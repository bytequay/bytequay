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
    void countsConfirmedFindsAndOpensByCurrentVersionAndListsEveryNote()
    {
        ContextScope component = new ContextScope(SCOPE, "Iceberg", new ContextMatch.Selected(ImmutableList.of("plugin/iceberg/"), ImmutableList.of()),
                true, Optional.empty(), Optional.empty());
        ContextEntry current = new ContextEntry(ENTRY, ContextEntryKind.KNOWLEDGE, SCOPE, "Iceberg", "Summary", "Body", ImmutableList.of(),
                ImmutableList.of(), ContextEntryOrigin.USER, true, 2);
        ContextEntry disabled = new ContextEntry(ContextEntry.knowledgeId(SCOPE), ContextEntryKind.KNOWLEDGE, SCOPE, "Overview", "Summary", "Body",
                ImmutableList.of(), ImmutableList.of(), ContextEntryOrigin.USER, false, 1);
        RepositoryNotebook notebook = new RepositoryNotebook(1, 2, true, true, ImmutableList.of(),
                ImmutableList.of(ContextScope.project(), component), ImmutableList.of(disabled, current), ImmutableList.of(), Optional.empty());
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

        ContextUsageService.Overview result = new ContextUsageService(store, notebooks, mock(ContextGradeService.class), mock(ContextWebLookups.class),
                Clock.fixed(NOW, ZoneOffset.UTC))
                .overview("workspace", "all");

        assertThat(result.summary().eligibleDevelopmentTasks()).isEqualTo(2);
        assertThat(result.summary().developmentTasksFoundOrOpened()).isEqualTo(1);
        assertThat(result.summary().contextOffTasks()).isZero();
        ContextUsageService.EntryRow row = result.entries().stream().filter(entry -> entry.entryId().equals(ENTRY)).findFirst().orElseThrow();
        assertThat(row.currentVersion()).contains(2L);
        assertThat(row.foundTasks()).isEqualTo(1);
        assertThat(row.openedTasks()).isZero();
        assertThat(row.lastConfirmedUseAt()).contains(NOW.minusSeconds(60));
        assertThat(result.entries()).extracting(ContextUsageService.EntryRow::entryId).containsExactly(ENTRY, ContextEntry.knowledgeId(SCOPE));
    }

    @Test
    void comparesWebLookupsOfTasksThatRanWithNotesOnAndOff()
    {
        RepositoryContextService notebooks = mock(RepositoryContextService.class);
        ContextUsageStore store = mock(ContextUsageStore.class);
        ContextWebLookups lookups = mock(ContextWebLookups.class);
        when(notebooks.getNotebook("workspace")).thenReturn(RepositoryNotebook.empty());
        List<ContextUsageEvent> facts = ImmutableList.of(eligible("task-on", "on-1", true), eligible("task-on", "on-2", true), eligible("task-off", "off-1", false),
                eligible("task-mixed", "mixed-1", true), eligible("task-mixed", "mixed-2", false), eligible("task-quiet", "quiet-1", true));
        doAnswer(invocation -> {
            Consumer<ContextUsageEvent> consumer = invocation.getArgument(2);
            facts.forEach(consumer);
            return null;
        }).when(store).forEach(eq("workspace"), any(), any());
        when(lookups.find("workspace")).thenReturn(ImmutableList.of(settled("task-on", "on-1", 1), settled("task-on", "on-2", 2), settled("task-off", "off-1", 5),
                settled("task-mixed", "mixed-1", 4), settled("task-mixed", "mixed-2", 4), settled("task-quiet", "quiet-1", 0), settled("task-other", "other-1", 9)));

        ContextUsageService.Summary summary = new ContextUsageService(store, notebooks, mock(ContextGradeService.class), lookups, Clock.fixed(NOW, ZoneOffset.UTC))
                .overview("workspace", "all").summary();

        assertThat(summary.webLookups()).isEqualTo(new ContextUsageService.WebLookups(new ContextUsageService.Lookups(2, 3), new ContextUsageService.Lookups(1, 5)));
    }

    private static ContextUsageEvent eligible(String task, String turn, boolean notesOn)
    {
        return new ContextUsageEvent("workspace", task, Optional.of(turn), Optional.empty(), ContextUsageEvent.TaskKind.DEVELOPMENT, ContextUsageEvent.Stage.ELIGIBLE,
                ContextUsageEvent.Delivery.CONFIRMED, "context-eligible:" + turn, Optional.empty(), Optional.empty(), Optional.of(notesOn), NOW.minusSeconds(60));
    }

    private static ContextWebLookups.Turn settled(String task, String turn, int lookups)
    {
        return new ContextWebLookups.Turn(task, turn, lookups, NOW.minusSeconds(30));
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
