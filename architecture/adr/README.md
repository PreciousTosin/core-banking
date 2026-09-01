# Architecture Decision Records

ADRs use zero-padded sequential identifiers and kebab-case filenames, such as
`0001-use-postgresql-as-the-system-of-record.md`. Their titles begin with the
matching `ADR-0001` identifier.

## Statuses and threshold

Decision statuses are `Proposed`, `Accepted`, `Rejected`, `Superseded`, and
`Deprecated`. Implementation statuses are `Not started`, `Partial`,
`Complete`, and `Not applicable`; they are independent of decision status.

Create an ADR for a material change to service/module boundaries, financial
invariant ownership, accounting semantics, authoritative data ownership,
public/provider/cross-language contracts, security/regulatory/audit/trust
boundaries, deployment/failure domains, resource budgets, consistency,
concurrency, idempotency, recovery/delivery guarantees, or deliberately
accepted significant technical debt. Routine refactoring, local details,
documentation corrections, and compatible dependency patches do not require
one unless they cross that threshold.

## Lifecycle and permanence

New non-retrospective records start `Proposed`. Only retrospective records
with verified pre-introduction historical evidence may be introduced directly
as `Accepted` or `Rejected`, apart from the exact one-time ADR-0001 adoption
binding in Task 5. Thereafter the permitted lifecycle is:

- `Proposed -> Proposed`, `Accepted`, or `Rejected`
- `Accepted -> Accepted`, `Superseded`, or `Deprecated`
- `Superseded -> Superseded` and `Deprecated -> Deprecated`, retaining all
  accepted-record protections
- `Rejected -> Rejected` only when the complete file is byte-for-byte
  identical

While the parent is `Proposed`, substantive sections and
relationship/evidence fields may be freely revised, including on a
`Proposed -> Proposed` edge. A material reversal after acceptance requires a
new ADR that supersedes the original.

Accepted, Superseded, and Deprecated records cannot be deleted or renamed, and
their accepted rationale is permanently immutable. Rejected records also
cannot be deleted or renamed. After rejection, no field, prose, relationship,
implementation status, compliance result, or evidence item may be appended,
removed, or rewritten.

## Evidence

Use path-bound local evidence in exactly one of these forms:

```text
- HASH changed: repository/path; repository/path
- HASH snapshot: repository/path; repository/path
```

`changed` proves the named path changed in the commit. `snapshot` proves an
observed tree state, rather than introduction. A stable GitHub pull-request URL
is also valid only when it names the normalized current `origin`
owner/repository; URLs for another repository, an absent/non-GitHub origin, or
movable branches are not stable same-repository evidence.
