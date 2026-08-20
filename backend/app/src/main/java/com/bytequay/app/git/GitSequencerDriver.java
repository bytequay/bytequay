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
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Drives one Git sequencer, whether it was started by a cherry-pick or a rebase.
 *
 * <p>Both commands share the sequencer: it applies commits until one conflicts
 * or proves empty, records the remainder in a todo file, and resumes on
 * {@code --continue} or {@code --skip}. This helper performs one bounded action
 * at a time and then reports what the repository says, so the caller never has
 * to trust an exit code.
 *
 * <p>Public only so sibling implementation packages can use it. It is internal
 * to this Maven module and belongs in no public port.
 */
@Component
public final class GitSequencerDriver
{
    /**
     * Only leftover conflict markers may fail this check.
     *
     * <p>Whitespace the source author wrote is copied faithfully, and a
     * repository's own {@code core.whitespace} must not change what ByteQuay
     * accepts.
     */
    private static final List<String> MARKER_ONLY_WHITESPACE = ImmutableList.of(
            "-c", "core.whitespace=-trailing-space,-space-before-tab,-indent-with-non-tab,-tab-in-indent");
    private static final List<String> OPERATION_MARKERS = ImmutableList.of(
            "CHERRY_PICK_HEAD", "MERGE_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply");

    private final GitCommandRunner git;

    public GitSequencerDriver(GitCommandRunner git)
    {
        this.git = requireNonNull(git, "git is null");
    }

    /**
     * Starts one cherry-pick sequence over an ordered commit list.
     *
     * @param worktree leased worktree to mutate
     * @param expectedHead HEAD the caller believes it is starting from
     * @param sourceShas commits to apply, in order
     * @return state after the sequencer stops or completes
     */
    public SequenceState beginCherryPick(Path worktree, String expectedHead, List<String> sourceShas)
    {
        requireNonNull(worktree, "worktree is null");
        requireNonNull(expectedHead, "expectedHead is null");
        requireNonNull(sourceShas, "sourceShas is null");
        if (sourceShas.isEmpty()) {
            return observe(worktree);
        }
        SequenceState before = observe(worktree);
        if (before.kind() != Kind.COMPLETED) {
            return unsafe(before.head(), "A Git operation is already open in " + worktree);
        }
        if (!before.head().equals(expectedHead)) {
            return unsafe(before.head(), "Worktree moved before the sequence started");
        }
        git.run(worktree, ImmutableList.<String>builder()
                .add("cherry-pick", "-x")
                .addAll(sourceShas)
                .build());
        return observe(worktree);
    }

    /**
     * Resumes a sequence whose conflicts are fully staged.
     *
     * @param worktree leased worktree
     * @param expectedStoppedCommit commit the caller believes the sequencer stopped on
     * @return state after the sequencer stops again or completes
     */
    public SequenceState continueSequence(Path worktree, String expectedStoppedCommit)
    {
        SequenceState before = observe(worktree);
        if (before.kind() != Kind.STOPPED_RESOLVED
                || !before.stoppedCommit().orElseThrow().equals(expectedStoppedCommit)) {
            return unsafe(before.head(), "Sequence is not a staged resolution of " + expectedStoppedCommit);
        }
        // No editor is opened for a plain continue, and the runner already
        // disables the terminal prompt.
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
        // A skip resumes the rest of the batch, so HEAD moving afterwards is
        // normal: it means later commits applied. What must never happen is the
        // skipped commit producing a commit of its own, and the caller proves
        // that by reconciling every created commit against its -x line.
        return observe(worktree);
    }

    /**
     * Aborts an open sequence and verifies the branch returned to where it was.
     *
     * @param worktree leased worktree
     * @param expectedTip tip the abort must restore
     * @return state after the abort
     */
    public SequenceState abort(Path worktree, String expectedTip)
    {
        git.run(worktree, ImmutableList.of("cherry-pick", "--abort"));
        SequenceState after = observe(worktree);
        if (!after.head().equals(expectedTip) || after.kind() != Kind.COMPLETED) {
            return unsafe(after.head(), "Abort did not restore " + expectedTip);
        }
        return after;
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
            return new SequenceState(
                    Kind.UNSAFE, "", Optional.empty(), ImmutableList.of(), ImmutableList.of(),
                    "Cannot read HEAD: " + headResult.output().strip());
        }
        String head = headResult.output().strip();

        Optional<String> cherryPickHead = markerCommit(worktree, "CHERRY_PICK_HEAD");
        Set<String> openMarkers = openMarkers(worktree);
        List<ConflictedPath> conflicts = conflicts(worktree);
        boolean dirty = !git.run(worktree, "status", "--porcelain=v1", "--untracked-files=all")
                .output().isBlank();

        if (openMarkers.isEmpty()) {
            return conflicts.isEmpty() && !dirty
                    ? new SequenceState(
                            Kind.COMPLETED, head, Optional.empty(), remaining(worktree),
                            ImmutableList.of(), "")
                    : unsafe(head, "No Git operation is open but the worktree is not clean");
        }
        if (!openMarkers.equals(Set.of("CHERRY_PICK_HEAD")) || cherryPickHead.isEmpty()) {
            return unsafe(head, "Unexpected Git operation in progress: " + String.join(", ", openMarkers));
        }
        if (!conflicts.isEmpty()) {
            return new SequenceState(
                    Kind.STOPPED_CONFLICT, head, cherryPickHead, remaining(worktree), conflicts, "");
        }
        boolean nothingStaged = git.run(worktree, "diff", "--cached", "--quiet", "HEAD").successful();
        if (nothingStaged && !dirty) {
            return new SequenceState(
                    Kind.STOPPED_EMPTY, head, cherryPickHead, remaining(worktree), ImmutableList.of(), "");
        }
        return new SequenceState(
                Kind.STOPPED_RESOLVED, head, cherryPickHead, remaining(worktree), ImmutableList.of(), "");
    }

    /**
     * Reports whether a range carries a leftover conflict marker.
     *
     * @param worktree worktree to inspect
     * @param fromSha range start, exclusive
     * @param toSha range end, inclusive
     * @return true when no marker was left behind
     */
    public boolean withoutConflictMarkers(Path worktree, String fromSha, String toSha)
    {
        return git.run(worktree, ImmutableList.<String>builder()
                .addAll(MARKER_ONLY_WHITESPACE)
                .add("diff", "--check", fromSha + ".." + toSha)
                .build())
                .successful();
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
        Set<String> open = new LinkedHashSet<>();
        for (String marker : ImmutableList.<String>builder().addAll(OPERATION_MARKERS).add("sequencer").build()) {
            if (exists(worktree, marker)) {
                open.add(marker);
            }
        }
        // The sequencer directory outlives a completed batch until the next
        // command clears it, so on its own it does not mean work is open.
        if (open.equals(Set.of("sequencer"))) {
            return Set.of();
        }
        open.remove("sequencer");
        return open;
    }

    private boolean exists(Path worktree, String marker)
    {
        Result path = git.run(worktree, "rev-parse", "--git-path", marker);
        if (!path.successful()) {
            return false;
        }
        Path resolved = worktree.resolve(path.output().strip()).normalize();
        return java.nio.file.Files.exists(resolved);
    }

    private Optional<String> markerCommit(Path worktree, String marker)
    {
        Result result = git.run(worktree, "rev-parse", "--verify", "--quiet", marker);
        return result.successful() && !result.output().isBlank()
                ? Optional.of(result.output().strip())
                : Optional.empty();
    }

    private List<String> remaining(Path worktree)
    {
        Result todo = git.run(worktree, "rev-parse", "--git-path", "sequencer/todo");
        if (!todo.successful()) {
            return ImmutableList.of();
        }
        Path file = worktree.resolve(todo.output().strip()).normalize();
        try {
            if (!java.nio.file.Files.isRegularFile(file)) {
                return ImmutableList.of();
            }
            ImmutableList.Builder<String> abbreviated = ImmutableList.builder();
            for (String line : java.nio.file.Files.readAllLines(file)) {
                String[] fields = line.strip().split("\\s+");
                if (fields.length >= 2 && fields[0].equals("pick")) {
                    abbreviated.add(fields[1]);
                }
            }
            return fullShas(worktree, abbreviated.build());
        }
        catch (java.io.IOException e) {
            return ImmutableList.of();
        }
    }

    /**
     * Git's todo file stores abbreviated ids.
     *
     * <p>A caller compares these against full plan SHAs, so returning them
     * abbreviated would silently never match. One batched rev-parse resolves
     * them all.
     */
    private List<String> fullShas(Path worktree, List<String> abbreviated)
    {
        if (abbreviated.isEmpty()) {
            return ImmutableList.of();
        }
        Result resolved = git.run(worktree, ImmutableList.<String>builder()
                .add("rev-parse")
                .addAll(abbreviated)
                .build());
        if (!resolved.successful()) {
            return ImmutableList.of();
        }
        ImmutableList.Builder<String> commits = ImmutableList.builder();
        for (String line : resolved.output().lines().toList()) {
            if (!line.isBlank()) {
                commits.add(line.strip());
            }
        }
        return commits.build();
    }

    private List<ConflictedPath> conflicts(Path worktree)
    {
        Result unmerged = git.run(worktree, "ls-files", "-u");
        if (!unmerged.successful() || unmerged.output().isBlank()) {
            return ImmutableList.of();
        }
        Map<String, Set<Integer>> stages = new LinkedHashMap<>();
        Map<String, String> modes = new LinkedHashMap<>();
        for (String line : unmerged.output().lines().toList()) {
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            String[] fields = line.substring(0, tab).strip().split("\\s+");
            if (fields.length < 3) {
                continue;
            }
            String path = line.substring(tab + 1);
            stages.computeIfAbsent(path, key -> new LinkedHashSet<>()).add(Integer.parseInt(fields[2]));
            modes.putIfAbsent(path, fields[0]);
        }
        ImmutableList.Builder<ConflictedPath> conflicts = ImmutableList.builder();
        stages.forEach((path, present) ->
                conflicts.add(new ConflictedPath(path, classify(present, modes.get(path)))));
        return conflicts.build();
    }

    private static ConflictedPath.Type classify(Set<Integer> stages, String mode)
    {
        if ("160000".equals(mode)) {
            return ConflictedPath.Type.SUBMODULE;
        }
        if (!stages.contains(1)) {
            return ConflictedPath.Type.ADD_ADD;
        }
        return stages.contains(2) && stages.contains(3)
                ? ConflictedPath.Type.CONTENT
                : ConflictedPath.Type.MODIFY_DELETE;
    }

    private static SequenceState unsafe(String head, String diagnostics)
    {
        return new SequenceState(
                Kind.UNSAFE, head, Optional.empty(), ImmutableList.of(), ImmutableList.of(), diagnostics);
    }
}
