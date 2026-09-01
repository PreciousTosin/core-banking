---
title: Funds-core crosscutting concepts
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
  - ADR-0007
  - ADR-0008
code_refs:
  - services/funds-core/src/main/java/com/corebanking/funds/domain/
  - services/funds-core/src/main/java/com/corebanking/funds/runtime/ProdDatasourceStartupGuard.java
  - services/funds-core/src/main/resources/db/migration/
---

# Crosscutting concepts

<a id="materialised-balance"></a>
<!-- migration-source: 08.03::01 -->
Money is a currency plus signed integer minor units with checked exact
arithmetic. Balanced journals, typed canonical hashes, idempotency,
serializable transactions, and immutable facts form the integrity boundary.
Corrections are linked exact reversals, preserving additive history.

PostgreSQL migrations define the schema and database roles. The production
[datasource startup guard](../../services/funds-core/src/main/java/com/corebanking/funds/runtime/ProdDatasourceStartupGuard.java)
fails closed for blank or inactive configuration. JVM memory, JDBC, worker,
request, and transaction-timeout limits are bounded; proof queries distinguish
immutable postings from materialised projections.
