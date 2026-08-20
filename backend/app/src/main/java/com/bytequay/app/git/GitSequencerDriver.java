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
import com.bytequay.app.git.SequenceState.ConflictedPath.IndexStage;
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
                .addAll(expectedConflicts.stream().map(ConflictedPath::path).toList())
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
                    Kind.UNSAFE, "", Optional.empty(), ImmutableList.of(),
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
                            Kind.COMPLETED, head, Optional.empty(), ImmutableList.of(), "")
                    : unsafe(head, "No Git operation is open but the worktree is not clean");
        }
        if (!openMarkers.equals(Set.of("CHERRY_PICK_HEAD")) || cherryPickHead.isEmpty()) {
            return unsafe(head, "Unexpected Git operation in progress: " + String.join(", ", openMarkers));
        }
        if (!conflicts.isEmpty()) {
            return new SequenceState(
                    Kind.STOPPED_CONFLICT, head, cherryPickHead, conflicts, "");
        }
        boolean nothingStaged = git.run(worktree, "diff", "--cached", "--quiet", "HEAD").successful();
        if (nothingStaged && !dirty) {
            return new SequenceState(
                    Kind.STOPPED_EMPTY, head, cherryPickHead, ImmutableList.of(), "");
        }
        return new SequenceState(
                Kind.STOPPED_RESOLVED, head, cherryPickHead, ImmutableList.of(), "");
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

    /**
     * Replaces a temporary conflict commit and its direct fixup with one final
     * cherry-pick commit, or drops both when their final tree equals the parent.
     */
    public NormalizationResult normalizeConflictPair(
            Path worktree,
            String originalHead,
            String conflictCommit,
            String fixupCommit)
    {
        if (!parent(worktree, conflictCommit).equals(originalHead)
                || !parent(worktree, fixupCommit).equals(conflictCommit)) {
            return NormalizationResult.unsafe(head(worktree), "Conflict pair is not H0 -> C -> F");
        }
        String finalTree = tree(worktree, fixupCommit);
        if (finalTree.isEmpty()) {
            return NormalizationResult.unsafe(head(worktree), "Cannot read the final conflict tree");
        }
        SequenceState before = observe(worktree);
        boolean empty = finalTree.equals(tree(worktree, originalHead));
        if (before.kind() == Kind.COMPLETED && before.head().equals(originalHead) && empty) {
            return new NormalizationResult(NormalizationResult.Kind.EMPTY, originalHead, "");
        }
        if (before.kind() == Kind.COMPLETED
                && !before.head().equals(fixupCommit)
                && parent(worktree, before.head()).equals(originalHead)
                && tree(worktree, before.head()).equals(finalTree)
                && message(worktree, before.head()).equals(message(worktree, conflictCommit))) {
            return new NormalizationResult(NormalizationResult.Kind.APPLIED, before.head(), "");
        }
        if (before.kind() == Kind.COMPLETED && before.head().equals(fixupCommit) && empty) {
            git.run(worktree, "reset", "--hard", originalHead);
            SequenceState after = observe(worktree);
            return after.kind() == Kind.COMPLETED && after.head().equals(originalHead)
                    ? new NormalizationResult(NormalizationResult.Kind.EMPTY, originalHead, "")
                    : NormalizationResult.unsafe(after.head(), "Empty conflict pair was not removed cleanly");
        }
        if (before.kind() == Kind.COMPLETED && before.head().equals(fixupCommit)) {
            Result reset = git.run(worktree, "reset", "--soft", originalHead);
            if (!reset.successful()) {
                return NormalizationResult.unsafe(before.head(), "Cannot prepare conflict pair normalization");
            }
        }
        else if (!normalizationIsPrepared(worktree, originalHead, finalTree)) {
            return NormalizationResult.unsafe(before.head(), "Conflict pair is not at a recoverable normalization point");
        }
        return finishNormalizedCommit(worktree, originalHead, conflictCommit, finalTree);
    }

    private NormalizationResult finishNormalizedCommit(
            Path worktree,
            String originalHead,
            String conflictCommit,
            String finalTree)
    {
        Result committed = git.run(worktree, "commit", "--quiet", "--no-gpg-sign", "-C", conflictCommit);
        SequenceState after = observe(worktree);
        if (!committed.successful()
                || after.kind() != Kind.COMPLETED
                || !parent(worktree, after.head()).equals(originalHead)
                || !tree(worktree, after.head()).equals(finalTree)
                || !message(worktree, after.head()).equals(message(worktree, conflictCommit))) {
            return NormalizationResult.unsafe(after.head(), "Conflict pair did not normalize to one equivalent commit");
        }
        return new NormalizationResult(NormalizationResult.Kind.APPLIED, after.head(), "");
    }

    private boolean normalizationIsPrepared(Path worktree, String originalHead, String expectedTree)
    {
        if (!head(worktree).equals(originalHead)) {
            return false;
        }
        Result writtenTree = git.run(worktree, "write-tree");
        String status = git.run(worktree, "status", "--porcelain=v1", "--untracked-files=all").output();
        return writtenTree.successful()
                && writtenTree.output().strip().equals(expectedTree)
                && git.run(worktree, "diff", "--quiet").successful()
                && git.run(worktree, "ls-files", "-u").output().isBlank()
                && status.lines().noneMatch(line -> line.startsWith("??"));
    }

    private Set<String> openMarkers(Path worktree)
    {
        Set<String> open = new LinkedHashSet<>();
        for (String marker : ImmutableList.<String>builder().addAll(OPERATION_MARKERS).add("sequencer").build()) {
            if (exists(worktree, marker)) {
                open.add(marker);
            }
        }
        // The sequencer directory can outlive a completed operation until the next
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

    private List<ConflictedPath> conflicts(Path worktree)
    {
        Result unmerged = git.run(worktree, "ls-files", "-u");
        if (!unmerged.successful() || unmerged.output().isBlank()) {
            return ImmutableList.of();
        }
        Map<String, List<IndexStage>> stages = new LinkedHashMap<>();
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
            stages.computeIfAbsent(path, key -> new java.util.ArrayList<>()).add(
                    new IndexStage(Integer.parseInt(fields[2]), fields[0], fields[1]));
        }
        ImmutableList.Builder<ConflictedPath> conflicts = ImmutableList.builder();
        stages.forEach((path, present) -> {
            present.sort(java.util.Comparator.comparingInt(IndexStage::stage));
            Set<Integer> stageNumbers = present.stream()
                    .map(IndexStage::stage)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            String mode = present.getFirst().mode();
            conflicts.add(new ConflictedPath(path, classify(stageNumbers, mode), present));
        });
        return conflicts.build();
    }

    private String parent(Path worktree, String commit)
    {
        Result result = git.run(worktree, "rev-parse", commit + "^");
        return result.successful() ? result.output().strip() : "";
    }

    private String head(Path worktree)
    {
        Result result = git.run(worktree, "rev-parse", "HEAD");
        return result.successful() ? result.output().strip() : "";
    }

    private String tree(Path worktree, String commit)
    {
        Result result = git.run(worktree, "rev-parse", commit + "^{tree}");
        return result.successful() ? result.output().strip() : "";
    }

    private String message(Path worktree, String commit)
    {
        Result result = git.run(worktree, "log", "-1", "--format=%B", commit);
        return result.successful() ? result.output() : "";
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
                Kind.UNSAFE, head, Optional.empty(), ImmutableList.of(), diagnostics);
    }

    /** Result of replacing temporary conflict history with its final answer. */
    public record NormalizationResult(Kind kind, String head, String diagnostics)
    {
        public NormalizationResult
        {
            requireNonNull(kind, "kind is null");
            requireNonNull(head, "head is null");
            requireNonNull(diagnostics, "diagnostics is null");
        }

        private static NormalizationResult unsafe(String head, String diagnostics)
        {
            return new NormalizationResult(Kind.UNSAFE, head, diagnostics);
        }

        public enum Kind
        {
            APPLIED,
            EMPTY,
            UNSAFE
        }
    }
}
