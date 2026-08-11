# Task Agent

Status: normative greenfield replacement specification
Parent architecture: [Development-flow architecture](./README.md)

## 1. Purpose

The Task Agent is the persistent semantic owner of one confirmed development goal.
It understands the goal, changes the code, interprets review feedback, prepares local
and remote review artifacts, and continues until the Task reaches a program-observed
terminal state.

One Task has one branch and one worktree. The Task Agent is the primary agent writer.
The [CI Fixer](./ci-autofix.md) is the only other agent writer. Bounded deterministic
Git operations from [Upstream Sync](./upstream-sync.md) may also mutate as a program
operation. The [Workflow Runtime](./workflow-runtime.md) serializes all three through
the same writer lease. The [Adversarial Reviewer](./adversarial-reviewer.md) is always
read-only.

This is a new component. It does not wrap or translate earlier workflow sessions,
stages, reports, or result formats. There is no migration, compatibility route, dual
write, or fallback to an older agent flow.

## 2. First-principles result

A development owner needs continuity across five activities:

1. understand one confirmed goal;
2. inspect and modify one repository checkout;
3. react to independent review;
4. react to user and GitHub feedback;
5. prepare exact artifacts for user-authorized external effects.

Splitting these activities across handoff agents discards the reasoning that makes
the implementation coherent. Running them concurrently creates stale patches. The
smallest safe design is therefore one persistent Task Agent, resumed with durable
event references, plus two narrow specialists:

- a fresh read-only reviewer for independence;
- a persistent CI Fixer for repeated, bounded CI diagnosis.

The program schedules and proves facts. The Task Agent decides meaning. Neither side
parses the other's final prose as a workflow command.

## 3. Non-goals

- Re-clarifying the launch goal already confirmed by the user and Trunk.
- Receiving the full Trunk transcript or a generated plan as hidden instructions.
- Delegating normal development to another writer.
- Monitoring GitHub or polling CI.
- Deciding that its own work is approved.
- Pushing, posting GitHub replies, resolving GitHub threads, merging, or executing any
  other remote effect.
- Writing PR timeline events.
- Marking the Task complete based on agent prose.
- Producing a machine-read approval result.

## 4. Owned durable data

The program stores Task-Agent lifecycle in the runtime's shared `AgentSession`,
`AgentRun`, and `AgentResult` records. Do not create parallel Task-specific session or
turn tables. The following are role-specific fields/views of those shared records.

### `AgentSession` where `role = TASK_AGENT`

| Field | Meaning |
|---|---|
| `sessionId` | Program-generated persistent session identity |
| `taskId` | Exactly one owning Task |
| `state` | Shared runtime state: `NEW`, `IDLE`, `RUNNING`, `PARKED_CHILD`, or `CLOSED` |
| `createdAt`, `updatedAt` | Program timestamps |
| `lastRunId` | Most recent run |
| `closeReason` | Program-observed terminal reason |

### `AgentRun` where `role = TASK_AGENT`

| Field | Meaning |
|---|---|
| `runId` | Program-generated turn/run identity |
| `sessionId` | Owning persistent session |
| `wakeKind` | `INITIAL`, `UPSTREAM_SYNC`, `ADVERSARIAL_REVIEW`, `LOCAL_REVIEW`, `REMOTE_FEEDBACK`, `CI_FIX_READY`, `USER_ANSWER`, or `RECOVERY` |
| `inputRefs` | Program-owned references to the exact facts causing the wake |
| `intendedGateKind` | Program-derived `INITIAL_PUBLISH`, `CI_UPDATE`, or `REMOTE_FEEDBACK` for this work intent; inherited across reviewer/user-answer resumes and never model-supplied |
| `startingHead`, `endingHead` | Git facts measured by the program |
| `status` | `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, or `CANCELED` |
| `resultRef` | Opaque, automatically persisted agent completion prose |
| `startedAt`, `completedAt` | Program timestamps |

### Data owned elsewhere

- Git commits are the code-change record.
- Check runs belong to the check runner.
- PR title/body revisions belong to the local PR.
- Review runs/results belong to reviewer runtime.
- Task-scoped questions and exact user answers belong to the Task conversation/runtime;
  they append decisions without rewriting the immutable launch goal.
- Private local-review threads/revisions belong to User Gates; GitHub feedback
  revisions/drafts belong to Remote Feedback.
- Gates, authorizations, effect receipts, GitHub observations, and timeline projections
  belong to their named components.

The Task Agent creates these records only through tools. Its turn-completion prose is
stored verbatim as opaque `AgentResult` content and never inspected by the program for
fields, status, approval, findings, or next action.

## 5. Exact inputs

### Initial launch envelope

The program supplies:

- `taskId` and `sessionId`;
- exact confirmed `goal` from successful normal `start_task(goal)` or the
  user-confirmed `UpstreamSyncCommands.startConfirmed(...)` entry;
- `repositoryId`, worktree path, Task branch, base commit, and measured current head;
- runtime policy: one writer lease, no agent remote effects, and exact user gates;
- available tool capabilities.

It does **not** supply exploratory Trunk discussion, discarded alternatives, hidden
reasoning, or a separate agent's implementation plan.

The Task Agent's first actions are:

1. call `read_task_state()` to verify current durable facts;
2. read repository instruction files and relevant source in the Task worktree;
3. call `search_project_context` when source-cited project memory is useful;
4. inspect the base/current state and form its own working plan;
5. begin implementation under the writer lease.

The working plan may be narrated to the user as normal live agent activity. It is not
a durable approval artifact, a separate role, or reviewer input.

### Resume envelope

The program resumes the same session with one or more immutable references:

- `LocalReviewBatchReady(batchId)`;
- `RemoteFeedbackReady(worksetId, observedRemoteHead)`;
- `CiFixReady(roundId, attemptId, candidateHead)`;
- `AgentResultReady(runId, reviewedHead)`;
- `UserAnswerReady(questionId, answerRevisionId)`;
- `RecoveryRequired(failedRunId, measuredHead, worktreeStatusRef)`.

The envelope is a wake-up hint, not the full source of truth. Task calls
`read_task_state()` and then reads the referenced records. Events arriving while it
is running are queued; the program never interrupts its active turn with another
agent's message.

## 6. Lifecycle: start to stop

These are narrative lifecycle steps, not persisted phases or stages.

### Launch and development

1. For an ordinary Task, [Trunk Agent](./trunk-agent.md) successfully calls
   `start_task(goal)`. The optional [Upstream Sync](./upstream-sync.md) entry
   instead creates its Task only after the user confirms an exact durable range
   preview; both paths store one self-contained goal.
2. Runtime creates one branch/worktree and one `AgentSession(role=TASK_AGENT)`.
   An ordinary Task persists `INITIAL` work and ensures reconciliation;
   `WorkSelector` creates its first run. A confirmed upstream Task leaves the
   session `IDLE` while deterministic `UPSTREAM_SYNC` operations apply clean
   commits; the selector creates a Task turn only for conflict, material choice,
   or final semantic review.
3. For an eligible agent wake, runtime acquires the Task writer lease and starts
   the session with the bounded launch envelope.
4. Task reads durable state, repository instructions, code, and relevant Project
   Intelligence.
5. Task edits and tests in the worktree. It commits coherent changes locally; it never
   pushes.
6. Task calls `save_pr_draft(title, body)` when the change is reviewable.
7. Task calls `run_checks()` or selects an allowed named profile. The program runs
   every profile required by the current `LocalCheckPolicyRevision` and records each
   immutable `LocalCheckRun` attempt, including bounded fail-closed output
   reference/excerpt, conclusion, policy revision, attempt sequence, and
   measured start/end head. Environment values are never hashed into evidence.

The current ordinary-INITIAL implementation is narrower than this normative
surface. Its first body exposes bounded read/edit, a fixed commit-and-adopt tool,
and `request_initial_review(title, body)`. That terminal command mechanically
materializes/reuses the one local unpublished PR, appends the exact draft, runs
the program-selected INITIAL check profiles, and seals a fresh reviewer request.
The exact reviewer-result successor may correct and commit another descendant,
request a fresh review, or call `ready_for_initial_publish()` after a completed
same-head review. It does not expose a generic shell, raw Git, owner IDs, or a
question tool.

### Adversarial review

8. When Task intends to present or publish the current committed change, it calls
   `spawn_agent(role="adversarial_reviewer")`.
9. Runtime freezes the current head/diff/evidence, parks Task's mutation activity,
   starts a fresh read-only reviewer, and automatically stores its result.
10. Runtime resumes Task with `AgentResultReady`; Task reads the opaque prose.
11. Task decides which observations are actionable. It fixes accepted issues,
    reruns checks, commits, and requests another exact-head review. The normative
    future surface calls `request_user_input` for a material product choice; that
    tool and its owner are not exposed in the current checkpoint.
12. No program branch asks whether “findings exist.” Completion against the current
    head is the only objective reviewer fact.

If the result-delivery turn leaves the exact reviewed revision and mechanically
inspected worktree unchanged, it may stop without spawning another reviewer.
That consumes one durable input only; it does not mean approved, ready, or gated.
If the turn adopts a descendant revision, the same zero-argument terminal
reviewer command must freeze a fresh subject before stop. Missing that command
consumes the old result and creates typed `NEEDS_ATTENTION`; a naked Task resume
cannot bypass it.

### Local user review and first publication

13. When the exact committed head, PR draft, checks, and review are ready, Task calls
    the terminal tool `ready_for_review()`.
14. On success, runtime ends the run, persists its opaque result, measures the clean
    head, and releases the writer lease. [User Gates](./user-gates.md) then validates
    objective freshness and opens the exact initial-publication gate.
15. User inspects the actual diff, check evidence, review result, and PR draft.
16. Adding/editing local comment drafts invalidates authorization freshness. When the
    user submits them, User Gates freezes a `LocalReviewBatch`, transitions that gate
    revision to `CHANGES_REQUESTED`, stores pending `LOCAL_REVIEW` work with the
    immutable batch reference, and ensures reconciliation. `WorkSelector` alone
    creates the later Task writer turn.
17. Task reacquires the lease, reads all current thread revisions, and edits/replies.
    If code changes, it commits, checks, and obtains fresh adversarial review. A
    reply/resolution-only round keeps the already-current exact-head evidence.
18. Task calls `ready_for_review()` again. This repeats until the user authorizes the
    exact current gate revision and chooses the frozen publication-ready policy:
    `KEEP_DRAFT` or `MARK_READY_ON_EXACT_GREEN`.
19. The program—not Task—pushes the authorized head and opens the GitHub PR. With
    `KEEP_DRAFT`, it remains draft. With `MARK_READY_ON_EXACT_GREEN`, the program may
    mark it ready only after GitHub proves required CI green for that exact pushed
    head. The policy may carry only to a later program-authorized `CI_UPDATE` head;
    it never follows external or feedback-driven heads. Task remains parked and never
    marks the PR ready itself.

### CI and remote feedback

20. [GitHub Integration](./github-integration.md) records remote events without waking
    an agent through prose.
21. A final red CI state creates CI-Fixer work directly. The runtime waits for any
    current writer, then resumes the persistent CI Fixer without a Task-Agent relay.
    When it commits a candidate, runtime stores `CI_FIX_READY` and reconciles; the
    selector decides the next writer under the Task lock.
22. Task inspects the CI Fixer diff/result, reruns appropriate checks, obtains fresh
    adversarial review, and calls `ready_for_review()` for a CI update gate or narrow
    standing consent.
23. New GitHub review comments persist as `REMOTE_FEEDBACK` work. When selected, Task reads full immutable
    thread revisions, makes any required code changes, writes local reply drafts, and
    proposes remote resolutions.
24. If code changed, Task commits, checks, and requests fresh review. A reply-only
    batch keeps the same exact head. Task then calls `ready_for_review()`.
25. The user reviews one exact batch. The program executes authorized push/reply/
    resolve actions and records receipts; Task never performs them.
26. Runtime parks Task until another durable event requires semantic work.

### Convergence and stop

27. If CI and remote feedback arrive together, runtime preserves both. Only the
    current lease holder writes:
    - if CI Fixer is already running, feedback waits; Task then inspects the new head
      and feedback together;
    - if Task is already addressing feedback, the old CI failure waits; after the new
      head exists, the observer decides whether that failure is still current before
      waking CI Fixer.
28. Merge authorization/execution is program/user work. Task does not declare success.
29. When GitHub observation proves merge/closure, or the user cancels locally, runtime
    closes the session and releases the worktree according to retention policy.
30. Durable Task, PR, review, check, gate, effect, and timeline records remain.

## 7. Agent tools and program APIs

### Task Agent tool surface

| Tool | Contract |
|---|---|
| Normal source/Git tools | Read/search/edit the one Task worktree and create local commits; remote push is denied |
| `read_task_state()` | Returns current head, pending event refs, PR draft ref, current checks/reviews/gate, and remote identity/state |
| `search_project_context(query, path_hints?)` | Returns advisory source-cited project knowledge |
| `run_checks(profile?)` | With no profile, derives the active gate kind and runs all policy-required profiles; an optional allowed profile runs only that focused check. Returns stored `LocalCheckRun` references, not agent-authored evidence |
| `save_pr_draft(title, body)` | Before first publication only: on a clean committed non-empty diff, materializes the stable Local PR if absent and stores a title/body revision. Reject once remote identity exists; v1 has no post-publication metadata-edit effect |
| `spawn_agent(role="adversarial_reviewer")` | Successful call returns `reviewRequestId`, seals/ends the parent turn, and durably queues the one allowed exact-head read-only reviewer request; the claimed reviewer operation later creates the child session/run |
| `request_initial_review(title, body)` | Current ordinary-INITIAL terminal tool. After a fixed commit/adoption it materializes or reuses the one local unpublished PR, appends the bounded draft, records exact INITIAL checks, and seals the fresh read-only reviewer request. No owner IDs are accepted or returned. |
| `ready_for_initial_publish()` | Current INITIAL reviewer-result terminal tool. It accepts no arguments, consumes one fresh authenticated repository observation into a stored target/subject/action/request bundle, and stops. Only the STOPPED finalizer may atomically open the manual INITIAL gate and settle the run/session/input/pointer/lease. |
| `read_agent_result(run_id)` | Reads an already persisted result, primarily for recovery/history; normal completion is delivered automatically |
| `list_local_review_items(batch_id?)` | Lists private local review items and current revision IDs; omission selects the authenticated active batch |
| `read_local_review_item(revision_id)` | Returns one exact immutable local comment/thread revision |
| `reply_local_thread(thread_id, expected_revision_id, body)` | Appends a private local reply only if the revision Task read is still current |
| `resolve_local_thread(thread_id, expected_revision_id)` | Records Task's local resolution only if the revision Task read is still current; user can reopen/dismiss during the next gate review |
| `list_feedback_items(workset_id)` | Lists remote-feedback identities plus current immutable content-revision and observation IDs |
| `read_feedback_item(content_revision_id)` | Returns one exact semantic content revision plus its current derived observation |
| `read_feedback_context(item_id, before?, after?)` | Reads bounded thread history |
| `draft_feedback_reply(content_revision_id, body)` | Stores a private draft bound programmatically to the current observation; never posts to GitHub |
| `propose_thread_resolution(content_revision_id, rationale?)` | Stores intent bound to the current observation for the next exact user gate; never resolves remotely |
| `propose_keep_open(content_revision_id, rationale)` | Makes a deliberate non-resolution proposal visible to the user |
| `ready_for_review()` | Zero-argument terminal declaration. Currently bound only to the CI-fix `AGENT_RESULT_READY` continuation: success freezes program-derived exact evidence, revokes tools, and lets the stopped finalizer open/revise a local `CI_UPDATE` gate. Rejection leaves tools live. It never authorizes an effect. |
| `request_user_input(question)` | Normative deferred tool. It is not present in any current manifest; `TaskQuestions` has no production owner/bean in this checkpoint. |

There is no timeline, push, mark-ready, GitHub-comment, GitHub-resolve, merge,
Task-complete, arbitrary subagent, or direct CI-Fixer spawn tool.

Current implementation note: `run_checks` is exposed only by the specialized
`CI_FIX_READY`/changed-reviewer-result Task wrapper. Ordinary INITIAL instead
runs its program-selected check profiles inside the sealed reviewer-request
command. Its private finalizer supports exact STOPPED recovery and converts a
review-result continuation with no accepted terminal command into typed
`MISSING_INITIAL_TERMINAL_REQUEST` attention. Upstream, feedback, local-review,
and Task-question wrappers remain deferred.

When the Task has an authenticated `UpstreamSyncRun`, runtime adds only the bounded
`UPSTREAM_SYNC` capabilities defined by [Upstream Sync](./upstream-sync.md):
`read_upstream_pick`, `read_upstream_diff`, `read_conflict_file`,
`continue_upstream_pick`, `commit_upstream_fixup`,
and `commit_upstream_standalone`. Those tools make semantic
conflict/history choices explicit while the program owns Git safety. In this mode,
ordinary direct commit/rebase/cherry-pick capabilities are removed; source editing
remains available, and only the terminal specialized tools may request history
mutation. These tools are absent from ordinary development runs.

### Task prompt/runtime policy

The Task Agent's standing instruction is short and explicit:

- work until the confirmed goal is reviewable or a material user decision is needed;
- read current durable state after every wake;
- never assume a queued event still applies to a newer head;
- before `ready_for_review()` for changed code, commit, capture checks, and request a
  fresh exact-head adversarial review;
- interpret reviewer prose yourself; the runtime will not interpret it;
- never perform a remote effect;
- release/park at a user gate or when waiting for an external event.

### Program APIs

| API | Responsibility |
|---|---|
| `AgentSessions.createIdle(TASK_AGENT, sessionManifest)` | After worktree provisioning, create the Task's one persistent `IDLE` session with no `AgentRun` or model call |
| `WorkflowCommands.enqueueTurn(taskId, kind, subjectManifest)` | Persist one pending work fact and ensure reconciliation without interrupting a running turn or directly creating a competing writer |
| `WorkSelector.selectNext(taskId, throughWorkWatermark)` | Select at most one current mutation owner from the reconciliation pass's frozen watermark after typed-fact/staleness checks |
| `AgentSessions.resume(sessionId, operationId, claimToken, inputRef)` | Atomically reserve the idle persistent session for the current claim with one stored bounded envelope; operation redelivery reuses its run |
| `AgentRuns.finish(runId, claimToken, terminalOutcome, processMetadata, writerFence)` | Validate the current process generation/fence and idempotently persist the one completed/failed/canceled result plus operation-kind terminal facts before releasing mutation/reconciliation |
| `AgentSessions.close(sessionId, reason)` | Close only from a program-observed Task terminal state |
| `TaskStateReader.read(taskId)` | Assemble current objective state from owner records |
| `WriterLeases.acquire(taskId, operationId, holderKind)` | Through canonical `MutationAdmission`, enforce one eligible Task Agent, CI Fixer, or `UPSTREAM_SYNC` program writer and issue a fencing token |
| `LocalChecks.runAndRecord(preparedBatch, operationId, fence)` | Execute one frozen program-owned foreground profile batch inside the current writer turn and bind immutable exact-head attempts; the no-arg agent tool runs the required profile set without a nested operation/session/lease |
| `PrRecords.materialize(taskId, expectedHead)` | Idempotently create the Task's one Local PR after a clean committed diff exists |
| `PrRecords.saveDraft(prId, expectedRevision, title, body, actor)` | Before remote identity exists, append the exact local title/body revision; reject after publication |
| `TaskQuestions.ask(taskId, runId, question)` | Store a Task-scoped question, seal measured Git/sequencer state, install the waiting barrier, and seal the run; `AgentRuns.finish` alone persists its result, releases its fence, leaves the session `IDLE`, and exposes the question |
| `TaskQuestions.answer(userId, questionId, body)` | After the predecessor result is durable, store the exact answer and atomically create/reserve the one `USER_ANSWER` successor bound to that sealed state without changing launch goal |
| `GateSubjects.buildCurrent(taskId)` | Build the exact gate subject from current owner records after the run is sealed |
| `GateReadiness.evaluate(subject)` | Return objective blockers without reading agent prose |
| `GateCommands.openOrRevise(subject, actionManifest, evidence)` | Open/revise the appropriate exact user gate |

`TaskQuestions.ask/answer` in this table are normative deferred contracts, not
current production commands. No current prompt or tool manifest contains
`request_user_input`.

## 8. Contracts with other components

### [Trunk Agent](./trunk-agent.md)

- Supplies one exact confirmed goal through the durable Task-start command.
- Does not relay later events or stay alive as a parent.
- Task asks the user directly if implementation uncovers a material ambiguity.

### [Project Intelligence](./project-intelligence.md)

- Task queries it on demand and verifies relevant citations against the checkout.
- Knowledge is guidance, never permission, completion evidence, or a gate.

### [Adversarial Reviewer](./adversarial-reviewer.md)

- Task may spawn only this child role.
- Runtime binds exact head/diff/evidence and auto-persists the result before resuming
  Task.
- Runtime also supplies resolved Task-scoped question/answer records created after
  launch; it does not replay or semantically filter the Trunk transcript.
- Task interprets ordinary prose; the program only verifies run completion and head.

### [CI Fixer](./ci-autofix.md)

- Runtime, not Task, starts/resumes CI Fixer on a current final red observation.
- Both share one Task worktree under mutually exclusive writer leases.
- CI Fixer commits but never pushes. Task inspects its candidate before any gate.

### [Remote Feedback](./remote-feedback.md)

- Remote observer persists full thread/item revisions and wakes the existing Task
  session.
- Task stores drafts and proposed resolutions locally.
- Only an exact user authorization lets the program publish them.

### [Optional Upstream Sync](./upstream-sync.md)

- A separately confirmed upstream preview creates a normal Task plus one
  `UpstreamSyncRun`; that component may produce the candidate in the same Task
  worktree under the same exclusive writer lease before initial publication.
- Task then inspects the resulting exact head and uses the normal checks,
  adversarial-review, local-review, and publication contracts.
- Upstream Sync receives no separate worktree or publication authority and never runs
  concurrently with Task or CI Fixer mutation.

### [PR Timeline](./pr-timeline.md)

- Task never records events. The timeline projects committed facts from Task runs,
  commits, checks, reviews, feedback, gates, effects, and remote observations.
- Live agent narration/tool activity may be displayed separately and is not durable
  PR history.

### [User Gates](./user-gates.md) and [GitHub Integration](./github-integration.md)

- User Gates owns private local-review thread/revision records and backs the Task's
  `list/read/reply/resolve_local_thread` tools; Remote Feedback owns only GitHub
  feedback records.
- `ready_for_review()` requests a gate; it does not authorize an effect.
- The implemented CI binding references one User Gates-owned deterministic
  complete-empty local-review fact for the exact PR/change set and freezes an
  empty CI-memory reference list because no memory owner exists yet. Private
  local-review comments/threads and upstream/feedback/local-review Task bindings
  remain deferred rather than consulting legacy state.
- The implemented ordinary INITIAL binding freezes only `KEEP_DRAFT`. Its exact
  stopped reviewer-result finalizer opens the manual gate; `MARK_READY_ON_EXACT_GREEN`
  and partial-publication recovery gates remain deferred.
- Any new head, check/review revision, PR draft, feedback revision, or applicable
  policy change stales the current gate mechanically.
- GitHub effects execute only from a fresh user/standing authorization and produce
  program-owned receipts.

## 9. Invariants

1. One Task has exactly one persistent Task-Agent session, branch, and worktree.
2. At most one Task Agent, CI Fixer, or bounded Upstream-Sync program operation holds
   the Task writer lease.
3. The adversarial reviewer never holds that lease and cannot write.
4. Task reads durable state after every wake; wake prose is never authoritative.
5. A completed agent turn cannot change lifecycle state through its final prose.
6. A changed-code review candidate references a committed exact head, check evidence,
   and completed adversarial review for that head.
7. `ready_for_review()` never approves, pushes, replies, resolves, or merges.
8. Task cannot call GitHub mutation tools, including mark-ready.
9. After a successful terminal tool call, further worktree mutation by that run is
   denied.
10. Events that arrive during a run are queued, not injected into the active context.
11. A result against an old head remains auditable but cannot satisfy current
    readiness.
12. Task completion requires program-observed terminal state, never an agent claim.

## 10. Failure and recovery

| Failure | Required behavior |
|---|---|
| Task process dies with dirty worktree | After death is proven, release lease; persist `RECOVERY` work with measured status; the selector resumes a new run without destructive reset |
| Process dies after commit but before completion | Measure current head, retain commit, and resume from durable state; do not repeat edits blindly |
| Reviewer fails | Store failed opaque result; Task retries a fresh reviewer before readiness |
| Reviewer completes for stale head | Keep result for audit; `ready_for_review` rejects it and requires a new exact-head run |
| Malformed agent tool call | Return immediate validation error to the active agent; final prose is not a fallback payload |
| Local review is submitted while Task runs | Store immutable revisions/batch, transition the reviewed gate to `CHANGES_REQUESTED`, ensure reconciliation, and do not interrupt |
| CI failure arrives while Task runs | Store observation; reassess after Task releases lease and a current remote head is known |
| Remote head changes outside ByteQuay | Stale pending gate; refetch feedback/check state before preparing another candidate |
| Program restarts while parked | Recreate no new semantic session; resume the existing session from durable run/event refs |
| Cancellation | Stop dispatch, cancel running process safely, retain durable PR/timeline history, then release worktree per policy |

## 11. Acceptance traces

### 0. Provisioning does not start the agent

1. Provision one Task worktree.
2. Assert one persistent `IDLE` Task-Agent session and zero `AgentRun` rows.
3. Let `WorkSelector` select the first `RUN_TASK_TURN` operation.
4. Assert `AgentSessions.resume(...)` creates exactly one run in the existing
   session.

### A. First publication with a local review change

1. Task reads goal/repository and commits H1.
2. `run_checks()` records green evidence for H1.
3. Fresh reviewer result R1 is auto-persisted for H1 and delivered to Task.
4. Task calls `ready_for_review`; gate G1 opens for H1.
5. User submits local comment C1. G1 becomes `CHANGES_REQUESTED`; Task is resumed.
6. Task fixes C1, commits H2, captures checks and obtains R2.
7. New gate G2 opens for H2. User authorizes G2 with `KEEP_DRAFT`.
8. Program publishes H2 as a draft; Task never pushes or marks ready.

### B. Reviewer writes nuanced prose

1. Reviewer says the change is generally good but calls out one risky edge case.
2. Runtime stores the text without looking for a keyword or findings array.
3. Task reads it, decides the edge case is actionable, fixes it, and requests another
   review.
4. The next gate shows both review records and the current exact-head result.

### C. CI and remote comments arrive concurrently

1. CI Fixer holds the writer lease for remote head H3.
2. GitHub observer records feedback batch F1; runtime queues it without interruption.
3. CI Fixer commits H4 and parks.
4. Task wakes with CI result and F1, re-reads current state, and addresses feedback on
   H4.
5. It commits H5, checks/reviews H5, and opens one exact gate. No stale concurrent
   patch is applied.

### D. Feedback work makes old CI failure irrelevant

1. Task already holds the lease addressing feedback on H3.
2. Red CI observation for H3 is stored.
3. Task commits H4 and parks at a review gate.
4. Runtime does not start CI Fixer for H3. GitHub observation of H4 determines the
   next CI action.

### E. Crash after local commit

1. Task commits H2, then its process dies before final prose.
2. Runtime records failed run and measured H2.
3. Recovery run calls `read_task_state`, inspects H2 and worktree status, and
   continues with checks/review.
4. No parser or duplicate patch reconstruction is involved.

## 12. Evidence: accept, merge, reject

| Source | Decision | Reason |
|---|---|---|
| [Codex code review](https://learn.chatgpt.com/docs/code-review) | **Accept** a dedicated reviewer that reads an exact selected diff and does not modify the worktree | Independent review should challenge, not co-author, the candidate |
| [Codex subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents) | **Accept** narrow roles and the warning about concurrent write-heavy work | It supports one primary writer plus a read-only reviewer rather than many coding children |
| [Codex subagent spawn implementation](https://github.com/openai/codex/blob/3aae5d885bac39c1262491aa3fd100dfd8b3919f/codex-rs/core/src/tools/handlers/multi_agents/spawn.rs#L124-L224) and [completion watcher](https://github.com/openai/codex/blob/3aae5d885bac39c1262491aa3fd100dfd8b3919f/codex-rs/core/src/agent/control.rs#L479-L560) | **Merge** child IDs and automatic result delivery with durable result storage before parent resume | Task should receive an opaque result handle, not depend on child final-message formatting or live interruption |
| [Grok subagents](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/16-subagents.md) | **Accept** resumable children and guidance against delegation when context-transfer cost is high | Development and feedback stay with the persistent Task Agent; CI and adversarial review are bounded specialties |
| Separate implementation owner for every phase | **Reject** | It adds context loss and artifact handoffs without creating independent authority |
| Program parsing final prose | **Reject** | Tool calls can be validated live; completion prose remains useful to the next agent/user without becoming a brittle control protocol |
| Concurrent Task/CI writes | **Reject** | Exact-head review and user authorization are meaningless if another writer changes the worktree concurrently |

## 13. Tradeoffs

- A persistent session retains context but can grow. Bounded wake envelopes,
  `read_task_state`, and durable result handles limit repetition; session replacement
  may use those same facts if context becomes unhealthy.
- Serial writers reduce throughput on one Task but eliminate stale patches and races.
  Different Tasks remain parallel in separate worktrees.
- Re-review after every code-changing candidate costs time/tokens but preserves the
  independence and exact-head contract.
- User gates can pause progress. That is intentional at irreversible boundaries;
  reversible local edits continue autonomously.
- Task interpretation of reviewer prose is not mechanically uniform. The local user
  gate exposes the actual result and diff, which is a stronger safety boundary than a
  model-authored approval token.
