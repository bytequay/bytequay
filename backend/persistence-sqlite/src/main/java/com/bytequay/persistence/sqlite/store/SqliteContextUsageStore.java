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
import com.bytequay.persistence.sqlite.entity.AppSettingEntity;
import com.bytequay.persistence.sqlite.repository.AppSettingJpaRepository;
import com.google.common.hash.Hashing;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Idempotent, workspace-scoped usage facts in the existing SQLite database. */
@Repository
public class SqliteContextUsageStore
        implements ContextUsageStore
{
    private static final int ACTIVITY_PAGE_SIZE = 20;
    // Scalar reads stay current after bulk delivery updates and do not retain every fact during aggregation.
    private static final String FACTS = "SELECT f.workspaceId, f.taskId, f.turnId, f.reviewRunId, f.taskKind, f.stage, f.delivery, "
            + "f.receiptRef, f.entryId, f.entryVersion, f.entryKind, f.scopeId, f.entryTitle, f.observedPreparation, "
            + "f.contextEnabled, f.occurredAtMillis FROM ContextUsageFactEntity f";
    private static final String TRACKING_PREFIX = "workspace.contextUsageTrackingSince.";

    private final EntityManager entities;
    private final AppSettingJpaRepository settings;

    public SqliteContextUsageStore(EntityManager entities, AppSettingJpaRepository settings)
    {
        this.entities = requireNonNull(entities, "entities is null");
        this.settings = requireNonNull(settings, "settings is null");
    }

    @Override
    @Transactional
    public void record(ContextUsageEvent event)
    {
        requireNonNull(event, "event is null");
        String id = digest(identity(event));
        String payload = digest(payload(event));
        ContextUsageEvent.Note note = event.note().orElse(null);
        ContextUsageEvent.Scope scope = event.scope().orElse(null);
        // One SQLite upsert keeps retries atomic across concurrent task workers.
        int changed = entities.createNativeQuery("""
                INSERT INTO context_usage_fact (id, workspace_id, task_id, turn_id, review_run_id, task_kind, stage, delivery, receipt_ref,
                    entry_id, entry_version, entry_kind, scope_id, entry_title, observed_preparation, context_enabled, occurred_at_ms, payload_digest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET delivery = CASE
                    WHEN context_usage_fact.delivery = 'CONFIRMED' OR excluded.delivery = 'PREPARED' THEN context_usage_fact.delivery
                    ELSE excluded.delivery END
                WHERE context_usage_fact.payload_digest = excluded.payload_digest
                """)
                .setParameter(1, id)
                .setParameter(2, event.workspaceId())
                .setParameter(3, event.taskId())
                .setParameter(4, event.turnId().orElse(null))
                .setParameter(5, event.reviewRunId().orElse(null))
                .setParameter(6, event.taskKind().name())
                .setParameter(7, event.stage().name())
                .setParameter(8, event.delivery().name())
                .setParameter(9, event.receiptRef())
                .setParameter(10, note == null ? null : note.id())
                .setParameter(11, note == null ? null : note.version())
                .setParameter(12, note == null ? null : note.kind().name())
                .setParameter(13, note == null ? scope == null ? null : scope.id() : note.scopeId())
                .setParameter(14, note == null ? null : note.title())
                .setParameter(15, scope == null ? null : scope.preparation().name())
                .setParameter(16, event.contextEnabled().orElse(null))
                .setParameter(17, event.occurredAt().toEpochMilli())
                .setParameter(18, payload)
                .executeUpdate();
        checkState(changed == 1, "Context usage receipt identity was reused with different content");
        if (event.stage() == ContextUsageEvent.Stage.ELIGIBLE) {
            String key = TRACKING_PREFIX + event.workspaceId();
            if (!settings.existsById(key)) {
                settings.save(new AppSettingEntity(key, event.occurredAt().toString()));
            }
        }
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
        return settings.findById(TRACKING_PREFIX + requireText(workspaceId)).map(setting -> Instant.parse(setting.getValue()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContextUsageEvent> listTask(String workspaceId, String taskId, int limit)
    {
        checkArgument(limit >= 1 && limit <= 1001, "Task usage display limit is out of range");
        return entities.createQuery(FACTS + " WHERE f.workspaceId = :workspace AND f.taskId = :task "
                        + "ORDER BY f.occurredAtMillis DESC, f.id DESC", Tuple.class)
                .setParameter("workspace", requireText(workspaceId))
                .setParameter("task", requireText(taskId))
                .setMaxResults(limit)
                .getResultList().stream().map(SqliteContextUsageStore::event).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public void forEach(String workspaceId, Optional<Instant> since, Consumer<ContextUsageEvent> consumer)
    {
        requireNonNull(since, "since is null");
        requireNonNull(consumer, "consumer is null");
        TypedQuery<Tuple> query = entities.createQuery(FACTS + " WHERE f.workspaceId = :workspace"
                        + (since.isPresent() ? " AND f.occurredAtMillis >= :since" : "") + " ORDER BY f.occurredAtMillis, f.id", Tuple.class)
                .setParameter("workspace", requireText(workspaceId));
        since.ifPresent(value -> query.setParameter("since", value.toEpochMilli()));
        try (Stream<Tuple> rows = query.getResultStream()) {
            rows.map(SqliteContextUsageStore::event).forEach(consumer);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContextUsageEvent.Note> findSelectedNote(String workspaceId, String reviewRunId, String entryId, long version)
    {
        checkArgument(version > 0, "note version must be positive");
        return entities.createQuery(FACTS + " WHERE f.workspaceId = :workspace AND f.reviewRunId = :run "
                        + "AND f.entryId = :entry AND f.entryVersion = :version AND f.stage = :stage", Tuple.class)
                .setParameter("workspace", requireText(workspaceId))
                .setParameter("run", requireText(reviewRunId))
                .setParameter("entry", requireText(entryId))
                .setParameter("version", version)
                .setParameter("stage", ContextUsageEvent.Stage.SELECTED)
                .setMaxResults(1)
                .getResultList().stream().findFirst().flatMap(row -> event(row).note());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContextUsageEvent> activities(String workspaceId, String entryId, Optional<Instant> since, int page)
    {
        checkPage(page);
        requireNonNull(since, "since is null");
        return activitiesQuery(workspaceId, entryId, since)
                .setFirstResult(Math.multiplyExact(page, ACTIVITY_PAGE_SIZE))
                .setMaxResults(ACTIVITY_PAGE_SIZE)
                .getResultList().stream().map(SqliteContextUsageStore::event).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasMoreActivities(String workspaceId, String entryId, Optional<Instant> since, int page)
    {
        checkPage(page);
        requireNonNull(since, "since is null");
        return !activitiesQuery(workspaceId, entryId, since)
                .setFirstResult(Math.multiplyExact(page + 1, ACTIVITY_PAGE_SIZE))
                .setMaxResults(1)
                .getResultList().isEmpty();
    }

    private TypedQuery<Tuple> activitiesQuery(String workspaceId, String entryId, Optional<Instant> since)
    {
        TypedQuery<Tuple> query = entities.createQuery(FACTS + " WHERE f.workspaceId = :workspace AND f.entryId = :entry"
                        + (since.isPresent() ? " AND f.occurredAtMillis >= :since" : "") + " ORDER BY f.occurredAtMillis DESC, f.id DESC",
                Tuple.class)
                .setParameter("workspace", requireText(workspaceId))
                .setParameter("entry", requireText(entryId));
        since.ifPresent(value -> query.setParameter("since", value.toEpochMilli()));
        return query;
    }

    private static ContextUsageEvent event(Tuple row)
    {
        String entryId = row.get(8, String.class);
        String scopeId = row.get(11, String.class);
        ContextUsageEvent.Preparation preparation = row.get(13, ContextUsageEvent.Preparation.class);
        return new ContextUsageEvent(row.get(0, String.class), row.get(1, String.class), Optional.ofNullable(row.get(2, String.class)),
                Optional.ofNullable(row.get(3, String.class)), row.get(4, ContextUsageEvent.TaskKind.class),
                row.get(5, ContextUsageEvent.Stage.class), row.get(6, ContextUsageEvent.Delivery.class), row.get(7, String.class),
                entryId == null ? Optional.empty() : Optional.of(new ContextUsageEvent.Note(entryId, row.get(9, Long.class),
                        row.get(10, ContextEntryKind.class), scopeId, row.get(12, String.class))),
                preparation == null ? Optional.empty() : Optional.of(new ContextUsageEvent.Scope(scopeId, preparation)),
                Optional.ofNullable(row.get(14, Boolean.class)), Instant.ofEpochMilli(row.get(15, Long.class)));
    }

    private void delivery(String workspaceId, String receiptRef, ContextUsageEvent.Stage stage, ContextUsageEvent.Delivery value)
    {
        requireNonNull(stage, "stage is null");
        entities.createQuery("UPDATE ContextUsageFactEntity SET delivery = :value WHERE workspaceId = :workspace "
                        + "AND receiptRef = :receipt AND stage = :stage AND delivery <> :confirmed")
                .setParameter("value", value)
                .setParameter("workspace", requireText(workspaceId))
                .setParameter("receipt", requireText(receiptRef))
                .setParameter("stage", stage)
                .setParameter("confirmed", ContextUsageEvent.Delivery.CONFIRMED)
                .executeUpdate();
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
