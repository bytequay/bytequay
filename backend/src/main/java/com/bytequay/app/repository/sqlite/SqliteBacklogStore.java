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

import com.bytequay.app.domain.BacklogItem;
import com.bytequay.app.repository.BacklogStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
class SqliteBacklogStore
        implements BacklogStore
{
    private static final String COLUMNS = """
            id, thread_id, workspace_id, title, body, tags_json, priority,
            source, status, created_by, origin, created_at_ms,
            in_progress_at_ms, started_at_ms, resolved_at_ms, rejected_at_ms,
            rejection_reason, linked_task_id, related_backlog_ids_json,
            item_key, summary, detail, impact_risk, links_json
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    SqliteBacklogStore(JdbcTemplate jdbc, ObjectMapper mapper)
    {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public BacklogItem save(BacklogItem item)
    {
        // origin is deliberately absent from the DO UPDATE list: provenance is
        // stamped once at insert and never rewritten.
        jdbc.update("""
                INSERT INTO backlog_item (%s)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    thread_id = excluded.thread_id,
                    workspace_id = excluded.workspace_id,
                    title = excluded.title,
                    body = excluded.body,
                    tags_json = excluded.tags_json,
                    priority = excluded.priority,
                    source = excluded.source,
                    status = excluded.status,
                    created_by = excluded.created_by,
                    created_at_ms = excluded.created_at_ms,
                    in_progress_at_ms = excluded.in_progress_at_ms,
                    started_at_ms = excluded.started_at_ms,
                    resolved_at_ms = excluded.resolved_at_ms,
                    rejected_at_ms = excluded.rejected_at_ms,
                    rejection_reason = excluded.rejection_reason,
                    linked_task_id = excluded.linked_task_id,
                    related_backlog_ids_json = excluded.related_backlog_ids_json,
                    item_key = excluded.item_key,
                    summary = excluded.summary,
                    detail = excluded.detail,
                    impact_risk = excluded.impact_risk,
                    links_json = excluded.links_json
                """.formatted(COLUMNS),
                item.id(),
                item.threadId(),
                item.workspaceId(),
                item.title(),
                item.body(),
                writeJson(item.tags()),
                item.priority(),
                item.source(),
                item.status(),
                item.createdBy(),
                item.origin(),
                item.createdAt().toEpochMilli(),
                epochOrNull(item.inProgressAt()),
                epochOrNull(item.startedAt()),
                epochOrNull(item.resolvedAt()),
                epochOrNull(item.rejectedAt()),
                item.rejectionReason(),
                item.linkedTaskId(),
                writeJson(item.relatedBacklogIds()),
                item.itemKey(),
                item.summary(),
                item.detail(),
                item.impactRisk(),
                writeJson(item.links()));
        return findById(item.id()).orElseThrow(() -> new IllegalStateException(
                "backlog item vanished after save: " + item.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BacklogItem> findByThread(String threadId)
    {
        return jdbc.query(
                "SELECT %s FROM backlog_item WHERE thread_id = ? ORDER BY created_at_ms ASC"
                        .formatted(COLUMNS),
                this::toDomain,
                threadId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BacklogItem> findByWorkspace(String workspaceId)
    {
        return jdbc.query(
                "SELECT %s FROM backlog_item WHERE workspace_id = ? ORDER BY created_at_ms DESC"
                        .formatted(COLUMNS),
                this::toDomain,
                workspaceId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BacklogItem> findById(String id)
    {
        return jdbc.query(
                "SELECT %s FROM backlog_item WHERE id = ?".formatted(COLUMNS),
                this::toDomain,
                id).stream().findFirst();
    }

    @Override
    @Transactional
    public boolean resolveIfInProgressAndUnlinked(
            String id, String taskId, Instant resolvedAt)
    {
        long resolvedAtMs = resolvedAt.toEpochMilli();
        return jdbc.update("""
                UPDATE backlog_item
                SET status = ?,
                    linked_task_id = ?,
                    started_at_ms = coalesce(started_at_ms, ?),
                    in_progress_at_ms = coalesce(in_progress_at_ms, ?),
                    resolved_at_ms = ?
                WHERE id = ?
                  AND status = ?
                  AND linked_task_id IS NULL
                """,
                BacklogItem.STATUS_RESOLVED,
                taskId,
                resolvedAtMs,
                resolvedAtMs,
                resolvedAtMs,
                id,
                BacklogItem.STATUS_IN_PROGRESS) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BacklogItem> findByWorkspaceAndItemKey(String workspaceId, String itemKey)
    {
        return jdbc.query(
                "SELECT %s FROM backlog_item WHERE workspace_id = ? AND item_key = ?"
                        .formatted(COLUMNS),
                this::toDomain,
                workspaceId,
                itemKey).stream().findFirst();
    }

    @Override
    @Transactional
    public String nextItemKey(String workspaceId)
    {
        jdbc.update("""
                INSERT INTO workspace_backlog_seq (workspace_id, next_value)
                VALUES (?, 1)
                ON CONFLICT(workspace_id) DO NOTHING
                """, workspaceId);
        Integer value = jdbc.queryForObject("""
                UPDATE workspace_backlog_seq
                SET next_value = next_value + 1
                WHERE workspace_id = ?
                RETURNING next_value - 1
                """, Integer.class, workspaceId);
        if (value == null) {
            throw new IllegalStateException(
                    "no backlog sequence for workspace " + workspaceId);
        }
        return "BQ-" + value;
    }

    @Override
    @Transactional
    public void delete(String id)
    {
        jdbc.update("DELETE FROM backlog_item WHERE id = ?", id);
    }

    private BacklogItem toDomain(ResultSet rs, int rowNum)
            throws SQLException
    {
        return new BacklogItem(
                rs.getString("id"),
                rs.getString("thread_id"),
                rs.getString("workspace_id"),
                rs.getString("title"),
                rs.getString("body"),
                readStrings(rs.getString("tags_json")),
                rs.getString("priority"),
                rs.getString("source"),
                rs.getString("status"),
                rs.getString("created_by"),
                rs.getString("origin"),
                Instant.ofEpochMilli(rs.getLong("created_at_ms")),
                instantOrNull(rs, "in_progress_at_ms"),
                instantOrNull(rs, "started_at_ms"),
                instantOrNull(rs, "resolved_at_ms"),
                instantOrNull(rs, "rejected_at_ms"),
                rs.getString("rejection_reason"),
                rs.getString("linked_task_id"),
                readStrings(rs.getString("related_backlog_ids_json")),
                rs.getString("item_key"),
                rs.getString("summary"),
                rs.getString("detail"),
                rs.getString("impact_risk"),
                readLinks(rs.getString("links_json")));
    }

    private String writeJson(List<?> values)
    {
        try {
            return mapper.writeValueAsString(values);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("could not encode backlog json", e);
        }
    }

    private List<String> readStrings(String json)
    {
        return readJson(json, new TypeReference<List<String>>() {});
    }

    private List<BacklogItem.Link> readLinks(String json)
    {
        return readJson(json, new TypeReference<List<BacklogItem.Link>>() {});
    }

    private <T> List<T> readJson(String json, TypeReference<List<T>> type)
    {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, type);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid backlog json", e);
        }
    }

    private static Long epochOrNull(Instant instant)
    {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static Instant instantOrNull(ResultSet rs, String column)
            throws SQLException
    {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }
}
