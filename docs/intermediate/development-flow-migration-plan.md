# Development flow migration plan

Status: **WORKING EXECUTION PLAN**

Created: **2026-07-28**

Normative contract:
[development-flow-design.md](./development-flow-design.md)

This document describes how to implement the locked design safely. It may
change as code is discovered. It cannot change a locked contract; any semantic
change must first be recorded in the normative design.

## Outcome

Replace the current multi-writer development flow with typed Turns,
domain-owned state transitions, exact result fencing, a delivery-only
ExecutionDispatcher, exact-head remote workflows, and durable Cleanup without
breaking active Tasks or existing UI/API behavior.

## Migration strategy

Use a versioned strangler migration.

Add immutable routing fields:

~~~text
task.workflow_version = LEGACY | V2
trunk.turn_version = LEGACY | V2
~~~

Rules:

1. Existing rows default to LEGACY.
2. A Task never changes workflow version after creation.
3. A Trunk changes turn version only when it has no queued/running Trunk turn.
4. Existing LEGACY Tasks, turns, validation claims, push effects, review
   effects, and cleanup behavior finish through the legacy path.
5. New Tasks enter V2 only for allow-listed Workspaces/Trunks.
6. A Trunk may contain LEGACY and V2 sibling Tasks because exact Task ids route
   every command and observation.
7. Do not dual-write Task or Stage control state.
8. Do not copy, cancel, or requeue active legacy Turns during deployment.
9. Historical legacy data remains readable through compatibility projections.
   It does not need aesthetic backfill into the new tables.
10. If a small number of long-lived LEGACY Tasks later block retirement,
    evaluate a separate quiescent converter then. Do not build it speculatively.

The rollback unit is one whole Task. A V2 Task is never interpreted by legacy
workflow code.

### In-flight compatibility

- Legacy scheduler workers alone drain legacy queued/running Turns and effects.
- V2 workers claim only typed V2 records and DispatchTickets.
- If an existing durable effect table is reused, every claim includes
  workflow_version and cannot be claimed by both workers.
- Query services union typed V2 history with legacy history for UI and audit.
- Existing validation, push, review-gate, local-review and cleanup records stay
  attached to LEGACY Tasks.
- Remote observations route through the linked Task's immutable workflow
  version.
- Mixed LEGACY/V2 sibling Tasks under one Trunk are a required isolation case.
- Ambiguous legacy ownership is sealed for manual reconciliation; it is never
  repaired using latest/active Task inference.
- Before V2 Task creation is enabled, deploy the legacy admission bridge.
  Reconcile already-running LEGACY work into CapacityLeases, or keep V2
  dispatch paused until that work drains. New LEGACY and V2 launches always
  use the same combined ceilings.

## Operational controls

Use only two migration controls:

1. Workspace allow-list: V2 new Tasks enabled.
2. Global V2 dispatch pause.

Behavior:

- Disabling V2 creation stops expansion but does not downgrade existing V2
  Tasks.
- Pausing V2 dispatch leaves durable requests queued and lets operators inspect
  state.
- Once a V2 Task exists, workflow failures are fixed forward.
- All schema changes before cutover are additive.
- Auto-merge remains disabled during initial canary rollout.

### Current executor baseline

The current application creates eleven long-lived executors:

1. general application orchestration
2. GitHub I/O virtual threads
3. legacy review execution
4. shared agent runner
5. validation runner
6. validation lease renewer
7. CodeGraph indexing
8. checkpoint generation
9. planning-base refresh
10. Task runtime projection
11. ds4 supervision

Spring scheduling is a twelfth execution facility and currently services all
scheduled methods with its default scheduler. Servlet, HTTP-client,
ForkJoin/common-pool, virtual-thread carrier, and process-drain threads are
framework mechanics and are not included in the eleven.

The target's “two executors” means exactly two ExecutionDispatcher-owned V2
facilities, not two executors in the whole application. Slice 3 absorbs V2
agent and validation execution. Slices 4 through 8 move V2 planning, publish,
CI, GitHub and review work behind the dispatcher. Slices 10 and 11 remove the
domain-writing runtime projector and legacy workflow pools. CodeGraph,
checkpoint, ds4 and unrelated application/I/O work remain independent.

## Slice map

~~~mermaid
flowchart LR
    S0["0. Baseline"] --> S1["1. Additive spine"]
    S1 --> S2["2. Domain commands"]
    S1 --> S3["3. Dispatcher"]
    S2 --> S4["4. Provision + Plan"]
    S3 --> S4
    S4 --> S5["5. Local Development"]
    S5 --> S6["6. Publish"]
    S6 --> S7["7. Remote CI + branch sync"]
    S7 --> S8["8. Remote review + merge"]
    S4 --> S9["9. Task control + Cleanup"]
    S5 --> S9
    S8 --> S9
    S9 --> S10["10. Compatibility + canary"]
    S10 --> S11["11. Legacy drain"]
~~~

Every slice is independently deployable with V2 Task creation disabled.

## Slice 0 — executable baseline

Goal: turn the current feature contract and recent race failures into a
repeatable acceptance harness before changing ownership.

Deliver:

- scenario fixture that creates a Trunk, Task, Stage graph, worktree, local PR,
  remote PR facts, Turns, review batches, validation and authorizations
- deterministic fake agent executor
- deterministic fake Git/GitHub adapters with ambiguous-success injection
- restart harness that recreates services against the same database
- duplicate and out-of-order result delivery helpers
- compatibility snapshots for current controllers, Stage rail, Task trace,
  timeline and status labels
- one documented mapping from every recent lifecycle regression class to a
  locked acceptance scenario
- one inventory mapping every Task/review execution pool, semaphore, and raw
  thread launch to its current admission authority or documented bypass

Start with existing focused suites rather than creating a second test
framework:

- TestTaskCommandExecutor
- TestAgentScheduler
- TestPlanStageService
- TestStageSteeringService
- TestValidationClaimService
- TestBrainReviewServiceImpl
- TestReviewRoundStateMachine
- TestTaskPushSaga
- TestRoundGateSaga
- TestTaskLifecycleDriver
- TestReadyToMerge
- TestTaskTerminalSealer

Acceptance gate:

- current canonical Plan through Cleanup flow passes without manual database
  changes
- steering, cancellation, duplicate result, stale result, restart, and sibling
  Task isolation cases are executable
- no production behavior changes

## Slice 1 — additive persistence spine

Goal: establish identity and routing without switching behavior.

Deliver:

- workflow_version on Task
- turn_version on Trunk
- Task epoch
- Stage generation and optimistic version
- ThreadTurn, TaskTurn, StageTurn, and ReviewAssignmentTurn tables
- matching typed message tables or exact typed foreign keys
- typed questions, attachments, checkpoints, and PermissionRequest references
- DispatchTicket and outbox
- TaskAssignment and TaskPolicyRevision foundations
- TaskTerminalIntent and transition-audit foundations
- TaskBlocker foundation
- fenced WorktreeLease and capacity-lease foundations
- additive exact fingerprint/head/base and operation-id columns where existing
  durable stores are reused
- repositories and schema migration tests

Do not:

- route production work to V2
- change existing lifecycle transitions
- backfill active legacy Turns into typed tables
- introduce dual state writes

Acceptance gate:

- upgrade representative historical databases
- restart twice
- prove existing rows and behavior are unchanged
- prove database constraints reject ambiguous ownership
- prove each V2 Turn has one non-null exact owner

## Slice 2 — domain command ownership

Goal: create the synchronous transition boundary before asynchronous execution.

Deliver:

- V2 TrunkManager
- V2 TaskManager
- Stage base manager plus Plan, Local Development, Remote Development and
  Cleanup managers
- synchronous use-case handlers for cross-domain commands
- legal transition tables
- Task-scoped serialization extracted from TaskCommandExecutor's proven pattern
- optimistic versions, idempotent command ids, and result-fence validation
- package/architecture tests preventing dispatcher, observer, controller,
  projector, and scheduled jobs from importing state-writing repositories

Acceptance gate:

- transition-table tests cover every legal and illegal edge
- duplicate commands are idempotent
- stale epoch/generation/version commands are rejected
- each aggregate has one writer package
- no asynchronous worker is required for these tests

## Slice 3 — delivery-only ExecutionDispatcher

Goal: deliver V2 asynchronous work without domain knowledge.

Deliver:

- DispatchTicket claim, lease, heartbeat, retry and terminal evidence
- exact cancel request and stop-result delivery
- global lane limits
- Workspace and Trunk executing-Task caps
- one mutating execution lease per Task
- CapacityManager as the sole capacity-lease writer and admission-policy owner
- one shared virtual-thread-per-operation V2 workflow executor
- one small scheduled executor for claim, heartbeat and lease-expiry timing
- durable waiting in DispatchTicket rather than an executor queue
- writer fencing token required by every Git-mutating adapter
- reserved Trunk control lane
- fair admission across Workspace/Trunk scopes
- shared initial hard ceilings of CLI=4 and API=6 across LEGACY and V2, with
  one permit in each reserved for Trunk control
- a thin legacy admission bridge so AgentScheduler uses the same
  CapacityManager during mixed-version operation
- provider session, log streaming and accounting adapters extracted from the
  current scheduler
- dispatcher restart reconciliation

Hard dependency rule:

ExecutionDispatcher may know operation kind, delivery lane, exact owner
reference and callback route. It may not know Task phase, Stage transition,
review verdict meaning, CI budget meaning, or prompt source-string meaning.

LEGACY AgentScheduler keeps its workflow/domain behavior for LEGACY Tasks
during this slice. Its admission boundary alone is bridged to CapacityManager;
maintaining separate LEGACY and V2 resource ceilings is forbidden.

Acceptance gate:

- dispatcher imports no domain repositories
- crash before claim, after claim, during execution, and before result delivery
  all recover
- lease expiry cannot cause two accepted results
- queued cancellation never launches
- late success after cancellation is delivered and superseded
- Workspace/Trunk caps and sibling Task parallelism work
- saturated worker lanes cannot block Trunk control
- waiting tickets consume no worker or executing-Task lease
- validation, review and legacy admission cannot bypass the shared ceilings
- executor submission failure releases capacity and restores durable
  dispatch eligibility
- no class except CapacityManager writes CapacityLease
- exactly two ExecutionDispatcher-owned V2 executor facilities exist

### Executor migration during coexistence

- Keep the LEGACY shared agent runner behind AgentScheduler until drain, but
  route its admission through the legacy CapacityManager bridge.
- All V2 agent, validation, Git, GitHub, merge and cleanup work enters through
  DispatchTicket and the V2 workflow executor.
- Keep the legacy validation runner/lease renewer and review executor only for
  LEGACY ownership; retire each when its final live claimant drains.
- Move publish and round-gate work off the general application executor as
  their V2 slices land. The general application and GitHub I/O executors remain
  available for non-workflow orchestration and read-only fan-out.
- Leave CodeGraph, checkpoint, and ds4 executors independent; they are
  subsystem or hardware lifecycle pools, not workflow admission.
- Do not enlarge Spring's scheduling pool to create workflow concurrency.
  Scheduled methods discover, reconcile, or wake durable work and return
  quickly.
- Retire the Task runtime projector executor when projections stop writing
  Task state.

## Slice 4 — Task provisioning and Plan

Goal: make the first V2 vertical path from Trunk to approved Plan.

Deliver:

- typed TaskAssignment variants
- immutable creation context and policy snapshot
- worktree/branch provisioning Operation
- exact existing-PR adoption
- Task Brain creation and TaskTurns
- Plan revisions and exactly-one self-review evidence
- Plan concerns, follow-ups, stewardship evidence and failure blocker
- manual and policy approval
- follow-ups
- replan quiescence, Task epoch advance, and new Plan generation
- Task/Stage/Turn compatibility projections for current API and UI

Reuse:

- WorktreeService and Git adapters
- current engine snapshot and work-model resolution
- TaskCommandExecutor locking pattern
- Plan self-review prompt/tool behavior

Acceptance gate:

- zero-Task Trunk creates V2 Task
- Task B can be created while Task A runs
- existing-PR assignment starts at exact PR head
- stale provisioning result cannot activate Task
- one self-review per candidate final Plan revision
- plan edit invalidates old approval
- replan waits for quiescence and preserves prior Plan history

## Slice 5 — Local Development

Goal: replace the complete pre-promotion workflow.

Deliver:

- Local Development StageTurn execution
- exact Stage steering, append and cancel-and-replace
- canonical ValidationOperation using the existing fingerprinted claim pattern
- DevReport handoff
- Task-owned BrainReviewEpisode and TaskTurn
- bounded Brain changes/fix/validation/re-review loop
- immutable LocalFeedbackBatch based on LocalReviewSubmission
- pending-draft blocker and Submit semantics
- audited explicit-human PublishOverride
- queued feedback while Brain is active
- durable PermissionRequest
- advisory and blocking review attachment/import
- stable local PR identity and immutable DevReport
- local review eligibility projection

Reuse:

- ValidationClaim and cancellation reconciler
- LocalReviewSubmission
- Brain review prompts, verdict tools and findings
- InvestigationReview as a separate aggregate
- current Stage streaming UI contract

Acceptance gate:

- implementation -> validation -> Brain -> Local Review works
- changes_requested loops through exact StageTurn and fingerprint
- Brain budget exhaustion never records approved
- adding a draft blocks promotion but does not wake Development
- submitted Turn cannot resolve a newer comment revision
- feedback during Brain review runs afterward
- advisory review does not block; blocking review does
- human publish override is auditable and unavailable to automation
- restart and duplicate delivery variants pass

## Slice 6 — publish boundary

Goal: cross from private local work to one exact remote Draft PR.

Deliver:

- exact PublishAuthorization
- typed PublishOperation and effect steps
- direct and fork routing
- clean-worktree and commit-ahead preconditions
- branch push
- create-or-adopt GitHub Draft PR
- remote head proof
- local-only PromotionManifest
- RemotePrBinding and stable local-to-remote PR identity
- Task remote identity acceptance
- Local Development completion and Remote Development opening in one explicit
  handoff

Reuse:

- TaskPushSaga claims, effect probes, adoption and recovery
- current Git and GitHub clients
- current local PR title/body preparation

Acceptance gate:

- crash at every claim/effect/evidence boundary creates no duplicate PR
- ambiguous push/create is probed before retry
- fork head and upstream base are correct
- local comments/reviews never appear in GitHub payload
- stale authorization cannot publish
- exactly one Remote Stage opens for the accepted PR/head

## Slice 7 — remote CI and branch sync

Goal: make exact-head remote observation and repair reliable.

Deliver:

- RemotePrSnapshot including head SHA
- RemoteObserver routing by exact Task workflow version
- CI policy including explicit NONE behavior
- explicit missing/neutral/skipped/canceled check policy
- CiRepairEpisode with separate rerun, fix, push, delivery and budget counters
- flaky/infrastructure/base-failure classification
- StageTurn -> validation -> optional Brain -> push protocol
- budget extension/per-push approval/manual takeover/stop commands
- BranchSyncEpisode and force-with-lease effect
- invalidation of old-head evidence

Reuse:

- PR sync/check normalization
- CI log collection
- branch guard fetch/rebase observations
- existing validation and push effect infrastructure

Acceptance gate:

- pending waits without consuming a slot
- old-head green/red cannot advance current head
- first rerun and semantic fix counters do not collide
- last permitted push receives its result
- exhaustion blocks only the owning Episode/Remote Stage
- branch conflict repair is exact and restartable
- new branch-sync head invalidates CI/review/readiness/merge evidence

## Slice 8 — remote review, readiness, and merge

Goal: replace remote review ping-pong and terminal remote effects.

Deliver:

- RemoteInboxItem for inline comment, top-level comment, review body, review
  verdict, requested review, resolution and head change
- exact RemoteFeedbackBatch
- fix/reply draft/validation/Brain loop
- one immutable all-or-nothing authorization with recoverable partial external
  progress
- typed ordered reply/resolve/push effects
- ReadinessEvidence
- revisioned autoApprove and autoMerge behavior
- exact-head MergeAuthorization and MergeOperation
- merge queue entry, bounce and bounded re-enqueue
- merged/closed truth from RemoteObserver

Reuse:

- remote comment ingestion and identity rules
- RoundGateSaga effect ordering, probing and recovery
- current readiness predicate inputs
- current approval-permission lookup

Acceptance gate:

- body-only REQUEST_CHANGES creates addressable work
- own mirrored replies do not create a new round
- new comments during a gate form a later batch
- remote replies/reviews never auto-post
- enabling autoMerge enables autoApprove without weakening exact-head readiness
- new head invalidates prior readiness and one-head consent
- autoMerge re-proves all gates
- queue entry does not complete Task
- bounded queue bounce retains consent only under policy

## Slice 9 — Task controls, Cleanup, and outcome

Goal: make lifecycle interruption and terminalization honest.

Deliver:

- pause/stop barrier and exact resume checkpoint
- retry with new operation identity
- archive/revive preconditions
- cancel epoch advance and child cancellation
- Cleanup Stage and ordered CleanupOperation
- required/optional cleanup policy
- restart-safe cleanup claims and probes
- unique TaskOutcome
- idempotent Trunk outcome inbox
- asynchronous Brain summary enrichment plus deterministic fallback

Reuse:

- TaskTerminalSealer responsibilities as Cleanup steps
- TaskRuntimeStopReconciler evidence model
- validation cancellation reconciliation
- WorktreeService cleanup primitives
- TaskCompletionAnnouncer summary behavior

Acceptance gate:

- pause/resume/retry/cancel from every major Stage checkpoint
- cancellation never affects sibling Tasks
- open remote PR remains open on ordinary Task cancel
- restart during every cleanup step completes exactly once
- optional remote branch deletion can retry or be waived
- Task becomes terminal only after required cleanup
- Cleanup preserves PR, review, timeline, transcript and audit history
- duplicate completion delivers one TaskOutcome and one Trunk marker

## Slice 10 — compatibility and canary

Goal: prove product parity before expanding V2.

Deliver:

- controller and DTO adapters for LEGACY and V2
- union history queries for typed and legacy Turns
- Stage rail and Task status projections
- timeline, trace, notification, activity and cost/token projections
- Workspace allow-list
- invariant auditor and operator diagnostics
- canary runbook

Canary order:

1. one internal Workspace
2. manual approval only
3. Plan + Local flow
4. first push and CI observation
5. CI repair
6. remote review rounds
7. manual merge
8. autoApprove
9. autoMerge
10. consecutive unattended Tasks

Acceptance gate:

- existing frontend/API contract suite passes for LEGACY and V2
- mixed LEGACY/V2 siblings are isolated
- no manual database/status repair during the agreed canary batch
- invariant auditor reports zero multi-owner or stale-result transitions

## Slice 11 — legacy drain and retirement

Goal: remove legacy coordination only after it has no live responsibility.

Preconditions:

- V2 creation enabled for all intended Workspaces
- zero nonterminal LEGACY Tasks
- zero queued/running/claimed LEGACY Turns and effects
- retention window completed
- historical read compatibility proven

Remove:

- AgentScheduler domain callbacks and source-string switches
- TaskPhase workflow coordination
- TaskLifecycleDriver transition writes
- AutomationCoordinator transition writes
- pseudo-Stage creation
- generic AgentRun workflow authority
- projection writes
- latest/active Task inference
- stale queue_task/reorder_queue/drop_queued_task exposure
- legacy CapacityManager bridge and task-flow-specific validation/review
  executors after their final claimants drain

Legacy CI_FIXING, REVIEW_ROUND, BRANCH_GUARD and REVIEW pseudo-Stage rows remain
immutable historical records. Ambiguous legacy nullable-scope Turns are sealed
for manual reconciliation and never reassigned by latest/active inference.

AgentScheduler may be deleted only after its useful provider launch, log,
heartbeat, lane, and accounting code has moved behind ExecutionDispatcher.

Acceptance gate:

- scheduler-removal architecture tests pass
- all canonical and fault-injection scenarios run solely through V2
- historical LEGACY Tasks remain readable
- no database query or code path claims legacy work

## Pull request boundaries

Each Slice may require several PRs, but a PR must have one migration or
behavioral purpose. Recommended boundaries:

1. schema plus repository constraints
2. command/state machine plus unit tests
3. dispatcher/executor adapter plus fault tests
4. compatibility projection
5. workflow enablement flag

Do not combine schema foundation, a full Stage migration, and legacy deletion
in one PR.

Every implementation PR must name:

- locked contract IDs it implements
- current behavior it preserves
- stale/duplicate/restart scenario covered
- feature flag or routing boundary
- rollback behavior

## Parallel implementation

Use one integration owner and no more than three simultaneous implementation
agents. During the baseline wave, that means one shared-foundation agent and
two characterization-test agents. After the baseline lands, use no more than
two product-code agents and one test/audit agent. Every agent works from the
same pinned base commit in an isolated worktree and owns a disjoint file list.
The integrator alone owns migration numbering, shared routing, compatibility
adapters, and semantic conflict resolution.

Start in parallel only after these two documents are committed:

1. one agent owns the atomic workflow-version migration and its migration test
2. one agent adds Task/Stage lifecycle characterization in existing tests
3. one agent adds scheduler/capacity characterization in existing tests

The first two test agents make no production edits. After the atomic migration
lands, reuse that agent slot for Brain/review/publish/terminal characterization.
Merge routing first, then lifecycle, capacity, and remote/terminal baselines.

Slice 1 has one persistence/schema owner. After its shared records and
contracts land, Slice 2 domain commands and Slice 3 dispatcher/capacity may
run in parallel in new V2 packages. Slices 4 through 9 integrate in behavioral
order even when records, adapters, projections, and tests within one slice are
developed concurrently.

## Test strategy

Use three layers:

1. Pure transition tests for every owner.
2. Real-database command and operation tests with duplicate/out-of-order facts.
3. End-to-end fake-adapter scenarios with restart at every external-effect
   boundary.

Avoid time-based sleeps. Persist clock/lease inputs and drive time explicitly.

At minimum, every multi-effect saga is tested with failure:

- before claim
- after claim, before I/O
- after remote/local success, before evidence
- after evidence, before owner acceptance
- after acceptance, before next wake
- during cancellation
- after restart

## First implementation step

Start with Slice 0 plus only the inert routing columns from Slice 1.

That first change should:

- add no V2 manager
- route no production Task differently
- capture the accepted current behavior and failure cases
- create the safe per-Task migration boundary

Do not start by renaming AgentScheduler or rewriting TaskPhaseMachine. Without
the executable baseline and immutable workflow routing, either change creates
another cross-cutting migration with no safe rollback.

## Definition of migration complete

The redesign is complete when:

- all new Tasks use V2
- every aggregate has one state writer
- every asynchronous completion is exact and fenced
- no dispatcher/observer/projector writes domain state
- multiple sibling Tasks run without shared runtime state
- all local, Brain, remote review and automation policies retain parity
- merge and cleanup survive ambiguous effects and restart
- no terminal Task requires manual state repair
- CapacityManager is the only workflow admission authority and no executor,
  semaphore, raw thread, or mixed-version path bypasses it
- legacy workflow code has no live claims and is removed
