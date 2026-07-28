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
package com.bytequay.app.developmentflow.userwait;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.persistence.V2UserWaitStore;
import com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.V2StageSteeringRuntime;
import com.bytequay.app.developmentflow.task.TaskBrainConversationRuntime;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTypedUserWaitContinuation
{
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");
    private static final ActiveAgentContextRegistry.TypedOwner OWNER =
            new ActiveAgentContextRegistry.TypedOwner(
                    DispatchTicket.OwnerKind.TASK_TURN,
                    "plan-turn", "plan-operation");

    @Test
    void restartRedrivesReadyWaitUntilTheStableSuccessorIsPhysical()
    {
        Harness harness = harness(OWNER);
        when(harness.plans().continueUserWait(
                anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("plan-next");
        when(harness.waits().typedTurnExists(
                DispatchTicket.OwnerKind.TASK_TURN, "plan-next"))
                .thenReturn(false, true);

        harness.runtime().maintain(NOW);
        runtime(harness).maintain(NOW.plusSeconds(1));

        verify(harness.plans(), times(2)).continueUserWait(
                "plan-turn", "plan-operation", "QUESTION", "question-1", "Proceed");
        verify(harness.waits(), times(1)).markContinuationDispatched(
                "QUESTION", "question-1", "plan-next");
    }

    @Test
    void staleOwnerIsSupersededWithoutInventingASuccessor()
    {
        Harness harness = harness(OWNER);
        when(harness.plans().continueUserWait(
                anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        harness.runtime().maintain(NOW);

        verify(harness.waits()).markContinuationSuperseded(
                "QUESTION", "question-1",
                "Plan owner or subject is no longer current");
        verify(harness.waits(), never()).markContinuationDispatched(
                anyString(), anyString(), anyString());
    }

    @Test
    void restartRedrivesTaskBrainReviewThroughItsOwnerRuntime()
    {
        Harness harness = harness(OWNER);
        TaskBrainConversationRuntime taskBrain = mock(
                TaskBrainConversationRuntime.class);
        when(harness.taskBrainProvider().getIfAvailable()).thenReturn(taskBrain);
        when(harness.waits().requireWaitOwnerContext(OWNER)).thenReturn(
                new V2UserWaitStore.WaitOwnerContext(
                        "trunk-1", "task-1", 3L, "stage-1", 2L, 1,
                        "code-1", "head-1", "base-1",
                        "LOCAL_DEVELOPMENT", "DEVELOPMENT_BRAIN_REVIEW"));
        when(taskBrain.continueUserWait(
                anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("brain-next");
        when(harness.waits().typedTurnExists(
                DispatchTicket.OwnerKind.TASK_TURN, "brain-next"))
                .thenReturn(false, true);

        harness.runtime().maintain(NOW);
        runtime(harness).maintain(NOW.plusSeconds(1));

        verify(taskBrain, times(2)).continueUserWait(
                "plan-turn", "plan-operation", "QUESTION", "question-1", "Proceed");
        verify(harness.plans(), never()).continueUserWait(
                anyString(), anyString(), anyString(), anyString(), anyString());
        verify(harness.waits()).markContinuationDispatched(
                "QUESTION", "question-1", "brain-next");
    }

    @Test
    void siblingWaitReceiptCannotBeConsumed()
    {
        ActiveAgentContextRegistry.TypedOwner sibling =
                new ActiveAgentContextRegistry.TypedOwner(
                        DispatchTicket.OwnerKind.TASK_TURN,
                        "sibling-turn", "plan-operation");
        Harness harness = harness(OWNER);
        when(harness.waits().findUserWaitResult("plan-operation"))
                .thenReturn(Optional.of(new V2UserWaitStore.UserWaitReceipt(
                        sibling, "QUESTION", "question-1",
                        "digest", "evidence", NOW)));

        assertThatThrownBy(() -> harness.runtime().resumeQuestion("question-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
        verify(harness.plans(), never()).continueUserWait(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @SuppressWarnings("unchecked")
    private static Harness harness(
            ActiveAgentContextRegistry.TypedOwner owner)
    {
        V2UserWaitStore waits = mock(V2UserWaitStore.class);
        V2UserWaitStore.Question question = new V2UserWaitStore.Question(
                "question-1", owner, "trunk-1", "task-1", "stage-1",
                "call-1", "Continue?", null, "[]", true,
                "ANSWERED", "Proceed", null, "Proceed", "user", 1,
                "READY", null, null, NOW.minusSeconds(1), NOW);
        when(waits.findQuestion("question-1"))
                .thenReturn(Optional.of(question));
        when(waits.listReadyContinuations(32)).thenReturn(List.of(
                new V2UserWaitStore.ReadyContinuation(
                        owner.kind(), "QUESTION", "question-1")));
        when(waits.findUserWaitResult(owner.operationId()))
                .thenReturn(Optional.of(new V2UserWaitStore.UserWaitReceipt(
                        owner, "QUESTION", "question-1",
                        "digest", "evidence", NOW)));
        when(waits.requireWaitOwnerContext(owner)).thenReturn(
                new V2UserWaitStore.WaitOwnerContext(
                        "trunk-1", "task-1", 3L, "stage-1", 2L, 1,
                        "code-1", "head-1", "base-1", "PLAN", "PLAN_DRAFT"));
        PlanRuntimeCoordinator plans = mock(PlanRuntimeCoordinator.class);
        ObjectProvider<PlanRuntimeCoordinator> planProvider =
                mock(ObjectProvider.class);
        when(planProvider.getIfAvailable()).thenReturn(plans);
        ObjectProvider<V2StageSteeringRuntime> stageProvider =
                mock(ObjectProvider.class);
        ObjectProvider<ReviewAssignmentTurnRuntime> reviewProvider =
                mock(ObjectProvider.class);
        ObjectProvider<TaskBrainConversationRuntime> taskBrainProvider =
                mock(ObjectProvider.class);
        Harness harness = new Harness(
                waits, mock(TrunkUserWaitContinuation.class), plans,
                planProvider, stageProvider, reviewProvider, taskBrainProvider, null);
        return new Harness(
                waits, harness.trunks(), plans, planProvider, stageProvider,
                reviewProvider, taskBrainProvider, runtime(harness));
    }

    private static TypedUserWaitContinuation runtime(Harness harness)
    {
        return new TypedUserWaitContinuation(
                harness.waits(), harness.trunks(), harness.planProvider(),
                harness.stageProvider(), harness.reviewProvider(),
                harness.taskBrainProvider());
    }

    private record Harness(
            V2UserWaitStore waits,
            TrunkUserWaitContinuation trunks,
            PlanRuntimeCoordinator plans,
            ObjectProvider<PlanRuntimeCoordinator> planProvider,
            ObjectProvider<V2StageSteeringRuntime> stageProvider,
            ObjectProvider<ReviewAssignmentTurnRuntime> reviewProvider,
            ObjectProvider<TaskBrainConversationRuntime> taskBrainProvider,
            TypedUserWaitContinuation runtime) {}
}
