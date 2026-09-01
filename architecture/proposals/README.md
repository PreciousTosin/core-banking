# Architecture proposals

Proposals describe unimplemented designs. Allowed statuses are `draft`,
`proposed`, `approved`, `implementing`, `implemented`, `rejected`, and
`superseded`. An `approved` proposal does not mean it is current architecture:
it becomes current only when implementation and acceptance evidence update the
relevant current-state documents and diagrams.

## Governed proposal registry

This README is the permanent registry for governed proposal identities. Each
identity has a stable anchor here; its mutable pointer names the record's sole
active or archive location. When a terminal record moves, change the pointer,
not the identity anchor. Accepted ADRs and plan backlinks use the stable
registry anchor so they continue to identify the same proposal across a move.

Proposal records link their ADRs and implementation plans. Implemented,
rejected, and superseded records are archived under the same basename.
