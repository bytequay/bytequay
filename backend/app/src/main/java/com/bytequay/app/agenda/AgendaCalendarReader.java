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

import com.bytequay.app.google.GoogleBusyTime;
import com.bytequay.app.google.GoogleWorkspaceService;
import com.bytequay.core.domain.AgendaCalendar;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.Objects.requireNonNull;

/**
 * Today's busy times for the agenda, when the user turned the calendar on: one read of the primary calendar, separate from the
 * Calendar view's snapshot, reduced to times before anything reaches the agent or the database.
 */
@Component
class AgendaCalendarReader
{
    private static final Logger log = LoggerFactory.getLogger(AgendaCalendarReader.class);
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final int WORK_START = 9 * 60;
    private static final int WORK_END = 18 * 60;
    private static final int SHORTEST_FREE = 30;

    private final GoogleWorkspaceService google;

    AgendaCalendarReader(GoogleWorkspaceService google)
    {
        this.google = requireNonNull(google, "google is null");
    }

    AgendaCalendar today(AgendaSchedule schedule, LocalDate day)
    {
        if (!google.status().installed() || google.snapshot().isEmpty()) {
            return AgendaCalendar.of("not_connected");
        }
        if (schedule.calendarAccountId().isEmpty()) {
            return AgendaCalendar.of("off");
        }
        ZoneId zone = ZoneId.systemDefault();
        Instant from = day.atStartOfDay(zone).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();
        String account = schedule.calendarAccountId().orElseThrow();
        // ponytail: a read that outlives the wait keeps running until the connector returns; the agenda goes on without it
        CompletableFuture<List<GoogleBusyTime>> read = CompletableFuture.supplyAsync(() -> google.busyTimes(account, from, to), Thread::startVirtualThread);
        try {
            return summarize(read.get(WAIT.toMillis(), TimeUnit.MILLISECONDS), day, zone);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return AgendaCalendar.of("unavailable");
        }
        catch (ExecutionException | TimeoutException | RuntimeException failure) {
            log.warn("The agenda could not read today's calendar: {}", failure.getMessage());
            return AgendaCalendar.of("unavailable");
        }
    }

    /** Busy stretches of the day merged, the hours they hold to the nearest half hour, and the gaps in the working hours. */
    static AgendaCalendar summarize(List<GoogleBusyTime> events, LocalDate day, ZoneId zone)
    {
        boolean allDay = events.stream().filter(GoogleBusyTime::allDay)
                .anyMatch(event -> !LocalDate.parse(event.start()).isAfter(day) && LocalDate.parse(event.end()).isAfter(day));
        List<int[]> stretches = events.stream().filter(event -> !event.allDay())
                .map(event -> new int[] {minute(event.start(), day, zone), minute(event.end(), day, zone)})
                .filter(stretch -> stretch[1] > stretch[0])
                .sorted(Comparator.comparingInt(stretch -> stretch[0]))
                .toList();
        List<int[]> merged = new ArrayList<>();
        for (int[] stretch : stretches) {
            int[] last = merged.isEmpty() ? null : merged.getLast();
            if (last != null && stretch[0] <= last[1]) {
                last[1] = Math.max(last[1], stretch[1]);
            }
            else {
                merged.add(stretch.clone());
            }
        }
        int busyMinutes = merged.stream().mapToInt(stretch -> stretch[1] - stretch[0]).sum();
        ImmutableList.Builder<AgendaCalendar.Block> free = ImmutableList.builder();
        int cursor = WORK_START;
        for (int[] stretch : merged) {
            if (stretch[0] - cursor >= SHORTEST_FREE && stretch[0] <= WORK_END) {
                free.add(block(cursor, Math.min(stretch[0], WORK_END)));
            }
            cursor = Math.max(cursor, stretch[1]);
        }
        if (WORK_END - cursor >= SHORTEST_FREE) {
            free.add(block(cursor, WORK_END));
        }
        return new AgendaCalendar("used", Math.round(busyMinutes / 30.0) / 2.0, allDay,
                merged.stream().map(stretch -> block(stretch[0], stretch[1])).collect(ImmutableList.toImmutableList()), free.build());
    }

    /** Minutes past local midnight, clipped to the day. */
    private static int minute(String time, LocalDate day, ZoneId zone)
    {
        LocalDateTime local = OffsetDateTime.parse(time).atZoneSameInstant(zone).toLocalDateTime();
        if (local.toLocalDate().isBefore(day)) {
            return 0;
        }
        if (local.toLocalDate().isAfter(day)) {
            return 24 * 60;
        }
        return local.getHour() * 60 + local.getMinute();
    }

    private static AgendaCalendar.Block block(int from, int to)
    {
        return new AgendaCalendar.Block(clock(from), clock(to));
    }

    private static String clock(int minute)
    {
        return minute >= 24 * 60 ? "24:00" : "%02d:%02d".formatted(minute / 60, minute % 60);
    }
}
