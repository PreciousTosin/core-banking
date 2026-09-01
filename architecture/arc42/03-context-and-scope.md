---
title: Funds-core context and scope
status: current
owners:
  - architecture
last_verified: 2026-09-01
related_adrs: []
code_refs:
  - services/funds-core/
  - services/funds-core/README.md
---

# Context and scope

Current external actors are developers/operators, PostgreSQL, and test
infrastructure. Operators run the roles and configuration documented in the
[service README](../../services/funds-core/README.md); PostgreSQL is the
authoritative store for journals, postings, projections, idempotency, and
outbox rows.

<a id="modeled-context-boundary"></a>
<!-- migration-source: 02.03::02 -->
The Java/Quarkus module processes typed application commands. Customer
channels, providers, NIBSS/NIP, Go services, brokers, and workflow engines are
not current interfaces or topology claims.
