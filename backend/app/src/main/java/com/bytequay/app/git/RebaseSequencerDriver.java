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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.git.SequenceState.completed;
import static com.bytequay.app.git.SequenceState.unsafe;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Runs guarded rebase commands and reports only observable Git state. */
@Component
public final class RebaseSequencerDriver
{
    private static final Set<String> UNMERGED_CODES = ImmutableSet.of("DD", "AU", "UD", "UA", "DU", "AA", "UU");

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
        // Rebase has no --no-overwrite-ignore: it would silently replace an ignored file that the base or a replayed commit tracks.
        Optional<String> ignoredFileConflict = ignoredFileConflict(worktree, expectedHead, baseSha);
        if (ignoredFileConflict.isPresent()) {
            return unsafe(expectedHead, ignoredFileConflict.orElseThrow());
        }
        git.run(worktree, ImmutableList.of("-c", "commit.gpgSign=false", "rebase", "--no-autosquash", baseSha));
        return observe(worktree);
    }

    private Optional<String> ignoredFileConflict(Path worktree, String head, String baseSha)
    {
        // ponytail: checked once before the rebase starts; ignored files a conflict turn creates are not rechecked before --continue.
        Result ignored = git.run(worktree, "ls-files", "-z", "--others", "--ignored", "--exclude-standard", "--directory", "--no-empty-directory");
        if (!ignored.successful() || ignored.truncated()) {
            return Optional.of("Cannot list the ignored files before the rebase");
        }
        List<String> pathspecs = Arrays.stream(ignored.output().split("\0"))
                .filter(path -> !path.isEmpty())
                // Without its slash, an ignored directory also matches a file the rebase would write at that name.
                .map(path -> path.endsWith("/") ? path.substring(0, path.length() - 1) : path)
                .collect(toImmutableList());
        if (pathspecs.isEmpty()) {
            return Optional.empty();
        }
        // The checkout of the base writes what differs from the tip; each replayed commit writes what it changes.
        for (List<String> written : ImmutableList.of(
                ImmutableList.of("diff", "--name-only", head, baseSha),
                ImmutableList.of("log", "--format=", "--name-only", baseSha + ".." + head))) {
            Result touched = git.run(worktree, ImmutableList.<String>builder()
                    .add("--literal-pathspecs")
                    .addAll(written)
                    .add("--")
                    .addAll(pathspecs)
                    .build());
            if (!touched.successful() || touched.truncated()) {
                return Optional.of("Cannot compare the ignored files with the rebase");
            }
            Optional<String> path = touched.output().lines().filter(line -> !line.isBlank()).findFirst();
            if (path.isPresent()) {
                return Optional.of("Rebase would overwrite the ignored file " + path.orElseThrow());
            }
        }
        return Optional.empty();
    }

    /** Continues only the same fully staged rebase stop the caller inspected. */
    public SequenceState continueRebase(Path worktree, String expectedHead, String expectedStoppedCommit)
    {
        SequenceState before = observe(worktree);
        if (before.kind() != Kind.STOPPED_RESOLVED
                || !before.head().equals(expectedHead)
                || !before.stoppedCommit().filter(expectedStoppedCommit::equals).isPresent()) {
            return unsafe(before.head(), "Rebase resolution changed before continue");
        }
        git.run(worktree, ImmutableList.of("-c", "core.editor=true", "-c", "commit.gpgSign=false", "rebase", "--continue"));
        return observe(worktree);
    }

    /** Skips only the same proven-empty rebase stop the caller inspected. */
    public SequenceState skipEmpty(Path worktree, String expectedHead, String expectedStoppedCommit)
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
            return before.head().equals(originalWorkTip) ? before : unsafe(before.head(), "Rebase is closed away from the original work tip");
        }
        if (before.stoppedCommit().isEmpty()) {
            return unsafe(before.head(), "No guarded rebase is open to abort");
        }
        git.run(worktree, "rebase", "--abort");
        SequenceState after = observe(worktree);
        return after.kind() == Kind.COMPLETED && after.head().equals(originalWorkTip) ? after : unsafe(after.head(), "Abort did not restore the original work tip");
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
        Set<String> openMarkers = git.openMarkers(worktree, GitCommandRunner.OPERATION_MARKERS);
        String status = git.run(worktree, "status", "--porcelain=v1", "--untracked-files=all").output();
        List<ConflictedPath> conflicts = git.conflicts(worktree);

        if (openMarkers.isEmpty()) {
            return conflicts.isEmpty() && status.isBlank() ? completed(head) : unsafe(head, "No rebase is open but the worktree is not clean");
        }
        boolean oneRebaseMarker = openMarkers.equals(ImmutableSet.of("rebase-merge"))
                || openMarkers.equals(ImmutableSet.of("rebase-apply"));
        Optional<String> rebaseHead = git.markerCommit(worktree, "REBASE_HEAD");
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

    private static boolean unsafeWorktreeChanges(String status)
    {
        return status.lines().anyMatch(line -> line.startsWith("??")
                || (line.length() >= 2
                        && line.charAt(1) != ' '
                        && !UNMERGED_CODES.contains(line.substring(0, 2))));
    }
}
