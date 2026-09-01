---
title: Funds-core solution strategy
status: current
owners:
  - architecture
last_verified: 2026-09-01
related_adrs:
  - ADR-0002
  - ADR-0003
  - ADR-0004
  - ADR-0005
  - ADR-0006
code_refs:
  - services/funds-core/src/main/java/com/corebanking/funds/
  - services/funds-core/src/main/resources/db/migration/
---

# Solution strategy

<a id="financial-facts-and-intent"></a>
<!-- migration-source: 04.01 -->
Domain invariants and typed command hashes protect financial facts before
persistence; PostgreSQL serializable transactions coordinate posting state.
Flyway migrations establish the authoritative schema, roles, and database
invariants. Corrections use linked exact reversals rather than rewriting an
original journal. Independently sourced proofs compare immutable postings with
materialised balance and control projections.

<a id="closed-period-correction"></a>
<!-- migration-source: 08.10::02 -->
Closed-period corrections remain additive: they book in an open period and
retain their link to the original immutable fact.

Bounded runtime configuration is described in the
[service README](../../services/funds-core/README.md). This is a kernel
strategy, not a deployed full-platform design.
