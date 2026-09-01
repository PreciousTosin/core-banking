---
title: Funds-core building-block view
status: current
owners:
  - architecture
last_verified: 2026-09-01
related_adrs:
  - ADR-0002
  - ADR-0003
  - ADR-0004
  - ADR-0006
  - ADR-0007
code_refs:
  - services/funds-core/src/main/java/com/corebanking/funds/domain/
  - services/funds-core/src/main/java/com/corebanking/funds/application/
  - services/funds-core/src/main/java/com/corebanking/funds/infrastructure/postgres/
  - services/funds-core/src/main/resources/db/migration/
---

# Building-block view

<a id="account-building-block"></a>
<!-- migration-source: 08.01 -->
The [domain package](../../services/funds-core/src/main/java/com/corebanking/funds/domain/Money.java)
contains accounting records and invariants. The
[application package](../../services/funds-core/src/main/java/com/corebanking/funds/application/PostingService.java)
contains posting, reversal, hashing, validation, transaction deadlines, and
proof services.

The [PostgreSQL infrastructure](../../services/funds-core/src/main/java/com/corebanking/funds/infrastructure/postgres/JdbcLedgerRepository.java)
and [Flyway migrations](../../services/funds-core/src/main/resources/db/migration/V001__accounting_reference.sql)
are the authoritative persistence boundary. They own durable accounting facts,
balances, control projections, idempotency, outbox persistence, chart
governance, and database-role boundaries. The runtime package supplies the
production datasource startup guard.

The [current funds-core component diagram](../diagrams/funds-core-components.mmd)
shows the permitted dependency direction between these implemented layers.
