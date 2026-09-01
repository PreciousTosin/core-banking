package com.corebanking.funds.application;

import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.exception.InvalidJournalException;
import com.corebanking.funds.domain.exception.MonetaryOverflowException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class JournalValidator {
    public static final int MAX_POSTINGS_PER_JOURNAL = 256;
    public static final int MAX_DIMENSIONS_PER_POSTING = 32;
    public static final int MAX_DIMENSION_JSON_BYTES = 8_192;

    public void validate(JournalDraft draft) {
        Objects.requireNonNull(draft, "draft");
        if (draft.bookingTime().getNano() % 1_000 != 0) {
            throw new InvalidJournalException(
                "bookingTime must use PostgreSQL-compatible microsecond precision");
        }
        var postingIds = new HashSet<UUID>();
        Map<CurrencyCode, Long> currencyTotals = new HashMap<>();
        if (draft.postings().size() > MAX_POSTINGS_PER_JOURNAL) {
            throw new InvalidJournalException(
                "journal exceeds POC posting limit of " + MAX_POSTINGS_PER_JOURNAL);
        }

        for (var posting : draft.postings()) {
            if (posting.postingId() == null) {
                throw new InvalidJournalException("postingId must not be null");
            }
            if (posting.accountId() == null) {
                throw new InvalidJournalException("accountId must not be null");
            }
            if (!postingIds.add(posting.postingId())) {
                throw new InvalidJournalException("duplicate postingId: " + posting.postingId());
            }
            if (posting.dimensions().size() > MAX_DIMENSIONS_PER_POSTING) {
                throw new InvalidJournalException(
                    "posting exceeds POC dimension limit of " + MAX_DIMENSIONS_PER_POSTING);
            }
            if (PostingDimensions.jsonbTextBytes(posting.dimensions())
                > MAX_DIMENSION_JSON_BYTES) {
                throw new InvalidJournalException(
                    "posting dimension JSON exceeds POC byte limit of "
                        + MAX_DIMENSION_JSON_BYTES);
            }

            try {
                currencyTotals.merge(posting.currency(), posting.signedMinorUnits(), Math::addExact);
            } catch (ArithmeticException overflow) {
                throw new MonetaryOverflowException(overflow);
            }
        }

        currencyTotals.forEach((currency, total) -> {
            if (total != 0) {
                throw new InvalidJournalException("journal is unbalanced for currency " + currency.value());
            }
        });
    }
}
