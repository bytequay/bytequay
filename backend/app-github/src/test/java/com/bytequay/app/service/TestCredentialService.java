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
import com.bytequay.app.service.credentials.CredentialService.GitHubReadSelection;
import com.bytequay.app.service.github.GhCliService;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.store.CredentialStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.core.hosting.HostingFailure.Kind.AUTHENTICATION_REQUIRED;
import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
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

        assertThat(new CredentialService(store, new InMemoryWorkspaceCredentialStore(), ghCli, mock(GitHubAccountClient.class)).resolveGitHubToken()).isEqualTo("gho_rotated");
        verify(store).save("gho_rotated", GH_CLI_SOURCE);
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
        when(ghCli.readToken()).thenReturn("gho_secret");
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
        CredentialService service = new CredentialService(store, new InMemoryWorkspaceCredentialStore(), mock(GhCliService.class), accounts);

        GitHubCredential first = service.resolveGitHubCredential();
        GitHubCredential second = service.resolveGitHubCredential();

        assertThat(first).isSameAs(second);
        assertThat(first.profile().login()).isEqualTo("octocat");
        verify(accounts).fetchUserProfile("token");
    }

    @Test
    void rawCliResolutionInvalidatesCapturedReadsAndUpdatesFallback()
    {
        CredentialStore store = stored("old-token", GH_CLI_SOURCE);
        GhCliService ghCli = mock(GhCliService.class);
        when(ghCli.currentToken()).thenReturn(Optional.of("old-token"));
        CredentialService service = new CredentialService(store, new InMemoryWorkspaceCredentialStore(), ghCli, mock(GitHubAccountClient.class));
        GitHubReadSelection first = service.captureGitHubRead("old-token");
        when(ghCli.currentToken()).thenReturn(Optional.of("new-token"));

        assertThat(service.resolveGitHubToken()).isEqualTo("new-token");
        assertThat(store.revision()).isNotEqualTo(first.revision());
        assertThatThrownBy(() -> service.withCurrentGitHubRead(first, () -> "stale"))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(REVISION_CONFLICT));
        when(ghCli.currentToken()).thenReturn(Optional.empty());
        assertThat(service.resolveGitHubToken()).isEqualTo("new-token");
    }

    @Test
    void localAcceptanceDoesNotReadTheCliOrRemoteProfile()
    {
        CredentialStore store = stored("token", GH_CLI_SOURCE);
        GhCliService ghCli = mock(GhCliService.class);
        GitHubAccountClient accounts = mock(GitHubAccountClient.class);
        when(ghCli.currentToken()).thenReturn(Optional.of("token"));
        CredentialService service = new CredentialService(store, new InMemoryWorkspaceCredentialStore(), ghCli, accounts);
        GitHubReadSelection selection = service.captureGitHubRead("token");
        clearInvocations(ghCli, accounts);

        assertThat(service.withCurrentGitHubRead(selection, () -> "accepted")).isEqualTo("accepted");
        verifyNoInteractions(ghCli, accounts);
        assertThat(selection.toString()).doesNotContain("token=token");
        assertThat(new GitHubCredential("secret-value", profile("alice")).toString()).doesNotContain("secret-value");
    }

    @Test
    void deletingAndReaddingTheSameTokenCannotReviveAnOldRead()
    {
        CredentialStore store = stored("token", null);
        GitHubAccountClient accounts = mock(GitHubAccountClient.class);
        when(accounts.fetchUserProfile("token")).thenReturn(profile("alice"));
        CredentialService service = new CredentialService(store, new InMemoryWorkspaceCredentialStore(), mock(GhCliService.class), accounts);
        GitHubReadSelection first = service.captureGitHubRead("token");
        service.deleteGitHubToken();
        AtomicBoolean accepted = new AtomicBoolean();

        assertThatThrownBy(() -> service.withCurrentGitHubRead(first, () -> accepted.getAndSet(true)))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(AUTHENTICATION_REQUIRED));
        service.saveGitHubToken("token");
        assertThatThrownBy(() -> service.withCurrentGitHubRead(first, () -> accepted.getAndSet(true)))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(REVISION_CONFLICT));
        assertThat(accepted).isFalse();
    }

    @Test
    void captureRejectsATokenThatIsNoLongerSelected()
    {
        CredentialService service = new CredentialService(stored("current", null), new InMemoryWorkspaceCredentialStore(),
                mock(GhCliService.class), mock(GitHubAccountClient.class));

        assertThatThrownBy(() -> service.captureGitHubRead("previous"))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(REVISION_CONFLICT));
    }

    @Test
    void gitAuthenticationSendsTheWorkspaceTokenOnlyToGitHub()
    {
        CredentialStore store = mock(CredentialStore.class);
        InMemoryWorkspaceCredentialStore workspaceStore = new InMemoryWorkspaceCredentialStore();
        workspaceStore.save("workspace-id", "workspace-token", "octocat");
        CredentialService service = new CredentialService(store, workspaceStore, mock(GhCliService.class), mock(GitHubAccountClient.class));

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
        CredentialService service = new CredentialService(store, new InMemoryWorkspaceCredentialStore(), mock(GhCliService.class), mock(GitHubAccountClient.class));

        assertThat(service.gitAuthenticationEnvironment("workspace-id", ImmutableList.of("https://git.example.com/repo.git")))
                .containsExactlyInAnyOrderEntriesOf(ImmutableMap.of("GIT_CONFIG_COUNT", "0"));
        verify(store, never()).getSecret();
    }

    private static UserProfile profile(String login)
    {
        return new UserProfile(login, login, "", "", 0, 0, 0, "", "", "", "");
    }

    private static CredentialStore stored(String token, String source)
    {
        CredentialStore store = mock(CredentialStore.class);
        AtomicReference<String> secret = new AtomicReference<>(token);
        AtomicReference<String> selectedSource = new AtomicReference<>(source);
        AtomicReference<String> revision = new AtomicReference<>(UUID.randomUUID().toString());
        when(store.getSecret()).thenAnswer(ignored -> Optional.ofNullable(secret.get()));
        when(store.source()).thenAnswer(ignored -> Optional.ofNullable(selectedSource.get()));
        when(store.revision()).thenAnswer(ignored -> revision.get());
        doAnswer(call -> {
            secret.set(call.getArgument(0));
            selectedSource.set(call.getArgument(1));
            revision.set(UUID.randomUUID().toString());
            return null;
        }).when(store).save(anyString(), any());
        doAnswer(ignored -> {
            secret.set(null);
            revision.set(null);
            return null;
        }).when(store).delete();
        return store;
    }
}
