package com.corebanking.funds.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable, approved version of a product family. Customer ledger accounts bind to a
 * version, not a definition, and the version owns the kind, finance principle, effective
 * window, approval reference and policy hash. A later version is a new row; the
 * product_version_immutable trigger rejects UPDATE and DELETE, so historical classification
 * of existing accounts cannot be rewritten.
 */
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

    // Normalised to lowercase so equal policy documents compare equal regardless of hex case.
    private static String requireSha256Hex(String value) {
        value = requireNonBlank(value, "policyJsonHash");
        if (!value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("policyJsonHash must be a 64-character hexadecimal SHA-256 hash");
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
