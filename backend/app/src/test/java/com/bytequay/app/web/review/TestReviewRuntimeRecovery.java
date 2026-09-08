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
package com.bytequay.app.web.review;

import com.bytequay.agent.cli.process.OwnedProcessRecovery;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentRuntimeStopImpact;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.impl.AgentMaintenance;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentProcessLaunchStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.core.store.SteerDeliveryStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agent.AgentServingGate;
import com.bytequay.app.agent.AgentStartupRecovery;
import com.bytequay.app.review.AgentReviewProjection;
import com.bytequay.app.review.AgentReviewService;
import com.bytequay.app.review.ReviewPublicationService;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.ReviewNotFoundException;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestReviewRuntimeRecovery
{
    private static final String REVIEW_ID = "review-1";
    private static final TaskId TASK_ID = TaskId.valueOf("11111111-1111-4111-8111-111111111111");
    private static final Instant NOW = Instant.parse("2026-09-07T15:00:00Z");
    private final AgentReviewService reviews = mock(AgentReviewService.class);
    private final AgentTurnService turns = mock(AgentTurnService.class);
    private final ProcessSupervisor supervisor = mock(ProcessSupervisor.class);
    private final AgentTaskStore taskStore = mock(AgentTaskStore.class);
    private final AgentTurnStore turnStore = mock(AgentTurnStore.class);
    private final AgentProcessLaunchStore launchStore = mock(AgentProcessLaunchStore.class);
    private final AgentStartupRecovery recovery = new AgentStartupRecovery(
            mock(AgentEventOutboxStore.class), mock(AgentEventService.class), maintenance(), supervisor,
            new AgentServingGate(), launchStore, taskStore, turnStore);
    private final ReviewController controller = new ReviewController(
            reviews, mock(ReviewPublicationService.class), turns, mock(AgentEventService.class), mock(AgentApprovalService.class), recovery);

    @Test
    void scopesTheImpactToTheReviewOwnedTask()
    {
        AgentTask task = task();
        when(reviews.task(REVIEW_ID)).thenReturn(task);
        when(turns.stopImpact(TASK_ID)).thenReturn(new AgentRuntimeStopImpact(TASK_ID, "task:7", Optional.empty(), false));

        var impact = controller.runtimeStopImpact(REVIEW_ID);

        assertThat(task.interactive()).isFalse();
        assertThat(impact.taskId()).isEqualTo(TASK_ID.toString());
        assertThat(impact.generation()).isEqualTo("task:7");
        verify(turns).stopImpact(TASK_ID);
        verifyNoInteractions(supervisor, taskStore, turnStore, launchStore);
    }

    @Test
    void recoversAStartupClaimAndKeepsTheSameReviewOpen()
    {
        AgentTask blocked = task().claimWorktreeMutation(WorktreeMutationClaim.recovery("launch-1", NOW), NOW);
        when(reviews.task(REVIEW_ID)).thenReturn(blocked);
        when(reviews.get(REVIEW_ID)).thenReturn(projection());
        when(taskStore.find(TASK_ID)).thenReturn(Optional.of(blocked));
        when(taskStore.update(any())).thenAnswer(invocation -> invocation.getArgument(0, AgentTask.class).withVersion(blocked.version() + 1));
        when(launchStore.list()).thenReturn(ImmutableList.of());
        when(supervisor.recoverOwnedProcess("launch-1")).thenReturn(new OwnedProcessRecovery(
                "launch-1", Optional.empty(), Optional.empty(), OwnedProcessRecovery.Outcome.VERIFIED_STOPPED, "Runtime verified stopped"));

        var result = controller.stopRuntime(REVIEW_ID, new ReviewHttpModels.StopRuntimeRequest("task:" + blocked.version()));

        assertThat(result.id()).isEqualTo(REVIEW_ID);
        assertThat(result.state()).isEqualTo("OPEN");
        assertThat(result.task().id()).isEqualTo(TASK_ID.toString());
        assertThat(result.followUpPaused()).isTrue();
        assertThat(result.task().mutationClaim()).isEmpty();
        verify(supervisor).recoverOwnedProcess("launch-1");
        verify(supervisor).acknowledge("launch-1");
        verify(taskStore).update(argThat(task -> task.worktreeMutationClaim().isEmpty() && task.state() == blocked.state()));
        verify(reviews).task(REVIEW_ID);
        verify(reviews).get(REVIEW_ID);
        verifyNoMoreInteractions(reviews);
        verifyNoInteractions(turns);
    }

    @Test
    void stopsTheCurrentBackendRuntimeThroughTheTurnService()
    {
        TurnId turnId = TurnId.valueOf("22222222-2222-4222-8222-222222222222");
        when(reviews.task(REVIEW_ID)).thenReturn(task().claimWorktreeMutation(WorktreeMutationClaim.turn(turnId, NOW), NOW));
        when(reviews.get(REVIEW_ID)).thenReturn(projection());

        controller.stopRuntime(REVIEW_ID, new ReviewHttpModels.StopRuntimeRequest("task:8"));

        verify(turns).stopRuntime(TASK_ID, "task:8");
        verifyNoInteractions(supervisor, taskStore, turnStore, launchStore);
    }

    @Test
    void preservesTheRuntimeServicesStaleGenerationRejection()
    {
        AgentTask blocked = task().claimWorktreeMutation(WorktreeMutationClaim.recovery("launch-1", NOW), NOW);
        when(reviews.task(REVIEW_ID)).thenReturn(blocked);
        when(taskStore.find(TASK_ID)).thenReturn(Optional.of(blocked));

        assertThatThrownBy(() -> controller.stopRuntime(REVIEW_ID, new ReviewHttpModels.StopRuntimeRequest("task:old")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("runtime changed");
        verify(reviews).task(REVIEW_ID);
        verifyNoMoreInteractions(reviews);
        verifyNoInteractions(turns, supervisor, turnStore, launchStore);
    }

    @Test
    void rejectsAnUnknownReviewBeforeAccessingAnyTaskRuntime()
    {
        when(reviews.task("unknown-review")).thenThrow(new ReviewNotFoundException("Review not found"));

        assertThatThrownBy(() -> controller.runtimeStopImpact("unknown-review")).isInstanceOf(ReviewNotFoundException.class);
        assertThatThrownBy(() -> controller.stopRuntime("unknown-review", new ReviewHttpModels.StopRuntimeRequest("task:7")))
                .isInstanceOf(ReviewNotFoundException.class);
        verifyNoInteractions(turns, supervisor, taskStore, turnStore, launchStore);
    }

    private AgentMaintenance maintenance()
    {
        return new AgentMaintenance(
                taskStore, turnStore, mock(QueuedInputStore.class), mock(SteerDeliveryStore.class), mock(AgentUnitOfWork.class),
                mock(AgentEventOutboxStore.class), mock(AgentApprovalService.class), mock(AgentMetricsService.class), mock(AgentCompletionService.class),
                new AgentRuntimeRegistry(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AgentTask task()
    {
        return AgentTask.created(
                TASK_ID, Optional.of("workspace-1"), false, "Review owner/repo#7", new AgentLaneId("claude"), new RepositoryIdentity("owner", "repo"),
                Path.of("/tmp/review-worktree"), new AgentTaskDefaults(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Optional.empty()), NOW);
    }

    private static AgentReviewProjection projection()
    {
        AgentReview review = new AgentReview(
                REVIEW_ID, "workspace-1", "owner/repo", 7, OptionalLong.empty(), TASK_ID.toString(), AgentReview.State.OPEN, true,
                Optional.empty(), Optional.of("head"), 0, NOW, NOW, Optional.empty());
        return new AgentReviewProjection(
                review, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                new AgentReviewProjection.TaskView(TASK_ID.toString(), "workspace-1", "OPEN", 8, "claude", "Claude Code", Optional.empty(), Optional.empty(), false, false),
                Optional.empty(), ImmutableList.of(), 0, ImmutableList.of(), Optional.empty());
    }
}
