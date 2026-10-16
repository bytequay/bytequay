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
package com.bytequay.core.domain;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * How much of today the user's calendar holds, as times only: no titles, attendees or descriptions are kept.
 *
 * @param state {@code used} when today was read; else {@code off}, {@code not_connected} or {@code unavailable}, and nothing else is known
 * @param allDay whether an all-day event, such as time off, is on today
 * @param busy the busy stretches of today, local {@code HH:mm} pairs
 * @param free the gaps of half an hour or more in the working hours
 */
public record AgendaCalendar(String state, double busyHours, boolean allDay, List<Block> busy, List<Block> free)
{
    private static final Set<String> STATES = ImmutableSet.of("used", "off", "not_connected", "unavailable");

    public AgendaCalendar
    {
        checkArgument(STATES.contains(state), "Unknown calendar state: %s", state);
        checkArgument(busyHours >= 0, "busyHours is negative");
        busy = ImmutableList.copyOf(busy);
        free = ImmutableList.copyOf(free);
    }

    public static AgendaCalendar of(String state)
    {
        return new AgendaCalendar(state, 0, false, ImmutableList.of(), ImmutableList.of());
    }

    public boolean used()
    {
        return state.equals("used");
    }

    public record Block(String from, String to)
    {
        public Block
        {
            requireNonNull(from, "from is null");
            requireNonNull(to, "to is null");
        }
    }
}
