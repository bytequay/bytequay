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

import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.repository.github.GitHubIssuePreview;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.ContributionCalendar;
import com.bytequay.core.domain.RecentEvent;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserCommitSummary;
import com.bytequay.core.domain.UserProfile;
import com.google.common.collect.ImmutableList;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.regex.Pattern;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

@Service
public class RepoService
{
    private static final int MAX_CODE_SNIPPET_LINES = 20;
    private static final int MAX_CODE_PATH_LENGTH = 4096;
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern GITHUB_NAME = Pattern.compile("^[A-Za-z0-9_.-]+$");
    private static final Pattern COMMIT_SHA = Pattern.compile("^[0-9a-fA-F]{40}$");

    private final GitHubPullRequestReadClient pullRequests;
    private final GitHubAccountClient accounts;
    private final GitHubRepoClient repositories;
    private final CredentialService credentialService;

    public RepoService(GitHubPullRequestReadClient pullRequests, GitHubAccountClient accounts, GitHubRepoClient repositories, CredentialService credentialService)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.accounts = requireNonNull(accounts, "accounts is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
    }

    public ContributionCalendar getContributionCalendar(String login)
    {
        checkText(login, "login");
        try {
            return accounts.fetchContributionCalendar(credentialService.resolveGitHubToken(), login);
        }
        catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Contribution graph temporarily unavailable", e);
        }
    }

    public List<UserCommitSummary> getUserCommitsOnDate(String login, String isoDate)
    {
        checkText(login, "login");
        if (isoDate == null || !ISO_DATE.matcher(isoDate).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date must be yyyy-MM-dd");
        }
        return accounts.fetchUserCommitsOnDate(credentialService.resolveGitHubToken(), login, isoDate);
    }

    public UserProfile getUserProfile()
    {
        return credentialService.resolveGitHubCredential().profile();
    }

    public UserProfile getUserProfile(String login)
    {
        checkText(login, "login");
        return accounts.fetchUserProfile(credentialService.resolveGitHubToken(), login);
    }

    public GitHubIssuePreview getIssuePreview(String owner, String repo, int number)
    {
        checkText(owner, "owner");
        checkText(repo, "repo");
        if (!GITHUB_NAME.matcher(owner).matches() || !GITHUB_NAME.matcher(repo).matches() || number <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid GitHub issue reference");
        }
        return repositories.fetchIssuePreview(credentialService.resolveGitHubToken(), RepoRef.of(owner, repo), number);
    }

    public List<String> getCodeSnippet(String owner, String repo, String commit, String path, int startLine, int endLine)
    {
        checkText(owner, "owner");
        checkText(repo, "repo");
        checkText(path, "path");
        if (!GITHUB_NAME.matcher(owner).matches() || !GITHUB_NAME.matcher(repo).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid GitHub repository");
        }
        if (commit == null || !COMMIT_SHA.matcher(commit).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "commit must be a full SHA");
        }
        if (path.length() > MAX_CODE_PATH_LENGTH || path.startsWith("/") || path.indexOf('\0') >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid repository path");
        }
        if (startLine <= 0 || endLine < startLine
                || (long) endLine - startLine + 1 > MAX_CODE_SNIPPET_LINES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid code line range");
        }
        List<String> lines = pullRequests.fetchRepositoryFileLines(credentialService.resolveGitHubToken(), RepoRef.of(owner, repo), path, commit, startLine, endLine)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "repository file not found"));
        if (lines.size() != (long) endLine - startLine + 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "code line range not found");
        }
        return ImmutableList.copyOf(lines);
    }

    public List<RecentEvent> getFollowingEvents(String login)
    {
        checkText(login, "login");
        List<RecentEvent> all = accounts.fetchReceivedEvents(credentialService.resolveGitHubToken(), login, 30);
        return ImmutableList.copyOf(all.size() <= 10 ? all : all.subList(0, 10));
    }
}
