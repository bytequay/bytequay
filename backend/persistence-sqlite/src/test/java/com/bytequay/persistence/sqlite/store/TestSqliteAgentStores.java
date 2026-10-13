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
import com.bytequay.agent.core.AgentEventOutboxEntry;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentInputDelivery;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentProcessLaunch;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.InputDeliveryId;
import com.bytequay.agent.core.LaunchOwnerKind;
import com.bytequay.agent.core.QueueAgentInputCommand;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.ReviewTurnInputResolver;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.ToolCounters;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMemory;
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
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.RunningTurn;
import com.bytequay.core.domain.PullRequestTask;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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

import static com.bytequay.agent.core.AgentEventOutboxEntry.pendingLifecycle;
import static com.bytequay.agent.core.AgentEventSource.root;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TestSqliteAgentStores
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-agent-store-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private SqliteAgentTaskStore taskStore;

    @Autowired
    private SqlitePullRequestTaskStore pullRequestTasks;

    @Autowired
    private SqliteAgentTurnStore turnStore;

    @Autowired
    private SqliteQueuedInputStore queueStore;

    @Autowired
    private SqliteSteerDeliveryStore steerStore;

    @Autowired
    private SqliteAgentEventOutboxStore outboxStore;

    @Autowired
    private SqliteAgentProcessLaunchStore launchStore;

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
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON", "create-drop");
    }

    @Test
    void roundTripsTaskSummarySessionMetricsAndCleanupFacts()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentMetrics metrics = new AgentMetrics(
                10,
                2,
                5,
                Optional.of(new BigDecimal("0.1873740")),
                Duration.ofSeconds(3),
                new ToolCounters(4, 2, 1));
        TaskId taskId = TaskId.random();
        TaskWorktree worktree = new TaskWorktree(
                taskId,
                "workspace-a",
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-worktrees").resolve(taskId.toString()),
                "bytequay/task/" + taskId,
                "0123456789012345678901234567890123456789");
        // Ignored build output leaves a completed worktree dirty without uncommitted work.
        WorktreeSnapshot snapshot = new WorktreeSnapshot(
                worktree,
                "1123456789012345678901234567890123456789",
                true,
                false,
                false);
        TaskSummary summary = new TaskSummary(
                "finished",
                Optional.of(snapshot),
                metrics,
                Optional.of("reviewed"),
                ImmutableList.of());
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

        AgentTask updated = taskStore.update(original.withMetrics(AgentMetrics.empty(), now.plusSeconds(1)));

        assertThat(updated.version()).isEqualTo(1);
        assertThat(taskStore.find(original.id())).contains(updated);
        assertThatThrownBy(() -> taskStore.update(original.withMetrics(AgentMetrics.empty(), now.plusSeconds(2))))
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
                AgentMetrics.empty(),
                Optional.empty(),
                ImmutableList.of());
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
        AgentTask internal = newTask(now.plusSeconds(2), Optional.of("workspace-a"), false).withArchived(true, now.plusSeconds(3));
        taskStore.insert(expected);
        taskStore.insert(otherWorkspace);
        taskStore.insert(internal);

        assertThat(taskStore.listRecentInteractive("workspace-a", 10)).containsExactly(expected);
        assertThat(taskStore.listByWorkspace("workspace-a")).containsExactly(expected, internal);
        assertThat(taskStore.listByWorkspace("workspace-b")).containsExactly(otherWorkspace);
    }

    @Test
    void scopesCompletedSummariesBeforeLimitingOrDecodingRows()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        RepositoryIdentity repository = new RepositoryIdentity("bytequay", "sample");
        AgentTask expected = completedTask(TaskId.random(), "workspace-a", true, repository, now);
        AgentTask otherWorkspace = completedTask(TaskId.random(), "workspace-b", true, repository, now.plusSeconds(1));
        AgentTask otherOwner = completedTask(TaskId.random(), "workspace-a", true, new RepositoryIdentity("other", "sample"), now.plusSeconds(2));
        AgentTask otherName = completedTask(TaskId.random(), "workspace-a", true, new RepositoryIdentity("bytequay", "other"), now.plusSeconds(3));
        AgentTask internal = completedTask(TaskId.random(), "workspace-a", false, repository, now.plusSeconds(4));
        AgentTask open = newTask(now.plusSeconds(5), Optional.of("workspace-a"), true);
        AgentTask missingSummary = completedTask(TaskId.random(), "workspace-a", true, repository, now.plusSeconds(6));
        AgentTask failed = newTask(now.plusSeconds(7), Optional.of("workspace-a"), true)
                .fail("failed", now.plusSeconds(8), now.plusSeconds(100));
        ImmutableList.of(expected, otherWorkspace, otherOwner, otherName, internal, open, missingSummary, failed).forEach(taskStore::insert);
        entityManager.flush();
        // Excluded rows must never be decoded, even when their retained payload is unreadable.
        for (AgentTask excluded : ImmutableList.of(otherWorkspace, otherOwner, otherName, internal, open, failed)) {
            jdbc.update("UPDATE agent_task SET summary_json = ? WHERE id = ?", "invalid-json", excluded.id().toString());
        }
        jdbc.update("UPDATE agent_task SET summary_json = NULL WHERE id = ?", missingSummary.id().toString());
        entityManager.clear();

        assertThat(taskStore.listRecentCompletedInteractive("workspace-a", new RepositoryIdentity("ByteQuay", "SAMPLE"), 1)).containsExactly(expected);
        assertThat(taskStore.listRecentCompletedInteractive("workspace-a", repository, 10)).containsExactly(expected);
        assertThat(taskStore.listRecentCompletedInteractive("WORKSPACE-A", repository, 1)).isEmpty();
    }

    @Test
    void ordersCompletedSummariesWithStableTiesAndRetainsArchivedCleanedTasks()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        RepositoryIdentity repository = new RepositoryIdentity("bytequay", "sample");
        AgentTask first = completedTask(TaskId.valueOf("00000000-0000-0000-0000-000000000001"), "workspace-a", true, repository, now)
                .withArchived(true, now)
                .withCleanupState(CleanupState.DELETED, Optional.empty(), now);
        AgentTask second = completedTask(TaskId.valueOf("00000000-0000-0000-0000-000000000002"), "workspace-a", true, repository, now);
        AgentTask newest = completedTask(TaskId.random(), "workspace-a", true, repository, now.plusSeconds(1));
        ImmutableList.of(second, first, newest).forEach(taskStore::insert);
        entityManager.flush();
        entityManager.clear();

        assertThat(taskStore.listRecentCompletedInteractive("workspace-a", repository, 2)).containsExactly(newest, first);
        assertThat(taskStore.listRecentCompletedInteractive("workspace-a", repository, 3)).containsExactly(newest, first, second);
        assertThatThrownBy(() -> taskStore.listRecentCompletedInteractive("workspace-a", repository, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> taskStore.listRecentCompletedInteractive(" ", repository, 1)).isInstanceOf(IllegalArgumentException.class);
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
                    maximum_runtime_millis = NULL, attachments_json = NULL,
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
                    maximum_runtime_millis = NULL, attachments_json = NULL
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
        AgentTask task = newTask(now).withDefaults(
                new AgentTaskDefaults(Optional.of("model"), Optional.of("high"), AgentPermissionPolicy.AUTO, Optional.of("priority")), now);
        taskStore.insert(task);
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), task.id(), "request-1", turnSettings(), now);
        turnStore.insert(turn);
        entityManager.clear();

        assertThat(turnStore.find(turn.id())).contains(turn);
        assertThat(taskStore.find(task.id()).orElseThrow().defaults().serviceTier()).contains("priority");
    }

    @ParameterizedTest
    @EnumSource(DevelopmentTurnOrigin.Kind.class)
    void retainsUnfinishedContinuationAndTrustedTurnOriginAcrossReload(DevelopmentTurnOrigin.Kind kind)
    {
        assertThat(jdbc.queryForList("PRAGMA table_info('agent_task')"))
                .filteredOn(column -> column.get("name").equals("development_continuation_unfinished"))
                .singleElement().satisfies(column -> assertThat(column.get("dflt_value")).isEqualTo("false"));
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask task = newTask(now).withDevelopmentContinuation(true, now);
        taskStore.insert(task);
        DevelopmentTurnOrigin origin = new DevelopmentTurnOrigin(kind, TurnId.random());
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), task.id(), "generated-turn",
                        new AgentTurnSettings(Optional.of("model"), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(5), ImmutableList.of()), now)
                .withDevelopmentOrigin(origin);
        turnStore.insert(turn);
        entityManager.clear();
        assertThat(turnStore.find(turn.id())).contains(turn);

        AgentTurn completed = turnStore.update(turn.markDeliveryStarted(now.plusSeconds(1))
                .running(new ProviderTurnRef("generated-native-turn"), Optional.of("model"))
                .withDevelopmentResult(new DevelopmentResultReport(true, Optional.empty()))
                .finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), now.plusSeconds(2)));
        entityManager.clear();

        AgentTask restored = taskStore.find(task.id()).orElseThrow();
        assertThat(restored.developmentContinuationUnfinished()).isTrue();
        assertThat(restored.withDefaults(restored.defaults(), now.plusSeconds(3)).developmentContinuationUnfinished()).isTrue();
        assertThat(turnStore.find(turn.id())).contains(completed);
        taskStore.update(restored.withDevelopmentContinuation(false, now.plusSeconds(4)));
        entityManager.clear();
        assertThat(taskStore.find(task.id()).orElseThrow().developmentContinuationUnfinished()).isFalse();
    }

    @Test
    void ignoresStoredDeveloperReviewModeAndPreservesSessionAndInputSettings()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask task = newTask(now, Optional.of("workspace-a"), true)
                .withSessionReference(new AgentSessionRef(AgentLaneId.CODEX_CLI, 3, "existing-developer-session"), now);
        jdbc.execute("ALTER TABLE agent_task ADD COLUMN local_review_head varchar(255)");
        taskStore.insert(task);
        AgentTurnSettings settings = new AgentTurnSettings(Optional.of("model"), Optional.empty(), AgentPermissionPolicy.AUTO,
                Duration.ZERO, ImmutableList.of(), Optional.empty(), Optional.of(DevelopmentAutomation.LOCAL_ONLY));
        jdbc.update("UPDATE agent_task SET local_review_head = ? WHERE id = ?", "a".repeat(40), task.id().toString());
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), task.id(), "private-review", settings, now);
        turnStore.insert(turn);
        QueuedAgentInput queued = QueuedAgentInput.queued(QueuedInputId.random(), task.id(), 1, "private-review-next",
                new BlobRef("queue/input", "text/plain", 5), "later", settings, now);
        queueStore.insert(queued);
        entityManager.clear();

        AgentTask restored = taskStore.find(task.id()).orElseThrow();
        assertThat(restored.interactive()).isTrue();
        assertThat(restored.permissionPolicy()).isEqualTo(AgentPermissionPolicy.AUTO);
        assertThat(restored.sessionReference()).isEqualTo(task.sessionReference());
        assertThat(restored.taskWorktree()).isEqualTo(task.taskWorktree());
        assertThat(turnStore.find(turn.id()).orElseThrow().settings()).contains(settings);
        assertThat(queueStore.find(queued.id()).orElseThrow().settings()).isEqualTo(settings);
        taskStore.update(restored.withDefaults(restored.defaults(), now.plusSeconds(2)));
        entityManager.clear();
        assertThat(taskStore.find(task.id()).orElseThrow().sessionReference()).isEqualTo(task.sessionReference());
        assertThat(jdbc.queryForObject("SELECT local_review_head FROM agent_task WHERE id = ?", String.class, task.id().toString())).isEqualTo("a".repeat(40));
    }

    @Test
    void persistsTurnPlanSnapshotsIncludingExplicitClearAndPreservesThemThroughCompletion()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask task = newTask(now);
        taskStore.insert(task);
        AgentTurnSettings settings = new AgentTurnSettings(
                Optional.of("model"), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(5), ImmutableList.of());
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), task.id(), "plan", settings, now);
        turnStore.insert(turn);
        entityManager.clear();
        assertThat(turnStore.find(turn.id()).orElseThrow().plan()).isEmpty();
        assertThat(turnStore.find(turn.id()).orElseThrow().guidelines()).isEmpty();
        assertThat(turnStore.find(turn.id()).orElseThrow().memories()).isEmpty();

        List<ProviderAgentEvent.PlanItem> items = ImmutableList.of(new ProviderAgentEvent.PlanItem("Verify", ProviderAgentEvent.PlanItemState.IN_PROGRESS));
        AgentTurn running = turnStore.update(turnStore.find(turn.id()).orElseThrow().markDeliveryStarted(now)
                .withReference(ImmutableList.of("CONTRIBUTING.md"), ImmutableList.of(new TurnMemory("m12", "Document every new table property.")))
                .running(new ProviderTurnRef("provider-plan"), Optional.of("model")).withPlan(items));
        entityManager.clear();
        assertThat(turnStore.find(turn.id()).orElseThrow().plan()).contains(items);
        // The guideline files and memories that went with the input stay with the turn through later updates.
        assertThat(turnStore.find(turn.id()).orElseThrow().guidelines()).containsExactly("CONTRIBUTING.md");
        assertThat(turnStore.find(turn.id()).orElseThrow().memories()).containsExactly(new TurnMemory("m12", "Document every new table property."));
        AgentTurn cleared = turnStore.update(running.withPlan(ImmutableList.of()));
        entityManager.clear();
        assertThat(turnStore.find(turn.id()).orElseThrow().plan()).contains(ImmutableList.of());
        turnStore.update(cleared.withPlan(items).finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), now.plusSeconds(1)));
        entityManager.clear();
        assertThat(turnStore.find(turn.id()).orElseThrow().plan()).contains(items);
        assertThat(turnStore.find(turn.id()).orElseThrow().withVersion(99).plan()).contains(items);
    }

    @Test
    void retainsAutomationSnapshotsAndReadinessIndependentlyOfFutureDefaults()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        DevelopmentAutomation selected = new DevelopmentAutomation(false, true, true, Optional.of(AgentLaneId.CLAUDE_CLI), true);
        AgentTask original = newTask(now);
        AgentTask task = original.withDefaults(original.defaults().withDevelopmentAutomation(selected), now);
        taskStore.insert(task);
        AgentTurnSettings settings = new AgentTurnSettings(
                Optional.of("model"), Optional.of("high"), AgentPermissionPolicy.AUTO, Duration.ofMinutes(5), ImmutableList.of(), Optional.empty(), Optional.of(selected));
        DevelopmentResultReport question = new DevelopmentResultReport(false, Optional.of("Which implementation should I use?"));
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), task.id(), "automation-1", settings, now)
                .markDeliveryStarted(now.plusSeconds(1))
                .running(new ProviderTurnRef("provider-automation"), Optional.of("model"))
                .withDevelopmentResult(question)
                .finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), now.plusSeconds(2));
        turnStore.insert(turn);
        QueuedAgentInput queued = QueuedAgentInput.queued(
                QueuedInputId.random(), task.id(), 1, "automation-queue", new BlobRef("queue/input", "text/plain", 5), "later", settings, now);
        queueStore.insert(queued);
        taskStore.update(task.withDefaults(task.defaults().withDevelopmentAutomation(DevelopmentAutomation.LOCAL_ONLY), now.plusSeconds(3)));
        jdbc.update("UPDATE agent_turn SET development_result = ? WHERE id = ?",
                "{\"readyForDelivery\":false,\"message\":\"Which implementation should I use?\",\"stopAfter\":\"COMMITS\"}", turn.id().toString());
        entityManager.clear();

        assertThat(taskStore.find(task.id()).orElseThrow().defaults().developmentAutomation()).contains(DevelopmentAutomation.LOCAL_ONLY);
        assertThat(turnStore.find(turn.id()).orElseThrow()).satisfies(restored -> {
            assertThat(restored.settings()).contains(settings);
            assertThat(restored.developmentResult()).contains(question);
            assertThat(restored.withVersion(restored.version() + 1).developmentResult()).contains(question);
        });
        assertThat(queueStore.find(queued.id()).orElseThrow().settings()).isEqualTo(settings);
    }

    @Test
    void persistsDevelopmentResultsWithoutAnEnumCheckConstraint()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask task = newTask(now);
        taskStore.insert(task);
        AgentEventOutboxEntry lifecycle = pendingLifecycle(
                EventId.random(), task.id(), Optional.empty(), AgentEventPayload.LifecyclePhase.TURN_STARTED, "started", now);
        AgentEventOutboxEntry development = AgentEventOutboxEntry.pendingInline(
                EventId.random(), task.id(), Optional.empty(), root(),
                new AgentEventPayload.DevelopmentResult(false, Optional.of("Which implementation should I use?"), Optional.empty()), now);

        outboxStore.insert(lifecycle);
        outboxStore.insert(development);
        entityManager.clear();

        assertThat(outboxStore.find(lifecycle.eventId())).contains(lifecycle);
        assertThat(outboxStore.find(development.eventId())).contains(development);
        assertThat(jdbc.queryForObject("SELECT payload_kind FROM agent_event_outbox WHERE event_id = ?", String.class, development.eventId().toString()))
                .isEqualTo("DEVELOPMENT_RESULT");
        assertThat(jdbc.queryForObject("SELECT sql FROM sqlite_schema WHERE type = 'table' AND name = 'agent_event_outbox'", String.class))
                .doesNotContainPattern("(?i)check\\s*\\([^)]*\\bpayload_kind\\b");
    }

    @Test
    void roundTripsKnownUnsentDelivery()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask task = newTask(now);
        taskStore.insert(task);
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), task.id(), "request-1", turnSettings(), now)
                .markDeliveryStarted(now.plusSeconds(1))
                .finishKnownUnsent(AgentMetrics.empty(), "not sent", now.plusSeconds(2));
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
                        AgentMetrics.empty(),
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
                new AgentMetrics(1, 0, 1, Optional.of(new BigDecimal("0.1873740")), Duration.ofSeconds(2)),
                Optional.empty(),
                now.plusSeconds(2)));
        AgentTurn second = AgentTurn.accepted(TurnId.random(), task.id(), "request-2", turnSettings(), now.plusSeconds(3));
        turnStore.insert(second);
        entityManager.clear();

        assertThat(terminal.version()).isEqualTo(1);
        assertThat(turnStore.find(terminal.id())).contains(terminal);
        assertThat(turnStore.findActive(task.id())).contains(second);
        assertThat(turnStore.list(task.id())).containsExactly(terminal, second);
        assertThatThrownBy(() -> turnStore.update(first.withMetrics(AgentMetrics.empty())))
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
                        AgentMetrics.empty(),
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
    void keepsEarlierPrConversationsAndFindsTheNewestAfterReload()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask earlier = completedTask(TaskId.random(), "workspace", true, new RepositoryIdentity("owner", "repo"), now);
        AgentTask latest = newTask(now.plusSeconds(1), Optional.of("workspace"), true);
        taskStore.insert(earlier);
        taskStore.insert(latest);
        PullRequestTask oldLink = new PullRequestTask(earlier.id().toString(), "workspace", "owner/repo", 7, "Old snapshot");
        PullRequestTask newLink = new PullRequestTask(latest.id().toString(), "workspace", "owner/repo", 7, "New snapshot");
        pullRequestTasks.insert(oldLink);
        pullRequestTasks.insert(newLink);
        taskStore.update(earlier.withArchived(true, now.plusSeconds(2)));
        entityManager.flush();
        entityManager.clear();

        assertThat(pullRequestTasks.find("workspace", "OWNER/REPO", 7)).contains(newLink);
        assertThat(pullRequestTasks.findByTaskId(earlier.id().toString())).contains(oldLink);
        assertThat(pullRequestTasks.findByTaskId(latest.id().toString())).contains(newLink);
        assertThat(pullRequestTasks.find("other-workspace", "owner/repo", 7)).isEmpty();
        assertThat(pullRequestTasks.find("workspace", "other/repo", 7)).isEmpty();
        assertThat(pullRequestTasks.find("workspace", "owner/repo", 8)).isEmpty();

        taskStore.delete(earlier.id());
        entityManager.clear();
        assertThat(pullRequestTasks.find("workspace", "owner/repo", 7)).contains(newLink);
        assertThat(pullRequestTasks.findByTaskId(earlier.id().toString())).isEmpty();
        assertThat(taskStore.find(latest.id())).isPresent();
    }

    @Test
    void deletesATaskWithEveryRowThatHangsOffIt()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask task = newTask(now);
        taskStore.insert(task);
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), task.id(), "request-1", turnSettings(), now);
        turnStore.insert(turn);
        queueStore.insert(queuedInput(task.id(), 1, "queue-1", now));
        steerStore.insert(AgentInputDelivery.accepted(InputDeliveryId.random(), task.id(), turn.id(), "steer-1", now));
        outboxStore.insert(pendingLifecycle(EventId.random(), task.id(), Optional.of(turn.id()), AgentEventPayload.LifecyclePhase.TURN_STARTED, "started", now));
        launchStore.insert(new AgentProcessLaunch("launch-task", LaunchOwnerKind.APP_SERVER, task.id().toString(), true, "/usr/bin/codex", "digest", now));
        launchStore.insert(new AgentProcessLaunch("launch-turn", LaunchOwnerKind.CLAUDE_TURN, turn.id().toString(), true, "/usr/bin/claude", "digest", now));
        AgentTask other = newTask(now);
        taskStore.insert(other);
        launchStore.insert(new AgentProcessLaunch("launch-other", LaunchOwnerKind.APP_SERVER, other.id().toString(), true, "/usr/bin/codex", "digest", now));
        entityManager.clear();
        PullRequestTask assignment = new PullRequestTask(task.id().toString(), "workspace", "Owner/Repo", 7, "Load PR context");
        pullRequestTasks.insert(assignment);
        entityManager.clear();
        assertThat(pullRequestTasks.find("workspace", "OWNER/REPO", 7)).contains(assignment);
        assertThat(pullRequestTasks.findByTaskId(task.id().toString())).contains(assignment);
        assertThat(pullRequestTasks.find("another-workspace", "owner/repo", 7)).isEmpty();

        // A development task's local runs go with it; a review task's runs stay with the review.
        insertLocalRun("run-task", task.id().toString());
        insertLocalRun("run-review", other.id().toString());
        jdbc.update("""
                INSERT INTO agent_review (id, workspace_id, repo, number, task_id, state, follow_up_paused, version, created_at, updated_at)
                VALUES ('review-other', 'workspace', 'owner/repo', 8, ?, 'OPEN', 0, 0, '2026-08-16T00:00:00Z', '2026-08-16T00:00:00Z')
                """, other.id().toString());

        taskStore.delete(task.id());
        entityManager.clear();

        assertThat(jdbc.queryForList("SELECT id FROM local_run", String.class)).containsExactly("run-review");
        assertThat(jdbc.queryForList("SELECT run_id FROM local_run_output", String.class)).containsExactly("run-review");
        assertThat(taskStore.find(task.id())).isEmpty();
        assertThat(pullRequestTasks.findByTaskId(task.id().toString())).isEmpty();
        assertThat(turnStore.find(turn.id())).isEmpty();
        for (String table : ImmutableList.of("agent_turn", "agent_queued_input", "agent_input_delivery", "agent_event_outbox")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE task_id = ?", Integer.class, task.id().toString()))
                    .as(table).isZero();
        }
        assertThat(launchStore.list()).extracting(AgentProcessLaunch::launchId).containsExactly("launch-other");
        assertThat(taskStore.find(other.id())).isPresent();

        taskStore.delete(task.id());
        assertThat(taskStore.find(other.id())).isPresent();

        taskStore.delete(other.id());
        assertThat(jdbc.queryForList("SELECT id FROM local_run", String.class)).containsExactly("run-review");
        assertThat(jdbc.queryForList("SELECT run_id FROM local_run_output", String.class)).containsExactly("run-review");
    }

    private void insertLocalRun(String id, String taskId)
    {
        jdbc.update("""
                INSERT INTO local_run (id, command, initiator, local_paths_json, ordinal, output_truncated, results_json, scope, started_at, status, task_id,
                    tree_unverified, version, workspace_id)
                VALUES (?, './mvnw test', 'USER', '[]', 1, 0, '[]', 'CLASS', '2026-08-16T00:00:00Z', 'PASSED', ?, 0, 0, 'workspace')
                """, id, taskId);
        jdbc.update("INSERT INTO local_run_output (run_id, output) VALUES (?, 'output')", id);
    }

    @Test
    void roundTripsTheArchiveShelf()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask task = newTask(now);
        taskStore.insert(task);

        AgentTask archived = taskStore.update(task.withArchived(true, now.plusSeconds(5)));
        entityManager.clear();

        assertThat(taskStore.find(task.id())).contains(archived);
        assertThat(archived.archivedAt()).contains(now.plusSeconds(5));
        assertThat(archived.updatedAt()).isEqualTo(task.updatedAt());
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
        DefaultAgentMetricsService metrics = new DefaultAgentMetricsService(taskStore, turnStore, logs, clock);

        RecordingDriver firstProcess = new RecordingDriver();
        DefaultAgentTurnService beforeRestart = turnService(
                firstProcess,
                new AgentRuntimeRegistry(ignored -> {}),
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

        AgentRuntimeRegistry restartedRuntime = new AgentRuntimeRegistry(ignored -> {});
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
                (worktree, query) -> "IDE unavailable in this storage test",
                task -> "",
                ReviewTurnInputResolver.NONE,
                clock,
                Duration.ofMinutes(5),
                Runnable::run,
                task -> false,
                (task, turn) -> {},
                outcome -> {},
                ignored -> {});
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

    private static AgentTask completedTask(TaskId id, String workspaceId, boolean interactive, RepositoryIdentity repository, Instant now)
    {
        return AgentTask.created(id, Optional.of(workspaceId), interactive, "Task conclusion", AgentLaneId.CODEX_CLI, repository,
                        Path.of("/tmp/bytequay-worktree"), AgentPermissionPolicy.AUTO, now)
                .complete(new TaskSummary("Retained agent conclusion", Optional.empty(), AgentMetrics.empty(), Optional.empty(),
                        ImmutableList.of()), now, now.plus(Duration.ofDays(7)));
    }

    private static AgentTurnSettings turnSettings()
    {
        return new AgentTurnSettings(
                Optional.of("model"),
                Optional.of("medium"),
                AgentPermissionPolicy.AUTO,
                Duration.ofMinutes(5),
                ImmutableList.of(new AgentAttachmentReference("attachment-1", "image.png", "image/png")),
                Optional.of("priority"),
                Optional.empty());
    }

    private static final class RecordingDriver
            implements AgentDriver
    {
        private static final AgentCapabilities CAPABILITIES = new AgentCapabilities(
                false,
                false,
                false,
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
                                    Duration.ZERO,
                                    true));
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
                    Duration.ZERO,
                    true));
        }

        private synchronized List<AgentTurnCommand> commands()
        {
            return ImmutableList.copyOf(commands);
        }
    }
}
