# ADR-0003: Use signed integer minor units

- Status: Accepted
- Retrospective: Yes
- Decision date: 2026-09-01
- Deciders: Funds-core maintainers
- Scope: Exact money and posting-sign semantics
- Implementation status: Complete
- Related proposals: None
- Related implementation plans: None
- Related pull requests: None
- Related commits: None
- Related architecture sections: [Constraints](../arc42/02-constraints.md); [Solution strategy](../arc42/04-solution-strategy.md); [Building-block view](../arc42/05-building-block-view.md); [Crosscutting concepts](../arc42/08-crosscutting-concepts.md); [Decisions index](../arc42/09-decisions.md); [Quality requirements](../arc42/10-quality-requirements.md); [Glossary](../arc42/12-glossary.md)
- Supersedes: None
- Superseded by: None

## Context

Ledger amounts require exact arithmetic and an unambiguous bank perspective.
Binary floating point and sign-free debit/credit pairs complicate balancing,
overflow handling, canonical hashing, and exact reversal.

## Decision drivers

- Preserve exact currency-minor-unit values without rounding drift.
- Make debit and credit signs uniform across code, persistence, and examples.
- Detect overflow and ensure every allowed posting can be exactly negated.

## Considered options

- Use binary floating point; this permits representation and equality drift.
- Use arbitrary decimal values throughout; this widens scale/rounding policy and storage complexity.
- Use signed 64-bit integer minor units with explicit currency and checked arithmetic.

## Decision

<a id="signed-minor-unit-semantics"></a>
<!-- migration-source: 04.07 -->
<!-- migration-source: 08.12 -->
Money is currency plus a signed integer count of minor units. From the bank's
perspective debit postings are positive and credit postings are negative.
Journals balance to zero per currency; arithmetic is checked, zero postings and
`Long.MIN_VALUE` are rejected, and reversal uses exact negation.

## Consequences

### Positive

Balance proofs, hashes, persistence, examples, and exact reversals share one
deterministic numeric representation.

### Negative

Currency exponent changes and fractional-minor-unit products need explicit
policy outside this representation.

### Risks

Extreme aggregates can overflow even when individual postings are valid;
checked operations and overflow tests must remain on every aggregation path.

## Compliance and verification

- Money and journal tests cover checked addition, subtraction, negation, per-currency balancing, and overflow.
- The funds-core README documents the same debit-positive and credit-negative convention.

## Implementation evidence

- 38f822136da516ebf343c82c469a6cbccf148413 changed: services/funds-core/src/main/java/com/corebanking/funds/domain/Money.java; services/funds-core/src/test/java/com/corebanking/funds/domain/MoneyTest.java
- 17a8a1d3d33b5d607b76bfa99d0a3c90f47c872c changed: services/funds-core/src/main/java/com/corebanking/funds/domain/PostingLine.java; services/funds-core/src/main/java/com/corebanking/funds/application/JournalValidator.java; services/funds-core/src/test/java/com/corebanking/funds/application/JournalValidatorTest.java
- a0bfc223a45ee61e0469b3f124240f5ea9797350 changed: services/funds-core/README.md
