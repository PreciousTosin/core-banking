---
title: Funds-core quality requirements
status: current
owners:
  - architecture
last_verified: 2026-09-01
related_adrs: []
code_refs:
  - services/funds-core/README.md
  - services/funds-core/src/test/
---

# Quality requirements

<a id="accounting-proofs"></a>
<!-- migration-source: 08.11 -->
The implemented acceptance boundary in the
[funds-core README](../../services/funds-core/README.md) covers exact money,
balanced journals, serializable accounting updates, exact reversal,
independent trial-balance/control proofs, database-role denial of direct
mutation, bounded runtime settings, typed hashes, idempotency recovery, NUBAN
and product foundations, and governed chart rotation.

The test gate includes unit, generated-property, PostgreSQL integration,
failure-injection, and child-process crash tests. Explicit exclusions include
identifier APIs, NIP, account details, accruals, non-interest allocation,
holds, Go contracts, event relay, providers, reconciliation, FX, security UI,
and full 8 GiB orchestration.
