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

import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.domain.PrViewState;
import com.bytequay.app.domain.RecentEvent;
import com.bytequay.app.domain.UserStats;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.PrViewStateStore;
import com.bytequay.app.repository.PullRequestRepository;
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

import static com.bytequay.app.service.CredentialService.GITHUB_ACCOUNT_NAME;
import static java.util.Objects.requireNonNull;

@Service
public class StatsService
{
    private static final Logger log = LoggerFactory.getLogger(StatsService.class);
    // Five minutes balances "I just pushed, where's my commit?" against
    // hammering GitHub's events endpoint. The events feed itself has its
    // own propagation delay (typically <5 min for public repos, longer
    // for private), so anything tighter than this just burns API quota.
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int RAW_EVENTS_LIMIT = 100;

    private final PullRequestRepository gitHub;
    private final PrViewStateStore viewStateStore;
    private final CredentialService credentialService;
    private final AppSettingsStore settingsStore;

    private volatile UserStats cached = UserStats.empty();

    public StatsService(
            PullRequestRepository gitHub,
            PrViewStateStore viewStateStore,
            CredentialService credentialService,
            AppSettingsStore settingsStore)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.viewStateStore = requireNonNull(viewStateStore, "viewStateStore is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.settingsStore = requireNonNull(settingsStore, "settingsStore is null");
    }

    /** Returns cached stats, refreshing if the cache is stale. Stores login for scheduled refresh. */
    public UserStats getStats(String login)
    {
        return getStats(login, false);
    }

    /**
     * As {@link #getStats(String)}, but {@code force=true} bypasses the
     * cache TTL and pulls fresh events from GitHub immediately. Used by
     * the home page's "Refresh stats" button.
     */
    public UserStats getStats(String login, boolean force)
    {
        if (login != null && !login.isBlank()) {
            settingsStore.set(AppSettingsStore.Key.GITHUB_LOGIN, login);
        }
        if (force) {
            forceRefresh(login);
        }
        else {
            refreshIfStale(login);
        }
        return cached;
    }

    private void forceRefresh(String login)
    {
        Optional<String> pat = credentialService.getSecret(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME).filter(s -> !s.isBlank());
        if (pat.isEmpty() || login == null || login.isBlank()) {
            return;
        }
        try {
            cached = compute(pat.get(), login);
        }
        catch (Exception e) {
            log.warn("Force stats refresh failed: {}", e.getMessage());
        }
    }

    /** Called from the sync job; uses the stored login so it can run without a request. */
    public void refreshIfStale()
    {
        Optional<String> login = settingsStore.get(AppSettingsStore.Key.GITHUB_LOGIN);
        refreshIfStale(login.orElse(null));
    }

    private void refreshIfStale(String login)
    {
        if (Duration.between(cached.updatedAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
            return;
        }
        Optional<String> pat = credentialService.getSecret(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME).filter(s -> !s.isBlank());
        if (pat.isEmpty() || login == null || login.isBlank()) {
            return;
        }
        try {
            cached = compute(pat.get(), login);
        }
        catch (Exception e) {
            log.warn("Stats refresh failed: {}", e.getMessage());
        }
    }

    private UserStats compute(String pat, String login)
    {
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
