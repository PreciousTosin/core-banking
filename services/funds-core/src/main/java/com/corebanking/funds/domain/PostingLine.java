package com.corebanking.funds.domain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One side of a journal: a signed amount against a ledger account. Positive minor units debit,
 * negative credit. postingId, accountId, currency, amount and dimensions are pinned into the
 * canonical hash; accountSequence is not. It is the account's monotonic posting position,
 * assigned by the repository inside the posting transaction (drafts carry 0) and stored under
 * UNIQUE (account_id, account_sequence).
 *
 * <p>postingId and accountId are not null-checked here because the canonical hasher tolerates
 * nulls; JournalValidator rejects them before anything reaches the database.
 */
public record PostingLine(
    UUID postingId,
    UUID accountId,
    CurrencyCode currency,
    long signedMinorUnits,
    long accountSequence,
    Map<String, String> dimensions) {

    public PostingLine {
        currency = Objects.requireNonNull(currency, "currency");
        dimensions = Map.copyOf(Objects.requireNonNull(dimensions, "dimensions"));
        // Mirrors the posting table CHECK (signed_minor_units <> 0).
        if (signedMinorUnits == 0) {
            throw new IllegalArgumentException("signedMinorUnits must be non-zero");
        }
        // A reversal negates every original amount with Math.negateExact. Long.MIN_VALUE is the
        // one value that cannot be negated, so it is refused at admission rather than letting
        // an original become irreversible later.
        if (signedMinorUnits == Long.MIN_VALUE) {
            throw new IllegalArgumentException(
                "signedMinorUnits must be exactly reversible and cannot equal Long.MIN_VALUE");
        }
    }
}
