# Architecture diagrams

Mermaid is the default textual diagram language. Each source uses this
seven-line metadata contract after its Mermaid front matter:

```text
---
title: CURRENT — Funds-core system context
---
%% state: CURRENT
%% abstraction: system-context
%% question: Which implemented actors and systems exchange information with funds-core?
%% owner: funds-core
%% arc42: architecture/arc42/05-building-block-view.md
%% adrs: ADR-0002, ADR-0004
%% last_verified: 2026-09-01
```

The Mermaid `title` directive must contain the same `CURRENT` or `PROPOSED`
state as the metadata. `abstraction` is a short stable level such as
`system-context`, `container`, `component`, `runtime-sequence`, or
`deployment`. `question` is the one review question the diagram is intended to
answer. The owner reviews the source against the related arc42 view and ADRs;
`last_verified` records that review rather than unrelated edits.

Render Mermaid sources with:

```bash
npx --yes @mermaid-js/mermaid-cli -i architecture/diagrams/<name>.mmd -o architecture/diagrams/generated/<name>.svg
```
