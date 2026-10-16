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
import com.google.common.collect.ImmutableMap;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One day's agenda: the latest run's suggestions, and what the user marked on them.
 *
 * @param since the moment the run read "new" from, the previous ready agenda's making
 * @param marks the user's own marks by ref; whether an item is done otherwise follows from stored facts when the agenda is read
 */
public record AgendaDay(
        LocalDate day,
        State state,
        Instant generatedAt,
        Instant since,
        String laneId,
        String model,
        String summary,
        List<AgendaItem> items,
        Optional<AgendaRecap> recap,
        Map<String, Mark> marks,
        Optional<String> failure)
{
    public enum State
    {
        READY, FAILED
    }

    public enum Mark
    {
        DONE, DISMISSED
    }

    public AgendaDay
    {
        requireNonNull(day, "day is null");
        requireNonNull(state, "state is null");
        requireNonNull(generatedAt, "generatedAt is null");
        requireNonNull(since, "since is null");
        requireNonNull(laneId, "laneId is null");
        requireNonNull(model, "model is null");
        requireNonNull(summary, "summary is null");
        items = ImmutableList.copyOf(items);
        requireNonNull(recap, "recap is null");
        marks = ImmutableMap.copyOf(marks);
        requireNonNull(failure, "failure is null");
        checkArgument(state == State.READY || failure.isPresent(), "A failed agenda says why");
    }

    public AgendaDay withMarks(Map<String, Mark> next)
    {
        return new AgendaDay(day, state, generatedAt, since, laneId, model, summary, items, recap, next, failure);
    }
}
