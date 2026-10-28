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
import com.bytequay.app.service.hosting.HostingConnectionService;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.WorkspaceHostingBinding;
import com.bytequay.core.store.CredentialStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.core.hosting.HostingFailure.Kind.AUTHENTICATION_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

        assertThat(new CredentialService(store, new InMemoryWorkspaceCredentialStore(), ghCli, mock(GitHubAccountClient.class),
                mock(HostingConnectionService.class), mock(WorkspaceStore.class)).resolveGitHubToken()).isEqualTo("gho_rotated");
        verify(store, never()).getSecret();
    }

    @Test
    void aPastedPatIsNeverOverriddenByGh()
    {
        CredentialStore store = mock(CredentialStore.class);
        GhCliService ghCli = mock(GhCliService.class);
        when(store.source()).thenReturn(Optional.empty());
        when(store.getSecret()).thenReturn(Optional.of("ghp_pasted"));

        assertThat(new CredentialService(store, new InMemoryWorkspaceCredentialStore(), ghCli, mock(GitHubAccountClient.class),
                mock(HostingConnectionService.class), mock(WorkspaceStore.class)).resolveGitHubToken()).isEqualTo("ghp_pasted");
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

        assertThat(new CredentialService(store, new InMemoryWorkspaceCredentialStore(), ghCli, mock(GitHubAccountClient.class),
                mock(HostingConnectionService.class), mock(WorkspaceStore.class)).resolveGitHubToken()).isEqualTo("gho_imported");
    }

    @Test
    void importingFromGhStoresTheCredentialAndSource()
    {
        CredentialStore store = mock(CredentialStore.class);
        GhCliService ghCli = mock(GhCliService.class);
        GitHubAccountClient accounts = mock(GitHubAccountClient.class);
        when(ghCli.readToken()).thenReturn("gho_secret");
        when(accounts.fetchUserProfile("gho_secret")).thenReturn(profile("octocat"));

        var result = new CredentialService(store, new InMemoryWorkspaceCredentialStore(), ghCli, accounts,
                mock(HostingConnectionService.class), mock(WorkspaceStore.class)).importGitHubCliToken();

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
        CredentialService service = new CredentialService(store, new InMemoryWorkspaceCredentialStore(), mock(GhCliService.class), accounts,
                mock(HostingConnectionService.class), mock(WorkspaceStore.class));

        GitHubCredential first = service.resolveGitHubCredential();
        GitHubCredential second = service.resolveGitHubCredential();

        assertThat(first).isSameAs(second);
        assertThat(first.profile().login()).isEqualTo("octocat");
        verify(accounts).fetchUserProfile("token");
    }

    @Test
    void gitAuthenticationSendsTheWorkspaceTokenOnlyToGitHub()
    {
        CredentialStore store = mock(CredentialStore.class);
        InMemoryWorkspaceCredentialStore workspaceStore = new InMemoryWorkspaceCredentialStore();
        workspaceStore.save("workspace-id", "workspace-token", "octocat");
        CredentialService service = new CredentialService(store, workspaceStore, mock(GhCliService.class), mock(GitHubAccountClient.class),
                mock(HostingConnectionService.class), mock(WorkspaceStore.class));

        Map<String, String> environment = service.gitAuthenticationEnvironment(
                "workspace-id",
                ImmutableList.of("https://github.com/example/source.git", "https://GITHUB.com:443/example/target.git", "https://git.example.com/example/other.git",
                        "https://github.com.attacker.test/example/other.git", "https://github.com:8443/example/other.git"));

        String expectedHeader = "Authorization: Basic " + Base64.getEncoder().encodeToString("x-access-token:workspace-token".getBytes(StandardCharsets.UTF_8));
        assertThat(environment).containsExactlyInAnyOrderEntriesOf(ImmutableMap.<String, String>builder()
                .put("GIT_CONFIG_COUNT", "1")
                .put("GIT_CONFIG_KEY_0", "http.https://github.com/.extraHeader")
                .put("GIT_CONFIG_VALUE_0", expectedHeader)
                .buildOrThrow());
        verify(store, never()).getSecret();
    }

    @Test
    void nonGitHubRemotesNeedNoGitHubCredential()
    {
        CredentialStore store = mock(CredentialStore.class);
        CredentialService service = new CredentialService(store, new InMemoryWorkspaceCredentialStore(), mock(GhCliService.class), mock(GitHubAccountClient.class),
                mock(HostingConnectionService.class), mock(WorkspaceStore.class));

        assertThat(service.gitAuthenticationEnvironment("workspace-id", ImmutableList.of("https://git.example.com/repo.git")))
                .containsExactlyInAnyOrderEntriesOf(ImmutableMap.of("GIT_CONFIG_COUNT", "0"));
        verify(store, never()).getSecret();
    }

    @ParameterizedTest
    @CsvSource({"GITHUB,https://github.com", "GITLAB,https://gitlab.com", "GITLAB,https://gitlab.example.test:8443/gitlab"})
    void boundAccountSuppliesGitCredentialsOnlyWithinItsServer(HostingProvider provider, String base)
    {
        CredentialStore global = mock(CredentialStore.class);
        InMemoryWorkspaceCredentialStore overrides = new InMemoryWorkspaceCredentialStore();
        overrides.save("workspace-id", "override-token", "other");
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        HostingConnectionService hosting = mock(HostingConnectionService.class);
        when(workspaces.findHostingBinding("workspace-id")).thenReturn(Optional.of(new WorkspaceHostingBinding("workspace-id", 7, 9, Optional.empty())));
        HostingInstance instance = new HostingInstance(3, provider, URI.create(base), URI.create(base + "/api"));
        when(hosting.readSession(7)).thenReturn(new HostingReadSession(7, "hosting-account", new HostingAccess(instance, "selected-token")));
        CredentialService service = new CredentialService(global, overrides, mock(GhCliService.class), mock(GitHubAccountClient.class), hosting, workspaces);

        Map<String, String> environment = service.gitAuthenticationEnvironment("workspace-id", ImmutableList.of(base + "/group/subgroup/project.git"));

        String header = "Authorization: Basic " + Base64.getEncoder().encodeToString("x-access-token:selected-token".getBytes(StandardCharsets.UTF_8));
        assertThat(environment).containsExactlyInAnyOrderEntriesOf(ImmutableMap.<String, String>builder()
                .put("GIT_CONFIG_COUNT", "2")
                .put("GIT_CONFIG_KEY_0", "http." + base + "/.extraHeader")
                .put("GIT_CONFIG_VALUE_0", header)
                .put("GIT_CONFIG_KEY_1", "http.followRedirects")
                .put("GIT_CONFIG_VALUE_1", "false")
                .buildOrThrow());
        assertThat(service.gitAuthenticationEnvironment("workspace-id", ImmutableList.of("https://another.example.test/group/project.git")))
                .containsExactlyInAnyOrderEntriesOf(ImmutableMap.of("GIT_CONFIG_COUNT", "0"));
        verifyNoInteractions(global);
    }

    @Test
    void privateServerCredentialsExcludeOtherPortsAndPaths()
    {
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        HostingConnectionService hosting = mock(HostingConnectionService.class);
        when(workspaces.findHostingBinding("workspace-id")).thenReturn(Optional.of(new WorkspaceHostingBinding("workspace-id", 7, 9, Optional.empty())));
        HostingInstance instance = new HostingInstance(3, HostingProvider.GITLAB, URI.create("https://git.example.test:8443/gitlab"),
                URI.create("https://git.example.test:8443/gitlab/api/v4"));
        when(hosting.readSession(7)).thenReturn(new HostingReadSession(7, "hosting-account", new HostingAccess(instance, "selected-token")));
        CredentialService service = new CredentialService(mock(CredentialStore.class), new InMemoryWorkspaceCredentialStore(),
                mock(GhCliService.class), mock(GitHubAccountClient.class), hosting, workspaces);

        assertThat(service.gitAuthenticationEnvironment("workspace-id", ImmutableList.of(
                "https://git.example.test/gitlab/group/project.git", "https://git.example.test:8443/other/project.git",
                "https://git.example.test:8443/gitlab-other/project.git", "https://git.example.test.attacker.test:8443/gitlab/project.git")))
                .containsExactlyInAnyOrderEntriesOf(ImmutableMap.of("GIT_CONFIG_COUNT", "0"));
    }

    @Test
    void disconnectedSelectedAccountDoesNotFallBackToTheGlobalCredential()
    {
        CredentialStore global = mock(CredentialStore.class);
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        HostingConnectionService hosting = mock(HostingConnectionService.class);
        when(workspaces.findHostingBinding("workspace-id")).thenReturn(Optional.of(new WorkspaceHostingBinding("workspace-id", 7, 9, Optional.empty())));
        when(hosting.readSession(7)).thenThrow(new HostingFailure(AUTHENTICATION_REQUIRED, "Reconnect this hosting account"));
        CredentialService service = new CredentialService(global, new InMemoryWorkspaceCredentialStore(), mock(GhCliService.class),
                mock(GitHubAccountClient.class), hosting, workspaces);

        assertThatThrownBy(() -> service.gitAuthenticationEnvironment("workspace-id", ImmutableList.of("https://gitlab.com/group/project.git")))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(AUTHENTICATION_REQUIRED));
        verifyNoInteractions(global);
    }

    private static UserProfile profile(String login)
    {
        return new UserProfile(login, login, "", "", 0, 0, 0, "", "", "", "");
    }
}
