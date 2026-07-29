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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAgentRunRetirementMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void preservesHistoryAndAllowsOnlyImmutableReviewCompatibilityHeaders()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("agent-run-retirement.db")
                + "?foreign_keys=ON&busy_timeout=30000";
        migrate(url, "289");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url));
        jdbc.update("""
                INSERT INTO agent_run(
                    id, kind, status, iterations, started_at_ms)
                VALUES ('historical-run', 'dev', 'failed', 1, 1)
                """);

        migrate(url, "290");

        assertThat(jdbc.queryForObject(
                "SELECT status FROM agent_run WHERE id = 'historical-run'",
                String.class)).isEqualTo("failed");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE agent_run SET headline = 'rewritten'
                WHERE id = 'historical-run'
                """))
                .hasMessageContaining("AgentRun rows are immutable after V2 cutover");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO agent_run(
                    id, kind, status, iterations, started_at_ms)
                VALUES ('new-legacy-run', 'dev', 'queued', 0, 2)
                """))
                .hasMessageContaining("AgentRun creation is retired");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO agent_run(
                    id, kind, source, review_round_id, status, iterations,
                    started_at_ms, finished_at_ms, workspace_id,
                    cost_usd_milli, tokens_in, tokens_out, step_cursor, outcome)
                VALUES (
                    'owned-review-header', 'review_compatibility_header',
                    'v2_review_assignment_turn_fk', 'future-round',
                    'succeeded', 0, 3, 3, 'ws-default',
                    0, 0, 0, 0, 'completed')
                """))
                .hasMessageContaining("AgentRun creation is retired");

        jdbc.update("""
                INSERT INTO agent_run(
                    id, kind, source, review_round_id, status, iterations,
                    started_at_ms, finished_at_ms, cost_usd_milli,
                    tokens_in, tokens_out, step_cursor, outcome)
                VALUES (
                    'review-header', 'review_compatibility_header',
                    'v2_review_assignment_turn_fk', 'future-round',
                    'succeeded', 0, 3, 3, 0, 0, 0, 0, 'completed')
                """);
        assertThat(jdbc.queryForObject(
                "SELECT source FROM agent_run WHERE id = 'review-header'",
                String.class)).isEqualTo("v2_review_assignment_turn_fk");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE agent_run SET workspace_id = NULL
                WHERE id = 'review-header'
                """))
                .hasMessageContaining("AgentRun rows are immutable after V2 cutover");
    }

    private static void migrate(String url, String target)
    {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(url, "", "")
                .locations("classpath:db/migration")
                .javaMigrations(
                        new BackfillTurnLiveness(),
                        new BackfillLocalReviewSubmissions(new ObjectMapper()),
                        new NormalizeDeadLifecycleStates())
                .target(target);
        configuration.load().migrate();
    }
}
