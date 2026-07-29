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

import com.bytequay.app.developmentflow.execution.remote.SqliteUserRemoteActionStore;
import com.bytequay.app.developmentflow.execution.remote.SqliteUserRemoteActionStore.AuthorizationRequest;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.Action;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionKind;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionPayload;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionStatus;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.FrozenDraft;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.SemanticAction;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchWakeStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.acceptSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.execute;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.migrate;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.number;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDevelopmentFlowUserRemoteActionMigration
{
    private static final Instant NOW = Instant.ofEpochMilli(1_000);

    @TempDir
    private Path tempDir;

    @Test
    void freshDatabaseCreatesTheNarrowActionProtocol()
            throws Exception
    {
        String url = database("fresh-user-actions.db");
        migrate(url, "270");

        try (Connection connection = connect(url)) {
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM sqlite_master
                    WHERE type = 'table' AND name IN (
                        'v2_user_remote_action_v270',
                        'v2_user_remote_action_draft_v270',
                        'v2_user_remote_action_dispatch_v270')
                    """)).isEqualTo(3);
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM sqlite_master
                    WHERE type = 'trigger'
                      AND name = 'v2_trunk_purge_user_remote_action_guard_v270'
                    """)).isOne();
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
        }
        assertThat(jdbc(url).queryForObject("""
                SELECT sql FROM sqlite_master
                WHERE type = 'table' AND name = 'v2_user_remote_action_v270'
                """, String.class))
                .contains("attempt_count <= attempt_limit");
    }

    @Test
    void fullUpgradeThroughV284KeepsTheCurrentCodeViewValid()
            throws Exception
    {
        String url = database("v284-after-ci-trigger.db");

        migrate(url, "284");

        try (Connection connection = connect(url)) {
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM sqlite_master
                    WHERE type = 'view'
                      AND name = 'task_current_code_subject_v230'
                      AND sql LIKE '%remote_worktree_subject%'
                      AND sql NOT LIKE '%remote_worktree_subject_v248_pre_v283%'
                    """)).isOne();
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM sqlite_master
                    WHERE type = 'trigger'
                      AND name = 'task_pause_evidence_insert'
                    """)).isOne();
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM task_current_code_subject_v230
                    """)).isZero();
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
        }
    }

    @Test
    void upgradeAuthorizesOneExactHeadAndDeduplicatesAcrossRestart()
            throws Exception
    {
        String url = upgradedDatabase("upgrade-user-actions.db");
        SqliteUserRemoteActionStore store = store(url);
        AuthorizationRequest request = commentRequest("comment-command", "ship it");

        Action authorized = store.authorize(request, NOW);
        Action duplicate = store(url).authorize(request, NOW.plusMillis(1));

        assertThat(duplicate.id()).isEqualTo(authorized.id());
        assertThat(authorized.status()).isEqualTo(ActionStatus.REQUESTED);
        assertThat(authorized.remotePrBindingId()).isEqualTo("binding-1");
        assertThat(authorized.headSha()).isEqualTo("head-1");
        assertThat(authorized.baseSha()).isEqualTo("base-1");
        JdbcTemplate jdbc = jdbc(url);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM v2_user_remote_action_v270",
                Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM dispatch_ticket
                WHERE operation_kind = 'APPLY_V2_USER_REMOTE_ACTION'
                  AND owner_kind = 'TASK' AND owner_id = 'task-1'
                  AND async_family = 'GITHUB_EFFECT' AND lane_mask = 32
                  AND stage_id = 'remote-stage-1'
                  AND expected_head_sha = 'head-1'
                  AND expected_base_sha = 'base-1'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox wake
                JOIN v2_user_remote_action_dispatch_v270 dispatch
                  ON dispatch.dispatch_ticket_id = wake.aggregate_id
                WHERE wake.topic = 'V2_DISPATCH_TICKET_REQUESTED'
                """, Integer.class)).isOne();

        try (Connection connection = connect(url)) {
            insertSnapshot(connection, 1, 2, "head-2", "base-1", "OPEN",
                    "MERGEABLE");
            acceptSnapshot(connection, 1, 2, "head-2", "base-1");
        }
        Action stale = store(url).claim(
                authorized.id(), 0, ClaimMode.EXECUTE, "worker", NOW.plusSeconds(1),
                NOW.plusSeconds(31));
        assertThat(stale.status()).isEqualTo(ActionStatus.ABANDONED);
        assertThat(jdbc.queryForObject("""
                SELECT last_error FROM v2_user_remote_action_v270 WHERE id = ?
                """, String.class, authorized.id())).contains("stale");

        Action replay = store(url).authorize(request, NOW.plusSeconds(2));
        assertThat(replay.id()).isEqualTo(authorized.id());
        assertThat(replay.headSha()).isEqualTo("head-1");

        Action current = store(url).authorize(
                commentRequest("next-comment-command", "ship it"),
                NOW.plusSeconds(2));
        assertThat(current.id()).isNotEqualTo(authorized.id());
        assertThat(current.headSha()).isEqualTo("head-2");
    }

    @Test
    void reviewFreezesOnlyExactDraftsAndReservesThemUntilFinalization()
            throws Exception
    {
        String url = upgradedDatabase("review-user-actions.db");
        try (Connection connection = connect(url)) {
            execute(connection, """
                    INSERT INTO pr_comment(
                        id, pr_id, origin, scope, file_path, line_number,
                        author, body, created_at_ms, side)
                    VALUES ('draft-1', 'pr-1', 'local', 'file-line',
                        'src/A.java', 12, 'user', 'change this', 900, 'RIGHT')
                    """);
        }
        FrozenDraft draft = new FrozenDraft(
                "draft-1", "file-line", "src/A.java", 12, "RIGHT",
                null, null, "change this", null);
        AuthorizationRequest request = new AuthorizationRequest(
                "task-1", "review-command", "pr-1", ActionKind.SUBMIT_REVIEW,
                new ActionPayload(
                        1, "summary", "REQUEST_CHANGES", null, List.of(draft)),
                "CHANGES_REQUESTED");

        Action action = store(url).authorize(request, NOW);
        assertThat(action.payload().drafts()).containsExactly(draft);
        assertThat(jdbc(url).queryForObject("""
                SELECT COUNT(*) FROM v2_user_remote_action_draft_v270
                WHERE action_id = ? AND comment_id = 'draft-1'
                  AND body = 'change this'
                """, Integer.class, action.id())).isOne();

        AuthorizationRequest competing = new AuthorizationRequest(
                "task-1", "competing-command", "pr-1", ActionKind.SUBMIT_REVIEW,
                new ActionPayload(1, "other", "COMMENT", null, List.of(draft)),
                "COMMENTED");
        assertThatThrownBy(() -> store(url).authorize(
                competing, NOW.plusMillis(1)))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc(url).queryForObject(
                "SELECT COUNT(*) FROM v2_user_remote_action_v270",
                Integer.class)).isOne();
    }

    @Test
    void mergedV2PrAuthorizesOnlyAnExactTopLevelComment()
            throws Exception
    {
        String url = upgradedDatabase("merged-comment-user-actions.db");
        try (Connection connection = connect(url)) {
            insertSnapshot(connection, 1, 2, "head-1", "base-1", "MERGED",
                    "MERGEABLE");
            acceptSnapshot(connection, 1, 2, "head-1", "base-1");
            execute(connection, """
                    UPDATE pr SET status = 'merged', merged_at_ms = 2000
                    WHERE id = 'pr-1'
                    """);
        }

        SqliteUserRemoteActionStore store = store(url);
        Action comment = store.authorize(
                commentRequest("merged-comment-command", "after merge"), NOW);

        assertThat(comment.status()).isEqualTo(ActionStatus.REQUESTED);
        assertThat(comment.headSha()).isEqualTo("head-1");
        assertThatThrownBy(() -> store.authorize(new AuthorizationRequest(
                "task-1", "merged-review-command", "pr-1",
                ActionKind.SUBMIT_REVIEW,
                new ActionPayload(
                        1, "", "APPROVE", null, List.of()),
                "APPROVED"), NOW.plusMillis(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact Remote subject");
    }

    @Test
    void terminalCommandReplayKeepsItsExactResultAndRejectsReuse()
            throws Exception
    {
        String url = upgradedDatabase("command-replay-user-actions.db");
        SqliteUserRemoteActionStore store = store(url);
        AuthorizationRequest request = commentRequest(
                "lost-response-command", "posted once");
        Action action = store.authorize(request, NOW);
        Action claimed = store.claim(
                action.id(), 0, ClaimMode.EXECUTE, "worker", NOW.plusMillis(1),
                NOW.plusSeconds(30));
        store.recordRecoveryBaseline(
                action.id(), claimed.attemptCount(), List.of("issue-comment:80"));
        store.finishSucceeded(
                action.id(), claimed.attemptCount(), "issue-comment:91",
                "exact proof", NOW.plusMillis(2));
        store.markFinalized(
                action.id(), ActionStatus.SUCCEEDED, NOW.plusMillis(3));

        Action replay = store(url).authorize(request, NOW.plusSeconds(1));

        assertThat(replay.id()).isEqualTo(action.id());
        assertThat(replay.operationId()).isEqualTo(action.operationId());
        assertThat(replay.status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(replay.externalEffectId()).isEqualTo("issue-comment:91");
        assertThat(jdbc(url).queryForObject(
                "SELECT COUNT(*) FROM v2_user_remote_action_v270",
                Integer.class)).isOne();
        assertThatThrownBy(() -> store(url).authorize(
                commentRequest("lost-response-command", "different"),
                NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reused for a different request");

        Action separate = store(url).authorize(
                commentRequest("intentional-second-command", "posted once"),
                NOW.plusSeconds(3));
        assertThat(separate.id()).isNotEqualTo(action.id());
    }

    @Test
    void restartReclaimsOnlyAnExpiredClaimForProbeWithoutSpendingAnAttempt()
            throws Exception
    {
        String url = upgradedDatabase("reclaim-user-actions.db");
        SqliteUserRemoteActionStore firstProcess = store(url);
        Action action = firstProcess.authorize(
                commentRequest("recover-command", "recover me"), NOW);
        Action claimed = firstProcess.claim(
                action.id(), 0, ClaimMode.EXECUTE, "worker-1",
                NOW.plusSeconds(1), NOW.plusSeconds(31));
        assertThat(claimed.attemptCount()).isOne();

        SqliteUserRemoteActionStore restarted = store(url);
        assertThatThrownBy(() -> restarted.claim(
                action.id(), 1, ClaimMode.PROBE, "worker-2",
                NOW.plusSeconds(30), NOW.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim lost");

        Action reclaimed = restarted.claim(
                action.id(), 1, ClaimMode.PROBE, "worker-2",
                NOW.plusSeconds(31), NOW.plusSeconds(61));

        assertThat(reclaimed.status()).isEqualTo(ActionStatus.CLAIMED);
        assertThat(reclaimed.attemptCount()).isOne();
        restarted.finishSucceeded(
                action.id(), 1, "issue-comment:91", "exact proof",
                NOW.plusSeconds(32));
        assertThat(restarted.require(action.operationId()).status())
                .isEqualTo(ActionStatus.SUCCEEDED);
    }

    @Test
    void exhaustedActionBecomesFinalizableAndBlocksPurgeUntilThen()
            throws Exception
    {
        String url = upgradedDatabase("exhausted-user-actions.db");
        SqliteUserRemoteActionStore store = store(url);
        Action action = store.authorize(
                commentRequest("retry-command", "retry me"), NOW);

        for (int attempt = 1; attempt <= 3; attempt++) {
            Action claimed = store.claim(
                    action.id(), attempt - 1, ClaimMode.PROBE,
                    "worker-" + attempt, NOW.plusSeconds(attempt),
                    NOW.plusSeconds(attempt + 30));
            store.finishIndeterminate(
                    action.id(), claimed.attemptCount(), "not proven",
                    NOW.plusSeconds(attempt + 1));
        }
        Action exhausted = store.require(action.operationId());
        assertThat(exhausted.status()).isEqualTo(ActionStatus.ABANDONED);

        try (Connection connection = connect(url)) {
            execute(connection, """
                    UPDATE threads
                    SET lifecycle_state = 'ARCHIVED', aggregate_version = 1
                    WHERE id = 'trunk-1'
                    """);
            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO v2_trunk_purge_authorization_v269(
                        trunk_id, archived_version, authorized_at_ms)
                    VALUES ('trunk-1', 1, 2000)
                    """))
                    .hasMessageContaining(
                            "V2 Trunk purge cannot race a nonterminal user remote action");
        }

        store.markFinalized(action.id(), ActionStatus.ABANDONED,
                NOW.plusSeconds(10));
        assertThat(jdbc(url).queryForObject("""
                SELECT COUNT(*) FROM v2_user_remote_action_v270
                WHERE id = ? AND status = 'ABANDONED'
                  AND completed_at_ms IS NOT NULL AND finalized_at_ms IS NOT NULL
                """, Integer.class, action.id())).isOne();
    }

    @Test
    void v282BackfillsLegacyRowsAndKeepsThemReadable()
            throws Exception
    {
        String url = upgradedDatabaseAt270("legacy-semantic-action.db");
        try (Connection connection = connect(url)) {
            execute(connection, """
                    INSERT INTO v2_user_remote_action_v270(
                        id, operation_id, task_id, command_id, task_epoch,
                        remote_stage_id, stage_generation,
                        remote_pr_binding_id, pr_id, kind,
                        remote_repository_id, head_repository_id,
                        remote_pr_number, branch_name, expected_head_sha,
                        expected_base_sha, payload_json, payload_digest,
                        handled_action, attempt_limit, authorized_by,
                        authorized_at_ms, status)
                    VALUES (
                        'legacy-action', 'legacy-operation', 'task-1',
                        'legacy-command', 1, 'remote-stage-1', 1,
                        'binding-1', 'pr-1', 'POST_TOP_LEVEL_COMMENT',
                        'acme/widget', 'acme/widget', 41, 'dev/task-1',
                        'head-1', 'base-1',
                        '{"version":1,"body":"legacy","reviewAction":null,'
                            || '"branchName":null,"drafts":[]}',
                        lower(hex(randomblob(32))), 'COMMENTED', 3, 'user',
                        1000, 'REQUESTED')
                    """);
        }

        migrateSemanticActionProtocol(url);

        Action legacy = store(url).requireById("legacy-action");
        assertThat(legacy.kind()).isEqualTo(ActionKind.POST_TOP_LEVEL_COMMENT);
        assertThat(legacy.semanticAction())
                .isEqualTo(SemanticAction.POST_TOP_LEVEL_COMMENT);
        assertThat(legacy.payload().body()).isEqualTo("legacy");
    }

    @Test
    void parityActionsUseOneFencedWireProtocolAndStableCommandIdentity()
            throws Exception
    {
        String url = upgradedDatabase("parity-user-actions.db");
        SqliteUserRemoteActionStore store = store(url);
        List<ParityCase> cases = List.of(
                parity(SemanticAction.RERUN_FAILED_CHECKS,
                        ActionPayload.empty()),
                parity(SemanticAction.SET_DRAFT_STATE,
                        ActionPayload.selected(null, true)),
                parity(SemanticAction.UPDATE_TITLE,
                        ActionPayload.value("New title")),
                parity(SemanticAction.UPDATE_BODY,
                        ActionPayload.body("New body")),
                parity(SemanticAction.CLOSE_PULL_REQUEST,
                        ActionPayload.empty()),
                parity(SemanticAction.COMMENT_AND_CLOSE,
                        ActionPayload.body("Closing")),
                parity(SemanticAction.REPLY_REVIEW_THREAD,
                        ActionPayload.targetBody("10", "Reply")),
                parity(SemanticAction.EDIT_ISSUE_COMMENT,
                        ActionPayload.targetBody("11", "Edited")),
                parity(SemanticAction.EDIT_REVIEW_COMMENT,
                        ActionPayload.targetBody("12", "Edited")),
                parity(SemanticAction.DELETE_ISSUE_COMMENT,
                        ActionPayload.target("13")),
                parity(SemanticAction.DELETE_REVIEW_COMMENT,
                        ActionPayload.target("14")),
                parity(SemanticAction.ADD_REVIEWER,
                        ActionPayload.value("alice")),
                parity(SemanticAction.REMOVE_REVIEWER,
                        ActionPayload.value("bob")),
                parity(SemanticAction.SET_ASSIGNEE,
                        ActionPayload.selected("alice", true)),
                parity(SemanticAction.SET_LABEL,
                        ActionPayload.selected("bug", false)),
                parity(SemanticAction.CREATE_INLINE_COMMENT,
                        ActionPayload.inlineComment(
                                "Inline", "src/A.java", 12, "RIGHT",
                                null, null)),
                parity(SemanticAction.REACT_PULL_REQUEST,
                        ActionPayload.value("heart")),
                parity(SemanticAction.REACT_REVIEW_COMMENT,
                        ActionPayload.targetValue("15", "rocket")),
                parity(SemanticAction.REACT_ISSUE_COMMENT,
                        ActionPayload.targetValue("16", "+1")),
                parity(SemanticAction.SET_THREAD_RESOLUTION,
                        ActionPayload.targetSelected("17", true)));

        for (int index = 0; index < cases.size(); index++) {
            ParityCase parity = cases.get(index);
            String commandId = "parity-command-" + index;
            Action authorized = store.authorize(new AuthorizationRequest(
                    "task-1", commandId, "pr-1", parity.action(),
                    parity.payload(), null), NOW.plusMillis(index));
            Action replay = store(url).authorize(new AuthorizationRequest(
                    "task-1", commandId, "pr-1", parity.action(),
                    parity.payload(), null), NOW.plusSeconds(1));

            assertThat(authorized.kind()).isEqualTo(ActionKind.DEQUEUE);
            assertThat(authorized.semanticAction()).isEqualTo(parity.action());
            assertThat(replay.id()).isEqualTo(authorized.id());
            assertThat(authorized.stageId()).isEqualTo("remote-stage-1");
            assertThat(authorized.headSha()).isEqualTo("head-1");
            assertThat(authorized.baseSha()).isEqualTo("base-1");
        }
        assertThat(jdbc(url).queryForObject("""
                SELECT COUNT(*) FROM v2_user_remote_action_v270
                WHERE kind = 'DEQUEUE' AND semantic_action <> 'DEQUEUE'
                """, Integer.class)).isEqualTo(cases.size());
        assertThatThrownBy(() -> store.authorize(new AuthorizationRequest(
                "task-1", "parity-command-0", "pr-1",
                SemanticAction.UPDATE_BODY, ActionPayload.body("different"),
                null), NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reused for a different request");
    }

    @Test
    void emptyCommitCiTriggerOwnsBothLanesAndAdvancesWorktreeIdentity()
            throws Exception
    {
        String url = upgradedDatabase("empty-commit-ci-trigger.db");
        SqliteUserRemoteActionStore store = store(url);
        AuthorizationRequest request = new AuthorizationRequest(
                "task-1", "trigger-ci-command", "pr-1",
                SemanticAction.TRIGGER_CI_EMPTY_COMMIT,
                ActionPayload.empty(), null);

        Action action = store.authorize(request, NOW);

        assertThat(action.worktreePath()).isNotBlank();
        assertThat(action.expectedCodeFingerprint()).isNotBlank();
        assertThat(jdbc(url).queryForObject("""
                SELECT COUNT(*) FROM dispatch_ticket
                WHERE operation_id = ? AND async_family = 'GITHUB_EFFECT'
                  AND lane_mask = 48 AND exclusive_task = 1
                  AND writer_required = 1
                  AND expected_code_fingerprint = ?
                  AND expected_head_sha = 'head-1'
                  AND expected_base_sha = 'base-1'
                """, Integer.class, action.operationId(),
                action.expectedCodeFingerprint())).isOne();

        Action claimed = store.claim(
                action.id(), 0, ClaimMode.EXECUTE, "worker", NOW.plusMillis(1),
                NOW.plusSeconds(30));
        store.finishSucceeded(
                action.id(), claimed.attemptCount(),
                "ci-trigger-empty-commit:head-2", "exact empty commit proof",
                NOW.plusMillis(2));

        JdbcTemplate jdbc = jdbc(url);
        assertThat(jdbc.queryForMap("""
                SELECT source_kind, source_operation_id, code_fingerprint,
                       head_sha, base_sha, revision
                FROM remote_worktree_subject
                WHERE source_operation_id = ?
                """, action.operationId()))
                .containsEntry("source_kind", "USER_CI_TRIGGER")
                .containsEntry("code_fingerprint",
                        action.expectedCodeFingerprint())
                .containsEntry("head_sha", "head-2")
                .containsEntry("base_sha", "base-1")
                .containsEntry("revision", 1);
        assertThat(jdbc.queryForObject("""
                SELECT head_sha FROM task_current_code_subject_v230
                WHERE task_id = 'task-1'
                """, String.class)).isEqualTo("head-2");
        assertThat(store(url).authorize(request, NOW.plusSeconds(1)).id())
                .isEqualTo(action.id());
    }

    @Test
    void titleAuthorizationRejectsTheLegacyUiOverflowLimit()
            throws Exception
    {
        SqliteUserRemoteActionStore store = store(
                upgradedDatabase("title-limit-user-actions.db"));

        assertThatThrownBy(() -> store.authorize(new AuthorizationRequest(
                "task-1", "long-title", "pr-1",
                SemanticAction.UPDATE_TITLE, ActionPayload.value("x".repeat(257)),
                null), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256");
    }

    private String upgradedDatabase(String name)
            throws Exception
    {
        String url = upgradedDatabaseAt270(name);
        migrateSemanticActionProtocol(url);
        return url;
    }

    private String upgradedDatabaseAt270(String name)
            throws Exception
    {
        String url = database(name);
        migrate(url, "228");
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
        }
        migrate(url, "269");
        migrate(url, "270");
        try (Connection connection = connect(url)) {
            execute(connection, """
                    UPDATE pr
                    SET status = 'remote-open', pushed_at_ms = 51,
                        remote_pr_number = 41,
                        remote_pr_url = 'https://example/pr/1',
                        repo = 'acme/widget'
                    WHERE id = 'pr-1'
                    """);
            insertRemoteOwner(connection, 1);
            insertSnapshot(connection, 1, 1, "head-1", "base-1", "OPEN",
                    "MERGEABLE");
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");
        }
        return url;
    }

    private void migrateSemanticActionProtocol(String url)
            throws Exception
    {
        Path migrations = tempDir.resolve("v282-v283-migrations");
        Files.createDirectories(migrations);
        for (String migration : List.of(
                "V282__user_remote_action_semantic_identity.sql",
                "V283__push_driven_ci_trigger.sql")) {
            Path destination = migrations.resolve(migration);
            if (!Files.exists(destination)) {
                try (InputStream source = requireNonNull(
                        getClass().getResourceAsStream(
                                "/db/migration/" + migration))) {
                    Files.copy(source, destination);
                }
            }
        }
        Flyway.configure()
                .dataSource(url, null, null)
                .locations("filesystem:" + migrations)
                .validateOnMigrate(false)
                .load()
                .migrate();
    }

    private static AuthorizationRequest commentRequest(String body)
    {
        return commentRequest("comment-command", body);
    }

    private static ParityCase parity(
            SemanticAction action, ActionPayload payload)
    {
        return new ParityCase(action, payload);
    }

    private static AuthorizationRequest commentRequest(
            String commandId, String body)
    {
        return new AuthorizationRequest(
                "task-1", commandId, "pr-1",
                ActionKind.POST_TOP_LEVEL_COMMENT,
                new ActionPayload(1, body, null, null, List.of()),
                "COMMENTED");
    }

    private static SqliteUserRemoteActionStore store(String url)
    {
        SQLiteDataSource dataSource = dataSource(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new SqliteUserRemoteActionStore(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new SqliteDispatchWakeStore(jdbc),
                new ObjectMapper());
    }

    private static JdbcTemplate jdbc(String url)
    {
        return new JdbcTemplate(dataSource(url));
    }

    private static SQLiteDataSource dataSource(String url)
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return dataSource;
    }

    private String database(String name)
    {
        return "jdbc:sqlite:" + tempDir.resolve(name)
                + "?foreign_keys=ON&busy_timeout=30000";
    }

    private record ParityCase(
            SemanticAction action, ActionPayload payload) {}
}
