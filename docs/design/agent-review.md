# Agent Review

This is the canonical design for the PR-owned Review & Learning workflow. Changes to the
product boundary or the invariants below require explicit approval. Delivery
and verification belong in [the implementation checklist](agent-review-impl-tmp.md),
not in this document. Keep this document as final design content, without a
dated progress log.

## 1. Decision

Agent review is a private, PR-owned workflow over the existing agent service.
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

The PR list and development task PR pane both expose **Review & Learning**
beside **PR #number** and **Changes**. The tab shows independent private cards
in three visually distinct areas: **PR intention**, **Commit implementation**,
and **Findings**. PR intention has a larger green card and stronger top border.
Commit implementation is a native ordered list with visible `1.`, `2.`, `3.`
markers in source order. Each entry shows the commit's title, short SHA, and
original message body first. Its separate blue-header, white-body review card
below contains the agent's explanation and private discussion. Folding that
review card leaves the commit title and message visible. Findings follow a
section divider and use the existing
inline review-comment presentation. Each card retains its own private discussion
through the shared [Local review comments](agent-service.md#local-review-comments)
components. Do not generate combined-result, evidence/test, coverage, or
critical-issues cards. A collapsed **Review details** disclosure shows the
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

The tab can append compact derived **Investigating** or **Validating** status
for a review run, while ordinary discussion uses **Running**. It has no
**Summary & comments** or **Activity** subtab. **View in workspace** opens the
exact review task with the existing navigation, its agent conversation on the
left, and the PR pane on the right. That conversation reuses task transcript,
approval, queue, steering and composer controls. Its live review stream also
keeps the PR pane's cards current, and the pane opens no stream of its own:
Electron keeps one stream per renderer and review, so a second subscription
would silently replace the conversation's. Review routes preserve their
own ownership checks; they do not become interactive development task routes.
Opening and closing these views never starts or cancels execution.

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
**Open discussion** returns to that finding. Outdated findings open their
recorded snapshot, not an unrelated line in the latest diff.

Private review is available for the user's own PRs and closed/merged PRs
when their source can be resolved. Do not reuse the existing remote-review
eligibility predicate to hide it. Remote publication retains existing
account/PR eligibility checks; a private review does not grant new remote
approval or comment permissions.

The global PR view resolves watched workspaces by the PR's monitored repository
identity, including upstream repositories watched through forks. Reuse the
first matching workspace with an open review; if none has one, use the first
matching workspace in saved display order for Start review. An explicitly
opened workspace keeps its own review context. Loading or failed context reads
must not be presented as an unwatched repository.

Workspace **Tasks** shows **Review tasks** as the third section on **Active**,
below **Cherry-pick executions** and **Agent tasks**, listing that workspace's
open and ended reviews. Group reviews by their local calendar start date into
**Today**, **Yesterday**, **Recent 7 days**, **Recent 30 days**, and **Older**.
Each review appears once, empty groups are hidden, and rows are newest first.
A row names the pull request by number and stored title beside its state icon.
Opening a row selects the exact review ID in the PR pane, including an ended
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

### Visual contract

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
  and action buttons; private labels and handlers retain their own authority;
- activity/transcript components and capability-driven controls from Tasks.

Start with `frontend/src/pulls/PullsScreen.tsx`, `PullOverview.tsx`,
`PullChanges.tsx`, `MarkdownBody.tsx`, `frontend/src/agents/AgentTaskScreen.tsx`,
`frontend/src/css/pulls.css`, and the existing shared CSS/tokens. Revalidate
these entry points against the current checkout before implementation.

Reuse appearance separately from action handlers. Existing GitHub Reply,
Resolve, and Submit callbacks must not back private **Ask agent** or
**Resolve locally** actions. Prefer a focused presentational extraction only
where two real callers need it; do not introduce a generic comment framework.

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
| Review | Workspace and logical PR identity, physical repository/head-branch identity, agent task ID, latest completed run, latest observed remote head, follow-up execution enabled/paused, optimistic version, explicit closure |
| Run | Review ID, full/incremental scope, frozen base tip and merge base, head SHA, prior successful reviewed head if any, turn IDs, ordered assignments, latest investigation, accepted result/coverage, terminal outcome |
| Assignment | Consecutive number, investigation/validation/evidence-collection kind, reporting-repair attempt, immutable report when sealed |
| Investigation | Change map, distinct assessed changed-file paths, captured supporting context refs, numbered hypotheses, evidence-request answers, latest validation conclusions |
| Validation | Sealed investigation step number, assessment of every candidate, newly discovered hypotheses, coverage and omissions |
| Summary | Run ID, revision, purpose, actual commit explanations |
| Finding | Stable ID, source run, immutable anchor mapping, title/body, evidence references, current revision, local resolution/withdrawal and reason, revalidation result for later runs |
| Discussion message | Stable ID, review and summary-card/finding target, author, body, target revision/run, correlated task input/turn or reply, delivery outcome |
| Outgoing draft/publication | PR/account-owned draft usable without Agent Review; stable item ID, MANUAL or AGENT_FINDING source, optional finding ID/revision, frozen location, user-editable body/version, inclusion, review body/event, exact publication attempt/payload and returned remote identities |

These are logical records, not a mandate for one table per row. Reuse existing
draft and publication mechanisms where they satisfy durability and identity.
Use optimistic versions and operation idempotency at every local mutation.
Enforce one open review per workspace/logical PR; duplicate Start actions
return it rather than allocate another task/worktree. Replace the ephemeral
pending-comment array with the same durable draft path for manual Add to
review and agent Add to my review. Manual-only review must still work without
an agent review record.

Persist summaries, findings, private discussions, and user drafts as product
data, independently of the task's disposable detailed log. Store only the
bounded evidence excerpts needed to retain a finding's meaning, not another
copy of the entire transcript. Runtime retention must not erase these records.
PR cache eviction must not delete or orphan an active review or unpublished
local work; retain its logical PR identity and required snapshot data.

Derive Running, Waiting for approval, Ready, Stopped, Incomplete, and New
commits from existing task/turn facts and review result facts. Do not add
Reading/Thinking/Testing lifecycle enums or persist UI timers/percentages.
Complete coverage means the recorded scope was reviewed, not that the PR is
guaranteed correct or that every possible test ran.

## 4. Background execution and worktree

Starting review is a direct user action. ByteQuay checks provider readiness,
resolves/fetches the exact PR head and base, and provisions through
[WorktreeService](worktree-service.md). The generated local branch remains
ByteQuay-owned; it maps to the contributor's branch but is not that remote
branch and is never pushed by this workflow. Resolve fork PR heads through
trusted repository/PR metadata, not an agent-supplied shell command or URL.

Create a workflow-owned task and start its first turn with a ByteQuay-built
review request. Display that input as **ByteQuay · Review request**, not as a
message the user typed. Apply the review policy through the managed
instruction channel on initial and resumed sessions. Do not write
`AGENTS.md`, `CLAUDE.md`, repository settings, or global provider settings.
Use the existing attended AUTO sandbox/approval behavior, not CI Autofix's
or cherry-pick's unattended policy. Workflow-owned does not mean unattended.
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

An active review observes remote heads through the existing backend PR-refresh
path and shows when new commits are available. Refresh does not move the checkout
or start another review. Only an explicit user **Update review** or **Resume**
action may synchronize it. Fetch the newest head for that action; never interrupt
an active investigation or move its worktree beneath it.

Before syncing, require the existing task execution boundary to exclude turn
start, queued start, tools, and cleanup. Verify provider quiescence, generated
branch/repository ownership, expected HEAD, and no open Git operation. Save
uncommitted experiments before moving; require a clean index/worktree including
untracked files at the move itself. An unrelated review-only mutex that
the agent queue can bypass is insufficient.

The core `synchronizeWorktree` callback uses the same durable task version,
task lock, and existing mutation claim as private local-review transitions.
It excludes direct and queued starts throughout stash, move, and restoration,
and releases ownership before launching the next review turn. A review mutex
alone is insufficient because the core queue can start without it. Native
stash operations also hold a repository lock across linked worktrees.
`WorktreeService` itself stays provision/inspect/cleanup and gains no review
lifecycle or generic lock registry.

For an ordinary descendant head, update the verified generated branch with a
guarded fast-forward and verify the resulting HEAD. Unknown local commits are
refused. Keep `TaskWorktree.startCommit` as the original provisioning fact; the
current and completed review heads remain run facts.

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
full-review scope; the explicit **Update review** action authorizes realignment
to the verified remote revision. Experiments use
the same stash-and-restore path. Keep the old review readable until a new run
has completed.

**Stop** cancels the current run, pauses queued/new-head execution, and keeps
the review open for discussion or explicit resume. **End review** closes the
ongoing workflow once execution is quiescent and pending input is handled.
It completes the task, durably closes the review, then requests normal clean
worktree cleanup. Preserve dirty work; retain the generated branch. A PR
being merged/closed does not itself authorize destructive cleanup.

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
one derived index per worktree, optional agent-led queries, synchronization
inside queries, and best-effort index deletion after successful worktree
cleanup. No review-specific graph lifecycle, forced graph use, or extra
refresh tool. Graph failure never fails review execution or invalidates an
otherwise complete review.

## 5. Context, anchors, and evidence

The initial request is small: review purpose, PR identity, frozen scope,
previous review reference where applicable, and available capabilities. The
agent requests additional context as needed:

- PR title, author description, base/head identities and actual commits;
- PR timeline, remote discussions, checks, and previous local findings;
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

Register a fixed set of root-agent-only tools through the existing task MCP
bridge. The authenticated callback supplies task, review, run, and actor;
the model cannot choose them or supply an absolute filesystem path/token.
Context reads may select validated repository-relative paths or returned
reference IDs, never a source root or another task's files. The review
bridge must work when CodeGraph is off or absent. Ordinary tasks do not gain
these tools. Provider-specific registration, resume, cancellation, and
isolation must pass executable tests before enabling the review capability
for that lane; failure does not disable ordinary agent tasks.

| Tool | Purpose |
| --- | --- |
| `review_get_context` | Bounded typed reads of current PR, linked issue/PR, diff, source, evidence, summary, finding, or discussion; supports pagination |
| `review_save_summary` | Save the intention and exactly one explanation for every actual commit |
| `review_save_investigation` | Save the change map, assessed changed files, supporting context refs, and numbered candidate hypotheses |
| `review_save_finding` | Create a finding, or update a returned finding ID with its expected revision |
| `review_reply` | Append a private answer to a summary/finding discussion using its returned target/message identity |
| `review_withdraw_finding` | Withdraw an agent finding with expected revision and an explanation |
| `review_finish` | Seal candidate assessments, concrete evidence requests, frozen investigation identity, coverage and omissions |

These are fixed application operations, not a model-controlled registry.
Tool declarations carry strict JSON schemas and bounded fields; the backend
validates again. Managed prompts request tool use, but ByteQuay never assumes
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
inheritance and caller identity with the actual provider. If the broker cannot
distinguish a root from a child carrying the same task token, use the existing
no-child launch profile for review sessions and advertise subagents as
unavailable there. Ordinary tasks retain their capabilities. Review subagents
can be enabled only after a trustworthy root-only tool boundary is proved;
do not assume process-wide MCP configuration enforces it.

The Claude review profile selects only the fixed native file, shell, source,
and background-command output/stop tools used for investigation. It disables
slash commands and refuses Agent, Task, Skill, Workflow and SendMessage before
approval. Native delegation, resumed children, and newly introduced provider
workflow tools are therefore not inherited from the ordinary task profile.
Codex reviews use their no-child launch profile. Both review projections
advertise subagents as unavailable independently of ordinary task capability.

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
`endAnchor` is optional for a single line. Priority may use the existing
product vocabulary but is not required to locate or retain a finding. IDs in
this example are illustrative; only IDs returned for the bound run are valid.
The model does not supply GitHub request parameters, path/side/line mappings,
commit SHA for publication, or publication authority.

`review_save_investigation` seals a bounded change map, distinct paths from the
frozen scope, captured evidence IDs, and up to thirty candidate hypotheses.
Only an investigation or evidence-collection assignment can call it. The
summary must account for every actual commit first. Subsequent collection
preserves candidate text and one-based numbering, appends hypotheses requested
by validation, and may append new discoveries. Its `answers` account for each
requested candidate: the check attempted, returned evidence references, and any
limitation. An answer without evidence must explain why it is unavailable.

A validation assignment cannot rewrite its sealed input. `review_finish` names
that investigation's step number and assesses every candidate with an outcome,
conclusion, evidence references, and counterevidence considered:

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

New findings are provisional and can be saved only by the assigned validator,
with an explicit verification explanation and diff-bound evidence. Final
validation accounts for every new non-withdrawn finding. Multiple candidates
may map to the same finding for a shared root cause. Existing findings remain
replyable and revisable in user discussions; those turns cannot advance a
review assignment or declare a run complete.

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

Evidence stays with the finding it supports, distinguishing executed checks
from source inspection and reported CI results. `review_finish` still records
machine coverage and omissions for the run status; it does not create a card.
The summary tool requests only purpose and commit explanations. Retained stored
fields and discussions are read without generating replacement prose for
removed sections.

Every saved card and finding describes the pull request. Scope omissions remain run metadata; outgoing findings do not contain
agent autobiography or tool/environment commentary. Review commands have no network access, so the agent builds
and runs the tests that exercise the change offline against the local
dependency caches; only a failed offline build shows a dependency is missing.

Validate commit/finding references against the run. Incremental review
updates the overall explanation and identifies what the new commits changed;
it does not replace the summary with only a delta changelog.

### Missing or incorrect output

Return field-specific recoverable errors without saving malformed output.
The run has one shared reporting-repair allowance for a turn that ends without
its required report. Repair retries the same numbered assignment and consumes
no evidence round. Sealed reports are immutable; only an exact operation replay
is accepted after sealing. Operation replay keys are scoped to the numbered assignment; a repair replays
its earlier successful writes instead of repeating them.

Each assignment is persisted before launch and has a deterministic key
`review-step:<runId>:<number>`, with a repair suffix for that attempt. The
coordinator reattaches an already accepted turn by that key after an interrupted
association write. It advances only after the matching turn has completed and
the existing task runtime is idle. Stop, recovery and explicit Resume reuse
existing task semantics. No additional task lifecycle states are introduced.

Every tool closure captures the trusted accepted turn at registration. Calls
must still belong to that active, uncancelled turn and its exact current
assignment. An earlier turn cannot gain the next step's permissions. User
question turns bind to their source run and selected discussion; generic user
turns have discussion privileges only. Tools and orchestration share one review
lock. The review permission profile remains enabled even when no active turn
can receive a reporting assignment.

A valid `NEEDS_EVIDENCE` assessment schedules collection if fewer than two
collection assignments have run. Collection answers produce a new sealed
investigation; all candidates must be validated against it again. Unavailable
evidence may lead to `UNRESOLVED`; requests that exhaust the budget leave the
run **Incomplete** with an explicit explanation. Neither advances the baseline
or turns an uncertain claim into a supported finding. Explicit unreviewed scope
without another actionable request also ends incomplete and pauses follow-up.

`review_finish` neither completes the task nor authorizes publication. Complete
coverage requires a complete distinct commit summary, every required changed
file, and supported or rejected assessments for all candidates. The backend
rechecks the investigation identity and supported finding revisions at run
acceptance. Missing scope and unsettled hypotheses require complete=false and
omissions. A complete zero-finding result is explicit.

A run advances the successful reviewed-head cursor only after the validation
turn settles and complete=true with no omissions. Partial, failed, stopped,
and incomplete runs never advance it. Ignore previously accepted runs with
incomplete coverage or missing commit explanations when choosing a baseline.
Findings from unfinished/failed runs remain provisional.

## 7. Discussion and progress

**Ask agent** includes the selected summary-card/finding identity and revision,
reviewed code version, user message, and relevant discussion context. It does
not dump every PR comment into every prompt. Idle input starts a turn; busy
input goes into the existing durable queue with a visible queued status.
Route answers back to the selected discussion through `review_reply`; do not
guess a target from prose or scatter one final response across all cards.

Current summary card identities include the run ID and `purpose` or
`commit:<full-sha>`. Retained discussions on `combined-result`,
`critical-issues`, `coverage`, `evidence`, or `limitations` keep their original
target and remain replyable under Earlier discussions. Validate both the run's review ownership and the
selected commit before accepting input. Persist this target with the message
and carry it through the existing reply tool, so one commit's discussion never
appears in another card or run. Existing unscoped summary discussion belongs
to the PR-purpose card for its recorded run.

**Steer now** sends guidance to the matching active turn only when supported.
**Run next** queues a later turn. Preserve existing failure/uncertain-delivery
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

Classify each accepted input as a review-run request or a targeted discussion.
A discussion turn replies to its recorded target and may revise that summary
or finding; it does not need to resubmit the entire PR review or call
`review_finish`, and it cannot advance the successful-head cursor. Bind its
tools to that target's source run and current permitted revisions. If the
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
This drives current-run completion repair and Ready with no
renderer present, no new agent completion loop, and no separate polling
scheduler. Duplicate events and restart between terminal commit and callback
must be harmless.

Run creation and reconciliation serialize on the review. If startup ends before
the run records its turn, reconciliation looks up the original request's
idempotency key and restores the accepted turn link. An attempt with no accepted
turn becomes failed and paused; Resume retries on the same review and task.
While Resume is pending, the control shows that startup is in progress and
preserves the previous result until the new projection arrives.

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
that run, not inherited reads. The overall PR diff remains the publication
anchor space; the incremental delta alone is not that space.

Use a full review when there is no successful predecessor, ancestry cannot
be proved, history was rewritten, or the base/merge base changed. Full-scope
selection does not authorize unsafe worktree replacement (Section 4).

Reuse unchanged finding locations only when deterministic mapping and source
validation prove them. An ambiguous/missing mapping is outdated and not
publishable until re-anchored and revalidated. Disappearance from an agent's
new report is not resolution. Ask the agent to update/withdraw existing IDs;
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

- **Resolve locally** excludes the finding and any linked draft from future
  publication while retaining the user's edited draft text.
- **Add to my review** snapshots the selected finding into the user's local,
  editable pending review. It is not a GitHub pending review creation.
- **Post comment now** publishes only the selected, previewed comment.
- The user's existing final review submission publishes only included,
  eligible draft comments and the review text/event the user selected.

Never include private replies, summary discussions, activity, or an agent
summary automatically. Agent revisions do not silently rewrite drafts; flag
a changed source finding for the user's recheck. A withdrawn/resolved finding
must be excluded even if it was previously added to a draft. The agent cannot
re-include it or invoke publication.

The finding/discussion has one stable identity; its outgoing draft snapshot
has a separate stable item identity and body. Editing the draft never edits
the agent's finding. In Changes, show the finding card with its linked draft
state/preview rather than another indistinguishable pending clone. Manual
comments remain ordinary draft items. Both PR views show the same link state.

At publication, validate the exact PR/account, current remote head, finding
revision, body, and diff location again. A changed head/revision requires
revalidation and renewed user confirmation, not best-effort line guessing.
Derive GitHub path, side, line/range, and commit parameters in code.
Resolve the PR's local ID and repository/number together server-side; reject
a mixed-PR or mixed-head batch. Apply these guards to manual draft comments
in the same outgoing review as well as promoted agent findings.
Publication requests identify local draft/item IDs, expected versions, and
the user's chosen review text/event, not raw agent-generated GitHub fields.
For a manual comment created in a selected commit-range view, require a
deterministic mapping to the current overall PR publication diff and confirm
that location before inclusion; otherwise keep it local and non-publishable.
Never hoist one item's commit SHA over a mixed-snapshot batch.

Atomically reserve the exact local publication payload before sending. Resolve,
withdraw, edits, and duplicate clicks must not race that reservation. A
finding resolved before reservation is never eligible; while a send is in
flight, show that fact instead of falsely promising that resolution can undo
it. Store returned remote IDs. An uncertain result requires reconciliation,
not automatic reposting. Once actually published, local resolution does not
delete or resolve the GitHub thread; remote changes require a separate user
action.

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
without the tested guard is not review-capable. The current same-user
runtime is not an OS-enforced secret-read boundary: do not claim that prompt
text or environment stripping prevents every ambient-credential bypass.
Verify supported native-tool permission paths and keep this limitation
explicit; do not weaken isolation merely to enable review.

## 10. Failure and retention rules

- Optional graph and ancillary context failures are recoverable; continue
  where evidence permits.
- Invalid local reports do not become findings. An exhausted repair leaves
  partial results visible and the successful cursor unchanged.
- Provider/backend failure uses the existing interruption, replay, and
  uncertain-delivery rules. No automatic duplicate turn or publication.
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
review subagents without a proved root-only tool boundary,
independent reviewer/verifier multi-agent execution, arbitrary application tool
registry, automatic force-push worktree repair, automatic commit/push,
provider-history editing, and a new scheduler or graph framework. Do not
describe CodeRabbit's private implementation as an established specification;
this is ByteQuay's design.

## 12. Reusable UI-design prompt

Copy the following prompt into the UI-design task. Supply the current PR
details screenshot and access to the current frontend when available.

> Design the Review & Learning tab for ByteQuay, a macOS desktop app. This is a
> design/prototype task, not backend implementation. Follow
> `docs/design/agent-review.md`; do not change its product behavior.
>
> Preserve the existing shell: left navigation, middle PR list, and the
> right-hand PR pane. Add Review & Learning beside PR #number and Changes in
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
> Review & Learning separates a larger green PR intention card, a numbered
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
> language. Local resolution/withdrawal excludes a linked draft but preserves
> user edits. Publishing requires an explicit user action and never includes
> private discussion. Show running, queued question, approval needed, ready
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
adds native commit/history denials; Codex escalations reach the ByteQuay guard before
approval, so legitimate non-Git escalations may still ask the user. Review anchors continue to use committed
PR revisions, independent of edited experiment files.
ByteQuay's own controlled remote-head synchronization remains available in
review mode; it uses verified Git operations outside the agent CLI permission
path and never publishes experiment changes.
