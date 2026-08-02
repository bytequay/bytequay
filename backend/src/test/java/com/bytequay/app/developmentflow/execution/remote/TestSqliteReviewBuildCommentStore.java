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
import com.bytequay.app.developmentflow.execution.remote.SqliteReviewBuildCommentStore.ProposalView;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionStatus;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchWakeStore;
import com.bytequay.app.developmentflow.trunk.V2TrunkPurge;
import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewFindingSeverity;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.service.review.ReviewBuildSelectionStore;
import com.bytequay.app.service.review.ReviewBuildSpawnService;
import com.bytequay.app.testing.MigratedSqliteDatabase;
import com.bytequay.app.testing.SqliteTestPools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SqliteTestPools.class)
class TestSqliteReviewBuildCommentStore
{
    private static final Instant NOW = Instant.ofEpochMilli(10_000);

    @TempDir
    private Path tempDir;

    @Test
    void approvalFreezesOneTrunkOwnedActionAndReplaysOnlyTheSameKey()
    {
        Fixture fixture = fixture("approve.db");
        ProposalView pending = fixture.store().findProposal("review-pass")
                .orElseThrow();

        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(pending.items()).extracting(item -> item.kind())
                .containsExactly("INLINE", "TOP_LEVEL");

        ProposalView approved = fixture.store().approve(
                "review-pass", "approve-command", NOW);
        CommentAction action = fixture.store().requireActionByThread(
                "build-thread");
        ProposalView replay = fixture.restartedStore().approve(
                "review-pass", "approve-command", NOW.plusMillis(1));

        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(replay.commandId()).isEqualTo("approve-command");
        assertThat(action.payload().reviewAction()).isEqualTo("COMMENT");
        assertThat(action.payload().drafts()).singleElement().satisfies(draft -> {
            assertThat(draft.filePath()).isEqualTo("src/Main.java");
            assertThat(draft.lineNumber()).isEqualTo(17);
            assertThat(draft.side()).isEqualTo("RIGHT");
            assertThat(draft.findingId()).isEqualTo("finding-inline");
        });
        assertThat(action.payload().body()).contains(
                "Architecture note", "bytequay-review-build:finding-top");
        assertThat(fixture.count("review_build_comment_action_v287")).isOne();
        assertThat(fixture.count("dispatch_ticket WHERE owner_kind = 'TRUNK'"
                + " AND task_id IS NULL AND stage_id IS NULL"
                + " AND operation_kind = 'APPLY_REVIEW_BUILD_COMMENTS'"))
                .isOne();
        assertThat(fixture.count("tasks WHERE thread_id = 'build-thread'"))
                .isZero();
        assertThatThrownBy(() -> fixture.store().approve(
                "review-pass", "different-command", NOW.plusMillis(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different decision");
    }

    @Test
    void discardIsReplayableDbOnlyAndCreatesNoRemoteWork()
    {
        Fixture fixture = fixture("discard.db");

        ProposalView discarded = fixture.store().discard(
                "review-pass", "discard-command", NOW);
        ProposalView replay = fixture.restartedStore().discard(
                "review-pass", "discard-command", NOW.plusMillis(1));

        assertThat(discarded.status()).isEqualTo("DISCARDED");
        assertThat(replay.commandId()).isEqualTo("discard-command");
        assertThat(fixture.count("review_build_comment_action_v287")).isZero();
        assertThat(fixture.count("dispatch_ticket"
                + " WHERE operation_kind = 'APPLY_REVIEW_BUILD_COMMENTS'"))
                .isZero();
        assertThat(fixture.count("outbox"
                + " WHERE topic = 'V2_DISPATCH_TICKET_REQUESTED'"))
                .isZero();
        assertThatThrownBy(() -> fixture.store().discard(
                "review-pass", "different-command", NOW.plusMillis(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different decision");
    }

    @Test
    void archivedTrunkCannotAuthorizeOrBypassTheStorageGuard()
    {
        Fixture fixture = fixture("archived-approval.db");
        fixture.jdbc().update("""
                UPDATE threads
                SET lifecycle_state = 'ARCHIVED',
                    aggregate_version = aggregate_version + 1
                WHERE id = 'build-thread'
                """);

        assertThatThrownBy(() -> fixture.store().approve(
                "review-pass", "approve-command", NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zero-Task Trunk");
        assertThatThrownBy(() -> fixture.jdbc().update("""
                INSERT INTO review_build_comment_action_v287(
                    id, operation_id, thread_id, review_pass_id, command_id,
                    workspace_id, remote_repository_id, head_repository_id,
                    remote_pr_number, branch_name, expected_head_sha,
                    payload_json, payload_digest, semantic_attempt, status,
                    attempt_count, attempt_limit, observation_count,
                    observation_limit, authorized_at_ms)
                VALUES ('direct-action', 'direct-operation', 'build-thread',
                    'review-pass', 'direct-command', 'workspace',
                    'acme/widget', 'contributor/widget', 42, 'feature',
                    'head-1', '{}',
                    '0000000000000000000000000000000000000000000000000000000000000000',
                    1, 'REQUESTED', 0, 3, 0, 60, 10000)
                """))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("zero-Task Trunk");
        assertThat(fixture.count("review_build_comment_action_v287")).isZero();
    }

    @Test
    void findingsResolveOnlyAfterAcceptedSuccessAndRestartFinalizationIsIdempotent()
    {
        Fixture fixture = fixture("finalize.db");
        fixture.store().approve("review-pass", "approve-command", NOW);
        CommentAction action = fixture.store().requireActionByThread(
                "build-thread");
        CommentAction claimed = fixture.store().claim(
                action.id(), 0, ClaimMode.EXECUTE, "worker", NOW.plusMillis(1),
                NOW.plusSeconds(30));
        fixture.store().recordRecoveryBaseline(
                action.id(), claimed.attemptCount(), List.of("review:80"));
        fixture.store().finishSucceeded(
                action.id(), claimed.attemptCount(), "review:91",
                "exact suggested-change review", NOW.plusMillis(2));

        assertThatThrownBy(() -> fixture.store().finalizeAction(
                action.id(), ActionStatus.SUCCEEDED, NOW.plusMillis(3)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("requires accepted delivery");
        assertThat(fixture.count("review_findings WHERE status = 'resolved'"))
                .isZero();

        fixture.jdbc().update("""
                UPDATE dispatch_ticket
                SET version = version + 1,
                    status = 'SUCCEEDED', completed_at_ms = ?,
                    delivery_acceptance = 'ACCEPTED',
                    delivery_evidence = '{}'
                WHERE operation_id = ?
                """, NOW.plusMillis(3).toEpochMilli(), action.operationId());
        fixture.restartedStore().finalizeAction(
                action.id(), ActionStatus.SUCCEEDED, NOW.plusMillis(4));
        fixture.restartedStore().finalizeAction(
                action.id(), ActionStatus.SUCCEEDED, NOW.plusMillis(5));

        assertThat(fixture.count("review_findings WHERE status = 'resolved'"))
                .isEqualTo(2);
        assertThat(fixture.jdbc().queryForObject("""
                SELECT resolved_count FROM review_build_comment_action_v287
                WHERE id = ?
                """, Integer.class, action.id())).isEqualTo(2);
    }

    @Test
    void observationBudgetAbandonsWithoutSpendingAnotherMutationAttempt()
    {
        Fixture fixture = fixture("observation-budget.db");
        fixture.store().approve("review-pass", "approve-command", NOW);
        CommentAction action = fixture.store().requireActionByThread(
                "build-thread");
        CommentAction claimed = fixture.store().claim(
                action.id(), 0, ClaimMode.EXECUTE, "worker", NOW.plusMillis(1),
                NOW.plusSeconds(30));

        for (int observation = 1; observation < 60; observation++) {
            assertThat(fixture.store().deferProbe(
                    action.id(), claimed.attemptCount(), NOW.plusMillis(2),
                    NOW.plusSeconds(5), "not visible")).isTrue();
        }
        assertThat(fixture.restartedStore().deferProbe(
                action.id(), claimed.attemptCount(), NOW.plusMillis(2),
                NOW.plusSeconds(5), "not visible")).isFalse();

        assertThat(fixture.jdbc().queryForMap("""
                SELECT status, attempt_count, observation_count
                FROM review_build_comment_action_v287 WHERE id = ?
                """, action.id()))
                .containsEntry("status", "ABANDONED")
                .containsEntry("attempt_count", 1)
                .containsEntry("observation_count", 60);
    }

    @Test
    void observationWindowStartsAtTheFirstMissNotAtAuthorization()
    {
        Fixture fixture = fixture("late-first-execution.db");
        fixture.store().approve("review-pass", "approve-command", NOW);
        CommentAction action = fixture.store().requireActionByThread(
                "build-thread");
        Instant firstObservation = NOW.plus(Duration.ofHours(2));
        CommentAction claimed = fixture.store().claim(
                action.id(), 0, ClaimMode.EXECUTE, "worker", firstObservation,
                firstObservation.plusSeconds(30));

        assertThat(fixture.store().deferProbe(
                action.id(), claimed.attemptCount(), firstObservation,
                firstObservation.plusSeconds(5), "not visible")).isTrue();

        assertThat(fixture.jdbc().queryForObject("""
                SELECT status FROM review_build_comment_action_v287
                WHERE id = ?
                """, String.class, action.id())).isEqualTo("CLAIMED");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT observation_started_at_ms
                FROM review_build_comment_action_v287 WHERE id = ?
                """, Long.class, action.id()))
                .isEqualTo(firstObservation.toEpochMilli());
        assertThat(fixture.jdbc().queryForObject("""
                SELECT observation_deadline_ms
                FROM review_build_comment_action_v287 WHERE id = ?
                """, Long.class, action.id()))
                .isEqualTo(firstObservation.plus(Duration.ofMinutes(5))
                        .toEpochMilli());
    }

    @Test
    void archivedTrunkStillAllowsProbeRecoveryOfAnAuthorizedEffect()
    {
        Fixture fixture = fixture("archive-recovery.db");
        fixture.store().approve("review-pass", "approve-command", NOW);
        CommentAction action = fixture.store().requireActionByThread(
                "build-thread");
        CommentAction claimed = fixture.store().claim(
                action.id(), 0, ClaimMode.EXECUTE, "worker", NOW.plusMillis(1),
                NOW.plusMillis(2));
        fixture.store().deferProbe(
                action.id(), claimed.attemptCount(), NOW.plusMillis(1),
                NOW.plusMillis(2), "possible remote effect");
        fixture.jdbc().update("""
                UPDATE threads
                SET lifecycle_state = 'ARCHIVED',
                    aggregate_version = aggregate_version + 1
                WHERE id = 'build-thread'
                """);

        CommentAction recovered = fixture.restartedStore().claim(
                action.id(), claimed.attemptCount(), ClaimMode.PROBE,
                "recovery-worker", NOW.plusMillis(3), NOW.plusSeconds(30));

        assertThat(recovered.status()).isEqualTo(ActionStatus.CLAIMED);
        assertThat(recovered.attemptCount()).isOne();
    }

    @Test
    void purgeAuthorizationIsBlockedUntilTheAcceptedActionIsFinalized()
    {
        Fixture fixture = fixture("purge.db");
        fixture.store().approve("review-pass", "approve-command", NOW);
        fixture.jdbc().execute(
                "DROP TRIGGER v2_trunk_purge_authorization_insert_v269");

        assertThatThrownBy(() -> fixture.jdbc().update("""
                INSERT INTO v2_trunk_purge_authorization_v269(
                    trunk_id, archived_version, authorized_at_ms)
                VALUES ('build-thread', 0, ?)
                """, NOW.plusMillis(1).toEpochMilli()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("review build comment action");
    }

    @Test
    void migrationBackfillsAnAlreadyFrozenSuggestedChangeSelection()
    {
        String url = databaseUrl("backfill.db");
        MigratedSqliteDatabase.migrate(url);
        DataSource source = dataSource(url);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        seedFrozenSuggestedSelection(jdbc);

        MigratedSqliteDatabase.migrate(url);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM review_build_comment_proposal_v287 proposal
                JOIN review_build_selection selection
                  ON selection.thread_id = proposal.thread_id
                 AND selection.review_pass_id = proposal.review_pass_id
                 AND selection.selection_digest = proposal.selection_digest
                WHERE proposal.thread_id = 'build-thread'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM review_build_comment_proposal_item_v287
                WHERE thread_id = 'build-thread'
                  AND body LIKE '%bytequay-review-build:%'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM tasks
                WHERE thread_id = 'build-thread'
                """, Integer.class)).isZero();
    }

    @Test
    void finalizedAcceptedReviewBuildCanBePurgedInDependencyOrder()
    {
        Fixture fixture = fixture("finalized-purge.db");
        fixture.store().approve("review-pass", "approve-command", NOW);
        CommentAction action = fixture.store().requireActionByThread(
                "build-thread");
        CommentAction claimed = fixture.store().claim(
                action.id(), 0, ClaimMode.EXECUTE, "worker", NOW.plusMillis(1),
                NOW.plusSeconds(30));
        fixture.store().recordRecoveryBaseline(
                action.id(), claimed.attemptCount(), List.of("review:80"));
        fixture.store().finishSucceeded(
                action.id(), claimed.attemptCount(), "review:91",
                "exact suggested-change review", NOW.plusMillis(2));
        fixture.jdbc().update("""
                UPDATE dispatch_ticket
                SET version = version + 1,
                    status = 'SUCCEEDED', completed_at_ms = ?,
                    delivery_acceptance = 'ACCEPTED', delivery_evidence = '{}'
                WHERE operation_id = ?
                """, NOW.plusMillis(3).toEpochMilli(), action.operationId());
        fixture.store().finalizeAction(
                action.id(), ActionStatus.SUCCEEDED, NOW.plusMillis(4));
        SqliteDispatchWakeStore wakes = new SqliteDispatchWakeStore(
                fixture.jdbc());
        var claimedWakes = wakes.claimAvailable(
                "purge-worker", NOW.plusMillis(3), NOW.plusSeconds(30), 1);
        assertThat(claimedWakes).hasSize(1);
        assertThat(wakes.markDelivered(
                claimedWakes.getFirst(), NOW.plusMillis(4))).isTrue();
        fixture.jdbc().update("""
                UPDATE threads
                SET lifecycle_state = 'ARCHIVED', aggregate_version = 1
                WHERE id = 'build-thread'
                """);
        assertThat(fixture.jdbc().queryForList("""
                SELECT type || ':' || name
                FROM sqlite_schema
                WHERE sql LIKE '%plan_approval_v280%'
                """, String.class)).isEmpty();
        V2TrunkPurge purge = new V2TrunkPurge(
                fixture.jdbc(), fixture.transactionManager());

        purge.delete("build-thread", 1, () -> fixture.jdbc().update(
                "DELETE FROM threads WHERE id = 'build-thread'"));

        assertThat(fixture.count("review_build_comment_dispatch_v287")).isZero();
        assertThat(fixture.count("review_build_comment_action_v287")).isZero();
        assertThat(fixture.count("review_build_comment_proposal_item_v287"))
                .isZero();
        assertThat(fixture.count("review_build_comment_proposal_v287")).isZero();
        assertThat(fixture.count("threads WHERE id = 'build-thread'"))
                .isZero();
    }

    private Fixture fixture(String name)
    {
        String url = databaseUrl(name);
        MigratedSqliteDatabase.migrate(url);
        DataSource source = dataSource(url);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        seedFrozenSuggestedSelection(jdbc);
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(source);
        return new Fixture(jdbc, source, transactionManager);
    }

    private String databaseUrl(String name)
    {
        return "jdbc:sqlite:" + tempDir.resolve(name)
                + "?foreign_keys=ON&busy_timeout=30000";
    }

    private static DataSource dataSource(String url)
    {
        DataSource source = SqliteTestPools.open(url);
        return source;
    }

    private static void seedFrozenSuggestedSelection(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace', 'Workspace', '', 0, 1, 1)
                """);
        insertThread(jdbc, "review-thread", "review");
        insertThread(jdbc, "build-thread", "build");
        jdbc.update("""
                INSERT INTO review_passes(
                    id, thread_id, repo_full_name, pr_number, head_sha, phase,
                    round, round_cap, cost_cap_milli, cost_usd_milli,
                    created_at_ms, ended_at_ms, host_kind, host_id, kind)
                VALUES ('review-pass', 'review-thread', 'acme/widget', 42,
                    'head-1', 'TERMINATE', 0, 3, 500, 0, 2, 2,
                    'THREAD', 'review-thread', 'FRESH')
                """);
        jdbc.update("""
                INSERT INTO review_findings(
                    id, review_pass_id, path, line, severity, status, body,
                    created_at_ms)
                VALUES
                    ('finding-inline', 'review-pass', 'src/Main.java', 17,
                     'blocker', 'agreed', 'Fix the exact race', 3),
                    ('finding-top', 'review-pass', NULL, NULL,
                     'major', 'arbitrated', 'Architecture note', 4)
                """);
        ReviewBuildSelectionStore selections = new ReviewBuildSelectionStore(
                jdbc, new ObjectMapper().findAndRegisterModules());
        selections.freeze(
                "build-thread", "review-pass", "acme/widget", 42, "head-1",
                new ReviewBuildSelectionStore.SpawnInput(
                        "workspace", "Review PR #42 comments",
                        ReviewBuildSelectionStore.SelectionPolicy.ALL_ELIGIBLE,
                        ReviewBuildSpawnService.MODE_SUGGESTED,
                        "acme/widget", "contributor/widget", "main", "feature"),
                List.of(
                        finding("finding-inline", "src/Main.java", 17,
                                ReviewFindingSeverity.BLOCKER,
                                "Fix the exact race", 3),
                        finding("finding-top", null, null,
                                ReviewFindingSeverity.MAJOR,
                                ReviewFindingStatus.ARBITRATED,
                                "Architecture note", 4)),
                Instant.ofEpochMilli(5));
    }

    private static ReviewFinding finding(
            String id, String path, Integer line,
            ReviewFindingSeverity severity, String body, long createdAt)
    {
        return finding(id, path, line, severity, ReviewFindingStatus.AGREED,
                body, createdAt);
    }

    private static ReviewFinding finding(
            String id, String path, Integer line,
            ReviewFindingSeverity severity, ReviewFindingStatus status,
            String body, long createdAt)
    {
        return new ReviewFinding(
                id, "review-pass", path, line, severity,
                status, body, null, null,
                Instant.ofEpochMilli(createdAt));
    }

    private static void insertThread(
            JdbcTemplate jdbc, String id, String flow)
    {
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model,
                    cost_usd_milli, tokens_in, tokens_out,
                    created_at_ms, updated_at_ms, workspace_id, flow,
                    parallel_slots, turn_version, lifecycle_state,
                    aggregate_version)
                VALUES (?, 'CLI_AGENT', 'codex', ?, 'IDLE', 'gpt-test',
                    0, 0, 0, 1, 1, 'workspace', ?, 1, 'V2', 'IDLE', 0)
                """, id, id, flow);
    }

    private record Fixture(
            JdbcTemplate jdbc,
            DataSource source,
            DataSourceTransactionManager transactionManager)
    {
        SqliteReviewBuildCommentStore store()
        {
            return new SqliteReviewBuildCommentStore(
                    jdbc, new TransactionTemplate(transactionManager),
                    new SqliteDispatchWakeStore(jdbc),
                    new ObjectMapper().findAndRegisterModules());
        }

        SqliteReviewBuildCommentStore restartedStore()
        {
            return store();
        }

        int count(String suffix)
        {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + suffix, Integer.class);
            return count == null ? 0 : count;
        }
    }
}
