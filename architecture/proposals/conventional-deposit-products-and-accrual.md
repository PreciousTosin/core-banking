---
title: Conventional deposit products and accrual
status: approved
owners:
  - funds-core
target_release: undecided
related_adrs:
  - ADR-0002
  - ADR-0003
  - ADR-0004
  - ADR-0005
  - ADR-0006
related_plans:
  - docs/superpowers/plans/2026-08-30-conventional-deposit-products-and-accrual-implementation.md
---
# Conventional deposit products and accrual

> **Architecture state: APPROVED — non-current.** No savings, current-account,
> fixed-deposit, accrual, capitalisation, or maturity capability is current yet.

## Purpose and scope

Add versioned savings, current, and fixed-deposit liability contracts with exact,
idempotent accrual, capitalisation, maturity, renewal, and early liquidation.
Product policies define eligibility, day count, rounding, schedule, tax treatment,
and journal templates; contracts stay bound to their opening version until an
explicit approved migration.

## Requirements and constraints

- `funds-core` calculates and posts all money using signed integer minor units
  and exact rational intermediates. Binary floating point is forbidden.
- Savings, current, and fixed deposits are distinct policy types. Conventional
  policy cannot accept a `NON_INTEREST` finance principle.
- Accrual facts, checkpoints, journal links, capitalisation consumption, and
  contract transitions are durable, immutable, and idempotent.
- Corrections are additive. Principal already held as a customer liability is
  never recreated at maturity.
- A scheduler supplies business dates and bounded stable-key pages; wall-clock
  timing, retries, and cache state cannot change calculated value.

## Acceptance boundary

Delivery must prove exact calculation and rounding, version retention, balanced
journals, tax separation, restrictions and period close, crash/retry recovery,
fixed-deposit state races, reconciliation at a cutoff, and bounded processing of
at least 100,000 synthetic contracts. Product, tax, legal, and regulatory policy
examples are illustrative configuration rather than production approval.

## Relationships

- Plan: [Conventional Deposit Products and Accrual Implementation Plan](../../docs/superpowers/plans/2026-08-30-conventional-deposit-products-and-accrual-implementation.md)
- Decisions: [ADR-0002](../adr/0002-centralize-financial-invariants-in-funds-core.md), [ADR-0003](../adr/0003-use-signed-integer-minor-units.md), [ADR-0004](../adr/0004-use-postgresql-as-the-authoritative-ledger.md), [ADR-0005](../adr/0005-use-immutable-journals-and-additive-corrections.md), and [ADR-0006](../adr/0006-couple-idempotency-and-outbox-to-ledger-commit.md)
