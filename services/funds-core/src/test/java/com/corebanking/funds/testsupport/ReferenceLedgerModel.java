package com.corebanking.funds.testsupport;

import com.corebanking.funds.application.PostingCommand;
import com.corebanking.funds.application.PostingResult;
import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.PostingLine;
import com.corebanking.funds.domain.exception.IdempotencyConflictException;
import com.corebanking.funds.domain.exception.InvalidJournalException;
import com.corebanking.funds.domain.exception.MonetaryOverflowException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An in-memory accounting oracle that has no database or production-service dependency. It models
 * only what AccountingStateMachineIT compares against PostgreSQL: per-currency balance, per-account
 * and per-control signed totals in BigInteger (so a long overflow is predicted rather than
 * suffered), same-hash versus different-hash command replay, which journals are still reversible,
 * and the deterministic outbox identity of each journal. It deliberately does not model periods,
 * chart governance, account sequences, transaction deadlines or the database's exact-negation and
 * one-reversal constraints; the generator only proposes reversals this model says are eligible, and
 * anything the kernel rejects beyond that is a test failure, not a predicted outcome.
 */
public final class ReferenceLedgerModel {
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private final Map<UUID, String> accountControls;
    private final Map<UUID, BigInteger> accountTotals = new LinkedHashMap<>();
    private final Map<ControlKey, BigInteger> controlTotals = new LinkedHashMap<>();
    private final Map<UUID, SuccessfulCommand> successfulCommands = new LinkedHashMap<>();
    private final Map<UUID, StoredJournal> journals = new LinkedHashMap<>();
    private final Set<UUID> expectedOutboxIds = new LinkedHashSet<>();

    /**
     * Fixes the account universe and each account's control code. An account not in this map is
     * treated as unpostable, so predictions never depend on database lookups.
     */
    public ReferenceLedgerModel(Map<UUID, String> accountControls) {
        this.accountControls = Map.copyOf(Objects.requireNonNull(accountControls, "accountControls"));
        this.accountControls.forEach((accountId, control) -> accountTotals.put(accountId, BigInteger.ZERO));
    }

    /**
     * Predicts the kernel's outcome without mutating the model. Order matters and mirrors the
     * service: idempotency (same or different hash) is decided first, then per-currency balance,
     * then whether any resulting account or control total would leave the signed 64-bit range.
     */
    public ExpectedOutcome predict(PostingCommand command) {
        SuccessfulCommand previous = successfulCommands.get(command.commandId());
        if (previous != null) {
            return previous.requestHash().equals(command.requestHash())
                ? ExpectedOutcome.SUCCESSFUL_RETRY
                : ExpectedOutcome.IDEMPOTENCY_CONFLICT;
        }

        Map<CurrencyCode, BigInteger> currencyTotals = new LinkedHashMap<>();
        Map<UUID, BigInteger> accountDeltas = new LinkedHashMap<>();
        Map<ControlKey, BigInteger> controlDeltas = new LinkedHashMap<>();
        for (PostingLine line : command.journal().postings()) {
            BigInteger amount = BigInteger.valueOf(line.signedMinorUnits());
            currencyTotals.merge(line.currency(), amount, BigInteger::add);
            accountDeltas.merge(line.accountId(), amount, BigInteger::add);
            String control = accountControls.get(line.accountId());
            if (control == null) {
                return ExpectedOutcome.INVALID_JOURNAL;
            }
            controlDeltas.merge(new ControlKey(control, line.currency()), amount, BigInteger::add);
        }
        if (currencyTotals.values().stream().anyMatch(total -> total.signum() != 0)) {
            return ExpectedOutcome.INVALID_JOURNAL;
        }
        boolean accountOverflow = accountDeltas.entrySet().stream().anyMatch(entry ->
            outsideLong(accountTotals.getOrDefault(entry.getKey(), BigInteger.ZERO).add(entry.getValue())));
        boolean controlOverflow = controlDeltas.entrySet().stream().anyMatch(entry ->
            outsideLong(controlTotals.getOrDefault(entry.getKey(), BigInteger.ZERO).add(entry.getValue())));
        return accountOverflow || controlOverflow
            ? ExpectedOutcome.MONETARY_OVERFLOW
            : ExpectedOutcome.NEW_SUCCESS;
    }

    /**
     * Mutates the oracle only after a real command has returned successfully. A same-hash retry
     * must return the stored result unchanged and leaves the model untouched; a success the model
     * did not predict is an oracle/kernel disagreement and fails immediately.
     */
    public void apply(PostingCommand command, PostingResult result) {
        ExpectedOutcome prediction = predict(command);
        if (prediction == ExpectedOutcome.SUCCESSFUL_RETRY) {
            SuccessfulCommand previous = successfulCommands.get(command.commandId());
            if (!previous.result().equals(result)) {
                throw new AssertionError("same-hash retry returned a different result");
            }
            return;
        }
        if (prediction != ExpectedOutcome.NEW_SUCCESS) {
            throw new AssertionError("real command succeeded although oracle predicted " + prediction);
        }

        List<ModelLine> lines = new ArrayList<>(command.journal().postings().size());
        for (PostingLine line : command.journal().postings()) {
            BigInteger amount = BigInteger.valueOf(line.signedMinorUnits());
            accountTotals.merge(line.accountId(), amount, BigInteger::add);
            var controlKey = new ControlKey(accountControls.get(line.accountId()), line.currency());
            controlTotals.merge(controlKey, amount, BigInteger::add);
            lines.add(new ModelLine(
                line.postingId(),
                line.accountId(),
                line.currency(),
                line.signedMinorUnits(),
                line.dimensions()));
        }
        var storedJournal = new StoredJournal(
            result.journalId(),
            command.journal().reversalOfJournalId(),
            result.canonicalHash(),
            List.copyOf(lines));
        var successful = new SuccessfulCommand(command, command.requestHash(), result);
        successfulCommands.put(command.commandId(), successful);
        journals.put(result.journalId(), storedJournal);
        expectedOutboxIds.add(outboxId(result.journalId()));
    }

    public List<UUID> successfulCommandIds() {
        return List.copyOf(successfulCommands.keySet());
    }

    public List<UUID> journalIds() {
        return List.copyOf(journals.keySet());
    }

    /**
     * Journals eligible for their first exact reversal under the database-wide correction rule:
     * originals only (a reversal is never itself reversed) that no stored reversal already links
     * to, matching one_reversal_per_original_idx.
     */
    public List<UUID> reversibleJournalIds() {
        Set<UUID> alreadyReversed = new LinkedHashSet<>();
        journals.values().stream()
            .map(StoredJournal::reversalOfJournalId)
            .filter(Objects::nonNull)
            .forEach(alreadyReversed::add);
        return journals.values().stream()
            .filter(journal -> journal.reversalOfJournalId() == null)
            .map(StoredJournal::journalId)
            .filter(journalId -> !alreadyReversed.contains(journalId))
            .toList();
    }

    public SuccessfulCommand successfulCommand(UUID commandId) {
        SuccessfulCommand command = successfulCommands.get(commandId);
        if (command == null) {
            throw new IllegalArgumentException("unknown successful command " + commandId);
        }
        return command;
    }

    public StoredJournal journal(UUID journalId) {
        StoredJournal journal = journals.get(journalId);
        if (journal == null) {
            throw new IllegalArgumentException("unknown journal " + journalId);
        }
        return journal;
    }

    public Map<UUID, BigInteger> accountTotals() {
        return Map.copyOf(accountTotals);
    }

    public Map<ControlKey, BigInteger> controlTotals() {
        return Map.copyOf(controlTotals);
    }

    public Map<UUID, StoredJournal> journals() {
        return Map.copyOf(journals);
    }

    public Set<UUID> expectedOutboxIds() {
        return Set.copyOf(expectedOutboxIds);
    }

    public int successfulCommandCount() {
        return successfulCommands.size();
    }

    public int postingCount() {
        return journals.values().stream().mapToInt(journal -> journal.lines().size()).sum();
    }

    /** The exception the kernel must throw for a rejected outcome; null for the success cases. */
    public static Class<? extends RuntimeException> exceptionType(ExpectedOutcome outcome) {
        return switch (outcome) {
            case INVALID_JOURNAL -> InvalidJournalException.class;
            case IDEMPOTENCY_CONFLICT -> IdempotencyConflictException.class;
            case MONETARY_OVERFLOW -> MonetaryOverflowException.class;
            case NEW_SUCCESS, SUCCESSFUL_RETRY -> null;
        };
    }

    // Independently re-derives the outbox event id the way JdbcLedgerRepository does, so the
    // test proves the id is a pure function of the journal rather than reading it back.
    private static UUID outboxId(UUID journalId) {
        return UUID.nameUUIDFromBytes(("JournalPosted:" + journalId).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean outsideLong(BigInteger value) {
        return value.compareTo(LONG_MIN) < 0 || value.compareTo(LONG_MAX) > 0;
    }

    /** The five kernel responses the oracle can predict; see exceptionType for the mapping. */
    public enum ExpectedOutcome {
        NEW_SUCCESS,
        SUCCESSFUL_RETRY,
        INVALID_JOURNAL,
        IDEMPOTENCY_CONFLICT,
        MONETARY_OVERFLOW
    }

    /** Control totals are per control code and currency, as in control_account_projection. */
    public record ControlKey(String controlAccountCode, CurrencyCode currency) {
        public ControlKey {
            Objects.requireNonNull(controlAccountCode, "controlAccountCode");
            Objects.requireNonNull(currency, "currency");
        }
    }

    public record ModelLine(
        UUID postingId,
        UUID accountId,
        CurrencyCode currency,
        long signedMinorUnits,
        Map<String, String> dimensions
    ) {
        public ModelLine {
            dimensions = Map.copyOf(dimensions);
        }
    }

    /** What the model keeps of a committed journal: enough to build its exact reversal later. */
    public record StoredJournal(
        UUID journalId,
        UUID reversalOfJournalId,
        String canonicalHash,
        List<ModelLine> lines
    ) {
        public StoredJournal {
            lines = List.copyOf(lines);
        }
    }

    public record SuccessfulCommand(PostingCommand command, String requestHash, PostingResult result) {}
}
