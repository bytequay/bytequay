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

import com.bytequay.app.repository.github.GitHubIssuePreview;
import com.bytequay.app.service.RepoService;
import com.bytequay.app.workspace.RepositorySearchResult;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.ContributionCalendar;
import com.bytequay.core.domain.RecentEvent;
import com.bytequay.core.domain.UserCommitSummary;
import com.bytequay.core.domain.UserProfile;
import com.google.common.collect.ImmutableList;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api")
public class RepoController
{
    private final RepoService repos;
    private final WorkspaceRepositoryService workspaces;

    public RepoController(
            RepoService repos,
            WorkspaceRepositoryService workspaces)
    {
        this.repos = requireNonNull(repos, "repos is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
    }

    @GetMapping("/profile")
    public UserProfile getProfile()
    {
        return repos.getUserProfile();
    }

    @GetMapping("/profile/{login}")
    public UserProfile getProfile(@PathVariable String login)
    {
        return repos.getUserProfile(login);
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

    @GetMapping("/repos/{owner}/{repo}/issues/{number}/preview")
    public GitHubIssuePreview getIssuePreview(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable int number)
    {
        return repos.getIssuePreview(owner, repo, number);
    }

    @GetMapping("/repos/{owner}/{repo}/code-snippet")
    public CodeSnippet getCodeSnippet(
            @PathVariable String owner,
            @PathVariable String repo,
            @RequestParam String commit,
            @RequestParam String path,
            @RequestParam int startLine,
            @RequestParam int endLine)
    {
        List<String> content = repos.getCodeSnippet(owner, repo, commit, path, startLine, endLine);
        ImmutableList.Builder<CodeLine> lines = ImmutableList.builderWithExpectedSize(content.size());
        for (int index = 0; index < content.size(); index++) {
            lines.add(new CodeLine(startLine + index, content.get(index)));
        }
        return new CodeSnippet(lines.build());
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

    public record CodeSnippet(List<CodeLine> lines)
    {
        public CodeSnippet
        {
            lines = ImmutableList.copyOf(requireNonNull(lines, "lines is null"));
        }
    }

    public record CodeLine(int number, String content)
    {
        public CodeLine
        {
            requireNonNull(content, "content is null");
        }
    }
}
