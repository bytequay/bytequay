# Agent Service Design

This document specifies local, worktree-scoped development tasks driven by
Codex CLI or Claude Code. The whole-system shape and Maven module boundaries
are defined in [ByteQuay Architecture](architecture.md).
The exact service and method sequence is defined in
[Create-to-close lifecycle](#7-create-to-close-lifecycle).

## 1. Decision summary

ByteQuay will add a new local agent subsystem rather than restore the deleted
task-agent implementation. Every task is bound to one validated Git worktree
and one immutable provider lane. The frontend sends multiple turns to the same
ByteQuay task; the selected driver resumes the provider session.

The backend exposes only provider-supported messages, plans, tool activity,
commands, file changes, approvals, usage, errors, and lifecycle events. Hidden
chain-of-thought is not part of the contract.

SQLite stores durable lifecycle facts, provider session references, per-turn
metrics, task totals, cleanup state, and the final summary. Detailed active-task
history is an append-only JSONL file with optional blobs. Terminal task logs
remain available for seven days; cleanup deletes them only after the retention
deadline.

The first release is single-user, localhost-only, and permits one active turn
per task. It accepts only clean worktrees. Cost remains unavailable when the
provider does not report a monetary amount.

## 2. Domain model and invariants

### 2.1 Main values

| Type | Meaning |
| --- | --- |
| `TaskId` | ByteQuay-owned stable task identifier |
| `TurnId` | One user request and its provider execution |
| `ApprovalId` | One pending permission decision |
| `AgentLaneId` | Stable driver ID such as `codex-cli` or `claude-cli` |
| `AgentSessionRef` | Opaque provider session/thread reference plus contract version |
| `AgentTask` | Durable task state, lane, worktree, timestamps, totals, and summary |
| `AgentTurn` | Durable per-turn state, provider turn reference, timing, and metrics |
| `TaskResources` | Validated runtime paths owned by one task |
| `WorkspaceLease` | Exclusive in-process ownership of a canonical worktree |

### 2.2 State

```text
Task: CREATED -> READY -> ACTIVE -> COMPLETING -> COMPLETED
                  |        |             |
                  +------> FAILED <------+

Turn: ACCEPTED -> RUNNING -> COMPLETED
                    |-----> FAILED
                    |-----> CANCELLED
                    +-----> INTERRUPTED

Cleanup: RETAINED -> DELETE_DUE -> DELETED
                         |
                         +--------> RETRY_PENDING -> DELETED
```

The implementation must enforce these invariants:

- One task has exactly one lane and one canonical worktree.
- At most one turn is `RUNNING` for a task.
- A worktree has at most one live task lease.
- Event sequence numbers are monotonic within a task.
- An event is appended before it is published to SSE.
- A provider session reference is stored before the first resumable turn is
  reported as accepted.
- A worktree must be clean when its task lease is acquired.
- Runtime logs are retained until seven days after the task becomes terminal.
- Cleanup failure changes cleanup state, never the terminal task state.
- Missing cost is `unavailable`, never zero.

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
     * Creates a durable task for one clean, validated worktree and provider lane.
     *
     * <p>The implementation validates the command, acquires a worktree lease,
     * rejects a dirty worktree, allocates task resources, verifies that the
     * lane is available, persists the task, and then returns it. No provider
     * turn is started. If creation fails after acquiring the lease, the
     * implementation deletes newly allocated resources and releases the lease
     * before propagating the failure.
     *
     * @param command requested lane, worktree, repository identity, and title
     * @return the newly created task in {@code READY} state
     * @throws InvalidAgentRequestException when the worktree or command is invalid
     * @throws AgentConflictException when the worktree already has a live task
     * @throws AgentUnavailableException when the requested lane is unavailable
     */
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
     * Lists tasks for the task window in reverse update order.
     *
     * <p>The result contains durable task facts only. Detailed event history is
     * obtained from {@link AgentEventService}.
     *
     * @param limit maximum number of tasks to return
     * @return at most {@code limit} tasks
     * @throws InvalidAgentRequestException when {@code limit} is not positive
     */
    List<AgentTask> list(int limit);
}
```

### 3.2 AgentTurnService

`AgentTurnService` serializes turns and coordinates a driver session. It is
the only core service allowed to start or cancel provider execution.

```java
package com.bytequay.agent.core;

public interface AgentTurnService
{
    /**
     * Accepts one user message and starts a provider turn.
     *
     * <p>The implementation rejects terminal tasks and concurrent turns,
     * persists an {@code ACCEPTED} turn, opens or resumes the task's provider
     * session, captures a usage baseline, starts the run, and returns without
     * waiting for provider completion.
     *
     * <p>Repeating the same idempotency key returns the previously accepted
     * turn. A different request while a turn is active fails with a conflict.
     *
     * @param command task ID, message content, attachments, and idempotency key
     * @return the accepted turn
     * @throws AgentTaskNotFoundException when the task does not exist
     * @throws AgentConflictException when another turn is active
     * @throws AgentUnavailableException when the provider session cannot start
     */
    AgentTurn start(StartAgentTurnCommand command);

    /**
     * Requests cancellation of a running turn.
     *
     * <p>Cancellation is idempotent. The method signals the provider run and
     * process supervisor, but terminal state is recorded only when the run
     * actually exits or the cancellation grace period expires.
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

### 3.3 AgentWorkspaceService

`AgentWorkspaceService` validates worktree identity and prevents two live
tasks from mutating the same worktree.

```java
package com.bytequay.agent.core;

import java.nio.file.Path;

public interface AgentWorkspaceService
{
    /**
     * Validates and exclusively leases a clean Git worktree for a task.
     *
     * <p>The path is canonicalized, checked for symlink escape, verified with
     * Git, matched to the requested repository identity, and required to have
     * no tracked or untracked changes before the lease is granted.
     *
     * @param taskId owner of the lease
     * @param requestedPath user-selected worktree path
     * @param repository expected repository identity
     * @return the canonical worktree and an idempotent release handle
     * @throws InvalidAgentRequestException when the path is unsafe or not the expected worktree
     * @throws AgentConflictException when another task owns the worktree
     */
    WorkspaceLease acquire(TaskId taskId, Path requestedPath, RepositoryIdentity repository);

    /**
     * Returns current Git facts without changing the worktree.
     *
     * @param worktree canonical leased worktree
     * @return branch, HEAD, dirty state, and repository identity
     * @throws InvalidAgentRequestException when the path is no longer a valid worktree
     */
    WorkspaceSnapshot inspect(Path worktree);

    /**
     * Releases a task's worktree lease.
     *
     * <p>The operation is idempotent and does not delete the worktree.
     *
     * @param taskId task whose lease should be released
     */
    void release(TaskId taskId);
}
```

The first release validates existing clean worktrees only. Creating or deleting
Git worktrees is a separate product decision. Changes created by the running
agent are expected after the initial clean-state check.

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

```java
package com.bytequay.agent.core;

import java.util.List;
import java.util.concurrent.Flow;

public interface AgentEventService
{
    /**
     * Persists and publishes one normalized event.
     *
     * <p>The backend assigns the next task sequence number, appends the event
     * envelope to JSONL, updates any durable projection, and only then publishes
     * it to subscribers.
     *
     * @param event normalized event without a backend sequence number
     * @return the persisted envelope containing the assigned sequence
     * @throws AgentStorageException when the event cannot be persisted
     */
    AgentEventEnvelope append(UnsequencedAgentEvent event);

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
     * @return a stage completed with the explicit decision or timeout denial
     * @throws AgentConflictException when the approval ID is already pending
     */
    CompletionStage<ApprovalDecision> request(ApprovalRequest request, Duration timeout);

    /**
     * Resolves one pending approval from a direct user action.
     *
     * <p>Repeating the same decision is idempotent. A contradictory second
     * decision fails with a conflict.
     *
     * @param command approval ID, decision, and optional scoped permission
     * @return the recorded decision
     * @throws ApprovalNotFoundException when no such approval is pending
     * @throws AgentConflictException when a different decision was already recorded
     */
    ApprovalDecision resolve(ResolveApprovalCommand command);

    /**
     * Denies all unresolved approvals owned by a terminal or cancelled turn.
     *
     * @param turnId turn whose approvals should be closed
     */
    void denyPending(TurnId turnId);
}
```

### 3.7 AgentMetricsService

`AgentMetricsService` centralizes provider-specific usage semantics so that
repeated snapshots are not accidentally summed.

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
     * final-for-invocation. Provider event IDs and monotonically increasing
     * counters are used to discard duplicates.
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

ByteQuay does not estimate subscription-backed Codex cost from a local price
table. If the provider does not report an amount, the UI displays
`unavailable`; a CLI estimate is visibly labeled as approximate.

### 3.8 AgentCompletionService

`AgentCompletionService` closes a task, creates its durable final result, and
performs retention-aware cleanup.

```java
package com.bytequay.agent.core;

public interface AgentCompletionService
{
    /**
     * Completes and closes a task while retaining its runtime log for seven days.
     *
     * <p>The implementation rejects active turns, closes the provider session,
     * collects the final agent response, Git snapshot, test facts, task
     * metrics, and learnings, commits the summary, {@code COMPLETED} state,
     * and deletion deadline in one transaction, and releases the worktree
     * lease. It does not delete retained runtime data.
     *
     * @param command task ID plus optional user-supplied completion notes
     * @return the durable completion summary and retention deadline
     * @throws AgentConflictException when a turn is active or the task cannot complete
     */
    TaskCompletion complete(CompleteAgentTaskCommand command);

    /**
     * Deletes retained runtime resources for a terminal task.
     *
     * <p>The operation is idempotent and is used both for the first cleanup
     * after the seven-day deadline and for retries after a deletion failure.
     * Cleanup failure records {@code RETRY_PENDING} without changing the
     * task's terminal state or summary.
     *
     * @param taskId terminal task whose retention deadline has passed
     * @return the updated cleanup result
     * @throws AgentConflictException when the task is active or its retention deadline has not passed
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
     * Opens a new provider session or resumes an existing reference.
     *
     * <p>The worktree and permission policy are fixed for the returned session.
     * A resumed reference must belong to the same lane and supported contract
     * version.
     *
     * @param context task identity, canonical worktree, policy, and optional session reference
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
     * @param command provider-neutral prompt, attachments, and turn policy
     * @return a handle for completion and cancellation
     * @throws AgentUnavailableException when the provider refuses or cannot start
     */
    RunningTurn start(AgentTurnCommand command);

    /**
     * Releases local transports owned by this session.
     *
     * <p>Closing does not delete provider-owned history and must be idempotent.
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
     * Returns a stage completed exactly once with the terminal provider result.
     *
     * @return completion stage for success, failure, cancellation, or interruption
     */
    CompletionStage<AgentTurnResult> completion();

    /**
     * Requests cancellation of the provider turn.
     *
     * <p>The call is idempotent and non-blocking. The completion stage reports
     * the eventual terminal result.
     */
    void cancel();
}
```

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

## 5. CLI runtime interfaces

These interfaces are internal to `agent-cli`. They are shared by the Codex
and Claude drivers but are not exposed to future HTTP API drivers.

The existing `BoundedProcessRunner` remains in `app` for short-lived
`gh` commands. It merges stdout and stderr and has no streaming stdin, so it
must not be stretched into the long-lived agent runtime.

### 5.1 ProcessSupervisor

```java
package com.bytequay.agent.cli.process;

public interface ProcessSupervisor
{
    /**
     * Starts an argv-based process in a validated working directory.
     *
     * <p>No shell is used. Stdout and stderr are drained independently, output
     * limits are enforced, and the sink is called in observed stream order.
     *
     * @param spec executable, arguments, environment allowlist, worktree, and limits
     * @param output sink for bounded stdout and stderr chunks
     * @return a managed process handle
     * @throws AgentUnavailableException when the process cannot be started
     */
    ManagedProcess start(ProcessSpec spec, ProcessOutputSink output);
}
```

### 5.2 ManagedProcess

```java
package com.bytequay.agent.cli.process;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;

public interface ManagedProcess
        extends AutoCloseable
{
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
     * Returns a stage completed when the process exits.
     *
     * @return exit code, terminal reason, timing, and truncation flags
     */
    CompletionStage<ProcessExit> exit();

    /**
     * Requests graceful termination followed by forced process-tree termination.
     *
     * <p>The method is idempotent and does not block for the grace period.
     */
    void cancel();

    /**
     * Releases streams and terminates a still-running process.
     */
    @Override
    void close();
}
```

## 6. Persistence ports

These interfaces live in `agent-core`; SQLite/JPA implementations live in
`persistence-sqlite`. See
[ByteQuay Architecture](architecture.md) for the dependency direction.

### 6.1 AgentTaskStore

```java
package com.bytequay.agent.core.store;

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
     * Lists recently updated tasks.
     *
     * @param limit maximum rows to return
     * @return tasks ordered by descending update time
     */
    List<AgentTask> listRecent(int limit);
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

### 6.3 TaskLogStore

```java
package com.bytequay.agent.core.store;

import java.nio.ByteBuffer;
import java.util.List;

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
| 1. Create | `POST /agent/tasks` | `AgentTaskService.create(CreateAgentTaskCommand)` | `AgentWorkspaceService.acquire(...)`, `AgentResourceService.allocate(...)`, `AgentDriverRegistry.require(...)`, `AgentDriver.probe()`, then `AgentTaskStore.insert(...)`; returns `READY` |
| 2. Observe | Open task window or reconnect SSE | `AgentEventService.subscribe(TaskId, afterSequence)` | Replays persisted events and then publishes live events without a gap |
| 3. Start turn | `POST /agent/tasks/{taskId}/turns` | `AgentTurnService.start(StartAgentTurnCommand)` | Inserts `ACCEPTED`, opens or resumes the provider session, persists `AgentSession.reference()`, begins metrics, calls `AgentSession.start(...)`, then records `RUNNING` |
| 4. Run | Keep SSE open; resolve approvals when requested | `AgentApprovalService.resolve(ResolveApprovalCommand)` | Provider calls `AgentEventSink.emit(...)`; normalized events pass through `AgentEventService.append(...)` and `TaskLogStore.append(...)`; usage passes through `AgentMetricsService.record(...)` |
| 5. Finish turn | Read turn state as needed | `AgentTurnService.get(TurnId)` | `RunningTurn.completion()` triggers `AgentApprovalService.denyPending(...)`, `AgentMetricsService.finishTurn(...)`, and `AgentTurnStore.update(...)` |
| 6. Continue | Send another message to the same task | `AgentTurnService.start(StartAgentTurnCommand)` | Reuses the task's stored `AgentSessionRef`; steps 3–5 repeat with a new `TurnId` |
| 7. Close | `POST /agent/tasks/{taskId}/complete` | `AgentCompletionService.complete(CompleteAgentTaskCommand)` | Rejects an active turn, closes the session, collects Git/turn/metric facts, commits the summary and `COMPLETED` state, and releases the worktree lease |
| 8. Delete runtime | Automatic retention job after seven days | `AgentCompletionService.cleanup(TaskId)` | Calls `AgentResourceService.delete(...)`, then records cleanup as `DELETED` |

The method calls and ownership are shown end to end below. Calls below
`AgentTaskService`, `AgentTurnService`, and `AgentCompletionService` are
internal orchestration, not additional frontend requests.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Web as HTTP/SSE controller
    participant Tasks as AgentTaskService
    participant Workspace as AgentWorkspaceService
    participant Resources as AgentResourceService
    participant Drivers as AgentDriverRegistry
    participant Driver as AgentDriver
    participant TaskStore as AgentTaskStore
    participant Events as AgentEventService
    participant Sink as AgentEventSink
    participant Turns as AgentTurnService
    participant TurnStore as AgentTurnStore
    participant Session as AgentSession
    participant Run as RunningTurn
    participant Metrics as AgentMetricsService
    participant Approvals as AgentApprovalService
    participant Logs as TaskLogStore
    participant Completion as AgentCompletionService

    User->>Web: POST /agent/tasks
    Web->>Tasks: create(CreateAgentTaskCommand)
    Tasks->>Workspace: acquire(taskId, worktree, repository)
    Workspace-->>Tasks: WorkspaceLease
    Tasks->>Resources: allocate(taskId)
    Resources-->>Tasks: TaskResources
    Tasks->>Drivers: require(laneId)
    Drivers-->>Tasks: AgentDriver
    Tasks->>Driver: probe()
    Driver-->>Tasks: AgentProviderStatus
    Tasks->>TaskStore: insert(readyTask)
    Tasks-->>Web: AgentTask(READY)
    Web-->>User: 201 Created

    User->>Web: GET /agent/tasks/{taskId}/stream?after=0
    Web->>Events: subscribe(taskId, 0)
    Events-->>Web: replay followed by live events

    loop Each user turn in the same task
        User->>Web: POST /agent/tasks/{taskId}/turns
        Web->>Turns: start(StartAgentTurnCommand)
        Turns->>TurnStore: findByIdempotencyKey(taskId, key)
        Turns->>TurnStore: findActive(taskId)
        Turns->>TurnStore: insert(acceptedTurn)
        Turns->>Drivers: require(task.laneId())
        Drivers-->>Turns: AgentDriver
        Turns->>Driver: open(sessionContext, eventSink)
        Driver-->>Turns: AgentSession
        Turns->>Session: reference()
        Session-->>Turns: AgentSessionRef
        Turns->>TaskStore: update(taskWithSessionRef)
        Turns->>Metrics: beginTurn(taskId, turnId)
        Turns->>Session: start(AgentTurnCommand)
        Session-->>Turns: RunningTurn
        Turns->>Run: reference()
        Run-->>Turns: ProviderTurnRef
        Turns->>TurnStore: update(runningTurn)
        Turns-->>Web: AgentTurn(RUNNING)

        loop Provider output
            Session->>Sink: emit(providerEvent)
            Sink->>Events: append(unsequencedEvent)
            Events->>Logs: append(envelope)
            Logs-->>Events: flushed
            Events-->>Web: publish persisted envelope
            opt Usage event
                Events->>Metrics: record(taskId, turnId, usageSample)
            end
            opt Approval event
                Session->>Approvals: request(request, timeout)
                Approvals-->>Web: approval event
                User->>Web: POST /agent/approvals/{approvalId}
                Web->>Approvals: resolve(ResolveApprovalCommand)
                Approvals-->>Session: ApprovalDecision
            end
        end

        Turns->>Run: completion()
        Run-->>Turns: AgentTurnResult
        Turns->>Approvals: denyPending(turnId)
        Turns->>Metrics: finishTurn(turnId, terminalState)
        Turns->>TurnStore: update(terminalTurn)
        Turns-->>Web: terminal turn state and metrics
    end

    User->>Web: POST /agent/tasks/{taskId}/complete
    Web->>Completion: complete(CompleteAgentTaskCommand)
    Completion->>TurnStore: findActive(taskId)
    Completion->>Session: close()
    Completion->>Workspace: inspect(worktree)
    Completion->>TurnStore: list(taskId)
    Completion->>Metrics: taskTotals(taskId)
    Completion->>TaskStore: update(completedTaskWithSummaryAndDeleteAfter)
    Completion->>Workspace: release(taskId)
    Completion-->>Web: TaskCompletion
    Web-->>User: task closed; logs retained for seven days
```

After the seven-day deadline, the retention job calls
`AgentCompletionService.cleanup(TaskId)`. Cleanup calls
`AgentResourceService.delete(TaskId)` before `AgentTaskStore.update(...)`
records `DELETED`. A deletion failure leaves the terminal task and summary
unchanged, records `RETRY_PENDING`, and retries the same cleanup method later.

The cancellation branch calls `AgentTurnService.cancel(TurnId)`,
`RunningTurn.cancel()`, `AgentApprovalService.denyPending(TurnId)`, and
`AgentMetricsService.finishTurn(TurnId, terminalState)` in order.

If creation fails after resource allocation,
`AgentTaskService.create(CreateAgentTaskCommand)` calls
`AgentResourceService.delete(TaskId)` and
`AgentWorkspaceService.release(TaskId)` before returning the error.

## 8. Provider implementations

The two drivers are the provider-format normalization layer. They share process
supervision but not wire DTOs or parsing: `CodexCliDriver` consumes App Server
JSON-RPC and `ClaudeCliDriver` consumes Claude `stream-json`. Each maps raw
provider messages to `ProviderAgentEvent` before calling `AgentEventSink`; raw
provider JSON never reaches `agent-core`, `TaskLogStore`, SQLite, or SSE.

### 8.1 CodexCliDriver

The Codex lane uses the documented
[Codex App Server protocol](https://developers.openai.com/codex/app-server),
not terminal-screen scraping and not `codex exec --json`. ByteQuay starts one
long-lived process and multiplexes tasks by Codex thread ID:

```text
codex app-server --listen stdio://
```

Stdout is newline-delimited JSON-RPC and must never be merged with stderr.
Stderr is a bounded diagnostic stream. The WebSocket transport is experimental
and is not used by the localhost sidecar.

The connection and task sequence is:

1. Send `initialize` once with ByteQuay `clientInfo`, wait for its response,
   then send the `initialized` notification. No other request is legal before
   this handshake finishes.
2. For a new ByteQuay task, call `thread/start` with the canonical `cwd`, the
   selected model when explicitly configured, `approvalPolicy`, and a
   `workspaceWrite` sandbox limited to the leased worktree. Persist the returned
   `thread.id` as the provider value in `AgentSessionRef`.
3. For a later backend process or unloaded task, call `thread/resume` with the
   stored `threadId`. Do not infer a session ID from provider files.
4. Start each user turn with `turn/start`; store the returned `turn.id` as the
   `ProviderTurnRef`.
5. Keep reading notifications until `turn/completed`. Treat
   `item/completed` as authoritative when a streamed delta differs from the
   final item.
6. Cancel with `turn/interrupt`. Closing a ByteQuay task sends
   `thread/unsubscribe`; it does not call `thread/archive` or `thread/delete`
   because provider-owned history is retained.

The driver normalizes these App Server events:

| App Server event | ByteQuay event |
| --- | --- |
| `item/agentMessage/delta` and completed `agentMessage` | Partial and final assistant message |
| `turn/plan/updated`, `item/plan/delta`, and completed `plan` | Structured plan/progress |
| `item/started` and `item/completed` for `commandExecution` | Command start and terminal result |
| `item/commandExecution/outputDelta` | Incremental stdout/stderr output |
| `fileChange` items and `turn/diff/updated` | File change and aggregated diff |
| `mcpToolCall`, `dynamicToolCall`, and `collabToolCall` items | Tool lifecycle |
| `item/commandExecution/requestApproval` and `item/fileChange/requestApproval` | Frontend approval request |
| `thread/tokenUsage/updated` | Cumulative usage snapshot |
| `error` and failed `turn/completed` | Error and terminal failure |

Readable reasoning summaries may be exposed as progress when the provider emits
them. Raw reasoning text is not persisted or shown, even if a model or protocol
version emits `item/reasoning/textDelta`; hidden chain-of-thought remains outside
the ByteQuay contract.

Protocol bindings are generated with the supported Codex binary and committed
or vendored with the driver:

```text
codex app-server generate-json-schema --out <schema-directory>
```

The driver probe runs `codex --version`, checks it against the supported
protocol range, and rejects an incompatible binary instead of silently dropping
unknown required events. Token updates are cumulative snapshots, so the driver
deduplicates by provider event identity and derives turn usage from the baseline
captured by `AgentMetricsService.beginTurn(...)`.

If App Server disconnects, all live turns become `INTERRUPTED`. ByteQuay may
restart and initialize the shared process, but it resumes a thread and starts a
new turn only after an explicit user retry.

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
  --permission-mode manual
  --mcp-config <task-provider-directory>/bytequay-mcp.json
  --permission-prompt-tool mcp__bytequay__permission_prompt
  --session-id <generated-claude-session-id>
```

`ProcessSupervisor` supplies this as an argv list without a shell. It writes the
UTF-8 user message to stdin and then closes stdin; prompts are not placed in the
process list or interpolated into a command string. The process working
directory is the task's canonical leased worktree.

ByteQuay generates the first session ID so the provider reference can be
persisted before the process starts, then verifies that Claude emits the same
ID in its initialization and result messages. For every later turn, replace
`--session-id` with the stored session ID in `--resume`:

```text
--resume <claude-session-id>
```

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
| `--forward-subagent-text` | Adds subagent text with `parent_tool_use_id` so the frontend can reconstruct nested activity |

`--include-partial-messages` produces both deltas and later completed assistant
messages. The driver accumulates deltas for live display, uses the completed
message as authoritative, and does not append duplicate final text. It keeps
reading stdout through EOF even after a `result` object because trailing system
events can follow the result.

The driver normalizes at least these message classes:

| Claude stream message | ByteQuay event |
| --- | --- |
| `system/init` | Session reference, CLI/model/tool capability facts |
| `stream_event` text deltas | Partial assistant message |
| `assistant` content blocks | Completed message plus `tool_use` starts |
| `user` content containing `tool_result` | Tool result and bounded command output |
| `tool_progress` | Running-tool elapsed-time update |
| `system/hook_started`, `hook_progress`, and `hook_response` | Hook lifecycle and bounded output |
| `system/task_started`, `task_progress`, and `task_notification` | Subagent/background-task progress |
| `system/api_retry` and rate-limit messages | Retry/rate-limit progress |
| `result` | Terminal status, response, duration, usage, session ID, and cost estimate |

Subagent messages retain `parent_tool_use_id` for nesting. Raw thinking blocks
are discarded; only provider-supported user-facing messages, tool activity, and
progress summaries enter the ByteQuay log. Hook and tool output is bounded and
redacted before persistence.

Headless manual approval needs a callback path; a terminal dialog is not a
frontend integration. ByteQuay writes an owner-only MCP configuration under the
task's provider directory and points `--permission-prompt-tool` at a
`mcp__bytequay__permission_prompt` tool. That adapter delegates to
`AgentApprovalService.request(...)`, waits for the frontend decision, and
returns allow or deny to Claude. The task-scoped MCP endpoint uses an ephemeral
capability token stored in the owner-only config file. ByteQuay does not pass
the token on the command line.

The driver never enables `--dangerously-skip-permissions`. It also does not use
`--bare`: bare mode does not read Claude subscription OAuth or keychain
credentials and therefore would break the supported no-API-key deployment.
`--debug` and `--debug-file` are troubleshooting options, not task-event
sources, and remain off by default.

The driver probe runs `claude --version` and checks each optional observability
flag. `--forward-subagent-text` requires a sufficiently recent Claude Code
version; when unavailable, the driver omits it and reports subagent transcript
streaming as unsupported rather than failing the entire lane. Required
streaming or permission flags failing the probe make the lane unavailable.

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
| Session resume | `thread/resume` with stored thread ID | New process with `--resume <session-id>` |
| Partial assistant text | Native `item/agentMessage/delta` | `stream_event` text deltas with `--include-partial-messages` |
| Structured plans | Native `turn/plan/updated` and plan items | No guaranteed dedicated plan event; render provider messages/tool activity only |
| Tool lifecycle | Typed item start/completion plus tool-specific records | `tool_use`, `tool_result`, and `tool_progress` messages |
| Incremental command output | Native `item/commandExecution/outputDelta` | Not guaranteed for Bash; terminal tool results are authoritative |
| Hook lifecycle | `hook/started` and `hook/completed` | Available with `--include-hook-events` |
| Nested agent activity | `collabToolCall` items | Parent-linked subagent messages with `--forward-subagent-text` |
| Interactive approvals | App Server JSON-RPC approval requests | ByteQuay MCP permission-prompt tool |
| Token usage | Cumulative `thread/tokenUsage/updated` snapshots | Per-step assistant usage and terminal invocation totals |
| Monetary cost | `UNAVAILABLE` unless a future protocol field supplies it | `CLI_ESTIMATE` from terminal `total_cost_usd` when present |
| Cancellation | `turn/interrupt` | Graceful then forced process-tree termination |

Capabilities also describe supported attachment types and version-gated event
classes. The UI must render missing capabilities honestly rather than
synthesize data.

## 9. Event and frontend contract

Every JSONL/SSE envelope has:

| Field | Rule |
| --- | --- |
| `schemaVersion` | Reject unsupported major versions |
| `sequence` | Assigned only by `AgentEventService`; monotonic per task |
| `taskId` | Required |
| `turnId` | Required for turn events |
| `type` | `message`, `plan`, `tool`, `command`, `file`, `approval`, `usage`, `lifecycle`, or `error` |
| `occurredAt` | Backend receipt time |
| `providerEventId` | Optional deduplication key |
| `payload` | Type-specific normalized data or a relative blob reference |

Stable localhost endpoints:

```text
POST /agent/tasks
GET  /agent/tasks/{taskId}
GET  /agent/tasks?limit=...
POST /agent/tasks/{taskId}/turns
GET  /agent/turns/{turnId}
POST /agent/turns/{turnId}/cancel
POST /agent/approvals/{approvalId}
POST /agent/tasks/{taskId}/complete
POST /agent/tasks/{taskId}/cleanup
GET  /agent/tasks/{taskId}/events?after=...&limit=...
GET  /agent/tasks/{taskId}/stream?after=...
GET  /agent/providers/status
```

Spring MVC controllers translate HTTP DTOs and errors only. SSE adapts the
core `Flow.Publisher`; controllers do not parse provider events or manage
processes.

## 10. Durable and disposable storage

### 10.1 SQLite

`agent_task` stores:

- task ID, title, lane ID, repository identity, and canonical worktree;
- task state, optimistic version, timestamps, and terminal reason;
- provider session reference and its contract version;
- token totals, cost amount when available, cost provenance, elapsed time, and
  provenance;
- final summary, Git/test facts, cleanup state, and runtime deletion deadline.

`agent_turn` stores:

- turn ID, task ID, idempotency key, and state;
- provider turn reference and model when reported;
- start/end timestamps and elapsed time;
- token usage, cost, provenance, and terminal reason.

Detailed prompts, tool payloads, and stdout are not copied into SQLite.

### 10.2 Runtime directory

The directory is outside every worktree:

```text
~/Library/Application Support/ByteQuay/agent-runtime/
└── <task-id>/
    ├── events.jsonl
    ├── blobs/
    │   └── <content-id>
    └── provider/
        └── scratch/
```

The task ID is generated by ByteQuay, not supplied as a path. Every resolved
child is checked to remain under the configured runtime root.

On successful completion:

1. Stop and close the provider session owned by the task.
2. Collect final response, Git facts, tests, metrics, and learnings.
3. Commit summary, totals, `COMPLETED`, and
   `deleteAfter = completedAt + 7 days` in SQLite.
4. Release the worktree lease.
5. Keep `events.jsonl`, blobs, and provider scratch data readable until the
   deletion deadline.
6. At or after the deadline, invoke `AgentCompletionService.cleanup()` and
   mark cleanup `DELETED`. A failed deletion records `RETRY_PENDING` and is
   retried.

Failed tasks use the same seven-day period, measured from their terminal
timestamp.

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

- On backend startup, accepted/running turns without a live process become
  `INTERRUPTED`; tasks remain resumable.
- A repeated start request uses an idempotency key. Another concurrent request
  receives HTTP 409.
- A dropped SSE connection does not affect the provider run. Reconnect uses the
  last received sequence.
- CLI commands are argv lists passed directly to `ProcessBuilder`; no shell
  command strings are accepted.
- Runtime directories use `0700`; files use `0600`.
- The controller remains bound to `127.0.0.1` and validates the desktop
  origin/session.
- Existing CLI authentication is used without extracting credentials into
  frontend storage or event payloads.
- Known secrets are redacted before an event or diagnostic blob is persisted.
- Approval timeout and disconnect default to denial.
- Output, blob size, runtime, and cancellation grace periods are bounded.
