# Development flow architecture

Status: **LOCKED**

Version: **3.3**

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

The former flow accumulated many race fixes around one structural problem:
several classes could infer and mutate the same lifecycle state.
AgentScheduler, Task lifecycle services, Stage lifecycle services, Brain
review coordination, validation listeners, PR polling, and projections all
participated in transitions. A callback could therefore be correct in
isolation and still be wrong for the current Task, Stage, generation, code
fingerprint, or remote head.

The locked design has one rule:

> A domain owner changes its own state synchronously. Asynchronous
> infrastructure executes requested work and reports facts; it never decides
> the next domain state.

The implementation preserves the complete development and review product, not
only the happy path.

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
    writer of CapacityLease. No dispatcher, executor, or adapter may launch an
    Operation without its exact lease.
24. **C24** — A Task, Stage, Turn, Operation, or provider session is not a JVM
    thread. Only an admitted running execution temporarily borrows a worker;
    durable queueing and external/user waits hold no worker.
25. **C25** — ExecutionDispatcher owns exactly two V2 execution facilities:
    one shared virtual-thread-per-admitted-operation executor and one small
    scheduled maintenance executor. Async families are logical lanes, not
    separate pools, and executor queues never represent workflow state.
26. **C26** — New Task creation is permanently V2-only. Application creation
    enters through typed TaskCreationHandoff, and the database rejects every
    non-V2 Task insert. LEGACY Task, Turn, Stage, AgentRun, validation, and
    effect rows are immutable history: no legacy scheduler, admission bridge,
    executor, or recovery loop may claim or mutate them. Normal Task work
    cannot create an AgentRun. The sole schema-compatibility exception is an
    already-terminal, hidden, detached review header required by retained
    `review_round` foreign keys; it is inserted once with no Workspace, Trunk,
    Task, or Stage ownership and is never a Session, lifecycle state,
    accounting projection, or execution
    authority. ReviewAssignmentTurn owns the corresponding work and result.
    Normal Task work cannot consume reserved Trunk-control capacity.
    Development-flow canary
    properties and conditional bean gates do not exist after cutover:
    ExecutionDispatcher and V2 runtime/MCP beans are unconditional, and the
    route diagnostic reports only `v2Only=true`.
27. **C27** — Manual direct-merge consent freezes exactly one merge method
    (`merge`, `squash`, or `rebase`) with the exact-head MergeAuthorization.
    Recovery uses that frozen method and never substitutes another strategy.
    Historical V2 operations created before method capture retain the former
    `squash` behavior. Merge-queue execution remains queue-owned.
28. **C28** — Every explicit user-authorized GitHub write carries one stable
    client command id. Its Task or zero-Task review Trunk owner stores that id
    with the exact PR subject and immutable payload; an identical retry replays
    the same durable action and terminal result, while reuse for different
    input is rejected. Before the first remote effect, execution freezes the
    matching remote-effect ids that already exist, and recovery may adopt only
    an id outside that baseline.
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
    into that field once; `parallel_slots` then remains historical
    compatibility data, not a second admission authority. Capacity
    notifications are post-commit hints: ExecutionDispatcher queues a
    coalesced retry on its owned
    maintenance executor, and transaction-completion callbacks perform no
    repository work.
31. **C31** — On 2026-07-29 the product owner confirmed that there are no users
    and no production legacy data, and explicitly waived the rollout's drain
    observation and retention-window preconditions. Legacy execution was
    therefore retired immediately. The waiver is specific to this cutover: it
    does not authorize deletion of historical rows, and it is not a general
    precedent for a future migration. Historical LEGACY rows remain readable
    and immutable, compatibility mutation ports fail closed, and reintroducing
    legacy execution requires a new locked decision and migration. “No
    retention window” means there is no waiting period before runtime removal;
    preserving any rows that happen to exist is a data-safety rule and does not
    delay or keep legacy execution alive.
32. **C32** — A user-requested local test run is a durable Validation-family
    Operation against one exact active V2 Task code subject. An HTTP caller may
    wait for its result as a convenience, but request timeout does not cancel,
    duplicate, or make the work synchronous. A stable command id is retained
    while the Operation is nonterminal, and result acceptance projects one
    deterministic local check and timeline event at most once.
33. **C33** — Every ReviewAssignmentTurn freezes a positive cost reservation.
    Accepted provider receipts plus live reservations cannot exceed the
    ReviewRound cap, including concurrently admitted seats and follow-up Turns.
    Terminal Turns release unused reservation; retries and follow-ups require a
    new reservation from the remaining durable budget.
34. **C34** — A synchronous development-flow command performs database work
    only. In particular, Approve & ship freezes authorization and promotion
    requirements without Git, filesystem, credential, provider, or GitHub I/O.
    ExecutionDispatcher proves those requirements while executing the durable
    Operation. Every review command that can admit a new seat—initial start,
    Continue, Re-review, an answer-driven follow-up, or a scheduled/delta
    request—likewise persists exact snapshot intent and performs no source
    capture or provider I/O synchronously. Periodic V2 redrive and archival
    scans run only as dispatcher-owned MaintenanceWork; they may request owner
    commands but are never lifecycle owners or independent schedulers.
35. **C35** — Workspace issue intake and quality scans are discovery-only
    scheduled initiators. They may create a typed V2 Task assignment and issue
    an exact synchronous Plan owner command, but they cannot inspect or mutate
    legacy lifecycle rows, create AgentRuns, or launch execution. Automated
    Plan approval records `AUTOMATION` as its actor kind and must satisfy the
    same current-revision, self-review, and follow-up fences as a human or
    policy approval.
36. **C36** — Every user-visible write on a Task-owned remote PR enters one
    typed, durable user-remote-action protocol. The authorization freezes the
    semantic action, immutable payload, exact Task/Remote Stage generation,
    PR binding, head/base SHA, and stable client command id before dispatcher
    execution. This includes manual CI rerun, draft/readiness, title/body,
    close, inline and thread comments, edits/deletes, reviewer/assignee/label
    changes, reactions, and thread resolution. A repeated command id replays
    the same authorization; a changed payload is rejected. Only an exact
    top-level comment may use the preserved terminal-PR exception; every other
    action requires the current open Remote Stage.
37. **C37** — A dispatched code-writing Operation captures every worktree fact
    needed for result acceptance while its exact CapacityLease and writer
    fence are still active. The immutable raw result carries the output head,
    fingerprint, cleanliness, frozen base, and merge base as applicable.
    Synchronous result delivery performs database work only; it must not run
    Git, inspect the filesystem, or reconstruct evidence after releasing the
    execution lease.
38. **C38** — A reviewed quality-scan `CreateIssue` proposal is not a legacy
    publish action. The quality Task is first canceled; approval then performs
    one database-only command that claims the exact notification, freezes its
    repository/title/body and a stable remote marker, and creates one
    Task-owned `GITHUB_EFFECT` DispatchTicket. ExecutionDispatcher alone calls
    GitHub. Restart reconciliation probes the marker and never repeats issue
    creation. Result delivery records issue provenance and resolves only that
    notification/operation; approval, discard, failure, and recovery never
    invoke TaskPhaseMachine or transition the already-canceled Task. The first
    terminal delivery freezes its exact result/error and delivery timestamp;
    an identical replay is accepted, while a changed replay is rejected and
    provenance is never recorded twice.
39. **C39** — Every seat-admitting review command for a V2 Task is a
    database-only command. Initial start also creates the placeholder
    ReviewSession; start, Continue, Re-review, answer, and scheduled/delta
    commands each create one exact TaskReviewSnapshot Operation and one
    Task-owned `LOCAL_GIT` DispatchTicket for that command. The Operation
    freezes Task epoch, worktree path, code fingerprint, exact head and base,
    options, command payload, and review identity. ExecutionDispatcher captures
    the exact diff only after exclusive Task admission and the writer fence.
    Accepted delivery re-enters TaskCommandExecutor before its fresh database
    transaction creates ReviewAssignmentTurns; request and delivery perform no
    Git or provider I/O. A stale or canceled Task, changed code subject, or
    changed ReviewSession–Task link supersedes the Operation without admitting
    review work, and terminal replay must match the first exact result.
40. **C40** — Spawn build has two explicit ownership modes. Findings on a PR
    writable by the user create a Trunk-owned `REVIEW_FINDINGS` Task assignment
    and resolve only from its TaskOutcome. Findings on somebody else's PR use
    a live zero-Task BUILD Trunk: its immutable AGREED or human-included
    ARBITRATED selection becomes a comment-only proposal and can never
    materialize a writable Task or worktree. Approve and discard are
    database-only commands. Approval freezes one stable command, exact reviewed
    head, marker-bearing review payload, and Trunk-owned
    `GITHUB_EFFECT` DispatchTicket; discard performs no remote effect. The
    dispatcher posts or recovers one COMMENT review outside the frozen remote
    baseline. A delayed GitHub read stays on the same semantic mutation attempt
    and spends no mutation retry budget, but a separate durable observation
    count/deadline prevents an infinite wait. Exact finding revisions resolve
    only after accepted success, and the Trunk cannot be purged while delivery
    or finalization remains live. This immutable zero-Task publication is
    deliberately one-shot: terminal failure never rearms or reuses its
    authorization. The UI exposes the durable reason and directs the user to a
    new review pass/selection for a new publication; Task Retry rules do not
    manufacture a Task or mutate this frozen proposal.
41. **C41** — Publishing a standalone `ReviewPass` is a one-shot durable
    zero-Task review-Trunk effect. A newly seated review thread is created on
    the V2 route; a historical LEGACY thread or `TASK_PHASE`-hosted pass is
    rejected before any authorization, ticket, wake, or lifecycle write.
    Authorization freezes the exact base repository, head repository/ref,
    reviewed head SHA, verdict, ordered finding ids and revisions, rendered
    marker payload, and stable client command. It creates one Trunk-owned
    `GITHUB_EFFECT` DispatchTicket; only ExecutionDispatcher may execute or
    probe the GitHub review. Accepted delivery alone may mark exactly those
    findings POSTED and the pass PUBLISHED. Queued, running, retryable failure,
    indeterminate, published, and terminal failure remain readable after app
    restart. A terminal one-shot failure never rearms; the UI directs the user
    to start a new review pass. Unfinished or unfinalized publication blocks
    physical Trunk purge, while finalized accepted history purges explicitly
    child-before-parent.
42. **C42** — A V2 TaskTurn or StageTurn receives only its typed V2 tool
    profile. Legacy lifecycle, TaskPhase, generic artifact, PR-check, and
    review-verdict mutation tools are never exposed to that provider session.
    After a successful writer-capable StageTurn, ExecutionDispatcher
    checkpoints any provider changes as a deterministic commit inside the
    exact Task writer fence, excluding ByteQuay's hook directory, and captures
    the resulting head, fingerprint, cleanliness, frozen base, and merge base
    before releasing either lease. A clean no-change Turn creates no empty
    commit. Owner delivery consumes only that immutable evidence.
43. **C43** — A Task-owned PR has one stable local identity and one write
    boundary. Creation or remote adoption occurs only inside its exact typed
    Task command/Operation; generic PR synchronization and controller
    fallbacks cannot create, replace, alias, or refresh that Task binding.
    Task-owned reads use the stored binding, and a taskless request cannot
    infer ownership from repository, number, current branch, or a nullable
    Task id.
44. **C44** — The desktop persists each explicit remote-write command id
    atomically before transport, keyed by a digest of its complete semantic
    intent rather than by its body text. Transport loss and process restart
    retain the same id. After a durable authorization response, a surface may
    clear the transport-retry key only if it truthfully reports the action as
    queued; a surface claiming published, merged, or any other completed state
    clears only from the durable terminal projection. Definitive client
    rejection may also clear the key. Authorization alone is never displayed
    as external-effect success.
45. **C45** — A visible GitHub write for a PR with no Task owner uses one
    deterministic born-V2 REVIEW Trunk for the exact Workspace/repository/PR
    identity. Its database-only authorization requires an unambiguous
    Workspace repository mapping and a complete cached remote subject,
    including exact base and head SHAs; it performs no GitHub read. One
    immutable Trunk-owned `GITHUB_EFFECT` action freezes the semantic action,
    payload, stable command id, remote baseline, and subject. Dispatcher
    execution and exact accepted delivery own the effect and durable status
    projection. This covers draft/readiness, title/body, comments and reviews,
    edit/delete/reply, reviewer/assignee/label changes, reactions, thread
    resolution, CI rerun, approval, merge/dequeue, and auto-merge controls.
    A taskless push-driven CI trigger is rejected because it has no Task
    worktree or writer fence. Historical direct AI-review publication and
    random-command service overloads are retired rather than bypassing this
    protocol.
46. **C46** — `AgentRun` is immutable compatibility data after cutover. A new
    row may be inserted only as the exact already-terminal, hidden
    `review_compatibility_header` required by the retained `review_round`
    foreign key. That header has null Trunk, Task, and Stage ownership and can
    never be updated, reparented, resumed, accounted, or exposed as a Session.
    ReviewSession and ReviewAssignmentTurn own all review lifecycle and usage.
47. **C47** — Quick review is a one-seat preset of the typed ReviewSession and
    ReviewAssignmentTurn flow. Its pre-seat snapshot is an unscoped
    `REMOTE_OBSERVATION` operation with lane mask 64. It freezes the exact
    repository, remote PR number, base branch, base/head, diff, and
    capabilities, admits through CapacityManager, reserves durable round
    budget, and exposes diff-only investigation tools; repository-file access,
    self-refutation, and a separate verifier seat are unavailable. It has no
    Workspace, Trunk, or Task execution owner, does not count as a running Task,
    and has no application-executor job, in-memory run map, legacy AI-review
    draft, or direct provider/GitHub publication path. Review GET endpoints are
    projection-only.
48. **C48** — Every typed ReviewRound stores one immutable source snapshot
    before any seat is admitted. The snapshot includes the repository, remote
    PR number, base branch, PR title and description, exact base/head, diff,
    changed-file list, complete bodies for every non-deleted changed file,
    capabilities, and source coordinates retained only as capture provenance.
    Full review advertises `frozen-changed-files`: it may read those persisted
    bodies but cannot claim arbitrary repository source, repository callers,
    or Git history. Quick review remains diff-only. Every result, guidance,
    restart, deterministic coverage pass, verification, and finalization
    continuation loads that row only; uncaptured paths fail closed, typed CLI
    work uses a neutral non-checkout directory, and no continuation may fetch
    GitHub, run Git, or read mutable filesystem state after the dispatch lease.
    V2 Task reviews derive it from the accepted TaskReviewSnapshot result, and
    standalone reviews derive it from the accepted ReviewSessionSnapshot
    result.
49. **C49** — Every seat-admitting command for a standalone ReviewSession is
    database-only and creates one durable ReviewSessionSnapshot Operation with
    a ReviewSession-owned DispatchTicket. Quick capture follows C47. Full
    capture is Workspace-only, uses combined `LOCAL_GIT` + `GITHUB` lane mask
    48, and is serialized symmetrically against every same-Workspace
    `LOCAL_GIT` CapacityLease. It has no Trunk or Task owner and consumes no
    running-Task count. The Operation freezes repository, remote PR number,
    base branch, PR title and description, exact base/head, command payload,
    Workspace and local
    repository coordinates before I/O. A changed PR subject, Workspace
    repository binding, local path, or later Task/Trunk attachment supersedes
    it without admitting a seat. Accepted delivery alone persists the frozen
    ReviewRound snapshot and admits typed seats.
50. **C50** — Migration V292 widens DispatchTicket ownership to
    `REVIEW_SESSION` only through a forward-only canonical SQLite table
    rebuild that preserves every row, explicit index, trigger, foreign key,
    and integrity invariant. Migration V293 adds the standalone and
    per-command Task snapshot Operations, their preparation projection, exact
    ticket-shape guards, and purge guards: a live ticket blocks deletion and a
    terminal ticket is deleted with its owning snapshot Operation. Neither
    migration weakens historical-row immutability.
51. **C51** — Workspace deletion is the one force-delete boundary for a
    standalone full ReviewSession. It persists cancellation on every exact
    ReviewSessionSnapshot and ReviewAssignmentTurn ticket before signaling an
    active handler, transactionally authorizes the ReviewSession cascade, then
    removes the exact outbox, delivery claim, execution evidence, CapacityLease,
    and DispatchTicket graph before deleting the Workspace parent. An unknown
    standalone-review ticket shape or uncanceled live ticket fails closed. A
    non-cooperative handler may return after the cancellation signal and purge
    commit, but the absent ticket and owner fence result delivery and evidence
    finalization from recreating any state; evidence failure remains fatal while
    the exact ticket still exists.
52. **C52** — Workspace repository detach and re-clone are destructive
    Workspace commands, not generic Session pause controls. They are rejected
    until every V2 Task in the Workspace is terminal and every Workspace-scoped
    DispatchTicket is terminal; paused Tasks and waiting, retryable, claimed,
    result-pending, or delivering tickets are not quiescent. The application
    performs an actionable preflight, while database guards are authoritative
    at the serialized write boundary. The reciprocal admission guard rejects a
    new Workspace-scoped DispatchTicket while the repository is detached or an
    active re-clone is queued, forking, cloning, or syncing. A race therefore
    has one winner: either the ticket commits and blocks the destructive
    command, or the destructive command commits and blocks the ticket. There is
    no Workspace-wide pause mutation; exact Task and Stage owners must cancel
    or finish their own work before repository replacement.

## Owners and boundaries

| Owner | Sole write authority |
|---|---|
| TrunkManager | Trunk lifecycle and conversation, Task creation authorization, Trunk policy, Task outcome inbox |
| TaskManager | Task lifecycle and epoch, Task assignment, branch/worktree/stable PR binding, Stage graph, Task Brain, Task automation policy |
| PlanStageManager | Plan revisions, Plan state, plan follow-ups, self-review wait, approval eligibility |
| LocalDevelopmentStageManager | Local implementation checkpoint, validation, private local review threads/batches, publish eligibility |
| RemoteDevelopmentStageManager | Accepted remote head, CI checkpoint, remote inbox and rounds, readiness, merge workflow |
| CleanupStageManager | Quiescence and cleanup checklist |
| ReviewSessionManager | PR-subject advisory review sessions, optional Workspace/Task attachment, durable standalone snapshot preparation, seats, findings, guidance, and budgets |
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

Each Turn freezes a positive provider-cost reservation at admission. The
database rejects a Turn or later round-budget reduction when accepted receipts
plus live reservations would exceed the ReviewRound cap. Completion records
the exact receipt and releases unused reservation; a retry or follow-up Turn
reserves again from the durable remainder.

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
Review start, Continue, Re-review, answer, and scheduled/delta commands end at
that durable boundary; none reads GitHub, runs Git, inspects a worktree, or
launches a provider before commit.

### Asynchronous execution pattern

1. ExecutionDispatcher claims a DispatchTicket after capacity admission.
2. It leases and heartbeats the ticket.
3. It invokes an agent, local process, Git, or GitHub adapter.
4. It records raw execution evidence, including fenced output-code facts for
   a code-writing attempt before releasing its execution and writer leases.
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
User-requested local tests belong to Validation; they do not add an eighth
family. A desktop request may wait for a terminal result, but the durable
Operation continues after a transport timeout and keeps the same command id
until terminal completion.

The user-visible push-driven CI trigger is not an alias for rerunning failed
checks. Its typed user action composes the Local Git and GitHub lanes in one
writer-required DispatchTicket; the command transaction performs no I/O. The
dispatched operation creates or adopts one exact marker empty commit, pushes it
under the Task's writer fence, and proves the local, remote-branch, and PR heads
before advancing the current worktree subject. Recovery resumes that same
marker commit instead of creating another one.

Quality-scan issue creation also remains inside the existing GitHub-effects
family; it is not an eighth family and owns no scheduler. Its approval endpoint
returns after durable authorization. A GITHUB lease covers both the initial
marker probe and the create call. If the process loses the response after the
mutation begins, later attempts only probe the exact marker; absence is parked
for reconciliation rather than treated as permission to create again.

Zero-Task external PR actions also reuse the GitHub-effects family and the
shared dispatcher. Their deterministic review Trunk is a durable domain owner,
not a worker, pool, or scheduler. Merge and auto-merge semantic actions retain
their exact remote subject and method/policy evidence even when delivered by
that shared handler; this does not create an additional async family.

Review snapshot preparation also composes existing families rather than adding
an eighth family. A TaskReviewSnapshot is Task-owned `LOCAL_GIT`. A standalone
quick ReviewSessionSnapshot is unscoped `REMOTE_OBSERVATION`; a standalone full
ReviewSessionSnapshot combines `LOCAL_GIT` and `GITHUB` under one Workspace
lease. Only the accepted snapshot result can make a ReviewRound eligible to
admit typed review Turns. Full capture also copies the complete body of each
non-deleted changed file into that immutable row while the lease is held;
after release, changed-file reads and deterministic reference coverage use
only those copies.

Retiring AgentScheduler does not create a replacement scheduler for helper
calls. GlobalReviewRunner, HarnessDiagnosisService, LessonExtractor,
InvestigationReviewRunner, and CliReviewRunner invoke their provider
synchronously in the execution context established by their caller. They own
no queue, retry loop, worker pool, or lifecycle transition. When such a call is
part of a Task-owned V2 Operation, dispatcher and capacity admission occur
before the helper is entered. Standalone review assignments remain typed
ReviewAssignmentTurns; the older ReviewPass renderer uses an exact
ReviewCallContext and runs calls synchronously inside its existing bounded
review owner rather than introducing legacy admission.
Typed review provider processes always receive a neutral non-checkout working
directory. Their source surface is the typed tool contract backed by the
ReviewRound snapshot, never ambient checkout access.

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

The scheduled executor also invokes registered MaintenanceWork in short,
bounded passes. MaintenanceWork may discover eligible exact owners and issue a
synchronous owner command that persists durable work. It cannot perform the
effect, hold workflow state in its schedule, or create another executor. Idle
Task archival and standing-consent local auto-publish redrive use this path.

There is no mixed-version execution path after retirement. ExecutionDispatcher
and CapacityManager are the only development-flow execution and admission
authorities. Historical LEGACY rows may appear in read projections, but no
worker claims them and no legacy bridge, scheduler, pool, semaphore, or
recovery loop participates in admission.

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
- A standalone full-review snapshot owns no Task, but its Workspace-scoped
  `LOCAL_GIT` + `GITHUB` lease conflicts symmetrically with every
  same-Workspace `LOCAL_GIT` lease. It does not increment the Workspace or
  Trunk executing-Task count. A quick snapshot is an unscoped, diff-only
  `REMOTE_OBSERVATION` lease and likewise does not count as a Task.
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

The V2 hard ceilings remain four CLI executions and six API executions. One
permit inside each ceiling is reserved for Trunk control; Task-owned work
cannot consume that reservation. Other lanes, including validation and
read-only review, require explicit configured ceilings and may not bypass
CapacityManager.

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

### Manual cherry-pick conflict boundary

A Workspace cherry-pick conflict is not development-flow execution. The
command returns the retained worktree path and exact conflict paths so the user
can resolve or abort manually. It creates no Trunk, Task, Stage, Turn, provider
session, or legacy scheduler work. A later automated conflict-resolution
feature would require a typed V2 Operation and a separately locked contract.

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
4. Provisioning discovers any existing PR subject and creates or adopts the
   exact branch/worktree under dispatcher admission.
5. TaskManager accepts provisioning evidence and opens Plan.

Steps 1–3 are one database-only command: they perform no credential, GitHub,
Git, filesystem, or provider I/O. For an existing PR, creation freezes the
local repository route and PR number. A review-findings Task additionally
freezes the current review selection. The ProvisionTaskOperation owns remote
discovery under combined GitHub and Local Git capacity, proves the exact base
and head repositories, refs, and SHAs, and records that subject in its durable
source proof and result. Delivery rejects a changed review selection or a
result whose discovered subject differs from the frozen route and PR number.
Exact remote fields retained on older assignments remain readable for upgrade
and replay but are not synchronous input to new Task creation.

TaskAssignment variants must cover:

- NEW_FROM_TRUNK with plan seed, planning-base SHA, and prompt
- EXISTING_OWN_PR with repository route and PR number
- REVIEW_FINDINGS with review session, PR number, and selected finding ids
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

There are two explicit Task-review start commands:

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
- Spawn build from review through either a writable Task assignment or a
  zero-Task comment proposal, selected from immutable PR authorship and route

For a V2 Task review, every command that could admit another seat—initial
start, Continue, Re-review, answer, or a scheduled/delta request—first freezes
the current Task epoch, worktree, code fingerprint, exact head/base, command
payload, review options, and ReviewSession in one transaction. A per-command
TaskReviewSnapshot Operation captures the exact diff under exclusive Task
admission and the writer fence. Its accepted delivery enters the exact Task
command boundary before creating ReviewAssignmentTurns in a fresh transaction.
Neither request nor delivery reads Git or invokes a provider. A stale or
canceled Task, changed code subject, or changed Task attachment supersedes the
snapshot and admits no seat.

For a standalone ReviewSession, those same commands persist a
ReviewSessionSnapshot Operation instead. Quick review is unscoped,
`REMOTE_OBSERVATION` lane 64, and diff-only. Full review is Workspace-only,
uses combined `LOCAL_GIT` + `GITHUB` lane 48, and freezes the configured local
repository coordinates while CapacityManager excludes every same-Workspace
`LOCAL_GIT` lease in both admission orders. Neither scope creates or counts an
executing Task. Both freeze repository, remote PR number, base branch, exact
base/head, diff, capabilities, and applicable local coordinates before a seat
is admitted. A subject, repository binding, local path, or ownership-link
change supersedes the Operation rather than rebuilding input synchronously.

Review seats are read-only. They cannot edit a Task worktree. Selected findings
enter Local or Remote Development through an explicit immutable feedback batch.
Unselected findings remain advisory history.

For a local or otherwise writable PR, imported review findings become private
local comments. A ReviewSession cannot publish them or manufacture a GitHub
approval. One nonterminal build Task per PR remains enforced. Review findings
are resolved from that Task's explicit outcome, not merely because a Task was
created.

For somebody else's PR, `suggested_change` creates a zero-Task BUILD Trunk and
an immutable comment proposal; Task creation and worktree materialization fail
closed. The user reviews the frozen proposal locally and then explicitly
approves or discards
the proposal. Approval records a stable command and one Trunk-owned
`GITHUB_EFFECT` ticket without calling GitHub. Execution verifies the exact
reviewed head and creates or recovers one COMMENT review containing the frozen
inline and top-level comments. Discard has no remote effect. Only accepted
success resolves the exact frozen finding revisions; an unfinished action also
blocks physical Trunk purge.

If a standalone external-PR review is later attached to a Task, attachment
does not silently make it blocking. The user or calling use case must choose.

### Promotion from local to remote

Approve and ship is the single promotion authority.

Its synchronous command is database-only. It freezes authorization, required
preflight facts, effect identities, and one DispatchTicket; it performs no Git,
filesystem, credential, provider, or GitHub I/O. The dispatched publish
Operation proves the required clean worktree, minimum commits ahead, branch,
base, and publish permission immediately before external effects. Failure
leaves durable evidence and cannot be converted into an approval claim.

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
- immutable review_round source snapshot with repository, remote PR number,
  base branch, PR title/description, exact base/head, diff, changed-file list,
  complete non-deleted changed-file bodies, capabilities, and capture-only
  local coordinates
- task_review_snapshot_operation and per-command Task review-round snapshot
  operation for exact Task input capture under Task writer admission
- review_session_snapshot_operation for durable standalone quick/full capture,
  terminal preparation projection, and ReviewSession-owned dispatch
- review_build_comment_proposal, proposal item, action, and dispatch records
- zero-Task external_pr_action and dispatch records, with the exact cached PR
  base/head subject and terminal delivery projection
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
- A review snapshot result is accepted only if its frozen repository, remote PR
  number, base branch, PR prompt metadata, exact base/head, owner/link,
  Workspace binding, local coordinates, and command identity still match. Any
  mismatch is superseded before seat admission. After acceptance, mutable PR
  identity and source locations are never consulted for round execution.
- Snapshot delivery is replay-safe: the first terminal result is immutable and
  an identical redelivery is accepted, while a changed redelivery fails
  closed. A nonterminal snapshot ticket prevents owner purge; deleting a
  terminal snapshot Operation deletes its terminal ticket in the same owner
  cleanup boundary.
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

A ReviewSession projection exposes its latest snapshot preparation as
REQUESTED, COMPLETED, FAILED, CANCELED, or SUPERSEDED, with scope and terminal
error when present. REQUESTED is a durable pre-seat state and continues to poll
across restart; a terminal preparation failure is not misreported as a
completed ReviewRound. This projection cannot capture input or admit a seat.

A promoted Trunk conversation is one compatibility read over two immutable
ledgers: retained LEGACY rows first in their positive sequence space, followed
by typed rows ordered by the Trunk aggregate version that exposed them. Typed
compatibility sequence values are the negative of that durable, JSON-safe
version. They are UI identities and exact paging cursors only; physical typed
Turn/message sequences remain positive and domain-local. Readers must not
compare the mixed values numerically, infer ownership from their sign, or use
`seq < cursor`. Historical LEGACY rows are immutable after retirement, so the
positive prefix cannot gain new execution output; a tail refresh still
restarts an incomplete paging cursor to preserve the compatibility protocol.

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
| User Run tests | exact Task-owned Validation Operation | Durable and idempotent; a waiting HTTP response does not own execution |
| DevReport and deep context handoff | immutable DevReport | Preserved |
| Private local PR and review timeline | stable PR + LocalReviewThread/Batch | Preserved across promotion/Cleanup |
| Manual publish override | audited PublishOverride | Preserved; automation forbidden |
| Quick/full/scheduled/delta agent review, Continue, Re-review, and answer | PR-owned ReviewSession + per-command TaskReviewSnapshot or ReviewSessionSnapshot Operation | Preserved; every seat-admitting command is DB-only, source capture is durable and exact, and standalone quick/full admission uses its locked unscoped/Workspace capacity shape |
| Concurrent review cost limit | ReviewRound receipts + ReviewAssignmentTurn reservations | Frozen before launch and enforced across seats/follow-ups |
| Spawn build from review | writable REVIEW_FINDINGS Task assignment or foreign-PR zero-Task comment proposal | Preserved for AGREED and human-included ARBITRATED findings, with authorship-specific ownership and no unauthorized worktree |
| First push and Draft PR create/adopt | PublishOperation/effect steps | Preserved and crash-safe |
| Direct/fork routing | RemotePrBinding | Preserved |
| CI pending/green/red/no-check policy | exact-head RemotePrSnapshot | Preserved and made explicit |
| CI rerun/fix/budget/fallback | CiRepairEpisode | Preserved with separate counters |
| Scheduled branch guard | Remote Stage BranchSyncEpisode + Task write lease | Preserved |
| Remote comment/review-body handling | RemoteInboxItem/RemoteFeedbackBatch | Preserved; body-only verdict fixed |
| Reply/resolve/push round gate | immutable authorization + effect cursor | Preserved and recoverable |
| Direct user remote-PR controls | Task-owned user-remote-action or zero-Task external-pr-action authorization + DispatchTicket | Preserved across restart with stable command replay, honest terminal projection, and exact-subject fencing |
| Push-driven CI trigger | typed user action + writer-required Local Git/GitHub DispatchTicket | One restart-safe empty commit and exact push; distinct from failed-check rerun |
| Auto-ready/keep-draft | TaskAutomationPolicy | Preserved |
| autoApprove/autoMerge/min approvals | policy revision + exact evidence/authorization | Preserved and exact-head |
| Standing-consent local auto-publish | dispatcher MaintenanceWork + owner command | Deterministic redrive; no independent scheduler or direct effect |
| Merge queue bounce/retry | MergeOperation | Preserved |
| Pause/resume/retry/archive | Task lifecycle + exact blocker/operation | Preserved without generic resume |
| Durable permissions and approval budgets | PermissionRequest/policy grant | Preserved and restart-safe |
| Parallel Tasks and scope limits | CapacityManager | Preserved and enforced |
| Task worker and executor ownership | CapacityManager + ExecutionDispatcher | Temporary admitted workers; durable waits use no thread |
| Task trace/timeline/status/notifications | read-only projections | Preserved |
| Task completion summary/follow-ups | TaskOutcome + Trunk inbox | Preserved and made reliable |
| Runtime/worktree/branch cleanup | Cleanup Stage/step ledger | Preserved and made durable |
| Historical LEGACY compatibility | read-only projections + invariant auditor | Rows remain readable; no worker claims them and every mutation seam fails closed |
| Issue/quality scheduled intake | typed V2 assignment + exact Plan owner command | Discovery preserved; automation owns no lifecycle or executor |
| Removed future-Task queue | none | Remains removed |

## Required acceptance scenarios

These scenarios remain the regression contract, including restart and
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
50. Load terminal historical LEGACY siblings beside V2 Tasks; verify their
    traces remain readable and no legacy execution is claimed or resumed.
51. Leave Tasks waiting for capacity, CI, review, permission, and user input;
    verify they hold no workflow worker or executing-Task capacity lease.
52. Run all new agent, validation, Git, and Task-owned review work solely
    through V2; verify CapacityManager enforces the global/Workspace/Trunk
    policy with no legacy bridge, pool, or semaphore bypass.
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
62. Upgrade a database containing historical LEGACY rows. Verify the rows
    remain readable, no worker or recovery loop claims them, every retired
    mutation port fails closed, and migration V277 rejects a new LEGACY Task
    while accepting typed V2 Task creation.
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
67. Request Run tests twice with one stable command id, lose or time out the
    first HTTP response, and verify one exact Validation-family Operation,
    process execution, accepted result, local PR check, and timeline event. A
    changed code subject supersedes the stale result without projection.
68. Approve & ship an eligible local PR and prove its command transaction
    performs no Git, filesystem, credential, provider, or GitHub I/O. Restart
    before dispatch and verify the Operation proves every frozen promotion
    requirement before its first effect.
69. Admit review seats and follow-up Turns concurrently near the ReviewRound
    cost cap. Verify durable receipts plus live reservations never exceed the
    cap, retries reserve again, and terminal unused reservation is released.
70. Call every public Task, Stage, review, publish, and remote-action mutation
    seam for a historical LEGACY Task; each fails closed before scheduling,
    Git, provider, or GitHub I/O while the same history remains readable.
71. Trigger idle archival and standing-consent local auto-publish repeatedly.
    Verify only dispatcher MaintenanceWork discovers candidates, owner
    commands are idempotent, and no independent scheduler performs effects or
    changes lifecycle state.
72. Let issue intake and a quality scan create V2 Tasks and auto-approve their
    reviewed Plans. Verify each automation records an `AUTOMATION` approval
    against the exact current Plan revision, never reads a legacy Task phase,
    and creates no AgentRun or direct provider execution.
73. Invoke every visible remote-PR write for a V2 Task, lose the first HTTP
    response, and retry with the same command id. Verify one immutable semantic
    authorization, one dispatcher-owned effect, exact open-head fencing (or
    the top-level terminal-comment exception), payload-mismatch rejection, and
    no direct controller GitHub call.
74. Complete Local Development, remote-feedback repair, and remote CI/branch
    repair Turns, then deliver each result after its execution lease has been
    released. Verify the dispatcher-captured output subject is accepted once
    and result delivery performs no Git or filesystem inspection; reject a
    missing, dirty, stale-base, or wrong-merge-base subject.
75. Invoke rerun-failed when no failed check run exists and verify it does not
    stand in for the push-driven CI trigger. Crash that trigger after its empty
    marker commit and again after its push but before PR-head refresh. Verify
    one marker commit, one eventual exact push, combined capacity and writer
    ownership, one current-worktree-subject advance, ordinary Remote observation
    of the new head, and no duplicate effect when the command is replayed.
    Delay PR-head propagation across repeated probes and verify the same
    semantic attempt remains live rather than exhausting its budget.
76. Approve and discard reviewed quality-scan `CreateIssue` proposals. Verify
    approval performs database work only, owns one exact Task-scoped GitHub
    ticket, survives restart by marker probe without a second create, and
    resolves the exact notification on typed result delivery. Replay the same
    terminal result and a changed one; verify the former is idempotent, the
    latter is rejected, and provenance is recorded once. Verify discard performs
    no remote call, and neither path writes Task phase or lifecycle. Retitle and
    close the created issue and place it beyond the first 100 results; marker-
    first paginated recovery must still find the exact issue.
77. Create existing-own-PR and review-findings Tasks while the GitHub adapter
    is unavailable. Verify synchronous creation commits only route, PR number,
    and immutable local/review input; provisioning waits for combined GitHub
    and Local Git capacity, discovers one exact remote subject, rejects a stale
    review selection, and replays durable proof without adopting a changed
    subject. Upgrade historical exact rows and V1 results and prove they remain
    readable and deliverable.
78. Request an agent review for one exact V2 Task subject. Verify the HTTP
    command atomically records the placeholder ReviewSession, Task snapshot
    Operation, and `LOCAL_GIT` ticket without Git or provider I/O. Restart before
    capture and before delivery; accept one exact diff inside TaskCommandExecutor
    and create ReviewAssignmentTurns once. Replay the same result, move the head,
    and cancel the Task; only exact current evidence may admit review work and a
    stale placeholder becomes terminal without launching a seat.
79. Spawn review work for somebody else's PR. Verify it creates a zero-Task
    BUILD Trunk and immutable comment proposal, rejects Task/worktree creation,
    and exposes database-only approve and discard commands. Reuse a command id
    with the same and changed payload, move the reviewed head, and crash around
    the GitHub COMMENT review; exact baseline/marker recovery creates one review
    and mismatched input fails closed. Delay GitHub visibility beyond the
    ordinary attempt limit and verify probe-only observation spends no semantic
    mutation retry, while its separate bound eventually records an actionable
    terminal failure instead of polling forever. Discard makes no remote call,
    an archived Trunk cannot approve, included ARBITRATED findings behave like
    AGREED findings, findings resolve only after accepted success, and physical
    purge is rejected until delivery and finalization complete, then succeeds
    in child-before-parent delete order. A terminal failure displays its reason
    and requires a new review pass/selection rather than rearming the action.
80. Publish a standalone ReviewPass, lose the authorization response, restart
    before and after the GitHub effect, and prove one born-V2 zero-Task review
    Trunk, one marker-bearing review, and one accepted finalization of the
    frozen finding revisions. Reject LEGACY/TASK_PHASE ownership before any
    write, keep unfinished work purge-protected, and expose a one-shot terminal
    failure without rearming it.
81. Complete a dirty writer-capable StageTurn. Verify its provider tools contain
    only the typed V2 profile, its changes are staged and committed inside the
    exact writer fence, ByteQuay hooks are excluded, and the immutable clean
    output subject is captured before lease release. A clean no-change Turn
    creates no empty commit; a result missing exact captured evidence cannot
    advance its owner.
82. Synchronize and mutate PRs while a Task owns one stable PR binding. Verify
    generic sync and taskless controllers neither replace nor alias the Task
    binding, stored Task reads remain exact, and no nullable/latest/branch
    inference selects an owner.
83. Invoke every visible write on a PR with no Task owner, including approval,
    merge, dequeue, and auto-merge controls. Verify database-only authorization
    resolves one exact Workspace repository and born-V2 REVIEW Trunk, requires
    a complete cached base/head subject, creates one Trunk-owned action and
    dispatcher ticket, and performs no controller GitHub call. Restart through
    authorization, effect, and delivery; the client reuses its command id
    across transport ambiguity, may report queued from durable authorization,
    and reports completion only from the durable terminal projection. Reject an
    ambiguous/missing repository mapping, stale/incomplete subject, changed
    command payload, direct AI-review publication, and taskless empty-commit CI
    trigger before external I/O.
84. Kill the desktop after persisting a remote-write command id but before the
    request or response. Verify restart reuses the id for the exact semantic
    intent, corrupt command storage fails closed, two intentional identical
    writes can receive distinct ids after terminal completion, and accepted
    authorization may clear the transport key only when the UI reports queued
    and never displays external-effect success.
85. Insert the sole allowed review compatibility header and verify it is
    already terminal, hidden, detached from Trunk/Task/Stage, and immutable.
    Reject every other AgentRun insert, update, reparent, transition, resume,
    accounting write, and Session-control path without affecting its linked
    ReviewRound history.
86. Start quick review twice for one exact external PR head. Verify one typed
    one-seat ReviewRound, durable capacity/budget admission, diff-only tools,
    no repository read tool, restart-safe status, and idempotent terminal
    projection. Prove no application executor, in-memory quick-run map, legacy
    AI draft endpoint, or direct publish path can perform the review.
87. Restart or steer after every typed review seat result. Verify each
    continuation reads the immutable ReviewRound snapshot only, never Git,
    GitHub, or the filesystem; complete frozen changed-file bodies remain
    readable after the checkout changes or is deleted, uncaptured paths fail
    closed, deterministic coverage searches only frozen bodies, and typed CLI
    work starts outside the checkout. Block a result whose route, prompt
    metadata, base/head, or snapshot identity does not match the exact round.
88. For a Task-attached review, invoke initial start, Continue, Re-review,
    answer, and scheduled/delta review. Verify each command commits its own
    exact TaskReviewSnapshot and Task-owned `LOCAL_GIT` ticket without Git,
    GitHub, filesystem, or provider I/O; capture runs only after exact Task
    writer admission, and accepted delivery re-enters TaskCommandExecutor
    before admitting a seat.
89. For a standalone ReviewSession, run every seat-admitting command in quick
    and full scope. Verify quick capture is unscoped `REMOTE_OBSERVATION` lane
    64 with diff-only capabilities; full capture is Workspace-only
    `LOCAL_GIT` + `GITHUB` lane 48, conflicts with every same-Workspace
    `LOCAL_GIT` lease in both admission orders, and neither scope consumes a
    running-Task count.
90. Change the repository, remote PR number, base branch, base/head, Workspace
    repository binding, PR title/description, local path, changed-file body, or
    ReviewSession Task/Trunk attachment after snapshot authorization. Verify
    the frozen result is superseded and no seat starts. Restart and replay
    exact delivery; verify the immutable repository/number/branch/prompt
    metadata/base/head/diff/changed-file bodies/capabilities/capture
    coordinates are reused without source I/O and a changed terminal replay
    fails closed.
91. Upgrade a populated pre-V292 database through V293. Verify the canonical
    DispatchTicket rebuild preserves every row, explicit index, trigger,
    foreign key, and integrity check while adding only `REVIEW_SESSION` owner
    support. Verify V293 ticket-shape guards reject mismatched ownership and
    lanes, live snapshot tickets block purge, and terminal owner cleanup
    removes the terminal ticket without orphaning either side.
92. Delete a Workspace containing terminal and genuinely claimed/running
    standalone full-review work. Verify cancellation intent commits and the
    dispatcher signals the exact running handler before ReviewSession removal;
    the typed snapshot/turn rows, outbox wake, delivery claim, execution
    evidence/log, CapacityLease, DispatchTicket, and purge authorization all
    disappear without a foreign-key violation. Hold a non-cooperative handler
    past purge commit and Workspace deletion, then let it return success; no
    owner, evidence, ticket, delivery, or Workspace state is recreated. An
    unexpected ticket shape and an evidence failure for a still-live ticket
    must continue to fail closed.
93. Attempt Workspace repository detach and re-clone while the Workspace has
    an active, paused, provisioning, or Cleanup Task, or a requested, claimed,
    retryable, result-pending, or delivering DispatchTicket. Verify both
    commands fail without changing repository state. Race ticket admission
    against each destructive command and verify exactly one commits: an
    admitted ticket blocks detach/re-clone, while a committed detach or active
    re-clone blocks later Workspace-scoped tickets. After every V2 Task and
    ticket is terminal, verify the destructive command succeeds; verify ticket
    admission resumes only after repository reattachment or successful
    re-clone completion.

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

## Retired runtime and retained compatibility source

The following runtime authorities have been physically removed:

- AgentScheduler and its shared agent-runner pool
- LegacyCapacityBridge, LegacyCapacityLeaseMaintainer, and LegacySagaCapacity
- LegacyReviewAdmission
- TaskRuntimeProjector and its executor
- the legacy validation executor and lease-renewal pools

Retired compatibility mutation seams remain only where an older API still
needs a stable type. RetiredThreadTurnScheduler rejects enqueue requests,
ValidationExecutorRegistry rejects execution and renewal, and RetiredSagaGate
never admits work. They own no worker, queue, callback, capacity lease,
recovery loop, or state transition.

Some legacy source classes and schemas are intentionally still present. This
includes TaskPhaseMachine, TaskLifecycleDriver, AutomationCoordinator,
TaskPrePushDriver, TaskRuntimeStopReconciler, TaskSchedulerConflictBridge,
CiFixRunExecutor, LocalCiFixExecutor, StageLifecycle, PlanStageService, and
ReviewPassService. Their presence is not runtime authority: they may support
historical reads, DTO/API compatibility, or a fail-closed boundary, but they
cannot create or claim new legacy work. TaskIdleArchiver is active V2
MaintenanceWork and InvestigationReviewService is the typed
ReviewAssignmentTurn coordinator; neither is a legacy execution owner.
Physical deletion of read-only compatibility source is optional cleanup and
is not a migration-completion condition.

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

### 3.3 — 2026-07-30

- Made repository detach and re-clone explicit Workspace destructive commands
  that require terminal V2 Tasks and DispatchTickets instead of pretending a
  Workspace-wide pause can stop aggregate-owned execution.
- Added reciprocal database guards so repository replacement and new durable
  admission are serialized, with exactly one winner under a race.

### 3.2 — 2026-07-30

- Made every seat-admitting review command database-only. Task-attached start,
  Continue, Re-review, answer, and scheduled/delta requests now use an exact
  per-command TaskReviewSnapshot before typed seat admission.
- Added the durable standalone ReviewSessionSnapshot boundary: quick capture is
  unscoped, diff-only `REMOTE_OBSERVATION` lane 64; full capture is
  Workspace-only `LOCAL_GIT` + `GITHUB` lane 48, is mutually exclusive with
  same-Workspace Local Git work, and does not count as a Task.
- Froze repository, remote PR number, base branch, PR title/description, exact
  base/head, diff, complete non-deleted changed-file bodies, capabilities, and
  capture-only local coordinates in the immutable round source; owner/link or
  subject changes supersede capture before seat launch. Full reviews expose
  only frozen changed-file reads after capture; quick reviews remain diff-only.
- Locked the V292 canonical DispatchTicket rebuild and the V293 snapshot
  operation, preparation projection, exact-shape, and cleanup guards.

### 3.1 — 2026-07-30

- Made the retained AgentRun foreign-key header terminal, hidden, ownerless,
  and fully immutable; ReviewSession and ReviewAssignmentTurn are the only
  review lifecycle and accounting owners.
- Replaced the application-executor quick-review bypass with a one-seat typed
  ReviewAssignmentTurn preset, enforced diff-only tool access, and made review
  reads projection-only.
- Persisted an immutable source snapshot for every typed ReviewRound so result,
  guidance, restart, deterministic coverage, verification, and finalization
  continuations are DB-only, including frozen PR prompt context and complete
  changed-file bodies.
- Retired legacy Stage streaming and replaced the production ThreadRegistry
  runner with a fail-closed, threadless compatibility executor.

### 3.0 — 2026-07-29

- Completed the no-user hard cutover: V2 provider sessions expose only typed
  tools, successful code-writing StageTurns checkpoint changes under the exact
  writer fence, and Task-owned PR identity cannot be mutated by generic sync
  or taskless controller fallbacks.
- Persisted desktop remote-command ids before transport and required durable
  terminal projections before the UI clears an accepted command or reports
  its GitHub effect complete.
- Routed every supported taskless PR write through one exact zero-Task REVIEW
  Trunk, immutable external-action record, and dispatcher ticket; required a
  complete cached base/head subject and retired direct AI-review/random-command
  bypasses. A taskless empty-commit CI trigger now fails closed because it has
  no worktree writer owner.

### 2.9 — 2026-07-29

- Made standalone ReviewPass publication a V2 zero-Task review-Trunk
  `GITHUB_EFFECT` with exact remote subject, finding revisions, stable command,
  marker payload, bounded recovery, and accepted-delivery finalization.
- Required new review threads to be born on V2 and rejected historical LEGACY
  or TASK_PHASE publication before any write; exposed durable publication
  state for restart-safe queued, indeterminate, success, and terminal-failure
  UI.
- Blocked purge while publication remains live and defined explicit
  child-before-parent deletion after accepted finalization.

### 2.8 — 2026-07-29

- Split review-build execution by PR ownership. Writable authored PRs retain
  the `REVIEW_FINDINGS` Task path; somebody else's PR now creates a zero-Task
  comment proposal with database-only approve/discard and one Trunk-owned,
  exact-head, restart-safe GitHub review Operation.
- Required finding resolution only after accepted remote success and blocked
  physical Trunk purge while comment delivery or finalization remains live.
- Separated bounded GitHub observation from mutation attempts, admitted
  human-included ARBITRATED findings, rejected archived owners, and made a
  terminal one-shot failure actionable through a new review pass rather than
  silently rearming frozen authorization.

### 2.7 — 2026-07-29

- Made V2 Task review startup database-only. It atomically freezes one exact
  Task code subject and DispatchTicket; dispatcher-owned `LOCAL_GIT` capture
  runs under exclusive writer admission before Task-command-bound delivery
  creates ReviewAssignmentTurns.
- Required stale, canceled, duplicate, and restarted snapshot delivery to fail
  closed without Git or provider work in the request/delivery transactions.

### 2.6 — 2026-07-29

- Made existing-PR Task creation database-only. The immutable assignment now
  freezes repository route, PR number, and any review selection; dispatcher-
  owned provisioning discovers and proves the exact remote subject under
  combined GitHub and Local Git capacity before opening Plan.
- Kept historical exact assignments and V1 provisioning evidence readable
  across the schema upgrade while requiring exact V2 remote-subject evidence
  for newly deferred assignments.

### 2.5 — 2026-07-29

- Removed V2 quality-scan `CreateIssue` approval from synchronous legacy
  PublishService execution. Approval now freezes a marker-bearing operation
  for the dispatcher-owned GitHub lane; typed, database-only delivery resolves
  the exact notification without changing the already-canceled Task.

### 2.4 — 2026-07-29

- Preserved the push-driven CI control as a distinct, durable Local Git plus
  GitHub protocol. It creates or resumes one exact marker empty commit under
  dispatcher admission and the Task writer fence; failed-check rerun is not a
  semantic substitute.

### 2.3 — 2026-07-29

- Required every code-writing AgentTurn to capture its immutable output code
  subject inside dispatcher-owned execution while the CapacityLease and
  writer fence remain active; synchronous result delivery is database-only.

### 2.2 — 2026-07-29

- Routed scheduled issue intake and quality scans through typed V2 Task
  assignment and exact Plan owner commands, with truthful `AUTOMATION`
  approval identity and no legacy lifecycle inference.
- Completed durable exact-head parity for the direct user controls exposed on
  Task-owned remote PRs, using one stable-command authorization and dispatcher
  protocol instead of controller-side GitHub writes or predictable 409s.

### 2.1 — 2026-07-29

- Classified manual Run tests as a durable, idempotent Validation-family
  Operation and separated HTTP waiting from execution ownership.
- Froze per-ReviewAssignmentTurn cost reservations and required concurrent
  receipts plus reservations to remain within the ReviewRound cap.
- Made synchronous Approve & ship database-only and moved promotion-requirement
  proof into the dispatched publish Operation.
- Put idle archival and standing-consent auto-publish discovery under
  dispatcher-owned MaintenanceWork rather than independent schedules.
- Added hard-cutover acceptance coverage for every historical LEGACY mutation
  boundary.

### 2.0 — 2026-07-29

- Locked immediate legacy-runtime retirement after the product owner confirmed
  there are no users or production legacy data, explicitly waiving the former
  drain-observation and undefined retention-window preconditions.
- Made new Task creation permanently V2-only through TaskCreationHandoff and
  migration V277's database trigger while retaining historical LEGACY rows as
  immutable, readable data.
- Removed development-flow canary properties and conditional bean gates;
  ExecutionDispatcher and V2 runtime/MCP beans are unconditional and the route
  diagnostic reports only `v2Only=true`.
- Recorded the physical removal of AgentScheduler, the legacy capacity
  bridge/maintainer/saga admission components, LegacyReviewAdmission,
  TaskRuntimeProjector, and their task-flow-specific execution facilities.
- Required every retained compatibility mutation seam to fail closed and
  clarified that remaining legacy source may serve read-only/API compatibility
  without being an execution authority.
- Recorded that GlobalReviewRunner, HarnessDiagnosisService, LessonExtractor,
  InvestigationReviewRunner, and CliReviewRunner invoke providers
  synchronously in their owning execution context instead of scheduling a
  second asynchronous job.
- Preserved standalone reviews as typed ReviewAssignmentTurns; older
  ReviewPass calls use an exact ReviewCallContext and execute synchronously in
  their bounded review owner rather than through legacy admission.
- Kept cherry-pick conflicts manual: the retained worktree and conflict paths
  are returned to the user, with no legacy Task, Turn, Stage, or agent
  execution created.

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
