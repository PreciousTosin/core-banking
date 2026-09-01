---
title: Funds-core runtime view
status: current
owners:
  - architecture
last_verified: 2026-09-01
related_adrs: []
code_refs:
  - services/funds-core/src/main/java/com/corebanking/funds/application/PostingService.java
  - services/funds-core/src/main/java/com/corebanking/funds/application/ReversalService.java
  - services/funds-core/src/main/java/com/corebanking/funds/application/proof/AccountingProofService.java
---

# Runtime view

## Posting

[PostingService](../../services/funds-core/src/main/java/com/corebanking/funds/application/PostingService.java)
verifies the typed request hash, begins a serializable transaction, and
acquires idempotency ownership. It validates and locks book, period, and
account state; assigns sequences; validates the journal; persists journal and
postings; updates balances and control projection; writes outbox data;
completes idempotency; and commits.

## Reversal and proof

[ReversalService](../../services/funds-core/src/main/java/com/corebanking/funds/application/ReversalService.java)
loads original facts, builds an exact negated linked journal in an open period,
uses the trusted reversal path, and preserves additive history.
[AccountingProofService](../../services/funds-core/src/main/java/com/corebanking/funds/application/proof/AccountingProofService.java)
independently aggregates immutable source postings and compares materialised
balance and control projections.
