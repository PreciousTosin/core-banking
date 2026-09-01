---
title: Non-interest banking products
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
  - docs/superpowers/plans/2026-08-30-non-interest-banking-products-implementation.md
---
# Non-interest banking products

> **Architecture state: APPROVED — non-current.** This proposal is an
> implementation boundary, not a Sharia, legal, or regulatory certification.

## Purpose and scope

Create a structurally separate non-interest deposit/investment foundation and
one illustrative Mudarabah-style investment-pool allocation proof. The design
models finance principle, governance approvals, pool participation, realised
results, exact allocation, and dedicated journal templates instead of disguising
conventional interest as a zero rate.

## Requirements and constraints

- Non-interest versions reject conventional annual rates, accrued-interest
  accounts, calculation policies, and journal templates before mutation.
- Approved contracts and pool versions are immutable and name their approval,
  permitted asset/pool class, profit/loss/fee rules, ratios, and accounting policy.
- Participation events and cutoff facts are immutable; allocation uses exact
  rational arithmetic and a deterministic minor-unit residual rule.
- Pool close uses maker-checker approval over canonical evidence and blocks until
  source completeness and trial-balance proofs pass.
- Distribution commands, journal effects, and outbox facts commit atomically;
  cross-pool leakage and silent principal/return guarantees are forbidden.

## Acceptance boundary

Delivery must prove cross-principle rejection, maker-checker integrity, exact
conservation of approved distributable results, deterministic allocation,
crash/retry singularity, cutoff replay, restrictions, and bounded processing of
at least 100,000 participant contracts. Murabahah and Ijarah financing, broader
loss policies, and any external certification remain outside this proposal.

## Relationships

- Plan: [Non-Interest Banking Products Implementation Plan](../../docs/superpowers/plans/2026-08-30-non-interest-banking-products-implementation.md)
- Decisions: [ADR-0002](../adr/0002-centralize-financial-invariants-in-funds-core.md), [ADR-0003](../adr/0003-use-signed-integer-minor-units.md), [ADR-0004](../adr/0004-use-postgresql-as-the-authoritative-ledger.md), [ADR-0005](../adr/0005-use-immutable-journals-and-additive-corrections.md), and [ADR-0006](../adr/0006-couple-idempotency-and-outbox-to-ledger-commit.md)
