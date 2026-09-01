# Working in this repository

Instructions for contributors and coding agents. Tool-specific entry points
(`CLAUDE.md` and the `.agents/`, `.codex/`, `.qodo/` directories) defer to
this file so that every tool follows the same rules.

## Layout

- `services/funds-core/` — the implemented accounting kernel (Java 25,
  Quarkus, PostgreSQL). Its [README](services/funds-core/README.md) is the
  source of truth for the accounting model, identity rules, roles, and limits.
- `architecture/` — the comprehensive design and, once the ADR framework
  lands, `architecture/adr/` for decisions.
- `docs/superpowers/` — specs and implementation plans.
- `docs/conventions/` — repository-wide conventions.

## Code comments

Comments follow [docs/conventions/code-comments.md](docs/conventions/code-comments.md).
The short version:

- Comment the **why**, not the what. A comment that restates the code is
  removed in review.
- Every public type gets a one-block purpose comment; every migration gets a
  header block. Methods get Javadoc only when the name does not carry the
  contract.
- Accuracy over coverage. Do not write a claim you have not verified against
  the code.
- No `TODO`/`FIXME` in source; work items go in the plans or tracker.
- A comment-only change must leave a comment-stripped diff empty.

## Before opening a pull request

- [ ] New public types have a purpose comment; new migrations have a header.
- [ ] No comment restates its code; no `TODO`/`FIXME` was added.
- [ ] `./mvnw checkstyle:check` passes in `services/funds-core` (it also runs
      inside `validate`, so a violation fails the build before any test does).
- [ ] If a migration was edited, the tests that read it as text still pass
      (`MigrationIT` asserts on `V004__application_roles.sql`).
- [ ] `./mvnw clean verify` was run in `services/funds-core` with Docker
      available (the PostgreSQL Testcontainers gate is not optional).

## Financial invariants that must never regress

Positive minor units are debits, negative are credits, and a journal sums to
zero. Money is a signed 64-bit integer of minor units with checked arithmetic.
Journals are immutable; corrections are additive reversals. The ledger-account
UUID is the only balance-bearing identity. Chart rows lock before book rows.
See the funds-core README for the full statement of each rule.
