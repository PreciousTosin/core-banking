---
title: Funds-core constraints
status: current
owners:
  - architecture
last_verified: 2026-09-01
related_adrs: []
code_refs:
  - services/funds-core/
  - services/funds-core/README.md
---

# Constraints

The module uses Java 25, Quarkus, Flyway migrations, and PostgreSQL 18.6
integration evidence. Financial values use exact signed integer minor units.
The target is an 8 GiB single VM resource budget, not evidence that a full
topology is deployed. Kubernetes and Helm files exist, but do not prove a
deployed platform.

Customer channels, providers, NIBSS/NIP, Go services, brokers, workflow
engines, high availability, regulatory certification, and production
throughput are outside the implemented current state.
