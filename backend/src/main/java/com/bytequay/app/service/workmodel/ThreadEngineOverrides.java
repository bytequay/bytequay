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
package com.bytequay.app.service.workmodel;

import com.bytequay.app.domain.WorkModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * The engine a single trunk pinned for a session audience, overriding
 * the workspace's pick for that trunk and everything under it.
 *
 * <p>Sparse by design: the new-trunk dialog writes a row only for the
 * kinds the creator actually swapped, so an untouched trunk keeps
 * inheriting whatever the workspace Agents page says today.
 *
 * <p>Deliberately JDBC-direct for the same reason as
 * {@link WorkspaceEngineSettings}: {@link WorkModelResolver} sits below
 * the stores that would otherwise close a bean cycle.
 */
@Component
public class ThreadEngineOverrides
{
    private final JdbcTemplate jdbc;

    public ThreadEngineOverrides(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    /** The engine this trunk pinned for {@code audience}, or empty when
     *  it inherits the workspace's pick. */
    public Optional<WorkModel> forAudience(String threadId, String audience)
    {
        if (threadId == null || threadId.isBlank() || audience == null) {
            return Optional.empty();
        }
        return jdbc.queryForList("""
                        SELECT choice
                        FROM thread_engines
                        WHERE thread_id = ? AND audience = ?
                        """, String.class, threadId, audience)
                .stream()
                .findFirst()
                .flatMap(WorkspaceEngineSettings::parseChoice);
    }

    /** Every pin on this trunk, keyed by audience. */
    public Map<String, String> forThread(String threadId)
    {
        Map<String, String> pinned = new LinkedHashMap<>();
        if (threadId == null || threadId.isBlank()) {
            return pinned;
        }
        jdbc.query("""
                SELECT audience, choice
                FROM thread_engines
                WHERE thread_id = ?
                """, rs -> { pinned.put(rs.getString(1), rs.getString(2)); }, threadId);
        return pinned;
    }

    /**
     * Replace this trunk's pins with {@code choices}. Entries naming an
     * unknown audience or an unparseable choice id are dropped rather
     * than stored — a stale client must not be able to strand a trunk on
     * an engine nothing can resolve.
     */
    public void replace(String threadId, Map<String, String> choices)
    {
        requireNonNull(threadId, "threadId is null");
        jdbc.update("DELETE FROM thread_engines WHERE thread_id = ?", threadId);
        if (choices == null) {
            return;
        }
        choices.forEach((audience, choice) -> {
            if (SessionAudience.ALL.contains(audience)
                    && WorkspaceEngineSettings.parseChoice(choice).isPresent()) {
                jdbc.update("""
                        INSERT INTO thread_engines (thread_id, audience, choice)
                        VALUES (?, ?, ?)
                        """, threadId, audience, choice.strip());
            }
        });
    }
}
