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

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.acceptSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.assertFails;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.execute;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertCiPolicy;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertFailedCi;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.number;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.text;
import static org.assertj.core.api.Assertions.assertThat;

class TestTaskBranchRepairableCiClassificationMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void preservesHistoricalEpisodesAndAdmitsOnlyTheNewExplicitClassification()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("classification.db")
                + "?foreign_keys=ON";
        Flyway.configure().dataSource(url, "", "").target("325").load()
                .migrate();
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
            insertSnapshot(
                    connection, 1, 1, "head-1", "base-1", "OPEN", "UNKNOWN");
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");
            insertFailedCi(connection, 1, 1, "head-1", "base-1");
            execute(connection, """
                    INSERT INTO ci_repair_episode(
                        id, remote_development_stage_id, task_id, task_epoch,
                        stage_generation, remote_pr_binding_id,
                        failed_ci_evaluation_id, subject_head_sha,
                        subject_base_sha, classification, status,
                        rerun_limit, fix_attempt_limit, delivery_retry_limit,
                        push_limit, opened_at_ms, completed_at_ms, stop_reason)
                    VALUES ('historical-episode', 'remote-stage-1', 'task-1', 1,
                        1, 'binding-1', 'ci-evaluation-1-1', 'head-1',
                        'base-1', 'TASK_DETERMINISTIC', 'STOPPED', 0, 2, 2, 2,
                        80, 81, 'historical')
                    """);
            execute(connection, """
                    INSERT INTO ci_repair_control_command(
                        id, ci_repair_episode_id, command_id, kind, actor,
                        reason, created_at_ms, consumed_at_ms)
                    VALUES ('historical-command', 'historical-episode',
                        'historical-stop', 'STOP_AUTOMATION', 'user',
                        'historical', 81, 81)
                    """);
        }

        Flyway.configure().dataSource(url, "", "").load().migrate();

        try (Connection connection = connect(url)) {
            assertThat(text(connection, """
                    SELECT classification || '|' || status
                    FROM ci_repair_episode WHERE id = 'historical-episode'
                    """)).isEqualTo("TASK_DETERMINISTIC|STOPPED");
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM ci_repair_control_command
                    WHERE ci_repair_episode_id = 'historical-episode'
                    """)).isOne();
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
            assertThat(text(connection, """
                    SELECT sql FROM sqlite_master
                    WHERE type = 'table' AND name = 'ci_repair_episode'
                    """)).contains("'TASK_BRANCH_REPAIRABLE'");
            assertThat(text(connection, """
                    SELECT group_concat(name, '|')
                    FROM (
                        SELECT name FROM sqlite_master
                        WHERE type = 'trigger'
                          AND tbl_name = 'ci_repair_episode'
                        ORDER BY name)
                    """)).isEqualTo(String.join("|",
                            "ci_repair_episode_budget_update",
                            "ci_repair_episode_counter_update",
                            "ci_repair_episode_exhaustion",
                            "ci_repair_episode_identity_immutable",
                            "ci_repair_episode_insert",
                            "ci_repair_episode_push_result",
                            "ci_repair_episode_push_subject",
                            "ci_repair_episode_terminal_immutable",
                            "ci_repair_episode_terminal_result"));
            assertThat(text(connection, """
                    SELECT sql FROM sqlite_master
                    WHERE type = 'index'
                      AND name = 'idx_ci_repair_one_live_subject'
                      AND tbl_name = 'ci_repair_episode'
                    """))
                    .contains("CREATE UNIQUE INDEX")
                    .contains("remote_development_stage_id, subject_head_sha")
                    .contains("WHERE status NOT IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED')");

            execute(connection, """
                    INSERT INTO ci_repair_episode(
                        id, remote_development_stage_id, task_id, task_epoch,
                        stage_generation, remote_pr_binding_id,
                        failed_ci_evaluation_id, subject_head_sha,
                        subject_base_sha, classification, status,
                        rerun_limit, fix_attempt_limit, delivery_retry_limit,
                        push_limit, opened_at_ms)
                    VALUES ('append-only-episode', 'remote-stage-1', 'task-1', 1,
                        1, 'binding-1', 'ci-evaluation-1-1', 'head-1',
                        'base-1', 'TASK_BRANCH_REPAIRABLE', 'OPEN', 0, 2, 2, 2,
                        90)
                    """);
            assertFails(connection, """
                    UPDATE ci_repair_episode
                    SET classification = 'TASK_DETERMINISTIC'
                    WHERE id = 'append-only-episode'
                    """);
            assertFails(connection, """
                    INSERT INTO ci_repair_episode(
                        id, remote_development_stage_id, task_id, task_epoch,
                        stage_generation, remote_pr_binding_id,
                        failed_ci_evaluation_id, subject_head_sha,
                        subject_base_sha, classification, status,
                        rerun_limit, fix_attempt_limit, delivery_retry_limit,
                        push_limit, opened_at_ms)
                    VALUES ('invalid-episode', 'remote-stage-1', 'task-1', 1,
                        1, 'binding-1', 'ci-evaluation-1-1', 'head-1',
                        'base-1', 'MIXED', 'OPEN', 0, 2, 2, 2, 91)
                    """);
        }
    }
}
