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
package com.bytequay.app.service.credentials;

import com.bytequay.app.domain.Credential;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.github.GhCliService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.app.service.CredentialService.GITHUB_ACCOUNT_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Precedence around the gh-CLI live binding. The stale-copy case is the
 * whole point of the feature: after {@code gh auth login} rotates the
 * token, the value sitting in the credentials table is dead, and resolving
 * it would 401 every call until the user noticed and re-imported.
 */
class TestPatResolver
{
    @Test
    void aGhSourcedSlotPrefersGhsLiveTokenOverTheStoredCopy()
    {
        CredentialService credentials = mock(CredentialService.class);
        GhCliService ghCli = mock(GhCliService.class);
        when(credentials.get(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME))
                .thenReturn(Optional.of(accountRow(GhCliService.SOURCE_MARKER)));
        when(ghCli.currentToken()).thenReturn(Optional.of("gho_rotated"));

        assertThat(new PatResolver(credentials, ghCli).resolve()).isEqualTo("gho_rotated");
        verify(credentials, never()).getSecret(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME);
    }

    @Test
    void aPastedPatIsNeverOverriddenByGh()
    {
        CredentialService credentials = mock(CredentialService.class);
        GhCliService ghCli = mock(GhCliService.class);
        when(credentials.get(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME))
                .thenReturn(Optional.of(accountRow(null)));
        when(credentials.getSecret(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME))
                .thenReturn(Optional.of("ghp_pasted"));

        assertThat(new PatResolver(credentials, ghCli).resolve()).isEqualTo("ghp_pasted");
        verify(ghCli, never()).currentToken();
    }

    @Test
    void anUninstalledGhFallsBackToTheStoredCopy()
    {
        CredentialService credentials = mock(CredentialService.class);
        GhCliService ghCli = mock(GhCliService.class);
        when(credentials.get(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME))
                .thenReturn(Optional.of(accountRow(GhCliService.SOURCE_MARKER)));
        when(ghCli.currentToken()).thenReturn(Optional.empty());
        when(credentials.getSecret(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME))
                .thenReturn(Optional.of("gho_imported"));

        assertThat(new PatResolver(credentials, ghCli).resolve()).isEqualTo("gho_imported");
    }

    @Test
    void aPerRepoTokenStillWinsOutright()
    {
        CredentialService credentials = mock(CredentialService.class);
        GhCliService ghCli = mock(GhCliService.class);
        when(credentials.getSecret(eq(CredentialType.REPO), eq("acme/widgets")))
                .thenReturn(Optional.of("ghp_repo"));

        assertThat(new PatResolver(credentials, ghCli).resolve("acme/widgets")).isEqualTo("ghp_repo");
        verify(ghCli, never()).currentToken();
    }

    private static Credential accountRow(String configJson)
    {
        return new Credential(
                1L,
                CredentialType.ACCOUNT,
                GITHUB_ACCOUNT_NAME,
                "default api",
                "octocat",
                "gho_…",
                null,
                true,
                configJson,
                Instant.EPOCH,
                Instant.EPOCH,
                null);
    }
}
