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
