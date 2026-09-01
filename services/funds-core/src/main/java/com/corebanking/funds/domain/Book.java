package com.corebanking.funds.domain;

import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * The accounting book a journal posts into: one legal entity, functional currency and
 * timezone. The timezone turns a journal's booking instant into the book-local date that must
 * fall inside its accounting period. policyVersion is the book's current accounting policy;
 * a journal whose policyVersion differs is rejected at commit.
 */
public record Book(
    UUID id,
    UUID legalEntityId,
    CurrencyCode functionalCurrency,
    ZoneId timezone,
    String calendar,
    int policyVersion) {

    public Book {
        id = Objects.requireNonNull(id, "id");
        legalEntityId = Objects.requireNonNull(legalEntityId, "legalEntityId");
        functionalCurrency = Objects.requireNonNull(functionalCurrency, "functionalCurrency");
        timezone = Objects.requireNonNull(timezone, "timezone");
        calendar = requireNonBlank(calendar, "calendar");
        if (policyVersion < 1) {
            throw new IllegalArgumentException("policyVersion must be positive");
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
