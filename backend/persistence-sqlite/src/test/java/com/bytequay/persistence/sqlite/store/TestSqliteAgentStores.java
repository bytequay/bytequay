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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.agent.core.AgentAttachmentReference;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentSandboxPolicy;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskMetrics;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TestFact;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.CostProvenance;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
import com.google.common.collect.ImmutableList;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SqlitePersistenceConfiguration.class)
class TestSqliteAgentStores
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-agent-store-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private SqliteAgentTaskStore taskStore;

    @Autowired
    private SqliteAgentTurnStore turnStore;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    }

    @Test
    void roundTripsTaskSummarySessionMetricsAndCleanupFacts()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        TaskMetrics metrics = new TaskMetrics(
                10,
                2,
                5,
                Optional.of(new BigDecimal("1.25")),
                CostProvenance.PROVIDER_REPORTED,
                Duration.ofSeconds(3));
        TaskId taskId = TaskId.random();
        TaskWorktree worktree = new TaskWorktree(
                taskId,
                "workspace-a",
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-worktrees").resolve(taskId.toString()),
                "bytequay/task/" + taskId,
                "0123456789012345678901234567890123456789");
        WorktreeSnapshot snapshot = new WorktreeSnapshot(
                worktree,
                "1123456789012345678901234567890123456789",
                true,
                false);
        TaskSummary summary = new TaskSummary(
                "finished",
                Optional.of(snapshot),
                ImmutableList.of(new TestFact("mvn verify", TestFact.Status.PASSED, Optional.empty())),
                metrics,
                ImmutableList.of("keep the boundary small"),
                Optional.of("reviewed"));
        AgentTask task = AgentTask.created(
                        taskId,
                        true,
                        "Agent task",
                        AgentLaneId.CODEX_CLI,
                        worktree,
                        AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                        now)
                .withSessionReference(new AgentSessionRef(AgentLaneId.CODEX_CLI, 1, "session-1"), now.plusSeconds(3))
                .complete(summary, now.plusSeconds(5), now.plus(Duration.ofDays(7)));

        taskStore.insert(task);

        assertThat(taskStore.find(task.id())).contains(task);
        Instant cleanupNow = now.plus(Duration.ofDays(8));
        assertThat(taskStore.listCleanupDue(cleanupNow, cleanupNow, 10)).containsExactly(task);
        assertThat(taskStore.listLive()).isEmpty();
        assertThat(taskStore.listPendingWorktreeCleanup(AgentPermissionPolicy.AUTO, now.plusSeconds(5))).containsExactly(task);

        AgentTask removed = taskStore.update(task.withWorktreeRemovedAt(now.plusSeconds(6)));

        assertThat(taskStore.find(task.id())).contains(removed);
        assertThat(removed.worktreeRemovedAt()).contains(now.plusSeconds(6));
        assertThat(taskStore.listPendingWorktreeCleanup(AgentPermissionPolicy.AUTO, now.plusSeconds(6))).isEmpty();
    }

    @Test
    void backsOffFailedWorktreeCleanupSoAnotherTaskCanProgress()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask oldest = managedTask(now).fail("failed", now.plusSeconds(1), now.plus(Duration.ofDays(7)));
        AgentTask newer = managedTask(now).fail("failed", now.plusSeconds(2), now.plus(Duration.ofDays(7)));
        taskStore.insert(oldest);
        taskStore.insert(newer);

        assertThat(taskStore.listPendingWorktreeCleanup(AgentPermissionPolicy.AUTO, now.plusSeconds(9)))
                .containsExactly(oldest, newer);

        taskStore.update(oldest.withWorktreeCleanupAttemptAt(now.plusSeconds(10)));

        assertThat(taskStore.listPendingWorktreeCleanup(AgentPermissionPolicy.AUTO, now.plusSeconds(9)))
                .containsExactly(newer);
    }

    @Test
    void backsOffFailedRetentionCleanupSoAnotherDueTaskCanProgressWithABoundedBatch()
    {
        Instant now = Instant.parse("2026-08-24T00:00:00Z");
        Instant terminalAt = now.minus(Duration.ofDays(8));
        AgentTask retryPending = managedTask(terminalAt.minusSeconds(1))
                .fail("failed", terminalAt, terminalAt.plus(Duration.ofDays(7)))
                .withCleanupState(CleanupState.DELETE_DUE, Optional.empty(), now.minusSeconds(30))
                .withCleanupState(CleanupState.RETRY_PENDING, Optional.of("still dirty"), now.minusSeconds(30));
        AgentTask due = managedTask(terminalAt)
                .fail("failed", terminalAt.plusSeconds(1), terminalAt.plus(Duration.ofDays(7)).plusSeconds(1));
        taskStore.insert(retryPending);
        taskStore.insert(due);

        assertThat(taskStore.listCleanupDue(now, now.minus(Duration.ofMinutes(1)), 1)).containsExactly(due);
        assertThat(taskStore.listCleanupDue(now.plus(Duration.ofMinutes(1)), now, 2)).containsExactly(due, retryPending);
    }

    @Test
    void keepsWorkflowWorktreeOutOfGenericRetentionUntilWorkflowCleanupFinishes()
    {
        Instant now = Instant.parse("2026-08-24T00:00:00Z");
        Instant terminalAt = now.minus(Duration.ofDays(8));
        AgentTask workflowTask = managedTask(terminalAt, AgentPermissionPolicy.CI_AUTOFIX)
                .fail("workflow finished", terminalAt, terminalAt.plus(Duration.ofDays(7)));
        taskStore.insert(workflowTask);

        assertThat(taskStore.listCleanupDue(now, now, 10)).isEmpty();

        AgentTask worktreeRemoved = taskStore.update(workflowTask.withWorktreeRemovedAt(now.plusSeconds(1)));

        assertThat(taskStore.listCleanupDue(now.plusSeconds(1), now.plusSeconds(1), 10))
                .containsExactly(worktreeRemoved);
    }

    @Test
    void keepsLegacyWorkflowTaskWithoutManagedWorktreeEligibleForRetention()
    {
        Instant now = Instant.parse("2026-08-24T00:00:00Z");
        Instant terminalAt = now.minus(Duration.ofDays(8));
        AgentTask legacy = AgentTask.created(
                        TaskId.random(),
                        Optional.empty(),
                        false,
                        "Legacy workflow task",
                        AgentLaneId.CODEX_CLI,
                        new RepositoryIdentity("bytequay", "sample"),
                        Path.of("/tmp/bytequay-legacy-workflow"),
                        AgentPermissionPolicy.CI_AUTOFIX,
                        terminalAt)
                .fail("legacy workflow finished", terminalAt, terminalAt.plus(Duration.ofDays(7)));
        taskStore.insert(legacy);

        assertThat(taskStore.listCleanupDue(now, now, 10)).containsExactly(legacy);
    }

    @Test
    void enforcesOptimisticTaskUpdates()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask original = newTask(now);
        taskStore.insert(original);

        AgentTask updated = taskStore.update(original.withMetrics(TaskMetrics.empty(), now.plusSeconds(1)));

        assertThat(updated.version()).isEqualTo(1);
        assertThat(taskStore.find(original.id())).contains(updated);
        assertThatThrownBy(() -> taskStore.update(original.withMetrics(TaskMetrics.empty(), now.plusSeconds(2))))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("version changed");
    }

    @Test
    void roundTripsWorktreeMutationClaim()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask original = managedTask(now);
        WorktreeMutationClaim claim = WorktreeMutationClaim.turn(TurnId.random(), now.plusSeconds(1));
        taskStore.insert(original.claimWorktreeMutation(claim, now.plusSeconds(1)));
        entityManager.clear();

        assertThat(taskStore.find(original.id()).orElseThrow().worktreeMutationClaim()).contains(claim);
    }

    @Test
    void optimisticTaskRowAdmitsOnlyOneConcurrentSendOrCompletionClaim()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask original = managedTask(now);
        taskStore.insert(original);
        AgentTask observedBySend = taskStore.find(original.id()).orElseThrow();
        AgentTask observedByCompletion = taskStore.find(original.id()).orElseThrow();
        WorktreeMutationClaim turnClaim = WorktreeMutationClaim.turn(TurnId.random(), now.plusSeconds(1));
        WorktreeMutationClaim completionClaim = new WorktreeMutationClaim(
                WorktreeMutationClaim.Kind.COMPLETION,
                UUID.randomUUID().toString(),
                now.plusSeconds(1));

        taskStore.update(observedBySend.claimWorktreeMutation(turnClaim, now.plusSeconds(1)));

        assertThatThrownBy(() -> taskStore.update(
                observedByCompletion.claimWorktreeMutation(completionClaim, now.plusSeconds(1))))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("version changed");
        assertThat(taskStore.find(original.id()).orElseThrow().worktreeMutationClaim()).contains(turnClaim);
    }

    @Test
    void readsLegacySummaryWithoutInventingManagedWorktreeFacts()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        TaskSummary summary = new TaskSummary(
                "finished",
                Optional.empty(),
                ImmutableList.of(),
                TaskMetrics.empty(),
                ImmutableList.of(),
                Optional.empty());
        AgentTask task = newTask(now).complete(
                summary,
                now.plusSeconds(1),
                now.plus(Duration.ofDays(7)));
        taskStore.insert(task);
        entityManager.clear();

        assertThat(taskStore.find(task.id()).orElseThrow().summary().orElseThrow().worktree()).isEmpty();
    }

    @Test
    void rejectsDuplicateTaskIdentifierInsteadOfMerging()
    {
        AgentTask task = newTask(Instant.parse("2026-08-16T00:00:00Z"));
        taskStore.insert(task);

        assertThatThrownBy(() -> taskStore.insert(task))
                .isInstanceOf(AgentConflictException.class);
    }

    @Test
    void listsOnlyInteractiveTasksInTheRequestedWorkspace()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask expected = newTask(now, Optional.of("workspace-a"), true);
        AgentTask otherWorkspace = newTask(now.plusSeconds(1), Optional.of("workspace-b"), true);
        AgentTask internal = newTask(now.plusSeconds(2), Optional.of("workspace-a"), false);
        taskStore.insert(expected);
        taskStore.insert(otherWorkspace);
        taskStore.insert(internal);

        assertThat(taskStore.listRecentInteractive("workspace-a", 10)).containsExactly(expected);
    }

    @Test
    void readsLegacyNullOwnershipAsInternal()
    {
        AgentTask task = newTask(Instant.parse("2026-08-16T00:00:00Z"));
        taskStore.insert(task);
        jdbc.update("UPDATE agent_task SET workspace_id = NULL, interactive = NULL WHERE id = ?", task.id().toString());
        entityManager.clear();

        AgentTask restored = taskStore.find(task.id()).orElseThrow();
        assertThat(restored.workspaceId()).isEmpty();
        assertThat(restored.interactive()).isFalse();
        assertThat(restored.taskWorktree()).isEmpty();
        assertThat(taskStore.listRecentInteractive("workspace-a", 10)).isEmpty();
        assertThat(taskStore.hasWorktreeReference("workspace-a")).isFalse();
    }

    @Test
    void roundTripsManagedTaskWorktreeFacts()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        TaskId taskId = TaskId.random();
        RepositoryIdentity repository = new RepositoryIdentity("bytequay", "sample");
        TaskWorktree worktree = new TaskWorktree(
                taskId,
                "workspace-a",
                repository,
                Path.of("/tmp/bytequay-worktrees").resolve(taskId.toString()),
                "bytequay/task/" + taskId,
                "0123456789abcdef0123456789abcdef01234567");
        AgentTask task = AgentTask.created(
                taskId,
                true,
                "Managed agent task",
                AgentLaneId.CODEX_CLI,
                worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                now);

        taskStore.insert(task);
        entityManager.clear();

        assertThat(taskStore.find(taskId).orElseThrow().taskWorktree()).contains(worktree);
        assertThat(taskStore.hasWorktreeReference("workspace-a")).isTrue();
        assertThat(taskStore.hasWorktreeReference("workspace-b")).isFalse();
    }

    @Test
    void readsLegacyNullTaskDefaultsAndTurnSnapshot()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask task = newTask(now);
        taskStore.insert(task);
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), task.id(), "request-1", turnSettings(), now);
        turnStore.insert(turn);
        jdbc.update("UPDATE agent_task SET default_model = NULL, default_effort = NULL WHERE id = ?", task.id().toString());
        jdbc.update("""
                UPDATE agent_turn
                SET requested_model = NULL, effort = NULL, permission_policy = NULL,
                    sandbox_policy = NULL, maximum_runtime_millis = NULL, attachments_json = NULL
                WHERE id = ?
                """, turn.id().toString());
        entityManager.clear();

        assertThat(taskStore.find(task.id()).orElseThrow().defaults())
                .isEqualTo(AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO));
        assertThat(turnStore.find(turn.id()).orElseThrow().settings()).isEmpty();
    }

    @Test
    void readsLegacyProviderModelWithoutTurnSettings()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask task = newTask(now);
        taskStore.insert(task);
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), task.id(), "request-1", turnSettings(), now)
                .markDeliveryStarted(now.plusSeconds(1))
                .running(new ProviderTurnRef("provider-turn-1"), Optional.of("model"));
        turnStore.insert(turn);
        jdbc.update("""
                UPDATE agent_turn
                SET requested_model = NULL, effort = NULL, permission_policy = NULL,
                    sandbox_policy = NULL, maximum_runtime_millis = NULL, attachments_json = NULL
                WHERE id = ?
                """, turn.id().toString());
        entityManager.clear();

        AgentTurn restored = turnStore.find(turn.id()).orElseThrow();
        assertThat(restored.model()).contains("model");
        assertThat(restored.settings()).isEmpty();
    }

    @Test
    void roundTripsRequestedTurnSettings()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask task = newTask(now);
        taskStore.insert(task);
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), task.id(), "request-1", turnSettings(), now);
        turnStore.insert(turn);
        entityManager.clear();

        assertThat(turnStore.find(turn.id())).contains(turn);
    }

    @Test
    void enforcesSingleActiveTurnAndIdempotencyInSqlite()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask task = newTask(now);
        taskStore.insert(task);
        AgentTurn first = AgentTurn.accepted(TurnId.random(), task.id(), "request-1", turnSettings(), now);
        turnStore.insert(first);

        assertThat(turnStore.findActive(task.id())).contains(first);
        assertThat(turnStore.findByIdempotencyKey(task.id(), "request-1")).contains(first);
        assertThat(turnStore.listRecoverable()).containsExactly(first);
        assertThatThrownBy(() -> turnStore.insert(AgentTurn.accepted(
                TurnId.random(),
                task.id(),
                "request-2",
                turnSettings(),
                now.plusSeconds(1))))
                .isInstanceOf(AgentConflictException.class);
    }

    @Test
    void releasesActiveSlotWhenTurnBecomesTerminal()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask task = newTask(now);
        taskStore.insert(task);
        AgentTurn first = AgentTurn.accepted(TurnId.random(), task.id(), "request-1", turnSettings(), now)
                .markDeliveryStarted(now.plusSeconds(1))
                .running(new ProviderTurnRef("provider-turn-1"), Optional.of("model"));
        turnStore.insert(first);

        AgentTurn terminal = turnStore.update(first.finish(
                TurnState.COMPLETED,
                new TurnMetrics(1, 0, 1, Optional.empty(), CostProvenance.UNAVAILABLE, Duration.ofSeconds(2)),
                Optional.empty(),
                now.plusSeconds(2)));
        AgentTurn second = AgentTurn.accepted(TurnId.random(), task.id(), "request-2", turnSettings(), now.plusSeconds(3));
        turnStore.insert(second);

        assertThat(terminal.version()).isEqualTo(1);
        assertThat(turnStore.findActive(task.id())).contains(second);
        assertThat(turnStore.list(task.id())).containsExactly(terminal, second);
        assertThatThrownBy(() -> turnStore.update(first.withMetrics(TurnMetrics.empty())))
                .isInstanceOf(AgentConflictException.class);
    }

    @Test
    void enforcesIdempotencyAfterActiveSlotIsReleased()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask task = newTask(now);
        taskStore.insert(task);
        AgentTurn terminal = AgentTurn.accepted(TurnId.random(), task.id(), "same-request", turnSettings(), now)
                .finish(
                        TurnState.CANCELLED,
                        TurnMetrics.empty(),
                        Optional.of("cancelled"),
                        now.plusSeconds(1));
        turnStore.insert(terminal);

        assertThatThrownBy(() -> turnStore.insert(AgentTurn.accepted(
                TurnId.random(),
                task.id(),
                "same-request",
                turnSettings(),
                now.plusSeconds(2))))
                .isInstanceOf(AgentConflictException.class);
    }

    private static AgentTask newTask(Instant now)
    {
        return newTask(now, Optional.empty(), false);
    }

    private static AgentTask managedTask(Instant now)
    {
        return managedTask(now, AgentPermissionPolicy.AUTO);
    }

    private static AgentTask managedTask(Instant now, AgentPermissionPolicy permissionPolicy)
    {
        TaskId taskId = TaskId.random();
        TaskWorktree worktree = new TaskWorktree(
                taskId,
                "workspace-a",
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-worktrees").resolve(taskId.toString()),
                "bytequay/task/" + taskId,
                "0123456789012345678901234567890123456789");
        return AgentTask.created(
                taskId,
                false,
                "Agent task",
                AgentLaneId.CODEX_CLI,
                worktree,
                AgentTaskDefaults.forPermission(permissionPolicy),
                now);
    }

    private static AgentTask newTask(Instant now, Optional<String> workspaceId, boolean interactive)
    {
        return AgentTask.created(
                TaskId.random(),
                workspaceId,
                interactive,
                "Agent task",
                AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-worktree"),
                new AgentTaskDefaults(
                        Optional.of("model"),
                        Optional.of("medium"),
                        AgentPermissionPolicy.AUTO),
                now);
    }

    private static AgentTurnSettings turnSettings()
    {
        return new AgentTurnSettings(
                Optional.of("model"),
                Optional.of("medium"),
                AgentPermissionPolicy.AUTO,
                AgentSandboxPolicy.WORKSPACE_WRITE,
                Duration.ofMinutes(5),
                ImmutableList.of(new AgentAttachmentReference("attachment-1", "image.png", "image/png")));
    }
}
