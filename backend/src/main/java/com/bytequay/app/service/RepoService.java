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
import com.bytequay.app.domain.GitHubUserMatch;
import com.bytequay.app.domain.IssueDetail;
import com.bytequay.app.domain.IssueTimelineEvent;
import com.bytequay.app.domain.ListPullRequestsQuery;
import com.bytequay.app.domain.PrViewState;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RecentEvent;
import com.bytequay.app.domain.RepoActivityItem;
import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.domain.RepoMeta;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.StoredRepoMeta;
import com.bytequay.app.domain.UserOrg;
import com.bytequay.app.domain.UserProfile;
import com.bytequay.app.domain.UserRepo;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.GithubHomeCacheStore;
import com.bytequay.app.repository.GithubHomeCacheStore.EventFeed;
import com.bytequay.app.repository.PrViewStateStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.RepoMetaStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import static com.bytequay.app.config.AsyncConfig.IO_EXECUTOR;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Service
public class RepoService
{
    private static final Logger log = LoggerFactory.getLogger(RepoService.class);
    /** Window inside which a {@link RepoMeta} row in {@link RepoMetaStore}
     *  is treated as fresh enough to skip a background refresh. Picked at
     *  one hour because description / license / topics / language bytes
     *  change on the order of days; reading hour-old data is fine. */
    private static final Duration REPO_META_TTL = Duration.ofHours(1);

    /** Same allowlist {@code PullRequestService} validates against —
     *  GitHub's reactions API takes exactly these eight content
     *  strings. Duplicated here rather than imported because the two
     *  services live in different packages and the constant is
     *  trivially small. */
    private static final Set<String> ALLOWED_REACTION_CONTENT = Set.of(
            "+1", "-1", "laugh", "confused", "heart", "hooray", "rocket", "eyes");

    private final WatchedRepoStore watchedRepoStore;
    private final PullRequestRepository gitHub;
    private final PrViewStateStore viewStateStore;
    private final RepoListCache repoListCache;
    private final RepoMetaStore repoMetaStore;
    private final GithubHomeCacheStore homeCache;
    private final AppSettingsStore settingsStore;
    private final Executor ioExecutor;

    public RepoService(
            WatchedRepoStore watchedRepoStore,
            PullRequestRepository gitHub,
            PrViewStateStore viewStateStore,
            RepoListCache repoListCache,
            RepoMetaStore repoMetaStore,
            GithubHomeCacheStore homeCache,
            AppSettingsStore settingsStore,
            @Qualifier(IO_EXECUTOR) Executor ioExecutor)
    {
        this.watchedRepoStore = requireNonNull(watchedRepoStore, "watchedRepoStore is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.viewStateStore = requireNonNull(viewStateStore, "viewStateStore is null");
        this.repoListCache = requireNonNull(repoListCache, "repoListCache is null");
        this.repoMetaStore = requireNonNull(repoMetaStore, "repoMetaStore is null");
        this.homeCache = requireNonNull(homeCache, "homeCache is null");
        this.settingsStore = requireNonNull(settingsStore, "settingsStore is null");
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

    /**
     * Last-12-months contribution heatmap for {@code login}, sourced from
     * GitHub's GraphQL contribution calendar. The repository layer
     * already swallows GraphQL failures into an empty calendar (since
     * the home card can render a blank grid without breaking the page),
     * so this method is a thin pass-through. {@code login} is required —
     * GraphQL needs an explicit user, there's no "viewer" shorthand for
     * contribution data.
     */
    public ContributionCalendar getContributionCalendar(String pat, String login)
    {
        if (login == null || login.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "login must not be blank");
        }
        return gitHub.fetchContributionCalendar(pat, login);
    }

    /**
     * Reads the cached profile from the local DB. Pure-DB read — no GitHub
     * call on this path. {@link com.bytequay.app.scheduler.GithubHomeCacheRefreshJob}
     * owns the writes; this method 404s until the first scheduler tick lands a
     * row, and the home page already handles that as "profile not yet known".
     */
    public UserProfile getUserProfile()
    {
        Optional<String> login = settingsStore.get(AppSettingsStore.Key.GITHUB_LOGIN);
        if (login.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not yet cached");
        }
        return homeCache.findProfile(login.get())
                .map(GithubHomeCacheStore.TimedValue::value)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not yet cached"));
    }

    /**
     * Fetches the profile from GitHub (REST + the GraphQL Sponsors overlay)
     * and persists the merged result to the cache. Used by the scheduler on
     * its 2-minute tick and on profile-edit so the next read reflects the
     * change immediately.
     */
    public UserProfile refreshUserProfileFromGitHub(String pat)
    {
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
        homeCache.putProfile(fresh.login(), fresh, Instant.now());
        // Single-user app, but the rest of the cache (events, stats, orgs)
        // keys by login — record it so the scheduler and read-side endpoints
        // can resolve the active user without a second round-trip.
        settingsStore.set(AppSettingsStore.Key.GITHUB_LOGIN, fresh.login());
        return fresh;
    }

    public List<PullRequest> getRepoPullRequests(String pat, String owner, String repo)
    {
        RepoRef ref = RepoRef.of(owner, repo);
        // Cache the GitHub-derived list, but overlay viewState on every
        // read — that way "Mark handled" / snooze clicks reflect
        // immediately, without waiting for the 2-min TTL to expire.
        List<PullRequest> base = repoListCache.getPulls(ref,
                () -> gitHub.listPullRequests(pat, ref, ListPullRequestsQuery.openPullRequests()));
        Map<Long, PrViewState> stateByPrId = viewStateStore.findAll();
        return base.stream()
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

    /**
     * Title/body search for PRs in a single repo, across all states
     * (open + closed + merged). Used by the create-task PR linker so
     * users can find old or closed PRs that aren't in the capped
     * {@link #getRepoPullRequests} response — typing the linker only
     * surfaces the top page of recent open PRs without this fallback.
     *
     * @param query the user-typed search text. Wrapped into
     *              {@code repo:owner/repo type:pr in:title <query>}
     *              before being sent to GitHub's search API.
     */
    public List<PullRequest> searchRepoPullRequests(String pat, String owner, String repo, String query)
    {
        if (query == null || query.isBlank()) {
            return ImmutableList.of();
        }
        // GitHub's search query syntax: scope to the repo, type=pr,
        // restrict to title matches so a body word like "fix" doesn't
        // surface a hundred unrelated PRs. The user's text goes verbatim
        // after the qualifiers — multi-word queries are AND-ed by
        // default, which matches typical "type a memorable phrase" UX.
        String trimmed = query.trim();
        String searchQuery = "repo:" + owner + "/" + repo + " type:pr in:title " + trimmed;
        List<PullRequest> hits = gitHub.searchPullRequests(pat, searchQuery);
        Map<Long, PrViewState> stateByPrId = viewStateStore.findAll();
        return hits.stream()
                .map(pr -> overlayViewState(pr, stateByPrId.get(pr.id())))
                .collect(toImmutableList());
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
                pr.reviewerVerdicts(),
                state.snoozedUntil(),
                state.snoozeWakeReason(),
                pr.headRef());
    }

    public List<RepoIssue> getRepoIssues(String pat, String owner, String repo, String state)
    {
        RepoRef ref = RepoRef.of(owner, repo);
        String normalized = (state == null || state.isBlank()) ? "open" : state;
        return repoListCache.getIssues(ref, normalized, () -> gitHub.fetchRepoIssues(pat, ref, normalized));
    }

    /**
     * Loads one issue's detail payload — body, labels, assignees,
     * milestone, comments, and the structural timeline — for the
     * in-app detail page. Three upstream calls (issue + comments +
     * timeline) fired sequentially; could parallelise later if the
     * latency shows up in profiling. Not cached: detail pages are
     * visited one at a time, so a per-page TTL would just mask
     * staleness on revisit.
     */
    public IssueDetail getIssueDetail(String pat, String owner, String repo, int number)
    {
        RepoRef ref = RepoRef.of(owner, repo);
        IssueDetail base = gitHub.fetchIssueDetail(pat, ref, number);
        List<IssueDetail.Comment> comments = gitHub.fetchIssueDetailComments(pat, ref, number);
        List<IssueTimelineEvent> timeline = gitHub.fetchIssueTimeline(pat, ref, number);
        boolean subscribed = gitHub.fetchIssueSubscription(pat, ref, number);
        return new IssueDetail(
                base.id(),
                base.number(),
                base.title(),
                base.body(),
                base.author(),
                base.authorAvatarUrl(),
                base.state(),
                base.htmlUrl(),
                base.createdAt(),
                base.updatedAt(),
                base.closedAt(),
                base.labels(),
                base.assignees(),
                base.milestone(),
                comments,
                timeline,
                subscribed);
    }

    /**
     * Posts a comment on an issue. Returns the GitHub-side payload
     * normalised to {@link IssueDetail.Comment} so the UI can append
     * the row directly without a refetch.
     */
    public IssueDetail.Comment createIssueComment(String pat, String owner, String repo, int number, String body)
    {
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comment body must not be blank");
        }
        return gitHub.postIssueComment(pat, RepoRef.of(owner, repo), number, body);
    }

    /**
     * Toggles an issue's state. Returns the post-flip detail with the
     * existing comment list spliced back in — GitHubClient drops the
     * comments to keep its method one-fetch-per-call, so we re-attach
     * here using the cached fetch path. The frontend uses the result
     * to update both detail.state and any derived UI (close
     * timestamp, status pill).
     */
    public IssueDetail setIssueState(String pat, String owner, String repo, int number, String state)
    {
        if (!"open".equals(state) && !"closed".equals(state)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "state must be \"open\" or \"closed\"; got: " + state);
        }
        RepoRef ref = RepoRef.of(owner, repo);
        IssueDetail flipped = gitHub.setIssueState(pat, ref, number, state);
        // Re-fetch comments + timeline so the close/reopen event GitHub
        // emits and any closedAt timestamps surface without a second
        // round-trip from the frontend.
        List<IssueDetail.Comment> comments = gitHub.fetchIssueDetailComments(pat, ref, number);
        List<IssueTimelineEvent> timeline = gitHub.fetchIssueTimeline(pat, ref, number);
        boolean subscribed = gitHub.fetchIssueSubscription(pat, ref, number);
        return new IssueDetail(
                flipped.id(),
                flipped.number(),
                flipped.title(),
                flipped.body(),
                flipped.author(),
                flipped.authorAvatarUrl(),
                flipped.state(),
                flipped.htmlUrl(),
                flipped.createdAt(),
                flipped.updatedAt(),
                flipped.closedAt(),
                flipped.labels(),
                flipped.assignees(),
                flipped.milestone(),
                comments,
                timeline,
                subscribed);
    }

    /**
     * Flips the viewer's subscription on one issue. No new fetch on
     * return — the frontend patches its in-memory detail
     * optimistically, mirroring the reactions path.
     */
    public void setIssueSubscription(String pat, String owner, String repo, int number, boolean subscribe)
    {
        gitHub.setIssueSubscription(pat, RepoRef.of(owner, repo), number, subscribe);
    }

    /**
     * Adds an emoji reaction to an issue comment. Mirrors the PR-side
     * {@code addIssueCommentReaction} flow but doesn't patch any cached
     * detail row — issue detail isn't cached server-side, so the next
     * {@link #getIssueDetail} call surfaces the new tally directly from
     * GitHub. {@code content} must be one of the eight allowlisted
     * GitHub reaction strings (validated by the underlying client).
     */
    public void addIssueCommentReaction(String pat, String owner, String repo, long commentId, String content)
    {
        if (!ALLOWED_REACTION_CONTENT.contains(content)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "reaction content must be one of " + ALLOWED_REACTION_CONTENT);
        }
        gitHub.addIssueCommentReaction(pat, owner, repo, commentId, content);
    }

    /**
     * Returns the cached {@link RepoMeta} for one repo. Stale-while-
     * revalidate: a row that's at most one hour old AND complete is
     * returned immediately; an older row is returned immediately and a
     * background refresh runs on the IO executor; an absent row, or one
     * persisted before V44 added {@code owner_avatar_url}, triggers a
     * synchronous fetch.
     */
    public RepoMeta getRepoMeta(String pat, String owner, String repo)
    {
        Optional<StoredRepoMeta> stored = repoMetaStore.find(owner, repo);
        Instant now = Instant.now();
        if (stored.isPresent()) {
            StoredRepoMeta s = stored.get();
            // GitHub always returns owner.avatar_url, so a null here means
            // the row was persisted before V44 added the column. Treat
            // that as a synchronous-refresh signal so the caller paints
            // the real avatar on the very next mount instead of waiting
            // for the TTL to fire.
            boolean complete = s.meta().ownerAvatarUrl() != null;
            boolean fresh = !s.syncedAt().isBefore(now.minus(REPO_META_TTL));
            if (complete && fresh) {
                return s.meta();
            }
            if (complete) {
                ioExecutor.execute(() -> refreshRepoMeta(pat, owner, repo));
                return s.meta();
            }
        }
        RepoMeta fresh = gitHub.fetchRepoMeta(pat, RepoRef.of(owner, repo));
        repoMetaStore.save(owner, repo, fresh, now);
        return fresh;
    }

    private void refreshRepoMeta(String pat, String owner, String repo)
    {
        try {
            RepoMeta fresh = gitHub.fetchRepoMeta(pat, RepoRef.of(owner, repo));
            repoMetaStore.save(owner, repo, fresh, Instant.now());
        }
        catch (Exception e) {
            log.warn("Background repo-meta refresh for {}/{} failed: {}", owner, repo, e.getMessage());
        }
    }

    public List<RepoActivityItem> getRepoActivity(String pat, String owner, String repo)
    {
        RepoRef ref = RepoRef.of(owner, repo);
        return repoListCache.getActivity(ref, () -> gitHub.fetchRepoActivity(pat, ref));
    }

    public List<UserRepo> getUserRepos(String pat)
    {
        return gitHub.fetchUserRepos(pat);
    }

    /**
     * Reads the cached org list from the local DB. The scheduler refreshes
     * this row every 30 days; reads always return whatever's there, falling
     * back to an empty list if the first refresh hasn't landed yet.
     */
    public List<UserOrg> getUserOrgs()
    {
        Optional<String> login = settingsStore.get(AppSettingsStore.Key.GITHUB_LOGIN);
        if (login.isEmpty()) {
            return ImmutableList.of();
        }
        return homeCache.findOrgs(login.get())
                .map(GithubHomeCacheStore.TimedValue::value)
                .orElse(ImmutableList.of());
    }

    /**
     * Fetches {@code /user/orgs} and persists the result. {@code read:org}
     * scope is optional on the PAT — without it GitHub returns 403/404 and
     * we cache an empty list (the home-page org strip simply hides).
     */
    public List<UserOrg> refreshUserOrgsFromGitHub(String pat, String login)
    {
        List<UserOrg> fresh;
        try {
            fresh = ImmutableList.copyOf(gitHub.fetchUserOrgs(pat));
        }
        catch (ResponseStatusException e) {
            int status = e.getStatusCode().value();
            if (status == 403 || status == 404) {
                log.warn("refreshUserOrgs: GitHub returned {} (likely missing read:org scope) — caching empty list", status);
                fresh = ImmutableList.of();
            }
            else {
                throw e;
            }
        }
        homeCache.putOrgs(login, fresh, Instant.now());
        return fresh;
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

    /**
     * Reads the cached recent-activity feed from the local DB. The scheduler
     * refreshes this row every 2 minutes; reads return whatever's there, or
     * an empty list before the first refresh.
     */
    public List<RecentEvent> getRecentEvents(String login)
    {
        return homeCache.findEvents(login, EventFeed.RECENT)
                .map(GithubHomeCacheStore.TimedValue::value)
                .orElse(ImmutableList.of());
    }

    public List<RecentEvent> getFollowingEvents(String login)
    {
        return homeCache.findEvents(login, EventFeed.FOLLOWING)
                .map(GithubHomeCacheStore.TimedValue::value)
                .orElse(ImmutableList.of());
    }

    /**
     * Fetches {@code /users/{login}/events}, filters to the current month
     * (matches the home page's "this month" framing), and persists.
     */
    public List<RecentEvent> refreshRecentEventsFromGitHub(String pat, String login)
    {
        Instant monthStart = ZonedDateTime.now(ZoneOffset.UTC)
                .toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<RecentEvent> fresh = gitHub.fetchUserEvents(pat, login, 100).stream()
                .filter(e -> e.createdAt() != null && !e.createdAt().isBefore(monthStart))
                .collect(toImmutableList());
        homeCache.putEvents(login, EventFeed.RECENT, fresh, Instant.now());
        return fresh;
    }

    /**
     * Fetches {@code /users/{login}/received_events} and trims to the most
     * recent 10 (matches the home-page "Following" card).
     */
    public List<RecentEvent> refreshFollowingEventsFromGitHub(String pat, String login)
    {
        List<RecentEvent> all = gitHub.fetchReceivedEvents(pat, login, 30);
        List<RecentEvent> fresh = ImmutableList.copyOf(all.size() <= 10 ? all : all.subList(0, 10));
        homeCache.putEvents(login, EventFeed.FOLLOWING, fresh, Instant.now());
        return fresh;
    }

    public UserProfile updateUserProfile(String pat, String name, String bio, String location)
    {
        gitHub.updateUserProfile(pat, name, bio, location);
        // Re-fetch + persist via the same path the scheduler uses so the
        // hasSponsors overlay is re-applied and the cache reflects the edit
        // on the very next read.
        return refreshUserProfileFromGitHub(pat);
    }
}
