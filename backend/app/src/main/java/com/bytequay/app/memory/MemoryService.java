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

import com.bytequay.core.memory.Memory;
import com.bytequay.core.memory.MemoryReport;
import com.bytequay.core.memory.MemorySource;
import com.bytequay.core.memory.MemoryStore;
import com.google.common.collect.ImmutableList;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

    private final MemoryStore store;
    private final Clock clock;
    // ponytail: one lock for every memory write; per-workspace locks if writes ever contend.
    private final Object lock = new Object();

    public MemoryService(MemoryStore store, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
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

    /** Flags the memory; it stays as it is, and agents keep receiving it with the report. */
    public Memory report(String workspaceId, String memoryId, String reason, String quote, String taskId)
    {
        synchronized (lock) {
            Memory reported = get(workspaceId, memoryId).reported(new MemoryReport(reason, quote, taskId, clock.instant()), clock.instant());
            store.save(reported);
            return reported;
        }
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
