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

import com.bytequay.core.context.ContextUsageEvent;
import com.bytequay.core.store.ContextUsageStore;
import com.bytequay.persistence.sqlite.entity.AppSettingEntity;
import com.bytequay.persistence.sqlite.entity.ContextUsageFactEntity;
import com.bytequay.persistence.sqlite.repository.AppSettingJpaRepository;
import com.bytequay.persistence.sqlite.repository.ContextUsageFactJpaRepository;
import com.bytequay.persistence.sqlite.repository.ContextUsageFactJpaRepository.Fact;
import com.google.common.hash.Hashing;
import org.springframework.data.domain.PageRequest;
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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Idempotent, workspace-scoped usage facts in the existing SQLite database. */
@Repository
public class SqliteContextUsageStore
        implements ContextUsageStore
{
    private static final String TRACKING_PREFIX = "workspace.contextUsageTrackingSince.";

    private final ContextUsageFactJpaRepository facts;
    private final AppSettingJpaRepository settings;

    public SqliteContextUsageStore(ContextUsageFactJpaRepository facts, AppSettingJpaRepository settings)
    {
        this.facts = requireNonNull(facts, "facts is null");
        this.settings = requireNonNull(settings, "settings is null");
    }

    @Override
    @Transactional
    public void record(ContextUsageEvent event)
    {
        requireNonNull(event, "event is null");
        String id = digest(identity(event));
        String payload = digest(payload(event));
        int changed = facts.insertOrPromote(new ContextUsageFactEntity(id, payload, event));
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
        return facts.listTask(requireText(workspaceId), requireText(taskId), PageRequest.of(0, limit)).stream()
                .map(SqliteContextUsageStore::event).collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public void forEach(String workspaceId, Optional<Instant> since, Consumer<ContextUsageEvent> consumer)
    {
        requireNonNull(since, "since is null");
        requireNonNull(consumer, "consumer is null");
        String workspace = requireText(workspaceId);
        try (Stream<Fact> rows = since.map(value -> facts.streamWorkspaceSince(workspace, value.toEpochMilli()))
                .orElseGet(() -> facts.streamWorkspace(workspace))) {
            rows.map(SqliteContextUsageStore::event).forEach(consumer);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContextUsageEvent.Note> findSelectedNote(String workspaceId, String reviewRunId, String entryId, long version)
    {
        checkArgument(version > 0, "note version must be positive");
        return facts.selectedNote(requireText(workspaceId), requireText(reviewRunId), requireText(entryId), version,
                        ContextUsageEvent.Stage.SELECTED, PageRequest.of(0, 1)).stream()
                .findFirst().flatMap(row -> event(row).note());
    }

    private static ContextUsageEvent event(Fact row)
    {
        String entryId = row.getEntryId();
        String scopeId = row.getScopeId();
        ContextUsageEvent.Preparation preparation = row.getObservedPreparation();
        return new ContextUsageEvent(row.getWorkspaceId(), row.getTaskId(), Optional.ofNullable(row.getTurnId()),
                Optional.ofNullable(row.getReviewRunId()), row.getTaskKind(), row.getStage(), row.getDelivery(), row.getReceiptRef(),
                entryId == null ? Optional.empty() : Optional.of(new ContextUsageEvent.Note(entryId, row.getEntryVersion(),
                        row.getEntryKind(), scopeId, row.getEntryTitle())),
                preparation == null ? Optional.empty() : Optional.of(new ContextUsageEvent.Scope(scopeId, preparation)),
                Optional.ofNullable(row.getContextEnabled()), Instant.ofEpochMilli(row.getOccurredAtMillis()));
    }

    private void delivery(String workspaceId, String receiptRef, ContextUsageEvent.Stage stage, ContextUsageEvent.Delivery value)
    {
        requireNonNull(stage, "stage is null");
        facts.updateDelivery(requireText(workspaceId), requireText(receiptRef), stage, value, ContextUsageEvent.Delivery.CONFIRMED);
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
}
