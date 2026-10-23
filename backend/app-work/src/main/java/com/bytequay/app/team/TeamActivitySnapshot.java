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
package com.bytequay.app.team;

import com.bytequay.core.domain.TeamActivityEvent;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** The page of matching events, and counts over every matching event retained in the period. */
public record TeamActivitySnapshot(String accountLogin, List<TeamActivityEvent> events, int updates, int workItems, List<PersonStatistics> people,
        Statistics totals, List<Day> days, Coverage coverage)
{
    public TeamActivitySnapshot
    {
        events = ImmutableList.copyOf(events);
        people = ImmutableList.copyOf(people);
        days = ImmutableList.copyOf(days);
    }

    public record Statistics(long prsOpened, long authoredPrsMerged, long mergesPerformed, long prsReviewed, long issuesOpened, long issuesClosed,
            long comments, long directInteractions, long sharedPullRequests, long sharedIssues) {}

    public record PersonStatistics(long memberId, String login, Statistics statistics, Optional<Instant> lastActiveAt) {}

    /** Matching updates on one local day; outside the collected window a zero means unknown, not quiet. */
    public record Day(LocalDate date, long updates, boolean collected) {}

    public record Coverage(boolean partial, Optional<Instant> observedSince, Optional<Instant> lastRefreshedAt, List<String> errors,
            int memberCount, int membersScanned, int repositoryCount, int repositoriesScanned)
    {
        public Coverage
        {
            errors = ImmutableList.copyOf(errors);
        }
    }
}
