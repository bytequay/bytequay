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
package com.bytequay.app.repository.sqlite.migration;

import com.bytequay.app.beans.stage.TaskBrainViewData;
import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.compatibility.V2DevelopmentFlowProjection;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOwnerResultCodec;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchTicketStore;
import com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteCiPolicy;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteEffectOperationHandler;
import com.bytequay.app.developmentflow.stage.RemoteObservationConsumer;
import com.bytequay.app.developmentflow.stage.RemoteObservationOperationHandler;
import com.bytequay.app.developmentflow.stage.RemoteObservationRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteRepairTurnRuntime;
import com.bytequay.app.developmentflow.stage.persistence.SqliteAgentResultSubmissionStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairNormalizationStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.BrainRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BranchEpisode;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BranchStep;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CiBudgets;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CiEpisode;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.RemoteContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.testing.MigratedSqliteDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.acceptSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.execute;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertCiPolicy;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertFailedCi;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRevisionBackedCurrentSubject;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.migrate;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRemoteRepairBrainRecovery
{
    private static final Instant NOW = Instant.ofEpochMilli(1_000);

    @TempDir
    private Path tempDir;

    @Test
    void successfulCiValidationPushesDirectlyWithoutTaskBrain()
            throws Exception
    {
        Harness runtime = harness("ci-validation-direct-push.db");
        deliverFailedObservation(runtime);

        Turn stage = runtime.jdbc().queryForObject("""
                SELECT operation.stage_turn_id AS turn_id,
                       operation.operation_id, operation.semantic_attempt,
                       operation.expected_code_fingerprint,
                       operation.expected_head_sha,
                       operation.expected_base_sha,
                       ticket.id AS ticket_id, identity.branch_name
                  FROM ci_repair_operation operation
                  JOIN dispatch_ticket ticket
                    ON ticket.operation_id = operation.operation_id
                  JOIN task_code_identity identity
                    ON identity.task_id = ticket.task_id
                 WHERE operation.kind = 'FIX_STAGE_TURN'
                """, (rs, row) -> turn(rs));
        AgentTurnOwnerResultCodec.OwnerResult stageResult = stageResult(
                runtime, stage);
        markResultPending(runtime.jdbc(), stage,
                DispatchTicket.Outcome.SUCCEEDED,
                runtime.json().writeValueAsString(stageResult.payload()));
        DispatchTicket.DeliveryReceipt stageAccepted =
                runtime.turnRuntime().deliver(stageResult);
        completeTicket(runtime.dataSource(), stage.ticketId(), stageAccepted);

        Effect validation = runtime.jdbc().queryForObject("""
                SELECT operation.operation_id, operation.semantic_attempt,
                       operation.expected_code_fingerprint,
                       operation.expected_head_sha,
                       operation.expected_base_sha,
                       ticket.id AS ticket_id
                  FROM ci_repair_operation operation
                  JOIN dispatch_ticket ticket
                    ON ticket.operation_id = operation.operation_id
                 WHERE operation.kind = 'VALIDATE'
                """, (rs, row) -> new Effect(
                        rs.getString("operation_id"),
                        rs.getInt("semantic_attempt"),
                        rs.getString("expected_code_fingerprint"),
                        rs.getString("expected_head_sha"),
                        rs.getString("expected_base_sha"),
                        rs.getString("ticket_id")));
        String episodeId = runtime.jdbc().queryForObject(
                "SELECT id FROM ci_repair_episode", String.class);
        RemoteCiRepairRuntimeCoordinator directRepair =
                new RemoteCiRepairRuntimeCoordinator(
                        runtime.commands(), runtime.remoteStore(),
                        ignored -> RemoteCiRepairRuntimeCoordinator
                                .Classification.TASK_BRANCH_REPAIRABLE,
                        new CiBudgets(0, 3, 2, 3), runtime.turnRuntime(),
                        runtime.json(), Clock.fixed(NOW, ZoneOffset.UTC));
        RemoteEffectOperationHandler.Result validationResult =
                new RemoteEffectOperationHandler.Result(
                        1, validation.operationId(),
                        RemoteEffectOperationHandler.Disposition.SUCCEEDED,
                        validation.fingerprint(), validation.head(),
                        validation.base(), "validation passed", null);
        String validationPayload = runtime.json().writeValueAsString(
                validationResult);
        markResultPending(
                runtime.jdbc(), validation.operationId(), validation.fence(),
                DispatchTicket.Outcome.SUCCEEDED, validationPayload);
        DispatchTicket.DeliveryReceipt validationAccepted =
                directRepair.deliverEffect(
                        new DispatchTicket.OwnerReference(
                                DispatchTicket.OwnerKind.STAGE,
                                "remote-stage-1",
                                "REMOTE_CI_VALIDATION_RESULT"),
                        validation.fence(), new DispatchTicket.DispatchResult(
                                validation.fence(),
                                DispatchTicket.Outcome.SUCCEEDED,
                                validationPayload, validationPayload, null));
        completeTicket(
                runtime.dataSource(), validation.ticketId(),
                validationAccepted);

        assertThat(runtime.jdbc().queryForMap("""
                SELECT operation.kind, ticket.owner_kind,
                       ticket.operation_kind, ticket.callback_route
                  FROM ci_repair_operation operation
                  JOIN dispatch_ticket ticket
                    ON ticket.operation_id = operation.operation_id
                 WHERE operation.kind = 'PUSH_HEAD'
                """))
                .containsEntry("kind", "PUSH_HEAD")
                .containsEntry("owner_kind", "STAGE")
                .containsEntry("operation_kind", "PUSH_REMOTE_CI_REPAIR")
                .containsEntry("callback_route", "REMOTE_CI_PUSH_RESULT");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM ci_repair_operation
                 WHERE kind = 'BRAIN_REVIEW'
                """, Integer.class)).isZero();
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM task_turn
                 WHERE purpose = 'REMOTE_CI_BRAIN_REVIEW'
                """, Integer.class)).isZero();
        assertThat(runtime.jdbc().queryForObject("""
                SELECT classification FROM ci_repair_episode
                """, String.class)).isEqualTo("TASK_BRANCH_REPAIRABLE");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT json_extract(turn.launch_input, '$.prompt')
                  FROM stage_turn turn
                 WHERE turn.purpose = 'REMOTE_CI_REPAIR'
                """, String.class))
                .contains("append-only commits on the current Task branch")
                .contains("Never rewrite base history")
                .contains("Treat all of them as part of this repair");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM ci_base_repair_authorization_v303
                """, Integer.class)).isZero();
        assertThat(runtime.taskStore().findById("task-1").orElseThrow()
                .pendingBrainResult()).isNull();
        assertThat(runtime.remoteStore().requireCiEpisode(
                "task-1", episodeId).status())
                .isEqualTo("AWAITING_PUSH_CI");
    }

    @Test
    void failedValidationKeepsTheSuccessorAppendOnlyWithoutBaseAuthority()
            throws Exception
    {
        Harness runtime = harness("ci-validation-retry-boundary.db");
        deliverFailedObservation(runtime);

        Turn first = latestCiStageTurn(runtime);
        AgentTurnOwnerResultCodec.OwnerResult stageResult = stageResult(
                runtime, first);
        markResultPending(runtime.jdbc(), first,
                DispatchTicket.Outcome.SUCCEEDED,
                runtime.json().writeValueAsString(stageResult.payload()));
        DispatchTicket.DeliveryReceipt stageAccepted =
                runtime.turnRuntime().deliver(stageResult);
        completeTicket(runtime.dataSource(), first.ticketId(), stageAccepted);

        Effect validation = runtime.jdbc().queryForObject("""
                SELECT operation.operation_id, operation.semantic_attempt,
                       operation.expected_code_fingerprint,
                       operation.expected_head_sha,
                       operation.expected_base_sha,
                       ticket.id AS ticket_id
                  FROM ci_repair_operation operation
                  JOIN dispatch_ticket ticket
                    ON ticket.operation_id = operation.operation_id
                 WHERE operation.kind = 'VALIDATE'
                """, (rs, row) -> new Effect(
                        rs.getString("operation_id"),
                        rs.getInt("semantic_attempt"),
                        rs.getString("expected_code_fingerprint"),
                        rs.getString("expected_head_sha"),
                        rs.getString("expected_base_sha"),
                        rs.getString("ticket_id")));
        RemoteCiRepairRuntimeCoordinator directRepair =
                new RemoteCiRepairRuntimeCoordinator(
                        runtime.commands(), runtime.remoteStore(),
                        ignored -> RemoteCiRepairRuntimeCoordinator
                                .Classification.TASK_BRANCH_REPAIRABLE,
                        new CiBudgets(0, 3, 2, 3), runtime.turnRuntime(),
                        runtime.json(), Clock.fixed(NOW, ZoneOffset.UTC));
        RemoteEffectOperationHandler.Result validationResult =
                new RemoteEffectOperationHandler.Result(
                        1, validation.operationId(),
                        RemoteEffectOperationHandler.Disposition.FAILED,
                        validation.fingerprint(), validation.head(),
                        validation.base(), "tests still fail", null);
        String payload = runtime.json().writeValueAsString(validationResult);
        markResultPending(
                runtime.jdbc(), validation.operationId(), validation.fence(),
                DispatchTicket.Outcome.SUCCEEDED, payload);
        DispatchTicket.DeliveryReceipt validationAccepted =
                directRepair.deliverEffect(
                        new DispatchTicket.OwnerReference(
                                DispatchTicket.OwnerKind.STAGE,
                                "remote-stage-1",
                                "REMOTE_CI_VALIDATION_RESULT"),
                        validation.fence(), new DispatchTicket.DispatchResult(
                                validation.fence(),
                                DispatchTicket.Outcome.SUCCEEDED,
                                payload, payload, null));
        completeTicket(runtime.dataSource(), validation.ticketId(),
                validationAccepted);

        assertThat(runtime.jdbc().queryForMap("""
                SELECT status, source_kind
                  FROM ci_repair_next_fix_due_v318
                """))
                .containsEntry("status", "PENDING")
                .containsEntry("source_kind", "VALIDATION_FAILED");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM ci_repair_operation
                 WHERE kind = 'FIX_STAGE_TURN'
                """, Integer.class)).isOne();

        deliverFailedObservation(runtime, "fresh-after-validation-failure", 801L);

        Turn successor = latestCiStageTurn(runtime);
        assertThat(successor.attempt()).isEqualTo(2);
        String successorPrompt = runtime.json().readTree(
                runtime.turnStore().requireStageTurnLaunchInput(
                        successor.turnId())).path("prompt").asText();
        assertThat(successorPrompt)
                .contains("append-only commits on the current Task branch")
                .contains("Never rewrite base history")
                .contains("amend, rebase, reset, squash, reorder")
                .contains("Do not push")
                .contains("Canonical validation failed");
        assertThat(runtime.jdbc().queryForMap("""
                SELECT operation.base_repair_authorization_id,
                       due.status AS due_status
                  FROM ci_repair_operation operation
                  JOIN ci_repair_next_fix_due_v318 due
                    ON due.ci_repair_episode_id =
                       operation.ci_repair_episode_id
                 WHERE operation.stage_turn_id = ?
                """, successor.turnId()))
                .containsEntry("base_repair_authorization_id", null)
                .containsEntry("due_status", "DISPATCHED");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM ci_base_repair_authorization_v303
                """, Integer.class)).isZero();
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM ci_repair_operation
                 WHERE kind = 'BRAIN_REVIEW'
                """, Integer.class)).isZero();
    }

    @Test
    void historicalCiBrainFailureIsAuditableButCannotBeRetried()
            throws Exception
    {
        FailedBrain failed = failedBrain("remote-brain-retired.db");
        Harness runtime = failed.runtime();

        assertThat(runtime.jdbc().queryForObject(
                "SELECT family FROM remote_repair_brain_failure_receipt_v309 WHERE blocker_id = ?",
                String.class, failed.blockerId())).isEqualTo("CI");
        assertThat(runtime.jdbc().queryForObject(
                "SELECT purpose FROM task_turn WHERE id = ?",
                String.class, failed.brain().turnId()))
                .isEqualTo("REMOTE_CI_BRAIN_REVIEW");
        assertThat(new V2DevelopmentFlowProjection(runtime.jdbc())
                .brain(task()).recovery()).isNull();

        assertThatThrownBy(() -> runtime.turnRuntime().retryFailedBrain(
                "task-1", failed.brain().turnId(), failed.blockerId(),
                "retired-ci-brain-retry", "user", "retry historical CI Brain"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale or ambiguous");

        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM remote_repair_brain_replacement_operation_v309",
                Integer.class)).isZero();
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM remote_repair_brain_retry_command_v309",
                Integer.class)).isZero();
        assertThat(runtime.jdbc().queryForObject(
                "SELECT status FROM task_blocker WHERE id = ?",
                String.class, failed.blockerId())).isEqualTo("OPEN");
        assertThat(runtime.taskStore().findById("task-1").orElseThrow()
                .pendingBrainResult()).isNull();
    }

    @Test
    void historicalCompatibilityBaseRepairBrainCannotBeRetried()
            throws Exception
    {
        FailedBrain exact = failedBrain("compatibility-brain-retry.db");
        seedCompatibilityBaseRewrite(exact, "adopted-head");

        assertThat(new V2DevelopmentFlowProjection(exact.runtime().jdbc())
                .brain(task()).recovery()).isNull();
        assertThatThrownBy(() -> exact.runtime().turnRuntime().retryFailedBrain(
                "task-1", exact.brain().turnId(), exact.blockerId(),
                "retry-compatible-brain", "user",
                "retry the compatibility base-repair Brain"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale or ambiguous");
        assertThat(exact.runtime().jdbc().queryForObject(
                "SELECT COUNT(*) FROM remote_repair_brain_replacement_operation_v309",
                Integer.class)).isZero();
        assertThat(exact.runtime().jdbc().queryForObject(
                "SELECT COUNT(*) FROM remote_repair_brain_retry_command_v309",
                Integer.class)).isZero();
    }

    @Test
    void retryFailsClosedWhenTheRemoteHeadOrBaseMoves()
            throws Exception
    {
        FailedBrain failed = failedBrain("remote-brain-stale.db");
        Harness runtime = failed.runtime();
        try (Connection connection = runtime.dataSource().getConnection()) {
            insertSnapshot(
                    connection, 1, 2, "head-concurrent", "base-2",
                    "OPEN", "MERGEABLE");
            insertFailedCi(
                    connection, 1, 2, "head-concurrent", "base-2");
            acceptSnapshot(
                    connection, 1, 2, "head-concurrent", "base-2");
        }

        assertThatThrownBy(() -> runtime.turnRuntime().retryFailedBrain(
                "task-1", failed.brain().turnId(), failed.blockerId(),
                "stale-retry", "user", "retry stale Remote Brain"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale or ambiguous");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*)
                  FROM remote_repair_brain_replacement_operation_v309
                """, Integer.class)).isZero();
        assertThat(runtime.jdbc().queryForObject("""
                SELECT status FROM task_blocker WHERE id = ?
                """, String.class, failed.blockerId())).isEqualTo("OPEN");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT status FROM ci_repair_episode WHERE id = ?
                """, String.class, failed.episodeId()))
                .isEqualTo(failed.episodeStatus());
    }

    @Test
    void historicalCiBrainRetirementSurvivesAnUnrelatedTaskPolicyRevision()
            throws Exception
    {
        FailedBrain failed = failedBrain("remote-brain-policy-drift.db");
        Harness runtime = failed.runtime();
        TaskManager.State before = runtime.taskStore()
                .findById("task-1").orElseThrow();

        runtime.tasks().revisePolicy(new TaskManager.PolicyCommand(
                new TaskManager.Command(
                        "revise-policy-after-brain-failure", "user", "task-1",
                        before.epoch(), before.version()),
                "policy-after-brain-failure", true, false,
                0, 3, 3, false, null));

        assertThat(runtime.jdbc().queryForObject("""
                SELECT aggregate_version FROM tasks WHERE id = 'task-1'
                """, Long.class)).isEqualTo(before.version() + 1);
        assertThat(new V2DevelopmentFlowProjection(runtime.jdbc())
                .brain(task()).recovery()).isNull();
        assertThatThrownBy(() -> runtime.turnRuntime().retryFailedBrain(
                "task-1", failed.brain().turnId(), failed.blockerId(),
                "retry-after-policy", "user", "retry after policy revision"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale or ambiguous");
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM remote_repair_brain_replacement_operation_v309",
                Integer.class)).isZero();
    }

    @Test
    void failedBranchBrainCanBeReplacedAndApprovedWithoutSpendingStepBudget()
            throws Exception
    {
        FailedBranchBrain failed = failedBranchBrain(
                "branch-brain-retry.db");
        Harness runtime = failed.runtime();

        var retry = runtime.turnRuntime().retryFailedBrain(
                "task-1", failed.brain().turnId(), failed.blockerId(),
                "retry-branch-brain", "user", "retry failed Branch Brain");

        assertThat(runtime.jdbc().queryForMap("""
                SELECT step.status, step.attempt_count, step.attempt_limit,
                       replacement.semantic_attempt,
                       replacement.execution_attempt,
                       replacement.status AS replacement_status,
                       ticket.callback_route
                  FROM branch_sync_effect_step step
                  JOIN remote_repair_brain_replacement_operation_v309 replacement
                    ON replacement.branch_sync_effect_step_id = step.id
                  JOIN dispatch_ticket ticket
                    ON ticket.id = replacement.dispatch_ticket_id
                 WHERE replacement.operation_id = ?
                """, retry.replacementOperationId()))
                .containsEntry("status", "CLAIMED")
                .containsEntry("attempt_count", failed.attemptCount())
                .containsEntry("attempt_limit", failed.attemptLimit())
                .containsEntry("semantic_attempt", failed.attemptCount())
                .containsEntry("execution_attempt", failed.attemptCount() + 1)
                .containsEntry("replacement_status", "DISPATCHED")
                .containsEntry("callback_route", "BRANCH_SYNC_BRAIN_RESULT");

        Turn replacement = turn(runtime, retry.replacementOperationId());
        // The verdict is the row record_development_verdict writes; the final
        // message is the reviewer's own words and nobody parses it.
        recordBrainVerdict(
                runtime, replacement, "APPROVED",
                "rebased repair is safe to push");
        AgentTurnOwnerResultCodec.OwnerResult approved = branchBrainResult(
                runtime.json(), replacement, DispatchTicket.Outcome.SUCCEEDED,
                AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED,
                "The rebase preserves Task intent. Safe to push.", null);
        markResultPending(runtime.jdbc(), replacement,
                DispatchTicket.Outcome.SUCCEEDED,
                runtime.json().writeValueAsString(approved.payload()));
        DispatchTicket.DeliveryReceipt accepted =
                runtime.turnRuntime().deliver(approved);
        completeTicket(runtime.dataSource(), replacement.ticketId(), accepted);

        assertThat(accepted.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(runtime.jdbc().queryForMap("""
                SELECT status, attempt_count, attempt_limit
                  FROM branch_sync_effect_step WHERE id = ?
                """, failed.stepId()))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("attempt_count", failed.attemptCount())
                .containsEntry("attempt_limit", failed.attemptLimit());
        assertThat(runtime.jdbc().queryForMap("""
                SELECT episode.status, step.status AS step_status,
                       step.attempt_count, step.attempt_limit,
                       operation.kind, operation.status AS operation_status,
                       ticket.callback_route
                  FROM branch_sync_episode episode
                  JOIN branch_sync_effect_step step
                    ON step.branch_sync_episode_id = episode.id
                   AND step.ordinal = 6
                  JOIN branch_sync_dispatch_operation operation
                    ON operation.branch_sync_effect_step_id = step.id
                  JOIN dispatch_ticket ticket
                    ON ticket.operation_id = operation.operation_id
                 WHERE episode.id = ?
                """, failed.episodeId()))
                .containsEntry("status", "PUSHING")
                .containsEntry("step_status", "CLAIMED")
                .containsEntry("attempt_count", 1)
                .containsEntry("attempt_limit", failed.attemptLimit())
                .containsEntry("kind", "FORCE_WITH_LEASE_PUSH")
                .containsEntry("operation_status", "DISPATCHED")
                .containsEntry("callback_route", "BRANCH_SYNC_PUSH_RESULT");
    }

    @Test
    void failedBranchReplacementProjectsAnotherExplicitRetry()
            throws Exception
    {
        FailedBranchBrain failed = failedBranchBrain(
                "branch-brain-retry-chain.db");
        Harness runtime = failed.runtime();
        var first = runtime.turnRuntime().retryFailedBrain(
                "task-1", failed.brain().turnId(), failed.blockerId(),
                "retry-branch-one", "user", "retry first Branch Brain failure");
        Turn firstTurn = turn(runtime, first.replacementOperationId());
        AgentTurnOwnerResultCodec.OwnerResult failedReplacement =
                branchBrainResult(
                        runtime.json(), firstTurn, DispatchTicket.Outcome.FAILED,
                        AgentTurnOperationHandler.Disposition.PROVIDER_FAILED,
                        "", "replacement branch Brain provider failed");
        markResultPending(
                runtime.jdbc(), firstTurn, DispatchTicket.Outcome.FAILED,
                runtime.json().writeValueAsString(failedReplacement.payload()));
        DispatchTicket.DeliveryReceipt accepted =
                runtime.turnRuntime().deliver(failedReplacement);
        completeTicket(runtime.dataSource(), firstTurn.ticketId(), accepted);

        String blockerId = runtime.jdbc().queryForObject("""
                SELECT id FROM task_blocker
                 WHERE blocker_type = 'REMOTE_REPAIR_BRAIN_FAILED'
                   AND subject_revision = ? AND status = 'OPEN'
                """, String.class, first.replacementTurnId());
        TaskBrainViewData.RecoveryAction recovery =
                new V2DevelopmentFlowProjection(runtime.jdbc())
                        .brain(task()).recovery();

        assertThat(recovery).isEqualTo(new TaskBrainViewData.RecoveryAction(
                "RETRY_REMOTE_REPAIR_BRAIN_REVIEW", "remote-stage-1",
                blockerId, first.replacementTurnId()));
        assertThat(runtime.turnRuntime().retryFailedBrain(
                "task-1", first.replacementTurnId(), blockerId,
                "retry-branch-two", "user",
                "retry replacement Branch Brain failure")
                .replacementTurnId()).isNotEqualTo(first.replacementTurnId());
    }

    @Test
    void onlyAnUnreportedVerdictIsSweptForAnAutomaticSecondReview()
            throws Exception
    {
        // The automatic sweep offers one replacement to a review that ended
        // without calling record_development_verdict. A review whose provider
        // genuinely failed is a different problem and stays for the user, so
        // the sweep discriminates on the reason the gate wrote.
        FailedBranchBrain failed = failedBranchBrain("branch-brain-sweep.db");
        SqliteStageSteeringStore steering =
                new SqliteStageSteeringStore(failed.runtime().jdbc());

        assertThat(steering.findParkedRemoteBrainReviews(
                "succeeded without record_development_verdict", 32)).isEmpty();

        failed.runtime().jdbc().update("""
                UPDATE task_blocker SET payload_json = ?
                 WHERE id = ?
                """,
                "{\"error\":\"OWNER_OUTPUT_MALFORMED: Remote repair Brain "
                        + "succeeded without record_development_verdict\"}",
                failed.blockerId());

        assertThat(steering.findParkedRemoteBrainReviews(
                "succeeded without record_development_verdict", 32))
                .singleElement()
                .satisfies(parked -> {
                    assertThat(parked.taskId()).isEqualTo("task-1");
                    assertThat(parked.blockerId()).isEqualTo(failed.blockerId());
                    assertThat(parked.failedTurnId())
                            .isEqualTo(failed.brain().turnId());
                });
    }

    @Test
    void malformedCiStageOutputTerminatesOnceAndSuppressesTheSameSubject()
            throws Exception
    {
        assertCiStageFailureTerminatesOnce(
                "ci-stage-malformed.db",
                AgentTurnOperationHandler.Disposition.OWNER_OUTPUT_MALFORMED,
                "not strict JSON",
                "OWNER_OUTPUT_MALFORMED: expected a JSON object",
                "CI_REPAIR_OUTPUT_MALFORMED");
    }

    @Test
    void blankMalformedCiStageOutputFailsClosedWithoutNormalization()
            throws Exception
    {
        assertCiStageFailureTerminatesOnce(
                "ci-stage-blank-malformed.db",
                AgentTurnOperationHandler.Disposition.OWNER_OUTPUT_MALFORMED,
                "",
                "OWNER_OUTPUT_MALFORMED: expected a JSON object",
                "CI_REPAIR_OUTPUT_MALFORMED");
    }

    @Test
    void failedCiStageExecutionTerminatesOnceAndSuppressesTheSameSubject()
            throws Exception
    {
        assertCiStageFailureTerminatesOnce(
                "ci-stage-provider-failed.db",
                AgentTurnOperationHandler.Disposition.PROVIDER_FAILED,
                "",
                "provider session failed",
                "CI_REPAIR_TURN_FAILED");
    }

    @Test
    void malformedBranchStageOutputCreatesOneExactSubjectExhaustion()
            throws Exception
    {
        Harness runtime = harness("branch-stage-malformed.db");
        try (Connection connection = runtime.dataSource().getConnection()) {
            insertSnapshot(
                    connection, 1, 1, "head-1", "base-1",
                    "OPEN", "MERGEABLE");
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");
            connection.createStatement().executeUpdate("""
                    INSERT INTO task_branch_sync_policy_revision(
                        id, task_id, revision, enabled, schedule, source,
                        attempt_limit, command_id, actor, created_at_ms)
                    VALUES ('branch-malformed-policy', 'task-1', 1, 1,
                        'on-base-change', 'USER_CONFIGURED', 2,
                        'configure-branch-malformed', 'test', 900)
                    """);
        }
        RemoteContext remote = runtime.remoteStore().requireRemoteContext(
                "task-1", "remote-stage-1");
        BranchEpisode episode = runtime.commands().execute("task-1", () ->
                runtime.remoteStore().insertBranchEpisode(
                        remote, "branch-malformed", "base-2",
                        "branch-malformed-policy", "USER_CONFIGURED", 2, NOW));
        assertThat(runtime.jdbc().update("""
                UPDATE branch_sync_effect_step
                   SET status = 'SUCCEEDED', attempt_count = 1,
                       evidence = 'prior exact branch step',
                       completed_at_ms = 999
                 WHERE branch_sync_episode_id = ? AND ordinal < 3
                """, episode.id())).isEqualTo(2);
        SqliteRemoteRepairTurnStore.RepairContext context =
                runtime.turnStore().requireContext(
                        "task-1", "remote-stage-1");
        BranchStep step = runtime.remoteStore().requireBranchStep(
                episode.id(), 3);
        String branchLaunch = branchStageLaunch(runtime.json(), step);
        var request = runtime.commands().execute("task-1", () ->
                runtime.turnStore().insertBranchStageTurn(
                        context, episode, step, branchLaunch,
                        "API", 2, NOW.plusMillis(1)));
        Turn turn = stageTurn(runtime, request.operationId());
        String error = "OWNER_OUTPUT_MALFORMED: expected a JSON object";
        AgentTurnOwnerResultCodec.OwnerResult malformed = stageFailureResult(
                runtime.json(), turn, "BRANCH_CONFLICT_REPAIR",
                RemoteRepairTurnRuntime.BRANCH_STAGE_CALLBACK,
                AgentTurnOperationHandler.Disposition.OWNER_OUTPUT_MALFORMED,
                "rebased successfully in prose", error);
        markResultPending(
                runtime.jdbc(), turn, DispatchTicket.Outcome.FAILED,
                runtime.json().writeValueAsString(malformed.payload()));

        DispatchTicket.DeliveryReceipt accepted =
                runtime.turnRuntime().deliver(malformed);
        completeTicket(runtime.dataSource(), turn.ticketId(), accepted);

        assertThat(runtime.jdbc().queryForMap("""
                SELECT episode.status, episode.attempt_count,
                       step.status AS step_status, step.attempt_count AS step_attempts,
                       operation.status AS operation_status,
                       blocker.blocker_type, blocker.status AS blocker_status
                  FROM branch_sync_episode episode
                  JOIN branch_sync_effect_step step
                    ON step.branch_sync_episode_id = episode.id
                   AND step.ordinal = 3
                  JOIN branch_sync_dispatch_operation operation
                    ON operation.branch_sync_effect_step_id = step.id
                  JOIN branch_sync_exhaustion_v319 exhaustion
                    ON exhaustion.branch_sync_episode_id = episode.id
                  JOIN task_blocker blocker ON blocker.id = exhaustion.blocker_id
                 WHERE episode.id = ?
                """, episode.id()))
                .containsEntry("status", "FAILED")
                .containsEntry("attempt_count", 0)
                .containsEntry("step_status", "FAILED")
                .containsEntry("step_attempts", 1)
                .containsEntry("operation_status", "FAILED")
                .containsEntry("blocker_type", "BRANCH_SYNC_EXHAUSTED")
                .containsEntry("blocker_status", "OPEN");
        assertThat(runtime.remoteStore().hasExactBranchSyncExhaustion(
                remote, runtime.remoteStore().requireCodeSubject("task-1")))
                .isTrue();
        assertThat(runtime.turnRuntime().deliver(malformed).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM branch_sync_exhaustion_v319
                 WHERE branch_sync_episode_id = ?
                """, Integer.class, episode.id())).isOne();
    }

    private void assertCiStageFailureTerminatesOnce(
            String database,
            AgentTurnOperationHandler.Disposition disposition,
            String finalText,
            String error,
            String blockerType)
            throws Exception
    {
        Harness runtime = harness(database);
        if (disposition == AgentTurnOperationHandler.Disposition
                .OWNER_OUTPUT_MALFORMED
                && finalText != null && !finalText.isBlank()) {
            try (Connection connection = runtime.dataSource().getConnection()) {
                insertSnapshot(connection, 1, 1, "head-1", "base-1",
                        "OPEN", "MERGEABLE");
                insertFailedCi(connection, 1, 1, "head-1", "base-1");
                acceptSnapshot(connection, 1, 1, "head-1", "base-1");
                insertRevisionBackedCurrentSubject(connection);
            }
        }
        deliverFailedObservation(runtime);
        String episodeId = runtime.jdbc().queryForObject(
                "SELECT id FROM ci_repair_episode WHERE id <> 'revision-episode'",
                String.class);
        Turn turn = latestCiStageTurn(runtime);
        AgentTurnOwnerResultCodec.OwnerResult failed = stageFailureResult(
                runtime.json(), turn, "REMOTE_CI_REPAIR",
                RemoteRepairTurnRuntime.CI_STAGE_CALLBACK,
                disposition, finalText, error);
        markResultPending(
                runtime.jdbc(), turn, DispatchTicket.Outcome.FAILED,
                runtime.json().writeValueAsString(failed.payload()));

        DispatchTicket.DeliveryReceipt accepted =
                runtime.turnRuntime().deliver(failed);
        completeTicket(runtime.dataSource(), turn.ticketId(), accepted);

        assertThat(accepted.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(runtime.jdbc().queryForMap("""
                SELECT episode.status, episode.fix_attempt_count,
                       episode.push_count, operation.status AS operation_status,
                       turn.status AS turn_status, receipt.raw_outcome,
                       receipt.acceptance, blocker.blocker_type,
                       blocker.status AS blocker_status, blocker.payload_json
                  FROM ci_repair_episode episode
                  JOIN ci_repair_operation operation
                    ON operation.ci_repair_episode_id = episode.id
                   AND operation.kind = 'FIX_STAGE_TURN'
                  JOIN stage_turn turn ON turn.id = operation.stage_turn_id
                  JOIN ci_repair_delivery_receipt receipt
                    ON receipt.ci_repair_operation_id = operation.id
                  JOIN task_blocker blocker
                    ON blocker.owner_kind = 'EPISODE'
                   AND blocker.owner_id = episode.id
                 WHERE episode.id = ? AND blocker.blocker_type = ?
                """, episodeId, blockerType))
                .containsEntry("status", "FIXING")
                .containsEntry("fix_attempt_count", 0)
                .containsEntry("push_count", 0)
                .containsEntry("operation_status", "FAILED")
                .containsEntry("turn_status", "FAILED")
                .containsEntry("raw_outcome", "FAILED")
                .containsEntry("acceptance", "ACCEPTED")
                .containsEntry("blocker_type", blockerType)
                .containsEntry("blocker_status", "OPEN")
                .hasEntrySatisfying("payload_json", value -> assertThat(value)
                        .asString()
                        .contains("MANUAL_TAKEOVER", "STOP_AUTOMATION")
                        .doesNotContain("EXTEND_BUDGET"));
        assertThatThrownBy(() -> runtime.jdbc().update("""
                UPDATE ci_repair_delivery_receipt
                   SET acceptance = 'SUPERSEDED' WHERE operation_id = ?
                """, turn.operationId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("delivery receipt is immutable");
        assertThat(runtime.turnRuntime().deliver(failed).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        boolean normalizationEligible = disposition
                == AgentTurnOperationHandler.Disposition
                        .OWNER_OUTPUT_MALFORMED
                && finalText != null
                && !finalText.isBlank();
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*)
                  FROM remote_repair_result_normalization_due_v322
                 WHERE source_operation_id = ?
                """, Integer.class, turn.operationId()))
                .isEqualTo(normalizationEligible ? 1 : 0);
        if (normalizationEligible) {
            runtime.turnRuntime().maintain(NOW.plusMillis(2));
            String sourceRawDigest = runtime.jdbc().queryForObject("""
                    SELECT source_raw_result_digest
                      FROM remote_repair_result_normalization_due_v322
                     WHERE source_operation_id = ?
                    """, String.class, turn.operationId());
            Long sourceCodeSubjectRevision = runtime.jdbc().queryForObject("""
                    SELECT source_code_subject_revision
                      FROM remote_repair_result_normalization_due_v322
                     WHERE source_operation_id = ?
                    """, Long.class, turn.operationId());
            String sourceCodeSubjectKind = runtime.jdbc().queryForObject("""
                    SELECT source_code_subject_kind
                      FROM remote_repair_result_normalization_due_v322
                     WHERE source_operation_id = ?
                    """, String.class, turn.operationId());
            String sourceCodeSubjectId = runtime.jdbc().queryForObject("""
                    SELECT source_code_subject_id
                      FROM remote_repair_result_normalization_due_v322
                     WHERE source_operation_id = ?
                    """, String.class, turn.operationId());
            assertThat(runtime.jdbc().queryForMap("""
                    SELECT turn.purpose, ticket.owner_kind,
                           ticket.callback_route, ticket.writer_required,
                           json_extract(turn.launch_input,
                               '$.systemPrompt') AS system_prompt,
                           json_extract(turn.launch_input,
                               '$.prompt') AS prompt,
                           json_extract(turn.launch_input,
                               '$.toolEndpoint.profile') AS tool_profile,
                           json_type(turn.launch_input,
                               '$.resumeSessionId') AS resume_type,
                           json_type(turn.launch_input,
                               '$.fallbackPrompt') AS fallback_type,
                           json_type(turn.launch_input,
                               '$.toolEndpoint.approvalPromptTool')
                               AS approval_prompt_type,
                           COALESCE(json_array_length(json_extract(
                               turn.launch_input, '$.images')), 0) AS image_count
                      FROM remote_repair_result_normalization_operation_v322 op
                      JOIN task_turn turn
                        ON turn.id = op.normalization_task_turn_id
                      JOIN dispatch_ticket ticket
                        ON ticket.id = op.dispatch_ticket_id
                     WHERE op.source_operation_id = ?
                    """, turn.operationId()))
                    .containsEntry("purpose",
                            "REMOTE_REPAIR_RESULT_NORMALIZATION")
                    .containsEntry("owner_kind", "TASK_TURN")
                    .containsEntry("callback_route",
                            RemoteRepairTurnRuntime.NORMALIZATION_CALLBACK)
                    .containsEntry("writer_required", 0)
                    .containsEntry("system_prompt", """
                            You are a syntax-only result normalizer. Do not inspect files, use tools, edit the workspace, or perform remote effects.
                            Return exactly one raw JSON object shaped {"schemaVersion":1,"summary":"string"}.
                            Preserve the meaning of the frozen malformed result. Do not add fields, Markdown fences, or surrounding prose.
                            """)
                    .containsEntry("prompt", """
                            Normalize this frozen malformed Remote CI repair result into the required shape.

                            Required shape:
                            {"schemaVersion":1,"summary":"string"}

                            Source trace:
                            sourceOperationId=%s
                            sourceRawResultDigest=%s
                            taskId=task-1
                            taskEpoch=1
                            stageId=remote-stage-1
                            stageGeneration=1
                            sourceCodeSubjectRevision=%d
                            sourceCodeSubjectKind=%s
                            sourceCodeSubjectId=%s
                            expectedCodeFingerprint=%s
                            expectedHeadSha=%s
                            expectedBaseSha=%s

                            Frozen malformed output encoded as one JSON string:
                            %s""".formatted(
                                    turn.operationId(), sourceRawDigest,
                                    sourceCodeSubjectRevision,
                                    sourceCodeSubjectKind,
                                    sourceCodeSubjectId,
                                    turn.fingerprint(), turn.head(), turn.base(),
                                    "\"not strict JSON\""))
                    .containsEntry("tool_profile", "TASK_BRAIN_READ_ONLY")
                    .containsEntry("resume_type", null)
                    .containsEntry("fallback_type", null)
                    .containsEntry("approval_prompt_type", null)
                    .containsEntry("image_count", 0);

            String normalizationOperationId = runtime.jdbc().queryForObject("""
                    SELECT operation_id
                      FROM remote_repair_result_normalization_operation_v322
                     WHERE source_operation_id = ?
                    """, String.class, turn.operationId());
            Turn normalizationTurn = turn(
                    runtime, normalizationOperationId);
            AgentTurnOwnerResultCodec.OwnerResult normalized =
                    normalizationResult(runtime.json(), normalizationTurn);
            markResultPending(
                    runtime.jdbc(), normalizationTurn,
                    DispatchTicket.Outcome.SUCCEEDED,
                    runtime.json().writeValueAsString(normalized.payload()));
            DispatchTicket.DeliveryReceipt normalizedAccepted =
                    runtime.turnRuntime().deliver(normalized);
            completeTicket(
                    runtime.dataSource(), normalizationTurn.ticketId(),
                    normalizedAccepted);
            assertThat(normalizedAccepted.acceptance())
                    .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
            assertThat(runtime.turnRuntime().deliver(normalized))
                    .isEqualTo(normalizedAccepted);
            assertThat(runtime.jdbc().queryForObject("""
                    SELECT COUNT(*)
                      FROM remote_repair_commit_adoption_operation_v322
                     WHERE normalization_operation_row_id = (
                         SELECT id
                           FROM remote_repair_result_normalization_operation_v322
                          WHERE operation_id = ?)
                    """, Integer.class, normalizationOperationId)).isOne();
            assertThat(runtime.jdbc().queryForMap("""
                    SELECT normalization.status AS normalization_status,
                           adoption.status AS adoption_status,
                           ticket.operation_kind, ticket.owner_kind,
                           ticket.owner_id, episode.fix_attempt_count,
                           blocker.status AS blocker_status,
                           json_extract(normalization.terminal_evidence,
                               '$.schemaVersion') AS evidence_schema,
                           json_extract(normalization.terminal_evidence,
                               '$.sourceOperationId') AS evidence_source
                      FROM remote_repair_result_normalization_operation_v322
                           normalization
                      JOIN remote_repair_commit_adoption_operation_v322 adoption
                        ON adoption.normalization_operation_row_id =
                           normalization.id
                      JOIN dispatch_ticket ticket
                        ON ticket.id = adoption.dispatch_ticket_id
                      JOIN ci_repair_episode episode
                        ON episode.id = adoption.ci_repair_episode_id
                      JOIN task_blocker blocker
                        ON blocker.id = adoption.blocker_id
                     WHERE normalization.operation_id = ?
                    """, normalizationOperationId))
                    .containsEntry("normalization_status", "SUCCEEDED")
                    .containsEntry("adoption_status", "DISPATCHED")
                    .containsEntry("operation_kind",
                            "ADOPT_NORMALIZED_REMOTE_REPAIR")
                    .containsEntry("owner_kind", "TASK")
                    .containsEntry("owner_id", "task-1")
                    .containsEntry("fix_attempt_count", 0)
                    .containsEntry("blocker_status", "OPEN")
                    .containsEntry("evidence_schema", 1)
                    .containsEntry("evidence_source", turn.operationId());
        }

        deliverFailedObservation(runtime, "same-subject-after-failure", 803L);

        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM ci_repair_episode "
                        + "WHERE id <> 'revision-episode'",
                Integer.class)).isOne();
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM ci_repair_operation
                 WHERE kind = 'FIX_STAGE_TURN'
                   AND id <> 'revision-operation-row'
                """, Integer.class)).isOne();
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM task_blocker
                 WHERE owner_id = ? AND blocker_type = ?
                """, Integer.class, episodeId, blockerType)).isOne();
        assertThat(runtime.jdbc().queryForMap("""
                SELECT status, fix_attempt_count, push_count
                  FROM ci_repair_episode WHERE id = ?
                """, episodeId))
                .containsEntry("status", "FIXING")
                .containsEntry("fix_attempt_count", 0)
                .containsEntry("push_count", 0);
    }

    @Test
    void equalTreeCiFixParksOneSameBudgetContinuationThenBlocks()
            throws Exception
    {
        Harness runtime = harness("ci-fix-no-change.db");
        deliverFailedObservation(runtime);
        String episodeId = runtime.jdbc().queryForObject(
                "SELECT id FROM ci_repair_episode", String.class);
        Turn first = latestCiStageTurn(runtime);
        AgentTurnOwnerResultCodec.OwnerResult firstNoChange = stageResult(
                runtime, first, "tree-1", "tree-1");
        markResultPending(runtime.jdbc(), first,
                DispatchTicket.Outcome.SUCCEEDED,
                runtime.json().writeValueAsString(firstNoChange.payload()));

        DispatchTicket.DeliveryReceipt firstAccepted =
                runtime.turnRuntime().deliver(firstNoChange);
        completeTicket(runtime.dataSource(), first.ticketId(), firstAccepted);

        assertThat(firstAccepted.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(runtime.jdbc().queryForMap("""
                SELECT episode.fix_attempt_count, operation.status,
                       due.status AS due_status,
                       due.semantic_attempt, due.execution_attempt,
                       due.predecessor_accepted_snapshot_id,
                       due.predecessor_accepted_observation_revision
                  FROM ci_repair_episode episode
                  JOIN ci_repair_operation operation
                    ON operation.ci_repair_episode_id = episode.id
                   AND operation.kind = 'FIX_STAGE_TURN'
                  JOIN ci_repair_fix_continuation_due_v318 due
                    ON due.ci_repair_episode_id = episode.id
                 WHERE episode.id = ?
                """, episodeId))
                .containsEntry("fix_attempt_count", 0)
                .containsEntry("status", "FAILED")
                .containsEntry("due_status", "PENDING")
                .containsEntry("semantic_attempt", 1)
                .containsEntry("execution_attempt", 2)
                .containsKey("predecessor_accepted_snapshot_id")
                .containsEntry("predecessor_accepted_observation_revision", 1);
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM ci_repair_operation
                 WHERE ci_repair_episode_id = ? AND kind = 'VALIDATE'
                """, Integer.class, episodeId)).isZero();
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM remote_code_subject
                 WHERE stage_turn_id = ?
                """, Integer.class, first.turnId())).isZero();
        assertThatThrownBy(() -> runtime.jdbc().update("""
                UPDATE ci_repair_episode SET fix_attempt_count = 1 WHERE id = ?
                """, episodeId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("exact accepted result");

        // Exact redelivery is receipt replay; it cannot create another due row.
        assertThat(runtime.turnRuntime().deliver(firstNoChange).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM ci_repair_fix_continuation_due_v318
                 WHERE ci_repair_episode_id = ?
                """, Integer.class, episodeId)).isOne();

        CiEpisode episode = runtime.remoteStore().requireCiEpisode(
                "task-1", episodeId);
        assertThatThrownBy(() -> runtime.commands().execute("task-1", () ->
                runtime.turnRuntime()
                        .startPendingCiNoChangeContinuationInCommand(episode)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("freshness authorization");

        // The terminal result only parks the continuation. A distinct accepted
        // Remote observation is the authority that may dispatch it.
        deliverFailedObservation(runtime, "failed-ci-after-no-change", 801L);
        Turn second = latestCiStageTurn(runtime);
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(runtime.jdbc().queryForMap("""
                SELECT operation.semantic_attempt, operation.execution_attempt,
                       operation.status, due.status AS due_status
                  FROM ci_repair_fix_continuation_operation_v318 operation
                  JOIN ci_repair_fix_continuation_due_v318 due
                    ON due.id = operation.continuation_due_id
                 WHERE operation.ci_repair_episode_id = ?
                """, episodeId))
                .containsEntry("semantic_attempt", 1)
                .containsEntry("execution_attempt", 2)
                .containsEntry("status", "DISPATCHED")
                .containsEntry("due_status", "DISPATCHED");
        String correctivePrompt = runtime.json().readTree(
                runtime.turnStore().requireStageTurnLaunchInput(second.turnId()))
                .path("prompt").asText();
        assertThat(correctivePrompt)
                .contains("owns every failed check")
                .contains("sourceTreeSha and resultTreeSha were equal")
                .contains("consumed no CI fix budget")
                .contains("Do not create an empty commit");

        AgentTurnOwnerResultCodec.OwnerResult secondNoChange = stageResult(
                runtime, second, "tree-1", "tree-1");
        markResultPending(runtime.jdbc(), second,
                DispatchTicket.Outcome.SUCCEEDED,
                runtime.json().writeValueAsString(secondNoChange.payload()));
        DispatchTicket.DeliveryReceipt secondAccepted =
                runtime.turnRuntime().deliver(secondNoChange);
        completeTicket(runtime.dataSource(), second.ticketId(), secondAccepted);

        assertThat(runtime.jdbc().queryForObject("""
                SELECT fix_attempt_count FROM ci_repair_episode WHERE id = ?
                """, Integer.class, episodeId)).isZero();
        assertThat(runtime.jdbc().queryForMap("""
                SELECT blocker_type, status, payload_json FROM task_blocker
                 WHERE owner_id = ? AND blocker_type = 'CI_REPAIR_NO_CHANGE'
                """, episodeId))
                .containsEntry("blocker_type", "CI_REPAIR_NO_CHANGE")
                .containsEntry("status", "OPEN")
                .hasEntrySatisfying("payload_json", value -> assertThat(value)
                        .asString().contains(
                                "RETRY_ONCE", "MANUAL_TAKEOVER",
                                "STOP_AUTOMATION"));
        assertThat(runtime.commands().execute("task-1", () ->
                runtime.turnRuntime()
                        .startPendingCiNoChangeContinuationInCommand(
                                runtime.remoteStore().requireCiEpisode(
                                        "task-1", episodeId))))
                .isFalse();

        String blockerId = runtime.jdbc().queryForObject("""
                SELECT id FROM task_blocker
                 WHERE owner_id = ? AND blocker_type = 'CI_REPAIR_NO_CHANGE'
                """, String.class, episodeId);
        runtime.repair().retryNoChange(
                "task-1", episodeId, blockerId, "retry-no-change-once",
                "user", "allow one final substantive repair");
        runtime.repair().retryNoChange(
                "task-1", episodeId, blockerId, "retry-no-change-once",
                "user", "allow one final substantive repair");

        assertThat(runtime.jdbc().queryForMap("""
                SELECT episode.fix_attempt_count,
                       authorization.consumed_at_ms,
                       due.status AS due_status, due.semantic_attempt,
                       due.execution_attempt, blocker.status AS blocker_status
                  FROM ci_repair_episode episode
                  JOIN ci_repair_no_change_retry_authorization_v318 authorization
                    ON authorization.ci_repair_episode_id = episode.id
                  JOIN ci_repair_fix_continuation_due_v318 due
                    ON due.recovery_authorization_id = authorization.id
                  JOIN task_blocker blocker ON blocker.id = authorization.blocker_id
                 WHERE episode.id = ?
                """, episodeId))
                .containsEntry("fix_attempt_count", 0)
                .containsKey("consumed_at_ms")
                .containsEntry("due_status", "PENDING")
                .containsEntry("semantic_attempt", 1)
                .containsEntry("execution_attempt", 3)
                .containsEntry("blocker_status", "RESOLVED");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*)
                  FROM ci_repair_fix_continuation_operation_v318
                 WHERE ci_repair_episode_id = ?
                """, Integer.class, episodeId)).isOne();
        assertThatThrownBy(() -> runtime.commands().execute("task-1", () ->
                runtime.turnRuntime()
                        .startPendingCiNoChangeContinuationInCommand(
                                runtime.remoteStore().requireCiEpisode(
                                        "task-1", episodeId))))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("freshness authorization");

        deliverFailedObservation(
                runtime, "failed-ci-after-user-no-change-retry", 802L);
        Turn third = latestCiStageTurn(runtime);
        assertThat(third.attempt()).isEqualTo(3);
        assertThat(runtime.jdbc().queryForObject("""
                SELECT fix_attempt_count FROM ci_repair_episode WHERE id = ?
                """, Integer.class, episodeId)).isZero();
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*)
                  FROM ci_repair_no_change_retry_authorization_v318
                 WHERE ci_repair_episode_id = ?
                """, Integer.class, episodeId)).isOne();

        AgentTurnOwnerResultCodec.OwnerResult thirdNoChange = stageResult(
                runtime, third, "tree-1", "tree-1");
        markResultPending(runtime.jdbc(), third,
                DispatchTicket.Outcome.SUCCEEDED,
                runtime.json().writeValueAsString(thirdNoChange.payload()));
        DispatchTicket.DeliveryReceipt thirdAccepted =
                runtime.turnRuntime().deliver(thirdNoChange);
        completeTicket(runtime.dataSource(), third.ticketId(), thirdAccepted);

        assertThat(runtime.jdbc().queryForMap("""
                SELECT blocker_type, status, payload_json
                  FROM task_blocker
                 WHERE owner_id = ?
                   AND blocker_type =
                       'CI_REPAIR_NO_CHANGE_RETRY_EXHAUSTED'
                """, episodeId))
                .containsEntry(
                        "blocker_type", "CI_REPAIR_NO_CHANGE_RETRY_EXHAUSTED")
                .containsEntry("status", "OPEN")
                .hasEntrySatisfying("payload_json", value -> assertThat(value)
                        .asString()
                        .contains("MANUAL_TAKEOVER", "STOP_AUTOMATION")
                        .doesNotContain("RETRY_ONCE"));
        assertThatThrownBy(() -> runtime.repair().retryNoChange(
                "task-1", episodeId,
                runtime.jdbc().queryForObject("""
                        SELECT id FROM task_blocker
                         WHERE owner_id = ? AND status = 'OPEN'
                        """, String.class, episodeId),
                "retry-no-change-twice", "user", "try again"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact CI no-change retry source");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT fix_attempt_count FROM ci_repair_episode WHERE id = ?
                """, Integer.class, episodeId)).isZero();
    }

    @Test
    void legacyCiResultWithoutTreeProofFailsClosedOnce()
            throws Exception
    {
        Harness runtime = harness("ci-fix-legacy-tree-proof.db");
        deliverFailedObservation(runtime);
        String episodeId = runtime.jdbc().queryForObject(
                "SELECT id FROM ci_repair_episode", String.class);
        Turn stage = latestCiStageTurn(runtime);
        AgentTurnOwnerResultCodec.OwnerResult legacy =
                legacyStageResultWithoutTrees(runtime, stage);
        markResultPending(runtime.jdbc(), stage,
                DispatchTicket.Outcome.SUCCEEDED,
                runtime.json().writeValueAsString(legacy.payload()));

        DispatchTicket.DeliveryReceipt accepted =
                runtime.turnRuntime().deliver(legacy);

        assertThat(accepted.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(runtime.jdbc().queryForMap("""
                SELECT episode.status, episode.fix_attempt_count,
                       operation.status AS operation_status,
                       blocker.blocker_type, blocker.status AS blocker_status
                  FROM ci_repair_episode episode
                  JOIN ci_repair_operation operation
                    ON operation.ci_repair_episode_id = episode.id
                  JOIN task_blocker blocker
                    ON blocker.owner_id = episode.id
                 WHERE episode.id = ?
                   AND blocker.blocker_type =
                       'CI_REPAIR_OUTPUT_PROOF_MISSING'
                """, episodeId))
                .containsEntry("status", "FIXING")
                .containsEntry("fix_attempt_count", 0)
                .containsEntry("operation_status", "FAILED")
                .containsEntry("blocker_status", "OPEN");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM ci_repair_delivery_receipt
                 WHERE operation_id = ?
                """, Integer.class, stage.operationId())).isOne();
        assertThat(runtime.turnRuntime().deliver(legacy).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM ci_repair_delivery_receipt
                 WHERE operation_id = ?
                """, Integer.class, stage.operationId())).isOne();
    }

    @Test
    void changedTreeConsumesBudgetExactlyOnceBeforeValidation()
            throws Exception
    {
        Harness runtime = harness("ci-fix-changed-tree.db");
        deliverFailedObservation(runtime);
        String episodeId = runtime.jdbc().queryForObject(
                "SELECT id FROM ci_repair_episode", String.class);
        Turn stage = latestCiStageTurn(runtime);
        AgentTurnOwnerResultCodec.OwnerResult changed = stageResult(
                runtime, stage, "tree-1", "tree-2");
        markResultPending(runtime.jdbc(), stage,
                DispatchTicket.Outcome.SUCCEEDED,
                runtime.json().writeValueAsString(changed.payload()));

        DispatchTicket.DeliveryReceipt accepted =
                runtime.turnRuntime().deliver(changed);

        assertThat(runtime.jdbc().queryForMap("""
                SELECT episode.fix_attempt_count, episode.status,
                       result.disposition, result.source_tree_sha,
                       result.result_tree_sha
                  FROM ci_repair_episode episode
                  JOIN ci_repair_fix_tree_result_v318 result
                    ON result.ci_repair_episode_id = episode.id
                 WHERE episode.id = ?
                """, episodeId))
                .containsEntry("fix_attempt_count", 1)
                .containsEntry("status", "VALIDATING")
                .containsEntry("disposition", "CHANGED")
                .containsEntry("source_tree_sha", "tree-1")
                .containsEntry("result_tree_sha", "tree-2");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM ci_repair_operation
                 WHERE ci_repair_episode_id = ? AND kind = 'VALIDATE'
                   AND semantic_attempt = 1
                """, Integer.class, episodeId)).isOne();
        assertThat(runtime.turnRuntime().deliver(changed).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(runtime.jdbc().queryForObject("""
                SELECT fix_attempt_count FROM ci_repair_episode WHERE id = ?
                """, Integer.class, episodeId)).isOne();
        completeTicket(runtime.dataSource(), stage.ticketId(), accepted);
    }

    private FailedBranchBrain failedBranchBrain(String fileName)
            throws Exception
    {
        Harness runtime = harness(fileName);
        try (Connection connection = runtime.dataSource().getConnection()) {
            insertSnapshot(
                    connection, 1, 1, "head-1", "base-1",
                    "OPEN", "MERGEABLE");
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");
            connection.createStatement().executeUpdate("""
                    INSERT INTO task_branch_sync_policy_revision(
                        id, task_id, revision, enabled, schedule, source,
                        attempt_limit, command_id, actor, created_at_ms)
                    VALUES ('branch-policy-1', 'task-1', 1, 1,
                        'on-base-change', 'USER_CONFIGURED', 2,
                        'configure-branch-policy', 'test', 900)
                    """);
        }
        RemoteContext remote = runtime.remoteStore().requireRemoteContext(
                "task-1", "remote-stage-1");
        BranchEpisode episode = runtime.commands().execute("task-1", () ->
                runtime.remoteStore().insertBranchEpisode(
                        remote, "branch-brain-recovery", "base-2",
                        "branch-policy-1", "USER_CONFIGURED", 2, NOW));
        assertThat(runtime.jdbc().update("""
                UPDATE branch_sync_effect_step
                   SET status = CASE WHEN ordinal = 3 THEN 'SKIPPED'
                                     ELSE 'SUCCEEDED' END,
                       attempt_count = CASE WHEN ordinal = 3 THEN 0 ELSE 1 END,
                       evidence = 'prior exact branch step',
                       completed_at_ms = 999
                 WHERE branch_sync_episode_id = ? AND ordinal <= 4
                """, episode.id())).isEqualTo(4);
        assertThat(runtime.jdbc().update("""
                UPDATE branch_sync_episode SET status = 'VALIDATING'
                 WHERE id = ? AND status = 'OPEN'
                """, episode.id())).isOne();

        SqliteRemoteRepairTurnStore.RepairContext context =
                runtime.turnStore().requireContext(
                        "task-1", "remote-stage-1");
        BranchStep step = runtime.remoteStore().requireBranchStep(
                episode.id(), 5);
        String launch = branchBrainLaunch(runtime.json(), step);
        BrainRequest brain = runtime.commands().execute("task-1", () ->
                runtime.turnStore().insertBranchBrain(
                        context, episode, step, launch,
                        "API", 2, NOW.plusMillis(1)));
        CommandResult<TaskManager.State> requested =
                runtime.commands().execute("task-1", () ->
                        runtime.tasks().requestBrainReviewInCommand(
                                new TaskManager.BrainReviewRequestCommand(
                                        "request-test-branch-brain", "test",
                                        "task-1", context.taskEpoch(),
                                        context.taskVersion(), brain.rowId(),
                                        brain.fence())));
        assertThat(requested.disposition())
                .isEqualTo(CommandResult.Disposition.APPLIED);

        Turn brainTurn = turn(runtime, brain.operationId());
        AgentTurnOwnerResultCodec.OwnerResult failed = branchBrainResult(
                runtime.json(), brainTurn, DispatchTicket.Outcome.FAILED,
                AgentTurnOperationHandler.Disposition.PROVIDER_FAILED,
                "", "branch Brain provider failed");
        markResultPending(runtime.jdbc(), brainTurn,
                DispatchTicket.Outcome.FAILED,
                runtime.json().writeValueAsString(failed.payload()));
        DispatchTicket.DeliveryReceipt accepted =
                runtime.turnRuntime().deliver(failed);
        completeTicket(runtime.dataSource(), brainTurn.ticketId(), accepted);
        assertThat(accepted.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);

        String blockerId = runtime.jdbc().queryForObject("""
                SELECT id FROM task_blocker
                 WHERE blocker_type = 'REMOTE_REPAIR_BRAIN_FAILED'
                   AND subject_revision = ? AND status = 'OPEN'
                """, String.class, brain.turnId());
        var budget = runtime.jdbc().queryForMap("""
                SELECT attempt_count, attempt_limit
                  FROM branch_sync_effect_step WHERE id = ?
                """, step.id());
        assertThat(budget)
                .containsEntry("attempt_count", 1)
                .containsEntry("attempt_limit", 2);
        int attemptCount = runtime.jdbc().queryForObject("""
                SELECT attempt_count FROM branch_sync_effect_step WHERE id = ?
                """, Integer.class, step.id());
        int attemptLimit = runtime.jdbc().queryForObject("""
                SELECT attempt_limit FROM branch_sync_effect_step WHERE id = ?
                """, Integer.class, step.id());
        return new FailedBranchBrain(
                runtime, episode.id(), step.id(), brain, blockerId,
                attemptCount, attemptLimit);
    }

    private FailedBrain failedBrain(String fileName)
            throws Exception
    {
        Harness runtime = harness(fileName);
        deliverFailedObservation(runtime);
        String episodeId = runtime.jdbc().queryForObject(
                "SELECT id FROM ci_repair_episode", String.class);

        Turn stage = runtime.jdbc().queryForObject("""
                SELECT operation.stage_turn_id AS turn_id,
                       operation.operation_id, operation.semantic_attempt,
                       operation.expected_code_fingerprint,
                       operation.expected_head_sha,
                       operation.expected_base_sha,
                       ticket.id AS ticket_id, identity.branch_name
                  FROM ci_repair_operation operation
                  JOIN dispatch_ticket ticket
                    ON ticket.operation_id = operation.operation_id
                  JOIN task_code_identity identity
                    ON identity.task_id = ticket.task_id
                 WHERE operation.kind = 'FIX_STAGE_TURN'
                """, (rs, row) -> turn(rs));
        AgentTurnOwnerResultCodec.OwnerResult stageResult = stageResult(
                runtime, stage);
        markResultPending(runtime.jdbc(), stage,
                DispatchTicket.Outcome.SUCCEEDED,
                runtime.json().writeValueAsString(stageResult.payload()));
        DispatchTicket.DeliveryReceipt stageAccepted =
                runtime.turnRuntime().deliver(stageResult);
        assertThat(stageAccepted.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        completeTicket(runtime.dataSource(), stage.ticketId(), stageAccepted);

        Effect validation = runtime.jdbc().queryForObject("""
                SELECT operation.operation_id, operation.semantic_attempt,
                       operation.expected_code_fingerprint,
                       operation.expected_head_sha,
                       operation.expected_base_sha,
                       ticket.id AS ticket_id
                  FROM ci_repair_operation operation
                  JOIN dispatch_ticket ticket
                    ON ticket.operation_id = operation.operation_id
                 WHERE operation.kind = 'VALIDATE'
                """, (rs, row) -> new Effect(
                        rs.getString("operation_id"),
                        rs.getInt("semantic_attempt"),
                        rs.getString("expected_code_fingerprint"),
                        rs.getString("expected_head_sha"),
                        rs.getString("expected_base_sha"),
                        rs.getString("ticket_id")));
        DispatchTicket.DeliveryReceipt validationAccepted =
                deliverValidation(runtime, validation);
        assertThat(validationAccepted.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        completeTicket(
                runtime.dataSource(), validation.ticketId(), validationAccepted);

        SqliteRemoteRepairTurnStore.RepairContext context =
                runtime.turnStore().requireContext(
                        "task-1", "remote-stage-1");
        CiEpisode episode = runtime.remoteStore().requireCiEpisode(
                "task-1", episodeId);
        String launch = brainLaunch(runtime.json(), episodeId);
        BrainRequest brain = runtime.commands().execute("task-1", () ->
                runtime.turnStore().insertCiBrain(
                        context, episode, launch,
                        "API", 2, NOW.plusMillis(3)));
        CommandResult<TaskManager.State> requested =
                runtime.commands().execute("task-1", () ->
                runtime.tasks().requestBrainReviewInCommand(
                        new TaskManager.BrainReviewRequestCommand(
                                "request-test-remote-brain", "test", "task-1",
                                context.taskEpoch(), context.taskVersion(),
                                brain.rowId(), brain.fence())));
        assertThat(requested.disposition())
                .isEqualTo(CommandResult.Disposition.APPLIED);

        Turn brainTurn = turn(runtime, brain.operationId());
        AgentTurnOwnerResultCodec.OwnerResult failed = brainResult(
                runtime.json(), brainTurn, DispatchTicket.Outcome.FAILED,
                AgentTurnOperationHandler.Disposition.PROVIDER_FAILED,
                "", "provider session failed");
        markResultPending(runtime.jdbc(), brainTurn,
                DispatchTicket.Outcome.FAILED,
                runtime.json().writeValueAsString(failed.payload()));
        String episodeStatus = runtime.jdbc().queryForObject("""
                SELECT status FROM ci_repair_episode WHERE id = ?
                """, String.class, episodeId);
        int fixAttempts = runtime.jdbc().queryForObject("""
                SELECT fix_attempt_count FROM ci_repair_episode WHERE id = ?
                """, Integer.class, episodeId);
        int pushes = runtime.jdbc().queryForObject("""
                SELECT push_count FROM ci_repair_episode WHERE id = ?
                """, Integer.class, episodeId);
        DispatchTicket.DeliveryReceipt failedAccepted =
                runtime.turnRuntime().deliver(failed);
        assertThat(failedAccepted.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        completeTicket(
                runtime.dataSource(), brainTurn.ticketId(), failedAccepted);
        String blockerId = runtime.jdbc().queryForObject("""
                SELECT id FROM task_blocker
                 WHERE blocker_type = 'REMOTE_REPAIR_BRAIN_FAILED'
                   AND status = 'OPEN'
                """, String.class);
        return new FailedBrain(
                runtime, episodeId, brain, blockerId, failed,
                episodeStatus, fixAttempts, pushes);
    }

    /** Seeds only the immutable evidence consumed by the V325 retry guard. */
    private static void seedCompatibilityBaseRewrite(
            FailedBrain failed, String rewriteInputHead)
            throws Exception
    {
        try (Connection connection = failed.runtime().dataSource()
                .getConnection()) {
            execute(connection, "PRAGMA foreign_keys = OFF");
            for (String trigger : List.of(
                    "ci_repair_episode_identity_immutable",
                    "ci_repair_operation_identity_immutable",
                    "ci_repair_operation_base_identity_v303",
                    "remote_repair_brain_failure_receipt_immutable_v309",
                    "ci_base_repair_manifest_insert_v321",
                    "ci_base_repair_authorization_insert_v303",
                    "ci_base_repair_reauthorization_insert_v322",
                    "remote_repair_commit_adoption_operation_insert_v322",
                    "remote_repair_commit_adoption_result_insert_v322",
                    "remote_repair_commit_adoption_delivery_insert_v322",
                    "remote_worktree_subject_insert",
                    "ci_base_repair_rewrite_result_insert_v303")) {
                execute(connection,
                        "DROP TRIGGER IF EXISTS %s".formatted(trigger));
            }
            execute(connection, """
                    UPDATE ci_repair_episode
                       SET classification = 'BASE_DETERMINISTIC'
                     WHERE id = '%s'
                    """.formatted(failed.episodeId()));
            execute(connection, """
                    INSERT INTO ci_base_repair_manifest_v303(
                        id, ci_repair_episode_id, failed_ci_evaluation_id,
                        remote_pr_snapshot_id, subject_head_sha,
                        subject_base_sha, subject_manifest_json,
                        manifest_digest, created_at_ms)
                    SELECT 'compat-manifest', episode.id,
                           episode.failed_ci_evaluation_id,
                           evaluation.remote_pr_snapshot_id,
                           episode.subject_head_sha, episode.subject_base_sha,
                           '{"schema":"CI_BASE_REPAIR_SUBJECT_V1"}',
                           '%s', 900
                      FROM ci_repair_episode episode
                      JOIN remote_ci_evaluation evaluation
                        ON evaluation.id = episode.failed_ci_evaluation_id
                     WHERE episode.id = '%s'
                    """.formatted("b".repeat(64), failed.episodeId()));
            execute(connection, """
                    INSERT INTO ci_base_repair_authorization_v303(
                        id, ci_repair_episode_id, manifest_id,
                        semantic_attempt, authority_kind,
                        automation_policy_id, blocker_id, command_id, actor,
                        reason, failed_ci_evaluation_id, remote_pr_snapshot_id,
                        expected_worktree_head_sha, subject_head_sha,
                        subject_base_sha, manifest_digest, status,
                        claimed_at_ms, terminal_at_ms, terminal_evidence)
                    SELECT 'compat-original-authorization', episode.id,
                           'compat-manifest', 1, 'AUTO_APPROVE_POLICY',
                           'automation-policy-1', NULL, 'compat-authorize',
                           NULL, 'compatibility fixture',
                           episode.failed_ci_evaluation_id,
                           manifest.remote_pr_snapshot_id,
                           episode.subject_head_sha, episode.subject_head_sha,
                           episode.subject_base_sha, manifest.manifest_digest,
                           'CLOSED', 901, 902, 'legacy result was malformed'
                      FROM ci_repair_episode episode
                      JOIN ci_base_repair_manifest_v303 manifest
                        ON manifest.id = 'compat-manifest'
                     WHERE episode.id = '%s'
                    """.formatted(failed.episodeId()));
            execute(connection, """
                    UPDATE ci_repair_operation
                       SET base_repair_authorization_id =
                           'compat-original-authorization'
                     WHERE ci_repair_episode_id = '%s'
                       AND kind = 'BRAIN_REVIEW'
                    """.formatted(failed.episodeId()));
            execute(connection, """
                    UPDATE remote_repair_brain_failure_receipt_v309
                       SET base_repair_authorization_id =
                           'compat-original-authorization'
                     WHERE ci_repair_episode_id = '%s'
                    """.formatted(failed.episodeId()));
            execute(connection, """
                    UPDATE ci_repair_operation
                       SET base_repair_authorization_id =
                               'compat-original-authorization',
                           expected_code_fingerprint = 'adopted-fingerprint',
                           expected_head_sha = 'adopted-head'
                     WHERE ci_repair_episode_id = '%s' AND kind = 'VALIDATE'
                    """.formatted(failed.episodeId()));
            execute(connection, """
                    INSERT INTO ci_base_repair_reauthorization_v322(
                        id, source_authorization_id, normalization_due_id,
                        normalization_operation_row_id, ci_repair_episode_id,
                        source_operation_row_id, source_operation_id,
                        blocker_id, adoption_command_id, task_id, task_epoch,
                        remote_development_stage_id, stage_generation,
                        semantic_attempt, source_code_subject_revision,
                        source_code_subject_kind, source_code_subject_id,
                        expected_code_fingerprint, expected_head_sha,
                        expected_base_sha, status, claimed_at_ms)
                    SELECT 'compat-reauthorization',
                           'compat-original-authorization', 'compat-due',
                           'compat-normalization', episode.id, source.id,
                           source.operation_id, blocker.id, 'compat-adopt',
                           source.task_id, source.task_epoch,
                           source.remote_development_stage_id,
                           source.stage_generation, source.semantic_attempt,
                           code.revision, code.subject_kind, code.subject_id,
                           source.expected_code_fingerprint,
                           source.expected_head_sha, source.expected_base_sha,
                           'CLAIMED', 903
                      FROM ci_repair_episode episode
                      JOIN ci_repair_operation source
                        ON source.ci_repair_episode_id = episode.id
                       AND source.kind = 'FIX_STAGE_TURN'
                      JOIN task_blocker blocker
                        ON blocker.id = '%s'
                      JOIN task_code_subject_revision_v320 code
                        ON code.revision = (
                            SELECT MAX(latest.revision)
                              FROM task_code_subject_revision_v320 latest
                             WHERE latest.task_id = source.task_id
                               AND latest.task_epoch = source.task_epoch)
                     WHERE episode.id = '%s'
                    """.formatted(failed.blockerId(), failed.episodeId()));
            execute(connection, """
                    INSERT INTO remote_repair_commit_adoption_operation_v322(
                        id, normalization_due_id,
                        normalization_operation_row_id, ci_repair_episode_id,
                        source_operation_row_id, source_operation_id,
                        source_stage_turn_id,
                        source_base_repair_authorization_id,
                        compatibility_reauthorization_id, blocker_id,
                        command_id, task_id, task_epoch,
                        remote_development_stage_id, stage_generation,
                        operation_id, dispatch_ticket_id, adoption_attempt,
                        worktree_path, expected_branch_name,
                        source_code_subject_revision,
                        source_code_subject_kind, source_code_subject_id,
                        expected_code_fingerprint, expected_head_sha,
                        expected_base_sha, candidate_capture_kind,
                        candidate_code_fingerprint, candidate_head_sha,
                        candidate_source_tree_sha, candidate_result_tree_sha,
                        source_execution_started_at_ms,
                        source_execution_finished_at_ms, status, result_id,
                        requested_at_ms, completed_at_ms, error_message)
                    SELECT 'compat-adoption', 'compat-due',
                           'compat-normalization', source.ci_repair_episode_id,
                           source.id, source.operation_id,
                           source.stage_turn_id,
                           'compat-original-authorization',
                           'compat-reauthorization', '%s', 'compat-adopt',
                           source.task_id, source.task_epoch,
                           source.remote_development_stage_id,
                           source.stage_generation, 'compat-adoption-operation',
                           ticket.id, 1, identity.worktree_path,
                           identity.branch_name, code.revision,
                           code.subject_kind, code.subject_id,
                           source.expected_code_fingerprint,
                           source.expected_head_sha, source.expected_base_sha,
                           'LEGACY_REFLOG_WINDOW_V1', NULL, NULL, NULL, NULL,
                           904, 905, 'SUCCEEDED', 'compat-adoption-result',
                           904, 906, NULL
                      FROM ci_repair_operation source
                      JOIN dispatch_ticket ticket
                        ON ticket.operation_id = source.operation_id
                      JOIN task_code_identity identity
                        ON identity.task_id = source.task_id
                      JOIN task_code_subject_revision_v320 code
                        ON code.revision = (
                            SELECT MAX(latest.revision)
                              FROM task_code_subject_revision_v320 latest
                             WHERE latest.task_id = source.task_id
                               AND latest.task_epoch = source.task_epoch)
                     WHERE source.ci_repair_episode_id = '%s'
                       AND source.kind = 'FIX_STAGE_TURN'
                    """.formatted(failed.blockerId(), failed.episodeId()));
            execute(connection, """
                    INSERT INTO remote_repair_commit_adoption_result_v322(
                        id, adoption_operation_row_id, operation_id, task_id,
                        task_epoch, remote_development_stage_id,
                        stage_generation, worktree_path, expected_branch_name,
                        expected_code_fingerprint, expected_head_sha,
                        expected_base_sha, candidate_capture_kind,
                        candidate_code_fingerprint, candidate_head_sha,
                        candidate_parent_sha, candidate_source_tree_sha,
                        candidate_result_tree_sha, candidate_branch_name,
                        candidate_base_merge_base_sha,
                        candidate_source_head_merge_base_sha, result_clean,
                        git_operation_state_clear, writer_fencing_token,
                        evidence, recorded_at_ms)
                    SELECT 'compat-adoption-result', adoption.id,
                           adoption.operation_id, adoption.task_id,
                           adoption.task_epoch,
                           adoption.remote_development_stage_id,
                           adoption.stage_generation, adoption.worktree_path,
                           adoption.expected_branch_name,
                           adoption.expected_code_fingerprint,
                           adoption.expected_head_sha,
                           adoption.expected_base_sha,
                           adoption.candidate_capture_kind,
                           'adopted-fingerprint', 'adopted-head',
                           adoption.expected_head_sha, 'source-tree',
                           'result-tree', adoption.expected_branch_name,
                           adoption.expected_base_sha,
                           adoption.expected_head_sha, 1, 1, 1, '{}', 906
                      FROM remote_repair_commit_adoption_operation_v322 adoption
                     WHERE adoption.id = 'compat-adoption'
                    """);
            execute(connection, """
                    INSERT INTO remote_repair_commit_adoption_delivery_v322(
                        adoption_operation_row_id, operation_id, result_id,
                        raw_outcome, raw_result_digest, acceptance, evidence,
                        recorded_at_ms)
                    VALUES ('compat-adoption', 'compat-adoption-operation',
                        'compat-adoption-result', 'SUCCEEDED', '%s',
                        'ACCEPTED', 'exact accepted adoption', 907)
                    """.formatted("c".repeat(64)));
            execute(connection, """
                    INSERT INTO remote_worktree_subject(
                        id, task_id, task_epoch, remote_development_stage_id,
                        stage_generation, revision, source_kind,
                        source_operation_id, code_fingerprint, head_sha,
                        base_sha, recorded_at_ms)
                    SELECT 'compat-adopted-worktree', adoption.task_id,
                           adoption.task_epoch,
                           adoption.remote_development_stage_id,
                           adoption.stage_generation,
                           COALESCE((SELECT MAX(existing.revision)
                               FROM remote_worktree_subject existing
                               WHERE existing.task_id = adoption.task_id
                                 AND existing.task_epoch = adoption.task_epoch),
                               0) + 1,
                           'CI_STAGE_TURN', adoption.operation_id,
                           adopted.candidate_code_fingerprint,
                           adopted.candidate_head_sha,
                           adoption.expected_base_sha, 908
                      FROM remote_repair_commit_adoption_operation_v322 adoption
                      JOIN remote_repair_commit_adoption_result_v322 adopted
                        ON adopted.id = adoption.result_id
                     WHERE adoption.id = 'compat-adoption'
                    """);
            execute(connection, """
                    INSERT INTO ci_base_repair_rewrite_result_v303(
                        authorization_id, ci_repair_operation_id,
                        input_head_sha, output_head_sha, repair_commit_sha,
                        original_commits_json, proof_json, proof_digest,
                        validation_outcome, recorded_at_ms)
                    SELECT 'compat-original-authorization', validation.id,
                           '%s', validation.result_head_sha, 'repair-commit',
                           '[]', '{}', '%s', 'PASSED', 909
                      FROM ci_repair_operation validation
                     WHERE validation.ci_repair_episode_id = '%s'
                       AND validation.kind = 'VALIDATE'
                    """.formatted(
                    rewriteInputHead, "d".repeat(64), failed.episodeId()));
            execute(connection, """
                    INSERT INTO ci_base_repair_subject_v303(
                        id, task_id, task_epoch,
                        remote_development_stage_id, stage_generation,
                        authorization_id, ci_repair_operation_id,
                        code_fingerprint, head_sha, base_sha, recorded_at_ms)
                    SELECT 'compat-base-subject', validation.task_id,
                           validation.task_epoch,
                           validation.remote_development_stage_id,
                           validation.stage_generation,
                           'compat-original-authorization', validation.id,
                           validation.result_code_fingerprint,
                           validation.result_head_sha,
                           validation.expected_base_sha, 910
                      FROM ci_repair_operation validation
                     WHERE validation.ci_repair_episode_id = '%s'
                       AND validation.kind = 'VALIDATE'
                    """.formatted(failed.episodeId()));
        }
    }

    private Harness harness(String fileName)
            throws Exception
    {
        Path file = tempDir.resolve(fileName);
        MigratedSqliteDatabase.copyFixture(
                "remote-repair-brain-recovery-v309", file,
                TestRemoteRepairBrainRecovery::seedRuntime);
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + file
                + "?foreign_keys=ON&busy_timeout=30000");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        TaskManager.Store taskStore = taskStore(jdbc, transactions);
        TaskManager tasks = new TaskManager(commands, taskStore);
        SqliteRemoteRuntimeStore remoteStore =
                new SqliteRemoteRuntimeStore(jdbc);
        SqliteRemoteRepairTurnStore turnStore =
                new SqliteRemoteRepairTurnStore(jdbc);
        ObjectMapper json = new ObjectMapper();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        RemoteRepairTurnRuntime turnRuntime = new RemoteRepairTurnRuntime(
                commands, tasks, remoteStore, turnStore,
                new SqliteAgentResultSubmissionStore(jdbc), json, clock,
                8080);
        turnRuntime.setNormalizationStore(
                new SqliteRemoteRepairNormalizationStore(jdbc));
        RemoteCiRepairRuntimeCoordinator repair =
                new RemoteCiRepairRuntimeCoordinator(
                        commands, remoteStore,
                        ignored -> RemoteCiRepairRuntimeCoordinator
                                .Classification.TASK_BRANCH_REPAIRABLE,
                        new CiBudgets(0, 3, 2, 3),
                        new RemoteCiRepairRuntimeCoordinator.DeterministicRepairPort()
                        {
                            @Override
                            public void startInCommand(
                                    RemoteObservationConsumer.Candidate candidate,
                                    CiEpisode episode)
                            {
                                turnRuntime.startInCommand(candidate, episode);
                            }

                            @Override
                            public void acceptValidationInCommand(
                                    CiEpisode episode,
                                    RemoteEffectOperationHandler.Result result)
                            {
                                // This fixture inserts a resumable frozen Brain
                                // launch explicitly after validation.
                            }

                            @Override
                            public boolean
                                    startPendingCiNoChangeContinuationInCommand(
                                            CiEpisode episode)
                            {
                                return turnRuntime
                                        .startPendingCiNoChangeContinuationInCommand(
                                                episode);
                            }

                            @Override
                            public boolean startPendingCiNextFixInCommand(
                                    CiEpisode episode)
                            {
                                return turnRuntime.startPendingCiNextFixInCommand(
                                        episode);
                            }
                        }, json, clock);
        RemoteObservationConsumer consumer = (candidate, acceptance) -> {
            acceptance.accept();
            repair.acceptObservationInCommand(candidate);
            return RemoteObservationConsumer.Consumption.ACCEPTED;
        };
        RemoteObservationRuntimeCoordinator observations =
                new RemoteObservationRuntimeCoordinator(
                        commands, remoteStore, consumer, json, clock);
        return new Harness(
                dataSource, jdbc, commands, taskStore, tasks, remoteStore,
                turnStore, turnRuntime, repair, observations, json);
    }

    private static void seedRuntime(Path database)
            throws Exception
    {
        String url = "jdbc:sqlite:" + database;
        migrate(url);
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
        }
        migrate(url);
        try (Connection connection = connect(url)) {
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
            connection.createStatement().executeUpdate("""
                    INSERT INTO task_automation_policy(
                        id, task_id, revision, source, auto_approve, auto_merge,
                        keep_draft, minimum_write_approvals,
                        max_merge_queue_reenqueues, require_low_risk,
                        require_small_effort, stewardship_exception,
                        created_by, created_at_ms)
                    VALUES ('automation-policy-1', 'task-1', 1, 'TEST', 1, 0,
                        0, 0, 0, 0, 0, 0, 'test', 54)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO remote_ci_required_check(
                        ci_policy_revision_id, check_name)
                    VALUES ('ci-policy-1', 'build')
                    """);
        }
        migrate(url);
    }

    private static void deliverFailedObservation(Harness runtime)
            throws Exception
    {
        deliverFailedObservation(runtime, "failed-ci", 800L);
    }

    private static void deliverFailedObservation(
            Harness runtime,
            String observationKey,
            long observedAtMs)
            throws Exception
    {
        ObservationRequest request = runtime.observations().requestObservation(
                "task-1", "remote-stage-1");
        String head = runtime.jdbc().queryForObject("""
                SELECT expected_head_sha FROM remote_observation_operation
                 WHERE operation_id = ?
                """, String.class, request.operationId());
        String base = runtime.jdbc().queryForObject("""
                SELECT expected_base_sha FROM remote_observation_operation
                 WHERE operation_id = ?
                """, String.class, request.operationId());
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, "remote-stage-1", 1L, request.operationId(),
                request.semanticAttempt(), null, head, base);
        RemoteObservationOperationHandler.Observation observation =
                new RemoteObservationOperationHandler.Observation(
                        1, observationKey, head, base,
                        RemoteObservationOperationHandler.PrState.OPEN,
                        RemoteObservationOperationHandler.Mergeability.MERGEABLE,
                        RemoteObservationOperationHandler.MergeQueueState.NONE,
                        0, 0, 0, 0, 0, 0,
                        List.of(new RemoteCiPolicy.Check(
                                "CHECK_RUN", "build-1", "build",
                                RemoteCiPolicy.CheckState.FAILED,
                                "completed", "failure", null,
                                observedAtMs, "{}")),
                        "{\"failed\":true}", observedAtMs);
        String payload = runtime.json().writeValueAsString(observation);
        markResultPending(runtime.jdbc(), request.operationId(), fence,
                DispatchTicket.Outcome.SUCCEEDED, payload);
        runtime.observations().deliver(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.STAGE,
                        "remote-stage-1",
                        RemoteObservationOperationHandler.CALLBACK_ROUTE),
                fence, new DispatchTicket.DispatchResult(
                        fence, DispatchTicket.Outcome.SUCCEEDED,
                        payload, payload, null));
    }

    private static DispatchTicket.DeliveryReceipt deliverValidation(
            Harness runtime, Effect effect)
            throws Exception
    {
        DispatchTicket.OperationFence fence = effect.fence();
        RemoteEffectOperationHandler.Result result =
                new RemoteEffectOperationHandler.Result(
                        1, effect.operationId(),
                        RemoteEffectOperationHandler.Disposition.SUCCEEDED,
                        effect.fingerprint(), effect.head(), effect.base(),
                        "validation passed", null);
        String payload = runtime.json().writeValueAsString(result);
        markResultPending(runtime.jdbc(), effect.operationId(), fence,
                DispatchTicket.Outcome.SUCCEEDED, payload);
        return runtime.repair().deliverEffect(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.STAGE,
                        "remote-stage-1", "REMOTE_CI_VALIDATION_RESULT"),
                fence, new DispatchTicket.DispatchResult(
                        fence, DispatchTicket.Outcome.SUCCEEDED,
                        payload, payload, null));
    }

    /** A repair Turn reports through record_repair_summary now, so the fixture
     *  writes the row the tool would have written and leaves the final message
     *  as the prose a repairing agent actually writes. */
    private static void recordRepairSummary(Harness runtime, Turn turn)
    {
        runtime.jdbc().update("""
                INSERT INTO stage_turn_repair_submission(
                    stage_turn_id, operation_id, task_id, summary,
                    replies_json, submitted_at_ms)
                VALUES (?, ?, 'task-1', 'fixed CI', '[]', 1)
                ON CONFLICT(stage_turn_id) DO NOTHING
                """, turn.turnId(), turn.operationId());
    }

    private static AgentTurnOwnerResultCodec.OwnerResult stageResult(
            Harness runtime, Turn turn)
            throws Exception
    {
        return stageResult(runtime, turn, "tree-1", "tree-2");
    }

    private static AgentTurnOwnerResultCodec.OwnerResult stageResult(
            Harness runtime,
            Turn turn,
            String sourceTreeSha,
            String resultTreeSha)
            throws Exception
    {
        recordRepairSummary(runtime, turn);
        ObjectMapper json = runtime.json();
        DispatchTicket.OperationFence fence = turn.fence();
        boolean noChange = sourceTreeSha.equals(resultTreeSha);
        AgentTurnOperationHandler.OutputCodeSubject output =
                new AgentTurnOperationHandler.OutputCodeSubject(
                        noChange ? turn.fingerprint() : "fingerprint-2",
                        noChange ? turn.head() : "head-2",
                        turn.base(), true, turn.base(),
                        sourceTreeSha, resultTreeSha, null, null,
                        turn.head(), turn.branchName());
        AgentTurnOperationHandler.RawResult payload =
                new AgentTurnOperationHandler.RawResult(
                        1, turn.turnId(), DispatchTicket.OwnerKind.STAGE_TURN,
                        "REMOTE_CI_REPAIR",
                        AgentTurnProviderSession.Transport.API, "openai",
                        "stage-session",
                        "Fixed CI. The flaky assertion now waits for the queue.",
                        1, 1, 1, null,
                        AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED,
                        null, null, output);
        AgentTurnOperationHandler.Evidence evidence =
                new AgentTurnOperationHandler.Evidence(
                        1,
                        AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED,
                        null,
                        new AgentTurnProviderSession.WriterFence(
                                "/tmp/task-1", "task-1", turn.operationId(),
                                1, 1),
                        null, output);
        return new AgentTurnOwnerResultCodec(json).decode(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.STAGE_TURN,
                        turn.turnId(), RemoteRepairTurnRuntime.CI_STAGE_CALLBACK),
                fence, new DispatchTicket.DispatchResult(
                        fence, DispatchTicket.Outcome.SUCCEEDED,
                        json.writeValueAsString(payload),
                        json.writeValueAsString(evidence), null));
    }

    private static AgentTurnOwnerResultCodec.OwnerResult normalizationResult(
            ObjectMapper json, Turn turn)
            throws Exception
    {
        DispatchTicket.OperationFence fence = turn.fence();
        AgentTurnOperationHandler.RawResult payload =
                new AgentTurnOperationHandler.RawResult(
                        1, turn.turnId(), DispatchTicket.OwnerKind.TASK_TURN,
                        "REMOTE_REPAIR_RESULT_NORMALIZATION",
                        AgentTurnProviderSession.Transport.API, "openai",
                        "normalization-session",
                        "{\"schemaVersion\":1,\"summary\":\"fixed CI\"}",
                        1, 1, 1, null,
                        AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED,
                        null);
        String encoded = json.writeValueAsString(payload);
        return new AgentTurnOwnerResultCodec(json).decode(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.TASK_TURN,
                        turn.turnId(),
                        RemoteRepairTurnRuntime.NORMALIZATION_CALLBACK),
                fence, new DispatchTicket.DispatchResult(
                        fence, DispatchTicket.Outcome.SUCCEEDED,
                        encoded, encoded, null));
    }

    private static AgentTurnOwnerResultCodec.OwnerResult
            legacyStageResultWithoutTrees(Harness runtime, Turn turn)
            throws Exception
    {
        recordRepairSummary(runtime, turn);
        ObjectMapper json = runtime.json();
        DispatchTicket.OperationFence fence = turn.fence();
        AgentTurnOperationHandler.OutputCodeSubject output =
                new AgentTurnOperationHandler.OutputCodeSubject(
                        "fingerprint-2", "head-2", "base-1", true, "base-1");
        AgentTurnOperationHandler.RawResult payload =
                new AgentTurnOperationHandler.RawResult(
                        1, turn.turnId(), DispatchTicket.OwnerKind.STAGE_TURN,
                        "REMOTE_CI_REPAIR",
                        AgentTurnProviderSession.Transport.API, "openai",
                        "legacy-stage-session",
                        "Fixed CI, but without tree proof.",
                        1, 1, 1, null,
                        AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED,
                        null, null, output);
        AgentTurnOperationHandler.Evidence evidence =
                new AgentTurnOperationHandler.Evidence(
                        1,
                        AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED,
                        null,
                        new AgentTurnProviderSession.WriterFence(
                                "/tmp/task-1", "task-1", turn.operationId(),
                                1, 1),
                        null, output);
        return new AgentTurnOwnerResultCodec(json).decode(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.STAGE_TURN,
                        turn.turnId(), RemoteRepairTurnRuntime.CI_STAGE_CALLBACK),
                fence, new DispatchTicket.DispatchResult(
                        fence, DispatchTicket.Outcome.SUCCEEDED,
                        json.writeValueAsString(payload),
                        json.writeValueAsString(evidence), null));
    }

    private static AgentTurnOwnerResultCodec.OwnerResult stageFailureResult(
            ObjectMapper json,
            Turn turn,
            String purpose,
            String callback,
            AgentTurnOperationHandler.Disposition disposition,
            String finalText,
            String error)
            throws Exception
    {
        DispatchTicket.OperationFence fence = turn.fence();
        AgentTurnOperationHandler.OutputCodeSubject output =
                "REMOTE_CI_REPAIR".equals(purpose)
                    && disposition == AgentTurnOperationHandler.Disposition
                            .OWNER_OUTPUT_MALFORMED
                ? new AgentTurnOperationHandler.OutputCodeSubject(
                        "fingerprint-2", "head-2", turn.base(), true,
                        turn.base(), "source-tree", "result-tree", null,
                        null, turn.head(), turn.head(), turn.branchName(), null)
                : null;
        AgentTurnOperationHandler.RawResult payload =
                new AgentTurnOperationHandler.RawResult(
                        1, turn.turnId(), DispatchTicket.OwnerKind.STAGE_TURN,
                        purpose, AgentTurnProviderSession.Transport.API, "openai",
                        "stage-session", finalText, 1, 1, 1, null,
                        disposition, error, null, output);
        String evidence = output == null ? "{}"
                : json.writeValueAsString(new AgentTurnOperationHandler.Evidence(
                        1, disposition, null,
                        new AgentTurnProviderSession.WriterFence(
                                "/tmp/task-1", "task-1", turn.operationId(),
                                1, 1),
                        error, output));
        return new AgentTurnOwnerResultCodec(json).decode(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.STAGE_TURN,
                        turn.turnId(), callback),
                fence, new DispatchTicket.DispatchResult(
                        fence, DispatchTicket.Outcome.FAILED,
                        json.writeValueAsString(payload), evidence, error));
    }

    private static AgentTurnOwnerResultCodec.OwnerResult brainResult(
            ObjectMapper json,
            Turn turn,
            DispatchTicket.Outcome outcome,
            AgentTurnOperationHandler.Disposition disposition,
            String finalText,
            String error)
            throws Exception
    {
        DispatchTicket.OperationFence fence = turn.fence();
        AgentTurnOperationHandler.RawResult payload =
                new AgentTurnOperationHandler.RawResult(
                        1, turn.turnId(), DispatchTicket.OwnerKind.TASK_TURN,
                        "REMOTE_CI_BRAIN_REVIEW",
                        AgentTurnProviderSession.Transport.API, "openai",
                        "brain-session", finalText, 1, 1, 1, null,
                        disposition, error);
        return new AgentTurnOwnerResultCodec(json).decode(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.TASK_TURN,
                        turn.turnId(), RemoteRepairTurnRuntime.CI_BRAIN_CALLBACK),
                fence, new DispatchTicket.DispatchResult(
                        fence, outcome, json.writeValueAsString(payload),
                        "{}", error));
    }

    /** A Brain review reports through record_development_verdict now, so the
     *  fixture writes the row the tool would have written. */
    private static void recordBrainVerdict(
            Harness runtime, Turn turn, String verdict, String summary)
    {
        runtime.jdbc().update("""
                INSERT INTO task_turn_brain_verdict(
                    task_turn_id, operation_id, task_id, verdict, summary,
                    findings_json, submitted_at_ms)
                VALUES (?, ?, 'task-1', ?, ?, '[]', 1)
                """, turn.turnId(), turn.operationId(), verdict, summary);
    }

    private static AgentTurnOwnerResultCodec.OwnerResult branchBrainResult(
            ObjectMapper json,
            Turn turn,
            DispatchTicket.Outcome outcome,
            AgentTurnOperationHandler.Disposition disposition,
            String finalText,
            String error)
            throws Exception
    {
        DispatchTicket.OperationFence fence = turn.fence();
        AgentTurnOperationHandler.RawResult payload =
                new AgentTurnOperationHandler.RawResult(
                        1, turn.turnId(), DispatchTicket.OwnerKind.TASK_TURN,
                        "BRANCH_SYNC_BRAIN_REVIEW",
                        AgentTurnProviderSession.Transport.API, "openai",
                        "branch-brain-session", finalText, 1, 1, 1, null,
                        disposition, error);
        return new AgentTurnOwnerResultCodec(json).decode(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.TASK_TURN,
                        turn.turnId(),
                        RemoteRepairTurnRuntime.BRANCH_BRAIN_CALLBACK),
                fence, new DispatchTicket.DispatchResult(
                        fence, outcome, json.writeValueAsString(payload),
                        "{}", error));
    }

    private static String brainLaunch(ObjectMapper json, String episodeId)
            throws Exception
    {
        String turnId = PlanRuntimeCoordinator.id(
                "ci-repair-task-turn", episodeId + ":brain:1");
        String operationId = PlanRuntimeCoordinator.id(
                "ci-repair-operation", episodeId + ":brain:1");
        ObjectNode launch = json.createObjectNode();
        launch.put("schemaVersion", 1);
        launch.put("transport", "API");
        launch.put("provider", "openai");
        launch.put("model", "review-model");
        launch.put("workingDirectory", "/tmp/task-1");
        launch.put("systemPrompt", "Read-only Remote repair Brain review.");
        launch.put("prompt", "resume-only delta");
        launch.put("resumeSessionId", "failed-provider-session");
        launch.put("fallbackPrompt", "Review the exact deterministic CI repair");
        launch.put("priorCumulativeInputTokens", 120);
        launch.put("priorCumulativeOutputTokens", 30);
        launch.putObject("toolEndpoint")
                .put("serverName", "bytequay")
                .put("url", "http://127.0.0.1:8080/api/v2/task-turns/"
                        + turnId + "/operations/" + operationId + "/mcp")
                .put("ownerKind", "TASK_TURN")
                .put("ownerId", turnId)
                .put("operationId", operationId)
                .put("profile", "TASK_BRAIN_READ_ONLY")
                .put("approvalPromptTool", "mcp__bytequay__approval_prompt");
        return json.writeValueAsString(launch);
    }

    private static String branchBrainLaunch(
            ObjectMapper json, BranchStep step)
            throws Exception
    {
        int attempt = step.attemptCount() + 1;
        String suffix = step.id() + ":" + attempt;
        String turnId = PlanRuntimeCoordinator.id(
                "branch-sync-task-turn", suffix);
        String operationId = PlanRuntimeCoordinator.id(
                "branch-sync-operation", suffix);
        ObjectNode launch = json.createObjectNode();
        launch.put("schemaVersion", 1);
        launch.put("transport", "API");
        launch.put("provider", "openai");
        launch.put("model", "review-model");
        launch.put("workingDirectory", "/tmp/task-1");
        launch.put("systemPrompt", "Read-only branch repair Brain review.");
        launch.put("prompt", "resume-only branch delta");
        launch.put("resumeSessionId", "failed-branch-provider-session");
        launch.put("fallbackPrompt",
                "Review the exact conflict repair rebased onto base-2.");
        launch.put("priorCumulativeInputTokens", 120);
        launch.put("priorCumulativeOutputTokens", 30);
        launch.putObject("toolEndpoint")
                .put("serverName", "bytequay")
                .put("url", "http://127.0.0.1:8080/api/v2/task-turns/"
                        + turnId + "/operations/" + operationId + "/mcp")
                .put("ownerKind", "TASK_TURN")
                .put("ownerId", turnId)
                .put("operationId", operationId)
                .put("profile", "TASK_BRAIN_READ_ONLY")
                .put("approvalPromptTool", "mcp__bytequay__approval_prompt");
        return json.writeValueAsString(launch);
    }

    private static String branchStageLaunch(
            ObjectMapper json, BranchStep step)
            throws Exception
    {
        int attempt = step.attemptCount() + 1;
        String suffix = step.id() + ":" + attempt;
        String turnId = PlanRuntimeCoordinator.id(
                "branch-sync-stage-turn", suffix);
        String operationId = PlanRuntimeCoordinator.id(
                "branch-sync-operation", suffix);
        ObjectNode launch = json.createObjectNode();
        launch.put("schemaVersion", 1);
        launch.put("transport", "API");
        launch.put("provider", "openai");
        launch.put("model", "development-model");
        launch.put("workingDirectory", "/tmp/task-1");
        launch.put("systemPrompt", "Resolve the exact branch conflict.");
        launch.put("prompt", "Resolve conflicts and return strict JSON.");
        launch.putObject("toolEndpoint")
                .put("serverName", "bytequay")
                .put("url", "http://127.0.0.1:8080/api/v2/stage-turns/"
                        + turnId + "/operations/" + operationId + "/mcp")
                .put("ownerKind", "STAGE_TURN")
                .put("ownerId", turnId)
                .put("operationId", operationId)
                .put("profile", "STAGE_DEVELOPMENT")
                .put("approvalPromptTool", "mcp__bytequay__approval_prompt");
        return json.writeValueAsString(launch);
    }

    private static Turn turn(Harness runtime, String operationId)
    {
        return runtime.jdbc().queryForObject("""
                SELECT turn.id AS turn_id, turn.operation_id,
                       turn.attempt AS semantic_attempt,
                       turn.expected_code_fingerprint,
                       turn.expected_head_sha, turn.expected_base_sha,
                       ticket.id AS ticket_id, identity.branch_name
                  FROM task_turn turn
                  JOIN dispatch_ticket ticket
                    ON ticket.operation_id = turn.operation_id
                  JOIN task_code_identity identity
                    ON identity.task_id = ticket.task_id
                 WHERE turn.operation_id = ?
                """, (rs, row) -> turn(rs), operationId);
    }

    private static Turn latestCiStageTurn(Harness runtime)
    {
        return runtime.jdbc().queryForObject("""
                SELECT turn.id AS turn_id, turn.operation_id,
                       turn.attempt AS semantic_attempt,
                       turn.expected_code_fingerprint,
                       turn.expected_head_sha, turn.expected_base_sha,
                       ticket.id AS ticket_id, identity.branch_name
                  FROM stage_turn turn
                  JOIN dispatch_ticket ticket
                    ON ticket.operation_id = turn.operation_id
                  JOIN task_code_identity identity
                    ON identity.task_id = ticket.task_id
                 WHERE turn.purpose = 'REMOTE_CI_REPAIR'
                 ORDER BY turn.requested_at_ms DESC, turn.attempt DESC
                 LIMIT 1
                """, (rs, row) -> turn(rs));
    }

    private static Turn stageTurn(Harness runtime, String operationId)
    {
        return runtime.jdbc().queryForObject("""
                SELECT turn.id AS turn_id, turn.operation_id,
                       turn.attempt AS semantic_attempt,
                       turn.expected_code_fingerprint,
                       turn.expected_head_sha, turn.expected_base_sha,
                       ticket.id AS ticket_id, identity.branch_name
                  FROM stage_turn turn
                  JOIN dispatch_ticket ticket
                    ON ticket.operation_id = turn.operation_id
                  JOIN task_code_identity identity
                    ON identity.task_id = ticket.task_id
                 WHERE turn.operation_id = ?
                """, (rs, row) -> turn(rs), operationId);
    }

    private static Turn turn(ResultSet rs)
            throws SQLException
    {
        return new Turn(
                rs.getString("turn_id"), rs.getString("operation_id"),
                rs.getInt("semantic_attempt"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("ticket_id"),
                rs.getString("branch_name"));
    }

    private static void markResultPending(
            JdbcTemplate jdbc,
            Turn turn,
            DispatchTicket.Outcome outcome,
            String payload)
    {
        markResultPending(
                jdbc, turn.operationId(), turn.fence(), outcome, payload);
    }

    private static void markResultPending(
            JdbcTemplate jdbc,
            String operationId,
            DispatchTicket.OperationFence fence,
            DispatchTicket.Outcome outcome,
            String payload)
    {
        String error = outcome == DispatchTicket.Outcome.SUCCEEDED
                ? null : "provider session failed";
        assertThat(jdbc.update("""
                UPDATE dispatch_ticket
                   SET version = version + 1, status = 'RESULT_PENDING',
                       infrastructure_attempts = MAX(infrastructure_attempts, 1),
                       pending_result_outcome = ?,
                       pending_result_payload = ?, pending_result_evidence = ?,
                       pending_result_task_epoch = ?,
                       pending_result_stage_id = ?,
                       pending_result_stage_generation = ?,
                       pending_result_operation_id = ?,
                       pending_result_attempt = ?,
                       pending_result_expected_code_fingerprint = ?,
                       pending_result_expected_head_sha = ?,
                       pending_result_expected_base_sha = ?,
                       pending_result_error = ?
                 WHERE operation_id = ? AND status = 'REQUESTED'
                """, outcome.name(), payload, payload, fence.taskEpoch(),
                fence.stageId(), fence.stageGeneration(), fence.operationId(),
                fence.attempt(), fence.expectedCodeFingerprint(),
                fence.expectedHeadSha(), fence.expectedBaseSha(),
                error,
                operationId)).isOne();
        String rawResult;
        try {
            rawResult = new ObjectMapper().writeValueAsString(
                    new DispatchTicket.DispatchResult(
                            fence, outcome, payload, payload, error));
        }
        catch (Exception e) {
            throw new IllegalStateException("Could not encode execution evidence", e);
        }
        assertThat(jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, status,
                    started_at_ms, heartbeat_at_ms, finished_at_ms, raw_result)
                SELECT 'execution-' || id, id, infrastructure_attempts, ?,
                       1001, 1002, 1002, ?
                  FROM dispatch_ticket
                 WHERE operation_id = ? AND status = 'RESULT_PENDING'
                """, switch (outcome) {
                    case SUCCEEDED -> "SUCCEEDED";
                    case FAILED -> "FAILED";
                    case CANCELED -> "CANCELED";
                    case INDETERMINATE -> "UNKNOWN";
                }, rawResult, operationId)).isOne();
    }

    private static void completeTicket(
            SQLiteDataSource dataSource,
            String ticketId,
            DispatchTicket.DeliveryReceipt receipt)
    {
        SqliteDispatchTicketStore tickets =
                new SqliteDispatchTicketStore(dataSource);
        DispatchTicket ticket = tickets.findById(ticketId).orElseThrow();
        assertThat(tickets.compareAndSet(
                ticketId, ticket.version(),
                ticket.completeDelivery(receipt, NOW.plusMillis(10))))
                .isTrue();
    }

    private static TaskManager.Store taskStore(
            JdbcTemplate jdbc, DataSourceTransactionManager transactions)
    {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean(
                    DataSourceTransactionManager.class, () -> transactions);
            context.scan("com.bytequay.app.developmentflow.task.persistence");
            context.refresh();
            return context.getBean(TaskManager.Store.class);
        }
    }

    private static Task task()
    {
        return new Task(
                "task-1", "trunk-1", 1, TaskStatus.IDLE,
                "task-branch-1", "/tmp/task-1", "main", "/tmp",
                null, null, 41, "OPEN", "FAILING", null, null, null,
                0, 0, 0, null, Instant.ofEpochMilli(1), null, null,
                null, null, null);
    }

    private record Harness(
            SQLiteDataSource dataSource,
            JdbcTemplate jdbc,
            TaskCommandExecutor commands,
            TaskManager.Store taskStore,
            TaskManager tasks,
            SqliteRemoteRuntimeStore remoteStore,
            SqliteRemoteRepairTurnStore turnStore,
            RemoteRepairTurnRuntime turnRuntime,
            RemoteCiRepairRuntimeCoordinator repair,
            RemoteObservationRuntimeCoordinator observations,
            ObjectMapper json) {}

    private record FailedBrain(
            Harness runtime,
            String episodeId,
            BrainRequest brain,
            String blockerId,
            AgentTurnOwnerResultCodec.OwnerResult raw,
            String episodeStatus,
            int fixAttempts,
            int pushes) {}

    private record FailedBranchBrain(
            Harness runtime,
            String episodeId,
            String stepId,
            BrainRequest brain,
            String blockerId,
            int attemptCount,
            int attemptLimit) {}

    private record Turn(
            String turnId,
            String operationId,
            int attempt,
            String fingerprint,
            String head,
            String base,
            String ticketId,
            String branchName)
    {
        private DispatchTicket.OperationFence fence()
        {
            return new DispatchTicket.OperationFence(
                    1L, "remote-stage-1", 1L, operationId, attempt,
                    fingerprint, head, base);
        }
    }

    private record Effect(
            String operationId,
            int attempt,
            String fingerprint,
            String head,
            String base,
            String ticketId)
    {
        private DispatchTicket.OperationFence fence()
        {
            return new DispatchTicket.OperationFence(
                    1L, "remote-stage-1", 1L, operationId, attempt,
                    fingerprint, head, base);
        }
    }
}
