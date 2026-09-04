# funds-core test catalogue

Module `services/funds-core` at commit `f4f5e91`, reviewed 2026-09-04. Every entry below was read from the test source; line numbers refer to that commit.

## How the suite runs

- **Runner.** Maven Surefire 3.5.4 runs every class in the normal test phase; the include list is **/Test*.java, **/*Test.java, **/*Tests.java, **/*TestCase.java, **/*Properties.java, **/*IT.java. There is no Failsafe phase, so integration tests cannot be skipped separately from unit tests.
- **Database.** Quarkus Dev Services starts postgres:18.6-bookworm through Testcontainers per JVM (reuse=false) and runs all eight Flyway migrations under the test profile. The test login is the container superuser; several classes depend on that for TRUNCATE, SET ROLE and SET SESSION AUTHORIZATION.
- **Gate.** ./mvnw clean verify in services/funds-core with Docker reachable. Checkstyle runs in validate before any test.

## Inventory

| Class | Type | Methods | Lines | Component |
|---|---|---:|---:|---|
| [MoneyTest](#moneytest) | Unit | 11 | 132 | domain |
| [NubanTest](#nubantest) | Unit | 5 | 66 | domain |
| [JournalValidatorTest](#journalvalidatortest) | Unit | 18 | 428 | application |
| [PostingServiceTest](#postingservicetest) | Unit | 8 | 403 | application |
| [JournalProperties](#journalproperties) | Property-based | 3 | 126 | application |
| [ProdDatasourceStartupGuardTest](#proddatasourcestartupguardtest) | Configuration | 2 | 41 | runtime |
| [PackagingContractTest](#packagingcontracttest) | Packaging contract | 20 | 820 | pom |
| [WorkerPoolBoundsIT](#workerpoolboundsit) | Configuration | 1 | 66 | The Quarkus global worker executor as configured by quarkus |
| [PostingTimeoutIT](#postingtimeoutit) | Failure injection | 3 | 194 | application |
| [PostingAtomicityIT](#postingatomicityit) | Failure injection | 1 | 454 | application |
| [PostingServiceIT](#postingserviceit) | Failure injection | 17 | 1278 | application |
| [PostingConcurrencyIT](#postingconcurrencyit) | Concurrency | 6 | 1098 | application |
| [AccountingStateMachineIT](#accountingstatemachineit) | Property-based | 3 | 957 | application |
| [ReversalServiceIT](#reversalserviceit) | Failure injection | 26 | 1373 | application |
| [AcceptanceHardeningIT](#acceptancehardeningit) | Integration (PostgreSQL) | 23 | 1803 | V005 and V006 triggers, CHECKs and unique indexes on journal, posting, idempotency_command, chart_version, ledger_account_chart_mapping, ledger_account, product_version, product_definition; the owner-only funds |
| [AccountingProofServiceIT](#accountingproofserviceit) | Integration (PostgreSQL) | 10 | 518 | application |
| [LedgerConstraintIT](#ledgerconstraintit) | Integration (PostgreSQL) | 23 | 1171 | Migrated schema |
| [MigrationIT](#migrationit) | Integration (PostgreSQL) | 36 | 1171 | Migrated schema |
| [MigrationUpgradeIT](#migrationupgradeit) | Integration (PostgreSQL) | 2 | 755 | V005 (chart backfill, scheme tags, string-dimension CHECK, product columns moved to product_version) and V006 (rotate_chart_version); the V004-history replay path in JdbcLedgerRepository (V004_OPAQUE / V004_V1 verifiers versus TYPED_V2 / V2) |
| [PostingCrashRecoveryIT](#postingcrashrecoveryit) | Crash (child JVM) | 16 | 2977 | application |
| [CrashPostingWorker](#crashpostingworker) | Test support | 1 | 133 | Posts one deterministic command through TestPostingStack |
| [TestPostingStack](#testpostingstack) | Test support | - | 240 | create(dataSource, observer) wires JournalValidator, CanonicalJournalHasher, JdbcLedgerRepository(validator, hasher, observer), a no-pause PostgresRetryPolicy and the 4-arg PostingService; resetAndSeed and reset (TRUNCATE RESTART IDENTITY CASCADE over 14 tables); the deterministic reference graph with pre-seeded projections |
| [ReferenceLedgerModel](#referenceledgermodel) | Test support | - | 261 | predict(command) -> NEW_SUCCESS, SUCCESSFUL_RETRY, INVALID_JOURNAL, IDEMPOTENCY_CONFLICT or MONETARY_OVERFLOW; apply(command, result) with AssertionError on disagreement; reversibleJournalIds; expectedOutboxIds; exceptionType mapping |
| [PropertyCases and GeneratedLedgerOperation](#propertycases-and-generatedledgeroperation) | Test support | - | 86 | positiveMinorUnits(seed, n) |

20 test classes, 234 test methods, plus 4 support entries.

## Acceptance coverage map

| Acceptance | Claim | Evidence classes | Gap to challenge |
|---|---|---|---|
| ACC-01 | Unbalanced and mixed-currency journals reject; period, policy and chart governance; mapping races | JournalValidatorTest, JournalProperties, PostingServiceIT, LedgerConstraintIT, AcceptanceHardeningIT, PostingConcurrencyIT | Balance trigger assumed rather than proved at the DB boundary in AcceptanceHardeningIT; mixed-currency covered only in LedgerConstraintIT and PostingServiceIT. |
| ACC-02 | Serializable concurrent updates, canonical locks, persisted postings, materialised equals replayed balances | PostingServiceIT, PostingAtomicityIT, PostingConcurrencyIT, AccountingStateMachineIT | Deadlock-freedom is inferred, not observed; chart-before-book lock order is never traced. |
| ACC-19 | Trial balance and control-account proofs with corruption detection | AccountingProofServiceIT, AccountingStateMachineIT | All proof accounts are debit-normal; proof-reader arithmetic runs only on an empty database in MigrationIT. |
| ACC-20 | Closed-period rejection, exact linked reversal, 256/32/8,192 and the reversible domain | JournalValidatorTest, ReversalServiceIT, AcceptanceHardeningIT, PostingServiceTest | At-limit acceptance exists only for 256 postings and 8,192 bytes in ReversalServiceIT; reversal-side caps in ReversalService are unreachable; narration limit appears DB-only. |
| ACC-24 | Application role cannot mutate the ledger, disable triggers or escalate; proof reader reads only proof columns | MigrationIT, LedgerConstraintIT, PostingServiceIT | MigrationUpgradeIT and the crash child run as superuser; maker-checker is explicitly out of scope. |
| ACC-25 | Bounded JVM, HTTP and JDBC inputs; worker boundary; 1s/3s/5s deadlines; constrained image | PackagingContractTest, WorkerPoolBoundsIT, PostingTimeoutIT, ReversalServiceIT | Idle-transaction deadline is a setting check only; core-thread count unasserted; smoke script runs outside the Maven gate. |
| ACC-29 | Integer money, enums, presence fixtures, typed hashes over every financial field, golden bytes | MoneyTest, JournalValidatorTest, PostingServiceTest | No all-field matrix for reversalV2; subtract untested. |
| ACC-32 | Same-key races, stale-hash matrices, same-content replay before governance change, owner recovery, crash before and after commit | PostingServiceTest, PostingServiceIT, PostingConcurrencyIT, ReversalServiceIT, PostingCrashRecoveryIT, MigrationUpgradeIT | Only two of five hooks are real crash points; the idempotency state between crash and retry is never asserted. |
| ACC-38 | NUBAN check digits, scoped alias cardinality, primary uniqueness, immutable mappings, simulator fixture | NubanTest, MigrationIT | No positive AccountIdentifier construction; DB rejections use the loose seven-state oracle. |
| ACC-40 / ACC-42 | Product kind and finance principle immutable on each product version; historical accounts keep classification | MoneyTest, MigrationIT, AcceptanceHardeningIT, MigrationUpgradeIT | product_kind is never the target of an UPDATE anywhere; approval_reference never negatively tested. |

## Cross-cutting QA themes

### Type-only exception oracles

Across the suite most negative service-path tests assert only the exception class. InvalidJournalException is thrown for at least six distinct reasons, so a wrong-reason rejection passes. Add a reason code or message fragment to the domain exceptions and assert it.

Where: ReversalServiceIT, AcceptanceHardeningIT, JournalValidatorTest, PostingServiceIT, AccountingProofServiceIT

### Loose SQLSTATE oracle in MigrationIT

Seventeen tests accept any of seven SQLSTATEs. LedgerConstraintIT and AcceptanceHardeningIT already have constraint-name helpers; MigrationIT should adopt assertConstraint for immutability, scope-change, NUBAN, period-overlap and product-version tests, and pin 55P03 in the lock test.

Where: MigrationIT

### Harness self-tests inflate the integration suites

Twelve of sixteen tests in PostingCrashRecoveryIT, two in PostingConcurrencyIT and roughly two thirds of PackagingContractTest test their own helpers. They are legitimate hardening but should move to a test-support module with plain unit tests so the production-facing count is honest.

Where: PostingCrashRecoveryIT, PostingConcurrencyIT, PackagingContractTest

### Superuser coupling

TRUNCATE, SET ROLE, SET SESSION AUTHORIZATION, CREATE ROLE and the crash child all rely on the Dev Services superuser. The only real-login evidence is PostingServiceIT's temporary funds_app role and LedgerConstraintIT's SET SESSION AUTHORIZATION. MigrationUpgradeIT replays V004 history as superuser, so an upgrade privilege regression is invisible.

Where: MigrationUpgradeIT, TestPostingStack, AccountingProofServiceIT, MigrationIT

### Production deadlines inside race windows

Hand-wired services keep the 1 s lock and 5 s idle deadlines while tests poll for 5 to 10 s. PostingConcurrencyIT and PostingCrashRecoveryIT's bounded-cancellation test can flake loudly under load. Either widen the timeouts for those stacks or tighten the windows and assert the overlap.

Where: PostingConcurrencyIT, PostingCrashRecoveryIT

### Boundary acceptance is thin

Limits are mostly proved one-over (257, 33, 8,193). At-limit acceptance exists for 256 postings and 8,192 bytes only. 32 dimensions accepted is nowhere.

Where: JournalValidatorTest, AcceptanceHardeningIT, ReversalServiceIT

### Three reference-graph fixtures

TestPostingStack, PostingServiceIT and PostingConcurrencyIT each seed the same uuid(1..8) graph with small differences (product code, projection baselines, RESTART IDENTITY). Consolidate on TestPostingStack and rename it so it does not match the surefire include.

Where: TestPostingStack, PostingServiceIT, PostingConcurrencyIT

### Claims that outrun the assertion

Several method names promise more than the body proves: 'Atomically' in LedgerConstraintIT, 'RepeatableReadSnapshot' in ReversalServiceIT, 'WithoutDeadlock' in PostingConcurrencyIT, 'BookAndChartMove' in LedgerConstraintIT, 'AddingGovernedRotation' in MigrationUpgradeIT. Rename or strengthen.

Where: LedgerConstraintIT, ReversalServiceIT, PostingConcurrencyIT, MigrationUpgradeIT

### No CI runs the gate

Only the architecture-docs workflow exists. The whole catalogue above is enforced by engineers running ./mvnw clean verify locally with Docker. Until a CI job exists, the PR checklist is the only guard.

Where: .github/workflows

## Class-by-class catalogue

Oracle strength: **strong** asserts exact values or state after failure; **medium** asserts an exception type or a partial state; **weak** asserts only that nothing threw or reads back what the test itself inserted.

### MoneyTest

`src/test/java/com/corebanking/funds/domain/MoneyTest.java` (132 lines)

- **Type:** Unit
- **Harness:** Plain JUnit 5.
- **Component under test:** domain: Money, CurrencyCode, NormalBalance, Book, LedgerAccount, ProductDefinition, ProductVersion and their enums.
- **Invariants and acceptance IDs:**
  - ACC-29: integer money with checked arithmetic, canonical currency, natural-balance rendering, required-field presence
  - ADR-0003: signed integer minor units, overflow raises MonetaryOverflowException
  - Sign convention: debit positive, credit negative, natural balance by normal side
  - ACC-40 / ACC-42: product kind and finance principle required on ProductVersion
- **Fixtures:** NGN currency, random UUIDs never asserted on, fixed timestamps, a 64-char hash literal.
- **Determinism and timing:** Instant and deterministic.

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `addsOnlySameCurrency` (L19) | 100 + 50 NGN, then NGN + USD | Money.of(NGN,150) equality; IllegalArgumentException for mixed currency | strong |
| `rejectsMissingCurrencyAtConstructionBoundary` (L25) | null currency through of() and the constructor | NullPointerException both | medium |
| `rejectsOverflowInsteadOfWrapping` (L30) | MAX + 1 and negate(MIN) | MonetaryOverflowException both | strong |
| `currencyCodeIsCanonical` (L37) | "ngn" and "NAIRA" | value() == NGN; IllegalArgumentException | medium |
| `rendersDebitAndCreditNormalBalances` (L44) | toNatural for both sides including MIN | 10,000; 10,000; -2,000; MonetaryOverflowException | strong |
| `bookRejectsMissingRequiredReferenceData` (L51) | valid Book then null legal entity, currency, timezone, empty calendar | no throw; NPE x3; IAE | medium |
| `bookRequiresPositivePolicyVersion` (L63) | policy version 1, 0, -1 | no throw; IAE; IAE | medium |
| `ledgerAccountRejectsMissingRequiredReferenceData` (L73) | valid account then five nulls and empty control code | no throw; NPE x5; IAE | medium |
| `ledgerAccountUsesItsNormalDirectionForBookedBalance` (L92) | credit-normal account with signed -2,500 | bookedNaturalBalance == 2,500 | strong |
| `productDefinitionRequiresIdentifierAndCode` (L100) | valid; null id; empty code | no throw; NPE; IAE | medium |
| `productVersionRequiresReferenceTermsAndValidInterval` (L106) | valid; null kind; null principle; version 0; start == end; empty approval; bad hash length | no throw; NPE x2; IAE x4 | medium |

**QA observations**

1. Money.subtract is documented in the README but never tested, nor is add underflow (MIN + (-1)).
2. Money itself admits Long.MIN_VALUE (line 34); rejection happens only in PostingLine. Consistent with the README, but worth stating.
3. CurrencyCode canonicalisation is tested for case and length only; whitespace and non-ASCII are untested.
4. ProductVersion with a null end date (open-ended) and start > end are untested; only start == end is (line 124).
5. All presence rejections are type-only; no message names the missing field.

### NubanTest

`src/test/java/com/corebanking/funds/domain/NubanTest.java` (66 lines)

- **Type:** Unit
- **Harness:** Plain JUnit 5 plus one reflective test over record components.
- **Component under test:** domain: AccountIdentifier (nubanCheckDigit, isValidNuban, constructor), AccountIdentifierScheme.
- **Invariants and acceptance IDs:**
  - ACC-38: NUBAN check digit, per-scheme construction rules, address-only identity
  - ADR-0007: an address never carries a balance; structurally checked by reflection
- **Fixtures:** Published CBN vector 000011/000001457 -> 9; synthetic 011000/987654321 -> 5; simulator fixture 000000/0000000017. Helper always builds SIMULATOR_ONLY, ACTIVE, non-primary.
- **Determinism and timing:** Instant and deterministic.

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `validatesPublishedCbnAlgorithmFixture` (L151) | check digit for the CBN sample | '9' and isValidNuban true | strong |
| `validatesGeneratedSerialAndRejectsMutatedDigit` (L156) | synthetic serial, then one mutated digit | '5'; true; false | strong |
| `rejectsNonDigitsAndKeepsSyntheticFixtureValid` (L166) | non-digit institution, non-digit NUBAN, simulator fixture | false; false; true | medium |
| `requiresNubanInstitutionScopeAndProviderScopeForVirtualAccounts` (L174) | NUBAN without institution; provider VA without provider; IBAN | IllegalArgumentException x3 | medium |
| `remainsAddressMetadataWithoutMoneyOrBalanceComponents` (L183) | reflects over record components | no component named *balance*, none assignable from Money | medium |

**QA observations**

1. No positive construction test exists: every identifier(...) call is expected to throw, so a regression rejecting all NUBANs would pass.
2. The IBAN rejection does not distinguish 'scheme unsupported' from 'institution code required'.
3. Wrong-length inputs (9-digit NUBAN, 5-digit institution) are never tried; only non-digit characters are.
4. The structural guard would miss a primitive long field not named 'balance'.
5. Only two check-digit vectors; no check digit 0 edge case.

### JournalValidatorTest

`src/test/java/com/corebanking/funds/application/JournalValidatorTest.java` (428 lines)

- **Type:** Unit
- **Harness:** Plain JUnit 5 with new JournalValidator() and new CanonicalJournalHasher().
- **Component under test:** application: JournalValidator (256 / 32 / 8,192 limits), CanonicalJournalHasher (sha256, v004Sha256, v2Sha256), CanonicalCommandHasher (postingV2, reversalV2), JournalDraft and PostingLine constructors.
- **Invariants and acceptance IDs:**
  - ACC-01: unbalanced and mixed-currency journals reject
  - ACC-20: 256 / 32 / 8,192 limits and the reversible signed-amount domain
  - ACC-29: every financial field changes the hash; V004_V1 and V2 golden bytes are pinned
  - ADR-0003: sum overflow raises MonetaryOverflowException
  - ADR-0005: Long.MIN_VALUE rejected because it cannot be negated exactly
- **Fixtures:** Fixed UUID(0,n) ids, booking time 2026-08-30T14:15:16.123456Z, policy version 41, three golden SHA-256 constants (lines 118, 143, 146, 149).
- **Determinism and timing:** Instant and deterministic.

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `acceptsBalancedSingleCurrencyJournal` (L56) | validates +100,000 / -100,000 NGN | assertDoesNotThrow only | weak |
| `acceptsMicrosecondBookingTimeAndRejectsSubMicrosecondPrecision` (L65) | .123456Z vs .123456001Z | no throw; InvalidJournalException | medium |
| `rejectsPerCurrencyImbalance` (L83) | +1,000 USD vs -1,000 NGN | InvalidJournalException (type only) | medium |
| `hashIsIndependentOfInputLineOrder` (L92) | two lines in both orders | sha256 equal | strong |
| `v004VerifierRetainsItsGoldenBytesAndDoesNotPinTheLaterChartField` (L105) | V004 hash with and without a chart change | exact golden d9aa3d75...57eb; V004 unchanged by chart; v2 differs | strong |
| `v2JournalAndTypedCommandSchemesRetainTheirGoldenBytes` (L128) | V2 journal, typed posting and typed reversal hashes | three exact 64-hex constants | strong |
| `rejectsEmptyJournal` (L153) | JournalDraft with no lines | IllegalArgumentException from the constructor, not the validator | medium |
| `rejectsZeroPosting` (L158) | PostingLine amount 0 | IllegalArgumentException at construction | medium |
| `rejectsAmountsThatCannotBeExactlyReversed` (L164) | PostingLine Long.MIN_VALUE | IllegalArgumentException at construction | medium |
| `rejectsJournalAndDimensionInputsBeyondTheReversalEnvelope` (L172) | 257 postings; 33 dimensions; oversized value | InvalidJournalException each; no at-limit acceptance | medium |
| `rejectsDuplicatePostingIdentity` (L195) | two lines share POSTING_A | InvalidJournalException | medium |
| `rejectsArithmeticOverflowWhileSummingCurrency` (L204) | MAX + 1 in one currency | MonetaryOverflowException | medium |
| `rejectsMissingPostingAndAccountIdentities` (L213) | null posting id; null account id | InvalidJournalException each | medium |
| `hashChangesForEveryFinanciallyMeaningfulJournalField` (L226) | baseline plus 19 single-field mutations | 20 distinct 64-hex hashes | strong |
| `hashExcludesDatabaseAssignedAccountSequence` (L311) | accountSequence 0 vs 99 | hashes equal | strong |
| `hashSortsOpposingDimensionInsertionOrdersAndConstructorsCopyMutableCollections` (L321) | two map orders; mutate sources after construction | hashes equal; UnsupportedOperationException on put/clear | strong |
| `hashUsesPostingIdentityAsTheTieBreakForOneAccount` (L346) | same-account lines in two orders, then one id changed | equal across order; not equal after id change | strong |
| `reversalPresenceAndValueAreBothCanonical` (L361) | reversalOfJournalId null vs two values | pairwise not equal | strong |

**QA observations**

1. Boundary acceptance is untested: nothing proves exactly 256 postings, 32 dimensions or 8,192 bytes are accepted (lines 175, 182, 189), so an off-by-one that rejects the legal maximum would pass.
2. Three 'rejects' tests exercise record constructors, not the validator (lines 153 to 168).
3. No direct all-field matrix for reversalV2; only one golden reversal hash (line 149).
4. Every rejection is type-only; no message is asserted.
5. The golden constants are the key regression guards and must never be updated to match new output (comment at lines 100 to 104).

### PostingServiceTest

`src/test/java/com/corebanking/funds/application/PostingServiceTest.java` (403 lines)

- **Type:** Unit
- **Harness:** Plain JUnit 5; hand-wired 3-arg PostingService with a recording proxy DataSource, a recording LedgerRepository and a no-pause retry policy.
- **Component under test:** application: PostingService.post admission ordering, PostgresRetryPolicy.execute, LedgerRepository.findCompleted / post contract, CanonicalCommandHasher.postingV2.
- **Invariants and acceptance IDs:**
  - ACC-32: stale-hash mutation matrix; same-content replay wins before validation
  - ACC-20: generic path denies caller-supplied reversal metadata
  - ADR-0006: idempotency admission happens before any ledger work
  - ADR-0005: reversal links only through ReversalService
- **Fixtures:** Fixed draft uuid(1..12), BOUNDARY_TEST, 2026-01-15; mutable DraftValues copy for the 19-field matrix; RecordingRepository.completed simulates a stored result.
- **Determinism and timing:** Instant and deterministic; the executions counter is per outer execute call, not per attempt.

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `subMicrosecondBookingTimeFailsAfterIdempotencyPreflightButBeforeRetryOrPost` (L44) | nanosecond booking time with a valid hash | InvalidJournalException; executions 1, connections 1, preflight 1, repository calls 0 | strong |
| `otherInvalidJournalFailsAfterIdempotencyPreflightButBeforeRetryOrPost` (L58) | unbalanced 100 / -99 | same exception and counters | strong |
| `exactMicrosecondBookingTimeProceedsThroughOneNormalAttempt` (L72) | valid command | journalId matches; counters 1/1/1/1 (result comes from the fake) | medium |
| `completedSameContentReplayResolvesBeforeLaterJournalValidation` (L87) | invalid draft but repository reports a completed result | stored result returned; connections 1, preflight 1, calls 0 | strong |
| `staleCallerHashCannotAuthorizeChangedFinancialContent` (L102) | original hash with changed narration | IdempotencyConflictException; connections 0, calls 0 | strong |
| `staleCallerHashConflictsForEveryFinancialCommandField` (L122) | 19 single-field mutations under the original hash | IdempotencyConflictException per field; connections 0, preflight 0, calls 0 | strong |
| `genericPostingPathRejectsCallerSuppliedReversalMetadata` (L171) | non-reversal type carrying reversalOfJournalId with a recomputed hash | InvalidJournalException; repository calls 0 (no connection count) | medium |
| `staleHashOnCallerSuppliedReversalMetadataConflictsBeforeMetadataValidation` (L190) | same disguise with the stale hash | IdempotencyConflictException; connections 0, calls 0 | strong |

**QA observations**

1. The proxy Connection returns null for unhandled JDBC methods and swallows commit/rollback/close, so rollback on failure is unobservable here.
2. Counters prove call counts, not order; 'before retry' is inferred from executions == 1.
3. genericPostingPathRejectsCallerSuppliedReversalMetadata omits the connection and preflight counters its siblings assert (line 171).
4. The matrix row that mutates commandId conflicts because the hash covers the id, so 'same commandId, different content' is not what that row tests (line 129).
5. No case with a completed result and a mismatched hash; that path lives in the ACC-32 race tests.

### JournalProperties

`src/test/java/com/corebanking/funds/application/JournalProperties.java` (126 lines)

- **Type:** Property-based
- **Harness:** Plain JUnit 5 over a seeded SplittableRandom generator from PropertyCases; no property library, no shrinking. Runs because of the **/*Properties.java include.
- **Component under test:** application: JournalValidator.validate; PostingLine and JournalDraft constructors; PropertyCases.positiveMinorUnits.
- **Invariants and acceptance IDs:**
  - ACC-01: balance invariant across the amount range
  - Balanced journal: off-by-one imbalance always rejected
- **Fixtures:** SEED 0xCB20260830L, 2,000 random cases plus 7 boundaries (1, 2, 99, 100, 1e9, MAX/2, MAX-1); single NGN currency; case label carries seed and amount.
- **Determinism and timing:** Milliseconds; fully deterministic.

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `addingEqualDebitAndCreditAlwaysBalances` (L36) | +a / -a for 2,007 amounts | assertDoesNotThrow with case label | weak |
| `changingOneSideByOneMinorUnitAlwaysFails` (L49) | +a / -(a-1) for every amount | a == 1: IllegalArgumentException; else InvalidJournalException | medium |
| `positiveMinorUnitsContainsExactBoundariesThenReproducibleRandomCases` (L74) | draws the stream twice | length 2,007; exact boundary prefix; identical draws; tail in [1, 1e9] | strong |

**QA observations**

1. Only the credit side is perturbed and only by +1 (lines 59, 66); the Javadoc claims 'either side'. A validator accepting a net of -1 would pass.
2. Random draws stop at 1e9, so the upper half of the long domain is covered only by two fixed boundaries.
3. The positive property is no-exception only; it has teeth only because of the negative companion.
4. Case count cannot be scaled from the environment.

### ProdDatasourceStartupGuardTest

`src/test/java/com/corebanking/funds/runtime/ProdDatasourceStartupGuardTest.java` (41 lines)

- **Type:** Configuration
- **Harness:** Plain JUnit 5 calling the package-private static validate(active, url, username, password).
- **Component under test:** runtime: ProdDatasourceStartupGuard.validate only. The @Startup / @IfBuildProfile("prod") wiring is not exercised.
- **Invariants and acceptance IDs:**
  - README roles: missing or blank production datasource inputs fail closed and the diagnostic never echoes a value
- **Fixtures:** Literal inputs including a sensitive password string.
- **Determinism and timing:** Instant.

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `rejectsEachMissingOrBlankDatasourceFieldWithoutEchoingValues` (L18) | six calls with one field missing, blank or 'false' | IllegalStateException with the exact 'missing or blank: <property>' message; message excludes the password and URL | strong |
| `acceptsCompleteResolvedDatasourceValues` (L28) | all four values present | assertDoesNotThrow | weak |

**QA observations**

1. The 'before readiness can be UP' claim is a wiring property that no in-JVM test proves; only prod-runtime-smoke.sh probes it, outside the Maven gate.
2. Case-insensitive 'true' is accepted by production but untested; 'yes' or '1' are not rejected by any test.
3. Blank versus null coverage is asymmetric: url only null, username and password only whitespace.
4. Precedence when several fields are missing is untested.

### PackagingContractTest

`src/test/java/com/corebanking/funds/PackagingContractTest.java` (820 lines)

- **Type:** Packaging contract
- **Harness:** Plain JUnit 5 with @TempDir; spawns git via ProcessBuilder (5 s bound, 16 KiB cap); hardened DOM parser; CountingProperties; filesystem symlinks. MODULE is resolved in a static initialiser.
- **Component under test:** pom.xml, src/main/resources/application.properties (19 controlled keys), Dockerfile.jvm, README.md, docs/health-contract.md, scripts/prod-runtime-smoke.sh.
- **Invariants and acceptance IDs:**
  - ACC-25: bounded JVM, HTTP and JDBC inputs; 2-8/32 worker boundary; 1s/3s/5s deadlines; constrained image
  - README memory boundary: JVM flags, pool bounds, 128 KiB bodies, no heap dumps
  - README roles: migrate-at-start false; prod datasource from environment; no embedded JDBC URL
  - ADR-0008: resource-budgeted single VM evidence suite (closest ADR, not cited)
- **Fixtures:** Decoy modules written into @TempDir, some symlinked into the real module; isolated git repositories via git init and git add; CONTROLLED_PROPERTIES map; closed CONTRACT_INPUTS list.
- **Determinism and timing:** Dozens of git subprocess spawns per run; needs git on PATH, a real worktree, symlink support and preserved mode bits. No sleeps.

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `modulePathMatchesAnIndependentTrackedGitAnchor` (L83) | compares the resolver with git rev-parse and ls-files | paths equal | strong |
| `modulePathIgnoresCallerSuppliedBasedirOverride` (L90) | sets a property nothing reads | same path | weak |
| `symlinkedCodeSourceCanonicalizesToTheRealModuleBeforeWalkingParents` (L108) | symlinked target/test-classes | resolves to the real module | medium |
| `regularDecoyModuleOutsideTheGitWorktreeFailsClosed` (L125) | decoy in @TempDir | IllegalStateException containing 'exact Git-tracked path' | medium |
| `symlinkedModuleSentinelsFailBeforeRepositoryValidation` (L136) | symlinked pom and properties sentinels | message names the sentinel and 'non-symbolic-link regular file' | medium |
| `sentinelThroughASymlinkedParentDirectoryFailsCanonicalValidation` (L156) | symlinked resources directory | message contains 'symlink-free canonical path' | medium |
| `nonSentinelContractInputSymlinksFailBeforeContentOrMetadataAccess` (L175) | symlinks each non-sentinel input | IllegalStateException per input | medium |
| `nestedContractInputsRejectSymlinkedParentDirectoriesBeforeRepositoryValidation` (L204) | symlinked docs and scripts parents | IllegalStateException per input | medium |
| `exactTrackedContractPathIsAcceptedInAnIsolatedRepository` (L231) | fresh repo with a tracked Dockerfile | real path returned | strong |
| `untrackedContractPathFailsClosedInAnIsolatedRepository` (L241) | README written but not added | IllegalStateException naming README | strong |
| `nonExactRelativeContractPathIsRejectedBeforeFilesystemAccess` (L256) | ./Dockerfile.jvm spelling | message contains 'exact repository-relative spelling' | medium |
| `wrongTrackedPomIdentityFailsClosed` (L264) | decoy pom with another groupId | message contains com.corebanking:funds-core | medium |
| `duplicateTrackedPomIdentityFailsClosed` (L279) | two artifactId children | message contains com.corebanking:funds-core | medium |
| `productionConfigurationHasOneEffectiveAssignmentForEveryBound` (L302) | loads application.properties and counts assignments | 19 exact values, one assignment each, no jdbc:postgresql:// anywhere | strong |
| `semanticAssignmentCountingDetectsEscapedAndContinuedDuplicateKeys` (L319) | unicode-escaped and line-continued duplicates | count == 2 both ways | strong |
| `pomBindsExactlyOneQuarkusBuildGoal` (L331) | parses pom DOM | exactly one quarkus-maven-plugin execution with goal build | strong |
| `nestedPomConfigurationGoalsDoNotCountAsLifecycleBindings` (L336) | injects a nested goal under configuration | assertDoesNotThrow | medium |
| `dockerfileIsTheCompletePinnedNonRootRuntimeContract` (L375) | reads non-comment lines | exact 9-line list including the full digest, USER 10001 and JAVA_TOOL_OPTIONS; no HeapDumpOnOutOfMemoryError | strong |
| `documentationHasUniqueRequiredSectionsCoverageAndExclusions` (L398) | parses README and health contract | unique headings; 11 ACC rows exactly once; 13 exclusions; fail-closed phrase; 8-char digest prefix; smoke phrase once | medium |
| `documentedRuntimeSmokeIsExecutable` (L427) | checks mode bit and README command line | isExecutable; exact command line present once | medium |

**QA observations**

1. About two thirds of the class self-tests its own trust resolver (lines 83 to 296); production-facing assertions are lines 302 to 433.
2. requireExactTrackedContractPath rethrows without the cause (lines 756 to 759, 529, 787), so a missing git binary or a safe.directory refusal in CI reports as 'untracked file' and, via the static initialiser, fails the whole class with ExceptionInInitializerError.
3. Profile-prefixed overrides such as %dev.quarkus.datasource.jdbc.max-size=100 are not detected because keys are counted literally. Relevant to the local-development plan.
4. The README digest check is an 8-character prefix (line 423); the Dockerfile check holds the full digest.
5. The pom check ignores pluginManagement, profiles and parents, and the enforcer Java range is not part of the contract.
6. documentedRuntimeSmokeIsExecutable fails on core.fileMode=false checkouts for environmental reasons and does not read the script.

### WorkerPoolBoundsIT

`src/test/java/com/corebanking/funds/WorkerPoolBoundsIT.java` (66 lines)

- **Type:** Configuration
- **Harness:** @QuarkusTest (Dev Services PostgreSQL still starts); ExecutorRecorder.getCurrent(); CompletableFuture and latches.
- **Component under test:** The Quarkus global worker executor as configured by quarkus.thread-pool.* in application.properties. No funds-core class is exercised.
- **Invariants and acceptance IDs:**
  - ACC-25: 2-8/32 worker and queue boundary with deterministic rejection
  - ADR-0008: resource budget (closest ADR, not cited)
- **Fixtures:** Constants CORE_THREADS 2, MAX_THREADS 8, QUEUE_CAPACITY 32 mirrored by hand from application.properties.
- **Determinism and timing:** Latch waits of 5 s and 10 s. Assumes no other component holds a worker or queue slot at submission time; a background task shifts the boundary by one and fails the test.

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `globalWorkerPoolHasAFiniteQueueAndRejectsBeyondItsDeclaredCapacity` (L31) | submits 40 blocking tasks, waits for 8 workers, submits a 41st, releases | 8 started within 5 s; 41st throws RejectedExecutionException; all 40 complete; accepted.size() == 40 | medium |

**QA observations**

1. assertEquals(capacity, accepted.size()) at line 64 is tautological; the loop runs exactly capacity iterations.
2. CORE_THREADS is declared but never asserted, so the '2' half of 2-8/32 is unverified.
3. The rejection kind is asserted only by exception type; mapping to an HTTP saturation signal per the health contract is not covered here.
4. Configuration is mirrored by hand rather than read from the runtime config.

### PostingTimeoutIT

`src/test/java/com/corebanking/funds/application/PostingTimeoutIT.java` (194 lines)

- **Type:** Failure injection
- **Harness:** @QuarkusTest with Dev Services PostgreSQL; injected DataSource and the CDI-bound PostingTransactionTimeouts; hand-wired PostingService with custom timeouts and a counting retry pause; raw JDBC for lock holding.
- **Component under test:** application: PostingTransactionTimeouts (config binding and apply), PostingService.post (5-arg), JdbcLedgerRepository, PostgresRetryPolicy, SqlState, LedgerTimeoutException; PostgreSQL lock_timeout, statement_timeout, idle_in_transaction_session_timeout.
- **Invariants and acceptance IDs:**
  - ACC-25: transaction-local 1s/3s/5s deadlines applied before financial work; timeouts are never retried
  - ADR-0006: a timed-out posting leaves no command, journal, posting or outbox row
- **Fixtures:** TestPostingStack resetAndSeed; command uuid(2000) with +100 / -100 NGN; lockAccount takes FOR UPDATE on ledger_account; guarded setting and count helpers.
- **Determinism and timing:** Real 250 ms lock wait with a [150 ms, 2 s) acceptance window; pg_sleep(1) against 150 ms. No polling.

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `productionDeadlinesAreAppliedLocallyBeforeFinancialWork` (L63) | applies the CDI timeouts on a pooled connection and reads SHOW | lock 1s, statement 3s, idle 5s | medium |
| `blockedAccountLockHasATypedFiniteOutcomeAndIsNotRetried` (L81) | holder locks PROVIDER_ASSET; service with 250 ms / 2 s / 3 s posts | LedgerTimeoutException; SQLSTATE 55P03 in the cause chain; retry pauses 0; elapsed in [150 ms, 2 s); four tables empty | strong |
| `statementDeadlineMapsToTheSameTypedNonRetryableOutcome` (L118) | pg_sleep(1) under a 150 ms statement timeout on a raw connection | SQLSTATE 57014; persistenceFailure yields LedgerTimeoutException; not retryable | medium |

**QA observations**

1. The idle-transaction deadline is asserted only as a setting value; no test drives a session to the 5 s cutoff or classifies the resulting 25P03.
2. Transaction-locality is not proved: after rollback the settings are not re-read to confirm they returned to 0.
3. The lock test does not assert materialised_balance or control projections are unchanged; narrower than PostingCrashRecoveryIT.assertOneCompletedEffect.
4. The CDI-wired PostingService is never shown to receive the CDI timeouts; test 2 uses a hand-built service.
5. assertTrue(!isRetryable) at line 136 should be assertFalse (style only).

### PostingAtomicityIT

`src/test/java/com/corebanking/funds/application/PostingAtomicityIT.java` (454 lines)

- **Type:** Failure injection
- **Harness:** @QuarkusTest with Dev Services PostgreSQL; stack hand-wired through TestPostingStack.create with a throwing PostingTransactionObserver.
- **Component under test:** application: PostingService, JdbcLedgerRepository, PostgresRetryPolicy, CanonicalJournalHasher, the afterFinancialRowsBeforeOutbox hook; all six ledger tables.
- **Invariants and acceptance IDs:**
  - ACC-02: all-or-nothing commit of postings, balances, projections, idempotency and outbox
  - ADR-0006: exactly one COMPLETED command and one JournalPosted event after recovery
  - ADR-0004: PostgreSQL rollback is the recovery mechanism
  - Balanced journal: +100,000 / -100,000 with +1 sequence and version increments
- **Fixtures:** TestPostingStack baselines (provider 11,000/3/3, customer -17,000/5/5, PROVIDER-CASH 11,000, INDEPENDENT-CONTROL 777, CUSTOMER-DEPOSITS absent); COMMAND_ID uuid(20); journal and posting ids shared with CrashPostingWorker; six-table RowProbe snapshot through a fresh connection.
- **Determinism and timing:** Deterministic; two TRUNCATEs; no-pause retry policy.

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `failureAfterFinancialRowsButBeforeOutboxRollsBackEverything` (L62) | snapshot; post with the throwing observer; snapshot; post again with a no-op observer; snapshot | InjectedPostingFailure carries the command id; before == after-failure snapshot (six tables, record equality); after recovery exact JournalFact, two exact PostingFacts with dimensions, balance deltas +100,000/-100,000, exact BalanceFacts (+1 seq and version), control deltas including INDEPENDENT 0, one COMPLETED command pointing at uuid(21), one JournalPosted outbox row at the journal sequence | strong |

**QA observations**

1. The snapshot is scoped to the command id and the two fixture accounts (lines 268 to 276), so a leaked row under another command or account would be missed; controls are book-wide.
2. result_json is checked by substring for the canonical hash (line 157), not by JSON structure.
3. assertEquals inside the probe's bindAccounts (line 449) fails with a fixture-level message rather than a test-level one.
4. Only one injection point; the other four observer hooks are covered (partially) by PostingCrashRecoveryIT.
5. Recovery uses a new TestPostingStack, which proves a clean retry but not same-instance recovery (acceptable, no in-memory state).

### PostingServiceIT

`src/test/java/com/corebanking/funds/application/PostingServiceIT.java` (1278 lines)

- **Type:** Failure injection
- **Harness:** @QuarkusTest with Dev Services PostgreSQL; injected service for happy paths; hand-wired service over a RecordingDataSource (proxy Connection tracing autocommit, isolation, commit, rollback, close); ScriptedLedgerRepository; a DDL-installed 22003 trigger; a temporary LOGIN role that only holds funds_app. Re-implements the reference graph inline instead of using TestPostingStack.
- **Component under test:** application: PostingService, ReversalService, JdbcLedgerRepository, PostgresRetryPolicy (RetryJitter), SqlState, both hashers, all domain exceptions; V002 narration CHECK; V004 role model.
- **Invariants and acceptance IDs:**
  - ACC-02: Example A inflow as one atomic ledger effect
  - ACC-32: same-hash replay, same-content replay before later governance changes, different-hash conflict
  - ACC-01 / ACC-20: currency mismatch and closed period reject with zero committed rows
  - ACC-24: post and reverse through a login holding only funds_app
  - ADR-0003: MonetaryOverflowException on balance and projection overflow; LedgerCapacityException on sequence and version exhaustion
  - ADR-0006: idempotency and outbox rows commit with the journal
  - Retry policy: only 40001 and 40P01 retry, at most five attempts, fresh serializable transaction each time
- **Fixtures:** Fixed uuid(1..8) reference graph, uuid(20..23) command and journal, uuid(40..42) reversal; DIFFERENT_HASH of 64 f's; @BeforeEach and @AfterEach truncate 14 tables CASCADE (no RESTART IDENTITY); seedProjectionState; TemporaryLoginRole with a hard-coded password literal.
- **Determinism and timing:** No sleeps or repetitions; retries never sleep; each test truncates twice. Needs a login able to CREATE ROLE, GRANT and pg_terminate_backend.

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `postsExampleAInflowAsOneAtomicLedgerEffect` (L107) | posts Example A through the injected service | journalId; canonicalHash == sha256(journal); counts 1/2; balances and sequences; posting rows; control totals; one COMPLETED command with hash and journal id; one unpublished JournalPosted outbox row at the journal sequence | strong |
| `completedCommandWithSameHashReturnsStoredResultWithoutReposting` (L146) | posts twice | first == replay; counts and balances unchanged | strong |
| `completedSameContentReplayWinsBeforeLaterPeriodChartAndPolicyChanges` (L167) | posts, then closes the period, bumps policy, retires the chart, re-posts | stored == replay; counts 1/2/1 | strong |
| `completedCommandWithDifferentHashIsAnIdempotencyConflict` (L197) | same command id with DIFFERENT_HASH over a recording datasource | IdempotencyConflictException; zero connections opened; stored hash unchanged | strong |
| `closedExplicitPeriodRejectsCommandWithoutCommittingAnyPostingRows` (L219) | closes the period, posts | AccountingPeriodClosedException; six tables empty | medium |
| `accountCurrencyMismatchRollsBackEveryPostingRow` (L230) | USD lines on NGN accounts | InvalidJournalException; exactly one connection with [autoCommit:false, isolation:8, rollback, close]; six tables empty | strong |
| `materialisedBalanceOverflowRollsBackEveryChange` (L249) | seeds balance at MAX, posts +1/-1 | MonetaryOverflowException; single rolled-back attempt; balance still MAX, sequence 9; no new rows | strong |
| `accountSequenceExhaustionIsCapacityFailureNotMonetaryOverflow` (L280) | seeds sequence at MAX | LedgerCapacityException; no committed facts; sequence unchanged | medium |
| `materialisedVersionExhaustionIsCapacityFailureNotMonetaryOverflow` (L298) | seeds version at MAX | LedgerCapacityException; no committed facts; version unchanged | medium |
| `controlProjectionOverflowRollsBackEarlierMaterialisedAndControlChanges` (L319) | seeds a control at MAX with balances already updated first | MonetaryOverflowException; single rolled-back attempt; both balances and both controls equal their seeds | strong |
| `postgresNumericOverflowIsMappedAndRollsBackEveryEarlierWrite` (L354) | installs a trigger raising 22003 on PROVIDER-CASH | MonetaryOverflowException with 22003 in the cause chain; not retried; all seeded state unchanged | strong |
| `postingServiceRetriesWithFreshSerializableTransactionsAndUnchangedCommand` (L393) | scripted repository throws 40001 twice then delegates | 3 distinct connections; jitter for attempts [1, 2]; same command instance each time; two rollbacks then one commit | strong |
| `postsAndReversesThroughFreshConnectionsRestrictedToFundsApp` (L445) | temporary login granted only funds_app, one scripted 40001, post then reverse | 3 repository calls; exactly 4 connection identities each with current_user funds_app and no migrator membership; 2 journals, 4 postings, 2 outbox rows; balances and controls net 0; reversal command COMPLETED | strong |
| `temporaryLoginRoleIsDroppedWhenSetupFails` (L500) | GRANT of a missing role inside the helper | SQLSTATE 42704; role absent afterwards | strong |
| `postingServiceStopsAfterFiveFreshRolledBackTransactions` (L521) | scripted repository always throws 40P01 | LedgerPersistenceException; 5 connections, 5 commands, 0 commits, 5 rollbacks; jitter [1..4] | strong |
| `postingServicePreservesSuppressedRollbackFailure` (L549) | repository throws; rollback also fails | same original exception; one suppressed 'rollback failed'; single rolled-back attempt | strong |
| `ordinaryPostgreSqlConstraintFailureIsNotRetried` (L572) | 513-byte narration | LedgerPersistenceException with 23514; single rolled-back attempt | strong |

**QA observations**

1. Dimension persistence is claimed in the fixture Javadoc (lines 586 to 590) but never read back; no test reads funds.posting.dimensions here.
2. Journal row content is unasserted beyond count(*): canonical_hash, period, chart, policy, narration, booking time and value date are never read back.
3. The different-hash conflict is rejected at Java admission with zero connections, so the stored-row-hash-differs path in findCompleted is not exercised here.
4. Three rejection tests (lines 219, 280, 298) lack the RecordingDataSource, so 'one attempt, rolled back' is unproven for them.
5. Exception messages are never asserted except the injected 'rollback failed'.
6. Retry tests fabricate 40001 and 40P01 before any SQL runs; real contention is never produced here.
7. Brittle internal counts (4 identities, 3 repository calls at lines 474 to 475) encode the reversal preflight connection count; a hard-coded password literal sits at line 447.
8. The 513-byte narration test implies the Java validator does not cap narration at 512 bytes; the README claims matching service and database limits. Worth a ticket.
9. The reference graph duplicates TestPostingStack with a different product code; two fixtures to keep in sync.

### PostingConcurrencyIT

`src/test/java/com/corebanking/funds/application/PostingConcurrencyIT.java` (1098 lines)

- **Type:** Concurrency
- **Harness:** @QuarkusTest with Dev Services PostgreSQL; hand-wired PostingService (4-arg, default 1s/3s/5s timeouts, no-pause retry) with gate observers; ObservedDataSource proxy capturing pg_backend_pid and lock-statement parameters; 2-thread pools; pg_stat_activity polling.
- **Component under test:** application: PostingService, PostingCommand, PostingResult, CanonicalCommandHasher.postingV2; JdbcLedgerRepository (idempotency row lock, lock_period_for_posting, lock_account_mapping_for_posting, materialised_balance FOR UPDATE); PostgresRetryPolicy.
- **Invariants and acceptance IDs:**
  - ACC-32: same-key same-hash and different-hash races produce one effect; loser gets IdempotencyConflictException
  - ACC-01: period close waits on the in-flight posting's FOR SHARE lock, five repetitions
  - ACC-02: accounts lock in canonical UUID-string order regardless of input order; materialised balance equals replayed sum
  - ADR-0006: outbox count equals journal count after every race
- **Fixtures:** Fixed uuid(1..8) reference graph truncated and reseeded per test (no RESTART IDENTITY); FirstWriterGate parks in afterIdempotencyAcquired; BeforeCommitGate parks in beforeCommit; AccountLockObserver records afterAccountLocks. Two threads per test; 5 repetitions for period close; 50 sequential pairs for the reverse-order test.
- **Determinism and timing:** No sleeps. Latches 5 to 30 s; busy-poll loops opening a new pooled connection per iteration for up to 10 s; futures up to 30 s. The reverse-order test is 50 sequential pairs.

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `concurrentSameCommandAndHashWaitsForTheWinnerAndReturnsOneEffect` (L100) | races two identical commands; winner parked after idempotency acquire; loser observed lock-waiting | no failures; identical PostingResults; counts 1/2/1 | strong |
| `concurrentSameCommandAndDifferentHashesKeepsOnlyTheWinningRequest` (L124) | same race with reversed line order (different hash) | one success, one IdempotencyConflictException; persisted journal, request hash and canonical hash belong to the winner | strong |
| `periodCloseWaitsForTheInFlightPostingBeforeItCanCommit` (L162) | @RepeatedTest(5): posting parked at beforeCommit; closer UPDATEs the period with a 5 s lock_timeout | closer observed in wait_event_type Lock; neither future done while parked; afterwards journal committed and period CLOSED | strong |
| `reverseInputOrderLocksCanonicallyAndCommitsOneHundredJournals` (L241) | 50 pairs of A-first and B-first journals with traced lock order | 100 committed traces; every account and materialised lock list is exactly [A,B] in canonical order; counts 100/200/100; balances equal replayed sums of 5,050 and -5,050 | medium |
| `boundedCleanupCancelsOutstandingFutureWithoutCallingExecutorClose` (L319) | self-test of shutdownExecutor | future cancelled, worker interrupted, delegate terminated, close() not called | strong |
| `cleanupFailureIsSuppressedWhenBodyAlreadyFailed` (L370) | self-test: awaitTermination false while the body already failed | primary exception preserved with one suppressed AssertionError | strong |

**QA observations**

1. The hand-wired service keeps the production 1 s lock and 5 s idle deadlines while the test polls for up to 10 s. Under load the loser can hit 55P03 (not retried) or the parked posting can be killed by the idle timeout. Failure mode is a loud flake, not a false pass.
2. 'Without deadlock' in the reverse-order test is inferred: pairs are joined before the next pair starts and nothing proves the two workers overlapped inside lock_account_mapping_for_posting.
3. LockKind.from matches SQL text and assumes parameter 1 is the account id (lines 960 to 966, 1047 to 1055); a parameter reorder could silently record the chart id.
4. Two shutdownExecutor self-tests pay for a Quarkus context and DB reset while touching no production code.
5. No assertion that the idempotency row is COMPLETED with a matching result_json after a race; no control projection check in the reverse-order test; chart-before-book lock order is not traced.
6. queryLong and queryString ignore rows.next(); an empty result reads as 0 or null.

### AccountingStateMachineIT

`src/test/java/com/corebanking/funds/application/AccountingStateMachineIT.java` (957 lines)

- **Type:** Property-based
- **Harness:** @QuarkusTest with Dev Services PostgreSQL; PostingService hand-wired via TestPostingStack; ReferenceLedgerModel as the oracle; sealed GeneratedLedgerOperation vocabulary; SplittableRandom(seed).
- **Component under test:** application: PostingService.post and postTrustedReversal, both hashers, JdbcLedgerRepository write path; DB triggers including reversal_exact_negation; all ledger tables.
- **Invariants and acceptance IDs:**
  - ACC-02: serial accounting updates, persisted postings, materialised equals replayed balances
  - Balanced journal: zero unbalanced (journal, currency) groups after every operation
  - ADR-0003: BigInteger exact totals; MAX/2 boundary drives MonetaryOverflowException
  - ADR-0005: canonical_hash unchanged after later operations; reversals are exact negations with identical dimensions
  - ADR-0006: same-hash retry returns the original journal; one COMPLETED command and one JournalPosted event per journal with deterministic ids
  - ACC-19 (uncited): control projection equals an independent SQL aggregation
- **Fixtures:** BASE_SEED 0xCB20260830L, 32 seeds x 128 operations; every id derived from (seed, index, salt); all 32 books coexist in one database; operation mix 45% post, 20% same-hash retry, 10% different-hash retry, 15% reversal, 10% unbalanced by one unit.
- **Determinism and timing:** Fully deterministic, single-threaded, no sleeps. O(journals) queries per operation, so O(n^2) per seed; expect minutes. One failure stops all remaining seeds.

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `generatedHistoriesPreserveEveryAccountingInvariant` (L69) | 4,096 generated operations: predict with the model, execute, apply, assert cardinality and every invariant; replays the failing prefix once on failure | per-op expected exception class or success; exact count deltas; after every op: per-currency balance zero, replay == materialised == model, projection == independent aggregation == model, one event and one COMPLETED command per journal, outbox id set equality, hashes and reversal links unchanged, reversal line multiset equals original | strong |
| `databaseReversalComparisonRejectsDimensionMismatch` (L86) | valid reversal with one line's dimensions altered, via postTrustedReversal | LedgerPersistenceException whose cause names constraint reversal_exact_negation; invariants still hold | strong |
| `reversalCandidatesExcludeCorrectionsAndAlreadyReversedOriginals` (L130) | model-only: two originals and one reversal | reversibleJournalIds == [second original] | medium |

**QA observations**

1. One monolithic test; a @ParameterizedTest over seeds would give 32 independent results. The final executed == 4096 assertion (line 79) is tautological.
2. The model is a second implementation: a shared misconception passes. The outbox id derivation is copied verbatim from JdbcLedgerRepository (ReferenceLedgerModel line 270).
3. Generator is narrow: two accounts, one currency, two-line journals only; no reversal of a reversal, period close, chart rotation, policy change or ACC-20 limits; no concurrency.
4. RetryDifferentHash changes several fields at once (amount, ids, narration) so it never isolates one field.
5. SubmitUnbalanced is always off by exactly one unit; same-sign, zero and empty shapes are absent.
6. No override hook to widen seeds or run one failing seed in isolation.
7. The model-only test boots Quarkus for no reason.

### ReversalServiceIT

`src/test/java/com/corebanking/funds/application/ReversalServiceIT.java` (1373 lines)

- **Type:** Failure injection
- **Harness:** @QuarkusTest with Dev Services PostgreSQL; injected DataSource, PostingService, ReversalService; dynamic-proxy DataSource chains for interleaving, timeout injection and read counting; one hand-wired PostingService overriding postTrustedReversal with a CyclicBarrier; a virtual thread as mutator.
- **Component under test:** application: ReversalService.reverse (REPEATABLE READ read-only preflight, hash admission, limits, derived reversal posting ids), PostingService.post and postTrustedReversal, both hashers, ReversalRequest and PostingLine validation, JournalValidator; DB guards posting_requires_in_progress_command and one_reversal_per_original_idx; narration CHECK.
- **Invariants and acceptance IDs:**
  - ACC-20: closed-period rejection, exact linked reversal into the open period, one reversal per original, no reversal of a reversal, 256/32/8,192 limits, reversible amount domain
  - ACC-32: same-hash replay, different-hash conflict, stale-hash matrix, same-content replay before governance change, in-progress owner takeover
  - ACC-25 (uncited): every preflight read carries a finite timeout and failure restores the connection
  - ADR-0005: original snapshot unchanged; appends rejected; exact negation with negateExact
  - ADR-0006: stored result replay; command and outbox counts
- **Fixtures:** ORIGINAL_COMMAND uuid(200), journal uuid(201), REVERSAL_COMMAND uuid(210), NEXT_PERIOD uuid(211); exampleA +100,000 / -100,000 with dimensions; closeOriginalAndOpenNextPeriod; insertIncompleteOriginal; appendBalancedLinesToOriginal; JournalSnapshot and PostingSnapshot oracles; DatabaseCounts over four tables.
- **Determinism and timing:** No sleeps or random data; latches and barriers 5 to 10 s; 256 and 258-line journals. The read-count test pins the preflight at exactly 5 statements.

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `reversesClosedPeriodJournalInCurrentPeriodWithoutChangingOriginal` (L98) | post in Jan, close Jan, open Feb, reverse twice | first == replay; original header and postings unchanged; reversal period, times, link, type, narration, ids exact; exact negations with sequence +1; counts 2/4/2/2 | strong |
| `explicitReversalValueDateMustBelongToCurrentPeriod` (L134) | value date in the closed period | InvalidJournalException; counts (command count omitted) | medium |
| `ordinaryPostingToClosedPeriodRemainsRejected` (L157) | post into the closed period | AccountingPeriodClosedException; zero rows | strong |
| `rejectsReversalOfReversalWithoutWritingAnything` (L171) | reverse the reversal | InvalidJournalException; counts unchanged | medium |
| `longMinimumAmountIsRejectedBeforeItCanBecomeAnIrreversibleFact` (L197) | PostingLine with Long.MIN_VALUE | IllegalArgumentException from the constructor; DB counts (tautological, nothing touched the DB) | medium |
| `completedReversalPreflightWinsBeforeLaterPeriodPolicyChanges` (L211) | reverse, close Feb, replay | stored == replay; counts unchanged | strong |
| `usesCurrentBookPolicyForCorrectionInsteadOfHistoricalPolicy` (L229) | bump policy to 2, reverse | reversal policy_version 2 | strong |
| `completedCommandPreflightWinsBeforeInvalidOriginalLookup` (L253) | replay, then same id with a missing original and with the reversal as original | replay equals stored; both changed calls IdempotencyConflictException | strong |
| `staleReversalHashConflictsForEveryFinancialRequestFieldWithoutDatabaseWork` (L286) | 8 single-field mutations under the baseline hash | IdempotencyConflictException each; counts unchanged | strong |
| `originalMustBelongToACompletedCommand` (L334) | original planted IN_PROGRESS | InvalidJournalException; no reversal command row | medium |
| `rejectsJournalAbovePocPostingLimitWithoutWritingCorrection` (L351) | 258 lines through postingService.post | InvalidJournalException; counts unchanged (no reversal attempted) | medium |
| `reversesAJournalAtTheExactTwoHundredFiftySixPostingBoundary` (L380) | 256-line original reversed | 256 postings each; multiset exact negation; counts 2/512 | strong |
| `rejectsPostingAbovePocDimensionLimitWithoutWritingCorrection` (L418) | 33 dimensions through postingService.post | InvalidJournalException; counts unchanged | medium |
| `requestRequiresLowercaseSha256AndUtf8BoundedReason` (L440) | 512-byte UTF-8 reason accepted; bad hashes; 513-byte reason | octet_length 512; three IllegalArgumentExceptions | strong |
| `originalHeaderAndPostingsComeFromOneRepeatableReadSnapshot` (L474) | pause preflight after the header read; concurrent append attempt | mutator failed with SQLException; 2 postings each | medium |
| `everyReadHasFiniteTimeoutAndFailureRollsBackAndRestoresConnection` (L514) | first preflight read throws 57014 | LedgerPersistenceException; timeout set; events contain readOnly, REPEATABLE_READ, rollback, restore; last event close | strong |
| `everyReversalReadSetsTimeoutBeforeExecution` (L541) | counts preflight reads on a successful reversal | exactly 5 prepared and executed; proxy fails any read without a positive timeout | strong |
| `matchingInProgressCommandContinuesButDifferentHashConflictsFirst` (L562) | planted IN_PROGRESS reversal command with matching hash | different content conflicts; matching request completes; row COMPLETED | strong |
| `rejectsPostCompletionPostingAppendThatBreaksCanonicalFact` (L589) | balanced append plus projection updates by SQL | bare SQLException; counts unchanged | medium |
| `postingAndReversalHashesMatchPersistedMicrosecondBookingTimes` (L601) | microsecond and sub-microsecond booking times for posting and reversal | sub-microsecond rejected; persisted times and canonical hashes equal the returned values | strong |
| `acceptsDimensionJsonAtExactPocByteLimit` (L651) | 8,192-byte dimension JSON, then reverse | octet_length 8,192 on the original; reversal has 2 postings | medium |
| `rejectsOversizedDimensionValueBeforeExpansion` (L667) | 8,184 x's via postingService.post | InvalidJournalException | medium |
| `rejectsOversizedDimensionKeyBeforeExpansion` (L677) | 8,185-char key | InvalidJournalException | medium |
| `existingReversalIsRejectedBeforePostingAnotherCommand` (L687) | second reversal with a new command id | InvalidJournalException; counts unchanged | medium |
| `unrelatedDatabaseConstraintRemainsAPersistenceFailure` (L709) | pre-occupies the derived reversal posting id | LedgerPersistenceException; counts unchanged | strong |
| `concurrentDistinctCommandsCreateExactlyOneReversal` (L749) | two commands past preflight on a barrier | one PostingResult and one InvalidJournalException; one REVERSAL row; counts 2/4/2/2 | strong |

**QA observations**

1. The snapshot-coherence test (line 474) is not load-bearing: the concurrent append is rejected by the DB, so nothing changes between the two reads and REPEATABLE READ is never exercised.
2. rejectsPostCompletionPostingAppend asserts a bare SQLException (line 589); a typo in the fixture SQL would pass. Assert posting_requires_in_progress_command.
3. Ten type-only InvalidJournalException assertions cannot distinguish 'already reversed' from 'reversal of a reversal' from 'period invalid'.
4. Four limit tests exercise PostingService, not ReversalService (lines 351, 418, 667, 677); the reversal-side caps in ReversalService (reads capped at MAX+1, byte re-check) are effectively unreachable and unproved.
5. The Long.MIN_VALUE test is a unit test on PostingLine wrapped in DB counts that cannot fail (line 197).
6. 'Without database work' is asserted by row counts, which cannot show that no connection was opened; a recording proxy already exists in the class.
7. Materialised balances and control projections after a reversal are never asserted; the outbox payload and V2 scheme tags are never inspected.
8. The race test hand-builds JdbcLedgerRepository with the no-arg constructor (line 754); confirm it is production-equivalent.

### AcceptanceHardeningIT

`src/test/java/com/corebanking/funds/application/AcceptanceHardeningIT.java` (1803 lines)

- **Type:** Integration (PostgreSQL)
- **Harness:** @QuarkusTest with Dev Services PostgreSQL; injected DataSource and PostingService, but most methods issue raw SQL through TestPostingStack.execute; races staged with 2-thread pools and pg_stat_activity / pg_blocking_pids introspection; assertConstraint matches PostgreSQL constraint names.
- **Component under test:** V005 and V006 triggers, CHECKs and unique indexes on journal, posting, idempotency_command, chart_version, ledger_account_chart_mapping, ledger_account, product_version, product_definition; the owner-only funds.rotate_chart_version; PostingService.post as the service half of dual guards. Asserts 36 named constraints.
- **Invariants and acceptance IDs:**
  - ACC-01: book-local dates, period ownership and status, current policy, effective chart, DRAFT-first lifecycle, complete and frozen mappings, governed atomic rotation, chart-before-book lock order, mapping races five repetitions each
  - ACC-20: exact linked reversal, one reversal per original, 256/32/8,192 and the reversible domain at the database boundary
  - ACC-40 / ACC-42: product kind and finance principle immutable on product_version; historical accounts keep classification
  - ADR-0004: every guard re-proved in the database, not only in Java
  - ADR-0005 / ADR-0006 / ADR-0007: reversal linkage and negation; completed command result consistency and hash-scheme guards; ledger identity stable across chart versions
- **Fixtures:** TestPostingStack resetAndSeed per test; all ids new UUID(0, n) in per-test blocks; direct-SQL helpers for headers, inexact reversals, unbalanced single lines, 257-line journals (generate_series), exact reversals, candidate charts; race helpers with book-row blockers; @RepeatedTest(5) on four methods.
- **Determinism and timing:** 20 staged race executions per class run (four @RepeatedTest(5)); busy-poll pg_stat_activity loops with 5 s deadlines opening a new connection per iteration; Future.get(5 s); no sleeps or random values.

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `productClassificationLivesOnTheImmutableVersion` (L68) | column inspection, v2 version insert, UPDATE attempts | columns absent on definition and present on version; account still SAVINGS/CONVENTIONAL; constraints product_version_immutable and product_definition_identity_immutable | strong |
| `newFactsCannotClaimLegacyHashSchemes` (L120) | V004_OPAQUE command and V004_V1 journal inserts | new_command_hash_scheme; new_journal_hash_scheme | strong |
| `completedCommandsMustIdentifyTheirOwnExactJournalResult` (L154) | foreign and wrong-hash completions | completed_command_result_consistency twice; journal count 0 | strong |
| `serviceAndDatabaseUseTheLagosBookingDateForTheSelectedOpenPeriod` (L172) | 23:30Z on 31 Jan (1 Feb in Lagos) via service and SQL | InvalidJournalException (type only); journal_booking_date_period | strong |
| `serviceAndDatabaseRejectBookingAndValueDatePeriodDivergence` (L193) | booking 31 Jan, value 1 Feb | InvalidJournalException; journal_value_date_period | strong |
| `databaseRejectsWrongBookAndClosedPeriodsAtTheJournalBoundary` (L211) | foreign period; closed period | journal_period_book; journal_open_period | strong |
| `databaseIndependentlyEnforcesCurrentPolicyAndEffectiveBookChart` (L234) | policy 2; foreign chart; retired chart | journal_current_policy; journal_chart_governance; journal_effective_chart | strong |
| `chartRotationPinsHistoricalJournalsAndRequiresOneMappingVersion` (L271) | post, draft chart 2, direct ACTIVE, rotate, mutate mappings, post | one_active_chart_per_book_idx; ledger_account_chart_mapping_frozen x3; historical journal keeps chart 1 and classifies via it | strong |
| `chartActivationRequiresCompleteMappingsAndDraftCreation` (L355) | post-activation OPEN account; direct ACTIVE chart; partial rotation; complete and rotate | active_chart_account_onboarding_deferred; chart_version_must_start_draft; chart_mapping_incomplete; subsequent post lands on the new chart | strong |
| `governedChartRotationRejectsHistoricalAndInvalidEffectiveBoundsAtomically` (L413) | effective_at equal to a booking, before activation, in the future | chart_rotation_historical_cutoff; chart_rotation_effective_bounds x2; statuses unchanged | strong |
| `governedChartRotationRequiresDistinctExistingLifecycleRowsForOneBook` (L447) | one call per argument guard | nine distinct chart_rotation_* constraints; statuses unchanged | strong |
| `governedChartRotationDoesNotDeadlockWithCandidateMappingInsert` (L527) | @RepeatedTest(5): mapping INSERT raced against rotation with a book-row blocker | no 40P01; rotation succeeded (RETIRED/ACTIVE at 2026-01-10); pg_blocking_pids proves rotation waited on the mapping session | strong |
| `governedChartRotationDoesNotDeadlockWithCandidateMappingUpdate` (L558) | @RepeatedTest(5): same race with an UPDATE | no deadlock; rotation succeeds; account_class EQUITY persisted | strong |
| `governedChartRotationDoesNotDeadlockWithCandidateMappingDelete` (L583) | @RepeatedTest(5): same race with a DELETE | rotation fails 23514 chart_mapping_incomplete; statuses unchanged | strong |
| `directJournalGovernanceWaitsForChartBeforeBookDuringRotation` (L619) | blocker holds the candidate chart; rotation and a direct journal insert stall | no 40P01 on either side; rotation succeeds; journal insert fails journal_effective_chart | medium |
| `repeatableReadGovernedRotationSerializesAgainstAConcurrentMappingDeletion` (L785) | uncommitted DELETE; rotation under REPEATABLE READ | 40001; candidate DRAFT; mapping count 1 | strong |
| `repeatableReadActivationSerializesAgainstConcurrentOpenAccountCreation` (L818) | uncommitted OPEN account; direct activation under REPEATABLE READ | 40001; candidate DRAFT; account exists; no mapping | strong |
| `readCommittedGovernedRotationRevalidatesAfterConcurrentMappingDeletion` (L854) | same delete race under READ COMMITTED | 23514 chart_mapping_incomplete; candidate DRAFT | strong |
| `repeatableReadChartCreationSerializesAgainstAnEarlierOpenAccountInsert` (L884) | @RepeatedTest(5): whole chart lifecycle under REPEATABLE READ against an uncommitted account | 40001; chart absent; account present | strong |
| `databaseRejectsDisguisedAndInexactReversalFacts` (L971) | linked non-REVERSAL type; REVERSAL with +/-50 lines | journal_reversal_linkage_check; reversal_exact_negation at commit | strong |
| `databaseRejectsEveryIrreversibleDirectPostingDomain` (L993) | Long.MIN_VALUE; 33 dimensions; 8,193 bytes; numeric value | four named posting_* constraints | strong |
| `databaseRejectsTheTwoHundredFiftySeventhDirectPosting` (L1019) | 257 balanced lines | journal_reversible_posting_count; zero rows | strong |
| `databaseAllowsOneExactReversalAndRejectsAnySecondDirectLink` (L1030) | exact direct reversal then a second link | one_reversal_per_original_idx; counts 2/4 | strong |

**QA observations**

1. insertDirectBalancedJournal (lines 1453 to 1492) is dead code and reuses ids other tests use.
2. Service-side assertions are type-only (lines 180, 200); the DB oracle is stronger than the service oracle in the dual-guard tests.
3. newFactsCannotClaimLegacyHashSchemes never asserts the default scheme of the successfully inserted command is V2 (line 130).
4. The successful rotation on the other book at line 512 is unasserted.
5. The chart-before-book proof at line 619 shows absence of 40P01 in one interleaving without pg_blocking_pids; a journal waiting on the book row would look the same.
6. No pg_terminate_backend safety net after cancelled futures; a hung backend could block the next TRUNCATE.
7. Gaps: limits proved one-over only (257, 33, 8,193); the balance trigger is assumed rather than proved here; mixed-currency and DRAFT-chart posting are not covered; approval_reference is never negatively tested.
8. Timezone dependence is data-driven only; a second book in a negative-offset zone would harden the Lagos test.

### AccountingProofServiceIT

`src/test/java/com/corebanking/funds/application/proof/AccountingProofServiceIT.java` (518 lines)

- **Type:** Integration (PostgreSQL)
- **Harness:** @QuarkusTest with Dev Services PostgreSQL; injected DataSource, PostingService, ReversalService, AccountingProofService; seeding and corruption by raw JDBC including SET ROLE funds_migrator.
- **Component under test:** application.proof: AccountingProofService (trialBalance, controlAccount), TrialBalanceProof and ControlAccountProof records, JdbcAccountingProofRepository; indirectly PostingService, ReversalService and the projection table ownership.
- **Invariants and acceptance IDs:**
  - ACC-19: per-book per-currency trial balance, independently sourced control proofs, corruption detection
  - Balanced journal: every trial proof asserts balanced()
  - ADR-0003: BigInteger aggregates beyond long stay exact
  - ADR-0005: the proof trusts postings, not the mutable projection
- **Fixtures:** Book uuid(1), chart uuid(2) activated by direct UPDATE, period uuid(3), ten INTERNAL ASSET/DEBIT accounts; fixed booking time; V2 command hashes; extra books uuid(100..) and uuid(200..) per test; 14-table truncate before and after.
- **Determinism and timing:** Deterministic, no sleeps; two truncates per test.

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `provesInflowTransferAndReversalAtEachCurrentCutoff` (L86) | inflow, transfer, reversal of the inflow | exact debit and credit totals at each cutoff (100,000 / 125,000 / 225,000); balanced; CUSTOMER-DEPOSITS source == projection with difference 0 | strong |
| `projectionCorruptionReportsExactDifferenceWithoutChangingImmutableTrialProof` (L106) | adds +37 to the projection as funds_migrator | trial proof unchanged; source -80,000, projection -79,963, difference -37 | strong |
| `missingProjectionForMappedSourceFailsClosedWhileEmptySourceUsesZero` (L145) | deletes the projection row | IllegalStateException (type only); NEVER-POSTED difference 0 | medium |
| `exactSourceSequenceAcceptsUnrelatedLaterJournalAndRejectsRewrittenProjectionSequence` (L169) | unrelated later journal; rewrites latest_journal_sequence twice | valid proof -50/-50; IllegalStateException after each rewrite; trial still balanced | strong |
| `laterNetZeroMappedActivityCannotBeHiddenByRewindingProjectionSequence` (L207) | net-zero transfer then sequence rewind | IllegalStateException | medium |
| `controlProjectionProofRejectsHistoricalCutoffAfterLaterMappedActivity` (L226) | asks for the control proof at an older cutoff | IllegalArgumentException containing 'current cutoff'; trial balanced; control -50 at current | strong |
| `isolatesBookAndCurrencyAndKeepsAggregatesBeyondLongExact` (L248) | USD book with MAX then +10; NGN 10 in the base book | USD totals MAX + 10 as BigInteger; NGN 10; cross-book and cross-currency 0 | strong |
| `controlProofCoordinatesIsolateBookCurrencyAndControlCode` (L290) | four journals across book, currency and control coordinates | four exact source == projection values with difference 0 | strong |
| `trialProofHandlesOrderedLongMinimumWithoutNegationOverflow` (L328) | MIN + 1 against MAX | totals MAX; balanced | strong |
| `validatesEmptyCutoffInputsAndProofConsistency` (L345) | cutoff 0 on empty tables; null, negative and blank inputs; record constructors | exact zero records; NPE and IAE per input; constructor rejects inconsistent balanced/difference | strong |

**QA observations**

1. All accounts are ASSET/DEBIT, so a proof bug in credit-normal classification would be invisible.
2. Three rejections are IllegalStateException type-only (lines 157, 189, 200, 220).
3. Chart activation bypasses rotate_chart_version, so the proof never runs under the governed path.
4. SET ROLE funds_migrator (line 113) assumes the test login holds that role; not asserted beforehand.
5. Command seed 80 and posting seeds 81 to 86 overlap across tests; safe only because of truncation.

### LedgerConstraintIT

`src/test/java/com/corebanking/funds/infrastructure/postgres/LedgerConstraintIT.java` (1171 lines)

- **Type:** Integration (PostgreSQL)
- **Harness:** @QuarkusTest with Dev Services PostgreSQL; injected AgroalDataSource; raw JDBC only, bypassing PostingService entirely; savepoint-scoped assertSqlState and assertConstraintViolation; single-thread executor for two-session races; SET ROLE, SET SESSION AUTHORIZATION.
- **Component under test:** Migrated schema: deferred journal_balance and posting_balance triggers, posting_reference_consistency, journal_governance, identity-immutability triggers, ledger_account_chart_mapping_frozen, journal_immutable and posting_immutable (reject_ledger_mutation), posting_requires_in_progress_command, completed_idempotency_immutable; V004/V005 grants; journal sequence; the two lock functions.
- **Invariants and acceptance IDs:**
  - ACC-01: per-currency balance and reference consistency enforced at commit
  - ACC-24: funds_app cannot mutate, disable triggers, redefine functions or escalate; a real proof-reader session reads only proof columns
  - ADR-0004: PostgreSQL enforces invariants without the application
  - ADR-0005: committed journals and postings are immutable
  - ADR-0006: completed idempotency results are immutable
- **Fixtures:** Fixed uuid(1..8) graph with two NGN customers and one USD internal account; second book uuid(21..23), alternate chart uuid(24); command uuid(30), journal uuid(31); withCommittedJournal and withFinalizedJournal helpers truncate before and after; workers return SQLException rather than throwing.
- **Determinism and timing:** Two race tests with 5 s deadlines, busy loops opening a new connection per iteration; TRUNCATE before and after most tests, so not parallel-safe; SET SESSION AUTHORIZATION needs superuser.

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `rejectsCrossCurrencyNetZeroJournalAtCommitAfterBothPostingInsertsSucceed` (L83) | +100 NGN and -100 USD, both inserts succeed, then commit | 2 postings before commit; commit throws 23514 | strong |
| `rejectsPostingCurrencyDifferentFromAccountCurrency` (L98) | USD posting on an NGN account | 23514 | strong |
| `rejectsPostingAccountFromAnotherBook` (L112) | posting to a second-book account | 23514 | strong |
| `rejectsJournalLegalEntityDifferentFromBookLegalEntity` (L127) | foreign legal entity | 23514 | strong |
| `rejectsBookLegalEntityChangeAfterJournalCommit` (L138) | UPDATE book legal entity | 55000 | strong |
| `rejectsAccountCurrencyChangeAfterJournalCommit` (L147) | UPDATE account currency | 55000 | strong |
| `rejectsAccountControlMappingChangeAfterJournalCommit` (L154) | UPDATE mapping control code | 55000 | strong |
| `rejectsAccountChartVersionChangeAfterJournalCommit` (L163) | UPDATE mapping chart_version_id | 55000 | strong |
| `rejectsCoherentAccountBookAndChartMoveAfterJournalCommit` (L175) | UPDATE account book_id only | 55000 | strong |
| `allowsOperationalAccountStateChangeAfterJournalCommit` (L188) | UPDATE status CLOSED with closed_at | row matches both values | strong |
| `rejectsCommittedJournalUpdateAsImmutable` (L206) | UPDATE journal narration | 55000 | strong |
| `rejectsCommittedJournalDeleteAsImmutable` (L213) | DELETE journal | 55000 | strong |
| `rejectsCommittedPostingUpdateAsImmutable` (L220) | UPDATE posting dimensions | 55000 | strong |
| `rejectsCommittedPostingDeleteAsImmutable` (L228) | DELETE posting | 55000 | strong |
| `allowsPostingAssemblyOnlyWhileCommandIsInProgress` (L235) | postings under IN_PROGRESS then after COMPLETED | count 2; then 55000 | strong |
| `rejectsAppendWhoseCompletedJournalWasUncommittedAtTriggerLookup` (L259) | creator holds an uncommitted COMPLETED command while a worker appends | 55000 with constraint posting_requires_in_progress_command; 1 journal, 2 postings, sum 0 | strong |
| `visibleInProgressAssemblySerializesBeforeCompletion` (L323) | session A holds new postings while a worker completes | worker observed lock-waiting; completes after A commits; COMPLETED with 4 postings | strong |
| `rejectsDuplicateCommandIdWithDifferentRequestHash` (L368) | second command row with the same id | 23505 (primary key) | strong |
| `rejectsDuplicateAccountSequence` (L378) | two postings with account_sequence 1 | 23505 | strong |
| `rejectsCompletedIdempotencyWithoutJournalResultAndCompletionTimestamp` (L393) | COMPLETED row with nulls | 23514 | strong |
| `commitsJournalPostingsBalancesControlProjectionIdempotencyAndOutboxAtomically` (L399) | hand-inserts every row of a posting and commits | counts, per-currency sum 0, balances 500/-500, projection, COMPLETED command, one outbox row (all read back from fixture values) | medium |
| `runsLedgerConstraintsOnPostgreSql18Point6` (L491) | SHOW server_version | startsWith 18.6 | strong |
| `applicationRoleCannotBypassLedgerControls` (L510) | as funds_app performs the legitimate write set then probes bypasses; SET SESSION AUTHORIZATION; as migrator; as proof_reader | legitimate work succeeds; setval, last_value, outbox publish columns, UPDATE/DELETE, DISABLE TRIGGER, ADD COLUMN, CREATE OR REPLACE FUNCTION, period close all 42501; completed result UPDATE 55000 completed_idempotency_immutable; SET ROLE funds_migrator denied; migrator still cannot rewrite completed rows; proof_reader reads proof columns only and cannot escalate | strong |

**QA observations**

1. The 'Atomically' test (line 399) is a hand-seeded happy path with no failure injected; real atomicity evidence lives in PostingServiceIT and the crash tests.
2. Constraint names are asserted in only three places (lines 296, 636, 654 to 658); all other 55000 and 23514 tests accept any trigger raising that state.
3. rejectsCoherentAccountBookAndChartMove updates only book_id; the mapping is not moved (lines 175 to 185).
4. rejectsDuplicateCommandIdWithDifferentRequestHash proves a primary key, not hash semantics (line 368).
5. As funds_app, journal and posting mutations fail with 42501 before the immutability triggers are reached; trigger coverage is owner-path only.
6. Gaps: journal_governance is probed only for legal-entity mismatch; chart-before-book lock order, ACC-20 limits and reversal constraints are not covered here.
7. Duplicate PostgreSQL version pin with MigrationIT, both printing to stdout.

### MigrationIT

`src/test/java/com/corebanking/funds/infrastructure/postgres/MigrationIT.java` (1171 lines)

- **Type:** Integration (PostgreSQL)
- **Harness:** @QuarkusTest with Dev Services PostgreSQL migrated by Flyway; injected AgroalDataSource; inTransaction helper always rolls back; savepoint-scoped assertSqlRejected (any of seven SQLSTATEs) and assertSqlStateRejected; SET ROLE across the three capability roles; one two-connection lock test with truncation.
- **Component under test:** Migrated schema: reference tables, V003.2 finality trigger and single-reversal index, V004 role model (ownership, ACLs, default privileges, SECURITY DEFINER allow-list, pinned search_path), V005 proof-reader grants, V006 rotate_chart_version denial, funds.is_valid_nuban, identifier cardinality and immutability triggers, product-version binding CHECKs, period exclusion; the text of V004__application_roles.sql.
- **Invariants and acceptance IDs:**
  - ACC-24: role model: ownership, exact grants, function allow-list
  - ACC-38: NUBAN check digits, identifier cardinality and immutability
  - ACC-40 / ACC-42: product-version binding constraints
  - ADR-0004 / ADR-0007: PostgreSQL authoritative; ledger identity versus addresses
  - MIGRATION-ROLES.md: fail-closed bootstrap, migrator ownership, proof-reader as an external role
- **Fixtures:** Fixed uuid(1..7) and uuid(20..22); insertDraftReferenceGraph with two CUSTOMER accounts and one CONTROL account; periods inserted per test; no @BeforeEach; SQL built with String.formatted.
- **Determinism and timing:** No sleeps; one 250 ms lock wait; rollback isolation except the lock test which truncates reference tables; needs a login able to SET ROLE to all three roles.

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `createsEveryAccountingReferenceTable` (L53) | lists information_schema tables | containsAll of 8 reference names | weak |
| `runsOnPostgreSql18Point6` (L80) | SHOW server_version | startsWith 18.6 | strong |
| `installsJournalFinalityTriggerAndSingleReversalIndex` (L89) | queries pg_trigger and pg_indexes | one trigger posting_requires_in_progress_command; one index whose definition matches a WHERE predicate pattern | medium |
| `installsHardenedRoleOwnershipAndExactPrivileges` (L125) | about 30 catalog assertions, funds_app denials, migrator probe objects | three NOLOGIN roles without memberships; every object owned by funds_migrator; SECURITY DEFINER iff in a 10-function allow-list; pinned search_path; no PUBLIC ACLs; exact column grants for funds_app and funds_proof_reader; exactly five executable functions for funds_app; 42501 on chart UPDATE and rotate_chart_version | strong |
| `roleBootstrapIsFailClosedAndNeverAltersExistingClusterRoles` (L423) | reads V004 as text | exactly three CREATE ROLE lines; no IF NOT EXISTS, ALTER ROLE funds_, pg_auth_members or REVOKE pattern | weak |
| `proofReaderCanRunExactProofsButCannotReadOperationalOrPolicyPayloads` (L447) | proof-shaped queries on an empty DB; four denied SELECTs | both queries return 0/0; four 42501 | medium |
| `rejectsLedgerCurrencyLongerThanThreeCharacters` (L507) | currency NGNN | 22001 | strong |
| `rejectsUnknownLedgerNormalBalance` (L517) | SIDEWAYS | 23514 | strong |
| `rejectsLedgerAccountWhoseBookDoesNotExist` (L527) | missing book | 23503 | strong |
| `rejectsChartVersionFromAnotherBook` (L537) | cross-book chart mapping | 23503 | strong |
| `acceptsCustomerAccountWithProductVersionBinding` (L549) | reads back the fixture binding | equals PRODUCT_VERSION_ID | weak |
| `rejectsCustomerAccountWithoutProductVersionBinding` (L564) | CUSTOMER with null version | 23514 | strong |
| `rejectsProductVersionBindingForNonCustomerAccount` (L574) | CONTROL with a version | 23514 | strong |
| `rejectsProductVersionUpdate` (L584) | UPDATE approval_reference | any of seven SQLSTATEs; value unchanged | medium |
| `rejectsUnreferencedProductVersionDelete` (L600) | DELETE the unreferenced version | any of seven; count 1 | medium |
| `rejectsCustomerProductVersionBindingReplacement` (L615) | UPDATE product_version_id | any of seven; binding unchanged | medium |
| `rejectsOverlappingAccountingPeriodsForOneBook` (L634) | two periods sharing a day | any of seven (23P01 not pinned) | medium |
| `acceptsNonOverlappingAccountingPeriodsForOneBook` (L656) | Jan and Feb | count 2 | medium |
| `sqlNubanValidatorAcceptsPublishedAndSyntheticFixtures` (L676) | two valid NUBANs | true, true | strong |
| `sqlNubanValidatorRejectsMutatedCheckDigit` (L684) | one mutated digit | false | strong |
| `rejectsBadNubanCheckDigitAtTableBoundary` (L690) | bad check digit insert | any of seven | medium |
| `rejectsSameActiveIdentifierScopeForTwoAccounts` (L700) | same NUBAN on two accounts | any of seven | medium |
| `rejectsSecondActivePrimaryNubanForOneAccount` (L714) | two primaries | any of seven | medium |
| `acceptsSameProviderAliasFromDifferentProvidersOnOneAccount` (L728) | same alias, two providers | count 2 | medium |
| `rejectsProviderAliasWithInstitutionCode` (L745) | provider alias with institution | any of seven | medium |
| `rejectsSameActiveProviderAliasWithinOneProvider` (L756) | same alias and provider on two accounts | any of seven | medium |
| `retainsSyntheticNubanAsSimulatorOnlyData` (L770) | inserts the simulator fixture | routing_scope SIMULATOR_ONLY; no INTERNAL/EXTERNAL row | weak |
| `productionMigrationDoesNotSeedSyntheticNuban` (L790) | counts synthetic rows | 0 on an empty table | weak |
| `rejectsEveryIbanIdentifier` (L798) | IBAN scheme | any of seven | medium |
| `rejectsExternalAddressForNonCustomerAccount` (L808) | EXTERNAL alias on CONTROL | any of seven | medium |
| `rejectsCustomerScopeChangeAfterExternalIdentifierExists` (L818) | scope change with an alias present | any of seven; scope unchanged | medium |
| `rejectsAccountScopeChangeWithoutIdentifiers` (L837) | CONTROL to INTERNAL | any of seven; unchanged | medium |
| `externalIdentifierInsertLocksLedgerRowAgainstConcurrentUpdate` (L858) | session 1 inserts an alias and holds; session 2 updates under a 250 ms lock_timeout | any of seven (55P03 not pinned) | weak |
| `acceptsInternalAddressForNonCustomerAccount` (L889) | INTERNAL alias on CONTROL | reads back INTERNAL | weak |
| `rejectsIdentifierUpdate` (L904) | UPDATE lifecycle_status | any of seven; still ACTIVE | medium |
| `rejectsIdentifierDelete` (L923) | DELETE identifier | any of seven; count 1 | medium |

**QA observations**

1. Seventeen tests use assertSqlRejected, which accepts any of 22001, 23503, 23505, 23514, 23P01, 55000, 55P03; a foreign key firing instead of the intended trigger passes. No test in this class asserts a constraint name.
2. The concurrency claim at line 858 is unproven: 55P03 is not pinned.
3. roleBootstrapIsFailClosed tests the migration source text, case-sensitively; no test re-runs V004 against a cluster where a role already exists.
4. The single-reversal index is asserted by catalog text, not behaviour (behaviour lives in AcceptanceHardeningIT).
5. Three read-back tests only prove the insert was not rejected (lines 549, 770, 889); line 790 counts an empty table.
6. ACC-40 / ACC-42 gap: product_kind and finance_principle are never the target of an UPDATE here (AcceptanceHardeningIT covers finance_principle).
7. Proof-reader proof shapes run on an empty database; 0/0 cannot detect a wrong join or sign.
8. Role denials use SET ROLE from a privileged login; LedgerConstraintIT uses SET SESSION AUTHORIZATION for the stronger claim.

### MigrationUpgradeIT

`src/test/java/com/corebanking/funds/infrastructure/postgres/MigrationUpgradeIT.java` (755 lines)

- **Type:** Integration (PostgreSQL)
- **Harness:** Plain JUnit 5 (no @QuarkusTest); each test starts its own Testcontainers PostgreSQLContainer and runs Flyway programmatically to targets 004, 005, 006; hand-wired PGSimpleDataSource as the container superuser; 3-arg PostingService and ReversalService.
- **Component under test:** V005 (chart backfill, scheme tags, string-dimension CHECK, product columns moved to product_version) and V006 (rotate_chart_version); the V004-history replay path in JdbcLedgerRepository (V004_OPAQUE / V004_V1 verifiers versus TYPED_V2 / V2).
- **Invariants and acceptance IDs:**
  - ACC-32: same-content replay after period close and policy bump returns the stored result
  - README chart governance: V004 history upgrades without rewriting hashes; new facts use V2 schemes
  - ACC-40 / ACC-42 (uncited): a later product version does not reclassify the existing account
  - ADR-0005 / ADR-0006: new reversal is an exact negation; mutated replays conflict
- **Fixtures:** Fixed uuid(1..55) including V004-shaped rows written by raw SQL (accounts still carrying chart columns, product_definition carrying kind and principle, journals without scheme columns, v004Sha256 hashes, opaque request hash for one command), seeded balances and projections.
- **Determinism and timing:** Two container starts plus full migration chains; the slowest class in the module; deterministic data; couples to a Flyway internal logger class name (line 355).

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `v006PreservesTheAuthenticV005UpgradeWhileAddingGovernedRotation` (L70) | migrate to 004, seed, to 005, inspect, insert product version 2, to 006, inspect, then replay and reverse | Flyway current version at each step; product columns moved; mapping string-agg exact; journal chart backfilled; schemes V004_OPAQUE/V004_V1 and canonical_hash preserved; rotate_chart_version exists; authentic replay returns the V004 journal with its v004Sha256; amount- and chart-mutated replays conflict; legacy reversal replay and reason-mutated conflict; new exact reversal into the next period; replay after chart retirement still equal; new command schemes TYPED_V2/V2; EXCEPT ALL between original and correction lines yields 0; journal count 4 | strong |
| `v005RejectsLegacyDimensionsThatCannotRoundTripAsTypedStringFacts` (L186) | seeds a journal with numeric dimensions before migrating to 005 | FlywayException whose cause chain mentions posting_dimensions_string_values_check | medium |

**QA observations**

1. Runs as the container superuser, not funds_app; a privilege regression in V005 or V006 is invisible here.
2. Balances and projections after upgrade and after the new reversal are never verified against postings; a corrupting backfill would pass.
3. Inserting a second product version cannot change ledger_account.product_version_id (lines 143 to 149); the reclassification claim needs a rejected UPDATE.
4. rotate_chart_version is only asserted to exist (lines 168 to 174); its behaviour on migrated data is untested.
5. Only NGN lines are mutated in the amount-conflict check; a hash scheme ignoring the USD block would pass.
6. queryString and queryLong ignore rows.next(); preservedJournalHash could be null and compare equal to null.

### PostingCrashRecoveryIT

`src/test/java/com/corebanking/funds/application/PostingCrashRecoveryIT.java` (2977 lines)

- **Type:** Crash (child JVM)
- **Harness:** @QuarkusTest with Dev Services PostgreSQL; PGSimpleDataSource probe with 1 s driver timeouts and session lock_timeout 200 ms; child JVM via ProcessBuilder from java.home on the surefire class path; virtual-thread bounded calls (runBounded); JDK proxies for tracking and fault injection; fake clocks and recording doubles for the WorkerHandle self-tests.
- **Component under test:** application: PostingService.post through TestPostingStack, JdbcLedgerRepository including lock_account_mapping_for_posting, PostgresRetryPolicy, the beforeCommit and afterCommitBeforeReturn hooks, both hashers; all ledger tables; PostgreSQL dead-session rollback and 55P03 semantics.
- **Invariants and acceptance IDs:**
  - ACC-32: owner death before commit rolls back and an identical retry posts once; death after commit before return replays the stored result
  - ADR-0006: one COMPLETED command naming the journal and hash; exactly one unpublished JournalPosted event with a deterministic id
  - ADR-0003 / Balanced journal: fixed +100,000 / -100,000 NGN; balance and control deltas exact; independent control delta 0
  - ADR-0005: post-crash snapshot equals the pre-crash snapshot; replay leaves the committed snapshot unchanged
  - Retry hygiene: a 40001 attempt's connection is closed before the next attempt
- **Fixtures:** TestPostingStack resetAndSeed; command ids uuid(40), uuid(50), uuid(60), uuid(61); journal uuid(21) and postings uuid(22), uuid(23) shared with CrashPostingWorker; HaltingObserver halts with exit 91; several injecting datasources; PROCESS_TIMEOUT 10 s; no @RepeatedTest, @Timeout or @Disabled.
- **Determinism and timing:** PROCESS_TIMEOUT 10 s per bounded call with min(3 s, half) reserved for cleanup, so a 2 s call has a 1 s operation budget. awaitCommittedJournal is a connection-per-iteration busy loop. Two child JVM launches per class run. assertHaltedAt requires the REACHED line to be the only non-blank output, so any child stderr warning fails the test.

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `unchangedMvccSnapshotDoesNotClaimRollbackWhileAccountLocksRemainHeld` (L139) | harness self-test: FOR UPDATE on both accounts, snapshot and probe | snapshot unchanged; probe throws 55P03; awaitRollbackComplete returns after rollback | medium |
| `boundedCrashProbesDoNotContaminateQuarkusPoolSessionTimeouts` (L174) | reads pool session timeouts before and after a probe | both 0 before and after (probe uses a separate datasource, so cannot differ) | weak |
| `timedBoundedCallCancelsAndJoinsItsBlockingTask` (L185) | runBounded 100 ms over a never-released latch | AssertionError; task started and interrupted | medium |
| `callerInterruptionCompletesBoundedCleanupBeforeRestoringInterrupt` (L211) | interrupts the caller while cleanup is held | caller waits for cleanup; joins within 1 s; interrupt restored | strong |
| `cleanupFailureRestoresInterruptAndRetainsPrimaryAndSuppressedDiagnostics` (L266) | interrupting cleanup executor plus a release error | executor terminated; interrupt set; failure tree contains both diagnostics | medium |
| `lateJdbcConnectionIsClosedWhenTimeoutWinsAcquisitionRace` (L289) | getConnection blocks until interrupted then returns a proxy | AssertionError; proxy saw abort and close | strong |
| `trackedJdbcProxiesUseIdentitySemanticsAndPreserveWrapperContracts` (L307) | proxy equals, hashCode, toString, unwrap | identity semantics; unwrap returns proxy or delegate as expected; SELECT 1 == 1 | strong |
| `blockingResourceCancellationRunsInOwnedBoundedTaskBeforeMainWorkJoins` (L349) | blocking cancellation supplier under runBounded | exact ordering counter reaches 3; failure tree contains both diagnostics | strong |
| `realRepositoryRetryClosesAndClearsTheFailedAttemptBeforeOpeningTheNextConnection` (L410) | 40001 on the first executed statement through the real stack | 2 connections acquired and recorded, all closed; full assertOneCompletedEffect oracle | strong |
| `boundedCancellationCancelsTheBlockingRealRepositoryStatementAndAbortsItsConnection` (L446) | holder locks both accounts; real service posts under a 2 s bound (effective 1 s) | AssertionError from the bound; lock statement executed, cancelled, connection aborted and closed; snapshot unchanged | medium |
| `workerOperationAndCleanupBudgetsStartFreshAfterLongVisibilityPhase` (L506) | WorkerHandle with a fake clock | exit 91; exact output; budgets [100, 100, 100] | strong |
| `workerCleanupReservesDeletionWithinOneDiminishingDeadline` (L537) | close() with a fake clock | file deleted; budgets [90, 70] | strong |
| `workerCleanupPrestartsOneDeletionAndJoinsItAfterNonDeletionBudgetIsExhausted` (L561) | asserting TimedWaits doubles | latches at 0; exactly one deletion attempt | strong |
| `workerStartFailureStillAttemptsBoundedDeletionAndSuppressesItsFailure` (L636) | ProcessStarter throws after the budget | exact IOException message; deletion attempted with a fresh budget; one identical suppressed | strong |
| `processDeathBeforeCommitRollsBackAndIdenticalRetryPostsOnce` (L683) | child halts in beforeCommit; parent waits for exit and lock release; identical retry in-process | exit 91 with exactly one REACHED line; post-rollback snapshot equals pre-crash; assertOneCompletedEffect on the retry | strong |
| `processDeathAfterCommitReturnsStoredResultWithoutReposting` (L708) | child commits then halts; parent waits for visibility and replays | visible journal uuid(21); exit 91; replay returns the committed id and sequence; snapshot unchanged; assertOneCompletedEffect | strong |

**QA observations**

1. Sixteen tests but only four touch production code; twelve self-test the roughly 2,000-line in-file bounding harness, which belongs in a shared test-support package with its own unit tests.
2. boundedCancellation... (line 446) races its effective 1 s harness budget against the production 1 s lock_timeout; if the server's 55P03 arrives first, LedgerTimeoutException is rethrown and assertThrows(AssertionError) fails. This is the one timing-sensitive production test in the file.
3. The before-commit test cannot distinguish 'rows written then rolled back' from 'hook fired before any write'; nothing proves uncommitted rows existed at halt time.
4. Crash points cover only beforeCommit and afterCommitBeforeReturn; afterIdempotencyAcquired, afterAccountLocks and afterFinancialRowsBeforeOutbox are never a real process death.
5. The retry test injects 40001 on the set_config statement, before any financial statement; a mid-transaction 40001 after locks and inserts is not covered.
6. Inner 8 s CrashProbe deadlines sit inside a 7 s effective outer budget, so their diagnostics are dead; the 3-arg WorkerHandle constructor is unused.
7. The idempotency row state between crash and retry (the interesting ACC-32 window) is never asserted; only the final COMPLETED state is.

### CrashPostingWorker

`src/test/java/com/corebanking/funds/application/CrashPostingWorker.java` (133 lines)

- **Type:** Test support
- **Harness:** public static void main; PGSimpleDataSource from CB_TEST_JDBC_URL, CB_TEST_DB_USER, CB_TEST_DB_PASSWORD; Runtime.halt(91) at the chosen hook.
- **Component under test:** Posts one deterministic command through TestPostingStack.create with a HaltingObserver; asserts nothing itself.
- **Invariants and acceptance IDs:**
  - ACC-32: supplies the halt points and the deterministic command whose TYPED_V2 hash is a function of the command id
- **Fixtures:** JOURNAL_ID uuid(21), postings uuid(22) and uuid(23), POSTING_AMOUNT 100,000, PROVIDER_INFLOW, fixed booking time and dimensions; CrashPoint enum BEFORE_COMMIT and AFTER_COMMIT_BEFORE_RETURN.
- **Determinism and timing:** No socket or login timeout on the child datasource; relies on the parent's 10 s wait and destroyForcibly.

| Method | What it does | What it asserts | Oracle |
|---|---|---|---|
| `main` (L35) | parses command id and crash point, posts, halts | nothing directly; the parent asserts exit 91 and a single REACHED line | n/a |

**QA observations**

1. HaltingObserver ignores the commandId argument, so it would halt on any command posted in the child.
2. Only two of the five observer hooks are selectable crash points.
3. A missing environment variable surfaces as an NPE stack trace, which the parent reports only as an exit-code and output mismatch.
4. The child inherits the Dev Services superuser; nothing asserts it runs as funds_app.

### TestPostingStack

`src/test/java/com/corebanking/funds/application/TestPostingStack.java` (240 lines)

- **Type:** Test support
- **Harness:** Fixture and hand-wired production stack.
- **Component under test:** create(dataSource, observer) wires JournalValidator, CanonicalJournalHasher, JdbcLedgerRepository(validator, hasher, observer), a no-pause PostgresRetryPolicy and the 4-arg PostingService; resetAndSeed and reset (TRUNCATE RESTART IDENTITY CASCADE over 14 tables); the deterministic reference graph with pre-seeded projections.
- **Invariants and acceptance IDs:**
  - Fixture: provider asset uuid(5) DEBIT under PROVIDER-CASH; customer liability uuid(6) CREDIT under CUSTOMER-DEPOSITS; baselines 11,000/3/3 and -17,000/5/5; INDEPENDENT-CONTROL 777 unmapped; CUSTOMER-DEPOSITS projection absent to exercise the insert path
- **Fixtures:** Used by PostingAtomicityIT, PostingCrashRecoveryIT, CrashPostingWorker, PostingTimeoutIT, ReversalServiceIT, AcceptanceHardeningIT, AccountingStateMachineIT.
- **Determinism and timing:** n/a

**QA observations**

1. The class name matches the **/Test*.java surefire include; it has no @Test methods so JUnit reports zero tests, but it is a naming hazard.
2. reset needs TRUNCATE privilege that funds_app never has; tests depend on the Dev Services superuser.
3. The chart is activated by direct UPDATE, bypassing rotate_chart_version, so none of the ITs built on this stack exercise governed activation.
4. Seeded balances are not backed by posting rows; tests must assert deltas, as the comment instructs.
5. The no-pause retry policy means a serialization livelock would hang rather than pass slowly; no timeout is provided here.

### ReferenceLedgerModel

`src/test/java/com/corebanking/funds/testsupport/ReferenceLedgerModel.java` (261 lines)

- **Type:** Test support
- **Harness:** In-memory oracle used only by AccountingStateMachineIT.
- **Component under test:** predict(command) -> NEW_SUCCESS, SUCCESSFUL_RETRY, INVALID_JOURNAL, IDEMPOTENCY_CONFLICT or MONETARY_OVERFLOW; apply(command, result) with AssertionError on disagreement; reversibleJournalIds; expectedOutboxIds; exceptionType mapping.
- **Invariants and acceptance IDs:**
  - Oracle: second implementation of admission ordering: known command (same hash retry, else conflict), unknown account, per-currency imbalance, BigInteger total outside long
- **Fixtures:** n/a
- **Determinism and timing:** n/a

**QA observations**

1. A shared misconception between the model and the kernel passes; the outbox id derivation string is copied verbatim from JdbcLedgerRepository (line 270).
2. The prediction order is claimed to mirror the service; only PostingServiceTest supports that claim.
3. The model omits periods, chart governance, sequences, deadlines and DB reversal constraints by design, so the generator must never propose such cases.
4. apply catches disagreement only in the success direction; the caller must compare exception types for the rejection direction.

### PropertyCases and GeneratedLedgerOperation

`src/test/java/com/corebanking/funds/testsupport/` (86 lines)

- **Type:** Test support
- **Harness:** Seeded generator and sealed operation vocabulary.
- **Component under test:** positiveMinorUnits(seed, n): seven boundaries then n draws in [1, 1e9]; stateMachineMinorUnits(random, index): six boundaries cycled by index mod 7 with a random draw on the seventh slot. Post, RetrySame, RetryDifferentHash, Reverse, SubmitUnbalanced records each carrying their command identity.
- **Invariants and acceptance IDs:**
  - Generator: used by JournalProperties and AccountingStateMachineIT
- **Fixtures:** n/a
- **Determinism and timing:** n/a

**QA observations**

1. Random amounts stop at 1e9; the high half of the long domain is exercised only by fixed boundaries.
2. MAX/2 lands on every seventh-minus-one slot, so overflow outcomes are cheap and frequent and may mask rarer mid-range bugs.
3. No negative or zero amounts, no multi-currency, multi-line, closed-period, cross-book or Long.MIN_VALUE operations exist in the vocabulary.

