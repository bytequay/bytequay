# Development flow architecture

Status: **LOCKED**

Version: **1.9**

Decision date: **2026-07-28**

This document is the normative architecture contract for development-flow
ownership, state transitions, persistence, asynchronous work, recovery, and
cleanup.

This document and its tracked migration plan are a self-contained
implementation contract. Files under `docs/mockups/` are optional local design
history, not required inputs. The preserved product topology and journey are
restated below. If an older note describes TaskPhase, AgentScheduler, generic
AgentRun, CI_FIXING stages, nullable turn scope, event-driven lifecycle
transitions, or any other conflicting behavior, this document controls.

Changing a locked rule requires an explicit decision. Record the decision in
the change log at the end; update at least one acceptance trace and the feature
compatibility matrix; do not silently edit an invariant to accommodate an
implementation.

## Purpose

The current flow has accumulated many race fixes around one structural
problem: several classes can infer and mutate the same lifecycle state.
AgentScheduler, Task lifecycle services, Stage lifecycle services, Brain
review coordination, validation listeners, PR polling, and projections all
participate in transitions. A callback can therefore be correct in isolation
and still be wrong for the current Task, Stage, generation, code fingerprint,
or remote head.

The target design has one rule:

> A domain owner changes its own state synchronously. Asynchronous
> infrastructure executes requested work and reports facts; it never decides
> the next domain state.

The target must preserve the complete existing development and review product,
not only the happy path.

## Preserved product topology and journey

The product hierarchy is:

~~~text
Workspace
  └─ 0..n Trunks
       └─ 0..n sibling Tasks
            └─ Task-owned Stages, Episodes, Turns, branch, worktree and PR
~~~

A zero-Task Trunk is valid. Each Task belongs to exactly one Trunk, receives
its own execution/conversation context from that Trunk at creation, and never
inherits a sibling Task's mutable runtime state. UI focus addresses one Trunk
or Task; it is not ownership or a concurrency restriction. Trunk conversation
continues and sibling Tasks may run concurrently subject to capacity policy.

The preserved user journey is:

~~~text
Plan
  -> implement + validate + Brain review
  -> Local Review + Approve & ship
  -> Draft remote PR + CI
  -> remote feedback rounds
  -> Merge / Close
  -> Cleanup
~~~

The sidebar groups those checkpoints under exactly four durable Stage rows:

~~~text
Plan
Local Development
  Implementing -> Validation -> Brain review -> Local review -> Push / PR
Remote Development
  Remote PR -> CI validation -> Comments -> Merge / Close
Cleanup
~~~

Approve & ship is the single local-to-remote promotion authority; creating the
remote PR is its result, not a second approval gate. The stable PR projection
remains `local-drafted -> local-open -> remote-drafted -> remote-open -> merged
| closed`. Detailed ownership, gates, automation, audit/history, provider
lanes, recovery, and cleanup requirements are defined in the sections and
compatibility matrix below; no ignored document is needed to implement them.

## Vocabulary

- **Workspace** — repository, credentials, policy, memory, and resource scope.
- **Trunk** — the long-lived Thread conversation that plans and creates Tasks.
- **Task** — one independent unit of work. It owns a branch, worktree, remote
  identity, Task Brain, Stage graph, policy, and terminal outcome.
- **Stage** — one durable Task-owned workflow boundary. Only Plan, Local
  Development, Remote Development, and Cleanup are Stages.
- **Episode** — a bounded loop attached to a Task or Stage, such as Brain
  review, CI repair, branch sync, or a remote feedback round. An Episode is
  not a Stage.
- **Turn** — one agent invocation with one unambiguous owner.
- **Operation** — one durable request for asynchronous work.
- **Effect step** — one externally observable step inside a multi-effect
  Operation.
- **Blocker** — a typed reason progress cannot continue automatically. A
  blocker overlays state; it does not replace the owner's state.
- **Projection** — a read-only UI or reporting view derived from domain facts.

## Locked decisions

1. **C01** — Trunk, Task, and Stage are separate aggregates with separate managers and
   repositories.
2. **C02** — There are exactly four durable Stage kinds:
   PLAN, LOCAL_DEVELOPMENT, REMOTE_DEVELOPMENT, and CLEANUP.
3. **C03** — CI repair, Brain review, local review, remote review rounds, branch guard,
   validation, and panel review are Episodes, Operations, gates, or artifacts;
   they are not peer Stages.
4. **C04** — ThreadTurn, TaskTurn, and StageTurn are separate tables and types. Their
   owner foreign key is non-null. Scope is never inferred from nullable ids.
5. **C05** — A standalone review assignment has its own ReviewAssignmentTurn. It is not
   forced into the Trunk / Task / Stage hierarchy.
6. **C06** — Domain state transitions are synchronous database commands.
7. **C07** — Cross-domain coordination is a synchronous use-case handler calling each
   owner in an explicit order and, where required, one transaction.
8. **C08** — Events and outbox records may wake dispatchers and projections. They are
   not the correctness mechanism for ordering domain transitions.
9. **C09** — ExecutionDispatcher replaces AgentScheduler as delivery and
   execution infrastructure. It owns DispatchTicket claiming, execution
   leasing and heartbeat, provider/process execution, infrastructure retry,
   cancellation delivery, and result delivery only. It owns neither domain
   transitions nor capacity policy.
10. **C10** — A dispatcher, observer, scheduled job, listener, projector, controller,
    agent, or provider callback cannot directly update Task or Stage state.
11. **C11** — Every asynchronous result is accepted only against its exact subject and
    operation fence.
12. **C12** — Runtime state such as queued, running, waiting for permission, or idle is
    derived from turns, operations, leases, and blockers. It is not mixed into
    the Task lifecycle.
13. **C13** — No command may find the latest or active Task or Stage and assume it is
    the target. Exact ids are mandatory.
14. **C14** — Local review data remains private. Promotion sends commits plus approved
    PR title/body only. Local comments, Brain comments, drafts, and review
    events never migrate to GitHub.
15. **C15** — AI-drafted GitHub replies, reviews, and reviewer requests are never posted
    without an explicit user action. Pre-authorized CI-repair and branch-sync
    code pushes are the only per-iteration remote-write exceptions.
16. **C16** — Waiting for user input, CI, review, a timer, or an external observation
    consumes no execution slot.
17. **C17** — A Task cannot become terminal until required Cleanup steps have completed.
18. **C18** — A read projection never writes source state.
19. **C19** — A completed Stage is immutable. Replan or another semantic restart opens
    a new Stage generation; it does not reopen the old row.
20. **C20** — A Task is permanently assigned one workflow version at creation. It never
    changes workflow implementation mid-flight.
21. **C21** — One stable local PR identity and all private timeline/review
    history survive promotion and Cleanup. Remote identity attaches to that
    record; promotion does not replace or erase it.
22. **C22** — An explicit human publish override may acknowledge selected
    review/validation blockers. Automation cannot use the override, and
    structural Git/authorization safety checks are never overridable.
23. **C23** — CapacityManager is the sole admission-policy authority and sole
    writer of CapacityLease. No dispatcher, executor, adapter, or legacy bridge
    may launch an Operation without its exact lease.
24. **C24** — A Task, Stage, Turn, Operation, or provider session is not a JVM
    thread. Only an admitted running execution temporarily borrows a worker;
    durable queueing and external/user waits hold no worker.
25. **C25** — ExecutionDispatcher owns exactly two V2 execution facilities:
    one shared virtual-thread-per-admitted-operation executor and one small
    scheduled maintenance executor. Async families are logical lanes, not
    separate pools, and executor queues never represent workflow state.
26. **C26** — During mixed LEGACY/V2 operation, both paths acquire from the
    same CapacityManager ceilings through a thin legacy admission bridge.
    Normal Task work cannot consume reserved Trunk-control capacity.
27. **C27** — Manual direct-merge consent freezes exactly one merge method
    (`merge`, `squash`, or `rebase`) with the exact-head MergeAuthorization.
    Recovery uses that frozen method and never substitutes another strategy.
    Historical V2 operations created before method capture retain the former
    `squash` behavior. Merge-queue execution remains queue-owned.
28. **C28** — Every explicit user-authorized GitHub write carries one stable
    client command id. The Task stores that id with the exact PR subject and
    immutable payload; an identical retry replays the same durable action and
    terminal result, while reuse for different input is rejected. Before the
    first remote effect, execution freezes the matching remote-effect ids that
    already exist, and recovery may adopt only an id outside that baseline.
    A top-level comment may be authorized against an exact terminal PR after
    merge or close; reviews, queue changes, and other Stage-bound writes still
    require their live owner and current open-head fence.
29. **C29** — CapacityManager resolves the applicable Workspace and Trunk
    ceilings inside the same serialized admission transaction that may create
    the CapacityLease. A committed limit reduction therefore cannot be followed
    by an admission using an older policy snapshot. Policy-change wakes are
    published only after the settings transaction commits; rollback publishes
    no wake. Existing leases are not revoked, but no additional Task is admitted
    until occupancy is below every current ceiling. Workspace and Trunk setting
    boundaries reject non-positive ceilings before persistence.
30. **C30** — `thread_settings.max_running_tasks` is the sole persisted Trunk
    Task-ceiling input. Upgrade migration V275 normalizes invalid historical
    values and projects an explicit legacy `threads.parallel_slots > 1` value
    into that field once; `parallel_slots` then remains legacy-drain data, not
    a second admission authority. Capacity notifications are post-commit
    hints: ExecutionDispatcher queues a coalesced retry on its owned
    maintenance executor, and transaction-completion callbacks perform no
    repository work.
31. **C31** — Legacy retirement diagnostics fail closed while any nonterminal
    LEGACY Task, queued/running legacy Turn, live legacy AgentRun, unfinished
    legacy validation claim, or unconsumed legacy effect remains. AgentRun
    liveness is QUEUED, RUNNING, PAUSED, or AWAITING_GATE. A detached run is
    legacy drain work unless its exact ReviewRound and ReviewSession identify
    a V2 owner Task. A cancellation-requested validation remains drain-owned
    until completion or durable supersession proves it stopped.

## Owners and boundaries

| Owner | Sole write authority |
|---|---|
| TrunkManager | Trunk lifecycle and conversation, Task creation authorization, Trunk policy, Task outcome inbox |
| TaskManager | Task lifecycle and epoch, Task assignment, branch/worktree/stable PR binding, Stage graph, Task Brain, Task automation policy |
| PlanStageManager | Plan revisions, Plan state, plan follow-ups, self-review wait, approval eligibility |
| LocalDevelopmentStageManager | Local implementation checkpoint, validation, private local review threads/batches, publish eligibility |
| RemoteDevelopmentStageManager | Accepted remote head, CI checkpoint, remote inbox and rounds, readiness, merge workflow |
| CleanupStageManager | Quiescence and cleanup checklist |
| ReviewSessionManager | PR-subject advisory review sessions, optional Workspace/Task attachment, seats, findings, guidance, and budgets |
| ExecutionDispatcher | DispatchTicket claim, execution lease/heartbeat, provider/process lifecycle, infrastructure retry, cancellation and result delivery; requests admission from CapacityManager |
| CapacityManager | Sole capacity-lease writer and policy owner for global, Workspace, Trunk, Task, control, and resource-lane admission |
| RemoteObserver | GitHub webhook/poll ingestion and delivery of immutable observations |
| Projectors | Timeline, rail, status chip, notifications, trace, cost, token and activity views |

An owner exposes commands and accepts facts. It does not call another owner's
repository.

Example: accepting a Brain verdict is one use case:

1. TaskManager accepts the verdict into the Task-owned BrainReviewEpisode.
2. LocalDevelopmentStageManager accepts the resulting approved/findings fact.
3. The use case commits both results.
4. An outbox wake is written if the new Stage state requests another Turn.

TaskManager does not update the Local Development row, and Local Development
does not update the Task Brain row.

## Domain state

### Trunk lifecycle

Trunk lifecycle remains ACTIVE, IDLE, and ARCHIVED. Trunk conversation and
Task execution are independent. A Trunk can accept a new turn or create Task B
while Task A is running.

Focus is a UI preference, not ownership. Switching the focused Task cannot
cancel or mutate sibling Tasks.

A Task cannot create or mutate a sibling Task. It may send a typed successor
proposal, follow-up, backlog item, or TaskOutcome to Trunk; Trunk decides
whether another Task is created.

### Task lifecycle

Task lifecycle contains durable business states only:

~~~text
PROVISIONING -> ACTIVE

ACTIVE -> PAUSING -> PAUSED -> RESUMING -> ACTIVE

ACTIVE | PAUSED | ARCHIVED -> CANCELING -> CLEANING -> CANCELED

ACTIVE | PAUSED | ARCHIVED -> CLEANING -> COMPLETED
ACTIVE | PAUSED | ARCHIVED -> CLEANING -> REMOTE_CLOSED

ACTIVE -> ARCHIVING -> ARCHIVED -> RESUMING -> ACTIVE
~~~

Rules:

- Task epoch starts at one.
- Cancel, replan, assignment replacement before work starts, and any other
  whole-Task invalidation increment the epoch.
- Task remains CANCELING while exact child work is stopping.
- Task remains CLEANING while required Cleanup work is incomplete.
- NEEDS_ATTENTION is a projection from open blockers, not a Task lifecycle
  state.
- IDLE, RUNNING, QUEUED, and AWAITING_REVIEW are projections, not lifecycle
  states.
- ERRORED is an operation or blocker result. Recovery restores the owning
  Stage checkpoint rather than guessing a generic Task phase.
- A failed operation leaves Task at its durable lifecycle and Stage checkpoint
  with an OPERATION_FAILED blocker. Retry replaces the operation; it does not
  retry the Task.

### Stage identity

Each Stage has:

- id
- task id
- kind
- monotonic generation per Task and kind
- optimistic version
- opened and completed timestamps
- current checkpoint state

A Stage result always carries stage id and generation. A completed Stage row
never returns to an earlier state.

### Plan Stage

~~~text
DRAFTING -> SELF_REVIEW -> AWAITING_APPROVAL -> COMPLETED
    ^             |
    +-- revision -+
~~~

- Plan content is revisioned.
- Exactly one mandatory Brain self-review applies to each candidate final
  revision.
- A material change creates another revision and invalidates approval evidence.
- Auto-approval may approve the latest reviewed revision when Task policy
  permits.
- User follow-ups are recorded against a revision.
- note-plan-concern facts and Project Stewardship direction checks are part of
  the reviewed Plan evidence.
- A stewardship exception disables automatic Plan approval and automatic merge
  until explicitly resolved.
- The first self-review execution failure may retry once as infrastructure
  recovery. A repeated failure or successful Turn with no verdict opens a
  blocker; it does not advance or repeatedly review an unchanged revision.
- Replan is a Task command: quiesce active Stage work, increment Task epoch,
  preserve history, and open a new Plan Stage generation.

### Local Development Stage

~~~text
IMPLEMENTING -> VALIDATING -> BRAIN_REVIEW -> LOCAL_REVIEW -> PUBLISHING -> COMPLETED
      ^                    |               |
      |                    v               v
      +-- ADDRESSING_BRAIN_FINDINGS   ADDRESSING_LOCAL_FEEDBACK
      |                    |               |
      +--------------------+---------------+
~~~

- Development is the only pre-promotion branch writer.
- The private local PR is created or adopted idempotently when the first
  committed, reviewable diff exists. Its exact UI-open time is not correctness
  state.
- Validation is exact-fingerprint evidence.
- Task Brain reviews; it never edits the branch.
- Development produces a typed DevReport for Brain and later handoffs.
- Local feedback is private and revisioned.
- Local Review is a repeatable gate, not a permanently completed milestone.
- Automated publish eligibility requires a clean committed worktree, at least one commit
  ahead of base, green validation for the current fingerprint, approved Brain
  evidence for the same fingerprint or an explicit unresolved escalation, no
  unsubmitted/submitted open feedback that blocks promotion, and valid
  promotion consent.
- Brain approval cannot bypass an open Brain finding/root.
- An explicit human PublishOverride may acknowledge open local feedback or red
  local validation. It records the exact blockers, reason, actor, subject and
  time. It cannot bypass a dirty/uncommitted worktree, invalid branch/base,
  stale subject, missing permission, or ambiguous authorization.

### Remote Development Stage

~~~text
WAITING_CI -> AWAITING_READY -> WAITING_REMOTE_REVIEW
     ^                               |
     |                               v
     +-- CI repair / round push -- ADDRESSING_REMOTE_FEEDBACK

WAITING_REMOTE_REVIEW -> READY_TO_MERGE -> MERGING -> COMPLETED
~~~

CI repair, remote feedback, and branch sync are child Episodes. Their history
does not create peer Stage rows or make the rail grow without bound.

- Remote Development is the only post-promotion workflow owner.
- Its subject is an accepted exact remote head SHA.
- Remote Development owns BranchSyncEpisode state; TaskManager remains the
  authority that grants the Task write lease and accepts branch/worktree
  identity changes.
- New accepted heads invalidate CI, review, readiness, and one-head merge
  evidence for older heads.
- Merge queue entry is not completion. Only observed GitHub merged truth
  completes the Stage.

### Cleanup Stage

~~~text
WAITING_QUIESCENCE -> CLEANING -> COMPLETED
~~~

The cleanup checklist is durable and idempotent. Cleanup closes only after
required steps succeed or the user explicitly resolves an allowed waiver. A
failure leaves the Stage at its current checkpoint and opens a Cleanup blocker;
there is no separate BLOCKED/AWAITING_USER Stage state.

### Blockers

Blockers preserve the checkpoint to which recovery must return. Examples:

- permission request
- local feedback draft
- user approval gate
- Brain budget exhausted
- CI-fix budget exhausted
- validation failure
- branch conflict
- external changes requested
- merge readiness failure
- ambiguous external effect
- delivery failure
- cleanup failure

Each blocker records owner type/id, subject revision, type, status, payload,
opened time, and resolution evidence.

## Conversation and Turn contract

### ThreadTurn

ThreadTurn belongs to one Trunk and cannot carry Task or Stage ids.

Typical purposes:

- user conversation
- Trunk planning
- Task creation discussion

Thread messages reference ThreadTurn.

### TaskTurn

TaskTurn belongs to one Task and cannot carry Stage identity as ownership.
It may carry a trigger Stage id and generation as immutable context.

Typical purposes:

- Plan self-review
- development Brain review
- remote-round Brain review
- Task completion summary
- Task-level analysis

Task messages reference TaskTurn.

### StageTurn

StageTurn belongs to one exact Stage id and generation. Task id is derived
through the Stage foreign key.

Typical purposes:

- implementation
- fixing Brain findings
- addressing submitted local feedback
- CI repair
- remote feedback fixes
- branch-conflict repair
- user steering

Stage messages reference StageTurn.

### ReviewAssignmentTurn

ReviewAssignmentTurn belongs to one review seat/assignment. It supports the
standalone and advisory multi-agent review product without inventing a fake
Task or Stage.

### Typed supporting records

Correctness-bearing conversation records follow the same ownership split:

- ThreadMessage, ThreadQuestion, ThreadAttachment, and ThreadCheckpoint belong
  to one Trunk/ThreadTurn.
- TaskMessage, TaskQuestion, TaskAttachment, and TaskCheckpoint belong to one
  Task/TaskTurn.
- StageMessage, StageQuestion, StageAttachment, and StageCheckpoint belong to
  one exact Stage generation/StageTurn.
- ReviewAssignmentMessage, question, attachment, and checkpoint belong to one
  ReviewAssignmentTurn.
- PermissionRequest references exactly one typed Turn plus the operation/tool
  call that requested it.

There is no nullable owner tuple and no generic latest-conversation lookup.

### Turn immutability

- Launch input is frozen at admission.
- Attachment admission freezes the exact owner, absolute local path, media
  type, and content digest in the same transaction as the typed Turn request.
  Provider launch re-verifies that digest and fails closed if the file has
  changed; attachments are provider input, not mutable prompt decoration.
- A running Turn is never edited in place.
- User input received while a Turn runs becomes another command, feedback
  revision, or queued Turn.
- Interrupt identifies one exact typed Turn. A compatibility request that
  omits the Turn id resolves one deterministic newest cancelable Trunk Turn;
  it never fans out to sibling Turns, Tasks, or Stages.
- Turn status is delivery state only: REQUESTED, QUEUED, CLAIMED, RUNNING,
  SUCCEEDED, FAILED, CANCELED, or SUPERSEDED.
- Turn success does not imply a domain transition. The owner must accept it.

## Synchronous and asynchronous contract

### Synchronous command pattern

Every user action, observation, and asynchronous result enters through a
synchronous command:

1. Load the exact owner.
2. Validate owner state, optimistic version, Task epoch, Stage generation,
   operation id, and subject revision.
3. Apply the owner's transition.
4. Create any requested Turn/Operation and outbox wake in the same transaction.
5. Commit.

Cross-domain use cases call owners explicitly in a documented order.

Command transactions are atomic. A requested asynchronous record, its
DispatchTicket, and the outbox wake commit before execution can begin.

### Asynchronous execution pattern

1. ExecutionDispatcher claims a DispatchTicket after capacity admission.
2. It leases and heartbeats the ticket.
3. It invokes an agent, local process, Git, or GitHub adapter.
4. It records raw execution evidence.
5. It submits a synchronous result command to the owning use case.
6. The owner accepts, rejects, or supersedes the result.

Infrastructure retry handles delivery failures. Domain retry consumes domain
budget only when the domain authorizes a new semantic attempt.

Delivery is at least once. Domain result acceptance is exactly once through a
unique operation id, optimistic version, and the full subject fence.

### Async families

There are seven infrastructure families:

1. Agent Turns.
2. Validation.
3. Local Git/worktree operations.
4. GitHub effects: push, PR create/update, mark-ready, replies, resolution, and
   CI rerun.
5. Remote observation: head, CI, reviews, comments, and merge queue.
6. Merge/merge-queue effects.
7. Cleanup I/O.

First push, CI repair, remote feedback, branch sync, and Cleanup are domain
protocols composed from these families. They do not get their own schedulers.

### Worker and executor contract

A Task does not retain a JVM worker while it waits. Only an admitted Operation
borrows a worker, and the worker is released when that execution attempt
finishes. Waiting for a user, permission, CI, review, a timer, capacity, or an
external observation therefore occupies neither a worker nor a capacity lease.

CapacityManager grants a durable capacity lease before ExecutionDispatcher
submits work. A requested Operation remains a durable DispatchTicket while it
waits; an executor queue is never a second workflow queue. Domain managers do
not import executors, semaphores, or thread-pool configuration.

Workspace settings and Trunk settings provide policy inputs; they never grant
capacity or write leases. The Workspace ceiling counts distinct executing Tasks
across all of its Trunks, and the Trunk ceiling counts distinct executing Tasks
inside that Trunk. Multiple admitted Operations for one already-executing Task
do not consume additional Task-count capacity, though lane, exclusivity, and
writer-lease rules still apply. A missing Workspace override uses the configured
Workspace default. A missing Trunk `maxRunningTasks` override uses the
configured Trunk default. Migration V275 preserves an existing explicit legacy
`parallel_slots` value greater than one by copying it once into that override;
admission never reads the legacy column afterward.

Executor submission failure releases the CapacityLease idempotently and leaves
the DispatchTicket durably eligible for retry.

ExecutionDispatcher uses one shared virtual-thread-per-operation workflow
executor plus one small scheduled executor for claims, heartbeats, and lease
expiry. The seven async families are logical lanes on those executors, not
seven dispatcher pools. RemoteObserver and unrelated application subsystems
may keep their own read-only or hardware-specific executors.

During LEGACY/V2 coexistence, a thin legacy admission bridge makes
AgentScheduler acquire and release the same CapacityManager leases as V2.
Separate LEGACY and V2 global counters are forbidden because they would
oversubscribe the machine. The bridge changes legacy admission only; legacy
domain coordination continues to drain through its existing path.

### Worktree writer lease

Every code- or Git-mutating adapter requires a fenced WorktreeLease containing:

- Task id
- operation id
- Task epoch
- fencing token
- lease owner
- expiry

At most one valid writer lease exists for a Task branch. Git-mutating adapters
reject missing or stale fencing tokens. Brain review, remote observation, and
read-only review seats do not take the writer lease.

### Full result fence

Every result capable of changing workflow carries:

~~~text
taskEpoch
stageId
stageGeneration
operationId
attempt
expectedCodeFingerprint
expectedHeadSha
expectedBaseSha
~~~

Only fields relevant to the operation are populated, but identity fields are
never inferred.

A mismatch produces SUPERSEDED. A late success may remain useful audit
evidence, but cannot advance state.

## Capacity and parallel Tasks

Admission order:

1. global lane ceiling
2. Workspace executing-Task ceiling
3. Trunk executing-Task ceiling
4. one mutating execution lease per Task
5. exact worktree/runtime lease

Rules:

- Count distinct executing Tasks, not Turns.
- Mutating agent work, Task Brain execution, validation, and Git mutation
  share the Task's exclusive write lease. The resulting synchronous Task or
  Stage command does not consume execution capacity.
- Read-only advisory review seats use their review lane and cannot edit the
  worktree.
- Waiting for CI, review, permission, a user gate, or a timer consumes no slot.
- Creating Task B while Task A runs is allowed. Task B is created immediately;
  its first operation may wait for admission without inventing a QUEUED Task
  lifecycle state.
- A reserved Trunk control lane keeps planning, cancellation, and permission
  handling available when worker lanes are saturated.
- Sibling Tasks have separate branch, worktree, session, turns, operations,
  budgets, blockers, and accepted remote head.
- At most one nonterminal Task owns a stable PR identity.
- No Thread.status busy gate may block a sibling Task.

At initial cutover, the shared LEGACY/V2 hard ceilings remain four CLI
executions and six API executions. One permit inside each ceiling is reserved
for Trunk control; Task-owned work cannot consume that reservation. Other
lanes, including validation and read-only review, require explicit configured
ceilings before canary and may not bypass CapacityManager.

Workspace and Trunk limits have one policy source each. V275 projects valid
legacy parallel-slots data once into thread settings and guards that setting
against non-positive values; the fields do not remain independent admission
authorities. Lowering a limit blocks new admission and lets existing leases
drain rather than canceling running work.

The old future-Task queue is not part of this contract. If product design later
requests planning future work without creating a Task, introduce a typed
TaskIntent owned by Trunk. Do not revive TaskPhase.QUEUED or a JSON queue.

Next returns UI focus to Trunk and leaves the current Task alive at its exact
wait/gate checkpoint. Only Trunk materializes another Task.

## End-to-end flow

~~~mermaid
flowchart TD
    U["User"] -->|"SYNC"| TR["TrunkManager"]
    TR -->|"SYNC TaskAssignment"| TM["TaskManager"]
    TM -->|"ASYNC provision"| D["ExecutionDispatcher"]
    D -->|"SYNC fenced result"| TM

    TM -->|"SYNC open"| P["Plan Stage"]
    P -->|"SYNC request review"| TM
    TM -->|"ASYNC TaskTurn"| D
    D -->|"SYNC verdict"| TM
    TM -->|"SYNC verdict handoff"| P
    P -->|"SYNC completed"| TM

    TM -->|"SYNC open"| L["Local Development Stage"]
    L -->|"ASYNC StageTurn / validation"| D
    L -->|"SYNC request Brain"| TM
    TM -->|"ASYNC TaskTurn"| D
    TM -->|"SYNC verdict handoff"| L
    U -->|"SYNC draft / submit feedback"| L
    U -->|"SYNC Approve and ship"| L
    L -->|"ASYNC PublishOperation"| D

    D -->|"ASYNC push / create or adopt PR"| GH["GitHub"]
    D -->|"SYNC published head"| L
    L -->|"SYNC promotion handoff"| TM
    TM -->|"SYNC open"| R["Remote Development Stage"]

    GH -.->|"ASYNC CI / review / head / queue observation"| O["RemoteObserver"]
    O -->|"SYNC exact-head facts"| R
    R -->|"ASYNC fix / validation / effects"| D
    R -->|"SYNC request Brain"| TM
    TM -->|"SYNC verdict handoff"| R
    U -->|"SYNC remote-round approval / merge consent"| R
    R -->|"ASYNC MergeOperation"| D

    GH -.->|"ASYNC merged or closed truth"| O
    R -->|"SYNC terminal fact"| TM
    TM -->|"SYNC open"| C["Cleanup Stage"]
    C -->|"ASYNC cleanup steps"| D
    C -->|"SYNC cleanup complete"| TM
    TM -->|"SYNC TaskOutcome"| TR
~~~

## Detailed protocols

### Trunk creation and Task creation

Trunk creation:

- validates Workspace ownership and name
- freezes the complete work model for plan, development, review, and CI-fix
  audiences
- records inherited policy and permission references
- creates no Task implicitly

Task creation is a synchronous Trunk-to-Task use case:

1. TrunkManager authorizes a typed TaskAssignment.
2. TaskManager creates a PROVISIONING Task with workflow version, epoch one,
   policy revision, Task sequence, and immutable assignment.
3. TaskManager requests ProvisionTaskOperation.
4. Provisioning creates or adopts the exact branch/worktree.
5. TaskManager accepts provisioning evidence and opens Plan.

TaskAssignment variants must cover:

- NEW_FROM_TRUNK with plan seed, planning-base SHA, and prompt
- EXISTING_OWN_PR with repository, PR number, and exact remote head
- REVIEW_FINDINGS with review session and selected finding ids
- ISSUE with issue identity
- AUTOMATION with producer and reason
- QUALITY_SCAN with evidence identity

An EXISTING_OWN_PR Task starts from the PR head or a proven equivalent ref. It
must not silently cut from the normal base.

Creation provenance controls the base snapshot:

- an agent-origin Task uses the exact Trunk planning snapshot approved for the
  handoff
- a direct user, issue-monitor, automation, or quality-scan Task starts from a
  freshly fetched remote base
- a fork Task branches from watched upstream and publishes through an
  owner-qualified head

Task branch/worktree invariants:

- one stable branch, worktree and PR identity per Task
- one mutating writer lease per Task
- never write local main or master
- pre-push fetch/rebase and conflict reconciliation are explicit Operations
- push kind controls autosquash policy
- rewritten history uses force-with-lease, never an unguarded force push

### Plan and replan

- The Plan Stage records a proposed revision.
- Task Brain performs one self-review TaskTurn for that revision.
- The use case records the Brain result in Task and Plan.
- If revised, the new revision requires its own self-review.
- User or policy approval applies only to the exact reviewed revision.
- Approval completes Plan and lets Task open Local Development.
- Replan first requests whole-Task quiescence. No new work is admitted.
- Once all claimed mutating work is stopped or reconciled, Task epoch advances
  and a new Plan Stage generation opens.

### Development and Brain loop

1. Local Development admits a StageTurn for implementation or exact findings.
2. The Stage accepts the Turn result only for its operation and fingerprint.
3. It requests canonical validation for the new fingerprint.
4. Green validation lets the Stage request Task Brain review.
5. Task opens BrainReviewEpisode and TaskTurn for the same fingerprint,
   DevReport, and finding set.
6. Task accepts the Brain verdict.
7. The use case hands approved/findings to Local Development.
8. Approved enters Local Review.
9. Changes requested create another bounded StageTurn.
10. Budget exhaustion opens a blocker and enters Local Review with an explicit
    unresolved escalation. It never records approved.

Brain reviews and comments. Local Development is the branch writer.

DevReport is typed and immutable for its Stage generation and fingerprint. It
includes implemented intent, commits, files, validation evidence, known risks,
unresolved concerns, and context/retrieval references. Development transcript
remains immutable; summary and deep retrieval provide later context. Reusing a
provider session is an optimization and never correctness state.

### User steering

Steer is a synchronous Stage command.

- APPEND is the default: persist the steering input and admit it after the
  current Turn.
- CANCEL_AND_REPLACE is explicit: mark the exact operation cancel-requested,
  create a replacement request, and prevent the old result from being accepted.
- Steering an inactive or completed Stage is rejected with the current owner
  and state.
- A queued Turn can be canceled durably without pretending it was running.
- Stage stream and interrupt always target exact Stage and operation ids.

### Local comments

- Adding or editing a local comment is synchronous and private.
- Send submits one exact thread immediately; Add to review saves a draft;
  Submit review freezes the selected batch.
- The first pending user draft opens a local-feedback blocker that prevents
  promotion.
- Merely creating a draft does not wake Development.
- Default submission selects pending user roots. Agent/Brain/advisory findings
  require explicit user selection before they become Development work.
- Approval with no comments remains valid when every other gate is eligible.
- Submit freezes exact CommentRevision ids, complete thread content, Stage
  generation, and code fingerprint into LocalFeedbackBatch.
- A submitted batch admits one StageTurn.
- That Turn can reply to or resolve only the revisions it received.
- A newer reply, edit, reopen, or resubmission remains pending for a later
  batch.
- Dismissal records an explicit reason and revision.
- A batch submitted during Brain review queues behind that review and starts
  when Local Review resumes.
- After fixes: validation and a fresh Brain review are mandatory before the
  promotion gate rearms.
- User feedback does not consume Brain or CI automation budget.
- A human decision to proceed with unresolved Brain escalation is a separate
  audited command; it never rewrites the Brain verdict to approved.

### User-requested agent review

There are two explicit commands:

- RequestAdvisoryReview creates or continues a ReviewSession and does not block
  the Task.
- RequestBlockingReview creates a Task-attached ReviewSession and opens an
  exact-subject blocker before work starts.

ReviewSession is primarily owned by the stable PR subject, with optional
Workspace and Task attachment metadata. It supports:

- quick review as a one-seat preset
- full multi-seat review
- exact start commit/head
- Lead and parallel reviewer assignments
- Workspace reviewer definitions/personas
- guidance, steering, messages, cancellation, resume, Continue, and Re-review
- per-seat and overall budgets
- finding add/edit/drop/arbitration, answers, dispositions, and outcomes
- scheduled Workspace review requests
- scheduled or user-requested delta review after the head moves
- remote review adoption
- manual selection, verdict, and publication
- Spawn build from review through a Trunk-owned TaskAssignment containing the
  ReviewSession id, selected finding ids, PR identity, and reviewed head

Review seats are read-only. They cannot edit a Task worktree. Selected findings
enter Local or Remote Development through an explicit immutable feedback batch.
Unselected findings remain advisory history.

For a local PR, imported review findings become private local comments. A
ReviewSession cannot publish them or manufacture a GitHub approval. One
nonterminal build Task per PR remains enforced. Review findings are resolved
from that Task's explicit outcome, not merely because a Task was created.

If a standalone external-PR review is later attached to a Task, attachment
does not silently make it blocking. The user or calling use case must choose.

### Promotion from local to remote

Approve and ship is the single promotion authority.

PublishAuthorization freezes:

- Task epoch
- Local Stage id and generation
- code fingerprint and local HEAD
- base SHA
- direct/fork routing
- branch name
- PR title/body revision
- policy revision
- consent identity
- explicit PublishOverride evidence when present

PublishOperation is a durable effect saga:

1. verify clean committed worktree and authorization subject
2. reconcile branch/base requirements
3. push branch
4. create or adopt a GitHub Draft PR
5. fetch remote detail and prove remote head
6. record remote identity and head evidence

Claims commit before I/O. Ambiguous outcomes are probed before retry. A crash
after remote success must adopt the existing effect rather than create a
duplicate.

Only commits and the approved title/body cross the boundary. Local review
threads and timeline remain stored locally.

Promotion attaches remote identity to the same stable PR aggregate. Dashboard
triage, local timeline, Brain findings, validation evidence, and narration
remain available after promotion and after Cleanup.

Local Development completes only after accepting the durable published result.
Task then opens Remote Development with that exact remote head.

### Remote observation and CI

RemoteObserver delivers immutable RemotePrSnapshot records containing:

- repository and PR identity
- head and base SHA
- draft/open/merged/closed state
- check suites and normalized CI status
- effective reviews and approval permissions
- changes-requested verdicts
- requested reviewers
- live review threads and comments
- mergeability and merge queue state
- observation revision and time

Remote Development folds snapshots synchronously.

- An observation for an old head is historical and cannot advance current
  state.
- Remote comments observed while initial CI is unresolved are persisted but
  not dispatched until the relevant head is green.
- PENDING waits and consumes no slot.
- NONE passes only when the Task's explicit CI policy permits no checks.
- Missing, queued, canceled, skipped, and neutral checks follow explicit
  repository policy; they are not silently normalized to green.
- PASSED may authorize mark-ready or enter AWAITING_READY.
- FAILED opens or continues one CiRepairEpisode for the exact head.

CiRepairEpisode:

- may authorize one CI rerun before code changes
- separates rerun count, semantic fix attempts, delivery retries, and pushes
- admits StageTurn -> ValidationOperation -> optional Task Brain review ->
  PushHead effect
- accepts the new pushed head and returns to WAITING_CI
- closes only on green CI or explicit stop
- on budget exhaustion opens an Episode/Remote Stage blocker with Extend,
  Continue with per-push approval, Manual takeover, and Stop automation choices

The last permitted push receives its CI result before exhaustion is declared.

CI failure classification is part of the Episode:

- flaky and infrastructure failures are rerun-only; they do not authorize code
  edits
- deterministic failures introduced by the Task may authorize a normal fix
- deterministic failures already present on the exact base are recorded as
  base evidence and may authorize an explicitly scoped base repair below the
  Task commits

A new monitor scan cannot reopen an exhausted Episode. Normal Resume cannot
reset its semantic budget; the user must extend or select a fallback.

### Branch sync and guard

Task owns branch/worktree authority. Remote Development owns the scheduled
BranchSyncEpisode and the remote checkpoint affected by a new head.

BranchSyncEpisode records old head, observed base, target base, policy, and
budget. It may perform:

1. fetch and compare
2. mechanical rebase
3. StageTurn for conflict repair
4. canonical validation
5. optional Task Brain review
6. force-with-lease push

Accepting the new head atomically invalidates old-head CI, remote review,
readiness, and merge authorization. Standing auto-merge policy remains a
policy, but must derive new exact-head authorization after all gates are
re-proved.

Branch sync and CI repair may be detected concurrently. The Task writer lease
admits only one branch mutation; the loser re-evaluates against the resulting
head rather than replaying its stale plan.

### Remote feedback rounds

Remote ingestion creates deduplicated RemoteInboxItem records for:

- inline review comment
- top-level issue comment
- review body
- review verdict
- requested review
- thread resolved or reopened
- head changed

A body-only REQUEST_CHANGES verdict is addressable work; it cannot remain only
a timeline event.

One reviewer batch becomes one RemoteFeedbackBatch for exact head H:

1. freeze inbox item revisions
2. triage
3. prepare fixes and reply drafts locally
4. run canonical validation
5. request Task Brain review
6. expose one all-or-nothing user gate
7. after explicit approval, durably post replies, resolve eligible threads,
   and push commits
8. accept the new head and return to WAITING_CI

All-or-nothing describes authorization and completion, not impossible external
atomicity:

- one immutable authorization covers the complete batch
- effects are ordered, claimed, probed, and retry-safe
- a crash may leave partial external progress temporarily visible
- proven completed effects are not repeated or rolled back
- recovery resumes the durable cursor without requesting approval again
- the batch completes only after every authorized effect is proven complete

The system ignores its own mirrored replies. Remote comment identity, kind, and
live resolution are authoritative. Posting and push effects are idempotent and
recoverable. New comments arriving during a round form the next batch.

Auto-approve never posts remote replies or GitHub reviews.

### Readiness, auto-approve, and auto-merge

TaskAutomationPolicy is revisioned. Changes affect future authorization and do
not silently rewrite an already claimed effect.

Policy distinctions:

| Action | autoApprove | autoMerge | Explicit per-event user action |
|---|---:|---:|---:|
| Plan approval | allowed | n/a | alternative |
| Local promotion/push | allowed only as explicit standing Task consent | n/a | alternative |
| Mark ready | allowed | n/a | alternative |
| CI-repair push | allowed within armed policy and budget | n/a | alternative |
| Branch-sync push | allowed within armed policy and budget | n/a | alternative |
| Post remote review replies/review | never | never | required |
| Request reviewers | never | never | required |
| Merge | no | allowed after fresh proof | alternative |

Policy rules:

- autoApprove and autoMerge are explicit opt-ins
- enabling autoMerge also enables autoApprove
- disabling autoMerge does not disable autoApprove
- initial green CI marks the Draft PR ready automatically unless keep-draft is
  explicitly enabled
- automatic app-gate approval evaluates the current post-review revision
- existing low-risk/small-effort eligibility remains required where configured
- a stewardship exception disables autoApprove and autoMerge until resolved
- reviewer-visible GitHub interactions remain manually gated
- auto-push streak, CI-repair budget, Brain budget, validation retry budget,
  review budget, and infrastructure retry limit are separate counters

ReadinessEvidence is exact-head and includes:

- PR open and non-draft
- CI accepted under policy
- configured write-approval threshold met
- no effective changes-requested verdict
- no unresolved live thread/comment/batch
- no blocking app-owned gate
- GitHub explicitly reports mergeable; unknown fails closed
- fresh remote observation revision

Manual merge consent creates one-head MergeAuthorization.
AutoMerge is standing policy that may create a fresh authorization only after
all readiness facts are re-proved for the current head.

For a direct merge, the authorization and MergeOperation also freeze the
user-selected `merge`, `squash`, or `rebase` method. Probe/restart recovery
cannot change it. Queue entry has no client-selected direct-merge method; the
repository's merge-queue policy remains authoritative.

MergeOperation:

- re-fetches remote truth before the effect
- never falls back from unknown merge-queue support to an unsafe direct merge
- records queue entry separately from merge completion
- may re-enqueue a bounded number of queue bounces under standing consent
- completes only when RemoteObserver reports merged

If the current user cannot merge, the product may offer a manually approved
reviewer/maintainer nudge. It never posts that nudge automatically.

Remote close is also observation-driven. Canceling a Task does not close its
remote PR unless the user explicitly requests that external effect.

Readiness notifications are projections. They are edge-triggered and
throttled, and their delivered marker resets when readiness regresses so a
later fresh transition can notify again.

### Pause, resume, retry, archive, and cancel

Pause:

1. Task records PAUSING and prevents new admissions.
2. Exact claimed operations receive cancellation/stop requests.
3. Runtime-stop facts are reconciled.
4. Task enters PAUSED with its restore checkpoint.

Resume:

- validates no old mutating operation can still be accepted
- reconciles ambiguous external effects
- restores the exact Stage/Episode checkpoint
- dispatches the parked owner: Plan Brain, validation, feedback batch, remote
  round, publish effect, or other exact operation
- does not map everything to IMPLEMENTING

Retry:

- creates a new operation id and semantic attempt
- preserves the failed operation as audit history
- requires exact owner and subject

Archive:

- allowed only when no live Turn, Episode, validation, recovery request,
  permission request, or required cleanup remains
- preserves branch/session/checkpoints needed for revival
- is reversible resource release and never runs terminal Cleanup

Cancel:

1. Task records CANCELING and increments epoch.
2. New work admission stops.
3. Child owners mark exact work cancel-requested.
4. Dispatcher stops or reconciles claimed work.
5. Late results are rejected by epoch/operation fence.
6. Task opens Cleanup with terminal reason CANCELED.
7. Sibling Tasks and Trunk remain untouched.
8. Remote PR remains unless explicit close is separately authorized.

Cancel may close the private local PR aggregate and discard unpromoted local
work because Cancel is the explicit destructive command. It never deletes
audit/history records.

### Cleanup and Task outcome

CleanupOperation contains ordered durable steps:

1. prove no new admissions
2. cancel/reconcile open Turns and Operations
3. stop and evict provider sessions
4. cancel/reconcile validation
5. seal open review batches and revoke stale authorizations
6. dismiss Task-scoped notifications and permission prompts
7. release worktree/runtime leases
8. remove worktree
9. delete local branch when policy permits
10. optionally delete remote branch for merged/closed Tasks
11. record final cleanup evidence

Each step has REQUESTED, CLAIMED, SUCCEEDED, FAILED, SKIPPED, or WAIVED state,
an idempotency key, attempt count, lease, evidence, and error.

Required local cleanup must succeed. Optional remote cleanup may be retried or
explicitly waived. Cleanup failure opens a blocker and does not falsely close
the Stage.

Cleanup never deletes the stable PR aggregate, local timeline, review history,
TaskOutcome, Stage/Turn transcripts, validation evidence, or effect audit.

When Cleanup completes, TaskManager records the final Task state and a unique
TaskOutcome containing terminal reason, PR identity, merged/closed/canceled
facts, cleanup summary, summary state, follow-up proposals, and backlog items.

TrunkManager receives TaskOutcome idempotently. A Brain-generated completion
summary is asynchronous enrichment; a deterministic fallback guarantees the
Trunk always receives a completion marker.

## Persistence contract

The target schema is domain-specific. Control state must not hide in metrics
JSON, notification payloads, prompt source strings, or generic AgentRun rows.

### Core ownership

- trunk
- task, including workflow_version, epoch, lifecycle, assignment_id,
  policy_revision, branch/worktree/remote identity
- stage, including task_id, kind, generation, version, checkpoint state
- immutable transition/audit rows for each aggregate
- plan_stage
- local_development_stage
- remote_development_stage
- cleanup_stage

### Conversations and execution

- thread_turn and thread_message
- thread_question, thread_attachment, and thread_checkpoint
- task_turn and task_message
- task_question, task_attachment, and task_checkpoint
- stage_turn and stage_message
- stage_question, stage_attachment, and stage_checkpoint
- review_assignment_turn and review_assignment_message
- review_assignment_question, attachment, and checkpoint
- agent_execution for provider session, raw status, log and accounting
- dispatch_ticket for infrastructure admission and lease
- fenced worktree_lease and capacity_lease
- outbox for reliable wakeup/result delivery

### Domain protocols

- task_assignment
- task_policy_revision
- task_terminal_intent
- task_blocker
- permission_request
- plan_revision, plan_self_review, and plan_followup
- brain_review_episode
- local_review_thread, comment_revision, and local_feedback_batch
- review_session, review_assignment, review_finding, disposition, and publish authorization
- validation_operation and validation_evidence
- publish_operation, publish_authorization, and effect_step
- remote_pr_binding, remote_pr_snapshot, CI check snapshot, and remote_inbox_item
- ci_repair_episode
- branch_sync_episode
- remote_feedback_batch
- readiness_evidence
- merge_authorization and merge_operation
- cleanup_operation and cleanup_step
- task_outcome and trunk_outcome_inbox

Not every protocol requires a new generic abstraction. Existing durable stores
may be renamed or extended when their semantics match.

## Recovery and idempotency

- Claims commit before external I/O.
- Effects have stable idempotency keys.
- Explicit user GitHub writes retain their client command id across a lost
  transport response. Repeating the command replays its durable result rather
  than creating a second authorization.
- Recovery records the matching remote-id baseline before first execution.
  Timestamp and payload equality alone never prove that a later command owns
  an already-existing comment or review.
- Unknown outcomes are probed before retry.
- Leases expire and can be reclaimed, but expired does not mean the external
  effect did not happen.
- Code-writing Turns are never blindly replayed after restart. The Stage owner
  first reconciles worktree, commit, fingerprint, and operation evidence.
- GitHub effects reconcile by repository, branch, PR identity, head SHA, payload
  identity, and remote state as appropriate.
- Cancellation records intent before process interruption.
- A late result is recorded and then accepted or superseded synchronously.
- Polling is a recovery backstop, not a transition owner.
- There is one serialization boundary per Task command. Sibling Tasks do not
  share it.

PermissionRequest is durable and contains a stable call id, exact typed Turn
and operation identity, requested capability/tool and parameters, policy
snapshot, state, answer, and answer revision. It supports one-time allow/deny,
Allow next N, always for Task, and always for repository according to the
permission cascade. A late answer conflicting with an already terminal request
is rejected and audited; it cannot apply to another Turn.

An observed remote merge or close records TaskTerminalIntent and opens Cleanup.
The external fact remains durable while Task is CLEANING. Task becomes
COMPLETED or REMOTE_CLOSED only after required Cleanup succeeds.

## Read models

The UI continues to expose familiar labels:

- Running
- Queued
- Waiting for CI
- Awaiting review
- Needs attention
- Paused
- Completed

These are projections from Task lifecycle, current Stage checkpoint, live
turns/operations, and blockers.

Stage rail, PR lifecycle, Task trace, activity, notifications, cost/tokens, and
timeline are projections. They cannot clear blockers, accept results, or
advance owners.

A promoted Trunk conversation is one compatibility read over two immutable
ledgers: retained LEGACY rows first in their positive sequence space, followed
by typed rows ordered by the Trunk aggregate version that exposed them. Typed
compatibility sequence values are the negative of that durable, JSON-safe
version. They are UI identities and exact paging cursors only; physical typed
Turn/message sequences remain positive and domain-local. Readers must not
compare the mixed values numerically, infer ownership from their sign, or use
`seq < cursor`. A tail refresh restarts an incomplete paging cursor because a
draining LEGACY child may append inside the positive prefix after promotion.

Typed provider trace is a separate read keyed by exact Trunk, ThreadTurn,
DispatchTicket, execution, and request message. Tool, thinking, and error
events have stable execution/log/event identities but no conversation
sequence, so polling or reload cannot duplicate them or make Task/Stage logs
look like Trunk messages.

The compatibility Trunk runtime projection is read-only. Conversation status
is derived only from exact Trunk planning and ThreadTurn work, with open user
waits preceding executing work and executing work preceding queued work. A
child Task cannot make the Trunk conversation RUNNING. Lifetime cost and token
totals do include every execution attempt under the Trunk, including child
Task/Stage attempts and retries, while exact `trunk_id` fencing excludes
sibling Trunks. Activity time is the maximum durable typed or retained legacy
activity and is used before list limits are applied.

Status/capability precedence is server-derived:

1. terminal outcome
2. canceling or cleaning
3. pausing, paused, archiving, archived, or resuming
4. open blocker or user gate
5. running/claimed work
6. queued/requested work
7. external wait such as CI/review
8. active/idle presentation

A lower-precedence projection cannot hide a higher-precedence safety state.

The five PR labels remain:

~~~text
local-drafted -> local-open -> remote-drafted -> remote-open -> merged | closed
~~~

They are derived from local review/promotion facts and RemotePrSnapshot, not a
second workflow coordinator.

## Existing-feature compatibility matrix

| Current capability | V2 owner/record | Contract |
|---|---|---|
| Workspace/Trunk/Task hierarchy and grouped four-Stage rail | aggregate ownership + Stage projection | Preserved and fully defined in this tracked contract |
| Zero-Task Trunk and planning conversation | TrunkManager, ThreadTurn | Preserved |
| Trunk images, exact interrupt, trace, status, activity and lifetime usage | typed attachment/Turn/trace/runtime projections | Preserved with exact ownership and no child-state leakage |
| Create Task while sibling runs | TrunkManager + TaskManager | Preserved with capacity admission |
| Workspace and Trunk parallel-Task limits | CapacityManager + persisted settings | Preserved with atomic policy resolution at admission |
| User/agent/issue/quality/automation Task origins | typed TaskAssignment | Preserved without nullable inference |
| Fresh-base, planning-snapshot and fork provenance | TaskAssignment + ProvisionTaskOperation | Preserved |
| Task branch/worktree/session/PR identity | TaskManager + fenced WorktreeLease | Preserved and strengthened |
| Mandatory Plan, self-review, follow-ups, auto/manual approval | Plan Stage + Task Brain | Preserved |
| Replan with history and runtime teardown | Task command + new Plan generation | Preserved and fenced |
| Stage stream, steer and interrupt | exact StageTurn/Operation | Preserved |
| Development/validation/Brain loop | Local Stage + BrainReviewEpisode | Preserved without coordinator ping-pong |
| DevReport and deep context handoff | immutable DevReport | Preserved |
| Private local PR and review timeline | stable PR + LocalReviewThread/Batch | Preserved across promotion/Cleanup |
| Manual publish override | audited PublishOverride | Preserved; automation forbidden |
| Quick/full/scheduled/delta agent review | PR-owned ReviewSession | Preserved |
| Spawn build from review | Trunk-owned REVIEW_FINDINGS assignment | Preserved |
| First push and Draft PR create/adopt | PublishOperation/effect steps | Preserved and crash-safe |
| Direct/fork routing | RemotePrBinding | Preserved |
| CI pending/green/red/no-check policy | exact-head RemotePrSnapshot | Preserved and made explicit |
| CI rerun/fix/budget/fallback | CiRepairEpisode | Preserved with separate counters |
| Scheduled branch guard | Remote Stage BranchSyncEpisode + Task write lease | Preserved |
| Remote comment/review-body handling | RemoteInboxItem/RemoteFeedbackBatch | Preserved; body-only verdict fixed |
| Reply/resolve/push round gate | immutable authorization + effect cursor | Preserved and recoverable |
| Auto-ready/keep-draft | TaskAutomationPolicy | Preserved |
| autoApprove/autoMerge/min approvals | policy revision + exact evidence/authorization | Preserved and exact-head |
| Merge queue bounce/retry | MergeOperation | Preserved |
| Pause/resume/retry/archive | Task lifecycle + exact blocker/operation | Preserved without generic resume |
| Durable permissions and approval budgets | PermissionRequest/policy grant | Preserved and restart-safe |
| Parallel Tasks and scope limits | CapacityManager | Preserved and enforced |
| Task worker and executor ownership | CapacityManager + ExecutionDispatcher | Temporary admitted workers; durable waits use no thread |
| Task trace/timeline/status/notifications | read-only projections | Preserved |
| Task completion summary/follow-ups | TaskOutcome + Trunk inbox | Preserved and made reliable |
| Runtime/worktree/branch cleanup | Cleanup Stage/step ledger | Preserved and made durable |
| Legacy drain and retirement diagnostics | invariant auditor + typed drain counters | Fail closed until every legacy runtime owner has drained |
| Removed future-Task queue | none | Remains removed |

## Required acceptance scenarios

The design is not implemented until all scenarios pass with restart and
duplicate-delivery variants where applicable.

1. Create a zero-Task Trunk, talk to it, and create Task A.
2. While Task A has a running StageTurn, talk to Trunk and create Task B.
3. Saturate Task lanes; Trunk cancel/planning/permission control still runs.
4. Run sibling Tasks in parallel without shared state, worktree, session, PR,
   or blocker effects.
5. Enforce Workspace/Trunk fairness without changing Task lifecycle to QUEUED.
6. Steer a queued StageTurn, including typed image attachments.
7. Append steering during a running Turn.
8. Cancel-and-replace a Turn; its late success is superseded.
9. Answer a user question on the exact originating typed Turn.
10. Pause and resume from each async family at its exact owner/checkpoint.
11. Cancel Task A during code, validation, publish, CI fix, Brain review, and
    merge while Task B continues.
12. Restart during PAUSING and complete the stop barrier exactly once.
13. Replan while validation is active; open a new Plan generation only after
    quiescence and supersede the late validation result.
14. Reject Plan approval for an unreviewed or stale revision.
15. Treat repeated/no-verdict Plan self-review failure as a blocker.
16. Brain requests changes, Development fixes, validation passes, and Brain
    re-reviews the exact fingerprint.
17. Reject Brain approval while a Brain finding/root remains open.
18. Exhaust Brain budget without recording approval.
19. Add a local comment without waking Development.
20. Submit exact local revisions; add a newer reply while the fix Turn runs;
    the stale Turn cannot resolve the newer revision.
21. Submit local feedback during Brain review; it runs afterward.
22. Explicitly override red validation/open local feedback; audit exact
    blockers and reject the same request from automation.
23. Run advisory review without blocking Development.
24. Move the reviewed head; stale advisory findings cannot affect Development
    without a fresh/import decision.
25. Run blocking review and import only selected findings as private comments.
26. Spawn one explicitly linked build Task from selected review findings and
    resolve findings only from its TaskOutcome.
27. Crash before/after branch push and before/after PR create; recovery probes
    and adopts without duplication.
28. Promote in fork mode using the correct qualified head and upstream base.
29. Verify local comments/reviews/timeline remain private and survive Cleanup.
30. Observe missing, queued, pending, neutral, skipped, canceled, green, red,
    and no-check CI under explicit repository policy.
31. Deliver CI for an old head; current Remote Stage does not advance.
32. Exhaust CI-fix budget; extend or continue with per-push approval; process
    the last push's result before declaring exhaustion.
33. Classify flaky/infrastructure failure as rerun-only and deterministic base
    failure as explicit base evidence.
34. Race branch sync and CI repair; one fenced writer wins and the other
    re-evaluates.
35. Branch sync creates a new head and invalidates CI, Brain/review, readiness,
    and merge evidence.
36. Ingest a body-only REQUEST_CHANGES review into an addressable batch.
37. Receive new remote comments while a round is gated; they form a later
    batch.
38. Crash between remote push, reply posting and thread resolution; resume the
    effect cursor without duplication or a second approval.
39. Verify autoApprove never posts review text, reviews, reviewer requests, or
    merges.
40. Verify enabling autoMerge enables autoApprove and fresh exact-head
    readiness is still required.
41. Observe merge-queue bounce and bounded re-enqueue; exhaustion opens a
    blocker instead of an immediate policy loop.
42. When the user cannot merge, offer only a manually approved nudge.
43. Answer a durable permission request late or twice without affecting a
    different Turn.
44. Cancel a Task with an open remote PR without closing the PR or deleting
    its remote branch.
45. Observe remote merge/close during a running operation; fence it and start
    Cleanup.
46. Restart after every Cleanup step; finish exactly once.
47. Fail optional remote-branch deletion; retry or waive without losing local
    cleanup evidence.
48. Deliver one TaskOutcome and one Trunk completion marker despite duplicate
    completion events.
49. Verify Cleanup preserves PR, review, timeline, transcript, validation and
    effect-audit history.
50. Verify mixed LEGACY/V2 sibling Tasks and historical traces remain readable.
51. Leave Tasks waiting for capacity, CI, review, permission, and user input;
    verify they hold no workflow worker or executing-Task capacity lease.
52. Run mixed LEGACY/V2 agent, validation, Git, and review work; verify one
    shared global/Workspace/Trunk policy is enforced with no pool or semaphore
    bypass.
53. Restart with claimed capacity and execution leases; reconcile or expire
    them without accepting two writers, and keep reserved Trunk control
    available while Task lanes are saturated.
54. Reject adapter execution without an exact CapacityLease and reject direct
    development-flow submission to a generic executor.
55. In a fresh checkout containing no ignored `docs/mockups/` files, derive
    the hierarchy, grouped rail, product journey, role boundaries, gates, and
    migration order solely from this contract and its tracked migration plan.
56. Select each direct merge method, restart before the remote effect, and
    prove that execution uses the exact frozen method and head SHA; upgrading
    an older V2 merge operation preserves its prior squash behavior.
57. Lose the HTTP response after a user comment or review command commits,
    retry with the same command id, and prove one durable action and one remote
    effect while the same terminal result is replayed.
58. Authorize two intentional identical comments within one GitHub timestamp
    second and prove the second command does not adopt the first command's
    remote id; each command produces its own exact effect.
59. After an exact Task-owned PR merges and its Remote Stage completes, post a
    top-level comment successfully; reject a review or queue mutation through
    the same terminal-owner exception.
60. Lower a Workspace or Trunk parallel-Task limit while another Task is
    attempting admission; if the settings commit wins, prove no lease is
    created from the earlier ceiling, while rollback leaves policy and wake
    version unchanged. Reject zero or negative Workspace and Trunk ceilings
    without changing either persisted policy or wake version.
61. Upgrade Trunks with missing, sparse, positive, zero, and negative legacy
    concurrency settings; preserve each valid explicit limit in the sole
    Trunk setting, normalize invalid values to inheritance, and reject later
    non-positive writes. Raise a committed limit and prove a denied V2 ticket
    is retried by ExecutionDispatcher immediately without waiting for the
    periodic scan or re-entering the settings transaction's database resource.
62. Leave LEGACY AgentRuns queued, running, paused, awaiting a gate, and in a
    standalone detached review; leave an unfinished or cancellation-requested
    LEGACY validation claim. Verify retirement remains blocked until every run
    is terminal and every validation is completed or durably superseded, while
    detached review artifacts whose exact ReviewSession owns a V2 Task do not
    enter the legacy count.
63. Create two sibling Tasks concurrently from one Trunk; serialize only the
    Trunk authorization, assign distinct monotonic policy revisions and Trunk
    versions, then let both Task bundles proceed independently.
64. Queue two Trunk Turns and interrupt one exact Turn; suppress or cancel only
    that Turn across the pending-planning and physically dispatched races. If
    no exact id is supplied, prefer the live running Turn over a newer queued
    Turn; leave the other Turn and every child Task/Stage untouched.
65. Admit a Trunk Turn with an image, restart before provider launch, and prove
    the frozen attachment is replayed exactly; changing the file after
    admission durably suppresses that exact launch, renders it terminal, and
    does not prevent the next valid recovery candidate from launching.
66. Mix retained LEGACY Trunk messages, typed Trunk messages and traces, and
    active child Tasks. Verify stable paging/reload identities, no trace or
    child-message leakage into conversation order, server-derived Trunk status,
    lifetime usage including retries, activity ordering before truncation, and
    projected Workspace card counts/spend/activity without legacy write-back.

## Patterns to preserve

Reuse and adapt:

- TaskCommandExecutor's per-Task serialization and fresh transaction boundary
- ValidationClaim's fingerprinted lease and acceptance pattern
- LocalReviewSubmission's immutable submitted batch
- TaskPushSaga's committed claims, probing, adoption, and recovery
- RoundGateSaga's ordered effect ledger and ambiguity handling
- TaskRecoveryRequest's durable audited intent
- exact push and round-gate authorization records
- worktree leases and provider session accounting

## Structures to retire

Retire after migration:

- AgentScheduler as a domain coordinator
- nullable Task/Stage identity on ThreadTurn
- TaskPhase as a cross-Stage workflow
- generic Task status as both runtime and lifecycle
- CI_FIXING, REVIEW_STAGE, REVIEW_ROUND, and BRANCH_GUARD backing Stages
- source-string switches that decide domain meaning
- generic AgentRun as workflow source of truth
- projections that mutate Task status
- latest/active Task inference
- notification payloads as gate authority
- in-memory-only pending permission requests
- immediate cosmetic CleanupStage closure
- task-flow-specific executor queues, semaphores, and raw-thread launches that
  bypass CapacityManager

Legacy pseudo-Stage rows remain immutable historical episodes/review
references. Ambiguous legacy nullable-scope Turn rows are sealed for manual
reconciliation; they are never reassigned using latest/active inference.

## Non-goals

- No generic workflow DSL.
- No event-sourced rewrite.
- No microservices.
- No distributed transaction coordinator.
- No new dependency solely for state machines.
- No future-Task queue unless separately requested.
- No big-bang migration of active Tasks.
- No stale-PR 7/14/21/28 automation in the parity migration; it remains a
  promised extension until separately designed and implemented.

## Change log

### 1.9 — 2026-07-29

- Froze typed Trunk attachments at admission and required digest verification
  again before provider launch.
- Made Trunk interruption target one exact Turn across pending planning and
  dispatched execution rather than canceling every live Trunk request, with a
  running-before-queued fallback when an older client omits the exact id.
- Defined typed provider traces as a separate exact-owner read and defined the
  runtime compatibility projection for status, activity, cost, and tokens.
- Made invalid frozen launch input terminal for its exact pending Turn while
  allowing later committed recovery candidates to continue, and extended the
  read-only projection through Workspace landing-card aggregates.
- Required concurrent Task creation to allocate policy revision and Trunk
  version inside the Trunk serialization boundary.
- Added acceptance scenarios 63–66 and the corresponding compatibility row.

### 1.8 — 2026-07-29

- Scoped Trunk promotion quiescence to live Trunk Turns. Immutable LEGACY
  Task and Stage siblings may continue draining after their Trunk promotes;
  they neither block the route switch nor gain authority over Trunk state.
- Required a complete frozen four-audience engine snapshot before promotion
  and startup repair of sparse V2 snapshots before dispatch recovery begins.
- Made the LEGACY/V2 Trunk conversation projection retain stable, disjoint
  message identities and exact paging cursors while legacy siblings append.

### 1.7 — 2026-07-29

- Made legacy retirement diagnostics count every live AgentRun status,
  standalone detached review runs, and unfinished validation claims while
  excluding detached review artifacts owned by an exact V2 Task.
- Added acceptance scenario 62 so cancellation-requested validation cannot be
  mistaken for a completed legacy drain.

### 1.6 — 2026-07-29

- Made Trunk task capacity a single-source setting and recorded V275's
  one-time projection and invalid-value repair.
- Assigned capacity-change retry scheduling to ExecutionDispatcher's owned
  maintenance executor and prohibited repository work from transaction
  completion callbacks.
- Added acceptance scenario 61 for upgrade compatibility, database guards,
  single-connection safety, and immediate V2 retry.

### 1.5 — 2026-07-29

- Added C29 to make persisted Workspace/Trunk limits atomic with durable
  capacity admission and to publish policy wakes only after commit.
- Clarified that Task-count capacity counts distinct executing Tasks rather
  than Operations and does not revoke already-running work.
- Added compatibility coverage and acceptance scenario 60 for concurrent
  limit reduction, admission, and rollback.

### 1.4 — 2026-07-29

- Added C28 to lock client-command replay and pre-effect remote-id baselines
  for explicit GitHub writes.
- Preserved exact-identity top-level comments after merge or close without
  relaxing the live Stage fence for reviews and other remote actions.
- Added acceptance scenarios 57-59 for lost responses, same-second duplicate
  payloads, and terminal-PR action boundaries.

### 1.3 — 2026-07-29

- Added C27 to preserve all existing direct merge methods as immutable,
  exact-head authorization input across restart and upgrade.
- Added acceptance scenario 56 for method fencing and historical squash
  compatibility.

### 1.2 — 2026-07-28

- Removed normative dependencies on ignored local design notes.
- Restated the preserved Workspace/Trunk/Task topology, user journey, compact
  four-Stage rail, promotion boundary, and PR projection in this tracked file.
- Added a clean-checkout acceptance scenario so parallel agents require only
  the two tracked development-flow documents.

### 1.1 — 2026-07-28

- Added C23-C26 to lock Task-to-worker semantics, CapacityManager as the sole
  admission authority, two V2 execution facilities, shared mixed-version
  ceilings, and reserved Trunk control.
- Locked one shared V2 workflow executor plus a small lease-timing executor;
  async families remain logical lanes rather than separate pools.
- Preserved the current CLI/API hard ceilings, reserved Trunk control
  capacity, and required one shared LEGACY/V2 admission bridge.
- Added pool-bypass, waiting-without-worker, and restartable-capacity
  acceptance scenarios.

### 1.0 — 2026-07-28

- Locked aggregate ownership and synchronous transition rules.
- Split ThreadTurn, TaskTurn, StageTurn, and standalone review Turns.
- Retired AgentScheduler's domain-management role in the target.
- Locked four durable Stages and child Episode boundaries.
- Added exact result fencing, remote-head evidence, and durable cleanup.
- Preserved local/Brain/remote review, auto-approval, auto-merge, branch guard,
  parallel Tasks, recovery, and standalone review contracts.
