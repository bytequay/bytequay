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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.InvestigationReviewData.ReviewCapabilities;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.pr.PullRequestService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

/** Loads the reproducible PR snapshot shared by API and CLI investigators. */
@Component
public class InvestigationReviewContext
{
    private static final int MAX_DIFF_CHARS = 240_000;
    private static final int MAX_FILE_CHARS = 120_000;

    private final PRService prs;
    private final PullRequestService pullRequests;
    private final TaskStore tasks;
    private final WatchedRepoStore watchedRepos;
    private final GitRunner git;

    public InvestigationReviewContext(
            PRService prs, PullRequestService pullRequests, TaskStore tasks,
            WatchedRepoStore watchedRepos, GitRunner git)
    {
        this.prs = requireNonNull(prs, "prs is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.git = requireNonNull(git, "git is null");
    }

    public Snapshot load(PR pr)
    {
        return load(pr, true);
    }

    /**
     * Load review evidence without accidentally granting a remote-only review
     * access to a watched clone. Task-owned reviews remain local through their
     * task worktree; standalone reviews only use a clone after they have been
     * explicitly attached to its workspace.
     */
    public Snapshot load(PR pr, boolean allowWorkspaceSource)
    {
        List<PRCommit> commits = prs.commits(pr.id());
        String base = commits.isEmpty() ? "unknown-base" : commits.get(0).sha();
        String head = commits.isEmpty() ? "unknown-head" : commits.get(commits.size() - 1).sha();
        if (pr.repo() != null && pr.remotePrNumber() != null) {
            List<DiffFile> files = pullRequests.getPullRequestDiffFiles(pr.repo(), pr.remotePrNumber());
            Path taskRoot = taskRootAt(pr, head);
            Path repositoryRoot = taskRoot == null && allowWorkspaceSource
                    ? watchedRootAt(pr.repo(), head) : taskRoot;
            ReviewCapabilities capabilities = repositoryRoot == null
                    ? ReviewCapabilities.remoteOnly() : ReviewCapabilities.localSource();
            return new Snapshot(
                    pr, base, head, render(files), files, null, repositoryRoot, capabilities);
        }
        Task task = tasks.findTaskById(pr.taskId())
                .orElseThrow(() -> new IllegalArgumentException("no task for PR " + pr.id()));
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            throw new IllegalStateException("task " + task.id() + " has no worktree");
        }
        Path root = Path.of(task.worktreePath()).toAbsolutePath().normalize();
        try {
            String diff = git.diff(root, pr.baseBranch(), "HEAD", MAX_DIFF_CHARS);
            return new Snapshot(pr, base, head, diff, List.of(), root, root,
                    ReviewCapabilities.localSource());
        }
        catch (IOException e) {
            throw new IllegalStateException("could not read local PR diff: " + e.getMessage(), e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted reading local PR diff", e);
        }
    }

    public String headCommit(PR pr)
    {
        List<PRCommit> commits = prs.commits(pr.id());
        return commits.isEmpty() ? "unknown-head" : commits.get(commits.size() - 1).sha();
    }

    /**
     * Materialise GitHub's PR head in an explicitly watched clone before a
     * full review starts. Normal context reads stay side-effect free; this is
     * called only from the user-triggered full-review path. GitHub publishes
     * {@code pull/N/head} on the target repository even for fork PRs.
     */
    public void prepareWatchedPr(PR pr)
    {
        if (pr.repo() == null || pr.remotePrNumber() == null
                || pr.baseBranch() == null || pr.baseBranch().isBlank()) {
            return;
        }
        String head = headCommit(pr);
        if ("unknown-head".equals(head)) {
            return;
        }
        watchedRepo(pr.repo()).ifPresent(watched ->
                watchedRoot(watched).ifPresent(root -> {
                    if (refExists(root, head)) {
                        return;
                    }
                    try {
                        String remote = watched.upstreamRemoteName();
                        if (remote == null || remote.isBlank()) {
                            git.fetchPrRefs(root, pr.remotePrNumber(), pr.baseBranch());
                        }
                        else {
                            git.fetchPrRefs(root, remote, pr.remotePrNumber(), pr.baseBranch());
                        }
                    }
                    catch (GitRunner.GitCommandException | IOException ignored) {
                        // load() below will keep the review remote-only, and the
                        // caller turns that into an actionable full-review error.
                    }
                    catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }));
    }

    public String readFile(Snapshot snapshot, String path)
    {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        if (snapshot.localRoot() == null) {
            if (snapshot.repositoryRoot() != null) {
                try {
                    return truncate(git.fileAtRef(
                            snapshot.repositoryRoot(), snapshot.headCommit(), path));
                }
                catch (IOException e) {
                    throw new IllegalStateException("could not read " + path + " at reviewed SHA: "
                            + e.getMessage(), e);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted reading " + path, e);
                }
            }
            if (snapshot.pr().repo() == null) {
                throw new IllegalStateException("PR has no repository");
            }
            return truncate(String.join("\n", pullRequests.getFileBlobLines(
                    snapshot.pr().repo(), path, snapshot.headCommit())));
        }
        Path target = snapshot.localRoot().resolve(path).normalize();
        if (!target.startsWith(snapshot.localRoot())) {
            throw new IllegalArgumentException("path escapes checkout");
        }
        try {
            return truncate(Files.readString(target));
        }
        catch (IOException e) {
            throw new IllegalStateException("could not read " + path + ": " + e.getMessage(), e);
        }
    }

    /** True line count for validation; unlike {@link #readFile}, this is not
     * truncated to the investigator prompt limit. */
    public int fileLineCount(Snapshot snapshot, String path)
    {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        if (snapshot.localRoot() == null) {
            if (snapshot.repositoryRoot() != null) {
                try {
                    return toIntExact(git.fileAtRef(
                            snapshot.repositoryRoot(), snapshot.headCommit(), path).lines().count());
                }
                catch (IOException e) {
                    throw new IllegalStateException("could not count " + path + " at reviewed SHA: "
                            + e.getMessage(), e);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted reading " + path, e);
                }
            }
            if (snapshot.pr().repo() == null) {
                throw new IllegalStateException("PR has no repository");
            }
            return pullRequests.getFileBlobLines(
                    snapshot.pr().repo(), path, snapshot.headCommit()).size();
        }
        Path target = snapshot.localRoot().resolve(path).normalize();
        if (!target.startsWith(snapshot.localRoot())) {
            throw new IllegalArgumentException("path escapes checkout");
        }
        try (Stream<String> lines = Files.lines(target)) {
            return toIntExact(lines.count());
        }
        catch (IOException e) {
            throw new IllegalStateException("could not read " + path + ": " + e.getMessage(), e);
        }
    }

    private static String render(List<DiffFile> files)
    {
        StringBuilder out = new StringBuilder();
        for (DiffFile file : files) {
            out.append("diff --git a/").append(file.filename()).append(" b/")
                    .append(file.filename()).append('\n');
            if (file.patch() != null) {
                out.append(file.patch()).append('\n');
            }
            if (out.length() >= MAX_DIFF_CHARS) {
                return out.substring(0, MAX_DIFF_CHARS) + "\n... (diff truncated)\n";
            }
        }
        return out.toString();
    }

    private static String truncate(String value)
    {
        return value.length() <= MAX_FILE_CHARS
                ? value
                : value.substring(0, MAX_FILE_CHARS) + "\n... (file truncated)\n";
    }

    /** Repository-wide literal references at the frozen reviewed SHA. Empty
     * means the round is remote-only or the symbol has no matches. */
    public List<String> repositoryReferences(Snapshot snapshot, String symbol, int limit)
    {
        if (snapshot.repositoryRoot() == null) {
            return List.of();
        }
        try {
            return git.grepAtRef(
                    snapshot.repositoryRoot(), snapshot.headCommit(), symbol, limit);
        }
        catch (IOException e) {
            return List.of();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private Path taskRootAt(PR pr, String head)
    {
        if (pr.taskId() == null || pr.taskId().isBlank()) {
            return null;
        }
        return tasks.findTaskById(pr.taskId())
                .map(Task::worktreePath)
                .filter(path -> path != null && !path.isBlank())
                .map(Path::of)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .filter(Files::isDirectory)
                .filter(root -> refExists(root, head))
                .orElse(null);
    }

    private Path watchedRootAt(String repo, String head)
    {
        return watchedRoot(repo)
                .filter(root -> refExists(root, head))
                .orElse(null);
    }

    private Optional<Path> watchedRoot(String repo)
    {
        return watchedRepo(repo).flatMap(this::watchedRoot);
    }

    private Optional<WatchedRepo> watchedRepo(String repo)
    {
        String[] parts = repo.split("/", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }
        return watchedRepos.find(parts[0], parts[1]);
    }

    private Optional<Path> watchedRoot(WatchedRepo watched)
    {
        return Optional.ofNullable(watched.localClonePath())
                .filter(path -> !path.isBlank())
                .map(Path::of)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .filter(Files::isDirectory);
    }

    private boolean refExists(Path root, String head)
    {
        try {
            return git.refExists(root, head);
        }
        catch (IOException e) {
            return false;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public record Snapshot(
            PR pr, String baseCommit, String headCommit, String diff,
            List<DiffFile> files, Path localRoot, Path repositoryRoot,
            ReviewCapabilities capabilities)
    {
        public Snapshot(
                PR pr, String baseCommit, String headCommit, String diff,
                List<DiffFile> files, Path localRoot)
        {
            this(pr, baseCommit, headCommit, diff, files, localRoot, localRoot,
                    localRoot == null
                            ? ReviewCapabilities.remoteOnly() : ReviewCapabilities.localSource());
        }
    }
}
