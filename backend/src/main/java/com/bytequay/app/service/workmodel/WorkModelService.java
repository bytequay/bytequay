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
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
    /** Long enough that a per-agent-run engine resolution never forks a
     *  process, short enough that installing a CLI shows up without a
     *  restart. */
    private static final Duration CLI_PROBE_CACHE = Duration.ofSeconds(60);

    private final CredentialService credentials;
    private final CodexModelCatalogProbe codexModels;
    private final Map<String, Supplier<Boolean>> cliProbes;

    public WorkModelService(CredentialService credentials, CodexModelCatalogProbe codexModels)
    {
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.codexModels = requireNonNull(codexModels, "codexModels is null");
        ImmutableMap.Builder<String, Supplier<Boolean>> probes = ImmutableMap.builder();
        for (WorkModelCatalog.CatalogAgent agent : WorkModelCatalog.CLI_AGENTS) {
            String id = agent.id();
            probes.put(id, Suppliers.memoizeWithExpiration(
                    () -> probeCli(id),
                    CLI_PROBE_CACHE.toMillis(),
                    TimeUnit.MILLISECONDS));
        }
        this.cliProbes = probes.buildOrThrow();
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

    /**
     * What this machine could run, in preference order, for a caller that has
     * no configured pick at all: installed CLI agents in catalog order, then
     * API providers holding a credential. Entries carry engine identity only —
     * pass one through {@link #freeze} to fill in model and account.
     *
     * <p>Order is the offer, not the verdict. A caller whose transport cannot
     * run a CLI agent filters these itself rather than having discovery guess
     * on its behalf.
     */
    public List<WorkModel> discoverEngines()
    {
        ImmutableList.Builder<WorkModel> out = ImmutableList.builder();
        for (WorkModelCatalog.CatalogAgent agent : WorkModelCatalog.CLI_AGENTS) {
            if (cliAvailable(agent.id())) {
                out.add(new WorkModel(WorkModelKind.CLI, agent.id(), null, null, null));
            }
        }
        for (WorkModelCatalog.CatalogProvider provider : WorkModelCatalog.API_PROVIDERS) {
            if (!credentials.listByTypeAndName(CredentialType.AI, provider.id()).isEmpty()) {
                out.add(new WorkModel(WorkModelKind.API, provider.id(), null, null, null));
            }
        }
        return out.build();
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
                    toEntries(agent.id(), agent.models())));
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

    /** True for model variants served by a local subprocess rather than the
     *  provider's cloud endpoint, which need a different transport. */
    public static boolean isLocalModel(String providerId, String modelId)
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

    /** Cached: engine resolution asks this once per agent run, and forking a
     *  process inside that path would put a 2s timeout on every launch. */
    boolean cliAvailable(String agentId)
    {
        Supplier<Boolean> probe = cliProbes.get(agentId);
        return probe != null && probe.get();
    }

    private static boolean probeCli(String agentId)
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
                    toEntries(provider.id(), provider.models()),
                    toAccounts(accounts)));
        }
        return out.build();
    }

    private static List<WorkModelEntry> toEntries(
            String engine,
            List<WorkModelCatalog.CatalogEntry> models)
    {
        return models.stream()
                .map(model -> entry(engine, model))
                .collect(toImmutableList());
    }

    /**
     * The reasoning-effort ladder one model accepts. Claude and Codex do not
     * share a vocabulary — {@code minimal} exists only on Codex, {@code xhigh}
     * and {@code max} only on Claude — and within Claude the ladder narrows by
     * model family. An engine or model with no native effort control returns
     * empty, which callers read as "send no effort at all".
     */
    static List<String> catalogEfforts(String engine, String modelId)
    {
        return switch (engine) {
            case "claude-code", "anthropic" -> modelId.contains("haiku")
                    ? List.of()
                    : modelId.contains("sonnet")
                            ? List.of("low", "medium", "high", "max")
                            : List.of("low", "medium", "high", "xhigh", "max");
            case "codex", "openai" -> modelId.startsWith("gpt-5")
                    ? List.of("minimal", "low", "medium", "high")
                    : List.of();
            default -> List.of();
        };
    }

    private static String catalogDefaultEffort(String engine, List<String> efforts)
    {
        if (efforts.isEmpty()) {
            return null;
        }
        return engine.equals("codex") || engine.equals("openai")
                ? "medium" : "high";
    }

    /**
     * The effort this engine/model pair should actually run at: the requested
     * value when the model accepts it, otherwise the model's own default.
     * Never throws — a stored effort that outlived a model change must not
     * fail the turn that reads it, and a model with no effort ladder yields
     * {@code null} so the transport omits the flag entirely.
     *
     * <p>Codex publishes a live per-model ladder through its app-server
     * catalog, so that answer wins over the curated one when available.
     */
    public String resolveEffort(
            WorkModelKind kind, String engine, String modelId, String requested)
    {
        requireNonNull(kind, "kind is null");
        requireNonNull(engine, "engine is null");
        requireNonNull(modelId, "modelId is null");
        List<String> efforts = null;
        String fallback = null;
        if (kind == WorkModelKind.CLI && "codex".equals(engine)) {
            var discovered = codexModels.models(false);
            if (discovered.isPresent()) {
                var live = discovered.orElseThrow().stream()
                        .filter(model -> model.id().equals(modelId))
                        .findFirst();
                if (live.isPresent()) {
                    efforts = live.orElseThrow().supportedReasoningEfforts().stream()
                            .map(CodexModelCatalogProbe.ReasoningEffort::id)
                            .collect(toImmutableList());
                    fallback = live.orElseThrow().defaultReasoningEffort();
                }
            }
        }
        if (efforts == null) {
            efforts = catalogEfforts(engine, modelId);
            fallback = catalogDefaultEffort(engine, efforts);
        }
        if (efforts.isEmpty()) {
            return null;
        }
        String normalized = requested == null || requested.isBlank()
                ? null : requested.strip().toLowerCase(Locale.ROOT);
        return normalized != null && efforts.contains(normalized)
                ? normalized
                : (efforts.contains(fallback) ? fallback : null);
    }

    private static WorkModelEntry entry(
            String engine,
            WorkModelCatalog.CatalogEntry model)
    {
        List<String> efforts = catalogEfforts(engine, model.id());
        String defaultEffort = catalogDefaultEffort(engine, efforts);
        return new WorkModelEntry(
                model.id(),
                model.displayName(),
                model.isDefault(),
                null,
                defaultEffort,
                efforts.stream()
                        .map(effort -> new WorkModelReasoningEffort(
                                effort, effortDescription(effort)))
                        .collect(toImmutableList()));
    }

    private static String effortDescription(String effort)
    {
        return switch (effort) {
            case "minimal" -> "Fastest response with minimal reasoning";
            case "low" -> "Faster response with lighter reasoning";
            case "medium" -> "Balanced speed and reasoning";
            case "high" -> "Deeper reasoning";
            case "xhigh" -> "Very deep reasoning";
            case "max" -> "Maximum available reasoning";
            default -> effort;
        };
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
