# funds-core accounting kernel

This Java 25/Quarkus module is the implemented proof-of-concept accounting kernel. It owns exact journal validation, serializable PostgreSQL posting, immutable journals and reference foundations, idempotency, reversals, independent accounting proofs, and transactional outbox rows. The broader service topology and later delivery slices remain described in the [architecture](../../architecture/modern-core-banking-comprehensive-design-revised.md) and [implementation plan](../../docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md).

## Reading the accounting model

Positive amounts are debits; negative amounts are credits. The signs describe which side of the accounting equation a posting occupies, not whether a customer's displayed balance is positive. Natural balances are derived by multiplying signed postings by `+1` for debit-normal accounts and `-1` for credit-normal accounts.

For example, a customer deposits NGN 10,000.00 (1,000,000 minor units):

| Account | Normal side | Signed posting | Effect in natural balance |
|---|---|---:|---:|
| Bank cash/settlement asset | Debit | +1,000,000 | +1,000,000 asset |
| Customer deposit liability | Credit | -1,000,000 | +1,000,000 liability owed |

The postings sum to zero. A NGN 25.00 transfer between two customer liabilities debits the sender `+2,500` (reducing its credit-normal natural balance) and credits the recipient `-2,500` (increasing its credit-normal natural balance). This is why the storage convention cannot be inferred from the customer UI's plus/minus display.

`Money` stores an ISO currency plus signed integer minor units in a Java `long`; it never uses binary floating point. Addition, subtraction, negation, journal totals, balance updates and projection totals use checked exact arithmetic. A value outside the signed 64-bit range fails with `MonetaryOverflowException`, and the database transaction rolls back—there is no wrapping, saturation or rounding fallback. Currency conversion, rates and decimal rounding are outside this slice.

## Identity and product foundations

The ledger-account UUID is the balance-bearing financial identity. A NUBAN or provider virtual account is only an address that resolves to an account; it never becomes a posting account ID, command ID, transaction ID or idempotency key and holds no state or balance. One account can have several provider-scoped virtual addresses, while database constraints permit at most one active primary NUBAN for an account and prevent one active scoped address from resolving to two accounts.

NUBAN validation implements the six-digit institution-code plus nine-digit serial/check-digit algorithm. Institution code `000000` with NUBAN `0000000017` is the deterministic `SIMULATOR_ONLY` fixture. It is not production-routable configuration. Nigerian IBAN fabrication is rejected; `IBAN` is reserved only for a future country-specific validator. Issuance, replacement, resolution and account-details APIs are not implemented here.

Customer accounts bind immutably to a versioned savings, current, fixed-deposit or domiciliary product definition. Product versions retain effective dates, approval reference and a policy hash, so later product changes cannot rewrite historical accounting. `CONVENTIONAL` and `NON_INTEREST` are distinct finance principles. A non-interest product is not a conventional product with a zero rate: it requires a separately approved contract, permitted asset/fee/profit mechanics, governance and allocation rules. This slice stores and constrains those foundations; interest accrual/capitalisation/maturity and non-interest pool allocation arrive in later plans.

## Database roles and startup

An operator-controlled migration login assumes `funds_migrator`; the service login receives only `funds_app`. The complete ownership, grants and reset procedure is in [`MIGRATION-ROLES.md`](src/main/resources/db/MIGRATION-ROLES.md). Packaged runtime configuration is supplied through `FUNDS_DB_JDBC_URL`, `FUNDS_APP_DB_USER`, and `FUNDS_APP_DB_PASSWORD`. Flyway is disabled at runtime and enabled only by the test profile; the migration job must finish before service admission.

There are six versioned migrations: `V001`, `V002`, `V003`, `V003.1`, `V003.2`, and `V004`. The plan's earlier “four migrations” wording predates the two reviewed invariant-hardening intermediates. No application cache stores balances or journals.

## Build and verification

Use Docker access for the PostgreSQL 18.6 Testcontainers gate and Java 25:

```bash
cd services/funds-core
./mvnw clean verify
./mvnw -DskipTests package
docker build -f Dockerfile.jvm -t core-banking/funds-core:accounting-kernel .
docker run --rm --entrypoint java --memory=640m --cpus=0.60 --pids-limit=256 \
  core-banking/funds-core:accounting-kernel -version
```

The test gate contains unit, deterministic generated-property, PostgreSQL integration, failure-injection and real child-process crash tests; no accounting test is intentionally skipped. Full service startup additionally requires the separately migrated profile database and is deferred to the deployment-profile plan.

## Memory boundary

The image uses `-Xms128m -Xmx384m -XX:MaxMetaspaceSize=96m -XX:MaxDirectMemorySize=64m -Xss512k -XX:+ExitOnOutOfMemoryError` inside the planned 640 MiB container. The remainder is deliberate headroom for JIT code cache, native libraries, TLS/socket buffers and other RSS. Heap dumps are opt-in through an approved encrypted diagnostic workflow. The JDBC pool is bounded to 2–8 connections with a five-second acquisition timeout; request bodies are capped at 128 KiB. No unused cache or executor is introduced. See the [health contract](docs/health-contract.md) for health, pool-saturation and failure semantics.

The target VM has 8 GiB RAM, but that is an evidence-suite budget across versioned normal, concurrency and restore profiles—not permission to run every component at peak simultaneously. Later profile work must measure and cap PostgreSQL connections and per-query memory, use bounded Go queues/goroutines and `GOMEMLIMIT`, stream/paginate file and projection work, bound broker retention/batches, cap workflow history/concurrency and observability queues/label cardinality. Those other-component controls and the exact orchestration limits are planned, not implemented by this module.

## Acceptance coverage and limits

| Acceptance | Implemented evidence in this slice | Boundary |
|---|---|---|
| ACC-01 | Unbalanced and mixed-currency journals reject atomically. | Implemented. |
| ACC-02 | Serializable concurrent accounting updates, canonical locks, persisted postings and materialised/replayed balance invariants. | Accounting portion only; no deployed multi-replica profile or complete funds-availability scenario. |
| ACC-19 | Per-book/per-currency trial balance and independently sourced control-account proofs, including corruption detection. | Implemented kernel proof. |
| ACC-20 | Closed-period rejection and immutable linked exact reversal/correction into an open period. | Kernel accounting portion; approval workflow is later work. |
| ACC-24 | Application-role denial of direct ledger mutation, trigger disabling and privileged functions. | Direct-mutation/privilege portion only; maker-checker and audit UI are excluded. |
| ACC-25 | Bounded JVM, HTTP and JDBC configuration inputs plus a constrained image smoke command. | Configuration inputs only; no full 8 GiB overlay or mixed-load soak. |
| ACC-29 | Java integer-money, enum, presence/hash fixtures and canonical journal hash prerequisites. | Java fixture prerequisites only; Go/generated contracts and cross-language golden binaries are excluded. |
| ACC-32 | Same-key same/different-hash races, abandoned pre-commit owner recovery, and stored-result recovery after owner termination immediately before and after commit. | Implemented with real PostgreSQL and child JVM termination. |
| ACC-38 | NUBAN check digits, scoped alias cardinality, primary uniqueness, immutable mappings and simulator-only fixture constraints. | Foundation/constraint portion only; issuance, resolution, replacement and concurrent allocation APIs are excluded. |
| ACC-40 | Immutable product-version binding to customer accounts. | Version foundation only; product-specific lifecycle behavior is excluded. |
| ACC-42 | Immutable finance-principle classification and approval-reference foundations. | Constraint foundation only; complete template/rate/account guards and allocation behavior are excluded. |

Explicit exclusions are identifier issuance/resolution APIs, real or simulated NIP, account-details projection, accrual/capitalisation/maturity, non-interest allocation, holds, Go contracts, event relay, providers, reconciliation, FX execution, security UI, and full 8 GiB orchestration. The POC does not claim production readiness, high availability, regulatory certification, host-loss durability or production throughput.
