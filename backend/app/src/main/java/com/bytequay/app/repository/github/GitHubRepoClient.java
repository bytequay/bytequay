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
package com.bytequay.app.repository.github;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.app.repository.github.CommitAvatarSnapshot.CommitAvatar;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryLabel;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static com.bytequay.app.repository.github.GitHubApiSupport.get;
import static com.bytequay.app.repository.github.GitHubApiSupport.issueTypeName;
import static com.bytequay.app.repository.github.GitHubApiSupport.requireBody;
import static com.bytequay.app.repository.github.GitHubApiSupport.translating;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

@Component
public class GitHubRepoClient
{
    private static final String ISSUE_TYPES_API_VERSION = "2026-03-10";

    private final RestClient gitHubRestClient;
    private final GitHubPaginator paginator;

    public GitHubRepoClient(RestClient gitHubRestClient)
    {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
        this.paginator = new GitHubPaginator(gitHubRestClient);
    }

    public List<RepositoryContributor> fetchContributors(String pat, RepoRef repo)
    {
        return paginator.paginate(
                        pat,
                        "/repos/{owner}/{repo}/contributors",
                        new ParameterizedTypeReference<List<ContributorResponse>>() {},
                        repo.owner(),
                        repo.repo()).stream()
                .filter(value -> value.id() > 0)
                .filter(value -> value.login() != null && !value.login().isBlank())
                .map(value -> new RepositoryContributor(
                        value.id(),
                        value.login(),
                        Optional.ofNullable(value.avatarUrl()).orElse(""),
                        "Bot".equalsIgnoreCase(value.type()) || value.login().endsWith("[bot]")))
                .collect(toImmutableList());
    }

    /** Returns a named avatar scan so synchronization can distinguish an empty branch from provider failure. */
    public CommitAvatarSnapshot fetchCommitAvatarSnapshot(String pat, RepoRef repo, String ref, int limit)
    {
        requireNonNull(repo, "repo is null");
        requireNonNull(ref, "ref is null");
        checkArgument(!ref.isBlank(), "ref is blank");
        checkArgument(limit > 0, "limit is not positive");
        ImmutableList.Builder<CommitAvatar> avatars = ImmutableList.builder();
        int pageSize = Math.min(limit, 100);
        int remaining = limit;
        try {
            for (int page = 1; remaining > 0; page++) {
                int currentPage = page;
                List<CommitResponse> commits = get(gitHubRestClient, pat, builder -> builder.path("/repos/{owner}/{repo}/commits")
                        .queryParam("sha", ref)
                        .queryParam("per_page", pageSize)
                        .queryParam("page", currentPage)
                        .build(repo.owner(), repo.repo()))
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});
                if (commits == null || commits.isEmpty()) {
                    break;
                }
                commits.stream()
                        .limit(remaining)
                        .filter(commit -> commit.sha() != null && !commit.sha().isBlank())
                        .filter(commit -> commit.author() != null || commit.committer() != null)
                        .map(commit -> new CommitAvatar(
                                commit.sha(),
                                accountValue(commit.author(), CommitAuthor::login),
                                accountValue(commit.author(), CommitAuthor::avatarUrl),
                                accountValue(commit.committer(), CommitAuthor::login),
                                accountValue(commit.committer(), CommitAuthor::avatarUrl)))
                        .forEach(avatars::add);
                remaining -= commits.size();
                if (commits.size() < pageSize) {
                    break;
                }
            }
            return new CommitAvatarSnapshot(avatars.build(), true);
        }
        catch (RestClientException ignored) {
            return new CommitAvatarSnapshot(ImmutableList.of(), false);
        }
    }

    public List<RepositoryLabel> fetchLabels(String pat, RepoRef repo)
    {
        return paginator.paginate(
                        pat,
                        "/repos/{owner}/{repo}/labels",
                        new ParameterizedTypeReference<List<LabelResponse>>() {},
                        repo.owner(),
                        repo.repo()).stream()
                .filter(value -> value.id() > 0)
                .filter(value -> value.name() != null && !value.name().isBlank())
                .map(value -> new RepositoryLabel(
                        value.id(), value.name(), Optional.ofNullable(value.color()).orElse("")))
                .collect(toImmutableList());
    }

    public RepositoryOverview fetchOverview(String pat, RepoRef repo)
    {
        return translating(() -> {
            GitHubRepoResponse response = requireBody(get(gitHubRestClient, pat, "/repos/{owner}/{repo}", repo.owner(), repo.repo())
                    .retrieve()
                    .body(GitHubRepoResponse.class), "Empty response from GitHub /repos");
            return new RepositoryOverview(
                    response.description(),
                    response.stargazersCount(),
                    Optional.ofNullable(response.topics()).orElse(ImmutableList.of()),
                    fetchLanguages(pat, repo));
        });
    }

    public IssuePage fetchOpenIssuesPage(String pat, RepoRef repo, int page)
    {
        checkArgument(page > 0, "page must be positive");
        List<IssueResponse> response = paginator.fetchPage(
                pat,
                "/repos/{owner}/{repo}/issues",
                new ParameterizedTypeReference<List<IssueResponse>>() {},
                null,
                page,
                repo.owner(),
                repo.repo());
        List<RepositoryIssue> issues = response.stream()
                .filter(value -> value.pullRequest() == null)
                .filter(value -> value.id() > 0 && value.number() > 0)
                .filter(value -> value.title() != null && !value.title().isBlank())
                .filter(value -> value.createdAt() != null && value.updatedAt() != null)
                .map(value -> new RepositoryIssue(
                        value.id(),
                        value.number(),
                        value.title(),
                        Optional.ofNullable(value.body()).orElse(""),
                        Optional.ofNullable(value.user()).map(IssueUser::login).orElse("unknown"),
                        Optional.ofNullable(value.user()).map(IssueUser::avatarUrl).orElse(""),
                        Optional.ofNullable(value.htmlUrl()).orElse(""),
                        issueLabels(value),
                        Optional.ofNullable(value.assignees()).orElse(ImmutableList.of()).stream()
                                .map(IssueUser::login)
                                .filter(Objects::nonNull)
                                .collect(toImmutableList()),
                        Math.max(0, value.comments()),
                        value.createdAt(),
                        value.updatedAt()))
                .collect(toImmutableList());
        return new IssuePage(issues, response.size() == GitHubPaginator.PAGE_SIZE);
    }

    public GitHubIssueSource fetchOpenIssueSource(String pat, RepoRef repo, int issueNumber)
    {
        checkArgument(issueNumber > 0, "issueNumber must be positive");
        return translating(() -> {
            IssueResponse issue = requireBody(get(gitHubRestClient, pat, "/repos/{owner}/{repo}/issues/{number}", repo.owner(), repo.repo(), issueNumber)
                    .retrieve().body(IssueResponse.class), "Empty issue response from GitHub");
            if (!"open".equals(issue.state()) || issue.pullRequest() != null || issue.number() != issueNumber) {
                throw new AgentConflictException("This source is not an open issue. Refresh issues before starting development.");
            }
            return new GitHubIssueSource("github.com", issue.id(), repo.fullName(), issueNumber,
                    "https://github.com/" + repo.fullName() + "/issues/" + issueNumber,
                    issue.title(), Optional.ofNullable(issue.body()).orElse(""), issue.updatedAt());
        });
    }

    public GitHubIssuePreview fetchIssuePreview(String pat, RepoRef repo, int issueNumber)
    {
        checkArgument(issueNumber > 0, "issueNumber must be positive");
        return translating(() -> {
            IssueResponse issue = Optional.ofNullable(get(gitHubRestClient, pat, "/repos/{owner}/{repo}/issues/{number}", repo.owner(), repo.repo(), issueNumber)
                            .retrieve()
                            .body(IssueResponse.class))
                    .filter(value -> value.title() != null && value.updatedAt() != null)
                    .filter(value -> "open".equals(value.state()) || "closed".equals(value.state()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatusCode.valueOf(502), "Incomplete issue response from GitHub"));
            return new GitHubIssuePreview(
                    issue.title(),
                    Optional.ofNullable(issue.body()).orElse(""),
                    Optional.ofNullable(issue.user()).map(IssueUser::login).orElse("unknown"),
                    issueLabels(issue),
                    issue.updatedAt(),
                    issue.state(),
                    issue.pullRequest() != null);
        });
    }

    /** One explicit bounded search; fixed repository/state qualifiers cannot be replaced by caller search syntax. */
    public GitHubSearchResponse searchHistory(String pat, RepoRef repo, boolean pullRequests, String plainTerms)
    {
        checkArgument(pat != null && !pat.isBlank(), "GitHub credential is required");
        requireNonNull(repo, "repo is null");
        requireNonNull(plainTerms, "plainTerms is null");
        checkArgument(plainTerms.getBytes(UTF_8).length <= 200 && plainTerms.matches("[\\p{L}\\p{N} _./-]*"),
                "History query must contain at most 200 UTF-8 bytes of plain words");
        String words = Arrays.stream(plainTerms.strip().split(" +")).filter(word -> !word.isEmpty()).map(word -> "\"" + word + "\"").collect(joining(" "));
        String query = "repo:" + repo.fullName() + (pullRequests ? " is:pr is:merged" : " is:issue is:closed") + " in:title,body"
                + (words.isEmpty() ? "" : " " + words);
        return translating(() -> requireBody(get(gitHubRestClient, pat, uri -> uri.path("/search/issues").queryParam("q", "{query}")
                        .queryParam("sort", "updated").queryParam("order", "desc").queryParam("per_page", 20).queryParam("page", 1).build(query))
                .retrieve().body(GitHubSearchResponse.class), "Missing history search response from GitHub"));
    }

    private static List<String> issueLabels(IssueResponse issue)
    {
        return Optional.ofNullable(issue.labels()).orElse(ImmutableList.of()).stream()
                .map(IssueLabel::name)
                .filter(Objects::nonNull)
                .collect(toImmutableList());
    }

    public String fetchIssueType(String pat, RepoRef repo, int issueNumber)
    {
        checkArgument(issueNumber > 0, "issueNumber must be positive");
        return translating(() -> {
            IssueResponse issue = requireBody(get(gitHubRestClient, pat, "/repos/{owner}/{repo}/issues/{number}", repo.owner(), repo.repo(), issueNumber)
                    .headers(headers -> headers.set("X-GitHub-Api-Version", ISSUE_TYPES_API_VERSION))
                    .retrieve()
                    .body(IssueResponse.class), "Empty issue response from GitHub");
            return issueTypeName(issue.type());
        });
    }

    public List<String> fetchIssueTypes(String pat, RepoRef repo)
    {
        return translating(() -> {
            List<IssueTypeResponse> types = get(gitHubRestClient, pat, "/repos/{owner}/{repo}/issue-types", repo.owner(), repo.repo())
                    .headers(headers -> headers.set("X-GitHub-Api-Version", ISSUE_TYPES_API_VERSION))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return Optional.ofNullable(types).orElse(ImmutableList.of()).stream()
                    .map(IssueTypeResponse::name)
                    .filter(Objects::nonNull)
                    .filter(name -> !name.isBlank())
                    .distinct()
                    .collect(toImmutableList());
        });
    }

    private Map<String, Long> fetchLanguages(String pat, RepoRef repo)
    {
        try {
            Map<String, Long> languages = get(gitHubRestClient, pat, "/repos/{owner}/{repo}/languages", repo.owner(), repo.repo())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return languages == null ? ImmutableMap.of() : languages;
        }
        catch (RestClientResponseException ignored) {
            return ImmutableMap.of();
        }
    }

    private static Optional<String> accountValue(CommitAuthor account, Function<CommitAuthor, String> value)
    {
        return Optional.ofNullable(account)
                .map(value)
                .filter(result -> !result.isBlank());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ContributorResponse(
            long id,
            String login,
            @JsonProperty("avatar_url") String avatarUrl,
            String type) {}

    public record IssuePage(List<RepositoryIssue> issues, boolean hasNext)
    {
        public IssuePage
        {
            issues = ImmutableList.copyOf(requireNonNull(issues, "issues is null"));
        }
    }

    public record RepositoryOverview(
            String description,
            int stargazersCount,
            List<String> topics,
            Map<String, Long> languages)
    {
        public RepositoryOverview
        {
            checkArgument(stargazersCount >= 0, "stargazersCount is negative");
            topics = ImmutableList.copyOf(requireNonNull(topics, "topics is null"));
            languages = ImmutableMap.copyOf(requireNonNull(languages, "languages is null"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LabelResponse(long id, String name, String color) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IssueResponse(
            long id,
            int number,
            String title,
            String body,
            String state,
            IssueUser user,
            @JsonProperty("html_url") String htmlUrl,
            List<IssueLabel> labels,
            List<IssueUser> assignees,
            int comments,
            @JsonProperty("created_at") java.time.Instant createdAt,
            @JsonProperty("updated_at") java.time.Instant updatedAt,
            @JsonProperty("pull_request") Map<String, Object> pullRequest,
            JsonNode type) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IssueTypeResponse(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IssueUser(
            String login,
            @JsonProperty("avatar_url") String avatarUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IssueLabel(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CommitResponse(String sha, CommitAuthor author, CommitAuthor committer) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CommitAuthor(String login, @JsonProperty("avatar_url") String avatarUrl) {}
}
