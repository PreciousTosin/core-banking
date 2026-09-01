# Funds database migration roles

The role migration is a fail-closed bootstrap for a dedicated funds-core
PostgreSQL cluster. `V004__application_roles.sql` must be run once by a
controlled bootstrap login that can create roles and transfer ownership of the
existing `funds` schema objects. The migration intentionally fails if any of
`funds_migrator`, `funds_app`, or `funds_proof_reader` already exists; it never
adopts, alters, or removes memberships from a pre-existing cluster role.

After V004 succeeds, the database administrator grants `funds_migrator` to the
production Flyway login. Every later Flyway migration must execute
`SET ROLE funds_migrator` before creating or changing funds objects. This makes
`current_role`—and therefore each new object's owner—`funds_migrator`, and makes
the migrator's hardened default privileges apply. A later migration must reset
the role before doing work outside this ownership boundary.

Login roles and passwords are deployment-owned. Provision application and
proof-reader logins outside Flyway and grant each login exactly the capability
role it needs. Do not grant either login `funds_migrator`.

## Governed chart rotation

V006 makes operational chart replacement one owner-only database operation.
The controlled operator assumes `funds_migrator` and executes:

```sql
BEGIN;
SET LOCAL ROLE funds_migrator;
SELECT funds.rotate_chart_version(
    '<book-id>'::uuid,
    '<current-active-chart-id>'::uuid,
    '<complete-draft-chart-id>'::uuid,
    '<effective-timestamp>'::timestamptz);
COMMIT;
```

The operation locks both chart rows in UUID order and the stable book row next,
then revalidates book ownership, forward lifecycle/version, complete mappings
and the effective boundary before changing both statuses atomically. Validity is
half-open: `[activated_at, retired_at)`. The boundary cannot predate activation,
be in the future, or overlap an existing journal on the retiring chart.
`funds_app` has neither chart lifecycle `UPDATE` nor operation `EXECUTE`, so the
runtime cannot reproduce rotation with two raw statements.

Initial empty-book bootstrap and exceptional repair remain trusted migration-
owner actions. Like every PostgreSQL object owner, `funds_migrator` can alter
objects or bypass ordinary grants; it is therefore a control-plane trust
boundary, never an application credential. Once a book has an active chart,
operators use the governed operation rather than raw lifecycle `UPDATE`.

`funds_proof_reader` is an external, read-only proof-job capability, not the
service's default datasource role. The in-process posting and proof APIs use the
`funds_app` datasource; an independently scheduled proof job receives its own
deployment-managed login granted only `funds_proof_reader`. V005 grants that
role only the journal sequence/book/chart columns, posting account/currency/
amount columns, immutable chart-mapping account-currency/control-code columns,
and control-projection columns needed by the proof queries. It cannot read
account identifiers,
product policy JSON, idempotency results, materialised balances, or outbox
payloads. Control-account projection proof is deliberately current-cutoff-only;
historical cutoffs remain supported by the immutable-posting trial-balance
proof, but require projection history before they can be claimed for controls.

`funds_app` has `USAGE` on the journal sequence because PostgreSQL requires it
for `nextval` during journal insertion. This also permits session-local
`currval`, which reveals only the value allocated by that same database
session. It does not permit `setval`, direct sequence reads, or sequence UPDATE
privilege. Sequence gaps after rollbacks or abandoned allocations are expected;
journal sequences are monotonic identifiers, not gapless business numbers.

## Development reset

Flyway `clean` removes database objects, but PostgreSQL roles are
cluster-global and therefore survive it. For a development reset, recreate the
PostgreSQL container or cluster; that is the preferred and least ambiguous
procedure for this dedicated POC database. If recreation is impossible, use a
controlled administrator teardown that first terminates relevant sessions and
reassigns or drops every object owned by the capability roles before dropping
the exact roles. Never use an ad-hoc or casual `DROP ROLE` as a reset step.
