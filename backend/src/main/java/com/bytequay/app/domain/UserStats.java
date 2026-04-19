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
package com.bytequay.app.domain;

import java.time.Instant;

public record UserStats(
        StatPeriods commits,
        StatPeriods pushes,
        StatPeriods prsCreated,
        StatPeriods prsReviewed,
        StatPeriods comments,
        StatPeriods prsViewed,
        StatPeriods prsMarkedReviewed,
        Instant updatedAt)
{
    /**
     * Counts of an activity type bucketed by recency.
     * <ul>
     *   <li>{@code yesterday} — [todayStart - 1d, todayStart). Powers the
     *       day-over-day delta on the home page.</li>
     *   <li>{@code previousWeek} — [weekStart - 7d, weekStart). Powers the
     *       week-over-week trend (kept for any later UI that wants it).</li>
     * </ul>
     */
    public record StatPeriods(int today, int yesterday, int thisWeek, int thisMonth, int previousWeek) {}

    public static UserStats empty()
    {
        StatPeriods zero = new StatPeriods(0, 0, 0, 0, 0);
        return new UserStats(zero, zero, zero, zero, zero, zero, zero, Instant.EPOCH);
    }
}
