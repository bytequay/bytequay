# Agent Service Implementation Checkpoints

> Temporary review draft. This file turns
> [Agent Service Design](agent-service.md) and
> [Agent Task UI Design](agent-task-ui.md) into an implementation sequence.
> It is not implementation approval and is intentionally not linked from the
> design index yet. After review, accepted decisions belong in the canonical
> design documents and delivery tracking; this temporary file can then be
> removed.

## 1. Outcome

Build ByteQuay's agent-task desktop surface on the agent service that already
exists. Do not build another agent harness.

The existing interactive shell is the executable reference client:

```text
scripts/agent-e2e.sh
    -> AgentController REST/SSE
    -> agent-core task and turn services
    -> Codex App Server or Claude CLI
    -> normalized events -> JSONL -> SSE
```

At the client boundary, the desktop replaces the shell's
`curl + jq + terminal` presentation:

```text
React UI
    <-> typed preload IPC
    <-> Electron main authenticated REST/SSE
    <-> existing and extended agent service
```

The desktop must not reproduce the shell's temporary backend, database, or
worktree lifecycle. CP2–CP4 still extend the existing production backend's
contract, durability, isolation, and recovery before the live UI ships.
Leaving a task screen unsubscribes that renderer only; it does not cancel or
complete the backend task.

## 2. Progress legend and checkpoint rule

- `[x]` is implemented/verified, or is a decision that has been explicitly
  resolved. Only Section 3 uses it specifically to mean already implemented in
  the current repository.
- `[ ]` is required work or an unaccepted decision.
- A checkpoint is complete only when every implementation and exit-gate box in
  that checkpoint is checked.
- Backend and frontend work may run in parallel after the shared contract is
  frozen, but a backend-only or renderer-only half-checkpoint does not count as
  complete.
- Each checkpoint must leave its affected modules buildable and must include
  the smallest automated check that would fail if its contract regressed.
- Java checkpoint changes pass collection arguments directly to Guava
  immutable `copyOf` operations without a redundant `requireNonNull` wrapper,
  following the project-wide rule in `CONTRIBUTING.md`.

## 3. Current baseline

Already implemented and to be reused:

- [x] One lazily started Codex App Server over stdio.
- [x] Codex root-thread start/resume and multiple turns in one session.
- [x] A separate Claude `stream-json` driver.
- [x] Provider-neutral message, plan, command, file, tool, approval, usage,
  lifecycle, and error mapping.
- [x] SQLite task, turn, metrics, completion, and cleanup facts.
- [x] Turn idempotency and one active turn per task.
- [x] Clean-worktree validation and an in-process exclusive lease.
- [x] Task JSONL, monotonic task sequence, replay, and SSE publication.
- [x] Cancel, approval resolution, completion, retention, and provider-status
  HTTP endpoints.
- [x] `scripts/agent-e2e.sh --interactive` as a real Codex/Claude backend
  reference client.
- [x] Existing automated regression floors for task/turn services, JSONL,
  replay/SSE, provider mapping, SQLite stores, HTTP security, and completion.

Missing for the reviewed rich-task design:

- [ ] Workspace-owned public task creation instead of renderer-selected paths.
- [ ] Production adoption of the CP1 reference projection and version-tolerant
  decoding across the required experimental surface.
- [ ] Proof that the reviewed per-task execution-environment design exists in
  that reference protocol and still works with the latest installed CLI.
- [ ] A task-owned Codex executor/environment and fail-closed isolation.
- [ ] Canonical event IDs plus root/child/turn/item source identities.
- [ ] Persisted user input and delivery state.
- [ ] Atomic SQLite state plus event-outbox persistence.
- [ ] Durable workspace leases, mutation claims, approvals, and recovery facts.
- [ ] Model, effort, and permission defaults with per-turn snapshots.
- [ ] An authenticated Electron task/event bridge.
- [ ] React task routes, reducer, transcript, composer, and task controls.
- [ ] Safe images, subagent inspection, steering, queueing, and Claude parity.

## 4. Fixed scope

These are scope rules, not claims that current code already enforces them:

- The new interactive task surface adds no ByteQuay model-callable business
  tool, commit tool, Git hook, or post-turn workflow. Existing workflow-specific
  adapters remain unchanged unless a checkpoint explicitly updates their
  shared agent contract.
- There is no generic abstraction combining interactive agent, cherry-pick, CI
  Autofix, and rebase tasks.
- There is no arbitrary executable/script provider. The shell is a reference
  client; production providers remain the Codex and Claude drivers.
- There is no custom Code Mode host or undocumented App Server queue API.
- First-release interactive Codex tasks must prove that inherited MCP servers,
  apps/plugins, dynamic tools, exec-policy allow rules, and lifecycle hooks are
  absent or disabled in CP1/CP4. They must also prove that no managed
  requirements apply; a missing field, non-null value, request failure, or
  undecodable response fails closed until a narrower contract has exact
  executable evidence.
- The renderer receives no raw provider JSON, backend log stream, credential,
  or hidden reasoning.
- The UI does not copy Codex Desktop assets or promise pixel parity.
- The frontend adds no component framework, CSS framework, or state library.
- The queue has no priorities, reordering, schedules, or per-child stop/steer
  UI.

## 5. Ownership

| Role | Owns |
| --- | --- |
| Runtime owner | App Server protocol and reference fixtures, Codex/Claude adapters, process supervision, task executor/environment, routing, and quiescence |
| Service/storage owner | Task/turn/event/approval contracts, workspace boundary, SQLite state, outbox, leases, recovery, and HTTP/SSE DTOs |
| Desktop owner | Electron REST/SSE client, preload API, React reducer, routes, task UI, accessibility, and plain CSS |
| Product/release owner | Experimental dependency decision, same-host trust decision, capability policy, and release gate |

One desktop owner should carry the first visible slice across Electron main,
preload, and React. Splitting those layers before the first vertical slice adds
handoff cost around one small event contract. Runtime and service owners supply
one checked-in fixture for that contract so desktop work can proceed in
parallel.

## 6. Delivery shape

```mermaid
flowchart TD
    C0["CP0 · Baseline and decisions"] --> C1["CP1 · App Server compatibility gate"]
    C1 --> C2["CP2 · Canonical ByteQuay contract"]
    C2 --> C3["CP3 · Durable replay and recovery"]
    C2 --> C5["CP5 · Electron task bridge"]
    C3 --> C4["CP4 · Codex task isolation"]
    C5 --> C6["CP6 · Fixture-driven task preview"]
    C4 --> C7["CP7 · First usable Codex task"]
    C6 --> C7
    C7 --> C8["CP8 · Rich transcript and images"]
    C8 --> C9["CP9 · Subagents"]
    C9 --> C10["CP10 · Steer and queue"]
    C10 --> C11["CP11 · Claude parity and release"]
```

Checkpoint 2 is the parallel-work cut: service/runtime work continues through
checkpoints 3 and 4 while the desktop owner builds checkpoints 5 and 6 from
the same recorded event fixtures. Checkpoint 7 is the convergence gate; it
must use the real runtime rather than fixtures.

## [ ] CP0 — Freeze the baseline and release decisions

**Owners:** product/release owner with runtime owner

**Depends on:** nothing

**Visible UI:** no

Purpose: preserve the working backend path and decide whether the proposed
experimental dependency is acceptable before extending it.

**Status (2026-08-30):** the engineering baseline is verified. The product
decisions about experimental maturity, same-host trust, and checkout ownership
remain open, so CP0 is not complete and CP2 must not start.

Implementation and decisions:

- [ ] Explicitly approve or reject shipping a product surface over Codex App
  Server while the official documentation describes the command as
  experimental and unsupported for production workloads.
- [ ] Explicitly accept or reject the unauthenticated same-host boundary of the
  reviewed stock task executor.
- [x] Choose Codex CLI/App Server `0.151.0` for the compatibility spike;
  this exact version and its hashes are reference-only and do not gate the
  production CLI.
- [ ] Decide whether an interactive task exclusively edits the existing managed
  workspace checkout or receives a separately provisioned task worktree.
- [ ] If it edits the managed checkout, define one workspace-wide mutation gate
  used by workspace sync, cherry-pick, CI Autofix, rebase, and every other
  ByteQuay-owned Git writer while the interactive lease is live; an agent-only
  lease is insufficient. External Terminal/editor writes cannot be blocked, so
  revalidate and fail closed when the checkout changes unexpectedly.
- [ ] If it uses a separate worktree, update the canonical design before CP2;
  this plan does not infer creation/deletion semantics.
- [x] Keep automatic Codex startup self-update enabled and use the
  user-managed installed CLI. The production probe records detected version text
  for diagnostics but never treats that output, including an unparseable
  string, as a readiness gate.
- [ ] Keep the new task UI behind a development-only gate until CP11.
- [ ] Capture sanitized request/event fixtures from the current task -> two
  turns -> replay/SSE -> completion flow.
- [x] Keep `TestAgentHttpApi` as the deterministic Spring HTTP integration
  regression test.
- [x] Remove the stale non-interactive branch from `agent-e2e.sh` and its docs,
  rather than restoring a test-only deterministic driver to production.
- [x] Keep `--interactive --provider codex|claude` as the manual real-provider
  reference path.

Exit gate:

- [x] `cd backend && mvn verify` passes before contract changes begin: 518
  tests passed with no failures or errors.
- [x] `./scripts/agent-e2e.sh --interactive --provider codex --minimal-worktree`
  completes two turns against the current backend.
- [x] A Claude interactive run either completes or records one actionable
  readiness/authentication limitation. On this development host, Claude CLI
  `2.1.251` reports `loggedIn: false`; run `claude auth login` before its real
  provider shell can pass.
- [ ] The latest-CLI update policy, reference-fixture version,
  experimental-risk decision,
  same-host trust decision, checkout strategy, and (when applicable) shared
  mutation-gate behavior are recorded in the canonical design.

Primary files:

- `scripts/agent-e2e.sh`
- `docs/design/agent-e2e.md`
- `backend/app/.../AgentCliUpdateJob.java`
- `backend/agent-cli/.../CodexCliDriver.java`

The real Codex reference run used one temporary task and returned
`first-turn-ok` and `second-turn-ok` from consecutive completed turns. The
shell then stopped the backend and removed its temporary repository, database,
runtime, and logs.

## [ ] CP1 — Prove the v0.151.0 reference contract

**Owner:** runtime owner

**Depends on:** CP0

**Visible UI:** no

Purpose: fail fast before persistence and UI depend on an experimental runtime
shape.

**Status (2026-08-31):** the checked-in schema and two-root live runtime gates
are implemented, but CP1 is not complete: one representative live
command approval, a bounded Git-worktree filesystem action, a non-empty
background terminal with targeted termination, child recursion, disk-backed
restart recovery, cross-task event/approval/command/filesystem isolation, a
safe live rerun with a dedicated API key that proves key isolation, and the
written go/no-go result remain open.
The checked-in fixture now has a separate `--live-children` path for the
recursive proof, but this host has no dedicated compatibility API key and has
not run it. Adding the executable gate is progress; only a passing live run
closes child recursion.
The no-child `--live` path now keeps two root tasks on separate executors,
completes task B while task A has a yielded command, verifies event, approval,
command, terminal, and filesystem ownership, and reloads both histories after
an App Server restart. Only a dedicated-key pass closes those gates.
Production adoption of the shared isolation guards and a CP1-approved
child-capable launch profile belongs to CP4. See
[Codex App Server compatibility fixture](codex-app-server-compat.md).

**Progress (2026-08-31):** the offline gate now projects and codec-tests the
v0.151.0 modern approval methods, top-level fields, requiredness, both command
kinds, every command/file decision shape, and the opaque permission payload
with its response scope. Legacy `applyPatchApproval` and
`execCommandApproval` requests are deliberately unsupported and fail closed.
The current runtime bridge still handles only command/file one-time
`accept`/`decline` and fails closed for unrepresented scope or stale decisions;
preserving every product-permitted decision offered by the provider in the
provider-neutral contract and UI begins in CP2. That set is the intersection of
the provider's legal choices and ByteQuay's immutable safety ceiling; an empty
set fails closed, and `acceptWithExecpolicyAmendment` is never product-permitted
in the first release. The gate also checks the background-terminal
list/terminate and `thread/resume` contracts and derives one compact committed
projection manifest instead of committing 413 schemas or 820 generated
TypeScript files. The normal fixture command verifies the manifest;
`--update-bindings` regenerates it intentionally. This remains reference-only
and never gates the production user-managed CLI. The safe live path is ready
to prove one real parent-command approval, one bounded mutation per repository,
a yielded command with targeted termination, and disk-backed history recovery
through a fresh App Server. This host has no dedicated compatibility API key,
so those live claims remain unchecked until that gate actually passes.

The auth-free offline gate now also proves the proposed first-release
exec-policy source and managed-requirements absence guards. It first validates
that a poison isolated-user `.rules` file is a real `allow` rule with the
documented `codex execpolicy check` command.
`config/read(includeLayers: true)` exposes that active isolated user layer, and
the guard rejects it before any thread or model turn. After the poison file is
removed, the clean workspace passes only after the guard checks the `rules/`
directory beside every returned system, user, or project policy-bearing source
and finds no `.rules` file. `packagedDefaults` and
`legacyManagedConfigTomlFromFile` layers are not exec-policy sources. The
behavioral poison control exercises the isolated user source without claiming
every source type is present. The live isolation path calls `config/read` for
every temporary task cwd. The exact App Server has no public `--ignore-rules`
option or rule-inventory RPC, so CP4 must adopt this conservative source
inspection; a per-thread config override cannot disable rules. The same keyless
App Server call pins the
`configRequirements/read` response shape, requires its
`requirements` field to be present and null, and fails for an omitted or
non-null value.

The same pre-thread gate now proves three configured launch profiles are
effective, not merely present in command arguments: no child features,
`multi_agent` only, and `multi_agent_v2` only. Each separate App Server must
report one active `sessionFlags` layer and matching effective values for the
selected multi-agent shape plus every disabled optional feature, disabled web
search, empty MCP configuration, host-skill-discovery disable, and the minimal
shell environment. The V1 profile additionally sets and verifies
`agents.max_depth=2`, which is the bounded depth required for its grandchild
proof. `hooks/list` must be empty and error-free for the requested cwd.
`skills/list` may return only bundled system skills whose canonical paths
remain beneath the isolated `CODEX_HOME/skills/.system`; it rejects every host,
repository, plugin-owned, or out-of-root skill without pinning names or count.
Synthetic poison cases exercise those rejection branches. This proves profile
selection and repairs evidence behind the existing capability-isolation
checks; without a model turn it does not prove child creation or close any
remaining live CP1 gate.

The offline schema gate now also pins the child-thread facts required by the
later recursive live proof. Stable schema must expose subagent source kinds,
parent/session/status metadata, `thread/status/changed`, V1
`collabAgentToolCall` sender/receiver/state fields, and V2
`subAgentActivity` identity/path/lifecycle fields. The direct-parent and
ancestor filters on `thread/list` must remain available only in the
experimental schema. They are an optional discovery aid; the fixed-point
history algorithm does not depend on them. This schema evidence adds no
production child router and does not close the live recursion gate.

The compact Java projection now preserves an optional model
`multiAgentVersion`, V1 sender/receiver/agent-state identity and optional state
message, V2 agent thread, logical path, and lifecycle kind, plus the
parent/session/turn/item history
needed by recursive reconciliation. Optional capability fields remain absent
when omitted, additive fields remain ignorable, and future enum values decode
as `UNKNOWN`. This is codec evidence only: V2 activity is not yet routed into
provider-neutral events, and no child subscription or graph exists.

The [official App Server page](https://learn.chatgpt.com/docs/app-server)
documents version-specific schema generation, experimental opt-in,
`environment/info`, background-terminal APIs, steering, and streamed events.
It does not currently document `environment/add` on that page. Therefore the
per-task environment design is a go/no-go compatibility question, not an
assumption.

Implementation:

- [x] Generate JSON Schema from the exact selected binary, verify that the
  required experimental definitions are present, and preserve the generation
  command/version in the fixture metadata.
- [x] Make the generated compact reference projection authoritative for the
  fields ByteQuay consumes. The normal fixture verifies the committed manifest,
  `--update-bindings` regenerates it, and Java reflection/codec conformance keeps
  production decoding tolerant of additive fields and future enum values. An
  incompatible required shape fails the affected operation and reports the
  detected running version instead of failing a version-text gate.
- [x] Initialize with `capabilities.experimentalApi: true`, wait for the
  response, and send `initialized` before any other request.
- [x] Prove the exact schema/binary exposes and accepts `environment/add` with
  the fields required by the reviewed design.
- [x] Prove commands selected through environments A and B report distinct
  executor-only `HOME` values and canonical working directories rather than
  falling back to the App Server's ambient process.
- [x] Launch the version-matched official task executor on loopback and prove
  its reported endpoint and handshake behavior.
- [x] Register the task environment, call `environment/info`, and verify its
  canonical working directory and shell.
- [x] Start one thread and turn selecting only that environment; run one
  built-in command in a temporary executor directory and observe typed
  command-start, command-completion-with-output, and final-agent-message events.
- [ ] Repeat a bounded filesystem action inside a disposable Git worktree with
  the proposed production sandbox shape.
- [x] Project and codec-test the modern approval methods, top-level fields,
  requiredness, both command kinds, every command/file wire decision shape,
  and the opaque permission payload plus response scope from v0.151.0.
- [x] Reject legacy `applyPatchApproval` and `execCommandApproval` server
  requests with a fail-closed JSON-RPC error.
- [ ] Exercise one representative real command approval request and typed
  `accept` response through the live App Server path. Exhaustive modern wire
  coverage remains an offline schema/codec gate; richer product decision
  matrices begin in CP2 only after ByteQuay defines its finite immutable safety
  ceiling.
- [x] Exercise background-terminal list and clean with an empty result.
- [ ] Exercise a non-empty yielded background terminal and targeted terminate.
- [x] Inspect `model/list` effort choices, text input support, and advertised
  multi-agent protocol version, then run the advertised default effort.
- [x] Exercise `thread/read(includeTurns: true)` with command history.
- [x] Pin the stable V1/V2 child item, child status, source-kind, and parent
  metadata schema plus the experimental-only parent/ancestor list filters.
- [x] Project and codec-test model multi-agent capability, V1/V2 child
  identity, and recursive thread/turn/item history without enabling routing.
- [x] Start separate credential-free no-child, V1-only, and V2-only launch
  profiles and repeat the pre-thread config, managed-policy, rule, skill, and
  hook isolation assertions for each one.
- [x] Add a separate dedicated-key `--live-children` path that discovers the
  model's V1/V2 profile under the no-child baseline, restarts with only that
  feature, sets and verifies the V1 depth-two requirement when applicable, and
  fail-closed checks a bounded root/child/grandchild history,
  markers, item types, approvals, worktree state, and background terminals.
- [ ] Exercise recursive child discovery needed by the later quiescence
  barrier. Select only the child-capable profile advertised by the live model,
  then prove root, child, and grandchild history; profile selection alone
  cannot satisfy this item.
- [x] Call `thread/resume` for the still-loaded root and complete a second turn.
- [ ] Restart App Server and prove disk-backed thread recovery.
- [ ] Run two task environments through one App Server and prove event,
  approval, command, and filesystem separation.
- [x] Prove inherited MCP servers, lifecycle hooks, project config, and the
  caller's ambient user config are absent. The fixture requires exactly one
  isolated App Server user layer under its temporary `CODEX_HOME`, calls
  `config/read` for every temporary task cwd, accepts only isolated bundled
  system skills, and runs MCP/skill/hook/config assertions before the first
  model turn.
- [x] Launch with apps/plugins and related optional features explicitly
  disabled, empty dynamic tools, and empty capability roots. The keyless gate
  proves the feature flags, web-search mode, empty MCP map, host-skill setting,
  and complete shell-environment policy are active session flags for the
  no-child, V1-only, and V2-only profiles. Exactly one multi-agent feature is
  enabled in each child-capable profile.
- [x] Give every compatibility subprocess a separate credential-free
  `CODEX_HOME` and minimal allowlisted process environment. Configure the
  model-requested shell environment to omit `CODEX_HOME` and `CODEX_API_KEY`.
  Never copy a rotating ChatGPT login into the fixture.
- [x] Start App Server without credentials, inspect every returned config
  layer, reject a real rule-bearing isolated user source before any model turn,
  and accept a clean isolated home/workspace only after every returned system,
  user, or project policy-bearing source's `rules/` directory contains no
  `.rules` file.
- [x] Call `configRequirements/read` without credentials and prove that the
  isolated launch has no applicable managed requirements. A missing
  `requirements` field, non-null value, request failure, or undecodable response
  must make the lane unavailable. The response must contain exactly that one
  top-level key; an unknown sibling policy field also fails closed.
- [x] Make a reference-fixture version, generated-bundle hash, or required
  method/field mismatch an actionable fixture failure.

Exit gate:

- [x] The checked-in offline compatibility fixture passes for the exact
  version.
- [ ] The checked-in `--live` fixture passes with a dedicated API key.
- [ ] The checked-in `--live-children` fixture passes with a dedicated API key.
- [x] The reference compatibility fixture rejects a different version and
  rejects changed schema bundle hashes or required fields.
- [x] The executable fixture proves no managed requirements apply before the
  source guard is considered complete.
- [x] No tested command falls back to App Server's ambient/default executor;
  the exact recorded action expands both executor-specific `HOME` markers, an
  unset command-level `CODEX_HOME`, and an unset App Server-only sentinel.
- [ ] The dedicated-key live run proves `CODEX_API_KEY` is absent from both
  executor commands and is supplied only to the App Server process.
- [ ] A yielded/background command remains observable and cleanable.
- [ ] Multi-task routing contains no cross-task event or approval.
- [ ] The checkpoint has a written go/no-go result. A no-go blocks CP2–CP11
  until the canonical design changes.

Primary files:

- `scripts/codex-app-server-compat.mjs`
- `backend/agent-cli/src/test/resources/.../app-server-v0.151.0-bindings.json`
- `backend/agent-cli/.../codex/TestCodexProtocolReferenceBindings.java`
- `backend/agent-cli/.../codex/CodexAppServerClient.java`
- `backend/agent-cli/.../codex/CodexProtocol.java`
- `backend/agent-cli/.../codex/CodexProtocolCodec.java`
- `backend/agent-cli/.../codex/CodexCliDriver.java`
- Codex protocol/driver compatibility fixtures

## [ ] CP2 — Freeze the ByteQuay task and event contract

**Owners:** service/storage owner with runtime and desktop review

**Depends on:** CP1

**Visible UI:** no; desktop fixture work may start after completion

Purpose: establish one safe, versioned, provider-neutral contract for JSONL,
REST/SSE, Electron IPC, and React.

Implementation:

- [ ] Add `workspaceId` to interactive `AgentTask` and SQLite task rows.
- [ ] Add the smallest durable interactive-surface discriminator needed to keep
  workspace task lists from including cherry-pick, CI Autofix, or rebase agent
  tasks. Do not turn it into a generic workflow hierarchy.
- [ ] Change public task creation to accept `workspaceId`, title, lane, and task
  defaults; remove public renderer-selected worktree and repository fields.
- [ ] Resolve the CP0-approved managed-checkout or task-worktree strategy and
  repository inside `app` using existing workspace services.
- [ ] List interactive tasks by `workspaceId` and reject a route/task workspace
  mismatch.
- [ ] Keep legacy/internal workflow tasks readable without silently listing
  unknown-workspace tasks as interactive tasks.
- [ ] Add task defaults and per-turn snapshots for model, effort, permission,
  effective sandbox/runtime policy, and attachment references.
- [ ] Reuse the existing model catalog; do not create a second registry.
- [ ] Expand backend readiness to typed states: ready, authentication required,
  CLI missing, unsupported, degraded, and temporarily unavailable, with
  actionable reason metadata. `checking` remains renderer request state. For
  Codex, `unsupported` requires a detected protocol/capability failure and
  includes detected version text; no reported version text, including unparseable
  output, produces it by itself.
- [ ] Apply that readiness allocation gate only to lanes enabled for the
  current checkpoint. Interactive Claude creation stays backend-disabled until
  CP11 supplies its exact authentication preflight and parity contract.
- [ ] Probe before task resource/lease allocation and before each accepted turn.
- [ ] Define explicit `AgentEventPayload` variants in `agent-core`; stop using a
  serialized `ProviderAgentEvent` as the public/persisted shape.
- [ ] Add `schemaVersion`, globally unique `eventId`, task sequence, task/turn
  IDs, provider root/child/turn/item IDs, timestamps, and typed payload.
- [ ] Preserve provider source/item IDs when supplied. Derive a synthetic key
  only from deterministic provider fields with replay-stability fixtures;
  otherwise only display-only facts may omit source identity and render as a
  standalone/root-level event. An execution item, approval, delivery, or child
  relation without stable routing/reconciliation identity fails closed or
  makes that lane unavailable.
- [ ] Add a durable user-message event shape, including attachment references
  and delivery kind.
- [ ] Remove renderer-supplied attachment paths from `StartTurnRequest`. Until
  CP8 exists, public interactive turns reject attachments; after CP8 they
  accept only opaque task-owned attachment references. Trusted internal
  workflow attachments remain an internal command boundary.
- [ ] Add typed approval request/resolution/expiry variants and the
  product-permitted subset of their provider-offered decisions. An empty subset
  fails closed.
- [ ] Preserve completed items as authoritative replacements for deltas sharing
  the same source/item identity.
- [ ] Keep Codex wire DTOs, the compact generated reference projection, and raw
  Claude blocks inside `agent-cli`.
- [ ] Keep a schema-v1 JSONL reader: absent source fields render as an explicit
  root-level legacy stream rather than invented child identity.
- [ ] Narrow task/turn responses so the renderer never receives a worktree path,
  provider session reference, backend URL, or credential.
- [ ] Check in one valid monotonic frontend fixture containing create, user
  message, partial and final assistant messages, plan, command/output,
  file/tool activity, approval request/resolution, usage, lifecycle, and
  error. Derive separate transport tests that duplicate or omit an envelope.
- [ ] Freeze the envelope/common-source evolution rule: later checkpoints may
  add versioned payload variants and optional fields compatibly, but may not
  reinterpret an existing tag. Reserve the direct-parent/logical-agent-path
  metadata needed for CP9.

Exit gate:

- [ ] TypeScript models the fixture as a discriminated union without `any` or
  provider-payload inspection.
- [ ] No public interactive agent request can submit a worktree, repository, or
  attachment filesystem path.
- [ ] Workspace-scoped list/read behavior is covered by HTTP tests.
- [ ] Logged-out or incompatible providers allocate no task row, runtime
  directory, or worktree ownership.
- [ ] Two turns retain their exact model, effort, permission, effective
  sandbox/runtime policy, and attachment-reference snapshots.
- [ ] Existing logs/rows remain readable under explicit legacy behavior.
- [ ] Existing cherry-pick, CI Autofix, and rebase callers still compile and
  their focused suites pass.

Primary files:

- `backend/agent-spi/...`
- `backend/agent-core/...`
- `backend/persistence-sqlite/.../AgentTaskEntity.java`
- `backend/persistence-sqlite/.../AgentTurnEntity.java`
- `backend/app/.../web/agent/AgentController.java`
- `backend/app/.../web/agent/AgentHttpModels.java`

## [ ] CP3 — Make mutation, replay, and approval state crash-safe

**Owner:** service/storage owner

**Depends on:** CP2

**Visible UI:** no

Purpose: make the event stream an authoritative reconstruction source rather
than a best-effort activity log.

Implementation:

- [ ] Add `AgentUnitOfWork` backed by one SQLite transaction.
- [ ] Add `AgentEventOutboxStore` and its SQLite entity/store.
- [ ] Make `TaskLogStore` find/append idempotently by `eventId`.
- [ ] Commit every state mutation and its bounded unsequenced event together.
- [ ] Drain the committed outbox to JSONL before SSE publication or provider
  callback completion.
- [ ] Drain pending outbox rows before agent endpoints become ready at startup.
- [ ] Add an explicit agent-serving gate: either complete recovery before the
  web layer serves agent data or have every agent task/turn/event/approval read,
  mutation, and SSE endpoint reject until recovery is ready. Provider-status
  diagnostics may remain available. `ApplicationReadyEvent` alone is too late
  because the HTTP server is already listening.
- [ ] Write bounded user-message bytes to a task-owned blob before the SQLite
  transaction, then persist only bounded metadata and its blob reference in
  the outbox before attempting `turn/start` or later `turn/steer` delivery.
- [ ] Add input-delivery states `ACCEPTED`, `SENDING`, `DELIVERED`, `FAILED`,
  and `UNKNOWN`; never automatically resend an unknown delivery.
- [ ] Persist each delivery's ID, start/steer kind, task/turn, idempotency key,
  timestamps, and redacted terminal reason.
- [ ] Persist approval requests before publishing cards and persist
  resolution/expiry before resuming the provider callback.
- [ ] Deny pending approvals durably on timeout, cancellation, terminal turn,
  or provider loss.
- [ ] Add a durable workspace-lease table with unique canonical worktree.
- [ ] If CP0 selected the managed checkout, make workspace sync, cherry-pick,
  CI Autofix, rebase, and every other ByteQuay Git writer acquire/check the same
  durable workspace ownership before mutation. Detect/revalidate external
  changes and fail closed; do not claim to fence unrelated user processes.
- [ ] Add one task-local worktree mutation claim acquired by turn start, later
  queue claims, or completion. Recovery preserves and reconciles the existing
  claim; it does not invent a separate recovery-claim kind.
- [ ] Rebuild the in-memory lease index from SQLite before accepting agent
  traffic.
- [ ] Put unverifiable ownership/delivery in a typed `RECOVERING` state instead
  of releasing the lease or claiming success.
- [ ] Keep provider, Git, JSONL, and SSE calls outside SQLite transactions.

Exit gate:

- [ ] Crash tests around state commit, JSONL flush, and outbox mark yield one
  `eventId` and one sequence with no missing state event.
- [ ] Replay always contains the accepted user input before provider output.
- [ ] Reconnect from the last sequence loses and duplicates no event.
- [ ] A resolved/expired approval never reappears as pending after reload.
- [ ] Two live tasks cannot own the same canonical worktree across restart.
- [ ] Concurrent Send and Complete yield exactly one mutation claim.
- [ ] Startup serves no agent mutation endpoint before outbox/lease recovery
  finishes, and no task/event read or SSE stream exposes partially recovered
  state.
- [ ] Backend verification plus shared workflow regression suites pass.

Primary files:

- `backend/agent-core/.../DefaultAgentEventService.java`
- `backend/agent-core/.../DefaultAgentTaskService.java`
- `backend/agent-core/.../DefaultAgentTurnService.java`
- `backend/agent-core/.../DefaultAgentApprovalService.java`
- `backend/agent-core/.../FileTaskLogStore.java`
- `backend/persistence-sqlite/...`
- `backend/app/.../AgentMaintenanceJob.java`

## [ ] CP4 — Integrate task isolation and quiescent recovery

**Owner:** runtime owner with service/storage owner

**Depends on:** CP1 and CP3

**Visible UI:** no

Purpose: turn the CP1 compatibility proof into the production Codex execution
path and prevent one task or old process from mutating another task's worktree.

Implementation:

- [ ] Implement the gated launch sequence from the canonical service design:
  fsynced `PREPARED` marker, committed launch intent, `ATTEMPTING`, gated
  launcher identity, `RELEASE_PENDING`, gate release, `RUNNING`, terminal
  receipt, then durable acknowledgement.
- [ ] Record executable identity and OS start token and verify directly owned
  process-tree termination during recovery; a root PID exit is insufficient.
- [ ] Persist launch phase and opaque owner for the shared App Server, each task
  executor, and each Claude turn.
- [ ] Add `TaskExecServerManager` using only the contract proven in CP1.
- [ ] Launch one version-matched executor/environment per active Codex task.
- [ ] Persist one unique environment ID per task, reuse it only for that task,
  and never assign it to another task. The v0.151.0 reference protocol has no
  unregister operation, so correctness must not depend on one.
- [ ] Re-register and verify a replacement environment before thread resume.
- [ ] Select exactly that task's environment for thread and turn start.
- [ ] Apply canonical worktree roots, workspace-write sandbox, model/effort
  snapshot, and the reviewed Auto approval behavior.
- [ ] Adopt the CP1-proven isolated subprocess environment, pre-turn
  child-capable launch profile plus the skill-origin, hook, config-layer
  rule-source, and managed-requirements guards in the production runtime path.
  Do not claim a per-thread override disables exec-policy rules; v0.151 exposes
  none.
- [ ] Verify inherited capabilities remain disabled after start and resume.
  Force skill discovery and repeat the origin/error guard immediately before
  every `turn/start`, including the first turn after resume/restart.
- [ ] Route root and child events/approvals by provider thread and turn identity
  without cross-task fallback.
- [ ] Run one combined fixed-point reconciliation: repeatedly read root and
  discovered descendants with `thread/read(includeTurns: true)`, reconcile all
  live/history command/tool/file/hook/terminal items, and page background
  terminals for every known child. Stop only after a final whole pass discovers
  no new child/item, every item is terminal, and every background list is
  empty.
- [ ] Treat root answer/turn completion as necessary but not sufficient for
  quiescence.
- [ ] Retain the mutation claim and lease while a child, item, background
  terminal, unknown delivery, or owned process is active or unverifiable.
- [ ] Add a guarded fail-recovery operation that never edits or cleans the
  worktree.
- [ ] Before enabling a model, prove full recursive child discovery/routing and
  quiescence. Changing ByteQuay capability metadata does not prevent invisible
  descendants; otherwise mark the model/lane unavailable. CP9 adds inspection,
  not this safety behavior.
- [ ] After a successful stable terminal reconciliation, atomically finalize
  the turn, release its `TURN` claim, and transition the task from `ACTIVE` to
  `READY`.

Exit gate:

- [ ] Two concurrent tasks share one App Server and distinct task executors with
  no event, approval, command, or filesystem cross-talk.
- [ ] Writes outside selected workspace roots are rejected by the compatibility
  sandbox fixture.
- [ ] A second turn resumes the same root only after its task environment is
  verified.
- [ ] Root completion while an item/child/background terminal runs keeps Send
  and Complete blocked.
- [ ] A missed live child, a child-created grandchild, and live/history overlap
  converge through recursive history closure without duplicate routing.
- [ ] Cancellation lists/cleans background terminals and reaches the same
  quiescence barrier.
- [ ] Backend restart either proves the old owned process tree stopped or leaves
  the task visibly recovering with its lease held.
- [ ] Missing/corrupt process receipts fail closed.
- [ ] If a compatibility fixture can leave a writer outside both ByteQuay's
  directly owned process proof and App Server item/background-terminal
  registries, reject the Codex lane for release rather than merely leaving one
  task recovering.

Primary files:

- `backend/agent-cli/.../process/...`
- `backend/agent-cli/.../codex/...`
- new task-executor manager and fixtures in `agent-cli`
- recovery state and launch stores in `agent-core`/`persistence-sqlite`

## [ ] CP5 — Add the authenticated Electron task bridge

**Owner:** desktop owner

**Depends on:** CP2; may run in parallel with CP3 and CP4

**Visible UI:** no standalone product UI

Purpose: replace the reference shell's HTTP/SSE client without exposing
localhost secrets or provider wire data to React.

Implementation:

- [ ] Define narrowed task, turn, event, readiness, approval, and completion
  DTOs plus bridge methods in `frontend/src/types.ts`.
- [ ] Add Electron-main operations for list/create/get task, read events,
  start/get/cancel turn, resolve approval, and complete task.
- [ ] Do not scaffold defaults, attachments, steering, or queue methods before
  their checkpoints.
- [ ] Attach `X-ByteQuay-Session` only in Electron main.
- [ ] Implement one authenticated fetch-based SSE reader with standard
  `AbortController`; do not add a dependency or use renderer `EventSource`.
- [ ] Parse events split across network chunks and reconnect with the last
  delivered task sequence.
- [ ] Report `connecting`, `open`, `reconnecting`, and terminal `failed`
  connection states.
- [ ] Include subscription ID and task ID in pushed IPC so late events from a
  previous screen are ignored.
- [ ] Abort on explicit unsubscribe, replacement subscription, renderer/window
  destruction, or backend shutdown.
- [ ] Make authentication, not-found, workspace mismatch, and incompatible
  schema terminal/actionable instead of reconnecting forever.
- [ ] Expose one typed preload subscribe operation that returns unsubscribe.

Exit gate:

- [ ] Tests cover chunk splitting, replay/live overlap, duplicate sequence,
  reconnect cursor, explicit unsubscribe, late subscription events, and
  terminal HTTP failure.
- [ ] A test renderer receives ordered fixtures without knowing the backend URL
  or session token.
- [ ] No raw Codex/Claude payload, agent worktree/attachment path, or credential
  crosses the agent bridge. Existing non-agent workspace DTOs are outside this
  checkpoint.
- [ ] Existing frontend lint and type checks pass.

Primary files:

- `frontend/src/types.ts`
- `frontend/src/main.ts`
- `frontend/src/preload.ts`
- a small `frontend/src/agents/sse.ts` only if extraction is needed for testing

## [ ] CP6 — Build the fixture-driven text-task developer preview

**Owner:** desktop owner

**Depends on:** CP2 and CP5

**Visible UI:** yes, development-only

Purpose: produce the first reviewable task-screen shape from the frozen CP2
fixture while CP3/CP4 finish. This checkpoint proves renderer behavior only;
it does not claim a usable live agent task.

Implementation:

- [ ] Add `#/workspaces/{workspaceId}/tasks/agent/{taskId}` to the existing hash
  route model; route state, not component memory, owns the selected task.
- [ ] Keep cherry-pick tasks unchanged and add a separate **Agent tasks**
  section to the existing Tasks screen.
- [ ] Add a preview-only New agent task action using the route workspace, the
  fixed Codex lane, and backend/workspace defaults. Do not add the provider,
  model, effort, or permission picker before CP7.
- [ ] Add one `agents/model.ts` reducer for task/turn state, ordered events,
  last sequence, connection state, projection, and composer state.
- [ ] Drive the screen and reducer through a typed mock bridge using the valid
  monotonic CP2 fixture plus separate duplicate/gap/reconnect cases.
- [ ] Simulate paged replay followed by subscription after the highest applied
  sequence.
- [ ] Ignore simulated duplicate/older sequence and fill a simulated gap before
  projecting newer events.
- [ ] Render durable user messages, partial/final assistant messages,
  lifecycle, and errors.
- [ ] Render plan, command/output, file, tool, approval, and usage fixtures as
  simple typed rows so the preview loses no information the shell prints.
- [ ] Render only the product-permitted subset of provider-offered approval
  decisions; do not reduce the contract to a generic boolean, and fail closed
  when that subset is empty.
- [ ] Anchor a text composer with Send, Cancel, connection state, and idle-only
  Complete.
- [ ] Have preview controls emit the exact typed bridge commands and one stable
  idempotency key, while transcript state still waits for the fixture's
  durable user-message event instead of inventing history.
- [ ] Navigating away unsubscribes the mock renderer subscription only.
  Reopening replays the simulated still-running task.
- [ ] Keep the preview behind the CP0 development gate and clearly label it as
  fixture data; it must never connect to a real provider or claim durability.

Exit gate:

- [ ] Route tests cover create/open/deep-link/reload/back/forward.
- [ ] Reducer tests cover replay/live overlap, duplicate/gap handling,
  authoritative final replacement, approval resolution, cancellation, and
  terminal errors.
- [ ] Static UI tests cover task list, task detail, composer, reconnecting, and
  error states.
- [ ] A mock-bridge journey covers create/open, Send command, streamed fixture,
  simulated reconnect/reload, Cancel command, and Complete command.
- [ ] No live-backend or provider acceptance is claimed; CP7 owns that gate.
- [ ] No new state/UI/CSS framework or generic task model is introduced.

Initial frontend shape:

```text
frontend/src/agents/
├── AgentTasksView.tsx
├── AgentTaskScreen.tsx
├── model.ts
└── model.test.ts
frontend/src/css/agents.css
```

Extract composer, approval, activity, or subagent components only when their
implemented behavior makes `AgentTaskScreen` materially harder to read.

## [ ] CP7 — Converge on the first usable Codex task

**Owners:** runtime, service/storage, and desktop owners

**Depends on:** CP3, CP4, CP5, and CP6

**Visible UI:** yes; first real-runtime milestone

Purpose: join the independently reviewed runtime and desktop work into one
complete vertical slice.

Implementation:

- [ ] Gate task creation on fresh typed provider readiness before task/resource/
  lease allocation.
- [ ] Promote the existing cherry-pick-specific provider-status IPC into one
  shared typed agent-provider readiness operation; update its existing consumer
  instead of maintaining two status paths.
- [ ] Add typed Electron/preload operations to refresh provider readiness,
  update task defaults, read effective turn settings, cancel active execution,
  call `POST /agent/tasks/{taskId}/fail-recovery`, and invoke the confirmed
  shared-server stop operation.
- [ ] Add Workspace Settings provider rows and actionable install, login,
  report-issue, and retry states.
- [ ] Make this milestone Codex-only. Show Claude as unavailable or
  preview-only until CP11 rather than accepting a task the product cannot yet
  render and recover at parity.
- [ ] Add Codex model selection at task creation; keep the Codex lane immutable.
- [ ] Add supported model, effort, and permission selection for the next turn.
- [ ] Persist defaults and display the effective settings actually snapshotted
  for each turn.
- [ ] Map **Auto** through the typed backend policy; React never sends provider
  wire strings.
- [ ] Render complete typed approval cards with source, kind, reason,
  command/directory or scope, and only offered choices.
- [ ] Render live command, output, file, tool, plan, usage, lifecycle, and error
  rows from canonical events.
- [ ] Keep mutation controls disabled during recovery or unproven quiescence.
- [ ] Render a **Recovering agent execution** card with bounded diagnostics.
  **Stop execution** invokes normal turn cancellation/reconciliation while work
  may still be active; it does not release the claim or lease.
- [ ] After every provider-visible item/background terminal is terminal and all
  directly owned processes are verified stopped, expose **Mark task failed**.
  It calls `POST /agent/tasks/{taskId}/fail-recovery`, marks the whole task
  `FAILED`, releases claim/lease, retains history, and leaves the worktree
  untouched. The backend rejects it before that barrier.
- [ ] Add supervised `GET /agent/runtime/codex/stop-impact` and
  `POST /agent/runtime/codex/stop` operations plus typed Electron/preload
  methods. The first returns the runtime generation and complete affected-task
  list; the second requires both so a newly active task cannot be omitted by a
  stale confirmation.
- [ ] The confirmed **Stop shared Codex server** action marks every affected
  live Codex turn `INTERRUPTED`, durably denies pending approvals, pauses queued
  work, retains claims/leases, and blocks replacement until the old process
  tree is verified stopped. Resumption is an explicit later user action; the
  renderer never kills a process directly.
- [ ] After the recovery barrier, re-enable the composer. A **Retry** shortcut
  is the existing start-turn operation with a fresh idempotency key and an
  explicit confirmation to resend the prior message; it is never offered for
  `UNKNOWN` delivery and never runs automatically.
- [ ] Label the composer, model/effort/permission controls, approval actions,
  and activity disclosure; support keyboard operation, restore focus after an
  approval action, and announce only bounded state changes rather than every
  streamed token.
- [ ] Complete only while idle/quiescent; close the provider session, stop the
  task executor, retain history, and release the durable workspace lease.
- [ ] Adapt the shell reference client to the workspace-safe contract through a
  temporary test workspace; do not preserve a raw-path public API for it.
- [ ] Replace the CP6 mock-bridge implementation with the real bridge for the
  product route. Packaged builds cannot create/open fixture tasks or import the
  fixture runtime; fixtures remain test inputs only.

End-to-end acceptance:

- [ ] Create a Codex task from one watched workspace.
- [ ] Send “Summarize this repository” and see the durable user message, live
  typed activity, and final answer.
- [ ] Reload mid-turn; no item is lost or duplicated and the run continues.
- [ ] Restart the backend mid-turn; the restored turn is visibly
  `INTERRUPTED`/`RECOVERING` or reconciled terminal, never implicitly resumed or
  resent, and ownership remains held until the barrier passes.
- [ ] Resolve an approval, reload, and verify it stays resolved.
- [ ] Send a second message and verify the same root thread is resumed.
- [ ] Cancel a running turn and reach the quiescence/recovery outcome.
- [ ] Complete the idle task and prove its lease is released.
- [ ] Run two tasks concurrently without event/approval/filesystem cross-talk.
- [ ] The packaged desktop, not only unit fixtures, passes this journey.

**Checkpoint result:** this is the first usable Codex task surface. Do not call
CP2, CP5, or CP6 alone “agent UI complete.”

## [ ] CP8 — Add rich transcript projection and safe images

**Owners:** service/runtime owner for facts and attachment boundary; desktop
owner for projection

**Depends on:** CP7

**Visible UI:** yes

Implementation:

- [ ] Distinguish structured checklist updates from free-form Plan-mode text.
- [ ] Preserve provider-supported readable reasoning summaries and discard
  hidden reasoning.
- [ ] Normalize bounded/redacted command output, cwd, file changes, built-in
  tools, web search, image view, compaction, retry, duration, and usage facts.
- [ ] Merge activity by provider thread/turn/item identity; completed items
  authoritatively replace deltas.
- [ ] Group contiguous activity into one expandable block and derive the folded
  sentence from typed facts only.
- [ ] Keep an activity group visibly active and expanded while any member is
  running. After all members become terminal, fold to the derived summary
  unless the user explicitly chose an expanded/collapsed state; live updates
  never override that choice.
- [ ] Show plan state, turn elapsed time, provider-supplied item duration,
  usage, unavailable cost, and existing-style status icons.
- [ ] Add task-owned attachment storage using opaque references.
- [ ] Add typed Electron/preload operations for native image selection, byte
  upload, owned-reference preview reads, and attachment removal. Electron main
  owns file paths; the renderer sees only metadata and opaque references.
- [ ] Verify media type, byte size, pixel dimensions, ownership, and model input
  modality.
- [ ] Convert a supported task-owned image to `localImage`; reject URL images,
  arbitrary paths, cross-task references, malformed images, and oversize data.
- [ ] Load replayed user-message attachment references through the typed bridge
  into transcript previews; show retained, expired, and unavailable states.
- [ ] Revoke every renderer object URL on replacement, removal, unmount, and
  failed preview load.
- [ ] Add composer preview/remove and retained/expired attachment states.

Exit gate:

- [ ] Folded summaries are reproducible from the canonical event fixture and
  are never persisted as state.
- [ ] Plan replacement, live elapsed stop, missing-cost display, and
  delta/final replacement have reducer tests.
- [ ] A supported image survives send/reload and reaches Codex from a
  task-owned path.
- [ ] An unsupported model cannot attach an image.
- [ ] Arbitrary local paths and unbounded output are rejected before
  persistence or provider input.

## [ ] CP9 — Preserve and inspect subagents

**Owners:** service/runtime owner for durable child projection; desktop owner
for inspection UI

**Depends on:** CP8

**Visible UI:** yes

Implementation:

- [ ] Expose the CP4-proven root/child identity, routing, recursive discovery,
  and quiescence facts as typed canonical subagent lifecycle events for
  supported V1 spawn events and V2 agent paths.
- [ ] Preserve the CP4 ordering rule: routing is active before a child discovery
  event is published.
- [ ] Persist explicit backfill lifecycle markers while CP4 reads known child
  history; deduplicate live/history projection by source identity.
- [ ] Project nested grandchildren on the direct-parent/logical-agent-path
  metadata reserved in CP2 while retaining one task sequence.
- [ ] Derive child terminal results from the reconciled CP4 child history; add
  no child transcript table.
- [ ] Persist only a compact terminal child index for post-cleanup summaries.
- [ ] Expose and replay CP4-routed child approvals in the filtered projection;
  an unmatched approval remains a CP4 fail-closed runtime error, not a second
  CP9 router.
- [ ] Render one parent card with **Active** and **Done**, label/purpose, status,
  elapsed time, and result summary.
- [ ] Clicking a child filters the existing event projection and shows a
  breadcrumb; it creates no task route or second SSE connection.
- [ ] Reuse root message, plan, activity, approval, and terminal renderers.
- [ ] Reuse CP4's recursive child/item/background-terminal closure as the
  safety barrier for Send/queue start/Complete; CP9 must not introduce a second
  quiescence algorithm.
- [ ] After cleanup, retain the child summary and show **Activity expired** for
  removed details.

Exit gate:

- [ ] Active and completed children are inspectable from replay while retained.
- [ ] Missed live discovery is recovered from history without duplicate items.
- [ ] A child-created grandchild is discovered and nested correctly.
- [ ] Root completion before child/item completion retains the mutation claim.
- [ ] Two tasks’ child events and approvals never cross.
- [ ] Cleanup preserves summaries and makes transcript expiry explicit.

## [ ] CP10 — Add steering and the durable next-turn queue

**Owners:** service/storage and runtime owners for delivery; desktop owner for
controls

**Depends on:** CP3, CP7, and CP9

**Visible UI:** yes

Implementation:

- [ ] Add capability-gated steering against the exact active root thread and
  turn.
- [ ] Persist steer user input and delivery state before one provider attempt.
- [ ] Add `POST /agent/turns/{turnId}/steer` plus its typed Electron/preload
  operation; the renderer never calls App Server steering directly.
- [ ] Add task-owned FIFO queue records with text/blob, attachments, effective
  settings, state, idempotency key, and prior/current turn references.
- [ ] Add create/list/remove/resume queue endpoints and typed IPC operations.
- [ ] Atomically claim FIFO head, new turn, input delivery, user event, and
  worktree mutation claim.
- [ ] Auto-start only after successful quiescent completion.
- [ ] Pause after failure, cancellation, interruption, authentication loss, or
  recovery.
- [ ] Make `UNKNOWN`/`UNCERTAIN` delivery visible and never automatically retry
  it.
- [ ] Serialize enqueue against Complete with expected task version and the
  shared mutation claim.
- [ ] Render **Steer**, **Run next**, queued/paused/starting/uncertain states,
  remove, and explicit resume. Do not add reorder, priority, or schedule.
- [ ] Show queued text preview, attachments, and effective settings in the
  queue, not as a transcript user message. The atomic first claim emits exactly
  one durable user-message event and moves that item into the transcript.

Exit gate:

- [ ] A stale/different active-turn steer is rejected.
- [ ] An untouched queued input survives restart and starts once in FIFO order.
- [ ] Non-success pauses later work.
- [ ] Unknown delivery is never silently resent.
- [ ] Double-click/idempotency and enqueue-versus-Complete races cannot create
  duplicate turns or live work on a completed task.
- [ ] Completing or discarding uncertain input records abandonment rather than
  claiming it was never delivered.

## [ ] CP11 — Finish Claude parity and pass the release gate

**Owners:** all roles

**Depends on:** CP8, CP9, and CP10

**Visible UI:** yes; release candidate

Implementation:

- [ ] Implement Claude's exact typed authentication/readiness probe, then
  enable Claude in New task only when a fresh probe is ready. Missing,
  unsupported, or authentication-required states stay disabled with their
  actionable Workspace Settings control.
- [ ] Map Claude's supported partial/final messages, tools, hooks, approvals,
  usage, errors, and parent-linked activity into the canonical contract.
- [ ] Apply exact per-turn model/effort snapshots on new and resumed Claude
  invocations.
- [ ] Preserve strict MCP configuration, empty inherited setting sources, and
  generated task sandbox settings.
- [ ] Make authentication-required a typed preflight result that blocks before
  task/turn/resource allocation.
- [ ] Preserve task resources and retry budget when authentication expires
  during a turn; enable explicit retry after login refresh.
- [ ] Wire **Refresh login status** to the shared typed
  `refreshAgentProviderReadiness` bridge operation. After readiness succeeds,
  **Retry** calls the existing typed `startAgentTurn` operation with a fresh
  idempotency key only after quiescence and explicit resend confirmation; it is
  disabled for `UNKNOWN` delivery and never silently reuses a delivery row.
- [ ] Hide or explain unsupported Claude controls through capabilities; do not
  invent steering, checklist, image, or event fidelity.
- [ ] Render Codex and Claude through the same reducer without provider-name
  branches except capability-specific labels.
- [ ] Re-run keyboard, focus, labeling, contrast, and restrained live-region
  checks across both provider capability shapes.
- [ ] Run full security/redaction, replay/reconnect, approval, isolation,
  recovery, completion, retention, image, subagent, steering, and queue
  acceptance matrices.
- [ ] Reconfirm the exact v0.151.0 reference suite, run the latest installed
  Codex integration journey, and reconfirm the product's recorded
  experimental/same-host decisions for the release candidate.
- [ ] Remove the development-only UI gate only after every release box passes.

Release checks:

- [ ] `cd backend && mvn verify`
- [ ] `cd frontend && npm run lint`
- [ ] `cd frontend && npx tsc --noEmit`
- [ ] `cd frontend && npm test`
- [ ] `cd frontend && npm run package`
- [ ] Packaged Codex create -> two turns -> approval -> reload -> cancel ->
  complete journey passes.
- [ ] Packaged Claude create -> two turns -> approval -> reload -> cancel ->
  complete journey passes for advertised capabilities.
- [ ] Interactive Codex and Claude `agent-e2e.sh` runs remain useful backend
  diagnostics.
- [ ] Logged-out/unsupported providers allocate no task resources.
- [ ] No event/blob/IPC payload contains provider credentials, backend session
  secrets, raw hidden reasoning, arbitrary paths, or unbounded output.
- [ ] No custom ByteQuay model tool, commit workflow, generic task framework,
  raw log viewer, UI framework, or copied Codex Desktop asset entered scope.

## 7. Review gates

Approve only CP0/CP1 as a compatibility investigation first. After CP1 records
a go result, answer the remaining questions and approve CP2–CP11. A CP1 no-go
returns the design for revision rather than starting service or UI work.

- [ ] Is the CP1 per-task environment design supported by the v0.151.0
  reference protocol and the latest installed CLI, especially
  `environment/add`?
- [x] Use the user-managed Codex CLI, keep startup self-update enabled, and do
  not disable task start because of any reported version text, including
  unparseable output. Include the detected text when an actual protocol operation
  fails.
- [ ] Is the experimental App Server dependency acceptable for the intended
  release audience?
- [ ] Is the unauthenticated loopback task-executor boundary acceptable on the
  same host?
- [ ] Is CP7 the right first usable real-runtime milestone, with images, rich
  folding, subagents, queueing, and Claude parity deliberately following it?
- [ ] Has CP0 explicitly selected managed checkout plus a workspace-wide
  mutation gate, or a separately designed task worktree, before CP2 begins?
- [ ] Is seven-day detailed runtime retention still the desired product policy?

## 8. First implementation action after compatibility-spike approval

Do not begin by writing React components. Begin with CP0 and CP1:

1. preserve latest-user-CLI production behavior and isolate the exact
   v0.151.0 rule to the reference fixture;
2. repair the stale shell baseline;
3. generate the exact experimental schema;
4. prove or reject the per-task environment and quiescence assumptions; and
5. only after the go decision, freeze the CP2 ByteQuay event fixture so runtime
   and desktop work can proceed in parallel.

This ordering avoids rebuilding the existing agent service while also avoiding
a UI whose production runtime contract later proves unavailable.
