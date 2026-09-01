---
title: Full single-VM proof-of-concept platform
status: proposed
owners:
  - architecture
  - platform
target_release: undecided
related_adrs:
  - ADR-0001
  - ADR-0002
  - ADR-0004
  - ADR-0006
  - ADR-0008
related_plans: None
---
# Full single-VM proof-of-concept platform

> **Architecture state: PROPOSED — non-current.** Repository architecture text,
> diagrams, manifests, or scripts are design evidence, not deployment evidence.

## Purpose and scope

Assemble the bounded single-VM PoC described by the comprehensive design: edge,
orchestration, provider gateway and simulator, risk, reconciliation, projections,
PostgreSQL, Temporal, Redpanda, Valkey, MinIO, fault injection, and mandatory
telemetry around the implemented `funds-core` kernel. The supported outcome is an
evidence suite across declared profiles, not every component at peak concurrently.

## Requirements and constraints

- One Ubuntu 24.04 VM has 4 vCPU, 8 GiB RAM, disabled/unused swap, explicit
  cgroup, connection, worker, retention, file, and queue limits, and a 2 GiB
  host/page-cache reserve.
- PostgreSQL remains authoritative; at-least-once delivery is made safe by
  transactional idempotency/outbox boundaries and evidence-driven reconciliation.
- Startup, schema migration, secrets, storage, broker/workflow retention,
  health, failure behavior, observability, and graceful degradation are explicit.
- Normal, concurrency/fault, and restore/replay profiles retain version/config
  hashes, seeds, resource measurements, proof outputs, and fault timelines.
- The platform must fail admission or reduce nonessential work before resource
  exhaustion can create partial or ambiguous financial behavior.

## Acceptance boundary

The full acceptance matrix must prove posting, idempotency, restrictions, failure
recovery, provider ambiguity, reconciliation, migration, restore/replay, telemetry,
and resource bounds. No file in the repository alone proves that a profile ran,
that a deployment exists, or that production throughput, HA, or RTO/RPO is met.

## Design detail and diagrams

- [Proposed Ubuntu 24.04 single-VM infrastructure detail](../infrastructure/infra-ubuntu24.04-poc.md)
- [Proposed container view](../diagrams/containers.mmd)
- [Proposed single-VM deployment view](../diagrams/single-vm-deployment.mmd)

## Relationships

- Decisions: [ADR-0001](../adr/0001-manage-architecture-as-versioned-code.md), [ADR-0002](../adr/0002-centralize-financial-invariants-in-funds-core.md), [ADR-0004](../adr/0004-use-postgresql-as-the-authoritative-ledger.md), [ADR-0006](../adr/0006-couple-idempotency-and-outbox-to-ledger-commit.md), and [ADR-0008](../adr/0008-target-an-eight-gib-single-vm-evidence-suite.md)
