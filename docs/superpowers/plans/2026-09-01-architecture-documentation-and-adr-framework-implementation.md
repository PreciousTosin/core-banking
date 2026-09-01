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
- Before Task 1 changes any file, create the durable local baseline ref `refs/codex/architecture-docs-framework-base`; fail rather than overwrite it if it already exists. Before every cross-task range review, resolve that ref to a 40-lowercase-hex commit, verify the commit exists, and use `<resolved-base>..HEAD`, never `HEAD~N`. Delete only this exact ref after Final Verification succeeds.
- Markdown links resolve relative to the containing Markdown file after stripping query strings while preserving and validating fragments; the validator supports inline and reference-style links, angle-bracket destinations, and backslash-escaped spaces.
- Link validation scans governed repository Markdown from the filesystem, including newly created and untracked task files, while pruning `.git/`, `.worktrees/`, `.claude/worktrees/`, `graft/`, every `node_modules/`, Maven/Gradle `target/` and `build/` output, and `architecture/diagrams/generated/`. Markdown fenced-code blocks and inline-code spans are examples, not link-bearing prose, and are excluded before destinations are parsed.
- Unrelated untracked or modified user state, including `.claude/worktrees/`, is reported for awareness but never modified, staged, deleted, ignored, or treated as a framework failure. Task cleanliness assertions use `git diff --quiet`, `git diff --cached --quiet`, and `git status --short --` followed by the explicit paths owned by the current task.

## File Structure

### Stable entry points and governance

- Create `ARCHITECTURE.md`: concise current system summary, constraints, core invariants, and navigation.
- Create `architecture/README.md`: authority hierarchy, current/proposed rules, ownership, change workflow, and review cadence.
- Create `architecture/adr/README.md`: ADR threshold, lifecycle, numbering, mutability, and retrospective-record policy.
- Create `architecture/adr/template.md`: required ADR fields and headings.
- Create `architecture/proposals/README.md`: proposal statuses and promotion/archive workflow.
- Create `architecture/archive/proposals/README.md`: implemented-proposal archive convention and traceability requirements.
- Create `architecture/diagrams/README.md`: Mermaid metadata, state labels, render command, and ownership rules.

### Current-state arc42 baseline

- Create all twelve files under `architecture/arc42/` exactly as named in the specification.
- Each file owns one arc42 concern and begins with the approved metadata block.
- `architecture/arc42/09-decisions.md` is an index and does not duplicate ADR rationale.

### Migration and historical preservation

- Create `architecture/archive/comprehensive-design-migration-inventory.md`: granular rows for every material top-level section and subsection, with stable unique source keys, exact heading-relative material-block coverage, disposition, stable destination anchors, evidence, rationale, and resolution while retaining full top-level coverage `1` through `27`.
- Create `architecture/archive/comprehensive-design-migration-review.md`: independent approval of the resolved pre-cutover inventory, bound to a reviewed full commit and the committed inventory blob.
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
- Produces: `extract_markdown_destinations(text: str) -> list[str]`, which returns destinations from prose links only and ignores fenced and inline code examples.
- Produces: `extract_markdown_links(text: str) -> list[MarkdownLink]`, `extract_anchors(text: str) -> set[str]`, and deterministic link/reference diagnostics that retain fragments.
- Produces: `validate_repository(root: Path, checks: frozenset[str]) -> list[str]`, where an empty list means success and each non-empty string is one actionable validation error.
- CLI: `python3 architecture/scripts/validate_architecture.py --root . --checks links`; omit `--checks` to run all checks registered at that point in the plan.

- [ ] **Step 1: Capture the implementation baseline**

Run before changing a file:

```bash
base_ref=refs/codex/architecture-docs-framework-base
if git show-ref --verify --quiet "$base_ref"; then
  echo "$base_ref already exists; inspect and remove it deliberately before restarting" >&2
  exit 1
fi
base_commit="$(git rev-parse --verify 'HEAD^{commit}')"
printf '%s' "$base_commit" | grep -Eq '^[0-9a-f]{40}$'
git cat-file -e "$base_commit^{commit}"
git update-ref "$base_ref" "$base_commit" ""
recorded_base="$(git rev-parse --verify "$base_ref^{commit}")"
printf '%s' "$recorded_base" | grep -Eq '^[0-9a-f]{40}$'
git cat-file -e "$recorded_base^{commit}"
```

Expected: the previously absent local ref resolves to the exact starting commit. Keep it through Final Verification; no task may overwrite or delete it.

- [ ] **Step 2: Write failing validator unit tests**

Create temporary repositories in `unittest.TestCase` methods and cover only the generic primitives owned by this task:

```python
def test_relative_link_rejects_a_missing_target(self):
    self.write("ARCHITECTURE.md", "[missing](architecture/missing.md)\n")
    errors = validator.validate_links(self.root)
    self.assertTrue(any("architecture/missing.md does not exist" in error for error in errors))

def test_nested_links_resolve_from_containing_file(self):
    self.write("architecture/arc42/target file.md", "# Section\n")
    self.write("architecture/guides/nested.md", "[target](<../arc42/target file.md?view=full#section>)\n")
    self.assertEqual([], validator.validate_links(self.root))

def test_escaped_space_link_resolves_from_containing_file(self):
    self.write("architecture/arc42/target file.md", "# Section\n")
    self.write("architecture/guides/nested.md", "[target](../arc42/target\\ file.md#section)\n")
    self.assertEqual([], validator.validate_links(self.root))

def test_missing_same_file_and_cross_file_anchors_fail(self):
    self.write("architecture/target.md", "# Existing\n")
    self.write("architecture/source.md", "# Source\n[local](#missing)\n[remote](target.md#missing)\n")
    errors = validator.validate_links(self.root)
    self.assertTrue(any("source.md#missing" in error for error in errors))
    self.assertTrue(any("target.md#missing" in error for error in errors))

def test_github_heading_slugs_are_deterministic_for_duplicates(self):
    self.write("architecture/target.md", "# Repeated heading\n## Repeated heading\n## Repeated heading\n")
    self.write("architecture/source.md", "[first](target.md#repeated-heading) [second](target.md#repeated-heading-1) [third](target.md#repeated-heading-2)\n")
    self.assertEqual([], validator.validate_links(self.root))

def test_explicit_html_id_is_a_valid_anchor(self):
    self.write("architecture/target.md", '<a id="stable-destination"></a>\n# Display title\n')
    self.write("architecture/source.md", "[target](target.md#stable-destination)\n")
    self.assertEqual([], validator.validate_links(self.root))

def test_reference_style_link_and_definition_are_resolved(self):
    self.write("architecture/target.md", "# Stable section\n")
    self.write("architecture/source.md", "[target][architecture target]\n\n[architecture target]: <target.md#stable-section> \"Title\"\n")
    self.assertEqual([], validator.validate_links(self.root))

def test_undefined_reference_link_fails(self):
    self.write("architecture/source.md", "[target][missing definition]\n")
    errors = validator.validate_links(self.root)
    self.assertTrue(any("undefined reference: missing definition" in error for error in errors))

def test_broken_links_inside_fenced_and_inline_code_are_examples(self):
    self.write(
        "docs/examples.md",
        "`[inline](missing-inline.md)`\n\n```markdown\n[fenced](missing-fenced.md)\n```\n",
    )
    self.assertEqual([], validator.validate_links(self.root))

def test_destination_extraction_masks_code_but_keeps_prose_links(self):
    text = "`[inline](missing-inline.md)`\n```md\n[fenced](missing-fenced.md)\n```\n[real](real.md)\n"
    self.assertEqual(["real.md"], validator.extract_markdown_destinations(text))

def test_link_scan_includes_new_untracked_markdown(self):
    self.write("new-task-not-added-to-git.md", "[missing](governed-missing.md)\n")
    errors = validator.validate_links(self.root)
    self.assertTrue(any("governed-missing.md does not exist" in error for error in errors))

def test_link_scan_prunes_non_governed_trees(self):
    for path in (
        ".git/objects/example.md",
        ".worktrees/feature/example.md",
        ".claude/worktrees/mirror/example.md",
        "graft/cache/example.md",
        "architecture/tooling/node_modules/pkg/example.md",
        "services/funds-core/target/site/example.md",
        "module/build/reports/example.md",
        "architecture/diagrams/generated/example.md",
    ):
        self.write(path, "[ignored](missing.md)\n")
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

Define immutable `MarkdownLink(destination: str, line: int)`, `parse_front_matter(path: Path) -> dict[str, str | list[str]]`, `extract_markdown_links(text: str) -> list[MarkdownLink]`, `extract_markdown_destinations(text: str) -> list[str]`, `extract_anchors(text: str) -> set[str]`, `validate_links(root: Path) -> list[str]`, and `main(argv: Sequence[str] | None = None) -> int` with those exact names and types before constructing `VALIDATORS`.

Implement the bodies with these exact rules:

- Parse only the repository's YAML subset: scalar `key: value`, `key: []`, and indented `- item` lists between the first two `---` lines.
- `links` walks Markdown files from the repository-root filesystem rather than `git ls-files`, so newly created and untracked task files are governed. Prune the exact repository-relative roots `.git/`, `.worktrees/`, `.claude/worktrees/`, `graft/`, and `architecture/diagrams/generated/`, plus any directory component named `node_modules`, `target`, or `build`; do not follow symlinked directories, which can be worktree or cache mirrors.
- Before extracting Markdown links, mask CommonMark fenced code blocks opened by at least three backticks or tildes and inline code spans delimited by matching backtick runs. Preserve line breaks while masking so diagnostics retain correct locations. Parse inline links, full/collapsed/shortcut reference links, and case-insensitive reference definitions; reject every used reference without exactly one definition. Unwrap angle-bracket destinations, convert Markdown backslash-escaped spaces to literal spaces, strip a query component, retain a decoded fragment, and skip `http`, `https`, and `mailto` destinations.
- Resolve file paths against the containing file's parent. For pure fragments validate the containing file; for cross-file fragments validate the resolved Markdown file. Build anchors from explicit HTML `id` attributes and deterministic GitHub-style heading slugs: lowercase, remove formatting and punctuation other than hyphens/underscores, convert spaces to hyphens, and append `-1`, `-2`, and later suffixes to duplicate base slugs in document order. A missing target file and a missing fragment are separate actionable errors.
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
- Create: `architecture/archive/proposals/README.md`
- Create: `architecture/diagrams/README.md`
- Modify: `architecture/scripts/validate_architecture.py`
- Modify: `architecture/scripts/tests/test_validate_architecture.py`

**Interfaces:**
- Consumes: authority, metadata, lifecycle, and review rules from the approved specification.
- Produces: stable human entry points and templates consumed by every later documentation task.

- [ ] **Step 1: Add failing governance-structure and root-size tests**

Add `test_required_governance_files` to the validator tests. It creates a temporary empty root, calls the new `validate_structure`, and asserts the error list names all seven files above. Add `test_root_architecture_must_be_fewer_than_180_lines`, with a valid 179-line fixture and an invalid 180-line fixture, and assert the latter reports `ARCHITECTURE.md must contain fewer than 180 lines`.

- [ ] **Step 2: Run the focused test and verify failure**

Run:

```bash
python3 -m unittest architecture.scripts.tests.test_validate_architecture.ValidatorTest.test_required_governance_files -v
```

Expected: fail with `AttributeError: module 'validate_architecture' has no attribute 'validate_structure'` before production code changes.

- [ ] **Step 3: Implement the governance-structure rule**

Add `structure` to `CHECKS` and `VALIDATORS`; implement the exact new rule that `validate_structure` requires the seven governance files listed in this task, reports every missing path, and rejects a root `ARCHITECTURE.md` with 180 or more physical lines.

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
- `architecture/archive/proposals/README.md`: only completed proposals move here, `status: implemented` is required for an implemented proposal, and the archived proposal retains links to its current arc42 replacement, implementation evidence, ADRs, and plan history.
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
git add ARCHITECTURE.md architecture/README.md architecture/adr/README.md architecture/adr/template.md architecture/proposals/README.md architecture/archive/proposals/README.md architecture/diagrams/README.md architecture/scripts/validate_architecture.py architecture/scripts/tests/test_validate_architecture.py
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

- [ ] **Step 1: Add failing current-state lifecycle and metadata tests**

Add tests that call new `validate_metadata` and require exactly the twelve filenames, reject `status: proposed` under `arc42/`, reject absent or empty owners, reject a `code_refs` path that does not exist, and accept only ISO `YYYY-MM-DD` `last_verified` values. Add a deprecated arc42 fixture whose `replacement` field is an existing local Markdown link and negative fixtures for a missing, empty, non-link, missing-target, or self-referential replacement. Add proposal fixtures proving `status: implemented` is rejected under active `architecture/proposals/`, accepted only under `architecture/archive/proposals/`, and rejected there if current-architecture replacement or implementation-evidence traceability is absent. Before production changes these tests must fail because `validate_metadata` is absent.

- [ ] **Step 2: Run focused tests and verify failure**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: fail with a missing `validate_metadata` behavior or missing twelve-file diagnostics; record the focused failure before implementation.

- [ ] **Step 3: Implement the arc42 metadata contract**

Add `metadata` to `CHECKS` and `VALIDATORS` and implement exactly the arc42 filename, required-field, status, date, code-reference, and deprecated-replacement rules from Step 1. Inspect proposal placement now: reject `status: implemented` in active `architecture/proposals/`; permit it only below `architecture/archive/proposals/` and require existing current-architecture and implementation-evidence links. Full proposal metadata and bidirectional traceability remain owned by Task 7.

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
- Modify: `architecture/README.md`
- Modify: all twelve files under `architecture/arc42/`
- Modify: `architecture/adr/README.md`
- Modify: `architecture/proposals/README.md`
- Modify: `services/funds-core/README.md`
- Modify: `docs/superpowers/plans/2026-08-30-account-identifiers-and-nip-inbound-implementation.md`
- Modify: `docs/superpowers/plans/2026-08-30-conventional-deposit-products-and-accrual-implementation.md`
- Modify: `docs/superpowers/plans/2026-08-30-non-interest-banking-products-implementation.md`
- Modify: `docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md`
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
- Every non-`historical-only` covered block must map exactly once to a stable `repository/path.md#explicit-anchor` destination. An empty destination, missing anchor, duplicate or omitted block mapping, destination mapped from the wrong source key, or destination section lacking the exact `<!-- migration-source: <source-key> -->` marker fails. A fixture with two destinations proves each block maps to its exact destination rather than merely proving both files exist.
- Every row, including an unsplit one-row heading, fails when `Covered blocks` or `Rationale` is empty. A `historical-only` row fails unless its rationale explicitly explains why no maintained destination is appropriate.
- For a heading with blocks `B01` and `B02`, a fixture missing `B02` fails with a coverage-gap diagnostic, and a fixture assigning `B01` to two rows fails with a coverage-overlap diagnostic. A mixed-content heading represented by multiple rows also fails if its source keys do not use contiguous distinct `::01`, `::02` segment suffixes.

Build the valid fixture with all 27 roots, real temporary destination files, and real temporary evidence files; do not use a one-row fixture as a supposedly complete inventory.

Use this fixture construction pattern so the single expected unresolved diagnostic is not contaminated by missing paths or missing top-level coverage:

```python
self.write("architecture/README.md", "# Architecture\n<a id=\"migration-01\"></a>\n<!-- migration-source: 01 -->\n")
self.write("services/funds-core/README.md", "# Funds core\n")
self.write(
    "architecture/modern-core-banking-comprehensive-design-revised.md",
    "\n\n".join(
        (
            f"## {section}. Section {section}\n\nCurrent paragraph.\n\nProposed paragraph."
            if section == 8
            else f"## {section}. Section {section}\n\nMaterial paragraph for section {section}."
        )
        for section in range(1, 28)
    ),
)
rows = []
for section in range(1, 28):
    if section == 8:
        self.write("architecture/current-08.md", '# Current\n<a id="block-01"></a>\n<!-- migration-source: 08::01 -->\n')
        self.write("architecture/proposal-08.md", '# Proposal\n<a id="block-02"></a>\n<!-- migration-source: 08::02 -->\n')
        rows.extend([
            "| 08::01 | 8. Section 8 | B01 | current | B01=architecture/current-08.md#block-01 | services/funds-core/README.md | B01 is verified current behavior. | resolved |",
            "| 08::02 | 8. Section 8 | B02 | proposal | B02=architecture/proposal-08.md#block-02 | None | B02 is an unimplemented design. | resolved |",
        ])
        continue
    resolution = "unresolved" if section == 27 else "resolved"
    self.write(f"architecture/destination-{section:02d}.md", f'# Destination {section}\n<a id="source-{section:02d}"></a>\n<!-- migration-source: {section:02d} -->\n')
    rows.append(
        f"| {section:02d} | {section}. Section {section} | B01 | service-detail | B01=architecture/destination-{section:02d}.md#source-{section:02d} | None | B01 belongs in detailed service documentation. | {resolution} |"
    )
self.write(
    "architecture/archive/comprehensive-design-migration-inventory.md",
    "| Source key | Source heading | Covered blocks | Disposition | Destination map | Evidence | Rationale | Resolution |\n"
    "|---|---|---|---|---|---|---|---|\n" + "\n".join(rows) + "\n",
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
| Source key | Source heading | Covered blocks | Disposition | Destination map | Evidence | Rationale | Resolution |
|---|---|---|---|---|---|---|---|
```

Inventory every material `##`, `###`, and `####` heading within numbered sections 1 through 27, not merely the 27 top-level headings. Derive a stable source key from the printed heading number: zero-pad each numeric component (`8.1.1` becomes `08.01.01`); named worked examples use a lowercase slug (`13.08.example-a`). If one heading mixes dispositions, split it into `08.01::01`, `08.01::02`, and later contiguous segment keys, with one row per classified segment. Full source keys must be unique and top-level roots `01` through `27` must all be represented.

For machine-checkable material coverage, tokenize each heading's direct body, from the heading line to the next Markdown heading of any level, into ordered heading-relative blocks `B01`, `B02`, and later ordinals. A paragraph is one maximal run of non-blank prose lines; a list is one maximal contiguous list including indented continuations; a table is one maximal contiguous Markdown table; and a fenced block is one complete backtick- or tilde-fenced block. Blank lines separate blocks. The archive banner is outside every numbered heading body, so these ordinals survive both `git mv` and banner insertion. `Covered blocks` is a semicolon-separated list of exact ordinals. Across all rows for one heading, require every derived block ordinal exactly once: a gap, duplicate within one row, or overlap across rows is a blocking error. Require at least one covered block and a non-empty `Rationale` on every row, including unsplit headings. The rationale states why that material has the selected disposition; for `historical-only`, it must explicitly state why retention only in the archive is appropriate and why no maintained destination exists.

Allowed `Disposition` values are exactly `current`, `proposal`, `decision`, `service-detail`, `plan-detail`, and `historical-only`. Allowed `Resolution` values are exactly `unresolved` and `resolved`. `Destination map` contains semicolon-separated `BLOCK=repository/path.md#explicit-anchor` entries and maps every `Covered blocks` ordinal exactly once; this exact block mapping removes ambiguity when a row has multiple destinations. Every non-historical destination file and fragment must exist, and the destination section selected by that fragment must contain the exact explicit backlink `<!-- migration-source: SOURCE_KEY -->`. A marker for a different key is an error. Use `None` only for a `historical-only` destination. Semicolon-separated `Evidence` entries must exist for every `current` row and may be `None` for other dispositions. No row may resolve merely by pointing to the comprehensive source document.

At initial inventory commit, use existing arc42, plan, service-document, source, test, and migration paths. Rows requiring not-yet-created ADRs or proposals remain `unresolved` and point to the already-existing `architecture/adr/README.md` or `architecture/proposals/README.md` governance destination until Tasks 5 and 7 replace that destination with the exact created artifact. This preserves path validity without falsely claiming extraction is complete.

For every maintained destination chosen in this task, add a stable explicit HTML anchor immediately before the narrowest destination section containing the extracted material and place `<!-- migration-source: SOURCE_KEY -->` immediately after the anchor. When several source keys map to the same section, add one exact marker line per key. Plan-detail and service-detail rows receive the same anchor/marker treatment in their existing Markdown destinations. Do not use a general file-level anchor when a more exact subsection owns the material.

- [ ] **Step 4: Implement the migration contract**

Add `migration` to `CHECKS` and `VALIDATORS` and implement `validate_migration_inventory(root: Path) -> list[str]` with the exact schema, source-key grammar, allowed values, top-level and subsection coverage, uniqueness, material-block tokenization and exact-once coverage, non-empty per-row rationale, historical-only rationale, contiguous mixed-segment suffix, exact destination-block mapping, destination-file and anchor existence, exact source-marker backlink, current-evidence, and unresolved-row rules above. Parse numbered `##`/`###`/`####` headings from the comprehensive source, plus unnumbered `#### Example A` through `#### Example J` under section 13.8, and require each derived material heading key to have either one exact inventory key or one or more segment keys with that key plus `::NN`; reject inventory keys that do not map back to a source heading. Table parsing must report malformed rows rather than silently skipping them.

- [ ] **Step 5: Validate the inventory's deliberate interim state**

Run:

```bash
python3 architecture/scripts/validate_architecture.py --root . --checks migration
```

Expected: fail only with one or more `unresolved migration row` diagnostics for ADR/proposal extraction scheduled in Tasks 5 and 7. All schema, coverage, uniqueness, destination, and evidence checks pass.

- [ ] **Step 6: Commit the classification inventory**

```bash
git add architecture/archive/comprehensive-design-migration-inventory.md architecture/README.md architecture/arc42 architecture/adr/README.md architecture/proposals/README.md services/funds-core/README.md docs/superpowers/plans/2026-08-30-account-identifiers-and-nip-inbound-implementation.md docs/superpowers/plans/2026-08-30-conventional-deposit-products-and-accrual-implementation.md docs/superpowers/plans/2026-08-30-non-interest-banking-products-implementation.md docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md architecture/scripts/validate_architecture.py architecture/scripts/tests/test_validate_architecture.py
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
- Produces: `validate_accepted_adr_immutability(root: Path, base_ref: str, head_ref: str | None = None) -> list[str]`; `head_ref=None` compares the base commit with the current working tree, while a supplied head compares two Git trees.
- CLI: `--adr-base-ref REF [--adr-head-ref REF]`; this git-aware check is additive to ordinary repository checks and ignores ADR paths absent at the base.

- [ ] **Step 1: Add failing ADR contract and reciprocal-traceability tests**

Write tests against a new `validate_adrs` behavior before implementing it. Test contiguous numbering, filename/title agreement, required headings, valid statuses, relationship fields containing either a non-empty value or the literal `None`, retrospective marking, separation of decision from implementation status, evidence syntax, and substantive content. The substantive headings are exactly `## Context`, `## Decision drivers`, `## Considered options`, `## Decision`, `## Consequences`, `### Positive`, `### Negative`, `### Risks`, `## Compliance and verification`, and `## Implementation evidence`; each must contain non-whitespace prose, a list item, or a link before the next heading of the same or higher level. Add one negative test per empty substantive heading. Test that the body of `## Implementation evidence` may be exactly `None` only with `Not started` or `Not applicable`, while `Partial` and `Complete` require at least one existing repository path plus a full 40-lowercase-hex commit hash or stable `https://github.com/<owner>/<repo>/pull/<number>` URL in the evidence section or matching relationship fields.

Also add negative reciprocal fixtures proving all of these fail independently: a foundational ADR `0001` through `0008` with `Related architecture sections: None`; an ADR architecture-section link whose repository-relative path does not exist; an ADR linking an existing arc42 file whose `related_adrs` omits that ADR ID; an arc42 `related_adrs` ID with no matching ADR; and an arc42 ADR ID whose ADR does not link back to that exact section. Add a positive fixture with two ADR/arc42 pairs so the check cannot pass by comparing only aggregate sets.

Add repository-backed lifecycle/evidence fixtures in temporary Git repositories:

- a local 40-lowercase-hex evidence hash resolves with the equivalent of `git cat-file -e "$hash^{commit}"`; a syntactically valid nonexistent hash fails, while a stable GitHub pull-request URL remains valid evidence;
- `Supersedes` and `Superseded by` reject a missing ADR target, self-reference, non-reciprocal edge, incompatible statuses, and a cycle; accept a reciprocal `Accepted` successor that supersedes a `Superseded` predecessor;
- an ADR Accepted at the base rejects mutations to `Context`, `Decision drivers`, `Considered options`, `Decision`, or the complete `Consequences` subtree including `Positive`, `Negative`, and `Risks`;
- an Accepted-at-base ADR accepts only `Accepted -> Superseded` or `Accepted -> Deprecated`, rejects every reverse or lateral decision-status change, and enforces implementation status monotonically as `Not started -> Partial -> Complete`; `Not applicable` may remain unchanged but cannot transition to or from another implementation status;
- relationship/evidence sequences are append-only: legal suffix additions to `Related pull requests`, `Related commits`, `Related architecture sections`, `Related proposals`, `Supersedes`, `Superseded by`, `Compliance and verification`, and `Implementation evidence` pass, while rewriting, removal, insertion before an existing item, or reordering fails;
- a brand-new ADR absent from the base is not compared for accepted-record immutability, but ordinary ADR structure/lifecycle validation still applies.

Use one legal-append fixture and separate mutation fixtures so a single unrelated error cannot mask the behavior under test.

- [ ] **Step 2: Run ADR tests and verify failure**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: fail because `validate_adrs` and the `adrs` registry entry do not exist.

- [ ] **Step 3: Implement the ADR contract, lifecycle graph, evidence existence, and accepted-record immutability**

Add `adrs` to `CHECKS` and `VALIDATORS`. Implement the exact numbering, filename/title, field, lifecycle-value, substantive-section, relationship-`None`, retrospective, implementation-evidence, 40-hex, stable-PR-URL, evidence-path, and reciprocal architecture-link rules from Step 1. For each local full hash used as evidence, run Git through `subprocess` with argument vectors and require repository-aware commit existence equivalent to `git -C <root> cat-file -e <hash>^{commit}`; syntax alone is insufficient. Parse every Markdown destination in `Related architecture sections`, require it to resolve to an existing `architecture/arc42/*.md` file, and require that file's `related_adrs` metadata to contain the ADR's exact ID. Conversely, resolve every arc42 `related_adrs` ID to an ADR and require that ADR to link the exact arc42 path. For foundational ADRs `ADR-0001` through `ADR-0008`, reject `Related architecture sections: None`; `None` remains valid in the reusable template and in future non-foundational records when genuinely unaffected.

Resolve every `Supersedes` and `Superseded by` ADR ID. Reject missing targets, self-reference, non-reciprocal declarations, and cycles in the directed predecessor-to-successor graph. Every predecessor with `Superseded by: ADR-NNNN` must be `Superseded`, every named successor must be `Accepted`, and the successor's `Supersedes` field must name the predecessor; `Deprecated` records do not claim a superseding ADR. Reject multiple successors for one predecessor.

Implement `validate_accepted_adr_immutability` with `git rev-parse --verify REF^{commit}`, `git show COMMIT:PATH`, and current filesystem reads when `head_ref` is omitted. For every ADR present with `Status: Accepted` at the base, compare parsed records and require byte-stable normalized content for `Context`, `Decision drivers`, `Considered options`, `Decision`, and the entire `Consequences` section including its three required subsections. Allow decision status to remain `Accepted` or transition once to `Superseded`/`Deprecated`; enforce the implementation-status ordering and fixed `Not applicable` rule from Step 1. Treat each mutable relationship/evidence area as an ordered sequence, interpret the literal `None` as an empty sequence, and require the base sequence to be an exact prefix of the head/current sequence. Reject edits to all other accepted-record fields. Do not compare an ADR absent at base. Extend `main` with `--adr-base-ref` and optional `--adr-head-ref`; emit deterministic errors and return non-zero on a comparison failure.

- [ ] **Step 4: Write ADR-0001 through ADR-0004**

Use `Status: Accepted`. Every ADR links at least one exact affected arc42 file under `Related architecture sections`, and each linked arc42 file includes the reciprocal ADR ID in `related_adrs`. ADR-0001 is not retrospective and links the approved design plus its commit; implementation status is `Partial` until the framework is complete. ADR-0002 through ADR-0004 are retrospective with implementation status `Complete` and cite exact evidence:

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

Link each ADR from `09-decisions.md`; update `related_adrs` in every arc42 file to the exact IDs that govern its documented claims, using `[]` when none governs the section; and replace each decision inventory row's temporary governance destination with an exact explicit anchor in the created ADR before marking that row `resolved`. Put the exact `<!-- migration-source: SOURCE_KEY -->` marker in the selected ADR section.

- [ ] **Step 7: Validate and commit decisions**

Run:

```bash
python3 architecture/scripts/validate_architecture.py --root . --checks metadata,adrs,links
python3 architecture/scripts/validate_architecture.py --root . --adr-base-ref HEAD
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

Write tests against a new `validate_diagrams` behavior before implementing it. Test the seven required metadata comments, allowed state values, non-empty `abstraction`, non-empty `question`, existing arc42 path, existing ADR IDs, ISO date, matching state in the Mermaid title, required five filenames, and executable mode on `architecture/scripts/render-diagrams.sh`. Include negative tests for missing abstraction, missing question, missing title state, a `CURRENT` metadata/`PROPOSED` title mismatch, a non-executable render script, and an arc42 section that does not contain a Markdown link back to the diagram source. Include a positive fixture in which each diagram's declared arc42 section links its exact `.mmd` path.

- [ ] **Step 2: Run diagram tests and verify failure**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: fail because `validate_diagrams` and the `diagrams` registry entry do not exist.

- [ ] **Step 3: Implement the diagram contract**

Add `diagrams` to `CHECKS` and `VALIDATORS`. Implement the exact filename, first-ten-lines metadata/front-matter, state, abstraction, question, owner, arc42-path, ADR-ID, ISO-date, title-state agreement, executable-render-script, and reciprocal arc42-link rules from Step 1. The required comment keys are `state`, `abstraction`, `question`, `owner`, `arc42`, `adrs`, and `last_verified`; the Mermaid YAML front-matter title must include the same literal `CURRENT` or `PROPOSED` state. For each diagram, resolve its declared arc42 path and require that Markdown file to contain a link whose resolved destination is the exact diagram source.

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

The script must begin with `#!/usr/bin/env bash`, use `set -euo pipefail`, resolve repository paths from its own location, require `architecture/tooling/node_modules/.bin/mmdc`, create a caller-provided directory or `mktemp -d`, render each source to SVG, and remove only its own temporary directory on exit. Establish and verify the executable contract before invoking it:

Run:

```bash
npm ci --prefix architecture/tooling
chmod +x architecture/scripts/render-diagrams.sh
test -x architecture/scripts/render-diagrams.sh
architecture/scripts/render-diagrams.sh
python3 architecture/scripts/validate_architecture.py --root . --checks diagrams,links
```

Expected: all five required diagram sources render and metadata/link validation passes; additional governed `.mmd` sources are permitted only when they satisfy the same contracts and render successfully.

- [ ] **Step 8: Link diagrams and commit**

Link each source from its owning arc42 section; do not commit generated SVGs. Verify ignored/generated dependencies are not tracked:

```bash
test -z "$(git ls-files 'architecture/tooling/node_modules/**' 'architecture/diagrams/generated/**' '*.svg')"
```

```bash
git add .gitignore architecture/diagrams architecture/scripts/render-diagrams.sh architecture/scripts/validate_architecture.py architecture/scripts/tests/test_validate_architecture.py architecture/tooling architecture/arc42
test "$(git ls-files -s architecture/scripts/render-diagrams.sh | awk '{print $1}')" = 100755
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

Write tests against new proposal metadata and traceability behavior before implementation. Require all six proposal files; allowed statuses; existing `related_plans` paths; existing `related_adrs` IDs; a `**Proposal:**` backlink in the account-identifier, conventional-deposit, and non-interest plans; reciprocal `Related proposals:` links in every ADR named by a proposal; and reciprocal proposal links in every plan named by a proposal. Reassert that active `architecture/proposals/` rejects `status: implemented` and that an implemented proposal is valid only under `architecture/archive/proposals/` with current-architecture replacement and implementation evidence. For `2026-08-30-accounting-kernel-implementation.md`, require `**Current architecture:**` links to arc42 sections 05, 06, and 08 plus `**Retrospective ADRs:**` links to ADR-0002 through ADR-0006, and explicitly reject a `**Proposal:**` backlink.

- [ ] **Step 2: Run tests and verify failure**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: fail because proposal metadata and bidirectional traceability rules are not implemented.

- [ ] **Step 3: Implement proposal metadata and bidirectional traceability**

Extend `validate_metadata` with the exact proposal required fields and `PROPOSAL_STATUSES = frozenset({"draft", "proposed", "approved", "implementing", "implemented", "rejected", "superseded"})`, while retaining the placement rule that excludes `implemented` from the active directory. Add a `validate_traceability(root: Path) -> list[str]` check and register `traceability`. For every proposal, verify that each `related_plans` path exists and contains a link back to that proposal and that each `related_adrs` ID resolves to an ADR containing a `Related proposals:` link back to that proposal. Enforce the special accounting-kernel rule from Step 1 and verify the three unimplemented plan mappings exactly.

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

Update every proposal-classified inventory row with its exact real proposal anchor and `resolved`; put the exact `<!-- migration-source: SOURCE_KEY -->` marker in the selected proposal section. Run:

```bash
python3 architecture/scripts/validate_architecture.py --root . --checks metadata,links,migration,traceability
python3 architecture/scripts/validate_architecture.py --root . --adr-base-ref HEAD
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: metadata, links, migration inventory, bidirectional traceability, accepted-ADR immutability, and unit tests pass; every granular inventory row is now `resolved`, every final destination anchor exists, and every destination section carries the exact source-key backlink.

- [ ] **Step 8: Commit proposal separation**

```bash
git add architecture/proposals architecture/infrastructure/infra-ubuntu24.04-poc.md architecture/adr architecture/archive/comprehensive-design-migration-inventory.md docs/superpowers/plans/2026-08-30-account-identifiers-and-nip-inbound-implementation.md docs/superpowers/plans/2026-08-30-conventional-deposit-products-and-accrual-implementation.md docs/superpowers/plans/2026-08-30-non-interest-banking-products-implementation.md docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md architecture/scripts/validate_architecture.py architecture/scripts/tests/test_validate_architecture.py
git commit -m "docs: separate proposed architecture from current state"
```

### Task 8: Complete archive cutover and repair documentation links

**Files:**
- Move: `architecture/modern-core-banking-comprehensive-design-revised.md` to `architecture/archive/modern-core-banking-comprehensive-design-revised.md`
- Create: `architecture/archive/comprehensive-design-migration-review.md`
- Modify: `ARCHITECTURE.md`
- Modify: `architecture/README.md`
- Modify: `services/funds-core/README.md`
- Modify: `architecture/infrastructure/infra-ubuntu24.04-poc.md`
- Modify: `docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md`
- Modify: `architecture/scripts/validate_architecture.py`
- Modify: `architecture/scripts/tests/test_validate_architecture.py`

**Interfaces:**
- Consumes: a migration inventory with all 27 sections resolved and existing replacement destinations.
- Consumes: a read-only independent review of the committed Task 7 inventory, performed by a named reviewer other than the implementer before the source move.
- Produces: a non-authoritative historical document under `archive/` and no stale internal links.
- Produces: a persistent approval record bound to the reviewed pre-cutover full commit and exact inventory Git blob.

- [ ] **Step 1: Add failing archive-state-machine tests**

Write tests against a new `validate_archive_state` behavior before implementing it. Cover every state explicitly:

- valid unresolved: at least one granular row is `unresolved`, old source present, archive absent;
- valid resolved pre-cutover: all rows `resolved`, old source present, archive absent;
- valid resolved post-cutover: all rows `resolved`, old source absent, archive present;
- invalid source loss: unresolved rows with old source absent, or resolved rows with both old source and archive absent;
- invalid premature archive: unresolved rows with archive present;
- invalid duplicate copy: old source and archive both present, regardless of resolution.
- invalid post-cutover review: archived source without `comprehensive-design-migration-review.md`, a reviewer equal to the implementer, non-`APPROVED` outcome, nonzero unresolved count, malformed or nonexistent reviewed commit/blob, reviewed commit that is not the committed Task 7 pre-cutover state, inventory blob mismatch, or current committed inventory content differing from the reviewed content.

Use the complete 27-root, subsection, and material-block fixture from Task 4 rather than a path-presence-only fixture. Assert that `validate_migration_inventory` selects the old source in both pre-cutover states and the archived source in the post-cutover state, then performs the same granular source-key and exact-once material-block coverage checks against the selected file. In the archived fixture, remove one source material block while leaving its inventory ordinal in place and assert a focused source/content coverage error. Neither source and both sources must each produce an error; the validator may never merge, prefer, or silently skip duplicate/missing sources.

Add `test_archive_review_binds_named_approval_to_committed_inventory`, using a temporary Git repository with a resolved committed inventory. It records a different named reviewer and implementer, `Outcome: APPROVED`, `Unresolved rows: 0`, the 40-hex reviewed commit, and the 40-hex inventory blob; it passes, then fails independently after a nonexistent commit, wrong blob, or post-review inventory commit is introduced. The state check is separate from the inventory's unresolved-row diagnostic: unresolved/pre-cutover is a safe archive state even though the complete migration check still blocks cutover. Run the new tests before implementation:

```bash
python3 -m unittest architecture.scripts.tests.test_validate_architecture.ValidatorTest.test_archive_state_selects_exactly_one_source_and_rechecks_full_inventory -v
python3 -m unittest architecture.scripts.tests.test_validate_architecture.ValidatorTest.test_archive_review_binds_named_approval_to_committed_inventory -v
```

Expected: fail because the Task 4 migration validator only knows the old source path and the new archive-state/source-selection behavior does not exist.

- [ ] **Step 2: Implement the archive state machine, selected-source check, and review gate**

Implement `select_comprehensive_source(root: Path, all_rows_resolved: bool) -> tuple[Path | None, list[str]]` and use it from both `validate_migration_inventory` and `validate_archive_state`. It returns the old exact path when it alone exists in unresolved or resolved pre-cutover state, returns the archived exact path only when it alone exists and all rows are resolved, and returns deterministic errors plus `None` for neither, both, or an archived-only unresolved state. After selection, `validate_migration_inventory` must parse that selected file and rerun its full heading-key, top-level `01` through `27`, subsection, material-block tokenization, exact-once coverage, source-key back-reference, exact destination anchor/backlink mapping, evidence, rationale, and resolution contract; moving the source must not reduce validation to file presence.

Implement `validate_archive_review(root: Path) -> list[str]` and register `archive-review`. Parse exactly one value each for `Reviewed commit`, `Reviewer`, `Implementer`, `Outcome`, `Unresolved rows`, `Inventory path`, and `Inventory blob`. Require distinct non-empty reviewer and implementer identities, literal `APPROVED`, integer zero, the exact inventory path, and lowercase 40-hex commit/blob IDs. Independently parse the inventory and require zero unresolved rows. Verify the reviewed commit exists, resolves the recorded inventory path to the recorded blob, and its inventory bytes equal both the current filesystem bytes and the bytes at `HEAD:architecture/archive/comprehensive-design-migration-inventory.md`; this prevents either uncommitted or later committed inventory changes. Before the evidence file is tracked, require `Reviewed commit` to equal `HEAD`. After it is tracked, locate its unique introduction commit with `git log --diff-filter=A --format=%H -- architecture/archive/comprehensive-design-migration-review.md` and require the reviewed commit to equal that introduction commit's sole parent; later Task 8 commits therefore do not invalidate the record. Make archived-only state call this validator, so cutover cannot validate without persistent review evidence. Add `archive` to `CHECKS` and `VALIDATORS`; do not infer classification completeness from path presence alone.

Run:

```bash
python3 -m unittest architecture.scripts.tests.test_validate_architecture.ValidatorTest.test_archive_state_selects_exactly_one_source_and_rechecks_full_inventory -v
python3 architecture/scripts/validate_architecture.py --root . --checks migration,archive,links
```

Expected: the focused archive-state/source-selection tests pass, followed by repository validation with zero unresolved rows in the valid resolved pre-cutover state. The missing review file still blocks `archive-review`. Do not move the source document if granular coverage, destination anchors/backlinks, evidence, links, or archive state fails.

- [ ] **Step 3: Obtain independent read-only migration approval**

After Task 7 is committed and before any source move or inventory edit, resolve the exact review inputs:

```bash
reviewed_commit="$(git rev-parse --verify 'HEAD^{commit}')"
printf '%s' "$reviewed_commit" | grep -Eq '^[0-9a-f]{40}$'
git cat-file -e "$reviewed_commit^{commit}"
inventory_path=architecture/archive/comprehensive-design-migration-inventory.md
inventory_blob="$(git rev-parse --verify "$reviewed_commit:$inventory_path")"
printf '%s' "$inventory_blob" | grep -Eq '^[0-9a-f]{40}$'
git cat-file -e "$inventory_blob^{blob}"
python3 architecture/scripts/validate_architecture.py --root . --checks migration,archive,links
```

Have a reviewer other than the implementer inspect the committed inventory and selected source read-only at `reviewed_commit`. The reviewer must independently confirm every source block is classified exactly once, every non-historical block maps to the exact destination anchor bearing its source-key marker, every historical-only row has its required rationale, and zero rows are unresolved. Continue only when the reviewer provides their identity and literal outcome `APPROVED`; a request for changes returns execution to Task 7 and produces a new Task 7 commit before another review.

- [ ] **Step 4: Persist and commit the review evidence**

Create `architecture/archive/comprehensive-design-migration-review.md` with exactly these fields populated from the verified Git values and the two actual identities used for the review:

```markdown
# Comprehensive Design Migration Review

- Reviewed commit: 40-lowercase-hex Git commit recorded by Step 3
- Reviewer: identity supplied by the independent reviewer
- Implementer: identity of the Task 7 implementer
- Outcome: APPROVED
- Unresolved rows: 0
- Inventory path: architecture/archive/comprehensive-design-migration-inventory.md
- Inventory blob: 40-lowercase-hex Git blob recorded by Step 3
```

The descriptive text after `Reviewed commit`, `Reviewer`, `Implementer`, and `Inventory blob` above specifies the required concrete value; the created evidence file contains the concrete value itself. Do not modify the reviewed inventory. Run and commit:

```bash
python3 architecture/scripts/validate_architecture.py --root . --checks migration,archive,archive-review,links
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
git add architecture/archive/comprehensive-design-migration-review.md architecture/scripts/validate_architecture.py architecture/scripts/tests/test_validate_architecture.py
git commit -m "docs: approve comprehensive design migration"
review_commit="$(git rev-parse --verify 'HEAD^{commit}')"
test "$(git rev-parse --verify "$review_commit^")" = "$reviewed_commit"
python3 architecture/scripts/validate_architecture.py --root . --checks migration,archive,archive-review,links
```

- [ ] **Step 5: Move the comprehensive design with Git**

Run:

```bash
git mv architecture/modern-core-banking-comprehensive-design-revised.md architecture/archive/modern-core-banking-comprehensive-design-revised.md
```

Add a banner immediately under its title: `Historical source document — non-authoritative; see /ARCHITECTURE.md and the migration inventory.`

- [ ] **Step 6: Repair every old-path link**

Run:

```bash
rg -n 'architecture/modern-core-banking-comprehensive-design-revised.md|modern-core-banking-comprehensive-design-revised.md' --glob '*.md'
```

Update `services/funds-core/README.md`, `architecture/infrastructure/infra-ubuntu24.04-poc.md`, and the accounting-kernel plan to the root entry point or exact arc42/proposal destination. The approved design's repository-tree example and this implementation plan may retain the historical filename as non-link prose. Only the migration inventory may contain a Markdown link to the archived historical source.

- [ ] **Step 7: Run complete documentation validation**

Run:

```bash
python3 architecture/scripts/validate_architecture.py --root .
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
architecture/scripts/render-diagrams.sh
git diff --check
```

Expected: validation, unit tests, Mermaid rendering, and whitespace checks pass.

- [ ] **Step 8: Commit the archive cutover**

```bash
git add ARCHITECTURE.md architecture/archive/modern-core-banking-comprehensive-design-revised.md architecture/README.md architecture/infrastructure/infra-ubuntu24.04-poc.md services/funds-core/README.md docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md
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
- `validate_pr_body` parses exactly one canonical `## Architecture impact` section, requires each checkbox prompt and field label exactly once inside that section, and rejects a second canonical section or any duplicate canonical checkbox/field literal elsewhere in prose. Fenced examples and HTML comments containing complete fake sections or duplicate labels are masked and do not satisfy or invalidate the real section.
- `validate_workflow_contract` requires the explicit pull-request event set `types: [opened, synchronize, reopened, edited, ready_for_review]` with no path filter, `push` to `master`, top-level `permissions: contents: read`, checkout `fetch-depth: 0`, the PR-body step guarded by `github.event_name == 'pull_request'`, unit tests, repository validation, npm install, direct executable diagram rendering, stale reporting with an explicit UTC date, and event-aware diff checking.
- Workflow validation strips YAML comments outside quoted scalars and uses indentation-aware mapping/sequence parsing for top-level `on` and `permissions`, the validation job, and its ordered step mappings. A comment, block-scalar example, wrong job, or nested similarly named key cannot satisfy a required trigger, permission, guard, checkout input, or run step. Duplicate top-level/job keys and structurally misplaced literals fail.
- A workflow fixture with a missing `edited` event, `pull_request.paths`, `contents: write`, missing PR-body checking, a non-executable render-script contract, bare `git diff --check`, `git diff --check "$base_sha..$head_sha"` without a verified merge base, or `git diff-tree --check --root "$GITHUB_SHA"` as the unavailable-push-base fallback fails with a focused diagnostic.
- Add an integration-style unit fixture that initializes a temporary Git repository, commits a Markdown file containing trailing whitespace in the penultimate commit, and makes an unrelated clean tip commit. Assert the tip-only `git diff-tree --check "$tip"` output misses the earlier file, while `empty_tree=$(git hash-object -t tree /dev/null)` followed by `git diff --check "$empty_tree" "$tip"` reports it. This proves the fallback covers the complete current tree of a multi-commit replacement push rather than only the tip commit.
- Add a behind-base Git fixture: create a common commit, a feature head from it, and then advance the base branch separately. Assert `git merge-base "$base_sha" "$head_sha"` is the common commit, not the newer base tip, and assert the PR range helper validates and returns `merge_base..head_sha`. Include an Accepted ADR mutation on the feature branch and prove the same merge-base/head pair makes the git-aware ADR validator reject it.

- [ ] **Step 2: Run tests and verify failure**

Run:

```bash
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: fail because `validate_pr_body`, `validate_workflow_contract`, and their registry/CLI behavior do not exist.

- [ ] **Step 3: Implement template, PR-body, and workflow contracts**

Extend `validate_structure` with the literal template prompts. Implement `validate_pr_body(body: str) -> list[str]` by first masking fenced code and HTML comments while preserving lines, then locating level-two headings and parsing only the single canonical `## Architecture impact` section through the next level-one/two heading or EOF. Require each canonical checkbox prompt and field label exactly once inside it; scan remaining unmasked prose and reject canonical literals outside it. Apply the exact selection and value rules from Step 1. Extend `main` with `--pr-event PATH`: load the standard GitHub event JSON using `json`, read `pull_request.body` as an empty string when null, print deterministic errors, and return non-zero on violations.

Implement a small standard-library, indentation-aware workflow structure reader for the repository's workflow subset. It must remove YAML comments only outside quoted values, distinguish mappings, sequences, and block-scalar contents by indentation, reject duplicate keys in governed mappings, and return the exact top-level trigger/permission mappings plus ordered mappings for the architecture validation job's steps. Implement `validate_workflow_contract(root: Path) -> list[str]`, register `workflow`, and verify required values at their structural locations; never satisfy a contract by global literal search.

- [ ] **Step 4: Create the pull-request template**

Include the exact architecture block above, plus a warning that selecting `No architecture impact` while changing a boundary, invariant, contract, deployment topology, trust boundary, or resource budget is a review defect.

- [ ] **Step 5: Create the GitHub Actions workflow**

Configure `pull_request: types: [opened, synchronize, reopened, edited, ready_for_review]` with no `paths` filter so initial submissions, commits, reopenings, body edits, and draft-to-ready transitions all retrigger architecture-impact declarations. Configure pushes to `master` with no path filter. Use `ubuntu-24.04`, `actions/checkout@v4` with `fetch-depth: 0`, `actions/setup-python@v5` with Python `3.12`, and `actions/setup-node@v4` with Node `22`. Grant only top-level `contents: read`; do not request pull-request write permission.

Use this exact trigger contract:

```yaml
on:
  pull_request:
    types: [opened, synchronize, reopened, edited, ready_for_review]
  push:
    branches: [master]
```

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

The final workflow step must be an event-aware shell block. On pull requests, read `.pull_request.base.sha` and `.pull_request.head.sha` from `$GITHUB_EVENT_PATH` with `jq -r`, verify both are 40-lowercase-hex existing commits, compute `merge_base="$(git merge-base "$base_sha" "$head_sha")"`, verify the merge base is a 40-lowercase-hex existing commit, run `git diff --check "$merge_base..$head_sha"`, and run the ADR immutability CLI with that same base/head pair. This prevents a head branch behind the current base from validating the wrong range.

On pushes, read `.before`; when it is a non-zero 40-lowercase-hex existing commit, run whitespace and ADR immutability checks over `before_sha..GITHUB_SHA`. When `.before` is missing, null, all zeroes, malformed, or unavailable, compute the canonical empty tree and run the complete-current-tree whitespace check. Because the event supplies no trustworthy historical base in that case, walk every parent-to-child commit edge reachable from `GITHUB_SHA` in deterministic topological order and run accepted-ADR immutability for each edge; root commits have no accepted base record and are skipped. This catches an ADR accepted and then rewritten within a replacement push. A tip-only `git diff-tree --check --root "$GITHUB_SHA"` is forbidden. Checkout uses `fetch-depth: 0`, and a bare working-tree-only `git diff --check` is not the CI contract.

Use this exact step:

```yaml
- name: Check changed-tree whitespace
  shell: bash
  run: |
    set -euo pipefail
    sha_pattern='^[0-9a-f]{40}$'
    if [[ "$GITHUB_EVENT_NAME" == "pull_request" ]]; then
      base_sha="$(jq -r '.pull_request.base.sha // empty' "$GITHUB_EVENT_PATH")"
      head_sha="$(jq -r '.pull_request.head.sha // empty' "$GITHUB_EVENT_PATH")"
      [[ "$base_sha" =~ $sha_pattern ]]
      [[ "$head_sha" =~ $sha_pattern ]]
      git cat-file -e "$base_sha^{commit}"
      git cat-file -e "$head_sha^{commit}"
      merge_base="$(git merge-base "$base_sha" "$head_sha")"
      [[ "$merge_base" =~ $sha_pattern ]]
      git cat-file -e "$merge_base^{commit}"
      git diff --check "$merge_base..$head_sha"
      python3 architecture/scripts/validate_architecture.py --root . --adr-base-ref "$merge_base" --adr-head-ref "$head_sha"
      exit 0
    fi
    [[ "$GITHUB_SHA" =~ $sha_pattern ]]
    git cat-file -e "$GITHUB_SHA^{commit}"
    before_sha="$(jq -r '.before // empty' "$GITHUB_EVENT_PATH")"
    zero_sha=0000000000000000000000000000000000000000
    if [[ "$before_sha" =~ $sha_pattern ]] && [[ "$before_sha" != "$zero_sha" ]] && git cat-file -e "$before_sha^{commit}" 2>/dev/null; then
      git diff --check "$before_sha..$GITHUB_SHA"
      python3 architecture/scripts/validate_architecture.py --root . --adr-base-ref "$before_sha" --adr-head-ref "$GITHUB_SHA"
    else
      empty_tree="$(git hash-object -t tree /dev/null)"
      [[ "$empty_tree" =~ $sha_pattern ]]
      git diff --check "$empty_tree" "$GITHUB_SHA"
      while read -r child parent; do
        [[ "$child" =~ $sha_pattern ]]
        [[ "$parent" =~ $sha_pattern ]]
        python3 architecture/scripts/validate_architecture.py --root . --adr-base-ref "$parent" --adr-head-ref "$child"
      done < <(git rev-list --reverse --topo-order --parents "$GITHUB_SHA" | awk 'NF > 1 { child=$1; for (i=2; i<=NF; i++) print child, $i }')
    fi
```

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
base_ref=refs/codex/architecture-docs-framework-base
architecture_base="$(git rev-parse --verify "$base_ref^{commit}")"
printf '%s' "$architecture_base" | grep -Eq '^[0-9a-f]{40}$'
git cat-file -e "$architecture_base^{commit}"
git diff --check "$architecture_base..HEAD"
git status --short -- .github/pull_request_template.md .github/workflows/architecture-docs.yml architecture/README.md architecture/scripts/validate_architecture.py architecture/scripts/tests/test_validate_architecture.py
```

Expected: all unit tests pass; repository validation prints `architecture validation passed`; all five required Mermaid sources render; both whitespace checks are silent; scoped status lists only Task 10 files before commit. Run `git status --short` separately to report unrelated user state, but do not modify or stage it.

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
python3 architecture/scripts/validate_architecture.py --root . --adr-base-ref "$framework_commit"
npm ci --prefix architecture/tooling
architecture/scripts/render-diagrams.sh
git diff --check
git diff -- architecture/adr/0001-manage-architecture-as-versioned-code.md
```

Expected: all automated checks pass, the git-aware accepted-ADR check accepts the monotonic status/evidence append, and the ADR diff changes only implementation status and appended evidence.

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
test -x architecture/scripts/render-diagrams.sh
test "$(git ls-files -s architecture/scripts/render-diagrams.sh | awk '{print $1}')" = 100755
test -z "$(git ls-files 'architecture/tooling/node_modules/**' 'architecture/diagrams/generated/**' '*.svg')"
test "$(wc -l < ARCHITECTURE.md)" -lt 180
base_ref=refs/codex/architecture-docs-framework-base
architecture_base="$(git rev-parse --verify "$base_ref^{commit}")"
printf '%s' "$architecture_base" | grep -Eq '^[0-9a-f]{40}$'
git cat-file -e "$architecture_base^{commit}"
git diff --check "$architecture_base..HEAD"
task10_commit="$(git rev-parse --verify 'HEAD^')"
python3 architecture/scripts/validate_architecture.py --root . --adr-base-ref "$task10_commit" --adr-head-ref HEAD
git diff --quiet
git diff --cached --quiet
scoped_status="$(git status --short -- ARCHITECTURE.md architecture .github .gitignore services/funds-core/README.md docs/superpowers/plans/2026-08-30-account-identifiers-and-nip-inbound-implementation.md docs/superpowers/plans/2026-08-30-conventional-deposit-products-and-accrual-implementation.md docs/superpowers/plans/2026-08-30-non-interest-banking-products-implementation.md docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md)"
test -z "$scoped_status"
git status --short --branch
git update-ref -d "$base_ref" "$architecture_base"
test ! git show-ref --verify --quiet "$base_ref"
```

Expected results:

- Validator unit tests report zero failures and zero errors.
- Repository validation prints `architecture validation passed`.
- Stale verification is reported at a 90-day threshold and warnings alone exit zero.
- All five required Mermaid sources render successfully into a temporary directory; any additional governed sources also satisfy metadata, backlink, and render checks.
- The render script has executable mode `100755` and is directly invocable locally and in CI.
- `package-lock.json` resolves `@mermaid-js/mermaid-cli` exactly to `11.16.0`; no `node_modules`, generated diagram output, or SVG is tracked.
- The twelve implementation commits after the captured base contain no whitespace errors: eleven task-ending commits plus Task 8's separate independent-review evidence commit.
- The durable baseline ref resolves to a verified commit before its final range check and is deleted only after all other verification succeeds.
- There is no tracked working-tree or index diff, and scoped status contains no framework artifacts. Unrelated untracked/user state shown by the final status report is left untouched and does not fail acceptance.
- `ARCHITECTURE.md` is under 180 lines.
- All twelve arc42 files are `current` or `deprecated`; none is `proposed`.
- ADR identifiers are contiguous from `0001` through `0008`.
- Every foundational ADR names existing affected arc42 paths, and ADR/arc42 references are reciprocal in both directions.
- Every diagram's declared arc42 section contains a link back to that exact diagram source.
- Every proposal has reciprocal ADR and plan links; the three unimplemented plans link their proposals, while the implemented accounting-kernel plan links current arc42 sections and retrospective ADRs without a proposal backlink.
- The granular migration inventory covers every material heading under top-level sections `1` through `27`, has unique stable source keys, zero unresolved rows, and existing required destination anchors, exact source-key backlinks, and evidence paths.
- Every inventory row has non-empty exact material-block coverage and disposition rationale; each heading-relative block is covered exactly once, including after archive cutover.
- The independent review record names distinct reviewer and implementer identities, `APPROVED`, zero unresolved rows, an existing reviewed pre-cutover commit, and the exact inventory blob that remains current after cutover.
- The old comprehensive-design path is absent and its archived copy is explicitly non-authoritative.
- Pull-request `opened`, `synchronize`, `reopened`, `edited`, and `ready_for_review` events are all gated; PR whitespace and ADR immutability use the verified merge-base-to-head range, and an unavailable push base falls back to an empty-tree-versus-current-tree whitespace check plus parent-edge ADR immutability checks.
- No generated SVG or `node_modules` content is tracked.
