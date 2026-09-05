# Agent Service Design

This document specifies local, worktree-scoped development tasks driven by
Codex App Server launched through the Codex CLI or by Claude Code CLI. The
whole-system shape and Maven module boundaries are defined in
[ByteQuay Architecture](architecture.md).
The exact service and method sequence is defined in
[Create-to-close lifecycle](#7-create-to-close-lifecycle).
The shared provisioning and cleanup boundary is defined in
[Managed Worktree Service](worktree-service.md).

## Current implementation (2026-09-05)

This status note records the implemented, ungated, shipped boundary.
It supersedes older wording that described the agent subsystem or its rich task
surface as future work.

### Current situation

- The Codex lane already uses the official App Server transport.
  `CodexCliDriver` starts one task-owned execution
  `codex app-server --listen stdio://` process per active turn, rooted at that
  task's worktree. The only concurrent exceptions are bounded text-only helpers
  for task titles and Better Words; they run in isolated runtime scratch and
  have no model-callable tools, writable task worktree, approvals, or resumable
  history. The next real turn resumes the same thread in a new matching-profile
  process. The driver persists Codex thread and turn references and maps App
  Server JSON-RPC notifications into `ProviderAgentEvent`.
- The compatibility spike selects Codex CLI/App Server `0.151.0` exactly as a
  reference fixture. It does not constrain production. ByteQuay updates a
  user-managed Codex CLI only when its parsed version is below the supported
  minimum; newer or unparseable version text is left alone and never becomes a
  readiness gate. The detected version text is retained for status and
  incompatibility diagnostics. The product accepted the experimental
  dependency for the first task release on 2026-08-31; CP11
  compatibility and release acceptance passed on 2026-09-04.
- The official documentation currently marks the `app-server` command itself,
  not only WebSocket transport, as experimental and unsupported for production
  workloads. The existing stdio integration therefore remains an explicit,
  accepted release risk despite protocol compatibility tests.
- The Claude lane is a separate `stream-json` adapter. Both adapters feed the
  ByteQuay-owned task, turn, approval, metrics, retention, and event services.
- ByteQuay persists normalized user-visible events in the task JSONL log and
  exposes replay through `GET /agent/tasks/{taskId}/events` and SSE through
  `GET /agent/tasks/{taskId}/stream`. The stream commits its response with an
  SSE comment as soon as a client subscribes, so an idle task that has no
  event yet still reads as an open stream rather than a hanging request. Raw
  provider payloads and hidden chain-of-thought are outside that contract.
- One cherry-pick range owns one agent task. Its durable execution stores
  `agentTaskId` and the active `currentTurnId`, and every conflict-resolution
  attempt emits events correlated to those IDs.
- The shared stateless `WorktreeService` now provisions and verifies every
  repository-scoped agent task from a saved workspace and exact commit.
  Interactive tasks, cherry-pick, and CI Autofix own fresh managed worktrees;
  Branch Rebase borrows its caller's `TaskWorktree`, and public start routes no
  longer accept repository or worktree paths.
- The ungated React task surface uses typed Electron operations for create,
  replay/SSE, later turns, approvals, recovery, cancellation, and completion.
  It projects the same durable events for Codex and Claude through normalized
  capabilities rather than a second frontend logging path.
- Cherry-pick remains a separate workflow projection. Its progress presentation
  does not change the interactive task contract or create another agent service.

### Implemented rich-task boundary

The rich task surface is implemented, ungated, and passed the CP11 release
gate. It extends the existing service instead of introducing a second agent
framework; the recorded CP11 rows below are release evidence rather than open
distribution work.

The Codex driver's wire DTOs and compact generated reference projection remain
private to `agent-cli`. The projection is authoritative for the consumed Codex
wire surface and its conformance checks, but neither is ByteQuay's shared domain
model. `agent-core` owns the smaller cross-provider task, turn, approval, queue,
attachment, thread-correlation, and event contracts required by the product.
Claude continues to adapt its native stream to those contracts; it does not
pass through Codex App Server.

The implementation therefore has these fixed boundaries:

1. one task owns at most one worktree-capable App Server process for its active
   turn, and that process serves no other task, so root and child thread IDs need
   no routing between tasks; the process is verified stopped before the turn
   releases its claim, and a later turn resumes the same provider thread in a new
   process; concurrent disposable text-only helpers are permitted only in
   isolated, tool-free runtime scratch directories;
2. every interactive task is durably scoped to a ByteQuay workspace and a
   separate ByteQuay-provisioned worktree, and every accepted input, resolved
   approval, and child relationship is replayable from the task event
   sequence;
3. the task owns defaults while each turn snapshots the model, reasoning
   effort, permission policy, and attachments actually submitted;
4. ByteQuay owns the durable next-turn queue, while native Codex
   `turn/steer` is used only for immediate input to the matching active turn;
5. Electron main owns authenticated HTTP/SSE and the renderer receives only
   typed ByteQuay DTOs; and
6. existing rows and retained JSONL logs stay readable during an additive,
   versioned migration. New required fields are backfilled or represented as
   absent for legacy records rather than reinterpreting old payloads.

A legacy task without a known workspace remains readable through its existing
workflow/task ID but is not silently inserted into an interactive workspace
list. A legacy event without source-thread/item metadata renders in a
root-level legacy stream; ByteQuay does not invent child relationships.

CP2 froze the provider-neutral part of that boundary: interactive task rows
carry a workspace discriminator and defaults, accepted turns retain immutable
requested settings and opaque attachment references, schema-v2 events carry
stable event/source identity, accepted user messages and approval lifecycle are
replay events, and legacy rows/logs remain readable. CP3 then activated the
workspace-safe public request and narrowed response records, the managed
worktree, and the crash-safe outbox. CP4 applied each accepted settings
snapshot to the task-owned App Server runtime. Pending
approval records and their lifecycle events are durable; only the live
provider callback remains process-local. Startup closes all durable pending
approvals, including rows owned by an already-terminal turn. Every state-coupled
transition now commits its outbox row in the same SQLite transaction and drains
it before publication; display-only provider events still append directly.
Accepted turns commit their blob-backed user message through that outbox and
drain it before provider delivery. Direct start does not duplicate the turn in a delivery
table: `startedAt` is persisted immediately before the provider call, and the
provider reference confirms receipt. A failure before that marker is known
unsent; a failure after it without a provider reference is uncertain and
retains the blocking turn and mutation claim, so neither Send nor Complete can
silently race or resend it. Startup derives the same facts from the turn; the
task remains `OPEN`. Codex image inputs, steering, and the durable queue ship
through the authenticated Electron bridge: a steer is its own delivery row and
is never resent, and a queued input is claimed with its turn and the worktree
in one unit of work. CP11 added the typed Claude readiness, event, approval,
capability, and authentication-recovery behavior described below.

Cherry-pick sequencing, Git verification, CI attempt limits, exact-lease
pushes, and user-confirmation gates remain application-owned invariants. The
agent framework supplies execution and observable activity; agent messages do
not decide those workflow outcomes.

## 1. Decision summary

ByteQuay has a new local agent subsystem rather than the deleted task-agent
implementation. Every task is bound to one ByteQuay workspace, one separately
provisioned ByteQuay-owned Git worktree, and one immutable provider lane. The
ungated frontend sends multiple turns to the same task; the selected driver
resumes the provider session.

The backend exposes user inputs plus provider-supported messages, readable
reasoning summaries, plans, tool activity, commands, file changes, approvals,
subagent activity, usage, errors, and lifecycle events. Hidden
chain-of-thought and raw provider JSON are not part of the contract.

For Codex development tasks, App Server owns reasoning and tool selection.
ByteQuay launches one task-owned App Server per active turn, never more than one
for a task, with that task's worktree as its working directory. It verifies the
process stopped at turn completion; a later turn resumes the durable provider
thread in a new matching-profile process. Under `workspace-write` App Server's
default environment is rooted at that directory, so every active turn gets a
separate execution-process and worktree boundary without registering an
environment or reimplementing Codex's
process/filesystem protocol. It is distinct from the experimental process-wide
`--code-mode-host`, which is not a task router and remains unset. Ambient MCP
servers, apps/plugins, dynamic tools, exec-policy allow rules, and lifecycle
hooks must be absent for the first release and are verified before the first
turn. The exact v0.151 App Server cannot disable or inventory `.rules` through
a public option or RPC. ByteQuay therefore uses an isolated `CODEX_HOME`,
calls `config/read(includeLayers: true)` for every task cwd, rejects project
config, and rejects any `.rules` file beside each returned system, user, or
project policy-bearing config source.
Managed command-rule contents are not inspectable through that response, so
the first release also requires `configRequirements/read` to report no
applicable requirements. The v0.151 compatibility gate pins that response
shape and proves the keyless isolated launch returns an explicit null
`requirements` field. A missing field, non-null value, request failure, or
undecodable response makes the lane unavailable. This security-specific RPC
must contain exactly that one top-level key; an unknown sibling policy field
also fails closed. Supporting a broader shape later requires new exact
executable evidence.

Phase one observes App Server's native command, file, web, and other built-in
tool execution through the task-owned process and worktree. The Codex lane
registers no ByteQuay business MCP or dynamic tools and does not use
`--code-mode-host`; product tools and workflows, including commit integration,
require a separate later design. Claude's task-scoped permission callback is
only an approval transport adapter, not a product tool.

The [CodeGraph Service](codegraph-service.md) is the one narrow later
exception to this baseline: behind its own isolation gate, ByteQuay exposes
exactly one optional, fail-open structural-exploration tool,
`codegraph_explore`. It performs no product action and does not create a
general tool registry. Every other business MCP, dynamic tool, and workflow
action remains outside this contract.

SQLite stores durable task/turn lifecycle facts, provider session references,
per-turn metrics, task totals, runtime-retention state, and the final summary. Detailed active-task
history is an append-only JSONL file with optional blobs. Terminal task logs
remain available for seven days; cleanup deletes them only after the retention
deadline.

The first rich-task release is single-user, localhost-only, and permits one
active turn per task. A small ByteQuay-owned FIFO may hold later inputs, but it
never creates concurrent turns. The shared worktree service creates every task
worktree from an exact commit and verifies it clean before the task becomes
ready. Cost remains unavailable when the provider does not report a monetary
amount.

## 2. Domain model and invariants

### 2.1 Main values

| Type | Meaning |
| --- | --- |
| `TaskId` | ByteQuay-owned stable task identifier |
| `TurnId` | One user request and its provider execution |
| `EventId` | Globally unique ByteQuay-owned idempotency key for one canonical event |
| `workspaceId` | Existing ByteQuay workspace identifier that scopes task navigation and authorization |
| `ApprovalId` | One pending permission decision |
| `QueuedInputId` | One durable user request waiting to become a turn |
| `InputDeliveryId` | Durable attempt to deliver steer input that cannot reuse a turn row |
| `TaskWorktree` | Immutable ByteQuay-owned repository, path, branch, and start-commit facts for one verified task worktree |
| `AttachmentRef` | Opaque reference to a bounded, task-owned input blob |
| `AgentLaneId` | Stable driver ID such as `codex` or `claude` |
| `AgentSessionRef` | Opaque provider session/thread reference plus contract version |
| `ProviderThreadRef` | Opaque lane-scoped root or child thread reference and optional direct parent |
| `ProviderItemRef` | Opaque provider item reference used to merge deltas with its authoritative completion |
| `AgentTurnSettings` | Immutable requested model, effort, service tier, permission, sandbox, runtime, and attachment snapshot; CP4 makes it effective at provider start |
| `AgentInputDelivery` | Durable steer send attempt and known or uncertain outcome; direct start uses `AgentTurn` |
| `QueuedAgentInput` | FIFO input snapshot waiting to become one turn |
| `ApprovalRequest` / `ApprovalResponse` | Matching tagged interaction variants and product-permitted available decisions |
| `WorktreeMutationClaim` | Durable task-local claim owned by one turn or completion operation |
| `AgentTurnResult` | Provider/process terminal lifecycle, operational diagnostics, and a bounded optional final response for direct text-only consumers |
| `AgentTask` | Durable workspace, lifecycle state, lane, worktree, defaults, timestamps, totals, summary, and optional successful worktree-removal time |
| `AgentTurn` | Durable per-turn state, provider references, effective settings, timing, and metrics |
| `TaskResources` | Validated runtime paths owned by one task |

### 2.2 State

```text
Task:
  OPEN -> COMPLETED
  OPEN -> FAILED

Turn: ACCEPTED -> RUNNING -> COMPLETED
      |             |-----> FAILED
      |             |-----> CANCELLED
      |             +-----> INTERRUPTED
      +-------------------> FAILED | CANCELLED | INTERRUPTED

Runtime cleanup: RETAINED -> DELETE_DUE -> DELETED
                                 |
                                 +--------> RETRY_PENDING -> DELETED

Queued input: QUEUED -> STARTING -> STARTED
              PAUSED -> STARTING
              QUEUED | PAUSED -> REMOVED
              STARTING -> PAUSED | UNCERTAIN
              UNCERTAIN -> ABANDONED

Worktree mutation claim:
  NONE -> TURN -> NONE
  NONE -> COMPLETION -> RELEASED

Input delivery: ACCEPTED -> SENDING -> DELIVERED
                |              |-> FAILED
                |              +-> UNKNOWN
                +----------------> FAILED

```

`OPEN` is the only nonterminal task lifecycle state. Running, idle, closing,
and recovery required are derived projections, not durable task states. An
`OPEN` task is idle and sendable only when it has no nonterminal turn, no
worktree-mutation claim, and provider quiescence has been proved. A live turn
projects running, a `COMPLETION` claim projects closing, and an unsettled turn
or retained `TURN` claim projects recovery required. No projection transition
changes `TaskState`.

A provider terminal, failed, cancelled, or interrupted status alone does not
prove that its tools stopped. ByteQuay retains the blocking turn or mutation
claim until recursive item/history/background-terminal reconciliation and
owned-process verification prove quiescence. Only then may it release the
claim and project the still-`OPEN` task as idle. The guarded mark-failed action
is the only recovery-required path to `FAILED`; there is no recovery state
machine, timer, or automatic retry loop.

`cleanup action required` is a task projection used when a terminal task still
has a dirty worktree. It is not a worktree lifecycle state. The shared service
simply rejects cleanup until the worktree is clean and has no open Git
operation.

The implementation must enforce these invariants:

- One task has exactly one workspace, one lane, and one separately provisioned
  canonical worktree. The task is persisted as `OPEN` only after the generated
  branch, path, repository, and exact starting commit are verified durably.
- At most one nonterminal turn is `ACCEPTED` or `RUNNING` for a task.
- Each accepted turn snapshots its effective model, reasoning effort,
  permission policy, and attachment references; later default changes do not
  rewrite it.
- The user-message event, including attachment references, is durable before
  the provider turn starts. A steer input follows the same persist-before-send
  rule and remains part of the active turn.
- Every provider event retains its root or child thread reference, optional
  direct parent, provider turn reference, and provider item reference when the
  source supplies them.
- An approval request and its resolution are both appended before the provider
  is resumed, so replay cannot resurrect a resolved card.
- The durable queue is FIFO. Only one process may claim its head, only a
  successful turn may auto-start the next item, and failure, cancellation,
  interruption, authentication loss, or backend recovery pauses the queue.
- `STARTED`, `REMOVED`, and `ABANDONED` queue rows are historical and do not
  block task completion. Completion rejects only live `QUEUED`, `PAUSED`, or
  `STARTING` rows; it records any `UNCERTAIN` row as `ABANDONED` because the
  explicit Complete action declines to resend it.
- Provider delivery is never assumed from a timeout or lost connection. A
  direct turn with `startedAt` but no provider reference, an `UNKNOWN` steer,
  and an `UNCERTAIN` queue claim are not retried automatically because App
  Server supplies no input idempotency key.
- A canonical worktree and generated branch belong to exactly one task. The
  task stores the immutable `TaskWorktree` returned by provisioning; there is
  no separate allocation state or startup worktree reconciliation.
- Within that worktree, one durable worktree-mutation claim serializes turn
  acceptance/queue claims and completion.
- A turn's claim covers its root run and every discovered descendant. Root
  `turn/completed` does not release it or start queued work. The driver first
  reaches a stable relationship/item/background fixed point, then closes the
  per-turn App Server and requires its complete owned process tree to be
  `VERIFIED_STOPPED`. Item history alone cannot prove descendant termination.
- Provider execution uses the selected App Server sandbox and approval policy
  inside the generated task worktree. Provider control-plane traffic is a separate
  trust boundary: ByteQuay selects the provider configuration, but the first
  release does not claim an OS-enforced endpoint allowlist for the App Server
  or Claude control process.
- The first Codex release exposes no inherited MCP server, connected app,
  plugin, dynamic tool, exec-policy allow rule, or lifecycle hook. A capability
  that cannot be disabled makes the lane unavailable before a turn is accepted.
  Exec-policy readiness is source-based: every task cwd is checked, each
  returned system, user, or project policy-bearing config source is scanned for
  `.rules`, any project layer is rejected, and an unknown layer shape fails
  closed. `packagedDefaults` and `legacyManagedConfigTomlFromFile` layers are
  not exec-policy sources. This is not described as a per-thread rule override.
  Separately, `configRequirements/read` must prove there are no applicable
  managed requirements; non-null or uninspectable requirements fail closed.
- Definite terminal operations release their matching claim in the same unit
  of work as their terminal state. An unknown provider delivery, orphaned
  process, or active descendant retains the blocking turn or claim and projects
  recovery required; Send, queue start, and Complete remain blocked. A failed
  completion releases its `COMPLETION` claim and leaves the task `OPEN` when
  retry is safe or records `FAILED`; it never leaves an ownerless claim.
- Event sequence numbers are monotonic within a task.
- An event is appended before it is published to SSE.
- Every SQLite state transition with a matching event commits that event's
  outbox row in the same transaction. Draining the outbox appends JSONL
  idempotently by `EventId` before publication or provider callback, closing
  the SQLite/file crash gap.
- A provider session reference is stored before the first resumable turn is
  reported as accepted.
- A task worktree must be clean when provisioning completes. Normal completion
  also requires it to be clean so cleanup can remove the directory while
  preserving the generated result branch. The shared service never force-removes
  dirty work or deletes the branch.
- Runtime logs are retained until seven days after the task becomes terminal.
- Cleanup failure leaves the worktree in place, is reported to the caller, and
  never changes the terminal task state. Successful physical removal records
  `worktreeRemovedAt`; this is a completed-operation fact, not another
  lifecycle state. A failed attempt advances the task's existing `updatedAt`
  operation timestamp so policy monitors can back off without adding retry
  state. Both workflow worktree cleanup and retention cleanup first drain every
  committed task outbox event; a drain failure preserves all physical task
  resources. Generic retention excludes managed `CI_AUTOFIX` and `CHERRY_PICK`
  worktrees until their workflow monitor records successful removal, preserving
  the workflow `FINISHED` barrier.
- Missing cost is `unavailable`, never zero.
- UI-only summaries, icons, expanded state, step counters, and live timer ticks
  are derived by the renderer and are never persisted as execution facts.

A terminal turn does not by itself make its task terminal or prove provider
quiescence. The task remains `OPEN`. It projects idle and becomes sendable only
when its task worktree, runtime files, and provider session reference remain
usable, the root/descendant execution tree is proven quiescent, and the turn's
mutation claim is released. Otherwise it projects recovery required and stays
blocked. This permits an explicit later retry on the same task without
allowing an older process to keep mutating beneath it. Task
`FAILED` is reserved for an unrecoverable task-level problem such as lost task
resources, invalid persisted worktree facts, or an unusable persisted session contract; it is
never inferred from assistant message text.

## 3. Core service interfaces

These interfaces live in `agent-core`. The default implementations may be
package-private classes because `app` depends on the contracts, not on
implementation details.

Public collection signatures use JDK interfaces such as `List` rather than
Guava implementation types. Ordering is part of each list method's contract;
implementations may build immutable collections internally but must not expose
mutable results.

The signatures below use domain-specific unchecked exceptions:
`InvalidAgentRequestException`, `AgentConflictException`,
`AgentUnavailableException`, and `AgentStorageException`. HTTP translation
belongs in `app`.

### 3.1 AgentTaskService

`AgentTaskService` owns task creation and task-level reads. It does not start
turns or perform completion cleanup.

```java
package com.bytequay.agent.core;

import java.util.List;

public interface AgentTaskService
{
    /**
     * Creates a workspace-owned interactive task and its separate managed worktree.
     *
     * <p>The app resolves the workspace and exact commit before constructing
     * this path-free command. Core verifies lane readiness, provisions the
     * worktree, allocates resources, and persists {@code OPEN} with a bounded
     * first-message title whose source is {@code PROVISIONAL}. No provider turn
     * is started by this method.
     *
     * @param command trusted workspace, exact commit, first message, lane, and defaults
     * @return the newly created task in {@code OPEN} state
     */
    AgentTask createInteractive(CreateInteractiveAgentTaskCommand command);

    /** Creates a workflow-owned task whose caller supplies its final title. */
    AgentTask create(CreateAgentTaskCommand command);

    /**
     * Returns the current durable representation of a task.
     *
     * @param taskId task to read
     * @return the stored task
     * @throws AgentTaskNotFoundException when the task does not exist
     */
    AgentTask get(TaskId taskId);

    /**
     * Changes defaults used by later composer sends without mutating active or
     * queued input snapshots.
     *
     * <p>The command carries the expected task version and catalogued
     * model/effort/permission values. Provider lane and worktree remain
     * immutable.
     *
     * @param command task ID, expected version, and new defaults
     * @return updated task
     */
    AgentTask updateDefaults(UpdateAgentTaskDefaultsCommand command);

    /**
     * Lists tasks for the task window in reverse update order.
     *
     * <p>The result contains durable task facts only. Detailed event history is
     * obtained from {@link AgentEventService}.
     *
     * @param workspaceId owning workspace
     * @param limit maximum number of tasks to return
     * @return at most {@code limit} tasks
     * @throws InvalidAgentRequestException when {@code limit} is not positive
     */
    List<AgentTask> list(String workspaceId, int limit);
}
```

### 3.2 AgentTurnService

`AgentTurnService` serializes turns and coordinates a driver session. It is
the only core service allowed to start or cancel provider execution.

```java
package com.bytequay.agent.core;

import java.util.List;

public interface AgentTurnService
{
    /**
     * Accepts one user message and starts a provider turn.
     *
     * <p>The implementation rejects terminal tasks and concurrent turns. A
     * short unit of work reserves the idempotency key and worktree-mutation claim with
     * an {@code ACCEPTED} turn plus the user-message event. Only after its
     * outbox drains may the service open or resume the provider session,
     * capture a usage baseline, persist {@code startedAt}, start the run, and
     * return without waiting for completion.
     *
     * <p>An exception before persistence means no turn was accepted. A
     * definite local/session-open failure before {@code startedAt} records the
     * turn {@code FAILED} without sending. Once {@code startedAt} is durable,
     * a provider reference proves receipt; a timeout or lost connection before
     * that reference is reconciled from known provider history when possible,
     * otherwise the turn becomes {@code INTERRUPTED}. Neither case
     * automatically resends input.
     * The task remains {@code OPEN}. It becomes idle and sendable only after
     * the provider execution and every known child are proven quiescent and
     * the turn's worktree-mutation claim is released; until then the derived
     * status is recovery required.
     *
     * <p>Repeating the same idempotency key returns the previously accepted
     * turn. A different request while a turn is active fails with a conflict.
     *
     * @param command task ID/version, message content, attachments, and idempotency key
     * @return the accepted/running turn, or durable failed/interrupted turn
     * when setup ended after acceptance
     * @throws AgentTaskNotFoundException when the task does not exist
     * @throws AgentConflictException when another turn is active
     * @throws AgentUnavailableException when availability fails before a turn
     * is accepted
     */
    AgentTurn start(StartAgentTurnCommand command);

    /**
     * Sends additional user input to the expected active provider turn.
     *
     * <p>The command contains a ByteQuay idempotency key and the expected
     * provider turn reference. The service persists an input-delivery row and
     * user-message event before one send attempt. This operation does not
     * create another ByteQuay turn and is rejected when the driver does not
     * advertise steering or the active reference no longer matches.
     * Repeating a key returns its durable delivery state. Because App Server
     * has no steer idempotency key, an {@code UNKNOWN} delivery is never
     * retried automatically.
     *
     * @param command active turn, text/attachments, expected provider reference,
     * and idempotency key
     * @return accepted, sending, delivered, failed, or unknown delivery state
     */
    AgentInputDelivery steer(SteerAgentTurnCommand command);

    /**
     * Adds one later turn to the task-owned FIFO queue.
     *
     * <p>The record snapshots text, attachment references, model, effort, and
     * permission settings. One unit of work conditionally bumps the expected
     * task version, inserts the queue row, and inserts its event so enqueue
     * cannot race completion. It may be removed until atomically claimed for
     * start. Enqueue is idempotent and does not call the provider.
     *
     * @param command task/version, queued input, effective settings, and idempotency key
     * @return the durable queued input
     */
    QueuedAgentInput enqueue(QueueAgentInputCommand command);

    /**
     * Lists waiting inputs in FIFO order.
     *
     * @param taskId owning task
     * @return immutable queued inputs ordered by insertion sequence
     */
    List<QueuedAgentInput> queued(TaskId taskId);

    /**
     * Removes a waiting input or abandons an uncertain delivery.
     *
     * <p>{@code QUEUED} and {@code PAUSED} become {@code REMOVED};
     * {@code UNCERTAIN} becomes {@code ABANDONED}. A started/consumed input
     * cannot be removed. Abandoning does not assert that the provider never
     * received the input; it records the user's decision not to resend it.
     *
     * @param queuedInputId input to remove
     * @return the removed or abandoned durable record
     */
    QueuedAgentInput removeQueued(QueuedInputId queuedInputId);

    /**
     * Explicitly starts the paused FIFO head after a non-success terminal turn
     * or backend recovery.
     *
     * <p>Successful completion invokes the same atomic claim/start path
     * internally. Each claim creates exactly one durable turn. A definite
     * pre-send failure may return the input to {@code PAUSED}; its interrupted
     * turn remains visible and a later explicit resume creates a new turn. An
     * {@code UNCERTAIN} item is rejected and must never be resent by this
     * method.
     *
     * @param taskId idle task whose queue should resume
     * @return the new turn created from the queue head
     */
    AgentTurn resumeQueued(TaskId taskId);

    /**
     * Requests cancellation of a running or recovery-pending execution tree.
     *
     * <p>Cancellation is idempotent. The method signals the provider run and
     * every known active descendant, then cleans the matching App Server
     * background terminals. Stopping the supervised provider process is a
     * last resort because it interrupts every thread in this task. An already
     * {@code INTERRUPTED} turn may retain its claim until item/history and
     * background-terminal reconciliation succeeds.
     *
     * @param turnId turn to cancel
     * @return the latest turn state after the cancellation request is recorded
     * @throws AgentTurnNotFoundException when the turn does not exist
     */
    AgentTurn cancel(TurnId turnId);

    /**
     * Returns the latest durable state and metrics for one turn.
     *
     * @param turnId turn to read
     * @return the stored turn
     * @throws AgentTurnNotFoundException when the turn does not exist
     */
    AgentTurn get(TurnId turnId);
}
```

### 3.3 WorktreeService

`WorktreeService` is the one synchronous filesystem boundary used by
interactive tasks, cherry-pick, and CI Autofix. It creates an initially clean
generated branch/worktree under the selected ByteQuay workspace, returns its
immutable ownership facts, revalidates those facts on inspection and cleanup,
and owns no workflow state. Branch Rebase borrows its caller's worktree.

The exact contract and caller-specific usage are defined in
[Managed Worktree Service](worktree-service.md). Its core surface is:

```java
package com.bytequay.agent.core;

public interface WorktreeService
{
    TaskWorktree provision(ProvisionWorktreeCommand command);

    WorktreeSnapshot inspect(TaskWorktree worktree);

    void cleanup(TaskWorktree worktree);
}
```

The command carries a ByteQuay-generated task ID, trusted workspace ID, the
physical selected-repository/origin identity, and exact starting commit. The
service derives the managed repository, path, and generated branch; there is no
caller-selected path or arbitrary branch. Repeating the exact request is
idempotent when the existing worktree matches exactly. A half-finished or
mismatched orphan is preserved and reported rather than adopted or deleted.

`TaskWorktree` contains only task ID, workspace ID, physical repository,
canonical worktree path, generated branch, and exact starting commit.
`WorktreeSnapshot` contains that worktree, a required exact HEAD, the dirty
flag, and the open-Git-operation flag.

`inspect` and `cleanup` revalidate the supplied immutable worktree facts.
Cleanup requires a clean worktree with no Git operation open, uses non-forced
removal, removes only the worktree, and always retains the generated branch.
The service has no allocation store, lifecycle, cleanup disposition, recovery
API, or automatic startup reconciliation.

### 3.4 AgentResourceService

`AgentResourceService` owns files created by ByteQuay for a task. Provider
account/session files outside this directory remain provider-owned.

```java
package com.bytequay.agent.core;

public interface AgentResourceService
{
    /**
     * Creates the owner-only runtime directory for a task.
     *
     * <p>The directory is created outside the Git worktree with mode
     * {@code 0700}; files created through the returned paths use
     * {@code 0600}. Repeated allocation returns the same validated paths.
     *
     * @param taskId task that owns the resources
     * @return validated paths for events, blobs, and provider scratch data
     * @throws AgentStorageException when the directory cannot be created safely
     */
    TaskResources allocate(TaskId taskId);

    /**
     * Resolves previously allocated resources without creating them.
     *
     * @param taskId task that owns the resources
     * @return validated task resource paths
     * @throws AgentStorageException when resources are missing or escape the runtime root
     */
    TaskResources require(TaskId taskId);

    /**
     * Verifies and stores one user-selected attachment as a task-owned blob.
     *
     * <p>The command contains bytes supplied by the trusted Electron main
     * process, a display name, and a claimed media type. The implementation
     * reads the image header itself (PNG, JPEG, or GIF), refuses bytes that are
     * not the claimed type, bounds the blob by size (10 MiB) and by pixel count
     * (30 million), keeps the facts beside the blob, and returns an opaque
     * reference. It never accepts a renderer-provided filesystem path, and it
     * does not rewrite the image, so metadata inside it is kept as uploaded.
     *
     * @param command owning task and bounded attachment content
     * @return immutable attachment metadata and opaque reference
     */
    StoredAgentAttachment storeAttachment(StoreAgentAttachmentCommand command);

    /**
     * Reads a previously validated attachment owned by the same task.
     *
     * @param taskId owning task
     * @param attachmentId opaque attachment reference
     * @return bounded content and verified metadata
     * @throws AgentAttachmentNotFoundException when the task owns no such attachment
     */
    StoredAgentAttachment readAttachment(TaskId taskId, String attachmentId);

    /**
     * Removes a stored attachment. A message that already references it then
     * shows the reference as unavailable rather than failing to render.
     */
    void deleteAttachment(TaskId taskId, String attachmentId);

    /**
     * Deletes all ByteQuay-owned runtime resources for a task.
     *
     * <p>Deletion is idempotent and must reject paths outside the configured
     * runtime root. For a durable terminal task, the caller is responsible for
     * committing its summary and waiting for its retention deadline before
     * invoking this method. Resources from a failed creation that never
     * inserted a task may be deleted immediately.
     *
     * @param taskId task whose runtime resources should be deleted
     * @throws AgentStorageException when safe deletion cannot be completed
     */
    void delete(TaskId taskId);
}
```

### 3.5 AgentEventService

`AgentEventService` is the sole sequencer and publisher of normalized events.

`AgentEventPayload` is the canonical sealed union in `agent-core`. It contains
both provider-derived payloads (assistant message, reasoning summary, plan,
command/file/tool/web/image/hook activity, usage, subagent, lifecycle, error)
and ByteQuay-originated payloads (user input/delivery, approval resolution, and
queue lifecycle). `UnsequencedAgentEvent` combines that
payload with a stable `EventId` and task/turn/source metadata;
`AgentEventEnvelope` adds schema version, sequence, and receipt time. This
envelope is the only JSONL/SSE wire shape.

`ProviderAgentEvent` is an SPI input, not the persisted union. A small core
normalizer maps it into `AgentEventPayload`; core services create the
ByteQuay-originated variants directly. Stateful provider observations such as
usage, approvals, and terminal lifecycle are handed to their owning core
service for an atomic projection/outbox write; item/message activity without a
SQLite projection is appended directly. Neither path serializes Java class
names or provider DTOs, so the versioned JSON schema stays explicit.

Every event coupled to a SQLite mutation participates in the outbox, whether
the cause is a ByteQuay action or a provider observation. The state mutation
and unsequenced event commit together, then `AgentEventService` assigns a task
sequence and appends JSONL idempotently by `EventId`. It marks the outbox row
delivered only after the log flush and then publishes SSE or completes a
provider callback. Display-only provider events with no SQLite projection may
use `append(...)` directly. Startup drains pending rows first. The outbox is
transport state, not a second retained transcript.

```java
package com.bytequay.agent.core;

import java.util.List;
import java.util.concurrent.Flow;

public interface AgentEventService
{
    /**
     * Persists and publishes one display-only normalized event.
     *
     * <p>The backend deduplicates by event ID, assigns the next task sequence
     * number once, appends the envelope to JSONL, and only then publishes it
     * to subscribers. An event coupled to SQLite state must use the outbox and
     * {@link #drain(EventId)} instead.
     *
     * @param event normalized event without a backend sequence number
     * @return the persisted envelope containing the assigned sequence
     * @throws AgentStorageException when the event cannot be persisted
     */
    AgentEventEnvelope append(UnsequencedAgentEvent event);

    /**
     * Drains one committed state-coupled event to JSONL and live subscribers.
     *
     * <p>The method is idempotent by {@code EventId}. Core services call it
     * only after their SQLite unit of work commits; startup also drains every
     * pending entry before accepting new mutations.
     *
     * @param eventId committed outbox event to drain
     * @return the existing or newly appended envelope
     */
    AgentEventEnvelope drain(EventId eventId);

    /**
     * Reads a bounded page of persisted events after a sequence number.
     *
     * @param taskId task whose history is requested
     * @param afterSequence exclusive lower sequence bound; zero starts at the beginning
     * @param limit maximum number of envelopes to return
     * @return events ordered by ascending sequence
     * @throws InvalidAgentRequestException when the sequence or limit is invalid
     */
    List<AgentEventEnvelope> readAfter(TaskId taskId, long afterSequence, int limit);

    /**
     * Opens a gap-free replay-and-live stream after a sequence number.
     *
     * <p>The publisher first emits persisted events after the supplied
     * sequence, then live events. It honors subscriber backpressure and closes
     * when the task becomes terminal or the subscriber cancels.
     *
     * @param taskId task to observe
     * @param afterSequence last sequence already received by the caller
     * @return a publisher suitable for the application's SSE adapter
     * @throws AgentTaskNotFoundException when the task does not exist
     */
    Flow.Publisher<AgentEventEnvelope> subscribe(TaskId taskId, long afterSequence);
}
```

### 3.6 AgentApprovalService

`AgentApprovalService` bridges provider permission requests and explicit
frontend decisions.

CP1 projected and codec-tested the exact v0.151.0 modern approval methods,
top-level fields, requiredness, command/file decision variants, and opaque
permission payload/scope, while legacy `applyPatchApproval` and
`execCommandApproval` requests failed closed. CP2 then froze the finite shipped
decision matrix and immutable safety ceiling. The current provider-neutral
request carries an inspectable command, file-change, tool, or other kind; its
source; requested permissions; and only the applicable subset of
`ALLOW_ONCE`, `ALLOW_FOR_TASK`, and `DENY`. A task-wide allow is offered only
with a non-empty task scope. The product-permitted set is the intersection of
the provider's legal offered decisions and ByteQuay's immutable safety ceiling;
an empty set fails closed instead of producing an actionable approval.
Undisplayable, unrouteable, additional/network, overly broad, scoped-but-
unsupported, unoffered, stale, or post-cancellation grants fail closed.
`acceptWithExecpolicyAmendment` remains reference-codec coverage only: the first
release does not offer or persist it because that would create an allow rule and
violate the zero-inherited/generated-allow-rule boundary. CP1's representative
live command approval remains historical executable evidence rather than a
claim that every provider response union was exercised live.

Denying is always product-permitted and is never gated on the offered list. A
deny widens nothing, `decline` is part of the protocol's command decision
union, and codex-cli 0.152.1 was measured to omit `decline` from
`availableDecisions` for an escalated command, offering only `accept`,
`acceptWithExecpolicyAmendment`, and `cancel`, while still honouring a
`decline` reply and continuing the turn. Requiring `decline` to be listed left
such a request with no card at all. Only an allow must be offered as a plain
one-time `accept`.

`ApprovalRequest` wraps the provider-neutral request with durable approval,
task, and turn identity. If a Codex command request omits its optional
`availableDecisions`, the driver fills its known legal command defaults and
then applies the safety ceiling; other supported requests use their legal fixed
decisions subject to the same ceiling. `ApprovalDecision` records allow or deny,
an optional scoped permission, whether an allow covers the matching task scope,
the resolution time, and timeout denial. The Codex driver converts that finite
contract through its reference-conformant adapter, whose decoder tolerates
additive runtime fields.

Grantable always means supported by the selected App Server sandbox and
approval policy. The renderer can choose only among the decisions preserved by
the driver from the running protocol; it cannot invent a broader permission.

Tool user-input and MCP elicitation requests are not misrepresented as binary
approvals. Until ByteQuay defines a separate typed interaction card, the
driver answers them with the protocol's cancel/decline response and emits an
observable unsupported-interaction event. Any unknown or unrouteable server
request fails closed.

```java
package com.bytequay.agent.core;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

public interface AgentApprovalService
{
    /**
     * Registers a provider permission request and waits asynchronously for a decision.
     *
     * <p>The request is emitted as a normalized approval event. Expiry resolves
     * to a denial; approval is never inferred from a frontend disconnect.
     *
     * @param request provider-neutral permission request
     * @param timeout maximum time to wait for the user
     * @return a stage completed with the typed response or fail-closed timeout
     * @throws AgentConflictException when the approval ID is already pending
     */
    CompletionStage<ApprovalResponse> request(ApprovalRequest request, Duration timeout);

    /**
     * Resolves one pending approval from a direct user action.
     *
     * <p>Repeating the same decision is idempotent. The requested response is
     * first intersected with the immutable application safety ceiling; an
     * unsafe or overly broad grant is rejected rather than forwarded. A
     * contradictory second decision fails with a conflict. The service stores
     * the terminal decision and appends a matching {@code RESOLVED} or
     * {@code EXPIRED} event before
     * completing the provider callback.
     *
     * @param command approval ID, expected state, and response variant matching
     * the stored request kind and product-permitted offered decisions
     * @return the recorded typed resolution
     * @throws ApprovalNotFoundException when no such approval is pending
     * @throws AgentConflictException when a different decision was already recorded
     */
    ApprovalResolution resolve(ResolveApprovalCommand command);

    /**
     * Denies all unresolved approvals owned by a terminal or cancelled turn.
     *
     * @param turnId turn whose approvals should be closed
     */
    void denyPending(TurnId turnId);
}
```

### 3.7 AgentMetricsService

`AgentMetricsService` centralizes provider-specific usage semantics. The turn
service atomically commits each sample's metrics projection and usage outbox
entry. A stable provider event ID becomes the durable outbox identity and gives
exact replay deduplication. When a provider omits identity, ByteQuay still
records and displays the callback, but cannot deduplicate a repeated delta
callback across replay.

```java
package com.bytequay.agent.core;

public interface AgentMetricsService
{
    /**
     * Captures the task's usage totals immediately before a provider turn starts.
     *
     * @param taskId task about to start a turn
     * @param turnId accepted turn
     * @return the baseline used to derive per-turn usage from cumulative providers
     */
    UsageBaseline beginTurn(TaskId taskId, TurnId turnId);

    /**
     * Applies one normalized provider usage sample.
     *
     * <p>The sample declares whether its counters are cumulative, delta, or
     * final-for-invocation. The caller deduplicates stable provider event IDs
     * through the same outbox transaction before invoking this method.
     *
     * @param taskId task that owns the provider session
     * @param turnId turn receiving the sample
     * @param sample normalized token and cost information with provenance
     * @return the latest persisted per-turn metrics
     * @throws AgentStorageException when metrics cannot be persisted
     */
    TurnMetrics record(TaskId taskId, TurnId turnId, ProviderUsageSample sample);

    /**
     * Finalizes elapsed time and usage for a terminal turn.
     *
     * @param turnId terminal turn
     * @param terminalState completed, failed, cancelled, or interrupted state
     * @return immutable final metrics for the turn
     * @throws AgentConflictException when the turn is not terminal
     */
    TurnMetrics finishTurn(TurnId turnId, TurnState terminalState);

    /**
     * Returns durable totals across all turns in a task.
     *
     * @param taskId task whose totals are requested
     * @return token, cost when available, elapsed time, and provenance totals
     */
    TaskMetrics taskTotals(TaskId taskId);
}
```

Cost values require provenance:

- `PROVIDER_REPORTED`: the provider identifies the amount as billing data.
- `CLI_ESTIMATE`: the CLI calculated an approximate amount locally.
- `UNAVAILABLE`: subscription/account use cannot be translated into an API
  invoice.

Cost equality is numeric rather than scale-sensitive. `TurnMetrics` and
`TaskMetrics` remove insignificant trailing decimal zeros at construction, so
equivalent values survive SQLite and summary-JSON round trips without breaking
the terminal task invariant that summary metrics equal task metrics.

ByteQuay does not estimate subscription-backed Codex cost from a local price
table. If the provider does not report an amount, the UI displays
`unavailable`; a CLI estimate is visibly labeled as approximate.

### 3.8 AgentCompletionService

`AgentCompletionService` closes a task, creates its durable final result, and
separately performs idempotent worktree and retention cleanup.

```java
package com.bytequay.agent.core;

public interface AgentCompletionService
{
    /**
     * Completes and closes a task while retaining its runtime log for seven days.
     *
     * <p>The implementation rejects active turns and live {@code QUEUED},
     * {@code PAUSED}, or {@code STARTING} inputs. The
     * explicit Complete action records any {@code UNCERTAIN} inputs as
     * {@code ABANDONED}; consumed {@code STARTED} and terminal queue rows do not
     * block closure. The live-queue check, uncertain-row
     * abandonment, and {@code COMPLETION} claim commit in one unit of work
     * against enqueue's task-version guard. It then closes the provider session,
     * collects the Git snapshot, durable turn/test facts, compact child index,
     * task metrics, and an optional display-only final agent message. It
     * revalidates the task worktree, rejects dirty or open Git state, commits
     * the summary, {@code COMPLETED} state, deletion deadline, and release of
     * the matching {@code COMPLETION} mutation claim in one transaction. It does
     * not remove the worktree. A direct interactive caller may request cleanup
     * immediately; a workflow first persists its own terminal result, then calls
     * {@link #cleanupWorktree(TaskId)}.
     * Agent message text is never interpreted and its absence or format cannot
     * prevent completion. The method does not delete retained runtime data.
     *
     * <p>A failure after the completion claim is acquired releases that claim in
     * the same transaction and leaves the task retryable in {@code OPEN} or
     * records terminal {@code FAILED}.
     *
     * @param command task ID/version plus optional user-supplied completion notes
     * @return the durable completion summary and retention deadline
     * @throws AgentConflictException when worktree-mutating work is active or the task cannot complete
     */
    TaskCompletion complete(CompleteAgentTaskCommand command);

    /**
     * Fails an open task after stopping and verifying its retained provider runtime.
     *
     * <p>The operation refuses an active turn, retains the worktree and
     * transcript, settles queued input without resending it, and atomically
     * records {@code FAILED} while releasing any matching stale claim. The
     * interactive controller may then call {@link #cleanupWorktree(TaskId)},
     * which removes only a verified clean worktree.
     *
     * @param taskId task the user chose not to continue
     * @param reason bounded user-visible failure reason
     * @return terminal failed task
     * @throws AgentConflictException when the task is terminal or has an active turn
     */
    AgentTask abandon(TaskId taskId, String reason);

    /**
     * Idempotently removes a terminal task's verified clean worktree while
     * retaining its generated branch. Pending task outbox events drain before
     * physical removal; a drain failure preserves the worktree.
     */
    void cleanupWorktree(TaskId taskId);

    /**
     * Removes the terminal worktree and retained runtime resources.
     *
     * <p>After the seven-day deadline this drains pending task outbox events,
     * then retries worktree removal before deleting runtime data. Failure
     * records {@code RETRY_PENDING} without changing the task's terminal state
     * or summary. Workflow-policy managed worktrees remain ineligible until
     * their owning workflow has completed worktree cleanup.
     *
     * @param taskId terminal task whose retention deadline has passed
     * @return the updated cleanup result
     * @throws AgentConflictException when the task is nonterminal or its retention deadline has not passed
     */
    CleanupResult cleanup(TaskId taskId);
}
```

## 4. Provider SPI

These contracts live in `agent-spi`. They describe a resumable provider
session and one running turn, not a universal model API or ByteQuay-owned tool
loop.

### 4.1 AgentDriverRegistry

```java
package com.bytequay.agent.spi;

import java.util.List;

public interface AgentDriverRegistry
{
    /**
     * Returns the configured driver for a stable lane ID.
     *
     * @param laneId requested lane
     * @return the configured driver
     * @throws AgentUnavailableException when the lane is unknown or disabled
     */
    AgentDriver require(AgentLaneId laneId);

    /**
     * Probes every configured lane for UI health display.
     *
     * <p>Probe failures are represented in each status and do not fail the
     * entire result.
     *
     * @return provider statuses in stable display order
     */
    List<AgentProviderStatus> statuses();
}
```

### 4.2 AgentDriver

```java
package com.bytequay.agent.spi;

import java.util.List;

public interface AgentDriver
{
    /**
     * Returns the stable identifier persisted with every task.
     *
     * @return provider lane ID
     */
    AgentLaneId id();

    /**
     * Returns features supported by this driver version.
     *
     * @return immutable capability description
     */
    AgentCapabilities capabilities();

    /**
     * Checks binary, authentication, and protocol readiness without starting a task.
     *
     * @return health state and user-actionable diagnostics
     */
    AgentProviderStatus probe();

    /**
     * Returns selectable provider models and their supported efforts and input
     * modalities when the provider exposes a catalog.
     *
     * <p>App Server drivers map {@code model/list}; providers without a native
     * catalog return an empty list and the existing settings service may use
     * its seeded catalog. Results are cached off the turn hot path.
     *
     * @return immutable current provider model catalog
     */
    List<ProviderModel> models();

    /**
     * Opens a new provider session or resumes an existing reference.
     *
     * <p>The worktree, lane, and maximum application safety boundary are fixed
     * for the returned session. A resumed reference must belong to the same
     * lane and supported contract version. Each turn may choose only a model,
     * effort, and permission setting allowed by that task boundary.
     *
     * @param context task identity, canonical worktree, validated task runtime,
     * provider scratch directory, allowed policy boundary, task defaults, and
     * optional session reference
     * @param events sink for normalized provider events
     * @return an open provider session
     * @throws AgentUnavailableException when the provider cannot open or resume
     */
    AgentSession open(AgentSessionContext context, AgentEventSink events);
}
```

### 4.3 AgentSession

```java
package com.bytequay.agent.spi;

public interface AgentSession
        extends AutoCloseable
{
    /**
     * Returns the opaque reference that ByteQuay must persist for later turns.
     *
     * @return provider session or thread reference
     */
    AgentSessionRef reference();

    /**
     * Starts one turn in this provider session.
     *
     * <p>The caller guarantees that no other turn is active in the session.
     * Provider output is emitted through the sink passed to
     * {@link AgentDriver#open}.
     *
     * @param command provider-neutral prompt, task-owned attachments, selected
     * model, reasoning effort, and maximum runtime
     * @return a handle for completion and cancellation
     * @throws AgentUnavailableException when the provider refuses or cannot start
     */
    RunningTurn start(AgentTurnCommand command);

    /**
     * Releases local transports owned by this session.
     *
     * <p>Closing does not delete provider-owned history and must be idempotent.
     * Normal task completion calls it only after the execution tree is
     * quiescent; the driver unsubscribes the root and every known subscribed
     * child thread. Recovery may instead terminate this task's owned provider
     * process tree and observe exit; another task has a separate process tree.
     */
    @Override
    void close();
}
```

### 4.4 RunningTurn

```java
package com.bytequay.agent.spi;

import java.util.concurrent.CompletionStage;

public interface RunningTurn
{
    /**
     * Returns the provider turn reference when the provider exposes one.
     *
     * @return provider turn reference or an opaque locally generated reference
     */
    ProviderTurnRef reference();

    /**
     * Returns a stage completed exactly once with a terminal fact and explicit quiescence result.
     *
     * <p>A root terminal message is necessary but not sufficient. Recursive
     * item history discovers the reachable V1/V2 graph and reconciles observed
     * execution-capable items and background terminals, but it carries no
     * descendant turn status. After that closure succeeds or fails, the driver
     * closes the per-turn App Server and classifies its complete owned process
     * tree. Success requires both a stable closure and {@code VERIFIED_STOPPED};
     * a failed closure returns a failed result, and its provider-quiescent flag
     * is true only after the same verified stop.
     * The stage completes normally; provider or transport failure is an
     * interrupted or failed terminal result, never exceptional completion.
     * After transport loss, interruption is not reported as quiescent until
     * this turn's task-owned provider process is {@code VERIFIED_STOPPED}.
     *
     * @return lifecycle completion for success, failure, cancellation, or interruption
     */
    CompletionStage<AgentTurnResult> completion();

    /**
     * Sends additional input to this exact running turn when supported.
     *
     * <p>The input cannot change model, effort, sandbox, or approval policy.
     * Drivers without native steering reject the operation as unsupported.
     *
     * @param input text and validated task-owned attachments
     */
    void steer(AgentTurnInput input);

    /**
     * Requests cancellation of the provider turn.
     *
     * <p>The call is idempotent and non-blocking. The completion stage reports
     * the eventual terminal result.
     */
    void cancel();
}
```

`AgentTurnResult` is consumed by `AgentTurnService` and by direct text-only
application consumers such as the writing assistant. It contains lifecycle
facts, operational diagnostics, and a bounded optional `finalResponse` for
those direct consumers. Durable `AgentTurnService` ignores `finalResponse` as
task output and maps only provider-protocol and process facts to the durable
turn lifecycle: an official terminal success or failure, an explicit
cancellation, or a lost/uncertain execution. Canonical assistant messages are
separate events and remain authoritative for durable tasks; neither their text
nor `finalResponse` is inspected to decide whether a turn completed or failed.

`AgentTurnCommand` contains the accepted text and attachments plus the immutable
per-invocation fields the driver consumes: model, optional reasoning effort,
and maximum runtime. Durable `AgentTurnSettings` also records the effective
permission and sandbox policies. Those policies are enforced through the
task-scoped `AgentSessionContext`: public interactive tasks are fixed to `AUTO`
and each workflow policy is fixed for the lifetime of its task, so a reused
session cannot observe policy drift under the current surface. Any future
per-turn permission change must move that policy into `AgentTurnCommand` or
recreate the provider session before it is accepted. `AgentTurnInput` contains
only steerable text and attachment references. `AgentCapabilities` adds native
steering support, while each `ProviderModel` reports its own input modalities
and effort choices.

A text-only session's normalized worktree must be beneath its runtime directory.
Both CLI drivers validate that invariant before assigning the existing
`CLI_PROBE` owner role, so this compatibility name covers provider control
commands and isolated helpers but never a task-worktree-capable process.

### 4.5 AgentEventSink

```java
package com.bytequay.agent.spi;

@FunctionalInterface
public interface AgentEventSink
{
    /**
     * Accepts one provider-normalized event for backend sequencing.
     *
     * <p>The driver must preserve provider order and must not include secrets
     * or unsupported hidden reasoning.
     *
     * @param event normalized event associated with the current task and turn
     */
    void emit(ProviderAgentEvent event);
}
```

Every `ProviderAgentEvent` carries a `ProviderAgentEventSource` containing the opaque
provider thread ID, optional direct parent thread ID, optional provider turn
ID, and optional provider item ID when supplied. Execution, approval, delivery,
and child relationships require stable identity. A driver may synthesize one
only from deterministic provider fields covered by replay-stability tests;
display-only events may omit it and render at the root. This source metadata is
part of the normalized SPI value; core does not infer it later from session
timing, display text, or generic tool summaries.

The sink is a dispatcher, not a shortcut around state services. Display-only
events go to `AgentEventService.append(...)`; usage, approval, and terminal
observations go to the owning core service so its SQLite projection and event
outbox entry commit together.

The provider event family is expanded only for observable provider facts:
user-visible reasoning summaries, web/image activity, rich tool
arguments/results, approval requests, and subagent lifecycle. Approval
resolution, user delivery, and queue payloads originate in core.
Codex wire DTOs, the compact reference projection, and raw Claude blocks remain
private to their drivers.

## 5. Owned-process runtime interfaces

These interfaces live in `agent-cli` and are shared by the Codex and Claude
drivers. They are not part of the provider SPI and are not exposed to future
HTTP API drivers.

The existing `GitCommandRunner` in `app` also handles short-lived `gh`
commands. It merges stdout and stderr and has no streaming stdin, so it
must not be stretched into the long-lived agent runtime.

### 5.1 ProcessSupervisor

```java
package com.bytequay.agent.cli.process;

import java.util.List;

public interface ProcessSupervisor
{
    /**
     * Creates and fsyncs a launch marker without starting a process.
     *
     * <p>The caller persists the returned launch ID and {@code PREPARED} launch
     * intent in its own unit of work before invoking
     * {@link PreparedProcess#start}. This closes the database/process gap.
     *
     * @param spec owner kind/ID, executable, arguments, environment allowlist,
     * execution profile, worktree, and limits
     * @return durable prepared launch; no payload process exists yet
     */
    PreparedProcess prepare(ProcessSpec spec);

    /**
     * Terminates provider processes owned by an earlier backend instance.
     *
     * <p>The supervisor reads owner-only launch markers and signals a PID only
     * when its executable and OS process-start token still match. It waits for
     * the complete process tree to exit and returns an unverifiable result
     * rather than risking an unrelated process.
     *
     * @return one terminal or unverifiable result per launch marker
     */
    List<OwnedProcessRecovery> recoverOwnedProcesses();

    /**
     * Deletes a terminal launch marker/result only after its owning state
     * transition has committed durably.
     *
     * @param launchId agent-cli-local opaque launch identifier
     */
    void acknowledge(String launchId);
}

public interface PreparedProcess
{
    String launchId();

    /**
     * Starts the already prepared argv-based process.
     *
     * <p>No shell is used. Stdout and stderr are drained independently, output
     * limits are enforced, and the sink is called in observed stream order.
     */
    ManagedProcess start(ProcessOutputSink output);

    /** Removes a known-unsent prepared marker after owner rollback. */
    void abandon();
}
```

`ProcessSpec` carries an agent-cli-local opaque owner string; it never imports a
`TaskId` or `TurnId` type from `agent-core`. The owning driver maps that value
back to durable domain state. `ProcessOwner.Kind` also carries one closed
`ExecutionProfile` value rather than arbitrary sandbox text:

- `PROVIDER_CONTROL` owns one task's App Server or one Claude turn process and
  does not claim an endpoint-level network allowlist;
- `TASK_EXEC_SERVER` is retained only to recover markers written by the
  superseded loopback-executor design; no current launch selects it.

Within `PROVIDER_CONTROL`, `CLI_PROBE` identifies short-lived provider control
commands and validated text-only helpers. The exact
`APP_SERVER/codex-model-catalog` owner is also control-only. Every other
`APP_SERVER` owner and every `CLAUDE_TURN` owner is task-worktree-capable.
Keeping this classification on existing owner values preserves durable marker
and SQLite compatibility without adding a lifecycle state.

Adding another profile requires a design change and compatibility fixture. A
caller cannot supply raw Seatbelt rules, broaden writable roots, or turn a
provider approval into a different execution profile.

`prepare(...)` and `PreparedProcess.start(...)` use a gated-launch protocol
instead of a spawn-then-record race:

1. `prepare` fsyncs a `PREPARED` marker containing launch/backend IDs, opaque owner,
   executable identity, argv digest, and intended process-group/session;
2. the owning service commits that launch ID and `PREPARED` intent, then uses a
   guarded unit of work to move `PREPARED -> ATTEMPTING` immediately before it
   invokes `start`;
3. `start` spawns a tiny owned launcher that reports PID, OS start token, and
   process group, waits on a parent-held gate, and exits without executing the
   payload if that gate closes; `start` fsyncs those identities as `GATED`;
4. before opening the gate, `start` fsyncs `RELEASE_PENDING`; it then releases
   the gate and fsyncs `RUNNING`. `RELEASE_PENDING` is conservatively treated as
   possibly executed even if the final write was not observed; and
5. only after `start` returns a `RUNNING` marker does the owning service commit
   `ATTEMPTING -> RUNNING`.

A pre-spawn `PREPARED` marker is removable only after recovery proves no gated
launcher exists. A crash after spawn but before identity fsync closes the gate,
so the launcher exits without executing the payload. Once released, the marker
also records the owner process group/job identity and observed member
PID/start-token pairs; this permits recovery after the root exits while a child
remains. This proves only recorded membership. A descendant that can escape
before observation makes the result `UNVERIFIABLE`; ByteQuay does not infer
containment from one readiness fixture or from root exit. The narrow exception
is a control-only launch whose descendant disappears between the OS snapshot
and start-token read. A live unreadable descendant, or any unidentified
descendant of a task-worktree-capable launch, remains `UNVERIFIABLE`. Recovery
blocks the affected claim unless the validated launch path provides a stronger
platform primitive or all relevant execution is reconciled through the
provider contract instead.

The owning row records one durable fact, whether the owner had committed to
spawning, while the marker supplies every finer launch fact. Recovery uses both:

- no owning row and no marker is known unlaunched;
- a row that never reached the spawn, plus an intact `PREPARED` marker, is known
  unlaunched;
- a row that never reached the spawn and has no marker at all is known
  unlaunched, because the gate could not have been released;
- an attempted row plus a `PREPARED` marker means the crash preceded the spawn
  and is known unlaunched;
- an attempted row plus `GATED` closes the gate and, after the launcher is
  verified stopped, proves the payload was unlaunched;
- an attempted row plus `RELEASE_PENDING`/`RUNNING` is treated as possibly
  executed and follows full process/result recovery; and
- an impossible ordering, such as a marker that observed a launcher under a row
  that never reached the spawn, or a missing/corrupt marker for any attempted
  launch, is `UNVERIFIABLE`.

The owning row deliberately holds no lifecycle beyond that one fact. Every later
transition is already durable in the marker, so mirroring them would create two
state machines to keep in step and one more write that could fail while the
process is healthy.

Orphan prepared markers without an owning committed intent are removable only
when their format proves no launcher was released.

Tree stop changes the marker to `TERMINAL` and fsyncs the `ProcessExit`/result
receipt; it does not delete it. For a task process, the owning core transaction
first commits the turn terminal fact and tree proof, then calls
`acknowledge(launchId)`. Probes and text-only helpers acknowledge after verified
close. A crash on either side replays the terminal receipt or deletes an
already-acknowledged marker idempotently. Task App Server markers name their
task and Claude turn markers name their turn; model-catalog, probe, and helper
markers instead carry their closed control owner.
Markers contain no credentials, prompt
text, or core-domain objects. A missing/incomplete marker for a
launch intent that had reached `ATTEMPTING` is unverifiable: recovery keeps the
durable claim and task worktree untouched instead of assuming the launch never happened.

When marker and launch-row owners both exist, they must agree. An unverifiable
task-capable launch blocks its task; an unknown or conflicting owner blocks the
agent gate globally. A recognized control-only receipt is retained for later
recovery but does not block task worktrees it could never access.

### 5.2 ManagedProcess

```java
package com.bytequay.agent.cli.process;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;

public interface ManagedProcess
        extends AutoCloseable
{
    /**
     * Returns the opaque launch identifier used for durable acknowledgement.
     *
     * @return agent-cli-local launch identifier
     */
    String launchId();

    /**
     * Returns the operating-system process ID.
     *
     * @return child process ID
     */
    long pid();

    /**
     * Writes bytes to the child's standard input.
     *
     * @param bytes bytes to write; ownership remains with the caller
     * @return a stage completed when the bytes are accepted or the process exits
     */
    CompletionStage<Void> write(ByteBuffer bytes);

    /**
     * Closes the child's standard input without terminating the process.
     */
    void closeInput();

    /**
     * Returns a stage completed after root exit and process-tree verification.
     *
     * <p>The supervisor tracks the owned process group/descendants. The result
     * distinguishes {@code VERIFIED_STOPPED} from {@code UNVERIFIABLE}; root
     * PID exit alone is never sufficient to release a worktree-mutation claim.
     *
     * @return root exit code, full-tree termination proof, terminal reason,
     * timing, and truncation flags
     */
    CompletionStage<ProcessExit> exit();

    /**
     * Requests graceful termination followed by forced process-tree termination.
     *
     * <p>The method is idempotent and does not block for the grace period.
     * Callers await {@link #exit()} and treat only a verified full-tree stop as
     * quiescent.
     */
    void cancel();

    /**
     * Releases streams and terminates a still-running process.
     */
    @Override
    void close();
}
```

`ProcessExit.treeTermination()` is `VERIFIED_STOPPED` only after every owned
process-group/job member and recorded descendant is observed gone. Detached/background-child uncertainty returns
`UNVERIFIABLE`; recovery keeps the task claim held and its worktree untouched. The same
rule applies to normal cancellation, backend-startup recovery, and forced
close. Each task's App Server and each Claude
process use this exact rule.
Per-command lifecycle is additionally reconciled through the provider contract
in section 5.3.

### 5.3 Task process boundary

A turn's task-owned App Server process runs with the task worktree as its working
directory, and that is the whole boundary. Under `sandbox: workspace-write`
App Server's default environment is rooted at its own working directory, so a
model-requested write inside the worktree succeeds and one outside it is
escalated as an approval rather than performed. Measured against codex-cli
0.151.0 with no environment registered: an in-worktree write landed with zero
approvals, and a write to a path under `$HOME` produced an approval request and
never landed.

That removes an entire mechanism rather than reimplementing it. There is no
`codex exec-server` process, no `environment/add` upsert, no
`environment/info` handshake, no `environments` selection on threads or turns,
and no unauthenticated loopback listener to treat as a same-host trust
boundary. Task App Server sessions declare
`capabilities.experimentalApi: true` solely for the background-terminal
inventory and termination methods required by quiescence and cancellation.
That declaration does not restore the removed environment family or authorize
any other experimental method.

The launch owner is the task id, so a recovered orphan is attributable to the
task that leaked it rather than to a single process shared by the backend.
`ProcessOwner.Kind.APP_SERVER` carries that id; the persisted
`LaunchOwnerKind.TASK_EXEC_SERVER` constant is retained only so launch markers
and `agent_process_launch` rows written before this change still deserialize.

A per-turn session owns its task's process. After root completion and bounded
history/background reconciliation, or after that reconciliation fails, the
turn service closes the session and requires the supervisor to classify the
whole owned process tree as `VERIFIED_STOPPED`. The next accepted turn resumes
the stored provider thread in a new process selected from that turn's immutable
model snapshot. `AgentRuntimeRegistry` also implements `AutoCloseable` and is
wired as the bean's destroy method, so a backend shutdown with open tasks cannot
strand a process.

#### ByteQuay-owned Codex home

Every Codex process ByteQuay starts, the per-turn task App Server, the model-catalog
App Server, and the probe commands, runs with `CODEX_HOME` set to ByteQuay's
own directory, `~/Library/Application Support/ByteQuay/codex-home`, created
owner-only with an empty user config. Nothing in the user's `~/.codex` reaches
a task: not its exec-policy rules, hooks, plugins, MCP servers, or login. The
variable is set on those processes alone, so the user's own Codex desktop and
terminal sessions keep `~/.codex` untouched, and the model-facing shell still
never sees `CODEX_HOME`.

The home is signed in separately, once, with the exact command readiness
reports, `CODEX_HOME="…/ByteQuay/codex-home" codex login`, which stores that
login in ByteQuay's home only. ByteQuay never reads, copies, or links the user's
credentials. The isolation guard keeps running against ByteQuay's home as the
safety net; with an empty home it passes by construction, which is what makes
the Codex lane usable on a machine whose own Codex setup carries inherited
capabilities.

### 5.4 Turn quiescence

A turn completes when its process is quiet, not when its root thread reports
completion. A yielded command outlives the item that started it, and a subagent
can still be working, so a clean completion at that moment would release the
task's claim over files something may still be writing.

Pre-stop reconciliation uses the live item stream plus
`thread/backgroundTerminals/list` for every thread the process has shown. Item
notifications are never shed, which is what makes the live stream sound to
reason from; only streamed text is dropped under backlog pressure.
`capabilities.experimentalApi` is true solely for those background-terminal
methods.

Each pass first closes over history with `thread/items/list`, which is the only
thing that can see an item or relationship the notification stream never
delivered. Root entries are restricted to the current provider turn so a
resumed process cannot rediscover an old child or fail on an old unfinished
item. Terminal history reconciles a live item whose completion notification was
missed before the in-memory and background-terminal inventories are checked.
`thread/items/list` does not report a descendant turn's terminal status, so even
an empty, stable item/background fixed point is not proof that a child stopped.
`thread/read(includeTurns: true)` remains unimplemented.

The wait runs on its own thread, never the message consumer, because the
consumer's queue holds the notifications that would end the wait. It repeats
until two consecutive passes find no live item, no background terminal, and no
thread that appeared mid-pass. Then it closes the per-turn App Server and waits
for `VERIFIED_STOPPED`; only that process fact proves quiescence. A history,
item, or background check that cannot settle makes the turn `FAILED`, but the
driver still closes the process. That failure is quiescent only if the full
owned tree is verified stopped. An unverifiable stop reports
`providerQuiescent: false`, so `agent-core` retains the worktree claim.

### 5.5 Claim retention and abandonment

A turn releases its worktree mutation claim only when the provider was shown
quiet. `AgentTurnResult` carries `providerQuiescent`; for Codex this is true only
after the per-turn App Server's complete owned process tree is
`VERIFIED_STOPPED`, and Claude applies the same rule to the process tree of its
per-turn CLI invocation. Exceptional provider completion and any unverified
session close are non-quiescent by definition.

When it is false the claim is retained. That deliberately blocks every later
turn on the task, because the alternative is handing a turn a worktree that
something may still be writing to. The task stays `OPEN` and its worktree stays
on disk.

Completion cannot resolve that state — it refuses a held claim — so two
user actions exist. An exceptional or unverifiable close retains the task's
session/process identity with its stale `TURN` claim. `AgentTurnService.stopRuntime`
stops only this task's provider session and owned process tree and explicitly
retries termination/verification against that retained runtime. A successful
close returns only after `VERIFIED_STOPPED`, releases the stale claim, and
allows a new turn or paused queue item to be retried; it never resends the old
turn. Another unverifiable close leaves the claim in place. The operation is confirmed
against the generation returned by `GET /agent/tasks/{taskId}/runtime/stop-impact`
and reachable at `POST /agent/tasks/{taskId}/runtime/stop`.
`AgentCompletionService.abandon` is the way out of the task itself. It is guarded
the same way completion is, by naming the claim it discards, refuses a task
with an active turn, stops and verifies the task's own process first, and
retains the worktree rather than cleaning it, because whatever the provider
left behind is the evidence someone will want. It is reachable at
`POST /agent/tasks/{taskId}/mark-failed`, which then removes only a clean
worktree and never touches one whose old process could not be verified stopped.

After a backend restart, a turn that was mid-flight has no transport and is
interrupted once the previous backend's processes are classified. A verified
stop releases the turn's claim; an unverifiable launch swaps it for a
`RECOVERY` claim keyed by that launch, and a later startup that verifies the
launch stopped is what releases it.

Release compatibility tests run reference-version sandbox and lifecycle
fixtures: ordinary built-in tool execution must stay inside the task worktree,
yielded commands must appear in background-terminal listing, and clean or
terminate must stop them. A command that escapes both process ownership and App
Server's registries makes the result `UNVERIFIABLE` and the Codex lane
ineligible for release; one successful fixture is not generalized into a proof
about arbitrary detached descendants. Failure of the sandbox path or
background-terminal API in the running CLI disables the affected Codex
development task and reports the detected Codex version.

## 6. Persistence ports

These interfaces live in `agent-core`; SQLite/JPA implementations live in
`persistence-sqlite`. See
[ByteQuay Architecture](architecture.md) for the dependency direction.

### 6.0 AgentUnitOfWork

```java
package com.bytequay.agent.core.store;

public interface AgentUnitOfWork
{
    <T> T execute(Work<T> work);

    @FunctionalInterface
    interface Work<T>
    {
        T run();
    }
}
```

The SQLite adapter executes the callback in one transaction. Every store call
inside it joins that transaction; no provider, Git, JSONL, or SSE operation is
allowed inside the callback. A core state transition therefore writes its
task/turn/approval/delivery/queue facts and corresponding
`AgentEventOutboxEntry` together, commits, and only then calls
`AgentEventService.drain(eventId)`. Startup drains pending entries before new
work is accepted.

User message bytes and other large event bodies are written to a task-owned
blob before the transaction. The blob file and its containing directory are
both flushed before the reference is returned. The outbox contains only bounded
event metadata and a typed blob reference, so raw prompts and tool output are
not copied into SQLite. If the transaction rolls back, the unreferenced blob
remains confined to that task and is removed by normal task cleanup.

### 6.1 AgentTaskStore

```java
package com.bytequay.agent.core.store;

import com.bytequay.agent.spi.AgentPermissionPolicy;

import java.util.List;
import java.util.Optional;

public interface AgentTaskStore
{
    /**
     * Inserts a new task.
     *
     * @param task task in its initial durable state
     * @throws AgentConflictException when the task ID already exists
     */
    void insert(AgentTask task);

    /**
     * Finds a task by ID.
     *
     * @param taskId task identifier
     * @return the task, or empty when absent
     */
    Optional<AgentTask> find(TaskId taskId);

    /**
     * Replaces a task using optimistic version checking.
     *
     * @param task updated task whose version matches the stored row
     * @return the stored task with its next version
     * @throws AgentConflictException when the stored version changed
     */
    AgentTask update(AgentTask task);

    /**
     * Claims exactly one mutating operation inside the task worktree.
     *
     * <p>The conditional update succeeds only when the task version matches,
     * the task is in an allowed state, and no claim is held. It runs inside
     * {@link AgentUnitOfWork} with the owning turn or completion
     * mutation and its outbox event.
     *
     * @param taskId task whose generated worktree is claimed
     * @param expectedVersion optimistic task version
     * @param claim turn or completion owner
     * @return updated task containing the claim
     */
    AgentTask claimWorktreeMutation(
            TaskId taskId,
            long expectedVersion,
            WorktreeMutationClaim claim);

    /**
     * Releases a claim only for its matching owner.
     *
     * @param taskId owning task
     * @param claim matching current claim
     * @return updated task with no task-local worktree-mutation claim
     */
    AgentTask releaseWorktreeMutation(TaskId taskId, WorktreeMutationClaim claim);

    /**
     * Serializes queue insertion against task completion.
     *
     * <p>The conditional update bumps the task version only while the task is
     * {@code OPEN} and its claim is absent or owned by the active turn. It
     * fails for a completion claim or a turn claim whose quiescence is not
     * proved. The queue row
     * and event outbox entry are inserted in the same unit of work, so either
     * enqueue wins before Complete observes the queue or Complete wins and
     * enqueue receives a conflict.
     *
     * @param taskId owning task
     * @param expectedVersion renderer-observed task version
     * @return task with its next optimistic version
     */
    AgentTask guardQueueInsert(TaskId taskId, long expectedVersion);

    /**
     * Reports whether a persisted task still owns managed worktree facts for
     * a workspace, including a retained generated branch after cleanup.
     *
     * @param workspaceId workspace considered for removal
     * @return true when removal would orphan task-owned Git facts
     */
    boolean hasWorktreeReference(String workspaceId);

    /**
     * Lists terminal managed tasks whose successful worktree removal is not recorded.
     *
     * <p>A workflow monitor must match the task to its own durable terminal
     * workflow row before requesting cleanup.
     */
    List<AgentTask> listPendingWorktreeCleanup(
            AgentPermissionPolicy permissionPolicy,
            Instant retryBefore);

    /**
     * Lists recently updated tasks.
     *
     * @param workspaceId owning workspace
     * @param limit maximum rows to return
     * @return tasks ordered by descending update time
     */
    List<AgentTask> listRecent(String workspaceId, int limit);
}
```

### 6.2 AgentTurnStore

```java
package com.bytequay.agent.core.store;

import java.util.List;
import java.util.Optional;

public interface AgentTurnStore
{
    /**
     * Inserts an accepted turn and enforces one active turn per task.
     *
     * @param turn accepted turn
     * @throws AgentConflictException when the ID exists or the task already has an active turn
     */
    void insert(AgentTurn turn);

    /**
     * Finds a turn by ID.
     *
     * @param turnId turn identifier
     * @return the turn, or empty when absent
     */
    Optional<AgentTurn> find(TurnId turnId);

    /**
     * Finds a turn previously accepted with an idempotency key.
     *
     * @param taskId task identifier
     * @param idempotencyKey caller-supplied stable key
     * @return matching turn, or empty when no request was accepted
     */
    Optional<AgentTurn> findByIdempotencyKey(TaskId taskId, String idempotencyKey);

    /**
     * Finds the task's current active turn.
     *
     * @param taskId task identifier
     * @return accepted or running turn, or empty when idle
     */
    Optional<AgentTurn> findActive(TaskId taskId);

    /**
     * Replaces a turn using optimistic version checking.
     *
     * @param turn updated turn
     * @return the stored turn with its next version
     * @throws AgentConflictException when the stored version changed
     */
    AgentTurn update(AgentTurn turn);

    /**
     * Lists all turns for summary and metrics calculation.
     *
     * @param taskId task identifier
     * @return turns ordered by creation time
     */
    List<AgentTurn> list(TaskId taskId);
}
```

### 6.3 ApprovalStore

Approval state is durable independently of an SSE connection. Resolution is
an optimistic, idempotent state transition; the store preserves the tagged
request, product-permitted available decisions, typed response, source IDs, and
timestamps.

```java
package com.bytequay.agent.core.store;

import java.util.List;
import java.util.Optional;

public interface ApprovalStore
{
    void insert(PendingApproval approval);

    Optional<PendingApproval> find(ApprovalId approvalId);

    ApprovalResolution resolve(ApprovalId approvalId, ApprovalResponse response);

    List<PendingApproval> listPending(TurnId turnId);

    List<ApprovalResolution> denyPending(TurnId turnId, ApprovalResolutionReason reason);
}
```

The service stores the request before publishing its card and stores the
resolution before completing the driver callback. Unsupported, expired,
unmatched, terminal-turn, and provider-disconnect requests resolve fail closed
and append the same terminal event as a user denial.

### 6.4 SteerDeliveryStore

Direct `turn/start` is one-to-one with `AgentTurn`, so it uses the turn's
`startedAt`, state, and provider reference and has no parallel delivery store.
App Server does not accept a client idempotency key for `turn/steer`, so CP10
added this small steer-only store with explicit delivery state. It remains
steer-specific and is not generalized for speculative input kinds.

```java
package com.bytequay.agent.core.store;

import java.util.List;
import java.util.Optional;

public interface SteerDeliveryStore
{
    void insert(AgentInputDelivery delivery);

    Optional<AgentInputDelivery> findByIdempotencyKey(
            TaskId taskId,
            String idempotencyKey);

    AgentInputDelivery update(AgentInputDelivery delivery);

    List<AgentInputDelivery> listUnsettled();
}
```

The `(taskId, idempotencyKey)` key is unique. `ACCEPTED` means no provider
send was attempted and can be recovered safely. A definite local validation,
session-open, or pre-send setup failure changes `ACCEPTED` to `FAILED`; it is
never retried implicitly. Once the send begins, a definite provider refusal
changes `SENDING` to `FAILED`, while connection/process loss changes it to
`UNKNOWN`. The UI may offer “send again as new input” only after provider
quiescence is proven; that creates a new key and warns that an `UNKNOWN` steer
might already have arrived.

### 6.5 QueuedInputStore

The queue needs one small durable store; it is not reconstructed from provider
history. The store preserves FIFO insertion order and state transitions. The
service claims the head and inserts its `AgentTurn` in one SQLite transaction,
so there is no remove-before-insert crash window.

```java
package com.bytequay.agent.core.store;

import java.util.List;
import java.util.Optional;

public interface QueuedInputStore
{
    void insert(QueuedAgentInput input);

    Optional<QueuedAgentInput> findByIdempotencyKey(TaskId taskId, String idempotencyKey);

    List<QueuedAgentInput> list(TaskId taskId);

    List<QueuedAgentInput> listOutstanding(TaskId taskId);

    Optional<QueuedAgentInput> head(TaskId taskId);

    QueueClaim claimHeadAndInsertTurn(
            TaskId taskId,
            AgentTurn acceptedTurn);

    QueuedAgentInput pause(QueuedInputId inputId, String reason);

    QueuedAgentInput markStarted(QueuedInputId inputId);

    QueuedAgentInput markUncertain(QueuedInputId inputId, String reason);

    QueuedAgentInput remove(QueuedInputId inputId);

    QueuedAgentInput abandon(QueuedInputId inputId, String reason);
}
```

`claimHeadAndInsertTurn(...)` locks the FIFO head and commits its `STARTING`
state and the `ACCEPTED` turn in one SQLite transaction. The queue row itself
owns its delivery claim; it does not create a second input-delivery row. Queue
idempotency is unique per task. The user-message event is keyed by queued-input
ID and drains before the claimed turn receives `startedAt`, so replay
deduplication can safely identify a known-unsent claim. On startup, such a
turn becomes `INTERRUPTED`, and the queue row
becomes `PAUSED`; a claimed turn with `startedAt` but no provider reference
becomes `UNCERTAIN` and `INTERRUPTED`. Only the latter is ineligible for
resend. A committed
`STARTED` claim is already consumed even if the provider later fails and does
not block task completion. Removing an untouched input records `REMOVED`;
discarding an `UNCERTAIN` claim records `ABANDONED` without claiming that the
provider did not receive it.

The first claim creates the queue item's stable user-message event. A later
explicit resume after a known pre-send failure creates a new turn attempt
but references that existing message event and emits only a delivery-attempt
event, so replay shows one user request with its attempts rather than duplicate
message text.

Enqueue runs inside `AgentUnitOfWork` with
`AgentTaskStore.guardQueueInsert(...)` and its outbox entry. Complete reads
`listOutstanding(...)` and conditionally acquires the `COMPLETION` claim in one
unit of work. SQLite serialization plus the task-version write guarantees that
a concurrent enqueue either becomes visible before that check or fails after
completion wins; it cannot create live work on a terminal task.

### 6.6 AgentEventOutboxStore

```java
package com.bytequay.agent.core.store;

import java.util.List;
import java.util.Optional;

public interface AgentEventOutboxStore
{
    void insert(AgentEventOutboxEntry entry);

    Optional<AgentEventOutboxEntry> find(EventId eventId);

    List<AgentEventOutboxEntry> listPending(int limit);

    void markAppended(EventId eventId, long sequence);
}
```

The SQLite implementation participates in the same transaction as the owning
task, turn, approval, delivery, queue, or metric mutation.
`markAppended` is
idempotent. If JSONL was flushed but the mark was not, retry finds the same
`EventId` in `TaskLogStore` and records its existing sequence rather than
appending a duplicate.

### 6.7 TaskLogStore

```java
package com.bytequay.agent.core.store;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

public interface TaskLogStore
{
    /**
     * Appends one complete event envelope as a single JSONL record.
     *
     * <p>The append is flushed before returning. After a crash, only a final
     * partial line may be ignored; earlier corruption is reported.
     *
     * @param envelope sequenced event to append
     * @throws AgentStorageException when the append or flush fails
     */
    void append(AgentEventEnvelope envelope);

    /**
     * Finds an already appended envelope by ByteQuay event identity.
     *
     * <p>This makes outbox retry idempotent when JSONL was flushed before its
     * SQLite outbox row was marked delivered.
     *
     * @param taskId owning task
     * @param eventId stable ByteQuay event identity
     * @return the existing task sequence when present
     */
    Optional<Long> findSequence(TaskId taskId, EventId eventId);

    /**
     * Reads a bounded page after a sequence number.
     *
     * @param taskId task whose log is read
     * @param afterSequence exclusive lower sequence bound
     * @param limit maximum records
     * @return ordered event envelopes
     * @throws AgentStorageException when non-tail corruption is detected
     */
    List<AgentEventEnvelope> readAfter(TaskId taskId, long afterSequence, int limit);

    /**
     * Stores a large payload outside JSONL and returns its relative reference.
     *
     * @param taskId owning task
     * @param content bounded payload bytes
     * @param mediaType payload media type
     * @return task-relative blob reference safe to place in an event
     * @throws AgentStorageException when the blob cannot be stored safely
     */
    BlobRef writeBlob(TaskId taskId, ByteBuffer content, String mediaType);
}
```

## 7. Create-to-close lifecycle

This is the normative lifecycle. The frontend calls only the public service
entry points through HTTP controllers. Each public service implementation then
coordinates the narrower interfaces shown in the last column.

| Step | Frontend action | Public service method | Required internal calls and result |
| --- | --- | --- | --- |
| 1. Create and send | `POST /agent/tasks` with `workspaceId`, first `message`, and idempotency key, never a path | `InteractiveAgentTaskCreator.create(...)`, `AgentTurnService.start(...)`, then `AgentTaskTitleService.generate(...)` | `app` resolves the workspace/repository and exact starting commit; core verifies readiness before provisioning and persists `OPEN` with a 36-character first-message fallback marked `PROVISIONAL`. The controller starts the idempotent initial turn, captures the response, and only for a new turn schedules best-effort naming. That disposable text-only session uses the same lane outside the task conversation; it may atomically replace only a still-provisional title and insert `TITLE_UPDATED`. Failure leaves the fallback, and a user rename always wins |
| 2. Attach | Optional authenticated image upload | `AgentResourceService.storeAttachment(...)` | Validates content and limits, writes a task-owned blob, and returns an opaque `AttachmentRef`; arbitrary renderer paths are rejected |
| 3. Observe | Open task window or reconnect SSE | `AgentEventService.subscribe(TaskId, afterSequence)` | Replays persisted root and child events and then publishes live events without a gap |
| 4. Start turn | `POST /agent/tasks/{taskId}/turns` | `AgentTurnService.start(StartAgentTurnCommand)` | Rechecks lane readiness before accepting input and writes the bounded message blob. One short `AgentUnitOfWork` reserves the idempotency key and worktree-mutation claim as `ACCEPTED` with the user-message outbox entry. The event drains before provider send; the service opens/resumes the session, persists its reference, begins metrics, persists `startedAt`, starts the run, and records `RUNNING` with its provider reference. Failed readiness creates no turn |
| 5. Run | Keep SSE open; steer, queue, cancel, or resolve approvals | `AgentTurnService` and `AgentApprovalService` operations | Display-only provider events pass through `AgentEventService.append(...)`; any provider observation coupled to SQLite state uses `AgentUnitOfWork` plus the event outbox; steer persists and drains its input event before delivery; enqueue conditionally updates the task version with its row/event so it cannot race Complete and does not call the provider; approval resolution and its event commit and drain before the provider callback completes |
| 6. Finish turn | Read turn state as needed | `AgentTurnService.get(TurnId)` | A root terminal event remains visible while recursive item/background reconciliation runs. Because item history has no descendant turn status, the per-turn App Server then closes and its owned tree must be `VERIFIED_STOPPED` before core releases the worktree claim. Reconciliation failure records a failed turn; verified stop still permits safe release, while an unverifiable stop retains the claim. Core atomically finalizes metrics/state and inserts terminal events, and only a quiescent success may claim the FIFO head |
| 7. Continue | Send or resume queued input on the same task | `AgentTurnService.start(...)` or `resumeQueued(...)` | Reuses the stored `AgentSessionRef`, creates one new `TurnId`, and launches a new matching-profile process; non-success leaves queued work paused |
| 8. Mark failed | `POST /agent/tasks/{taskId}/mark-failed` after inspecting the incident | `AgentCompletionService.abandon(TaskId, String)`, then optional `cleanupWorktree(TaskId)` | Refuses an active turn, closes the task's retained provider session, and requires its owned tree to be verified stopped; one unit of work records the incident and `FAILED` and releases the mutation claim. The interactive controller then attempts normal clean-worktree cleanup unless a pre-restart process remained unverifiable; dirty or unverifiable work remains untouched |
| 9. Close | `POST /agent/tasks/{taskId}/complete?workspaceId=...` | `AgentCompletionService.complete(CompleteAgentTaskCommand)`, then `cleanupWorktree(TaskId)` | The controller first resolves `getInteractive(workspaceId, taskId)`, so neither another workspace nor a workflow-owned task can cross this endpoint. It rejects an active turn or any last-turn process not already verified stopped, live `QUEUED`/`PAUSED`/`STARTING` input, dirty worktree, or open Git operation; Complete abandons uncertain inputs, claims the worktree-mutation boundary, collects Git/turn/metric facts, and atomically commits the summary, `COMPLETED`, and release of the matching claim. The interactive controller then removes the reverified worktree without force. Workflow callers insert their durable `FINISHED` result between these calls |
| 10. Delete runtime | Automatic retention job after seven days | `AgentCompletionService.cleanup(TaskId)` | Retries verified worktree removal before `AgentResourceService.delete(...)`, then records cleanup as `DELETED`; failure records `RETRY_PENDING` for the same operation |

The base send path is shown end to end below. Calls below `AgentTaskService`,
`AgentTurnService`, and `AgentCompletionService` are internal orchestration,
not additional frontend requests. `Web` is reached only by Electron main over
authenticated localhost HTTP/SSE; React uses typed preload IPC and never owns
the backend session token.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Desktop as React + Electron main
    participant Web as HTTP/SSE controller
    participant Creator as InteractiveAgentTaskCreator
    participant Tasks as AgentTaskService
    participant Titles as AgentTaskTitleService
    participant Worktree as WorktreeService
    participant Resources as AgentResourceService
    participant Drivers as AgentDriverRegistry
    participant Driver as AgentDriver
    participant UoW as AgentUnitOfWork
    participant TaskStore as AgentTaskStore
    participant Outbox as AgentEventOutboxStore
    participant Events as AgentEventService
    participant Sink as AgentEventSink
    participant Turns as AgentTurnService
    participant TurnStore as AgentTurnStore
    participant ApprovalStore
    participant Session as AgentSession
    participant TitleSession as Disposable title session
    participant Run as RunningTurn
    participant Metrics as AgentMetricsService
    participant Approvals as AgentApprovalService
    participant Logs as TaskLogStore
    participant Completion as AgentCompletionService

    User->>Desktop: Create task with first message
    Desktop->>Web: POST /agent/tasks
    Web->>Creator: create(taskId, workspaceId, message, lane, defaults, key)
    Creator->>Tasks: createInteractive(CreateInteractiveAgentTaskCommand)
    Tasks->>Drivers: require(laneId) + probe()
    Tasks->>Worktree: provision(ProvisionWorktreeCommand)
    Worktree-->>Tasks: TaskWorktree
    Tasks->>Resources: allocate(taskId)
    Tasks->>UoW: persist OPEN + PROVISIONAL fallback
    Tasks-->>Creator: AgentTask(OPEN)
    Creator-->>Web: AgentTask(OPEN)
    Web->>Turns: start(trusted initial message)
    Turns-->>Web: initial AgentTurn
    Web->>Tasks: get(taskId)
    Tasks-->>Web: response snapshot
    Web->>Titles: schedule generate(task, initialTurnId, firstMessage)
    Web-->>Desktop: 201 Created
    Desktop-->>User: Task created

    par Background naming
        Titles->>Resources: require(taskId) + create task-owned scratch
        Titles->>Driver: open(text-only, isolated, no tools or task-worktree write)
        Driver-->>Titles: TitleSession
        Titles->>TitleSession: start(first-message naming prompt)
        TitleSession-->>Titles: short title or failure
        Titles->>UoW: update PROVISIONAL title + insert TITLE_UPDATED
        Titles->>Events: drain(titleUpdatedEventId)
    and Initial turn
        Note over Turns,Session: Runs through the same turn lifecycle below
    end

    User->>Desktop: Open task
    Desktop->>Web: GET /agent/tasks/{taskId}/stream?after=0
    Web->>Events: subscribe(taskId, 0)
    Events-->>Web: replay followed by live events

    loop Each user turn in the same task
        User->>Desktop: Send message
        Desktop->>Web: POST /agent/tasks/{taskId}/turns
        Web->>Turns: start(StartAgentTurnCommand)
        Turns->>TurnStore: findByIdempotencyKey(taskId, key)
        Turns->>TurnStore: findActive(taskId)
        Turns->>Drivers: require(task.laneId())
        Drivers-->>Turns: AgentDriver
        Turns->>Driver: probe()
        Driver-->>Turns: AgentProviderStatus
        Turns->>Resources: writeBlob(boundedUserMessage)
        Turns->>UoW: execute(reserve accepted turn + claim)
        UoW->>TaskStore: claimWorktreeMutation(TURN)
        UoW->>TurnStore: insert(acceptedTurn)
        UoW->>Outbox: insert(userMessageBlobRefEvent)
        UoW-->>Turns: committed
        Turns->>Events: drain(userMessageEventId)
        Events->>Outbox: find(eventId)
        Events->>Logs: findSequence or append(envelope)
        Logs-->>Events: flushed
        Events->>Outbox: markAppended(eventId, sequence)
        Events-->>Turns: persisted envelope
        Turns->>Driver: open(sessionContext, eventSink)
        Driver-->>Turns: AgentSession
        Turns->>Session: reference()
        Session-->>Turns: AgentSessionRef
        Turns->>TaskStore: update(taskWithSessionRef)
        Turns->>Metrics: beginTurn(taskId, turnId)
        Turns->>TurnStore: update(acceptedTurn.startedAt)
        Turns->>Session: start(AgentTurnCommand)
        Session-->>Turns: RunningTurn
        Turns->>Run: reference()
        Run-->>Turns: ProviderTurnRef
        Turns->>TurnStore: update(runningTurn)
        Turns-->>Web: AgentTurn(RUNNING)

        loop Provider output
            Session->>Sink: emit(providerEvent)
            alt Display-only message/item event
                Sink->>Events: append(unsequencedEvent)
                Events->>Logs: append(envelope)
                Logs-->>Events: flushed
                Events-->>Web: publish persisted envelope
                Web-->>Desktop: typed SSE envelope
            else Usage event with durable projection
                Sink->>UoW: execute(metrics + usage event)
                UoW->>Metrics: record(taskId, turnId, usageSample)
                UoW->>Outbox: insert(usageEvent)
                Sink->>Events: drain(usageEventId)
                Events-->>Web: persisted usage event
            end
            opt Approval event
                Session->>Approvals: request(request, timeout)
                Approvals->>UoW: insert approval + requested event
                UoW->>ApprovalStore: insert(pendingApproval)
                UoW->>Outbox: insert(approvalRequested)
                Approvals->>Events: drain(approvalRequestedEventId)
                Events-->>Web: persisted approval event
                Web-->>Desktop: approval card event
                User->>Desktop: Allow or deny
                Desktop->>Web: POST /agent/approvals/{approvalId}
                Web->>Approvals: resolve(ResolveApprovalCommand)
                Approvals->>UoW: update approval + resolved event
                UoW->>ApprovalStore: resolve(approval, response)
                UoW->>Outbox: insert(approvalResolved)
                Approvals->>Events: drain(approvalResolvedEventId)
                Approvals-->>Session: persisted ApprovalResponse
                Approvals-->>Web: ApprovalResolution
            end
        end

        Turns->>Run: completion()
        Run-->>Turns: AgentTurnResult (provider barrier quiescent)
        Turns->>Approvals: denyPending(turnId)
        Turns->>UoW: execute(verify version + terminal state/event)
        UoW->>Metrics: finishTurn(turnId, terminalState)
        UoW->>TurnStore: update(terminalTurn)
        UoW->>TaskStore: releaseWorktreeMutation(TURN)
        UoW->>Outbox: insert(turnTerminalEvent)
        Turns->>Events: drain(turnTerminalEventId)
        Turns-->>Web: terminal turn state and metrics
    end

    User->>Desktop: Complete task
    Desktop->>Web: POST /agent/tasks/{taskId}/complete?workspaceId=...
    Web->>Web: getInteractive(workspaceId, taskId)
    Web->>Completion: complete(CompleteAgentTaskCommand)
    Completion->>TurnStore: findActive(taskId)
    Completion->>UoW: execute(claim COMPLETION + event)
    UoW->>TaskStore: claimWorktreeMutation(COMPLETION)
    UoW->>Outbox: insert(taskClosingEvent)
    Completion->>Events: drain(taskClosingEventId)
    Completion->>Session: close()
    Completion->>Worktree: inspect(task.worktree)
    Completion->>TurnStore: list(taskId)
    Completion->>Metrics: taskTotals(taskId)
    Completion->>UoW: execute(complete task + event)
    UoW->>TaskStore: update(completedTaskWithSummaryDeleteAfterAndNoClaim)
    UoW->>Outbox: insert(taskCompletedEvent)
    Completion->>Events: drain(taskCompletedEventId)
    Completion-->>Web: TaskCompletion
    Web->>Completion: cleanupWorktree(taskId)
    Completion->>Worktree: cleanup(task.worktree)
    Web-->>Desktop: task closed; logs retained for seven days
    Desktop-->>User: Completed
```

Every `UoW` group above is one SQLite transaction and every `drain` occurs
after that transaction commits. The diagram expands representative writes;
turn-delivery, queue, cancellation, and timeout transitions use the same
mutation-plus-outbox rule. Provider calls, JSONL flushes,
and SSE publication never run inside the SQLite transaction.

After the seven-day deadline, the retention job calls
`AgentCompletionService.cleanup(TaskId)`. Cleanup first drains every committed
task outbox event, then retries verified worktree removal before
`AgentResourceService.delete(TaskId)` and
`AgentTaskStore.update(...)` records `DELETED`. A cleanup failure leaves the
terminal task and summary unchanged, records `RETRY_PENDING`, and retries the
same method later. Generic retention does not select a managed workflow-policy
task until its policy monitor has recorded `worktreeRemovedAt`; interactive
tasks and legacy tasks without managed worktree facts remain independently
eligible.

Workflow success paths preserve the crash barrier `task terminal -> workflow
FINISHED -> worktree cleanup`. A workflow may persist cancellation intent
before task quiescence, but it never cleans the worktree until the task is
terminal. Physical removal records `worktreeRemovedAt` only after it succeeds.
Each workflow monitor selects terminal tasks without that fact for its
permission policy, matches them to its own durable `FINISHED` row, and
only then applies its batch limit, so an absent or nonterminal workflow row at
the head cannot starve a later finished workflow. It re-enters idempotent task
completion when needed to drain a committed terminal event, followed by the
idempotent cleanup. Failed completion or cleanup attempts
advance the task's existing `updatedAt`; monitors require that timestamp to be
at least one minute old before selecting it again, preventing an unsafe
worktree from hot-looping or starving later candidates. Startup does not
globally remove terminal task worktrees, because a workflow may have crashed
before its own terminal write.

The cancellation branch calls `AgentTurnService.cancel(TurnId)`,
`RunningTurn.cancel()`, `AgentApprovalService.denyPending(TurnId)`, and
`AgentMetricsService.finishTurn(TurnId, terminalState)` in order.

If creation fails after resource allocation,
`AgentTaskService.create(CreateAgentTaskCommand)` calls
`AgentResourceService.delete(TaskId)` and
`WorktreeService.cleanup(taskWorktree)` before returning the error. Cleanup
revalidates ownership and removes only a clean worktree without force. If it
cannot finish, the path and branch are preserved and the failure is reported;
startup does not retry or reconcile it automatically.

## 8. Provider implementations

The two drivers are the provider-format normalization layer. They share process
supervision but not wire DTOs or parsing: `CodexCliDriver` consumes App Server
JSON-RPC and `ClaudeCliDriver` consumes Claude `stream-json`. Each maps raw
provider messages to `ProviderAgentEvent` before calling `AgentEventSink`; raw
provider JSON never reaches `agent-core`, `TaskLogStore`, SQLite, or SSE.

### 8.1 CodexCliDriver

The Codex lane uses the documented
[Codex App Server protocol](https://learn.chatgpt.com/docs/app-server),
not terminal-screen scraping and not `codex exec --json`. ByteQuay starts one
task-owned process per active Codex turn, never more than one for a task, rooted
at that task's managed worktree:

```text
codex app-server --listen stdio://
```

Stdout is newline-delimited JSON-RPC and must never be merged with stderr.
Stderr is a bounded diagnostic stream. The WebSocket transport is experimental
and is not used by the localhost sidecar.

The connection and task sequence is:

1. Send `initialize` once with ByteQuay `clientInfo` and
   `capabilities.experimentalApi: true`, wait for its response, then send the
   `initialized` notification. This opt-in exists solely for the
   background-terminal inventory and termination used by quiescence and
   cancellation; the removed environment family and every other experimental
   method remain outside the product contract. No other request is legal before
   this handshake finishes.
2. Populate the existing ByteQuay model settings catalog from `model/list`,
   including supported reasoning efforts, input modalities, service tiers, and
   `multiAgentVersion`. Production accepts the running catalog without a
   version allowlist. A required incompatible shape fails the affected operation
   and includes the detected Codex version in its diagnostic.
3. Query `model/list` under the CP1 no-child isolation profile before a task
   thread exists, resolve the accepted turn's selected model, and launch the
   task process with exactly the child profile that model advertises. V1 enables
   only `multi_agent` and fixes `agents.max_depth=2`; V2 enables only
   `multi_agent_v2`; a model advertising no child support stays on the no-child
   profile. An unknown advertised version makes that model unavailable rather
   than silently selecting another graph protocol. Every profile explicitly
   disables apps, plugins, hooks, host skill discovery, web search, the other
   multi-agent feature, and all other unapproved optional capabilities, and
   applies the same minimal command environment. The separate
   `--live-children` fixture proved the bounded root/child/grandchild V1/V2
   contract with a persistent fixture profile signed in directly with ChatGPT;
   production now applies that model-advertised profile at each turn boundary.
   Before creating a thread, call `config/read(includeLayers: true)` and require
   exactly one `sessionFlags` layer with the same effective feature and shell
   policy, an empty MCP-server map, and no inherited shell setters, exclusions,
   or filters. `hooks/list` must be empty and error-free. `skills/list` may
   return only bundled system skills canonically beneath the isolated
   `CODEX_HOME/skills/.system`, with no plugin owner; names and count are not a
   contract. Use `forceReload: true` and repeat the same origin/error guard
   immediately before every `turn/start`, including the first turn after resume
   or restart. Build a matching per-thread config override that
   supplies no dynamic tools or capability roots and cannot be widened by
   project/user config. For every task cwd, require the isolated user layer,
   reject every project layer, and scan the `rules/` directory beside each
   returned system, user, or project policy-bearing config source.
   `packagedDefaults` and `legacyManagedConfigTomlFromFile` layers are not
   exec-policy sources. Any `.rules` file, unreadable source, or unknown layer
   shape makes the lane unavailable. This source guard is required because
   v0.151 App Server has no public
   `--ignore-rules` or rule-inventory API. Also call
   `configRequirements/read` and require no applicable requirements. A
   missing `requirements` field, non-null value, request failure, or undecodable
   response makes the lane unavailable. This security-specific response must
   contain exactly that one top-level key; an unknown sibling policy field also
   fails closed. Supporting a broader shape later requires new exact executable
   evidence.
4. Call `thread/start` with canonical `cwd`, the selected default model,
   the compatibility-tested wire value for on-request approval
   (`approvalPolicy: "on-request"` in the v0.151 reference schema),
   `approvalsReviewer: "user"`, the generated per-thread config override, and
   legacy `sandbox: "workspace-write"`. No `environments` are selected: the
   process default is rooted at the process working directory, which is this
   task's worktree. After start, page
   `mcpServerStatus/list(threadId)`, app/plugin status, and `hooks/list`; every
   external tool/hook must be absent or disabled before the session is usable.
   Persist the returned `thread.id` as the root provider value in
   `AgentSessionRef`.
5. For every accepted turn, resolve that turn's immutable model snapshot and
   launch a new per-turn App Server in the same worktree under the matching
   child profile. A stale idle session must first close with a verified process
   stop. Start a new thread when no session reference exists; otherwise call
   `thread/resume` with the stored `threadId`, the same config and sandbox ceiling, and
   `approvalsReviewer: "user"`. Repeat the
   effective-capability checks and never infer a session ID from provider
   files. Relaunching at the turn boundary is deliberate: changing the task
   default between turns cannot reuse a process configured for the previous
   model's V1/V2 protocol.
6. Start each user turn with `turn/start`. Its `input` is built only from the
   already-persisted user event: text plus model-gated
   `{"type": "localImage", "path": "<validated-task-blob>"}` items. App Server's `image`
   input is URL-based and is outside the first local-upload release. Apply the
   turn's snapshotted model, reasoning effort, service tier, `approvalPolicy`,
   `approvalsReviewer: "user"`, canonical `cwd`, and structured
   `sandboxPolicy` with `type: "workspaceWrite"` and
   `writableRoots: [canonicalWorktree]`, with tool network disabled. The
   thread's persisted
   `selectedCapabilityRoots` remains empty. Store the returned `turn.id` as the
   `ProviderTurnRef`. Never add
   `.git`, a worktree pointer target, or the resolved Git directory as an extra
   writable root.
8. Send immediate follow-up input with `turn/steer` only when the request names
   the current `threadId` and expected active `turnId`. Steering adds input to
   that run; it cannot change model, effort, approval, or sandbox settings and
   does not create a ByteQuay turn.
9. Keep reading notifications for the root and every known child.
   `turn/completed` is the root terminal fact, but it only begins recursive,
   paginated `thread/items/list` closure over the reachable V1 or V2 child set.
   Every observed execution-capable item must be terminal, and paginated
   `thread/backgroundTerminals/list` is empty for the root and every child.
   Each newly discovered child is listed in the same fixed point, so V1 and V2
   grandchildren are included even when their live notification was missed.
   Item history carries no descendant turn status; after the fixed point, close
   the per-turn App Server and require its whole owned process tree to be
   `VERIFIED_STOPPED` before reporting `providerQuiescent` and releasing the
   claim.
   Treat `item/completed` as authoritative when a streamed delta differs from
   the final item.
10. Cancel with `turn/interrupt`, call
   `thread/backgroundTerminals/clean` for the root and every known child, and
   repeat list/history reconciliation until the same empty/terminal barrier
   passes. A targeted stop may call `thread/backgroundTerminals/terminate`
   only with a `processId` returned by the matching thread's list response.
   Cancellation and failed reconciliation both close the per-turn process; an
   unverifiable stop retains the worktree claim. When ending the per-turn
   session, unsubscribe its root and every known subscribed child before
   process shutdown. Later ByteQuay task completion does not call
   `thread/archive` or `thread/delete`, because provider-owned history is
   retained and no turn process remains live.

No documented stable App Server API provides ByteQuay's durable next-turn
queue. Queue records stay in SQLite and become ordinary `turn/start` calls only
after `AgentTurnService` claims them.

Task defaults and each accepted or queued turn retain `serviceTier` separately
from effort. The existing model catalog exposes each tier's ID, name, and
description. Creation and defaults updates validate model, effort, and tier
against that catalog; a provider without tiers cannot enable Fast. The Codex
adapter sends `serviceTierForTurn` with the selected opaque tier ID, or
`"default"` for standard speed, on every `turn/start`. Explicit standard speed
prevents a resumed thread from retaining Fast from an earlier turn. This field
is verified against both the v0.151.0 reference and installed v0.152.1 App Server
schemas. No ambient Codex
configuration is edited to toggle speed, and reasoning effort is unchanged.

The driver normalizes these App Server events:

| App Server event | ByteQuay event |
| --- | --- |
| `item/agentMessage/delta` and completed `agentMessage` | Partial and final assistant message |
| completed reasoning items and supported summary deltas | Readable reasoning summary only |
| `turn/plan/updated` | Structured checklist steps and status |
| `item/plan/delta` and completed `plan` | Separate free-form Plan-mode text; completed item is authoritative |
| `item/started` and `item/completed` for `commandExecution` | Command start and terminal result |
| `item/commandExecution/outputDelta` | Incremental stdout/stderr output |
| `fileChange` items and `turn/diff/updated` | File change and aggregated diff |
| `mcpToolCall` and experimental `dynamicToolCall` items | Disabled by the base agent release; the separately gated CodeGraph bridge enables and normalizes only its one fixed optional tool |
| `webSearch` and `imageView` items | Search/view activity and terminal result |
| synchronous `hook/started` and `hook/completed` notifications | Reserved for a future approved profile; the first-release strict profile requires `hooks/list` to be empty and advertises no Codex hook lifecycle |
| V1 `collabAgentToolCall` with `tool: "spawnAgent"`, `senderThreadId`, `receiverThreadIds[]`, and `agentsStates` | Child discovery and direct parent relation; other collaboration tools update activity but never manufacture a parent/child edge; child content comes from child history |
| Multi-Agent V2 `subAgentActivity` with `agentThreadId`/`agentPath` | Selected-profile `started` activity supplies V2 child discovery; every kind supplies a canonical absolute logical path (not a filesystem path), lifecycle, and routing only for identities already resolved by that selected graph |
| command, file-change, and permission approval server requests | Typed approval request; the later ByteQuay resolution is a separate event |
| `serverRequest/resolved` | Terminal resolution/cancellation of the matching durable request |
| `thread/tokenUsage/updated` | Usage update normalized according to the adapter's known schema; cumulative semantics are not assumed generically |
| `contextCompaction` item lifecycle | Visible compaction activity without hidden context content; deprecated `thread/compacted` is not used |
| `error` and failed `turn/completed` | Error and terminal failure |

ByteQuay appends the user message before calling `turn/start` or `turn/steer`.
If App Server later echoes an equivalent user item, the driver correlates it
without appending duplicate transcript text.

Every accepted task-relevant event is resolved to a source through its
thread/turn/item fields and the driver's registry; process-global account,
configuration, and warning notifications remain process-scoped. A V1
`collabAgentToolCall` registers an edge only for `tool: "spawnAgent"`, using
each receiver thread as a direct child of the sender; a V2 `subAgentActivity`
uses its canonical absolute logical `agentPath`. App Server may retain both
known item forms in one transcript. The selected model's advertised version is
the only graph-discovery source; the other form is supplementary activity whose
thread identities still must resolve inside that same graph. A live
`thread/started` notification may establish lineage only when it carries an
explicit `parentThreadId`; a missing parent is never inferred to mean the root.
For V2, the root path is `/`, and every activity path is absolute, normalized,
and stable for its thread. A selected-profile `started` edge must supply a path
distinct from and strictly nested below its direct parent's already known path,
including for root children and grandchildren. `thread/started` may bind
explicit lineage metadata, but the driver does not route or announce that V2
child until its canonical `started` path exists. `interacted` and other
supplementary activity validate stable paths and already-resolved identities;
they never create or reparent a child. Once the direct parent and, for V2, the
canonical path are known, routing begins before the discovery card is published.
A selected model whose advertised
`multiAgentVersion` is not implemented is unavailable rather than silently
dropping child activity. Paginated `thread/items/list` backfills a known child
after reconnect; backfilled items pass through the same normalizer and
source-key deduplication before append. They are appended in provider history
order inside explicit backfill-started/completed lifecycle markers; the task
sequence still records ByteQuay receipt order. `thread/items/list` is a current
non-experimental method on the supported App Server line. The unimplemented
`thread/read(includeTurns: true)` method and experimental parent/ancestor list
filters are not correctness dependencies.

There is no assumed App Server `quiesce` method. The Codex driver builds the
pre-stop fixed point by paging `thread/items/list` for the root, recursively paging
every V1/V2 child found in live events or item history, and repeating until a
final pass discovers no new thread IDs and every listed execution-capable item
is terminal. V1 discovery accepts only `spawnAgent` relationships from
`collabAgentToolCall`; V2 discovery accepts only `started` relationships from
`subAgentActivity`, while upward/interacted activity never manufactures an
edge. In parallel, an item registry keyed by thread/turn/item reconciles every
execution-capable item to terminal history and the driver pages
`thread/backgroundTerminals/list` for every root/descendant until all pages are
empty. This combined fixed point closes missed-live-notification,
nested-grandchild, root-answer-before-command-exit, and visible
yielded-terminal gaps, but it does not expose descendant turn status and is not
the final quiescence proof. The driver then closes the per-turn App Server and
requires its complete owned process tree to be `VERIFIED_STOPPED`. This final
fact also covers a process no longer represented by item history; an
unverifiable tree retains the claim. All Codex lifecycle hooks are disabled for
first-release tasks; any effective hook makes task start fail. Independently,
any applicable or uninspectable managed requirements fail readiness. A
read/list error, active item/background terminal, or unstable closure marks the
turn failed and triggers process shutdown. The failed turn is still quiescent
when shutdown proves `VERIFIED_STOPPED`; otherwise the `OPEN` task remains
blocked with the recovery-required projection and retained claim. No other task
shares that process.

The task uses the running App Server's selected sandbox and approval policy.
Readiness verifies that ordinary built-in tool execution works and stays inside
the task worktree. The
App Server control process still needs provider traffic; ByteQuay supplies the
selected provider configuration but does not claim that the command sandbox or
first-release process profile enforces a destination allowlist for that control
traffic. The driver sets `approvalsReviewer: "user"` at start, resume, and turn
start so approval requests remain visible to ByteQuay. ByteQuay adds no general
custom tool router, semantic Git-command policy, or post-turn Git workflow in
this phase. The separately gated CodeGraph bridge remains limited to its one
fixed structural tool.

Each task connection maintains its own root-thread, active-turn, and
known-child registry. A task event that arrives for an unknown child is held in
a small bounded buffer until collaboration or thread session metadata binds
it; expiry becomes a provider diagnostic rather than a guessed source. A
server request is answered only after that task-local registry resolves its
source. Unmatched, late, or unsupported requests receive a fail-closed
cancel/deny response and never appear as another task's approval.

Readable reasoning summaries may be exposed as progress when the provider emits
them. Raw reasoning text is not persisted or shown, even if a model or protocol
version emits `item/reasoning/textDelta`; hidden chain-of-thought remains outside
the ByteQuay contract.

Reference protocol metadata is generated with the selected compatibility
binary:

```text
codex app-server generate-json-schema --experimental --out <schema-directory>
```

The fixture does not commit the 413 schema files or 820 generated
TypeScript files. It derives one compact committed JSON projection containing
only the record components, wire names, required/null rules, samples, and enum
values consumed by ByteQuay's Java protocol adapter. The normal command

```text
node scripts/codex-app-server-compat.mjs
```

regenerates the exact v0.151.0 schemas in a temporary directory and requires
that derived projection to equal the committed manifest. An intentional
reference update uses

```text
node scripts/codex-app-server-compat.mjs --update-bindings
```

The `agent-cli` conformance test reflects over the projected Java records and
decodes or encodes generated samples to enforce every consumed component, wire
name, required-field rule, required non-null value, and known enum value.
Optional properties deliberately decode explicit null as absent; runtime
mapping separately rejects security-sensitive approval shapes it cannot
represent. Additive object fields remain decodable, and each projected enum
uses an `UNKNOWN` default for future wire values. The compact manifest is the
authoritative reference projection; it is not a second public domain model or
a generated production client.

The [v0.151.0 compatibility fixture](codex-app-server-compat.md)
regenerates stable and experimental schemas, verifies their bundle hashes,
required surface, and compact Java projection. Its recorded live slice proves
the superseded environment/exec-server boundary rather than the current
production task-process topology, so CP11 uses it only as protocol and
configuration evidence. That historical fixture covered two independent root
tasks and three turns. Both subscription-backed paths passed locally on
2026-08-31 with `gpt-5.6-sol`; the recursive run used its advertised V2 graph
while validating a supplementary V1 collaboration record. The default fixture
remains offline, authentication-free, deterministic, and safe for CI. The `--live` and
`--live-children` paths are explicit local real-model smoke gates and are not
invoked by the normal test or CI command. Each requires
`BYTEQUAY_CODEX_COMPAT_HOME` to point to a separate persistent Codex profile
outside the repository that was signed in directly with ChatGPT. It must never
use the ambient Codex profile or seed a profile by copying `auth.json`. App
Server verifies through `account/read` that `requiresOpenaiAuth` is true and
`account.type` is `chatgpt`; API-key fixture support is deferred. App Server
receives that selected `CODEX_HOME` only for its own
process and uses a temporary `CODEX_SQLITE_HOME` for database state. A
profile-local exclusive lock prevents concurrent live gates from sharing the
rotating login; it is acquired before mutable auth/runtime validation. The
fixture rejects persistent Codex-home instruction files. Run-scoped rollout,
writer-lock, shell-snapshot, and trust records that App Server places in the
fixture-only profile are removed only after every owned process group stops;
`auth.json` and the profile remain.

Every model-requested tool process instead receives a
minimal allowlisted, credential-free environment; both live gates proved that model-requested
commands receive no `CODEX_API_KEY`, `OPENAI_API_KEY`, `CODEX_HOME`, or App
Server-only sentinel. A bounded Git-worktree action and credential isolation,
one representative live command approval, yielded-terminal termination,
recursive children, restart recovery, and fail-closed cross-task routing
satisfy the CP1 technical evidence gates. The offline projection and codec
tests already pin the modern approval methods,
top-level fields, requiredness, command/file decision variants, and opaque
permission payload/scope, and prove legacy approval methods fail closed; they
do not prove the representative live callback. Richer product decision
matrices begin in CP2 only after the immutable safety ceiling is defined. The
auth-free offline gate also validates a real isolated-user `allow` rule,
observes its active user layer through App Server,
proves the ByteQuay source guard rejects it, and accepts a clean isolated
workspace only after every tested task cwd has each returned system, user, or
project policy-bearing config folder free of `.rules`. It separately pins
`configRequirements/read` and proves its `requirements` field is present and
null. The same keyless App Server run proves the no-child session flags and
shell environment, empty MCP and hook configuration, and isolated
bundled-system-skill origin before creating a thread. Synthetic poison cases
exercise the rejection branches. The separate CP1 child-capable proof passed,
and production now resolves the accepted turn's model from the catalog,
launches exactly its advertised V1 or V2 profile, and recursively discovers the
corresponding relationship items with `thread/items/list`. The fixture's opt-in
`--live-children` path selected the model-advertised profile and passed its
subscription-backed local smoke run with the explicitly selected ChatGPT
profile. That paragraph records a historical CP0-through-CP3 milestone: the
static UI was then behind a development-only gate while CP4 still owned Codex
task isolation and quiescent recovery. CP4 and the later rich-task checkpoints
are implemented, CP11 passed, and the shipped surface is ungated.

That exact fixture and its projection are reference-only and never form a
production version allowlist. The driver probe runs `codex --version` for
display and diagnostics, not acceptance: no reported version text, whether
older, newer, prerelease, or unparseable, disables the lane. The startup update
is only a minimum-version floor; newer or unparseable versions are left alone.
Production decoding keeps the same additive-field and
future-enum tolerance checked by the conformance test. If a real request,
response, or required event is incompatible, ByteQuay fails that operation
without silently dropping data and includes the detected version text in the
actionable issue-report diagnostic. When the reference schema supplies a stable
notification key, the driver may use it to deduplicate usage updates; App
Server notifications do not otherwise guarantee a JSON-RPC event ID. In every
case, ByteQuay's own persisted `EventId` and task sequence make replay
idempotent, and the driver follows only the counter semantics proven by the
fixtures. It subtracts the baseline captured by
`AgentMetricsService.beginTurn(...)` only when that schema explicitly defines
the counters as cumulative; otherwise it normalizes snapshots or deltas
defensively without double counting.

If a task's App Server disconnects, that task's live turn becomes
`INTERRUPTED`. ByteQuay may restart and initialize the task-owned process, but
it resumes the thread and starts a new turn only after an explicit user retry.

### 8.2 ClaudeCliDriver

The Claude lane follows the official
[Claude Code CLI](https://code.claude.com/docs/en/cli-usage) and
[programmatic streaming](https://code.claude.com/docs/en/headless) contracts.
One process runs per ByteQuay turn. The full-fidelity argv for a new session is:

```text
claude
  --print
  --output-format stream-json
  --verbose
  --include-partial-messages
  --include-hook-events
  --forward-subagent-text
  --model <effective-model>
  --effort <effective-effort>
  --strict-mcp-config
  --setting-sources=
  --settings <task-provider-directory>/claude-sandbox-settings.json
  --permission-mode manual
  --mcp-config <task-provider-directory>/bytequay-mcp.json
  --permission-prompt-tool mcp__bytequay__permission_prompt
  --session-id <generated-claude-session-id>
```

`ProcessSupervisor` supplies this as an argv list without a shell. It writes the
UTF-8 user message to stdin and then closes stdin; prompts are not placed in the
process list or interpolated into a command string. The process working
directory is the task's canonical generated worktree.

For a turn with text attachments, the driver canonicalizes each file and
requires it to remain inside that task's runtime directory. It gives Claude the
attachment parent directory with `--add-dir` and grants an exact `Read` rule for
each file with `--allowedTools`. The prompt contains only the attachment name
and canonical path; ByteQuay does not copy the file contents into the prompt.
The initial Claude implementation advertises `text/plain` attachments, which is
how CI Autofix exposes its bounded raw job logs.

ByteQuay generates the first session ID so the provider reference can be
persisted before the process starts, then verifies that Claude emits the same
ID in its initialization and result messages. For every later turn, replace
`--session-id` with the stored session ID in `--resume`:

```text
--resume <claude-session-id>
```

The resumed invocation still carries the effective `--model` and `--effort`
snapshot selected for that ByteQuay turn; resume changes conversation identity,
not the per-turn settings guarantee.

Use the exact stored ID rather than `--continue`, because multiple ByteQuay
tasks can share a worktree over time. Do not pass `--fork-session` because a
ByteQuay task continues the same provider conversation. Capture `session_id`
from the initialization/result stream before reporting the first turn as
resumable.

The output flags have distinct purposes:

| Flag | Required behavior |
| --- | --- |
| `--output-format stream-json` | Emits one structured JSON object per line instead of terminal UI text |
| `--verbose` | Emits the complete turn-by-turn structured stream rather than only the compact result |
| `--include-partial-messages` | Adds raw streaming events for partial assistant text and tool-input deltas |
| `--include-hook-events` | Adds hook start, progress, output, and response events when hooks run |
| `--forward-subagent-text` | Adds forwarded text with `parent_tool_use_id`; this is retained as provider activity context, but is not a direct thread edge and therefore does not by itself enable the nested-agent capability |
| `--strict-mcp-config` | Loads only the task-scoped MCP servers named by ByteQuay |
| `--setting-sources=` | Disables inherited user/project setting sources for the agent process |
| `--settings <file>` | Applies the generated task sandbox and deny-write boundary |

`--include-partial-messages` produces both deltas and later completed assistant
messages. For each active content block, the driver assigns one provider item
identity, emits deltas without a terminal provider-event identity, and emits the
completed block as `COMMENTARY` with that same item identity. Completing one
block removes only that block's `(parent_tool_use_id, content index)`
correlation. The exact key wins even when a provider completion rewrites the
text. When Claude omits or reindexes a hidden content block in the completed
message, the driver instead reuses a stream identity only when exactly one
accumulated stream under the same `parent_tool_use_id` has the completed block's
exact full text. It never guesses between matches or across a forwarded child,
so an interleaved child completion cannot discard the root stream's identity.
The frontend therefore replaces that block's live text instead of appending the
completion. On success, the authoritative `result` response becomes the turn's
`ANSWER`/`FINAL` event under the latest root text item identity. It replaces
only that latest root commentary while preserving earlier and tool-adjacent
commentary. The terminal result clears all remaining per-turn text and tool
correlations. The driver keeps reading stdout through EOF even after a `result`
object because trailing system events can follow the result.

For Claude, the `result` object is the root terminal fact; the driver completes
`RunningTurn.completion()` only after forwarded child/background-task terminal
events and supervised process-tree exit. Forced cancellation also waits for
that exit, so a background child cannot outlive the task's
worktree-mutation claim.

Claude Code 2.1.260 may leave an ignored empty `.claude/.cc-writes/` staging
directory in the worktree, the provider behavior documented in
[anthropics/claude-code#79945](https://github.com/anthropics/claude-code/issues/79945).
After the supervised process tree is `VERIFIED_STOPPED` and before completing
the running turn, the Claude driver removes only that exact directory when it
is a non-symlink and empty, then removes `.claude` only when it too is a
non-symlink and empty. Files, symlinks, and nonempty directories are never
removed. Failure to remove an eligible empty scratch directory fails the turn
with an actionable diagnostic and retains the worktree; the shared worktree
service remains fail-closed over ignored entries.

The driver normalizes at least these message classes:

| Claude stream message | ByteQuay event |
| --- | --- |
| `system/init` | Session reference, CLI/model/tool capability facts |
| `stream_event` text deltas | Partial assistant `COMMENTARY` |
| `assistant` content blocks | Completed `COMMENTARY` plus `tool_use` starts |
| `user` content containing `tool_result` | Tool result and bounded command output |
| `tool_progress` | Running-tool elapsed-time update; a tracked Bash call remains a typed `Command` with the same item identity |
| `system/hook_started`, `hook_progress`, and `hook_response` | Hook lifecycle and bounded output |
| `system/task_started`, `task_progress`, and `task_notification` | Subagent/background-task progress |
| `system/api_retry` and rate-limit messages | Retry/rate-limit progress |
| `result` | Terminal `ANSWER`/status, response, duration, usage, session ID, and cost estimate |

Forwarded messages and task/tool activity may retain `parent_tool_use_id` inside
the adapter while they are correlated, but that value names a tool invocation,
not a direct provider thread and parent thread. The current Claude lane must not
manufacture a `ProviderThreadRef` or a nested-agent panel from it; it advertises
`nestedAgentActivity: false` and renders the supported activity in the root
transcript. Raw thinking blocks are discarded; only provider-supported
user-facing messages, tool activity, and progress summaries enter the ByteQuay
log. Hook and tool output is bounded and redacted before persistence.

Headless manual approval needs a callback path; a terminal dialog is not a
frontend integration. ByteQuay writes an owner-only MCP configuration under the
task's provider directory and points `--permission-prompt-tool` at a
`mcp__bytequay__permission_prompt` tool. That adapter delegates to
`AgentApprovalService.request(...)`, waits for the frontend decision, and
returns allow or deny to Claude. The task-scoped MCP endpoint uses an ephemeral
capability token stored in the owner-only config file. ByteQuay does not pass
the token on the command line.

The attended `AUTO` policy runs `--permission-mode manual`. Its generated
sandbox keeps `autoAllowBashIfSandboxed: true`, so ordinary sandboxed commands
run without a card, while an unsandboxed command or another tool permission is
routed through the ByteQuay permission-prompt tool for a direct user decision.
The driver readiness check requires the running CLI's `--help` output to
advertise the `manual` mode before the lane is usable. This fail-closed split is
required because Claude 2.1.260 `auto` mode may classify an explicitly requested
unsandboxed command as allowed without invoking the configured host prompt.

`CI_AUTOFIX` and `CHERRY_PICK` remain unattended and run
`--permission-mode auto`; the ByteQuay broker answers any escalation from their
fixed policy and never waits for a frontend decision. The interactive path
must not use Claude's auto-mode classifier as a substitute for ByteQuay's
durable approval card.

Containment does not depend on that answer. The generated settings apply the
selected task sandbox and workspace roots, and keep the task event log and
settings file outside the writable worktree. Claude's own provider traffic
remains a separate control-plane trust boundary and is not described as
destination-allowlisted by this sandbox. The permission-prompt MCP adapter is
only the provider's approval callback; it is not a model-callable ByteQuay
business tool and cannot perform product actions.

The driver never enables `--dangerously-skip-permissions`. It also does not use
`--bare`: bare mode does not read Claude subscription OAuth or keychain
credentials and therefore would break the supported no-API-key deployment.
`--debug` and `--debug-file` are troubleshooting options, not task-event
sources, and remain off by default.

The driver probe runs `claude --version` and checks each optional observability
flag. It also requires `--permission-mode` to list the `manual` token used by
attended tasks; a CLI that exposes the flag without that mode is unsupported.
`--forward-subagent-text` requires a sufficiently recent Claude Code version;
when unavailable, the driver omits it and reports subagent transcript streaming
as unsupported rather than failing the entire lane. Required streaming or
permission flags failing the probe make the lane unavailable.

The terminal `result` contains usage for that invocation and, when present,
`total_cost_usd`. Claude documents that monetary field as a client-side
estimate, not authoritative billing data; ByteQuay labels it as a CLI estimate
and never uses it for billing decisions. Cancellation sends graceful
termination, then forcefully terminates the process tree after the configured
grace period, and records the observed terminal result or process exit.

### 8.3 Capability differences

`AgentCapabilities` advertises event fidelity rather than one generic
"streaming" boolean:

| Capability | Codex App Server | Claude Code CLI |
| --- | --- | --- |
| Model catalog | `model/list`, mapped through existing settings models | Provider catalog when available, otherwise seeded settings models |
| Session resume | `thread/resume` with stored thread ID | New process with `--resume <session-id>` |
| Partial assistant text | Native `item/agentMessage/delta` | `stream_event` text deltas with `--include-partial-messages` |
| Structured checklist | Native `turn/plan/updated` steps/status | No guaranteed dedicated checklist; render provider messages/tool activity only |
| Free-form Plan-mode text | `item/plan/delta` plus authoritative completed `plan` | Provider messages when available, not synthesized as a checklist |
| Tool lifecycle | Typed item start/completion plus tool-specific records | `tool_use`, `tool_result`, and `tool_progress` messages |
| Incremental command output | Native `item/commandExecution/outputDelta` when emitted; missing deltas and a null terminal aggregate produce no invented log | Not guaranteed for Bash; terminal tool results are authoritative |
| Hook lifecycle | Not advertised in the first-release strict profile; `hooks/list` must be empty | Available with `--include-hook-events` |
| Nested agent activity | V1 `collabAgentToolCall` spawn edges and V2 `subAgentActivity` paths | Not advertised: `parent_tool_use_id` is a tool edge, not the direct thread lineage required by the canonical subagent graph; forwarded text remains root transcript activity |
| Interactive approvals | App Server JSON-RPC approval requests | ByteQuay MCP permission-prompt tool |
| Image input | Local uploads become model-gated `localImage {path}` inputs; URL `image` is deferred | Capability-gated; hidden when the current CLI/model cannot accept it |
| Text attachments | Provider input item when supported | Exact read-only paths inside the task runtime directory |
| Steering | Stable `turn/steer` against the expected active turn | Unsupported until a native safe equivalent exists |
| Token usage | `thread/tokenUsage/updated`, semantics normalized from the adapter's known schema | Per-step assistant usage and terminal invocation totals |
| Monetary cost | `UNAVAILABLE` unless a future protocol field supplies it | `CLI_ESTIMATE` from terminal `total_cost_usd` when present |
| Cancellation | `turn/interrupt` | Graceful then forced process-tree termination |

Capabilities also describe supported attachment types and capability-gated event
classes. The UI must render missing capabilities honestly rather than
synthesize data.

## 9. Event and frontend contract

One versioned ByteQuay event contract is canonical for JSONL, replay, SSE,
Electron IPC, and React. Codex wire DTOs, the compact reference projection, and
Claude JSON never become that contract.

Every envelope has:

| Field | Rule |
| --- | --- |
| `schemaVersion` | Reject unsupported major versions; additive fields are optional for legacy events |
| `eventId` | Globally unique stable ByteQuay identity assigned before outbox append and used for idempotent recovery |
| `sequence` | Assigned only by `AgentEventService`; monotonic across the whole task |
| `taskId` | Required |
| `turnId` | ByteQuay turn ID when the event belongs to a turn |
| `source` | Common source object containing the optional provider identities below |
| `source.providerThreadId` | Opaque root or child source when provider-thread scoped |
| `source.parentProviderThreadId` | Direct parent when this event establishes or describes a child |
| `source.logicalAgentPath` | Optional stable logical path reserved for recursive subagent projection; V2 uses absolute normalized paths, with `/` for the root and strict direct-parent nesting for descendants |
| `source.providerTurnId` | Opaque provider turn when supplied |
| `source.providerItemId` | Opaque item key for deltas and authoritative completion |
| `type` | Typed payload family listed below |
| `occurredAt` | Backend receipt time; used for ByteQuay timing |
| `source.providerOccurredAt` | Optional source timestamp; never required for ordering |
| `source.providerEventId` | Optional provider event key; ByteQuay's `eventId` remains authoritative |
| `payload` | Type-specific normalized data or a typed relative blob reference |

Required payload families are:

- user and assistant messages, including delivery kind/outcome and attachment
  refs (a failed steer receives an observable failed-delivery update);
- user-visible reasoning summaries, structured checklist updates, and separate
  free-form Plan-mode text;
- commands, file changes, built-in tool activity, web searches, and image views
  with typed lifecycle, bounded arguments/results, working directory, and
  duration when supplied;
- approval requested, resolved, and expired events with kind, requested scope,
  decision, source child, and resolution time;
- subagent discovered, completed, and failed events with direct parent and
  stable child thread identity; a child is running from the moment it is
  discovered, so discovery is its start rather than a separate event;
- backfill started and completed events bracketing activity recovered from a
  thread's history rather than seen live;
- usage, queue, turn/task lifecycle, and error events.

The renderer keys identified streamed activity by
`(source.providerThreadId, source.providerTurnId, source.providerItemId)`.
Deltas update the live item and `item/completed` replaces it authoritatively.
An unidentified display-only event remains a standalone root event rather than
receiving invented identity. Child state is reconstructed from relationship
and lifecycle events in task JSONL; no child-agent table is added initially.

Folded activity labels, icons, “Step 1 / 3,” expanded/collapsed state, and live
elapsed ticks are deterministic React projections. They are not written back
as a second event or summary model.

ByteQuay measures turn elapsed time from its accepted/running/terminal
timestamps. Item duration is shown only when the provider supplies enough
typed timestamps or duration data; the UI never invents a provider duration.

Stable localhost endpoints:

```text
POST /agent/tasks
GET  /agent/tasks/{taskId}
GET  /agent/tasks?workspaceId=...&limit=...
PATCH /agent/tasks/{taskId}/defaults
POST /agent/tasks/{taskId}/turns
GET  /agent/turns/{turnId}
POST /agent/turns/{turnId}/steer
POST /agent/turns/{turnId}/cancel
POST /agent/approvals/{approvalId}
POST /agent/tasks/{taskId}/attachments
GET  /agent/tasks/{taskId}/attachments/{attachmentId}
DELETE /agent/tasks/{taskId}/attachments/{attachmentId}
POST /agent/tasks/{taskId}/queue
GET  /agent/tasks/{taskId}/queue
DELETE /agent/tasks/{taskId}/queue/{queuedInputId}
POST /agent/tasks/{taskId}/queue/resume
POST /agent/tasks/{taskId}/mark-failed
POST /agent/tasks/{taskId}/complete?workspaceId=...
POST /agent/tasks/{taskId}/cleanup?workspaceId=...
GET  /agent/tasks/{taskId}/events?after=...&limit=...
GET  /agent/tasks/{taskId}/stream?after=...
GET  /agent/providers/status
GET  /api/settings/models
```

Both direct completion and direct cleanup are interactive workspace routes.
They resolve `getInteractive(workspaceId, taskId)` before mutation; CI Autofix
and cherry-pick close and clean their tasks only through their workflow
services after the workflow's own terminal barrier is durable.

`GET /api/settings/models` is the existing cached, cross-provider model
catalog; the agent surface reuses it rather than creating another registry.
Its model option is extended with input modalities from `ProviderModel`, so
image selection is gated per model. Task creation and turn/queue requests name
only catalogued model/effort values, carry the renderer-observed task version,
and snapshot the effective selection.

Deleting a queue row removes an untouched `QUEUED`/`PAUSED` input or records
an `UNCERTAIN` input as `ABANDONED`; it never erases a consumed `STARTED` row.

`POST /agent/tasks/{taskId}/mark-failed` is workspace-scoped and carries a
bounded user-visible reason. It maps only to
`AgentCompletionService.abandon(...)`, returns a conflict while a turn is
active, and closes and verifies any retained provider runtime before recording
`FAILED` and releasing the claim. The controller then requests normal safe
worktree cleanup unless a pre-restart process was unverifiable. Dirty or
unverifiable work remains untouched until the generated branch is clean and
safe to remove; the shared service has no force-removal operation.

The public task-creation DTO contains the client-generated `taskId`,
`workspaceId`, first `message`, immutable `laneId`, defaults, and an
`idempotencyKey`, not a title or filesystem path. The first message provides
the bounded provisional task label and the input to best-effort background
title generation. `app` resolves the managed repository and exact
starting commit, verifies the task belongs to that workspace, and only then
builds the core command. Trusted internal workflow callers such as cherry-pick
and CI Autofix supply their own already-frozen commit, but they use the same
`WorktreeService` and cannot submit an arbitrary path.

Task and completion responses may expose the safe generated branch name and
verified HEAD as local-result facts. They never expose its internal path or
perform a merge/push action.

Spring MVC controllers translate HTTP DTOs and errors only. SSE adapts the
core `Flow.Publisher`; controllers do not parse provider events or manage
processes. One task stream includes its root and every known child thread.

Electron main attaches the private desktop session header, uploads bytes from
the native file chooser, reads reconnecting SSE with the last sequence, and
forwards narrowed DTOs over typed preload IPC. The renderer never receives the
session token, raw provider JSON, or a local attachment path and never connects
directly to App Server or localhost.

## 10. Durable and disposable storage

### 10.1 SQLite

`agent_task` stores:

- task ID, owning workspace ID, title, lane ID, repository identity, canonical
  worktree, generated result branch, and exact starting commit;
- default model, reasoning effort, and permission policy;
- task lifecycle state, optimistic version, timestamps, terminal reason, and optional
  worktree-mutation claim kind/owner/acquired time;
- provider session reference and its contract version;
- token totals, cost amount when available, cost provenance, elapsed time, and
  provenance;
- final summary, Git/test facts, a compact terminal child index (thread and
  parent IDs, label, final state, elapsed time, and result summary only),
  successful worktree-removal time, runtime cleanup state, and runtime deletion
  deadline. A dirty terminal worktree is a derived cleanup-action projection,
  not another stored lifecycle state.

`agent_turn` stores:

- turn ID, task ID, idempotency key, and state;
- provider turn reference plus the effective model, reasoning effort,
  permission/sandbox policy, and attachment references submitted for that
  turn;
- start/end timestamps and elapsed time;
- token usage, cost, provenance, terminal reason, and the optional typed
  `failureKind` for a failed turn.

`agent_queued_input` stores the queue ID, task ID, FIFO insertion sequence,
state, idempotency key, effective settings, attachment references, payload blob
reference, stable user-message event ID after first claim, current/previous
claimed turn IDs, timestamps, and pause reason. The text payload
lives in an owner-only task blob so detailed prompts are not copied into
SQLite. A claimed queue row, task-local turn claim, new `agent_turn`, and
user-message outbox row commit atomically.

When CP10 implements steering, `agent_input_delivery` stores only steer
delivery ID, task/turn, unique ByteQuay idempotency key, state, timestamps, and
redacted failure. Direct starts and queued starts reuse `agent_turn`; they do
not claim provider exactly-once delivery.

`agent_approval` stores the tagged request, source IDs, product-permitted
available decisions,
the typed response when resolved, and request/resolution timestamps. Pending is
derived from the missing response; it is not a separate lifecycle state.

`agent_process_launch` begins when `prepare(...)` returns and stores the launch
ID, closed owner kind and opaque owner ID, whether the owner committed to
spawning, the resolved executable and argv digest, and a transition timestamp,
for every supervised launch, including task providers, probes, the model
catalog, and text-only helpers. Before that row
exists, a turn owner has never been attempted. The executable is retained
because it is the only record of what was launched when the process marker
itself is the thing that went missing. The launch row stores no prompt,
credential, or raw argv. The owner reference and launch row commit in one unit
of work; the fsynced agent-cli marker supplies the OS identity, phase, and
receipt used to reconcile that durable intent.

`agent_event_outbox` stores each state-coupled unsequenced event in the same
SQLite transaction as its owning state mutation, keyed by `eventId`, with its
delivery state and assigned task sequence. It is a short-lived delivery ledger,
not a second transcript: after `TaskLogStore` has durably appended the event,
the row is marked delivered and may be pruned according to bounded outbox
retention.

Accepted user-message/steer bytes, tool payloads, and stdout are not copied
into SQLite. They are represented by bounded JSONL events and typed task blob
references. Until an event drains, `agent_event_outbox` may temporarily hold
its bounded metadata and blob reference, never the raw message or output
bytes. Approval request and resolution state remains durably queryable in
SQLite as well as replayable in the event sequence.

### 10.2 Runtime directory

The directory is outside every worktree:

```text
~/Library/Application Support/ByteQuay/agent-runtime/
├── _processes/
│   └── <launch-id>.json
└── <task-id>/
    ├── events.jsonl
    ├── blobs/
    │   ├── attachments/<content-id>
    │   ├── queue/<content-id>
    │   └── events/<content-id>
    ├── provider/
    │   └── scratch/
    └── title-<request-id>/
        ├── worktree/
        └── provider/
```

The task ID and content IDs are generated by ByteQuay, not supplied as paths.
Process markers are owner-only, identify the task or closed control role that
owns the launch, contain
identity/recovery metadata only, and are validated against the OS process-start
token before any signal is sent.
Every resolved child is checked to remain under the configured runtime root.
Attachment and event blobs share this root but use typed references,
independent media/size validation, and separate subdirectories. Only a
validated attachment owned by the task can be converted into a provider input
path. A disposable helper's unique scratch is deleted only after verified
shutdown. Task-title scratch otherwise stays inside the owning task's resources
until recovery and normal task cleanup can safely remove it; standalone Better
Words scratch is likewise retained rather than deleting files beneath a
possibly live process.

On successful completion, after the execution tree is quiescent and live queued
work is empty:

1. Close the provider session and unsubscribe its terminal root/child threads.
2. Collect Git facts, tests, metrics, and an optional uninterpreted final
   response for display.
3. Commit summary, totals, `COMPLETED`,
   `deleteAfter = completedAt + 7 days`, and release of the matching completion
   mutation claim in SQLite.
4. For a workflow, persist its own `FINISHED` result. A direct interactive
   completion already has its terminal task result.
5. Explicitly revalidate and remove the clean worktree without force while
   retaining its generated result branch, then record `worktreeRemovedAt`.
   Pending task outbox events drain before this physical removal. If
   removal or that fact's write fails, preserve and report the result without
   creating a cleanup lifecycle state; the policy-scoped workflow monitor
   re-enters the terminal workflow after a one-minute `updatedAt` backoff and
   retries this idempotent operation.
6. Keep `events.jsonl`, blobs, and provider scratch data readable until the
   deletion deadline.
7. At or after the deadline, invoke `AgentCompletionService.cleanup()`, retry
   only tasks eligible under the workflow barrier, drain their pending outbox,
   retry worktree removal, delete runtime resources, and mark cleanup `DELETED`. A
   failure records `RETRY_PENDING` and advances the existing task update time.
   Retention maintenance waits one minute before selecting that attempt again
   and orders eligible work by update time, so one persistent failure does not
   hot-loop or starve another due task.

Failed tasks use the same seven-day period, measured from their terminal
timestamp. The product owner accepted this seven-day detailed-runtime window
for the first release on 2026-09-04.

### 10.3 Provider-owned history

ByteQuay does not delete provider-owned session history in the first release.
Closing a provider session only releases ByteQuay's transport and process
resources.

For example, suppose a task stores a Codex thread reference in
`AgentSessionRef`. Completing the task closes that session, retains ByteQuay's
`events.jsonl` for seven days, and then deletes the ByteQuay runtime directory.
The Codex thread may still appear in Codex's own history because it is owned by
Codex, not by ByteQuay. The same rule applies to a Claude session. ByteQuay
must not edit undocumented Codex or Claude storage files.

If a provider later exposes an official history-deletion operation, using it
requires a separate explicit user action and design decision.

## 11. Recovery, security, and concurrency

- Startup drains the event outbox and performs the separate provider-process
  recovery described below, but it does not scan or reconcile worktrees. Each
  later `provision`, `inspect`, or `cleanup` call revalidates its immutable
  repository, path, branch, and commit facts. A half-finished or mismatched
  orphan is preserved and reported rather than adopted or deleted.
- Provider launches carry an owner marker with PID, executable, OS process-start
  token, and driver owner. Startup signals only a process whose identity still
  matches, then observes the entire directly owned process tree exit. If
  identity or exit cannot be proven, a known task owner retains its claim and
  projects recovery required; an unknown or conflicting owner closes the global
  agent gate. Recognized control-only evidence is retained but does not close
  that gate because the launch could not access a task worktree.
- Durable launch phase distinguishes a known pre-launch crash from an uncertain
  process result. `NOT_ATTEMPTED` and an intact `PREPARED` marker before
  `PreparedProcess.start()` prove no payload ran; a missing/corrupt marker after
  `PREPARED`, `ATTEMPTING`, or `RUNNING` is `UNVERIFIABLE`, never guessed safe.
- Accepted/running roots without a usable transport become `INTERRUPTED`, but
  their turn claim is released only after the root, every known descendant,
  every observed execution-capable item, and every listed background terminal
  are terminal/stopped. If that cannot be reconciled, ByteQuay may stop that
  task's supervised provider process; root completion alone is not the
  quiescence barrier. Queue auto-start and all worktree-mutating actions remain
  disabled until it passes. Startup therefore never releases a `TURN` claim
  merely because the matching durable turn row is terminal.
- A startup `OPEN` task with a stale `COMPLETION` claim has no unrecorded Git
  write to resume. After provider quiescence, one unit of work records
  completion interruption and releases the claim; the task remains `OPEN` and
  projects idle. If the final
  `COMPLETED` transaction already committed, startup finishes only provider
  shutdown; it does not automatically inspect or remove the task worktree.
  Crash fixtures cover both sides of the task transaction.
- A direct `ACCEPTED` turn without `startedAt` is known unsent: startup marks
  it `INTERRUPTED` and pauses any queue claim. Explicit resume creates a new
  turn rather than reusing the old attempt. An active turn with `startedAt`
  but no provider reference is uncertain and a matching queue claim becomes
  `UNCERTAIN`; it is not eligible for automatic or ordinary queue resume. An
  independent steer delivery uses its steer-only ledger. Only after quiescence
  may the user abandon uncertain input; neither action retries it.
- Loss of a task-owned App Server interrupts only the running Codex root/child
  work for that task. ByteQuay terminates and verifies that owned process before
  starting a replacement. Stored thread references and queued inputs stay
  intact, but no turn or queue head is implicitly restarted.
- A repeated start request uses an idempotency key. Another concurrent request
  receives HTTP 409.
- Queue claiming, turn insertion, the task-local worktree-mutation claim,
  and their event outbox row are transactional. A successful terminal turn may
  claim the FIFO head once; every other terminal state requires a direct user
  resume action.
- Turn start, a queue claim, and completion all acquire the same task-local
  worktree-mutation claim by optimistic compare-and-set; completion rejects a
  nonterminal claim.
- Git worktree provisioning and cleanup are serialized per managed repository.
  Normal cleanup refuses a dirty worktree or open sequencer and preserves the
  generated branch. The shared service has no force-removal or branch-deletion
  operation.
- Provider readiness verifies the selected sandbox, workspace roots, approval
  flow, and ordinary built-in tool execution through the task-owned process and
  worktree.
- A dropped SSE connection does not affect the provider run. Reconnect uses the
  last received sequence.
- CLI commands are argv lists passed directly to `ProcessBuilder`; no shell
  command strings are accepted.
- Runtime directories use `0700`; files use `0600`.
- The controller remains bound to `127.0.0.1` and validates the desktop
  origin/session.
- Existing CLI authentication is used without extracting credentials into
  frontend storage or event payloads.
- Image attachments are task-owned, media-verified, bounded by byte and pixel
  limits, and addressed by opaque references. Renderer-supplied filesystem
  paths are rejected.
- Known secrets are redacted before an event or diagnostic blob is persisted.
- Approval timeout, turn termination, and provider disconnect resolve pending
  requests as denied and append a terminal approval event. A renderer/SSE
  disconnect alone leaves the card pending until that rule resolves it.
- Output, blob size, runtime, and cancellation grace periods are bounded.

## 12. Workspace settings and provider readiness

Provider authentication readiness was a CP11 release blocker and is now
implemented. Installing a CLI and opening its interactive screen does not prove
that it can make an authenticated remote request, so ByteQuay detects a missing
or expired login before a task acquires resources or starts a turn.

### 12.1 Backend gate

`AgentDriver.probe()` is the authoritative check for the exact executable and
environment that ByteQuay will use. `AgentDriverRegistry.statuses()` supplies
the same results to Workspace settings. A driver probe must distinguish, with
machine-readable status rather than by parsing diagnostic text:

- CLI missing;
- incompatible required provider protocol or capability, including the detected CLI
  version for diagnosis; no Codex reported version text, including unparseable
  output, produces this state by itself;
- authentication required or expired;
- ready;
- degraded but usable; and
- temporarily unavailable.

For Claude CLI, the authentication portion runs `claude auth status --json`
with the same executable and allowlisted environment used for turns. Opening
the interactive `claude` screen is not an authentication check. The CP11
reference executable (2.1.259) returns one JSON object whose required
`loggedIn` member is boolean; fields such as `authMethod`, `apiProvider`, and
`projectsDirectory` are additive diagnostics and are ignored. `loggedIn:
false` maps to `AUTHENTICATION_REQUIRED` even when the command exits non-zero,
because that is the reference logged-out behavior. `loggedIn: true` is usable
only with a zero exit and after the version/capability checks pass. Missing,
malformed, multiple, or wrongly typed JSON, a non-zero logged-in result, or a
timeout maps to `TEMPORARILY_UNAVAILABLE`; it never becomes ready by parsing
stderr text. Stdout and stderr remain separate, and ByteQuay persists neither
the response's other fields nor any credential/path value. Other drivers own
their provider-specific readiness checks behind the same SPI.

Task creation and every later turn start perform a fresh readiness check before
accepting a turn, acquiring a new task resource, or consuming a CI Autofix
attempt. A rejected probe throws the provider-status-bearing unavailable error;
the authenticated HTTP boundary returns `503` with code
`AGENT_PROVIDER_UNAVAILABLE`, `laneId`, typed `readiness`, redacted
`diagnostic`, and optional `signInCommand`. It does not create a failed turn or
provision a worktree. Other transport or runtime-unavailable failures retain
the generic error contract instead of manufacturing a readiness state.

If authentication expires during a running turn, the driver confirms it with
that same typed status command after the failed provider result or process
exit; it never classifies expiry by matching human-readable diagnostics. The
turn persists optional `failureKind: AUTHENTICATION_REQUIRED` alongside its
ordinary failed terminal state and delivery facts, and emits exactly one
redacted `AUTHENTICATION_REQUIRED` error event. A temporarily unavailable or
malformed follow-up probe leaves the turn as an ordinary provider failure.

The owning workflow pauses with the typed
`AUTHENTICATION_REQUIRED` reason. It preserves the task, worktree, session
reference, logs, and remaining attempt budget. A CI Autofix retry remains part
of the same already-counted fix attempt and is linked as a later user turn, so
authentication loss does not consume another authorized attempt. Nothing
automatically resends. After a successful readiness recheck and quiescence,
the user may explicitly confirm a retry in the same task using the original
durable user text and attachment references with a fresh idempotency key.
`UNKNOWN` delivery is never retryable. Authentication failure must never be
reported as a GitHub login problem.

ByteQuay does not read, persist, or send provider OAuth credentials. The CLI
continues to own its credentials; ByteQuay stores only readiness state,
timestamps, and redacted diagnostics.

### 12.2 Workspace settings UI

Workspace settings contains an **Agent providers** section with one row for
each configured lane, initially Codex CLI and Claude CLI. Although CLI login is
machine-user scoped, it is shown here because readiness determines whether the
selected provider can work in this workspace.

| UI state | Display | Primary action |
| --- | --- | --- |
| Checking | Checking provider… | None |
| Ready | Connected, CLI version, last checked time | Check again |
| Authentication required | Sign in required; tasks cannot start | Sign in / show provider login command |
| CLI missing | CLI not installed | Show installation instructions |
| Unsupported | Required protocol/capability failed; detected CLI version shown | Report issue / Check again |
| Degraded | Available with named capability limitations | Check again |
| Unavailable | Redacted actionable diagnostic | Check again |

The sign-in action is a direct user action. In the first release ByteQuay shows
the provider-owned command (for Claude, `claude auth login`) for the user to run
in a visible terminal; it does not launch, script, or infer completion of an
interactive login. The row provides **Check again**, which invokes the shared
typed readiness-refresh bridge operation and replaces the displayed status and
last-checked time from that one result.

Attempting to start a task while sign-in is required navigates or links to this
settings section and explains that the task has not started. Raw messages such
as `OAuth session expired and could not be refreshed` may remain in diagnostic
details, but the primary message is **Claude sign-in required**.

### 12.3 Release acceptance

CP11 closed the release blocker after automated tests demonstrated:

1. A new installation with Claude CLI present but logged out is blocked before
   task/turn creation, worktree provisioning, and CI attempt consumption.
2. Workspace settings renders the typed authentication-required state and its
   sign-in action without exposing credentials.
3. Successful login plus readiness refresh enables task start without an app
   restart.
4. Authentication expiry during a turn pauses the workflow, preserves its
   retry budget and task resources, and supports an explicit retry after login.
5. Provider authentication failures are logged once with task/run context and
   a redacted diagnostic, and are never labeled as GitHub failures.

## 13. Rich-task service acceptance

The task UI in [Agent Task UI Design](agent-task-ui.md) shipped only after the
CP11 automated contract, integration, security, and packaged-journey evidence
proved:

1. two concurrent Codex turns for different tasks and their child threads use
   separate task-owned App Server processes without event, approval, turn,
   item, process, or worktree cross-talk; each process is verified stopped with
   its turn and no task has more than one live process;
2. replay fixtures contain the original user input and attachments, streamed
   and authoritative completed items, readable reasoning summaries, plans,
   resolved/expired approvals, and direct child relationships without duplicate
   final text;
3. a second turn resumes the same root thread and preserves the model, effort,
   permission, and timing actually used for each turn;
4. renderer reload and SSE reconnect from the last sequence reconstruct the
   same root/child transcript while provider execution continues;
5. model-gated image input accepts only validated task-owned blobs and cannot
   be redirected to an arbitrary local path;
6. steering reaches only the expected active turn; untouched queued input
   survives restart, runs once in FIFO order after success, and pauses after
   every non-success terminal state; unknown/uncertain delivery is visible and
   never retried automatically; consumed `STARTED` rows do not block completion,
   while deleting or completing an uncertain row records `ABANDONED`; a
   definite pre-send start has no `startedAt` and is never sent; and a
   concurrent enqueue/Complete race either durably queues before the completion
   check or rejects enqueue after the completion claim;
7. crash fixtures at every SQLite-commit/JSONL-flush/outbox-mark boundary
   produce one canonical `EventId` and one task sequence, and never expose a
   state mutation without its matching replay event;
8. a completed child remains inspectable from task JSONL without a child table
   throughout runtime retention; all its approvals and activity retain their
   source IDs; after cleanup its durable summary remains and the transcript is
   explicitly reported as expired;
9. root completion with an active descendant, a missed live child notification,
   a child-created grandchild, and a root answer followed by a still-running
   command/tool/background item cannot report a successful result until
   recursive, model-version-aware `thread/items/list` closure reaches a stable
   item/background fixed point; V2 keeps `/` as the root logical path and
   requires every stable descendant path to be strictly nested under its direct
   parent. The per-turn App Server's owned process tree must then be
   `VERIFIED_STOPPED`; failed reconciliation records a failed turn, and only a
   verified stop permits its claim to release;
10. unknown delivery and transport loss disable queue start, Send, and Complete
   until thread/item/background-terminal reconciliation succeeds or
   that task's owned provider process tree is `VERIFIED_STOPPED`; compatibility
   fixtures must show that yielded and background commands are listed and
   cleanable, and a detached writer that escapes those registries makes the
   Codex lane ineligible for release rather than creating a fictional receipt;
11. restart safely releases a pre-final stale `COMPLETION` claim from an
   `OPEN` task after quiescence, leaving it idle
   without automatically inspecting or removing its worktree; every explicit
   worktree operation revalidates immutable ownership facts and preserves a
   half-finished or mismatched orphan; launch crash fixtures cover every database
   phase/marker combination from `NOT_ATTEMPTED` through `RUNNING`, proving that
   `ATTEMPTING + PREPARED/GATED` is known unlaunched while
   `RELEASE_PENDING` is never misclassified as unsent;
12. concurrent turn, queue, and Complete requests produce exactly one
   worktree-mutation claim; native App Server command, file, web, and other
   built-in tool calls run through the task-owned process and worktree and produce
   typed progress and terminal events; no ByteQuay business MCP/dynamic tool,
   lifecycle hook, or Code Mode host is registered for Codex, except that the
   CodeGraph bridge enables exactly its one fixed optional structural tool;
13. Claude new/resumed invocation fixtures carry each turn's effective model,
   effort, strict MCP config, empty inherited setting sources, and generated
   sandbox settings; attended `AUTO` uses CLI `manual` mode so sandbox escapes
   reach the ByteQuay approval adapter, unattended policies remain CLI `auto`,
   and the allocation-free readiness probe rejects a CLI without the `manual`
   token while verifying the exact executable and authentication contract; and
14. the release explicitly accepts App Server's documented experimental
   maturity, maintains
   the exact v0.151.0 reference fixture, compact projection, and experimental
   surface, and passes per-turn process launch/resume, cross-task process isolation,
   approval-ceiling, sandbox, active-item closure, background-terminal, image,
   steering, and reconnect tests against the latest user-managed Codex CLI.
   No reported version text, including unparseable output, may fail readiness by
   itself; a protocol incompatibility must report that detected running version.
