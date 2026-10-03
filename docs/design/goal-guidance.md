# Agent guided goals

Status: Work-item foundation implemented; agent guidance remains planned. This document specifies
the requested Goal conversation, discovery, guidance and durable progress panel,
and the three lessons from the Plane comparison. The
[implementation plan](goal-guidance-impl-tmp.md) lists actionable changes and
acceptance checks.

The user describes an outcome to an agent. ByteQuay helps develop a path, find
suitable issues or tickets, choose the next piece of work, execute accepted work
through the existing task service, and remember the results. Opening the Goal
later should answer: where are we going, what should I do next, what is blocked,
and what have we actually achieved?

This proposal extends the implemented [issue development release](issue-development-plan.md)
and refines the later [work planning roadmap](issue-development.md). It does not
claim that the roadmap is already implemented. The [agent service](agent-service.md),
[development delivery](development-delivery.md), [Jira integration](jira-integration.md)
and [repository memory](repository-memory.md) retain their execution, publication,
source and memory boundaries.

## Current implementation and the missing behavior

Goals now group durable work items. Each item has an identity, kind, acceptance
criteria, completion rule, revision, supporting links and retained decisions.
The panel provides creation, scope editing, linking, explicit result acceptance,
dropping, reopening and moving between Goals. Unmarking a source keeps its Goal
while the Goal holds work and archives it otherwise. The Goal remembers its
source, so marking the source again restores the kept Goal rather than starting
a second one. Archiving a Goal retains its work, which remains accessible
through saved work.

One item counts once regardless of its issue, Jira, task or PR links. Failed and
completed tasks are ended activity, unmerged PR closure is not delivery, and
missing cache entries are unknown. Done currently requires a user acceptance with
recorded evidence and scope coverage; merged-delivery acceptance also requires a
linked PR observed merged and every other linked PR merged or closed. A PR closed
without merging is recorded as an abandoned attempt; it neither counts as
delivery nor blocks the PR that replaced it. There is no automatic scope
certification from a merged PR.

GitHub and Jira task admission now reserve work-item source/task ownership in the
same transaction as task creation. Canonical identities use GitHub host/issue ID
or Jira cloud/issue ID, independently of Jira connection credentials. Accepted
source snapshots are retained with the work and its newly admitted attempt.
Admission refuses a source whose work item is Done, Dropped or in another
workspace before preparing a worktree, and a Jira ticket cannot be reassigned
away from its work item's workspace.
The existing source services still reopen the current task by default. A new
explicit same-source attempt without deleting the prior task, proposal acceptance,
work-item launch from the composer, automatic outcome harvesting, Goal conversation
and discovery remain pending. The checklist distinguishes these from implemented
local planning behavior.

The checked development database contained no Goals or issue/Jira task bindings.
The final Goal mapping uses `goal`; work items and identity reservations use
`work_item` and `work_item_identity`. No runtime data was reset, copied or deleted.
The superseded empty `workspace_goal` table is not read or rewritten. This is an
unreleased schema redesign, not a released-data migration contract.

Relevant implementation entry points:

- [Goal service](../../backend/app/src/main/java/com/bytequay/app/service/goal/GoalServiceImpl.java)
  groups accepted work. The [work-item service](../../backend/app/src/main/java/com/bytequay/app/service/goal/WorkItemService.java)
  owns decisions and identity; the [reference reader](../../backend/app/src/main/java/com/bytequay/app/service/goal/GoalReferenceReader.java)
  reads supporting activity without remote requests.
- [Goal panel](../../frontend/src/goals/GoalPanel.tsx),
  [summary model](../../frontend/src/goals/model.ts) and
  [Goal loading](../../frontend/src/goals/useGoals.ts) implement the current UI.
- [GitHub admission](../../backend/app/src/main/java/com/bytequay/app/agent/IssueDevelopmentService.java)
  and [Jira admission](../../backend/app/src/main/java/com/bytequay/app/agent/JiraDevelopmentService.java)
  preserve source snapshots and admit ordinary execution tasks.
- [Task naming](../../backend/agent-core/src/main/java/com/bytequay/agent/core/impl/DefaultAgentTaskTitleService.java)
  demonstrates a bounded tool-free provider session without a task row or Git worktree.

## Product flow

1. **Help the user find the goal.** Keep the floating Goal control and its resizable zoom window. New goal immediately offers a useful opening
   question and two or three concrete directions grounded in the selected
   workspace, alongside an optional free-text answer. A vague existing title
   such as “Iceberg connector expertise” needs the same help: understand the
   connector, review changes, or deliver a small fix imply different paths.
   The user need not know a ticket, write a prompt, name the Goal or fill a form.
   A locally rendered opening question needs no model request on panel read;
   answering it starts guidance directly without a separate Plan with agent gate.
2. **Clarify and agree on the path.** Use known objective, constraints, accepted
   work and saved results before asking anything. Ask one consequential question
   at a time, offer short suggested answers, and retain free text for correction.
   Suggest a success definition, editable title and small initial path. State
   assumptions about unknown experience or time; do not invent user preferences
   or require a questionnaire before offering help. The agent prepares the next
   question or proposal; the user makes the substantive decision.
3. **Discover and propose work.** Read within the selected workspace and its
   connected, mapped Jira scope. Propose learning, investigation or development
   items, including local items with no external ticket. A recommendation names
   the reason, evidence, uncertainty, acceptance criteria and next action.
4. **Prepare the next action.** Accept plan saves the selected work items and
   their order. It allocates no execution task or worktree. The agent immediately
   identifies the next eligible item, explains why it comes first and supplies
   its intended outcome, source context and execution request. Start opens the
   ordinary composer with these fields and existing provider/automation defaults
   already filled in. The user can review and submit without composing another
   prompt or manually finding the source. Learning that needs no repository task
   stays in the Goal conversation.
5. **Guide the work.** Open task, View changes, Review result and Answer question
   lead to existing surfaces. Goal chat explains decisions and can propose a
   revised next step. An instruction intended to change an active task is handed
   to that task's existing composer or steering action, not injected silently.
6. **Record, assess and rearrange recommendations.** Task results, observed PR
   changes, blockers and user decisions update the factual record. The agent
   explains where the Goal stands, what changed and what should happen next
   without waiting for “what next?” or a manual discovery request. It can select
   another ready item within accepted constraints and keep its suggestion current.
   A proposed change to accepted scope, criteria, priority, order or dependencies
   shows the specific change and its reason for the user to accept or adjust.
   An unchanged plan needs no repeated approval. The panel retains both current
   guidance and the decisions and outcomes that explain it.

Example: “Help me make my first Iceberg contribution; I have a few hours each
week.” The initial path might be learn the contribution process, investigate a
verified candidate, implement its bounded fix, and address review feedback.
These are illustrative steps, not invented live tickets. The discovery pass must
return real source references before presenting any particular issue as suitable.

For an objective such as becoming a committer, the panel shows progress on agreed
work, not a percentage likelihood of becoming a committer. External recognition
cannot be inferred from merged-PR counts. The user confirms such a Goal outcome.

## Goal panel and user decisions

Enhance the current Goal surface in place. Preserve its floating control,
pinning, Goal switcher, workspace navigation and expansion. Clicking the control
zooms a floating window out from it without narrowing the underlying page. The
header drags the window, its bottom-right corner resizes it, and expansion uses
the same panel. Leaving the control/window area closes it automatically, except
during a drag, resize or confirmation. Closing preserves drafts for reopening;
Escape and the close button also dismiss it. Keep the window inside the page
and respect reduced-motion preferences.
The default view is guidance, with Work and History as secondary inspection
views. A user should understand the current situation and useful next action
before opening a transcript, search box or board. Both Home surfaces open this
same Goal and the same current guidance.

Use this information hierarchy in both the floating and expanded view:

1. **Goal and current situation.** Show the title, a short agreed outcome and
   one plain-language status such as Clarifying your goal, Checking candidates,
   Working, Your decision needed, Waiting for review or Ready to conclude.
   These are projections of current facts and guidance, not new durable
   execution lifecycle states.
2. **One useful agent intervention.** Show the current clarification or the
   strongest next recommendation, with a short reason, relevant evidence and
   one primary action. A clarification offers concrete answers and optional
   text. Recommendations provide prepared actions such as Review proposed path,
   Investigate this candidate, Review result or Respond to feedback. Urgent
   approvals, failures and blockers take precedence over finding more tickets.
3. **The path in context.** Show the current milestone and the next few accepted
   steps in a compact ordered list. Make prerequisites and proposed changes
   visible. Expand to inspect all work; empty columns and zero-valued counters
   must not dominate an empty or uncertain Goal.
4. **What changed and what remains.** Show a short supported result and the
   latest meaningful event, with evidence and earlier decisions available on
   demand. Keep numerical progress subordinate to these explanations.

Use the app's existing typography, controls, neutral surfaces and blue actions.
Avoid three equal-weight statistics tiles above the guidance. Use meaningful
headings, readable body text, short paragraphs, restrained borders and room
around the primary decision. The floating window uses a single column; expansion gives
guidance and the path more room without showing two competing copies of the
Goal panel. Preserve the draft, selected item and guidance revision when changing
presentation. Render only one panel, and keep it out of keyboard navigation while closed or
covered by another layer. Manual creation, source search and board management remain
available under Work; they are not the opening task given to an unsure user.

The floating control may show one short meaningful cue, such as Goal · 1 decision
or Goal · Review ready, rather than a meaningless 0/0. It does not auto-open,
interrupt work, animate repeatedly or notify for unchanged guidance. Opening it
shows the saved current assessment immediately; panel reads stay local.

| Content | What it contains | User actions |
| --- | --- | --- |
| Objective | Outcome, constraints, accepted success definition | Edit, discuss, pause guidance |
| Path | Ordered accepted work items and explicit dependencies | Accept proposed changes, reorder, remove from scope |
| Current guidance | One question or recommendation with why, evidence and uncertainty | Answer, accept, adjust, skip, ask why |
| Work | Planned, Working and Done; blocked or waiting details | Start, open task, review result |
| Results | Outcome, supporting evidence and what remains | Accept a result, reopen, inspect PR or artifact |
| History and lessons | User decisions, verified events and attributed summaries | Read earlier decisions and evidence |

Show at most three recommendations at once. Each accepted work item has a stable
identity and an expandable set of related sources, execution attempts and results.
Adding a source or another attempt does not add a second progress card.
Dismissal is local to the proposal and relevant source revision; an unchanged
candidate must not immediately return. A user can explicitly ask to reconsider it.

Proposal text and agent summaries are visibly distinct from observed facts.
Show checked coverage and stale or unavailable evidence where it affects a
decision. Preserve the user's draft and the last good panel on errors. Use
keyboard-accessible controls and announce answering, saved and failed states.

## Guidance follows the work

The service prepares guidance from the latest accepted Goal, constraints, work
items, source observations and saved results. It is responsible for choosing the
next useful intervention; requiring the user to open Discover and invent a
search prompt or to open Working and ask for status does not satisfy this design.

| Situation | Agent responsibility | User decision |
| --- | --- | --- |
| No clear outcome or only a broad title | Ask the smallest useful question with concrete suggested answers | Choose or describe the intended outcome |
| Outcome clear, no accepted path | Draft milestones, completion criteria and a suitable starting point | Accept or refine the proposed path |
| Ready to find work | Discover within authorized scope and explain candidate fit and uncertainty | Choose a proposed candidate or direction |
| Accepted work is ready | Prepare its task request and explain why it comes next | Start with the existing execution controls |
| Work is running | Show observed activity and the current milestone; avoid unnecessary questions | Answer only when execution needs input |
| Attempt failed or a blocker changed | Explain the impact and recommend recovery or other eligible work | Approve any changed scope or start the prepared recovery |
| A result or review arrives | Summarize evidence and outstanding feedback; prepare the relevant review action | Review, accept a result or respond to feedback |
| Milestone delivered | Save the result, reassess remaining work and recommend the next eligible step | Start it or accept a proposed plan change |
| User returns later | Show saved situation, changes since their last decision and the next useful action | Continue without reconstructing context |

Suggested answers are first-class Goal messages. Prepared actions reference
validated work items, sources and existing task actions; agent text is never an
executable command. One current guidance response contains a brief assessment,
an optional consequential question with suggested answers, proposed plan changes
and the next supported action. This extends the existing bounded planner reply,
not a second workflow engine. Facts remain useful when generation is unavailable;
show the last guidance with its stale/failed status and the specific retry remedy.

## Planning records and execution records

```text
Goal and its conversation
  -> accepted Work items
       -> source references
       -> existing Agent tasks and their turns
       -> verified delivery or a user accepted result
  <- durable results, summaries and proposed next steps
```

A Goal groups an outcome and keeps its conversation. It never becomes an
execution Task. A Work item is one agreed piece of work. An Agent task retains
its current conversation, worktree, provider, approvals, review and PR history.
One work item can have several explicit attempts. Do not use `parentTaskId` for
goal membership; it already has execution relationships.

Use these records in the existing modules; names below are proposed contracts:

| Record | Minimum durable facts |
| --- | --- |
| Goal | Existing identity and workspace, objective, constraints, accepted success definition, guidance preference and revision |
| Work item | ID, workspace, optional Goal, title, intent, kind, acceptance criteria, completion rule, priority, order, state and revision |
| Work item links | Canonical sources and accepted snapshots, task IDs, intended PRs or artifacts, explicit blocker relationships |
| Goal exchange | Request ID, user message or automatic trigger, input revision, provider/model, validated answer, proposal and failure if any |
| Work history | Stable fact identity, work-item ID, event kind, evidence, actor and occurrence time |

Start with Learn, Investigate and Develop kinds. These describe intent; they are
not new execution engines. Learning can be completed through the Goal conversation
and an accepted artifact without starting a task. A repository experiment uses
the ordinary task composer and its existing permission and automation controls.

Keep one primary Goal per work item. Moving the item changes membership, not its
execution history. A move must resolve affected blocker links explicitly; it
cannot leave dangling links or dependencies across Goals. One task belongs to
one primary work item. A PR can support
several items only when its scope is explicitly associated with each. Similar
titles are never sufficient evidence to merge items or declare a duplicate.

Canonical GitHub source identity includes host and stable repository/issue
identity; names, issue numbers and URLs are display metadata. Jira identity uses
cloud/site and stable issue identity, not the connection credential ID. Refresh
and authorization still resolve through the current connection. Scope source
lookups across workspaces before ordinary launch so another view does not create
duplicate work. If already owned elsewhere, offer that existing item and workspace.

The current GitHub and Jira task-link tables each enforce a unique source slot
per task association. Supporting several task attempts requires a deliberate
change: reserve canonical source ownership on the work item, retain each task's
accepted source snapshot, and make admission open the existing attempt by default.
Start another attempt is explicit, requires the earlier attempt to be terminal
and its delivery continuation to have settled, and receives a new operation
identity. An idle but open task is still the existing attempt. Preserve
the source locks and task-creation transaction; merely removing the constraint
would reintroduce duplicate-start races.

## Lesson one Trustworthy progress

Use four work-item states: Planned, Working, Done and Dropped. Running, awaiting
review, blocked, failed attempt, evidence unavailable and waiting for the user
are details derived from execution or evidence, not additional durable lifecycle
states. A failed attempt leaves its work item Working and offers recovery.

| Observation | Effect on the work item |
| --- | --- |
| Agent finishes answering, or task is completed locally | Save an attempt result; this alone does not establish Done |
| Task fails or is interrupted | Record failure; keep remaining work and its history |
| Linked PR closes without merging | Record abandonment of that PR; never count it as delivered |
| Issue disappears from the open cache | Evidence unavailable until authoritative source state is read |
| GitHub issue closes or Jira enters Done | Record source state; this alone does not establish code delivery |
| Intended PRs merge in the agreed repository and base, covering the accepted scope | Done under the agreed merged-delivery rule |
| User accepts the required learning, investigation or local-result artifact | Done under the agreed accepted-result rule |
| User drops the item from scope | Dropped with a reason; not a delivered outcome |

The completion rule is agreed before execution: merged delivery for ordinary
contribution work, or an explicitly accepted result for learning, investigation
and intentional local-only work. Changes to that rule are recorded user decisions.
An agent can recommend acceptance but cannot certify its own response as success.

For merge-based completion, save the accepted deliverable set, repository/base,
PR identity, observed merge commit/time and which acceptance criteria it covers.
A merged partial PR does not complete the rest of an item. If coverage is
uncertain, ask the user to review the scope mapping before accepting delivery.
Tests are supporting evidence, not a replacement for the selected completion rule.
GitHub or Jira closure remains a separate explicit source action.

Goal progress is Done / current in-scope accepted work items. Display Dropped
separately and record scope additions and removals. Empty Goals have no progress
percentage. A Goal with all work done is Ready to conclude until the user accepts
its overall outcome. Reopening changes current state and adds history; it does
not count as another first completion. Do not infer older historical delivery
from today's source state or display a historical trend without recorded facts.

An independent first change can correct today's panel before the new model:
label it linked-item activity, separate Ended from achieved results, show missing
issues as Unknown, and keep merged PRs as an explicit factual count. Do not
present a renamed reference count as outcome progress.

## Lesson two One record per outcome

Accepted work receives a Work item even when there is no GitHub issue or Jira
ticket. The primary source, task attempts and deliverables are links on that item.
For example, one bug, two execution attempts and one merged PR remain one outcome.

Accepting a proposal and binding an existing task use the same find-or-create
operation. Persist a unique accepted-item mapping for each proposal item, so
accepting it with a different request ID still returns the same work item.
An operation ID also makes lost-response retries idempotent; reusing it with
different content is a conflict.
Explicit source/task associations are used to find existing work; uncertain
relationships are shown for confirmation. A task already owned by another item
opens that item instead of silently moving it. Moving between Goals preserves
identity, snapshots, history and accepted results.

Persist a compact result before task-log retention can remove its source: final
answer or bounded excerpt, the task/turn identity, relevant verification results,
PR/artifact references, uncertainties and evidence coverage. Save a factual
receipt first, then generate its narrative summary. Summary failure cannot lose
the result or change its completion state. Do not treat a task's unverified claim
that tests passed as a verified check; use normalized command/check evidence and
retain that distinction when only the claim is available.

Reconcile durable task/source facts after restart before retention can remove
uncaptured results. A linked task's retention or deletion path must first save
its receipt, or preserve the evidence and expose a retryable capture failure.
This recovery records facts; it does not restart an interrupted model request.

Goal history outlives task runtime cleanup and source caches. Deleting an
execution task leaves its saved result and an unavailable-task reference. Archive
a Goal without deleting its work. Unmarking a source as Goal removes its role
marker/source association but retains an existing plan, conversation and outcomes;
the current unconditional seeded-Goal deletion must change for this feature.

Goal lessons are attributed summaries of this work. They do not automatically
become repository-wide rules. Remember an applicable lesson uses the existing
repository-memory capture policy and source evidence, rather than restoring the
removed repository-learning system.

## Lesson three Clear organization

Keep the existing list and board. Add fixed views for Next, Working, Blocked and
Done, plus priority and source filters. Accepting a plan establishes an order;
the user can reorder directly or ask the agent to propose a new order.

Use High, Normal and Low local priorities. An explicit user priority wins;
source priority is displayed separately and only mapped through explicit fields
or configured mappings. Do not interpret arbitrary labels as urgency. Prefer
ready work that unblocks an accepted step, and explain that choice in words.

Implement only `blockedBy` between items of the same Goal in the first release.
Reject self-links and cycles. Completing a blocker makes its dependents eligible;
it never automatically starts them. A dropped blocker requires revising the
dependency, not pretending it was completed. Show external blockers as evidence
and a reason without importing another repository or constructing a global graph.

Both Needs attention views reuse the same backend work-item projection and open
the same item. A goal recommendation and its linked issue must not become two
cards for the same next action. Existing urgent approvals, failures and review
requests keep priority. Preserve Ack and Snooze, and announce a new proposal only
when it is materially new or requires a decision.

Cycles, modules, custom workflow builders, arbitrary hierarchy, saved query
languages, YAML synchronization and a public MCP/webhook platform are deferred.
Add them only for an observed organization or integration need.

## Bounded discovery and planning

The first release supports one selected ByteQuay workspace per Goal, its watched
GitHub repository and Jira tickets explicitly mapped to that workspace. It never
silently watches repositories, changes Jira mappings or expands scope. Show a
setup remedy when the requested repository is not available. Keep cross-workspace
goals as a later addition.

Discovery is application-owned and read-only. Use the existing GitHub/Jira refresh
services, credentials, rate-limit handling and source caches. A planning request
may refresh authorized sources; an ordinary panel read never performs discovery.
First filter locally for closed/inaccessible sources, known duplicate markers,
existing active work, accepted delivery, user dismissals and blockers. Inspect
the available candidate set before presentation limits, and disclose source caps,
partial refreshes and stale data. Never claim exhaustive discovery from a sample.

Proposed bounded first-release pass:

1. Supply up to 100 compact eligible candidates, selected by explicit constraints,
   priority and relevance. Include existing work and PR associations so the agent
   can avoid recommending work already underway. Record the selection coverage.
2. A tool-free model call shortlists at most five candidate IDs with reasons.
   The backend accepts only IDs from that supplied set.
3. Refresh available detail and relevant known PR relationships for those five
   through fixed application reads. A missing duplicate/closing-PR relationship
   read is a concrete source-adapter addition, not an assumed existing capability.
4. A second tool-free call produces at most three recommendations and the proposed
   path, grounded in those snapshots. Without sufficient evidence, say Check first
   and propose an investigation instead of claiming the issue is ready or unfixed.

An ordinary follow-up chat or result summary needs only one model call. These are
bounded application steps, not an open-ended agent tool loop. Repository guidelines
and a bounded root overview may be read at an immutable commit through existing
Git helpers. The planner cannot browse arbitrary code or execute commands.
Deeper code investigation is an explicit work-item action through the existing
task flow; its result feeds the plan. This limitation must be visible whenever
a recommendation depends on code that has not been inspected.

## Provider boundary and durable conversation

Use the existing `AgentDriver` and `SessionAccess.TEXT_ONLY` for planning,
shortlisting and summarization. Resolve the workspace Development provider/model
default, allow an explicit Goal choice, and save the selected values on each
request. Probe readiness before allocation. Keep the existing provider login,
managed instructions, sandbox and process-supervision boundaries. Initial support
must be verified for Codex and Claude; do not silently substitute another lane.

A Goal request owns a scratch directory and a supervised helper identity. It
creates no `agent_task`, execution turn, Git branch or managed Git worktree.
It exposes no native tools, ByteQuay tools, external MCP server or approval cards.
The application supplies snapshots and validates a typed response containing an
answer, optional proposed work changes, evidence references and a supported summary.
Reject unknown IDs, cross-workspace references, invalid dependencies, oversized
responses and unrecognized fields before recording any proposed mutation.

Persist the user message before dispatch and the complete validated response after
completion. Failed JSON validation is a recoverable request failure; do not mine
free text for executable actions. A provider success response never bypasses
application admission or outcome verification. Source descriptions, comments and
repository files are reference data, not authority to change scope or permissions.

The durable conversation belongs to ByteQuay. Reconstruct each tool-free request
from the objective, constraints, accepted plan, result records, saved summary and
recent messages. There is no required provider conversation to resume. Preserve
all messages for user inspection; bound model input independently. Pin explicit
decisions and constraints so summarization cannot silently drop them. Record
summary coverage and input revision. If the required context exceeds the budget,
ask to narrow the request rather than silently omit required constraints.

## Updates and failure handling

Creating a Goal through an answer or free-text message starts guidance and shows
its behavior: clarify, suggest, reassess and summarize while ByteQuay is running,
with Pause guidance and Update now controls. Existing manual Goals show a useful
opening question without enabling model calls merely by being read. Answering
that question starts guidance directly; no separate discovery setup or planning
prompt is required. Explicitly paused Goals stay paused. Manual chat still works
while automatic guidance is paused. This document creates no running automation
in the development environment.

Always record source/execution facts without requiring a model. Automatic guidance
runs after meaningful linked task results, review/merge/blocker changes and user
acceptance or feedback. Only a meaningful change in the eligible candidate set
can trigger another discovery pass. Coalesce events; a poll, unchanged timestamp
or the agent's own saved summary cannot trigger another run. Reuse quiet hours and
existing refresh cadence. Proposed initial bounds are one active request per Goal,
two concurrent Goal requests app-wide, and no more than one automatic pass per Goal
in five minutes. User requests take precedence without starting overlapping work.

Persist the last processed input revision/fingerprint, not a recurring workflow
machine. The existing backend refresh lifecycle checks dirty Goals; it does not
call a model on every UI refresh. If a Goal changes during a run, save the answer
as historical but refuse to apply its stale proposal or overwrite the current
summary. Coalesce one later pass against the new revision. Retry and double-submit
reuse the stored request identity and never duplicate accepted items or launches.

The runner owns cancellation, timeout, verified session close and scratch cleanup
using the existing supervisor. Keep helper ownership on the request so uncertain
cleanup can be reconciled before another run. Preserve scratch resources when
stop cannot be verified. Persisted messages without a completed response and
without a live owned request appear Interrupted after restart, with explicit Retry.
Do not resend an interrupted user request or execution automatically on startup.
Fresh factual changes may update the panel; the next guidance request incorporates
them. No off-device daemon or activity while the application is stopped is promised.

| Failure | Required behavior |
| --- | --- |
| Offline, unauthenticated or rate limited | Keep the plan and last results; name the unavailable source and offer its existing remedy |
| Discovery covers only part of the source set | Show checked coverage and uncertainty; do not infer absence or completion |
| Agent fails or emits invalid output | Preserve the user message, current plan and evidence; expose retry |
| User edits while the agent answers | Preserve their edit; stale output cannot become the current plan |
| Item is closed, marked as Goal or started elsewhere before Start | Revalidate admission and open existing work or explain the changed condition |
| Summary generation fails | Facts remain recorded; show Summary pending and retry independently |
| Task logs expire | Saved outcomes remain; unavailable evidence is identified |
| Goal is archived or guidance paused | Stop new automatic requests; preserve history and any existing execution tasks |

## Application and storage changes

Keep domain records and store ports in `core`, entities/adapters in
`persistence-sqlite`, and Goal orchestration, discovery, result projection and HTTP
in `app`. Use the existing provider SPI and task creation path. Add no Maven
module, connector framework, execution engine or general event bus.

Extend `GoalController` and the typed Electron bridge with goal messages, proposal
acceptance/dismissal, work-item editing, result acceptance and guidance preference.
Commands carry an operation ID and expected revision. Reads are local projections.
Start carries a work-item ID into canonical task creation; the server resolves
and revalidates its accepted source, workspace and intended outcome. Persist task,
source snapshot and work-item association together before the first turn is admitted.
The existing task idempotency identity is reused on lost-response retries.

Use Hibernate-managed tables for final planning, conversation and history records.
Index source ownership and task associations; use database uniqueness plus the
existing source locks. Enforce task/workspace ownership and revision checks in
the backend, not just the panel. Record result receipts idempotently by their
task/turn or source evidence identity so event replay cannot multiply outcomes.

Replace the old reference-counting Goal projection when work items land. Do not
retain competing progress models or add compatibility adapters for intermediate
designs. Preserve existing task, source and PR histories. The implementation must
explicitly account for any meaningful existing Goal data before an incompatible
schema change: back it up and agree on its disposition, rather than silently
resetting the user's database. Disposable local fixtures may reset under the
existing development convention. This proposal authorizes no data deletion and
introduces no Flyway or migration-history machinery.

## First release and verification

The first complete release includes the conversation, bounded GitHub/Jira
discovery, accepted work-item identity, existing-task handoff, durable outcome
receipts, summaries, next proposals and the updated panel. Fixing a progress bar
alone is not this release. The implementation plan delivers smaller independently
verifiable changes on the way to that complete loop.

Acceptance must demonstrate:

- A natural-language Goal produces a saved path and real source-backed proposals
  without starting a task or allocating a Git worktree.
- Accepting a proposal twice creates one item; starting twice creates one task
  and first turn. A source already active in another workspace opens that work.
- One issue, repeated attempts and its PR count as one outcome. Failed attempts,
  unmerged closures, cache loss and partial delivery do not increase Done.
- Learning completes through an explicitly accepted result. A code contribution
  completes only through its selected verified delivery rule.
- Source refresh, task result and merge events update the same panel and both
  attention views; repeated events produce no repeated summary or notification.
- Stale proposals, missing credentials, malicious source instructions, invalid
  model output, pause, restart and uncertain cleanup preserve user work and scope.
- Results and summaries remain readable after execution retention. A reopened
  item preserves earlier achievement and scope history without double counting.
- Codex and Claude fixtures prove tool-free planning and canonical execution
  handoff; opt-in live smoke evidence is reported separately from fixture checks.

Run backend `mvn verify` and frontend lint, TypeScript, tests and packaging for
material implementation changes. Add focused tests at the identity, admission,
outcome and user-interaction boundaries; use the existing test frameworks.

## Reference ideas from Plane

Plane's [work-item model](https://github.com/makeplane/plane/blob/preview/apps/api/plane/db/models/issue.py)
illustrates stable work identity, source references and explicit relations. Its
[state model](https://github.com/makeplane/plane/blob/preview/apps/api/plane/db/models/state.py)
separates completion from cancellation. ByteQuay adapts these concepts to its own
local execution and delivery evidence. This design copies no implementation code
and does not depend on running Plane.
