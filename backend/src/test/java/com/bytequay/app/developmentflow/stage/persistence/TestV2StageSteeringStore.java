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

import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.stage.V2StageSteeringControl;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.LocalTurn;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Predecessor;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Request;
import com.bytequay.app.developmentflow.task.TaskControlHandoff;
import com.bytequay.app.developmentflow.task.TaskControlMaintainer;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.task.TaskResumeOwner;
import com.bytequay.app.developmentflow.task.persistence.SqliteTaskControlRuntimeStore;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.testing.MigratedSqliteDatabase;
import com.bytequay.app.testing.V2TaskSeed;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestV2StageSteeringStore
{
    @TempDir
    private Path tempDir;

    @Test
    void appendPersistsBeforeSuccessorAndSurvivesRestart()
    {
        Fixture fixture = fixture("append.db", false);
        Request request = request(
                fixture, "steer-1", "command-1",
                V2StageSteeringControl.Mode.APPEND);
        fixture.commands().executeVoid("task-1", () ->
                fixture.steering().insert(request, List.of()));

        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM stage_turn WHERE stage_id = 'local-stage-1'",
                Integer.class)).isEqualTo(1);
        assertThat(fixture.steering().predecessorQuiesced(request)).isFalse();

        SQLiteDataSource restarted = dataSource(fixture.url());
        SqliteStageSteeringStore recovered = new SqliteStageSteeringStore(
                new JdbcTemplate(restarted));
        assertThat(recovered.find("steer-1")).contains(request);
        assertThat(recovered.findPending(10)).extracting(Request::id)
                .containsExactly("steer-1");
    }

    @Test
    void cliContinuationRequiresTheAcceptedOutputSubjectAndNoLiveConsumer()
    {
        Fixture fixture = fixture("cli-continuation.db", false, true);
        Request request = request(
                fixture, "steer-cli", "command-cli",
                V2StageSteeringControl.Mode.APPEND);
        terminalizeSuccessfulCliPredecessor(fixture.jdbc());

        assertThat(fixture.steering().cliContinuation(
                request, "local-stage-1", 1,
                "fingerprint-2", "head-2", "base-1",
                "codex", "gpt-5.6", "/tmp/task-1"))
                .get()
                .extracting(
                        SqliteStageSteeringStore.CliContinuation::providerSessionId)
                .isEqualTo("stage-session-1");
        assertThat(fixture.steering().cliContinuation(
                request, "local-stage-1", 1,
                "fingerprint-stale", "head-2", "base-1",
                "codex", "gpt-5.6", "/tmp/task-1")).isEmpty();

        fixture.jdbc().update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES ('live-cli-consumer', 'local-stage-1', 1,
                    'USER_STEERING', 'REQUESTED', 'live-cli-operation', 2, 1,
                    'fingerprint-2', 'head-2', 'base-1', 'CLI', '{}', 12)
                """);
        assertThat(fixture.steering().cliContinuation(
                request, "local-stage-1", 1,
                "fingerprint-2", "head-2", "base-1",
                "codex", "gpt-5.6", "/tmp/task-1")).isEmpty();
        assertThat(fixture.steering().cliContinuation(
                "turn-1", "local-stage-1", 1,
                "fingerprint-2", "head-2", "base-1",
                "codex", "gpt-5.6", "/tmp/task-1"))
                .get()
                .extracting(
                        SqliteStageSteeringStore.CliContinuation::sessionReusable)
                .isEqualTo(false);
    }

    @Test
    void cliContinuationRequiresTheFrozenExecutionProvider()
    {
        Fixture fixture = fixture("cli-provider-fence.db", false, true);
        Request request = request(
                fixture, "steer-cli", "command-cli",
                V2StageSteeringControl.Mode.APPEND);
        Request userWait = request(
                fixture, "wait-cli", "wait-command-cli",
                V2StageSteeringControl.Mode.CANCEL_AND_REPLACE);
        terminalizeSuccessfulCliPredecessor(fixture.jdbc(), "claude");

        assertThat(fixture.steering().cliContinuation(
                request, "local-stage-1", 1,
                "fingerprint-2", "head-2", "base-1",
                "codex", "gpt-5.6", "/tmp/task-1")).isEmpty();
        assertThat(fixture.steering().cliContinuation(
                "turn-1", "local-stage-1", 1,
                "fingerprint-2", "head-2", "base-1",
                "codex", "gpt-5.6", "/tmp/task-1"))
                .get()
                .extracting(
                        SqliteStageSteeringStore.CliContinuation::sessionReusable)
                .isEqualTo(false);
        assertThat(fixture.steering().cliContinuation(
                userWait, "local-stage-1", 1,
                "fingerprint-2", "head-2", "base-1",
                "codex", "gpt-5.6", "/tmp/task-1"))
                .get()
                .extracting(
                        SqliteStageSteeringStore.CliContinuation::sessionReusable)
                .isEqualTo(false);
    }

    @Test
    void exactAutomaticContinuationRequiresTheLatestSuccessfulStageTurn()
    {
        Fixture fixture = fixture("cli-exact-continuation.db", false, true);
        terminalizeSuccessfulCliPredecessor(fixture.jdbc());

        assertThat(fixture.steering().cliContinuation(
                "turn-1", "local-stage-1", 1,
                "fingerprint-2", "head-2", "base-1",
                "codex", "gpt-5.6", "/tmp/task-1"))
                .get()
                .extracting(
                        SqliteStageSteeringStore.CliContinuation::providerSessionId)
                .isEqualTo("stage-session-1");

        seedCliSteeringSuccessor(
                fixture.jdbc(), "STAGE_DEVELOPMENT",
                "fingerprint-2", "head-2", false);
        assertThat(fixture.steering().cliContinuation(
                "turn-1", "local-stage-1", 1,
                "fingerprint-2", "head-2", "base-1",
                "codex", "gpt-5.6", "/tmp/task-1"))
                .get()
                .extracting(
                        SqliteStageSteeringStore.CliContinuation::sessionReusable)
                .isEqualTo(false);
    }

    @Test
    void exactContinuationKeepsContextWhenTheProviderHasNoResumeToken()
    {
        Fixture fixture = fixture("cli-exact-no-token.db", false, true);
        terminalizeSuccessfulCliPredecessor(fixture.jdbc());
        fixture.jdbc().update("""
                UPDATE agent_execution SET provider_session_id = NULL
                WHERE id = 'cli-execution'
                """);

        assertThat(fixture.steering().cliContinuation(
                "turn-1", "local-stage-1", 1,
                "fingerprint-2", "head-2", "base-1",
                "codex", "gpt-5.6", "/tmp/task-1"))
                .get()
                .satisfies(continuation -> {
                    assertThat(continuation.launchInput())
                            .contains("implement the change");
                    assertThat(continuation.providerSessionId()).isNull();
                    assertThat(continuation.sessionReusable()).isFalse();
                });
    }

    @Test
    void lateUserWaitKeepsExactHistoryButDoesNotReuseTheSourceSession()
    {
        Fixture fixture = fixture("cli-late-user-wait.db", false, true);
        Request consumer = request(
                fixture, "consumer", "consumer-command",
                V2StageSteeringControl.Mode.APPEND);
        Request lateAnswer = request(
                fixture, "late-answer", "late-answer-command",
                V2StageSteeringControl.Mode.CANCEL_AND_REPLACE);
        fixture.commands().executeVoid("task-1", () ->
                fixture.steering().insert(consumer, List.of()));
        terminalizeSuccessfulCliPredecessor(fixture.jdbc());
        seedCliSteeringSuccessor(
                fixture.jdbc(), "STAGE_DEVELOPMENT",
                "fingerprint-2", "head-2", false);
        fixture.commands().executeVoid("task-1", () ->
                fixture.steering().markAdmitted(
                        consumer.id(), "STAGE_TURN", "steering-turn-1",
                        "steering-operation-1", Instant.ofEpochMilli(11)));

        assertThat(fixture.steering().cliContinuation(
                lateAnswer, "local-stage-1", 1,
                "fingerprint-2", "head-2", "base-1",
                "codex", "gpt-5.6", "/tmp/task-1"))
                .get()
                .satisfies(continuation -> {
                    assertThat(continuation.providerSessionId())
                            .isEqualTo("stage-session-1");
                    assertThat(continuation.sessionReusable()).isFalse();
                });
    }

    @Test
    void queuedAppendContinuesFromTheLatestSuccessfulEarlierAppend()
    {
        Fixture fixture = fixture("cli-append-lineage.db", false, true);
        Request first = request(
                fixture, "z-steer-first", "command-first",
                V2StageSteeringControl.Mode.APPEND);
        Request second = new Request(
                "a-steer-second", "command-second", first.taskId(),
                first.taskEpoch(), first.stageId(), first.stageKind(),
                first.stageGeneration(), first.acceptedStageVersion(),
                first.acceptedCheckpoint(), first.mode(), "Second adjustment",
                "b".repeat(64), first.predecessor(), "PENDING", null, null,
                null, "test", first.requestedAt());
        fixture.commands().executeVoid("task-1", () -> {
            fixture.steering().insert(first, List.of());
            fixture.steering().insert(second, List.of());
        });
        terminalizeSuccessfulCliPredecessor(fixture.jdbc());
        seedCliSteeringSuccessor(
                fixture.jdbc(), "STAGE_DEVELOPMENT",
                "fingerprint-3", "head-3", true);
        fixture.commands().executeVoid("task-1", () ->
                fixture.steering().markAdmitted(
                        first.id(), "STAGE_TURN", "steering-turn-1",
                        "steering-operation-1", Instant.ofEpochMilli(11)));

        assertThat(fixture.steering().cliContinuation(
                second, "local-stage-1", 1,
                "fingerprint-3", "head-3", "base-1",
                "codex", "gpt-5.6", "/tmp/task-1"))
                .get()
                .extracting(
                        SqliteStageSteeringStore.CliContinuation::providerSessionId)
                .isEqualTo("stage-session-2");
    }

    @Test
    void incompatibleLatestAppendDoesNotFallBackToAnOlderSession()
    {
        Fixture fixture = fixture("cli-append-fence.db", false, true);
        Request first = request(
                fixture, "z-steer-first", "command-first",
                V2StageSteeringControl.Mode.APPEND);
        Request second = new Request(
                "a-steer-second", "command-second", first.taskId(),
                first.taskEpoch(), first.stageId(), first.stageKind(),
                first.stageGeneration(), first.acceptedStageVersion(),
                first.acceptedCheckpoint(), first.mode(), "Second adjustment",
                "b".repeat(64), first.predecessor(), "PENDING", null, null,
                null, "test", first.requestedAt());
        fixture.commands().executeVoid("task-1", () -> {
            fixture.steering().insert(first, List.of());
            fixture.steering().insert(second, List.of());
        });
        terminalizeSuccessfulCliPredecessor(fixture.jdbc());
        seedCliSteeringSuccessor(
                fixture.jdbc(), "TASK_BRAIN", "fingerprint-2", "head-2", true);
        fixture.commands().executeVoid("task-1", () ->
                fixture.steering().markAdmitted(
                        first.id(), "STAGE_TURN", "steering-turn-1",
                        "steering-operation-1", Instant.ofEpochMilli(11)));

        assertThat(fixture.steering().cliContinuation(
                second, "local-stage-1", 1,
                "fingerprint-2", "head-2", "base-1",
                "codex", "gpt-5.6", "/tmp/task-1")).isEmpty();
    }

    @Test
    void failedLatestAppendDoesNotReplayAnOlderSuccessfulSession()
    {
        Fixture fixture = fixture("cli-append-failed.db", false, true);
        Request first = request(
                fixture, "z-steer-first", "command-first",
                V2StageSteeringControl.Mode.APPEND);
        Request second = new Request(
                "a-steer-second", "command-second", first.taskId(),
                first.taskEpoch(), first.stageId(), first.stageKind(),
                first.stageGeneration(), first.acceptedStageVersion(),
                first.acceptedCheckpoint(), first.mode(), "Second adjustment",
                "b".repeat(64), first.predecessor(), "PENDING", null, null,
                null, "test", first.requestedAt());
        fixture.commands().executeVoid("task-1", () -> {
            fixture.steering().insert(first, List.of());
            fixture.steering().insert(second, List.of());
        });
        terminalizeSuccessfulCliPredecessor(fixture.jdbc());
        seedCliSteeringSuccessor(
                fixture.jdbc(), "STAGE_DEVELOPMENT",
                "fingerprint-2", "head-2", false);
        fixture.commands().executeVoid("task-1", () ->
                fixture.steering().markAdmitted(
                        first.id(), "STAGE_TURN", "steering-turn-1",
                        "steering-operation-1", Instant.ofEpochMilli(11)));

        assertThat(fixture.steering().cliContinuation(
                second, "local-stage-1", 1,
                "fingerprint-2", "head-2", "base-1",
                "codex", "gpt-5.6", "/tmp/task-1")).isEmpty();
    }

    @Test
    void cancelAndReplaceWaitsForWriterQuiescenceThenAdmitsExactlyOneTurn()
    {
        Fixture fixture = fixture("replace.db", false);
        Request request = request(
                fixture, "steer-2", "command-2",
                V2StageSteeringControl.Mode.CANCEL_AND_REPLACE);
        fixture.commands().executeVoid("task-1", () ->
                fixture.steering().insert(request, List.of()));
        assertThat(fixture.steering().cancellationRequestedFor("operation-1"))
                .isTrue();

        insertWriterLeases(fixture.jdbc());
        terminalizePredecessor(fixture.jdbc());
        assertThat(fixture.steering().predecessorQuiesced(request)).isFalse();
        fixture.jdbc().update("DELETE FROM worktree_leases WHERE operation_id = 'operation-1'");
        fixture.jdbc().update("""
                UPDATE capacity_lease SET released_at_ms = 9, release_reason = 'done'
                WHERE operation_id = 'operation-1'
                """);
        assertThat(fixture.steering().predecessorQuiesced(request)).isTrue();

        fixture.commands().executeVoid("task-1", () -> {
            fixture.local().clearImplementationTurnInCommand(
                    new StageManager.ResultCommand(
                            "clear-old", "test", "task-1", fence("1")),
                    "request-1");
            LocalTurn turn = replacementTurn();
            fixture.steering().insertLocalTurn(turn);
            fixture.local().requestImplementationInCommand(
                    new StageManager.Command(
                            "arm-replacement", "test", "task-1", 1,
                            "local-stage-1", 1, 2),
                    turn.fence(), turn.localRequestId());
            fixture.steering().markAdmitted(
                    request.id(), "STAGE_TURN", turn.turnId(),
                    turn.operationId(), Instant.ofEpochMilli(10));
        });

        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM stage_steering_request_v257
                WHERE id = 'steer-2' AND status = 'ADMITTED'
                  AND successor_owner_id = 'replacement-turn'
                """, Integer.class)).isEqualTo(1);
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM stage_turn
                WHERE stage_id = 'local-stage-1' AND purpose = 'USER_STEERING'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void malformedResultPendingIsFencedBeforeOneExactReplacementIsArmed()
    {
        Fixture fixture = fixture("malformed-result.db", false);
        Request request = request(
                fixture, "steer-malformed", "command-malformed",
                V2StageSteeringControl.Mode.CANCEL_AND_REPLACE);
        fixture.commands().executeVoid("task-1", () ->
                fixture.steering().insert(request, List.of()));
        markMalformedResultPending(fixture.jdbc());

        assertThat(fixture.steering().predecessorQuiesced(request)).isFalse();
        assertThat(fixture.steering().malformedLocalResultPendingReady(
                request, fence("1"))).isTrue();

        LocalTurn replacement = malformedReplacementTurn();
        fixture.commands().executeVoid("task-1", () -> {
            fixture.steering().insertMalformedLocalResultReplacementTurn(
                    replacement, request);
            fixture.local().replaceImplementationTurnInCommand(
                    new StageManager.ResultCommand(
                            "replace-malformed", "test", "task-1", fence("1")),
                    replacement.fence(), replacement.localRequestId());
            fixture.steering().markAdmitted(
                    request.id(), "STAGE_TURN", replacement.turnId(),
                    replacement.operationId(), Instant.ofEpochMilli(10));
        });

        assertThat(fixture.jdbc().queryForMap("""
                SELECT previous.status AS previous_status,
                       replacement.status AS replacement_status,
                       steering.status AS steering_status,
                       local_request.predecessor_turn_id
                FROM stage_turn previous
                JOIN stage_turn replacement ON replacement.id = 'replacement-turn'
                JOIN local_stage_turn_request local_request
                  ON local_request.stage_turn_id = replacement.id
                JOIN stage_steering_request_v257 steering
                  ON steering.id = 'steer-malformed'
                WHERE previous.id = 'turn-1'
                """))
                .containsEntry("previous_status", "SUPERSEDED")
                .containsEntry("replacement_status", "QUEUED")
                .containsEntry("steering_status", "ADMITTED")
                .containsEntry("predecessor_turn_id", "turn-1");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT returned_pending_operation_id
                FROM local_stage_command_receipt
                WHERE command_id = 'replace-malformed'
                """, String.class)).isEqualTo("replacement-operation");
        assertThat(fixture.steering().findByCommand("command-malformed"))
                .get()
                .extracting(Request::successorOwnerId)
                .isEqualTo("replacement-turn");
        assertThat(fixture.steering().cancellationRequestedFor("operation-1"))
                .as("late delivery remains fenced after admission")
                .isTrue();
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM stage_turn
                WHERE stage_id = 'local-stage-1' AND attempt = 2
                """, Integer.class)).isOne();
    }

    @Test
    void malformedResultReplacementRejectsEveryLiveExecutionAuthority()
    {
        Fixture fixture = fixture("malformed-result-live-authority.db", false);
        Request request = request(
                fixture, "steer-malformed", "command-malformed",
                V2StageSteeringControl.Mode.CANCEL_AND_REPLACE);
        fixture.commands().executeVoid("task-1", () ->
                fixture.steering().insert(request, List.of()));
        markMalformedResultPending(fixture.jdbc());
        assertThat(fixture.steering().malformedLocalResultPendingReady(
                request, fence("1"))).isTrue();

        fixture.jdbc().update("""
                INSERT INTO dispatch_delivery_claim(
                    ticket_id, ticket_version, claim_owner, claimed_at_ms,
                    heartbeat_at_ms, expires_at_ms)
                SELECT id, version, 'delivery-worker', 8, 8, 100
                FROM dispatch_ticket WHERE id = 'ticket-1'
                """);
        assertThat(fixture.steering().malformedLocalResultPendingReady(
                request, fence("1"))).isFalse();
        fixture.jdbc().update("""
                DELETE FROM dispatch_delivery_claim WHERE ticket_id = 'ticket-1'
                """);

        fixture.jdbc().update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, status, started_at_ms)
                VALUES ('live-execution', 'ticket-1', 2, 'RUNNING', 8)
                """);
        assertThat(fixture.steering().malformedLocalResultPendingReady(
                request, fence("1"))).isFalse();
        fixture.jdbc().update("""
                DELETE FROM agent_execution WHERE id = 'live-execution'
                """);

        fixture.jdbc().update("""
                INSERT INTO capacity_lease(
                    id, ticket_id, operation_id, workflow_source, lane_mask,
                    trunk_control, exclusive_task, writer_required, workspace_id,
                    trunk_id, task_id, task_epoch, holder, fencing_token,
                    acquired_at_ms, heartbeat_at_ms, expires_at_ms)
                VALUES ('live-capacity', 'ticket-1', 'operation-1', 'V2', 2,
                    0, 1, 1, 'workspace-1', 'trunk-1', 'task-1', 1,
                    'holder', 1, 8, 8, 100)
                """);
        assertThat(fixture.steering().malformedLocalResultPendingReady(
                request, fence("1"))).isFalse();
        fixture.jdbc().update("""
                INSERT INTO worktree_leases(
                    worktree_path, task_id, agent_kind, acquired_at_ms,
                    expires_at_ms, workflow_version, operation_id, task_epoch,
                    fencing_token, lease_owner)
                VALUES ('/tmp/task-1', 'task-1', 'CLI_AGENT', 8, 100,
                    'V2', 'operation-1', 1, 1, 'holder')
                """);
        fixture.jdbc().update("""
                UPDATE capacity_lease SET released_at_ms = 9,
                    release_reason = 'done' WHERE id = 'live-capacity'
                """);
        assertThat(fixture.steering().malformedLocalResultPendingReady(
                request, fence("1"))).isFalse();
        fixture.jdbc().update("""
                DELETE FROM worktree_leases WHERE worktree_path = '/tmp/task-1'
                """);

        assertThat(fixture.steering().malformedLocalResultPendingReady(
                request, fence("1"))).isTrue();
    }

    @Test
    void staleFenceAndDuplicateCommandCannotAffectSiblingTask()
    {
        Fixture fixture = fixture("sibling.db", true);
        Request request = request(
                fixture, "steer-3", "same-command",
                V2StageSteeringControl.Mode.APPEND);
        fixture.commands().executeVoid("task-1", () ->
                fixture.steering().insert(request, List.of()));

        Request duplicateCommand = new Request(
                "steer-other", "same-command", request.taskId(), request.taskEpoch(),
                request.stageId(), request.stageKind(), request.stageGeneration(),
                request.acceptedStageVersion(), request.acceptedCheckpoint(),
                request.mode(), "different", "b".repeat(64),
                request.predecessor(), "PENDING", null, null, null,
                "test", Instant.ofEpochMilli(7));
        assertThatThrownBy(() -> fixture.commands().executeVoid("task-1", () ->
                fixture.steering().insert(duplicateCommand, List.of())))
                .isInstanceOf(DataAccessException.class);

        Request stale = new Request(
                "stale", "stale-command", request.taskId(), request.taskEpoch(),
                request.stageId(), request.stageKind(), 2,
                request.acceptedStageVersion(), request.acceptedCheckpoint(),
                request.mode(), request.body(), "c".repeat(64), null,
                "PENDING", null, null, null, "test", Instant.ofEpochMilli(8));
        assertThatThrownBy(() -> fixture.commands().executeVoid("task-1", () ->
                fixture.steering().insert(stale, List.of())))
                .isInstanceOf(DataAccessException.class);

        assertThat(fixture.jdbc().queryForObject("""
                SELECT status FROM dispatch_ticket WHERE id = 'ticket-2'
                """, String.class)).isEqualTo("REQUESTED");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM stage_steering_request_v257
                WHERE task_id = 'task-2'
                """, Integer.class)).isZero();
    }

    @Test
    void localResumeRearmsOnlyTheUniqueHighestCanceledSemanticAttempt()
    {
        Fixture fixture = fixture("local-resume.db", false);
        terminalizePredecessor(fixture.jdbc());
        fixture.commands().executeVoid("task-1", () ->
                fixture.local().clearImplementationTurnInCommand(
                        new StageManager.ResultCommand(
                                "clear-attempt-1", "test", "task-1", fence("1")),
                        "request-1"));
        seedSecondImplementation(fixture.jdbc());
        long stageVersion = fixture.jdbc().queryForObject(
                "SELECT version FROM stage WHERE id = 'local-stage-1'",
                Long.class);
        fixture.commands().executeVoid("task-1", () ->
                fixture.local().requestImplementationInCommand(
                        new StageManager.Command(
                                "arm-attempt-2", "test", "task-1", 1,
                                "local-stage-1", 1, stageVersion),
                        historyFence(), "request-history"));
        terminalizeHistory(fixture.jdbc());
        fixture.commands().executeVoid("task-1", () ->
                fixture.local().clearImplementationTurnInCommand(
                        new StageManager.ResultCommand(
                                "clear-attempt-2", "test", "task-1",
                                historyFence()),
                        "request-history"));

        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(fixture.dataSource());
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        TaskManager tasks = new TaskManager(
                commands, taskStore(fixture.jdbc(), transactions));
        SqliteTaskControlRuntimeStore controlStore =
                new SqliteTaskControlRuntimeStore(fixture.jdbc(), transactions);
        TaskControlHandoff controls = new TaskControlHandoff(commands, tasks);
        SqliteStageResumeRearmStore rearms =
                new SqliteStageResumeRearmStore(fixture.jdbc());
        TaskResumeOwner owner = new TaskResumeOwner()
        {
            @Override
            public StageKind kind()
            {
                return StageKind.LOCAL_DEVELOPMENT;
            }

            @Override
            public Acceptance accept(TaskResumeOwner.Request request)
            {
                return rearms.accept(request, kind());
            }
        };
        TaskControlMaintainer withoutOwner = new TaskControlMaintainer(
                controlStore, controls, List.of(), List.of(), ignored -> {});
        TaskControlMaintainer withOwner = new TaskControlMaintainer(
                controlStore, controls, List.of(owner), List.of(), ignored -> {});
        Instant now = Instant.ofEpochMilli(100);

        tasks.requestPause(new TaskManager.Command(
                "pause-local", "user", "task-1", 1,
                taskVersion(fixture.jdbc())));
        withoutOwner.maintain(now);
        tasks.requestResume(new TaskManager.Command(
                "resume-local", "user", "task-1", 1,
                taskVersion(fixture.jdbc())));
        withoutOwner.maintain(now.plusMillis(1));
        withOwner.maintain(now.plusMillis(2));
        SqliteStageResumeRearmStore.Intent intent = rearms
                .pending(StageKind.LOCAL_DEVELOPMENT, 10).getFirst();
        commands.executeVoid("task-1", () ->
                rearms.materializeLocalTurn(intent, now.plusMillis(3)));

        assertThat(fixture.jdbc().queryForMap("""
                SELECT intent.status, successor.status AS successor_status,
                       successor.semantic_attempt, successor.owner_kind
                FROM stage_resume_rearm_intent_v257 intent
                JOIN stage_resume_rearm_successor_v257 successor
                  ON successor.handoff_id = intent.handoff_id
                WHERE intent.task_id = 'task-1'
                """))
                .containsEntry("status", "MATERIALIZED")
                .containsEntry("successor_status", "ARMED")
                .containsEntry("semantic_attempt", 3)
                .containsEntry("owner_kind", "STAGE_TURN");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM stage_turn
                WHERE stage_id = 'local-stage-1'
                  AND purpose = 'IMPLEMENT_LOCAL_PLAN'
                  AND attempt = 3 AND status = 'QUEUED'
                """, Integer.class)).isOne();
        assertThat(fixture.jdbc().queryForMap("""
                SELECT json_extract(launch_input, '$.prompt') AS prompt,
                       json_type(launch_input, '$.resumeSessionId') AS resume_type,
                       json_type(launch_input, '$.fallbackPrompt') AS fallback_type
                FROM stage_turn
                WHERE stage_id = 'local-stage-1'
                  AND purpose = 'IMPLEMENT_LOCAL_PLAN' AND attempt = 3
                """))
                .containsEntry("prompt", "complete durable fallback")
                .containsEntry("resume_type", null)
                .containsEntry("fallback_type", null);
    }

    private Fixture fixture(String name, boolean sibling)
    {
        return fixture(name, sibling, false);
    }

    private Fixture fixture(String name, boolean sibling, boolean cli)
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(name)
                + "?foreign_keys=ON&busy_timeout=30000";
        MigratedSqliteDatabase.migrate(url);
        SQLiteDataSource dataSource = dataSource(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedOwner(jdbc, "1", 1);
        if (sibling) {
            seedOwner(jdbc, "2", 2);
        }
        Flyway.configure().dataSource(dataSource).load().migrate();
        seedImplementation(jdbc, "1", cli);
        if (sibling) {
            seedImplementation(jdbc, "2", cli);
        }
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        V2StageStore stages = new V2StageStore(jdbc);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stages, stages);
        arm(commands, local, "1");
        if (sibling) {
            arm(commands, local, "2");
        }
        return new Fixture(
                url, dataSource, jdbc, commands, local,
                new SqliteStageSteeringStore(jdbc));
    }

    private static Request request(
            Fixture fixture, String id, String commandId,
            V2StageSteeringControl.Mode mode)
    {
        Predecessor predecessor = fixture.steering()
                .findPredecessor(fence("1")).orElseThrow();
        return new Request(
                id, commandId, "task-1", 1, "local-stage-1",
                StageKind.LOCAL_DEVELOPMENT, 1, 1,
                StageCheckpoint.IMPLEMENTING, mode, "Please adjust this",
                "a".repeat(64), predecessor, "PENDING", null, null, null,
                "test", Instant.ofEpochMilli(6));
    }

    private static void arm(
            TaskCommandExecutor commands, LocalDevelopmentStageManager local,
            String suffix)
    {
        commands.executeVoid("task-" + suffix, () ->
                local.requestImplementationInCommand(
                        new StageManager.Command(
                                "arm-" + suffix, "test", "task-" + suffix,
                                1, "local-stage-" + suffix, 1, 0),
                        fence(suffix), "request-" + suffix));
    }

    private static ResultFence fence(String suffix)
    {
        return new ResultFence(
                1, "local-stage-" + suffix, 1, "operation-" + suffix, 1,
                "fingerprint-" + suffix, "base-" + suffix, "base-" + suffix);
    }

    private static ResultFence historyFence()
    {
        return new ResultFence(
                1, "local-stage-1", 1, "operation-history", 2,
                "fingerprint-1", "base-1", "base-1");
    }

    private static LocalTurn replacementTurn()
    {
        return new LocalTurn(
                "replacement-request", "persist-replacement", "replacement-turn",
                "replacement-operation", "replacement-ticket", "workspace-1",
                "trunk-1", "task-1", 1, "local-stage-1", 1,
                "fingerprint-1", "base-1", "base-1", "API", 2, "{}",
                "d".repeat(64), "test", Instant.ofEpochMilli(10));
    }

    private static LocalTurn malformedReplacementTurn()
    {
        return new LocalTurn(
                "replacement-request", "persist-replacement", "replacement-turn",
                "replacement-operation", "replacement-ticket", "workspace-1",
                "trunk-1", "task-1", 1, "local-stage-1", 1, 2,
                "fingerprint-1", "base-1", "base-1", "API", 2, "{}",
                "d".repeat(64), "test", Instant.ofEpochMilli(10));
    }

    private static void terminalizePredecessor(JdbcTemplate jdbc)
    {
        jdbc.update("""
                UPDATE stage_turn SET status = 'CANCELED', started_at_ms = 8,
                    finished_at_ms = 8, error_message = 'replaced'
                WHERE id = 'turn-1'
                """);
        jdbc.update("""
                UPDATE dispatch_ticket SET version = version + 1,
                    status = 'CANCELED', cancel_requested_at_ms = 8,
                    delivery_acceptance = 'SUPERSEDED',
                    delivery_evidence = 'replaced', completed_at_ms = 8
                WHERE id = 'ticket-1'
                """);
    }

    private static void markMalformedResultPending(JdbcTemplate jdbc)
    {
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    infrastructure_attempts = 1,
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = 'malformed-owner-payload',
                    pending_result_evidence = 'provider-finished',
                    pending_result_task_epoch = 1,
                    pending_result_stage_id = 'local-stage-1',
                    pending_result_stage_generation = 1,
                    pending_result_operation_id = 'operation-1',
                    pending_result_attempt = 1,
                    pending_result_expected_code_fingerprint = 'fingerprint-1',
                    pending_result_expected_head_sha = 'base-1',
                    pending_result_expected_base_sha = 'base-1',
                    last_error = ?
                WHERE id = 'ticket-1'
                """, DispatchTicket.resultProtocolFailure(
                "strict Local result could not be decoded"));
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, status,
                    started_at_ms, finished_at_ms, raw_result)
                VALUES ('malformed-execution', 'ticket-1', 1, 'SUCCEEDED',
                    8, 9, 'malformed-owner-payload')
                """);
    }

    private static void terminalizeHistory(JdbcTemplate jdbc)
    {
        jdbc.update("""
                UPDATE stage_turn SET status = 'CANCELED', started_at_ms = 18,
                    finished_at_ms = 18, error_message = 'paused'
                WHERE id = 'turn-history'
                """);
        jdbc.update("""
                UPDATE dispatch_ticket SET version = version + 1,
                    status = 'CANCELED', cancel_requested_at_ms = 18,
                    delivery_acceptance = 'SUPERSEDED',
                    delivery_evidence = 'paused', completed_at_ms = 18
                WHERE id = 'ticket-history'
                """);
    }

    private static void seedSecondImplementation(JdbcTemplate jdbc)
    {
        String launch = """
                {"schemaVersion":1,"transport":"CLI","provider":"codex",
                 "model":"model","workingDirectory":"/tmp/task-1",
                 "prompt":"incremental prompt","resumeSessionId":"old-session",
                 "fallbackPrompt":"complete durable fallback",
                 "toolEndpoint":{"serverName":"bytequay",
                 "url":"http://127.0.0.1:53123/api/v2/stage-turns/turn-history/operations/operation-history/mcp",
                 "ownerKind":"STAGE_TURN","ownerId":"turn-history",
                 "operationId":"operation-history","profile":"STAGE_DEVELOPMENT",
                 "approvalPromptTool":"mcp__bytequay__approval_prompt"}}
                """;
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status, operation_id,
                    attempt, task_epoch, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, delivery_lane,
                    launch_input, requested_at_ms)
                VALUES ('turn-history', 'local-stage-1', 1,
                    'IMPLEMENT_LOCAL_PLAN', 'QUEUED', 'operation-history',
                    2, 1, 'fingerprint-1', 'base-1', 'base-1', 'CLI', ?, 17)
                """, launch);
        jdbc.update("""
                INSERT INTO local_stage_turn_request(
                    id, command_id, stage_turn_id, task_id,
                    local_development_stage_id, task_epoch, stage_generation,
                    kind, queue_mode, prompt_digest, requested_by, requested_at_ms)
                VALUES ('request-history', 'persist-history', 'turn-history',
                    'task-1', 'local-stage-1', 1, 1, 'IMPLEMENTATION',
                    'IMMEDIATE', ?, 'test', 17)
                """, "f".repeat(64));
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family, owner_kind,
                    owner_id, callback_route, lane_mask, exclusive_task,
                    writer_required, workspace_id, trunk_id, task_id, task_epoch,
                    stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('ticket-history', 'operation-history',
                    'EXECUTE_STAGE_TURN', 'AGENT_TURN', 'STAGE_TURN',
                    'turn-history', 'STAGE_TURN_RESULT', 1, 1, 1,
                    'workspace-1', 'trunk-1', 'task-1', 1, 'local-stage-1', 1,
                    2, 'fingerprint-1', 'base-1', 'base-1', 'REQUESTED', 17)
                """);
    }

    private static void terminalizeSuccessfulCliPredecessor(JdbcTemplate jdbc)
    {
        terminalizeSuccessfulCliPredecessor(jdbc, "codex");
    }

    private static void terminalizeSuccessfulCliPredecessor(
            JdbcTemplate jdbc, String executionProvider)
    {
        jdbc.update("""
                UPDATE stage_turn SET status = 'SUCCEEDED', started_at_ms = 9,
                    finished_at_ms = 10 WHERE id = 'turn-1'
                """);
        jdbc.update("""
                UPDATE dispatch_ticket SET version = version + 1,
                    status = 'SUCCEEDED', delivery_acceptance = 'ACCEPTED',
                    delivery_evidence = 'accepted', completed_at_ms = 10
                WHERE id = 'ticket-1'
                """);
        ObjectMapper json = new ObjectMapper();
        var payload = json.createObjectNode();
        payload.put("providerCumulativeInputTokens", 100);
        payload.put("providerCumulativeOutputTokens", 40);
        payload.putObject("outputCodeSubject")
                .put("codeFingerprint", "fingerprint-2")
                .put("headSha", "head-2")
                .put("baseSha", "base-1");
        String rawResult = json.createObjectNode()
                .put("payloadJson", payload.toString())
                .toString();
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    provider_session_id, status, started_at_ms, finished_at_ms,
                    raw_result)
                VALUES ('cli-execution', 'ticket-1', 1, ?,
                    'stage-session-1', 'SUCCEEDED', 9, 10, ?)
                """, executionProvider, rawResult);
    }

    private static void seedCliSteeringSuccessor(
            JdbcTemplate jdbc,
            String profile,
            String outputFingerprint,
            String outputHead,
            boolean successful)
    {
        ObjectMapper json = new ObjectMapper();
        var launch = json.createObjectNode();
        launch.put("schemaVersion", 1);
        launch.put("transport", "CLI");
        launch.put("provider", "codex");
        launch.put("model", "gpt-5.6");
        launch.put("workingDirectory", "/tmp/task-1");
        launch.putObject("toolEndpoint").put("profile", profile);
        launch.put("prompt", "first adjustment");
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status, operation_id,
                    attempt, task_epoch, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, delivery_lane,
                    launch_input, requested_at_ms)
                VALUES ('steering-turn-1', 'local-stage-1', 1,
                    'USER_STEERING', 'QUEUED', 'steering-operation-1',
                    1, 1, 'fingerprint-1', 'base-1', 'base-1', 'CLI',
                    ?, 8)
                """, launch.toString());
        jdbc.update("""
                INSERT INTO local_stage_turn_request(
                    id, command_id, stage_turn_id, task_id,
                    local_development_stage_id, task_epoch, stage_generation,
                    kind, queue_mode, prompt_digest, requested_by,
                    requested_at_ms)
                VALUES ('steering-local-request-1', 'steering-command-1',
                    'steering-turn-1', 'task-1', 'local-stage-1', 1, 1,
                    'STEERING', 'IMMEDIATE', ?, 'test', 8)
                """, "c".repeat(64));
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family, owner_kind,
                    owner_id, callback_route, lane_mask, exclusive_task,
                    writer_required, workspace_id, trunk_id, task_id, task_epoch,
                    stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('steering-ticket-1', 'steering-operation-1',
                    'EXECUTE_STAGE_TURN', 'AGENT_TURN', 'STAGE_TURN',
                    'steering-turn-1', 'STAGE_TURN_RESULT', 1, 1, 1,
                    'workspace-1', 'trunk-1', 'task-1', 1,
                    'local-stage-1', 1, 1, 'fingerprint-1', 'base-1',
                    'base-1', 'REQUESTED', 8)
                """);
        if (!successful) {
            jdbc.update("""
                    UPDATE stage_turn SET status = 'FAILED', started_at_ms = 9,
                        finished_at_ms = 10, error_message = 'provider failed'
                    WHERE id = 'steering-turn-1'
                    """);
            jdbc.update("""
                    UPDATE dispatch_ticket SET version = version + 1,
                        status = 'FAILED', delivery_acceptance = 'ACCEPTED',
                        delivery_evidence = 'failed', completed_at_ms = 10,
                        last_error = 'provider failed'
                    WHERE id = 'steering-ticket-1'
                    """);
            return;
        }
        jdbc.update("""
                UPDATE stage_turn SET status = 'SUCCEEDED', started_at_ms = 9,
                    finished_at_ms = 10 WHERE id = 'steering-turn-1'
                """);
        jdbc.update("""
                UPDATE dispatch_ticket SET version = version + 1,
                    status = 'SUCCEEDED', delivery_acceptance = 'ACCEPTED',
                    delivery_evidence = 'accepted', completed_at_ms = 10
                WHERE id = 'steering-ticket-1'
                """);
        var payload = json.createObjectNode();
        payload.put("providerCumulativeInputTokens", 100);
        payload.put("providerCumulativeOutputTokens", 40);
        payload.putObject("outputCodeSubject")
                .put("codeFingerprint", outputFingerprint)
                .put("headSha", outputHead)
                .put("baseSha", "base-1");
        String rawResult = json.createObjectNode()
                .put("payloadJson", payload.toString())
                .toString();
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    provider_session_id, status, started_at_ms, finished_at_ms,
                    raw_result)
                VALUES ('steering-execution-1', 'steering-ticket-1', 1,
                    'codex', 'stage-session-2', 'SUCCEEDED', 9, 10, ?)
                """, rawResult);
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

    private static long taskVersion(JdbcTemplate jdbc)
    {
        return jdbc.queryForObject(
                "SELECT aggregate_version FROM tasks WHERE id = 'task-1'",
                Long.class);
    }

    private static void insertWriterLeases(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO capacity_lease(
                    id, ticket_id, operation_id, workflow_source, lane_mask,
                    trunk_control, exclusive_task, writer_required, workspace_id,
                    trunk_id, task_id, task_epoch, holder, fencing_token,
                    acquired_at_ms, heartbeat_at_ms, expires_at_ms)
                VALUES ('capacity-1', 'ticket-1', 'operation-1', 'V2', 2,
                    0, 1, 1, 'workspace-1', 'trunk-1', 'task-1', 1,
                    'holder', 1, 7, 7, 100)
                """);
        jdbc.update("""
                INSERT INTO worktree_leases(
                    worktree_path, task_id, agent_kind, acquired_at_ms,
                    expires_at_ms, workflow_version, operation_id, task_epoch,
                    fencing_token, lease_owner)
                VALUES ('/tmp/task-1', 'task-1', 'CLI_AGENT', 7, 100,
                    'V2', 'operation-1', 1, 1, 'holder')
                """);
    }

    private static void seedOwner(JdbcTemplate jdbc, String suffix, int sequence)
    {
        jdbc.update("""
                INSERT OR IGNORE INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'Workspace', '', 0, 1, 1)
                """);
        jdbc.update("""
                INSERT OR IGNORE INTO threads(
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
                SELECT 'policy-1', 'trunk-1', 1, 'TRUNK', 'test', 1
                WHERE NOT EXISTS (
                    SELECT 1 FROM task_policy_revision WHERE id = 'policy-1')
                """);
        String assignmentId = "assignment-" + suffix;
        V2TaskSeed.insertAuthorized(jdbc, assignmentId, seed -> seed.update("""
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms, creation_authorization_id)
                VALUES (?, 'trunk-1', 'NEW_FROM_TRUNK', ?, 'seed', 'build',
                    'test', 2, ?)
                """, assignmentId, "base-" + suffix,
                "authorization-" + assignmentId));
        String taskId = "task-" + suffix;
        String taskInsert = """
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, epoch, aggregate_version, lifecycle_state,
                    assignment_id, policy_revision_id, creation_receipt_id,
                    name, task_type, opening_prompt, origin)
                VALUES (?, 'trunk-1', ?, 'IDLE', 'PLANNING', 2,
                    'V2', 1, 0, 'PROVISIONING', ?, 'policy-1', ?, ?,
                    'DEVELOP', 'build', 'user')
                """;
        V2TaskSeed.insertCreated(jdbc, taskId, seed -> seed.update(
                taskInsert, taskId, sequence, assignmentId,
                "creation-receipt-" + taskId, "Test task " + assignmentId));
        seedCode(jdbc, suffix);
        jdbc.update("""
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                VALUES (?, ?, 'LOCAL_DEVELOPMENT', 1, 0, 'IMPLEMENTING', 3)
                """, "local-stage-" + suffix, "task-" + suffix);
        jdbc.update("""
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES (?, ?, 1)
                """, "task-" + suffix, "local-stage-" + suffix);
        jdbc.update("""
                UPDATE tasks SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                WHERE id = ?
                """, "task-" + suffix);
        jdbc.update("""
                INSERT INTO local_development_stage(
                    stage_id, task_id, generation, opened_for_epoch)
                VALUES (?, ?, 1, 1)
                """, "local-stage-" + suffix, "task-" + suffix);
    }

    private static void seedCode(JdbcTemplate jdbc, String suffix)
    {
        String taskId = "task-" + suffix;
        V2TaskSeed.completeProvisioning(
                jdbc, taskId, "base-" + suffix, "base-" + suffix,
                "fingerprint-" + suffix, "created", 3);
    }

    private static void seedImplementation(
            JdbcTemplate jdbc, String suffix, boolean cli)
    {
        String deliveryLane = cli ? "CLI" : "API";
        int laneMask = cli ? 1 : 2;
        String launchInput = cli
                ? """
                    {"schemaVersion":1,"transport":"CLI","provider":"codex",
                     "model":"gpt-5.6","workingDirectory":"/tmp/task-%s",
                     "toolEndpoint":{"profile":"STAGE_DEVELOPMENT"},
                     "prompt":"implement the change"}
                    """.formatted(suffix)
                : "{}";
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status, operation_id,
                    attempt, task_epoch, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, delivery_lane,
                    launch_input, requested_at_ms)
                VALUES (?, ?, 1, 'IMPLEMENT_LOCAL_PLAN', 'QUEUED', ?, 1, 1,
                    ?, ?, ?, ?, ?, 4)
                """, "turn-" + suffix, "local-stage-" + suffix,
                "operation-" + suffix, "fingerprint-" + suffix,
                "base-" + suffix, "base-" + suffix, deliveryLane, launchInput);
        jdbc.update("""
                INSERT INTO local_stage_turn_request(
                    id, command_id, stage_turn_id, task_id,
                    local_development_stage_id, task_epoch, stage_generation,
                    kind, queue_mode, prompt_digest, requested_by, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, 1, 1, 'IMPLEMENTATION', 'IMMEDIATE',
                    ?, 'test', 4)
                """, "request-" + suffix, "persist-" + suffix,
                "turn-" + suffix, "task-" + suffix, "local-stage-" + suffix,
                "e".repeat(64));
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family, owner_kind,
                    owner_id, callback_route, lane_mask, exclusive_task,
                    writer_required, workspace_id, trunk_id, task_id, task_epoch,
                    stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'EXECUTE_STAGE_TURN', 'AGENT_TURN', 'STAGE_TURN',
                    ?, 'STAGE_TURN_RESULT', ?, 1, 1, 'workspace-1', 'trunk-1',
                    ?, 1, ?, 1, 1, ?, ?, ?, 'REQUESTED', 4)
                """, "ticket-" + suffix, "operation-" + suffix,
                "turn-" + suffix, laneMask, "task-" + suffix,
                "local-stage-" + suffix,
                "fingerprint-" + suffix, "base-" + suffix, "base-" + suffix);
    }

    private static SQLiteDataSource dataSource(String url)
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return dataSource;
    }

    private record Fixture(
            String url, SQLiteDataSource dataSource, JdbcTemplate jdbc,
            TaskCommandExecutor commands,
            LocalDevelopmentStageManager local,
            SqliteStageSteeringStore steering) {}
}
