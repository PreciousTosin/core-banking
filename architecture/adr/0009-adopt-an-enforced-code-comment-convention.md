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
