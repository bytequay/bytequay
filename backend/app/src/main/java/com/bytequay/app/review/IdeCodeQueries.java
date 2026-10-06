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

import com.bytequay.agent.core.CodeQueryResult;
import com.bytequay.agent.core.CodeQueryResult.Status;
import com.bytequay.agent.core.CodeQueryService;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.spi.CodeQuery;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** In-memory requests to the desktop's one shared IDE per worktree. No second index or saved query queue. */
@Component
public class IdeCodeQueries
        implements CodeQueryService
{
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private final IdeRuntimeService runtime;
    private final WorktreeService worktrees;
    private final GitCommandRunner git;
    private final ObjectMapper mapper;
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private long version;

    public IdeCodeQueries(IdeRuntimeService runtime, WorktreeService worktrees, GitCommandRunner git, ObjectMapper mapper)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.git = requireNonNull(git, "git is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Override
    public String query(TaskWorktree worktree, CodeQuery query)
    {
        try {
            return mapper.writeValueAsString(execute(worktree, query));
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode code query evidence", failure);
        }
    }

    public Answer execute(TaskWorktree worktree, CodeQuery query)
    {
        Path root = worktrees.ownedCheckout(worktree);
        validateFile(root, query.path());
        IdeConnection connection = runtime.open(worktree);
        Source source = source(root);
        Request request = new Request(UUID.randomUUID().toString(), worktree.workspaceId(), worktree.taskId().toString(),
                System.currentTimeMillis() + TIMEOUT.toMillis(), query, connection);
        Pending operation = new Pending(request, new CompletableFuture<>());
        synchronized (this) {
            if (pending.size() >= 32 || pending.values().stream().filter(item -> item.request().taskId().equals(request.taskId())).count() >= 4) {
                return answer(worktree, source, CodeQueryResult.unavailable(Status.UNAVAILABLE, "Too many active code queries; retry after the current requests finish"));
            }
            pending.put(request.id(), operation);
            changed();
        }
        CodeQueryResult result;
        try {
            result = operation.reply().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            worktrees.ownedCheckout(worktree);
            if (!source.equals(source(root))) {
                result = CodeQueryResult.unavailable(Status.STALE, "The working copy changed during the query. Read the current source and retry.");
            }
            // Providers may return a location through a symlink; never send an agent outside its task via that location.
            if (result.status() == Status.READY) {
                for (CodeQueryResult.Item item : result.items()) {
                    validateFile(root, item.path());
                    item.calls().forEach(location -> validateFile(root, location.path()));
                }
            }
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            result = CodeQueryResult.unavailable(Status.CANCELLED, "Code query cancelled");
        }
        catch (TimeoutException failure) {
            result = CodeQueryResult.unavailable(Status.UPDATING, "The IDE did not answer before the deadline. Check project import and toolchain status, then retry.");
        }
        catch (ExecutionException | RuntimeException failure) {
            result = CodeQueryResult.unavailable(Status.UNAVAILABLE, "The IDE query could not be validated: " + bounded(failure.getMessage()));
        }
        finally {
            synchronized (this) {
                pending.remove(request.id());
                changed();
            }
        }
        return answer(worktree, source, result);
    }

    /** A versioned snapshot makes reconnect and cancellation explicit without replaying already completed work. */
    public synchronized Snapshot poll(long after)
            throws InterruptedException
    {
        if (after == version) {
            wait(25_000);
        }
        return new Snapshot(version, pending.values().stream().map(Pending::request).collect(ImmutableList.toImmutableList()));
    }

    public synchronized void complete(String id, String taskId, CodeQueryResult result)
    {
        Pending operation = pending.get(id);
        if (operation == null) {
            return;
        }
        checkArgument(operation.request().taskId().equals(taskId), "code query belongs to another task");
        checkArgument(result.items().size() <= operation.request().query().limit(), "code query exceeded its limit");
        operation.reply().complete(result);
    }

    private void changed()
    {
        version++;
        notifyAll();
    }

    private Source source(Path root)
    {
        Result head = git.run(root, "rev-parse", "--verify", "HEAD^{commit}");
        Result status = git.runStdout(root, "--no-optional-locks", "status", "--porcelain=v1", "-z", "--untracked-files=all");
        checkState(head.successful() && !head.truncated() && status.successful() && !status.truncated(), "Cannot read the working copy identity");
        String revision = head.output().strip();
        var paths = ImmutableSortedSet.copyOf(ReviewCodeService.statusCodes(ReviewCodeService.entries(status.output(), false)).keySet());
        return new Source(revision, ReviewCodeService.fingerprint(root, revision, paths));
    }

    private static void validateFile(Path root, String relative)
    {
        CodeQuery.checkPath(relative);
        try {
            Path file = root.resolve(relative).toRealPath();
            checkArgument(file.startsWith(root) && Files.isRegularFile(file) && Files.size(file) <= 2 * 1024 * 1024, "Source is outside the worktree or exceeds 2 MiB");
            CodeQuery.checkPath(root.relativize(file).toString());
        }
        catch (IOException failure) {
            throw new IllegalArgumentException("The source file is unavailable", failure);
        }
    }

    private static Answer answer(TaskWorktree worktree, Source source, CodeQueryResult result)
    {
        return new Answer(worktree.taskId().toString(), source.revision(), source.fingerprint(), result);
    }

    private static String bounded(String text)
    {
        return text == null ? "unavailable" : text.substring(0, Math.min(2000, text.length()));
    }

    public record Request(String id, String workspaceId, String taskId, long expiresAt, CodeQuery query, IdeConnection connection) {}

    public record Snapshot(long version, List<Request> requests)
    {
        public Snapshot
        {
            requests = ImmutableList.copyOf(requests);
        }
    }

    public record Answer(String taskId, String revision, String sourceFingerprint, CodeQueryResult result) {}

    private record Source(String revision, String fingerprint) {}

    private record Pending(Request request, CompletableFuture<CodeQueryResult> reply) {}
}
