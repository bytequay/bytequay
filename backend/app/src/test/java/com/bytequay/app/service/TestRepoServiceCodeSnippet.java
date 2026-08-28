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
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.store.PrViewStateStore;
import com.bytequay.core.store.PullRequestStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestRepoServiceCodeSnippet
{
    private static final String COMMIT = "4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa";

    private GitHubPullRequestReadClient pullRequests;
    private CredentialService credentials;
    private RepoService service;

    @BeforeEach
    void setUp()
    {
        pullRequests = mock(GitHubPullRequestReadClient.class);
        credentials = mock(CredentialService.class);
        service = new RepoService(
                pullRequests,
                mock(PullRequestStore.class),
                mock(GitHubAccountClient.class),
                mock(PrViewStateStore.class),
                credentials);
    }

    @Test
    void returnsOnlyTheRequestedNumberedSourceLines()
    {
        when(credentials.resolveGitHubToken()).thenReturn("pat");
        when(pullRequests.fetchRepositoryFileLines(
                "pat", RepoRef.of("trinodb", "trino"), "src/Main.java", COMMIT, 2, 3))
                .thenReturn(Optional.of(java.util.List.of("second", "third")));

        assertThat(service.getCodeSnippet(
                "trinodb", "trino", COMMIT, "src/Main.java", 2, 3))
                .containsExactly("second", "third");

        verify(pullRequests).fetchRepositoryFileLines(
                "pat", RepoRef.of("trinodb", "trino"), "src/Main.java", COMMIT, 2, 3);
    }

    @Test
    void rejectsUnsafeRangesBeforeContactingGitHub()
    {
        assertThatThrownBy(() -> service.getCodeSnippet(
                "trinodb", "trino", COMMIT, "src/Main.java", 1, 21))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(credentials, pullRequests);
    }

    @Test
    void rejectsOverflowingRangesBeforeContactingGitHub()
    {
        assertThatThrownBy(() -> service.getCodeSnippet(
                "trinodb", "trino", COMMIT, "src/Main.java", 1, Integer.MAX_VALUE))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(credentials, pullRequests);
    }

    @Test
    void reportsMissingSourceLinesAsNotFound()
    {
        when(credentials.resolveGitHubToken()).thenReturn("pat");
        when(pullRequests.fetchRepositoryFileLines(
                "pat", RepoRef.of("trinodb", "trino"), "src/Main.java", COMMIT, 2, 2))
                .thenReturn(Optional.of(java.util.List.of()));

        assertThatThrownBy(() -> service.getCodeSnippet(
                "trinodb", "trino", COMMIT, "src/Main.java", 2, 2))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
