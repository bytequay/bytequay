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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteContextDataCleanup
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_PATH = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-context-cleanup-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private SqliteContextDataCleanup cleanup;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON", "update");
    }

    @Test
    void deletesTheRemovedNoteDataOnceAndKeepsEverythingElse()
    {
        for (String key : new String[] {"workspace.repositoryContext.w1", "workspace.repositoryContextLearningPlan.w1", "workspace.contextUsageTrackingSince.w1",
                "workspace.specChecks.w1", "workspace.agentModel.w1", "workspace.RepositoryContext.w1"}) {
            jdbc.update("INSERT INTO app_settings (setting_key, setting_value) VALUES (?, '{}')", key);
        }
        for (String table : new String[] {"context_grade", "context_usage_fact", "development_context_receipt"}) {
            jdbc.execute("CREATE TABLE " + table + " (id TEXT PRIMARY KEY)");
        }
        jdbc.execute("ALTER TABLE agent_review_run ADD COLUMN context_json TEXT");

        cleanup.clean();
        cleanup.clean();

        assertThat(jdbc.queryForList("SELECT setting_key FROM app_settings ORDER BY setting_key", String.class))
                .containsExactly("workspace.RepositoryContext.w1", "workspace.agentModel.w1", "workspace.specChecks.w1");
        assertThat(jdbc.queryForList("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('context_grade', 'context_usage_fact', "
                + "'development_context_receipt')", String.class)).isEmpty();
        assertThat(jdbc.queryForList("SELECT name FROM pragma_table_info('agent_review_run')", String.class)).contains("evidence_json").doesNotContain("context_json");
    }
}
