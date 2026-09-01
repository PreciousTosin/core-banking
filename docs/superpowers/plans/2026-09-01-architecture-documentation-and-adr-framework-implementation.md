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
- Before Task 1 changes any file, capture the immutable implementation baseline with `git rev-parse HEAD > /tmp/core-banking-architecture-base`; every cross-task whitespace review uses `$(cat /tmp/core-banking-architecture-base)..HEAD`, never `HEAD~N`.
- Markdown links resolve relative to the containing Markdown file after stripping query strings and fragments; the validator supports angle-bracket destinations and backslash-escaped spaces.

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

- Create `architecture/archive/comprehensive-design-migration-inventory.md`: granular rows for every material top-level section and subsection, with stable unique source keys, disposition, destination, evidence, coverage notes, and resolution while retaining full top-level coverage `1` through `27`.
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
- Produces: generic front-matter, Markdown-link, CLI-dispatch, and deterministic-error primitives that later tasks extend with repository contracts.
- Produces: `validate_repository(root: Path, checks: frozenset[str]) -> list[str]`, where an empty list means success and each non-empty string is one actionable validation error.
- CLI: `python3 architecture/scripts/validate_architecture.py --root . --checks links`; omit `--checks` to run all checks registered at that point in the plan.

- [ ] **Step 1: Capture the implementation baseline**

Run before changing a file:

```bash
git rev-parse HEAD > /tmp/core-banking-architecture-base
test "$(tr -d '\n' < /tmp/core-banking-architecture-base | wc -c)" -eq 40
```

Expected: the file contains exactly one full 40-hex commit hash. Keep it through Final Verification.

- [ ] **Step 2: Write failing validator unit tests**

Create temporary repositories in `unittest.TestCase` methods and cover only the generic primitives owned by this task:

```python
def test_relative_link_rejects_a_missing_target(self):
    self.write("ARCHITECTURE.md", "[missing](architecture/missing.md)\n")
    errors = validator.validate_links(self.root)
    self.assertTrue(any("architecture/missing.md does not exist" in error for error in errors))

def test_nested_links_resolve_from_containing_file(self):
    self.write("architecture/arc42/target file.md", "# Target\n")
    self.write("architecture/guides/nested.md", "[target](<../arc42/target file.md?view=full#section>)\n")
    self.assertEqual([], validator.validate_links(self.root))

def test_escaped_space_link_resolves_from_containing_file(self):
    self.write("architecture/arc42/target file.md", "# Target\n")
    self.write("architecture/guides/nested.md", "[target](../arc42/target\\ file.md#section)\n")
    self.assertEqual([], validator.validate_links(self.root))

def test_front_matter_parses_supported_subset(self):
    path = self.write("architecture/example.md", """---
title: Example
owners:
  - architecture
related_adrs: []
---
# Example
""")
    self.assertEqual(
        {"title": "Example", "owners": ["architecture"], "related_adrs": []},
        validator.parse_front_matter(path),
    )
```

- [ ] **Step 3: Run the unit tests to verify they fail**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: failure because `validate_architecture` and its validation functions do not exist.

- [ ] **Step 4: Implement the standard-library validator**

Use these constants and public functions exactly; later tasks add check names and validators only when they add the corresponding failing contract test:

```python
CHECKS = frozenset({"links"})

Validator = Callable[[Path], list[str]]
VALIDATORS: dict[str, Validator] = {
    "links": validate_links,
}

def validate_repository(root: Path, checks: frozenset[str] = CHECKS) -> list[str]:
    errors: list[str] = []
    for check in sorted(checks):
        errors.extend(VALIDATORS[check](root))
    return sorted(errors)
```

Define `parse_front_matter(path: Path) -> dict[str, str | list[str]]`, `validate_links(root: Path) -> list[str]`, and `main(argv: Sequence[str] | None = None) -> int` with those exact names and types before constructing `VALIDATORS`.

Implement the bodies with these exact rules:

- Parse only the repository's YAML subset: scalar `key: value`, `key: []`, and indented `- item` lists between the first two `---` lines.
- `links` scans repository Markdown files, resolves each relative destination against the containing file's parent, removes query strings and fragments before checking the filesystem, unwraps destinations enclosed in angle brackets, converts Markdown backslash-escaped spaces to literal spaces, and skips `http`, `https`, `mailto`, and pure `#fragment` targets.
- Sort errors by path and message so local and CI output is deterministic.
- Print each error to stderr and return `1`; print `architecture validation passed` and return `0` when clean.

- [ ] **Step 5: Run validator unit tests**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: all generic primitive tests pass without third-party packages. No arc42, ADR, proposal, diagram, migration, archive, PR-body, workflow, or staleness contract is implemented in this task.

- [ ] **Step 6: Commit the validator**

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
- Modify: `architecture/scripts/validate_architecture.py`
- Modify: `architecture/scripts/tests/test_validate_architecture.py`

**Interfaces:**
- Consumes: authority, metadata, lifecycle, and review rules from the approved specification.
- Produces: stable human entry points and templates consumed by every later documentation task.

- [ ] **Step 1: Add a failing governance-structure test**

Add `test_required_governance_files` to the validator tests. It creates a temporary empty root, calls the new `validate_structure`, and asserts the error list names all six files above.

- [ ] **Step 2: Run the focused test and verify failure**

Run:

```bash
python3 -m unittest architecture.scripts.tests.test_validate_architecture.ValidatorTest.test_required_governance_files -v
```

Expected: fail with `AttributeError: module 'validate_architecture' has no attribute 'validate_structure'` before production code changes.

- [ ] **Step 3: Implement the governance-structure rule**

Add `structure` to `CHECKS` and `VALIDATORS`; implement the exact new rule that `validate_structure` requires the six governance files listed in this task and reports every missing path.

- [ ] **Step 4: Write the root entry point**

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

- [ ] **Step 5: Write governance and templates**

Copy the approved rules into focused documents:

- `architecture/README.md`: authority order, ownership, update triggers, pull-request traceability, review cadence, and archive rule.
- `architecture/adr/README.md`: statuses, implementation statuses, ADR threshold, immutable accepted rationale, supersession, full-hash evidence, and retrospective ADR label.
- `architecture/adr/template.md`: include `Retrospective: No` and `Related proposals: None` in addition to every field and heading specified by the design.
- `architecture/proposals/README.md`: statuses and the rule that `approved` does not mean `current`.
- `architecture/diagrams/README.md`: exact seven-line metadata contract, title/state rule, abstraction-level guidance, intended-question guidance, and render command.

Use this diagram header example:

```text
---
title: CURRENT — Funds-core system context
---
%% state: CURRENT
%% abstraction: system-context
%% question: Which implemented actors and systems exchange information with funds-core?
%% owner: funds-core
%% arc42: architecture/arc42/05-building-block-view.md
%% adrs: ADR-0002, ADR-0004
%% last_verified: 2026-09-01
```

Require the Mermaid `title` directive to contain the same `CURRENT` or `PROPOSED` state as the metadata. Define `abstraction` as a short stable level such as `system-context`, `container`, `component`, `runtime-sequence`, or `deployment`; define `question` as the single review question the diagram is intended to answer.

- [ ] **Step 6: Validate focused files and links**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
python3 architecture/scripts/validate_architecture.py --root . --checks links
```

Expected: unit tests pass; link validation passes because not-yet-created arc42 paths are plain code until Task 3.

- [ ] **Step 7: Commit governance**

```bash
git add ARCHITECTURE.md architecture/README.md architecture/adr/README.md architecture/adr/template.md architecture/proposals/README.md architecture/diagrams/README.md architecture/scripts/validate_architecture.py architecture/scripts/tests/test_validate_architecture.py
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
- Modify: `architecture/scripts/validate_architecture.py`
- Modify: `architecture/scripts/tests/test_validate_architecture.py`

**Interfaces:**
- Consumes: implemented evidence in `services/funds-core/`, database migrations `V001` through `V006`, funds-core tests, infrastructure manifests, and the approved authority rules.
- Produces: the canonical current-state architecture baseline linked from the root entry point.

- [ ] **Step 1: Add failing current-state metadata tests**

Add tests that call new `validate_metadata` and require exactly the twelve filenames, reject `status: proposed` under `arc42/`, reject absent or empty owners, reject a `code_refs` path that does not exist, and accept only ISO `YYYY-MM-DD` `last_verified` values. Before production changes these tests must fail because `validate_metadata` is absent.

- [ ] **Step 2: Run focused tests and verify failure**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: fail with a missing `validate_metadata` behavior or missing twelve-file diagnostics; record the focused failure before implementation.

- [ ] **Step 3: Implement the arc42 metadata contract**

Add `metadata` to `CHECKS` and `VALIDATORS` and implement exactly the arc42 filename, required-field, status, date, and code-reference rules from Step 1; proposal metadata remains owned by Task 7.

- [ ] **Step 4: Write arc42 sections 1 through 4**

Use `status: current`, owner `architecture`, and `last_verified: 2026-09-01`. Record only these verified facts:

- The repository implements a funds-core accounting kernel, not a complete bank.
- The current module uses Java 25, Quarkus, Flyway migrations, and PostgreSQL 18.6 integration evidence.
- The PoC target is constrained to an 8 GiB single VM, but the full topology is proposed and not deployed by current repository evidence.
- The kernel owns exact money, journal validation, posting, reversals, balances, chart governance, proofs, database roles, and outbox persistence.
- External actors are currently developers/operators, PostgreSQL, and test infrastructure; customer channels, providers, NIBSS/NIP, Go services, brokers, and workflow engines are outside the implemented context.

Link code roots and existing service documentation as evidence.

- [ ] **Step 5: Write arc42 sections 5 through 8**

Document these current blocks and flows:

- Domain records and invariants under `services/funds-core/src/main/java/com/corebanking/funds/domain/`.
- Application services for posting, reversal, hashing, validation, transaction deadlines, and accounting proofs.
- PostgreSQL repositories and migrations as the authoritative persistence boundary.
- Runtime startup guard and bounded runtime configuration.
- Posting flow: verify typed request hash, begin serializable transaction, acquire idempotency ownership, validate/lock book-period-account state, assign sequences, validate journal, persist journal/postings, update balances/control projection, write outbox, complete idempotency, commit.
- Reversal flow: load original facts, construct exact negated linked journal in an open period, use the trusted reversal path, and preserve additive history.
- Proof flow: independently aggregate immutable source postings and compare materialised balance/control projections.
- Current deployment evidence: a JVM Dockerfile with bounded memory and smoke script; Kubernetes/Helm files exist but do not prove a deployed full platform.

- [ ] **Step 6: Write arc42 sections 9 through 12**

For decisions, initially link only the ADR template and state that Task 5 creates the decision index. For quality and risk, capture the implemented acceptance boundary from `services/funds-core/README.md`, including explicit exclusions. Define glossary entries for debit, credit, signed posting, natural balance, journal, posting, ledger account, external account identifier, NUBAN, idempotency, reversal, outbox, book, chart version, accounting period, control account, trial balance, current state, and proposed state.

- [ ] **Step 7: Complete root navigation and validate**

Add links from `ARCHITECTURE.md` to all twelve arc42 files. Run:

```bash
python3 architecture/scripts/validate_architecture.py --root . --checks metadata,links
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: metadata, links, and unit tests pass. Full structure validation begins after Task 4 creates the required migration inventory.

- [ ] **Step 8: Commit current-state baseline**

```bash
git add ARCHITECTURE.md architecture/arc42 architecture/scripts/validate_architecture.py architecture/scripts/tests/test_validate_architecture.py
git commit -m "docs: add current-state arc42 baseline"
```

### Task 4: Classify every section of the comprehensive design

**Files:**
- Create: `architecture/archive/comprehensive-design-migration-inventory.md`
- Modify: `architecture/scripts/validate_architecture.py`
- Modify: `architecture/scripts/tests/test_validate_architecture.py`

**Interfaces:**
- Consumes: all 27 top-level sections of `architecture/modern-core-banking-comprehensive-design-revised.md`.
- Produces: a complete classification gate that Task 8 must satisfy before archive cutover.

- [ ] **Step 1: Add failing granular migration-inventory tests**

Add `migration` to the planned `CHECKS`/`VALIDATORS` contract, but write tests before changing production code. Test all of these behaviors:

- A complete fixture contains at least one unique source key rooted at each top-level integer `01` through `27`, includes multiple rows for mixed section `08`, and has exactly one `unresolved` row; assert one `unresolved migration row` error by presence, not total error cardinality.
- Missing top-level root `17`, a duplicate full source key, a malformed source key, an unsupported disposition, and an unsupported resolution each produce focused errors.
- A non-`historical-only` row whose destination does not exist fails; a `current` row whose evidence path does not exist fails.
- A mixed-content heading represented by multiple rows fails if any row has empty coverage notes or if its source keys do not use distinct `::01`, `::02` segment suffixes.

Build the valid fixture with all 27 roots, real temporary destination files, and real temporary evidence files; do not use a one-row fixture as a supposedly complete inventory.

Use this fixture construction pattern so the single expected unresolved diagnostic is not contaminated by missing paths or missing top-level coverage:

```python
self.write("architecture/README.md", "# Architecture\n")
self.write("services/funds-core/README.md", "# Funds core\n")
self.write(
    "architecture/modern-core-banking-comprehensive-design-revised.md",
    "\n".join(f"## {section}. Section {section}" for section in range(1, 28)),
)
rows = []
for section in range(1, 28):
    if section == 8:
        rows.extend([
            "| 08::01 | 8. Current segment | current | architecture/README.md | services/funds-core/README.md | Implemented paragraph | resolved |",
            "| 08::02 | 8. Proposed segment | proposal | architecture/README.md | None | Unimplemented paragraph | resolved |",
        ])
        continue
    resolution = "unresolved" if section == 27 else "resolved"
    rows.append(
        f"| {section:02d} | {section}. Section {section} | service-detail | architecture/README.md | None | Entire section | {resolution} |"
    )
self.write(
    "architecture/archive/comprehensive-design-migration-inventory.md",
    "| Source key | Source heading | Disposition | Destination | Evidence | Coverage notes | Resolution |\n"
    "|---|---|---|---|---|---|---|\n" + "\n".join(rows) + "\n",
)
```

- [ ] **Step 2: Run the focused tests and verify failure**

Run:

```bash
python3 -m unittest architecture.scripts.tests.test_validate_architecture.ValidatorTest.test_migration_inventory_requires_sections_one_through_twenty_seven -v
```

Expected: fail because `validate_migration_inventory` and the `migration` registry entry do not exist.

- [ ] **Step 3: Create the migration inventory**

Use this exact table schema:

```markdown
| Source key | Source heading | Disposition | Destination | Evidence | Coverage notes | Resolution |
|---|---|---|---|---|---|---|
```

Inventory every material `##`, `###`, and `####` heading within numbered sections 1 through 27, not merely the 27 top-level headings. Derive a stable source key from the printed heading number: zero-pad each numeric component (`8.1.1` becomes `08.01.01`); named worked examples use a lowercase slug (`13.08.example-a`). If one heading mixes dispositions, split it into `08.01::01`, `08.01::02`, and later segment keys, with one row per content segment. Full source keys must be unique and top-level roots `01` through `27` must all be represented.

Allowed `Disposition` values are exactly `current`, `proposal`, `decision`, `service-detail`, `plan-detail`, and `historical-only`. Allowed `Resolution` values are exactly `unresolved` and `resolved`. Semicolon-separated `Destination` entries are repository-relative paths; every listed destination must exist at validation time. Use `None` only for a `historical-only` destination. Semicolon-separated `Evidence` entries must exist for every `current` row and may be `None` for other dispositions. `Coverage notes` must name the paragraph range, table, example, or list covered and, for mixed content, explain why the segments have different dispositions. No row may resolve merely by pointing to the comprehensive source document.

At initial inventory commit, use existing arc42, plan, service-document, source, test, and migration paths. Rows requiring not-yet-created ADRs or proposals remain `unresolved` and point to the already-existing `architecture/adr/README.md` or `architecture/proposals/README.md` governance destination until Tasks 5 and 7 replace that destination with the exact created artifact. This preserves path validity without falsely claiming extraction is complete.

- [ ] **Step 4: Implement the migration contract**

Add `migration` to `CHECKS` and `VALIDATORS` and implement `validate_migration_inventory(root: Path) -> list[str]` with the exact schema, source-key grammar, allowed values, top-level coverage, uniqueness, mixed-segment coverage-note, path-existence, current-evidence, and unresolved-row rules above. Parse numbered `##`/`###`/`####` headings from the comprehensive source, plus unnumbered `#### Example A` through `#### Example J` under section 13.8, and require each derived heading key to have either one exact inventory key or one or more segment keys with that key plus `::NN`; reject inventory keys that do not map back to a source heading. Table parsing must report malformed rows rather than silently skipping them.

- [ ] **Step 5: Validate the inventory's deliberate interim state**

Run:

```bash
python3 architecture/scripts/validate_architecture.py --root . --checks migration
```

Expected: fail only with one or more `unresolved migration row` diagnostics for ADR/proposal extraction scheduled in Tasks 5 and 7. All schema, coverage, uniqueness, destination, and evidence checks pass.

- [ ] **Step 6: Commit the classification inventory**

```bash
git add architecture/archive/comprehensive-design-migration-inventory.md architecture/scripts/validate_architecture.py architecture/scripts/tests/test_validate_architecture.py
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
- Modify: all twelve files under `architecture/arc42/` (ADR index and `related_adrs` metadata)
- Modify: `architecture/archive/comprehensive-design-migration-inventory.md`
- Modify: `architecture/scripts/validate_architecture.py`
- Modify: `architecture/scripts/tests/test_validate_architecture.py`

**Interfaces:**
- Consumes: approved ADR template and implementation evidence.
- Produces: a contiguous decision history and stable IDs used by diagrams, proposals, and arc42 metadata.

- [ ] **Step 1: Add failing ADR contract tests**

Write tests against a new `validate_adrs` behavior before implementing it. Test contiguous numbering, filename/title agreement, required headings, valid statuses, non-empty `None` relationship fields, retrospective marking, separation of decision from implementation status, evidence syntax, and substantive content. The substantive headings are exactly `## Context`, `## Decision drivers`, `## Considered options`, `## Decision`, `## Consequences`, `### Positive`, `### Negative`, `### Risks`, `## Compliance and verification`, and `## Implementation evidence`; each must contain non-whitespace prose, a list item, or a link before the next heading of the same or higher level. Add one negative test per empty substantive heading. Test that the body of `## Implementation evidence` may be exactly `None` only with `Not started` or `Not applicable`, while `Partial` and `Complete` require at least one existing repository path plus a full 40-lowercase-hex commit hash or stable `https://github.com/<owner>/<repo>/pull/<number>` URL in the evidence section or matching relationship fields.

- [ ] **Step 2: Run ADR tests and verify failure**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: fail because `validate_adrs` and the `adrs` registry entry do not exist.

- [ ] **Step 3: Implement the ADR contract**

Add `adrs` to `CHECKS` and `VALIDATORS`. Implement the exact numbering, filename/title, field, lifecycle-value, substantive-section, relationship-`None`, retrospective, implementation-evidence, 40-hex, stable-PR-URL, and evidence-path rules from Step 1. `Related pull requests`, `Related commits`, and `Implementation evidence` are append-only relationship/evidence areas; do not attempt to infer rationale immutability from a single checkout.

- [ ] **Step 4: Write ADR-0001 through ADR-0004**

Use `Status: Accepted`. ADR-0001 is not retrospective and links the approved design plus its commit; implementation status is `Partial` until the framework is complete. ADR-0002 through ADR-0004 are retrospective with implementation status `Complete` and cite exact evidence:

- ADR-0002: `PostingService`, `ReversalService`, proof services, and database privilege migrations.
- ADR-0003: `Money`, `PostingLine`, `JournalValidator`, overflow tests, and the debit/credit example in the funds-core README.
- ADR-0004: JDBC repositories, serializable transaction setup, Flyway migrations, PostgreSQL integration tests, and the separate proof-reader role.

Each ADR must explain at least two rejected alternatives and negative consequences. For retrospective Complete ADRs, identify immutable evidence at execution time with `git log --format='%H %s' -- <evidence-paths>` and verify each selected value using `printf '%s' "$hash" | grep -Eq '^[0-9a-f]{40}$'` plus `git cat-file -e "$hash^{commit}"`. Verify these current-history candidates: `58fde48ba5ef053304b85ffe31cb17c1de021c5e` and `a8d7653f4296d13baa4e2fe56d7abae46161ff32` for ADR-0002, `38f822136da516ebf343c82c469a6cbccf148413` and `17a8a1d3d33b5d607b76bfa99d0a3c90f47c872c` for ADR-0003, and `c309afc5afcd0854d4ec690e80dcb9ba9ff28186` plus `58fde48ba5ef053304b85ffe31cb17c1de021c5e` for ADR-0004. Cite at least one verified full hash and one existing code/test/migration path in every Complete record.

- [ ] **Step 5: Write ADR-0005 through ADR-0008**

- ADR-0005 is retrospective/complete and cites exact reversal, immutability, and migration evidence.
- ADR-0006 is retrospective/complete and cites the idempotency row, journal/posting/balance/outbox atomic transaction, concurrency tests, and crash-recovery tests.
- ADR-0007 is retrospective/partial: identifier foundations exist, but issuance/resolution/NIP APIs do not.
- ADR-0008 is retrospective/partial: the 8 GiB target and resource envelopes are documented and some manifests exist, but the complete profile-based evidence suite is not deployed or measured.

For ADR-0005 verify and cite `feb5bbd951c5061ef05050c35604aa863cbdea02`; for ADR-0006 verify and cite both `df6b2fb6a67f1406ccf2e8b0fa813626900c7d25` and `227bd288b593015f9009b0c408b1daf29855e997`. These hashes are immutable evidence, not replacements for the exact repository evidence paths. ADR-0007 and ADR-0008 are `Partial`, so each must also contain at least one verified full hash or stable PR URL and an existing evidence path; neither may use `None` for implementation evidence.

- [ ] **Step 6: Update decision indexes and classification**

Link each ADR from `09-decisions.md`; update `related_adrs` in every arc42 file to the exact IDs that govern its documented claims, using `[]` when none governs the section; and replace each decision inventory row's temporary governance destination with the exact ADR path before marking that row `resolved`.

- [ ] **Step 7: Validate and commit decisions**

Run:

```bash
python3 architecture/scripts/validate_architecture.py --root . --checks metadata,adrs,links
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: ADR, metadata, link, and unit-test checks pass.

```bash
git add architecture/adr architecture/arc42 architecture/archive/comprehensive-design-migration-inventory.md architecture/scripts/validate_architecture.py architecture/scripts/tests/test_validate_architecture.py
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
- Modify: `architecture/scripts/validate_architecture.py`
- Modify: `architecture/scripts/tests/test_validate_architecture.py`

**Interfaces:**
- Consumes: arc42 sections and ADR IDs from Tasks 3 and 5.
- Produces: Mermaid sources and `architecture/scripts/render-diagrams.sh [output-directory]`, which renders every `.mmd` file and exits non-zero on the first syntax failure.

- [ ] **Step 1: Add failing diagram metadata tests**

Write tests against a new `validate_diagrams` behavior before implementing it. Test the seven required metadata comments, allowed state values, non-empty `abstraction`, non-empty `question`, existing arc42 path, existing ADR IDs, ISO date, matching state in the Mermaid title, and required five filenames. Include negative tests for missing abstraction, missing question, missing title state, and a `CURRENT` metadata/`PROPOSED` title mismatch.

- [ ] **Step 2: Run diagram tests and verify failure**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: fail because `validate_diagrams` and the `diagrams` registry entry do not exist.

- [ ] **Step 3: Implement the diagram contract**

Add `diagrams` to `CHECKS` and `VALIDATORS`. Implement the exact filename, first-ten-lines metadata/front-matter, state, abstraction, question, owner, arc42-path, ADR-ID, ISO-date, and title-state agreement rules from Step 1. The required comment keys are `state`, `abstraction`, `question`, `owner`, `arc42`, `adrs`, and `last_verified`; the Mermaid YAML front-matter title must include the same literal `CURRENT` or `PROPOSED` state.

- [ ] **Step 4: Add isolated Mermaid tooling**

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

Run `npm install --package-lock-only --prefix architecture/tooling`, then mechanically verify both manifests pin and resolve Mermaid CLI exactly:

```bash
node -e 'const p=require("./architecture/tooling/package.json"); if(p.devDependencies["@mermaid-js/mermaid-cli"]!=="11.16.0") process.exit(1)'
node -e 'const l=require("./architecture/tooling/package-lock.json"); if(l.packages["node_modules/@mermaid-js/mermaid-cli"].version!=="11.16.0") process.exit(1)'
```

Add these ignores:

```gitignore
/architecture/tooling/node_modules/
/architecture/diagrams/generated/
```

- [ ] **Step 5: Write current-state diagrams**

- `context.mmd`: `CURRENT`; show developer/operator, funds-core, PostgreSQL, and test/runtime boundary. Put NIP/providers/Go services outside the current system with a proposed-state note, not as current containers.
- `funds-core-components.mmd`: `CURRENT`; show domain, application, PostgreSQL infrastructure, runtime guard, and their allowed dependency direction.
- `posting-sequence.mmd`: `CURRENT`; show request-hash validation, serializable transaction, idempotency lock, account locks, validation, immutable facts, projections, outbox, completion, and commit/rollback.

- [ ] **Step 6: Write proposed-state diagrams**

- `containers.mmd`: `PROPOSED`; show the planned Java funds-core plus Go application services and infrastructure, visibly separating implemented funds-core from unimplemented containers.
- `single-vm-deployment.mmd`: `PROPOSED`; show the 8 GiB host, profile-dependent component groups, cgroup ceilings, and 2,048 MiB host/page-cache reserve.

- [ ] **Step 7: Implement and run the render script**

The script must use `set -euo pipefail`, resolve repository paths from its own location, require `architecture/tooling/node_modules/.bin/mmdc`, create a caller-provided directory or `mktemp -d`, render each source to SVG, and remove only its own temporary directory on exit.

Run:

```bash
npm ci --prefix architecture/tooling
architecture/scripts/render-diagrams.sh
python3 architecture/scripts/validate_architecture.py --root . --checks diagrams,links
```

Expected: all five diagrams render and metadata/link validation passes.

- [ ] **Step 8: Link diagrams and commit**

Link each source from its owning arc42 section; do not commit generated SVGs. Verify ignored/generated dependencies are not tracked:

```bash
test -z "$(git ls-files 'architecture/tooling/node_modules/**' 'architecture/diagrams/generated/**' '*.svg')"
```

```bash
git add .gitignore architecture/diagrams architecture/scripts/render-diagrams.sh architecture/scripts/validate_architecture.py architecture/scripts/tests/test_validate_architecture.py architecture/tooling architecture/arc42
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
- Modify: `architecture/adr/0001-manage-architecture-as-versioned-code.md`
- Modify: `architecture/adr/0002-centralize-financial-invariants-in-funds-core.md`
- Modify: `architecture/adr/0003-use-signed-integer-minor-units.md`
- Modify: `architecture/adr/0004-use-postgresql-as-the-authoritative-ledger.md`
- Modify: `architecture/adr/0005-use-immutable-journals-and-additive-corrections.md`
- Modify: `architecture/adr/0006-couple-idempotency-and-outbox-to-ledger-commit.md`
- Modify: `architecture/adr/0007-separate-ledger-identity-from-account-addresses.md`
- Modify: `architecture/adr/0008-target-an-eight-gib-single-vm-evidence-suite.md`
- Modify: `architecture/archive/comprehensive-design-migration-inventory.md`
- Modify: `architecture/scripts/validate_architecture.py`
- Modify: `architecture/scripts/tests/test_validate_architecture.py`

**Interfaces:**
- Consumes: unimplemented material from the comprehensive design and existing plans.
- Produces: explicit proposed-state documents with bidirectional links to decisions and delivery plans.

- [ ] **Step 1: Add failing bidirectional proposal-traceability tests**

Write tests against new proposal metadata and traceability behavior before implementation. Require all six proposal files; allowed statuses; existing `related_plans` paths; existing `related_adrs` IDs; a `**Proposal:**` backlink in the account-identifier, conventional-deposit, and non-interest plans; reciprocal `Related proposals:` links in every ADR named by a proposal; and reciprocal proposal links in every plan named by a proposal. For `2026-08-30-accounting-kernel-implementation.md`, require `**Current architecture:**` links to arc42 sections 05, 06, and 08 plus `**Retrospective ADRs:**` links to ADR-0002 through ADR-0006, and explicitly reject a `**Proposal:**` backlink.

- [ ] **Step 2: Run tests and verify failure**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: fail because proposal metadata and bidirectional traceability rules are not implemented.

- [ ] **Step 3: Implement proposal metadata and bidirectional traceability**

Extend `validate_metadata` with the exact proposal required fields and `PROPOSAL_STATUSES = frozenset({"draft", "proposed", "approved", "implementing", "implemented", "rejected", "superseded"})`. Add a `validate_traceability(root: Path) -> list[str]` check and register `traceability`. For every proposal, verify that each `related_plans` path exists and contains a link back to that proposal and that each `related_adrs` ID resolves to an ADR containing a `Related proposals:` link back to that proposal. Enforce the special accounting-kernel rule from Step 1 and verify the three unimplemented plan mappings exactly.

- [ ] **Step 4: Extract product and identifier proposals**

Use `status: approved` for the three proposals with existing implementation plans. Preserve their requirements, constraints, acceptance boundaries, and exact plan links without presenting them as current. Use these exact ADR mappings: account identifiers/NIP links ADR-0002, ADR-0004, ADR-0006, and ADR-0007; conventional deposits links ADR-0002, ADR-0003, ADR-0004, ADR-0005, and ADR-0006; non-interest banking links ADR-0002, ADR-0003, ADR-0004, ADR-0005, and ADR-0006.

- [ ] **Step 5: Extract platform and provider proposals**

Use `status: proposed` for full PoC platform, production platform, and provider/reconciliation proposals. Record that repository manifests or architecture text are design evidence, not deployment evidence. Use these exact ADR mappings: full PoC platform links ADR-0001, ADR-0002, ADR-0004, ADR-0006, and ADR-0008; production platform links ADR-0001, ADR-0004, and ADR-0008; providers/reconciliation links ADR-0002, ADR-0004, ADR-0006, ADR-0007, and ADR-0008. Link the proposed container and single-VM diagrams.

- [ ] **Step 6: Label the infrastructure document and add backlinks**

Add proposal metadata to `architecture/infrastructure/infra-ubuntu24.04-poc.md` with `status: proposed`, owner `platform`, ADR-0008, and the full-PoC proposal. Add `**Proposal:**` and `**Related ADRs:**` immediately below the headers of only these unimplemented plans, using these exact proposal mappings:

- account identifiers/NIP -> `architecture/proposals/account-identifiers-and-nip-inbound.md`
- conventional deposits -> `architecture/proposals/conventional-deposit-products-and-accrual.md`
- non-interest banking -> `architecture/proposals/non-interest-banking-products.md`

Do not add a proposal backlink to the already-implemented accounting-kernel plan. Instead add `**Current architecture:**` links to `architecture/arc42/05-building-block-view.md`, `06-runtime-view.md`, and `08-crosscutting-concepts.md`, plus `**Retrospective ADRs:**` links to ADR-0002 through ADR-0006. In each ADR referenced by any of the six proposals, append the exact proposal path under `Related proposals:`; do not alter Context, drivers, options, Decision, Consequences, or prior implementation evidence in accepted ADRs.

- [ ] **Step 7: Resolve proposal inventory rows and validate**

Update every proposal-classified inventory row with its real destination and `resolved`. Run:

```bash
python3 architecture/scripts/validate_architecture.py --root . --checks metadata,links,migration,traceability
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: metadata, links, migration inventory, bidirectional traceability, and unit tests pass; every granular inventory row is now `resolved` and every final destination exists.

- [ ] **Step 8: Commit proposal separation**

```bash
git add architecture/proposals architecture/infrastructure/infra-ubuntu24.04-poc.md architecture/adr architecture/archive/comprehensive-design-migration-inventory.md docs/superpowers/plans/2026-08-30-account-identifiers-and-nip-inbound-implementation.md docs/superpowers/plans/2026-08-30-conventional-deposit-products-and-accrual-implementation.md docs/superpowers/plans/2026-08-30-non-interest-banking-products-implementation.md docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md architecture/scripts/validate_architecture.py architecture/scripts/tests/test_validate_architecture.py
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
- Modify: `docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md`
- Modify: `architecture/scripts/validate_architecture.py`
- Modify: `architecture/scripts/tests/test_validate_architecture.py`

**Interfaces:**
- Consumes: a migration inventory with all 27 sections resolved and existing replacement destinations.
- Produces: a non-authoritative historical document under `archive/` and no stale internal links.

- [ ] **Step 1: Add failing archive-state-machine tests**

Write tests against a new `validate_archive_state` behavior before implementing it. Cover every state explicitly:

- valid unresolved: at least one granular row is `unresolved`, old source present, archive absent;
- valid resolved pre-cutover: all rows `resolved`, old source present, archive absent;
- valid resolved post-cutover: all rows `resolved`, old source absent, archive present;
- invalid source loss: unresolved rows with old source absent, or resolved rows with both old source and archive absent;
- invalid premature archive: unresolved rows with archive present;
- invalid duplicate copy: old source and archive both present, regardless of resolution.

The state check is separate from the inventory's unresolved-row diagnostic: unresolved/pre-cutover is a safe archive state even though the complete migration check still blocks cutover.

- [ ] **Step 2: Implement the archive state machine and verify the gate before moving**

Implement `validate_archive_state(root: Path) -> list[str]`, add `archive` to `CHECKS` and `VALIDATORS`, and encode exactly the three valid and three invalid categories in Step 1. Do not infer cutover from Git history; use inventory resolution plus presence of the two exact paths.

Run:

```bash
python3 architecture/scripts/validate_architecture.py --root . --checks migration,archive,links
```

Expected: pass with zero unresolved rows in the valid resolved pre-cutover state. Do not move the source document if granular coverage, destinations, evidence, links, or archive state fails.

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

Update `services/funds-core/README.md`, `architecture/infrastructure/infra-ubuntu24.04-poc.md`, and the accounting-kernel plan to the root entry point or exact arc42/proposal destination. The approved design's repository-tree example and this implementation plan may retain the historical filename as non-link prose. Only the migration inventory may contain a Markdown link to the archived historical source.

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
git add ARCHITECTURE.md architecture/archive/comprehensive-design-migration-inventory.md architecture/archive/modern-core-banking-comprehensive-design-revised.md architecture/README.md architecture/infrastructure/infra-ubuntu24.04-poc.md architecture/scripts/validate_architecture.py architecture/scripts/tests/test_validate_architecture.py services/funds-core/README.md docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md
git commit -m "docs: complete architecture documentation migration"
```

### Task 9: Report stale architecture verification without blocking

**Files:**
- Modify: `architecture/README.md`
- Modify: `architecture/scripts/validate_architecture.py`
- Modify: `architecture/scripts/tests/test_validate_architecture.py`

**Interfaces:**
- Consumes: arc42 and Mermaid `last_verified` ISO dates plus an explicit reporting date.
- Produces: `report_stale(root: Path, as_of: date, threshold_days: int = 90) -> list[StaleWarning]` and CLI flags `--report-stale --as-of YYYY-MM-DD`; warnings alone always exit `0`.

- [ ] **Step 1: Add failing deterministic staleness tests**

Before production changes, test an explicit `as_of=date(2026, 9, 1)` with a 90-calendar-day threshold: age 90 is not stale, age 91 is stale, future dates produce a validation error rather than a stale warning, malformed dates remain blocking metadata/diagram errors, warnings are sorted by repository-relative path, and a warning-only CLI invocation returns `0`. Test local output as `WARNING: <path>: last_verified <date> is 91 days old (threshold: 90)` and GitHub Actions output as `::warning file=<path>::last_verified <date> is 91 days old (threshold: 90)` when `GITHUB_ACTIONS=true`.

- [ ] **Step 2: Run the tests and verify failure**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: fail because `StaleWarning`, `report_stale`, and the reporting CLI flags do not exist.

- [ ] **Step 3: Implement non-blocking stale reporting**

Add an immutable `StaleWarning` dataclass containing `path: Path`, `last_verified: date`, `age_days: int`, and `threshold_days: int`. Inspect arc42 front matter and Mermaid metadata. A document is stale only when `(as_of - last_verified).days > 90`. Require `--as-of` with `--report-stale` for reproducible local and CI runs; do not use wall-clock time inside `report_stale`. Print the exact local or GitHub annotation form from Step 1. Return `0` when warnings are the only findings; return non-zero only for malformed/future dates or ordinary blocking validation errors.

- [ ] **Step 4: Document and verify reporting**

Document the 90-day report-only threshold and command in `architecture/README.md`. Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
python3 architecture/scripts/validate_architecture.py --root . --report-stale --as-of 2026-09-01
```

Expected: tests pass; any stale documents print deterministic warnings and the command exits `0`.

- [ ] **Step 5: Commit stale reporting**

```bash
git add architecture/README.md architecture/scripts/validate_architecture.py architecture/scripts/tests/test_validate_architecture.py
git commit -m "feat: report stale architecture verification"
```

### Task 10: Enforce architecture documentation in pull requests and CI

**Files:**
- Create: `.github/pull_request_template.md`
- Create: `.github/workflows/architecture-docs.yml`
- Modify: `architecture/README.md`
- Modify: `architecture/scripts/validate_architecture.py`
- Modify: `architecture/scripts/tests/test_validate_architecture.py`

**Interfaces:**
- Consumes: complete local validation and render commands.
- Produces: an architecture-impact declaration for reviewers, a read-only PR-event-body checker, and an automated GitHub Actions gate on every pull request.

- [ ] **Step 1: Add failing PR-body and workflow-contract validation**

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

Add tests before changing production code:

- `validate_pr_body` rejects no selected box, both selected boxes, a missing required label, and `Architecture changed` when all four artifact fields are `None` or verification evidence is `None`/empty.
- `validate_pr_body` accepts exactly one selection; for `Architecture changed`, all five labels have non-empty values, `None` is allowed only for an unaffected artifact field, at least one of ADR/arc42/proposal/diagram is not `None`, and verification evidence is not `None`.
- `validate_workflow_contract` requires all `pull_request` events without a path filter, `push` to `master`, top-level `permissions: contents: read`, checkout `fetch-depth: 0`, the PR-body step guarded by `github.event_name == 'pull_request'`, unit tests, repository validation, npm install, diagram rendering, stale reporting with an explicit UTC date, and event-aware diff checking.
- A workflow fixture with `pull_request.paths`, `contents: write`, missing PR-body checking, or bare `git diff --check` fails with a focused diagnostic.

- [ ] **Step 2: Run tests and verify failure**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: fail because `validate_pr_body`, `validate_workflow_contract`, and their registry/CLI behavior do not exist.

- [ ] **Step 3: Implement template, PR-body, and workflow contracts**

Extend `validate_structure` with the literal template prompts. Implement `validate_pr_body(body: str) -> list[str]` using the exact selection and field rules from Step 1. Extend `main` with `--pr-event PATH`: load the standard GitHub event JSON using `json`, read `pull_request.body` as an empty string when null, print deterministic errors, and return non-zero on violations. Implement `validate_workflow_contract(root: Path) -> list[str]`, register `workflow`, and validate the required trigger, permission, checkout, step, and diff-contract literals without adding a YAML dependency.

- [ ] **Step 4: Create the pull-request template**

Include the exact architecture block above, plus a warning that selecting `No architecture impact` while changing a boundary, invariant, contract, deployment topology, trust boundary, or resource budget is a review defect.

- [ ] **Step 5: Create the GitHub Actions workflow**

Configure all `pull_request` activity with no `paths` filter so architecture-impact declarations run for architecture-bearing source-code changes, and configure pushes to `master` with no path filter. Use `ubuntu-24.04`, `actions/checkout@v4` with `fetch-depth: 0`, `actions/setup-python@v5` with Python `3.12`, and `actions/setup-node@v4` with Node `22`. Grant only top-level `contents: read`; do not request pull-request write permission.

Run these steps in order:

```yaml
- run: python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
- if: github.event_name == 'pull_request'
  run: python3 architecture/scripts/validate_architecture.py --root . --pr-event "$GITHUB_EVENT_PATH"
- run: python3 architecture/scripts/validate_architecture.py --root .
- run: npm ci --prefix architecture/tooling
- run: architecture/scripts/render-diagrams.sh
- run: |
    set -o pipefail
    python3 architecture/scripts/validate_architecture.py --root . --report-stale --as-of "$(date -u +%F)" | tee -a "$GITHUB_STEP_SUMMARY"
```

The final workflow step must be an event-aware shell block. On pull requests, read `.pull_request.base.sha` and `.pull_request.head.sha` from `$GITHUB_EVENT_PATH` with `jq -r`, verify both commits exist, and run `git diff --check "$base_sha..$head_sha"`. On pushes, read `.before`; when it is a non-zero existing commit run `git diff --check "$before_sha..$GITHUB_SHA"`, otherwise run `git diff-tree --check --root "$GITHUB_SHA"`. This is why checkout uses `fetch-depth: 0`; a bare working-tree-only `git diff --check` is not the CI contract.

- [ ] **Step 6: Link governance to CI and run the local acceptance gate**

Update `architecture/README.md` with the CI workflow path. Leave ADR-0001 implementation status `Partial` in this commit because the evidence commit hash does not exist yet.

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
python3 architecture/scripts/validate_architecture.py --root .
npm ci --prefix architecture/tooling
architecture/scripts/render-diagrams.sh
python3 architecture/scripts/validate_architecture.py --root . --checks workflow
git diff --check
git diff --check "$(cat /tmp/core-banking-architecture-base)..HEAD"
git status --short
```

Expected: all unit tests pass; repository validation prints `architecture validation passed`; all five Mermaid sources render; both whitespace checks are silent; status lists only Task 10 files before commit.

- [ ] **Step 7: Commit workflow enforcement**

```bash
git add .github/pull_request_template.md .github/workflows/architecture-docs.yml architecture/README.md architecture/scripts/validate_architecture.py architecture/scripts/tests/test_validate_architecture.py
git commit -m "ci: enforce architecture documentation contracts"
```

### Task 11: Finalize immutable implementation evidence

**Files:**
- Modify: `architecture/adr/0001-manage-architecture-as-versioned-code.md`

**Interfaces:**
- Consumes: the full commit hash produced by Task 10.
- Produces: ADR-0001 with `Implementation status: Complete` and immutable local implementation evidence.

- [ ] **Step 1: Capture and validate the Task 10 commit hash**

Run:

```bash
framework_commit="$(git rev-parse HEAD)"
test "$(printf '%s' "$framework_commit" | wc -c)" -eq 40
git show --quiet --format='%s' "$framework_commit"
```

Expected: a 40-character hash and subject `ci: enforce architecture documentation contracts`.

- [ ] **Step 2: Append evidence without rewriting rationale**

Change only `Implementation status: Partial` to `Implementation status: Complete` and append the full Task 10 hash plus these verified commands under `## Implementation evidence`:

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
python3 architecture/scripts/validate_architecture.py --root . --report-stale --as-of 2026-09-01
npm ci --prefix architecture/tooling
architecture/scripts/render-diagrams.sh
node -e 'const l=require("./architecture/tooling/package-lock.json"); if(l.packages["node_modules/@mermaid-js/mermaid-cli"].version!=="11.16.0") process.exit(1)'
test -z "$(git ls-files 'architecture/tooling/node_modules/**' 'architecture/diagrams/generated/**' '*.svg')"
git diff --check "$(cat /tmp/core-banking-architecture-base)..HEAD"
git status --short --branch
```

Expected results:

- Validator unit tests report zero failures and zero errors.
- Repository validation prints `architecture validation passed`.
- Stale verification is reported at a 90-day threshold and warnings alone exit zero.
- Exactly five Mermaid sources render successfully into a temporary directory.
- `package-lock.json` resolves `@mermaid-js/mermaid-cli` exactly to `11.16.0`; no `node_modules`, generated diagram output, or SVG is tracked.
- The eleven implementation commits after the captured base contain no whitespace errors.
- The working tree is clean.
- `ARCHITECTURE.md` is under 180 lines.
- All twelve arc42 files are `current` or `deprecated`; none is `proposed`.
- ADR identifiers are contiguous from `0001` through `0008`.
- Every proposal has reciprocal ADR and plan links; the three unimplemented plans link their proposals, while the implemented accounting-kernel plan links current arc42 sections and retrospective ADRs without a proposal backlink.
- The granular migration inventory covers every material heading under top-level sections `1` through `27`, has unique stable source keys, zero unresolved rows, and existing required destination/evidence paths.
- The old comprehensive-design path is absent and its archived copy is explicitly non-authoritative.
- No generated SVG or `node_modules` content is tracked.
