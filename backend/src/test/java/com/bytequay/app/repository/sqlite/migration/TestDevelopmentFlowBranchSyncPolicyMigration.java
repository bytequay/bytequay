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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.acceptSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.execute;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertCiPolicy;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.migrate;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDevelopmentFlowBranchSyncPolicyMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void freezesTheExactEnabledTaskPolicyIntoEachNewEpisode()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("branch-policy.db");
        migrate(url);
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
            execute(connection, """
                    INSERT INTO tasks(
                        id, thread_id, seq, status, phase, created_at_ms,
                        workflow_version)
                    VALUES ('legacy-task', 'trunk-1', 2, 'IDLE',
                        'IMPLEMENTING', 2, 'LEGACY')
                    """);
        }
        migrate(url);
        try (Connection connection = connect(url)) {
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
            insertSnapshot(
                    connection, 1, 1, "head-1", "base-1", "OPEN",
                    "MERGEABLE");
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");
        }
        migrate(url);

        try (Connection connection = connect(url)) {
            assertThatThrownBy(() -> execute(connection, policy(
                    "legacy-policy", "legacy-task", 1, 1,
                    "USER_CONFIGURED", 3, "legacy-command")))
                    .hasMessageContaining("current V2 Task");
            execute(connection, policy(
                    "disabled-policy", "task-1", 1, 0,
                    "USER_CONFIGURED", 3, "disable-command"));
            assertThatThrownBy(() -> execute(connection, episode(
                    "disabled-episode", "disabled-policy",
                    "USER_CONFIGURED", 3)))
                    .hasMessageContaining("exact scheduled or CI authority");

            execute(connection, policy(
                    "enabled-policy", "task-1", 2, 1,
                    "USER_CONFIGURED", 3, "enable-command"));
            execute(connection, episode(
                    "enabled-episode", "enabled-policy",
                    "USER_CONFIGURED", 3));

            assertThatThrownBy(() -> execute(connection, """
                    UPDATE task_branch_sync_policy_revision
                    SET enabled = 0 WHERE id = 'enabled-policy'
                    """))
                    .hasMessageContaining("policy revision is immutable");
            assertThatThrownBy(() -> execute(connection, """
                    UPDATE branch_sync_episode
                    SET branch_sync_policy_revision_id = 'disabled-policy'
                    WHERE id = 'enabled-episode'
                    """))
                    .hasMessageContaining("Branch sync subject is immutable");
            assertThatThrownBy(() -> execute(connection, policy(
                    "unbounded-policy", "task-1", 3, 1,
                    "USER_CONFIGURED", 11, "unbounded-command")))
                    .hasMessageContaining("CHECK constraint failed");

            assertThat(DevelopmentFlowRemoteProtocolFixture.text(
                    connection, """
                            SELECT branch_sync_policy_revision_id
                            FROM branch_sync_episode
                            WHERE id = 'enabled-episode'
                            """))
                    .isEqualTo("enabled-policy");
        }
    }

    private static String policy(
            String id,
            String taskId,
            int revision,
            int enabled,
            String source,
            int attempts,
            String commandId)
    {
        return """
                INSERT INTO task_branch_sync_policy_revision(
                    id, task_id, revision, enabled, schedule, source,
                    attempt_limit, command_id, actor, created_at_ms)
                VALUES ('%s', '%s', %s, %s, 'nightly', '%s', %s,
                    '%s', 'user', 1000)
                """.formatted(
                id, taskId, revision, enabled, source, attempts, commandId);
    }

    private static String episode(
            String id, String policyId, String source, int attempts)
    {
        return """
                INSERT INTO branch_sync_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id, source_snapshot_id,
                    old_head_sha, observed_base_sha, target_base_sha,
                    policy_source, purpose, authority_kind, authority_id,
                    status, attempt_limit, opened_at_ms,
                    branch_sync_policy_revision_id)
                VALUES ('%s', 'remote-stage-1', 'task-1', 1, 1, 'binding-1',
                    'snapshot-1-1', 'head-1', 'base-1', 'base-2', '%s',
                    'SCHEDULED', 'BRANCH_SYNC_POLICY', '%s',
                    'OPEN', %s, 1000, '%s')
                """.formatted(id, source, policyId, attempts, policyId);
    }
}
