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

import com.bytequay.core.domain.AgendaCalendar;
import com.bytequay.integration.google.GoogleBusyTime;
import com.bytequay.integration.google.GoogleSnapshot;
import com.bytequay.integration.google.GoogleWorkspace;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
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
        GoogleWorkspace google = mock(GoogleWorkspace.class);
        when(google.status()).thenReturn(new GoogleSnapshot.Status(true, "", Optional.empty(), Optional.empty(), ImmutableList.of(), ""));
        when(google.snapshot()).thenReturn(Optional.empty());
        AgendaCalendarReader reader = new AgendaCalendarReader(google);
        assertThat(reader.today(ON, FRIDAY).state()).isEqualTo("not_connected");

        when(google.snapshot()).thenReturn(Optional.of(GoogleSnapshot.empty("people/one", "one@example.com", "One")));
        assertThat(reader.today(new AgendaSchedule(true, 540, ImmutableList.of(DayOfWeek.FRIDAY), Optional.empty()), FRIDAY).state()).isEqualTo("off");

        when(google.busyTimes(eq("people/one"), any(), any())).thenThrow(new IllegalStateException("A Google operation is already running."));
        assertThat(reader.today(ON, FRIDAY).state()).isEqualTo("unavailable");
    }

    @Test
    void interruptingTheAgendaCancelsItsCalendarRead()
            throws Exception
    {
        GoogleWorkspace google = mock(GoogleWorkspace.class);
        when(google.status()).thenReturn(new GoogleSnapshot.Status(true, "", Optional.empty(), Optional.empty(), ImmutableList.of(), ""));
        when(google.snapshot()).thenReturn(Optional.of(GoogleSnapshot.empty("people/one", "one@example.com", "One")));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(google.busyTimes(eq("people/one"), any(), any())).thenAnswer(call -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                return ImmutableList.of();
            }
            catch (InterruptedException failure) {
                interrupted.countDown();
                throw failure;
            }
        });
        AgendaCalendarReader reader = new AgendaCalendarReader(google);
        AtomicReference<AgendaCalendar> result = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread caller = Thread.startVirtualThread(() -> {
            result.set(reader.today(ON, FRIDAY));
            interruptRestored.set(Thread.currentThread().isInterrupted());
        });
        try {
            assertThat(started.await(5, SECONDS)).isTrue();
            caller.interrupt();
            assertThat(caller.join(Duration.ofSeconds(5))).isTrue();
            assertThat(result.get().state()).isEqualTo("unavailable");
            assertThat(interruptRestored).isTrue();
            assertThat(interrupted.await(5, SECONDS)).isTrue();
        }
        finally {
            caller.interrupt();
        }
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
