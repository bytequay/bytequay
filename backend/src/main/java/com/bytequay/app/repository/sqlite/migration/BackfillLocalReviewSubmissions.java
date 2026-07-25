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
package com.bytequay.app.repository.sqlite.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Backfills {@code local_review_submission} from the immutable local
 * timeline rows whose payload has {@code reviewEvent=submitted},
 * ordered by {@code (created_at_ms, id)} into deterministic submission
 * sequences; {@code pr.local_review_epoch} becomes the maximum.
 *
 * <p>Outcome stamps come only from durable evidence: a batch whose
 * every root is now resolved/dismissed is completed; batches of a
 * terminal task or a PR already past local-open are canceled as
 * historical; everything else stays open and unbound — the queue
 * driver's admission re-derives the next root deterministically, and a
 * still-live legacy addressing turn reads as busy there, so the
 * backfill never guesses a binding and never risks a double dispatch.
 * Idempotent: an event already referenced by a row is skipped.
 */
@Component
public class BackfillLocalReviewSubmissions
        implements JavaMigration
{
    private static final Logger log = LoggerFactory.getLogger(BackfillLocalReviewSubmissions.class);

    private final ObjectMapper mapper;

    public BackfillLocalReviewSubmissions(ObjectMapper mapper)
    {
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Override
    public MigrationVersion getVersion()
    {
        return MigrationVersion.fromVersion("203");
    }

    @Override
    public String getDescription()
    {
        return "backfill local review submissions";
    }

    @Override
    public Integer getChecksum()
    {
        return 203_001;
    }

    @Override
    public boolean canExecuteInTransaction()
    {
        return true;
    }

    @Override
    public void migrate(Context context)
            throws SQLException
    {
        Connection connection = context.getConnection();
        List<SubmittedEvent> events = loadSubmittedEvents(connection);
        Map<String, List<SubmittedEvent>> byTask = new LinkedHashMap<>();
        for (SubmittedEvent event : events) {
            byTask.computeIfAbsent(event.taskId, ignored -> new ArrayList<>()).add(event);
        }
        int created = 0;
        for (Map.Entry<String, List<SubmittedEvent>> entry : byTask.entrySet()) {
            created += backfillTask(connection, entry.getKey(), entry.getValue());
        }
        if (created > 0) {
            log.info("Backfilled {} local review submission batch(es)", created);
        }
    }

    private int backfillTask(Connection connection, String taskId, List<SubmittedEvent> events)
            throws SQLException
    {
        long seq = selectLong(connection,
                "SELECT COALESCE(MAX(submission_seq), 0) FROM local_review_submission WHERE task_id = ?",
                taskId);
        int created = 0;
        for (SubmittedEvent event : events) {
            seq++;
            if (exists(connection,
                    "SELECT 1 FROM local_review_submission WHERE timeline_event_id = ?", event.id)) {
                continue;
            }
            List<String> roots = commentIds(event.payloadJson);
            Outcome outcome = outcomeFor(connection, event, roots);
            insertRow(connection, taskId, event, seq, roots, outcome);
            created++;
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE pr SET local_review_epoch = MAX(local_review_epoch, ?) WHERE id = ?")) {
            update.setLong(1, seq);
            update.setString(2, events.getFirst().prId);
            update.executeUpdate();
        }
        return created;
    }

    private Outcome outcomeFor(Connection connection, SubmittedEvent event, List<String> roots)
            throws SQLException
    {
        boolean historical = exists(connection,
                "SELECT 1 FROM tasks WHERE id = ? "
                        + "AND status IN ('COMPLETED', 'REMOTE_CLOSED', 'CANCELED')", event.taskId)
                || !exists(connection,
                        "SELECT 1 FROM pr WHERE id = ? AND status = 'local-open'", event.prId);
        if (historical) {
            return Outcome.canceled(event.createdAtMs);
        }
        for (String root : roots) {
            if (!exists(connection,
                    "SELECT 1 FROM pr_comment WHERE id = ? "
                            + "AND (resolved_at_ms IS NOT NULL OR dismissed_at_ms IS NOT NULL)", root)) {
                return Outcome.open();
            }
        }
        // Every submitted root is closed: completed at the addressed
        // watermark when one exists, else at the submission itself.
        Long watermark = selectNullableLong(connection,
                "SELECT local_addressed_through_ms FROM pr WHERE id = ?", event.prId);
        return Outcome.completed(watermark != null ? watermark : event.createdAtMs);
    }

    private void insertRow(
            Connection connection, String taskId, SubmittedEvent event,
            long seq, List<String> roots, Outcome outcome)
            throws SQLException
    {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO local_review_submission (id, timeline_event_id, task_id, pr_id, "
                        + "submission_seq, root_ids_json, root_snapshot_json, submitted_through_ms, "
                        + "attempt, failures, created_at_ms, completed_at_ms, canceled_at_ms, "
                        + "cancel_reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, ?)")) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, event.id);
            insert.setString(3, taskId);
            insert.setString(4, event.prId);
            insert.setLong(5, seq);
            insert.setString(6, toJson(roots));
            insert.setString(7, snapshotJson(roots));
            insert.setLong(8, event.createdAtMs);
            insert.setLong(9, event.createdAtMs);
            setNullableLong(insert, 10, outcome.completedAtMs);
            setNullableLong(insert, 11, outcome.canceledAtMs);
            insert.setString(12, outcome.cancelReason);
            insert.executeUpdate();
        }
    }

    private List<SubmittedEvent> loadSubmittedEvents(Connection connection)
            throws SQLException
    {
        List<SubmittedEvent> events = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT e.id, e.pr_id, p.task_id, e.created_at_ms, e.payload_json "
                        + "FROM pr_timeline_event e JOIN pr p ON p.id = e.pr_id "
                        + "WHERE p.task_id IS NOT NULL AND e.event_type = 'review' "
                        + "AND e.actor = 'user' AND e.is_local_only = 1 "
                        + "AND e.payload_json LIKE '%\"reviewEvent\":\"submitted\"%' "
                        + "ORDER BY e.created_at_ms, e.id");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                events.add(new SubmittedEvent(
                        rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getLong(4), rows.getString(5)));
            }
        }
        return events;
    }

    private List<String> commentIds(String payloadJson)
    {
        try {
            JsonNode ids = mapper.readTree(payloadJson).path("commentIds");
            List<String> roots = new ArrayList<>();
            for (JsonNode id : ids) {
                if (!id.asText("").isBlank()) {
                    roots.add(id.asText());
                }
            }
            return roots;
        }
        catch (Exception e) {
            return List.of();
        }
    }

    private String snapshotJson(List<String> roots)
    {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (int i = 0; i < roots.size(); i++) {
            snapshot.add(Map.of("id", roots.get(i), "order", i));
        }
        return toJson(snapshot);
    }

    private String toJson(Object value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (Exception e) {
            throw new IllegalStateException("failed to serialise backfill snapshot", e);
        }
    }

    private static boolean exists(Connection connection, String sql, String arg)
            throws SQLException
    {
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, arg);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static long selectLong(Connection connection, String sql, String arg)
            throws SQLException
    {
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, arg);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static Long selectNullableLong(Connection connection, String sql, String arg)
            throws SQLException
    {
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, arg);
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                long value = rows.getLong(1);
                return rows.wasNull() ? null : value;
            }
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException
    {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        }
        else {
            statement.setLong(index, value);
        }
    }

    private record SubmittedEvent(
            String id, String prId, String taskId, long createdAtMs, String payloadJson) {}

    private record Outcome(Long completedAtMs, Long canceledAtMs, String cancelReason)
    {
        static Outcome open()
        {
            return new Outcome(null, null, null);
        }

        static Outcome completed(long atMs)
        {
            return new Outcome(atMs, null, null);
        }

        static Outcome canceled(long atMs)
        {
            return new Outcome(null, atMs, "backfill_historical");
        }
    }
}
