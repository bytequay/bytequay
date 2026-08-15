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

import com.bytequay.app.domain.Credential;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.repository.sqlite.SqliteCredentialStore;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.github.GhCliService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.app.service.credentials.CredentialService.GITHUB_ACCOUNT_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        SqliteCredentialStore store = mock(SqliteCredentialStore.class);
        GhCliService ghCli = mock(GhCliService.class);
        when(store.find(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME))
                .thenReturn(Optional.of(accountRow(GH_CLI_SOURCE)));
        when(ghCli.currentToken()).thenReturn(Optional.of("gho_rotated"));

        assertThat(new CredentialService(store, ghCli).resolveGitHubToken()).isEqualTo("gho_rotated");
        verify(store, never()).getSecret(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME);
    }

    @Test
    void aPastedPatIsNeverOverriddenByGh()
    {
        SqliteCredentialStore store = mock(SqliteCredentialStore.class);
        GhCliService ghCli = mock(GhCliService.class);
        when(store.find(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME))
                .thenReturn(Optional.of(accountRow(null)));
        when(store.getSecret(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME))
                .thenReturn(Optional.of("ghp_pasted"));

        assertThat(new CredentialService(store, ghCli).resolveGitHubToken()).isEqualTo("ghp_pasted");
        verify(ghCli, never()).currentToken();
    }

    @Test
    void anUnavailableGhFallsBackToTheStoredCopy()
    {
        SqliteCredentialStore store = mock(SqliteCredentialStore.class);
        GhCliService ghCli = mock(GhCliService.class);
        when(store.find(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME))
                .thenReturn(Optional.of(accountRow(GH_CLI_SOURCE)));
        when(ghCli.currentToken()).thenReturn(Optional.empty());
        when(store.getSecret(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME))
                .thenReturn(Optional.of("gho_imported"));

        assertThat(new CredentialService(store, ghCli).resolveGitHubToken()).isEqualTo("gho_imported");
    }

    @Test
    void importingFromGhStoresTheCredentialAndSource()
    {
        SqliteCredentialStore store = mock(SqliteCredentialStore.class);
        GhCliService ghCli = mock(GhCliService.class);
        when(ghCli.readCredential()).thenReturn(new GhCliService.CliCredential("gho_secret", "octocat"));

        var result = new CredentialService(store, ghCli).importGitHubCliToken();

        assertThat(result).isEqualTo("octocat");
        verify(store).upsert(
                eq(CredentialType.ACCOUNT),
                eq(GITHUB_ACCOUNT_NAME),
                eq("gho_secret"),
                eq("octocat"),
                any(),
                eq(GH_CLI_SOURCE));
    }

    private static Credential accountRow(String configJson)
    {
        return new Credential(
                1L,
                CredentialType.ACCOUNT,
                GITHUB_ACCOUNT_NAME,
                "octocat",
                "gho_…",
                null,
                configJson,
                Instant.EPOCH,
                Instant.EPOCH,
                null);
    }
}
