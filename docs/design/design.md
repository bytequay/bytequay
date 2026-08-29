# ByteQuay Design

This directory is the entry point for ByteQuay's whole-system design. ByteQuay
is a local-first macOS application with an Electron and React frontend, one
localhost Spring Boot backend, GitHub integration, and SQLite persistence.

The current product implements pull-request workflows and a local agent
subsystem. The design distinguishes shipped behavior, known integration gaps,
and follow-up work so a proposed replacement is not mistaken for current
behavior.

## Design documents

- [Architecture](architecture.md) describes the whole system, Maven module
  boundaries, dependency direction, the dedicated SQLite persistence module,
  runtime composition, and extraction sequence.
- [Agent Service](agent-service.md) records the current worktree-scoped Codex
  App Server and Claude task implementation, its event/UI gaps, and the planned
  follow-up toward one App Server-based framework.
- [CI Autofix Service](ci-autofix-service.md) specifies a user-started,
  bounded watch/fix/commit/push loop for pending or failing GitHub Actions checks. Review
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

When a cherry-pick conflicts, ByteQuay commits Git's conflict snapshot as the
source-marked target commit `C`, then the agent resolves it in one directly
adjacent `fixup!` commit `F`. Both commits remain in the generated branch: the
item records `C` as its target while the execution tip advances to `F`. This is
also true when `tree(F)` equals the previous target tree, because the pair is
the durable attribution for how the conflict was settled. The tradeoff is
deliberate: `C` may contain conflict markers or an incomplete selected side and
therefore may not build when checked out alone; only the `C` plus `F` boundary
is the validated result. The first implementation keeps the ByteQuay validation
hook as a successful no-op; a project-aware command needs an explicit
user-selected validation profile because ByteQuay cannot infer how an arbitrary
repository builds.

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
