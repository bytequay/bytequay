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

import com.google.common.collect.ImmutableList;

import java.time.DayOfWeek;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * When the daily agenda is made: after {@code minute} past midnight on each of {@code days}, the last of which also carries the
 * week's recap.
 *
 * @param calendarAccountId the Google account whose busy times the agenda reads; empty keeps the calendar out. It is saved apart
 *         from task agent access, which resets on every restart, because the agenda runs while nobody is there to approve.
 */
public record AgendaSchedule(boolean enabled, int minute, List<DayOfWeek> days, Optional<String> calendarAccountId)
{
    // Off until the user turns it on: each run spends their model account without a click.
    public static final AgendaSchedule DEFAULT = new AgendaSchedule(false, 9 * 60,
            ImmutableList.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), Optional.empty());

    public AgendaSchedule
    {
        checkArgument(minute >= 0 && minute < 24 * 60, "The agenda time must be within the day");
        days = days == null ? ImmutableList.of() : days.stream().distinct().sorted().collect(toImmutableList());
        requireNonNull(calendarAccountId, "calendarAccountId is null");
        calendarAccountId.ifPresent(account -> checkArgument(!account.isBlank(), "Choose a Google account for the calendar"));
    }

    public boolean workingDay(DayOfWeek day)
    {
        return days.contains(day);
    }

    /** The last working day of the week, which looks back over it. */
    public Optional<DayOfWeek> recapDay()
    {
        return days.stream().max(Comparator.naturalOrder());
    }
}
