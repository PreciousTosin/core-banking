# Archived architecture proposals

Terminal `implemented`, `rejected`, and `superseded` proposal records move to
this directory under the same basename. Their registry pointer changes during
the move, while accepted ADR and any plan backlinks continue to use the stable
registry anchor in `architecture/proposals/README.md`.

Every terminal record retains closure evidence and ADR history. `related_plans`
is required and reciprocal when present. `implementing` and `implemented`
delivery requires at least one plan; a `rejected` or `superseded` record that
never entered planning uses the literal `None`.

Status-specific terminal fields are:

- `implemented`: `implementation_status: Complete` and one or more current
  architecture replacement links.
- `rejected`: `implementation_status: Not applicable` and `replacement: None`.
- `superseded`: `implementation_status: Not applicable` and one or more
  replacement proposal links.
