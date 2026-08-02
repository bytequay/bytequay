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

import com.bytequay.app.developmentflow.execution.remote.SqliteExternalPrActionStore;
import com.bytequay.app.developmentflow.execution.remote.SqliteExternalPrActionStore.AuthorizationRequest;
import com.bytequay.app.developmentflow.execution.remote.SqliteExternalPrActionStore.UnwatchedRepositoryException;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.Action;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionPayload;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionStatus;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.SemanticAction;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchWakeStore;
import com.bytequay.app.testing.SqliteTestPools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.execute;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.migrate;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.number;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SqliteTestPools.class)
class TestExternalPrActionRuntimeMigration
{
    private static final Instant NOW = Instant.ofEpochMilli(1_000);

    @TempDir
    private Path tempDir;

    @Test
    void createsZeroTaskReviewTrunkAndReplaysStableCommand()
            throws Exception
    {
        String url = database("external-action.db");
        migrate(url);
        seedExternalPr(url);
        SqliteExternalPrActionStore store = store(url);
        AuthorizationRequest request = approve("approve-command");

        Action authorized = store.authorize(request, NOW);
        Action replay = store(url).authorize(request, NOW.plusMillis(1));

        assertThat(replay.id()).isEqualTo(authorized.id());
        assertThat(authorized.taskId()).isNull();
        assertThat(authorized.remotePrBindingId()).isNotBlank();
        assertThat(authorized.headSha()).isEqualTo("head-1");
        assertThat(authorized.baseSha()).isEqualTo("base-1");
        JdbcTemplate jdbc = jdbc(url);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM threads trunk
                WHERE trunk.id = ? AND trunk.flow = 'review'
                  AND trunk.turn_version = 'V2'
                  AND trunk.pr_ref = 'acme/widget#17'
                  AND NOT EXISTS (
                      SELECT 1 FROM tasks task WHERE task.thread_id = trunk.id)
                """, Integer.class, authorized.remotePrBindingId())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM dispatch_ticket
                WHERE operation_id = ?
                  AND operation_kind = 'APPLY_V2_EXTERNAL_PR_ACTION'
                  AND owner_kind = 'TRUNK' AND task_id IS NULL
                  AND stage_id IS NULL AND async_family = 'GITHUB_EFFECT'
                  AND expected_head_sha = 'head-1'
                  AND expected_base_sha = 'base-1'
                """, Integer.class, authorized.operationId())).isOne();
        assertThat(store.findLatestProjection("external-pr").orElseThrow()
                .status()).isEqualTo("QUEUED");
        assertThat(store.findLatestProjection("external-pr").orElseThrow()
                .blocksNewPublication()).isTrue();
    }

    @Test
    void changedCachedHeadAbandonsBeforeAnyEffectAndFinalizationFailsClosed()
            throws Exception
    {
        String url = database("external-action-stale.db");
        migrate(url);
        seedExternalPr(url);
        SqliteExternalPrActionStore store = store(url);
        Action authorized = store.authorize(approve("approve-command"), NOW);
        jdbc(url).update("""
                UPDATE pr_detail SET head_sha = 'head-2' WHERE pr_id = 101
                """);

        Action stale = store.claim(
                authorized.id(), 0, ClaimMode.EXECUTE, "worker",
                NOW.plusSeconds(1), NOW.plusSeconds(31));

        assertThat(stale.status()).isEqualTo(ActionStatus.ABANDONED);
        assertThatThrownBy(() -> store.markFinalized(
                stale.id(), ActionStatus.ABANDONED, NOW.plusSeconds(2)))
                .hasMessageContaining("accepted delivery");
        assertThat(store.findLatestProjection("external-pr").orElseThrow()
                .blocksNewPublication()).isTrue();
    }

    @Test
    void unwatchedRepositoryFailsWithItsOwnActionableReason()
            throws Exception
    {
        String url = database("external-action-unwatched.db");
        migrate(url);
        seedExternalPr(url);
        jdbc(url).update("DELETE FROM workspace_repos");

        assertThatThrownBy(() -> store(url).authorize(approve("approve"), NOW))
                .isInstanceOf(UnwatchedRepositoryException.class)
                .hasMessage("ByteQuay must watch acme/widget"
                        + " before publishing to its pull requests");
    }

    @Test
    void migrationPersistsBothSidesOfTheCachedCommitIdentity()
            throws Exception
    {
        String url = database("external-action-schema.db");
        migrate(url);
        try (Connection connection = connect(url)) {
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM pragma_table_info('pr_detail')
                    WHERE name IN ('base_sha', 'merge_commit_sha')
                    """)).isEqualTo(2);
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM sqlite_master
                    WHERE type = 'table' AND name IN (
                        'external_pr_action_v289',
                        'external_pr_action_draft_v289',
                        'external_pr_action_dispatch_v289')
                    """)).isEqualTo(3);
        }
    }

    private static AuthorizationRequest approve(String commandId)
    {
        return new AuthorizationRequest(
                commandId, "external-pr", null, SemanticAction.SUBMIT_REVIEW,
                new ActionPayload(1, "", "APPROVE", null, List.of()),
                "APPROVED");
    }

    private static void seedExternalPr(String url)
            throws Exception
    {
        try (Connection connection = connect(url)) {
            execute(connection, """
                    INSERT INTO workspaces(
                        id, name, memory_md, is_scratch,
                        created_at_ms, updated_at_ms)
                    VALUES ('workspace-1', 'Widget', '', 0, 1, 1)
                    """);
            execute(connection, """
                    INSERT INTO workspace_repos(
                        workspace_id, repo_full_name, added_at_ms)
                    VALUES ('workspace-1', 'acme/widget', 1)
                    """);
            execute(connection, """
                    INSERT INTO pr(
                        id, task_id, branch_name, base_branch, title,
                        description, status, created_at_ms, remote_pr_number,
                        origin, repo)
                    VALUES ('external-pr', NULL, 'feature', 'main', 'Change',
                        '', 'remote-open', 1, 17, 'external', 'acme/widget')
                    """);
            execute(connection, """
                    INSERT INTO pull_requests(
                        id, repo, number, title, origin)
                    VALUES (101, 'acme/widget', 17, 'Change', 'authored')
                    """);
            execute(connection, """
                    INSERT INTO pr_detail(
                        pr_id, body, labels, draft, additions, deletions,
                        changed_files, requested_reviewer_count,
                        requested_reviewers, head_sha, head_ref, head_repo,
                        base_ref, base_repo, state, merged, synced_at,
                        base_sha)
                    VALUES (101, '', '[]', 0, 1, 1, 1, 0, '[]', 'head-1',
                        'feature', 'fork/widget', 'main', 'acme/widget',
                        'open', 0, '2026-07-29T00:00:00Z', 'base-1')
                    """);
        }
    }

    private SqliteExternalPrActionStore store(String url)
    {
        DataSource dataSource = dataSource(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new SqliteExternalPrActionStore(
                jdbc,
                new TransactionTemplate(
                        new DataSourceTransactionManager(dataSource)),
                new SqliteDispatchWakeStore(jdbc),
                new ObjectMapper());
    }

    private static JdbcTemplate jdbc(String url)
    {
        return new JdbcTemplate(dataSource(url));
    }

    private static DataSource dataSource(String url)
    {
        DataSource dataSource = SqliteTestPools.open(url);
        return dataSource;
    }

    private String database(String name)
    {
        return "jdbc:sqlite:" + tempDir.resolve(name)
                + "?foreign_keys=ON&busy_timeout=30000";
    }
}
