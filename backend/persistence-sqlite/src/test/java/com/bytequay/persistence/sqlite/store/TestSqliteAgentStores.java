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

import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentAttachmentReference;
import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentInputDelivery;
import com.bytequay.agent.core.AgentSandboxPolicy;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.InputDeliveryId;
import com.bytequay.agent.core.QueueAgentInputCommand;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskMetrics;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TestFact;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.core.impl.AgentMaintenance;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.impl.DefaultAgentEventService;
import com.bytequay.agent.core.impl.DefaultAgentMetricsService;
import com.bytequay.agent.core.impl.DefaultAgentTurnService;
import com.bytequay.agent.core.impl.FileAgentResourceService;
import com.bytequay.agent.core.impl.FileTaskLogStore;
import com.bytequay.agent.core.impl.StaticAgentDriverRegistry;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentEventSink;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnFailureKind;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.CostProvenance;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.RunningTurn;
import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
    private SqliteQueuedInputStore queueStore;

    @Autowired
    private SqliteSteerDeliveryStore steerStore;

    @Autowired
    private SqliteAgentEventOutboxStore outboxStore;

    @Autowired
    private SqliteAgentUnitOfWork unitOfWork;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @TempDir
    private Path runtimeDirectory;

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
                Optional.of(new BigDecimal("0.1873740")),
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
                        TitleSource.USER,
                        AgentLaneId.CODEX_CLI,
                        worktree,
                        AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                        now)
                .withSessionReference(new AgentSessionRef(AgentLaneId.CODEX_CLI, 1, "session-1"), now.plusSeconds(3))
                .complete(summary, now.plusSeconds(5), now.plus(Duration.ofDays(7)));

        taskStore.insert(task);
        entityManager.clear();

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
                Optional.of("creation-fingerprint"),
                "Managed agent task",
                TitleSource.USER,
                AgentLaneId.CODEX_CLI,
                worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                now);

        taskStore.insert(task);
        entityManager.clear();

        AgentTask restored = taskStore.find(taskId).orElseThrow();
        assertThat(restored.creationFingerprint()).contains("creation-fingerprint");
        assertThat(restored.taskWorktree()).contains(worktree);
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
                    sandbox_policy = NULL, maximum_runtime_millis = NULL, attachments_json = NULL,
                    known_unsent = NULL
                WHERE id = ?
                """, turn.id().toString());
        entityManager.clear();

        assertThat(taskStore.find(task.id()).orElseThrow().defaults())
                .isEqualTo(AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO));
        assertThat(turnStore.find(turn.id()).orElseThrow()).satisfies(restored -> {
            assertThat(restored.settings()).isEmpty();
            assertThat(restored.knownUnsent()).isFalse();
        });
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
    void roundTripsKnownUnsentDelivery()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask task = newTask(now);
        taskStore.insert(task);
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), task.id(), "request-1", turnSettings(), now)
                .markDeliveryStarted(now.plusSeconds(1))
                .finishKnownUnsent(TurnMetrics.empty(), "not sent", now.plusSeconds(2));
        turnStore.insert(turn);
        entityManager.clear();

        assertThat(turnStore.find(turn.id()).orElseThrow()).satisfies(restored -> {
            assertThat(restored.knownUnsent()).isTrue();
            assertThat(restored.startedAt()).isPresent();
            assertThat(restored.providerTurnReference()).isEmpty();
        });
    }

    @Test
    void roundTripsTypedTurnFailure()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask task = newTask(now);
        taskStore.insert(task);
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), task.id(), "request-1", turnSettings(), now)
                .markDeliveryStarted(now.plusSeconds(1))
                .running(new ProviderTurnRef("provider-turn-1"), Optional.of("model"))
                .finish(
                        TurnState.FAILED,
                        TurnMetrics.empty(),
                        Optional.of("Claude sign-in required"),
                        Optional.of(AgentTurnFailureKind.AUTHENTICATION_REQUIRED),
                        now.plusSeconds(2));
        turnStore.insert(turn);
        entityManager.clear();

        assertThat(turnStore.find(turn.id()).orElseThrow().failureKind())
                .contains(AgentTurnFailureKind.AUTHENTICATION_REQUIRED);
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
                new TurnMetrics(1, 0, 1, Optional.of(new BigDecimal("0.1873740")), CostProvenance.CLI_ESTIMATE, Duration.ofSeconds(2)),
                Optional.empty(),
                now.plusSeconds(2)));
        AgentTurn second = AgentTurn.accepted(TurnId.random(), task.id(), "request-2", turnSettings(), now.plusSeconds(3));
        turnStore.insert(second);
        entityManager.clear();

        assertThat(terminal.version()).isEqualTo(1);
        assertThat(turnStore.find(terminal.id())).contains(terminal);
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

    @Test
    void roundTripsQueuedInputInQueueOrderAndEnforcesItsIdempotencyKey()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask task = newTask(now);
        taskStore.insert(task);
        QueuedAgentInput second = queuedInput(task.id(), 2, "queue-2", now.plusSeconds(1));
        QueuedAgentInput first = queuedInput(task.id(), 1, "queue-1", now)
                .starting(TurnId.random(), EventId.random(), now.plusSeconds(2))
                .uncertain("Backend restarted while the input was being sent", now.plusSeconds(3));
        queueStore.insert(second);
        queueStore.insert(first);
        entityManager.clear();

        assertThat(queueStore.listQueued(task.id())).extracting(QueuedAgentInput::id).containsExactly(first.id(), second.id());
        assertThat(queueStore.find(first.id())).hasValueSatisfying(stored -> {
            assertThat(stored.state()).isEqualTo(first.state());
            assertThat(stored.preview()).isEqualTo("Queued input");
        });
        assertThat(queueStore.findQueuedByIdempotencyKey(task.id(), "queue-2"))
                .hasValueSatisfying(stored -> assertThat(stored.id()).isEqualTo(second.id()));
        assertThat(jdbc.queryForList("SELECT * FROM agent_queued_input").toString())
                .doesNotContain("later  on", "later on");

        QueuedAgentInput removed = queueStore.update(second.removed(now.plusSeconds(4)));
        assertThat(removed.version()).isEqualTo(1);
        assertThat(removed.state()).isEqualTo(QueuedAgentInput.State.REMOVED);
        assertThatThrownBy(() -> queueStore.update(second.removed(now.plusSeconds(5))))
                .isInstanceOf(AgentConflictException.class);
        // Last, because the rejected row stays in the persistence context and would be flushed again.
        assertThatThrownBy(() -> queueStore.insert(queuedInput(task.id(), 3, "queue-2", now)))
                .isInstanceOf(AgentConflictException.class);
    }

    @Test
    void roundTripsSteerDeliveriesAndListsOnlyUnsettledOnes()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask task = newTask(now);
        taskStore.insert(task);
        TurnId turnId = TurnId.random();
        AgentInputDelivery sending = AgentInputDelivery.accepted(InputDeliveryId.random(), task.id(), turnId, "steer-1", now).sending(now.plusSeconds(1));
        AgentInputDelivery unknown = AgentInputDelivery.accepted(InputDeliveryId.random(), task.id(), turnId, "steer-2", now)
                .sending(now.plusSeconds(1))
                .unknown("no answer", now.plusSeconds(2));
        steerStore.insert(sending);
        steerStore.insert(unknown);
        entityManager.clear();

        assertThat(steerStore.findSteerByIdempotencyKey(task.id(), "steer-2")).contains(unknown);
        assertThat(steerStore.listUnsettled()).containsExactly(sending);

        AgentInputDelivery delivered = steerStore.update(sending.delivered(now.plusSeconds(3)));
        assertThat(delivered.state()).isEqualTo(AgentInputDelivery.State.DELIVERED);
        assertThat(steerStore.listUnsettled()).isEmpty();
        assertThatThrownBy(() -> steerStore.update(sending.delivered(now.plusSeconds(4))))
                .isInstanceOf(AgentConflictException.class);
        assertThatThrownBy(() -> steerStore.insert(AgentInputDelivery.accepted(InputDeliveryId.random(), task.id(), turnId, "steer-1", now)))
                .isInstanceOf(AgentConflictException.class);
    }

    @Test
    void resumesPersistedQueueInFifoOrderExactlyOnceAfterRestart()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        AgentTask task = managedTask(now);
        taskStore.insert(task);
        FileAgentResourceService resources = new FileAgentResourceService(runtimeDirectory);
        resources.allocate(task.id());
        FileTaskLogStore logs = new FileTaskLogStore(resources);
        AgentApprovalService approvals = mock(AgentApprovalService.class);
        DefaultAgentMetricsService metrics = new DefaultAgentMetricsService(taskStore, turnStore, clock);

        RecordingDriver firstProcess = new RecordingDriver();
        DefaultAgentTurnService beforeRestart = turnService(
                firstProcess,
                new AgentRuntimeRegistry(),
                new DefaultAgentEventService(logs, outboxStore, taskStore, clock),
                logs,
                resources,
                metrics,
                approvals,
                clock);
        beforeRestart.start(new StartAgentTurnCommand(task.id(), "current", ImmutableList.of(), "turn-1"));
        QueuedAgentInput first = beforeRestart.enqueue(new QueueAgentInputCommand(
                task.id(), taskStore.find(task.id()).orElseThrow().version(), "queued one", ImmutableList.of(), "queue-1"));
        QueuedAgentInput second = beforeRestart.enqueue(new QueueAgentInputCommand(
                task.id(), taskStore.find(task.id()).orElseThrow().version(), "queued two", ImmutableList.of(), "queue-2"));
        entityManager.flush();
        entityManager.clear();

        AgentRuntimeRegistry restartedRuntime = new AgentRuntimeRegistry();
        AgentMaintenance maintenance = new AgentMaintenance(
                taskStore,
                turnStore,
                queueStore,
                steerStore,
                unitOfWork,
                outboxStore,
                approvals,
                metrics,
                mock(AgentCompletionService.class),
                restartedRuntime,
                clock);
        maintenance.recover();
        maintenance.settleAfterProcessRecovery(Map.of(), Set.of(), true);
        entityManager.flush();
        entityManager.clear();

        assertThat(queueStore.listQueued(task.id())).extracting(QueuedAgentInput::state)
                .containsExactly(QueuedAgentInput.State.PAUSED, QueuedAgentInput.State.PAUSED);
        RecordingDriver restartedDriver = new RecordingDriver();
        DefaultAgentEventService restartedEvents = new DefaultAgentEventService(logs, outboxStore, taskStore, clock);
        DefaultAgentTurnService afterRestart = turnService(
                restartedDriver,
                restartedRuntime,
                restartedEvents,
                logs,
                resources,
                metrics,
                approvals,
                clock);

        afterRestart.resumeQueued(task.id());
        restartedDriver.completeCurrent();
        afterRestart.resumeQueued(task.id());
        restartedDriver.completeCurrent();
        entityManager.flush();
        entityManager.clear();

        assertThat(restartedDriver.commands()).extracting(AgentTurnCommand::message)
                .containsExactly("queued one", "queued two");
        assertThat(queueStore.listQueued(task.id())).satisfiesExactly(
                queued -> {
                    assertThat(queued.id()).isEqualTo(first.id());
                    assertThat(queued.state()).isEqualTo(QueuedAgentInput.State.STARTED);
                },
                queued -> {
                    assertThat(queued.id()).isEqualTo(second.id());
                    assertThat(queued.state()).isEqualTo(QueuedAgentInput.State.STARTED);
                });
        assertThat(restartedEvents.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.payload() instanceof AgentEventPayload.UserMessage message
                        && message.deliveryKind() == AgentEventPayload.DeliveryKind.QUEUED)
                .extracting(event -> ((AgentEventPayload.UserMessage) event.payload()).text())
                .containsExactly("queued one", "queued two");
    }

    private DefaultAgentTurnService turnService(
            RecordingDriver driver,
            AgentRuntimeRegistry runtimeRegistry,
            DefaultAgentEventService events,
            FileTaskLogStore logs,
            FileAgentResourceService resources,
            DefaultAgentMetricsService metrics,
            AgentApprovalService approvals,
            Clock clock)
    {
        return new DefaultAgentTurnService(
                taskStore,
                turnStore,
                queueStore,
                steerStore,
                unitOfWork,
                outboxStore,
                logs,
                resources,
                new StaticAgentDriverRegistry(ImmutableList.of(driver)),
                events,
                metrics,
                approvals,
                runtimeRegistry,
                clock,
                Duration.ofMinutes(5),
                Runnable::run);
    }

    private static QueuedAgentInput queuedInput(TaskId taskId, long position, String idempotencyKey, Instant now)
    {
        return QueuedAgentInput.queued(
                QueuedInputId.random(),
                taskId,
                position,
                idempotencyKey,
                new BlobRef("blobs/queue-" + position, "text/plain; charset=utf-8", 5),
                "later  on",
                turnSettings(),
                now);
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
                TitleSource.USER,
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

    private static final class RecordingDriver
            implements AgentDriver
    {
        private static final AgentCapabilities CAPABILITIES = new AgentCapabilities(
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                CostProvenance.UNAVAILABLE,
                ImmutableSet.of(AgentPermissionPolicy.AUTO),
                ImmutableSet.of());

        private final List<AgentTurnCommand> commands = new ArrayList<>();
        private CompletableFuture<AgentTurnResult> current;

        @Override
        public AgentLaneId id()
        {
            return AgentLaneId.CODEX_CLI;
        }

        @Override
        public AgentCapabilities capabilities()
        {
            return CAPABILITIES;
        }

        @Override
        public AgentProviderStatus probe()
        {
            return new AgentProviderStatus(id(), AgentProviderStatus.Readiness.READY, Optional.of("test"), "ready", CAPABILITIES);
        }

        @Override
        public AgentSession open(AgentSessionContext context, AgentEventSink events)
        {
            AgentSessionRef reference = context.sessionReference().orElseGet(() -> new AgentSessionRef(id(), 1, "session-1"));
            return new AgentSession()
            {
                @Override
                public AgentSessionRef reference()
                {
                    return reference;
                }

                @Override
                public RunningTurn start(AgentTurnCommand command)
                {
                    CompletableFuture<AgentTurnResult> completion;
                    synchronized (RecordingDriver.this) {
                        commands.add(command);
                        current = new CompletableFuture<>();
                        completion = current;
                    }
                    ProviderTurnRef turnReference = new ProviderTurnRef("turn-" + command.turnId());
                    return new RunningTurn()
                    {
                        @Override
                        public ProviderTurnRef reference()
                        {
                            return turnReference;
                        }

                        @Override
                        public CompletionStage<AgentTurnResult> completion()
                        {
                            return completion;
                        }

                        @Override
                        public void cancel()
                        {
                            completion.complete(new AgentTurnResult(
                                    AgentTurnResult.State.CANCELLED,
                                    Optional.empty(),
                                    Optional.of("cancelled"),
                                    Duration.ZERO));
                        }
                    };
                }

                @Override
                public void close() {}
            };
        }

        private synchronized void completeCurrent()
        {
            if (current == null) {
                throw new IllegalStateException("no turn is running");
            }
            current.complete(new AgentTurnResult(
                    AgentTurnResult.State.COMPLETED,
                    Optional.of("done"),
                    Optional.empty(),
                    Duration.ZERO));
        }

        private synchronized List<AgentTurnCommand> commands()
        {
            return ImmutableList.copyOf(commands);
        }
    }
}
