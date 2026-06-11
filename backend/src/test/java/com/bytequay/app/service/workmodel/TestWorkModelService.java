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
package com.bytequay.app.service.workmodel;

import com.bytequay.app.domain.Credential;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.domain.WorkModelOptions;
import com.bytequay.app.service.CredentialService;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class TestWorkModelService
{
    @Test
    void cliAgentsAlwaysAppearAsInstalledAndAuthed()
    {
        CredentialService credentials = Mockito.mock(CredentialService.class);
        when(credentials.listByTypeAndName(any(), any())).thenReturn(List.of());

        WorkModelOptions options = new WorkModelService(credentials).options();

        // We dropped per-host CLI probing — the picker reports every CLI
        // agent as available and discovers a missing binary at use-time.
        assertThat(options.cliAgents())
                .extracting(WorkModelOptions.WorkModelAgentOption::id)
                .containsExactly("claude-code", "codex");
        assertThat(options.cliAgents()).allSatisfy(agent -> {
            assertThat(agent.installed()).isTrue();
            assertThat(agent.authed()).isTrue();
        });
    }

    @Test
    void apiProvidersHiddenWhenNoCredentialOnFile()
    {
        CredentialService credentials = Mockito.mock(CredentialService.class);
        when(credentials.listByTypeAndName(any(), any())).thenReturn(List.of());

        WorkModelOptions options = new WorkModelService(credentials).options();

        // Every provider in the catalog has zero credentials → empty
        // API list. CLI agents always appear regardless because they
        // auth outside ByteQuay.
        assertThat(options.apiProviders()).isEmpty();
    }

    @Test
    void apiProviderSurfacesAllStoredAccounts()
    {
        CredentialService credentials = Mockito.mock(CredentialService.class);
        when(credentials.listByTypeAndName(any(), any())).thenReturn(List.of());
        Instant now = Instant.parse("2026-05-22T00:00:00Z");
        when(credentials.listByTypeAndName(eq(CredentialType.AI), eq("anthropic")))
                .thenReturn(ImmutableList.of(
                        credential(1, "anthropic", "personal", true, now),
                        credential(2, "anthropic", "team", false, now)));

        WorkModelOptions options = new WorkModelService(credentials).options();

        assertThat(options.apiProviders())
                .extracting(WorkModelOptions.WorkModelProviderOption::id)
                .containsExactly("anthropic");
        WorkModelOptions.WorkModelProviderOption anthropic = options.apiProviders().get(0);
        assertThat(anthropic.accounts())
                .extracting(WorkModelOptions.WorkModelAccount::name)
                .containsExactly("personal", "team");
        assertThat(anthropic.accounts().get(0).isDefault()).isTrue();
        assertThat(anthropic.accounts().get(1).isDefault()).isFalse();
        // Validity is unknown until the credential surface's test-probe
        // wires through; the picker renders this as a neutral chip.
        assertThat(anthropic.accounts().get(0).valid()).isNull();
    }

    @Test
    void everyAgentExposesItsCatalogDefault()
    {
        CredentialService credentials = Mockito.mock(CredentialService.class);
        when(credentials.listByTypeAndName(any(), any())).thenReturn(List.of());

        WorkModelOptions options = new WorkModelService(credentials).options();

        for (WorkModelOptions.WorkModelAgentOption agent : options.cliAgents()) {
            assertThat(agent.defaultModel()).isNotBlank();
            assertThat(agent.models())
                    .as("agent %s lists its default in the model entries", agent.id())
                    .anySatisfy(m -> {
                        assertThat(m.id()).isEqualTo(agent.defaultModel());
                        assertThat(m.isDefault()).isTrue();
                    });
        }
    }

    private static Credential credential(long id, String provider, String instance, boolean isDefault, Instant now)
    {
        return new Credential(
                id,
                CredentialType.AI,
                provider,
                instance,
                /* label */ provider + " " + instance,
                /* preview */ "sk-…",
                /* notes */ "",
                isDefault,
                /* configJson */ null,
                now,
                now,
                /* lastUsedAt */ null);
    }
}
