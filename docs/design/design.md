# ByteQuay Design

This directory is the entry point for ByteQuay's whole-system design. ByteQuay
is a local-first macOS application with an Electron and React frontend, one
localhost Spring Boot backend, GitHub integration, and SQLite persistence.

The current product implements pull-request workflows. Agent development is a
new proposed subsystem and is not evidence that the feature already exists.
The design deliberately separates current behavior from future implementation.

## Design documents

- [Architecture](architecture.md) describes the whole system, Maven module
  boundaries, dependency direction, the dedicated SQLite persistence module,
  runtime composition, and extraction sequence.
- [Agent Service](agent-service.md) specifies worktree-scoped Codex and Claude
  tasks, Java service and provider interfaces, event streaming, approvals,
  metrics, storage, cleanup, recovery, and a future API-driver seam.

## Design principles

- Keep ByteQuay local-first and bind the backend to localhost.
- Keep GitHub writes behind direct user actions.
- Put framework-free contracts upstream and infrastructure implementations at
  the edges.
- Use SQLite for durable application facts and task-owned files for disposable
  execution history.
- Add modules only for real dependency, replacement, or testing boundaries.
- Treat proposed designs as implementation guidance, not compatibility
  requirements for deleted systems.
