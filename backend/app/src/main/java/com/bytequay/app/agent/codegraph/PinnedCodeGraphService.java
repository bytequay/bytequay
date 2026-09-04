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
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * {@link CodeGraphService} over the pinned CodeGraph 1.6.0 bundle.
 *
 * <p>The task index lives at {@code <task-runtime>/codegraph}; a sidecar records the commit it was
 * last brought up to date with. Every query first synchronizes the index, so the agent never
 * refreshes anything: an unchanged sync costs well under a second, and the only decision is whether
 * an unscoped sync or a full rebuild is cheaper, which the changed-file count answers.
 */
public final class PinnedCodeGraphService
        implements CodeGraphService
{
    static final String TRUNCATION_MARKER = "\n[truncated by ByteQuay: the graph result exceeded the tool limit]";
    static final int DEFAULT_RESULT_LIMIT_BYTES = 16 * 1024;
    static final int MAXIMUM_QUERY_LENGTH = 2000;
    // Measured on 2026-09-04: above roughly five percent of the tracked files an unscoped sync
    // costs more than a full rebuild (Trino: 924 changed files synced in 47 s, rebuilt in 42 s).
    static final double REBUILD_FRACTION = 0.05;
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration QUERY_TIMEOUT = Duration.ofMinutes(1);
    private static final String INDEX_DIRECTORY = "codegraph";
    private static final String HEAD_FILE = "codegraph.head";
    private static final String SCRATCH_HOME = "codegraph-home";
    private static final String DATABASE = "codegraph.db";

    private final CodeGraphCli cli;
    private final GitCommandRunner git;
    private final int resultLimitBytes;
    private final Duration buildTimeout;
    private final Duration queryTimeout;
    // ponytail: one lock per task index, never evicted; tasks are few and an entry is tiny.
    private final ConcurrentMap<Path, ReentrantLock> locks = new ConcurrentHashMap<>();

    public PinnedCodeGraphService(CodeGraphCli cli, GitCommandRunner git)
    {
        this(cli, git, DEFAULT_RESULT_LIMIT_BYTES, BUILD_TIMEOUT, QUERY_TIMEOUT);
    }

    PinnedCodeGraphService(CodeGraphCli cli, GitCommandRunner git, int resultLimitBytes, Duration buildTimeout, Duration queryTimeout)
    {
        this.cli = requireNonNull(cli, "cli is null");
        this.git = requireNonNull(git, "git is null");
        checkArgument(resultLimitBytes > 0, "resultLimitBytes is not positive");
        this.resultLimitBytes = resultLimitBytes;
        this.buildTimeout = requireNonNull(buildTimeout, "buildTimeout is null");
        this.queryTimeout = requireNonNull(queryTimeout, "queryTimeout is null");
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
        lock.lock();
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

    static boolean rebuildNeeded(long changedFiles, long trackedFiles)
    {
        return changedFiles < 0 || changedFiles > trackedFiles * REBUILD_FRACTION;
    }

    private void synchronize(Path source, TaskResources resources)
    {
        Path index = indexDirectory(resources);
        Path head = headFile(resources);
        Path scratch = scratchHome(resources);
        String current = revParseHead(source);
        Optional<String> last = readHead(head);
        Result result;
        if (!Files.isRegularFile(index.resolve(DATABASE)) || last.isEmpty()) {
            discard(index, head);
            result = cli.run(source, index, scratch, buildTimeout, ImmutableList.of("init", source.toString(), "--yes"));
        }
        else if (rebuildNeeded(changedFiles(source, last.get()), trackedFiles(source))) {
            result = cli.run(source, index, scratch, buildTimeout, ImmutableList.of("index", source.toString(), "--quiet"));
        }
        else {
            result = cli.run(source, index, scratch, buildTimeout, ImmutableList.of("sync", source.toString(), "--quiet"));
        }
        if (!result.successful()) {
            discard(index, head);
            throw unavailable("synchronization", result);
        }
        try {
            Files.writeString(head, current + "\n");
        }
        catch (IOException e) {
            discard(index, head);
            throw new CodeGraphException("Cannot record the CodeGraph index commit: " + e.getMessage(), e);
        }
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
        return locks.computeIfAbsent(indexDirectory(resources), ignored -> new ReentrantLock());
    }

    private static Path indexDirectory(TaskResources resources)
    {
        return resources.runtimeDirectory().resolve(INDEX_DIRECTORY);
    }

    private static Path headFile(TaskResources resources)
    {
        return resources.runtimeDirectory().resolve(HEAD_FILE);
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
