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
package com.bytequay.app.developmentflow.compatibility;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Read-only routing facts; runtime choice is always the persisted version. */
@Repository
public class V2ControlRouteStore
{
    private final JdbcTemplate jdbc;

    public V2ControlRouteStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public Optional<String> taskForStage(String stageId)
    {
        if (stageId == null || stageId.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT owner.task_id
                FROM stage owner
                JOIN tasks task ON task.id = owner.task_id
                WHERE owner.id = ? AND task.workflow_version = 'V2'
                """, (rs, row) -> rs.getString("task_id"), stageId)
                .stream().findFirst();
    }
}
