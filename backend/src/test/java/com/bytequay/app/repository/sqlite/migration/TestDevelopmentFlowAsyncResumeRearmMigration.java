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

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageResumeRearmStore;
import com.bytequay.app.developmentflow.task.TaskControlHandoff;
import com.bytequay.app.developmentflow.task.TaskControlMaintainer;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.task.TaskResumeOwner;
import com.bytequay.app.developmentflow.task.persistence.SqliteTaskControlRuntimeStore;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.acceptSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.execute;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertCiPolicy;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertGreenCi;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.migrate;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedApprovedEvidence;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedGreenValidationEvidence;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedLocalDevelopmentTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static org.assertj.core.api.Assertions.assertThat;

class TestDevelopmentFlowAsyncResumeRearmMigration
{
    private static final Instant NOW = Instant.ofEpochMilli(10_000);

    @TempDir
    private Path tempDir;

    @Test
    void asyncCheckpointFamiliesRearmOnceAcrossRestartWithoutCrossTaskEffects()
            throws Exception
    {
        Fixture fixture = fixture();
        Resume validation = acceptResume(
                fixture, "task-1", StageKind.LOCAL_DEVELOPMENT, 1);
        fixture.commands().executeVoid("task-1", () ->
                fixture.rearms().materializeValidation(
                        validation.intent(), NOW.plusMillis(10)));

        Resume brain = acceptResume(
                fixture, "task-2", StageKind.LOCAL_DEVELOPMENT, 20);
        fixture.commands().executeVoid("task-2", () -> {
            SqliteStageResumeRearmStore.BrainResume successor =
                    fixture.rearms().prepareBrainReview(
                            brain.intent(), NOW.plusMillis(30));
            CommandResult<TaskManager.State> requested =
                    fixture.tasks().requestBrainReviewInCommand(
                            new TaskManager.BrainReviewRequestCommand(
                                    successor.commandId(), "test-resume-owner",
                                    brain.intent().taskId(),
                                    brain.intent().taskEpoch(),
                                    brain.intent().currentTaskVersion(),
                                    successor.episodeId(), successor.fence()));
            if (requested.disposition()
                    != CommandResult.Disposition.APPLIED) {
                throw new IllegalStateException("Brain resume was not applied");
            }
            fixture.rearms().completeBrainReview(
                    brain.intent(), successor, NOW.plusMillis(30));
        });

        Resume publish = acceptResume(
                fixture, "task-3", StageKind.LOCAL_DEVELOPMENT, 40);
        fixture.commands().executeVoid("task-3", () ->
                fixture.rearms().recoverPublish(
                        publish.intent(), NOW.plusMillis(50)));

        Resume merge = acceptResume(
                fixture, "task-4", StageKind.REMOTE_DEVELOPMENT, 60);
        fixture.commands().executeVoid("task-4", () ->
                fixture.rearms().recoverMerge(
                        merge.intent(), NOW.plusMillis(70)));

        SqliteStageResumeRearmStore restarted =
                new SqliteStageResumeRearmStore(
                        new JdbcTemplate(fixture.dataSource()));
        assertThat(restarted.pending(StageKind.LOCAL_DEVELOPMENT, 20)).isEmpty();
        assertThat(restarted.pending(StageKind.REMOTE_DEVELOPMENT, 20)).isEmpty();
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM stage_resume_async_successor_v272
                WHERE status = 'ARMED'
                """, Integer.class)).isEqualTo(4);
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM stage_resume_rearm_intent_v257
                WHERE status = 'MATERIALIZED'
                """, Integer.class)).isEqualTo(4);

        assertThat(fixture.jdbc().queryForMap("""
                SELECT operation.status, successor.domain_attempt,
                       lineage.budget_attempt, lineage.consumes_budget
                FROM validation_operation operation
                JOIN stage_resume_async_successor_v272 successor
                  ON successor.owner_id = operation.id
                JOIN stage_resume_budget_lineage_v272 lineage
                  ON lineage.handoff_id = successor.handoff_id
                WHERE operation.task_id = 'task-1'
                  AND operation.status = 'DISPATCHED'
                """))
                .containsEntry("status", "DISPATCHED")
                .containsEntry("domain_attempt", 2)
                .containsEntry("budget_attempt", 1)
                .containsEntry("consumes_budget", 0);
        assertThat(fixture.jdbc().queryForMap("""
                SELECT episode.status, successor.domain_attempt,
                       lineage.budget_attempt, lineage.consumes_budget,
                       turn.status AS turn_status
                FROM brain_review_episode episode
                JOIN task_turn turn ON turn.id = episode.task_turn_id
                JOIN stage_resume_async_successor_v272 successor
                  ON successor.owner_id = episode.id
                JOIN stage_resume_budget_lineage_v272 lineage
                  ON lineage.handoff_id = successor.handoff_id
                WHERE episode.task_id = 'task-2'
                  AND episode.status = 'REQUESTED'
                """))
                .containsEntry("status", "REQUESTED")
                .containsEntry("turn_status", "QUEUED")
                .containsEntry("domain_attempt", 2)
                .containsEntry("budget_attempt", 1)
                .containsEntry("consumes_budget", 0);
        assertThat(fixture.jdbc().queryForMap("""
                SELECT operation.id, operation.operation_id, operation.status,
                       ticket.id AS ticket_id, ticket.status AS ticket_status,
                       authorization.consumed_at_ms, authorization.outcome
                FROM publish_operation operation
                JOIN publish_authorization authorization
                  ON authorization.id = operation.publish_authorization_id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = operation.operation_id
                WHERE operation.task_id = 'task-3'
                """))
                .containsEntry("id", "publish-resume-3")
                .containsEntry("operation_id", "publish-resume-operation-3")
                .containsEntry("status", "DISPATCHED")
                .containsEntry("ticket_id", "publish-resume-ticket-3")
                .containsEntry("ticket_status", "REQUESTED")
                .containsEntry("consumed_at_ms", null)
                .containsEntry("outcome", null);
        assertThat(fixture.jdbc().queryForMap("""
                SELECT operation.id, operation.operation_id, operation.status,
                       ticket.id AS ticket_id, ticket.status AS ticket_status,
                       authorization.status AS authorization_status
                FROM remote_merge_operation operation
                JOIN remote_merge_authorization authorization
                  ON authorization.id = operation.merge_authorization_id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = operation.operation_id
                WHERE operation.task_id = 'task-4'
                """))
                .containsEntry("id", "merge-resume-4")
                .containsEntry("operation_id", "merge-resume-operation-4")
                .containsEntry("status", "REQUESTED")
                .containsEntry("ticket_id", "merge-resume-ticket-4")
                .containsEntry("ticket_status", "REQUESTED")
                .containsEntry("authorization_status", "CONSUMED");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM dispatch_ticket
                WHERE task_id IN ('task-1', 'task-2', 'task-3', 'task-4')
                  AND status = 'REQUESTED'
                """, Integer.class)).isEqualTo(4);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM pragma_foreign_key_check", Integer.class))
                .isZero();
    }

    private Fixture fixture()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("async-resume.db");
        migrate(url, "228");
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedLocalDevelopmentTask(connection, 1);
            seedLocalDevelopmentTask(connection, 2);
            seedGreenValidationEvidence(connection, 2);
            seedLocalDevelopmentTask(connection, 3);
            seedApprovedEvidence(connection, 3);
            seedPublishedRemoteTask(connection, 4);
        }
        migrate(url, "232");
        try (Connection connection = connect(url)) {
            insertRemoteOwner(connection, 4);
        }
        migrate(url, "271");
        try (Connection connection = connect(url)) {
            execute(connection, "DELETE FROM dispatch_ticket");
            seedCanceledValidation(connection, 1);
            seedCanceledBrainReview(connection, 2);
            seedCanceledPublish(connection, 3);
            seedCanceledMerge(connection, 4);
        }
        migrate(url, "272");

        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url + "?foreign_keys=ON&busy_timeout=30000");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        TaskManager tasks = new TaskManager(
                commands, taskStore(jdbc, transactions));
        return new Fixture(
                dataSource, jdbc, transactions, commands, tasks,
                new SqliteStageResumeRearmStore(jdbc));
    }

    private static Resume acceptResume(
            Fixture fixture, String taskId, StageKind kind, long offset)
    {
        SqliteTaskControlRuntimeStore controlsStore =
                new SqliteTaskControlRuntimeStore(
                        fixture.jdbc(), fixture.transactions());
        TaskControlHandoff controls = new TaskControlHandoff(
                fixture.commands(), fixture.tasks());
        TaskResumeOwner owner = new TaskResumeOwner()
        {
            @Override
            public StageKind kind()
            {
                return kind;
            }

            @Override
            public Acceptance accept(Request request)
            {
                return fixture.rearms().accept(request, kind());
            }
        };
        TaskControlMaintainer withoutOwner = new TaskControlMaintainer(
                controlsStore, controls, List.of(), List.of(), ignored -> {});
        TaskControlMaintainer withOwner = new TaskControlMaintainer(
                controlsStore, controls, List.of(owner), List.of(), ignored -> {});
        fixture.tasks().requestPause(new TaskManager.Command(
                "pause-" + taskId, "user", taskId, 1,
                taskVersion(fixture.jdbc(), taskId)));
        withoutOwner.maintain(NOW.plusMillis(offset));
        fixture.tasks().requestResume(new TaskManager.Command(
                "resume-" + taskId, "user", taskId, 1,
                taskVersion(fixture.jdbc(), taskId)));
        withoutOwner.maintain(NOW.plusMillis(offset + 1));
        withOwner.maintain(NOW.plusMillis(offset + 2));
        SqliteStageResumeRearmStore.Intent intent = fixture.rearms()
                .pending(kind, 20).stream()
                .filter(candidate -> candidate.taskId().equals(taskId))
                .findFirst().orElseThrow();
        return new Resume(intent);
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

    private static long taskVersion(JdbcTemplate jdbc, String taskId)
    {
        return jdbc.queryForObject(
                "SELECT aggregate_version FROM tasks WHERE id = ?",
                Long.class, taskId);
    }

    private static void seedCanceledValidation(Connection connection, int task)
            throws Exception
    {
        execute(connection, """
                UPDATE stage SET version = 1, checkpoint = 'VALIDATING'
                WHERE id = 'local-stage-%1$s'
                """.formatted(task));
        execute(connection, """
                INSERT INTO validation_operation(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, dev_report_id, operation_id,
                    semantic_attempt, code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES ('validation-resume-%1$s', 'local-stage-%1$s',
                    'task-%1$s', 1, 1, 'report-%1$s',
                    'validation-resume-operation-%1$s', 1, 'fingerprint-%1$s',
                    'head-%1$s', 'base-%1$s', 'REQUESTED', 100)
                """.formatted(task));
        execute(connection, validationTicket(task));
        execute(connection, """
                UPDATE validation_operation SET status = 'DISPATCHED'
                WHERE id = 'validation-resume-%1$s'
                """.formatted(task));
        execute(connection, """
                UPDATE validation_operation
                SET status = 'CANCELED', completed_at_ms = 101,
                    error_message = 'paused'
                WHERE id = 'validation-resume-%1$s'
                """.formatted(task));
        cancelTicket(connection, "validation-resume-ticket-" + task);
    }

    private static String validationTicket(int task)
    {
        return """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('validation-resume-ticket-%1$s',
                    'validation-resume-operation-%1$s',
                    'VALIDATE_LOCAL_DEVELOPMENT', 'VALIDATION', 'STAGE',
                    'local-stage-%1$s', 'STAGE_VALIDATION_RESULT', 4, 1, 0,
                    'workspace-1', 'trunk-1', 'task-%1$s', 1,
                    'local-stage-%1$s', 1, 1, 'fingerprint-%1$s', 'head-%1$s',
                    'base-%1$s', 'REQUESTED', 100)
                """.formatted(task);
    }

    private static void seedCanceledBrainReview(Connection connection, int task)
            throws Exception
    {
        execute(connection, """
                UPDATE stage SET version = 1, checkpoint = 'BRAIN_REVIEW'
                WHERE id = 'local-stage-%1$s'
                """.formatted(task));
        String launch = """
                {"schemaVersion":1,"transport":"API","provider":"openai",
                 "model":"review-model","workingDirectory":"/tmp/task-%1$s",
                 "prompt":"review","toolEndpoint":{"serverName":"bytequay",
                 "url":"http://127.0.0.1:8080/api/v2/task-turns/brain-resume-turn-%1$s/operations/brain-resume-operation-%1$s/mcp",
                 "ownerKind":"TASK_TURN","ownerId":"brain-resume-turn-%1$s",
                 "operationId":"brain-resume-operation-%1$s",
                 "profile":"TASK_BRAIN_READ_ONLY",
                 "approvalPromptTool":"mcp__bytequay__approval_prompt"}}
                """.formatted(task);
        execute(connection, """
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES ('brain-resume-turn-%1$s', 'task-%1$s',
                    'DEVELOPMENT_BRAIN_REVIEW', 'REQUESTED',
                    'brain-resume-operation-%1$s', 1, 1, 'local-stage-%1$s', 1,
                    'fingerprint-%1$s', 'head-%1$s', 'base-%1$s', 'API',
                    '%2$s', 110)
                """.formatted(task, launch.replace("'", "''")));
        execute(connection, """
                INSERT INTO brain_review_episode(
                    id, task_brain_id, task_id, task_epoch,
                    local_development_stage_id, stage_generation, dev_report_id,
                    validation_evidence_id, task_turn_id, semantic_attempt,
                    code_fingerprint, expected_head_sha, expected_base_sha,
                    status, requested_at_ms)
                VALUES ('brain-resume-%1$s', 'brain-%1$s', 'task-%1$s', 1,
                    'local-stage-%1$s', 1, 'report-%1$s',
                    'validation-evidence-%1$s', 'brain-resume-turn-%1$s', 1,
                    'fingerprint-%1$s', 'head-%1$s', 'base-%1$s',
                    'REQUESTED', 110)
                """.formatted(task));
        execute(connection, brainTicket(task));
        execute(connection, """
                UPDATE task_turn
                SET status = 'CANCELED', started_at_ms = 110,
                    finished_at_ms = 111, error_message = 'paused'
                WHERE id = 'brain-resume-turn-%1$s'
                """.formatted(task));
        execute(connection, """
                UPDATE brain_review_episode
                SET status = 'CANCELED', completed_at_ms = 111,
                    error_message = 'paused'
                WHERE id = 'brain-resume-%1$s'
                """.formatted(task));
        cancelTicket(connection, "brain-resume-ticket-" + task);
    }

    private static String brainTicket(int task)
    {
        return """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('brain-resume-ticket-%1$s',
                    'brain-resume-operation-%1$s', 'EXECUTE_TASK_TURN',
                    'AGENT_TURN', 'TASK_TURN', 'brain-resume-turn-%1$s',
                    'TASK_TURN_RESULT', 2, 1, 0, 'workspace-1', 'trunk-1',
                    'task-%1$s', 1, 'local-stage-%1$s', 1, 1,
                    'fingerprint-%1$s', 'head-%1$s', 'base-%1$s',
                    'REQUESTED', 110)
                """.formatted(task);
    }

    private static void seedCanceledPublish(Connection connection, int task)
            throws Exception
    {
        execute(connection, """
                UPDATE stage SET version = 1, checkpoint = 'PUBLISHING'
                WHERE id = 'local-stage-%1$s'
                """.formatted(task));
        execute(connection, manifestSql(task));
        execute(connection, publishAuthorizationSql(task));
        execute(connection, publishOperationSql(task));
        String[] kinds = {
                "VERIFY_SUBJECT", "RECONCILE_BRANCH_BASE", "PUSH_BRANCH",
                "CREATE_OR_ADOPT_DRAFT_PR", "FETCH_REMOTE_DETAIL",
                "PROVE_REMOTE_HEAD"};
        for (int index = 0; index < kinds.length; index++) {
            execute(connection, """
                    INSERT INTO publish_effect_step(
                        id, publish_operation_id, ordinal, kind,
                        idempotency_key, status, attempt_limit)
                    VALUES ('publish-resume-step-%1$s-%2$s',
                        'publish-resume-%1$s', %2$s, '%3$s',
                        'publish-resume-%1$s:%3$s', 'REQUESTED', 3)
                    """.formatted(task, index + 1, kinds[index]));
        }
        execute(connection, publishTicket(task));
        execute(connection, """
                UPDATE publish_operation SET status = 'DISPATCHED'
                WHERE id = 'publish-resume-%1$s'
                """.formatted(task));
        execute(connection, """
                UPDATE publish_operation
                SET status = 'CANCELED', completed_at_ms = 121,
                    error_message = 'paused'
                WHERE id = 'publish-resume-%1$s'
                """.formatted(task));
        execute(connection, """
                UPDATE publish_authorization
                SET consumed_at_ms = 121, outcome = 'CANCELED'
                WHERE id = 'publish-resume-authorization-%1$s'
                """.formatted(task));
        cancelTicket(connection, "publish-resume-ticket-" + task);
    }

    private static String manifestSql(int task)
    {
        return """
                INSERT INTO promotion_manifest(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, dev_report_id, pr_id, policy_revision_id,
                    revision, code_fingerprint, head_sha, base_sha, route,
                    base_repository_id, head_repository_id,
                    publish_repository_id, branch_name, head_ref, base_branch,
                    worktree_clean, commits_ahead, branch_verified,
                    base_verified, permission_clear, pr_title, pr_body,
                    pr_content_revision, pr_content_digest, created_at_ms)
                VALUES ('publish-resume-manifest-%1$s', 'local-stage-%1$s',
                    'task-%1$s', 1, 1, 'report-%1$s', 'pr-%1$s', 'policy-%1$s',
                    1, 'fingerprint-%1$s', 'head-%1$s', 'base-%1$s', 'DIRECT',
                    'acme/widget', 'acme/widget', 'acme/widget', 'dev/task-%1$s',
                    'dev/task-%1$s', 'main', 1, 1, 1, 1, 1,
                    'Implement feature %1$s', 'Description', 1,
                    'publish-resume-digest-%1$s', 120)
                """.formatted(task);
    }

    private static String publishAuthorizationSql(int task)
    {
        return """
                INSERT INTO publish_authorization(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, manifest_id, dev_report_id,
                    validation_evidence_id, brain_review_episode_id, pr_id,
                    policy_revision_id, code_fingerprint, head_sha, base_sha,
                    route, base_repository_id, head_repository_id,
                    publish_repository_id, branch_name, head_ref, base_branch,
                    pr_content_revision, pr_content_digest, consent_kind,
                    consent_id, actor_id, brain_basis,
                    authorized_operation_id, authorized_attempt, created_at_ms)
                VALUES ('publish-resume-authorization-%1$s',
                    'local-stage-%1$s', 'task-%1$s', 1, 1,
                    'publish-resume-manifest-%1$s', 'report-%1$s',
                    'validation-evidence-%1$s', 'brain-episode-%1$s', 'pr-%1$s',
                    'policy-%1$s', 'fingerprint-%1$s', 'head-%1$s', 'base-%1$s',
                    'DIRECT', 'acme/widget', 'acme/widget', 'acme/widget',
                    'dev/task-%1$s', 'dev/task-%1$s', 'main', 1,
                    'publish-resume-digest-%1$s', 'STANDING_TASK', 'policy-%1$s',
                    'policy', 'APPROVED', 'publish-resume-operation-%1$s', 1, 120)
                """.formatted(task);
    }

    private static String publishOperationSql(int task)
    {
        return """
                INSERT INTO publish_operation(
                    id, publish_authorization_id, local_development_stage_id,
                    task_id, task_epoch, stage_generation, operation_id,
                    semantic_attempt, code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES ('publish-resume-%1$s',
                    'publish-resume-authorization-%1$s', 'local-stage-%1$s',
                    'task-%1$s', 1, 1, 'publish-resume-operation-%1$s', 1,
                    'fingerprint-%1$s', 'head-%1$s', 'base-%1$s',
                    'REQUESTED', 120)
                """.formatted(task);
    }

    private static String publishTicket(int task)
    {
        return """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('publish-resume-ticket-%1$s',
                    'publish-resume-operation-%1$s',
                    'PUBLISH_LOCAL_DEVELOPMENT', 'GITHUB_EFFECT', 'STAGE',
                    'local-stage-%1$s', 'STAGE_PUBLISH_RESULT', 48, 1, 1,
                    'workspace-1', 'trunk-1', 'task-%1$s', 1,
                    'local-stage-%1$s', 1, 1, 'fingerprint-%1$s', 'head-%1$s',
                    'base-%1$s', 'REQUESTED', 120)
                """.formatted(task);
    }

    private static void seedCanceledMerge(Connection connection, int task)
            throws Exception
    {
        insertCiPolicy(connection, task);
        execute(connection, automationPolicySql(task));
        insertSnapshot(connection, task, 1, "head-" + task, "base-" + task,
                "OPEN", "MERGEABLE", "NONE", "UNSUPPORTED", 0, 0, 0, 0);
        acceptSnapshot(connection, task, 1, "head-" + task, "base-" + task);
        insertGreenCi(connection, task, 1, "head-" + task, "base-" + task);
        execute(connection, readinessSql(task));
        execute(connection, mergeAuthorizationSql(task));
        execute(connection, mergeOperationSql(task));
        execute(connection, """
                UPDATE remote_merge_authorization
                SET status = 'CONSUMED', terminal_at_ms = 130
                WHERE id = 'merge-resume-authorization-%1$s'
                """.formatted(task));
        execute(connection, mergeTicket(task));
        authorizeMergeStage(connection, task);
        execute(connection, """
                UPDATE remote_merge_operation
                SET status = 'CANCELED', completed_at_ms = 131,
                    last_error = 'paused'
                WHERE id = 'merge-resume-%1$s'
                """.formatted(task));
        cancelTicket(connection, "merge-resume-ticket-" + task);
    }

    private static String automationPolicySql(int task)
    {
        return """
                INSERT INTO task_automation_policy(
                    id, task_id, revision, source, auto_approve, auto_merge,
                    keep_draft, minimum_write_approvals,
                    max_merge_queue_reenqueues, require_low_risk,
                    require_small_effort, stewardship_exception,
                    created_by, created_at_ms)
                VALUES ('resume-automation-%1$s', 'task-%1$s', 1, 'TASK', 1, 1,
                    0, 0, 0, 0, 0, 0, 'user', 125)
                """.formatted(task);
    }

    private static String readinessSql(int task)
    {
        return """
                INSERT INTO remote_readiness_evidence(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_snapshot_id, ci_evaluation_id,
                    automation_policy_id, head_sha, base_sha, pr_open,
                    non_draft, ci_accepted, write_approval_count,
                    required_write_approval_count, changes_requested_count,
                    unresolved_thread_count, unresolved_comment_count,
                    open_feedback_batch_count, blocking_gate_count,
                    low_risk_required, small_effort_required,
                    low_risk_eligible, small_effort_eligible, mergeability,
                    merge_queue_capability, ready, evidence, observed_at_ms)
                VALUES ('resume-readiness-%1$s', 'remote-stage-%1$s',
                    'task-%1$s', 1, 1, 'snapshot-%1$s-1', 'green-ci-%1$s-1',
                    'resume-automation-%1$s', 'head-%1$s', 'base-%1$s',
                    1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    'MERGEABLE', 'UNSUPPORTED', 1, 'ready', 61)
                """.formatted(task);
    }

    private static String mergeAuthorizationSql(int task)
    {
        return """
                INSERT INTO remote_merge_authorization(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, readiness_evidence_id,
                    automation_policy_id, head_sha, base_sha, authority_kind,
                    status, authorized_at_ms)
                VALUES ('merge-resume-authorization-%1$s',
                    'remote-stage-%1$s', 'task-%1$s', 1, 1,
                    'resume-readiness-%1$s', 'resume-automation-%1$s',
                    'head-%1$s', 'base-%1$s', 'AUTO_MERGE_POLICY',
                    'ACTIVE', 130)
                """.formatted(task);
    }

    private static String mergeOperationSql(int task)
    {
        return """
                INSERT INTO remote_merge_operation(
                    id, merge_authorization_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, operation_id,
                    semantic_attempt, head_sha, base_sha, mode,
                    merge_queue_capability, status, attempt_limit,
                    max_queue_reenqueues, requested_at_ms)
                VALUES ('merge-resume-%1$s',
                    'merge-resume-authorization-%1$s', 'remote-stage-%1$s',
                    'task-%1$s', 1, 1, 'merge-resume-operation-%1$s', 1,
                    'head-%1$s', 'base-%1$s', 'DIRECT', 'UNSUPPORTED',
                    'REQUESTED', 3, 0, 130)
                """.formatted(task);
    }

    private static String mergeTicket(int task)
    {
        return """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES ('merge-resume-ticket-%1$s',
                    'merge-resume-operation-%1$s',
                    'MERGE_REMOTE_PULL_REQUEST', 'MERGE', 'STAGE',
                    'remote-stage-%1$s', 'REMOTE_MERGE_RESULT', 128, 1, 0,
                    'workspace-1', 'trunk-1', 'task-%1$s', 1,
                    'remote-stage-%1$s', 1, 1, 'head-%1$s', 'base-%1$s',
                    'REQUESTED', 130)
                """.formatted(task);
    }

    private static void authorizeMergeStage(Connection connection, int task)
            throws Exception
    {
        connection.setAutoCommit(false);
        try {
            execute(connection, """
                    UPDATE stage SET version = 1, checkpoint = 'READY_TO_MERGE'
                    WHERE id = 'remote-stage-%1$s' AND version = 0
                    """.formatted(task));
            execute(connection, """
                    UPDATE stage SET version = 2, checkpoint = 'MERGING'
                    WHERE id = 'remote-stage-%1$s' AND version = 1
                    """.formatted(task));
            execute(connection, """
                    INSERT INTO stage_transition(
                        id, stage_id, command_id, generation, from_checkpoint,
                        to_checkpoint, stage_version, cause, actor, occurred_at_ms)
                    VALUES ('resume-merge-transition-%1$s', 'remote-stage-%1$s',
                        'resume-merge-command-%1$s', 1, 'READY_TO_MERGE',
                        'MERGING', 2, 'AUTHORIZE_MERGE', 'system', 130)
                    """.formatted(task));
            execute(connection, """
                    INSERT INTO stage_command_receipt(
                        id, stage_id, task_id, command_id, cause, actor,
                        disposition, expected_task_epoch,
                        expected_stage_generation, expected_stage_version,
                        source_checkpoint, subject_task_epoch, subject_stage_id,
                        subject_stage_generation, subject_operation_id,
                        subject_attempt, subject_expected_head_sha,
                        subject_expected_base_sha, proof_id, returned_kind,
                        returned_generation, returned_version,
                        returned_checkpoint, returned_pending_task_epoch,
                        returned_pending_stage_id,
                        returned_pending_stage_generation,
                        returned_pending_operation_id, returned_pending_attempt,
                        returned_pending_head_sha, returned_pending_base_sha,
                        recorded_at_ms)
                    VALUES ('resume-merge-receipt-%1$s', 'remote-stage-%1$s',
                        'task-%1$s', 'resume-merge-command-%1$s',
                        'AUTHORIZE_MERGE', 'system', 'APPLIED', 1, 1, 1,
                        'READY_TO_MERGE', 1, 'remote-stage-%1$s', 1,
                        'merge-resume-operation-%1$s', 1, 'head-%1$s',
                        'base-%1$s', 'merge-resume-authorization-%1$s',
                        'REMOTE_DEVELOPMENT', 1, 2, 'MERGING', 1,
                        'remote-stage-%1$s', 1, 'merge-resume-operation-%1$s',
                        1, 'head-%1$s', 'base-%1$s', 130)
                    """.formatted(task));
            connection.commit();
        }
        catch (Throwable failure) {
            connection.rollback();
            throw failure;
        }
        finally {
            connection.setAutoCommit(true);
        }
    }

    private static void cancelTicket(Connection connection, String ticketId)
            throws Exception
    {
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'CANCELED',
                    cancel_requested_at_ms = 140,
                    delivery_acceptance = 'ACCEPTED',
                    delivery_evidence = 'paused', completed_at_ms = 140
                WHERE id = '%s' AND status = 'REQUESTED'
                """.formatted(ticketId));
    }

    private record Fixture(
            SQLiteDataSource dataSource,
            JdbcTemplate jdbc,
            DataSourceTransactionManager transactions,
            TaskCommandExecutor commands,
            TaskManager tasks,
            SqliteStageResumeRearmStore rearms) {}

    private record Resume(SqliteStageResumeRearmStore.Intent intent) {}
}
