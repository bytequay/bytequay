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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * What one agenda run read and kept, failed runs too, so the reading tool's pages can be judged against real mornings.
 *
 * @param calls the tool calls in order
 * @param picked each kept item and the first kind of page that handed out its ref
 * @param dropped the items the app refused, and why
 * @param calendar the calendar's state for the run: used, off, not_connected or unavailable
 */
public record AgendaRun(
        String id,
        LocalDate day,
        Instant startedAt,
        long elapsedMillis,
        AgendaDay.State state,
        Trigger trigger,
        String laneId,
        String model,
        List<Call> calls,
        List<Pick> picked,
        List<Drop> dropped,
        String calendar,
        Optional<String> failure)
{
    public enum Trigger
    {
        SCHEDULED, MAKE_AGAIN
    }

    public AgendaRun
    {
        checkText(id, "id");
        requireNonNull(day, "day is null");
        requireNonNull(startedAt, "startedAt is null");
        requireNonNull(state, "state is null");
        requireNonNull(trigger, "trigger is null");
        requireNonNull(laneId, "laneId is null");
        requireNonNull(model, "model is null");
        calls = ImmutableList.copyOf(calls);
        picked = ImmutableList.copyOf(picked);
        dropped = ImmutableList.copyOf(dropped);
        requireNonNull(calendar, "calendar is null");
        requireNonNull(failure, "failure is null");
    }

    /** A tool call, sized by the length of the page it returned. */
    public record Call(String kind, int page, int entries, int chars, long millis, Optional<String> error)
    {
        public Call
        {
            requireNonNull(kind, "kind is null");
            requireNonNull(error, "error is null");
        }
    }

    public record Pick(String ref, String action, String kind)
    {
        public Pick
        {
            requireNonNull(ref, "ref is null");
            requireNonNull(action, "action is null");
            requireNonNull(kind, "kind is null");
        }
    }

    public record Drop(String ref, String reason)
    {
        public Drop
        {
            requireNonNull(ref, "ref is null");
            requireNonNull(reason, "reason is null");
        }
    }
}
