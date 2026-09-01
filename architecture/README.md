# Architecture documentation governance

## Authority and ownership

When sources overlap, authority is ordered as follows:

1. Executable implementation and tests evidence actual behaviour.
2. `architecture/arc42/` records reviewed, verified current architecture.
3. Accepted ADRs explain significant architectural choices.
4. `architecture/proposals/` records designs that are not current.
5. `docs/superpowers/plans/` records delivery steps, not system truth.
6. Archived documents preserve history and are not authoritative.

Every architecture document has an owning service or repository role. Owners
are responsible for review; they do not have unilateral authority to change a
cross-cutting decision. Service-local documentation owns detailed contracts and
operating instructions, while arc42 retains system responsibilities and
boundaries.

## Update and review rules

Update architecture artifacts when a change affects a boundary, invariant,
contract, resource budget, quality guarantee, current-state fact, decision,
proposal, or diagram. Every pull request assesses its architecture impact and
identifies affected architecture documents, ADRs, proposals, diagrams, and
verification evidence. A stable pull-request URL or full commit hash provides
traceability; branch links are not durable evidence.

Review current-versus-proposed classification and unimplemented accepted ADRs
monthly during active PoC development; verify relevant arc42 views and
diagrams at delivery milestones; and review accepted ADRs, risks, technical
debt, deployment assumptions, and resource budgets quarterly or before a
production-readiness claim.

Archive material only after its current replacements, decision rationale,
proposals, plans, or historical status have been reviewed and linked. Archived
material remains history, never current authority.

<a id="architecture-review-checklist"></a>
<!-- migration-source: 25 -->
## Architecture review checklist

A reviewer should reject the design or implementation if any answer below is unclear:

- Can one database transaction enforce each money invariant?
- Is the owner of balances, holds, float reservations and journals unambiguous?
- Can a committed journal ever lack a durable outbox record?
- Can any retry create a second financial effect?
- What exact evidence permits provider fallback?
- Does each reconciliation break type have a correct accounting action?
- Is the bank-side debit/credit effect and settlement model explicit for every implemented workflow state?
- Do legal entity, book, chart version and accounting period participate in every posting guard?
- Do independently derived trial balance and subledger/control-account totals agree?
- Can a partial, corrected or late source be prevented from producing a false reconciliation proof?
- Can a ledger-only break avoid a duplicate posting?
- Is tamper evidence anchored outside the database trust boundary?
- Can projections and statements be rebuilt deterministically?
- Are public, service, data, and privileged-operation trust boundaries explicit?
- Are secrets required and PII excluded from telemetry and workflow search metadata?
- Are the PoC's claims narrower than the evidence it produces?
- Are Java heap/native memory, Go runtime memory, database connections, queues, broker retention and disk bounded?
- Does the exact 8 GiB test artifact state which profile and components were active?
- Can broker outage, OOM, disk pressure or pool exhaustion shed load without losing accepted intent?
- Can maker-checker, direct database privilege and submission crash-window tests demonstrate the claimed controls?
- Does externally final inbound value route safely for every restricted, closed or unknown destination?
- Does a concurrent idempotency owner crash produce one stored result without stranding an in-progress command?
- Can every event class be reconstructed for the defined recovery window after broker and published-row loss?
- Do exact profile overlays prove per-container CPU, memory, PID, connection and volume limits?
- Can several provider virtual accounts—and only one active primary NUBAN—resolve to the same ledger account without making an address a balance holder?
- Is inbound idempotency based on external session/evidence identity rather than the destination NUBAN?
- Does every customer account retain its immutable product version, including after terms change?
- Can accrual, capitalisation, maturity and liquidation restart without duplicate recognition or unbounded per-account memory?
- Are non-interest products structurally separated from conventional interest templates and independently prove pool-allocation conservation?
