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

`funds_app` has `USAGE` on the journal sequence because PostgreSQL requires it
for `nextval` during journal insertion. This also permits session-local
`currval`, which reveals only the value allocated by that same database
session. It does not permit `setval`, direct sequence reads, or sequence UPDATE
privilege. Sequence gaps after rollbacks or abandoned allocations are expected;
journal sequences are monotonic identifiers, not gapless business numbers.
