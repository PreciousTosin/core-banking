---
title: Funds-core risks and technical debt
status: current
owners:
  - architecture
last_verified: 2026-09-01
related_adrs: []
code_refs:
  - services/funds-core/README.md
  - infrastructure/kubernetes/
  - infrastructure/helm/
---

# Risks and technical debt

<a id="empirical-sizing-risk"></a>
<!-- migration-source: 02.04::02 -->
The PoC is one accounting kernel, not a complete or production-ready bank. It
does not claim high availability, regulatory certification, host-loss
durability, production throughput, a deployed full topology, or a full 8 GiB
mixed-load soak.

Deferred work includes channels, NIP/providers, Go contracts, event relay and
brokers, workflows, reconciliation, FX, account details, identifier APIs,
accruals, allocation, holds, and security UI. Kubernetes and Helm files are
not mitigation without deployment and acceptance evidence. The
[service README](../../services/funds-core/README.md) keeps these limits
explicit.
