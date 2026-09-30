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
import com.bytequay.core.context.ContextUsageEvent;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.store.ContextUsageStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** A read-only view of which retained tasks and reviews found or opened each note. */
@Service
public class ContextUsageService
{
    private final ContextUsageStore store;
    private final RepositoryContextService notebooks;
    private final ContextGradeService grades;
    private final Clock clock;

    public ContextUsageService(ContextUsageStore store, RepositoryContextService notebooks, ContextGradeService grades, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.notebooks = requireNonNull(notebooks, "notebooks is null");
        this.grades = requireNonNull(grades, "grades is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** Aggregates the chosen window without reading note bodies or generating new knowledge; every note is listed. */
    public Overview overview(String workspaceId, String window)
    {
        RepositoryNotebook notebook = notebooks.getNotebook(workspaceId);
        Optional<Instant> since = since(window);
        Aggregation aggregation = new Aggregation();
        store.forEach(workspaceId, since, aggregation::accept);
        notebook.entries().forEach(entry -> aggregation.entries.computeIfAbsent(entry.id(), id -> new EntryUsage(entry.id(), entry.title())));
        Map<String, ContextGradeService.Counts> counts = grades.counts(workspaceId, since.orElse(Instant.EPOCH));
        List<EntryRow> entries = aggregation.entries.values().stream().map(value -> value.row(notebook, counts))
                .sorted(Comparator.comparing((EntryRow row) -> row.currentVersion().isEmpty()).thenComparing(EntryRow::title, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(EntryRow::entryId))
                .collect(toImmutableList());
        Summary summary = new Summary(aggregation.development.size(), aggregation.reviews.size(), aggregation.off.size(),
                aggregation.foundOrOpened.getOrDefault(ContextUsageEvent.TaskKind.DEVELOPMENT, ImmutableSet.of()).size(),
                aggregation.foundOrOpened.getOrDefault(ContextUsageEvent.TaskKind.PR_REVIEW, ImmutableSet.of()).size());
        return new Overview(store.trackingSince(workspaceId), window, summary, entries);
    }

    private Optional<Instant> since(String window)
    {
        checkArgument(window != null && (window.equals("30d") || window.equals("all")), "Unsupported usage window");
        return window.equals("30d") ? Optional.of(Instant.now(clock).minus(Duration.ofDays(30))) : Optional.empty();
    }

    private static String workKey(ContextUsageEvent event)
    {
        return event.taskKind() == ContextUsageEvent.TaskKind.PR_REVIEW
                ? "review:" + event.reviewRunId().orElseThrow() : "development:" + event.taskId();
    }

    public record Overview(Optional<Instant> trackingSince, String window, Summary summary, List<EntryRow> entries)
    {
        public Overview
        {
            entries = ImmutableList.copyOf(entries);
        }
    }

    public record Summary(int eligibleDevelopmentTasks, int eligibleReviewRuns, int contextOffTasks, int developmentTasksFoundOrOpened,
            int reviewRunsFoundOrOpened) {}

    /** Found and opened count the note's current version; grades count tasks across its versions; a removed note has no current version. */
    public record EntryRow(String entryId, String title, Optional<Long> currentVersion, int foundTasks, int openedTasks, Optional<Instant> lastConfirmedUseAt,
            long helpedTasks, long notRelevantTasks, long wrongTasks) {}

    private static final class Aggregation
    {
        private final Set<String> development = new HashSet<>();
        private final Set<String> reviews = new HashSet<>();
        private final Set<String> off = new HashSet<>();
        private final Map<String, EntryUsage> entries = new HashMap<>();
        private final Map<ContextUsageEvent.TaskKind, Set<String>> foundOrOpened = new HashMap<>();

        private void accept(ContextUsageEvent event)
        {
            String work = workKey(event);
            if (event.stage() == ContextUsageEvent.Stage.ELIGIBLE && event.delivery() == ContextUsageEvent.Delivery.CONFIRMED) {
                (event.taskKind() == ContextUsageEvent.TaskKind.DEVELOPMENT ? development : reviews).add(work);
                if (!event.contextEnabled().orElseThrow()) {
                    off.add(work);
                }
            }
            event.note().ifPresent(note -> entries.computeIfAbsent(note.id(), id -> new EntryUsage(note.id(), note.title())));
            if (event.delivery() != ContextUsageEvent.Delivery.CONFIRMED) {
                return;
            }
            event.note().ifPresent(note -> {
                entries.get(note.id()).accept(note, event, work);
                if (event.stage() == ContextUsageEvent.Stage.FOUND || event.stage() == ContextUsageEvent.Stage.OPENED) {
                    foundOrOpened.computeIfAbsent(event.taskKind(), ignored -> new HashSet<>()).add(work);
                }
            });
        }
    }

    private static final class EntryUsage
    {
        private final String id;
        private String title;
        private Instant titleAt = Instant.MIN;
        private final Map<Long, Set<String>> found = new HashMap<>();
        private final Map<Long, Set<String>> opened = new HashMap<>();
        private Optional<Instant> lastUse = Optional.empty();

        private EntryUsage(String id, String title)
        {
            this.id = id;
            this.title = title;
        }

        private void accept(ContextUsageEvent.Note note, ContextUsageEvent event, String work)
        {
            if (event.occurredAt().isAfter(titleAt)) {
                title = note.title();
                titleAt = event.occurredAt();
            }
            if (event.stage() == ContextUsageEvent.Stage.FOUND) {
                found.computeIfAbsent(note.version(), ignored -> new HashSet<>()).add(work);
            }
            if (event.stage() == ContextUsageEvent.Stage.OPENED) {
                opened.computeIfAbsent(note.version(), ignored -> new HashSet<>()).add(work);
            }
            if (event.stage() == ContextUsageEvent.Stage.PROVIDED || event.stage() == ContextUsageEvent.Stage.OPENED) {
                if (lastUse.isEmpty() || event.occurredAt().isAfter(lastUse.orElseThrow())) {
                    lastUse = Optional.of(event.occurredAt());
                }
            }
        }

        private EntryRow row(RepositoryNotebook notebook, Map<String, ContextGradeService.Counts> counts)
        {
            ContextGradeService.Counts graded = counts.getOrDefault(id, new ContextGradeService.Counts(0, 0, 0));
            Optional<ContextEntry> current = notebook.entries().stream().filter(entry -> entry.id().equals(id)).findFirst();
            Optional<Long> version = current.map(ContextEntry::version);
            int foundTasks = version.map(value -> found.getOrDefault(value, ImmutableSet.of()).size()).orElse(0);
            int openedTasks = version.map(value -> opened.getOrDefault(value, ImmutableSet.of()).size()).orElse(0);
            return new EntryRow(id, current.map(ContextEntry::title).orElse(title), version, foundTasks, openedTasks, lastUse,
                    graded.helped(), graded.notRelevant(), graded.wrong());
        }
    }
}
