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
import com.bytequay.core.domain.UserProfile;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAuthoredIssueService
{
    private static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");

    private final GitHubRepoClient repositories = mock(GitHubRepoClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final Clock clock = mock(Clock.class);
    private final AuthoredIssueService service = new AuthoredIssueService(repositories, credentials, clock);

    @Test
    void searchesTheLastThirtyDaysOnceEveryFiveMinutesAndSkipsPullRequests()
    {
        when(credentials.resolveGitHubCredential()).thenReturn(new GitHubCredential("pat", profile("viewer")));
        when(repositories.searchAuthoredIssues("pat", "viewer", LocalDate.parse("2026-09-25"))).thenReturn(new GitHubSearchResponse(2, ImmutableList.of(
                item(42, "https://api.github.com/repos/owner/repo", null),
                item(43, "https://api.github.com/repos/owner/repo", new GitHubSearchResponse.PullRequestLink(null))), false));
        when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(299), NOW.plusSeconds(300));

        assertThat(service.authoredIssues()).singleElement().satisfies(issue -> {
            assertThat(issue.repo()).isEqualTo("owner/repo");
            assertThat(issue.number()).isEqualTo(42);
            assertThat(issue.assignees()).containsExactly("maintainer");
            assertThat(issue.labels()).containsExactly("docs");
            assertThat(issue.commentCount()).isEqualTo(3);
        });
        service.authoredIssues();
        verify(repositories, times(1)).searchAuthoredIssues(any(), any(), any());

        service.authoredIssues();
        verify(repositories, times(2)).searchAuthoredIssues(any(), any(), any());
    }

    @Test
    void anotherAccountSearchesAgainAtOnce()
    {
        when(credentials.resolveGitHubCredential()).thenReturn(new GitHubCredential("pat", profile("viewer")), new GitHubCredential("other-pat", profile("other")));
        when(repositories.searchAuthoredIssues(any(), any(), any())).thenReturn(new GitHubSearchResponse(0, ImmutableList.of(), false));
        when(clock.instant()).thenReturn(NOW);

        service.authoredIssues();
        service.authoredIssues();

        verify(repositories).searchAuthoredIssues("pat", "viewer", LocalDate.parse("2026-09-25"));
        verify(repositories).searchAuthoredIssues("other-pat", "other", LocalDate.parse("2026-09-25"));
    }

    private static GitHubSearchResponse.Item item(int number, String repositoryUrl, GitHubSearchResponse.PullRequestLink pullRequest)
    {
        return new GitHubSearchResponse.Item(number, number, "Document a limit", "https://github.com/owner/repo/issues/" + number, NOW, NOW, null, "open",
                repositoryUrl, null, ImmutableList.of(new GitHubSearchResponse.Label("docs", "ffffff")), false, pullRequest, null, null,
                ImmutableList.of(new GitHubUser("maintainer", null, "User")), 3);
    }

    private static UserProfile profile(String login)
    {
        return new UserProfile(login, login, "", "", 0, 0, 0, "", "", "", "");
    }
}
