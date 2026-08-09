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
package com.bytequay.app.scheduler;

import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.sqlite.SqliteGithubHomeCacheStore;
import com.bytequay.app.repository.sqlite.SqliteGithubHomeCacheStore.EventFeed;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.RepoService;
import com.bytequay.app.service.StatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.bytequay.app.service.CredentialService.GITHUB_ACCOUNT_NAME;
import static java.util.Objects.requireNonNull;

/**
 * Background refresh for the home page's GitHub-sourced caches. Runs on a
 * 15-second tick with {@code initialDelay = 0} so the first refresh fires
 * immediately at boot — frontend reads on cold launch return empty until
 * this lands, but then track the GitHub state on the per-feed TTLs:
 *
 * <ul>
 *   <li>profile — 2 min ({@code github_user_profile_cache})</li>
 *   <li>recent + following events — 2 min ({@code github_user_event_cache})</li>
 *   <li>stats — 5 min ({@code github_user_stats_cache})</li>
 *   <li>orgs — 30 days ({@code github_user_orgs_cache})</li>
 * </ul>
 *
 * <p>Each refresh is independently try/catch'd so a transient failure on
 * one feed doesn't block the others. The {@link AtomicBoolean} guard keeps
 * a long-running tick from overlapping with the next one.
 */
@Component
public class GithubHomeCacheRefreshJob
{
    private static final Logger log = LoggerFactory.getLogger(GithubHomeCacheRefreshJob.class);

    private static final Duration PROFILE_TTL = Duration.ofMinutes(2);
    private static final Duration EVENTS_TTL = Duration.ofMinutes(2);
    // Mirrors StatsService.CACHE_TTL — keep in sync if either side moves.
    private static final Duration STATS_TTL = Duration.ofMinutes(5);
    // Org membership changes on the order of months, not minutes. The home
    // page profile card just needs the org list to render the avatar strip;
    // stale by a day is fine, stale by 30 days is the threshold where we
    // start risking visible drift after the user joins or leaves an org.
    private static final Duration ORGS_TTL = Duration.ofDays(30);

    private final CredentialService credentialService;
    private final AppSettingsStore settingsStore;
    private final SqliteGithubHomeCacheStore homeCache;
    private final RepoService repoService;
    private final StatsService statsService;
    private final QuietHoursPolicy quietHours;

    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public GithubHomeCacheRefreshJob(
            CredentialService credentialService,
            AppSettingsStore settingsStore,
            SqliteGithubHomeCacheStore homeCache,
            RepoService repoService,
            StatsService statsService,
            QuietHoursPolicy quietHours)
    {
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.settingsStore = requireNonNull(settingsStore, "settingsStore is null");
        this.homeCache = requireNonNull(homeCache, "homeCache is null");
        this.repoService = requireNonNull(repoService, "repoService is null");
        this.statsService = requireNonNull(statsService, "statsService is null");
        this.quietHours = requireNonNull(quietHours, "quietHours is null");
    }

    @Scheduled(initialDelay = 0, fixedDelay = 15_000)
    public void tick()
    {
        // Pause the overnight home-cache refresh to conserve rate limit.
        if (quietHours.isQuietNow()) {
            return;
        }
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            doRefresh();
        }
        finally {
            refreshing.set(false);
        }
    }

    private void doRefresh()
    {
        // Early-exit when no GitHub PAT is configured — the downstream
        // services would each 401 anyway, but skipping here avoids four
        // failed round-trips per tick. The services resolve their own
        // PATs via PatResolver from here on.
        boolean haveGitHubPat = credentialService.getSecret(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME)
                .filter(s -> !s.isBlank())
                .isPresent();
        if (!haveGitHubPat) {
            return;
        }
        Instant now = Instant.now();

        // Profile refresh sets GITHUB_LOGIN as a side-effect, so other feeds
        // can rely on it being populated after a successful first run.
        String login = refreshProfileIfStale(now)
                .orElseGet(() -> settingsStore.get(AppSettingsStore.Key.GITHUB_LOGIN).orElse(null));
        if (login == null) {
            return;
        }

        refreshEventsIfStale(login, EventFeed.RECENT, now);
        refreshEventsIfStale(login, EventFeed.FOLLOWING, now);
        refreshStatsIfStale(login, now);
        refreshOrgsIfStale(login, now);
    }

    /** Returns the freshly-fetched login when it ran, empty otherwise. */
    private Optional<String> refreshProfileIfStale(Instant now)
    {
        Optional<String> existingLogin = settingsStore.get(AppSettingsStore.Key.GITHUB_LOGIN);
        Optional<Instant> fetchedAt = existingLogin
                .flatMap(homeCache::findProfile)
                .map(SqliteGithubHomeCacheStore.TimedValue::fetchedAt);
        if (fetchedAt.isPresent() && Duration.between(fetchedAt.get(), now).compareTo(PROFILE_TTL) < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(repoService.refreshUserProfileFromGitHub().login());
        }
        catch (Exception e) {
            log.warn("Profile refresh failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void refreshEventsIfStale(String login, EventFeed feed, Instant now)
    {
        Instant fetchedAt = homeCache.findEvents(login, feed)
                .map(SqliteGithubHomeCacheStore.TimedValue::fetchedAt)
                .orElse(Instant.EPOCH);
        if (Duration.between(fetchedAt, now).compareTo(EVENTS_TTL) < 0) {
            return;
        }
        try {
            switch (feed) {
                case RECENT -> repoService.refreshRecentEventsFromGitHub(login);
                case FOLLOWING -> repoService.refreshFollowingEventsFromGitHub(login);
            }
        }
        catch (Exception e) {
            log.warn("{} events refresh failed: {}", feed, e.getMessage());
        }
    }

    private void refreshStatsIfStale(String login, Instant now)
    {
        Instant fetchedAt = homeCache.findStats(login)
                .map(SqliteGithubHomeCacheStore.TimedValue::fetchedAt)
                .orElse(Instant.EPOCH);
        if (Duration.between(fetchedAt, now).compareTo(STATS_TTL) < 0) {
            return;
        }
        try {
            statsService.refreshFromGitHub(login);
        }
        catch (Exception e) {
            log.warn("Stats refresh failed: {}", e.getMessage());
        }
    }

    private void refreshOrgsIfStale(String login, Instant now)
    {
        Instant fetchedAt = homeCache.findOrgs(login)
                .map(SqliteGithubHomeCacheStore.TimedValue::fetchedAt)
                .orElse(Instant.EPOCH);
        if (Duration.between(fetchedAt, now).compareTo(ORGS_TTL) < 0) {
            return;
        }
        try {
            repoService.refreshUserOrgsFromGitHub(login);
        }
        catch (Exception e) {
            log.warn("Orgs refresh failed: {}", e.getMessage());
        }
    }
}
