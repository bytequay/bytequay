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

import com.bytequay.app.domain.RepoMeta;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.GitHubRepoRepository;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static com.bytequay.app.repository.github.GitHubApiSupport.toReadableException;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubRepoClient
        implements GitHubRepoRepository
{
    private final RestClient gitHubRestClient;

    public GitHubRepoClient(RestClient gitHubRestClient)
    {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
    }

    @Override
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
                    Optional.ofNullable(response.topics()).orElse(ImmutableList.of()),
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
            return languages == null ? ImmutableMap.of() : languages;
        }
        catch (RestClientResponseException ignored) {
            return ImmutableMap.of();
        }
    }
}
