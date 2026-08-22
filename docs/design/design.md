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
- [CI Autofix Service](ci-autofix-service.md) specifies a user-started,
  bounded fix/commit/push/monitor loop for failing GitHub Actions checks. Review
  comment triage is intentionally outside that service and will be designed
  separately.
- [Branch Rebase Service](rebase-service.md) specifies a program-owned rebase
  of one local branch onto another with agent-assisted conflict resolution,
  intended for explicit branch rebases and to replace CI Autofix's unverified
  agent-driven rebase. It is not automatic in the cherry-pick range flow.
- [Cherry-pick Range](cherry-pick-range.md) specifies source-range preview,
  target-presence detection, pull-request grouping and skip selection,
  conflict scope, immutable confirmation, local execution handoff, and final
  range review.
- [Commit History and Refresh](commits.md) specifies local-only commit display,
  durable commit-to-PR and avatar enrichment, scheduled and manual refresh,
  backend service contracts, and the renderer call chain.
- [Watched Repository / Workspace](watched-repo.md) specifies how watching a
  GitHub repository creates a durable workspace, provisions direct and fork
  repository routing, opens Workspace Home, and connects Commits to the
  existing cherry-pick flow.

When a cherry-pick conflicts, ByteQuay temporarily commits Git's conflict
snapshot with the standard `-x` line. The agent resolves it in one direct
`fixup!` commit. ByteQuay validates the final tree, then immediately squashes
the pair to one source-marked commit, or removes both when the final tree is
empty relative to the previous target tip. The first implementation keeps the
ByteQuay validation hook as a successful no-op; a project-aware command needs
an explicit user-selected validation profile because ByteQuay cannot infer how
an arbitrary repository builds.

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

## Release blockers

The next release that exposes agent-backed development tasks must not ship
until provider authentication readiness is implemented end to end. A fresh or
expired Claude installation currently opens the local CLI successfully but
fails only after ByteQuay starts a turn, because CLI presence and remote
authentication are different checks.

The required backend gate, workspace-settings behavior, UI states, recovery,
and acceptance criteria are defined in
[Agent Service: Workspace settings and provider readiness](agent-service.md#12-workspace-settings-and-provider-readiness).
