# Development task delivery

Status: Approved MVP implemented with fixture and local verification. Settings,
accepted-turn snapshots, root readiness, live continuation ownership, agent commit
and draft publication, existing CI attachment, full check gates, source
synchronization, and ready promotion use the current [agent service](agent-service.md)
and [CI Autofix](ci-autofix-service.md). The small in-process coordinator adds no
overall workflow state machine, delivery table, phase engine, step journal, or
restart/resume support. Unsupported CI evidence retains the draft as described
below; no live-provider/live-PR smoke is claimed.

Track implementation and verification in the temporary
[implementation checklist](development-delivery-impl-tmp.md).

## 1. Who advances the work

**ByteQuay's backend coordinator decides and starts each follow-up.** The
agent executes the requested work; the frontend displays results and accepts
user answers. The agent does not schedule the next workflow step, and closing
the task panel does not stop the backend's running continuation.

| Responsibility | Owner |
| --- | --- |
| Edit, choose local checks, write commit messages, commit | Development agent |
| Check actual worktree, commits, remote branch, and PR | ByteQuay |
| Decide whether a commit/publication follow-up is needed | ByteQuay |
| Push the development revision and create the draft with native tools | Development agent, on ByteQuay's scoped follow-up |
| Watch CI and start repair turns | Existing CI Autofix service, driven by the coordinator |
| Fix CI and make repair commits | CI repair agent |
| Validate and push CI repairs | Existing CI Autofix service |
| Verify green and mark the draft ready | ByteQuay |
| Answer an unresolved question | User; automation waits |

Use one `DevelopmentFollowupCoordinator` in `app`, with ordinary sequential
asynchronous calls to existing services. A small in-memory per-task owner
prevents overlapping continuations. This is execution ownership, not a set of
persisted workflow stages. Keep the existing agent and CI service lifecycles;
do not duplicate them in a new delivery lifecycle.

## 2. Settings and scope

**Settings → Agents → Workflow** has three settings, all on by default:

| Setting | Meaning |
| --- | --- |
| Auto commit | Commit finished development work; ask the agent if Git still reports uncommitted changes. |
| Auto draft PR | Ask the agent to push committed work and create a draft if the task has no matching PR. Update the same open PR on later revisions. |
| Auto CI fix | Attach existing CI Autofix and mark this task's draft ready after verified green. |

Store defaults in the existing `AppSettingsStore`. Copy them into a new task,
allow per-task overrides, and snapshot them when a user turn is accepted.
Generated follow-ups inherit that snapshot. **CI repair agent** defaults to
**Same agent as development**, stored as an absent `ciLaneId`. It uses the
provider already chosen for the development task. Task creation resolves an
explicit task choice first, otherwise the workspace Development setting, then
the global Development setting. CI does not reread those mutable defaults for
an existing task. An explicit CI provider overrides that inheritance; Codex and
Claude Code are eligible when they advertise `CI_AUTOFIX`. This selects the
agent that repairs code; GitHub Actions remains the CI host.
The task's **Automation** control
is separate from its **Auto** permission mode. While a continuation is active,
settings edits are labeled **For future turns**; **Stop automation** stops the
current continuation. Local only supplies all three false for one user turn.

Submitting the request with its visible settings records authority for that
request's generated branch, repository/base, initial push/draft, bounded CI
writes, and ready promotion. There is no second routine publication approval.
Provider approval and authentication checks still apply. Generated text is
not itself a user authorization. Existing tasks do not auto-publish on app
startup or page load; their next explicit submission carries the selection.

**Send to agent** on a task's linked PR diff is another explicit submission.
Its visible action commits and pushes requested edits while keeping draft PRs
draft: the input snapshots `autoCommit=true`, `autoDraftPr=true`, and
`autoCiFix=false`, without changing task defaults or the main composer's Local
only choice. A busy task queues that input with the same settings. This action
requires an existing open PR and keeps feedback private in the task transcript;
it does not post GitHub comments or replace the PR description. Questions ask
for an explanation without edits or release of prior held work. Existing
readiness, unchanged-work, and publication checks still decide whether any
commit or push is necessary.

Only interactive development tasks enter this continuation. CI, cherry-pick,
review-owned tasks, helpers, and child agents cannot recursively start it.
An unchanged read-only turn produces no new commit, PR, or CI run.
When all three settings are off, use ordinary task behavior: do not activate
a continuation or require its readiness report. Git facts may still be read
for display. A failed turn preserves the task with either selection.

Respect independent settings:

- Auto commit off plus dirty files stops before publication, even if Auto
  draft PR is on. Never silently commit or publish only a committed subset.
- Auto draft PR off leaves commits local. Auto CI fix can operate only on an
  already-existing PR that exactly matches the clean local revision.
- Auto CI fix off leaves the published draft as a draft.
- Missing/unsupported CI shows the actual partial result, not full success.
  CI requires an authored PR with a verified workspace head remote and a `CI_AUTOFIX`
  provider. Both Codex and Claude support it. The explicitly selected CI repair
  agent may differ from the development agent. With no explicit CI selection,
  use that task's development provider. An unsupported or unavailable resolved
  provider still fails visibly; do not silently switch providers after failure.
  Fork PRs use the selected repository for repair pushes and the monitored
  upstream repository for checks and base synchronization. Missing upstream
  rerun permission pauses the existing CI run for user action; it does not
  redirect the rerun to the fork or imply green checks.

## 3. User-input gates come before Git decisions

A successful provider turn is not proof that the assignment is finished.
Today both providers can report success when the final message asks the user
to choose the next step. Existing `AgentTurnResult` carries execution outcome,
not that semantic distinction. Files and commits cannot resolve it.

Add one explicit, typed **per-turn readiness report**, delivered by the root
agent through a fixed task-bound local reporting tool:

```text
report_development_result(
    readyForDelivery: false,
    message: "Which implementation should I use?"
)
```

`readyForDelivery: true` means the requested work is complete enough for the
enabled follow-ups and there is no unresolved user decision. False means wait
for the user or honor an explicit hold. It does not change `TurnState` or add
a task/workflow state. Store it on the existing turn/event, so the UI can show
why the agent stopped. A missing/invalid report never implies true: stop the
MVP continuation with a clear failure instead of guessing from final prose.

The report contains only `readyForDelivery` and `message`. The user's accepted
Auto commit, Auto draft PR, and Auto CI fix controls determine which actions
run. A report cannot change those choices, add authority, choose a destination,
or perform a remote write. The provider binding accepts only the current root
turn's report, not a child agent's claim. The report conveys the agent's
understanding; Git/GitHub still prove results.

When an explicit text restriction conflicts with the selected controls, the
agent reports false and explains which Automation control the user needs to
adjust before submitting again. For example, "do not push" with Auto draft PR
on waits for that choice to be turned off; "leave it draft" with Auto CI fix on
waits for Auto CI fix to be turned off. The agent cannot silently stop delivery
at an intermediate step. Auto CI fix includes ready promotion after verified
green; this design has no separate control for repairs without promotion.

Reporting now uses the existing task-local tool transport. The broker validates
and acknowledges a candidate; only the provider's successful current-root
native tool completion produces the authoritative `development_result` event.
Subagents remain available and cannot authorize delivery. Core binds that event
to the accepted ByteQuay turn, checks the native turn/item identity, and stores
the report on the turn atomically with its event through the existing outbox.
Duplicate item events cannot overwrite a newer report. The turn projection
keeps a question visible even when its transcript event is outside loaded history.

Managed instructions require a false report before ending with a blocking
question, and prohibit default commit/publication while such a decision is
unresolved. Use the existing transcript and composer for the question/answer;
a complete native questionnaire/card integration is unnecessary for this MVP.
Current provider adapters do not implement a general native user-question
contract. Unsupported question tools must not be mistaken for a ready result.

The coordinator checks the gate **before any automatic mutation**:

| Turn result | ByteQuay response |
| --- | --- |
| Waiting for user; dirty files exist | Show the question and uncommitted-file count. Do not ask for a commit or publish. |
| Waiting for user; commits exist | Keep the commits. Show Not pushed where applicable. Do not push/create a PR. |
| Waiting for user; PR already exists | Display it; do not newly attach CI or promote it from this turn. Do not undo a completed publication. |
| Tool approval or native input request is still active | The turn has not settled; do not run the checker. |
| User answers | Start a normal user turn. When it finishes, use its fresh readiness report and inspect Git again. |

An explicit answer can release the earlier hold even if the answer turn makes
no additional edits. The already-existing dirty files or unpublished commits
are still pending work. Readiness plus actual pending work decides this; it
does not require another newly changed file just to restart normal progression.

Waiting for an answer is normal conversation, not a failed task and not a
reason to delete it. A question arising inside an automatic commit/publication
turn follows the same rule; stop the continuation and allow the user to reply.
Do not reinterpret old queued instructions as answers to a new question:
pause them until the user resolves the question and explicitly resumes them.

## 4. The after-turn algorithm

The live completion hook in `DefaultAgentTurnService` notifies the coordinator
once for each terminal user-turn outcome, including failure. Only a successful
result **and** verified provider shutdown/quiescence can advance follow-ups.
Provider success can be recorded before process cleanup; cleanup failure must
fail any owned continuation. Failed or uncertain execution also ends it;
release ownership only after the writer is proven stopped. Do not trigger from
transcript replay, opening the task, a completion retry, or an already-terminal
callback.

Reserve the in-memory task owner under the existing task lock before ordinary
queue dispatch, so a simultaneous Send cannot race the checker. Coordinator
Git inspections and continuation waits run outside that lock. Generated turns
reuse the existing serialized provider-launch path through a trusted internal
start carrying the owner; other starts respect it.

The terminal transaction sets the existing task row's
`developmentContinuationUnfinished` marker before exposing admission to another
turn. Under the same task lock, the live completion callback reserves the
in-memory owner before notifying the coordinator. A racing read may store the
terminal result and marker, but cannot reserve the owner or dispatch work.
Only the live completion path notifies `DevelopmentFollowupCoordinator`;
ordinary reads and terminal retries do not create continuations. The app runs
its readiness/queue gate, Git inspection, and commit/publication waits outside
the task lock. `advance` drives at most one commit turn and one publication
turn through `startGeneratedTurn`, verifying each result before continuing.

An optional `DevelopmentTurnOrigin` on a generated turn names its bounded
operation and original user turn. That user-turn ID is the in-memory owner;
there is no delivery ID or step journal. The internal start path carries the
source turn's complete accepted settings, including model, effort, permission,
service tier, attachments, and automation choices. Every generated turn must
report readiness before the coordinator continues under that same selection.

Before an ordinary development provider starts, its accepted hook captures the
starting HEAD, file fingerprint, commit count, frozen base SHA, and exact PR
base/push routing. This also runs outside the task lock; core revalidates the
accepted turn before launching it. The existing unfinished marker covers this
capture interval, clears immediately before ordinary provider launch, and is
set again at continuation reservation. Stop during capture cannot launch the
provider later; backend loss stops the continuation without replaying it.
Generated turns inherit this baseline without recapturing it.

The hook observes the remote ref and matching PRs when publication or CI is
enabled. An unavailable remote baseline does not prevent initial local editing,
but prevents subsequent publication from assuming absence. Read-only turns
without pending work settle locally. A question's ordinary answer or a FIFO
handoff can carry earlier pending work forward even without fresh edits.

The coordinator applies these decisions in order:

```text
after a stopped user development turn:
    if all automation settings off: show facts; return to normal task behavior
    if failed or execution uncertain: fail the MVP continuation
    if readiness report missing/invalid: fail the MVP continuation
    if not readyForDelivery: release ownership; wait for user
    if accepted user work already queued: run it first; check after it ends

    inspect actual Git and the accepted automation selection
    if nothing changed and no explicitly requested pending delivery: finish

    if dirty:
        if auto commit off: show uncommitted work; finish locally
        run one ordinary "commit finished changes" agent turn
        wait for its completion, gate report, and verified shutdown
        if it needs user input: release ownership; wait for user
        inspect again; if still dirty/invalid: fail

    inspect remote branch and the exact matching PR
    if clean local revision needs publication and auto draft PR on:
        run one ordinary "push this revision / create draft if absent" turn
        wait for its completion, gate report, and verified shutdown
        if it needs user input: release ownership; wait for user
        verify actual remote SHA and PR identity; if wrong/unknown: fail

    if no matching published PR: show local result; finish
    if auto CI fix off: show PR; finish
    if selected CI provider unavailable/unsupported: fail with PR and reason

    start/reuse the existing CI run for this verified revision
    drive its existing watch/fix/push loop within its limits
    on green: wait for complete current PR/head/base/check evidence
    synchronize published CI repairs back into the clean source worktree
    recheck current PR/head/base/checks and clean synchronized source
    if PR is draft: mark ready once; verify afterward
    show the result; release ownership; keep the source task open
```

This is ordinary control flow, not saved `COMMITTING → PUBLISHING → WATCHING`
transitions. The coordinator waits asynchronously on each generated turn;
its completion continues the same function. The commit turn is labeled
**ByteQuay · Auto commit**; the publication turn shows its request as one
**Open draft pull request** `head → base` row instead of the prompt. Generated
turns carry trusted origin metadata and never start a second coordinator. They
reuse existing turn execution, approvals, transcript, and metrics.

When an already-accepted user queue takes precedence, atomically hand admission
to its FIFO turn under the task lock and end the current continuation. That
user turn's live completion callback starts the next checker. Do not wait on
a queued turn while retaining the ownership that prevents it from starting.

Allow at most one automatic commit request and one automatic publication
request per continuation. No repeated "please try again" loop, scheduled
handoff retry, or separate model deciding which workflow step comes next.
An explicit user answer starts fresh normal work; the checker runs again
once that work declares readiness. Existing per-turn request IDs guard
against duplicate live dispatch; no delivery-specific journal is added.

Inspection verifies worktree ownership, HEAD, staged/unstaged/untracked files,
open Git operations, actual commit/diff changes, remote ref, and PR identity.
Compare with the user turn's starting Git snapshot and the last observed
published revision, not merely the task's original start commit. An existing
PR alone must not cause every question to start another CI run.

Automatic publication accepts a fast-forward update or a rewrite of history
that belonged to the accepted user turn's starting HEAD. In either case the
remote SHA must still match the pre-turn observation, and the exact push lease
protects against a later remote change. This permits requests to amend, reword,
or reorder the task's published commits without a separate push request. After
verifying the rewritten revision on the same PR, continue into the enabled CI
flow for that revision. History already divergent from the task when the turn
started remains blocked unless the completed work incorporates it.

The commit request only commits finished task work with repository-convention
messages. The publication request never commits: it checks the supplied exact
HEAD/remote, pushes that object to the authorized ref, and uses
`gh pr create --draft` with the repository template when no matching PR exists.
Reuse an existing open PR; closed/merged/ambiguous matches stop the MVP.
Enforce expected remote absence or exact SHA at push time and do not overwrite
outside changes. The generated command pins the exact push URL/ref/object and
uses `--force-with-lease=<ref>:<expected-SHA>`, with an empty expected value for
confirmed absence. Git enforces that lease when the command runs. Native agent
tools retain their normal approval policy; this scoped instruction is not a
sandbox for arbitrary agent commands. ByteQuay independently rereads the remote
SHA and exact head/base repository/branch identities afterward; a reported URL
or successful agent response cannot establish publication.

Publication uses the installed `gh` login and its Git credential helper; app
workspace credentials are used separately for ByteQuay's read-only verification.
No app token is injected into the generated prompt. An unavailable `gh` login
fails the publication action. Same-repository PR commands use a branch-only
`--head`; forks use `owner:branch`. The installed CLI does not support an
organization-owned fork in that syntax; such a creation fails visibly without
an alternate publisher or retry. Existing PRs keep their current draft status;
PRs created by the publication follow-up must independently verify as drafts.

Useful local validation belongs to the agent. Missing Bun, for example,
remains an explicit local-check limitation; it is not proof of passing tests
and need not block a finished commit/draft. Missing prerequisites for the next
action, such as GitHub CLI authentication, stop that action visibly.

## 5. CI and concurrent input

CI Autofix continues to own its existing repair policy: up to three fixes,
three failed-job reruns per unchanged head, and eight hours. The user-approved
window begins once this live continuation activates, before follow-ups;
generated turns never extend it. Supply the expected PR/head and that deadline
at CI start. A reused run keeps its original scope/budget. An unchanged turn
cannot restart an exhausted run; this MVP has no resume/retry delivery action.

The application implements this through `CiAutofixService.startDevelopment`,
then calls `advance` from the same live coordinator with a five-second wait
between observations. It supplies the accepted provider without fallback,
original activation timestamp, parent/source-turn IDs, and exact verified PR
identity. `DevelopmentCiOwnership` ties the optional persisted CI parent link
to that live owner; a database row alone cannot restore authority. Existing CI
and rebase stores exclude delivery-owned runs from standalone monitor batches.
A CI reply is serialized by the existing run lock, and polling rereads a changed
paused run before interpreting it as an unrecoverable result.

The live CI owner also captures the source task's accepted user-message text
through the original user turn, including earlier answers and steering, while
excluding generated follow-ups, assistant prose, known-unsent turns, and queued
work not yet delivered. Every CI repair, rebase-conflict, and reply session receives
that same scope through managed instructions. Missing or oversized required text
fails attachment rather than dropping restrictions. Attachment names are reported
without copying their contents; the agent must ask if unavailable content is needed.
The scope does not replay completed development/publication actions or override
CI/rebase execution rules. It adds no stored workflow state or restart support.

Keep the source task busy while the separate CI worktree may commit, rebase,
and push. This is an in-memory ownership check at Send, queue start, and
Complete, not a new durable `DELIVERY` claim. Existing worktree claims still
protect actual agent processes. Generated turns receive the coordinator's
internal ownership token. Manual CI attachment to the same task branch must
honor the same exclusion.

For the MVP, the composer can retain a draft while CI owns the branch, but
Send/queue start is disabled with **CI Autofix is running · Stop to start over**.
Do not extend queue storage/admission to support delivery queues. Previously
accepted user work is drained before the continuation; new work waits. Existing
CI pause/message controls may resolve a repair question in that same CI run;
source development cannot compete with it. Stop or an unrecoverable CI result
fails this MVP continuation rather than creating a recovery journey.

Before releasing a successful continuation, bring the source to the exact
verified published CI head, including when CI rewrote history. Require its
managed worktree and generated branch, expected source HEAD, no staged,
unstaged, or untracked changes, no active Git operation, and stopped CI writers.
Retain live ownership while fetching that exact commit from the accepted push
URL without updating refs or `FETCH_HEAD`. Recheck source ownership, routing,
HEAD, and cleanliness after the fetch, then check authority immediately before
updating the same generated branch:

```sh
git -c core.hooksPath=/dev/null checkout --no-overwrite-ignore \
  --no-recurse-submodules -B <generated-branch> <verified-sha>
```

This permits noncolliding ignored build output and makes Git refuse ignored
files that the published revision would overwrite. `reset --keep` does not
provide that ignored-file protection. Verify the exact resulting HEAD, branch,
ownership, routing, and cleanliness afterward. Stop drains the admitted native
action before child/source cleanup or Delete can proceed.

Successful synchronization keeps the source task `OPEN` for another user turn.
CI's retained result branch and the remote branch/PR preserve the published
work. A synchronization failure ends the continuation visibly; it does not
undo remote results or retry the local transition. Inspect local files after a
failed native update. Add no private checkpoint refs or sync-replay protocol,
and never hard-reset dirty work to make continuation succeed.

The shared `CiHeadSnapshot.green` predicate now requires nonempty jobs in every
observed workflow, completed workflows with passing conclusions, and passing
jobs. A passed job cannot hide a queued or empty workflow. Actions inspection
rejects incomplete or truncated workflow/job pages instead of accepting a
partial success. After CI records green, the coordinator reads Actions again
while checking delivery and around promotion; a newly queued workflow with no
jobs still blocks completion.

`GitHubPullRequestReadClient.fetchDeliveryChecks` reads the exact current
PR/head/base and all pages of check runs and commit statuses. It combines
classic branch protection with active repository/organization ruleset
requirements. Every observed check must pass, and each required context must
be present from its required source. Completed check runs accept `success`,
`neutral`, or `skipped`; commit statuses require `success`. Missing, pending,
unknown, malformed, or incomplete evidence never implies green. Denied reads
fail visibly rather than implying that the repository has no requirements.

This gate deliberately has bounded support. Required app-bound check runs
must match the app ID; an app-bound legacy commit status lacks the origin
proof needed here and remains waiting. Required-workflow rules, code-scanning
rules, and unknown rule types also wait with an explicit limitation. GitHub
can require checks on its test merge commit instead of the head: while that
commit is unknown, or it has separate checks whose full requirements are not
verified by this implementation, head success cannot promote the PR. A failed
test-merge rollup or other external check is reported as a failure. CI Autofix
remains an Actions repair service and does not attempt to repair unsupported
external checks. Draft-excluding workflows can therefore prevent this order
from completing. Waiting uses the original CI run's deadline and fails when
it expires; it does not renew authority or restart a finished CI run.

Ready promotion belongs to the development coordinator, never a generic CI
`GREEN` callback or a standalone CI run. It honors the accepted Auto CI fix
selection, synchronizes the source, then rereads current PR identity,
head/base, remote publication, and checks before issuing exactly one
`markPullRequestReadyForReview` mutation. The write client verifies its returned
PR node and draft flag; the coordinator separately verifies fresh PR/check/
Actions/remote/local observations afterward. GitHub has no expected-head
precondition for this mutation. An external-head race, changed checks, or an
uncertain response fails visibly with the existing remote result preserved.
Never retry promotion or automatically reverse the draft flag to repair
uncertainty. Failed or expired delivery keeps its actual PR and error visible.

## 6. Failure, restart, and deletion

**The delivery MVP does not resume after backend restart.** A turn, push,
PR-create, CI-handoff, or synchronization failure ends that continuation.
After owned execution is verified stopped, clear its unfinished marker and
release its claim while keeping the task `OPEN`. Keep the worktree, dirty
files, commits, transcript, attachments, and provider conversation. Failure
does not close the task, schedule retention cleanup, or remove a checkout.

An approval deadline ends the affected turn as interrupted with its timeout
reason and resolves outstanding requests without granting them. A later user
message starts a fresh turn in the same task, using its retained conversation
and current files. It evaluates approvals normally; expired requests cannot
authorize later operations. A new submission may use its own accepted automation
settings, but never resumes the failed continuation or repeats an uncertain
remote operation merely because the old marker existed. Keep queued inputs
paused until the user explicitly resumes them.

A pending user answer remains normal conversation, not an execution failure.
If shutdown is unverified, preserve the same task and files but retain the
claim until task-scoped Stop can prove cleanup. The UI reports the specific
shutdown problem instead of presenting an inactive recovery loop.

Remove the former proposal's delivery records, per-step intents, source-sync
checkpoints, reconciler, retry API, and recoverable promotion. Keep only:

- accepted settings and the readiness report on existing tasks/turns;
- a task-level unfinished-continuation marker, to fail it after a lost process;
- an optional parent task ID on delivery-owned CI runs, inserted before they
  can start work, so failure/deletion can stop their children;
- existing lifecycle events for the stopped operation and verified Git/PR/run
  references for display.

The unfinished marker says only that a live continuation was lost. It records
no next step, phase, or resume checkpoint. Set it before dispatch, clear it
when settled or waiting for a user answer, and never replay it. Existing agent
startup handling deals with a process lost during a normal user turn; a lost
enabled continuation is marked failed rather than automatically restarted.

A delivery-owned CI run can advance, start a turn/rebase, rerun jobs, push,
or accept a resume message only while its matching live coordinator owns it.
Exclude it from independent CI scheduling; the coordinator drives the existing
CI service. Apply the same guard to linked rebase scheduling. Standalone CI
runs keep their existing behavior; do not remove their built-in recovery.

On source failure, revoke that live authority first, then drain admitted
source program actions and stop its CI/rebase children and provider processes.
The existing live owner monitor protects final inspection, synchronization, and
promotion. Stop and shutdown drain it outside the task lock before releasing
source exclusion; no new operation registry or recovery state is added. On startup, before the serving gate and
schedulers can write: clean up known processes using existing infrastructure,
end unfinished continuations, and stop their owned children. This is stop/fail
handling only; never resume or replay an uncertain push journal for a lost
parent. After verified child/rebase shutdown, finish the owned CI run as
cancelled so it releases its branch reservation. Preserve the child task and
dirty files for inspection; the old run cannot become a writer again through
another entry point after its continuation lost authority.

An in-flight remote write may already have succeeded. Show a known PR/branch
or **Remote result unknown**; do not retry the action, close a PR, delete a
remote branch, or promise rollback. Starting over must not quietly attach to
that old result or overwrite it.

The current explicit task Delete already supports discarding owned local
worktree changes and its generated branch after writers stop; normal cleanup
is different. Extend deletion narrowly to stop and delete delivery-owned CI
local tasks too, since the existing source Delete does not cascade to them.
If writer shutdown cannot be proven, deletion remains blocked by the existing
ownership protection. Remote branches and PRs survive; show their references
before deletion so the user can handle them separately. No automatic delete
or destructive cleanup happens just because the continuation failed.

## 7. Task presentation

Show actual observations and the current operation, not a workflow diagram's
stored state. The runtime-only label becomes **Idle**, not an ambiguous Ready.
Examples of derived activity are Committing, Creating draft PR, Waiting for CI,
and Fixing CI 1/3. The current CI run keeps its own status row.

A blocking question takes precedence:

```text
Waiting for your answer: Which implementation should I use?
```

The user replies in the normal composer. A successful answered turn leads
back to the checker; ByteQuay never asks the agent to commit simply because
uncommitted files exist while the decision remains open. Existing published
results are displayed truthfully and never undone by the gate.

Once cleanup succeeds, the normal composer accepts a new message in the
retained task. A browser/renderer reload leaves the backend continuation
running; backend restart fails the live continuation. No Resume delivery or
automatic recovery control is part of this MVP. Task closure is explicit; both
settled success and failure retain the open development conversation and
worktree.

Known PR references live in the side panel. One pull-request chip above the
composer opens that panel on the PR tab, and a task whose branch already has a
pull request opens with that panel open. Local validation limitations remain in
the agent transcript. The existing task-level lifecycle event carries bounded
messages for full-check waiting, source synchronization, and promotion. Only
changed waiting reasons are emitted; the renderer retains the latest message
outside loaded history, hides it on settlement, and cannot resurrect it for a
newer continuation. The task PR list refreshes when the continuation settles.

## 8. Implemented scope and checks

The implementation covers:

1. Three settings, accepted-turn snapshots, root readiness reporting, and
   question-first gating. Native success alone cannot authorize delivery.
2. One live after-turn coordinator, task ownership at admission, normal
   generated turns, actual Git/PR checks, and clear task result presentation.
3. Existing CI integration, parent-owned writer guard, clean source sync,
   corrected green/ready gate, and failure/delete handling.

The backend completion hook, not frontend polling or a model command, owns
progression. The small component belongs in `app`; agent core exposes the
completion notification and respects live admission ownership without depending
on GitHub logic. Reuse managed instruction delivery and provider tool transport.
Do not add a generic workflow framework or duplicate task/provider services.

Focused checks cover: dirty and committed question cases; answering as a new
turn; questions inside generated turns; pending approvals; missing report;
independent settings; queued work and simultaneous Send; duplicate completion
callbacks; generated-turn non-recursion; existing PR/no-op handling; exact
remote verification; bounded follow-ups; parent/child CI write exclusion;
CI limits/green detection; and stop/fail on lost backend with no orphan CI or
rebase writer. Verify Delete preserves remote results and refuses live writers.

For material implementation run `mvn verify` in `backend/`, and frontend
`npm run lint`, `npx tsc --noEmit`, `npm test`, and `npm run package`. Validate
document links and consistency alongside the implementation checks.
