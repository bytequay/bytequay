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
package com.bytequay.app.agenda;

import com.bytequay.app.agenda.AgendaSnapshot.RefInfo;
import com.bytequay.core.domain.AgendaTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** One Friday's snapshot, shaped like the design's sample day. */
final class AgendaFixtures
{
    static final Instant SINCE = Instant.parse("2026-10-15T01:02:00Z");
    static final Instant NOW = Instant.parse("2026-10-16T01:02:00Z");

    private AgendaFixtures() {}

    static ObjectMapper mapper()
    {
        // As Spring Boot configures the backend's mapper: ISO dates, Optional unwrapped.
        return new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    static AgendaSnapshot friday(int changes)
    {
        Map<String, RefInfo> refs = new LinkedHashMap<>();
        ImmutableList.Builder<AgendaSnapshot.Change> changeEntries = ImmutableList.builder();
        for (int index = 0; index < changes; index++) {
            String ref = "pr:trinodb/trino#" + (31000 + index);
            refs.put(ref, pull(31000 + index));
            changeEntries.add(new AgendaSnapshot.Change(ref, "Change " + index, ImmutableList.of("1 new reply"), Optional.empty(), NOW.minusSeconds(index)));
        }
        refs.put("pr:trinodb/trino#31402", pull(31402));
        refs.put("pr:trinodb/trino#31377", pull(31377));
        refs.put("pr:trinodb/trino-gateway#612", new RefInfo(Optional.of(NOW), Optional.empty(), AgendaTarget.pull("trinodb/trino-gateway", 612, Optional.empty())));
        refs.put("task:t1", new RefInfo(Optional.of(NOW.minusSeconds(3600)), Optional.of("ws1"), AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", "t1")));
        refs.put("work:w19", new RefInfo(Optional.of(NOW), Optional.of("ws1"), AgendaTarget.of(AgendaTarget.Kind.WORK, "ws1", "w19")));
        refs.put("work:w22", new RefInfo(Optional.of(NOW.minusSeconds(32400)), Optional.of("ws1"), AgendaTarget.of(AgendaTarget.Kind.WORK, "ws1", "w22"),
                Optional.of("Iceberg write path · 3/6")));
        refs.put("epic:E1", new RefInfo(Optional.of(NOW), Optional.of("ws1"), AgendaTarget.issue("trinodb/trino", 30001, "ws1")));
        refs.put("pr:trinodb/trino#31390", pull(31390));
        refs.put("pr:unserved/repo#1", pull(1));
        List<AgendaSnapshot.Mine> mine = ImmutableList.of(new AgendaSnapshot.Mine("pr:trinodb/trino-gateway#612", "Route by client tags", false,
                new AgendaSnapshot.Review(Optional.of("approved"), ImmutableList.of("finn-ep"), ImmutableList.of()), Optional.of("passing"), ImmutableList.of(),
                Optional.of(true), Optional.of(NOW.minusSeconds(172800)), Optional.empty()));
        List<AgendaSnapshot.Task> tasks = ImmutableList.of(new AgendaSnapshot.Task("task:t1", "development", "Retry Iceberg commit on conflict", "waiting_on_you",
                Optional.of("question"), Optional.of("Exponential backoff or a fixed delay?"), Optional.empty(), Optional.of(NOW.minusSeconds(3600)),
                Optional.of("pr:trinodb/trino#31402"), Optional.of("work:w19"), Optional.of(new AgendaSnapshot.Findings(3, 2, Optional.of("high")))));
        List<AgendaSnapshot.Ask> asks = ImmutableList.of(new AgendaSnapshot.Ask("pr:trinodb/trino#31377", "review_requested", "Add Iceberg metadata cache",
                Optional.of("marlo-t"), true, Optional.of(NOW.minusSeconds(259200)), Optional.of(134), Optional.of("passing"), Optional.empty(), Optional.empty()));
        List<AgendaSnapshot.EpicEntry> epics = ImmutableList.of(new AgendaSnapshot.EpicEntry("epic:E1", "Iceberg write path", 3, 6, 1, 2, 0,
                ImmutableList.of(), ImmutableList.of(new AgendaSnapshot.Ready("work:w22", "Expose snapshot id in metadata", "NORMAL", 2))));
        List<AgendaSnapshot.HistoryDay> history = ImmutableList.of(new AgendaSnapshot.HistoryDay("2026-10-15",
                ImmutableList.of(new AgendaSnapshot.HistoryItem("pr:trinodb/trino#31377", "review", "open"))));
        Optional<AgendaSnapshot.Week> week = Optional.of(new AgendaSnapshot.Week(ImmutableList.of("pr:trinodb/trino#31390"), ImmutableList.of(),
                ImmutableList.of("pr:trinodb/trino#31377")));
        AgendaSnapshot.Overview overview = new AgendaSnapshot.Overview("2026-10-16", "FRIDAY", true, SINCE,
                new AgendaSnapshot.Counts(changes, mine.size(), tasks.size(), asks.size(), epics.size()), ImmutableList.of());
        return new AgendaSnapshot(overview, changeEntries.build(), mine, tasks, asks, epics, history, week, refs);
    }

    private static RefInfo pull(int number)
    {
        return new RefInfo(Optional.of(NOW), Optional.of("ws1"), AgendaTarget.pull("trinodb/trino", number, Optional.of("ws1")));
    }
}
