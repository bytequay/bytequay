# Development flow architecture

Status: **LOCKED**

Version: **3.34**

Decision date: **2026-07-28**

This document is the normative architecture contract for development-flow
ownership, state transitions, persistence, asynchronous work, recovery, and
cleanup.

The tracked
[Project Intelligence architecture](./project-intelligence-design.md) is the
normative extension for learned context, approved direction, review
intelligence, briefing, and review voice. It is subordinate to this document
for lifecycle, admission, execution, source snapshots, and recovery.

The development-flow core in this document and its tracked migration plan is a
self-contained implementation contract. Files under `docs/mockups/` are
optional local design history, not required inputs. The preserved product
topology and journey are restated below. If an older note describes TaskPhase,
AgentScheduler, generic AgentRun, CI_FIXING stages, nullable turn scope,
event-driven lifecycle
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
    or Git history. Quick review remains diff-only. Every result, restart,
    deterministic coverage pass, verification, and finalization continuation
    loads source evidence from that row only. After Project Intelligence
    cutover, review objectives and guidance may additionally load only the
    exact immutable round-intelligence row required by C53; it supplies no
    source evidence and grants no additional source capability. Uncaptured
    paths fail closed, typed CLI work uses a neutral non-checkout directory,
    and no continuation may fetch GitHub, run Git, or read mutable filesystem
    state after the dispatch lease. V2 Task reviews derive the source row from
    the accepted TaskReviewSnapshot result, and standalone reviews derive it
    from the accepted ReviewSessionSnapshot result.
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
53. **C53** — Project Intelligence is a Workspace-owned context system, not a
    lifecycle owner. It may select bounded Plan and full-review angles,
    applicable areas, objectives, recipes, and ordering, but it cannot change
    Stage shape, admission, review class, panel, budget, source capability,
    evidence, severity, verification, failure class, publishability, or
    verdict. After Project Intelligence cutover, each new Plan revision and
    full ReviewRound uses one exact immutable intelligence projection; a quick
    review uses an explicit empty projection, and no admitted seat or
    continuation reloads live intelligence. A changed basis requires a new Plan
    revision, and review requires a new ReviewRound. A Plan-scoped exception may
    cover only a deliberate deviation from an item already present in the
    still-current frozen basis and typed STEWARDSHIP concern; it cannot accept a
    basis change. The same mandatory Plan self-review is never rerun. Project
    Intelligence adds no Stage, scheduler, executor, or capacity authority.
54. **C54** — A successful Plan self-review with CHANGES_REQUESTED records its
    exact concerns and enters the existing AWAITING_APPROVAL checkpoint; it
    does not launch an automatic redraft. The user may request a revised Plan,
    which creates a new revision and one self-review, or atomically dismiss
    every current PLAN_REASONING CONCERN as incorrect review reasoning with an
    attributed explanation and record an exact HUMAN approval on the same
    revision. Every exact concern must still be OPEN, and generic follow-up
    commands cannot resolve or defer CONCERN or STEWARDSHIP. A
    PROJECT_DIRECTION concern carries its exact frozen item and statement
    digest as non-dismissible STEWARDSHIP.
    POLICY and AUTOMATION still require APPROVED. BLOCKED, failed, no-verdict,
    partial, stale, or open-STEWARDSHIP cases cannot use adjudication. A changed
    intelligence basis requires a new Plan revision, while a deliberate
    Direction deviation remains the C53 exact scoped-exception path.
    Adjudication is immutable approval evidence, not another self-review; it
    cannot mutate Project Intelligence, add a Stage, or authorize an override
    in a downstream implementation BrainReviewEpisode or Agent Review.
55. **C55** — CLI conversation continuity is an exact-owner optimization, not
    process or lifecycle ownership. Every admitted Turn starts a fresh CLI
    process, but the domain owner may freeze the successful predecessor's
    `agent_execution.provider_session_id` together with a complete durable
    fallback prompt before admitting an eligible continuation. The CLI adapter
    owns only resume invocation and the bounded fallback attempt; it never
    chooses lineage or reconstructs domain history. Eligibility requires the
    causally latest Turn in the same typed lineage, the same provider, model,
    working directory, capability profile, and current subject, plus no live
    consumer: one Trunk conversation; one Task Brain conversation or exact
    wait source; one Stage id, generation, and code subject; or one
    ReviewAssignment seat and purpose-specific subject. Trunk order is its
    returned aggregate version, Task conversation order is durable Turn row
    order, Review order is its logical attempt, and queued Stage APPEND resolves
    the latest earlier admitted successor before applying success and
    compatibility fences. Wall-clock timestamps and UUID order are never
    lineage authority. A failed, ambiguous, or incompatible newer Turn blocks
    fallback to an older session. A session is never shared across sibling
    Tasks, Stage generations, review seats, or concurrent consumers. The new
    Turn's owner-scoped MCP endpoint is rebound on every invocation.

    A finite noninteractive Claude Task Brain that intentionally omits the
    permission-prompt callback preapproves only the exact active MCP tool names
    frozen for that invocation, rendered against that owner-scoped server. It
    never receives a wildcard, profile-wide superset, user-wait tool, or native
    write tool. A fresh fallback after an unavailable provider session preserves
    both the absent callback and that exact allowlist; a tool-free Turn remains
    tool-free. This is CLI transport plumbing for the already-authorized
    catalog, not a new capability or lifecycle authority.

    A TaskTurn may retain a non-null trigger Stage id as immutable provenance
    and dispatch fencing, but its live MCP `ThreadScope.TASK` carries only the
    Task id and a null Stage id. A StageTurn alone carries
    `ThreadScope.STAGE` plus its Stage id. Provider transport must not collapse
    those two identities or relax the typed `ToolCall` scope invariant.

    Every spawned CLI process is registered before prompt delivery. The
    execution keeps one current recovery PID while immutable, sequential
    `agent_execution_process_attempt` rows retain both the rejected resume and
    its bounded fresh fallback. Replacing the current PID and recording the
    next process attempt is one durable transaction; failed registration stops
    the new process instead of allowing untracked provider work.

    Codex CLI terminal usage is cumulative for its provider session, not a
    per-Turn receipt. Every resumable Codex launch freezes the already-accounted
    input and output totals with the exact session id. An ordinary settled
    predecessor supplies its immutable raw terminal totals; an exact USER_WAIT
    source without a terminal receipt carries forward its frozen launch
    baseline. A source without a trustworthy baseline is reconstructed fresh.
    The adapter retains each terminal cumulative pair as raw result evidence and
    records only its non-negative difference from the frozen baseline as this
    Turn's usage. A partial or regressing pair fails the Turn, cannot seed a
    later resume, and is not replayed. An unavailable-session fallback starts a
    new provider session with a zero baseline.

    Only a provider-specific, recognized missing or expired session response
    received before any provider-work or unknown-output evidence may restart
    that same admitted Turn once without resume, using its already-frozen
    fallback. Codex prompts, including reconstructed fallbacks, are delivered
    over stdin rather than process arguments. Timeout, transport loss, partial
    or unknown output, accepted work, usage, or any other ambiguous failure is
    never replayed. A semantic retry and an explicit user
    CANCEL_AND_REPLACE start fresh. An exact typed USER_WAIT continuation may
    use cancellation/quiescence machinery internally without becoming a
    semantic replacement and may resume only its exact settled source. A Task
    Brain rejects an overlapping message before persisting attachments or a
    second Turn; the client may queue it and submit after the current Turn is
    terminal. API Turns always reconstruct their context from durable owner
    data and never carry a CLI resume token. `TASK_COMPLETION_SUMMARY`,
    historical `REMOTE_CI_BRAIN_REVIEW`, and `BRANCH_SYNC_BRAIN_REVIEW` are
    finite, noninteractive TaskTurns and never expose question or permission-wait
    tools. ReviewAssignmentTurn MCP is likewise evidence-only and cannot create
    a user wait: a standalone seat has no required Trunk route, and every seat
    must return a terminal review result. Retained ReviewAssignment wait rows
    and continuation code are readable compatibility state, not an admitted
    runtime path.
56. **C56** — A non-`UNKNOWN` remote CI failure classification requires one
    versioned, immutable typed provenance proof for the exact Remote Stage and
    generation, accepted RemotePrSnapshot and CI evaluation, subject head and
    base, provider/check identity and profile, actual tested subject kind and
    SHA (including a verified synthetic pull-request merge subject), evidence
    completeness, and stable head/base failure fingerprints. When every
    concrete failed check has internally complete exact head/base lineage but
    those fingerprints do not prove one unanimous origin, the persisted result
    is `TASK_BRANCH_REPAIRABLE`, not a Task-origin claim. It authorizes only an
    append-only repair on the current named Task branch. Missing, duplicate,
    partial, mixed-profile, unsupported-schema, mismatched, or raw-text-only
    evidence is `UNKNOWN`. Check names, log substrings, blocker labels, and
    user-selected classification labels are not provenance and cannot
    authorize a code mutation.

    Schema-v5 may additionally carry `ACTIONS_JOB_LOG_V1` evidence for a
    concrete GitHub Actions failure only after the exact completed run,
    attempt, complete attempt-scoped job set, job, check run, check suite, and
    tested SHA are already bound. The adapter downloads that exact job id to
    EOF with an eight-MiB hard limit, requires strict UTF-8, freezes the raw
    byte count and SHA-256 digest, and runs the versioned
    `MAVEN_COMPILER_V1` parser. The parser is complete only when every
    non-boilerplate Maven compiler error in the compilation section maps to a
    canonical diagnostic and a sorted stable fingerprint. GitHub Actions may
    timestamp javac `symbol`, `location`, `required`, `found`, and `reason`
    continuation records without repeating Maven's `[ERROR]` marker; the
    parser accepts only that fixed continuation grammar while inside an active
    compiler diagnostic. Missing, malformed, or duplicate canonical
    diagnostics still fail closed. Raw log bytes are not persisted. Empty,
    expired, denied, oversized, partial, invalid-UTF-8,
    unrecognized, or partly parsed captures remain `UNKNOWN`; transient
    provider failures retry the read-only Observation instead of manufacturing
    proof. Annotation proof remains preferred, and dependency-aggregate proof
    is attempted before the log fallback. A deterministic failed-head versus
    failed-base comparison requires the same evidence source, parser id, and
    parser version on both sides. Arbitrary harness signatures, cached tails,
    excerpts, or substrings remain diagnostic-only and never become C56 proof.

    A dependency-only GitHub Actions aggregate may derive the unanimous
    classification of its exact failed dependencies, but only from schema-v4
    proof. That proof freezes the workflow blob fetched at the exact tested
    SHA, exact run and attempt, complete attempt-scoped job set, unique static
    aggregate job key/name, literal nonempty `needs`, and one recognized
    result-only fan-in step whose references exactly equal those dependencies.
    The declared fan-in step must be the aggregate job's only failed runtime
    step; runner setup and teardown must be complete and successful or skipped,
    and workflow/job defaults that could change command semantics are rejected.
    Every runtime job and check identity/outcome must match, at least one
    dependency must have failed, and every failure-requiring dependency must
    carry its own complete C56 proof. A matrix dependency is supported only
    when its static workflow name is a literal prefix/suffix containing exactly
    one `${{ matrix.<identifier> }}` expression and every runtime instance in
    the complete attempt job set maps uniquely to it; each instance remains a
    separate dependency identity. Other dynamic, matrix, or reusable jobs,
    incomplete pagination, rerun races, ambiguous names, unsupported scripts,
    aggregate-only failure, or independent aggregate failure remain `UNKNOWN`.
    Mixed dependency classifications become `TASK_BRANCH_REPAIRABLE` only when
    the aggregate graph and every concrete failed leaf's head/base comparison
    are otherwise complete and exact; malformed, incomplete, or leafless proof
    remains `UNKNOWN`. Log prose never supplies authority.
57. **C57** — A CiRepairEpisode's exact subject and classification are
    immutable. A later accepted C56 proof may replace an `UNKNOWN` Episode only
    for the same exact Stage generation, head, and base through one serialized,
    durable supersession: any predecessor work must first be canceled or
    reconciled, the successor copies every consumed counter and remaining
    budget, and the old blocker resolves only after exactly one proven successor
    Episode is durable. Replay reuses that successor; stale or different-subject
    proof cannot replace anything. A fresh, exact per-attempt authorization is
    required for every deterministic repair. It freezes the source evaluation
    and snapshot, head/base, original Task-commit manifest, and either the latest
    applicable Task automation-policy revision or the exact open blocker and
    explicit user decision. Authorization is consumed only after its push is
    accepted and is never reused by a retry. `autoApprove`, including when
    implied by `autoMerge`, may authorize a proven base-owned repair on the Task
    PR branch; it never manufactures provenance or permits a direct write to the
    base branch. `TASK_BRANCH_REPAIRABLE` uses the same fresh per-attempt fence
    and finite CI budget, but its authority ends at an append-only current-Task
    repair; it can never supply a base-repair manifest, history rewrite, or
    force-with-lease authority.
58. **C58** — A terminal failed provider/process result for the exact current
    `BRANCH_SYNC_BRAIN_REVIEW` TaskTurn, or for a historical
    `REMOTE_CI_BRAIN_REVIEW` TaskTurn retained across the direct-push cutover,
    is an accepted owner result, not an indefinitely redelivered infrastructure
    error. The first exact delivery freezes the immutable raw-result digest and
    error in one typed failure receipt, terminalizes that TaskTurn and its
    repair-Brain Operation as `FAILED`, clears only their current pending
    delivery fence, and leaves the owning CiRepairEpisode or BranchSyncEpisode
    at the same Brain cursor with one exact `REMOTE_REPAIR_BRAIN_FAILED`
    blocker. An
    identical redelivery returns the same receipt; a changed or stale delivery
    is rejected. No verdict or push was produced, so this transition consumes
    neither semantic CI-fix/branch-repair budget nor the attempt authorization.
    A historical CI Brain failure has no replacement authority after the
    direct-push cutover: it remains readable audit/blocker state and projects no
    Brain Retry. Any pre-cutover nonterminal CI Brain ticket is ineligible for
    provider launch or MCP authorization and settles through the existing
    owner-not-found failure path; terminal result delivery remains readable.
    Generic Task cancellation remains available. A branch-sync
    replacement advances its execution ordinal and reclaims the same Brain step
    without changing that step's semantic `attempt_count` or `attempt_limit`.

    BranchSync recovery is one explicit, idempotent Retry command fenced by Task epoch,
    Remote Stage/generation, Episode and Brain cursor, head/base and code
    subject, failed TaskTurn/Operation, failure receipt, and blocker. It admits
    one fresh TaskTurn, Operation, and DispatchTicket from the predecessor's
    frozen launch context and durable trace, with a new identity and storage
    ordinal. It never resumes the failed CLI provider session or inherits its
    cumulative-usage baseline. Only after the successor pending fence is
    durable may the exact blocker resolve. The replacement fence permanently
    supersedes late predecessor delivery, and command replay returns the same
    successor. The recovery projection, API, and Remote Stage UI expose Retry
    only for that exact current BranchSync failure and disable ordinary competing repair
    controls while replacement is being armed. This is not a generic TaskTurn
    retry and does not reinterpret an ambiguous, nonterminal provider outcome
    as a proven failure.
59. **C59** — A Claude CLI `PLAN_DRAFT` or `PLAN_SELF_REVIEW` TaskTurn always
    carries the exact owner-scoped permission-prompt callback in its frozen
    process arguments, even when its generic runtime tool catalog is empty.
    This is a provider-protocol bridge for the purpose-matching Plan result
    call, not a capability grant. The callback may return the original input
    only for `record_plan` or `record_plan_self_review`, respectively, after
    matching the Task, epoch, Plan Stage/generation, TaskTurn, Operation,
    purpose, endpoint, and call identity. Every mismatch and every other tool
    or mutation is denied. The bridge exposes no generic tool, creates no
    PermissionRequest or user wait, and grants no filesystem, shell, Git,
    GitHub, lifecycle, or policy access. C55 still requires finite automatic
    owners that have no such Plan result bridge to omit the callback argument.
60. **C60** — The Plan Stage overlay reads and edits the Task's canonical,
    revisioned `TaskAutomationPolicy`; it cannot project or write retained
    legacy Task flags or maintain a second Plan-local policy copy. A policy
    mutation is an exact TaskManager command with a stable command id and
    expected policy revision. The desktop serializes those writes and waits
    for every earlier policy response before sending Plan approval, and the
    approval command is fenced by the resulting policy revision. A failed or
    stale write blocks that approval attempt instead of allowing approval
    against old policy. Enabling `autoMerge` atomically enables `autoApprove`
    in the same canonical revision; disabling `autoMerge` does not clear
    `autoApprove`.
61. **C61** — A provider-successful Remote observation whose frozen Task epoch
    is no longer current is terminal delivery evidence but not a
    `RemotePrSnapshot`. Exact delivery records one immutable raw-result receipt,
    marks the observation Operation `SUPERSEDED`, and lets its DispatchTicket
    finish with `SUPERSEDED` delivery acceptance. It creates no snapshot, CI
    evaluation, accepted-snapshot pointer, inbox item, Task terminal intent, or
    lifecycle transition. For this path, migration V310 adds only the schema
    and exact forward reconciliation needed for the
    successful-without-snapshot shape; it does not weaken current-epoch
    snapshot admission or result fencing.

    A Task-owned PR read may display an already-synchronized terminal
    `merged|closed` value from the stable PR cache when its repository and PR
    number exactly match the preserved `RemotePrBinding`. That terminal cache
    overlay performs no remote read and may affect presentation only. It can
    never accept an observation, advance or clean up a Task, authorize a write,
    supply CI/mergeability/readiness evidence, or override a higher-precedence
    lifecycle/blocker state. Cached draft/open or missing state never outranks
    the accepted Remote snapshot.
62. **C62** — Malformed Local Development Brain output has one bounded typed
    repair bridge. The original malformed exact TaskTurn follows the existing
    strict protocol-failure transition and permits one ordinary explicit Retry.
    If that exact retry also returns a provider-successful malformed payload,
    the Task owner records its second protocol-failure receipt and admits at
    most one `DEVELOPMENT_BRAIN_RESULT_REPAIR` TaskTurn for that lineage. The
    repair Turn freezes
    the source malformed raw output and digest, both predecessor failure
    identities, the required result shape, and the complete current Task,
    Local Stage/generation, Brain episode, code fingerprint, head, and base
    fence.

    `DEVELOPMENT_BRAIN_RESULT_REPAIR` receives no ByteQuay/MCP application
    tools, repository source payload, provider resume token, mutation
    authority, permission/user-wait path, or semantic Brain budget. The CLI
    still starts in the frozen working directory under its read-only sandbox;
    provider-native read primitives are transport facilities, not workflow
    capabilities, and the repair instruction forbids using them. Its only
    instruction is to reconstruct one candidate object in the frozen required
    shape from the frozen malformed result. The same unchanged strict decoder
    and exact owner fences judge that candidate. A valid result continues the
    waiting Brain verdict flow without consuming another semantic review
    attempt. A provider/process failure, stale fence, or second malformed
    result terminalizes the bridge, fails the waiting episode, and opens one
    exact manual recovery blocker. No automatic second repair Turn,
    repair-of-repair, provider fallback, or loop is permitted.
63. **C63** — Agent execution evidence is an attempt fence, not optional
    telemetry. An `AGENT_TURN` result may enter owner delivery only when no
    execution row for its ticket is unfinished and its exact current
    infrastructure attempt has terminal raw evidence matching the complete
    pending-result fence and payload. A no-launch cancellation is the only
    result that does not require positive current execution evidence. No
    ticket may claim a replacement execution or reconciliation attempt while
    any earlier execution row for that ticket remains unfinished.

    At the Agent-result codec boundary, `USER_WAIT` is a non-success
    disposition and must not enter a success-payload parser. A `CANCELED`
    DispatchResult with a null payload may synthesize the typed
    `PROVIDER_CANCELED` result needed for delivery only after its immutable
    owner kind, owner id, Operation, attempt, and complete Task/Stage/code
    fence match exactly. Every other null, malformed, or non-success payload
    fails closed rather than being interpreted as provider success.

    ExecutionDispatcher maintenance runs before dispatch admission. It may
    finalize the current execution from an already-durable `RESULT_PENDING`
    result, or terminalize an abandoned execution after its durable retry or
    reconciliation wait becomes due. It may not invoke the provider, handler,
    or external effect. A current `RETRY_WAIT` whose infrastructure failure is
    already durable becomes `FAILED`; a due `RECONCILE_WAIT` or an execution
    overtaken by a later infrastructure attempt remains `UNKNOWN`. V312 repairs
    historical terminal `AGENT_TURN` tickets
    without redelivery: an earlier superseded attempt is `UNKNOWN`; a current
    attempt under a terminal `FAILED` ticket is also `UNKNOWN` because the
    cleared raw outcome could have been `FAILED` or `INDETERMINATE`; only a
    current terminal `SUCCEEDED` or `CANCELED` ticket proves the corresponding
    execution status. Cleanup quiescence counts every unfinished execution
    row, independent of its attempt number. V312 may also terminalize an exact
    historical no-launch cancellation whose `RESULT_PENDING` `AGENT_TURN`
    ticket records `CANCELED`, has zero infrastructure attempts and no
    `agent_execution`, and still matches its immutable typed StageTurn owner
    and complete fence, but whose Stage owner is now completed or noncurrent.
    That correction marks the StageTurn and ticket `CANCELED`, records
    `SUPERSEDED` delivery acceptance plus explicit migration-recovery evidence,
    and clears the pending-result and next-attempt fields without delivery,
    provider execution, handler invocation, or replay. Any owner, fence,
    launch-evidence, outcome, or staleness ambiguity remains untouched.
64. **C64** — An open worktree quarantine is a Task-wide writer barrier, not a
    Stage retry state. Its immutable source records the exact Task, worktree,
    source StageTurn/Operation, Task branch, frozen clean head, and code
    fingerprint that could not be restored. It follows that Task across Stage
    checkpoints and generations and rejects every ordinary V2 worktree writer.

    The only restorative bypass is a Task-owned durable
    `REPAIR_QUARANTINED_WORKTREE` Local-Git Operation created by the typed Task
    recovery command. ExecutionDispatcher executes it only after
    CapacityManager grants the exact Task exclusivity and the worktree writer
    lease grants a fresh fencing token. The Operation also freezes the current
    Task epoch and Stage identity plus the quarantine source; controllers,
    projectors, and Stage managers perform no Git repair.

    Quarantine clears only when exact result delivery accepts proof of the Task
    branch, frozen head, clean code fingerprint, and absence of Git control
    state. Failure, cancellation, stale ownership, incomplete proof, or an
    unsupported Git state leaves quarantine open; an explicit Retry creates a
    new Operation and lease rather than mutating the prior result. Cleanup's
    exact `REMOVE_WORKTREE` step is the sole independent disposal bypass. It
    may resolve quarantine as disposed only after proving the bound worktree
    path absent, and it never re-enables an ordinary writer.
65. **C65** — Current Task code-subject ownership is advanced by one durable
    database-assigned revision coupled to the accepted immutable source fact.
    Process time, source timestamps, UUID/string order, and projection read
    order are evidence only and never decide which local subject is current.

    Every accepted V2 DevelopmentReport, admitted Remote worktree result,
    Remote steering result, passed CI base-rewrite result, and accepted local
    publish-base-sync result appends exactly one source-keyed revision in the
    same transaction. A DevelopmentReport becomes a code subject only when its
    exact successful Local StageTurn delivery receipt accepts that report and
    its validation Operation; inserting or parsing the report alone grants no
    ownership. Replay cannot append another revision for the same source.
    Revisions are compared only within the exact Task epoch, so sibling Tasks
    and old epochs cannot affect ownership. A completed local BASE_SYNC
    StageTurn hands authority to its accepted DevelopmentReport revision as
    before. Missing or ambiguous revision evidence fails closed before another
    writer or push can use that subject.
66. **C66** — One provider-successful, malformed Remote CI repair StageTurn
    result may enter exactly one bounded Task-owned result-normalizing lineage.
    The failed source StageTurn and its raw provider result remain immutable.
    Admission freezes that source identity, raw text and digest, exact required
    Stage-result JSON shape, provider-execution proof, Task epoch, Remote Stage
    and generation, CiRepairEpisode and cursor, semantic/execution attempt,
    worktree and Task branch, and current code/head/base subject. A
    provider/process failure, an indeterminate outcome, a BranchSync StageTurn,
    a Remote repair Brain TaskTurn, or a source without the complete exact fence
    is not eligible.

    The lineage admits at most one fresh, no-resume, read-only
    `REMOTE_REPAIR_RESULT_NORMALIZATION` TaskTurn. It receives only the frozen
    malformed result, required shape, and identifying fences. It receives no
    ByteQuay/MCP application tools, repository payload, prior provider session
    or resume token, writer lease, mutation authority, permission path, or user
    wait. It debits neither a model/semantic-attempt budget nor the CI repair
    budget; actual
    provider execution and usage remain immutable audit evidence. Its frozen
    instruction forbids provider-native filesystem or repository reads. The
    same unchanged strict Remote CI Stage-result decoder judges its candidate.
    There is no prose inference, decoder fallback, provider substitution,
    ordinary Retry, second normalizer, or repair-of-repair.

    A malformed writer for a V322-or-later source captures immutable candidate
    proof before restoring its source under the original writer fencing token.
    That proof identifies the candidate commit, its exact source parent and
    changed tree, the Task branch/worktree, and the original execution window;
    the normalizer itself never touches Git. After a strict changed-tree Stage
    result is accepted, the Remote owner may create exactly one durable
    `ADOPT_NORMALIZED_REMOTE_REPAIR` Local-Git Operation. CapacityManager first
    admits the exact Task and the worktree writer lease then supplies a fresh
    token. The handler may only fast-forward the restored exact Task branch
    from the frozen source to the uniquely proven candidate, independently
    re-prove its parent and changed tree, and record the resulting clean code
    subject. It may not discover arbitrary commits, cherry-pick, merge, reset
    to an unproven object, or accept the normalizer's assertion as Git proof.

    V322 has one compatibility-only exception for an eligible malformed writer
    that became terminal before V322 and was restored before candidate capture
    existed. Under the same exact source/execution fences, adoption may inspect
    only that frozen execution's bounded reflog window and may proceed only if
    exactly one commit has the source as parent and a changed tree. Zero,
    multiple, expired, or otherwise ambiguous candidates fail closed. No
    V322-or-later source and no unrelated historical row may use reflog proof.

    V323 closes the initial-DevelopmentReport ownership gap without weakening
    that exception. It extends the one canonical C65 revision ledger with the
    `DEVELOPMENT_REPORT` source kind and backfills only an exact V2 report that
    is still the Task's current code subject, has no matching ledger revision,
    and has its successful accepted Local StageTurn delivery receipt and
    matching Task/epoch/Stage/generation/Turn/code subject. A superseded
    historical report is not appended after its successor and can never steal
    current ownership. An otherwise-exact pre-V322
    malformed repair omitted solely because that accepted report lacked a C65
    revision may then be migration-allowlisted under all unchanged C66 source,
    execution, blocker, authorization, and bounded-reflog fences. Runtime may
    neither mint this compatibility authority nor substitute nullable or
    SHA-only evidence. For a terminal delivered ticket, the StageTurn,
    CI-repair Operation, and owner delivery receipt share the exact owner
    completion instant; the dispatcher terminalizes the already-bound ticket
    afterward, so its completion time must be greater than or equal to that
    instant rather than falsely equal to it. Ticket/Operation/owner identity,
    execution attempt, raw result digest, and receipt remain the authority.

    The accepted DevelopmentReport lineage also makes
    `local_stage_turn_request` and `local_stage_turn_delivery_receipt`
    required historical leaves. Because both intentionally retain exact
    StageTurn references instead of cascading independently, the
    transaction-local V2 Trunk purge owns their child-before-parent deletion
    after quiescence authorization; it deletes the receipt before the request
    and only for Tasks owned by that exact archived Trunk.

    The existing exact `CI_REPAIR_OUTPUT_MALFORMED` blocker remains open from
    source failure through normalization and adoption; no duplicate blocker is
    created. Normalizer admission changes neither that blocker nor base-repair
    authority. Only after strict candidate acceptance may an immutable
    continuation receipt preserve or reactivate
    the original exact CI base-repair authorization for this lineage; it never
    creates a new authorization. Successful Stage adoption advances the
    durable current-code revision, charges the original applicable changed-tree
    CI-fix attempt exactly once, resolves the blocker, and resumes ordinary
    validation, publication, and CI without Task Brain review.

    Provider/process failure, another malformed result, stale ownership,
    failed or ambiguous Git proof, or adoption failure leaves the source
    failure and blocker intact, closes any reactivated authorization, and
    exposes manual recovery only. A writer outcome that cannot be restored also
    opens the existing C64 quarantine; it does not authorize a second adoption.
    Replay returns the same receipts and can neither charge again nor start
    another Turn or Operation. Malformed BranchSync Stage and Remote repair
    Brain output retain their existing strict terminal/manual or explicit-Retry
    behavior; extending normalization to either requires another locked
    decision.

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
    ^                            |
    +--------- revise -----------+
~~~

- Plan content is revisioned.
- Exactly one mandatory Brain self-review applies to each candidate final
  revision.
- A material Plan or governing-basis change creates another revision and
  invalidates approval evidence.
- Auto-approval may approve the latest APPROVED-reviewed revision when Task
  policy permits.
- User follow-ups are recorded against a revision.
- note-plan-concern facts and Project Direction checks are part of
  the reviewed Plan evidence.
- APPROVED waits for the normal approval decision. CHANGES_REQUESTED stores
  at least one exact CONCERN and waits for the user; it never starts a redraft
  by itself.
- From CHANGES_REQUESTED, Revise Plan creates a new revision. Adjudicate and
  approve is an all-or-nothing HUMAN command that dismisses every exact concern
  as incorrect reasoning and approves the same revision.
- A viable deliberate Direction deviation is APPROVED with STEWARDSHIP linked
  to the exact frozen item and statement digest, not CHANGES_REQUESTED. From a
  CHANGES_REQUESTED result, the exception route creates a substantive revision
  that states the bounded deviation, rationale, rejected aligned alternative,
  risks, and compensating checks. A malformed or misclassified
  verdict/concern/stewardship combination opens the existing review blocker.
- BLOCKED, failed/no-verdict review, an open STEWARDSHIP concern, or any stale
  Task, Stage, revision, self-review, content, intelligence, or current
  Direction fence cannot be adjudicated.
- Task and Stage optimistic versions are part of that fence. A same-generation
  owner mutation rejects a stale displayed decision.
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
  committed, reviewable diff exists and initially remains `local-drafted`.
  Accepting the exact current Brain outcome that advances Local Development
  from `BRAIN_REVIEW` to `LOCAL_REVIEW`--either `APPROVED`, or
  `BRAIN_BUDGET_EXHAUSTED` with its exact open escalation blocker--must, in the
  same serialized Task transaction, advance that stable Task-owned PR to
  `local-open` and append one status event. A missing or non-Task PR, or a
  status other than `local-drafted|local-open`, rejects and rolls back the
  whole handoff; exact replay at `local-open` is idempotent. This is local
  readiness, performs no external I/O, and is not local-to-remote promotion.
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
- quarantined worktree
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

Its operation-scoped MCP exposes only frozen review-evidence tools. It does not
advertise or accept `ask_user_question` or `approval_prompt`; each finite seat
must return a terminal result. This is required for standalone reviews, which
need not have an owner Trunk through which a durable user wait could be listed
or answered.

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

ReviewAssignment question/permission tables remain readable for compatibility,
but the current evidence-only MCP cannot create new rows in them.

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

For an Agent Turn, making the DispatchTicket `RESULT_PENDING` and finalizing
its current `agent_execution` evidence are separate durable writes around a
crash boundary. Delivery claiming is therefore ineligible while that exact
execution row is unfinished or absent, even though the raw ticket result is
already durable. The exact current terminal row must also match the complete
pending result. Dispatcher-owned MaintenanceWork retries only evidence
finalization from the already-durable result; it never reruns the provider,
handler, or external effect. Once evidence is terminal, normal result delivery
may be claimed. A retry/reconciliation claim is likewise fenced on the absence
of every unfinished prior attempt. V312 forward-reconciles historical
impossible terminal-ticket / unfinished-execution shapes using the conservative
status rules in C63, without changing or redelivering the terminal ticket. This
lets Task quiescence and cancellation Cleanup observe the true absence of live
execution.

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

### Worktree quarantine and repair

If a writer-capable AgentTurn cannot prove restoration of its exact frozen
source before releasing its writer lease, it durably opens one worktree
quarantine. The quarantine retains its source StageTurn and Operation for
audit, but admission treats it as a Task/worktree-wide barrier: replacing the
Stage, opening a later generation, steering, resuming, or observing a new
remote fact cannot make an ordinary writer eligible.

`REPAIR_QUARANTINED_WORKTREE` reuses the Local Git/worktree async family. Its
typed command performs database work only and atomically records the immutable
repair Operation, DispatchTicket, and wake. Admission requires the exact open
quarantine, Task/worktree identity, current Task epoch and Stage identity,
frozen Task branch, frozen clean head and fingerprint, plus sole Task and
worktree-writer ownership. The repair lease is a narrow bypass of the
quarantine admission check; it does not weaken fencing for another path or
Task.

After rechecking those fences under the live writer token, repair mutates the
bound worktree in this order:

1. inspect Git control state and abort only a recognized in-progress operation
   under the explicit repair authority; an unsupported or malformed state
   fails closed
2. discard quarantined tracked and untracked dirt at the checkout's current
   `HEAD`, without moving a possibly foreign branch ref
3. switch to the exact immutable Task branch
4. reset that Task branch to the frozen head and clean the worktree again
5. prove the current branch is the Task branch, `HEAD` is the frozen head, the
   worktree is clean, the code fingerprint is the frozen clean fingerprint,
   and no rebase, merge, cherry-pick, revert, or sequencer control state remains

The Operation restores a known source; it never rebases, merges, salvages, or
infers quarantined changes. In particular, it never resets to the frozen head
before the exact Task branch is checked out, so it cannot move an unrelated
branch ref.

The handler records one immutable result while its writer lease is live.
Delivery then records one immutable receipt, and only exact accepted success
may resolve the quarantine and its blocker. If the process crashes after
filesystem mutation, reconciliation acquires a new fencing token and proves
the complete target state before adopting success. If a prior immutable result
exists but DispatchResult or delivery did not finish, reconciliation re-proves
that state and replays the original evidence; it neither trusts the expired
token nor blindly repeats the mutation.

Infrastructure reconciliation may continue the same Operation. A terminal
failed, canceled, or superseded repair remains immutable and leaves quarantine
open. A user Retry creates a new Operation, DispatchTicket, capacity lease, and
writer token against the still-current quarantine and owner fences. If the
Task epoch, current Stage, worktree, branch, frozen source, or quarantine
identity changed, admission or delivery fails closed. Task cancellation does
not reinterpret repair as success; Cleanup may instead use its independent
disposal path below.

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
- A Plan TaskTurn is code/worktree read-only, but its purpose-matching result
  tool is owner delivery, not a worktree mutation. Claude CLI's
  `approval_prompt` automatically allows only `record_plan` for `PLAN_DRAFT`
  or `record_plan_self_review` for `PLAN_SELF_REVIEW` after exact
  TaskTurn/Operation/purpose authorization. Every mismatch and every other
  mutation is denied without creating a PermissionRequest. C59 requires that
  exact callback in Claude's frozen argv for these two purposes even when no
  generic runtime tools are advertised; this exception does not expose a tool
  or broaden the result gate.
- Task Brain performs one self-review TaskTurn for that revision.
- The use case records the Brain result in Task and Plan.
- APPROVED enters the normal approval wait. CHANGES_REQUESTED persists its exact
  concerns and enters the same wait without starting agent work.
- The user may request revision; the existing redraft path then creates a new
  revision with its own self-review.
- The user may instead adjudicate a CHANGES_REQUESTED result. The serialized
  Plan-owner command requires an attributed, nonblank DISMISSED_INCORRECT
  decision for every exact CONCERN, resolves them, and records HUMAN approval
  atomically. A partial or stale command changes nothing.
- Adjudication cannot accept BLOCKED, failed/no-verdict, or open-STEWARDSHIP
  evidence. POLICY and AUTOMATION approval still require APPROVED.
- Generic resolution and deferral remain limited to FOLLOW_UP; every adjudicated
  CONCERN must still be OPEN when the atomic command starts.
- If Project Intelligence is wrong or inapplicable, the user corrects it through
  its owner and creates a new Plan revision with a new frozen basis and one
  self-review. The Plan text may remain identical when the basis digest changed.
- Revision idempotency is scoped to the stable producing command or Turn. Its
  replay returns the original row; a fresh candidate identical to the
  immediately current prose and basis is a no-op, while A/X to B/X to A/X is
  valid monotonic revision history.
- Human, policy, or automation approval applies only to the exact reviewed
  revision.
- The Plan overlay exposes the complete selected canonical
  `TaskAutomationPolicy` revision. Its edits are serialized exact Task commands,
  not Stage writes. The client waits for all preceding policy mutations before
  approval and submits the resulting policy revision as an approval fence;
  backend rejection is authoritative if the revision changed.
- `autoMerge=true` and `autoApprove=true` are committed together in one policy
  revision. No projection may briefly persist or approve against the invalid
  combination `autoMerge=true, autoApprove=false`.
- Approval completes Plan and lets Task open Local Development.
- If HUMAN approval used adjudication, the exact bounded concern resolutions
  accompany the approved Plan in downstream development Turns. They prevent
  blind replay of the Plan-review concern but do not suppress a new Brain or
  review finding supported by new code evidence.
- Replan first requests whole-Task quiescence. No new work is admitted.
- Once all claimed mutating work is stopped or reconciled, Task epoch advances
  and a new Plan Stage generation opens.

The Task Brain compatibility projection parses the canonical stored Plan JSON
as structured data rather than Markdown. Its exact shape has root `status`,
`understanding.summary`, and `intent`; `intent` contains `summary`, ordered
`steps` with `order`, `action`, and optional `file` or `files`, plus
`validationStrategy` and `expectedFilesChanged`. Optional root `goal` and
`outOfScope` remain projection inputs. Historical prose revisions retain the
existing Markdown-heading fallback. A valid canonical Plan must therefore
project its real steps and policy controls; it cannot silently become a
zero-step card because a Markdown parser consumed JSON text.

### Development and Brain loop

1. Local Development admits a StageTurn for implementation or exact findings.
2. The Stage accepts the Turn result only for its operation and fingerprint.
3. It requests canonical validation for the new fingerprint.
4. Green validation lets the Stage request Task Brain review.
5. Task opens BrainReviewEpisode and TaskTurn for the same fingerprint,
   DevReport, and finding set.
6. Task accepts the Brain verdict.
7. The use case hands approved/findings to Local Development.
8. Approved enters Local Review through that atomic PR-open boundary.
9. Changes requested create another bounded StageTurn.
10. Budget exhaustion opens a blocker and enters Local Review through the same
    atomic PR-open boundary with an explicit unresolved escalation. It never
    records approved.

The Local Development Brain is a finite typed TaskTurn. Its launch prompt
requires a terminal JSON object with `schemaVersion=1`, verdict
`APPROVED|CHANGES_REQUESTED`, a string summary, and a string findings array;
`APPROVED` requires no findings and `CHANGES_REQUESTED` requires at least one.
The final TaskTurn payload is the verdict. The prompt must not direct the
provider to a legacy verdict tool that is absent from the typed Task Brain MCP
surface, and the Task owner continues to reject prose or mixed-content output.

If the exact current provider execution succeeds but its final payload fails
that strict JSON, schema, enum, or verdict/findings cardinality contract, the
Task consumes the delivery as a typed protocol failure rather than leaving the
ticket indefinitely `RESULT_PENDING`. The raw AgentExecution remains
`SUCCEEDED`, while the exact TaskTurn and BrainReviewEpisode become `FAILED`;
the Task clears only their pending Brain fence and opens or reuses one
Task-owned `OPERATION_FAILED` blocker naming the failed Turn and exact triggering
Local Stage generation, code fingerprint, head, and base. No Brain verdict was
produced, so this failure does not consume or advance the semantic Brain-review
budget.

The dedicated Retry command revalidates that Task epoch, Local Stage,
generation, Brain-review checkpoint, code subject, failed Turn, accepted
delivery receipt, and open blocker under the Task serialization boundary. It
then admits one fresh BrainReviewEpisode, TaskTurn, Operation, and
DispatchTicket from the complete frozen launch context plus durable execution
trace. Retry removes failed-session resume state and cumulative baselines,
rebinds the new typed TaskTurn MCP endpoint, preserves the semantic review
budget lineage while assigning the successor a new storage/execution ordinal,
and resolves only that blocker after the new pending Brain fence is durable.
The lineage records the same logical budget attempt with `consumes_budget=0`.
Its stable command receipt makes replay idempotent; late predecessor delivery
remains fenced. For one malformed-result lineage, this is the single ordinary
Retry that precedes the C62 bridge; replay returns that same retry and cannot
create another ordinary predecessor.

If the provider succeeds but that ordinary retry is also malformed, the Task
does not loop through another ordinary Retry. It keeps the retry's
BrainReviewEpisode at a result-repair cursor, records the exact second
protocol-failure receipt, and atomically admits one application-tool-free
TaskTurn whose purpose is `DEVELOPMENT_BRAIN_RESULT_REPAIR`. The frozen repair input contains
malformed result and digest, both malformed predecessor identities, the exact
required schema, and the complete current owner/code fence. It contains no
mutable conversation or repository source payload.

The repair Turn starts a fresh provider session, exposes no ByteQuay/MCP
application tools or permission callback, cannot wait for permission or user
input, and consumes no semantic Brain budget. Its frozen working directory is
read-only transport context and the prompt forbids provider-native reads. Its
candidate must pass the same strict JSON/schema/enum/cardinality
decoder; no decoder normalization or prose fallback is introduced. Exact valid
delivery supplies the waiting episode's verdict and resumes the ordinary
approved-or-changes-requested transition. Provider failure, stale ownership,
or malformed repair output records one terminal repair receipt and exposes an
exact manual blocker. The owner never admits a second repair Turn or repairs
the repair output automatically.

An accepted `FAILED` result for the exact current Local Development StageTurn
is an owner transition, not malformed-result replacement. The Local Stage
keeps its generation and checkpoint, clears only that operation's pending
fence, and creates or reuses exactly one open Stage-owned `OPERATION_FAILED`
blocker whose subject revision is the failed StageTurn. The compatibility
projection offers Retry only while that exact Stage, generation, Task epoch,
failed Turn, and blocker are still current.

Explicit Retry revalidates those identities inside the serialized Task
command, then admits exactly one fresh StageTurn, Operation, and DispatchTicket
from the failed Turn's complete frozen original launch context plus its durable
execution trace. It carries no resume token from the failed provider session.
Only after the successor and its pending Stage fence are durable may the owner
resolve that exact blocker. The stable retry command replays its original
receipt, and duplicate or late predecessor delivery cannot clear or overwrite
the successor. Automatic provider-quota waiting and cross-provider fallback
remain undecided and out of scope; neither may be inferred from this explicit
recovery contract.

Brain reviews and comments. Local Development is the branch writer.

DevReport is typed and immutable for its Stage generation and fingerprint. It
includes implemented intent, commits, files, validation evidence, known risks,
unresolved concerns, and context/retrieval references. Development transcript
remains immutable; summary and deep retrieval provide later context. Reusing a
provider session is an optimization and never correctness state.

### CLI provider-session continuity

The CLI adapter does not retain a JVM object, worker thread, or long-lived
subprocess between Turns. `agent_execution.provider_session_id` is the durable
continuation token. The owning runtime resolves an eligible predecessor while
freezing the new Turn and persists one immutable resume bundle:

- `resumeSessionId` — the exact CLI session to continue
- `fallbackPrompt` — the complete durable reconstruction used only when that
  session is explicitly unavailable before provider work begins
- `priorCumulativeInputTokens` / `priorCumulativeOutputTokens` — the exact
  already-accounted Codex session totals paired with that resume token

The ordinary `prompt` is incremental when `resumeSessionId` is present. A
fresh launch, an API launch, a retry after failure/cancellation, and an explicit
    replacement carry the complete prompt directly and no resume token. Trunk and
    Task Brain reconstruct from their typed message tables. Stage continuations
    reconstruct from the frozen launch plus durable execution trace until the same
    information is normalized into Stage messages/checkpoints. Review
    continuations use the frozen seat prompt and exact purpose/subject lineage.
    Exact user-wait and explicitly linked automatic continuations still perform
    that reconstruction when the provider token is absent, incompatible, or was
    consumed by a later Turn; they launch fresh instead of reducing the prompt to
    the incremental answer or next instruction. Frozen image identities are part
    of the reconstructed launch, are digest-verified again, and are not inferred
    from mutable conversation text.

The provider process is always launched with the current Turn's typed MCP
endpoint and access profile, even when the CLI session is resumed. The adapter
may fall back only after recognizing a provider-specific missing/expired
session response and proving that no session, assistant output, tool activity,
usage, unknown output frame, or other provider-work evidence was observed.
Codex receives both incremental and reconstruction prompts over stdin so a
durable transcript cannot exceed the operating system's argument-size limit.
Every provider stream frame remains durable UI/log evidence, but it is not the
domain result. The adapter derives `finalText` from the provider's authoritative
terminal response: Claude's terminal `result` field, or the last Codex
`agent_message` including an intentionally empty one. It never concatenates
progress commentary with a strict typed owner result.
Otherwise the result is failed or indeterminate and normal owner recovery
applies; automatic replay is forbidden because it could duplicate work or
effects.

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

If the publish preflight proves that the remote base moved from the frozen
base SHA, it returns typed `BASE_MOVED` and performs no push or GitHub write.
Accepted delivery moves Local Development from `PUBLISHING` back to
`LOCAL_REVIEW` and, in the same Task command, either opens one exact
`LocalPublishBaseSyncEpisode` under the frozen `autoApprove` policy revision or
opens one `LOCAL_PUBLISH_BASE_SYNC_REQUIRED` blocker. Disabling the separately
scheduled branch-sync guard does not revoke this exact pre-publish repair
authority; its current immutable revision supplies only the bounded attempt
limit. A later manual approval is authorized only by the exact accepted
publish-failure receipt and open blocker, never by latest-state inference.

The Episode runs two durable `LOCAL_GIT` Operations through the existing
dispatcher and Task writer lease: fetch/compare, then a real mechanical rebase.
Neither Operation pushes. A clean rebase and a proven real rebase conflict both
open one semantic `BASE_SYNC` StageTurn: the clean path inspects the rewritten
subject, while the conflict path performs and resolves the rebase from the
restored source subject. Both paths must produce a fresh DevReport, canonical
validation, and Task Brain review before Local Review can create a fresh
PublishAuthorization. No validation, Brain verdict, publish authorization, or
failed publish evidence from the old base is reused. The Episode, Operations,
dedicated Stage-start receipt, target base, and all result fences are durable,
idempotent, and counted by cancellation, Cleanup, and Trunk-purge quiescence.

Mechanical-rebase reconciliation holds the same exact Task writer fence as the
original effect. It may accept a completed target only after independently
proving the exact rewritten patch series. If Git instead retains an in-progress
rebase whose `orig-head`, `head-name`, and `onto` exactly match the immutable
Operation, reconciliation aborts it back to the exact clean source and reruns
that same Operation. Foreign, incomplete, or malformed rebase state is never
aborted or adopted; it remains indeterminate for explicit recovery.

Local publish-base synchronization follows the Task lifecycle; it is not an
independent background owner. Pause stops admission of new Git work. A claimed
fetch may only prove an already-present target, and a claimed rebase may only
prove an already-completed exact patch series or abort its own exact
in-progress rebase back to the frozen source. The Episode then records one
immutable `PAUSED` cursor (`FETCH`, `REBASE`, or `HANDOFF`). Resume promotes
that cursor only after the Task and same Local Stage are current and `ACTIVE`;
it never infers progress from the worktree. Cancel advances the Task epoch,
supersedes late results, records exact cancellation evidence, and lets Cleanup
remove the worktree after the writer has stopped. A late result cannot reopen
or advance the Stage.

Only determinate operation failure spends the Episode attempt budget. Standing
`autoApprove` authority may retry automatically while the frozen limit remains.
Manual authority grants exactly one attempt per approval. Pause, cancellation,
conflict, and indeterminate execution spend no attempt. Reaching the limit
terminalizes the Episode as `EXHAUSTED` and opens one exact
`LOCAL_PUBLISH_BASE_SYNC_EXHAUSTED` blocker. One explicit user command may
extend that exact Episode by exactly one attempt; the immutable extension,
blocker, predecessor Episode, retry Episode, and new limit are one audited
chain. There is no generic Resume or scheduler path that can add budget.

Only commits and the approved title/body cross the boundary. Local review
threads and timeline remain stored locally.

Promotion attaches remote identity to the same stable PR aggregate. Dashboard
triage, local timeline, Brain findings, validation evidence, and narration
remain available after promotion and after Cleanup.

Successful publish delivery records remote identity only through the stable PR,
RemotePrBinding, and TaskManager's serialized Local-to-Remote handoff. It must
not write legacy Task projection columns through TaskStore. The V2 Task
aggregate version advances only with the TaskManager transition that completes
Local Development and opens Remote Development.

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

The provider adapter fences each observation with a second PR-detail read. The
head SHA, base SHA, merge-commit SHA, and lifecycle tuple (`state`, `merged`,
`draft`) must still match. Any mismatch rejects the whole observation for
durable retry; the earlier read's lifecycle state is never persisted as newer
truth.

Remote observation is read-only and its reconcile path is safe to repeat. If
bounded infrastructure reconciliation parks an observation, the Remote
Observation maintainer may re-arm only the same exact ticket after the normal
polling interval and only while its Task epoch, current Remote Stage/generation,
head/base fence, and uncanceled owner remain current. It does not create a new
Operation or semantic attempt. The generic dispatcher retains its bounded
manual-park rule for effectful Operations.

- An observation for an old head is historical and cannot advance current
  state.
- A successful result for an old Task epoch is not an old-head snapshot. Its
  exact raw result and digest are retained in a `SUPERSEDED` delivery receipt,
  the observation and ticket terminalize, and no Remote snapshot or CI
  evaluation is inserted. This prevents an epoch change such as cancellation
  from stranding the ticket at `RESULT_PENDING` while preserving the epoch
  fence.
- Remote comments observed while initial CI is unresolved are persisted but
  not dispatched until the relevant head is green.
- PENDING waits and consumes no slot.
- NONE passes only when the Task's explicit CI policy permits no checks.
- Missing, queued, canceled, skipped, and neutral checks follow explicit
  repository policy; they are not silently normalized to green.
- Failure classification consumes only a C56 typed provenance proof attached to
  the accepted snapshot/evaluation. Provider-native typed conclusions may prove
  flaky or infrastructure failure; deterministic Task/base classification also
  requires the complete exact tested-subject and stable-fingerprint comparison.
  Complete exact evidence whose ownership is mixed or cannot be made unanimous
  yields `TASK_BRANCH_REPAIRABLE`, whose only mutation authority is an
  append-only repair of the current Task branch. Raw observation/check evidence
  remains audit data and is never parsed for authority.
- A recognized dependency-only Actions aggregate is not a second independent
  failure. Its exact schema-v4 proof may inherit only one unanimous strict
  classification from all of its failure-requiring dependencies; it cannot
  weaken, replace, or mix their concrete proof.
- PASSED may authorize mark-ready or enter AWAITING_READY.
- FAILED opens or continues one CiRepairEpisode for the exact head.

CiRepairEpisode:

- may authorize one CI rerun before code changes
- separates rerun count, semantic fix attempts, delivery retries, and pushes
- admits StageTurn -> ValidationOperation -> PushHead effect; CI repair does
  not create or wait for a Task Brain review
- accepts the new pushed head and returns to WAITING_CI
- closes only on green CI, explicit stop, or a C57 supersession after exact
  predecessor quiescence
- on budget exhaustion opens an Episode/Remote Stage blocker with Extend,
  Continue with per-push approval, Manual takeover, and Stop automation choices

The last permitted push receives its CI result before exhaustion is declared.

CI failure classification is part of the Episode:

- flaky and infrastructure failures are rerun-only; they do not authorize code
  edits
- deterministic failures introduced by the Task may authorize a normal fix
- exact mixed-origin or non-unanimous failures are
  `TASK_BRANCH_REPAIRABLE`; they authorize the same append-only current-Task
  Stage repair, never a base-history rewrite
- deterministic failures already present on the exact base are recorded as
  base evidence and may authorize an explicitly scoped base repair below the
  Task commits
- `UNKNOWN` opens one exact classification blocker and authorizes no repair.
  Its available controls are typed rerun, manual takeover, and stop automation;
  `CLASSIFY_TASK`, `CLASSIFY_BASE`, or any other payload label cannot manufacture
  provenance.

Classification is immutable on an Episode. If a later accepted observation
contains a strictly valid C56 proof for the same Stage generation, head, and
base, Remote Development supersedes the `UNKNOWN` Episode rather than editing
it. It first cancels or reconciles any live predecessor Operation/Turn, then
atomically records the terminal supersession, copies all counters and remaining
budgets into one proven successor, and resolves the old blocker only after the
successor is durable. The proof, predecessor, and successor identities make
replay idempotent. A weaker, stale, or different-subject observation leaves the
existing Episode and blocker unchanged.

Every code-repair attempt freezes its own C57 authorization before work begins.
A Task-owned deterministic or `TASK_BRANCH_REPAIRABLE` repair uses the ordinary
append-only repair StageTurn, canonical validation, and a normal push to the
exact named Task head remote. Its agent prompt requires every observed failure
to be treated as part of the repair and forbids base-history rewriting. A
base-owned repair never writes the base branch. Its repair StageTurn
may create only the scoped repair commit at the current Task tip; a
deterministic HistoryRewriter effect then places that repair below the frozen
original Task-commit manifest. The rewritten subject must pass canonical
validation before one force-with-lease push to the exact named Task head remote
and expected old head. The CI-repair Stage owns this repair-to-publication
sequence; neither path creates a TaskTurn or pending Task Brain result.
There is no inferred-remote, ordinary-push, or force-push fallback. The attempt
authorization is consumed only after that push is accepted; every retry derives
a new authorization from the latest applicable automation-policy revision or
the still-current exact blocker and explicit user action.

Every CI-repair StageTurn also requires one immutable accepted-Remote freshness
authorization. The authorization freezes the accepted snapshot and monotonic
observation revision, the authoritative observed target-base SHA, and the exact
current Task code subject. The Task code base must equal that observed target
base, and no BranchSync writer may remain live. The first repair for an observed
failed CI evaluation may use the Candidate being folded. Every later writer —
including validation failure, the bounded no-change continuation, explicit
base repair, and user steering — first persists intent
and cannot dispatch until a distinct accepted snapshot with a strictly greater
observation revision is folded. Wall-clock order alone is not authority.

The database rejects an ordinary CI-repair StageTurn, its no-change
continuation, or a CI steering replacement without the matching immutable
freshness authorization. Accepted Remote truth is folded in this order:
BranchSync ownership first, CI repair second, then merge/readiness. A live
BranchSync or a base mismatch defers CI; it never races another worktree writer
and never permits a repair on a stale base. Manual commands and
budget changes persist intent and request observation rather than launching a
writer synchronously.

When the pending repair has already produced a local head that is ahead of the
Remote PR but still uses an older base, Remote Development admits the narrow
`CI_PRECONDITION_LOCAL` BranchSync purpose. It freezes the exact pending repair
intent, accepted Remote snapshot, and current local code subject; cancels and
settles any older CI writer; then performs only fetch, mechanical rebase, and,
if required, conflict repair. Canonical validation and push are skipped for
this precondition. The successful rebase must be the exact
worktree subject produced by that BranchSync operation, while the recorded
Remote result snapshot remains the unchanged source snapshot. A fresh Remote
observation is then required before the still-pending CI Turn can run its
normal validation and single-push publication gates. Full BranchSync
must never publish the rejected or not-yet-validated partial repair.
If that local precondition rewrote history, the final CI push freezes the
successful BranchSync Episode on its immutable Operation and uses one exact
named-head force-with-lease against the precondition's observed old Remote
head. Every intervening validation, steering, base-repair, and CI-fix code
subject carries the same immutable predecessor lineage, so a later repair
attempt cannot silently fall back to ordinary publication. SQL rejects
missing, stale, or substituted lineage. There is no ordinary-push fallback.

`START_BRANCH_SYNC` freezes the observation Operation on its immutable manual
authorization. Replaying the same command before or after that authorization
is consumed returns the same observation identity and creates no new work. A
fresh accepted green evaluation cancels every pending CI Turn and unconsumed
BranchSync authorization; green evidence can never authorize a repair writer.

A new monitor scan cannot reopen an exhausted Episode. Normal Resume cannot
reset its semantic budget; the user must extend or select a fallback.

### Branch sync and guard

Task owns branch/worktree authority. Remote Development owns the scheduled
BranchSyncEpisode and the remote checkpoint affected by a new head.

BranchSyncEpisode records old head, observed base, target base, policy, and
budget. The default attempt limit is eight and the storage ceiling is ten;
existing persisted policy revisions never change when the default changes.
Each determinate failed effect rearms the same ordered step in the same Episode
and appends another immutable dispatch row. It cannot replace the Episode and
reset the budget. Exhaustion terminalizes that Episode, records one exact
Remote-and-local-subject blocker, and suppresses any successor for the
unchanged subject. A genuinely changed Remote or local code subject resolves
the attention item and may be evaluated as new work. `MANUAL_TAKEOVER` and
`STOP_AUTOMATION` are durable idempotent BranchSync-owned control commands.
They resolve the exact blocker while retaining unchanged-subject suppression;
neither changes budget, starts a writer, or routes through CI recovery. It may
perform:

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

### Remote repair output and Brain failure recovery

The active finite Task Brain step exists only in a BranchSyncEpisode. A
historical CiRepairEpisode already parked at its pre-cutover Brain cursor still
follows C58 for readable, finite recovery, but no current CI transition creates
that TaskTurn. Remote repair Stage and BranchSync Brain final output stays
strict: the owner accepts
only the documented JSON object, never surrounding prose, a Markdown fence, or
an inferred verdict. The AgentTurn execution boundary validates that small
owner contract before reporting provider success. If a Remote CI repair Stage
writer returns malformed output, it first freezes the C66 candidate proof while
the original writer token is live, then restores the exact source head and
removes untracked output before releasing that token. Other Stage writers
restore without creating C66 proof. Each returns one typed
`OWNER_OUTPUT_MALFORMED` failure with the immutable raw text retained. A Brain
Turn is read-only and returns the same typed failure without acquiring a writer.

Delivery of malformed output or a proven terminal provider/process failure is
consumed once:
the exact TaskTurn and repair-Brain Operation become terminal, while the parent
Episode stays live at its Brain cursor and exposes one exact recovery blocker.
The dispatcher must not keep the failed raw result at `RESULT_PENDING`, and it
must not launch replacement work on its own.

A failed or malformed CI repair StageTurn likewise records one immutable
delivery receipt and one exact Episode blocker while leaving the same
CiRepairEpisode live. A failed or malformed BranchSync StageTurn terminalizes
its existing Episode and records the normal exact-subject BranchSync exhaustion.
Repeated delivery replays the receipt; repeated observations of the unchanged
subject cannot create another Episode or Turn, reopen a cursor, or consume,
extend, or reset a semantic budget. Recovery surfaces only actions actually
owned by that domain.

The C58 provider/process-failure Retry remains a synchronous owner command. It
revalidates the full Remote subject and failed-result receipt, then durably
creates one fresh TaskTurn, Operation, DispatchTicket, and replacement fence
before waking ExecutionDispatcher. The successor reconstructs the frozen
prompt and typed MCP context, starts with no failed CLI session or cumulative-
usage baseline, and consumes no additional semantic repair attempt. A stale
predecessor result remains immutable audit evidence but cannot advance the
Episode, overwrite the successor, push, or change the budget.

A malformed provider-successful Remote CI repair Stage result does not use that
Retry. It follows C66: one fresh tool-free normalization TaskTurn is judged by
the unchanged strict decoder, and a changed tree must pass the separate durable
Local-Git adoption before the Task code subject can advance. The original
malformed blocker stays open for that whole path, and any preserved or
reactivated base-repair authority remains the same exact authorization under an
immutable continuation receipt. Failure, malformation, stale proof, or
ambiguous adoption remains a manual blocker and cannot recursively launch
another recovery. BranchSync Stage and Remote repair Brain malformation do not
enter this bridge and retain the existing behavior above.

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

The Plan overlay is an editor for this same canonical policy, not another
policy store. Its client mutation queue must settle before Plan approval, and
approval names the resulting policy revision as required by C60.

Policy distinctions:

| Action | autoApprove | autoMerge | Explicit per-event user action |
|---|---:|---:|---:|
| Plan approval | allowed | n/a | alternative |
| Local promotion/push | allowed only as explicit standing Task consent | n/a | alternative |
| Mark ready | allowed | n/a | alternative |
| Task, append-only mixed-origin, or base CI repair and push | allowed only with C56 proof, exact C57 attempt authorization, armed policy, and budget; only unanimous exact base proof may rewrite history | n/a | alternative |
| Branch-sync push | allowed within armed policy and budget | n/a | alternative |
| Post remote review replies/review | never | never | required |
| Request reviewers | never | never | required |
| Merge | no | allowed after fresh proof | alternative |

Policy rules:

- autoApprove and autoMerge are explicit opt-ins
- enabling autoMerge also enables autoApprove
- disabling autoMerge does not disable autoApprove
- autoApprove authorizes a proven exact repair action, never its failure
  classification; without applicable standing consent, each base-owned repair
  attempt requires an exact user authorization against its current blocker
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
- exact merge-queue capability for the current base branch; unknown fails closed
- fresh remote observation revision

Merge-queue capability uses two typed GitHub proofs. A valid GraphQL queue
object or queue entry proves `SUPPORTED`. GraphQL queue and entry both being
null is inconclusive; the adapter must then read GitHub's active rules for the
exact base branch through
`GET /repos/{owner}/{repo}/rules/branches/{branch}`. A complete rules response
containing `merge_queue` proves `SUPPORTED`, and a complete response without
`merge_queue` proves `UNSUPPORTED`. Any error, incomplete or malformed
response, subject mismatch, or unavailable proof remains `UNKNOWN` and cannot
authorize either merge mode.

Manual merge consent creates one-head MergeAuthorization.
AutoMerge is standing policy that may create a fresh authorization only after
all readiness facts are re-proved for the current head.

For a direct merge, the authorization and MergeOperation also freeze the
user-selected `merge`, `squash`, or `rebase` method. Probe/restart recovery
cannot change it. Queue entry has no client-selected direct-merge method; the
repository's merge-queue policy remains authoritative.

MergeOperation:

- re-fetches remote truth before the effect
- completes every mutation-deciding remote read, including active branch
  rules, before recording the external-effect claim
- on a read failure before that claim, re-arms the same exact subject and
  authorization as a read-only preflight without creating a semantic attempt,
  consuming merge or queue-bounce budget, or entering indeterminate-effect
  probing
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

An open worktree quarantine blocks every Cleanup Git mutation except the exact
`REMOVE_WORKTREE` step. That step is an independent disposal bypass, not a
repair: it may remove only the Task-bound worktree under Cleanup's normal
quiescence, capacity, and writer fences, performs no branch switch or restore,
and succeeds only after proving the path absent. Accepted absent-path evidence
resolves the quarantine as disposed. It grants no permission to run branch
deletion or another ordinary writer through the quarantined path; each later
Cleanup step retains its own prerequisites and evidence.

Provider-session quiescence counts only unfinished `AGENT_TURN` executions.
Cleanup's own execution, Remote Observation and other non-agent work, and
historical executions with terminal evidence are not provider sessions.
Failed or indeterminate Cleanup attempts remain immutable attempt evidence;
the exact reconciliation predicates are required only before recording a
successful step result.

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
- agent_execution for provider session, current recovery process, per-Turn
  accounting deltas, raw terminal provider-cumulative usage, status and log,
  with agent_execution_process_attempt for every CLI process launch and an
  explicit recovered-evidence marker for V312 terminal reconciliation
- dispatch_ticket for infrastructure admission and lease
- fenced worktree_lease and capacity_lease
- immutable worktree_quarantine source evidence plus
  worktree_quarantine_repair_operation, result, and accepted-delivery receipt
- outbox for reliable wakeup/result delivery

### Domain protocols

- task_assignment
- task_policy_revision
- task_terminal_intent
- task_blocker
- permission_request
- plan_revision, plan_self_review, and plan_followup
- brain_review_episode, malformed-result receipts, and the exact one-shot
  `DEVELOPMENT_BRAIN_RESULT_REPAIR` lineage linking the original, ordinary
  retry, repair Turn, and frozen raw/digest/required-shape input
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
- remote_pr_binding, remote_pr_snapshot, remote-observation delivery receipt,
  CI check snapshot, immutable ci_failure_provenance, and remote_inbox_item;
  only the C61 exact old-epoch success receipt may omit a snapshot
- ci_repair_episode, ci_repair_supersession, per-attempt
  ci_repair_authorization, deterministic history_rewrite_operation/evidence,
  and exact remote-repair Brain failure receipt/replacement lineage
- branch_sync_episode, including its exact repair-Brain failure receipt and
  replacement lineage
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
- A failed exact source restore quarantines the whole Task worktree before any
  ordinary writer may be admitted. Only the current durable quarantine-repair
  Operation can restore it; its reconciliation re-proves branch, frozen head,
  cleanliness, fingerprint, and absence of Git control state under a fresh
  writer token. Only accepted success clears the barrier.
- GitHub effects reconcile by repository, branch, PR identity, head SHA, payload
  identity, and remote state as appropriate.
- Cancellation records intent before process interruption.
- An Agent `RESULT_PENDING` ticket is not delivery-claimable until its exact
  current `agent_execution` is terminal and its raw result matches the full
  durable pending fence and payload. No replacement claim may overtake any
  unfinished execution for that ticket. Maintenance may finalize
  already-durable evidence but cannot execute the Operation again; V312 repairs
  only
  historical terminal-ticket/active-execution contradictions and marks the
  recovered evidence explicitly.
- A late result is recorded and then accepted or superseded synchronously.
- A successful late Remote observation from an old Task epoch is superseded
  through its raw delivery receipt without creating a snapshot. V310 makes
  that terminal shape and exact upgrade reconciliation explicit; replay
  returns the same receipt.
- A proven terminal remote-repair Brain failure is accepted once as a typed
  failure receipt. Its explicit Retry creates one fresh, fully fenced successor
  and permanently supersedes predecessor delivery without charging semantic
  repair budget or reusing failed provider-session accounting.
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

An `approval_prompt` callback received through an exact V2 Turn endpoint stays
inside that typed owner boundary. It resolves the exact Task's V2 policy
revision and the current typed Turn/Operation/Stage runtime context. Retained
`ThreadService` tool budgets, nullable legacy Task phase, legacy Task
auto-approve flags, and generic `AgentRun` phase are neither fallback evidence
nor a veto for a typed callback; they are never read or mutated on that path.
If the exact V2 owner or policy cannot be resolved, the callback fails closed
instead of consulting legacy state. An applicable typed auto-approval returns
the original tool input unchanged. Otherwise the same callback creates or
reuses one exact durable PermissionRequest; repeated callbacks with its stable
call id and repeated answers are idempotent and can admit at most one exact
successor.

An observed remote merge or close records TaskTerminalIntent and opens Cleanup.
In the same Task command, before terminal handoff, the current Remote Stage
stops its exact live CI-repair and branch-sync Episodes. Terminal truth takes
precedence over push-delivery deferral and observed-head completion; replay
with no live Episode is a no-op and cannot affect another Stage.
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

For a Task-owned PR, the accepted Remote snapshot remains the workflow read
authority. The PR surface may overlay only an already-synchronized terminal
`merged|closed` cache value whose repository and number match the stable
RemotePrBinding. This makes externally closed PRs display honestly after a
Task epoch changes, but it performs no read-through synchronization and cannot
drive TaskTerminalIntent, Cleanup, CI, mergeability, readiness, authorization,
or any other owner transition. Nonterminal cache values never override the
accepted snapshot.

Remote CI projections expose the immutable provenance status (`UNKNOWN` or a
proven classification), the exact evaluation/snapshot/head/base subject, any
Episode supersession link, consumed counters and remaining budgets, open
blocker, and current attempt-authorization state. They offer base repair only
when C56 proof and either current standing consent or an exact user gate exist.
Projected choice labels are presentation data; they are never executable
authority and cannot classify a failure.

When an exact current branch-sync Brain Turn has a C58 terminal failure receipt,
the Remote Stage projection exposes its failed Turn/Operation and blocker plus
one idempotent Retry capability. A historical CI-repair Brain receipt remains
visible but exposes no Brain Retry. The BranchSync API requires the stable
retry command id and complete expected subject fence; the UI cannot offer
ordinary repair steering or a second Retry while the replacement is armed.
After replacement, status derives from the fresh Turn and late predecessor
delivery remains visible only as superseded audit history.

An open worktree quarantine is projected as the Task's highest-precedence
recovery blocker even when its source Stage is no longer current. The Task
recovery API accepts one stable command id plus the displayed quarantine,
Task-epoch, current-Stage, worktree, branch, head, and fingerprint fence. It
returns the same durable Operation on exact command replay. The Stage recovery
card exposes `REPAIR_WORKTREE` only for that exact capability, shows requested,
running, failed, canceled, or superseded evidence from the durable Operation,
and disables every ordinary writer while repair is nonterminal. A terminal
non-success exposes Retry, which uses a new command and Operation; the original
history remains visible. Accepted repair delivery removes the action. Once
terminal Cleanup owns disposal, the UI exposes Cleanup progress rather than a
repair that could revive the worktree. No projection or controller probes or
mutates Git.

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

The PR right-panel timeline is a replay-safe read projection over durable
owner facts plus retained private timeline rows; reading it never writes a
compatibility event. V2 milestones come from DevReport commits, Brain review
Episode request and terminal outcome, RemotePrBinding first push, successful
mark-ready, CI repair Episode start/terminal outcomes and changed
`CI_STAGE_TURN` code subjects,
terminal merge truth, and Cleanup start/completion. A failed or parked Brain
Episode terminates its own visible attempt before any later retry, and only an
accepted structured Brain result may expose a finding/comment body; raw agent
transcripts are not timeline content. BranchSync subjects are not CI repair
commits, while a changed CI continuation or adopted repair subject is. A
terminal success names the last pushed repair head (falling back to the
original subject only when no push exists); exhausted and stopped outcomes
remain visible blockers. Stable
PR status remains aggregate state: publish owns `remote-drafted`, accepted
mark-ready or observed-open truth owns `remote-open`, and the terminal remote
observation/merge owner owns `merged|closed`. Projectors may display those
facts but may not perform any transition.

## Existing-feature compatibility matrix

| Current capability | V2 owner/record | Contract |
|---|---|---|
| Workspace/Trunk/Task hierarchy and grouped four-Stage rail | aggregate ownership + Stage projection | Preserved and fully defined in this tracked contract |
| Zero-Task Trunk and planning conversation | TrunkManager, ThreadTurn | Preserved |
| Trunk images, exact interrupt, trace, status, activity and lifetime usage | typed attachment/Turn/trace/runtime projections | Preserved with exact ownership and no child-state leakage |
| CLI session continuity and token accounting | typed owner launch + agent_execution/process-attempt evidence | Exact-owner resume only; Codex cumulative totals remain raw lineage evidence and are charged once as per-Turn deltas; every spawned PID is durable before prompt delivery |
| Create Task while sibling runs | TrunkManager + TaskManager | Preserved with capacity admission |
| Workspace and Trunk parallel-Task limits | CapacityManager + persisted settings | Preserved with atomic policy resolution at admission |
| User/agent/issue/quality/automation Task origins | typed TaskAssignment | Preserved without nullable inference |
| Fresh-base, planning-snapshot and fork provenance | TaskAssignment + ProvisionTaskOperation | Preserved |
| Task branch/worktree/session/PR identity | TaskManager + fenced WorktreeLease | Preserved and strengthened |
| Quarantined Task worktree recovery | Task recovery command + durable `REPAIR_QUARANTINED_WORKTREE` Local-Git Operation | Exact fenced restore only; every ordinary writer stays blocked until accepted branch/head/clean-fingerprint/no-control-state proof, while Cleanup retains only its absent-path disposal bypass |
| Mandatory Plan, self-review, follow-ups, auto/manual approval | Plan Stage + Task Brain + canonical TaskAutomationPolicy | Preserved; Claude freezes the exact Plan result permission bridge even with no generic tools, the overlay serializes canonical policy writes before revision-fenced approval, and CHANGES_REQUESTED waits for human revision or exact all-concern adjudication while POLICY/AUTOMATION still require APPROVED |
| Project Intelligence in Plan and full review | immutable Plan-revision and ReviewRound intelligence projections | Post-cutover extension; exact Workspace context may select bounded angles and ordering but never lifecycle, source capability, evidence, verification, publication, or verdict |
| Replan with history and runtime teardown | Task command + new Plan generation | Preserved and fenced |
| Stage stream, steer and interrupt | exact StageTurn/Operation | Preserved |
| Development/validation/Brain loop | Local Stage + BrainReviewEpisode + bounded DEVELOPMENT_BRAIN_RESULT_REPAIR TaskTurn | Preserved without coordinator ping-pong; after the original and one ordinary retry are malformed, one application-tool-free, no-resume, no-budget repair Turn may reconstruct the strict result, and its own malformed/failure outcome blocks manually without looping |
| User Run tests | exact Task-owned Validation Operation | Durable and idempotent; a waiting HTTP response does not own execution |
| DevReport and deep context handoff | immutable DevReport | Preserved |
| Private local PR and review timeline | stable PR + LocalReviewThread/Batch | Created as `local-drafted`; exact Brain-to-Local Review acceptance atomically opens it once; preserved across promotion/Cleanup |
| Manual publish override | audited PublishOverride | Preserved; automation forbidden |
| Quick/full/scheduled/delta agent review, Continue, Re-review, and answer | PR-owned ReviewSession + per-command TaskReviewSnapshot or ReviewSessionSnapshot Operation | Preserved; every seat-admitting command is DB-only, source capture is durable and exact, and standalone quick/full admission uses its locked unscoped/Workspace capacity shape |
| Concurrent review cost limit | ReviewRound receipts + ReviewAssignmentTurn reservations | Frozen before launch and enforced across seats/follow-ups |
| Spawn build from review | writable REVIEW_FINDINGS Task assignment or foreign-PR zero-Task comment proposal | Preserved for AGREED and human-included ARBITRATED findings, with authorship-specific ownership and no unauthorized worktree |
| First push and Draft PR create/adopt | PublishOperation/effect steps | Preserved and crash-safe |
| Direct/fork routing | RemotePrBinding | Preserved |
| CI pending/green/red/no-check policy and failure provenance | frozen repository CI policy + exact-head RemotePrSnapshot + immutable typed CI proof | Preserved with `DEFAULT_REPOSITORY_CI_POLICY_V1`: NONE/MISSING/QUEUED/PENDING wait, PASSED/SKIPPED are accepted, and FAILED/NEUTRAL/CANCELED fail. The Remote owner forward-appends this default only when an active binding still ends at the obsolete built-in `PUBLISH_HANDOFF_FAIL_CLOSED` revision; historical revisions remain immutable, explicit repository policies are untouched, and work frozen to the replaced revision supersedes. Schema-v5 exact-job Maven log proof may classify only after complete identity/capture/parsing; incomplete or mismatched evidence remains `UNKNOWN`. The diagnostic CI Autofix Harness may reuse capture/parsing utilities but owns no Remote Stage transition or proof. An old-Task-epoch success supersedes with raw receipt and no snapshot |
| CI rerun/fix/base repair/budget/fallback | CiRepairEpisode + supersession + per-attempt authorization + bounded malformed-Stage normalization/adoption | Preserved with separate counters; a proven successor cannot reset budget, and autoApprove authorizes only the exact action. A changed CI repair goes directly from its StageTurn through canonical validation and Stage-owned push to fresh observation; it creates no Task Brain review or verdict. One provider-successful malformed Remote CI repair Stage result may use one no-resume/tool-free syntax-normalization TaskTurn and, for an independently proven changed tree, one CapacityManager/writer-fenced Local-Git adoption; it creates no new authorization or budget, never rewrites the source failure, and otherwise remains manually blocked |
| Scheduled branch guard | Remote Stage BranchSyncEpisode + Task write lease | Preserved |
| Remote comment/review-body handling | RemoteInboxItem/RemoteFeedbackBatch | Preserved; body-only verdict fixed |
| Reply/resolve/push round gate | immutable authorization + effect cursor | Preserved and recoverable |
| Direct user remote-PR controls | Task-owned user-remote-action or zero-Task external-pr-action authorization + DispatchTicket | Preserved across restart with stable command replay, honest terminal projection, and exact-subject fencing |
| Push-driven CI trigger | typed user action + writer-required Local Git/GitHub DispatchTicket | One restart-safe empty commit and exact push; distinct from failed-check rerun |
| Auto-ready/keep-draft | TaskAutomationPolicy | Preserved |
| autoApprove/autoMerge/min approvals | policy revision + exact evidence/authorization | Preserved and exact-head; standing consent may authorize proven Task-branch base repair but never classification or a direct base-branch write |
| Standing-consent local auto-publish | dispatcher MaintenanceWork + owner command | Deterministic redrive; no independent scheduler or direct effect |
| Merge queue bounce/retry | MergeOperation | Preserved |
| Pause/resume/retry/archive | Task lifecycle + exact blocker/operation | Preserved without generic resume |
| Durable permissions and approval budgets | exact V2 Turn/Task policy + PermissionRequest/policy grant | Preserved and restart-safe; a typed callback never consults retained legacy budget, phase, auto-approve, or AgentRun state |
| Parallel Tasks and scope limits | CapacityManager | Preserved and enforced |
| Task worker and executor ownership | CapacityManager + ExecutionDispatcher | Temporary admitted workers; durable waits use no thread; an Agent retry cannot overtake unfinished evidence from an earlier infrastructure attempt, and Agent delivery requires positive exact-current terminal evidence |
| Task trace/timeline/status/notifications | read-only projections | Preserved; an exact-binding already-synced terminal PR cache may correct display only and never becomes lifecycle or remote-work authority |
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
33. Classify flaky/infrastructure failure as rerun-only and deterministic
    Task/base failure only from complete C56 typed provenance; raw markers and
    incomplete evidence remain `UNKNOWN` and authorize no mutation.
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
    continuation reads source evidence only from the immutable ReviewRound
    snapshot and, after Project Intelligence cutover, guidance only from the
    exact immutable round-intelligence row; it never reads Git, GitHub, the
    filesystem, or live knowledge. Complete frozen changed-file bodies remain
    readable after the checkout changes or is deleted, uncaptured paths fail
    closed, deterministic coverage searches only frozen bodies, and typed CLI
    work starts outside the checkout. Block a result whose route, prompt
    metadata, base/head, intelligence digest, or snapshot identity does not
    match the exact round.
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
94. After Project Intelligence cutover, watch the same repository in two
    Workspaces and start a Plan draft, a full review, and a quick review.
    Verify the Plan draft launch and resulting revision use one exact immutable
    Workspace projection, the full ReviewRound freezes only its authorized
    Workspace projection beside the C48 source snapshot, and quick review
    freezes the canonical empty projection. Activate, retire, challenge, and
    replace knowledge after admission; every existing Turn and continuation
    reuses its frozen projection while a new Plan revision or ReviewRound sees
    the new basis. Prove no live or repository-only retrieval occurs after
    admission and intelligence cannot change Stage state, review class,
    capacity, source tools, evidence, severity, verification, publishability,
    or the user verdict.
95. Complete one Plan self-review with CHANGES_REQUESTED and two persisted
    concerns. Verify no redraft Turn is admitted and the existing Plan Stage
    waits at AWAITING_APPROVAL. Request revision and verify a new Plan revision
    with one new self-review is created. In a second case, submit attributed
    DISMISSED_INCORRECT reasons for every concern and verify the resolutions and
    HUMAN approval commit atomically on the same revision without changing its
    sole self-review. Replay the same stable command and verify the original
    approval/evidence returns; change its payload, Task/Stage optimistic version,
    or another displayed fence and verify rejection. Omit one concern, use the
    generic endpoint to resolve/defer one first, leave typed STEWARDSHIP open,
    try to store a frozen Direction reference as PLAN_REASONING, or use BLOCKED,
    failed, or no-verdict evidence; verify the entire adjudication rejects with
    no partial resolution or approval. Change the applicable Direction digest
    and attempt a scoped exception against the old revision; verify rejection
    and a required new revision. Choose Intelligence is wrong, correct it through
    Memory, and verify a new revision may preserve identical Plan prose only when
    its projection digest changed. Verify Local and Remote Development
    receive the exact bounded approval evidence and may still raise a new
    implementation-backed finding. Prove adjudication never mutates knowledge,
    grants a Direction exception, enables POLICY/AUTOMATION approval, adds a
    Stage, or overrides a downstream implementation BrainReviewEpisode or Agent
    Review.
96. Within one open Plan Stage, create revisions A/X, B/X, and then A/X again,
    where the letter is the prose digest and X is the intelligence digest.
    Verify all three receive monotonic revision identities and one self-review
    each. Replay each stable producing command and verify no duplicate revision;
    submit a fresh candidate identical to the immediately current prose and
    basis and verify NO_SEMANTIC_CHANGE. A historical content match must never
    return or reuse the older revision's self-review or approval evidence.
97. Complete successful CLI Turns for a Trunk conversation, Task Brain, one
    Stage generation, and purpose-linked ReviewAssignment seats, then admit
    their exact continuations. Verify each new Turn starts a fresh process,
    resumes only its eligible predecessor session, rebinds its current typed
    MCP endpoint, and freezes a complete durable fallback. Run sibling Tasks,
    another Stage generation, and another review seat concurrently; verify no
    session crosses those boundaries and no two live Turns consume one token.
    Queue multiple Stage APPEND commands and verify each resolves the causally
    latest earlier admitted Turn; make that latest Turn failed or incompatible
    and verify the owner starts fresh instead of skipping back to an older
    session. Tie timestamps and reverse UUID order; verify Trunk aggregate
    version, Task row order, Review logical attempt, and Stage steering row
    order still select the predecessor. Attempt an overlapping Task Brain send
    and verify it returns conflict without saving attachments or admitting a
    Turn.

    Return a provider-specific missing/expired-session response before any
    provider output and verify one fresh fallback attempt reconstructs
    equivalent context, with a monotonic execution log and the Codex prompt on
    stdin. Repeat with a timeout, partial assistant/tool output, usage,
    transport loss, an unknown JSON event, and an accepted session followed by
    failure; verify none is replayed. Verify semantic retry, explicit user
    CANCEL_AND_REPLACE, and every API Turn start from durable context without a
    CLI resume token, while an exact settled USER_WAIT source from a supported
    interactive profile may resume.

    Resume Codex from a frozen 100/40 cumulative baseline and return terminal
    totals 125/47. Verify the Turn and lifetime projections record 25/7, the raw
    result retains 125/47, and the next exact continuation freezes 125/47.
    Remove an ordinary predecessor's cumulative totals and verify fresh
    reconstruction. Return totals below the frozen baseline and verify terminal
    failure, zero delta, no replay, and no later resume from that result. Verify
    an unavailable-session fallback uses a zero baseline. Before each prompt,
    verify an immutable process-attempt row and matching current recovery PID
    commit together. Force registration to fail and verify the child stops
    without prompt delivery. On bounded fallback, verify attempts N and N+1
    remain durable, current PID points to N+1 before its prompt, and log sequence
    remains monotonic.

    Emit Claude assistant progress around tool calls and then a terminal strict
    JSON `result`; verify all frames remain in the execution log while only the
    terminal result becomes owner `finalText`. Emit multiple Codex
    `agent_message` items ending with an empty final item; verify the last item
    is authoritative and earlier progress cannot become the domain result.

    Attempt `ask_user_question` and
    `approval_prompt` from Task completion summary, one seeded historical
    remote-CI Brain review, branch-sync Brain review, and ReviewAssignmentTurn
    MCP. Verify the tools are
    absent from discovery, direct calls fail closed, no wait row is created,
    and each owner receives a terminal result.
98. Run a Claude CLI Plan TaskTurn through `approval_prompt` for `PLAN_DRAFT`
    and `PLAN_SELF_REVIEW`. Verify the exact purpose-matching qualified tool is
    allowed with unchanged input, then records its result exactly once without
    a user wait. Try the wrong-purpose tool, another write, a foreign-prefix or
    suffix-lookalike name, and a stale Turn or Operation; verify each fails
    closed with no result and no PermissionRequest. Force the first draft
    result delivery to fail after the provider returns. Verify the Task remains
    ACTIVE and the Plan remains DRAFTING behind one exact OPERATION_FAILED
    blocker. Retry that failed TaskTurn and blocker: one fresh operation with
    no failed-session reuse replaces it, resolves the predecessor blocker only
    after the replacement is durably armed, and can advance to SELF_REVIEW.
    Replay the retry command and verify no duplicate Turn, ticket, blocker
    resolution, Task PAUSING/RESUMING transition, or Stage generation.
99. Record a canonical JSON V2 Plan with `understanding.summary`,
    `intent.summary`, multiple ordered `intent.steps` using both `file` and
    `files`, `intent.validationStrategy`, and
    `intent.expectedFilesChanged`. Complete its exact self-review with APPROVED
    and verify the Task Brain compatibility read projects every actionable
    step and Brain policy control rather than parsing the JSON as Markdown and
    returning zero steps. Repeat with a historical Markdown Plan and verify the
    heading fallback remains nonempty. For both forms, preserve the full Plan
    content and derive `awaiting` only from the exact revision digest,
    self-review, and AWAITING_APPROVAL checkpoint. A stale or absent review
    must remain non-actionable. Enable auto-merge and verify its policy command
    also enables auto-approval; if policy redrive approves the waiting Plan,
    Local Development opens exactly once and no stale manual approval is
    accepted. After the current Stage advances, verify the same historical Plan
    remains visible as `locked` from its approval evidence rather than
    disappearing with `task_current_stage`.
100. Run a Local Development StageTurn whose exact V2 Task policy has
    auto-approve enabled while retained legacy Task auto-approve is disabled,
    its legacy phase is PLANNING, and its `ThreadService` tool budget would
    reject the call. Request an in-worktree Edit and a mutating Bash command
    through `approval_prompt`. Verify the exact typed Stage runtime authorizes
    the Edit, the V2 policy authorizes the Bash command, both return `allow`
    with the original input unchanged, and no legacy budget, phase,
    auto-approve, permission session, or `AgentRun` state is read or mutated.
    Disable typed auto-approve and repeat the mutating Bash command: verify one
    exact durable PermissionRequest is exposed to the user, the current
    provider call receives the typed wait response, and answering it admits at
    most one exact successor. Replay both the callback and answer; verify the
    same wait/resolution is returned with no duplicate PermissionRequest,
    successor StageTurn, Operation, or DispatchTicket. Remove the selected V2
    policy and repeat an otherwise auto-allowable callback; verify it is denied
    before any stage, budget, auto-approval, or user-wait rule runs.

    Invoke `run_checks` from that typed StageTurn. Verify it resolves the exact
    worktree frozen in the active typed Turn context and never consults nullable
    legacy Task worktree metadata. If the typed context has no worktree, verify
    it fails closed instead of falling back to a legacy Task path.

    Then return a terminal Local Development provider result whose payload is
    malformed instead of strict Stage JSON. Verify result acceptance fails
    closed, the exact ticket remains `RESULT_PENDING` with its delivery error,
    and the Stage read exposes an explicit retry with the exact predecessor
    StageTurn id only when delivery recorded a typed owner-result protocol
    failure and the exact final agent execution has positive terminal-success
    evidence. A plain infrastructure/storage delivery error, missing terminal
    execution proof, or still-live execution remains an ordinary delivery
    retry and exposes no malformed-result recovery. Invoke that
    retry as one CANCEL_AND_REPLACE command whose expected predecessor is
    checked under the Task serialization boundary and whose stable command
    identity derives from the Stage and predecessor. Verify the predecessor is
    superseded before its payload can mutate Stage state. When the provider,
    process, delivery claim, capacity lease, and worktree mutation are already
    quiescent, verify recovery does not wait forever for the predecessor's
    `RESULT_PENDING` ticket to become terminal: it atomically admits one fresh
    StageTurn from the predecessor's complete frozen launch context, ordered
    durable provider trace, and protocol-failure evidence. Verify it uses the
    frozen fallback prompt when the predecessor was a resumed CLI Turn, strips
    resume and cumulative-token fields, rebinds the typed MCP endpoint, and
    swaps the exact Stage pending fence. A repeated retry returns the same
    replacement without
    canceling that successor. Redeliver the malformed predecessor before and
    after the replacement completes; verify it remains superseded and cannot
    overwrite the replacement result. While either this replacement or the
    exact accepted-failure recovery is projected, verify ordinary Stage
    steering is disabled and only the dedicated recovery command is usable.
101. Accept a terminal `FAILED` result from the exact current Local Development
    StageTurn. Verify the Stage generation and checkpoint do not change, only
    the matching pending fence clears, and exactly one open Stage-owned
    `OPERATION_FAILED` blocker names the failed StageTurn. Verify the read model
    exposes Retry only for that exact current failure. Invoke Retry twice with
    one stable command: one fresh StageTurn, Operation, and DispatchTicket is
    admitted from the complete frozen original launch context plus durable
    execution trace, carries no failed provider-session resume token, and
    resolves only the matching blocker after the successor fence is armed.
    Redeliver the failed predecessor and retry after restart; verify the same
    receipt returns, no duplicate work or blocker is created, and neither the
    successor fence nor result can be overwritten. Verify no provider-quota
    timer or cross-provider fallback is started by this recovery. Apply the
    forward reconciliation migration to an already-accepted exact failure and
    verify it creates or reuses the same single open blocker, proves and
    preserves the already-cleared matching pending fence, retains every durable
    execution-attempt trace in order, and prevents ordinary Local StageTurn
    admission until Retry arms the exact successor.
102. Return a provider-successful Development Brain TaskTurn whose final text
    is prose, mixed content, an unknown verdict, invalid schema, or a
    verdict/findings cardinality mismatch. Verify the raw AgentExecution stays
    `SUCCEEDED`, while delivery atomically marks the exact TaskTurn and
    BrainReviewEpisode `FAILED`, clears only their current Task Brain fence,
    records one accepted immutable protocol-failure receipt, and opens or
    reuses one Task-owned `OPERATION_FAILED` blocker tied to the failed Turn and
    exact triggering Local Stage/code subject. Verify no verdict is recorded
    and Brain-review budget consumption does not advance.

    Invoke the dedicated Retry twice. Under the Task stripe, verify it rejects
    any changed Task epoch, Stage/generation/checkpoint, fingerprint/head/base,
    failed Turn, receipt, or blocker. For an exact current subject, verify one
    fresh BrainReviewEpisode, TaskTurn, Operation, and DispatchTicket are
    admitted from frozen launch context plus durable trace, with no failed
    provider-session resume or cumulative baseline and with the new TaskTurn
    MCP endpoint rebound. Verify the successor has a new storage/execution
    ordinal but an explicit `consumes_budget=0` lineage to the same logical
    budget attempt. Only the exact blocker is resolved after the successor
    Brain fence is durable, and replay or late predecessor delivery cannot
    create, clear, or overwrite another successor.
103. Deliver an exact current approved Brain result and, separately, exact
    Brain-budget exhaustion for a stable Task-owned `local-drafted` PR. Verify
    Brain acceptance, pending-fence clearing, Local Stage
    `BRAIN_REVIEW -> LOCAL_REVIEW`, PR `local-drafted -> local-open`, and one
    status event commit or roll back together under one Task transaction;
    exact replay creates nothing. Verify changes-requested, malformed, stale,
    superseded, or mismatched-subject results never open the PR. Upgrade a
    pre-V301 exact current V2 Local Review subject left `local-drafted`; verify
    V301 creates its deterministic repair event and changes it to `local-open`
    once, while legacy, terminal, stale, invalid-validation, non-Task, and
    nonmatching subjects remain unchanged. Paused, pausing, resuming, archived,
    and archiving Tasks remain eligible because those passive Local Review
    subjects can resume; canceling, cleaning, and terminal Tasks do not.
104. Complete every external effect of an exact PublishOperation, then crash or
    reject local result acceptance before its delivery receipt commits. Verify
    the `RESULT_PENDING` delivery adopts the already-created remote PR on
    restart without another push or PR, records remote identity through the
    stable PR and RemotePrBinding, and commits the Local-to-Remote TaskManager
    handoff plus one Remote Stage. Verify publish delivery performs no legacy
    TaskStore metadata write or full-row Task save, and the V2 Task aggregate
    version advances exactly once through TaskManager. Exact replay returns the
    same receipt and creates no second binding, Stage, observation request, or
    remote effect.
105. With the production one-connection SQLite pool, load a dispatched Remote
    Observation whose CI policy has required checks. Verify operation context
    and required checks are loaded by sequential completed queries, without a
    nested connection acquisition. Park that read-only ticket after bounded
    infrastructure reconciliation; after the polling interval, verify the
    Remote Observation maintainer re-arms the same ticket only while the exact
    active Task epoch, current Remote Stage/generation, head/base fence, and
    uncanceled owner still match. Reconciliation produces one accepted snapshot
    without a new Operation or semantic attempt. Stale, completed, paused,
    canceled, or cancel-requested subjects remain parked. Close or merge the PR
    between the adapter's initial and stability reads without changing its
    head/base SHAs; the mixed observation must fail for retry and must not
    persist the initial nonterminal lifecycle state.
106. Observe GitHub check runs and verify the provider adapter emits the
    canonical durable `CHECK_RUN` kind accepted by Remote CI storage. Replay an
    immutable result written by the faulty adapter with the known
    `GITHUB_CHECK_RUN` alias; verify strict decoding canonicalizes only that
    known alias, persists the original raw result digest plus canonical check
    rows, and evaluates CI once. Unknown check kinds still fail closed before
    domain acceptance.
107. Deliver two failed observations for the same exact head whose failure
    classification needs attention. Verify one live CiRepairEpisode and one
    open blocker remain; the blocker prevents another repair arm without
    terminalizing its owner. Deliver green CI and verify the same Episode
    succeeds and its blocker resolves. Explicitly stop or take over an Episode
    and verify later failed observations for that exact subject cannot reopen
    automation; an automatically stopped historical Episode without a consumed
    user control does not suppress recovery. Upgrade a pre-V302 database with
    open blockers owned by automatically stopped CI Episodes; verify V302
    preserves immutable Episode history, resolves those orphaned blockers, and
    permits the next exact failed observation to create only one live Episode.
108. Deliver failed CI observations whose provenance is complete and versioned,
    then variants with missing, duplicate, partial, mixed-profile,
    unsupported-schema, mismatched provider/check/profile evidence, the wrong
    actual tested SHA, an unverified synthetic pull-request merge subject, and
    raw classification markers. Verify exact typed proof yields `FLAKY`,
    `INFRASTRUCTURE`, `TASK_DETERMINISTIC`, `TASK_BRANCH_REPAIRABLE`, or
    `BASE_DETERMINISTIC`; only complete exact but mixed/non-unanimous ownership
    yields `TASK_BRANCH_REPAIRABLE`, while every invalid variant remains
    `UNKNOWN` and starts no mutation. Begin with one `UNKNOWN` Episode and
    blocker, then accept stronger
    typed proof for the same Stage generation, head, and base. Verify
    predecessor work is quiesced, the old Episode becomes terminal superseded,
    exactly one successor copies every consumed counter and remaining budget,
    the blocker resolves only after the successor is durable, replay reuses
    that successor, and stale or different-subject proof does nothing.

    Include one static dependency-only Actions aggregate backed by the exact
    workflow blob, run attempt, complete job set, and concrete failed
    dependencies. Verify it inherits their unanimous classification. Then vary
    the workflow SHA/path, run/attempt/head/suite, pagination total, job/check
    identity, literal job mapping, `needs`, fan-in script, dependency outcome,
    rerun stability, and dependency classification. Include one dependency
    whose static name has exactly one literal-prefix/suffix
    `${{ matrix.<identifier> }}` placeholder and verify all uniquely mapped
    runtime instances are proven separately. Verify malformed, missing,
    otherwise dynamic or matrix-shaped, incomplete, leafless, or
    aggregate-only cases remain `UNKNOWN`; an otherwise exact mixed-origin
    graph whose every failed leaf has concrete head/base proof becomes
    `TASK_BRANCH_REPAIRABLE` and never gains base-rewrite authority.

    For `TASK_DETERMINISTIC`, verify a fresh attempt authorization freezes the
    evaluation, snapshot, head/base, original Task-commit manifest, and its
    policy or blocker source, and that its repair StageTurn validates and
    normal-pushes the exact named Task head without creating a Task Brain Turn
    or pending Brain result. For
    `TASK_BRANCH_REPAIRABLE`, verify the same finite budget and fresh fence
    start an append-only current-Task StageTurn whose prompt includes every
    failed check, then canonical validation, normal push, and fresh
    observation; verify no base authorization, HistoryRewriter,
    force-with-lease push, CI Brain Turn, or pending Brain result exists. For
    `BASE_DETERMINISTIC`, exercise both standing `autoApprove` consent and an
    exact blocker decision. Verify the StageTurn creates only a tip repair
    commit, HistoryRewriter deterministically places it below the frozen Task
    manifest, validation passes, and one exact named expected-old-head
    force-with-lease push occurs without a Task Brain Turn and with no inferred remote,
    ordinary-push, force-push fallback, or base-branch write. Verify an
    authorization is consumed only after an accepted push, every failed or
    retried attempt needs a new authorization, and disabling both consent
    sources prevents repair from starting.

    Persist schema-v4 aggregate proof and schema-v5 exact-job log proof through
    the production snapshot writer and verify the dedicated provenance column
    contains the same schema version and completeness bit. For schema-v5,
    bind the exact run, attempt, complete job set, job/check/suite, and tested
    SHA; capture the complete strict-UTF-8 log within eight MiB; retain only its
    byte count, digest, parser identity/version, canonical diagnostics, and
    sorted fingerprints. Verify exact matching Maven compiler head/base proof
    may classify while truncated, expired, denied, oversized, invalid-UTF-8,
    unrecognized, partly parsed, identity-mismatched, mixed-parser, cached-tail,
    excerpt, and raw-substring variants remain `UNKNOWN`. Verify the diagnostic
    CI Autofix Harness shares only the strict GitHub job-log capture primitive;
    its heuristic `HarnessLogParser` is not the versioned
    `MAVEN_COMPILER_V1` proof parser and neither component becomes a Remote
    owner or provenance source. Verify already-durable schema-v3
    concrete and schema-v4 aggregate proof remain readable with their original
    authority. A Java/SQL schema-version mismatch must fail the test before it
    can strand a live observation at delivery.

    Start an active Remote Stage whose latest immutable CI policy is the
    obsolete built-in `PUBLISH_HANDOFF_FAIL_CLOSED` revision. Verify the Remote
    owner appends exactly one `DEFAULT_REPOSITORY_CI_POLICY_V1` revision,
    copies required checks, and leaves both history and explicit repository
    policies unchanged. An in-flight Observation frozen to the replaced
    revision may persist its receipt but must supersede before a Stage
    transition; the next Observation freezes the appended revision.
109. Launch an initial and an infrastructure-retry Plan self-review. Verify the
    MCP tool contract and both frozen prompts state that `APPROVED` requires an
    empty concerns array, non-blocking caveats belong in follow-ups or
    stewardship, and a rejected call that persisted nothing may be corrected
    until exactly one typed submission is accepted. Reject `APPROVED` with a
    concern without persisting a verdict; never infer one from final prose. If
    the provider then succeeds without an accepted submission, retain the
    terminal review failure and open blocker required by the existing policy.
110. Run each direct-final-text V2 CLI owner through its initial, steering, and
    retry launch paths: Local Development and its Brain, local-review feedback,
    remote feedback and its Brain, Remote CI repair Stage, and BranchSync repair
    Stage and its Brain. Verify
    every shared prompt freezes the exact result schema and requires one raw
    JSON object whose first and last non-whitespace characters are `{` and `}`,
    with no Markdown fence or surrounding prose. Return an otherwise valid
    fenced object and verify strict delivery remains pending or blocked under
    the existing exact recovery contract; no decoder strips the fence.
111. Launch Claude CLI once with an exact runtime catalog that exposes
    `approval_prompt` and once with a finite automatic Brain catalog that does
    not. Verify the first invocation names the frozen owner gate through
    `--permission-prompt-tool`, the second omits that CLI option while retaining
    the same exact owner-scoped MCP endpoint and preapproves exactly its active
    bare MCP catalog through fully qualified `--allowedTools`; both can call a
    tool from their advertised catalog. Verify that no user-wait, mutation, or
    native write tool is present. Give the automatic TaskTurn a non-null trigger
    Stage id and verify an actual MCP read sees Task scope with a null Stage id;
    repeat with a StageTurn and verify its Stage scope retains the Stage id.
    Force one provider-session-unavailable resume and verify the single fresh
    fallback preserves the absent callback and the same exact allowlist. The
    durable endpoint identity remains mandatory;
    deriving the optional CLI callback must neither broaden the catalog nor
    create a typed user-wait path for an automatic Turn.
112. Seed one historical pre-direct-push `REMOTE_CI_BRAIN_REVIEW` TaskTurn and,
    separately, create one current `BRANCH_SYNC_BRAIN_REVIEW` TaskTurn. Deliver
    a terminal provider/process failure to each exact current Turn. Verify the
    immutable raw result is consumed once, the exact
    TaskTurn and repair-Brain Operation become `FAILED`, their ticket no longer
    loops at `RESULT_PENDING`, and the owning Episode remains at its Brain
    cursor with one exact recovery blocker. Verify no verdict, push, semantic
    CI-fix/branch-repair budget consumption, or attempt-authorization
    consumption occurs. Reload the Remote Stage and verify the historical CI
    failure is readable but offers no Brain Retry, while BranchSync offers only
    its exact Retry capability. Calling the recovery API with the historical CI
    subject must fail closed and create no Turn, Operation, or ticket.
    Claim one seeded pre-cutover live CI Brain ticket and verify it reaches no
    provider or MCP authorization and settles through owner-not-found handling.

    For BranchSync, invoke Retry twice with one stable command, then restart
    before dispatch and redeliver the failed predecessor before and after
    successor completion. Verify one fresh TaskTurn, Operation, and
    DispatchTicket are reconstructed from frozen context with new identities
    and storage ordinal, no failed CLI resume token or cumulative baseline, and
    one durable replacement fence. The blocker resolves only after that fence
    is armed; command replay returns the same successor, and every late or
    changed predecessor delivery remains superseded and cannot alter the
    cursor, budget, authorization, or result.
    Also verify a newly admitted Task- or base-owned CI repair goes from passed
    canonical validation directly to one push, with no
    `REMOTE_CI_BRAIN_REVIEW` TaskTurn, Operation, ticket, or pending result.
    Change each BranchSync Task epoch, Remote Stage/generation, Episode/cursor,
    head/base, code subject, failed Turn/Operation, receipt, and blocker in turn
    and verify Retry fails closed without creating work.
113. Launch Claude CLI for `PLAN_DRAFT` and `PLAN_SELF_REVIEW` with an empty
    generic runtime tool catalog. Verify each frozen argv still names only its
    exact owner-scoped permission-prompt callback, its purpose-matching
    `record_plan` or `record_plan_self_review` call returns the original input,
    and every changed Task/epoch/Stage/generation/Turn/Operation/purpose,
    endpoint, call id, tool, or mutation is denied. Verify no generic tool,
    PermissionRequest, user wait, filesystem/shell/Git capability, or access
    broadening appears. Repeat a non-Plan finite automatic TaskTurn and verify
    C55 still omits the callback argument.
114. Load the Plan overlay with canonical Task automation-policy revision N.
    Enable auto-merge and immediately request approval while delaying the
    policy response. Verify one exact Task command commits revision N+1 with
    both `autoMerge` and `autoApprove` true, the client sends approval only
    after that response, and approval is fenced by N+1. Reject or fail the
    policy write and verify approval is not sent; race another revision and
    verify stale approval changes nothing. Reload and prove the overlay reads
    the canonical revision rather than legacy Task flags or a Plan-local copy.
115. Apply V310 to an exact provider-successful Remote observation left
    `RESULT_PENDING` after its Task advanced from epoch E to E+1, including a
    payload that reports `CLOSED`. Verify forward reconciliation creates one
    immutable raw-result receipt, marks the observation `SUPERSEDED`, completes
    its ticket with superseded delivery acceptance, and creates no
    RemotePrSnapshot, CI evaluation, accepted pointer, inbox item, terminal
    intent, or Cleanup transition. Replay returns the same receipt. With an
    exact-binding PR cache already synchronized to `closed`, verify the PR UI
    displays closed; with only cached draft/open or a mismatched binding, verify
    it does not override accepted snapshot truth. In every case the cache
    performs no remote read and cannot change lifecycle.
116. Return malformed output from an exact Local Development Brain TaskTurn,
    invoke its one ordinary Retry, and return a second provider-successful
    malformed result. Verify the Task records both failure identities and the
    second raw output/digest, keeps the retry episode at its repair cursor, and
    admits exactly one durable `DEVELOPMENT_BRAIN_RESULT_REPAIR` TaskTurn with
    the frozen required
    shape and full owner/code fence. Across replay and restart, prove it has no
    ByteQuay/MCP application tools, repository source payload, permission
    callback, resume token, mutation or wait path, writer lease, or semantic
    Brain-budget charge. Verify its frozen working directory remains read-only
    and its instruction forbids provider-native reads. Return one strictly valid reconstructed
    object and verify the unchanged decoder continues the waiting verdict flow.
    Separately return malformed output, a provider/process failure, and a stale
    fence from the repair Turn; each terminalizes once with one manual blocker
    and no ordinary retry loop, second repair, repair-of-repair, or provider
    fallback.
117. Crash an Agent Turn after its ticket commits `RESULT_PENDING` but before
    its current `agent_execution` evidence finishes. Verify delivery cannot be
    claimed, Task quiescence still sees the live execution, and dispatcher
    maintenance finalizes only the already-durable evidence without invoking
    the provider or effect handler again. Then verify normal delivery occurs
    once. Apply V312 to a historical terminal Agent ticket whose execution is
    still active; verify only the execution evidence terminalizes with the
    explicit recovered-evidence error marker, no owner result is redelivered,
    and a canceled Task can pass quiescence and enter/finish Cleanup.
118. Crash after an Agent ticket durably enters due `RETRY_WAIT` or due
    `RECONCILE_WAIT` but before its current execution evidence finishes. Verify
    a replacement claim cannot overtake that row, maintenance terminalizes the
    abandoned attempt once without invoking work, and only then may the next
    infrastructure attempt start. Apply V312 to a terminal success whose
    unfinished row belongs to an earlier attempt and verify that row becomes
    `UNKNOWN`, not falsely successful. Apply it to a terminal failed ticket
    whose cleared outcome could be indeterminate and verify `UNKNOWN` plus the
    ambiguity marker. Finally prove a non-Agent result still follows its own
    evidence contract and is not required to fabricate an Agent execution row.
119. Cancel a typed Agent StageTurn before provider launch and persist its
    exact `RESULT_PENDING` `CANCELED` ticket with a null payload, zero
    infrastructure attempts, and no `agent_execution`. Verify the result codec
    does not parse `USER_WAIT` or any other non-success disposition as a
    success payload, and synthesizes `PROVIDER_CANCELED` for a null canceled
    payload only after the immutable owner and complete fence match. Change the
    owner kind/id, Operation, attempt, Task epoch, Stage/generation, code
    fingerprint, head, or base in turn and verify delivery fails closed.
    Complete or replace the Stage owner before that exact no-launch
    cancellation is delivered, then apply V312. Verify the typed StageTurn and
    ticket become `CANCELED`, ticket acceptance is `SUPERSEDED`, explicit
    migration-recovery evidence is retained, pending-result and next-attempt
    fields are cleared, and no execution row, provider call, handler call, or
    replay is invented. A launched, current-owner, ambiguous, or mismatched
    row remains unchanged for explicit reconciliation.
120. Freeze a publish authorization at base A, move the authoritative remote
    base to B, and execute publish. Verify the Operation returns typed
    `BASE_MOVED` with B, performs no push or GitHub write, revokes the old
    authorization, records one accepted failed-publish receipt, and moves the
    exact current Local Stage from `PUBLISHING` to `LOCAL_REVIEW`. With frozen
    `autoApprove=true`, verify the same Task transaction opens exactly one
    bounded local publish-base-sync Episode and fetch ticket. Replay creates no
    duplicate Episode, Operation, ticket, blocker, or Stage transition.
121. Repeat scenario 120 with frozen `autoApprove=false`. Verify delivery opens
    one exact `LOCAL_PUBLISH_BASE_SYNC_REQUIRED` blocker and no Git work. After
    the publish ticket terminalizes, approve that blocker and verify the
    accepted failed-publish receipt, blocker payload, source base, target base,
    Task/Stage/code fence, and actor admit the Episode once. Change any subject
    or use latest policy inference and verify admission fails closed. Disable
    scheduled branch sync while leaving the frozen publish policy auto-approved
    and verify pre-publish repair still starts with the existing bounded attempt
    limit; it must not overwrite the user's schedule setting.
122. Deliver the exact fetch result and verify one mechanical-rebase ticket is
    armed. Exercise a clean real rebase, a preview conflict, and a preview-clean
    but real-rebase conflict. Verify the clean path preserves the exact patch
    series on B; both conflict paths capture actual paths and restore the exact
    clean source before semantic repair; infrastructure failure is never
    mislabeled conflict. In each successful clean/conflict case, verify one
    `BASE_SYNC` StageTurn and dedicated start receipt atomically move Local
    Development to `IMPLEMENTING`. Its output must be based on B and must pass a
    fresh DevReport, validation, Brain review, Local Review, and publish
    authorization. Cancellation after a claimed mutation observes the actual
    Git result instead of downgrading a completed rebase to canceled. Crash
    during rebase, then reconcile under the exact writer fence: a completed
    target is adopted only with patch-series proof; matching in-progress Git
    metadata is aborted to the exact clean source and the immutable Operation
    is rerun. Change `orig-head`, `head-name`, or `onto` and verify the foreign
    rebase remains untouched and reconciliation stays indeterminate.
123. Close a remote PR externally and let its already-observed terminal Task
    enter Cleanup. In separate runs, leave an exact CI-repair Episode and an
    exact branch-sync Episode live; verify terminal observation synchronously
    stops only that current Stage's Episode, replay opens no replacement, and
    Cleanup step 2 no longer counts it. Seed Cleanup's own execution as
    running/unknown and retain a finished historical agent execution. Verify
    step 3 ignores both, while one genuinely unfinished `AGENT_TURN` still
    blocks. Record an indeterminate attempt and verify its evidence persists;
    finish the real provider and verify a probe succeeds, Cleanup completes,
    and the local Task closes.
    Separately move or cancel a Task while a publish-base-sync raw result is
    pending; verify delivery loads its immutable operation despite the stale
    current Stage, records `SUPERSEDED` once, and cannot retry forever.
124. Pause separately while local publish-base fetch and mechanical rebase are
    claimed. Verify settlement starts no new Git effect: fetch parks at its
    frozen source unless the exact target is already present; rebase either
    adopts the exact completed patch series or aborts only its own matching
    in-progress metadata. Verify one immutable cursor resumes only after the
    same Task/Stage is `ACTIVE`. Cancel each cursor and deliver a late result;
    verify it is superseded, cannot advance the Stage, and Cleanup can remove
    the worktree after the writer stops. Fail standing and manual attempts
    determinately and verify only those failures spend budget, standing policy
    retries within its frozen bound, manual policy opens one retry blocker, and
    exhaustion opens one exact blocker. Extend it once and prove the new limit
    is exactly the predecessor limit plus one; replay is idempotent and no
    pause, conflict, cancellation, unknown result, generic Resume, or stale
    command creates an attempt or extension.
125. Finish a CI repair StageTurn with no tree change, fail validation after a
    changed tree, reject the direct push after passed validation, authorize a
    manual base repair, and steer a live CI repair in separate runs. Verify each
    command/delivery
    persists intent and launches no successor writer against the currently
    accepted snapshot. Fold a same-revision replay and verify it remains
    parked. Fold a distinct greater observation revision for the exact current
    Stage and authoritative base and verify exactly one matching freshness
    authorization is recorded. Change the snapshot, revision, Task code base,
    Task epoch, Stage generation, Episode, semantic/execution attempt, or leave
    BranchSync live and verify SQL admission fails closed. Finally try to insert
    an ordinary, no-change-continuation, and steering CI StageTurn without that
    proof; each must be rejected before a DispatchTicket can run.
126. Leave a rejected or not-yet-validated CI repair locally ahead on an older
    base. Verify the exact pending intent admits `CI_PRECONDITION_LOCAL`, old CI
    writers settle first, fetch/rebase/conflict repair produce one proven local
    subject, and validation and push remain skipped. Verify the
    unchanged Remote snapshot cannot itself launch the pending repair; a later
    distinct failed accepted observation admits exactly one normal CI Turn.
    Complete that Turn and verify its only publication is an exact named-head
    force-with-lease against the old Remote head frozen by the successful local
    precondition. Remove or change its BranchSync lineage and verify SQL rejects
    the push before dispatch.
    Replay `START_BRANCH_SYNC` before and after its manual authorization is
    consumed and verify the original observation Operation is returned with no
    new ticket. Fail one BranchSync effect repeatedly and verify one Episode
    owns at most its frozen eight attempts, then records one exhaustion blocker
    and opens no successor for an unchanged Remote-and-local subject. Change
    either subject and verify the old suppression resolves without rewriting
    its evidence. Exercise both BranchSync-owned terminal control choices,
    replay the same command, and verify the blocker clears while two later
    polls of the unchanged subject create no Episode, writer, or budget reset.
127. Return malformed strict output and an ordinary terminal provider failure
    from separate CI repair StageTurns. Verify the writer case restores its
    exact source before releasing the lease, each delivery records one terminal
    receipt and exact blocker, redelivery is receipt replay, and a later failed
    observation for the same head/base creates no Episode, Turn, or budget
    change. Repeat with a malformed historical CI Brain result, a malformed
    BranchSync repair Brain result, and a malformed BranchSync conflict-repair
    StageTurn; preserve only the existing explicit
    Brain retry and BranchSync terminal controls. Neither is eligible for C66.
128. Force exact source restoration to fail after a writer-capable StageTurn
    and verify one Task-wide quarantine blocks implementation, steering,
    validation writers, CI repair, BranchSync, publish, and later Stage
    generations before execution. Authorize `REPAIR_WORKTREE` and verify its
    database-only command creates one durable
    `REPAIR_QUARANTINED_WORKTREE` Local-Git Operation and ticket. Starting from
    dirty detached HEAD and, separately, a dirty wrong branch, prove repair
    discards dirt without first moving that ref, switches to the exact Task
    branch, restores the frozen head, and clears quarantine only after exact
    branch/head/clean-fingerprint/no-Git-control-state delivery proof.
129. Restart after repair mutation and again after immutable result persistence
    but before DispatchResult/delivery. Verify a new fencing token re-proves and
    adopts the same result without trusting the expired token or duplicating an
    Operation. Cancel before and after mutation, fail proof, introduce an
    unsupported Git control marker, change Task epoch/current Stage/worktree or
    quarantine source, and deliver a stale result; every case leaves quarantine
    open. Exact command replay returns the same Operation, while explicit Retry
    creates one new fenced Operation and preserves prior evidence.
130. Enter Cleanup with an open worktree quarantine. Verify every Cleanup Git
    mutation except exact `REMOVE_WORKTREE` remains blocked, while that step may
    remove only the bound path and resolve quarantine as disposed after
    absent-path proof. Reload the Task recovery card through requested, running,
    failed, retryable, successful, and Cleanup-disposal states; capabilities
    come only from the durable projection, ordinary controls remain disabled,
    and neither API nor UI performs Git work.
131. Complete a CI repair StageTurn and then accept an exact passed base-rewrite
    result while the process clock is equal to or earlier than the predecessor
    writer timestamp. Verify the base-rewrite source receives the next durable
    code-subject revision, becomes current, and is the exact subject frozen by
    the following push. Replay the same source and verify no second revision is
    created; create the same code/head/base triple from a distinct later source
    and verify its later revision wins without timestamp or selector ordering.
132. Return provider-successful malformed output after a Remote CI repair Stage
    writer creates one changed commit. Verify the source failure/raw digest and
    existing `CI_REPAIR_OUTPUT_MALFORMED` blocker remain immutable, candidate
    proof is frozen before exact source restoration under the original writer
    token, and restart admits exactly one fresh no-resume, tool-free, read-only
    normalization TaskTurn. Prove it acquires no writer, permission, user wait,
    model/semantic-attempt debit, or CI-repair-budget debit, and its candidate
    passes the unchanged strict Remote CI Stage-result decoder. Before Git
    adoption, change every Task/Stage/Episode/code/head/base/source fence in
    turn and verify no writer starts. With exact fences, verify exactly one
    `ADOPT_NORMALIZED_REMOTE_REPAIR` Operation runs under CapacityManager and a
    fresh worktree writer token, independently proves the unique candidate's
    source parent and changed tree, fast-forwards the exact restored Task
    branch, advances one code-subject revision, charges the original fix once,
    resolves the existing blocker, and resumes the ordinary pipeline. The
    original base-repair authorization may be preserved or reactivated only by
    its immutable continuation receipt; no new authorization exists. Replay
    every boundary and verify no duplicate Turn, Operation, charge, blocker,
    code revision, or continuation. Return malformed/failing normalizer output,
    make ownership stale, or supply zero/multiple/wrong-parent/same-tree Git
    candidates; verify the blocker remains open, any reactivated authorization
    closes, and
    only manual recovery remains with no loop. Finally upgrade an otherwise
    exact pre-V322 source and prove the compatibility path may adopt only one
    changed-tree child in its bounded original execution reflog window; missing
    or ambiguous reflog proof fails, and a V322-or-later source can never use
    that fallback. Finally return malformed BranchSync Stage and Remote repair
    Brain results and verify neither can create a normalization lineage or
    adoption Operation.

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

## Deferred follow-ups

- Keep malformed BranchSync Stage and Remote repair Brain output on their
  existing strict terminal/manual or explicit-Retry paths. Extending the C66
  normalizer or Local-Git adoption to either requires a separate locked design;
  V322 supplies no generic Remote-result recovery.
- Keep Plan self-review delivery strict for now. If malformed typed calls
  remain recurrent after publishing the verdict/concern invariant in both the
  MCP schema and launch prompt, separately design one bounded normalization or
  recovery transition. It must preserve one accepted immutable self-review,
  never infer a verdict from prose, and cannot rerun or replace the terminal
  review without another locked decision.
- Define explicit per-Workspace/repository local Task-worktree bootstrap and
  validation profiles. Commands must be frozen for the Task, run through
  durable dispatch, invalidate readiness when the profile or dependency lock
  changes, and expose failure plus Retry. This is not implemented in the
  current run; local validation remains best-effort and remote CI remains the
  independent authoritative check. Do not share or symlink mutable dependency
  directories between parallel Task worktrees.

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

### 3.34 — 2026-08-02

- Completed the Task-owned PR right-panel lifecycle projection from durable V2
  facts. It covers Development commits and completion, Brain review start and
  terminal structured comment card, first push plus draft/ready transitions,
  CI repair start/address/commit/terminal result, merge/close, and Cleanup
  start/completion.
- Kept lifecycle ownership unchanged: the projection is replay-safe and may
  append missing presentation rows, but only the exact domain command or
  accepted Remote observation changes stable PR or Task state. Successful CI
  repair presentation names the exact final pushed head rather than inferring
  it from the currently visible branch.
- Added deterministic same-time ordering and replay regressions so restart or
  repeated bundle reads cannot duplicate events or place draft/open before the
  first accepted push.

### 3.33 — 2026-08-02

- Added the explicit persisted `TASK_BRANCH_REPAIRABLE` CI classification for
  complete exact check/run/head/base evidence whose ownership is mixed or not
  unanimous. It is not a Task-origin claim.
- Bound that classification to append-only commits on the current named Task
  branch under the existing finite CI budget and fresh-attempt fence. Its path
  is StageTurn -> canonical validation -> normal push -> fresh observation,
  with no base authorization, history rewrite, force-with-lease, or CI Brain.
- Kept `BASE_DETERMINISTIC` strict: only unanimous exact matching base failure
  proof may authorize quarantine/history rewrite. Invalid or incomplete
  provenance remains `UNKNOWN` and mutation-free. Migration V326 preserves all
  historical Episode values, children, indexes, triggers, and foreign keys
  while extending the immutable classification check.

### 3.32 — 2026-08-02

- Corrected CI repair ownership: the CI repair Stage now owns
  repair, canonical validation, publication, and fresh observation. A passed
  validation proceeds directly to the fenced push; no production transition
  creates or waits for a Task Brain review.
- Removed the configurable CI Brain gate. Product-development Brain handoffs,
  remote-feedback Brain review, and BranchSync conflict-repair Brain review are
  unchanged.
- Kept already-durable `REMOTE_CI_BRAIN_REVIEW` rows and their terminal C58
  delivery readable as historical compatibility only, but retired their Brain
  replacement action and provider/MCP launch eligibility. Acceptance coverage now requires direct CI
  validation-to-push, absence of a CI TaskTurn/Operation/ticket/pending result,
  and fail-closed recovery with no replacement work.

### 3.31 — 2026-08-02

- Corrected the Claude CLI transport for finite automatic Task Brain turns:
  when their exact runtime catalog deliberately omits the interactive
  permission callback, the process now preapproves only that catalog's fully
  qualified owner-scoped MCP tools. No wildcard, profile superset, user-wait,
  mutation, or native write capability is introduced.
- Required an unavailable-session fresh fallback to preserve both the absent
  callback and the exact allowlist. This is an implementation correction to
  C55 and acceptance scenario 111, not a new workflow transition or authority.
- Corrected the active MCP scope projection for TaskTurns whose immutable
  trigger provenance names a Stage. Task-owned calls now retain that Stage only
  in durable fences and expose a null runtime Stage id; StageTurns continue to
  expose their exact Stage id. The strict typed `ToolCall` invariant was not
  weakened.

### 3.30 — 2026-08-02

- Made an exactly accepted V2 DevelopmentReport a first-class source in the
  one causal code-subject revision ledger. Raw report and raw Remote rows no
  longer acquire ownership merely by existing; the current projection selects
  the greatest exact-epoch admitted revision, then the initial Task identity.
- Rebuilt the complete eight-table V322 normalization/adoption foreign-key
  graph in place so existing revisions, evidence, identities, child rows, and
  sequence ordering survive while `DEVELOPMENT_REPORT` becomes a legal source
  kind. Upgrade backfill is current-owner-only and preserves the completed
  BASE_SYNC handoff without allowing historical reports to steal authority.
- Corrected pre-V322 repair eligibility to reflect durable delivery order:
  owner Turn, repair Operation, and receipt finish at one instant, then the
  dispatcher terminalizes the exact ticket at or after it. Added strict
  regression coverage for the observed two-millisecond gap and retained every
  identity, execution, digest, blocker, authorization, and reflog fence.
- Extended authorized V2 Trunk purge to remove accepted DevelopmentReport
  delivery receipts and Local StageTurn requests child-first before deleting
  their StageTurns. This preserves the exact accepted-report lineage during
  normal operation without leaving its non-cascading history able to block an
  otherwise quiescent archived-Trunk purge.
- Corrected the shared typed owner-endpoint decoder to admit an absent approval
  gate for the explicitly tool-free Remote repair normalizer. The generic
  AgentTurn handler still requires the exact scoped ByteQuay approval tool for
  every other Task/Stage Turn, so this cannot broaden another Turn's catalog or
  permission path.
- Added a forward-only V324 repair for the exact pre-provider failure produced
  while that decoder bug was present. It rearms the same normalization
  Operation, TaskTurn, and DispatchTicket for infrastructure attempt two,
  preserves attempt one's full AgentExecution evidence, and leaves the due
  row, semantic attempt, CI counters, blocker, code subject, and closed source
  authorization unchanged. Missing provider/process evidence and the exact
  invalid-launch payload are mandatory; any near match fails migration rather
  than creating a general terminal-Turn retry path.
- Added a forward-only V325 compatibility guard correction. A closed pre-V322
  base-repair authorization may validate only its exact accepted normalized
  candidate, and may create or retry Brain review and push only from the exact
  passed rewrite subject tied to that same claimed reauthorization and
  adoption. The current causal code-subject revision must identify that exact
  adopted worktree or base-repair subject; an equal fingerprint/head/base from
  a later source is not authority. Ordinary claimed authorization behavior is
  unchanged; mismatched candidate, rewrite, subject, or retry fences remain
  blocked.

### 3.29 — 2026-08-02

- Promoted malformed Remote CI repair Stage-result recovery into one locked,
  bounded lineage: one fresh no-resume, tool-free, read-only TaskTurn receives
  the frozen raw source and exact Stage schema, and the unchanged strict decoder
  remains the only acceptance boundary. The source failure stays immutable;
  failure, stale proof, or another malformed result remains manually blocked
  without a loop or semantic/model-attempt/CI-repair-budget debit.
- Required V322-or-later malformed Remote CI repair writers to capture exact
  candidate-commit proof before source restoration. A strict changed-tree
  result can advance only through one independently proving
  `ADOPT_NORMALIZED_REMOTE_REPAIR` Local-Git Operation under CapacityManager
  and a fresh writer lease. The
  bounded reflog-window proof is confined to otherwise-exact pre-V322 rows.
- Kept the original malformed-output blocker open until normalized Stage
  adoption. The exact original base-repair authorization may be preserved
  or reactivated only by an immutable continuation receipt; no replacement
  authority is created, and failure closes reactivated authority while
  retaining manual recovery. BranchSync Stage and Remote repair Brain
  malformed-result behavior is unchanged and remains outside this bridge.

### 3.28 — 2026-08-02

- Froze `DEFAULT_REPOSITORY_CI_POLICY_V1`: no-check, missing, queued, and
  pending evidence waits; passed and intentionally skipped checks are
  accepted; failed, neutral, and canceled checks fail. The complete matrix is
  selected and persisted at publish handoff rather than inferred from provider
  order or check names.
- Added a forward-only Remote-owner adoption path for active bindings still on
  the obsolete built-in `PUBLISH_HANDOFF_FAIL_CLOSED` policy. It appends one
  immutable default revision, preserves required checks and history, leaves
  repository-defined policies untouched, and supersedes in-flight work frozen
  to the replaced revision.
- Extended strict C56 with schema-v5 `ACTIONS_JOB_LOG_V1` proof for annotation-
  free Maven compiler failures. Only a complete eight-MiB-bounded, strict-
  UTF-8 capture of the exact already-bound Actions job may enter the versioned
  parser; the durable proof retains identity, byte count/digest, canonical
  diagnostics, and stable fingerprints, never raw log bytes.
- Required identical evidence source/parser versions across failed head/base
  comparison and kept every incomplete, unrecognized, mixed, cached-tail, or
  raw-substring form `UNKNOWN`. The CI Autofix Harness shares the strict
  GitHub job-log fetch/capture primitive, but its heuristic parser remains
  diagnostic-only and owns no Remote Stage state or provenance.
- Recorded the live Actions-log compatibility case where javac continuation
  records carry runner timestamps but no repeated `[ERROR]` marker. The
  versioned parser recognizes only the five canonical continuation prefixes;
  all other partial or duplicate evidence remains `UNKNOWN`.

### 3.27 — 2026-08-02

- Replaced timestamp/string arbitration of local Task code subjects with one
  durable, source-keyed database revision ledger. Accepted worktree, steering,
  CI base-rewrite, and local publish-base-sync facts advance ownership exactly
  once in their source transaction and only within the current Task epoch.
- Preserved the local BASE_SYNC-to-DevelopmentReport handoff and added scenario
  131 proving that clock rollback, equal timestamps, replay, and duplicate
  triples cannot select a stale predecessor for validation or push.
- Recorded two E2E implementation corrections without changing ownership:
  typed Stage tools resolve only the active Turn worktree, and malformed Local
  result recovery requires a classified protocol failure plus positive exact
  terminal-execution proof, then reconstructs the successor from complete
  frozen launch and provider-trace evidence.

### 3.26 — 2026-08-02

- Added the durable Task-owned `REPAIR_QUARANTINED_WORKTREE` Local-Git
  Operation. Central quarantine now blocks every ordinary Task writer across
  Stage changes until exact Task-branch, frozen-head, clean-fingerprint, and
  no-Git-control-state proof is accepted under fresh capacity and writer
  fences.
- Locked the safe restore order, crash/reconciliation and immutable receipt
  behavior, success-only quarantine clear, and new-Operation Retry semantics.
  Failed, canceled, stale, unsupported, or incompletely proven repair remains
  quarantined.
- Kept Cleanup `REMOVE_WORKTREE` as the sole independent absent-path disposal
  bypass and added the exact Task recovery API/UI projection plus acceptance
  scenarios 128–130.

### 3.25 — 2026-08-01

- Made malformed Remote CI/Branch Stage and Brain output one typed terminal
  result instead of an infrastructure delivery loop. Stage writers restore the
  exact source under their live writer fence; immutable receipts and exact
  blockers suppress same-subject relaunch without consuming or resetting
  semantic budgets. Existing explicit Brain retry and BranchSync terminal
  controls remain the only recovery authority.
- Recorded, but did not implement, the separately approved one-shot strict JSON
  normalization bridge if malformed Remote repair output remains recurrent.

### 3.24 — 2026-08-01

- Locked locally-ahead CI base freshness to a local-only BranchSync
  precondition: fetch/rebase/conflict repair may update the exact Task code
  subject, but validation, Brain review, and push remain owned by the later
  freshly authorized CI Turn. Its final publication carries the successful
  precondition lineage and uses one exact named-head force-with-lease with no
  ordinary-push fallback.
- Made `START_BRANCH_SYNC` replay return its frozen observation before and
  after authority consumption, and made fresh green evidence cancel rather
  than authorize every pending repair intent.
- Raised the new-policy BranchSync default to eight under the existing hard
  ceiling of ten. Determinate effect retries now remain in one Episode;
  exhaustion records exact subject-bound suppression so repeated observations
  cannot reset the budget. Durable BranchSync-owned manual-takeover and
  stop-automation commands clear attention without unsuppressing the unchanged
  subject or launching work.

### 3.23 — 2026-08-01

- Required every CI-repair writer, including no-change continuation, later
  validation/Brain repair, manual base repair, and user steering, to carry an
  immutable authorization from a distinct accepted Remote observation when it
  follows a predecessor. Exact monotonic observation revision and snapshot
  lineage replace timestamp inference.
- Ordered accepted observation folding as BranchSync before CI and made live
  BranchSync/base mismatch defer all CI writer admission. Manual and steering
  actions now persist intent and request observation instead of launching
  synchronously; SQL rejects every unproved CI StageTurn surface.
- Recorded, but did not choose, the locally-ahead base-drift repair behavior;
  publishing rejected/unvalidated partial repairs through full BranchSync
  remains prohibited pending an explicit locked decision.

### 3.22 — 2026-08-01

- Added an exact first-publish `BASE_MOVED` recovery path: publish performs no
  remote write, Local Development returns to Local Review, and frozen standing
  consent or one exact manual blocker admits bounded local fetch/rebase work.
- Required clean and conflicting rebases to re-enter semantic Local Development
  through one `BASE_SYNC` StageTurn with a fresh DevReport, validation, Brain
  review, Local Review, and publish authorization; old-base evidence is never
  reused and scheduled branch-sync enablement is not treated as consent.
- Required the real rebase result, rather than merge-tree preview alone, to
  classify conflicts and restore the exact source on conflict or failed
  mutation. Added exact cancellation, replay, stale-result, Cleanup, and purge
  fences for the new Episode.
- Required crash-mid-rebase reconciliation to hold the exact Task writer fence,
  adopt only a proven completed patch series, and abort/retry only rebase state
  whose source branch, source head, and target base match the immutable
  Operation. Foreign or malformed Git state remains untouched and indeterminate.
- Corrected Cleanup provider reconciliation so only unfinished Agent Turns
  block provider quiescence, while failed/indeterminate attempt evidence remains
  durable and externally closed PR Tasks can complete local Cleanup.
- Made exact accepted `MERGED`/`CLOSED` truth synchronously stop the current
  Remote Stage's live CI-repair and branch-sync Episodes before Cleanup handoff.
- Added forward migrations V313–V316 and acceptance scenarios 120–123. V316
  narrowly rearms Cleanup probes stranded by the old result guard and settles
  only terminally observed CI/branch children with exact canceled-ticket proof;
  unrelated manual reconciliation remains parked.

### 3.21 — 2026-08-01

- Locked Claude's exact `PLAN_DRAFT` / `PLAN_SELF_REVIEW` permission-prompt
  argv bridge even when the generic catalog is empty. The bridge admits only
  the purpose-matching Plan result call after the complete owner fence and
  grants no additional tool, mutation, permission wait, or source access.
- Made the Plan overlay read and write only canonical revisioned
  `TaskAutomationPolicy`, required pending policy writes to settle before
  revision-fenced Plan approval, and retained the atomic
  `autoMerge => autoApprove` invariant.
- Defined successful old-Task-epoch Remote observation delivery as one
  `SUPERSEDED` raw receipt with no snapshot or lifecycle fold. Allowed only an
  exact-binding, already-synchronized terminal PR cache to correct UI display;
  cache state remains non-authoritative for workflow.
- Promoted the malformed Development Brain follow-up into one locked,
  application-tool-free `DEVELOPMENT_BRAIN_RESULT_REPAIR` TaskTurn after the original and
  one ordinary retry are malformed. It starts fresh from frozen raw
  output/digest and required shape, uses the unchanged strict decoder and no
  semantic budget, and fails to one manual blocker without an automatic loop.
- Required Agent result delivery to wait for terminal execution evidence,
  prohibited replacement attempts from overtaking unfinished evidence, and
  required positive exact-current terminal proof rather than treating missing
  evidence as success. `USER_WAIT` and other non-success dispositions no longer
  enter success-payload parsing; only an exact owner/fence-matched canceled
  no-launch result may synthesize `PROVIDER_CANCELED` from a null payload.
  Maintenance is limited to evidence finalization rather than effect replay;
  ambiguous reconciliation and superseded attempts become `UNKNOWN`. V310
  forward-reconciles exact stale Remote results, while V312 repairs historical
  terminal-ticket/active-execution contradictions and exact stale no-launch
  StageTurn cancellations so cancellation Cleanup cannot remain stranded at
  quiescence. Added acceptance scenarios 113–119.

### 3.20 — 2026-08-01

- Locked the missing exact recovery transition for terminal provider/process
  failures in CI-repair and branch-sync Brain TaskTurns. Failed raw delivery is
  consumed once, its exact Turn/Operation terminalizes, and the parent Episode
  remains at its Brain cursor with one recovery blocker instead of looping at
  `RESULT_PENDING`.
- Required explicit idempotent replacement from frozen context with a fresh
  TaskTurn/Operation/ticket, no failed CLI resume or cumulative baseline, no
  semantic repair-budget charge, a separate monotonic execution ordinal for
  branch replacement, stale-predecessor fencing, and exact recovery
  projection/API/UI. Added acceptance scenario 112.
- Corrected an implementation-only Cleanup admission mismatch found by the
  clean-baseline regression run: terminal TaskOutcome summaries now admit the
  same typed CLI/API Brain lanes (9/10) required by their TaskTurn and summary
  Operation. The retired generic agent lane (2) is no longer accepted, and the
  current runtime is covered from exact terminal outcome through enrichment.

### 3.19 — 2026-07-31

- Bound Claude's optional `--permission-prompt-tool` argument to the exact
  runtime MCP catalog rather than the frozen endpoint's canonical gate name.
  Interactive owners that expose `approval_prompt` retain it; finite automatic
  Brain and other noninteractive catalogs omit it without broadening access.
- Added acceptance scenario 111 after a live remote-CI Brain review proved that
  naming a deliberately unexposed permission tool makes Claude reject even an
  otherwise allowed read-only MCP call.

### 3.18 — 2026-07-31

- Closed a live CLI prompt gap without relaxing strict delivery: every V2
  direct-final-text Stage and Brain boundary now requires one raw JSON object,
  forbids Markdown fences and surrounding prose, and carries its exact schema
  through initial, steering, and retry launches.
- Preserved immutable malformed output and the existing exact replacement
  recovery. No decoder normalization, inferred result, or new transition was
  added.

### 3.17 — 2026-07-31

- Made the already-enforced Plan self-review verdict/concern invariant explicit
  in the MCP tool contract and both initial and retry prompts: `APPROVED` has
  no concerns, non-blocking caveats use follow-ups or stewardship, and a
  rejected call that recorded nothing may be corrected until one typed result
  is accepted. Strict typed-only delivery and the terminal no-verdict blocker
  are unchanged.
- Recorded bounded Plan-review normalization or recovery as a deferred option
  requiring a separate locked decision; this version adds no salvage
  transition for an already-terminal self-review.

### 3.16 — 2026-07-31

- Locked GitHub's active rules for the exact base branch as the authoritative
  second merge-queue proof when GraphQL reports neither a queue nor an entry. A
  complete response proves `SUPPORTED` by containing `merge_queue` and
  `UNSUPPORTED` by its absence; every incomplete, malformed, failed, or
  mismatched read remains `UNKNOWN` and fails closed.
- Required mutation-deciding remote reads to finish before the external-effect
  claim. A pre-claim read failure retries only the same exact read-only
  preflight and consumes neither a semantic attempt nor merge/queue budget;
  ambiguity after the claim continues through normal effect reconciliation.

### 3.15 — 2026-07-31

- Closed three implementation gaps found by the pre-live operational trace
  without changing the locked workflow: Remote observations now persist
  GitHub's typed merge-queue capability; missing legacy evidence stays
  `UNKNOWN` and cannot start a merge.
- Kept failed base-history rewrite proof as immutable audit evidence while
  forbidding it from becoming the current repair subject. Both Java delivery
  and SQL admission require a passed validation from a succeeded rewrite
  Operation before the rewritten head can advance.
- Restored exact database enforcement for the base-rewrite validation
  DispatchTicket. Its dedicated operation/callback mapping requires the Task
  writer lease; ordinary validation and writerless substitutions fail closed.
- Made legacy `UNKNOWN` merge-queue evidence recoverable: it remains immutable
  observation history but cannot create readiness, policy redrive, or merge
  authorization. A later exact observation with known capability resumes the
  same Remote owner. The GitHub adapter proves `UNSUPPORTED` only from an
  explicit absent queue and absent entry; malformed or omitted fields fail the
  observation rather than silently enabling direct merge.
- Required a failed, canceled, or exceptional base-history rewrite to restore
  and verify the exact frozen StageTurn input head under the writer lease before
  returning. Failure evidence retains the rejected rewritten SHA, while retry
  and restart begin from the durable input subject instead of double-rewriting
  rejected history.

### 3.14 — 2026-07-31

- Kept CI classification strict while adding a narrow schema-v4 proof for
  dependency-only GitHub Actions aggregates: exact workflow blob, run attempt,
  complete job/check identities, static literal dependency graph, and one
  recognized result-only fan-in whose declared runtime step alone failed. The
  only admitted matrix shape is one exact
  literal-prefix/suffix `${{ matrix.<identifier> }}` job-name template whose
  runtime instances map uniquely; aggregate prose, all other dynamic workflow
  shapes, incomplete job sets, rerun races, and mixed dependencies remain
  `UNKNOWN`.
- Reconfirmed that standing `autoApprove`, including consent implied by
  `autoMerge`, authorizes only an already-proven base repair on the Task PR
  branch within the existing CI budget; without that policy each exact repair
  attempt requires explicit blocker authorization.

### 3.13 — 2026-07-31

- Made versioned, immutable, typed exact-subject CI provenance the only
  authority for a non-`UNKNOWN` failure classification; incomplete,
  mismatched, unsupported, and raw-text evidence now fail closed.
- Defined immutable `UNKNOWN`-to-proven Episode supersession without resetting
  counters or budgets, with predecessor quiescence, idempotent replay, and
  blocker resolution only after the successor is durable.
- Added fresh per-attempt authorization and distinct Task-owned normal-push and
  base-owned tip-repair/history-rewrite/force-with-lease protocols, including
  standing `autoApprove` consent without direct base-branch writes or inferred
  push fallbacks.
- Added acceptance scenario 108 and moved CI provenance and base-repair consent
  from deferred design questions into the locked contract.
- Added an adapter/storage compatibility assertion after an end-to-end dry run
  exposed that typed provenance and its SQL gate must share one schema version.
- Added fail-closed pagination, per-attempt blocker identity, and explicit
  dispatch-handler coverage after implementation review found that a partial
  GitHub check list, a reused blocker id, or an unregistered base-rewrite
  operation could otherwise strand a valid repair.
- Made base-history rewrite reconciliation read-only and recoverable from the
  already-rewritten exact subject, and made an observation of the exact pending
  push head provisional until push delivery records it. Successful push
  delivery always requests a fresh observation, so callback ordering cannot
  stop the owning Episode or lose the pushed head's CI result.
- Required infrastructure classifications to prove the exact failed check's
  typed identity, lineage, and tested subject as well as the top-level
  provenance envelope. Provider-native canceled or timed-out conclusions do
  not need synthetic annotation fingerprints, but incomplete, unmatched, or
  mixed check sets remain `UNKNOWN`.
- Added transactional supersession, authorization-consumption, failed-
  validation, fresh-retry, and exact command-replay regressions; replaying a
  base-repair command with a different expected worktree head now fails closed.

### 3.12 — 2026-07-31

- Kept a CI Episode live while classification or effect failure needs user
  attention, and made an open Episode blocker suppress duplicate repair arms
  without pretending the Episode was explicitly stopped.
- Limited same-subject terminal suppression to exhausted Episodes and consumed
  Stop automation/Manual takeover commands; automatic historical stops remain
  recoverable.
- Added V302 reconciliation for orphaned open blockers created by the prior
  stop-on-block implementation, plus acceptance scenario 107.

### 3.11 — 2026-07-31

- Made exact approved and budget-exhausted Brain-to-Local Review handoffs open
  the stable Task-owned PR in the same serialized transaction, with fail-closed
  rollback and idempotent replay.
- Added V301 forward reconciliation for exact active or resumable V2 Local
  Review subjects stranded at `local-drafted`, plus acceptance scenario 103.
- Removed legacy TaskStore metadata writes from successful V2 publish delivery;
  stable PR/RemotePrBinding identity and the serialized TaskManager handoff are
  now the only authorities, with restart/replay covered by acceptance scenario
  104.
- Made Remote Observation persistence safe with the production single JDBC
  connection and assigned exact parked read-only ticket recovery to the Remote
  Observation maintainer, with acceptance scenario 105.
- Canonicalized GitHub check runs to the durable `CHECK_RUN` vocabulary and
  added a bounded replay bridge for the already-persisted
  `GITHUB_CHECK_RUN` adapter alias, with acceptance scenario 106.

### 3.10 — 2026-07-30

- Defined strict malformed Development Brain delivery as a Task-owned protocol
  failure with one fresh, idempotent, no-budget Retry, and added acceptance
  scenario 102. Deferred both an optional bounded normalization Turn and
  explicit local worktree bootstrap/validation profiles for separate design.
- Separated provider stream evidence from the typed owner result: Claude uses
  its terminal result field and Codex uses its last agent message, including an
  empty final item, so progress commentary cannot corrupt strict JSON delivery.
- Required malformed `RESULT_PENDING` Stage recovery to admit its exact
  replacement immediately after all live execution authorities are proven
  quiescent, rather than waiting for a ticket state that result rejection
  cannot terminalize. Ordinary Stage steering is disabled while either exact
  recovery path is open.
- Added forward-safe reconciliation for already-accepted Local Stage failures,
  with one-blocker and ordinary-admission database guards plus complete ordered
  attempt trace reconstruction.
- Made accepted failure of the exact current Local Development StageTurn open
  one exact Stage-owned blocker while preserving its checkpoint, and made
  explicit Retry reconstruct a fresh fenced successor without failed-session
  reuse.
- Defined the canonical structured Plan JSON compatibility projection while
  retaining the historical Markdown fallback, preventing a valid Plan from
  projecting zero steps and hiding Brain policy controls.
- Added acceptance scenario 101 and expanded scenario 99 for exact failure
  replay/fencing and both Plan content formats. Provider-quota waiting and
  cross-provider fallback remain undecided.
- Required Local Development Brain launches to request the strict typed
  TaskTurn verdict payload directly instead of referring to a legacy
  owner-scoped verdict tool that the V2 Task Brain cannot expose.

### 3.9 — 2026-07-30

- Clarified that an exact typed V2 permission callback resolves only its V2
  Task policy and typed Turn/Operation/Stage runtime; retained
  `ThreadService`, legacy Task phase/auto-approve, and generic `AgentRun` state
  are never fallback policy or mutation targets.
- Required typed auto-approval to preserve tool input and the non-auto-approved
  path to create one exact, idempotent durable PermissionRequest.
- Added acceptance scenario 100 for Local Development permission allow/wait
  behavior and exact CANCEL_AND_REPLACE recovery from malformed
  `RESULT_PENDING` delivery, including the projected predecessor identity and
  replay-stable Stage+predecessor command.

### 3.8 — 2026-07-30

- Required the V2 compatibility read to expose the latest immutable Plan and
  exact review/approval state to the existing Task Brain approval card.
- Kept Plan policy outside the read model: enabling auto-merge still implies
  auto-approval and may synchronously advance an awaiting Plan.
- Added acceptance scenario 99 for Markdown Plan projection, stale-review
  rejection, automatic policy redrive, and the locked historical card.

### 3.7 — 2026-07-30

- Clarified that the Plan Task Brain is code/worktree read-only while its exact
  purpose-matching Plan result is typed owner delivery.
- Required the Claude permission callback to auto-allow only that result after
  exact TaskTurn, Operation, and purpose authorization, without a user wait.
- Added acceptance scenario 98 for both Plan purposes and fail-closed
  mismatches, plus exact failed-draft retry without Task Resume or failed CLI
  session reuse.

### 3.6 — 2026-07-30

- Kept one fresh OS process per CLI process attempt: normally one per admitted
  Turn, with at most one bounded fresh fallback for an unavailable resumed
  session.
- Made the owner freeze a resume token and complete durable fallback together,
  with current typed MCP configuration rebound on every resumed invocation.
- Allowed one fallback only for an explicit missing/expired session before any
  provider-work evidence; prohibited replay after ambiguous or accepted work.
- Defined lineage isolation for Trunk, Task Brain, Stage generation, and
  ReviewAssignment seats, and kept retry, replacement, and API context
  reconstruction independent of CLI session availability.
- Added acceptance scenario 97 for continuity, isolation, fallback, and
  no-replay behavior.
- Made domain owners, rather than the CLI adapter, select one causally latest
  compatible predecessor and freeze its fallback before admission.
- Defined deterministic Trunk, Task, Stage, and Review lineage order; a newer
  failed, ambiguous, or incompatible Turn blocks reuse of an older session.
- Distinguished exact USER_WAIT continuation from explicit replacement,
  rejected overlapping Task Brain sends before attachment persistence, and
  required provider-specific unavailable-session evidence with no preceding
  unknown output.
- Required reconstructed Codex prompts to use stdin so durable history is not
  bounded by the operating system's argument-size limit.
- Froze Codex cumulative usage baselines with resume tokens, retained raw
  terminal cumulative totals, and charged only per-Turn deltas without replay
  on regression.
- Registered every CLI process before prompt delivery, retained immutable
  sequential PID evidence, and atomically replaced the current recovery PID for
  bounded fallback.
- Made completion summaries, remote-CI/branch-sync Brain verdicts, and review
  assignments explicitly noninteractive; their scoped MCP catalogs cannot
  create a typed user wait.

### 3.5 — 2026-07-30

- Made CHANGES_REQUESTED Plan self-review a human decision gate instead of an
  automatic redraft trigger.
- Added exact all-concern human adjudication on the same Plan revision while
  preserving APPROVED-only policy and automation gates, blockers, Direction
  exceptions, and the one-self-review invariant.
- Required typed, non-dismissible Direction linkage and exact idempotent command
  replay for adjudication evidence.
- Restricted scoped exceptions to unchanged frozen Direction and required a new
  revision for every applicable-basis change.
- Preserved generic follow-up isolation and added Task/Stage optimistic-version
  fences to all-concern adjudication.
- Required wrong intelligence to be corrected through Memory and a new frozen
  Plan revision, with revision idempotency scoped to its producing command
  rather than historical content.
- Added acceptance scenarios 95 and 96 for revision, adjudication, typed
  stewardship, command replay, A/B/A history, and non-authority boundaries.

### 3.4 — 2026-07-30

- Incorporated the tracked Project Intelligence contract as a subordinate
  extension without reopening lifecycle ownership or the completed migration.
- Locked one immutable intelligence projection per Plan revision and full
  ReviewRound, explicit empty intelligence for quick review, and the rule that
  intelligence selects attention but cannot supply evidence or decide review
  outcomes.
- Required a new Plan revision or ReviewRound when the governing basis changes,
  introduced an exact human-approved Plan exception later narrowed by 3.5 to an
  unchanged frozen basis, and prohibited any new intelligence Stage, scheduler,
  executor, or capacity authority.
- Added the compatibility-matrix boundary and acceptance scenario 94 for
  Workspace isolation, frozen reuse, quick-review emptiness, and downstream
  non-authority.
- Clarified C48 and scenario 87 so frozen source evidence remains exclusive to
  the source snapshot while post-cutover guidance comes only from the separate
  immutable round-intelligence row.

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
