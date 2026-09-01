---
title: Funds-core glossary
status: current
owners:
  - architecture
last_verified: 2026-09-01
related_adrs: []
code_refs:
  - services/funds-core/README.md
  - services/funds-core/src/main/java/com/corebanking/funds/domain/
---

# Glossary

<a id="bank-accounting-terms"></a>
<!-- migration-source: 08.09::01 -->
- **Debit:** Positive signed posting in this kernel.
- **Credit:** Negative signed posting in this kernel.
- **Signed posting:** Currency amount expressed in signed integer minor units.
- **Natural balance:** Posting total adjusted for debit- or credit-normal side.
- **Journal:** Immutable balanced accounting record containing postings.
- **Posting:** One journal line applied to a ledger account.
- **Ledger account:** UUID-based balance-bearing financial identity.
- **External account identifier:** Address resolving to, but distinct from, a ledger account.
- **NUBAN:** Nigerian Uniform Bank Account Number; a validated external-address foundation.
- **Idempotency:** Return of a completed result rather than a repeated financial effect.
- **Reversal:** Linked exact negation of an original journal preserving history.
- **Outbox:** Durable transactional row for later relay.
- **Book:** Accounting scope owning journal and period coordinates.
- **Chart version:** Immutable per-book account-classification mapping version.
- **Accounting period:** Book-owned period that must be open for posting.
- **Control account:** Classified account with a projection checked against source postings.
- **Trial balance:** Per-book/per-currency debit-credit proof at a cutoff.
- **Current state:** Reviewed fact supported by implemented evidence.
- **Proposed state:** Design or plan that is not current architecture.
