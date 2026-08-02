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

import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.mcp.McpResponses;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.tools.SecurityType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestUnattendedStep
{
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpResponses responses = new McpResponses(mapper);
    private final ThreadTurnStore turnStore = mock(ThreadTurnStore.class);
    private final ThreadService threads = mock(ThreadService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final UnattendedStep step = new UnattendedStep(turnStore, threads, notifications, responses);

    @Test
    void anAttendedTurnIsLeftToTheNormalPromptFlow()
    {
        stubRunningTurn(/* attended */ true, "task-1");
        assertThat(step.apply(ctx("Bash", Set.of())))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    @Test
    void noRunningTurnFallsThrough()
    {
        when(turnStore.listTurnsByTaskIdAndStatus("thread-1", ThreadTurnStatus.RUNNING, 1))
                .thenReturn(List.of());
        assertThat(step.apply(ctx("Bash", Set.of())))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    @Test
    void unattendedToolWithinTheAutonomyEnvelopeIsAutoAllowed()
    {
        stubRunningTurn(/* attended */ false, "task-1");
        // Read maps to CODE_READ, which the turn was granted.
        assertThat(step.apply(ctx("Read", Set.of(SecurityType.CODE_READ))))
                .isInstanceOf(ApprovalStepResult.Resolve.class);
        verify(notifications, never()).notifyNeedsAttention(anyString(), anyString(), anyString());
    }

    @Test
    void unattendedToolOutsideTheEnvelopeIsDeniedAndEscalated()
    {
        stubRunningTurn(/* attended */ false, "task-1");
        // Bash maps to CODE_EXEC, which was NOT granted — deny + escalate.
        ApprovalStepResult result = step.apply(ctx("Bash", Set.of(SecurityType.CODE_READ)));

        assertThat(result).isInstanceOf(ApprovalStepResult.Resolve.class);
        assertThat(((ApprovalStepResult.Resolve) result).response().toString()).contains("deny");
        verify(notifications).notifyNeedsAttention(eq("thread-1"), eq("task-1"), anyString());
    }

    @Test
    void typedTurnNeverConsultsTheRetiredLegacyUnattendedState()
    {
        ApprovalContext typed = new ApprovalContext(
                "thread-1", "task-1", "typed-agent-1",
                JsonNodeFactory.instance.numberNode(1),
                "Bash", "call-1", mapper.createObjectNode(),
                Set.of(SecurityType.CODE_EXEC), true);

        assertThat(step.apply(typed))
                .isInstanceOf(ApprovalStepResult.Continue.class);
        verifyNoInteractions(turnStore, threads, notifications);
    }

    private void stubRunningTurn(boolean attended, String taskId)
    {
        ThreadTurn turn = mock(ThreadTurn.class);
        when(turn.initiator()).thenReturn(new TurnInitiator(attended, "source"));
        when(turn.taskId()).thenReturn(taskId);
        when(turnStore.listTurnsByTaskIdAndStatus("thread-1", ThreadTurnStatus.RUNNING, 1))
                .thenReturn(List.of(turn));
    }

    private ApprovalContext ctx(String toolName, Set<SecurityType> grants)
    {
        return new ApprovalContext(
                "thread-1", JsonNodeFactory.instance.numberNode(1),
                toolName, "call-1", mapper.createObjectNode(), grants);
    }
}
