# Cherry-pick Range Design

> **Status: implemented and verified with the shared managed-worktree boundary
> on 2026-09-01.** Range ordering, conflict history, and accounting remain
> unchanged. Worktree creation, inspection, and non-destructive cleanup use the
> shared
> [Managed Worktree Service](worktree-service.md).

This document defines the range preview, user confirmation, local execution,
agent conflict resolution, recovery, and final verification. It is a bounded
feature; it does not restore the deleted generic development-flow system.

## 1. Product flow

1. The backend fetches the source and target and creates a frozen preview.
2. The UI shows commits in cherry-pick order. It may fold adjacent commits by
   pull request and provide a pull-request checkbox, but the durable selection
   is always a list of source commit SHAs.
3. The user reviews the exact range and confirms the commits to skip.
4. Immediately before creating the worktree, the backend synchronizes the
   target again. If its SHA differs from the preview, start is rejected as
   stale and the user reviews a new preview.
5. The backend preassigns the task ID and first persists the execution with its
   frozen physical repository, target SHA, generated branch, and canonical
   worktree path. One idempotent ensure operation then creates or adopts the
   exact agent task through `WorktreeService`, which creates the worktree at the
   synchronized target SHA. There is nothing to rebase before the first
   cherry-pick because the generated branch has no unique commits.
6. The backend applies one source commit at a time with
   `git cherry-pick -x <sourceSha>`.
7. A clean commit is recorded immediately. A proven-empty commit is skipped.
   A conflict retains the source-marked target and its adjacent agent fixup as
   described in section 7.
8. After every item has a result, the backend performs exact Git accounting,
   stores `git range-diff` only for human review, completes the agent task, and
   removes the clean worktree while retaining the generated branch for delivery.

There is no automatic rebase before or after range execution. A target move
before start invalidates the preview. Rebasing an already-created branch is a
separate service and a separate user decision.

## 2. Fixed boundaries

- One execution has one repository, source range, target branch, generated
  work branch, and one task-owned generated worktree.
- One source range contains at most 3,000 commits.
- Source commits stay in oldest-first topological order.
- Merge commits are rejected because no mainline parent was chosen.
- The local target branch is synchronized but never checked out and never
  receives the cherry-picks.
- The work branch is created at the exact frozen target object ID and receives
  all local mutations.
- Confirmation permits local Git mutations only. It does not permit push,
  comment, approval, merge, CI rerun, or another GitHub write.
- The program owns order, skips, state transitions, empty decisions, and final
  accounting. Agent messages never decide an outcome.
- A clean final `APPLIED` item has exactly one commit carrying the exact
  standard `-x` line for its source SHA.
- A conflict-resolved final `APPLIED` item has exactly two adjacent commits:
  source-marked target `C`, followed by one `fixup!` commit `F` with no `-x`
  line. The pair is never rewritten by a new execution.
- A final `EMPTY_SKIPPED` item has no commit and no target SHA.
- Commit titles, PR titles, patch similarity, and `range-diff` never cause an
  automatic skip.
- All execution worktrees come from the shared service. The renderer supplies
  `workspaceId`, never a repository path or worktree parent.
- A successful execution retains its verified generated branch as the durable
  local result and removes its worktree. Delivery pushes that branch from the
  managed repository; CI Autofix creates a fresh worktree at the PR head.

## 3. Preview and target-presence detection

The preview input names:

- a full local clone;
- source remote, base ref, and tip ref;
- target remote and target branch; and
- the local target branch used as the synchronized mirror.

The backend:

1. rejects a shallow clone;
2. fetches the source remote;
3. runs `git fetch <targetRemote> <targetBranch>:<localTargetBranch>`;
4. verifies the local target branch and remote-tracking ref resolve to the same
   SHA, refusing a diverged local branch instead of forcing it;
5. resolves source base, source tip, and target to full immutable SHAs;
6. enumerates the source range in application order; and
7. rejects merge commits.

A source commit is automatically `ALREADY_ON_TARGET` only when either:

- it is an ancestor of the target; or
- one of the newest 5,000 target commits contains an exact standard line:

  ```text
  (cherry picked from commit <full source SHA>)
  ```

This remains reliable when a historical conflict resolution changed the patch.
An origin line older than that bounded history window is not automatic presence
evidence. `git cherry` or patch ID may be shown as a warning, but it cannot skip
a commit.

## 4. Pull-request grouping and conflict preview

For each source commit, the backend returns:

```text
position
sourceSha
sourceParentSha
title
author
committedAt
changedPaths
pullRequest?: number, title, URL, mergedAt
alreadyOnTarget and evidence
patchEquivalentWarning
conflictPreview
```

PR association is display metadata. It may come from a PR number lookup or a
final commit association, but a PR head SHA is never used as execution input
because merge and squash strategies can change it.

The UI sorts by `position`, not by PR merge time. A folded PR row controls the
same commit checkboxes underneath it. Confirmed skips are transmitted as source
SHAs, so grouping can never reorder or silently add commits.

Conflict preview uses a temporary local worktree and native Git operations.
It reports paths, conflict types, nearby text sections when available, and the
nearest Maven module. Exact simulation stops at the first unresolved conflict;
later results are labelled not analyzed because the chosen resolution changes
what those commits will meet.

## 5. Confirmation and synchronization

The start request contains the preview ID, expected source base/tip SHA,
expected target SHA, selected skip SHAs, agent lane, and idempotency key.

Start performs these checks before any generated branch or worktree is made:

1. the preview exists and has not expired;
2. all expected SHAs equal the preview;
3. every selected skip is an eligible commit in that preview;
4. no execution is already live for the same repository and target branch;
5. the target is fetched again; and
6. the synchronized target still equals the preview target SHA.

If step 6 fails, start returns a stale-preview error. It does not rebase or
silently execute against the new target. The user must see the new conflict
preview and confirm again.

The service generates the task branch and worktree directly at that SHA:

```text
WorktreeService.provision(
    new ProvisionWorktreeCommand(taskId, workspaceId, repository, frozenTargetSha))
```

The Git implementation performs the equivalent guarded `git worktree add -b`
under the backend-owned workspace worktree root. The caller cannot choose the
path or branch.

The execution row is written before provisioning. Start, repeated start,
monitor advance, and cancellation use the same ensure operation, so restart
repairs the narrow insert-to-task gap without a new lifecycle state.
Cancellation does not write `FINISHED` unless the exact preassigned task is
present or can be safely created; a verification failure leaves the execution
retryable. A create race rereads and validates the exact winning task. The
persisted physical repository prevents a workspace selection change from
routing recovery into another clone; legacy rows with neither an existing task
nor that frozen fact are preserved and rejected.

## 6. Durable ownership and results

`CherryPickExecutionService` is the only transition owner. It writes:

- execution `RUNNING`, `PAUSED`, or `FINISHED`;
- preassigned agent task ID, frozen physical repository, generated branch, and
  canonical worktree path;
- current step `APPLYING` or `RESOLVING`;
- pause/finish reason; and
- each item's result.

Each item has one durable result:

```text
PENDING
ALREADY_ON_TARGET
USER_SKIPPED
APPLIED             targetSha is required
EMPTY_SKIPPED       targetSha is absent
```

The current item is derived as the first `PENDING` item. There is no separately
stored cursor that could disagree after a crash.

Before every external mutation, the service stores an action journal containing
the action type, source SHA, expected HEAD, attempt, and idempotency key. It then
runs Git or starts an agent turn without holding a database transaction,
inspects actual state, and saves the result in a second short transaction.

The journal actions used by the conflict path are:

```text
COMMIT_CONFLICT_SNAPSHOT   expectedHead = H0
AGENT_TURN                 expectedHead = C
AGENT_REPAIR               expectedHead = F to amend
FINALIZE_CONFLICT_PAIR     expectedHead = F
```

The journal and Git objects are enough for recovery while the item remains
`PENDING`. A clean item stores its one created commit as `targetSha`. A
conflict-resolved item stores `C` as `targetSha`, while the execution's
`currentTargetSha` advances to `F`; the next source commit therefore starts
from the repaired tree without mislabelling the fixup as the source target.

`NORMALIZE_CONFLICT_PAIR` remains readable only for executions persisted by
the former squashing implementation. New executions never journal that action.

## 7. Applying one source commit

Let:

- `S` be the requested source commit;
- `H0` be the finalized work-branch tip before `S`;
- `C` be the retained source-marked conflict target; and
- `F` be the retained directly adjacent agent fixup.

The program runs one command:

```text
git cherry-pick -x S
```

The command exit text is diagnostic only. The program classifies Git state.

### 7.1 Clean apply

A clean apply is accepted only when:

- no Git operation remains;
- the index and worktree are clean;
- HEAD is exactly one child of `H0`;
- that child has the exact standard `-x` line for `S`; and
- `git diff --check H0..HEAD` finds no conflict marker. Repository whitespace
  rules are disabled for this check so copied source whitespace is not treated
  as a conflict failure.

The item becomes `APPLIED` with that child SHA.

### 7.2 Empty before a conflict commit

The operation is proven empty only when all of these are true:

- HEAD is still `H0`;
- `CHERRY_PICK_HEAD` is exactly `S`;
- there are no unmerged entries;
- `git diff --cached --quiet HEAD` succeeds; and
- there are no tracked or untracked changes.

The service journals `SKIP_EMPTY`, runs `git cherry-pick --skip`, and verifies
HEAD is still `H0`, no operation remains, and the worktree is clean. It records
`EMPTY_SKIPPED` and continues without an agent turn or user pause.

This covers both an originally empty source commit and a change already present
under another target commit. Git stops identically in both cases.

### 7.3 Conflict snapshot

When `CHERRY_PICK_HEAD=S` and unmerged paths exist, the service first persists:

- `S`, its source parent, and `H0`;
- every conflict path and type; and
- each exact `git ls-files -u` entry: stage 1 base blob, stage 2 target blob,
  stage 3 source blob, mode, and path when those stages exist.

It then stages only those exact conflict paths as Git left them:

```text
git add -A -- <conflict paths>
git cherry-pick --continue
```

The service accepts `C` only when it is one child of `H0`, contains the exact
`-x` line for `S`, closes the operation, and leaves a clean worktree. `C` is the
durable target commit, but it may contain text markers or Git's selected side
for a binary/delete/submodule conflict. The item remains `PENDING` and
`RESOLVING` until the adjacent fixup supplies the marker-free final tree.

### 7.4 Agent fixup

The agent receives `S`, the source parent, `H0`, `C`, the recorded index stages,
conflict paths, attempt number, and any user note. Its goal is:

```text
Resolve the final tree.
Remove conflict markers and unrelated changes.
Run the project's existing local checks when they can run offline.
Stage the complete result.
Run exactly: git commit --fixup=<full C SHA>
If the tree already looks correct, use --allow-empty with that fixup.
Stop.
```

The agent must not run cherry-pick continue/skip/abort, rebase, reset, push,
`gh`, or an ordinary commit. Claude cherry-pick tasks route shell commands
through the permission broker. It allows read/build/stage commands, a
fixup-shaped commit, and the exact `git commit --amend --no-edit` repair shape.
The service still verifies the expected history; the broker does not decide
success.

After the turn, the service accepts `F` only when:

- `F` is the only new commit and its parent is exactly `C`;
- its subject is exactly `fixup! <subject of C>`;
- it contains no `-x` origin line;
- no operation remains and the index/worktree are clean; and
- marker-only `git diff --check H0..F` succeeds.

Agent prose is never parsed. No fixup causes another CREATE turn. A structurally
correct `F` that is dirty or still contains markers causes a REPAIR turn whose
only history action is `git commit --amend --no-edit`; it replaces `F` and may
not append a third commit. There are at most three total turns. Wrong history
pauses instead of guessing.

### 7.5 Validation and pair finalization

After structural checks, ByteQuay calls:

```java
validate(worktree, F)
```

The first implementation intentionally makes this a successful no-op.
ByteQuay cannot infer how an arbitrary project builds. A future feature may
store and run a user-approved validation profile; until then the agent's build
attempt is useful user information, not a program control fact.

Before starting the next source commit, the program verifies and retains this
history without rewriting either commit:

```text
H0 -- C -- F
```

It accepts the pair only when `C` remains one child of `H0`, `F` remains one
child of `C`, both messages retain their verified shapes, HEAD is exactly `F`,
and the worktree is clean with no operation or conflict marker in the final
`H0..F` tree. The item becomes `APPLIED` with `targetSha=C`, while the execution
sets `currentTargetSha=F`. The next source commit starts from `F`.

The pair remains even when `tree(F) == tree(H0)`. Unlike an originally empty
pick, a conflicted pick produced a reviewable target and an attributed semantic
resolution, so dropping both would erase that history.

This history shape has a conscious cost. `C` captures Git's unresolved snapshot
and may not compile or even be marker-free when checked out by itself. ByteQuay
does not claim that `C` is an independently valid boundary; it validates and
accounts for `C` plus its immediately adjacent `F` as one conflict-resolution
unit. Consumers that test every commit must likewise recognize that pair. The
benefit is that the source target and the agent-authored resolution remain
separately reviewable and attributable.

## 8. Recovery and edge cases

Every `advance` inspects Git before mutating it. The action journal explains
which SHA is expected, and actual Git objects explain whether the action ran.

| Observed state | Decision |
| --- | --- |
| Journal says empty skip, the exact empty operation is still open | Run guarded `--skip` once |
| Journal says empty skip, HEAD is clean at `H0` and the operation is closed | Adopt `EMPTY_SKIPPED`; do not start the source again |
| Journal says snapshot, conflict still open at `H0` | Recheck saved stages, create `C` once |
| Journal says snapshot, one valid `C` already exists | Adopt `C`; do not continue again |
| Journal says agent turn, HEAD is `C` | Start or retry the fixup turn |
| Journal says agent turn, one valid `F` exists | Adopt and validate `F`; do not ask for another commit |
| Journal says repair, HEAD is the expected `F` | Ask the agent to amend that same fixup |
| Journal says repair, one valid amended `F` exists | Adopt the replacement; reject a third commit |
| Journal says finalize, HEAD is the expected `F` and `C/F` remain valid | Atomically record `targetSha=C` and `currentTargetSha=F` |
| Journal says finalize, HEAD or either parent/message changed | Pause without adopting the moved history |
| Any parent, origin, tree, operation, or path fact disagrees | Pause and preserve the worktree |

An execution carrying the former `NORMALIZE_CONFLICT_PAIR` journal is a legacy
recovery case, not a new transition. Its compatibility handler may finish the
already-started soft-reset/commit sequence and produce the former single
source-marked commit. Removing that journal value would make a persisted
in-flight execution unreadable, so it remains until those executions have
settled.

Additional edge cases:

| Edge case | Response |
| --- | --- |
| Source was already recorded by an exact historical `-x` line | `ALREADY_ON_TARGET`; never invoke Git |
| Source is patch-equivalent but has no exact evidence | Show warning; still attempt it |
| `git cherry-pick` returns nonzero | Inspect state; do not decide from the exit code |
| Empty and conflict both seem possible | Unmerged paths mean conflict; empty requires no unmerged path and no change |
| Agent runs `--continue` or creates extra history | Permission denies the command; unexpected Git history pauses |
| Agent creates valid `F` but leaves dirty files or markers | Retry by amending that same `F`; never add a third commit |
| Agent creates no valid `F` | Retry the same item up to three turns |
| Final `C/F` tree equals `H0` | Retain both and record the conflict as `APPLIED` |
| Backend stops after creating `F` but before recording the pair | Reverify `C/F`, then record `C` and advance the execution tip to `F` once |
| User cancels | Cancel the turn; clean work may use normal cleanup with its generated branch retained, while dirty or open work is preserved for inspection |
| Target moves after preview but before start | Reject stale preview before creating the worktree |
| Target moves during execution | Finish against the frozen target; do not auto-rebase |

## 9. Final accounting and range review

Completion requires:

- every item has a non-`PENDING` result;
- no Git operation remains and the worktree is clean;
- HEAD equals the execution's recorded `currentTargetSha`;
- applied targets follow item order, and each is the recorded `targetSha`, is a
  child of the previous finalized item boundary, and carries the exact `-x`
  line for its source;
- every conflict target is followed immediately by exactly one direct child
  whose subject is `fixup! <target subject>` and which carries no `-x` line;
- the next item starts after that fixup, so every created commit is consumed by
  exactly one clean target or one conflict `C/F` pair; and
- the whole generated range has no conflict marker.

A persisted execution completed by the former normalization handler may contain
one normalized conflict target without `F`. Persisted items have no separate
history-version or fixup field, so accounting recognizes that legacy shape
structurally: an item carrying persisted conflict-path evidence may omit the
adjacent fixup, but any adjacent candidate is consumed only when its parent,
subject, and absent `-x` line exactly match `F`. New pair finalization cannot
use the exception because it records `currentTargetSha=F`, and the
no-unrecorded-commit check therefore requires that exact child to be consumed.

Only after these checks does the service store:

```text
git range-diff sourceBase..sourceTip initialTarget..finalTarget
```

This output helps a person see missing, extra, or changed patches. It is never
parsed to decide completion because its matching is heuristic.

After the review is stored, completion records the generated branch as the
local result and calls `WorktreeService.cleanup(taskWorktree)`. Delivery
later pushes that recorded branch from the managed repository, so it does not
require a checked-out worktree. Cleanup failure is retryable and does not
rewrite the already verified range result. Successful removal records the
task's `worktreeRemovedAt`; the monitor reselects a `FINISHED` execution whose
terminal task lacks that fact, applies its batch limit after the workflow
match, and retries completion/event delivery followed by cleanup after a
one-minute backoff from the task's existing `updatedAt`. Pending task outbox
events drain before worktree removal. Cancellation has no destructive cleanup
mode: the shared service refuses dirty or open Git state, uses non-force
removal, and always retains the generated branch.

`WorkspaceBranchPusher` therefore accepts the workspace plus retained local
branch/ref and verifies it through the managed repository. It no longer
requires the removed worktree path or requires that branch to be checked out.

## 10. API contract

```text
POST /api/cherry-pick-ranges/previews
GET  /api/cherry-pick-ranges/previews/{previewId}
POST /api/cherry-pick-ranges/executions
GET  /api/cherry-pick-ranges/executions?limit=...
GET  /api/cherry-pick-ranges/executions/{executionId}
POST /api/cherry-pick-ranges/executions/{executionId}/messages
POST /api/cherry-pick-ranges/executions/{executionId}/cancel
```

The execution response exposes the durable answer only:

```text
executionId, repository, workBranch, localTargetBranch
status, currentStep?, pauseReason?, finishReason?, summary?
initialTargetSha, currentTargetSha, currentPosition?
items[]:
  position, sourceSha, title, result, targetSha?, pullRequestNumber?
```

For a conflict, `targetSha` is `C` and `currentTargetSha` is the effective branch
tip `F` until a later item advances it. The response does not add a separate
fixup field because Git's required directly adjacent child is the authority.

The renderer starts an execution only from the user's explicit confirmation.
It sends the frozen preview SHAs, exact skipped source SHAs, selected conflict
resolver, route `workspaceId`, and one stable idempotency key. It never sends a
repository or worktree path. While the execution
is `RUNNING`, it polls the durable `GET` response and displays the per-item
results without deriving Git state itself.

The renderer stores only the active execution ID; the route supplies the
workspace ID, and the backend resolves every repository/worktree fact. This is
enough for the progress screen and cancel action to survive navigation or an
app restart. `PAUSED` stops polling and shows the backend summary plus a bounded
user note field. Sending that note is an explicit retry action. Cancellation
is also an explicit user action and is available while running or paused.
`FINISHED` shows the final per-item accounting and allows a new range to be
started. The backend remains the sole owner of every status and result.

The main application contract is the concrete service currently used by the
Spring controller:

```java
/** Owns progress and final results for confirmed cherry-pick executions. */
public final class CherryPickExecutionService
{
    /**
     * Revalidates the confirmed preview, synchronizes the target, creates the
     * agent task and managed worktree, and returns the idempotent durable execution.
     */
    public CherryPickExecution start(
            StartCherryPickExecutionCommand command,
            String workspaceId);

    /** Returns the execution and its per-item durable results. */
    public CherryPickExecution get(CherryPickExecutionId executionId);

    /** Lists recent executions in reverse update order. */
    public List<CherryPickExecution> list(int limit);

    /**
     * Inspects Git and performs at most one bounded next transition.
     * It is the only method that advances an item.
     */
    public CherryPickExecution advance(CherryPickExecutionId executionId);

    /** Resumes one paused current item with a bounded untrusted user note. */
    public CherryPickExecution sendMessage(
            CherryPickExecutionId executionId,
            String message);

    /** Cancels future work and preserves the generated branch or dirty work. */
    public CherryPickExecution cancel(CherryPickExecutionId executionId);
}
```

For start, `app` resolves the managed repository, worktree root, and exact
frozen commit from `workspaceId` before invoking the shared service. For every
later operation, including cancel, it resolves the workspace and worktree facts
from the immutable `TaskWorktree` stored with the durable execution/task.
The resulting canonical path remains an internal execution fact and is not
returned to the renderer.

Low-level Git support remains a concrete internal helper, not a public port:

```java
/** Runs guarded, observable Git operations; it never records plan results. */
public final class GitSequencerDriver
{
    /** Starts one `git cherry-pick -x` at an exact expected HEAD. */
    public SequenceState beginCherryPick(
            Path worktree, String expectedHead, String sourceSha);

    /** Reads operation markers, HEAD, status, and full conflict stages. */
    public SequenceState observe(Path worktree);

    /** Stages exact conflict paths and creates source-marked target `C`. */
    public SequenceState commitConflictSnapshot(
            Path worktree,
            String expectedHead,
            String expectedSource,
            List<ConflictedPath> expectedConflicts);

    /** Skips only a rechecked empty operation. */
    public SequenceState skipEmpty(
            Path worktree, String expectedHead, String expectedSource);

    /** Completes a persisted journal from the former squashing implementation. */
    public NormalizationResult normalizeConflictPair(
            Path worktree, String originalHead, String conflictCommit, String fixupCommit);

    /** Aborts an open cherry-pick and verifies restoration. */
    public SequenceState abort(Path worktree, String expectedHead);
}
```

## 11. Rebase reuse

Rebase can reuse the low-level observation vocabulary, command runner, agent
task/turn lifecycle, retry rules, validation hook shape, and action-journal
pattern. It must not reuse the cherry-pick retained-`C/F` policy.

During a rebase conflict the agent edits and stages only; the program runs
`git rebase --continue`. A rebase may replay multiple commits and Git owns its
todo list, so appending an agent fixup after a temporary replayed commit is not
stable. A commit that becomes empty during replay is skipped by the program.

The implemented [Branch Rebase Service](rebase-service.md) is primarily for an
explicit rebase operation and a later CI Autofix migration. It is not invoked
automatically by this cherry-pick flow.

## 12. Acceptance criteria

Tests must prove:

1. source order and frozen SHAs are stable;
2. ancestry and exact `-x` lines are the only automatic presence evidence;
3. a target move after preview is rejected before workspace creation;
4. clean commits create exactly one final commit;
5. both kinds of original empty commit create no commit and continue;
6. conflicts persist index stages 1/2/3 before `C` is created;
7. the agent can produce only one direct fixup-shaped `F`;
8. every conflict retains `H0--C--F`, records `targetSha=C`, and advances the
   execution tip to `F` without rewriting either commit;
9. a `C/F` final tree equal to `H0` remains an attributed `APPLIED` pair;
10. crash recovery adopts the snapshot, fixup, and final recorded pair without
    repeating a mutation, while persisted former normalization journals remain
    readable;
11. invalid parents, origins, subjects, extra commits, dirty files, and markers
    pause rather than guess;
12. final accounting uses Git and the confirmed plan, never agent prose or
    `range-diff`; and
13. no path writes to GitHub;
14. execution creation uses the shared managed-worktree service. Successful
    completion terminalizes the task before persisting the execution result;
    explicit cancellation first ensures the exact preassigned task, then
    persists `FINISHED/CANCELLED` before terminalizing the task. Both paths
    remove the clean worktree only after both records are
    terminal while preserving the deliverable branch. Delivery pushes that
    branch without a checkout, and cancellation never deletes dirty work or the
    generated branch; and
15. a crash after the cancellation write leaves an unambiguous
    `FINISHED/CANCELLED` execution. The monitor retries its still-live task and
    then cleanup, without inferring cancellation from task state or pending-item
    count. A dirty or open-operation refusal is logged after the durable cancel
    result, returned to the user as that successful cancellation, and preserves
    the worktree. Failed completion or cleanup advances the task's existing
    `updatedAt`; a one-minute cutoff prevents hot loops and lets another
    candidate progress.
