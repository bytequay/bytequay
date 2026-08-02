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

import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.mcp.McpResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTypedPolicyGuardStep
{
    private final TaskManager.Store tasks = mock(TaskManager.Store.class);
    private final TypedPolicyGuardStep step = new TypedPolicyGuardStep(
            tasks, new McpResponses(new ObjectMapper()));

    @Test
    void missingTypedTaskPolicyFailsClosed()
    {
        when(tasks.findPolicy("task-1")).thenReturn(Optional.empty());

        assertThat(step.apply(context(true, "task-1")))
                .isInstanceOf(ApprovalStepResult.Resolve.class);
    }

    @Test
    void presentTypedTaskPolicyContinuesToTheRemainingPolicy()
    {
        when(tasks.findPolicy("task-1")).thenReturn(Optional.of(policy()));

        assertThat(step.apply(context(true, "task-1")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    @Test
    void legacyAndTrunkCallbacksDoNotRequireAV2TaskPolicy()
    {
        assertThat(step.apply(context(false, "task-1")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
        assertThat(step.apply(context(true, null)))
                .isInstanceOf(ApprovalStepResult.Continue.class);
        verify(tasks, never()).findPolicy("task-1");
    }

    private static ApprovalContext context(boolean typed, String taskId)
    {
        return new ApprovalContext(
                "thread-1", taskId, "agent-1",
                JsonNodeFactory.instance.numberNode(1),
                "Bash", "call-1",
                JsonNodeFactory.instance.objectNode(), Set.of(), typed);
    }

    private static TaskManager.PolicyRevision policy()
    {
        return new TaskManager.PolicyRevision(
                "policy-1", "task-1", "thread-1", 1,
                false, false, 0, 3, 3, true, "permission-policy-1");
    }
}
