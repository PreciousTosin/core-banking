package com.corebanking.funds.domain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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
        if (signedMinorUnits == 0) {
            throw new IllegalArgumentException("signedMinorUnits must be non-zero");
        }
        if (signedMinorUnits == Long.MIN_VALUE) {
            throw new IllegalArgumentException(
                "signedMinorUnits must be exactly reversible and cannot equal Long.MIN_VALUE");
        }
    }
}
