# Core Banking Architecture

## Current state

The only implemented application slice is the Java 25/Quarkus `funds-core`
accounting kernel. It is the current source of truth for posting and the
financial behaviour it implements. Current architecture is recorded in the
reviewed arc42 collection when it is introduced.

## PoC constraints and non-claims

This repository does not currently contain a Go service, NIP integration, a
full Compose platform, a customer-facing API, or a production topology. Those
subjects are proposed work until implementation and acceptance evidence make
them current. The PoC remains deliberately small and must not be represented
as production-ready solely because a design or implementation plan exists.

## Component responsibilities

`funds-core` owns the implemented accounting-kernel responsibilities. It
enforces accounting behaviour within its Java/Quarkus boundary; detailed
runtime, storage, and operational facts belong in current-state arc42 views
and service-local documentation.

## Non-negotiable financial invariants

Financial changes must preserve balanced journal semantics, use exact monetary
representation, retain an auditable record, and protect idempotent processing
where a request can be retried. The reviewed current-state views and accepted
ADRs define the applicable evidence and boundaries for those invariants.

## Documentation map

Current architecture is organized in these twelve arc42 views:

- [Introduction and goals](architecture/arc42/01-introduction-and-goals.md)
- [Constraints](architecture/arc42/02-constraints.md)
- [Context and scope](architecture/arc42/03-context-and-scope.md)
- [Solution strategy](architecture/arc42/04-solution-strategy.md)
- [Building-block view](architecture/arc42/05-building-block-view.md)
- [Runtime view](architecture/arc42/06-runtime-view.md)
- [Deployment view](architecture/arc42/07-deployment-view.md)
- [Crosscutting concepts](architecture/arc42/08-crosscutting-concepts.md)
- [Decisions](architecture/arc42/09-decisions.md)
- [Quality requirements](architecture/arc42/10-quality-requirements.md)
- [Risks and technical debt](architecture/arc42/11-risks-and-technical-debt.md)
- [Glossary](architecture/arc42/12-glossary.md)

The architecture index, ADR collection, diagrams, and proposals live under
`architecture/`. Plans describe delivery rather than current system truth.

## Proposed work

Unimplemented designs belong in `architecture/proposals/`. An accepted ADR or
approved proposal authorizes a direction but does not make that direction
current; current-state documentation changes with implementation and reviewed
evidence.
