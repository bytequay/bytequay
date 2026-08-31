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
    void taskFollowsTheCreateToCompleteLifecycle()
    {
        AgentTask task = newTask()
                .ready(CREATED_AT.plusSeconds(1))
                .activate(CREATED_AT.plusSeconds(2))
                .beginCompletion(CREATED_AT.plusSeconds(3));
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
    void taskCannotSkipLifecycleStates()
    {
        AgentTask created = newTask();

        assertThatThrownBy(() -> created.activate(CREATED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("READY");
        assertThatThrownBy(() -> created.fail(
                "creation failed",
                CREATED_AT.plusSeconds(1),
                CREATED_AT.plus(7, ChronoUnit.DAYS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ready");
    }

    @Test
    void taskUpdateTimeCannotMoveBackwards()
    {
        AgentTask ready = newTask().ready(CREATED_AT.plusSeconds(2));

        assertThatThrownBy(() -> ready.activate(CREATED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before");
    }

    @Test
    void interactiveOwnershipRequiresWorkspace()
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

        assertThatThrownBy(() -> new CreateAgentTaskCommand(
                AgentLaneId.CODEX_CLI,
                WORKTREE,
                new RepositoryIdentity("bytequay", "bytequay"),
                "Interactive task",
                AgentPermissionPolicy.AUTO,
                Optional.empty(),
                true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspaceId");
    }

    @Test
    void terminalTaskCleanupPreservesTerminalState()
    {
        Instant completedAt = CREATED_AT.plusSeconds(4);
        AgentTask completed = newTask()
                .ready(CREATED_AT.plusSeconds(1))
                .activate(CREATED_AT.plusSeconds(2))
                .beginCompletion(CREATED_AT.plusSeconds(3))
                .complete(summary(), completedAt, completedAt.plus(7, ChronoUnit.DAYS));

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
        WorkspaceSnapshot workspace = new WorkspaceSnapshot(
                WORKTREE,
                new RepositoryIdentity("bytequay", "bytequay"),
                Optional.of("feature"),
                "0123456789abcdef",
                true);
        return new TaskSummary(
                "Implemented the requested change.",
                workspace,
                ImmutableList.of(new TestFact("mvn verify", TestFact.Status.PASSED, Optional.empty())),
                TaskMetrics.empty(),
                ImmutableList.of("Keep provider events normalized."),
                Optional.empty());
    }
}
