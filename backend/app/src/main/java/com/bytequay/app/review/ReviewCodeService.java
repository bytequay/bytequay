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
package com.bytequay.app.review;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.app.agent.AgentProperties;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.app.git.ReviewExperiments;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.settings.ClaudeAccounts;
import com.bytequay.app.workspace.ManagedWorktreeService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestTask;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.google.common.base.Splitter;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.ListMultimap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.MoreFiles;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * Whole-file reads for a review, its working copy while that exists, else the latest run's frozen head in the managed
 * repository; or for any workspace task, its owned working copy.
 *
 * <p>Reads never inspect the worktree through {@code WorktreeService.inspect}: its status takes Git's
 * optional index lock, so a poll could make Update's stash or merge fail on {@code index.lock}.
 */
@Component
public class ReviewCodeService
{
    static final int MAXIMUM_FILE_BYTES = 2 * 1024 * 1024;
    private static final int BINARY_PROBE_BYTES = 8 * 1024;
    private static final Pattern HUNK_HEADER = Pattern.compile("@@ -\\d+(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");

    private final AgentReviewStore store;
    private final AgentReviewService reviews;
    private final ManagedWorktreeService worktrees;
    private final WorkspaceRepositoryService workspaces;
    private final GitCommandRunner git;
    private final ReviewExperiments experiments;
    private final AgentTaskService tasks;
    private final AgentTurnService turns;
    private final PullRequestTaskStore assignments;
    private final PullRequestStore pullRequestRows;
    private final WorktreeDiffReader localDiff;
    private final PullRequestService pullRequests;
    private final AgentProperties agentProperties;
    private final ClaudeAccounts claudeAccounts;

    public ReviewCodeService(
            AgentReviewStore store,
            AgentReviewService reviews,
            ManagedWorktreeService worktrees,
            WorkspaceRepositoryService workspaces,
            GitCommandRunner git,
            ReviewExperiments experiments,
            AgentTaskService tasks,
            AgentTurnService turns,
            PullRequestTaskStore assignments,
            PullRequestStore pullRequestRows,
            WorktreeDiffReader localDiff,
            PullRequestService pullRequests,
            AgentProperties agentProperties,
            ClaudeAccounts claudeAccounts)
    {
        this.store = requireNonNull(store, "store is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.git = requireNonNull(git, "git is null");
        this.experiments = requireNonNull(experiments, "experiments is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.assignments = requireNonNull(assignments, "assignments is null");
        this.pullRequestRows = requireNonNull(pullRequestRows, "pullRequestRows is null");
        this.localDiff = requireNonNull(localDiff, "localDiff is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.agentProperties = requireNonNull(agentProperties, "agentProperties is null");
        this.claudeAccounts = requireNonNull(claudeAccounts, "claudeAccounts is null");
    }

    public CodeFiles files(String reviewId)
    {
        return files(source(reviewId));
    }

    public CodeFiles files(TaskCodeHost host)
    {
        return files(source(host));
    }

    /** Resolves a writable IDE workspace without claiming it or changing an agent's execution state. */
    public TaskWorktree ideWorktree(String reviewId)
    {
        Source source = source(reviewId);
        source.ended().ifPresent(reason -> {
            throw new ReviewConflictException(reason);
        });
        if (source.checkout().isEmpty()) {
            throw new ReviewConflictException("The working copy is gone");
        }
        return source.task().taskWorktree().orElseThrow();
    }

    public TaskWorktree ideWorktree(TaskCodeHost host)
    {
        AgentTask task = mutableTask(host);
        if (task.state() != TaskState.OPEN) {
            throw new ReviewConflictException("The task has ended");
        }
        return task.taskWorktree().orElseThrow();
    }

    /** Agent queries can inspect an active review task without gaining the interactive task save route. */
    public TaskWorktree ideQueryWorktree(TaskCodeHost host)
    {
        AgentTask task = task(host);
        if (task.state() != TaskState.OPEN) {
            throw new ReviewConflictException("The task has ended");
        }
        return task.taskWorktree().orElseThrow();
    }

    private CodeFiles files(Source source)
    {
        Set<String> changed = source.diff().get().stream().map(DiffFile::filename).collect(toImmutableSet());
        // GitHub's pull-request file status vocabulary.
        Set<String> added = source.diff().get().stream().filter(file -> file.status().equals("added")).map(DiffFile::filename).collect(toImmutableSet());
        if (source.checkout().isEmpty()) {
            String revision = source.headSha().get();
            Result listed = git.runStdout(repository(source.task()), "ls-tree", "-r", "-z", "--name-only", revision);
            checkState(listed.successful(), "Cannot list the reviewed revision: %s", listed.output().strip());
            List<CodeFileEntry> files = ImmutableSortedSet.copyOf(entries(listed.output(), listed.truncated())).stream()
                    .map(path -> new CodeFileEntry(path, changed.contains(path), added.contains(path), false, false))
                    .collect(toImmutableList());
            return new CodeFiles(CodeSource.REVISION, revision, Optional.empty(), false, listed.truncated(), files);
        }
        Path checkout = source.checkout().orElseThrow();
        String head = source.headSha().get();
        Result listed = git.runStdout(checkout, "ls-files", "-z", "--cached", "--others", "--exclude-standard");
        checkState(listed.successful(), "Cannot list the working copy: %s", listed.output().strip());
        Result status = status(checkout);
        ListMultimap<String, String> codes = statusCodes(entries(status.output(), status.truncated()));
        ImmutableList.Builder<CodeFileEntry> files = ImmutableList.builder();
        // An unmerged path is listed once per index stage.
        for (String path : ImmutableSortedSet.copyOf(entries(listed.output(), listed.truncated()))) {
            List<String> pathCodes = codes.get(path);
            // A staged deletion can leave the same path untracked, so only the disk says whether it is gone.
            if (pathCodes.stream().anyMatch(code -> code.indexOf('D') >= 0) && Files.notExists(checkout.resolve(path), LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            boolean notInHead = pathCodes.stream().anyMatch(code -> code.equals("??") || "ARC".indexOf(code.charAt(0)) >= 0);
            files.add(new CodeFileEntry(path, changed.contains(path), added.contains(path), notInHead, !pathCodes.isEmpty()));
        }
        CodeFiles result = new CodeFiles(CodeSource.WORKING_COPY, head, Optional.of(fingerprint(checkout, head, codes.keySet())),
                experiments.hasSavedExperiment(source.task().taskWorktree().orElseThrow()), listed.truncated() || status.truncated(), files.build());
        requireUnchangedHead(source);
        return result;
    }

    /** The HEAD, uncommitted or working-copy-only paths, and fingerprint a run records at start, read like {@link #files}. */
    public WorkingCopySource workingCopySource(Path checkout)
    {
        String head = head(checkout);
        Result status = status(checkout);
        Set<String> paths = ImmutableSortedSet.copyOf(statusCodes(entries(status.output(), status.truncated())).keySet());
        return new WorkingCopySource(head, ImmutableList.copyOf(paths), fingerprint(checkout, head, paths));
    }

    private String head(Path checkout)
    {
        Result head = git.run(checkout, "rev-parse", "--verify", "HEAD^{commit}");
        checkState(head.successful() && !head.truncated(), "Cannot read the working copy HEAD: %s", head.output().strip());
        return head.output().strip();
    }

    private Result status(Path checkout)
    {
        Result status = git.runStdout(checkout, "--no-optional-locks", "status", "--porcelain=v1", "-z", "--untracked-files=all");
        checkState(status.successful(), "Cannot read the working copy status: %s", status.output().strip());
        return status;
    }

    /** SHA-256 over HEAD and each uncommitted or working-copy-only path with the hash of its bytes, in path order. */
    static String fingerprint(Path checkout, String head, Set<String> paths)
    {
        Hasher hasher = Hashing.sha256().newHasher().putString(head, UTF_8).putByte((byte) 0);
        for (String path : ImmutableSortedSet.copyOf(paths)) {
            hasher.putString(path, UTF_8).putByte((byte) 0).putString(contentHash(checkout.resolve(path)), UTF_8).putByte((byte) 0);
        }
        return hasher.hash().toString();
    }

    private static String contentHash(Path file)
    {
        try {
            if (Files.isSymbolicLink(file)) {
                return "link:" + Files.readSymbolicLink(file);
            }
            // A deleted path, or a directory such as an unregistered nested repository.
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return "-";
            }
            return MoreFiles.asByteSource(file).hash(Hashing.sha256()).toString();
        }
        catch (IOException failure) {
            return "unreadable";
        }
    }

    public CodeFile file(String reviewId, String path)
    {
        return file(reviewId, path, Optional.empty());
    }

    public CodeFile file(String reviewId, String path, Optional<String> expectedRevision)
    {
        checkPath(path);
        return file(source(reviewId), path, expectedRevision);
    }

    public CodeFile file(TaskCodeHost host, String path)
    {
        return file(host, path, Optional.empty());
    }

    public CodeFile file(TaskCodeHost host, String path, Optional<String> expectedRevision)
    {
        checkPath(path);
        return file(source(host), path, expectedRevision);
    }

    private CodeFile file(Source source, String path, Optional<String> expectedRevision)
    {
        requireNonNull(expectedRevision, "expectedRevision is null");
        if (expectedRevision.isPresent() && !expectedRevision.orElseThrow().equals(source.headSha().get())) {
            throw new ReviewConflictException("The working copy revision changed; refresh the file list before reading this file");
        }
        return file(source, path);
    }

    private CodeFile file(Source source, String path)
    {
        Optional<String> prPatch = prFile(source, path).flatMap(file -> Optional.ofNullable(file.patch()));
        if (source.checkout().isEmpty()) {
            Path repository = repository(source.task());
            String revision = source.headSha().get();
            Result shown = git.runStdout(repository, "cat-file", "blob", revision + ":" + path);
            if (!shown.successful()) {
                throw notFound(path);
            }
            // ponytail: the runner decodes output as UTF-8, so these bytes match the blob for UTF-8 text only; a revision is never saved against its hash.
            byte[] bytes = shown.output().getBytes(UTF_8);
            boolean tooLarge = shown.truncated() || bytes.length > MAXIMUM_FILE_BYTES;
            return content(path, bytes, tooLarge, readOnlyReason(source, false, false), prPatch, () -> lineChanges(repository, source, Optional.of(revision), path));
        }
        Path root = source.checkout().orElseThrow();
        byte[] bytes = readCheckout(root, path);
        boolean symlinked = symlinked(root, path);
        // Git diffs a link's target text, not the file shown through it.
        CodeFile result = content(path, bytes, bytes.length > MAXIMUM_FILE_BYTES, readOnlyReason(source, true, symlinked), prPatch,
                () -> symlinked ? ImmutableList.of() : lineChanges(root, source, Optional.empty(), path));
        requireUnchangedHead(source);
        return result;
    }

    public CodeHistory history(String reviewId, Optional<String> revision, Optional<String> path, int offset)
    {
        return history(source(reviewId), revision, path, offset);
    }

    public CodeHistory history(TaskCodeHost host, Optional<String> revision, Optional<String> path, int offset)
    {
        return history(source(host), revision, path, offset);
    }

    private CodeHistory history(Source source, Optional<String> revision, Optional<String> path, int offset)
    {
        Path root = source.checkout().orElseGet(() -> repository(source.task()));
        String selected = historyRevision(source, root, revision.orElseGet(source.headSha()));
        return localDiff.historyPage(root, selected, path, offset);
    }

    public CodeHistory.Comparison historyCommit(String reviewId, String revision, Optional<String> parent)
    {
        return historyCommit(source(reviewId), revision, parent);
    }

    public CodeHistory.Comparison historyCommit(TaskCodeHost host, String revision, Optional<String> parent)
    {
        return historyCommit(source(host), revision, parent);
    }

    private CodeHistory.Comparison historyCommit(Source source, String revision, Optional<String> parent)
    {
        Path root = source.checkout().orElseGet(() -> repository(source.task()));
        return localDiff.historyCommit(root, historyRevision(source, root, revision), parent);
    }

    public CodeFile snapshot(String reviewId, String revision, String path)
    {
        return snapshot(source(reviewId), revision, path);
    }

    public CodeFile snapshot(TaskCodeHost host, String revision, String path)
    {
        return snapshot(source(host), revision, path);
    }

    private CodeFile snapshot(Source source, String revision, String path)
    {
        checkPath(path);
        Path root = source.checkout().orElseGet(() -> repository(source.task()));
        String selected = historyRevision(source, root, revision);
        Result shown = localDiff.read(root, "cat-file", "blob", selected + ":" + path);
        if (!shown.successful()) {
            throw notFound(path);
        }
        byte[] bytes = shown.output().getBytes(UTF_8);
        return content(path, bytes, shown.truncated() || bytes.length > MAXIMUM_FILE_BYTES, Optional.of("Historical source is read-only"),
                Optional.empty(), ImmutableList::of);
    }

    private String historyRevision(Source source, Path root, String revision)
    {
        String selected = localDiff.commit(root, revision);
        checkArgument(localDiff.read(root, "merge-base", "--is-ancestor", selected, source.headSha().get()).successful(), "Commit is outside this task's history");
        return selected;
    }

    public CodeBlame blame(String reviewId, String path)
    {
        checkPath(path);
        return blame(source(reviewId), path);
    }

    public CodeBlame blame(TaskCodeHost host, String path)
    {
        checkPath(path);
        return blame(source(host), path);
    }

    private CodeBlame blame(Source source, String path)
    {
        CodeFile file = file(source, path);
        String revision = source.headSha().get();
        if (file.content().isEmpty()) {
            return new CodeBlame(path, revision, file.contentHash().orElse(""), ImmutableList.of(), false, file.unavailableReason());
        }
        Path root = source.checkout().orElseGet(() -> repository(source.task()));
        if (source.checkout().isPresent() && symlinked(root, path)) {
            return new CodeBlame(path, revision, file.contentHash().orElse(""), ImmutableList.of(), false, Optional.of("Blame is unavailable for symbolic links"));
        }
        Result result = localDiff.read(root, ImmutableList.<String>builder()
                .add("blame", "--incremental", "--no-progress", "--no-textconv", "--encoding=UTF-8")
                .addAll(source.checkout().isEmpty() ? ImmutableList.of(revision) : ImmutableList.of())
                .add("--", path).build().toArray(String[]::new));
        if (!result.successful()) {
            return new CodeBlame(path, revision, file.contentHash().orElse(""), ImmutableList.of(), false, Optional.of("No local Git blame is available for this file"));
        }
        requireUnchangedHead(source);
        CodeFile after = file(source, path);
        if (!file.contentHash().equals(after.contentHash())) {
            throw new ReviewConflictException("The file changed while blame was being read; refresh before viewing annotations");
        }
        String output = result.truncated() ? result.output().substring(0, result.output().lastIndexOf('\n') + 1) : result.output();
        return new CodeBlame(path, revision, file.contentHash().orElse(""), CodeBlame.parse(output), result.truncated(), Optional.empty());
    }

    /**
     * How the file differs from the pull request's merge base, compared with {@code revision}, or without one with the
     * working tree, so saved local edits count too. A file the pull request renamed compares with its source path.
     * Empty when Git cannot say, such as for an untracked file or an unknown merge base.
     */
    private List<LineChange> lineChanges(Path directory, Source source, Optional<String> revision, String path)
    {
        Optional<String> base = source.mergeBaseSha().get();
        if (base.isEmpty()) {
            return ImmutableList.of();
        }
        List<String> paths = prFile(source, path).filter(file -> file.status().equals("renamed")).isEmpty()
                ? ImmutableList.of(path)
                : renameSource(directory, base.orElseThrow(), source.headSha().get(), path).map(renamed -> ImmutableList.of(renamed, path)).orElse(ImmutableList.of(path));
        Result diff = git.runStdout(directory, ImmutableList.<String>builder()
                // Porcelain diff rewrites a stat-dirty index even under --no-optional-locks, which would take index.lock under a
                // concurrent Update; user settings must not merge hunks or change the path prefixes the parser reads.
                .add("-c", "diff.autoRefreshIndex=false", "-c", "core.quotePath=false", "--no-optional-locks", "--literal-pathspecs")
                .add("diff", "--no-color", "--no-ext-diff", "--no-textconv", "-M", "-U0", "--inter-hunk-context=0", "--src-prefix=a/", "--dst-prefix=b/")
                .add(base.orElseThrow())
                .addAll(revision.stream().iterator())
                .add("--")
                .addAll(paths)
                .build()
                .toArray(String[]::new));
        return diff.successful() && !diff.truncated() ? lineChanges(diff.output(), path) : ImmutableList.of();
    }

    private Optional<String> renameSource(Path directory, String base, String head, String path)
    {
        Result renames = git.runStdout(directory, "diff-tree", "-r", "-z", "-M", "--name-status", "--diff-filter=R", base, head);
        if (!renames.successful() || renames.truncated()) {
            return Optional.empty();
        }
        List<String> entries = entries(renames.output(), false);
        // Each rename is a status, its source and its destination.
        for (int index = 0; index + 2 < entries.size(); index += 3) {
            if (entries.get(index + 2).equals(path)) {
                return Optional.of(entries.get(index + 1));
            }
        }
        return Optional.empty();
    }

    /**
     * The hunks of a {@code git diff -U0} that write {@code path}, where each hunk is one contiguous change. A deletion
     * of the path, or of a rename source Git could not pair, writes {@code /dev/null} and is skipped.
     */
    static List<LineChange> lineChanges(String diff, String path)
    {
        ImmutableList.Builder<LineChange> changes = ImmutableList.builder();
        boolean writesPath = false;
        for (String line : Splitter.on('\n').split(diff)) {
            if (line.startsWith("diff --git ")) {
                writesPath = false;
            }
            else if (line.startsWith("+++ ")) {
                // Git ends a label holding a space with a tab, for patch(1), and C-quotes a name with quotes or control characters.
                String label = line.substring(4, line.endsWith("\t") ? line.length() - 1 : line.length());
                writesPath = (label.length() > 1 && label.startsWith("\"") && label.endsWith("\"") ? unquote(label) : label).equals("b/" + path);
            }
            Matcher hunk = HUNK_HEADER.matcher(line);
            if (!writesPath || !hunk.lookingAt()) {
                continue;
            }
            int removed = hunk.group(1) == null ? 1 : Integer.parseInt(hunk.group(1));
            int start = Integer.parseInt(hunk.group(2));
            int added = hunk.group(3) == null ? 1 : Integer.parseInt(hunk.group(3));
            if (added > 0) {
                changes.add(new LineChange(removed == 0 ? LineChange.Kind.ADDED : LineChange.Kind.MODIFIED, start, added));
            }
            else if (removed > 0) {
                changes.add(new LineChange(LineChange.Kind.DELETED, start, removed));
            }
        }
        return changes.build();
    }

    /** Git's C-quoted name, with {@code core.quotePath=false} so only quotes, backslashes and control characters are escaped. */
    static String unquote(String quoted)
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int end = quoted.length() - 1;
        for (int index = 1; index < end; ) {
            int codePoint = quoted.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint != '\\' || index >= end) {
                bytes.writeBytes(Character.toString(codePoint).getBytes(UTF_8));
                continue;
            }
            char escaped = quoted.charAt(index++);
            switch (escaped) {
                case 'a' -> bytes.write(7);
                case 'b' -> bytes.write('\b');
                case 't' -> bytes.write('\t');
                case 'n' -> bytes.write('\n');
                case 'v' -> bytes.write(11);
                case 'f' -> bytes.write('\f');
                case 'r' -> bytes.write('\r');
                default -> {
                    if (escaped >= '0' && escaped <= '3' && index + 1 < end) {
                        bytes.write(Integer.parseInt(quoted.substring(index - 1, index + 2), 8));
                        index += 2;
                    }
                    else {
                        bytes.write(escaped);
                    }
                }
            }
        }
        return bytes.toString(UTF_8);
    }

    /**
     * Writes a file of the review's working copy over the version the editor loaded.
     *
     * @param expectedHash the {@link CodeFile#contentHash} the edit started from
     * @return the saved file, read back like {@link #file}
     * @throws ReviewFileConflictException when the bytes on disk no longer hash to {@code expectedHash}
     */
    public CodeFile save(String reviewId, String path, String content, String expectedHash)
    {
        saveBytes(reviewId, path, savedBytes(content, expectedHash), expectedHash);
        return file(source(reviewId), path);
    }

    /** IDE encoders supply bytes directly, so UTF-16 and BOMs retain the same conflict-safe save boundary. */
    public void saveBytes(String reviewId, String path, byte[] content, String expectedHash)
    {
        checkSave(path, content, expectedHash);
        reviews.mutateWorkingCopy(reviewId, task -> {
            overwrite(source(reviewId), path, content, expectedHash);
            return null;
        });
    }

    /**
     * Writes any file of the task's working copy over the version the editor loaded, under the task's worktree claim, which the core
     * refuses while a turn, queued input, another claim or development automation holds the task.
     *
     * @param expectedHash the {@link CodeFile#contentHash} the edit started from
     * @return the saved file, read back like {@link #file}
     * @throws ReviewFileConflictException when the bytes on disk no longer hash to {@code expectedHash}
     */
    public CodeFile save(TaskCodeHost host, String path, String content, String expectedHash)
    {
        saveBytes(host, path, savedBytes(content, expectedHash), expectedHash);
        return file(source(host), path);
    }

    public void saveBytes(TaskCodeHost host, String path, byte[] content, String expectedHash)
    {
        checkSave(path, content, expectedHash);
        mutableTask(host);
        Source source = source(host);
        LocalRunService.requireTaskIdle(turns, source.task());
        turns.synchronizeWorktree(source.task().id(), source.task().version(), claimed -> overwrite(source, path, content, expectedHash));
    }

    private static void checkSave(String path, byte[] content, String expectedHash)
    {
        checkPath(path);
        checkArgument(content != null && content.length <= MAXIMUM_FILE_BYTES, "The file is larger than 2 MiB");
        checkArgument(expectedHash != null, "expectedHash is required");
    }

    private static byte[] savedBytes(String content, String expectedHash)
    {
        checkArgument(content != null, "content is required");
        checkArgument(expectedHash != null, "expectedHash is required");
        byte[] bytes = content.getBytes(UTF_8);
        checkArgument(bytes.length <= MAXIMUM_FILE_BYTES, "The file is larger than 2 MiB");
        return bytes;
    }

    /** Overwrites an existing file in place, so its mode stays; run under the worktree claim. */
    private static void overwrite(Source source, String path, byte[] bytes, String expectedHash)
    {
        Path root = source.checkout().orElseThrow(() -> new ReviewConflictException("The working copy is gone"));
        // Repeated under the claim: the working copy may have changed since the editor loaded the file.
        readOnlyReason(source, true, symlinked(root, path)).ifPresent(reason -> {
            throw new ReviewConflictException(reason);
        });
        if (expectedHash.equals("-")) {
            try {
                Path file = root.resolve(path);
                Files.createDirectories(file.getParent());
                Files.write(file, bytes, LinkOption.NOFOLLOW_LINKS, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return;
            }
            catch (IOException failure) {
                throw new ReviewConflictException("Cannot create " + path + ": " + failure.getMessage(), failure);
            }
        }
        byte[] current = readCheckout(root, path);
        Path file = root.resolve(path);
        String currentHash = contentHash(file);
        if (!currentHash.equals(expectedHash)) {
            boolean shown = current.length <= MAXIMUM_FILE_BYTES && !binary(current);
            throw new ReviewFileConflictException(currentHash, shown ? Optional.of(new String(current, UTF_8)) : Optional.empty());
        }
        try {
            Files.write(file, bytes, LinkOption.NOFOLLOW_LINKS, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        catch (IOException failure) {
            throw new ReviewConflictException("Cannot save " + path + ": " + failure.getMessage(), failure);
        }
    }

    /** The canonical working copy a terminal shell starts in, while the review is open and its working copy exists. */
    public TerminalLaunch terminal(String reviewId)
    {
        AgentReview review = store.findReview(reviewId).orElseThrow(() -> new ReviewNotFoundException("Review not found: " + reviewId));
        if (review.state() != AgentReview.State.OPEN) {
            throw new ReviewConflictException("The review has ended");
        }
        AgentTask task = reviews.task(reviewId);
        return terminal(task, checkout(task).orElseThrow(() -> new ReviewConflictException("The working copy is gone")));
    }

    /** The canonical working copy a terminal shell starts in, while the task is open and owns the pull request. */
    public TerminalLaunch terminal(TaskCodeHost host)
    {
        AgentTask task = mutableTask(host);
        if (task.state() != TaskState.OPEN) {
            throw new ReviewConflictException("The task has ended");
        }
        return terminal(task, worktrees.ownedCheckout(task.taskWorktree().orElseThrow()));
    }

    private TerminalLaunch terminal(AgentTask task, Path checkout)
    {
        claudeAccounts.checkWorkspaceAccess(task.laneId().value(), task.workspaceId());
        Optional<Path> claudeHome = Optional.empty();
        if (task.laneId().usesClaude()) {
            Path home = agentProperties.claudeHome();
            if (!task.laneId().equals(AgentLaneId.CLAUDE_CLI)) {
                home = home.resolve("accounts").resolve(claudeAccounts.find(task.laneId().value()).orElseThrow().id());
            }
            claudeHome = Optional.of(home.toAbsolutePath().normalize());
        }
        return new TerminalLaunch(checkout, claudeHome);
    }

    public record TerminalLaunch(Path workingDirectory, Optional<Path> claudeConfigDirectory) {}

    private static Optional<DiffFile> prFile(Source source, String path)
    {
        return source.diff().get().stream().filter(file -> file.filename().equals(path)).findFirst();
    }

    private Source source(String reviewId)
    {
        return source(reviewId, true);
    }

    private Source source(String reviewId, boolean live)
    {
        AgentReview review = store.findReview(reviewId).orElseThrow(() -> new ReviewNotFoundException("Review not found: " + reviewId));
        List<ReviewRun> runs = store.listRuns(reviewId);
        AgentTask task = reviews.task(reviewId);
        Optional<Path> checkout = checkout(task);
        Optional<String> ended = review.state() == AgentReview.State.OPEN ? Optional.empty() : Optional.of("This review has ended — read-only");
        if (runs.isEmpty()) {
            Path directory = checkout.orElseGet(() -> repository(task));
            String head = checkout.isPresent() ? head(directory) : review.worktreeHeadSha().orElseThrow();
            Supplier<Base> base = Suppliers.memoize(() -> reviewBase(review, Optional.empty(), directory, head));
            return new Source(task, checkout, Suppliers.memoize(() -> localDiff.diff(directory, base.get().sha(), Optional.of(head)).files()),
                    () -> Optional.of(base.get().sha()), () -> head, ended);
        }
        ReviewRun run = runs.getLast();
        if (live && checkout.isPresent()) {
            Path directory = checkout.orElseThrow();
            String head = head(directory);
            if (!head.equals(run.headSha())) {
                // Catch-up changes code without changing the frozen review. Derive file marks from the checkout's own committed revision.
                Supplier<Base> base = Suppliers.memoize(() -> reviewBase(review, Optional.of(run), directory, head));
                return new Source(task, checkout, Suppliers.memoize(() -> localDiff.diff(directory, base.get().sha(), Optional.of(head)).files()),
                        () -> Optional.of(base.get().sha()), () -> head, ended);
            }
        }
        return new Source(task, checkout, run::diff, () -> Optional.of(run.mergeBaseSha()), run::headSha, ended);
    }

    /** Reads remain available during execution, but a head move cannot pair one revision's code with another revision's marks. */
    private void requireUnchangedHead(Source source)
    {
        if (source.checkout().isPresent() && !source.headSha().get().equals(head(source.checkout().orElseThrow()))) {
            throw new ReviewConflictException("The working copy changed while it was being read; refresh to read its current revision");
        }
    }

    private Base reviewBase(AgentReview review, Optional<ReviewRun> run, Path checkout, String head)
    {
        WorkspaceRepositoryContext workspace = workspaces.requireReadyRepository(review.workspaceId());
        String baseRef = review.number().stream().boxed().findFirst()
                .flatMap(number -> pullRequests.findPullRequestDetail(review.repo(), number))
                .flatMap(detail -> Optional.ofNullable(detail.baseRef())).or(() -> run.map(ReviewRun::baseRef))
                .orElseThrow(() -> new ReviewConflictException("The pull request base branch is unavailable; refresh the pull request"));
        String remote = workspace.selectedRepository().equals(workspace.monitoredRepository()) ? "origin" : workspace.sourceRemote();
        Result base = localDiff.read(checkout, "rev-parse", "--verify", "refs/remotes/" + remote + "/" + baseRef + "^{commit}");
        String tip = base.successful() && !base.truncated() ? base.output().strip()
                : run.map(ReviewRun::baseSha).orElseThrow(() -> new ReviewConflictException("The pull request base commit is unavailable"));
        return new Base(localDiff.require(checkout, "merge-base", tip, head).strip(), baseRef);
    }

    /** Resolves workspace ownership on every read; browsing never acquires a worktree mutation claim. */
    public AgentTask task(TaskCodeHost host)
    {
        AgentTask task = tasks.get(TaskId.valueOf(host.taskId()));
        if (!task.workspaceId().equals(Optional.of(host.workspaceId()))) {
            throw new ReviewNotFoundException("Task does not belong to this workspace");
        }
        if (liveWorktree(task).isEmpty()) {
            throw new ReviewConflictException("The task's worktree was removed");
        }
        worktrees.ownedCheckout(task.taskWorktree().orElseThrow());
        return task;
    }

    /** Task routes retain their development-only mutation policy; review mutations use the review-owned routes. */
    public AgentTask mutableTask(TaskCodeHost host)
    {
        AgentTask task = task(host);
        if (!task.interactive()) {
            throw new ReviewConflictException("This task's working copy is read-only here");
        }
        return task;
    }

    private Source source(TaskCodeHost host)
    {
        AgentTask task = task(host);
        Path checkout = worktrees.ownedCheckout(task.taskWorktree().orElseThrow());
        Supplier<Base> base = Suppliers.memoize(() -> taskBase(task, checkout));
        Supplier<String> head = Suppliers.memoize(() -> head(checkout));
        Optional<String> readOnly = task.state() != TaskState.OPEN ? Optional.of("This task has ended — read-only")
                : !task.interactive() ? Optional.of("This task's working copy is read-only here") : Optional.empty();
        return new Source(task, Optional.of(checkout), Suppliers.memoize(() -> localDiff.diff(checkout, base.get().sha(), Optional.of(head.get())).files()),
                () -> Optional.of(base.get().sha()), head, readOnly);
    }

    private Base taskBase(AgentTask task, Path checkout)
    {
        Optional<ReviewRun> reviewed = store.findReviewByTask(task.id().toString()).flatMap(review -> store.listRuns(review.id()).stream().reduce((left, right) -> right));
        if (reviewed.isPresent()) {
            ReviewRun run = reviewed.orElseThrow();
            return new Base(run.mergeBaseSha(), run.baseRef());
        }
        WorkspaceRepositoryContext workspace = workspaces.requireReadyRepository(task.workspaceId().orElseThrow());
        Optional<PullRequestTask> assigned = assignments.findByTaskId(task.id().toString());
        String branch = localDiff.require(checkout, "rev-parse", "--abbrev-ref", "HEAD").strip();
        Optional<String> baseRef = assigned.flatMap(pr -> pullRequests.findPullRequestDetail(pr.repo(), pr.number()))
                .or(() -> pullRequestRows.findByHeadRef(workspace.monitoredRepository().fullName(), branch).stream()
                        .filter(pr -> pr.origin() == PullRequest.Origin.AUTHORED || branch.equals(task.taskWorktree().orElseThrow().generatedBranch()))
                        .findFirst().flatMap(pr -> pullRequests.findPullRequestDetail(pr.repo(), pr.number())))
                .flatMap(detail -> Optional.ofNullable(detail.baseRef()));
        if (baseRef.isPresent()) {
            String remote = workspace.selectedRepository().equals(workspace.monitoredRepository()) ? "origin" : workspace.sourceRemote();
            Result base = localDiff.read(checkout, "merge-base", "HEAD", "refs/remotes/" + remote + "/" + baseRef.orElseThrow());
            if (base.successful() && !base.truncated()) {
                return new Base(base.output().strip(), baseRef.orElseThrow());
            }
        }
        return new Base(task.taskWorktree().orElseThrow().startCommit(), "Task starting commit");
    }

    public Changes changes(TaskCodeHost host, ChangeScope scope, Optional<String> commit, Optional<String> base, Optional<String> head)
    {
        Source source = source(host);
        Path checkout = source.checkout().orElseThrow();
        return changes(source, taskBase(source.task(), checkout), scope, commit, base, head, Optional.empty());
    }

    public Changes changes(String reviewId, ChangeScope scope, Optional<String> commit, Optional<String> base, Optional<String> head)
    {
        boolean historical = scope == ChangeScope.COMMITTED || head.isPresent();
        Source source = source(reviewId, !historical);
        List<ReviewRun> runs = store.listRuns(reviewId);
        if (runs.isEmpty()) {
            Base defaultBase = reviewBase(store.findReview(reviewId).orElseThrow(), Optional.empty(),
                    source.checkout().orElseGet(() -> repository(source.task())), source.headSha().get());
            return changes(source, defaultBase, scope, commit, base, head, Optional.of(reviewId));
        }
        ReviewRun run = head.map(revision -> runs.reversed().stream().filter(item -> item.headSha().equals(revision)).findFirst()
                .orElseThrow(() -> new ReviewNotFoundException("No review run exists for this revision"))).orElseGet(runs::getLast);
        Base defaultBase = historical || source.checkout().isEmpty() ? new Base(run.mergeBaseSha(), run.baseRef())
                : reviewBase(store.findReview(reviewId).orElseThrow(), Optional.of(run), source.checkout().orElseThrow(), source.headSha().get());
        return changes(source, defaultBase, scope, commit, base, head, Optional.of(reviewId));
    }

    private Changes changes(Source source, Base defaultBase, ChangeScope scope, Optional<String> commit, Optional<String> requestedBase,
            Optional<String> requestedHead, Optional<String> reviewId)
    {
        Path checkout = source.checkout().orElseGet(() -> repository(source.task()));
        boolean workingCopy = scope == ChangeScope.ALL || scope == ChangeScope.UNCOMMITTED;
        if (workingCopy && source.checkout().isEmpty()) {
            throw new ReviewConflictException("The task's worktree was removed");
        }
        checkArgument(!workingCopy || requestedHead.isEmpty(), "Working-copy changes cannot select a historical head");
        boolean live = workingCopy || scope == ChangeScope.COMMIT && requestedHead.isEmpty();
        String currentHead = live || source.checkout().isEmpty() ? source.headSha().get() : head(checkout);
        String selectedHead = requestedHead.map(value -> localDiff.commit(checkout, value)).orElseGet(() ->
                scope == ChangeScope.COMMITTED && reviewId.isPresent() ? source.headSha().get() : currentHead);
        if (!selectedHead.equals(currentHead)) {
            requireSourceCommit(checkout, selectedHead, currentHead, reviewId);
        }
        String base = requestedBase.map(value -> localDiff.commit(checkout, value)).orElse(defaultBase.sha());
        checkArgument(localDiff.read(checkout, "merge-base", "--is-ancestor", base, selectedHead).successful(), "Comparison base is not an ancestor of the selected head");
        String comparedBase = scope == ChangeScope.UNCOMMITTED ? currentHead : base;
        Optional<String> selectedCommit = Optional.empty();
        if (scope == ChangeScope.COMMIT) {
            String revision = localDiff.commit(checkout, commit.orElseThrow(() -> new IllegalArgumentException("A commit is required")));
            checkArgument(localDiff.read(checkout, "merge-base", "--is-ancestor", revision, selectedHead).successful(), "Commit is not part of the selected branch");
            checkArgument(!localDiff.read(checkout, "merge-base", "--is-ancestor", revision, base).successful(), "Commit is outside the comparison range");
            comparedBase = localDiff.require(checkout, "rev-parse", "--verify", revision + "^1").strip();
            selectedCommit = Optional.of(revision);
        }
        else {
            checkArgument(commit.isEmpty(), "Commit selection requires COMMIT scope");
        }
        WorktreeDiffReader.History history = localDiff.history(checkout, base, selectedHead);
        WorktreeDiffReader.Diff diff = localDiff.diff(checkout, comparedBase, workingCopy ? Optional.empty() : Optional.of(selectedCommit.orElse(selectedHead)));
        Result status = source.checkout().isPresent() ? status(checkout) : new Result(0, "", false);
        ListMultimap<String, String> codes = statusCodes(entries(status.output(), status.truncated()));
        List<String> staged = codes.keySet().stream()
                .filter(path -> codes.get(path).stream().anyMatch(code -> code.charAt(0) != ' ' && !code.equals("??"))).sorted().collect(toImmutableList());
        List<String> unstaged = codes.keySet().stream()
                .filter(path -> codes.get(path).stream().anyMatch(code -> code.charAt(1) != ' ' && !code.equals("??"))).sorted().collect(toImmutableList());
        List<String> untracked = codes.keySet().stream().filter(path -> codes.get(path).contains("??")).sorted().collect(toImmutableList());
        Map<String, DiffFile> files = new LinkedHashMap<>();
        diff.files().forEach(file -> files.put(file.filename(), file));
        boolean truncated = diff.truncated() || history.truncated() || status.truncated();
        if (workingCopy) {
            int patches = 0;
            int remainingPatchBytes = GitCommandRunner.MAXIMUM_OUTPUT_BYTES;
            for (String path : untracked) {
                boolean replacement = Optional.ofNullable(files.get(path)).filter(file -> file.status().equals("removed")).isPresent();
                if (!files.containsKey(path) || replacement) {
                    boolean show = patches++ < 50 && !symlinked(checkout, path) && Files.isRegularFile(checkout.resolve(path), LinkOption.NOFOLLOW_LINKS);
                    DiffFile file = show ? (replacement ? localDiff.replacement(checkout, comparedBase, path) : localDiff.untracked(checkout, path))
                            : new DiffFile(path, replacement ? "modified" : "added", 0, 0, null);
                    int bytes = Optional.ofNullable(file.patch()).map(patch -> patch.getBytes(UTF_8).length).orElse(0);
                    if (bytes > remainingPatchBytes) {
                        file = new DiffFile(path, file.status(), file.additions(), file.deletions(), null);
                        truncated = true;
                    }
                    else {
                        remainingPatchBytes -= bytes;
                    }
                    files.put(path, file);
                    truncated |= patches > 50;
                }
            }
            // A staged change can have been reversed only in the working tree, leaving no net HEAD diff but still needing inspection.
            for (String path : staged) {
                if (!files.containsKey(path) && !untracked.contains(path)) {
                    files.put(path, new DiffFile(path, "modified", 0, 0, null));
                }
            }
        }
        String branch = source.checkout().isPresent() ? localDiff.require(checkout, "rev-parse", "--abbrev-ref", "HEAD").strip() : "Reviewed revision";
        Optional<String> fingerprint = workingCopy ? Optional.of(fingerprint(checkout, currentHead, codes.keySet())) : Optional.empty();
        if (live) {
            requireUnchangedHead(source);
        }
        return new Changes(branch, selectedHead, base, requestedBase.isPresent() ? base : defaultBase.label(), scope, selectedCommit, fingerprint,
                history.commits(), ImmutableList.copyOf(files.values()), staged, unstaged, untracked, truncated);
    }

    private void requireSourceCommit(Path checkout, String selected, String current, Optional<String> reviewId)
    {
        boolean retained = reviewId.map(id -> store.listRuns(id).stream().anyMatch(run -> run.headSha().equals(selected))).orElse(false);
        checkArgument(retained || localDiff.read(checkout, "merge-base", "--is-ancestor", selected, current).successful(), "Head is outside this task's history");
    }

    private record Base(String sha, String label) {}

    public enum ChangeScope
    {
        ALL,
        UNCOMMITTED,
        COMMITTED,
        COMMIT
    }

    public record Changes(String branch, String headSha, String baseSha, String baseLabel, ChangeScope scope, Optional<String> selectedCommit,
            Optional<String> sourceFingerprint, List<DiffCommit> commits, List<DiffFile> files,
            List<String> staged, List<String> unstaged, List<String> untracked, boolean truncated)
    {
        public Changes
        {
            commits = ImmutableList.copyOf(commits);
            files = ImmutableList.copyOf(files);
            staged = ImmutableList.copyOf(staged);
            unstaged = ImmutableList.copyOf(unstaged);
            untracked = ImmutableList.copyOf(untracked);
        }
    }

    private Optional<Path> checkout(AgentTask task)
    {
        return liveWorktree(task).map(worktrees::ownedCheckout);
    }

    private static Optional<TaskWorktree> liveWorktree(AgentTask task)
    {
        return task.taskWorktree().filter(worktree -> task.worktreeRemovedAt().isEmpty() && Files.isDirectory(worktree.canonicalWorktree(), LinkOption.NOFOLLOW_LINKS));
    }

    private Path repository(AgentTask task)
    {
        // The workspace accessor synchronizes only a never-synchronized workspace, which a task with a worktree cannot have.
        return workspaces.requireReadyRepository(task.workspaceId().orElseThrow()).repository();
    }

    /** Reads up to one byte past the size cap, refusing a path whose real location leaves the working copy. */
    static byte[] readCheckout(Path root, String path)
    {
        try {
            Path real = root.resolve(path).toRealPath();
            checkArgument(real.startsWith(root), "path leaves the working copy: %s", path);
            if (!Files.isRegularFile(real)) {
                throw notFound(path);
            }
            try (InputStream input = Files.newInputStream(real)) {
                return input.readNBytes(MAXIMUM_FILE_BYTES + 1);
            }
        }
        catch (IOException failure) {
            throw notFound(path);
        }
    }

    private static CodeFile content(String path, byte[] bytes, boolean tooLarge, Optional<String> readOnlyReason, Optional<String> prPatch, Supplier<List<LineChange>> lineChanges)
    {
        Optional<String> unavailable = binary(bytes) ? Optional.of("Binary file")
                : tooLarge ? Optional.of("This file is larger than 2 MiB")
                : Optional.empty();
        if (unavailable.isPresent()) {
            return new CodeFile(path, Optional.empty(), Optional.empty(), readOnlyReason, unavailable, prPatch, ImmutableList.of());
        }
        return new CodeFile(path, Optional.of(new String(bytes, UTF_8)), Optional.of(Hashing.sha256().hashBytes(bytes).toString()), readOnlyReason, Optional.empty(), prPatch,
                lineChanges.get());
    }

    private static boolean binary(byte[] bytes)
    {
        for (int index = 0; index < Math.min(bytes.length, BINARY_PROBE_BYTES); index++) {
            if (bytes[index] == 0) {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> readOnlyReason(Source source, boolean workingCopy, boolean symlinked)
    {
        if (source.ended().isPresent()) {
            return source.ended();
        }
        if (!workingCopy) {
            return Optional.of("Read-only: the working copy is gone");
        }
        if (symlinked) {
            return Optional.of("Symbolic links are read-only here");
        }
        return Optional.empty();
    }

    /** Whether the file or any directory between it and the checkout root is a symbolic link, so a PR cannot aim an editable path elsewhere. */
    private static boolean symlinked(Path root, String path)
    {
        Path current = root;
        for (String name : Splitter.on('/').split(path)) {
            current = current.resolve(name);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    static void checkPath(String path)
    {
        checkArgument(path != null && !path.isBlank(), "path must not be blank");
        checkArgument(path.indexOf('\0') < 0 && path.indexOf('\\') < 0, "path must not contain NUL or backslash characters");
        checkArgument(!path.startsWith("/"), "path must be repository-relative");
        for (String name : Splitter.on('/').split(path)) {
            checkArgument(!name.isEmpty() && !name.equals(".") && !name.equals(".."), "path must be a normalized repository-relative path");
            // Git never tracks a .git component; a worktree's .git file would only reveal where its metadata lives.
            checkArgument(!name.equalsIgnoreCase(".git"), "path must not name Git metadata");
        }
    }

    /** NUL-terminated Git output entries; truncated output loses its last, possibly partial, entry. */
    static List<String> entries(String output, boolean truncated)
    {
        String complete = truncated ? output.substring(0, output.lastIndexOf('\0') + 1) : output;
        return Splitter.on('\0').omitEmptyStrings().splitToList(complete);
    }

    /** Porcelain v1 status codes by path. A rename or copy entry is followed by its source path, which names no current file. */
    static ListMultimap<String, String> statusCodes(List<String> entries)
    {
        ImmutableListMultimap.Builder<String, String> codes = ImmutableListMultimap.builder();
        for (int index = 0; index < entries.size(); index++) {
            String entry = entries.get(index);
            checkState(entry.length() > 3 && entry.charAt(2) == ' ', "Unexpected Git status entry: %s", entry);
            String code = entry.substring(0, 2);
            codes.put(entry.substring(3), code);
            if (code.indexOf('R') >= 0 || code.indexOf('C') >= 0) {
                index++;
            }
        }
        return codes.build();
    }

    private static ReviewNotFoundException notFound(String path)
    {
        return new ReviewNotFoundException("File not found: " + path);
    }

    /**
     * What one host reads: a review's latest run, or a task's local branch changes and worktree HEAD.
     *
     * @param headSha the revision a missing working copy is read at and renames are paired to
     * @param ended why the host no longer accepts edits
     */
    private record Source(
            AgentTask task,
            Optional<Path> checkout,
            Supplier<List<DiffFile>> diff,
            Supplier<Optional<String>> mergeBaseSha,
            Supplier<String> headSha,
            Optional<String> ended) {}

    public enum CodeSource
    {
        WORKING_COPY,
        REVISION
    }

    /**
     * A listing of the working copy or of the reviewed revision.
     *
     * @param sourceFingerprint the working copy's {@link #fingerprint}; empty for a revision
     * @param savedExperiment a failed Update left this review's experiments stashed, which Discard also drops
     */
    public record CodeFiles(
            CodeSource source,
            String revision,
            Optional<String> sourceFingerprint,
            boolean savedExperiment,
            boolean truncated,
            List<CodeFileEntry> files)
    {
        public CodeFiles
        {
            requireNonNull(source, "source is null");
            requireNonNull(revision, "revision is null");
            requireNonNull(sourceFingerprint, "sourceFingerprint is null");
            files = ImmutableList.copyOf(files);
        }
    }

    /**
     * The source a run starts against.
     *
     * @param localPaths uncommitted or working-copy-only paths, sorted
     */
    public record WorkingCopySource(String head, List<String> localPaths, String fingerprint)
    {
        public WorkingCopySource
        {
            requireNonNull(head, "head is null");
            localPaths = ImmutableList.copyOf(localPaths);
            requireNonNull(fingerprint, "fingerprint is null");
        }
    }

    /**
     * One listed file.
     *
     * @param addedInPr the pull request adds this file
     * @param onlyInWorkingCopy untracked, or staged as a new, renamed or copied path, so absent from HEAD
     */
    public record CodeFileEntry(String path, boolean changedInPr, boolean addedInPr, boolean onlyInWorkingCopy, boolean uncommitted)
    {
        public CodeFileEntry
        {
            requireNonNull(path, "path is null");
        }
    }

    public record CodeFile(
            String path,
            Optional<String> content,
            Optional<String> contentHash,
            Optional<String> readOnlyReason,
            Optional<String> unavailableReason,
            Optional<String> prPatch,
            List<LineChange> lineChanges)
    {
        public CodeFile
        {
            requireNonNull(path, "path is null");
            requireNonNull(content, "content is null");
            requireNonNull(contentHash, "contentHash is null");
            requireNonNull(readOnlyReason, "readOnlyReason is null");
            requireNonNull(unavailableReason, "unavailableReason is null");
            requireNonNull(prPatch, "prPatch is null");
            lineChanges = ImmutableList.copyOf(lineChanges);
        }

        public boolean editable()
        {
            return readOnlyReason.isEmpty();
        }
    }

    /**
     * One contiguous change against the merge base.
     *
     * @param line the first added or modified line; for {@link Kind#DELETED}, the line the removed lines followed, 0 for the top of the file
     * @param count the added or modified lines; for {@link Kind#DELETED}, the removed lines
     */
    public record LineChange(Kind kind, int line, int count)
    {
        public enum Kind
        {
            ADDED,
            MODIFIED,
            DELETED
        }

        public LineChange
        {
            requireNonNull(kind, "kind is null");
            checkArgument(line >= 0 && count > 0, "invalid line change: %s,%s", line, count);
        }
    }
}
