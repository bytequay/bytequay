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
package com.bytequay.app.repository;

import com.bytequay.app.domain.RecentEvent;
import com.bytequay.app.domain.UserOrg;
import com.bytequay.app.domain.UserProfile;
import com.bytequay.app.domain.UserStats;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Local persistence for the home page's GitHub-sourced data — profile, the
 * recent / following activity feeds, computed user stats, and the org list.
 *
 * <p>The {@code /api/profile}, {@code /api/activity/{recent,following}},
 * {@code /api/stats}, and {@code /api/user/orgs} endpoints read from this
 * store only and never call GitHub on the read path. A background scheduler
 * ({@code GithubHomeCacheRefreshJob}) overwrites rows after each successful
 * GitHub fetch using its own per-feed TTL (2 min for profile/events, 5 min
 * for stats, 30 days for orgs), so reads always return whatever was persisted
 * by the last refresh — {@link Optional#empty()} until the first one lands.
 */
public interface GithubHomeCacheStore
{
    /** Which user-events endpoint a row in the events cache came from. */
    enum EventFeed
    {
        /** {@code GET /users/{login}/events} — the user's own activity. */
        RECENT,
        /** {@code GET /users/{login}/received_events} — feed of users they follow. */
        FOLLOWING
    }

    /** Stored value paired with the timestamp of the GitHub fetch that produced it. */
    record TimedValue<T>(T value, Instant fetchedAt) {}

    Optional<TimedValue<UserProfile>> findProfile(String login);

    void putProfile(String login, UserProfile profile, Instant fetchedAt);

    Optional<TimedValue<List<RecentEvent>>> findEvents(String login, EventFeed feed);

    void putEvents(String login, EventFeed feed, List<RecentEvent> events, Instant fetchedAt);

    Optional<TimedValue<UserStats>> findStats(String login);

    void putStats(String login, UserStats stats, Instant fetchedAt);

    Optional<TimedValue<List<UserOrg>>> findOrgs(String login);

    void putOrgs(String login, List<UserOrg> orgs, Instant fetchedAt);
}
