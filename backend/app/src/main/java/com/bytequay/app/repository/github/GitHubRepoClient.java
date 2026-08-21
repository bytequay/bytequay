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

import com.bytequay.core.domain.RepoMeta;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryLabel;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static com.bytequay.app.repository.github.GitHubApiSupport.toReadableException;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubRepoClient
{
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
                .toList();
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
                .toList();
    }

    public List<String> fetchTopics(String pat, RepoRef repo)
    {
        try {
            GitHubRepoResponse response = gitHubRestClient
                    .get()
                    .uri("/repos/{owner}/{repo}", repo.owner(), repo.repo())
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(GitHubRepoResponse.class);
            if (response == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502), "Empty response from GitHub /repos");
            }
            return List.copyOf(Optional.ofNullable(response.topics()).orElse(List.of()));
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    public List<RepositoryIssue> fetchOpenIssues(String pat, RepoRef repo)
    {
        return paginator.paginate(
                        pat,
                        "/repos/{owner}/{repo}/issues",
                        new ParameterizedTypeReference<List<IssueResponse>>() {},
                        repo.owner(),
                        repo.repo()).stream()
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
                        Optional.ofNullable(value.labels()).orElse(List.of()).stream()
                                .map(IssueLabel::name)
                                .filter(Objects::nonNull)
                                .toList(),
                        Optional.ofNullable(value.assignees()).orElse(List.of()).stream()
                                .map(IssueUser::login)
                                .filter(Objects::nonNull)
                                .toList(),
                        Math.max(0, value.comments()),
                        value.createdAt(),
                        value.updatedAt()))
                .toList();
    }

    public RepoMeta fetchRepoMeta(String pat, RepoRef repo)
    {
        try {
            GitHubRepoResponse response = gitHubRestClient
                    .get()
                    .uri("/repos/{owner}/{repo}", repo.owner(), repo.repo())
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(GitHubRepoResponse.class);
            if (response == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502), "Empty response from GitHub /repos");
            }

            Map<String, Long> languages = fetchLanguages(pat, repo);
            String licenseName = Optional.ofNullable(response.license())
                    .map(GitHubRepoResponse.License::spdxId)
                    .orElseGet(() -> Optional.ofNullable(response.license())
                            .map(GitHubRepoResponse.License::name)
                            .orElse(null));
            GitHubRepoResponse.Parent parent = response.parent();
            String parentOwner = parent == null ? null : Optional.ofNullable(parent.owner())
                    .map(GitHubRepoResponse.Owner::login)
                    .orElse(null);

            return new RepoMeta(
                    response.fullName(),
                    response.htmlUrl(),
                    response.description(),
                    response.defaultBranch(),
                    licenseName,
                    response.stargazersCount(),
                    response.forksCount(),
                    response.subscribersCount(),
                    response.openIssuesCount(),
                    response.size(),
                    response.createdAt(),
                    response.pushedAt(),
                    Optional.ofNullable(response.topics()).orElse(List.of()),
                    languages,
                    Optional.ofNullable(response.owner())
                            .map(GitHubRepoResponse.Owner::avatarUrl)
                            .orElse(null),
                    parentOwner,
                    parent == null ? null : parent.name(),
                    parent == null ? null : parent.defaultBranch());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    private Map<String, Long> fetchLanguages(String pat, RepoRef repo)
    {
        try {
            Map<String, Long> languages = gitHubRestClient
                    .get()
                    .uri("/repos/{owner}/{repo}/languages", repo.owner(), repo.repo())
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return languages == null ? Map.of() : languages;
        }
        catch (RestClientResponseException ignored) {
            return Map.of();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ContributorResponse(
            long id,
            String login,
            @JsonProperty("avatar_url") String avatarUrl,
            String type) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LabelResponse(long id, String name, String color) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IssueResponse(
            long id,
            int number,
            String title,
            String body,
            IssueUser user,
            @JsonProperty("html_url") String htmlUrl,
            List<IssueLabel> labels,
            List<IssueUser> assignees,
            int comments,
            @JsonProperty("created_at") java.time.Instant createdAt,
            @JsonProperty("updated_at") java.time.Instant updatedAt,
            @JsonProperty("pull_request") Map<String, Object> pullRequest) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IssueUser(
            String login,
            @JsonProperty("avatar_url") String avatarUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IssueLabel(String name) {}
}
