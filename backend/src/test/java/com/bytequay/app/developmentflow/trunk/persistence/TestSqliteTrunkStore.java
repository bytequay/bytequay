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
package com.bytequay.app.developmentflow.trunk.persistence;

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.trunk.TrunkLifecycle;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.testing.MigratedSqliteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqliteTrunkStore
{
    @TempDir
    private Path tempDir;

    @Test
    void duplicateStaleRollbackAndRestartUseOneDurableReceipt()
    {
        SQLiteDataSource dataSource = database(tempDir.resolve("trunk.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc, "trunk-1");
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        V2TrunkStore store = new V2TrunkStore(jdbc);
        TrunkManager manager = new TrunkManager(commands, store);
        TrunkManager.Command command = new TrunkManager.Command(
                "idle-1", "user", "trunk-1", 0);

        assertThat(manager.markIdle(command).state())
                .isEqualTo(new TrunkManager.State("trunk-1", TrunkLifecycle.IDLE, 1));
        assertThat(manager.markIdle(command).disposition().name()).isEqualTo("DUPLICATE");

        TrunkManager restarted = new TrunkManager(
                commands, new V2TrunkStore(new JdbcTemplate(dataSource)));
        assertThat(restarted.markIdle(command).disposition().name()).isEqualTo("DUPLICATE");
        assertThat(count(jdbc, "trunk_transition")).isEqualTo(1);
        assertThat(count(jdbc, "trunk_command_receipt")).isEqualTo(1);

        assertThatThrownBy(() -> restarted.activate(new TrunkManager.Command(
                "stale-1", "user", "trunk-1", 0)))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(STALE_VERSION));

        seedTrunk(jdbc, "trunk-rollback");
        TrunkManager.State expected = new TrunkManager.State(
                "trunk-rollback", TrunkLifecycle.ACTIVE, 0);
        TrunkManager.State updated = new TrunkManager.State(
                "trunk-rollback", TrunkLifecycle.IDLE, 1);
        assertThatThrownBy(() -> commands.execute(
                "v2-trunk/trunk-rollback",
                () -> store.commit(
                        "bad-command", "NOT_A_CAUSE", "user", 0,
                        expected, updated)))
                .isInstanceOf(DataAccessException.class);
        assertThat(store.findById("trunk-rollback")).contains(expected);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM trunk_transition WHERE trunk_id = 'trunk-rollback'
                """, Integer.class)).isZero();
        assertThatThrownBy(() -> store.commit(
                "outside", "MARK_IDLE", "user", 0, expected, updated))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("command transaction");
    }

    private static SQLiteDataSource database(Path file)
    {
        String url = "jdbc:sqlite:" + file + "?foreign_keys=ON&busy_timeout=30000";
        MigratedSqliteDatabase.migrate(url);
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return dataSource;
    }

    private static void seedTrunk(JdbcTemplate jdbc, String trunkId)
    {
        jdbc.update("""
                INSERT OR IGNORE INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'workspace-1', '', 0, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model,
                    cost_usd_milli, tokens_in, tokens_out,
                    created_at_ms, updated_at_ms, workspace_id, flow,
                    parallel_slots, turn_version, lifecycle_state)
                VALUES (?, 'CLI_AGENT', 'codex', ?, 'IDLE', 'test',
                    0, 0, 0, 1, 1, 'workspace-1', 'build', 2, 'V2', 'ACTIVE')
                """, trunkId, trunkId);
    }

    private static int count(JdbcTemplate jdbc, String table)
    {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
