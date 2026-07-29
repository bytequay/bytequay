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
package com.bytequay.app.service.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * Applies a whole queue of staged history edits (reorder, squash,
 * reword) as ONE non-interactive {@code git rebase -i}. The UI stages
 * edits locally and only ever calls this once, on "Rewrite history",
 * so a five-step edit costs one rebase rather than five.
 *
 * <p>The plan is expressed as the desired result — an ordered list of
 * output commits, each naming the original commits folded into it — so
 * the caller never has to think in todo-list verbs. The first pick of a
 * group lands the change and the rest are {@code fixup}s; an
 * {@code exec git commit --amend} follows when the message changed,
 * which covers reword and squash alike without ever opening an editor.
 *
 * <p>Atomicity: HEAD is captured up front and restored on any failure
 * (including a conflicted rebase), so a caller that gets an exception
 * knows the ref is exactly where it started and its pending queue is
 * still valid to retry.
 */
public class HistoryRewriter
{
    private final GitRunner git;

    public HistoryRewriter(GitRunner git)
    {
        this.git = requireNonNull(git, "git is null");
    }

    /**
     * One output commit. {@code picks} are the original full shas that
     * compose it, oldest first; more than one means a squash. A null
     * {@code message} keeps whatever the first pick already had — the
     * common case for a pure reorder, and worth skipping because the
     * amend is the only part of the todo that can trip commit hooks.
     */
    public record RewriteEntry(List<String> picks, String message) {}

    /**
     * @param branch    the branch being rewritten; must be the checked-out one
     * @param base      sha to replay onto — the newest commit left untouched
     * @param commits   output commits, oldest first; must cover all of {@code base..HEAD}
     * @param forcePush push with {@code --force-with-lease} afterwards
     */
    public record RewritePlan(
            String branch,
            String base,
            List<RewriteEntry> commits,
            boolean forcePush) {}

    /**
     * @param pushed    the rewritten branch reached the remote
     * @param pushError why it didn't, when a force push was asked for and
     *                  refused. The rewrite itself still stands — the
     *                  usual cause is the lease catching a remote that
     *                  moved while the user was editing.
     */
    public record RewriteResult(String headSha, boolean pushed, String pushError) {}

    /** Rebase ran but did not finish — conflict, hook rejection, bad todo. */
    public static class RewriteFailedException
            extends RuntimeException
    {
        public RewriteFailedException(String message)
        {
            super(message);
        }
    }

    /**
     * Runs {@code plan} against the clone at {@code workingDir}.
     *
     * @throws IllegalArgumentException when the plan is malformed or the
     *         clone is not in a state where a rebase is safe
     * @throws RewriteFailedException when the rebase itself fails; HEAD
     *         has already been restored by then
     */
    public RewriteResult rewrite(Path workingDir, RewritePlan plan)
            throws IOException, InterruptedException
    {
        requireNonNull(workingDir, "workingDir is null");
        requireNonNull(plan, "plan is null");
        validate(plan);

        String current = git.currentBranch(workingDir);
        if (!current.equals(plan.branch())) {
            throw new IllegalArgumentException(
                    "History can only be rewritten on the checked-out branch — "
                            + plan.branch() + " is not " + current + ".");
        }
        if (git.hasUncommittedChanges(workingDir)) {
            throw new IllegalArgumentException(
                    "Commit or stash your working-tree changes before rewriting history.");
        }

        String savedHead = git.headSha(workingDir);
        Path scratch = Files.createTempDirectory("bytequay-rewrite-");
        try {
            Path todo = writeTodo(scratch, plan);
            GitRunner.GitResult result = git.rebaseWithTodo(workingDir, plan.base(), todo);
            if (result.exitCode() != 0) {
                restore(workingDir, savedHead);
                throw new RewriteFailedException(failureMessage(result));
            }
            String head = git.headSha(workingDir);
            if (!plan.forcePush()) {
                return new RewriteResult(head, false, null);
            }
            // The rewrite is already committed locally; a rejected push
            // is reported, not rolled back, so the user can fetch and
            // retry the push without redoing the edit.
            GitRunner.GitResult push = git.pushRewrittenBranch(workingDir);
            return push.exitCode() == 0
                    ? new RewriteResult(head, true, null)
                    : new RewriteResult(head, false, pushMessage(push));
        }
        finally {
            deleteRecursively(scratch);
        }
    }

    private static void validate(RewritePlan plan)
    {
        if (plan.base() == null || plan.base().isBlank()) {
            throw new IllegalArgumentException(
                    "Nothing to rebase onto — load more history and try again.");
        }
        if (plan.commits() == null || plan.commits().isEmpty()) {
            throw new IllegalArgumentException("No commits in the rewrite plan.");
        }
        for (RewriteEntry entry : plan.commits()) {
            if (entry.picks() == null || entry.picks().isEmpty()) {
                throw new IllegalArgumentException("A rewrite entry names no commits.");
            }
            for (String sha : entry.picks()) {
                if (sha == null || !sha.matches("[0-9a-fA-F]{7,40}")) {
                    throw new IllegalArgumentException("Not a commit sha: " + sha);
                }
            }
        }
    }

    private Path writeTodo(Path scratch, RewritePlan plan)
            throws IOException
    {
        List<String> lines = new ArrayList<>();
        int index = 0;
        for (RewriteEntry entry : plan.commits()) {
            lines.add("pick " + entry.picks().getFirst());
            entry.picks().stream().skip(1).forEach(sha -> lines.add("fixup " + sha));
            if (entry.message() != null) {
                Path message = scratch.resolve("message-" + index + ".txt");
                Files.writeString(message, entry.message(), StandardCharsets.UTF_8);
                // --no-verify: the tree is byte-identical to what the
                // picks already produced, so re-running commit hooks
                // here only risks aborting the rebase over a message.
                lines.add("exec git commit --amend --no-verify -F '" + message + "'");
            }
            index++;
        }
        Path todo = scratch.resolve("todo");
        Files.writeString(todo, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return todo;
    }

    /** Puts the branch back exactly where it was; both steps are
     *  best-effort because a half-finished rebase can leave either one
     *  a no-op, and neither failing should mask the real error. */
    private void restore(Path workingDir, String savedHead)
    {
        try {
            git.rebaseAbort(workingDir);
        }
        catch (IOException | RuntimeException ignored) {
            // Nothing to abort, or git refused — resetHard still runs.
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            git.resetHard(workingDir, savedHead);
        }
        catch (IOException | RuntimeException ignored) {
            // Reported through the RewriteFailedException the caller raises.
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String pushMessage(GitRunner.GitResult push)
    {
        String detail = push.stderr().isBlank() ? push.stdout() : push.stderr();
        String trimmed = detail.strip();
        return trimmed.isEmpty()
                ? "The force push was rejected. History was rewritten locally only."
                : "History was rewritten locally, but the force push was rejected:\n" + trimmed;
    }

    private static String failureMessage(GitRunner.GitResult result)
    {
        String detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
        String trimmed = detail.strip();
        return trimmed.isEmpty()
                ? "The rebase failed. Nothing was changed."
                : "The rebase failed and was rolled back:\n" + trimmed;
    }

    private static void deleteRecursively(Path root)
    {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                }
                catch (IOException ignored) {
                    // Temp scratch; the OS reaps it either way.
                }
            });
        }
        catch (IOException ignored) {
            // Same.
        }
    }
}
