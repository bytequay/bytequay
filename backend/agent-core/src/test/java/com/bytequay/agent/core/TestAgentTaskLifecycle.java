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
package com.bytequay.agent.core;

import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAgentTaskLifecycle
{
    private static final Instant CREATED_AT = Instant.parse("2026-08-16T00:00:00Z");
    private static final Path WORKTREE = Path.of("/tmp/bytequay-worktree");

    @Test
    void openTaskCanComplete()
    {
        AgentTask task = newTask();
        Instant completedAt = CREATED_AT.plusSeconds(4);
        TaskSummary summary = summary();

        AgentTask completed = task.complete(summary, completedAt, completedAt.plus(7, ChronoUnit.DAYS));

        assertThat(completed.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(completed.summary()).contains(summary);
        assertThat(completed.summary().orElseThrow().tests()).isInstanceOf(ImmutableList.class);
        assertThat(completed.summary().orElseThrow().learnings()).isInstanceOf(ImmutableList.class);
        assertThat(completed.terminalAt()).contains(completedAt);
        assertThat(completed.cleanupState()).isEqualTo(CleanupState.RETAINED);
    }

    @Test
    void openTaskCanFail()
    {
        AgentTask task = newTask();
        Instant failedAt = CREATED_AT.plusSeconds(1);

        AgentTask failed = task.fail("provider unavailable", failedAt, failedAt.plus(7, ChronoUnit.DAYS));

        assertThat(failed.state()).isEqualTo(TaskState.FAILED);
        assertThat(failed.terminalReason()).contains("provider unavailable");
    }

    @Test
    void taskUpdateTimeCannotMoveBackwards()
    {
        AgentTask open = newTask().withMetrics(TaskMetrics.empty(), CREATED_AT.plusSeconds(2));

        assertThatThrownBy(() -> open.withMetrics(TaskMetrics.empty(), CREATED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before");
    }

    @Test
    void aChosenTitleIsFinalAndOnlyAProvisionalOneTakesAProposal()
    {
        AgentTask fixed = newTask();
        assertThat(fixed.titleSource()).isEqualTo(TitleSource.USER);
        assertThatThrownBy(() -> fixed.withTitle("Proposed", TitleSource.AGENT, CREATED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provisional");
        assertThatThrownBy(() -> fixed.withTitle("Back", TitleSource.PROVISIONAL, CREATED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        AgentTask renamed = fixed.withTitle("Chosen", TitleSource.USER, CREATED_AT.plusSeconds(1));
        assertThat(renamed.title()).isEqualTo("Chosen");
        assertThat(renamed.updatedAt()).isEqualTo(CREATED_AT.plusSeconds(1));

        TaskId taskId = TaskId.random();
        AgentTask provisional = AgentTask.created(
                taskId,
                true,
                "first message",
                TitleSource.PROVISIONAL,
                AgentLaneId.CODEX_CLI,
                new TaskWorktree(
                        taskId,
                        "workspace-1",
                        new RepositoryIdentity("bytequay", "bytequay"),
                        Path.of("/worktrees").resolve(taskId.toString()),
                        "bytequay/task/" + taskId,
                        "0123456789012345678901234567890123456789"),
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                CREATED_AT);
        AgentTask proposed = provisional.withTitle("Agent proposal", TitleSource.AGENT, CREATED_AT.plusSeconds(1));
        assertThat(proposed.titleSource()).isEqualTo(TitleSource.AGENT);
        // The agent proposes once; only a person changes it after that.
        assertThatThrownBy(() -> proposed.withTitle("Again", TitleSource.AGENT, CREATED_AT.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(proposed.withTitle("Mine", TitleSource.USER, CREATED_AT.plusSeconds(2)).titleSource())
                .isEqualTo(TitleSource.USER);
    }

    @Test
    void interactiveTaskRequiresWorkspace()
    {
        assertThatThrownBy(() -> AgentTask.created(
                TaskId.random(),
                Optional.empty(),
                true,
                "Interactive task",
                AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("bytequay", "bytequay"),
                WORKTREE,
                AgentPermissionPolicy.AUTO,
                CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspaceId");
    }

    @Test
    void terminalTaskCleanupPreservesTerminalState()
    {
        Instant completedAt = CREATED_AT.plusSeconds(4);
        AgentTask completed = newTask().complete(summary(), completedAt, completedAt.plus(7, ChronoUnit.DAYS));

        AgentTask due = completed.withCleanupState(
                CleanupState.DELETE_DUE,
                Optional.empty(),
                completedAt.plus(7, ChronoUnit.DAYS));
        AgentTask retry = due.withCleanupState(
                CleanupState.RETRY_PENDING,
                Optional.of("disk busy"),
                completedAt.plus(7, ChronoUnit.DAYS).plusSeconds(1));
        AgentTask deleted = retry.withCleanupState(
                CleanupState.DELETED,
                Optional.empty(),
                completedAt.plus(7, ChronoUnit.DAYS).plusSeconds(2));

        assertThat(deleted.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(deleted.summary()).isEqualTo(completed.summary());
        assertThat(deleted.cleanupState()).isEqualTo(CleanupState.DELETED);
    }

    private static AgentTask newTask()
    {
        return AgentTask.created(
                TaskId.valueOf("123e4567-e89b-12d3-a456-426614174000"),
                Optional.empty(),
                false,
                "Implement agent service",
                AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("bytequay", "bytequay"),
                WORKTREE,
                AgentPermissionPolicy.AUTO,
                CREATED_AT);
    }

    private static TaskSummary summary()
    {
        TaskId taskId = TaskId.valueOf("123e4567-e89b-12d3-a456-426614174000");
        TaskWorktree worktree = new TaskWorktree(
                taskId,
                "workspace-a",
                new RepositoryIdentity("bytequay", "bytequay"),
                WORKTREE.resolve(taskId.toString()),
                "bytequay/task/" + taskId,
                "0123456789012345678901234567890123456789");
        return new TaskSummary(
                "Implemented the requested change.",
                Optional.of(new WorktreeSnapshot(
                        worktree,
                        "1123456789012345678901234567890123456789",
                        true,
                        false)),
                ImmutableList.of(new TestFact("mvn verify", TestFact.Status.PASSED, Optional.empty())),
                TaskMetrics.empty(),
                ImmutableList.of("Keep provider events normalized."),
                Optional.empty());
    }
}
