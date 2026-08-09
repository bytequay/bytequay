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
package com.bytequay.app.service;

import com.bytequay.app.domain.PrViewState;
import com.bytequay.app.domain.RecentEvent;
import com.bytequay.app.domain.UserStats;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.sqlite.PrViewStateStore;
import com.bytequay.app.repository.sqlite.SqliteGithubHomeCacheStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Service
public class StatsService
{
    private static final Logger log = LoggerFactory.getLogger(StatsService.class);
    // Five minutes balances "I just pushed, where's my commit?" against
    // hammering GitHub's events endpoint. The events feed itself has its
    // own propagation delay (typically <5 min for public repos, longer
    // for private), so anything tighter than this just burns API quota.
    public static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int RAW_EVENTS_LIMIT = 100;

    private final PullRequestRepository gitHub;
    private final PrViewStateStore viewStateStore;
    private final AppSettingsStore settingsStore;
    private final SqliteGithubHomeCacheStore homeCache;
    private final PatResolver patResolver;

    public StatsService(
            PullRequestRepository gitHub,
            PrViewStateStore viewStateStore,
            AppSettingsStore settingsStore,
            SqliteGithubHomeCacheStore homeCache,
            PatResolver patResolver)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.viewStateStore = requireNonNull(viewStateStore, "viewStateStore is null");
        this.settingsStore = requireNonNull(settingsStore, "settingsStore is null");
        this.homeCache = requireNonNull(homeCache, "homeCache is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
    }

    /**
     * Returns the cached stats from the local DB. With {@code force=true}
     * (the home-page ↻ button), runs a synchronous refresh first so the user
     * sees the post-push numbers immediately. Otherwise it's a pure read —
     * staleness is the scheduler's problem.
     */
    public UserStats getStats(String login, boolean force)
    {
        if (login != null && !login.isBlank()) {
            settingsStore.set(AppSettingsStore.Key.GITHUB_LOGIN, login);
        }
        if (force && login != null && !login.isBlank()) {
            try {
                return refreshFromGitHub(login);
            }
            catch (Exception e) {
                // Catches both the 401 from patResolver when no PAT is
                // configured and any GitHub-side failure on the refresh
                // path; either way the read-side cache surfaces the
                // last-known stats.
                log.warn("Force stats refresh failed: {}", e.getMessage());
            }
        }
        return readFromCache(login);
    }

    /**
     * Reads the cached stats row by login. Returns {@link UserStats#empty()}
     * if the row hasn't been populated yet — same shape the in-memory cache
     * used to start with, so the home page renders zeros until the first
     * scheduler tick lands.
     */
    private UserStats readFromCache(String login)
    {
        if (login == null || login.isBlank()) {
            return UserStats.empty();
        }
        return homeCache.findStats(login)
                .map(SqliteGithubHomeCacheStore.TimedValue::value)
                .orElseGet(UserStats::empty);
    }

    /** Called by the post-PR-sync hook so the home-page numbers reflect any
     *  freshly-marked-reviewed PRs without waiting for the next 5-min tick. */
    public void refreshIfStale()
    {
        Optional<String> login = settingsStore.get(AppSettingsStore.Key.GITHUB_LOGIN);
        if (login.isEmpty()) {
            return;
        }
        Instant fetchedAt = homeCache.findStats(login.get())
                .map(SqliteGithubHomeCacheStore.TimedValue::fetchedAt)
                .orElse(Instant.EPOCH);
        if (Duration.between(fetchedAt, Instant.now()).compareTo(CACHE_TTL) < 0) {
            return;
        }
        try {
            refreshFromGitHub(login.get());
        }
        catch (Exception e) {
            // Either PatResolver had no PAT configured (401) or the
            // refresh itself failed — both fall through to "stale row
            // stays in the cache."
            log.warn("Stats refresh failed: {}", e.getMessage());
        }
    }

    /**
     * Fetches the raw events from GitHub, recomputes the periodised stats,
     * and persists them. Called from {@link #getStats} on force-refresh and
     * from the scheduler on its TTL tick.
     */
    public UserStats refreshFromGitHub(String login)
    {
        UserStats fresh = compute(login);
        homeCache.putStats(login, fresh, Instant.now());
        return fresh;
    }

    private UserStats compute(String login)
    {
        String pat = patResolver.resolve();
        Instant now = Instant.now();
        ZonedDateTime nowUtc = now.atZone(ZoneOffset.UTC);
        Instant todayStart = nowUtc.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant yesterdayStart = todayStart.minus(1, ChronoUnit.DAYS);
        Instant weekStart = nowUtc.toLocalDate().with(DayOfWeek.MONDAY).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant prevWeekStart = weekStart.minus(7, ChronoUnit.DAYS);
        Instant monthStart = nowUtc.toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<RecentEvent> events;
        try {
            events = gitHub.fetchUserEvents(pat, login, RAW_EVENTS_LIMIT);
        }
        catch (Exception e) {
            log.warn("Failed to fetch raw events for stats: {}", e.getMessage());
            events = ImmutableList.of();
        }

        int commitsToday = 0;
        int commitsYesterday = 0;
        int commitsWeek = 0;
        int commitsMonth = 0;
        int commitsPrevWeek = 0;
        int pushesToday = 0;
        int pushesYesterday = 0;
        int pushesWeek = 0;
        int pushesMonth = 0;
        int pushesPrevWeek = 0;
        int prsCreatedToday = 0;
        int prsCreatedYesterday = 0;
        int prsCreatedWeek = 0;
        int prsCreatedMonth = 0;
        int prsCreatedPrevWeek = 0;
        int prsReviewedToday = 0;
        int prsReviewedYesterday = 0;
        int prsReviewedWeek = 0;
        int prsReviewedMonth = 0;
        int prsReviewedPrevWeek = 0;
        int commentsToday = 0;
        int commentsYesterday = 0;
        int commentsWeek = 0;
        int commentsMonth = 0;
        int commentsPrevWeek = 0;

        for (RecentEvent event : events) {
            Instant ts = event.createdAt();
            if (ts == null) {
                continue;
            }
            boolean isMonth = !ts.isBefore(monthStart);
            boolean isWeek = !ts.isBefore(weekStart);
            boolean isToday = !ts.isBefore(todayStart);
            // Yesterday = [yesterdayStart, todayStart). Disjoint from today.
            boolean isYesterday = !ts.isBefore(yesterdayStart) && ts.isBefore(todayStart);
            // Previous week = [prevWeekStart, weekStart). Disjoint from this week.
            boolean isPrevWeek = !ts.isBefore(prevWeekStart) && ts.isBefore(weekStart);

            switch (event.type() != null ? event.type() : "") {
                case "PushEvent" -> {
                    int count = event.commitCount();
                    if (isMonth) {
                        pushesMonth++;
                        commitsMonth += count;
                    }
                    if (isWeek) {
                        pushesWeek++;
                        commitsWeek += count;
                    }
                    if (isToday) {
                        pushesToday++;
                        commitsToday += count;
                    }
                    if (isYesterday) {
                        pushesYesterday++;
                        commitsYesterday += count;
                    }
                    if (isPrevWeek) {
                        pushesPrevWeek++;
                        commitsPrevWeek += count;
                    }
                }
                case "PullRequestEvent" -> {
                    if ("opened".equals(event.action())) {
                        if (isMonth) {
                            prsCreatedMonth++;
                        }
                        if (isWeek) {
                            prsCreatedWeek++;
                        }
                        if (isToday) {
                            prsCreatedToday++;
                        }
                        if (isYesterday) {
                            prsCreatedYesterday++;
                        }
                        if (isPrevWeek) {
                            prsCreatedPrevWeek++;
                        }
                    }
                }
                case "PullRequestReviewEvent" -> {
                    if (isMonth) {
                        prsReviewedMonth++;
                    }
                    if (isWeek) {
                        prsReviewedWeek++;
                    }
                    if (isToday) {
                        prsReviewedToday++;
                    }
                    if (isYesterday) {
                        prsReviewedYesterday++;
                    }
                    if (isPrevWeek) {
                        prsReviewedPrevWeek++;
                    }
                }
                case "IssueCommentEvent", "PullRequestReviewCommentEvent" -> {
                    if (isMonth) {
                        commentsMonth++;
                    }
                    if (isWeek) {
                        commentsWeek++;
                    }
                    if (isToday) {
                        commentsToday++;
                    }
                    if (isYesterday) {
                        commentsYesterday++;
                    }
                    if (isPrevWeek) {
                        commentsPrevWeek++;
                    }
                }
                default -> { /* ignore */ }
            }
        }

        Map<Long, PrViewState> viewStates = viewStateStore.findAll();
        int viewedToday = 0;
        int viewedYesterday = 0;
        int viewedWeek = 0;
        int viewedMonth = 0;
        int viewedPrevWeek = 0;
        int markedReviewedToday = 0;
        int markedReviewedYesterday = 0;
        int markedReviewedWeek = 0;
        int markedReviewedMonth = 0;
        int markedReviewedPrevWeek = 0;

        for (PrViewState vs : viewStates.values()) {
            if (vs.viewedAt() != null) {
                Instant ts = vs.viewedAt();
                if (!ts.isBefore(monthStart)) {
                    viewedMonth++;
                }
                if (!ts.isBefore(weekStart)) {
                    viewedWeek++;
                }
                if (!ts.isBefore(todayStart)) {
                    viewedToday++;
                }
                if (!ts.isBefore(yesterdayStart) && ts.isBefore(todayStart)) {
                    viewedYesterday++;
                }
                if (!ts.isBefore(prevWeekStart) && ts.isBefore(weekStart)) {
                    viewedPrevWeek++;
                }
            }
            if (vs.reviewedAt() != null) {
                Instant ts = vs.reviewedAt();
                if (!ts.isBefore(monthStart)) {
                    markedReviewedMonth++;
                }
                if (!ts.isBefore(weekStart)) {
                    markedReviewedWeek++;
                }
                if (!ts.isBefore(todayStart)) {
                    markedReviewedToday++;
                }
                if (!ts.isBefore(yesterdayStart) && ts.isBefore(todayStart)) {
                    markedReviewedYesterday++;
                }
                if (!ts.isBefore(prevWeekStart) && ts.isBefore(weekStart)) {
                    markedReviewedPrevWeek++;
                }
            }
        }

        return new UserStats(
                new UserStats.StatPeriods(commitsToday, commitsYesterday, commitsWeek, commitsMonth, commitsPrevWeek),
                new UserStats.StatPeriods(pushesToday, pushesYesterday, pushesWeek, pushesMonth, pushesPrevWeek),
                new UserStats.StatPeriods(prsCreatedToday, prsCreatedYesterday, prsCreatedWeek, prsCreatedMonth, prsCreatedPrevWeek),
                new UserStats.StatPeriods(prsReviewedToday, prsReviewedYesterday, prsReviewedWeek, prsReviewedMonth, prsReviewedPrevWeek),
                new UserStats.StatPeriods(commentsToday, commentsYesterday, commentsWeek, commentsMonth, commentsPrevWeek),
                new UserStats.StatPeriods(viewedToday, viewedYesterday, viewedWeek, viewedMonth, viewedPrevWeek),
                new UserStats.StatPeriods(markedReviewedToday, markedReviewedYesterday, markedReviewedWeek, markedReviewedMonth, markedReviewedPrevWeek),
                now);
    }
}
