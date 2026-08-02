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

import com.bytequay.app.developmentflow.stage.V2RemoteFeedbackControlService;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.InboxItem;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.InboxKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.Provenance;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore.NewTurn;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore.ReplyDraft;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.ReviewRoundState;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.testing.MigratedSqliteDatabase;
import com.bytequay.app.testing.SqliteTestPools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
class TestV2RemoteFeedbackControlService
{
    private static final Instant NOW = Instant.ofEpochMilli(100);

    @TempDir
    private Path tempDir;

    @Test
    void listsTheTypedBatchAndAuthorizesOnlyItsExactDraftsWithoutANoopPush()
            throws Exception
    {
        Fixture fixture = fixture();
        fixture.gate("head-1");

        List<ReviewRound> rounds = fixture.service().findByTask("task-1");
        assertThat(rounds).hasSize(1);
        ReviewRound round = rounds.getFirst();
        assertThat(round.id()).isEqualTo("batch-1");
        assertThat(round.idx()).isOne();
        assertThat(round.reviewers()).containsExactly("@alice", "@bob");
        assertThat(round.status()).isEqualTo(ReviewRoundState.AWAITING_GATE);
        assertThat(round.stats()).isEqualTo(
                new ReviewRound.ReviewRoundStats(0, 2, 0, 0));
        assertThat(round.gatedAt()).isEqualTo(NOW.plusMillis(3));

        ReviewRound approved = fixture.service().approve("batch-1");
        assertThat(approved.status()).isEqualTo(ReviewRoundState.POSTED);
        assertThat(fixture.effects()).containsExactly(
                "1|POST_INLINE_REPLY|inbox-inline|thread-1|inline reply",
                "2|RESOLVE_THREAD|inbox-inline|thread-1|thread-1",
                "3|POST_TOP_LEVEL_REPLY|inbox-top|comment-2|top reply");
        assertThat(fixture.count("""
                SELECT COUNT(*) FROM remote_feedback_effect_step
                WHERE kind = 'PUSH_COMMITS'
                """)).isZero();
    }

    @Test
    void appendsPushLastAndDuplicateApprovalReusesTheSameAuthorization()
            throws Exception
    {
        Fixture fixture = fixture();
        fixture.gate("head-fixed");

        ReviewRound first = fixture.service().approve("batch-1");
        ReviewRound duplicate = fixture.service().approve("batch-1");

        assertThat(duplicate).isEqualTo(first);
        assertThat(fixture.effects()).containsExactly(
                "1|POST_INLINE_REPLY|inbox-inline|thread-1|inline reply",
                "2|RESOLVE_THREAD|inbox-inline|thread-1|thread-1",
                "3|POST_TOP_LEVEL_REPLY|inbox-top|comment-2|top reply",
                "4|PUSH_COMMITS|null|null|head-fixed");
        assertThat(fixture.count("""
                SELECT COUNT(*) FROM remote_feedback_authorization
                WHERE remote_feedback_batch_id = 'batch-1'
                """)).isOne();
        assertThat(fixture.count("""
                SELECT COUNT(*) FROM remote_feedback_effect_step
                WHERE remote_feedback_batch_id = 'batch-1'
                """)).isEqualTo(4);
        assertThat(fixture.count("""
                SELECT COUNT(*) FROM remote_feedback_effect_dispatch
                """)).isOne();
        assertThat(fixture.count("""
                SELECT COUNT(*) FROM dispatch_ticket
                WHERE callback_route = 'REMOTE_FEEDBACK_EFFECT_RESULT'
                """)).isOne();
    }

    @Test
    void rejectsAnApprovalWhenTheRemoteHeadMovedPastTheFrozenGate()
            throws Exception
    {
        Fixture fixture = fixture();
        fixture.gate("head-fixed");
        try (var connection = connect(fixture.url())) {
            insertSnapshot(connection, 1, 2, "head-moved", "base-1", "OPEN",
                    "MERGEABLE", "NONE", "UNSUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 2, "head-moved", "base-1");
        }

        assertThatThrownBy(() -> fixture.service().approve("batch-1"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(failure -> assertThat(
                        ((ResponseStatusException) failure).getStatusCode().value())
                        .isEqualTo(409))
                .hasMessageContaining("stale or incomplete");
        assertThat(fixture.count("""
                SELECT COUNT(*) FROM remote_feedback_authorization
                """)).isZero();
        assertThat(fixture.count("""
                SELECT COUNT(*) FROM remote_feedback_effect_step
                """)).isZero();
        assertThat(fixture.count("""
                SELECT COUNT(*) FROM dispatch_ticket
                WHERE callback_route = 'REMOTE_FEEDBACK_EFFECT_RESULT'
                """)).isZero();
    }

    private Fixture fixture()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(UUID.randomUUID() + ".db")
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
        DataSourceTransactionManager manager =
                new DataSourceTransactionManager(dataSource);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        SqliteRemoteDevelopmentRuntimeStore remote =
                new SqliteRemoteDevelopmentRuntimeStore(jdbc);
        SqliteRemoteFeedbackLoopStore loop =
                new SqliteRemoteFeedbackLoopStore(jdbc);
        V2RemoteFeedbackControlService service =
                new V2RemoteFeedbackControlService(
                        jdbc, new TaskCommandExecutor(manager), remote);
        return new Fixture(url, jdbc, transaction, remote, loop, service);
    }

    private record Fixture(
            String url,
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            SqliteRemoteDevelopmentRuntimeStore remote,
            SqliteRemoteFeedbackLoopStore loop,
            V2RemoteFeedbackControlService service)
    {
        void gate(String proposedHead)
        {
            transaction.executeWithoutResult(ignored -> {
                remote.ingest(inbox(
                        "inbox-inline", "inline-key", InboxKind.INLINE_COMMENT,
                        "alice", "thread-1", "comment-1", "please fix inline",
                        NOW));
                remote.ingest(inbox(
                        "inbox-top", "top-key", InboxKind.TOP_LEVEL_COMMENT,
                        "bob", null, "comment-2", "please fix top",
                        NOW.plusMillis(1)));
                assertThat(remote.freezeNextBatch(
                        "batch-1", "task-1", "remote-stage-1", false,
                        "observer", NOW)).isPresent();
                jdbc.update("""
                        UPDATE stage
                        SET version = version + 1,
                            checkpoint = 'ADDRESSING_REMOTE_FEEDBACK'
                        WHERE id = 'remote-stage-1'
                        """);
                loop.markAddressing("batch-1");
                loop.insertTurn(new NewTurn(
                        "request-1", "batch-1", "turn-1", "turn-operation-1",
                        "turn-ticket-1", 1, null, "workspace-1", "trunk-1",
                        "task-1", 1, "remote-stage-1", 1, "fingerprint-1",
                        "head-1", "base-1", "CLI", 1, "{}",
                        SqliteRemoteDevelopmentRuntimeStore.digest("prompt"),
                        "runtime", NOW));
            });
            var turn = loop.requireStageTurnContext(
                    "turn-1", "turn-operation-1");
            transaction.executeWithoutResult(ignored -> {
                loop.finishStageTurn(turn, "SUCCEEDED", null, NOW.plusMillis(1));
                loop.insertRepairAndValidation(
                        turn, "repair-1", proposedHead, "fingerprint-fixed",
                        "fixed review",
                        SqliteRemoteDevelopmentRuntimeStore.digest("result"),
                        List.of(
                                new ReplyDraft(
                                        "draft-inline", 1, 1,
                                        "POST_INLINE_REPLY", "inline reply",
                                        SqliteRemoteDevelopmentRuntimeStore.digest(
                                                "inline reply"), "thread-1"),
                                new ReplyDraft(
                                        "draft-resolve", 2, 1,
                                        "RESOLVE_THREAD", null, null, "thread-1"),
                                new ReplyDraft(
                                        "draft-top", 3, 2,
                                        "POST_TOP_LEVEL_REPLY", "top reply",
                                        SqliteRemoteDevelopmentRuntimeStore.digest(
                                                "top reply"), "comment-2")),
                        "remote-validation-1", "remote-validation-ticket-1",
                        NOW.plusMillis(1));
            });
            var validation = loop.requireValidationContext("remote-validation-1");
            transaction.executeWithoutResult(ignored -> {
                var evidence = loop.completeValidation(
                        validation, true, "[]", "checks passed",
                        NOW.plusMillis(2), NOW.plusMillis(3));
                loop.insertFinalValidation(validation, evidence, "checks passed");
                loop.moveAwaitingApproval("batch-1");
            });
        }

        List<String> effects()
        {
            return jdbc.query("""
                    SELECT step.ordinal || '|' || step.kind || '|' ||
                           COALESCE(step.remote_inbox_item_id, 'null') || '|' ||
                           COALESCE(step.external_target, 'null') || '|' ||
                           payload.payload AS effect
                    FROM remote_feedback_effect_step step
                    JOIN remote_feedback_effect_payload payload
                      ON payload.remote_feedback_effect_step_id = step.id
                    ORDER BY step.ordinal
                    """, (rs, row) -> rs.getString("effect"));
        }

        int count(String sql)
        {
            return jdbc.queryForObject(sql, Integer.class);
        }
    }

    private static InboxItem inbox(
            String id,
            String key,
            InboxKind kind,
            String actor,
            String threadId,
            String commentId,
            String body,
            Instant observedAt)
    {
        return new InboxItem(
                id, "task-1", 1, "remote-stage-1", 1, "binding-1",
                "snapshot-1-1", kind, key, 1, "head-1", "base-1", actor,
                Provenance.EXTERNAL, false, threadId, commentId, null, null,
                body, SqliteRemoteDevelopmentRuntimeStore.digest(body), null,
                null, null, observedAt, "raw:" + body);
    }
}
