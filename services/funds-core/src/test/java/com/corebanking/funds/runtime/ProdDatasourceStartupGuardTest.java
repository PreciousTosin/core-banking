package com.corebanking.funds.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProdDatasourceStartupGuardTest {
    @Test
    void rejectsEachMissingOrBlankDatasourceFieldWithoutEchoingValues() {
        assertRejected("quarkus.datasource.jdbc.url", null, "funds_app", "sensitive-password");
        assertRejected("quarkus.datasource.username", "jdbc:postgresql://db/funds", " ", "sensitive-password");
        assertRejected("quarkus.datasource.password", "jdbc:postgresql://db/funds", "funds_app", "\t");
    }

    @Test
    void acceptsCompleteResolvedDatasourceValues() {
        assertDoesNotThrow(() -> ProdDatasourceStartupGuard.validate(
            "jdbc:postgresql://db/funds", "funds_app", "sensitive-password"));
    }

    private static void assertRejected(String expectedField, String url, String username, String password) {
        var error = assertThrows(IllegalStateException.class,
            () -> ProdDatasourceStartupGuard.validate(url, username, password));
        assertEquals("Production datasource configuration is missing or blank: " + expectedField, error.getMessage());
        assertFalse(error.getMessage().contains("sensitive-password"));
        assertFalse(error.getMessage().contains("jdbc:postgresql"));
    }
}
