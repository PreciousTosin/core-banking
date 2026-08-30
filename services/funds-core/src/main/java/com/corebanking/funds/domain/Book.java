package com.corebanking.funds.domain;

import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

public record Book(
    UUID id,
    UUID legalEntityId,
    CurrencyCode functionalCurrency,
    ZoneId timezone,
    String calendar,
    String policyVersion) {

    public Book {
        id = Objects.requireNonNull(id, "id");
        legalEntityId = Objects.requireNonNull(legalEntityId, "legalEntityId");
        functionalCurrency = Objects.requireNonNull(functionalCurrency, "functionalCurrency");
        timezone = Objects.requireNonNull(timezone, "timezone");
        calendar = requireNonBlank(calendar, "calendar");
        policyVersion = requireNonBlank(policyVersion, "policyVersion");
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
