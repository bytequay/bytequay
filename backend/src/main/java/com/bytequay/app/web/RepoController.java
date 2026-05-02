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

import com.bytequay.app.domain.GitHubUserMatch;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.RecentEvent;
import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.domain.UserOrg;
import com.bytequay.app.domain.UserProfile;
import com.bytequay.app.domain.UserRepo;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.service.RepoService;
import com.google.common.collect.ImmutableMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
     * Fetches the authenticated user's GitHub profile. Requires a Bearer PAT.
     * GET /api/profile
     */
    @GetMapping("/profile")
    public UserProfile getProfile()
    {
        return repoService.getUserProfile(patResolver.resolve());
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
     * Lists open issues (not PRs) for a watched repo. Requires a Bearer PAT.
     * GET /api/repos/{owner}/{repo}/issues
     */
    @GetMapping("/repos/{owner}/{repo}/issues")
    public List<RepoIssue> getRepoIssues(
            @PathVariable String owner,
            @PathVariable String repo)
    {
        return repoService.getRepoIssues(patResolver.resolve(owner + "/" + repo), owner, repo);
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
     * Lists organisations the authenticated user is a member of. Used by the
     * home page profile card to show org affiliations.
     * GET /api/user/orgs
     */
    @GetMapping("/user/orgs")
    public List<UserOrg> getUserOrgs()
    {
        return repoService.getUserOrgs(patResolver.resolve());
    }

    /**
     * Returns recent GitHub events for a user filtered to the current month. Requires a Bearer PAT.
     * GET /api/activity/recent?login={login}
     */
    @GetMapping("/activity/recent")
    public List<RecentEvent> getRecentActivity(@RequestParam("login") String login)
    {
        return repoService.getRecentEvents(patResolver.resolve(), login);
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
     * Returns recent events from users the authenticated user follows, up to 10.
     * GET /api/activity/following?login={login}
     */
    @GetMapping("/activity/following")
    public List<RecentEvent> getFollowingActivity(@RequestParam("login") String login)
    {
        return repoService.getFollowingEvents(patResolver.resolve(), login);
    }

    /**
     * Updates the authenticated user's GitHub profile.
     * PATCH /api/profile
     */
    @PatchMapping("/profile")
    public UserProfile updateProfile(@RequestBody UpdateProfileRequest request)
    {
        return repoService.updateUserProfile(
                patResolver.resolve(),
                request.name(),
                request.bio(),
                request.location());
    }

    public record AddRepoRequest(String owner, String repo) {}

    public record UpdateProfileRequest(String name, String bio, String location) {}
}
