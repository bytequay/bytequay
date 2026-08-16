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
package com.bytequay.agent.core.impl;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentWorkspaceService;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.WorkspaceLease;
import com.bytequay.agent.core.WorkspaceSnapshot;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static java.util.Objects.requireNonNull;

public final class GitAgentWorkspaceService
        implements AgentWorkspaceService
{
    private static final int MAX_GIT_OUTPUT_BYTES = 1024 * 1024;
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(10);

    private final String gitExecutable;
    private final Map<Path, TaskId> tasksByWorktree = new HashMap<>();
    private final Map<TaskId, Path> worktreesByTask = new HashMap<>();

    public GitAgentWorkspaceService()
    {
        this("git");
    }

    public GitAgentWorkspaceService(String gitExecutable)
    {
        checkNotBlank(gitExecutable, "gitExecutable");
        this.gitExecutable = gitExecutable;
    }

    @Override
    public WorkspaceLease acquire(TaskId taskId, Path requestedPath, RepositoryIdentity repository)
    {
        return lease(taskId, requestedPath, repository, true);
    }

    @Override
    public WorkspaceLease restore(TaskId taskId, Path canonicalWorktree, RepositoryIdentity repository)
    {
        return lease(taskId, canonicalWorktree, repository, false);
    }

    @Override
    public WorkspaceSnapshot inspect(Path worktree)
    {
        Path canonical = canonicalize(worktree);
        String topLevel = runGit(canonical, ImmutableList.of("rev-parse", "--show-toplevel"), false).output();
        Path reportedTopLevel = canonicalize(Path.of(topLevel));
        if (!reportedTopLevel.equals(canonical)) {
            throw new InvalidAgentRequestException("The selected path is not the Git worktree root");
        }

        String remote = runGit(canonical, ImmutableList.of("remote", "get-url", "origin"), false).output();
        RepositoryIdentity repository = parseRepository(remote);
        String head = runGit(canonical, ImmutableList.of("rev-parse", "HEAD"), false).output();
        GitResult branchResult = runGit(
                canonical,
                ImmutableList.of("symbolic-ref", "--quiet", "--short", "HEAD"),
                true);
        Optional<String> branch = branchResult.exitCode() == 0
                ? Optional.of(branchResult.output())
                : Optional.empty();
        boolean dirty = !runGit(
                canonical,
                ImmutableList.of("status", "--porcelain=v1", "--untracked-files=all"),
                false).output().isEmpty();
        return new WorkspaceSnapshot(canonical, repository, branch, head, dirty);
    }

    @Override
    public synchronized void release(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        Path worktree = worktreesByTask.remove(taskId);
        if (worktree != null) {
            tasksByWorktree.remove(worktree, taskId);
        }
    }

    private WorkspaceLease lease(
            TaskId taskId,
            Path requestedPath,
            RepositoryIdentity expectedRepository,
            boolean requireClean)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(expectedRepository, "expectedRepository is null");
        WorkspaceSnapshot snapshot = inspect(requestedPath);
        if (!snapshot.repository().toString().equalsIgnoreCase(expectedRepository.toString())) {
            throw new InvalidAgentRequestException(
                    "Worktree repository " + snapshot.repository() + " does not match " + expectedRepository);
        }
        if (requireClean && snapshot.dirty()) {
            throw new InvalidAgentRequestException("Worktree has tracked or untracked changes");
        }

        synchronized (this) {
            Path existingWorktree = worktreesByTask.get(taskId);
            if (existingWorktree != null && !existingWorktree.equals(snapshot.worktree())) {
                throw new AgentConflictException("Task already owns another worktree lease");
            }
            TaskId owner = tasksByWorktree.get(snapshot.worktree());
            if (owner != null && !owner.equals(taskId)) {
                throw new AgentConflictException("Worktree already has a live task lease");
            }
            tasksByWorktree.put(snapshot.worktree(), taskId);
            worktreesByTask.put(taskId, snapshot.worktree());
        }
        return new WorkspaceLease(taskId, snapshot.worktree(), () -> release(taskId));
    }

    private Path canonicalize(Path path)
    {
        requireNonNull(path, "path is null");
        try {
            Path absolute = path.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(absolute)) {
                throw new InvalidAgentRequestException("Worktree path must not be a symbolic link");
            }
            if (!Files.isDirectory(absolute)) {
                throw new InvalidAgentRequestException("Worktree path is not a directory");
            }
            return absolute.toRealPath();
        }
        catch (IOException e) {
            throw new InvalidAgentRequestException("Cannot resolve worktree path", e);
        }
    }

    private GitResult runGit(Path worktree, List<String> arguments, boolean allowExitOne)
    {
        List<String> command = ImmutableList.<String>builderWithExpectedSize(arguments.size() + 3)
                .add(gitExecutable)
                .add("-C")
                .add(worktree.toString())
                .addAll(arguments)
                .build();
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
        }
        catch (IOException e) {
            throw new InvalidAgentRequestException("Cannot start Git", e);
        }

        InputStream output = process.getInputStream();
        CompletableFuture<byte[]> outputFuture = new CompletableFuture<>();
        Thread.ofVirtual().name("bytequay-git-output").start(() -> readOutput(output, outputFuture));
        try {
            if (!process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new InvalidAgentRequestException("Git command timed out");
            }
            byte[] bytes = outputFuture.get(1, TimeUnit.SECONDS);
            if (bytes.length > MAX_GIT_OUTPUT_BYTES) {
                throw new InvalidAgentRequestException("Git output exceeded the configured limit");
            }
            int exitCode = process.exitValue();
            String text = new String(bytes, StandardCharsets.UTF_8).strip();
            if (exitCode != 0 && !(allowExitOne && exitCode == 1)) {
                throw new InvalidAgentRequestException("Git command failed: " + text);
            }
            return new GitResult(exitCode, text);
        }
        catch (ExecutionException e) {
            process.destroyForcibly();
            throw new InvalidAgentRequestException("Cannot read Git output", e.getCause());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new InvalidAgentRequestException("Interrupted while waiting for Git", e);
        }
        catch (java.util.concurrent.TimeoutException e) {
            process.destroyForcibly();
            throw new InvalidAgentRequestException("Timed out while draining Git output", e);
        }
    }

    private static void readOutput(InputStream output, CompletableFuture<byte[]> result)
    {
        try (output) {
            result.complete(output.readNBytes(MAX_GIT_OUTPUT_BYTES + 1));
        }
        catch (IOException e) {
            result.completeExceptionally(e);
        }
    }

    private static RepositoryIdentity parseRepository(String remote)
    {
        String path;
        if (remote.startsWith("git@github.com:")) {
            path = remote.substring("git@github.com:".length());
        }
        else {
            try {
                URI uri = new URI(remote);
                if (uri.getHost() == null || !uri.getHost().toLowerCase(Locale.ROOT).equals("github.com")) {
                    throw new InvalidAgentRequestException("The origin remote is not hosted on GitHub");
                }
                path = uri.getPath();
            }
            catch (URISyntaxException e) {
                throw new InvalidAgentRequestException("The origin remote URL is invalid", e);
            }
        }
        path = path.replaceFirst("^/+", "").replaceFirst("\\.git$", "");
        try {
            return RepositoryIdentity.valueOf(path);
        }
        catch (IllegalArgumentException e) {
            throw new InvalidAgentRequestException("The origin remote does not identify a repository", e);
        }
    }

    private record GitResult(int exitCode, String output) {}
}
