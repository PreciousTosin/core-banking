# Code comment conventions

This document defines how source comments are written in this repository. It
applies to every language in the tree (Java, SQL migrations, shell, and later
Go). It was first applied across `services/funds-core` and the examples below
are taken from that module.

The one rule everything else derives from: **comment the why, not the what.**
Code already states what it does. A comment earns its place by recording
intent, an invariant, a non-obvious constraint, or a decision a reader could
not recover from the code alone.

## Principles

1. **Intent over mechanics.** `// lock chart before book` restates the code.
   `// Chart before book is the one lock order every governance path shares;
   deviating here deadlocks against rotate_chart_version.` records why.
2. **Point at concepts, don't re-teach them.** The service README and the
   architecture documents own the long explanations (sign convention, hash
   scheme boundary, role model). A comment names the concept and, when useful,
   the document section; it does not paraphrase the section.
3. **Accuracy beats coverage.** A wrong comment is worse than none, because the
   reader trusts it over the code. If you cannot verify a claim against the
   code, leave it out.
4. **Comments never change behaviour.** A commenting change touches no code,
   imports, formatting, or line width. It must survive a comment-stripped diff
   with zero differences (see Verification).
5. **No work items in comments.** `TODO`, `FIXME`, `XXX` belong in the issue
   tracker or the plan documents, not in source.

## Java

| Element | Rule |
|---|---|
| Public type | One `/** ... */` block: what the type is, the invariant it owns, and where it sits in the flow it participates in. |
| Public or package-private method | One to three lines of Javadoc: the contract, what it throws and why. Skip when the type-level comment plus the method name already say it (typical for record accessors and one-line delegators). |
| Private helper | Comment only when the name does not carry the *why*. |
| Inline `//` | Reserved for non-obvious decisions: lock order, scheme or version boundaries, checked-overflow semantics, error suppression, branch selection between paths that look interchangeable. |
| Fields and constants | Comment only when the value's origin or coupling is not local (e.g. a limit that must match a database CHECK constraint). |
| Enums, exceptions, tiny records | A one-line type comment is enough. |

Style: first sentence ends with a period. Stay within the file's existing
line width (about 100 characters). Do not add `@param`/`@return` tags unless
the parameter's meaning is genuinely non-obvious; prose is preferred.

### Example — type and method

```java
/**
 * Transactional entry point for posting a journal. Owns the SERIALIZABLE transaction, the
 * transaction-local deadlines, idempotency replay, retry and rollback; JdbcLedgerRepository
 * owns the SQL. Two entry points share one choreography: the public generic path and the
 * package-private trusted-reversal path reserved for ReversalService.
 */
@ApplicationScoped
public class PostingService {

    /**
     * Posts a generic journal. Proves the TYPED_V2 request hash is postingV2 of the journal and
     * rejects reversal metadata; linked reversals enter only through the trusted path.
     */
    public PostingResult post(PostingCommand command) { ... }
}
```

Note the second sentence is verifiable: the hash check and the rejection are
both visible in the method body, and "only through the trusted path" is a
claim about this class's two entry points, not about who may call them.

### Example — inline why

```java
// A suppressed rollback failure must not hide the original cause.
private static void rollback(Connection connection, Throwable original) {
```

### Anti-examples

```java
// increment counter          <- restates the code
counter++;

/** Gets the book id. */       <- adds nothing to a record accessor
public UUID bookId()

// TODO handle overflow        <- work item, belongs in the tracker
```

## SQL migrations

| Element | Rule |
|---|---|
| File header | A `--` block at the top: purpose of the migration, what it adds or changes, and why it exists relative to the previous version. |
| Function, trigger, constraint | One `--` line above it naming the invariant it enforces or the failure it prevents. |
| Table | One `--` line on its role when the name alone is ambiguous. |
| Data-migration blocks (`DO $$`) | Explain what state is being validated or transformed and what happens if it is not as expected. |

Migrations are immutable once applied to a shared database; comments added to
an already-applied migration change its Flyway checksum. In this repository
every test environment migrates a fresh container, so comments are safe. Once
a shared database exists, comment migrations only before they are applied.

Some tests assert on migration text (for example `MigrationIT` asserts that
`V004` does not contain certain role-management strings). A comment is text;
read the test that guards a file before commenting it.

### Example

```sql
-- V006: governed chart rotation. Adds the owner-only funds.rotate_chart_version
-- operation and moves the posting lock routine, the journal governance trigger
-- and the mapping-mutation trigger onto one canonical lock order: chart rows
-- in UUID order, then the stable book row. V005 introduced chart governance
-- but left rotation as two lifecycle UPDATEs and took posting locks through a
-- single join, so governance and posting could not be proven deadlock-free.
-- Nothing here rewrites stored facts.

-- Posting and direct-journal guards participate in the same lock protocol as
-- chart governance: chart row first, then the stable book row. A join with
-- FOR SHARE on both relations does not promise executor row-lock order.
CREATE OR REPLACE FUNCTION funds.lock_book_chart_for_posting(
```

The header says what changed relative to V005 and why; the routine comment
records the non-obvious fact (a join does not promise lock order) that
justifies the routine's existence.

## Tests

| Element | Rule |
|---|---|
| Test class | One `/** ... */` stating which acceptance criterion or invariant the class proves and what failure it would catch. |
| Test method | Comment only when the method name does not already state scenario and expected outcome. Multi-step scenarios (crash recovery, concurrency races, upgrade paths) get a short sequence note. |
| Test-support classes | Full type-and-method treatment; they are infrastructure other tests depend on. |
| Fixtures and magic values | Say where a value comes from when it is not arbitrary (e.g. a simulator-only NUBAN fixture). |

## Shell and configuration

Scripts get a header comment: purpose, inputs, and what a non-zero exit
means. Non-obvious flags get an inline comment. Configuration files get a
comment only where a value's origin or coupling is not local.

## Verification

A commenting change is verified in three steps:

1. The module compiles (`compile` and `test-compile`).
2. A comment-stripped diff between the base commit and the working tree is
   empty for every touched source file. This proves nothing but comments
   changed.
3. A reviewer who did not write the comments checks each one against the code
   it describes.

## Enforcement

Enforcement is a planned follow-up, not part of the initial adoption:

- Checkstyle with a minimal ruleset (`MissingJavadocType` on public types in
  `src/main`, `InvalidJavadocPosition`, `JavadocStyle`). Deliberately no
  `MissingJavadocMethod`, which pushes authors toward restating code.
- A migration-header check that fails the build if any `V*.sql` lacks a
  leading `--` block.

Both would be wired into the module's `verify` phase.
