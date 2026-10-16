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

import com.bytequay.core.domain.AgendaRecap;
import com.bytequay.core.domain.AgendaTarget;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Today's agenda as a Home page draws it.
 *
 * @param state NONE before today's agenda exists, RUNNING while one is being made, READY or FAILED
 * @param at when the schedule makes it, as minutes past midnight, while today's has not been made
 * @param failure why the last attempt failed: the whole state when FAILED, or a ready agenda's failed Make again
 */
public record AgendaView(
        String day,
        String state,
        boolean enabled,
        int at,
        Optional<Instant> generatedAt,
        Optional<String> model,
        Optional<String> summary,
        List<Item> items,
        Optional<AgendaRecap> recap,
        Optional<String> failure)
{
    public AgendaView
    {
        requireNonNull(day, "day is null");
        requireNonNull(state, "state is null");
        requireNonNull(generatedAt, "generatedAt is null");
        requireNonNull(model, "model is null");
        requireNonNull(summary, "summary is null");
        items = ImmutableList.copyOf(items);
        requireNonNull(recap, "recap is null");
        requireNonNull(failure, "failure is null");
    }

    /**
     * @param status open, done or dismissed
     * @param byYou whether the user marked it, rather than a stored fact closing it
     * @param doneText what closed it, such as "Merged"
     */
    public record Item(
            String ref,
            String action,
            String title,
            String label,
            String why,
            Optional<Instant> at,
            Optional<String> workspaceId,
            AgendaTarget target,
            String status,
            boolean byYou,
            Optional<String> doneText,
            Optional<Instant> doneAt) {}
}
