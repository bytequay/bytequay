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

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.threads.ParkedProposalService;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    private PublishToolHandlers handlers;

    @BeforeEach
    void setUp()
    {
        taskStore = mock(TaskStore.class);
        parkedProposals = mock(ParkedProposalService.class);
        pullRequestService = mock(PullRequestService.class);
        handlers = new PublishToolHandlers(
                taskStore,
                mock(WatchedRepoStore.class),
                parkedProposals,
                mock(GitRunner.class),
                new ObjectMapper(),
                mock(TaskPhaseMachine.class),
                pullRequestService);
    }

    @Test
    void resolveReviewThreadParksTheTypedProposalFromExplicitRepoAndPrNumber()
    {
        Task task = taskAt("task-1", TaskStatus.RUNNING);
        when(taskStore.findActiveTaskForThread("thread-1")).thenReturn(Optional.of(task));

        ToolOutcome outcome = handlers.resolveReviewThread(
                new PublishToolHandlers.ResolveReviewThreadArgs(555L, true, "acme/widget", 42),
                new ToolCall("thread-1", null, AgentRole.TASK));

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
        when(taskStore.findActiveTaskForThread("thread-2")).thenReturn(Optional.of(task));

        handlers.resolveReviewThread(
                new PublishToolHandlers.ResolveReviewThreadArgs(7L, null, "acme/widget", 5),
                new ToolCall("thread-2", null, AgentRole.TASK));

        ArgumentCaptor<ParkedProposal> captor = ArgumentCaptor.forClass(ParkedProposal.class);
        verify(parkedProposals).park(eq(task), captor.capture());
        assertThat(((ParkedProposal.ResolveReviewThread) captor.getValue()).resolved()).isTrue();
    }

    @Test
    void resolveReviewThreadRejectsAMissingRootCommentIdWithoutParking()
    {
        ToolOutcome outcome = handlers.resolveReviewThread(
                new PublishToolHandlers.ResolveReviewThreadArgs(null, true, "acme/widget", 42),
                new ToolCall("thread-3", null, AgentRole.TASK));

        assertThat(((ToolOutcome.Completed) outcome).text()).contains("root_comment_id is required");
        verify(parkedProposals, never()).park(any(), any());
    }

    @Test
    void listPrReviewThreadsReportsNoPrWhenNeitherArgsNorAnActiveTaskCarryOne()
    {
        when(taskStore.findActiveTaskForThread("thread-4")).thenReturn(Optional.empty());

        ToolOutcome outcome = handlers.listPrReviewThreads(
                new PublishToolHandlers.ListPrReviewThreadsArgs(null, null),
                new ToolCall("thread-4", null, AgentRole.TASK));

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
                new ToolCall("thread-5", null, AgentRole.TASK));

        ToolOutcome.Completed completed = (ToolOutcome.Completed) outcome;
        assertThat(completed.isError()).isTrue();
        assertThat(completed.text()).contains("acme/widget#42").contains("502 from GitHub");
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
