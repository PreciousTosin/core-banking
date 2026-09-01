# ADR-0007: Separate ledger identity from account addresses

- Status: Accepted
- Retrospective: Yes
- Decision date: 2026-09-01
- Deciders: Funds-core maintainers
- Scope: Ledger account identity and external account-address foundations
- Implementation status: Partial
- Related proposals: None
- Related implementation plans: None
- Related pull requests: None
- Related commits: None
- Related architecture sections: [Constraints](../arc42/02-constraints.md); [Context and scope](../arc42/03-context-and-scope.md); [Building-block view](../arc42/05-building-block-view.md); [Crosscutting concepts](../arc42/08-crosscutting-concepts.md); [Decisions index](../arc42/09-decisions.md); [Quality requirements](../arc42/10-quality-requirements.md); [Risks and technical debt](../arc42/11-risks-and-technical-debt.md); [Glossary](../arc42/12-glossary.md)
- Supersedes: None
- Superseded by: None

## Context

Balance-bearing ledger identity must remain stable while externally presented
addresses such as NUBANs or provider virtual accounts can be issued, rotated,
scoped, or retired.

## Decision drivers

- Keep accounting references stable across address lifecycle changes.
- Support multiple address schemes without embedding routing semantics in ledger identity.
- Validate Nigerian account-number foundations without claiming unbuilt issuance or NIP interfaces.

## Considered options

- Use NUBAN as the ledger primary key; replacement or multi-address use would destabilize accounting references.
- Store each external address as an untyped account column; scheme-specific uniqueness and validation become ambiguous.
- Model immutable ledger-account identity separately from typed, lifecycle-managed account identifiers.

## Decision

<a id="account-identifier-boundary"></a>
<!-- migration-source: 08.01.01::01 -->
Ledger accounts use internal UUID identity. External identifiers resolve to a
ledger account and carry their own scheme, normalized value, institution or
provider coordinates, lifecycle, primary flag, and routing scope. Current code
provides domain and schema foundations; issuance, resolution, account-details,
and NIP-facing APIs remain unimplemented.

## Consequences

### Positive

Financial identity remains stable while address schemes evolve independently.

### Negative

Every payment or channel boundary needs an explicit resolution step before it
can address a ledger account.

### Risks

Treating foundation types or tables as a delivered API would overstate current
capability; arc42 exclusions and acceptance evidence keep that gap explicit.

## Compliance and verification

- Domain tests validate NUBAN normalization and check digits.
- Migration tests verify identifier reference and uniqueness foundations.
- Delivery remains Partial until issuance, resolution, account-details, and NIP APIs have acceptance evidence.

## Implementation evidence

- cf376ea91ee4bcc8f38aaf1ba282cca10f0e9676 changed: services/funds-core/src/main/java/com/corebanking/funds/domain/AccountIdentifier.java; services/funds-core/src/main/java/com/corebanking/funds/domain/AccountIdentifierScheme.java; services/funds-core/src/test/java/com/corebanking/funds/domain/NubanTest.java
- 1e88afca4ea62195088b3136e5a04c7e3a1e3915 changed: services/funds-core/src/main/resources/db/migration/V001__accounting_reference.sql; services/funds-core/src/test/java/com/corebanking/funds/infrastructure/postgres/MigrationIT.java
- d6891210fccca0874384b09a60a13aa632828f8d snapshot: services/funds-core/src/main/java/com/corebanking/funds/domain/AccountIdentifier.java; services/funds-core/src/main/java/com/corebanking/funds/domain/AccountIdentifierScheme.java; services/funds-core/src/test/java/com/corebanking/funds/domain/NubanTest.java
