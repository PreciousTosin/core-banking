package com.corebanking.funds;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PackagingContractTest {
    private static final Path MODULE = Path.of("").toAbsolutePath();

    @Test
    void productionConfigurationIsBoundedAndDeploymentSupplied() throws IOException {
        String config = read("src/main/resources/application.properties");

        assertContains(config,
            "quarkus.datasource.db-kind=postgresql",
            "quarkus.datasource.jdbc.min-size=2",
            "quarkus.datasource.jdbc.max-size=8",
            "quarkus.datasource.jdbc.acquisition-timeout=5S",
            "quarkus.datasource.jdbc.leak-detection-interval=30S",
            "quarkus.flyway.migrate-at-start=false",
            "quarkus.http.limits.max-body-size=128K",
            "quarkus.micrometer.export.prometheus.enabled=true",
            "%prod.quarkus.datasource.jdbc.url=${FUNDS_DB_JDBC_URL}",
            "%prod.quarkus.datasource.username=${FUNDS_APP_DB_USER}",
            "%prod.quarkus.datasource.password=${FUNDS_APP_DB_PASSWORD}");
        assertFalse(config.contains("jdbc:postgresql://"), "production JDBC endpoint must not be embedded");
    }

    @Test
    void imageRunsJava25AsNonRootWithExplicitMemoryCaps() throws IOException {
        String dockerfile = read("Dockerfile.jvm");
        String pom = read("pom.xml");

        assertContains(dockerfile,
            "FROM eclipse-temurin:25-jre",
            "USER 10001",
            "-Xms128m",
            "-Xmx384m",
            "-XX:MaxMetaspaceSize=96m",
            "-XX:MaxDirectMemorySize=64m",
            "-Xss512k",
            "-XX:+ExitOnOutOfMemoryError",
            "ENTRYPOINT [\"java\",\"-jar\",\"/work/quarkus-run.jar\"]");
        assertFalse(dockerfile.contains("HeapDumpOnOutOfMemoryError"), "heap dumps must be opt-in");
        assertContains(pom, "<goal>build</goal>");
    }

    @Test
    void operatorDocumentationStatesImplementedBoundaries() throws IOException {
        String readme = read("README.md");
        String health = read("docs/health-contract.md");

        assertContains(readme,
            "Positive amounts are debits",
            "0000000017",
            "SIMULATOR_ONLY",
            "ACC-01",
            "ACC-42",
            "no accounting test is intentionally skipped",
            "six versioned migrations",
            "No application cache stores balances or journals");
        assertContains(health,
            "/q/health/live",
            "/q/health/ready",
            "/q/metrics",
            "funds_app",
            "funds_migrator");
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(MODULE.resolve(relativePath));
    }

    private static void assertContains(String value, String... expected) {
        for (String item : expected) {
            assertTrue(value.contains(item), () -> "missing contract text: " + item);
        }
    }
}
