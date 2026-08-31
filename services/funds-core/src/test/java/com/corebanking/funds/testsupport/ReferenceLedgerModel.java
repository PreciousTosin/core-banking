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

/** An in-memory accounting oracle that has no database or production-service dependency. */
public final class ReferenceLedgerModel {
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private final Map<UUID, String> accountControls;
    private final Map<UUID, BigInteger> accountTotals = new LinkedHashMap<>();
    private final Map<ControlKey, BigInteger> controlTotals = new LinkedHashMap<>();
    private final Map<UUID, SuccessfulCommand> successfulCommands = new LinkedHashMap<>();
    private final Map<UUID, StoredJournal> journals = new LinkedHashMap<>();
    private final Set<UUID> expectedOutboxIds = new LinkedHashSet<>();

    public ReferenceLedgerModel(Map<UUID, String> accountControls) {
        this.accountControls = Map.copyOf(Objects.requireNonNull(accountControls, "accountControls"));
        this.accountControls.forEach((accountId, control) -> accountTotals.put(accountId, BigInteger.ZERO));
    }

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

    /** Mutates the oracle only after a real command has returned successfully. */
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

    public static Class<? extends RuntimeException> exceptionType(ExpectedOutcome outcome) {
        return switch (outcome) {
            case INVALID_JOURNAL -> InvalidJournalException.class;
            case IDEMPOTENCY_CONFLICT -> IdempotencyConflictException.class;
            case MONETARY_OVERFLOW -> MonetaryOverflowException.class;
            case NEW_SUCCESS, SUCCESSFUL_RETRY -> null;
        };
    }

    private static UUID outboxId(UUID journalId) {
        return UUID.nameUUIDFromBytes(("JournalPosted:" + journalId).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean outsideLong(BigInteger value) {
        return value.compareTo(LONG_MIN) < 0 || value.compareTo(LONG_MAX) > 0;
    }

    public enum ExpectedOutcome {
        NEW_SUCCESS,
        SUCCESSFUL_RETRY,
        INVALID_JOURNAL,
        IDEMPOTENCY_CONFLICT,
        MONETARY_OVERFLOW
    }

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
