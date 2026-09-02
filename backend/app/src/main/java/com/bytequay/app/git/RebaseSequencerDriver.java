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
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Runs guarded rebase commands and reports only observable Git state. */
@Component
public final class RebaseSequencerDriver
{
    private static final Set<String> UNMERGED_CODES = ImmutableSet.of(
            "DD", "AU", "UD", "UA", "DU", "AA", "UU");
    private static final List<String> OPERATION_MARKERS = ImmutableList.of(
            "CHERRY_PICK_HEAD", "MERGE_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply");

    private final GitCommandRunner git;

    public RebaseSequencerDriver(GitCommandRunner git)
    {
        this.git = requireNonNull(git, "git is null");
    }

    /** Starts one non-interactive rebase at the exact expected work tip. */
    public SequenceState begin(Path worktree, String expectedHead, String baseSha)
    {
        SequenceState before = observe(worktree);
        if (before.kind() != Kind.COMPLETED || !before.head().equals(expectedHead)) {
            return unsafe(before.head(), "Worktree is not clean at the recorded work tip");
        }
        git.run(worktree, ImmutableList.of(
                "-c", "commit.gpgSign=false", "rebase", "--no-autosquash", baseSha));
        return observe(worktree);
    }

    /** Continues only the same fully staged rebase stop the caller inspected. */
    public SequenceState continueRebase(
            Path worktree,
            String expectedHead,
            String expectedStoppedCommit)
    {
        SequenceState before = observe(worktree);
        if (before.kind() != Kind.STOPPED_RESOLVED
                || !before.head().equals(expectedHead)
                || !before.stoppedCommit().filter(expectedStoppedCommit::equals).isPresent()) {
            return unsafe(before.head(), "Rebase resolution changed before continue");
        }
        git.run(worktree, ImmutableList.of(
                "-c", "core.editor=true",
                "-c", "commit.gpgSign=false",
                "rebase", "--continue"));
        return observe(worktree);
    }

    /** Skips only the same proven-empty rebase stop the caller inspected. */
    public SequenceState skipEmpty(
            Path worktree,
            String expectedHead,
            String expectedStoppedCommit)
    {
        SequenceState before = observe(worktree);
        if (before.kind() != Kind.STOPPED_EMPTY
                || !before.head().equals(expectedHead)
                || !before.stoppedCommit().filter(expectedStoppedCommit::equals).isPresent()) {
            return unsafe(before.head(), "Rebase is not stopped empty at the recorded commit");
        }
        git.run(worktree, "rebase", "--skip");
        return observe(worktree);
    }

    /** Aborts only an open rebase and proves the original work tip was restored. */
    public SequenceState abort(Path worktree, String originalWorkTip)
    {
        SequenceState before = observe(worktree);
        if (before.kind() == Kind.COMPLETED) {
            return before.head().equals(originalWorkTip)
                    ? before
                    : unsafe(before.head(), "Rebase is closed away from the original work tip");
        }
        if (before.stoppedCommit().isEmpty()) {
            return unsafe(before.head(), "No guarded rebase is open to abort");
        }
        git.run(worktree, "rebase", "--abort");
        SequenceState after = observe(worktree);
        return after.kind() == Kind.COMPLETED && after.head().equals(originalWorkTip)
                ? after
                : unsafe(after.head(), "Abort did not restore the original work tip");
    }

    /** Reads a completed or stopped rebase without trusting command output. */
    public SequenceState observe(Path worktree)
    {
        requireNonNull(worktree, "worktree is null");
        Result headResult = git.run(worktree, "rev-parse", "HEAD");
        if (!headResult.successful()) {
            return unsafe("", "Cannot read HEAD: " + headResult.output().strip());
        }
        String head = headResult.output().strip();
        Set<String> openMarkers = openMarkers(worktree);
        String status = git.run(worktree, "status", "--porcelain=v1", "--untracked-files=all").output();
        List<ConflictedPath> conflicts = conflicts(worktree);

        if (openMarkers.isEmpty()) {
            return conflicts.isEmpty() && status.isBlank()
                    ? completed(head)
                    : unsafe(head, "No rebase is open but the worktree is not clean");
        }
        boolean oneRebaseMarker = openMarkers.equals(ImmutableSet.of("rebase-merge"))
                || openMarkers.equals(ImmutableSet.of("rebase-apply"));
        Optional<String> rebaseHead = markerCommit(worktree, "REBASE_HEAD");
        if (!oneRebaseMarker || rebaseHead.isEmpty()) {
            return unsafe(head, "Unexpected Git operation in progress: " + String.join(", ", openMarkers));
        }
        if (unsafeWorktreeChanges(status)) {
            return unsafe(head, "Rebase worktree has unstaged or untracked changes");
        }
        if (!conflicts.isEmpty()) {
            return new SequenceState(Kind.STOPPED_CONFLICT, head, rebaseHead, conflicts, "");
        }
        boolean nothingStaged = git.run(worktree, "diff", "--cached", "--quiet", "HEAD").successful();
        if (nothingStaged && status.isBlank()) {
            return new SequenceState(Kind.STOPPED_EMPTY, head, rebaseHead, ImmutableList.of(), "");
        }
        boolean nothingUnstaged = git.run(worktree, "diff", "--quiet").successful();
        if (!nothingStaged && nothingUnstaged) {
            return new SequenceState(Kind.STOPPED_RESOLVED, head, rebaseHead, ImmutableList.of(), "");
        }
        return unsafe(head, "Rebase resolution is only partly staged");
    }

    /** Reports whether the rebased range contains no conflict marker. */
    public boolean withoutConflictMarkers(Path worktree, String baseSha, String headSha)
    {
        return git.run(worktree, ImmutableList.of(
                "-c",
                "core.whitespace=-trailing-space,-space-before-tab,-indent-with-non-tab,-tab-in-indent",
                "diff",
                "--check",
                baseSha + ".." + headSha)).successful();
    }

    private Set<String> openMarkers(Path worktree)
    {
        List<Path> paths = git.gitPaths(worktree, OPERATION_MARKERS);
        Set<String> open = new LinkedHashSet<>();
        for (int marker = 0; marker < paths.size(); marker++) {
            if (java.nio.file.Files.exists(paths.get(marker))) {
                open.add(OPERATION_MARKERS.get(marker));
            }
        }
        return open;
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
        for (String line : unmerged.output().lines().collect(toImmutableList())) {
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            String[] fields = line.substring(0, tab).strip().split("\\s+");
            if (fields.length < 3) {
                continue;
            }
            stages.computeIfAbsent(line.substring(tab + 1), ignored -> new java.util.ArrayList<>())
                    .add(new IndexStage(Integer.parseInt(fields[2]), fields[0], fields[1]));
        }
        ImmutableList.Builder<ConflictedPath> result = ImmutableList.builder();
        stages.forEach((path, present) -> {
            present.sort(java.util.Comparator.comparingInt(IndexStage::stage));
            Set<Integer> numbers = present.stream()
                    .map(IndexStage::stage)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            result.add(new ConflictedPath(path, classify(numbers, present.getFirst().mode()), present));
        });
        return result.build();
    }

    private static boolean unsafeWorktreeChanges(String status)
    {
        return status.lines().anyMatch(line -> line.startsWith("??")
                || (line.length() >= 2
                        && line.charAt(1) != ' '
                        && !UNMERGED_CODES.contains(line.substring(0, 2))));
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

    private static SequenceState completed(String head)
    {
        return new SequenceState(Kind.COMPLETED, head, Optional.empty(), ImmutableList.of(), "");
    }

    private static SequenceState unsafe(String head, String diagnostic)
    {
        return new SequenceState(Kind.UNSAFE, head, Optional.empty(), ImmutableList.of(), diagnostic);
    }
}
