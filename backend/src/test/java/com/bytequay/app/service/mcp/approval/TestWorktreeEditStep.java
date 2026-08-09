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

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.sqlite.SqliteAgentRunStore;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.agents.ResolvedAgentContext;
import com.bytequay.app.service.mcp.McpResponses;
import com.bytequay.app.service.skills.ByteQuayRole;
import com.bytequay.app.service.tools.AgentRole;
import com.bytequay.app.service.tools.PermissionResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestWorktreeEditStep
{
    private static final String WORKTREE = "/repo/.worktrees/task-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private final McpResponses responses = new McpResponses(mapper);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final SqliteAgentRunStore agentRuns = mock(SqliteAgentRunStore.class);
    private final ActiveAgentContextRegistry activeContexts =
            new ActiveAgentContextRegistry();
    private final WorktreeEditStep step = new WorktreeEditStep(
            taskStore, agentRuns, activeContexts, responses);

    @Test
    void allowsEditInWorktreeDuringAWorkStage()
    {
        arm(TaskPhase.IMPLEMENTING);
        assertThat(step.apply(editCtx(WORKTREE + "/src/Foo.java")))
                .isInstanceOf(ApprovalStepResult.Resolve.class);
    }

    @Test
    void allowsEditInWorktreeWithALiveAgentRun()
    {
        // CI-fixing doesn't move the task's phase anymore — a live ci_fix
        // run's edits to the same worktree are auto-approved via the run
        // check instead of the stage check.
        arm(TaskPhase.PUSHED_AWAITING_CI);
        when(agentRuns.findLiveByTask("task-1")).thenReturn(List.of(mock(AgentRun.class)));
        assertThat(step.apply(editCtx(WORKTREE + "/src/Foo.java")))
                .isInstanceOf(ApprovalStepResult.Resolve.class);
    }

    @Test
    void promptsForEditOutsideWorktree()
    {
        arm(TaskPhase.IMPLEMENTING);
        assertThat(step.apply(editCtx("/repo/other/Bar.java")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    @Test
    void promptsForPathEscapingTheWorktree()
    {
        arm(TaskPhase.IMPLEMENTING);
        assertThat(step.apply(editCtx(WORKTREE + "/../../etc/passwd")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    @Test
    void promptsForBashEvenInsideTheWorktree()
    {
        JsonNode input = mapper.createObjectNode().put("command", "git push");
        ApprovalContext ctx = new ApprovalContext(
                "thread-1", JsonNodeFactory.instance.numberNode(1),
                "Bash", "call-1", input, ImmutableSet.of());
        assertThat(step.apply(ctx)).isInstanceOf(ApprovalStepResult.Continue.class);
    }

    @Test
    void promptsForEditOutsideAWorkStage()
    {
        // PlanStage is read-only; an edit there is not auto-approved.
        arm(TaskPhase.PLANNING);
        assertThat(step.apply(editCtx(WORKTREE + "/src/Foo.java")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    @Test
    void typedLocalStageUsesItsExactRuntimeContextNotTheLegacyTaskPhase()
    {
        when(taskStore.findTaskById("task-1"))
                .thenReturn(Optional.of(task(TaskPhase.PLANNING, "")));
        String agentKey = armTyped(
                DispatchTicket.OwnerKind.STAGE_TURN,
                StageType.DEVELOPMENT_STAGE);

        assertThat(step.apply(typedEditCtx(
                WORKTREE + "/src/Foo.java", agentKey)))
                .isInstanceOf(ApprovalStepResult.Resolve.class);
        verify(taskStore, never()).findTaskById("task-1");
        verify(agentRuns, never()).findLiveByTask("task-1");
    }

    @Test
    void typedTaskTurnCannotBorrowStageEditAuthority()
    {
        arm(TaskPhase.IMPLEMENTING);
        String agentKey = armTyped(
                DispatchTicket.OwnerKind.TASK_TURN, null);

        assertThat(step.apply(typedEditCtx(
                WORKTREE + "/src/Foo.java", agentKey)))
                .isInstanceOf(ApprovalStepResult.Continue.class);
        verify(agentRuns, never()).findLiveByTask("task-1");
    }

    @Test
    void typedLocalStageStillRejectsAPathOutsideItsWorktree()
    {
        arm(TaskPhase.PLANNING);
        String agentKey = armTyped(
                DispatchTicket.OwnerKind.STAGE_TURN,
                StageType.DEVELOPMENT_STAGE);

        assertThat(step.apply(typedEditCtx(
                "/repo/other/Bar.java", agentKey)))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    private void arm(TaskPhase phase)
    {
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(task(phase)));
    }

    private ApprovalContext editCtx(String filePath)
    {
        JsonNode input = mapper.createObjectNode().put("file_path", filePath);
        // The gate resolves the task from the turn's stamped task_id (task-1),
        // not a thread-level "active task" guess.
        return new ApprovalContext(
                "thread-1", "task-1", JsonNodeFactory.instance.numberNode(1),
                "Edit", "call-1", input, ImmutableSet.of());
    }

    private ApprovalContext typedEditCtx(String filePath, String agentKey)
    {
        JsonNode input = mapper.createObjectNode().put("file_path", filePath);
        return new ApprovalContext(
                "thread-1", "task-1", agentKey,
                JsonNodeFactory.instance.numberNode(1),
                "Edit", "call-1", input, ImmutableSet.of(), true);
    }

    private String armTyped(
            DispatchTicket.OwnerKind kind, StageType stageType)
    {
        String agentKey = "typed-agent-1";
        activeContexts.put(
                "thread-1",
                agentKey,
                new ResolvedAgentContext(
                        ByteQuayRole.TASK, "1", AgentRole.TASK, stageType,
                        ImmutableSet.of(), List.of(), ImmutableSet.of(), ImmutableSet.of("approval_prompt")),
                PermissionResolver.RunningScope.NONE,
                new ActiveAgentContextRegistry.TypedOwner(
                        kind, "turn-1", "operation-1"),
                WORKTREE);
        return agentKey;
    }

    private static Task task(TaskPhase phase)
    {
        return task(phase, WORKTREE);
    }

    private static Task task(TaskPhase phase, String worktree)
    {
        return new Task(
                "task-1", "thread-1", 1L, TaskStatus.RUNNING,
                "feature/x", worktree, "main", worktree,
                null, null, null, null, null,
                "DEVELOP", null, null,
                0L, 0L, 0L, null,
                Instant.EPOCH, null, null, null, null, null,
                null, phase, null, 0, null);
    }
}
