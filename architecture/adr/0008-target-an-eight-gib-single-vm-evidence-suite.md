# ADR-0008: Target an eight GiB single VM evidence suite

- Status: Accepted
- Retrospective: Yes
- Decision date: 2026-09-01
- Deciders: Architecture and funds-core maintainers
- Scope: PoC resource budget and evidence boundary
- Implementation status: Partial
- Related proposals: None
- Related implementation plans: None
- Related pull requests: None
- Related commits: None
- Related architecture sections: [Constraints](../arc42/02-constraints.md); [Deployment view](../arc42/07-deployment-view.md); [Crosscutting concepts](../arc42/08-crosscutting-concepts.md); [Decisions index](../arc42/09-decisions.md); [Quality requirements](../arc42/10-quality-requirements.md); [Risks and technical debt](../arc42/11-risks-and-technical-debt.md)
- Supersedes: None
- Superseded by: None

## Context

The PoC targets constrained operation on a single 8 GiB VM. Unbounded heap,
connections, workers, requests, or database work can turn resource exhaustion
into partial financial behavior rather than an explicit correctness failure.

## Decision drivers

- Bound runtime resources and fail before unsafe overload becomes financial drift.
- Distinguish documented targets and manifest snapshots from deployed measurements.
- Require repeatable mixed-profile evidence before claiming the complete PoC envelope.

## Considered options

- Treat capacity as an operations-only concern; this leaves correctness exposed to exhaustion timing.
- Claim readiness from static manifests alone; files do not prove deployment or measured behavior.
- Govern an 8 GiB target with explicit envelopes and a profile-based evidence suite.

## Decision

<a id="single-vm-resource-envelope"></a>
<!-- migration-source: 04.08 -->
<!-- migration-source: 21.01 -->
The PoC targets one 8 GiB VM and treats resource exhaustion as a correctness
event. JVM memory, JDBC, workers, requests, transaction deadlines, and query
timeouts are bounded. Docker, Kubernetes, and Helm artifacts are inputs to the
evidence suite, not proof of deployment. Completion requires measured profiles
for the intended topology and failure modes.

## Consequences

### Positive

Resource assumptions become reviewable constraints with observable fail-closed
behavior instead of implicit production claims.

### Negative

The acceptance suite is environment-sensitive and needs orchestration,
measurement retention, and periodic revalidation as component budgets change.

### Risks

Current module smoke evidence can be mistaken for a full mixed-load result;
the Partial status and snapshot wording prohibit that inference.

## Compliance and verification

- Funds-core packaging and smoke checks exercise bounded JVM and container settings.
- Worker, JDBC, request, and transaction limits are documented and tested where implemented.
- Delivery remains Partial until the complete profile-based suite is deployed, measured, and retained.

## Implementation evidence

- 9a64624b1fb974d141e6782a0bbc2a24867d66b5 changed: architecture/modern-core-banking-comprehensive-design-revised.md
- a0bfc223a45ee61e0469b3f124240f5ea9797350 changed: services/funds-core/Dockerfile.jvm; services/funds-core/README.md
- a22709f0a4c486f3d9ca32fbb7a919498ae8310d changed: services/funds-core/scripts/prod-runtime-smoke.sh
- 5c6b44bb23f830e1e5f31201ce3b78bcf4954838 changed: services/funds-core/README.md; services/funds-core/src/main/resources/application.properties; services/funds-core/src/test/java/com/corebanking/funds/WorkerPoolBoundsIT.java
- e5b2a98ff3a5e3b469e76d4ba5b0900a10ca0490 snapshot: infrastructure/kubernetes/infra-modern-core-banking-poc/namespace.yaml; infrastructure/helm/modern-core-banking-poc/Chart.yaml
