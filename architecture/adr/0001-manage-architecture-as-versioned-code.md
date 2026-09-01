# ADR-0001: Manage architecture as versioned code

- Status: Accepted
- Retrospective: No
- Decision date: 2026-09-01
- Deciders: Architecture maintainers
- Scope: Architecture documentation and ADR framework governance
- Implementation status: Partial
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

## Implementation evidence

- 0e46650dcb382bf4ddc040e0ec73e98675dff40b changed: docs/superpowers/specs/2026-09-01-architecture-documentation-and-adr-framework-design.md
