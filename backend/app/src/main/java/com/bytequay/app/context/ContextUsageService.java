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
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextUsageEvent;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.store.ContextUsageStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** A read-only view of confirmed notebook reach and known coverage gaps. */
@Service
public class ContextUsageService
{
    private static final int PAGE_SIZE = 20;

    private final ContextUsageStore store;
    private final RepositoryContextService notebooks;
    private final Clock clock;

    public ContextUsageService(ContextUsageStore store, RepositoryContextService notebooks, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.notebooks = requireNonNull(notebooks, "notebooks is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** Aggregates the chosen window without reading note bodies or generating new knowledge. */
    public Overview overview(String workspaceId, String window, int page)
    {
        RepositoryNotebook notebook = notebooks.getNotebook(workspaceId);
        Optional<Instant> since = since(window);
        checkPage(page);
        Aggregation aggregation = new Aggregation();
        store.forEach(workspaceId, since, aggregation::accept);
        notebook.entries().forEach(entry -> aggregation.entries.computeIfAbsent(entry.id(), id -> new EntryUsage(entry)));
        List<EntryRow> entries = aggregation.entries.values().stream().map(value -> value.row(notebook))
                .sorted(Comparator.comparing((EntryRow row) -> row.currentVersion().isEmpty()).thenComparing(EntryRow::title, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(EntryRow::entryId))
                .collect(toImmutableList());
        List<ScopeGapRow> gaps = aggregation.gaps.values().stream().map(value -> value.row(notebook))
                .sorted(Comparator.comparingInt(ScopeGapRow::demandingTasks).reversed().thenComparing(ScopeGapRow::scopeId)).collect(toImmutableList());
        int activeWithoutExposure = (int) notebook.entries().stream().filter(ContextEntry::enabled)
                .filter(entry -> notebook.scopes().stream().anyMatch(scope -> scope.enabled() && scope.id().equals(entry.scopeId())))
                .filter(entry -> !aggregation.entries.get(entry.id()).currentExposure(entry.version())).count();
        int stillMissing = (int) gaps.stream().filter(gap -> gap.currentKnowledge() == Knowledge.MISSING).count();
        Summary summary = new Summary(aggregation.development.size(), aggregation.reviews.size(), aggregation.off.size(),
                aggregation.providedOrOpened.size(), activeWithoutExposure, gaps.size(), stillMissing,
                aggregation.noScopeMatches.size(), aggregation.noNoteMatches.size());
        List<KindRow> kinds = new ArrayList<>();
        for (ContextUsageEvent.TaskKind taskKind : ContextUsageEvent.TaskKind.values()) {
            for (ContextEntryKind noteKind : ContextEntryKind.values()) {
                KindUsage value = aggregation.kinds.getOrDefault(new KindKey(taskKind, noteKind), new KindUsage());
                kinds.add(new KindRow(taskKind, noteKind, value.providedOrOpened.size(), value.found.size(), value.opened.size()));
            }
        }
        return new Overview(store.trackingSince(workspaceId), window, "RETAINED_TASKS_AND_REVIEWS", summary, ImmutableList.copyOf(kinds),
                page(entries, page), page(gaps, page), entries.size() > end(page), gaps.size() > end(page));
    }

    /** Lists the evidence behind one note's counts, including prepared and uncertain rows. */
    public Activities activities(String workspaceId, String entryId, String window, int page)
    {
        notebooks.getNotebook(workspaceId);
        checkArgument(entryId != null && !entryId.isBlank(), "entryId is required");
        checkPage(page);
        Optional<Instant> since = since(window);
        List<Activity> activities = store.activities(workspaceId, entryId, since, page).stream()
                .map(event -> new Activity(event.taskId(), event.reviewRunId(), event.turnId(), event.taskKind(),
                        event.note().orElseThrow().version(), event.stage(), event.delivery(), event.occurredAt(), event.receiptRef()))
                .collect(toImmutableList());
        return new Activities(entryId, activities, store.hasMoreActivities(workspaceId, entryId, since, page));
    }

    private Optional<Instant> since(String window)
    {
        checkArgument(window != null && (window.equals("30d") || window.equals("all")), "Unsupported usage window");
        return window.equals("30d") ? Optional.of(Instant.now(clock).minus(Duration.ofDays(30))) : Optional.empty();
    }

    private static void checkPage(int page)
    {
        checkArgument(page >= 0 && page <= 10_000, "Page is out of range");
    }

    private static int end(int page)
    {
        return Math.multiplyExact(page + 1, PAGE_SIZE);
    }

    private static <T> List<T> page(List<T> values, int page)
    {
        int start = Math.min(Math.multiplyExact(page, PAGE_SIZE), values.size());
        return ImmutableList.copyOf(values.subList(start, Math.min(end(page), values.size())));
    }

    private static String workKey(ContextUsageEvent event)
    {
        return event.taskKind() == ContextUsageEvent.TaskKind.PR_REVIEW
                ? "review:" + event.reviewRunId().orElseThrow() : "development:" + event.taskId();
    }

    public record Overview(Optional<Instant> trackingSince, String window, String coverage, Summary summary, List<KindRow> byTaskAndNoteKind,
            List<EntryRow> entries, List<ScopeGapRow> scopeGaps, boolean hasMoreEntries, boolean hasMoreScopeGaps)
    {
        public Overview
        {
            byTaskAndNoteKind = ImmutableList.copyOf(byTaskAndNoteKind);
            entries = ImmutableList.copyOf(entries);
            scopeGaps = ImmutableList.copyOf(scopeGaps);
        }
    }

    public record Summary(int eligibleDevelopmentTasks, int eligibleReviewRuns, int contextOffTasks, int tasksProvidedOrOpened,
            int activeNotesWithoutConfirmedDeliveryOrOpen, int scopesDemandedWhileMissing, int scopesStillMissingAfterDemand,
            int searchesWithNoMatchingScope, int searchesWithScopeButNoNote) {}

    public record KindRow(ContextUsageEvent.TaskKind taskKind, ContextEntryKind noteKind, int providedOrOpenedTasks, int foundTasks, int openedTasks) {}

    public record Counts(int selectedTasks, int providedTasks, int searchHits, int foundTasks, int openedTasks, int citedReviews) {}

    public record AllVersionCounts(int providedTasks, int foundTasks, int openedTasks) {}

    public record EntryRow(String entryId, ContextEntryKind kind, String scopeId, String title, Optional<Long> currentVersion,
            Optional<Boolean> currentEnabled, Counts currentVersionCounts, AllVersionCounts allVersionCounts, Optional<Instant> lastConfirmedUseAt) {}

    public enum Knowledge
    {
        PRESENT,
        MISSING,
        DISABLED
    }

    public record ScopeGapRow(String scopeId, int demandingTasks, Map<String, Integer> observedPreparationCounts,
            ContextUsageEvent.Preparation currentPreparation, Knowledge currentKnowledge)
    {
        public ScopeGapRow
        {
            observedPreparationCounts = ImmutableMap.copyOf(observedPreparationCounts);
        }
    }

    public record Activities(String entryId, List<Activity> activities, boolean hasMore)
    {
        public Activities
        {
            activities = ImmutableList.copyOf(activities);
        }
    }

    public record Activity(String taskId, Optional<String> reviewRunId, Optional<String> turnId, ContextUsageEvent.TaskKind taskKind,
            long entryVersion, ContextUsageEvent.Stage stage, ContextUsageEvent.Delivery delivery, Instant occurredAt, String receiptRef) {}

    private static final class Aggregation
    {
        private final Set<String> development = new HashSet<>();
        private final Set<String> reviews = new HashSet<>();
        private final Set<String> off = new HashSet<>();
        private final Set<String> providedOrOpened = new HashSet<>();
        private final Set<String> noScopeMatches = new HashSet<>();
        private final Set<String> noNoteMatches = new HashSet<>();
        private final Map<String, EntryUsage> entries = new HashMap<>();
        private final Map<String, GapUsage> gaps = new HashMap<>();
        private final Map<KindKey, KindUsage> kinds = new HashMap<>();

        private void accept(ContextUsageEvent event)
        {
            String work = workKey(event);
            if (event.stage() == ContextUsageEvent.Stage.ELIGIBLE && event.delivery() == ContextUsageEvent.Delivery.CONFIRMED) {
                (event.taskKind() == ContextUsageEvent.TaskKind.DEVELOPMENT ? development : reviews).add(work);
                if (!event.contextEnabled().orElseThrow()) {
                    off.add(work);
                }
            }
            event.note().ifPresent(note -> entries.computeIfAbsent(note.id(), id -> new EntryUsage(note)));
            if (event.delivery() != ContextUsageEvent.Delivery.CONFIRMED) {
                return;
            }
            if (event.stage() == ContextUsageEvent.Stage.NO_MATCH) {
                (event.scope().isPresent() ? noNoteMatches : noScopeMatches).add(event.receiptRef());
            }
            if (event.stage() == ContextUsageEvent.Stage.SCOPE_DEMAND) {
                ContextUsageEvent.Scope scope = event.scope().orElseThrow();
                gaps.computeIfAbsent(scope.id(), GapUsage::new).accept(scope.preparation(), work);
            }
            event.note().ifPresent(note -> {
                EntryUsage entry = entries.computeIfAbsent(note.id(), id -> new EntryUsage(note));
                entry.accept(note, event, work);
                KindUsage byKind = kinds.computeIfAbsent(new KindKey(event.taskKind(), note.kind()), ignored -> new KindUsage());
                if (event.stage() == ContextUsageEvent.Stage.PROVIDED || event.stage() == ContextUsageEvent.Stage.OPENED) {
                    providedOrOpened.add(work);
                    byKind.providedOrOpened.add(work);
                }
                if (event.stage() == ContextUsageEvent.Stage.FOUND) {
                    byKind.found.add(work);
                }
                if (event.stage() == ContextUsageEvent.Stage.OPENED) {
                    byKind.opened.add(work);
                }
            });
        }
    }

    private record KindKey(ContextUsageEvent.TaskKind taskKind, ContextEntryKind noteKind) {}

    private static final class KindUsage
    {
        private final Set<String> providedOrOpened = new HashSet<>();
        private final Set<String> found = new HashSet<>();
        private final Set<String> opened = new HashSet<>();
    }

    private static final class EntryUsage
    {
        private final String id;
        private ContextEntryKind kind;
        private String scopeId;
        private String title;
        private Instant titleAt = Instant.MIN;
        private final Map<Long, StageUsage> versions = new HashMap<>();
        private final StageUsage all = new StageUsage();
        private Optional<Instant> lastUse = Optional.empty();

        private EntryUsage(ContextEntry entry)
        {
            this(new ContextUsageEvent.Note(entry.id(), entry.version(), entry.kind(), entry.scopeId(), entry.title()));
        }

        private EntryUsage(ContextUsageEvent.Note note)
        {
            id = note.id();
            kind = note.kind();
            scopeId = note.scopeId();
            title = note.title();
        }

        private void accept(ContextUsageEvent.Note note, ContextUsageEvent event, String work)
        {
            if (event.occurredAt().isAfter(titleAt)) {
                kind = note.kind();
                scopeId = note.scopeId();
                title = note.title();
                titleAt = event.occurredAt();
            }
            versions.computeIfAbsent(note.version(), ignored -> new StageUsage()).accept(event.stage(), work);
            all.accept(event.stage(), work);
            if (event.stage() == ContextUsageEvent.Stage.PROVIDED || event.stage() == ContextUsageEvent.Stage.OPENED) {
                if (lastUse.isEmpty() || event.occurredAt().isAfter(lastUse.orElseThrow())) {
                    lastUse = Optional.of(event.occurredAt());
                }
            }
        }

        private boolean currentExposure(long version)
        {
            StageUsage usage = versions.get(version);
            return usage != null && (!usage.provided.isEmpty() || !usage.opened.isEmpty());
        }

        private EntryRow row(RepositoryNotebook notebook)
        {
            Optional<ContextEntry> current = notebook.entries().stream().filter(entry -> entry.id().equals(id)).findFirst();
            current.ifPresent(entry -> {
                kind = entry.kind();
                scopeId = entry.scopeId();
                title = entry.title();
            });
            StageUsage version = current.map(entry -> versions.getOrDefault(entry.version(), new StageUsage())).orElseGet(StageUsage::new);
            return new EntryRow(id, kind, scopeId, title, current.map(ContextEntry::version), current.map(ContextEntry::enabled), version.counts(),
                    new AllVersionCounts(all.provided.size(), all.found.size(), all.opened.size()), lastUse);
        }
    }

    private static final class StageUsage
    {
        private final Set<String> selected = new HashSet<>();
        private final Set<String> provided = new HashSet<>();
        private final Set<String> found = new HashSet<>();
        private final Set<String> opened = new HashSet<>();
        private final Set<String> cited = new HashSet<>();
        private int searchHits;

        private void accept(ContextUsageEvent.Stage stage, String work)
        {
            switch (stage) {
                case SELECTED -> selected.add(work);
                case PROVIDED -> provided.add(work);
                case FOUND -> {
                    found.add(work);
                    searchHits++;
                }
                case OPENED -> opened.add(work);
                case CITED -> cited.add(work);
                case ELIGIBLE, SCOPE_DEMAND, NO_MATCH -> {}
            }
        }

        private Counts counts()
        {
            return new Counts(selected.size(), provided.size(), searchHits, found.size(), opened.size(), cited.size());
        }
    }

    private static final class GapUsage
    {
        private final String scopeId;
        private final Set<String> demanding = new HashSet<>();
        private final Map<ContextUsageEvent.Preparation, Set<String>> observed = new EnumMap<>(ContextUsageEvent.Preparation.class);

        private GapUsage(String scopeId)
        {
            this.scopeId = scopeId;
        }

        private void accept(ContextUsageEvent.Preparation preparation, String work)
        {
            demanding.add(work);
            observed.computeIfAbsent(preparation, ignored -> new HashSet<>()).add(work);
        }

        private ScopeGapRow row(RepositoryNotebook notebook)
        {
            Optional<ContextScope> current = notebook.scopes().stream().filter(scope -> scope.id().equals(scopeId)).findFirst();
            ContextUsageEvent.Preparation preparation = current.map(ContextUsageEvent.Scope::from)
                    .map(ContextUsageEvent.Scope::preparation).orElse(ContextUsageEvent.Preparation.NOT_PREPARED);
            Optional<ContextEntry> knowledge = notebook.entries().stream().filter(entry -> entry.id().equals(ContextEntry.knowledgeId(scopeId))).findFirst();
            Knowledge presence;
            if (knowledge.isEmpty()) {
                presence = Knowledge.MISSING;
            }
            else if (current.filter(ContextScope::enabled).isEmpty() || knowledge.filter(ContextEntry::enabled).isEmpty()) {
                presence = Knowledge.DISABLED;
            }
            else {
                presence = Knowledge.PRESENT;
            }
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (ContextUsageEvent.Preparation value : ContextUsageEvent.Preparation.values()) {
                counts.put(value.name(), observed.getOrDefault(value, ImmutableSet.of()).size());
            }
            return new ScopeGapRow(scopeId, demanding.size(), counts, preparation, presence);
        }
    }
}
