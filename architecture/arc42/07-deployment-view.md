---
title: Funds-core deployment view
status: current
owners:
  - architecture
last_verified: 2026-09-01
related_adrs: []
code_refs:
  - services/funds-core/Dockerfile.jvm
  - services/funds-core/scripts/prod-runtime-smoke.sh
  - infrastructure/kubernetes/
  - infrastructure/helm/
---

# Deployment view

Current evidence is a JVM [Dockerfile](../../services/funds-core/Dockerfile.jvm)
with bounded memory and a [production-runtime smoke script](../../services/funds-core/scripts/prod-runtime-smoke.sh).
The smoke script exercises the image against PostgreSQL 18.6 under constrained
container resources. The 8 GiB VM is a target budget for this bounded module,
not a deployed environment claim.

[Kubernetes](../../infrastructure/kubernetes/infra-modern-core-banking-poc/namespace.yaml) and
[Helm](../../infrastructure/helm/modern-core-banking-poc/Chart.yaml) files exist but do not establish a deployed
full platform.
