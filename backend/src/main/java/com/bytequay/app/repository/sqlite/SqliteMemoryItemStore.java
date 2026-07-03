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

import com.bytequay.app.domain.MemoryItem;
import com.bytequay.app.domain.MemoryItemConfidence;
import com.bytequay.app.domain.MemoryItemKind;
import com.bytequay.app.domain.MemoryItemOrigin;
import com.bytequay.app.domain.MemoryItemScopeKind;
import com.bytequay.app.domain.MemoryItemSource;
import com.bytequay.app.repository.MemoryItemStore;
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
 * SQLite-backed memory_item store. JSON-encodes
 * {@code sources}/{@code tags} into TEXT columns — both are read
 * back as whole arrays, never queried into, so storing them as a
 * single blob is the cheapest shape and avoids a join table.
 */
@Repository
public class SqliteMemoryItemStore
        implements MemoryItemStore
{
    private static final TypeReference<List<MemoryItemSource>> SOURCES_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRINGS_TYPE = new TypeReference<>() {};

    private static final String COLS = ""
            + "id, scope_kind, scope_id, kind, text, sources_json, confidence, tags_json, "
            + "superseded_by, resolved_at_ms, proposed_at_ms, applied_at_ms, source";

    private static final String INSERT_SQL = ""
            + "INSERT INTO memory_item ("
            + "    scope_kind, scope_id, kind, text, sources_json, confidence, tags_json, "
            + "    proposed_at_ms, source) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "RETURNING " + COLS;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SqliteMemoryItemStore(JdbcTemplate jdbc, ObjectMapper mapper)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Override
    public MemoryItem insert(NewItem newItem)
    {
        requireNonNull(newItem, "newItem is null");
        long now = Instant.now().toEpochMilli();
        MemoryItem row = jdbc.queryForObject(
                INSERT_SQL,
                rowMapper(),
                newItem.scopeKind().name(),
                newItem.scopeId(),
                newItem.kind().name(),
                newItem.text(),
                writeJson(newItem.sources() == null ? List.of() : newItem.sources()),
                newItem.confidence().name(),
                writeJson(newItem.tags() == null ? List.of() : newItem.tags()),
                now,
                newItem.source().name());
        if (row == null) {
            throw new IllegalStateException("memory_item insert returned no row");
        }
        return row;
    }

    @Override
    public Optional<MemoryItem> findById(long id)
    {
        List<MemoryItem> hits = jdbc.query(
                "SELECT " + COLS + " FROM memory_item WHERE id = ?",
                rowMapper(), id);
        return hits.stream().findFirst();
    }

    @Override
    public List<MemoryItem> findByScope(MemoryItemScopeKind scopeKind, String scopeId)
    {
        return jdbc.query(
                "SELECT " + COLS + " FROM memory_item "
                        + "WHERE scope_kind = ? AND scope_id = ? "
                        + "ORDER BY proposed_at_ms DESC, id DESC",
                rowMapper(), scopeKind.name(), scopeId);
    }

    @Override
    public List<MemoryItem> findPending(MemoryItemScopeKind scopeKind, String scopeId)
    {
        return jdbc.query(
                "SELECT " + COLS + " FROM memory_item "
                        + "WHERE scope_kind = ? AND scope_id = ? AND applied_at_ms IS NULL "
                        + "ORDER BY proposed_at_ms DESC, id DESC",
                rowMapper(), scopeKind.name(), scopeId);
    }

    @Override
    public List<MemoryItem> findLive(MemoryItemScopeKind scopeKind, String scopeId)
    {
        return jdbc.query(
                "SELECT " + COLS + " FROM memory_item "
                        + "WHERE scope_kind = ? AND scope_id = ? "
                        + "  AND applied_at_ms IS NOT NULL AND superseded_by IS NULL "
                        + "ORDER BY proposed_at_ms ASC, id ASC",
                rowMapper(), scopeKind.name(), scopeId);
    }

    @Override
    public Optional<MemoryItem> markApplied(long id, long nowMs)
    {
        // applied_at_ms is set only when null — applying an already-
        // applied row is a no-op and preserves the original time.
        int updated = jdbc.update(
                "UPDATE memory_item SET applied_at_ms = ? "
                        + "WHERE id = ? AND applied_at_ms IS NULL",
                nowMs, id);
        if (updated == 0 && findById(id).isEmpty()) {
            return Optional.empty();
        }
        return findById(id);
    }

    @Override
    public boolean delete(long id)
    {
        return jdbc.update("DELETE FROM memory_item WHERE id = ?", id) > 0;
    }

    @Override
    public boolean markSuperseded(long id, long supersededByItemId)
    {
        return jdbc.update(
                "UPDATE memory_item SET superseded_by = ? WHERE id = ?",
                supersededByItemId, id) > 0;
    }

    @Override
    public boolean markResolved(long id, long nowMs)
    {
        return jdbc.update(
                "UPDATE memory_item SET resolved_at_ms = ? WHERE id = ?",
                nowMs, id) > 0;
    }

    @Override
    public int deleteByScope(MemoryItemScopeKind scopeKind, String scopeId)
    {
        return jdbc.update("DELETE FROM memory_item WHERE scope_kind = ? AND scope_id = ?",
                scopeKind.name(), scopeId);
    }

    private RowMapper<MemoryItem> rowMapper()
    {
        return (rs, n) -> new MemoryItem(
                rs.getLong("id"),
                MemoryItemScopeKind.valueOf(rs.getString("scope_kind")),
                rs.getString("scope_id"),
                MemoryItemKind.valueOf(rs.getString("kind")),
                rs.getString("text"),
                readSources(rs.getString("sources_json")),
                MemoryItemConfidence.valueOf(rs.getString("confidence")),
                readStrings(rs.getString("tags_json")),
                readLong(rs.getObject("superseded_by")),
                readInstant(rs.getObject("resolved_at_ms")),
                readInstant(rs.getObject("proposed_at_ms")),
                readInstant(rs.getObject("applied_at_ms")),
                MemoryItemOrigin.valueOf(rs.getString("source")));
    }

    private static Instant readInstant(Object raw)
    {
        if (raw == null) {
            return null;
        }
        return Instant.ofEpochMilli(((Number) raw).longValue());
    }

    /** SQLite returns INTEGER columns as {@link Integer} when the
     *  value fits, {@link Long} when it doesn't — coerce through
     *  {@link Number} so the {@code superseded_by} cast doesn't blow
     *  up on small ids. */
    private static Long readLong(Object raw)
    {
        return raw == null ? null : ((Number) raw).longValue();
    }

    private List<MemoryItemSource> readSources(String json)
    {
        return JsonText.read(mapper, json, SOURCES_TYPE, List.of(), "invalid sources_json");
    }

    private List<String> readStrings(String json)
    {
        return JsonText.read(mapper, json, STRINGS_TYPE, List.of(), "invalid tags_json");
    }

    private String writeJson(Object value)
    {
        return JsonText.write(mapper, value, "failed to serialise memory_item JSON column");
    }
}
