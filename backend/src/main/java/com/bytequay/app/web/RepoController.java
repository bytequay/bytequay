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
package com.bytequay.app.web;

import com.bytequay.app.domain.ContributionCalendar;
import com.bytequay.app.domain.GitHubUserMatch;
import com.bytequay.app.domain.IssueDetail;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.RecentEvent;
import com.bytequay.app.domain.RepoActivityItem;
import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.domain.RepoMeta;
import com.bytequay.app.domain.UserCommitSummary;
import com.bytequay.app.domain.UserOrg;
import com.bytequay.app.domain.UserProfile;
import com.bytequay.app.domain.UserRepo;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.service.RepoService;
import com.google.common.collect.ImmutableMap;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api")
public class RepoController
{
    private final RepoService repoService;
    private final PatResolver patResolver;

    public RepoController(RepoService repoService, PatResolver patResolver)
    {
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.repoService = requireNonNull(repoService, "repoService is null");
    }

    /**
     * Lists all watched repos in display order.
     * GET /api/repos
     */
    @GetMapping("/repos")
    public List<WatchedRepo> listRepos()
    {
        return repoService.listWatchedRepos();
    }

    /**
     * Adds a repo to the watched list.
     * POST /api/repos body: {"owner":"...", "repo":"..."}
     */
    @PostMapping("/repos")
    public WatchedRepo addRepo(@RequestBody AddRepoRequest request)
    {
        if (request == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "body is required");
        }
        if (request.owner() == null || request.owner().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "owner is required");
        }
        if (request.repo() == null || request.repo().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "repo is required");
        }
        return repoService.addWatchedRepo(request.owner(), request.repo());
    }

    /**
     * Removes a repo from the watched list.
     * DELETE /api/repos/{owner}/{repo}
     */
    @DeleteMapping("/repos/{owner}/{repo}")
    public Map<String, String> removeRepo(
            @PathVariable String owner,
            @PathVariable String repo)
    {
        repoService.removeWatchedRepo(owner, repo);
        return ImmutableMap.of("result", "removed");
    }

    /**
     * Returns the cached profile from the local DB. The scheduler refreshes
     * this row on a 2-minute tick; first launch returns 404 until the first
     * tick lands.
     * GET /api/profile
     */
    @GetMapping("/profile")
    public UserProfile getProfile()
    {
        return repoService.getUserProfile();
    }

    /**
     * Last-12-months contribution heatmap for one user — drives the
     * home page's "Your year in code" card.
     * GET /api/contribution-graph?login=...
     */
    @GetMapping("/contribution-graph")
    public ContributionCalendar getContributionGraph(@RequestParam("login") String login)
    {
        return repoService.getContributionCalendar(patResolver.resolve(), login);
    }

    /**
     * Commits authored by {@code login} on one UTC calendar day, used
     * by the heatmap-cube popover to unfold a count into the actual
     * commits. {@code date} is {@code yyyy-MM-dd}.
     * GET /api/contribution-graph/day?login=...&date=YYYY-MM-DD
     */
    @GetMapping("/contribution-graph/day")
    public List<UserCommitSummary> getContributionGraphDay(
            @RequestParam("login") String login,
            @RequestParam("date") String date)
    {
        return repoService.getUserCommitsOnDate(patResolver.resolve(), login, date);
    }

    /**
     * Lists open pull requests for a watched repo. Requires a Bearer PAT.
     * GET /api/repos/{owner}/{repo}/pulls
     */
    @GetMapping("/repos/{owner}/{repo}/pulls")
    public List<PullRequest> getRepoPulls(
            @PathVariable String owner,
            @PathVariable String repo)
    {
        return repoService.getRepoPullRequests(patResolver.resolve(owner + "/" + repo), owner, repo);
    }

    /**
     * Single-PR fetch — used by the deep-link fallback when a PR isn't in
     * the (capped) repo list response. Same shape as one item from
     * {@link #getRepoPulls}.
     * GET /api/repos/{owner}/{repo}/pulls/{number}
     */
    @GetMapping("/repos/{owner}/{repo}/pulls/{number}")
    public PullRequest getRepoPull(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable int number)
    {
        return repoService.getRepoPullRequest(patResolver.resolve(owner + "/" + repo), owner, repo, number);
    }

    /**
     * Title search for PRs in a single repo, all states. Powers the
     * create-thread linker's text-search fallback so a user can find an
     * old or closed PR that isn't in the 30 most-recent open PRs
     * returned by {@link #getRepoPulls}. Empty {@code q} returns an
     * empty list rather than running an unscoped search.
     * GET /api/repos/{owner}/{repo}/pulls/search?q=...
     */
    @GetMapping("/repos/{owner}/{repo}/pulls/search")
    public List<PullRequest> searchRepoPulls(
            @PathVariable String owner,
            @PathVariable String repo,
            @RequestParam(name = "q", required = false) String query)
    {
        return repoService.searchRepoPullRequests(
                patResolver.resolve(owner + "/" + repo), owner, repo, query);
    }

    /**
     * Lists issues (not PRs) for a watched repo. {@code state} maps
     * directly to GitHub's filter — typically "open" (default) or
     * "closed". Requires a Bearer PAT.
     * GET /api/repos/{owner}/{repo}/issues?state=open
     */
    @GetMapping("/repos/{owner}/{repo}/issues")
    public List<RepoIssue> getRepoIssues(
            @PathVariable String owner,
            @PathVariable String repo,
            @RequestParam(name = "state", defaultValue = "open") String state)
    {
        return repoService.getRepoIssues(patResolver.resolve(owner + "/" + repo), owner, repo, state);
    }

    /**
     * Single-issue detail payload for the in-app issue page — body,
     * labels, assignees, milestone, and the conversation comments.
     * GET /api/repos/{owner}/{repo}/issues/{number}/detail
     */
    @GetMapping("/repos/{owner}/{repo}/issues/{number}/detail")
    public IssueDetail getIssueDetail(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable int number)
    {
        return repoService.getIssueDetail(patResolver.resolve(owner + "/" + repo), owner, repo, number);
    }

    /**
     * Posts a comment on one issue. Returns the new comment so the
     * in-app issue page can append it without a refetch round-trip.
     * POST /api/repos/{owner}/{repo}/issues/{number}/comments  body:{body}
     */
    @PostMapping("/repos/{owner}/{repo}/issues/{number}/comments")
    public IssueDetail.Comment createIssueComment(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable int number,
            @RequestBody IssueCommentRequest request)
    {
        if (request == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "body is required");
        }
        if (request.body() == null || request.body().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "body must not be blank");
        }
        return repoService.createIssueComment(
                patResolver.resolve(owner + "/" + repo), owner, repo, number, request.body());
    }

    /** POST body shape for {@link #createIssueComment}. */
    public record IssueCommentRequest(String body) {}

    /**
     * Closes or reopens an issue. {@code state} must be "open" or
     * "closed". Returns the refreshed detail so the page can flip
     * its status pill and refresh comments without a second
     * round-trip.
     * PATCH /api/repos/{owner}/{repo}/issues/{number}  body:{state}
     */
    @PatchMapping("/repos/{owner}/{repo}/issues/{number}")
    public IssueDetail setIssueState(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable int number,
            @RequestBody IssueStateRequest request)
    {
        if (request == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "body is required");
        }
        if (request.state() == null || request.state().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "state is required");
        }
        return repoService.setIssueState(
                patResolver.resolve(owner + "/" + repo), owner, repo, number, request.state());
    }

    /** PATCH body shape for {@link #setIssueState}. */
    public record IssueStateRequest(String state) {}

    /**
     * Adds an emoji reaction to an issue comment. Returns {@code result:
     * reacted} on success; idempotent on the GitHub side, so re-toggling
     * the same content from the same viewer is a no-op as far as the
     * count is concerned.
     * POST /api/repos/{owner}/{repo}/issues/comments/{commentId}/reactions  body:{content}
     */
    @PostMapping("/repos/{owner}/{repo}/issues/comments/{commentId}/reactions")
    public Map<String, String> addIssueCommentReaction(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable long commentId,
            @RequestBody IssueReactionRequest request)
    {
        if (request == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "body is required");
        }
        if (request.content() == null || request.content().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "content is required");
        }
        repoService.addIssueCommentReaction(
                patResolver.resolve(owner + "/" + repo), owner, repo, commentId, request.content());
        return ImmutableMap.of("result", "reacted");
    }

    /** POST body shape for {@link #addIssueCommentReaction}. */
    public record IssueReactionRequest(String content) {}

    /**
     * Toggles the viewer's subscription to an issue. {@code subscribed=true}
     * subscribes (PUT) and {@code subscribed=false} returns the row to
     * its default state (DELETE). Returns {@code result: subscribed}
     * or {@code result: unsubscribed} so the frontend can log the
     * action without re-parsing the request.
     * POST /api/repos/{owner}/{repo}/issues/{number}/subscription
     */
    @PostMapping("/repos/{owner}/{repo}/issues/{number}/subscription")
    public Map<String, String> setIssueSubscription(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable int number,
            @RequestBody IssueSubscriptionRequest request)
    {
        if (request == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "body is required");
        }
        repoService.setIssueSubscription(
                patResolver.resolve(owner + "/" + repo), owner, repo, number, request.subscribed());
        return ImmutableMap.of("result", request.subscribed() ? "subscribed" : "unsubscribed");
    }

    /** POST body shape for {@link #setIssueSubscription}. */
    public record IssueSubscriptionRequest(boolean subscribed) {}

    /**
     * Repo-level metadata: description, stars, forks, watchers, license,
     * topics, languages map. Powers the right-pane hero / About /
     * language bar on the repo detail page.
     * GET /api/repos/{owner}/{repo}/meta
     */
    @GetMapping("/repos/{owner}/{repo}/meta")
    public RepoMeta getRepoMeta(
            @PathVariable String owner,
            @PathVariable String repo)
    {
        return repoService.getRepoMeta(patResolver.resolve(owner + "/" + repo), owner, repo);
    }

    /**
     * Recent ~30 GitHub events for a repo (push / PR / issue / release …).
     * Powers the right-pane "Recent activity" timeline.
     * GET /api/repos/{owner}/{repo}/activity
     */
    @GetMapping("/repos/{owner}/{repo}/activity")
    public List<RepoActivityItem> getRepoActivity(
            @PathVariable String owner,
            @PathVariable String repo)
    {
        return repoService.getRepoActivity(patResolver.resolve(owner + "/" + repo), owner, repo);
    }

    /**
     * Lists GitHub repos the authenticated user owns or collaborates on. Requires a Bearer PAT.
     * GET /api/user/repos
     */
    @GetMapping("/user/repos")
    public List<UserRepo> getUserRepos()
    {
        return repoService.getUserRepos(patResolver.resolve());
    }

    /**
     * Returns the cached org list from the local DB. Refreshed every 30 days
     * by the scheduler; reads return an empty list until the first tick lands.
     * GET /api/user/orgs
     */
    @GetMapping("/user/orgs")
    public List<UserOrg> getUserOrgs()
    {
        return repoService.getUserOrgs();
    }

    /**
     * Returns the cached recent-activity feed (current month only) from the
     * local DB. Refreshed every 2 minutes by the scheduler.
     * GET /api/activity/recent?login={login}
     */
    @GetMapping("/activity/recent")
    public List<RecentEvent> getRecentActivity(@RequestParam("login") String login)
    {
        return repoService.getRecentEvents(login);
    }

    /**
     * Searches GitHub public repositories. Requires a Bearer PAT.
     * GET /api/search/repos?q={query}
     */
    @GetMapping("/search/repos")
    public List<UserRepo> searchRepos(@RequestParam("q") String q)
    {
        return repoService.searchRepos(patResolver.resolve(), q);
    }

    /**
     * Searches GitHub user accounts (excludes organisations). Used by the
     * team editor's autocomplete so members are picked instead of
     * hand-typed. Returns at most 10 matches; queries shorter than 2
     * characters short-circuit to an empty list.
     * GET /api/search/users?q={query}
     */
    @GetMapping("/search/users")
    public List<GitHubUserMatch> searchUsers(@RequestParam("q") String q)
    {
        return repoService.searchUsers(patResolver.resolve(), q);
    }

    /**
     * Returns the cached following feed from the local DB. Refreshed every 2
     * minutes by the scheduler; trimmed to the most recent 10 events.
     * GET /api/activity/following?login={login}
     */
    @GetMapping("/activity/following")
    public List<RecentEvent> getFollowingActivity(@RequestParam("login") String login)
    {
        return repoService.getFollowingEvents(login);
    }

    /**
     * Updates the authenticated user's GitHub profile.
     * PATCH /api/profile
     */
    @PatchMapping("/profile")
    public UserProfile updateProfile(@RequestBody UpdateProfileRequest request)
    {
        if (request == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "body is required");
        }
        return repoService.updateUserProfile(
                patResolver.resolve(),
                request.name(),
                request.bio(),
                request.location());
    }

    public record AddRepoRequest(String owner, String repo) {}

    public record UpdateProfileRequest(String name, String bio, String location) {}
}
