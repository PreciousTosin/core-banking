---
title: Funds-core decisions index
status: current
owners:
  - architecture
last_verified: 2026-09-01
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
code_refs:
  - architecture/adr/template.md
---

<a id="architecture-decisions-index"></a>
# Decisions

The [ADR template](../adr/template.md) defines the record shape. This index
links decision records without duplicating their rationale.

- [ADR-0001: Manage architecture as versioned code](../adr/0001-manage-architecture-as-versioned-code.md)
- [ADR-0002: Centralize financial invariants in funds core](../adr/0002-centralize-financial-invariants-in-funds-core.md)
- [ADR-0003: Use signed integer minor units](../adr/0003-use-signed-integer-minor-units.md)
- [ADR-0004: Use PostgreSQL as the authoritative ledger](../adr/0004-use-postgresql-as-the-authoritative-ledger.md)
- [ADR-0005: Use immutable journals and additive corrections](../adr/0005-use-immutable-journals-and-additive-corrections.md)
- [ADR-0006: Couple idempotency and outbox to ledger commit](../adr/0006-couple-idempotency-and-outbox-to-ledger-commit.md)
- [ADR-0007: Separate ledger identity from account addresses](../adr/0007-separate-ledger-identity-from-account-addresses.md)
- [ADR-0008: Target an eight GiB single VM evidence suite](../adr/0008-target-an-eight-gib-single-vm-evidence-suite.md)
- [ADR-0009: Adopt an enforced code comment convention](../adr/0009-adopt-an-enforced-code-comment-convention.md)
