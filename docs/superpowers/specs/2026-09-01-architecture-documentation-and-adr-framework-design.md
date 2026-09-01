# Architecture Documentation and ADR Framework Design

**Date:** 2026-09-01

**Status:** Approved design

**Scope:** Repository-wide architecture documentation governance
**Canonical documentation root:** `architecture/`

## 1. Purpose

This design establishes architecture documentation as version-controlled source in the same repository as the core-banking implementation. It defines where current architecture, proposed changes, decisions, diagrams, implementation plans, and historical material belong; how they relate; and how implementation changes keep them accurate.

The framework uses arc42 for current-state architecture and Architecture Decision Records (ADRs) for significant decisions. It deliberately avoids documenting every source file. Documentation focuses on responsibilities, constraints, boundaries, financial invariants, runtime interactions, deployment assumptions, and quality requirements.

## 2. Current repository problem

The repository currently contains:

- A comprehensive architecture document of more than 1,600 lines that mixes target architecture, PoC constraints, implemented facts, worked examples, acceptance expectations, and future design.
- A separate single-VPS infrastructure architecture document.
- Detailed implementation plans under `docs/superpowers/plans/`.
- Service-local operational and contract documentation under `services/funds-core/`.
- No short root architecture entry point, dedicated ADR collection, scripted architecture-diagram collection, or explicit current-versus-proposed governance rule.

The comprehensive document remains valuable source material, but its statements cannot all be treated as implemented facts. The migration must classify and preserve its content before archiving it.

## 3. Goals and non-goals

### 3.1 Goals

- Keep architecture documentation in Git beside the implementation.
- Provide a short, stable entry point for understanding the system.
- Make current state distinguishable from proposals without interpreting prose.
- Capture important decisions, alternatives, consequences, and verification evidence.
- Maintain textual diagram sources that can be reviewed and rendered automatically.
- Provide bidirectional traceability among architecture, ADRs, proposals, plans, pull requests, commits, tests, and operational evidence.
- Keep the process lightweight enough for the single-VM PoC while remaining suitable for later production evolution.

### 3.2 Non-goals

- Documenting every class, function, table, or source file.
- Treating implementation plans as current architecture.
- Introducing a documentation portal or publishing platform in the first iteration.
- Rewriting accepted ADR history after the fact.
- Claiming that implementation is correct whenever it differs from documentation.
- Converting all existing documentation in one unreviewed mechanical operation.

## 4. Considered approaches

### 4.1 Minimal overlay

Keep the comprehensive design intact and add only `ARCHITECTURE.md` and `architecture/adr/`.

This minimizes initial work, but leaves current and proposed states mixed and keeps the large document as an unstable authority. It does not resolve the central classification problem.

### 4.2 Modular arc42 framework

Extract verified current facts into modular arc42 sections, keep proposed designs separately, introduce ADRs, and maintain diagram sources as code.

This requires a deliberate initial classification pass but provides clear authority, focused review, and a sustainable change workflow. This is the selected approach.

### 4.3 Generated documentation portal

Adopt a documentation site generator such as MkDocs or Antora immediately.

This improves navigation and presentation but adds dependencies and publishing concerns before content authority and governance are stable. It may be reconsidered later through an ADR.

## 5. Repository structure

```text
ARCHITECTURE.md

architecture/
├── README.md
├── arc42/
│   ├── 01-introduction-and-goals.md
│   ├── 02-constraints.md
│   ├── 03-context-and-scope.md
│   ├── 04-solution-strategy.md
│   ├── 05-building-block-view.md
│   ├── 06-runtime-view.md
│   ├── 07-deployment-view.md
│   ├── 08-crosscutting-concepts.md
│   ├── 09-decisions.md
│   ├── 10-quality-requirements.md
│   ├── 11-risks-and-technical-debt.md
│   └── 12-glossary.md
├── adr/
│   ├── README.md
│   ├── template.md
│   └── 0001-<decision-title>.md
├── diagrams/
│   ├── README.md
│   ├── context.mmd
│   ├── containers.mmd
│   ├── funds-core-components.mmd
│   ├── posting-sequence.mmd
│   └── single-vm-deployment.mmd
├── proposals/
│   ├── README.md
│   └── <unimplemented-design>.md
├── infrastructure/
│   └── infra-ubuntu24.04-poc.md
└── archive/
    └── modern-core-banking-comprehensive-design-revised.md

docs/superpowers/plans/
services/<service>/README.md
services/<service>/docs/
```

The existing comprehensive design moves to `archive/` only after every material section has been classified as one or more of:

- Verified current fact extracted into arc42.
- Unimplemented design extracted into a proposal.
- Decision rationale captured in an ADR.
- Detailed implementation procedure retained in a plan or service document.
- Historical material retained only in the archive.

The migration records this classification in a checklist so content cannot disappear silently.

## 6. Documentation authority

When sources overlap, authority is interpreted in this order:

1. Executable implementation and tests provide evidence of actual behaviour.
2. `architecture/arc42/` describes reviewed, verified current architecture.
3. Accepted ADRs explain why architecturally significant choices were made.
4. `architecture/proposals/` describes designs that are not yet current.
5. `docs/superpowers/plans/` describes delivery steps rather than system truth.
6. Archived documents preserve history and are not authoritative.

A disagreement between executable behaviour and current-state documentation is a defect requiring review. The executable behaviour is evidence of what the system does, not proof that the behaviour is intended or correct. Resolution may change the implementation, documentation, decision record, or more than one of them.

Service-local documentation owns detailed operating instructions and contracts that would make the system-level architecture unwieldy. Arc42 documents link to those details and retain responsibility and boundary descriptions.

## 7. arc42 current-state model

The arc42 collection contains current-state facts only:

1. **Introduction and goals:** business goals, stakeholders, and system purpose.
2. **Constraints:** PoC resource limits, technology constraints, regulatory assumptions, and non-claims.
3. **Context and scope:** users, external systems, trust boundaries, and system interfaces.
4. **Solution strategy:** the few foundational approaches shaping the current implementation.
5. **Building-block view:** responsibilities, ownership, dependencies, and authoritative data boundaries.
6. **Runtime view:** important runtime scenarios such as posting, reversal, identifier resolution, and recovery.
7. **Deployment view:** the current single-VM topology, resource envelopes, persistence, networking, and failure domains.
8. **Crosscutting concepts:** money representation, idempotency, audit, security, observability, schema migration, and memory control.
9. **Decisions:** a concise index of relevant ADRs, not duplicated rationale.
10. **Quality requirements:** measurable quality scenarios and acceptance evidence.
11. **Risks and technical debt:** known current limitations, explicit deferrals, and mitigations.
12. **Glossary:** banking, accounting, Nigerian payment-system, and implementation terminology.

Each arc42 file begins with metadata in this form:

```yaml
---
title: Funds-core building-block view
status: current
owners:
  - funds-core
last_verified: 2026-09-01
related_adrs:
  - ADR-0003
code_refs:
  - services/funds-core/
---
```

Allowed arc42 `status` values are `current` and `deprecated`. A deprecated document must link to its replacement and should be removed or archived in a subsequent cleanup change.

`last_verified` means a named owner reviewed the described facts against implementation and tests on that date. It is not automatically changed by unrelated commits.

## 8. Proposed-state model

Unimplemented designs live under `architecture/proposals/` and are visibly marked:

```yaml
---
title: Conventional deposit products
status: proposed
owners:
  - funds-core
target_release: undecided
related_adrs: []
related_plans:
  - docs/superpowers/plans/2026-08-30-conventional-deposit-products-and-accrual-implementation.md
---
```

Allowed proposal statuses are `draft`, `proposed`, `approved`, `implementing`, `implemented`, `rejected`, and `superseded`.

An approved proposal is still not current architecture. It becomes current only after implementation and acceptance evidence merge. The completing change must:

- Update relevant arc42 documents.
- Update affected diagrams.
- Link the merge pull request or commit and verification evidence.
- Mark the proposal `implemented` and move it to the archive, or remove it after its Git history and current-state replacements are linked.
- Update related ADR implementation evidence.

## 9. Architecture Decision Records

ADRs use zero-padded sequential identifiers and kebab-case filenames, for example `0001-use-postgresql-as-the-system-of-record.md`. The document title starts with the matching identifier.

### 9.1 Required ADR fields

```markdown
# ADR-0001: Decision title

- Status: Proposed
- Decision date:
- Deciders:
- Scope:
- Implementation status: Not started
- Related pull requests:
- Related commits:
- Related architecture sections:
- Supersedes:
- Superseded by:

## Context

## Decision drivers

## Considered options

## Decision

## Consequences

### Positive

### Negative

### Risks

## Compliance and verification

## Implementation evidence
```

Empty relationship fields use `None`; required substantive sections may not be empty. Dates use ISO 8601 calendar format.

### 9.2 Decision lifecycle

Allowed decision statuses are:

```text
Proposed -> Accepted -> Superseded
                     -> Deprecated
Proposed -> Rejected
```

Decision status and implementation status are independent. Allowed implementation statuses are `Not started`, `Partial`, `Complete`, and `Not applicable`.

After acceptance, decision rationale and recorded alternatives are immutable. Later pull-request, commit, verification, supersession, and implementation-status links may be appended. A material reversal or alteration requires a new ADR that supersedes the original.

### 9.3 ADR threshold

An ADR is required when a change materially affects any of:

- Service or module boundaries.
- Ownership of financial invariants.
- Ledger, journal, balance, proof, or accounting semantics.
- Persistence technology or authoritative data ownership.
- Public, provider-facing, or cross-language contracts.
- Security, regulatory, audit, or trust boundaries.
- Deployment topology or failure domains.
- Resource and memory budgets.
- Consistency, concurrency, idempotency, recovery, or delivery guarantees.
- A significant, deliberately accepted technical debt.

Routine refactoring, local implementation details, documentation corrections, and compatible dependency patches do not require ADRs unless they cross one of these thresholds.

## 10. Diagrams as code

Mermaid is the default diagram language because it is textual and commonly rendered in Markdown platforms. PlantUML may be introduced for a diagram that Mermaid cannot express clearly, without replacing Mermaid by default.

Every diagram source states:

- `CURRENT` or `PROPOSED` state in its title.
- Abstraction level and intended question answered.
- Owner.
- Related arc42 section.
- Related ADRs.
- Last verification date.

The initial diagram set covers:

- System context.
- Container and service topology.
- Funds-core component responsibilities.
- Posting transaction sequence.
- Single-VM deployment and memory allocation.

Account identifier and NIP inbound diagrams remain proposed until that runtime path is implemented. Generated SVG files are committed only when a required documentation target cannot render the diagram source directly.

## 11. Traceability

Traceability is bidirectional:

- Arc42 sections link to applicable ADRs, diagram sources, code roots, and detailed service documents.
- ADRs link to affected arc42 sections, proposals, pull requests or commits, and verification evidence.
- Proposals link to their decisions and implementation plans.
- Implementation plans link back to proposals and ADRs rather than redefining architectural authority.
- Pull requests identify affected architecture documents, ADRs, proposals, and diagrams.

For local work without a pull request, a full Git commit hash is accepted as implementation evidence. When a pull request later exists, its stable URL is added. Links to branches alone are insufficient because branches can move or be deleted.

## 12. Change workflow

Architecturally significant work follows this flow:

```text
Identify a decision
  -> create a Proposed ADR
  -> review and Accept or Reject it
  -> create or update an approved proposal
  -> create an implementation plan when needed
  -> implement and verify
  -> update current arc42 facts and diagrams in the completing change
  -> attach immutable pull-request or commit evidence
  -> mark ADR implementation status Complete
  -> archive the implemented proposal
```

An accepted ADR authorizes a direction but does not claim that the system already implements it. Current-state architecture changes only with the implementation and its evidence.

The pull-request template asks:

- Does this change an architectural boundary, invariant, contract, resource budget, or quality guarantee?
- Is a new or superseding ADR required?
- Which current-state arc42 sections change?
- Does this implement, invalidate, or supersede a proposal?
- Which diagrams change?
- What automated tests or operational evidence verify the new documentation claim?

Not every pull request must modify architecture documentation. It must explicitly answer the architecture-impact question so omission is deliberate.

## 13. Automation and enforcement

The first iteration uses lightweight repository scripts and CI rather than a documentation portal. Checks should be introduced incrementally in this order:

1. Broken internal Markdown links and missing referenced local paths.
2. Unique, sequential ADR identifiers and valid ADR status values.
3. Required ADR headings and non-empty substantive sections.
4. Valid current/proposed placement and document status values.
5. Mermaid syntax rendering; PlantUML syntax if PlantUML sources exist.
6. Pull-request architecture-impact checklist presence.
7. Reporting of stale `last_verified` dates without initially blocking merges.

Staleness should begin as a report, not a hard failure, because elapsed time alone does not prove inaccuracy. A later ADR may establish risk-based blocking thresholds.

## 14. Review cadence and ownership

- **Every pull request:** assess architecture impact and update affected artifacts.
- **Monthly during active PoC development:** review current-versus-proposed classification and unimplemented accepted ADRs.
- **At each delivery milestone:** verify relevant arc42 views and diagrams against the implementation and acceptance evidence.
- **Quarterly or before production-readiness claims:** review all accepted ADRs, risks, technical debt, deployment assumptions, and resource budgets.

Every architecture document has at least one owning service or repository role. Ownership means responsibility for review, not unilateral authority to change cross-cutting decisions.

## 15. Root `ARCHITECTURE.md`

The root document remains short and stable. It contains only:

- System purpose and current scope.
- Explicit current-state and PoC labels.
- Principal constraints and non-claims.
- A compact component-responsibility summary.
- Core financial and operational invariants.
- Links to arc42 sections, the ADR index, diagrams, proposals, and service documentation.

Detailed runtime flows, schema definitions, examples, operational procedures, and future designs belong in linked documents. The root file is reviewed at milestones rather than synchronized line by line with every code commit.

## 16. Initial adoption sequence

1. Add the root entry point, framework README files, ADR template, arc42 skeleton, and diagram conventions.
2. Create an explicit migration inventory for every section of the existing comprehensive design.
3. Establish initial ADRs for already-implemented foundational decisions, clearly marked as retrospective records.
4. Extract and verify current funds-core and single-VM facts against source code, migrations, tests, runtime configuration, and existing operational documentation.
5. Extract unimplemented product, provider, and production-target designs into proposals.
6. Create and validate the initial Mermaid diagrams.
7. Update implementation plans with backlinks without treating those plans as current architecture.
8. Archive the comprehensive design only after the migration inventory has no unresolved material sections.
9. Add link, ADR-structure, and diagram checks to CI.
10. Add the architecture-impact checklist to the pull-request template.

The adoption work must preserve existing documentation until its replacement is reviewed. Moving the comprehensive design is a late migration step, not the first one.

## 17. Acceptance criteria

The framework is successfully established when:

- `ARCHITECTURE.md` provides a concise route into the documentation.
- All twelve arc42 sections exist and distinguish verified current facts from links to proposed work.
- ADR conventions, lifecycle, threshold, and template are documented.
- Initial foundational decisions have ADRs with implementation evidence.
- Diagram sources render successfully and identify their state.
- The comprehensive design has a complete migration inventory.
- No material content is archived without a classified replacement or explicit historical disposition.
- Plans, proposals, ADRs, arc42 documents, and implementation evidence are linked in both directions where applicable.
- Pull requests must declare architecture impact.
- Automated checks validate links, ADR structure, and diagram syntax.

## 18. Risks and mitigations

### Documentation drift

Mitigate through pull-request impact declarations, explicit ownership, evidence links, milestone verification, and gradual CI enforcement.

### Excessive ceremony

Apply the ADR threshold only to materially significant decisions. Keep service detail in service-local documentation and keep the root entry point concise.

### False current-state claims during migration

Require evidence before extraction into arc42. Unverified material stays in the original document or becomes an explicitly labelled proposal.

### Loss of historical rationale

Archive only after classification, retain Git history, use retrospective ADR labels, and supersede rather than rewrite accepted decisions.

### Tooling complexity

Start with Markdown, Mermaid, simple scripts, and existing CI. Introduce a publishing platform only through a later justified ADR.

## 19. Key decisions made by this design

- `architecture/` remains the canonical architecture-documentation root.
- A short root `ARCHITECTURE.md` is the stable entry point.
- arc42 contains verified current state only.
- Proposals hold non-current designs, including approved but unimplemented designs.
- ADR decision status is separate from implementation status.
- Mermaid is the default diagram language.
- Implementation plans remain delivery artifacts, not architecture authority.
- The existing comprehensive design is classified before it is archived.
- Lightweight validation precedes any documentation-site platform.
