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
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.ActionsJobLogEvidence;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.CanonicalDiagnostic;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.CheckComparison;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.CheckEvidence;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.CheckProfile;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.PullRequestAssociation;
import com.bytequay.app.testing.SqliteTestPools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.acceptSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.execute;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertCiPolicy;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertFailedCi;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.number;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SqliteTestPools.class)
class TestStrictActionsJobLogCiProofMigration
{
    private static final String RAW_LOG_SENTINEL =
            "RAW_ACTIONS_JOB_LOG_MUST_NOT_BE_PERSISTED";

    @TempDir
    private Path tempDir;

    @Test
    void snapshotGateAcceptsOnlySchemasThreeThroughFiveWithoutRawLog()
            throws Exception
    {
        String url = seededDatabase("strict-ci-proof.db");
        try (Connection connection = connect(url)) {
            insertSnapshot(connection, 1, "{\"schemaVersion\":3,\"complete\":true}");
            insertSnapshot(connection, 2, "{\"schemaVersion\":4,\"complete\":true}");
            String schemaFive = schemaFiveProvenance();
            assertThat(schemaFive)
                    .contains(sha256(RAW_LOG_SENTINEL))
                    .doesNotContain(RAW_LOG_SENTINEL);
            insertSnapshot(connection, 3, schemaFive);

            assertThatThrownBy(() -> insertSnapshot(
                    connection, 4, "{\"schemaVersion\":2,\"complete\":true}"))
                    .hasMessageContaining(
                            "Remote CI provenance is not schema v3, v4, or v5");
            assertThatThrownBy(() -> insertSnapshot(
                    connection, 4, "{\"schemaVersion\":6,\"complete\":true}"))
                    .hasMessageContaining(
                            "Remote CI provenance is not schema v3, v4, or v5");

            assertThat(text(connection, """
                    SELECT group_concat(schema_version, ',')
                    FROM (
                        SELECT json_extract(
                                   ci_provenance_json, '$.schemaVersion')
                                   AS schema_version
                        FROM remote_pr_snapshot
                        ORDER BY observation_revision)
                    """)).isEqualTo("3,4,5");
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM remote_pr_snapshot
                    WHERE instr(ci_provenance_json,
                        'RAW_ACTIONS_JOB_LOG_MUST_NOT_BE_PERSISTED') > 0
                    """)).isZero();
        }
    }

    @Test
    void baseRepairGateAcceptsSchemaFiveAndRejectsCorruptVersions()
            throws Exception
    {
        String url = seededDatabase("strict-base-repair-proof.db");
        try (Connection connection = connect(url)) {
            insertSnapshot(connection, 1, schemaFiveProvenance());
            insertFailedCi(connection, 1, 1, "head-1", "base-1");
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");
            insertBaseEpisode(connection);

            insertBaseManifest(connection, "manifest-v5");
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM ci_base_repair_manifest_v303
                    WHERE id = 'manifest-v5'
                    """)).isOne();

            execute(connection, "DELETE FROM ci_base_repair_manifest_v303");
            // Isolate the downstream V321 repair gate from snapshot admission.
            execute(connection, "DROP TRIGGER remote_pr_snapshot_immutable");
            execute(connection,
                    "DROP TRIGGER remote_pr_snapshot_ci_provenance_v321");
            for (int invalidVersion : List.of(2, 6)) {
                execute(connection, """
                        UPDATE remote_pr_snapshot
                        SET ci_provenance_json = json_set(
                            ci_provenance_json, '$.schemaVersion', %s)
                        WHERE id = 'snapshot-1-1'
                        """.formatted(invalidVersion));
                assertThatThrownBy(() -> insertBaseManifest(
                        connection, "manifest-v" + invalidVersion))
                        .hasMessageContaining(
                                "Base repair requires exact complete typed CI proof");
            }
        }
    }

    private String seededDatabase(String fileName)
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(fileName)
                + "?foreign_keys=ON";
        DataSource dataSource = SqliteTestPools.open(url);
        Flyway.configure().dataSource(dataSource).target("320").load().migrate();
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
        }
        Flyway.configure().dataSource(dataSource).load().migrate();
        return url;
    }

    private static void insertSnapshot(
            Connection connection, int revision, String provenance)
            throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO remote_pr_snapshot(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id, observation_revision,
                    observation_key, remote_repository_id, remote_pr_number,
                    head_sha, base_sha, pr_state, mergeability,
                    merge_queue_state, observed_at_ms, raw_evidence,
                    ci_provenance_json)
                VALUES (?, 'remote-stage-1', 'task-1', 1, 1, 'binding-1', ?, ?,
                    'acme/widget', 41, 'head-1', 'base-1', 'OPEN', 'MERGEABLE',
                    'NONE', ?, '{}', ?)
                """)) {
            statement.setString(1, "snapshot-1-" + revision);
            statement.setInt(2, revision);
            statement.setString(3, "observation-1-" + revision);
            statement.setLong(4, 60L + revision);
            statement.setString(5, provenance);
            statement.executeUpdate();
        }
    }

    private static String schemaFiveProvenance()
            throws Exception
    {
        CanonicalDiagnostic diagnostic = new CanonicalDiagnostic(
                "backend/src/test/java/TestWidget.java", "COMPILATION", "",
                "cannot find symbol", "class RetiredWorkflow", "");
        byte[] rawLog = RAW_LOG_SENTINEL.getBytes(StandardCharsets.UTF_8);
        ActionsJobLogEvidence logEvidence = new ActionsJobLogEvidence(
                RemoteCiProvenance.ACTIONS_JOB_LOG_SOURCE,
                RemoteCiProvenance.MAVEN_COMPILER_PARSER,
                RemoteCiProvenance.MAVEN_COMPILER_PARSER_VERSION,
                101L, 1, 9_001L, 7_001L, "head-1", rawLog.length,
                sha256(RAW_LOG_SENTINEL), true, true, List.of(diagnostic));
        CheckEvidence head = new CheckEvidence(
                "github-check:7001",
                new CheckProfile(
                        15_368L, "github-actions", 7L,
                        ".github/workflows/ci.yml", "build"),
                11L, 11L, 101L, 1, "head-1", "head-1", "pull_request",
                RemoteCiPolicy.CheckState.FAILED, true,
                Set.of(RemoteCiProvenance.canonicalFingerprint(diagnostic)),
                new PullRequestAssociation(41, "head-1", "base-1"),
                null, logEvidence);
        RemoteCiProvenance provenance = new RemoteCiProvenance(
                5, "acme/widget", 41, "head-1", "base-1", null, true,
                List.of(), List.of(new CheckComparison(head, null)));
        return new ObjectMapper().writeValueAsString(provenance);
    }

    private static String sha256(String value)
            throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void insertBaseEpisode(Connection connection)
            throws Exception
    {
        execute(connection, """
                INSERT INTO ci_repair_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    failed_ci_evaluation_id, subject_head_sha, subject_base_sha,
                    classification, status, rerun_limit, fix_attempt_limit,
                    delivery_retry_limit, push_limit, opened_at_ms)
                VALUES ('base-episode', 'remote-stage-1', 'task-1', 1, 1,
                    'binding-1', 'ci-evaluation-1-1', 'head-1', 'base-1',
                    'BASE_DETERMINISTIC', 'OPEN', 0, 2, 2, 2, 80)
                """);
    }

    private static void insertBaseManifest(
            Connection connection, String manifestId)
            throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ci_base_repair_manifest_v303(
                    id, ci_repair_episode_id, failed_ci_evaluation_id,
                    remote_pr_snapshot_id, subject_head_sha, subject_base_sha,
                    subject_manifest_json, manifest_digest, created_at_ms)
                VALUES (?, 'base-episode', 'ci-evaluation-1-1',
                    'snapshot-1-1', 'head-1', 'base-1', '{}',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    81)
                """)) {
            statement.setString(1, manifestId);
            statement.executeUpdate();
        }
    }
}
