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
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
        when(gitHub.fetchContributionCalendar("pat", "contributor"))
                .thenThrow(new IllegalStateException("RESOURCE_LIMITS_EXCEEDED"));

        RepoService service = service(gitHub, credentialService);

        assertThatThrownBy(() -> service.getContributionCalendar("contributor"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getReason()).isEqualTo("Contribution graph temporarily unavailable");
                });
    }

    private static RepoService service(GitHubAccountClient gitHub, CredentialService credentialService)
    {
        return new RepoService(mock(GitHubPullRequestReadClient.class), gitHub, mock(GitHubRepoClient.class), credentialService);
    }
}
