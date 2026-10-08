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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.review.ReviewFinding;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteReviewFindingSchemaUpdate
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"), "bytequay-review-finding-schema-update-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private SqliteAgentReviewStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        createPreviousFindingTable();
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON", "update");
    }

    @Test
    void addsRequiredOriginToPopulatedFindingTable()
    {
        ReviewFinding finding = store.findFinding("finding-1").orElseThrow();

        assertThat(finding.origin()).isEqualTo(ReviewFinding.Origin.AUTOMATIC);
        assertThat(finding.title()).isEqualTo("Existing finding");
        assertThat(store.listFindings("review-1")).containsExactly(finding);
        assertThat(jdbc.queryForMap("SELECT \"notnull\", dflt_value FROM pragma_table_info('agent_review_finding') WHERE name = 'origin'"))
                .containsEntry("notnull", 1)
                .containsEntry("dflt_value", "'AUTOMATIC'");
    }

    private static void createPreviousFindingTable()
    {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE_PATH);
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE agent_review_finding (
                        id varchar(255) NOT NULL PRIMARY KEY,
                        review_id varchar(255) NOT NULL,
                        run_id varchar(255) NOT NULL,
                        ordinal integer NOT NULL,
                        title TEXT NOT NULL,
                        body TEXT NOT NULL,
                        severity varchar(255),
                        anchor_json TEXT NOT NULL,
                        evidence_json TEXT NOT NULL,
                        memories_json TEXT,
                        revision integer NOT NULL,
                        state varchar(255) NOT NULL CHECK (state IN ('OPEN', 'RESOLVED', 'WITHDRAWN')),
                        state_reason TEXT,
                        validated_head_sha varchar(255) NOT NULL,
                        outdated boolean NOT NULL,
                        version bigint NOT NULL,
                        created_at varchar(255) NOT NULL,
                        updated_at varchar(255) NOT NULL)
                    """);
            statement.execute("""
                    INSERT INTO agent_review_finding (
                        id, review_id, run_id, ordinal, title, body, anchor_json, evidence_json, revision,
                        state, validated_head_sha, outdated, version, created_at, updated_at)
                    VALUES ('finding-1', 'review-1', 'run-1', 1, 'Existing finding', 'Existing body', 'null', '[]', 1,
                        'OPEN', 'head-sha', false, 0, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')
                    """);
        }
        catch (SQLException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
