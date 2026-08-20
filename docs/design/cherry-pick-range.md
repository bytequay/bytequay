# Cherry-pick Range Design

> **Status: locked.** This is the approved range design. Implementation
> progress is tracked in
> [Cherry-pick Range Implementation Checklist](cherry-pick-range-impl-tmp.md).

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
5. The backend creates a generated work branch directly at the synchronized,
   frozen target SHA. There is nothing to rebase before the first cherry-pick
   because the generated branch has no unique commits.
6. The backend applies one source commit at a time with
   `git cherry-pick -x <sourceSha>`.
7. A clean commit is recorded immediately. A proven-empty commit is skipped.
   A conflict uses the agent flow in section 7.
8. After every item has a result, the backend performs exact Git accounting and
   stores `git range-diff` only for human review.

There is no automatic rebase before or after range execution. A target move
before start invalidates the preview. Rebasing an already-created branch is a
separate service and a separate user decision.

## 2. Fixed boundaries

- One execution has one repository, source range, target branch, generated
  work branch, and generated worktree.
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
- A final `APPLIED` item always has exactly one commit carrying the exact
  standard `-x` line for its source SHA, whether or not a conflict occurred.
- A final `EMPTY_SKIPPED` item has no commit and no target SHA.
- Commit titles, PR titles, patch similarity, and `range-diff` never cause an
  automatic skip.

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
- target history contains an exact standard line:

  ```text
  (cherry picked from commit <full source SHA>)
  ```

This remains reliable when a historical conflict resolution changed the patch.
`git cherry` or patch ID may be shown as a warning, but it cannot skip a commit.

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

The work branch is then created directly at that SHA:

```text
git worktree add -b <generatedWorkBranch> <worktree> <frozenTargetSha>
```

## 6. Durable ownership and results

`CherryPickExecutionService` is the only transition owner. It writes:

- execution `RUNNING`, `PAUSED`, or `FINISHED`;
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
NORMALIZE_CONFLICT_PAIR    expectedHead = F
```

Temporary SHAs do not become final item fields. The journal and Git objects are
enough for recovery while the item remains `PENDING`. After normalization the
item stores only the final commit SHA or `EMPTY_SKIPPED`.

## 7. Applying one source commit

Let:

- `S` be the requested source commit;
- `H0` be the finalized work-branch tip before `S`;
- `C` be the temporary source-marked conflict snapshot;
- `F` be the temporary agent fixup; and
- `C'` be the normalized final commit.

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
`-x` line for `S`, closes the operation, and leaves a clean worktree. `C` is a
temporary evidence commit: it may contain text markers or Git's selected side
for a binary/delete/submodule conflict. The item remains `PENDING` and
`RESOLVING`.

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

### 7.5 Validation and immediate normalization

After structural checks, ByteQuay calls:

```java
validate(worktree, F)
```

The first implementation intentionally makes this a successful no-op.
ByteQuay cannot infer how an arbitrary project builds. A future feature may
store and run a user-approved validation profile; until then the agent's build
attempt is useful user information, not a program control fact.

Before starting the next source commit, the program normalizes the temporary
history:

```text
H0 -- C -- F
```

If `tree(F) == tree(H0)`, it moves the ByteQuay-owned work branch back to `H0`,
drops `C` and `F`, and records `EMPTY_SKIPPED`.

Otherwise it performs the equivalent of:

```text
git reset --soft H0
git commit -C C
```

It accepts `C'` only when:

- `C'` is one child of `H0`;
- `tree(C') == tree(F)`;
- the original message and exact `-x` source line from `C` are preserved; and
- the worktree is clean with no operation or conflict marker.

The item becomes `APPLIED` with `targetSha=C'`. The next source commit starts
only after this point. Immediate normalization avoids an end-of-range autosquash
that would rewrite every later target SHA.

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
| Journal says normalize, HEAD is `F` | Start normalization |
| Journal says normalize, HEAD is `H0` with staged tree equal to `tree(F)` | Finish `git commit -C C` |
| Journal says normalize, a valid `C'` already exists | Adopt `C'` |
| Journal says normalize, HEAD is clean `H0` and `tree(F)==tree(H0)` | Adopt the empty removal |
| Any parent, origin, tree, operation, or path fact disagrees | Pause and preserve the worktree |

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
| Final `C/F` tree equals `H0` | Drop both and record `EMPTY_SKIPPED` |
| Backend stops after soft reset | Compare the staged tree with `tree(F)` and finish once |
| User cancels | Cancel the turn, abort an open cherry-pick, and remove only ByteQuay's generated branch/worktree |
| Target moves after preview but before start | Reject stale preview before creating the worktree |
| Target moves during execution | Finish against the frozen target; do not auto-rebase |

## 9. Final accounting and range review

Completion requires:

- every item has a non-`PENDING` result;
- no Git operation remains and the worktree is clean;
- the number of commits from initial target to final HEAD equals the number of
  `APPLIED` items;
- those commits follow item order;
- each commit is the recorded target SHA, is a child of the previous finalized
  target SHA, and carries the exact `-x` line for its source; and
- the whole generated range has no conflict marker.

Only after these checks does the service store:

```text
git range-diff sourceBase..sourceTip initialTarget..finalTarget
```

This output helps a person see missing, extra, or changed patches. It is never
parsed to decide completion because its matching is heuristic.

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

Temporary `C` and `F` are recovery details and are not final API fields.

The renderer starts an execution only from the user's explicit confirmation.
It sends the frozen preview SHAs, exact skipped source SHAs, selected conflict
resolver, worktree parent, and one stable idempotency key. While the execution
is `RUNNING`, it polls the durable `GET` response and displays the per-item
results without deriving Git state itself.

The renderer stores only the active execution ID and the selected local clone
path so the progress screen and cancel action survive navigation or an app
restart. `PAUSED` stops polling and shows the backend summary plus a bounded
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
     * generated workspace, and returns the idempotent durable execution.
     */
    public CherryPickExecution start(
            StartCherryPickExecutionCommand command,
            Path repository,
            Path worktreeParent);

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

    /** Cancels future work and removes only the generated workspace. */
    public CherryPickExecution cancel(
            CherryPickExecutionId executionId,
            Path repository);
}
```

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

    /** Stages exact conflict paths and creates temporary snapshot `C`. */
    public SequenceState commitConflictSnapshot(
            Path worktree,
            String expectedHead,
            String expectedSource,
            List<ConflictedPath> expectedConflicts);

    /** Skips only a rechecked empty operation. */
    public SequenceState skipEmpty(
            Path worktree, String expectedHead, String expectedSource);

    /** Squashes `C/F` into one commit or removes an empty pair. */
    public NormalizationResult normalizeConflictPair(
            Path worktree, String originalHead, String conflictCommit, String fixupCommit);

    /** Aborts an open cherry-pick and verifies restoration. */
    public SequenceState abort(Path worktree, String expectedHead);
}
```

## 11. Rebase reuse

Rebase can reuse the low-level observation vocabulary, command runner, agent
task/turn lifecycle, retry rules, validation hook shape, and action-journal
pattern. It must not reuse the cherry-pick `C/F/C'` policy.

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
8. nonempty `C/F` is normalized to one final `-x` commit with `tree(C')=tree(F)`;
9. a `C/F` final tree equal to `H0` is removed and recorded `EMPTY_SKIPPED`;
10. crash recovery adopts the snapshot, fixup, soft-reset, normalized, and
    empty-removal states without repeating a mutation;
11. invalid parents, origins, subjects, extra commits, dirty files, and markers
    pause rather than guess;
12. final accounting uses Git and the confirmed plan, never agent prose or
    `range-diff`; and
13. no path writes to GitHub.
