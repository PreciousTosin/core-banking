---
title: Funds-core solution strategy
status: current
owners:
  - architecture
last_verified: 2026-09-01
related_adrs: []
code_refs:
  - services/funds-core/src/main/java/com/corebanking/funds/
  - services/funds-core/src/main/resources/db/migration/
---

# Solution strategy

Domain invariants and typed command hashes protect financial facts before
persistence; PostgreSQL serializable transactions coordinate posting state.
Flyway migrations establish the authoritative schema, roles, and database
invariants. Corrections use linked exact reversals rather than rewriting an
original journal. Independently sourced proofs compare immutable postings with
materialised balance and control projections.

Bounded runtime configuration is described in the
[service README](../../services/funds-core/README.md). This is a kernel
strategy, not a deployed full-platform design.
