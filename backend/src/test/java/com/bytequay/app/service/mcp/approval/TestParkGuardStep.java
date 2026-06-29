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
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.mcp.McpResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
    void fallsThroughWhenNothingIsParked()
    {
        Task running = task(TaskStatus.RUNNING);
        when(taskStore.listTasksByThread("thread-1")).thenReturn(List.of(running));
        when(taskStore.hasActiveTask("thread-1")).thenReturn(true);

        assertThat(step.apply(ctx("Read")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    private static Task task(TaskStatus status)
    {
        Task t = mock(Task.class);
        when(t.status()).thenReturn(status);
        return t;
    }

    private ApprovalContext ctx(String toolName)
    {
        return new ApprovalContext(
                "thread-1", JsonNodeFactory.instance.numberNode(1),
                toolName, "call-1", mapper.createObjectNode(), Set.of());
    }
}
