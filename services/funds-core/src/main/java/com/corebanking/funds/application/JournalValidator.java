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
    public void validate(JournalDraft draft) {
        Objects.requireNonNull(draft, "draft");
        var postingIds = new HashSet<UUID>();
        Map<CurrencyCode, Long> currencyTotals = new HashMap<>();

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
