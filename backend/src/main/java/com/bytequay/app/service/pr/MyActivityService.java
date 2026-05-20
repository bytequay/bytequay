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
package com.bytequay.app.service.pr;

import com.bytequay.app.domain.ContributionCalendar;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.domain.MyActivitySummary;
import com.bytequay.app.domain.MyActivitySummary.DailyAuthored;
import com.bytequay.app.domain.MyActivitySummary.RepoActivityCount;
import com.bytequay.app.domain.PrAnalyticsSummary.KpiCard;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.StoredPrDetail;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.PrDetailStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.CredentialService;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Aggregates the local PR store into the "what did I author" figures
 * the Dev activity page renders. Today only the PR-authored metrics
 * are sourced from local data — {@code commitsMade} and {@code
 * commentsPosted} are placeholders pending the activity-events
 * mirror described in {@code docs/mockups/activity-design.md}.
 */
@Service
public class MyActivityService
{
    private static final Logger log = LoggerFactory.getLogger(MyActivityService.class);
    private static final int REPOS_MAX_ROWS = 8;
    private static final int DAILY_MAX_DAYS_ALL = 90;
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    // The calendar GraphQL call is uncached upstream, so we hold the
    // last fetch per login in-process for a short TTL. Cheap to keep
    // (≤366 days of small day records); refreshes silently on miss.
    private static final Duration CALENDAR_TTL = Duration.ofMinutes(5);

    private final PullRequestStore pullRequestStore;
    private final PrDetailStore detailStore;
    private final WatchedRepoStore watchedRepoStore;
    private final AppSettingsStore settingsStore;
    private final PullRequestRepository gitHub;
    private final CredentialService credentialService;
    private final ConcurrentMap<String, CachedCalendar> calendarCache = new ConcurrentHashMap<>();

    public MyActivityService(
            PullRequestStore pullRequestStore,
            PrDetailStore detailStore,
            WatchedRepoStore watchedRepoStore,
            AppSettingsStore settingsStore,
            PullRequestRepository gitHub,
            CredentialService credentialService)
    {
        this.pullRequestStore = requireNonNull(pullRequestStore, "pullRequestStore is null");
        this.detailStore = requireNonNull(detailStore, "detailStore is null");
        this.watchedRepoStore = requireNonNull(watchedRepoStore, "watchedRepoStore is null");
        this.settingsStore = requireNonNull(settingsStore, "settingsStore is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
    }

    public MyActivitySummary summarize(String rawScope, String requestedZone)
    {
        String scope = normalizeScope(rawScope);
        Instant cutoff = cutoffFor(scope);
        ZoneId zone = resolveZone(requestedZone);
        int watchedCount = watchedRepoStore.findAll().size();
        String currentLogin = settingsStore.get(AppSettingsStore.Key.GITHUB_LOGIN).orElse(null);

        Aggregate agg = currentLogin == null
                ? Aggregate.empty()
                : aggregate(pullRequestStore.findAll(), currentLogin, cutoff, zone);

        KpiCard prsOpened = new KpiCard(
                (double) agg.opened,
                formatCount(agg.opened),
                false,
                null);
        KpiCard prsMerged = new KpiCard(
                (double) agg.merged,
                formatCount(agg.merged),
                false,
                null);
        Optional<ContributionCalendar> calendar = currentLogin == null
                ? Optional.empty()
                : loadCalendar(currentLogin);
        KpiCard commitsMade = buildCommitsCard(calendar, cutoff, zone);
        Integer currentStreak = calendar.map(c -> currentStreak(c, zone)).orElse(null);
        Integer longestStreak = calendar.map(MyActivityService::longestStreak).orElse(null);
        KpiCard commentsPosted = new KpiCard(
                (double) agg.comments,
                formatCount(agg.comments),
                true,
                null);

        return new MyActivitySummary(
                scope,
                watchedCount,
                currentLogin,
                prsOpened,
                prsMerged,
                commitsMade,
                commentsPosted,
                buildDailyAuthored(agg.dailyOpened, agg.dailyMerged, cutoff, zone),
                buildReposByActivity(agg.repoOpened, agg.repoMerged),
                currentStreak,
                longestStreak);
    }

    private static String normalizeScope(String raw)
    {
        if (raw == null) {
            return "30d";
        }
        String n = raw.toLowerCase(Locale.ROOT);
        return switch (n) {
            case "7d", "30d", "90d", "all" -> n;
            default -> "30d";
        };
    }

    private static Instant cutoffFor(String scope)
    {
        Instant now = Instant.now();
        return switch (scope) {
            case "7d" -> now.minus(Duration.ofDays(7));
            case "30d" -> now.minus(Duration.ofDays(30));
            case "90d" -> now.minus(Duration.ofDays(90));
            default -> Instant.EPOCH;
        };
    }

    private static ZoneId resolveZone(String requested)
    {
        if (requested == null || requested.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(requested);
        }
        catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    private Aggregate aggregate(List<PullRequest> all, String currentLogin, Instant cutoff, ZoneId zone)
    {
        int opened = 0;
        int merged = 0;
        int comments = 0;
        Map<LocalDate, Integer> dailyOpened = new HashMap<>();
        Map<LocalDate, Integer> dailyMerged = new HashMap<>();
        Map<String, Integer> repoOpened = new HashMap<>();
        Map<String, Integer> repoMerged = new HashMap<>();
        for (PullRequest pr : all) {
            // Comments KPI scans EVERY PR's cached detail — not just
            // PRs you authored. A comment you left on someone else's
            // PR still counts toward "Comments posted".
            comments += countCommentsBy(pr.id(), currentLogin, cutoff);

            if (pr.author() == null || !pr.author().equalsIgnoreCase(currentLogin)) {
                continue;
            }
            // Opened: bucket by createdAt.
            Instant created = pr.createdAt();
            if (created != null && (isAllTime(cutoff) || !created.isBefore(cutoff))) {
                opened++;
                LocalDate day = created.atZone(zone).toLocalDate();
                dailyOpened.merge(day, 1, Integer::sum);
                if (pr.repo() != null) {
                    repoOpened.merge(pr.repo(), 1, Integer::sum);
                }
            }
            // Merged: separate bucket — same PR can count toward both
            // opened (if it opened in-window) and merged (if it merged
            // in-window) on different days.
            Instant mergedAt = pr.mergedAt();
            if (mergedAt != null && (isAllTime(cutoff) || !mergedAt.isBefore(cutoff))) {
                merged++;
                LocalDate day = mergedAt.atZone(zone).toLocalDate();
                dailyMerged.merge(day, 1, Integer::sum);
                if (pr.repo() != null) {
                    repoMerged.merge(pr.repo(), 1, Integer::sum);
                }
            }
        }
        return new Aggregate(opened, merged, comments, dailyOpened, dailyMerged, repoOpened, repoMerged);
    }

    private int countCommentsBy(long prId, String login, Instant cutoff)
    {
        StoredPrDetail detail = detailStore.find(prId).orElse(null);
        if (detail == null) {
            return 0;
        }
        int count = 0;
        // Top-level PR / issue comments come through the timeline as
        // event = "commented". Bot accounts on most repos don't share
        // a login with the user, so a simple author equality is fine.
        for (PrTimelineEvent ev : detail.timeline()) {
            if (!"commented".equalsIgnoreCase(ev.event())) {
                continue;
            }
            if (ev.actor() == null || !ev.actor().equalsIgnoreCase(login)) {
                continue;
            }
            if (ev.timestamp() == null) {
                continue;
            }
            if (!isAllTime(cutoff) && ev.timestamp().isBefore(cutoff)) {
                continue;
            }
            count++;
        }
        // Per-line review comments. Each message in a thread counts —
        // a long back-and-forth is many comments, not one.
        for (PrReviewThreadMessage msg : detail.reviewComments()) {
            if (msg.author() == null || !msg.author().equalsIgnoreCase(login)) {
                continue;
            }
            if (msg.createdAt() == null) {
                continue;
            }
            if (!isAllTime(cutoff) && msg.createdAt().isBefore(cutoff)) {
                continue;
            }
            count++;
        }
        return count;
    }

    private static List<DailyAuthored> buildDailyAuthored(
            Map<LocalDate, Integer> opened,
            Map<LocalDate, Integer> merged,
            Instant cutoff,
            ZoneId zone)
    {
        if (opened.isEmpty() && merged.isEmpty()) {
            return ImmutableList.of();
        }
        LocalDate today = LocalDate.now(zone);
        LocalDate from = isAllTime(cutoff)
                ? today.minusDays(DAILY_MAX_DAYS_ALL - 1L)
                : cutoff.atZone(zone).toLocalDate();
        ImmutableList.Builder<DailyAuthored> out = ImmutableList.builder();
        for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
            out.add(new DailyAuthored(
                    day.format(ISO_DATE),
                    opened.getOrDefault(day, 0),
                    merged.getOrDefault(day, 0)));
        }
        return out.build();
    }

    private static List<RepoActivityCount> buildReposByActivity(
            Map<String, Integer> opened,
            Map<String, Integer> merged)
    {
        // Merge the two maps' keysets and rank by total activity.
        Map<String, int[]> combined = new HashMap<>();
        opened.forEach((k, v) -> combined.computeIfAbsent(k, x -> new int[2])[0] = v);
        merged.forEach((k, v) -> combined.computeIfAbsent(k, x -> new int[2])[1] = v);
        return combined.entrySet().stream()
                .sorted((a, b) -> {
                    int totalA = a.getValue()[0] + a.getValue()[1];
                    int totalB = b.getValue()[0] + b.getValue()[1];
                    int cmp = Integer.compare(totalB, totalA);
                    return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
                })
                .limit(REPOS_MAX_ROWS)
                .map(e -> new RepoActivityCount(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .collect(toImmutableList());
    }

    private static KpiCard buildCommitsCard(Optional<ContributionCalendar> calendar, Instant cutoff, ZoneId zone)
    {
        if (calendar.isEmpty()) {
            return new KpiCard(null, "—", false, "PAT required");
        }
        long total = 0;
        LocalDate from = isAllTime(cutoff) ? null : cutoff.atZone(zone).toLocalDate();
        for (ContributionCalendar.Week week : calendar.get().weeks()) {
            for (ContributionCalendar.Day day : week.days()) {
                if (day.date() == null) {
                    continue;
                }
                if (from != null && day.date().isBefore(from)) {
                    continue;
                }
                total += day.contributionCount();
            }
        }
        // GitHub's contribution calendar covers commits across all
        // public + private repos the user has access to — including
        // ones we don't watch. That's a feature, not a bug, but it
        // also means the number isn't constrained to the local
        // store's "watched set" the way the rest of the page is.
        return new KpiCard((double) total, formatCount(total), false, null);
    }

    private static int currentStreak(ContributionCalendar calendar, ZoneId zone)
    {
        Map<LocalDate, Integer> byDay = flattenCalendar(calendar);
        LocalDate today = LocalDate.now(zone);
        // If today is still zero, start counting from yesterday — the
        // day isn't over yet, so a missing commit today shouldn't
        // break a streak that ran through yesterday.
        LocalDate cursor = byDay.getOrDefault(today, 0) > 0 ? today : today.minusDays(1);
        int streak = 0;
        while (byDay.getOrDefault(cursor, 0) > 0) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    private static int longestStreak(ContributionCalendar calendar)
    {
        int longest = 0;
        int run = 0;
        // Walk weeks in order; within each week, days are Sun → Sat.
        // The day-of-week ordering is consistent so we can treat the
        // sequence as a chronological run with no gaps.
        for (ContributionCalendar.Week week : calendar.weeks()) {
            for (ContributionCalendar.Day day : week.days()) {
                if (day.contributionCount() > 0) {
                    run++;
                    if (run > longest) {
                        longest = run;
                    }
                }
                else {
                    run = 0;
                }
            }
        }
        return longest;
    }

    private static Map<LocalDate, Integer> flattenCalendar(ContributionCalendar calendar)
    {
        Map<LocalDate, Integer> out = new HashMap<>();
        for (ContributionCalendar.Week week : calendar.weeks()) {
            for (ContributionCalendar.Day day : week.days()) {
                if (day.date() != null) {
                    out.put(day.date(), day.contributionCount());
                }
            }
        }
        return out;
    }

    private Optional<ContributionCalendar> loadCalendar(String login)
    {
        CachedCalendar cached = calendarCache.get(login);
        if (cached != null && Duration.between(cached.fetchedAt(), Instant.now()).compareTo(CALENDAR_TTL) < 0) {
            return Optional.of(cached.value());
        }
        Optional<String> pat = credentialService.getSecret(CredentialType.ACCOUNT, CredentialService.GITHUB_ACCOUNT_NAME)
                .filter(s -> !s.isBlank());
        if (pat.isEmpty()) {
            return Optional.empty();
        }
        try {
            ContributionCalendar fresh = gitHub.fetchContributionCalendar(pat.get(), login);
            calendarCache.put(login, new CachedCalendar(fresh, Instant.now()));
            return Optional.of(fresh);
        }
        catch (Exception e) {
            log.debug("contribution calendar fetch failed for {}: {}", login, e.getMessage());
            return Optional.empty();
        }
    }

    private record CachedCalendar(ContributionCalendar value, Instant fetchedAt) {}

    private static String formatCount(long n)
    {
        if (n < 1000) {
            return Long.toString(n);
        }
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static boolean isAllTime(Instant cutoff)
    {
        return Instant.EPOCH.equals(cutoff);
    }

    private record Aggregate(
            int opened,
            int merged,
            int comments,
            Map<LocalDate, Integer> dailyOpened,
            Map<LocalDate, Integer> dailyMerged,
            Map<String, Integer> repoOpened,
            Map<String, Integer> repoMerged)
    {
        static Aggregate empty()
        {
            return new Aggregate(0, 0, 0, new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
        }
    }
}
