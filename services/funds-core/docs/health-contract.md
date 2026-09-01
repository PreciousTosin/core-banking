# funds-core health and metrics contract

This slice uses Quarkus SmallRye Health and the Micrometer Prometheus registry. It adds no custom health endpoint and makes no claim that health alone proves accounting correctness.

## Endpoints

- `GET /q/health/live` is process liveness. In this slice it reports the framework-provided liveness result; it does not query PostgreSQL, inspect pool headroom, or run an accounting proof.
- `GET /q/health/ready` is readiness. With the JDBC datasource active, Quarkus' datasource health check includes PostgreSQL connectivity. A failed database check makes the aggregate readiness response unhealthy. Successful readiness means a check connection succeeded, not that migrations, every privilege, capacity under load, or ledger invariants were independently proved.
- `GET /q/health` is the aggregate health document supplied by SmallRye Health.
- `GET /q/metrics` is the Prometheus exposition endpoint supplied by the Micrometer Prometheus extension. Metrics must use bounded labels; account, customer, command, journal and free-text values are not metric labels.

Management endpoints belong on the private container network. Authentication and a separate management interface are deployment-profile work and are not implemented here.

## Database and migration prerequisite

Runtime replicas connect with a login granted `funds_app`. They never migrate the schema: `quarkus.flyway.migrate-at-start=false`. Before a replica is admitted, an operator-controlled Flyway job must apply all migrations through the `funds_migrator` role as described in [`MIGRATION-ROLES.md`](../src/main/resources/db/MIGRATION-ROLES.md). A reachable but unmigrated or incorrectly privileged database can pass a shallow connection check and then fail application operations; deployment therefore treats successful migration validation as a separate prerequisite.

The three production connection values (`FUNDS_DB_JDBC_URL`, `FUNDS_APP_DB_USER`, and `FUNDS_APP_DB_PASSWORD`) are mounted configuration/secret inputs. The production datasource is explicitly active, and a prod-only eager startup guard checks the resolved datasource URL, username and password for missing/blank values without logging their contents. Missing inputs make packaged startup fail closed before healthy readiness. Tests instead use PostgreSQL 18.6 Dev Services and enable Flyway in the test profile.

## Resource and failure semantics

The JDBC pool is bounded at 2–8 connections, waits at most five seconds to acquire one, and reports a connection held for 30 seconds through leak detection. The Quarkus global worker pool is separately bounded to 2–8 threads and a 32-task queue; work beyond that capacity is rejected instead of accumulating without limit. Pool or queue exhaustion therefore becomes bounded failure/backpressure rather than unbounded connection, thread, or queued-request growth. This slice does not implement a readiness threshold for spare capacity, so readiness must not be interpreted as headroom evidence.

Posting and reversal transactions set PostgreSQL-local deadlines before their first financial read or lock: `lock_timeout=1s`, `statement_timeout=3s`, and `idle_in_transaction_session_timeout=5s`. Lock and statement expiry map to one non-retryable typed timeout outcome; only serialization failures and deadlocks retain the bounded retry policy. Transaction-local settings disappear on commit/rollback and do not pollute pooled sessions.

The container bounds heap, metaspace, direct memory and thread-stack size. `ExitOnOutOfMemoryError` terminates the process so the runtime can restart it; heap dumps are disabled because they may expose financial or identity data. An interrupted database transaction is rolled back by PostgreSQL, and clients retry with the same command ID. The crash tests cover owner termination immediately before and after commit. Health cannot replace those tests, the trial-balance proof, or the control-account proof.

No application cache stores balances or journals. PostgreSQL remains authoritative; materialised balances are transactionally maintained, replayable projections. Worker and JDBC capacities are packaging contracts with deterministic saturation/deadline tests, not estimates of throughput.
