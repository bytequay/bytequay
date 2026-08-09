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

import com.bytequay.app.service.mcp.McpResponses;
import com.bytequay.app.service.threads.ThreadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestBudgetStep
{
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpResponses responses = new McpResponses(mapper);
    private final ThreadService threads = mock(ThreadService.class);
    private final BudgetStep step = new BudgetStep(threads, responses);

    @Test
    void consumesAPreApprovedBudgetAndAutoAllows()
    {
        when(threads.tryConsumeToolBudget("thread-1", "stage-1", "Bash"))
                .thenReturn(OptionalInt.of(3));

        assertThat(step.apply(ctx("Bash")))
                .isInstanceOf(ApprovalStepResult.Resolve.class);
        // The auto-allow is surfaced to the user with the remaining count.
        verify(threads).notifyPermissionAutoAllowed(
                "thread-1", "stage-1", "call-1", "Bash", 3);
    }

    @Test
    void fallsThroughWhenNoBudgetIsGranted()
    {
        when(threads.tryConsumeToolBudget("thread-1", "stage-1", "Bash"))
                .thenReturn(OptionalInt.empty());

        assertThat(step.apply(ctx("Bash")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
        verify(threads, never())
                .notifyPermissionAutoAllowed(anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void typedTurnSkipsTheRetiredLegacyBudgetSession()
    {
        assertThat(step.apply(typedCtx("Bash")))
                .isInstanceOf(ApprovalStepResult.Continue.class);

        verify(threads, never()).tryConsumeToolBudget(
                anyString(), anyString(), anyString());
        verify(threads, never()).notifyPermissionAutoAllowed(
                anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    private ApprovalContext ctx(String toolName)
    {
        return new ApprovalContext(
                "thread-1", "task-1", "stage-1", JsonNodeFactory.instance.numberNode(1),
                toolName, "call-1", mapper.createObjectNode(), ImmutableSet.of());
    }

    private ApprovalContext typedCtx(String toolName)
    {
        return new ApprovalContext(
                "thread-1", "task-1", "v2-stage-turn:turn-1:operation-1",
                JsonNodeFactory.instance.numberNode(1), toolName, "call-1",
                mapper.createObjectNode(), ImmutableSet.of(), true);
    }
}
