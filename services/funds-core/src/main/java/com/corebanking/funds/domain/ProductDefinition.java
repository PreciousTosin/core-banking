package com.corebanking.funds.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * The stable commercial family a product belongs to. Deliberately carries no kind or finance
 * principle: those live on each immutable ProductVersion (moved there by V005) so that adding
 * a version cannot reclassify accounts already bound to an earlier one.
 */
public record ProductDefinition(UUID id, String productCode) {
    public ProductDefinition {
        id = Objects.requireNonNull(id, "id");
        productCode = requireNonBlank(productCode, "productCode");
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
