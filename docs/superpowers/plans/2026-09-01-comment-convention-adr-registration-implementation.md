# Comment Convention ADR Registration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Register the repository code comment convention, and the build gate that now enforces it, as retrospective ADR-0009 — accepted from birth, with historical evidence that predates the record, passing every architecture check including the git-history edge checks that police direct `Accepted` introduction.

**Architecture:** One new file, `architecture/adr/0009-adopt-an-enforced-code-comment-convention.md`, plus two reciprocal registrations: `ADR-0009` in `architecture/arc42/09-decisions.md`, and a backlink to the record in the enforcement plan it names. All three land in **one commit**, because the validator checks both pairings against the tree as it stands: an ADR that links the decisions index while the index does not list it fails, and an ADR that names an implementation plan which does not link back fails. The record is `Accepted`/`Retrospective: Yes`, which the validator permits only when at least one evidence commit is a strict ancestor of the commit that introduces the record — all eight evidence lines below already satisfy that.

**Tech Stack:** Python 3.12+ (`architecture/scripts/validate_architecture.py`, standard library only), git, Markdown.

**Governing documents:** `architecture/adr/README.md` (lifecycle, statuses, evidence forms) and `architecture/adr/template.md` (record shape). The machine authority is `architecture/scripts/validate_architecture.py`; where prose and validator disagree, the validator wins and this plan follows it.

**Base commit:** `dac49a4`, the merge that placed the completed comment-convention enforcement work, this registration plan, and all eight evidence commits on local `master`.

## Global Constraints

- Every command runs inside the checkout the executor was given — main checkout or worktree. Anchor each block with `cd "$(git rev-parse --show-toplevel)"`; never a literal absolute path.
- **The ADR, the decisions-index edit and the plan backlink are one commit.** Every split produces an intermediate tree that fails: the ADR alone fails both reciprocity checks, the backlink alone fails link resolution, the index alone fails nothing but proves nothing. There is no valid multi-commit ordering.
- **If validation fails after the ADR is committed, fix it with `git commit --amend`, never a follow-up commit.** The record is `Accepted` from birth, so from its very next commit edge the validator freezes, byte-for-byte:
  - the sections `Context`, `Decision drivers`, `Considered options`, `Decision` and `Consequences`, plus any heading you added beyond the required ten;
  - **every metadata field except `Status`, `Implementation status`, and the seven relationship fields.** `Scope`, `Deciders`, `Decision date` and `Retrospective` are frozen — editing `Scope` in a follow-up commit fails with `accepted ADR field changed: Scope`. This matters because D4 rests on the exact wording of `Scope`: get it right before committing, because afterwards the only cures are rewriting history or a superseding ADR.

  `Compliance and verification` and `Implementation evidence`, like the relationship fields, are append-only rather than frozen: a later commit may add lines but never alter or remove existing ones.
- All eight evidence commits are ancestors of base commit `dac49a4` and therefore predate the future ADR introduction on any branch created from this base. The registration commit may be merged, rebased, or squashed without invalidating those hashes. Before integrating, Task 2 must still prove the actual introduction edge and Task 3 must prove the three-file commit boundary.
- Evidence hashes are **40 lowercase hex characters**. Uppercase fails the regex.
- Relationship fields are separated by exactly `"; "` — one semicolon, one space. `;` alone or `;  ` fails.
- Items in `Related architecture sections` and `Related implementation plans` must be **only** a Markdown link, with no other text in the item.
- Do not add an `<a id="...">` anchor or a `<!-- migration-source: NN.NN -->` comment. Those exist in ADRs 0002-0008 as migration-inventory artefacts; a genuinely new ADR must not carry them, and ADR-0001 does not.
- Do not add section headings beyond the ten the template requires. Any extra heading is not in the validator's mutable-section allowlist, so it would be frozen byte-for-byte forever.
- The ADR number must stay contiguous. `0001`-`0008` exist, so this record is `0009`; creating `0010` while `0009` is absent fails the whole directory.
- No source, build, or funds-core change belongs in this plan. `git diff` on `services/` must be empty at the end.

## Verified inputs

Everything below was rechecked against the tree at `dac49a4`, after the enforcement branch was merged to local `master`, so the executor is not copying stale branch-topology assumptions.

| Input | Verified |
|---|---|
| Next free ADR number | `0009` (`0001`-`0008` present and contiguous) |
| Filename vs title | `validate_architecture._kebab_title("Adopt an enforced code comment convention")` returns `adopt-an-enforced-code-comment-convention`, matching `0009-adopt-an-enforced-code-comment-convention.md` exactly |
| All 8 evidence lines | Pass the validator's grammar; every commit resolves; every path exists in that commit's tree; every `changed:` path is genuinely in that commit's diff against its parents |
| Evidence predates the record | All 8 commits are ancestors of `HEAD`, so each is a strict ancestor of the future ADR commit — the condition `_qualified_historical_introduction` requires |
| Repository baseline | `python3 architecture/scripts/validate_architecture.py --root .` prints `architecture validation passed` at `dac49a4` |
| funds-core gate | `mise exec java@25 -- ./mvnw checkstyle:check` is green in the merged enforcement state inherited by `dac49a4` |
| `origin` | `git@github.com:PreciousTosin/core-banking.git` — a GitHub origin, so PR-URL evidence *would* be accepted; this plan uses local hash evidence anyway, because no PR exists yet |

## Decisions

Settled before writing. Do not re-open during execution.

- **D1 — One commit for the ADR and the index.** Forced by the reciprocity check, not a style preference.
- **D2 — `Status: Accepted`, `Retrospective: Yes`.** The convention was adopted, applied to 77 files, and mechanically enforced before this record existed; the record documents a decision already in force. This is exactly the case the framework's retrospective path is for, and the evidence satisfies its strict-ancestor test.
- **D3 — `Related architecture sections` links only the decisions index.** Linking `02-constraints.md` as well would be defensible, but every arc42 file linked must also list `ADR-0009` in its own `related_adrs` frontmatter, and the comment convention is a contributor-process rule rather than a runtime constraint on the system. Keep the reciprocal surface to one file.
- **D4 — `Implementation status: Complete`, with the scope bounded in the `Scope` field.** The decision as scoped — a documented convention plus mechanized enforcement of its mechanizable subset in the only module that has code — is fully delivered. The limits (shell scripts, future modules, and the judgement rules a tool cannot check) are stated in `### Negative` rather than hidden behind a softer status.
- **D5 — Amend, never follow up.** See Global Constraints.
- **D6 — `Related implementation plans` names the enforcement plan only, not this registration plan.** The enforcement plan delivered the decision; this one files the record. Naming both would be defensible but would require a backlink in this file too, and a plan whose only content is "create the ADR" is not an implementation of the decision the ADR records. Setting the field to `None` instead — which would sidestep the backlink rule entirely — was rejected: it would discard the one traceability link between the record and the work that delivered it, which is the whole point of the field.

### A note on the ADR threshold

`architecture/adr/README.md` sets the bar at material changes to boundaries, invariants, contracts, or deliberately accepted debt, and explicitly excludes "documentation corrections". A comment convention sits near that line, and this record is being created because the maintainer asked for it. The case for it being above the line: the convention binds every contributor in every language, and its enforcement now **fails the funds-core build**, which makes it a repository-wide contract with a verification boundary rather than a documentation preference. Note the honest limit of that argument — no CI job runs that build today, so the boundary is real but locally enforced; the ADR's `### Negative` section says so rather than leaving the reader to discover it. The plan states this openly in the ADR's `Context` rather than pretending the threshold question does not arise.

## File Structure

- Create `architecture/adr/0009-adopt-an-enforced-code-comment-convention.md` — the record. Nothing else in the repository states this decision.
- Modify `architecture/arc42/09-decisions.md` — add `ADR-0009` to the `related_adrs` frontmatter (machine-checked) and a bullet to the index list (convention, not machine-checked, but the list is the human entry point and an index missing a record is exactly the rot this framework exists to prevent).
- Modify `docs/superpowers/plans/2026-09-01-comment-convention-enforcement-implementation.md` — add the backlink to ADR-0009. This is not optional bookkeeping: the validator requires every plan named in `Related implementation plans` to link back to the record (see the ADR ↔ plan rule below).

### The ADR ↔ plan link rule

`validate_adrs` enforces the pairing in **both** directions:

- For every target of an ADR's `Related implementation plans`, that plan file must contain a Markdown link resolving to the ADR's own path, or the run fails with `implementation plan does not link back to ADR-0009: <plan>`.
- For every Markdown link from any file in `docs/superpowers/plans/` to any file in `architecture/adr/`, that ADR must name the linking plan in its `Related implementation plans`, or the run fails with `<plan>: ADR backlink is missing for ADR-0009`.

Two consequences the executor must respect:

1. The backlink and the ADR land in the **same commit**. The backlink is a Markdown link, and `validate_links` requires its target to exist, so adding it before the ADR file exists fails too. There is no valid ordering other than one commit.
2. **Do not turn any mention of ADR-0009 in this registration plan into a Markdown link.** Every reference here is a code span on purpose. Linking it would oblige ADR-0009 to name this plan as well, which it deliberately does not (D6).

---

### Task 1: Write ADR-0009 and register it in the decisions index

**Files:**
- Create: `architecture/adr/0009-adopt-an-enforced-code-comment-convention.md`
- Modify: `docs/superpowers/plans/2026-09-01-comment-convention-enforcement-implementation.md`
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
migration without a header, or a work item left in source fails the funds-core
build in seconds. The convention cannot decay unnoticed, because the gate is
part of the same build that runs the financial test suite.

### Negative

A new type cannot pass the funds-core build without a purpose comment,
including in tests. Enforcement reaches only as far as that build: no
continuous-integration job runs it, so the gate binds whoever runs the build
and the pre-pull-request checklist, and nothing blocks a merge mechanically
until such a job exists. The
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

- [ ] **Step 2: Add the backlink to the enforcement plan**

In `docs/superpowers/plans/2026-09-01-comment-convention-enforcement-implementation.md`, insert this line immediately after the `# Comment Convention Enforcement Implementation Plan` title line, followed by a blank line:

```markdown
**Retrospective ADR:** [ADR-0009](../../../architecture/adr/0009-adopt-an-enforced-code-comment-convention.md)
```

This is the same placement and shape the accounting-kernel plan uses for its `**Retrospective ADRs:**` line. The `../../../` prefix is correct: the file sits at `docs/superpowers/plans/`, so three levels up is the repository root.

Without this line the validator fails with `implementation plan does not link back to ADR-0009`. With it, and without the ADR file, `validate_links` fails because the target does not exist. Both files must therefore be committed together.

- [ ] **Step 3: Add ADR-0009 to the decisions index frontmatter**

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

- [ ] **Step 4: Add the index bullet**

Append to the list at the end of `architecture/arc42/09-decisions.md`:

```markdown
- [ADR-0009: Adopt an enforced code comment convention](../adr/0009-adopt-an-enforced-code-comment-convention.md)
```

The validator does not check this list — only the frontmatter drives reciprocity — but an index that omits a record is the decay this framework exists to prevent, and the link itself is checked for existence by `validate_links`.

- [ ] **Step 5: Validate before committing**

```bash
cd "$(git rev-parse --show-toplevel)"
python3 architecture/scripts/validate_architecture.py --root .
```

Expected: `architecture validation passed`, exit 0.

Failure messages and what each means:

| Message | Cause |
|---|---|
| `implementation plan does not link back to ADR-0009` | Step 2 was missed, or the inserted link does not resolve to the ADR's exact path |
| `ADR backlink is missing for ADR-0009` | Some file under `docs/superpowers/plans/` links to the ADR without being named in `Related implementation plans` — most likely a mention in this registration plan was turned into a Markdown link |
| `does not list ADR-0009` | Step 3 was missed: the `related_adrs` frontmatter |
| `does not link back to this exact architecture section` | The ADR's `Related architecture sections` does not point at `09-decisions.md` |
| A heading or field error | Compare against the exact block in Step 1; field order and heading order are checked as sequences, not sets |
| `does not exist` on a link | The three files were not all created before validating |

- [ ] **Step 6: Confirm the change is only these three files, then commit them together**

```bash
cd "$(git rev-parse --show-toplevel)"
git status --porcelain
```

Expected — three entries, in any order:

```
 M architecture/arc42/09-decisions.md
 M docs/superpowers/plans/2026-09-01-comment-convention-enforcement-implementation.md
?? architecture/adr/0009-adopt-an-enforced-code-comment-convention.md
```

Exactly three, nothing else. Running `validate_architecture.py` as a script creates no `__pycache__`, so at this point the status is clean apart from these files. Any other entry means something unplanned changed — stop and investigate.

```bash
cd "$(git rev-parse --show-toplevel)"
git add architecture/adr/0009-adopt-an-enforced-code-comment-convention.md architecture/arc42/09-decisions.md docs/superpowers/plans/2026-09-01-comment-convention-enforcement-implementation.md
git commit -m "Register the code comment convention as ADR-0009"
```

One commit. See the ADR ↔ plan link rule for why all three must land together.

---

### Task 2: Prove the retrospective introduction is legal

The structural run in Task 1 Step 5 says nothing about whether a *new* record may be born `Accepted`. That rule lives in the git-history edge checks, which need a committed record to compare against its parent. This task is the real gate for a retrospective ADR.

**Files:** none — verification only.

- [ ] **Step 1: Endpoint check**

```bash
cd "$(git rev-parse --show-toplevel)"
python3 architecture/scripts/validate_architecture.py --root . \
  --adr-base-ref HEAD~1 --adr-head-ref HEAD
```

Expected: `architecture validation passed`.

A failure naming the introduction rule means the record did not qualify as a historical introduction. The qualification is all of: `Retrospective: Yes`; no malformed evidence line anywhere in the section; the whole evidence block valid; and at least one evidence commit that is a **strict ancestor** of the commit that introduced the record. An evidence commit equal to the introducing commit is explicitly skipped, so evidence must predate the record — all eight lines in Task 1 do.

- [ ] **Step 2: Walk the ADR introduction edge**

```bash
cd "$(git rev-parse --show-toplevel)"
python3 architecture/scripts/validate_architecture.py --root . \
  --adr-edge-base-ref HEAD~1 --adr-edge-head-ref HEAD
```

Expected: `architecture validation passed`. The ADR, index registration and backlink are required to be the sole commit created by this plan, so `HEAD~1..HEAD` is the complete implementation range. Unlike `git merge-base master HEAD`, this remains non-empty if the plan is executed directly on `master`.

- [ ] **Step 3: If either check failed, amend — do not add a commit**

```bash
cd "$(git rev-parse --show-toplevel)"
# edit the ADR, then re-stage all three paths so the amend keeps the set intact:
git add architecture/adr/0009-adopt-an-enforced-code-comment-convention.md architecture/arc42/09-decisions.md docs/superpowers/plans/2026-09-01-comment-convention-enforcement-implementation.md
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

Expected: `Ran 151 tests`, `OK`. This does not test the new ADR; it proves the validator you just trusted is itself intact.

This step imports the validator, so it leaves untracked `architecture/scripts/__pycache__/` and `architecture/scripts/tests/__pycache__/` behind — `.gitignore` does not cover them. They are harmless, are never staged by this plan (every `git add` names explicit paths), and account for the two extra lines in Step 3's status.

- [ ] **Step 2: Every check, plus the staleness pass CI runs**

```bash
cd "$(git rev-parse --show-toplevel)"
python3 architecture/scripts/validate_architecture.py --root .
python3 architecture/scripts/validate_architecture.py --root . --checks tooling
python3 architecture/scripts/validate_architecture.py --root . --report-stale --as-of "$(date -u +%F)"
```

Expected: `architecture validation passed` three times. The third may print `WARNING:`/`::warning` lines for arc42 files whose `last_verified` is more than 90 days old; warnings do not fail the run. A `last_verified` in the future does fail it.

- [ ] **Step 3: Nothing outside the three files moved**

```bash
cd "$(git rev-parse --show-toplevel)"
git diff-tree --no-commit-id --name-only -r HEAD | sort
git status --porcelain --untracked-files=no
```

Expected: exactly these three paths, followed by no tracked working-tree entries:

```text
architecture/adr/0009-adopt-an-enforced-code-comment-convention.md
architecture/arc42/09-decisions.md
docs/superpowers/plans/2026-09-01-comment-convention-enforcement-implementation.md
```

This checks the implementation commit itself instead of comparing with the old architecture-framework commit, which would include unrelated enforcement history. Untracked `architecture/scripts/**/__pycache__/` directories from Step 1 are deliberately excluded from the second command.

- [ ] **Step 4: The funds-core gate is untouched**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
mise exec java@25 -- ./mvnw checkstyle:check
```

Expected: `BUILD SUCCESS`, `You have 0 Checkstyle violations.` This plan changes no Java, SQL, or POM, so a failure here means something unrelated broke and must be understood before merging.

---

### What a push actually carries

Before pushing, measure the unpublished history rather than trusting a count captured when this plan was written:

```bash
cd "$(git rev-parse --show-toplevel)"
git rev-list --count origin/master..master
```

The result is the number of commits on local `master` that are absent from its remote-tracking branch. If it is non-zero when this plan is executed, a later push publishes those commits as well as the integrated ADR work; inspect that history before pushing. If it is zero, the push carries only work integrated after the measurement. Do not encode the measured count back into this plan because the remote-tracking ref can change independently of the document.

The `Compliance and verification` numbers in the ADR cannot be re-checked by that CI, or by any agent without Docker and a Java 25 toolchain: no job runs the funds-core build, and this plan's Task 3 Step 4 runs `checkstyle:check` only, never the 254-test `clean verify` the record cites. Those numbers come from the enforcement work's own executed run; treat them as a record of what was verified then, not as something the ADR commit re-proves.

## When the pull request is opened

The repository validates the PR body itself on `pull_request` events. The description must contain exactly one `## Architecture impact` section with exactly one of its two canonical checkboxes ticked. Because this change adds an ADR, tick "Architecture changed; linked below" and fill all five fields, none empty, at least one of the first four not `None`, and `Verification evidence:` never `None`. Use `.github/pull_request_template.md` verbatim as the starting point and fill:

- `Related ADRs:` ADR-0009
- `Current-state arc42 sections changed:` architecture/arc42/09-decisions.md
- `Proposals implemented, invalidated, or superseded:` None
- `Diagrams changed:` None
- `Verification evidence:` the three validator commands from Task 3 Step 2 and the edge checks from Task 2

## Integration

No merge strategy is load-bearing now: all eight evidence commits are already ancestors of the base on `master`, and none of the evidence lines names the future registration commit. After Task 3 passes, use `superpowers:finishing-a-development-branch` to choose the repository's normal integration path. A merge commit, rebase, or squash is valid provided the ADR, decisions-index registration and enforcement-plan backlink appear atomically in the commit that first introduces ADR-0009.

After integration, run these checks in the checkout that holds the target branch:

```bash
cd "$(git rev-parse --show-toplevel)"
python3 architecture/scripts/validate_architecture.py --root .
ADR=architecture/adr/0009-adopt-an-enforced-code-comment-convention.md
test -f "$ADR" || {
  echo "FAIL: integrated HEAD does not contain ADR-0009"
  exit 1
}
mapfile -t EVIDENCE < <(grep -oE '^- [0-9a-f]{40}' "$ADR" | cut -d' ' -f2)
test "${#EVIDENCE[@]}" -eq 8 || {
  echo "FAIL: expected 8 ADR evidence hashes, found ${#EVIDENCE[@]}"
  exit 1
}
for evidence in "${EVIDENCE[@]}"; do
  git merge-base --is-ancestor "$evidence" HEAD || {
    echo "FAIL: ADR evidence is not reachable from the integrated HEAD: $evidence"
    exit 1
  }
done
```

Expected: `architecture validation passed`, no `FAIL` line, and exit 0. The assertions require the ADR and exactly eight evidence hashes before the loop proves every hash is reachable from the integrated history; the check does not rely on a fixed branch name or a stale expectation about how many hashes were once branch-only.

## Rollback

Three things reference the record: the ADR file itself, the `related_adrs` entry and bullet in `architecture/arc42/09-decisions.md`, and the backlink in the enforcement plan. They land in one commit, so they come and go together. Do not hand-remove the ADR file alone: the index entry and the backlink would be left dangling, failing both `validate_links` and the reciprocity checks.

**Do not use `git revert`.** A revert deletes the ADR file, and `_validate_adr_edge` rejects that on every edge it walks with `Accepted ADR was deleted or renamed` — so the revert fails the branch's own edge check, and once pushed it fails CI on `master` for every future run whose merge base predates it. Reverting an `Accepted` record trades a small problem for a permanent one.

The rollback therefore depends entirely on whether the commit has been pushed:

- **Not yet pushed:** drop it. `git reset --hard HEAD~1` on the branch, or `git commit --amend` to correct it in place. This is clean and leaves no trace.
- **Already on `master`:** there is no rollback. `Accepted` records cannot be deleted or renamed, and the number cannot be reused because numbering must stay contiguous. The only route is a **new** ADR that supersedes 0009, with `Superseded by` appended to 0009's relationship field — appending is permitted, editing is not.

Removing the enforcement itself is a separate matter from the record, and is covered by the enforcement plan's own rollback section.

## Risks

| Risk | Handling |
|---|---|
| The record is born `Accepted`, so five sections, every heading, and every field except `Status`, `Implementation status` and the seven relationship fields freeze on the next commit | Task 2 runs the edge checks immediately, and its Step 3 amends rather than appending. Nothing is pushed until both checks pass |
| A naive structural-only run gives false confidence | Task 2 exists precisely because `validate_architecture.py --root .` alone cannot see the introduction rule |
| Committing the ADR without one of its two reciprocal registrations | D1 and Task 1 Step 6 keep all three files in one commit; Step 5 catches either mismatch, by name, before any commit exists |
| Evidence that looks right but is not | All eight lines were checked against the validator's own grammar and against each commit's real diff before this plan was written |
| Integration onto a base that does not contain the historical evidence makes a direct `Accepted` introduction invalid | The base records all eight hashes as ancestors of `dac49a4`; Task 2 checks the real introduction edge, and the post-integration loop fails unless every hash is reachable from the integrated `HEAD` |
| An extra heading added for readability | Global Constraints forbid it: extra headings fall outside the mutable-section allowlist and freeze forever |

## Out of Scope

Changing the enforcement itself; adding a shell-script header check or fixing the missing header in `services/funds-core/scripts/prod-runtime-smoke.sh` (a separate one-comment change); linking ADR-0009 from `02-constraints.md` or any other arc42 section (D3); and opening the pull request, which is the maintainer's step.
