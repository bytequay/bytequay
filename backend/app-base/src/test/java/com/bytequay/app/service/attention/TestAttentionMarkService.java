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
package com.bytequay.app.service.attention;

import com.bytequay.core.domain.AttentionMark;
import com.bytequay.core.store.AttentionMarkStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAttentionMarkService
{
    // A Monday, so "next monday" is a week away rather than today.
    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");

    private final AttentionMarkStore store = mock(AttentionMarkStore.class);
    private final AttentionMarkService marks = new AttentionMarkService(store, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acknowledgesUntilTheItemNextChanges()
    {
        AttentionMark mark = marks.acknowledge("owner/repository#31402");

        assertThat(mark).isEqualTo(new AttentionMark("owner/repository#31402", AttentionMark.Kind.ACK, Optional.empty(), NOW.toString(), Optional.empty(),
                "acknowledged", Optional.empty()));
        verify(store).change(ImmutableList.of(mark), ImmutableList.of());
    }

    @Test
    void snoozesToTheMenusTimesOnTheUsersClock()
    {
        assertThat(marks.snooze("task:7d0e", AttentionMarkService.Snooze.HOUR).until()).contains(Instant.parse("2026-10-19T04:00:00Z"));

        ZonedDateTime tomorrow = marks.snooze("task:7d0e", AttentionMarkService.Snooze.TOMORROW).until().orElseThrow().atZone(ZoneId.systemDefault());
        assertThat(tomorrow.toLocalDate()).isEqualTo(NOW.atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1));
        assertThat(tomorrow.toLocalTime()).isEqualTo(LocalTime.of(9, 0));

        AttentionMark monday = marks.snooze("task:7d0e", AttentionMarkService.Snooze.MONDAY);
        ZonedDateTime until = monday.until().orElseThrow().atZone(ZoneId.systemDefault());
        assertThat(until.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(until.toLocalTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(until.toInstant()).isAfter(NOW.plusSeconds(6 * 24 * 3600));
        assertThat(monday.label()).isEqualTo("next monday");
        assertThat(monday.kind()).isEqualTo(AttentionMark.Kind.SNOOZE);
    }

    @Test
    void namesOnlySnoozesThatHaveNotEnded()
    {
        when(store.all()).thenReturn(ImmutableList.of(
                new AttentionMark("Owner/repository#1", AttentionMark.Kind.SNOOZE, Optional.of(NOW.plusSeconds(60)), NOW.toString(), Optional.empty(), "1 hour", Optional.empty()),
                new AttentionMark("owner/repository#2", AttentionMark.Kind.SNOOZE, Optional.of(NOW), NOW.toString(), Optional.empty(), "1 hour", Optional.empty()),
                new AttentionMark("owner/repository#3", AttentionMark.Kind.ACK, Optional.empty(), NOW.toString(), Optional.empty(), "acknowledged", Optional.empty())));

        assertThat(marks.snoozedKeys()).containsExactly("owner/repository#1");
    }
}
