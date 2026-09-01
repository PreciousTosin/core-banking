---
title: Account identifiers and simulated NIP inbound
status: approved
owners:
  - funds-core
  - provider-gateway
target_release: undecided
related_adrs:
  - ADR-0002
  - ADR-0004
  - ADR-0006
  - ADR-0007
related_plans:
  - docs/superpowers/plans/2026-08-30-account-identifiers-and-nip-inbound-implementation.md
---
# Account identifiers and simulated NIP inbound

> **Architecture state: APPROVED — non-current.** Approval permits delivery;
> it does not claim that the capabilities below are implemented.

## Purpose and scope

Give one customer ledger account a primary synthetic NUBAN and multiple scoped
provider virtual-account aliases, expose masked account details, and accept
simulated NIP/provider inbound credits exactly once. Ledger-account UUIDs remain
the only balance-bearing identity; account numbers are mutable addresses, and
provider or NIP session identity is transaction identity.

## Requirements and constraints

- `funds-core` owns identifier lifecycle, authoritative resolution, restrictions,
  posting, idempotency, and outbox facts in PostgreSQL.
- An active scoped identifier resolves to one ledger account. One account may
  have many aliases but at most one active primary NUBAN.
- `provider-gateway` retains canonical external evidence; orchestration retries
  at least once without creating a second financial effect.
- The synthetic `000000`/`0000000017` identity is `SIMULATOR_ONLY`; production
  adapters reject it. No Nigerian IBAN is generated.
- Caches are bounded and non-authoritative. Full identifiers and customer names
  stay out of ordinary logs, metrics, traces, and workflow search attributes.

## Acceptance boundary

Delivery must prove identifier uniqueness and lifecycle races, restrictions,
masked lookup, canonical-hash conflict detection, crash/retry behavior, balanced
inbound journals, outbox atomicity, reconciliation, and bounded operation. Real
NIP connectivity, a production institution code, regulatory certification, and
production identity/KYC controls remain outside this proposal.

## Relationships

- Plan: [Account Identifiers and Simulated NIP Inbound Implementation Plan](../../docs/superpowers/plans/2026-08-30-account-identifiers-and-nip-inbound-implementation.md)
- Decisions: [ADR-0002](../adr/0002-centralize-financial-invariants-in-funds-core.md), [ADR-0004](../adr/0004-use-postgresql-as-the-authoritative-ledger.md), [ADR-0006](../adr/0006-couple-idempotency-and-outbox-to-ledger-commit.md), and [ADR-0007](../adr/0007-separate-ledger-identity-from-account-addresses.md)
