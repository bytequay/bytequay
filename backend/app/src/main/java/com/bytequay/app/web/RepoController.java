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

import com.bytequay.app.service.RepoService;
import com.bytequay.app.workspace.RepositorySearchResult;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.app.workspace.WorkspaceSummary;
import com.bytequay.core.domain.ContributionCalendar;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.RecentEvent;
import com.bytequay.core.domain.RepoMeta;
import com.bytequay.core.domain.UserCommitSummary;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.domain.Workspace;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api")
public class RepoController
{
    private final RepoService repos;
    private final WorkspaceRepositoryService workspaces;

    public RepoController(RepoService repos, WorkspaceRepositoryService workspaces)
    {
        this.repos = requireNonNull(repos, "repos is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
    }

    @GetMapping("/repos")
    public List<WatchedRepoView> listRepos()
    {
        return workspaces.listWorkspaces().stream().map(WatchedRepoView::of).collect(toImmutableList());
    }

    @DeleteMapping("/repos/{owner}/{repo}")
    public Map<String, String> removeRepo(@PathVariable String owner, @PathVariable String repo)
    {
        workspaces.listWorkspaces().stream()
                .filter(workspace -> workspace.selectedOwner().equalsIgnoreCase(owner)
                        && workspace.selectedRepo().equalsIgnoreCase(repo))
                .findFirst()
                .ifPresent(workspace -> workspaces.unwatch(workspace.id()));
        return Map.of("result", "removed");
    }

    @GetMapping("/profile")
    public UserProfile getProfile()
    {
        return repos.getUserProfile();
    }

    @GetMapping("/contribution-graph")
    public ContributionCalendar getContributionGraph(@RequestParam("login") String login)
    {
        return repos.getContributionCalendar(login);
    }

    @GetMapping("/contribution-graph/day")
    public List<UserCommitSummary> getContributionGraphDay(
            @RequestParam("login") String login,
            @RequestParam("date") String date)
    {
        return repos.getUserCommitsOnDate(login, date);
    }

    @GetMapping("/repos/{owner}/{repo}/pulls/{number}")
    public PullRequest getRepoPull(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable int number)
    {
        return repos.getRepoPullRequest(owner, repo, number);
    }

    @GetMapping("/repos/{owner}/{repo}/meta")
    public RepoMeta getRepoMeta(@PathVariable String owner, @PathVariable String repo)
    {
        return repos.getRepoMeta(owner, repo);
    }

    @GetMapping("/repository-search")
    public List<RepositorySearchResult> searchRepositories(
            @RequestParam(value = "q", defaultValue = "") String query,
            @RequestParam(value = "limit", defaultValue = "30") int limit)
    {
        return workspaces.searchRepositories(query, limit);
    }

    @GetMapping("/activity/recent")
    public List<RecentEvent> getRecentActivity(@RequestParam("login") String login)
    {
        return repos.getRecentEvents(login);
    }

    @GetMapping("/activity/following")
    public List<RecentEvent> getFollowingActivity(@RequestParam("login") String login)
    {
        return repos.getFollowingEvents(login);
    }

    public record WatchedRepoView(String id, String owner, String repo, int displayOrder)
    {
        static WatchedRepoView of(WorkspaceSummary workspace)
        {
            return new WatchedRepoView(
                    workspace.id(),
                    workspace.selectedOwner(),
                    workspace.selectedRepo(),
                    workspace.displayOrder());
        }

        static WatchedRepoView of(Workspace workspace)
        {
            return of(WorkspaceSummary.of(workspace));
        }
    }
}
