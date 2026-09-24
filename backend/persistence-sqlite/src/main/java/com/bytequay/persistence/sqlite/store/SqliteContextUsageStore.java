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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextUsageEvent;
import com.bytequay.core.store.ContextUsageStore;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Idempotent, workspace-scoped usage facts in the existing SQLite database. */
@Repository
public class SqliteContextUsageStore
        implements ContextUsageStore
{
    private static final int ACTIVITY_PAGE_SIZE = 20;
    private static final String COLUMNS = "workspace_id, task_id, turn_id, review_run_id, task_kind, stage, delivery, receipt_ref, "
            + "entry_id, entry_version, entry_kind, scope_id, entry_title, observed_preparation, context_enabled, occurred_at_ms";
    private static final String TRACKING_PREFIX = "workspace.contextUsageTrackingSince.";

    private final JdbcTemplate jdbc;

    public SqliteContextUsageStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    @Override
    @Transactional
    public void record(ContextUsageEvent event)
    {
        requireNonNull(event, "event is null");
        if (event.stage() == ContextUsageEvent.Stage.ELIGIBLE) {
            jdbc.update("INSERT OR IGNORE INTO app_settings (setting_key, setting_value) VALUES (?, ?)",
                    TRACKING_PREFIX + event.workspaceId(), event.occurredAt().toString());
        }
        String id = digest(identity(event));
        String payload = digest(payload(event));
        ContextUsageEvent.Note note = event.note().orElse(null);
        ContextUsageEvent.Scope scope = event.scope().orElse(null);
        int changed = jdbc.update("""
                INSERT INTO context_usage_fact (id, workspace_id, task_id, turn_id, review_run_id, task_kind, stage, delivery, receipt_ref,
                    entry_id, entry_version, entry_kind, scope_id, entry_title, observed_preparation, context_enabled, occurred_at_ms, payload_digest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET delivery = CASE
                    WHEN context_usage_fact.delivery = 'CONFIRMED' OR excluded.delivery = 'PREPARED' THEN context_usage_fact.delivery
                    ELSE excluded.delivery END
                WHERE context_usage_fact.payload_digest = excluded.payload_digest
                """, id, event.workspaceId(), event.taskId(), event.turnId().orElse(null), event.reviewRunId().orElse(null), event.taskKind().name(),
                event.stage().name(), event.delivery().name(), event.receiptRef(), note == null ? null : note.id(), note == null ? null : note.version(),
                note == null ? null : note.kind().name(), note == null ? scope == null ? null : scope.id() : note.scopeId(),
                note == null ? null : note.title(), scope == null ? null : scope.preparation().name(), event.contextEnabled().orElse(null),
                event.occurredAt().toEpochMilli(), payload);
        checkState(changed == 1, "Context usage receipt identity was reused with different content");
    }

    @Override
    @Transactional
    public void confirm(String workspaceId, String receiptRef, ContextUsageEvent.Stage stage)
    {
        delivery(workspaceId, receiptRef, stage, ContextUsageEvent.Delivery.CONFIRMED);
    }

    @Override
    @Transactional
    public void markUncertain(String workspaceId, String receiptRef, ContextUsageEvent.Stage stage)
    {
        delivery(workspaceId, receiptRef, stage, ContextUsageEvent.Delivery.UNCERTAIN);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> trackingSince(String workspaceId)
    {
        return jdbc.query("SELECT setting_value FROM app_settings WHERE setting_key = ?", rs -> rs.next() ? Optional.of(Instant.parse(rs.getString(1))) : Optional.empty(),
                TRACKING_PREFIX + requireText(workspaceId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContextUsageEvent> listTask(String workspaceId, String taskId, int limit)
    {
        checkArgument(limit >= 1 && limit <= 1001, "Task usage display limit is out of range");
        return ImmutableList.copyOf(jdbc.query("SELECT " + COLUMNS + " FROM context_usage_fact WHERE workspace_id = ? AND task_id = ? "
                        + "ORDER BY occurred_at_ms DESC, id DESC LIMIT ?", (row, ignored) -> event(row),
                requireText(workspaceId), requireText(taskId), limit));
    }

    @Override
    @Transactional(readOnly = true)
    public void forEach(String workspaceId, Optional<Instant> since, Consumer<ContextUsageEvent> consumer)
    {
        requireNonNull(since, "since is null");
        requireNonNull(consumer, "consumer is null");
        String sql = "SELECT " + COLUMNS + " FROM context_usage_fact WHERE workspace_id = ?"
                + (since.isPresent() ? " AND occurred_at_ms >= ?" : "") + " ORDER BY occurred_at_ms, id";
        RowCallbackHandler handler = rs -> consumer.accept(event(rs));
        if (since.isPresent()) {
            jdbc.query(sql, handler, requireText(workspaceId), since.orElseThrow().toEpochMilli());
        }
        else {
            jdbc.query(sql, handler, requireText(workspaceId));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContextUsageEvent.Note> findSelectedNote(String workspaceId, String reviewRunId, String entryId, long version)
    {
        checkArgument(version > 0, "note version must be positive");
        return jdbc.query("SELECT " + COLUMNS + " FROM context_usage_fact WHERE workspace_id = ? AND review_run_id = ? "
                        + "AND entry_id = ? AND entry_version = ? AND stage = 'SELECTED' LIMIT 1",
                rs -> rs.next() ? event(rs).note() : Optional.empty(),
                requireText(workspaceId), requireText(reviewRunId), requireText(entryId), version);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContextUsageEvent> activities(String workspaceId, String entryId, Optional<Instant> since, int page)
    {
        checkPage(page);
        requireNonNull(since, "since is null");
        String sql = "SELECT " + COLUMNS + " FROM context_usage_fact WHERE workspace_id = ? AND entry_id = ?"
                + (since.isPresent() ? " AND occurred_at_ms >= ?" : "") + " ORDER BY occurred_at_ms DESC, id DESC LIMIT ? OFFSET ?";
        int offset = Math.multiplyExact(page, ACTIVITY_PAGE_SIZE);
        if (since.isPresent()) {
            return ImmutableList.copyOf(jdbc.query(sql, (rs, row) -> event(rs), requireText(workspaceId), requireText(entryId),
                    since.orElseThrow().toEpochMilli(), ACTIVITY_PAGE_SIZE, offset));
        }
        return ImmutableList.copyOf(jdbc.query(sql, (rs, row) -> event(rs), requireText(workspaceId), requireText(entryId), ACTIVITY_PAGE_SIZE, offset));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasMoreActivities(String workspaceId, String entryId, Optional<Instant> since, int page)
    {
        checkPage(page);
        requireNonNull(since, "since is null");
        String sql = "SELECT EXISTS (SELECT 1 FROM context_usage_fact WHERE workspace_id = ? AND entry_id = ?"
                + (since.isPresent() ? " AND occurred_at_ms >= ?" : "") + " LIMIT 1 OFFSET ?)";
        int offset = Math.multiplyExact(page + 1, ACTIVITY_PAGE_SIZE);
        if (since.isPresent()) {
            return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, requireText(workspaceId), requireText(entryId), since.orElseThrow().toEpochMilli(), offset));
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, requireText(workspaceId), requireText(entryId), offset));
    }

    private void delivery(String workspaceId, String receiptRef, ContextUsageEvent.Stage stage, ContextUsageEvent.Delivery value)
    {
        requireNonNull(stage, "stage is null");
        jdbc.update("UPDATE context_usage_fact SET delivery = ? WHERE workspace_id = ? AND receipt_ref = ? AND stage = ? AND delivery <> 'CONFIRMED'",
                value.name(), requireText(workspaceId), requireText(receiptRef), stage.name());
    }

    private static ContextUsageEvent event(ResultSet row)
            throws SQLException
    {
        String entryId = row.getString("entry_id");
        String scopeId = row.getString("scope_id");
        String preparation = row.getString("observed_preparation");
        Object enabled = row.getObject("context_enabled");
        return new ContextUsageEvent(row.getString("workspace_id"), row.getString("task_id"), Optional.ofNullable(row.getString("turn_id")),
                Optional.ofNullable(row.getString("review_run_id")), ContextUsageEvent.TaskKind.valueOf(row.getString("task_kind")),
                ContextUsageEvent.Stage.valueOf(row.getString("stage")), ContextUsageEvent.Delivery.valueOf(row.getString("delivery")),
                row.getString("receipt_ref"), entryId == null ? Optional.empty() : Optional.of(new ContextUsageEvent.Note(entryId,
                row.getLong("entry_version"), ContextEntryKind.valueOf(row.getString("entry_kind")), scopeId, row.getString("entry_title"))),
                preparation == null ? Optional.empty() : Optional.of(new ContextUsageEvent.Scope(scopeId, ContextUsageEvent.Preparation.valueOf(preparation))),
                enabled == null ? Optional.empty() : Optional.of(((Number) enabled).intValue() != 0), Instant.ofEpochMilli(row.getLong("occurred_at_ms")));
    }

    private static String identity(ContextUsageEvent event)
    {
        StringBuilder key = new StringBuilder();
        append(key, event.workspaceId());
        append(key, event.receiptRef());
        append(key, event.stage().name());
        append(key, event.note().map(ContextUsageEvent.Note::id).orElse(""));
        append(key, event.note().map(note -> Long.toString(note.version())).orElse(""));
        append(key, event.scope().map(ContextUsageEvent.Scope::id).orElse(""));
        return key.toString();
    }

    private static String payload(ContextUsageEvent event)
    {
        StringBuilder value = new StringBuilder(identity(event));
        append(value, event.taskId());
        append(value, event.turnId().orElse(""));
        append(value, event.reviewRunId().orElse(""));
        append(value, event.taskKind().name());
        append(value, event.note().map(note -> note.kind().name()).orElse(""));
        append(value, event.note().map(ContextUsageEvent.Note::scopeId).orElse(""));
        append(value, event.note().map(ContextUsageEvent.Note::title).orElse(""));
        append(value, event.scope().map(scope -> scope.preparation().name()).orElse(""));
        append(value, event.contextEnabled().map(String::valueOf).orElse(""));
        return value.toString();
    }

    private static void append(StringBuilder destination, String part)
    {
        destination.append(part.length()).append(':').append(part);
    }

    private static String digest(String text)
    {
        return Hashing.sha256().hashString(text, StandardCharsets.UTF_8).toString();
    }

    private static String requireText(String value)
    {
        checkArgument(value != null && !value.isBlank(), "Context usage identity is required");
        return value;
    }

    private static void checkPage(int page)
    {
        checkArgument(page >= 0 && page <= 10_000, "Page is out of range");
    }
}
