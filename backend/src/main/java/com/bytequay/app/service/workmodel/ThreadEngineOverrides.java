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
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.sqlite.WorkModelJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * The engine snapshot a single trunk owns for each session audience.
 *
 * <p>New trunks write all four rows, including roles the creator left on
 * the workspace default. Each row stores the complete model and account
 * resolved at creation, so later workspace or credential-default changes
 * affect only future trunks. Legacy sparse rows keep resolving through the
 * compact {@code choice} column because their original defaults cannot be
 * reconstructed after the fact.
 *
 * <p>Deliberately JDBC-direct for the same reason as
 * {@link WorkspaceEngineSettings}: {@link WorkModelResolver} sits below
 * the stores that would otherwise close a bean cycle.
 */
@Component
public class ThreadEngineOverrides
{
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final EntityManager entityManager;

    public ThreadEngineOverrides(JdbcTemplate jdbc, ObjectMapper mapper, EntityManager entityManager)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.entityManager = requireNonNull(entityManager, "entityManager is null");
    }

    /** The engine this trunk froze for {@code audience}. Legacy rows read
     *  their compact picker choice; a missing or invalid row is empty. */
    public Optional<WorkModel> forAudience(String threadId, String audience)
    {
        if (threadId == null || threadId.isBlank() || audience == null) {
            return Optional.empty();
        }
        return jdbc.query("""
                        SELECT choice, work_model_json
                        FROM thread_engines
                        WHERE thread_id = ? AND audience = ?
                        """, (rs, rowNum) -> {
                            WorkModel frozen = WorkModelJson.deserialise(
                                    mapper, rs.getString("work_model_json"));
                            return frozen != null
                                    && frozen.model() != null
                                    && !frozen.model().isBlank()
                                    ? frozen
                                    : WorkspaceEngineSettings.parseChoice(rs.getString("choice")).orElse(null);
                        }, threadId, audience)
                .stream()
                .filter(model -> model != null)
                .findFirst();
    }

    /** Whether this audience row carries the immutable JSON snapshot rather
     * than only a legacy compact picker choice. */
    public boolean isFrozen(String threadId, String audience)
    {
        if (threadId == null || threadId.isBlank() || audience == null) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM thread_engines
                WHERE thread_id = ? AND audience = ?
                  AND CASE
                      WHEN json_valid(work_model_json) = 1
                      THEN NULLIF(TRIM(json_extract(
                              work_model_json, '$.model')), '') IS NOT NULL
                      ELSE 0
                  END
                """, Integer.class, threadId, audience);
        return count != null && count == 1;
    }

    /** Every compact picker label on this trunk, keyed by audience. */
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
     * Replace this trunk's complete creation-time snapshot. Validation runs
     * before the delete so an invalid caller cannot erase a valid snapshot.
     */
    @Transactional
    public void replace(String threadId, Map<String, WorkModel> choices)
    {
        requireNonNull(threadId, "threadId is null");
        requireNonNull(choices, "choices is null");
        if (!choices.keySet().equals(SessionAudience.ALL)) {
            throw new IllegalArgumentException("engine snapshot must contain all session audiences");
        }

        Map<String, FrozenEngine> frozen = new LinkedHashMap<>();
        choices.forEach((audience, model) -> {
            requireNonNull(model, "engine for " + audience + " is null");
            String pickerChoice = WorkspaceEngineSettings.pickerChoice(model)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "invalid engine for " + audience));
            if (model.model() == null || model.model().isBlank()) {
                throw new IllegalArgumentException("model for " + audience + " is required");
            }
            if (model.kind() == WorkModelKind.API
                    && !pickerChoice.equals("local")
                    && (model.account() == null || model.account().isBlank())) {
                throw new IllegalArgumentException("API account for " + audience + " is required");
            }
            frozen.put(audience, new FrozenEngine(
                    pickerChoice, WorkModelJson.serialise(mapper, model)));
        });

        // ThreadService creates the parent through JPA and these children
        // through JDBC in one transaction. Flush first so SQLite sees the
        // parent before enforcing the child foreign key.
        entityManager.flush();
        jdbc.update("DELETE FROM thread_engines WHERE thread_id = ?", threadId);
        frozen.forEach((audience, engine) -> {
            jdbc.update("""
                    INSERT INTO thread_engines (thread_id, audience, choice, work_model_json)
                    VALUES (?, ?, ?, ?)
                    """, threadId, audience, engine.pickerChoice(), engine.workModelJson());
        });
    }

    private record FrozenEngine(String pickerChoice, String workModelJson) {}
}
