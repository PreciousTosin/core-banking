---
title: Production platform
status: proposed
owners:
  - architecture
  - platform
  - security
target_release: undecided
related_adrs:
  - ADR-0001
  - ADR-0004
  - ADR-0008
related_plans: None
---
# Production platform

> **Architecture state: PROPOSED — non-current.** The single-VM PoC and its
> manifests do not establish this production topology or its controls.

## Purpose and scope

Define the later production boundary for multi-zone service deployment,
authoritative PostgreSQL availability and recovery, broker and workflow
durability, identity and privileged access, network segmentation, secrets and
key management, data protection, audit, retention, and controlled migrations.

## Requirements and constraints

- Production substrate, replicas, quorum, storage, capacity, failure domains,
  region strategy, and recovery objectives require measured evidence and explicit
  decisions; they cannot be inferred from PoC behavior.
- Workload and human identity use least privilege, short-lived credentials,
  maker-checker controls, separable duties, auditable emergency access, and
  explicit rotation and revocation.
- Encryption, tokenisation/masking, log redaction, audit immutability, retention,
  deletion, and legal hold are policy-bound and independently verified.
- Database migrations use expand/contract compatibility, rehearsed backups and
  restores, bounded locks, monitored rollback/roll-forward decisions, and no
  destructive shortcut against immutable financial facts.

## Acceptance boundary

Before this proposal can become current, production SLOs, capacity and failure
tests, backup/restore objectives, security and compliance review, key and access
operations, data lifecycle, deployment automation, and migration controls need
path-bound evidence. No production deployment is currently claimed.

## Diagrams

The [proposed container view](../diagrams/containers.mmd) and [proposed single-VM
deployment view](../diagrams/single-vm-deployment.mmd) illustrate PoC boundaries;
they are inputs to, not evidence of, a production topology.

## Relationships

- Decisions: [ADR-0001](../adr/0001-manage-architecture-as-versioned-code.md), [ADR-0004](../adr/0004-use-postgresql-as-the-authoritative-ledger.md), and [ADR-0008](../adr/0008-target-an-eight-gib-single-vm-evidence-suite.md)
