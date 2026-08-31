# Task 7 report: concurrent idempotency and account locking

## Status

Complete. The PostgreSQL-backed tests deterministically prove same-key waiter behavior, winner-safe conflict handling, canonical account/materialised lock ordering, bounded transaction retries, and replay/materialised balance agreement.

## Changed files

- `services/funds-core/src/main/java/com/corebanking/funds/application/PostingTransactionObserver.java`
  - Adds the exact five-callback internal SPI and `noop()` factory from the task brief.
  - Provides the stateless `@ApplicationScoped` production no-op observer bean.
- `services/funds-core/src/main/java/com/corebanking/funds/application/PostingService.java`
  - Injects the observer while retaining the Task 6 three-argument constructor.
  - Calls `beforeCommit` immediately before `Connection.commit()`.
  - Calls `afterCommitBeforeReturn` only after a successful commit and before returning.
- `services/funds-core/src/main/java/com/corebanking/funds/infrastructure/postgres/JdbcLedgerRepository.java`
  - Injects the observer while retaining the Task 6 no-argument and validator/hasher constructors.
  - Calls `afterIdempotencyAcquired` after the command row is locked and the request-hash decision is safe.
  - Calls `afterAccountLocks` after all canonically ordered account and materialised-balance locks are held.
  - Calls `afterFinancialRowsBeforeOutbox` after journal, posting, materialised-balance, and control writes, immediately before the outbox insert.
- `services/funds-core/src/test/java/com/corebanking/funds/application/PostingConcurrencyIT.java`
  - Adds three deterministic, real-PostgreSQL concurrency proofs.

`PostgresRetryPolicy` required no source change: Task 6 already established a five-attempt bound, retry eligibility limited to SQLSTATE `40001`/`40P01`, and a fresh transaction per service attempt. Task 7 preserves and exercises that contract.

## Design and proof notes

### Same command ID and same hash

The test uses two executor workers and two independently observed database backends. A first-writer latch in `afterIdempotencyAcquired` pauses only the transaction that acquires the idempotency row first. While it is paused, the test queries `pg_stat_activity` until PostgreSQL reports the other backend waiting on a lock; it also proves neither future has completed. The winner is then released.

Both futures return the same `PostingResult`, and database assertions prove exactly one journal, two postings, and one outbox event.

This deliberately avoids a two-party post-insert barrier: the losing `INSERT ... ON CONFLICT` waits for the winning transaction and therefore cannot reach such a barrier before the winner commits.

### Same command ID and different hashes

The same first-writer gate and PostgreSQL lock-wait observation drive the conflict race. The test is winner-agnostic and proves:

- exactly one successful result;
- exactly one `IdempotencyConflictException`;
- exactly one journal, two postings, and one outbox event;
- the stored command `request_hash`, journal `canonical_hash`, journal ID, and returned result all belong to the actual winner.

### Reverse account input order

The test posts 100 deterministic, unique journals in 50 concurrent pairs. Each pair supplies the same two accounts in opposite input order. A standard `DataSource` wrapper records the UUID bind order for every ledger-account and materialised-balance `SELECT ... FOR UPDATE` on every attempted transaction.

The assertions prove all observed lock sequences are canonical UUID-string prefixes, all 100 committed transactions acquired both lock classes in the same complete order, all futures completed without an unhandled deadlock, and exactly 100 journals/200 postings/100 outbox events committed. Posting-table totals are independently recomputed and equal the materialised totals (`+5050` and `-5050`). The existing retry policy bounds each operation at five attempts.

The production constructors remain compatible with Task 6. Deterministic test observers can be supplied through ordinary constructor composition with any `javax.sql.DataSource`, including wrappers around `PGSimpleDataSource`.

## TDD RED evidence

The concurrency integration test was written before the observer implementation.

Command:

```bash
cd services/funds-core
newgrp docker -c 'mise x java@25 -- ./mvnw -Dtest=PostingConcurrencyIT test'
```

Result: `BUILD FAILURE` during `testCompile`, with five missing-symbol/override errors for the intentionally absent `PostingTransactionObserver`. This established the required missing synchronization contract before production code was added.

## Focused GREEN evidence

Final-state command, using the provisioned toolchain and dependency cache:

```bash
cd services/funds-core
newgrp docker -c 'for run in 1 2 3 4 5; do env JAVA_HOME=/tmp/core-banking-mise/installs/java/25.0.2 PATH=/tmp/core-banking-mise/installs/java/25.0.2/bin:/usr/local/sbin:/usr/local/bin:/usr/bin:/bin ./mvnw -Dmaven.repo.local=/tmp/core-banking-m2 -Dtest=PostingConcurrencyIT test || exit 1; done'
```

Result: five consecutive `BUILD SUCCESS` runs. Every run reported:

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

Every focused run started `postgres:18.6-bookworm`, and Flyway reported `PostgreSQL 18.6`.

## Full-suite evidence

Command:

```bash
cd services/funds-core
newgrp docker -c 'env JAVA_HOME=/tmp/core-banking-mise/installs/java/25.0.2 PATH=/tmp/core-banking-mise/installs/java/25.0.2/bin:/usr/local/sbin:/usr/local/bin:/usr/bin:/bin ./mvnw -Dmaven.repo.local=/tmp/core-banking-m2 test'
```

Result:

```text
Tests run: 96, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Suite breakdown:

- `PostingConcurrencyIT`: 3
- `PostingServiceIT`: 12
- `LedgerConstraintIT`: 19
- `MigrationIT`: 32
- `JournalProperties`: 3
- `JournalValidatorTest`: 12
- `MoneyTest`: 10
- `NubanTest`: 5

Both database constraint suites printed server version `18.6 (Debian 18.6-1.pgdg12+2)`.

## Toolchain and warning scan

Verified versions:

```text
openjdk 25.0.2 2026-01-20
Apache Maven 3.9.16
Java version: 25.0.2
PostgreSQL 18.6 (Debian 18.6-1.pgdg12+2)
```

The final Maven output contained no `WARN`/`WARNING` lines. This report scan also returned no matches:

```bash
rg -n 'Failures: [1-9]|Errors: [1-9]|Skipped: [1-9]|\bWARN(?:ING)?\b' target/surefire-reports
```

Testcontainers emitted its existing INFO-level unauthenticated-registry fallback message; the exact locally available `postgres:18.6-bookworm` image started successfully, so it is not a task warning or failure.

## Self-review

- The observer callback names and signatures exactly match the brief.
- Callback placement was reviewed adjacent to the lock/write/commit operations rather than inferred only from tests.
- The Task 6 public constructors, transaction isolation, rollback behavior, retry semantics, and overflow translation remain intact.
- No `Thread.sleep`, PostgreSQL sleep, or `CyclicBarrier` appears in the implementation or concurrency tests.
- Every generated command, journal, and posting ID in the 100-journal stress proof is unique and deterministic.
- `git diff --check` and trailing-whitespace scans are clean.
- Mutation review: removing the idempotency callback prevents the winner gate; removing account callbacks or canonical sorting breaks lock-order evidence; duplicate financial effects break row counts; storing the losing hash breaks winner consistency; and balance drift breaks the independent replay comparison.

## Concerns

None. The concurrency test intentionally uses PostgreSQL backend-state observation and JDBC proxies because transaction waiting and SQL bind order are the behaviors under proof; it introduces no production sleeps or test-only production branches.

## Review fix round 1: bounded executor cleanup

The reverse-order proof no longer uses Java 25's `ExecutorService.close()`, whose termination wait is unbounded. Both the 100-journal executor and the same-key race executor now:

- retain every submitted future as soon as submission succeeds;
- cancel every outstanding future with interruption during cleanup;
- call `shutdownNow()` in `finally`;
- wait at most 10 seconds for termination and raise a description-specific `AssertionError` if workers remain;
- restore the current thread's interrupt status before raising a cleanup assertion when `awaitTermination` is interrupted.

The 100-journal workload, per-future 30-second result bounds, canonical lock assertions, and independently replayed totals are unchanged.

### Fix RED evidence

A mutation-oriented test was added first. It runs an outstanding interruptible worker through an `ExecutorService` proxy whose `close()` throws immediately, then requires cancellation, worker interruption, and bounded termination. Before the cleanup helper existed, the focused build failed as expected:

```text
[ERROR] COMPILATION ERROR
PostingConcurrencyIT.java:[223,13] cannot find symbol
  symbol: method shutdownExecutor(ExecutorService,List<Future<?>>,String)
[INFO] BUILD FAILURE
```

This test fails immediately if cleanup calls `close()`, fails if outstanding futures are not cancelled, and fails after the explicit 10-second bound if shutdown/termination handling is removed; it cannot turn a close mutation into an unbounded test hang.

### Fix focused GREEN evidence

Command:

```bash
cd services/funds-core
newgrp docker -c 'for run in 1 2 3 4 5; do env JAVA_HOME=/tmp/core-banking-mise/installs/java/25.0.2 PATH=/tmp/core-banking-mise/installs/java/25.0.2/bin:/usr/local/sbin:/usr/local/bin:/usr/bin:/bin ./mvnw -Dmaven.repo.local=/tmp/core-banking-m2 -Dtest=PostingConcurrencyIT test || exit 1; done'
```

Result: five consecutive `BUILD SUCCESS` runs, each reporting:

```text
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

All five runs used `postgres:18.6-bookworm` and reported PostgreSQL 18.6.

### Fix full-suite and scan evidence

The full Java 25 suite, run after the five focused passes with the same provisioned toolchain/cache and Docker command shown above, reported:

```text
Tests run: 97, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
LedgerConstraintIT PostgreSQL server_version=18.6 (Debian 18.6-1.pgdg12+2)
MigrationIT PostgreSQL server_version=18.6 (Debian 18.6-1.pgdg12+2)
```

The final Maven output contained no `WARN`/`WARNING` lines, and the Surefire failure/warning scan returned no matches. These lifecycle source scans also returned no matches:

```bash
rg -n 'try \(ExecutorService|executor\.close\(' src/test/java/com/corebanking/funds/application/PostingConcurrencyIT.java
rg -n 'Thread\.sleep|pg_sleep|CyclicBarrier' src/test/java/com/corebanking/funds/application/PostingConcurrencyIT.java
```
