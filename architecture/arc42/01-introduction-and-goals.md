---
title: Funds-core introduction and goals
status: current
owners:
  - architecture
last_verified: 2026-09-01
related_adrs: []
code_refs:
  - services/funds-core/
  - services/funds-core/README.md
---

# Introduction and goals

The implemented slice is the Java 25/Quarkus `funds-core` accounting kernel,
not a complete bank. It preserves accounting facts through exact journal
posting, reversal, balance maintenance, and proof. Its current acceptance
boundary is the [funds-core README](../../services/funds-core/README.md).

<a id="implemented-claim-boundary"></a>
<!-- migration-source: 03.02 -->
The kernel owns exact money, journal validation, posting, reversals, balances,
chart governance, proofs, database roles, and outbox persistence. Developers
and operators are the current human stakeholders. This PoC does not claim a
customer-facing product, full topology, or production readiness.
