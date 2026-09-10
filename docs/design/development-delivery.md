# Development task delivery

Status: Approved MVP design; implementation pending. This replaces the larger
recovery-oriented proposal. Use the current [agent service](agent-service.md)
and [CI Autofix](ci-autofix-service.md), with a small in-process coordinator.
There is no overall workflow state machine, delivery table, phase engine,
step journal, or restart/resume support for the delivery chain.

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
Generated follow-ups inherit that snapshot. The task's **Automation** control
is separate from its **Auto** permission mode. While a continuation is active,
settings edits are labeled **For future turns**; **Stop automation** stops the
current continuation. Local only supplies all three false for one user turn.

Submitting the request with its visible settings records authority for that
request's generated branch, repository/base, initial push/draft, bounded CI
writes, and ready promotion. There is no second routine publication approval.
Provider approval and authentication checks still apply. Generated text is
not itself a user authorization. Existing tasks do not auto-publish on app
startup or page load; their next explicit submission carries the selection.

Only interactive development tasks enter this continuation. CI, cherry-pick,
review-owned tasks, helpers, and child agents cannot recursively start it.
An unchanged read-only turn produces no new commit, PR, or CI run.
When all three settings are off, use ordinary task behavior: do not activate
a continuation, require its readiness report, or apply its fail-and-delete
policy. Git facts may still be read for display.

Respect independent settings:

- Auto commit off plus dirty files stops before publication, even if Auto
  draft PR is on. Never silently commit or publish only a committed subset.
- Auto draft PR off leaves commits local. Auto CI fix can operate only on an
  already-existing PR that exactly matches the clean local revision.
- Auto CI fix off leaves the published draft as a draft.
- Missing/unsupported CI shows the actual partial result, not full success.
  Currently CI requires an authored same-repository PR and a `CI_AUTOFIX`
  provider. Claude supports it; Codex development can hand off to Claude.
  Fork draft creation works independently, but fork CI remains unsupported.

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

The same report may narrow delivery with `stopAfter` (`local_changes`,
`commits`, `draft`, or `ci`). For example, "leave it draft" allows repairs but
stops before ready. It cannot enable a disabled setting, add authority, choose
a destination, or perform a remote write. This single report replaces the
previous proposal's separate `restrict_delivery` tool. The provider binding
must accept only the current root turn's report, not a child agent's claim.
The report conveys the agent's understanding; Git/GitHub still prove results.

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
queue dispatch, so a simultaneous Send cannot race the checker. Do no long
Git/provider work while holding that lock. Generated turns use a trusted
internal start path carrying the owner; other starts respect it.

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
    if auto CI fix off or unavailable: show PR and reason; finish

    start/reuse the existing CI run for this verified revision
    drive its existing watch/fix/push loop within its limits
    on green: check current PR/head/checks and permitted ready promotion
    synchronize published CI repairs back into the clean source worktree
    show the result; release ownership
```

This is ordinary control flow, not saved `COMMITTING → PUBLISHING → WATCHING`
transitions. The coordinator waits asynchronously on each generated turn;
its completion continues the same function. Generated turns are labeled
**ByteQuay · Auto commit** or **ByteQuay · Create draft PR**, carry trusted
origin metadata, and never start a second coordinator. They reuse existing
turn execution, approvals, transcript, and metrics.

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

The commit request only commits finished task work with repository-convention
messages. The publication request never commits: it checks the supplied exact
HEAD/remote, pushes that object to the authorized ref, and uses
`gh pr create --draft` with the repository template when no matching PR exists.
Reuse an existing open PR; closed/merged/ambiguous matches stop the MVP.
Enforce expected remote absence or exact SHA at push time and do not overwrite
outside changes. Verify GitHub independently afterward, not by parsing a URL.

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

Before returning a successful task to the user, bring the source to the exact
verified published CI head. Under ownership, require the source at its expected
HEAD, completely clean, no active Git operation, and stopped CI writers. Fetch
that exact object and use a checked clean-worktree transition such as
`git reset --keep --no-recurse-submodules <verified-sha>`. Verify afterward.
CI's retained result branch contains the published work. If synchronization
fails or the process stops, fail; add no private checkpoint refs or sync-replay
protocol. Never hard-reset dirty work to make continuation succeed.

The current green predicate must be corrected: a passed job plus a queued
workflow exposing no jobs can currently report green. Require complete,
nonempty CI evidence with no pending/failing relevant workflows/jobs. Ready
promotion additionally checks the current PR revision and required check/status
results. Unknown or missing checks remain waiting; draft-excluding workflows
can block the desired order. CI Autofix remains an Actions repair service;
third-party failures are reported, not assigned an invented repair adapter.
A failed/expired/exhausted run keeps the actual draft and error visible.

Ready promotion belongs to this coordinator, not a generic CI `GREEN` callback.
Honor stopAfter/Auto CI fix and recheck current head/base/PR before and after
the mutation. An external-head race or uncertain response fails visibly;
GitHub's mutation has no expected-head precondition. Do not retry promotion or
flip draft status automatically to repair uncertainty.

## 6. Failure, restart, and deletion

**The delivery MVP does not resume after backend restart.** A turn, push,
PR-create, CI-handoff, or synchronization failure ends the continuation. The
user deletes the failed task and starts another. A pending user answer is the
normal-conversation exception described above, not an execution failure.

Remove the former proposal's delivery records, per-step intents, source-sync
checkpoints, reconciler, retry API, and recoverable promotion. Keep only:

- accepted settings and the readiness report on existing tasks/turns;
- a task-level unfinished-continuation marker, to fail it after a lost process;
- an optional parent task ID on delivery-owned CI runs, inserted before they
  can start work, so failure/deletion can stop their children;
- existing task failure text and verified Git/PR/run references for display.

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

On source failure, revoke that live authority first, then stop its CI/rebase
children and provider processes. On startup, before the serving gate and
schedulers can write: clean up known processes using existing infrastructure,
fail unfinished parents, and stop their owned children. This is stop/fail
handling only; never resume or replay an uncertain push journal for a lost
parent. A dirty child may remain paused locally, but cannot become a writer
again through another entry point after its owner failed.

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
and Fixing CI 1/3. A separate compact result shows local changes, commit count
and HEAD, pushed revision, PR link/draft flag, and current CI outcome.

A blocking question takes precedence:

```text
Waiting for your answer: Which implementation should I use?
1 local commit · 2 uncommitted files · Not pushed · No PR
```

The user replies in the normal composer. A successful answered turn leads
back to the checker; ByteQuay never asks the agent to commit simply because
those two files exist while the decision remains open. Existing published
results are displayed truthfully and never undone by the gate.

For failures show **Automation failed · Delete task and start again**, with
the failing operation and actual local/remote result. Browser/renderer reload
can reread existing facts while the backend continues; backend restart fails
the live continuation. No Resume delivery or automatic recovery control is
part of this MVP. Task closure is explicit; successful delivery retains the
open development conversation and worktree.

## 8. Implementation gaps and checks

Implement in this order:

1. Three settings, accepted-turn snapshots, root readiness reporting, and
   question-first gating. Current native success is insufficient.
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
`npm run lint`, `npx tsc --noEmit`, `npm test`, and `npm run package`. This change
is documentation only; validate its links and consistency rather than running
an application build.
