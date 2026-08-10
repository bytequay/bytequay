# Workflow Runtime

Status: normative greenfield replacement specification.

This document is sufficient to implement the durable runtime used by the new
development flow. It deliberately defines new records and boundaries. An
implementation must not call, wrap, migrate, dual-write to, or share lifecycle
state with an older development-flow service.

Read this with the [overall architecture](./README.md),
[PR timeline](./pr-timeline.md), and [user gates](./user-gates.md). Agent-specific
prompt and tool contracts live in the other documents linked by the overall
architecture.

## 1. Purpose

The runtime turns a user command, agent completion, or external observation into
durable, restart-safe work. It owns:

- one Task branch and one isolated worktree;
- dispatch, admission, retries, and recovery;
- one exclusive fenced writer lease per Task;
- agent-session start, park, resume, and close;
- storage of an agent result before another agent can consume it;
- objective Task/code/check state and references to GitHub-owned effect results.

The runtime does **not** decide whether code is good, whether a review comment is
important, or whether prose means “approved.” Those are semantic judgments made
by an agent or the user.

## 2. Non-goals

- No compatibility mode, data migration, legacy row reader, or dual execution.
- No stage graph and no independent Plan or Development agent.
- No parser for an agent's final prose, JSON, keywords, or verdict.
- No concurrent writers for the same Task.
- No live agent-to-agent interruption or conversation relay.
- No workflow transitions reconstructed from the PR timeline.
- No in-memory-only queue, lease, approval, or effect authority.
- No new interface/factory per record. Start with concrete modules and the
  database transaction boundary described here.

## 3. Invariants

1. A Task owns exactly one branch and one worktree. Agents never get their own
   worktrees for that Task.
2. The normal agent writers are the Task Agent and CI Fixer. The optional
   deterministic `UPSTREAM_SYNC` program operation may also mutate it. All three
   serialize through the one current `WriterLease` fencing token; no other agent
   or program path may write.
3. Every command that schedules asynchronous work updates its domain owner in
   one transaction. Objective effects create one immutable `Operation` plus its
   one `DispatchTicket`; competing Task-writer causes instead remain pending and
   ensure one reconciliation ticket, from which `WorkSelector` creates at most
   one eligible writer operation under the Task lock.
4. A controller or **workflow-control** tool handler may commit that transaction;
   it may not start a process, run Git, call GitHub, or resume an agent directly.
   Ordinary execution tools such as source reads, shell, and `run_checks` may run
   only inside an already claimed Agent run under its current capabilities and
   writer fence; they cannot create lifecycle transitions or external effects.
5. A claimed operation is revalidated against its frozen subject before each
   irreversible effect.
6. A child agent's opaque final response is committed to `AgentResult` before
   its result-ready fact can be selected for parent delivery.
7. A running agent is never interrupted by new CI, review, or user input. New
   facts are stored in an inbox; the runtime selects the next turn after the
   current turn parks or finishes.
8. Program code interprets typed tool calls and objective records, never agent
   prose. A malformed tool call fails immediately as a tool error; malformed
   final prose remains ordinary stored prose.
9. Only proven effects advance objective state. “I pushed” in an agent message is
   not evidence of a push.
10. Every idempotency key is derived from stable owner identity plus the frozen
    subject, never from wall-clock time.

## 4. Minimal data model

Names below are new logical records. SQL naming may follow project conventions,
but their ownership and constraints must remain intact.

### `Task`

| Field | Meaning |
|---|---|
| `task_id` | Stable application identity. |
| `request_key` | Caller idempotency key; unique. |
| `repository_id` | Repository configuration used to provision the Task. |
| `goal_text` | Exact confirmed user goal; never an agent-generated summary. |
| `status` | `CREATED`, `ACTIVE`, `WAITING_USER`, `NEEDS_ATTENTION`, `COMPLETED`, or `CANCELED`. Remote/CI/gate waiting is projected from owner records rather than copied here. |
| `epoch` | Monotonic invalidation counter for leases and old operations. |
| `launch_base_sha` | Immutable base used to create the worktree; nullable only while a normal Task is `CREATED` and provisioning has not resolved the configured base. |
| `current_base_sha` | Current program-proven comparison/publication base; initialized to the launch base and changed only through an immutable `TaskBaseRevision`. Same narrow provisioning nullability. |
| `current_base_revision_id` | Pointer to the latest immutable base revision; nullable only before base provisioning. |
| `branch_name` | Unique Task branch. |
| `worktree_path` | Isolated Task worktree. |
| `current_head_sha` | Last program-observed clean committed head. |
| `task_session_id` | Persistent Task Agent session. |
| `ci_session_id` | Nullable persistent CI Fixer session. |
| `pr_id` | Nullable until a reviewable committed diff exists; then immutable. |
| `current_lifecycle_revision_id` | Pointer to the latest immutable lifecycle revision. |
| `current_change_set_revision_id` | Pointer to the currently adopted immutable code revision. |
| `pending_work_watermark` | Monotonic value allocated when a new pending work fact is registered. |
| `last_reconciled_work_watermark` | Highest pending-work watermark covered by a completed reconciliation pass; it is not proof that every older cause completed. |
| `reconciliation_sequence` | Monotonic generation for successive reconciliation operations. |
| `selected_writer_operation_id` | Nullable pointer to the one non-reserved writer operation selected under the Task lock; no other ordinary writer ticket is eligible. |
| `reserved_mutation_operation_id` | Nullable admission reservation for one already-created successor operation that must consume a sealed non-clean/sequencer state before any unrelated writer. |
| `waiting_mutation_state_ref` | Nullable pointer to a sealed Task-question state. While present, no writer may run; answering creates and reserves the one exact successor before clearing this barrier. |

`status` is intentionally coarse. Detailed UI state comes from the current
operation, open gate, CI observation, and feedback batch. Do not add a new Task
status for every screen.

The normal conversation/audit store retains the Trunk transcript and associates
the accepted tool invocation through `request_key`. That transcript is not a
Task launch field. The program neither selects “accepted decisions” from it nor
injects it into the Task Agent; the self-contained `goal_text` is the complete
semantic launch contract.

### `WaitingMutationState`

`request_user_input` may be called while the Task worktree or an upstream Git
sequencer is intentionally non-clean. The runtime therefore stores an immutable
barrier instead of assuming that “waiting” means clean:

```text
WaitingMutationState {
  waitingStateId, taskId, questionId, predecessorOperationId,
  sealedStateRef, worktreeDigest, createdAt,
  answerRevisionId?, successorOperationId?, consumedAt?
}
```

The terminal question tool seals the measured Git/sequencer state, stores the
question, sets `Task.waiting_mutation_state_ref`, and moves the Task to
`WAITING_USER`. It never resets or commits on the agent's behalf. While the
pointer exists, `MutationAdmission` rejects every writer. An exact answer
atomically appends the answer and first applies any terminal-remote fact. If the
Task remains active, it creates one `USER_ANSWER` Task-turn operation and
nonclaimable ticket, sets that operation as the reserved successor, clears the
waiting pointer, and moves the Task to `ACTIVE`; a terminal Task instead consumes
the wait through cleanup without another writer. The ticket becomes claimable
only after the predecessor result is durable and its lease/selected pointer are
cleared; the successor rechecks `sealedStateRef` before resuming. This same
contract handles clean and dirty waits, trading some throughput for one safe,
restartable rule.

### `TaskLifecycleRevision`

Every lifecycle change is an immutable owner fact:

```text
TaskLifecycleRevision {
  lifecycleRevisionId, taskId, sequence,
  fromStatus?, toStatus, reasonCode,
  evidenceRef?, operationId?, recordedAt
}
```

`UNIQUE(task_id, sequence)` orders revisions. `Task.status` and
`current_lifecycle_revision_id` are transactional current-state pointers, not a
second history. No controller, agent prose, or timeline projector updates them
directly.

### `TaskBaseRevision`

The launch base is retained, while an explicitly integrated moving target gets
one immutable current-base fact:

```text
TaskBaseRevision {
  baseRevisionId, taskId, sequence,
  previousBaseSha?, baseSha,
  reason = INITIAL | UPSTREAM_TARGET_INTEGRATION | EXPLICIT_RECONCILIATION,
  evidenceRef, sourceOperationId, recordedAt
}
```

For a normal Task, the claimed provision operation resolves the configured base
and appends `INITIAL` before creating the branch/worktree. A confirmed upstream
preview already owns the resolved target SHA and may append `INITIAL` in its
Task-creation transaction. A later revision is valid only after a fenced
operation proves the exact new target and the branch integration result. It
updates `Task.current_base_sha/current_base_revision_id` atomically and stales
base-bound checks, reviews, gates, and upstream verification. Agent prose or a
moving ref name cannot advance it.

### `ChangeSetRevision`

The runtime adopts one mechanically observed clean committed candidate at a
writer-turn boundary:

```text
ChangeSetRevision {
  changeSetRevisionId, taskId, sequence,
  previousHeadSha, headSha, baseRevisionId, baseSha,
  treeDigest, diffDigest,
  source = TASK_AGENT | CI_FIXER | UPSTREAM_SYNC,
  sourceRunId?, sourceOperationId,
  adoptedAt
}
```

`FlowWorktreeInspector` is the stateless observation boundary used before
adoption. Given the program-owned repository root, Task worktree, exact branch,
base SHA, and predecessor SHA, it verifies a clean attached same-repository
worktree in two agreeing Git observation passes. It returns the observed head
plus versioned SHA-256 digests derived only from immutable base/head tree object IDs;
it never reads an agent-supplied head or a textual diff. Inspection alone does
not adopt the head or grant mutation authority: the later `ChangeSets.adopt`
transaction must revalidate its fence and expected Task pointers before storing
a revision. The initial safe boundary rejects primary checkouts, broken linked-
worktree registration, external clean/process filters, assume-unchanged or
skip-worktree index entries, partial/promisor object stores, alternate object
commands, and any gitlink. The safety probe runs before object peeling, so
inspection cannot lazily fetch a missing object. Recursive submodule inspection
is deferred; the inspector never enters a submodule repository.

`UNIQUE(task_id, sequence)` and `UNIQUE(task_id, head_sha, source_operation_id)`
make adoption replay-safe. The row is appended only after the program proves the
Task branch, clean worktree including untracked files, committed head, expected
predecessor, live writer fence, and computed Git/tree/diff digests. Agent text
or an agent-supplied SHA cannot adopt code. `baseSha` is derived from the current
`TaskBaseRevision`; a caller cannot supply or silently mutate it.

### `Operation`

| Field | Meaning |
|---|---|
| `operation_id` | Stable execution identity. |
| `owner_kind`, `owner_id` | Non-null durable owner identity, such as `TASK`, `PR`, or pre-Task `UPSTREAM_REQUEST`. |
| `task_id` | Nullable derived Task link. Required for Task writers; null for a pre-Task upstream preview. |
| `kind` | Bounded program action, such as `PROVISION_TASK`, `RECONCILE_TASK`, `RUN_TASK_TURN`, `RUN_REVIEWER`, `RUN_CI_FIXER`, read-only `RUN_CI_LEARNING`, `UPSTREAM_SYNC`, `PUBLISH`, or `MERGE`. Local check commands execute inside the already claimed writer turn rather than creating a nested writer operation. |
| `subject_digest` | Hash of every revision/head the operation is allowed to act on. |
| `input_ref` | Immutable stored input manifest. |
| `state` | `READY`, `CLAIMED`, `WAITING`, `SUCCEEDED`, `RETRYABLE`, `FAILED`, or `CANCELED`. |
| `attempt` | Transport attempt count; does not grant new semantic authority. |
| `result_ref` | Typed program result or external receipt, never parsed agent prose. |

Unique constraint: `(owner_kind, owner_id, kind, subject_digest)` for operations that must
exist once per subject. A deliberate rerun gets a new immutable subject revision,
not a random duplicate key.

### `DispatchTicket`

`DispatchTicket` is one-to-one with `Operation` and contains `not_before`, claim
owner, claim expiry, monotonic `claim_generation`, priority, and delivery state.
A successful claim returns an unguessable `claimToken` bound to that generation.
It is the only durable
claim model: an eligible unclaimed ticket is work. A best-effort database
notification may reduce latency after commit, but polling claimable tickets is
the correctness path and no second notification record exists.

Every agent process, tool capability, and terminal result is bound below the
model-visible schema to `{runId, claimToken}`. Writer capabilities additionally
carry the `WriterLease` fence. A stale claim generation can neither call tools
nor finish a run, including for read-only reviewers and CI-learning turns.

A single worker pool is enough; lanes are a `kind`/priority field, not separate
executors.

### `WriterLease`

| Field | Meaning |
|---|---|
| `task_id` | Primary key: at most one writer per Task. |
| `operation_id` | Operation currently allowed to mutate. |
| `task_epoch` | Must equal the Task's current epoch. |
| `holder_kind` | `TASK_AGENT`, `CI_FIXER`, or `UPSTREAM_SYNC`. The last is a bounded program operation, not a fifth agent role. |
| `fencing_token` | Monotonic token issued at acquisition. |
| `expires_at` | Short renewable expiry. |

Every adapter that mutates the Task's local filesystem, index, commits, branch,
or history requires `{task_id, operation_id, task_epoch, fencing_token}` and
rejects a stale token. The lease is shared serially; it is not permanently owned
by one agent session. An exact-SHA remote push is an authorized external effect:
it runs under the publication barrier and effect fence, and must not change the
local worktree or Task branch.

### `AgentRun` and `AgentResult`

`AgentRun` freezes `runId`, its unique `operationId`, role, session, parent
session if any, head SHA, prompt manifest, capability set, program-derived
`intendedGateKind` when it is a writer run,
`state = QUEUED | RUNNING | COMPLETED | FAILED | CANCELED`, optional
`failureReasonCode`, and timestamps. `UNIQUE(operation_id)` makes both fresh
start and resume replay-safe. A timeout is `FAILED` with
`failureReasonCode=TIMEOUT`; roles do not add competing run states.

`AgentResult` has a unique `runId`, `terminalOutcome = COMPLETED | FAILED |
CANCELED`, optional opaque `finalContent`, optional program-owned `errorRef`,
program-owned `stopProofRef`, and storage timestamp. Every terminal run gets one result, so
a consumer can receive a durable failure/cancellation without invented agent
prose. The process supervisor derives `terminalOutcome` from process/tool state;
the model never authors or formats that enum.

```text
AgentTerminalOutcome =
  COMPLETED(finalContent?) |
  FAILED(errorRef, partialContent?) |
  CANCELED(reasonCode, partialContent?)
```

`finalContent`/`partialContent` is stored verbatim when present. `errorRef` and
`reasonCode` are program-owned observations, not model output fields.

The current new-flow transport is deliberately **in-process Task/CI writer
execution only**. Read-only reviewer and learning execution remains pending for
the adversarial-reviewer component; this writer supervisor does not claim it.
`AgentProcessAttempt` records `{runId, claimGeneration, claimTokenDigest,
executionId, capabilityId, state = RESERVED | ACTIVATED | STOPPED, jvmPid?,
jvmStartedAt?, threadId?, threadName?, capabilityRevokedAt?, stopType?,
stoppedAt?, stopProofRef?, quarantineReason?}`. The secret claim token is never
placed in a prompt, result, capability object string, or process metadata.
`threadName` is diagnostic only; the witness binds the exact terminated
`Thread`, its ID, and its JVM PID/start identity.

`InProcessWriterAgentSupervisor` is the one concrete launcher for `TASK_AGENT`
and `CI_FIXER`. It persists `RESERVED`, creates, registers, and starts one owned
Java thread behind a closed dormant gate, then durably records the exact
JVM-start/thread identity and advances the run to `RUNNING`. Only after that
transaction commits does it open the gate and permit the writer body to run.

Every tool effect runs wholly inside the opaque capability's synchronous
`callTool`/`runTool` scope on that exact writer thread. Admission atomically
revalidates the run, claim generation/token digest, capability ID, revocation
state, and writer fence, then tracks the invocation until the supplied effect
returns. Cancellation closes local admission and durably revokes first. A tool
already admitted may drain, but no later tool starts and ownership cannot be
released until both the invocation and writer thread end. The body and tools may
not detach child work. A transport that needs child work must first own and join
that complete execution set.

On normal return the supervisor revokes the capability and verifies the exact
thread is `TERMINATED`. Only then can it privately construct the in-memory stop
witness accepted by the runtime and store a program-derived `NORMAL_RETURN`
proof. Raw JVM/thread IDs and caller strings cannot mint `STOPPED`. Cancellation
revokes first, interrupts, and waits for a bounded deadline. A cooperative end
gets a `COOPERATIVE_CANCELLATION` proof. An execution or admitted tool that does
not end remains quarantined with its writer pointer and lease retained. If the
claim expired while a writer ended, revocation and `STOPPED` are still stored,
the live registry entry is removed, and the typed outcome is
`STOPPED_AWAITING_RECOVERY`; no `AgentResult`, pointer, or lease is released.
Recovery may mark that exact expired attempt `FAILED/DONE` first. Only
termination commands may then use the retained exact claim generation/token,
recovery result reference, selected pointer, and writer lease to revoke, capture
STOPPED, or quarantine. Tool admission and `AgentRuns.finish` still require the
current unexpired `CLAIMED` authority, so this narrow path cannot mutate or
publish a result after recovery won the race.

CLI/shell agent transport is unsupported in the new flow. A future OS-process
transport must own a complete process group and mechanical death receipt before
it can be admitted. Cross-JVM recovery of an activated in-process Java thread is
also deferred: a restarted JVM cannot safely join or revoke that old thread.
After its claim expires, recovery settles the exact operation/ticket
`FAILED/DONE` with `PROCESS_ATTEMPT_RECOVERY_REQUIRED`, moves the Task to
`NEEDS_ATTENTION`, and retains its selected pointer and writer lease. It leaves
the attempt's capability-revocation and quarantine fields unchanged because no
stop was proven, and it issues no successor generation. A second supervisor in
the same live JVM reuses the shared live registry; it never calls the execution
dead merely because it has a new runtime object.

The runtime may expose a result to a model as text, but it never reads that text
to choose a transition. Objective evidence such as changed head, check run, or
commit IDs is stored separately by the adapters that observed it.

### `LocalCheckPolicyRevision`, `LocalCheckProfile`, and `LocalCheckRun`

Local check configuration and evidence are program-owned.

```text
LocalCheckPolicyRevision {
  policyRevisionId, repositoryId, sequence,
  sourceRevision, sourceDigest, recordedAt
}

LocalCheckProfile {
  profileId, policyRevisionId, name,
  command, workingDirectory, environmentAllowlist,
  timeoutSeconds, requiredForGateKinds[],
  permittedGeneratedPaths[]
}

LocalCheckRun {
  checkRunId, taskId, changeSetRevisionId,
  policyRevisionId, profileId, operationId,
  observedStartHead, observedEndHead,
  commandDigest, startedAt, completedAt,
  conclusion = PASSED | FAILED | UNAVAILABLE,
  exitCode?, unavailableReasonCode?, outputRef,
  trackedTreeCleanBefore, trackedTreeCleanAfter
}
```

Policy/profile identifiers, commands, required gate kinds, conclusion, heads,
timestamps, and output references come from the program. An agent may select an
allowed profile name; it cannot supply a command, status, or evidence ID.

Fresh evidence for change-set revision `C` requires:

- the run binds `C`, its exact head at both start and end, and the current policy
  revision/profile;
- every profile required for the target gate has one terminal run;
- the tracked tree is clean before and after (permitted generated paths do not
  alter tracked source); and
- no later change-set or applicable policy revision exists.

A real attempted profile may conclude `UNAVAILABLE` only from a program-observed
missing toolchain/credential/environment condition with retained output. A
missing attempt is not `UNAVAILABLE`. `LocalCheckRun` is immutable: a later
head/tree/policy change does not rewrite its conclusion. Instead,
`LocalChecks.requiredEvidence` returns an objective `STALE_*` blocker for the
current subject; the historical run remains readable but cannot satisfy
readiness.

### `InboxItem`

An immutable external, user, agent-result, or system fact waiting to be handled:

`{inbox_id, task_id, source, external_key, revision, kind, subject_head,
payload_ref, work_watermark, observed_at,
selected_by_operation_id?, handled_by_operation_id?}`.

Unique constraint: `(source, external_key, revision)`. Updating a GitHub comment
creates a new revision; redelivering the same revision does nothing.
The first registration of any owner fact that can wake a Task allocates its
`work_watermark` under the Task lock; redelivery reuses it.

### `ReconciliationWait`

A reconciliation pass that is blocked by a higher-priority in-flight fact does
not spin or select lower work:

```text
operationId, taskId, blockerKind, blockerOwnerRef, blockerRevision,
throughWorkWatermark, createdAt, releasedAt?
```

There is at most one live wait because there is at most one nonterminal
reconciliation operation. `blockerOwnerRef` is exact: a reviewer run, user
question, reserved mutation, or publication operation/gate revision. The pass
transitions to runtime `WAITING` and no new reconciliation generation is created
merely because older causes remain ineligible. The blocker owner's terminal
transaction calls `Reconciliation.releaseWait`; that call terminally cancels
the old pass with typed result `BLOCKER_ADVANCED` and creates exactly one next
generation frozen at the then-current work watermark. Restart recovery performs
the same compare-and-set. A different revision cannot release the wait.

### External-effect ownership

The runtime owns only the enclosing `Operation`, its `DispatchTicket`, and the
reference in `Operation.input_ref`/`result_ref`. The
[GitHub integration](./github-integration.md) owns the immutable ordered effect
payload, step attempts, probes, and receipts tied to that runtime operation. The
runtime must not create shadow effect-attempt or receipt rows.

## 5. Program APIs

The names are implementation contracts, not suggestions to retain older service
shapes.

| Method | Caller | Required behavior |
|---|---|---|
| `TaskCommands.startTask(requestKey, repositoryId, goalText)` | `start_task` Trunk tool | Require a self-contained `goalText`. In one transaction create `Task`, initial lifecycle revision, `PROVISION_TASK` operation, and ticket. Return `task_id` immediately; normal conversation storage remains audit only. |
| `TaskProvisioning.provision(taskId, operationId)` | dispatcher | For a normal Task, fetch/resolve the configured base to an exact SHA and append the initial `TaskBaseRevision`; then create branch/worktree or fail the launch. Never fall back to a shared checkout. After isolation succeeds, create the persistent Task Agent session, append `CREATED -> ACTIVE`, but start no model call: persist `INITIAL` (ordinary) or first deterministic upstream work as pending and ensure reconciliation. A confirmed upstream Task reuses its already-proven initial base revision. |
| `WorkflowCommands.enqueueTurn(taskId, kind, subjectManifest)` | domain coordinators | Persist/deduplicate the pending inbox fact, then call `Reconciliation.ensure`; never make a second writer operation directly eligible. |
| `DispatchQueue.claim(workerId, now)` | dispatcher | Atomically claim one eligible ticket and issue its expiring monotonic `claimToken`. A writer ticket is eligible only when its operation matches the Task's selected or reserved writer pointer. A `RUN_REVIEWER` ticket is eligible only when its exact parent has a durable `AgentResult`, that parent session is `PARKED_CHILD`, its selected-writer pointer is clear, and no writer lease remains; this predicate is the enforceable representation of “parent-blocked.” A reconciliation ticket waits while `selected_writer_operation_id` is live, but may run ahead of a reservation only when a terminal/effect/recovery cause outranks it. A reserved ticket's direct eligibility predicate—not `WorkSelector`—checks predecessor result/pointer/lease plus those higher blockers. A GitHub effect claim also locks its `prId` and permits only that PR's oldest eligible nonterminal `ExternalEffectPlan.prSequence`. An expired agent claim is not eligible for a new generation until `stopAndProve` records the prior generation dead. |
| `TaskLifecycle.appendRevision(taskId, expectedLifecycleRevisionId, nextStatus, reasonCode, evidenceRef?, operationId?)` | Task owner commands | Append the immutable transition and advance Task pointers in one transaction; reject an unexpected current revision. |
| `TaskBases.advance(taskId, expectedBaseRevisionId, newBaseSha, reason, evidenceRef, sourceOperationId, fence)` | fenced base-integration operation | Prove the resolved target/base integration, append `TaskBaseRevision`, and advance current-base pointers atomically; reject a ref name, stale fence, or unproven integration. |
| `ChangeSets.adopt(taskId, expectedChangeSetRevisionId, sourceOperationId, fence)` | fenced writer operation, including its authenticated check tool | Mechanically inspect the bound worktree, derive the current base revision, prove predecessor/branch/clean committed head, compute digests, append the immutable revision, and advance `Task.current_head_sha`/pointer atomically. |
| `ChangeSets.current(taskId)` | launch/gate/check builders | Return the currently adopted immutable revision; never infer it from agent prose. |
| `MutationAdmission.evaluate(taskId, operationId)` | dispatcher before every writer claim | Require the Task's selected/reserved writer pointer, then return objective blockers from lifecycle/quarantine, any unhandled terminal-remote fact, `waiting_mutation_state_ref`, publication barrier, nonterminal exact-head reviewer barrier, and reserved successor. When a wait barrier exists no writer is eligible; when a mutation reservation exists, only that operation ID is eligible after terminal facts are reconciled. |
| `WriterLeases.acquire(taskId, operationId, holderKind)` | writer operation | Call `MutationAdmission.evaluate`, then issue the next fencing token only for an allowed holder kind and when no live lease exists. No caller may bypass the canonical predicate. |
| `WriterLeases.assertValid(fence)` | every mutating adapter | Fail closed before mutation if owner, epoch, token, or expiry differs. |
| `WriterLeases.renew(fence)` / `release(fence)` | operation runner | Renew only the same token; release idempotently. |
| `MutationAdmission.reserveSuccessor(taskId, predecessorOperationId, successorOperationId, sealedStateRef, fence)` | terminal upstream history/question tool | In the same transaction as the immutable semantic command and successor operation/nonclaimable ticket, set the Task reservation after verifying the current fence. No unrelated writer may acquire after the predecessor releases. Runtime admission makes this existing ticket directly claimable only after the predecessor `AgentResult` is durable, its selected-writer pointer/lease are cleared, and no terminal/effect/recovery fact outranks it; `WorkSelector` never enables it. |
| `MutationAdmission.finish(taskId, successorOperationId, outcomeRef)` | reserved successor/recovery operation | Clear the reservation only after success or a proven clean restore and release any exact reconciliation wait on this reservation. On dirty/uncertain failure, atomically transfer both reservation/wait to one typed recovery operation or quarantine the Task without admitting another writer. |
| `AgentSessions.createIdle(role, sessionManifest)` | provisioning only | Create one persistent `IDLE` session without an `AgentRun` or model call. This is the only Task-Agent provisioning path. |
| `AgentSessions.startFresh(operationId, claimToken, role, promptManifest, capabilities)` | claimed operation | Validate the current claim, lock the operation, and first return its existing unique `AgentRun`, if any. Otherwise create a fresh session plus its first run before process start and bind the owning request once. Use this for a reviewer and for a CI Fixer only when that Task has no CI session yet. Claim redelivery or a crash after commit must reuse the same session/run. |
| `AgentSessions.resume(sessionId, operationId, claimToken, inputRef)` | claimed operation | Validate the current claim, lock the operation, and first return its existing unique `AgentRun`, if any. Otherwise compare-and-set `IDLE`/eligible `PARKED_CHILD` to `RUNNING`, append stored input and one run, and reserve that session for this operation in the claim transaction. Start/recover the model process only after commit and always against that same run. |
| `InProcessWriterAgentSupervisor.launch(runId, claim, writerFence, body)` | Task/CI dispatcher after run transaction | Reuse the one live-JVM execution when present. Otherwise persist `RESERVED`; start an owned writer thread behind a closed gate; durably activate its exact JVM/thread identity and logical run; then open the gate. The body receives only a synchronous whole-effect capability. CLI launch is unsupported. |
| `InProcessWriterAgentSupervisor.cancel(handle, deadline)` | authenticated Task/CI cancellation owner | Close local tool admission and durably revoke before interrupting, then wait for the exact thread and any admitted synchronous tool. Store a cooperative stop proof and finish only when both ended. Otherwise quarantine while retaining pointer/lease. An expired claim yields `STOPPED_AWAITING_RECOVERY` after truthful stop capture. |
| `ProgramRunnerSupervisor.stopAndProve(operationId, writerFence)` | non-agent writer recovery | Terminate and prove a deterministic `UPSTREAM_SYNC`/other program runner dead before inspecting its worktree or transferring its fence; it has no `AgentRun` or model capability. |
| `ReviewerRequests.create(parentSessionId, subjectManifest)` | terminal `spawn_agent` Task tool | Validate and create the frozen reviewer request plus one initially parent-blocked `RUN_REVIEWER` operation/ticket; seal the parent run against more tools and return `reviewRequestId`, but create no reviewer session/run. Parent finalization below parks the session and makes the ticket eligible only after its result/state and writer release are durable. |
| `AgentRuns.sealForReview(runId)` | accepted `ready_for_review()` tool | Revoke further mutating tools for this run, request terminal completion, and defer gate construction until result/head evidence is stored and the writer lease is released. |
| `AgentRuns.finish(runId, claimToken, terminalOutcome, writerFence)` | `InProcessWriterAgentSupervisor` for the current proven Task/CI generation | Require an already `STOPPED` exact-generation attempt with durable capability revocation and the supervisor's terminated-thread witness; this method never changes `ACTIVATED` to `STOPPED`. Validate the tagged outcome/current claim and idempotently store one terminal `AgentResult`. Ordinary Task completion returns its persistent session to `IDLE` or durable recovery; CI Fixer finalization will call `CiAutofix.finalizeAttempt` before lease release when that checkpoint is connected. Release exact waits and ensure reconciliation only after owned terminal facts are durable. Conflicting result or stale-claim redelivery is rejected; identical current-generation redelivery returns the stored result. Reviewer/learning finalization requires its own later read-only supervisor contract and is not implemented by this API. |
| `TaskQuestions.ask(taskId, runId, question)` | authenticated terminal agent tool | Store the question and measured sealed state, set the waiting barrier, move the Task to `WAITING_USER`, seal the current run against more tools, and request finalization. It does not persist the run result, release the fence/pointer, or change session state; only `AgentRuns.finish` does so and then exposes the question as answerable. |
| `TaskQuestions.answer(userId, questionId, body)` | authenticated user command | Require the predecessor `AgentResult` durable/question answerable, append the exact answer, release any exact reconciliation wait on this question, reconcile a pending terminal-remote fact first, and if still active atomically create/reserve the one `USER_ANSWER` successor bound to the sealed state; never resume a generic latest turn. |
| `LocalCheckPolicies.current(repositoryId)` | check/gate builders | Return the current immutable policy revision and allowed profiles. |
| `LocalChecks.runAndRecord(taskId, changeSetRevisionId, profileId, operationId, fence)` | authenticated `run_checks` execution tool inside the current writer turn | Resolve the program-owned profile, verify the current operation/fence and exact change set, execute/capture it, inspect final head/tree, and append one immutable run. It creates no nested operation or lease. |
| `LocalChecks.requiredEvidence(taskId, changeSetRevisionId, gateKind)` | gate builder | Return exact current-policy required runs plus blocker/warning codes; never choose a result from model text. |
| `ExternalInbox.ingest(source, key, revision, payload)` | GitHub observer | Insert once, link the Task/PR, and call `Reconciliation.ensure`. Never start an agent directly. |
| `Reconciliation.ensure(taskId, causeRef)` | every pending-work owner | Lock Task and idempotently register `causeRef`: a new cause gets the next `pending_work_watermark`, redelivery reuses its existing one. Reuse the one nonterminal `RECONCILE_TASK`, including `WAITING`, if present. Otherwise, when an unhandled/unselected cause exists, increment `reconciliation_sequence` and create an operation/ticket binding `(taskEpoch, generation, throughWorkWatermark=current pending watermark)`. A cause arriving during that run advances the watermark but cannot mutate its frozen subject. |
| `Reconciliation.waitFor(operationId, blockerOwnerRef, blockerRevision)` | claimed reconciliation operation | Revalidate that the exact blocker revision is still nonterminal; if so persist `ReconciliationWait`, transition this operation once to `WAITING`, and create no writer or next generation. If it already advanced, restart selection under the same Task lock instead of installing a lost wait. |
| `Reconciliation.releaseWait(blockerOwnerRef, blockerRevision)` | blocker owner's terminal transaction | Idempotently compare-and-set the exact wait, cancel its runtime operation with typed `BLOCKER_ADVANCED`, and create at most one next reconciliation generation at the current watermark when pending causes remain. |
| `WorkSelector.selectNext(taskId, throughWorkWatermark)` | claimed reconciliation operation | Lock Task and choose current unselected facts at or below the frozen watermark using canonical priority. If a higher-priority reviewer/question/publication/reserved/recovery continuation is still in flight, call `waitFor` and select no lower work. Otherwise create exactly one ordinary writer operation/ticket, mark its input facts `selected_by_operation_id`, and set `selected_writer_operation_id`. Reservation tickets are never created or enabled here. |
| `OperationRunner.complete(operationId, typedResultRef)` | non-agent program runner | Commit result and release claim/lease. Agent operations finish only through `AgentRuns.finish`, which performs the equivalent operation settlement in its role-specific transaction; the generic runner must not settle them again. Success clears the matching selected-writer pointer and marks only proven input facts handled. A transport-retryable writer keeps its selections/pointer for the same operation; terminal failure clears them and registers typed recovery. A non-waiting reconciliation completion advances `last_reconciled_work_watermark` only through its frozen watermark and ensures a next generation only for eligible unhandled/newer facts. A waiting pass is advanced only by `releaseWait`, never by this generic completion rule. |
| `OperationCommands.settle(operationId, expectedState, outcome, typedOwnerProofRef)` | authoritative domain owner | Compare-and-set `WAITING -> READY/SUCCEEDED/CANCELED` or any nonterminal state to `CANCELED` under the state-machine proof rules; make the ticket/barrier ineligible atomically. A claimed executor must first be stopped/proven unable to act, and an uncertain remote call cannot be canceled without reconciliation proof. |
| `TaskCommands.resolveAttention(taskId, attentionRevision, decision)` | authenticated user/program proof | Resolve only runtime-owned non-gate attention and register the bounded next cause for reconciliation. Gate attention is resolved exclusively by User Gates from typed GitHub proof or explicit cancellation. |
| `TaskCommands.cancel(taskId, expectedEpoch)` | authenticated user | Increment epoch, revoke future admission, and enqueue idempotent cleanup. It cannot undo proven external effects. |
| `TaskLifecycle.acceptRemoteTerminal(taskId, observationRevision)` | GitHub observer owner command | From a fresh observation, mark a merge `COMPLETED` or an unmerged remote close `CANCELED`, then enqueue cleanup. |
| `WorkflowRecovery.redrive(now)` | bounded scheduled maintenance | Expire claims/leases, make retryable tickets eligible, and ask GitHub integration to reconcile uncertain effect operations. |

`run_checks()` reads the program-owned `AgentRun.intendedGateKind` and runs every
profile required by the current policy. Scheduling derives that value
mechanically: no-remote normal/upstream-final work maps to `INITIAL_PUBLISH`;
`CI_FIX_READY` maps to `CI_UPDATE`; `REMOTE_FEEDBACK` or combined CI+feedback maps
to `REMOTE_FEEDBACK`; and `LOCAL_REVIEW` inherits its referenced gate kind.
Reviewer/user-answer resumes retain the originating kind. The optional
`run_checks(profile)` form runs one allowed profile for focused development but
does not satisfy any other required profile. Both forms call
`LocalChecks.runAndRecord`; neither runs a model-supplied command. Returned typed
`check_run_id` values are valid only under the freshness rules above. The model
does not manufacture test evidence or declare a missing attempt unavailable.
Inside a writer turn, the tool first calls `ChangeSets.adopt` under that turn's
fence if the current clean committed head is not yet adopted, then binds the run
to the returned revision. A dirty/uncommitted candidate is an immediate tool
error. The agent may keep working after a check, but any later commit creates a
new change-set revision and makes the earlier run stale; readiness then requires
fresh checks for the final adopted revision.

`ready_for_review()` is also an agent tool. It is only a semantic declaration
that the Task Agent wants the current work shown to the user. The handler first
preflights known readiness under the current fence; a rejected call returns
typed blockers and leaves the run active. An accepted call seals the active
writer run: further mutation tools are rejected, the agent's ordinary
final prose is stored, and the program adopts the final clean committed head as a
`ChangeSetRevision` while the fence is still valid. It then releases the writer
lease. Only after those facts are durable does the program run any required
objective producer finalization through durable dispatch—for example, the
optional upstream Task's exact `UpstreamVerification`—then obtain the exact
change-set/check/review owner revisions and ask the [gate
component](./user-gates.md) to build the subject. An invalid call returns an
immediate tool error and leaves the run active so the agent can correct it. A
race/failure discovered only by post-run revalidation creates a durable
`REVIEW_READINESS_FAILED` cause for a new Task turn; it cannot reopen the
completed run or open a stale gate.

## 6. Command and dispatch protocol

Every asynchronous command follows one durable boundary:

1. Begin a database transaction and lock the domain owner.
2. Validate the command's expected owner revision and caller authority.
3. Create or revise the domain fact being requested.
4. For a deterministic objective effect, freeze its complete subject manifest
   and insert one immutable `Operation` plus its unique `DispatchTicket`.
   For a possible Task writer, leave the cause pending and call
   `Reconciliation.ensure`; do not create that writer directly. The explicit
   sealed-state successor used by a terminal Git/question command is the only
   reservation exception.
5. Commit.
6. Optionally notify the dispatcher after commit; periodic ticket claiming is
   the recovery and correctness path.
7. A worker claims the ticket, re-reads the owner and revalidates the subject.
   A reconciliation worker locks the Task and lets `WorkSelector` materialize
   exactly one selected writer operation/ticket from current pending facts.
8. If code mutation is required, the selected operation acquires the fenced
   writer lease.
9. It executes, records typed evidence/receipts, and finishes or parks the
    operation. Another durable command performs the next transition.

No process is started before transaction commit. A crash after commit can lose only a
best-effort notification; the durable ticket is still claimable.

## 7. Agent-to-agent protocol

Only the Task Agent creates an adversarial reviewer child:

1. `spawn_agent("adversarial_reviewer")` creates one `ReviewerRequest` plus a
   parent-blocked `RUN_REVIEWER` operation/ticket for exact head `H`, seals the
   parent run, and returns `reviewRequestId`. It creates no reviewer run.
2. Parent `AgentRuns.finish` stores the parent result/state, releases its writer
   fence and selected pointer, transitions its persistent session to
   `PARKED_CHILD`, and only then makes the reviewer ticket eligible. It does not
   keep a model call open while the child runs.
3. After claim, idempotent `AgentSessions.startFresh` creates exactly one
   read-only reviewer session/run bound to the request. The exact-head review is
   a short mutation-admission barrier: no Task/CI writer is admitted until that
   run becomes terminal.
4. The child returns ordinary prose, fails, or is canceled.
5. `AgentRuns.finish` stores one terminal `AgentResult(R)`, settles the reviewer
   operation/session, releases the review barrier, persists
   `AgentResultReady(runId, H)`, and ensures the one Task reconciliation ticket
   atomically. Failure/cancellation carries typed outcome/error evidence rather
   than invented prose.
6. Under the Task lock, `WorkSelector` re-evaluates CI/feedback/user facts and
   selects at most one next writer. When it selects the parent resume, the input
   contains `result_ref=R`; the parent then judges which findings are actionable.

There is no direct child-to-parent message, shared context mutation, or program
parser. `read_agent_result(R)` may exist for recovery/history, but normal delivery
is automatic so the parent cannot forget where the result was stored.

## 8. Scheduling and simultaneous CI/review feedback

External facts never interrupt an active turn. Pending causes never create
competing writer tickets. The one reconciliation operation runs after the
current writer becomes non-running; under the Task lock, `WorkSelector` uses
this order:

1. prove terminal remote states and invalidate stale authorizations; a merged or
   closed PR cancels/transfers any reservation to cleanup before mutation;
2. continue a previously authorized exact-subject effect if still valid;
3. resolve a pending `RECOVERY`/quarantine proof before normal work;
4. honor the direct admission barrier for an exact
   `reserved_mutation_operation_id`; its existing ticket—not `WorkSelector`—runs
   when eligible, and lower ordinary work cannot pass it;
5. deliver an exact in-progress continuation such as `AgentResultReady` or
   `CI_FIX_READY` to the Task Agent; compatible current feedback may be included
   in that same bounded envelope;
6. handle final failing CI for the current remote head with the CI Fixer;
7. handle frozen review feedback with the Task Agent against the resulting head;
8. handle new local user feedback with the Task Agent;
9. otherwise park.

Priority includes work that is **in flight but not yet deliverable**. If an
active reviewer is expected to produce `AgentResultReady`, a publication
operation is waiting for exact provider/CI proof, a Task question is unanswered,
the persistent CI session is finishing a bounded learning turn needed before a
new repair turn, or a reserved successor must finish, reconciliation selects no lower writer.
It stores one exact `ReconciliationWait` on that blocker. The blocker owner's
terminal transaction advances that wait once and freezes a new generation at
the then-current watermark. Old red CI or feedback cannot sneak ahead, and
unchanged pending facts cannot cause a polling/spin loop.

Why CI precedes feedback for the same remote head: a CI repair changes the code
subject. Starting both writers would make one patch stale, and preparing replies
against known-red code creates a misleading publication candidate. The feedback
item remains immutable in the inbox and is delivered after the CI round. A
repository-specific compile-first rule belongs inside the CI Fixer prompt, not in
this scheduler.

Exact continuations precede new CI work because they finish an already-sealed
semantic handoff. Running another writer first would knowingly stale a completed
review or an uninspected CI candidate. Every selection still revalidates its
head/workset; stale continuations remain audit records and are not resumed as
current work.

The order is deterministic but not semantic: the program does not decide how to
fix either input. It only avoids two actors mutating one branch concurrently.

## 9. Runtime state machines

### Operation

```text
READY ------> CLAIMED -> SUCCEEDED
  |                    -> WAITING -> READY | SUCCEEDED | CANCELED
  |                    -> RETRYABLE -> READY | CANCELED
  |                    -> FAILED
  |                    -> CANCELED
  \--------------------------------> CANCELED
```

`WAITING` means a durable external condition is required, not that a Java thread
or writer lease remains occupied. `RETRYABLE` is for transport/process failure;
it does not create a fresh semantic attempt or broaden authority. A typed owner
proof may settle `WAITING` directly to `SUCCEEDED`/`CANCELED`; stale is encoded
as `CANCELED` plus a typed stale result, not a separate runtime state.
An owner may cancel `READY`, `RETRYABLE`, or `WAITING` from an exact stale,
terminal, or user-cancel proof. Canceling `CLAIMED` first requires proof that the
executor/process can no longer act; an uncertain remote call stays `WAITING`
until a provider probe settles it. Cancellation atomically makes its ticket
ineligible and releases any matching logical barrier.

### Agent session

```text
NEW -> IDLE -> RUNNING -> IDLE
                   \-> PARKED_CHILD -> IDLE
          IDLE ----------------------> CLOSED
```

A session is resumed only from `IDLE` or `PARKED_CHILD`. New inbox facts cannot
change a `RUNNING` session's prompt.

### Task

```text
CREATED -> ACTIVE -> WAITING_USER
WAITING_USER -> ACTIVE
ACTIVE/WAITING_USER/NEEDS_ATTENTION -> COMPLETED
ACTIVE/WAITING_USER -> NEEDS_ATTENTION
NEEDS_ATTENTION -> ACTIVE
CREATED/ACTIVE/WAITING_USER/NEEDS_ATTENTION -> CANCELED
```

Only program-proven merge/close/cancel facts produce a terminal Task state.
Agent prose cannot. “Waiting for CI/GitHub/gate” is UI projection from current
owner records while the Task remains `ACTIVE`; no command owns a duplicate
remote-wait transition.

## 10. Concurrency, idempotency, and recovery

### Concurrency

- Row-lock the Task while choosing or scheduling its next mutation.
- Allow at most one nonterminal reconciliation operation per Task. Its monotonic
  generation and frozen work watermark let causes arriving during or after a
  pass create the next generation without reviving a terminal operation.
  A pass waiting on an in-flight higher-priority blocker is released only by
  that blocker revision; pending lower causes do not generate replacement passes.
- Keep at most one ordinary writer operation in
  `Task.selected_writer_operation_id`. All other causes remain pending owner
  facts behind one coalesced reconciliation ticket, so ticket-claim timing
  cannot override `WorkSelector` priority.
- Enforce one live `WriterLease` with `task_id` as its primary key.
- Every writer admission calls the one `MutationAdmission.evaluate` predicate.
  A quarantined/`NEEDS_ATTENTION` Task, active reviewer barrier, publication
  barrier, waiting-question barrier, unhandled terminal-remote fact, or
  mismatched reserved successor fails closed. A reservation is not a lease
  transfer: the predecessor releases its fence, and only the named successor
  can receive the next fence after terminal reconciliation.
- A nonterminal authorized publication operation is an admission barrier even
  while it waits without a lease. If its subject becomes stale, close that
  operation before admitting another writer.
- Read-only external observers may run concurrently because they cannot mutate
  the worktree. A reviewer holds no writer lease, but its exact-head run is a
  short mutation-admission barrier so a writer cannot knowingly waste or stale
  the review. Out-of-band head changes still make the result stale.

### Idempotency

- Task creation: unique `request_key`.
- Command delivery: expected owner revision plus command key.
- Operation: unique owner/kind/subject digest.
- Dispatch: one ticket per operation.
- Agent start: one run per operation; start/resume redelivery returns that run.
- Agent finish: one result per run; identical terminal redelivery returns it.
- External ingest: source/key/revision.
- External effect: GitHub-owned plan/step keys and receipts reference one runtime
  operation; the runtime stores no duplicate effect fact.
- PR creation: remote lookup/probe before retry, then bind one remote identity to
  the existing PR aggregate.

### Recovery

- Expired non-agent ticket claim: requeue the same ticket after its adapter's
  effect/recovery rule. Expired agent claim: do not requeue to a new generation.
  The current in-process transport has no cross-JVM death proof, so an activated
  attempt discovered after restart remains quarantined.
- Expired writer lease: immediately quarantine admission. Lease time alone does
  not prove a shell/model/Git process stopped. The live-JVM
  `InProcessWriterAgentSupervisor` must revoke and join a Task/CI writer, or
  `ProgramRunnerSupervisor` must stop deterministic program work and prove
  the complete runner/process group dead **before** inspecting the worktree or
  releasing/admitting another fence. If death or a clean committed exact state
  cannot be proven, keep the Task `NEEDS_ATTENTION`; never race inspection,
  guess, reset, or silently switch branches. This rule applies equally to Task
  Agent, CI Fixer, and `UPSTREAM_SYNC` program runners.
- Agent process died before result: retry/resume according to the same immutable
  operation-bound run only after old-generation death proof. A deliberate new
  semantic attempt first finishes the old run as failed, then creates a new
  operation/run linked to it; it never adds a second run to one operation.
- Agent result committed but parent not resumed: its `AgentResultReady` fact and
  deduplicated reconciliation ticket are recovered; `WorkSelector` decides when
  the parent delivery becomes the one selected writer.
- User question persisted with a sealed state: keep the waiting barrier through
  restart. An answer creates the exact reserved successor; a digest mismatch or
  missing predecessor-death proof quarantines instead of resuming into changed
  state.
- External effect timed out: GitHub integration probes by stable remote identity,
  records its owned receipt if proven, and returns the result reference to the
  same runtime operation.
- Database unavailable: perform no filesystem, Git, agent, or GitHub effect.

## 11. Cross-component contracts

| Component | Runtime gives | Runtime requires |
|---|---|---|
| [Task Agent](./task-agent.md) | Persistent session, self-contained exact goal, repository/worktree/base/head/policy facts, serialized writer turns, and stored child results. The Task Agent reads repository instructions/code and queries [Project Intelligence](./project-intelligence.md) itself; the runtime does not inject a selected Trunk/PI context. | Typed tool calls; commits and parks before relinquishing a writer turn. |
| [Adversarial Reviewer](./adversarial-reviewer.md) | Fresh exact-head read-only run. | Ordinary findings prose; no state mutation or verdict contract. |
| [CI Fixer](./ci-autofix.md) | Persistent session, exact failed CI observation, relevant memory, exclusive writer turn. | Commit/check evidence and ordinary summary; never push. |
| [PR timeline](./pr-timeline.md) | Immutable owner rows and revision timestamps. | Read-only projection; no callback that can advance workflow. |
| [User gates](./user-gates.md) | Durable operation/dispatch machinery and exact-subject revalidation. | Immutable authorization and GitHub-owned effect-plan reference; no authorization inferred from agent output. |
| [GitHub observer/executor](./github-integration.md) | One runtime operation/ticket plus authorization, including fresh exact-head ready authority for eligible heads. | Own the ordered effect payload, attempts, probes, and receipts; return only their references. Observer never writes the worktree. |
| [Optional upstream sync](./upstream-sync.md) | Durable operation, dispatch, and the same fenced writer lease for bounded deterministic Git steps. | Semantic conflict work returns to the Task Agent; no separate agent role or private worktree. |

## 12. Acceptance traces

An implementation is incomplete until these traces pass with process restarts
inserted at every numbered boundary.

### A. Start exactly once

1. Submit `startTask` twice with one `request_key`.
2. Observe one Task, one worktree, one start operation, and one Task Agent
   session. The session is `IDLE` and there are zero `AgentRun` rows before
   reconciliation selects work.
3. Kill the process after transaction commit but before the optional dispatcher
   notification.
4. Restart and observe the ticket claimed without another Task/session.
5. Observe the first selected `RUN_TASK_TURN` resume that existing session and
   create exactly one `AgentRun`.

### A2. Agent start redelivery

1. Claim a fresh reviewer operation and commit `startFresh`, then stop before
   the model process starts.
2. Redeliver the same claim and assert `startFresh` returns the same session and
   unique operation-bound run.
3. Repeat with `resume` on a persistent Task/CI session.
4. Assert each operation has one run and terminal `finish` creates one result;
   no recovery path creates a replacement run merely because process start was
   interrupted.

### A3. Expired live in-process generation

1. Start a Task or CI writer under claim token `C1`, then let its claim
   expire while the process remains alive.
2. Assert the ticket cannot issue `C2` or start another process yet.
3. In the same live JVM, cancel through the shared supervisor registry: revoke
   tools first, interrupt, and join the exact thread. A cooperative end stores
   proof; an uncooperative end retains the lease/pointer and quarantines.
4. Restart the runtime object in that same JVM and assert it reuses the live
   registry rather than launching a replacement or declaring death.
5. Restart in a new JVM and assert the activated attempt remains quarantined;
   this in-process transport does not issue `C2` or inspect the worktree.

### A4. Crash-safe process launch

Run the same writer launch with a crash after each implemented boundary:

1. after `RESERVED(executionId)` commits but before Java thread construction;
2. after the thread starts behind its closed gate but before identity activation;
3. after identity activation commits but before the gate opens; and
4. after the gate opens while the body is live.

At every same-JVM boundary assert no body runs before durable activation and the
shared registry starts at most one thread. At every new-JVM boundary assert an
activated attempt remains quarantined because the old Java thread cannot be
joined; recovery never manufactures a stop proof or launches a replacement.

### B. Child result before parent resume

1. Task Agent spawns reviewer for head `H1` and parks.
2. Repeat once with completed prose and once with a program-observed timeout.
3. Kill the app during `AgentRuns.finish` handling.
4. After restart, exactly one terminal `AgentResult` exists for each run.
5. The parent receives each stored outcome once; no code parsed or invented its
   contents, and the reviewer barrier/session is terminal.

### C. CI and feedback arrive together

1. Ingest final red CI and a review comment for remote head `H1`.
2. Assert both inbox items persist and no running turn is interrupted.
3. CI Fixer alone obtains the lease and commits `H2`.
4. Task Agent later receives the unchanged feedback revision against `H2`.
5. Assert no overlapping valid fencing tokens existed.

### D. Stale writer cannot commit

1. Let lease token `41` expire and issue token `42`.
2. Invoke every mutating adapter with token `41`.
3. Assert each fails before mutation.

### E. Uncertain external effect

1. Lose the network response after creating a remote PR.
2. Restart.
3. GitHub integration probes by the stable publication identity, stores its
   discovered receipt tied to the original runtime operation, and binds that
   remote PR to the existing local PR.
4. Assert no duplicate PR was opened.

### F. Lifecycle and change-set revisions

1. Race two lifecycle commands against the same expected revision; exactly one
   immutable successor commits and the other receives a stale-revision error.
2. Complete a fenced Task Agent writer turn at clean committed `H2`.
3. `ChangeSets.adopt` computes the actual tree/diff, appends one revision, and
   advances Task pointers; redelivery returns that same revision.
4. A dirty tree, unexpected predecessor, agent-supplied SHA, or stale fence
   cannot append/adopt a revision.

### G. Program-owned local checks

1. For adopted change set `C2`, call `run_checks("unit")`; program resolves the
   current profile, captures output and start/end head, and appends one run.
2. Assert the agent cannot substitute a command or conclusion.
3. Adopt `C3` or publish a new applicable policy revision; the `C2` run remains
   historical but `requiredEvidence(C3, gateKind)` rejects it as stale.
4. A missing attempt cannot become `UNAVAILABLE`; a real attempted profile with
   captured objective environment failure may.

### H. One selector despite simultaneous causes

1. Commit `AgentResultReady`, `FINAL_RED`, and `REMOTE_FEEDBACK` while a Task
   writer is finishing, all in one unchanged Task epoch; insert the third cause
   after reconciliation generation 1 has already frozen its watermark.
2. Restart. Assert all causes remain in their owners, generation 1 is not
   mutated, and no second live reconciliation exists.
3. Under the Task lock, assert `WorkSelector` creates only the exact continuation
   writer and sets `selected_writer_operation_id`.
4. Complete it. Assert generation 2 is created for the newer/unhandled
   watermark, then later generations select the still-current causes once each.
5. Assert no terminal reconciliation ticket was revived and no claim race
   created a second eligible writer.

### I. Dirty user wait survives restart

1. During an upstream conflict, call `request_user_input` with a dirty index and
   active sequencer.
2. Assert the question and `WaitingMutationState` commit before the lease is
   released; all Task/CI writers are rejected.
3. Restart, answer the exact question, and assert one answer-bound reserved
   successor is created.
4. It rechecks the sealed digest, resumes the same Task session, and no reset,
   hidden commit, or unrelated writer occurred.

### J. Reviewer completion outranks red CI without spinning

1. Task parks on active reviewer `R1`; final red CI is registered before `R1`
   completes.
2. Reconciliation records one wait on exact blocker `R1` and selects no CI
   writer. Restart repeatedly; no new reconciliation generation appears.
3. Reviewer completion atomically stores `AgentResultReady`, releases that exact
   wait, and creates one new generation at the current watermark.
4. The Task continuation consumes `R1` before any CI writer can mutate the head.

### K. Publication wait blocks feedback without spinning

1. An authorized feedback/publication operation is waiting for exact provider or
   CI proof; a later feedback workset arrives.
2. Reconciliation waits on the exact operation/gate revision and selects no Task
   writer. Restart; the wait remains one row/operation.
3. Typed proof settles or stales that publication and releases the wait once.
4. The next generation revalidates and selects only the still-current feedback.

## 13. First-principles challenge

| Question | Decision | Why | Trade-off |
|---|---|---|---|
| Can the Task Agent itself own the whole loop? | No. It owns semantic work, not durable scheduling or external truth. | Models terminate, lose connections, and can state effects that did not occur. | More program records, but restart safety is testable. |
| Can a queue call the process directly after saving the Task? | No. Objective effects get one operation/ticket; competing writer causes persist and use one reconciliation ticket. | A crash between save and call otherwise strands work; direct writer creation also races priority. | Small dispatcher-table and selector cost. |
| Can CI Fixer and Task Agent edit separate copies then merge? | No. Serialize one Task worktree. | Automatic patch merging creates a new semantic conflict resolver and stale evidence. | Lower throughput for one Task; deterministic code state. |
| Should a new external comment steer the running model? | No. Store then resume later. | Mid-turn input changes the model's subject without a reproducible boundary. | New comments may wait for the current turn. |
| Should child output use required JSON? | No. Store opaque prose and use typed tools for control. | A probabilistic formatter is an unsafe workflow API. | The program cannot automatically declare a semantic review passed—which is intentional. |
| Do we need an event-stream framework? | No. Owner rows, operations/tickets, and one dispatcher are sufficient. | The product is a local sidecar; a second distributed log adds duplicate truth. | Scale is bounded to one machine; revisit only with measured need. |
| Do we need detailed Task phases? | No. Keep Task status coarse and project detail from owner records. | Phase enums couple otherwise independent components and multiply invalid transitions. | Queries combine several records, which is simpler than coordinating duplicate state. |

## 14. Evidence and adopted/rejected ideas

- **Accepted from Codex:** a dedicated reviewer reads a selected diff and reports
  findings without changing the worktree. This supports a fresh read-only child,
  not a second writer. [Official Codex code-review documentation](https://learn.chatgpt.com/docs/code-review)
- **Accepted from Codex:** subagents are useful for independently bounded work;
  concurrent write-heavy work risks conflicts and context pollution. This
  supports narrow reviewer/CI roles and serialized mutation.
  [Official Codex subagent documentation](https://learn.chatgpt.com/docs/agent-configuration/subagents)
- **Accepted from Grok Build:** a child receives an explicit prompt/capability
  scope and returns a result to its parent; resumable sessions suit repeated CI
  work. **Rejected:** giving every child an automatically isolated worktree,
  because ByteQuay needs one coherent Task branch.
  [Grok Build subagents](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/16-subagents.md)
- **Accepted from Grok Build:** program scheduling can make background agent work
  visible from durable conditions. **Merged:** ByteQuay adds transactional
  operations/tickets and fencing; GitHub integration separately owns effect
  attempts/probes/receipts because remote mutation must survive a local restart.
  [Grok Build scheduler](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-tools/src/implementations/grok_build/scheduler/actor.rs#L568-L688)
## 15. Definition of done

- All acceptance traces are automated integration tests.
- No code path starts work without one committed `Operation`/`DispatchTicket`;
  no second durable notification record exists.
- No Task/CI writer body starts outside
  `InProcessWriterAgentSupervisor.launch`; tests at every reserve/gate/identity
  boundary prove activation precedes body execution.
  CLI/shell agent launch is rejected until a real process-group supervisor exists.
- Every Task lifecycle transition and adopted clean code candidate has one
  immutable owner revision with optimistic append/adopt checks.
- Local check commands, policy/profiles, conclusions, exact heads, and freshness
  are program-owned and covered by integration tests.
- No mutating adapter accepts an absent/stale fence.
- No transition depends on parsing model content.
- No Task can have two worktrees or two simultaneous writers.
- Restart tests prove child-result delivery and external-effect recovery.
- Runtime stores no GitHub effect payload/attempt/probe/receipt copy; its
  operation references the GitHub-owned plan and result.
- The new runtime can be enabled as one replacement flow without reading or
  writing any old development-flow row.
