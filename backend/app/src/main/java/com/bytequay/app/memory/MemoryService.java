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
package com.bytequay.app.memory;

import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.memory.Memory;
import com.bytequay.core.memory.MemoryReport;
import com.bytequay.core.memory.MemorySource;
import com.bytequay.core.memory.MemoryStore;
import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** A workspace's repository memory. Nothing here reaches GitHub or starts work. */
@Service
public class MemoryService
{
    static final int PAGE_SIZE = 20;
    static final int MAX_WORKSPACE_MEMORIES = 300;
    static final String LAST_NUMBER = "memory.lastNumber";
    static final String SWITCHES = "workspace.memory.";
    private static final Duration UNDO_WINDOW = Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final MemoryStore store;
    private final AppSettingsStore settings;
    private final ObjectMapper mapper;
    private final Clock clock;
    // ponytail: one lock for every memory write; per-workspace locks if writes ever contend.
    private final Object lock = new Object();
    // Deleted memories, kept for the Undo toast; a restart forgets them.
    private final Map<String, Memory> deleted = new HashMap<>();

    public MemoryService(MemoryStore store, AppSettingsStore settings, ObjectMapper mapper, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public List<Memory> list(String workspaceId)
    {
        return store.list(workspaceId);
    }

    public Memory get(String workspaceId, String memoryId)
    {
        return store.find(memoryId).filter(memory -> memory.workspaceId().equals(workspaceId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory " + memoryId + " is not in this workspace"));
    }

    /**
     * Literal matches: the text contains the query, ignoring case, and the scope covers the path. A path also finds the
     * repository-wide memories, after the ones scoped to it, longest scope first.
     */
    public Page search(String workspaceId, Optional<String> query, Optional<String> path, int page)
    {
        checkArgument(page >= 1, "page must be positive");
        Optional<String> needle = query.map(String::strip).filter(text -> !text.isEmpty()).map(text -> text.toLowerCase(Locale.ROOT));
        Optional<String> target = path.map(String::strip).filter(text -> !text.isEmpty());
        List<Memory> matches = store.list(workspaceId).stream()
                .filter(memory -> needle.isEmpty() || memory.text().toLowerCase(Locale.ROOT).contains(needle.orElseThrow()))
                .filter(memory -> target.isEmpty() || memory.repositoryWide() || memory.match(target.orElseThrow()).isPresent())
                .sorted(Comparator.comparingInt((Memory memory) -> target.map(memory::match).orElse(OptionalInt.empty()).orElse(0)).reversed())
                .collect(toImmutableList());
        int pages = Math.max(1, (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        List<Memory> slice = matches.stream().skip((long) (page - 1) * PAGE_SIZE).limit(PAGE_SIZE).collect(toImmutableList());
        return new Page(slice, page, pages, matches.size());
    }

    /** Saves a new memory under the next number; numbers are never reused, so a cited {@code [m12]} always means one memory. */
    public Memory create(String workspaceId, Memory.Kind kind, String text, List<String> scope, List<MemorySource> sources, Memory.Origin origin)
    {
        synchronized (lock) {
            if (store.list(workspaceId).size() >= MAX_WORKSPACE_MEMORIES) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A workspace keeps at most " + MAX_WORKSPACE_MEMORIES + " memories; delete some first");
            }
            long number = settings.get(LAST_NUMBER).map(Long::parseLong).orElse(0L) + 1;
            Instant now = clock.instant();
            Memory memory = new Memory("m" + number, workspaceId, kind, text, scope, sources, origin, 1, Optional.empty(), 0, 0, ImmutableList.of(), now, now);
            settings.put(LAST_NUMBER, Long.toString(number));
            store.save(memory);
            return memory;
        }
    }

    /** Flags the memory; it stays as it is, and agents keep receiving it with the report. */
    public Memory report(String workspaceId, String memoryId, String reason, String quote, String taskId)
    {
        synchronized (lock) {
            Memory reported = get(workspaceId, memoryId).reported(new MemoryReport(reason, quote, taskId, clock.instant()), clock.instant());
            store.save(reported);
            return reported;
        }
    }

    /** A harvest saw the rule again; a memory the user owns stays as it is. */
    public Memory addSources(String workspaceId, String memoryId, List<MemorySource> sources)
    {
        synchronized (lock) {
            Memory sourced = agentOwned(workspaceId, memoryId).withSources(sources, clock.instant());
            store.save(sourced);
            return sourced;
        }
    }

    /** A harvest found a newer pull request that overrides the rule; the replaced text and scope stay as the previous version. */
    public Memory replace(String workspaceId, String memoryId, String text, List<String> scope, List<MemorySource> sources)
    {
        synchronized (lock) {
            Memory replaced = agentOwned(workspaceId, memoryId).replaced(text, scope, sources, clock.instant());
            store.save(replaced);
            return replaced;
        }
    }

    private Memory agentOwned(String workspaceId, String memoryId)
    {
        Memory memory = get(workspaceId, memoryId);
        if (memory.origin().user()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Memory " + memoryId + " is the user's; agents never change it");
        }
        return memory;
    }

    /** The user's words replace the text; line breaks become spaces, since a memory is one paragraph. */
    public Memory edit(String workspaceId, String memoryId, long expectedVersion, String text)
    {
        synchronized (lock) {
            Memory edited = current(workspaceId, memoryId, expectedVersion).edited(text.strip().replaceAll("\\s*\\R\\s*", " "), clock.instant());
            store.save(edited);
            return edited;
        }
    }

    public Memory restore(String workspaceId, String memoryId, long expectedVersion)
    {
        synchronized (lock) {
            Memory memory = current(workspaceId, memoryId, expectedVersion);
            checkArgument(memory.previous().isPresent(), "Memory %s has no previous version", memoryId);
            Memory restored = memory.restored(clock.instant());
            store.save(restored);
            return restored;
        }
    }

    /** The user read the reports and keeps the memory as it is. */
    public Memory keep(String workspaceId, String memoryId)
    {
        synchronized (lock) {
            Memory kept = get(workspaceId, memoryId).kept(clock.instant());
            store.save(kept);
            return kept;
        }
    }

    public void delete(String workspaceId, String memoryId)
    {
        synchronized (lock) {
            Memory memory = get(workspaceId, memoryId);
            store.delete(memoryId);
            Instant now = clock.instant();
            deleted.values().removeIf(earlier -> earlier.updatedAt().plus(UNDO_WINDOW).isBefore(now));
            deleted.put(memoryId, new Memory(memory.id(), memory.workspaceId(), memory.kind(), memory.text(), memory.scope(), memory.sources(), memory.origin(),
                    memory.version(), memory.previous(), memory.delivered(), memory.cited(), memory.reports(), memory.createdAt(), now));
        }
    }

    public Memory undoDelete(String workspaceId, String memoryId)
    {
        synchronized (lock) {
            Memory memory = Optional.ofNullable(deleted.get(memoryId))
                    .filter(found -> found.workspaceId().equals(workspaceId) && !found.updatedAt().plus(UNDO_WINDOW).isBefore(clock.instant()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deleting memory " + memoryId + " can no longer be undone"));
            deleted.remove(memoryId);
            store.save(memory);
            return memory;
        }
    }

    public MemorySwitches switches(String workspaceId)
    {
        return settings.get(SWITCHES + workspaceId).map(json -> {
            try {
                return mapper.readValue(json, MemorySwitches.class);
            }
            catch (JsonProcessingException failure) {
                log.warn("Memory switches of workspace {} could not be read; memory stays on", workspaceId, failure);
                return MemorySwitches.ON;
            }
        }).orElse(MemorySwitches.ON);
    }

    public MemorySwitches saveSwitches(String workspaceId, MemorySwitches switches)
    {
        try {
            settings.put(SWITCHES + workspaceId, mapper.writeValueAsString(switches));
            return switches;
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Memory switches could not be saved", failure);
        }
    }

    /**
     * Each specification a spec check used becomes a SPEC memory scoped to the checked directory, or widens the one it already has.
     * A memory the user owns stays as it is. It never fails the check: a full workspace keeps what it has.
     */
    public void rememberSpecs(String workspaceId, String directory, List<String> urls, String taskId)
    {
        for (String url : new LinkedHashSet<>(urls)) {
            try {
                synchronized (lock) {
                    Optional<Memory> existing = store.list(workspaceId).stream()
                            .filter(memory -> memory.kind() == Memory.Kind.SPEC && memory.sources().stream().anyMatch(source -> source.url().equals(url)))
                            .findFirst();
                    if (existing.isPresent()) {
                        Memory memory = existing.orElseThrow();
                        if (!memory.origin().user() && !memory.repositoryWide() && memory.match(directory).isEmpty() && memory.scope().size() < Memory.MAX_SCOPE) {
                            store.save(memory.rescoped(ImmutableList.<String>builder().addAll(memory.scope()).add(directory).build(), clock.instant()));
                        }
                        continue;
                    }
                }
                String text = "Implements the specification at " + url + ".";
                create(workspaceId, Memory.Kind.SPEC, ContextLimits.bytes(text) <= Memory.MAX_TEXT_BYTES ? text : "Implements the specification linked in its source.",
                        ImmutableList.of(directory), ImmutableList.of(new MemorySource(MemorySource.Kind.PAGE, url, "", clock.instant(), Optional.empty(), Optional.empty())),
                        new Memory.Origin(false, "Spec check of " + directory, Optional.of(taskId)));
            }
            catch (RuntimeException failure) {
                log.warn("The spec memory for {} could not be saved", url, failure);
            }
        }
    }

    private Memory current(String workspaceId, String memoryId, long expectedVersion)
    {
        Memory memory = get(workspaceId, memoryId);
        if (memory.version() != expectedVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Memory " + memoryId + " changed since you opened it; reload it and try again");
        }
        return memory;
    }

    /** "PR #31201 @alice · 3 PRs": the first source and how many pull requests or pages the memory rests on. */
    public static String sourceLabel(Memory memory)
    {
        MemorySource first = memory.sources().getFirst();
        String head = first.pullRequest()
                .map(part -> "PR #" + part.number() + " @" + part.author() + (part.merged() ? "" : ", closed"))
                .orElseGet(() -> "Page " + first.url().replaceFirst("^https://", ""));
        long pullRequests = memory.sources().stream().flatMap(source -> source.pullRequest().stream()).mapToInt(MemorySource.PullRequestPart::number).distinct().count();
        return pullRequests > 1 ? head + " · " + pullRequests + " PRs" : head;
    }

    /** What an agent learns about a report with the memory. */
    public static Optional<String> flag(Memory memory)
    {
        return memory.reports().stream().reduce((first, second) -> second).map(report -> "reported wrong: " + report.reason());
    }

    public record Page(List<Memory> memories, int page, int pages, int total)
    {
        public Page
        {
            memories = ImmutableList.copyOf(memories);
        }
    }
}
