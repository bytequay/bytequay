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
package com.bytequay.app.service.mcp.approval;

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.mcp.McpResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestParkGuardStep
{
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpResponses responses = new McpResponses(mapper);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final ParkGuardStep step = new ParkGuardStep(taskStore, responses);

    @Test
    void deniesEveryToolWhileATaskNeedsAttention()
    {
        Task needsAttention = task(TaskStatus.NEEDS_ATTENTION);
        when(taskStore.listTasksByThread("thread-1")).thenReturn(List.of(needsAttention));

        ApprovalStepResult result = step.apply(ctx("Read"));
        assertThat(result).isInstanceOf(ApprovalStepResult.Resolve.class);
        assertThat(((ApprovalStepResult.Resolve) result).response().toString()).contains("deny");
    }

    @Test
    void aParkedSiblingDoesNotBlockATaskScopedToolCall()
    {
        Task parked = task("task-1", TaskStatus.NEEDS_ATTENTION);
        Task running = task("task-2", TaskStatus.RUNNING);
        when(taskStore.listTasksByThread("thread-1")).thenReturn(List.of(parked, running));
        when(taskStore.hasActiveTask("thread-1")).thenReturn(true);

        assertThat(step.apply(ctx("task-2", "Edit")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    @Test
    void phaseOnlyNeedsAttentionStillBlocksItsTask()
    {
        Task parked = task("task-1", TaskStatus.IDLE);
        when(parked.phase()).thenReturn(TaskPhase.NEEDS_ATTENTION);
        when(taskStore.listTasksByThread("thread-1")).thenReturn(List.of(parked));

        assertThat(step.apply(ctx("task-1", "Edit")))
                .isInstanceOf(ApprovalStepResult.Resolve.class);
    }

    @Test
    void deniesWhenParkedAtTheReviewGateWithNoActiveTask()
    {
        Task awaitingReview = task(TaskStatus.AWAITING_REVIEW);
        when(taskStore.listTasksByThread("thread-1")).thenReturn(List.of(awaitingReview));
        when(taskStore.hasActiveTask("thread-1")).thenReturn(false);

        assertThat(step.apply(ctx("Read")))
                .isInstanceOf(ApprovalStepResult.Resolve.class);
    }

    @Test
    void allowsThroughWhenATaskIsStillActivelyRunning()
    {
        // A parked-for-review task exists, but another task is active —
        // the thread isn't blocked, so tool calls flow on.
        Task awaitingReview = task(TaskStatus.AWAITING_REVIEW);
        when(taskStore.listTasksByThread("thread-1")).thenReturn(List.of(awaitingReview));
        when(taskStore.hasActiveTask("thread-1")).thenReturn(true);

        assertThat(step.apply(ctx("Read")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    @Test
    void legacyShipParkDoesNotBlockTheCanonicalLocalFixLoop()
    {
        Task stalePark = task("task-1", TaskStatus.AWAITING_REVIEW);
        when(stalePark.phase()).thenReturn(TaskPhase.ADDRESSING_LOCAL_COMMENTS);
        when(taskStore.listTasksByThread("thread-1")).thenReturn(List.of(stalePark));
        when(taskStore.hasActiveTask("thread-1")).thenReturn(false);

        assertThat(step.apply(ctx("task-1", "Edit")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    @Test
    void legacyExemptionDoesNotUnblockASiblingPublishGate()
    {
        Task stalePark = task("task-1", TaskStatus.AWAITING_REVIEW);
        when(stalePark.phase()).thenReturn(TaskPhase.INTERNAL_REVIEW);
        Task realGate = task("task-2", TaskStatus.AWAITING_REVIEW);
        when(taskStore.listTasksByThread("thread-1")).thenReturn(List.of(stalePark, realGate));
        when(taskStore.hasActiveTask("thread-1")).thenReturn(false);

        assertThat(step.apply(ctx("task-2", "Edit")))
                .isInstanceOf(ApprovalStepResult.Resolve.class);
    }

    @Test
    void legacyExemptionDoesNotUnblockAnUnscopedTrunkCall()
    {
        Task stalePark = task("task-1", TaskStatus.AWAITING_REVIEW);
        when(stalePark.phase()).thenReturn(TaskPhase.INTERNAL_REVIEW);
        when(taskStore.listTasksByThread("thread-1")).thenReturn(List.of(stalePark));
        when(taskStore.hasActiveTask("thread-1")).thenReturn(false);

        assertThat(step.apply(ctx("Edit")))
                .isInstanceOf(ApprovalStepResult.Resolve.class);
    }

    @Test
    void fallsThroughWhenNothingIsParked()
    {
        Task running = task(TaskStatus.RUNNING);
        when(taskStore.listTasksByThread("thread-1")).thenReturn(List.of(running));
        when(taskStore.hasActiveTask("thread-1")).thenReturn(true);

        assertThat(step.apply(ctx("Read")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    @Test
    void typedTurnNeverConsultsTheRetiredLegacyParkState()
    {
        ApprovalContext typed = new ApprovalContext(
                "thread-1", "task-1", "typed-agent-1",
                JsonNodeFactory.instance.numberNode(1),
                "Edit", "call-1", mapper.createObjectNode(), ImmutableSet.of(), true);

        assertThat(step.apply(typed))
                .isInstanceOf(ApprovalStepResult.Continue.class);
        verifyNoInteractions(taskStore);
    }

    private static Task task(TaskStatus status)
    {
        return task("task-1", status);
    }

    private static Task task(String id, TaskStatus status)
    {
        Task t = mock(Task.class);
        when(t.id()).thenReturn(id);
        when(t.status()).thenReturn(status);
        return t;
    }

    private ApprovalContext ctx(String toolName)
    {
        return new ApprovalContext(
                "thread-1", JsonNodeFactory.instance.numberNode(1),
                toolName, "call-1", mapper.createObjectNode(), ImmutableSet.of());
    }

    private ApprovalContext ctx(String taskId, String toolName)
    {
        return new ApprovalContext(
                "thread-1", taskId, JsonNodeFactory.instance.numberNode(1),
                toolName, "call-1", mapper.createObjectNode(), ImmutableSet.of());
    }
}
