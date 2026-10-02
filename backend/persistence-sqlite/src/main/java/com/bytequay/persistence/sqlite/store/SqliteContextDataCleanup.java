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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Deletes what the removed repository-context notes left behind: their settings, their three tables and the review run column
 * that held a run's context snapshot. Nothing is migrated. Each step does nothing once done, so it runs at every start.
 */
// ponytail: runs on every start; delete this class once every installation has started a build that includes it.
@Component
public class SqliteContextDataCleanup
{
    private static final Logger log = LoggerFactory.getLogger(SqliteContextDataCleanup.class);
    private static final List<String> TABLES = List.of("context_grade", "context_usage_fact", "development_context_receipt");

    private final JdbcTemplate jdbc;

    public SqliteContextDataCleanup(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    // After startup, so Hibernate has created the tables a new database needs.
    @EventListener(ApplicationReadyEvent.class)
    public void clean()
    {
        try {
            // GLOB matches case-sensitively, unlike LIKE.
            jdbc.update("DELETE FROM app_settings WHERE setting_key GLOB 'workspace.repositoryContext*' OR setting_key GLOB 'workspace.contextUsageTrackingSince.*'");
            TABLES.forEach(table -> jdbc.execute("DROP TABLE IF EXISTS " + table));
            if (!jdbc.queryForList("SELECT name FROM pragma_table_info('agent_review_run') WHERE name = 'context_json'", String.class).isEmpty()) {
                jdbc.execute("ALTER TABLE agent_review_run DROP COLUMN context_json");
            }
        }
        catch (DataAccessException failure) {
            log.warn("Removed repository context data could not be deleted; the next start retries: {}", failure.toString());
        }
    }
}
