# Remote feedback

Status: **normative greenfield replacement specification**

Remote feedback is a Task Agent operating mode, not another agent role. The
program captures immutable GitHub feedback revisions; `WorkSelector` resumes the
persistent Task Agent with exact references, stores its private reply/resolution drafts
through tools, and requires the user to approve the exact batch before anything
is published. Pre-push Local PR review items belong to
[user-gates.md](./user-gates.md), not this component.

Read [README.md](./README.md) for system-wide ownership, then
[task-agent.md](./task-agent.md), [user-gates.md](./user-gates.md),
[github-integration.md](./github-integration.md), [ci-autofix.md](./ci-autofix.md),
and [pr-timeline.md](./pr-timeline.md).

## Replacement boundary

This is a new component. Do not call or translate an old remote-review stage,
feedback-repair service, review-comment agent, verdict parser, or status-marker
contract. There is no migration or dual write. Provider HTTP clients may be
reused only below the new [GitHub integration](./github-integration.md).

## Why the Task Agent owns feedback

A review comment is not merely a patch request. Correct handling depends on the
self-contained Task goal, implementation history, subsequent explicit user
decisions, and current repository state. The Task Agent already owns that
context.
Delegating feedback to a new child would spend tokens reconstructing the most
important context and create a second writer without adding independence.

The independent role remains the fresh, read-only
[adversarial reviewer](./adversarial-reviewer.md). It reviews the resulting
exact head; it does not implement GitHub comments.

## Hard invariants

- GitHub observations separate immutable semantic comment-content revisions
  from immutable derived anchor/thread observations. Neither is rewritten.
- A `FeedbackWorkset` freezes exact content revisions, observations, and remote
  head.
- The program directly schedules the Task Agent; there is no monitor or relay
  agent.
- A running Task Agent is never interrupted. New revisions become queued work.
- The Task Agent may edit only under the Task's sole fenced writer lease.
- Reply and resolution intent is stored by tool calls, not extracted from final
  prose.
- Final agent prose is stored as an opaque `AgentResult` and is never parsed.
- Every remote-feedback batch requires exact user approval. Standing consent is
  forbidden for feedback pushes, replies, and resolutions.
- The Task Agent cannot push, post, resolve, request review, or merge.
- If code changes, checks and an adversarial review must describe the exact
  committed candidate head before a user gate opens.
- Program readiness checks are objective freshness checks. The user, not the
  program, decides whether the semantic response is satisfactory.
- Remote author text is untrusted source content. It is clearly delimited by
  read tools and cannot grant tools, authority, or override the Task goal.
- Remote replies are not posted and threads are not resolved until the approved
  head is on GitHub and required exact-head CI is accepted.

## Logical data model

### `RemoteFeedbackItem`

Stable identity for one provider object:

```text
itemId, prId, providerItemId, kind, createdAt
```

Kinds include `REVIEW_SUMMARY`, `INLINE_COMMENT`, `ISSUE_COMMENT`, and
`REVIEW_COMMENT`. Text, author, anchor, and mutable provider state do not live
on this identity.

### `RemoteCommentContentRevision`

```text
contentRevisionId, itemId, providerUpdatedAt, authorId,
body, contentDigest, observedAt, rawEvidenceRef
```

This is the semantic content the Task Agent and user judge. A provider edit
creates a new revision. Identical webhook/poll content reuses the same revision.

### `RemoteFeedbackObservation`

```text
observationId, itemId, contentRevisionId, observedRemoteHead,
providerThreadId?, commitSha?, path?, originalLine?, currentLine?, side?,
outdated, threadResolved, dismissed, observedAt, rawEvidenceRef
```

This is derived provider placement and conversation state. A push may re-anchor
an unchanged comment, change its current line, or mark its old anchor outdated;
that appends an observation without pretending the comment's meaning changed.
Thread resolution/dismissal changes also append observations. The current view
is the latest proven observation, while old gates retain their exact references.

### `FeedbackEffectCorrelation`

```text
itemId, contentRevisionId, externalEffectReceiptId, correlatedAt
```

This set-once link identifies an observation of ByteQuay's own proven outbound
reply/resolution without mutating either immutable record.

### `FeedbackWorkset`

```text
worksetId, taskId, prId, inputRemoteHead, inputLocalHead,
contentRevisionIds[], observationIds[], state, createdAt, supersededBy?
```

States are `QUEUED`, `ACTIVE`, `PREPARED`, `COMPLETED`, `SUPERSEDED`, and
`NEEDS_ATTENTION`. User waiting, authorization, and effect execution are derived
from linked gate/effect owner records; they are not copied onto the workset.
This is lifecycle bookkeeping, not a semantic verdict.

### `FeedbackDraftAction`

An immutable local revision created only through a tool call:

```text
draftActionId, worksetId, contentRevisionId, observationId, kind,
body?, rationale?, supersedesDraftActionId?,
createdByAgentRunId, createdAt
```

Kinds are:

- `REPLY` — local reply text;
- `PROPOSE_RESOLUTION` — ask the user to resolve the referenced thread after
  remote proof;
- `KEEP_OPEN` — explicit proposal to leave the thread open.

`KEEP_OPEN` is not an assertion that a finding is irrelevant. It makes the
agent's proposed action visible for user judgment.
`request_user_input` creates a Task-owned question/answer record and parks the
workset; it is not a feedback action and no program code infers a question from
draft prose.

### Gate binding

The `REMOTE_FEEDBACK` gate freezes:

```text
worksetId, inputRemoteHead, outputLocalHead,
contentRevisionIds[], observationIds[], latestDraftActionIds[],
prDraftRevision?, checkRunIds[], adversarialReviewRunId?,
effectActionDigest, policyRevision
```

The gate and authorization are owned by [user-gates.md](./user-gates.md).

## Observation and workset creation

The program command is:

```text
RemoteFeedback.observe(prId, normalizedProviderItem) -> ObservationResult
```

It inserts/reuses the item and content revision, then appends/reuses the exact
derived observation idempotently. New actionable content or a newly actionable
thread observation causes:

```text
RemoteFeedback.enqueueCurrent(prId) -> worksetId
```

`enqueueCurrent` coalesces all currently unhandled content/observation pairs
into one workset.
It does not wake an agent directly. The workset is the pending domain fact; the
owner creates/reuses one deduplicated reconciliation `Operation`/`DispatchTicket`
in [workflow-runtime.md](./workflow-runtime.md). There is no separate
mutation-intent record. The runtime chooses the next owner after checking
admission, current heads, and pending finalized red CI.

Dismissed, resolved, or outdated items remain in history but are not silently
treated as new actionable work. New content or a later thread observation can
reopen work.
An observation correlated to ByteQuay's own proven reply/resolution receipt is
retained as remote truth but does not create a new feedback workset. Correlation
uses the provider object ID from the receipt, not author-name guessing.
If an observation races ahead of its receipt, work eligibility waits for the
in-flight effect probe; the content revision is either correlated after proof or queued
normally after the operation is disproven/closed.

## Agent launch contract

When the workset becomes current, the runtime resumes the existing Task Agent
with a `FeedbackLaunch`:

```text
FeedbackLaunch
  taskId, prId, worksetId
  taskGoal
  inputRemoteHead, inputLocalHead, currentLocalHead
  contentRevisionRefs[], observationRefs[]
  currentPrDraftRef
  pendingCiFacts[]
  toolPolicy = TASK_FEEDBACK
```

The launch contains references and a short factual summary. It does not copy a
large thread transcript into a prompt and does not tell the agent which comments
are valid. The Task Agent calls tools to read the exact records it needs.
`taskGoal` is the self-contained goal stored by `start_task`; the program does
not interpret a Trunk transcript or inject a hidden Project Intelligence
selection. The Task Agent queries Project Intelligence itself when useful.
Every remote body is labeled with provider identity/revision and delivered as
untrusted quoted data, never concatenated into runtime instructions.

If the local head has changed since workset creation, the runtime carries each
still-open feedback identity forward and binds the launch to the new local head.
It never pretends that an old remote-CI result proves the new local head.

## Task Agent tools

```text
list_feedback_items(workset_id) -> item, content-revision, and observation IDs
read_feedback_item(content_revision_id) -> exact content plus current observation
read_feedback_context(item_id, before?, after?) -> bounded thread history
draft_feedback_reply(content_revision_id, body) -> draftActionId
propose_thread_resolution(content_revision_id, rationale?) -> draftActionId
propose_keep_open(content_revision_id, rationale) -> draftActionId
request_user_input(question) -> user request
run_checks(profile?) -> LocalCheckRunRef[]
spawn_agent(role="adversarial_reviewer") -> reviewRequestId
Task finishes; program preflight proves the exact candidate
```

Every command resolves `taskId`, `prId`, agent run, and current head from the
authenticated session. The agent does not provide those security-sensitive IDs.
The runtime also binds the writer fencing token below the tool layer; it is not
model-visible input.
Tool schemas reject stale/foreign revision IDs while the agent is still active,
so it can reread and retry. This is not final-response JSON parsing.

Draft tools bind the current observation below the model-facing schema and
append action revisions; changing a reply creates a new `FeedbackDraftAction`.
The gate always binds the latest selected action plus exact content and
observation revisions. `ACCEPTED_SEALED` means the candidate is sealed against
further model mutation; it is not a gate ID or publication authority.

## End-to-end lifecycle

### 1. Detect and queue

1. [GitHub integration](./github-integration.md) observes a review/comment or
   thread-state change and refetches enough provider state to normalize it.
2. `RemoteFeedback.observe` stores/reuses the content revision and derived
   observation.
3. `enqueueCurrent` creates/coalesces a workset and its deduplicated runtime
   reconciliation operation/ticket.
4. If a writer is active, the intent waits. The agent is not interrupted.
5. If final red CI for the same current remote head is waiting, CI repair goes
   first. Feedback identities stay queued and are rebound afterward.

### 2. Understand and prepare

1. The runtime claims the Task writer lease and resumes the Task Agent.
2. The agent reads the full current thread revisions, repository code, goal, and
   any relevant CI facts.
3. It judges each comment itself. It may change code, write a reply draft,
   propose resolution/keep-open, or ask the user.
4. Code changes are committed. `run_checks()` stores exact-head evidence.
5. For a changed head, the agent spawns a fresh exact-head adversarial reviewer,
   reads the stored result, and fixes anything it judges actionable.
6. The agent finishes. The runtime derives the current workset, Task, and head
   from the authenticated session, proves the report/Git preconditions, seals the candidate, and
   returns `ACCEPTED_SEALED`.

The program does not search the agent's prose for “addressed,” “approved,” or a
count of findings.

### 3. Build the user gate

After the sealed agent result, head evidence, and lease release are durable, the
program checks only facts it owns:

- the workset and referenced content/observation revisions still exist;
- the worktree is clean and its local head is committed;
- the candidate head still matches the tool caller's current head;
- locally executed checks and reviewer run, when required, bind that head;
- every selected draft action references a workset content/observation pair;
- no conflicting writer/effect owns the Task; and
- the PR is still open.

It then opens a `REMOTE_FEEDBACK` gate. The UI shows:

- original current comments and thread history;
- current remote head and candidate local head;
- exact diff;
- check and adversarial-review evidence;
- every reply draft, resolution proposal, and keep-open proposal; and
- any feedback item with no proposed action.

An item without a draft does not cause the program to invent a failure. The user
can request changes, reply locally, or approve the visible batch deliberately.

### 4. User requests changes

1. The user requests changes and records a local review instruction against the
   exact gate; the open gate becomes `CHANGES_REQUESTED`.
2. That immutable instruction/batch is persisted; the UI does not edit the
   agent-authored `FeedbackDraftAction` directly.
3. The same Task Agent resumes with the instruction and current feedback refs,
   then creates any superseding draft action through its normal tool.
4. After changes, fresh checks/review and a new gate revision are required.

### 5. User approves

`GateCommands.authorize(...)`, using the canonical exact-subject/action-digest
contract in [user-gates.md](./user-gates.md), atomically freezes one
authorization and ordered effect plan and creates its runtime operation/ticket.
[GitHub integration](./github-integration.md) executes:

1. push exact approved head if it differs from remote;
2. confirm remote head;
3. wait for required exact-head CI to be accepted;
4. refetch every referenced item's content and thread/anchor observation;
5. stop on a semantic content edit or an unexpected conversation transition;
6. post approved reply drafts;
7. refetch affected threads;
8. resolve only approved, unchanged, still-eligible threads; and
9. persist provider receipts.

Only provider observations mark the workset `COMPLETED`.

An anchor-only transition caused by the authorized push is expected, not a
semantic edit. It may continue when the provider item and thread identities are
unchanged, the content revision is unchanged, the new observation is proven on
the exact authorized remote head, and the approved action remains eligible.
Resolution/dismissal/reopen or thread-identity changes are re-evaluated: an
already-satisfied approved effect may be receipted, otherwise the operation
stales. The executor never compares comment bodies heuristically.

### 6. New feedback during execution

- If it changes referenced semantic content before its effect, remaining steps
  become stale. A proven anchor-only transition from the authorized push follows
  the rule above.
- If it is a new unrelated item, it creates the next workset. It does not
  interrupt the current operation.
- If the approved push has already happened but CI fails, no reply or resolution
  is posted. The gate revision becomes `STALE`; the runtime operation becomes
  `CANCELED` with a typed partial-effect result that references the retained push
  receipt, cancels unexecuted conversation steps, and releases the publication
  barrier. [ci-autofix.md](./ci-autofix.md) then repairs CI. The Task Agent must
  reread current feedback, prepare a current batch, and obtain a new manual
  `REMOTE_FEEDBACK` gate; old approval never follows the repair head.
- Already-posted, provider-proven replies remain facts; they are never rolled
  back or duplicated.

## Concurrent CI and feedback

The worktree rule is simple: one queue, one writer, no stale patch merge.

| Situation | Behavior |
|---|---|
| Red CI and feedback are both pending before any writer starts | CI Fixer gets the lease; Task Agent then receives current feedback against the resulting local head. |
| Task Agent already owns the writer when red CI arrives | Record CI without interruption. After the turn, re-evaluate heads. If unpublished Task changes supersede the failed remote head, resume the Task Agent with that CI evidence before opening a gate; never let a CI Fixer treat old-head failure as proof about the new head. |
| CI Fixer owns the writer when feedback arrives | Queue feedback; resume Task Agent after the fix result is stored and the lease is transferred. |
| New thread arrives while a feedback gate is open | Create next workset. Referenced-item edits stale this gate; unrelated new items block merge but do not mutate the approved batch. |

The program may coalesce the stored CI-fix result and pending feedback into one
Task Agent turn and one eventual user gate. It never runs two writers and never
asks either agent to merge the other's live patch.

## Recovery

- **Duplicate provider event:** reuse the existing content revision and
  observation; create no second workset item.
- **Agent crash before a draft tool call:** keep the workset queued and link a
  replacement `AgentRun`; infer nothing from partial prose.
- **Agent crash after draft calls:** drafts remain durable. A replacement Task
  turn can inspect them and continue.
- **Process crash after user approval:** authorization and operation are already
  durable. Effect recovery probes GitHub before retry.
- **Outbound echo arrives before its receipt:** retain the content/observation without a
  Task wake, reconcile the in-flight effect, then either attach the set-once
  correlation or enqueue it as ordinary feedback.
- **Push succeeded, CI red:** transition the gate to `STALE`, complete the old
  runtime operation as `CANCELED` with its retained push receipt, cancel
  conversation effects, release its barrier, and route
  exact-head CI repair. Any later feedback publication needs a new manual gate.
- **Thread edited while waiting for CI:** stop remaining effects and require a
  new gate using the new revision.
- **Comment deleted/dismissed externally:** preserve its revision history, mark
  its current provider state, and skip any no-longer-valid effect.
- **PR closed:** cancel unexecuted worksets/effects. Preserve all local drafts and
  history.

## Timeline projection

The durable PR timeline projects:

- receipt of a new remote content revision or meaningful thread observation;
- start/completion/failure of its Task Agent run;
- preparation and authorization of a feedback batch;
- provider-proven push, reply, and resolution effects; and
- staleness/supersession when it changes user-visible flow.

It does not copy every tool call, retry, raw webhook, or the complete agent
transcript. Full details remain linked execution/evidence records. Agents never
write timeline events. See [pr-timeline.md](./pr-timeline.md).

## Required acceptance traces

1. **Reply-only:** current comment -> Task Agent draft -> user approval -> no
   code push -> refetch -> reply -> receipt.
2. **Code fix:** comment -> commit -> checks -> adversarial review -> user gate ->
   push -> exact-head green -> reply -> resolve.
3. **Reviewer prose is informal:** “Looks good except perhaps X” is stored and
   shown; no program parser decides whether findings exist.
4. **Malformed tool input:** a stale revision ID returns an immediate tool error;
   the agent rereads and succeeds without ending its run.
5. **User requests changes:** the old gate stales; Task Agent fixes; only a new
   exact-head/revision gate can authorize effects.
6. **Edited comment:** an edit during the CI wait prevents reply/resolution from
   using the old body.
7. **CI and feedback together:** CI Fixer and Task Agent run serially; the Task
   Agent sees the stored CI result and current comments; only one combined local
   head is reviewable.
8. **Crash after reply:** recovery observes the existing provider reply and does
   not duplicate it.
9. **Unrelated new thread:** it becomes the next workset and blocks merge without
   corrupting already-authorized item revisions.
10. **Privacy:** draft replies and unrelated private Local PR content never
    reach GitHub before the feedback gate is authorized.
11. **Outbound echo:** observing ByteQuay's provider-proven reply/resolution
    records remote truth but does not wake the Task Agent with its own effect.
12. **Expected re-anchor:** an authorized push changes only the current line or
    outdated/anchor observation while semantic content stays identical; the
    executor validates the exact pushed head and continues without a false stale.
13. **Red after approved push:** the push receipt survives, the old feedback
    operation terminates and releases its barrier, CI repair runs, and replies
    remain blocked until a newly prepared batch receives a new manual gate.

## First-principles challenge

| Question | Decision | Tradeoff |
|---|---|---|
| Does feedback need a dedicated agent? | No. It is tightly coupled to goal and implementation history; use the Task Agent. | Task context grows, so launches carry references and agents read on demand. |
| Can the program classify comments as actionable? | No. That is semantic work. Show the Task Agent's proposed action to the user. | User may see an intentionally unaddressed item. |
| Is strict agent JSON needed to build the batch? | No. Small typed tool calls persist one draft action at a time. | More tool calls, but errors are local and retryable. |
| Can a generic `passed` field drive publication? | No. Exact heads, revisions, checks, result references, and user approval are the real contract. | Program cannot certify semantic correctness—and should not pretend to. |
| Can replies be posted immediately after a local fix? | No. The public claim should follow remote-head and CI proof. | Conversation is slower. |
| Can feedback use standing consent? | No. Human-facing text and thread closure are context-sensitive irreversible effects. | Every batch waits for the user. |
| Should a new unrelated comment cancel an already-approved batch? | No. Preserve referenced-item exactness and queue the new item. | The user may complete two gates instead of one. |

## Evidence and adopted/rejected ideas

- **Accept Codex review separation:** Codex's dedicated reviewer inspects a
  selected diff and reports findings without changing the worktree. ByteQuay
  uses that fresh role after feedback implementation, while the Task Agent keeps
  write ownership.
  [Codex code review](https://learn.chatgpt.com/docs/code-review)
- **Accept Codex result delivery, reject final-text protocols:** Codex exposes
  explicit spawn/wait lifecycle operations and completed child output. ByteQuay
  persists the child result before resuming the Task Agent, but never parses it
  as a verdict.
  [Codex spawn implementation](https://github.com/openai/codex/blob/3aae5d885bac39c1262491aa3fd100dfd8b3919f/codex-rs/core/src/tools/handlers/multi_agents/spawn.rs#L124-L224)
- **Accept Grok's warning against costly delegation:** Grok recommends avoiding
  subagents for work requiring tight user back-and-forth or where context setup
  exceeds the benefit. Remote feedback has both properties, so it stays with the
  Task Agent.
  [Grok subagents: when not to use them](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/16-subagents.md#when-to-use-subagents)
- **Merge Grok's explicit user choices with ByteQuay's exact gate:** Grok Plan
  Mode exposes approve, request-changes, comment, and quit actions. ByteQuay uses
  equivalent user choices but freezes exact code/comment/effect revisions.
  [Grok Plan Mode](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/19-plan-mode.md)
- **Reject Grok persona output files as workflow authority:** declared outputs
  are useful for optional artifacts, but publication cannot depend on an agent
  remembering a required file/schema. Draft tools persist each intended effect
  while the agent is active.
  [Grok input/output contracts](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/16-subagents.md#inputoutput-contracts)

## Implementation completion rule

This component is complete only when all thirteen traces pass with arbitrary natural
language agent results. A test suite that works only when a model prints a
particular heading, JSON key, marker, or verdict has reproduced the old failure
mode and does not satisfy this specification.
