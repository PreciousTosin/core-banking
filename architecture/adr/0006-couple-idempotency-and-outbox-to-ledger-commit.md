# ADR-0006: Couple idempotency and outbox to ledger commit

- Status: Accepted
- Retrospective: Yes
- Decision date: 2026-09-01
- Deciders: Funds-core maintainers
- Scope: Posting atomicity, idempotency, and durable delivery intent
- Implementation status: Complete
- Related proposals: None
- Related implementation plans: None
- Related pull requests: None
- Related commits: None
- Related architecture sections: [Solution strategy](../arc42/04-solution-strategy.md); [Building-block view](../arc42/05-building-block-view.md); [Runtime view](../arc42/06-runtime-view.md); [Crosscutting concepts](../arc42/08-crosscutting-concepts.md); [Decisions index](../arc42/09-decisions.md); [Quality requirements](../arc42/10-quality-requirements.md); [Glossary](../arc42/12-glossary.md)
- Supersedes: None
- Superseded by: None

## Context

At-least-once command delivery can repeat after timeouts or process crashes.
Persisting idempotency, financial effects, or delivery intent in separate
commits allows duplicate effects or facts with no durable relay record.

## Decision drivers

- Return a prior completed result without repeating a financial effect.
- Make journal, posting, balance, outbox, and completion state succeed or fail together.
- Recover deterministically from concurrency, ambiguous return, and crash boundaries.

## Considered options

- Keep idempotency only in process memory; restarts lose ownership and results.
- Publish directly to a broker before or after the database commit; either order leaves a dual-write failure window.
- Store idempotency and outbox rows in the same PostgreSQL transaction as the ledger commit.

## Decision

<a id="idempotent-atomic-delivery"></a>
<!-- migration-source: 04.03 -->
Each command owns a durable idempotency row bound to its typed request hash.
The serializable transaction writes journal, postings, balances, control
projection, outbox intent, and completed idempotency result atomically. Relay
delivery remains at least once, so consumers use stable event identity.

## Consequences

### Positive

Retries and crashes cannot create a second financial effect, and every committed
journal has durable delivery intent available for later relay.

### Negative

The transaction touches more rows and lock domains, increasing contention and
requiring disciplined retry, timeout, and lock ordering.

### Risks

An incorrect canonical request hash could alias distinct commands; typed hashing,
concurrency tests, and crash-recovery tests protect this boundary.

## Compliance and verification

- Concurrency tests prove one committed effect and stable completed results under competing callers.
- Failure-injection and child-process crash tests prove rollback or deterministic recovery at commit boundaries.

## Implementation evidence

- df6b2fb6a67f1406ccf2e8b0fa813626900c7d25 changed: services/funds-core/src/main/java/com/corebanking/funds/application/PostingService.java; services/funds-core/src/main/java/com/corebanking/funds/infrastructure/postgres/JdbcLedgerRepository.java; services/funds-core/src/test/java/com/corebanking/funds/application/PostingConcurrencyIT.java
- df6b2fb6a67f1406ccf2e8b0fa813626900c7d25 snapshot: services/funds-core/src/main/resources/db/migration/V002__journal_and_outbox.sql
- 227bd288b593015f9009b0c408b1daf29855e997 changed: services/funds-core/src/test/java/com/corebanking/funds/application/PostingAtomicityIT.java; services/funds-core/src/test/java/com/corebanking/funds/application/PostingCrashRecoveryIT.java; services/funds-core/src/test/java/com/corebanking/funds/application/CrashPostingWorker.java; services/funds-core/src/test/java/com/corebanking/funds/application/TestPostingStack.java
