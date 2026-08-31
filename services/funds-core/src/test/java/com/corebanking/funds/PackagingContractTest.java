package com.corebanking.funds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

class PackagingContractTest {
    private static final String MODULE_REPOSITORY_PATH = "services/funds-core";
    private static final Set<String> CONTRACT_INPUTS = Set.of(
        "pom.xml",
        "src/main/resources/application.properties",
        "Dockerfile.jvm",
        "README.md",
        "docs/health-contract.md",
        "scripts/prod-runtime-smoke.sh");
    private static final Path MODULE = resolveModuleRoot(actualCodeSource());
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
    void modulePathMatchesAnIndependentTrackedGitAnchor() throws Exception {
        assertEquals(independentExpectedModule(), resolveModuleRoot(actualCodeSource()));
    }

    @Test
    void modulePathIgnoresCallerSuppliedBasedirOverride(@TempDir Path temp) throws Exception {
        Path expected = independentExpectedModule();
        Path crafted = Files.createDirectory(temp.resolve("false-basedir"));
        String original = System.getProperty("funds.core.basedir");
        System.setProperty("funds.core.basedir", crafted.toString());
        try {
            assertEquals(expected, resolveModuleRoot(actualCodeSource()));
            assertFalse(expected.startsWith(crafted));
        } finally {
            if (original == null) {
                System.clearProperty("funds.core.basedir");
            } else {
                System.setProperty("funds.core.basedir", original);
            }
        }
    }

    @Test
    void symlinkedCodeSourceCanonicalizesToTheRealModuleBeforeWalkingParents(@TempDir Path temp) throws Exception {
        Path decoyModule = temp.resolve("decoy module with spaces");
        writeDecoyModule(decoyModule, "com.corebanking", "funds-core");
        Path linkedCodeSource = decoyModule.resolve("target/test-classes");
        Files.delete(linkedCodeSource);
        Files.createSymbolicLink(linkedCodeSource, Path.of(actualCodeSource()).toRealPath());
        try {
            Path resolved = resolveModuleRoot(linkedCodeSource.toUri());

            assertEquals(independentExpectedModule(), resolved);
            assertFalse(resolved.startsWith(decoyModule));
        } finally {
            Files.deleteIfExists(linkedCodeSource);
        }
    }

    @Test
    void regularDecoyModuleOutsideTheGitWorktreeFailsClosed(@TempDir Path temp) throws Exception {
        Path decoyModule = temp.resolve("regular-decoy");
        writeDecoyModule(decoyModule, "com.corebanking", "funds-core");

        var failure = assertThrows(IllegalStateException.class,
            () -> resolveModuleRoot(decoyModule.resolve("target/test-classes").toUri()));

        assertTrue(failure.getMessage().contains("exact Git-tracked path"), failure::getMessage);
    }

    @Test
    void symlinkedModuleSentinelsFailBeforeRepositoryValidation(@TempDir Path temp) throws Exception {
        for (String sentinel : List.of("pom.xml", "src/main/resources/application.properties")) {
            Path decoyModule = temp.resolve(sentinel.replace('/', '-') + "-decoy");
            writeDecoyModule(decoyModule, "com.corebanking", "funds-core");
            Path decoySentinel = decoyModule.resolve(sentinel);
            Files.delete(decoySentinel);
            Files.createSymbolicLink(decoySentinel, MODULE.resolve(sentinel));
            try {
                var failure = assertThrows(IllegalStateException.class,
                    () -> resolveContractInput(decoyModule, sentinel));

                assertTrue(failure.getMessage().contains(sentinel), failure::getMessage);
                assertTrue(failure.getMessage().contains("non-symbolic-link regular file"), failure::getMessage);
            } finally {
                Files.deleteIfExists(decoySentinel);
            }
        }
    }

    @Test
    void sentinelThroughASymlinkedParentDirectoryFailsCanonicalValidation(@TempDir Path temp) throws Exception {
        Path decoyModule = temp.resolve("symlinked-parent-decoy");
        writeDecoyModule(decoyModule, "com.corebanking", "funds-core");
        Path resources = decoyModule.resolve("src/main/resources");
        Files.delete(resources.resolve("application.properties"));
        Files.delete(resources);
        Files.createSymbolicLink(resources, MODULE.resolve("src/main/resources"));
        try {
            var failure = assertThrows(IllegalStateException.class,
                () -> resolveContractInput(decoyModule, "src/main/resources/application.properties"));

            assertTrue(failure.getMessage().contains("application.properties"), failure::getMessage);
            assertTrue(failure.getMessage().contains("symlink-free canonical path"), failure::getMessage);
        } finally {
            Files.deleteIfExists(resources);
        }
    }

    @Test
    void nonSentinelContractInputSymlinksFailBeforeContentOrMetadataAccess(@TempDir Path temp) throws Exception {
        for (String relativePath : List.of(
            "Dockerfile.jvm",
            "README.md",
            "docs/health-contract.md",
            "scripts/prod-runtime-smoke.sh")) {
            Path decoyModule = Files.createDirectory(temp.resolve(relativePath.replace('/', '-') + "-decoy"));
            Path decoyInput = decoyModule.resolve(relativePath);
            Files.createDirectories(decoyInput.getParent());
            Files.createSymbolicLink(decoyInput, MODULE.resolve(relativePath));
            try {
                var failure = assertThrows(IllegalStateException.class,
                    () -> {
                        if ("scripts/prod-runtime-smoke.sh".equals(relativePath)) {
                            isExecutableContractInput(decoyModule, relativePath);
                        } else {
                            read(decoyModule, relativePath);
                        }
                    });

                assertTrue(failure.getMessage().contains(relativePath), failure::getMessage);
                assertTrue(failure.getMessage().contains("non-symbolic-link regular file"), failure::getMessage);
            } finally {
                Files.deleteIfExists(decoyInput);
            }
        }
    }

    @Test
    void nestedContractInputsRejectSymlinkedParentDirectoriesBeforeRepositoryValidation(@TempDir Path temp)
        throws Exception {
        for (String relativePath : List.of("docs/health-contract.md", "scripts/prod-runtime-smoke.sh")) {
            Path decoyModule = Files.createDirectory(temp.resolve(relativePath.replace('/', '-') + "-parent-decoy"));
            Path externalParent = MODULE.resolve(relativePath).getParent();
            Path linkedParent = decoyModule.resolve(Path.of(relativePath).getParent());
            Files.createDirectories(linkedParent.getParent());
            Files.createSymbolicLink(linkedParent, externalParent);
            try {
                var failure = assertThrows(IllegalStateException.class,
                    () -> {
                        if ("scripts/prod-runtime-smoke.sh".equals(relativePath)) {
                            isExecutableContractInput(decoyModule, relativePath);
                        } else {
                            read(decoyModule, relativePath);
                        }
                    });

                assertTrue(failure.getMessage().contains(relativePath), failure::getMessage);
                assertTrue(failure.getMessage().contains("symlink-free canonical path"), failure::getMessage);
            } finally {
                Files.deleteIfExists(linkedParent);
            }
        }
    }

    @Test
    void exactTrackedContractPathIsAcceptedInAnIsolatedRepository(@TempDir Path temp) throws Exception {
        Path repository = Files.createDirectory(temp.resolve("tracked-repository"));
        Path module = repository.resolve("services/funds-core");
        Path dockerfile = writeContractFixture(module, "Dockerfile.jvm", "FROM scratch\n");
        initializeTrackedRepository(repository, "services/funds-core/Dockerfile.jvm");

        assertEquals(dockerfile.toRealPath(), resolveContractInput(module, "Dockerfile.jvm"));
    }

    @Test
    void untrackedContractPathFailsClosedInAnIsolatedRepository(@TempDir Path temp) throws Exception {
        Path repository = Files.createDirectory(temp.resolve("untracked-repository"));
        Path module = repository.resolve("services/funds-core");
        writeContractFixture(module, "Dockerfile.jvm", "FROM scratch\n");
        writeContractFixture(module, "README.md", "untracked\n");
        initializeTrackedRepository(repository, "services/funds-core/Dockerfile.jvm");

        var failure = assertThrows(IllegalStateException.class,
            () -> resolveContractInput(module, "README.md"));

        assertTrue(failure.getMessage().contains("README.md"), failure::getMessage);
        assertTrue(failure.getMessage().contains("exact Git-tracked path"), failure::getMessage);
    }

    @Test
    void nonExactRelativeContractPathIsRejectedBeforeFilesystemAccess(@TempDir Path temp) {
        var failure = assertThrows(IllegalStateException.class,
            () -> resolveContractInput(temp, "./Dockerfile.jvm"));

        assertTrue(failure.getMessage().contains("exact repository-relative spelling"), failure::getMessage);
    }

    @Test
    void wrongTrackedPomIdentityFailsClosed(@TempDir Path temp) throws Exception {
        Path repository = Files.createDirectory(temp.resolve("wrong-identity-repository"));
        Path decoyModule = repository.resolve(MODULE_REPOSITORY_PATH);
        writeDecoyModule(decoyModule, "example.decoy", "funds-core");
        initializeTrackedRepository(repository,
            "services/funds-core/pom.xml",
            "services/funds-core/src/main/resources/application.properties");

        var failure = assertThrows(IllegalStateException.class,
            () -> resolveModuleRoot(decoyModule.resolve("target/test-classes").toUri()));

        assertTrue(failure.getMessage().contains("com.corebanking:funds-core"), failure::getMessage);
    }

    @Test
    void duplicateTrackedPomIdentityFailsClosed(@TempDir Path temp) throws Exception {
        Path repository = Files.createDirectory(temp.resolve("duplicate-identity-repository"));
        Path decoyModule = repository.resolve(MODULE_REPOSITORY_PATH);
        writeDecoyModule(decoyModule, "com.corebanking", "funds-core");
        initializeTrackedRepository(repository,
            "services/funds-core/pom.xml",
            "services/funds-core/src/main/resources/application.properties");
        Path pom = decoyModule.resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom).replace(
            "<artifactId>funds-core</artifactId>",
            "<artifactId>funds-core</artifactId><artifactId>decoy</artifactId>"));

        var failure = assertThrows(IllegalStateException.class,
            () -> resolveModuleRoot(decoyModule.resolve("target/test-classes").toUri()));

        assertTrue(failure.getMessage().contains("com.corebanking:funds-core"), failure::getMessage);
    }

    @Test
    void productionConfigurationHasOneEffectiveAssignmentForEveryBound() throws Exception {
        String source = read(MODULE, "src/main/resources/application.properties");
        var properties = new Properties();
        properties.load(new StringReader(source));
        var assignments = assignmentCounts(source);

        CONTROLLED_PROPERTIES.forEach((key, expected) -> {
            assertEquals(expected, properties.getProperty(key), key);
            assertEquals(1, assignments.getOrDefault(key, 0), key + " must have one active assignment");
        });
        assertFalse(properties.values().stream().anyMatch(value -> value.toString().contains("jdbc:postgresql://")),
            "production JDBC endpoint must not be embedded");
    }

    @Test
    void semanticAssignmentCountingDetectsEscapedAndContinuedDuplicateKeys() throws IOException {
        String source = read(MODULE, "src/main/resources/application.properties");
        String escapedDuplicate = source + "\nquarkus.datasource.jdbc.max\\u002dsize=8\n";
        String continuedDuplicate = source + "\nquarkus.datasource.jdbc.max\\\n-size=8\n";

        assertEquals(2, assignmentCounts(escapedDuplicate).get("quarkus.datasource.jdbc.max-size"));
        assertEquals(2, assignmentCounts(continuedDuplicate).get("quarkus.datasource.jdbc.max-size"));
    }

    @Test
    void pomBindsExactlyOneQuarkusBuildGoal() throws Exception {
        assertPomContract(read(MODULE, "pom.xml"));
    }

    @Test
    void nestedPomConfigurationGoalsDoNotCountAsLifecycleBindings() throws Exception {
        String pom = read(MODULE, "pom.xml");
        String mutated = pom.replace("<extensions>true</extensions>", """
            <extensions>true</extensions>
            <configuration>
                <execution><goals><goal>build</goal></goals></execution>
            </configuration>""");
        assertDoesNotThrow(() -> assertPomContract(mutated));
    }

    private static void assertPomContract(String source) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(source)));
        var project = document.getDocumentElement();
        var build = singleDirectChild(project, "build");
        var plugins = singleDirectChild(build, "plugins");
        var quarkusPlugins = directChildren(plugins, "plugin").stream()
            .filter(plugin -> "io.quarkus".equals(directChildText(plugin, "groupId")))
            .filter(plugin -> "quarkus-maven-plugin".equals(directChildText(plugin, "artifactId")))
            .toList();
        assertEquals(1, quarkusPlugins.size());
        var executions = singleDirectChild(quarkusPlugins.getFirst(), "executions");
        var execution = directChildren(executions, "execution");
        assertEquals(1, execution.size());
        var goals = singleDirectChild(execution.getFirst(), "goals");
        var goal = directChildren(goals, "goal");
        assertEquals(1, goal.size());
        assertEquals("build", goal.getFirst().getTextContent().trim());
    }

    @Test
    void dockerfileIsTheCompletePinnedNonRootRuntimeContract() throws IOException {
        List<String> directives = read(MODULE, "Dockerfile.jvm").lines()
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
        String readme = read(MODULE, "README.md");
        String health = read(MODULE, "docs/health-contract.md");

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
        String baseImage = section(readme, "Base-image review and refresh");
        assertTrue(baseImage.contains("sha256:f9e65324"));
        assertEquals(1, baseImage.split(Pattern.quote("all four production-runtime probes"), -1).length - 1);
    }

    @Test
    void documentedRuntimeSmokeIsExecutable() throws IOException {
        assertTrue(isExecutableContractInput(MODULE, "scripts/prod-runtime-smoke.sh"));
        assertEquals(1, section(read(MODULE, "README.md"), "Build and verification").lines()
            .filter(line -> line.equals("./scripts/prod-runtime-smoke.sh core-banking/funds-core:accounting-kernel"))
            .count());
    }

    private static Map<String, Integer> assignmentCounts(String source) throws IOException {
        var properties = new CountingProperties();
        properties.load(new StringReader(source));
        return properties.counts;
    }

    private static String directChildText(Element parent, String localName) {
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
                return element.getTextContent().trim();
            }
        }
        return null;
    }

    private static Element singleDirectChild(Element parent, String localName) {
        var children = directChildren(parent, localName);
        assertEquals(1, children.size(), localName + " direct-child count");
        return children.getFirst();
    }

    private static List<Element> directChildren(Element parent, String localName) {
        var result = new ArrayList<Element>();
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
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

    private static String read(Path module, String relativePath) throws IOException {
        return Files.readString(resolveContractInput(module, relativePath), StandardCharsets.UTF_8);
    }

    private static boolean isExecutableContractInput(Path module, String relativePath) {
        return Files.isExecutable(resolveContractInput(module, relativePath));
    }

    private static Path resolveContractInput(Path module, String relativePath) {
        if (!CONTRACT_INPUTS.contains(relativePath)) {
            throw new IllegalStateException("Contract input must use its exact repository-relative spelling");
        }

        Path relative = Path.of(relativePath);
        String normalizedRelative = relative.normalize().toString().replace('\\', '/');
        if (relative.isAbsolute() || !relativePath.equals(normalizedRelative)) {
            throw new IllegalStateException("Contract input must use its exact repository-relative spelling");
        }

        Path lexicalModule = module.toAbsolutePath().normalize();
        final Path canonicalModule;
        try {
            canonicalModule = lexicalModule.toRealPath();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Contract module must be a canonical filesystem directory");
        }
        if (!canonicalModule.equals(lexicalModule)
            || Files.isSymbolicLink(lexicalModule)
            || !Files.isDirectory(lexicalModule, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Contract module must be a symlink-free canonical filesystem directory");
        }

        Path candidate = lexicalModule.resolve(relative).normalize();
        if (!candidate.startsWith(lexicalModule)) {
            throw new IllegalStateException(relativePath + " must remain beneath the canonical module root");
        }

        Path current = lexicalModule;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                if (current.equals(candidate)) {
                    throw new IllegalStateException(relativePath
                        + " must be a non-symbolic-link regular file");
                }
                throw new IllegalStateException(relativePath
                    + " must have a symlink-free canonical path beneath the module root");
            }
        }
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(relativePath + " must be a non-symbolic-link regular file");
        }

        final Path canonicalInput;
        try {
            canonicalInput = candidate.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException(relativePath + " could not be canonicalized");
        }
        if (!canonicalInput.equals(candidate) || !canonicalInput.startsWith(canonicalModule)) {
            throw new IllegalStateException(relativePath
                + " must have a symlink-free canonical path beneath the module root");
        }

        requireExactTrackedContractPath(canonicalModule, canonicalInput, relativePath);
        return canonicalInput;
    }

    private static URI actualCodeSource() {
        try {
            return PackagingContractTest.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("Packaging-test code source is not a filesystem URI", e);
        }
    }

    private static Path independentExpectedModule() throws IOException {
        Path codeSource = Path.of(actualCodeSource()).toRealPath();
        String repositoryOutput = runBoundedGit(codeSource, "rev-parse", "--show-toplevel");
        if (repositoryOutput.lines().count() != 1) {
            throw new IllegalStateException("Git returned an ambiguous repository root");
        }
        Path repository = Path.of(repositoryOutput).toRealPath();
        String trackedPom = runBoundedGit(repository, "ls-files", "--error-unmatch", "--",
            "services/funds-core/pom.xml");
        String trackedProperties = runBoundedGit(repository, "ls-files", "--error-unmatch", "--",
            "services/funds-core/src/main/resources/application.properties");
        if (!"services/funds-core/pom.xml".equals(trackedPom)
            || !"services/funds-core/src/main/resources/application.properties".equals(trackedProperties)) {
            throw new IllegalStateException("Git did not independently identify the exact funds-core sentinels");
        }
        Path expected = repository.resolve("services/funds-core").toRealPath();
        if (!expected.startsWith(repository)) {
            throw new IllegalStateException("Tracked funds-core module escaped its canonical repository root");
        }
        return expected;
    }

    private static String runBoundedGit(Path context, String... arguments) throws IOException {
        var command = new ArrayList<String>();
        command.add("git");
        command.add("-C");
        command.add(context.toString());
        command.addAll(List.of(arguments));
        try (var outputFile = TemporaryOutput.create()) {
            Process process = null;
            try {
                var processBuilder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.path().toFile());
                processBuilder.environment().keySet().removeIf(name -> name.startsWith("GIT_"));
                process = processBuilder.start();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    terminateProcess(process);
                    throw new IllegalStateException("Git trust command exceeded five seconds");
                }
                long outputSize = Files.size(outputFile.path());
                if (outputSize > 16_384) {
                    throw new IllegalStateException("Git trust command output exceeded the 16384-byte read cap");
                }
                if (process.exitValue() != 0) {
                    throw new IllegalStateException("Git trust command failed with exit " + process.exitValue());
                }
                return readBoundedProcessOutput(outputFile.path());
            } catch (InterruptedException e) {
                if (process != null) {
                    try {
                        terminateProcess(process);
                    } catch (InterruptedException | RuntimeException cleanupFailure) {
                        e.addSuppressed(cleanupFailure);
                    }
                }
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Git trust validation", e);
            }
        }
    }

    private static String readBoundedProcessOutput(Path outputFile) throws IOException {
        return Files.size(outputFile) <= 16_384
            ? Files.readString(outputFile, StandardCharsets.UTF_8).strip()
            : "<output exceeded 16384 bytes>";
    }

    private static void terminateProcess(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed-out Git process did not terminate");
            }
        }
    }

    private static void writeDecoyModule(Path module, String groupId, String artifactId) throws IOException {
        Files.createDirectories(module.resolve("target/test-classes"));
        Files.createDirectories(module.resolve("src/main/resources"));
        Files.writeString(module.resolve("pom.xml"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>0.0.0-decoy</version>
            </project>
            """.formatted(groupId, artifactId));
        Files.writeString(module.resolve("src/main/resources/application.properties"), "decoy=true\n");
    }

    private static Path writeContractFixture(Path module, String relativePath, String content) throws IOException {
        Path fixture = module.resolve(relativePath);
        Files.createDirectories(fixture.getParent());
        Files.writeString(fixture, content);
        return fixture;
    }

    private static void initializeTrackedRepository(Path repository, String... trackedPaths) throws IOException {
        runBoundedGit(repository, "init", "--quiet");
        var arguments = new ArrayList<>(List.of("add", "--"));
        arguments.addAll(List.of(trackedPaths));
        runBoundedGit(repository, arguments.toArray(String[]::new));
    }

    private static Path resolveModuleRoot(URI codeSource) {
        Path testClasses;
        try {
            testClasses = Path.of(codeSource).toRealPath();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Packaging-test code source is not a canonical filesystem path");
        }
        Path target = testClasses.getParent();
        if (!"test-classes".equals(fileName(testClasses)) || target == null || !"target".equals(fileName(target))) {
            throw new IllegalStateException("Unexpected packaging-test code-source layout");
        }
        Path module;
        try {
            module = target.getParent() == null ? null : target.getParent().toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("Packaging-test module root is not canonical");
        }
        if (module == null) {
            throw new IllegalStateException("Packaging-test code source did not resolve the funds-core module");
        }

        Path pom = resolveContractInput(module, "pom.xml");
        resolveContractInput(module, "src/main/resources/application.properties");
        requireFundsCorePomIdentity(pom);
        return module;
    }

    private static void requireExactTrackedContractPath(
        Path canonicalModule,
        Path canonicalInput,
        String relativePath
    ) {
        try {
            String repositoryOutput = runBoundedGit(canonicalModule, "rev-parse", "--show-toplevel");
            if (repositoryOutput.lines().count() != 1) {
                throw new IllegalStateException("Git returned an ambiguous repository root");
            }
            Path repository = Path.of(repositoryOutput).toRealPath();
            Path expectedModule = repository.resolve(MODULE_REPOSITORY_PATH).toRealPath();
            if (!expectedModule.equals(canonicalModule) || !canonicalModule.startsWith(repository)) {
                throw new IllegalStateException("Module is not at the required repository path");
            }

            String expectedTrackedPath = MODULE_REPOSITORY_PATH + "/" + relativePath;
            String actualTrackedPath = repository.relativize(canonicalInput).toString().replace('\\', '/');
            if (!expectedTrackedPath.equals(actualTrackedPath)) {
                throw new IllegalStateException("Contract input has the wrong repository path");
            }
            String trackedPath = runBoundedGit(repository,
                "ls-files", "--error-unmatch", "--", expectedTrackedPath);
            if (!expectedTrackedPath.equals(trackedPath)) {
                throw new IllegalStateException("Git returned an unexpected tracked path");
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(relativePath
                + " must be the exact Git-tracked path in services/funds-core");
        }
    }

    private static void requireFundsCorePomIdentity(Path pom) {
        try (var reader = Files.newBufferedReader(pom, StandardCharsets.UTF_8)) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var project = factory.newDocumentBuilder().parse(new InputSource(reader)).getDocumentElement();
            var groupIds = directChildren(project, "groupId");
            var artifactIds = directChildren(project, "artifactId");
            if (!"project".equals(project.getLocalName())
                || groupIds.size() != 1
                || artifactIds.size() != 1
                || !"com.corebanking".equals(groupIds.getFirst().getTextContent().trim())
                || !"funds-core".equals(artifactIds.getFirst().getTextContent().trim())) {
                throw new IllegalStateException("pom.xml must identify com.corebanking:funds-core");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("pom.xml must be a parseable com.corebanking:funds-core project");
        }
    }

    private static String fileName(Path path) {
        return path.getFileName() == null ? "" : path.getFileName().toString();
    }

    private record TemporaryOutput(Path path) implements AutoCloseable {
        private static TemporaryOutput create() throws IOException {
            return new TemporaryOutput(Files.createTempFile("funds-core-git-", ".log"));
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
        }
    }

    private static final class CountingProperties extends Properties {
        private final Map<String, Integer> counts = new HashMap<>();

        @Override
        public synchronized Object put(Object key, Object value) {
            counts.merge(key.toString(), 1, Integer::sum);
            return super.put(key, value);
        }
    }
}
