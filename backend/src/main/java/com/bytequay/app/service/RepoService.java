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

import com.bytequay.app.domain.GitHubUserMatch;
import com.bytequay.app.domain.ListPullRequestsQuery;
import com.bytequay.app.domain.PrViewState;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RecentEvent;
import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.UserOrg;
import com.bytequay.app.domain.UserProfile;
import com.bytequay.app.domain.UserRepo;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.PrViewStateStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.WatchedRepoStore;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Service
public class RepoService
{
    private static final Duration HOME_CACHE_TTL = Duration.ofMinutes(2);

    private final WatchedRepoStore watchedRepoStore;
    private final PullRequestRepository gitHub;
    private final PrViewStateStore viewStateStore;

    private volatile CachedValue<UserProfile> cachedProfile;
    private volatile CachedValue<List<RecentEvent>> cachedRecentEvents;
    private volatile CachedValue<List<RecentEvent>> cachedFollowingEvents;

    public RepoService(
            WatchedRepoStore watchedRepoStore,
            PullRequestRepository gitHub,
            PrViewStateStore viewStateStore)
    {
        this.watchedRepoStore = requireNonNull(watchedRepoStore, "watchedRepoStore is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.viewStateStore = requireNonNull(viewStateStore, "viewStateStore is null");
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

    public UserProfile getUserProfile(String pat)
    {
        CachedValue<UserProfile> c = cachedProfile;
        if (c != null && c.isValid()) {
            return c.value();
        }
        UserProfile rest = gitHub.fetchUserProfile(pat);
        // hasSponsors lives in GraphQL only — overlay it onto the REST result.
        // Failure-path returns false from GitHubClient, which means the
        // Sponsors row simply hides on the home page.
        boolean hasSponsors = gitHub.fetchHasSponsorsListing(pat);
        UserProfile fresh = new UserProfile(
                rest.login(),
                rest.name(),
                rest.avatarUrl(),
                rest.htmlUrl(),
                rest.publicRepos(),
                rest.followers(),
                rest.following(),
                rest.bio(),
                rest.location(),
                rest.company(),
                rest.email(),
                hasSponsors);
        cachedProfile = CachedValue.of(fresh);
        return fresh;
    }

    public List<PullRequest> getRepoPullRequests(String pat, String owner, String repo)
    {
        List<PullRequest> fresh = gitHub.listPullRequests(pat, RepoRef.of(owner, repo), ListPullRequestsQuery.openPullRequests());
        Map<Long, PrViewState> stateByPrId = viewStateStore.findAll();
        return fresh.stream()
                .map(pr -> overlayViewState(pr, stateByPrId.get(pr.id())))
                .collect(toImmutableList());
    }

    /**
     * Single-PR fetch with the same view-state overlay {@link
     * #getRepoPullRequests} applies, so the deep-link fallback row looks
     * identical to what would have come back in the list response.
     */
    public PullRequest getRepoPullRequest(String pat, String owner, String repo, int number)
    {
        PullRequest fresh = gitHub.getPullRequest(pat, PullRequestRef.of(owner, repo, number));
        PrViewState state = viewStateStore.findAll().get(fresh.id());
        return overlayViewState(fresh, state);
    }

    private static PullRequest overlayViewState(PullRequest pr, PrViewState state)
    {
        if (state == null) {
            return pr;
        }
        return new PullRequest(
                pr.id(),
                pr.repo(),
                pr.number(),
                pr.title(),
                pr.author(),
                pr.htmlUrl(),
                pr.createdAt(),
                pr.updatedAt(),
                pr.origin(),
                pr.labels(),
                pr.labelColors(),
                pr.draft(),
                state.viewedAt(),
                state.reviewedAt(),
                state.handledAction(),
                pr.requestedReviewers(),
                pr.ciStatus(),
                pr.additions(),
                pr.deletions(),
                pr.commentCount(),
                pr.attentionReason(),
                pr.state(),
                pr.closedAt(),
                pr.mergedAt(),
                pr.mergeable(),
                pr.mergeableState(),
                pr.headPushedAt(),
                pr.reviewerVerdicts());
    }

    public List<RepoIssue> getRepoIssues(String pat, String owner, String repo)
    {
        return gitHub.fetchRepoIssues(pat, RepoRef.of(owner, repo));
    }

    public List<UserRepo> getUserRepos(String pat)
    {
        return gitHub.fetchUserRepos(pat);
    }

    public List<UserOrg> getUserOrgs(String pat)
    {
        return gitHub.fetchUserOrgs(pat);
    }

    public List<UserRepo> searchRepos(String pat, String query)
    {
        return gitHub.searchRepositories(pat, query);
    }

    public List<GitHubUserMatch> searchUsers(String pat, String query)
    {
        if (query == null || query.trim().length() < 2) {
            // GitHub returns 422 for short queries; short-circuit with an
            // empty list so the autocomplete doesn't error on "a" / "ab".
            return Collections.emptyList();
        }
        return gitHub.searchUsers(pat, query.trim());
    }

    public List<RecentEvent> getRecentEvents(String pat, String login)
    {
        CachedValue<List<RecentEvent>> c = cachedRecentEvents;
        if (c != null && c.isValid()) {
            return c.value();
        }
        Instant monthStart = ZonedDateTime.now(ZoneOffset.UTC)
                .toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<RecentEvent> fresh = gitHub.fetchUserEvents(pat, login, 100).stream()
                .filter(e -> e.createdAt() != null && !e.createdAt().isBefore(monthStart))
                .collect(toImmutableList());
        cachedRecentEvents = CachedValue.of(fresh);
        return fresh;
    }

    public List<RecentEvent> getFollowingEvents(String pat, String login)
    {
        CachedValue<List<RecentEvent>> cachedValue = cachedFollowingEvents;
        if (cachedValue != null && cachedValue.isValid()) {
            return cachedValue.value();
        }
        List<RecentEvent> all = gitHub.fetchReceivedEvents(pat, login, 30);
        List<RecentEvent> fresh = ImmutableList.copyOf(all.size() <= 10 ? all : all.subList(0, 10));
        cachedFollowingEvents = CachedValue.of(fresh);
        return fresh;
    }

    public UserProfile updateUserProfile(String pat, String name, String bio, String location)
    {
        UserProfile updated = gitHub.updateUserProfile(pat, name, bio, location);
        // Invalidate cached profile so the next read reflects the update immediately
        cachedProfile = null;
        return updated;
    }

    private record CachedValue<T>(T value, Instant expiresAt)
    {
        static <T> CachedValue<T> of(T value)
        {
            return new CachedValue<>(value, Instant.now().plus(HOME_CACHE_TTL));
        }

        boolean isValid()
        {
            return Instant.now().isBefore(expiresAt);
        }
    }
}
