# Non-Interest Banking Products Implementation Plan

**Proposal:** [Non-interest banking products](../../../architecture/proposals/README.md#non-interest-banking-products)

**Related ADRs:** [ADR-0002](../../../architecture/adr/0002-centralize-financial-invariants-in-funds-core.md), [ADR-0003](../../../architecture/adr/0003-use-signed-integer-minor-units.md), [ADR-0004](../../../architecture/adr/0004-use-postgresql-as-the-authoritative-ledger.md), [ADR-0005](../../../architecture/adr/0005-use-immutable-journals-and-additive-corrections.md), and [ADR-0006](../../../architecture/adr/0006-couple-idempotency-and-outbox-to-ledger-commit.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans task-by-task. Keep checkbox state here.

**Goal:** Implement a structurally separate, governance-approved non-interest deposit/investment foundation and a Mudarabah-style investment-pool allocation proof without disguising conventional interest as “zero rate”.

**Architecture:** Java `funds-core` owns finance-principle guards, approved product/contract versions, pool participation facts, exact allocations and journals. Go orchestration schedules pool close/distribution only. Conventional and non-interest templates have separate types and account-role allowlists. The PoC demonstrates one illustrative investment-account flow; Murabahah/Ijarah financing remains outside this plan.

**Tech Stack:** Java 25/Quarkus/PostgreSQL 18.6; Go/Temporal; exact rational allocation; JUnit state/property tests; Testcontainers.

**Prerequisites:** Accounting kernel, account/product foundations, funds control, business dates/outbox, conventional-product policy infrastructure where shared only for versioning—not calculation semantics.

**Acceptance:** INV-29, INV-31–INV-32; ACC-42–ACC-43.

## Governance and claim limits

- `NON_INTEREST` is a finance-principle type with its own approved contract and templates, never a conventional annual rate of zero.
- Every active version requires governance approval reference, effective interval, permitted pool/asset class, profit/loss/fee rules and named accounting policy.
- The software enforces configured contracts and conservation. It does not itself provide Sharia, legal, regulatory or financial-reporting certification.
- Fixed/promised returns and accidental principal guarantees are rejected unless a separately approved legal product type explicitly supports them.
- Pool allocation uses realised approved distributable results, not elapsed-time interest accrual.

## Target files

```text
services/funds-core/src/main/java/com/corebanking/funds/domain/noninterest/
services/funds-core/src/main/java/com/corebanking/funds/application/noninterest/
services/funds-core/src/main/java/com/corebanking/funds/infrastructure/postgres/JdbcInvestmentPoolRepository.java
services/funds-core/src/main/resources/db/migration/V009__non_interest_products.sql
services/funds-core/src/main/resources/db/migration/V010__investment_pool_allocation.sql
services/txn-orchestrator/internal/workflows/investment_pool.go
test/acceptance/non_interest_products.sh
```

---

### Task 1: Create hard finance-principle and template separation

- [ ] Write failing tests that attempt to attach annual-interest rate, accrued-interest account, conventional accrual template or conventional product kind to a non-interest version.
- [ ] Add sealed/closed types for `NonInterestContractType` (`CURRENT`, `SAFEKEEPING_SAVINGS`, `MUDARABAH_INVESTMENT`) and separate `NonInterestJournalTemplate` account-role allowlists.
- [ ] Require approval reference, policy hash, permitted asset/pool class, bank-share rule, investor-share rule, fee/loss/reserve policy and effective dates.
- [ ] Make invalid cross-principle configuration fail before database mutation; independently reinforce with database constraints/triggers.
- [ ] Commit: `feat(non-interest): separate product and accounting types`

### Task 2: Persist approved non-interest contracts and pools

- [ ] Create V009 for governance approval, investment pool, pool ledger-account mapping, non-interest contract and immutable version binding.
- [ ] Test one account belongs to at most one active investment pool for the same contract; pool control/asset/income/expense/reserve accounts cannot be shared with another pool unless an explicit allocation layer exists (excluded here).
- [ ] Open non-remunerated current/safekeeping accounts without interest scheduling. Open Mudarabah investment accounts with pool membership and disclosed ratio/version.
- [ ] Reject inactive approval, conventional template, currency mismatch, missing pool and unapproved principal/return guarantee.
- [ ] Commit: `feat(funds-core): open governed non-interest contracts`

### Task 3: Record immutable participation units

- [ ] Create V010 participation events (`SUBSCRIBE`, `WITHDRAW`, approved adjustment) and daily/cutoff weighted-unit facts with unique event identity.
- [ ] Write state/property tests for mid-period subscriptions/withdrawals, restrictions, duplicate commands, same-time ordering and exact unit conservation.
- [ ] Calculate weighted participation from immutable event intervals using exact integer/rational arithmetic. Wall-clock job time cannot alter weights.
- [ ] Page events/contracts by stable key; independently replay unit balances at any cutoff.
- [ ] Commit: `feat(non-interest): track investment participation exactly`

<a id="pool-distribution-delivery-detail"></a>
<!-- migration-source: 13.07.03 -->
### Task 4: Approve and freeze a pool-close result

- [ ] Write tests for incomplete source period, unbalanced pool books, unapproved adjustments, changed evidence hash, cross-pool account and concurrent close commands.
- [ ] A maker creates a close proposal referencing immutable source cutoff and realised income/expense facts; a different approver binds the exact canonical hash and distributable result.
- [ ] Freeze a new immutable close version; corrections create a later version/correction journal and never rewrite distributed history.
- [ ] Do not permit allocation until pool trial balance, source completeness and approval proof pass.
- [ ] Commit: `feat(non-interest): approve investment pool close`

### Task 5: Allocate profit exactly and conservatively

- [ ] Write the illustrative failing case: ₦100,000 distributable profit, 30% disclosed bank/mudarib share, ₦70,000 investor pool, multiple weighted participants and a final minor-unit residual.
- [ ] Implement exact ratio multiplication and largest-remainder (or explicitly approved deterministic) minor-unit allocation. Tie-break by immutable contract ID; record unrounded numerator/denominator and booked result.
- [ ] Enforce `bank share + investor allocations + residual = approved distributable result` using an independent PostgreSQL numeric proof.
- [ ] Generate thousands of ratios/participants/results, including zero result, one minor unit, negative loss scenario routed to its approved policy, overflow boundaries and order permutations.
- [ ] Commit: `feat(non-interest): allocate pool results exactly`

### Task 6: Post idempotent distributions and loss treatment

- [ ] Write integration tests for bank share, investor liabilities, reserve/residual, restricted/closed account handling, crash/retry, closed accounting period and cross-pool rejection.
- [ ] Post distribution batches with deterministic `(pool_close_id, allocation_id)` commands. Mark an allocation posted only in the same transaction as its journal/outbox fact.
- [ ] Use accounting-policy-approved accounts; do not label balances “interest”. Route unavailable customer destinations through restricted/unapplied liability policy without losing pool attribution.
- [ ] Implement loss only for the explicitly approved PoC rule; otherwise block close and require review. Never silently transfer investor loss to the bank or another pool.
- [ ] Commit: `feat(non-interest): post governed pool distributions`

### Task 7: Bound scheduling, memory and recovery

- [ ] Go workflow passes pool/cutoff/checkpoint IDs only and continues-as-new before history limits. Java initially pages 500 participation/allocation rows with bounded executors/JDBC.
- [ ] Kill orchestrator/funds-core before and after close, allocation-page and posting commits. Repeat discovery may occur; close versions, allocations and journals remain singular.
- [ ] Run at least 100,000 participant contracts across several pools. Demonstrate no all-pool in-memory map, per-account timer/thread or unbounded residual list.
- [ ] Disable any cache and repeat conservation/replay proofs; cache loss may affect latency only.
- [ ] Commit: `perf(non-interest): bound pool allocation and recovery`

### Task 8: Complete adversarial acceptance and documentation

- [ ] Run ACC-42 and ACC-43 plus attempts at self-approval, changed payload, conventional-template injection, cross-pool leakage, duplicate close, reordered participants and rounding manipulation.
- [ ] Independently prove pool source result, bank share, investor liability/control total and residual at the same cutoff; rebuild from immutable facts.
- [ ] Document the ₦100,000/30:70 example in plain language and journals, clearly marking the example as illustrative and accounting-policy-dependent.
- [ ] Retain seeds, approval/policy hashes, image/config hashes, memory report and claim limitations. Obtain domain/accounting/Sharia review before any production claim.
- [ ] Commit: `test(non-interest): prove governance and allocation invariants`

## Final verification

```bash
cd services/funds-core && ./mvnw clean verify
cd ../txn-orchestrator && go test -race ./...
./test/acceptance/non_interest_products.sh
git diff --check
```

Expected: cross-principle configuration fails closed; pool allocations conserve every minor unit with no cross-pool leakage or duplicate posting; memory stays within the declared profile; documentation makes no regulatory or Sharia-certification claim.
