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
package com.bytequay.app.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Periodically dumps a one-line snapshot of the local SQLite schema —
 * each user table and its row count — to the application log. Useful
 * during dev for spotting unexpected growth (e.g. pr_review_comment
 * accumulating, or pr_view_state drifting beyond the active PR set).
 *
 * <p>The job ticks every 2 minutes; the first tick fires shortly after
 * startup so the log carries an initial snapshot without waiting two
 * minutes for the first rate window. Internal SQLite tables
 * ({@code sqlite_*}) are skipped — Flyway's {@code flyway_schema_history}
 * is included since it's part of the app's surface.
 */
@Component
public class DbStatsLogger
{
    private static final Logger log = LoggerFactory.getLogger(DbStatsLogger.class);
    private static final long FIXED_RATE_MS = 120_000L;
    private static final long INITIAL_DELAY_MS = 15_000L;

    private final JdbcClient jdbcClient;

    public DbStatsLogger(JdbcClient jdbcClient)
    {
        this.jdbcClient = requireNonNull(jdbcClient, "jdbcClient is null");
    }

    @Scheduled(fixedRate = FIXED_RATE_MS, initialDelay = INITIAL_DELAY_MS)
    public void logTableRowCounts()
    {
        try {
            List<String> tables = jdbcClient.sql(
                    "SELECT name FROM sqlite_master "
                            + "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' "
                            + "ORDER BY name")
                    .query(String.class)
                    .list();
            if (tables.isEmpty()) {
                log.info("DB stats: no user tables present");
                return;
            }
            StringBuilder sb = new StringBuilder("DB stats: ");
            for (int i = 0; i < tables.size(); i++) {
                String table = tables.get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(table).append('=').append(rowCount(table));
            }
            log.info(sb.toString());
        }
        catch (Exception e) {
            log.warn("DB stats logging failed: {}", e.getMessage());
        }
    }

    private long rowCount(String table)
    {
        // Table names come from sqlite_master, not user input — quoting them
        // with double-quotes guards against any reserved-word collisions
        // without exposing a SQL-injection surface.
        try {
            return jdbcClient.sql("SELECT COUNT(*) FROM \"" + table + "\"")
                    .query(Long.class)
                    .single();
        }
        catch (Exception e) {
            log.debug("row count failed for {}: {}", table, e.getMessage());
            return -1L;
        }
    }
}
