package com.corebanking.funds.domain;

import java.util.Objects;
import java.util.UUID;

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
