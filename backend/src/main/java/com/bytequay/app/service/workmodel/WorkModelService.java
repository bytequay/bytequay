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
import com.bytequay.app.domain.WorkModelOptions.WorkModelAccount;
import com.bytequay.app.domain.WorkModelOptions.WorkModelAgentOption;
import com.bytequay.app.domain.WorkModelOptions.WorkModelEntry;
import com.bytequay.app.domain.WorkModelOptions.WorkModelProviderOption;
import com.bytequay.app.service.CredentialService;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Assembles the option tree the work-model picker walks. Inputs are two
 * independently-evolving sources:
 *
 * <ul>
 *   <li>{@link WorkModelCatalog} — the curated lists of CLI agents
 *       and API providers, plus their baseline models. Source of
 *       friendly names, ordering, and default-model markers.</li>
 *   <li>{@link CredentialService} — stored API keys, which gate
 *       whether a provider appears at all (no DeepSeek key, no
 *       DeepSeek row) and supply the named account list (★ default
 *       + alternates).</li>
 * </ul>
 *
 * <p>CLI agents are always reported as installed + authed. We used to
 * probe the local binary at picker-render time, but the {@code --version}
 * spawn leaked file-descriptor drain threads and could wedge the JVM's
 * {@code posix_spawn} machinery. Users now discover a missing CLI at
 * use-time with a clear error rather than at render time with a chip.
 */
@Service
public class WorkModelService
{
    private final CredentialService credentials;

    public WorkModelService(CredentialService credentials)
    {
        this.credentials = requireNonNull(credentials, "credentials is null");
    }

    public WorkModelOptions options()
    {
        return new WorkModelOptions(cliAgentOptions(), apiProviderOptions());
    }

    private static List<WorkModelAgentOption> cliAgentOptions()
    {
        ImmutableList.Builder<WorkModelAgentOption> out = ImmutableList.builder();
        for (WorkModelCatalog.CatalogAgent agent : WorkModelCatalog.CLI_AGENTS) {
            out.add(new WorkModelAgentOption(
                    agent.id(),
                    agent.displayName(),
                    /* installed */ true,
                    /* authed */ true,
                    agent.defaultModel().id(),
                    toEntries(agent.models())));
        }
        return out.build();
    }

    private List<WorkModelProviderOption> apiProviderOptions()
    {
        ImmutableList.Builder<WorkModelProviderOption> out = ImmutableList.builder();
        for (WorkModelCatalog.CatalogProvider provider : WorkModelCatalog.API_PROVIDERS) {
            List<Credential> accounts = credentials.listByTypeAndName(CredentialType.AI, provider.id());
            if (accounts.isEmpty()) {
                // Gate: hide providers with no credential on file. The
                // picker surfaces a "Manage credentials →" link so a
                // user can wire one up and revisit.
                continue;
            }
            out.add(new WorkModelProviderOption(
                    provider.id(),
                    provider.displayName(),
                    provider.defaultModel().id(),
                    toEntries(provider.models()),
                    toAccounts(accounts)));
        }
        return out.build();
    }

    private static List<WorkModelEntry> toEntries(List<WorkModelCatalog.CatalogEntry> models)
    {
        return models.stream()
                .map(m -> new WorkModelEntry(m.id(), m.displayName(), m.isDefault()))
                .collect(toImmutableList());
    }

    private static List<WorkModelAccount> toAccounts(List<Credential> accounts)
    {
        return accounts.stream()
                .map(c -> new WorkModelAccount(
                        c.instanceName(),
                        c.isDefault(),
                        null))
                .collect(toImmutableList());
    }
}
