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
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.ContributionCalendar;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.store.PrViewStateStore;
import com.bytequay.core.store.PullRequestStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestRepoServiceContributionCalendar
{
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void graphQlResourceLimitBecomesTemporaryUnavailable()
    {
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        CredentialService credentialService = mock(CredentialService.class);
        when(credentialService.resolveGitHubToken()).thenReturn("pat");
        when(gitHub.fetchContributionCalendar("pat", "chenjian2664"))
                .thenThrow(new IllegalStateException("RESOURCE_LIMITS_EXCEEDED"));

        RepoService service = service(gitHub, credentialService);

        assertThatThrownBy(() -> service.getContributionCalendar("chenjian2664"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getReason()).isEqualTo("Contribution graph temporarily unavailable");
                });
    }

    @Test
    void openingAPullRequestStoresItSoItHasARowIdAndViewState()
    {
        GitHubPullRequestReadClient pullRequests = mock(GitHubPullRequestReadClient.class);
        PullRequestStore store = mock(PullRequestStore.class);
        PrViewStateStore viewStates = mock(PrViewStateStore.class);
        CredentialService credentialService = mock(CredentialService.class);
        when(credentialService.resolveGitHubToken()).thenReturn("pat");
        FetchedPullRequest fetched = fetchedPullRequest();
        when(pullRequests.getPullRequest("pat", PullRequestRef.of("trinodb", "trino", 30835)))
                .thenReturn(fetched);
        when(store.saveObserved(fetched)).thenReturn(storedPullRequest());
        when(viewStates.findByPrIds(ImmutableSet.of(7L))).thenReturn(ImmutableMap.of());

        RepoService service = new RepoService(
                pullRequests, store, mock(GitHubAccountClient.class), viewStates, credentialService);

        assertThat(service.getRepoPullRequest("trinodb", "trino", 30835).id()).isEqualTo(7L);
    }

    private static FetchedPullRequest fetchedPullRequest()
    {
        return new FetchedPullRequest(
                1L, "trinodb/trino", 30835, "title", "alice", "url", NOW, NOW,
                PullRequest.Origin.OTHER, ImmutableList.of(), null, false, "open", null, null,
                ImmutableList.of(), null, null, "feature");
    }

    private static PullRequest storedPullRequest()
    {
        return new PullRequest(
                7L, "trinodb/trino", 30835, "title", "alice", "url", NOW, NOW,
                PullRequest.Origin.OTHER, ImmutableList.of(), null, false, null, null, null,
                ImmutableList.of(), null, 0, 0, 0, null, "open", null, null, null, null, null, null, "feature", 1L);
    }

    private static RepoService service(GitHubAccountClient gitHub, CredentialService credentialService)
    {
        return new RepoService(
                mock(GitHubPullRequestReadClient.class),
                mock(PullRequestStore.class),
                gitHub,
                mock(PrViewStateStore.class),
                credentialService);
    }

    private static ContributionCalendar calendar()
    {
        return new ContributionCalendar(
                1,
                ImmutableList.of(new ContributionCalendar.Week(ImmutableList.of(
                        new ContributionCalendar.Day(LocalDate.parse("2026-07-17"), 1, "#40c463")))));
    }
}
