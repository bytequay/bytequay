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
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePublishResultStore;
import com.bytequay.app.testing.SqliteTestPools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.Map;

import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.CANCELED;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.FAILED;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.MISSING;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.NEUTRAL;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.NONE;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.PASSED;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.PENDING;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.QUEUED;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.SKIPPED;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.PolicyOutcome.ACCEPTED;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.PolicyOutcome.WAITING;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.migrate;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SqliteTestPools.class)
class TestPublishCiPolicyPersistence
{
    private static final Instant OPENED_AT = Instant.ofEpochMilli(52);

    @TempDir
    private Path tempDir;

    @Test
    void defaultRepositoryPolicyCoversEveryNormalizedState()
    {
        RemoteCiPolicy.Policy policy =
                RemoteCiPolicy.DEFAULT_REPOSITORY_CI_POLICY_V1;

        assertThat(Map.of(
                NONE, policy.outcome(NONE),
                MISSING, policy.outcome(MISSING),
                QUEUED, policy.outcome(QUEUED),
                PENDING, policy.outcome(PENDING),
                PASSED, policy.outcome(PASSED),
                FAILED, policy.outcome(FAILED),
                NEUTRAL, policy.outcome(NEUTRAL),
                SKIPPED, policy.outcome(SKIPPED),
                CANCELED, policy.outcome(CANCELED)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        NONE, WAITING,
                        MISSING, WAITING,
                        QUEUED, WAITING,
                        PENDING, WAITING,
                        PASSED, ACCEPTED,
                        FAILED, RemoteCiPolicy.PolicyOutcome.FAILED,
                        NEUTRAL, RemoteCiPolicy.PolicyOutcome.FAILED,
                        SKIPPED, ACCEPTED,
                        CANCELED, RemoteCiPolicy.PolicyOutcome.FAILED));
    }

    @Test
    void publishHandoffFreezesAndExactlyReplaysTheDefaultPolicy()
            throws Exception
    {
        StoreFixture fixture = fixture("exact-policy.db");

        fixture.initialize();
        fixture.initialize();

        assertThat(fixture.jdbc().queryForObject("""
                SELECT source || '|' || none_outcome || '|' || missing_outcome
                    || '|' || queued_outcome || '|' || pending_outcome
                    || '|' || neutral_outcome || '|' || skipped_outcome
                    || '|' || canceled_outcome || '|' || created_by
                    || '|' || created_at_ms
                FROM remote_ci_policy_revision WHERE id = 'ci-policy-1'
                """, String.class)).isEqualTo(
                        "DEFAULT_REPOSITORY_CI_POLICY_V1|WAITING|WAITING|"
                                + "WAITING|WAITING|FAILED|ACCEPTED|FAILED|"
                                + "v2-publish-delivery|52");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM remote_ci_policy_revision
                WHERE task_id = 'task-1'
                """, Integer.class)).isOne();
    }

    @Test
    void publishHandoffRejectsAConflictingPersistedPolicyReplay()
            throws Exception
    {
        StoreFixture fixture = fixture("conflicting-policy.db");
        fixture.jdbc().update("""
                INSERT INTO remote_ci_policy_revision(
                    id, task_id, remote_pr_binding_id, revision, source,
                    none_outcome, missing_outcome, queued_outcome,
                    pending_outcome, neutral_outcome, skipped_outcome,
                    canceled_outcome, created_by, created_at_ms)
                VALUES ('ci-policy-1', 'task-1', 'binding-1', 1,
                    'DEFAULT_REPOSITORY_CI_POLICY_V1', 'WAITING', 'WAITING',
                    'WAITING', 'WAITING', 'FAILED', 'FAILED', 'FAILED',
                    'v2-publish-delivery', 52)
                """);

        assertThatThrownBy(fixture::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Exact publish delivery evidence is missing");
    }

    private StoreFixture fixture(String fileName)
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(fileName)
                + "?foreign_keys=ON&busy_timeout=30000";
        migrate(url);
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
        }
        DataSource dataSource = SqliteTestPools.open(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new StoreFixture(jdbc, new SqlitePublishResultStore(jdbc));
    }

    private record StoreFixture(
            JdbcTemplate jdbc,
            SqlitePublishResultStore store)
    {
        private void initialize()
        {
            store.initializeRemote(
                    store.requireContext("publish-operation-id-1"),
                    "binding-1",
                    new StageManager.State(
                            "remote-stage-1", "task-1",
                            StageKind.REMOTE_DEVELOPMENT, 1, 0,
                            StageCheckpoint.WAITING_CI, null, null),
                    "ci-policy-1", "automation-policy-1", OPENED_AT);
        }
    }
}
