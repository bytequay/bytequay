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

import com.bytequay.app.repository.UserConceptStore;
import com.bytequay.app.service.concepts.ConceptKind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * SQLite-backed implementation of {@link UserConceptStore}. The
 * {@code aka} list is JSON-encoded into a single TEXT column —
 * cheap to query and read, and v1 never needs to query <em>inside</em>
 * the array.
 */
@Repository
public class SqliteUserConceptStore
        implements UserConceptStore
{
    private static final String UPSERT_SQL = ""
            + "INSERT INTO concept_user "
            + "    (name, kind, definition, aka_json, criteria_json, created_at_ms, updated_at_ms) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT(name) DO UPDATE "
            + "SET kind = excluded.kind, "
            + "    definition = excluded.definition, "
            + "    aka_json = excluded.aka_json, "
            + "    criteria_json = excluded.criteria_json, "
            + "    updated_at_ms = excluded.updated_at_ms "
            + "RETURNING name, kind, definition, aka_json, criteria_json, created_at_ms, updated_at_ms";

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SqliteUserConceptStore(JdbcTemplate jdbc, ObjectMapper mapper)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Override
    public List<UserConceptRow> findAll()
    {
        return jdbc.query(
                "SELECT name, kind, definition, aka_json, criteria_json, "
                        + "created_at_ms, updated_at_ms FROM concept_user ORDER BY name ASC",
                rowMapper());
    }

    @Override
    public Optional<UserConceptRow> findByName(String name)
    {
        requireNonNull(name, "name is null");
        List<UserConceptRow> hits = jdbc.query(
                "SELECT name, kind, definition, aka_json, criteria_json, "
                        + "created_at_ms, updated_at_ms FROM concept_user WHERE name = ?",
                rowMapper(),
                name);
        return hits.stream().findFirst();
    }

    @Override
    public UserConceptRow save(
            String name,
            ConceptKind kind,
            String definition,
            List<String> aka,
            String criteriaJson)
    {
        requireNonNull(name, "name is null");
        requireNonNull(kind, "kind is null");
        requireNonNull(definition, "definition is null");
        long now = Instant.now().toEpochMilli();
        String akaJson;
        try {
            akaJson = mapper.writeValueAsString(aka == null ? List.of() : aka);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise aka list", e);
        }
        UserConceptRow row = jdbc.queryForObject(
                UPSERT_SQL,
                rowMapper(),
                name,
                kind.name(),
                definition,
                akaJson,
                criteriaJson,
                now,
                now);
        if (row == null) {
            throw new IllegalStateException("UPSERT returned no row for " + name);
        }
        return row;
    }

    @Override
    public boolean delete(String name)
    {
        requireNonNull(name, "name is null");
        return jdbc.update("DELETE FROM concept_user WHERE name = ?", name) > 0;
    }

    private RowMapper<UserConceptRow> rowMapper()
    {
        return (rs, n) -> {
            String akaJson = rs.getString("aka_json");
            List<String> aka;
            try {
                aka = akaJson == null || akaJson.isBlank()
                        ? List.of()
                        : mapper.readValue(akaJson, STRING_LIST);
            }
            catch (JsonProcessingException e) {
                throw new IllegalStateException("invalid aka_json on row " + rs.getString("name"), e);
            }
            return new UserConceptRow(
                    rs.getString("name"),
                    ConceptKind.valueOf(rs.getString("kind")),
                    rs.getString("definition"),
                    aka,
                    rs.getString("criteria_json"),
                    rs.getLong("created_at_ms"),
                    rs.getLong("updated_at_ms"));
        };
    }
}
