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
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.github.GhCliService;
import com.bytequay.core.store.CredentialStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestCredentialService
{
    private static final String GH_CLI_SOURCE = "{\"source\":\"gh-cli\"}";

    @Test
    void aGhSourcedSlotPrefersGhsLiveTokenOverTheStoredCopy()
    {
        CredentialStore store = mock(CredentialStore.class);
        GhCliService ghCli = mock(GhCliService.class);
        when(store.source()).thenReturn(Optional.of(GH_CLI_SOURCE));
        when(ghCli.currentToken()).thenReturn(Optional.of("gho_rotated"));

        assertThat(new CredentialService(store, new InMemoryWorkspaceCredentialStore(), ghCli, mock(GitHubAccountClient.class)).resolveGitHubToken()).isEqualTo("gho_rotated");
        verify(store, never()).getSecret();
    }

    @Test
    void aPastedPatIsNeverOverriddenByGh()
    {
        CredentialStore store = mock(CredentialStore.class);
        GhCliService ghCli = mock(GhCliService.class);
        when(store.source()).thenReturn(Optional.empty());
        when(store.getSecret()).thenReturn(Optional.of("ghp_pasted"));

        assertThat(new CredentialService(store, new InMemoryWorkspaceCredentialStore(), ghCli, mock(GitHubAccountClient.class)).resolveGitHubToken()).isEqualTo("ghp_pasted");
        verify(ghCli, never()).currentToken();
    }

    @Test
    void anUnavailableGhFallsBackToTheStoredCopy()
    {
        CredentialStore store = mock(CredentialStore.class);
        GhCliService ghCli = mock(GhCliService.class);
        when(store.source()).thenReturn(Optional.of(GH_CLI_SOURCE));
        when(ghCli.currentToken()).thenReturn(Optional.empty());
        when(store.getSecret()).thenReturn(Optional.of("gho_imported"));

        assertThat(new CredentialService(store, new InMemoryWorkspaceCredentialStore(), ghCli, mock(GitHubAccountClient.class)).resolveGitHubToken()).isEqualTo("gho_imported");
    }

    @Test
    void importingFromGhStoresTheCredentialAndSource()
    {
        CredentialStore store = mock(CredentialStore.class);
        GhCliService ghCli = mock(GhCliService.class);
        when(ghCli.readCredential()).thenReturn(new GhCliService.CliCredential("gho_secret", "octocat"));

        var result = new CredentialService(store, new InMemoryWorkspaceCredentialStore(), ghCli, mock(GitHubAccountClient.class)).importGitHubCliToken();

        assertThat(result).isEqualTo("octocat");
        verify(store).save("gho_secret", GH_CLI_SOURCE);
    }
}
