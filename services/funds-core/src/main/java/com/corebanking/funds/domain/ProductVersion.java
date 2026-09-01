package com.corebanking.funds.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record ProductVersion(
    UUID id,
    ProductDefinition productDefinition,
    int version,
    DepositProductKind productKind,
    FinancePrinciple financePrinciple,
    Instant effectiveFrom,
    Instant effectiveTo,
    String approvalReference,
    String policyJsonHash) {

    public ProductVersion {
        id = Objects.requireNonNull(id, "id");
        productDefinition = Objects.requireNonNull(productDefinition, "productDefinition");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        productKind = Objects.requireNonNull(productKind, "productKind");
        financePrinciple = Objects.requireNonNull(financePrinciple, "financePrinciple");
        effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");
        }
        approvalReference = requireNonBlank(approvalReference, "approvalReference");
        policyJsonHash = requireSha256Hex(policyJsonHash);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String requireSha256Hex(String value) {
        value = requireNonBlank(value, "policyJsonHash");
        if (!value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("policyJsonHash must be a 64-character hexadecimal SHA-256 hash");
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
