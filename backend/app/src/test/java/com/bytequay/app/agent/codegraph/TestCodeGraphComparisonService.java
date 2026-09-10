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
package com.bytequay.app.agent.codegraph;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.CodeGraphArm;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.ToolCounters;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.app.agent.codegraph.CodeGraphComparison.ArmComparison;
import com.bytequay.app.agent.codegraph.CodeGraphComparison.RepositoryComparison;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.agent.core.AgentSandboxPolicy.WORKSPACE_WRITE;
import static com.bytequay.agent.spi.CostProvenance.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestCodeGraphComparisonService
{
    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final CodeGraphComparisonService service = new CodeGraphComparisonService(tasks, turns);
    private int keys;

    @Test
    void groupsStampedTasksByRepositoryAndArmWithMediansPerFinishedTurn()
    {
        AgentTask on = task("workspace-a", CodeGraphArm.ON, NOW);
        AgentTask onAgain = task("workspace-a", CodeGraphArm.ON, NOW.plusSeconds(1));
        AgentTask off = task("workspace-a", CodeGraphArm.OFF, NOW.plusSeconds(2));
        AgentTask elsewhere = task("workspace-b", "bytequay/other", CodeGraphArm.OFF, NOW.plusSeconds(3));
        when(tasks.listWithCodeGraphArm()).thenReturn(ImmutableList.of(on, onAgain, off, elsewhere));
        when(turns.list(on.id())).thenReturn(ImmutableList.of(
                finished(on, 100, 10, new ToolCounters(4, 2, 1, 1500, 0), Duration.ofMinutes(2)),
                finished(on, 300, 30, new ToolCounters(8, 6, 3, 4500, 1), Duration.ofMinutes(4)),
                AgentTurn.accepted(TurnId.random(), on.id(), "still-open", settings(), NOW)));
        when(turns.list(onAgain.id())).thenReturn(ImmutableList.of(finished(onAgain, 200, 20, new ToolCounters(6, 4, 2, 3000, 0), Duration.ofMinutes(3))));
        when(turns.list(off.id())).thenReturn(ImmutableList.of(finished(off, 500, 50, new ToolCounters(10, 9, 0, 0, 0), Duration.ofMinutes(5))));
        when(turns.list(elsewhere.id())).thenReturn(ImmutableList.of());

        CodeGraphComparison comparison = service.compare();

        assertThat(comparison.repositories()).extracting(RepositoryComparison::repository).containsExactly("bytequay/sample", "bytequay/other");
        RepositoryComparison first = comparison.repositories().getFirst();
        assertThat(first.arms()).extracting(ArmComparison::arm).containsExactly(CodeGraphArm.ON, CodeGraphArm.OFF);
        ArmComparison onArm = first.arms().getFirst();
        assertThat(onArm.taskCount()).isEqualTo(2);
        assertThat(onArm.turnCount()).isEqualTo(3);
        assertThat(onArm.turnsPerTask()).isEqualTo(1.5);
        assertThat(onArm.smallSample()).isTrue();
        assertThat(onArm.inputTokens()).contains(200.0);
        assertThat(onArm.outputTokens()).contains(20.0);
        assertThat(onArm.toolCalls()).contains(6.0);
        assertThat(onArm.exploreActions()).contains(4.0);
        assertThat(onArm.minutes()).contains(3.0);
        assertThat(onArm.graphCalls()).contains(2.0);
        assertThat(onArm.graphSeconds()).contains(3.0);
        ArmComparison offArm = first.arms().getLast();
        assertThat(offArm.taskCount()).isEqualTo(1);
        assertThat(offArm.inputTokens()).contains(500.0);
        assertThat(offArm.graphCalls()).isEmpty();
        assertThat(offArm.graphSeconds()).isEmpty();
        RepositoryComparison second = comparison.repositories().getLast();
        assertThat(second.arms()).hasSize(1);
        assertThat(second.arms().getFirst().turnCount()).isZero();
        assertThat(second.arms().getFirst().inputTokens()).isEmpty();
    }

    @Test
    void twoWorkspacesWatchingOneRepositoryShareOneComparison()
    {
        AgentTask here = task("workspace-a", "bytequay/sample", CodeGraphArm.ON, NOW);
        AgentTask there = task("workspace-b", "bytequay/sample", CodeGraphArm.ON, NOW.plusSeconds(1));
        when(tasks.listWithCodeGraphArm()).thenReturn(ImmutableList.of(here, there));
        when(turns.list(here.id())).thenReturn(ImmutableList.of());
        when(turns.list(there.id())).thenReturn(ImmutableList.of());

        CodeGraphComparison comparison = service.compare();

        assertThat(comparison.repositories()).extracting(RepositoryComparison::repository).containsExactly("bytequay/sample");
        assertThat(comparison.repositories().getFirst().arms().getFirst().taskCount()).isEqualTo(2);
    }

    @Test
    void medianOfAnEvenCountAveragesTheMiddlePair()
    {
        AgentTask task = task("workspace-a", CodeGraphArm.OFF, NOW);
        assertThat(CodeGraphComparisonService.median(ImmutableList.of(
                finished(task, 100, 1, ToolCounters.ZERO, Duration.ZERO).metrics(),
                finished(task, 400, 1, ToolCounters.ZERO, Duration.ZERO).metrics(),
                finished(task, 300, 1, ToolCounters.ZERO, Duration.ZERO).metrics(),
                finished(task, 200, 1, ToolCounters.ZERO, Duration.ZERO).metrics()), TurnMetrics::inputTokens)).contains(250.0);
        assertThat(CodeGraphComparisonService.median(ImmutableList.of(), TurnMetrics::inputTokens)).isEmpty();
    }

    private AgentTurn finished(AgentTask task, long inputTokens, long outputTokens, ToolCounters counters, Duration elapsed)
    {
        return AgentTurn.accepted(TurnId.random(), task.id(), "key-" + keys++, settings(), NOW)
                .markDeliveryStarted(NOW)
                .running(new ProviderTurnRef("provider-turn"), Optional.empty())
                .finish(TurnState.COMPLETED, new TurnMetrics(inputTokens, 0, outputTokens, Optional.empty(), UNAVAILABLE, elapsed, counters), Optional.empty(), NOW.plusSeconds(1));
    }

    private static AgentTask task(String workspaceId, CodeGraphArm arm, Instant createdAt)
    {
        return task(workspaceId, "bytequay/sample", arm, createdAt);
    }

    private static AgentTask task(String workspaceId, String repository, CodeGraphArm arm, Instant createdAt)
    {
        TaskId taskId = TaskId.random();
        TaskWorktree worktree = new TaskWorktree(
                taskId,
                workspaceId,
                RepositoryIdentity.valueOf(repository),
                Path.of("/tmp/bytequay-worktrees").resolve(taskId.toString()),
                "bytequay/task/" + taskId,
                "0123456789012345678901234567890123456789");
        return AgentTask.created(
                taskId,
                true,
                Optional.empty(),
                "Task",
                TitleSource.USER,
                AgentLaneId.CODEX_CLI,
                worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                Optional.of(arm),
                createdAt);
    }

    private static AgentTurnSettings settings()
    {
        return new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, WORKSPACE_WRITE, Duration.ofMinutes(5), ImmutableList.of());
    }
}
