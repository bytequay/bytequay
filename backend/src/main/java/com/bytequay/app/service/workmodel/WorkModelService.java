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
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.domain.WorkModelOptions;
import com.bytequay.app.domain.WorkModelOptions.WorkModelAccount;
import com.bytequay.app.domain.WorkModelOptions.WorkModelAgentOption;
import com.bytequay.app.domain.WorkModelOptions.WorkModelEntry;
import com.bytequay.app.domain.WorkModelOptions.WorkModelProviderOption;
import com.bytequay.app.domain.WorkModelOptions.WorkModelReasoningEffort;
import com.bytequay.app.service.CredentialService;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Assembles the option tree the work-model picker walks. Inputs are two
 * independently-evolving sources:
 *
 * <ul>
 *   <li>{@link WorkModelCatalog} — the curated lists of CLI agents
 *       and API providers, plus fallback models.</li>
 *   <li>{@link CodexModelCatalogProbe} — Codex's live picker-visible
 *       models, descriptions, defaults, and reasoning efforts.</li>
 *   <li>{@link CredentialService} — stored API keys, which gate
 *       whether a provider appears at all (no DeepSeek key, no
 *       DeepSeek row) and supply the named account list (★ default
 *       + alternates).</li>
 * </ul>
 *
 * <p>CLI installation is checked with {@code --version}. Codex discovery
 * then uses its JSONL app-server protocol; interactive slash commands are
 * intentionally never scraped.
 */
@Service
public class WorkModelService
{
    private static final Duration CLI_PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final CredentialService credentials;
    private final CodexModelCatalogProbe codexModels;

    public WorkModelService(CredentialService credentials, CodexModelCatalogProbe codexModels)
    {
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.codexModels = requireNonNull(codexModels, "codexModels is null");
    }

    public WorkModelOptions options()
    {
        return options(false);
    }

    public WorkModelOptions refresh()
    {
        return options(true);
    }

    /**
     * Turn a picker id into the complete engine identity a new trunk freezes.
     * Picker ids deliberately omit default models and may omit the API account;
     * those moving defaults are resolved once here so later workspace,
     * credential, or catalog changes cannot move an existing trunk.
     */
    public Optional<WorkModel> freezeChoice(String pickerChoice)
    {
        return WorkspaceEngineSettings.parseChoice(pickerChoice).map(this::freeze);
    }

    /** Complete a resolved workspace engine for durable per-trunk storage. */
    public WorkModel freeze(WorkModel choice)
    {
        requireNonNull(choice, "choice is null");
        WorkModelKind kind = requireNonNull(choice.kind(), "kind is null");
        String engine = requireValue(choice.agentOrProvider(), "agent/provider");
        String model = choice.model() == null || choice.model().isBlank()
                ? defaultModel(kind, engine)
                : choice.model().strip();
        String account = null;
        if (kind == WorkModelKind.API && !isLocalModel(engine, model)) {
            account = freezeAccount(engine, choice.account());
        }
        return new WorkModel(kind, engine, model, account, choice.reasoningEffort());
    }

    private WorkModelOptions options(boolean refresh)
    {
        return new WorkModelOptions(cliAgentOptions(refresh), apiProviderOptions());
    }

    private List<WorkModelAgentOption> cliAgentOptions(boolean refresh)
    {
        ImmutableList.Builder<WorkModelAgentOption> out = ImmutableList.builder();
        for (WorkModelCatalog.CatalogAgent agent : WorkModelCatalog.CLI_AGENTS) {
            boolean installed = cliAvailable(agent.id());
            if (installed && "codex".equals(agent.id())) {
                var discovered = codexModels.models(refresh);
                if (discovered.isPresent()) {
                    List<CodexModelCatalogProbe.Model> models = discovered.orElseThrow();
                    String defaultModel = models.stream()
                            .filter(CodexModelCatalogProbe.Model::isDefault)
                            .findFirst()
                            .orElse(models.get(0))
                            .id();
                    out.add(new WorkModelAgentOption(
                            agent.id(),
                            agent.displayName(),
                            true,
                            true,
                            defaultModel,
                            toCodexEntries(models)));
                    continue;
                }
            }
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

    private String defaultModel(WorkModelKind kind, String engine)
    {
        if (kind == WorkModelKind.CLI) {
            if ("codex".equals(engine)) {
                var discovered = codexModels.models(false);
                if (discovered.isPresent() && !discovered.orElseThrow().isEmpty()) {
                    List<CodexModelCatalogProbe.Model> models = discovered.orElseThrow();
                    return models.stream()
                            .filter(CodexModelCatalogProbe.Model::isDefault)
                            .findFirst()
                            .orElse(models.getFirst())
                            .id();
                }
            }
            WorkModelCatalog.CatalogAgent agent = WorkModelCatalog.agent(engine);
            if (agent != null) {
                return agent.defaultModel().id();
            }
        }
        else {
            WorkModelCatalog.CatalogProvider provider = WorkModelCatalog.provider(engine);
            if (provider != null) {
                return provider.defaultModel().id();
            }
        }
        throw new IllegalArgumentException("No default model for " + kind + " engine " + engine);
    }

    private String freezeAccount(String provider, String requested)
    {
        if (requested != null && !requested.isBlank()) {
            String account = requested.strip();
            return credentials.get(CredentialType.AI, provider, account)
                    .map(Credential::instanceName)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No " + provider + " API account named " + account));
        }
        return credentials.getDefault(CredentialType.AI, provider)
                .or(() -> credentials.get(CredentialType.AI, provider))
                .map(Credential::instanceName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No " + provider + " API account is configured"));
    }

    private static boolean isLocalModel(String providerId, String modelId)
    {
        WorkModelCatalog.CatalogProvider provider = WorkModelCatalog.provider(providerId);
        return provider != null && provider.models().stream()
                .anyMatch(model -> model.id().equals(modelId) && model.localServed());
    }

    private static String requireValue(String value, String label)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.strip();
    }

    private static boolean cliAvailable(String agentId)
    {
        String binary = "codex".equals(agentId) ? "codex" : "claude";
        try {
            Process process = new ProcessBuilder(binary, "--version")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(CLI_PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        }
        catch (Exception ignored) {
            return false;
        }
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

    private static List<WorkModelEntry> toCodexEntries(List<CodexModelCatalogProbe.Model> models)
    {
        return models.stream()
                .map(m -> new WorkModelEntry(
                        m.id(),
                        m.displayName(),
                        m.isDefault(),
                        m.description(),
                        m.defaultReasoningEffort(),
                        m.supportedReasoningEfforts().stream()
                                .map(e -> new WorkModelReasoningEffort(e.id(), e.description()))
                                .collect(toImmutableList())))
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
