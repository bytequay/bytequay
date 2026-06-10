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
import java.util.Map;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Assembles the option tree the work-model picker walks. Inputs are
 * three independently-evolving sources:
 *
 * <ul>
 *   <li>{@link WorkModelCatalog} — the curated lists of CLI agents
 *       and API providers, plus their baseline models. Source of
 *       friendly names, ordering, and default-model markers.</li>
 *   <li>{@link CliAgentDetector} — per-agent local readiness (binary
 *       on PATH + auth probe). Drives the "installed & authed" /
 *       "set up →" badge on CLI rows.</li>
 *   <li>{@link CredentialService} — stored API keys, which gate
 *       whether a provider appears at all (no DeepSeek key, no
 *       DeepSeek row) and supply the named account list (★ default
 *       + alternates).</li>
 * </ul>
 *
 * <p>The composed view is a snapshot — callers re-fetch when the
 * picker opens. Phase 2 reads from the same source when resolving the
 * effective work model for a turn.
 */
@Service
public class WorkModelService
{
    private final CredentialService credentials;
    private final CliAgentDetector cliDetector;

    public WorkModelService(CredentialService credentials, CliAgentDetector cliDetector)
    {
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.cliDetector = requireNonNull(cliDetector, "cliDetector is null");
    }

    /** Non-blocking read — never waits on a fresh probe. Used by the
     *  picker's first-open path; the detector kicks off a background
     *  sweep when entries are stale and the picker's manual Refresh
     *  button is the path that actually waits for one. */
    public WorkModelOptions options()
    {
        return new WorkModelOptions(cliAgentOptions(cliDetector.detectAll()), apiProviderOptions());
    }

    /** Blocking read — synchronously re-probes every CLI agent.
     *  Hooked from the picker's Refresh affordance only. */
    public WorkModelOptions optionsBlocking()
    {
        return new WorkModelOptions(cliAgentOptions(cliDetector.detectAllBlocking()), apiProviderOptions());
    }

    private List<WorkModelAgentOption> cliAgentOptions(Map<String, CliAgentDetector.Readiness> readiness)
    {
        ImmutableList.Builder<WorkModelAgentOption> out = ImmutableList.builder();
        for (WorkModelCatalog.CatalogAgent agent : WorkModelCatalog.CLI_AGENTS) {
            CliAgentDetector.Readiness r = readiness.getOrDefault(
                    agent.id(), new CliAgentDetector.Readiness(false, false));
            out.add(new WorkModelAgentOption(
                    agent.id(),
                    agent.displayName(),
                    r.installed(),
                    r.authed(),
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
                        // {@code instanceName} is the human-pickable
                        // "name" the picker shows ("personal", "team", …).
                        c.instanceName(),
                        c.isDefault(),
                        // Validity isn't probed on every fetch — the
                        // credentials surface owns the test-probe and
                        // its cached result will land here in a follow-up.
                        // Null means "never probed", which the picker
                        // renders as a neutral chip rather than ✓/⚠.
                        null))
                .collect(toImmutableList());
    }
}
