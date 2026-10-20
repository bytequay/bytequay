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
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.QueueAgentInputCommand;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.DevelopmentLearningCommit;
import com.bytequay.agent.spi.DevelopmentLearningSummary;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.app.agent.TaskPullRequestOwnership;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.review.AgentReviewService;
import com.bytequay.app.review.IdeRuntimeService;
import com.bytequay.app.web.GlobalExceptionHandler;
import com.bytequay.app.web.agent.LocalReviewController;
import com.bytequay.app.web.agent.TaskLearningController;
import com.bytequay.app.workspace.ManagedWorktreeService;
import com.bytequay.app.workspace.WorkspacePaths;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.localreview.LocalReviewLearningAnchor;
import com.bytequay.core.localreview.LocalReviewMessage.Author;
import com.bytequay.core.localreview.LocalReviewStore;
import com.bytequay.core.localreview.LocalReviewThread;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import java.util.OptionalInt;

import static com.bytequay.app.review.ReviewTestData.openReview;
import static com.bytequay.app.review.ReviewTestData.pullRequestReview;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
    private final AgentReviewService reviewTasks = mock(AgentReviewService.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
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
                "open", null, null, null, null, Instant.EPOCH, worktree.generatedBranch(), null, null, null, null);
        when(pullRequests.findByHeadRef("owner/repo", worktree.generatedBranch())).thenReturn(ImmutableList.of(pr));
        AgentTurnSettings settings = new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(10),
                ImmutableList.of(), Optional.empty(), Optional.empty());
        turn = AgentTurn.accepted(TurnId.random(), taskId, "turn", settings, Instant.EPOCH)
                .markDeliveryStarted(Instant.EPOCH).running(new ProviderTurnRef("provider"), Optional.empty());
        ManagedWorktreeService worktrees = new ManagedWorktreeService(paths, workspaces, new GitCommandRunner(), mock(IdeRuntimeService.class));
        service = new LocalReviewService(store, tasks, turns, turnStore, queueStore, reviews, reviewTasks,
                new TaskPullRequestOwnership(workspaces, mock(PullRequestTaskStore.class), pullRequests, worktrees), clock);
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
        when(turns.findTurnByIdempotencyKey(eq(taskId), anyString())).thenAnswer(call -> Optional.ofNullable(accepted.get(call.getArgument(1))));
        when(turns.start(any())).thenAnswer(call -> {
            StartAgentTurnCommand command = call.getArgument(0);
            assertThat(stored).containsKey("thread");
            assertThat(command.developmentAutomation()).isEmpty();
            assertThat(command.message()).contains("list_local_review_comments with commentId=", "reply_local_review_comment",
                    "never quote private feedback in commit messages", "User comment:\nPlease fix this", "locate its current counterpart first")
                    .doesNotContain("newValue", "Selected code (reference only)");
            accepted.put(command.idempotencyKey(), turn);
            return turn;
        });
    }

    @Test
    void leavesLongSelectedCodeInTheSavedThreadAndKeepsTheCompleteQuestion()
    {
        String code = "newValue".repeat(7500);
        String question = ("Please fix this " + "and keep this question intact. ".repeat(400)).strip();
        doAnswer(call -> {
            StartAgentTurnCommand command = call.getArgument(0);
            assertThat(command.message()).contains("list_local_review_comments with commentId=\"thread\"", "contextOffset")
                    .doesNotContain("newValue").endsWith("User comment:\n" + question);
            assertThat(command.message().length() - question.length()).isLessThan(1200);
            assertThat(command.developmentAutomation()).isEmpty();
            return turn;
        }).when(turns).start(any());
        LocalReviewView saved = service.create(taskId.toString(), "workspace", new LocalReviewService.Create("thread", "message", "owner/repo", 7,
                "src/Example.java", "RIGHT", 10, 11, Optional.of("a".repeat(40)), Optional.of("b".repeat(40)), code, question, 0), "create");
        assertThat(saved.code()).isEqualTo(code);
        assertThat(saved.messages().getFirst().body()).isEqualTo(question);
        verify(turns).start(any());
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
    void inlineFeedbackKeepsDevelopmentScopeAndTaskVersion()
    {
        doAnswer(call -> {
            StartAgentTurnCommand command = call.getArgument(0);
            assertThat(command.expectedTaskVersion()).hasValue(0);
            assertThat(command.developmentAutomation()).isEmpty();
            accepted.put(command.idempotencyKey(), turn);
            return turn;
        }).when(turns).start(any());

        create(0);

        verify(turns).start(any());
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
        QueuedAgentInput starting = QueuedAgentInput.queued(QueuedInputId.random(), taskId, 1, "queue", new BlobRef("expired.txt", "text/plain", 10),
                "Please fix this", turn.settings().orElseThrow(), Instant.EPOCH).starting(turn.id(), EventId.random(), Instant.EPOCH);
        QueuedAgentInput queued = starting.started(Instant.EPOCH);
        when(turnStore.find(turn.id())).thenReturn(Optional.of(turn));
        when(turns.enqueue(any())).thenAnswer(call -> {
            when(queueStore.findQueuedByIdempotencyKey(eq(taskId), anyString())).thenReturn(Optional.of(queued));
            when(turns.findQueuedByIdempotencyKey(eq(taskId), anyString())).thenReturn(Optional.of(queued));
            return queued;
        });
        LocalReviewView saved = create(0);
        LocalReviewView.Message message = saved.messages().getFirst();
        assertThat(message.turnId()).contains(turn.id().toString());
        assertThat(message.queuedInputId()).contains(queued.id().toString());
        assertThat(message.messageEventId()).contains(queued.messageEventId().orElseThrow().toString());
        assertThat(message.deliveryState()).contains("RUNNING");
        assertThat(service.listForAgent(taskId.toString()).getFirst().messages().getFirst()).isEqualTo(message);
        service.retry(taskId.toString(), "workspace", "thread", "message", 4);
        verify(turns, times(1)).enqueue(any());
        verify(turns, never()).start(any());
        for (TurnState state : ImmutableList.of(TurnState.COMPLETED, TurnState.FAILED, TurnState.CANCELLED, TurnState.INTERRUPTED)) {
            when(turnStore.find(turn.id())).thenReturn(Optional.of(turn.finish(state, AgentMetrics.empty(),
                    state == TurnState.COMPLETED ? Optional.empty() : Optional.of("Turn ended"), Instant.EPOCH.plusSeconds(1))));
            assertThat(service.list(taskId.toString(), "workspace").getFirst().messages().getFirst().deliveryState()).contains(state.name());
        }
        when(queueStore.findQueuedByIdempotencyKey(eq(taskId), anyString())).thenReturn(Optional.of(starting.paused("Try again", Instant.EPOCH.plusSeconds(2))));
        assertThat(service.list(taskId.toString(), "workspace").getFirst().messages().getFirst().deliveryState()).contains("PAUSED");
    }

    @Test
    void anInterruptedReplyShowsWhyAndIsSentAgainAsANewInput()
    {
        create(0);
        String firstKey = stored.get("thread").messages().getFirst().inputKey().orElseThrow();
        accepted.put(firstKey, turn.finish(TurnState.INTERRUPTED, AgentMetrics.empty(), Optional.of("Backend restarted"), Instant.EPOCH));

        LocalReviewView.Message shown = service.list(taskId.toString(), "workspace").getFirst().messages().getFirst();
        assertThat(shown.deliveryState()).contains("INTERRUPTED");
        assertThat(shown.deliveryError()).contains("Backend restarted");

        LocalReviewView.Message retried = service.retry(taskId.toString(), "workspace", "thread", "message", 1).messages().getFirst();

        verify(turns, times(2)).start(any());
        assertThat(stored.get("thread").messages().getFirst().inputKey().orElseThrow()).isNotEqualTo(firstKey).startsWith("local-review:");
        assertThat(retried.deliveryState()).contains("RUNNING");
        assertThat(retried.deliveryError()).isEmpty();
    }

    @Test
    void aPausedQueuedReplyLeavesTheQueueBeforeItIsSentAgain()
    {
        when(turnStore.findActive(taskId)).thenReturn(Optional.of(turn));
        QueuedAgentInput paused = QueuedAgentInput.queued(QueuedInputId.random(), taskId, 1, "queued-key", new BlobRef("reply.txt", "text/plain", 10),
                "Please fix this", turn.settings().orElseThrow(), Instant.EPOCH).paused("Waiting for the agent's development readiness report", Instant.EPOCH);
        when(turns.enqueue(any())).thenAnswer(call -> {
            QueueAgentInputCommand command = call.getArgument(0);
            when(queueStore.findQueuedByIdempotencyKey(taskId, command.idempotencyKey())).thenReturn(Optional.of(paused));
            return paused;
        });
        LocalReviewView.Message saved = create(0).messages().getFirst();
        assertThat(saved.deliveryState()).contains("PAUSED");
        assertThat(saved.deliveryError()).contains("Waiting for the agent's development readiness report");

        when(turnStore.findActive(taskId)).thenReturn(Optional.empty());
        service.retry(taskId.toString(), "workspace", "thread", "message", 1);

        InOrder order = inOrder(turns);
        order.verify(turns).removeQueued(taskId, paused.id());
        order.verify(turns).start(any());
    }

    @Test
    void aQueuedReplyShowsTheStateOfTheTurnItBecame()
    {
        when(turnStore.findActive(taskId)).thenReturn(Optional.of(turn));
        AgentTurn completed = turn.finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), Instant.EPOCH);
        QueuedAgentInput started = QueuedAgentInput.queued(QueuedInputId.random(), taskId, 1, "queued-key", new BlobRef("reply.txt", "text/plain", 10),
                "Please fix this", turn.settings().orElseThrow(), Instant.EPOCH).starting(completed.id(), EventId.random(), Instant.EPOCH).started(Instant.EPOCH);
        when(turnStore.find(completed.id())).thenReturn(Optional.of(completed));
        when(turns.enqueue(any())).thenAnswer(call -> {
            when(queueStore.findQueuedByIdempotencyKey(eq(taskId), anyString())).thenReturn(Optional.of(started));
            return started;
        });

        LocalReviewView.Message saved = create(0).messages().getFirst();

        // The turn runs under the queue's own key, so only the row leads to it.
        assertThat(saved.deliveryState()).contains("COMPLETED");
        assertThat(saved.turnId()).contains(completed.id().toString());
    }

    @Test
    void theAgentReopensOnlyAThreadAnAgentResolved()
    {
        create(0);
        service.resolve(taskId.toString(), "workspace", "thread", true, stored.get("thread").version(), "user-resolve");
        assertThatThrownBy(() -> service.resolveFromAgent(taskId.toString(), "thread", false, "agent-reopen"))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("only the user reopens it");
        // Resolving what the user already resolved does not make it the agent's.
        service.resolveFromAgent(taskId.toString(), "thread", true, "agent-resolve");
        assertThatThrownBy(() -> service.resolveFromAgent(taskId.toString(), "thread", false, "agent-reopen-2")).isInstanceOf(AgentConflictException.class);

        service.resolve(taskId.toString(), "workspace", "thread", false, stored.get("thread").version(), "user-reopen");
        service.resolveFromAgent(taskId.toString(), "thread", true, "agent-resolve-2");
        assertThat(service.resolveFromAgent(taskId.toString(), "thread", false, "agent-reopen-3").resolved()).isFalse();
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
    void reviewOwnedTaskAcceptsPrivateCommentsWithoutDevelopmentDelivery()
    {
        task = AgentTask.created(taskId, false, "Review", TitleSource.USER, AgentLaneId.CODEX_CLI, task.taskWorktree().orElseThrow(),
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH);
        when(tasks.get(taskId)).thenReturn(task);
        AgentReview review = pullRequestReview("review", "workspace", "owner/repo", 7, taskId.toString(), Instant.EPOCH);
        when(reviews.findReviewByTask(taskId.toString())).thenReturn(Optional.of(review));
        doAnswer(call -> {
            String prompt = call.getArgument(2);
            assertThat(prompt).contains("reply_local_review_comment", "Answer within this private review").doesNotContain("finish and commit the change");
            accepted.put(call.getArgument(3), turn);
            return null;
        }).when(reviewTasks).sendMessage(eq("review"), eq(0L), anyString(), anyString(), eq(false));

        LocalReviewView comment = create(0);
        assertThat(comment.taskVersion()).isEqualTo(task.version());
        assertThat(service.availableForAgent(taskId.toString())).isTrue();
        assertThat(service.replyFromAgent(taskId.toString(), comment.id(), "The value is nullable", "answer").messages()).hasSize(2);
        assertThat(service.list(taskId.toString(), "workspace")).hasSize(1);
        assertThatThrownBy(() -> service.list(taskId.toString(), "another-workspace")).isInstanceOf(AgentTaskNotFoundException.class);
        assertThatThrownBy(() -> service.create(taskId.toString(), "workspace", new LocalReviewService.Create("other", "message", "owner/repo", 8,
                "src/Example.java", "RIGHT", 10, 11, Optional.empty(), Optional.empty(), "newValue", "Please explain", 0), "other"))
                .hasMessageContaining("does not belong");
        when(reviews.findReviewByTask(taskId.toString())).thenReturn(Optional.of(review.closed(Instant.EPOCH)));
        assertThat(service.availableForAgent(taskId.toString())).isFalse();
        assertThat(service.list(taskId.toString(), "workspace")).hasSize(1);
        assertThatThrownBy(() -> service.reply(taskId.toString(), "workspace", comment.id(), "later", "Explain", 0, "later"))
                .hasMessageContaining("open review");
        when(reviews.findReviewByTask(taskId.toString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(taskId.toString(), "workspace")).isInstanceOf(AgentTaskNotFoundException.class);
    }

    @Test
    void developmentAgentReadsItsOwnReviewAndTheOpenReviewsOfItsPullRequest()
    {
        // Newest update first, as the store lists them.
        AgentReview own = review("own", OptionalInt.empty(), Optional.of(taskId.toString())).closed(Instant.EPOCH);
        when(reviews.listReviews("workspace")).thenReturn(ImmutableList.of(
                own,
                review("earlier-own", OptionalInt.empty(), Optional.of(taskId.toString())),
                review("pull-request", OptionalInt.of(7), Optional.empty()),
                review("another-task", OptionalInt.of(7), Optional.of(TaskId.random().toString())),
                review("ended", OptionalInt.of(7), Optional.empty()).closed(Instant.EPOCH),
                review("another-pull-request", OptionalInt.of(8), Optional.empty())));
        when(reviews.listFindings(anyString())).thenAnswer(call -> ImmutableList.of(finding("finding-" + call.getArgument(0), call.getArgument(0))));
        ReviewMessage question = new ReviewMessage("question", "pull-request", Optional.of("finding-pull-request"), Optional.empty(), ReviewMessage.Author.USER,
                "Is this real?", 1, "run", Optional.empty(), Optional.empty(), 0, Instant.EPOCH);
        ReviewMessage summaryQuestion = new ReviewMessage("summary", "pull-request", Optional.empty(), Optional.empty(), ReviewMessage.Author.USER,
                "What changed?", 1, "run", Optional.empty(), Optional.empty(), 0, Instant.EPOCH);
        when(reviews.listMessages("pull-request")).thenReturn(ImmutableList.of(question, summaryQuestion));

        assertThat(service.readsFindings(taskId.toString())).isTrue();
        // Its own newest review stays readable after it ends, as the task's panel still shows it; the pull request's other reviews only while open.
        assertThat(service.findingsForAgent(taskId.toString())).extracting(value -> value.review().id()).containsExactly("own", "pull-request", "another-task");
        assertThat(service.findingsForAgent(taskId.toString()).get(1).messages()).containsExactly(question);

        ReviewMessage saved = new ReviewMessage("reply", "another-task", Optional.of("finding-another-task"), Optional.empty(), ReviewMessage.Author.AGENT,
                "Fixed in abc1234", 1, "run", Optional.empty(), Optional.empty(), 0, Instant.EPOCH, Optional.of(taskId.toString()));
        when(reviewTasks.replyFromDeveloper(eq("another-task"), eq("finding-another-task"), eq(taskId.toString()), anyString(), eq("Fixed in abc1234"))).thenReturn(saved);
        assertThat(service.replyToFindingFromAgent(taskId.toString(), "finding-another-task", "  Fixed in abc1234  ", "key-1")).contains(saved);
        assertThat(service.replyToFindingFromAgent(taskId.toString(), "finding-another-task", "Fixed in abc1234", "key-1")).contains(saved);
        service.replyToFindingFromAgent(taskId.toString(), "finding-another-task", "Fixed in abc1234", "key-2");
        ArgumentCaptor<String> replyIds = ArgumentCaptor.forClass(String.class);
        verify(reviewTasks, times(3)).replyFromDeveloper(eq("another-task"), eq("finding-another-task"), eq(taskId.toString()), replyIds.capture(), eq("Fixed in abc1234"));
        // A retried key names the same reply, so the review service returns the saved one instead of adding another.
        assertThat(replyIds.getAllValues().get(0)).isEqualTo(replyIds.getAllValues().get(1)).isNotEqualTo(replyIds.getAllValues().get(2));

        // Other ids fall through to the task's own comments.
        assertThat(service.replyToFindingFromAgent(taskId.toString(), "finding-another-pull-request", "Fixed", "key-3")).isEmpty();
        assertThat(service.replyToFindingFromAgent(taskId.toString(), "finding-ended", "Fixed", "key-4")).isEmpty();
        assertThatThrownBy(() -> service.replyToFindingFromAgent(taskId.toString(), "finding-own", "Fixed", " ")).hasMessageContaining("operation key");
        verify(reviewTasks, times(3)).replyFromDeveloper(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void developmentAgentResolvesOnlyAFindingItAnswered()
    {
        when(reviews.listReviews("workspace")).thenReturn(ImmutableList.of(
                review("own", OptionalInt.empty(), Optional.of(taskId.toString())), review("another-pull-request", OptionalInt.of(8), Optional.empty())));
        when(reviews.listFindings(anyString())).thenAnswer(call -> ImmutableList.of(finding("finding-" + call.getArgument(0), call.getArgument(0))));
        ReviewFinding open = finding("finding-own", "own");
        ReviewFinding resolved = open.resolvedByDeveloper(taskId.toString(), Instant.EPOCH);
        when(reviewTasks.resolveFromDeveloper("own", "finding-own", taskId.toString(), true)).thenReturn(resolved);
        when(reviewTasks.resolveFromDeveloper("own", "finding-own", taskId.toString(), false)).thenReturn(open);

        // The card's reason is the agent's own reply, so resolving without one is refused; another task's reply is not its own.
        assertThatThrownBy(() -> service.resolveFindingFromAgent(taskId.toString(), "finding-own", true)).hasMessageContaining("Reply on the finding");
        when(reviews.listMessages("own")).thenReturn(ImmutableList.of(new ReviewMessage("earlier", "own", Optional.of("finding-own"), Optional.empty(),
                ReviewMessage.Author.AGENT, "Fixed", 1, "run", Optional.empty(), Optional.empty(), 0, Instant.EPOCH, Optional.of(TaskId.random().toString()))));
        assertThatThrownBy(() -> service.resolveFindingFromAgent(taskId.toString(), "finding-own", true)).hasMessageContaining("Reply on the finding");
        verify(reviewTasks, never()).resolveFromDeveloper(anyString(), anyString(), anyString(), eq(true));

        when(reviews.listMessages("own")).thenReturn(ImmutableList.of(new ReviewMessage("reply", "own", Optional.of("finding-own"), Optional.empty(),
                ReviewMessage.Author.AGENT, "Fixed in abc1234", 1, "run", Optional.empty(), Optional.empty(), 0, Instant.EPOCH, Optional.of(taskId.toString()))));
        assertThat(service.resolveFindingFromAgent(taskId.toString(), "finding-own", true)).contains(resolved);
        assertThat(service.resolveFindingFromAgent(taskId.toString(), "finding-own", false)).contains(open);

        // An id it may not read falls through to the task's own comments.
        assertThat(service.resolveFindingFromAgent(taskId.toString(), "finding-another-pull-request", true)).isEmpty();
        verify(reviewTasks, times(2)).resolveFromDeveloper(anyString(), anyString(), anyString(), anyBoolean());

        // A finding someone already resolved needs no reply: the review service leaves it with whoever resolved it.
        ReviewFinding mine = open.withState(ReviewFinding.State.RESOLVED, Optional.of("Handled elsewhere"), Instant.EPOCH);
        when(reviews.listFindings("own")).thenReturn(ImmutableList.of(mine));
        when(reviews.listMessages("own")).thenReturn(ImmutableList.of());
        when(reviewTasks.resolveFromDeveloper("own", "finding-own", taskId.toString(), true)).thenReturn(mine);
        assertThat(service.resolveFindingFromAgent(taskId.toString(), "finding-own", true)).contains(mine);
    }

    @Test
    void reviewTaskReadsNoFindingsThroughTheLocalReviewTools()
    {
        task = AgentTask.created(taskId, false, "Review", TitleSource.USER, AgentLaneId.CODEX_CLI, task.taskWorktree().orElseThrow(),
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH);
        when(tasks.get(taskId)).thenReturn(task);
        AgentReview review = pullRequestReview("review", "workspace", "owner/repo", 7, taskId.toString(), Instant.EPOCH);
        when(reviews.findReviewByTask(taskId.toString())).thenReturn(Optional.of(review));
        when(reviews.listReviews("workspace")).thenReturn(ImmutableList.of(review));

        assertThat(service.readsFindings(taskId.toString())).isFalse();
        assertThat(service.findingsForAgent(taskId.toString())).isEmpty();
        assertThat(service.replyToFindingFromAgent(taskId.toString(), "finding-review", "Fixed", "key")).isEmpty();
        verify(reviews, never()).listFindings(anyString());
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
    void latestLearningPreservesThePreviousVerifiedSummaryDuringRefinement()
    {
        AgentTurn previous = learningTurn(0);
        AgentTurn candidate = learningTurn(10);
        when(turnStore.list(taskId)).thenReturn(ImmutableList.of(previous, candidate));
        when(tasks.getInteractive("workspace", taskId)).thenReturn(task.withDevelopmentContinuation(true, Instant.EPOCH));
        assertThat(service.latestLearning(taskId.toString(), "workspace")).contains(previous);
        when(turnStore.list(taskId)).thenReturn(ImmutableList.of(candidate));
        assertThat(service.latestLearning(taskId.toString(), "workspace")).isEmpty();

        when(tasks.getInteractive("workspace", taskId)).thenReturn(task);
        assertThat(service.latestLearning(taskId.toString(), "workspace")).contains(candidate);
        AgentTurn noSummary = turn.finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), Instant.EPOCH.plusSeconds(20));
        AgentTurn failed = learningTurn(30, TurnState.FAILED);
        AgentTurn stopped = AgentTurn.accepted(TurnId.random(), taskId, "stopped-summary", turn.settings().orElseThrow(), Instant.EPOCH)
                .withDevelopmentOrigin(new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.REFINE_SUMMARY, turn.id()))
                .markDeliveryStarted(Instant.EPOCH).running(new ProviderTurnRef("stopped"), Optional.empty())
                .withDevelopmentResult(candidate.developmentResult().orElseThrow()).requestCancellation(Instant.EPOCH)
                .finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), Instant.EPOCH.plusSeconds(40));
        when(turnStore.list(taskId)).thenReturn(ImmutableList.of(previous, noSummary, failed, stopped));
        assertThat(service.latestLearning(taskId.toString(), "workspace")).contains(previous);
        when(tasks.getInteractive("another-workspace", taskId)).thenThrow(new AgentTaskNotFoundException(taskId));
        assertThatThrownBy(() -> service.latestLearning(taskId.toString(), "another-workspace")).isInstanceOf(AgentTaskNotFoundException.class);
    }

    @Test
    void learningDiscussionDerivesItsSnapshotAndReusesPrivateReplies()
    {
        AgentTurn summary = learningTurn(0);
        when(turnStore.list(taskId)).thenReturn(ImmutableList.of(summary));
        when(pullRequests.findByHeadRef(anyString(), anyString())).thenReturn(ImmutableList.of());
        doAnswer(call -> {
            StartAgentTurnCommand command = call.getArgument(0);
            assertThat(stored).containsKey("learning-thread");
            assertThat(command.developmentAutomation()).contains(DevelopmentAutomation.LOCAL_ONLY);
            assertThat(command.message()).contains("list_local_review_comments with commentId=\"learning-thread\"", "reply_local_review_comment", "Do not edit code",
                    "Never commit, push, or publish", "reference evidence", "User comment:")
                    .doesNotContain("Keep null values", "Preserve nullable values");
            accepted.put(command.idempotencyKey(), turn);
            return turn;
        }).when(turns).start(any());
        LocalReviewService.LearningCreate request = new LocalReviewService.LearningCreate(summary.id().toString(), "commit:" + "a".repeat(40),
                "learning-thread", "question", "Why allow null?", 0);
        LocalReviewView saved = service.createLearning(taskId.toString(), "workspace", request, "create-learning");
        LocalReviewLearningAnchor anchor = saved.learningAnchor().orElseThrow();
        assertThat(anchor).isEqualTo(new LocalReviewLearningAnchor(summary.id().toString(), "commit:" + "a".repeat(40), "aaaaaaa · Preserve nullable values", "Keep null values"));
        assertThat(saved.repo()).isEqualTo("owner/repo");
        assertThat(saved.number()).isZero();
        assertThat(saved.path()).isEmpty();
        assertThat(saved.code()).isEmpty();
        assertThat(saved.line()).isZero();
        assertThat(service.createLearning(taskId.toString(), "workspace", request, "create-learning").messages()).hasSize(1);
        LocalReviewView sameCard = service.createLearning(taskId.toString(), "workspace", new LocalReviewService.LearningCreate(summary.id().toString(), request.cardId(),
                "another-browser-id", "second-question", "Is the map mutable?", 1), "second-learning");
        assertThat(sameCard.id()).isEqualTo(saved.id());
        assertThat(stored).hasSize(1);
        assertThat(sameCard.messages()).hasSize(2);
        assertThat(service.reply(taskId.toString(), "workspace", saved.id(), "third-question", "Explain more", 2, "reply").messages()).hasSize(3);
        when(turnStore.findActive(taskId)).thenReturn(Optional.of(turn));
        assertThat(service.replyFromAgent(taskId.toString(), saved.id(), "Null values represent missing partitions", "answer").messages()).hasSize(4);
        assertThat(service.resolveFromAgent(taskId.toString(), saved.id(), true, "resolve").learningAnchor()).contains(anchor);
        assertThat(service.list(taskId.toString(), "workspace").getFirst().resolved()).isTrue();
        verify(turns, times(3)).start(any());
    }

    @Test
    void learningCommentsRejectUnverifiedForeignAndMissingCards()
    {
        AgentTurn summary = learningTurn(0);
        when(turnStore.list(taskId)).thenReturn(ImmutableList.of(summary));
        LocalReviewService.LearningCreate request = new LocalReviewService.LearningCreate(summary.id().toString(), "purpose", "learning", "question", "Explain", 0);
        when(tasks.getInteractive("workspace", taskId)).thenReturn(task.withDevelopmentContinuation(true, Instant.EPOCH));
        assertThatThrownBy(() -> service.createLearning(taskId.toString(), "workspace", request, "create")).hasMessageContaining("Verified learning summary");
        when(tasks.getInteractive("workspace", taskId)).thenReturn(task);
        assertThatThrownBy(() -> service.createLearning(taskId.toString(), "workspace", new LocalReviewService.LearningCreate(TurnId.random().toString(),
                "purpose", "learning", "question", "Explain", 0), "create")).hasMessageContaining("does not belong");
        assertThatThrownBy(() -> service.createLearning(taskId.toString(), "workspace", new LocalReviewService.LearningCreate(summary.id().toString(),
                "commit:missing", "learning", "question", "Explain", 0), "create")).hasMessageContaining("card does not exist");
        assertThat(stored).isEmpty();
        verify(turns, never()).start(any());
    }

    @Test
    void learningHttpContractReturnsNullOrTheSavedTurnAndCreatesPrivateComments()
            throws Exception
    {
        MockMvc mvc = standaloneSetup(new TaskLearningController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        String route = "/agent/tasks/" + taskId + "/learning";
        String empty = mvc.perform(get(route).param("workspaceId", "workspace")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(empty).isEqualTo("null");
        AgentTurn summary = learningTurn(0);
        when(turnStore.list(taskId)).thenReturn(ImmutableList.of(summary));
        mvc.perform(get(route).param("workspaceId", "workspace")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(summary.id().toString()))
                .andExpect(jsonPath("$.developmentResult.learningSummary.purpose").value("Make partition values reusable"))
                .andExpect(jsonPath("$.developmentResult.learningSummary.evidence").value("Unit tests passed"))
                .andExpect(jsonPath("$.developmentOrigin.kind").value("REFINE_SUMMARY"));
        doThrow(new IllegalStateException("Provider unavailable")).when(turns).start(any());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String request = mapper.writeValueAsString(new LocalReviewService.LearningCreate(summary.id().toString(), "purpose", "learning", "question", "Why this design?", 0));
        mvc.perform(post(route + "/comments").param("workspaceId", "workspace").header("Idempotency-Key", "create")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.learningAnchor.turnId").value(summary.id().toString()))
                .andExpect(jsonPath("$.learningAnchor.cardId").value("purpose"))
                .andExpect(jsonPath("$.learningAnchor.body").value("Make partition values reusable"))
                .andExpect(jsonPath("$.number").value(0)).andExpect(jsonPath("$.messages[0].deliveryState").value("UNSENT"));
        mvc.perform(post(route + "/comments").param("workspaceId", "workspace").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest());
        for (String cardId : ImmutableList.of("combined-result", "evidence", "limitations")) {
            String removed = mapper.writeValueAsString(new LocalReviewService.LearningCreate(summary.id().toString(), cardId, "removed", "question", "Explain", 0));
            mvc.perform(post(route + "/comments").param("workspaceId", "workspace").header("Idempotency-Key", "removed")
                            .contentType(MediaType.APPLICATION_JSON).content(removed))
                    .andExpect(status().isBadRequest());
        }
    }

    private AgentTurn learningTurn(long second)
    {
        return learningTurn(second, TurnState.COMPLETED);
    }

    private AgentTurn learningTurn(long second, TurnState state)
    {
        DevelopmentLearningSummary summary = new DevelopmentLearningSummary("Make partition values reusable",
                ImmutableList.of(new DevelopmentLearningCommit("a".repeat(40), "Preserve nullable values", "Keep null values")),
                Optional.of("Reuse one helper"), "Unit tests passed", ImmutableList.of());
        Instant created = Instant.EPOCH.plusSeconds(second);
        return AgentTurn.accepted(TurnId.random(), taskId, "summary-" + second, turn.settings().orElseThrow(), created)
                .withDevelopmentOrigin(new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.REFINE_SUMMARY, turn.id()))
                .markDeliveryStarted(created).running(new ProviderTurnRef("summary-provider"), Optional.empty())
                .withDevelopmentResult(new DevelopmentResultReport(false, Optional.empty(), Optional.of(summary)))
                .finish(state, AgentMetrics.empty(), state == TurnState.COMPLETED ? Optional.empty() : Optional.of("Verification failed"), created.plusSeconds(1));
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

    private static AgentReview review(String id, OptionalInt number, Optional<String> sourceTaskId)
    {
        return openReview(id, "workspace", "owner/repo", number, "task-of-" + id, sourceTaskId, Instant.EPOCH);
    }

    private static ReviewFinding finding(String id, String reviewId)
    {
        return new ReviewFinding(id, reviewId, "run", 1, "Title", "Body", Optional.empty(), Optional.empty(), ImmutableList.of(), 1, ReviewFinding.State.OPEN,
                Optional.empty(), "b".repeat(40), false, 0, Instant.EPOCH, Instant.EPOCH, ImmutableList.of(), ReviewFinding.Origin.AUTOMATIC);
    }

    private LocalReviewView create(long expectedTaskVersion)
    {
        return service.create(taskId.toString(), "workspace", new LocalReviewService.Create("thread", "message", "owner/repo", 7,
                "src/Example.java", "RIGHT", 10, 11, Optional.of("a".repeat(40)), Optional.of("b".repeat(40)), "newValue", "Please fix this", expectedTaskVersion), "create");
    }
}
