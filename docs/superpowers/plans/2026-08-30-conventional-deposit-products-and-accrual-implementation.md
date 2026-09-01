# Conventional Deposit Products and Accrual Implementation Plan

**Proposal:** [Conventional deposit products and accrual](../../../architecture/proposals/README.md#conventional-deposit-products-and-accrual)

**Related ADRs:** [ADR-0002](../../../architecture/adr/0002-centralize-financial-invariants-in-funds-core.md), [ADR-0003](../../../architecture/adr/0003-use-signed-integer-minor-units.md), [ADR-0004](../../../architecture/adr/0004-use-postgresql-as-the-authoritative-ledger.md), [ADR-0005](../../../architecture/adr/0005-use-immutable-journals-and-additive-corrections.md), and [ADR-0006](../../../architecture/adr/0006-couple-idempotency-and-outbox-to-ledger-commit.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans task-by-task. Keep checkbox state here.

**Goal:** Implement versioned savings, current and fixed-deposit liabilities with exact, idempotent accrual, capitalisation, maturity, renewal and early-liquidation accounting.

**Architecture:** Java `funds-core` owns product versions, contracts, eligible-balance facts, calculation and journals. A Go scheduler/orchestrator supplies business-date work and retries, but never calculates or posts money. Jobs page by stable keys and use durable checkpoints; no per-account timers exist.

**Tech Stack:** Java 25/Quarkus/PostgreSQL 18.6; Go/Temporal for schedules; exact integer/rational arithmetic; JUnit generated properties; Testcontainers.

**Prerequisites:** Accounting kernel, product-version foundations, funds control/account restrictions, business date and outbox.

**Acceptance:** INV-17, INV-18, INV-21, INV-29–INV-30; ACC-20, ACC-40–ACC-41.

## Product rules

- Product versions are immutable. A contract remains on its opening version until an explicit, approved migration.
- Money is integer minor units; rates are scaled integers/rationals. Binary floating point is forbidden.
- Every calculation declares eligible balance, day-count convention, rounding boundary/mode, schedule, tax rule and ledger template.
- Savings/current/fixed are distinct behaviours, not labels on one unconstrained record.
- Tax examples are hypothetical configuration; the implementation does not hard-code a Nigerian tax rate.
- Accounting jobs receive a business date and deterministic command key; wall-clock retry timing cannot change value.

## Target files

```text
services/funds-core/src/main/java/com/corebanking/funds/domain/product/
services/funds-core/src/main/java/com/corebanking/funds/application/accrual/
services/funds-core/src/main/java/com/corebanking/funds/application/deposit/
services/funds-core/src/main/java/com/corebanking/funds/infrastructure/postgres/JdbcDepositContractRepository.java
services/funds-core/src/main/resources/db/migration/V007__deposit_contracts.sql
services/funds-core/src/main/resources/db/migration/V008__accrual_facts.sql
services/txn-orchestrator/internal/workflows/deposit_schedule.go
test/acceptance/conventional_deposits.sh
```

---

### Task 1: Model and validate conventional product policies

- [ ] Write failing tests for Savings, Current and Fixed Deposit policy construction. Reject missing day-count/rounding, invalid tier bands, negative rates, overlapping versions and `NON_INTEREST` finance principle.
- [ ] Implement scaled `AnnualRate`, `DayCountConvention` (`ACTUAL_365_FIXED`, `ACTUAL_366`, `THIRTY_360` only when specified), `RoundingPolicy`, eligibility rules, schedules, fee/overdraft and early-liquidation policy types.
- [ ] Store canonical policy JSON plus hash; activate through maker-checker reference. A new edit inserts a new version.
- [ ] Generate boundary cases for zero/one minor unit, rate scale, date ranges, leap day and maximum configured balance.
- [ ] Commit: `feat(products): model conventional deposit policies`

### Task 2: Open deposit contracts atomically

- [ ] Create V007 for `deposit_contract`, maturity instruction and immutable contract-version binding. Customer ledger account plus contract must commit together.
- [ ] Test savings/current/fixed opening; reject inactive/future product versions, currency mismatch, missing maturity for fixed deposit and fixed maturity before value date.
- [ ] Current accounts may declare approved overdraft; savings/fixed default to zero authorised floor unless their product explicitly permits otherwise.
- [ ] Publish `DepositContractOpened` atomically without putting PII/account number in headers.
- [ ] Commit: `feat(funds-core): open versioned deposit contracts`

<a id="accrual-maturity-delivery-detail"></a>
<!-- migration-source: 13.07.02 -->
### Task 3: Implement exact accrual calculation

- [ ] Write unit/generated tests for daily eligible balance, tiers, minimum-balance rule, leap years and negative/zero balances. Use `BigInteger`/exact fraction as oracle.
- [ ] Implement `AccrualCalculator.calculate(contract, balanceFacts, from, to)` returning unrounded rational result, rounded minor units, residual metadata and rule/version evidence.
- [ ] Prove illustrative `₦1,000,000 × 12% × 30/365 = ₦9,863.01` under half-even rounding; do not round each intermediate multiplication/division.
- [ ] Reject overflow before persistence and retain inputs/hash sufficient to reproduce every output.
- [ ] Commit: `feat(funds-core): calculate exact deposit accruals`

### Task 4: Persist idempotent accrual facts and journals

- [ ] Create V008 `accrual_run`, `accrual_fact`, checkpoint and unique `(contract_id, accrual_date, rule_version)` constraints.
- [ ] Write integration tests for debit interest expense / credit accrued-interest payable, zero-accrual policy, closed period, account restriction and duplicate/crash retry.
- [ ] Commit each bounded page under deterministic commands. Partial job failure leaves completed facts intact and resumes after its durable last key; it never marks the whole date complete until coverage proves.
- [ ] Independently aggregate facts and journals to prove recognised expense equals payable movement per currency.
- [ ] Commit: `feat(funds-core): recognise deposit accruals exactly once`

### Task 5: Capitalise savings interest

- [ ] Test schedule boundaries, product-version retention, restricted/closed destination, tax split, duplicate execution and correction in a later open period.
- [ ] Capitalisation debits accrued-interest payable and credits customer liability; configured withholding debits the customer's gross amount/payable component and credits statutory liability separately.
- [ ] Link capitalisation to the covered accrual facts so each fact is consumed once. Never delete or edit accrual journals.
- [ ] Commit: `feat(funds-core): capitalise savings accruals`

### Task 6: Mature, renew and liquidate fixed deposits

- [ ] Write state-machine tests over `ACTIVE -> MATURED -> PAID_OUT|RENEWED` and `ACTIVE -> EARLY_LIQUIDATED`; race every terminal command.
- [ ] At maturity, capitalise accrued interest once; principal already exists in customer liability and is not recreated. Execute payout/renewal as a separate linked operation.
- [ ] Early liquidation applies only the versioned policy: forfeiture, reduced earned amount or fee are explicit components/templates. Reverse previously accrued excess through linked correction, never mutation.
- [ ] Renewal opens a new contract bound to the then-selected version and records the source contract.
- [ ] Commit: `feat(funds-core): manage fixed deposit lifecycle`

### Task 7: Schedule in bounded memory and survive crashes

- [ ] Implement Go workflow supplying business date and stable pages; Java query orders by `(next_action_date, contract_id)` with initial page size 500.
- [ ] Bound Temporal payload/history, worker concurrency, JDBC pool, Java executor queue and response size. Pass IDs/checkpoints, not product/account objects, through workflow history.
- [ ] Kill scheduler/funds-core before and after fact/journal/checkpoint commit. Repeated discovery is permitted; duplicate recognition is not.
- [ ] Load at least 100,000 synthetic contracts and demonstrate no per-account thread/timer/cache and no monotonic heap/RSS growth.
- [ ] Commit: `perf(deposits): bound accrual scheduling and recovery`

### Task 8: Complete acceptance and accounting documentation

- [ ] Run ACC-40–ACC-41 across savings, current and fixed products, month/year/leap boundaries, rounding, restrictions, period close and concurrent retries.
- [ ] Reconcile trial balance, accrued-interest control, customer-liability control and contract-level facts at a cutoff.
- [ ] Document the fixed-deposit example step-by-step, including principal, accrual expense/payable, capitalisation, tax as separate policy and maturity instruction.
- [ ] Retain exact seed, product-policy hashes, image/config hashes, runtime/resource report and explicit exclusions (IFRS certification, production tax/legal approval).
- [ ] Commit: `test(deposits): prove conventional product lifecycle`

## Final verification

```bash
cd services/funds-core && ./mvnw clean verify
cd ../txn-orchestrator && go test -race ./...
./test/acceptance/conventional_deposits.sh
git diff --check
```

Expected: each accrual period is recognised once, versions remain immutable, all lifecycle journals balance, replay/control proofs agree and the 100,000-contract run stays inside the declared 8 GiB profile budget.
