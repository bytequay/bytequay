# Development flow migration plan

Status: **MIGRATION COMPLETE; LEGACY EXECUTION RETIRED**

Created: **2026-07-28**

Completed: **2026-07-30**

Normative contract:
[development-flow-design.md](./development-flow-design.md)

Post-migration Project Intelligence enhancement:
[project-intelligence-delivery-plan.md](./project-intelligence-delivery-plan.md)

This document records how the locked design was implemented and the permanent
cutover/maintenance rules. It cannot change a locked contract; any semantic
change must first be recorded in the normative design.

The linked Project Intelligence work may extend immutable Plan and ReviewRound
inputs and exact Plan approval evidence through the existing V2 owners. It does
not reopen this completed migration and cannot restore a retired runtime.

## Outcome

The multi-writer development flow has been replaced with typed Turns,
domain-owned state transitions, exact result fencing, a delivery-only
ExecutionDispatcher, exact-head remote workflows, and durable Cleanup without
breaking active Tasks or existing UI/API behavior.

## Migration strategy

The implementation used a versioned strangler migration. The rollout is now
complete; the rules below describe the permanent storage and routing boundary,
not an active coexistence period.

The migration added immutable routing fields:

~~~text
task.workflow_version = LEGACY | V2
trunk.turn_version = LEGACY | V2
~~~

Rules:

1. Historical rows retain their immutable LEGACY value.
2. A Task never changes workflow version after creation.
3. A Trunk changes turn version only when it has no queued/running Trunk turn.
4. Existing LEGACY Tasks, Turns, validation claims, push effects, review
   effects, and pseudo-Stages are read-only history. No runtime claims them.
5. Every new Task enters V2 through TaskCreationHandoff. Migration V277 also
   rejects any non-V2 Task insert at the database boundary.
6. A Trunk may contain historical LEGACY rows beside V2 Tasks because exact
   owner ids keep compatibility reads isolated.
7. Do not dual-write Task or Stage control state.
8. Do not copy, cancel, requeue, or resume historical legacy Turns.
9. Historical legacy data remains readable through compatibility projections.
   It does not need aesthetic backfill into the new tables.
10. Reintroducing legacy creation or execution requires a new locked design
    decision and schema migration; an application flag cannot enable it.

The rollback unit is one whole Task. A V2 Task is never interpreted by legacy
workflow code.

### Retired legacy compatibility

- V2 workers claim only typed V2 records and DispatchTickets.
- No legacy scheduler worker, admission bridge, executor, or recovery loop
  claims historical Turns or effects.
- If an existing durable effect table is reused, every claim includes
  workflow_version and rejects LEGACY ownership.
- Query services union typed V2 history with legacy history for UI and audit.
- Existing validation, push, review-gate, local-review and cleanup records stay
  attached to historical LEGACY Tasks and remain immutable.
- Remote observations can advance only an exact V2 owner.
- Historical LEGACY/V2 sibling reads under one Trunk remain an isolation case;
  they are not a mixed execution case.
- Ambiguous legacy ownership is sealed for manual reconciliation; it is never
  repaired using latest/active Task inference.
- Retired compatibility mutation ports fail closed. Historical reads never
  write a lifecycle field or wake execution.

## Operational controls

No development-flow canary or routing control remains. The former Workspace
allow-list and V2-dispatch properties are removed, as are their
ConditionalOnProperty bean gates. ExecutionDispatcher and the V2 runtime/MCP
beans are unconditional.

Behavior:

- Every new Task is V2 and workflow failures are fixed forward.
- `GET /api/development-flow/route` is a diagnostic that returns
  `v2Only=true`; it is not a toggle.
- Use typed Task/Stage pause, retry, takeover, cancellation, and Cleanup
  commands for workflow control. No property can restore LEGACY routing or
  conditionally omit V2 runtime beans.
- Auto-merge remains governed by Task policy and exact-head authorization; it
  is unrelated to legacy retirement.

### Pre-migration executor baseline (historical)

Before this migration, the application created eleven long-lived executors:

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

Spring scheduling was a twelfth execution facility and serviced all scheduled
methods with its default scheduler. Servlet, HTTP-client,
ForkJoin/common-pool, virtual-thread carrier, and process-drain threads are
framework mechanics and are not included in the eleven.

The target's “two executors” means exactly two ExecutionDispatcher-owned V2
facilities, not two executors in the whole application. Slice 3 absorbed V2
agent and validation execution. Slices 4 through 8 moved V2 planning, publish,
CI, GitHub and review work behind the dispatcher. Slices 10 and 11 removed the
domain-writing runtime projector and legacy workflow pools. CodeGraph,
checkpoint, ds4, planning-base refresh, and unrelated application/I/O work may
remain independent when they own no development-flow transition.

This historical count established why pool count alone was not an admission
guarantee. The pre-migration ownership and bypasses were:

| Facility | Former bound | Historical authority and migration disposition |
| --- | --- | --- |
| General application executor (`AsyncConfig`) | 4 core, 16 max, queue 100 | No workflow admission. Publish, round-gate, AI review and Task-producing work are Slice 3–8 bridge targets; read-only observation may remain application work. |
| GitHub I/O executor | virtual thread per submission | No workflow admission. It may remain only for read-only repository fan-out; accepted observations enter a synchronous owner command. |
| Legacy review executor | 2 running, queue 50 | Was independent from Task capacity. Legacy admission was removed; the bounded ReviewPass owner remains only for typed standalone review. |
| Shared agent runner | unbounded cached pool | `AgentScheduler` limited ordinary calls to CLI 4 and API 6 without a durable lease. The runner and scheduler are removed. |
| Validation runner and renewer | unbounded cached pool plus one timer | One claim-key guard was not a Workspace/Trunk/Task ceiling. V2 validation moved to DispatchTicket and the legacy pair was removed. |
| Planning-base refresher | 1 | Performs planning Git work outside common admission. Route V2 refresh through a typed operation. |
| Task runtime projector | 1 plus scheduled sweep | Wrote lifecycle state and woke the scheduler. It and its executor are removed. |
| CodeGraph, checkpoint and ds4 | 2, 2 and 1 | Separate subsystem or hardware work; not V2 workflow executors. |

Additional admission paths had to be migrated explicitly; replacing only
`AgentScheduler` would have left them live:

- `CliReviewRunner` owns a separate three-process semaphore. Ordinary legacy
  reviewer seats can therefore run outside the scheduler's CLI=4 count.
- `InvestigationReviewService` launches one raw virtual worker per review
  round with no global ceiling.
- Task pause teardown and the post-CI-fix commit/push path launch raw virtual
  threads from `TaskService` and `CiFixRunExecutor`.
- `AgentScheduler` and `TaskCommandExecutor` both use raw post-commit virtual
  thread trampolines. V2 replaces these with a committed outbox wake.
- Spring's 33 scheduled methods share its default single worker. Several
  scheduled methods then launch validation, review, Git/GitHub or Task work in
  independent pools; the single scheduler thread is neither an overall cap
  nor the target state owner.
- The only workflow-adjacent common-pool submission is an email-triggered PR
  refresh. It remains observation only; any accepted lifecycle fact must pass
  through RemoteObserver and the exact owner command.

Process-pipe drains, servlet/SSE plumbing, model catalog probes, CodeGraph,
project-learning, workspace maintenance and ds4 threads are not independent
Task admissions. The SQLite Hikari pool of one serializes database writes but
is likewise not a fairness or workflow-capacity authority.

## Slice map (completed)

The slices below are the implementation record, not unfinished rollout gates.

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
    S10 --> S11["11. Legacy retirement"]
~~~

During rollout, every slice was independently deployable with V2 Task creation
disabled. That rollback property is historical; migration V277 now prevents
new LEGACY Task creation.

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

### Baseline wave record

The first executable-baseline wave landed on 2026-07-28. It deliberately
preserves LEGACY runtime behavior. Migration V222 adds immutable Task workflow
routing and additive Thread turn routing, both defaulting to LEGACY, while the
existing focused suites now lock these current boundaries:

| Area | Characterization locked by the wave | Target slice |
| --- | --- | --- |
| Routing | historical upgrade, LEGACY defaults, explicit V2 inserts, invalid-value rejection, immutable Task routing, and restart | 1 |
| Task and Stage lifecycle | steering reaches the exact pending Stage, sibling Task command stripes are independent, canceled state survives a late merge, and one Task's completed addressing Turn cannot drive its sibling | 2, 5, 9 |
| Capacity and recovery | cancellation releases the CLI lane, the API lane admits six and queues the seventh, and an expired validation lease is reclaimed once after service recreation | 3, 5 |
| Brain and remote effects | a verdict from another run cannot reach the live round, stale gate proof is rejected, duplicate authorization repeats no Git/GitHub effect, and observed merged/closed truth clears standing authorization | 5, 8, 9 |

The wave also made the remaining migration gaps explicit; these are target
work, not accepted V2 behavior:

- Slice 2 and Slice 5 must add exact Task ownership, command idempotency, and
  epoch/generation fences. Current completion paths can still double-apply a
  duplicate delivery, a stale pre-cancel snapshot can race cancellation, and
  steering does not yet implement append or cancel-and-replace.
- Slice 3 must replace independent scheduler, validation, and review admission
  with CapacityManager, including Workspace/Trunk caps, fair admission, and a
  reserved Trunk-control permit. The current SQLite write pool also serializes
  production database commands even though the Task command stripes are
  independent.
- Slice 8 must fail closed while mergeability is unknown and bind readiness,
  approval, and merge authorization to an exact head SHA. Current remote PR
  facts do not yet carry enough identity for that proof.
- Slice 9 must replace immediate terminal sealing and branch reaping with a
  durable Cleanup Stage. Late merge observation after cancellation must not
  silently delete the remote branch.

The latest-50 regression audit maps the substantive lifecycle fixes to the
locked scenarios below. A mapping means current characterization or partial
support, not that the V2 scenario is complete.

| Regression area and commits | Locked scenarios |
| --- | --- |
| Explicit Turn scope and exact interrupt/permission/Plan owner (`9c8dc7a1`) | 1, 4, 6, 9, 10, 43, 50 |
| CI-fix completion accepting the coordinator's queued state (`610f946a`) | 32 |
| Atomic Stage steering persistence and failed-holder recovery (`489ed358`, `4c89bf57`) | 6, 30, 32 |
| Paused/active Task remote-close cleanup and agent release (`0e9d894b`, `4e2f7fc5`) | 45 |
| CI/merge-queue/timeline idempotence (`7ca60244`) | 30, 41 |
| Plan review evidence and preserved auto policy (`888b640e`, `d686d94e`) | 14, 15, 40 |
| Durable publish failure and exact push effects (`03732f71`, `284baae9`) | 27, 35–38 |
| Typed local-review batch, fix, validation and Brain handoff (`1cffc5ef`, `a6aa1768`, `d117cfaf`, `103640d1`) | 16, 20, 21, 49 |
| Validation cancellation and restart recovery (`440ad538`, `f75af39e`) | 11, 13, 52, 53 |
| Pause/recovery barriers and exact resume (`997ad58b`, `947ab285`, `581aa51f`) | 10, 12 |
| One durable terminal command and Task-local liveness (`2fcf8e4f`, `db16be63`, `ac23023f`) | 4, 8, 45, 48, 51 |
| Exact Brain/gate/push ownership and stale/duplicate rejection (`cebb86b4`, `284baae9`) | 10, 11, 13, 16, 31, 35–38, 45, 48 |
| Immutable LEGACY/V2 routing (`90e7f0b2`) | 50 |
| Independent Task stripes, exact steering target and canceled late-result isolation (`1d7d90f6`) | 4, 6, 11, 45 |

No commit in that audit closes scenarios 2, 3, 5, 7, 17–19, 22, 24, 26,
29, 33, 34, 39, 42, 44, 46, 47, 49 or 54. Scenarios 51–53 have only
LEGACY characterization, and scenario 54 has no implementation until
CapacityManager exists. UI-only, learning, documentation and migration-number
changes were intentionally excluded from this lifecycle map.

The shared real-store fixture, same-database service recreation, deterministic
duplicate/out-of-order delivery, ambiguous Git/GitHub probes, and strict API
compatibility snapshots are now checked in. The same fixture also drives the
persisted LEGACY Remote Development owner graph through the production phase
machine and its existing terminal Cleanup marker while retaining PR ownership.
Slice 0 is complete; durable V2 Cleanup remains correctly deferred to Slice 9.

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
- temporary shared hard ceilings of CLI=4 and API=6 across LEGACY and V2, with
  one permit in each reserved for Trunk control during coexistence
- a temporary thin legacy admission bridge so AgentScheduler used the same
  CapacityManager during mixed-version rollout
- provider session, log streaming and accounting adapters extracted from the
  current scheduler
- dispatcher restart reconciliation

Hard dependency rule:

ExecutionDispatcher may know operation kind, delivery lane, exact owner
reference and callback route. It may not know Task phase, Stage transition,
review verdict meaning, CI budget meaning, or prompt source-string meaning.

During this historical slice, LEGACY AgentScheduler kept its workflow/domain
behavior for LEGACY Tasks and only its admission boundary was bridged to
CapacityManager. Slice 11 removed both AgentScheduler and the bridge; this
paragraph is not current runtime guidance.

Acceptance gate:

- dispatcher imports no domain repositories
- crash before claim, after claim, during execution, and before result delivery
  all recover
- lease expiry cannot cause two accepted results
- queued cancellation never launches
- late success after cancellation is delivered and superseded
- Workspace/Trunk caps and sibling Task parallelism work
- a Workspace or Trunk limit committed concurrently with admission is resolved
  inside the serialized admission boundary; no stale-policy admission may land
  after the lower ceiling commits
- settings commit wakes capacity waiters, while settings rollback emits no wake
- ExecutionDispatcher schedules the V2 retry on its owned maintenance executor;
  the settings completion callback never re-enters the database connection
- Workspace and Trunk settings reject zero or negative Task ceilings before
  persistence and publish no policy wake for the rejected command
- V275 projects valid legacy `parallel_slots > 1` values once into
  `thread_settings.max_running_tasks`, normalizes invalid historical values to
  inheritance, and leaves no second Trunk admission-policy read path
- saturated worker lanes cannot block Trunk control
- waiting tickets consume no worker or executing-Task lease
- validation, review and legacy admission cannot bypass the shared ceilings
- executor submission failure releases capacity and restores durable
  dispatch eligibility
- no class except CapacityManager writes CapacityLease
- exactly two ExecutionDispatcher-owned V2 executor facilities exist

### Historical executor coexistence (retired)

- The temporary LEGACY shared agent runner sat behind AgentScheduler and the
  legacy CapacityManager bridge; both are now removed.
- V2 agent, validation, Git, GitHub, merge, and Cleanup work entered through
  DispatchTicket and the V2 workflow executor and remains there.
- The legacy validation runner and lease renewer are removed. The bounded
  ReviewPass executor may remain for its typed standalone review owner; it is
  not legacy workflow admission.
- Publish and round-gate compatibility mutation paths now fail closed. General
  application and GitHub I/O executors may serve non-workflow orchestration and
  read-only fan-out.
- CodeGraph, checkpoint, planning-base refresh, and ds4 executors remain
  independent subsystem or hardware lifecycle pools, not workflow admission.
- Spring scheduling is not workflow concurrency. Scheduled methods may
  discover, reconcile, or wake durable V2 work and must return quickly.
- The domain-writing Task runtime projector and its executor are removed.

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
- temporary Workspace allow-list (removed at permanent cutover)
- invariant auditor and operator diagnostics
- canary runbook

Historical canary order (completed):

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

## Slice 11 — legacy retirement (complete)

Goal: remove legacy execution authority while retaining readable history.

### Locked retirement decision

On 2026-07-29 the product owner confirmed that the application has no users
and no production legacy data. The product owner therefore explicitly waived
the rollout's drain-observation and retention-window preconditions and
authorized immediate retirement. There is no undefined retention window left
to complete: no clock, user count, or drain counter delays runtime removal.
Keeping any rows that happen to exist readable and immutable is a data-safety
rule, not a retention period or a legacy worker. This is a one-time cutover
decision, not a reusable rule for a future migration and not permission to
delete historical rows.

Completed runtime retirement:

- every new Task is V2 through typed TaskCreationHandoff
- migration V277 rejects non-V2 Task inserts in the database
- development-flow canary properties and ConditionalOnProperty gates are
  removed; dispatcher and V2 MCP/runtime beans are unconditional
- the route diagnostic exposes only `v2Only=true`
- AgentScheduler and its shared runner are removed
- LegacyCapacityBridge, LegacyCapacityLeaseMaintainer, and LegacySagaCapacity
  are removed
- LegacyReviewAdmission is removed
- TaskRuntimeProjector and its executor are removed
- legacy validation execution and lease-renewal pools are removed
- retired Turn, saga, and validation mutation ports fail closed and own no
  queue, worker, callback, lease, recovery loop, or state transition
- GlobalReviewRunner, HarnessDiagnosisService, LessonExtractor,
  InvestigationReviewRunner, and CliReviewRunner invoke providers
  synchronously in their caller-owned execution context
- standalone review assignments remain typed; older ReviewPass calls carry an
  exact ReviewCallContext and run synchronously in their bounded review owner
- a Workspace cherry-pick conflict remains manual and returns its retained
  worktree and conflict paths without creating legacy execution
- migration V278 freezes and atomically enforces ReviewAssignmentTurn cost
  reservations across concurrent review seats and follow-ups
- migration V279 routes manual Run tests through one exact, durable Validation
  Operation and projects an accepted result at most once
- migration V280 stores publish preflight requirements rather than claiming
  synchronous proof; Approve & ship is database-only and the dispatcher proves
  requirements before remote effects
- migration V281 gives scheduled issue/quality initiators a truthful typed
  `AUTOMATION` Plan approval command; they no longer infer legacy Task phase or
  create legacy execution
- migration V282 records the semantic identity of every visible Task-owned PR
  mutation in the durable exact-head user-remote-action ledger while preserving
  existing V270 authorizations
- migration V283 preserves the push-driven CI trigger as its own typed user
  action. Its DispatchTicket combines Local Git and GitHub lanes, requires the
  Task writer lease, freezes the worktree and code fingerprint, and advances
  the revisioned worktree subject only after proving one exact empty marker
  commit at the local, remote-branch, and PR heads
- migration V284 makes existing-PR Task creation database-only. The immutable
  assignment freezes repository route, PR number, and review selection while
  dispatcher-owned provisioning discovers and proves the exact remote subject
  under combined GitHub and Local Git admission
- migration V285 removes V2 quality-scan `CreateIssue` approval from legacy
  synchronous publishing. The approval transaction claims the exact
  notification and freezes one marker-bearing operation plus Task-owned
  GITHUB_EFFECT ticket. Dispatcher recovery probes that marker without
  repeating creation; typed database-only delivery freezes the first exact
  terminal result, records provenance once, and resolves the notification
  without TaskPhaseMachine. Identical delivery replay is accepted and a
  changed replay is rejected
- migration V286 makes V2 Task review startup database-only. One exact Task
  snapshot Operation captures the diff under exclusive `LOCAL_GIT` writer
  admission; accepted delivery re-enters TaskCommandExecutor before its fresh
  transaction creates ReviewAssignmentTurns
- migration V287 gives foreign-PR suggested-change review builds a zero-Task
  comment-only owner. Approval/discard are database-only, the approved action
  runs through one Trunk-owned exact-head GitHub ticket, and exact finding
  revisions resolve only after accepted, restart-safe delivery. Probe-only
  propagation waits retain the same semantic attempt but have a separate
  durable observation bound, and finalized records purge in explicit
  child-before-parent order. Terminal failure is visible and requires a new
  review pass/selection rather than mutating the frozen one-shot action
- migration V288 makes standalone ReviewPass publication a one-shot zero-Task
  review-Trunk effect. New review threads are born V2; historical LEGACY and
  TASK_PHASE passes reject before any write. Authorization freezes exact
  remote coordinates, reviewed head, verdict, ordered finding revisions and
  marker payload in one stable-command Trunk-owned GitHub ticket. Durable
  queued/running/failed/indeterminate/published state survives UI restart,
  accepted delivery alone finalizes findings and pass, and unfinished work
  blocks purge until explicit child-before-parent deletion is safe
- migration V289 closes the remaining taskless PR-write boundary. It adds one
  deterministic born-V2 REVIEW Trunk and immutable Trunk-owned external action
  for each exact Workspace/repository/PR command, freezes the complete cached
  base/head subject without remote I/O, and dispatches every supported write
  through the shared GitHub-effect handler. The durable projection is the only
  source of terminal UI success across restart. Taskless empty-commit CI
  triggering and historical direct AI-review/random-command publication fail
  closed instead of bypassing Task writer ownership
- migration V290 makes every historical AgentRun row immutable and rejects
  every former run-creation shape. Investigation review may insert only one
  already-terminal, hidden compatibility header required by the retained
  `review_round` foreign keys; the header has no Workspace/Trunk/Task/Stage,
  worker, claim, status transition, accounting update, Session control, or
  lifecycle role.
  ReviewAssignmentTurn and its typed Operation remain the sole execution truth
- migration V291 stores one immutable source snapshot for every typed
  ReviewRound before seat admission. It freezes the PR route, title and
  description, exact base/head and diff, changed-file manifest, and complete
  non-deleted changed-file bodies. Result delivery, guidance, deterministic
  coverage, restart, verification, and finalization load that snapshot from
  SQLite and cannot re-fetch GitHub, run Git, inspect mutable filesystem state,
  or give a typed CLI the checkout as its working directory
- migration V292 performs a forward-only canonical SQLite rebuild of
  `dispatch_ticket` to add `REVIEW_SESSION` ownership. It preserves all rows,
  explicit indexes, triggers, foreign keys, and integrity invariants rather
  than editing SQLite schema text in place
- migration V293 makes every remaining seat-admitting review command durable
  before source capture. Task-attached Continue, Re-review, answer, and
  scheduled/delta requests add a per-command TaskReviewSnapshot under exact
  Task writer admission. Standalone requests add a ReviewSession-owned
  ReviewSessionSnapshot: quick is unscoped, diff-only `REMOTE_OBSERVATION`
  lane 64; full is Workspace-only `LOCAL_GIT` + `GITHUB` lane 48 and excludes
  every same-Workspace Local Git lease without counting as a Task. Both freeze
  repository, remote PR number, base branch, PR prompt metadata, exact
  base/head, diff, capabilities, and applicable local coordinates before seat
  admission; full capture also returns complete non-deleted changed-file
  bodies for V291 persistence.
  Subject/link drift supersedes delivery, and exact ticket-shape plus
  live-ticket/terminal-cleanup guards prevent orphaned execution records.
  The same migration makes Workspace repository detach and re-clone require
  terminal V2 Tasks plus terminal Workspace DispatchTickets, and reciprocally
  rejects new Workspace tickets while the repository is detached or an active
  re-clone is queued, forking, cloning, or syncing. The service preflight gives
  an actionable conflict; database triggers decide admission races
- code-writing AgentTurns capture their immutable output head, fingerprint,
  cleanliness, frozen base, and merge base before their CapacityLease and
  writer fence end; Local and Remote result delivery consumes that evidence
  without running Git or inspecting a worktree
- idle archival and standing-consent local auto-publish discovery run as
  dispatcher-owned MaintenanceWork, not independent schedulers

Historical compatibility:

- legacy CI_FIXING, REVIEW_ROUND, BRANCH_GUARD, REVIEW, Task, Turn, AgentRun,
  validation, and effect rows remain immutable readable records
- ambiguous nullable-scope legacy Turns remain sealed and are never reassigned
  through latest/active inference
- compatibility queries may project historical rows but cannot claim them or
  write lifecycle state
- legacy source classes may remain for read models, DTO/API compatibility, or
  fail-closed adapters; source presence is not execution authority

The source tree still contains compatibility-era classes such as
TaskPhaseMachine, TaskLifecycleDriver, AutomationCoordinator,
TaskPrePushDriver, TaskRuntimeStopReconciler, TaskSchedulerConflictBridge,
CiFixRunExecutor, LocalCiFixExecutor, StageLifecycle, PlanStageService, and
ReviewPassService. They have not all been physically deleted, so this plan does
not claim otherwise. They must not create, claim, schedule, or transition new
legacy work. TaskIdleArchiver is active V2 MaintenanceWork and
InvestigationReviewService coordinates typed ReviewAssignmentTurns; neither is
legacy runtime authority. Later physical deletion is ordinary cleanup and is
not a migration-completion gate.

Acceptance gate:

- [x] scheduler-removal architecture tests pass
- [x] all new development-flow work routes solely through V2
- [x] canary properties and conditional V2 bean gates are removed
- [x] historical LEGACY Tasks remain readable
- [x] no worker or recovery path claims legacy work
- [x] retired compatibility mutation paths fail closed
- [x] database creation of a new LEGACY Task is rejected
- [x] repository detach/re-clone rejects non-quiescent V2 Tasks and tickets,
  and reciprocal database admission guards close the race without pause-all

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

## Parallel implementation (historical)

This section records the migration's coordination model. The migration is
complete; do not start new slice work from this sequence.

Use one integration owner and no more than three simultaneous implementation
agents. During the baseline wave, that means one shared-foundation agent and
two characterization-test agents. After the baseline lands, use no more than
two product-code agents and one test/audit agent. Every agent works from the
same pinned base commit in an isolated worktree and owns a disjoint file list.
The integrator alone owns migration numbering, shared routing, compatibility
adapters, and semantic conflict resolution.

A clean checkout is the portability boundary. Agent prompts may require only
these two tracked development-flow documents and tracked source/tests; files
under `docs/mockups/` are optional history and must never be an implementation
dependency.

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

## First implementation step (historical, complete)

Start with Slice 0 plus only the inert routing columns from Slice 1.

That first change should:

- add no V2 manager
- route no production Task differently
- capture the accepted current behavior and failure cases
- create the safe per-Task migration boundary

Do not start by renaming AgentScheduler or rewriting TaskPhaseMachine. Without
the executable baseline and immutable workflow routing, either change creates
another cross-cutting migration with no safe rollback.

## Cutover record and operator runbook

Before upgrading an existing installation, rehearse against an online SQLite
backup. Do not copy a running database file directly because committed data may
still be in its WAL. Keep one immutable source backup, migrate a second copy,
and compare the source hash afterward:

~~~bash
sqlite3 "/absolute/path/to/bytequay.db" \
  ".timeout 30000" \
  ".backup '/private/tmp/bytequay-pre-v277.db'"
cp -p /private/tmp/bytequay-pre-v277.db /private/tmp/bytequay-v293.db
chmod 0444 /private/tmp/bytequay-pre-v277.db
shasum -a 256 /private/tmp/bytequay-pre-v277.db
cd backend
mvn -q -Dtest=TestDevelopmentFlowBackupAudit \
  -Dbytequay.audit.db=/private/tmp/bytequay-v293.db test
shasum -a 256 /private/tmp/bytequay-pre-v277.db
~~~

The audit runner refuses the standard live-database path, targets exactly
V293, checks SQLite integrity and foreign keys before and after migration, and
requires the development-flow invariant audit to be healthy. Legacy counters
are still printed as historical diagnostics. Under the explicit no-user/no-
production-data waiver they do not gate retirement, and no counter value can
cause legacy execution to resume.

The V2 creation cutover and legacy retirement are complete. Every nonblank
Workspace id routes new Task creation to V2, and migration V277 enforces the
same boundary in storage. The former Workspace allow-list and V2-dispatch
properties have been removed. ExecutionDispatcher and V2 MCP/runtime beans are
wired unconditionally; `GET /api/development-flow/route` returns only
`v2Only=true`.

The implementation now has separate typed Trunk, Task, Stage, review, and
user-wait Turns; owner-only aggregate transitions; durable dispatch and exact
result fences; Workspace/Trunk/Task capacity admission; restart-safe local,
Brain, validation, publish, CI, remote review, merge, and Cleanup operations;
exact-head policy freshness; explicit human-authorized GitHub effects;
readiness notification/assistance; and LEGACY/V2 compatibility projections.
Existing LEGACY rows keep their immutable route only for reads. They cannot be
claimed, resumed, or mutated by an execution path.

Existing-PR Task creation is also database-only. It freezes repository route,
PR number, and any immutable review selection, then lets the dispatched
ProvisionTaskOperation discover and prove the exact remote refs and SHAs under
combined GitHub and Local Git admission. Migration V284 preserves historical
exact assignments and results while requiring exact remote-subject evidence
before a newly deferred Task can leave PROVISIONING.

Reviewed quality-scan issue proposals use the V285 durable GitHub protocol.
The source Task is already CANCELING/CLEANING/CANCELED before approval; neither
approval nor discard can transition it. A new V2 proposal fails closed unless
it owns an exact notification, Task epoch, immutable payload/marker, and
dispatcher ticket. The legacy CreateIssue branch remains reachable only for an
explicitly historical LEGACY row.

V2 Task review requests use the V286 initial snapshot protocol and V293's
per-command extension. The initial request transaction creates the placeholder
ReviewSession; initial start, Continue, Re-review, answer, and scheduled/delta
commands each freeze the exact Task code subject and persist their own
`LOCAL_GIT` DispatchTicket without reading Git or launching a provider.
Dispatcher capture holds exclusive Task writer admission; accepted delivery
re-enters TaskCommandExecutor before a fresh database transaction creates
ReviewAssignmentTurns. A stale/canceled Task, changed code subject, or changed
Task attachment supersedes the capture and admits no review seat.

Suggested-change review builds for somebody else's PR use the V287 zero-Task
comment protocol. Their immutable selection becomes a locally reviewable
proposal; Task/worktree creation fails closed. Approve and discard perform
database work only. Approval creates one Trunk-owned exact-head GitHub action,
while discard has no remote effect. Recovery uses the frozen baseline and
markers to prove one COMMENT review, exact finding revisions resolve only after
accepted success, and unfinished delivery/finalization blocks physical purge.

Standalone ReviewPass publication uses the V288 zero-Task review-Trunk
protocol. New review threads enter V2 at creation; publication never promotes
or transitions a historical route. A database-only command freezes the exact
PR sides/ref/head, verdict, finding revisions and marker-bearing payload before
one Trunk-owned GitHub ticket is dispatched. Its durable read projection lets
the UI recover queued, retryable-failure, indeterminate, published, and
terminal-failure state after restart. Only accepted delivery posts the frozen
findings and publishes the pass; terminal failure stays one-shot and directs a
new review pass, and unfinished finalization prevents physical purge.

Taskless PR writes use the V289 external-action protocol. Authorization maps
the repository to exactly one Workspace, creates or reuses the deterministic
born-V2 REVIEW Trunk, and freezes the locally cached PR base/head subject plus
semantic payload and stable command before one Trunk-owned GitHub ticket is
woken. Controllers perform no remote fallback. The UI recovers the durable
projection by PR identity. It may call an accepted authorization "queued" and
close its transport-retry key, but calls the effect published only from
terminal finalization. A missing or ambiguous Workspace repository mapping,
incomplete subject cache, taskless empty-commit CI request, and retired direct
AI-review publication all fail before GitHub I/O.

Migration V290 seals AgentRun as immutable compatibility storage. The sole new
shape is an already-terminal, hidden, ownerless review header needed by the
retained ReviewRound foreign key; it has no Session, worker, lifecycle, or
accounting authority and cannot be updated or reparented. Migration V291 then
freezes each typed ReviewRound's exact source snapshot before admitting a
seat, including frozen PR prompt metadata and complete bodies for non-deleted
changed files. Full review tools and deterministic coverage consume only those
persisted bodies and reject uncaptured paths; quick review is a one-seat typed
preset with diff-only tools. Typed CLI work starts in a neutral non-checkout
directory. The old application-executor/in-memory quick-review endpoints and
direct AI-review publication are gone. Historical Stage streams cannot create
a registry session, and ThreadRegistry's production compatibility dependency
owns no runner thread or queue.

Migration V292 rebuilds `dispatch_ticket` forward-only to add the exact
`REVIEW_SESSION` owner shape while preserving populated rows, explicit indexes,
triggers, foreign keys, and SQLite integrity. V293 adds the durable pre-seat
operation for standalone ReviewSessions and the per-command Task review
extension. Every initial start, Continue, Re-review, answer, and scheduled or
delta request now commits snapshot intent without source or provider I/O.
Quick standalone capture is unscoped `REMOTE_OBSERVATION` lane 64. Full
standalone capture is Workspace-only combined `LOCAL_GIT` + `GITHUB` lane 48,
is serialized against all same-Workspace Local Git leases, and does not count
as an executing Task. While holding that lease it captures complete bodies for
the non-deleted changed files that V291 makes the full review's only readable
source. The preparation projection exposes terminal status;
subject or owner-link drift supersedes capture, live work blocks purge, and
terminal owner cleanup removes its terminal ticket.

Workspace deletion is the explicit force-delete boundary for standalone full
reviews. It records cancellation and signals exact running snapshot/seat
handlers before a transaction-local ReviewSession purge authorization removes
the owner aggregate. The same transaction deletes the exact ticket outbox,
delivery claim, execution evidence, CapacityLease, and DispatchTicket graph
before the Workspace parent. Unknown ticket shapes and uncanceled live work
fail closed. A handler returning after purge cannot deliver or recreate
evidence because both its exact ticket and typed owner are absent; ordinary
evidence failures still surface whenever the ticket remains live.

The compatibility edge now also repairs incomplete four-audience engine
snapshots before dispatcher recovery; serializes concurrent Task authorization
inside the Trunk boundary; scopes promotion quiescence to live Trunk Turns;
unions retained and typed conversation rows with stable cursors; freezes and
re-verifies typed Trunk attachments; interrupts one exact Trunk Turn; exposes
typed provider trace separately from conversation order; and derives Trunk
status, lifetime usage, and activity without writing legacy lifecycle fields.
Child Task execution contributes to Trunk lifetime usage and activity but never
changes Trunk conversation status.

Older clients that omit an exact Trunk Turn id stop the running Turn before a
newer queued Turn. A deterministic frozen-input failure suppresses only its
reserved launch and recovery continues with later candidates. Workspace card
counts, spend, and activity are likewise calculated from the read-only typed
Task/Trunk projections rather than copied into legacy status columns.

All V2 user-authorized PR write endpoints require an `Idempotency-Key` header.
The Electron client retains one key across transport or server failure and
clears it only after success or a definitive client rejection. Identical
retries replay the stored command; changing the action, target, or payload
under the same key is rejected. Comment/review execution also freezes a
pre-effect remote-id baseline so recovery cannot adopt an older same-body
GitHub item merely because GitHub timestamps have one-second precision.

The push-driven CI control likewise retains its stable command key, but is not
implemented as `RERUN_FAILED_CHECKS`: it must work when there is no failed run.
If execution restarts after creating or pushing its exact marker empty commit,
the dispatcher resumes proof and push of that commit rather than committing
again. Git and GitHub I/O occur only after combined capacity admission and
inside the Task writer fence.

Manual Run tests use the same stable-command rule. The UI retains the key while
the durable Validation Operation is nonterminal, including an HTTP 202 wait
timeout, and releases it only after a terminal response. Approve & ship records
its authorization and promotion requirements without synchronous local or
remote I/O; dispatcher execution proves those requirements. Review turns
reserve their frozen share of the round cost cap before launch.

There is no post-cutover canary switch:

1. Inspect `GET /api/development-flow/route`; the permanent result is
   `v2Only=true`.
2. Inspect `GET /api/development-flow/diagnostics`; do not declare the runtime
   healthy unless `healthy` is true and `findings` is empty.
3. `GET /api/development-flow/legacy-drain` remains informational for database
   inspection. Its counters identify historical shapes or unexpected data;
   they are not a worker queue and no longer gate retirement.
4. Use typed Task/Stage pause, retry, takeover, cancellation, and Cleanup
   commands. There is no property-level path to pause V2 and no property-level
   path to restore LEGACY.
5. Do not delete an active Task, truncate operation tables, or change a Task's
   `workflow_version` as rollback. Use the typed pause, resume, retry, stop,
   budget-extension, takeover, or Cleanup controls.
6. Treat every historical LEGACY row as immutable. Do not manually requeue or
   relabel it; compatibility services expose reads only and mutation seams fail
   closed.
7. Do not re-register a retained compatibility class as a scheduler, state
   writer, worker, or recovery owner. Reintroducing legacy execution requires a
   new locked design and schema migration.
8. A cherry-pick conflict remains a manual retained-worktree handoff and
   standalone review assignments remain typed; neither is a reason to create a
   legacy Task or scheduler job.

Physical V2 Trunk deletion is transactionally authorized and is rejected while
any typed child operation can still execute or still has an undelivered
terminal result. This purge is a product deletion primitive, not an operational
shortcut for mutating or deleting historical legacy data.

Workspace repository detach and re-clone likewise require repository
quiescence rather than a best-effort Session pause. The command preflight and
V293 database guards reject nonterminal V2 Tasks or DispatchTickets, and the
reciprocal ticket guard prevents new work from entering after repository
replacement wins the serialized race.

## Definition of migration complete

The redesign is complete:

- [x] all new Tasks use V2, enforced by application routing and migration V277
- [x] ExecutionDispatcher and V2 MCP/runtime beans are unconditional; route
  diagnostics report only `v2Only=true`
- [x] every aggregate has one state writer
- [x] every asynchronous completion is exact and fenced
- [x] no dispatcher, observer, or projector writes domain state
- [x] multiple sibling Tasks run without shared runtime state
- [x] local, Brain, remote review, and automation policies retain parity
- [x] merge and Cleanup survive ambiguous effects and restart
- [x] no terminal Task requires manual state repair
- [x] review start, Continue, Re-review, answer, and scheduled/delta commands
  are database-only and admit seats only from accepted immutable snapshots
- [x] post-capture review tools, deterministic coverage, guidance, restart,
  verification, and typed CLI work consume only frozen DB evidence; full
  review reads complete changed-file bodies and quick review remains diff-only
- [x] Task review snapshots use exact Task writer admission; standalone quick
  and full snapshots use their locked unscoped/Workspace capacity shapes and
  never count as Tasks
- [x] migrations V292 and V293 preserve DispatchTicket integrity, fence exact
  ReviewSession ownership, expose durable preparation state, and prevent live
  or orphaned snapshot cleanup
- [x] Workspace force-delete cancels and signals exact standalone review work,
  removes its complete ticket/evidence/lease graph before the Workspace, and
  fences a non-cooperative late handler return without state resurrection
- [x] Workspace repository detach/re-clone and new durable admission are
  mutually fenced at the database boundary; no Workspace-wide pause control is
  used to fake quiescence
- [x] CapacityManager is the only development-flow admission authority and no
  executor, semaphore, raw thread, or mixed-version path bypasses it
- [x] AgentScheduler, legacy capacity bridges, the domain-writing runtime
  projector, and legacy workflow pools have no live claims and are removed
- [x] retained compatibility mutation ports fail closed
- [x] historical LEGACY rows remain readable without execution authority

The presence of legacy-named read-model or compatibility source does not make
this checklist incomplete. Only reintroduced creation, claiming, scheduling,
transition, or recovery authority would reopen the migration.

Project Intelligence delivery is tracked separately. Its unchecked work does
not change the completed status above; a violation of the V2 ownership,
admission, or snapshot contracts would require correction in the intelligence
design, not a legacy migration path. Its human Plan-adjudication extension must
reuse the Plan owner, existing AWAITING_APPROVAL checkpoint, concern records,
and approval handoff; it adds no migration Stage or execution runtime.
