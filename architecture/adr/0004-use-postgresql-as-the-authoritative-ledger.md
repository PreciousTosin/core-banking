# ADR-0004: Use PostgreSQL as the authoritative ledger

- Status: Accepted
- Retrospective: Yes
- Decision date: 2026-09-01
- Deciders: Funds-core maintainers
- Scope: Authoritative ledger persistence and consistency
- Implementation status: Complete
- Related proposals: [Account identifiers and NIP inbound](../proposals/README.md#account-identifiers-and-nip-inbound); [Conventional deposit products and accrual](../proposals/README.md#conventional-deposit-products-and-accrual); [Non-interest banking products](../proposals/README.md#non-interest-banking-products); [Full PoC platform](../proposals/README.md#full-poc-platform); [Production platform](../proposals/README.md#production-platform); [Providers and reconciliation](../proposals/README.md#providers-and-reconciliation)
- Related implementation plans: [Account identifiers and NIP inbound plan](../../docs/superpowers/plans/2026-08-30-account-identifiers-and-nip-inbound-implementation.md); [Accounting kernel plan](../../docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md); [Conventional deposit products and accrual plan](../../docs/superpowers/plans/2026-08-30-conventional-deposit-products-and-accrual-implementation.md); [Non-interest banking products plan](../../docs/superpowers/plans/2026-08-30-non-interest-banking-products-implementation.md)
- Related pull requests: None
- Related commits: None
- Related architecture sections: [Context and scope](../arc42/03-context-and-scope.md); [Solution strategy](../arc42/04-solution-strategy.md); [Building-block view](../arc42/05-building-block-view.md); [Runtime view](../arc42/06-runtime-view.md); [Crosscutting concepts](../arc42/08-crosscutting-concepts.md); [Decisions index](../arc42/09-decisions.md); [Quality requirements](../arc42/10-quality-requirements.md); [Glossary](../arc42/12-glossary.md)
- Supersedes: None
- Superseded by: None

## Context

Journals, postings, balances, idempotency, outbox rows, charts, and periods must
share one transactional consistency boundary with durable database constraints.

## Decision drivers

- Commit related financial effects atomically under concurrency.
- Enforce invariants independently of application-process lifetime.
- Support repeatable schema evolution and least-privilege proof access.

## Considered options

- Treat an event broker as the primary ledger; this moves correctness into asynchronous reconstruction.
- Split authoritative facts and projections across independent databases; this creates distributed commit ambiguity.
- Use PostgreSQL as the authoritative ledger under serializable transactions and Flyway migrations.

## Decision

<a id="postgresql-consistency-boundary"></a>
<!-- migration-source: 08.09::02 -->
PostgreSQL is authoritative for ledger facts and their transactional projections.
Posting uses JDBC transactions at serializable isolation; Flyway owns schema and
role evolution. Application roles cannot mutate final facts directly, and a
separate proof-reader role supports independent reconciliation queries.

## Consequences

### Positive

One ACID commit establishes journals, postings, balances, idempotency completion,
and outbox intent, while integration tests exercise the real database boundary.

### Negative

Throughput and availability are bounded by the PostgreSQL deployment and require
careful lock ordering, retry policy, migration control, and capacity evidence.

### Risks

Privilege drift or unsafe migrations can bypass guarantees; role-denial tests,
upgrade tests, and database constraints keep that boundary observable.

## Compliance and verification

- PostgreSQL migration, constraint, posting, proof, and role-denial integration tests pass.
- PostingService explicitly configures serializable transactions before repository work.

## Implementation evidence

- 58fde48ba5ef053304b85ffe31cb17c1de021c5e changed: services/funds-core/src/main/java/com/corebanking/funds/application/PostingService.java; services/funds-core/src/main/java/com/corebanking/funds/infrastructure/postgres/JdbcLedgerRepository.java; services/funds-core/src/test/java/com/corebanking/funds/application/PostingServiceIT.java
- c309afc5afcd0854d4ec690e80dcb9ba9ff28186 changed: services/funds-core/src/main/resources/db/migration/V002__journal_and_outbox.sql; services/funds-core/src/main/resources/db/migration/V003__ledger_invariants.sql; services/funds-core/src/test/java/com/corebanking/funds/infrastructure/postgres/LedgerConstraintIT.java; services/funds-core/src/test/java/com/corebanking/funds/infrastructure/postgres/MigrationIT.java
- a8d7653f4296d13baa4e2fe56d7abae46161ff32 changed: services/funds-core/src/main/resources/db/migration/V004__application_roles.sql; services/funds-core/src/test/java/com/corebanking/funds/application/proof/AccountingProofServiceIT.java
- a8d7653f4296d13baa4e2fe56d7abae46161ff32 snapshot: services/funds-core/src/main/java/com/corebanking/funds/infrastructure/postgres/JdbcAccountingProofRepository.java
