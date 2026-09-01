# Architecture Documentation and ADR Framework Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish a version-controlled arc42 architecture baseline, ADR lifecycle, diagram-as-code workflow, proposal separation, migration inventory, traceability convention, and automated documentation gate for the core-banking repository.

**Architecture:** Keep `architecture/` as the canonical documentation root and add a short root `ARCHITECTURE.md` entry point. Verified implementation facts live in modular arc42 documents; accepted decisions live in ADRs; unimplemented designs live in proposals; the existing comprehensive design is archived only after a machine-checked classification inventory reaches zero unresolved sections.

**Tech Stack:** Markdown, Mermaid CLI 11.16.0, Python 3.12+ standard library, Node.js 20+, npm lockfile, GitHub Actions on Ubuntu 24.04.

**Spec:** `docs/superpowers/specs/2026-09-01-architecture-documentation-and-adr-framework-design.md`

## Global Constraints

- `architecture/` remains the canonical architecture-documentation root.
- `ARCHITECTURE.md` remains a short, stable entry point; it does not duplicate detailed arc42 content.
- `architecture/arc42/` contains verified current-state facts only.
- Approved but unimplemented designs remain non-current and live under `architecture/proposals/` or in a clearly labelled detailed proposal document.
- ADR decision status and implementation status are independent.
- Accepted ADR rationale is immutable; material changes require a superseding ADR.
- Mermaid is the default diagram language; every diagram declares `CURRENT` or `PROPOSED`.
- Implementation plans describe delivery and are not architecture authority.
- The comprehensive design stays at its current path until every material section has a classified, linked disposition.
- Documentation/diagram tooling is development-only and adds no Java or Go runtime dependency.
- Python validation uses the standard library only; do not add PyYAML or another runtime parser.
- Pin `@mermaid-js/mermaid-cli` exactly to `11.16.0` and commit the npm lockfile.
- Local work without a pull request uses a full Git commit hash as implementation evidence; branch names are not evidence.
- Do not claim that infrastructure manifests are deployed or verified merely because their files exist.
- Every task ends with its focused validation and a commit before the next task starts.

## File Structure

### Stable entry points and governance

- Create `ARCHITECTURE.md`: concise current system summary, constraints, core invariants, and navigation.
- Create `architecture/README.md`: authority hierarchy, current/proposed rules, ownership, change workflow, and review cadence.
- Create `architecture/adr/README.md`: ADR threshold, lifecycle, numbering, mutability, and retrospective-record policy.
- Create `architecture/adr/template.md`: required ADR fields and headings.
- Create `architecture/proposals/README.md`: proposal statuses and promotion/archive workflow.
- Create `architecture/diagrams/README.md`: Mermaid metadata, state labels, render command, and ownership rules.

### Current-state arc42 baseline

- Create all twelve files under `architecture/arc42/` exactly as named in the specification.
- Each file owns one arc42 concern and begins with the approved metadata block.
- `architecture/arc42/09-decisions.md` is an index and does not duplicate ADR rationale.

### Migration and historical preservation

- Create `architecture/archive/comprehensive-design-migration-inventory.md`: one row for each top-level section in the comprehensive design, with classification, destination, evidence, and resolution.
- Move `architecture/modern-core-banking-comprehensive-design-revised.md` to `architecture/archive/modern-core-banking-comprehensive-design-revised.md` only in the final archive-cutover task.

### Decisions and proposals

- Create ADRs `0001` through `0008` for documentation governance and implemented foundational decisions.
- Create focused proposals for identifier/NIP delivery, conventional deposits, non-interest banking, the full PoC platform, production platform evolution, and provider/reconciliation capabilities.
- Modify the four existing implementation plans to link to their governing proposals and ADRs.

### Diagrams and tooling

- Create five Mermaid sources under `architecture/diagrams/`.
- Create `architecture/tooling/package.json` and `architecture/tooling/package-lock.json` for the pinned Mermaid CLI.
- Create `architecture/scripts/render-diagrams.sh` for deterministic source validation into a temporary output directory.
- Create `architecture/scripts/validate_architecture.py` and `architecture/scripts/tests/test_validate_architecture.py` for repository rules.

### Repository workflow

- Create `.github/pull_request_template.md` with an architecture-impact declaration.
- Create `.github/workflows/architecture-docs.yml` to run validator tests, repository validation, and Mermaid rendering.
- Modify `.gitignore` to exclude `architecture/tooling/node_modules/` and generated local diagram output.

---

### Task 1: Build the architecture-document validator

**Files:**
- Create: `architecture/scripts/validate_architecture.py`
- Create: `architecture/scripts/tests/test_validate_architecture.py`

**Interfaces:**
- Consumes: repository root path and optional comma-separated check names.
- Produces: `validate_repository(root: Path, checks: frozenset[str]) -> list[str]`, where an empty list means success and each non-empty string is one actionable validation error.
- CLI: `python3 architecture/scripts/validate_architecture.py --root . --checks structure,metadata,adrs,links,diagrams,migration`; omit `--checks` to run all checks.

- [ ] **Step 1: Write failing validator unit tests**

Create temporary repositories in `unittest.TestCase` methods and cover these exact failures:

```python
def test_arc42_rejects_proposed_status(self):
    path = self.write("architecture/arc42/01-introduction-and-goals.md", """---
title: Introduction and goals
status: proposed
owners:
  - architecture
last_verified: 2026-09-01
related_adrs: []
code_refs: []
---
# Introduction and goals
""")
    errors = validator.validate_metadata(self.root)
    self.assertTrue(any("arc42 status must be current or deprecated" in error for error in errors))

def test_adr_sequence_rejects_a_gap(self):
    self.write_adr("0001-first.md", "ADR-0001", "Accepted", "Complete")
    self.write_adr("0003-third.md", "ADR-0003", "Accepted", "Complete")
    errors = validator.validate_adrs(self.root)
    self.assertTrue(any("ADR sequence gap: expected 0002" in error for error in errors))

def test_relative_link_rejects_a_missing_target(self):
    self.write("ARCHITECTURE.md", "[missing](architecture/missing.md)\n")
    errors = validator.validate_links(self.root)
    self.assertTrue(any("architecture/missing.md does not exist" in error for error in errors))

def test_migration_inventory_rejects_unresolved_rows(self):
    self.write("architecture/archive/comprehensive-design-migration-inventory.md", """
| Source section | Classification | Destination | Evidence | Resolution |
|---|---|---|---|---|
| 1. Purpose | current | architecture/arc42/01-introduction-and-goals.md | services/funds-core/README.md | unresolved |
""")
    errors = validator.validate_migration_inventory(self.root)
    self.assertEqual(1, len(errors))
    self.assertIn("unresolved migration row", errors[0])

def test_diagram_requires_state_and_traceability_metadata(self):
    self.write("architecture/diagrams/context.mmd", "flowchart LR\nA --> B\n")
    errors = validator.validate_diagrams(self.root)
    self.assertTrue(any("missing Mermaid metadata: state" in error for error in errors))
```

- [ ] **Step 2: Run the unit tests to verify they fail**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: failure because `validate_architecture` and its validation functions do not exist.

- [ ] **Step 3: Implement the standard-library validator**

Use these constants and public functions exactly:

```python
ARC42_FILES = (
    "01-introduction-and-goals.md",
    "02-constraints.md",
    "03-context-and-scope.md",
    "04-solution-strategy.md",
    "05-building-block-view.md",
    "06-runtime-view.md",
    "07-deployment-view.md",
    "08-crosscutting-concepts.md",
    "09-decisions.md",
    "10-quality-requirements.md",
    "11-risks-and-technical-debt.md",
    "12-glossary.md",
)
ARC42_STATUSES = frozenset({"current", "deprecated"})
PROPOSAL_STATUSES = frozenset(
    {"draft", "proposed", "approved", "implementing", "implemented", "rejected", "superseded"}
)
ADR_STATUSES = frozenset({"Proposed", "Accepted", "Superseded", "Deprecated", "Rejected"})
ADR_IMPLEMENTATION_STATUSES = frozenset({"Not started", "Partial", "Complete", "Not applicable"})
CHECKS = frozenset({"structure", "metadata", "adrs", "links", "diagrams", "migration"})

Validator = Callable[[Path], list[str]]
VALIDATORS: dict[str, Validator] = {
    "structure": validate_structure,
    "metadata": validate_metadata,
    "adrs": validate_adrs,
    "links": validate_links,
    "diagrams": validate_diagrams,
    "migration": validate_migration_inventory,
}

def validate_repository(root: Path, checks: frozenset[str] = CHECKS) -> list[str]:
    errors: list[str] = []
    for check in sorted(checks):
        errors.extend(VALIDATORS[check](root))
    return sorted(errors)
```

Define `parse_front_matter(path: Path) -> dict[str, str | list[str]]`, each `validate_*` function referenced by `VALIDATORS`, and `main(argv: Sequence[str] | None = None) -> int` with those exact names and types before constructing `VALIDATORS`.

Implement the bodies with these exact rules:

- Parse only the repository's YAML subset: scalar `key: value`, `key: []`, and indented `- item` lists between the first two `---` lines.
- `structure` requires the root entry point, governance READMEs/templates, all twelve arc42 files, the diagram README, proposal README, and migration inventory.
- `metadata` requires `title`, `status`, `owners`, and `last_verified` on arc42 files; proposal files require `title`, `status`, `owners`, `related_adrs`, and `related_plans`.
- `adrs` requires a contiguous filename sequence starting at `0001`, a matching `# ADR-NNNN:` title, all template headings, valid decision/implementation statuses, and `None` rather than blank relationship fields.
- `links` checks relative Markdown link targets after removing fragments; skip `http`, `https`, `mailto`, and pure `#fragment` targets.
- `diagrams` requires the first six lines to include Mermaid comments for `state`, `owner`, `arc42`, `adrs`, and `last_verified`; state is exactly `CURRENT` or `PROPOSED`; referenced local arc42 paths and ADR IDs must exist.
- `migration` parses table rows below the inventory header, rejects `unresolved`, requires exactly sections `1` through `27`, and requires every non-`historical-only` destination path to exist.
- Sort errors by path and message so local and CI output is deterministic.
- Print each error to stderr and return `1`; print `architecture validation passed` and return `0` when clean.

- [ ] **Step 4: Run validator unit tests**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: all validator unit tests pass without third-party packages.

- [ ] **Step 5: Commit the validator**

```bash
git add architecture/scripts/validate_architecture.py architecture/scripts/tests/test_validate_architecture.py
git commit -m "test: add architecture documentation validator"
```

### Task 2: Establish navigation and documentation governance

**Files:**
- Create: `ARCHITECTURE.md`
- Create: `architecture/README.md`
- Create: `architecture/adr/README.md`
- Create: `architecture/adr/template.md`
- Create: `architecture/proposals/README.md`
- Create: `architecture/diagrams/README.md`

**Interfaces:**
- Consumes: authority, metadata, lifecycle, and review rules from the approved specification.
- Produces: stable human entry points and templates consumed by every later documentation task.

- [ ] **Step 1: Add a failing governance-structure test**

Add `test_required_governance_files` to the validator tests. It creates a temporary empty root, calls `validate_structure`, and asserts the error list names all six files above.

- [ ] **Step 2: Run the focused test and verify failure**

Run:

```bash
python3 -m unittest architecture.scripts.tests.test_validate_architecture.ValidatorTest.test_required_governance_files -v
```

Expected: fail until the fixture helper and required-file assertion agree with the governance structure.

- [ ] **Step 3: Write the root entry point**

Keep `ARCHITECTURE.md` below 180 lines. Use these headings and claims:

```markdown
# Core Banking Architecture

## Current state
## PoC constraints and non-claims
## Component responsibilities
## Non-negotiable financial invariants
## Documentation map
## Proposed work
```

State explicitly that the only implemented application slice is the Java 25/Quarkus `funds-core` accounting kernel; no Go service, NIP integration, full Compose platform, customer-facing API, or production topology is current. Reserve the documentation map using the twelve approved arc42 paths as inline code; Task 3 converts them to links after the files exist.

- [ ] **Step 4: Write governance and templates**

Copy the approved rules into focused documents:

- `architecture/README.md`: authority order, ownership, update triggers, pull-request traceability, review cadence, and archive rule.
- `architecture/adr/README.md`: statuses, implementation statuses, ADR threshold, immutable accepted rationale, supersession, full-hash evidence, and retrospective ADR label.
- `architecture/adr/template.md`: include `Retrospective: No` in addition to every field and heading specified by the design.
- `architecture/proposals/README.md`: statuses and the rule that `approved` does not mean `current`.
- `architecture/diagrams/README.md`: exact five-line metadata contract and render command.

Use this diagram header example:

```text
%% state: CURRENT
%% owner: funds-core
%% arc42: architecture/arc42/05-building-block-view.md
%% adrs: ADR-0002, ADR-0004
%% last_verified: 2026-09-01
```

- [ ] **Step 5: Validate focused files and links**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
python3 architecture/scripts/validate_architecture.py --root . --checks links
```

Expected: unit tests pass; link validation passes because not-yet-created arc42 paths are plain code until Task 3.

- [ ] **Step 6: Commit governance**

```bash
git add ARCHITECTURE.md architecture/README.md architecture/adr/README.md architecture/adr/template.md architecture/proposals/README.md architecture/diagrams/README.md architecture/scripts/tests/test_validate_architecture.py
git commit -m "docs: establish architecture governance"
```

### Task 3: Create the verified current-state arc42 baseline

**Files:**
- Create: `architecture/arc42/01-introduction-and-goals.md`
- Create: `architecture/arc42/02-constraints.md`
- Create: `architecture/arc42/03-context-and-scope.md`
- Create: `architecture/arc42/04-solution-strategy.md`
- Create: `architecture/arc42/05-building-block-view.md`
- Create: `architecture/arc42/06-runtime-view.md`
- Create: `architecture/arc42/07-deployment-view.md`
- Create: `architecture/arc42/08-crosscutting-concepts.md`
- Create: `architecture/arc42/09-decisions.md`
- Create: `architecture/arc42/10-quality-requirements.md`
- Create: `architecture/arc42/11-risks-and-technical-debt.md`
- Create: `architecture/arc42/12-glossary.md`
- Modify: `ARCHITECTURE.md`

**Interfaces:**
- Consumes: implemented evidence in `services/funds-core/`, database migrations `V001` through `V006`, funds-core tests, infrastructure manifests, and the approved authority rules.
- Produces: the canonical current-state architecture baseline linked from the root entry point.

- [ ] **Step 1: Add failing current-state metadata tests**

Add tests that require exactly the twelve filenames, reject `status: proposed` under `arc42/`, reject absent owners, and reject a `code_refs` path that does not exist.

- [ ] **Step 2: Run focused tests and verify failure**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: new structure tests fail because `architecture/arc42/` is absent.

- [ ] **Step 3: Write arc42 sections 1 through 4**

Use `status: current`, owner `architecture`, and `last_verified: 2026-09-01`. Record only these verified facts:

- The repository implements a funds-core accounting kernel, not a complete bank.
- The current module uses Java 25, Quarkus, Flyway migrations, and PostgreSQL 18.6 integration evidence.
- The PoC target is constrained to an 8 GiB single VM, but the full topology is proposed and not deployed by current repository evidence.
- The kernel owns exact money, journal validation, posting, reversals, balances, chart governance, proofs, database roles, and outbox persistence.
- External actors are currently developers/operators, PostgreSQL, and test infrastructure; customer channels, providers, NIBSS/NIP, Go services, brokers, and workflow engines are outside the implemented context.

Link code roots and existing service documentation as evidence.

- [ ] **Step 4: Write arc42 sections 5 through 8**

Document these current blocks and flows:

- Domain records and invariants under `services/funds-core/src/main/java/com/corebanking/funds/domain/`.
- Application services for posting, reversal, hashing, validation, transaction deadlines, and accounting proofs.
- PostgreSQL repositories and migrations as the authoritative persistence boundary.
- Runtime startup guard and bounded runtime configuration.
- Posting flow: verify typed request hash, begin serializable transaction, acquire idempotency ownership, validate/lock book-period-account state, assign sequences, validate journal, persist journal/postings, update balances/control projection, write outbox, complete idempotency, commit.
- Reversal flow: load original facts, construct exact negated linked journal in an open period, use the trusted reversal path, and preserve additive history.
- Proof flow: independently aggregate immutable source postings and compare materialised balance/control projections.
- Current deployment evidence: a JVM Dockerfile with bounded memory and smoke script; Kubernetes/Helm files exist but do not prove a deployed full platform.

- [ ] **Step 5: Write arc42 sections 9 through 12**

For decisions, initially link only the ADR template and state that Task 5 creates the decision index. For quality and risk, capture the implemented acceptance boundary from `services/funds-core/README.md`, including explicit exclusions. Define glossary entries for debit, credit, signed posting, natural balance, journal, posting, ledger account, external account identifier, NUBAN, idempotency, reversal, outbox, book, chart version, accounting period, control account, trial balance, current state, and proposed state.

- [ ] **Step 6: Complete root navigation and validate**

Add links from `ARCHITECTURE.md` to all twelve arc42 files. Run:

```bash
python3 architecture/scripts/validate_architecture.py --root . --checks metadata,links
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: metadata, links, and unit tests pass. Full structure validation begins after Task 4 creates the required migration inventory.

- [ ] **Step 7: Commit current-state baseline**

```bash
git add ARCHITECTURE.md architecture/arc42 architecture/scripts/tests/test_validate_architecture.py
git commit -m "docs: add current-state arc42 baseline"
```

### Task 4: Classify every section of the comprehensive design

**Files:**
- Create: `architecture/archive/comprehensive-design-migration-inventory.md`
- Modify: `architecture/scripts/tests/test_validate_architecture.py`

**Interfaces:**
- Consumes: all 27 top-level sections of `architecture/modern-core-banking-comprehensive-design-revised.md`.
- Produces: a complete classification gate that Task 8 must satisfy before archive cutover.

- [ ] **Step 1: Add failing migration-inventory tests**

Test that the inventory must contain exactly one row for each integer section `1` through `27`, that destinations exist, that resolution is `resolved`, and that duplicate or missing section numbers fail.

- [ ] **Step 2: Run the focused tests and verify failure**

Run:

```bash
python3 -m unittest architecture.scripts.tests.test_validate_architecture.ValidatorTest.test_migration_inventory_requires_sections_one_through_twenty_seven -v
```

Expected: fail because the inventory does not exist.

- [ ] **Step 3: Create the migration inventory**

Use this exact table schema:

```markdown
| Source section | Classification | Destination | Evidence | Resolution |
|---|---|---|---|---|
```

Create one row for each section `1` through `27`. Use one of these classifications: `current`, `proposal`, `decision`, `service-detail`, `plan-detail`, or `historical-only`. Multiple destinations are semicolon-separated repository paths. The evidence column must contain at least one code, test, migration, or current documentation path for `current` rows. Proposal rows link the proposal that Task 7 will create and remain `unresolved` until that file exists. No row may resolve merely by linking back to the source comprehensive design.

- [ ] **Step 4: Validate the inventory's deliberate interim state**

Run:

```bash
python3 architecture/scripts/validate_architecture.py --root . --checks migration
```

Expected: fail only on proposal and ADR destinations scheduled for Tasks 5 and 7. Record the exact failures in the task review; other error types must be fixed now.

- [ ] **Step 5: Commit the classification inventory**

```bash
git add architecture/archive/comprehensive-design-migration-inventory.md architecture/scripts/tests/test_validate_architecture.py
git commit -m "docs: inventory comprehensive architecture migration"
```

### Task 5: Record foundational architecture decisions

**Files:**
- Create: `architecture/adr/0001-manage-architecture-as-versioned-code.md`
- Create: `architecture/adr/0002-centralize-financial-invariants-in-funds-core.md`
- Create: `architecture/adr/0003-use-signed-integer-minor-units.md`
- Create: `architecture/adr/0004-use-postgresql-as-the-authoritative-ledger.md`
- Create: `architecture/adr/0005-use-immutable-journals-and-additive-corrections.md`
- Create: `architecture/adr/0006-couple-idempotency-and-outbox-to-ledger-commit.md`
- Create: `architecture/adr/0007-separate-ledger-identity-from-account-addresses.md`
- Create: `architecture/adr/0008-target-an-eight-gib-single-vm-evidence-suite.md`
- Modify: `architecture/arc42/09-decisions.md`
- Modify: relevant `related_adrs` metadata in `architecture/arc42/*.md`
- Modify: `architecture/archive/comprehensive-design-migration-inventory.md`
- Modify: `architecture/scripts/tests/test_validate_architecture.py`

**Interfaces:**
- Consumes: approved ADR template and implementation evidence.
- Produces: a contiguous decision history and stable IDs used by diagrams, proposals, and arc42 metadata.

- [ ] **Step 1: Add failing ADR contract tests**

Test contiguous numbering, filename/title agreement, required headings, valid statuses, non-empty `None` relationship fields, retrospective marking, and separation of decision from implementation status.

- [ ] **Step 2: Run ADR tests and verify failure**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: ADR structure tests fail because no numbered ADR exists.

- [ ] **Step 3: Write ADR-0001 through ADR-0004**

Use `Status: Accepted`. ADR-0001 is not retrospective and links the approved design plus its commit; implementation status is `Partial` until the framework is complete. ADR-0002 through ADR-0004 are retrospective with implementation status `Complete` and cite exact evidence:

- ADR-0002: `PostingService`, `ReversalService`, proof services, and database privilege migrations.
- ADR-0003: `Money`, `PostingLine`, `JournalValidator`, overflow tests, and the debit/credit example in the funds-core README.
- ADR-0004: JDBC repositories, serializable transaction setup, Flyway migrations, PostgreSQL integration tests, and the separate proof-reader role.

Each ADR must explain at least two rejected alternatives and negative consequences.

- [ ] **Step 4: Write ADR-0005 through ADR-0008**

- ADR-0005 is retrospective/complete and cites exact reversal, immutability, and migration evidence.
- ADR-0006 is retrospective/complete and cites the idempotency row, journal/posting/balance/outbox atomic transaction, concurrency tests, and crash-recovery tests.
- ADR-0007 is retrospective/partial: identifier foundations exist, but issuance/resolution/NIP APIs do not.
- ADR-0008 is retrospective/partial: the 8 GiB target and resource envelopes are documented and some manifests exist, but the complete profile-based evidence suite is not deployed or measured.

- [ ] **Step 5: Update decision indexes and classification**

Link each ADR from `09-decisions.md`, add applicable ADR IDs to arc42 metadata, and resolve inventory rows whose missing destination was an ADR.

- [ ] **Step 6: Validate and commit decisions**

Run:

```bash
python3 architecture/scripts/validate_architecture.py --root . --checks metadata,adrs,links
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: ADR, metadata, link, and unit-test checks pass.

```bash
git add architecture/adr architecture/arc42 architecture/archive/comprehensive-design-migration-inventory.md architecture/scripts/tests/test_validate_architecture.py
git commit -m "docs: record foundational architecture decisions"
```

### Task 6: Add version-controlled Mermaid diagrams

**Files:**
- Create: `architecture/diagrams/context.mmd`
- Create: `architecture/diagrams/containers.mmd`
- Create: `architecture/diagrams/funds-core-components.mmd`
- Create: `architecture/diagrams/posting-sequence.mmd`
- Create: `architecture/diagrams/single-vm-deployment.mmd`
- Create: `architecture/scripts/render-diagrams.sh`
- Create: `architecture/tooling/package.json`
- Create: `architecture/tooling/package-lock.json`
- Modify: `.gitignore`
- Modify: `architecture/arc42/03-context-and-scope.md`
- Modify: `architecture/arc42/05-building-block-view.md`
- Modify: `architecture/arc42/06-runtime-view.md`
- Modify: `architecture/arc42/07-deployment-view.md`
- Modify: `architecture/scripts/tests/test_validate_architecture.py`

**Interfaces:**
- Consumes: arc42 sections and ADR IDs from Tasks 3 and 5.
- Produces: Mermaid sources and `architecture/scripts/render-diagrams.sh [output-directory]`, which renders every `.mmd` file and exits non-zero on the first syntax failure.

- [ ] **Step 1: Add failing diagram metadata tests**

Test the five required metadata comments, allowed state values, existing arc42 path, existing ADR IDs, ISO date, and required five filenames.

- [ ] **Step 2: Run diagram tests and verify failure**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: required-diagram tests fail because the Mermaid sources are absent.

- [ ] **Step 3: Add isolated Mermaid tooling**

Create `architecture/tooling/package.json` with exact content:

```json
{
  "name": "core-banking-architecture-tooling",
  "private": true,
  "version": "1.0.0",
  "engines": {
    "node": ">=20"
  },
  "devDependencies": {
    "@mermaid-js/mermaid-cli": "11.16.0"
  }
}
```

Run `npm install --package-lock-only --prefix architecture/tooling`, then verify the lockfile resolves Mermaid CLI `11.16.0`. Add these ignores:

```gitignore
/architecture/tooling/node_modules/
/architecture/diagrams/generated/
```

- [ ] **Step 4: Write current-state diagrams**

- `context.mmd`: `CURRENT`; show developer/operator, funds-core, PostgreSQL, and test/runtime boundary. Put NIP/providers/Go services outside the current system with a proposed-state note, not as current containers.
- `funds-core-components.mmd`: `CURRENT`; show domain, application, PostgreSQL infrastructure, runtime guard, and their allowed dependency direction.
- `posting-sequence.mmd`: `CURRENT`; show request-hash validation, serializable transaction, idempotency lock, account locks, validation, immutable facts, projections, outbox, completion, and commit/rollback.

- [ ] **Step 5: Write proposed-state diagrams**

- `containers.mmd`: `PROPOSED`; show the planned Java funds-core plus Go application services and infrastructure, visibly separating implemented funds-core from unimplemented containers.
- `single-vm-deployment.mmd`: `PROPOSED`; show the 8 GiB host, profile-dependent component groups, cgroup ceilings, and 2,048 MiB host/page-cache reserve.

- [ ] **Step 6: Implement and run the render script**

The script must use `set -euo pipefail`, resolve repository paths from its own location, require `architecture/tooling/node_modules/.bin/mmdc`, create a caller-provided directory or `mktemp -d`, render each source to SVG, and remove only its own temporary directory on exit.

Run:

```bash
npm ci --prefix architecture/tooling
architecture/scripts/render-diagrams.sh
python3 architecture/scripts/validate_architecture.py --root . --checks diagrams,links
```

Expected: all five diagrams render and metadata/link validation passes.

- [ ] **Step 7: Link diagrams and commit**

Link each source from its owning arc42 section; do not commit generated SVGs.

```bash
git add .gitignore architecture/diagrams architecture/scripts architecture/tooling architecture/arc42
git commit -m "docs: add architecture diagrams as code"
```

### Task 7: Separate proposed capabilities and add plan traceability

**Files:**
- Create: `architecture/proposals/account-identifiers-and-nip-inbound.md`
- Create: `architecture/proposals/conventional-deposit-products-and-accrual.md`
- Create: `architecture/proposals/non-interest-banking-products.md`
- Create: `architecture/proposals/full-poc-platform.md`
- Create: `architecture/proposals/production-platform.md`
- Create: `architecture/proposals/providers-and-reconciliation.md`
- Modify: `architecture/infrastructure/infra-ubuntu24.04-poc.md`
- Modify: `docs/superpowers/plans/2026-08-30-account-identifiers-and-nip-inbound-implementation.md`
- Modify: `docs/superpowers/plans/2026-08-30-conventional-deposit-products-and-accrual-implementation.md`
- Modify: `docs/superpowers/plans/2026-08-30-non-interest-banking-products-implementation.md`
- Modify: `docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md`
- Modify: `architecture/archive/comprehensive-design-migration-inventory.md`
- Modify: `architecture/scripts/tests/test_validate_architecture.py`

**Interfaces:**
- Consumes: unimplemented material from the comprehensive design and existing plans.
- Produces: explicit proposed-state documents with bidirectional links to decisions and delivery plans.

- [ ] **Step 1: Add failing proposal metadata and backlink tests**

Require all six proposal files, allowed statuses, plan paths that exist, ADR IDs that exist, and a `**Proposal:**` backlink in each of the four existing plans.

- [ ] **Step 2: Run tests and verify failure**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: proposal and backlink tests fail.

- [ ] **Step 3: Extract product and identifier proposals**

Use `status: approved` for the three proposals with existing implementation plans. Preserve their requirements, constraints, acceptance boundaries, and exact plan links without presenting them as current. Link ADR-0002, ADR-0003, ADR-0004, ADR-0006, and ADR-0007 where applicable.

- [ ] **Step 4: Extract platform and provider proposals**

Use `status: proposed` for full PoC platform, production platform, and provider/reconciliation proposals. Record that repository manifests or architecture text are design evidence, not deployment evidence. Link the proposed container and single-VM diagrams.

- [ ] **Step 5: Label the infrastructure document and add backlinks**

Add proposal metadata to `architecture/infrastructure/infra-ubuntu24.04-poc.md` with `status: proposed`, owner `platform`, ADR-0008, and the full-PoC proposal. Add `**Proposal:**` and `**Related ADRs:**` immediately below the header of each existing implementation plan.

- [ ] **Step 6: Resolve proposal inventory rows and validate**

Update every proposal-classified inventory row with its real destination and `resolved`. Run:

```bash
python3 architecture/scripts/validate_architecture.py --root . --checks metadata,links,migration
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: metadata, links, migration inventory, and unit tests pass.

- [ ] **Step 7: Commit proposal separation**

```bash
git add architecture/proposals architecture/infrastructure/infra-ubuntu24.04-poc.md architecture/archive/comprehensive-design-migration-inventory.md docs/superpowers/plans architecture/scripts/tests/test_validate_architecture.py
git commit -m "docs: separate proposed architecture from current state"
```

### Task 8: Complete archive cutover and repair documentation links

**Files:**
- Move: `architecture/modern-core-banking-comprehensive-design-revised.md` to `architecture/archive/modern-core-banking-comprehensive-design-revised.md`
- Modify: `architecture/archive/comprehensive-design-migration-inventory.md`
- Modify: `ARCHITECTURE.md`
- Modify: `architecture/README.md`
- Modify: `services/funds-core/README.md`
- Modify: `architecture/infrastructure/infra-ubuntu24.04-poc.md`
- Modify: `architecture/scripts/validate_architecture.py`
- Modify: `architecture/scripts/tests/test_validate_architecture.py`
- Modify: any repository Markdown file still linking to the old comprehensive-design path

**Interfaces:**
- Consumes: a migration inventory with all 27 sections resolved and existing replacement destinations.
- Produces: a non-authoritative historical document under `archive/` and no stale internal links.

- [ ] **Step 1: Add a failing archive-gate test**

Test that the old path must remain present whenever the inventory contains an unresolved row, and must be absent only when all 27 rows resolve and the archived target exists.

- [ ] **Step 2: Verify the gate before moving**

Run:

```bash
python3 architecture/scripts/validate_architecture.py --root . --checks migration,links
```

Expected: pass with zero unresolved rows. Do not move the source document if this command fails.

- [ ] **Step 3: Move the comprehensive design with Git**

Run:

```bash
git mv architecture/modern-core-banking-comprehensive-design-revised.md architecture/archive/modern-core-banking-comprehensive-design-revised.md
```

Add a banner immediately under its title: `Historical source document — non-authoritative; see /ARCHITECTURE.md and the migration inventory.`

- [ ] **Step 4: Repair every old-path link**

Run:

```bash
rg -n 'architecture/modern-core-banking-comprehensive-design-revised.md|modern-core-banking-comprehensive-design-revised.md' --glob '*.md'
```

Update service and infrastructure links to the root entry point or exact arc42/proposal destination. Only the migration inventory may link to the archived historical source.

- [ ] **Step 5: Run complete documentation validation**

Run:

```bash
python3 architecture/scripts/validate_architecture.py --root .
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
architecture/scripts/render-diagrams.sh
git diff --check
```

Expected: validation, unit tests, Mermaid rendering, and whitespace checks pass.

- [ ] **Step 6: Commit the archive cutover**

```bash
git add ARCHITECTURE.md architecture services/funds-core/README.md
git commit -m "docs: complete architecture documentation migration"
```

### Task 9: Enforce architecture documentation in pull requests and CI

**Files:**
- Create: `.github/pull_request_template.md`
- Create: `.github/workflows/architecture-docs.yml`
- Modify: `architecture/README.md`
- Modify: `architecture/scripts/validate_architecture.py`
- Modify: `architecture/scripts/tests/test_validate_architecture.py`

**Interfaces:**
- Consumes: complete local validation and render commands.
- Produces: an architecture-impact declaration for reviewers and an automated GitHub Actions gate.

- [ ] **Step 1: Add failing pull-request-template validation**

Extend `validate_structure` to require these literal prompts:

```markdown
## Architecture impact
- [ ] No architecture impact
- [ ] Architecture changed; linked below

Related ADRs:
Current-state arc42 sections changed:
Proposals implemented, invalidated, or superseded:
Diagrams changed:
Verification evidence:
```

Add a unit test that omits `Verification evidence:` and asserts a focused failure.

- [ ] **Step 2: Run tests and verify failure**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: pull-request-template test fails because `.github/pull_request_template.md` is absent.

- [ ] **Step 3: Create the pull-request template**

Include the exact architecture block above, plus a warning that selecting `No architecture impact` while changing a boundary, invariant, contract, deployment topology, trust boundary, or resource budget is a review defect.

- [ ] **Step 4: Create the GitHub Actions workflow**

Configure `pull_request` and pushes to `master`, limited to architecture, documentation, workflow, service documentation, migration, and runtime-configuration paths. Use `ubuntu-24.04`, `actions/checkout@v4`, `actions/setup-python@v5` with Python `3.12`, and `actions/setup-node@v4` with Node `22`. Grant only `contents: read`.

Run these steps in order:

```yaml
- run: python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
- run: python3 architecture/scripts/validate_architecture.py --root .
- run: npm ci --prefix architecture/tooling
- run: architecture/scripts/render-diagrams.sh
- run: git diff --check
```

- [ ] **Step 5: Link governance to CI and run the local acceptance gate**

Update `architecture/README.md` with the CI workflow path. Leave ADR-0001 implementation status `Partial` in this commit because the evidence commit hash does not exist yet.

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
python3 architecture/scripts/validate_architecture.py --root .
npm ci --prefix architecture/tooling
architecture/scripts/render-diagrams.sh
git diff --check
git status --short
```

Expected: all unit tests pass; repository validation prints `architecture validation passed`; all five Mermaid sources render; `git diff --check` is silent; status lists only Task 9 files before commit.

- [ ] **Step 6: Commit workflow enforcement**

```bash
git add .github architecture/README.md architecture/scripts
git commit -m "ci: enforce architecture documentation contracts"
```

### Task 10: Finalize immutable implementation evidence

**Files:**
- Modify: `architecture/adr/0001-manage-architecture-as-versioned-code.md`

**Interfaces:**
- Consumes: the full commit hash produced by Task 9.
- Produces: ADR-0001 with `Implementation status: Complete` and immutable local implementation evidence.

- [ ] **Step 1: Capture and validate the Task 9 commit hash**

Run:

```bash
framework_commit="$(git rev-parse HEAD)"
test "$(printf '%s' "$framework_commit" | wc -c)" -eq 40
git show --quiet --format='%s' "$framework_commit"
```

Expected: a 40-character hash and subject `ci: enforce architecture documentation contracts`.

- [ ] **Step 2: Append evidence without rewriting rationale**

Change only `Implementation status: Partial` to `Implementation status: Complete` and append the full Task 9 hash plus these verified commands under `## Implementation evidence`:

```text
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
python3 architecture/scripts/validate_architecture.py --root .
npm ci --prefix architecture/tooling
architecture/scripts/render-diagrams.sh
```

- [ ] **Step 3: Re-run the complete gate**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
python3 architecture/scripts/validate_architecture.py --root .
npm ci --prefix architecture/tooling
architecture/scripts/render-diagrams.sh
git diff --check
git diff -- architecture/adr/0001-manage-architecture-as-versioned-code.md
```

Expected: all automated checks pass and the ADR diff changes only implementation status and evidence.

- [ ] **Step 4: Commit evidence finalization**

```bash
git add architecture/adr/0001-manage-architecture-as-versioned-code.md
git commit -m "docs: finalize architecture framework evidence"
```

## Final Verification

From the repository root, run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
python3 architecture/scripts/validate_architecture.py --root .
npm ci --prefix architecture/tooling
architecture/scripts/render-diagrams.sh
git diff --check HEAD~10..HEAD
git status --short --branch
```

Expected results:

- Validator unit tests report zero failures and zero errors.
- Repository validation prints `architecture validation passed`.
- Exactly five Mermaid sources render successfully into a temporary directory.
- The ten implementation commits contain no whitespace errors.
- The working tree is clean.
- `ARCHITECTURE.md` is under 180 lines.
- All twelve arc42 files are `current` or `deprecated`; none is `proposed`.
- ADR identifiers are contiguous from `0001` through `0008`.
- Every proposal and implementation plan has working backlinks.
- The migration inventory contains sections `1` through `27`, zero unresolved rows, and existing destinations.
- The old comprehensive-design path is absent and its archived copy is explicitly non-authoritative.
- No generated SVG or `node_modules` content is tracked.
