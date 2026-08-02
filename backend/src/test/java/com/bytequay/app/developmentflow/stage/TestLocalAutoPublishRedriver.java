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
package com.bytequay.app.developmentflow.stage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestLocalAutoPublishRedriver
{
    @TempDir
    private Path tempDir;

    @Test
    void eligibleSubjectUsesOneStableAutomaticPublishCommand()
            throws Exception
    {
        V2PrRemoteControlService controls = mock(V2PrRemoteControlService.class);
        CandidateJdbc jdbc = new CandidateJdbc();
        LocalAutoPublishRedriver redriver = new LocalAutoPublishRedriver(
                jdbc, controls);

        redriver.maintain(Instant.parse("2026-07-29T00:00:00Z"));
        redriver.maintain(Instant.parse("2026-07-29T00:01:00Z"));

        ArgumentCaptor<String> commands = ArgumentCaptor.forClass(String.class);
        verify(controls, times(2)).approveAndShip(
                commands.capture(), eq("task-1"), eq("pr-1"), eq(false));
        assertThat(commands.getAllValues())
                .hasSize(2)
                .containsOnly(commands.getValue());
        assertThat(commands.getValue()).isNotBlank();
    }

    @Test
    void terminalAttemptSuppressesOnlyItsExactPublishSubject()
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("redriver.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createCandidateSchema(jdbc);
        seedCandidate(jdbc);
        jdbc.update("""
                INSERT INTO publish_operation(
                    task_id, task_epoch, local_development_stage_id,
                    stage_generation, code_fingerprint, expected_head_sha,
                    expected_base_sha, status)
                VALUES ('task-1', 1, 'stage-1', 1, 'different-fingerprint',
                    'head-1', 'base-1', 'FAILED')
                """);

        V2PrRemoteControlService controls = mock(V2PrRemoteControlService.class);
        LocalAutoPublishRedriver redriver = new LocalAutoPublishRedriver(
                jdbc, controls);
        redriver.maintain(Instant.parse("2026-07-29T00:00:00Z"));
        verify(controls).approveAndShip(
                anyString(), eq("task-1"), eq("pr-1"), eq(false));

        clearInvocations(controls);
        jdbc.update("""
                UPDATE publish_operation SET code_fingerprint = 'fp-1'
                """);
        redriver.maintain(Instant.parse("2026-07-29T00:01:00Z"));
        verifyNoInteractions(controls);
    }

    private static void createCandidateSchema(JdbcTemplate jdbc)
    {
        jdbc.batchUpdate(
                """
                CREATE TABLE tasks(
                    id TEXT, epoch INTEGER, policy_revision_id TEXT,
                    workflow_version TEXT, lifecycle_state TEXT,
                    created_at_ms INTEGER)
                """,
                """
                CREATE TABLE task_current_stage(
                    task_id TEXT, stage_id TEXT, stage_generation INTEGER)
                """,
                """
                CREATE TABLE stage(
                    id TEXT, generation INTEGER, version INTEGER, kind TEXT,
                    checkpoint TEXT, completed_at_ms INTEGER)
                """,
                """
                CREATE TABLE local_development_stage(
                    stage_id TEXT, generation INTEGER, opened_for_epoch INTEGER)
                """,
                "CREATE TABLE task_policy_revision(id TEXT, auto_approve INTEGER)",
                """
                CREATE TABLE dev_report(
                    id TEXT, local_development_stage_id TEXT, revision INTEGER,
                    workflow_version TEXT, task_epoch INTEGER,
                    stage_generation INTEGER, code_fingerprint TEXT,
                    head_sha TEXT, base_sha TEXT)
                """,
                """
                CREATE TABLE validation_operation(
                    id TEXT, dev_report_id TEXT, semantic_attempt INTEGER,
                    status TEXT)
                """,
                """
                CREATE TABLE validation_evidence(
                    id TEXT, validation_operation_id TEXT, passed INTEGER)
                """,
                """
                CREATE TABLE brain_review_episode(
                    id TEXT, dev_report_id TEXT, semantic_attempt INTEGER,
                    status TEXT, verdict TEXT, unresolved_finding_count INTEGER)
                """,
                "CREATE TABLE pr(id TEXT, task_id TEXT, origin TEXT, status TEXT)",
                """
                CREATE TABLE task_blocker(
                    task_id TEXT, stage_id TEXT, status TEXT, blocker_type TEXT)
                """,
                """
                CREATE TABLE publish_operation(
                    task_id TEXT, task_epoch INTEGER,
                    local_development_stage_id TEXT, stage_generation INTEGER,
                    code_fingerprint TEXT, expected_head_sha TEXT,
                    expected_base_sha TEXT, status TEXT)
                """);
    }

    private static void seedCandidate(JdbcTemplate jdbc)
    {
        jdbc.batchUpdate(
                """
                INSERT INTO tasks VALUES (
                    'task-1', 1, 'policy-1', 'V2', 'ACTIVE', 1)
                """,
                "INSERT INTO task_current_stage VALUES ('task-1', 'stage-1', 1)",
                """
                INSERT INTO stage VALUES (
                    'stage-1', 1, 7, 'LOCAL_DEVELOPMENT', 'LOCAL_REVIEW', NULL)
                """,
                "INSERT INTO local_development_stage VALUES ('stage-1', 1, 1)",
                "INSERT INTO task_policy_revision VALUES ('policy-1', 1)",
                """
                INSERT INTO dev_report VALUES (
                    'report-1', 'stage-1', 1, 'V2', 1, 1,
                    'fp-1', 'head-1', 'base-1')
                """,
                """
                INSERT INTO validation_operation VALUES (
                    'validation-operation-1', 'report-1', 1, 'COMPLETED')
                """,
                """
                INSERT INTO validation_evidence VALUES (
                    'validation-1', 'validation-operation-1', 1)
                """,
                """
                INSERT INTO brain_review_episode VALUES (
                    'brain-1', 'report-1', 1, 'SUCCEEDED', 'APPROVED', 0)
                """,
                "INSERT INTO pr VALUES ('pr-1', 'task-1', 'task', 'local-open')");
    }

    private static final class CandidateJdbc
            extends JdbcTemplate
    {
        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args)
        {
            try {
                ResultSet row = mock(ResultSet.class);
                when(row.getString(anyString())).thenAnswer(invocation -> switch (
                        invocation.getArgument(0, String.class)) {
                    case "task_id" -> "task-1";
                    case "policy_revision_id" -> "policy-1";
                    case "stage_id" -> "stage-1";
                    case "report_id" -> "report-1";
                    case "validation_id" -> "validation-1";
                    case "brain_id" -> "brain-1";
                    case "pr_id" -> "pr-1";
                    default -> null;
                });
                when(row.getLong("task_epoch")).thenReturn(1L);
                when(row.getLong("generation")).thenReturn(1L);
                when(row.getLong("stage_version")).thenReturn(7L);
                return List.of(mapper.mapRow(row, 0));
            }
            catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }
}
