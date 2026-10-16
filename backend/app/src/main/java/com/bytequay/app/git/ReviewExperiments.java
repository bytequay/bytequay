/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.app.git;

import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Striped;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.Lock;

import static com.bytequay.app.utils.Locks.withLock;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Temporarily preserves private experiments while the caller moves a quiescent review checkout. */
@Component
public class ReviewExperiments
{
    // Linked worktrees share their repository's stash stack.
    private static final Striped<Lock> STASH_LOCKS = Striped.lock(64);
    private static final String DISCARDED_EXPERIMENTS = "Private experiments conflicted with the PR code and were discarded.";

    private final GitCommandRunner git;
    private final WorktreeService worktrees;

    public ReviewExperiments(GitCommandRunner git, WorktreeService worktrees)
    {
        this.git = requireNonNull(git, "git is null");
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
    }

    /** Automatic catch-up never captures, restores, or discards private work. */
    public void synchronizeClean(TaskWorktree worktree, String expectedHead, String targetHead, Runnable moveHead)
    {
        Lock lock = repositoryLock(worktree);
        withLock(lock, () -> {
            requireCleanHead(worktree, expectedHead);
            checkState(ownedStashes(worktree).isEmpty(), "Saved private experiments are waiting; update the review explicitly");
            moveHead.run();
            requireCleanHead(worktree, targetHead);
            return null;
        });
    }

    /** Explicit sync may discard experiments that cannot be restored; returned information always describes the verified target revision. */
    public Optional<String> synchronize(TaskWorktree worktree, String expectedHead, String targetHead, Runnable moveHead)
    {
        requireNonNull(expectedHead, "expectedHead is null");
        requireNonNull(targetHead, "targetHead is null");
        requireNonNull(moveHead, "moveHead is null");
        Lock lock = repositoryLock(worktree);
        return withLock(lock, () -> {
            WorktreeSnapshot current = worktrees.inspect(worktree);
            checkState(current.head().equals(expectedHead) || current.head().equals(targetHead), "Review HEAD changed; experiments were preserved");
            requireNoOperation(current);
            Optional<String> information = Optional.empty();
            // A later explicit sync finishes interrupted temporary-stash work without a separate recovery mode.
            for (Stash leftover : ownedStashes(worktree)) {
                information = restoreOrDiscard(worktree, current.head(), leftover);
                if (information.isPresent()) {
                    break;
                }
            }
            if (!output(worktree, "ls-files", "--unmerged", "-z").isEmpty()) {
                discard(worktree, current.head());
                information = Optional.of(DISCARDED_EXPERIMENTS);
            }
            current = requireHead(worktree, current.head());
            if (current.head().equals(targetHead)) {
                return information;
            }
            Optional<Stash> saved = capture(worktree, current);
            try {
                requireCleanHead(worktree, expectedHead);
                moveHead.run();
                requireCleanHead(worktree, targetHead);
            }
            catch (RuntimeException failure) {
                restoreAfterFailedMove(worktree, expectedHead, saved, failure);
                throw failure;
            }
            if (saved.isPresent()) {
                Optional<String> restored = restoreOrDiscard(worktree, targetHead, saved.orElseThrow());
                if (restored.isPresent()) {
                    return restored;
                }
            }
            return information;
        });
    }

    /** Discards private experiments for explicit finalization or a disclosed restoration conflict during sync. */
    public void discard(TaskWorktree worktree, String expectedHead)
    {
        requireNonNull(expectedHead, "expectedHead is null");
        Lock lock = repositoryLock(worktree);
        withLock(lock, () -> {
            requireHead(worktree, expectedHead);
            output(worktree, "reset", "--hard", expectedHead);
            requireHead(worktree, expectedHead);
            output(worktree, "clean", "-fd");
            requireCleanHead(worktree, expectedHead);
            for (Stash stash : ownedStashes(worktree)) {
                drop(worktree, stash);
            }
            requireCleanHead(worktree, expectedHead);
            return null;
        });
    }

    /** Whether a failed update left this review's experiments stashed. Listing the stash reflog takes no index lock, so a poll cannot fail an update. */
    public boolean hasSavedExperiment(TaskWorktree worktree)
    {
        return !ownedStashes(worktree).isEmpty();
    }

    private Lock repositoryLock(TaskWorktree worktree)
    {
        worktrees.inspect(worktree);
        String commonDirectory = output(worktree, "rev-parse", "--path-format=absolute", "--git-common-dir").strip();
        checkState(!commonDirectory.isBlank(), "Cannot identify the review repository");
        return STASH_LOCKS.get(commonDirectory);
    }

    private Optional<Stash> capture(TaskWorktree worktree, WorktreeSnapshot snapshot)
    {
        if (!snapshot.uncommitted()) {
            return Optional.empty();
        }
        checkState(ownedStashes(worktree).isEmpty(), "A temporary experiment stash already exists; the update was stopped");
        String marker = prefix(worktree) + "sync:" + UUID.randomUUID();
        output(worktree, "-c", "core.hooksPath=/dev/null", "stash", "push", "--include-untracked", "--message", marker);
        return Optional.of(ownedStashes(worktree).stream().filter(stash -> stash.subject().endsWith(": " + marker)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Cannot identify the saved review experiment; the update was stopped")));
    }

    private Optional<String> restoreOrDiscard(TaskWorktree worktree, String expectedHead, Stash stash)
    {
        if (!apply(worktree, expectedHead, stash)) {
            discard(worktree, expectedHead);
            return Optional.of(DISCARDED_EXPERIMENTS);
        }
        drop(worktree, stash);
        requireHead(worktree, expectedHead);
        return Optional.empty();
    }

    private boolean apply(TaskWorktree worktree, String expectedHead, Stash stash)
    {
        requireHead(worktree, expectedHead);
        Result result = git.run(worktree.canonicalWorktree(), "stash", "apply", "--index", stash.oid());
        requireHead(worktree, expectedHead);
        return result.successful();
    }

    private void restoreAfterFailedMove(TaskWorktree worktree, String expectedHead, Optional<Stash> saved, RuntimeException failure)
    {
        if (saved.isEmpty()) {
            return;
        }
        Stash stash = saved.orElseThrow();
        try {
            requireCleanHead(worktree, expectedHead);
            checkState(apply(worktree, expectedHead, stash), "Could not restore the saved experiment after the failed update");
            drop(worktree, stash);
            requireHead(worktree, expectedHead);
        }
        catch (RuntimeException recoveryFailure) {
            failure.addSuppressed(recoveryFailure);
            throw new IllegalStateException("Review update failed; experiments remain saved locally as " + stash.oid(), failure);
        }
    }

    private List<Stash> ownedStashes(TaskWorktree worktree)
    {
        ImmutableList.Builder<Stash> result = ImmutableList.builder();
        for (String line : output(worktree, "stash", "list", "--format=%H%x09%gd%x09%gs").lines().toList()) {
            String[] fields = line.split("\t", 3);
            if (fields.length == 3 && fields[2].contains(": " + prefix(worktree))) {
                checkState(fields[0].matches("[0-9a-f]{40}|[0-9a-f]{64}") && fields[1].matches("stash\\@\\{[0-9]+\\}"), "Cannot identify the private experiment stash");
                result.add(new Stash(fields[0], fields[1], fields[2]));
            }
        }
        return result.build();
    }

    private void drop(TaskWorktree worktree, Stash stash)
    {
        Optional<Stash> entry = ownedStashes(worktree).stream().filter(candidate -> candidate.oid().equals(stash.oid())).findFirst();
        if (entry.isEmpty()) {
            return;
        }
        String reference = entry.orElseThrow().reference();
        checkState(output(worktree, "rev-parse", "--verify", reference).strip().equals(stash.oid()), "Stash changed during review cleanup; try again");
        output(worktree, "stash", "drop", reference);
    }

    private WorktreeSnapshot requireHead(TaskWorktree worktree, String expectedHead)
    {
        WorktreeSnapshot snapshot = worktrees.inspect(worktree);
        checkState(snapshot.head().equals(expectedHead), "Review HEAD changed; experiments were preserved");
        requireNoOperation(snapshot);
        return snapshot;
    }

    private void requireCleanHead(TaskWorktree worktree, String expectedHead)
    {
        checkState(!requireHead(worktree, expectedHead).uncommitted(), "Review files changed during synchronization; experiments were preserved");
    }

    private static void requireNoOperation(WorktreeSnapshot snapshot)
    {
        checkState(!snapshot.gitOperationInProgress(), "Finish the open Git operation in the working copy; experiments were preserved");
    }

    private static String prefix(TaskWorktree worktree)
    {
        return "bytequay-review-experiment:" + worktree.taskId() + ":";
    }

    private String output(TaskWorktree worktree, String... arguments)
    {
        return git.run(worktree.canonicalWorktree(), arguments).requireOutput(message -> new IllegalStateException("Review Git operation failed: " + message));
    }

    private record Stash(String oid, String reference, String subject) {}
}
