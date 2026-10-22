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
package com.bytequay.app.cherrypick;

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickItem;
import com.bytequay.core.cherrypick.CherryPickLifecycle.BranchCommit;
import com.bytequay.core.cherrypick.CherryPickLifecycle.BranchCommit.Origin;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Reads and checks the work branch the way every stage after applying relies on it.
 *
 * <p>The layout is the one CI Autofix already keeps: one {@code -x} copy per applied source, in
 * plan order, each followed by at most one {@code fixup!} directly after it. Folding the fixups
 * then never reorders or changes anything else.
 */
final class CherryPickBranch
{
    static final String FIXUP_PREFIX = "fixup! ";
    private static final String ORIGIN_PREFIX = "(cherry picked from commit ";
    private static final String FIELD = "\u001f";
    private static final String RECORD = "\u001e";
    private static final int MAX_SUBJECT_CHARACTERS = 1000;
    private static final Duration SQUASH_TIMEOUT = Duration.ofMinutes(5);

    private final GitCommandRunner git;

    CherryPickBranch(GitCommandRunner git)
    {
        this.git = requireNonNull(git, "git is null");
    }

    /**
     * Reads base..tip oldest first and says why it breaks the layout, if it does. A fixup keeps the
     * origin it was first seen with; one seen for the first time gets {@code origin}.
     */
    Inspection inspect(Path worktree, String baseSha, String tipSha, List<String> appliedSources, List<BranchCommit> previous, Origin origin)
    {
        if (!git.run(worktree, "rev-list", "--min-parents=2", baseSha + ".." + tipSha).output().isBlank()) {
            return Inspection.broken("The work branch contains a merge commit");
        }
        GitCommandRunner.Result read = git.run(worktree, "log", "--reverse", "--format=" + RECORD + "%H" + FIELD + "%s" + FIELD + "%B", baseSha + ".." + tipSha);
        if (!read.successful() || read.truncated()) {
            return Inspection.broken("Cannot read the work branch");
        }
        ImmutableList.Builder<BranchCommit> commits = ImmutableList.builder();
        ImmutableList.Builder<String> sources = ImmutableList.builder();
        Set<String> fixed = new HashSet<>();
        Optional<BranchCommit> last = Optional.empty();
        for (String record : read.output().split(RECORD)) {
            if (record.isBlank()) {
                continue;
            }
            String[] fields = record.split(FIELD, 3);
            if (fields.length < 3) {
                return Inspection.broken("Cannot read the work branch");
            }
            String sha = fields[0].strip();
            String subject = fields[1].strip();
            BranchCommit commit;
            if (subject.startsWith(FIXUP_PREFIX)) {
                Optional<BranchCommit> target = last.filter(value -> value.sourceSha().isPresent());
                if (target.isEmpty() || !subject.equals(FIXUP_PREFIX + target.orElseThrow().subject()) || !fixed.add(target.orElseThrow().sha())) {
                    return Inspection.broken("Fixup " + sha.substring(0, 7) + " is not the only fixup directly after the commit it belongs to");
                }
                String source = target.orElseThrow().sourceSha().orElseThrow();
                Origin kept = previous.stream()
                        .filter(value -> value.fixupOf().filter(source::equals).isPresent())
                        .findFirst()
                        .flatMap(BranchCommit::origin)
                        .orElse(origin);
                commit = new BranchCommit(sha, bounded(subject), Optional.empty(), Optional.of(source), Optional.of(kept));
            }
            else {
                Optional<String> source = originSha(fields[2]);
                if (source.isEmpty()) {
                    return Inspection.broken("Commit " + sha.substring(0, 7) + " carries no cherry-pick origin line");
                }
                sources.add(source.orElseThrow());
                commit = new BranchCommit(sha, bounded(subject), source, Optional.empty(), Optional.empty());
            }
            commits.add(commit);
            last = Optional.of(commit);
        }
        if (!sources.build().equals(appliedSources)) {
            return Inspection.broken("The work branch no longer holds the applied commits in plan order");
        }
        if (!git.withoutConflictMarkers(worktree, baseSha, tipSha)) {
            return Inspection.broken("A commit still contains a conflict marker");
        }
        return new Inspection(commits.build(), Optional.empty());
    }

    /**
     * Folds every fixup into the commit before it. The folded tip must hold exactly the same tree;
     * anything else restores the original tip and says why.
     */
    Optional<String> squash(Path worktree, String baseSha, String tipSha)
    {
        GitCommandRunner.Result folded = git.run(worktree, SQUASH_TIMEOUT, ImmutableMap.of("GIT_SEQUENCE_EDITOR", "true"),
                ImmutableList.of("-c", "core.editor=true", "-c", "commit.gpgSign=false", "-c", "core.hooksPath=/dev/null",
                        "rebase", "--interactive", "--autosquash", baseSha));
        Optional<String> head = revision(worktree, "HEAD");
        if (folded.successful() && head.isPresent() && revision(worktree, head.orElseThrow() + "^{tree}").equals(revision(worktree, tipSha + "^{tree}"))) {
            return Optional.empty();
        }
        git.run(worktree, "rebase", "--abort");
        git.run(worktree, "-c", "core.hooksPath=/dev/null", "checkout", "--no-overwrite-ignore", "--no-recurse-submodules", "-B", currentBranch(worktree), tipSha);
        return Optional.of(folded.successful() ? "Folding the fixups changed the result; the branch was restored" : "Git could not fold the fixups; the branch was restored");
    }

    /**
     * Moves a clean worktree to a revision another checkout of the same repository created, such
     * as a CI repair. No fetch is needed: every task worktree shares the workspace's objects.
     */
    Optional<String> moveTo(Path worktree, String expectedHead, String revision)
    {
        if (!revision(worktree, "HEAD").filter(expectedHead::equals).isPresent() || !clean(worktree)) {
            return Optional.of("The worktree changed before ByteQuay could follow the pull request");
        }
        if (!git.run(worktree, "cat-file", "-e", revision + "^{commit}").successful()) {
            return Optional.of("The pull request head " + revision.substring(0, 7) + " is not in the workspace clone");
        }
        // Unlike reset --keep, this preserves ignored build output and refuses ignored-path collisions.
        GitCommandRunner.Result moved = git.run(worktree, "-c", "core.hooksPath=/dev/null", "checkout",
                "--no-overwrite-ignore", "--no-recurse-submodules", "-B", currentBranch(worktree), revision);
        if (!moved.successful() || !revision(worktree, "HEAD").filter(revision::equals).isPresent()) {
            return Optional.of("Could not move the worktree to " + revision.substring(0, 7));
        }
        return Optional.empty();
    }

    boolean isAncestor(Path worktree, String ancestor, String descendant)
    {
        return git.run(worktree, "merge-base", "--is-ancestor", ancestor, descendant).exitCode() == 0;
    }

    Optional<String> mergeBase(Path worktree, String left, String right)
    {
        GitCommandRunner.Result base = git.run(worktree, "merge-base", left, right);
        return base.successful() ? Optional.of(base.output().strip()) : Optional.empty();
    }

    int count(Path worktree, String fromSha, String toSha)
    {
        GitCommandRunner.Result counted = git.run(worktree, "rev-list", "--count", fromSha + ".." + toSha);
        return counted.successful() ? Integer.parseInt(counted.output().strip()) : 0;
    }

    Optional<String> revision(Path worktree, String name)
    {
        GitCommandRunner.Result resolved = git.run(worktree, "rev-parse", "--verify", "--quiet", name);
        return resolved.successful() ? Optional.of(resolved.output().strip()) : Optional.empty();
    }

    boolean clean(Path worktree)
    {
        GitCommandRunner.Result status = git.run(worktree, "status", "--porcelain=v1", "--untracked-files=all");
        return status.successful() && status.output().isBlank() && git.openMarkers(worktree, GitCommandRunner.OPERATION_MARKERS).isEmpty();
    }

    static List<String> appliedSources(CherryPickExecution execution)
    {
        return execution.items().stream()
                .filter(item -> item.result() == CherryPickItem.Result.APPLIED)
                .map(CherryPickItem::sourceSha)
                .collect(toImmutableList());
    }

    static Optional<String> originSha(String message)
    {
        // Git appends the immediate source after any origin lines inherited from earlier backports.
        for (String line : message.lines().toList().reversed()) {
            String value = line.strip();
            if (value.startsWith(ORIGIN_PREFIX) && value.endsWith(")")) {
                String sha = value.substring(ORIGIN_PREFIX.length(), value.length() - 1).strip();
                if (sha.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
                    return Optional.of(sha.toLowerCase(Locale.ROOT));
                }
            }
        }
        return Optional.empty();
    }

    private String currentBranch(Path worktree)
    {
        return git.run(worktree, "symbolic-ref", "--short", "HEAD").output().strip();
    }

    private static String bounded(String subject)
    {
        return subject.length() <= MAX_SUBJECT_CHARACTERS ? subject : subject.substring(0, MAX_SUBJECT_CHARACTERS);
    }

    record Inspection(List<BranchCommit> commits, Optional<String> problem)
    {
        Inspection
        {
            commits = ImmutableList.copyOf(commits);
            requireNonNull(problem, "problem is null");
        }

        static Inspection broken(String problem)
        {
            return new Inspection(ImmutableList.of(), Optional.of(problem));
        }
    }
}
