# ByteQuay Design

This directory is the entry point for ByteQuay's whole-system design. ByteQuay
is a local-first macOS application with an Electron and React frontend, one
localhost Spring Boot backend, GitHub integration, and SQLite persistence.

The current product implements pull-request workflows and the shipped, ungated
local agent-task surface. These documents distinguish current invariants from
historical checkpoint evidence and explicitly deferred future work.

## Design documents

- [Architecture](architecture.md) describes the whole system, Maven module
  boundaries, dependency direction, the dedicated SQLite persistence module,
  runtime composition, and extraction sequence.
- [Agent Service](agent-service.md) records the current worktree-scoped Codex
  App Server and Claude implementation and specifies the task, turn, event,
  approval, queue, attachment, and subagent contracts used by the rich task
  surface.
- [Managed Worktree Service](worktree-service.md) specifies the approved
  stateless, synchronous separate-worktree boundary shared by interactive
  tasks, cherry-pick, and CI Autofix: provision, inspect, and non-destructive
  cleanup that always retains the generated branch.
- [CodeGraph Service](codegraph-service.md) specifies the optional,
  fail-open structural exploration capability for managed agent worktrees:
  lazy per-worktree indexes, incremental refresh, two task-bound tools, and
  ByteQuay-managed instructions without repository-file changes.
- [Codex App Server compatibility fixture](codex-app-server-compat.md) records
  the executable v0.151.0 schema and compatibility evidence, together with the
  completed CP1 technical isolation and opt-in local real-model smoke evidence.
- [Agent Task UI](agent-task-ui.md) specifies the approved composer,
  transcript, approvals, folded activity, plans, images, subagent inspection,
  steering, queue, Electron transport, and incremental delivery sequence. The
  linked document records commit `84af81c73cea7ae2cf7a53681827f550c598b706`
  as the approved static visual and state prototype.
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
  later design. The CodeGraph service is the narrow exception for two
  fixed, optional structural-exploration tools; it is not a product-action or
  general tool framework.
- Treat proposed designs as implementation guidance, not compatibility
  requirements for deleted systems.

## Agent-task review decisions

The task surface completed CP11 and ships ungated. The accepted decisions are:

1. **Runtime shape:** one task-owned Codex App Server per active turn, never
   more than one for a task, rooted at that task's worktree. It is verified
   stopped at turn completion, and the next turn resumes the same thread in a
   new matching-profile process. Claude remains a separate CLI lane.
2. **UI contract:** ByteQuay persists and renders normalized messages, plans,
   approvals, activity/logs, images, metrics, queue/steer state, and root/child
   agents; it never renders raw provider JSON or hidden reasoning.
3. **Tool boundary:** the first release uses only the App Server's native
   harness tools and their typed events. It registers no ByteQuay-defined
   model tool or product workflow; commit integration is deferred with the
   rest of that tool contract.
4. **Release tradeoffs:** App Server remains experimental. The removed
   executor/environment and loopback-listener design is not part of the shipped
   topology; one task-owned process runs per active turn in the managed
   worktree. The App Server
   risk was accepted on 2026-08-31 and reconfirmed when CP11 passed on
   2026-09-04.
5. **Delivery order:** protocol/replay foundations, usable Codex tasks, rich
   transcript, subagents, steer/queue, and Claude parity are all implemented.
6. **Checkout isolation:** every agent task receives a separate
   ByteQuay-provisioned worktree. One shared synchronous service provisions,
   inspects, and removes verified clean worktrees for interactive tasks,
   cherry-pick, and CI Autofix; each caller persists its immutable worktree
   facts, and Branch Rebase borrows its caller's worktree.

## Release evidence and retained constraints

CP11 shipped only after provider authentication readiness was implemented end
to end. A fresh or expired Claude installation is now blocked before task,
turn, worktree, or workflow-attempt allocation, because CLI presence and remote
authentication are different checks.

The required backend gate, workspace-settings behavior, UI states, recovery,
and acceptance criteria are defined in
[Agent Service: Workspace settings and provider readiness](agent-service.md#12-workspace-settings-and-provider-readiness).

The shipped rich task surface passed the event/approval contract,
authenticated Electron SSE bridge, reconnect replay, image boundary,
root/child/item quiescence, and revalidated worktree-ownership criteria in
[Agent Task UI](agent-task-ui.md#8-acceptance-criteria).

Codex App Server is also a conscious release-risk decision: its official
documentation currently marks the command experimental and unsupported for
production workloads. ByteQuay accepted that dependency for the first
agent-task release on 2026-08-31 and reconfirmed it at CP11. ByteQuay maintains
a fixed reference fixture for the launch profiles and background-terminal
methods used by isolation and quiescence; task sessions opt into
`experimentalApi` solely for background-terminal inventory and termination.
Transport success alone is not sufficient. Production updates Codex only when
its parsed version is below the supported minimum and runs the user's installed
CLI without treating newer or unparseable version text as a readiness gate. An
incompatible operation fails with that detected version in its diagnostic so it
can be reported and fixed. The first release does not
add the separate, process-wide `--code-mode-host` dependency.

Agents do not edit the managed checkout. The approved workspace boundary is one
separate generated worktree per task, provisioned and cleaned through the
[Managed Worktree Service](worktree-service.md). Normal cleanup requires a
clean generated branch, uses non-force worktree removal, and always preserves
that branch as the local result. Destructive discard or branch deletion is not
part of the approved worktree-service contract and requires a separate future
design if the product needs it.

Production readiness continues to reject ambient exec-policy allow rules
before a turn starts. The v0.151 App Server exposes neither `--ignore-rules` nor an
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
in the isolated `CODEX_HOME`. CP1 separately proved child-capable V1 and V2
profiles with the same readiness checks. Production queries the catalog under
the no-child profile, launches exactly the selected model's advertised V1-only
or V2-only profile, and recursively closes the corresponding relationship
history through paginated `thread/items/list`. The compatibility fixture's
default path remains offline, authentication-free, and safe for CI. Its `--live` and
`--live-children` paths are opt-in local real-model smoke gates. Each requires
`BYTEQUAY_CODEX_COMPAT_HOME` to name a separate persistent Codex profile outside
the repository that was signed in directly with ChatGPT; the ambient profile
and a profile prepared by copying `auth.json` are forbidden. App Server must
verify through `account/read` that OpenAI authentication is required and the
account type is `chatgpt`, and use a temporary
`CODEX_SQLITE_HOME` for its database state. The fixture holds an exclusive
profile lock before reading mutable auth/runtime state, rejects persistent
Codex-home instruction files, and removes its run-scoped rollout, writer-lock,
shell-snapshot, and trust records after every owned process stops. Model-requested
tool processes remain credential-free: model-requested commands must prove that `CODEX_API_KEY`,
`OPENAI_API_KEY`, `CODEX_HOME`, and the App Server-only sentinel are absent.
API-key fixture support is deferred. The live paths' presence is not evidence;
both the no-child `--live` fixture and recursive `--live-children` fixture
passed locally with that subscription-backed profile on 2026-08-31. CP0
through CP11 are complete: the workspace-scoped task/turn contract, typed
readiness, schema-v2 event/source and approval contracts, crash-safe replay,
task isolation, recursive item reconciliation plus verified per-turn process
stop, rich UI, queue/steer behavior, and Claude parity are implemented and the
development gate is removed. The seven-day
detailed-runtime retention policy was accepted for this release on 2026-09-04.
Process-environment stripping still does not create a same-user
filesystem-read boundary around the persistent login profile.
