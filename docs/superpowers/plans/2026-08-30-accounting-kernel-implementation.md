# Accounting Kernel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Java `funds-core` accounting kernel that owns books, chart-of-accounts reference data, immutable balanced journals, checked monetary arithmetic, idempotent posting, materialised balances, outbox facts, accounting periods, reversals, trial-balance proof and subledger/control-account proof.

**Architecture:** Implement one Quarkus JVM service around an explicit PostgreSQL transaction boundary. Domain types validate money and journal equations before persistence; PostgreSQL independently enforces per-currency balance, immutability, account/currency compatibility and period state. The posting service locks accounts canonically, resolves idempotency inside the money transaction, writes journal/postings/balances/outbox atomically and exposes proof queries without introducing provider, hold or workflow concerns.

**Tech Stack:** Java 25 LTS; Quarkus 3.33.3.1 LTS; Maven 3.9.16 wrapper; PostgreSQL 18.6; JDBC/Agroal; Flyway; JUnit 6 on the Quarkus-managed platform; a deterministic in-repository property-case generator; Testcontainers through Quarkus Dev Services.

**Spec:** `architecture/modern-core-banking-comprehensive-design-revised.md`

## Global Constraints

- `funds-core` is the only writer of accounts, journals, postings, materialised balances and its money-event outbox.
- Positive signed posting amounts are debits; negative signed posting amounts are credits.
- Posted amounts are checked signed 64-bit integer minor units. Java arithmetic uses `Math.addExact`, `Math.subtractExact`, `Math.multiplyExact` and `Math.negateExact`.
- Every journal belongs to one legal entity and one book and balances to zero independently per currency.
- Every account has exactly one currency, account class and normal direction.
- Customer-facing balance signs are derived from normal direction; persistence never rewrites posting signs for display.
- Ordinary postings require an `OPEN` accounting period and the active chart/policy version.
- Closed-period corrections are linked new journals in the current open period; journals and postings are never updated or deleted.
- One command ID and canonical request hash produce at most one committed financial effect.
- Journal, postings, materialised balances, idempotency result and outbox event commit atomically.
- Database aggregates use `numeric`, not `bigint`, when checking totals so overflow cannot reproduce an application error.
- The implementation target is JVM mode. Native-image work is excluded from this slice.
- Compile and run `funds-core` on Java 25 LTS; do not silently fall back to Java 21 or a non-LTS feature release.
- Build new POC databases directly on PostgreSQL 18.6. An upgrade from an earlier PostgreSQL 18 minor must follow the 18.6 release-note checks for pre-existing `btree_gist` indexes; this fresh POC has no such indexes to migrate.
- The service uses synthetic test data only.
- The normal container target is 640 MiB with `-Xms128m -Xmx384m -XX:MaxMetaspaceSize=96m -XX:MaxDirectMemorySize=64m -Xss512k`.

## Program Decomposition

The approved specification is implemented through separate reviewable plans in delivery order:

1. This plan: accounting kernel.
2. Funds control: holds, restrictions, provider-float reservation and multi-replica concurrency.
3. Java/Go Protobuf contracts and canonical hashing fixtures.
4. Transactional outbox relay, Redpanda transport and Go projections.
5. Go orchestration, provider simulator, durable submission intent and outbound payout.
6. Provider routing, capability/settlement registry and resilience state.
7. Reconciliation, source manifests, suspense and daily proof.
8. Multi-currency FX execution and paired-journal workflows.
9. Identity, privileged controls, audit anchoring, backup and restore.
10. Exact 8 GiB Compose profiles, resource fault injection and acceptance evidence.

This plan does not create holds, call providers, start Temporal, publish to Redpanda or implement customer/channel APIs. It does create durable outbox rows because a committed money fact without its event violates the accounting boundary.

## File Structure

```text
services/funds-core/
├── .mvn/wrapper/maven-wrapper.properties
├── mvnw
├── mvnw.cmd
├── pom.xml
├── src/main/java/com/corebanking/funds/
│   ├── domain/
│   │   ├── AccountClass.java
│   │   ├── AccountStatus.java
│   │   ├── AccountingPeriodStatus.java
│   │   ├── Book.java
│   │   ├── CurrencyCode.java
│   │   ├── JournalDraft.java
│   │   ├── LedgerAccount.java
│   │   ├── Money.java
│   │   ├── NormalBalance.java
│   │   ├── PostingLine.java
│   │   ├── ReversalRequest.java
│   │   └── exception/
│   │       ├── AccountingPeriodClosedException.java
│   │       ├── IdempotencyConflictException.java
│   │       ├── InvalidJournalException.java
│   │       ├── LedgerPersistenceException.java
│   │       └── MonetaryOverflowException.java
│   ├── application/
│   │   ├── CanonicalJournalHasher.java
│   │   ├── JournalValidator.java
│   │   ├── PostingCommand.java
│   │   ├── PostingResult.java
│   │   ├── PostingService.java
│   │   ├── ReversalService.java
│   │   └── proof/
│   │       ├── AccountingProofService.java
│   │       ├── ControlAccountProof.java
│   │       └── TrialBalanceProof.java
│   └── infrastructure/postgres/
│       ├── JdbcAccountingProofRepository.java
│       ├── JdbcLedgerRepository.java
│       ├── LedgerRepository.java
│       ├── PostgresRetryPolicy.java
│       └── SqlState.java
├── src/main/resources/
│   ├── application.properties
│   └── db/migration/
│       ├── V001__accounting_reference.sql
│       ├── V002__journal_and_outbox.sql
│       ├── V003__ledger_invariants.sql
│       └── V004__application_roles.sql
├── src/test/java/com/corebanking/funds/
│   ├── domain/MoneyTest.java
│   ├── application/JournalValidatorTest.java
│   ├── application/JournalProperties.java
│   ├── testsupport/PropertyCases.java
│   ├── application/PostingServiceIT.java
│   ├── application/PostingConcurrencyIT.java
│   ├── application/ReversalServiceIT.java
│   ├── application/proof/AccountingProofServiceIT.java
│   └── infrastructure/postgres/
│       ├── LedgerConstraintIT.java
│       └── MigrationIT.java
└── src/test/resources/application.properties
```

`domain` contains dependency-free financial types. `application` owns use-case rules and transaction orchestration. `infrastructure/postgres` contains SQL and JDBC mapping only. Tests use real PostgreSQL for every database invariant; H2 is prohibited because deferred constraints, row locking, roles and PostgreSQL numeric semantics are part of the feature.

---

### Task 1: Bootstrap the Quarkus service and exact money primitive

**Files:**
- Create: `services/funds-core/pom.xml`
- Create: `services/funds-core/.mvn/wrapper/maven-wrapper.properties`
- Create: `services/funds-core/mvnw`
- Create: `services/funds-core/mvnw.cmd`
- Create: `services/funds-core/src/main/resources/application.properties`
- Create: `services/funds-core/src/test/resources/application.properties`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/CurrencyCode.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/Money.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/exception/MonetaryOverflowException.java`
- Test: `services/funds-core/src/test/java/com/corebanking/funds/domain/MoneyTest.java`

**Interfaces:**
- Consumes: Java 25 records and exact integer arithmetic.
- Produces: `CurrencyCode.of(String)`, `Money.of(CurrencyCode,long)`, `Money.add(Money)`, `Money.subtract(Money)` and `Money.negate()`.

- [ ] **Step 1: Generate the Maven wrapper and minimal Quarkus project metadata**

Create a Maven project pinned to the Quarkus 3.33.3.1 LTS BOM. Include `quarkus-arc`, `quarkus-jdbc-postgresql`, `quarkus-flyway`, `flyway-database-postgresql`, `quarkus-smallrye-health`, `quarkus-micrometer-registry-prometheus`, `quarkus-junit` and `rest-assured`. Configure compiler release 25 and Enforcer rules requiring Java 25 and Maven 3.9.16 or newer. Do not add a second test-engine BOM: Quarkus 3.33 manages JUnit 6, and the generated property suite below deliberately runs as ordinary JUnit tests.

Run:

```bash
cd services/funds-core
mvn -N wrapper:wrapper -Dmaven=3.9.16
./mvnw --version
```

Expected: Maven reports 3.9.16 and Java 25; the Enforcer rule rejects execution on Java 21 or a non-25 feature release.

- [ ] **Step 2: Write failing exact-money tests**

```java
package com.corebanking.funds.domain;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MoneyTest {
    private static final CurrencyCode NGN = CurrencyCode.of("NGN");

    @Test void addsOnlySameCurrency() {
        assertEquals(Money.of(NGN, 150), Money.of(NGN, 100).add(Money.of(NGN, 50)));
        assertThrows(IllegalArgumentException.class,
            () -> Money.of(NGN, 100).add(Money.of(CurrencyCode.of("USD"), 50)));
    }

    @Test void rejectsOverflowInsteadOfWrapping() {
        assertThrows(MonetaryOverflowException.class,
            () -> Money.of(NGN, Long.MAX_VALUE).add(Money.of(NGN, 1)));
        assertThrows(MonetaryOverflowException.class,
            () -> Money.of(NGN, Long.MIN_VALUE).negate());
    }

    @Test void currencyCodeIsCanonical() {
        assertEquals("NGN", CurrencyCode.of("ngn").value());
        assertThrows(IllegalArgumentException.class, () -> CurrencyCode.of("NAIRA"));
    }
}
```

- [ ] **Step 3: Run the test to verify failure**

Run: `cd services/funds-core && ./mvnw -Dtest=MoneyTest test`

Expected: FAIL because `CurrencyCode`, `Money` and `MonetaryOverflowException` do not exist.

- [ ] **Step 4: Implement the minimal money types**

```java
package com.corebanking.funds.domain;

import java.util.Locale;
import java.util.Objects;

public record CurrencyCode(String value) {
    public CurrencyCode {
        value = Objects.requireNonNull(value, "currency").toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z]{3}")) throw new IllegalArgumentException("currency must be three letters");
    }
    public static CurrencyCode of(String value) { return new CurrencyCode(value); }
}
```

```java
package com.corebanking.funds.domain;

import com.corebanking.funds.domain.exception.MonetaryOverflowException;

public record Money(CurrencyCode currency, long minorUnits) {
    public static Money of(CurrencyCode currency, long minorUnits) { return new Money(currency, minorUnits); }

    public Money add(Money other) {
        requireSameCurrency(other);
        try { return new Money(currency, Math.addExact(minorUnits, other.minorUnits)); }
        catch (ArithmeticException e) { throw new MonetaryOverflowException(e); }
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        try { return new Money(currency, Math.subtractExact(minorUnits, other.minorUnits)); }
        catch (ArithmeticException e) { throw new MonetaryOverflowException(e); }
    }

    public Money negate() {
        try { return new Money(currency, Math.negateExact(minorUnits)); }
        catch (ArithmeticException e) { throw new MonetaryOverflowException(e); }
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) throw new IllegalArgumentException("currency mismatch");
    }
}
```

`MonetaryOverflowException` extends `RuntimeException` and accepts the original `ArithmeticException` as its cause.

- [ ] **Step 5: Run tests and build**

Run: `cd services/funds-core && ./mvnw test`

Expected: PASS with all `MoneyTest` cases green.

- [ ] **Step 6: Commit**

```bash
git add services/funds-core
git commit -m "feat(funds-core): bootstrap exact money domain"
```

---

### Task 2: Define accounting reference types and natural-balance calculation

**Files:**
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/AccountClass.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/NormalBalance.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/AccountStatus.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/AccountingPeriodStatus.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/Book.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/LedgerAccount.java`
- Modify: `services/funds-core/src/test/java/com/corebanking/funds/domain/MoneyTest.java`

**Interfaces:**
- Consumes: `CurrencyCode`, signed posting totals.
- Produces: `NormalBalance.toNatural(long)`, `LedgerAccount.bookedNaturalBalance(long)` and immutable reference records.

- [ ] **Step 1: Write failing normal-direction tests**

```java
@Test void rendersDebitAndCreditNormalBalances() {
    assertEquals(10_000, NormalBalance.DEBIT.toNatural(10_000));
    assertEquals(10_000, NormalBalance.CREDIT.toNatural(-10_000));
    assertEquals(-2_000, NormalBalance.CREDIT.toNatural(2_000));
}
```

Add construction tests proving that `Book` requires legal entity, functional currency, timezone, calendar and policy version, and `LedgerAccount` requires book, currency, class, normal direction, control-account code and non-null status.

- [ ] **Step 2: Run the test to verify failure**

Run: `cd services/funds-core && ./mvnw -Dtest=MoneyTest test`

Expected: FAIL because the accounting reference types do not exist.

- [ ] **Step 3: Implement normal direction and reference records**

```java
public enum NormalBalance {
    DEBIT(1), CREDIT(-1);
    private final int multiplier;
    NormalBalance(int multiplier) { this.multiplier = multiplier; }
    public long toNatural(long signedPostingTotal) {
        try { return Math.multiplyExact(signedPostingTotal, multiplier); }
        catch (ArithmeticException e) { throw new MonetaryOverflowException(e); }
    }
}
```

Use these exact enum members:

```java
enum AccountClass { ASSET, LIABILITY, EQUITY, INCOME, EXPENSE }
enum AccountStatus { OPEN, DEBIT_BLOCKED, CREDIT_BLOCKED, CLOSED }
enum AccountingPeriodStatus { OPEN, CLOSING, CLOSED }
```

Implement `Book` and `LedgerAccount` as records with UUID identifiers and constructor validation. `LedgerAccount` exposes `bookedNaturalBalance(long signedTotal)` by delegating to its `NormalBalance`.

- [ ] **Step 4: Run domain tests**

Run: `cd services/funds-core && ./mvnw -Dtest='MoneyTest' test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/funds-core/src/main/java/com/corebanking/funds/domain services/funds-core/src/test/java/com/corebanking/funds/domain
git commit -m "feat(funds-core): model accounting reference data"
```

---

### Task 3: Validate journal equations and canonical content hashes in memory

**Files:**
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/PostingLine.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/JournalDraft.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/exception/InvalidJournalException.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/application/JournalValidator.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/application/CanonicalJournalHasher.java`
- Test: `services/funds-core/src/test/java/com/corebanking/funds/application/JournalValidatorTest.java`
- Test: `services/funds-core/src/test/java/com/corebanking/funds/application/JournalProperties.java`

**Interfaces:**
- Consumes: account IDs, currency codes and signed minor units.
- Produces: `JournalValidator.validate(JournalDraft)` and `CanonicalJournalHasher.sha256(JournalDraft)`.

- [ ] **Step 1: Write example-based failing tests**

Cover these exact cases:

```java
@Test void acceptsBalancedSingleCurrencyJournal() {
    var draft = fixtureJournal(
        line(assetAccount, "NGN", 100_000),
        line(customerLiability, "NGN", -100_000));
    assertDoesNotThrow(() -> validator.validate(draft));
}

@Test void rejectsPerCurrencyImbalance() {
    var draft = fixtureJournal(
        line(usdPosition, "USD", 1_000),
        line(ngnPosition, "NGN", -1_000));
    assertThrows(InvalidJournalException.class, () -> validator.validate(draft));
}

@Test void hashIsIndependentOfInputLineOrder() {
    assertEquals(hasher.sha256(draft(a, b)), hasher.sha256(draft(b, a)));
}
```

Also reject an empty journal, a zero posting, duplicate posting identity and arithmetic overflow while summing a currency.

- [ ] **Step 2: Run tests to verify failure**

Run: `cd services/funds-core && ./mvnw -Dtest=JournalValidatorTest test`

Expected: FAIL because journal types and services do not exist.

- [ ] **Step 3: Implement journal types and validation**

`PostingLine` is a record containing `UUID postingId`, `UUID accountId`, `CurrencyCode currency`, `long signedMinorUnits`, `long accountSequence`, and immutable `Map<String,String> dimensions`. Reject zero amounts.

`JournalDraft` contains `UUID journalId`, `UUID commandId`, `UUID correlationId`, `UUID businessTransactionId`, `UUID legalEntityId`, `UUID bookId`, `String transactionType`, `String narration`, `Instant bookingTime`, `LocalDate valueDate`, optional reversal journal ID, policy/template version and non-empty posting list.

`JournalValidator` groups by currency and sums with `Math.addExact`; every currency total must equal zero. It also ensures posting IDs and account IDs needed by the command are non-null.

`CanonicalJournalHasher` sorts postings by account ID then posting ID, encodes field names, lengths and UTF-8 bytes deterministically, and returns lowercase SHA-256 hex. It excludes database sequence values assigned after command creation but includes every field that changes financial meaning.

- [ ] **Step 4: Add deterministic generated balance properties**

```java
@Test
void addingEqualDebitAndCreditAlwaysBalances() {
    PropertyCases.positiveMinorUnits(0xCB20260830L, 2_000).forEach(amount -> {
        var draft = fixtureJournal(line(assetAccount, "NGN", amount), line(liabilityAccount, "NGN", -amount));
        assertDoesNotThrow(() -> validator.validate(draft), () -> "amount=" + amount);
    });
}

@Test
void changingOneSideByOneMinorUnitAlwaysFails() {
    PropertyCases.positiveMinorUnits(0xCB20260830L, 2_000).forEach(amount -> {
        var draft = fixtureJournal(line(assetAccount, "NGN", amount), line(liabilityAccount, "NGN", -amount + 1));
        assertThrows(InvalidJournalException.class, () -> validator.validate(draft), () -> "amount=" + amount);
    });
}
```

`PropertyCases.positiveMinorUnits(seed, randomCases)` returns a `LongStream` containing the boundary values `1`, `2`, `99`, `100`, `1_000_000_000`, `Long.MAX_VALUE / 2` and `Long.MAX_VALUE - 1`, followed by `randomCases` values from `SplittableRandom(seed)` in `[1, 1_000_000_001)`. The seed and failing amount appear in assertion output. This keeps the properties reproducible and compatible with the Quarkus-managed JUnit platform.

- [ ] **Step 5: Run example and property tests**

Run: `cd services/funds-core && ./mvnw -Dtest='JournalValidatorTest,JournalProperties' test`

Expected: PASS; each property evaluates all seven boundaries plus 2,000 seeded generated cases.

- [ ] **Step 6: Commit**

```bash
git add services/funds-core/src/main/java/com/corebanking/funds services/funds-core/src/test/java/com/corebanking/funds
git commit -m "feat(funds-core): validate balanced journal drafts"
```

---

### Task 4: Create accounting reference schema and migrations

**Files:**
- Create: `services/funds-core/src/main/resources/db/migration/V001__accounting_reference.sql`
- Create: `services/funds-core/src/test/java/com/corebanking/funds/infrastructure/postgres/MigrationIT.java`
- Modify: `services/funds-core/src/test/resources/application.properties`

**Interfaces:**
- Consumes: PostgreSQL 18.6.
- Produces: `funds.book`, `funds.chart_version`, `funds.accounting_period`, `funds.ledger_account` and controlled enum checks.

- [ ] **Step 1: Configure real PostgreSQL tests**

```properties
quarkus.datasource.db-kind=postgresql
quarkus.datasource.devservices.image-name=postgres:18.6-bookworm
quarkus.datasource.jdbc.min-size=1
quarkus.datasource.jdbc.max-size=8
quarkus.flyway.migrate-at-start=true
quarkus.flyway.clean-disabled=false
```

- [ ] **Step 2: Write failing migration assertions**

Use `@QuarkusTest` and injected `AgroalDataSource`. Query `information_schema.tables` and assert all four tables exist. Attempt to insert an account with an invalid currency length, invalid normal direction and missing book; each insert must fail.

- [ ] **Step 3: Run the test to verify failure**

Run: `cd services/funds-core && ./mvnw -Dtest=MigrationIT test`

Expected: FAIL because migration V001 does not exist.

- [ ] **Step 4: Implement V001 reference schema**

The migration must:

```sql
CREATE SCHEMA funds;

CREATE TABLE funds.book (
    book_id uuid PRIMARY KEY,
    legal_entity_id uuid NOT NULL,
    functional_currency char(3) NOT NULL CHECK (functional_currency ~ '^[A-Z]{3}$'),
    timezone text NOT NULL,
    calendar_code text NOT NULL,
    accounting_policy_version integer NOT NULL CHECK (accounting_policy_version > 0),
    UNIQUE (legal_entity_id, book_id)
);

CREATE TABLE funds.chart_version (
    chart_version_id uuid PRIMARY KEY,
    book_id uuid NOT NULL REFERENCES funds.book(book_id),
    version integer NOT NULL CHECK (version > 0),
    status text NOT NULL CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
    activated_at timestamptz,
    UNIQUE (book_id, version)
);

CREATE TABLE funds.accounting_period (
    period_id uuid PRIMARY KEY,
    book_id uuid NOT NULL REFERENCES funds.book(book_id),
    business_date_from date NOT NULL,
    business_date_to date NOT NULL,
    status text NOT NULL CHECK (status IN ('OPEN','CLOSING','CLOSED')),
    CHECK (business_date_to >= business_date_from),
    EXCLUDE USING gist (book_id WITH =, daterange(business_date_from, business_date_to, '[]') WITH &&)
);

CREATE TABLE funds.ledger_account (
    account_id uuid PRIMARY KEY,
    book_id uuid NOT NULL REFERENCES funds.book(book_id),
    chart_version_id uuid NOT NULL REFERENCES funds.chart_version(chart_version_id),
    account_code text NOT NULL,
    account_class text NOT NULL CHECK (account_class IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
    normal_balance text NOT NULL CHECK (normal_balance IN ('DEBIT','CREDIT')),
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    control_account_code text NOT NULL,
    status text NOT NULL CHECK (status IN ('OPEN','DEBIT_BLOCKED','CREDIT_BLOCKED','CLOSED')),
    authorised_floor_minor bigint NOT NULL DEFAULT 0 CHECK (authorised_floor_minor <= 0),
    created_at timestamptz NOT NULL,
    closed_at timestamptz,
    UNIQUE (book_id, account_code, currency)
);
```

Enable `btree_gist` before the exclusion constraint. Add indexes on period lookup and account book/status. Seed only deterministic test fixtures from tests, never from the production migration.

- [ ] **Step 5: Run migration tests**

Run: `cd services/funds-core && ./mvnw -Dtest=MigrationIT test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add services/funds-core/src/main/resources/db/migration/V001__accounting_reference.sql services/funds-core/src/test
git commit -m "feat(funds-core): add accounting reference schema"
```

---

### Task 5: Create immutable journal, balance, idempotency and outbox schema

**Files:**
- Create: `services/funds-core/src/main/resources/db/migration/V002__journal_and_outbox.sql`
- Create: `services/funds-core/src/main/resources/db/migration/V003__ledger_invariants.sql`
- Create: `services/funds-core/src/test/java/com/corebanking/funds/infrastructure/postgres/LedgerConstraintIT.java`

**Interfaces:**
- Consumes: V001 reference tables.
- Produces: immutable journals/postings, per-account materialised balances, transactional idempotency and retained outbox facts.

- [ ] **Step 1: Write failing database-invariant tests**

Tests use JDBC directly to prove PostgreSQL rejects:

- an unbalanced journal at commit;
- postings whose account currency differs;
- posting into an account in another book;
- update or delete of committed journal/posting;
- duplicate command ID with a different request hash;
- duplicate account sequence;
- a `COMPLETED` idempotency row without result JSON and journal ID.

Also prove a balanced journal, two postings, materialised balances, completed idempotency row and outbox event can commit together.

- [ ] **Step 2: Run tests to verify failure**

Run: `cd services/funds-core && ./mvnw -Dtest=LedgerConstraintIT test`

Expected: FAIL because ledger tables and triggers do not exist.

- [ ] **Step 3: Implement V002 tables**

Create:

```sql
CREATE TABLE funds.idempotency_command (
    command_id uuid PRIMARY KEY,
    request_hash char(64) NOT NULL,
    state text NOT NULL CHECK (state IN ('IN_PROGRESS','COMPLETED')),
    journal_id uuid,
    result_json jsonb,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    CHECK ((state = 'IN_PROGRESS' AND journal_id IS NULL AND result_json IS NULL AND completed_at IS NULL)
        OR (state = 'COMPLETED' AND journal_id IS NOT NULL AND result_json IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE TABLE funds.journal (
    journal_id uuid PRIMARY KEY,
    journal_sequence bigserial UNIQUE NOT NULL,
    command_id uuid UNIQUE NOT NULL REFERENCES funds.idempotency_command(command_id),
    correlation_id uuid NOT NULL,
    business_transaction_id uuid NOT NULL,
    legal_entity_id uuid NOT NULL,
    book_id uuid NOT NULL REFERENCES funds.book(book_id),
    period_id uuid NOT NULL REFERENCES funds.accounting_period(period_id),
    transaction_type text NOT NULL,
    narration text NOT NULL CHECK (octet_length(narration) <= 512),
    booking_time timestamptz NOT NULL,
    value_date date NOT NULL,
    reversal_of_journal_id uuid REFERENCES funds.journal(journal_id),
    policy_version integer NOT NULL CHECK (policy_version > 0),
    canonical_hash char(64) NOT NULL
);

CREATE TABLE funds.posting (
    posting_id uuid PRIMARY KEY,
    journal_id uuid NOT NULL REFERENCES funds.journal(journal_id),
    account_id uuid NOT NULL REFERENCES funds.ledger_account(account_id),
    currency char(3) NOT NULL,
    signed_minor_units bigint NOT NULL CHECK (signed_minor_units <> 0),
    account_sequence bigint NOT NULL CHECK (account_sequence > 0),
    dimensions jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (account_id, account_sequence)
);

CREATE TABLE funds.materialised_balance (
    account_id uuid PRIMARY KEY REFERENCES funds.ledger_account(account_id),
    signed_posting_total bigint NOT NULL DEFAULT 0,
    latest_account_sequence bigint NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0
);

CREATE TABLE funds.control_account_projection (
    book_id uuid NOT NULL REFERENCES funds.book(book_id),
    control_account_code text NOT NULL,
    currency char(3) NOT NULL,
    signed_posting_total bigint NOT NULL DEFAULT 0,
    latest_journal_sequence bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (book_id, control_account_code, currency)
);

CREATE TABLE funds.outbox_event (
    event_id uuid PRIMARY KEY,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL,
    event_type text NOT NULL,
    schema_version integer NOT NULL CHECK (schema_version > 0),
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    published_at timestamptz,
    publish_attempts integer NOT NULL DEFAULT 0,
    UNIQUE (aggregate_id, aggregate_version, event_type)
);

ALTER TABLE funds.idempotency_command
    ADD CONSTRAINT fk_idempotency_completed_journal
    FOREIGN KEY (journal_id) REFERENCES funds.journal(journal_id)
    DEFERRABLE INITIALLY DEFERRED;
```

Add foreign-key validation triggers that compare journal legal entity/book, account book and posting/account currency. Index journal sequence, business transaction, account postings and unpublished outbox creation time. `control_account_projection` is intentionally independent of `materialised_balance`: the posting transaction updates it directly from posting lines and immutable account-to-control mappings, so later proof code compares two separately persisted derivations.

- [ ] **Step 4: Implement V003 deferred balance and immutability triggers**

The deferred constraint function computes:

```sql
SELECT currency, sum(signed_minor_units::numeric)
FROM funds.posting
WHERE journal_id = checked_journal_id
GROUP BY currency
HAVING sum(signed_minor_units::numeric) <> 0;
```

Raise SQLSTATE `23514` when any row is returned. Attach it as a `DEFERRABLE INITIALLY DEFERRED` constraint trigger. Add a `BEFORE UPDATE OR DELETE` trigger on journal and posting that raises SQLSTATE `55000`. Application roles must not own these functions.

- [ ] **Step 5: Run constraint tests**

Run: `cd services/funds-core && ./mvnw -Dtest=LedgerConstraintIT test`

Expected: PASS, including rejection at transaction commit rather than first posting insert.

- [ ] **Step 6: Commit**

```bash
git add services/funds-core/src/main/resources/db/migration/V002__journal_and_outbox.sql services/funds-core/src/main/resources/db/migration/V003__ledger_invariants.sql services/funds-core/src/test
git commit -m "feat(funds-core): enforce immutable balanced journals"
```

---

### Task 6: Implement the serializable posting transaction and idempotency state machine

**Files:**
- Create: `services/funds-core/src/main/java/com/corebanking/funds/application/PostingCommand.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/application/PostingResult.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/application/PostingService.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/exception/AccountingPeriodClosedException.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/exception/IdempotencyConflictException.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/exception/LedgerPersistenceException.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/infrastructure/postgres/LedgerRepository.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/infrastructure/postgres/JdbcLedgerRepository.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/infrastructure/postgres/PostgresRetryPolicy.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/infrastructure/postgres/SqlState.java`
- Test: `services/funds-core/src/test/java/com/corebanking/funds/application/PostingServiceIT.java`

**Interfaces:**
- Consumes: `PostingCommand(UUID commandId, String requestHash, JournalDraft journal)`.
- Produces: `PostingResult(UUID journalId,long journalSequence,String canonicalHash)` and one atomic database effect.

- [ ] **Step 1: Write failing posting integration tests**

Test the Example A inflow: debit provider asset ₦100,000 and credit customer liability ₦100,000. Assert one journal, two postings, both materialised totals, one completed idempotency row and one outbox row.

Add tests for:

- same command and same hash returns the stored result;
- same command and different hash throws `IdempotencyConflictException`;
- closed period throws `AccountingPeriodClosedException` with no rows committed;
- account currency mismatch rolls back every row;
- `Long.MAX_VALUE + 1` materialised balance change raises `MonetaryOverflowException` and rolls back.

- [ ] **Step 2: Run tests to verify failure**

Run: `cd services/funds-core && ./mvnw -Dtest=PostingServiceIT test`

Expected: FAIL because posting service and repositories do not exist.

- [ ] **Step 3: Define repository boundary**

```java
public interface LedgerRepository {
    PostingResult post(Connection connection, PostingCommand command);
    Optional<PostingResult> findCompleted(Connection connection, UUID commandId, String requestHash);
}
```

`JdbcLedgerRepository.post` executes in this order: insert idempotency row with `ON CONFLICT DO NOTHING`; select it `FOR UPDATE`; compare hash; return completed result when present; validate open period and chart; sort account IDs and lock materialised balance/account rows; assign account sequences; validate the `JournalDraft`; insert journal and postings; update balances and the control-account projection with `Math.addExact`; insert outbox; update idempotency to completed; return result.

- [ ] **Step 4: Implement transaction and bounded retry**

```java
@ApplicationScoped
public class PostingService {
    private final AgroalDataSource dataSource;
    private final LedgerRepository repository;
    private final PostgresRetryPolicy retryPolicy;

    public PostingResult post(PostingCommand command) {
        return retryPolicy.execute(command.commandId(), () -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                try {
                    PostingResult result = repository.post(connection, command);
                    connection.commit();
                    return result;
                } catch (SQLException failure) {
                    rollback(connection, failure);
                    throw new LedgerPersistenceException(failure);
                } catch (RuntimeException failure) {
                    rollback(connection, failure);
                    throw failure;
                }
            } catch (SQLException connectionFailure) {
                throw new LedgerPersistenceException(connectionFailure);
            }
        });
    }

    private static void rollback(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
```

`LedgerPersistenceException` preserves the `SQLException` as its cause. `PostgresRetryPolicy` walks the cause chain, retries only SQLSTATE `40001` and `40P01`, at most five attempts, and uses injectable jitter so tests do not sleep. Every retry uses the same command ID and hash. Constraint, validation and idempotency conflicts are never retried.

- [ ] **Step 5: Run posting tests**

Run: `cd services/funds-core && ./mvnw -Dtest=PostingServiceIT test`

Expected: PASS with the Example A balances and exact row counts.

- [ ] **Step 6: Commit**

```bash
git add services/funds-core/src/main/java services/funds-core/src/test
git commit -m "feat(funds-core): post journals atomically"
```

---

### Task 7: Prove concurrent idempotency and account locking

**Files:**
- Test: `services/funds-core/src/test/java/com/corebanking/funds/application/PostingConcurrencyIT.java`
- Modify: `services/funds-core/src/main/java/com/corebanking/funds/infrastructure/postgres/JdbcLedgerRepository.java`
- Modify: `services/funds-core/src/main/java/com/corebanking/funds/infrastructure/postgres/PostgresRetryPolicy.java`

**Interfaces:**
- Consumes: `PostingService.post` from Task 6.
- Produces: verified concurrent same-key behavior, canonical account-lock ordering and bounded serialization retry.

- [ ] **Step 1: Write a failing same-key concurrency test**

Use two executors, a barrier immediately after idempotency insert and two independent database connections. Submit the same command/hash concurrently. Assert both futures return the same journal ID and the database has one journal, two postings and one event.

- [ ] **Step 2: Write a failing conflicting-hash race test**

Race the same command ID with two hashes. Assert exactly one result succeeds, one throws `IdempotencyConflictException`, and only the winning request's canonical hash is stored.

- [ ] **Step 3: Write a failing reverse-order account test**

Run 100 two-account journals where half supply accounts A/B and half B/A. Assert no unhandled deadlock, exactly 100 journals and final replayed/materialised totals agree. The repository must sort UUIDs by unsigned canonical string before locking.

- [ ] **Step 4: Run concurrency tests to verify failure**

Run: `cd services/funds-core && ./mvnw -Dtest=PostingConcurrencyIT test`

Expected: FAIL until lock ordering and waiter behavior are correct.

- [ ] **Step 5: Implement deterministic synchronization points and lock ordering**

Keep production code free of test sleeps. Inject a package-private `PostingTransactionObserver` whose default methods do nothing and whose test implementation coordinates latches after idempotency acquisition and after account locks. Sort accounts before generating `SELECT ... FOR UPDATE` statements.

- [ ] **Step 6: Run concurrency tests repeatedly**

Run:

```bash
cd services/funds-core
for run in 1 2 3 4 5; do ./mvnw -Dtest=PostingConcurrencyIT test || exit 1; done
```

Expected: five consecutive PASS runs with one effect per command.

- [ ] **Step 7: Commit**

```bash
git add services/funds-core/src/main/java services/funds-core/src/test
git commit -m "test(funds-core): prove concurrent posting safety"
```

---

### Task 8: Implement closed-period correction and exact reversal

**Files:**
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/ReversalRequest.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/application/ReversalService.java`
- Test: `services/funds-core/src/test/java/com/corebanking/funds/application/ReversalServiceIT.java`

**Interfaces:**
- Consumes: original committed journal, current open period and a new command/hash.
- Produces: one linked reversal journal containing exact negations of original postings.

- [ ] **Step 1: Write failing reversal tests**

Post Example A, close its period, open the next period, then reverse. Assert:

- reversal booking time/period belongs to the current open period;
- value-date policy is explicit in the request;
- every posting equals `Math.negateExact` of the original signed amount;
- reversal references the original journal;
- original rows are unchanged;
- reversing with the same command returns one stored result;
- reversing `Long.MIN_VALUE` is rejected as overflow;
- an ordinary new posting into the closed period remains rejected.

- [ ] **Step 2: Run tests to verify failure**

Run: `cd services/funds-core && ./mvnw -Dtest=ReversalServiceIT test`

Expected: FAIL because reversal service does not exist.

- [ ] **Step 3: Implement reversal service**

`ReversalRequest` includes new command ID/hash, original journal ID, correlation/business IDs, current open period ID, booking time, value date and reason. `ReversalService` loads the original immutable journal, builds a new `JournalDraft` with negated postings and `reversalOfJournalId`, validates it, then calls `PostingService.post`.

Do not special-case closed original periods; only the new journal's period must be open. Reject reversal of a reversal unless the active policy version explicitly supplies a distinct correction template; this slice returns `InvalidJournalException`.

- [ ] **Step 4: Run reversal and posting suites**

Run: `cd services/funds-core && ./mvnw -Dtest='ReversalServiceIT,PostingServiceIT,LedgerConstraintIT' test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/funds-core/src/main/java services/funds-core/src/test
git commit -m "feat(funds-core): add linked exact reversals"
```

---

### Task 9: Implement trial-balance and control-account proofs

**Files:**
- Create: `services/funds-core/src/main/java/com/corebanking/funds/application/proof/TrialBalanceProof.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/application/proof/ControlAccountProof.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/application/proof/AccountingProofService.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/infrastructure/postgres/JdbcAccountingProofRepository.java`
- Test: `services/funds-core/src/test/java/com/corebanking/funds/application/proof/AccountingProofServiceIT.java`

**Interfaces:**
- Consumes: immutable postings up to a journal sequence cutoff.
- Produces: `TrialBalanceProof(bookId,currency,cutoff,totalDebits,totalCredits,balanced)` and `ControlAccountProof(controlCode,currency,cutoff,sourceTotal,projectionTotal,difference)`.

- [ ] **Step 1: Write failing proof tests**

Post the inflow, intra-book transfer and reversal examples. Assert per currency that debit presentation equals credit presentation and signed sum is zero at each cutoff. Compare the customer-deposit control projection, updated atomically during posting, with a fresh aggregation from immutable postings mapped by `control_account_code`.

Corrupt only the materialised projection using an owner-only test connection; assert the proof reports the exact difference while immutable posting trial balance remains correct.

- [ ] **Step 2: Run tests to verify failure**

Run: `cd services/funds-core && ./mvnw -Dtest=AccountingProofServiceIT test`

Expected: FAIL because proof records/repository do not exist.

- [ ] **Step 3: Implement numeric proof queries**

Trial balance query uses `sum(CASE WHEN signed_minor_units > 0 THEN signed_minor_units::numeric ELSE 0 END)` and the absolute value of credits. It filters by book, currency and `journal_sequence <= cutoff`.

Control proof computes source totals from postings joined to account mapping and compares them with `funds.control_account_projection`, which is maintained in the posting write path rather than by this proof query. Convert `numeric` to `BigInteger`; reject conversion to `long` when out of range rather than truncating.

- [ ] **Step 4: Run proof tests**

Run: `cd services/funds-core && ./mvnw -Dtest=AccountingProofServiceIT test`

Expected: PASS and deliberate projection corruption produces a nonzero difference.

- [ ] **Step 5: Commit**

```bash
git add services/funds-core/src/main/java services/funds-core/src/test
git commit -m "feat(funds-core): prove trial and control balances"
```

---

### Task 10: Enforce least-privilege database roles

**Files:**
- Create: `services/funds-core/src/main/resources/db/migration/V004__application_roles.sql`
- Modify: `services/funds-core/src/test/java/com/corebanking/funds/infrastructure/postgres/LedgerConstraintIT.java`

**Interfaces:**
- Consumes: completed schema and trigger functions.
- Produces: migration, application, proof-reader and owner-role privilege separation.

- [ ] **Step 1: Write failing privilege tests**

Connect as the application role and prove it can select/insert through the required tables but cannot:

- update/delete journal or posting;
- alter a table;
- disable a trigger;
- replace an invariant function;
- update an accounting period to `CLOSED` directly;
- mutate a completed idempotency result.

Connect as proof-reader and prove it has read-only access and cannot insert.

- [ ] **Step 2: Run tests to verify failure**

Run: `cd services/funds-core && ./mvnw -Dtest=LedgerConstraintIT#applicationRoleCannotBypassLedgerControls test`

Expected: FAIL because the roles/grants do not exist.

- [ ] **Step 3: Implement V004 privileges**

Create `funds_migrator`, `funds_app` and `funds_proof_reader` as `NOLOGIN` roles; deployment-specific login roles inherit them. Revoke public schema/function/table privileges. Grant `funds_app` only required DML and sequence usage. Do not grant trigger, DDL or function ownership. Grant proof reader `SELECT` only. Period close is exposed through a subsequent privileged command plan, so `funds_app` cannot update period status in this slice.

- [ ] **Step 4: Run privilege and invariant tests**

Run: `cd services/funds-core && ./mvnw -Dtest='LedgerConstraintIT,MigrationIT' test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/funds-core/src/main/resources/db/migration/V004__application_roles.sql services/funds-core/src/test
git commit -m "security(funds-core): enforce ledger database roles"
```

---

### Task 11: Add memory-bounded configuration and complete the accounting-kernel gate

**Files:**
- Modify: `services/funds-core/src/main/resources/application.properties`
- Create: `services/funds-core/docs/health-contract.md`
- Create: `services/funds-core/Dockerfile.jvm`
- Create: `services/funds-core/README.md`
- Modify: all accounting-kernel tests where coverage gaps remain.

**Interfaces:**
- Consumes: all prior tasks.
- Produces: a reproducible JVM container, bounded datasource/executors and a documented local verification command.

- [ ] **Step 1: Add bounded production configuration**

Use mounted secret/config inputs and these defaults:

```properties
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.min-size=2
quarkus.datasource.jdbc.max-size=8
quarkus.datasource.jdbc.acquisition-timeout=5S
quarkus.datasource.jdbc.leak-detection-interval=30S
quarkus.flyway.migrate-at-start=false
quarkus.http.limits.max-body-size=128K
quarkus.micrometer.export.prometheus.enabled=true
```

The migration job runs separately with `funds_migrator`; replicas use `funds_app`. No application cache stores balances or journals.

- [ ] **Step 2: Create the JVM container**

```dockerfile
FROM eclipse-temurin:25-jre
WORKDIR /work
COPY target/quarkus-app/lib/ /work/lib/
COPY target/quarkus-app/*.jar /work/
COPY target/quarkus-app/app/ /work/app/
COPY target/quarkus-app/quarkus/ /work/quarkus/
USER 10001
ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx384m -XX:MaxMetaspaceSize=96m -XX:MaxDirectMemorySize=64m -Xss512k -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java","-jar","/work/quarkus-run.jar"]
```

Do not enable heap dumps by default. The Compose profile applies the 640 MiB memory, 0.60 CPU and 256 PID limits in the dedicated deployment-profile plan.

- [ ] **Step 3: Run the complete test gate**

Run:

```bash
cd services/funds-core
./mvnw clean verify
```

Expected: all unit, generated-property and PostgreSQL integration tests pass; no skipped accounting tests; Flyway validates all four migrations.

- [ ] **Step 4: Run a clean package and container smoke test**

Run:

```bash
cd services/funds-core
./mvnw -DskipTests package
docker build -f Dockerfile.jvm -t core-banking/funds-core:accounting-kernel .
docker run --rm --entrypoint java --memory=640m --cpus=0.60 --pids-limit=256 core-banking/funds-core:accounting-kernel -version
```

Expected: build succeeds; container reports Java 25 and stays within the declared runtime constraints. The full service startup test requires the profile PostgreSQL and is performed in the deployment-profile plan.

- [ ] **Step 5: Document the slice and map acceptance coverage**

`README.md` must list:

- positive-debit/negative-credit convention;
- exact money and overflow policy;
- migration and application role separation;
- local test commands;
- JVM memory flags;
- implemented acceptance coverage: ACC-01, the accounting portion of ACC-02, ACC-19, ACC-20, ACC-24, ACC-25 configuration inputs, ACC-29 Java fixture prerequisites and ACC-32;
- explicit exclusions: holds, Go contracts, event relay, providers, reconciliation, FX execution, security UI and full 8 GiB orchestration.

- [ ] **Step 6: Commit**

```bash
git add services/funds-core
git commit -m "docs(funds-core): complete accounting kernel slice"
```

---

## Final Slice Verification

Run from repository root:

```bash
cd services/funds-core
./mvnw clean verify
git diff --check
```

Expected results:

- Maven exits zero with no failed or skipped accounting tests.
- Generated-property tests report the fixed seed and failing case in assertion output when applicable.
- PostgreSQL integration tests prove commit-time balance, immutability, role and period controls.
- Concurrent tests pass five consecutive runs.
- `git diff --check` prints nothing.

Before moving to the funds-control plan, request an independent review against:

- architecture §§4–5, 8.1–8.3, 8.9–8.13, 9.1–9.2, 13.9, 16.1, 21.8–21.11 and 23;
- this plan's interfaces and exclusions;
- the exact base-to-head Git range for the accounting-kernel slice.
