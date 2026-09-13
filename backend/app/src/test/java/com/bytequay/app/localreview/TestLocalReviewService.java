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
package com.bytequay.app.localreview;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.web.GlobalExceptionHandler;
import com.bytequay.app.web.agent.LocalReviewController;
import com.bytequay.app.workspace.ManagedWorktreeService;
import com.bytequay.app.workspace.WorkspacePaths;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.localreview.LocalReviewMessage.Author;
import com.bytequay.core.localreview.LocalReviewStore;
import com.bytequay.core.localreview.LocalReviewThread;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestLocalReviewService
{
    private final LocalReviewStore store = mock(LocalReviewStore.class);
    private final AgentTaskService tasks = mock(AgentTaskService.class);
    private final AgentTurnService turns = mock(AgentTurnService.class);
    private final AgentTurnStore turnStore = mock(AgentTurnStore.class);
    private final QueuedInputStore queueStore = mock(QueuedInputStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final PullRequestStore pullRequests = mock(PullRequestStore.class);
    private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    private final Map<String, LocalReviewThread> stored = new HashMap<>();
    private final Map<String, AgentTurn> accepted = new HashMap<>();
    private final TaskId taskId = TaskId.random();
    private AgentTask task;
    private AgentTurn turn;
    private LocalReviewService service;

    @TempDir
    Path directory;

    @BeforeEach
    void setUp()
            throws IOException
    {
        WorkspacePaths paths = new WorkspacePaths(directory.toString());
        Files.createDirectories(paths.workspace("workspace"));
        TaskWorktree worktree = new TaskWorktree(taskId, "workspace", new RepositoryIdentity("owner", "repo"),
                paths.canonicalTaskWorktree("workspace", taskId.toString()), "bytequay/task/" + taskId, "a".repeat(40));
        task = AgentTask.created(taskId, true, "Task", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH);
        when(tasks.getInteractive("workspace", taskId)).thenReturn(task);
        when(tasks.get(taskId)).thenReturn(task);
        Workspace workspace = new Workspace("workspace", "Repository", 1, new RepoRef("owner", "repo"), Optional.empty(), Optional.empty(),
                1, new RepoRef("owner", "repo"), Workspace.Topology.DIRECT, "main", "https://github.com/owner/repo.git", Optional.empty(), Optional.empty(),
                paths.repository("workspace").toString(), Optional.empty(), Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH);
        when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace));
        PullRequest pr = new PullRequest(1, "owner/repo", 7, "Task", "owner", "https://github.com/owner/repo/pull/7", Instant.EPOCH, Instant.EPOCH,
                PullRequest.Origin.AUTHORED, ImmutableList.of(), ImmutableMap.of(), true, null, null, null, ImmutableList.of(), null, 1, 1, 0,
                null, "open", null, null, null, null, Instant.EPOCH, worktree.generatedBranch(), null, null, null, null);
        when(pullRequests.findByHeadRef("owner/repo", worktree.generatedBranch())).thenReturn(ImmutableList.of(pr));
        AgentTurnSettings settings = new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(10), ImmutableList.of());
        turn = AgentTurn.accepted(TurnId.random(), taskId, "turn", settings, Instant.EPOCH)
                .markDeliveryStarted(Instant.EPOCH).running(new ProviderTurnRef("provider"), Optional.empty());
        ManagedWorktreeService worktrees = new ManagedWorktreeService(paths, workspaces, new GitCommandRunner());
        service = new LocalReviewService(store, tasks, turns, turnStore, queueStore, workspaces, pullRequests, worktrees, clock);
        when(store.find(anyString())).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        when(store.listByTask(anyString())).thenAnswer(call -> stored.values().stream()
                .filter(value -> value.taskId().equals(call.getArgument(0))).collect(toImmutableList()));
        when(store.insert(any())).thenAnswer(call -> {
            LocalReviewThread value = call.getArgument(0);
            stored.put(value.id(), value);
            return value;
        });
        when(store.update(any())).thenAnswer(call -> {
            LocalReviewThread value = call.getArgument(0);
            assertThat(stored.get(value.id()).version()).isEqualTo(value.version());
            value = value.withVersion(value.version() + 1);
            stored.put(value.id(), value);
            return value;
        });
        when(turnStore.findByIdempotencyKey(eq(taskId), anyString())).thenAnswer(call -> Optional.ofNullable(accepted.get(call.getArgument(1))));
        when(turns.start(any())).thenAnswer(call -> {
            StartAgentTurnCommand command = call.getArgument(0);
            assertThat(stored).containsKey("thread");
            assertThat(command.developmentAutomation().orElseThrow().autoCommit()).isTrue();
            assertThat(command.developmentAutomation().orElseThrow().autoDraftPr()).isTrue();
            assertThat(command.developmentAutomation().orElseThrow().autoCiFix()).isFalse();
            assertThat(command.message()).contains("reply_local_review_comment", "never quote private feedback in commit messages", "newValue", "User comment:\nPlease fix this");
            accepted.put(command.idempotencyKey(), turn);
            return turn;
        });
    }

    @Test
    void storesBeforeDispatchAndReplaysWithAnUpdatedTaskVersion()
    {
        LocalReviewView first = create(0);
        LocalReviewView repeated = create(9);
        assertThat(first.messages()).hasSize(1);
        assertThat(repeated.messages()).hasSize(1);
        assertThat(first.messages().getFirst().turnId()).contains(turn.id().toString());
        verify(turns, times(1)).start(any());
        assertThat(service.list(taskId.toString(), "workspace").getFirst().messages()).hasSize(1);
    }

    @Test
    void messageIdsInDifferentThreadsCannotAliasTheAcceptedInput()
    {
        create(0);
        service.create(taskId.toString(), "workspace", new LocalReviewService.Create("second-thread", "message", "owner/repo", 7,
                "src/Example.java", "RIGHT", 10, 11, Optional.empty(), Optional.empty(), "newValue", "Please fix this", 1), "create");
        assertThat(accepted).hasSize(2);
        verify(turns, times(2)).start(any());
    }

    @Test
    void preservesUnsentMessageForExplicitRetryAndDoesNotRedispatchOnReads()
    {
        doThrow(new IllegalStateException("Provider unavailable")).when(turns).start(any());
        LocalReviewView first = create(0);
        assertThat(first.messages().getFirst().deliveryState()).contains("UNSENT");
        assertThat(first.messages().getFirst().deliveryError()).contains("Provider unavailable");
        service.list(taskId.toString(), "workspace");
        verify(turns, times(1)).start(any());
        service.retry(taskId.toString(), "workspace", "thread", "message", 4);
        verify(turns, times(2)).start(any());
    }

    @Test
    void uncertainAcceptanceReturnsTheAcceptedTurnWithoutSendingAgain()
    {
        doAnswer(call -> {
            StartAgentTurnCommand command = call.getArgument(0);
            accepted.put(command.idempotencyKey(), turn);
            throw new IllegalStateException("Response lost");
        }).when(turns).start(any());
        LocalReviewView saved = create(0);
        assertThat(saved.messages().getFirst().turnId()).contains(turn.id().toString());
        assertThat(saved.messages().getFirst().deliveryError()).isEmpty();
        service.retry(taskId.toString(), "workspace", "thread", "message", 1);
        verify(turns, times(1)).start(any());
    }

    @Test
    void rejectedQueueCanBeExplicitlyRetriedAfterTheActiveTurnEnds()
    {
        when(turnStore.findActive(taskId)).thenReturn(Optional.of(turn));
        when(turns.enqueue(any())).thenAnswer(call -> {
            when(turnStore.findActive(taskId)).thenReturn(Optional.empty());
            throw new AgentConflictException("Task has no active turn to queue behind");
        });
        LocalReviewView saved = create(0);
        assertThat(saved.messages().getFirst().deliveryState()).contains("UNSENT");
        service.retry(taskId.toString(), "workspace", "thread", "message", 1);
        verify(turns).enqueue(any());
        verify(turns).start(any());
    }

    @Test
    void queuedAssociationReadsTheDurableRowWithoutReopeningAnExpiredTextBlob()
    {
        when(turnStore.findActive(taskId)).thenReturn(Optional.of(turn));
        QueuedAgentInput queued = QueuedAgentInput.queued(QueuedInputId.random(), taskId, 1, "queue", new BlobRef("expired.txt", "text/plain", 10),
                "Please fix this", turn.settings().orElseThrow(), Instant.EPOCH).starting(turn.id(), EventId.random(), Instant.EPOCH).started(Instant.EPOCH);
        when(turns.enqueue(any())).thenAnswer(call -> {
            when(queueStore.findQueuedByIdempotencyKey(eq(taskId), anyString())).thenReturn(Optional.of(queued));
            return queued;
        });
        LocalReviewView saved = create(0);
        LocalReviewView.Message message = saved.messages().getFirst();
        assertThat(message.turnId()).contains(turn.id().toString());
        assertThat(message.queuedInputId()).contains(queued.id().toString());
        assertThat(message.messageEventId()).contains(queued.messageEventId().orElseThrow().toString());
        assertThat(service.listForAgent(taskId.toString()).getFirst().messages().getFirst()).isEqualTo(message);
        service.retry(taskId.toString(), "workspace", "thread", "message", 4);
        verify(turns, times(1)).enqueue(any());
        verify(turns, never()).start(any());
    }

    @Test
    void agentRepliesArePrivateIdempotentAndCannotCrossTasks()
    {
        create(0);
        when(turnStore.findActive(taskId)).thenReturn(Optional.of(turn));
        service.replyFromAgent(taskId.toString(), "thread", "Changed the implementation", "answer-1");
        LocalReviewView repeated = service.replyFromAgent(taskId.toString(), "thread", "Changed the implementation", "answer-1");
        assertThat(repeated.messages()).hasSize(2);
        assertThat(repeated.messages().getLast().author()).isEqualTo(Author.AGENT);
        assertThat(repeated.messages().getLast().turnId()).contains(turn.id().toString());
        assertThatThrownBy(() -> service.replyFromAgent(taskId.toString(), "thread", "Different answer", "answer-1")).isInstanceOf(AgentConflictException.class);
        TaskId otherId = TaskId.random();
        when(tasks.get(otherId)).thenReturn(task);
        assertThatThrownBy(() -> service.replyFromAgent(otherId.toString(), "thread", "Cross-task answer", "answer-2")).hasMessageContaining("another task");
        verify(turns, times(1)).start(any());
    }

    @Test
    void resolutionIsVersionedAndAnOldRetriedResolveCannotUndoAReopen()
    {
        LocalReviewView first = create(0);
        LocalReviewView resolved = service.resolve(taskId.toString(), "workspace", "thread", true, first.version(), "resolve");
        assertThat(resolved.resolved()).isTrue();
        LocalReviewView reopened = service.resolve(taskId.toString(), "workspace", "thread", false, resolved.version(), "reopen");
        assertThat(reopened.resolved()).isFalse();
        assertThat(service.resolve(taskId.toString(), "workspace", "thread", true, first.version(), "resolve").resolved()).isFalse();
        assertThatThrownBy(() -> service.resolve(taskId.toString(), "workspace", "thread", true, first.version(), "new-resolve")).isInstanceOf(AgentConflictException.class);
    }

    @Test
    void creationMustMatchTheTaskBranch()
    {
        when(pullRequests.findByHeadRef("owner/repo", task.taskWorktree().orElseThrow().generatedBranch())).thenReturn(ImmutableList.of());
        assertThatThrownBy(() -> create(0)).hasMessageContaining("does not track");
        verify(turns, never()).start(any());
        assertThat(stored).isEmpty();
    }

    @Test
    void rejectsUnsafeAnchorsBeforeSavingOrDispatching()
    {
        assertThatThrownBy(() -> service.create(taskId.toString(), "workspace", new LocalReviewService.Create("thread", "message", "owner/repo", 7,
                "src/../secret", "RIGHT", 10, 11, Optional.empty(), Optional.empty(), "newValue", "Please fix this", 0), "create"))
                .hasMessageContaining("traversal");
        assertThatThrownBy(() -> service.create(taskId.toString(), "workspace", new LocalReviewService.Create("thread", "message", "owner/repo", 7,
                "src/Example.java", "RIGHT", 10, 11, Optional.of("not a revision"), Optional.empty(), "newValue", "Please fix this", 0), "create"))
                .hasMessageContaining("revision");
        assertThat(stored).isEmpty();
        verify(turns, never()).start(any());
    }

    @Test
    void closedTaskThreadsRemainReadableAndResolvableButCannotSubmitReplies()
    {
        LocalReviewView saved = create(0);
        AgentTask closed = task.fail("Task closed", Instant.EPOCH, Instant.EPOCH.plusSeconds(100));
        when(tasks.getInteractive("workspace", taskId)).thenReturn(closed);
        when(tasks.get(taskId)).thenReturn(closed);
        assertThat(service.list(taskId.toString(), "workspace")).hasSize(1);
        assertThat(service.resolve(taskId.toString(), "workspace", "thread", true, saved.version(), "resolve").resolved()).isTrue();
        assertThatThrownBy(() -> service.reply(taskId.toString(), "workspace", "thread", "reply", "Please fix this", 1, "reply"))
                .isInstanceOf(AgentConflictException.class);
        assertThat(service.availableForAgent(taskId.toString())).isFalse();
        verify(turns, times(1)).start(any());
    }

    @Test
    void httpContractKeepsTheAnchorAndDeliveryErrorWithIdempotentMutations()
            throws Exception
    {
        MockMvc mvc = standaloneSetup(new LocalReviewController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        doThrow(new IllegalStateException("Provider unavailable")).when(turns).start(any());
        String route = "/agent/tasks/" + taskId + "/local-review-comments";
        String request = """
                {"id":"thread","messageId":"message","repo":"owner/repo","number":7,"path":"src/Example.java",
                 "side":"RIGHT","startLine":10,"line":11,"baseSha":null,"headSha":null,
                 "code":"newValue","body":"Please fix this","expectedTaskVersion":0}
                """;
        mvc.perform(post(route).param("workspaceId", "workspace").header("Idempotency-Key", "create")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.startLine").value(10)).andExpect(jsonPath("$.line").value(11))
                .andExpect(jsonPath("$.messages[0].deliveryState").value("UNSENT"))
                .andExpect(jsonPath("$.messages[0].deliveryError").value("Provider unavailable"));
        String response = mvc.perform(get(route).param("workspaceId", "workspace")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(response).get(0).path("messages").get(0).path("turnId").isNull()).isTrue();
        when(store.listByPullRequest("owner/repo", 7)).thenReturn(ImmutableList.of(stored.get("thread")));
        mvc.perform(get("/agent/local-review-comments").param("repo", "OWNER/REPO").param("number", "7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].taskId").value(taskId.toString()));
        mvc.perform(get("/agent/local-review-comments").param("repo", "owner/repo").param("number", "7").param("workspaceId", "other"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post(route + "/thread/messages/message/retry").param("workspaceId", "workspace")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedTaskVersion\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(1));
        long version = stored.get("thread").version();
        mvc.perform(patch(route + "/thread").param("workspaceId", "workspace").header("Idempotency-Key", "resolve")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resolved\":true,\"expectedVersion\":" + version + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resolved").value(true));
        mvc.perform(post(route).param("workspaceId", "workspace").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest());
        verify(turns, times(2)).start(any());
    }

    private LocalReviewView create(long expectedTaskVersion)
    {
        return service.create(taskId.toString(), "workspace", new LocalReviewService.Create("thread", "message", "owner/repo", 7,
                "src/Example.java", "RIGHT", 10, 11, Optional.of("a".repeat(40)), Optional.of("b".repeat(40)), "newValue", "Please fix this", expectedTaskVersion), "create");
    }
}
