# Infrastructure Architecture: Single-VPS PoC on Ubuntu 24.04

Parent document: @architecture/modern-core-banking-comprehensive-design-revised.md
Summary: This document specifies the infrastructure blueprint required to deploy the Modern Core Banking PoC on a single Ubuntu 24.04 VPS. It translates the architecture decisions into concrete host requirements, container topology, storage composition, and operational runbooks that support reproducible PoC validation.

---

## 1) Scope and constraints
- Target: one VPS with 4 vCPU, 8 GiB RAM, NVMe-backed storage; swap must be disabled during evidence-producing tests.
- OS: Ubuntu Server 24.04 LTS.
- Orchestrator: Docker Compose overlays (normal, concurrency, restore).
- Public surface: only the API edge is exposed; all other services live on private container networks.
- Observability: optional but recommended (OTel, Prometheus) per architecture guidelines.

This PoC intentionally does not aspire to multi-region HA, true disaster recovery, or production-grade throughput. See the main design document for the full set of invariants and acceptance criteria.

---

## 2) System context and topology
- Core services (Java/Go): funds-core, api-edge, provider-gateway, txn-orchestrator, risk-engine, recon-engine, projections, provider-simulator.
- Data & messaging: PostgreSQL, Redpanda (Kafka-compatible broker), Temporal, MinIO, Valkey (cache/state).
- Public edge: API surface exposed via a reverse proxy (Caddy or Traefik).
- Network: private container networks for all internal services; public inbound only on api-edge.
- Data flow: API edge -> transaction orchestrator -> funds-core + provider-gateway; journal events propagate via transactional outbox to Redpanda; projections and reconciliation read from outbox topics.

See Section 6 (System context) and Section 7 (Service ownership and boundaries) of the main architecture document for the canonical relationships.

---

## 3) Resource model and profiles
- PoC profile: default normal, concurrency, and restore overlays. Each overlay has explicit memory/CPU ceilings per component as documented in the main design (Section 21.1).
- Memory ceilings (example, per PoC guidance): roughly 7.3–7.5 GiB total across components in normal and concurrency runs, with plan for headroom and swap disabled during evidence runs.
- CPU quotas: align with the documented per-component ceilings; ensure the host has headroom for OS, Docker overhead, and logging.
- Storage: NVMe-backed volumes for Postgres, Redpanda, MinIO, Temporal state, and outbox payload archives.

- Security and access: secret storage (SOPS/Vault), mTLS between services, and encryption at rest where applicable.

---

## 4) Component mapping and packaging
- funds-core: Java; authoritative ledger mutations.
- api-edge: Go; public API surface; applies edge validation and idempotency checks.
- provider-gateway: Go; provider capability routing, virtual accounts, adapaters.
- txn-orchestrator: Go; Temporal-based workflows.
- risk-engine, recon-engine, projections, provider-simulator: Go; non-mutating or orchestration roles.
- Databases and stores:
  - PostgreSQL: primary ledger and application data.
  - Redpanda: event streaming for outbox, projections, and reconciliation streams.
  - Temporal: workflow orchestration state.
  - MinIO: object storage for archives.
  - Valkey: resilience/cache layer for provider state.

All internal services run on private networks with the API edge publicly exposed.

---

## 5) Storage and data management
- Persistent volumes per service as described in the main design: Postgres data directory, Redpanda data directory, MinIO data, Temporal state, and signed integrity manifests off-host.
- Backup strategy: PITR for PostgreSQL, periodic snapshots for Redpanda and MinIO, off-host archival of signed manifests.
- Data retention policies align with the PoC scope; long-term regulatory or production extensions are out of scope for this PoC.

---

## 6) Networking and security posture
- Ingress: TLS-enabled reverse proxy (Caddy/Traefik) terminating TLS on api-edge.
- Service networks: isolated docker networks; no internal endpoints exposed publicly.
- Secrets: stored using SOPS/Vault; sensitive fields redacted in logs and events.
- Identity: OIDC on the edge; mTLS among services; roles and least privilege per service.
- Compliance: PoC scope excludes production-grade KYC/AML and high-trust regulatory commitments; explicit boundaries documented in the main design.

---

## 7) Observability and runbook
- Telemetry: OpenTelemetry with an optional collector for traces/metrics.
- Health checks: /health and /ready endpoints per service.
- Runbooks: include recovery from outbox replay, provider-break, backlog growth, and credential rotation.

---

## 8) Acceptance criteria (PoC)
- All acceptance tests in the main design §23 pass under the normal overlay within the defined resource ceilings.
- No swap usage during evidence runs; memory budgets respected; no production-level throughput claims.
- Public API edge reachable with TLS; internal services remain private.

---

## 9) How this document relates to the parent design
- This document translates the high-level constraints into concrete infrastructure boundaries and procedures for the single-VPS PoC environment and clearly ties to the parent design decisions and invariants.

---

## 10) References
 - Modern Core Banking Comprehensive Design (PoC) – main document: @architecture/modern-core-banking-comprehensive-design-revised.md
 - Sections: 21 (Single-VPS PoC), 6 (System context), 7 (Service ownership), 8 (Data model), 9 (Ledger and outbox), 16 (Eventing/projections), 17 (Security), 20 (Production deployment target).

---

## 11) Operational runbook (PoC)
- Start in normal overlay: ensure all containers come up healthy
- Validate acceptance tests: §23 of main design
- If resource pressure observed, switch to a lighter profile or disable non-critical components (e.g., observability dashboards)
- If a component crashes, follow standard Docker/container restart policies and verify idempotency of mutations when recovery completes
- Backup plan: snapshot PostgreSQL, archive outbox payloads, and back up signed manifests

---

## 12) Kubernetes migration guidance (future state)
- This PoC can be ported to Kubernetes using the skeleton manifests in kubernetes/infra-modern-core-banking-poc
- Start with a namespace, PV/PVCs for all data stores, then deploy the core services, and finally set up Ingress for api-edge
- For CI/CD, consider a Helm chart or Kustomize overlays reflecting normal/concurrency/restore profiles
