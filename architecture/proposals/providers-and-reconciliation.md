---
title: Providers and reconciliation
status: proposed
owners:
  - funds-core
  - provider-gateway
  - reconciliation
target_release: undecided
related_adrs:
  - ADR-0002
  - ADR-0004
  - ADR-0006
  - ADR-0007
  - ADR-0008
related_plans: None
---
# Providers and reconciliation

> **Architecture state: PROPOSED — non-current.** Provider adapters, routing,
> settlement, reconciliation engines, and external payment rails are not current.

## Purpose and scope

Define capability ports, provider contracts, transaction and attempt state,
routing/fallback, webhooks, risk boundaries, settlement cycles, reconciliation,
suspense, returns, reversals, and a deterministic provider simulator. Unknown
external outcomes remain indeterminate until evidence proves a terminal result.

## Requirements and constraints

- Provider attempts have stable external identity, canonical request/response
  evidence, explicit timeouts and ambiguity, and no direct ledger access.
- Routing uses declared provider capability, currency, limits, cutoffs, health,
  and policy. Fallback cannot create a second financial effect after ambiguity.
- Webhooks are authenticated, durable, deduplicated, order-tolerant, and joined
  to commands/attempts without trusting an account address as transaction identity.
- Reconciliation compares independent internal, provider, and settlement evidence;
  unmatched and incomplete sources stay open, classified, aged, and auditable.
- Suspense and corrections use governed balanced journals. No reconciliation
  worker mutates posted history or silently treats absence of evidence as failure.
- Simulators and fault injection are deterministic, bounded, private, and retain
  seeds and timelines. They do not constitute real-provider certification.

## Acceptance boundary

Delivery must cover duplicated, delayed, reordered, malformed, contradictory,
timed-out, and late provider evidence; concurrent routing and fallback; settlement
calendar/cutoff behavior; suspense aging and additive correction; independent
daily proof; restart/replay; and bounded load. Real provider accreditation,
production fraud/AML decisions, and legal settlement finality remain outside.

## Diagrams

The [proposed container view](../diagrams/containers.mmd) and [proposed single-VM
deployment view](../diagrams/single-vm-deployment.mmd) show the intended PoC
placement but are not deployment or provider-integration evidence.

## Relationships

- Decisions: [ADR-0002](../adr/0002-centralize-financial-invariants-in-funds-core.md), [ADR-0004](../adr/0004-use-postgresql-as-the-authoritative-ledger.md), [ADR-0006](../adr/0006-couple-idempotency-and-outbox-to-ledger-commit.md), [ADR-0007](../adr/0007-separate-ledger-identity-from-account-addresses.md), and [ADR-0008](../adr/0008-target-an-eight-gib-single-vm-evidence-suite.md)
