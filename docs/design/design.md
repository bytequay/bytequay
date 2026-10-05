# ByteQuay Design

This directory is the entry point for ByteQuay's whole-system design. ByteQuay
is a local-first macOS application with an Electron and React frontend, one
localhost Spring Boot backend, GitHub integration, and SQLite persistence.

The current product implements pull-request workflows and the shipped, ungated
local agent-task surface. These documents distinguish current invariants from
historical checkpoint evidence and explicitly deferred future work.

## Design documents

- [Agent guided goals](goal-guidance.md) proposes Goal conversations, research
  runs, courses taught in the Goal window, issue/ticket and spec-check discovery,
  accepted work items, durable outcomes and recommendation cards the user starts
  or rejects, and learns what the user knows from lessons, work and private
  cards. Its [implementation plan](goal-guidance-impl-tmp.md) covers trustworthy
  progress, one record per outcome and clear organization. The local work-item
  foundation is implemented; the conversation, research, courses, discovery and
  guidance remain planned.

- [Private circles](teams.md) defines account-local trusted circles, shared Home
  attention preference, the Circles page, bounded member activity, and observed
  collaboration statistics. Its [temporary checklist](team-impl-tmp.md) tracks
  implementation.

- [WorkBench](workbench.md) defines the shared PR/WorkBench navigation, task-bound
  working-copy inspection, local commit history, and explicit development review
  using the existing isolated reviewer.

- [Jira development integration](jira-integration.md) specifies the native Jira board
  and completed history, hosted OAuth boundary, local source context and shared
  development flow.

- [Issue-backed Development](issue-development-plan.md) defines the approved small
  GitHub release: local Epic marking, issue-to-task launch and navigation, and
  actionable work items in both existing Needs attention views. The broader
  [work planning design](issue-development.md) remains the later roadmap.

- [Epic work items](epic-work-items.md) describes how an Epic gets ByteQuay work
  items through its Epic view, syncs its source's children on a click, runs,
  breaks down and concludes its work, and shows item status and Epic progress;
  it is implemented.

- [Architecture](architecture.md) describes the whole system, Maven module
  boundaries, dependency direction, the dedicated SQLite persistence module,
  runtime composition, and extraction sequence.
- [Agent Service](agent-service.md) records the current worktree-scoped Codex
  App Server and Claude implementation and specifies the task, turn, event,
  approval, queue, attachment, and subagent contracts used by the rich task
  surface.
- [DeepSeek API-key task lane](deepseek-api-key-lane.md) is the saved-key
  choice for interactive development and CI repair using the existing Codex
  App Server adapter and the same Codex CLI task permissions. Its automation
  paths have passed offline verification; permission parity is implemented,
  while the full opt-in real-key gate remains open. Its
  [implementation checklist](deepseek-api-key-lane-impl-tmp.md) tracks
  checkpoints and verification.
- [Managed Worktree Service](worktree-service.md) specifies the approved
  stateless, synchronous separate-worktree boundary shared by interactive
  tasks, cherry-pick, and CI Autofix: provision, inspect, and non-destructive
  cleanup that always retains the generated branch.
- [Code & Tests](code-tests.md) defines the shared worktree IDE and fixed
  task-bound semantic query tool. Editor and agent queries use the same language
  services. Goal planning uses bounded baseline Git text excerpts.
  [CodeGraph](codegraph-service.md) is retired.
- [Codex App Server compatibility fixture](codex-app-server-compat.md) records
  the executable v0.151.0 schema and compatibility evidence, together with the
  completed CP1 technical isolation and opt-in local real-model smoke evidence.
- [Agent Task UI](agent-task-ui.md) specifies the approved composer,
  transcript, approvals, folded activity, plans, images, subagent inspection,
  steering, queue, Electron transport, and incremental delivery sequence. The
  linked document records commit `84af81c73cea7ae2cf7a53681827f550c598b706`
  as the approved static visual and state prototype.
- [Managed Agent Skills](agent-skills.md) specifies app-wide prompt skill
  management in Settings with Global, Development, and Review scopes, SQLite
  storage, and per-turn provider-neutral
  instruction delivery, including always-applied internal ByteQuay guidance
  that is excluded from user-facing skill management.
- [Agent Review](agent-review.md) defines shared **WorkBench Summary** for PR and development reviews and retained development learning: a private intention card, distinct commit
  implementation cards, and inline findings, shared local discussions, and workspace review
  conversations beside the PR. It reuses agent tasks, incremental review and
  explicit user publication. Its [implementation checklist](agent-review-impl-tmp.md)
  records delivery gates; the design is not a claim of implementation.
- [Discussion Threads](discussion-threads.md) specifies private, read-only side
  conversations about a quoted passage of a completed assistant answer: a
  separate provider conversation seeded with the task's history through the
  selected turn, one thread per passage, a reply-count footer in the transcript
  and a task side-panel tab. A discussion is not an agent task and never enters
  the task's own conversation, event log, outbox or stream.
- [Review Triage](review-triage.md) gives each GitHub comment a private,
  read-only thread with an agent, on a development task's own pull request or
  on a pull request with an agent review. Ask agent opens one; a development
  task's Auto review summary option has the agent summarize each new review
  comment. It never replies on GitHub or edits code, and a drafted reply is
  posted only by the user.
- [Repository memory](repository-memory.md) is the approved redesign that
  replaced the repository-context notes. Agents get the repository's guideline
  files and short rules captured where the information appears: pull request
  history, corrections in tasks and reviews, passing commands and spec pages,
  instead of notes learned by reading code. Phases 0 to 2 are implemented:
  guidelines reach every conversation, and the learning system and its note data are
  deleted.
- [Spec check](spec-check.md) is implemented: compare a directory's code with the
  specification it implements, reproduce each gap with a test the check writes
  and runs, and report only reproduced bugs, feature requests and improvements,
  each with a draft issue the user files.
- [Code & Tests](code-tests.md) specifies the task-bound WorkBench tab for reading whole
  files, following symbols, running tests, editing source and tests, and a terminal
  in the Agent review working copy. Its [implementation checklist](code-tests-impl-tmp.md)
  tracks delivery and remaining native acceptance checks. The [IDE refactor plan](code-tests-ide-refactor.md)
  records the verified modular-editor approach and migration gates for debugging,
  Git history, Java/Rust/Go support, and retiring CodeGraph.
- [CI Autofix Service](ci-autofix-service.md) specifies a user-started,
  bounded watch/fix/commit/push loop for pending or failing GitHub Actions checks. Review
  comment triage is outside that service; see [Review Triage](review-triage.md).
- [Development Task Delivery](development-delivery.md) defines approved default-on
  commit, draft-PR, and CI-fix settings after development turns. The agent
  commits and publishes; ByteQuay verifies the result, coordinates the existing
  CI loop, and marks the draft ready after verified green. The task stays open
  for later turns. The MVP uses an in-process after-turn coordinator, waits on
  explicit user-input gates, and fails rather than resumes after restart.
  Settings, accepted-turn choices, root readiness, commit/publication, bounded
  CI integration, checked source synchronization, and ready promotion are
  implemented. Its [implementation checklist](development-delivery-impl-tmp.md)
  records fixture/local verification and conservative unsupported-check limits;
  review-comment fixing and whole-flow restart recovery are excluded.
- [Workspace relationships and backport chains](workspace-backports.md) defines
  direct Fork/Dependency context, fresh conversation handoffs, and sequential
  backports through existing cherry-pick, PR, CI, and rebase services.
  All five checkpoints are implemented and verified with local Git, provider and
  browser fixtures. Its [temporary checklist](workspace-backports-impl-tmp.md)
  records the checks and live-provider limits.
- [Branch Rebase Service](rebase-service.md) specifies a program-owned rebase
  of one local branch onto another with agent-assisted conflict resolution,
  intended for explicit branch rebases and to replace CI Autofix's unverified
  agent-driven rebase. It is not automatic in the cherry-pick range flow.
- [Cherry-pick Range](cherry-pick-range.md) specifies source-range preview,
  target-presence detection, pull-request grouping and skip selection,
  conflict scope, immutable confirmation, local execution handoff, and final
  accounting.
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
is the verified result. ByteQuay runs no project validation hook; a
project-aware command needs an explicit user-selected validation profile
because ByteQuay cannot infer how an arbitrary repository builds.

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
  later design. Code & Tests defines one fixed, optional semantic-query
  tool shared with the IDE. Agent Review separately authorizes its fixed context/local-reporting
  tools for review-owned tasks, subject to its implementation gates. Neither
  creates a general tool framework or model-callable GitHub publisher.
- Attended development allows `git` and `gh` under a shared
  provider-independent authorization policy and the provider's native risk
  review. ByteQuay does not pass ambient GitHub token, `GH_CONFIG_DIR`, or
  `SSH_AUTH_SOCK` variables into agent processes. Provider prompts that
  reach ByteQuay use its task approval service. Every GitHub write needs
  explicit authority from user input or accepted automation. Complete action
  and target checks are model-based; native sandbox boundaries remain enforced
  separately. Review, CI, cherry-pick, and text-only sessions retain narrower
  profiles. Provider login versus API-key authentication does not change
  permissions. See
  [the shared command policy](agent-service.md#shared-development-command-policy).
- Code & Tests delegates test discovery, profiles and debugging to curated
  language extensions. Explicit run/debug dispatch saves source and obtains
  the existing worktree execution claim before launch; durable results retain
  source identity, raw output and honest unknown outcomes. It does not parse
  arbitrary terminal text into a passing test result.
- Treat proposed designs as implementation guidance, not compatibility
  requirements for deleted systems.

## Agent-task review decisions

The task surface completed CP11 and ships ungated. The accepted decisions are:

1. **Runtime shape:** one task-owned Codex App Server per active turn, never
   more than one turn-bearing process for a task, rooted at that task's
   worktree. It is verified stopped at turn completion, and the next turn
   resumes the same thread in a new matching-profile process. Claude remains a
   separate CLI lane. A read-only discussion session may run one further process
   on the same task and worktree; it takes no turn and changes nothing, and
   [Discussion Threads](discussion-threads.md) owns that boundary.
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
   risk was accepted and reconfirmed when CP11 passed.
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

**Completion correction:** recursively check provider
agent status, latest-turn status, background commands, and child
inventory, then record finished work before separate process cleanup. The supervisor preserves known process
identities and observation gaps; cleanup failure preserves a proven provider
result while retaining the worktree claim. This replaces the previous broad
macOS containment proposal. Implemented and verified; the contract
and regression evidence are in
[Agent Service §5.6](agent-service.md#56-process-ownership-correction). The later
protocol-truncation follow-up uses bounded status reads and lossless protocol
stdout with bounded backpressure; its additional regression gate is pending.

Codex App Server is also a conscious release-risk decision: its official
documentation currently marks the command experimental and unsupported for
production workloads. ByteQuay accepted that dependency for the first
agent-task release and reconfirmed it at CP11. ByteQuay maintains
a fixed reference fixture for the launch profiles and background-terminal
methods used by isolation and quiescence; task sessions opt into
`experimentalApi` for direct-child listing and background-terminal inventory
and termination.
Transport success alone is not sufficient. Production configures the minimum
Codex CLI version through `bytequay.agent.codex-minimum-version`, currently
`0.159.3`. At startup, an older installed CLI is automatically upgraded to that
exact release with a versioned npm installation; equal or newer versions are kept.
Readiness, catalog discovery, and task launch require the configured executable
to meet the minimum after any upgrade. Failed or unreadable version checks
cannot bypass that gate. A reported mismatch includes the versioned
installation command in its diagnostic.
The separate v0.151.0 fixture
remains historical protocol evidence.
An incompatible operation also reports the detected version so it can be
reported and fixed. The first release does not
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
or V2-only profile, and checks recursive completion through metadata-only
`thread/read` and a one-turn `thread/turns/list` page without items, verified on
installed 0.152.1. Parent-filtered
`thread/list` provides the child inventory that legacy history can omit. Legacy
history also synthesizes item IDs and omits tool activity, so exact lost provider
events cannot be reconstructed; ByteQuay's durable replay remains unchanged.
The former
`thread/items/list` dependency fails for legacy threads. The compatibility
fixture's
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
shell-snapshot, and trust records after every owned process stops. The fixture's
model-requested tool processes remain credential-free: commands must prove that `CODEX_API_KEY`,
`OPENAI_API_KEY`, `CODEX_HOME`, and the App Server-only sentinel are absent.
API-key support in this subscription fixture is deferred; the DeepSeek lane has
its own fixture. The live paths' presence is not evidence;
both the no-child `--live` fixture and recursive `--live-children` fixture
passed locally with that subscription-backed profile. CP0
through CP11 recorded completion of the workspace-scoped task/turn contract, typed
readiness, schema-v2 event/source and approval contracts, crash-safe replay,
task isolation, recursive item reconciliation plus verified per-turn process
stop, rich UI, queue/steer behavior, and Claude parity; the development gate was
removed. The later completion/cleanup correction above qualifies that
historical evidence and has its own regression gate. The seven-day
detailed-runtime retention policy was accepted for this release.
Process-environment stripping still does not create a same-user
filesystem-read boundary around the persistent login profile.
