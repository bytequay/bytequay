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

import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.EffectDraft;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.EffectKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.InboxItem;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.InboxKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.ObservedInboxItem;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.PayloadKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.Provenance;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore.NewTurn;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore.ReplyDraft;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
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
        Flyway.configure().dataSource(url, "", "").target("228").load().migrate();
        try (var connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
        }
        Flyway.configure().dataSource(url, "", "").target("242").load().migrate();
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

        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
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

        transaction.executeWithoutResult(ignored -> {
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
                    "runtime", now));
        });
        var turn = loop.requireStageTurnContext("turn-1", "turn-operation-1");
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
}
