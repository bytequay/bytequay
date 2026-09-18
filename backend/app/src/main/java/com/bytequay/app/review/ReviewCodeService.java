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

import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.CodeGraphService;
import com.bytequay.agent.core.CodeSymbol;
import com.bytequay.agent.core.CodeSymbolResult;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.app.agent.TaskPullRequestOwnership;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.app.git.ReviewExperiments;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.ManagedWorktreeService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.core.review.ReviewRun;
import com.google.common.base.Splitter;
import com.google.common.base.Suppliers;
import com.google.common.collect.ContiguousSet;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.ListMultimap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.MoreFiles;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
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
import static java.util.Comparator.comparingInt;
import static java.util.Objects.requireNonNull;

/**
 * Whole-file reads for a review, its working copy while that exists, else the latest run's frozen head in the managed
 * repository; or for a development task that owns a pull request, its working copy.
 *
 * <p>Reads never inspect the worktree through {@code WorktreeService.inspect}: its status takes Git's
 * optional index lock, so a poll could make Update's stash or merge fail on {@code index.lock}.
 */
@Component
public class ReviewCodeService
{
    static final int MAXIMUM_FILE_BYTES = 2 * 1024 * 1024;
    private static final int BINARY_PROBE_BYTES = 8 * 1024;
    private static final Pattern TEST_ANNOTATION = Pattern.compile("@(?:[\\w.]+\\.)?(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");
    private static final Pattern HUNK_HEADER = Pattern.compile("@@ -\\d+(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");

    private final AgentReviewStore store;
    private final AgentReviewService reviews;
    private final ManagedWorktreeService worktrees;
    private final WorkspaceRepositoryService workspaces;
    private final GitCommandRunner git;
    private final CodeGraphService codeGraph;
    private final AgentResourceService resources;
    private final ReviewExperiments experiments;
    private final AgentTaskService tasks;
    private final AgentTurnService turns;
    private final TaskPullRequestOwnership pullRequestOwnership;
    private final PullRequestService pullRequests;

    public ReviewCodeService(
            AgentReviewStore store,
            AgentReviewService reviews,
            ManagedWorktreeService worktrees,
            WorkspaceRepositoryService workspaces,
            GitCommandRunner git,
            CodeGraphService codeGraph,
            AgentResourceService resources,
            ReviewExperiments experiments,
            AgentTaskService tasks,
            AgentTurnService turns,
            TaskPullRequestOwnership pullRequestOwnership,
            PullRequestService pullRequests)
    {
        this.store = requireNonNull(store, "store is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.git = requireNonNull(git, "git is null");
        this.codeGraph = requireNonNull(codeGraph, "codeGraph is null");
        this.resources = requireNonNull(resources, "resources is null");
        this.experiments = requireNonNull(experiments, "experiments is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.pullRequestOwnership = requireNonNull(pullRequestOwnership, "pullRequestOwnership is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
    }

    public CodeFiles files(String reviewId)
    {
        return files(source(reviewId));
    }

    public CodeFiles files(TaskCodeHost host)
    {
        return files(source(host));
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
            return new CodeFiles(CodeSource.REVISION, revision, codeGraphState(source), Optional.empty(), false, listed.truncated(), files);
        }
        Path checkout = source.checkout().orElseThrow();
        String head = head(checkout);
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
        return new CodeFiles(CodeSource.WORKING_COPY, head, codeGraphState(source), Optional.of(fingerprint(checkout, head, codes.keySet())),
                experiments.hasSavedExperiment(source.task().taskWorktree().orElseThrow()), listed.truncated() || status.truncated(), files.build());
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
        checkPath(path);
        return file(source(reviewId), path);
    }

    public CodeFile file(TaskCodeHost host, String path)
    {
        checkPath(path);
        return file(source(host), path);
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
            return content(path, bytes, tooLarge, readOnlyReason(source, false, path, false), prPatch, () -> lineChanges(repository, source, Optional.of(revision), path));
        }
        Path root = source.checkout().orElseThrow();
        byte[] bytes = readCheckout(root, path);
        boolean symlinked = symlinked(root, path);
        // Git diffs a link's target text, not the file shown through it.
        return content(path, bytes, bytes.length > MAXIMUM_FILE_BYTES, readOnlyReason(source, true, path, symlinked), prPatch,
                () -> symlinked ? ImmutableList.of() : lineChanges(root, source, Optional.empty(), path));
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
     * Writes a test source of the review's working copy over the version the editor loaded.
     *
     * @param expectedHash the {@link CodeFile#contentHash} the edit started from
     * @return the saved file, read back like {@link #file}
     * @throws ReviewFileConflictException when the bytes on disk no longer hash to {@code expectedHash}
     */
    public CodeFile save(String reviewId, String path, String content, String expectedHash)
    {
        checkPath(path);
        checkArgument(testSource(path), "Only test sources can be edited here");
        byte[] bytes = savedBytes(content, expectedHash);
        return reviews.mutateWorkingCopy(reviewId, task -> {
            Source source = source(reviewId);
            overwrite(source, path, bytes, expectedHash);
            return file(source, path);
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
        checkPath(path);
        byte[] bytes = savedBytes(content, expectedHash);
        Source source = source(host);
        LocalRunService.requireTaskIdle(turns, source.task());
        turns.synchronizeWorktree(source.task().id(), source.task().version(), claimed -> overwrite(source, path, bytes, expectedHash));
        return file(source, path);
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
        readOnlyReason(source, true, path, symlinked(root, path)).ifPresent(reason -> {
            throw new ReviewConflictException(reason);
        });
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

    /**
     * Resets the working copy to {@code expectedHead}, removing uncommitted and untracked files but not ignored ones,
     * and drops the experiments a failed Update saved.
     *
     * @param expectedHead the {@link CodeFiles#revision} the user confirmed discarding against
     * @return the clean working copy, listed like {@link #files}
     */
    public CodeFiles discard(String reviewId, String expectedHead)
    {
        checkArgument(expectedHead != null && !expectedHead.isBlank(), "expectedHead is required");
        return reviews.mutateWorkingCopy(reviewId, task -> {
            try {
                experiments.discard(task.taskWorktree().orElseThrow(), expectedHead);
            }
            catch (IllegalStateException failure) {
                throw new ReviewConflictException(failure.getMessage(), failure);
            }
            return files(reviewId);
        });
    }

    /** The canonical working copy a terminal shell starts in, while the review is open and its working copy exists. */
    public Path terminalDirectory(String reviewId)
    {
        AgentReview review = store.findReview(reviewId).orElseThrow(() -> new ReviewNotFoundException("Review not found: " + reviewId));
        if (review.state() != AgentReview.State.OPEN) {
            throw new ReviewConflictException("The review has ended");
        }
        return checkout(reviews.task(reviewId)).orElseThrow(() -> new ReviewConflictException("The working copy is gone"));
    }

    /** The canonical working copy a terminal shell starts in, while the task is open and owns the pull request. */
    public Path terminalDirectory(TaskCodeHost host)
    {
        AgentTask task = task(host);
        if (task.state() != TaskState.OPEN) {
            throw new ReviewConflictException("The task has ended");
        }
        return worktrees.ownedCheckout(task.taskWorktree().orElseThrow());
    }

    public CodeSymbolResult symbols(String reviewId, String query, int limit)
    {
        return symbols(source(reviewId), query, limit);
    }

    public CodeSymbolResult symbols(TaskCodeHost host, String query, int limit)
    {
        return symbols(source(host), query, limit);
    }

    /** Symbols matching a name in the working copy, from the task's code graph. */
    private CodeSymbolResult symbols(Source source, String query, int limit)
    {
        return graphWorktree(source)
                .map(worktree -> codeGraph.searchSymbols(worktree, resources.require(source.task().id()), query, limit))
                .orElse(CodeSymbolResult.UNAVAILABLE);
    }

    /**
     * One working-copy file's symbols in line order, each marked when it is a JUnit test method and when
     * the pull request added every line of it.
     *
     * <p>CodeGraph records no Java annotations on its nodes, so a method is a test when the lines from
     * its first line, where its annotations start, to the line naming it carry a JUnit test annotation.
     */
    public CodeOutline outline(String reviewId, String path)
    {
        checkPath(path);
        return outline(source(reviewId), path);
    }

    public CodeOutline outline(TaskCodeHost host, String path)
    {
        checkPath(path);
        return outline(source(host), path);
    }

    private CodeOutline outline(Source source, String path)
    {
        Optional<TaskWorktree> worktree = graphWorktree(source);
        if (worktree.isEmpty()) {
            return new CodeOutline(CodeSymbolResult.Status.UNAVAILABLE, ImmutableList.of());
        }
        byte[] bytes = readCheckout(source.checkout().orElseThrow(), path);
        List<String> lines = bytes.length > MAXIMUM_FILE_BYTES ? ImmutableList.of() : new String(bytes, UTF_8).lines().collect(toImmutableList());
        Set<Integer> addedLines = prFile(source, path).stream()
                .flatMap(file -> ReviewDiffAnchors.of(ImmutableList.of(file)).hunks(path).stream())
                .flatMap(hunk -> hunk.lines().stream())
                .filter(line -> line.kind() == '+')
                .map(ReviewDiffAnchors.Line::line)
                .collect(toImmutableSet());
        CodeSymbolResult result = codeGraph.fileSymbols(worktree.get(), resources.require(source.task().id()), path);
        return new CodeOutline(result.status(), result.symbols().stream()
                .sorted(comparingInt(CodeSymbol::startLine))
                .map(symbol -> new OutlineSymbol(symbol, testMethod(lines, symbol), addedLines.containsAll(ContiguousSet.closed(symbol.startLine(), symbol.endLine()))))
                .collect(toImmutableList()));
    }

    private static Optional<DiffFile> prFile(Source source, String path)
    {
        return source.diff().get().stream().filter(file -> file.filename().equals(path)).findFirst();
    }

    private static boolean testMethod(List<String> lines, CodeSymbol symbol)
    {
        if (!symbol.kind().equals("method")) {
            return false;
        }
        for (int line = symbol.startLine(); line <= Math.min(symbol.endLine(), lines.size()); line++) {
            String text = lines.get(line - 1);
            if (TEST_ANNOTATION.matcher(text).find()) {
                return true;
            }
            if (text.contains(symbol.name() + "(")) {
                return false;
            }
        }
        return false;
    }

    private CodeGraphState codeGraphState(Source source)
    {
        if (!codeGraph.available()) {
            return CodeGraphState.UNAVAILABLE;
        }
        if (!source.task().codegraph()) {
            return CodeGraphState.OFF;
        }
        return source.checkout().isPresent() ? CodeGraphState.READY : CodeGraphState.UNAVAILABLE;
    }

    /** The worktree graph reads may use: only a live working copy of a task stamped with the graph, while the helper is installed. */
    private Optional<TaskWorktree> graphWorktree(Source source)
    {
        return codeGraphState(source) == CodeGraphState.READY ? source.task().taskWorktree() : Optional.empty();
    }

    private Source source(String reviewId)
    {
        AgentReview review = store.findReview(reviewId).orElseThrow(() -> new ReviewNotFoundException("Review not found: " + reviewId));
        List<ReviewRun> runs = store.listRuns(reviewId);
        if (runs.isEmpty()) {
            throw new ReviewConflictException("This review has no run yet");
        }
        ReviewRun run = runs.getLast();
        AgentTask task = reviews.task(reviewId);
        return new Source(task, checkout(task), run::diff, () -> Optional.of(run.mergeBaseSha()), run::headSha,
                review.state() == AgentReview.State.OPEN ? Optional.empty() : Optional.of("This review has ended — read-only"), true);
    }

    /**
     * The development task a task host names, resolved again on every request.
     *
     * @throws ReviewNotFoundException when the pull request does not belong to the task
     * @throws ReviewConflictException when the task's worktree was removed
     */
    public AgentTask task(TaskCodeHost host)
    {
        AgentTask task = tasks.getInteractive(host.workspaceId(), TaskId.valueOf(host.taskId()));
        if (!pullRequestOwnership.owns(task, host.repo(), host.number(), false)) {
            throw new ReviewNotFoundException("Pull request %s#%s does not belong to this task".formatted(host.repo(), host.number()));
        }
        if (liveWorktree(task).isEmpty()) {
            throw new ReviewConflictException("The task's worktree was removed");
        }
        return task;
    }

    private Source source(TaskCodeHost host)
    {
        AgentTask task = task(host);
        Path checkout = worktrees.ownedCheckout(task.taskWorktree().orElseThrow());
        return new Source(task, Optional.of(checkout), Suppliers.memoize(() -> storedDiff(host)), Suppliers.memoize(() -> mergeBase(checkout, host)),
                Suppliers.memoize(() -> head(checkout)), task.state() == TaskState.OPEN ? Optional.empty() : Optional.of("This task has ended — read-only"), false);
    }

    /** The pull request's stored diff, which is approximate while the worktree HEAD is ahead of the pushed head. */
    private List<DiffFile> storedDiff(TaskCodeHost host)
    {
        try {
            return pullRequests.getPullRequestDiffFiles(host.repo(), host.number(), null, null);
        }
        catch (ResponseStatusException notStored) {
            // A diff never refreshed from GitHub: the files still read, without pull-request marks.
            return ImmutableList.of();
        }
    }

    /** The merge base with the pull request's base branch as last fetched into the worktree's repository; never fetches. */
    private Optional<String> mergeBase(Path checkout, TaskCodeHost host)
    {
        Optional<String> baseRef = pullRequests.findPullRequestDetail(host.repo(), host.number()).flatMap(detail -> Optional.ofNullable(detail.baseRef()));
        if (baseRef.isEmpty()) {
            return Optional.empty();
        }
        WorkspaceRepositoryContext workspace = workspaces.requireReadyRepository(host.workspaceId());
        // The remote assigning a pull request to a task, and starting a review, fetch the base branch into.
        String remote = workspace.selectedRepository().equals(workspace.monitoredRepository()) ? "origin" : workspace.sourceRemote();
        Result base = git.runStdout(checkout, "merge-base", "HEAD", "refs/remotes/" + remote + "/" + baseRef.orElseThrow());
        return base.successful() && !base.truncated() ? Optional.of(base.output().strip()) : Optional.empty();
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

    private static Optional<String> readOnlyReason(Source source, boolean workingCopy, String path, boolean symlinked)
    {
        if (source.ended().isPresent()) {
            return source.ended();
        }
        if (!workingCopy) {
            return Optional.of("Read-only: the working copy is gone");
        }
        if (source.testSourcesOnly() && !testSource(path)) {
            return Optional.of("Production source — read-only here");
        }
        if (symlinked) {
            return Optional.of("Symbolic links are read-only here");
        }
        return Optional.empty();
    }

    private static boolean testSource(String path)
    {
        return path.startsWith("src/test/") || path.contains("/src/test/");
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
     * What one host reads: a review's latest run, or a development task's stored pull request and worktree HEAD.
     *
     * @param headSha the revision a missing working copy is read at and renames are paired to
     * @param ended why the host no longer accepts edits
     * @param testSourcesOnly only test sources are editable, as on a review
     */
    private record Source(
            AgentTask task,
            Optional<Path> checkout,
            Supplier<List<DiffFile>> diff,
            Supplier<Optional<String>> mergeBaseSha,
            Supplier<String> headSha,
            Optional<String> ended,
            boolean testSourcesOnly) {}

    public enum CodeSource
    {
        WORKING_COPY,
        REVISION
    }

    /** Whether graph reads can run: {@code OFF} when the helper is installed but the review's task was created without the graph. */
    public enum CodeGraphState
    {
        READY,
        OFF,
        UNAVAILABLE
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
            CodeGraphState codeGraph,
            Optional<String> sourceFingerprint,
            boolean savedExperiment,
            boolean truncated,
            List<CodeFileEntry> files)
    {
        public CodeFiles
        {
            requireNonNull(source, "source is null");
            requireNonNull(revision, "revision is null");
            requireNonNull(codeGraph, "codeGraph is null");
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

    /** A file's symbols; empty unless the status is {@code READY}. */
    public record CodeOutline(CodeSymbolResult.Status status, List<OutlineSymbol> symbols)
    {
        public CodeOutline
        {
            requireNonNull(status, "status is null");
            symbols = ImmutableList.copyOf(symbols);
        }
    }

    /**
     * One outline entry.
     *
     * @param added every line of the symbol is an added line in the pull request's patch for its file
     */
    public record OutlineSymbol(CodeSymbol symbol, boolean test, boolean added)
    {
        public OutlineSymbol
        {
            requireNonNull(symbol, "symbol is null");
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
