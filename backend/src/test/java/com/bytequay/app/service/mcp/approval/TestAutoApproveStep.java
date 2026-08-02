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
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.mcp.McpResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAutoApproveStep
{
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskStore legacyTasks = mock(TaskStore.class);
    private final TaskManager.Store v2Tasks = mock(TaskManager.Store.class);
    private final AutoApproveStep step = new AutoApproveStep(
            legacyTasks, v2Tasks, new McpResponses(mapper));

    @Test
    void typedTurnUsesEffectiveV2ApprovalInsteadOfTheLegacyTaskColumn()
    {
        when(legacyTasks.isAutoApprove("task-1")).thenReturn(false);
        when(v2Tasks.effectiveAutoApprove("task-1")).thenReturn(true);

        assertThat(step.apply(ctx(true)))
                .isInstanceOf(ApprovalStepResult.Resolve.class);
        verify(legacyTasks, never()).isAutoApprove("task-1");
    }

    @Test
    void typedTurnWithoutAutoApproveFallsThroughToItsDurablePermissionOwner()
    {
        when(legacyTasks.isAutoApprove("task-1")).thenReturn(true);
        when(v2Tasks.effectiveAutoApprove("task-1")).thenReturn(false);

        assertThat(step.apply(ctx(true)))
                .isInstanceOf(ApprovalStepResult.Continue.class);
        verify(legacyTasks, never()).isAutoApprove("task-1");
    }

    @Test
    void legacyTurnStillUsesItsCompatibilityColumn()
    {
        when(legacyTasks.isAutoApprove("task-1")).thenReturn(true);

        assertThat(step.apply(ctx(false)))
                .isInstanceOf(ApprovalStepResult.Resolve.class);
        verify(v2Tasks, never()).effectiveAutoApprove("task-1");
    }

    private ApprovalContext ctx(boolean typed)
    {
        return new ApprovalContext(
                "thread-1", "task-1", "agent-1",
                JsonNodeFactory.instance.numberNode(1),
                "Bash", "call-1",
                mapper.createObjectNode().put("command", "touch output"),
                Set.of(), typed);
    }
}
