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
package com.bytequay.app.service.tools;

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.threads.ParkedProposalService;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the two PR review-comment tools added to {@link
 * PublishToolHandlers}: {@code list_pr_review_threads} (read) and
 * {@code resolve_review_thread} (parked publish). Both accept an
 * explicit {@code repo} + {@code pr_number}, falling back to the
 * active task's linked PR when omitted. The mutation parks a {@link
 * ParkedProposal.ResolveReviewThread} rather than hitting GitHub —
 * nothing publishes without the user's Approve in the gate.
 */
class TestPublishToolHandlers
{
    private TaskStore taskStore;
    private ParkedProposalService parkedProposals;
    private PullRequestService pullRequestService;
    private PRService prService;
    private TaskPhaseMachine taskPhaseMachine;
    private GitRunner git;
    private PublishToolHandlers handlers;

    @BeforeEach
    void setUp()
    {
        taskStore = mock(TaskStore.class);
        parkedProposals = mock(ParkedProposalService.class);
        pullRequestService = mock(PullRequestService.class);
        prService = mock(PRService.class);
        taskPhaseMachine = mock(TaskPhaseMachine.class);
        git = mock(GitRunner.class);
        handlers = new PublishToolHandlers(
                taskStore,
                mock(WatchedRepoStore.class),
                parkedProposals,
                git,
                new ObjectMapper(),
                taskPhaseMachine,
                pullRequestService,
                prService);
    }

    @Test
    void resolveReviewThreadParksTheTypedProposalFromExplicitRepoAndPrNumber()
    {
        Task task = taskAt("task-1", TaskStatus.RUNNING);
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(task));

        ToolOutcome outcome = handlers.resolveReviewThread(
                new PublishToolHandlers.ResolveReviewThreadArgs(555L, true, "acme/widget", 42),
                new ToolCall(ThreadScope.TASK, "thread-1", null, AgentRole.TASK, "task-1", null));

        assertThat(((ToolOutcome.Completed) outcome).isError()).isFalse();
        ArgumentCaptor<ParkedProposal> captor = ArgumentCaptor.forClass(ParkedProposal.class);
        verify(parkedProposals).park(eq(task), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(ParkedProposal.ResolveReviewThread.class);
        ParkedProposal.ResolveReviewThread parked = (ParkedProposal.ResolveReviewThread) captor.getValue();
        assertThat(parked.rootCommentId()).isEqualTo(555L);
        assertThat(parked.resolved()).isTrue();
        assertThat(parked.pr()).isEqualTo(new ParkedProposal.PrRef("acme", "widget", 42));
    }

    @Test
    void resolveReviewThreadDefaultsResolvedToTrueWhenTheFlagIsOmitted()
    {
        Task task = taskAt("task-2", TaskStatus.RUNNING);
        when(taskStore.findTaskById("task-2")).thenReturn(Optional.of(task));

        handlers.resolveReviewThread(
                new PublishToolHandlers.ResolveReviewThreadArgs(7L, null, "acme/widget", 5),
                new ToolCall(ThreadScope.TASK, "thread-2", null, AgentRole.TASK, "task-2", null));

        ArgumentCaptor<ParkedProposal> captor = ArgumentCaptor.forClass(ParkedProposal.class);
        verify(parkedProposals).park(eq(task), captor.capture());
        assertThat(((ParkedProposal.ResolveReviewThread) captor.getValue()).resolved()).isTrue();
    }

    @Test
    void resolveReviewThreadRejectsAMissingRootCommentIdWithoutParking()
    {
        ToolOutcome outcome = handlers.resolveReviewThread(
                new PublishToolHandlers.ResolveReviewThreadArgs(null, true, "acme/widget", 42),
                new ToolCall(ThreadScope.TRUNK, "thread-3", null, AgentRole.TASK));

        assertThat(((ToolOutcome.Completed) outcome).text()).contains("root_comment_id is required");
        verify(parkedProposals, never()).park(any(), any());
    }

    @Test
    void listPrReviewThreadsReportsNoPrWhenNeitherArgsNorAnActiveTaskCarryOne()
    {
        ToolOutcome outcome = handlers.listPrReviewThreads(
                new PublishToolHandlers.ListPrReviewThreadsArgs(null, null),
                new ToolCall(ThreadScope.TRUNK, "thread-4", null, AgentRole.TASK));

        ToolOutcome.Completed completed = (ToolOutcome.Completed) outcome;
        assertThat(completed.isError()).isFalse();
        assertThat(completed.text()).contains("no PR to read");
        verify(pullRequestService, never()).getPullRequestDetail(any(), anyInt());
    }

    @Test
    void listPrReviewThreadsResolvesTheExplicitRefAndWrapsAFetchFailureAsAToolError()
    {
        when(pullRequestService.getPullRequestDetail("acme/widget", 42))
                .thenThrow(new RuntimeException("502 from GitHub"));

        ToolOutcome outcome = handlers.listPrReviewThreads(
                new PublishToolHandlers.ListPrReviewThreadsArgs("acme/widget", 42),
                new ToolCall(ThreadScope.TRUNK, "thread-5", null, AgentRole.TASK));

        ToolOutcome.Completed completed = (ToolOutcome.Completed) outcome;
        assertThat(completed.isError()).isTrue();
        assertThat(completed.text()).contains("acme/widget#42").contains("502 from GitHub");
    }

    @Test
    void shipTaskParksTheProposedPrTitleAndBody()
    {
        Task task = taskAt("task-ship", TaskStatus.RUNNING);
        when(taskStore.findTaskById("task-ship")).thenReturn(Optional.of(task));

        ToolOutcome outcome = handlers.shipTask(
                new PublishToolHandlers.ShipTaskArgs(
                        "Next thing", "main", "Add cache layer", "## Summary\nCaches reads."),
                new ToolCall(ThreadScope.TASK, "thread-ship", null, AgentRole.TASK, "task-ship", null));

        assertThat(((ToolOutcome.Completed) outcome).isError()).isFalse();
        ArgumentCaptor<ParkedProposal> captor = ArgumentCaptor.forClass(ParkedProposal.class);
        verify(parkedProposals).park(eq(task), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(ParkedProposal.ShipTask.class);
        ParkedProposal.ShipTask parked = (ParkedProposal.ShipTask) captor.getValue();
        assertThat(parked.prTitle()).isEqualTo("Add cache layer");
        assertThat(parked.prBody()).isEqualTo("## Summary\nCaches reads.");
    }

    @Test
    void legacyPublishToolsCannotBypassTheLocalPrReviewLifecycle()
    {
        Task task = taskAt("task-local-pr", TaskStatus.RUNNING);
        when(taskStore.findTaskById("task-local-pr")).thenReturn(Optional.of(task));
        when(prService.findByTask("task-local-pr"))
                .thenReturn(Optional.of(PR.create(
                        "pr-local", task.id(), task.branchName(), task.baseBranch(),
                        "Local PR", "", task.createdAt())));
        ToolCall call = new ToolCall(ThreadScope.TASK,
                "thread-task-local-pr", null, AgentRole.TASK, "task-local-pr", null);

        ToolOutcome ship = handlers.shipTask(
                new PublishToolHandlers.ShipTaskArgs(null, null, "Title", "Body"), call);
        ToolOutcome push = handlers.push(new PublishToolHandlers.PushArgs(), call);
        ToolOutcome next = handlers.nextTask(
                new PublishToolHandlers.NextTaskArgs(null, null), call);
        ToolOutcome requestReview = handlers.requestReview(
                new PublishToolHandlers.RequestReviewArgs("Ready", null), call);
        ToolOutcome validate = handlers.validate(
                new PublishToolHandlers.ValidateArgs("tests pass"), call);

        assertThat(((ToolOutcome.Completed) ship).text()).contains("record_local_review");
        assertThat(((ToolOutcome.Completed) push).text()).contains("record_local_review");
        assertThat(((ToolOutcome.Completed) next).text()).contains("record_local_review");
        assertThat(((ToolOutcome.Completed) requestReview).text()).contains("record_local_review");
        assertThat(((ToolOutcome.Completed) validate).text()).contains("record_local_review");
        assertThat(task.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(task.phase()).isEqualTo(TaskPhase.IMPLEMENTING);
        verify(parkedProposals, never()).park(any(), any());
        verify(taskPhaseMachine, never()).observe(any(), any(), any());
        verify(taskPhaseMachine, never()).transition(any(), any(), any(), any());
    }

    @Test
    void shipTaskRejectsBlankPrTitleAndBody()
    {
        Task task = taskAt("task-ship-blank", TaskStatus.RUNNING);
        when(taskStore.findTaskById("task-ship-blank")).thenReturn(Optional.of(task));

        ToolOutcome outcome = handlers.shipTask(
                new PublishToolHandlers.ShipTaskArgs(null, null, "   ", ""),
                new ToolCall(ThreadScope.TASK, "thread-ship-blank", null, AgentRole.TASK, "task-ship-blank", null));

        ToolOutcome.Completed completed = (ToolOutcome.Completed) outcome;
        assertThat(completed.text()).contains("prepare the PR draft").contains("record_pr_description");
        verify(parkedProposals, never()).park(any(), any());
    }

    @Test
    void shipTaskAllowsBlankDraftFieldsWhenTheRemotePrAlreadyExists()
    {
        Task task = taskAt("task-ship-existing", TaskStatus.IN_REVIEW).withPrNumber(145);
        when(taskStore.findTaskById("task-ship-existing")).thenReturn(Optional.of(task));

        handlers.shipTask(
                new PublishToolHandlers.ShipTaskArgs(null, null, null, null),
                new ToolCall(ThreadScope.TASK, "thread-ship-existing", null, AgentRole.TASK, "task-ship-existing", null));

        ArgumentCaptor<ParkedProposal> captor = ArgumentCaptor.forClass(ParkedProposal.class);
        verify(parkedProposals).park(eq(task), captor.capture());
        ParkedProposal.ShipTask parked = (ParkedProposal.ShipTask) captor.getValue();
        assertThat(parked.prTitle()).isNull();
        assertThat(parked.prBody()).isNull();
    }

    @Test
    void shipTaskAllowsBlankDraftFieldsForAWorkItemLinkedToAnExistingPr()
    {
        Task task = taskAt("task-ship-linked", TaskStatus.IN_REVIEW).withLinkedPrNumber(145);
        when(taskStore.findTaskById("task-ship-linked")).thenReturn(Optional.of(task));

        handlers.shipTask(
                new PublishToolHandlers.ShipTaskArgs(null, null, null, null),
                new ToolCall(ThreadScope.TASK, "thread-ship-linked", null, AgentRole.TASK, "task-ship-linked", null));

        verify(parkedProposals).park(eq(task), any(ParkedProposal.ShipTask.class));
    }

    @Test
    void shipTaskBouncesTheTurnBackWhenTheWorktreeHasUncommittedChanges()
            throws Exception
    {
        Task task = taskAt("task-dirty", TaskStatus.RUNNING);
        when(taskStore.findTaskById("task-dirty")).thenReturn(Optional.of(task));
        when(git.hasUncommittedChanges(any())).thenReturn(true);

        ToolOutcome outcome = handlers.shipTask(
                new PublishToolHandlers.ShipTaskArgs(null, "main", "Add cache layer", "body"),
                new ToolCall(ThreadScope.TASK, "thread-dirty", null, AgentRole.TASK, "task-dirty", null));

        // Nothing parks — the agent is told to commit its own work and re-ship,
        // so the user never reviews an empty branch.
        ToolOutcome.Completed completed = (ToolOutcome.Completed) outcome;
        assertThat(completed.isError()).isFalse();
        assertThat(completed.text()).contains("uncommitted changes").contains("ship_task again");
        verify(parkedProposals, never()).park(any(), any());
    }

    @Test
    void resolvesTheTaskFromTheRunningTurnWhenNoActiveTaskOnThread()
    {
        // A shipped task is IN_REVIEW, so the thread's active-task
        // projection is null — but the running turn is stamped with the
        // task id. The handler must resolve by that id, not give up with
        // "no active task on this thread".
        Task shipped = taskAt("task-ship", TaskStatus.IN_REVIEW);
        when(taskStore.findTaskById("task-ship")).thenReturn(Optional.of(shipped));

        ToolOutcome outcome = handlers.requestReview(
                new PublishToolHandlers.RequestReviewArgs("ready", null),
                new ToolCall(ThreadScope.TASK, "thread-task-ship", null, AgentRole.TASK, "task-ship", null));

        ToolOutcome.Completed completed = (ToolOutcome.Completed) outcome;
        assertThat(completed.isError()).isFalse();
        assertThat(completed.text()).doesNotContain("no active task");
        verify(parkedProposals).park(eq(shipped), any());
    }

    @Test
    void requestReviewDiffPreviewUsesUpstreamBaseForForkWorktrees()
            throws Exception
    {
        Task task = taskAt("task-fork", TaskStatus.RUNNING);
        when(taskStore.findTaskById("task-fork")).thenReturn(Optional.of(task));
        when(git.refExists(Path.of(task.worktreePath()), "upstream/main")).thenReturn(true);
        when(git.diff(Path.of(task.worktreePath()), "upstream/main", "HEAD", 500_000))
                .thenReturn("diff --git a/src/App.java b/src/App.java\n");

        ToolOutcome outcome = handlers.requestReview(
                new PublishToolHandlers.RequestReviewArgs("ready", null),
                new ToolCall(ThreadScope.TASK, "thread-fork", null, AgentRole.TASK, "task-fork", null));

        assertThat(((ToolOutcome.Completed) outcome).isError()).isFalse();
        ArgumentCaptor<ParkedProposal> captor = ArgumentCaptor.forClass(ParkedProposal.class);
        verify(parkedProposals).park(eq(task), captor.capture());
        ParkedProposal.RequestReview parked = (ParkedProposal.RequestReview) captor.getValue();
        assertThat(parked.diffBase()).isEqualTo("upstream/main");
        assertThat(parked.diff()).contains("diff --git");
    }

    private static Task taskAt(String id, TaskStatus status)
    {
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        return new Task(
                id, "thread-" + id, 1L, status,
                "feature/x", "/tmp/wt/feature-x", "main", "/tmp/repo",
                null, null, null, null, null,
                "DEVELOP", null, null,
                0L, 0L, 0L,
                /* agentSessionId */ null,
                now, null, null, null, null, null);
    }
}
