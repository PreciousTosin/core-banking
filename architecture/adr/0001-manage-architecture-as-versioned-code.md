# ADR-0001: Manage architecture as versioned code

- Status: Accepted
- Retrospective: No
- Decision date: 2026-09-01
- Deciders: Architecture maintainers
- Scope: Architecture documentation and ADR framework governance
- Implementation status: Complete
- Related proposals: [Full PoC platform](../proposals/README.md#full-poc-platform); [Production platform](../proposals/README.md#production-platform)
- Related implementation plans: [Architecture Documentation and ADR Framework Implementation Plan](../../docs/superpowers/plans/2026-09-01-architecture-documentation-and-adr-framework-implementation.md)
- Related pull requests: None
- Related commits: None
- Related architecture sections: [Introduction and goals](../arc42/01-introduction-and-goals.md); [Decisions index](../arc42/09-decisions.md)
- Supersedes: None
- Superseded by: None

## Context

The repository already carried architecture descriptions and implementation
plans, but it lacked a durable authority hierarchy, lifecycle rules, and
machine-checked traceability between current facts, decisions, and proposals.

## Decision drivers

- Preserve architecture history beside the implementation it governs.
- Make current, proposed, and historical claims mechanically distinguishable.
- Give material decisions stable identifiers and reviewable lifecycle edges.

## Considered options

- Keep architecture in one comprehensive document; this obscures authority and mixes implemented facts with proposals.
- Move architecture to an external wiki; this separates decisions from code review and immutable Git evidence.
- Maintain modular arc42 documents, ADRs, proposals, and automated repository validation.

## Decision

Architecture is managed as versioned code under `architecture/`. Current facts
live in arc42 views, accepted decisions live in append-protected ADRs, and
unimplemented designs live in proposals. Plans describe delivery and link to
their governing decision but do not become architecture authority.

## Consequences

### Positive

Architecture changes are reviewable with code, stable links can cross artifacts,
and lifecycle/evidence failures are caught before integration.

### Negative

Authors must maintain reciprocal links, structured metadata, evidence bindings,
and migration classifications when architecture changes.

### Risks

Validation can become ceremonial if contracts are weakened; behavior-backed
tests and immutable accepted-record sections constrain that risk.

## Compliance and verification

- Run `python3 architecture/scripts/validate_architecture.py --root . --checks metadata,adrs,links`.
- Run the Git-aware accepted-record endpoint and edge-range checks for every review range.
- `python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v` — PASS (exit 0).
- `python3 architecture/scripts/validate_architecture.py --root .` — PASS (exit 0).
- `architecture/scripts/render-diagrams.sh` — PASS (exit 0; locked Mermaid dependencies plus npm/Puppeteer/XDG state were confined to one invocation-owned temporary root, that root was removed, and every governed source rendered).

## Implementation evidence

- 0e46650dcb382bf4ddc040e0ec73e98675dff40b changed: docs/superpowers/specs/2026-09-01-architecture-documentation-and-adr-framework-design.md
- 81fd06e952e31d4da3d5902286ef09abb3be1ff4 changed: .github/pull_request_template.md; .github/workflows/architecture-docs.yml; architecture/README.md; architecture/scripts/tests/test_validate_architecture.py; architecture/scripts/validate_architecture.py
- 81fd06e952e31d4da3d5902286ef09abb3be1ff4 snapshot: .github/pull_request_template.md; .github/workflows/architecture-docs.yml; .gitignore; ARCHITECTURE.md; architecture/README.md; architecture/adr/0001-manage-architecture-as-versioned-code.md; architecture/adr/0002-centralize-financial-invariants-in-funds-core.md; architecture/adr/0003-use-signed-integer-minor-units.md; architecture/adr/0004-use-postgresql-as-the-authoritative-ledger.md; architecture/adr/0005-use-immutable-journals-and-additive-corrections.md; architecture/adr/0006-couple-idempotency-and-outbox-to-ledger-commit.md; architecture/adr/0007-separate-ledger-identity-from-account-addresses.md; architecture/adr/0008-target-an-eight-gib-single-vm-evidence-suite.md; architecture/adr/README.md; architecture/adr/template.md; architecture/arc42/01-introduction-and-goals.md; architecture/arc42/02-constraints.md; architecture/arc42/03-context-and-scope.md; architecture/arc42/04-solution-strategy.md; architecture/arc42/05-building-block-view.md; architecture/arc42/06-runtime-view.md; architecture/arc42/07-deployment-view.md; architecture/arc42/08-crosscutting-concepts.md; architecture/arc42/09-decisions.md; architecture/arc42/10-quality-requirements.md; architecture/arc42/11-risks-and-technical-debt.md; architecture/arc42/12-glossary.md; architecture/archive/comprehensive-design-migration-inventory.md; architecture/archive/comprehensive-design-migration-review.md; architecture/archive/modern-core-banking-comprehensive-design-revised.md; architecture/archive/proposals/README.md; architecture/diagrams/README.md; architecture/diagrams/containers.mmd; architecture/diagrams/context.mmd; architecture/diagrams/funds-core-components.mmd; architecture/diagrams/posting-sequence.mmd; architecture/diagrams/single-vm-deployment.mmd; architecture/infrastructure/infra-ubuntu24.04-poc.md; architecture/proposals/README.md; architecture/proposals/account-identifiers-and-nip-inbound.md; architecture/proposals/conventional-deposit-products-and-accrual.md; architecture/proposals/full-poc-platform.md; architecture/proposals/non-interest-banking-products.md; architecture/proposals/production-platform.md; architecture/proposals/providers-and-reconciliation.md; architecture/scripts/render-diagrams.sh; architecture/scripts/tests/test_validate_architecture.py; architecture/scripts/validate_architecture.py; architecture/tooling/package-lock.json; architecture/tooling/package.json; docs/superpowers/plans/2026-08-30-account-identifiers-and-nip-inbound-implementation.md; docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md; docs/superpowers/plans/2026-08-30-conventional-deposit-products-and-accrual-implementation.md; docs/superpowers/plans/2026-08-30-non-interest-banking-products-implementation.md; docs/superpowers/plans/2026-09-01-architecture-documentation-and-adr-framework-implementation.md; services/funds-core/README.md
