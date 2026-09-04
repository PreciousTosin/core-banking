# funds-core Local Development and Service Documentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a human engineer run funds-core locally against a migrated, seeded PostgreSQL, drive postings, reversals and proofs over a dev-only HTTP surface, get fast test feedback, and find all of that in a service-local documentation set that links into the existing architecture governance.

**Architecture:** Four `%dev.` infrastructure properties turn on Dev Services migration plus a repeatable Flyway seed under `db/dev-seed`; an exact `@IfBuildProfile(anyOf = {"dev", "test"})` allowlist includes the `devtools` JAX-RS resource and reference resolver only in those two build profiles. The resource owns its error mapping so dev-only semantics cannot affect other REST resources. The smoke script proves the packaged image serves 404 on the dev path. `PackagingContractTest` pins the full profile-key set; four Markdown documents under `services/funds-core/docs/` describe the workflow and link outward to updated arc42 views, ADRs and conventions.

**Tech Stack:** Java 25 (mise-pinned), Maven 3.9.16 wrapper, Quarkus 3.33.3.1 (`quarkus-rest`, `quarkus-rest-jackson` added), Flyway, PostgreSQL 18.6 via Dev Services and Testcontainers, JUnit 5, RestAssured, Checkstyle 14.1.0, mise tasks.

**Spec:** [docs/superpowers/specs/2026-09-04-funds-core-local-development-and-docs-design.md](../specs/2026-09-04-funds-core-local-development-and-docs-design.md)

**Base commit:** `f4f5e91` on `master`.

**Model tiering for executors:** Task 1, Task 2 and Task 7 are ordinary implementation and suit an Opus-class executor. Task 3 and Task 4 touch the posting path and need the strongest available model. Task 5 and Task 6 are text and shell edits with exact content supplied below and suit a Sonnet-class executor. Reviews between tasks use the strongest model.

## Global Constraints

- Every command runs inside whichever checkout the executor was given. Each command block anchors itself with `cd "$(git rev-parse --show-toplevel)/services/funds-core"` because this repository executes implementation plans in worktrees (`superpowers:using-git-worktrees`). Never substitute a literal path.
- Java 25 only. **Prefix every Maven command with `mise exec java@25 --`.** A bare `./mvnw` in a non-interactive shell picks up the host JDK 27 and the enforcer rule `[25,26)` rejects the build. `mise run <task>` applies the toolchain itself and needs no prefix.
- `./mvnw clean verify` requires Docker for the PostgreSQL Testcontainers gate and must never use a host database (CLAUDE.md). If `id -nG` does not list `docker`, run the anchored gate command through `newgrp docker -c 'mise run verify'`. If Docker is genuinely unreachable, the gate is the human partner's step and is reported as **not run**, never as passed.
- Checkstyle runs in `validate` with `MissingJavadocType` at scope `package`, so **every new class, record, interface and test class needs a Javadoc purpose comment whose first sentence ends with a period**. `TODO`, `FIXME` and `XXX` fail the build in `.java` and `.sql`.
- The 19 production keys in `PackagingContractTest.CONTROLLED_PROPERTIES` keep their values and their single assignment. The only new profile-prefixed keys are the four `%dev.` infrastructure keys named in the spec; bean inclusion is enforced by the exact build-profile allowlist, not a configurable property.
- `README.md` must keep every heading in `assertUniqueHeadings` exactly once, the line `./scripts/prod-runtime-smoke.sh core-banking/funds-core:accounting-kernel` exactly once inside "Build and verification", the phrase `fail closed before readiness can be UP` inside "Database roles and startup", `sha256:f9e65324` and the phrase `all four production-runtime probes` exactly once inside "Base-image review and refresh", the 11 `| ACC-xx |` rows once each, and the 13 exclusion bullets. `docs/health-contract.md` must keep its three headings exactly once.
- No production behaviour changes: migrations `V001` to `V006`, `PostingService`, `ReversalService`, `JdbcLedgerRepository` and the proof classes are not edited. `git diff` on `src/main/java/com/corebanking/funds/{application,domain,infrastructure,runtime}` must be empty at the end.
- Comments follow `docs/conventions/code-comments.md`: the why, not the what.
- Every task ends with its own verification and a commit before the next task starts. Commit messages end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## File structure

| Path | Responsibility |
|---|---|
| `src/main/resources/application.properties` | four `%dev.` Dev Services/Flyway keys (Task 1) |
| `src/test/resources/application.properties` | distinct database name and reuse-off isolation for destructive tests (Task 1) |
| `mise.toml` | tasks `dev`, `test`, `verify`, `checkstyle` (Task 1) |
| `src/test/java/com/corebanking/funds/PackagingContractTest.java` | pins the complete dev/test profile-key set (Task 1) |
| `src/main/resources/db/dev-seed/R__dev_reference_ledger.sql` | repeatable, idempotent reference seed (Task 2) |
| `src/test/java/com/corebanking/funds/application/DevSeedIT.java` | seed applies twice and accepts a posting (Task 2) |
| `src/test/java/com/corebanking/funds/application/DevProfileBootstrapIT.java` | boots the actual dev config profile, verifies Flyway's repeatable seed, and drives it over HTTP (Task 4) |
| `src/test/java/com/corebanking/funds/infrastructure/postgres/MigrationIT.java` | test profile applies 8 versioned and 0 repeatable migrations (Task 2) |
| `pom.xml` | `quarkus-rest`, `quarkus-rest-jackson` (Task 3) |
| `src/main/java/com/corebanking/funds/devtools/DevLedgerReferences.java` | resolves book context, cutoff, default book, reference description (Task 3) |
| `src/main/java/com/corebanking/funds/devtools/DevPostingRequest.java`, `DevPostingLine.java`, `DevPostingResponse.java`, `DevReferenceResponse.java`, `DevErrorResponse.java`, `DevReversalRequest.java` | JSON records (Tasks 3 and 4) |
| `src/main/java/com/corebanking/funds/devtools/DevLedgerResource.java` | `/dev/ledger` endpoints (Tasks 3 and 4) |
| `src/main/java/com/corebanking/funds/devtools/DevLedgerResource.java` | resource-local domain exception to HTTP mapping (Task 4) |
| `src/test/java/com/corebanking/funds/application/DevLedgerResourceIT.java` | RestAssured coverage of the surface (Tasks 3 and 4) |
| `scripts/prod-runtime-smoke.sh` | 404 assertion inside the reachable-database probe (Task 5) |
| `README.md`, `docs/health-contract.md` | dev profile sentences and the index pointer (Task 5) |
| `docs/README.md`, `docs/developer-guide.md`, `docs/change-recipes.md` | service documentation set (Task 6) |
| `docs/test-catalogue.md` | refreshed after the new test classes and methods land (Task 6) |
| `AGENTS.md` (repository root) | pointer to the developer guide (Task 6) |
| `architecture/arc42/05-building-block-view.md`, `06-runtime-view.md`, `07-deployment-view.md` | current-state devtools boundary, runtime path and profile isolation (Task 6) |
| `architecture/diagrams/funds-core-components.mmd` | devtools HTTP adapter and read-only reference-query edge (Task 6) |

The two test classes for the dev tooling live in `com.corebanking.funds.application` because `TestPostingStack` is package-private there and is the only fixture that seeds the reference graph; moving or widening it is out of scope.

---

### Task 1: Dev profile, pinned by the packaging contract, plus mise tasks

**Files:**
- Modify: `src/main/resources/application.properties` (append after line 32)
- Modify: `src/test/resources/application.properties` (give destructive tests a distinct Dev Services database name)
- Modify: `src/test/java/com/corebanking/funds/PackagingContractTest.java` (add one constant near line 59 and one test after line 315)
- Modify: `mise.toml`

**Interfaces:**
- Produces: the property `%dev.quarkus.flyway.locations=db/migration,db/dev-seed` that Task 2's seed directory and Task 4's dev-profile bootstrap test rely on; mise task names `dev`, `test`, `verify`, `checkstyle` that Task 6's documents cite.

- [ ] **Step 1: Write the failing packaging test**

Add `"src/test/resources/application.properties"` to `CONTRACT_INPUTS` next
to the main properties path. Update the class purpose comment to say it reads
both main and test configuration. Also add the test-resource path to the
`nonSentinelContractInputSymlinksFailBeforeContentOrMetadataAccess` and
`nestedContractInputsRejectSymlinkedParentDirectoriesBeforeRepositoryValidation`
parameter lists. This gives the new input the same exact-Git-path and
symlink-free trust coverage as every existing contract input; without the
allowlist entry, `read(...)` rejects it before the property assertion runs.

Add the constant directly after `CONTROLLED_PROPERTIES` (which ends at line 79):

```java
    // Non-production configuration is restricted to the infrastructure needed by dev mode.
    // Bean inclusion uses an exact build-profile allowlist and is not configurable.
    private static final Map<String, String> NON_PRODUCTION_PROFILE_PROPERTIES = Map.of(
        "%dev.quarkus.datasource.devservices.image-name", "postgres:18.6-bookworm",
        "%dev.quarkus.datasource.devservices.reuse", "true",
        "%dev.quarkus.flyway.migrate-at-start", "true",
        "%dev.quarkus.flyway.locations", "db/migration,db/dev-seed");
```

Add the test directly after `productionConfigurationHasOneEffectiveAssignmentForEveryBound` (ends at line 315):

```java
    @Test
    void nonProductionProfileOverridesAreExactAndLeavePackagedDefaultsAlone() throws Exception {
        String source = read(MODULE, "src/main/resources/application.properties");
        var properties = new Properties();
        properties.load(new StringReader(source));

        var profileKeys = new HashSet<String>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("%") && !key.startsWith("%prod.")) {
                profileKeys.add(key);
            }
        }
        assertEquals(NON_PRODUCTION_PROFILE_PROPERTIES.keySet(), profileKeys,
            "only the pinned dev profile keys may exist");
        NON_PRODUCTION_PROFILE_PROPERTIES.forEach(
            (key, expected) -> assertEquals(expected, properties.getProperty(key), key));
        assertNull(properties.getProperty("quarkus.flyway.locations"),
            "packaged Flyway locations stay at the default so db/dev-seed can never run in the image");
        assertFalse(properties.getProperty("%dev.quarkus.flyway.locations").contains("jdbc:"));

        var testProperties = new Properties();
        testProperties.load(new StringReader(
            read(MODULE, "src/test/resources/application.properties")));
        assertEquals("funds_core_test",
            testProperties.getProperty("quarkus.datasource.devservices.db-name"),
            "destructive tests need a database identity distinct from the live dev service");
    }
```

`HashSet`, `Map`, `Properties` and `StringReader` are already imported (they are used by the surrounding tests). Add `assertNull` to the static `Assertions` imports if the file lists them individually:

```java
import static org.junit.jupiter.api.Assertions.assertNull;
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
mise exec java@25 -- ./mvnw -q test -Dtest=PackagingContractTest#nonProductionProfileOverridesAreExactAndLeavePackagedDefaultsAlone
```

Expected: `FAIL` because the four pinned dev keys and the isolated test database name are absent.

- [ ] **Step 3: Add the dev profile keys**

Append to `src/main/resources/application.properties` after the `%prod.` block:

```properties

# Dev profile only. quarkus:dev starts a PostgreSQL 18.6 Dev Services container, migrates it,
# applies the repeatable reference seed. Java annotations allow the unauthenticated driving
# surface only in exact dev and test build profiles; configuration cannot opt in another profile.
# PackagingContractTest pins this exact set and keeps packaged Flyway locations at the default.
%dev.quarkus.datasource.devservices.image-name=postgres:18.6-bookworm
%dev.quarkus.datasource.devservices.reuse=true
%dev.quarkus.flyway.migrate-at-start=true
%dev.quarkus.flyway.locations=db/migration,db/dev-seed
```

Append to `src/test/resources/application.properties`:

```properties
# Destructive integration fixtures must not share the live dev-mode database.
quarkus.datasource.devservices.db-name=funds_core_test
```

- [ ] **Step 4: Run the whole packaging contract to verify it passes**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
mise exec java@25 -- ./mvnw -q test -Dtest=PackagingContractTest
```

Expected: `Tests run: 21, Failures: 0` (20 existing plus the new one). The existing one-assignment test still passes because `%dev.quarkus.flyway.migrate-at-start` is a different literal key from `quarkus.flyway.migrate-at-start`.

- [ ] **Step 5: Add the mise tasks**

Replace `mise.toml` with:

```toml
[tools]
java = "25"

[tasks.dev]
description = "Quarkus dev mode: migrated and seeded Dev Services PostgreSQL, continuous testing on 'r'"
run = "./mvnw quarkus:dev"

[tasks.test]
description = "Checkstyle plus the full Surefire suite, integration tests included (needs Docker)"
run = "./mvnw test"

[tasks.verify]
description = "The pull-request gate from AGENTS.md (needs Docker)"
run = "./mvnw clean verify"

[tasks.checkstyle]
description = "Comment-convention rules only, no tests, no Docker"
run = "./mvnw checkstyle:check"
```

- [ ] **Step 6: Verify the tasks resolve the toolchain**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
mise tasks ls
mise run checkstyle
```

Expected: four tasks listed; `BUILD SUCCESS` with `You have 0 Checkstyle violations.` and the Maven banner reporting Java 25.

- [ ] **Step 7: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add services/funds-core/src/main/resources/application.properties \
        services/funds-core/src/test/resources/application.properties \
        services/funds-core/src/test/java/com/corebanking/funds/PackagingContractTest.java \
        services/funds-core/mise.toml
git commit -m "Add a pinned dev profile and mise tasks to funds-core

quarkus:dev now migrates its Dev Services PostgreSQL and reads the
db/dev-seed Flyway location. The dev and test profiles explicitly opt
into the driving surface, and PackagingContractTest pins every key.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Repeatable dev seed with its own proof

**Files:**
- Create: `src/main/resources/db/dev-seed/R__dev_reference_ledger.sql`
- Create: `src/test/java/com/corebanking/funds/application/DevSeedIT.java`
- Modify: `src/test/java/com/corebanking/funds/infrastructure/postgres/MigrationIT.java` (add one test after `createsEveryAccountingReferenceTable`, line 77)

**Interfaces:**
- Consumes: `TestPostingStack.reset`, `TestPostingStack.create`, `TestPostingStack.uuid` and the fixture constants `BOOK_ID`, `LEGAL_ENTITY_ID`, `CHART_VERSION_ID`, `PERIOD_ID`, `PROVIDER_ASSET`, `CUSTOMER_LIABILITY` (same identities the seed installs).
- Produces: the seeded identities `…0001` to `…0008` that Task 3's resource and Task 6's guide describe.

- [ ] **Step 1: Write the failing seed test**

`src/test/java/com/corebanking/funds/application/DevSeedIT.java`:

```java
package com.corebanking.funds.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

/**
 * Proves the dev-profile seed under db/dev-seed installs its fixed reference graph, survives an
 * unchanged second application, rejects stale reused state, and yields a graph the real posting
 * stack accepts. The test profile never adds db/dev-seed to its Flyway locations, so the file is
 * executed here by hand against the migrated test database.
 */
@QuarkusTest
class DevSeedIT {
    private static final String SEED = "/db/dev-seed/R__dev_reference_ledger.sql";

    @Inject
    DataSource dataSource;

    @BeforeEach
    void emptyLedger() throws SQLException {
        TestPostingStack.reset(dataSource);
    }

    @AfterEach
    void reset() throws SQLException {
        TestPostingStack.reset(dataSource);
    }

    @Test
    void seedInstallsTheReferenceGraphTwiceWithoutErrorAndAcceptsAPosting() throws Exception {
        String seed = readSeed();
        applySeed(seed);
        long governanceRevision = governanceRevision();
        applySeed(seed);

        assertEquals(1L, count("funds.book"));
        assertEquals(1L, count("funds.chart_version"));
        assertEquals("ACTIVE", chartStatus());
        assertEquals(1L, count("funds.accounting_period"));
        assertEquals(2L, count("funds.ledger_account"));
        assertEquals(2L, count("funds.ledger_account_chart_mapping"));
        assertEquals(0L, count("funds.journal"));
        assertEquals(0L, count("funds.materialised_balance"));
        assertEquals(governanceRevision, governanceRevision(),
            "an unchanged repeatable seed must not advance chart governance");

        var stack = TestPostingStack.create(dataSource, PostingTransactionObserver.noop());
        var result = stack.postingService().post(command());
        assertEquals(TestPostingStack.uuid(9_001), result.journalId());
        assertEquals(1L, count("funds.journal"));
    }

    @Test
    void changedSeedRejectsStaleImmutableStateInAReusedDatabase() throws Exception {
        String seed = readSeed();
        applySeed(seed);
        String changedSeed = seed.replace(
            "TIMESTAMPTZ '2026-01-01 00:00:00+00'",
            "TIMESTAMPTZ '2026-01-02 00:00:00+00'");

        SQLException failure = assertThrows(SQLException.class, () -> applySeed(changedSeed));
        assertTrue(failure.getMessage().contains("discard the reused Dev Services database"));
        assertTrue(failure instanceof PSQLException);
        assertEquals("dev_reference_seed_drift",
            ((PSQLException) failure).getServerErrorMessage().getConstraint());
    }

    private String readSeed() throws Exception {
        try (var stream = getClass().getResourceAsStream(SEED)) {
            assertNotNull(stream, "seed resource must be on the classpath: " + SEED);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void applySeed(String seed) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(seed);
        }
    }

    private long count(String table) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private String chartStatus() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(
                 "SELECT status FROM funds.chart_version WHERE chart_version_id = '00000000-0000-0000-0000-000000000002'")) {
            rows.next();
            return rows.getString(1);
        }
    }

    private long governanceRevision() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                 SELECT chart_governance_revision
                 FROM funds.book
                 WHERE book_id = '00000000-0000-0000-0000-000000000001'
                 """)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    // A balanced NGN 10,000.00 provider inflow booked inside the seeded calendar-2026 period.
    private static PostingCommand command() {
        var draft = new JournalDraft(
            TestPostingStack.uuid(9_001),
            TestPostingStack.uuid(9_000),
            TestPostingStack.uuid(9_002),
            TestPostingStack.uuid(9_003),
            TestPostingStack.LEGAL_ENTITY_ID,
            TestPostingStack.BOOK_ID,
            TestPostingStack.CHART_VERSION_ID,
            TestPostingStack.PERIOD_ID,
            "DEV_SEED_PROBE",
            "Dev seed probe",
            Instant.parse("2026-06-15T10:00:00Z"),
            LocalDate.of(2026, 6, 15),
            null,
            1,
            List.of(
                new PostingLine(TestPostingStack.uuid(9_004), TestPostingStack.PROVIDER_ASSET,
                    CurrencyCode.of("NGN"), 1_000_000L, 0L, Map.of("rail", "dev")),
                new PostingLine(TestPostingStack.uuid(9_005), TestPostingStack.CUSTOMER_LIABILITY,
                    CurrencyCode.of("NGN"), -1_000_000L, 0L, Map.of("customer", "dev"))));
        return new PostingCommand(draft.commandId(), new CanonicalCommandHasher().postingV2(draft), draft);
    }
}
```

`PostingTransactionObserver.noop()` exists (used by `AccountingStateMachineIT`). `UUID` is imported for readability of the fixture even though only `uuid(...)` is called; remove the import if the compiler flags it unused under checkstyle (it does not: no unused-import rule is configured).

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
mise exec java@25 -- ./mvnw -q test -Dtest=DevSeedIT
```

Expected: `FAIL` with `seed resource must be on the classpath: /db/dev-seed/R__dev_reference_ledger.sql`.

- [ ] **Step 3: Write the seed**

`src/main/resources/db/dev-seed/R__dev_reference_ledger.sql`:

```sql
-- R__dev_reference_ledger: repeatable development seed, applied only when the dev profile adds
-- db/dev-seed to quarkus.flyway.locations (application.properties). Installs the reference
-- fixed identities and core reference shape derived from TestPostingStack, with dev-specific
-- attributes, so every engineer's
-- /dev/ledger session speaks about the same book, chart, period and accounts. Seeds no
-- balances, journals or projections: those must come from real postings so proofs stay
-- meaningful. An unchanged script is idempotent. When the checksum changes, the assertion at
-- the end rejects stale immutable rows in a reused Dev Services database rather than silently
-- retaining a graph that no longer matches this file. Rows whose V005 triggers reject a repeated
-- insert are guarded with NOT EXISTS; activation is guarded by status = 'DRAFT'.
INSERT INTO funds.book
    (book_id, legal_entity_id, functional_currency, timezone, calendar_code, accounting_policy_version)
VALUES ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000008',
        'NGN', 'Africa/Lagos', 'NG', 1)
ON CONFLICT (book_id) DO NOTHING;

INSERT INTO funds.chart_version
    (chart_version_id, book_id, version, status, approval_reference)
SELECT '00000000-0000-0000-0000-000000000002',
       '00000000-0000-0000-0000-000000000001', 1, 'DRAFT', 'APP-DEV-CHART-001'
WHERE NOT EXISTS (SELECT 1 FROM funds.chart_version
                  WHERE chart_version_id = '00000000-0000-0000-0000-000000000002');

-- A deliberately long-lived dev-only period keeps current-time exploration usable without
-- pretending this fixture is a production accounting calendar.
INSERT INTO funds.accounting_period
    (period_id, book_id, business_date_from, business_date_to, status)
VALUES ('00000000-0000-0000-0000-000000000007', '00000000-0000-0000-0000-000000000001',
        DATE '2020-01-01', DATE '2099-12-31', 'OPEN')
ON CONFLICT (period_id) DO NOTHING;

INSERT INTO funds.product_definition (product_id, product_code)
VALUES ('00000000-0000-0000-0000-000000000003', 'DEV-SAVINGS')
ON CONFLICT (product_id) DO NOTHING;

INSERT INTO funds.product_version
    (product_version_id, product_id, version, effective_from, approval_reference,
     policy_hash, policy_json, product_kind, finance_principle)
VALUES ('00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000003',
        1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 'APP-DEV-SAVINGS-001',
        repeat('a', 64), '{}'::jsonb, 'SAVINGS', 'CONVENTIONAL')
ON CONFLICT (product_version_id) DO NOTHING;

INSERT INTO funds.ledger_account
    (account_id, book_id, account_scope, product_version_id, currency, status, created_at)
SELECT '00000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000001',
       'INTERNAL', NULL, 'NGN', 'OPEN', TIMESTAMPTZ '2026-01-01 00:00:00+00'
WHERE NOT EXISTS (SELECT 1 FROM funds.ledger_account
                  WHERE account_id = '00000000-0000-0000-0000-000000000005');

INSERT INTO funds.ledger_account
    (account_id, book_id, account_scope, product_version_id, currency, status, created_at)
SELECT '00000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000001',
       'CUSTOMER', '00000000-0000-0000-0000-000000000004', 'NGN', 'OPEN',
       TIMESTAMPTZ '2026-01-01 00:00:00+00'
WHERE NOT EXISTS (SELECT 1 FROM funds.ledger_account
                  WHERE account_id = '00000000-0000-0000-0000-000000000006');

INSERT INTO funds.ledger_account_chart_mapping
    (account_id, book_id, chart_version_id, account_code, account_currency, account_class,
     normal_balance, control_account_code, account_role)
SELECT '00000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000001',
       '00000000-0000-0000-0000-000000000002', 'PROVIDER-ASSET', 'NGN', 'ASSET',
       'DEBIT', 'PROVIDER-CASH', 'INTERNAL'
WHERE NOT EXISTS (SELECT 1 FROM funds.ledger_account_chart_mapping
                  WHERE account_id = '00000000-0000-0000-0000-000000000005'
                    AND chart_version_id = '00000000-0000-0000-0000-000000000002');

INSERT INTO funds.ledger_account_chart_mapping
    (account_id, book_id, chart_version_id, account_code, account_currency, account_class,
     normal_balance, control_account_code, account_role)
SELECT '00000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000001',
       '00000000-0000-0000-0000-000000000002', 'CUSTOMER-LIABILITY', 'NGN', 'LIABILITY',
       'CREDIT', 'CUSTOMER-DEPOSITS', 'CUSTOMER'
WHERE NOT EXISTS (SELECT 1 FROM funds.ledger_account_chart_mapping
                  WHERE account_id = '00000000-0000-0000-0000-000000000006'
                    AND chart_version_id = '00000000-0000-0000-0000-000000000002');

-- Activation last, once every open account is mapped (V005 chart_mapping_incomplete).
UPDATE funds.chart_version
SET status = 'ACTIVE', activated_at = TIMESTAMPTZ '2026-01-01 00:00:00+00'
WHERE chart_version_id = '00000000-0000-0000-0000-000000000002'
  AND status = 'DRAFT';

DO $seed_assertion$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM funds.book b
        JOIN funds.chart_version c ON c.book_id = b.book_id
        JOIN funds.accounting_period p ON p.book_id = b.book_id
        JOIN funds.product_definition d ON d.product_id =
            '00000000-0000-0000-0000-000000000003'
        JOIN funds.product_version v ON v.product_id = d.product_id
        WHERE b.book_id = '00000000-0000-0000-0000-000000000001'
          AND b.legal_entity_id = '00000000-0000-0000-0000-000000000008'
          AND b.functional_currency = 'NGN' AND b.timezone = 'Africa/Lagos'
          AND b.calendar_code = 'NG' AND b.accounting_policy_version = 1
          AND c.chart_version_id = '00000000-0000-0000-0000-000000000002'
          AND c.version = 1 AND c.status = 'ACTIVE'
          AND c.approval_reference = 'APP-DEV-CHART-001'
          AND c.activated_at = TIMESTAMPTZ '2026-01-01 00:00:00+00'
          AND c.retired_at IS NULL
          AND p.period_id = '00000000-0000-0000-0000-000000000007'
          AND p.business_date_from = DATE '2020-01-01'
          AND p.business_date_to = DATE '2099-12-31' AND p.status = 'OPEN'
          AND d.product_code = 'DEV-SAVINGS'
          AND v.product_version_id = '00000000-0000-0000-0000-000000000004'
          AND v.version = 1
          AND v.effective_from = TIMESTAMPTZ '2026-01-01 00:00:00+00'
          AND v.approval_reference = 'APP-DEV-SAVINGS-001'
          AND v.effective_to IS NULL AND v.policy_hash = repeat('a', 64)
          AND v.policy_json = '{}'::jsonb AND v.product_kind = 'SAVINGS'
          AND v.finance_principle = 'CONVENTIONAL'
          AND EXISTS (SELECT 1 FROM funds.ledger_account a
                      WHERE a.account_id = '00000000-0000-0000-0000-000000000005'
                        AND a.book_id = b.book_id AND a.account_scope = 'INTERNAL'
                        AND a.product_version_id IS NULL AND a.currency = 'NGN'
                        AND a.status = 'OPEN' AND a.authorised_floor_minor = 0
                        AND a.created_at = TIMESTAMPTZ '2026-01-01 00:00:00+00'
                        AND a.closed_at IS NULL)
          AND EXISTS (SELECT 1 FROM funds.ledger_account a
                      WHERE a.account_id = '00000000-0000-0000-0000-000000000006'
                        AND a.book_id = b.book_id AND a.account_scope = 'CUSTOMER'
                        AND a.product_version_id = v.product_version_id
                        AND a.currency = 'NGN' AND a.status = 'OPEN'
                        AND a.authorised_floor_minor = 0
                        AND a.created_at = TIMESTAMPTZ '2026-01-01 00:00:00+00'
                        AND a.closed_at IS NULL)
          AND EXISTS (SELECT 1 FROM funds.ledger_account_chart_mapping m
                      WHERE m.account_id = '00000000-0000-0000-0000-000000000005'
                        AND m.book_id = b.book_id AND m.chart_version_id = c.chart_version_id
                        AND m.account_code = 'PROVIDER-ASSET' AND m.account_currency = 'NGN'
                        AND m.account_class = 'ASSET' AND m.normal_balance = 'DEBIT'
                        AND m.control_account_code = 'PROVIDER-CASH'
                        AND m.account_role = 'INTERNAL' AND m.currency_policy = 'ACCOUNT_CURRENCY'
                        AND m.permitted_direction = 'BOTH')
          AND EXISTS (SELECT 1 FROM funds.ledger_account_chart_mapping m
                      WHERE m.account_id = '00000000-0000-0000-0000-000000000006'
                        AND m.book_id = b.book_id AND m.chart_version_id = c.chart_version_id
                        AND m.account_code = 'CUSTOMER-LIABILITY' AND m.account_currency = 'NGN'
                        AND m.account_class = 'LIABILITY' AND m.normal_balance = 'CREDIT'
                        AND m.control_account_code = 'CUSTOMER-DEPOSITS'
                        AND m.account_role = 'CUSTOMER' AND m.currency_policy = 'ACCOUNT_CURRENCY'
                        AND m.permitted_direction = 'BOTH')
    ) THEN
        RAISE EXCEPTION 'dev reference seed differs from immutable reused state; discard the reused Dev Services database and restart dev mode'
            USING ERRCODE = '55000', CONSTRAINT = 'dev_reference_seed_drift';
    END IF;
END
$seed_assertion$;
```

Checkstyle's `RegexpHeader` applies only to `db/migration/*.sql` (POM `resourceIncludes`), so the `R__` first line is not checked, but the header block is kept to the convention anyway.

- [ ] **Step 4: Run the seed test to verify it passes**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
mise exec java@25 -- ./mvnw -q test -Dtest=DevSeedIT
```

Expected: both cases pass. If the second unchanged `applySeed` fails with `ledger_account_chart_mapping_frozen` or `active_chart_account_onboarding_deferred`, a `NOT EXISTS` guard is missing on the failing statement; the `ON CONFLICT` form is not enough for those two tables because their BEFORE triggers fire ahead of conflict resolution. If the drift case does not fail with `dev_reference_seed_drift`, the seed can silently diverge in a reused database.

- [ ] **Step 5: Write the failing migration-history assertion**

Add to `MigrationIT` after `createsEveryAccountingReferenceTable` (line 77):

```java
    // The dev seed is a repeatable migration under db/dev-seed. The test profile must never
    // pick it up, so the history holds only the eight versioned files and nothing repeatable.
    @Test
    void testProfileAppliesExactlyTheEightVersionedMigrationsAndNoRepeatableSeed() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                 SELECT count(*) FILTER (WHERE type = 'SQL' AND version IS NOT NULL),
                        count(*) FILTER (WHERE type = 'SQL' AND version IS NULL)
                 FROM flyway_schema_history
                 WHERE success
                 """)) {
            assertTrue(rows.next());
            assertEquals(8, rows.getInt(1), "versioned migrations V001..V006 including V003.1 and V003.2");
            assertEquals(0, rows.getInt(2), "no repeatable migration may run under the test profile");
        }
    }
```

`assertTrue` and `assertEquals` are already statically imported in `MigrationIT`.

- [ ] **Step 6: Run it**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
mise exec java@25 -- ./mvnw -q test -Dtest=MigrationIT#testProfileAppliesExactlyTheEightVersionedMigrationsAndNoRepeatableSeed
```

Expected: `Tests run: 1, Failures: 0`. This test passes immediately: it documents the boundary the seed must respect and would fail if someone added `db/dev-seed` to the base `quarkus.flyway.locations`. If it fails with `relation "flyway_schema_history" does not exist`, Quarkus placed the history in the `funds` schema; change the query to `funds.flyway_schema_history` and note it in the commit message.

- [ ] **Step 7: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add services/funds-core/src/main/resources/db/dev-seed/R__dev_reference_ledger.sql \
        services/funds-core/src/test/java/com/corebanking/funds/application/DevSeedIT.java \
        services/funds-core/src/test/java/com/corebanking/funds/infrastructure/postgres/MigrationIT.java
git commit -m "Seed a reference ledger for funds-core dev mode

A repeatable Flyway migration under db/dev-seed installs the same book,
chart, period, product and two accounts the integration tests use. It
is idempotent, seeds no balances, and MigrationIT proves the test
profile never applies it.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Dev-only HTTP surface, reference and posting endpoints

**Files:**
- Modify: `pom.xml` (dependencies block, after line 33)
- Create: `src/main/java/com/corebanking/funds/devtools/DevLedgerReferences.java`
- Create: `src/main/java/com/corebanking/funds/devtools/DevReferenceResponse.java`
- Create: `src/main/java/com/corebanking/funds/devtools/DevPostingRequest.java`
- Create: `src/main/java/com/corebanking/funds/devtools/DevPostingLine.java`
- Create: `src/main/java/com/corebanking/funds/devtools/DevPostingResponse.java`
- Create: `src/main/java/com/corebanking/funds/devtools/DevLedgerResource.java`
- Create: `src/test/java/com/corebanking/funds/application/DevLedgerResourceIT.java`

**Interfaces:**
- Consumes: `PostingService.post(PostingCommand)`, `PostingCommand(UUID commandId, String requestHash, JournalDraft journal)`, `JournalDraft` (15 components, see `domain/JournalDraft.java:25`), `PostingLine(UUID postingId, UUID accountId, CurrencyCode currency, long signedMinorUnits, long accountSequence, Map<String,String> dimensions)`, `CanonicalCommandHasher.postingV2(JournalDraft)`, `PostingResult(UUID journalId, long journalSequence, String canonicalHash)`.
- Produces: `DevLedgerReferences.BookContext resolve(UUID bookId, Instant bookingTime)`, `long currentCutoff(UUID bookId)`, `UUID defaultBook()`, `DevReferenceResponse describe(UUID bookId)`; the deterministic id helper `DevLedgerResource.derived(UUID commandId, String label)`; both reused by Task 4.

- [ ] **Step 1: Write the failing resource test (reference and posting cases)**

`src/test/java/com/corebanking/funds/application/DevLedgerResourceIT.java`:

```java
package com.corebanking.funds.application;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the development-only /dev/ledger surface end to end over HTTP against the
 * TestPostingStack reference graph. The surface is present under the test profile and absent
 * from the prod build profile; scripts/prod-runtime-smoke.sh proves the latter with a 404.
 */
@QuarkusTest
class DevLedgerResourceIT {
    private static final String BOOK = "00000000-0000-0000-0000-000000000001";
    private static final String PROVIDER = "00000000-0000-0000-0000-000000000005";
    private static final String CUSTOMER = "00000000-0000-0000-0000-000000000006";
    private static final String COMMAND = "00000000-0000-0000-0000-000000000500";

    @Inject
    DataSource dataSource;

    @BeforeEach
    void seed() throws SQLException {
        TestPostingStack.resetAndSeed(dataSource);
    }

    @AfterEach
    void reset() throws SQLException {
        TestPostingStack.reset(dataSource);
    }

    @Test
    void referenceDescribesTheSeededBook() {
        given()
            .when().get("/dev/ledger/reference")
            .then().statusCode(200)
            .body("bookId", equalTo(BOOK))
            .body("chartVersionId", equalTo("00000000-0000-0000-0000-000000000002"))
            .body("policyVersion", equalTo(1))
            .body("timezone", equalTo("Africa/Lagos"))
            .body("openPeriods", hasSize(1))
            .body("accounts", hasSize(2))
            .body("accounts.accountCode", equalTo(java.util.List.of("CUSTOMER-LIABILITY", "PROVIDER-ASSET")));
    }

    @Test
    void postingReturnsTheJournalAndAnIdenticalReplayReturnsTheStoredResult() throws SQLException {
        var first = given().contentType(ContentType.JSON).body(inflow(COMMAND, 1_000_000))
            .when().post("/dev/ledger/postings")
            .then().statusCode(200)
            .body("commandId", equalTo(COMMAND))
            .body("journalId", notNullValue())
            .body("requestHash", notNullValue())
            .extract().jsonPath();

        var replay = given().contentType(ContentType.JSON).body(inflow(COMMAND, 1_000_000))
            .when().post("/dev/ledger/postings")
            .then().statusCode(200)
            .extract().jsonPath();

        assertEquals(first.getString("journalId"), replay.getString("journalId"));
        assertEquals(first.getLong("journalSequence"), replay.getLong("journalSequence"));
        assertEquals(first.getString("canonicalHash"), replay.getString("canonicalHash"));
        assertEquals(1L, count("funds.journal"));
        assertEquals(2L, count("funds.posting"));
    }

    // NGN 10,000.00 provider inflow: +amount on the debit-normal asset, -amount on the
    // credit-normal customer liability, booked inside the seeded January 2026 period.
    static String inflow(String commandId, long amount) {
        return """
            {"commandId":"%s","transactionType":"PROVIDER_INFLOW","narration":"dev inflow",
             "bookingTime":"2026-01-15T10:00:00Z","valueDate":"2026-01-15",
             "lines":[{"accountId":"%s","currency":"NGN","signedMinorUnits":%d,"dimensions":{"rail":"dev"}},
                      {"accountId":"%s","currency":"NGN","signedMinorUnits":%d,"dimensions":{"customer":"dev"}}]}
            """.formatted(commandId, PROVIDER, amount, CUSTOMER, -amount);
    }

    long count(String table) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
mise exec java@25 -- ./mvnw -q test -Dtest=DevLedgerResourceIT
```

Expected: every method fails with `Expected status code <200> but was <404>` (no resource exists yet). If the build fails earlier because `io.restassured.http.ContentType` is unresolved, RestAssured is on the test classpath (POM line 35) and the failure is elsewhere; read the message.

- [ ] **Step 3: Add the REST dependencies**

In `pom.xml`, after the `quarkus-micrometer-registry-prometheus` line (33):

```xml
        <!-- The dev-only /dev/ledger surface (devtools package). Its beans use an exact dev/test
             build-profile allowlist, so configuration cannot include them in another profile;
             the smoke script asserts the production 404. -->
        <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-rest</artifactId></dependency>
        <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-rest-jackson</artifactId></dependency>
```

- [ ] **Step 4: Write the response and request records**

`src/main/java/com/corebanking/funds/devtools/DevReferenceResponse.java`:

```java
package com.corebanking.funds.devtools;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What an engineer needs to know before posting in dev mode: the book, its active chart and
 * policy version, the open periods and every mapped account with its classification. Read from
 * the database so it reflects the dev seed or whatever the engineer has since changed.
 */
public record DevReferenceResponse(
    UUID bookId,
    UUID legalEntityId,
    UUID chartVersionId,
    int policyVersion,
    String timezone,
    List<Period> openPeriods,
    List<Account> accounts) {

    /** One OPEN accounting period of the book. */
    public record Period(UUID periodId, LocalDate from, LocalDate to) {}

    /** One account as classified by the active chart. */
    public record Account(
        UUID accountId,
        String accountCode,
        String currency,
        String accountClass,
        String normalBalance,
        String controlAccountCode) {}
}
```

`src/main/java/com/corebanking/funds/devtools/DevPostingLine.java`:

```java
package com.corebanking.funds.devtools;

import java.util.Map;
import java.util.UUID;

/**
 * One posting line as submitted over the dev surface. Sign convention is the ledger's: positive
 * minor units debit, negative credit (README "Reading the accounting model").
 */
public record DevPostingLine(UUID accountId, String currency, long signedMinorUnits, Map<String, String> dimensions) {}
```

`src/main/java/com/corebanking/funds/devtools/DevPostingRequest.java`:

```java
package com.corebanking.funds.devtools;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A posting as an engineer types it. Everything a real caller must supply and hash itself
 * (chart, period, legal entity, policy version, journal and posting identities, request hash) is
 * resolved or derived by the resource, which is why this surface is development-only.
 */
public record DevPostingRequest(
    UUID commandId,
    UUID bookId,
    String transactionType,
    String narration,
    Instant bookingTime,
    LocalDate valueDate,
    List<DevPostingLine> lines) {}
```

`src/main/java/com/corebanking/funds/devtools/DevPostingResponse.java`:

```java
package com.corebanking.funds.devtools;

import java.util.UUID;

/**
 * The stored result of a posting or reversal command plus the identity and typed request hash
 * the resource used, so an engineer can replay the exact command from a real client.
 */
public record DevPostingResponse(UUID commandId, String requestHash, UUID journalId, long journalSequence, String canonicalHash) {}
```

- [ ] **Step 5: Write the reference resolver**

`src/main/java/com/corebanking/funds/devtools/DevLedgerReferences.java`:

```java
package com.corebanking.funds.devtools;

import com.corebanking.funds.domain.exception.LedgerPersistenceException;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Read-only lookups that turn a book id and a booking time into the governance coordinates a
 * JournalDraft needs. A real caller carries these itself; the dev surface resolves them so an
 * engineer can post with a handful of fields. Missing governance coordinates are bad requests;
 * database failures retain the kernel's persistence-failure vocabulary.
 */
@Singleton
@IfBuildProfile(anyOf = {"dev", "test"})
public class DevLedgerReferences {

    /** Governance coordinates of one book for one booking time. */
    public record BookContext(
        UUID bookId,
        UUID legalEntityId,
        UUID chartVersionId,
        UUID periodId,
        int policyVersion,
        ZoneId timezone) {}

    private final DataSource dataSource;

    @Inject
    public DevLedgerReferences(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** The book with the lowest id, so a request may omit bookId against the dev seed. */
    public UUID defaultBook() {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT book_id FROM funds.book ORDER BY book_id LIMIT 1")) {
            if (!rows.next()) {
                throw new IllegalArgumentException("no book exists; run the dev seed or create one");
            }
            return rows.getObject(1, UUID.class);
        } catch (SQLException failure) {
            throw new LedgerPersistenceException(failure);
        }
    }

    /**
     * The active chart and the OPEN period whose business-date range contains the booking time
     * in the book's timezone: the same rule the journal governance trigger applies at commit.
     */
    public BookContext resolve(UUID bookId, Instant bookingTime) {
        Objects.requireNonNull(bookId, "bookId");
        Objects.requireNonNull(bookingTime, "bookingTime");
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                 SELECT b.legal_entity_id, b.accounting_policy_version, b.timezone,
                        c.chart_version_id, p.period_id
                 FROM funds.book b
                 JOIN funds.chart_version c ON c.book_id = b.book_id AND c.status = 'ACTIVE'
                 JOIN funds.accounting_period p ON p.book_id = b.book_id AND p.status = 'OPEN'
                   AND (? AT TIME ZONE b.timezone)::date
                       BETWEEN p.business_date_from AND p.business_date_to
                 WHERE b.book_id = ?
                 """)) {
            statement.setObject(1, bookingTime.atOffset(ZoneOffset.UTC));
            statement.setObject(2, bookId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalArgumentException(
                        "book " + bookId + " has no active chart or no OPEN period containing " + bookingTime);
                }
                return new BookContext(
                    bookId,
                    rows.getObject(1, UUID.class),
                    rows.getObject(4, UUID.class),
                    rows.getObject(5, UUID.class),
                    rows.getInt(2),
                    ZoneId.of(rows.getString(3)));
            }
        } catch (SQLException failure) {
            throw new LedgerPersistenceException(failure);
        }
    }

    /** Highest committed journal sequence of the book, the natural proof cutoff; 0 when empty. */
    public long currentCutoff(UUID bookId) {
        Objects.requireNonNull(bookId, "bookId");
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                 "SELECT COALESCE(MAX(journal_sequence), 0) FROM funds.journal WHERE book_id = ?")) {
            statement.setObject(1, bookId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } catch (SQLException failure) {
            throw new LedgerPersistenceException(failure);
        }
    }

    /** Book header, open periods and every account mapped under the active chart. */
    public DevReferenceResponse describe(UUID bookId) {
        Objects.requireNonNull(bookId, "bookId");
        try (var connection = dataSource.getConnection()) {
            UUID legalEntityId;
            int policyVersion;
            String timezone;
            UUID chartVersionId;
            try (var statement = connection.prepareStatement("""
                     SELECT b.legal_entity_id, b.accounting_policy_version, b.timezone, c.chart_version_id
                     FROM funds.book b
                     JOIN funds.chart_version c ON c.book_id = b.book_id AND c.status = 'ACTIVE'
                     WHERE b.book_id = ?
                     """)) {
                statement.setObject(1, bookId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new IllegalArgumentException("book " + bookId + " does not exist or has no active chart");
                    }
                    legalEntityId = rows.getObject(1, UUID.class);
                    policyVersion = rows.getInt(2);
                    timezone = rows.getString(3);
                    chartVersionId = rows.getObject(4, UUID.class);
                }
            }
            var periods = new ArrayList<DevReferenceResponse.Period>();
            try (var statement = connection.prepareStatement("""
                     SELECT period_id, business_date_from, business_date_to
                     FROM funds.accounting_period
                     WHERE book_id = ? AND status = 'OPEN'
                     ORDER BY business_date_from
                     """)) {
                statement.setObject(1, bookId);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        periods.add(new DevReferenceResponse.Period(
                            rows.getObject(1, UUID.class),
                            rows.getObject(2, java.time.LocalDate.class),
                            rows.getObject(3, java.time.LocalDate.class)));
                    }
                }
            }
            var accounts = new ArrayList<DevReferenceResponse.Account>();
            try (var statement = connection.prepareStatement("""
                     SELECT account_id, account_code, account_currency, account_class,
                            normal_balance, control_account_code
                     FROM funds.ledger_account_chart_mapping
                     WHERE book_id = ? AND chart_version_id = ?
                     ORDER BY account_code
                     """)) {
                statement.setObject(1, bookId);
                statement.setObject(2, chartVersionId);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        accounts.add(new DevReferenceResponse.Account(
                            rows.getObject(1, UUID.class),
                            rows.getString(2),
                            rows.getString(3),
                            rows.getString(4),
                            rows.getString(5),
                            rows.getString(6)));
                    }
                }
            }
            return new DevReferenceResponse(bookId, legalEntityId, chartVersionId, policyVersion, timezone, periods, accounts);
        } catch (SQLException failure) {
            throw new LedgerPersistenceException(failure);
        }
    }
}
```

- [ ] **Step 6: Write the resource with the reference and posting endpoints**

`src/main/java/com/corebanking/funds/devtools/DevLedgerResource.java`:

```java
package com.corebanking.funds.devtools;

import com.corebanking.funds.application.CanonicalCommandHasher;
import com.corebanking.funds.application.PostingCommand;
import com.corebanking.funds.application.PostingResult;
import com.corebanking.funds.application.PostingService;
import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Development-only HTTP surface over the kernel's typed commands. It exists so a human can
 * post, reverse and prove without writing a client; it is not a contract. Real callers mint
 * their own identities and compute the typed request hash (ADR-0002), which is exactly what
 * this class does on their behalf. Absent from the prod build profile; the packaged image
 * answers 404 here and scripts/prod-runtime-smoke.sh checks that.
 */
@Path("/dev/ledger")
@Singleton
@IfBuildProfile(anyOf = {"dev", "test"})
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DevLedgerResource {
    private final PostingService postingService;
    private final DevLedgerReferences references;
    private final CanonicalCommandHasher hasher = new CanonicalCommandHasher();

    @Inject
    public DevLedgerResource(PostingService postingService, DevLedgerReferences references) {
        this.postingService = Objects.requireNonNull(postingService, "postingService");
        this.references = Objects.requireNonNull(references, "references");
    }

    @GET
    @Path("/reference")
    public DevReferenceResponse reference(@QueryParam("bookId") UUID bookId) {
        return references.describe(bookId != null ? bookId : references.defaultBook());
    }

    @POST
    @Path("/postings")
    public DevPostingResponse post(DevPostingRequest request) {
        request = required(request, "request body");
        var commandId = request.commandId() != null ? request.commandId() : UUID.randomUUID();
        var bookingTime = required(request.bookingTime(), "bookingTime");
        var bookId = request.bookId() != null ? request.bookId() : references.defaultBook();
        var context = references.resolve(bookId, bookingTime);
        var valueDate = request.valueDate() != null
            ? request.valueDate()
            : LocalDate.ofInstant(bookingTime, context.timezone());
        if (request.lines() == null) {
            throw new IllegalArgumentException("lines are required");
        }
        var lines = new ArrayList<PostingLine>(request.lines().size());
        for (int index = 0; index < request.lines().size(); index++) {
            var line = required(request.lines().get(index), "lines[" + index + "]");
            lines.add(new PostingLine(
                derived(commandId, "posting:" + index),
                required(line.accountId(), "lines[" + index + "].accountId"),
                CurrencyCode.of(required(line.currency(), "lines[" + index + "].currency")),
                line.signedMinorUnits(),
                0L,
                line.dimensions() == null ? Map.of() : line.dimensions()));
        }
        var draft = new JournalDraft(
            derived(commandId, "journal"),
            commandId,
            derived(commandId, "correlation"),
            derived(commandId, "business-transaction"),
            context.legalEntityId(),
            context.bookId(),
            context.chartVersionId(),
            context.periodId(),
            required(request.transactionType(), "transactionType"),
            request.narration() == null ? "" : request.narration(),
            bookingTime,
            valueDate,
            null,
            context.policyVersion(),
            List.copyOf(lines));
        var requestHash = hasher.postingV2(draft);
        PostingResult result = postingService.post(new PostingCommand(commandId, requestHash, draft));
        return new DevPostingResponse(commandId, requestHash, result.journalId(), result.journalSequence(), result.canonicalHash());
    }

    /** Identities derived from the command id so immediate retries rebuild the same draft. */
    static UUID derived(UUID commandId, String label) {
        return UUID.nameUUIDFromBytes(("funds-core/dev/" + commandId + "/" + label).getBytes(StandardCharsets.UTF_8));
    }

    private static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
```

Booking time is deliberately required: it is part of the typed hash, so defaulting it from the wall clock would turn an identical retry into a different command. Value date remains a deterministic default derived from that required instant. If `JournalDraft` rejects a blank narration (check `domain/JournalDraft.java` lines 60 to 75 for a `narration.isBlank()` guard), keep the `""` default: the rejection surfaces as an `IllegalArgumentException` and a 400, which is the intended behaviour for a missing field.

- [ ] **Step 7: Run the completed Task 3 resource test**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
mise exec java@25 -- ./mvnw -q test -Dtest=DevLedgerResourceIT
```

Expected: both tests pass. Error-status cases belong to Task 4, which adds the resource-local mapper before committing. If the reference test fails on `accounts.accountCode`, the ordering is alphabetical by `account_code`, which puts `CUSTOMER-LIABILITY` before `PROVIDER-ASSET`; the expectation already reflects that.

- [ ] **Step 8: Run checkstyle on the new package**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
mise run checkstyle
```

Expected: `You have 0 Checkstyle violations.` Every new type above carries a purpose comment whose first sentence ends with a period.

- [ ] **Step 9: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add services/funds-core/pom.xml services/funds-core/src/main/java/com/corebanking/funds/devtools \
        services/funds-core/src/test/java/com/corebanking/funds/application/DevLedgerResourceIT.java
git commit -m "Add the dev-only /dev/ledger reference and posting endpoints

A JAX-RS resource excluded from the prod build profile resolves book,
chart, period and policy for a booking time, derives identities from
the command id and computes the typed request hash, so an engineer can
post from curl in dev mode. Rejections are not yet mapped (next commit).

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Reversal and proof endpoints, exception mapping

**Files:**
- Create: `src/main/java/com/corebanking/funds/devtools/DevReversalRequest.java`
- Create: `src/main/java/com/corebanking/funds/devtools/DevErrorResponse.java`
- Modify: `src/main/java/com/corebanking/funds/devtools/DevLedgerResource.java`
- Modify: `src/test/java/com/corebanking/funds/application/DevLedgerResourceIT.java`
- Create: `src/test/java/com/corebanking/funds/application/DevProfileBootstrapIT.java`

**Interfaces:**
- Consumes: `ReversalService.reverse(ReversalRequest)`, `ReversalRequest(UUID commandId, String requestHash, UUID originalJournalId, UUID correlationId, UUID businessTransactionId, UUID currentPeriodId, Instant bookingTime, LocalDate valueDate, String reason)`, `CanonicalCommandHasher.reversalV2(ReversalRequest)`, `AccountingProofService.trialBalance(UUID, CurrencyCode, long)` and `controlAccount(UUID, String, CurrencyCode, long)`, the domain exceptions under `com.corebanking.funds.domain.exception`.

- [ ] **Step 1: Confirm the domain exceptions are unchecked**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
grep -n "extends" src/main/java/com/corebanking/funds/domain/exception/*.java
```

Expected: every class extends `RuntimeException` directly or through `LedgerPersistenceException`. If any extends a checked exception, add a resource-local `@ServerExceptionMapper` overload for that checked type; do not change the exception.

- [ ] **Step 2: Add the failing reversal and proof tests**

Append to `DevLedgerResourceIT`:

```java
    @Test
    void unbalancedJournalIsRejectedWith422AndTheDomainExceptionName() throws SQLException {
        given().contentType(ContentType.JSON).body("""
            {"commandId":"%s","transactionType":"DEV_TEST","narration":"unbalanced",
             "bookingTime":"2026-01-15T10:00:00Z",
             "lines":[{"accountId":"%s","currency":"NGN","signedMinorUnits":100},
                      {"accountId":"%s","currency":"NGN","signedMinorUnits":-99}]}
            """.formatted(COMMAND, PROVIDER, CUSTOMER))
            .when().post("/dev/ledger/postings")
            .then().statusCode(422)
            .body("error", equalTo("InvalidJournalException"));
        assertEquals(0L, count("funds.journal"));
    }

    @Test
    void changedBodyUnderTheSameCommandIdIsAnIdempotencyConflict() {
        given().contentType(ContentType.JSON).body(inflow(COMMAND, 1_000_000))
            .when().post("/dev/ledger/postings").then().statusCode(200);
        given().contentType(ContentType.JSON).body(inflow(COMMAND, 2_000_000))
            .when().post("/dev/ledger/postings")
            .then().statusCode(409)
            .body("error", equalTo("IdempotencyConflictException"));
    }

    @Test
    void bookingTimeOutsideEveryOpenPeriodIsABadRequest() {
        given().contentType(ContentType.JSON).body("""
            {"commandId":"%s","transactionType":"DEV_TEST","narration":"wrong period",
             "bookingTime":"2027-03-01T10:00:00Z",
             "lines":[{"accountId":"%s","currency":"NGN","signedMinorUnits":100},
                      {"accountId":"%s","currency":"NGN","signedMinorUnits":-100}]}
            """.formatted(COMMAND, PROVIDER, CUSTOMER))
            .when().post("/dev/ledger/postings")
            .then().statusCode(400)
            .body("error", equalTo("IllegalArgumentException"));
    }

    @Test
    void bookingTimeIsRequiredBecauseItIsPartOfTheReplayHash() {
        given().contentType(ContentType.JSON).body("""
            {"commandId":"%s","transactionType":"DEV_TEST","narration":"missing time",
             "lines":[{"accountId":"%s","currency":"NGN","signedMinorUnits":100},
                      {"accountId":"%s","currency":"NGN","signedMinorUnits":-100}]}
            """.formatted(COMMAND, PROVIDER, CUSTOMER))
            .when().post("/dev/ledger/postings")
            .then().statusCode(400)
            .body("error", equalTo("IllegalArgumentException"));
    }

    @Test
    void reversalNegatesTheOriginalAndASecondReversalIsRejected() throws SQLException {
        var original = given().contentType(ContentType.JSON).body(inflow(COMMAND, 1_000_000))
            .when().post("/dev/ledger/postings").then().statusCode(200).extract().jsonPath();

        var reversal = given().contentType(ContentType.JSON).body("""
            {"commandId":"00000000-0000-0000-0000-000000000501","originalJournalId":"%s",
             "reason":"dev reversal","bookingTime":"2026-01-16T10:00:00Z"}
            """.formatted(original.getString("journalId")))
            .when().post("/dev/ledger/reversals")
            .then().statusCode(200)
            .body("journalId", notNullValue())
            .extract().jsonPath();

        assertEquals(2L, count("funds.journal"));
        assertEquals(4L, count("funds.posting"));
        assertEquals(original.getLong("journalSequence") + 1, reversal.getLong("journalSequence"));

        given().contentType(ContentType.JSON).body("""
            {"commandId":"00000000-0000-0000-0000-000000000502","originalJournalId":"%s",
             "reason":"second reversal","bookingTime":"2026-01-16T11:00:00Z"}
            """.formatted(original.getString("journalId")))
            .when().post("/dev/ledger/reversals")
            .then().statusCode(422)
            .body("error", equalTo("InvalidJournalException"));
    }

    @Test
    void proofsBalanceAfterAnInflowAndReconcileTheCustomerControl() {
        given().contentType(ContentType.JSON).body(inflow(COMMAND, 1_000_000))
            .when().post("/dev/ledger/postings").then().statusCode(200);

        given().queryParam("currency", "NGN")
            .when().get("/dev/ledger/proofs/trial-balance")
            .then().statusCode(200)
            .body("balanced", equalTo(true))
            .body("totalDebits", equalTo(1_000_000))
            .body("totalCredits", equalTo(1_000_000))
            .body("cutoff", equalTo(1));

        given().queryParam("currency", "NGN").queryParam("controlCode", "CUSTOMER-DEPOSITS")
            .when().get("/dev/ledger/proofs/control-account")
            .then().statusCode(200)
            .body("sourceTotal", equalTo(-1_000_000))
            .body("projectionTotal", equalTo(-1_000_000))
            .body("difference", equalTo(0));
    }
```

`cutoff` equals 1 because `TestPostingStack.reset` restarts the journal sequence (`RESTART IDENTITY`). `CUSTOMER-DEPOSITS` has no seeded projection row, so the posting inserts one at exactly the posted amount, which is why the control proof reconciles to 0.

- [ ] **Step 3: Run to verify the new tests fail**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
mise exec java@25 -- ./mvnw -q test -Dtest=DevLedgerResourceIT
```

Expected: the reversal and proof tests fail with `Expected status code <200> but was <404>`; the four newly added status-mapping tests fail with 500. Task 4 now supplies both the endpoints and their resource-local mapper before its commit.

- [ ] **Step 4: Write the request and error types, then add resource-local mapping**

`src/main/java/com/corebanking/funds/devtools/DevReversalRequest.java`:

```java
package com.corebanking.funds.devtools;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A reversal as an engineer types it: which journal, why, the required hash-bearing booking
 * time, and optionally which command id. Period and correlation identities are resolved or derived.
 */
public record DevReversalRequest(
    UUID commandId,
    UUID bookId,
    UUID originalJournalId,
    String reason,
    Instant bookingTime,
    LocalDate valueDate) {}
```

`src/main/java/com/corebanking/funds/devtools/DevErrorResponse.java`:

```java
package com.corebanking.funds.devtools;

/**
 * Error body of the dev surface: the domain exception's simple name, which is the vocabulary
 * the README acceptance table and the test catalogue use, plus its message.
 */
public record DevErrorResponse(String error, String message) {}
```

Add these imports to `DevLedgerResource`:

```java
import com.corebanking.funds.domain.exception.AccountingPeriodClosedException;
import com.corebanking.funds.domain.exception.IdempotencyConflictException;
import com.corebanking.funds.domain.exception.InvalidJournalException;
import com.corebanking.funds.domain.exception.LedgerCapacityException;
import com.corebanking.funds.domain.exception.LedgerPersistenceException;
import com.corebanking.funds.domain.exception.MonetaryOverflowException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
```

Add this method to `DevLedgerResource`. A mapper declared in a REST endpoint
class is invoked only for exceptions thrown from that class, so these dev-only
semantics cannot alter a future provider/public resource during `%test` runs:

```java
    @ServerExceptionMapper
    Response mapFailure(RuntimeException failure) {
        int status = switch (failure) {
            case IdempotencyConflictException _ -> 409;
            case InvalidJournalException _ -> 422;
            case AccountingPeriodClosedException _ -> 422;
            case MonetaryOverflowException _ -> 422;
            case LedgerCapacityException _ -> 422;
            case IllegalArgumentException _ -> 400;
            case LedgerPersistenceException _ -> 503;
            default -> 500;
        };
        return Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(new DevErrorResponse(failure.getClass().getSimpleName(), failure.getMessage()))
            .build();
    }
```

If `LedgerPersistenceException` is a superclass of one of the 422 types, order the `case` arms so the subclass comes first; the compiler reports dominance errors otherwise.

- [ ] **Step 5: Add the reversal and proof endpoints to the resource**

Add these imports to `DevLedgerResource`:

```java
import com.corebanking.funds.application.ReversalService;
import com.corebanking.funds.application.proof.AccountingProofService;
import com.corebanking.funds.application.proof.ControlAccountProof;
import com.corebanking.funds.application.proof.TrialBalanceProof;
import com.corebanking.funds.domain.ReversalRequest;
```

Replace the fields and constructor with:

```java
    private final PostingService postingService;
    private final ReversalService reversalService;
    private final AccountingProofService proofService;
    private final DevLedgerReferences references;
    private final CanonicalCommandHasher hasher = new CanonicalCommandHasher();

    @Inject
    public DevLedgerResource(
        PostingService postingService,
        ReversalService reversalService,
        AccountingProofService proofService,
        DevLedgerReferences references
    ) {
        this.postingService = Objects.requireNonNull(postingService, "postingService");
        this.reversalService = Objects.requireNonNull(reversalService, "reversalService");
        this.proofService = Objects.requireNonNull(proofService, "proofService");
        this.references = Objects.requireNonNull(references, "references");
    }
```

Add the endpoints after `post`:

```java
    @POST
    @Path("/reversals")
    public DevPostingResponse reverse(DevReversalRequest request) {
        request = required(request, "request body");
        var commandId = request.commandId() != null ? request.commandId() : UUID.randomUUID();
        var bookingTime = required(request.bookingTime(), "bookingTime");
        var bookId = request.bookId() != null ? request.bookId() : references.defaultBook();
        var context = references.resolve(bookId, bookingTime);
        var valueDate = request.valueDate() != null
            ? request.valueDate()
            : LocalDate.ofInstant(bookingTime, context.timezone());
        // ReversalRequest validates the hash shape at construction, so build once with a
        // well-formed placeholder, hash that, then rebuild with the typed hash (the same
        // two-step the reversal integration tests use).
        var unsigned = new ReversalRequest(
            commandId,
            "0".repeat(64),
            required(request.originalJournalId(), "originalJournalId"),
            derived(commandId, "correlation"),
            derived(commandId, "business-transaction"),
            context.periodId(),
            bookingTime,
            valueDate,
            required(request.reason(), "reason"));
        var requestHash = hasher.reversalV2(unsigned);
        var signed = new ReversalRequest(
            unsigned.commandId(),
            requestHash,
            unsigned.originalJournalId(),
            unsigned.correlationId(),
            unsigned.businessTransactionId(),
            unsigned.currentPeriodId(),
            unsigned.bookingTime(),
            unsigned.valueDate(),
            unsigned.reason());
        PostingResult result = reversalService.reverse(signed);
        return new DevPostingResponse(commandId, requestHash, result.journalId(), result.journalSequence(), result.canonicalHash());
    }

    @GET
    @Path("/proofs/trial-balance")
    public TrialBalanceProof trialBalance(
        @QueryParam("bookId") UUID bookId,
        @QueryParam("currency") String currency,
        @QueryParam("cutoff") Long cutoff
    ) {
        var book = bookId != null ? bookId : references.defaultBook();
        return proofService.trialBalance(
            book,
            CurrencyCode.of(required(currency, "currency")),
            cutoff != null ? cutoff : references.currentCutoff(book));
    }

    @GET
    @Path("/proofs/control-account")
    public ControlAccountProof controlAccount(
        @QueryParam("bookId") UUID bookId,
        @QueryParam("controlCode") String controlCode,
        @QueryParam("currency") String currency,
        @QueryParam("cutoff") Long cutoff
    ) {
        var book = bookId != null ? bookId : references.defaultBook();
        return proofService.controlAccount(
            book,
            required(controlCode, "controlCode"),
            CurrencyCode.of(required(currency, "currency")),
            cutoff != null ? cutoff : references.currentCutoff(book));
    }
```

`TrialBalanceProof` and `ControlAccountProof` are records, so Jackson serialises them as-is; `CurrencyCode` appears as `{"value":"NGN"}` and `BigInteger` totals as JSON numbers, which is what the test's `equalTo(1_000_000)` matchers expect.

- [ ] **Step 6: Run the whole resource test**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
mise exec java@25 -- ./mvnw -q test -Dtest=DevLedgerResourceIT
```

Expected: `Tests run: 8, Failures: 0`. If `totalDebits` compares as a `Long` versus `Integer`, replace `equalTo(1_000_000)` with `equalTo(1000000)`; RestAssured's JSON path yields `Integer` for values that fit.

- [ ] **Step 7: Add a real dev-profile Flyway bootstrap test**

Create `src/test/java/com/corebanking/funds/application/DevProfileBootstrapIT.java`:

```java
package com.corebanking.funds.application;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Proves the actual dev configuration lets Flyway discover the seed and exposes its graph. */
@QuarkusTest
@TestProfile(DevProfileBootstrapIT.DevConfigProfile.class)
class DevProfileBootstrapIT {

    @Inject
    DataSource dataSource;

    @Test
    void devProfileAppliesTheRepeatableSeedAndServesItOverHttp() throws SQLException {
        assertEquals(1L, scalar("""
            SELECT count(*) FROM flyway_schema_history
            WHERE version IS NULL AND description = 'dev reference ledger' AND success
            """));

        given().when().get("/dev/ledger/reference")
            .then().statusCode(200)
            .body("bookId", equalTo("00000000-0000-0000-0000-000000000001"));

        given().contentType("application/json").body("""
            {"commandId":"22222222-2222-2222-2222-222222222222",
             "transactionType":"DEPOSIT","narration":"dev bootstrap proof",
             "bookingTime":"2026-06-15T10:00:00Z","lines":[
               {"accountId":"00000000-0000-0000-0000-000000000005",
                "currency":"NGN","signedMinorUnits":100,"dimensions":{}},
               {"accountId":"00000000-0000-0000-0000-000000000006",
                "currency":"NGN","signedMinorUnits":-100,"dimensions":{}}]}
            """)
            .when().post("/dev/ledger/postings")
            .then().statusCode(200);
    }

    private long scalar(String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /** Selects the real dev configuration with a distinct, non-reused test database. */
    public static final class DevConfigProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "dev";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quarkus.datasource.devservices.reuse", "false",
                "quarkus.datasource.devservices.db-name", "funds_core_dev_profile_test");
        }
    }
}
```

This test must use `getConfigProfile()` rather than copying the Flyway
locations into `getConfigOverrides()`: the oracle is that the real `%dev`
configuration works. Its explicit reuse override takes precedence over
`%dev.quarkus.datasource.devservices.reuse=true`; its distinct database name
also forces a separate Dev Service from ordinary test-profile runs. Together
they isolate the test from both the live developer database and destructive
ordinary test fixtures. Quarkus restarts the application for the distinct
profile.

- [ ] **Step 8: Run checkstyle and the production-contract tests together**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
mise run checkstyle
mise exec java@25 -- ./mvnw -q test -Dtest='PackagingContractTest,DevSeedIT,DevProfileBootstrapIT,DevLedgerResourceIT,MigrationIT'
```

Expected: `0 Checkstyle violations`; all five classes pass. The packaging contract must still pass with the two new POM dependencies because it pins plugin executions, not dependencies.

- [ ] **Step 9: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add services/funds-core/src/main/java/com/corebanking/funds/devtools \
        services/funds-core/src/test/java/com/corebanking/funds/application/DevLedgerResourceIT.java \
        services/funds-core/src/test/java/com/corebanking/funds/application/DevProfileBootstrapIT.java
git commit -m "Add dev-only reversal and proof endpoints with status mapping

/dev/ledger/reversals derives its identities and typed hash the same
way postings do; /dev/ledger/proofs/* default the cutoff to the book's
highest journal sequence. Kernel rejections map to 409, 422, 400 and
503 with the exception name in the body.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Prove the packaged image serves no dev surface; align README and health contract

**Files:**
- Modify: `scripts/prod-runtime-smoke.sh` (after the `echo "reachable-database probe: ..."` line, around line 197)
- Modify: `README.md` ("Database roles and startup" paragraph, "Build and verification" section)
- Modify: `docs/health-contract.md` ("Endpoints" and "Database and migration prerequisite" sections)

**Interfaces:**
- Consumes: the `ready_port` variable and the `docker_api`/`curl` conventions already in the script.

- [ ] **Step 1: Add the 404 assertion to the reachable-database probe**

Insert after the line `echo "reachable-database probe: HTTP 200, aggregate UP, datasource UP (migration validation remains separate)"`:

```bash
dev_status="$(curl --silent --max-time 2 -o "$evidence_dir/dev.json" -w '%{http_code}' \
  "http://127.0.0.1:${ready_port}/dev/ledger/reference" 2>/dev/null || true)"
if [[ "$dev_status" != "404" ]]; then
  echo "packaged image served the development ledger surface (HTTP ${dev_status}, expected 404)" >&2
  exit 1
fi
echo "reachable-database probe: /dev/ledger/reference returned HTTP 404 (development surface absent from the packaged image)"
```

This extends the fourth probe rather than adding a fifth, so the README phrase `all four production-runtime probes` remains true and the packaging test that counts it is unaffected.

- [ ] **Step 2: Syntax-check the script**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
bash -n scripts/prod-runtime-smoke.sh && echo SYNTAX_OK
test -x scripts/prod-runtime-smoke.sh && echo EXECUTABLE
```

Expected: `SYNTAX_OK` and `EXECUTABLE`.

- [ ] **Step 3: Update the README**

In "Database roles and startup", replace the sentence `Flyway is disabled at runtime and enabled only by the test profile; the migration job must finish before service admission.` with:

```markdown
Flyway is disabled at runtime and enabled only by the test and dev profiles; the migration job must finish before service admission. The dev profile additionally applies a repeatable reference seed from `db/dev-seed`, a location the packaged configuration never lists.
```

Replace the whole "Build and verification" section body (keep the heading) with:

````markdown
Use Docker access for the PostgreSQL 18.6 Testcontainers gate and Java 25:

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
mise run verify
mise exec java@25 -- ./mvnw -DskipTests package
docker build -f Dockerfile.jvm -t core-banking/funds-core:accounting-kernel .
docker run --rm --entrypoint java --memory=640m --cpus=0.60 --pids-limit=256 \
  core-banking/funds-core:accounting-kernel -version
./scripts/prod-runtime-smoke.sh core-banking/funds-core:accounting-kernel
```

The test gate contains unit, deterministic generated-property, PostgreSQL integration, failure-injection and real child-process crash tests; no accounting test is intentionally skipped. Full service startup additionally requires the separately migrated profile database and is deferred to the deployment-profile plan.

Day-to-day development (dev mode with a migrated and seeded database, the development-only `/dev/ledger` surface, continuous testing, single-class runs, database inspection and troubleshooting) is described in the [developer guide](docs/developer-guide.md); the [documentation index](docs/README.md) lists every service-local document and the architecture documents that govern this module. The smoke script's reachable-database probe also asserts that the packaged image answers HTTP 404 on `/dev/ledger/reference`.
````

This deliberately replaces the README's bare Maven commands with the Java-25-aware mise forms and anchors the directory from the active worktree; it is not a paragraph-only change. The smoke command line stays exactly once.

- [ ] **Step 4: Update the health contract**

In "Endpoints", add a bullet after the `/q/metrics` bullet:

```markdown
- `/dev/ledger/*` uses an exact Java build-profile allowlist for dev and test. No property can enable it in prod or an arbitrary custom profile. `scripts/prod-runtime-smoke.sh` asserts the packaged image answers HTTP 404 inside its reachable-database probe.
```

In "Database and migration prerequisite", replace `Tests instead use PostgreSQL 18.6 Dev Services and enable Flyway in the test profile.` with:

```markdown
Tests and dev mode instead use PostgreSQL 18.6 Dev Services and enable Flyway in the test and dev profiles; dev mode also applies the repeatable reference seed under `db/dev-seed`. Only those two profiles opt into the development ledger surface.
```

- [ ] **Step 5: Run the packaging contract**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
mise exec java@25 -- ./mvnw -q test -Dtest=PackagingContractTest
```

Expected: `Tests run: 21, Failures: 0`. The documentation test checks headings, the ACC rows, the exclusions, the two phrases and the smoke command line; none of those changed.

- [ ] **Step 6: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add services/funds-core/scripts/prod-runtime-smoke.sh services/funds-core/README.md services/funds-core/docs/health-contract.md
git commit -m "Assert the packaged funds-core image serves no dev surface

The reachable-database smoke probe now also requires HTTP 404 on
/dev/ledger/reference. README and health contract describe the dev
profile and point at the developer guide.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Service documentation set and pointers

**Files:**
- Create: `docs/README.md`
- Create: `docs/developer-guide.md` (content in Appendix A)
- Create: `docs/change-recipes.md` (content in Appendix B)
- Modify: `docs/test-catalogue.md` (refresh its revision, inventory and method entries after Tasks 1 to 4)
- Modify: `AGENTS.md` at the repository root (Layout section)
- Modify: `architecture/arc42/05-building-block-view.md`
- Modify: `architecture/arc42/06-runtime-view.md`
- Modify: `architecture/arc42/07-deployment-view.md`
- Modify: `architecture/diagrams/funds-core-components.mmd`

**Interfaces:**
- Consumes: the mise task names from Task 1, the seeded identities from Task 2, the endpoint paths and status codes from Tasks 3 and 4, the smoke behaviour from Task 5.

- [ ] **Step 1: Write the index**

`services/funds-core/docs/README.md`:

```markdown
# funds-core documentation

Start with the [developer guide](developer-guide.md). Everything else on this page is either a
contract you will need when you change something, or the architecture document that governs the
change. When two sources disagree, the executable implementation and its tests win, then the
arc42 current-state views, then accepted ADRs, then service-local documents like these
([authority order](../../../architecture/README.md#authority-and-ownership)).

## Service-local documents

| Document | Read it when |
|---|---|
| [Developer guide](developer-guide.md) | You are setting up, running dev mode, or a test will not run. |
| [Change recipes](change-recipes.md) | You are about to change a migration, a pinned property, a limit, the Dockerfile, the README, the dev seed, or an error mapping. |
| [Test catalogue](test-catalogue.md) | You are reviewing test effectiveness or deciding where a new test belongs. |
| [Health and metrics contract](health-contract.md) | You touch readiness, liveness, metrics, pool or worker bounds. |
| [Migration roles](../src/main/resources/db/MIGRATION-ROLES.md) | You write a migration or provision a database login. |
| [Module README](../README.md) | You need the accounting model, identity rules, acceptance table, exclusions or the base-image procedure. |

## Governing architecture documents

| Document | Why it matters here |
|---|---|
| [Architecture entry point](../../../ARCHITECTURE.md) | Current versus proposed state, and the documentation map. |
| [Building-block view](../../../architecture/arc42/05-building-block-view.md) | Which package owns what, and the permitted dependency direction. |
| [Runtime view](../../../architecture/arc42/06-runtime-view.md) | How a posting flows through validation, locks, commit, idempotency and outbox. |
| [Deployment view](../../../architecture/arc42/07-deployment-view.md) | The image, the smoke script and the resource envelope. |
| [Constraints](../../../architecture/arc42/02-constraints.md) | Java 25, Quarkus, PostgreSQL 18.6, the 8 GiB budget, PoC exclusions. |
| [Crosscutting concepts](../../../architecture/arc42/08-crosscutting-concepts.md) | Money, hashing, idempotency and role concepts shared across the module. |
| [Decisions index](../../../architecture/arc42/09-decisions.md) | Every accepted ADR in one list. |

ADRs you will meet in this module:

- [ADR-0002](../../../architecture/adr/0002-centralize-financial-invariants-in-funds-core.md): funds-core is the sole owner of posting, reversal and proofs; callers submit typed commands. The dev surface is a convenience over those commands, not a second owner.
- [ADR-0003](../../../architecture/adr/0003-use-signed-integer-minor-units.md): money is a signed long of minor units with checked arithmetic.
- [ADR-0004](../../../architecture/adr/0004-use-postgresql-as-the-authoritative-ledger.md): PostgreSQL constraints and triggers enforce invariants independently of Java.
- [ADR-0005](../../../architecture/adr/0005-use-immutable-journals-and-additive-corrections.md): journals never change; corrections are reversals.
- [ADR-0006](../../../architecture/adr/0006-couple-idempotency-and-outbox-to-ledger-commit.md): the idempotency row and the outbox event commit with the journal.
- [ADR-0007](../../../architecture/adr/0007-separate-ledger-identity-from-account-addresses.md): the ledger-account UUID holds the balance; a NUBAN is only an address.
- [ADR-0008](../../../architecture/adr/0008-target-an-eight-gib-single-vm-evidence-suite.md): why the JVM, pool and worker bounds are what they are.
- [ADR-0009](../../../architecture/adr/0009-adopt-an-enforced-code-comment-convention.md): the comment convention checkstyle enforces.

Conventions and process:

- [Code comments](../../../docs/conventions/code-comments.md) and the [pull-request checklist](../../../AGENTS.md#before-opening-a-pull-request).
- [Proposals](../../../architecture/proposals/README.md) that would extend this module, and the [implementation plans](../../../docs/superpowers/plans/) that deliver them. Plans describe delivery, not current truth.
```

- [ ] **Step 2: Write the developer guide and the change recipes**

Create `docs/developer-guide.md` with the content of Appendix A and `docs/change-recipes.md` with the content of Appendix B, verbatim. Then re-audit `src/test/java`: update `docs/test-catalogue.md`'s reviewed commit, inventory totals and line counts; add complete entries for `DevSeedIT`, `DevProfileBootstrapIT` and `DevLedgerResourceIT`; and add the new methods to the `PackagingContractTest` and `MigrationIT` sections. Do not leave the catalogue claiming the pre-change count of 20 test classes or the old per-class method totals.

- [ ] **Step 3: Check every relative link resolves**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core/docs"
for f in README.md developer-guide.md change-recipes.md test-catalogue.md; do
  grep -oE '\]\(([^)#]+)' "$f" | sed 's/](//' | grep -vE '^https?://' | while read -r p; do
    [ -e "$p" ] || echo "BROKEN in $f: $p"
  done
done; echo LINKCHECK_DONE
```

Expected: only `LINKCHECK_DONE`. Fix any `BROKEN` line before continuing.

- [ ] **Step 4: Update the authoritative current-state architecture**

Update `05-building-block-view.md` and `funds-core-components.mmd` to show `devtools` as a build-time-gated inbound HTTP adapter: it calls the application services and performs read-only reference lookups through the datasource, while the domain remains independent of infrastructure. Update `06-runtime-view.md` with the dev request-normalisation flow (required booking time, deterministic identities, current governance resolution, typed hash, then the existing posting/reversal/proof services). Update `07-deployment-view.md` with the dev/test-only opt-in and the production 404 smoke assertion. Add `devtools` paths to the relevant `code_refs` and advance each edited document or diagram's `last_verified` date. These are current-state updates, not a new ADR.

- [ ] **Step 5: Point AGENTS.md at the guide**

In `AGENTS.md`, replace the first Layout bullet:

```markdown
- `services/funds-core/` — the implemented accounting kernel (Java 25,
  Quarkus, PostgreSQL). Its [README](services/funds-core/README.md) is the
  source of truth for the accounting model, identity rules, roles, and limits.
```

with:

```markdown
- `services/funds-core/` — the implemented accounting kernel (Java 25,
  Quarkus, PostgreSQL). Its [README](services/funds-core/README.md) is the
  source of truth for the accounting model, identity rules, roles, and limits;
  its [documentation index](services/funds-core/docs/README.md) leads to the
  developer guide, change recipes and test catalogue.
```

- [ ] **Step 6: Run the architecture validator (it reads AGENTS.md links) and checkstyle**

```bash
cd "$(git rev-parse --show-toplevel)"
python3 architecture/scripts/validate_architecture.py --root .
architecture/scripts/render-diagrams.sh
cd services/funds-core && mise run checkstyle
```

Expected: the validator exits 0, the locked renderer renders every governed Mermaid source in its invocation-owned temporary directory without error, and checkstyle reports 0 violations (Markdown is not scanned, this is a regression check). Generated SVGs remain ignored and untracked by repository policy.

- [ ] **Step 7: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add services/funds-core/docs/README.md services/funds-core/docs/developer-guide.md \
        services/funds-core/docs/change-recipes.md services/funds-core/docs/test-catalogue.md \
        architecture/arc42/05-building-block-view.md architecture/arc42/06-runtime-view.md \
        architecture/arc42/07-deployment-view.md architecture/diagrams/funds-core-components.mmd \
        AGENTS.md
git commit -m "Document funds-core local development and its dev adapter

Service-local documents and the refreshed test catalogue now link to
the current arc42 views, which record the build-time-gated HTTP adapter.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Full gate, dev-mode walkthrough, and the pull request

**Files:** none created; this task runs and records evidence.

- [ ] **Step 1: Run the full gate with Docker**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
if id -nG | grep -qw docker; then
  mise run verify
else
  newgrp docker -c 'mise run verify'
fi
```

Expected: the command itself exits 0 and prints `BUILD SUCCESS`; `Tests run:` total equals the refreshed catalogue (PackagingContractTest +1, MigrationIT +1, DevSeedIT 2, DevProfileBootstrapIT 1, DevLedgerResourceIT 8); `Failures: 0, Errors: 0, Skipped: 0`. Do not pipe the gate through `tail`: without an explicitly preserved pipeline status, `tail` can mask a Maven or Testcontainers failure. Record the totals in the PR body. If Docker is unreachable even after the `newgrp` branch, stop here and report the gate as **not run**.

- [ ] **Step 2: Walk the dev-mode path once by hand**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
mise run dev
```

In a second shell, once the console reports `Listening on: http://localhost:8080`:

```bash
curl -i localhost:8080/q/health/ready
curl -s localhost:8080/dev/ledger/reference | head -c 400; echo
curl -s -X POST localhost:8080/dev/ledger/postings -H 'content-type: application/json' -d '{
  "commandId":"11111111-1111-1111-1111-111111111111","transactionType":"PROVIDER_INFLOW",
  "narration":"NGN 10,000.00 inflow","bookingTime":"2026-06-15T10:00:00Z",
  "lines":[{"accountId":"00000000-0000-0000-0000-000000000005","currency":"NGN","signedMinorUnits":1000000},
           {"accountId":"00000000-0000-0000-0000-000000000006","currency":"NGN","signedMinorUnits":-1000000}]}'; echo
curl -s 'localhost:8080/dev/ledger/proofs/trial-balance?currency=NGN'; echo
```

Expected: readiness returns HTTP 200 with aggregate `UP`; the reference shows book `…0001` with two accounts; the posting returns a `journalId`; the trial balance shows `"balanced":true`. Exact totals of 1000000 require a clean database. If reuse retained the fixed command id, verify the idempotent replay or use the developer guide's safely scoped reset before expecting a new journal. Press `q` in the dev console to stop. Paste the responses into the PR body as evidence.

- [ ] **Step 3: Build the image and run the smoke script**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
mise exec java@25 -- ./mvnw -q -DskipTests package
docker build -f Dockerfile.jvm -t core-banking/funds-core:accounting-kernel .
./scripts/prod-runtime-smoke.sh core-banking/funds-core:accounting-kernel
```

Expected: the four probe lines as before plus `reachable-database probe: /dev/ledger/reference returned HTTP 404 (development surface absent from the packaged image)`. Bean inclusion is an exact Java build-profile allowlist with no configuration override; this runtime probe confirms the prod build omits the surface.

- [ ] **Step 4: Confirm production packages are untouched**

```bash
cd "$(git rev-parse --show-toplevel)"
git diff --stat f4f5e91 -- services/funds-core/src/main/java/com/corebanking/funds/application \
  services/funds-core/src/main/java/com/corebanking/funds/domain \
  services/funds-core/src/main/java/com/corebanking/funds/infrastructure \
  services/funds-core/src/main/java/com/corebanking/funds/runtime \
  services/funds-core/src/main/resources/db/migration
```

Expected: no output.

- [ ] **Step 5: Open the pull request**

Fill the template's architecture-impact section as `Architecture changed; linked below` with:

- Related ADRs: ADR-0002 (the dev surface is a convenience over typed commands, not a new owner), ADR-0008 (two runtime dependencies added inside the same 640 MiB budget).
- Current-state arc42 sections changed: building-block, runtime and deployment views for the build-time-gated devtools adapter, its read-only reference queries and its absence from deployable profiles by default.
- Proposals: none.
- Diagrams: `architecture/diagrams/funds-core-components.mmd` adds the devtools adapter and its permitted edges.
- Verification evidence: the verify totals from Step 1, the smoke output from Step 3, and the commit hashes of Tasks 1 to 6.

Walk the AGENTS.md checklist: purpose comments on every new type, no `TODO`, checkstyle passed, no migration edited (V004 text assertions unaffected), verify run with Docker.

---

## Self-review

**Spec coverage.** 5.1 dev profile: Task 1. 5.2 seed and its tests: Tasks 2 and 4, including the actual dev-config-profile Flyway bootstrap. 5.3 inner loops and mise: Task 1 (tasks) and Appendix A (documentation). 5.4 surface, records, resource-local mapper, resource test: Tasks 3 and 4. 5.5 packaged-image guard: Task 5. 5.6 documentation set, refreshed test catalogue, README, health contract, AGENTS pointer and current-state architecture: Tasks 5 and 6. Section 6 testing strategy: every task has a failing test first except Task 5 (shell and Markdown, verified by `bash -n` and the packaging contract) and Task 6 (Markdown and Mermaid, verified by the link check and architecture validator). Section 7 risks: the smoke run in Task 7 covers the dependency risk; Appendix A documents reuse and the fail-closed seed-drift reset.

**Placeholder scan.** No TBD, TODO, "similar to Task N", or "add validation" phrases. Every code step carries its code. The two documents in the appendices are complete.

**Type consistency.** `DevLedgerReferences.BookContext` has the fields `bookId, legalEntityId, chartVersionId, periodId, policyVersion, timezone` in both Task 3's definition and its uses in Tasks 3 and 4. `derived(UUID, String)` is defined in Task 3 and used in Task 4. `DevPostingResponse(commandId, requestHash, journalId, journalSequence, canonicalHash)` is constructed identically in both tasks. The seed identities `…0001` to `…0008` match `TestPostingStack`'s `uuid(1)` to `uuid(8)` and the constants used in `DevSeedIT`, `DevLedgerResourceIT` and Appendix A.

---

## Appendix A: `docs/developer-guide.md`

````markdown
# funds-core developer guide

This guide is for a Java engineer who is new to the `services/funds-core`
module. It describes day-to-day workflows: first run, inner loops, database
inspection, packaging and troubleshooting. For the accounting model itself,
read `services/funds-core/README.md` first. The [documentation index](README.md)
lists every other service-local document and the architecture documents that
govern this module.

## Prerequisites

- [mise](https://mise.jdx.dev/getting-started.html), installed and available
  as `mise` in the shell. It installs and selects the required JDK; do not
  install a separate JDK just for this module. The module's
  `services/funds-core/mise.toml`
  pins `java = "25"`. The Maven enforcer plugin in `pom.xml` requires Java in
  range `[25,26)` and Maven `3.9.16` or later, and fails the `validate` phase
  otherwise.
- [Docker Engine or Docker Desktop](https://docs.docker.com/engine/install/),
  running and reachable from your shell. The test gate uses Testcontainers to run
  PostgreSQL `18.6-bookworm`. Never point the module at a host PostgreSQL
  instance; `CLAUDE.md` requires the test gate to run in Docker, never against
  a host database.
- The Maven wrapper. `services/funds-core/.mvn/wrapper/maven-wrapper.properties`
  pins `apache-maven-3.9.16`, matching the enforcer's minimum. Always invoke
  `./mvnw`, not a locally installed Maven.
- git. `PackagingContractTest` resolves the module path through git and fails
  if git is missing or the repository is not a trusted `safe.directory` (see
  Troubleshooting).
- Bash plus `curl`, `jq`, and GNU `timeout`, all available by those exact
  command names. The production smoke script checks these before it starts;
  install them with the package manager for your OS (GNU `timeout` is normally
  supplied by the `coreutils` package).

Before cloning or building, verify the required external commands:

```bash
mise --version
docker info
command -v bash curl jq timeout
```

If `docker info` reports a Unix-socket permission error on Linux, follow
Docker's post-install instructions, then log out and back in so permanent
group membership is active. For a single command in the current login, use
`newgrp docker -c 'mise run verify'` from `services/funds-core`. Membership in
the `docker` group is root-equivalent; do not add it on a shared machine
without understanding that trust boundary.

To start dev mode before a fresh login activates the group, use
`newgrp docker -c 'mise run dev'`. This occupies that shell until dev mode
stops; the permanent login refresh is the normal setup.

## First run

```bash
git clone <repository-url>
cd core-banking/services/funds-core
mise install
mise run verify
```

`mise run verify` runs `./mvnw clean verify` with the pinned JDK. Expect this
order of work:

1. Checkstyle runs in the `validate` phase, bound there so a comment
   convention violation fails the build before any test runs. The ruleset is
   `config/checkstyle/checkstyle.xml`.
2. Surefire runs the test suite, which includes unit tests, `*IT` integration
   tests, and the packaging/documentation contract tests. `pom.xml` includes
   `**/*IT.java` alongside the usual `Test*`/`*Test`/`*Tests` patterns.
3. Testcontainers pulls and starts `postgres:18.6-bookworm` for the
   PostgreSQL-backed integration tests. The first run downloads the image; it
   is cached locally after that.

A clean run exercises unit, deterministic generated-property, PostgreSQL
integration, failure-injection and real child-process crash tests. No
accounting test is intentionally skipped.

## Inner loops

### Continuous testing

Quarkus dev mode supports continuous testing. Start dev mode with:

```bash
mise run dev
```

This runs `./mvnw quarkus:dev`. In the dev console, press `r` to start
continuous testing; Quarkus re-runs affected tests under the isolated test
profile every time you save. Those tests reset ledger tables, so they must
not share the live dev-mode database. The test configuration disables
cross-run Dev Services reuse and names its database `funds_core_test`, distinct
from the live dev service. Press `q` to stop dev mode.

### A single test class

```bash
mise exec java@25 -- ./mvnw test -Dtest=ClassName
```

### Checkstyle only

```bash
mise run checkstyle
```

This runs `./mvnw checkstyle:check`, the command AGENTS.md's pull-request
checklist calls out, and it
behaves like the bound `validate`-phase execution because the checkstyle
plugin configuration is set at the plugin level, not the execution level.

### Dev mode, the seed, and the dev ledger surface

`mise run dev` starts a PostgreSQL 18.6 Dev Services container, applies all
migrations, and additionally applies a repeatable seed migration at
`src/main/resources/db/dev-seed/R__dev_reference_ledger.sql`. The dev profile
in `application.properties` enables this:

```properties
%dev.quarkus.datasource.devservices.image-name=postgres:18.6-bookworm
%dev.quarkus.datasource.devservices.reuse=true
%dev.quarkus.flyway.migrate-at-start=true
%dev.quarkus.flyway.locations=db/migration,db/dev-seed
```

The Java beans themselves use `@IfBuildProfile(anyOf = {"dev", "test"})`;
there is no property that can expose this surface in a custom or production
build profile.

The seed creates a fixed set of reference rows, all under a book of currency
NGN and time zone Africa/Lagos:

| Row | Fixture id |
|---|---|
| Book | `00000000-0000-0000-0000-000000000001` |
| Chart (active) | `00000000-0000-0000-0000-000000000002` |
| Product | `00000000-0000-0000-0000-000000000003` |
| Product version | `00000000-0000-0000-0000-000000000004` |
| Provider asset account (`PROVIDER-CASH` control) | `00000000-0000-0000-0000-000000000005` |
| Customer liability account (`CUSTOMER-DEPOSITS` control) | `00000000-0000-0000-0000-000000000006` |
| Accounting period (OPEN, dev-only 2020–2099 window) | `00000000-0000-0000-0000-000000000007` |
| Book's legal-entity identifier (there is no separate legal-entity table) | `00000000-0000-0000-0000-000000000008` |

To reuse the Dev Services container across dev-mode restarts, add this to
`~/.testcontainers.properties`:

```properties
testcontainers.reuse.enable=true
```

The repeatable seed accepts an unchanged rerun. If a later seed revision
changes an immutable fixture attribute, startup fails with
`dev_reference_seed_drift`; use the safely scoped reset procedure under
"Inspecting and resetting the database", then restart dev mode so the current
seed is installed from a clean schema.

Dev mode also exposes an HTTP surface under `/dev/ledger`, positively enabled
only by the exact dev/test build-profile allowlist. Prod and arbitrary custom
profiles do not include it. The surface has no authentication: bind it only to
localhost and never port-forward or expose the dev-mode listener. It is not a contract: real callers submit
typed commands with their own request hash (see ADR-0002). The packaged
image returns HTTP 404 on `/dev/ledger/reference`, and the smoke script
asserts this.

Endpoints:

- `GET /dev/ledger/reference` returns the seeded book, legal-entity, chart,
  period and account ids. Product and product-version ids remain database-only.
- `POST /dev/ledger/postings` takes JSON with an optional `commandId`, a
  `transactionType`, a `narration`, required `bookingTime`, optional `valueDate`,
  and `lines` (each with `accountId`, `currency`, `signedMinorUnits`, and
  `dimensions`). The server resolves the book, chart, period and policy
  version and computes the typed request hash for you.
- `POST /dev/ledger/reversals` takes `originalJournalId`, `reason`, required
  `bookingTime`, and an optional `commandId`.
- `GET /dev/ledger/proofs/trial-balance?bookId&currency&cutoff` and
  `GET /dev/ledger/proofs/control-account?bookId&controlCode&currency&cutoff`
  return the independent proofs described in the README.

Responses use the kernel's own vocabulary. A domain rejection
(`InvalidJournalException`, `AccountingPeriodClosedException`,
`MonetaryOverflowException`, `LedgerCapacityException`) returns HTTP 422 with
`{error, message}`, where `error` is the exception's simple name. An
`IdempotencyConflictException` returns 409. A malformed request (missing
field, booking time outside every open period, unknown book) returns 400. A
`LedgerPersistenceException` returns 503.

Identities are derived from the `commandId`: journal, posting, correlation and
business-transaction ids are name-based UUIDs of the command id, and the typed
request hash is computed from the resulting draft. While book governance is
unchanged, sending the same body with the same `commandId` twice returns the
stored result, and changing a hash-bearing field under that command id returns
409. Booking time is required so an identical retry cannot acquire a new
hash-bearing wall-clock value. This convenience still resolves chart, period
and policy at request time and therefore does not promise replay across a chart
rotation, policy change or period closure; real callers pin those coordinates.
Omit `commandId` to get a fresh random one per request.

Wait until startup reports that Quarkus is listening, then verify runtime
readiness before using the surface:

```bash
curl -i http://localhost:8080/q/health/ready
```

Proceed only after it returns HTTP 200 with aggregate status `UP`. This proves
live datasource connectivity, not that production migrations or role grants
are correct.

The following exact totals assume a clean dev database. If you enabled
cross-run reuse and have already used the fixture, reset it first using the
procedure below. Worked example: post NGN 10,000.00 (1,000,000 minor units) as a debit to the
seeded provider asset account and a credit to the seeded customer liability
account, then reverse it, then check the trial balance.

```bash
curl -s http://localhost:8080/dev/ledger/reference

curl -s -X POST http://localhost:8080/dev/ledger/postings \
  -H 'Content-Type: application/json' \
  -d '{
    "commandId": "11111111-1111-1111-1111-111111111111",
    "transactionType": "DEPOSIT",
    "narration": "worked example deposit",
    "bookingTime": "2026-06-15T10:00:00Z",
    "lines": [
      {"accountId": "00000000-0000-0000-0000-000000000005",
       "currency": "NGN", "signedMinorUnits": 1000000, "dimensions": {}},
      {"accountId": "00000000-0000-0000-0000-000000000006",
       "currency": "NGN", "signedMinorUnits": -1000000, "dimensions": {}}
    ]
  }'
```

The response carries `journalId`, `journalSequence`, `canonicalHash` and the
`requestHash` the server computed. Take the `journalId` and reverse it:

```bash
curl -s -X POST http://localhost:8080/dev/ledger/reversals \
  -H 'Content-Type: application/json' \
  -d '{"originalJournalId": "<journal-id-from-the-posting-response>",
       "reason": "worked example reversal", "bookingTime": "2026-06-16T10:00:00Z"}'
```

Then run both proofs. `cutoff` is a journal sequence, not a time; leave it out
to prove at the book's highest committed sequence:

```bash
curl -s 'http://localhost:8080/dev/ledger/proofs/trial-balance?currency=NGN'
curl -s 'http://localhost:8080/dev/ledger/proofs/control-account?currency=NGN&controlCode=CUSTOMER-DEPOSITS'
```

After the posting, the trial balance shows debits and credits of 1,000,000
each and `balanced: true`. After the reversal it shows 2,000,000 each, still
balanced: a reversal is an additive correction, not a deletion (ADR-0005).
The control-account proof shows the `CUSTOMER-DEPOSITS` source total and
projection total agreeing, with `difference: 0`; after the reversal both are
back to 0.

## Inspecting and resetting the database

List the running Dev Services container:

```bash
docker ps
```

Connect to it:

```bash
docker exec -it <container> psql -U quarkus -d quarkus
```

`quarkus` as both user and database name are Dev Services defaults. If a
container was started with different values, check `docker inspect
<container>` for the actual environment.

Generic datasource environment variables such as
`QUARKUS_DATASOURCE_JDBC_URL`, `QUARKUS_DATASOURCE_USERNAME` and
`QUARKUS_DATASOURCE_PASSWORD` disable or redirect zero-config Dev Services.
Unset them for this workflow:

```bash
unset QUARKUS_DATASOURCE_JDBC_URL QUARKUS_DATASOURCE_USERNAME \
  QUARKUS_DATASOURCE_PASSWORD QUARKUS_DATASOURCE_DEVSERVICES_ENABLED
```

Also remove equivalent values from project/profile configuration rather than
merely overriding them in another file. Confirm the startup log plus `docker ps`
show the expected `postgres:18.6-bookworm` container before posting. Never aim
the unauthenticated dev surface or its destructive tests at a shared database.

To recover from `dev_reference_seed_drift`, first stop dev mode. List running
and stopped candidates, inspect each candidate, and remove only the container
whose image, environment and published port match the just-stopped funds-core
Dev Service:

```bash
docker ps -a --filter ancestor=postgres:18.6-bookworm
docker inspect <candidate-container-id>
docker rm -f <verified-funds-core-container-id>
mise run dev
```

Every angle-bracket token above is a placeholder: replace it with the exact
container id you verified; never paste the token literally or automate the
removal from a broad image-name match.

`docker rm -f` is destructive: it discards all local journals in that Dev
Services container. Ordinary restarts do not require this reset.

## Packaging and smoke

These are the README's "Build and verification" commands, run in order:

```bash
mise exec java@25 -- ./mvnw -DskipTests package
docker build -f Dockerfile.jvm -t core-banking/funds-core:accounting-kernel .
docker run --rm --entrypoint java --memory=640m --cpus=0.60 --pids-limit=256 \
  core-banking/funds-core:accounting-kernel -version
./scripts/prod-runtime-smoke.sh core-banking/funds-core:accounting-kernel
```

The smoke script runs four production-runtime probes:

1. A container started with no datasource configuration must fail closed
   with a non-secret diagnostic naming `quarkus.datasource.jdbc.url`.
2. A container started with `QUARKUS_DATASOURCE_ACTIVE=false` must fail
   closed with a non-secret diagnostic naming `quarkus.datasource.active`.
3. A container pointed at an unreachable database must keep running and
   answer `GET /q/health/ready` with HTTP 503, aggregate status `DOWN`, and
   the datasource check reported `DOWN`.
4. A container pointed at a real, reachable `postgres:18.6-bookworm`
   instance must answer `GET /q/health/ready` with HTTP 200, aggregate
   status `UP`, and the datasource check reported `UP`. The same probe then
   requires `GET /dev/ledger/reference` to answer HTTP 404, proving the
   development surface is absent from the packaged image.

None of these probes prove migrations or privileges are correct; they prove
only that startup fails closed on missing configuration and that readiness
correctly reflects a live database connection check.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Maven enforcer rejects your JDK version | Run through mise: `mise exec java@25 -- ./mvnw ...`. |
| Docker unreachable, or Dev Services fails to start | Run `docker info`. On Linux, activate permanent Docker-group membership by logging out and back in, run one gate with `newgrp docker -c 'mise run verify'`, or launch dev mode with `newgrp docker -c 'mise run dev'`. Also check that no external datasource variables redirect dev mode. |
| A child-JVM crash test fails or hangs | The test launches `${java.home}/bin/java`; run the parent Maven process through `mise exec java@25 --` so its `java.home` is the pinned JDK. |
| `PackagingContractTest` fails in CI citing an "exact Git-tracked path" | git is missing from the CI image, or the repository is not registered as a `safe.directory` for the user running the build. |
| `documentedRuntimeSmokeIsExecutable` fails | The checkout has `core.fileMode=false`, so the executable bit on `scripts/prod-runtime-smoke.sh` was not preserved. |
| Tests hang on a `TRUNCATE` statement | A previous test run left a race in a cancelled state holding a lock. Restart the PostgreSQL Testcontainer (or your local database, if you are inspecting manually) rather than waiting. |

### What dev mode is not

Dev mode is not a way to run without migrations: it applies every versioned migration
before the seed, the same as a full deployment. It is not a production
topology: it uses one Dev Services container, not a migrated, separately
privileged production database. The seeded ids are fixtures for local
exploration and worked examples; they are not stable identifiers you should
depend on outside dev mode.
````

## Appendix B: `docs/change-recipes.md`

````markdown
# funds-core change recipes

This document lists common changes to `services/funds-core` and, for each
one, what else must change alongside it, which tests will catch a forgotten
step, and how to verify the change. Read `AGENTS.md` and
`docs/conventions/code-comments.md` before editing anything: comment
conventions apply to every change here.

## Adding a versioned migration

**When**: you need a new schema change and there is no open, unapplied
migration for it.

**Also change**:

- Name the file `Vnnn__description.sql` following the existing sequence.
  Point versions use an underscore in the file name: `V003_1__...` and
  `V003_2__...` are versions `3.1` and `3.2`. The eight applied versions are
  `V001`, `V002`, `V003`, `V003_1`, `V003_2`, `V004`, `V005` and `V006`, so
  the next one is `V007`.
- If your migration runs after `V004__application_roles.sql`, its first
  statement must be `SET ROLE funds_migrator`, per
  `src/main/resources/db/MIGRATION-ROLES.md`.
- Open the file with a header comment block whose first line matches
  `-- Vnnn:` (the module's checkstyle `RegexpHeader` rule requires this, and
  the rule needs at least two header lines).
- Update `src/main/resources/db/MIGRATION-ROLES.md` if the migration changes
  roles, grants, or the reset procedure.
- Update the README sentence "There are eight versioned migrations" in the
  "Database roles and startup" section to the new count and list.

**Tests that will fail if you forget**:

- `MigrationIT` and `MigrationUpgradeIT` run the migrations against a real
  PostgreSQL Testcontainer and will fail on a bad ordering, a missing `SET
  ROLE`, or a broken upgrade path.
- The checkstyle `RegexpHeader` check (bound to `validate`) fails the build
  if the header is missing or malformed.

**Verify**: run `mise run verify`. `MigrationIT` also asserts that the test
profile applied exactly eight versioned migrations and no repeatable one, so
update that count in the same change. Never run the verify gate while a
canary or scratch migration file is present in `src/main/resources/db/migration`;
Flyway applies every file it finds there, and a leftover canary will apply
against the real test database and can corrupt the migration history checked
by `MigrationIT`.

## Changing a pinned runtime property

**When**: you need to change a JVM, JDBC, thread-pool, HTTP, or posting
timeout limit in `src/main/resources/application.properties`.

**Also change**:

- The property is one of the 19 entries in `CONTROLLED_PROPERTIES` in
  `PackagingContractTest`. Update that map's expected value to match.
- Update the README "Memory boundary" section if the property is part of the
  resource budget it describes.
- Update `docs/health-contract.md` if the property affects health, pool, or
  timeout semantics described there.
- Confirm the property still has exactly one active assignment.
  `java.util.Properties` keeps only the last assignment for a duplicate key,
  so a stray second line would silently widen a limit while the test still
  matched the final value; `PackagingContractTest` guards against this with
  its own counting reader.
- Do not add a raw JDBC URL to the file. The three production connection
  values (`FUNDS_DB_JDBC_URL`, `FUNDS_APP_DB_USER`, `FUNDS_APP_DB_PASSWORD`)
  are supplied only through mounted deployment configuration and secrets.
- The non-production profile keys are pinned too:
  `%dev.quarkus.datasource.devservices.image-name`,
  `%dev.quarkus.datasource.devservices.reuse`,
  `%dev.quarkus.flyway.migrate-at-start`, and
  `%dev.quarkus.flyway.locations`. Treat a change to any of these the same
  way as a `%prod` or unconditioned property change. Devtools inclusion is an
  exact Java build-profile allowlist and must never become configurable.

**Tests that will fail if you forget**: `PackagingContractTest` reads
`pom.xml`, `application.properties`, `Dockerfile.jvm`, `README.md`,
`docs/health-contract.md`, and `scripts/prod-runtime-smoke.sh` as text and
enforces specific structural and literal contracts. It does not prove that
all prose agrees, so manually review the affected README and health-contract
claims whenever a pinned value changes.

**Verify**: `mise run verify`, then read the "Memory boundary" section of
the README and `docs/health-contract.md` to confirm they still describe the
new value accurately.

## Changing a limit that lives in both Java and SQL

**When**: you need to change `JournalValidator`'s `MAX_POSTINGS_PER_JOURNAL`
(256), `MAX_DIMENSIONS_PER_POSTING` (32), or `MAX_DIMENSION_JSON_BYTES`
(8192).

**Also change**:

- The matching `V005` database `CHECK` constraints:
  `posting_dimensions_count_check`, `posting_dimensions_bytes_check`, and
  `journal_reversible_posting_count`. These constraints enforce the same
  limits at the database layer; changing only the Java constant leaves the
  database silently more or less permissive than the application.
- The README's `ACC-20` acceptance row, which states the exact limits
  (256 postings, 32 dimensions, 8,192 persisted dimension bytes).

**Tests that will fail if you forget**: `JournalValidatorTest`
(`rejectsJournalAndDimensionInputsBeyondTheReversalEnvelope` rejects 257, 33
and an oversized value), `AcceptanceHardeningIT`
(`databaseRejectsTheTwoHundredFiftySeventhDirectPosting` and
`databaseRejectsEveryIrreversibleDirectPostingDomain` prove the database
rejects one over each limit), and `ReversalServiceIT`
(`reversesAJournalAtTheExactTwoHundredFiftySixPostingBoundary` and
`acceptsDimensionJsonAtExactPocByteLimit` prove the limit itself is
accepted). If only one layer moves, the Java and database tests disagree.

**Verify**: `mise run verify`, and manually confirm the Java constant and
the SQL constraint use the same number by reading both files side by side.

## Changing the Dockerfile or base image

**When**: you are refreshing the base image digest or otherwise editing
`Dockerfile.jvm`.

**Also change**:

- Put the full `sha256:` digest in `Dockerfile.jvm`'s `FROM` line, following
  the "Base-image review and refresh" procedure in the README: pull the
  target Java 25 JRE tag, inspect and record its digest and Java patch
  version, review the upstream change, then update the Dockerfile.
- Update the README "Base-image review and refresh" section with the new
  8-character digest prefix (for example `sha256:f9e65324`), the review date,
  and the platform.
- `scripts/prod-runtime-smoke.sh` does not itself pin the application image
  digest, but its four probes must still pass against the rebuilt image.
- `PackagingContractTest` asserts the exact Dockerfile directive list,
  including the full digest string, `WORKDIR /work`, the four `COPY` lines,
  `USER 10001`, the `JAVA_TOOL_OPTIONS` value, and the `ENTRYPOINT`. Any
  directive reordering or wording change must match this list exactly.

**Tests that will fail if you forget**: `PackagingContractTest` compares the
Dockerfile's directives line by line against a fixed expected list and fails
on any mismatch, including a stale digest.

**Verify**: rebuild without relying on the old tag cache, then rerun
`mise run verify`, the constrained `java -version` smoke run, and
`./scripts/prod-runtime-smoke.sh core-banking/funds-core:accounting-kernel`.
Commit the new digest, review date, platform, and evidence as one reviewed
change.

## Changing the README or health contract

**When**: you are editing `README.md` or `docs/health-contract.md`.

**Also change**: nothing extra by default, but preserve these exact
elements, because `PackagingContractTest` checks them literally:

- The README headings "Reading the accounting model", "Identity and product
  foundations", "Database roles and startup", "Build and verification",
  "Memory boundary", "Acceptance coverage and limits", "Explicit exclusions",
  and "Base-image review and refresh" must each appear exactly once.
- The health contract headings "Endpoints", "Database and migration
  prerequisite", and "Resource and failure semantics" must each appear
  exactly once.
- Every `ACC-nn` acceptance row in the README must appear exactly once, and
  the full set (`ACC-01`, `ACC-02`, `ACC-19`, `ACC-20`, `ACC-24`, `ACC-25`,
  `ACC-29`, `ACC-32`, `ACC-38`, `ACC-40`, `ACC-42`) must be present.
- The "Explicit exclusions" bullet list must match its expected set exactly,
  with no duplicate or missing entry.
- The "Database roles and startup" section must contain the exact phrase
  "fail closed before readiness can be UP".
- The "Base-image review and refresh" section must contain the digest
  prefix `sha256:f9e65324` (or whatever the current digest is) and the exact
  phrase "all four production-runtime probes" exactly once.
- The "Build and verification" section must contain the exact line
  `./scripts/prod-runtime-smoke.sh core-banking/funds-core:accounting-kernel`,
  and that line must appear exactly once.

**Tests that will fail if you forget**:
`documentationHasUniqueRequiredSectionsCoverageAndExclusions` and
`documentedRuntimeSmokeIsExecutable` in `PackagingContractTest`.

**Verify**: `mise run verify`.

## Editing the dev seed

**When**: you need to change the fixtures created by
`src/main/resources/db/dev-seed/R__dev_reference_ledger.sql`.

**Also change**:

- Remember this is a Flyway repeatable migration (`R__` prefix): Flyway tries
  it again whenever its checksum changes on the next dev-mode start. Immutable
  fixture changes cannot be upgraded in place; the final assertion must fail
  with `dev_reference_seed_drift` and an instruction to discard the reused
  Dev Services database rather than silently retain stale rows.
- Keep an unchanged rerun idempotent. `ON CONFLICT DO NOTHING` is sufficient
  to avoid duplicate inserts for the book, chart, period, product and product
  version only because the final assertion compares the persisted immutable
  attributes with the current seed. It is not enough for `chart_version`,
  `ledger_account` or `ledger_account_chart_mapping`: their V005 BEFORE INSERT
  triggers mutate governance revisions or reject before conflict resolution,
  so those inserts use `INSERT ... SELECT ... WHERE NOT EXISTS`. The unchanged
  rerun test must prove the book governance revision does not advance. Guard
  the activation `UPDATE` with `status = 'DRAFT'`.
- Seed no balances, journals or projections; proofs must come from real
  postings.
- If you change a seeded id or attribute that the developer guide's worked
  example depends on (the book, chart, product, product version, the two
  seeded accounts, the accounting period, or the legal entity), update the
  developer guide's reference table and worked example to match.

**Tests that will fail if you forget**: `DevSeedIT` applies the unchanged seed
twice, proves stale reused state fails with `dev_reference_seed_drift`, asserts
the row counts and active chart, and posts through the real stack against the seeded graph.
`DevProfileBootstrapIT`, `DevLedgerResourceIT` and the developer guide use the
same identities; the bootstrap test additionally proves the real `%dev`
Flyway location discovers the repeatable migration.

**Verify**: `mise run verify`, then start `mise run dev` and confirm
`GET /dev/ledger/reference` returns the expected ids.

## Adding a domain exception or changing an error mapping

**When**: you are introducing a new rejection case in the domain layer or
changing how an existing exception maps to an HTTP response.

**Also change**:

- The resource-local `@ServerExceptionMapper` method in `DevLedgerResource`, which maps domain
  rejections to 422, idempotency conflicts to 409, malformed input to 400 and
  persistence failures to 503, all with `{error, message}` bodies. An unmapped
  new exception falls through to 500, which the dev surface reports with the
  exception name so it is visible rather than silent.
- The README acceptance table row that names the outcome, if the exception is
  new evidence for an `ACC-nn` claim.

**Tests that will fail if you forget**: the integration tests that assert the
exception type on the affected path (see `docs/test-catalogue.md` for which
class covers which outcome), and `DevLedgerResourceIT`, which asserts the
mapped status and `error` name for the unbalanced, conflict, second-reversal
and out-of-period cases.

**Verify**: `mise run verify`, and manually exercise the new rejection
path through `mise run dev` and the relevant `/dev/ledger` endpoint if it is
reachable that way.

## When a change needs an ADR

Check `architecture/adr/README.md`. It requires an ADR for:

> a material change to service/module boundaries, financial invariant
> ownership, accounting semantics, authoritative data ownership,
> public/provider/cross-language contracts, security/regulatory/audit/trust
> boundaries, deployment/failure domains, resource budgets, consistency,
> concurrency, idempotency, recovery/delivery guarantees, or deliberately
> accepted significant technical debt.

Routine refactoring, local details, documentation corrections, and
compatible dependency patches do not need one unless they cross that
threshold.

If your change needs an ADR:

- Create it under `architecture/adr/` from `architecture/adr/template.md`,
  with the next zero-padded sequential id and a kebab-case filename, like
  `0009-adopt-an-enforced-code-comment-convention.md`.
- Start it as `Proposed`, unless it is a retrospective record with verified
  pre-introduction evidence.
- Use path-bound local evidence (`HASH changed: repository/path` or `HASH
  snapshot: repository/path`) or a stable same-repository pull-request URL.

Fill in the pull request template's "Architecture impact" section
accordingly: check "Architecture changed; linked below" instead of "No
architecture impact", and list the related ADRs, the current-state arc42
sections changed, any proposals implemented/invalidated/superseded, diagrams
changed, and the verification evidence. Per `architecture/README.md`,
selecting "No architecture impact" while actually changing a boundary,
invariant, contract, deployment topology, trust boundary, or resource budget
is a review defect.

## Comment convention checklist

Before opening a pull request, `AGENTS.md` asks you to confirm:

- [ ] New public types have a purpose comment; new migrations have a header
      comment block.
- [ ] No comment restates its code; no `TODO`/`FIXME` was added.
- [ ] `mise run checkstyle` passes in `services/funds-core`.
- [ ] If a migration was edited, the tests that read it as text still pass
      (for example `MigrationIT` asserts on `V004__application_roles.sql`
      that it contains exactly three `CREATE ROLE funds_...` lines and never
      contains `IF NOT EXISTS`, `ALTER ROLE funds_`, `pg_auth_members`, or
      `REVOKE %I FROM %I`, comments included).
- [ ] `mise run verify` was run in `services/funds-core` with Docker
      available.

`docs/conventions/code-comments.md` adds: comment the why, not the what;
point at concepts instead of re-teaching them; accuracy beats coverage,
so do not write a claim you have not verified against the code; comments
never change behavior, and a comment-only change must leave a
comment-stripped diff empty; no work items in comments.
````
