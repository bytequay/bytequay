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
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.spi.CodeQuery;
import com.bytequay.app.git.command.GitCommandRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.bytequay.app.git.GitTestSupport.configureUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestIdeCodeQueries
{
    @TempDir
    Path directory;
    private final GitCommandRunner git = new GitCommandRunner();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Future<?>> submitted = new ArrayList<>();
    private final CodeQuery query = new CodeQuery(CodeQuery.Operation.DOCUMENT_SYMBOLS, "main.go", Optional.empty(), OptionalInt.empty(), OptionalInt.empty(), 20);
    private final CodeQueryResult ready = new CodeQueryResult(Status.READY, ImmutableList.of(), false, "provider answered");
    private TaskWorktree worktree;
    private Path root;
    private IdeCodeQueries queries;

    @BeforeEach
    void setUp()
            throws IOException
    {
        TaskId id = TaskId.random();
        root = Files.createDirectory(directory.resolve(id.toString())).toRealPath();
        run("init", "--initial-branch=fixture");
        configureUser(root, "Fixture", "fixture@example.invalid");
        Files.writeString(root.resolve("main.go"), "package main\nfunc caller() {}\n");
        run("add", "main.go");
        run("-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false", "commit", "-m", "Fixture");
        worktree = new TaskWorktree(id, "workspace", RepositoryIdentity.valueOf("acme/project"), root, "bytequay/task/" + id, run("rev-parse", "HEAD").strip());
        WorktreeService ownership = mock(WorktreeService.class);
        when(ownership.ownedCheckout(worktree)).thenReturn(root);
        IdeRuntimeService runtime = mock(IdeRuntimeService.class);
        when(runtime.open(worktree)).thenReturn(new IdeConnection(id.toString(), "session", "127.0.0.1:5205", "fixture-token", root.toString(),
                directory.resolve("bundle").toString(), directory.resolve("cache").toString(), "a".repeat(40)));
        queries = new IdeCodeQueries(runtime, ownership, git, new ObjectMapper().registerModule(new Jdk8Module()));
    }

    @AfterEach
    void stop()
    {
        submitted.forEach(future -> future.cancel(true));
        executor.close();
    }

    @Test
    void answersOnlyTheOwningTaskAndReturnsRevisionBoundEvidence()
            throws Exception
    {
        Future<String> answer = executor.submit(() -> queries.query(worktree, query));
        submitted.add(answer);
        IdeCodeQueries.Snapshot snapshot = next(1);
        IdeCodeQueries.Request request = snapshot.requests().getFirst();
        assertThat(request.query()).isEqualTo(query);
        assertThat(request.workspaceId()).isEqualTo("workspace");
        assertThatThrownBy(() -> queries.complete(request.id(), "another-task", ready)).isInstanceOf(IllegalArgumentException.class);
        queries.complete(request.id(), worktree.taskId().toString(), ready);
        var result = new ObjectMapper().readTree(answer.get(5, TimeUnit.SECONDS));
        assertThat(result.path("taskId").asText()).isEqualTo(worktree.taskId().toString());
        assertThat(result.path("revision").asText()).isEqualTo(worktree.startCommit());
        assertThat(result.path("sourceFingerprint").asText()).hasSize(64);
        assertThat(result.path("result").path("status").asText()).isEqualTo("READY");
        assertThat(queries.poll(snapshot.version()).requests()).isEmpty();
        assertThat(run("status", "--porcelain")).isEmpty();
    }

    @Test
    void rejectsAnswersAfterAnExternalEditEvenWithoutACommit()
            throws Exception
    {
        Future<IdeCodeQueries.Answer> answer = submit();
        IdeCodeQueries.Request request = next(1).requests().getFirst();
        Files.writeString(root.resolve("main.go"), "package main\nfunc renamed() {}\n");
        queries.complete(request.id(), worktree.taskId().toString(), ready);
        assertThat(answer.get(5, TimeUnit.SECONDS).result().status()).isEqualTo(Status.STALE);
    }

    @Test
    void boundsConcurrentQueriesAndCancelsThemWithoutDurableQueueState()
            throws Exception
    {
        for (int index = 0; index < 4; index++) {
            submit();
        }
        IdeCodeQueries.Snapshot snapshot = next(4);
        assertThat(queries.execute(worktree, query).result().status()).isEqualTo(Status.UNAVAILABLE);
        submitted.forEach(future -> future.cancel(true));
        while (!snapshot.requests().isEmpty()) {
            snapshot = queries.poll(snapshot.version());
        }
        assertThat(snapshot.requests()).isEmpty();
    }

    @Test
    void rejectsSymlinkEscapesBothBeforeDispatchAndInReturnedLocations()
            throws Exception
    {
        Path outside = Files.writeString(directory.resolve("outside.go"), "private contents");
        Files.createSymbolicLink(root.resolve("link.go"), outside);
        CodeQuery escaped = new CodeQuery(query.operation(), "link.go", Optional.empty(), OptionalInt.empty(), OptionalInt.empty(), 20);
        assertThatThrownBy(() -> queries.execute(worktree, escaped)).isInstanceOf(IllegalArgumentException.class);
        Future<IdeCodeQueries.Answer> answer = submit();
        IdeCodeQueries.Request request = next(1).requests().getFirst();
        var item = new CodeQueryResult.Item("link.go", 1, 1, 1, 2, Optional.of("outside"), OptionalInt.empty(), Optional.empty(), OptionalInt.empty(), ImmutableList.of());
        queries.complete(request.id(), worktree.taskId().toString(), new CodeQueryResult(Status.READY, ImmutableList.of(item), false, ""));
        assertThat(answer.get(5, TimeUnit.SECONDS).result().status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void acceptsTheRendererWireContractAndRejectsArbitraryOperations()
            throws Exception
    {
        ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module());
        CodeQueryResult result = mapper.readValue("""
                {"status":"READY","items":[{"path":"main.go","line":2,"column":6,"endLine":2,"endColumn":12,"name":"caller","calls":[]}],
                 "truncated":false,"message":"Imported project"}
                """, CodeQueryResult.class);
        assertThat(result.items().getFirst().name()).contains("caller");
        assertThatThrownBy(() -> mapper.readValue("{\"operation\":\"executeCommand\",\"path\":\"main.go\",\"limit\":20}", CodeQuery.class))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> new CodeQuery(query.operation(), ".git/config", Optional.empty(), OptionalInt.empty(), OptionalInt.empty(), 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Future<IdeCodeQueries.Answer> submit()
    {
        Future<IdeCodeQueries.Answer> future = executor.submit(() -> queries.execute(worktree, query));
        submitted.add(future);
        return future;
    }

    private IdeCodeQueries.Snapshot next(int count)
            throws InterruptedException
    {
        IdeCodeQueries.Snapshot snapshot = queries.poll(-1);
        while (snapshot.requests().size() < count) {
            snapshot = queries.poll(snapshot.version());
        }
        return snapshot;
    }

    private String run(String... args)
    {
        GitCommandRunner.Result result = git.run(root, args);
        assertThat(result.successful()).as(result.output()).isTrue();
        return result.output();
    }
}
