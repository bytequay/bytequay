# ByteQuay Design

This directory is the entry point for ByteQuay's whole-system design. ByteQuay
is a local-first macOS application with an Electron and React frontend, one
localhost Spring Boot backend, GitHub integration, and SQLite persistence.

The current product implements pull-request workflows and a local agent
subsystem. A rich user-facing agent task surface is proposed for review and is
not shipped behavior. The design distinguishes current implementation, the
existing backend invariants it must retain, and planned UI work so a proposed
surface is not mistaken for an existing one.

## Design documents

- [Architecture](architecture.md) describes the whole system, Maven module
  boundaries, dependency direction, the dedicated SQLite persistence module,
  runtime composition, and extraction sequence.
- [Agent Service](agent-service.md) records the current worktree-scoped Codex
  App Server and Claude implementation and specifies the task, turn, event,
  approval, queue, attachment, and subagent contracts required by the planned
  rich task surface.
- [Codex App Server compatibility fixture](codex-app-server-compat.md) records
  the executable v0.151.0 schema and live-runtime evidence, together with the
  CP1 isolation and approval evidence that remains open.
- [Agent Task UI](agent-task-ui.md) specifies the proposed composer,
  transcript, approvals, folded activity, plans, images, subagent inspection,
  steering, queue, Electron transport, and incremental delivery sequence.
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
- Use Codex App Server as the Codex harness and rich-client protocol; keep
  ByteQuay responsible for product state, cross-provider events, and UI.
- Start with the App Server's native harness, tools, approvals, and event
  stream. ByteQuay-defined model tools and workflow actions require a separate
  later design.
- Treat proposed designs as implementation guidance, not compatibility
  requirements for deleted systems.

## Agent-task review decisions

The proposed task surface is ready for product/design review, not
implementation approval. The decisions to accept or change are:

1. **Runtime shape:** one shared Codex App Server, one official
   task-owned exec-server/environment per active Codex task, and a separate
   Claude CLI lane.
2. **UI contract:** ByteQuay persists and renders normalized messages, plans,
   approvals, activity/logs, images, metrics, queue/steer state, and root/child
   agents; it never renders raw provider JSON or hidden reasoning.
3. **Tool boundary:** the first release uses only the App Server's native
   harness tools and their typed events. It registers no ByteQuay-defined
   model tool or product workflow; commit integration is deferred with the
   rest of that tool contract.
4. **Release tradeoffs:** App Server/environment APIs are experimental and the
   stock task executor has a same-host authentication gap.
5. **Delivery order:** protocol/replay foundations first, then a usable Codex
   task, rich transcript, subagents, steer/queue, and finally Claude parity.

## Release blockers

The next release that exposes agent-backed development tasks must not ship
until provider authentication readiness is implemented end to end. A fresh or
expired Claude installation currently opens the local CLI successfully but
fails only after ByteQuay starts a turn, because CLI presence and remote
authentication are different checks.

The required backend gate, workspace-settings behavior, UI states, recovery,
and acceptance criteria are defined in
[Agent Service: Workspace settings and provider readiness](agent-service.md#12-workspace-settings-and-provider-readiness).

The rich task surface also must not ship until the event/approval contract,
authenticated Electron SSE bridge, reconnect replay, image boundary, and
root/child/item quiescence and restart-safe worktree ownership satisfy the
acceptance criteria in
[Agent Task UI](agent-task-ui.md#8-acceptance-criteria).

Codex App Server is also a conscious release-risk decision: its official
documentation currently marks the command experimental and unsupported for
production workloads. A release must explicitly accept that dependency and
maintain a fixed reference fixture for the experimental per-task environment
and background-terminal methods used by isolation and the quiescence gate;
transport success alone is not sufficient. Production keeps Codex's startup
self-update enabled and runs the user's installed CLI without treating any
reported version text, including an unparseable string, as a readiness gate. An
incompatible operation fails with that detected version in its diagnostic so it
can be reported and fixed. The stock
exec-server's unauthenticated loopback listener is also an explicit same-host
trust decision: an ephemeral port is not treated as authentication. The first
release does not add the separate, process-wide `--code-mode-host` dependency.

The same release gate must reject ambient exec-policy allow rules before a
turn starts. The v0.151 App Server exposes neither `--ignore-rules` nor an
effective-rule inventory, so a per-thread override is not a valid design.
ByteQuay instead isolates `CODEX_HOME`, reads the effective config layers,
rejects project config, and conservatively rejects any `.rules` file beside a
returned system, user, or project policy-bearing source for every task cwd.
`packagedDefaults` and `legacyManagedConfigTomlFromFile` layers are not
exec-policy sources. ByteQuay separately requires
`configRequirements/read` to report no applicable managed requirements. A
missing `requirements` field, non-null value, request failure, or undecodable
response fails closed. This security-specific response must contain exactly
that one top-level key; an unknown sibling policy field also fails closed. The
exact v0.151 executable gate proves that the isolated keyless value is
explicitly null; supporting a broader shape later requires new executable
evidence. That gate also verifies the effective no-child isolation baseline,
empty hook and MCP configuration, and permits only bundled system skills rooted
in the isolated `CODEX_HOME`. Because the baseline disables multi-agent, CP1
must separately prove a child-capable profile with the same readiness checks
before CP1 completes or CP2 begins.
