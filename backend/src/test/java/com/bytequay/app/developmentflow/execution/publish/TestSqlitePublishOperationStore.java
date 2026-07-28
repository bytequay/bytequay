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
package com.bytequay.app.developmentflow.execution.publish;

import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.EffectClaim;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.EffectEvidence;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.EffectKind;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.OperationSnapshot;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.StepStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqlitePublishOperationStore
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    private Path tempDir;

    @Test
    void reloadsTheExactOperationGraphAfterRestart()
    {
        Path database = tempDir.resolve("publish-store.db");
        JdbcTemplate jdbc = jdbc(database);
        createSchema(jdbc);
        seed(jdbc);

        OperationSnapshot before = new SqlitePublishOperationStore(jdbc)
                .requireByOperationId("operation-1");
        OperationSnapshot restarted = new SqlitePublishOperationStore(jdbc(database))
                .requireByOperationId("operation-1");

        assertThat(restarted).isEqualTo(before);
        assertThat(restarted.request().stageCheckpoint()).isEqualTo("PUBLISHING");
        assertThat(restarted.request().authorizationActive()).isTrue();
        assertThat(restarted.steps()).extracting(step -> step.kind())
                .containsExactly(EffectKind.values());
    }

    @Test
    void claimsAndFinishesOnlyTheExactPersistedAttempt()
            throws Exception
    {
        JdbcTemplate jdbc = jdbc(tempDir.resolve("claim.db"));
        createSchema(jdbc);
        seed(jdbc);
        SqlitePublishOperationStore store = new SqlitePublishOperationStore(jdbc);
        var step = store.requireByOperationId("operation-1").steps().getFirst();

        EffectClaim claim = store.tryClaim(
                        step, ClaimMode.EXECUTE, "worker-1", NOW,
                        NOW.plusSeconds(60))
                .orElseThrow();

        assertThat(store.tryClaim(
                step, ClaimMode.EXECUTE, "worker-2", NOW,
                NOW.plusSeconds(60))).isEmpty();
        EffectClaim wrong = new EffectClaim(
                claim.step(), claim.mode(), claim.attempt(), claim.attemptLimit(),
                "worker-2", claim.claimedAt(), claim.leaseUntil());
        assertThat(store.finish(
                wrong, StepStatus.SUCCEEDED, "{}", null, NOW.plusSeconds(1)))
                .isFalse();

        String evidence = JSON.writeValueAsString(EffectEvidence.local(
                EffectKind.VERIFY_SUBJECT, "exact"));
        assertThat(store.finish(
                claim, StepStatus.SUCCEEDED, evidence, null, NOW.plusSeconds(1)))
                .isTrue();
        var persisted = new SqlitePublishOperationStore(jdbc)
                .requireByOperationId("operation-1").steps().getFirst();
        assertThat(persisted.status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(persisted.attemptCount()).isEqualTo(1);
        assertThat(persisted.evidenceJson()).isEqualTo(evidence);
    }

    @Test
    void expiredClaimCanBeReplacedOnlyFromItsExactSnapshot()
    {
        JdbcTemplate jdbc = jdbc(tempDir.resolve("expired.db"));
        createSchema(jdbc);
        seed(jdbc);
        jdbc.update("""
                UPDATE publish_effect_step
                SET status = 'CLAIMED', attempt_count = 1,
                    claim_mode = 'EXECUTE', claim_owner = 'dead-worker',
                    claimed_at_ms = ?, lease_until_ms = ?
                WHERE ordinal = 1
                """, NOW.minusSeconds(120).toEpochMilli(),
                NOW.minusSeconds(60).toEpochMilli());
        SqlitePublishOperationStore store = new SqlitePublishOperationStore(jdbc);
        var expired = store.requireByOperationId("operation-1").steps().getFirst();

        EffectClaim probe = store.tryClaim(
                        expired, ClaimMode.PROBE, "recovery", NOW,
                        NOW.plusSeconds(60))
                .orElseThrow();

        assertThat(probe.mode()).isEqualTo(ClaimMode.PROBE);
        assertThat(probe.attempt()).isEqualTo(2);
        assertThat(store.tryClaim(
                expired, ClaimMode.PROBE, "duplicate", NOW,
                NOW.plusSeconds(60))).isEmpty();
    }

    @Test
    void missingOperationFailsClosed()
    {
        JdbcTemplate jdbc = jdbc(tempDir.resolve("missing.db"));
        createSchema(jdbc);

        assertThatThrownBy(() -> new SqlitePublishOperationStore(jdbc)
                .requireByOperationId("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("found 0");
    }

    private static JdbcTemplate jdbc(Path file)
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + file);
        return new JdbcTemplate(dataSource);
    }

    private static void createSchema(JdbcTemplate jdbc)
    {
        jdbc.execute("CREATE TABLE threads(id TEXT PRIMARY KEY, workspace_id TEXT NOT NULL)");
        jdbc.execute("""
                CREATE TABLE tasks(
                    id TEXT PRIMARY KEY, thread_id TEXT NOT NULL,
                    workflow_version TEXT NOT NULL, lifecycle_state TEXT NOT NULL,
                    epoch INTEGER NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE stage(
                    id TEXT PRIMARY KEY, checkpoint TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE task_current_stage(
                    task_id TEXT PRIMARY KEY, stage_id TEXT NOT NULL,
                    stage_generation INTEGER NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE task_code_identity(
                    task_id TEXT PRIMARY KEY, worktree_path TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE promotion_manifest(
                    id TEXT PRIMARY KEY, pr_id TEXT NOT NULL, route TEXT NOT NULL,
                    base_repository_id TEXT NOT NULL,
                    head_repository_id TEXT NOT NULL,
                    publish_repository_id TEXT NOT NULL,
                    branch_name TEXT NOT NULL, head_ref TEXT NOT NULL,
                    base_branch TEXT NOT NULL, pr_title TEXT NOT NULL,
                    pr_body TEXT NOT NULL, pr_content_revision INTEGER NOT NULL,
                    pr_content_digest TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE publish_authorization(
                    id TEXT PRIMARY KEY, manifest_id TEXT NOT NULL,
                    revoked_at_ms INTEGER, consumed_at_ms INTEGER)
                """);
        jdbc.execute("""
                CREATE TABLE publish_operation(
                    id TEXT PRIMARY KEY, operation_id TEXT NOT NULL,
                    publish_authorization_id TEXT NOT NULL,
                    local_development_stage_id TEXT NOT NULL,
                    task_id TEXT NOT NULL, task_epoch INTEGER NOT NULL,
                    stage_generation INTEGER NOT NULL,
                    semantic_attempt INTEGER NOT NULL, status TEXT NOT NULL,
                    code_fingerprint TEXT NOT NULL,
                    expected_head_sha TEXT NOT NULL,
                    expected_base_sha TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE publish_effect_step(
                    id TEXT PRIMARY KEY, publish_operation_id TEXT NOT NULL,
                    ordinal INTEGER NOT NULL, kind TEXT NOT NULL,
                    status TEXT NOT NULL, attempt_count INTEGER NOT NULL,
                    attempt_limit INTEGER NOT NULL, claim_mode TEXT,
                    claim_owner TEXT, claimed_at_ms INTEGER,
                    lease_until_ms INTEGER, evidence TEXT, last_error TEXT,
                    completed_at_ms INTEGER)
                """);
    }

    private void seed(JdbcTemplate jdbc)
    {
        jdbc.update("INSERT INTO threads VALUES ('trunk-1', 'workspace-1')");
        jdbc.update("INSERT INTO tasks VALUES ('task-1', 'trunk-1', 'V2', 'ACTIVE', 1)");
        jdbc.update("INSERT INTO stage VALUES ('stage-1', 'PUBLISHING')");
        jdbc.update("INSERT INTO task_current_stage VALUES ('task-1', 'stage-1', 1)");
        jdbc.update("INSERT INTO task_code_identity VALUES ('task-1', ?)",
                tempDir.resolve("worktree").toAbsolutePath().normalize().toString());
        jdbc.update("""
                INSERT INTO promotion_manifest VALUES (
                    'manifest-1', 'pr-1', 'DIRECT',
                    'acme/widget', 'acme/widget', 'acme/widget',
                    'dev/task-1', 'dev/task-1', 'main',
                    'Fix widget', 'Approved body', 1, 'digest')
                """);
        jdbc.update("INSERT INTO publish_authorization VALUES ('auth-1', 'manifest-1', NULL, NULL)");
        jdbc.update("""
                INSERT INTO publish_operation VALUES (
                    'publish-1', 'operation-1', 'auth-1', 'stage-1', 'task-1',
                    1, 1, 1, 'DISPATCHED', 'fingerprint', 'head-sha', 'base-sha')
                """);
        int ordinal = 1;
        for (EffectKind kind : EffectKind.values()) {
            jdbc.update("""
                    INSERT INTO publish_effect_step VALUES (
                        ?, 'publish-1', ?, ?, 'REQUESTED', 0, 3,
                        NULL, NULL, NULL, NULL, NULL, NULL, NULL)
                    """, "step-" + ordinal, ordinal, kind.name());
            ordinal++;
        }
    }
}
