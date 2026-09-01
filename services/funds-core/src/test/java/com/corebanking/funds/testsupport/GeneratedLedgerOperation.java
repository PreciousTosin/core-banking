package com.corebanking.funds.testsupport;

import java.util.UUID;

public sealed interface GeneratedLedgerOperation {
    record Post(UUID commandId, long amount) implements GeneratedLedgerOperation {}

    record RetrySame(UUID commandId) implements GeneratedLedgerOperation {}

    record RetryDifferentHash(UUID commandId) implements GeneratedLedgerOperation {}

    record Reverse(UUID commandId, UUID originalJournalId) implements GeneratedLedgerOperation {}

    record SubmitUnbalanced(UUID commandId, long debit, long credit) implements GeneratedLedgerOperation {}
}
