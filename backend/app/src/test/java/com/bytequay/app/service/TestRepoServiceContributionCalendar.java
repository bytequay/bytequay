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
import com.bytequay.core.store.PrViewStateStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestRepoServiceContributionCalendar
{
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

    private static RepoService service(GitHubAccountClient gitHub, CredentialService credentialService)
    {
        return new RepoService(
                mock(GitHubPullRequestReadClient.class),
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
