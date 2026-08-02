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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TestPlanAutomationApprovalMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void installsAttributedAutomationApprovalWithoutBreakingForeignKeys()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("automation-plan.db")
                + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure()
                .dataSource(url, "", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url));

        String table = jdbc.queryForObject("""
                SELECT sql FROM sqlite_master
                WHERE type = 'table' AND name = 'plan_approval'
                """, String.class);
        assertThat(table).contains("'HUMAN', 'POLICY', 'AUTOMATION'");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'trigger' AND name IN (
                    'plan_approval_fence_insert', 'plan_approval_immutable')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_foreign_key_check",
                Integer.class)).isZero();
    }
}
