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
package com.bytequay.app.service.threads;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.domain.ThreadSettings;
import com.bytequay.app.repository.ThreadSettingsStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TestThreadSettingsServiceCapacity
{
    @TempDir
    private Path tempDir;

    @Test
    void validatesTaskLimitAndSignalsOnlyForCommittedChanges()
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("thread-settings.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE thread_settings(
                    thread_id TEXT PRIMARY KEY,
                    max_running_tasks INTEGER,
                    soft_cost_usd_milli INTEGER,
                    hard_cost_usd_milli INTEGER,
                    prompt_addendum TEXT,
                    updated_at_ms INTEGER NOT NULL)
                """);
        CapacityManager capacity = mock(CapacityManager.class);
        ThreadSettingsService service = new ThreadSettingsService(
                new JdbcThreadSettingsStore(jdbc), 4, capacity);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));

        transactions.executeWithoutResult(ignored -> {
            service.save("trunk", settings(2));
            verifyNoInteractions(capacity);
        });
        verify(capacity).policyChanged();
        assertThat(service.findOverrides("trunk").orElseThrow().maxRunningTasks())
                .isEqualTo(2);

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            service.save("trunk", settings(3));
            verify(capacity).policyChanged();
            throw new IllegalStateException("roll back trunk limit");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("roll back trunk limit");
        verify(capacity).policyChanged();
        assertThat(service.findOverrides("trunk").orElseThrow().maxRunningTasks())
                .isEqualTo(2);

        assertThatThrownBy(() -> service.save("trunk", settings(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max running tasks");
        verify(capacity).policyChanged();
        assertThat(service.findOverrides("trunk").orElseThrow().maxRunningTasks())
                .isEqualTo(2);

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            service.clear("trunk");
            verify(capacity).policyChanged();
            throw new IllegalStateException("roll back clear");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("roll back clear");
        verify(capacity).policyChanged();
        assertThat(service.findOverrides("trunk")).isPresent();

        transactions.executeWithoutResult(ignored -> {
            service.clear("trunk");
            verify(capacity).policyChanged();
        });
        verify(capacity, times(2)).policyChanged();
        assertThat(service.findOverrides("trunk")).isEmpty();
    }

    @Test
    void committedWakeIsOnlyAnInProcessSignal()
    {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + tempDir.resolve("single-pool.db"));
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(500);
        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("""
                    CREATE TABLE thread_settings(
                        thread_id TEXT PRIMARY KEY,
                        max_running_tasks INTEGER,
                        soft_cost_usd_milli INTEGER,
                        hard_cost_usd_milli INTEGER,
                        prompt_addendum TEXT,
                        updated_at_ms INTEGER NOT NULL)
                    """);
            CapacityManager capacity = mock(CapacityManager.class);
            ThreadSettingsService service = new ThreadSettingsService(
                    new JdbcThreadSettingsStore(jdbc), 4, capacity);
            TransactionTemplate transactions = new TransactionTemplate(
                    new DataSourceTransactionManager(dataSource));

            transactions.executeWithoutResult(ignored ->
                    service.save("trunk", settings(2)));

            verify(capacity).policyChanged();
        }
    }

    private static ThreadSettings settings(Integer maxRunningTasks)
    {
        return new ThreadSettings(
                "ignored", maxRunningTasks, null, null, null, Instant.EPOCH);
    }

    private record JdbcThreadSettingsStore(JdbcTemplate jdbc)
            implements ThreadSettingsStore
    {
        @Override
        public Optional<ThreadSettings> find(String threadId)
        {
            List<ThreadSettings> rows = jdbc.query("""
                    SELECT thread_id, max_running_tasks, soft_cost_usd_milli,
                           hard_cost_usd_milli, prompt_addendum, updated_at_ms
                    FROM thread_settings
                    WHERE thread_id = ?
                    """, (result, ignored) -> new ThreadSettings(
                    result.getString("thread_id"),
                    nullableInt(result, "max_running_tasks"),
                    nullableInt(result, "soft_cost_usd_milli"),
                    nullableInt(result, "hard_cost_usd_milli"),
                    result.getString("prompt_addendum"),
                    Instant.ofEpochMilli(result.getLong("updated_at_ms"))),
                    threadId);
            return rows.stream().findFirst();
        }

        @Override
        public void save(ThreadSettings settings)
        {
            jdbc.update("""
                    INSERT INTO thread_settings(
                        thread_id, max_running_tasks, soft_cost_usd_milli,
                        hard_cost_usd_milli, prompt_addendum, updated_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(thread_id) DO UPDATE SET
                        max_running_tasks = excluded.max_running_tasks,
                        soft_cost_usd_milli = excluded.soft_cost_usd_milli,
                        hard_cost_usd_milli = excluded.hard_cost_usd_milli,
                        prompt_addendum = excluded.prompt_addendum,
                        updated_at_ms = excluded.updated_at_ms
                    """,
                    settings.threadId(), settings.maxRunningTasks(),
                    settings.softCostUsdMilli(), settings.hardCostUsdMilli(),
                    settings.promptAddendum(), settings.updatedAt().toEpochMilli());
        }

        @Override
        public void clear(String threadId)
        {
            jdbc.update("DELETE FROM thread_settings WHERE thread_id = ?", threadId);
        }

        private static Integer nullableInt(ResultSet result, String column)
                throws SQLException
        {
            int value = result.getInt(column);
            return result.wasNull() ? null : value;
        }
    }
}
