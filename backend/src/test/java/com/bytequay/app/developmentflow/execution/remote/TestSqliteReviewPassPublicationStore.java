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
package com.bytequay.app.developmentflow.execution.remote;

import com.bytequay.app.developmentflow.execution.remote.ReviewBuildCommentOperationHandler.CommentAction;
import com.bytequay.app.developmentflow.execution.remote.SqliteReviewPassPublicationStore.PublicationView;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionStatus;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchWakeStore;
import com.bytequay.app.developmentflow.trunk.V2TrunkPurge;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqliteReviewPassPublicationStore
{
    private static final Instant NOW = Instant.ofEpochMilli(10_000);

    @TempDir
    private Path tempDir;

    @Test
    void authorizationFreezesExactSubjectAndReplaysAcrossRestart()
    {
        Fixture fixture = fixture("authorize.db");

        PublicationView authorized = fixture.store().authorize(
                "review-pass", "publish-command", "approve",
                List.of("finding-inline", "finding-top"), NOW);
        PublicationView replay = fixture.restartedStore().authorize(
                "review-pass", "publish-command", "APPROVE",
                List.of("finding-inline", "finding-top"), NOW.plusMillis(1));
        CommentAction action = fixture.action();

        assertThat(authorized.status()).isEqualTo("QUEUED");
        assertThat(authorized.terminal()).isFalse();
        assertThat(replay.commandId()).isEqualTo("publish-command");
        assertThat(action.payload().reviewAction()).isEqualTo("APPROVE");
        assertThat(action.payload().drafts()).singleElement().satisfies(draft -> {
            assertThat(draft.filePath()).isEqualTo("src/Main.java");
            assertThat(draft.lineNumber()).isEqualTo(17);
            assertThat(draft.findingId()).isEqualTo("finding-inline");
        });
        assertThat(action.payload().body()).contains(
                "Panel summary", "Architecture note",
                "bytequay-review-pass:review-pass:");
        assertThat(fixture.count("review_pass_publication_v288")).isOne();
        assertThat(fixture.count("dispatch_ticket"
                + " WHERE owner_kind = 'TRUNK' AND owner_id = 'review-thread'"
                + " AND task_id IS NULL AND stage_id IS NULL"
                + " AND operation_kind = 'PUBLISH_STANDALONE_REVIEW_PASS'"))
                .isOne();
        assertThatThrownBy(() -> fixture.store().authorize(
                "review-pass", "different-command", "APPROVE",
                List.of("finding-inline", "finding-top"), NOW.plusMillis(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different publication authorization");
    }

    @Test
    void historicalTaskPhaseAndLegacyThreadRejectBeforeAnyWrite()
    {
        Fixture taskPhase = fixture("task-phase.db");
        taskPhase.jdbc().update("""
                UPDATE review_passes
                SET host_kind = 'TASK_PHASE', host_id = 'historical-task'
                WHERE id = 'review-pass'
                """);

        assertThatThrownBy(() -> taskPhase.store().authorize(
                "review-pass", "publish-command", "COMMENT", List.of(), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TASK_PHASE-hosted");
        taskPhase.assertNoAuthorizationWrites();

        Fixture legacy = fixture("legacy-thread.db");
        legacy.jdbc().update("""
                UPDATE threads SET turn_version = 'LEGACY'
                WHERE id = 'review-thread'
                """);

        assertThatThrownBy(() -> legacy.store().authorize(
                "review-pass", "publish-command", "COMMENT", List.of(), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Historical review thread");
        assertThat(legacy.jdbc().queryForObject("""
                SELECT turn_version FROM threads WHERE id = 'review-thread'
                """, String.class)).isEqualTo("LEGACY");
        legacy.assertNoAuthorizationWrites();
    }

    @Test
    void acceptedDeliveryAloneFinalizesFindingsAndPassExactlyOnce()
    {
        Fixture fixture = fixture("finalize.db");
        fixture.store().authorize(
                "review-pass", "publish-command", "REQUEST_CHANGES",
                List.of("finding-inline", "finding-top"), NOW);
        CommentAction action = fixture.action();
        CommentAction claimed = fixture.store().claim(
                action.id(), 0, ClaimMode.EXECUTE, "worker",
                NOW.plusMillis(1), NOW.plusSeconds(30));
        fixture.store().recordRecoveryBaseline(
                action.id(), claimed.attemptCount(), List.of("review:80"));
        fixture.store().finishSucceeded(
                action.id(), claimed.attemptCount(), "review:91",
                "exact standalone review", NOW.plusMillis(2));

        assertThatThrownBy(() -> fixture.store().finalizeAction(
                action.id(), ActionStatus.SUCCEEDED, NOW.plusMillis(3)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("requires accepted delivery");
        assertThat(fixture.count("review_findings WHERE status = 'posted'"))
                .isZero();

        fixture.acceptDelivery(action.operationId(), NOW.plusMillis(3));
        fixture.restartedStore().finalizeAction(
                action.id(), ActionStatus.SUCCEEDED, NOW.plusMillis(4));
        fixture.restartedStore().finalizeAction(
                action.id(), ActionStatus.SUCCEEDED, NOW.plusMillis(5));

        assertThat(fixture.count("review_findings WHERE status = 'posted'"))
                .isEqualTo(2);
        assertThat(fixture.jdbc().queryForMap("""
                SELECT phase, verdict FROM review_passes
                WHERE id = 'review-pass'
                """))
                .containsEntry("phase", "published")
                .containsEntry("verdict", "request_changes");
        assertThat(fixture.store().findPublication("review-pass"))
                .get().satisfies(view -> {
                    assertThat(view.status()).isEqualTo("PUBLISHED");
                    assertThat(view.terminal()).isTrue();
                });
    }

    @Test
    void unfinishedPublicationBlocksPurgeButFinalizedHistoryPurgesInOrder()
    {
        Fixture unfinished = fixture("unfinished-purge.db");
        unfinished.store().authorize(
                "review-pass", "publish-command", "COMMENT", List.of(), NOW);
        unfinished.jdbc().execute(
                "DROP TRIGGER v2_trunk_purge_authorization_insert_v269");

        assertThatThrownBy(() -> unfinished.jdbc().update("""
                INSERT INTO v2_trunk_purge_authorization_v269(
                    trunk_id, archived_version, authorized_at_ms)
                VALUES ('review-thread', 0, ?)
                """, NOW.plusMillis(1).toEpochMilli()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("standalone review publication");

        Fixture finalized = fixture("finalized-purge.db");
        finalized.store().authorize(
                "review-pass", "publish-command", "COMMENT",
                List.of("finding-inline"), NOW);
        CommentAction action = finalized.action();
        CommentAction claimed = finalized.store().claim(
                action.id(), 0, ClaimMode.EXECUTE, "worker",
                NOW.plusMillis(1), NOW.plusSeconds(30));
        finalized.store().recordRecoveryBaseline(
                action.id(), claimed.attemptCount(), List.of("review:80"));
        finalized.store().finishSucceeded(
                action.id(), claimed.attemptCount(), "review:91",
                "exact standalone review", NOW.plusMillis(2));
        finalized.acceptDelivery(action.operationId(), NOW.plusMillis(3));
        finalized.store().finalizeAction(
                action.id(), ActionStatus.SUCCEEDED, NOW.plusMillis(4));
        finalized.deliverWake();
        finalized.jdbc().update("""
                UPDATE threads
                SET lifecycle_state = 'ARCHIVED', aggregate_version = 1
                WHERE id = 'review-thread'
                """);
        V2TrunkPurge purge = new V2TrunkPurge(
                finalized.jdbc(), finalized.transactionManager());

        purge.delete("review-thread", 1, () -> finalized.jdbc().update(
                "DELETE FROM threads WHERE id = 'review-thread'"));

        assertThat(finalized.count("review_pass_publication_dispatch_v288"))
                .isZero();
        assertThat(finalized.count("review_pass_publication_item_v288"))
                .isZero();
        assertThat(finalized.count("review_pass_publication_v288")).isZero();
        assertThat(finalized.count("review_passes WHERE id = 'review-pass'"))
                .isZero();
        assertThat(finalized.count("threads WHERE id = 'review-thread'"))
                .isZero();
    }

    private Fixture fixture(String name)
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(name)
                + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target("288")
                .load().migrate();
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl(url);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        seed(jdbc);
        return new Fixture(
                jdbc, source, new DataSourceTransactionManager(source));
    }

    private static void seed(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms,
                    updated_at_ms)
                VALUES ('workspace', 'Workspace', '', 0, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model,
                    cost_usd_milli, tokens_in, tokens_out,
                    created_at_ms, updated_at_ms, workspace_id, flow,
                    parallel_slots, turn_version, lifecycle_state,
                    aggregate_version)
                VALUES ('review-thread', 'LOGIC_LOOP', 'codex',
                    'Review PR #42', 'RUNNING', 'gpt-test', 0, 0, 0,
                    1, 1, 'workspace', 'review', 1, 'V2', 'ACTIVE', 0)
                """);
        jdbc.update("""
                INSERT INTO review_passes(
                    id, thread_id, repo_full_name, pr_number, head_sha, phase,
                    round, round_cap, cost_cap_milli, cost_usd_milli,
                    verdict, created_at_ms, ended_at_ms, host_kind, host_id,
                    kind, base_repository_id, head_repository_id, head_ref)
                VALUES ('review-pass', 'review-thread', 'acme/widget', 42,
                    'head-1', 'terminate', 0, 3, 500, 0, 'comment', 2, 2,
                    'THREAD', 'review-thread', 'FRESH', 'acme/widget',
                    'contributor/widget', 'feature')
                """);
        jdbc.update("""
                INSERT INTO review_participants(
                    id, review_pass_id, kind, credential_id, persona_label,
                    created_at_ms)
                VALUES ('lead', 'review-pass', 'lead', 'codex', 'Lead', 2)
                """);
        jdbc.update("""
                INSERT INTO review_messages(
                    id, review_pass_id, participant_id, phase, round, body,
                    cost_usd_milli, created_at_ms)
                VALUES ('summary', 'review-pass', 'lead', 'independent', 1,
                    'Panel summary', 0, 3)
                """);
        jdbc.update("""
                INSERT INTO review_findings(
                    id, review_pass_id, path, line, severity, status, body,
                    created_at_ms)
                VALUES
                    ('finding-inline', 'review-pass', 'src/Main.java', 17,
                     'blocker', 'agreed', 'Fix the exact race', 4),
                    ('finding-top', 'review-pass', NULL, NULL,
                     'major', 'arbitrated', 'Architecture note', 5)
                """);
    }

    private record Fixture(
            JdbcTemplate jdbc,
            SQLiteDataSource source,
            DataSourceTransactionManager transactionManager)
    {
        SqliteReviewPassPublicationStore store()
        {
            return new SqliteReviewPassPublicationStore(
                    jdbc, new TransactionTemplate(transactionManager),
                    new SqliteDispatchWakeStore(jdbc),
                    new ObjectMapper().findAndRegisterModules());
        }

        SqliteReviewPassPublicationStore restartedStore()
        {
            return store();
        }

        CommentAction action()
        {
            String operationId = jdbc.queryForObject("""
                    SELECT operation_id FROM review_pass_publication_v288
                    WHERE review_pass_id = 'review-pass'
                    """, String.class);
            return store().require(operationId);
        }

        void acceptDelivery(String operationId, Instant at)
        {
            jdbc.update("""
                    UPDATE dispatch_ticket
                    SET version = version + 1, status = 'SUCCEEDED',
                        completed_at_ms = ?, delivery_acceptance = 'ACCEPTED',
                        delivery_evidence = '{}'
                    WHERE operation_id = ?
                    """, at.toEpochMilli(), operationId);
        }

        void deliverWake()
        {
            SqliteDispatchWakeStore wakes = new SqliteDispatchWakeStore(jdbc);
            var claimed = wakes.claimAvailable(
                    "purge-worker", NOW.plusMillis(3), NOW.plusSeconds(30), 1);
            assertThat(claimed).hasSize(1);
            assertThat(wakes.markDelivered(
                    claimed.getFirst(), NOW.plusMillis(4))).isTrue();
        }

        void assertNoAuthorizationWrites()
        {
            assertThat(count("review_pass_publication_v288")).isZero();
            assertThat(count("dispatch_ticket"
                    + " WHERE operation_kind = 'PUBLISH_STANDALONE_REVIEW_PASS'"))
                    .isZero();
            assertThat(count("outbox"
                    + " WHERE topic = 'V2_DISPATCH_TICKET_REQUESTED'"))
                    .isZero();
        }

        int count(String suffix)
        {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + suffix, Integer.class);
            return count == null ? 0 : count;
        }
    }
}
