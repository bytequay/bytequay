# Managed Worktree Service

> **Status: implemented and verified for every agent-backed workflow on
> 2026-09-01.** This document records the separate-worktree decision for every
> agent-backed workflow. It replaces flow-specific worktree creation and the
> former existing-checkout lease model.

## 1. Decision

Every repository-scoped agent task runs in one ByteQuay-created Git worktree.
Interactive development tasks, cherry-pick executions, and CI Autofix runs all
obtain that worktree through one `WorktreeService`. Branch Rebase borrows its
caller's task worktree and never creates or removes another one.

The service is synchronous and stateless. It creates a worktree, verifies an
existing worktree, or removes a verified clean worktree. A successful method
return is the result; there is no worktree lifecycle enum, recovery endpoint,
allocation table, cleanup disposition, or cleanup authority model.

The owning task may record when removal succeeds so its workflow monitor can
retry an interrupted cleanup. That timestamp is a durable operation fact in
the task record, not worktree-service state; a workflow authorizes cleanup only
after its own terminal result is durable. A failed attempt advances the task's
existing `updatedAt`, and policy monitors apply a one-minute retry cutoff. This
provides backoff and fair candidate selection without a worktree lifecycle or
dedicated retry field.

An interactive create request supplies a client-generated task ID used for
idempotent creation and a workspace ID, never a repository path, worktree
parent, or worktree path. The backend validates that ID, resolves the managed
repository, freezes an exact starting commit, and asks the service to provision
that task's worktree before any provider turn starts. Trusted internal
workflows generate their own task IDs. The owning task or workflow persists the
returned immutable `TaskWorktree` facts.

```mermaid
flowchart LR
    CALLER["Interactive task<br/>Cherry-pick<br/>CI Autofix"]
    SERVICE["WorktreeService<br/>provision · inspect · cleanup"]
    WORKTREE["Generated task branch/worktree<br/>at exact commit"]
    RUNTIME["Provider runtime<br/>task-owned App Server or Claude CLI"]
    RESULT["Remove clean worktree<br/>always retain generated branch"]
    PRESERVE["Refuse cleanup<br/>preserve dirty or unverifiable work"]

    CALLER -->|"validated task/workspace/commit"| SERVICE
    SERVICE -->|"verified TaskWorktree"| WORKTREE
    WORKTREE --> RUNTIME
    RUNTIME -->|"history reconciled · process tree VERIFIED_STOPPED"| SERVICE
    SERVICE -->|"clean and verified"| RESULT
    SERVICE -->|"dirty, open, or mismatched"| PRESERVE
```

## 2. Boundary

`WorktreeService` is an `agent-core` contract. Its one production
`ManagedWorktreeService` implementation lives in `app`, where ByteQuay already
owns workspace resolution, `WorkspacePaths`, and Git execution. This is a
module boundary, not a provider or workflow framework.

The service replaced both:

- `AgentWorkspaceService`, which leased an existing caller-provided path
  without creating or deleting it; and
- `CherryPickWorkspace`, which implemented a private cherry-pick-only worktree
  lifecycle.

The service does not fetch, push, choose a base, interpret agent messages,
decide a workflow result, delete a branch, or expose a path to the renderer.
Each caller first resolves and fetches its exact commit using its existing
workflow rules. Non-repository helpers that do not create a durable agent task
or mutate Git, such as the Better Words text rewrite, keep their isolated
runtime scratch and do not manufacture a Git worktree.

## 3. Contract

```java
package com.bytequay.agent.core;

public interface WorktreeService
{
    /** Creates and verifies the deterministic worktree owned by one task. */
    TaskWorktree provision(ProvisionWorktreeCommand command);

    /** Reads verified current Git facts for a persisted task worktree. */
    WorktreeSnapshot inspect(TaskWorktree worktree);

    /** Removes one verified clean worktree without deleting its branch. */
    void cleanup(TaskWorktree worktree);
}
```

`ProvisionWorktreeCommand` follows the existing `agent-core` convention for a
validated input to a state-changing service method. It contains only:

- `TaskId taskId`;
- trusted `workspaceId`;
- `RepositoryIdentity physicalRepository`, equal to the workspace's selected
  repository/`origin`; and
- an exact commit object ID already fetched into the managed repository.

The service derives both:

```text
<workspace-root>/worktrees/<task-id>
bytequay/task/<task-id>
```

`TaskWorktree` contains only the immutable facts needed to use and revalidate
that result:

```java
public record TaskWorktree(
        TaskId taskId,
        String workspaceId,
        RepositoryIdentity physicalRepository,
        Path canonicalWorktree,
        String generatedBranch,
        String startCommit) {}
```

`WorktreeSnapshot` contains the persisted `TaskWorktree`, exact current HEAD,
dirty flag, and open-Git-operation flag. `inspect(...)` either returns those
facts for an existing verified worktree or throws; it does not represent
absence with another state or optional HEAD.

The contract accepts only lowercase 40- or 64-character Git object IDs for the
exact start commit and observed HEAD. The generated branch is exactly
`bytequay/task/<task-id>`, and the normalized absolute path has the task ID as
its basename. The application implementation additionally proves containment
beneath the selected workspace's `worktrees` root and verifies symlink and Git
ownership.

The physical identity is deliberately distinct from a workflow's logical
monitored/upstream repository. Cherry-pick and CI retain those logical
identities in their own records; the worktree service verifies only the clone
that physically owns the Git worktree.

## 4. Synchronous behavior

### Provision

`provision(...)`:

1. Resolves the trusted workspace and selected managed repository.
2. Verifies the supplied physical repository identity and that the exact start
   object exists as a commit.
3. Derives the task path and generated branch; callers cannot override either.
4. Runs the equivalent of
   `git worktree add --quiet -b <branch> <path> <start-commit>`.
5. Verifies the canonical path, common Git directory, origin, branch, exact
   HEAD, clean index/worktree, and absence of an open Git operation.
6. Returns `TaskWorktree` only after every check succeeds.

Repeating the same command may return an already-created worktree only when all
of those exact facts match and it is still clean. A conflicting path, branch,
repository, or Git state is preserved and reported rather than adopted or
deleted.

The method may make a best-effort non-force removal after a definite local
failure when it can still prove that the just-created worktree is exact and
clean. If ByteQuay stops during `git worktree add`, a path or branch may remain.
The next explicit provision or workspace inspection reports it. ByteQuay does
not guess, force-remove it, or introduce a worktree recovery state machine.
Recovery automation can be designed later if observed failures demonstrate a
real need.

### Inspect

`inspect(...)` re-resolves the workspace from `workspaceId` and rejects the
worktree unless all persisted ownership facts still agree with the filesystem
and Git metadata. It verifies the canonical non-symlinked task path, workspace
root containment, common Git directory, selected repository origin, generated
branch, and current HEAD before reporting dirty or open-operation facts.

Callers inspect before every provider session that will mutate the worktree and
again before completion or cleanup. A missing or mismatched worktree is a normal
operation failure for the owning task/workflow; it is not converted into a
second worktree lifecycle.

### Cleanup

`cleanup(...)`:

1. Repeats the same exact ownership checks as `inspect(...)`.
2. Refuses a dirty worktree, staged or untracked files, an open cherry-pick,
   rebase, merge, or other Git operation.
3. Runs non-force `git worktree remove` for that exact path.
4. Verifies that Git no longer registers the worktree.
5. Leaves `bytequay/task/<task-id>` in the managed repository as the local
   result.

Cleanup never runs `--force` and never deletes the generated branch. Therefore
it needs no discard mode, remote-handoff mode, or authority token. If removal
fails, it throws and leaves the worktree and branch for the caller to display
or retry. A repeated call may succeed for an already-absent exact worktree only
when Git no longer registers the path and the generated result branch still
exists.

Deleting uncommitted work, a generated branch, or a branch after remote
handoff is outside this contract. Add a separate explicit operation and its
concrete user/handoff authority only when the product actually needs that
destructive behavior.

## 5. Caller ownership

The worktree service owns no allocation database. The owning durable task or
workflow stores the returned `TaskWorktree` fields and supplies that value to
later `inspect(...)` and `cleanup(...)` calls. Task and completion responses may
expose the safe generated branch name and verified HEAD as local-result facts,
but never the internal path.

### Interactive agent task

The application resolves the workspace's selected
`refs/remotes/origin/<default-branch>^{commit}`, validates the interactive
client-generated task ID, provisions its worktree, and stores the returned
facts with the task. All turns reuse it. Each active Codex turn gets a
task-owned App Server in that worktree. Its immutable model snapshot selects
the `NONE`, V1, or V2 launch profile; no execution environment or loopback
executor is registered, and `experimentalApi` is true only for the
background-terminal methods. Complete is allowed only after recursive item
history and background-terminal reconciliation finishes, the provider process
tree is `VERIFIED_STOPPED`, and `inspect(...)` reports a clean worktree with no
open Git operation. Cleanup removes the worktree and retains the generated
branch.

### Cherry-pick range

The execution provisions its task worktree at the synchronized frozen target
before the first cherry-pick and stores the returned facts with the execution.
The sequencer and conflict turns use that one worktree. A successful range
verifies a clean generated branch, removes the worktree, and retains the branch
for delivery from the managed repository.

Cancellation does not silently discard generated work. A clean cancelled
worktree may use normal cleanup and retains its branch; dirty or open work is
preserved for inspection. A future destructive discard requires its own direct
user action and design.

### CI Autofix

CI Autofix provisions a fresh task worktree at the verified PR head and stores
the returned facts with the run/task. It never adopts the preceding
cherry-pick worktree. Its Branch Rebase instance borrows the same worktree.

The worktree remains while the run is active or paused. After any clean,
quiescent terminal result, cleanup removes the worktree and retains its local
branch, including after a verified push. Dirty or uncertain work remains for
inspection; this contract has no discard operation.

### Branch Rebase

Branch Rebase receives the owner's `TaskWorktree`, inspects it, and uses it. It
never provisions, transfers, or cleans a worktree. Its caller keeps ownership
until the full top-level workflow has settled.

## 6. Safety invariants

- One backend-validated task ID deterministically names one path and generated
  branch; interactive IDs are client-generated idempotency values, not paths.
- A task is published only after `provision(...)` returns a verified clean
  worktree at the exact starting commit.
- Provider readiness failure creates no task worktree.
- Renderer and HTTP values never contain a worktree or repository path.
- Every operation re-resolves the workspace and revalidates path containment,
  symlink safety, common Git directory, origin, and generated branch.
- Worktree add/remove operations are serialized per managed repository; work
  inside different provisioned worktrees remains concurrent.
- Cleanup requires provider quiescence from the caller: recursive Codex item
  history and background-terminal reconciliation must finish before the
  task-owned process tree is `VERIFIED_STOPPED`. Cleanup independently refuses
  dirty or open Git state.
- Cleanup uses non-force worktree removal and always retains the generated
  branch.
- A mismatch or interrupted operation is preserved and reported, never
  force-repaired or deleted by inference.
- Workspace removal is blocked while a persisted task/workflow references a
  worktree or the managed worktree root is non-empty.

## 7. Acceptance criteria

1. Interactive creation validates its client-generated idempotent task ID and
   workspace ID; interactive, cherry-pick, and CI Autofix creation accepts no
   renderer-controlled filesystem path.
2. All three provision through the same service and receive distinct generated
   branches/worktrees under their workspace.
3. Provision returns only an exact, clean, task-derived worktree and preserves
   every mismatch for inspection.
4. Inspect rejects a missing, symlinked, escaped, wrong-repository, or
   wrong-branch worktree.
5. Cleanup rejects dirty files and open Git operations without changing them.
6. Successful cleanup removes only the verified worktree, without force, and
   retains its generated branch and commits.
7. Branch Rebase uses its caller's worktree and creates no nested worktree.
8. An interrupted provision is reported safely; no startup recovery state
   machine or destructive orphan cleanup is required for the first release.
