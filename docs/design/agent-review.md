# Agent Review

[WorkBench](workbench.md) owns the shared navigation, task-bound code access,
and reviews of local development commits. Its source and hosting contracts
apply to the existing services described here.

This is the canonical design for the review workflow shown in WorkBench Summary. Changes to the
product boundary or the invariants below require explicit approval. Delivery
and verification belong in [the implementation checklist](agent-review-impl-tmp.md),
not in this document. Keep this document as final design content, without a
dated progress log.

## 1. Decision

Agent review is a private workflow over the existing agent service, sourced from
a GitHub PR or a development task's committed branch snapshot.
One ongoing review owns one agent task, provider conversation, and managed
worktree. Multiple investigation, discussion, and incremental-review turns
reuse them. Finishing one review run does not complete the agent task.

Review has four responsibilities: understand the intended behavior, inspect
its affected paths, validate candidate claims, and report supported findings.
ByteQuay coordinates a bounded sequence of assignments in the same task and
provider conversation. Investigation saves the change map, assessed changed
files, supporting context, numbered hypotheses, and intention/commit explanations.
Once its turn settles and releases the runtime, ByteQuay starts validation.
Validation either supports a finding, rejects a hypothesis, requests a specific
piece of evidence, or records an unresolved claim. A valid evidence request
starts an evidence-collection turn; its answers return to a new validation
turn. At most two evidence-collection assignments run per frozen head attempt.
This is explicit self-review in one conversation, not independent verification.

The agent judges whether evidence establishes the claim. ByteQuay checks report
structure, frozen identities, assignment ownership, candidate accounting, and
the follow-up budget. It never extracts workflow decisions from assistant prose
or treats a confidence score as proof. The agent chooses context and verification
techniques; ByteQuay supplies frozen scope, on-demand tools and optional CodeGraph.
There is no task per finding, new execution engine, or configurable pipeline.
Expected behavior must have a basis independent of the check used to observe
actual behavior. A test or another reviewer's comment can suggest a candidate,
but neither establishes the product contract or proves a finding by itself.
For each changed behavior, the investigator follows only the relevant callers,
sibling paths, implementations, registrations, configuration and failure paths,
then rereads the decisive changed and unchanged source. Validation checks each
candidate against the applicable contract and a focused behavior control. Broad
searches may run in parallel through native children assigned read-only
exploration; the root rereads decisive source, challenges their conclusions, and owns every saved
report. CodeGraph suggests paths to inspect, never establishes correctness.

User review turns carry the repository's guideline files; automatic assignments
reuse confirmed delivery within their frozen run. Guidelines are read at
the run's base commit as [Agent Service](agent-service.md#repository-guideline-input)
describes. Reviews keep no saved repository notes; [Repository
memory](repository-memory.md) describes what replaces them.

The user can watch activity, steer supported active turns, challenge the
summary or individual findings, resolve findings privately, and explicitly
promote selected comments into their own outgoing review. Agent output never
publishes itself to GitHub.

This design adds a narrow review capability to the existing task tool bridge.
It supersedes the no-business-tools baseline in [Agent Service](agent-service.md)
only for the context reads and local review writes specified here. It does
not authorize a general tool registry, remote-write tool, new agent runner,
or restoration of deleted task/development-flow systems.

## 2. UI placement and reuse

The shared panel exposes **PR** and **WorkBench**. PR shows the overview with
GitHub actions. WorkBench contains Summary, Changes, and
Code & Tests. Summary is available for authored and other authors' PRs and for
development tasks before PR creation. **Review changes** on a development task
starts a separate reviewer with the configured Review provider/model; the
source developer keeps its conversation and working copy. An explicitly opened
review workspace always shows that reviewer's source and shares its one stream.
The authored PR's **Assign agent** and **View in task** entry points continue to
use the existing task navigation and ownership checks.

The tab reads like a pull request timeline. A strip at the top says when the
kept summary was written and which commits it covers, and holds the reviewer
control: **Review changes** beside the committed range it would read,
**Reviewing N commits…** with **Stop** while it runs, then the review's status
chip with **Review again**. The timeline starts with the developer's
**Change intention**, then one numbered event per commit in source order: its
title, a `···` toggle for the Git message body, short SHA, time, and
**View changes**. Each event is followed by its own private card with the
agent's explanation and discussion, so folding the card leaves the commit event
visible. On a development task the reviewer's event follows the commits and
leads directly to its findings. The reviewer's own summary cards and Review
details are not repeated there, because the developer's cards already explain
the change; they remain in the review's workspace. Reviewing another author's
PR shows the reviewer's intention and commit cards as that timeline, followed
by Findings.

Every private card (change intention with a green header, a commit's design and
implementation with a blue header, and findings and local review comments in
the teal **Local** treatment) is the one local card component. It shares no
markup with the GitHub review thread card, so changing either never restyles
the other, and every surface that shows private cards follows the local card.
Each card retains its own private discussion through the shared
[Local review comments](agent-service.md#local-review-comments) components. Do not generate combined-result, evidence/test, coverage, or
critical-issues cards. Nonblocking verification warnings, such as cloud tests
that could not run without external credentials, appear inside the change intention
card for the displayed run. An accepted review with warnings remains **Finished**
and its supported findings can be selected for publication.
A collapsed **Review details** disclosure shows the
change map, assessed changed-file paths, retained supporting evidence from
both passes, and final candidate conclusions. Evidence reference IDs remain
visible so conclusions can be matched to their source or command excerpts;
the initial investigation's context selection cannot hide later validation
evidence. Draft hypotheses never appear as findings.
Source reads are context inspected, not proof of semantic coverage. Existing
discussions on removed sections remain under a
collapsed **Earlier discussions** disclosure, with their original run and target.
Opening a request in the transcript opens and focuses that exact discussion.
The tab also collects local comments for this PR across development and review
tasks. Changes retains the same selected-line/range composer and saved threads.

The tab shows **Reviewing** throughout investigation, validation, and automatic
report repair, including the gap between automatic assignments. An accepted result is **Finished**;
a persistent failure is **Could not finish**, with its reason. There is no routine **Retry review**
action: ByteQuay owns recovery within the running review. **Resume** is for a review the user stopped;
**Update review** remains the explicit action to review the latest source revision. Internal
incomplete-report states are not user-facing stages. Only an accepted result's
timestamp says **finished**; an unsuccessful attempt says **ended**. Saved
partial work remains provisional, and a failed attempt never claims there are
no findings. Ordinary discussion uses **Running**. The tab has no
**Summary & comments** or **Activity** subtab. **View in workspace** opens the
exact review task with the existing navigation, its agent conversation on the
left, and the PR pane on the right. That conversation reuses task transcript,
approval, queue, steering and composer controls. Its live review stream also
keeps the PR pane's cards current, and the pane opens no stream of its own:
Electron keeps one stream per renderer and review, so a second subscription
would silently replace the conversation's. Review routes preserve their
own ownership checks; they do not become interactive development task routes.
Opening and closing these views never starts or cancels execution.

During Reviewing, show progress only from persisted assignments and sealed
reports: the current assignment, assessed required files, candidate outcomes,
and any explicit omissions. Label assessed counts as reported coverage, not an
estimate of remaining work. Do not infer progress from file reads, model
activity, elapsed time, or a fabricated percentage.

In PR browsing, keep **Start review** and its existing scope/provider checks.
Development tasks maintain their own learning report through a dedicated
**Refine summary** turn after successful work changes code or commits, using
the existing [development follow-up](development-delivery.md) coordinator.
Their tab is available before a PR exists and shows the change intention and
individual commit explanations with the same visual separation, without
requiring a separate review task. **Refining summary…** appears in task activity.
The report is stored with the generated turn and read independently of the
conversation retention window. Unchanged turns do not regenerate it.
Questions use the existing private local-discussion service with an explicit
learning-card anchor containing the source turn, card identity, and immutable
text snapshot. Refreshes preserve earlier discussions and their source version.
Existing PR-owned findings remain visible when a separate review exists.
Reviews run in the backend and populate cards as structured results arrive.

The original PR view continues to show GitHub data. Changes shows local
findings at their validated diff locations. A finding there and its card in
Agent review are projections of the same finding ID and discussion; neither
copies the other's state. **View in Changes** opens the exact file/range and
**Open discussion** returns to that finding. For linked PRs, Changes stays on
the published diff; a finding from another revision offers a return to its saved
location and discussion in Summary instead of jumping to unrelated current
lines. Local reviews without a PR can still open their recorded snapshot.

Private review is available for other authors' closed/merged PRs when their
source can be resolved. Authored PRs can start a private review or use their development WorkBench;
retained reviews remain accessible from their existing task routes.
Do not reuse the remote-review eligibility predicate for private review. Remote publication retains existing
account/PR eligibility checks; a private review does not grant new remote
approval or comment permissions.

The global PR view resolves watched workspaces by the PR's monitored repository
identity, including upstream repositories watched through forks. Reuse the
first matching workspace with an open review; if none has one, use the first
matching workspace in saved display order for Start review. An explicitly
opened workspace keeps its own review context. Loading or failed context reads
must not be presented as an unwatched repository.

Workspace **Tasks** lists that workspace's open and ended reviews beside its
other tasks, under **All** and the **Review** type tab. A review sits in the
day of its latest activity: a card under **Today**, a row under **Yesterday**,
**Earlier this week** or **Older**. Each review appears once and names the
pull request by number and stored title.
Opening one selects the exact review ID in the PR pane, including an ended
review when a newer review exists for the same PR. A separate workspace icon
opens its conversation and PR together at
`/workspaces/:workspaceId/tasks/review/:reviewId`. Review ownership and controls
stay in the PR workflow. Interactive task routes remain restricted to interactive tasks.

`GET /api/agent-review?workspaceId=...` reads those reviews from local storage in
descending update order, with review ID as the stable ascending tie-breaker. It
returns the existing review projection and neither refreshes GitHub nor starts or
reconciles agent execution. Every projection carries `pullRequest`, the stored
pull request's title, state and draft flag; it is absent until ByteQuay has
stored that pull request, and nothing fetches it to fill the gap. Supplying `repo` and `number` retains the existing
single-PR lookup; `GET /api/agent-review/{reviewId}` opens an exact retained review.

In PR browsing, a **Code & Tests** tab beside Summary reads whole
files, follows symbols, runs tests, edits test source and opens a terminal in
the open review's working copy. Its routes under
`/api/agent-review/{reviewId}/code` (`files`, `file`, `symbols`, `outline`,
`runs`, `terminal`, and saving or discarding test edits) are a read/run surface
of that review's worktree. They validate review ownership like the other review
routes, never start agent work, and never write to GitHub. An ended review's
source and run history stay readable there. Their contract is in
[Code & Tests](code-tests.md) §4–§7.

### Visual contract

The review Summary follows the **2a hierarchy timeline** in the
[compact Summary reference](https://claude.ai/artifact/4XtSE3wCyQoJfmBJVYAhz9).
Its status, saved progress counts and review controls share the WorkBench tab
row, wrapping in narrow panes. The green intention card leads, with source
revisions and folded Review details in its footer. Numbered commit cards nest
below it, keeping their title, short SHA, private question and diff actions in
one header; their explanation and discussion fold together. Findings follow
with All, Open and Resolved filters that preserve card drafts and fold state.
The existing Findings card component, styling and action handlers are unchanged.

The checked-in PR page is the styling source of truth. Reuse its actual
components or small extracted presentational pieces, not a parallel imitation:

- top navigation, active-tab treatment, spacing, and pane/fullscreen behavior;
- review-card borders, backgrounds, radius, avatar/header, author/time text,
  file/line header, chevron, folding, and resolved/outdated treatments;
- existing icon set and sizes; no new AI icon language;
- current button variants, dimensions, disabled/loading/focus states;
- typography, text colors, Markdown rendering, inline code, and links;
- the same diff-review editor markup and CSS for private review questions and
  outgoing comments: Write/Preview tabs, Markdown toolbar, bordered textarea,
  and action buttons; a disabled **Private** button sits in the middle of the
  private editor's top bar, while outgoing editors retain their destination
  caption; private labels and handlers retain their own authority;
- activity/transcript components and capability-driven controls from Tasks.

Start with `frontend/src/pulls/PullsScreen.tsx`, `PullOverview.tsx`,
`PullChanges.tsx`, `MarkdownBody.tsx`, `frontend/src/agents/AgentTaskScreen.tsx`,
`frontend/src/css/pulls.css`, and the existing shared CSS/tokens. Revalidate
these entry points against the current checkout before implementation.

Reuse appearance separately from action handlers. Existing GitHub Reply,
Resolve, and Submit callbacks must not back private **Ask agent** or
**Resolve locally** actions. Prefer a focused presentational extraction only
where two real callers need it; do not introduce a generic comment framework.
Finding code references use the review comment's numbered code rows and their
saved excerpt, including for outdated findings. They never substitute the
current working copy for the reviewed version or mark unchanged source as added.

The current manual diff composer already supports line ranges. Extend that
path instead of rebuilding selection. Its pending comments are currently
renderer-held DTOs, not the durable draft needed here. Preserve manually
written comments alongside promoted findings, and use the existing Submit
review sheet for final selection. Do not silently inherit its current
APPROVE default as the agent's recommendation. New durable drafts default
to COMMENT; Approve and Request changes require an explicit user selection.
Persist the review body/event as well as its inline items when the sheet closes.

The earlier conversation sketches illustrate placement, not approved pixels
or evidence about the example PR. An approved UI reference must be checked in
before the production UI checkpoint is accepted. The prompt in Section 12 is
the handoff for that work.

## 3. Ownership and durable facts

Use the existing module direction in [Architecture](architecture.md): review
domain values/storage ports in `core`, SQLite adapters in
`persistence-sqlite`, and review orchestration, GitHub reads, and HTTP in
`app`. Agent execution remains in `agent-core`/`agent-cli`. No new Maven
module is needed.

One concrete application review service coordinates the following facts;
this is not a second task engine:

| Record | Required facts |
| --- | --- |
| Review | Workspace, optional source development task, optional logical PR identity, physical repository/head-branch identity, reviewer agent task ID, latest completed run, latest observed PR head, follow-up execution enabled/paused, optimistic version, explicit closure |
| Run | Review ID, full/incremental scope, frozen base tip and merge base, head SHA, prior successful reviewed head if any, turn IDs, ordered assignments, latest investigation, accepted result/coverage, terminal outcome |
| Assignment | Consecutive number, investigation/validation/evidence-collection kind, reporting-repair attempt, immutable report when sealed |
| Investigation | Change map, distinct assessed changed-file paths, captured supporting context refs, numbered hypotheses, evidence-request answers, latest validation conclusions |
| Validation | Sealed investigation step number, assessment of every candidate, newly discovered hypotheses, coverage, blocking omissions and verification warnings |
| Summary | Run ID, revision, purpose, actual commit explanations |
| Finding | Stable ID, origin run ID, current anchor, title/body, copied evidence, current revision, local resolution/withdrawal and reason, last validated head SHA and outdated flag; current-run validation binds the exact finding revision |
| Discussion message | Stable ID, review and summary-card/finding target, author, body, target revision/run, correlated task input/turn or reply, delivery outcome |
| Outgoing draft/publication | PR/account-owned draft usable without Agent Review; stable item ID, MANUAL or AGENT_FINDING source, optional finding ID/revision, reviewed PR commit SHA and frozen location, user-editable body/version, inclusion, review body/event, exact publication attempt/payload and returned remote identities |
| Local run | Task (a review's runs are its task's runs), ordinal, USER or AGENT initiator, class/method/command scope and selector, source head/local paths/fingerprint at start, status from runner facts only, bounded output and per-test results |

These are logical records, not a mandate for one table per row. Reuse existing
draft and publication mechanisms where they satisfy durability and identity.
Use optimistic versions and operation idempotency at every local mutation.
Enforce one open review per workspace/source development task or, for PR-owned
reviews, workspace/logical PR. Duplicate Start actions reuse that source's
reviewer task and worktree. Replace the ephemeral
pending-comment array with the same durable draft path for manual Add to
review and agent Add to my review. Manual-only review must still work without
an agent review record.

Persist summaries, findings, private discussions, and user drafts as product
data, independently of the task's disposable detailed log. Store only the
bounded evidence excerpts needed to retain a finding's meaning, not another
copy of the entire transcript. Runtime retention must not erase these records.
PR cache eviction must not delete or orphan an active review or unpublished
local work; retain its logical PR identity and required snapshot data.

Local runs are the review-owned test history shown in Code & Tests. They are
not evidence cards, never publish anything, and outlive End review and runtime
retention. A user run starts only from a direct user action. The agent keeps
running tests in its own sandbox with unchanged tools; the review task observer
mirrors each completed agent Maven command into the same run history as an
`AGENT` local run, read from its command event and Surefire reports. Status and
mirroring rules are in [Code & Tests](code-tests.md#5-backend-local-runs).

Derive Reviewing, Running, Waiting for approval, Finished, Stopped, Could not finish, and New
commits from existing task/turn facts and review result facts. Do not add
Reading/Thinking/Testing lifecycle enums or persist UI timers/percentages.
Complete coverage means the recorded scope was reviewed, not that the PR is
guaranteed correct or that every possible test ran.

## 4. Background execution and worktree

Starting review is a direct user action. ByteQuay checks provider readiness,
captures the exact source head and base, and provisions through
[WorktreeService](worktree-service.md). The generated local branch remains
ByteQuay-owned; it maps to the contributor's branch but is not that remote
branch and is never pushed by this workflow. Resolve fork PR heads through
trusted repository/PR metadata, not an agent-supplied shell command or URL.
For a development source, read the verified source worktree's local Git objects
without fetching or touching its index. Capture committed work, including
unpushed commits; exclude uncommitted files. The fresh reviewer receives the
source task's requirements and selected code, not the developer's conversation.
GET/POST `/api/agent-review/tasks/{sourceTaskId}?workspaceId=...` finds or starts
this review. The source task may be deleted later without deleting retained
reviewer results; further updates then explain that the source is unavailable.
An explicit Update can adopt a subsequently created real source PR, retaining
the reviewer and source identities. Nothing publishes automatically.

Create a workflow-owned task and start its first turn with a ByteQuay-built
review request. Display that input as **ByteQuay · Review request**, not as a
message the user typed. Apply the review policy through the managed
instruction channel on initial and resumed sessions. Do not write
`AGENTS.md`, `CLAUDE.md`, repository settings, or global provider settings.
Use the existing attended AUTO sandbox/approval behavior, not CI Autofix's
or cherry-pick's narrower task scope. Workflow-owned does not mean unattended.
Review-specific tool scopes and remote-write restrictions do not require a
new agent execution loop or a new generic permission-policy framework.

The backend owns execution and observation. Changing tabs, PR selection, or
renderer connections does not cancel a run. The local backend must remain
running; shutdown/restart follows existing interruption and recovery rules,
not a claim that this is a cloud job.

The agent may read, edit, add/remove temporary tests, and run experiments in
the worktree under the existing sandbox and approvals. It need not make a
commit each turn. Findings must describe the frozen PR code, not a defect
introduced only by the agent's experiment. Evidence from an edited worktree
must say what was changed and be checked against the original snapshot.

### Updating the mapped branch

An active review observes the PR's remote head through the existing detail
refresh event. Show newer PR commits as information about the reviewed version;
they do not prevent publishing comments on their original reviewed commit.
Advancement of the merge target branch, such as another PR merging into master,
does not invalidate the review or its outgoing comments. Retained closed/merged
runs stay bound to their historical scope. A newer remote head automatically
updates a clean reviewer checkout once its complete review run and all task
work are idle. This includes a force-pushed head. Never interrupt an active
investigation, validation, repair, discussion, or local run, or move the worktree
beneath its processes. Terminal task events and the existing reconciliation
path retry a deferred catch-up. Observing or catching up never starts another
review; **Update review** or **Resume** still explicitly assesses the new head.
Development-source reviews follow their local source only on explicit update.
An explicit update can capture the current base tip and merge base as source
context and start a full review when that context changed. This analysis choice
does not change the commit or location of any existing outgoing comment. An
unchanged-head worktree is not a merged-result checkout: interactions with
changed base code need source or executable evidence, or remain unresolved.

Before syncing, require the existing task execution boundary to exclude turn
start, queued start, tools, and cleanup. Verify provider quiescence, generated
branch/repository ownership, expected HEAD, and no open Git operation. Automatic
catch-up refuses staged, unstaged, or untracked changes, saved experiments, and
unrelated local commits; it never stashes or discards them. Code & Tests shows
an amber toolbar icon when the local branch is stale; its confirmed **Discard
and reset** action catches up the checkout without starting a review. Summary's
explicit Update review retains the experiment handling below. Automatic and
ordinary review updates require a clean index/worktree including untracked
files at the move itself. An unrelated review-only mutex that
the agent queue can bypass is insufficient.

The separate Code & Tests `POST /code/sync` action accepts the expected local
HEAD, fetches the latest remote PR head, and explicitly discards local commits
and tracked/untracked changes under the same idle, ownership, expected-head
and no-open-Git-operation checks. It is available only for an open remote
review. Owned saved-experiment stashes are removed; ignored files and unrelated
stashes are preserved, and ignored-file collisions with the remote tree are
refused. A failed preflight preserves local work; a successful reset records
the aligned working-copy head and returns the current review projection. It creates
no review run, task turn, or provider process and leaves findings and reviewed
heads unchanged. The renderer clears unsaved drafts only after success and
reloads the working-copy files. This destructive action has its own
confirmation and does not change automatic catch-up or Update review's
experiment-preservation contract.

The core `synchronizeWorktree` callback uses the same durable task version,
task lock, and existing mutation claim as private local-review transitions.
It excludes direct and queued starts throughout automatic moves and explicit
stash, move, and restoration, and releases ownership before launching any next
review turn. Automatic catch-up launches none. A review mutex
alone is insufficient because the core queue can start without it. Native
stash operations also hold a repository lock across linked worktrees.
`WorktreeService` itself gains no review lifecycle, discard mode or generic
lock registry.

Code & Tests saves and discards test edits through
`AgentReviewService.mutateWorkingCopy`. It holds the review lock, refuses a
review that is not open or whose latest run is running and not paused, and then
uses the same `synchronizeWorktree` callback. Discard reuses
`ReviewExperiments.discard` at the revision the tab last read. A user local run
takes the task's `LOCAL_RUN` mutation claim under the review lock and keeps it
until its processes are verified stopped. Turn start, enqueue and
synchronization already refuse a held claim. Update review, Resume and End
review also refuse it explicitly with "A run is using this working copy", and
Summary disables those controls, Run review again and Send to agent
with that reason.

For an ordinary descendant head, update the verified generated branch with a
guarded fast-forward and verify the resulting HEAD. Unknown local commits are
refused. Keep `TaskWorktree.startCommit` as the original provisioning fact and
each reviewed head as an immutable run fact. Separately retain the last
service-aligned worktree head so repeated remote updates can catch up without
mistaking the earlier automatic move for a local commit. Derived worktree sync
status reports its actual head, target, dirty state, and blocker independently
of whether that target has been reviewed. The additive nullable head field is
managed by Hibernate without a data reset.

Dirty experiments, including new tests and manual edits, are saved with native
`git stash push --include-untracked` before sync. Apply that exact stash at the
new head with its staging preserved, then drop only that entry after successful
restoration. Serialize ByteQuay stash operations across linked worktrees, which
share a stash stack. Ignored output and unrelated stashes are preserved.

Before the user initiates an update, disclose that experiments are restored when
possible and all private experiments are discarded if restoration conflicts.
On a conflict, restore the clean remote revision, remove untracked experiments
and this task's temporary stash, and continue the requested review. Preserve
ignored output and unrelated stashes. Do not leave conflict markers, ask the
user to resolve them, or introduce a recovery state. Keep at most one temporary
stash per task and normally none; conflict cleanup must not create another
backup. No agent conflict-resolution turn, merge commit, or push is involved.
A failed head move restores experiments at the old head when safe; filesystem
or Git failures remain ordinary errors.

Local-only commits, a different branch, or an open Git operation still require
inspection before syncing. For a force push/non-descendant update, compute a
full-review scope when the user requests another review. A clean idle worktree
can realign automatically to the verified remote revision; dirty experiments
require explicit **Update review** and use the same stash-and-restore path.
Keep the old review readable until a new run has completed.

**Stop** cancels the current run, pauses queued/new-head execution, and keeps
the review open for discussion or explicit resume. **End review** closes the
ongoing workflow once execution is quiescent and pending input is handled.
Task completion refuses uncommitted work, so End checks the working copy before
it stops observing the review task (`observer.unwatch`). Uncommitted changes,
untracked files included, refuse with 409 "Discard saved edits before ending
the review" and leave the review open and observed; ignored build output does
not. End then completes the task, durably closes the review, and requests
normal clean worktree cleanup. A worktree that still holds ignored output is
kept for inspection; the generated branch is always retained. A PR being
merged/closed does not itself authorize destructive cleanup.

When the last turn has ended but provider cleanup remains unverified, expose
**Recover runtime**. Its review-scoped impact/read and stop endpoints delegate
to the existing generation-guarded task runtime recovery, including startup
recovery for a retained `RECOVERY` claim. Do not route this workflow-owned task
through interactive Tasks endpoints or weaken their ownership guards. Recovery
preserves the same review, task, findings, discussion, and interrupted turn;
it neither ends the review nor marks a failed run as successfully reviewed.
For an old process-observation gap with no provider completion proof, the
boot-session recovery in Agent Service §5.6 may require one Mac restart after
the current boot observation has been durably recorded.

Once cleanup is verified, **Resume** continues the open review. If no prior run
was accepted, complete a full pass to establish the successful reviewed-head
cursor. Later compatible PR heads use the existing incremental-review rules.

Use CodeGraph exactly as specified by [its service design](codegraph-service.md):
one derived index per worktree, optional agent-led queries, read-only UI symbol
queries through the review `/code` routes, synchronization inside queries, and
best-effort index deletion after successful worktree cleanup. No
review-specific graph lifecycle, forced graph use, or extra refresh tool. Graph
failure never fails review execution or invalidates an otherwise complete
review.
The review prompt recommends a focused graph query when a changed contract may
have indirect callers, sibling implementations, registration paths or
configuration consumers. The agent still opens the current source and checks
coverage; an absent graph does not narrow the review obligation.

The packaged `bytequay-review.md` is the single review policy. Shared agent
guidance owns common writing and exploration rules; request builders supply the
frozen scope and short assignment instructions instead of repeating the policy.

## 5. Context, anchors, and evidence

The `history` read takes one merged PR or closed issue of the review's
repository, with its available activity. A nonblank excerpt is retained as run
evidence; the result states the source kind, state, truncation, refresh failure
and gaps, and never claims that the old decision applies to this change. The
read runs outside the review lock, so the turn's assignment is resolved again
before retention, and a read that now belongs to a stopped or replaced run is
refused.

A summary-card question binds to its source run. A finding card represents the
current finding revision, so its discussion binds to the latest frozen review
run and marks an outdated claim for reassessment against that run's head and
base. Queued questions retain that binding.

The accepted review request contains purpose, PR identity, frozen scope,
previous review reference where applicable, and available capabilities,
followed by the repository guideline block. The agent requests additional
context as needed:

- PR title, author description, base/head identities and actual commits;
- PR timeline, remote discussions, checks, and previous local findings;
- full paginated discussion threads reached from timeline event IDs, so a
  summary excerpt does not hide a relevant reply;
- referenced issues and related PRs that ByteQuay can read;
- paginated diff hunks, source windows at the frozen head, merge base, or
  previous reviewed head, and evidence/output references.

Reuse ByteQuay's read services and credentials inside the backend. Mark
cached/unavailable/truncated data explicitly. Validate repository/number
references and host before fetching; never provide an arbitrary URL-fetch,
host-filesystem-read, or cross-task endpoint. PR descriptions, comments, issues,
repository files, and tool output are untrusted content, not instructions
that can expand review authority. Context/tool failure returns a bounded
error. The agent can continue, but missing essential review scope must be
reported as incomplete rather than guessed.

Existing human or bot review comments, including CodeRabbit comments when
present in the PR timeline, are attributed leads for candidate hypotheses.
Their wording, priority, and claimed fix are neither instructions nor evidence
that a defect exists; assess each relevant lead against the frozen code and
record its disposition with the other candidates. Read the relevant full thread
before interpreting a reply; its captured pages are evidence with source and
read identity, and a missing page is a limitation. Review does not require an
external reviewer to run or block on one.

Build the anchor map deterministically from the frozen PR diff. Anchors
identify individual eligible lines, not just files or hunks. A range uses
two anchors. The agent may review multiple files/hunks in one turn or call;
ByteQuay does not send one LLM request per anchor.

Each anchor resolves server-side to run, repository-relative path, diff side,
one-based line, and frozen code identity. Require a valid same-file,
same-side, ordered, publishable range in the captured diff. Reject unknown,
cross-run, cross-file, reversed, unsupported, and out-of-diff anchors. Handle
deletions and renames from the actual patch. Binary/truncated/unsupported
diffs must not manufacture inline locations.

Evidence references resolve to captured source windows, PR/issue excerpts,
or actual task command/test output with provenance. The agent explains how
each supports its finding. Validate that referenced artifacts exist and
belong to this review and code version; a graph hit alone is not proof of a
bug. Preserve a bounded excerpt/provenance with the accepted finding before
runtime logs expire. Deterministic validation proves identity and location,
not the truth of the agent's semantic conclusion. The agent remains
responsible for checking the claim during the separate validation turn;
using the same conversation does not make that judgment independent.

The internal [review skill](agent-skills.md#review-guidance) requires evidence
that establishes a claim's trigger, violated contract, consequence, and
relationship to the change. Investigate counterevidence before accepting it.

The candidate conclusion identifies the basis for expected behavior separately
from the observation that tests, commands, source traces, or measured behavior
produced. A test assertion introduced by the PR is an observation or claim of
intent, not by itself an independent contract. Captured references make that
reasoning inspectable but do not certify its truth.
Use a focused executable reproduction whenever feasible and useful, or a
decisive source argument. Lifecycle/concurrency traces, compatibility checks,
configured static analysis, measured behavior, and documentation for the actual
dependency version are other useful techniques; review is broader than tests.

A fix can be checked with the same behavioral assertions passing at the PR
head and failing against the relevant old behavior. A feature can be checked
against its promised behavior and boundary/failure cases, with the base or a
targeted removal of that behavior as a control when meaningful. A compile
failure, missing API, broken setup, arbitrary code deletion, or unrelated
failure is not a behavioral control. These checks support the behavior tested,
not the correctness of the entire PR. A finding about a remaining defect must
establish that it exists in the frozen PR code, not only after a mutation.

Capture commands, test/setup, expected/actual results, decisive output, actual
exit status, and control edits in evidence. Restore the agent's own experiment
edits after preserving that evidence; retain other work. Environment and test
setup failures do not establish a PR defect. Shell pipelines must preserve the
actual test exit status rather than reporting the status of a formatter.

Select at most ten distinct findings per round, ranked by severity and impact;
the reporting tool enforces that creation limit across retries and discussions.
Require Critical, High, or Medium by default; Low is included only at the user's
explicit request. Severity selection remains managed guidance. Complete commit
coverage remains required. No model score certifies semantic correctness.

## 6. Local reporting contract

Register a fixed set of review tools through the existing task MCP
bridge. The authenticated callback supplies task, review, run, and actor;
the model cannot choose them or supply an absolute filesystem path/token.
Context reads may select validated repository-relative paths or returned
reference IDs, never a source root or another task's files. The review
bridge must work when CodeGraph is off or absent. Ordinary tasks do not gain
these tools. Provider-specific registration, resume, cancellation, and
isolation must pass executable tests before enabling the review capability
for that lane; failure does not disable ordinary agent tasks. Save and mutation
tools are root-only; a gated child can receive only the specified read tools.

| Tool | Purpose |
| --- | --- |
| `review_get_context` | Bounded typed reads of current PR, timeline thread, linked issue/PR, selected commit-to-PR associations, diff, source, merged-PR or closed-issue history, evidence, summary, finding, or discussion; supports pagination |
| `review_save_summary` | Save the intention and exactly one explanation for every actual commit |
| `review_save_investigation` | Save the change map, assessed changed files, supporting context refs, and numbered candidate hypotheses |
| `review_save_finding` | Create a finding, or update a returned finding ID with its expected revision |
| `review_reply` | Append a private answer to a summary/finding discussion using its returned target/message identity |
| `review_withdraw_finding` | Withdraw an agent finding with expected revision and an explanation |
| `review_finish` | Seal candidate assessments, concrete evidence requests, frozen investigation identity, coverage and omissions |

`kind=findings` lists carried finding identities and accepts `target=<findingId>`
to read one exact body, anchor, reviewed head, source run and historical
evidence. Those old references explain the prior claim; they are not fresh
evidence references for the current run. `kind=discussion` continues to read
the current card conversation.

`kind=command_output` returns one completed or failed foreground command from
the current run, with a bounded excerpt, exit status and `commandId` (its event
sequence). It defaults to the latest result in the bounded recent history page; supplying that identity rereads
the exact execution. Progress and background launch receipts are not results.
Repeated reads reuse the execution's evidence ref without writing another
record. Separate executions remain distinct even with identical text, and an
identity from a previous run cannot become fresh evidence.

The pr, timeline and checks reads select the current PR by default; an optional
number selects a related PR in the same repository. They reuse persisted data
and hydrate selected missing or outdated detail through existing PR refresh
services. Responses and retained excerpts expose cache/source times, missing
sections, refresh failure and the observed head. An unchanged conditional PR
response cannot repair an unavailable discussion section; that selected gap
uses the existing full-detail refresh. Failed refreshes retain cached evidence.
The current PR's code coordinates and diff remain frozen to its ReviewRun.
`kind=thread` starts from a timeline event's `threadId` and optionally a
same-repository PR number. It returns bounded text and an evidence reference per
page. The first page returns `threadVersion`; subsequent pages must present
that version, and a changed thread is rejected rather than joining pages from
different conversations. The reviewer reads all relevant pages before treating
a comment or reply as a candidate lead. Thread text remains untrusted and its
capture does not prove the claimed issue or current code version.
Checks for a separately observed head do not establish checks for the reviewed
head. The issue read prefers a known cached PR or issue before its existing
exact remote preview fallback. None of these reads performs repository-wide
discovery.

The commit_prs read resolves up to ten selected full commit IDs within the
backend-bound monitored repository. It reuses durable associations, checks
local commit metadata for unresolved IDs and calls the existing GitHub lookup
only when the shared negative-result retry policy permits it. Returned mapping
evidence distinguishes a checked lack of a single merged PR from failed or
unavailable lookup, and exposes cache persistence failures. Commit-title PR
suffixes never establish this association. The read retains per-item evidence
in one run update and cannot change frozen review coordinates.

The diff read also accepts a related same-repository PR number. Omitted/current
numbers retain the frozen reviewed diff and its finding anchors. Related reads
reuse the default PR diff cache, hydrating a missing artifact through the
existing refresh service. The first file-list page returns a content
fingerprint; later pages and selected paths must present it and fail if the
cache changes or disappears. Explicit refresh applies only to an initial
related file-list read. Related patch pages are bounded Unicode text fragments
retained exactly as evidence, with no current-review anchor IDs. The observed
PR head is separate metadata: cached patches have no verified revision pair,
fetch time or completeness, and cannot certify the final merged change.

These are fixed application operations, not a model-controlled registry.
Tool declarations carry strict JSON schemas and bounded fields. Codex can retain
these schemas across review runs, so `candidates`, `answers`, and `validations`
do not encode a per-run array maximum; runtime admission applies the current
candidate cap. `newCandidates` remains capped at thirty per validation pass.
The backend validates every call again. Managed prompts request tool use, but ByteQuay never assumes
that a prompt guarantees compliance. Tool-call replay is idempotent; updates
return stable IDs/revisions and conflicts return the current revision.
Every write tool requires a bounded `operationKey`, unique within its bound
run and tool operation. Persist the key, normalized payload digest and result
in the same transaction as the write. Identical replay returns the saved
result; reusing a key with different content is a conflict. The agent reuses
the key when retrying the same operation and chooses a new key for a genuine
new write. Connection-local MCP JSON-RPC IDs are not durable idempotency keys.
This guarantees replay for the same key, not provider exactly-once execution.

Root-only access is a provider gate, not a prompt claim. Probe child-tool
inheritance and caller identity with the actual provider. Where authenticated
request metadata identifies the native caller, bind every review mutation to
the active root thread and turn; a child's shared task token grants only the
child-safe read inventory. Where the broker cannot distinguish a root from a
child carrying the same task token, enforce the root-only boundary in the
provider adapter and deny native child approvals before any root permission
shortcut. Use the no-child review launch profile if that boundary cannot be
verified. Ordinary tasks retain their capabilities. Process-wide MCP
configuration alone does not establish caller identity.

That boundary is proved for the Claude lane. A probe of the Claude CLI with a
stand-in MCP write tool showed that a read-only `Explore` child inherits every
MCP tool and can call a write tool, that the MCP request carries only a tool-use
identifier and so cannot identify its caller, and that the `PreToolUse` hook
input carries `agent_id` and `agent_type` for a child's tool call and neither
for the root's. An attended Claude review that owns its worktree therefore
installs a root-only child gate: an owner-only script in the task's provider
directory, registered as a `PreToolUse` hook for the Agent and Task tools and
every ByteQuay MCP tool. The gate parses the hook input with the system
`plutil`, refuses a call it cannot read, starts only `Explore` children, and
lets a child call only the ByteQuay tools marked child-callable:
`review_get_context` and `codegraph_explore`. Every tool that saves or changes
review state stays with the root agent, including tools added later, which
are root-only until marked otherwise. The approval layer applies the same rule,
and the driver offers the Agent tool only on a CLI release at or above the one
whose hook input was verified and only when child text is forwarded. Children
are for searching and exploring, as the shared guidance describes; they inherit
the review session's sandbox and command review.

The Claude review profile selects only the fixed native file, shell, source,
and background-command output/stop tools used for investigation, plus Agent
under the gate above. It disables slash commands and refuses Skill, Workflow
and SendMessage before approval, and refuses Agent and Task wherever the gate
is absent: unattended, text-only and read-only sessions. Other native
delegation, resumed children, and newly introduced provider workflow tools are
therefore not inherited from the ordinary task profile.

Codex separates child-readable context from root reporting. From Codex 0.156.1,
`PreToolUse` supplies `agent_id` to distinguish
child calls. The app-server `thread/start` and `thread/resume` config sets the
runtime-only `bypass_hook_trust=true` override for this isolated review session;
the Codex process launch flag alone does not activate the hook in an app-server
thread. Before each review turn, ByteQuay verifies that its review hook is the
sole discovered hook and that effective MCP isolation still holds. Hook discovery
may still label it untrusted because that listing does not include the thread's
runtime override. On successful execution, the hook allows a child only
`review_get_context` and optional `codegraph_explore` and refuses shell,
edit, save, other MCP and delegation calls. A Codex child explores through
those bounded tools. In a guarded review, the task MCP endpoint advertises
only those child-safe reads; ByteQuay registers root-only review operations as
App Server dynamic tools and binds each call to the root thread and exact active
turn. Native child approval requests
are refused before any root-only file-grant shortcut. This is a layered tool
boundary in the shared workspace-write sandbox, not filesystem isolation:
Codex 0.156.1 may continue a tool call if its `PreToolUse` hook fails or times
out. V2 role selection is not a reliable gate, so the policy checks caller
identity rather than trusting an explorer role label. Unsupported versions use
the no-child review profile. A failed hook or isolation verification on an
otherwise supported version aborts that review session rather than relaunching
with weaker settings. The projection reports nested agents as available only
when the provider gate is active. Initial and unsealed automatic assignment requests explicitly ask for up to two
concurrent read-only subagents on independent questions while the root continues.
This satisfies a native policy requiring delegation in the request; it does not
bypass provider capability gates or guarantee a useful split. Repair reuses prior
work, and a sealed assignment ends without launching more children.
In either lane, the root agent rereads
decisive code, validates child conclusions and owns all review writes.

A new guarded Codex review stores session contract version 2 for the dynamic
root tools. Codex cannot add those tool definitions to an already saved thread:
an existing version 1 review resumes with its original MCP tool layout and the
no-child profile. A review without a supported child gate also uses version 1.
Resume accepts only the matching saved contract version and tool layout; a
version 2 review fails clearly if the installed Codex version no longer supports
its child gate.

A new inline finding has this logical shape:

```json
{
  "operationKey": "finding-supplied-keys-1",
  "title": "Forward the supplied encryption keys",
  "body": "Explain the defect, its triggering condition and consequence.",
  "validation": "The delegation path discards the caller's keys; no default or downstream owner restores them.",
  "startAnchor": "A42",
  "endAnchor": "A44",
  "evidence": [
    {"ref": "E17", "explanation": "Explain how this source or result supports the finding."}
  ]
}
```

`operationKey`, `title`, `body`, `validation`, `startAnchor`, and nonempty `evidence` are required;
`endAnchor` is optional for a single line, and a defect may add a
[`reproduction`](#reproduction). A finding about the whole pull
request, such as a commit message or the description, has no lines: it passes
`"scope": "pull_request"` instead of anchors, carries no severity, because a
defect needs the lines it is in, and no `suggestion` block, because there are no
lines to replace; it gives its proposed wording as text. A revision keeps the
scope it was saved with. Such a finding is stored without an anchor, is never
outdated by moved lines, shows as a whole-pull-request card with no code, and
when added to the review joins the review's own text, which the user edits
before submitting, instead of becoming an inline comment. Priority may use the existing
product vocabulary but is not required to locate or retain a finding. IDs in
this example are illustrative; only IDs returned for the bound run are valid.
The model does not supply GitHub request parameters, path/side/line mappings,
commit SHA for publication, or publication authority.

`review_save_investigation` seals a bounded change map, distinct paths from the
frozen scope, captured evidence IDs, and candidate hypotheses. A run permits
thirty new hypotheses plus one slot for each finding originating in a prior run,
including resolved or withdrawn findings so its capacity cannot shrink during
the run. The tool schema stays stable while runtime admission enforces this cap.
The optional `verification` handoff records a bounded recipe (at most 4,000
characters) and up to ten `commandEvidenceRefs` from commands captured in this
run. It carries exact successful commands, environment and temporary build
adjustments, completed checks and limitations. Source-only or missing references
cannot establish a completed check. Evidence collection retains the prior handoff
when omitted and may replace it with updated setup; validation cannot edit it.
A new run starts without this handoff. The recipe is untrusted evidence, not an
automatically executed script or permission grant. Validation checks the code,
experiment files, environment and purpose before reusing a result and repeats
checks whose inputs changed or whose evidence does not answer the candidate.

Only an investigation or evidence-collection assignment can call it. The
summary must account for every actual commit first. Subsequent collection
preserves candidate text and one-based numbering, appends hypotheses requested
by validation, and may append new discoveries. Its `answers` account for each
requested candidate: the check attempted, returned evidence references, and any
limitation. An answer without evidence must explain why it is unavailable.

A validation assignment cannot rewrite its sealed input. `review_finish` names
that investigation's step number and assesses every candidate with an outcome,
conclusion, evidence references, and counterevidence considered. The conclusion
separates the expected contract's basis from the observed behavior and notes
when either remains uncertain:

- `SUPPORTED` names the exact saved finding ID and revision. Its references
  include every artifact cited by that finding.
- `REJECTED` explains concrete disproof and cites its evidence.
- `NEEDS_EVIDENCE` identifies the missing proposition, the specific next check,
  and how its possible results would settle the claim.
- `UNRESOLVED` explains why it cannot presently be settled.

All outcomes except `UNRESOLVED` require captured references. These requirements
make a verdict inspectable; they cannot determine whether its reasoning is true.
Missing fields produce recoverable tool errors without advancing the assignment.
Newly discovered hypotheses are submitted in `newCandidates`, numbered after
existing hypotheses, with `NEEDS_EVIDENCE` assessments. ByteQuay sends them to
collection before another validation. Zero-candidate investigations still get
validation to check the understanding, scope and relevant behavior.

During an active run, new findings are provisional and can be saved only by the
assigned validator, with an explicit verification explanation and diff-bound
evidence. Final validation accounts for every new non-withdrawn finding. Multiple
candidates may map to the same finding for a shared root cause. After a run is
accepted, user follow-ups can save additional validated findings using the same
tool, frozen anchors, evidence requirements, deduplication and per-run limit.
These additions do not rewrite the sealed validation or claim another full pass.
Stopped, failed and incomplete runs require Resume or Update before adding
findings from discussion. Existing findings remain replyable and revisable in
user discussions; those turns cannot advance a review assignment or declare a
run complete.

The summary contains:

- what the PR is trying to achieve, in simple words;
- how each actual commit supports that intention: its design, implementation,
  and resulting behavior, using IDs from the frozen scope.

Critical issues, bugs, and implementation defects belong only in **Findings**.
Concrete optional code improvements also belong there, labeled as suggestions
without a defect severity. When an exact replacement is supported by evidence,
include a GitHub fenced `suggestion` block in the finding body, anchored to the
current-side lines it replaces. Use the existing line/range mapping and
suggestion-diff renderer; the fence contains replacement code only. Do not put
suggestions in intention or commit explanations. The editable outgoing draft
preserves the block and selected range. Publication still requires a direct
user action.

Before the first save, the agent rereads each finding body as a publishable
comment: specific claim, reachable trigger, consequence, and a brief real case
when it helps. It retains material conditions but moves a reproducing test into
the finding's reproduction, and other setup, commands, logs and source
provenance into evidence. The backend
rejects a regular body over 120 prose words or a `Nit:` body over 35, excluding
fenced code, with a recoverable instruction to shorten it before
storage. The existing 6000-character field bound remains. An `**Example**`
heading is optional; no test code is required in the outgoing body. Evidence
distinguishes executed behavior from source tracing. Replies follow the same
clear claim/condition style without a separate length cap.

When validation supports no defect and no suggestion, the review reports up to
five nits instead of an empty result: small, concrete polish in test writing,
code simplification, naming or comments, a commit subject or body, or the PR
description. Nit candidates are collected during investigation, because a
candidate added during validation requires another investigation round. They
become severity-free findings titled `Nit:` only when no other finding is
supported; otherwise validation rejects them as held back. A nit about a commit
message or the PR description is a whole-pull-request finding and names its real
subject first. A review with no true nit still reports zero findings; nits are
never padded.

Evidence stays with the finding it supports, distinguishing executed checks
from source inspection and reported CI results. `review_finish` still records
machine coverage and omissions for the run status, plus optional `warnings` for
unavailable verification. Warnings are stored with coverage and shown in the
existing PR intention card; they do not create a separate card. A warning
reports only a check that could not run. A caveat about how strong a piece of
evidence is, such as a rough timing measurement, stays with the candidate
conclusion or finding evidence it qualifies, so the card never carries agent
commentary about its own method.
The summary tool requests only purpose and commit explanations. Retained stored
fields and discussions are read without generating replacement prose for
removed sections.

Every saved card and finding describes the pull request. Scope omissions remain run metadata; outgoing findings do not contain
agent autobiography or tool/environment commentary. An attended review is
instructed to read from the network and never write to it: it fetches Git objects and remote-tracking
references, runs read-only `git` and `gh` commands when existing CLI
authentication permits, and fetches or searches documentation and package registries. The agent
still builds and runs the tests that exercise the change against the local
dependency caches first; both providers may write the same validated Maven,
Gradle, and npm cache roots.
Only a failed build shows a dependency is missing.
The caches supply third-party dependencies only. The project's own modules come
from the worktree, because a cached build of one, such as a Maven SNAPSHOT a
different checkout installed, may hold other code under the same version. When
a build or test compile fails on a missing or outdated project module, the
agent builds that module from the worktree in the same invocation, without
installing it into the shared cache, and retries before it narrows to a single
test.
The tests that exercise the change are the ones the pull request adds or
changes and the existing tests that drive the changed code end to end, narrowed
to the relevant classes or methods. A test counts as unavailable only after it
was run and stopped by something the worktree cannot fix; a green CI check is
reported evidence and does not replace a test that can run in the worktree.

Validate commit/finding references against the run. Incremental review
updates the overall explanation and identifies what the new commits changed;
it does not replace the summary with only a delta changelog.

### Reproduction

A reproduction is a test that shows the behavior a claim is about. ByteQuay
records it from the worktree and the recorded command rather than from the
agent's account of them, so the user can read it first and run it themselves.
Two places carry one:

- **A defect finding**, with a test that fails because of the defect. When the
  agent's own test reproduces a defect, the finding carries it. Suggestions,
  nits and whole-pull-request findings have none; their controls, source
  traces and the output of existing tests stay in evidence.
- **A discussion reply** through `review_reply` or
  `reply_local_review_comment`, with a test that passes or fails. When the user
  asks the agent to prove, check or explain a behavior, the agent answers with
  a test whenever one can show it, and the reply states what the test shows.
  Clarifications of a development task's learning cards stay read-only and
  carry none.

The review policy holds the test to these rules:

- It is one new test in one new or existing test file, written in the module's
  own framework and style. It creates its data with helpers that file or the
  module's test fixtures already provide and adds the imports it uses. Pasted
  at the recorded location, it runs with no other change.
- Its name states the behavior it checks in the module's naming style, such as
  `testPartitionNullCountUnknownWithDeletionVectors`, never `testRepro` or
  `test1`.
- Its assertions state the correct behavior. A failing test shows a defect and
  can stay as the regression test once the defect is fixed; a passing test
  shows that the behavior is correct.
- A comment in the language's own syntax labels each decisive line with what
  happens at the tested commit. A failing line gives both results as
  `Fails: expected <expected>, actual <actual>`, where either side may be a
  value, `success`, or an error, such as `// Fails: expected NULL, actual 1`
  or `// Fails: expected success, actual IllegalStateException "…"`. A line
  that passes says `Passes:` and what it shows. Example code in a finding body
  or reply uses the same labels.

`review_save_finding`, `review_reply` and `reply_local_review_comment` take the
same optional object:

```json
"reproduction": {
  "testPath": "plugin/trino-delta-lake/src/test/java/io/trino/plugin/deltalake/TestDeltaLakeConnectorTest.java",
  "container": "class TestDeltaLakeConnectorTest",
  "testName": "testPartitionNullCountUnknownWithDeletionVectors",
  "command": "./mvnw test -pl plugin/trino-delta-lake -Dtest=TestDeltaLakeConnectorTest#testPartitionNullCountUnknownWithDeletionVectors"
}
```

`container` says where the test goes: its class, `describe` block, module or
package. `command` runs only this test from the repository root, exactly as the
agent ran it in this turn. ByteQuay reads the run's exit code and output from
the provider's recorded events and the test from the worktree, as
[Spec check](spec-check.md) does, and compares the worktree with the commit it
has checked out. It refuses, with a recoverable error:

- on a finding: one without a severity, a run that passed, or a checked-out
  commit other than the finding's reviewed commit;
- a command with `cd`, pipes, redirects or `&&`, or one that does not contain
  `testName`;
- a command whose latest completed run in this turn is missing or shows a
  build failure or no test run in its output;
- a `testPath` that is not a file in the worktree, whose change against the
  checked-out commit removes or edits a line, whose added lines do not contain
  `testName`, or whose added lines exceed 12 KiB.

The stored reproduction keeps those four fields, the checked-out commit, the
test file's added lines with their line numbers, the exit code, the last 8 KiB
of output, and every other worktree file that differs from that commit,
ignored files excepted. That list tells the user when the test alone is not
enough, such as a run with a fix the user asked for or with test edits the user
saved in Code & Tests. Before the final run, the agent restores its own
experiment edits that the test does not need and runs any control; it keeps the
user's edits and the changes the user asked for. It saves right after the final
run, while the test is still in the worktree. A finding revision that passes no
reproduction keeps the previous one, which names the commit it ran at.

The finding card shows a **Reproduction** disclosure under the body, open by
default, and a reply shows the same block under its text. The block shows a
failed or passed icon with the commit, the location as `container` and path,
each added block with its line numbers and a copy button, and the command with
a copy button. Other changed files appear as a warning icon with their count,
listed on hover. The output is folded. On a finding, the validation explanation
follows as its own line, stored on the finding rather than appended to the
first evidence explanation. Evidence stays folded; a source read shows its path
and lines, with its provenance in the hover title. **Copy conversation** and
the **Request fix** handoff include reproductions. Publication is unchanged:
**Add to my review** copies only the body, and the user pastes the test into
their comment when they want it.

### Missing or incorrect output

Return field-specific recoverable errors without saving malformed output.
Each assignment has one automatic continuation for a missing report or a failed/interrupted
provider turn whose delivery is known. Correcting investigation does not consume validation's
continuation. Recovery retains the same run, conversation, worktree, frozen head, summary, findings,
and evidence; it does not fetch or synchronize a new head. A repeated failure ends with an honest
failure reason rather than an unbounded model loop or a request to click Retry.

Repair retries the same numbered assignment and consumes no evidence round. The existing
run-level repair flag records that a correction occurred; it is not a budget. Sealed reports stay
intact during transport recovery: the continuation ends without resubmitting a report already saved.
Operation replay keys are scoped to the numbered assignment. A sealed validation invalidated by a
changed finding receives a new assignment identity, already marked as its correction attempt, so
replay cannot return the stale result or create an endless correction chain.

Each assignment is persisted before launch and has a deterministic key
`review-step:<runId>:<number>`, with a repair suffix for that attempt. The
coordinator reattaches an already accepted turn by that key after an interrupted
association write. It advances only after the matching turn has completed and
the existing task runtime is idle. The workflow continues a known failed turn only after runtime
cleanup, with no cancellation request and no authentication failure. Unknown delivery remains
non-retryable. A launch that throws before accepting any turn has no lifecycle event to drive a
later attempt, so ByteQuay retries that unsent launch once using the same key. An already accepted
turn is reattached instead. Authentication, incompatible-provider and conflicting-task refusals do
not retry. Stop and explicit Resume retain their existing semantics. No additional task lifecycle
states are introduced.

Every tool closure captures the trusted accepted turn at registration. Calls
must still belong to that active, uncancelled turn and its exact current
assignment. An earlier turn cannot gain the next step's permissions. User
question turns bind to their selected discussion and its assigned run; generic user
turns have discussion privileges only. Tools and orchestration share one review
lock. The review permission profile remains enabled even when no active turn
can receive a reporting assignment.

A valid `NEEDS_EVIDENCE` assessment schedules collection if fewer than two
collection assignments have run. Collection answers produce a new sealed
investigation; all candidates must be validated against it again. Unavailable
evidence may lead to `UNRESOLVED`; requests that exhaust the budget end the
attempt, shown as **Could not finish** with an explicit explanation. Neither advances the baseline
or turns an uncertain claim into a supported finding. Explicit unreviewed scope
without another actionable request also ends incomplete and pauses follow-up.

`review_finish` neither completes the task nor authorizes publication. Complete
coverage requires a complete distinct commit summary, every required changed
file, and supported or rejected assessments for all candidates. The backend
rechecks the investigation identity and supported finding revisions at run
acceptance. Missing scope and unsettled hypotheses require complete=false and
omissions. Tests that stay unavailable after the agent tried to make them
runnable, because of missing
credentials, external services, unobtainable third-party dependencies, native
denials, or another limit the worktree cannot fix, use `warnings` that name
the command that was run, the error that stopped it, and the recovery that was
tried. `review_finish` enforces the attempt: each warning carries `text` and
`evidenceRefs`, at least one ref must be captured command output from this run,
and a warning without one is refused. The stored warning keeps its refs in its
text, so the card shows which command output backs it. Evidence-strength
caveats are not warnings. Warnings do not prevent
complete=true with empty omissions when every required file and candidate has
been assessed. A warning cannot replace evidence needed to support or reject a
candidate. A complete zero-finding result is explicit.

A run advances the successful reviewed-head cursor only after the validation
turn settles and complete=true with no omissions. Partial, failed, stopped,
and incomplete runs never advance it. Ignore previously accepted runs with
incomplete coverage or missing commit explanations when choosing a baseline.
Findings from unfinished/failed runs remain provisional.

## 7. Discussion and progress

**Ask agent** includes the selected summary-card/finding identity and revision,
reviewed code version, user message, and relevant discussion context. A summary
card uses its source run; a current finding card uses the latest frozen review
run. When a finding is outdated, the question explicitly asks the agent to
reassess its claim against the current base and head before standing behind it. It does
not dump every PR comment into every prompt. Idle input starts a turn; busy
input goes into the existing durable queue with a visible queued status.
Route answers back to the selected discussion through `review_reply`; do not
guess a target from prose or scatter one final response across all cards.
A reply may carry a [reproduction](#reproduction).
When a follow-up requests review findings, save each supported code-specific
claim as a private finding anchored to the relevant frozen diff lines, including
optional code or test improvements. This also applies to requests entered in
the main task composer or an intention/commit discussion. Keep the actual
recommendations in Findings, with only a brief acknowledgment in the originating
discussion; a summary reply is not a substitute for a finding.
The backend permits summary-card replies only when the accepted turn is bound
to a user question on that exact card. General task-composer turns cannot append
summary replies or replace the intention/commit summary. They can save findings
under the accepted-run rules above and answer in the task conversation. Summary
edits require a review assignment or an explicit summary-card discussion. These
checks use saved input identity, never keywords or inferred meaning in prose.

Current summary card identities include the run ID and `purpose` or
`commit:<full-sha>`. Retained discussions on `combined-result`,
`critical-issues`, `coverage`, `evidence`, or `limitations` keep their original
target and remain replyable under Earlier discussions. Validate both the run's review ownership and the
selected commit before accepting input. Persist this target with the message
and carry it through the existing reply tool, so one commit's discussion never
appears in another card or run. Existing unscoped summary discussion belongs
to the PR-purpose card for its recorded run.

**Steer now** sends guidance to the matching active turn only when supported.
**Run next** queues a later turn and is disabled between assignment turns.
While a review appears busy, the owning view periodically re-reads its durable
review, turn, and recent conversation state in case a stream update was missed.
Rejected actions also refresh that state while preserving the draft and original
error; they never resend the message automatically.
Preserve existing failure/uncertain-delivery
behavior; never create concurrent turns. Paused queued messages keep their
original target/version. Before a resumed message executes after code or
finding changes, provide both versions or ask for revalidation; never silently
pretend the user asked about the new version.

The agent may explain, revise, or withdraw its finding; the user may resolve
it locally and explicitly reopen it. Keep the discussion and the reason.
An agent update may not undo a user resolution, erase a user message, or
overwrite edited outgoing text. A later run rechecking a resolved finding
keeps that decision unless the user explicitly reopens it.

There is no mandatory summary-approval gate. Users can read the summary,
discuss it until comfortable, or inspect findings immediately.

**Copy conversation** on a finding or local review thread copies a self-contained
Markdown snapshot for pasting into another agent. It retains the repository and
PR or source task, recorded code location and revision, current finding and
state, retained evidence and verification limitations, and every saved message
in that discussion with authors and target revisions. Historical text is quoted
as context; it grants no new execution or publication authority. Copying uses
the displayed saved data, includes folded content, and starts no agent or GitHub
operation. Later replies require another copy. The existing **Request fix**
handoff includes the same finding snapshot after the user's instruction, so
follow-up clarifications reach the original developer too.

Classify each accepted input as a review-run request or a targeted discussion.
A discussion turn replies to its recorded target and may revise that summary
or finding; it does not need to resubmit the entire PR review or call
`review_finish`, and it cannot advance the successful-head cursor. Bind its
tools to the summary target's source run or the current finding target's latest
frozen run, with that run's permitted revisions. If the
worktree has advanced, retrieve old source through the frozen snapshot
instead of claiming the current working files are the version under debate.

Activity uses the existing normalized event stream and replay rather than a
second logging pipeline. Tasks and review Activity render the same
`AgentConversation`, composer, queue, live activity, and history-scroll
implementation. The review adapter supplies review-scoped actions and actual
provider capabilities, while the shared session owns bounded replay and
connection state. Show compact status, elapsed time, and latest
observed activity; no invented percent complete or hidden reasoning. Render
only supported provider features. Review-scoped HTTP/IPC delegates to core
services while validating review ownership; do not weaken `getInteractive`
guards just to mount the entire interactive Tasks route for an internal task.

Backend progression subscribes to the existing durable task terminal events
and reconciles the owning review only after core settlement/quiescence, not
on a raw provider completion message. Startup reconciles open reviews against
persisted run/turn/sync facts and restores their subscriptions; PR-refresh
notifications also invoke the same idempotent reconcile operation. Normal
review reads may reconcile opportunistically but are not the only trigger.
This drives current-run completion repair and Finished with no
renderer present, no new agent completion loop, and no separate polling
scheduler. Duplicate events and restart between terminal commit and callback
must be harmless.

Run creation and reconciliation serialize on the review. If startup ends before
the run records its turn, reconciliation looks up the original request's
idempotency key and restores the accepted turn link. A launch with no accepted
turn is retried automatically once; a persistent launch failure ends with its reason.
**Resume** remains for a user-stopped review. While an explicit start or update is
pending, the control shows that startup is in progress and
preserves the previous result until the new projection arrives.

Questions about the pull request's GitHub comments are not review questions.
The review's task hosts a private thread per comment, answered in a read-only
discussion session from the review's saved results, as
[Review Triage](review-triage.md) §4.6 specifies. Those threads use their own
review-scoped routes and never start a turn, enter the queue, call a review
tool, or change findings, the summary or runs. Automatic catch-up does not wait
for them.

## 8. Incremental review

For a new frozen head `H2`, use the last fully completed reviewed head `H1`,
not the last attempted head or the agent's experimental worktree tip.

If the base identity/tip and merge base are unchanged and `H1` is an ancestor
of `H2`, investigate `H1..H2` plus whatever surrounding code the agent needs.
Recheck prior affected findings, preserve stable finding IDs, and retain
resolved/withdrawn history. The PR context identifies requiredReviewFiles:
paths changed between the successful predecessor's frozen head and this head,
including binary changes and paths reverted out of the overall publication diff.
Unchanged files already assessed in that predecessor can be reused, while
changed dependencies can still require their reinspection.
Assessed files in the new investigation describe work actually performed in
that run, not inherited reads. The PR diff captured for that run remains the
anchor space for comments on its reviewed commit; the incremental delta alone
is not that space.

Use a full review when there is no successful predecessor, ancestry cannot
be proved, history was rewritten, or the base/merge base changed. Full-scope
selection does not authorize unsafe worktree replacement (Section 4). An
unchanged head or identical PR patch does not establish that the reviewed
behavior is unchanged when the base tree or a relevant dependency changed.

Keep a carried finding's anchor and validated commit together while it awaits
reassessment. Do not automatically move its line numbers to a newer diff.
Locating the corresponding code in the new tree does not establish that its
trigger, violated contract, or consequence still holds. The agent reads each
carried OPEN finding's exact prior body and
evidence, includes it as a numbered investigation candidate, then rereads
current source and relevant dependencies. It saves a supported revision with
current-run evidence and a current anchor, advancing the validated commit and
anchor together, or withdraws the claim with an explanation and current-run
evidence references; a user RESOLVED finding remains resolved. A finding from an
earlier reviewed tree may remain pending for analysis of the newer tree. That
status does not block the user's comment on the earlier reviewed commit. An
ambiguous/missing mapping is outdated for the new analysis and needs
re-anchoring before the agent claims support at the new head. If dependency
evidence is unavailable, retain the finding as unresolved rather than treating
its mapped line as proof. Existing outgoing comments retain their original
commit, location, and user-edited text.
If the user reopens a pending finding, explicit Update or Resume starts its
reassessment even when the observed head and base have not changed again.
Disappearance from an agent's new report is not resolution. Ask the agent to
update/withdraw existing IDs;
deduplicate exact repeats and surface possible semantic duplicates instead
of silently merging different findings.

Store completion and advancement of the successful-head cursor atomically.
A new head observed during a run leaves that run and its comments bound to
their original head and marks the view as having newer commits. It waits for
an explicit update after the current run and queued discussions settle safely.
Advancing a review never overwrites the previous version's discussion.

## 9. User-owned publication

All finding/discussion tools are local-only. Publication is available only
through explicit desktop actions using ByteQuay's existing GitHub write path:

- **Resolve locally** changes only the private finding. A comment already
  copied into the user's review remains unchanged and eligible for publication.
- **Add to my review** snapshots the selected finding into the user's local,
  editable pending review. It is not a GitHub pending review creation.
- **Post comment now** publishes only the selected, previewed comment.
- The user's existing final review submission publishes only included,
  eligible draft comments and the review text/event the user selected.

Never include private replies, summary discussions, activity, or an agent
summary automatically. Adding a finding copies its body, location, and reviewed
commit into a user-owned snapshot. Later agent revisions, withdrawal, or local
resolution cannot change its displayed text, inclusion, or publication
eligibility. The user edits or removes the saved comment separately. The agent
cannot include draft items or invoke publication.

The finding/discussion has one stable identity; its outgoing draft snapshot
has a separate stable item identity and body. Editing the draft never edits
the agent's finding. In Changes, show the finding card with its linked draft
state/preview rather than another indistinguishable pending clone. Manual
comments remain ordinary draft items. Both PR views show the same link state.
An unanchored whole-PR suggestion or nit can become editable review prose in
the outgoing review body. That prose is not a finding-backed inline draft and
does not carry a finding-specific send-time reassessment gate. It still belongs
to the user's exact final preview and direct submit action.

Bind each inline comment to the PR commit whose diff the user reviewed.
Snapshot that commit SHA together with the path, side, line/range and body when
adding an agent finding or manual comment to the draft. Publication retains
those identities; it never substitutes today's PR head for an older comment.
A merge into the target branch, a new PR head, an ongoing later review, or an
outdated finding flag does not impose a publication gate on that snapshot.
Source revisions may be shown for the user's recheck without silently rewriting
or blocking their frozen comment. Resolved/withdrawn findings cannot be newly
added; comments already saved from them remain independent.
Adding a new finding still requires an accepted review at its validated commit;
an unfinished later review cannot authorize a new provisional finding.

Derive GitHub path, side, line/range, and commit parameters from the exact
selected snapshot. Validate destination ownership, body, side and positive
line/range values, and the exact review prose/event. Do not fetch or compare
the target branch tip or merge base before publication. Do not remap comments
onto the current head or reject them merely because newer code changed their
original locations. Send the explicit reviewed commit SHA and let GitHub
validate that commit's location or display the comment as outdated. Surface
GitHub's actual validation failures without claiming a successful post.
Resolve the PR's local ID and repository/number together server-side; reject
cross-PR data. GitHub anchors a review at one commit, so split a mixed-commit
draft into one review per commit. Send the user's review body/event once, with
the final group; use the cached current-head group last when it is included.
Preceding groups carry COMMENT and no review body. Persist
each successful group before proceeding so a partial failure cannot repost it.
Publication requests identify the prepared preview, local draft/item IDs,
expected versions, and the user's chosen review text/event, not raw
agent-generated GitHub fields.
Manual comments from a selected commit-range view keep that selected commit
and its line numbers. Never hoist one item's commit SHA over another item's
snapshot or move the line numbers onto the current head.

Atomically reserve the exact local publication payload before sending. Draft
edits, removals, and duplicate clicks must not race that reservation. Private
finding changes do not affect the reserved payload; local resolution cannot
undo a send. Store returned remote IDs. An uncertain result requires reconciliation,
not automatic reposting. Once actually published, local resolution does not
delete or resolve the GitHub thread; remote changes require a separate user
action.

Add an application-prepared final preview for both send routes. It must contain
the exact payload to be reserved: destination PR/account, review body and event,
included item bodies, and frozen commits and locations. Bind the user's
Submit review or Post comment now action to that preview and its draft version.
Immediately before reservation, recheck the local draft version, destination
ownership and draft publication state. If the selected body, commit or location
differs from the prepared preview, refuse the send, show the updated preview,
and require a new direct user action. Remote head/base advancement alone does
not change a frozen payload. Saving a draft or approving an earlier version
grants no send authority.

Single Post comment now and batch Submit review use the same item reservation.
If the finding already has a draft item, preview that user-edited body and
consume that same item on success. The two routes cannot send it twice.
Record a publication-attempt ID, a remote review ID for a batch, and remote
comment IDs where unambiguously established. Missing readback after a known
successful POST is not a retryable send failure. Correlate within the known
publication and full location/content identity; do not guess individual
matches from path/body alone or assume response order. Ambiguous per-comment
mapping stays unresolved without another POST.

After publication, retain the private finding with a Published indication
and known remote link, remove its item from the send queue, and render the
canonical fetched GitHub thread once. Do not retain a second pending clone.
Already published findings cannot be automatically restaged. Private replies
stay private even after their original finding has a remote counterpart.

The local review token exposes no publisher, approval, merge, push, or generic
HTTP operation. Never pass the desktop credential or GitHub token to the
model. Keep attended AUTO, but attach the fixed review capability to a narrow
native-tool approval guard in both adapters. Reject remote-write and
credential-access requests before offering an approval card; do not let an
ordinary AUTO approval enable direct `gh` writes or `git push` for a review
session. A user prompt or PR instruction cannot override that guard. A lane
without the tested guard is not review-capable. Remote reads do not loosen it.
Neither private-review adapter injects ambient GitHub token, `GH_CONFIG_DIR`,
or `SSH_AUTH_SOCK` variables into its agent process; Codex's model-facing
shell also excludes them. This does not prevent same-user tools from using
existing host login files. The Claude review sandbox opens outbound hosts and
replaces the blanket `gh` refusal with outright refusals of the plain
remote-write and credential commands. Native review of every Claude shell
command and the approval-boundary guard cover forms a prefix rule misses,
such as a `gh api` request with a write method. Codex full `AUTO` review has
turn-wide command network access so builds can fetch dependencies; read-only
remote commands no longer need network escalation. This network grant cannot
distinguish dependency reads from remote writes. The review instructions govern
commands that stay within the sandbox, while the approval-boundary guard covers
requests that reach it through ByteQuay's task approval service. The shared
environment filter does not change either
CLI's native reviewer. Text-only and read-only sessions keep command network
disabled; CI uses its separate worktree policy.

The current same-user runtime is not an OS-enforced secret-read boundary: do not claim that prompt
text or environment stripping prevents every ambient-credential bypass.
Verify supported native-tool permission paths and keep this limitation
explicit; do not weaken isolation merely to enable review.

## 10. Failure and retention rules

- Optional graph and ancillary context failures are recoverable; continue
  where evidence permits.
- Invalid local reports do not become findings. An exhausted repair leaves
  partial results visible and the successful cursor unchanged.
- Provider/backend failure uses bounded automatic continuation after verified cleanup and known
  delivery. Unknown delivery, cancellation and required sign-in are not replayed. No automatic
  duplicate turn or publication.
- Stale writes return conflicts; user resolution and user-edited drafts win
  over delayed agent updates.
- Git ownership mismatch, unsafe sync, missing essential source, and unknown
  process quiescence block the affected operation without destroying work.
- Review result readiness does not close its task or clean its worktree.
  Explicit End review uses the existing terminal-result/cleanup barrier.
- Keep user-visible review data after detailed runtime retention. CodeGraph
  deletion remains best effort; no review-specific graph recovery state.

## 11. Initial scope and deferred work

The initial version includes the one PR tab, private summary/findings,
discussion, on-demand context, evidence/anchor validation, background task
activity, capability-based queue/steer, incremental runs, and explicit user
publication. Reuse existing PR refresh cadence for observed head changes.

Defer a fourth column, new global review inbox, cross-PR agent memory,
independent reviewer/verifier multi-agent execution, arbitrary application tool
registry, automatic conflict repair of local changes, automatic commit/push,
provider-history editing, and a new scheduler or graph framework. Do not
describe CodeRabbit's private implementation as an established specification;
this is ByteQuay's design.

## 12. Reusable UI-design prompt

Copy the following prompt into the UI-design task. Supply the current PR
details screenshot and access to the current frontend when available.

> Design the Summary tab for ByteQuay, a macOS desktop app. This is a
> design/prototype task, not backend implementation. Follow
> `docs/design/agent-review.md`; do not change its product behavior.
>
> Preserve the existing shell: left navigation, middle PR list, and the
> right-hand PR pane. Add Summary beside PR #number and Changes in
> both the PR list and development task PR pane. Append the derived run status.
> Do not add a fourth column or put the summary into the GitHub timeline.
>
> The existing PR details page is the visual source of truth. Inspect and
> reuse its cards, avatar/author/time headers, file/line headers, folding,
> icons, buttons, Write/Preview editor, Markdown, typography, spacing, colors,
> borders and state treatments. Use the current components and CSS, not
> approximate substitutes. Inspect PullsScreen, PullOverview, PullChanges,
> MarkdownBody, shared styles and AgentTaskScreen. Keep PR cards and review
> cards visibly consistent in a side-by-side comparison. Add no UI framework,
> icon library or new design system. Match the app's current light appearance;
> do not introduce a review-only dark theme. If source is unavailable, use the
> screenshot and explicitly list assumptions instead of claiming code reuse.
>
> Summary separates a larger green PR intention card, a numbered
> commit list, and Findings below a clear divider. Each numbered item has the
> Git commit title/message above its separate review explanation and discussion
> card; use the existing code-diff comment editor for that card's input. Bugs, critical issues, and concrete inline code suggestions belong
> in Findings. Put each card’s Ask agent discussion directly with it; saved
> discussions on removed report sections are collapsed under Earlier discussions.
> Collect local user comments from both development and review tasks. Reuse review
> comment cards with file/line or range, evidence, private replies, Ask agent,
> Resolve locally, Add to my review and Post comment now. View in Changes
> opens the same finding at its diff location; both views share one thread.
> Do not make users approve the summary before opening findings.
>
> A View in workspace icon opens the exact review with its conversation on the
> left and the PR pane on the right. No Activity subtab remains in the PR page.
> The conversation reuses the existing task transcript: ByteQuay's initial review
> request, agent messages, folded tools/commands/output, approvals and errors.
> Show compact truthful status and elapsed time, with Stop and supported
> Steer/Run next controls. Ask agent starts a turn when idle and visibly queues
> while busy. Unsupported controls are unavailable, never simulated as real
> provider capabilities. No invented completion percentage or hidden reasoning.
> Include Start review, Update review, paused/resume and End review states;
> review completion leaves the discussion open. Distinguish private review
> availability from remote publication eligibility on authored or closed PRs.
>
> Make private versus outgoing content unmistakable using the existing visual
> language. Saved outgoing comments stay independent of local finding
> resolution, withdrawal, and revisions. Publishing requires an explicit user
> action and never includes private discussion. Show running, queued question, approval needed, ready
> with findings, explicit no findings, incomplete/error, resolved/withdrawn,
> draft added, and new-commits/outdated states. Label sample findings and
> commits illustrative; do not fabricate review results for a real PR.
>
> Deliver a clickable, local-only prototype with no network writes, a short
> component/style reuse map, and screenshots at normal and narrow pane widths
> using the app's supported appearance. Verify keyboard focus, long paths/comments,
> overflow, folding and state changes. Keep the original PR and Changes
> views recognizable and unchanged. Present the design for visual approval
> before implementing production UI.

### Private experiments and development commits

PR-owned review tasks remain outside development auto-commit and publication.
Local review of a self-authored development PR uses the same provider review
profile through a policy-only bridge and a durable `localReviewHead` baseline;
see [development delivery](development-delivery.md#2-settings-and-scope).
Review experiments, including new tests, stay uncommitted. Provider native
permission checks reject commits, branch-history changes and remote writes
only inside review sessions. Staging, unstaging, patch application, and Git
reads remain available for experiments; development and other workflow tasks
retain their existing command policies. These are provider permission guards, not arbitrary
shell or operating-system isolation. Claude disables sandbox auto-allow and
adds native commit/history denials. Codex keeps the private-review scope pinned
in both launch and thread configuration. Both native reviewers receive shared
rules permitting bounded verification while prohibiting commits, history moves
and remote publication. Provider prompts that reach ByteQuay retain the
existing review guard and task approval boundary. Review anchors continue to use committed
PR revisions, independent of edited experiment files.
ByteQuay's own controlled remote-head synchronization remains available in
review mode; it uses verified Git operations outside the agent CLI permission
path and never publishes experiment changes.
