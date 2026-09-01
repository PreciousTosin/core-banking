# Account Identifiers and Simulated NIP Inbound Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Keep checkbox state in this file.

**Goal:** Let one customer ledger account safely own a primary synthetic NUBAN and multiple provider virtual-account aliases, expose account details, and process simulated NIP/provider inbound credits exactly once without confusing an account address with transaction identity.

**Architecture:** Java `funds-core` owns identifier lifecycle, authoritative resolution and posting. Go `provider-gateway` owns provider/NIP protocol evidence; Go `txn-orchestrator` coordinates inbound workflow; Go `projections` serves masked account details. Protobuf is the only cross-language contract. PostgreSQL remains authoritative; caches are optional, bounded and non-authoritative.

**Tech Stack:** Java 25/Quarkus/PostgreSQL 18.6; Go; Protobuf/gRPC; Temporal Go SDK; deterministic Go provider simulator; JUnit and Go tests; Testcontainers/Compose.

**Prerequisites:** Accounting-kernel migrations and posting service, funds-control restrictions, Java/Go contract generation, transactional outbox/inbox foundations.

**Acceptance:** INV-24 and INV-26–INV-28; ACC-33 and ACC-38–ACC-39.

## Non-negotiable decisions

- Ledger UUIDs hold financial state. NUBANs and virtual accounts are addresses only.
- An active scoped identifier maps to one ledger account; one account may have many aliases.
- At most one active primary NUBAN exists per customer account. Provider aliases may coexist across providers and purposes.
- External event identity is `(source, provider_or_nip_session_id)` plus canonical evidence hash—not the destination account number.
- Nigeria has no generated ISO IBAN in this implementation.
- `000000`/`0000000017` is synthetic and `SIMULATOR_ONLY`; a real-provider adapter must reject it.
- Internal/control accounts cannot receive external identifiers.
- Full identifiers and customer names never enter metrics, traces, Temporal search attributes or ordinary logs.

## Target files

```text
contracts/account/v1/account_identifier.proto
contracts/provider/v1/inbound_credit.proto
services/funds-core/src/main/java/com/corebanking/funds/application/address/
services/funds-core/src/main/java/com/corebanking/funds/infrastructure/postgres/JdbcAccountIdentifierRepository.java
services/funds-core/src/main/resources/db/migration/V005__account_identifier_lifecycle.sql
services/provider-gateway/internal/collections/
services/provider-simulator/internal/nip/
services/txn-orchestrator/internal/workflows/inbound_credit.go
services/projections/internal/accountdetails/
test/golden/account-identifiers/
test/acceptance/account_identifiers_and_inbound.sh
```

---

### Task 1: Freeze cross-language address and inbound-evidence contracts

**Files:** Create both Protobuf files and golden fixtures; add Java/Go generated-code compatibility tests.

- [ ] Write failing tests proving amount uses `int64` minor units, identifier scheme/routing scope are explicit, unknown finance-controlling enums fail safely, and destination address is separate from `external_session_id` and `evidence_hash`.
- [ ] Define `AccountAddress`, `ResolveAccountAddressRequest/Response`, `AccountDetails`, `InboundCreditEvidence` and `PostInboundCreditCommand`. Never put a customer name in an event key/header.
- [ ] Add fixtures for NUBAN, two providers' aliases, duplicate session/same hash and duplicate session/conflicting hash.
- [ ] Run Java and Go encode/decode/hash tests and Protobuf breaking-change checks.
- [ ] Commit: `feat(contracts): define account address and inbound evidence`

<a id="account-opening-delivery-detail"></a>
<!-- migration-source: 13.07.01 -->
### Task 2: Implement authoritative identifier lifecycle in Java

**Files:** Create address application classes/repository, V005 and JUnit integration tests.

- [ ] Write failing tests for allocate, register-provider-alias, resolve, mark-primary, retire and late-resolution-history operations. Race two allocations of the same number and two primary-NUBAN promotions.
- [ ] Make lifecycle append-only: immutable identifier version rows plus a current-state pointer/view. Commands use UUID idempotency ID and canonical hash; conflicting reuse fails.
- [ ] Revalidate customer account scope, NUBAN check digit, provider scope and uniqueness inside one transaction. Never accept `IBAN` without a country validator.
- [ ] Publish `AccountAddressChanged` in the same transaction; event payload carries masked display plus mapping ID, not an unmasked value unless the authorised consumer contract requires it.
- [ ] Run `NubanTest`, migration/constraint tests and lifecycle concurrency tests five times.
- [ ] Commit: `feat(funds-core): manage account identifier lifecycle`

<a id="account-address-api-delivery-detail"></a>
<!-- migration-source: 08.01.01::02 -->
### Task 3: Expose authoritative resolution and account details

**Files:** Add authenticated gRPC handlers in funds-core; create Go projection and API tests.

- [ ] Write failing tests for own-account details, privileged internal resolution, masked aliases, unknown/retired identifiers, enumeration throttling and closed-account details.
- [ ] Implement internal `ResolveAccountAddress`; return account ID, mapping ID, lifecycle/account status and version, never balance authority.
- [ ] Build `account_details` projection idempotently from address/account events. `GET /accounts/{id}/details` checks ownership/role and defaults to primary NUBAN; aliases are masked.
- [ ] Keep Name Enquiry separate: minimum permitted response, audit record, per-caller rate limit and no ownership implication.
- [ ] Destroy/replay projection and compare it with funds-core at a cutoff.
- [ ] Commit: `feat(accounts): add secure account details and resolution`

### Task 4: Implement multi-provider virtual-account issuance

**Files:** Add collection port, two deterministic simulator adapters and Go conformance tests.

- [ ] Write the shared adapter suite for issue, stable provider reference, duplicate request, timeout-before-write, timeout-after-acceptance, malformed value and cancellation/retirement.
- [ ] Persist issuance intent before network write. After ambiguity, query the same provider reference; do not request another alias until authoritative rejection.
- [ ] On success, register the alias through funds-core. Provider A and Provider B aliases for the same account must both remain active and independently resolvable.
- [ ] Reject `SIMULATOR_ONLY` identifiers in any adapter configured `REAL` and fail startup if synthetic institution configuration is combined with a real endpoint.
- [ ] Run race/fault conformance tests with two gateway replicas.
- [ ] Commit: `feat(provider-gateway): issue scoped virtual account aliases`

### Task 5: Build the NIP-style simulator

**Files:** Create simulator Name Enquiry, Direct Credit and TSQ endpoints plus fault scripts.

- [ ] Write failing tests for valid/invalid NUBAN, signed evidence, duplicate delivery, conflicting duplicate, delayed finality, out-of-order status and unknown destination.
- [ ] Implement deterministic session IDs and authentication evidence. Name Enquiry calls the internal resolution contract; Direct Credit records immutable simulator evidence before responding; TSQ returns its monotonic state.
- [ ] Add scripts for lost response after acceptance, duplicate callback, conflict, restricted/closed/retired alias and late final credit.
- [ ] Ensure simulator state cannot read expected journal results or funds-core tables.
- [ ] Commit: `feat(simulator): model NIP name enquiry and direct credit`

### Task 6: Orchestrate inbound credit and post exactly once

**Files:** Create Go workflow/activity, Java inbound posting template and crash integration tests.

- [ ] Write failing end-to-end tests for open, debit-blocked, credit-blocked, frozen, dormant, closed, unknown and retired-alias destinations.
- [ ] Ingest and authenticate evidence; durable-inbox it by external session/source; resolve the address; preserve mapping ID and canonical evidence hash.
- [ ] Derive the funds-core command ID deterministically from external identity. Post debit provider float / credit normal, restricted, unapplied or suspense liability under §8.14.
- [ ] Same session/same hash returns stored result. Same session/different hash opens a case and never posts. Destination number alone never deduplicates two legitimate credits.
- [ ] Kill gateway, workflow worker and funds-core before/after each durable boundary; query TSQ when finality is ambiguous.
- [ ] Commit: `feat(inbound): post resolved collection evidence exactly once`

### Task 7: Prove security, memory and acceptance behaviour

**Files:** Add acceptance script, redaction tests, load fixture and runbook.

- [ ] Generate at least 100,000 aliases mapped many-to-one; resolve in stable-key pages and prove the Java heap/Go RSS stay inside their profile limits.
- [ ] Bound cache by weight/TTL, gateway concurrency, request bodies, Temporal payload/history and DB pools. Disable cache and repeat correctness tests.
- [ ] Scan logs/traces/metrics/workflow search attributes for full NUBAN, provider alias and customer name; the test must fail on leakage.
- [ ] Run ACC-33, ACC-38 and ACC-39, including concurrency, conflict, retired alias, real-adapter synthetic rejection and projection rebuild.
- [ ] Record exact images/configuration/seeds/results and document that real NIBSS connectivity/certification is not claimed.
- [ ] Commit: `test(accounts): prove identifier and inbound invariants`

## Final verification

```bash
buf lint && buf breaking --against '.git#branch=main'
cd services/funds-core && ./mvnw clean verify
cd ../provider-gateway && go test -race ./...
cd ../provider-simulator && go test -race ./...
cd ../txn-orchestrator && go test -race ./...
cd ../projections && go test -race ./...
./test/acceptance/account_identifiers_and_inbound.sh
git diff --check
```

Expected: every command exits zero; full identifiers are absent from telemetry; two provider aliases and one primary NUBAN resolve to one account; duplicate/conflicting sessions follow policy; externally final value is recorded exactly once.
