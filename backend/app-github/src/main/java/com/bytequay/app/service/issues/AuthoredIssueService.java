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
package com.bytequay.app.service.issues;

import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.repository.github.GitHubSearchResponse;
import com.bytequay.app.repository.github.GitHubUser;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Open issues the viewer opened anywhere on GitHub and that changed in the last thirty days, for My Work. */
@Service
public class AuthoredIssueService
{
    private static final Duration FRESH = Duration.ofMinutes(5);
    private static final int WINDOW_DAYS = 30;
    private static final String REPOSITORY_API = "/repos/";

    private final GitHubRepoClient repositories;
    private final CredentialService credentials;
    private final Clock clock;
    private Optional<Read> last = Optional.empty();

    public AuthoredIssueService(GitHubRepoClient repositories, CredentialService credentials, Clock clock)
    {
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** Searches GitHub only when the last answer is older than five minutes, so Home's polls cost nothing while it is closed. */
    public synchronized List<AuthoredIssue> authoredIssues()
    {
        GitHubCredential credential = credentials.resolveGitHubCredential();
        String login = credential.profile().login();
        Instant now = clock.instant();
        Optional<Read> fresh = last.filter(read -> read.login().equals(login) && now.isBefore(read.at().plus(FRESH)));
        if (fresh.isPresent()) {
            return fresh.orElseThrow().issues();
        }
        LocalDate since = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(WINDOW_DAYS);
        List<AuthoredIssue> issues = repositories.searchAuthoredIssues(credential.token(), login, since).items().stream()
                .filter(item -> item.pullRequest() == null && item.repositoryUrl() != null && item.repositoryUrl().contains(REPOSITORY_API))
                .map(AuthoredIssueService::issue)
                .collect(toImmutableList());
        last = Optional.of(new Read(login, now, issues));
        return issues;
    }

    private static AuthoredIssue issue(GitHubSearchResponse.Item item)
    {
        String repositoryUrl = item.repositoryUrl();
        return new AuthoredIssue(
                repositoryUrl.substring(repositoryUrl.lastIndexOf(REPOSITORY_API) + REPOSITORY_API.length()),
                item.number(),
                item.title(),
                item.htmlUrl(),
                Optional.ofNullable(item.labels()).orElse(ImmutableList.of()).stream().map(GitHubSearchResponse.Label::name).filter(Objects::nonNull).collect(toImmutableList()),
                Optional.ofNullable(item.assignees()).orElse(ImmutableList.of()).stream().map(GitHubUser::login).filter(Objects::nonNull).collect(toImmutableList()),
                Optional.ofNullable(item.comments()).orElse(0),
                item.createdAt(),
                item.updatedAt());
    }

    private record Read(String login, Instant at, List<AuthoredIssue> issues) {}
}
