# ADR-0002: Centralize financial invariants in funds core

- Status: Accepted
- Retrospective: Yes
- Decision date: 2026-09-01
- Deciders: Funds-core maintainers
- Scope: Financial invariant ownership and proof boundaries
- Implementation status: Complete
- Related proposals: None
- Related implementation plans: None
- Related pull requests: None
- Related commits: None
- Related architecture sections: [Introduction and goals](../arc42/01-introduction-and-goals.md); [Solution strategy](../arc42/04-solution-strategy.md); [Building-block view](../arc42/05-building-block-view.md); [Runtime view](../arc42/06-runtime-view.md); [Crosscutting concepts](../arc42/08-crosscutting-concepts.md); [Decisions index](../arc42/09-decisions.md); [Quality requirements](../arc42/10-quality-requirements.md)
- Supersedes: None
- Superseded by: None

## Context

Financial correctness depends on one implemented owner for journal validation,
posting, reversal, durable constraints, projections, and independent proofs.

## Decision drivers

- Prevent divergent invariant implementations across callers and persistence paths.
- Keep application and database enforcement independently testable.
- Prove projections against immutable source postings rather than trusting derived state.

## Considered options

- Let every channel enforce accounting invariants; this creates inconsistent financial behavior.
- Put all rules only in database triggers; this delays domain feedback and hides application intent.
- Centralize the invariant boundary in funds-core with layered domain, service, database, and proof enforcement.

## Decision

<a id="accounting-invariant-boundary"></a>
<!-- migration-source: 01::02 -->
<!-- migration-source: 04.02 -->
<!-- migration-source: 05 -->
Funds-core is the sole implemented owner of exact journal posting, reversal,
balance maintenance, database invariants, and accounting proofs. Callers submit
typed commands; they do not reproduce or bypass the financial rules.

## Consequences

### Positive

One boundary owns financial correctness, while independently sourced proof
queries and database privileges expose accidental bypasses.

### Negative

Funds-core is a high-assurance dependency whose changes require broader
integration and database-migration evidence.

### Risks

A privileged or alternate write path could evade application checks; restricted
roles, immutable-row constraints, and proof-reader separation mitigate this.

## Compliance and verification

- Posting, reversal, proof, migration, and role-denial integration suites pass.
- No supported caller writes financial tables outside the governed repository path.

## Implementation evidence

- 58fde48ba5ef053304b85ffe31cb17c1de021c5e changed: services/funds-core/src/main/java/com/corebanking/funds/application/PostingService.java; services/funds-core/src/main/java/com/corebanking/funds/infrastructure/postgres/JdbcLedgerRepository.java
- a8d7653f4296d13baa4e2fe56d7abae46161ff32 snapshot: services/funds-core/src/main/java/com/corebanking/funds/application/ReversalService.java; services/funds-core/src/main/java/com/corebanking/funds/application/proof/AccountingProofService.java; services/funds-core/src/main/java/com/corebanking/funds/infrastructure/postgres/JdbcAccountingProofRepository.java
- a8d7653f4296d13baa4e2fe56d7abae46161ff32 changed: services/funds-core/src/main/resources/db/migration/V004__application_roles.sql
