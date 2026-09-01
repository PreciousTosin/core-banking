# Accounting Kernel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Java `funds-core` accounting kernel that owns books, chart-of-accounts reference data, immutable account-address and product-version foundations, immutable balanced journals, checked monetary arithmetic, idempotent posting, materialised balances, outbox facts, accounting periods, reversals, trial-balance proof and subledger/control-account proof.

**Architecture:** Implement one Quarkus JVM service around an explicit PostgreSQL transaction boundary. Domain types validate money and journal equations before persistence; PostgreSQL independently enforces per-currency balance, immutability, account/currency compatibility and period state. The posting service locks accounts canonically, resolves idempotency inside the money transaction, writes journal/postings/balances/outbox atomically and exposes proof queries without introducing provider, hold or workflow concerns.

**Tech Stack:** Java 25 LTS; Quarkus 3.33.3.1 LTS; Maven 3.9.16 wrapper; PostgreSQL 18.6; JDBC/Agroal; Flyway; JUnit 6 on the Quarkus-managed platform; a deterministic in-repository property-case generator; Testcontainers through Quarkus Dev Services.

**Spec:** `architecture/modern-core-banking-comprehensive-design-revised.md`

## Global Constraints

- `funds-core` is the only writer of accounts, journals, postings, materialised balances and its money-event outbox.
- Positive signed posting amounts are debits; negative signed posting amounts are credits.
- Posted amounts are checked signed 64-bit integer minor units. Java arithmetic uses `Math.addExact`, `Math.subtractExact`, `Math.multiplyExact` and `Math.negateExact`.
- Every journal belongs to one legal entity and one book and balances to zero independently per currency.
- Every account has exactly one currency, account class and normal direction.
- The ledger-account UUID is the financial identity. NUBAN and provider virtual accounts are non-financial addresses and never appear as posting account IDs or transaction idempotency keys.
- One account may have several provider aliases; an active scoped identifier resolves to one account, and one active primary NUBAN is allowed per externally addressable customer account.
- Nigeria has no ISO IBAN format in the cited registry. Do not generate a Nigerian IBAN.
- The deterministic PoC default is institution code `000000`, NUBAN `0000000017`, `SIMULATOR_ONLY`; production adapters must reject it.
- Every customer account references an immutable product version and finance principle; non-customer control accounts do not require a customer product.
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

1. This plan: accounting kernel plus account-identifier/product-version reference foundations.
2. Funds control: holds, restrictions, provider-float reservation and multi-replica concurrency.
3. Java/Go Protobuf contracts and canonical hashing fixtures.
4. `2026-08-30-account-identifiers-and-nip-inbound-implementation.md`: account details, multi-provider aliases and simulated NIP inbound.
5. Transactional outbox relay, Redpanda transport and Go projections.
6. Go orchestration, provider simulator, durable submission intent and outbound payout.
7. Provider routing, capability/settlement registry and resilience state.
8. `2026-08-30-conventional-deposit-products-and-accrual-implementation.md`: savings/current/fixed-deposit rules and accrual lifecycle.
9. `2026-08-30-non-interest-banking-products-implementation.md`: non-interest governance, pools and allocation.
10. Reconciliation, source manifests, suspense and daily proof.
11. Multi-currency FX execution and paired-journal workflows.
12. Identity, privileged controls, audit anchoring, backup and restore.
13. Exact 8 GiB Compose profiles, resource fault injection and acceptance evidence.

This plan does not issue provider aliases, simulate NIP, calculate interest/profit, create holds, call providers, start Temporal, publish to Redpanda or implement customer/channel APIs. It creates the validation types and database foundations needed by the three named follow-on plans. It does create durable outbox rows because a committed money fact without its event violates the accounting boundary.

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
│   │   ├── AccountIdentifier.java
│   │   ├── AccountIdentifierScheme.java
│   │   ├── Book.java
│   │   ├── CurrencyCode.java
│   │   ├── DepositProductKind.java
│   │   ├── FinancePrinciple.java
│   │   ├── JournalDraft.java
│   │   ├── LedgerAccount.java
│   │   ├── Money.java
│   │   ├── NormalBalance.java
│   │   ├── PostingLine.java
│   │   ├── ProductDefinition.java
│   │   ├── ProductVersion.java
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
│   │   ├── PostingTransactionObserver.java
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
│   ├── domain/NubanTest.java
│   ├── application/JournalValidatorTest.java
│   ├── application/JournalProperties.java
│   ├── testsupport/PropertyCases.java
│   ├── application/PostingServiceIT.java
│   ├── application/PostingConcurrencyIT.java
│   ├── application/PostingAtomicityIT.java
│   ├── application/PostingCrashRecoveryIT.java
│   ├── application/CrashPostingWorker.java
│   ├── application/TestPostingStack.java
│   ├── application/AccountingStateMachineIT.java
│   ├── application/ReversalServiceIT.java
│   ├── application/proof/AccountingProofServiceIT.java
│   ├── testsupport/GeneratedLedgerOperation.java
│   ├── testsupport/ReferenceLedgerModel.java
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
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/AccountIdentifier.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/AccountIdentifierScheme.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/DepositProductKind.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/FinancePrinciple.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/ProductDefinition.java`
- Create: `services/funds-core/src/main/java/com/corebanking/funds/domain/ProductVersion.java`
- Modify: `services/funds-core/src/test/java/com/corebanking/funds/domain/MoneyTest.java`
- Test: `services/funds-core/src/test/java/com/corebanking/funds/domain/NubanTest.java`

**Interfaces:**
- Consumes: `CurrencyCode`, signed posting totals.
- Produces: `NormalBalance.toNatural(long)`, `LedgerAccount.bookedNaturalBalance(long)`, NUBAN validation/check-digit functions and immutable product/address reference records.

- [ ] **Step 1: Write failing normal-direction tests**

```java
@Test void rendersDebitAndCreditNormalBalances() {
    assertEquals(10_000, NormalBalance.DEBIT.toNatural(10_000));
    assertEquals(10_000, NormalBalance.CREDIT.toNatural(-10_000));
    assertEquals(-2_000, NormalBalance.CREDIT.toNatural(2_000));
}
```

Add construction tests proving that `Book` requires legal entity, functional currency, timezone, calendar and policy version, and `LedgerAccount` requires book, currency, class, normal direction, control-account code and non-null status.

In `NubanTest`, use published-algorithm fixtures and generated nine-digit serials to prove that the tenth digit validates against a six-digit institution code, a one-digit mutation fails, non-digits fail, and the synthetic pair `000000`/`0000000017` validates. Test that `AccountIdentifier` rejects a NUBAN without institution code, rejects provider virtual accounts without provider ID, and never exposes a money/balance field.

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
enum AccountIdentifierScheme { NUBAN, PROVIDER_VIRTUAL_ACCOUNT, IBAN }
enum DepositProductKind { SAVINGS, CURRENT, FIXED_DEPOSIT, DOMICILIARY }
enum FinancePrinciple { CONVENTIONAL, NON_INTEREST }
```

Implement `Book`, `LedgerAccount`, `ProductDefinition`, `ProductVersion` and `AccountIdentifier` as records with UUID identifiers and constructor validation. `LedgerAccount` exposes `bookedNaturalBalance(long signedTotal)` by delegating to its `NormalBalance`. `ProductVersion` is immutable and carries product kind, finance principle, effective interval, approval reference and opaque versioned-policy JSON hash; calculation fields arrive in the follow-on product plans. `AccountIdentifier` carries scheme, normalised value, institution/provider scope, lifecycle, primary flag and routing scope, but no balance.

Implement NUBAN check-digit calculation as a pure function over the six-digit institution code plus nine-digit serial using the CBN weights. Keep `IBAN` in the extensible enum but reject it until a country-specific validator exists; specifically do not treat `NG` plus a NUBAN as an IBAN.

- [ ] **Step 4: Run domain tests**

Run: `cd services/funds-core && ./mvnw -Dtest='MoneyTest,NubanTest' test`

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
- Produces: `funds.book`, `funds.chart_version`, `funds.accounting_period`, `funds.product_definition`, `funds.product_version`, `funds.ledger_account`, `funds.account_identifier` and controlled enum/check-digit checks.

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

Use `@QuarkusTest` and injected `AgroalDataSource`. Query `information_schema.tables` and assert all seven tables exist. Attempt to insert an account with an invalid currency length, invalid normal direction and missing book; each insert must fail. Also prove a customer account without a product version fails, the same scoped active identifier cannot map to two accounts, a second active primary NUBAN for one account fails, multiple provider aliases from different providers succeed, a bad NUBAN check digit fails and `SIMULATOR_ONLY` is retained as data rather than becoming routable.

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

CREATE TABLE funds.product_definition (
    product_id uuid PRIMARY KEY,
    product_code text NOT NULL UNIQUE,
    product_kind text NOT NULL CHECK (product_kind IN ('SAVINGS','CURRENT','FIXED_DEPOSIT','DOMICILIARY')),
    finance_principle text NOT NULL CHECK (finance_principle IN ('CONVENTIONAL','NON_INTEREST'))
);

CREATE TABLE funds.product_version (
    product_version_id uuid PRIMARY KEY,
    product_id uuid NOT NULL REFERENCES funds.product_definition(product_id),
    version integer NOT NULL CHECK (version > 0),
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    approval_reference text NOT NULL,
    policy_hash char(64) NOT NULL,
    policy_json jsonb NOT NULL,
    UNIQUE (product_id, version),
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE TABLE funds.ledger_account (
    account_id uuid PRIMARY KEY,
    book_id uuid NOT NULL REFERENCES funds.book(book_id),
    chart_version_id uuid NOT NULL REFERENCES funds.chart_version(chart_version_id),
    account_code text NOT NULL,
    account_scope text NOT NULL CHECK (account_scope IN ('CUSTOMER','CONTROL','INTERNAL')),
    product_version_id uuid REFERENCES funds.product_version(product_version_id),
    account_class text NOT NULL CHECK (account_class IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
    normal_balance text NOT NULL CHECK (normal_balance IN ('DEBIT','CREDIT')),
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    control_account_code text NOT NULL,
    status text NOT NULL CHECK (status IN ('OPEN','DEBIT_BLOCKED','CREDIT_BLOCKED','CLOSED')),
    authorised_floor_minor bigint NOT NULL DEFAULT 0 CHECK (authorised_floor_minor <= 0),
    created_at timestamptz NOT NULL,
    closed_at timestamptz,
    UNIQUE (book_id, account_code, currency),
    CHECK ((account_scope = 'CUSTOMER' AND product_version_id IS NOT NULL)
        OR (account_scope <> 'CUSTOMER' AND product_version_id IS NULL))
);

CREATE TABLE funds.account_identifier (
    account_identifier_id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES funds.ledger_account(account_id),
    scheme text NOT NULL CHECK (scheme IN ('NUBAN','PROVIDER_VIRTUAL_ACCOUNT','IBAN')),
    normalised_value text NOT NULL,
    institution_code char(6),
    provider_id uuid,
    purpose_code text,
    routing_scope text NOT NULL CHECK (routing_scope IN ('SIMULATOR_ONLY','INTERNAL','EXTERNAL')),
    lifecycle_status text NOT NULL CHECK (lifecycle_status IN ('PENDING','ACTIVE','RETIRED','REVOKED')),
    is_primary boolean NOT NULL DEFAULT false,
    valid_from timestamptz NOT NULL,
    valid_to timestamptz,
    issuance_evidence_hash char(64) NOT NULL,
    CHECK (valid_to IS NULL OR valid_to > valid_from),
    CHECK ((scheme = 'NUBAN' AND institution_code IS NOT NULL AND provider_id IS NULL
            AND normalised_value ~ '^[0-9]{10}$')
        OR (scheme = 'PROVIDER_VIRTUAL_ACCOUNT' AND provider_id IS NOT NULL)
        OR (scheme = 'IBAN' AND false))
);
```

Enable `btree_gist` before the exclusion constraint. Add indexes on period lookup, account book/status and identifier resolution. Add an immutable SQL NUBAN validator used by a table check. Add a unique expression index for active scoped identifiers over `(scheme, coalesce(institution_code,''), coalesce(provider_id::text,''), normalised_value)` and a partial unique index enforcing one active primary NUBAN per account. Prevent external identifiers on non-customer accounts. Do not permit update/delete to rewrite identifier history; lifecycle changes append a successor/audit fact in the follow-on plan. Seed the synthetic `000000`/`0000000017` fixture only from tests, never from the production migration.

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

<a id="posting-command-delivery-detail"></a>
<!-- migration-source: 09.01 -->
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
    private final DataSource dataSource;
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

Quarkus injects its Agroal-backed implementation through the standard `javax.sql.DataSource` interface; the child-process crash harness can therefore supply a `PGSimpleDataSource` without replacing the repository or transaction code. `LedgerPersistenceException` preserves the `SQLException` as its cause. `PostgresRetryPolicy` walks the cause chain, retries only SQLSTATE `40001` and `40P01`, at most five attempts, and uses injectable jitter so tests do not sleep. Every retry uses the same command ID and hash. Constraint, validation and idempotency conflicts are never retried.

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
- Create: `services/funds-core/src/main/java/com/corebanking/funds/application/PostingTransactionObserver.java`
- Modify: `services/funds-core/src/main/java/com/corebanking/funds/application/PostingService.java`
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

Keep production code free of test sleeps. Define `PostingTransactionObserver` as a public internal SPI because both the application transaction coordinator and PostgreSQL repository invoke it; inject a no-op production bean while tests provide deterministic observers. Define these exact callbacks: `afterIdempotencyAcquired(UUID)`, `afterAccountLocks(UUID)`, `afterFinancialRowsBeforeOutbox(UUID)`, `beforeCommit(UUID)` and `afterCommitBeforeReturn(UUID)`, plus `static PostingTransactionObserver noop()`. Task 7 uses the first two; Task 8 uses the remaining callbacks for deterministic failure and crash placement. Sort accounts before generating `SELECT ... FOR UPDATE` statements.

```java
public interface PostingTransactionObserver {
    default void afterIdempotencyAcquired(UUID commandId) {}
    default void afterAccountLocks(UUID commandId) {}
    default void afterFinancialRowsBeforeOutbox(UUID commandId) {}
    default void beforeCommit(UUID commandId) {}
    default void afterCommitBeforeReturn(UUID commandId) {}

    static PostingTransactionObserver noop() {
        return new PostingTransactionObserver() {};
    }
}

@ApplicationScoped
final class NoOpPostingTransactionObserver implements PostingTransactionObserver {}
```

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

### Task 8: Prove atomic rollback and idempotent recovery across process termination

**Files:**
- Create: `services/funds-core/src/test/java/com/corebanking/funds/application/PostingAtomicityIT.java`
- Create: `services/funds-core/src/test/java/com/corebanking/funds/application/PostingCrashRecoveryIT.java`
- Create: `services/funds-core/src/test/java/com/corebanking/funds/application/CrashPostingWorker.java`
- Create: `services/funds-core/src/test/java/com/corebanking/funds/application/TestPostingStack.java`
- Modify: `services/funds-core/src/main/java/com/corebanking/funds/application/PostingService.java`
- Modify: `services/funds-core/src/main/java/com/corebanking/funds/application/PostingTransactionObserver.java`
- Modify: `services/funds-core/src/main/java/com/corebanking/funds/infrastructure/postgres/JdbcLedgerRepository.java`

**Interfaces:**
- Consumes: `PostingService.post`, the five `PostingTransactionObserver` callbacks from Task 7 and the PostgreSQL datasource exposed by Quarkus Dev Services.
- Produces: direct evidence that a pre-outbox failure rolls back every financial row, a process death before commit elects a new idempotency owner and a process death after commit returns the stored result without reposting.

- [ ] **Step 1: Write the failing pre-outbox atomicity test**

Construct `PostingService` with an observer whose `afterFinancialRowsBeforeOutbox(commandId)` throws `InjectedPostingFailure`. Snapshot the relevant rows, submit a balanced two-line command and assert that journal, posting, materialised-balance, control-projection, idempotency and outbox state is byte-for-byte unchanged. Submit the same command/hash through the no-op observer and assert exactly one journal, two postings, the two expected balance deltas, one completed idempotency result and one outbox event.

```java
@Test
void failureAfterFinancialRowsButBeforeOutboxRollsBackEverything() {
    var command = CrashPostingWorker.command(COMMAND_ID);
    LedgerSnapshot before = rows.snapshot(COMMAND_ID, Set.of(PROVIDER_ASSET, CUSTOMER_LIABILITY));
    var failing = TestPostingStack.create(dataSource, new PostingTransactionObserver() {
        @Override public void afterFinancialRowsBeforeOutbox(UUID commandId) {
            throw new InjectedPostingFailure(commandId);
        }
    });

    assertThrows(InjectedPostingFailure.class, () -> failing.postingService().post(command));
    assertEquals(before, rows.snapshot(COMMAND_ID, Set.of(PROVIDER_ASSET, CUSTOMER_LIABILITY)));

    PostingResult recovered = TestPostingStack.create(dataSource, PostingTransactionObserver.noop())
        .postingService().post(command);
    assertEquals(1, rows.journalCount(COMMAND_ID));
    assertEquals(2, rows.postingCount(recovered.journalId()));
    assertEquals(Map.of(PROVIDER_ASSET, 100_000L, CUSTOMER_LIABILITY, -100_000L),
        rows.balanceDeltasSince(before));
    assertEquals(1, rows.controlProjectionDeltaCountSince(before));
    assertEquals(1, rows.completedIdempotencyCount(COMMAND_ID));
    assertEquals(1, rows.outboxCount(recovered.journalId()));
    assertEquals(COMMAND_ID, rows.commandFor(recovered.journalId()));
}
```

Define `InjectedPostingFailure`, an immutable `LedgerSnapshot` containing account totals/versions and control-projection totals, and a JDBC-backed `RowProbe rows` as private nested test helpers in `PostingAtomicityIT`. `RowProbe` filters through the command ID, journal ID and fixture account IDs so unrelated seed data is excluded. Snapshot equality after the injected failure proves both newly inserted rows and updates to pre-existing balance rows rolled back.

- [ ] **Step 2: Run the atomicity test to verify failure**

Run: `cd services/funds-core && ./mvnw -Dtest=PostingAtomicityIT test`

Expected: FAIL until the repository calls the observer after journal, posting, balance and control-projection writes but before the outbox insert, and the transaction coordinator rolls the exception back.

- [ ] **Step 3: Wire the pre-outbox callback inside the transaction**

Call `observer.afterFinancialRowsBeforeOutbox(command.commandId())` immediately before `insertOutbox`. Although the Java SPI is public so the repository can invoke it, only the no-op CDI bean is packaged as an injectable production implementation. Do not read a system property, environment variable or request field to select a failure point, so remote callers cannot activate the test seam in a deployed service.

- [ ] **Step 4: Write failing child-process crash tests for both commit boundaries**

`CrashPostingWorker` accepts `commandId` and one of `BEFORE_COMMIT` or `AFTER_COMMIT_BEFORE_RETURN`; it reads the synthetic-test connection values from `CB_TEST_JDBC_URL`, `CB_TEST_DB_USER` and `CB_TEST_DB_PASSWORD` so credentials do not appear in the process argument list. It builds the same deterministic inflow command as the parent through `TestPostingStack`, prints `REACHED:<point>` and flushes stdout, then invokes `Runtime.getRuntime().halt(91)` from the corresponding observer callback. It must not catch the halt or send a synthetic result.

Launch the worker with the Maven test classpath:

```java
private Process startWorker(CrashPoint point, UUID commandId) throws IOException {
    var builder = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("surefire.test.class.path"),
        CrashPostingWorker.class.getName(),
        commandId.toString(), point.name())
        .redirectErrorStream(true);
    builder.environment().put("CB_TEST_JDBC_URL", jdbcUrl);
    builder.environment().put("CB_TEST_DB_USER", username);
    builder.environment().put("CB_TEST_DB_PASSWORD", password);
    return builder.start();
}
```

For `BEFORE_COMMIT`, wait for exit code `91`, retry the identical command/hash in the parent and assert one completed idempotency row, one journal, two postings and one outbox event. For `AFTER_COMMIT_BEFORE_RETURN`, first assert the committed row becomes visible, wait for exit code `91`, retry and assert the returned journal ID equals the already committed journal and every row count remains unchanged. Each wait uses a ten-second timeout and forcibly terminates the child in `finally` if it has not exited.

- [ ] **Step 5: Run crash tests to verify failure**

Run: `cd services/funds-core && ./mvnw -Dtest=PostingCrashRecoveryIT test`

Expected: FAIL until `PostingService` invokes `beforeCommit(commandId)` immediately before `connection.commit()` and `afterCommitBeforeReturn(commandId)` immediately after a successful commit but before returning the result.

- [ ] **Step 6: Implement the test stack and commit-boundary callbacks**

`TestPostingStack.create(PGSimpleDataSource, PostingTransactionObserver)` constructs the real `JdbcLedgerRepository`, `JournalValidator`, `CanonicalJournalHasher`, `PostgresRetryPolicy` and `PostingService`; it must not replace persistence with mocks. `CrashPostingWorker.command(UUID)` returns the fixed legal entity, book, period, provider-asset account and customer-liability account command seeded by the integration-test fixture. The parent inserts that reference data before launching the child.

Place `beforeCommit` after all repository work and immediately before JDBC commit. Place `afterCommitBeforeReturn` after JDBC commit. A normal exception before commit rolls back; an actual `halt(91)` proves PostgreSQL rolls back the abandoned connection. After-commit recovery relies only on the completed idempotency record, not an in-memory response cache.

- [ ] **Step 7: Run the complete failure-boundary suite repeatedly**

Run:

```bash
cd services/funds-core
for run in 1 2 3 4 5; do
  ./mvnw -Dtest='PostingAtomicityIT,PostingCrashRecoveryIT' test || exit 1
done
```

Expected: five consecutive passes. Every pre-commit termination leaves zero effect before retry; every post-commit termination leaves exactly one recoverable effect.

- [ ] **Step 8: Commit**

```bash
git add services/funds-core/src/main/java services/funds-core/src/test
git commit -m "test(funds-core): prove posting crash recovery"
```

---

### Task 9: Generate stateful accounting sequences against PostgreSQL

**Files:**
- Create: `services/funds-core/src/test/java/com/corebanking/funds/application/AccountingStateMachineIT.java`
- Create: `services/funds-core/src/test/java/com/corebanking/funds/testsupport/GeneratedLedgerOperation.java`
- Create: `services/funds-core/src/test/java/com/corebanking/funds/testsupport/ReferenceLedgerModel.java`
- Modify: `services/funds-core/src/test/java/com/corebanking/funds/testsupport/PropertyCases.java`

**Interfaces:**
- Consumes: real `PostingService` from Task 6, failure seams from Tasks 7–8 and direct JDBC queries against the schema from Tasks 4–5.
- Produces: reproducible generated post/retry/conflict/reverse/reject histories whose reference-model balances, database replay, materialised balances, control projections, idempotency results and outbox cardinality agree after every operation.

- [ ] **Step 1: Define generated operations and an independent reference model**

Use this closed operation set:

```java
public sealed interface GeneratedLedgerOperation {
    record Post(UUID commandId, long amount) implements GeneratedLedgerOperation {}
    record RetrySame(UUID commandId) implements GeneratedLedgerOperation {}
    record RetryDifferentHash(UUID commandId) implements GeneratedLedgerOperation {}
    record Reverse(UUID commandId, UUID originalJournalId) implements GeneratedLedgerOperation {}
    record SubmitUnbalanced(UUID commandId, long debit, long credit) implements GeneratedLedgerOperation {}
}
```

`ReferenceLedgerModel` stores account totals in `Map<UUID,BigInteger>`, successful results by command ID, their request hashes, journal lines by journal ID and expected outbox IDs. Its `apply` method uses `BigInteger` so the oracle cannot reproduce a `long` overflow from production. It mutates only after the real command succeeds and predicts `InvalidJournalException`, `IdempotencyConflictException` or `MonetaryOverflowException` without querying production tables.

For a generated `Reverse`, construct a new `JournalDraft` directly from the model's original lines using `Math.negateExact`, set `reversalOfJournalId`, and submit it through the real `PostingService`. Task 10 later packages the same rule behind `ReversalService`; this test exercises the ledger invariant without depending on a later task.

- [ ] **Step 2: Write the failing seeded state-machine integration test**

Run 32 fixed seeds derived from `0xCB20260830L`; each seed generates 128 operations. Weight selection as 45% balanced post, 20% same-hash retry, 10% conflicting-hash retry, 15% reversal and 10% unbalanced submission. Generate amounts from boundary values `1`, `2`, `99`, `100`, `1_000_000_000`, `Long.MAX_VALUE / 2` plus bounded random minor units. When an operation requires an existing command or journal, select one from the reference model; if none exists, generate a balanced post instead.

After every operation, query PostgreSQL and assert:

1. every journal sums to zero independently per currency using `numeric`;
2. each materialised account total equals immutable-posting replay at the current journal cutoff;
3. each control projection equals an independent posting/account-mapping aggregation;
4. every successful journal has exactly one outbox event and one completed idempotency result;
5. same-hash retries retain the original journal ID and conflicting hashes create no additional idempotency, journal, posting or outbox row;
6. reversal lines are exact negations and original journal hashes remain unchanged.

Assertion messages contain `seed`, zero-based `operationIndex` and the complete generated prefix so a failure is reproducible without guessing.

- [ ] **Step 3: Run the state-machine test to verify failure**

Run: `cd services/funds-core && ./mvnw -Dtest=AccountingStateMachineIT test`

Expected: FAIL until operation generation, the independent model and all six database assertions are implemented.

- [ ] **Step 4: Implement deterministic generation and invariant comparison**

Use `SplittableRandom`, never wall-clock time, for operation choice, IDs and amounts. Create a separate book and account fixture per seed so sequences are isolated without truncating shared tables. Use the seed-derived fixed `bookingTime` and value date so canonical hashes are reproducible. Compare database `numeric` values with `BigInteger`; convert to `long` only when the model says the value is within the permitted range.

If a sequence fails, first rerun the exact prefix once to exclude infrastructure failure, then report the original assertion. Do not automatically discard operations and call the reduced sequence equivalent: stateful idempotency and reversal dependencies make naive shrinking unsound.

- [ ] **Step 5: Run generated sequences twice for reproducibility**

Run:

```bash
cd services/funds-core
./mvnw -Dtest=AccountingStateMachineIT test
./mvnw -Dtest=AccountingStateMachineIT test
```

Expected: both runs execute the same 4,096 operations in the same order and pass with identical final journal, idempotency and outbox counts.

- [ ] **Step 6: Commit**

```bash
git add services/funds-core/src/test
git commit -m "test(funds-core): generate accounting invariant histories"
```

---

### Task 10: Implement closed-period correction and exact reversal

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

### Task 11: Implement trial-balance and control-account proofs

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

### Task 12: Enforce least-privilege database roles

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

Connect as the external proof-job role and prove the exact trial/control queries succeed while reads of account-address identifiers, product policy JSON, idempotency results and outbox payloads fail. Prove it cannot insert or mutate any row.

- [ ] **Step 2: Run tests to verify failure**

Run: `cd services/funds-core && ./mvnw -Dtest=LedgerConstraintIT#applicationRoleCannotBypassLedgerControls test`

Expected: FAIL because the roles/grants do not exist.

- [ ] **Step 3: Implement V004 privileges**

Create `funds_migrator`, `funds_app` and `funds_proof_reader` as `NOLOGIN` roles; deployment-specific login roles inherit them. Revoke public schema/function/table privileges. Grant `funds_app` only required DML and sequence usage. Do not grant trigger, DDL or function ownership. Treat `funds_proof_reader` as an external proof-job capability, not the service datasource: grant column-level `SELECT` only for immutable journal/posting facts, governed control mappings and projection totals. Do not expose account identifiers, product policy, idempotency result JSON or outbox payloads. The control projection is current-state-only, so the plan claims only a current-cutoff control proof until projection history exists. Period close is exposed through a subsequent privileged command plan, so `funds_app` cannot update period status in this slice.

- [ ] **Step 4: Run privilege and invariant tests**

Run: `cd services/funds-core && ./mvnw -Dtest='LedgerConstraintIT,MigrationIT' test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/funds-core/src/main/resources/db/migration/V004__application_roles.sql services/funds-core/src/test
git commit -m "security(funds-core): enforce ledger database roles"
```

---

<a id="java-memory-evidence-delivery-detail"></a>
<!-- migration-source: 21.09::03 -->
### Task 13: Add memory-bounded configuration and complete the accounting-kernel gate

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
quarkus.thread-pool.core-threads=2
quarkus.thread-pool.max-threads=8
quarkus.thread-pool.queue-size=32
quarkus.thread-pool.growth-resistance=0
```

The migration job runs separately with `funds_migrator`; replicas use `funds_app`. The finite worker pool rejects a full queue before accepting more work. Every posting/reversal transaction sets local one-second lock, three-second statement and five-second idle-in-transaction deadlines before lock or statement work. Deadline outcomes are typed consistently and do not broaden the bounded serialization/deadlock retry policy. No application cache stores balances or journals.

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

Expected: all unit, generated-property, PostgreSQL integration, injected-failure and child-process crash tests pass; no skipped accounting tests; Flyway validates the eight migration resources through additive governed-rotation `V006`.

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
- identifier/address versus ledger/transaction identity, NUBAN validation and the simulator-only default;
- product-version and finance-principle foundations, including why non-interest is not a zero-rate conventional product;
- local test commands;
- JVM memory flags;
- implemented acceptance coverage: ACC-01, the accounting portion of ACC-02, ACC-19, ACC-20, ACC-24, ACC-25 configuration inputs, ACC-29 Java fixture prerequisites, ACC-32 including owner termination immediately before and after commit, the foundation/constraint portion of ACC-38 and the immutable-version portion of ACC-40/ACC-42;
- explicit exclusions: identifier issuance/resolution APIs, real or simulated NIP, account-details projection, accrual/capitalisation/maturity, non-interest allocation, holds, Go contracts, event relay, providers, reconciliation, FX execution, security UI and full 8 GiB orchestration.

- [ ] **Step 6: Commit**

```bash
git add services/funds-core
git commit -m "docs(funds-core): complete accounting kernel slice"
```

---

### Task 14: Harden governed acceptance invariants

**Files:**
- Create: `services/funds-core/src/main/resources/db/migration/V005__acceptance_hardening.sql`
- Create: `services/funds-core/src/main/resources/db/migration/V006__governed_chart_rotation.sql`
- Create/modify: command hashing, posting/reversal governance and persistence classes under `services/funds-core/src/main/java`
- Create/modify: `AcceptanceHardeningIT`, posting/reversal/concurrency/migration/role/runtime tests
- Modify: accounting-kernel architecture, README, health and role contracts

**Interfaces:**
- Consumes: the complete accounting-kernel slice and independent review findings.
- Produces: independently enforced period/chart/product/reversal invariants, trusted typed-command idempotency, finite runtime admission and exact proof-reader access.

- [ ] **Step 1: Add acceptance tests before implementation**

Prove service and direct-DML rejection for Lagos-midnight booking dates, booking/value-period divergence, wrong-book and closed periods, stale policy/chart versions and incomplete chart mappings. Repeat the posting-versus-period-close lock race five times. Add version rotation and historical-classification fixtures. Prove a partial DRAFT chart cannot activate, a complete chart can rotate safely, and mapping insert/update/delete all reject after activation. Use two-connection repeatable-read races to prove concurrent mapping deletion, open-account creation, or candidate-chart creation makes a waiting governance operation serialize instead of committing a stale completeness decision, even when the candidate did not yet exist in the other transaction's snapshot. Prove direct ACTIVE chart creation and ungoverned open-account onboarding reject.

Prove operational rotation is one atomic owner-governed operation. Race it against candidate mapping insert, update and delete in two PostgreSQL sessions for five repetitions each; require successful rotation or the named serialization/business rejection, never unhandled `40P01`, with lifecycle and completeness invariants intact. Prove direct-journal/posting governance takes chart locks before the stable book lock, and that wrong identities/books/states, non-forward versions, incomplete mappings, future/pre-activation boundaries and a boundary that would invalidate a historical journal all reject without partially retiring the current chart. Prove `funds_app` can neither update chart lifecycle columns nor execute rotation.

For posting and reversal requests, mutate every financially relevant typed field while retaining the supplied hash and require a deterministic conflict before financial work. Prove a completed same-content replay still resolves after its period closes, chart retires or policy advances. Build an authentic V004 upgrade fixture with the exact V004 journal/request semantics, including same-code NGN/USD accounts and historical postings balanced independently in both currencies. After V005, prove distinct currency-qualified mappings, unchanged V004 hash bytes, same-content typed replay, recomputed-mutation conflict and exact reversal into a current period.

Exercise the reversible envelope at 256/257 postings, 32/33 dimensions, 8,192/8,193 persisted dimension bytes and `Long.MIN_VALUE`. Require dimension JSON to be an object whose values are strings, matching the typed command contract, and prove both direct DML and a V004 upgrade containing non-string values reject. Prove generic posting rejects reversal metadata and direct DML cannot create alternate-type, duplicate or inexact reversals; prove an exact maximum-sized reversal succeeds.

- [ ] **Step 2: Implement additive governance and typed hashes**

Use additive `V005` to backfill product kind and finance principle onto immutable product versions, separate stable ledger-account identity from immutable currency-qualified per-chart mappings, pin every journal to one governed chart version, and add commit-time period/chart/reversal guards owned by `funds_migrator`. Require charts to begin in DRAFT, validate complete open-account mappings on activation and freeze mappings afterward. Live account onboarding/reopening after activation is explicitly deferred and rejected until a governed atomic workflow is delivered. Preserve historical classifications during chart and product rotation. Revoke or re-grant application capabilities so `funds_app` cannot bypass the new guards.

Use additive `V006` for operational replacement. Lock every participating current/candidate chart row in canonical UUID order and the stable book row next; then revalidate the same book, current `ACTIVE` state, forward complete `DRAFT` candidate and half-open effective boundary before retiring and activating atomically. Mapping mutation, service posting and direct-journal guards use the same chart-before-book order. Leave the operation ungranted to runtime/proof roles; initial empty-book bootstrap and exceptional repair are trusted migration-owner actions, while every active-chart replacement uses the governed operation rather than two raw lifecycle updates.

Version and domain-separate canonical encodings for posting and reversal typed commands, and persist explicit command/journal hash schemes. Keep migrated V004 bytes tagged and unchanged, verify them with the exact V004 algorithm, and compare migrated completed posting and reversal replays using typed V2 hashes reconstructed from verified journal facts rather than trusting the legacy opaque request hash. Freeze each durable scheme with a golden vector. Re-derive and verify new hashes inside the kernel, resolve completed replays before later governance validation, require a completed command/result cache to identify its own journal ID, sequence and canonical hash, and expose reversal linkage only through the trusted reversal-service path.

- [ ] **Step 3: Bound runtime and independent proof access**

Configure a 2–8 thread worker pool with a queue of 32 and deterministic rejection. Apply transaction-local 1s lock, 3s statement and 5s idle-in-transaction deadlines before database work. Map `55P03`/`57014` to a typed timeout for caller policy; do not add them to internal retry classes, which remain limited to serialization/deadlock failures.

Replace schema-wide proof grants with the exact table columns needed by the external proof job. Prove trial and current-cutoff control queries succeed while sensitive identifier, policy, idempotency and outbox columns are denied.

- [ ] **Step 4: Run focused and complete gates**

Run the combined acceptance-hardening integration gate, all concurrency cases five times where applicable, migration/role and packaging contracts, then Java 25/PostgreSQL 18.6 `./mvnw clean verify`. Require zero failures, errors, skips and warnings attributable to the accounting kernel; finish with `git diff --check` and a clean worktree.

- [ ] **Step 5: Commit thematic checkpoints**

Commit governance/hash/reversal/product/chart work separately from runtime/proof hardening when each gate is independently green. Never commit `.superpowers/sdd` evidence artifacts.

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
- Reference-data integration tests prove NUBAN check digits, scoped alias cardinality, primary-NUBAN uniqueness, product-version binding and the ban on fabricated Nigerian IBANs.
- Pre-outbox failure injection proves journal, postings, balances, control projection, idempotency and outbox roll back together.
- Child-process termination tests prove pre-commit rollback/new-owner recovery and post-commit stored-result recovery.
- Stateful PostgreSQL generation executes 4,096 post/retry/conflict/reverse/reject operations and checks six invariants after every operation.
- Concurrent tests pass five consecutive runs.
- `git diff --check` prints nothing.

Before moving to the funds-control plan, request an independent review against:

- architecture §§4–5, 8.1–8.3, 8.9–8.16, 9.1–9.2, 12.1.1, 13.7.1, 13.9, 16.1, 21.8–21.11 and 23;
- this plan's interfaces and exclusions;
- the exact base-to-head Git range for the accounting-kernel slice.
