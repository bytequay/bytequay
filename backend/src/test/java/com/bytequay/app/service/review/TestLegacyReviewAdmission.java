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

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPassHostKind;
import com.bytequay.app.domain.ReviewPassKind;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.threads.AgentScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestLegacyReviewAdmission
{
    private AgentScheduler scheduler;
    private TaskStore tasks;
    private ThreadStore threads;
    private LegacyReviewAdmission admission;

    @BeforeEach
    void setUp()
    {
        scheduler = mock(AgentScheduler.class);
        tasks = mock(TaskStore.class);
        threads = mock(ThreadStore.class);
        admission = new LegacyReviewAdmission(scheduler, tasks, threads);
    }

    @Test
    void taskHostedReviewUsesTheExactPersistedTaskScopeAndEpoch()
    {
        ReviewPass pass = pass(ReviewPassHostKind.TASK_PHASE, "task-7");
        Task task = mock(Task.class);
        when(task.id()).thenReturn("task-7");
        when(task.threadId()).thenReturn("trunk-3");
        com.bytequay.app.domain.Thread trunk = mock(com.bytequay.app.domain.Thread.class);
        when(trunk.id()).thenReturn("trunk-3");
        when(trunk.workspaceId()).thenReturn("workspace-2");
        when(tasks.findTaskById("task-7")).thenReturn(Optional.of(task));
        when(tasks.findTaskEpoch("task-7")).thenReturn(OptionalLong.of(9));
        when(threads.findThreadById("trunk-3")).thenReturn(Optional.of(trunk));

        CapacityManager.CapacityRequest request = admission.request(
                pass, LegacyReviewAdmission.ProviderLane.CLI, "seat-attempt");

        assertThat(request.operationId()).isEqualTo("legacy-review:pass-1:seat-attempt");
        assertThat(request.source()).isEqualTo(CapacityManager.WorkflowSource.LEGACY);
        assertThat(request.lanes()).containsExactlyInAnyOrder(
                CapacityManager.CapacityLane.REVIEW,
                CapacityManager.CapacityLane.CLI);
        assertThat(request.scope()).isEqualTo(new CapacityManager.CapacityScope(
                "workspace-2", "trunk-3", "task-7", 9L));
        assertThat(request.trunkControl()).isFalse();
        assertThat(request.exclusiveTask()).isFalse();
        assertThat(request.writerRequired()).isFalse();
    }

    @Test
    void standaloneReviewUsesOnlyItsExactWorkspace()
    {
        ReviewPass pass = pass(ReviewPassHostKind.THREAD, "review-thread-4");
        com.bytequay.app.domain.Thread reviewThread =
                mock(com.bytequay.app.domain.Thread.class);
        when(reviewThread.workspaceId()).thenReturn("workspace-8");
        when(threads.findThreadById("review-thread-4"))
                .thenReturn(Optional.of(reviewThread));

        CapacityManager.CapacityRequest request = admission.request(
                pass, LegacyReviewAdmission.ProviderLane.API, "lead-attempt");

        assertThat(request.lanes()).isEqualTo(Set.of(
                CapacityManager.CapacityLane.REVIEW,
                CapacityManager.CapacityLane.API));
        assertThat(request.scope()).isEqualTo(new CapacityManager.CapacityScope(
                "workspace-8", null, null, null));
        verifyNoInteractions(tasks);
    }

    @Test
    void missingTaskEpochFailsClosedBeforeSchedulerAdmission()
    {
        ReviewPass pass = pass(ReviewPassHostKind.TASK_PHASE, "task-7");
        Task task = mock(Task.class);
        when(task.id()).thenReturn("task-7");
        when(task.threadId()).thenReturn("trunk-3");
        com.bytequay.app.domain.Thread trunk = mock(com.bytequay.app.domain.Thread.class);
        when(trunk.id()).thenReturn("trunk-3");
        when(trunk.workspaceId()).thenReturn("workspace-2");
        when(tasks.findTaskById("task-7")).thenReturn(Optional.of(task));
        when(tasks.findTaskEpoch("task-7")).thenReturn(OptionalLong.empty());
        when(threads.findThreadById("trunk-3")).thenReturn(Optional.of(trunk));

        assertThatThrownBy(() -> admission.invoke(
                pass, LegacyReviewAdmission.ProviderLane.API, "attempt", () -> "never"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no exact epoch");
        verify(scheduler, never()).invokeReviewApi(any(), any());
    }

    @Test
    void nestedReviewerLaunchTriesCapacityInsteadOfBlockingTheLead()
            throws Exception
    {
        ReviewPass pass = standalonePass();
        stubReviewThread(pass);
        when(scheduler.invokeReviewApi(any(), any())).thenAnswer(invocation ->
                invocation.<Callable<String>>getArgument(1).call());
        when(scheduler.tryInvokeReviewApi(any(), any())).thenAnswer(invocation ->
                Optional.of(invocation.<Callable<String>>getArgument(1).call()));

        String result = admission.invoke(
                pass,
                LegacyReviewAdmission.ProviderLane.API,
                "lead",
                () -> admission.invoke(
                        pass,
                        LegacyReviewAdmission.ProviderLane.API,
                        "reviewer",
                        () -> "done"));

        assertThat(result).isEqualTo("done");
        verify(scheduler).invokeReviewApi(any(), any());
        verify(scheduler).tryInvokeReviewApi(any(), any());
    }

    @Test
    void nestedCapacityDenialIsRetryableAndDoesNotLaunchTheProvider()
            throws Exception
    {
        ReviewPass pass = standalonePass();
        stubReviewThread(pass);
        AtomicBoolean launched = new AtomicBoolean();
        when(scheduler.invokeReviewApi(any(), any())).thenAnswer(invocation ->
                invocation.<Callable<String>>getArgument(1).call());
        when(scheduler.tryInvokeReviewApi(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> admission.invoke(
                pass,
                LegacyReviewAdmission.ProviderLane.API,
                "lead",
                () -> admission.invoke(
                        pass,
                        LegacyReviewAdmission.ProviderLane.API,
                        "reviewer",
                        () -> {
                            launched.set(true);
                            return "never";
                        })))
                .isInstanceOf(LegacyReviewAdmission.ReviewCapacityUnavailableException.class)
                .hasMessageContaining("review capacity unavailable");
        assertThat(launched).isFalse();
    }

    @Test
    void rawAlreadyAdmittedPathRequiresTheExactAttemptMarker()
            throws Exception
    {
        ReviewPass pass = standalonePass();
        stubReviewThread(pass);
        when(scheduler.invokeReviewApi(any(), any())).thenAnswer(invocation ->
                invocation.<Callable<String>>getArgument(1).call());

        assertThatThrownBy(() -> admission.requireCurrent(
                pass, LegacyReviewAdmission.ProviderLane.API, "seat"))
                .isInstanceOf(IllegalStateException.class);

        String result = admission.invoke(
                pass,
                LegacyReviewAdmission.ProviderLane.API,
                "seat",
                () -> {
                    admission.requireCurrent(
                            pass, LegacyReviewAdmission.ProviderLane.API, "seat");
                    return "admitted";
                });
        assertThat(result).isEqualTo("admitted");
    }

    @Test
    void attemptIdentityIsStableAndSemanticInputsChangeIt()
    {
        String first = LegacyReviewAdmission.attemptId(
                "reviewer", "seat-1", ReviewPhase.INDEPENDENT, 2, "same");
        assertThat(LegacyReviewAdmission.attemptId(
                "reviewer", "seat-1", ReviewPhase.INDEPENDENT, 2, "same"))
                .isEqualTo(first);
        assertThat(LegacyReviewAdmission.attemptId(
                "reviewer", "seat-1", ReviewPhase.INDEPENDENT, 2, "changed"))
                .isNotEqualTo(first);
    }

    private ReviewPass standalonePass()
    {
        return pass(ReviewPassHostKind.THREAD, "review-thread-1");
    }

    private void stubReviewThread(ReviewPass pass)
    {
        com.bytequay.app.domain.Thread reviewThread =
                mock(com.bytequay.app.domain.Thread.class);
        when(reviewThread.workspaceId()).thenReturn("workspace-1");
        when(threads.findThreadById(pass.hostId())).thenReturn(Optional.of(reviewThread));
    }

    private static ReviewPass pass(ReviewPassHostKind hostKind, String hostId)
    {
        return new ReviewPass(
                "pass-1", "review-thread-1", "acme/widget", 42, "abc",
                ReviewPhase.INDEPENDENT, 1, 3, 500L, 0L, null,
                Instant.EPOCH, null, null, null, hostKind, hostId,
                ReviewPassKind.FRESH, null);
    }
}
