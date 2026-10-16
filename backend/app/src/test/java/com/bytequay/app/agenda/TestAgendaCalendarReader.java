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
import com.bytequay.app.google.GoogleSnapshot;
import com.bytequay.app.google.GoogleWorkspaceService;
import com.bytequay.core.domain.AgendaCalendar;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestAgendaCalendarReader
{
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate FRIDAY = LocalDate.parse("2026-10-16");
    private static final AgendaSchedule ON = new AgendaSchedule(true, 540, ImmutableList.of(DayOfWeek.FRIDAY), Optional.of("people/one"));

    @Test
    void mergesBusyTimeAndFindsTheGapsOfTheWorkingDay()
    {
        AgendaCalendar calendar = AgendaCalendarReader.summarize(ImmutableList.of(
                busy("2026-10-16T10:00:00+08:00", "2026-10-16T11:00:00+08:00"),
                busy("2026-10-16T10:30:00+08:00", "2026-10-16T12:30:00+08:00"),
                busy("2026-10-16T15:00:00+08:00", "2026-10-16T18:30:00+08:00"),
                // Last night's late call runs into today only until 00:30.
                busy("2026-10-15T23:30:00+08:00", "2026-10-16T00:30:00+08:00"),
                new GoogleBusyTime("2026-10-15", "2026-10-16", true)), FRIDAY, ZONE);

        assertThat(calendar.state()).isEqualTo("used");
        assertThat(calendar.busyHours()).isEqualTo(6.5);
        assertThat(calendar.allDay()).isFalse();
        assertThat(calendar.busy()).containsExactly(block("00:00", "00:30"), block("10:00", "12:30"), block("15:00", "18:30"));
        assertThat(calendar.free()).containsExactly(block("09:00", "10:00"), block("12:30", "15:00"));
    }

    @Test
    void anAllDayEventTodayIsSaidNotCounted()
    {
        AgendaCalendar calendar = AgendaCalendarReader.summarize(ImmutableList.of(new GoogleBusyTime("2026-10-16", "2026-10-17", true)), FRIDAY, ZONE);

        assertThat(calendar.allDay()).isTrue();
        assertThat(calendar.busyHours()).isZero();
        assertThat(calendar.free()).containsExactly(block("09:00", "18:00"));
    }

    @Test
    void saysWhyTheCalendarWasNotRead()
    {
        GoogleWorkspaceService google = mock(GoogleWorkspaceService.class);
        when(google.status()).thenReturn(new GoogleSnapshot.Status(true, "", Optional.empty(), Optional.empty(), ImmutableList.of(), ""));
        when(google.snapshot()).thenReturn(Optional.empty());
        AgendaCalendarReader reader = new AgendaCalendarReader(google, Clock.fixed(Instant.parse("2026-10-16T01:02:00Z"), ZONE));
        assertThat(reader.today(ON, FRIDAY).state()).isEqualTo("not_connected");

        when(google.snapshot()).thenReturn(Optional.of(GoogleSnapshot.empty("people/one", "one@example.com", "One")));
        assertThat(reader.today(new AgendaSchedule(true, 540, ImmutableList.of(DayOfWeek.FRIDAY), Optional.empty()), FRIDAY).state()).isEqualTo("off");

        when(google.busyTimes(eq("people/one"), any(), any())).thenThrow(new IllegalStateException("A Google operation is already running."));
        assertThat(reader.today(ON, FRIDAY).state()).isEqualTo("unavailable");
    }

    private static GoogleBusyTime busy(String start, String end)
    {
        return new GoogleBusyTime(start, end, false);
    }

    private static AgendaCalendar.Block block(String from, String to)
    {
        return new AgendaCalendar.Block(from, to);
    }
}
