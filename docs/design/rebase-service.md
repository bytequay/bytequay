# Branch Rebase Service

> **Status: proposed.** This design records an approved direction; it does not
> mean the service is implemented.

This document specifies a program-owned rebase of one local branch onto another
local branch, with agent-assisted conflict resolution. It is used to land a
completed cherry-pick range (see
[Cherry-pick Range](cherry-pick-range.md#14-landing-the-range)) and is intended
to replace the agent-driven rebase that CI Autofix currently asks for in prose.

## 1. Why this is a separate service

Cherry-picking and rebasing look similar and are not the same operation:

| | Cherry-pick range | Rebase |
| --- | --- | --- |
| What moves | a fixed list of source commits from elsewhere | the branch's own commits |
| Onto what | a base frozen at confirmation | a base that just moved |
| Plan | confirmed by the user, immutable | derived from Git, not user-selected |
| Skips | user-chosen, durable | none; a commit that becomes empty is dropped by Git |
| Ordering authority | the confirmed plan | existing branch history |

They share exactly one thing: a conflict appears, an agent resolves it in a
leased worktree, and the program decides from Git state whether the resolution
is acceptable. That shared part is what this service owns.

### 1.1 What CI Autofix does today

CI Autofix has no program-owned rebase. Its prompt tells the agent to perform
one:

```text
If the fetched target branch advanced, rebase the PR branch onto it and
resolve conflicts.
```

and `CiGitOperations` only detects a rebase that was left open:

```java
ImmutableList.of("MERGE_HEAD", "CHERRY_PICK_HEAD", "rebase-merge", "rebase-apply", "sequencer")
```

So the rebase itself is unverified: the program never learns which commits were
replayed, whether any were dropped, or whether history was rewritten beyond the
intended range. That contradicts the rule the rest of the system follows, that
Git state decides what happened. Moving CI Autofix onto this service is the
reason to build it as a service rather than as cherry-pick-private code.

Migrating CI Autofix is **not** part of the first release. The service ships for
the cherry-pick landing path first, with CI Autofix left unchanged until the
service has run against real ranges.

## 2. What is shared, and what is not

`git cherry-pick <list>` and `git rebase <base>` are the same machinery. Both
drive Git's sequencer, which applies commits until one conflicts or proves
empty, records the remainder in a todo file, and resumes on `--continue` or
`--skip`:

```text
.git/sequencer/todo              .git/rebase-merge/git-rebase-todo
  pick 13d1ca2 S2 conflicts        (same stop/continue/skip/abort contract)
  pick a37c5ca S3 clean
```

**Shared: `GitSequencerDriver`.** One concrete package-private helper owns
begin, observe, continue, guarded-skip, and abort, and reports a
`SequenceState` of `COMPLETED`, `STOPPED_CONFLICT`, `STOPPED_RESOLVED`,
`STOPPED_EMPTY`, or `UNSAFE`. Its full contract is defined in
[Cherry-pick Range §13.6](cherry-pick-range.md#136-java-contracts). It decides
nothing about plans; it reports Git facts and performs one bounded action.

That covers the conflict loop both services need: stop, hand the conflict to an
agent, verify the staged result from Git, continue, and skip a proven-empty
stop without an agent turn. The worktree-state inspection currently inside
`CiGitOperations` moves into the driver rather than being duplicated a third
time.

**Not shared: the plan layer.** The two services differ in exactly one place,
and it is not the loop:

| | Cherry-pick | Rebase |
| --- | --- | --- |
| who chooses the commit list | the user, through confirmed skips | nobody; it is whatever the branch holds |
| durable per-commit result owed | yes, for per-item progress and final accounting | no |
| what the service adds | plan, positions, `-x` reconciliation, accounting, range-diff | original tip, descendant fast path, abort-restores-tip |

So cherry-pick keeps a plan with durable item results, and this service keeps
almost no state at all. Forcing both into one state machine would mean giving
rebase a plan it does not have.

**Also not shared: CI Autofix's advance loop.** It advances attempts and CI
observations, not a Git sequence. It can adopt the driver for its rebase step —
which is the point of building the driver — without its run state machine
merging into either of these.

## 3. Boundaries

- One rebase moves one local work branch onto one local base branch.
- Both branches are local. The service never fetches, pushes, or contacts
  GitHub. Synchronizing the base branch happens before the service is called.
- The work branch is checked out in a leased worktree; the base branch is not
  checked out anywhere.
- The service never rebases onto a remote-tracking ref and never rebases a
  detached HEAD.
- Git state decides every outcome. Agent response text is shown to the user and
  never read as a control fact.
- The original work-branch tip is recorded before the first mutation, so the
  pre-rebase history is always recoverable by object ID.

## 4. State model

Status, mirroring the cherry-pick executor:

```text
RUNNING
PAUSED
FINISHED
```

Current step, which applies only to the commit the sequencer stopped on:

```text
REPLAYING
RESOLVING
VALIDATING
```

The service maintains no per-commit result list. Git's sequencer is the plan:
it decides which commits remain, and its todo file reports the position.
Duplicating that into a second durable list would create two answers to one
question, and they would disagree after any crash. Cherry-pick keeps such a list
only because it owes the user a per-item result for commits the user chose to
skip; nothing here was chosen.

Durable state is therefore small:

```text
rebaseId, workBranch, baseBranch
originalWorkTip          object ID before the first mutation
baseSha                  object ID rebased onto
status, currentStep?, pauseReason?
conflictCommitSha?       the commit the sequencer stopped on
agentTaskId, currentTurnId?, attemptCount
resultWorkTip?           object ID after a successful rebase
```

## 5. Flow

1. **Preconditions.** Worktree is leased, on the work branch, clean, with no
   Git operation open. The base branch resolves locally. Record
   `originalWorkTip` and `baseSha`.
2. **Fast path.** If the work branch is already a descendant of the base, record
   `FINISHED` without running anything. Git reports `Current branch <name> is up
   to date` for this case anyway; checking first means the service records a
   terminal result without starting an operation it would have to inspect.
3. **Start.** Call `GitSequencerDriver.begin(...)`, which runs one
   non-interactive rebase:

   ```text
   git rebase --no-autosquash <baseSha>
   ```

   A plain rebase opens no editor, and the shared Git runner already sets
   `GIT_TERMINAL_PROMPT=0`, so no further isolation is needed.
   `--no-autosquash` is not redundant: autosquash is off by default but a
   repository may enable `rebase.autoSquash`, and a work branch carrying
   `fixup!` subjects would then be silently squashed, changing the commits the
   user reviewed.
4. **Clean result.** Inspect Git. Accept only when no operation remains, the
   index and worktree are clean, and the new tip is a descendant of `baseSha`.
   Record `resultWorkTip` and `FINISHED`.
5. **Conflict.** Record `RESOLVING` and `conflictCommitSha` from
   `git rebase --show-current-patch`, then start one agent turn.
6. **After a turn.** Call `observe(...)` and read the `SequenceState`, never
   the agent's text:
   - `STOPPED_RESOLVED` → `continueSequence(...)`, which resumes the remaining
     commits in one action;
   - `STOPPED_EMPTY` → `skipEmpty(...)`, safe here because the sequencer, not a
     user selection, owns the commit list;
   - `STOPPED_CONFLICT` with attempts below the limit → start another turn with
     the actual diagnostics;
   - `COMPLETED` → validate;
   - `UNSAFE` → `PAUSED`.
7. **Validation.** After the rebase completes, run the stored validation command
   once against the final tree. The intermediate commits of a rebase are not
   individually validated; only the finished branch is.
8. **Limit.** At most three agent turns per conflicted commit. Exhaustion
   pauses.

## 6. Recovery, pause, and cancellation

- Every action stores its intent before mutating Git and inspects Git
  afterwards. A restart re-inspects rather than repeating a command.
- A pause leaves the rebase open. The worktree holds the conflict exactly as
  the agent left it, and the user can inspect it directly.
- Cancellation runs `git rebase --abort` and verifies that the work branch tip
  is back at `originalWorkTip`. This is the one place the service undoes work,
  and it is safe because a rebase in progress is the service's own operation,
  not user work. If the abort does not restore the recorded tip, the service
  pauses instead of continuing.
- `originalWorkTip` is reported in every terminal state so a user can recover
  the pre-rebase branch with `git reset --hard <sha>` even after a success.

## 7. Agent prompt

```text
Resolve only this rebase conflict.

Work branch: <branch>
Rebasing onto: <base branch and object ID>
Stopped on commit: <object ID and subject>
Conflict files:
- <path and conflict type>
Latest program diagnostic:
- <diagnostic, when retrying>

Goal:
- Resolve every conflict and stage the result.
- Leave the rebase open. ByteQuay runs git rebase --continue itself.

Do not run git rebase --continue, --skip, or --abort. Do not commit, amend,
cherry-pick, push, or write to GitHub. Do not touch files unrelated to the
conflict.

Your final message is for the user. ByteQuay inspects Git and decides.
```

The agent stages; the program continues. This is the same division the
cherry-pick prompt uses, and it is what lets both services share one driver: a
sequence continues through many commits, so the program must re-inspect at every
stop rather than letting the agent run ahead.

## 8. Java contracts

```java
package com.bytequay.app.rebase;

/**
 * Rebases one local branch onto another with agent-assisted conflict
 * resolution, deciding every outcome from Git state.
 */
public interface BranchRebaseService
{
    /**
     * Starts or returns the rebase for an idempotent request.
     *
     * @param command work branch, base branch, leased worktree, and idempotency key
     * @return the durable rebase
     */
    BranchRebase start(StartBranchRebaseCommand command);

    /**
     * Returns one rebase for the UI.
     *
     * @param rebaseId rebase identifier
     * @return current status, step, and recorded object IDs
     */
    BranchRebase get(BranchRebaseId rebaseId);

    /**
     * Inspects Git and performs at most one next action.
     *
     * @param rebaseId rebase selected by the local monitor
     * @return rebase after one bounded action
     */
    BranchRebase advance(BranchRebaseId rebaseId);

    /**
     * Aborts the rebase and restores the recorded original branch tip.
     *
     * @param rebaseId rebase identifier
     * @return finished or safely paused rebase
     */
    BranchRebase cancel(BranchRebaseId rebaseId);
}
```

The service lives in `app` beside the other application services. Its Git
helper is package-private and concrete, like `CherryPickGit`.

## 9. Acceptance criteria

1. A work branch already descended from the base finishes without rewriting
   history.
2. A clean rebase produces a tip descended from the base, with the original tip
   recorded.
3. A conflicted commit is resolved by an agent, continued by the program, and
   verified from Git state alone.
4. A commit that becomes empty is skipped by the program without an extra agent
   turn.
5. `fixup!` subjects on the work branch are not autosquashed.
6. Retry exhaustion pauses with the rebase left open and inspectable.
7. Cancellation restores the original branch tip, verified by object ID, and
   pauses if it cannot.
8. A restart mid-rebase re-inspects Git and never repeats a mutation blindly.
9. No path fetches, pushes, or writes to GitHub.
