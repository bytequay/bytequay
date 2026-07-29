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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.checks.ValidationClaimService;
import com.bytequay.app.service.checks.ValidationPassService;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.stage.StageStateMachine;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestLegacyBrainReviewRetirement
{
    private final TaskStore tasks = mock(TaskStore.class);
    private final StageStore stages = mock(StageStore.class);
    private final StageStateMachine stageMachine = mock(StageStateMachine.class);
    private final ReviewRoundStore rounds = mock(ReviewRoundStore.class);
    private final AgentRunService runs = mock(AgentRunService.class);
    private final ThreadStore threads = mock(ThreadStore.class);
    private final ThreadTurnScheduler scheduler = mock(ThreadTurnScheduler.class);
    private final ThreadTurnStore turns = mock(ThreadTurnStore.class);
    private final PRService prs = mock(PRService.class);
    private final ValidationPassService validation = mock(ValidationPassService.class);
    private final ValidationClaimService claims = mock(ValidationClaimService.class);
    private final ReviewRoundStateMachine roundMachine = mock(ReviewRoundStateMachine.class);
    private final TaskPhaseMachine phaseMachine = mock(TaskPhaseMachine.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final TaskCommandExecutor commands = mock(TaskCommandExecutor.class);
    private final BrainReviewServiceImpl service = new BrainReviewServiceImpl(
            tasks, stages, stageMachine, rounds, runs, threads, scheduler, turns,
            prs, validation, claims, roundMachine, phaseMachine, notifications,
            new ObjectMapper(), Clock.systemUTC(), events, commands);

    @Test
    void everyLegacyMutationRejectsBeforeCommandRoundTurnOrEventWrite()
    {
        PR pr = PR.create(
                "pr-1", "task-1", "feature/x", "main", "Title", "",
                Instant.EPOCH);
        Task task = mock(Task.class);
        when(task.id()).thenReturn("task-1");
        when(prs.findById("pr-1")).thenReturn(Optional.of(pr));
        when(tasks.findTaskById("task-1")).thenReturn(Optional.of(task));
        when(tasks.findWorkflowVersion("task-1")).thenReturn(Optional.of("LEGACY"));

        assertRetired(() -> service.reviewBeforeLocalOpen("pr-1", "agent"));
        assertRetired(() -> service.reviewAfterLocalComments("pr-1"));
        assertRetired(() -> service.pauseActiveReview("task-1", "pause"));
        assertRetired(() -> service.resumeParkedReview("task-1"));
        assertRetired(() -> service.reviewBeforeRoundGate(null, null));
        assertRetired(() -> service.recordVerdict(
                "task-1", "stage-1", "run-1", "round", "approved"));
        assertRetired(() -> service.onTurnFinished(null));
        assertRetired(() -> service.onRoundOpened(null));
        assertRetired(() -> service.onRoundTransitioned(null));
        assertRetired(() -> service.onRoundTurnStatusChanged(null));
        assertRetired(() -> service.onRoundValidationFinished(null));
        assertRetired(() -> service.onRoundGateValidationFinished(null));
        assertRetired(() -> service.driveRound("round-1"));
        assertRetired(() -> service.onTurnBudgetPaused(null));
        assertRetired(service::reconcilePlanSelfReviews);
        assertRetired(service::reconcileStalledRounds);
        assertRetired(() -> service.stopRoundRuntimeAfterCommit(null));
        assertRetired(() -> service.deliverNeedsAttention(null));

        assertThat(service.ownsParkedResume("task-1")).isFalse();
        assertThat(service.isBudgetExhaustedEscalation("task-1")).isFalse();
        verify(prs, never()).requestUserReview(any(), any());
        verifyNoInteractions(
                stages, stageMachine, rounds, runs, threads, scheduler, turns,
                validation, claims, roundMachine, phaseMachine, notifications,
                events, commands);
    }

    private static void assertRetired(Runnable action)
    {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
    }
}
