package com.corebanking.funds.runtime;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.ConfigProvider;

@Startup
@ApplicationScoped
@IfBuildProfile("prod")
public class ProdDatasourceStartupGuard {
    private static final String ACTIVE = "quarkus.datasource.active";
    private static final String JDBC_URL = "quarkus.datasource.jdbc.url";
    private static final String USERNAME = "quarkus.datasource.username";
    private static final String PASSWORD = "quarkus.datasource.password";

    @PostConstruct
    void validateConfiguredDatasource() {
        var config = ConfigProvider.getConfig();
        validate(
            config.getOptionalValue(ACTIVE, String.class).orElse(null),
            config.getOptionalValue(JDBC_URL, String.class).orElse(null),
            config.getOptionalValue(USERNAME, String.class).orElse(null),
            config.getOptionalValue(PASSWORD, String.class).orElse(null));
    }

    static void validate(String active, String jdbcUrl, String username, String password) {
        if (!"true".equalsIgnoreCase(active)) {
            throw missingOrBlank(ACTIVE);
        }
        requireNonBlank(JDBC_URL, jdbcUrl);
        requireNonBlank(USERNAME, username);
        requireNonBlank(PASSWORD, password);
    }

    private static void requireNonBlank(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            throw missingOrBlank(propertyName);
        }
    }

    private static IllegalStateException missingOrBlank(String propertyName) {
        return new IllegalStateException(
            "Production datasource configuration is missing or blank: " + propertyName);
    }
}
