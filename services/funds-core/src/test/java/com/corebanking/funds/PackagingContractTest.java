package com.corebanking.funds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

class PackagingContractTest {
    private static final Path MODULE = Path.of("").toAbsolutePath();
    private static final Map<String, String> CONTROLLED_PROPERTIES = Map.ofEntries(
        Map.entry("quarkus.datasource.db-kind", "postgresql"),
        Map.entry("quarkus.datasource.jdbc.min-size", "2"),
        Map.entry("quarkus.datasource.jdbc.max-size", "8"),
        Map.entry("quarkus.datasource.jdbc.acquisition-timeout", "5S"),
        Map.entry("quarkus.datasource.jdbc.leak-detection-interval", "30S"),
        Map.entry("quarkus.flyway.migrate-at-start", "false"),
        Map.entry("quarkus.http.limits.max-body-size", "128K"),
        Map.entry("quarkus.micrometer.export.prometheus.enabled", "true"),
        Map.entry("%prod.quarkus.datasource.active", "true"),
        Map.entry("%prod.quarkus.datasource.jdbc.url", "${FUNDS_DB_JDBC_URL}"),
        Map.entry("%prod.quarkus.datasource.username", "${FUNDS_APP_DB_USER}"),
        Map.entry("%prod.quarkus.datasource.password", "${FUNDS_APP_DB_PASSWORD}"));

    @Test
    void productionConfigurationHasOneEffectiveAssignmentForEveryBound() throws Exception {
        String source = read("src/main/resources/application.properties");
        var properties = new Properties();
        properties.load(new StringReader(source));

        CONTROLLED_PROPERTIES.forEach((key, expected) -> {
            assertEquals(expected, properties.getProperty(key), key);
            assertEquals(1, assignmentCounts(source).getOrDefault(key, 0), key + " must have one active assignment");
        });
        assertFalse(properties.values().stream().anyMatch(value -> value.toString().contains("jdbc:postgresql://")),
            "production JDBC endpoint must not be embedded");
    }

    @Test
    void pomBindsExactlyOneQuarkusBuildGoal() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var document = factory.newDocumentBuilder().parse(MODULE.resolve("pom.xml").toFile());
        var plugins = document.getElementsByTagNameNS("*", "plugin");
        int quarkusPlugins = 0;
        int executions = 0;
        int totalGoals = 0;
        int buildGoals = 0;
        for (int index = 0; index < plugins.getLength(); index++) {
            var plugin = (Element) plugins.item(index);
            if (!"quarkus-maven-plugin".equals(directChildText(plugin, "artifactId"))) {
                continue;
            }
            quarkusPlugins++;
            assertEquals("io.quarkus", directChildText(plugin, "groupId"));
            var executionNodes = plugin.getElementsByTagNameNS("*", "execution");
            executions += executionNodes.getLength();
            for (int executionIndex = 0; executionIndex < executionNodes.getLength(); executionIndex++) {
                var goals = ((Element) executionNodes.item(executionIndex)).getElementsByTagNameNS("*", "goal");
                totalGoals += goals.getLength();
                for (int goalIndex = 0; goalIndex < goals.getLength(); goalIndex++) {
                    if ("build".equals(goals.item(goalIndex).getTextContent().trim())) {
                        buildGoals++;
                    }
                }
            }
        }
        assertEquals(1, quarkusPlugins);
        assertEquals(1, executions);
        assertEquals(1, totalGoals);
        assertEquals(1, buildGoals);
    }

    @Test
    void dockerfileIsTheCompletePinnedNonRootRuntimeContract() throws IOException {
        List<String> directives = read("Dockerfile.jvm").lines()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .toList();

        assertEquals(List.of(
            "FROM eclipse-temurin:25-jre@sha256:f9e65324a37f28209ce7dd0e5149a7aa954520ed936fb87813cf6ded2400a112",
            "WORKDIR /work",
            "COPY target/quarkus-app/lib/ /work/lib/",
            "COPY target/quarkus-app/*.jar /work/",
            "COPY target/quarkus-app/app/ /work/app/",
            "COPY target/quarkus-app/quarkus/ /work/quarkus/",
            "USER 10001",
            "ENV JAVA_TOOL_OPTIONS=\"-Xms128m -Xmx384m -XX:MaxMetaspaceSize=96m -XX:MaxDirectMemorySize=64m -Xss512k -XX:+ExitOnOutOfMemoryError\"",
            "ENTRYPOINT [\"java\",\"-jar\",\"/work/quarkus-run.jar\"]"), directives);
        assertFalse(directives.stream().anyMatch(line -> line.contains("HeapDumpOnOutOfMemoryError")));
    }

    @Test
    void documentationHasUniqueRequiredSectionsCoverageAndExclusions() throws IOException {
        String readme = read("README.md");
        String health = read("docs/health-contract.md");

        assertUniqueHeadings(readme, Set.of("Reading the accounting model", "Identity and product foundations",
            "Database roles and startup", "Build and verification", "Memory boundary",
            "Acceptance coverage and limits", "Explicit exclusions", "Base-image review and refresh"));
        assertUniqueHeadings(health, Set.of("Endpoints", "Database and migration prerequisite",
            "Resource and failure semantics"));

        var expectedAcceptance = Set.of("ACC-01", "ACC-02", "ACC-19", "ACC-20", "ACC-24", "ACC-25",
            "ACC-29", "ACC-32", "ACC-38", "ACC-40", "ACC-42");
        var acceptanceRows = matches(readme, Pattern.compile("(?m)^\\| (ACC-\\d+) \\|"));
        assertEquals(expectedAcceptance, new HashSet<>(acceptanceRows));
        assertEquals(expectedAcceptance.size(), acceptanceRows.size(), "each acceptance row must occur exactly once");

        var expectedExclusions = Set.of("Identifier issuance/resolution APIs", "Real or simulated NIP", "Account-details projection",
            "Accrual/capitalisation/maturity", "Non-interest allocation", "Holds", "Go contracts", "Event relay",
            "Providers", "Reconciliation", "FX execution", "Security UI", "Full 8 GiB orchestration");
        var exclusions = bulletsUnder(readme, "Explicit exclusions");
        assertEquals(expectedExclusions, new HashSet<>(exclusions));
        assertEquals(expectedExclusions.size(), exclusions.size(), "each exclusion must occur exactly once");
        assertTrue(section(readme, "Database roles and startup").contains("fail closed before readiness can be UP"));
        assertTrue(section(readme, "Base-image review and refresh").contains("sha256:f9e65324"));
    }

    @Test
    void documentedRuntimeSmokeIsExecutable() throws IOException {
        Path smoke = MODULE.resolve("scripts/prod-runtime-smoke.sh");
        assertTrue(Files.isRegularFile(smoke));
        assertTrue(Files.isExecutable(smoke));
        assertEquals(1, section(read("README.md"), "Build and verification").lines()
            .filter(line -> line.equals("./scripts/prod-runtime-smoke.sh core-banking/funds-core:accounting-kernel"))
            .count());
    }

    private static Map<String, Integer> assignmentCounts(String source) {
        var counts = new HashMap<String, Integer>();
        source.lines().map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#") && !line.startsWith("!"))
            .forEach(line -> CONTROLLED_PROPERTIES.keySet().stream()
                .filter(key -> line.matches(Pattern.quote(key) + "(?:\\s*[:=]\\s*|\\s+).+"))
                .forEach(key -> counts.merge(key, 1, Integer::sum)));
        return counts;
    }

    private static String directChildText(Element parent, String localName) {
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
                return element.getTextContent().trim();
            }
        }
        return null;
    }

    private static void assertUniqueHeadings(String markdown, Set<String> expected) {
        var headings = matches(markdown, Pattern.compile("(?m)^## ([^#\\r\\n]+)$"));
        for (String heading : expected) {
            assertEquals(1, headings.stream().filter(heading::equals).count(), "heading: " + heading);
        }
    }

    private static List<String> matches(String value, Pattern pattern) {
        var result = new ArrayList<String>();
        var matcher = pattern.matcher(value);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    private static List<String> bulletsUnder(String markdown, String heading) {
        return Arrays.stream(section(markdown, heading).split("\\R"))
            .filter(line -> line.startsWith("- "))
            .map(line -> line.substring(2).trim())
            .toList();
    }

    private static String section(String markdown, String heading) {
        String marker = "## " + heading;
        int start = markdown.indexOf(marker);
        assertTrue(start >= 0, "missing heading: " + heading);
        int end = markdown.indexOf("\n## ", start + marker.length());
        return markdown.substring(start, end < 0 ? markdown.length() : end);
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(MODULE.resolve(relativePath));
    }
}
