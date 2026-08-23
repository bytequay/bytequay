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
package com.bytequay.app.service.settings;

import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.app.service.credentials.SecretMasker;
import com.bytequay.core.domain.ModelApiKey;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.ModelApiKeyStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Model and credential configuration behind global Settings: per-provider
 * model and effort selection, stored API keys, exactly one main model, and
 * per-workspace agent-model overrides.
 */
@Service
public class ModelSettingsService
{
    public static final String WORKSPACE_AGENT_MODEL_PREFIX = "workspace.agentModel.";

    private static final String MAIN_KEY = "model.main";
    private static final String SELECTION_PREFIX = "model.selection.";
    private static final String API_KEY_ID_PREFIX = "key:";
    private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    // ponytail: static catalogs until drivers report their own model lists;
    // move into the agent SPI when a driver actually declares one.
    private static final Map<String, ProviderCatalog> CLI_CATALOGS = ImmutableMap.of(
            "claude",
            new ProviderCatalog(
                    "Claude Code",
                    ImmutableList.of(
                            new ModelOption("Fable 5", "For your toughest challenges"),
                            new ModelOption("Opus 5", "For complex tasks"),
                            new ModelOption("Sonnet 5", "Most efficient for everyday tasks"),
                            new ModelOption("Haiku 4.5", "Fastest for quick answers")),
                    ImmutableList.of("Low", "Medium", "High", "Extra", "Max"),
                    "Opus 5",
                    "High"),
            "codex",
            new ProviderCatalog(
                    "Codex",
                    ImmutableList.of(
                            new ModelOption("5.6 Sol", ""),
                            new ModelOption("5.6 Terra", ""),
                            new ModelOption("5.6 Luna", ""),
                            new ModelOption("5.5", ""),
                            new ModelOption("5.4", ""),
                            new ModelOption("5.4 Mini", ""),
                            new ModelOption("5.3 Codex Spark", "")),
                    ImmutableList.of("Low", "Medium", "High", "Extra High"),
                    "5.6 Sol",
                    "Extra High"));

    private static final Map<String, List<String>> KEY_PROVIDER_MODELS = ImmutableMap.of(
            "DeepSeek", ImmutableList.of("deepseek-chat", "deepseek-reasoner"),
            "OpenAI", ImmutableList.of("gpt-5.2", "gpt-5.2-mini", "gpt-5.1"));

    private final AgentDriverRegistry drivers;
    private final ModelApiKeyStore apiKeys;
    private final AppSettingsStore settings;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ModelSettingsService(
            AgentDriverRegistry drivers,
            ModelApiKeyStore apiKeys,
            AppSettingsStore settings,
            ObjectMapper mapper,
            Clock clock)
    {
        this.drivers = requireNonNull(drivers, "drivers is null");
        this.apiKeys = requireNonNull(apiKeys, "apiKeys is null");
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public ModelSettings get()
    {
        String mainId = mainId();
        List<Provider> providers = drivers.statuses().stream()
                .map(status -> provider(status, mainId))
                .collect(toImmutableList());
        List<ApiKey> keys = apiKeys.findAll().stream()
                .map(key -> apiKey(key, mainId))
                .collect(toImmutableList());
        List<KeyProvider> keyProviders = KEY_PROVIDER_MODELS.entrySet().stream()
                .map(entry -> new KeyProvider(entry.getKey(), entry.getValue()))
                .collect(toImmutableList());
        return new ModelSettings(mainId, providers, keys, keyProviders);
    }

    public void select(String configId, String model, Optional<String> effort)
    {
        requireNonNull(effort, "effort is null");
        Catalog catalog = requireCatalog(configId);
        if (!catalog.modelNames().contains(requireNonNull(model, "model is null"))) {
            throw new IllegalArgumentException("unknown model for " + configId + ": " + model);
        }
        effort.ifPresent(value -> {
            if (!catalog.efforts().contains(value)) {
                throw new IllegalArgumentException("unknown effort for " + configId + ": " + value);
            }
        });
        writeJson(SELECTION_PREFIX + configId, new Selection(model, effort.orElse(null)));
    }

    public void setMain(String configId)
    {
        requireCatalog(configId);
        settings.put(MAIN_KEY, configId);
    }

    public ModelSettings addApiKey(String provider, String alias, String secret)
    {
        if (!KEY_PROVIDER_MODELS.containsKey(requireNonNull(provider, "provider is null"))) {
            throw new IllegalArgumentException("unknown API key provider: " + provider);
        }
        String trimmedAlias = requireNonNull(alias, "alias is null").trim();
        if (!ALIAS.matcher(trimmedAlias).matches()) {
            throw new IllegalArgumentException(
                    "Aliases use letters, digits, dots, dashes, or underscores.");
        }
        if (apiKeys.findAll().stream().anyMatch(key -> key.alias().equals(trimmedAlias))) {
            throw new IllegalArgumentException("An API key named " + trimmedAlias + " already exists.");
        }
        apiKeys.save(new ModelApiKey(trimmedAlias, provider, clock.instant()), secret);
        return get();
    }

    public ModelSettings removeApiKey(String alias)
    {
        String id = API_KEY_ID_PREFIX + requireNonNull(alias, "alias is null");
        apiKeys.delete(alias);
        settings.delete(SELECTION_PREFIX + id);
        if (id.equals(mainId())) {
            settings.delete(MAIN_KEY);
        }
        return get();
    }

    /** The current main model, for surfaces that display the effective default. */
    public MainModel mainModel()
    {
        String mainId = mainId();
        Catalog catalog = requireCatalog(mainId);
        Selection selection = selection(mainId, catalog);
        return new MainModel(mainId, catalog.label(), selection.model(), Optional.ofNullable(selection.effort()));
    }

    public Optional<WorkspaceAgentModel> workspaceOverride(String workspaceId)
    {
        return settings.get(WORKSPACE_AGENT_MODEL_PREFIX + requireNonNull(workspaceId, "workspaceId is null"))
                .flatMap(this::readOverride)
                .map(stored -> new WorkspaceAgentModel(
                        stored.configId(),
                        catalog(stored.configId()).map(Catalog::label).orElse(stored.configId()),
                        stored.model(),
                        Optional.ofNullable(stored.effort())));
    }

    public WorkspaceAgentModel setWorkspaceOverride(
            String workspaceId,
            String configId,
            String model,
            Optional<String> effort)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(effort, "effort is null");
        Catalog catalog = requireCatalog(configId);
        if (!catalog.modelNames().contains(requireNonNull(model, "model is null"))) {
            throw new IllegalArgumentException("unknown model for " + configId + ": " + model);
        }
        effort.ifPresent(value -> {
            if (!catalog.efforts().contains(value)) {
                throw new IllegalArgumentException("unknown effort for " + configId + ": " + value);
            }
        });
        writeJson(
                WORKSPACE_AGENT_MODEL_PREFIX + workspaceId,
                new StoredOverride(configId, model, effort.orElse(null)));
        return new WorkspaceAgentModel(configId, catalog.label(), model, effort);
    }

    public void clearWorkspaceOverride(String workspaceId)
    {
        settings.delete(WORKSPACE_AGENT_MODEL_PREFIX + requireNonNull(workspaceId, "workspaceId is null"));
    }

    private Provider provider(AgentProviderStatus status, String mainId)
    {
        String id = status.laneId().value();
        ProviderCatalog catalog = CLI_CATALOGS.getOrDefault(
                id, new ProviderCatalog(id, ImmutableList.of(), ImmutableList.of(), "", ""));
        Selection selection = selection(id, toCatalog(catalog));
        return new Provider(
                id,
                catalog.label(),
                status.health().name(),
                status.version(),
                status.diagnostic(),
                catalog.models(),
                catalog.efforts(),
                selection.model(),
                selection.effort() == null ? catalog.defaultEffort() : selection.effort(),
                id.equals(mainId));
    }

    private ApiKey apiKey(ModelApiKey key, String mainId)
    {
        String id = API_KEY_ID_PREFIX + key.alias();
        List<String> models = KEY_PROVIDER_MODELS.getOrDefault(key.provider(), ImmutableList.of());
        Selection selection = selection(id, catalog(id).orElseThrow());
        return new ApiKey(
                id,
                key.alias(),
                key.provider(),
                apiKeys.findSecret(key.alias()).map(SecretMasker::mask).orElse(""),
                key.createdAt(),
                models,
                selection.model(),
                id.equals(mainId));
    }

    private String mainId()
    {
        return settings.get(MAIN_KEY)
                .filter(id -> catalog(id).isPresent())
                .orElse("claude");
    }

    private Selection selection(String configId, Catalog catalog)
    {
        return settings.get(SELECTION_PREFIX + configId)
                .flatMap(this::readSelection)
                .filter(value -> catalog.modelNames().contains(value.model()))
                .orElseGet(() -> new Selection(catalog.defaultModel(), catalog.defaultEffort()));
    }

    private Catalog requireCatalog(String configId)
    {
        return catalog(configId)
                .orElseThrow(() -> new IllegalArgumentException("unknown model configuration: " + configId));
    }

    private Optional<Catalog> catalog(String configId)
    {
        requireNonNull(configId, "configId is null");
        ProviderCatalog cli = CLI_CATALOGS.get(configId);
        if (cli != null) {
            return Optional.of(toCatalog(cli));
        }
        if (!configId.startsWith(API_KEY_ID_PREFIX)) {
            return Optional.empty();
        }
        String alias = configId.substring(API_KEY_ID_PREFIX.length());
        return apiKeys.findAll().stream()
                .filter(key -> key.alias().equals(alias))
                .findFirst()
                .map(key -> {
                    List<String> models = KEY_PROVIDER_MODELS.getOrDefault(key.provider(), ImmutableList.of());
                    return new Catalog(
                            key.provider() + " · " + key.alias(),
                            models,
                            ImmutableList.of(),
                            models.isEmpty() ? "" : models.getFirst(),
                            null);
                });
    }

    private static Catalog toCatalog(ProviderCatalog catalog)
    {
        return new Catalog(
                catalog.label(),
                catalog.models().stream().map(ModelOption::name).collect(toImmutableList()),
                catalog.efforts(),
                catalog.defaultModel(),
                catalog.defaultEffort());
    }

    private Optional<Selection> readSelection(String value)
    {
        try {
            return Optional.of(mapper.readValue(value, Selection.class));
        }
        catch (JsonProcessingException corrupt) {
            return Optional.empty();
        }
    }

    private Optional<StoredOverride> readOverride(String value)
    {
        try {
            return Optional.of(mapper.readValue(value, StoredOverride.class));
        }
        catch (JsonProcessingException corrupt) {
            return Optional.empty();
        }
    }

    private void writeJson(String key, Object value)
    {
        try {
            settings.put(key, mapper.writeValueAsString(value));
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("setting could not be encoded", failure);
        }
    }

    public record ModelSettings(
            String mainId,
            List<Provider> providers,
            List<ApiKey> apiKeys,
            List<KeyProvider> keyProviders) {}

    public record Provider(
            String id,
            String label,
            String health,
            Optional<String> version,
            String diagnostic,
            List<ModelOption> models,
            List<String> efforts,
            String selectedModel,
            String selectedEffort,
            boolean main) {}

    public record ApiKey(
            String id,
            String alias,
            String provider,
            String maskedKey,
            java.time.Instant createdAt,
            List<String> models,
            String selectedModel,
            boolean main) {}

    public record KeyProvider(String name, List<String> models) {}

    public record ModelOption(String name, String description) {}

    public record MainModel(String configId, String label, String model, Optional<String> effort) {}

    public record WorkspaceAgentModel(String configId, String label, String model, Optional<String> effort) {}

    private record ProviderCatalog(
            String label,
            List<ModelOption> models,
            List<String> efforts,
            String defaultModel,
            String defaultEffort) {}

    private record Catalog(
            String label,
            List<String> modelNames,
            List<String> efforts,
            String defaultModel,
            String defaultEffort) {}

    private record Selection(String model, String effort) {}

    private record StoredOverride(String configId, String model, String effort) {}
}
