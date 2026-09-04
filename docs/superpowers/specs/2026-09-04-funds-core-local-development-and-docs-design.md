# funds-core Local Development and Service Documentation Design

**Date:** 2026-09-04

**Status:** Approved design

**Scope:** `services/funds-core` developer workflow, a development-only driving surface, and the service-local documentation set

**Base commit:** `f4f5e91` on `master`

## 1. Purpose

A human engineer must be able to start funds-core on their own machine against a migrated PostgreSQL, seed a small reference ledger, post and reverse a journal, run the accounting proofs, inspect the database, and get test feedback in seconds. Today none of that is possible outside the test suite: the kernel has no entry point other than its CDI beans, dev mode starts an empty database, and the only documentation for an engineer is the build section at the end of the README.

The same change gives the service a documentation layer that a new engineer reads first and that links into the architecture governance already in place rather than duplicating it.

## 2. Current state

- The kernel exposes `PostingService`, `ReversalService` and `AccountingProofService` as CDI beans. There is no HTTP API beyond the framework health and metrics endpoints.
- `./mvnw quarkus:dev` starts a Dev Services PostgreSQL but Flyway is disabled outside the test profile (`quarkus.flyway.migrate-at-start=false`), so the schema is empty.
- The test gate (`./mvnw clean verify`) runs checkstyle, unit, property and 13 integration test classes through Surefire against a fresh Dev Services PostgreSQL 18.6 container.
- `PackagingContractTest` pins 19 production properties by exact key and counts assignments per literal key, requires no `jdbc:postgresql://` value, pins the Dockerfile line for line, and checks README and health-contract headings. Profile-prefixed keys such as `%dev.x` are distinct keys to it.
- No CI workflow runs the Java gate; the PR checklist in `AGENTS.md` is the only guard.
- Service documentation is the README, `docs/health-contract.md` and `src/main/resources/db/MIGRATION-ROLES.md`, with no index and no onboarding path.

A full audit of the existing tests is recorded in `services/funds-core/docs/test-catalogue.md` and informs the testing choices below.

## 3. Goals and non-goals

### 3.1 Goals

- A migrated and seeded database in dev mode with one command.
- A fast inner loop: continuous testing and single-class runs documented and scripted.
- A way for a human to drive posting, reversal and proofs over HTTP in dev mode only.
- The packaged image is provably unchanged in behaviour: the dev surface returns 404 and every existing production contract test still passes.
- Service-local documentation: an index, a developer guide, change recipes, and the test catalogue, all linking to the arc42 views, ADRs and conventions that govern the service.

### 3.2 Non-goals

- A public or Go-facing API for funds-core. ADR-0002 says callers submit typed commands with their own hash; the dev surface computes the hash for convenience and is explicitly not a contract.
- A CI workflow for the verify gate. That ships as a separate follow-up plan.
- A documentation portal, new front-matter rules, or validator changes for service docs.
- Any change to financial behaviour, migrations `V001` to `V006`, or the production configuration values.

## 4. Considered approaches for the driving surface

1. **Rely on tests only.** Zero code, but a human still cannot see the kernel run. Rejected as the sole answer; kept as the first inner loop.
2. **Dev-only JAX-RS resource in the main module, excluded by build profile.** Adds `quarkus-rest` and `quarkus-rest-jackson` to the packaged jar; the resource bean is removed from the prod build by `@UnlessBuildProfile("prod")` and the smoke script proves the path is absent. Present under the test profile, so it can be covered by an integration test. Chosen.
3. **Separate dev-tooling Maven module.** Cleanest separation, no new dependency in the packaged jar, but a second module, a second POM, a second packaging contract and more for a new engineer to learn. Rejected for now; the profile guard plus the smoke probe gives the same guarantee at lower cost.

## 5. Design

### 5.1 Dev profile

`src/main/resources/application.properties` gains exactly four `%dev.` keys:

```properties
%dev.quarkus.datasource.devservices.image-name=postgres:18.6-bookworm
%dev.quarkus.datasource.devservices.reuse=true
%dev.quarkus.flyway.migrate-at-start=true
%dev.quarkus.flyway.locations=db/migration,db/dev-seed
```

The base keys keep their production values. `PackagingContractTest` gains a test that pins the `%dev.` key set exactly, so a dev override can never be added silently and can never carry a JDBC URL. The packaged Flyway locations stay at the default, so `db/dev-seed` can never run in the image.

### 5.2 Dev seed

`src/main/resources/db/dev-seed/R__dev_reference_ledger.sql` is a Flyway repeatable migration that installs the same reference graph the integration tests use: book `…0001` (NGN, Africa/Lagos, policy version 1), chart `…0002` (DRAFT then activated), product `…0003` and version `…0004` (SAVINGS, CONVENTIONAL), provider asset `…0005` (PROVIDER-CASH), customer liability `…0006` (CUSTOMER-DEPOSITS), one OPEN period `…0007` covering calendar 2026, legal entity `…0008`. It seeds no balances, journals or projections. It is idempotent: rows that a trigger would reject on re-insert use `INSERT … SELECT … WHERE NOT EXISTS`, the rest use `ON CONFLICT DO NOTHING`, and activation is guarded by `status = 'DRAFT'`.

`DevSeedIT` applies the seed twice against the test database and then posts through the real stack to prove the seeded graph accepts a journal. `MigrationIT` gains an assertion that the test profile applied exactly eight versioned migrations and no repeatable one.

### 5.3 Inner loops

`mise.toml` gains tasks `dev`, `test`, `verify` and `checkstyle`. Continuous testing in dev mode and `-Dtest=` runs are documented in the developer guide. No code is needed.

### 5.4 Dev-only driving surface

Package `com.corebanking.funds.devtools`, every bean annotated `@UnlessBuildProfile("prod")`:

- `DevLedgerResource` at `/dev/ledger`: `GET /reference`, `POST /postings`, `POST /reversals`, `GET /proofs/trial-balance`, `GET /proofs/control-account`.
- `DevLedgerReferences`: resolves book, legal entity, active chart, open period for a booking time, policy version and timezone; the current journal cutoff; the default book; and the reference description.
- Request and response records (`DevPostingRequest`, `DevPostingLine`, `DevPostingResponse`, `DevReversalRequest`, `DevReferenceResponse`, `DevErrorResponse`).
- `DevLedgerExceptionMapper`: `IdempotencyConflictException` to 409; `InvalidJournalException`, `AccountingPeriodClosedException`, `MonetaryOverflowException`, `LedgerCapacityException` to 422; `IllegalArgumentException` to 400; `LedgerPersistenceException` to 503; anything else 500. The body is `{error, message}` with the exception simple name.

Identity handling: a missing `commandId` is generated; journal, posting, correlation and business-transaction ids are derived deterministically from the command id with `UUID.nameUUIDFromBytes`, so a replay with the same body reaches the stored result and a replay with a changed body reaches the 409, exactly as a real caller would experience it. The typed request hash is computed server-side with `CanonicalCommandHasher`. Booking time defaults to now truncated to microseconds; value date defaults to the booking date in the book timezone.

`DevLedgerResourceIT` covers reference, post and replay, unbalanced 422, changed-body 409, reversal and second-reversal 422, both proofs, and an out-of-period 400.

### 5.5 Packaged-image guard

`scripts/prod-runtime-smoke.sh` extends its existing reachable-database probe: after readiness returns 200, `GET /dev/ledger/reference` must return 404. The README phrase "all four production-runtime probes" stays true because no probe is added.

### 5.6 Documentation

`services/funds-core/docs/`:

- `README.md`: one-screen index of service-local documents and the governing architecture documents by role, stating the authority order in one sentence.
- `developer-guide.md`: prerequisites, first run, inner loops, dev mode with worked curl examples, database inspection, packaging and smoke, troubleshooting, what dev mode is not.
- `change-recipes.md`: when you change X also change Y, with the tests that fail if you forget.
- `test-catalogue.md`: the audit of every test class and method.
- `health-contract.md` and `MIGRATION-ROLES.md` unchanged in role; the health contract gains one sentence on the absent dev path and one on the dev profile.

`README.md` build section and roles section, `AGENTS.md` layout section and the PR template usage are updated to point at the index. Existing architecture documents are linked, never copied.

## 6. Testing strategy

Every code change has a failing test first. The packaging contract, the seed, the surface and the smoke probe each have their own oracle. The full gate runs at the end with Docker; if Docker is unreachable in the executing session the gate is the human partner's step and is reported as not run.

## 7. Risks

- `quarkus-rest` in the packaged jar adds classes and a small startup cost inside the 640 MiB budget. Mitigation: the constrained smoke run and the existing memory bounds are unchanged; the 404 probe proves no endpoint is served.
- Dev Services container reuse needs `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`; without it the container is recreated per dev-mode start, which is slower but correct.
- The seed ids coincide with the integration-test fixtures by design; a dev database is never shared with the test profile because Dev Services in tests use `reuse=false`.

## 8. Out of scope follow-ups

- CI workflow for `./mvnw clean verify` with Docker.
- The test-effectiveness fixes listed in `test-catalogue.md` (type-only oracles, loose SQLSTATE sets, harness self-tests). Each is a separate ticket.
