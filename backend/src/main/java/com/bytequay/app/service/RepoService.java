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

import com.bytequay.app.domain.ContributionCalendar;
import com.bytequay.app.domain.PrViewState;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RecentEvent;
import com.bytequay.app.domain.RepoMeta;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.StoredRepoMeta;
import com.bytequay.app.domain.UserCommitSummary;
import com.bytequay.app.domain.UserProfile;
import com.bytequay.app.domain.UserRepo;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.repository.sqlite.PrViewStateStore;
import com.bytequay.app.repository.sqlite.RepoMetaStore;
import com.bytequay.app.repository.sqlite.SqliteAppSettingsStore;
import com.bytequay.app.repository.sqlite.SqliteGithubHomeCacheStore;
import com.bytequay.app.repository.sqlite.SqliteGithubHomeCacheStore.EventFeed;
import com.bytequay.app.repository.sqlite.SqliteWatchedRepoStore;
import com.bytequay.app.service.credentials.PatResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import static com.bytequay.app.config.AsyncConfig.IO_EXECUTOR;
import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static java.util.Objects.requireNonNull;

@Service
public class RepoService
{
    private static final Logger log = LoggerFactory.getLogger(RepoService.class);
    private static final Duration REPO_META_TTL = Duration.ofHours(1);
    private static final Duration CONTRIBUTION_CALENDAR_TTL = Duration.ofHours(8);
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private final SqliteWatchedRepoStore watchedRepoStore;
    private final GitHubPullRequestReadClient pullRequests;
    private final GitHubRepoClient repos;
    private final GitHubAccountClient accounts;
    private final PrViewStateStore viewStateStore;
    private final RepoMetaStore repoMetaStore;
    private final SqliteGithubHomeCacheStore homeCache;
    private final SqliteAppSettingsStore settingsStore;
    private final PatResolver patResolver;
    private final Executor ioExecutor;
    private final Map<String, CachedContributionCalendar> contributionCalendarCache = new ConcurrentHashMap<>();

    @Autowired
    public RepoService(
            SqliteWatchedRepoStore watchedRepoStore,
            GitHubPullRequestReadClient pullRequests,
            GitHubRepoClient repos,
            GitHubAccountClient accounts,
            PrViewStateStore viewStateStore,
            RepoMetaStore repoMetaStore,
            SqliteGithubHomeCacheStore homeCache,
            SqliteAppSettingsStore settingsStore,
            PatResolver patResolver,
            @Qualifier(IO_EXECUTOR) Executor ioExecutor)
    {
        this.watchedRepoStore = requireNonNull(watchedRepoStore, "watchedRepoStore is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.repos = requireNonNull(repos, "repos is null");
        this.accounts = requireNonNull(accounts, "accounts is null");
        this.viewStateStore = requireNonNull(viewStateStore, "viewStateStore is null");
        this.repoMetaStore = requireNonNull(repoMetaStore, "repoMetaStore is null");
        this.homeCache = requireNonNull(homeCache, "homeCache is null");
        this.settingsStore = requireNonNull(settingsStore, "settingsStore is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.ioExecutor = requireNonNull(ioExecutor, "ioExecutor is null");
    }

    public List<WatchedRepo> listWatchedRepos()
    {
        return watchedRepoStore.findAll();
    }

    public WatchedRepo addWatchedRepo(String owner, String repo)
    {
        return watchedRepoStore.add(owner, repo);
    }

    public void removeWatchedRepo(String owner, String repo)
    {
        watchedRepoStore.remove(owner, repo);
    }

    public ContributionCalendar getContributionCalendar(String login)
    {
        requireNotBlank(login, "login must not be blank");
        CachedContributionCalendar cached = contributionCalendarCache.get(login);
        if (cached != null && cached.fetchedAt().plus(CONTRIBUTION_CALENDAR_TTL).isAfter(Instant.now())) {
            return cached.value();
        }

        try {
            ContributionCalendar fresh = accounts.fetchContributionCalendar(patResolver.resolve(), login);
            contributionCalendarCache.put(login, new CachedContributionCalendar(fresh, Instant.now()));
            return fresh;
        }
        catch (IllegalStateException e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Contribution graph temporarily unavailable",
                    e);
        }
    }

    public List<UserCommitSummary> getUserCommitsOnDate(String login, String isoDate)
    {
        requireNotBlank(login, "login must not be blank");
        if (isoDate == null || !ISO_DATE.matcher(isoDate).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date must be yyyy-MM-dd");
        }
        return accounts.fetchUserCommitsOnDate(patResolver.resolve(), login, isoDate);
    }

    public UserProfile getUserProfile()
    {
        Optional<String> login = settingsStore.get(SqliteAppSettingsStore.GITHUB_LOGIN);
        if (login.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not yet cached");
        }
        return homeCache.findProfile(login.orElseThrow())
                .map(SqliteGithubHomeCacheStore.TimedValue::value)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not yet cached"));
    }

    public UserProfile refreshUserProfileFromGitHub()
    {
        String pat = patResolver.resolve();
        UserProfile profile = accounts.fetchUserProfile(pat);
        UserProfile fresh = new UserProfile(
                profile.login(), profile.name(), profile.avatarUrl(), profile.htmlUrl(),
                profile.publicRepos(), profile.followers(), profile.following(), profile.bio(),
                profile.location(), profile.company(), profile.email(), accounts.fetchHasSponsorsListing(pat));
        homeCache.putProfile(fresh.login(), fresh, Instant.now());
        settingsStore.set(SqliteAppSettingsStore.GITHUB_LOGIN, fresh.login());
        return fresh;
    }

    public PullRequest getRepoPullRequest(String owner, String repo, int number)
    {
        String pat = patResolver.resolve(owner + "/" + repo);
        PullRequest fresh = pullRequests.getPullRequest(pat, PullRequestRef.of(owner, repo, number));
        return overlayViewState(fresh, viewStateStore.findAll().get(fresh.id()));
    }

    public RepoMeta getRepoMeta(String owner, String repo)
    {
        Optional<StoredRepoMeta> stored = repoMetaStore.find(owner, repo);
        Instant now = Instant.now();
        if (stored.isPresent()) {
            StoredRepoMeta value = stored.orElseThrow();
            boolean complete = value.meta().ownerAvatarUrl() != null;
            boolean fresh = !value.syncedAt().isBefore(now.minus(REPO_META_TTL));
            if (complete && fresh) {
                return value.meta();
            }
            if (complete) {
                ioExecutor.execute(() -> refreshRepoMeta(owner, repo));
                return value.meta();
            }
        }
        RepoMeta fresh = fetchRepoMeta(owner, repo);
        repoMetaStore.save(owner, repo, fresh, now);
        return fresh;
    }

    private RepoMeta fetchRepoMeta(String owner, String repo)
    {
        return repos.fetchRepoMeta(patResolver.resolve(owner + "/" + repo), RepoRef.of(owner, repo));
    }

    private void refreshRepoMeta(String owner, String repo)
    {
        try {
            repoMetaStore.save(owner, repo, fetchRepoMeta(owner, repo), Instant.now());
        }
        catch (RuntimeException e) {
            log.warn("Background repo-meta refresh for {}/{} failed: {}", owner, repo, e.getMessage());
        }
    }

    public List<UserRepo> getUserRepos()
    {
        return accounts.fetchUserRepos(patResolver.resolve());
    }

    public List<UserRepo> searchRepos(String query)
    {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        String pat = patResolver.resolve();
        int slash = trimmed.indexOf('/');
        if (slash > 0 && slash == trimmed.lastIndexOf('/') && slash < trimmed.length() - 1) {
            String owner = trimmed.substring(0, slash).trim();
            String name = trimmed.substring(slash + 1).trim();
            Optional<UserRepo> exact = accounts.fetchRepository(pat, owner, name);
            if (exact.isPresent()) {
                return List.of(exact.orElseThrow());
            }
            return accounts.searchRepositories(pat, name + " in:name user:" + owner);
        }
        return accounts.searchRepositories(pat, trimmed + " in:name");
    }

    public List<RecentEvent> getRecentEvents(String login)
    {
        return cachedEvents(login, EventFeed.RECENT);
    }

    public List<RecentEvent> getFollowingEvents(String login)
    {
        return cachedEvents(login, EventFeed.FOLLOWING);
    }

    private List<RecentEvent> cachedEvents(String login, EventFeed feed)
    {
        return homeCache.findEvents(login, feed)
                .map(SqliteGithubHomeCacheStore.TimedValue::value)
                .orElse(List.of());
    }

    public List<RecentEvent> refreshRecentEventsFromGitHub(String login)
    {
        Instant monthStart = ZonedDateTime.now(ZoneOffset.UTC)
                .toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<RecentEvent> fresh = accounts.fetchUserEvents(patResolver.resolve(), login, 100).stream()
                .filter(event -> event.createdAt() != null && !event.createdAt().isBefore(monthStart))
                .toList();
        homeCache.putEvents(login, EventFeed.RECENT, fresh, Instant.now());
        return fresh;
    }

    public List<RecentEvent> refreshFollowingEventsFromGitHub(String login)
    {
        List<RecentEvent> all = accounts.fetchReceivedEvents(patResolver.resolve(), login, 30);
        List<RecentEvent> fresh = List.copyOf(all.size() <= 10 ? all : all.subList(0, 10));
        homeCache.putEvents(login, EventFeed.FOLLOWING, fresh, Instant.now());
        return fresh;
    }

    private static PullRequest overlayViewState(PullRequest pr, PrViewState state)
    {
        if (state == null) {
            return pr;
        }
        return new PullRequest(
                pr.id(), pr.repo(), pr.number(), pr.title(), pr.author(), pr.htmlUrl(),
                pr.createdAt(), pr.updatedAt(), pr.origin(), pr.labels(), pr.labelColors(), pr.draft(),
                state.viewedAt(), state.reviewedAt(), state.handledAction(), pr.requestedReviewers(),
                pr.ciStatus(), pr.additions(), pr.deletions(), pr.commentCount(), pr.attentionReason(),
                pr.state(), pr.closedAt(), pr.mergedAt(), pr.mergeable(), pr.mergeableState(),
                pr.headPushedAt(), pr.reviewerVerdicts(), state.snoozedUntil(), state.snoozeWakeReason(),
                pr.headRef());
    }

    private record CachedContributionCalendar(ContributionCalendar value, Instant fetchedAt) {}
}
