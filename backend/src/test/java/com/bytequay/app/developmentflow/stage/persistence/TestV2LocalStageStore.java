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
package com.bytequay.app.developmentflow.stage.persistence;

import com.bytequay.app.beans.stage.TaskBrainViewData;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.compatibility.V2BranchGuardProjection;
import com.bytequay.app.developmentflow.compatibility.V2DevelopmentFlowProjection;
import com.bytequay.app.developmentflow.compatibility.V2StageApiService;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOwnerResultCodec;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.developmentflow.persistence.SqliteAgentTurnOperationStore;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchTicketStore;
import com.bytequay.app.developmentflow.stage.LocalBrainResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteFeedbackRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteRepairTurnRuntime;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.stage.V2StageSteeringControl;
import com.bytequay.app.developmentflow.stage.V2StageSteeringRuntime;
import com.bytequay.app.developmentflow.task.SqliteTaskBrainConversationStore;
import com.bytequay.app.developmentflow.task.SqliteTaskBrainConversationStore.NewTurn;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.threads.ChatAttachmentStore;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.testing.MigratedSqliteDatabase;
import com.bytequay.app.testing.SqliteTestPools;
import com.bytequay.app.testing.V2TaskSeed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(SqliteTestPools.class)
class TestV2LocalStageStore
{
    @TempDir
    private Path tempDir;

    @Test
    void exactCommittedStageResultMaterializesTheStableTaskPr()
            throws Exception
    {
        DataSource dataSource = database();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc, "legacy-wrong");
        freezeContextBaseForMigratedFixture(jdbc, "master");
        seedImplementationRequest(jdbc);
        seedDevelopmentSubmission(jdbc);
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2StageStore stageStore = new V2StageStore(jdbc);
        TaskManager tasks = new TaskManager(
                commands, taskStore(jdbc, transactions));
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stageStore, stageStore);
        ResultFence fence = implementationFence();
        commands.execute("task-1", () -> local.requestImplementationInCommand(
                new StageManager.Command(
                        "request-implementation", "runtime", "task-1",
                        1, "local-stage", 1, 0),
                fence, "implementation-request"));
        ObjectMapper mapper = new ObjectMapper();
        AgentTurnOwnerResultCodec.OwnerResult delivery = stageDelivery(
                mapper, fence, true, "head-new");
        markSucceededResultPending(
                jdbc, "implementation-ticket", fence,
                mapper.writeValueAsString(delivery.payload()), null);
        persistFinishedAgentExecution(
                jdbc, mapper, "implementation-ticket", 1, delivery);
        PRService prs = mock(PRService.class);

        DispatchTicket.DeliveryReceipt receipt = runtime(
                commands, tasks, local,
                new SqliteLocalDevelopmentRuntimeStore(jdbc),
                mapper, prs)
                .deliverStageTurn(delivery);

        assertThat(receipt.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        // Design 3.36: the PR is created with the agent's template-aware body,
        // not the empty string that produced "No description provided".
        verify(prs).createForTaskInCommand(
                "task-1", "dev/task-1", "master", "Test task assignment-1",
                "## Summary\nRaised the label.\n\n## Validation\nmvn verify");
        assertThat(jdbc.queryForMap("""
                SELECT revision.subject_kind, revision.subject_id,
                       revision.code_fingerprint, revision.head_sha,
                       revision.base_sha
                FROM task_code_subject_revision_v320 revision
                JOIN dev_report report ON report.id = revision.subject_id
                WHERE report.stage_turn_id = 'implementation-turn'
                """))
                .containsEntry("subject_kind", "DEVELOPMENT_REPORT")
                .containsEntry("code_fingerprint", "fingerprint-new")
                .containsEntry("head_sha", "head-new")
                .containsEntry("base_sha", "head-old");
        assertThat(jdbc.queryForMap("""
                SELECT source_code_subject_kind, source_code_subject_id,
                       code_fingerprint, head_sha, base_sha
                FROM task_current_code_subject_fence_v322
                WHERE task_id = 'task-1'
                """))
                .containsEntry("source_code_subject_kind", "DEVELOPMENT_REPORT")
                .containsEntry("code_fingerprint", "fingerprint-new")
                .containsEntry("head_sha", "head-new")
                .containsEntry("base_sha", "head-old");
    }

    @Test
    void aBlankCommitSummaryIsAcceptedBecauseTheSimplifyPromptInvitesIt()
            throws Exception
    {
        // SIMPLIFY_INSTRUCTION tells a Turn that changed nothing to "leave
        // commitSummary blank". Requiring it here parked every such Turn on a
        // protocol failure the agent was instructed to produce.
        DataSource dataSource = database("blank-commit-summary.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc, "legacy-wrong");
        freezeContextBaseForMigratedFixture(jdbc, "master");
        seedImplementationRequest(jdbc);
        seedDevelopmentSubmission(jdbc, "");
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2StageStore stageStore = new V2StageStore(jdbc);
        TaskManager tasks = new TaskManager(
                commands, taskStore(jdbc, transactions));
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stageStore, stageStore);
        ResultFence fence = implementationFence();
        commands.execute("task-1", () -> local.requestImplementationInCommand(
                new StageManager.Command(
                        "request-implementation", "runtime", "task-1",
                        1, "local-stage", 1, 0),
                fence, "implementation-request"));
        ObjectMapper mapper = new ObjectMapper();
        AgentTurnOwnerResultCodec.OwnerResult delivery = stageDelivery(
                mapper, fence, true, "head-new",
                "Nothing needed simplifying.");
        markSucceededResultPending(
                jdbc, "implementation-ticket", fence,
                mapper.writeValueAsString(delivery.payload()), null);
        persistFinishedAgentExecution(
                jdbc, mapper, "implementation-ticket", 1, delivery);

        DispatchTicket.DeliveryReceipt receipt = runtime(
                commands, tasks, local,
                new SqliteLocalDevelopmentRuntimeStore(jdbc),
                mapper, mock(PRService.class))
                .deliverStageTurn(delivery);

        assertThat(receipt.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(jdbc.queryForObject("""
                SELECT commit_summary FROM dev_report
                WHERE stage_turn_id = 'implementation-turn'
                """, String.class))
                .isEmpty();
    }

    @Test
    void developmentReportOwnershipRollsBackWithItsAcceptedCommand()
            throws Exception
    {
        DataSource dataSource = database("development-report-rollback.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        seedImplementationRequest(jdbc);
        seedDevelopmentSubmission(jdbc);
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2StageStore stageStore = new V2StageStore(jdbc);
        TaskManager tasks = new TaskManager(
                commands, taskStore(jdbc, transactions));
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stageStore, stageStore);
        ResultFence fence = implementationFence();
        commands.execute("task-1", () -> local.requestImplementationInCommand(
                new StageManager.Command(
                        "request-implementation", "runtime", "task-1",
                        1, "local-stage", 1, 0),
                fence, "implementation-request"));
        markSucceededResultPending(jdbc, "implementation-ticket", fence);
        jdbc.execute("""
                CREATE TRIGGER force_validation_failure
                BEFORE INSERT ON validation_operation
                BEGIN SELECT RAISE(ABORT, 'forced validation failure'); END
                """);

        assertThatThrownBy(() -> runtime(
                commands, tasks, local,
                new SqliteLocalDevelopmentRuntimeStore(jdbc),
                new ObjectMapper(), mock(PRService.class))
                .deliverStageTurn(stageDelivery(
                        new ObjectMapper(), fence, true, "head-new")))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("forced validation failure");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM dev_report", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_code_subject_revision_v320
                WHERE subject_kind = 'DEVELOPMENT_REPORT'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM stage_turn WHERE id = 'implementation-turn'",
                String.class)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                "SELECT checkpoint FROM stage WHERE id = 'local-stage'",
                String.class)).isEqualTo("IMPLEMENTING");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM dispatch_ticket
                WHERE id = 'implementation-ticket'
                """, String.class)).isEqualTo("RESULT_PENDING");
    }

    @Test
    void strictCreationAuthorityFreezesTheTaskBaseBeforeDevelopmentStarts()
    {
        DataSource dataSource = database();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc, null);

        assertThat(jdbc.queryForMap("""
                SELECT base_source, base_ref, planning_base_sha
                FROM task_creation_context WHERE task_id = 'task-1'
                """))
                .containsEntry("base_source", "PLANNING_SNAPSHOT")
                .containsEntry("base_ref", "main")
                .containsEntry("planning_base_sha", "head-old");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE task_creation_context SET base_ref = NULL
                WHERE task_id = 'task-1'
                """))
                .hasStackTraceContaining("Task creation context is immutable");
    }

    @Test
    void dirtyStageResultCannotCreatePrReportOrAdvanceStage()
            throws Exception
    {
        assertInvalidStageOutput(false, "head-new", "uncommitted");
    }

    @Test
    void unchangedStageHeadCannotCreatePrReportOrAdvanceStage()
            throws Exception
    {
        assertInvalidStageOutput(true, "head-old", "no commit ahead");
    }

    @Test
    void aTurnThatNeverRecordedItsResultIsRejected()
            throws Exception
    {
        // The replacement for "prose is not strict JSON". Prose in the final
        // message is now fine — it is never parsed. What fails instead is a
        // Turn that ended without calling record_development_result, and it
        // fails by that name rather than with a Jackson message.
        assertInvalidStageResult(
                true,
                "head-new",
                "I finished the work but forgot to report it.",
                "succeeded without record_development_result",
                false);
    }

    @Test
    void changedCodeSubjectCanAdvanceImplementationToValidation()
    {
        DataSource dataSource = database();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        ResultFence source = new ResultFence(
                1, "local-stage", 1, "implementation-operation", 1,
                "fingerprint-old", "head-old", "head-old");
        seedImplementationRequest(jdbc);

        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        V2StageStore store = new V2StageStore(jdbc);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, store, store);
        commands.execute("task-1", () -> local.requestImplementationInCommand(
                new StageManager.Command(
                        "request-implementation", "runtime", "task-1",
                        1, "local-stage", 1, 0),
                source,
                "implementation-request"));

        jdbc.update("""
                UPDATE stage_turn
                SET status = 'SUCCEEDED', started_at_ms = 6, finished_at_ms = 7
                WHERE id = 'implementation-turn'
                """);
        jdbc.update("""
                INSERT INTO dev_report(
                    id, task_id, summary, decisions_json, invariants_json,
                    tricky_spots_json, test_map_json, followups_json,
                    created_at_ms, workflow_version,
                    local_development_stage_id, task_epoch, stage_generation,
                    stage_turn_id, revision, code_fingerprint, head_sha, base_sha,
                    implemented_intent, commit_summary, file_summary,
                    validation_summary, known_risks, unresolved_concerns,
                    context_refs, source_code_fingerprint, source_head_sha,
                    source_base_sha)
                VALUES (
                    'report-1', 'task-1', 'implemented', '[]', '[]', '[]',
                    '[]', '[]', 7, 'V2', 'local-stage', 1, 1,
                    'implementation-turn', 1,
                    'fingerprint-new', 'head-new', 'head-old',
                    'implement the approved plan', '', 'changed one file',
                    '', '', '', '[]',
                    'fingerprint-old', 'head-old', 'head-old')
                """);

        StageManager.State accepted = commands.execute("task-1", () ->
                local.acceptImplementationResultInCommand(
                        new StageManager.ResultCommand(
                                "accept-implementation", "dispatcher", "task-1", source),
                        "report-1")).state();

        assertThat(accepted.checkpoint().name()).isEqualTo("VALIDATING");
        assertThat(accepted.pendingResult()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT head_sha FROM dev_report WHERE id = 'report-1'",
                String.class)).isEqualTo("head-new");
        assertThat(jdbc.queryForObject(
                "SELECT source_head_sha FROM dev_report WHERE id = 'report-1'",
                String.class)).isEqualTo("head-old");
    }

    @Test
    void brainBudgetExhaustionTerminalizesAndClearsExactTaskFence()
            throws Exception
    {
        DataSource dataSource = database();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        seedBrainReview(jdbc);

        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2StageStore stageStore = new V2StageStore(jdbc);
        TaskManager.Store taskStore = taskStore(jdbc, transactions);
        TaskManager tasks = new TaskManager(commands, taskStore);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stageStore, stageStore);
        ResultFence fence = new ResultFence(
                1, "local-stage", 1, "brain-operation", 1,
                "fingerprint-old", "head-old", "head-old");
        commands.execute("task-1", () -> tasks.requestBrainReviewInCommand(
                new TaskManager.BrainReviewRequestCommand(
                        "request-brain", "runtime", "task-1", 1, 1,
                        "brain-episode", fence)));
        markResultPending(jdbc, "brain-ticket", fence);

        AgentTurnOperationHandler.ExactTurn exactTurn =
                new SqliteAgentTurnOperationStore(jdbc)
                        .find(DispatchTicket.OwnerKind.TASK_TURN, "brain-turn")
                        .orElseThrow();
        assertThat(exactTurn.purpose()).isEqualTo("DEVELOPMENT_BRAIN_REVIEW");
        assertThat(exactTurn.operationId()).isEqualTo("brain-operation");

        SqliteLocalDevelopmentRuntimeStore runtime =
                new SqliteLocalDevelopmentRuntimeStore(jdbc);
        ObjectMapper mapper = new ObjectMapper();
        AgentTurnOwnerResultCodec.OwnerResult delivery = brainDelivery(
                mapper, fence, DispatchTicket.Outcome.FAILED,
                AgentTurnOperationHandler.Disposition.PROVIDER_FAILED,
                "", "BRAIN_BUDGET_EXHAUSTED");
        PRService prs = mock(PRService.class);
        DispatchTicket.DeliveryReceipt accepted = new LocalBrainResultDeliveryPort(
                runtime(commands, tasks, local, runtime, mapper, prs))
                .deliver(delivery);

        assertThat(accepted.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);

        TaskManager.State restarted = taskStore.findById("task-1").orElseThrow();
        assertThat(restarted.pendingBrainResult()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM task_turn WHERE id = 'brain-turn'",
                String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM brain_review_episode WHERE id = 'brain-episode'",
                String.class)).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(jdbc.queryForObject(
                "SELECT checkpoint FROM stage WHERE id = 'local-stage'",
                String.class)).isEqualTo("LOCAL_REVIEW");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_blocker WHERE blocker_type = "
                        + "'BRAIN_BUDGET_EXHAUSTED' AND status = 'OPEN'",
                Integer.class)).isOne();

        TaskManager restartedTasks = new TaskManager(
                commands, taskStore(jdbc, transactions));
        LocalDevelopmentStageManager restartedLocal =
                new LocalDevelopmentStageManager(
                        commands, new V2StageStore(jdbc), new V2StageStore(jdbc));
        DispatchTicket.DeliveryReceipt duplicate = new LocalBrainResultDeliveryPort(
                runtime(
                        commands, restartedTasks, restartedLocal,
                        new SqliteLocalDevelopmentRuntimeStore(jdbc), mapper,
                        prs))
                .deliver(delivery);
        assertThat(duplicate.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM local_brain_turn_delivery_receipt",
                Integer.class)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_brain_budget_receipt",
                Integer.class)).isOne();
        verify(prs, times(1)).requestUserReviewInCommand(
                "task-1", "v2-local-runtime");
    }

    @Test
    void approvedBrainVerdictOpensLocalReviewExactlyOnce()
            throws Exception
    {
        DataSource dataSource = database();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        seedBrainReview(jdbc);

        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2StageStore stageStore = new V2StageStore(jdbc);
        TaskManager.Store taskStore = taskStore(jdbc, transactions);
        TaskManager tasks = new TaskManager(commands, taskStore);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stageStore, stageStore);
        ResultFence fence = brainFence("brain-operation", 1);
        commands.execute("task-1", () -> tasks.requestBrainReviewInCommand(
                new TaskManager.BrainReviewRequestCommand(
                        "request-brain", "runtime", "task-1", 1, 1,
                        "brain-episode", fence)));
        markSucceededResultPending(jdbc, "brain-ticket", fence);
        ObjectMapper mapper = new ObjectMapper();
        AgentTurnOwnerResultCodec.OwnerResult delivery = brainDelivery(
                mapper, "brain-turn", fence,
                "{\"schemaVersion\":1,\"verdict\":\"APPROVED\","
                        + "\"summary\":\"ready\",\"findings\":[]}");
        PRService prs = mock(PRService.class);
        LocalDevelopmentRuntimeCoordinator owner = runtime(
                commands, tasks, local,
                new SqliteLocalDevelopmentRuntimeStore(jdbc), mapper, prs);

        assertThat(owner.deliverBrainTurn(delivery).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(stageStore.findOwner("task-1", "local-stage")
                .orElseThrow().stage().checkpoint().name())
                .isEqualTo("LOCAL_REVIEW");
        assertThat(jdbc.queryForObject(
                "SELECT verdict FROM brain_review_episode "
                        + "WHERE id = 'brain-episode'", String.class))
                .isEqualTo("APPROVED");

        assertThat(owner.deliverBrainTurn(delivery).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        verify(prs, times(1)).requestUserReviewInCommand(
                "task-1", "v2-local-runtime");
    }

    @Test
    void localReviewPrFailureRollsBackTheAcceptedBrainBoundary()
            throws Exception
    {
        DataSource dataSource = database("brain-pr-rollback.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        seedBrainReview(jdbc);

        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2StageStore stageStore = new V2StageStore(jdbc);
        TaskManager.Store taskStore = taskStore(jdbc, transactions);
        TaskManager tasks = new TaskManager(commands, taskStore);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stageStore, stageStore);
        ResultFence fence = brainFence("brain-operation", 1);
        commands.execute("task-1", () -> tasks.requestBrainReviewInCommand(
                new TaskManager.BrainReviewRequestCommand(
                        "request-brain", "runtime", "task-1", 1, 1,
                        "brain-episode", fence)));
        markSucceededResultPending(jdbc, "brain-ticket", fence);
        ObjectMapper mapper = new ObjectMapper();
        AgentTurnOwnerResultCodec.OwnerResult delivery = brainDelivery(
                mapper, "brain-turn", fence,
                "{\"schemaVersion\":1,\"verdict\":\"APPROVED\","
                        + "\"summary\":\"ready\",\"findings\":[]}");
        PRService prs = mock(PRService.class);
        doThrow(new IllegalStateException("stable PR is unavailable"))
                .when(prs).requestUserReviewInCommand(
                        "task-1", "v2-local-runtime");
        LocalDevelopmentRuntimeCoordinator owner = runtime(
                commands, tasks, local,
                new SqliteLocalDevelopmentRuntimeStore(jdbc), mapper, prs);

        assertThatThrownBy(() -> owner.deliverBrainTurn(delivery))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stable PR is unavailable");
        assertThat(jdbc.queryForObject(
                "SELECT checkpoint FROM stage WHERE id = 'local-stage'",
                String.class)).isEqualTo("BRAIN_REVIEW");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM brain_review_episode "
                        + "WHERE id = 'brain-episode'", String.class))
                .isEqualTo("REQUESTED");
        assertThat(taskStore.findById("task-1").orElseThrow()
                .pendingBrainResult()).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM dispatch_ticket WHERE id = 'brain-ticket'",
                String.class)).isEqualTo("RESULT_PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM local_brain_turn_delivery_receipt",
                Integer.class)).isZero();
    }

    @Test
    void brainChangesVerdictCreatesOneExactStageOwnedFixTurn()
            throws Exception
    {
        DataSource dataSource = database();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        seedBrainReview(jdbc);

        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2StageStore stageStore = new V2StageStore(jdbc);
        TaskManager.Store taskStore = taskStore(jdbc, transactions);
        TaskManager tasks = new TaskManager(commands, taskStore);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stageStore, stageStore);
        ResultFence fence = new ResultFence(
                1, "local-stage", 1, "brain-operation", 1,
                "fingerprint-old", "head-old", "head-old");
        commands.execute("task-1", () -> tasks.requestBrainReviewInCommand(
                new TaskManager.BrainReviewRequestCommand(
                        "request-brain", "runtime", "task-1", 1, 1,
                        "brain-episode", fence)));
        markSucceededResultPending(jdbc, "brain-ticket", fence);

        ObjectMapper mapper = new ObjectMapper();
        AgentTurnOwnerResultCodec.OwnerResult delivery = brainDelivery(
                mapper, fence, DispatchTicket.Outcome.SUCCEEDED,
                AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED,
                """
                        {"schemaVersion":1,"verdict":"CHANGES_REQUESTED",
                         "summary":"one issue remains","findings":["fix A"]}
                        """,
                null);
        DispatchTicket.DeliveryReceipt accepted = new LocalBrainResultDeliveryPort(
                runtime(
                        commands, tasks, local,
                        new SqliteLocalDevelopmentRuntimeStore(jdbc), mapper))
                .deliver(delivery);

        assertThat(accepted.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        TaskManager.State restarted = taskStore.findById("task-1").orElseThrow();
        assertThat(restarted.pendingBrainResult()).isNull();
        assertThat(restarted.lastBrainVerdict())
                .isEqualTo(TaskManager.BrainVerdict.CHANGES_REQUESTED);
        StageManager.State restartedStage = stageStore
                .findOwner("task-1", "local-stage").orElseThrow().stage();
        assertThat(restartedStage.checkpoint().name())
                .isEqualTo("ADDRESSING_BRAIN_FINDINGS");
        assertThat(restartedStage.pendingResult()).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM brain_review_episode WHERE id = 'brain-episode'",
                String.class)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                "SELECT verdict FROM brain_review_episode WHERE id = 'brain-episode'",
                String.class)).isEqualTo("CHANGES_REQUESTED");

        String fixTurnId = jdbc.queryForObject(
                "SELECT id FROM stage_turn WHERE purpose = 'ADDRESS_BRAIN_FINDINGS'",
                String.class);
        AgentTurnOperationHandler.ExactTurn fixTurn =
                new SqliteAgentTurnOperationStore(jdbc)
                        .find(DispatchTicket.OwnerKind.STAGE_TURN, fixTurnId)
                        .orElseThrow();
        assertThat(fixTurn.taskId()).isEqualTo("task-1");
        assertThat(fixTurn.stageId()).isEqualTo("local-stage");
        assertThat(fixTurn.expectedCodeFingerprint()).isEqualTo("fingerprint-old");
        assertThat(fixTurn.brainProvider()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM dispatch_ticket ticket
                JOIN stage_turn turn ON turn.operation_id = ticket.operation_id
                WHERE turn.id = ? AND ticket.owner_kind = 'STAGE_TURN'
                  AND ticket.owner_id = turn.id
                  AND ticket.callback_route = 'STAGE_TURN_RESULT'
                  AND ticket.writer_required = 1
                """, Integer.class, fixTurnId)).isOne();
    }

    @Test
    void taskCancellationSupersedesItsLateBrainResult()
            throws Exception
    {
        DataSource dataSource = database();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        seedBrainReview(jdbc);

        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2StageStore stageStore = new V2StageStore(jdbc);
        TaskManager.Store taskStore = taskStore(jdbc, transactions);
        TaskManager tasks = new TaskManager(commands, taskStore);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stageStore, stageStore);
        ResultFence fence = new ResultFence(
                1, "local-stage", 1, "brain-operation", 1,
                "fingerprint-old", "head-old", "head-old");
        commands.execute("task-1", () -> tasks.requestBrainReviewInCommand(
                new TaskManager.BrainReviewRequestCommand(
                        "request-brain", "runtime", "task-1", 1, 1,
                        "brain-episode", fence)));
        markSucceededResultPending(jdbc, "brain-ticket", fence);
        tasks.requestCancel(new TaskManager.Command(
                "cancel-task", "user", "task-1", 1, 2));

        ObjectMapper mapper = new ObjectMapper();
        DispatchTicket.DeliveryReceipt receipt = new LocalBrainResultDeliveryPort(
                runtime(
                        commands, tasks, local,
                        new SqliteLocalDevelopmentRuntimeStore(jdbc), mapper))
                .deliver(brainDelivery(
                        mapper, fence, DispatchTicket.Outcome.SUCCEEDED,
                        AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED,
                        """
                                {"schemaVersion":1,"verdict":"APPROVED",
                                 "summary":"late","findings":[]}
                                """,
                        null));

        assertThat(receipt.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.SUPERSEDED);
        TaskManager.State canceled = taskStore.findById("task-1").orElseThrow();
        assertThat(canceled.lifecycle().name()).isEqualTo("CANCELING");
        assertThat(canceled.epoch()).isEqualTo(2);
        assertThat(canceled.pendingBrainResult()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM task_turn WHERE id = 'brain-turn'",
                String.class)).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM brain_review_episode WHERE id = 'brain-episode'",
                String.class)).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject(
                "SELECT checkpoint FROM stage WHERE id = 'local-stage'",
                String.class)).isEqualTo("BRAIN_REVIEW");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_blocker", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM stage_turn "
                        + "WHERE purpose = 'ADDRESS_BRAIN_FINDINGS'",
                Integer.class)).isZero();
    }

    @Test
    void successfulV299BrainResultCanFailProtocolAndRetryAfterV300Restart()
            throws Exception
    {
        DataSource dataSource = database("brain-v300-upgrade.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        ObjectMapper mapper = new ObjectMapper();
        String launchInput = brainLaunch(
                mapper, "brain-turn", "brain-operation",
                "Resume the provider session only.",
                "Review this implementation and return the development Brain "
                        + "verdict through the owner-scoped tool.", true);
        seedBrainReview(jdbc, launchInput);

        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2StageStore stageStore = new V2StageStore(jdbc);
        TaskManager.Store taskStore = taskStore(jdbc, transactions);
        TaskManager tasks = new TaskManager(commands, taskStore);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stageStore, stageStore);
        ResultFence fence = brainFence("brain-operation", 1);
        commands.execute("task-1", () -> tasks.requestBrainReviewInCommand(
                new TaskManager.BrainReviewRequestCommand(
                        "request-brain", "runtime", "task-1", 1, 1,
                        "brain-episode", fence)));
        AgentTurnOwnerResultCodec.OwnerResult malformed = brainDelivery(
                mapper, "brain-turn", fence, "final answer: not JSON");
        markSucceededResultPending(
                jdbc, "brain-ticket", fence,
                mapper.writeValueAsString(malformed.payload()), null);
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    provider_session_id, status, started_at_ms, finished_at_ms,
                    error_class, error_message)
                VALUES ('brain-execution-1', 'brain-ticket', 1, 'openai',
                    'old-session', 'FAILED', 8, 9, 'TRANSPORT', 'retryable'),
                       ('brain-execution-2', 'brain-ticket', 2, 'openai',
                    'new-session', 'SUCCEEDED', 10, 11, NULL, NULL)
                """);
        jdbc.update("""
                INSERT INTO agent_execution_log(
                    execution_id, seq, payload, created_at_ms)
                VALUES ('brain-execution-1', 0, 'trace-attempt-1-a', 8),
                       ('brain-execution-1', 1, 'trace-attempt-1-b', 9),
                       ('brain-execution-2', 0, 'trace-attempt-2-a', 10)
                """);
        persistFinishedAgentExecution(
                jdbc, mapper, "brain-ticket", 2, malformed);

        SqliteLocalDevelopmentRuntimeStore runtimeStore =
                new SqliteLocalDevelopmentRuntimeStore(jdbc);
        LocalDevelopmentRuntimeCoordinator owner = runtime(
                commands, tasks, local, runtimeStore, mapper);

        DispatchTicket.DeliveryReceipt accepted = owner.deliverBrainTurn(malformed);

        assertThat(accepted.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(taskStore.findById("task-1").orElseThrow()
                .pendingBrainResult()).isNull();
        assertThat(jdbc.queryForMap("""
                SELECT failure.raw_outcome, turn.status AS turn_status,
                       episode.status AS episode_status,
                       blocker.status AS blocker_status,
                       blocker.subject_revision
                  FROM development_brain_protocol_failure_v300 failure
                  JOIN task_turn turn ON turn.id = failure.task_turn_id
                  JOIN brain_review_episode episode
                    ON episode.id = failure.brain_review_episode_id
                  JOIN task_blocker blocker ON blocker.id = failure.blocker_id
                """))
                .containsEntry("raw_outcome", "SUCCEEDED")
                .containsEntry("turn_status", "FAILED")
                .containsEntry("episode_status", "FAILED")
                .containsEntry("blocker_status", "OPEN")
                .containsEntry("subject_revision", "brain-turn");
        assertThat(jdbc.queryForObject("""
                SELECT pending_result_outcome FROM dispatch_ticket
                 WHERE id = 'brain-ticket'
                """, String.class)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM agent_execution
                 WHERE id = 'brain-execution-2'
                """, String.class)).isEqualTo("SUCCEEDED");

        TaskBrainViewData beforeRetry = new V2DevelopmentFlowProjection(jdbc)
                .brain(legacyTask());
        assertThat(beforeRetry.recovery()).isEqualTo(
                new TaskBrainViewData.RecoveryAction(
                        "RETRY_DEVELOPMENT_BRAIN_REVIEW", "local-stage",
                        beforeRetry.recovery().blockerId(), "brain-turn"));
        String blockerId = beforeRetry.recovery().blockerId();
        completeTicket(dataSource, "brain-ticket", accepted);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM dispatch_ticket WHERE id = 'brain-ticket'",
                String.class)).isEqualTo("SUCCEEDED");

        var retry = owner.retryFailedBrainReview(
                "task-1", "brain-turn", blockerId,
                "retry-brain-command", "user", "retry malformed Brain output");

        assertThat(jdbc.queryForObject(
                "SELECT status FROM task_blocker WHERE id = ?",
                String.class, blockerId)).isEqualTo("RESOLVED");
        assertThat(taskStore.findById("task-1").orElseThrow()
                .pendingBrainResult().operationId())
                .isEqualTo(retry.replacementOperationId());
        assertThat(jdbc.queryForMap("""
                SELECT execution_attempt, budget_attempt, consumes_budget
                  FROM development_brain_retry_budget_lineage_v300
                 WHERE successor_episode_id = ?
                """, retry.replacementEpisodeId()))
                .containsEntry("execution_attempt", 2)
                .containsEntry("budget_attempt", 1)
                .containsEntry("consumes_budget", 0);
        var replacementLaunch = mapper.readTree(jdbc.queryForObject(
                "SELECT launch_input FROM task_turn WHERE id = ?",
                String.class, retry.replacementTurnId()));
        assertThat(replacementLaunch.path("prompt").asText())
                .startsWith("Review this implementation")
                .contains("owner-scoped tool")
                .contains("earlier instruction to submit through an owner-scoped "
                        + "tool is obsolete")
                .contains("Return only strict JSON with exactly this shape")
                .contains("APPROVED requires an empty findings array")
                .contains("CHANGES_REQUESTED requires one or more")
                .contains("exactly one raw JSON object")
                .contains("Do not wrap it in Markdown fences or add prose");
        String replacementPrompt = replacementLaunch.path("prompt").asText();
        assertThat(replacementPrompt.indexOf("trace-attempt-1-a"))
                .isLessThan(replacementPrompt.indexOf("trace-attempt-1-b"));
        assertThat(replacementPrompt.indexOf("trace-attempt-1-b"))
                .isLessThan(replacementPrompt.indexOf("trace-attempt-2-a"));
        assertThat(replacementLaunch.has("resumeSessionId")).isFalse();
        assertThat(replacementLaunch.has("fallbackPrompt")).isFalse();
        assertThat(replacementLaunch.has("priorCumulativeInputTokens")).isFalse();
        assertThat(replacementLaunch.has("priorCumulativeOutputTokens")).isFalse();
        assertThat(replacementLaunch.path("toolEndpoint").path("ownerId").asText())
                .isEqualTo(retry.replacementTurnId());
        assertThat(replacementLaunch.path("toolEndpoint")
                .path("operationId").asText())
                .isEqualTo(retry.replacementOperationId());
        assertThat(new V2DevelopmentFlowProjection(jdbc)
                .brain(legacyTask()).recovery()).isNull();
        assertThat(owner.retryFailedBrainReview(
                "task-1", "brain-turn", blockerId,
                "retry-brain-command", "user", "retry malformed Brain output"))
                .isEqualTo(retry);
        assertThat(owner.deliverBrainTurn(malformed).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(taskStore.findById("task-1").orElseThrow()
                .pendingBrainResult().operationId())
                .isEqualTo(retry.replacementOperationId());
    }

    @Test
    void secondMalformedBrainResultIsRepairedOnceAndAcceptedByStrictDecoder()
            throws Exception
    {
        BrainRepairHarness harness = secondMalformedBrainResult(
                "brain-result-repair-valid.db");
        String repaired = "{\"schemaVersion\":1,\"verdict\":\"APPROVED\","
                + "\"summary\":\"Implementation matches the intent\","
                + "\"findings\":[]}";
        ResultFence fence = brainFence(harness.repairOperationId(), 3);
        AgentTurnOwnerResultCodec.OwnerResult result = brainDelivery(
                harness.mapper(), harness.repairTurnId(),
                "DEVELOPMENT_BRAIN_RESULT_REPAIR", fence, repaired);
        markSucceededResultPending(
                harness.jdbc(), harness.repairTicketId(), fence,
                harness.mapper().writeValueAsString(result.payload()), null);
        persistFinishedAgentExecution(
                harness.jdbc(), harness.mapper(), harness.repairTicketId(), result);

        DispatchTicket.DeliveryReceipt accepted =
                harness.owner().deliverBrainTurn(result);

        assertThat(accepted.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(harness.taskStore().findById("task-1").orElseThrow()
                .pendingBrainResult()).isNull();
        assertThat(harness.jdbc().queryForMap("""
                SELECT status, raw_outcome, acceptance,
                       source_malformed_output, required_result_shape,
                       length(repaired_payload_digest) AS payload_digest_length
                FROM development_brain_result_repair_v311
                """))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("raw_outcome", "SUCCEEDED")
                .containsEntry("acceptance", "ACCEPTED")
                .containsEntry("source_malformed_output",
                        harness.secondMalformedOutput())
                .containsEntry("required_result_shape",
                        "{\"schemaVersion\":1,\"verdict\":\"APPROVED\","
                                + "\"summary\":\"string\",\"findings\":[]}")
                .containsEntry("payload_digest_length", 64);
        assertThat(harness.jdbc().queryForObject("""
                SELECT verdict FROM brain_review_episode
                WHERE id = ?
                """, String.class, harness.repairEpisodeId()))
                .isEqualTo("APPROVED");
        assertThat(harness.owner().deliverBrainTurn(result).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(harness.jdbc().queryForObject("""
                SELECT COUNT(*) FROM task_turn
                WHERE purpose = 'DEVELOPMENT_BRAIN_RESULT_REPAIR'
                """, Integer.class)).isOne();
    }

    @Test
    void brainResultRepairPersistenceRejectsMutationReplayAndPrematureDelivery()
            throws Exception
    {
        BrainRepairHarness harness = secondMalformedBrainResult(
                "brain-result-repair-trigger.db");

        assertThatThrownBy(() -> harness.jdbc().update("""
                UPDATE development_brain_result_repair_v311
                SET source_malformed_output = 'forged'
                """))
                .rootCause()
                .hasMessageContaining(
                        "Development Brain result repair identity is immutable");
        assertThatThrownBy(() -> harness.jdbc().update("""
                UPDATE development_brain_result_repair_v311
                SET status = 'SUCCEEDED', raw_outcome = 'SUCCEEDED',
                    repair_raw_result_digest = ?, repaired_payload_digest = ?,
                    acceptance = 'ACCEPTED', terminal_evidence = '{}',
                    completed_at_ms = 21
                """, "b".repeat(64), "c".repeat(64)))
                .rootCause()
                .hasMessageContaining(
                        "Development Brain result repair delivery is not exact");
        assertThatThrownBy(() -> harness.jdbc().update("""
                INSERT INTO development_brain_result_repair_v311(
                    id, predecessor_failure_id, source_failure_id,
                    source_task_turn_id, source_operation_id,
                    source_malformed_output, source_raw_result_digest,
                    required_result_shape, repair_brain_review_episode_id,
                    repair_task_turn_id, repair_operation_id, repair_ticket_id,
                    task_id, task_epoch, stage_id, stage_generation,
                    code_fingerprint, head_sha, base_sha, status,
                    requested_at_ms)
                SELECT 'duplicate-repair', predecessor_failure_id,
                    source_failure_id, source_task_turn_id, source_operation_id,
                    source_malformed_output, source_raw_result_digest,
                    required_result_shape, repair_brain_review_episode_id,
                    repair_task_turn_id, repair_operation_id, repair_ticket_id,
                    task_id, task_epoch, stage_id, stage_generation,
                    code_fingerprint, head_sha, base_sha, 'REQUESTED',
                    requested_at_ms
                FROM development_brain_result_repair_v311
                """))
                .rootCause()
                .hasMessageContaining(
                        "Development Brain result repair request is not exact");
    }

    @Test
    void malformedBrainResultRepairCreatesOneManualBlockerWithoutLooping()
            throws Exception
    {
        BrainRepairHarness harness = secondMalformedBrainResult(
                "brain-result-repair-terminal.db");
        ResultFence fence = brainFence(harness.repairOperationId(), 3);
        String malformedRepair = "Still not JSON";
        AgentTurnOwnerResultCodec.OwnerResult result = brainDelivery(
                harness.mapper(), harness.repairTurnId(),
                "DEVELOPMENT_BRAIN_RESULT_REPAIR", fence, malformedRepair);
        markSucceededResultPending(
                harness.jdbc(), harness.repairTicketId(), fence,
                harness.mapper().writeValueAsString(result.payload()), null);
        persistFinishedAgentExecution(
                harness.jdbc(), harness.mapper(), harness.repairTicketId(), result);

        DispatchTicket.DeliveryReceipt accepted =
                harness.owner().deliverBrainTurn(result);

        assertThat(accepted.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(harness.taskStore().findById("task-1").orElseThrow()
                .pendingBrainResult()).isNull();
        assertThat(harness.jdbc().queryForMap("""
                SELECT status, raw_outcome, acceptance,
                       repaired_payload_digest
                FROM development_brain_result_repair_v311
                """))
                .containsEntry("status", "FAILED")
                .containsEntry("raw_outcome", "SUCCEEDED")
                .containsEntry("acceptance", "ACCEPTED")
                .containsEntry("repaired_payload_digest", null);
        assertThat(harness.jdbc().queryForMap("""
                SELECT status, subject_revision, blocker_type
                FROM task_blocker WHERE status = 'OPEN'
                """))
                .containsEntry("status", "OPEN")
                .containsEntry("subject_revision", harness.repairTurnId())
                .containsEntry("blocker_type", "OPERATION_FAILED");
        assertThat(new V2DevelopmentFlowProjection(harness.jdbc())
                .brain(legacyTask()).recovery()).isNull();
        assertThat(harness.jdbc().queryForObject("""
                SELECT COUNT(*) FROM task_turn
                WHERE purpose = 'DEVELOPMENT_BRAIN_RESULT_REPAIR'
                """, Integer.class)).isOne();
    }

    @ParameterizedTest(name = "repair provider outcome {0}")
    @MethodSource("terminalBrainRepairOutcomes")
    void terminalBrainResultRepairProviderOutcomeCreatesOneManualBlocker(
            DispatchTicket.Outcome outcome,
            AgentTurnOperationHandler.Disposition disposition,
            String expectedStatus)
            throws Exception
    {
        BrainRepairHarness harness = secondMalformedBrainResult(
                "brain-result-repair-" + outcome.name() + ".db");
        ResultFence fence = brainFence(harness.repairOperationId(), 3);
        String error = "provider ended before returning repaired JSON";
        AgentTurnOwnerResultCodec.OwnerResult result = brainTerminalDelivery(
                harness.mapper(), harness.repairTurnId(), fence,
                outcome, disposition, error);
        markAgentResultPending(
                harness.jdbc(), harness.repairTicketId(), fence,
                outcome, harness.mapper().writeValueAsString(result.payload()),
                error);
        persistFinishedAgentExecution(
                harness.jdbc(), harness.mapper(), harness.repairTicketId(), result);

        DispatchTicket.DeliveryReceipt accepted =
                harness.owner().deliverBrainTurn(result);

        assertThat(accepted.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(harness.taskStore().findById("task-1").orElseThrow()
                .pendingBrainResult()).isNull();
        assertThat(harness.jdbc().queryForMap("""
                SELECT status, raw_outcome, acceptance
                FROM development_brain_result_repair_v311
                """))
                .containsEntry("status", expectedStatus)
                .containsEntry("raw_outcome", outcome.name())
                .containsEntry("acceptance", "ACCEPTED");
        assertThat(harness.jdbc().queryForObject("""
                SELECT COUNT(*) FROM task_blocker
                WHERE status = 'OPEN' AND subject_revision = ?
                  AND blocker_type = 'OPERATION_FAILED'
                """, Integer.class, harness.repairTurnId())).isOne();
        assertThat(harness.jdbc().queryForObject("""
                SELECT COUNT(*) FROM task_turn
                WHERE purpose = 'DEVELOPMENT_BRAIN_RESULT_REPAIR'
                """, Integer.class)).isOne();
        assertThat(new V2DevelopmentFlowProjection(harness.jdbc())
                .brain(legacyTask()).recovery()).isNull();
    }

    @Test
    void taskCancellationSupersedesItsLateBrainResultRepairWithoutBlocker()
            throws Exception
    {
        BrainRepairHarness harness = secondMalformedBrainResult(
                "brain-result-repair-superseded.db");
        ResultFence fence = brainFence(harness.repairOperationId(), 3);
        AgentTurnOwnerResultCodec.OwnerResult result = brainDelivery(
                harness.mapper(), harness.repairTurnId(),
                "DEVELOPMENT_BRAIN_RESULT_REPAIR", fence,
                "{\"schemaVersion\":1,\"verdict\":\"APPROVED\","
                        + "\"summary\":\"late repair\",\"findings\":[]}");
        markSucceededResultPending(
                harness.jdbc(), harness.repairTicketId(), fence,
                harness.mapper().writeValueAsString(result.payload()), null);
        persistFinishedAgentExecution(
                harness.jdbc(), harness.mapper(), harness.repairTicketId(), result);
        TaskManager.State current = harness.taskStore().findById("task-1")
                .orElseThrow();
        harness.tasks().requestCancel(new TaskManager.Command(
                "cancel-during-result-repair", "user", "task-1",
                current.epoch(), current.version()));

        DispatchTicket.DeliveryReceipt receipt =
                harness.owner().deliverBrainTurn(result);

        assertThat(receipt.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.SUPERSEDED);
        assertThat(harness.jdbc().queryForMap("""
                SELECT status, raw_outcome, acceptance,
                       json_extract(terminal_evidence, '$.repairTurnId')
                           AS evidence_turn_id,
                       length(json_extract(terminal_evidence,
                           '$.rawResultDigest')) AS digest_length
                FROM development_brain_result_repair_v311
                """))
                .containsEntry("status", "SUPERSEDED")
                .containsEntry("raw_outcome", "SUCCEEDED")
                .containsEntry("acceptance", "SUPERSEDED")
                .containsEntry("evidence_turn_id", harness.repairTurnId())
                .containsEntry("digest_length", 64);
        assertThat(harness.jdbc().queryForObject("""
                SELECT COUNT(*) FROM task_blocker WHERE status = 'OPEN'
                """, Integer.class)).isZero();
    }

    @Test
    void blankSecondMalformedResultStaysManualAndCannotAdmitAThirdRetry()
            throws Exception
    {
        BrainRepairHarness harness = secondMalformedBrainResult(
                "brain-result-repair-blank.db", "  \n", false);

        assertThat(harness.jdbc().queryForObject("""
                SELECT COUNT(*) FROM development_brain_result_repair_v311
                """, Integer.class)).isZero();
        assertThat(harness.jdbc().queryForObject("""
                SELECT COUNT(*) FROM task_blocker
                WHERE status = 'OPEN' AND blocker_type = 'OPERATION_FAILED'
                """, Integer.class)).isOne();
        assertThat(new V2DevelopmentFlowProjection(harness.jdbc())
                .brain(legacyTask()).recovery()).isNull();
        String blockerId = harness.jdbc().queryForObject("""
                SELECT blocker_id FROM development_brain_protocol_failure_v300
                WHERE task_turn_id = ?
                """, String.class, harness.sourceTurnId());
        assertThatThrownBy(() -> harness.owner().retryFailedBrainReview(
                "task-1", harness.sourceTurnId(), blockerId,
                "third-retry", "user", "retry blank output"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale or ambiguous");
        assertThat(harness.jdbc().queryForObject("""
                SELECT COUNT(*) FROM task_turn
                WHERE purpose = 'DEVELOPMENT_BRAIN_REVIEW'
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void malformedOrdinaryRetryContinuationAdmitsOneResultRepair()
            throws Exception
    {
        BrainRepairHarness harness = secondMalformedBrainResult(
                "brain-result-repair-continuation.db",
                "{\"schemaVersion\":1,\"summary\":\"missing verdict\","
                        + "\"findings\":[]}",
                true, true);

        assertThat(harness.jdbc().queryForMap("""
                SELECT repair.source_task_turn_id,
                       repair.source_operation_id,
                       source.owner_turn_id, source.owner_operation_id,
                       predecessor.task_turn_id AS predecessor_turn_id
                FROM development_brain_result_repair_v311 repair
                JOIN development_brain_protocol_failure_v300 source
                  ON source.id = repair.source_failure_id
                JOIN development_brain_protocol_failure_v300 predecessor
                  ON predecessor.id = repair.predecessor_failure_id
                """))
                .containsEntry("source_task_turn_id",
                        "brain-retry-continuation")
                .containsEntry("source_operation_id",
                        "brain-retry-continuation-operation")
                .containsEntry("predecessor_turn_id", "brain-turn");
        assertThat(harness.jdbc().queryForObject("""
                SELECT COUNT(*) FROM task_turn
                WHERE purpose = 'DEVELOPMENT_BRAIN_RESULT_REPAIR'
                """, Integer.class)).isOne();
        assertThat(harness.taskStore().findById("task-1").orElseThrow()
                .pendingBrainResult().operationId())
                .isEqualTo(harness.repairOperationId());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedBrainResults")
    void everyMalformedSuccessfulBrainResultBecomesOneRecoverableFailure(
            String name, String finalText)
            throws Exception
    {
        DataSource dataSource = database("brain-invalid-" + name + ".db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        seedBrainReview(jdbc);
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2StageStore stageStore = new V2StageStore(jdbc);
        TaskManager.Store taskStore = taskStore(jdbc, transactions);
        TaskManager tasks = new TaskManager(commands, taskStore);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stageStore, stageStore);
        ResultFence fence = brainFence("brain-operation", 1);
        commands.execute("task-1", () -> tasks.requestBrainReviewInCommand(
                new TaskManager.BrainReviewRequestCommand(
                        "request-brain", "runtime", "task-1", 1, 1,
                        "brain-episode", fence)));
        markSucceededResultPending(jdbc, "brain-ticket", fence);
        ObjectMapper mapper = new ObjectMapper();

        DispatchTicket.DeliveryReceipt receipt = runtime(
                commands, tasks, local,
                new SqliteLocalDevelopmentRuntimeStore(jdbc), mapper)
                .deliverBrainTurn(brainDelivery(
                        mapper, "brain-turn", fence, finalText));

        assertThat(receipt.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(taskStore.findById("task-1").orElseThrow()
                .pendingBrainResult()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM development_brain_protocol_failure_v300
                 WHERE task_turn_id = 'brain-turn' AND raw_outcome = 'SUCCEEDED'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_blocker
                 WHERE owner_kind = 'TASK' AND stage_id IS NULL
                   AND blocker_type = 'OPERATION_FAILED' AND status = 'OPEN'
                """, Integer.class)).isOne();
    }

    @Test
    void brainProtocolFailureReceiptRejectsWrongTrunkAndPartialLastBrainIdentity()
            throws Exception
    {
        DataSource dataSource = database("brain-receipt-invariants.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        seedBrainReview(jdbc);
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2StageStore stageStore = new V2StageStore(jdbc);
        TaskManager tasks = new TaskManager(
                commands, taskStore(jdbc, transactions));
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stageStore, stageStore);
        ResultFence fence = brainFence("brain-operation", 1);
        commands.execute("task-1", () -> tasks.requestBrainReviewInCommand(
                new TaskManager.BrainReviewRequestCommand(
                        "request-brain", "runtime", "task-1", 1, 1,
                        "brain-episode", fence)));
        markSucceededResultPending(jdbc, "brain-ticket", fence);
        ObjectMapper mapper = new ObjectMapper();
        runtime(commands, tasks, local,
                new SqliteLocalDevelopmentRuntimeStore(jdbc), mapper)
                .deliverBrainTurn(brainDelivery(
                        mapper, "brain-turn", fence, "not JSON"));

        jdbc.execute("""
                CREATE TABLE brain_receipt_copy AS
                SELECT * FROM task_brain_protocol_failure_receipt_v300
                """);
        jdbc.update("DELETE FROM task_brain_protocol_failure_receipt_v300");
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms,
                    workspace_id, flow, parallel_slots, turn_version,
                    lifecycle_state)
                VALUES ('trunk-2', 'CLI_AGENT', 'codex', 'Other trunk', 'IDLE',
                    'test', 0, 0, 0, 1, 1, 'workspace-1', 'build', 2,
                    'V2', 'ACTIVE')
                """);
        jdbc.update("""
                UPDATE brain_receipt_copy SET returned_trunk_id = 'trunk-2'
                """);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO task_brain_protocol_failure_receipt_v300
                SELECT * FROM brain_receipt_copy
                """))
                .rootCause()
                .hasMessageContaining(
                        "Brain protocol failure Task receipt is not exact");

        jdbc.update("""
                UPDATE brain_receipt_copy
                   SET returned_trunk_id = 'trunk-1',
                       returned_last_brain_verdict = 'APPROVED'
                """);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO task_brain_protocol_failure_receipt_v300
                SELECT * FROM brain_receipt_copy
                """))
                .rootCause()
                .hasMessageContaining("CHECK constraint failed");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM task_brain_protocol_failure_receipt_v300
                """, Integer.class)).isZero();
    }

    @Test
    void malformedUserWaitSuccessorClearsLogicalFenceAndRetriesExactDelivery()
            throws Exception
    {
        DataSource dataSource = database("brain-continuation-v300.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        ObjectMapper mapper = new ObjectMapper();
        seedBrainReview(jdbc, brainLaunch(
                mapper, "brain-turn", "brain-operation",
                "Review the implementation, asking if context is missing.",
                "Review the implementation, asking if context is missing.",
                false));
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2StageStore stageStore = new V2StageStore(jdbc);
        TaskManager.Store taskStore = taskStore(jdbc, transactions);
        TaskManager tasks = new TaskManager(commands, taskStore);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stageStore, stageStore);
        ResultFence logicalFence = brainFence("brain-operation", 1);
        commands.execute("task-1", () -> tasks.requestBrainReviewInCommand(
                new TaskManager.BrainReviewRequestCommand(
                        "request-brain", "runtime", "task-1", 1, 1,
                        "brain-episode", logicalFence)));

        jdbc.update("""
                UPDATE task_turn
                   SET status = 'SUCCEEDED', started_at_ms = 9,
                       finished_at_ms = 10
                 WHERE id = 'brain-turn'
                """);
        jdbc.update("""
                INSERT INTO task_question(
                    id, turn_id, call_id, prompt, state, answer,
                    answer_revision, created_at_ms, answered_at_ms,
                    answer_free_form, answer_actor, continuation_state)
                VALUES ('brain-question', 'brain-turn', 'call-1',
                    'Use the approved intent?', 'ANSWERED', 'yes', 1,
                    9, 10, 'yes', 'user', 'READY')
                """);
        jdbc.update("""
                INSERT INTO typed_user_wait_result(
                    operation_id, owner_kind, turn_id, wait_kind, wait_id,
                    payload_digest, result_evidence, accepted_at_ms)
                VALUES ('brain-operation', 'TASK_TURN', 'brain-turn',
                    'QUESTION', 'brain-question', ?, 'waiting for user', 9)
                """, "a".repeat(64));
        AgentTurnOwnerResultCodec.OwnerResult waiting = brainDelivery(
                mapper, logicalFence, DispatchTicket.Outcome.SUCCEEDED,
                AgentTurnOperationHandler.Disposition.USER_WAIT, "", null);
        markSucceededResultPending(
                jdbc, "brain-ticket", logicalFence,
                mapper.writeValueAsString(waiting.payload()), null);
        persistFinishedAgentExecution(
                jdbc, mapper, "brain-ticket", waiting);
        completeTicket(
                dataSource, "brain-ticket",
                new DispatchTicket.DeliveryReceipt(
                        DispatchTicket.Acceptance.ACCEPTED, "USER_WAIT"));

        SqliteTaskBrainConversationStore conversations =
                new SqliteTaskBrainConversationStore(jdbc);
        var source = conversations.findContinuationContext(
                "brain-turn", "brain-operation", "QUESTION", "brain-question")
                .orElseThrow();
        String continuationLaunch = brainLaunch(
                mapper, "brain-continuation", "brain-continuation-operation",
                "Continue after the user's answer and return the verdict.",
                "Continue after the user's answer and return the verdict.", false);
        commands.executeVoid("task-1", () -> conversations.insertContinuation(
                source,
                new NewTurn(
                        "brain-continuation", "brain-continuation-operation",
                        "brain-continuation-ticket", "DEVELOPMENT_BRAIN_REVIEW",
                        "workspace-1", "trunk-1", "task-1", 1,
                        "local-stage", 1L, "fingerprint-old", "head-old",
                        "head-old", 2, "API", 2, true, false,
                        "TASK_TURN_RESULT", continuationLaunch,
                        Instant.ofEpochMilli(11)),
                "QUESTION", "brain-question", null));
        ResultFence deliveryFence = brainFence(
                "brain-continuation-operation", 2);
        AgentTurnOwnerResultCodec.OwnerResult malformed = brainDelivery(
                mapper, "brain-continuation", deliveryFence,
                "{\"schemaVersion\":1,\"verdict\":\"APPROVED\","
                        + "\"summary\":\"wrong cardinality\","
                        + "\"findings\":[\"still broken\"]}");
        markSucceededResultPending(
                jdbc, "brain-continuation-ticket", deliveryFence,
                mapper.writeValueAsString(malformed.payload()), null);
        persistFinishedAgentExecution(
                jdbc, mapper, "brain-continuation-ticket", malformed);
        SqliteLocalDevelopmentRuntimeStore runtimeStore =
                new SqliteLocalDevelopmentRuntimeStore(jdbc);
        LocalDevelopmentRuntimeCoordinator owner = runtime(
                commands, tasks, local, runtimeStore, mapper);

        DispatchTicket.DeliveryReceipt accepted = owner.deliverBrainTurn(malformed);

        assertThat(accepted.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(taskStore.findById("task-1").orElseThrow()
                .pendingBrainResult()).isNull();
        assertThat(jdbc.queryForMap("""
                SELECT failure.task_turn_id, failure.owner_turn_id,
                       failure.operation_id, failure.owner_operation_id,
                       blocker.subject_revision
                  FROM development_brain_protocol_failure_v300 failure
                  JOIN task_blocker blocker ON blocker.id = failure.blocker_id
                """))
                .containsEntry("task_turn_id", "brain-continuation")
                .containsEntry("owner_turn_id", "brain-turn")
                .containsEntry("operation_id", "brain-continuation-operation")
                .containsEntry("owner_operation_id", "brain-operation")
                .containsEntry("subject_revision", "brain-continuation");
        completeTicket(dataSource, "brain-continuation-ticket", accepted);
        String blockerId = jdbc.queryForObject("""
                SELECT blocker_id FROM development_brain_protocol_failure_v300
                """, String.class);

        var retry = owner.retryFailedBrainReview(
                "task-1", "brain-continuation", blockerId,
                "retry-continuation", "user", "retry malformed continuation");

        assertThat(retry.failedTurnId()).isEqualTo("brain-continuation");
        assertThat(taskStore.findById("task-1").orElseThrow()
                .pendingBrainResult().operationId())
                .isEqualTo(retry.replacementOperationId());
        assertThat(jdbc.queryForObject("""
                SELECT budget_attempt
                  FROM development_brain_retry_budget_lineage_v300
                 WHERE successor_episode_id = ?
                """, Integer.class, retry.replacementEpisodeId())).isOne();
        assertThat(owner.deliverBrainTurn(malformed).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(taskStore.findById("task-1").orElseThrow()
                .pendingBrainResult().operationId())
                .isEqualTo(retry.replacementOperationId());
    }

    @Test
    void malformedPendingResultCanBeProjectedReplacedAndReplayedExactlyOnce()
            throws Exception
    {
        DataSource dataSource = database();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        ObjectMapper mapper = new ObjectMapper();
        var originalLaunch = mapper.createObjectNode();
        originalLaunch.put("schemaVersion", 1);
        originalLaunch.put("transport", "CLI");
        originalLaunch.put("provider", "claude");
        originalLaunch.put("model", "claude-opus");
        originalLaunch.put("workingDirectory", "/tmp/task-1");
        originalLaunch.put("systemPrompt", "Own this Local Stage.");
        originalLaunch.put("prompt", "resume-only instruction");
        originalLaunch.put("resumeSessionId", "malformed-provider-session");
        originalLaunch.put("fallbackPrompt",
                "Implement the approved frozen plan exactly.");
        originalLaunch.put("priorCumulativeInputTokens", 200);
        originalLaunch.put("priorCumulativeOutputTokens", 40);
        originalLaunch.putObject("toolEndpoint")
                .put("serverName", "bytequay")
                .put("url", "http://127.0.0.1:8080/api/v2/stage-turns/"
                        + "implementation-turn/operations/"
                        + "implementation-operation/mcp")
                .put("ownerKind", "STAGE_TURN")
                .put("ownerId", "implementation-turn")
                .put("operationId", "implementation-operation")
                .put("profile", "STAGE_DEVELOPMENT");
        ResultFence fence = new ResultFence(
                1, "local-stage", 1, "implementation-operation", 1,
                "fingerprint-old", "head-old", "head-old");
        seedImplementationRequest(jdbc, mapper.writeValueAsString(originalLaunch));
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2StageStore stageStore = new V2StageStore(jdbc);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stageStore, stageStore);
        commands.execute("task-1", () -> local.requestImplementationInCommand(
                new StageManager.Command(
                        "request-implementation", "runtime", "task-1",
                        1, "local-stage", 1, 0),
                fence, "implementation-request"));
        AgentTurnOwnerResultCodec.OwnerResult malformed = stageDelivery(
                mapper, fence, true, "head-new", "not strict stage json");
        markSucceededResultPending(
                jdbc, "implementation-ticket", fence,
                mapper.writeValueAsString(malformed.payload()),
                "Local result is not strict JSON");

        TaskManager.Store taskStore = taskStore(jdbc, transactions);
        LocalDevelopmentRuntimeCoordinator localRuntime = runtime(
                commands, new TaskManager(commands, taskStore), local,
                new SqliteLocalDevelopmentRuntimeStore(jdbc), mapper);
        SqliteStageSteeringStore steeringStore =
                new SqliteStageSteeringStore(jdbc);
        ReflectionTestUtils.setField(
                localRuntime, "steering", steeringStore);
        DispatchTicketControl tickets = mock(DispatchTicketControl.class);
        V2StageApiService api = new V2StageApiService(
                jdbc, new V2DevelopmentFlowProjection(jdbc),
                new V2BranchGuardProjection(jdbc), tickets, mapper);
        assertThat(api.detail("task-1", "local-stage")
                .recovery().replacement()).isNull();
        jdbc.update("""
                UPDATE dispatch_ticket
                   SET version = version + 1, last_error = ?
                 WHERE id = 'implementation-ticket'
                """, DispatchTicket.resultProtocolFailure(
                "Local result is not strict JSON"));
        assertThat(api.detail("task-1", "local-stage")
                .recovery().replacement()).isNull();
        persistFinishedAgentExecution(
                jdbc, mapper, "implementation-ticket", malformed);
        jdbc.update("""
                INSERT INTO agent_execution_log(
                    execution_id, seq, payload, created_at_ms)
                VALUES ('implementation-ticket-execution-1', 0,
                    'malformed provider trace', 19)
                """);
        String predecessorTurnId = api.detail("task-1", "local-stage")
                .recovery().replacement().stageTurnId();
        assertThat(predecessorTurnId).isEqualTo("implementation-turn");

        ChatAttachmentStore attachments = mock(ChatAttachmentStore.class);
        when(attachments.save("local-stage", List.of())).thenReturn(List.of());
        @SuppressWarnings("unchecked")
        ObjectProvider<RemoteRepairTurnRuntime> remoteRepairs =
                mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RemoteFeedbackRuntimeCoordinator> remoteFeedback =
                mock(ObjectProvider.class);
        V2StageSteeringRuntime steering = new V2StageSteeringRuntime(
                commands, stageStore, steeringStore, localRuntime,
                mock(PlanRuntimeCoordinator.class), attachments, tickets,
                remoteRepairs, remoteFeedback);
        String instruction = "Retry this stage from its durable context";
        String requestId = steering.steer(
                "task-1", "local-stage", instruction, List.of(),
                V2StageSteeringControl.Mode.CANCEL_AND_REPLACE,
                predecessorTurnId);

        var admission = jdbc.queryForMap("""
                SELECT request.status, request.successor_owner_id,
                       request.successor_operation_id,
                       turn.status AS turn_status, turn.purpose, turn.attempt,
                       local_request.predecessor_turn_id,
                       ticket.id AS successor_ticket_id, turn.launch_input
                  FROM stage_steering_request_v257 request
                  JOIN stage_turn turn
                    ON turn.id = request.successor_owner_id
                   AND turn.operation_id = request.successor_operation_id
                  JOIN local_stage_turn_request local_request
                    ON local_request.stage_turn_id = turn.id
                  JOIN dispatch_ticket ticket
                    ON ticket.owner_id = turn.id
                   AND ticket.operation_id = turn.operation_id
                 WHERE request.id = ?
                """, requestId);
        assertThat(admission)
                .containsEntry("status", "ADMITTED")
                .containsEntry("turn_status", "QUEUED")
                .containsEntry("purpose", "IMPLEMENT_LOCAL_PLAN")
                .containsEntry("attempt", 2)
                .containsEntry("predecessor_turn_id", "implementation-turn");
        String successorTurnId = (String) admission.get("successor_owner_id");
        String successorOperationId =
                (String) admission.get("successor_operation_id");
        JsonNode replacementLaunch = mapper.readTree(
                (String) admission.get("launch_input"));
        // Design 3.35: the replacement carries a bounded rejection brief —
        // reason, rejected Turn id, where the work already is, and how to read
        // the transcript — never the inlined provider trace, which a second
        // replacement would otherwise embed twice.
        assertThat(replacementLaunch.path("prompt").asText())
                .contains("Implement the approved frozen plan exactly.")
                .contains("rejected before it could be accepted")
                .contains("Local result is not strict JSON")
                .contains("Rejected Turn: ")
                .contains("already in this worktree")
                .contains("read_dev_conversation")
                .contains("Retry this exact Local Development operation")
                .doesNotContain("malformed provider trace");
        assertThat(replacementLaunch.has("resumeSessionId")).isFalse();
        assertThat(replacementLaunch.has("fallbackPrompt")).isFalse();
        assertThat(replacementLaunch.has("priorCumulativeInputTokens")).isFalse();
        assertThat(replacementLaunch.has("priorCumulativeOutputTokens")).isFalse();
        assertThat(replacementLaunch.path("toolEndpoint").path("ownerId").asText())
                .isEqualTo(successorTurnId);
        assertThat(replacementLaunch.path("toolEndpoint")
                .path("operationId").asText()).isEqualTo(successorOperationId);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM stage_turn WHERE id = 'implementation-turn'",
                String.class)).isEqualTo("SUPERSEDED");
        assertThat(stageStore.findOwner("task-1", "local-stage"))
                .get()
                .extracting(owner -> owner.stage().pendingResult())
                .isEqualTo(new ResultFence(
                        1, "local-stage", 1, successorOperationId, 2,
                        "fingerprint-old", "head-old", "head-old"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM stage_turn
                 WHERE stage_id = 'local-stage' AND attempt = 2
                """, Integer.class)).isOne();
        verify(tickets).requestCancel("implementation-ticket");

        DispatchTicket.DeliveryReceipt receipt =
                localRuntime.deliverStageTurn(malformed);

        assertThat(receipt.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.SUPERSEDED);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM stage_turn WHERE id = 'implementation-turn'",
                String.class)).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM dev_report WHERE stage_turn_id = "
                        + "'implementation-turn'", Integer.class)).isZero();
        completeSupersededDelivery(jdbc, "implementation-ticket");

        clearInvocations(tickets);
        String replay = steering.steer(
                "task-1", "local-stage", instruction, List.of(),
                V2StageSteeringControl.Mode.CANCEL_AND_REPLACE,
                predecessorTurnId);

        assertThat(replay).isEqualTo(requestId);
        assertThat(jdbc.queryForObject("""
                SELECT successor_owner_id FROM stage_steering_request_v257
                 WHERE id = ?
                """, String.class, requestId)).isEqualTo(successorTurnId);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM local_stage_turn_request
                 WHERE stage_turn_id = ?
                   AND predecessor_turn_id = 'implementation-turn'
                   AND queue_mode = 'CANCEL_AND_REPLACE'
                """, Integer.class, successorTurnId)).isOne();
        verifyNoInteractions(tickets);
    }

    @Test
    void acceptedFailedStageTurnRetriesOnceFromCompleteDurableContext()
            throws Exception
    {
        DataSource dataSource = database();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        freezeContextBaseForMigratedFixture(jdbc, "master");

        ObjectMapper mapper = new ObjectMapper();
        var launch = mapper.createObjectNode();
        launch.put("schemaVersion", 1);
        launch.put("transport", "CLI");
        launch.put("provider", "claude");
        launch.put("model", "claude-opus");
        launch.put("workingDirectory", "/tmp/task-1");
        launch.put("systemPrompt", "Own this exact Local Development Stage.");
        launch.put("prompt", "resume-only instruction");
        launch.put("resumeSessionId", "failed-provider-session");
        launch.put("fallbackPrompt",
                "Implement the approved plan: increase the left-nav task name "
                        + "font size to 14px.");
        launch.put("priorCumulativeInputTokens", 200);
        launch.put("priorCumulativeOutputTokens", 40);
        launch.putObject("toolEndpoint")
                .put("serverName", "bytequay")
                .put("url", "http://127.0.0.1:8080/api/v2/stage-turns/"
                        + "implementation-turn/operations/"
                        + "implementation-operation/mcp")
                .put("ownerKind", "STAGE_TURN")
                .put("ownerId", "implementation-turn")
                .put("operationId", "implementation-operation")
                .put("profile", "STAGE_DEVELOPMENT");
        seedImplementationRequest(jdbc, mapper.writeValueAsString(launch));

        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2StageStore stageStore = new V2StageStore(jdbc);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stageStore, stageStore);
        ResultFence fence = implementationFence();
        commands.execute("task-1", () -> local.requestImplementationInCommand(
                new StageManager.Command(
                        "request-implementation", "runtime", "task-1",
                        1, "local-stage", 1, 0),
                fence, "implementation-request"));
        String providerMessage = "You've hit your session limit · resets "
                + "12:40am (Asia/Singapore)";
        AgentTurnOwnerResultCodec.OwnerResult failed = failedStageDelivery(
                mapper, fence, providerMessage);
        markAgentResultPending(
                jdbc, "implementation-ticket", fence, failed.outcome(),
                mapper.writeValueAsString(failed.payload()),
                failed.payload().error());
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                VALUES ('existing-failure-blocker', 'task-1', 'local-stage',
                    'STAGE', 'local-stage', 'implementation-turn',
                    'OPERATION_FAILED', 'OPEN', '{"message":"failed"}', 8)
                """);
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    provider_session_id, status, started_at_ms, finished_at_ms,
                    error_class, error_message)
                VALUES ('first-failed-execution', 'implementation-ticket', 1,
                    'claude', 'first-provider-session', 'FAILED', 6, 7,
                    'TRANSPORT', 'connection reset')
                """);
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    provider_session_id, status, started_at_ms, finished_at_ms,
                    error_class, error_message)
                VALUES ('failed-execution', 'implementation-ticket', 2,
                    'claude', 'failed-provider-session', 'FAILED', 8, 9,
                    'RATE_LIMIT', 'HTTP 429')
                """);
        jdbc.update("""
                INSERT INTO agent_execution_log(
                    execution_id, seq, payload, created_at_ms)
                VALUES ('first-failed-execution', 0,
                    '{"type":"transport_error","message":"connection reset"}', 7)
                """);
        jdbc.update("""
                INSERT INTO agent_execution_log(
                    execution_id, seq, payload, created_at_ms)
                VALUES ('failed-execution', 0,
                    '{"type":"rate_limit_event","status":"rejected",'
                    || '"resetsAt":1785429600}', 9)
                """);
        persistFinishedAgentExecution(
                jdbc, mapper, "implementation-ticket", 2, failed);

        TaskManager tasks = new TaskManager(
                commands, taskStore(jdbc, transactions));
        SqliteLocalDevelopmentRuntimeStore runtimeStore =
                new SqliteLocalDevelopmentRuntimeStore(jdbc);
        LocalDevelopmentRuntimeCoordinator owner = runtime(
                commands, tasks, local, runtimeStore, mapper);
        DispatchTicket.DeliveryReceipt accepted = owner.deliverStageTurn(failed);

        assertThat(accepted.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM stage_turn WHERE id = 'implementation-turn'",
                String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT error_message FROM stage_turn WHERE id = "
                        + "'implementation-turn'", String.class))
                .isEqualTo(providerMessage);
        assertThat(stageStore.findOwner("task-1", "local-stage")
                .orElseThrow().stage().pendingResult()).isNull();
        String blockerId = jdbc.queryForObject("""
                SELECT id FROM task_blocker
                 WHERE task_id = 'task-1' AND stage_id = 'local-stage'
                   AND blocker_type = 'OPERATION_FAILED' AND status = 'OPEN'
                """, String.class);
        assertThat(blockerId).isEqualTo("existing-failure-blocker");
        assertThat(jdbc.queryForObject(
                "SELECT payload_json FROM task_blocker WHERE id = ?",
                String.class, blockerId)).isEqualTo("{\"message\":\"failed\"}");
        assertThat(jdbc.queryForObject("""
                SELECT payload_json FROM local_stage_turn_failure_v298
                 WHERE stage_turn_id = 'implementation-turn'
                """, String.class)).contains("12:40am");

        assertThatThrownBy(() -> commands.executeVoid("task-1", () -> {
            jdbc.update("""
                    INSERT INTO stage_turn(
                        id, stage_id, stage_generation, purpose, status,
                        operation_id, attempt, task_epoch,
                        expected_code_fingerprint, expected_head_sha,
                        expected_base_sha, delivery_lane, launch_input,
                        requested_at_ms)
                    VALUES ('bypass-turn', 'local-stage', 1, 'USER_STEERING',
                        'QUEUED', 'bypass-operation', 1, 1,
                        'fingerprint-old', 'head-old', 'head-old', 'CLI',
                        '{"prompt":"bypass recovery"}', 19)
                    """);
            jdbc.update("""
                    INSERT INTO local_stage_turn_request(
                        id, command_id, stage_turn_id, task_id,
                        local_development_stage_id, task_epoch, stage_generation,
                        kind, queue_mode, predecessor_turn_id,
                        prompt_digest, requested_by, requested_at_ms)
                    VALUES ('bypass-request', 'bypass-command', 'bypass-turn',
                        'task-1', 'local-stage', 1, 1, 'STEERING', 'IMMEDIATE',
                        NULL, ?, 'user', 19)
                    """, "0".repeat(64));
        })).hasStackTraceContaining(
                "Local StageTurn admission requires exact failure recovery");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM stage_turn WHERE id = 'bypass-turn'
                """, Integer.class)).isZero();

        completeFailedDelivery(jdbc, "implementation-ticket");
        V2StageApiService api = new V2StageApiService(
                jdbc, new V2DevelopmentFlowProjection(jdbc),
                new V2BranchGuardProjection(jdbc),
                mock(DispatchTicketControl.class), mapper);
        assertThat(api.detail("task-1", "local-stage").recovery().failure())
                .satisfies(recovery -> {
                    assertThat(recovery.stageTurnId())
                            .isEqualTo("implementation-turn");
                    assertThat(recovery.blockerId()).isEqualTo(blockerId);
                    assertThat(recovery.reason()).isEqualTo(providerMessage);
                });

        var retry = owner.retryFailedStageTurn(
                "task-1", "implementation-turn", blockerId,
                "retry-command", "user", "explicit retry");

        assertThat(jdbc.queryForObject(
                "SELECT status FROM task_blocker WHERE id = ?",
                String.class, blockerId)).isEqualTo("RESOLVED");
        assertThat(stageStore.findOwner("task-1", "local-stage")
                .orElseThrow().stage().pendingResult().operationId())
                .isEqualTo(retry.replacementOperationId());
        String retryLaunch = jdbc.queryForObject(
                "SELECT launch_input FROM stage_turn WHERE id = ?",
                String.class, retry.replacementTurnId());
        var retryJson = mapper.readTree(retryLaunch);
        assertThat(retryJson.path("prompt").asText())
                .startsWith("Implement the approved plan: increase the left-nav")
                .contains("transport_error")
                .contains("rate_limit_event")
                .contains(providerMessage)
                .contains("Retry this exact Local Development operation")
                // The retry carries the same reporting contract as the Turn it
                // replaces: a tool call, not a raw-JSON final message.
                .contains("report it with record_development_result")
                .doesNotContain("exactly one raw JSON object");
        assertThat(retryJson.path("prompt").asText().indexOf("transport_error"))
                .isLessThan(retryJson.path("prompt").asText()
                        .indexOf("rate_limit_event"));
        assertThat(retryJson.has("resumeSessionId")).isFalse();
        assertThat(retryJson.has("fallbackPrompt")).isFalse();
        assertThat(retryJson.has("priorCumulativeInputTokens")).isFalse();
        assertThat(retryJson.path("toolEndpoint").path("ownerId").asText())
                .isEqualTo(retry.replacementTurnId());
        assertThat(jdbc.queryForObject(
                "SELECT attempt FROM stage_turn WHERE id = ?",
                Integer.class, retry.replacementTurnId())).isEqualTo(2);
        ResultFence retryFence = stageStore.findOwner("task-1", "local-stage")
                .orElseThrow().stage().pendingResult();
        assertThat(retryFence.attempt()).isEqualTo(2);
        markResultPending(jdbc, retry.replacementTicketId(), retryFence);
        assertThat(runtimeStore.requireStageTurnContext(
                retry.replacementTurnId(), retry.replacementOperationId())
                .fence()).isEqualTo(retryFence);

        var replay = owner.retryFailedStageTurn(
                "task-1", "implementation-turn", blockerId,
                "retry-command", "user", "explicit retry");
        assertThat(replay).isEqualTo(retry);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM local_stage_turn_retry_v298
                 WHERE predecessor_turn_id = 'implementation-turn'
                """, Integer.class)).isOne();

        assertThat(owner.deliverStageTurn(failed).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(stageStore.findOwner("task-1", "local-stage")
                .orElseThrow().stage().pendingResult().operationId())
                .isEqualTo(retry.replacementOperationId());
    }

    @Test
    void recordingTheResultTwiceIsIdempotentButCannotBeChanged()
    {
        // The write path record_development_result reaches. An identical
        // re-submission is a no-op so a retried tool call is safe; a differing
        // one is refused, and refused in a sentence the agent can act on.
        DataSource dataSource = database("record-development-result.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        seedImplementationRequest(jdbc);
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2StageStore stageStore = new V2StageStore(jdbc);
        SqliteLocalDevelopmentRuntimeStore store =
                new SqliteLocalDevelopmentRuntimeStore(jdbc);
        LocalDevelopmentRuntimeCoordinator owner = runtime(
                commands, new TaskManager(commands, taskStore(jdbc, transactions)),
                new LocalDevelopmentStageManager(commands, stageStore, stageStore),
                store, new ObjectMapper());
        SqliteLocalDevelopmentRuntimeStore.DevelopmentReport report =
                new SqliteLocalDevelopmentRuntimeStore.DevelopmentReport(
                        "implemented", "one commit", "one file", "mvn verify",
                        "none", "none", "none", "## Summary");

        owner.recordDevelopmentResult(
                "implementation-turn", "implementation-operation", report);
        owner.recordDevelopmentResult(
                "implementation-turn", "implementation-operation", report);

        assertThat(store.findDevelopmentSubmission("implementation-turn"))
                .contains(report);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM stage_turn_development_submission",
                Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> owner.recordDevelopmentResult(
                "implementation-turn", "implementation-operation",
                new SqliteLocalDevelopmentRuntimeStore.DevelopmentReport(
                        "something else", "one commit", "one file", "mvn verify",
                        "none", "none", "none", "## Summary")))
                .hasMessageContaining("already called with different content");
    }

    private static LocalDevelopmentRuntimeCoordinator runtime(
            TaskCommandExecutor commands,
            TaskManager tasks,
            LocalDevelopmentStageManager local,
            SqliteLocalDevelopmentRuntimeStore store,
            ObjectMapper mapper)
    {
        return runtime(commands, tasks, local, store, mapper,
                mock(PRService.class));
    }

    private static LocalDevelopmentRuntimeCoordinator runtime(
            TaskCommandExecutor commands,
            TaskManager tasks,
            LocalDevelopmentStageManager local,
            SqliteLocalDevelopmentRuntimeStore store,
            ObjectMapper mapper,
            PRService prs)
    {
        return new LocalDevelopmentRuntimeCoordinator(
                commands, tasks, local, store, prs, mapper,
                Clock.fixed(Instant.ofEpochMilli(20), ZoneOffset.UTC), 8080);
    }

    private BrainRepairHarness secondMalformedBrainResult(String databaseName)
            throws Exception
    {
        return secondMalformedBrainResult(
                databaseName,
                "{\"schemaVersion\":1,"
                        + "\"summary\":\"approved but verdict is missing\","
                        + "\"findings\":[]}",
                true);
    }

    private BrainRepairHarness secondMalformedBrainResult(
            String databaseName, String secondMalformed, boolean expectRepair)
            throws Exception
    {
        return secondMalformedBrainResult(
                databaseName, secondMalformed, expectRepair, false);
    }

    private BrainRepairHarness secondMalformedBrainResult(
            String databaseName,
            String secondMalformed,
            boolean expectRepair,
            boolean continueOrdinaryRetry)
            throws Exception
    {
        DataSource dataSource = database(databaseName);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        ObjectMapper mapper = new ObjectMapper();
        seedBrainReview(jdbc, brainLaunch(
                mapper, "brain-turn", "brain-operation",
                "Review this exact implementation.",
                "Review this exact implementation.", false));
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2StageStore stageStore = new V2StageStore(jdbc);
        TaskManager.Store taskStore = taskStore(jdbc, transactions);
        TaskManager tasks = new TaskManager(commands, taskStore);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stageStore, stageStore);
        LocalDevelopmentRuntimeCoordinator owner = runtime(
                commands, tasks, local,
                new SqliteLocalDevelopmentRuntimeStore(jdbc), mapper);

        ResultFence originalFence = brainFence("brain-operation", 1);
        commands.execute("task-1", () -> tasks.requestBrainReviewInCommand(
                new TaskManager.BrainReviewRequestCommand(
                        "request-brain", "runtime", "task-1", 1, 1,
                        "brain-episode", originalFence)));
        AgentTurnOwnerResultCodec.OwnerResult originalMalformed = brainDelivery(
                mapper, "brain-turn", originalFence, "First malformed result");
        markSucceededResultPending(
                jdbc, "brain-ticket", originalFence,
                mapper.writeValueAsString(originalMalformed.payload()), null);
        persistFinishedAgentExecution(
                jdbc, mapper, "brain-ticket", originalMalformed);
        DispatchTicket.DeliveryReceipt originalReceipt =
                owner.deliverBrainTurn(originalMalformed);
        String originalBlocker = jdbc.queryForObject("""
                SELECT blocker_id
                FROM development_brain_protocol_failure_v300
                WHERE task_turn_id = 'brain-turn'
                """, String.class);
        completeTicket(dataSource, "brain-ticket", originalReceipt);

        var retryReceipt = owner.retryFailedBrainReview(
                "task-1", "brain-turn", originalBlocker,
                "retry-brain-command", "user", "retry malformed Brain output");
        String sourceTurnId = retryReceipt.replacementTurnId();
        String sourceOperationId = retryReceipt.replacementOperationId();
        String sourceTicketId = retryReceipt.replacementTicketId();
        int sourceAttempt = 2;
        if (continueOrdinaryRetry) {
            ResultFence logicalRetryFence = brainFence(sourceOperationId, 2);
            jdbc.update("""
                    UPDATE task_turn
                    SET status = 'SUCCEEDED', started_at_ms = 14,
                        finished_at_ms = 15
                    WHERE id = ?
                    """, sourceTurnId);
            jdbc.update("""
                    INSERT INTO task_question(
                        id, turn_id, call_id, prompt, state, answer,
                        answer_revision, created_at_ms, answered_at_ms,
                        answer_free_form, answer_actor, continuation_state)
                    VALUES ('retry-brain-question', ?, 'call-retry',
                        'Confirm the intended verdict?', 'ANSWERED', 'yes', 1,
                        14, 15, 'yes', 'user', 'READY')
                    """, sourceTurnId);
            jdbc.update("""
                    INSERT INTO typed_user_wait_result(
                        operation_id, owner_kind, turn_id, wait_kind, wait_id,
                        payload_digest, result_evidence, accepted_at_ms)
                    VALUES (?, 'TASK_TURN', ?, 'QUESTION',
                        'retry-brain-question', ?, 'waiting for user', 14)
                    """, sourceOperationId, sourceTurnId, "b".repeat(64));
            markSucceededResultPending(jdbc, sourceTicketId, logicalRetryFence);
            jdbc.update("""
                    INSERT INTO agent_execution(
                        id, ticket_id, infrastructure_attempt, provider,
                        status, started_at_ms, finished_at_ms, raw_result)
                    VALUES ('brain-retry-user-wait-execution', ?, 1, 'openai',
                        'SUCCEEDED', 14, 15, ?)
                    """, sourceTicketId, mapper.writeValueAsString(
                    new DispatchTicket.DispatchResult(
                            new DispatchTicket.OperationFence(
                                    logicalRetryFence.taskEpoch(),
                                    logicalRetryFence.stageId(),
                                    logicalRetryFence.stageGeneration(),
                                    logicalRetryFence.operationId(),
                                    logicalRetryFence.attempt(),
                                    logicalRetryFence.expectedCodeFingerprint(),
                                    logicalRetryFence.expectedHeadSha(),
                                    logicalRetryFence.expectedBaseSha()),
                            DispatchTicket.Outcome.SUCCEEDED,
                            "{}", "{}", null)));
            completeTicket(
                    dataSource, sourceTicketId,
                    new DispatchTicket.DeliveryReceipt(
                            DispatchTicket.Acceptance.ACCEPTED, "USER_WAIT"));
            SqliteTaskBrainConversationStore conversations =
                    new SqliteTaskBrainConversationStore(jdbc);
            var continuationSource = conversations.findContinuationContext(
                            sourceTurnId, sourceOperationId, "QUESTION",
                            "retry-brain-question")
                    .orElseThrow();
            sourceTurnId = "brain-retry-continuation";
            sourceOperationId = "brain-retry-continuation-operation";
            sourceTicketId = "brain-retry-continuation-ticket";
            sourceAttempt = 3;
            String continuationLaunch = brainLaunch(
                    mapper, sourceTurnId, sourceOperationId,
                    "Return the strict verdict after the user's answer.",
                    "Return the strict verdict after the user's answer.", false);
            String continuationTurnId = sourceTurnId;
            String continuationOperationId = sourceOperationId;
            String continuationTicketId = sourceTicketId;
            commands.executeVoid("task-1", () -> conversations.insertContinuation(
                    continuationSource,
                    new NewTurn(
                            continuationTurnId, continuationOperationId,
                            continuationTicketId, "DEVELOPMENT_BRAIN_REVIEW",
                            "workspace-1", "trunk-1", "task-1", 1,
                            "local-stage", 1L, "fingerprint-old", "head-old",
                            "head-old", 3, "API", 2, true, false,
                            "TASK_TURN_RESULT", continuationLaunch,
                            Instant.ofEpochMilli(16)),
                    "QUESTION", "retry-brain-question", null));
        }
        ResultFence retryFence = brainFence(sourceOperationId, sourceAttempt);
        AgentTurnOwnerResultCodec.OwnerResult retryMalformed = brainDelivery(
                mapper, sourceTurnId, retryFence, secondMalformed);
        markSucceededResultPending(
                jdbc, sourceTicketId, retryFence,
                mapper.writeValueAsString(retryMalformed.payload()), null);
        DispatchTicket.OperationFence retryOperationFence =
                new DispatchTicket.OperationFence(
                        retryFence.taskEpoch(), retryFence.stageId(),
                        retryFence.stageGeneration(), retryFence.operationId(),
                        retryFence.attempt(),
                        retryFence.expectedCodeFingerprint(),
                        retryFence.expectedHeadSha(),
                        retryFence.expectedBaseSha());
        DispatchTicket.DispatchResult durableRetryResult =
                new DispatchTicket.DispatchResult(
                        retryOperationFence, DispatchTicket.Outcome.SUCCEEDED,
                        mapper.writeValueAsString(retryMalformed.payload()),
                        "{}", null);
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    status, started_at_ms, finished_at_ms, raw_result)
                VALUES ('brain-retry-execution', ?, 1, 'openai',
                    'SUCCEEDED', 18, 19, ?)
                """, sourceTicketId,
                mapper.writeValueAsString(durableRetryResult));
        DispatchTicket.DeliveryReceipt retryDelivery =
                owner.deliverBrainTurn(retryMalformed);
        completeTicket(dataSource, sourceTicketId, retryDelivery);

        if (!expectRepair) {
            return new BrainRepairHarness(
                    dataSource, jdbc, mapper, owner, tasks, taskStore,
                    null, null, null, null, secondMalformed,
                    sourceTurnId);
        }

        var repair = jdbc.queryForMap("""
                SELECT repair_brain_review_episode_id, repair_task_turn_id,
                       repair_operation_id, repair_ticket_id,
                       source_malformed_output
                FROM development_brain_result_repair_v311
                """);
        String repairTurnId = (String) repair.get("repair_task_turn_id");
        var launch = mapper.readTree(jdbc.queryForObject("""
                SELECT launch_input FROM task_turn WHERE id = ?
                """, String.class, repairTurnId));
        assertThat(launch.has("resumeSessionId")).isFalse();
        assertThat(launch.has("fallbackPrompt")).isFalse();
        assertThat(launch.has("priorCumulativeInputTokens")).isFalse();
        assertThat(launch.has("priorCumulativeOutputTokens")).isFalse();
        assertThat(launch.has("images")).isFalse();
        assertThat(launch.path("prompt").asText())
                .contains(mapper.writeValueAsString(secondMalformed))
                .contains("Do not add facts or perform a new review")
                .contains("exactly one raw JSON object");
        assertThat(jdbc.queryForMap("""
                SELECT execution_attempt, budget_attempt, consumes_budget
                FROM development_brain_retry_budget_lineage_v300
                WHERE successor_episode_id = ?
                """, repair.get("repair_brain_review_episode_id")))
                .containsEntry("execution_attempt", 3)
                .containsEntry("budget_attempt", 1)
                .containsEntry("consumes_budget", 0);
        assertThat(taskStore.findById("task-1").orElseThrow()
                .pendingBrainResult().operationId())
                .isEqualTo(repair.get("repair_operation_id"));
        return new BrainRepairHarness(
                dataSource, jdbc, mapper, owner, tasks, taskStore,
                (String) repair.get("repair_brain_review_episode_id"),
                repairTurnId, (String) repair.get("repair_operation_id"),
                (String) repair.get("repair_ticket_id"), secondMalformed,
                sourceTurnId);
    }

    private record BrainRepairHarness(
            DataSource dataSource,
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            LocalDevelopmentRuntimeCoordinator owner,
            TaskManager tasks,
            TaskManager.Store taskStore,
            String repairEpisodeId,
            String repairTurnId,
            String repairOperationId,
            String repairTicketId,
            String secondMalformedOutput,
            String sourceTurnId) {}

    private void assertInvalidStageOutput(
            boolean clean, String outputHead, String expectedMessage)
            throws Exception
    {
        assertInvalidStageResult(
                clean, outputHead, developmentResult(), expectedMessage);
    }

    private void assertInvalidStageResult(
            boolean clean,
            String outputHead,
            String finalText,
            String expectedMessage)
            throws Exception
    {
        assertInvalidStageResult(clean, outputHead, finalText, expectedMessage, true);
    }

    private void assertInvalidStageResult(
            boolean clean,
            String outputHead,
            String finalText,
            String expectedMessage,
            boolean recordedResult)
            throws Exception
    {
        DataSource dataSource = database();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        seedImplementationRequest(jdbc);
        if (recordedResult) {
            seedDevelopmentSubmission(jdbc);
        }
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2StageStore stageStore = new V2StageStore(jdbc);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stageStore, stageStore);
        ResultFence fence = implementationFence();
        commands.execute("task-1", () -> local.requestImplementationInCommand(
                new StageManager.Command(
                        "request-implementation", "runtime", "task-1",
                        1, "local-stage", 1, 0),
                fence, "implementation-request"));
        markSucceededResultPending(jdbc, "implementation-ticket", fence);
        PRService prs = mock(PRService.class);
        LocalDevelopmentRuntimeCoordinator owner = runtime(
                commands,
                new TaskManager(commands, taskStore(jdbc, transactions)),
                local, new SqliteLocalDevelopmentRuntimeStore(jdbc),
                new ObjectMapper(), prs);

        assertThatThrownBy(() -> owner.deliverStageTurn(stageDelivery(
                new ObjectMapper(), fence, clean, outputHead, finalText)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessage);

        verify(prs, never()).createForTaskInCommand(
                anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM dev_report", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT checkpoint FROM stage WHERE id = 'local-stage'",
                String.class)).isEqualTo("IMPLEMENTING");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM dispatch_ticket "
                        + "WHERE id = 'implementation-ticket'",
                String.class)).isEqualTo("RESULT_PENDING");
    }

    private static ResultFence implementationFence()
    {
        return new ResultFence(
                1, "local-stage", 1, "implementation-operation", 1,
                "fingerprint-old", "head-old", "head-old");
    }

    private static AgentTurnOwnerResultCodec.OwnerResult brainDelivery(
            ObjectMapper mapper,
            ResultFence fence,
            DispatchTicket.Outcome outcome,
            AgentTurnOperationHandler.Disposition disposition,
            String finalText,
            String error)
            throws Exception
    {
        DispatchTicket.OperationFence operationFence = new DispatchTicket.OperationFence(
                fence.taskEpoch(), fence.stageId(), fence.stageGeneration(),
                fence.operationId(), fence.attempt(),
                fence.expectedCodeFingerprint(), fence.expectedHeadSha(),
                fence.expectedBaseSha());
        DispatchTicket.OwnerReference owner = new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.TASK_TURN,
                "brain-turn", "TASK_TURN_RESULT");
        AgentTurnOperationHandler.RawResult payload =
                new AgentTurnOperationHandler.RawResult(
                        1, "brain-turn", DispatchTicket.OwnerKind.TASK_TURN,
                        "DEVELOPMENT_BRAIN_REVIEW",
                        AgentTurnProviderSession.Transport.API, "openai", "session",
                        finalText, 1, 1, 1, null, disposition, error,
                        disposition == AgentTurnOperationHandler.Disposition.USER_WAIT
                                ? new AgentTurnOperationHandler.UserWaitRef(
                                        "QUESTION", "brain-question")
                                : null);
        DispatchTicket.DispatchResult raw = new DispatchTicket.DispatchResult(
                operationFence, outcome, mapper.writeValueAsString(payload),
                "{}", error);
        return new AgentTurnOwnerResultCodec(mapper).decode(
                owner, operationFence, raw);
    }

    private static AgentTurnOwnerResultCodec.OwnerResult brainDelivery(
            ObjectMapper mapper,
            String turnId,
            ResultFence fence,
            String finalText)
            throws Exception
    {
        return brainDelivery(
                mapper, turnId, "DEVELOPMENT_BRAIN_REVIEW", fence, finalText);
    }

    private static AgentTurnOwnerResultCodec.OwnerResult brainDelivery(
            ObjectMapper mapper,
            String turnId,
            String purpose,
            ResultFence fence,
            String finalText)
            throws Exception
    {
        DispatchTicket.OperationFence operationFence =
                new DispatchTicket.OperationFence(
                        fence.taskEpoch(), fence.stageId(),
                        fence.stageGeneration(), fence.operationId(),
                        fence.attempt(), fence.expectedCodeFingerprint(),
                        fence.expectedHeadSha(), fence.expectedBaseSha());
        DispatchTicket.OwnerReference owner = new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.TASK_TURN, turnId, "TASK_TURN_RESULT");
        AgentTurnOperationHandler.RawResult payload =
                new AgentTurnOperationHandler.RawResult(
                        1, turnId, DispatchTicket.OwnerKind.TASK_TURN,
                        purpose,
                        AgentTurnProviderSession.Transport.API, "openai", "session",
                        finalText, 1, 1, 1, null,
                        AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED,
                        null);
        return new AgentTurnOwnerResultCodec(mapper).decode(
                owner, operationFence,
                new DispatchTicket.DispatchResult(
                        operationFence, DispatchTicket.Outcome.SUCCEEDED,
                        mapper.writeValueAsString(payload), "{}", null));
    }

    private static AgentTurnOwnerResultCodec.OwnerResult brainTerminalDelivery(
            ObjectMapper mapper,
            String turnId,
            ResultFence fence,
            DispatchTicket.Outcome outcome,
            AgentTurnOperationHandler.Disposition disposition,
            String error)
            throws Exception
    {
        DispatchTicket.OperationFence operationFence =
                new DispatchTicket.OperationFence(
                        fence.taskEpoch(), fence.stageId(),
                        fence.stageGeneration(), fence.operationId(),
                        fence.attempt(), fence.expectedCodeFingerprint(),
                        fence.expectedHeadSha(), fence.expectedBaseSha());
        DispatchTicket.OwnerReference owner = new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.TASK_TURN, turnId, "TASK_TURN_RESULT");
        AgentTurnOperationHandler.RawResult payload =
                new AgentTurnOperationHandler.RawResult(
                        1, turnId, DispatchTicket.OwnerKind.TASK_TURN,
                        "DEVELOPMENT_BRAIN_RESULT_REPAIR",
                        AgentTurnProviderSession.Transport.API, "openai", "session",
                        "", 1, 1, 1, null, disposition, error);
        return new AgentTurnOwnerResultCodec(mapper).decode(
                owner, operationFence,
                new DispatchTicket.DispatchResult(
                        operationFence, outcome,
                        mapper.writeValueAsString(payload), "{}", error));
    }

    private static ResultFence brainFence(String operationId, int attempt)
    {
        return new ResultFence(
                1, "local-stage", 1, operationId, attempt,
                "fingerprint-old", "head-old", "head-old");
    }

    private static Stream<Arguments> malformedBrainResults()
    {
        return Stream.of(
                Arguments.of("blank", ""),
                Arguments.of("missing-verdict",
                        "{\"schemaVersion\":1,\"summary\":\"ok\",\"findings\":[]}"),
                Arguments.of("blank-summary",
                        "{\"schemaVersion\":1,\"verdict\":\"APPROVED\","
                                + "\"summary\":\" \",\"findings\":[]}"),
                Arguments.of("wrong-schema",
                        "{\"schemaVersion\":2,\"verdict\":\"APPROVED\","
                                + "\"summary\":\"ok\",\"findings\":[]}"),
                Arguments.of("unknown-verdict",
                        "{\"schemaVersion\":1,\"verdict\":\"BLOCKED\","
                                + "\"summary\":\"no\",\"findings\":[]}"),
                Arguments.of("approved-with-findings",
                        "{\"schemaVersion\":1,\"verdict\":\"APPROVED\","
                                + "\"summary\":\"no\",\"findings\":[\"fix\"]}"),
                Arguments.of("changes-without-findings",
                        "{\"schemaVersion\":1,"
                                + "\"verdict\":\"CHANGES_REQUESTED\","
                                + "\"summary\":\"no\",\"findings\":[]}"),
                Arguments.of("mixed-content",
                        "{\"schemaVersion\":1,\"verdict\":\"APPROVED\","
                                + "\"summary\":\"ok\",\"findings\":[]} prose"),
                Arguments.of("unknown-field",
                        "{\"schemaVersion\":1,\"verdict\":\"APPROVED\","
                                + "\"summary\":\"ok\",\"findings\":[],"
                                + "\"extra\":true}"),
                Arguments.of("duplicate-field",
                        "{\"schemaVersion\":1,\"verdict\":\"APPROVED\","
                                + "\"verdict\":\"CHANGES_REQUESTED\","
                                + "\"summary\":\"no\",\"findings\":[]}"),
                Arguments.of("wrong-finding-type",
                        "{\"schemaVersion\":1,\"verdict\":\"APPROVED\","
                                + "\"summary\":\"ok\",\"findings\":[7]}"),
                Arguments.of("scalar-coercion",
                        "{\"schemaVersion\":\"1\",\"verdict\":\"APPROVED\","
                                + "\"summary\":7,\"findings\":[]}"));
    }

    private static Stream<Arguments> terminalBrainRepairOutcomes()
    {
        return Stream.of(
                Arguments.of(
                        DispatchTicket.Outcome.FAILED,
                        AgentTurnOperationHandler.Disposition.PROVIDER_FAILED,
                        "FAILED"),
                Arguments.of(
                        DispatchTicket.Outcome.CANCELED,
                        AgentTurnOperationHandler.Disposition.PROVIDER_CANCELED,
                        "CANCELED"),
                Arguments.of(
                        DispatchTicket.Outcome.INDETERMINATE,
                        AgentTurnOperationHandler.Disposition.RECONCILIATION_REQUIRED,
                        "FAILED"));
    }

    private static AgentTurnOwnerResultCodec.OwnerResult stageDelivery(
            ObjectMapper mapper, ResultFence fence)
            throws Exception
    {
        return stageDelivery(mapper, fence, true, "head-new");
    }

    private static AgentTurnOwnerResultCodec.OwnerResult stageDelivery(
            ObjectMapper mapper,
            ResultFence fence,
            boolean clean,
            String outputHead)
            throws Exception
    {
        return stageDelivery(
                mapper, fence, clean, outputHead, developmentResult());
    }

    /** The Turn's final message. Prose on purpose: nothing parses it now. */
    private static String developmentResult()
    {
        return "Implemented the approved change and recorded the result.";
    }

    /** Stand in for the record_development_result call the agent makes while
     *  its Turn is running, so delivery has a submission row to read. */
    private static void seedDevelopmentSubmission(JdbcTemplate jdbc)
    {
        seedDevelopmentSubmission(jdbc, "one commit");
    }

    private static void seedDevelopmentSubmission(
            JdbcTemplate jdbc, String commitSummary)
    {
        jdbc.update("""
                INSERT INTO stage_turn_development_submission(
                    stage_turn_id, operation_id, task_id, implemented_intent,
                    commit_summary, file_summary, validation_summary,
                    known_risks, unresolved_concerns, context_refs,
                    pr_description, submitted_at_ms)
                VALUES ('implementation-turn', 'implementation-operation',
                    'task-1', 'implemented', ?, 'one file', 'pending',
                    'none', 'none', 'none',
                    '## Summary' || char(10) || 'Raised the label.' || char(10)
                        || char(10) || '## Validation' || char(10) || 'mvn verify',
                    1)
                """, commitSummary);
    }

    private static AgentTurnOwnerResultCodec.OwnerResult stageDelivery(
            ObjectMapper mapper,
            ResultFence fence,
            boolean clean,
            String outputHead,
            String finalText)
            throws Exception
    {
        DispatchTicket.OperationFence operationFence =
                new DispatchTicket.OperationFence(
                        fence.taskEpoch(), fence.stageId(), fence.stageGeneration(),
                        fence.operationId(), fence.attempt(),
                        fence.expectedCodeFingerprint(), fence.expectedHeadSha(),
                        fence.expectedBaseSha());
        DispatchTicket.OwnerReference owner = new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.STAGE_TURN,
                "implementation-turn", "STAGE_TURN_RESULT");
        AgentTurnOperationHandler.RawResult payload =
                new AgentTurnOperationHandler.RawResult(
                        1, "implementation-turn",
                        DispatchTicket.OwnerKind.STAGE_TURN,
                        "IMPLEMENT_LOCAL_PLAN",
                        AgentTurnProviderSession.Transport.API, "openai", "session",
                        finalText, 1, 1, 1, null,
                        AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED,
                        null, null,
                        new AgentTurnOperationHandler.OutputCodeSubject(
                                "fingerprint-new", outputHead, "head-old",
                                clean, "head-old", "tree-old",
                                outputHead.equals(fence.expectedHeadSha())
                                        ? "tree-old" : "tree-new"));
        AgentTurnOperationHandler.Evidence evidence =
                new AgentTurnOperationHandler.Evidence(
                        1, AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED,
                        null,
                        new AgentTurnProviderSession.WriterFence(
                                "/tmp/task-1", "task-1", fence.operationId(),
                                fence.taskEpoch(), 1),
                        null, payload.outputCodeSubject());
        DispatchTicket.DispatchResult raw = new DispatchTicket.DispatchResult(
                operationFence, DispatchTicket.Outcome.SUCCEEDED,
                mapper.writeValueAsString(payload),
                mapper.writeValueAsString(evidence), null);
        return new AgentTurnOwnerResultCodec(mapper).decode(
                owner, operationFence, raw);
    }

    private static AgentTurnOwnerResultCodec.OwnerResult failedStageDelivery(
            ObjectMapper mapper, ResultFence fence, String providerMessage)
            throws Exception
    {
        DispatchTicket.OperationFence operationFence =
                new DispatchTicket.OperationFence(
                        fence.taskEpoch(), fence.stageId(), fence.stageGeneration(),
                        fence.operationId(), fence.attempt(),
                        fence.expectedCodeFingerprint(), fence.expectedHeadSha(),
                        fence.expectedBaseSha());
        DispatchTicket.OwnerReference owner = new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.STAGE_TURN,
                "implementation-turn", "STAGE_TURN_RESULT");
        AgentTurnOperationHandler.RawResult payload =
                new AgentTurnOperationHandler.RawResult(
                        1, "implementation-turn",
                        DispatchTicket.OwnerKind.STAGE_TURN,
                        "IMPLEMENT_LOCAL_PLAN",
                        AgentTurnProviderSession.Transport.CLI, "claude",
                        "failed-provider-session", providerMessage,
                        0, 0, 0, null,
                        AgentTurnOperationHandler.Disposition.PROVIDER_FAILED,
                        "turn failed");
        DispatchTicket.DispatchResult raw = new DispatchTicket.DispatchResult(
                operationFence, DispatchTicket.Outcome.FAILED,
                mapper.writeValueAsString(payload), "{}", "turn failed");
        return new AgentTurnOwnerResultCodec(mapper).decode(
                owner, operationFence, raw);
    }

    private static void completeFailedDelivery(
            JdbcTemplate jdbc, String ticketId)
    {
        jdbc.update("""
                UPDATE dispatch_ticket
                   SET version = version + 1,
                       status = 'FAILED',
                       pending_result_outcome = NULL,
                       pending_result_payload = NULL,
                       pending_result_evidence = NULL,
                       pending_result_error = NULL,
                       pending_result_task_epoch = NULL,
                       pending_result_stage_id = NULL,
                       pending_result_stage_generation = NULL,
                       pending_result_operation_id = NULL,
                       pending_result_attempt = NULL,
                       pending_result_expected_code_fingerprint = NULL,
                       pending_result_expected_head_sha = NULL,
                       pending_result_expected_base_sha = NULL,
                       delivery_acceptance = 'ACCEPTED',
                       delivery_evidence = 'Local failure accepted',
                       completed_at_ms = 21,
                       last_error = 'provider failed'
                 WHERE id = ? AND status = 'RESULT_PENDING'
                """, ticketId);
    }

    private static void completeSupersededDelivery(
            JdbcTemplate jdbc, String ticketId)
    {
        jdbc.update("""
                UPDATE dispatch_ticket
                   SET version = version + 1,
                       status = 'SUCCEEDED',
                       pending_result_outcome = NULL,
                       pending_result_payload = NULL,
                       pending_result_evidence = NULL,
                       pending_result_error = NULL,
                       pending_result_task_epoch = NULL,
                       pending_result_stage_id = NULL,
                       pending_result_stage_generation = NULL,
                       pending_result_operation_id = NULL,
                       pending_result_attempt = NULL,
                       pending_result_expected_code_fingerprint = NULL,
                       pending_result_expected_head_sha = NULL,
                       pending_result_expected_base_sha = NULL,
                       delivery_acceptance = 'SUPERSEDED',
                       delivery_evidence = 'late predecessor superseded',
                       completed_at_ms = 21
                 WHERE id = ? AND status = 'RESULT_PENDING'
                """, ticketId);
    }

    private DataSource database()
    {
        return database("local-store.db");
    }

    private DataSource database(String name)
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(name)
                + "?foreign_keys=ON&busy_timeout=30000";
        MigratedSqliteDatabase.migrate(url);
        DataSource dataSource = SqliteTestPools.open(url);
        return dataSource;
    }

    private static void seedLocalOwner(JdbcTemplate jdbc)
    {
        seedLocalOwner(jdbc, "master");
    }

    private static void seedLocalOwner(JdbcTemplate jdbc, String legacyBaseBranch)
    {
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'Workspace', '', 0, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms,
                    workspace_id, flow, parallel_slots, turn_version,
                    lifecycle_state)
                VALUES ('trunk-1', 'CLI_AGENT', 'codex', 'Trunk', 'IDLE',
                    'test', 0, 0, 0, 1, 1, 'workspace-1', 'build', 2,
                    'V2', 'ACTIVE')
                """);
        V2TaskSeed.prepareWorkspaces(jdbc);
        jdbc.update("""
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, created_by, created_at_ms)
                VALUES ('policy-1', 'trunk-1', 1, 'TRUNK', 'user', 2)
                """);
        V2TaskSeed.insertAuthorized(jdbc, "assignment-1", seed -> seed.update("""
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms, creation_authorization_id)
                VALUES ('assignment-1', 'trunk-1', 'NEW_FROM_TRUNK', 'head-old',
                    'seed', 'build', 'user', 2, 'authorization-assignment-1')
                """));
        String taskInsert = """
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, epoch, aggregate_version, lifecycle_state,
                    assignment_id, policy_revision_id, base_branch,
                    creation_receipt_id, name, task_type, opening_prompt, origin)
                VALUES ('task-1', 'trunk-1', 1, 'IDLE', 'PLANNING', 2,
                    'V2', 1, 0, 'PROVISIONING', 'assignment-1', 'policy-1',
                    ?, 'creation-receipt-task-1', 'Test task assignment-1',
                    'DEVELOP', 'build', 'user')
                """;
        V2TaskSeed.insertCreated(jdbc, "task-1",
                seed -> seed.update(taskInsert, legacyBaseBranch));
        seedProvisionedCode(jdbc);
        jdbc.update("""
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint,
                    opened_at_ms)
                VALUES ('local-stage', 'task-1', 'LOCAL_DEVELOPMENT', 1, 0,
                    'IMPLEMENTING', 3)
                """);
        jdbc.update("""
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES ('task-1', 'local-stage', 1)
                """);
        jdbc.update("""
                UPDATE tasks
                SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                WHERE id = 'task-1'
                """);
        jdbc.update("""
                INSERT INTO local_development_stage(
                    stage_id, task_id, generation, opened_for_epoch)
                VALUES ('local-stage', 'task-1', 1, 1)
                """);
    }

    private static void seedProvisionedCode(JdbcTemplate jdbc)
    {
        V2TaskSeed.completeProvisioning(
                jdbc, "task-1", "head-old", "head-old",
                "fingerprint-old", "created", 3);
    }

    /**
     * This suite seeds its owner at V228 and migrates it forward. New V2 Tasks
     * already freeze this value during creation; normalize the historical test
     * fixture so the Local lookup exercises that modern source of truth.
     */
    private static void freezeContextBaseForMigratedFixture(
            JdbcTemplate jdbc, String baseRef)
    {
        jdbc.execute("DROP TRIGGER task_creation_context_immutable");
        try {
            jdbc.update("""
                    UPDATE task_creation_context
                    SET base_source = 'PLANNING_SNAPSHOT',
                        base_repository_id = repository_id,
                        base_ref = ?
                    WHERE task_id = 'task-1'
                    """, baseRef);
        }
        finally {
            jdbc.execute("""
                    CREATE TRIGGER task_creation_context_immutable
                    BEFORE UPDATE ON task_creation_context
                    BEGIN SELECT RAISE(ABORT,
                        'Task creation context is immutable'); END
                    """);
        }
    }

    private static String brainLaunch(
            ObjectMapper mapper,
            String turnId,
            String operationId,
            String prompt,
            String fallbackPrompt,
            boolean resumable)
            throws Exception
    {
        var launch = mapper.createObjectNode();
        launch.put("schemaVersion", 1);
        launch.put("transport", "API");
        launch.put("provider", "openai");
        launch.put("model", "review-model");
        launch.put("workingDirectory", "/tmp/task-1");
        launch.put("systemPrompt", "Read-only Development Brain review.");
        launch.put("prompt", prompt);
        if (resumable) {
            launch.put("resumeSessionId", "old-provider-session");
            launch.put("fallbackPrompt", fallbackPrompt);
            launch.put("priorCumulativeInputTokens", 120);
            launch.put("priorCumulativeOutputTokens", 30);
        }
        launch.putObject("toolEndpoint")
                .put("serverName", "bytequay")
                .put("url", "http://127.0.0.1:8080/api/v2/task-turns/"
                        + turnId + "/operations/" + operationId + "/mcp")
                .put("ownerKind", "TASK_TURN")
                .put("ownerId", turnId)
                .put("operationId", operationId)
                .put("profile", "TASK_BRAIN_READ_ONLY")
                .put("approvalPromptTool", "mcp__bytequay__approval_prompt");
        return mapper.writeValueAsString(launch);
    }

    private static void completeTicket(
            DataSource dataSource,
            String ticketId,
            DispatchTicket.DeliveryReceipt receipt)
    {
        SqliteDispatchTicketStore tickets =
                new SqliteDispatchTicketStore(dataSource);
        DispatchTicket ticket = tickets.findById(ticketId).orElseThrow();
        if (!tickets.compareAndSet(
                ticketId, ticket.version(),
                ticket.completeDelivery(receipt, Instant.ofEpochMilli(21)))) {
            throw new IllegalStateException("Could not complete test ticket");
        }
    }

    private static Task legacyTask()
    {
        return new Task(
                "task-1", "trunk-1", 1, TaskStatus.IDLE,
                "legacy-fallback", "/tmp/legacy-fallback", "master", "/tmp",
                null, null, null, null, null, null, null, null,
                0, 0, 0, null, Instant.ofEpochMilli(1), null, null,
                null, null, null);
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

    private static void seedBrainReview(JdbcTemplate jdbc)
    {
        seedBrainReview(jdbc, "{}");
    }

    private static void seedBrainReview(
            JdbcTemplate jdbc, String launchInput)
    {
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms, started_at_ms, finished_at_ms)
                VALUES ('implemented-turn', 'local-stage', 1,
                    'IMPLEMENT_LOCAL_PLAN', 'SUCCEEDED', 'implemented-operation',
                    1, 1, 'fingerprint-old', 'head-old', 'head-old', 'API', '{}',
                    4, 4, 5)
                """);
        jdbc.update("""
                INSERT INTO dev_report(
                    id, task_id, summary, created_at_ms, workflow_version,
                    local_development_stage_id, task_epoch, stage_generation,
                    stage_turn_id, revision, code_fingerprint, head_sha, base_sha,
                    implemented_intent, commit_summary, file_summary,
                    validation_summary, known_risks, unresolved_concerns,
                    context_refs, source_code_fingerprint, source_head_sha,
                    source_base_sha)
                VALUES ('report-brain', 'task-1', 'implemented', 5, 'V2',
                    'local-stage', 1, 1, 'implemented-turn', 1,
                    'fingerprint-old', 'head-old', 'head-old', 'intent', '', '',
                    '', '', '', '[]', 'fingerprint-old', 'head-old', 'head-old')
                """);
        jdbc.update("""
                INSERT INTO validation_operation(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, dev_report_id, operation_id,
                    semantic_attempt, code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES ('validation-1', 'local-stage', 'task-1', 1, 1,
                    'report-brain', 'validation-operation', 1,
                    'fingerprint-old', 'head-old', 'head-old', 'REQUESTED', 6)
                """);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('validation-ticket', 'validation-operation',
                    'VALIDATE_LOCAL_DEVELOPMENT', 'VALIDATION', 'STAGE',
                    'local-stage', 'STAGE_VALIDATION_RESULT', 4, 1, 0,
                    'workspace-1', 'trunk-1', 'task-1', 1, 'local-stage', 1, 1,
                    'fingerprint-old', 'head-old', 'head-old', 'REQUESTED', 6)
                """);
        jdbc.update("""
                UPDATE validation_operation SET status = 'DISPATCHED'
                WHERE id = 'validation-1'
                """);
        jdbc.update("""
                INSERT INTO validation_pass(
                    task_id, started_at_ms, ended_at_ms, passed, fix_rounds,
                    failures_json, workflow_version, task_epoch, stage_id,
                    stage_generation, operation_id, semantic_attempt,
                    code_fingerprint, expected_head_sha, expected_base_sha)
                VALUES ('task-1', 6, 7, 1, 0, '[]', 'V2', 1,
                    'local-stage', 1, 'validation-operation', 1,
                    'fingerprint-old', 'head-old', 'head-old')
                """);
        Long passId = jdbc.queryForObject(
                "SELECT id FROM validation_pass WHERE operation_id = "
                        + "'validation-operation'", Long.class);
        jdbc.update("""
                INSERT INTO validation_evidence(
                    id, validation_operation_id, validation_pass_id, task_id,
                    task_epoch, stage_id, stage_generation, code_fingerprint,
                    head_sha, base_sha, passed, failures_digest, evidence,
                    completed_at_ms)
                VALUES ('validation-evidence', 'validation-1', ?, 'task-1',
                    1, 'local-stage', 1, 'fingerprint-old', 'head-old',
                    'head-old', 1, NULL, '{}', 7)
                """, passId);
        jdbc.update("""
                UPDATE validation_operation
                SET status = 'COMPLETED', completed_at_ms = 7
                WHERE id = 'validation-1'
                """);
        jdbc.update("""
                UPDATE stage
                SET checkpoint = 'BRAIN_REVIEW', version = version + 1
                WHERE id = 'local-stage'
                """);
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES ('brain-turn', 'task-1', 'DEVELOPMENT_BRAIN_REVIEW',
                    'QUEUED', 'brain-operation', 1, 1, 'local-stage', 1,
                    'fingerprint-old', 'head-old', 'head-old', 'API', ?, 8)
                """, launchInput);
        jdbc.update("""
                INSERT INTO brain_review_episode(
                    id, task_brain_id, task_id, task_epoch,
                    local_development_stage_id, stage_generation, dev_report_id,
                    validation_evidence_id, task_turn_id, semantic_attempt,
                    code_fingerprint, expected_head_sha, expected_base_sha,
                    status, requested_at_ms)
                VALUES ('brain-episode', 'brain-task-1', 'task-1', 1,
                    'local-stage', 1, 'report-brain', 'validation-evidence',
                    'brain-turn', 1, 'fingerprint-old', 'head-old', 'head-old',
                    'REQUESTED', 8)
                """);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('brain-ticket', 'brain-operation', 'EXECUTE_TASK_TURN',
                    'AGENT_TURN', 'TASK_TURN', 'brain-turn', 'TASK_TURN_RESULT',
                    2, 1, 0, 'workspace-1', 'trunk-1', 'task-1', 1,
                    'local-stage', 1, 1, 'fingerprint-old', 'head-old',
                    'head-old', 'REQUESTED', 8)
                """);
    }

    private static void markResultPending(
            JdbcTemplate jdbc, String ticketId, ResultFence fence)
    {
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'FAILED',
                    pending_result_payload = '{}',
                    pending_result_evidence = '{}',
                    pending_result_error = 'BRAIN_BUDGET_EXHAUSTED',
                    pending_result_task_epoch = ?,
                    pending_result_stage_id = ?,
                    pending_result_stage_generation = ?,
                    pending_result_operation_id = ?,
                    pending_result_attempt = ?,
                    pending_result_expected_code_fingerprint = ?,
                    pending_result_expected_head_sha = ?,
                    pending_result_expected_base_sha = ?
                WHERE id = ? AND status = 'REQUESTED'
                """, fence.taskEpoch(), fence.stageId(), fence.stageGeneration(),
                fence.operationId(), fence.attempt(),
                fence.expectedCodeFingerprint(), fence.expectedHeadSha(),
                fence.expectedBaseSha(), ticketId);
    }

    private static void markSucceededResultPending(
            JdbcTemplate jdbc, String ticketId, ResultFence fence)
    {
        markSucceededResultPending(jdbc, ticketId, fence, "{}", null);
    }

    private static void markSucceededResultPending(
            JdbcTemplate jdbc,
            String ticketId,
            ResultFence fence,
            String payload,
            String lastError)
    {
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    infrastructure_attempts = MAX(infrastructure_attempts, 1),
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = ?,
                    pending_result_evidence = '{}',
                    pending_result_error = NULL,
                    pending_result_task_epoch = ?,
                    pending_result_stage_id = ?,
                    pending_result_stage_generation = ?,
                    pending_result_operation_id = ?,
                    pending_result_attempt = ?,
                    pending_result_expected_code_fingerprint = ?,
                    pending_result_expected_head_sha = ?,
                    pending_result_expected_base_sha = ?,
                    last_error = ?
                WHERE id = ? AND status = 'REQUESTED'
                """, payload,
                fence.taskEpoch(), fence.stageId(), fence.stageGeneration(),
                fence.operationId(), fence.attempt(),
                fence.expectedCodeFingerprint(), fence.expectedHeadSha(),
                fence.expectedBaseSha(), lastError, ticketId);
    }

    private static void markAgentResultPending(
            JdbcTemplate jdbc,
            String ticketId,
            ResultFence fence,
            DispatchTicket.Outcome outcome,
            String payload,
            String error)
    {
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    infrastructure_attempts = MAX(infrastructure_attempts, 1),
                    pending_result_outcome = ?, pending_result_payload = ?,
                    pending_result_evidence = '{}', pending_result_error = ?,
                    pending_result_task_epoch = ?, pending_result_stage_id = ?,
                    pending_result_stage_generation = ?,
                    pending_result_operation_id = ?, pending_result_attempt = ?,
                    pending_result_expected_code_fingerprint = ?,
                    pending_result_expected_head_sha = ?,
                    pending_result_expected_base_sha = ?
                WHERE id = ? AND status = 'REQUESTED'
                """, outcome.name(), payload, error, fence.taskEpoch(),
                fence.stageId(), fence.stageGeneration(), fence.operationId(),
                fence.attempt(), fence.expectedCodeFingerprint(),
                fence.expectedHeadSha(), fence.expectedBaseSha(), ticketId);
    }

    private static void persistFinishedAgentExecution(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            String ticketId,
            AgentTurnOwnerResultCodec.OwnerResult result)
            throws Exception
    {
        persistFinishedAgentExecution(jdbc, mapper, ticketId, 1, result);
    }

    private static void persistFinishedAgentExecution(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            String ticketId,
            int infrastructureAttempt,
            AgentTurnOwnerResultCodec.OwnerResult result)
            throws Exception
    {
        String status = switch (result.outcome()) {
            case SUCCEEDED -> "SUCCEEDED";
            case FAILED -> "FAILED";
            case CANCELED -> "CANCELED";
            case INDETERMINATE -> "UNKNOWN";
        };
        var durable = new DispatchTicket.DispatchResult(
                result.fence(), result.outcome(),
                mapper.writeValueAsString(result.payload()), "{}",
                result.payload().error());
        jdbc.update("""
                UPDATE dispatch_ticket
                   SET version = version + 1,
                       infrastructure_attempts = ?
                 WHERE id = ? AND status = 'RESULT_PENDING'
                   AND infrastructure_attempts < ?
                """, infrastructureAttempt, ticketId, infrastructureAttempt);
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    status, started_at_ms, finished_at_ms, raw_result)
                VALUES (?, ?, ?, 'openai', ?, 18, 19, ?)
                ON CONFLICT(ticket_id, infrastructure_attempt) DO UPDATE SET
                    status = excluded.status,
                    finished_at_ms = COALESCE(
                        agent_execution.finished_at_ms,
                        excluded.finished_at_ms),
                    raw_result = excluded.raw_result
                """, ticketId + "-execution-" + infrastructureAttempt,
                ticketId, infrastructureAttempt, status,
                mapper.writeValueAsString(durable));
    }

    private static void seedImplementationRequest(JdbcTemplate jdbc)
    {
        seedImplementationRequest(jdbc, "{}");
    }

    private static void seedImplementationRequest(
            JdbcTemplate jdbc, String launchInput)
    {
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES ('implementation-turn', 'local-stage', 1,
                    'IMPLEMENT_LOCAL_PLAN', 'QUEUED',
                    'implementation-operation', 1, 1,
                    'fingerprint-old', 'head-old', 'head-old', 'API', ?, 4)
                """, launchInput);
        jdbc.update("""
                INSERT INTO local_stage_turn_request(
                    id, command_id, stage_turn_id, task_id,
                    local_development_stage_id, task_epoch, stage_generation,
                    kind, queue_mode, prompt_digest, requested_by,
                    requested_at_ms)
                VALUES ('implementation-request', 'request-implementation',
                    'implementation-turn', 'task-1', 'local-stage', 1, 1,
                    'IMPLEMENTATION', 'IMMEDIATE',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'runtime', 4)
                """);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('implementation-ticket', 'implementation-operation',
                    'EXECUTE_STAGE_TURN', 'AGENT_TURN', 'STAGE_TURN',
                    'implementation-turn', 'STAGE_TURN_RESULT', 2, 1, 1,
                    'workspace-1', 'trunk-1', 'task-1', 1,
                    'local-stage', 1, 1,
                    'fingerprint-old', 'head-old', 'head-old', 'REQUESTED', 4)
                """);
    }
}
