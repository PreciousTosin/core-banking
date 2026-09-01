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

/**
 * Application-side admission rules for a journal draft. Each rule mirrors a database guard, so
 * a bad draft fails before any lock is taken and a bypass of this class still fails at commit:
 * per-currency zero sum (V003 journal_balanced_per_currency), 256 postings (V005
 * journal_reversible_posting_count), 32 dimensions and 8192 dimension bytes (V005
 * posting_dimensions_count_check / posting_dimensions_bytes_check). Zero and Long.MIN_VALUE
 * amounts never reach here: PostingLine rejects them, mirrored by the signed_minor_units CHECKs,
 * because an exact reversal must be able to negate every amount. Runs before the posting
 * transaction and again after account sequences are assigned.
 */
public class JournalValidator {
    // Must equal the V005 CHECKs on funds.posting and the 2..256 envelope enforced by
    // enforce_journal_reversibility; ReversalService reuses them as read bounds.
    public static final int MAX_POSTINGS_PER_JOURNAL = 256;
    public static final int MAX_DIMENSIONS_PER_POSTING = 32;
    public static final int MAX_DIMENSION_JSON_BYTES = 8_192;

    public void validate(JournalDraft draft) {
        Objects.requireNonNull(draft, "draft");
        // timestamptz keeps microseconds. Finer precision would be truncated on insert and the
        // journal re-hashed from the database would no longer match the draft.
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
            // Sized as PostgreSQL renders jsonb::text, so this agrees byte-for-byte with
            // octet_length(dimensions::text) <= 8192.
            if (PostingDimensions.jsonbTextBytes(posting.dimensions())
                > MAX_DIMENSION_JSON_BYTES) {
                throw new InvalidJournalException(
                    "posting dimension JSON exceeds POC byte limit of "
                        + MAX_DIMENSION_JSON_BYTES);
            }

            // Checked arithmetic: a running total outside the signed 64-bit range is a
            // MonetaryOverflowException, never a wrapped value that happens to sum to zero.
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
