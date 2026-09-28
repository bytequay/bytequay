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
package com.bytequay.app.context;

import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextEntryOrigin;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextUsageEvent;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.store.ContextUsageStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestContextUsageService
{
    private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");
    private static final String ENTRY = "knowledge:iceberg:commits";
    private static final String SCOPE = "iceberg";

    @Test
    void countsConfirmedUseByCurrentVersionAndShowsMissingDemandSeparately()
    {
        ContextScope component = new ContextScope(SCOPE, "Iceberg", new ContextMatch.Selected(ImmutableList.of("plugin/iceberg/"), ImmutableList.of()),
                true, Optional.empty(), Optional.empty());
        ContextEntry current = new ContextEntry(ENTRY, ContextEntryKind.KNOWLEDGE, SCOPE, "Iceberg", "Summary", "Body", ImmutableList.of(),
                ImmutableList.of(), ContextEntryOrigin.USER, true, 2);
        ContextEntry disabled = new ContextEntry(ContextEntry.knowledgeId(SCOPE), ContextEntryKind.KNOWLEDGE, SCOPE, "Overview", "Summary", "Body",
                ImmutableList.of(), ImmutableList.of(), ContextEntryOrigin.USER, false, 1);
        RepositoryNotebook notebook = new RepositoryNotebook(1, 2, true, true, ImmutableList.of(),
                ImmutableList.of(ContextScope.project(), component), ImmutableList.of(disabled, current), ImmutableList.of(), ImmutableList.of(), Optional.empty());
        RepositoryContextService notebooks = mock(RepositoryContextService.class);
        ContextUsageStore store = mock(ContextUsageStore.class);
        when(notebooks.getNotebook("workspace")).thenReturn(notebook);
        when(store.trackingSince("workspace")).thenReturn(Optional.of(NOW.minusSeconds(60)));
        List<ContextUsageEvent> facts = ImmutableList.of(
                event("task-old", ContextUsageEvent.Stage.ELIGIBLE, ContextUsageEvent.Delivery.CONFIRMED, Optional.empty(), Optional.empty(), Optional.of(true)),
                event("task-old", ContextUsageEvent.Stage.PROVIDED, ContextUsageEvent.Delivery.CONFIRMED, Optional.of(note(1)), Optional.empty(), Optional.empty()),
                event("task-new", ContextUsageEvent.Stage.ELIGIBLE, ContextUsageEvent.Delivery.CONFIRMED, Optional.empty(), Optional.empty(), Optional.of(true)),
                event("task-new", ContextUsageEvent.Stage.SELECTED, ContextUsageEvent.Delivery.CONFIRMED, Optional.of(note(2)), Optional.empty(), Optional.empty()),
                event("task-new", ContextUsageEvent.Stage.PROVIDED, ContextUsageEvent.Delivery.PREPARED, Optional.of(note(2)), Optional.empty(), Optional.empty()),
                event("task-new", ContextUsageEvent.Stage.FOUND, ContextUsageEvent.Delivery.CONFIRMED, Optional.of(note(2)), Optional.empty(), Optional.empty()),
                event("task-new", ContextUsageEvent.Stage.SCOPE_DEMAND, ContextUsageEvent.Delivery.CONFIRMED, Optional.empty(),
                        Optional.of(new ContextUsageEvent.Scope(SCOPE, ContextUsageEvent.Preparation.EMPTY)), Optional.empty()),
                event("task-new", ContextUsageEvent.Stage.NO_MATCH, ContextUsageEvent.Delivery.CONFIRMED, Optional.empty(), Optional.empty(), Optional.empty()));
        doAnswer(invocation -> {
            Consumer<ContextUsageEvent> consumer = invocation.getArgument(2);
            facts.forEach(consumer);
            return null;
        }).when(store).forEach(eq("workspace"), any(), any());

        ContextUsageService.Overview result = new ContextUsageService(store, notebooks, Clock.fixed(NOW, ZoneOffset.UTC))
                .overview("workspace", "all", 0);

        assertThat(result.summary().eligibleDevelopmentTasks()).isEqualTo(2);
        assertThat(result.summary().tasksProvidedOrOpened()).isEqualTo(1);
        assertThat(result.summary().searchesWithNoMatchingScope()).isEqualTo(1);
        assertThat(result.summary().scopesDemandedWhileMissing()).isEqualTo(1);
        assertThat(result.summary().scopesStillMissingAfterDemand()).isZero();
        ContextUsageService.EntryRow row = result.entries().stream().filter(entry -> entry.entryId().equals(ENTRY)).findFirst().orElseThrow();
        assertThat(row.currentVersion()).contains(2L);
        assertThat(row.currentVersionCounts().selectedTasks()).isEqualTo(1);
        assertThat(row.currentVersionCounts().providedTasks()).isZero();
        assertThat(row.currentVersionCounts().searchHits()).isEqualTo(1);
        assertThat(row.allVersionCounts().providedTasks()).isEqualTo(1);
        assertThat(result.scopeGaps()).singleElement().satisfies(gap -> {
            assertThat(gap.currentPreparation()).isEqualTo(ContextUsageEvent.Preparation.NOT_PREPARED);
            assertThat(gap.currentKnowledge()).isEqualTo(ContextUsageService.Knowledge.PRESENT);
            assertThat(gap.observedPreparationCounts()).containsEntry("EMPTY", 1);
        });
    }

    private static ContextUsageEvent event(String task, ContextUsageEvent.Stage stage, ContextUsageEvent.Delivery delivery,
            Optional<ContextUsageEvent.Note> note, Optional<ContextUsageEvent.Scope> scope, Optional<Boolean> enabled)
    {
        return new ContextUsageEvent("workspace", task, Optional.of(task + "-turn"), Optional.empty(), ContextUsageEvent.TaskKind.DEVELOPMENT,
                stage, delivery, task + ":" + stage + ":" + note.map(ContextUsageEvent.Note::version).orElse(0L),
                note, scope, enabled, NOW.minusSeconds(60));
    }

    private static ContextUsageEvent.Note note(long version)
    {
        return new ContextUsageEvent.Note(ENTRY, version, ContextEntryKind.KNOWLEDGE, SCOPE, "Iceberg");
    }
}
