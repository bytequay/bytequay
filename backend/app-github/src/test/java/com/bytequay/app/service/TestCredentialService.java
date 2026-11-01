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
import com.bytequay.app.service.credentials.GitHubTokenSource;
import com.bytequay.app.service.github.GhCliService;
import com.bytequay.app.service.hosting.HostingConnection;
import com.bytequay.app.service.hosting.HostingConnectionService;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingCredential.Source;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.WorkspaceHostingBinding;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.core.hosting.HostingFailure.Kind.AUTHENTICATION_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestCredentialService
{
    private static final HostingInstance GITHUB = new HostingInstance(1, HostingProvider.GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com"));
    private static final HostingConnection CONNECTION = new HostingConnection(new HostingAccount(7, 1, "583231", "octocat"), GITHUB, true, Optional.empty());

    @Test
    void resolvesTheTokenThroughTheMainGitHubAccount()
    {
        InMemoryAppSettingsStore settings = new InMemoryAppSettingsStore();
        settings.put(CredentialService.GITHUB_ACCOUNT_SETTING, "7");
        HostingConnectionService hosting = mock(HostingConnectionService.class);
        when(hosting.credentialSource(7)).thenReturn(Optional.of(Source.TOKEN));
        when(hosting.readSession(7)).thenReturn(session("ghp_pasted"));
        CredentialService service = service(settings, hosting, mock(GitHubAccountClient.class));

        assertThat(service.isGitHubAccountConfigured()).isTrue();
        assertThat(service.resolveGitHubToken()).isEqualTo("ghp_pasted");
        assertThat(service.gitHubTokenSource()).isEqualTo(GitHubTokenSource.PERSONAL_ACCESS_TOKEN);
        assertThat(service.cachedHostingRead()).map(HostingReadSession::accountId).contains(7L);
    }

    @Test
    void withoutAConnectedMainAccountNothingIsConfigured()
    {
        InMemoryAppSettingsStore settings = new InMemoryAppSettingsStore();
        HostingConnectionService hosting = mock(HostingConnectionService.class);
        CredentialService service = service(settings, hosting, mock(GitHubAccountClient.class));

        assertThat(service.isGitHubAccountConfigured()).isFalse();
        assertThatThrownBy(service::resolveGitHubToken).isInstanceOf(ResponseStatusException.class).hasMessageContaining("not configured");
        // A main account whose credential was removed elsewhere is not a sign-in either.
        settings.put(CredentialService.GITHUB_ACCOUNT_SETTING, "7");
        assertThat(service.isGitHubAccountConfigured()).isFalse();
        assertThat(service.gitHubTokenValue()).isEmpty();
        verify(hosting, never()).readSession(7);
    }

    @Test
    void savingATokenConnectsAGitHubAccountAndMakesItTheMainSignIn()
    {
        InMemoryAppSettingsStore settings = new InMemoryAppSettingsStore();
        HostingConnectionService hosting = mock(HostingConnectionService.class);
        GitHubAccountClient accounts = mock(GitHubAccountClient.class);
        when(accounts.fetchUserProfile("ghp_pasted")).thenReturn(profile("octocat"));
        when(hosting.connect(HostingProvider.GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com"), "ghp_pasted")).thenReturn(CONNECTION);
        CredentialService service = service(settings, hosting, accounts);

        service.saveGitHubToken("  ghp_pasted  ");

        assertThat(settings.get(CredentialService.GITHUB_ACCOUNT_SETTING)).contains("7");
        assertThat(service.cachedLogin()).contains("octocat");
        assertThatThrownBy(() -> service.saveGitHubToken(" ")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void importingFromGhConnectsTheAccountThroughTheCli()
    {
        InMemoryAppSettingsStore settings = new InMemoryAppSettingsStore();
        HostingConnectionService hosting = mock(HostingConnectionService.class);
        GitHubAccountClient accounts = mock(GitHubAccountClient.class);
        when(hosting.connectCli(HostingProvider.GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com"))).thenReturn(CONNECTION);
        when(hosting.readSession(7)).thenReturn(session("gho_secret"));
        when(hosting.credentialSource(7)).thenReturn(Optional.of(Source.CLI));
        when(accounts.fetchUserProfile("gho_secret")).thenReturn(profile("octocat"));
        CredentialService service = service(settings, hosting, accounts);

        assertThat(service.importGitHubCliToken()).isEqualTo("octocat");
        assertThat(settings.get(CredentialService.GITHUB_ACCOUNT_SETTING)).contains("7");
        assertThat(service.gitHubTokenSource()).isEqualTo(GitHubTokenSource.GITHUB_CLI);
    }

    @Test
    void signingOutDisconnectsTheMainAccount()
    {
        InMemoryAppSettingsStore settings = new InMemoryAppSettingsStore();
        settings.put(CredentialService.GITHUB_ACCOUNT_SETTING, "7");
        HostingConnectionService hosting = mock(HostingConnectionService.class);
        CredentialService service = service(settings, hosting, mock(GitHubAccountClient.class));

        service.deleteGitHubToken();

        verify(hosting).disconnect(7);
        assertThat(settings.get(CredentialService.GITHUB_ACCOUNT_SETTING)).isEmpty();
        assertThat(service.cachedHostingRead()).isEmpty();
    }

    @Test
    void bindsOneFetchedProfileToTheResolvedToken()
    {
        InMemoryAppSettingsStore settings = new InMemoryAppSettingsStore();
        settings.put(CredentialService.GITHUB_ACCOUNT_SETTING, "7");
        HostingConnectionService hosting = mock(HostingConnectionService.class);
        GitHubAccountClient accounts = mock(GitHubAccountClient.class);
        when(hosting.credentialSource(7)).thenReturn(Optional.of(Source.TOKEN));
        when(hosting.readSession(7)).thenReturn(session("token"));
        when(accounts.fetchUserProfile("token")).thenReturn(profile("octocat"));
        CredentialService service = service(settings, hosting, accounts);

        GitHubCredential first = service.resolveGitHubCredential();
        GitHubCredential second = service.resolveGitHubCredential();

        assertThat(first).isSameAs(second);
        assertThat(first.profile().login()).isEqualTo("octocat");
        verify(accounts).fetchUserProfile("token");
    }

    @Test
    void gitAuthenticationSendsTheBoundTokenOnlyToGitHub()
    {
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        HostingConnectionService hosting = mock(HostingConnectionService.class);
        when(workspaces.findHostingBinding("workspace-id")).thenReturn(Optional.of(new WorkspaceHostingBinding("workspace-id", 7, 9, Optional.empty())));
        when(hosting.readSession(7)).thenReturn(session("workspace-token"));
        CredentialService service = new CredentialService(new InMemoryAppSettingsStore(), mock(GhCliService.class), mock(GitHubAccountClient.class), hosting, workspaces);

        Map<String, String> environment = service.gitAuthenticationEnvironment("workspace-id", ImmutableList.of("https://GITHUB.com:443/example/target.git"));

        String expectedHeader = "Authorization: Basic " + Base64.getEncoder().encodeToString("x-access-token:workspace-token".getBytes(StandardCharsets.UTF_8));
        assertThat(environment).containsExactlyInAnyOrderEntriesOf(ImmutableMap.<String, String>builder()
                .put("GIT_CONFIG_COUNT", "2")
                .put("GIT_CONFIG_KEY_0", "http.https://github.com/.extraHeader")
                .put("GIT_CONFIG_VALUE_0", expectedHeader)
                .put("GIT_CONFIG_KEY_1", "http.followRedirects")
                .put("GIT_CONFIG_VALUE_1", "false")
                .buildOrThrow());
        assertThat(service.gitAuthenticationEnvironment("workspace-id", ImmutableList.of("https://git.example.com/example/other.git",
                "https://github.com.attacker.test/example/other.git", "https://github.com:8443/example/other.git")))
                .containsExactlyInAnyOrderEntriesOf(ImmutableMap.of("GIT_CONFIG_COUNT", "0"));
    }

    @Test
    void unboundWorkspaceGitAuthenticationRequiresAnAccount()
    {
        HostingConnectionService hosting = mock(HostingConnectionService.class);
        CredentialService service = service(new InMemoryAppSettingsStore(), hosting, mock(GitHubAccountClient.class));

        assertThatThrownBy(() -> service.gitAuthenticationEnvironment("workspace-id", ImmutableList.of("https://github.com/example/source.git")))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(AUTHENTICATION_REQUIRED));
        verify(hosting, never()).readSession(anyLong());
    }

    @ParameterizedTest
    @CsvSource({"GITHUB,https://github.com,x-access-token", "GITLAB,https://gitlab.com,oauth2", "GITLAB,https://gitlab.example.test:8443/gitlab,oauth2"})
    void boundAccountSuppliesGitCredentialsOnlyWithinItsServer(HostingProvider provider, String base, String gitUser)
    {
        AppSettingsStore global = mock(AppSettingsStore.class);
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        HostingConnectionService hosting = mock(HostingConnectionService.class);
        when(workspaces.findHostingBinding("workspace-id")).thenReturn(Optional.of(new WorkspaceHostingBinding("workspace-id", 7, 9, Optional.empty())));
        HostingInstance instance = new HostingInstance(3, provider, URI.create(base), URI.create(base + "/api"));
        when(hosting.readSession(7)).thenReturn(new HostingReadSession(7, "hosting-account", new HostingAccess(instance, "selected-token")));
        CredentialService service = new CredentialService(global, mock(GhCliService.class), mock(GitHubAccountClient.class), hosting, workspaces);

        Map<String, String> environment = service.gitAuthenticationEnvironment("workspace-id", ImmutableList.of(base + "/group/subgroup/project.git"));

        // GitLab before 17.1 takes an OAuth token only from the user oauth2.
        String header = "Authorization: Basic " + Base64.getEncoder().encodeToString((gitUser + ":selected-token").getBytes(StandardCharsets.UTF_8));
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
        CredentialService service = new CredentialService(new InMemoryAppSettingsStore(), mock(GhCliService.class), mock(GitHubAccountClient.class), hosting, workspaces);

        assertThat(service.gitAuthenticationEnvironment("workspace-id", ImmutableList.of(
                "https://git.example.test/gitlab/group/project.git", "https://git.example.test:8443/other/project.git",
                "https://git.example.test:8443/gitlab-other/project.git", "https://git.example.test.attacker.test:8443/gitlab/project.git")))
                .containsExactlyInAnyOrderEntriesOf(ImmutableMap.of("GIT_CONFIG_COUNT", "0"));
    }

    @Test
    void disconnectedSelectedAccountDoesNotFallBackToTheGlobalCredential()
    {
        AppSettingsStore global = mock(AppSettingsStore.class);
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        HostingConnectionService hosting = mock(HostingConnectionService.class);
        when(workspaces.findHostingBinding("workspace-id")).thenReturn(Optional.of(new WorkspaceHostingBinding("workspace-id", 7, 9, Optional.empty())));
        when(hosting.readSession(7)).thenThrow(new HostingFailure(AUTHENTICATION_REQUIRED, "Reconnect this hosting account"));
        CredentialService service = new CredentialService(global, mock(GhCliService.class), mock(GitHubAccountClient.class), hosting, workspaces);

        assertThatThrownBy(() -> service.gitAuthenticationEnvironment("workspace-id", ImmutableList.of("https://gitlab.com/group/project.git")))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(AUTHENTICATION_REQUIRED));
        verifyNoInteractions(global);
    }

    private static CredentialService service(InMemoryAppSettingsStore settings, HostingConnectionService hosting, GitHubAccountClient accounts)
    {
        return new CredentialService(settings, mock(GhCliService.class), accounts, hosting, mock(WorkspaceStore.class));
    }

    private static HostingReadSession session(String token)
    {
        return new HostingReadSession(7, "hosting-account", new HostingAccess(GITHUB, token));
    }

    private static UserProfile profile(String login)
    {
        return new UserProfile(login, login, "", "", 0, 0, 0, "", "", "", "");
    }
}
