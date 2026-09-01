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
- While an ADR remains `Proposed`, its substantive sections and relationship/evidence sequences may be revised in place; the legal decision-status edges are `Proposed -> Proposed`, `Proposed -> Accepted`, and `Proposed -> Rejected`. Once an ADR has reached `Accepted`, its immutable sections remain immutable after transition to `Superseded` or `Deprecated`; material changes require a superseding ADR. `Superseded` and `Deprecated` retain the accepted-record protections. A `Rejected` child is frozen at the rejection edge: every later same-path `Rejected -> Rejected` edge must be byte-for-byte identical, and deletion or rename is permanently forbidden.
- Mermaid is the default diagram language; every diagram declares `CURRENT` or `PROPOSED`.
- Implementation plans describe delivery and are not architecture authority.
- The comprehensive design stays at its current path until every material section has a classified, linked disposition.
- Documentation/diagram tooling is development-only and adds no Java or Go runtime dependency.
- Python validation uses the standard library only; do not add PyYAML or another runtime parser.
- Pin `@mermaid-js/mermaid-cli` exactly to `11.16.0` and commit the npm lockfile.
- Local work without a pull request uses a path-bound full Git commit hash as implementation evidence; branch names are not evidence. Every local evidence list item has exactly one of these forms: `- HASH changed: repository/path; repository/path` or `- HASH snapshot: repository/path; repository/path`, where `HASH` is 40 lowercase hexadecimal characters and every path is repository-relative. `changed` means each named path exists in that commit and occurs in that commit's changed-path set; for a root commit that set is the diff against the empty tree, and for a merge commit it is the union of the diffs against every parent. `snapshot` means each named path exists in that commit and records observed state only, not that the commit introduced or changed it. Stable pull-request evidence uses `- https://github.com/OWNER/REPOSITORY/pull/NUMBER` and must match the normalized owner/repository from the current repository's `remote.origin.url` (accepting SSH or HTTPS origin syntax).
- Do not claim that infrastructure manifests are deployed or verified merely because their files exist.
- Every task ends with its focused validation and a commit before the next task starts.
- Before Task 1 changes any file, create the durable local baseline ref `refs/codex/architecture-docs-framework-base`; fail rather than overwrite it if it already exists. Before every cross-task range review, resolve that ref to a 40-lowercase-hex commit, verify the commit exists, and use `<resolved-base>..HEAD`, never `HEAD~N`. Delete only this exact ref after Final Verification succeeds.
- Markdown links resolve relative to the containing Markdown file after stripping query strings while preserving and validating fragments; the validator supports inline and CommonMark reference-style links, angle-bracket destinations, and backslash-escaped spaces. Before parsing links it masks fenced code, inline code, and HTML comments while preserving line numbers. A destination beginning with a valid URI scheme matching `[A-Za-z][A-Za-z0-9+.-]*:` is external and skipped, except that a Windows drive path matching `^[A-Za-z]:[\\/]` remains a local path.
- Link validation scans only these governed Markdown paths from the repository-root filesystem: `ARCHITECTURE.md`; `architecture/**/*.md`; `docs/superpowers/plans/*.md`; `docs/superpowers/specs/*.md`; `services/*/README.md`; `services/*/docs/**/*.md`; and `.github/pull_request_template.md` when present. It includes newly created and untracked files in those paths while pruning `.git/`, `.worktrees/`, `.claude/worktrees/`, `graft/`, every `node_modules/`, Maven/Gradle `target/` and `build/` output, and `architecture/diagrams/generated/`. Unrelated root-level, user, cache, and `.claude/` Markdown is outside the gate and may be reported informationally. Markdown fenced-code blocks, inline-code spans, and HTML comments are examples, not link-bearing prose, and are excluded before destinations are parsed.
- Unrelated untracked, modified, or staged user state, including `.claude/worktrees/`, is reported for awareness but never modified, staged, deleted, ignored, stashed, committed, or treated as a framework failure. Before the first write in every task/commit scope, define the exact task-owned path array shown again in that scope's commit block and run its fail-fast preflight. Each preflight passes that matching task-specific array after `--` to `git status --porcelain=v1 --untracked-files=all --ignored=matching` and aborts for coordination when any owned existing path has unstaged, staged, unmerged, untracked, or ignored state; it never stashes, resets, restores, deletes, cleans, or overwrites that state. A path that does not yet exist and has no status entry is clean. Non-owned dirty state remains allowed and is reported separately. Every task stages only its exact array with `git add --`; compares the sorted task-owned staged names with the sorted array using `git diff --cached --name-only --no-renames`; and commits only that array with Git's `--only` path mode. Disabling rename display makes the comparison account for both sides of a move. A move task lists both the old and new paths so the deletion and addition are committed. The scoped comparison ignores unrelated pre-existing staged paths, which remain untouched and excluded from every task commit. Every multi-command acceptance, baseline-ref, preflight, and commit-validation shell block begins with `set -euo pipefail`; therefore baseline-ref deletion occurs only after every preceding Final Verification command succeeds.

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

- [ ] **Step 1: Preflight the exact Task 1 write scope, then capture the implementation baseline**

Run this single fail-fast block before changing a file. It verifies both that the durable ref is absent and that the exact Task 1 paths are clean before creating the ref, so an owned-path overlap cannot leave a baseline ref behind:

```bash
set -euo pipefail
base_ref=refs/codex/architecture-docs-framework-base
if git show-ref --verify --quiet "$base_ref"; then
  echo "$base_ref already exists; inspect and remove it deliberately before restarting" >&2
  exit 1
fi
task1_paths=(
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
)
owned_state="$(git status --porcelain=v1 --untracked-files=all --ignored=matching -- "${task1_paths[@]}")"
if [[ -n "$owned_state" ]]; then
  printf '%s\n' 'Task 1 owned paths overlap existing work; stop and coordinate without stashing, resetting, restoring, deleting, or cleaning:' "$owned_state" >&2
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

Expected: the owned-path check emits no output, then the previously absent local ref resolves to the exact starting commit. Any owned-path output is a coordination stop and occurs before `git update-ref`; it is not permission to discard overlapping state. Keep the ref through Final Verification; no task may overwrite or delete it. The `task1_paths` array is identical to the commit array in Step 6.

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

def test_task_list_checkbox_and_ordinary_brackets_are_not_references(self):
    self.write("architecture/source.md", "- [ ] pending\n\nOrdinary [text] remains prose.\n")
    self.assertEqual([], validator.validate_links(self.root))

def test_shortcut_reference_resolves_only_with_a_definition(self):
    self.write("architecture/target.md", "# Stable section\n")
    self.write("architecture/source.md", "See [target].\n\n[target]: target.md#stable-section\n")
    self.assertEqual([], validator.validate_links(self.root))

def test_undefined_full_reference_link_fails(self):
    self.write("architecture/source.md", "[target][missing definition]\n")
    errors = validator.validate_links(self.root)
    self.assertTrue(any("undefined reference: missing definition" in error for error in errors))

def test_undefined_collapsed_reference_link_fails(self):
    self.write("architecture/source.md", "[target][]\n")
    errors = validator.validate_links(self.root)
    self.assertTrue(any("undefined reference: target" in error for error in errors))

def test_duplicate_reference_definitions_fail_deterministically(self):
    self.write("architecture/first.md", "# First\n")
    self.write("architecture/second.md", "# Second\n")
    self.write(
        "architecture/source.md",
        "[target][id]\n\n[id]: first.md\n[ID]: second.md\n",
    )
    errors = validator.validate_links(self.root)
    self.assertEqual(
        ["architecture/source.md:4: duplicate reference definition: id (first defined on line 3)"],
        errors,
    )

def test_broken_links_inside_fenced_and_inline_code_are_examples(self):
    self.write(
        "architecture/examples.md",
        "`[inline](missing-inline.md)`\n\n```markdown\n[fenced](missing-fenced.md)\n```\n",
    )
    self.assertEqual([], validator.validate_links(self.root))

def test_broken_links_inside_html_comments_are_examples(self):
    self.write(
        "architecture/examples.md",
        "<!--\n[commented](missing-commented.md)\n-->\n[real](target.md)\n",
    )
    self.write("architecture/target.md", "# Target\n")
    self.assertEqual([], validator.validate_links(self.root))

def test_destination_extraction_masks_code_and_comments_but_keeps_prose_links(self):
    text = "`[inline](missing-inline.md)`\n```md\n[fenced](missing-fenced.md)\n```\n<!--\n[commented](missing-commented.md)\n-->\n[real](real.md)\n"
    self.assertEqual(["real.md"], validator.extract_markdown_destinations(text))

def test_all_valid_uri_schemes_are_non_local(self):
    self.write(
        "architecture/source.md",
        "[ftp](ftp://example.test/file) [telephone](tel:+2348000000000) [custom](bank+ledger:v1/account)\n",
    )
    self.assertEqual([], validator.validate_links(self.root))

def test_windows_drive_paths_remain_local_paths(self):
    self.write("architecture/source.md", "[drive](C:/missing/local.md)\n")
    errors = validator.validate_links(self.root)
    self.assertTrue(any("C:/missing/local.md does not exist" in error for error in errors))

def test_link_scan_includes_new_untracked_governed_markdown(self):
    self.write("docs/superpowers/plans/new-task-not-added-to-git.md", "[missing](governed-missing.md)\n")
    errors = validator.validate_links(self.root)
    self.assertTrue(any("governed-missing.md does not exist" in error for error in errors))

def test_link_scan_ignores_unrelated_untracked_markdown(self):
    for path in ("NOTES.md", ".claude/scratch.md", "user-notes/draft.md"):
        self.write(path, "[ignored](missing.md)\n")
    self.assertEqual([], validator.validate_links(self.root))

def test_link_scan_prunes_build_worktree_and_cache_trees(self):
    for path in (
        ".git/objects/example.md",
        ".worktrees/feature/example.md",
        ".claude/worktrees/mirror/example.md",
        "graft/cache/example.md",
        "architecture/tooling/node_modules/pkg/example.md",
        "services/funds-core/target/site/example.md",
        "services/funds-core/docs/build/reports/example.md",
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
set -euo pipefail
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
- `links` walks only `ARCHITECTURE.md`, `architecture/**/*.md`, `docs/superpowers/plans/*.md`, `docs/superpowers/specs/*.md`, `services/*/README.md`, `services/*/docs/**/*.md`, and `.github/pull_request_template.md` when present, directly from the repository-root filesystem rather than `git ls-files`, so newly created and untracked governed files are included. Implement a single `iter_governed_markdown(root: Path) -> Iterator[Path]` used by link validation and later Markdown-wide checks. Prune the exact repository-relative roots `.git/`, `.worktrees/`, `.claude/worktrees/`, `graft/`, and `architecture/diagrams/generated/`, plus any directory component named `node_modules`, `target`, or `build`; do not follow symlinked directories. Markdown outside the explicit governed patterns, including unrelated root/user Markdown and `.claude/`, cannot fail the gate; an optional informational report must remain non-blocking.
- Before extracting Markdown links, mask CommonMark fenced code blocks opened by at least three backticks or tildes, inline code spans delimited by matching backtick runs, and HTML comments from `<!--` through `-->`, including multiline comments. Preserve every line break while masking so diagnostics retain correct locations. Parse case-insensitive reference definitions first and exclude definition lines from link-use extraction. Parse inline links next. Parse a full `[text][id]` or collapsed `[text][]` reference as a link and reject it when its normalized label has no definition. Parse shortcut `[text]` as a link only when a matching normalized definition exists; otherwise it is ordinary CommonMark text, including task-list `[ ]`. Reject duplicate normalized definitions deterministically at the later definition line even when no link uses the label. Unwrap angle-bracket destinations, convert Markdown backslash-escaped spaces to literal spaces, strip a query component, and retain a decoded fragment. Treat any destination beginning with a valid URI scheme matching `[A-Za-z][A-Za-z0-9+.-]*:` as external/non-local and skip it, except that `^[A-Za-z]:[\\/]` is a Windows drive path and must continue through local-path resolution.
- Resolve file paths against the containing file's parent. For pure fragments validate the containing file; for cross-file fragments validate the resolved Markdown file. Build anchors from explicit HTML `id` attributes and deterministic GitHub-style heading slugs: lowercase, remove formatting and punctuation other than hyphens/underscores, convert spaces to hyphens, and append `-1`, `-2`, and later suffixes to duplicate base slugs in document order. A missing target file and a missing fragment are separate actionable errors.
- Sort errors by path and message so local and CI output is deterministic.
- Print each error to stderr and return `1`; print `architecture validation passed` and return `0` when clean.

- [ ] **Step 5: Run validator unit tests**

Run:

```bash
set -euo pipefail
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: all generic primitive tests pass without third-party packages. No arc42, ADR, proposal, diagram, migration, archive, PR-body, workflow, or staleness contract is implemented in this task.

- [ ] **Step 6: Commit the validator**

```bash
set -euo pipefail
task1_paths=(
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
)
git add -- "${task1_paths[@]}"
expected_staged="$(mktemp)"
actual_staged="$(mktemp)"
trap 'rm -f "$expected_staged" "$actual_staged"' EXIT
printf '%s\n' "${task1_paths[@]}" | LC_ALL=C sort >"$expected_staged"
git diff --cached --name-only --no-renames -- "${task1_paths[@]}" | LC_ALL=C sort >"$actual_staged"
diff -u "$expected_staged" "$actual_staged"
git commit --only -m "test: add architecture documentation validator" -- "${task1_paths[@]}"
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

- [ ] **Step 0: Preflight the exact Task 2 write scope**

```bash
set -euo pipefail
task2_paths=(
  ARCHITECTURE.md
  architecture/README.md
  architecture/adr/README.md
  architecture/adr/template.md
  architecture/archive/proposals/README.md
  architecture/diagrams/README.md
  architecture/proposals/README.md
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
)
owned_state="$(git status --porcelain=v1 --untracked-files=all --ignored=matching -- "${task2_paths[@]}")"
if [[ -n "$owned_state" ]]; then
  printf '%s\n' 'Task 2 owned paths overlap existing work; stop and coordinate without altering it:' "$owned_state" >&2
  exit 1
fi
```

Expected: no output. Run this before Step 1 edits; non-owned state is allowed. This exact array is repeated unchanged in Step 7.

- [ ] **Step 1: Add failing governance-structure and root-size tests**

Add `test_required_governance_files` to the validator tests. It creates a temporary empty root, calls the new `validate_structure`, and asserts the error list names all seven files above. Add `test_root_architecture_must_be_fewer_than_180_lines`, with a valid 179-line fixture and an invalid 180-line fixture, and assert the latter reports `ARCHITECTURE.md must contain fewer than 180 lines`.

- [ ] **Step 2: Run both new tests and verify failure**

Run:

```bash
set -euo pipefail
python3 -m unittest \
  architecture.scripts.tests.test_validate_architecture.ValidatorTest.test_required_governance_files \
  architecture.scripts.tests.test_validate_architecture.ValidatorTest.test_root_architecture_must_be_fewer_than_180_lines \
  -v
```

Expected: both tests run and fail before production code changes because `validate_structure` and its root-size behavior do not exist.

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
- `architecture/adr/README.md`: statuses, implementation statuses, ADR threshold, permanently immutable post-acceptance rationale, supersession, retrospective ADR label, and the exact path-bound evidence forms `- HASH changed: repository/path; repository/path` and `- HASH snapshot: repository/path; repository/path`. Document the complete lifecycle: `Proposed -> Proposed`, `Accepted`, or `Rejected`; `Accepted -> Accepted`, `Superseded`, or `Deprecated`; terminal `Superseded -> Superseded` and `Deprecated -> Deprecated` retain accepted-record protections; and `Rejected -> Rejected` is legal only when the complete file is byte-for-byte identical. State explicitly that substantive sections and relationship/evidence fields may be freely revised while the parent status is `Proposed`, including on a `Proposed -> Proposed` edge. State that Accepted/Superseded/Deprecated records and Rejected records cannot be deleted or renamed; after rejection no field, prose, relationship, implementation status, compliance result, or evidence item may be appended, removed, or rewritten. Explain that `snapshot` proves observed tree state rather than introduction, and that stable GitHub pull-request URLs must match the normalized current `origin` owner/repository.
- `architecture/adr/template.md`: include `Retrospective: No`, `Related proposals: None`, and `Related implementation plans: None` in addition to every field and heading specified by the design. Its implementation-evidence instructions use only the exact local `changed`/`snapshot` forms or a stable same-repository GitHub pull-request URL.
- `architecture/proposals/README.md`: statuses, the rule that `approved` does not mean `current`, and the permanent governed-proposal registry convention: stable identity anchors stay in this README while each mutable pointer names the record's sole active or archive location.
- `architecture/archive/proposals/README.md`: terminal `implemented`, `rejected`, and `superseded` records move here under the same basename; document status-specific `implementation_status` and `replacement` rules plus mandatory closure evidence, ADRs, and plan history. A registry pointer changes during the move, while accepted ADR and plan backlinks continue to use the stable registry anchor.
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
set -euo pipefail
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
python3 architecture/scripts/validate_architecture.py --root . --checks structure,links
```

Expected: unit tests pass; the repository structure check passes for the seven governance files and the root-size limit; link validation passes because not-yet-created arc42 paths are plain code until Task 3.

- [ ] **Step 7: Commit governance**

```bash
set -euo pipefail
task2_paths=(
  ARCHITECTURE.md
  architecture/README.md
  architecture/adr/README.md
  architecture/adr/template.md
  architecture/archive/proposals/README.md
  architecture/diagrams/README.md
  architecture/proposals/README.md
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
)
git add -- "${task2_paths[@]}"
expected_staged="$(mktemp)"
actual_staged="$(mktemp)"
trap 'rm -f "$expected_staged" "$actual_staged"' EXIT
printf '%s\n' "${task2_paths[@]}" | LC_ALL=C sort >"$expected_staged"
git diff --cached --name-only --no-renames -- "${task2_paths[@]}" | LC_ALL=C sort >"$actual_staged"
diff -u "$expected_staged" "$actual_staged"
git commit --only -m "docs: establish architecture governance" -- "${task2_paths[@]}"
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

- [ ] **Step 0: Preflight the exact Task 3 write scope**

```bash
set -euo pipefail
task3_paths=(
  ARCHITECTURE.md
  architecture/arc42/01-introduction-and-goals.md
  architecture/arc42/02-constraints.md
  architecture/arc42/03-context-and-scope.md
  architecture/arc42/04-solution-strategy.md
  architecture/arc42/05-building-block-view.md
  architecture/arc42/06-runtime-view.md
  architecture/arc42/07-deployment-view.md
  architecture/arc42/08-crosscutting-concepts.md
  architecture/arc42/09-decisions.md
  architecture/arc42/10-quality-requirements.md
  architecture/arc42/11-risks-and-technical-debt.md
  architecture/arc42/12-glossary.md
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
)
owned_state="$(git status --porcelain=v1 --untracked-files=all --ignored=matching -- "${task3_paths[@]}")"
if [[ -n "$owned_state" ]]; then
  printf '%s\n' 'Task 3 owned paths overlap existing work; stop and coordinate without altering it:' "$owned_state" >&2
  exit 1
fi
```

Expected: no output. Run this before Step 1 edits; non-owned state is allowed. This exact array is repeated unchanged in Step 8.

- [ ] **Step 1: Add failing current-state lifecycle and metadata tests**

Add tests that call new `validate_metadata` and require exactly the twelve filenames, reject `status: proposed` under `arc42/`, reject absent or empty owners, reject a `code_refs` path that does not exist, and accept only ISO `YYYY-MM-DD` `last_verified` values. Add a deprecated arc42 fixture whose `replacement` field is an existing local Markdown link and negative fixtures for a missing, empty, non-link, missing-target, or self-referential replacement. Add only the placement primitive needed before proposals are bootstrapped: active `architecture/proposals/` rejects terminal statuses `implemented`, `rejected`, and `superseded`; archive proposal paths accept only those terminal statuses. Full archive terminal metadata, the six governed proposal identities, exactly-one-location enforcement, and missing/duplicate lifecycle checks belong to Task 7 so Tasks 3 through 6 do not permanently require proposal files that have not yet been created. Before production changes these tests must fail because `validate_metadata` is absent.

- [ ] **Step 2: Run focused tests and verify failure**

Run:

```bash
set -euo pipefail
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: fail with a missing `validate_metadata` behavior or missing twelve-file diagnostics; record the focused failure before implementation.

- [ ] **Step 3: Implement the arc42 metadata contract**

Add `metadata` to `CHECKS` and `VALIDATORS` and implement exactly the arc42 filename, required-field, status, date, code-reference, and deprecated-replacement rules from Step 1. Inspect proposal placement now: reject `implemented`, `rejected`, and `superseded` below active `architecture/proposals/`, and reject non-terminal statuses below `architecture/archive/proposals/`. Do not require any particular proposal basename or active proposal count yet. Full proposal metadata, archive terminal traceability, governed-identity lifecycle, bootstrap completeness, and bidirectional traceability remain owned by Task 7.

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
set -euo pipefail
python3 architecture/scripts/validate_architecture.py --root . --checks metadata,links
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: metadata, links, and unit tests pass. The seven-file/root-size `structure` check has been available and passing since Task 2; Task 4 introduces the separate `migration` check for the inventory contract.

- [ ] **Step 8: Commit current-state baseline**

```bash
set -euo pipefail
task3_paths=(
  ARCHITECTURE.md
  architecture/arc42/01-introduction-and-goals.md
  architecture/arc42/02-constraints.md
  architecture/arc42/03-context-and-scope.md
  architecture/arc42/04-solution-strategy.md
  architecture/arc42/05-building-block-view.md
  architecture/arc42/06-runtime-view.md
  architecture/arc42/07-deployment-view.md
  architecture/arc42/08-crosscutting-concepts.md
  architecture/arc42/09-decisions.md
  architecture/arc42/10-quality-requirements.md
  architecture/arc42/11-risks-and-technical-debt.md
  architecture/arc42/12-glossary.md
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
)
git add -- "${task3_paths[@]}"
expected_staged="$(mktemp)"
actual_staged="$(mktemp)"
trap 'rm -f "$expected_staged" "$actual_staged"' EXIT
printf '%s\n' "${task3_paths[@]}" | LC_ALL=C sort >"$expected_staged"
git diff --cached --name-only --no-renames -- "${task3_paths[@]}" | LC_ALL=C sort >"$actual_staged"
diff -u "$expected_staged" "$actual_staged"
git commit --only -m "docs: add current-state arc42 baseline" -- "${task3_paths[@]}"
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

- [ ] **Step 0: Preflight the exact Task 4 write scope**

```bash
set -euo pipefail
task4_paths=(
  architecture/README.md
  architecture/adr/README.md
  architecture/arc42/01-introduction-and-goals.md
  architecture/arc42/02-constraints.md
  architecture/arc42/03-context-and-scope.md
  architecture/arc42/04-solution-strategy.md
  architecture/arc42/05-building-block-view.md
  architecture/arc42/06-runtime-view.md
  architecture/arc42/07-deployment-view.md
  architecture/arc42/08-crosscutting-concepts.md
  architecture/arc42/09-decisions.md
  architecture/arc42/10-quality-requirements.md
  architecture/arc42/11-risks-and-technical-debt.md
  architecture/arc42/12-glossary.md
  architecture/archive/comprehensive-design-migration-inventory.md
  architecture/proposals/README.md
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
  docs/superpowers/plans/2026-08-30-account-identifiers-and-nip-inbound-implementation.md
  docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md
  docs/superpowers/plans/2026-08-30-conventional-deposit-products-and-accrual-implementation.md
  docs/superpowers/plans/2026-08-30-non-interest-banking-products-implementation.md
  services/funds-core/README.md
)
owned_state="$(git status --porcelain=v1 --untracked-files=all --ignored=matching -- "${task4_paths[@]}")"
if [[ -n "$owned_state" ]]; then
  printf '%s\n' 'Task 4 owned paths overlap existing work; stop and coordinate without altering it:' "$owned_state" >&2
  exit 1
fi
```

Expected: no output. Run this before Step 1 edits; non-owned state is allowed. This exact array is repeated unchanged in Step 6.

- [ ] **Step 1: Add failing granular migration-inventory tests**

Add `migration` to the planned `CHECKS`/`VALIDATORS` contract, but write tests before changing production code. Test all of these behaviors:

- A complete fixture contains at least one unique source key rooted at each top-level integer `01` through `27`, includes multiple rows for mixed section `08`, and has exactly one `unresolved` row; assert one `unresolved migration row` error by presence, not total error cardinality.
- Missing top-level root `17`, a duplicate full source key, a malformed source key, an unsupported disposition, and an unsupported resolution each produce focused errors.
- A non-`historical-only` row whose destination does not exist fails; a `current` row whose evidence path does not exist fails.
- Every non-`historical-only` covered block must map exactly once to a stable `repository/path.md#explicit-anchor` destination. An empty destination, missing anchor, duplicate or omitted block mapping, destination mapped from the wrong source key, or destination section lacking the exact `<!-- migration-source: <source-key> -->` marker fails. A fixture with two destinations proves each block maps to its exact destination rather than merely proving both files exist.
- Globally inventory every active full-line `<!-- migration-source: SOURCE_KEY -->` occurrence in all Markdown returned by `iter_governed_markdown`, after masking fenced and inline code with the same line-preserving Markdown code masker used by link validation. Code examples of marker syntax are not active markers. Require the occurrence multiset, represented as exact `(repository/path.md, explicit-anchor, SOURCE_KEY)` triples, to equal the deduplicated active non-historical `Destination map` triples exactly. Add independent negative fixtures for an orphan/extra marker, a duplicate marker at the correct anchor, a marker under the wrong anchor in the correct file, and a provisional governance marker left behind after its inventory row is remapped to a final destination. No governed migration marker may exist without one active destination tuple, and no active tuple may lack its one marker occurrence.
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
set -euo pipefail
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

Allowed `Disposition` values are exactly `current`, `proposal`, `decision`, `service-detail`, `plan-detail`, and `historical-only`. Allowed `Resolution` values are exactly `unresolved` and `resolved`. `Destination map` contains semicolon-separated `BLOCK=repository/path.md#explicit-anchor` entries and maps every `Covered blocks` ordinal exactly once; this exact block mapping removes ambiguity when a row has multiple destinations. Every non-historical destination file and fragment must exist, and the destination section selected by that fragment must contain the exact explicit backlink `<!-- migration-source: SOURCE_KEY -->`. Use `None` only for a `historical-only` destination. Semicolon-separated `Evidence` entries must exist for every `current` row and may be `None` for other dispositions. No row may resolve merely by pointing to the comprehensive source document.

Treat the inventory as the sole source of truth for migration markers. Deduplicate its non-historical block mappings into expected `(path, anchor, source-key)` triples. After masking fenced and inline code, globally scan every governed Markdown file for active full-line marker comments, associate each marker with the explicit HTML anchor whose contiguous marker block immediately follows it, and require multiset equality with the expected triples. Therefore a missing, orphan, extra, duplicate, wrong-file, wrong-anchor, wrong-key, or superseded provisional marker is blocking even when all active destinations themselves validate.

At initial inventory commit, use existing arc42, plan, service-document, source, test, and migration paths. Rows requiring not-yet-created ADRs or proposals remain `unresolved` and point to the already-existing `architecture/adr/README.md` or `architecture/proposals/README.md` governance destination until Tasks 5 and 7 replace that destination with the exact created artifact. This preserves path validity without falsely claiming extraction is complete.

For every maintained destination chosen in this task, add a stable explicit HTML anchor immediately before the narrowest destination section containing the extracted material and place `<!-- migration-source: SOURCE_KEY -->` immediately after the anchor. When several source keys map to the same section, add one exact marker line per key. Plan-detail and service-detail rows receive the same anchor/marker treatment in their existing Markdown destinations. Do not use a general file-level anchor when a more exact subsection owns the material.

- [ ] **Step 4: Implement the migration contract**

Add `migration` to `CHECKS` and `VALIDATORS` and implement `validate_migration_inventory(root: Path) -> list[str]` with the exact schema, source-key grammar, allowed values, top-level and subsection coverage, uniqueness, material-block tokenization and exact-once coverage, non-empty per-row rationale, historical-only rationale, contiguous mixed-segment suffix, exact destination-block mapping, destination-file and anchor existence, exact source-marker backlink, current-evidence, and unresolved-row rules above. Parse numbered `##`/`###`/`####` headings from the comprehensive source, plus unnumbered `#### Example A` through `#### Example J` under section 13.8, and require each derived material heading key to have either one exact inventory key or one or more segment keys with that key plus `::NN`; reject inventory keys that do not map back to a source heading. Table parsing must report malformed rows rather than silently skipping them. Emit each unresolved row exactly as `architecture/archive/comprehensive-design-migration-inventory.md: unresolved migration row <SOURCE_KEY>` so interim gates can compare the complete diagnostic set mechanically.

- [ ] **Step 5: Validate the inventory's deliberate interim state**

First run the full unit-test suite as the green half of the Task 4 red/green cycle. Only after it passes, run repository migration validation separately and prove that its complete diagnostic set is exactly the deliberately deferred decision/proposal rows:

```bash
set -euo pipefail
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
migration_report="$(mktemp)"
expected_report="$(mktemp)"
trap 'rm -f "$migration_report" "$expected_report"' EXIT
if python3 architecture/scripts/validate_architecture.py --root . --checks migration 2>"$migration_report"; then
  echo "migration unexpectedly has no deliberately deferred ADR/proposal rows" >&2
  exit 1
fi
awk -F'|' '
  function trim(value) { gsub(/^[[:space:]]+|[[:space:]]+$/, "", value); return value }
  /^\|/ {
    disposition = trim($5)
    resolution = trim($9)
    if ((disposition == "decision" || disposition == "proposal") && resolution == "unresolved") {
      print "architecture/archive/comprehensive-design-migration-inventory.md: unresolved migration row " trim($2)
    }
  }
' architecture/archive/comprehensive-design-migration-inventory.md | LC_ALL=C sort >"$expected_report"
test -s "$expected_report"
LC_ALL=C sort -o "$migration_report" "$migration_report"
diff -u "$expected_report" "$migration_report"
```

Expected: the full unit suite passes after the focused Step 2 red failure and Step 4 implementation. The separate repository migration command then fails only with the exact `unresolved migration row` diagnostics for ADR/proposal extraction scheduled in Tasks 5 and 7; `diff` is silent, proving all schema, coverage, uniqueness, destination, marker, rationale, and evidence checks are green.

- [ ] **Step 6: Commit the classification inventory**

```bash
set -euo pipefail
task4_paths=(
  architecture/README.md
  architecture/adr/README.md
  architecture/arc42/01-introduction-and-goals.md
  architecture/arc42/02-constraints.md
  architecture/arc42/03-context-and-scope.md
  architecture/arc42/04-solution-strategy.md
  architecture/arc42/05-building-block-view.md
  architecture/arc42/06-runtime-view.md
  architecture/arc42/07-deployment-view.md
  architecture/arc42/08-crosscutting-concepts.md
  architecture/arc42/09-decisions.md
  architecture/arc42/10-quality-requirements.md
  architecture/arc42/11-risks-and-technical-debt.md
  architecture/arc42/12-glossary.md
  architecture/archive/comprehensive-design-migration-inventory.md
  architecture/proposals/README.md
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
  docs/superpowers/plans/2026-08-30-account-identifiers-and-nip-inbound-implementation.md
  docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md
  docs/superpowers/plans/2026-08-30-conventional-deposit-products-and-accrual-implementation.md
  docs/superpowers/plans/2026-08-30-non-interest-banking-products-implementation.md
  services/funds-core/README.md
)
git add -- "${task4_paths[@]}"
expected_staged="$(mktemp)"
actual_staged="$(mktemp)"
trap 'rm -f "$expected_staged" "$actual_staged"' EXIT
printf '%s\n' "${task4_paths[@]}" | LC_ALL=C sort >"$expected_staged"
git diff --cached --name-only --no-renames -- "${task4_paths[@]}" | LC_ALL=C sort >"$actual_staged"
diff -u "$expected_staged" "$actual_staged"
git commit --only -m "docs: inventory comprehensive architecture migration" -- "${task4_paths[@]}"
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
- Modify: `architecture/adr/README.md`
- Modify: all twelve files under `architecture/arc42/` (ADR index and `related_adrs` metadata)
- Modify: `architecture/archive/comprehensive-design-migration-inventory.md`
- Modify: `architecture/scripts/validate_architecture.py`
- Modify: `architecture/scripts/tests/test_validate_architecture.py`
- Modify: `docs/superpowers/plans/2026-09-01-architecture-documentation-and-adr-framework-implementation.md` (add only the ADR-0001 governing-link header metadata)

**Interfaces:**
- Consumes: approved ADR template and implementation evidence.
- Produces: a contiguous decision history and stable IDs used by diagrams, proposals, and arc42 metadata.
- Produces: `validate_accepted_adr_immutability(root: Path, base_ref: str, head_ref: str | None = None) -> list[str]`; `head_ref=None` compares the base commit with the current working tree, while a supplied head compares two Git trees.
- Produces: `validate_accepted_adr_edge_range(root: Path, base_ref: str, head_ref: str) -> list[str]`, which enumerates commits in `base..head` oldest-first/topologically and validates every parent-to-child edge for every child in that range, including every parent of merge commits.
- CLI: `--adr-base-ref REF [--adr-head-ref REF]` retains the endpoint/current-working-tree check; `--adr-edge-base-ref REF --adr-edge-head-ref REF` performs the commit-edge range check. These git-aware checks are additive to ordinary repository checks.

- [ ] **Step 0: Preflight the exact Task 5 write scope**

```bash
set -euo pipefail
task5_paths=(
  architecture/adr/0001-manage-architecture-as-versioned-code.md
  architecture/adr/0002-centralize-financial-invariants-in-funds-core.md
  architecture/adr/0003-use-signed-integer-minor-units.md
  architecture/adr/0004-use-postgresql-as-the-authoritative-ledger.md
  architecture/adr/0005-use-immutable-journals-and-additive-corrections.md
  architecture/adr/0006-couple-idempotency-and-outbox-to-ledger-commit.md
  architecture/adr/0007-separate-ledger-identity-from-account-addresses.md
  architecture/adr/0008-target-an-eight-gib-single-vm-evidence-suite.md
  architecture/adr/README.md
  architecture/arc42/01-introduction-and-goals.md
  architecture/arc42/02-constraints.md
  architecture/arc42/03-context-and-scope.md
  architecture/arc42/04-solution-strategy.md
  architecture/arc42/05-building-block-view.md
  architecture/arc42/06-runtime-view.md
  architecture/arc42/07-deployment-view.md
  architecture/arc42/08-crosscutting-concepts.md
  architecture/arc42/09-decisions.md
  architecture/arc42/10-quality-requirements.md
  architecture/arc42/11-risks-and-technical-debt.md
  architecture/arc42/12-glossary.md
  architecture/archive/comprehensive-design-migration-inventory.md
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
  docs/superpowers/plans/2026-09-01-architecture-documentation-and-adr-framework-implementation.md
)
owned_state="$(git status --porcelain=v1 --untracked-files=all --ignored=matching -- "${task5_paths[@]}")"
if [[ -n "$owned_state" ]]; then
  printf '%s\n' 'Task 5 owned paths overlap existing work; stop and coordinate without altering it:' "$owned_state" >&2
  exit 1
fi
```

Expected: no output. Run this before Step 1 edits; non-owned state is allowed. This exact array is repeated unchanged in Step 7.

- [ ] **Step 1: Add failing ADR contract and reciprocal-traceability tests**

Write tests against a new `validate_adrs` behavior before implementing it. Test contiguous numbering, filename/title agreement, required headings, valid statuses, relationship fields containing either a non-empty value or the literal `None`, retrospective marking, separation of decision from implementation status, evidence syntax, and substantive content. The substantive headings are exactly `## Context`, `## Decision drivers`, `## Considered options`, `## Decision`, `## Consequences`, `### Positive`, `### Negative`, `### Risks`, `## Compliance and verification`, and `## Implementation evidence`; each must contain non-whitespace prose, a list item, or a link before the next heading of the same or higher level. Add one negative test per empty substantive heading. Test that the body of `## Implementation evidence` may be exactly `None` only with `Not started` or `Not applicable`. `Partial` and `Complete` require at least one exact list entry in one of these forms: `- HASH changed: repository/path; repository/path`, `- HASH snapshot: repository/path; repository/path`, or `- https://github.com/OWNER/REPOSITORY/pull/NUMBER`. Require at least one path-bound local entry when local hashes are used; a bare hash and an independently listed path never satisfy the contract.

Also add negative reciprocal fixtures proving all of these fail independently: a foundational ADR `0001` through `0008` with `Related architecture sections: None`; an ADR architecture-section link whose repository-relative path does not exist; an ADR linking an existing arc42 file whose `related_adrs` omits that ADR ID; an arc42 `related_adrs` ID with no matching ADR; and an arc42 ADR ID whose ADR does not link back to that exact section. Add a positive fixture with two ADR/arc42 pairs so the check cannot pass by comparing only aggregate sets. Parse `Related implementation plans` as an ordered relationship sequence and implement full direct-link reciprocity: every Markdown link from a governed implementation plan that resolves to an `architecture/adr/NNNN-*.md` file must have an exact backlink from that ADR's `Related implementation plans`, and every ADR entry there must resolve to a governed plan that directly links back to that exact ADR. Add a positive fixture with two plans and two ADRs whose links cross differently so aggregate-set comparison cannot pass. Add independent negative fixtures for a missing plan target, a plan-to-ADR link without an ADR backlink, an ADR-to-plan link without a plan backlink, a backlink naming a different ADR, and a direct ADR target that does not exist. Preserve the special ADR-0001 rule in addition to the generic rule: its required reciprocal pair is ADR-0001 `Related implementation plans: [Architecture Documentation and ADR Framework Implementation Plan](../../docs/superpowers/plans/2026-09-01-architecture-documentation-and-adr-framework-implementation.md)` and the plan header `**Governing ADR:** [ADR-0001: Manage architecture as versioned code](../../../architecture/adr/0001-manage-architecture-as-versioned-code.md)`; the framework plan must have exactly that one governing-ADR header link.

Add repository-backed lifecycle/evidence fixtures in temporary Git repositories:

- a local 40-lowercase-hex evidence hash resolves with the equivalent of `git cat-file -e "$hash^{commit}"`; every paired path passes `git cat-file -e "$hash:$path"`; `changed` requires every paired path in the union of the commit's per-parent changed-path sets, using the empty tree for a root commit; `snapshot` requires tree existence but does not claim introduction. Add independent negative fixtures for a syntactically valid nonexistent hash, a path missing from the commit tree, and an unrelated hash/path pair where the path exists in the tree but is absent from that commit's changed-path set. Add a positive snapshot fixture for an unchanged path.
- stable GitHub pull-request evidence is valid only when its owner/repository equals the normalized current repository `remote.origin.url`; cover SSH and HTTPS origin normalization, reject a syntactically valid URL for a different repository, and reject PR evidence when origin cannot be normalized.
- `Supersedes` and `Superseded by` reject a missing ADR target, self-reference, non-reciprocal edge, incompatible statuses, and a cycle; accept a reciprocal `Accepted` successor that supersedes a `Superseded` predecessor;
- an ADR whose parent status is `Accepted`, `Superseded`, or `Deprecated` rejects mutations to `Context`, `Decision drivers`, `Considered options`, `Decision`, or the complete `Consequences` subtree including `Positive`, `Negative`, and `Risks`; separate fixtures first transition an Accepted ADR to `Superseded` and to `Deprecated`, then mutate each terminal record in a later commit and require the later edge to fail;
- enforce the complete decision-status edge matrix: `Proposed -> Proposed`, `Proposed -> Accepted`, or `Proposed -> Rejected`; `Accepted -> Accepted`, `Accepted -> Superseded`, or `Accepted -> Deprecated`; and terminal `Superseded -> Superseded`, `Deprecated -> Deprecated`, or `Rejected -> Rejected` only. Add distinct positive endpoint and edge-range fixtures for an unchanged `Proposed -> Proposed` edge and for a `Proposed -> Proposed` edge that revises Context, Decision drivers, Considered options, Decision, the Consequences subtree, relationship sequences, compliance text, evidence, and implementation status. Add focused rejection fixtures for `Superseded -> Accepted`, `Deprecated -> Accepted`, `Rejected -> Accepted`, every other terminal reversal/lateral change, and any pair absent from this matrix. Enforce implementation status monotonically as `Not started -> Partial -> Complete` once the parent is Accepted/Superseded/Deprecated; while the parent remains Proposed it may be revised to any allowed implementation status. `Not applicable` is fixed after acceptance and cannot transition to or from another implementation status;
- relationship/evidence sequences are append-only whenever the parent status is `Accepted`, `Superseded`, or `Deprecated`: legal suffix additions to `Related pull requests`, `Related commits`, `Related architecture sections`, `Related proposals`, `Related implementation plans`, `Supersedes`, `Superseded by`, `Compliance and verification`, and `Implementation evidence` pass, while rewriting, removal, insertion before an existing item, or reordering fails. Add post-supersession and post-deprecation fixtures for both a legal suffix append and an illegal rewrite. The positive Accepted fixture must combine `Implementation status: Partial -> Complete` with appended command/result entries under `Compliance and verification`, an appended `changed` evidence entry, and an appended path-bound `snapshot` entry for unchanged files; this exact legal completion transition must pass;
- an ADR present with `Accepted`, `Superseded`, or `Deprecated` status in an edge parent may not be absent at the child path. Add independent accepted-record deletion and rename fixtures; treat a rename as deletion of the protected original plus introduction of a different path and reject it even when the Markdown contents and ADR identifier are unchanged;
- an ADR present with `Rejected` status in an edge parent is a permanent same-path record: require the entire child file bytes to equal the parent bytes exactly, with no normalization and no append-only exception for relationships, compliance results, or implementation evidence. Add independent fixtures for a rationale/substantive mutation, relationship mutation, evidence append, implementation-status mutation, deletion, and byte-identical rename; every case except an unchanged same-path file fails, and the rename is diagnosed against the protected original path;
- an ADR introduced as `Accepted` is exempt only on its introduction edge, then a rationale mutation in the next commit fails;
- an ADR introduced as `Proposed`, accepted in a later commit, and mutated in a third commit fails on the accepted-to-mutated edge even though an endpoint-only comparison from the range base would not protect it;
- an ADR that is `Proposed` at the range base, accepted in the first ranged commit, and mutated in the next commit fails;
- a merge fixture mutates an Accepted ADR relative to only the second parent and proves all-parent merge semantics catches it; first-parent-only behavior is forbidden.

Build each range fixture with exact commits in a temporary repository and invoke `validate_accepted_adr_edge_range(root, range_base, range_head)`. Assert the returned diagnostic names both exact parent and child hashes and the ADR path. Also assert the Proposed-at-base endpoint call returns no immutability error while the edge-range call rejects the later mutation; this proves why endpoint validation remains supplementary rather than sufficient.

Use test helpers `write_valid_adr(status: str, context: str) -> None` and `commit_all(message: str) -> str`, where `commit_all` returns the verified 40-hex commit. Construct these exact fixtures:

| Test | Committed history after `range_base` | Required result |
|---|---|---|
| `test_edge_range_protects_newly_accepted_adr_after_introduction` | `Proposed/context-v1` -> `Accepted/context-v1` -> `Accepted/context-v2` | Fail only on the second-to-third edge and name `context-v2` as an immutable-section change. |
| `test_edge_range_protects_proposed_adr_accepted_after_base` | Base already contains `Proposed/context-v1`; range commits `Accepted/context-v1` -> `Accepted/context-v2` | Endpoint base/head comparison has no accepted-base error; edge range fails on the first-to-second ranged-commit edge. |
| `test_introduction_exemption_ends_after_accepted_child` | `Accepted/context-v1` -> `Accepted/context-v2` | Introduction edge passes; second edge fails. |
| `test_superseded_and_deprecated_records_remain_protected` | `Accepted/context-v1` -> terminal/context-v1 -> same-terminal/context-v2, once for `Superseded` and once for `Deprecated` | Transition edge passes; the following immutable-section mutation fails for each terminal status. |
| `test_terminal_statuses_cannot_reverse` | One fixture each for `Superseded -> Accepted`, `Deprecated -> Accepted`, and `Rejected -> Accepted` | Every edge fails with the forbidden status pair. |
| `test_accepted_record_cannot_be_deleted_or_renamed` | Base contains `Accepted/context-v1`; one branch deletes it, another moves it to a different ADR path without changing bytes | Both edges fail against the protected original path; the move is reported as a forbidden deletion/rename, not accepted as introduction. |
| `test_proposed_records_may_remain_unchanged_or_be_revised` | One range contains unchanged `Proposed/context-v1 -> Proposed/context-v1`; another revises substantive prose, relationships/evidence, and implementation status while remaining `Proposed` | Both endpoint and edge-range validation pass for both ranges. |
| `test_rejected_records_are_permanent_same_path_bytes` | `Proposed/context-v1 -> Rejected/context-final`, followed independently by same-path unchanged, rationale mutation, relationship/evidence mutation, deletion, and byte-identical rename children | Only the same-path byte-identical child passes; every mutation/deletion/rename fails against the Rejected parent path. |
| `test_merge_checks_every_parent` | Base contains `Accepted/context-v1`; first-parent branch makes only an unrelated file commit; second-parent branch commits `Accepted/context-v2`; merge with `--no-commit` restores `context-v1` before committing | Base/head endpoint and first-parent/merge comparison pass; second-parent/merge comparison and the edge range fail with the second-parent and merge hashes. |

For the first fixture, use the commit immediately before ADR introduction as `range_base`. For the merge fixture, retain both parent hashes from `git show -s --format=%P <merge>` and assert the merge has exactly two parents in first-parent, second-parent order before validating it.

Use one legal-append fixture and separate mutation fixtures so a single unrelated error cannot mask the behavior under test.

- [ ] **Step 2: Run ADR tests and verify failure**

Run:

```bash
set -euo pipefail
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: fail because `validate_adrs` and the `adrs` registry entry do not exist.

- [ ] **Step 3: Implement the ADR contract, lifecycle graph, evidence existence, and accepted-record immutability**

Add `adrs` to `CHECKS` and `VALIDATORS`. Implement the exact numbering, filename/title, field, lifecycle-value, substantive-section, relationship-`None`, retrospective, implementation-evidence, reciprocal architecture-link, reciprocal implementation-plan-link, and evidence-binding rules from Step 1. Parse only the three exact evidence list-item forms. For every local entry, run Git through `subprocess` argument vectors, verify the commit with `git -C <root> cat-file -e <hash>^{commit}`, and verify every paired path with `git -C <root> cat-file -e <hash>:<path>`. For `changed`, derive the commit's changed-path set by diffing a root commit against Git's empty tree or diffing a non-root commit separately against every parent and taking the union; require every named path in that set. For `snapshot`, require only commit/tree existence and report it as observed-state evidence, never introduction evidence. Normalize `remote.origin.url` from either `git@github.com:OWNER/REPOSITORY.git` or `https://github.com/OWNER/REPOSITORY.git`, strip the optional `.git`, compare owner/repository case-insensitively, and reject a stable PR URL when origin is absent, non-GitHub, or names a different repository. Parse every Markdown destination in `Related architecture sections`, require it to resolve to an existing `architecture/arc42/*.md` file, and require that file's `related_adrs` metadata to contain the ADR's exact ID. Conversely, resolve every arc42 `related_adrs` ID to an ADR and require that ADR to link the exact arc42 path. Parse every `Related implementation plans` destination, require it to resolve beneath `docs/superpowers/plans/`, and require the target plan to contain a direct Markdown link resolving to that exact ADR. Conversely, scan every governed implementation plan for every direct Markdown link resolving to `architecture/adr/NNNN-*.md` and require the target ADR's ordered `Related implementation plans` sequence to link back to that exact plan. Preserve the stricter framework rule: the current framework plan's single `**Governing ADR:**` header link must resolve to ADR-0001 and ADR-0001 must name that exact plan. For foundational ADRs `ADR-0001` through `ADR-0008`, reject `Related architecture sections: None`; `None` remains valid in the reusable template and in future non-foundational records when genuinely unaffected.

Resolve every `Supersedes` and `Superseded by` ADR ID. Reject missing targets, self-reference, non-reciprocal declarations, and cycles in the directed predecessor-to-successor graph. Every predecessor with `Superseded by: ADR-NNNN` must be `Superseded`, every named successor must be `Accepted`, and the successor's `Supersedes` field must name the predecessor; `Deprecated` records do not claim a superseding ADR. Reject multiple successors for one predecessor.

Implement `validate_accepted_adr_immutability` with `git rev-parse --verify REF^{commit}`, `git show COMMIT:PATH`, and current filesystem reads when `head_ref` is omitted. Enumerate ADR paths in both sides of the edge without rename detection. Enforce the complete status-pair matrix from Step 1. A Proposed parent imposes no content, relationship/evidence-sequence, or implementation-status immutability when the child remains Proposed or transitions to Accepted/Rejected; the Accepted or Rejected child becomes the protected baseline for later edges. Whenever an ADR has parent status `Accepted`, `Superseded`, or `Deprecated`, require the same path to exist in the child, so deletion fails and a Git rename is deliberately treated as deletion plus unrelated introduction and rejected. For every such protected parent, require byte-stable normalized content for `Context`, `Decision drivers`, `Considered options`, `Decision`, and the entire `Consequences` section including its three required subsections. Apply the same protection permanently after supersession/deprecation rather than only while the immediate parent is Accepted. Enforce the post-acceptance implementation-status ordering and fixed `Not applicable` rule from Step 1. Treat each mutable relationship/evidence area, including `Related implementation plans`, as an ordered sequence, interpret the literal `None` as an empty sequence, and require the parent sequence to be an exact prefix of the child/current sequence. In particular, accept the tested `Partial -> Complete` transition when command/results are appended to `Compliance and verification` and valid `changed`/`snapshot` entries are appended to `Implementation evidence`; path-bound snapshot semantics remain the observed-tree-state semantics defined above. Reject edits to all other Accepted/Superseded/Deprecated protected-record fields. If the parent is `Rejected`, require the same path and compare the complete raw file bytes exactly; do not permit even an append-only relationship, compliance, or evidence change. An ADR absent from an edge's parent is exempt on that introduction edge only; if it is Accepted or Rejected in that child, every later child edge protects it with the corresponding rule.

Implement `validate_accepted_adr_edge_range` by resolving both refs to verified commits, requiring the base to be an ancestor of the head, and reading `git rev-list --reverse --topo-order --parents <base>..<head>`. For each emitted child, validate `parent -> child` against every listed parent, including parents outside `<base>..<head>` and every parent of a merge commit, by calling the two-tree immutability primitive. Prefix deterministic diagnostics with `<parent> -> <child>`. A root child has no edge and is skipped. This all-parent rule validates changes introduced by either side of a merge and must not be reduced to first-parent traversal. Extend `main` with `--adr-base-ref` and optional `--adr-head-ref`, plus the paired `--adr-edge-base-ref` and `--adr-edge-head-ref`; reject a lone edge flag, emit deterministic errors, and return non-zero on comparison failure.

- [ ] **Step 4: Write ADR-0001 through ADR-0004**

Use `Status: Accepted`. Every ADR links at least one exact affected arc42 file under `Related architecture sections`, and each linked arc42 file includes the reciprocal ADR ID in `related_adrs`. ADR-0001 is not retrospective and links the approved design plus its commit; implementation status is `Partial` until the framework is complete. Under `Related implementation plans`, ADR-0001 contains exactly `[Architecture Documentation and ADR Framework Implementation Plan](../../docs/superpowers/plans/2026-09-01-architecture-documentation-and-adr-framework-implementation.md)`; ADRs without a related plan use `None`. After ADR-0001 exists, insert exactly this single metadata line in this plan's header immediately after `**Spec:**`, without changing any task prose, checkbox, command, ordering, or other semantics:

```markdown
**Governing ADR:** [ADR-0001: Manage architecture as versioned code](../../../architecture/adr/0001-manage-architecture-as-versioned-code.md)
```

ADR-0002 through ADR-0004 are retrospective with implementation status `Complete` and cite exact evidence:

- ADR-0002: `PostingService`, `ReversalService`, proof services, and database privilege migrations.
- ADR-0003: `Money`, `PostingLine`, `JournalValidator`, overflow tests, and the debit/credit example in the funds-core README.
- ADR-0004: JDBC repositories, serializable transaction setup, Flyway migrations, PostgreSQL integration tests, and the separate proof-reader role.

Each ADR must explain at least two rejected alternatives and negative consequences. For retrospective Complete ADRs, identify immutable evidence at execution time with `git log --format='%H %s' -- <evidence-paths>`, then verify each selected hash, each `HASH:PATH` tree entry, and each `changed` claim with the validator before recording it. Verify these current-history candidates: `58fde48ba5ef053304b85ffe31cb17c1de021c5e` and `a8d7653f4296d13baa4e2fe56d7abae46161ff32` for ADR-0002, `38f822136da516ebf343c82c469a6cbccf148413` and `17a8a1d3d33b5d607b76bfa99d0a3c90f47c872c` for ADR-0003, and `c309afc5afcd0854d4ec690e80dcb9ba9ff28186` plus `58fde48ba5ef053304b85ffe31cb17c1de021c5e` for ADR-0004. Record each candidate only as `changed` for paths it actually changes; use `snapshot` for paths that exist at the selected commit but were not changed there. Every Complete record contains at least one verified path-bound local evidence entry.

- [ ] **Step 5: Write ADR-0005 through ADR-0008**

- ADR-0005 is retrospective/complete and cites exact reversal, immutability, and migration evidence.
- ADR-0006 is retrospective/complete and cites the idempotency row, journal/posting/balance/outbox atomic transaction, concurrency tests, and crash-recovery tests.
- ADR-0007 is retrospective/partial: identifier foundations exist, but issuance/resolution/NIP APIs do not.
- ADR-0008 is retrospective/partial: the 8 GiB target and resource envelopes are documented and some manifests exist, but the complete profile-based evidence suite is not deployed or measured.

For ADR-0005 verify and cite `feb5bbd951c5061ef05050c35604aa863cbdea02`; for ADR-0006 verify and cite both `df6b2fb6a67f1406ccf2e8b0fa813626900c7d25` and `227bd288b593015f9009b0c408b1daf29855e997`. These hashes are immutable evidence only when paired with exact paths and the verified `changed` or `snapshot` mode. For ADR-0007, inspect the identifier-foundation paths at candidate commits and record a verified `changed` entry for paths actually changed there plus `snapshot` entries for any other cited foundation paths. For ADR-0008, do the same for the 8 GiB constraint, resource-envelope, Dockerfile, smoke-script, and manifest paths; manifests observed in a `snapshot` entry remain design/state evidence and do not become deployment evidence. Both Partial ADRs require at least one verified path-bound local entry and may not use `None`.

- [ ] **Step 6: Update decision indexes and classification**

Link each ADR from `09-decisions.md`; update `related_adrs` in every arc42 file to the exact IDs that govern its documented claims, using `[]` when none governs the section; and replace each decision inventory row's temporary governance destination with an exact explicit anchor in the created ADR before marking that row `resolved`. Put the exact `<!-- migration-source: SOURCE_KEY -->` marker in the selected ADR section and remove that row's superseded provisional marker from `architecture/adr/README.md`. After remapping, the global marker multiset must equal only the active inventory destination triples.

- [ ] **Step 7: Validate and commit decisions**

Run:

```bash
set -euo pipefail
python3 architecture/scripts/validate_architecture.py --root . --checks metadata,adrs,links
python3 architecture/scripts/validate_architecture.py --root . --adr-base-ref HEAD
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
migration_report="$(mktemp)"
expected_report="$(mktemp)"
trap 'rm -f "$migration_report" "$expected_report"' EXIT
if python3 architecture/scripts/validate_architecture.py --root . --checks migration 2>"$migration_report"; then
  echo "migration unexpectedly has no deferred proposal rows" >&2
  exit 1
fi
git show HEAD:architecture/archive/comprehensive-design-migration-inventory.md | awk -F'|' '
  function trim(value) { gsub(/^[[:space:]]+|[[:space:]]+$/, "", value); return value }
  /^\|/ && trim($5) == "proposal" && trim($9) == "unresolved" {
    print "architecture/archive/comprehensive-design-migration-inventory.md: unresolved migration row " trim($2)
  }
' | LC_ALL=C sort >"$expected_report"
test -s "$expected_report"
LC_ALL=C sort -o "$migration_report" "$migration_report"
diff -u "$expected_report" "$migration_report"
```

Expected: ADR, metadata, link, and unit-test checks pass. The expected diagnostic set is frozen from the committed Task 4 inventory before Task 5 edits; the migration command fails only for those exact proposal-disposition rows still marked `unresolved` for Task 7. `diff` is silent, proving there are no new proposal rows and no schema, material-block coverage, destination, evidence, or source-marker diagnostics.

```bash
set -euo pipefail
task5_paths=(
  architecture/adr/0001-manage-architecture-as-versioned-code.md
  architecture/adr/0002-centralize-financial-invariants-in-funds-core.md
  architecture/adr/0003-use-signed-integer-minor-units.md
  architecture/adr/0004-use-postgresql-as-the-authoritative-ledger.md
  architecture/adr/0005-use-immutable-journals-and-additive-corrections.md
  architecture/adr/0006-couple-idempotency-and-outbox-to-ledger-commit.md
  architecture/adr/0007-separate-ledger-identity-from-account-addresses.md
  architecture/adr/0008-target-an-eight-gib-single-vm-evidence-suite.md
  architecture/adr/README.md
  architecture/arc42/01-introduction-and-goals.md
  architecture/arc42/02-constraints.md
  architecture/arc42/03-context-and-scope.md
  architecture/arc42/04-solution-strategy.md
  architecture/arc42/05-building-block-view.md
  architecture/arc42/06-runtime-view.md
  architecture/arc42/07-deployment-view.md
  architecture/arc42/08-crosscutting-concepts.md
  architecture/arc42/09-decisions.md
  architecture/arc42/10-quality-requirements.md
  architecture/arc42/11-risks-and-technical-debt.md
  architecture/arc42/12-glossary.md
  architecture/archive/comprehensive-design-migration-inventory.md
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
  docs/superpowers/plans/2026-09-01-architecture-documentation-and-adr-framework-implementation.md
)
git add -- "${task5_paths[@]}"
expected_staged="$(mktemp)"
actual_staged="$(mktemp)"
trap 'rm -f "$expected_staged" "$actual_staged"' EXIT
printf '%s\n' "${task5_paths[@]}" | LC_ALL=C sort >"$expected_staged"
git diff --cached --name-only --no-renames -- "${task5_paths[@]}" | LC_ALL=C sort >"$actual_staged"
diff -u "$expected_staged" "$actual_staged"
git commit --only -m "docs: record foundational architecture decisions" -- "${task5_paths[@]}"
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
- Produces: Mermaid sources and `architecture/scripts/render-diagrams.sh [output-directory]`, which installs the locked renderer and all npm/Puppeteer/XDG cache, browser-asset, config, and data state below one invocation-owned temporary root, renders every `.mmd` file, exits non-zero on the first install or syntax failure, and never reads, writes, or deletes `architecture/tooling/node_modules/`, the repository outside the caller-owned output directory, `~/.npm`, or `~/.cache`.

- [ ] **Step 0: Preflight the exact Task 6 write scope**

```bash
set -euo pipefail
task6_paths=(
  .gitignore
  architecture/arc42/03-context-and-scope.md
  architecture/arc42/05-building-block-view.md
  architecture/arc42/06-runtime-view.md
  architecture/arc42/07-deployment-view.md
  architecture/diagrams/containers.mmd
  architecture/diagrams/context.mmd
  architecture/diagrams/funds-core-components.mmd
  architecture/diagrams/posting-sequence.mmd
  architecture/diagrams/single-vm-deployment.mmd
  architecture/scripts/render-diagrams.sh
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
  architecture/tooling/package-lock.json
  architecture/tooling/package.json
)
owned_state="$(git status --porcelain=v1 --untracked-files=all --ignored=matching -- "${task6_paths[@]}")"
if [[ -n "$owned_state" ]]; then
  printf '%s\n' 'Task 6 owned paths overlap existing work; stop and coordinate without altering it:' "$owned_state" >&2
  exit 1
fi
```

Expected: no output. Run this before Step 1 edits; non-owned state is allowed. This exact array is repeated unchanged in Step 8.

- [ ] **Step 1: Add failing diagram metadata tests**

Write tests against a new `validate_diagrams` behavior before implementing it. Test the seven required metadata comments, allowed state values, non-empty `abstraction`, non-empty `question`, existing arc42 path, existing ADR IDs, ISO date, matching state in the Mermaid title, required five filenames, and executable mode on `architecture/scripts/render-diagrams.sh`. Include negative tests for missing abstraction, missing question, missing title state, a `CURRENT` metadata/`PROPOSED` title mismatch, a non-executable render script, and an arc42 section that does not contain a Markdown link back to the diagram source. Include a positive fixture in which each diagram's declared arc42 section links its exact `.mmd` path. Add render-script contract fixtures requiring one invocation-owned `temp_root="$(mktemp -d)"`, an install prefix and npm/Puppeteer/XDG cache/config/data directories beneath that root, exact copies of `architecture/tooling/package.json` and `package-lock.json` into the install prefix, and `env`-bound `npm_config_cache`, `PUPPETEER_CACHE_DIR`, `XDG_CACHE_HOME`, `XDG_CONFIG_HOME`, and `XDG_DATA_HOME` on both `npm ci --prefix "$install_dir"` and `"$install_dir/node_modules/.bin/mmdc"`. Require one trap that removes only `"$temp_root"`; a default output may live below that root, while a caller-provided output is never removed. Reject any script that references `architecture/tooling/node_modules`, installs with `--prefix architecture/tooling`, omits any owned cache variable on either tool invocation, deletes a caller-provided output directory, writes a cache path beneath the repository, `~/.npm`, or `~/.cache`, or cleans anything outside its invocation-owned root. Add a fake-`npm`/fake-`mmdc` execution test that records their environments, asserts every cache/config/data path is below the created temp root, asserts none is below the repository or test home, and asserts the owned root is absent after success and after a forced render failure while a caller output directory remains.

- [ ] **Step 2: Run diagram tests and verify failure**

Run:

```bash
set -euo pipefail
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

Generate the lockfile with an invocation-owned cache/config/data root and remove only that root, then mechanically verify both manifests pin and resolve Mermaid CLI exactly:

```bash
set -euo pipefail
lock_temp_root="$(mktemp -d)"
cleanup_lock_temp() {
  rm -rf -- "$lock_temp_root"
}
trap cleanup_lock_temp EXIT
mkdir -p -- \
  "$lock_temp_root/npm-cache" \
  "$lock_temp_root/puppeteer-cache" \
  "$lock_temp_root/xdg-cache" \
  "$lock_temp_root/xdg-config" \
  "$lock_temp_root/xdg-data"
env \
  npm_config_cache="$lock_temp_root/npm-cache" \
  PUPPETEER_CACHE_DIR="$lock_temp_root/puppeteer-cache" \
  XDG_CACHE_HOME="$lock_temp_root/xdg-cache" \
  XDG_CONFIG_HOME="$lock_temp_root/xdg-config" \
  XDG_DATA_HOME="$lock_temp_root/xdg-data" \
  npm install --package-lock-only --prefix architecture/tooling
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

The script must begin with `#!/usr/bin/env bash`, use `set -euo pipefail`, and resolve repository paths from its own location. It creates one `temp_root` with `mktemp -d`, puts the installation directory, default output, npm cache, Puppeteer cache/browser assets, and XDG cache/config/data below it, copies the exact committed `architecture/tooling/package.json` and `architecture/tooling/package-lock.json` into its install directory, and invokes both `npm ci` and the installed `mmdc` with the same explicit owned environment. It verifies and invokes only `"$install_dir/node_modules/.bin/mmdc"` and installs nothing beneath the repository. It renders into a caller-provided output directory when supplied; otherwise output lives below `temp_root`. Its trap removes only `temp_root`, never a caller-owned output or any repository/home/cache path. A pre-existing `architecture/tooling/node_modules/` is excluded and nonblocking: the script never reads, writes, validates, or deletes it.

Implement the script with this exact ownership pattern (the loop renders every sorted source and returns immediately on the first failed render because `set -e` is active):

```bash
#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repository_root="$(cd "$script_dir/../.." && pwd -P)"
tooling_dir="$repository_root/architecture/tooling"
temp_root="$(mktemp -d)"
install_dir="$temp_root/install"
output_dir=""
cleanup() {
  rm -rf -- "$temp_root"
}
trap cleanup EXIT
if [[ $# -gt 1 ]]; then
  echo "usage: $0 [output-directory]" >&2
  exit 2
fi
if [[ $# -eq 1 ]]; then
  output_dir="$1"
  mkdir -p -- "$output_dir"
else
  output_dir="$temp_root/output"
fi
mkdir -p -- \
  "$install_dir" \
  "$output_dir" \
  "$temp_root/npm-cache" \
  "$temp_root/puppeteer-cache" \
  "$temp_root/xdg-cache" \
  "$temp_root/xdg-config" \
  "$temp_root/xdg-data"
cp -- "$tooling_dir/package.json" "$tooling_dir/package-lock.json" "$install_dir/"
owned_env=(
  "npm_config_cache=$temp_root/npm-cache"
  "PUPPETEER_CACHE_DIR=$temp_root/puppeteer-cache"
  "XDG_CACHE_HOME=$temp_root/xdg-cache"
  "XDG_CONFIG_HOME=$temp_root/xdg-config"
  "XDG_DATA_HOME=$temp_root/xdg-data"
)
env "${owned_env[@]}" npm ci --prefix "$install_dir"
mmdc="$install_dir/node_modules/.bin/mmdc"
test -x "$mmdc"
mapfile -t sources < <(find "$repository_root/architecture/diagrams" -maxdepth 1 -type f -name '*.mmd' -print | LC_ALL=C sort)
test "${#sources[@]}" -gt 0
for source in "${sources[@]}"; do
  output="$output_dir/$(basename "${source%.mmd}").svg"
  env "${owned_env[@]}" "$mmdc" -i "$source" -o "$output"
done
```

Establish and verify the executable and isolated-install contract before invoking it:

Run:

```bash
set -euo pipefail
chmod +x architecture/scripts/render-diagrams.sh
test -x architecture/scripts/render-diagrams.sh
! rg -n 'architecture/tooling/node_modules|npm[[:space:]]+ci[[:space:]]+--prefix[[:space:]]+architecture/tooling' architecture/scripts/render-diagrams.sh
architecture/scripts/render-diagrams.sh
python3 architecture/scripts/validate_architecture.py --root . --checks diagrams,links
```

Expected: the render script performs its locked `npm ci`, Puppeteer browser/cache work, and Mermaid rendering state only below its invocation-owned temporary root, all five required diagram sources render, that root is removed, and metadata/link validation passes. A caller-owned output directory is retained. No repository, `~/.npm`, or `~/.cache` state is created or changed; any pre-existing `architecture/tooling/node_modules/` remains byte-for-byte outside the script's scope.

- [ ] **Step 8: Link diagrams and commit**

Link each source from its owning arc42 section; do not commit generated SVGs. Verify ignored/generated dependencies are not tracked:

```bash
set -euo pipefail
test -z "$(git ls-files 'architecture/tooling/node_modules/**' 'architecture/diagrams/generated/**' 'architecture/diagrams/generated/**/*.svg')"
```

```bash
set -euo pipefail
task6_paths=(
  .gitignore
  architecture/arc42/03-context-and-scope.md
  architecture/arc42/05-building-block-view.md
  architecture/arc42/06-runtime-view.md
  architecture/arc42/07-deployment-view.md
  architecture/diagrams/containers.mmd
  architecture/diagrams/context.mmd
  architecture/diagrams/funds-core-components.mmd
  architecture/diagrams/posting-sequence.mmd
  architecture/diagrams/single-vm-deployment.mmd
  architecture/scripts/render-diagrams.sh
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
  architecture/tooling/package-lock.json
  architecture/tooling/package.json
)
git add -- "${task6_paths[@]}"
test "$(git ls-files -s architecture/scripts/render-diagrams.sh | awk '{print $1}')" = 100755
expected_staged="$(mktemp)"
actual_staged="$(mktemp)"
trap 'rm -f "$expected_staged" "$actual_staged"' EXIT
printf '%s\n' "${task6_paths[@]}" | LC_ALL=C sort >"$expected_staged"
git diff --cached --name-only --no-renames -- "${task6_paths[@]}" | LC_ALL=C sort >"$actual_staged"
diff -u "$expected_staged" "$actual_staged"
git diff --cached --name-status -- "${task6_paths[@]}"
git status --short -- architecture/arc42
git commit --only -m "docs: add architecture diagrams as code" -- "${task6_paths[@]}"
```

Expected: the staged-name comparison is exact for Task 6, and the status command reports any other arc42 state without staging or failing on it. `git commit --only` commits only `task6_paths`, so unrelated pre-existing staged files or edits to any other arc42 path remain untouched and cannot enter the Task 6 commit.

### Task 7: Separate proposed capabilities and add plan traceability

**Files:**
- Create: `architecture/proposals/account-identifiers-and-nip-inbound.md`
- Create: `architecture/proposals/conventional-deposit-products-and-accrual.md`
- Create: `architecture/proposals/non-interest-banking-products.md`
- Create: `architecture/proposals/full-poc-platform.md`
- Create: `architecture/proposals/production-platform.md`
- Create: `architecture/proposals/providers-and-reconciliation.md`
- Modify: `architecture/proposals/README.md`
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

- [ ] **Step 0: Preflight the exact Task 7 write scope**

```bash
set -euo pipefail
task7_paths=(
  architecture/adr/0001-manage-architecture-as-versioned-code.md
  architecture/adr/0002-centralize-financial-invariants-in-funds-core.md
  architecture/adr/0003-use-signed-integer-minor-units.md
  architecture/adr/0004-use-postgresql-as-the-authoritative-ledger.md
  architecture/adr/0005-use-immutable-journals-and-additive-corrections.md
  architecture/adr/0006-couple-idempotency-and-outbox-to-ledger-commit.md
  architecture/adr/0007-separate-ledger-identity-from-account-addresses.md
  architecture/adr/0008-target-an-eight-gib-single-vm-evidence-suite.md
  architecture/archive/comprehensive-design-migration-inventory.md
  architecture/infrastructure/infra-ubuntu24.04-poc.md
  architecture/proposals/account-identifiers-and-nip-inbound.md
  architecture/proposals/conventional-deposit-products-and-accrual.md
  architecture/proposals/full-poc-platform.md
  architecture/proposals/non-interest-banking-products.md
  architecture/proposals/production-platform.md
  architecture/proposals/providers-and-reconciliation.md
  architecture/proposals/README.md
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
  docs/superpowers/plans/2026-08-30-account-identifiers-and-nip-inbound-implementation.md
  docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md
  docs/superpowers/plans/2026-08-30-conventional-deposit-products-and-accrual-implementation.md
  docs/superpowers/plans/2026-08-30-non-interest-banking-products-implementation.md
)
owned_state="$(git status --porcelain=v1 --untracked-files=all --ignored=matching -- "${task7_paths[@]}")"
if [[ -n "$owned_state" ]]; then
  printf '%s\n' 'Task 7 owned paths overlap existing work; stop and coordinate without altering it:' "$owned_state" >&2
  exit 1
fi
```

Expected: no output. Run this before Step 1 edits; non-owned state is allowed. This exact array is repeated unchanged in Step 8.

- [ ] **Step 1: Add failing proposal-lifecycle and bidirectional-traceability tests**

Write tests against new proposal metadata, lifecycle, bootstrap, and traceability behavior before implementation. Define the six governed proposal identities by basename: `account-identifiers-and-nip-inbound`, `conventional-deposit-products-and-accrual`, `non-interest-banking-products`, `full-poc-platform`, `production-platform`, and `providers-and-reconciliation`. Give each identity a permanent explicit HTML anchor of that basename in a `## Governed proposal registry` section of `architecture/proposals/README.md`; the immediately following single Markdown link is its mutable location pointer. Test `validate_proposal_bootstrap(root)` separately: it passes only when all six initial files and registry targets are present in the active directory and is deliberately not registered in permanent `CHECKS`. For ongoing `validate_metadata`/`validate_traceability`, require every governed identity at exactly one of `architecture/proposals/<basename>.md` or `architecture/archive/proposals/<basename>.md`, and require its registry pointer to resolve to that exact sole location; add independent missing, active-plus-archive duplicate, stale-pointer, duplicate-anchor, and wrong-basename fixtures. Active locations allow only `draft`, `proposed`, `approved`, or `implementing`. Archive locations allow only `implemented`, `rejected`, or `superseded` and require `implementation_status`, `replacement`, `implementation_evidence`, non-empty `related_adrs`, and non-empty `related_plans`: `implemented` requires `implementation_status: Complete` and one or more existing `architecture/arc42/*.md` replacement links; `superseded` requires `implementation_status: Not applicable` and one or more existing active/archive proposal replacement links; `rejected` requires `implementation_status: Not applicable` and literal `replacement: None`. Every terminal archive record requires at least one valid path-bound local or stable same-repository pull-request implementation/closure evidence entry and reciprocal ADR/plan links; `None` is not evidence. Add positive fixtures for all three archive terminal statuses and focused negative fixtures for missing/invalid implementation status, replacement, evidence, ADR, or plan traceability.

Require allowed proposal statuses, existing `related_plans` paths, existing `related_adrs` IDs, a `**Proposal:**` backlink to the identity's stable registry anchor in the account-identifier, conventional-deposit, and non-interest plans, reciprocal `Related proposals:` links to that same stable anchor in every ADR named by a proposal, and reciprocal stable-anchor proposal links in every plan named by a proposal. The proposal file itself retains direct ADR and plan links; the validator resolves the registry pointer before comparing identities, so a later active-to-archive move changes only the proposal path and registry pointer, never an accepted ADR relationship entry. Reassert the generic Task 5 direct plan/ADR rule with repository fixtures for the framework, accounting-kernel, conventional-deposit, and non-interest plans: every direct ADR link in each plan has the exact ADR `Related implementation plans` backlink and every such ADR backlink has a direct plan link. Include independent missing-backlink tests for each direction. For `2026-08-30-accounting-kernel-implementation.md`, require `**Current architecture:**` links to arc42 sections 05, 06, and 08 plus `**Retrospective ADRs:**` links to ADR-0002 through ADR-0006, and explicitly reject a `**Proposal:**` backlink.

- [ ] **Step 2: Run tests and verify failure**

Run:

```bash
set -euo pipefail
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: fail because proposal lifecycle/bootstrap metadata and bidirectional traceability rules are not implemented.

- [ ] **Step 3: Implement proposal metadata and bidirectional traceability**

Extend `validate_metadata` with the exact proposal required fields and `PROPOSAL_STATUSES = frozenset({"draft", "proposed", "approved", "implementing", "implemented", "rejected", "superseded"})`. Implement the governed-identity, unique registry-anchor/pointer, exactly-one-active-or-archive-location, active/non-terminal placement, archive/terminal placement, status-specific `implementation_status`/`replacement`, closure-evidence, non-empty ADR/plan link, and missing/duplicate/stale-pointer rules from Step 1. This is the permanent lifecycle contract: it requires six logical proposal records, not six permanently active paths. Implement `validate_proposal_bootstrap(root: Path) -> list[str]` as a Task 7 adoption-only assertion that all six initial records and pointers are active; do not add it to `CHECKS` or `VALIDATORS`, so later valid archival does not fail ordinary repository validation. Add a `validate_traceability(root: Path) -> list[str]` check and register `traceability`. For every proposal at either valid location, verify that each `related_plans` path exists and contains a link to that proposal identity's stable registry anchor and that each `related_adrs` ID resolves to an ADR containing a `Related proposals:` link to the same stable anchor; resolve the pointer to prove the reciprocal link identifies the exact active/archive record. Apply the generic direct plan/ADR reciprocity from Task 5 to all governed plans, enforce the special accounting-kernel rule from Step 1, and verify the three unimplemented proposal/plan mappings exactly.

- [ ] **Step 4: Extract product and identifier proposals**

Use `status: approved` for the three proposals with existing implementation plans. Preserve their requirements, constraints, acceptance boundaries, and exact plan links without presenting them as current. Use these exact ADR mappings: account identifiers/NIP links ADR-0002, ADR-0004, ADR-0006, and ADR-0007; conventional deposits links ADR-0002, ADR-0003, ADR-0004, ADR-0005, and ADR-0006; non-interest banking links ADR-0002, ADR-0003, ADR-0004, ADR-0005, and ADR-0006.

- [ ] **Step 5: Extract platform and provider proposals**

Use `status: proposed` for full PoC platform, production platform, and provider/reconciliation proposals. Record that repository manifests or architecture text are design evidence, not deployment evidence. Use these exact ADR mappings: full PoC platform links ADR-0001, ADR-0002, ADR-0004, ADR-0006, and ADR-0008; production platform links ADR-0001, ADR-0004, and ADR-0008; providers/reconciliation links ADR-0002, ADR-0004, ADR-0006, ADR-0007, and ADR-0008. Link the proposed container and single-VM diagrams.

- [ ] **Step 6: Label the infrastructure document and add backlinks**

Add proposal metadata to `architecture/infrastructure/infra-ubuntu24.04-poc.md` with `status: proposed`, owner `platform`, ADR-0008, and the full-PoC proposal. Add `**Proposal:**` and `**Related ADRs:**` immediately below the headers of only these unimplemented plans, using these exact proposal mappings and direct ADR mappings:

- account identifiers/NIP -> `architecture/proposals/README.md#account-identifiers-and-nip-inbound`; ADR-0002, ADR-0004, ADR-0006, and ADR-0007
- conventional deposits -> `architecture/proposals/README.md#conventional-deposit-products-and-accrual`; ADR-0002, ADR-0003, ADR-0004, ADR-0005, and ADR-0006
- non-interest banking -> `architecture/proposals/README.md#non-interest-banking-products`; ADR-0002, ADR-0003, ADR-0004, ADR-0005, and ADR-0006

Do not add a proposal backlink to the already-implemented accounting-kernel plan. Instead add `**Current architecture:**` links to `architecture/arc42/05-building-block-view.md`, `06-runtime-view.md`, and `08-crosscutting-concepts.md`, plus direct `**Retrospective ADRs:**` links to ADR-0002 through ADR-0006. Add the six permanent registry anchors and initial active-file pointers to `architecture/proposals/README.md`. In each ADR referenced by any of the six proposals, append the proposal's stable registry-anchor link under `Related proposals:`. Also append exact plan paths under `Related implementation plans` so all direct links are reciprocal: ADR-0002 links the account-identifier, accounting-kernel, conventional-deposit, and non-interest plans; ADR-0003 links the accounting-kernel, conventional-deposit, and non-interest plans; ADR-0004 links all four; ADR-0005 links the accounting-kernel, conventional-deposit, and non-interest plans; ADR-0006 links all four; ADR-0007 links the account-identifier plan; ADR-0008 adds no plan backlink because none of these plans directly links it. Preserve the existing ADR-0001/framework-plan pair exactly. These are suffix appends to the ordered accepted-ADR relationship sequences; do not alter Context, drivers, options, Decision, Consequences, prior relationship entries, or prior implementation evidence in accepted ADRs.

- [ ] **Step 7: Resolve proposal inventory rows and validate**

Update every proposal-classified inventory row with its exact real proposal anchor and `resolved`; put the exact `<!-- migration-source: SOURCE_KEY -->` marker in the selected proposal section and remove that row's superseded provisional marker from `architecture/proposals/README.md`. The global marker inventory must contain no provisional governance marker after remapping. Run:

```bash
set -euo pipefail
python3 architecture/scripts/validate_architecture.py --root . --checks metadata,links,migration,traceability
python3 -c 'from pathlib import Path; from architecture.scripts.validate_architecture import validate_proposal_bootstrap; errors = validate_proposal_bootstrap(Path(".")); print("\n".join(errors)); raise SystemExit(bool(errors))'
python3 architecture/scripts/validate_architecture.py --root . --adr-base-ref HEAD
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: metadata, links, migration inventory, lifecycle-aware bidirectional traceability, the one-time all-six-active bootstrap assertion, accepted-ADR append-only/immutability checks, and unit tests pass; every granular inventory row is now `resolved`, every final destination anchor exists, and every destination section carries the exact source-key backlink. Later adoption changes may move a governed proposal to its same-basename archive path with valid terminal metadata without running or satisfying the bootstrap-only function.

- [ ] **Step 8: Commit proposal separation**

```bash
set -euo pipefail
task7_paths=(
  architecture/adr/0001-manage-architecture-as-versioned-code.md
  architecture/adr/0002-centralize-financial-invariants-in-funds-core.md
  architecture/adr/0003-use-signed-integer-minor-units.md
  architecture/adr/0004-use-postgresql-as-the-authoritative-ledger.md
  architecture/adr/0005-use-immutable-journals-and-additive-corrections.md
  architecture/adr/0006-couple-idempotency-and-outbox-to-ledger-commit.md
  architecture/adr/0007-separate-ledger-identity-from-account-addresses.md
  architecture/adr/0008-target-an-eight-gib-single-vm-evidence-suite.md
  architecture/archive/comprehensive-design-migration-inventory.md
  architecture/infrastructure/infra-ubuntu24.04-poc.md
  architecture/proposals/account-identifiers-and-nip-inbound.md
  architecture/proposals/conventional-deposit-products-and-accrual.md
  architecture/proposals/full-poc-platform.md
  architecture/proposals/non-interest-banking-products.md
  architecture/proposals/production-platform.md
  architecture/proposals/providers-and-reconciliation.md
  architecture/proposals/README.md
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
  docs/superpowers/plans/2026-08-30-account-identifiers-and-nip-inbound-implementation.md
  docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md
  docs/superpowers/plans/2026-08-30-conventional-deposit-products-and-accrual-implementation.md
  docs/superpowers/plans/2026-08-30-non-interest-banking-products-implementation.md
)
git add -- "${task7_paths[@]}"
expected_staged="$(mktemp)"
actual_staged="$(mktemp)"
trap 'rm -f "$expected_staged" "$actual_staged"' EXIT
printf '%s\n' "${task7_paths[@]}" | LC_ALL=C sort >"$expected_staged"
git diff --cached --name-only --no-renames -- "${task7_paths[@]}" | LC_ALL=C sort >"$actual_staged"
diff -u "$expected_staged" "$actual_staged"
git commit --only -m "docs: separate proposed architecture from current state" -- "${task7_paths[@]}"
```

### Task 8: Complete archive cutover and repair documentation links

**Files:**

Review-evidence commit (`task8_review_paths`):

- Create: `architecture/archive/comprehensive-design-migration-review.md`
- Modify: `architecture/scripts/validate_architecture.py`
- Modify: `architecture/scripts/tests/test_validate_architecture.py`

Archive-cutover commit (`task8_cutover_paths`):

- Move: `architecture/modern-core-banking-comprehensive-design-revised.md` to `architecture/archive/modern-core-banking-comprehensive-design-revised.md` (both exact paths belong to the array)
- Modify: `ARCHITECTURE.md`
- Modify: `architecture/README.md`
- Modify: `services/funds-core/README.md`
- Modify: `architecture/infrastructure/infra-ubuntu24.04-poc.md`
- Modify: `docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md`

**Interfaces:**
- Consumes: a migration inventory with all 27 sections resolved and existing replacement destinations.
- Consumes: a read-only independent review of the committed Task 7 inventory, performed by a named reviewer other than the implementer before the source move.
- Produces: a non-authoritative historical document under `archive/` and no stale internal links.
- Produces: a persistent approval record bound to the reviewed pre-cutover full commit and exact inventory Git blob.

- [ ] **Step 0: Preflight the exact Task 8 review-evidence write scope**

Run before writing archive-state tests, validator code, or the review record:

```bash
set -euo pipefail
task8_review_paths=(
  architecture/archive/comprehensive-design-migration-review.md
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
)
owned_state="$(git status --porcelain=v1 --untracked-files=all --ignored=matching -- "${task8_review_paths[@]}")"
if [[ -n "$owned_state" ]]; then
  printf '%s\n' 'Task 8 review paths overlap existing work; stop and coordinate without altering it:' "$owned_state" >&2
  exit 1
fi
```

Expected: no output. Non-owned state is allowed. This exact array is repeated unchanged in Step 4's review-evidence commit.

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
set -euo pipefail
python3 -m unittest architecture.scripts.tests.test_validate_architecture.ValidatorTest.test_archive_state_selects_exactly_one_source_and_rechecks_full_inventory -v
python3 -m unittest architecture.scripts.tests.test_validate_architecture.ValidatorTest.test_archive_review_binds_named_approval_to_committed_inventory -v
```

Expected: fail because the Task 4 migration validator only knows the old source path and the new archive-state/source-selection behavior does not exist.

- [ ] **Step 2: Implement the archive state machine, selected-source check, and review gate**

Implement `select_comprehensive_source(root: Path, all_rows_resolved: bool) -> tuple[Path | None, list[str]]` and use it from both `validate_migration_inventory` and `validate_archive_state`. It returns the old exact path when it alone exists in unresolved or resolved pre-cutover state, returns the archived exact path only when it alone exists and all rows are resolved, and returns deterministic errors plus `None` for neither, both, or an archived-only unresolved state. After selection, `validate_migration_inventory` must parse that selected file and rerun its full heading-key, top-level `01` through `27`, subsection, material-block tokenization, exact-once coverage, source-key back-reference, exact destination anchor/backlink mapping, evidence, rationale, and resolution contract; moving the source must not reduce validation to file presence.

Implement `validate_archive_review(root: Path) -> list[str]` and register `archive-review`. Parse exactly one value each for `Reviewed commit`, `Reviewer`, `Implementer`, `Outcome`, `Unresolved rows`, `Inventory path`, and `Inventory blob`. Require distinct non-empty reviewer and implementer identities, literal `APPROVED`, integer zero, the exact inventory path, and lowercase 40-hex commit/blob IDs. Independently parse the inventory and require zero unresolved rows. Verify the reviewed commit exists, resolves the recorded inventory path to the recorded blob, and its inventory bytes equal both the current filesystem bytes and the bytes at `HEAD:architecture/archive/comprehensive-design-migration-inventory.md`; this prevents either uncommitted or later committed inventory changes. Before the evidence file is tracked, require `Reviewed commit` to equal `HEAD`. After it is tracked, locate its unique introduction commit with `git log --diff-filter=A --format=%H -- architecture/archive/comprehensive-design-migration-review.md` and require the reviewed commit to equal that introduction commit's sole parent; later Task 8 commits therefore do not invalidate the record. Make archived-only state call this validator, so cutover cannot validate without persistent review evidence. Add `archive` to `CHECKS` and `VALIDATORS`; do not infer classification completeness from path presence alone.

Run:

```bash
set -euo pipefail
python3 -m unittest architecture.scripts.tests.test_validate_architecture.ValidatorTest.test_archive_state_selects_exactly_one_source_and_rechecks_full_inventory -v
python3 architecture/scripts/validate_architecture.py --root . --checks migration,archive,links
```

Expected: the focused archive-state/source-selection tests pass, followed by repository validation with zero unresolved rows in the valid resolved pre-cutover state. The missing review file still blocks `archive-review`. Do not move the source document if granular coverage, destination anchors/backlinks, evidence, links, or archive state fails.

- [ ] **Step 3: Obtain independent read-only migration approval**

After Task 7 is committed and before any source move or inventory edit, resolve the exact review inputs:

```bash
set -euo pipefail
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
set -euo pipefail
python3 architecture/scripts/validate_architecture.py --root . --checks migration,archive,archive-review,links
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
task8_review_paths=(
  architecture/archive/comprehensive-design-migration-review.md
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
)
git add -- "${task8_review_paths[@]}"
expected_staged="$(mktemp)"
actual_staged="$(mktemp)"
trap 'rm -f "$expected_staged" "$actual_staged"' EXIT
printf '%s\n' "${task8_review_paths[@]}" | LC_ALL=C sort >"$expected_staged"
git diff --cached --name-only --no-renames -- "${task8_review_paths[@]}" | LC_ALL=C sort >"$actual_staged"
diff -u "$expected_staged" "$actual_staged"
git commit --only -m "docs: approve comprehensive design migration" -- "${task8_review_paths[@]}"
review_commit="$(git rev-parse --verify 'HEAD^{commit}')"
test "$(git rev-parse --verify "$review_commit^")" = "$reviewed_commit"
python3 architecture/scripts/validate_architecture.py --root . --checks migration,archive,archive-review,links
```

- [ ] **Step 5: Preflight the exact Task 8 archive-cutover write scope**

Run only after the review-evidence commit succeeds and immediately before any cutover edit or `git mv`:

```bash
set -euo pipefail
task8_cutover_paths=(
  ARCHITECTURE.md
  architecture/README.md
  architecture/archive/modern-core-banking-comprehensive-design-revised.md
  architecture/infrastructure/infra-ubuntu24.04-poc.md
  architecture/modern-core-banking-comprehensive-design-revised.md
  docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md
  services/funds-core/README.md
)
owned_state="$(git status --porcelain=v1 --untracked-files=all --ignored=matching -- "${task8_cutover_paths[@]}")"
if [[ -n "$owned_state" ]]; then
  printf '%s\n' 'Task 8 cutover paths overlap existing work; stop and coordinate without altering it:' "$owned_state" >&2
  exit 1
fi
```

Expected: no output. Non-owned state is allowed. This exact array is repeated unchanged in Step 9's archive-cutover commit.

- [ ] **Step 6: Move the comprehensive design with Git**

Run:

```bash
set -euo pipefail
git mv architecture/modern-core-banking-comprehensive-design-revised.md architecture/archive/modern-core-banking-comprehensive-design-revised.md
```

Add a banner immediately under its title: `Historical source document — non-authoritative; see /ARCHITECTURE.md and the migration inventory.`

- [ ] **Step 7: Repair links and add exact historical navigation**

Run:

```bash
set -euo pipefail
rg -n 'architecture/modern-core-banking-comprehensive-design-revised.md|modern-core-banking-comprehensive-design-revised.md' --glob '*.md'
```

Update `services/funds-core/README.md`, `architecture/infrastructure/infra-ubuntu24.04-poc.md`, and the accounting-kernel plan to the root entry point or exact arc42/proposal destination. The approved design's repository-tree example and this implementation plan may retain the historical filename as non-link prose. Markdown links to the archived historical source are allowed only in the migration inventory and the two deliberate navigation entries below.

Make these two task-owned navigation edits explicitly:

- In `ARCHITECTURE.md`, add this concise section and keep the file below 180 physical lines:

```markdown
## Historical source

The [archived comprehensive design](architecture/archive/modern-core-banking-comprehensive-design-revised.md) is non-authoritative. Use the [migration inventory](architecture/archive/comprehensive-design-migration-inventory.md) to find each classified current, proposed, decision, service, plan, or historical destination.
```

- In `architecture/README.md`, add this archive/navigation section; its relative links intentionally differ from the root entry point's links:

```markdown
## Archive and migration evidence

The [archived comprehensive design](archive/modern-core-banking-comprehensive-design-revised.md), [migration inventory](archive/comprehensive-design-migration-inventory.md), and [independent migration review](archive/comprehensive-design-migration-review.md) preserve source history and cutover evidence. They are non-authoritative; authority for current architecture remains with the [arc42 current-state documents](arc42/01-introduction-and-goals.md) and their sibling sections.
```

- [ ] **Step 8: Run complete documentation validation**

Run:

```bash
set -euo pipefail
python3 architecture/scripts/validate_architecture.py --root .
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
architecture/scripts/render-diagrams.sh
git diff --check -- ARCHITECTURE.md architecture/README.md architecture/archive/modern-core-banking-comprehensive-design-revised.md architecture/infrastructure/infra-ubuntu24.04-poc.md architecture/modern-core-banking-comprehensive-design-revised.md docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md services/funds-core/README.md
```

Expected: validation, unit tests, Mermaid rendering, and whitespace checks pass. Link validation resolves the root historical-source/archive links, the architecture README's source/inventory/review links, and all repaired old-path references.

- [ ] **Step 9: Commit the archive cutover**

```bash
set -euo pipefail
task8_cutover_paths=(
  ARCHITECTURE.md
  architecture/README.md
  architecture/archive/modern-core-banking-comprehensive-design-revised.md
  architecture/infrastructure/infra-ubuntu24.04-poc.md
  architecture/modern-core-banking-comprehensive-design-revised.md
  docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md
  services/funds-core/README.md
)
# `git mv` has already staged the rename. Restore only these two task-owned
# index entries so the required exact-array `git add` can stage both sides.
git restore --staged -- \
  architecture/modern-core-banking-comprehensive-design-revised.md \
  architecture/archive/modern-core-banking-comprehensive-design-revised.md
git add -- "${task8_cutover_paths[@]}"
expected_staged="$(mktemp)"
actual_staged="$(mktemp)"
trap 'rm -f "$expected_staged" "$actual_staged"' EXIT
printf '%s\n' "${task8_cutover_paths[@]}" | LC_ALL=C sort >"$expected_staged"
git diff --cached --name-only --no-renames -- "${task8_cutover_paths[@]}" | LC_ALL=C sort >"$actual_staged"
diff -u "$expected_staged" "$actual_staged"
git commit --only -m "docs: complete architecture documentation migration" -- "${task8_cutover_paths[@]}"
```

### Task 9: Report stale architecture verification without blocking

**Files:**
- Modify: `architecture/README.md`
- Modify: `architecture/scripts/validate_architecture.py`
- Modify: `architecture/scripts/tests/test_validate_architecture.py`

**Interfaces:**
- Consumes: arc42 and Mermaid `last_verified` ISO dates plus an explicit reporting date.
- Produces: `report_stale(root: Path, as_of: date, threshold_days: int = 90) -> list[StaleWarning]` and CLI flags `--report-stale --as-of YYYY-MM-DD`; warnings alone always exit `0`.

- [ ] **Step 0: Preflight the exact Task 9 write scope**

```bash
set -euo pipefail
task9_paths=(
  architecture/README.md
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
)
owned_state="$(git status --porcelain=v1 --untracked-files=all --ignored=matching -- "${task9_paths[@]}")"
if [[ -n "$owned_state" ]]; then
  printf '%s\n' 'Task 9 owned paths overlap existing work; stop and coordinate without altering it:' "$owned_state" >&2
  exit 1
fi
```

Expected: no output. Run this before Step 1 edits; non-owned state is allowed. This exact array is repeated unchanged in Step 5.

- [ ] **Step 1: Add failing deterministic staleness tests**

Before production changes, test an explicit `as_of=date(2026, 9, 1)` with a 90-calendar-day threshold: age 90 is not stale, age 91 is stale, future dates produce a validation error rather than a stale warning, malformed dates remain blocking metadata/diagram errors, warnings are sorted by repository-relative path, and a warning-only CLI invocation returns `0`. Test local output as `WARNING: <path>: last_verified <date> is 91 days old (threshold: 90)` and GitHub Actions output as `::warning file=<path>::last_verified <date> is 91 days old (threshold: 90)` when `GITHUB_ACTIONS=true`.

- [ ] **Step 2: Run the tests and verify failure**

Run:

```bash
set -euo pipefail
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: fail because `StaleWarning`, `report_stale`, and the reporting CLI flags do not exist.

- [ ] **Step 3: Implement non-blocking stale reporting**

Add an immutable `StaleWarning` dataclass containing `path: Path`, `last_verified: date`, `age_days: int`, and `threshold_days: int`. Inspect arc42 front matter and Mermaid metadata. A document is stale only when `(as_of - last_verified).days > 90`. Require `--as-of` with `--report-stale` for reproducible local and CI runs; do not use wall-clock time inside `report_stale`. Print the exact local or GitHub annotation form from Step 1. Return `0` when warnings are the only findings; return non-zero only for malformed/future dates or ordinary blocking validation errors.

- [ ] **Step 4: Document and verify reporting**

Document the 90-day report-only threshold and command in `architecture/README.md`. Run:

```bash
set -euo pipefail
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
python3 architecture/scripts/validate_architecture.py --root . --report-stale --as-of 2026-09-01
```

Expected: tests pass; any stale documents print deterministic warnings and the command exits `0`.

- [ ] **Step 5: Commit stale reporting**

```bash
set -euo pipefail
task9_paths=(
  architecture/README.md
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
)
git add -- "${task9_paths[@]}"
expected_staged="$(mktemp)"
actual_staged="$(mktemp)"
trap 'rm -f "$expected_staged" "$actual_staged"' EXIT
printf '%s\n' "${task9_paths[@]}" | LC_ALL=C sort >"$expected_staged"
git diff --cached --name-only --no-renames -- "${task9_paths[@]}" | LC_ALL=C sort >"$actual_staged"
diff -u "$expected_staged" "$actual_staged"
git commit --only -m "feat: report stale architecture verification" -- "${task9_paths[@]}"
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

- [ ] **Step 0: Preflight the exact Task 10 write scope**

```bash
set -euo pipefail
task10_paths=(
  .github/pull_request_template.md
  .github/workflows/architecture-docs.yml
  architecture/README.md
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
)
owned_state="$(git status --porcelain=v1 --untracked-files=all --ignored=matching -- "${task10_paths[@]}")"
if [[ -n "$owned_state" ]]; then
  printf '%s\n' 'Task 10 owned paths overlap existing work; stop and coordinate without altering it:' "$owned_state" >&2
  exit 1
fi
```

Expected: no output. Run this before Step 1 edits; non-owned state is allowed. This exact array is repeated unchanged in Step 7.

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
- `validate_pr_body` parses exactly one canonical `## Architecture impact` section, requires each checkbox prompt and field label exactly once inside that section, and rejects a second canonical section or any duplicate canonical checkbox/field literal elsewhere in prose. Fenced examples, inline-code spans, and HTML comments containing complete fake sections or duplicate labels are masked and do not satisfy or invalidate the real section. Add a bypass fixture containing `` `Related ADRs:` `- [x] No architecture impact` `` outside an otherwise valid canonical section and prove those inline-code literals are ignored rather than counted or accepted.
- `validate_workflow_contract` requires the explicit pull-request event set `types: [opened, synchronize, reopened, edited, ready_for_review]` with no path filter, `push` to `master`, top-level `permissions: contents: read`, checkout `fetch-depth: 0`, the PR-body step guarded by `github.event_name == 'pull_request'`, unit tests, repository validation, direct executable diagram rendering whose script owns its locked install plus npm/Puppeteer/XDG state, stale reporting with an explicit UTC date, and event-aware edge-by-edge whitespace/ADR checking. It rejects any workflow command that installs into the repository tooling prefix, depends on repository-local `architecture/tooling/node_modules/`, or permits npm, Puppeteer, or XDG writes outside the render invocation's owned temporary root.
- Workflow validation strips YAML comments outside quoted scalars and uses indentation-aware mapping/sequence parsing for top-level `on` and `permissions`, the validation job, and its ordered step mappings. A comment, block-scalar example, wrong job, or same-spelled key nested at the wrong structural location cannot satisfy a required trigger, permission, guard, checkout input, or run step. Duplicate top-level/job keys and structurally misplaced literals fail.
- A workflow fixture with a missing `edited` event, `pull_request.paths`, `contents: write`, missing PR-body checking, a non-executable/cache-leaking render-script contract, a repository-local Mermaid installation, bare `git diff --check`, only one endpoint `git diff --check "$base_sha..$head_sha"`, known-base PR/push logic that omits either all-parent per-edge whitespace or `--adr-edge-base-ref`/`--adr-edge-head-ref`, a first-parent-only merge walk, root handling that skips `empty_tree -> root`, or `git diff-tree --check --root "$GITHUB_SHA"` as the unavailable-push-base fallback fails with a focused diagnostic.
- Add integration-style Git fixtures for PR known range, known-base push, and unavailable-base reachable-history modes. In each, introduce trailing whitespace in one commit and remove it in a later clean commit; prove an endpoint-only `git diff --check <base> <head>` is silent while the required parent-to-child enumeration reports the introduction edge. Add a root fixture whose root commit introduces trailing whitespace and prove `git diff --check "$empty_tree" "$root"` reports it. Add a merge fixture whose result is clean relative to the first parent but introduces trailing whitespace relative to only the second parent; assert the second-parent-to-merge check fails and a first-parent-only walk misses it. Exercise the same helper/contract used by the workflow for all three event modes.
- Add a behind-base Git fixture: create a common commit, a feature head from it, and then advance the base branch separately. Assert `git merge-base "$base_sha" "$head_sha"` is the common commit, not the newer base tip, and assert the PR range helper validates and returns `merge_base..head_sha`. Include an Accepted ADR mutation on the feature branch and prove the same merge-base/head pair makes the edge-range ADR validator reject it.
- Add CI-range fixtures for both PR and push ranges with exact histories: `(base) -> (introduce Proposed) -> (Accept) -> (mutate rationale)`, and `(base containing Proposed) -> (Accept) -> (mutate rationale)`. Assert `--adr-edge-base-ref <base> --adr-edge-head-ref <head>` rejects the exact accepted-to-mutated edge even when `--adr-base-ref <base> --adr-head-ref <head>` cannot detect it. Add `(base) -> (introduce Accepted) -> (mutate rationale)` and assert only the introduction edge is exempt. Add a merge whose Accepted ADR differs only from its second parent and assert the range rejects that second-parent edge, proving all-parent rather than first-parent semantics.

- [ ] **Step 2: Run tests and verify failure**

Run:

```bash
set -euo pipefail
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
```

Expected: fail because `validate_pr_body`, `validate_workflow_contract`, and their registry/CLI behavior do not exist.

- [ ] **Step 3: Implement template, PR-body, and workflow contracts**

Extend `validate_structure` with the literal template prompts. Implement `validate_pr_body(body: str) -> list[str]` by first masking fenced code, inline-code spans delimited by matching backtick runs, and HTML comments while preserving lines, then locating level-two headings and parsing only the single canonical `## Architecture impact` section through the next level-one/two heading or EOF. Require each canonical checkbox prompt and field label exactly once inside it; scan remaining unmasked prose and reject canonical literals outside it. Apply the exact selection and value rules from Step 1. Extend `main` with `--pr-event PATH`: load the standard GitHub event JSON using `json`, read `pull_request.body` as an empty string when null, print deterministic errors, and return non-zero on violations.

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
- run: architecture/scripts/render-diagrams.sh
- run: |
    set -o pipefail
    python3 architecture/scripts/validate_architecture.py --root . --report-stale --as-of "$(date -u +%F)" | tee -a "$GITHUB_STEP_SUMMARY"
```

The final workflow step must be an event-aware shell block. Define one `check_ranged_edges BASE HEAD` helper that enumerates every child in `BASE..HEAD` with `git rev-list --reverse --topo-order --parents`, checks every listed parent with `git diff --check "$parent" "$child"`, and uses `git diff --check "$empty_tree" "$child"` when an enumerated child is a root. It also validates each ADR parent/child edge. On pull requests, read `.pull_request.base.sha` and `.pull_request.head.sha` from `$GITHUB_EVENT_PATH` with `jq -r`, verify both are 40-lowercase-hex existing commits, compute `merge_base="$(git merge-base "$base_sha" "$head_sha")"`, verify the merge base is a 40-lowercase-hex existing commit, retain `git diff --check "$merge_base" "$head_sha"` as an endpoint summary, then call the helper for `merge_base..head_sha`, retain the useful endpoint ADR comparison, and invoke the validator's edge-range CLI over the same range. The all-parent edge walk catches whitespace introduced and later removed as well as changes contributed by either parent of a merge.

On pushes, read `.before`; when it is a non-zero 40-lowercase-hex existing commit and an ancestor of `GITHUB_SHA`, retain `git diff --check "$before_sha" "$GITHUB_SHA"` as an endpoint summary, call the same helper for every child and every parent in `before_sha..GITHUB_SHA`, retain the endpoint ADR comparison, and invoke the edge-range CLI over that same range. Thus a known-base push catches both ADR mutation and whitespace introduced then removed within the push. When `.before` is missing, null, all zeroes, malformed, unavailable, or not an ancestor, compute the canonical empty tree, retain the complete-current-tree `empty_tree -> GITHUB_SHA` summary, and enumerate every reachable commit with all parents in deterministic oldest-first/topological order. Run whitespace and ADR validation for every parent edge; for each reachable root, run `git diff --check "$empty_tree" "$root"` so root-introduced whitespace is not skipped. A new ADR is exempt only on the edge where it is absent from the parent; once its child version is Accepted or Rejected, subsequent edges enforce its applicable protection. A tip-only `git diff-tree --check --root "$GITHUB_SHA"` is forbidden. Checkout uses `fetch-depth: 0`, and a bare working-tree-only `git diff --check` is not the CI contract.

Use this exact step:

```yaml
- name: Check changed-tree whitespace
  shell: bash
  run: |
    set -euo pipefail
    sha_pattern='^[0-9a-f]{40}$'
    empty_tree="$(git hash-object -t tree /dev/null)"
    [[ "$empty_tree" =~ $sha_pattern ]]
    check_ranged_edges() {
      local range_base="$1"
      local range_head="$2"
      local commit_and_parents child parent
      local -a edge_parts
      while read -r commit_and_parents; do
        read -r -a edge_parts <<<"$commit_and_parents"
        child="${edge_parts[0]}"
        [[ "$child" =~ $sha_pattern ]]
        git cat-file -e "$child^{commit}"
        if [[ "${#edge_parts[@]}" -eq 1 ]]; then
          git diff --check "$empty_tree" "$child"
          continue
        fi
        for parent in "${edge_parts[@]:1}"; do
          [[ "$parent" =~ $sha_pattern ]]
          git cat-file -e "$parent^{commit}"
          git diff --check "$parent" "$child"
          python3 architecture/scripts/validate_architecture.py --root . --adr-base-ref "$parent" --adr-head-ref "$child"
        done
      done < <(git rev-list --reverse --topo-order --parents "$range_base..$range_head")
    }
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
      git diff --check "$merge_base" "$head_sha"
      check_ranged_edges "$merge_base" "$head_sha"
      python3 architecture/scripts/validate_architecture.py --root . --adr-base-ref "$merge_base" --adr-head-ref "$head_sha"
      python3 architecture/scripts/validate_architecture.py --root . --adr-edge-base-ref "$merge_base" --adr-edge-head-ref "$head_sha"
      exit 0
    fi
    [[ "$GITHUB_SHA" =~ $sha_pattern ]]
    git cat-file -e "$GITHUB_SHA^{commit}"
    before_sha="$(jq -r '.before // empty' "$GITHUB_EVENT_PATH")"
    zero_sha=0000000000000000000000000000000000000000
    if [[ "$before_sha" =~ $sha_pattern ]] && [[ "$before_sha" != "$zero_sha" ]] && git cat-file -e "$before_sha^{commit}" 2>/dev/null && git merge-base --is-ancestor "$before_sha" "$GITHUB_SHA"; then
      git diff --check "$before_sha" "$GITHUB_SHA"
      check_ranged_edges "$before_sha" "$GITHUB_SHA"
      python3 architecture/scripts/validate_architecture.py --root . --adr-base-ref "$before_sha" --adr-head-ref "$GITHUB_SHA"
      python3 architecture/scripts/validate_architecture.py --root . --adr-edge-base-ref "$before_sha" --adr-edge-head-ref "$GITHUB_SHA"
    else
      git diff --check "$empty_tree" "$GITHUB_SHA"
      while read -r commit_and_parents; do
        read -r -a edge_parts <<<"$commit_and_parents"
        child="${edge_parts[0]}"
        [[ "$child" =~ $sha_pattern ]]
        git cat-file -e "$child^{commit}"
        if [[ "${#edge_parts[@]}" -eq 1 ]]; then
          git diff --check "$empty_tree" "$child"
          continue
        fi
        for parent in "${edge_parts[@]:1}"; do
          [[ "$parent" =~ $sha_pattern ]]
          git cat-file -e "$parent^{commit}"
          git diff --check "$parent" "$child"
          python3 architecture/scripts/validate_architecture.py --root . --adr-base-ref "$parent" --adr-head-ref "$child"
        done
      done < <(git rev-list --reverse --topo-order --parents "$GITHUB_SHA")
    fi
```

- [ ] **Step 6: Link governance to CI and run the local acceptance gate**

Update `architecture/README.md` with the CI workflow path. Leave ADR-0001 implementation status `Partial` in this commit because the evidence commit hash does not exist yet.

Run:

```bash
set -euo pipefail
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
python3 architecture/scripts/validate_architecture.py --root .
architecture/scripts/render-diagrams.sh
python3 architecture/scripts/validate_architecture.py --root . --checks workflow
git diff --check -- .github/pull_request_template.md .github/workflows/architecture-docs.yml architecture/README.md architecture/scripts/tests/test_validate_architecture.py architecture/scripts/validate_architecture.py
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
set -euo pipefail
task10_paths=(
  .github/pull_request_template.md
  .github/workflows/architecture-docs.yml
  architecture/README.md
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
)
git add -- "${task10_paths[@]}"
expected_staged="$(mktemp)"
actual_staged="$(mktemp)"
trap 'rm -f "$expected_staged" "$actual_staged"' EXIT
printf '%s\n' "${task10_paths[@]}" | LC_ALL=C sort >"$expected_staged"
git diff --cached --name-only --no-renames -- "${task10_paths[@]}" | LC_ALL=C sort >"$actual_staged"
diff -u "$expected_staged" "$actual_staged"
git commit --only -m "ci: enforce architecture documentation contracts" -- "${task10_paths[@]}"
```

### Task 11: Finalize immutable implementation evidence

**Files:**
- Modify: `architecture/adr/0001-manage-architecture-as-versioned-code.md`

**Interfaces:**
- Consumes: the full commit hash produced by Task 10.
- Produces: ADR-0001 with `Implementation status: Complete`, immutable path-bound evidence for the complete implemented framework at the Task 10 commit, and append-only command/result records under `Compliance and verification`.

- [ ] **Step 0: Preflight the exact Task 11 write scope**

```bash
set -euo pipefail
task11_paths=(
  architecture/adr/0001-manage-architecture-as-versioned-code.md
)
owned_state="$(git status --porcelain=v1 --untracked-files=all --ignored=matching -- "${task11_paths[@]}")"
if [[ -n "$owned_state" ]]; then
  printf '%s\n' 'Task 11 owned paths overlap existing work; stop and coordinate without altering it:' "$owned_state" >&2
  exit 1
fi
```

Expected: no output. Run this before capturing evidence or editing the ADR; non-owned state is allowed. This exact array is repeated unchanged in Step 5.

- [ ] **Step 1: Capture and validate the Task 10 commit hash**

Run:

```bash
set -euo pipefail
framework_commit="$(git rev-parse --verify 'HEAD^{commit}')"
printf '%s' "$framework_commit" | grep -Eq '^[0-9a-f]{40}$'
git cat-file -e "$framework_commit^{commit}"
test "$(git show --quiet --format='%s' "$framework_commit")" = "ci: enforce architecture documentation contracts"
```

Expected: a 40-character hash and subject `ci: enforce architecture documentation contracts`.

- [ ] **Step 2: Append complete path-bound evidence without rewriting rationale**

Change only `Implementation status: Partial` to `Implementation status: Complete`. Retain the Task 10 `changed` evidence for its five CI-enforcement paths and append it as the first new implementation-evidence item. Then append one Task 10 `snapshot` item covering every completed framework artifact present in that commit. Define and verify the exact sets before editing the ADR:

```bash
set -euo pipefail
task10_changed_paths=(
  .github/pull_request_template.md
  .github/workflows/architecture-docs.yml
  architecture/README.md
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
)
framework_snapshot_paths=(
  .github/pull_request_template.md
  .github/workflows/architecture-docs.yml
  .gitignore
  ARCHITECTURE.md
  architecture/README.md
  architecture/adr/0001-manage-architecture-as-versioned-code.md
  architecture/adr/0002-centralize-financial-invariants-in-funds-core.md
  architecture/adr/0003-use-signed-integer-minor-units.md
  architecture/adr/0004-use-postgresql-as-the-authoritative-ledger.md
  architecture/adr/0005-use-immutable-journals-and-additive-corrections.md
  architecture/adr/0006-couple-idempotency-and-outbox-to-ledger-commit.md
  architecture/adr/0007-separate-ledger-identity-from-account-addresses.md
  architecture/adr/0008-target-an-eight-gib-single-vm-evidence-suite.md
  architecture/adr/README.md
  architecture/adr/template.md
  architecture/arc42/01-introduction-and-goals.md
  architecture/arc42/02-constraints.md
  architecture/arc42/03-context-and-scope.md
  architecture/arc42/04-solution-strategy.md
  architecture/arc42/05-building-block-view.md
  architecture/arc42/06-runtime-view.md
  architecture/arc42/07-deployment-view.md
  architecture/arc42/08-crosscutting-concepts.md
  architecture/arc42/09-decisions.md
  architecture/arc42/10-quality-requirements.md
  architecture/arc42/11-risks-and-technical-debt.md
  architecture/arc42/12-glossary.md
  architecture/archive/comprehensive-design-migration-inventory.md
  architecture/archive/comprehensive-design-migration-review.md
  architecture/archive/modern-core-banking-comprehensive-design-revised.md
  architecture/archive/proposals/README.md
  architecture/diagrams/README.md
  architecture/diagrams/containers.mmd
  architecture/diagrams/context.mmd
  architecture/diagrams/funds-core-components.mmd
  architecture/diagrams/posting-sequence.mmd
  architecture/diagrams/single-vm-deployment.mmd
  architecture/infrastructure/infra-ubuntu24.04-poc.md
  architecture/proposals/README.md
  architecture/proposals/account-identifiers-and-nip-inbound.md
  architecture/proposals/conventional-deposit-products-and-accrual.md
  architecture/proposals/full-poc-platform.md
  architecture/proposals/non-interest-banking-products.md
  architecture/proposals/production-platform.md
  architecture/proposals/providers-and-reconciliation.md
  architecture/scripts/render-diagrams.sh
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
  architecture/tooling/package-lock.json
  architecture/tooling/package.json
  docs/superpowers/plans/2026-08-30-account-identifiers-and-nip-inbound-implementation.md
  docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md
  docs/superpowers/plans/2026-08-30-conventional-deposit-products-and-accrual-implementation.md
  docs/superpowers/plans/2026-08-30-non-interest-banking-products-implementation.md
  docs/superpowers/plans/2026-09-01-architecture-documentation-and-adr-framework-implementation.md
  services/funds-core/README.md
)
test "${#framework_snapshot_paths[@]}" -eq 56
task10_changed="$(git show --format= --name-only --no-renames "$framework_commit" | LC_ALL=C sort -u)"
for path in "${task10_changed_paths[@]}"; do
  grep -Fqx "$path" <<<"$task10_changed"
done
for path in "${framework_snapshot_paths[@]}"; do
  git cat-file -e "$framework_commit:$path"
done
printf -v changed_entry '%s; ' "${task10_changed_paths[@]}"
changed_entry="${changed_entry%; }"
printf -v snapshot_entry '%s; ' "${framework_snapshot_paths[@]}"
snapshot_entry="${snapshot_entry%; }"
printf -- '- %s changed: %s\n' "$framework_commit" "$changed_entry"
printf -- '- %s snapshot: %s\n' "$framework_commit" "$snapshot_entry"
```

Append those two generated lines, in that order, as an exact suffix under `## Implementation evidence`. The `changed` line proves the five Task 10 paths changed in that commit. The 56-path `snapshot` line is path-bound immutable evidence that the complete root/governance, twelve-file arc42 set, ADR set and templates, proposal set, diagram sources and governance, archive source/inventory/review, infrastructure update, validator/tests/render script, tooling manifests, PR/workflow gate, `.gitignore`, service document, four earlier plans, and this framework implementation plan all exist together in the Task 10 tree; it does not claim Task 10 introduced or changed every snapshot path. Do not put validation commands in `Implementation evidence`.

- [ ] **Step 3: Re-run the complete gate**

Run:

```bash
set -euo pipefail
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
python3 architecture/scripts/validate_architecture.py --root .
python3 architecture/scripts/validate_architecture.py --root . --adr-base-ref "$framework_commit"
architecture/scripts/render-diagrams.sh
git diff --check -- architecture/adr/0001-manage-architecture-as-versioned-code.md
git diff -- architecture/adr/0001-manage-architecture-as-versioned-code.md
```

Expected: all automated checks pass, including validation of every `changed` and `snapshot` path against the Task 10 commit. The git-aware accepted-ADR check accepts the monotonic status transition and append-only evidence suffix; the ADR diff changes only implementation status and appended implementation evidence at this point.

- [ ] **Step 4: Append command/results as compliance evidence and verify immutability**

Only after the unit, repository, accepted-record, isolated-render, and ADR/diff checks in Step 3 succeed, append these exact result records, in this order, as a suffix under `## Compliance and verification`:

```markdown
- `python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v` — PASS (exit 0).
- `python3 architecture/scripts/validate_architecture.py --root .` — PASS (exit 0).
- `architecture/scripts/render-diagrams.sh` — PASS (exit 0; locked Mermaid dependencies plus npm/Puppeteer/XDG state were confined to one invocation-owned temporary root, that root was removed, and every governed source rendered).
```

These are append-only command/result records, not immutable artifact evidence. Re-run the accepted-record and repository checks after the append:

```bash
set -euo pipefail
python3 architecture/scripts/validate_architecture.py --root .
python3 architecture/scripts/validate_architecture.py --root . --adr-base-ref "$framework_commit"
git diff --check -- architecture/adr/0001-manage-architecture-as-versioned-code.md
git diff -- architecture/adr/0001-manage-architecture-as-versioned-code.md
```

Expected: repository and accepted-ADR validation pass. The final diff contains only `Implementation status: Partial -> Complete`, an append-only suffix to `Compliance and verification`, and the two append-only path-bound entries under `Implementation evidence`; accepted rationale, alternatives, consequences, existing relationships, and prior evidence remain byte-stable.

- [ ] **Step 5: Commit evidence finalization**

```bash
set -euo pipefail
task11_paths=(
  architecture/adr/0001-manage-architecture-as-versioned-code.md
)
git add -- "${task11_paths[@]}"
expected_staged="$(mktemp)"
actual_staged="$(mktemp)"
trap 'rm -f "$expected_staged" "$actual_staged"' EXIT
printf '%s\n' "${task11_paths[@]}" | LC_ALL=C sort >"$expected_staged"
git diff --cached --name-only --no-renames -- "${task11_paths[@]}" | LC_ALL=C sort >"$actual_staged"
diff -u "$expected_staged" "$actual_staged"
git commit --only -m "docs: finalize architecture framework evidence" -- "${task11_paths[@]}"
```

## Final Verification

From the repository root, run:

```bash
set -euo pipefail
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v
python3 architecture/scripts/validate_architecture.py --root .
python3 architecture/scripts/validate_architecture.py --root . --report-stale --as-of 2026-09-01
architecture/scripts/render-diagrams.sh
node -e 'const l=require("./architecture/tooling/package-lock.json"); if(l.packages["node_modules/@mermaid-js/mermaid-cli"].version!=="11.16.0") process.exit(1)'
! rg -n 'architecture/tooling/node_modules|npm[[:space:]]+ci[[:space:]]+--prefix[[:space:]]+architecture/tooling' architecture/scripts/render-diagrams.sh .github/workflows/architecture-docs.yml
test -x architecture/scripts/render-diagrams.sh
test "$(git ls-files -s architecture/scripts/render-diagrams.sh | awk '{print $1}')" = 100755
test -z "$(git ls-files 'architecture/tooling/node_modules/**' 'architecture/diagrams/generated/**' 'architecture/diagrams/generated/**/*.svg')"
test "$(wc -l < ARCHITECTURE.md)" -lt 180
base_ref=refs/codex/architecture-docs-framework-base
architecture_base="$(git rev-parse --verify "$base_ref^{commit}")"
printf '%s' "$architecture_base" | grep -Eq '^[0-9a-f]{40}$'
git cat-file -e "$architecture_base^{commit}"
git diff --check "$architecture_base..HEAD"
while read -r commit_and_parents; do
  read -r -a edge_parts <<<"$commit_and_parents"
  child="${edge_parts[0]}"
  printf '%s' "$child" | grep -Eq '^[0-9a-f]{40}$'
  for parent in "${edge_parts[@]:1}"; do
    printf '%s' "$parent" | grep -Eq '^[0-9a-f]{40}$'
    git cat-file -e "$parent^{commit}"
    git diff --check "$parent" "$child"
  done
done < <(git rev-list --reverse --topo-order --parents "$architecture_base..HEAD")
python3 architecture/scripts/validate_architecture.py --root . --adr-edge-base-ref "$architecture_base" --adr-edge-head-ref HEAD
task10_commit="$(git rev-parse --verify 'HEAD^')"
python3 architecture/scripts/validate_architecture.py --root . --adr-base-ref "$task10_commit" --adr-head-ref HEAD
framework_paths=(
  .github/pull_request_template.md
  .github/workflows/architecture-docs.yml
  .gitignore
  ARCHITECTURE.md
  architecture/README.md
  architecture/adr/0001-manage-architecture-as-versioned-code.md
  architecture/adr/0002-centralize-financial-invariants-in-funds-core.md
  architecture/adr/0003-use-signed-integer-minor-units.md
  architecture/adr/0004-use-postgresql-as-the-authoritative-ledger.md
  architecture/adr/0005-use-immutable-journals-and-additive-corrections.md
  architecture/adr/0006-couple-idempotency-and-outbox-to-ledger-commit.md
  architecture/adr/0007-separate-ledger-identity-from-account-addresses.md
  architecture/adr/0008-target-an-eight-gib-single-vm-evidence-suite.md
  architecture/adr/README.md
  architecture/adr/template.md
  architecture/arc42/01-introduction-and-goals.md
  architecture/arc42/02-constraints.md
  architecture/arc42/03-context-and-scope.md
  architecture/arc42/04-solution-strategy.md
  architecture/arc42/05-building-block-view.md
  architecture/arc42/06-runtime-view.md
  architecture/arc42/07-deployment-view.md
  architecture/arc42/08-crosscutting-concepts.md
  architecture/arc42/09-decisions.md
  architecture/arc42/10-quality-requirements.md
  architecture/arc42/11-risks-and-technical-debt.md
  architecture/arc42/12-glossary.md
  architecture/archive/comprehensive-design-migration-inventory.md
  architecture/archive/comprehensive-design-migration-review.md
  architecture/archive/modern-core-banking-comprehensive-design-revised.md
  architecture/archive/proposals/README.md
  architecture/diagrams/README.md
  architecture/diagrams/containers.mmd
  architecture/diagrams/context.mmd
  architecture/diagrams/funds-core-components.mmd
  architecture/diagrams/posting-sequence.mmd
  architecture/diagrams/single-vm-deployment.mmd
  architecture/infrastructure/infra-ubuntu24.04-poc.md
  architecture/modern-core-banking-comprehensive-design-revised.md
  architecture/proposals/README.md
  architecture/proposals/account-identifiers-and-nip-inbound.md
  architecture/proposals/conventional-deposit-products-and-accrual.md
  architecture/proposals/full-poc-platform.md
  architecture/proposals/non-interest-banking-products.md
  architecture/proposals/production-platform.md
  architecture/proposals/providers-and-reconciliation.md
  architecture/scripts/render-diagrams.sh
  architecture/scripts/tests/test_validate_architecture.py
  architecture/scripts/validate_architecture.py
  architecture/tooling/package-lock.json
  architecture/tooling/package.json
  docs/superpowers/plans/2026-08-30-account-identifiers-and-nip-inbound-implementation.md
  docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md
  docs/superpowers/plans/2026-08-30-conventional-deposit-products-and-accrual-implementation.md
  docs/superpowers/plans/2026-08-30-non-interest-banking-products-implementation.md
  docs/superpowers/plans/2026-09-01-architecture-documentation-and-adr-framework-implementation.md
  services/funds-core/README.md
)
test "${#framework_paths[@]}" -eq 57
git diff --quiet -- "${framework_paths[@]}"
git diff --cached --quiet -- "${framework_paths[@]}"
scoped_status="$(git status --short -- "${framework_paths[@]}")"
test -z "$scoped_status"
git status --short --branch
git update-ref -d "$base_ref" "$architecture_base"
test ! git show-ref --verify --quiet "$base_ref"
```

Expected results:

- Validator unit tests report zero failures and zero errors.
- Repository validation prints `architecture validation passed`.
- Stale verification is reported at a 90-day threshold and warnings alone exit zero.
- All five required Mermaid sources render successfully after the render script copies the exact manifests and confines the locked CLI install, npm cache, Puppeteer browser/cache assets, and XDG cache/config/data to one invocation-owned temporary root; any additional governed sources also satisfy metadata, backlink, and render checks. The trap removes only that root, never caller-owned output. No repository, `~/.npm`, or `~/.cache` state is created or changed, and a pre-existing `architecture/tooling/node_modules/` is never read, written, or deleted and remains excluded/nonblocking.
- The render script has executable mode `100755` and is directly invocable locally and in CI.
- `package-lock.json` resolves `@mermaid-js/mermaid-cli` exactly to `11.16.0`; no `node_modules` or output below `architecture/diagrams/generated/` is tracked. SVGs elsewhere remain permitted when they are explicitly classified, approved architecture derivatives.
- Every parent-to-child edge for every implementation commit in `architecture_base..HEAD` is whitespace-clean, including every parent edge of merge commits: twelve commits total, comprising eleven task-ending commits plus Task 8's separate independent-review evidence commit. The endpoint range check remains as an additional summary check.
- The durable baseline ref resolves to a verified commit before its final range check and is deleted only after all other verification succeeds.
- There is no framework-owned working-tree or index diff, and scoped status contains no framework artifacts. The unscoped final status is informational only: unrelated tracked, staged, untracked, and user state is left untouched and does not fail framework acceptance.
- `ARCHITECTURE.md` is under 180 lines.
- All twelve arc42 files are `current` or `deprecated`; none is `proposed`.
- ADR identifiers are contiguous from `0001` through `0008`.
- Every local ADR evidence hash is paired with exact repository paths and `changed` or `snapshot` mode; each hash is a commit, every path exists in its commit tree, every `changed` path is in the commit's root/merge-aware changed-path set, and `snapshot` is described only as observed state. Every stable GitHub pull-request URL matches the normalized current `origin` owner/repository.
- Every foundational ADR names existing affected arc42 paths, and ADR/arc42 references are reciprocal in both directions.
- Proposed ADRs may remain Proposed unchanged or with substantive, relationship/evidence, and implementation-status revisions; transition to Accepted or Rejected establishes the protected baseline. Accepted/Superseded/Deprecated protections remain permanent, and every Rejected record remains at the same path with byte-for-byte identical content on every later edge; rejected mutation, deletion, and rename are blocked.
- Every diagram's declared arc42 section contains a link back to that exact diagram source.
- Every governed proposal identity exists exactly once at its active or archive same-basename location, and its permanent registry anchor points to that sole record. Active records are non-terminal; archived implemented/rejected/superseded records carry their status-specific implementation status/replacement plus closure evidence and reciprocal ADR/plan links. The three unimplemented plans link stable proposal identities, while the implemented accounting-kernel plan links current arc42 sections and retrospective ADRs without a proposal backlink.
- Every direct plan-to-ADR link has the matching ADR `Related implementation plans` backlink and every such ADR backlink has a direct plan link, including the exact ADR-0001/framework pair and the accounting-kernel, account-identifier, conventional-deposit, and non-interest plan mappings.
- The granular migration inventory covers every material heading under top-level sections `1` through `27`, has unique stable source keys, zero unresolved rows, and existing required destination anchors, exact source-key backlinks, and evidence paths.
- Every inventory row has non-empty exact material-block coverage and disposition rationale; each heading-relative block is covered exactly once, including after archive cutover.
- The global multiset of governed `migration-source` marker occurrences equals the active inventory's deduplicated `(path, anchor, source-key)` destination set exactly; no orphan, extra, duplicate, wrong-section, or superseded provisional marker remains.
- The independent review record names distinct reviewer and implementer identities, `APPROVED`, zero unresolved rows, an existing reviewed pre-cutover commit, and the exact inventory blob that remains current after cutover.
- The old comprehensive-design path is absent and its archived copy is explicitly non-authoritative.
- Pull-request `opened`, `synchronize`, `reopened`, `edited`, and `ready_for_review` events are all gated. PR and known-base push modes enumerate every ranged child and run whitespace plus ADR validation against every parent, so whitespace introduced and later removed and second-parent merge changes still fail; endpoint checks remain summaries only. An unavailable push base retains the empty-tree-versus-current-tree summary, enumerates every reachable child and all parents for the same checks, and explicitly checks `empty-tree -> root` for every reachable root commit.
- No local generated output below `architecture/diagrams/generated/` or `node_modules` content is tracked; classified SVG derivatives elsewhere are not rejected globally.
