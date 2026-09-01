# Claude Code instructions

Follow [AGENTS.md](AGENTS.md). It holds the repository layout, the code
comment convention (see [docs/conventions/code-comments.md](docs/conventions/code-comments.md)),
the pull-request checklist, and the financial invariants that must not regress.

Run the `services/funds-core` test gate (`./mvnw clean verify`) in Docker,
never with a host database.
