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

import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.app.git.SequenceState.ConflictedPath;
import com.bytequay.app.git.SequenceState.Kind;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Drives guarded cherry-pick operations for one source commit at a time.
 *
 * <p>This helper performs one bounded action and then reports what the
 * repository says, so the caller never has to trust an exit code. A future
 * rebase service may reuse observation facts, but its commands and markers are
 * deliberately not generalized here.
 *
 * <p>Public only so sibling implementation packages can use it. It is internal
 * to this Maven module and belongs in no public port.
 */
@Component
public final class GitSequencerDriver
{
    private static final List<String> MARKERS = ImmutableList.<String>builder()
            .addAll(GitCommandRunner.OPERATION_MARKERS)
            .add("sequencer")
            .build();

    private final GitCommandRunner git;

    public GitSequencerDriver(GitCommandRunner git)
    {
        this.git = requireNonNull(git, "git is null");
    }

    /**
     * Starts one cherry-pick for one source commit.
     *
     * @param worktree leased worktree to mutate
     * @param expectedHead HEAD the caller believes it is starting from
     * @param sourceSha commit to apply
     * @return state after the cherry-pick stops or completes
     */
    public SequenceState beginCherryPick(Path worktree, String expectedHead, String sourceSha)
    {
        requireNonNull(worktree, "worktree is null");
        requireNonNull(expectedHead, "expectedHead is null");
        requireNonNull(sourceSha, "sourceSha is null");
        SequenceState before = observe(worktree);
        if (before.kind() != Kind.COMPLETED) {
            return unsafe(before.head(), "A Git operation is already open in " + worktree);
        }
        if (!before.head().equals(expectedHead)) {
            return unsafe(before.head(), "Worktree moved before the sequence started");
        }
        git.run(worktree, "cherry-pick", "-x", sourceSha);
        return observe(worktree);
    }

    /**
     * Commits Git's exact conflicted snapshot before an agent repairs it.
     *
     * <p>The caller persists the conflict stages before invoking this method.
     * Only the paths Git reports as unmerged are staged, then the program—not
     * the agent—closes the cherry-pick operation.
     */
    public SequenceState commitConflictSnapshot(
            Path worktree,
            String expectedHead,
            String expectedStoppedCommit,
            List<ConflictedPath> expectedConflicts)
    {
        requireNonNull(expectedConflicts, "expectedConflicts is null");
        SequenceState before = observe(worktree);
        if (before.kind() != Kind.STOPPED_CONFLICT
                || !before.head().equals(expectedHead)
                || !before.stoppedCommit().orElseThrow().equals(expectedStoppedCommit)
                || !before.conflicts().equals(expectedConflicts)) {
            return unsafe(before.head(), "Conflict changed before its snapshot was committed");
        }
        git.run(worktree, ImmutableList.<String>builder()
                .add("add", "-A", "--")
                .addAll(expectedConflicts.stream().map(ConflictedPath::path).collect(toImmutableList()))
                .build());
        SequenceState staged = observe(worktree);
        if (staged.kind() != Kind.STOPPED_RESOLVED
                || !staged.head().equals(expectedHead)
                || !staged.stoppedCommit().orElseThrow().equals(expectedStoppedCommit)) {
            return unsafe(staged.head(), "Conflict snapshot was not staged exactly");
        }
        git.run(worktree, ImmutableList.of("cherry-pick", "--continue"));
        return observe(worktree);
    }

    /**
     * Skips a stop that is provably empty, and proves it again afterwards.
     *
     * @param worktree leased worktree
     * @param expectedHead HEAD that must not move
     * @param expectedStoppedCommit commit the sequencer must be stopped on
     * @return state after the skip, or {@code UNSAFE} when anything disagrees
     */
    public SequenceState skipEmpty(Path worktree, String expectedHead, String expectedStoppedCommit)
    {
        SequenceState before = observe(worktree);
        if (before.kind() != Kind.STOPPED_EMPTY) {
            return unsafe(before.head(), "Sequence is not stopped on an empty commit");
        }
        if (!before.head().equals(expectedHead)) {
            return unsafe(before.head(), "Worktree moved before the empty skip");
        }
        if (!before.stoppedCommit().orElseThrow().equals(expectedStoppedCommit)) {
            return unsafe(before.head(), "Sequence stopped on a different commit than expected");
        }
        git.run(worktree, ImmutableList.of("cherry-pick", "--skip"));
        return observe(worktree);
    }

    /**
     * Reads the repository without changing it.
     *
     * @param worktree worktree to inspect
     * @return current sequencer state
     */
    public SequenceState observe(Path worktree)
    {
        requireNonNull(worktree, "worktree is null");
        Result headResult = git.run(worktree, "rev-parse", "HEAD");
        if (!headResult.successful()) {
            return new SequenceState(Kind.UNSAFE, "", Optional.empty(), ImmutableList.of(), "Cannot read HEAD: " + headResult.output().strip());
        }
        String head = headResult.output().strip();

        Optional<String> cherryPickHead = git.markerCommit(worktree, "CHERRY_PICK_HEAD");
        Set<String> openMarkers = openMarkers(worktree);
        List<ConflictedPath> conflicts = git.conflicts(worktree);
        boolean dirty = !git.run(worktree, "status", "--porcelain=v1", "--untracked-files=all")
                .output().isBlank();

        if (openMarkers.isEmpty()) {
            return conflicts.isEmpty() && !dirty
                    ? new SequenceState(
                            Kind.COMPLETED, head, Optional.empty(), ImmutableList.of(), "")
                    : unsafe(head, "No Git operation is open but the worktree is not clean");
        }
        if (!openMarkers.equals(ImmutableSet.of("CHERRY_PICK_HEAD")) || cherryPickHead.isEmpty()) {
            return unsafe(head, "Unexpected Git operation in progress: " + String.join(", ", openMarkers));
        }
        if (!conflicts.isEmpty()) {
            return new SequenceState(Kind.STOPPED_CONFLICT, head, cherryPickHead, conflicts, "");
        }
        boolean nothingStaged = git.run(worktree, "diff", "--cached", "--quiet", "HEAD").successful();
        if (nothingStaged && !dirty) {
            return new SequenceState(Kind.STOPPED_EMPTY, head, cherryPickHead, ImmutableList.of(), "");
        }
        return new SequenceState(Kind.STOPPED_RESOLVED, head, cherryPickHead, ImmutableList.of(), "");
    }

    /**
     * Reports whether a worktree is clean with no operation open.
     *
     * @param worktree worktree to inspect
     * @return true when nothing is in progress and nothing is modified
     */
    public boolean cleanAndResolved(Path worktree)
    {
        return observe(worktree).kind() == Kind.COMPLETED;
    }

    private Set<String> openMarkers(Path worktree)
    {
        Set<String> open = git.openMarkers(worktree, MARKERS);
        // The sequencer directory can outlive a completed operation until the next
        // command clears it, so on its own it does not mean work is open.
        if (open.equals(ImmutableSet.of("sequencer"))) {
            return ImmutableSet.of();
        }
        open.remove("sequencer");
        return open;
    }

    private static SequenceState unsafe(String head, String diagnostics)
    {
        return new SequenceState(Kind.UNSAFE, head, Optional.empty(), ImmutableList.of(), diagnostics);
    }
}
