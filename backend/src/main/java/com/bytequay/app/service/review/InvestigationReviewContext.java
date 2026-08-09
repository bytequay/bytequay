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
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

/** Loads the reproducible PR snapshot shared by API and CLI investigators. */
@Component
public class InvestigationReviewContext
{
    private static final int MAX_DIFF_CHARS = 240_000;

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

    /** Detached quick-review capture. This path never resolves a Task or
     * watched-clone root, even if ownership changes while the remote request
     * is in flight. */
    public Snapshot loadRemoteOnly(PR pr)
    {
        requireNonNull(pr, "pr is null");
        List<PRCommit> commits = prs.commits(pr.id());
        String base = commits.isEmpty() ? "unknown-base" : commits.getFirst().sha();
        String head = commits.isEmpty() ? "unknown-head" : commits.getLast().sha();
        if (pr.repo() == null || pr.remotePrNumber() == null) {
            throw new IllegalArgumentException(
                    "remote-only review requires a remote pull request");
        }
        List<DiffFile> files = pullRequests.getPullRequestDiffFiles(
                pr.repo(), pr.remotePrNumber());
        return new Snapshot(
                pr, base, head, render(files), files, null, null,
                ReviewCapabilities.remoteOnly());
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

    /** Freeze complete bodies for every non-deleted changed file while the
     * dispatcher still owns the source lease. Later round work may only read
     * this returned evidence and never consult Git, GitHub, or a checkout. */
    public Snapshot freezeChangedFiles(Snapshot snapshot)
    {
        requireNonNull(snapshot, "snapshot is null");
        if (snapshot.repositoryRoot() == null) {
            throw new IllegalStateException(
                    "full review has no repository source to freeze");
        }
        List<String> paths = snapshot.files().stream()
                .filter(file -> !ImmutableSet.of("deleted", "removed", "D").contains(
                        file.status()))
                .map(DiffFile::filename)
                .toList();
        try {
            return snapshot.withFrozenChangedFiles(captureChangedFiles(
                    git, snapshot.repositoryRoot(), snapshot.headCommit(), paths));
        }
        catch (IOException e) {
            throw new IllegalStateException(
                    "could not freeze changed files at reviewed SHA: "
                            + e.getMessage(), e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted freezing changed files", e);
        }
    }

    static Map<String, String> captureChangedFiles(
            GitRunner git, Path repositoryRoot, String headCommit,
            List<String> paths)
            throws IOException, InterruptedException
    {
        requireNonNull(git, "git is null");
        requireNonNull(repositoryRoot, "repositoryRoot is null");
        requireNonNull(headCommit, "headCommit is null");
        LinkedHashMap<String, String> contents = new LinkedHashMap<>();
        for (String rawPath : paths) {
            String path = frozenPath(rawPath);
            if (!contents.containsKey(path)) {
                contents.put(path, git.fileAtRef(
                        repositoryRoot, headCommit, path));
            }
        }
        return Collections.unmodifiableMap(contents);
    }

    public String readFile(Snapshot snapshot, String path)
    {
        return requireNonNull(snapshot, "snapshot is null").readFile(path);
    }

    /** True line count from the complete frozen changed-file body. */
    public int fileLineCount(Snapshot snapshot, String path)
    {
        return toIntExact(requireNonNull(snapshot, "snapshot is null")
                .readFile(path).lines().count());
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

    /** Literal references within complete frozen changed-file bodies. */
    public List<String> repositoryReferences(Snapshot snapshot, String symbol, int limit)
    {
        if (symbol == null || symbol.isBlank() || limit <= 0) {
            return List.of();
        }
        Pattern word = Pattern.compile(
                "(?<![\\w$])" + Pattern.quote(symbol) + "(?![\\w$])");
        List<String> matches = new ArrayList<>();
        for (Map.Entry<String, String> file : snapshot.fileContents().entrySet()) {
            List<String> lines = file.getValue().lines().toList();
            for (int line = 0; line < lines.size() && matches.size() < limit; line++) {
                if (word.matcher(lines.get(line)).find()) {
                    matches.add(file.getKey() + ":" + (line + 1));
                }
            }
            if (matches.size() >= limit) {
                break;
            }
        }
        return List.copyOf(matches);
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
            ReviewCapabilities capabilities, Map<String, String> fileContents)
    {
        public Snapshot
        {
            files = List.copyOf(requireNonNull(files, "files is null"));
            fileContents = Collections.unmodifiableMap(new LinkedHashMap<>(
                    requireNonNull(fileContents, "fileContents is null")));
        }

        public Snapshot(
                PR pr, String baseCommit, String headCommit, String diff,
                List<DiffFile> files, Path localRoot, Path repositoryRoot,
                ReviewCapabilities capabilities)
        {
            this(pr, baseCommit, headCommit, diff, files, localRoot,
                    repositoryRoot, capabilities, Map.of());
        }

        public Snapshot(
                PR pr, String baseCommit, String headCommit, String diff,
                List<DiffFile> files, Path localRoot)
        {
            this(pr, baseCommit, headCommit, diff, files, localRoot, localRoot,
                    localRoot == null
                            ? ReviewCapabilities.remoteOnly() : ReviewCapabilities.localSource(),
                    Map.of());
        }

        public Snapshot withFrozenChangedFiles(Map<String, String> contents)
        {
            return new Snapshot(
                    pr, baseCommit, headCommit, diff, files, localRoot,
                    repositoryRoot, ReviewCapabilities.frozenChangedFiles(), contents);
        }

        public String readFile(String path)
        {
            String normalized = frozenPath(path);
            String content = fileContents.get(normalized);
            if (content == null) {
                throw new IllegalArgumentException(
                        "path was not captured in frozen review snapshot: " + normalized);
            }
            return content;
        }
    }

    private static String frozenPath(String value)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        Path path = Path.of(value).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            throw new IllegalArgumentException("path escapes frozen snapshot");
        }
        return path.toString().replace('\\', '/');
    }
}
