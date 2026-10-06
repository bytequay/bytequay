# ByteQuay Design

This directory is the entry point for ByteQuay's whole-system design. ByteQuay
is a local-first macOS application with an Electron and React frontend, one
localhost Spring Boot backend, GitHub integration, and SQLite persistence.

The current product implements pull-request workflows and the shipped, ungated
local agent-task surface. These documents distinguish current invariants from
historical checkpoint evidence and explicitly deferred future work.

## Design documents

### Product and planning

- [Agent guided goals](goal-guidance.md): conversation, research, learning,
  accepted work, recommendation cards, and durable outcomes.
- [Issue-backed development](issue-development-plan.md): GitHub issue-to-task
  launch and the current small delivery scope.
- [Work planning](issue-development.md): the broader product vision and roadmap.
- [Epic work items](epic-work-items.md): delivery containers, leaf work items,
  execution attempts, and progress.
- [Private circles](teams.md): personal groups, scoped activity, and attention.
- [Jira integration](jira-integration.md): account, ticket, and development handoff.

### Desktop and workspaces

- [Architecture](architecture.md): system and module ownership, dependency
  direction, storage, and runtime composition.
- [Watched repositories](watched-repo.md) and [workspace UI](watched-repo-ui.md):
  repository routing, Workspace Home, and approved interaction references.
- [Settings](settings-window.md): application and workspace settings surfaces.
- [WorkBench](workbench.md): shared PR and local-task navigation and review.
- [Code & Tests](code-tests.md) and [IDE integration](code-tests-ide-refactor.md):
  editor, language services, execution, history, and shared semantic queries.

### Agents and review

- [Agent service](agent-service.md): tasks, turns, providers, events, approvals,
  queues, attachments, subagents, recovery, and completion.
- [Agent task UI](agent-task-ui.md): composer, transcript, plans, steering,
  queueing, and approved UI references.
- [Managed worktrees](worktree-service.md): checkout ownership and cleanup.
- [DeepSeek key lane](deepseek-api-key-lane.md): saved-key execution and trust.
- [Codex compatibility](codex-app-server-compat.md): provider protocol and
  executable compatibility contracts.
- [Agent skills](agent-skills.md): scoped reusable instructions.
- [Agent review](agent-review.md): private review and explicit publication.
- [Discussion threads](discussion-threads.md): private answer discussions.
- [Review triage](review-triage.md): private discussion of GitHub comments.
- [Repository memory](repository-memory.md): sourced rules, recipes, and specs.
- [Spec checks](spec-check.md): reproduced gaps and user-filed issue drafts.

### Git and delivery

- [Commit history](commits.md): local history and refresh boundaries.
- [Cherry-pick ranges](cherry-pick-range.md): selection, confirmation, local
  execution, conflict resolution, and accounting.
- [Branch rebase](rebase-service.md): program-owned Git operations and conflicts.
- [Development delivery](development-delivery.md): accepted commit, draft-PR,
  and CI follow-up scope.
- [CI Autofix](ci-autofix-service.md): bounded watch, repair, and publication.
- [Workspace relationships and backports](workspace-backports.md): related
  repositories, conversation handoff, and sequential backport chains.

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
