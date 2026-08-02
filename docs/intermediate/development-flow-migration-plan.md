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
- exact-owner CLI continuation from `agent_execution.provider_session_id`,
  selected and frozen by the domain owner from the causally latest compatible
  predecessor, with a complete durable fallback and no replay after unknown or
  ambiguous provider work
- one fresh-process resume attempt per admitted CLI Turn, current owner MCP
  rebinding, provider-specific missing/expired-session fallback at most once,
  stdin delivery for potentially large Codex reconstruction prompts, and
  immediate durable process-attempt registration before prompt delivery
- provider stream frames retained as UI/log evidence while owner `finalText`
  comes only from Claude's terminal result or the last Codex agent message,
  including an intentionally empty final message
- frozen Codex cumulative input/output baselines, immutable raw terminal
  cumulative totals, and per-Turn delta accounting
- fail-closed noninteractive catalogs for Task completion summaries, remote-CI
  and branch-sync Brain verdicts, and evidence-only ReviewAssignmentTurns
- delivery-claim eligibility that requires positive exact-current terminal
  `agent_execution` evidence, including the complete durable result fence and
  payload, before an Agent `RESULT_PENDING` ticket can enter owner delivery
- replacement execution and reconciliation claims fenced until every earlier
  execution row for that ticket is terminal
- dispatcher MaintenanceWork that finalizes execution evidence from an
  already-durable result without rerunning the provider, handler, or effect
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
- resumed Codex usage subtracts the exact frozen baseline once; missing
  ordinary-source totals force fresh reconstruction, regressing totals fail
  without replay, and raw terminal totals seed only the next exact continuation
- process-attempt persistence and current-PID replacement commit before every
  prompt; registration failure stops the child, and bounded fallback retains
  both sequential attempts while current PID moves atomically to the fallback
- progress/tool-round assistant frames cannot be concatenated into a strict
  typed owner result for either CLI provider
- a crash after Agent ticket `RESULT_PENDING` but before execution-evidence
  finalization cannot claim delivery or rerun provider work; maintenance
  finishes evidence once, then ordinary delivery runs once
- a crash after due `RETRY_WAIT` or due `RECONCILE_WAIT` cannot admit a
  replacement attempt until maintenance closes the abandoned evidence without
  rerunning work; a retry with a known infrastructure failure records
  `FAILED`, while ambiguous reconciliation or an already-overtaken attempt
  records `UNKNOWN`
- V312 terminalizes unfinished historical evidence only beneath an exact
  terminal `AGENT_TURN` ticket with an explicit recovered-evidence marker and
  no result redelivery; superseded prior attempts and ambiguous terminal
  failures become `UNKNOWN`, allowing canceled-Task quiescence to reach Cleanup

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
- manual and policy approval against the resulting canonical
  TaskAutomationPolicy revision
- Plan overlay reads/writes for that canonical revision, with serialized client
  mutations and `autoMerge => autoApprove` normalization before approval
- Claude `PLAN_DRAFT` / `PLAN_SELF_REVIEW` permission-prompt argv bridge for
  the exact result gate even when the generic runtime catalog is empty
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
- Claude Plan permission callbacks are frozen in argv for `PLAN_DRAFT` and
  `PLAN_SELF_REVIEW` even with an empty generic catalog; they auto-allow only
  the exact purpose-matching Plan result tool, preserve its input, expose no
  added capability, and never create a user wait
- a failed Plan draft leaves Task ACTIVE and Plan DRAFTING; its exact typed
  Retry durably replaces the failed TaskTurn, resolves only its matching
  blocker, and never routes through Task Resume or reuses the failed session
- the V2 compatibility read parses the canonical structured Plan JSON shape
  (`understanding.summary`, `intent.summary`, ordered `intent.steps`,
  `intent.validationStrategy`, and `intent.expectedFilesChanged`) into the
  existing approval card, retains the historical Markdown fallback, gates
  `awaiting` on the exact approved review, and keeps the card `locked` after
  Local Development becomes current; neither format can hide its actionable
  steps or Brain policy controls
- the Plan overlay reads and writes only the canonical revisioned Task policy;
  it waits for all policy responses before sending approval, approval carries
  the resulting revision, and a failed/stale mutation sends no approval
- enabling auto-merge commits auto-approval in the same policy revision;
  policy redrive and manual approval cannot both advance the same waiting Plan
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
- one bounded, application-tool-free `DEVELOPMENT_BRAIN_RESULT_REPAIR` TaskTurn after the
  original and one ordinary retry both return malformed Development Brain
  results
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

- implementation -> validation -> Brain -> Local Review works; exact approved
  or budget-exhausted Brain acceptance atomically advances the stable
  Task-owned PR from `local-drafted` to `local-open` in the same Task
  transaction, fails closed as one boundary, and is idempotent on replay
- changes_requested loops through exact StageTurn and fingerprint
- a Local Development `approval_prompt` resolves only the exact V2 Task policy
  and typed Turn/Operation/Stage runtime; retained `ThreadService` budget,
  legacy Task phase/auto-approve, and generic `AgentRun` state are never read
  or mutated on the typed path; a missing selected V2 policy denies before any
  allow rule or durable user wait
- exact in-worktree edits use typed Stage runtime authority; typed
  auto-approve returns the original input for other eligible prompts, and
  without it one exact durable PermissionRequest is created. Duplicate
  callbacks/answers cannot create a second wait, successor Turn, Operation, or
  ticket
- typed Stage `run_checks` resolves only the exact active Turn worktree; a
  missing typed worktree fails closed and cannot fall back to nullable legacy
  Task metadata
- a malformed terminal Stage result remains fenced in `RESULT_PENDING` and is
  recoverable through one explicit exact CANCEL_AND_REPLACE command carrying
  the projected predecessor StageTurn id only after typed protocol-failure
  classification and positive exact terminal-success execution proof. Plain
  delivery/infrastructure errors and incomplete execution proof remain normal
  delivery retries. Its stable Stage+predecessor command
  identity supersedes the predecessor before decoding; after exact provider,
  process, delivery-claim, capacity-lease, and worktree quiescence is proven,
  it reconstructs the successor from the predecessor's complete frozen launch,
  ordered durable trace, and protocol-failure evidence, strips failed-session
  resume/cumulative fields, rebinds the current typed MCP endpoint, swaps the
  pending fence, and starts fresh without waiting for the rejected
  `RESULT_PENDING` ticket to become terminal. It is idempotent across command
  replay and late redelivery without canceling the admitted successor
- an accepted `FAILED` current Local StageTurn leaves its Stage generation and
  checkpoint unchanged, clears only its pending fence, and opens one exact
  Stage-owned `OPERATION_FAILED` blocker tied to that failed Turn. Explicit
  Retry admits one fresh StageTurn, Operation, and ticket from the complete
  frozen launch context plus durable trace with no failed-session resume,
  resolves only that blocker after admission, and remains idempotent across
  replay, restart, and late predecessor delivery. Automatic provider-quota
  waiting and cross-provider fallback remain out of scope pending a separate
  design decision
- forward reconciliation of already-accepted Local Stage failures preserves
  every ordered execution-attempt trace, reuses the exact single blocker, and
  database-fences ordinary Local StageTurn admission until Retry arms the
  successor; the Stage UI likewise disables ordinary steering while either
  recovery projection is active
- Brain budget exhaustion never records approved
- Local Development Brain launch input requests its exact strict JSON
  `TaskTurn` verdict as the final response and never refers to the retired
  legacy verdict tool
- a provider-successful but malformed Development Brain result is consumed as
  an accepted typed protocol failure: its exact TaskTurn and episode fail, only
  the Task Brain fence clears, and one Task-owned blocker names the failed Turn
  and triggering Stage/code subject. Explicit Retry admits one fresh fenced
  Brain episode/TaskTurn/ticket from frozen context without failed-session
  reuse, assigns a new storage/execution ordinal with explicit
  `consumes_budget=0` lineage to the same logical budget attempt, resolves only
  that blocker after admission, and is idempotent across replay and late
  delivery
- if that single ordinary retry is also provider-successful but malformed, its
  episode remains at one result-repair cursor while exactly one durable
  `DEVELOPMENT_BRAIN_RESULT_REPAIR` TaskTurn freezes the source raw
  output/digest, both failure identities, required shape, and current
  owner/code fence. It starts without ByteQuay/MCP application tools,
  repository source payload, permission callback, resume, mutation/wait
  authority, writer lease, or semantic Brain-budget charge. Its frozen working
  directory stays read-only and its prompt forbids provider-native reads. The unchanged strict decoder accepts one valid
  reconstruction and continues the verdict flow; stale, failed, or malformed
  repair delivery opens one exact manual blocker and cannot create a second
  repair, repair-of-repair, provider fallback, or automatic retry loop
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
- successful result delivery records remote identity through the stable PR and
  RemotePrBinding, advances the Task only through TaskManager, and performs no
  legacy TaskStore metadata write or full-row Task save
- exactly one Remote Stage opens for the accepted PR/head

## Slice 7 — remote CI and branch sync

Goal: make exact-head remote observation and repair reliable.

The approved provenance and deterministic-repair hardening below is
implemented. Every item under "Required tests before this extension is
complete" has a checked-in suite and passes in the full backend run recorded
under the verification checkpoint.

Deliver:

- RemotePrSnapshot including head SHA
- RemoteObserver routing by exact Task workflow version
- old-Task-epoch provider success terminalized as one `SUPERSEDED` raw receipt
  with no snapshot, CI evaluation, accepted pointer, or lifecycle fold
- V310 schema support and exact forward reconciliation for stale successful
  observation tickets already stranded at `RESULT_PENDING`
- CI policy including explicit NONE behavior
- explicit missing/neutral/skipped/canceled check policy
- CiRepairEpisode with separate rerun, fix, push, delivery and budget counters
- flaky/infrastructure/Task/base-failure classification
- explicit `TASK_BRANCH_REPAIRABLE` classification for complete exact evidence
  whose ownership is mixed or non-unanimous; it authorizes only append-only
  current-Task repair and never a base-history rewrite
- CI-repair StageTurn -> validation -> direct push protocol, with no Task Brain
  review or verdict
- budget extension/per-push approval/manual takeover/stop commands
- BranchSyncEpisode and force-with-lease effect
- invalidation of old-head evidence
- exact failed-result acceptance for historical `REMOTE_CI_BRAIN_REVIEW` and
  current `BRANCH_SYNC_BRAIN_REVIEW`: consume one immutable raw failure,
  terminalize its TaskTurn and repair-Brain Operation, and keep the parent Episode at its
  Brain cursor with one exact recovery blocker
- one explicit idempotent BranchSync-only Retry that reconstructs a fresh
  TaskTurn, Operation, and DispatchTicket from frozen context, carries no failed
  CLI resume or cumulative baseline, consumes no semantic repair budget, and
  fences every late predecessor delivery; historical CI Brain failures project
  no replacement action
- one versioned, immutable typed CI provenance record for the exact Remote
  Stage generation, accepted evaluation/snapshot, head/base, provider/check
  identity and profile, actual tested subject and SHA, completeness, and stable
  failure fingerprints; every incomplete or mismatched form fails closed as
  `UNKNOWN`
- narrow schema-v4 proof for a dependency-only GitHub Actions aggregate using
  the workflow blob at the exact tested SHA, exact run attempt, complete
  attempt-scoped job set, unique static job mapping, literal `needs`, and one
  recognized result-only fan-in whose declared runtime step is the only failed
  aggregate step; it may inherit only the unanimous strict
  classification of all failure-requiring dependencies; the only supported
  matrix mapping is one literal-prefix/suffix `${{ matrix.<identifier> }}`
  name template whose complete runtime instances map uniquely
- immutable same-subject `UNKNOWN` supersession that first quiesces predecessor
  work, copies all counters and remaining budgets to exactly one proven
  successor, and resolves the old blocker only after that successor is durable
- a fresh typed authorization per deterministic repair attempt, bound to the
  exact evaluation, snapshot, head/base, original Task-commit manifest, and
  latest automation policy or exact blocker decision; it is consumed only
  after an accepted push and cannot be reused by a retry
- Task-owned repair through StageTurn, validation, and a direct normal push to
  the exact named Task head remote, with no CI Task Brain Turn
- base-owned repair through a tip-only StageTurn, deterministic HistoryRewriter
  placement below the frozen Task manifest, validation, and an exact named
  expected-old-head force-with-lease push with no CI Task Brain Turn or fallback
  and no direct base-branch write
- typed merge-queue capability on every immutable RemotePrSnapshot; a valid
  GraphQL queue or entry proves `SUPPORTED`; GraphQL null/null requires a
  complete `GET /repos/{owner}/{repo}/rules/branches/{branch}` read for the
  exact base branch, where `merge_queue` presence proves `SUPPORTED` and
  absence proves `UNSUPPORTED`; every failed, incomplete, malformed, or
  mismatched proof remains `UNKNOWN` and cannot authorize either merge mode
- failed base-rewrite proof remains immutable audit evidence but cannot become
  the current repair subject; subject admission independently requires a
  `PASSED` rewrite result from a succeeded Operation
- exact DispatchTicket mapping for base-history rewrite validation, including
  its dedicated operation/callback pair and mandatory Task writer lease
- typed rerun, manual-takeover, Stop, policy-consent, and blocker-consent
  handlers; payload labels and raw log markers are never command or provenance
  authority

Reuse:

- PR sync/check normalization
- CI log collection
- branch guard fetch/rebase observations
- existing validation and push effect infrastructure

Acceptance gate:

- pending waits without consuming a slot
- observation context and required-check reads never nest connection acquisition
  in the production single-connection pool
- the Remote Observation maintainer re-arms only the same exact current parked
  read-only ticket after the polling interval; it creates no new Operation or
  semantic attempt and does not weaken generic effect reconciliation bounds
- a successful old-epoch observation completes with superseded delivery
  acceptance and immutable raw receipt but no RemotePrSnapshot/CI evaluation;
  replay is idempotent and cannot create terminal intent or Cleanup
- V310 reconciles only a fully matched Task/Stage/generation/Operation/ticket
  fence whose stored Task epoch is no longer current; current, incomplete, or
  mismatched historical rows fail closed instead of being inferred from latest
  state
- GitHub adapters emit canonical `CHECK_RUN`; only the known previously durable
  `GITHUB_CHECK_RUN` alias is normalized on replay, while unknown kinds fail
  closed
- old-head green/red cannot advance current head
- a classification/effect blocker keeps one exact-head CI Episode live; later
  polls neither create another Episode nor start another repair arm, while
  exhausted and explicitly stopped/taken-over subjects remain suppressed
- V302 resolves orphaned open blockers owned by the former automatically
  stopped Episodes without deleting immutable Episode history
- first rerun and semantic fix counters do not collide
- last permitted push receives its result
- exhaustion blocks only the owning Episode/Remote Stage
- branch conflict repair is exact and restartable
- a terminal historical CI-repair or current branch-sync Brain provider failure
  is accepted once instead of remaining `RESULT_PENDING`; its Turn/Operation
  terminalizes and its Episode and Brain cursor remain current. Historical CI
  projects one exact blocker without Retry; BranchSync projects Retry
- replaying BranchSync Retry creates one fresh successor only, resolves the blocker only
  after the replacement fence is durable, does not resume the failed CLI
  session or inherit its cumulative baseline, and consumes no semantic repair
  budget or attempt authorization; branch recovery advances only the fresh
  TaskTurn execution ordinal while preserving the Brain step's exact
  `attempt_count` and `attempt_limit`
- late, duplicate, changed, and stale predecessor deliveries cannot overwrite
  the successor or change the Episode cursor, budget, authorization, or result
- new branch-sync head invalidates CI/review/readiness/merge evidence
- only complete supported typed provenance classifies a failure; missing,
  duplicate, partial, mixed-profile, unsupported-schema, mismatched, wrong-SHA,
  unverified synthetic-merge, and raw-text cases remain `UNKNOWN` and cause no
  code mutation
- publish handoff freezes `DEFAULT_REPOSITORY_CI_POLICY_V1` as the complete
  matrix: NONE/MISSING/QUEUED/PENDING wait, PASSED/SKIPPED are accepted, and
  FAILED/NEUTRAL/CANCELED fail; replay proves every immutable outcome and
  skipped conditional jobs cannot prematurely open CI repair
- the Remote owner forward-appends that default exactly once for an active
  binding whose latest policy is the obsolete built-in
  `PUBLISH_HANDOFF_FAIL_CLOSED`, copies required checks, preserves immutable
  history, leaves repository-defined policies untouched, and supersedes an
  in-flight Observation frozen to the replaced revision
- annotation-free Maven compiler failures may use only schema-v5
  `ACTIONS_JOB_LOG_V1`: an exact run/attempt/complete-job-set/job/check/suite/
  tested-SHA binding, complete strict-UTF-8 capture within eight MiB, frozen
  byte count/digest, and complete versioned `MAVEN_COMPILER_V1` diagnostics;
  raw logs, tails, excerpts, partial parses, and mixed parser versions remain
  diagnostic-only and classify as `UNKNOWN`
- `MAVEN_COMPILER_V1` includes the exact GitHub Actions runner shape in which
  timestamped javac `symbol`, `location`, `required`, `found`, and `reason`
  continuations omit `[ERROR]`; only those prefixes attach to an active
  diagnostic, while malformed and duplicate canonical evidence stays fail-
  closed
- the CI Autofix Harness shares only the strict GitHub job-log fetch/capture
  primitive; its heuristic parser, cache, orchestration, and repair state never
  become Remote Development provenance, admission, or lifecycle authority, and
  V2 keeps the separate versioned `MAVEN_COMPILER_V1` proof parser
- a dependency-only aggregate is accepted only with an exact workflow blob,
  stable run attempt, complete job set, static literal fan-in, exact job/check
  mapping, and one unanimous classification from its concrete failed
  dependencies; one exact literal-prefix/suffix
  `${{ matrix.<identifier> }}` job-name template may map uniquely to every
  complete runtime instance, while every other dynamic/reusable/matrix,
  incomplete, rerun-raced, ambiguous, aggregate-only, or independently failing
  case remains `UNKNOWN`; an otherwise complete exact mixed-origin graph is
  `TASK_BRANCH_REPAIRABLE` only when every failed leaf has concrete head/base
  proof, and remains append-only
- the production snapshot writer persists schema-v4 aggregate and schema-v5
  exact-job-log typed provenance in its dedicated column, the Java payload
  version exactly matches the SQL gate, and already-durable schema-v3 concrete
  proof remains readable without aggregate authority
- an `UNKNOWN`-to-proven same-subject transition is serialized, idempotent,
  stale-safe, preserves consumed counters and remaining budget, and does not
  resolve its blocker before the successor is durable
- Task-owned repair performs only the exact normal-push protocol; base-owned
  repair performs only the tip-repair/history-rewrite/validation/review/exact
  force-with-lease protocol, never writes the base branch, and has no inferred
  remote or weaker-push fallback
- standing `autoApprove` consent and exact blocker consent each authorize only
  one proven action under their frozen revision; absent consent blocks repair,
  an accepted push consumes authorization, and every retry requires a new one
- strict check-run reads prove complete, stable pagination; premature short or
  empty pages, changing totals, malformed pages, and the safety cap before the
  reported total fail closed instead of persisting a partial authority set
- every base-rewrite Operation kind is present in the explicit dispatcher
  handler registry, and every manual retry gets a fresh blocker identity
- the base-rewrite validation DispatchTicket uses only its exact typed
  operation/callback pair and requires `writer_required = 1`; the ordinary CI
  validation mapping and writerless variants are rejected by SQL
- a failed base rewrite may persist its immutable failed result but creates no
  current subject and advances no head; direct SQL subject admission requires
  both `validation_outcome = PASSED` and a succeeded rewrite Operation
- the production observer persists merge-queue capability on the immutable
  snapshot; `SUPPORTED` derives merge-queue mode, `UNSUPPORTED` derives direct
  mode, and absent/legacy `UNKNOWN` proof remains durably observable but cannot
  create readiness, redrive policy, or start merge until a later known
  observation arrives
- GraphQL queue/entry null/null never proves `UNSUPPORTED`; it requires the
  exact base branch's complete active-rules response. A valid queue/entry or a
  `merge_queue` rule proves `SUPPORTED`; only a complete rules response without
  that rule proves `UNSUPPORTED`; every failed, incomplete, malformed, or
  mismatched response persists `UNKNOWN` and fails closed
- all mutation-deciding remote reads finish before the merge effect claim; a
  failure before that claim re-arms only the same exact read-only preflight,
  creates no semantic attempt, consumes no merge or queue-bounce budget, and
  never enters indeterminate-effect probing
- reconciliation never repeats an already-applied history rewrite: unchanged
  input returns typed no-proof failure, an exact rewritten subject reconstructs
  its proof read-only, and a crash between rewrite and validation resumes from
  that reconstructed subject
- every rejected, canceled, or exceptional base-history rewrite restores and
  verifies the exact frozen StageTurn input head, clean branch, and fingerprint
  under the existing Task writer lease before retry or restart can proceed;
  immutable failure evidence still names the rejected rewritten SHA
- an observation that reaches the Task stripe after the remote push but before
  push-result delivery recognizes only the exact live PUSH_HEAD subject as
  provisional; accepted push delivery records the head and requests a fresh
  observation before CI repair can advance
- a provisional pushed-head snapshot may persist immutable observation/inbox
  evidence, but no feedback-resume, branch, merge, readiness, or auto-merge
  semantic fold consumes it before the accepted push result is durable
- infrastructure classification requires exact typed per-check identity,
  lineage, and tested-subject proof; provider-native canceled/timed-out
  conclusions may omit annotation fingerprints, while incomplete, unmatched,
  or mixed evidence remains `UNKNOWN`
- replay of one base-repair command must match its expected worktree head as
  well as its episode, authority, policy/blocker, actor, and reason

Required tests before this extension is complete:

- a typed-provenance matrix covering every supported class, the complete
  repository outcome matrix, exact-job Maven log evidence, and every
  fail-closed malformed, incomplete, mixed, mismatched, parser-version, capture,
  and raw-marker variant
- exact-attempt Actions adapter and aggregate-proof tests covering pagination,
  total changes, job/check identity, workflow blob and static job mapping,
  the one supported matrix-name template plus ambiguous/dynamic variants,
  rerun races, unsupported workflow shapes, aggregate-only failures, and
  unanimous versus mixed dependency classifications
- concurrent and replayed `UNKNOWN` supersession tests proving one successor,
  predecessor quiescence, stale-subject rejection, exact blocker resolution,
  and no counter or budget reset
- Task-owned repair protocol tests for frozen authorization, validation, no
  Task Brain creation, exact named normal push, push rejection, and retry
- `TASK_BRANCH_REPAIRABLE` tests for complete exact mixed/non-unanimous proof,
  aggregate-without-concrete-base rejection, persisted migration compatibility,
  append-only Stage repair, no base
  authorization/history rewrite, direct validation/push/reobservation, and no
  CI Brain creation
- base-owned repair protocol tests for tip-only StageTurn output,
  deterministic HistoryRewriter placement below the original Task manifest,
  exact expected-old-head force-with-lease, no Task Brain creation, no
  fallback, no base-branch write, standing-policy and blocker consent,
  authorization consumption, and fresh retry authorization
- persistence-boundary tests proving failed rewrite evidence cannot become a
  current subject, rewrite validation cannot dispatch without the exact
  callback and writer lease, and known/unknown merge-queue capabilities select
  the only legal merge mode or fail closed
- merge-queue capability tests covering GraphQL queue/entry proof, active rules
  for the exact base branch with and without `merge_queue`, and every failed,
  incomplete, malformed, or mismatched rules response, plus a transient
  pre-claim read failure that retries the same preflight without consuming
  semantic or queue budget or entering effect reconciliation
- a real-repository failed-rewrite test proving physical HEAD returns to the
  frozen StageTurn input and a second attempt starts there, plus production-hook
  recovery from legacy `UNKNOWN` capability to a later known observation
- seeded historical CI-repair and current branch-sync Brain failure-delivery
  tests covering exact terminal receipt acceptance, TaskTurn/Operation
  terminalization, retained Brain cursor, one blocker, no semantic-budget or
  authorization consumption, fail-closed historical CI replacement, and the
  BranchSync projection/API/UI capability; a seeded live pre-cutover CI Brain
  ticket reaches neither provider launch nor MCP authorization
- BranchSync retry tests covering stable-command replay, restart before dispatch, fresh
  identity/storage ordinal, frozen-context reconstruction, no failed-session
  resume or cumulative baseline, replacement fencing, late predecessor
  delivery, and rejection of every stale Task/Stage/Episode/subject/receipt
  component

Corrective execution note (confirmed 2026-08-01): implement this as the
smallest vertical extension of the existing Task stripe, typed blocker,
command-receipt, DispatchTicket, and recovery-projection machinery. Persist any
missing failure receipt/replacement lineage with a forward-only migration; do
not add a generic Turn retry abstraction, scheduler, or execution pool. On
upgrade, an exact `RESULT_PENDING` repair-Brain ticket that already has an
immutable terminal failed provider result is first delivered into the new
failure transition without launching an agent. Only the explicit Retry command
may arm its fresh successor. Missing or mismatched historical subject evidence
fails closed and must not be reconstructed from “latest” Task, Stage, Episode,
or provider-session state.

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
- presentation-only terminal PR-cache overlay for an exact stable
  RemotePrBinding; no read-through sync and no lifecycle authority

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
- an already-synchronized exact-binding `merged|closed` PR cache may correct
  the displayed PR label after an epoch change, while cached nonterminal or
  mismatched state cannot override the accepted snapshot and no cache value can
  create TaskTerminalIntent, Cleanup, readiness, CI, or write authorization

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
  ".backup '/private/tmp/bytequay-source-v312.db'"
cp -p /private/tmp/bytequay-source-v312.db /private/tmp/bytequay-v322.db
chmod 0444 /private/tmp/bytequay-source-v312.db
chmod 0600 /private/tmp/bytequay-v322.db
shasum -a 256 /private/tmp/bytequay-source-v312.db
cd backend
mvn -q -Dtest=TestDevelopmentFlowBackupAudit \
  -Dbytequay.audit.db=/private/tmp/bytequay-v322.db test
shasum -a 256 /private/tmp/bytequay-source-v312.db
~~~

The audit runner refuses the standard live-database path, targets exactly
V322, checks SQLite integrity and foreign keys before and after migration, and
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

## Locked corrective implementation after cutover

The legacy-to-V2 migration remains complete. The following 3.21 through 3.29
corrections are forward-only maintenance on the existing V2 owners and must
not introduce a legacy route, scheduler, state writer, generic normalization
service, or new executor:

- [x] freeze the exact Claude Plan permission-prompt argv bridge for
  `PLAN_DRAFT` and `PLAN_SELF_REVIEW` even when their generic catalog is empty,
  with owner/purpose tests proving no capability broadening
- [x] expose and mutate canonical revisioned TaskAutomationPolicy through the
  Plan overlay, serialize client writes before approval, and fence approval by
  the returned policy revision with atomic `autoMerge => autoApprove`
- [x] implement the bounded `DEVELOPMENT_BRAIN_RESULT_REPAIR` TaskTurn and
  persistence lineage after the original plus one ordinary malformed
  Development Brain retry, with unchanged strict decoding, no
  tools/resume/mutation/wait/writer authority or semantic budget, and one
  terminal manual blocker on repair failure
- [x] implement one bounded Remote CI repair result-normalization lineage for
  an exact provider-successful malformed StageTurn source: one fresh no-resume,
  tool-free, read-only TaskTurn, unchanged strict Stage decoding, no
  model/semantic or CI-repair-budget debit, and at most one independently
  proving `ADOPT_NORMALIZED_REMOTE_REPAIR` Local-Git Operation for a changed
  tree
- [x] gate Agent result-delivery claims on terminal current execution evidence;
  maintenance may finish evidence from the durable result but may not rerun the
  provider or effect; `USER_WAIT` and every other non-success disposition must
  bypass success-payload parsing, and only an exact immutable owner/fence
  `CANCELED` result may synthesize `PROVIDER_CANCELED` from a null payload
- [x] apply V310 to support and reconcile exact successful old-epoch Remote
  observation supersession without a snapshot
- [x] apply V312 to reconcile historical terminal-ticket / active-execution
  contradictions and exact stale no-launch typed StageTurn cancellations with
  explicit recovered evidence, no replay, and unblocked cancellation
  quiescence
- [x] project only an exact-binding already-synchronized terminal PR cache into
  display state, with tests proving it cannot drive lifecycle or remote gates
- [x] accept typed first-publish `BASE_MOVED` without a push, return the exact
  Local Stage to Local Review, and admit one bounded local base-sync Episode
  only from frozen standing `autoApprove` or one exact manual blocker
- [x] run local fetch and real mechanical rebase through durable dispatcher
  tickets and the Task writer lease, then route clean and conflict outcomes
  through one semantic `BASE_SYNC` StageTurn and the complete fresh local
  validation/Brain/review/publish sequence
- [x] preserve exact real-rebase conflict paths, restore the source before
  semantic repair, reject infrastructure failures as conflicts, and fence
  cancellation, replay, stale delivery, Cleanup, and Trunk purge
- [x] reconcile a crash-mid-rebase only under the exact Task writer fence:
  independently prove a completed target, or match Git's source branch, source
  head, and target metadata before aborting to the clean source and rerunning;
  leave foreign or malformed rebase state untouched and indeterminate
- [x] make local publish-base synchronization obey Task pause/resume/cancel,
  persist an exact parked cursor, retry only determinate failure under its
  frozen authority and limit, and expose one audited one-attempt extension for
  the exact exhausted blocker
- [x] reconcile Cleanup provider sessions using only unfinished `AGENT_TURN`
  executions, preserving failed/indeterminate attempt evidence and excluding
  Cleanup's own execution from its quiescence proof
- [x] add the Task-owned durable `REPAIR_QUARANTINED_WORKTREE` Local-Git
  Operation, exact recovery projection, and Task-wide writer barrier; accept
  repair only from branch/head/clean-fingerprint/no-Git-control-state proof
  under fresh capacity and writer fences, with new-Operation Retry and the
  independent Cleanup `REMOVE_WORKTREE` absent-path disposal bypass
- [x] replace wall-clock/string arbitration of current local code subjects with
  one database-assigned, source-keyed revision per accepted immutable result;
  keep the exact Task epoch boundary and local BASE_SYNC DevelopmentReport
  handoff, and prove a validated base rewrite wins during clock rollback

Each item lands with the corresponding normative acceptance scenarios 113–132.
Until its tests pass, it is an open correction even though legacy execution
retirement remains complete.

### V310 forward-migration contract

V310 is forward-only, preserves every existing row, and permits a successful
Remote-observation delivery receipt without a snapshot only when acceptance is
`SUPERSEDED` and the immutable
Task/epoch/Stage/generation/binding/Operation/ticket fence is exact. Startup
reconciliation applies that shape only to an existing `RESULT_PENDING` success
whose Task epoch has advanced, persists the raw result digest once,
terminalizes the observation and ticket, and creates no Remote domain fact.

All current-epoch Remote snapshot guards remain intact. A missing raw result,
ambiguous owner, mismatched operation, or changed subject is left untouched and
reported for manual reconciliation; V310 never guesses from a latest Task,
Stage, snapshot, or execution.

### V312 forward-migration contract

V312 preserves every existing row and finds unfinished `agent_execution`
evidence only beneath its exact terminal `AGENT_TURN` DispatchTicket with a
delivery receipt. It does not change or redeliver the ticket or call any
handler. An execution from an earlier infrastructure attempt becomes
`UNKNOWN`. A current execution beneath a terminal failed ticket also becomes
`UNKNOWN`, because the cleared raw result could have been `FAILED` or
`INDETERMINATE`. Only current evidence beneath terminal `SUCCEEDED` or
`CANCELED` tickets adopts that corresponding status. Every repaired row keeps
a stable recovered-evidence marker. Nonterminal and non-Agent tickets remain
unchanged; V312 never guesses from a latest Task, Turn, ticket, or execution.

The one additional correction is an exact historical no-launch cancellation.
V312 may select a typed StageTurn only when its `AGENT_TURN` ticket is
`RESULT_PENDING` with outcome `CANCELED`, null payload, zero infrastructure
attempts, no `agent_execution`, and an exact immutable owner/Operation/attempt
and complete Task/Stage/code fence, while the Stage owner is provably completed
or no longer current. It terminalizes both StageTurn and ticket as `CANCELED`,
records ticket delivery acceptance `SUPERSEDED` with explicit migration
recovery evidence, and clears next-attempt and pending-result fields. It does
not fabricate execution evidence, claim delivery, invoke a provider or
handler, or replay work. Any launched attempt, live current owner, existing
execution evidence, or owner/fence/outcome ambiguity remains unchanged for
explicit reconciliation.

### V313–V316 forward-migration contract

V313 rebuilds the immutable Stage command-receipt table only to admit the exact
`ACCEPT_PUBLISH_FAILURE` result cause. It preserves all existing rows and
constraints, and the new cause still requires a complete publish result fence,
one matching Stage transition, and an exact failed or canceled publish
Operation. It does not authorize a retry, repair, or remote effect by itself.

V314 replaces the Cleanup step-result guard without changing Cleanup history.
Failure and indeterminate attempt rows remain admissible immutable evidence;
the step-specific quiescence predicates apply only to a claimed successful
result. Provider-session proof counts only unfinished executions whose ticket
family is `AGENT_TURN`, so Cleanup, Remote Observation, and finished historical
execution rows cannot self-poison step 3.

V315 adds the local publish-base-sync Episode, its two local-Git Operations,
typed delivery receipts, the `BASE_SYNC` StageTurn subtype, and its dedicated
Stage-start receipt. Existing `local_stage_turn_request` rows and every inbound
foreign key are preserved in place. Standing admission requires the exact
still-pending typed `BASE_MOVED` publish result and frozen auto-approve policy;
manual admission remains possible after ticket delivery only through the exact
accepted failed-publish receipt and open blocker. The scheduled branch-sync
policy supplies a bounded attempt limit but its enabled flag is not consent.
The current-code projection advances to a mechanically rebased subject only
after an accepted exact result, and Cleanup/purge quiescence includes every
nonterminal base-sync Episode. Empty, populated, and live-V312 upgrade fixtures
must pass `foreign_key_check` with all pre-existing inbound references still
targeting `local_stage_turn_request`.

Recovery of the mechanical-rebase Operation is also fenced as a writer. It may
adopt an already-completed result only after exact patch-series proof, or abort
an in-progress rebase only when Git's stored original head, branch ref, and onto
SHA match the immutable Operation before rerunning it from the proven clean
source. A foreign or malformed rebase is not mutated and remains indeterminate.

V316 rearms only the historical Cleanup shape proven to have been parked by
the pre-V314 result guard: the current active Cleanup owns one ordinal 2 or 3
`CLAIMED/PROBE` attempt, its exact terminal `UNKNOWN` Cleanup execution records
that guard failure, and its unclaimed `RECONCILE_WAIT` ticket has no due time,
live execution, lease, result, cancellation, or later advanced step. It also
stops a live CI-repair or branch-sync Episode only under its exact accepted
`MERGED`/`CLOSED` Cleanup handoff, after any matching child whose exact ticket
is already canceled has been settled and no child Operation remains live.
Other manually parked tickets and unmatched Episodes remain unchanged.

V317 rebuilds only the local publish-base-sync Episode, Operation, and delivery
receipt tables to add immutable retry lineage, operation generations, parked
cursors, and the local `PARKED` acceptance. It preserves every V315 row as
generation 1, retains the original exact first-admission proof (including the
full pending-result fence, typed `BASE_MOVED` payload, latest bounded branch
policy, frozen standing approval, and absence of a remote binding), and adds
pause, resume, cancellation, and one-attempt budget-extension receipts.

The forward triggers permit proof-only reconciliation while the Task is
pausing and one narrowly fenced canceled-epoch reconciliation route. Resume
requires the exact materialized Stage resume handoff and current `ACTIVE`
Task/Stage. Cancellation requires the advanced Task epoch and terminal intent.
Determinate failures alone become `FAILED` or `EXHAUSTED`; automatic standing
retries retain the frozen limit, manual retries require their exact open
blocker, and an exhausted retry requires an immutable extension whose new limit
is exactly one greater. The current-code view may retain an exactly proven
parked clean rebase across pause, but Task epoch fencing excludes it after
cancel. Empty and populated migrations must retain canonical foreign keys and
pass `foreign_key_check`.

### V318 worktree-quarantine repair contract

V318 persists an immutable quarantine whenever a writer-capable AgentTurn
cannot prove restoration of its exact source before releasing the live writer
lease. The record freezes the Task and worktree, source StageTurn/Operation,
Task branch, clean head, and code fingerprint. Database admission treats any
open record as a Task-wide barrier across later Stage checkpoints and
generations; no ordinary worktree writer may bypass it.

The typed Task recovery command accepts one stable command id and exact current
Task epoch, Stage, worktree, quarantine, branch, head, and fingerprint fence.
It performs no Git work and atomically persists one
`REPAIR_QUARANTINED_WORKTREE` Operation, Local-Git DispatchTicket, and wake.
ExecutionDispatcher may claim it only after CapacityManager grants sole Task
admission and the worktree lease grants a fresh writer token. The repair
Operation/result/delivery records are immutable and the repair handler is the
only restorative quarantine bypass.

Under that lease, repair aborts only recognized in-progress Git control state,
discards dirt at current `HEAD` without first moving a possibly foreign branch
ref, switches to the exact Task branch, resets that branch to the frozen head,
cleans again, then proves the branch, head, clean fingerprint, and absence of
rebase/merge/cherry-pick/revert/sequencer state. Unsupported or malformed Git
state fails closed. Repair does not rebase, merge, or salvage quarantined
changes.

The immutable result is recorded while the writer token is live; exact accepted
delivery alone clears quarantine. After a crash, a reconciliation attempt uses
a new token and must re-prove the complete state before adopting either the
filesystem outcome or a prior immutable result. A failed, canceled,
superseded, stale, or incompletely proven Operation leaves quarantine open.
Exact command replay returns its original Operation; an explicit Retry creates
a new Operation, ticket, leases, and token against the still-current fences.

Cleanup's exact `REMOVE_WORKTREE` step remains a separate disposal bypass. It
may remove only the Task-bound path under normal Cleanup quiescence and resolve
quarantine as disposed only after absent-path proof; it performs no restore and
authorizes no other writer. The Task recovery projection and Stage card expose
only durable `REPAIR_WORKTREE` capability/status, disable ordinary writer
controls, and never inspect or mutate Git from the controller or UI.

Acceptance gate:

- dirty detached and wrong-branch worktrees restore without first moving a
  foreign ref, and exact branch/head/clean-fingerprint/no-control-state proof
  is required before quarantine clears
- ordinary writers and later Stage generations remain blocked across restart;
  crash before result, after result, and before delivery re-proves under a new
  token without duplicate mutation or receipt
- failure, cancellation before or after mutation, stale epoch/Stage/worktree or
  quarantine identity, unsupported Git state, and changed proof all remain
  quarantined; Retry is a new durable Operation
- the recovery API/card is projection-driven and replay-safe, while Cleanup
  `REMOVE_WORKTREE` alone may resolve the barrier from exact absent-path proof

### V318–V320 CI-repair freshness, BranchSync, and code-subject contract

V318 records a bounded continuation intent when a CI repair reports no tree
change, restores the rejected throwaway head to its exact source, and requires
that continuation to remain durable rather than launching it from result
delivery. V319 generalizes that rule to every CI repair writer: one immutable
authorization freezes the exact accepted failed Remote snapshot, monotonic
observation revision, authoritative base, local code subject, Episode, intent,
and semantic/execution attempt. A continuation requires a distinct later
snapshot and strictly greater revision. Green evidence cancels pending repair
intent and never authorizes a writer.

Accepted Remote folding runs BranchSync before CI. A locally-ahead pending
repair on an older base uses `CI_PRECONDITION_LOCAL`: settle any older CI
writer, fetch/rebase/repair conflicts locally, prove the resulting worktree
subject, skip validation/push, then request another Remote observation. Only
the later freshly authorized CI Turn performs its normal validation and single
push; it creates no Task Brain Turn. If the precondition rewrote local history,
that final
push freezes its successful BranchSync Episode; each intervening code writer
retains that predecessor lineage across later validation and repair retries,
and publication uses one exact named-head force-with-lease against the Episode's
old Remote head. Missing or substituted lineage fails before
dispatch and no ordinary-push fallback exists. Manual
`START_BRANCH_SYNC` freezes that first
observation Operation so replay before or after consumption returns the same
receipt and schedules nothing.

New BranchSync policy revisions default to eight attempts under the retained
database ceiling of ten; persisted revisions remain unchanged. Determinate
effect retries append immutable dispatch rows to the same ordered step and
Episode. Exhaustion records one exact Remote-and-local-subject blocker and
suppresses a replacement Episode until either subject genuinely changes. V319
forward-rebuilds the applied dispatch table so rows are keyed by step plus
semantic attempt instead of limiting a step to one row; V308 remains immutable.
Empty and populated migration fixtures must retain foreign-key integrity.
Exhaustion offers only the BranchSync-owned `MANUAL_TAKEOVER` and
`STOP_AUTOMATION` commands. Both are durable and replay-safe, resolve the exact
blocker, retain unchanged-subject suppression, and create no writer, budget
extension, or CI recovery command.

Remote CI/Branch repair Stage output and BranchSync Brain output remain strict
JSON. The AgentTurn boundary rejects unknown fields, duplicate keys, trailing
tokens, Markdown fences, surrounding prose, invalid schema/version, and
inconsistent BranchSync Brain verdict/findings. A malformed Stage writer
restores its exact source
under the live writer fence; restore failure enters the central worktree
quarantine. Otherwise malformed output and ordinary provider non-success each
become one typed failed result, immutable delivery/failure receipt, and exact
owner blocker. A same-subject observation cannot create another Turn/Episode or
reset/consume budget. No normalization Turn is part of V318/V319.

V320 removes the remaining timestamp/selector race from
`task_current_code_subject_v230`. It appends one immutable, source-keyed
database revision in the same transaction as each accepted Remote worktree,
Remote steering, passed CI base-rewrite, or local publish-base-sync fact. The
highest revision for the exact Task epoch is current; process clocks and source
identifier ordering are never authority. Source replay is idempotent, while a
distinct later source may legitimately carry the same code/head/base triple
and still advances the revision. A completed local BASE_SYNC StageTurn retains
its existing handoff to the DevelopmentReport.

### V322 Remote CI repair Stage-result normalization and adoption contract

V322 adds one immutable normalization lineage keyed by the exact failed Remote
CI repair StageTurn, plus the candidate-proof, continuation-authority,
Local-Git adoption Operation/result, and delivery receipts required by C66. A
unique source constraint is the at-most-once boundary; restart and replay reuse
that lineage rather than creating another TaskTurn, Operation, blocker, or
budget entry. Existing source failures and raw provider evidence are never
rewritten.

Admission requires a provider-successful `OWNER_OUTPUT_MALFORMED` result with
complete current Task epoch, Remote Stage/generation, CiRepairEpisode/cursor,
semantic/execution attempt, worktree/branch, code/head/base, terminal agent
execution, raw text/digest, required Stage-result schema, failure receipt, and
matching open blocker. Provider/process failure, indeterminate execution,
BranchSync StageTurn, Remote repair Brain TaskTurn, missing evidence, a stale
owner, or any mismatched fence stays on its existing path. Admission itself
neither resolves the blocker nor changes the original base-repair authorization.

The admitted `REMOTE_REPAIR_RESULT_NORMALIZATION` TaskTurn is fresh,
read-only, and no-resume. Its frozen launch contains only the malformed source,
digest, exact expected JSON shape, and identifying fences. It receives no
ByteQuay/MCP tools, repository source, writer lease, permission/user-wait path,
or mutation authority. It does not debit a model/semantic-attempt ledger or a
CI repair budget; provider execution and raw usage remain audit evidence.
Its prompt forbids provider-native filesystem or repository reads. Delivery
runs the unchanged strict Remote CI Stage-result decoder. Malformed, failed,
canceled, indeterminate, or stale normalization is terminal, retains the
existing blocker, closes any reactivated authority, and cannot create a
replacement normalizer.

For V322-or-later writer sources, the original AgentTurn handler freezes the
candidate commit, exact source parent, changed tree, Task branch/worktree, and
execution window while the original writer token remains valid, before exact
source restoration. A strict normalized Stage result with changed-tree proof
may then create exactly one `ADOPT_NORMALIZED_REMOTE_REPAIR` Local-Git
Operation. ExecutionDispatcher must obtain CapacityManager admission for the
exact Task and a fresh worktree writer lease. The handler independently proves
that the restored exact Task branch still names the frozen source and that the
unique candidate is its changed-tree child, then fast-forwards only that branch
and records a clean current code subject. It may not cherry-pick, merge, search
arbitrary objects, or trust the normalized JSON as repository evidence.

V322 may recover an otherwise-exact malformed writer already terminal before
the migration, for which pre-restore candidate capture did not exist, only
through a compatibility-labelled proof. That proof is restricted to the
frozen original execution's bounded reflog window and succeeds only for one
changed-tree commit whose parent is the exact source. No/multiple candidates,
expired evidence, wrong parent/tree, or any fence mismatch remains manual. The
compatibility query is forbidden for every V322-or-later source and unrelated
historical row; reflog discovery is not a general recovery API.

The original exact `CI_REPAIR_OUTPUT_MALFORMED` blocker remains open through
normalization and Stage adoption, with no duplicate attention item. After the
strict candidate is accepted, an immutable continuation receipt may preserve
or reactivate only the original exact CI base-repair authorization; it cannot
mint a replacement authorization.
Successful Stage adoption appends the current-code revision, consumes the
original CI changed-tree fix attempt exactly once, resolves that blocker, and
returns to ordinary validation/direct-push/CI without Task Brain review. Any normalization or
adoption failure/staleness leaves the blocker and immutable source failure in
place, closes reactivated authority, consumes no additional budget, and offers
manual recovery only. If an adoption writer cannot restore its frozen source,
the existing C64 worktree quarantine also opens; it cannot admit another
adoption.

Acceptance gate:

- normalizer admission, delivery, restart, and replay create at most one fresh
  no-resume/tool-free TaskTurn and never debit semantic/model-attempt or CI
  repair budget
- future writer candidate proof is captured only under the original writer
  fence; one adoption under fresh capacity/writer fences must independently
  prove source parent and changed tree before advancing code ownership
- the blocker stays open and the original authorization stays unchanged at
  normalization admission; only an immutable continuation receipt can
  reactivate it, successful adoption charges/resolves once, and every failure
  closes reactivated authority without a loop
- pre-V322 compatibility admits only one exact bounded-window reflog child;
  ambiguity fails closed and no new source can use that proof form

Implementation regression locks:

- future malformed-source admission is valid while the exact source ticket is
  `RESULT_PENDING` after owner delivery; it must match the complete pending
  result envelope and cannot require the dispatcher to terminalize first
- the normalization Operation's `REQUESTED -> DISPATCHED` handoff is not a
  terminal-result transition; terminal proof applies only when leaving
  `DISPATCHED`
- a CI fix attempt increments only after one accepted changed-tree result from
  the existing path or one exact accepted V322 adoption, never for
  normalization, no-change output, or failed adoption
- normalization and adoption redelivery replay the exact committed acceptance
  and evidence bytes; the original raw-result digest must still match
- every live boundary rechecks the same C65 source revision/kind/id and
  CiRepairEpisode cursor, while frozen candidates require one direct parent;
  only the migration-seeded pre-V322 allowlist may omit candidate proof and use
  bounded reflog discovery

Malformed BranchSync Stage and Remote repair Brain results are deliberately
outside V322. They retain their existing strict terminal/manual or explicit-
Retry behavior, and extending this lineage to either requires a new locked
decision rather than a schema-compatible flag.

### V323 DevelopmentReport code-subject completion contract

V323 makes an accepted V2 DevelopmentReport a first-class C65 source in the
single canonical Task code-subject revision ledger. The revision is appended
only with the exact successful, accepted Local StageTurn delivery receipt that
binds the report and its validation Operation to the same Task epoch, Local
Stage/generation, StageTurn, input subject, and output code/head/base triple.
A report row without that receipt is historical payload, not ownership.
Source-key uniqueness makes receipt replay idempotent. Upgrade backfill is
limited to an exact accepted DevelopmentReport that is still the current Task
code subject and has no ledger row. It never appends an older report after an
already-admitted successor merely to fill historical audit order.

The current-subject projection continues to select the greatest durable
revision in the exact Task epoch. This also makes the existing completed local
BASE_SYNC handoff explicit: authority returns to the newly accepted
DevelopmentReport revision, never to timestamp order or an unreceipted report.

Because V320 and V322 stored closed source-kind checks, V323 rebuilds the one
canonical revision table and the five V322 source-identity tables in place.
It preserves existing rows, identities, exact evidence bytes, indexes,
triggers, child references, and revision ordering; it does not introduce a
parallel ledger or normalization lineage. Migration verification must include
a populated V322 normalization/adoption lineage and a clean
`foreign_key_check` before V323 is considered safe.

For compatibility only, V323 may seed the V322 legacy allowlist and due row for
an otherwise-exact pre-V322 malformed Remote CI repair that V322 rejected
solely because its current accepted DevelopmentReport had no C65 revision.
The new revision must identify that exact report, and every original V322
Task/Stage/episode cursor, provider execution, raw digest, blocker, base-repair
authorization, and bounded reflog-window predicate remains mandatory. No
runtime path may mint this allowlist, accept a null revision, or use SHA-only
identity. The compatibility proof models the actual durable delivery order:
StageTurn, CI-repair Operation, and owner receipt use one exact completion
instant, while the dispatcher may terminalize their exact ticket a few
milliseconds later. A delivered terminal ticket therefore requires
`ticket.completed_at_ms >= source.completed_at_ms`, never timestamp equality;
the exact ticket/Operation/owner/execution/digest/receipt joins remain
mandatory.

Acceptance gate:

- an accepted V2 DevelopmentReport appends exactly one
  `DEVELOPMENT_REPORT` revision with its receipt transaction, while an
  unreceipted, failed, superseded, stale, or mismatched report appends none
- an accepted historical report already superseded by a later ledger source is
  not appended during upgrade and cannot become current by migration order
- existing V320/V322 revision and normalization/adoption rows survive the
  rebuild byte-for-byte with valid foreign keys and unchanged replay behavior
- only a pre-V322 malformed repair excluded solely by the missing accepted
  DevelopmentReport revision gains one immutable V322 compatibility lineage;
  all other missing or mismatched proof remains manual
- a terminal ticket recorded after its accepted owner delivery remains
  eligible, while any earlier, unbound, or otherwise mismatched ticket remains
  ineligible
- future Remote CI repair normalization carries the same exact
  revision/kind/id fence when its source is a DevelopmentReport and consumes no
  additional repair or model budget
- authorized archived-Trunk purge deletes the accepted-report
  `local_stage_turn_delivery_receipt` before its `local_stage_turn_request`
  and StageTurn owner, scoped to the exact Trunk's V2 Tasks; neither row may
  block an otherwise quiescent purge or be deleted during normal execution
- the tool-free normalization launch omits `approvalPromptTool`, the shared
  endpoint decoder accepts that explicit absence, and the AgentTurn handler
  continues to reject a missing exact ByteQuay approval gate for every other
  Task or Stage Turn before provider launch

### V324 exact pre-provider normalizer rearm contract

V324 is a forward-only repair for normalization tickets terminalized by the
former shared endpoint decoder before any provider or process began. It is not
a new normalization retry policy. Admission requires the exact V323
DevelopmentReport compatibility lineage, current Task epoch/Remote Stage/C65
subject, open malformed-output blocker, fixing CI episode, terminal accepted
owner delivery, and the structured `INVALID_LAUNCH_INPUT` result whose stable
error identifies the missing `approvalPromptTool` decoder defect. The current
AgentExecution must have no provider, session, PID, log, process-attempt, or
usage evidence. A suspect row missing any proof aborts the migration.

The migration preserves the failed AgentExecution byte-for-byte and rearms the
same normalization Operation, TaskTurn, and DispatchTicket. The ticket advances
from version four to five while retaining `infrastructure_attempts = 1`; the
ordinary dispatcher therefore records the next provider launch as
infrastructure attempt two. The normalization Operation returns through its
ordinary `REQUESTED -> DISPATCHED` guard. The due row remains `DISPATCHED`, and
no semantic attempt, model/CI-fix budget, episode counter, blocker, code
subject, source authorization, reauthorization, or adoption row changes. The
already-delivered dispatch wake remains historical advisory evidence; the
authoritative requested-ticket scan supplies liveness after restart.

Acceptance gate:

- the exact decoder-defect row rearms the same three owner identities and
  preserves attempt-one AgentExecution evidence byte-for-byte
- the next claim increments only the infrastructure attempt and can append a
  distinct attempt-two execution without charging repair budget
- provider/session/process/log/usage evidence, a different disposition/error,
  stale ownership, a live lease/delivery claim, or existing adoption authority
  fails closed and leaves the terminal lineage unchanged
- the temporarily removed terminal guards are recreated byte-for-byte, all
  temporary proof tables are removed, and `foreign_key_check` stays clean

### V325 compatibility base-repair continuation contract

V325 corrects the operation guards for the immutable pre-V322 authorization
that V322 reactivates without reopening it. The claimed compatibility
reauthorization permits `VALIDATE` only for its exact accepted adopted
candidate. Current flow permits `PUSH_HEAD` directly after the passed rewrite;
historical `BRAIN_REVIEW` and exact provider-failed Brain replacement rows stay
readable compatibility only. Each requires the current `CI_BASE_REPAIR`
subject produced by that validation and remains tied to the same
original authorization, adoption, Task epoch, Stage generation, semantic
attempt, fingerprint, head, base, and current causal code-subject source id.
An equal triple from a later source revision cannot continue the compatibility
authority. Ordinary `CLAIMED` authorization remains unchanged; every near
match fails closed.

Acceptance gate:

- exact adopted candidate validation and direct push may continue on the
  original semantic attempt without reopening or minting authorization; seeded
  historical passed-rewrite Brain review and provider-failed Brain replacement
  rows remain finite and readable but are never newly created
- a wrong candidate, rewrite outcome, rewritten fingerprint/head/base, current
  subject source revision, adoption delivery, or replacement fence cannot
  create an Operation
- V325 replaces only the two affected insert guards and changes no durable row,
  budget, attempt, blocker, episode, or authorization status during migration

## Definition of migration complete

The redesign is complete:

- [x] all new Tasks use V2, enforced by application routing and migration V277
- [x] ExecutionDispatcher and V2 MCP/runtime beans are unconditional; route
  diagnostics report only `v2Only=true`
- [x] every aggregate has one state writer
- [x] every asynchronous completion is exact and fenced
- [x] CLI continuations resume only an exact successful owner lineage, rebind
  the current typed MCP endpoint, and fall back once only for a
  provider-specific unavailable session before any provider-work or unknown
  output evidence; failed or incompatible newer Turns block older-session
  reuse, explicit replacement starts fresh, and exact settled USER_WAIT keeps
  its source lineage
- [x] every spawned CLI process has immutable sequential attempt evidence, and
  atomically replaces the execution's single current recovery PID before prompt
  delivery; registration failure stops the child
- [x] Codex resume baselines and raw terminal cumulative totals are exact
  lineage evidence; only non-negative per-Turn deltas enter accounting and
  projections
- [x] finite completion-summary and automatic remote Brain verdict TaskTurns,
  plus evidence-only ReviewAssignmentTurns, cannot advertise or invoke typed
  user-wait tools; Claude CLI derives its optional permission-prompt argument
  from that exact runtime catalog, so an intentionally absent gate is never
  named on the command line while interactive catalogs retain it. A finite
  noninteractive Task Brain preapproves only its exact active owner-scoped MCP
  catalog, and an unavailable-session fresh fallback preserves both that
  allowlist and the absent callback; tool-free Turns remain tool-free. The C59
  Plan-result argv bridge is the explicit purpose-fenced correction listed
  above, not a user-wait or generic-catalog exception
- [x] exact typed permission callbacks use only V2 Task policy/runtime and
  durable typed waits; retained `ThreadService` budget, legacy Task
  phase/auto-approve, and generic `AgentRun` state cannot influence them
- [x] malformed current Stage delivery can be recovered by an idempotent exact
  CANCEL_AND_REPLACE that supersedes late `RESULT_PENDING` delivery, admits one
  fresh successor from complete frozen launch, ordered trace, and classified
  failure evidence as soon as all execution authorities are quiescent, and
  does not wait for the rejected ticket to terminalize or reuse its failed
  session; unclassified delivery errors and missing exact terminal-execution
  proof expose no malformed-result command
- [x] every V2 CLI owner whose final text is decoded as strict JSON publishes
  the same raw-object boundary in its shared initial/steering/retry prompt:
  first non-whitespace `{`, last `}`, no Markdown fence or surrounding prose;
  strict decoders still reject rather than normalize malformed provider output
- [x] an accepted failed current Local StageTurn exposes one exact
  `OPERATION_FAILED` recovery; explicit Retry preserves the checkpoint, admits
  one fully reconstructed fresh successor without failed-session reuse, and
  resolves only its blocker after the successor fence is durable; forward
  reconciliation and database admission guards preserve the same invariant for
  failures accepted before the recovery migration
- [x] a provider-successful malformed Development Brain result is consumed as
  one typed Task-owned protocol failure; V299 `RESULT_PENDING` restart,
  user-wait continuation ownership, strict malformed-result variants,
  no-budget fresh Retry, legacy-prompt reconstruction, idempotent replay, and
  late predecessor delivery are covered by regression tests
- [x] Plan self-review tool metadata and both frozen launch prompts publish the
  same conditional verdict/concern rule enforced by the domain; a rejected
  unrecorded call may be corrected, while final prose still never supplies a
  verdict and provider success without an accepted submission retains its
  terminal review blocker
- [x] exact accepted Brain-to-Local Review handoffs atomically open the stable
  Task-owned PR once; V301 forward-reconciles only exact current V2 Local Review
  subjects left `local-drafted` in active or resumable lifecycles and records
  one deterministic status event
- [x] successful V2 publish delivery records remote identity through the stable
  PR and RemotePrBinding and advances Local-to-Remote only through TaskManager;
  it cannot mutate legacy TaskStore metadata or full-save the V2 Task row
- [x] first-publish base movement performs no remote write, uses frozen standing
  or exact manual authority for bounded local repair, and returns clean/conflict
  outcomes through a fresh semantic Local Development evidence sequence before
  publishing again
- [x] Remote Observation loads operation context and required checks without
  re-entering the production single-connection pool, and its maintainer re-arms
  only exact current parked read-only tickets at the normal polling interval
- [x] CI blockers keep one exact-head Episode live, explicit stop/takeover and
  exhaustion suppress same-subject reopening, and V302 repairs orphaned
  blockers produced by the former stop-on-block transition
- [x] every CI repair writer is admitted only by an exact accepted failed
  Remote snapshot; later writers require a distinct greater observation, green
  cancels pending intent, and locally-ahead base repair remains local until the
  normal CI Turn revalidates and publishes it directly without Task Brain
  review
- [x] BranchSync recovery commands replay one frozen observation identity, and
  determinate retries spend the same Episode's default-eight, hard-max-ten
  budget without same-subject successor reset; terminal control commands are
  BranchSync-owned and keep exact unchanged-subject suppression
- [x] malformed or non-success Remote CI/Branch Stage Turns, current BranchSync
  Brain Turns, and historical CI Brain Turns terminalize once; malformed
  writers restore under their exact lease or enter central quarantine,
  redelivery replays immutable receipts, and unchanged subject polling creates
  no replacement work or fresh budget
- [x] exactly one eligible provider-successful malformed Remote CI repair
  StageTurn may admit the C66 no-resume/tool-free TaskTurn; changed work advances
  only through one independently proving CapacityManager/writer-fenced
  Local-Git adoption. The source failure remains immutable, the original
  blocker/auth lineage is preserved, and failure or staleness remains manual
  without a loop; BranchSync and Remote repair Brain output stay outside C66
- [x] central quarantine blocks every ordinary Task writer across Stage changes,
  and the only recovery paths are accepted exact
  `REPAIR_QUARANTINED_WORKTREE` proof or Cleanup's exact absent-path disposal;
  restart, cancellation, staleness, retry, projection, and foreign-branch
  regressions from scenarios 128–130 pass
- [x] current local code-subject selection uses accepted source revisions rather
  than timestamps or selector text, including clock-rollback base rewrite and
  exact replay coverage from scenario 131
- [x] canonical structured Plan JSON projects its real ordered steps and Brain
  policy controls, while historical Markdown remains supported as an explicit
  compatibility fallback
- [x] no dispatcher, observer, or projector writes domain state
- [x] multiple sibling Tasks run without shared runtime state
- [x] local, Brain, remote review, and automation policies retain parity
- [x] merge and Cleanup survive ambiguous effects and restart
- [x] Cleanup provider quiescence ignores its own and finished non-provider
  executions while retaining exact failed/indeterminate attempt evidence
- [x] terminal TaskOutcome fallback delivery and optional Brain enrichment use
  one exact current typed CLI/API lane throughout Cleanup admission,
  TaskTurn, DispatchTicket, and summary Operation; the retired generic lane
  cannot strand the enrichment after Task completion
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

## Post-migration implementation corrections

- The PR right-panel lifecycle is now projected directly and idempotently from
  durable V2 owner facts while retaining existing private timeline rows. The
  projection covers development commits, Brain review start and terminal
  success/failure, first push and draft/open boundaries, CI repair start and
  success/exhausted/stopped outcomes plus changed repair/adoption subjects,
  merge/close, and Cleanup start/completion. A successful repair names its
  exact last-pushed head. It
  excludes BranchSync subjects and raw Brain transcripts. Exact-owner runtime
  commands, not the projection, advance the stable PR through draft, open, and
  terminal remote states; focused replay regressions prevent duplicate rows.
- A clean end-to-end CI run exposed a distinct policy gap rather than missing
  GitHub evidence: the exact base run and concrete base checks were present,
  but one failed profile was base-owned while another had different exact
  head/base fingerprints. Strict provenance correctly could not manufacture a
  unanimous origin. The persisted `TASK_BRANCH_REPAIRABLE` classification now
  gives that complete exact mixed/non-unanimous case a finite append-only
  current-Task repair path. It never authorizes base quarantine/history rewrite
  and proceeds directly through canonical validation, normal push, and fresh
  observation with no CI Brain. Invalid/incomplete evidence still blocks as
  `UNKNOWN`; V326 preserves historical Episode rows and their FK graph while
  extending the classification constraint.
- A live CI repair exposed an incorrect owner boundary: successful canonical
  validation entered a configurable Task Brain gate before publication. CI
  repair is not product-development review, so the gate and its configuration
  were removed. Current Task- and base-owned CI repairs now proceed directly
  from passed validation to their exact fenced push, then wait for fresh Remote
  observation. Historical CI Brain rows and terminal C58 delivery remain
  readable, but their replacement action is retired and no production path
  creates or launches another CI Brain. Pre-cutover live tickets fail before
  provider/MCP execution. Runtime regressions assert the push is created, no
  CI Brain TaskTurn or pending Brain result exists, and historical recovery
  fails closed without replacement work; no formatter or verdict tool was added.
- A live Remote repair Brain exposed an implementation-only Claude CLI gap:
  catalog advertisement did not itself authorize MCP calls in noninteractive
  `claude -p`, so calls were rejected before reaching ByteQuay. The provider
  request now carries the exact active finite-Brain catalog, Claude renders it
  as its owner-scoped `--allowedTools`, and both focused initial-invocation and
  unavailable-session fallback regressions prove the permission shape. The
  explicit Brain Retry was initially retained for the failed live Turn; the
  later ownership correction above retired that Retry for CI while preserving
  it for BranchSync. No output normalizer was added for Brain verdicts.
- That retry then exposed a second implementation-only boundary error: its
  TaskTurn correctly retained the Remote Stage as trigger provenance, but the
  active MCP scope incorrectly copied it into `ThreadScope.TASK`, so every read
  failed before its handler with `TASK tool call forbids stageId`. Runtime scope
  projection now nulls Stage id only for Task-owned calls while preserving the
  durable trigger/fence and exact Stage ids for Stage-owned calls. Regressions
  cover both sides. The observed CI successor failure is now historical audit
  state with no Brain replacement authority or CI-budget consumption.

### Verification checkpoint — 2026-08-02

- Static verification is green for the implemented scope. The focused backend
  PR-timeline suite passes 25 tests covering replay/idempotency, Development
  and CI-repair commits, Brain start/failure/structured findings,
  first-push/draft ordering, ready, exact repaired-head terminal CI outcomes,
  merge/close, and Cleanup. Focused strict-provenance, direct
  validation-to-push/no-CI-Brain, and V326 migration suites also pass. The
  focused frontend timeline/detail suite passes 51 tests; the complete
  frontend run passes 1,574 tests. Lint has no errors, TypeScript type-checking
  passes, and backend/frontend production compilation completes.
- The complete backend verification is now all-green on a host that permits
  local socket binding: `mvn verify` reports BUILD SUCCESS with 3,406 tests,
  zero failures, zero errors, and three skips. The earlier six DS4/logic-loop
  loopback errors were environment-only and do not reproduce. License,
  checkstyle, and Error Prone gates pass in the same run. The frontend gates
  pass alongside it: TypeScript type-checking is clean and the complete run
  passes 1,574 tests across 188 files.
- The first live restart used exactly one repository-root `./dev.sh` session.
  Flyway validated the migration resources through V326, but the execution
  sandbox exposed the real ByteQuay database and log directory as read-only,
  so startup stopped before the migration could be recorded. A read-only audit
  afterward proved that history remains successfully applied through V325
  with no V326 row or leftover V326 object, all 24 Episode rows retain the
  version-325 classification shape, all nine Episode triggers and the unique
  partial index remain present, and both `foreign_key_check` and
  `integrity_check` are clean. There is no partial migration to repair.
- V326 was then rehearsed against a consistent `.backup` copy of that same
  live database rather than the original. Applying the migration exactly as
  Flyway runs it (outside a transaction, per its `.conf`) preserved all 24
  Episode rows, restored all nine Episode triggers, left no `%v326%` scratch
  object behind, produced a clean `foreign_key_check` and an `ok`
  `integrity_check`, and rebuilt the classification constraint including
  `TASK_BRANCH_REPAIRABLE`. The historical values are unchanged at three
  `BASE_DETERMINISTIC` and twenty-one `UNKNOWN`. The real database is
  untouched and still at V325; this rehearsal only removes the risk from the
  next writable startup.
- V326 is now applied to the real database. A writable backend start recorded
  `version 326, success = 1` in 82 ms, and the post-migration audit matches the
  rehearsal exactly: 24 Episode rows, nine Episode triggers, no `%v326%`
  leftover object, a clean `foreign_key_check`, an `ok` `integrity_check`, the
  rebuilt classification constraint containing `TASK_BRANCH_REPAIRABLE`, and
  unchanged historical values at three `BASE_DETERMINISTIC` and twenty-one
  `UNKNOWN`. `GET /api/development-flow/route` returned `{"v2Only":true}`
  during that start. Flyway history is at 326 with nothing left to reconcile.
- Live end-to-end acceptance is not yet complete. It must use one new Trunk and
  one new V2 Task only after the successful V326 history row is visible; do
  not recover or mutate historical Tasks. Start through repository-root
  `./dev.sh` with normal write access to the ByteQuay application-support and
  log directories, enable auto-merge on the fresh Task, and observe
  Development through exact-head CI, merge, PR timeline projection, and
  Cleanup without manual CI intervention.

## Deferred follow-ups

- Keep malformed BranchSync Stage and current BranchSync Brain output on their
  existing strict terminal/manual or explicit-Retry paths. Historical Remote CI
  Brain output remains strict terminal/manual state with no Retry. Extending V322's
  normalizer or Local-Git adoption to either requires a separate locked design;
  no generic Remote-result bridge is implemented.
- If strict malformed Plan self-review calls remain recurrent after the direct
  schema/prompt correction, separately design a bounded normalization or
  recovery transition. It must preserve one accepted immutable review and may
  neither infer approval from prose nor replace a terminal review without a
  new locked decision.
- Add explicit per-Workspace/repository local Task-worktree bootstrap and
  validation profiles with frozen commands, dependency-lock invalidation,
  durable execution, and failure Retry. This is not implemented for the current
  end-to-end run; local validation remains best-effort and GitHub CI remains a
  separate authoritative check.
