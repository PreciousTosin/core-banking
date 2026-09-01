# Comment Convention ADR Registration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Register the repository code comment convention, and the build gate that now enforces it, as retrospective ADR-0009 — accepted from birth, with historical evidence that predates the record, passing every architecture check including the git-history edge checks that police direct `Accepted` introduction.

**Architecture:** One new file, `architecture/adr/0009-adopt-an-enforced-code-comment-convention.md`, plus a reciprocal registration in `architecture/arc42/09-decisions.md`. Both land in **one commit**, because the validator's ADR ↔ arc42 reciprocity check fails on any tree where an ADR links the decisions index but the index does not list it. The record is `Accepted`/`Retrospective: Yes`, which the validator permits only when at least one evidence commit is a strict ancestor of the commit that introduces the record — all eight evidence lines below already satisfy that.

**Tech Stack:** Python 3.12+ (`architecture/scripts/validate_architecture.py`, standard library only), git, Markdown.

**Governing documents:** `architecture/adr/README.md` (lifecycle, statuses, evidence forms) and `architecture/adr/template.md` (record shape). The machine authority is `architecture/scripts/validate_architecture.py`; where prose and validator disagree, the validator wins and this plan follows it.

**Base commit:** `aa7f055`, the merge of local `master` (`dfc7521`, which landed the ADR framework) into `worktree-comment-convention-enforcement-plan`.

## Global Constraints

- Every command runs inside the checkout the executor was given — main checkout or worktree. Anchor each block with `cd "$(git rev-parse --show-toplevel)"`; never a literal absolute path.
- **The ADR and the decisions-index edit are one commit.** Splitting them produces an intermediate tree that fails `validate_adrs` reciprocity. There is no valid ordering of two commits.
- **If validation fails after the ADR is committed, fix it with `git commit --amend`, never a follow-up commit.** The record is `Accepted` from birth, and the validator freezes `Context`, `Decision drivers`, `Considered options`, `Decision` and `Consequences` byte-for-byte across every subsequent commit edge. A follow-up commit that edits those sections is an immutability violation that cannot be undone except by rewriting history.
- Evidence hashes are **40 lowercase hex characters**. Uppercase fails the regex.
- Relationship fields are separated by exactly `"; "` — one semicolon, one space. `;` alone or `;  ` fails.
- Items in `Related architecture sections` and `Related implementation plans` must be **only** a Markdown link, with no other text in the item.
- Do not add an `<a id="...">` anchor or a `<!-- migration-source: NN.NN -->` comment. Those exist in ADRs 0002-0008 as migration-inventory artefacts; a genuinely new ADR must not carry them, and ADR-0001 does not.
- Do not add section headings beyond the ten the template requires. Any extra heading is not in the validator's mutable-section allowlist, so it would be frozen byte-for-byte forever.
- The ADR number must stay contiguous. `0001`-`0008` exist, so this record is `0009`; creating `0010` while `0009` is absent fails the whole directory.
- No source, build, or funds-core change belongs in this plan. `git diff` on `services/` must be empty at the end.

## Verified inputs

Everything below was measured against the tree at `aa7f055` before this plan was written, so the executor is not copying unchecked strings.

| Input | Verified |
|---|---|
| Next free ADR number | `0009` (`0001`-`0008` present and contiguous) |
| Filename vs title | `validate_architecture._kebab_title("Adopt an enforced code comment convention")` returns `adopt-an-enforced-code-comment-convention`, matching `0009-adopt-an-enforced-code-comment-convention.md` exactly |
| All 8 evidence lines | Pass the validator's grammar; every commit resolves; every path exists in that commit's tree; every `changed:` path is genuinely in that commit's diff against its parents |
| Evidence predates the record | All 8 commits are ancestors of `HEAD`, so each is a strict ancestor of the future ADR commit — the condition `_qualified_historical_introduction` requires |
| Repository baseline | `python3 architecture/scripts/validate_architecture.py --root .` prints `architecture validation passed` at `aa7f055` |
| funds-core gate | `mise exec java@25 -- ./mvnw checkstyle:check` is green at `aa7f055`, unaffected by the master merge |
| `origin` | `git@github.com:PreciousTosin/core-banking.git` — a GitHub origin, so PR-URL evidence *would* be accepted; this plan uses local hash evidence anyway, because no PR exists yet |

## Decisions

Settled before writing. Do not re-open during execution.

- **D1 — One commit for the ADR and the index.** Forced by the reciprocity check, not a style preference.
- **D2 — `Status: Accepted`, `Retrospective: Yes`.** The convention was adopted, applied to 77 files, and mechanically enforced before this record existed; the record documents a decision already in force. This is exactly the case the framework's retrospective path is for, and the evidence satisfies its strict-ancestor test.
- **D3 — `Related architecture sections` links only the decisions index.** Linking `02-constraints.md` as well would be defensible, but every arc42 file linked must also list `ADR-0009` in its own `related_adrs` frontmatter, and the comment convention is a contributor-process rule rather than a runtime constraint on the system. Keep the reciprocal surface to one file.
- **D4 — `Implementation status: Complete`, with the scope bounded in the `Scope` field.** The decision as scoped — a documented convention plus mechanized enforcement of its mechanizable subset in the only module that has code — is fully delivered. The limits (shell scripts, future modules, and the judgement rules a tool cannot check) are stated in `### Negative` rather than hidden behind a softer status.
- **D5 — Amend, never follow up.** See Global Constraints.

### A note on the ADR threshold

`architecture/adr/README.md` sets the bar at material changes to boundaries, invariants, contracts, or deliberately accepted debt, and explicitly excludes "documentation corrections". A comment convention sits near that line, and this record is being created because the maintainer asked for it. The case for it being above the line: the convention binds every contributor in every language, and its enforcement now **fails the build**, which makes it a repository-wide contract with a verification boundary rather than a documentation preference. The plan states this openly in the ADR's `Context` rather than pretending the threshold question does not arise.

## File Structure

- Create `architecture/adr/0009-adopt-an-enforced-code-comment-convention.md` — the record. Nothing else in the repository states this decision.
- Modify `architecture/arc42/09-decisions.md` — add `ADR-0009` to the `related_adrs` frontmatter (machine-checked) and a bullet to the index list (convention, not machine-checked, but the list is the human entry point and an index missing a record is exactly the rot this framework exists to prevent).

---

### Task 1: Write ADR-0009 and register it in the decisions index

**Files:**
- Create: `architecture/adr/0009-adopt-an-enforced-code-comment-convention.md`
- Modify: `architecture/arc42/09-decisions.md`

**Interfaces:**
- Consumes: the eight evidence commits listed in Step 1; the ADR framework at `dfc7521`.
- Produces: `ADR-0009`, referenced by later work that touches the comment convention.

- [ ] **Step 1: Create the ADR**

Create `architecture/adr/0009-adopt-an-enforced-code-comment-convention.md` with exactly this content. The thirteen metadata fields must stay in this order, and the ten headings must stay in this order; both are checked as exact sequences.

```markdown
# ADR-0009: Adopt an enforced code comment convention

- Status: Accepted
- Retrospective: Yes
- Decision date: 2026-09-01
- Deciders: Repository maintainers
- Scope: Source comment practice in every language, and mechanized enforcement of its checkable subset in services/funds-core
- Implementation status: Complete
- Related proposals: None
- Related implementation plans: [Comment convention enforcement plan](../../docs/superpowers/plans/2026-09-01-comment-convention-enforcement-implementation.md)
- Related pull requests: None
- Related commits: None
- Related architecture sections: [Decisions index](../arc42/09-decisions.md)
- Supersedes: None
- Superseded by: None

## Context

The accounting kernel encodes rules that its code cannot state: the sign
convention, the chart-before-book lock order, the boundary between the
TYPED_V2 and V004_OPAQUE hash schemes, why `Long.MIN_VALUE` is rejected at
admission so that every posting is exactly reversible. A reader who has only
the code can see what happens and not why it must. The repository had no
stated rule for recording that, so comments were absent where they mattered
and, where present, often restated the line below them.

A convention that lives only in review memory decays: it binds whoever
remembers it, and silently stops binding once they stop reviewing. The
repository's own threshold for a decision record excludes documentation
corrections, and a comment style would normally sit below it. This decision is
recorded because it is not a style preference: it is a contract binding every
contributor in every language, and it now fails the build.

## Decision drivers

- Record the intent and invariants that the code cannot express, at the point
  where a reader needs them.
- Keep comments that restate their code out of the tree; they consume review
  attention and rot into lies.
- Make the rule verifiable where a tool can judge it, and explicitly leave the
  rest to review rather than pretending a tool can judge it.
- Bind the rule to the build, so it cannot quietly stop applying.

## Considered options

- Leave comment practice to individual judgement; this is the state that produced absent and restating comments.
- Require Javadoc on every method; this manufactures comments that restate the signature, which is the failure being solved.
- State the convention in a document and enforce only its mechanizable subset in the build, leaving judgement to review.

## Decision

Source comments record why, not what. The convention is
`docs/conventions/code-comments.md` and applies to every language in the tree.
Every type that is not private carries a purpose comment; methods get Javadoc
only when the name does not carry the contract; each migration opens with a
header block stating what it changes relative to the previous version; work
items live in the tracker, never in source.

The mechanizable subset runs as Checkstyle in the `services/funds-core` build,
bound to `validate` so it fails before the test gate. Whether a comment
restates its code, and whether its claim is true, are not mechanizable and
remain the reviewer's responsibility.

## Consequences

### Positive

Invariants are documented where they are used rather than in a document a
reader must know to open. A missing type comment, a malformed summary, a
migration without a header, or a work item left in source fails the build in
seconds instead of reaching review. The convention cannot decay silently,
because the gate is part of the same build that runs the financial test suite.

### Negative

A new type cannot merge without a purpose comment, including in tests. The
Checkstyle version is pinned in the POM because the plugin's bundled 9.3
cannot parse Java 25, so the toolchain carries a version that must be
maintained. Enforcement covers Java and SQL in `services/funds-core` only:
shell scripts, and any module added later, are governed by the convention but
not yet by a gate.

### Risks

An author can satisfy the type rule with an empty or restating comment, which
is precisely the failure the convention targets and precisely what a tool
cannot detect. The summary rules reject empty and malformed Javadoc, and the
pull-request checklist names the restating case, but review remains the only
real defence. A rule set that grows past what the module already satisfies
would turn the gate into noise; rules are therefore measured at zero
violations before being enabled.

## Compliance and verification

- `mise exec java@25 -- ./mvnw checkstyle:check` in `services/funds-core` reports `You have 0 Checkstyle violations.` and `BUILD SUCCESS`.
- Each of the eight enabled rules was proven load-bearing by planting a violation, observing the build fail with that rule named, and reverting.
- `mise exec java@25 -- ./mvnw clean verify` passes with `Tests run: 254, Failures: 0, Errors: 0`, with the `check-comment-conventions` execution in the same build.
- The convention document and the shipped ruleset were cross-checked in both directions: every rule named in the document exists in the configuration, and every rule in the configuration is named in the document.

## Implementation evidence

- 24d2b4b48b0c7ba9a9697dea6baf16a1870b7916 changed: AGENTS.md; CLAUDE.md; docs/conventions/code-comments.md
- 912f4e9fec37e5c73d7e368e485f08719fb43e97 changed: docs/conventions/code-comments.md
- c49c3aaf9041f58ecc01eb60df184e25e20b89c8 snapshot: services/funds-core/src/main/java/com/corebanking/funds/application/PostingService.java; services/funds-core/src/main/resources/db/migration/V006__governed_chart_rotation.sql
- b4cf2aa2d002a720838dadb0f13c884336ca9984 changed: services/funds-core/config/checkstyle/checkstyle.xml; services/funds-core/pom.xml
- 655ed137edfa3f11a04f5f4c81003303eac57c25 changed: services/funds-core/config/checkstyle/checkstyle.xml; services/funds-core/pom.xml
- dfb8cebf9fa31bded85cd4da7694b1e7bffea710 changed: services/funds-core/src/test/java/com/corebanking/funds/testsupport/ReferenceLedgerModel.java
- 9f56d06bfd2f2c84a6c5c5ad1ace39114493bbf9 changed: services/funds-core/pom.xml
- f70e19610dc12dfe3748b29e70dbebac3c232740 changed: AGENTS.md; docs/conventions/code-comments.md
```

- [ ] **Step 2: Add ADR-0009 to the decisions index frontmatter**

This is the half the validator checks. In `architecture/arc42/09-decisions.md`, extend the `related_adrs` list:

```yaml
related_adrs:
  - ADR-0001
  - ADR-0002
  - ADR-0003
  - ADR-0004
  - ADR-0005
  - ADR-0006
  - ADR-0007
  - ADR-0008
  - ADR-0009
```

Leave `last_verified: 2026-09-01` as it is. Do not set it to a future date: `--report-stale` fails a build on a `last_verified` that is in the future, while a stale past date is only a warning.

- [ ] **Step 3: Add the index bullet**

Append to the list at the end of `architecture/arc42/09-decisions.md`:

```markdown
- [ADR-0009: Adopt an enforced code comment convention](../adr/0009-adopt-an-enforced-code-comment-convention.md)
```

The validator does not check this list — only the frontmatter drives reciprocity — but an index that omits a record is the decay this framework exists to prevent, and the link itself is checked for existence by `validate_links`.

- [ ] **Step 4: Validate before committing**

```bash
cd "$(git rev-parse --show-toplevel)"
python3 architecture/scripts/validate_architecture.py --root .
```

Expected: `architecture validation passed`, exit 0.

If it reports `does not list ADR-0009`, Step 2 was missed. If it reports `does not link back to this exact architecture section`, the ADR's `Related architecture sections` does not point at `09-decisions.md`. If it reports a heading or field error, compare against the exact block in Step 1 — the field order and heading order are checked as sequences, not as sets.

- [ ] **Step 5: Confirm the change is only these two files, then commit both together**

```bash
cd "$(git rev-parse --show-toplevel)"
git status --porcelain
```

Expected exactly:

```
 M architecture/arc42/09-decisions.md
?? architecture/adr/0009-adopt-an-enforced-code-comment-convention.md
```

```bash
cd "$(git rev-parse --show-toplevel)"
git add architecture/adr/0009-adopt-an-enforced-code-comment-convention.md architecture/arc42/09-decisions.md
git commit -m "Register the code comment convention as ADR-0009"
```

One commit. See Global Constraints for why.

---

### Task 2: Prove the retrospective introduction is legal

The structural run in Task 1 Step 4 says nothing about whether a *new* record may be born `Accepted`. That rule lives in the git-history edge checks, which need a committed record to compare against its parent. This task is the real gate for a retrospective ADR.

**Files:** none — verification only.

- [ ] **Step 1: Endpoint check**

```bash
cd "$(git rev-parse --show-toplevel)"
python3 architecture/scripts/validate_architecture.py --root . \
  --adr-base-ref HEAD~1 --adr-head-ref HEAD
```

Expected: `architecture validation passed`.

A failure naming the introduction rule means the record did not qualify as a historical introduction. The qualification is all of: `Retrospective: Yes`; no malformed evidence line anywhere in the section; the whole evidence block valid; and at least one evidence commit that is a **strict ancestor** of the commit that introduced the record. An evidence commit equal to the introducing commit is explicitly skipped, so evidence must predate the record — all eight lines in Task 1 do.

- [ ] **Step 2: Walk every edge on the branch**

```bash
cd "$(git rev-parse --show-toplevel)"
python3 architecture/scripts/validate_architecture.py --root . \
  --adr-edge-base-ref "$(git merge-base master HEAD)" --adr-edge-head-ref HEAD
```

Expected: `architecture validation passed`. This walks each commit edge from the merge base to `HEAD`, which is what CI does; the endpoint check in Step 1 alone would miss a violation introduced and then papered over mid-branch.

- [ ] **Step 3: If either check failed, amend — do not add a commit**

```bash
cd "$(git rev-parse --show-toplevel)"
# edit the ADR, then:
git add architecture/adr/0009-adopt-an-enforced-code-comment-convention.md architecture/arc42/09-decisions.md
git commit --amend --no-edit
```

Then re-run Steps 1 and 2. A follow-up commit that edits `Context`, `Decision drivers`, `Considered options`, `Decision` or `Consequences` of an `Accepted` record is an immutability violation, and the only cure is rewriting history — which is worse than amending now.

---

### Task 3: Full gate

- [ ] **Step 1: The validator's own test suite**

```bash
cd "$(git rev-parse --show-toplevel)"
python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py'
```

Expected: `OK`. This does not test the new ADR; it proves the validator you just trusted is itself intact.

- [ ] **Step 2: Every check, plus the staleness pass CI runs**

```bash
cd "$(git rev-parse --show-toplevel)"
python3 architecture/scripts/validate_architecture.py --root .
python3 architecture/scripts/validate_architecture.py --root . --checks tooling
python3 architecture/scripts/validate_architecture.py --root . --report-stale --as-of "$(date -u +%F)"
```

Expected: `architecture validation passed` three times. The third may print `WARNING:`/`::warning` lines for arc42 files whose `last_verified` is more than 90 days old; warnings do not fail the run. A `last_verified` in the future does fail it.

- [ ] **Step 3: Nothing outside the two files moved**

```bash
cd "$(git rev-parse --show-toplevel)"
git diff dfc7521..HEAD --stat -- services/ docs/conventions/ AGENTS.md
git status --porcelain
```

Expected: the `services/` and convention diffs contain only what the enforcement branch already landed before this plan started — this plan adds nothing there — and a clean status.

- [ ] **Step 4: The funds-core gate is untouched**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
mise exec java@25 -- ./mvnw checkstyle:check
```

Expected: `BUILD SUCCESS`, `You have 0 Checkstyle violations.` This plan changes no Java, SQL, or POM, so a failure here means something unrelated broke and must be understood before merging.

---

## When the pull request is opened

The repository validates the PR body itself on `pull_request` events. The description must contain exactly one `## Architecture impact` section with exactly one of its two canonical checkboxes ticked. Because this change adds an ADR, tick "Architecture changed; linked below" and fill all five fields, none empty, at least one of the first four not `None`, and `Verification evidence:` never `None`. Use `.github/pull_request_template.md` verbatim as the starting point and fill:

- `Related ADRs:` ADR-0009
- `Current-state arc42 sections changed:` architecture/arc42/09-decisions.md
- `Proposals implemented, invalidated, or superseded:` None
- `Diagrams changed:` None
- `Verification evidence:` the three validator commands from Task 3 Step 2 and the edge checks from Task 2

## Rollback

Before the record is merged, `git revert` or an amend removes it cleanly; nothing else references `ADR-0009`.

After it is merged, it cannot be deleted or renamed: `Accepted` records are permanent, and the number cannot be reused because numbering must stay contiguous. A reversal is a **new** ADR that supersedes 0009, with `Superseded by` appended to 0009's relationship field — that field is append-only, which the lifecycle permits. Removing the enforcement itself is a separate matter from the record and is covered by the enforcement plan's own rollback section.

## Risks

| Risk | Handling |
|---|---|
| The record is born `Accepted` and its core sections freeze on the next commit | Task 2 runs the edge checks immediately, and Step 3 amends rather than appending. Nothing is pushed until both checks pass |
| A naive structural-only run gives false confidence | Task 2 exists precisely because `validate_architecture.py --root .` alone cannot see the introduction rule |
| Committing the ADR without the index edit | D1 and Task 1 Step 5 keep them in one commit; Step 4 catches the mismatch before any commit exists |
| Evidence that looks right but is not | All eight lines were checked against the validator's own grammar and against each commit's real diff before this plan was written |
| An extra heading added for readability | Global Constraints forbid it: extra headings fall outside the mutable-section allowlist and freeze forever |

## Out of Scope

Changing the enforcement itself; adding a shell-script header check or fixing the missing header in `services/funds-core/scripts/prod-runtime-smoke.sh` (a separate one-comment change); linking ADR-0009 from `02-constraints.md` or any other arc42 section (D3); and opening the pull request, which is the maintainer's step.
