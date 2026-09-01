package com.corebanking.funds.testsupport;

import java.util.UUID;

/**
 * One step of a generated ledger history for AccountingStateMachineIT. Operations are produced
 * from a seeded SplittableRandom and carry their own command identities, so a failing history can
 * be replayed step for step from the seed. The sealed set is the IT's whole operation vocabulary;
 * it turns each into a concrete PostingCommand and checks the ReferenceLedgerModel's prediction.
 */
public sealed interface GeneratedLedgerOperation {
    /** New balanced two-line journal; expected NEW_SUCCESS unless a running total overflows. */
    record Post(UUID commandId, long amount) implements GeneratedLedgerOperation {}

    /** Resubmits an already successful command unchanged; expected SUCCESSFUL_RETRY. */
    record RetrySame(UUID commandId) implements GeneratedLedgerOperation {}

    /** Reuses a successful commandId with different content; expected IDEMPOTENCY_CONFLICT. */
    record RetryDifferentHash(UUID commandId) implements GeneratedLedgerOperation {}

    /** Exact linked reversal of a not-yet-reversed original, submitted via the trusted path. */
    record Reverse(UUID commandId, UUID originalJournalId) implements GeneratedLedgerOperation {}

    /** Debit and credit that differ by one minor unit; expected INVALID_JOURNAL. */
    record SubmitUnbalanced(UUID commandId, long debit, long credit) implements GeneratedLedgerOperation {}
}
