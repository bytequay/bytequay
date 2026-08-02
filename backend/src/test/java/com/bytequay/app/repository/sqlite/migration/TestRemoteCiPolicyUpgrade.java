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

import com.bytequay.app.developmentflow.stage.RemoteCiPolicy;
import com.bytequay.app.developmentflow.stage.RemoteObservationRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationRequest;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.migrate;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static org.assertj.core.api.Assertions.assertThat;

class TestRemoteCiPolicyUpgrade
{
    private static final Instant NOW = Instant.ofEpochMilli(1_000);

    @TempDir
    private Path tempDir;

    @Test
    void remoteOwnerAppendsTheDefaultAndSupersedesOldFrozenWork()
            throws Exception
    {
        Fixture fixture = fixture(
                "legacy.db", "PUBLISH_HANDOFF_FAIL_CLOSED", "FAILED");
        fixture.jdbc().update("""
                INSERT INTO remote_ci_required_check(
                    ci_policy_revision_id, check_name)
                VALUES ('ci-policy-1', 'build')
                """);
        ObservationRequest old = fixture.commands().execute("task-1", () ->
                fixture.store().insertObservation(
                        fixture.store().requireRemoteContext(
                                "task-1", "remote-stage-1"),
                        NOW.minusMillis(1)));

        ObservationRequest replay = fixture.coordinator().requestObservation(
                "task-1", "remote-stage-1");
        fixture.coordinator().requestObservation("task-1", "remote-stage-1");

        assertThat(replay.operationId()).isEqualTo(old.operationId());
        assertThat(fixture.jdbc().queryForList("""
                SELECT revision || '|' || source || '|' || skipped_outcome
                FROM remote_ci_policy_revision
                WHERE task_id = 'task-1'
                ORDER BY revision
                """, String.class)).containsExactly(
                        "1|PUBLISH_HANDOFF_FAIL_CLOSED|FAILED",
                        "2|DEFAULT_REPOSITORY_CI_POLICY_V1|ACCEPTED");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM remote_ci_required_check required
                JOIN remote_ci_policy_revision policy
                  ON policy.id = required.ci_policy_revision_id
                WHERE policy.task_id = 'task-1'
                  AND policy.revision = 2
                  AND required.check_name = 'build'
                """, Integer.class)).isOne();
        assertThat(fixture.store().requireRemoteContext(
                "task-1", "remote-stage-1"))
                .satisfies(context -> {
                    assertThat(context.ciPolicyRevisionId())
                            .isNotEqualTo("ci-policy-1");
                    assertThat(context.ciPolicy().outcome(
                            RemoteCiPolicy.CheckState.SKIPPED))
                            .isEqualTo(RemoteCiPolicy.PolicyOutcome.ACCEPTED);
                    assertThat(context.requiredChecks()).containsExactly("build");
                });

        assertThat(fixture.jdbc().update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = '{}',
                    pending_result_evidence = '{}',
                    pending_result_task_epoch = 1,
                    pending_result_stage_id = 'remote-stage-1',
                    pending_result_stage_generation = 1,
                    pending_result_operation_id = ?,
                    pending_result_attempt = 1,
                    pending_result_expected_head_sha = 'head-1',
                    pending_result_expected_base_sha = 'base-1'
                WHERE operation_id = ? AND status = 'REQUESTED'
                """, old.operationId(), old.operationId())).isOne();
        assertThat(fixture.store().requireObservationDelivery(old.operationId()))
                .satisfies(delivery -> {
                    assertThat(delivery.ciPolicyRevisionId())
                            .isEqualTo("ci-policy-1");
                    assertThat(delivery.current()).isFalse();
                });
    }

    @Test
    void remoteOwnerLeavesAnExplicitRepositoryPolicyAlone()
            throws Exception
    {
        Fixture fixture = fixture("repository.db", "REPOSITORY", "FAILED");

        ObservationRequest request = fixture.coordinator().requestObservation(
                "task-1", "remote-stage-1");

        assertThat(fixture.jdbc().queryForList("""
                SELECT revision || '|' || source
                FROM remote_ci_policy_revision
                WHERE task_id = 'task-1'
                """, String.class)).containsExactly("1|REPOSITORY");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT ci_policy_revision_id
                FROM remote_observation_operation
                WHERE operation_id = ?
                """, String.class, request.operationId()))
                .isEqualTo("ci-policy-1");
    }

    private Fixture fixture(String file, String source, String skippedOutcome)
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(file);
        migrate(url);
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
            insertRemoteOwner(connection, 1);
        }
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url + "?foreign_keys=ON&busy_timeout=30000");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO remote_ci_policy_revision(
                    id, task_id, remote_pr_binding_id, revision, source,
                    none_outcome, missing_outcome, queued_outcome,
                    pending_outcome, neutral_outcome, skipped_outcome,
                    canceled_outcome, created_by, created_at_ms)
                VALUES ('ci-policy-1', 'task-1', 'binding-1', 1, ?,
                    'WAITING', 'WAITING', 'WAITING', 'WAITING',
                    'FAILED', ?, 'FAILED', 'test', 55)
                """, source, skippedOutcome);
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        SqliteRemoteRuntimeStore store = new SqliteRemoteRuntimeStore(jdbc);
        RemoteObservationRuntimeCoordinator coordinator =
                new RemoteObservationRuntimeCoordinator(
                        commands, store,
                        (candidate, acceptance) -> {
                            throw new AssertionError(
                                    "No Remote observation is delivered here");
                        },
                        new ObjectMapper(),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(jdbc, commands, store, coordinator);
    }

    private record Fixture(
            JdbcTemplate jdbc,
            TaskCommandExecutor commands,
            SqliteRemoteRuntimeStore store,
            RemoteObservationRuntimeCoordinator coordinator) {}
}
