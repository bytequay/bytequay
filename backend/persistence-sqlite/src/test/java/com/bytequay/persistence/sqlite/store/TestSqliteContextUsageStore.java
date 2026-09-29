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
import com.bytequay.persistence.sqlite.entity.AppSettingEntity;
import com.google.common.collect.ImmutableList;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqliteContextUsageStore
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_PATH = Path.of(System.getProperty("java.io.tmpdir"),
            "bytequay-context-usage-" + UUID.randomUUID() + ".db").toString();
    private static final Instant AT = Instant.parse("2026-09-24T00:00:00Z");

    @Autowired
    private SqliteContextUsageStore store;

    @Autowired
    private EntityManager entities;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE_PATH, "create-drop");
    }

    @Test
    void retriesAndDeliveryUpdatesDoNotAddUses()
    {
        ContextUsageEvent offered = event("workspace-a", ContextUsageEvent.Stage.PROVIDED, ContextUsageEvent.Delivery.PREPARED);
        store.record(offered);
        store.record(offered);
        assertThat(noteRows("workspace-a", Optional.empty())).hasSize(1)
                .allMatch(row -> row.delivery() == ContextUsageEvent.Delivery.PREPARED);

        store.markUncertain("workspace-a", "offer-1", ContextUsageEvent.Stage.PROVIDED);
        store.confirm("workspace-a", "offer-1", ContextUsageEvent.Stage.PROVIDED);
        store.record(offered);
        assertThat(noteRows("workspace-a", Optional.empty())).hasSize(1)
                .allMatch(row -> row.delivery() == ContextUsageEvent.Delivery.CONFIRMED);

        ContextUsageEvent conflicting = new ContextUsageEvent(offered.workspaceId(), offered.taskId(), offered.turnId(), offered.reviewRunId(),
                offered.taskKind(), offered.stage(), offered.delivery(), offered.receiptRef(),
                Optional.of(new ContextUsageEvent.Note("knowledge:root", 1, ContextEntryKind.KNOWLEDGE, "root", "Different title")),
                Optional.empty(), Optional.empty(), AT);
        assertThatThrownBy(() -> store.record(conflicting)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void trackingAndActivityAreWorkspaceScoped()
    {
        store.record(new ContextUsageEvent("workspace-a", "task-1", Optional.of("turn-1"), Optional.empty(),
                ContextUsageEvent.TaskKind.DEVELOPMENT, ContextUsageEvent.Stage.ELIGIBLE, ContextUsageEvent.Delivery.CONFIRMED,
                "turn-1", Optional.empty(), Optional.empty(), Optional.of(true), AT));
        store.record(event("workspace-a", ContextUsageEvent.Stage.OPENED, ContextUsageEvent.Delivery.CONFIRMED));
        store.record(event("workspace-b", ContextUsageEvent.Stage.OPENED, ContextUsageEvent.Delivery.CONFIRMED));

        assertThat(store.trackingSince("workspace-a")).contains(AT);
        assertThat(store.trackingSince("workspace-b")).isEmpty();
        assertThat(noteRows("workspace-a", Optional.of(AT.plusSeconds(1)))).isEmpty();
        assertThat(noteRows("workspace-a", Optional.empty())).hasSize(1);
        ArrayList<ContextUsageEvent> rows = new ArrayList<>();
        store.forEach("workspace-a", Optional.empty(), rows::add);
        assertThat(rows.stream().map(ContextUsageEvent::stage).collect(ImmutableList.toImmutableList()))
                .containsExactly(ContextUsageEvent.Stage.ELIGIBLE, ContextUsageEvent.Stage.OPENED);
    }

    @Test
    void originalReviewReadCanResolveOnlyItsFrozenSelectedVersion()
    {
        ContextUsageEvent.Note selected = new ContextUsageEvent.Note("knowledge:root", 2, ContextEntryKind.KNOWLEDGE, "root", "Overview v2");
        store.record(new ContextUsageEvent("workspace-a", "review-task", Optional.empty(), Optional.of("run-1"),
                ContextUsageEvent.TaskKind.PR_REVIEW, ContextUsageEvent.Stage.SELECTED, ContextUsageEvent.Delivery.CONFIRMED,
                "context:run-1", Optional.of(selected), Optional.empty(), Optional.empty(), AT));

        assertThat(store.findSelectedNote("workspace-a", "run-1", selected.id(), 2)).contains(selected);
        assertThat(store.findSelectedNote("workspace-a", "run-1", selected.id(), 1)).isEmpty();
        assertThat(store.findSelectedNote("workspace-b", "run-1", selected.id(), 2)).isEmpty();
        assertThat(store.findSelectedNote("workspace-a", "run-2", selected.id(), 2)).isEmpty();
    }

    @Test
    void taskDetailFactsAreBoundedAndWorkspaceScoped()
    {
        ContextUsageEvent first = event("workspace-a", ContextUsageEvent.Stage.FOUND, ContextUsageEvent.Delivery.PREPARED);
        ContextUsageEvent second = new ContextUsageEvent(first.workspaceId(), first.taskId(), first.turnId(), first.reviewRunId(),
                first.taskKind(), ContextUsageEvent.Stage.OPENED, ContextUsageEvent.Delivery.CONFIRMED, "read-2", first.note(),
                first.scope(), first.contextEnabled(), AT.plusSeconds(1));
        store.record(first);
        store.record(second);
        store.record(event("workspace-b", ContextUsageEvent.Stage.FOUND, ContextUsageEvent.Delivery.CONFIRMED));

        assertThat(store.listTask("workspace-a", "task-1", 1)).containsExactly(second);
        assertThat(store.listTask("workspace-a", "task-1", 2)).containsExactly(second, first);
        assertThat(store.listTask("workspace-b", "task-1", 2)).hasSize(1);
        assertThat(store.listTask("workspace-a", "other-task", 2)).isEmpty();
        assertThatThrownBy(() -> store.listTask("workspace-a", "task-1", 1002)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordingUsageDoesNotDetachOtherManagedState()
    {
        AppSettingEntity setting = new AppSettingEntity("outer.transaction.setting", "before");
        entities.persist(setting);

        store.record(event("workspace-a", ContextUsageEvent.Stage.FOUND, ContextUsageEvent.Delivery.PREPARED));
        setting.setValue("after");
        entities.flush();
        entities.clear();

        assertThat(entities.find(AppSettingEntity.class, "outer.transaction.setting").getValue()).isEqualTo("after");
    }

    private List<ContextUsageEvent> noteRows(String workspaceId, Optional<Instant> since)
    {
        List<ContextUsageEvent> rows = new ArrayList<>();
        store.forEach(workspaceId, since, event -> event.note().filter(note -> note.id().equals("knowledge:root")).ifPresent(note -> rows.add(event)));
        return rows;
    }

    private static ContextUsageEvent event(String workspace, ContextUsageEvent.Stage stage, ContextUsageEvent.Delivery delivery)
    {
        return new ContextUsageEvent(workspace, "task-1", Optional.of("turn-1"), Optional.empty(),
                ContextUsageEvent.TaskKind.DEVELOPMENT, stage, delivery, "offer-1",
                Optional.of(new ContextUsageEvent.Note("knowledge:root", 1, ContextEntryKind.KNOWLEDGE, "root", "Overview")),
                Optional.empty(), Optional.empty(), AT);
    }
}
