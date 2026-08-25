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
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.service.github.GhCliService;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.store.CredentialStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
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
        GitHubAccountClient accounts = mock(GitHubAccountClient.class);
        when(ghCli.readCredential()).thenReturn(new GhCliService.CliCredential("gho_secret", "octocat"));
        when(accounts.fetchUserProfile("gho_secret")).thenReturn(profile("octocat"));

        var result = new CredentialService(store, new InMemoryWorkspaceCredentialStore(), ghCli, accounts).importGitHubCliToken();

        assertThat(result).isEqualTo("octocat");
        verify(store).save("gho_secret", GH_CLI_SOURCE);
    }

    @Test
    void bindsOneFetchedProfileToTheResolvedToken()
    {
        CredentialStore store = mock(CredentialStore.class);
        GitHubAccountClient accounts = mock(GitHubAccountClient.class);
        when(store.source()).thenReturn(Optional.empty());
        when(store.getSecret()).thenReturn(Optional.of("token"));
        when(accounts.fetchUserProfile("token")).thenReturn(profile("octocat"));
        CredentialService service = new CredentialService(
                store,
                new InMemoryWorkspaceCredentialStore(),
                mock(GhCliService.class),
                accounts);

        GitHubCredential first = service.resolveGitHubCredential();
        GitHubCredential second = service.resolveGitHubCredential();

        assertThat(first).isSameAs(second);
        assertThat(first.profile().login()).isEqualTo("octocat");
        verify(accounts).fetchUserProfile("token");
    }

    @Test
    void gitAuthenticationUsesTheWorkspaceTokenForEveryRemoteHost()
    {
        CredentialStore store = mock(CredentialStore.class);
        InMemoryWorkspaceCredentialStore workspaceStore = new InMemoryWorkspaceCredentialStore();
        workspaceStore.save("workspace-id", "workspace-token", "octocat");
        CredentialService service = new CredentialService(
                store,
                workspaceStore,
                mock(GhCliService.class),
                mock(GitHubAccountClient.class));

        Map<String, String> environment = service.gitAuthenticationEnvironment(
                "workspace-id",
                ImmutableList.of(
                        "https://github.com/example/source.git",
                        "https://github.com/example/target.git",
                        "https://git.example.com/example/other.git"));

        String expectedHeader = "Authorization: Basic " + Base64.getEncoder().encodeToString(
                "x-access-token:workspace-token".getBytes(StandardCharsets.UTF_8));
        assertThat(environment).containsExactlyInAnyOrderEntriesOf(ImmutableMap.<String, String>builder()
                .put("GIT_CONFIG_COUNT", "2")
                .put("GIT_CONFIG_KEY_0", "http.https://github.com/.extraHeader")
                .put("GIT_CONFIG_VALUE_0", expectedHeader)
                .put("GIT_CONFIG_KEY_1", "http.https://git.example.com/.extraHeader")
                .put("GIT_CONFIG_VALUE_1", expectedHeader)
                .buildOrThrow());
        verify(store, never()).getSecret();
    }

    private static UserProfile profile(String login)
    {
        return new UserProfile(login, login, "", "", 0, 0, 0, "", "", "", "");
    }
}
