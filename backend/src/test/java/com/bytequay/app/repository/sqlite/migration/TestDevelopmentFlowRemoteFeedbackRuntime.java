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

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOwnerResultCodec;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.RemoteFeedbackRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteFeedbackRuntimeCoordinator.ReplyResult;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.stage.persistence.SqliteAgentResultSubmissionStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.EffectDraft;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.EffectKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.InboxItem;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.InboxKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.ObservedInboxItem;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.PayloadKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.Provenance;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore.ReplyDraft;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore.TurnRequest;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.testing.MigratedSqliteDatabase;
import com.bytequay.app.testing.SqliteTestPools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.acceptSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.execute;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SqliteTestPools.class)
class TestDevelopmentFlowRemoteFeedbackRuntime
{
    @TempDir
    private Path tempDir;

    @Test
    void freezesExactRevisionsAndBuildsARecoverableUserGate()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("feedback-runtime.db")
                + "?foreign_keys=ON&busy_timeout=30000";
        MigratedSqliteDatabase.migrate(url);
        try (var connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
        }
        MigratedSqliteDatabase.migrate(url);
        try (var connection = connect(url)) {
            insertRemoteOwner(connection, 1);
            insertSnapshot(connection, 1, 1, "head-1", "base-1", "OPEN",
                    "MERGEABLE", "NONE", "UNSUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");
            execute(connection, """
                    UPDATE stage
                    SET version = version + 1,
                        checkpoint = 'WAITING_REMOTE_REVIEW'
                    WHERE id = 'remote-stage-1'
                    """);
        }

        DataSource dataSource = SqliteTestPools.open(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        SqliteRemoteDevelopmentRuntimeStore remote =
                new SqliteRemoteDevelopmentRuntimeStore(jdbc);
        SqliteRemoteFeedbackLoopStore loop = new SqliteRemoteFeedbackLoopStore(jdbc);
        Instant now = Instant.ofEpochMilli(100);
        InboxItem first = inbox("inbox-1", 1, "please fix", now);

        transaction.executeWithoutResult(ignored -> {
            assertThat(remote.ingest(first).inserted()).isTrue();
            assertThat(remote.ingest(first).inserted()).isFalse();
        });
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored ->
                remote.ingest(inbox("other-id", 1, "different", now))))
                .hasMessageContaining("different evidence");

        var batch = transaction.execute(status -> remote.freezeNextBatch(
                "batch-1", "task-1", "remote-stage-1", false, "observer", now)
                .orElseThrow());
        assertThat(batch.items()).hasSize(1);

        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactionManager);
        TaskManager tasks = new TaskManager(commands, reflectStore(
                "com.bytequay.app.developmentflow.task.persistence.V2TaskStore",
                TaskManager.Store.class, jdbc));
        RemoteDevelopmentStageManager stage = new RemoteDevelopmentStageManager(
                commands, reflectStore(
                        "com.bytequay.app.developmentflow.stage.persistence.V2StageStore",
                        StageManager.Store.class, jdbc), remote);
        RemoteFeedbackRuntimeCoordinator coordinator =
                new RemoteFeedbackRuntimeCoordinator(
                        commands, tasks, stage, remote, loop,
                        new SqliteAgentResultSubmissionStore(jdbc),
                        new ObjectMapper(), Clock.fixed(now, ZoneOffset.UTC),
                        53123);

        var request = coordinator.start("task-1", "batch-1");
        assertThat(coordinator.start("task-1", "batch-1")).isEqualTo(request);
        assertThat(request.attempt()).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT status FROM remote_feedback_batch WHERE id = 'batch-1'
                """, String.class)).isEqualTo("ADDRESSING");
        assertThat(jdbc.queryForObject("""
                SELECT checkpoint FROM stage WHERE id = 'remote-stage-1'
                """, String.class)).isEqualTo("ADDRESSING_REMOTE_FEEDBACK");
        assertThat(jdbc.queryForObject("""
                SELECT purpose || '|' || status || '|' || task_epoch || '|'
                    || stage_generation || '|' || expected_code_fingerprint || '|'
                    || expected_head_sha || '|' || expected_base_sha
                FROM stage_turn WHERE id = ? AND operation_id = ?
                """, String.class, request.turnId(), request.operationId()))
                .isEqualTo("ADDRESS_REMOTE_FEEDBACK|QUEUED|1|1|"
                        + "fingerprint-1|head-1|base-1");
        assertThat(jdbc.queryForObject("""
                SELECT owner_kind || '|' || owner_id || '|' || callback_route
                    || '|' || task_id || '|' || task_epoch || '|' || stage_id
                    || '|' || stage_generation || '|' || attempt
                FROM dispatch_ticket WHERE id = ? AND operation_id = ?
                """, String.class, request.ticketId(), request.operationId()))
                .isEqualTo("STAGE_TURN|" + request.turnId()
                        + "|REMOTE_FEEDBACK_TURN_RESULT|task-1|1|"
                        + "remote-stage-1|1|1");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM remote_feedback_batch_item
                WHERE remote_feedback_batch_id = 'batch-1'
                  AND remote_inbox_item_id = 'inbox-1'
                  AND external_revision = 1
                  AND frozen_body = 'please fix'
                """, Integer.class)).isOne();

        // A repair that ends without calling the tool fails by name, however
        // fluent its final message was.
        assertThatThrownBy(() -> coordinator.deliverStageTurn(
                stageTurnResult(new ObjectMapper(), request)))
                .hasMessageContaining(
                        "succeeded without record_feedback_repair");

        // The repair's result is a tool call now, not its final message. An
        // identical re-submission is a no-op so a retried call is safe; a
        // differing one is refused, and a draft whose body contradicts its
        // effect kind is refused while the agent can still fix it.
        List<ReplyResult> replies = List.of(new ReplyResult(
                null, 1, "POST_TOP_LEVEL_REPLY", "fixed, thanks", "comment-1"));
        coordinator.recordFeedbackRepair(
                request.turnId(), request.operationId(), "fixed review", replies);
        coordinator.recordFeedbackRepair(
                request.turnId(), request.operationId(), "fixed review", replies);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM stage_turn_repair_submission
                WHERE stage_turn_id = ?
                """, Integer.class, request.turnId())).isOne();
        assertThatThrownBy(() -> coordinator.recordFeedbackRepair(
                request.turnId(), request.operationId(), "something else",
                replies))
                .hasMessageContaining("already called with different content");
        assertThatThrownBy(() -> coordinator.recordFeedbackRepair(
                "other-turn", request.operationId(), "fixed review", replies))
                .hasMessageContaining("Remote repair Turn owner is missing");
        assertThatThrownBy(() -> coordinator.recordFeedbackRepair(
                request.turnId(), request.operationId(), "fixed review",
                List.of(new ReplyResult(
                        null, 1, "RESOLVE_THREAD", "a body", "comment-1"))))
                .hasMessageContaining("does not match its effect kind");

        var turn = loop.requireStageTurnContext(
                request.turnId(), request.operationId());
        transaction.executeWithoutResult(ignored -> {
            loop.finishStageTurn(turn, "SUCCEEDED", null, now.plusMillis(1));
            loop.insertRepairAndValidation(
                    turn, "repair-1", "head-fixed", "fingerprint-fixed",
                    "fixed review", SqliteRemoteDevelopmentRuntimeStore.digest("result"),
                    List.of(new ReplyDraft(
                            "reply-draft-1", 1, 1, "POST_TOP_LEVEL_REPLY",
                            "fixed, thanks", SqliteRemoteDevelopmentRuntimeStore.digest(
                                    "fixed, thanks"), "comment-1")),
                    "remote-validation-1", "remote-validation-ticket-1",
                    now.plusMillis(1));
        });
        var validation = loop.requireValidationContext("remote-validation-1");
        transaction.executeWithoutResult(ignored -> {
            var evidence = loop.completeValidation(
                    validation, true, "[]", "checks passed",
                    now.plusMillis(2), now.plusMillis(3));
            loop.insertFinalValidation(validation, evidence, "checks passed");
            loop.moveAwaitingApproval("batch-1");
        });

        transaction.executeWithoutResult(ignored -> remote.authorizeFeedback(
                "authorization-1", "batch-1", "user-1", "ship replies",
                List.of(new EffectDraft(
                        "reply-effect-1", EffectKind.POST_TOP_LEVEL_REPLY,
                        "inbox-1", "comment-1", null, PayloadKind.TEXT,
                        "fixed, thanks", "reply:comment-1:1", 3)),
                now.plusMillis(4)));
        assertThat(jdbc.queryForObject("""
                SELECT status FROM remote_feedback_batch WHERE id = 'batch-1'
                """, String.class)).isEqualTo("APPLYING");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM remote_feedback_authorization
                WHERE remote_feedback_batch_id = 'batch-1'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM dispatch_ticket
                WHERE callback_route = 'REMOTE_FEEDBACK_EFFECT_RESULT'
                """, Integer.class)).isOne();

        transaction.executeWithoutResult(ignored -> remote.ingest(
                inbox("inbox-2", 2, "one more thing", now.plusMillis(5))));
        var next = transaction.execute(status -> remote.freezeNextBatch(
                "batch-2", "task-1", "remote-stage-1", true, "observer",
                now.plusMillis(5)).orElseThrow());
        assertThat(next.items()).extracting(item -> item.externalRevision())
                .containsExactly(2L);

        transaction.executeWithoutResult(ignored -> {
            var inserted = remote.ingestObserved(observed(
                    "stable", "snapshot-1-1", "head-1", "raw-1", now));
            var replayed = remote.ingestObserved(observed(
                    "stable", "snapshot-1-1", "head-2", "raw-2",
                    now.plusMillis(1)));
            var revised = remote.ingestObserved(observed(
                    "changed", "snapshot-1-1", "head-1", "raw-3",
                    now.plusMillis(2)));
            assertThat(inserted.inserted()).isTrue();
            assertThat(replayed.inserted()).isFalse();
            assertThat(replayed.itemId()).isEqualTo(inserted.itemId());
            assertThat(revised.inserted()).isTrue();
        });
        assertThat(jdbc.queryForObject("""
                SELECT MAX(external_revision) FROM remote_inbox_item
                WHERE external_key = 'observed-key'
                """, Long.class)).isEqualTo(2L);
    }

    private static AgentTurnOwnerResultCodec.OwnerResult stageTurnResult(
            ObjectMapper json, TurnRequest request)
            throws Exception
    {
        DispatchTicket.OperationFence fence =
                new DispatchTicket.OperationFence(
                        1L, "remote-stage-1", 1L, request.operationId(), 1,
                        "fingerprint-1", "head-1", "base-1");
        AgentTurnOperationHandler.OutputCodeSubject output =
                new AgentTurnOperationHandler.OutputCodeSubject(
                        "fingerprint-2", "head-2", "base-1", true, "base-1");
        AgentTurnOperationHandler.RawResult payload =
                new AgentTurnOperationHandler.RawResult(
                        1, request.turnId(), DispatchTicket.OwnerKind.STAGE_TURN,
                        "ADDRESS_REMOTE_FEEDBACK",
                        AgentTurnProviderSession.Transport.API, "openai",
                        "feedback-session",
                        "I replied to every comment and pushed the fix.",
                        1, 1, 1, null,
                        AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED,
                        null, null, output);
        AgentTurnOperationHandler.Evidence evidence =
                new AgentTurnOperationHandler.Evidence(
                        1,
                        AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED,
                        null,
                        new AgentTurnProviderSession.WriterFence(
                                "/tmp/task-1", "task-1", request.operationId(),
                                1, 1),
                        null, output);
        return new AgentTurnOwnerResultCodec(json).decode(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.STAGE_TURN, request.turnId(),
                        RemoteFeedbackRuntimeCoordinator.TURN_CALLBACK),
                fence, new DispatchTicket.DispatchResult(
                        fence, DispatchTicket.Outcome.SUCCEEDED,
                        json.writeValueAsString(payload),
                        json.writeValueAsString(evidence), null));
    }

    private static InboxItem inbox(
            String id, long revision, String body, Instant observedAt)
    {
        return new InboxItem(
                id, "task-1", 1, "remote-stage-1", 1, "binding-1",
                "snapshot-1-1", InboxKind.TOP_LEVEL_COMMENT,
                "comment-key", revision, "head-1", "base-1", "reviewer",
                Provenance.EXTERNAL, false, null, "comment-1", null, null,
                body, SqliteRemoteDevelopmentRuntimeStore.digest(body), null,
                null, null, observedAt, "raw:" + body);
    }

    private static ObservedInboxItem observed(
            String body,
            String snapshotId,
            String headSha,
            String raw,
            Instant observedAt)
    {
        return new ObservedInboxItem(
                "task-1", 1, "remote-stage-1", 1, "binding-1", snapshotId,
                InboxKind.TOP_LEVEL_COMMENT, "observed-key", headSha, "base-1",
                "reviewer", Provenance.EXTERNAL, false, null, "comment-2",
                null, null, body, null, null, null, observedAt, raw);
    }

    private static <T> T reflectStore(
            String className, Class<T> storeType, JdbcTemplate jdbc)
            throws Exception
    {
        Constructor<?> constructor = Class.forName(className)
                .getDeclaredConstructor(JdbcTemplate.class);
        constructor.setAccessible(true);
        return storeType.cast(constructor.newInstance(jdbc));
    }
}
