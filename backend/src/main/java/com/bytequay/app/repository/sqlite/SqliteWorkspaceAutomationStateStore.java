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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.WorkspaceAutomationState;
import com.bytequay.app.repository.WorkspaceAutomationStateStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Repository
public class SqliteWorkspaceAutomationStateStore
        implements WorkspaceAutomationStateStore
{
    private final JdbcTemplate jdbc;

    public SqliteWorkspaceAutomationStateStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    @Override
    public Optional<WorkspaceAutomationState> find(String workspaceId, String kind)
    {
        return jdbc.query("""
                        SELECT workspace_id, kind, cursor, last_run_json, updated_at_ms
                        FROM workspace_automation_state
                        WHERE workspace_id = ? AND kind = ?
                        """,
                (result, row) -> new WorkspaceAutomationState(
                        result.getString("workspace_id"),
                        result.getString("kind"),
                        result.getObject("cursor", Integer.class),
                        result.getString("last_run_json"),
                        Instant.ofEpochMilli(result.getLong("updated_at_ms"))),
                requireNonNull(workspaceId, "workspaceId is null"),
                requireNonNull(kind, "kind is null")).stream().findFirst();
    }

    @Override
    public void save(WorkspaceAutomationState state)
    {
        requireNonNull(state, "state is null");
        jdbc.update("""
                INSERT INTO workspace_automation_state (
                    workspace_id, kind, cursor, last_run_json, updated_at_ms)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(workspace_id, kind) DO UPDATE SET
                    cursor = excluded.cursor,
                    last_run_json = excluded.last_run_json,
                    updated_at_ms = excluded.updated_at_ms
                """,
                state.workspaceId(),
                state.kind(),
                state.cursor(),
                state.lastRunJson(),
                state.updatedAt().toEpochMilli());
    }
}
