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
package com.bytequay.app.agent.codegraph;

import com.bytequay.agent.core.CodeGraphException;
import com.bytequay.agent.core.CodeGraphService;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.google.common.collect.ImmutableList;
import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.util.Objects.requireNonNull;

/**
 * {@link CodeGraphService} over the pinned CodeGraph 1.6.0 bundle.
 *
 * <p>A task index lives at {@code <task-runtime>/codegraph} beside a sidecar recording the commit it
 * was last brought up to date with. A repository baseline lives at
 * {@code <baselines>/<owner>/<name>/codegraph} beside its {@code HEAD}. Every query first
 * synchronizes the task index, so the agent never refreshes anything; the only decision is whether
 * an unscoped sync or a full rebuild is cheaper, which the changed-file count answers. Locks are
 * taken task first, then repository, and a refresh takes only the repository lock, so a prepare
 * waits for a running refresh and an explore waits for a running prepare.
 */
public final class PinnedCodeGraphService
        implements CodeGraphService
{
    static final String TRUNCATION_MARKER = "\n[truncated by ByteQuay: the graph result exceeded the tool limit]";
    static final String STILL_PREPARING = "CodeGraph index is still being prepared; retry shortly";
    static final int DEFAULT_RESULT_LIMIT_BYTES = 16 * 1024;
    static final int MAXIMUM_QUERY_LENGTH = 2000;
    // Measured on 2026-09-04: above roughly five percent of the tracked files an unscoped sync
    // costs more than a full rebuild (Trino: 924 changed files synced in 47 s, rebuilt in 42 s).
    static final double REBUILD_FRACTION = 0.05;
    static final String INDEX_DIRECTORY = "codegraph";
    static final String HEAD_FILE = "HEAD";
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration QUERY_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration PREPARE_WAIT = Duration.ofSeconds(60);
    private static final Duration COPY_TIMEOUT = Duration.ofMinutes(5);
    private static final String TASK_HEAD_FILE = "codegraph.head";
    private static final String SCRATCH_HOME = "codegraph-home";
    private static final String DATABASE = "codegraph.db";
    private static final String NEXT_DIRECTORY = "codegraph.next";
    private static final String OLD_DIRECTORY = "codegraph.old";
    private static final String CHECKOUT_DIRECTORY = "checkout";

    private final CodeGraphCli cli;
    private final GitCommandRunner git;
    private final Path baselineRoot;
    private final int resultLimitBytes;
    private final Duration buildTimeout;
    private final Duration queryTimeout;
    private final Duration prepareWait;
    // ponytail: one lock per task index and per repository, never evicted; both sets are small.
    private final ConcurrentMap<Path, ReentrantLock> taskLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<RepositoryIdentity, ReentrantLock> repositoryLocks = new ConcurrentHashMap<>();

    public PinnedCodeGraphService(CodeGraphCli cli, GitCommandRunner git, Path baselineRoot)
    {
        this(cli, git, baselineRoot, DEFAULT_RESULT_LIMIT_BYTES, BUILD_TIMEOUT, QUERY_TIMEOUT, PREPARE_WAIT);
    }

    PinnedCodeGraphService(CodeGraphCli cli, GitCommandRunner git, Path baselineRoot, int resultLimitBytes, Duration buildTimeout, Duration queryTimeout, Duration prepareWait)
    {
        this.cli = requireNonNull(cli, "cli is null");
        this.git = requireNonNull(git, "git is null");
        this.baselineRoot = requireNonNull(baselineRoot, "baselineRoot is null").toAbsolutePath().normalize();
        checkArgument(resultLimitBytes > 0, "resultLimitBytes is not positive");
        this.resultLimitBytes = resultLimitBytes;
        this.buildTimeout = requireNonNull(buildTimeout, "buildTimeout is null");
        this.queryTimeout = requireNonNull(queryTimeout, "queryTimeout is null");
        this.prepareWait = requireNonNull(prepareWait, "prepareWait is null");
    }

    @Override
    public void refreshBaseline(RepositoryIdentity repository, Path repositoryDirectory, String defaultBranchCommit)
    {
        requireNonNull(repository, "repository is null");
        requireNonNull(repositoryDirectory, "repositoryDirectory is null");
        requireNonNull(defaultBranchCommit, "defaultBranchCommit is null");
        checkArgument(!defaultBranchCommit.isBlank(), "defaultBranchCommit is blank");
        Path base = baselineDirectory(repository);
        Path index = base.resolve(INDEX_DIRECTORY);
        Path head = base.resolve(HEAD_FILE);
        ReentrantLock lock = lockFor(repository);
        lock.lock();
        try {
            if (usable(index, head) && readHead(head).orElseThrow().equals(defaultBranchCommit)) {
                return;
            }
            Path checkout = base.resolve(CHECKOUT_DIRECTORY);
            Path next = base.resolve(NEXT_DIRECTORY);
            Path scratch = base.resolve(SCRATCH_HOME);
            createDirectories(base);
            discardLeftovers(base, repositoryDirectory);
            addCheckout(repositoryDirectory, checkout, defaultBranchCommit);
            try {
                Result result;
                if (usable(index, head) && !rebuildNeeded(changedFiles(checkout, readHead(head).orElseThrow()), trackedFiles(checkout))) {
                    copy(index, next);
                    result = cli.run(checkout, next, scratch, buildTimeout, ImmutableList.of("sync", checkout.toString(), "--quiet"));
                }
                else {
                    result = cli.run(checkout, next, scratch, buildTimeout, ImmutableList.of("init", checkout.toString(), "--yes"));
                }
                if (!result.successful()) {
                    // A leftover codegraph.next is cleared by the next refresh; the old baseline is untouched.
                    throw unavailable("baseline refresh", result);
                }
                swap(index, next, base.resolve(OLD_DIRECTORY));
                writeHead(head, defaultBranchCommit);
            }
            finally {
                removeCheckout(repositoryDirectory, checkout);
            }
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public void prepare(TaskWorktree worktree, TaskResources resources)
    {
        requireNonNull(worktree, "worktree is null");
        requireNonNull(resources, "resources is null");
        Path source = worktree.canonicalWorktree();
        ReentrantLock taskLock = lockFor(resources);
        taskLock.lock();
        try {
            if (!usable(indexDirectory(resources), headFile(resources))) {
                cloneBaseline(worktree.physicalRepository(), resources);
            }
            synchronize(source, resources);
        }
        finally {
            taskLock.unlock();
        }
    }

    @Override
    public String explore(TaskWorktree worktree, TaskResources resources, String query)
    {
        requireNonNull(worktree, "worktree is null");
        requireNonNull(resources, "resources is null");
        requireNonNull(query, "query is null");
        checkArgument(!query.isBlank(), "query is blank");
        checkArgument(query.length() <= MAXIMUM_QUERY_LENGTH, "query is longer than %s characters", MAXIMUM_QUERY_LENGTH);
        Path source = worktree.canonicalWorktree();
        ReentrantLock lock = lockFor(resources);
        if (!tryLock(lock, prepareWait)) {
            throw new CodeGraphException(STILL_PREPARING);
        }
        try {
            synchronize(source, resources);
            Result result = cli.run(source, indexDirectory(resources), scratchHome(resources), queryTimeout, ImmutableList.of("explore", query));
            if (!result.successful()) {
                throw unavailable("query", result);
            }
            return cap(result.output());
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public void deleteIndex(TaskResources resources)
    {
        requireNonNull(resources, "resources is null");
        ReentrantLock lock = lockFor(resources);
        lock.lock();
        try {
            deleteRecursively(indexDirectory(resources));
            deleteRecursively(scratchHome(resources));
            Files.deleteIfExists(headFile(resources));
        }
        catch (IOException e) {
            throw new CodeGraphException("Cannot delete the CodeGraph index: " + e.getMessage(), e);
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public void deleteBaseline(RepositoryIdentity repository)
    {
        requireNonNull(repository, "repository is null");
        ReentrantLock lock = lockFor(repository);
        lock.lock();
        try {
            deleteRecursively(baselineDirectory(repository));
        }
        catch (IOException e) {
            throw new CodeGraphException("Cannot delete the CodeGraph baseline: " + e.getMessage(), e);
        }
        finally {
            lock.unlock();
        }
    }

    static boolean rebuildNeeded(long changedFiles, long trackedFiles)
    {
        return changedFiles < 0 || changedFiles > trackedFiles * REBUILD_FRACTION;
    }

    Path baselineDirectory(RepositoryIdentity repository)
    {
        return baselineRoot.resolve(repository.owner()).resolve(repository.name());
    }

    /** Clones the baseline into the task runtime under the repository lock, so a running refresh finishes first. */
    private void cloneBaseline(RepositoryIdentity repository, TaskResources resources)
    {
        ReentrantLock lock = lockFor(repository);
        lock.lock();
        try {
            Path base = baselineDirectory(repository);
            Path index = base.resolve(INDEX_DIRECTORY);
            Path head = base.resolve(HEAD_FILE);
            if (!usable(index, head)) {
                return;
            }
            Path target = indexDirectory(resources);
            deleteRecursively(target);
            Files.deleteIfExists(headFile(resources));
            copy(index, target);
            writeHead(headFile(resources), readHead(head).orElseThrow());
        }
        catch (IOException e) {
            throw new CodeGraphException("Cannot clone the CodeGraph baseline: " + e.getMessage(), e);
        }
        finally {
            lock.unlock();
        }
    }

    private void synchronize(Path source, TaskResources resources)
    {
        Path index = indexDirectory(resources);
        Path head = headFile(resources);
        Path scratch = scratchHome(resources);
        String current = revParseHead(source);
        Optional<String> last = readHead(head);
        Result result;
        if (!usable(index, head)) {
            discard(index, head);
            result = cli.run(source, index, scratch, buildTimeout, ImmutableList.of("init", source.toString(), "--yes"));
        }
        else if (rebuildNeeded(changedFiles(source, last.orElseThrow()), trackedFiles(source))) {
            result = cli.run(source, index, scratch, buildTimeout, ImmutableList.of("index", source.toString(), "--quiet"));
        }
        else {
            result = cli.run(source, index, scratch, buildTimeout, ImmutableList.of("sync", source.toString(), "--quiet"));
        }
        if (!result.successful()) {
            discard(index, head);
            throw unavailable("synchronization", result);
        }
        writeHead(head, current);
    }

    /** Files changed since the recorded commit, or -1 when git cannot compare, which forces a rebuild. */
    private long changedFiles(Path source, String last)
    {
        Result committed = git.run(source, "diff", "--name-only", last, "HEAD");
        Result pending = git.run(source, "status", "--porcelain");
        if (!committed.successful() || !pending.successful()) {
            return -1;
        }
        return lines(committed.output()) + lines(pending.output());
    }

    private long trackedFiles(Path source)
    {
        Result result = git.run(source, "ls-files");
        return result.successful() ? lines(result.output()) : 0;
    }

    private String revParseHead(Path source)
    {
        Result result = git.run(source, "rev-parse", "HEAD");
        if (!result.successful()) {
            throw new CodeGraphException("The worktree has no HEAD commit: " + summary(result.output()));
        }
        return result.output().strip();
    }

    private void addCheckout(Path repositoryDirectory, Path checkout, String commit)
    {
        Result result = git.run(repositoryDirectory, "worktree", "add", "--detach", checkout.toString(), commit);
        if (!result.successful()) {
            throw new CodeGraphException("Cannot check out " + commit + " for the CodeGraph baseline: " + summary(result.output()));
        }
    }

    private void removeCheckout(Path repositoryDirectory, Path checkout)
    {
        if (Files.exists(checkout)) {
            git.run(repositoryDirectory, "worktree", "remove", "--force", checkout.toString());
        }
        try {
            deleteRecursively(checkout);
        }
        catch (IOException ignored) {
            // A leftover checkout is only disk; the next refresh prunes and recreates it.
        }
    }

    /** Clears anything a crashed refresh left behind before a new one starts. */
    private void discardLeftovers(Path base, Path repositoryDirectory)
    {
        removeCheckout(repositoryDirectory, base.resolve(CHECKOUT_DIRECTORY));
        git.run(repositoryDirectory, "worktree", "prune");
        try {
            deleteRecursively(base.resolve(NEXT_DIRECTORY));
            deleteRecursively(base.resolve(OLD_DIRECTORY));
        }
        catch (IOException e) {
            throw new CodeGraphException("Cannot clear the CodeGraph baseline directory: " + e.getMessage(), e);
        }
    }

    /** Copies an index with an APFS clone when the volume supports it, otherwise byte for byte. */
    private void copy(Path from, Path to)
    {
        try {
            Result cloned = git.runCommand(baselineRoot, ImmutableList.of("cp", "-c", "-R", from.toString(), to.toString()), COPY_TIMEOUT, 64 * 1024);
            if (cloned.successful()) {
                return;
            }
            deleteRecursively(to);
            Result copied = git.runCommand(baselineRoot, ImmutableList.of("cp", "-R", from.toString(), to.toString()), COPY_TIMEOUT, 64 * 1024);
            if (!copied.successful()) {
                throw new CodeGraphException("Cannot copy the CodeGraph index: " + summary(copied.output()));
            }
        }
        catch (IOException e) {
            throw new CodeGraphException("Cannot copy the CodeGraph index: " + e.getMessage(), e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CodeGraphException("CodeGraph was interrupted", e);
        }
    }

    private static void swap(Path index, Path next, Path old)
    {
        try {
            if (Files.exists(index)) {
                Files.move(index, old, ATOMIC_MOVE);
            }
            Files.move(next, index, ATOMIC_MOVE);
            deleteRecursively(old);
        }
        catch (IOException e) {
            throw new CodeGraphException("Cannot replace the CodeGraph baseline: " + e.getMessage(), e);
        }
    }

    private String cap(String output)
    {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= resultLimitBytes) {
            return output;
        }
        return new String(bytes, 0, resultLimitBytes, StandardCharsets.UTF_8) + TRUNCATION_MARKER;
    }

    private ReentrantLock lockFor(TaskResources resources)
    {
        return taskLocks.computeIfAbsent(indexDirectory(resources), ignored -> new ReentrantLock());
    }

    private ReentrantLock lockFor(RepositoryIdentity repository)
    {
        return repositoryLocks.computeIfAbsent(repository, ignored -> new ReentrantLock());
    }

    private static boolean tryLock(ReentrantLock lock, Duration wait)
    {
        try {
            return lock.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CodeGraphException("CodeGraph was interrupted", e);
        }
    }

    private static boolean usable(Path index, Path head)
    {
        return Files.isRegularFile(index.resolve(DATABASE)) && readHead(head).isPresent();
    }

    private static Path indexDirectory(TaskResources resources)
    {
        return resources.runtimeDirectory().resolve(INDEX_DIRECTORY);
    }

    private static Path headFile(TaskResources resources)
    {
        return resources.runtimeDirectory().resolve(TASK_HEAD_FILE);
    }

    private static Path scratchHome(TaskResources resources)
    {
        return resources.runtimeDirectory().resolve(SCRATCH_HOME);
    }

    private static Optional<String> readHead(Path head)
    {
        try {
            return Files.isRegularFile(head) ? Optional.of(Files.readString(head).strip()).filter(value -> !value.isEmpty()) : Optional.empty();
        }
        catch (IOException e) {
            return Optional.empty();
        }
    }

    private static void writeHead(Path head, String commit)
    {
        try {
            Files.writeString(head, commit + "\n");
        }
        catch (IOException e) {
            throw new CodeGraphException("Cannot record the CodeGraph index commit: " + e.getMessage(), e);
        }
    }

    private static void createDirectories(Path directory)
    {
        try {
            Files.createDirectories(directory);
        }
        catch (IOException e) {
            throw new CodeGraphException("Cannot create " + directory + ": " + e.getMessage(), e);
        }
    }

    private static void discard(Path index, Path head)
    {
        try {
            deleteRecursively(index);
            Files.deleteIfExists(head);
        }
        catch (IOException e) {
            throw new CodeGraphException("Cannot discard the CodeGraph index: " + e.getMessage(), e);
        }
    }

    private static void deleteRecursively(Path directory)
            throws IOException
    {
        if (Files.exists(directory)) {
            MoreFiles.deleteRecursively(directory, RecursiveDeleteOption.ALLOW_INSECURE);
        }
    }

    private static CodeGraphException unavailable(String operation, Result result)
    {
        return new CodeGraphException("CodeGraph " + operation + " failed: " + summary(result.output()));
    }

    private static String summary(String output)
    {
        String last = output.lines().map(String::strip).filter(line -> !line.isEmpty()).reduce((first, second) -> second).orElse("no output");
        return last.length() > 300 ? last.substring(0, 300) : last;
    }

    private static long lines(String output)
    {
        return output.lines().filter(line -> !line.isBlank()).count();
    }
}
