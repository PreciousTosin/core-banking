# ADR-0005: Use immutable journals and additive corrections

- Status: Accepted
- Retrospective: Yes
- Decision date: 2026-09-01
- Deciders: Funds-core maintainers
- Scope: Journal finality, reversal, and correction semantics
- Implementation status: Complete
- Related proposals: None
- Related implementation plans: None
- Related pull requests: None
- Related commits: None
- Related architecture sections: [Solution strategy](../arc42/04-solution-strategy.md); [Runtime view](../arc42/06-runtime-view.md); [Crosscutting concepts](../arc42/08-crosscutting-concepts.md); [Decisions index](../arc42/09-decisions.md); [Quality requirements](../arc42/10-quality-requirements.md); [Glossary](../arc42/12-glossary.md)
- Supersedes: None
- Superseded by: None

## Context

Financial history must explain both an original booking and every correction.
Updating or deleting posted facts destroys the audit trail and makes cutoff
proofs depend on mutable interpretation.

## Decision drivers

- Preserve the original financial fact and its historical reporting position.
- Make correction effects exact, linked, and independently provable.
- Prevent duplicate reversal and mutation races in both code and database constraints.

## Considered options

- Update the original journal; this erases the fact that was previously authoritative.
- Delete and re-post the journal; this breaks stable identity, sequence, and audit references.
- Keep journals immutable and express corrections as new linked postings.

## Decision

<a id="additive-correction-semantics"></a>
<!-- migration-source: 04.04 -->
Posted journals and postings are immutable. A reversal is a new journal linked
to one original and contains the exact negation of every original posting. A
closed-period correction books additively in an open period while retaining
the original link; reversal of a reversal requires an explicit future policy.

## Consequences

### Positive

Audit history, cutoff proofs, and materialised projections retain an additive,
explainable chain of facts.

### Negative

Corrections consume additional rows and require consumers to understand linked
original and reversal journals.

### Risks

Concurrent reversal attempts could duplicate effects; deterministic identity,
single-reversal constraints, and integration tests reject that race.

## Compliance and verification

- Reversal integration tests prove exact negation, links, idempotency, and race behavior.
- Database migrations reject update/delete of final journals and postings and enforce one reversal per original.

## Implementation evidence

- feb5bbd951c5061ef05050c35604aa863cbdea02 changed: services/funds-core/src/main/java/com/corebanking/funds/application/ReversalService.java; services/funds-core/src/main/java/com/corebanking/funds/domain/ReversalRequest.java; services/funds-core/src/test/java/com/corebanking/funds/application/ReversalServiceIT.java
- feb5bbd951c5061ef05050c35604aa863cbdea02 snapshot: services/funds-core/src/main/resources/db/migration/V002__journal_and_outbox.sql; services/funds-core/src/main/resources/db/migration/V003__ledger_invariants.sql
- 5f39c9ec7c3a131e5bb2d71fc76f6971b8636f34 changed: services/funds-core/src/main/resources/db/migration/V003_2__journal_finality_and_single_reversal.sql
