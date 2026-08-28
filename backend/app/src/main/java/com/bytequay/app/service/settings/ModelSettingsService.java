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
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.ProviderModel;
import com.bytequay.app.service.credentials.SecretMasker;
import com.bytequay.core.domain.ModelApiKey;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.ModelApiKeyStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>Provider catalogs live in the settings store, never in code. Every app
 * start re-probes each available driver ({@code model/list} for Codex) and
 * rewrites its catalog row; Claude Code cannot enumerate models, so its row
 * is seeded once with the CLI aliases ({@code opus}, {@code fable}, ...) that
 * the CLI itself resolves to the newest model of each tier. Model ids are the
 * stable values providers accept on session start; display names resolve
 * through the catalog.
 */
@Service
public class ModelSettingsService
{
    public static final String WORKSPACE_AGENT_MODEL_PREFIX = "workspace.agentModel.";

    private static final Logger log = LoggerFactory.getLogger(ModelSettingsService.class);

    private static final String MAIN_KEY = "model.main";
    private static final String SELECTION_PREFIX = "model.selection.";
    private static final String CATALOG_PREFIX = "model.catalog.";
    private static final String API_KEY_ID_PREFIX = "key:";
    private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    // Bootstrap data written into the settings store; after that the stored
    // row is the only source the read paths consult. Claude Code has no
    // model-list surface, so aliases are the stable contract — and the
    // display names are the version-free alias tiers, because the CLI
    // resolves each alias to its newest model and a versioned label would go
    // stale the day a new model ships. Bump the seed version to roll a new
    // seed out over an existing row.
    private static final List<String> CLAUDE_EFFORTS = ImmutableList.of("Low", "Medium", "High", "Extra", "Max");
    private static final StoredCatalog CLAUDE_SEED = new StoredCatalog(
            "seed-2",
            "opus",
            ImmutableList.of(
                    new ModelOption("fable", "Fable", "For your toughest challenges", CLAUDE_EFFORTS, "High"),
                    new ModelOption("opus", "Opus", "For complex tasks", CLAUDE_EFFORTS, "High"),
                    new ModelOption("sonnet", "Sonnet", "Most efficient for everyday tasks", CLAUDE_EFFORTS, "High"),
                    new ModelOption("haiku", "Haiku", "Fastest for quick answers", CLAUDE_EFFORTS, "High")));
    private static final String CLAUDE_LANE = "claude";

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

    /**
     * Writes the seeded catalogs for providers that cannot enumerate models.
     * An existing row is rewritten only when this build ships a newer seed
     * version, so probe-free catalogs still pick up app updates.
     */
    public void seedCatalogs()
    {
        boolean current = storedCatalog(CLAUDE_LANE)
                .map(stored -> stored.version().equals(CLAUDE_SEED.version()))
                .orElse(false);
        if (!current) {
            writeJson(CATALOG_PREFIX + CLAUDE_LANE, CLAUDE_SEED);
        }
    }

    /**
     * Re-probes every available provider's model catalog and rewrites its
     * stored row. A failed or empty probe keeps the stored catalog.
     */
    public void refreshCatalogs()
    {
        for (AgentProviderStatus status : drivers.statuses()) {
            if (status.health() == AgentProviderStatus.Health.UNAVAILABLE) {
                continue;
            }
            String id = status.laneId().value();
            List<ProviderModel> models;
            try {
                models = drivers.require(status.laneId()).models();
            }
            catch (RuntimeException unavailable) {
                log.warn("Model catalog probe failed for {}: {}", id, unavailable.getMessage());
                continue;
            }
            if (models == null || models.isEmpty()) {
                continue;
            }
            List<ModelOption> options = models.stream()
                    .map(model -> new ModelOption(
                            model.id(),
                            model.label(),
                            model.description(),
                            model.efforts(),
                            model.defaultEffort().orElse(null)))
                    .collect(toImmutableList());
            String defaultModelId = models.stream()
                    .filter(ProviderModel::isDefault)
                    .findFirst()
                    .orElse(models.getFirst())
                    .id();
            writeJson(
                    CATALOG_PREFIX + id,
                    new StoredCatalog(status.version().orElse(""), defaultModelId, options));
        }
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
        ModelOption option = catalog.model(requireNonNull(model, "model is null"))
                .orElseThrow(() -> new IllegalArgumentException("unknown model for " + configId + ": " + model));
        effort.ifPresent(value -> {
            if (!option.efforts().contains(value)) {
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
        return new MainModel(
                mainId,
                catalog.label(),
                selection.model(),
                catalog.modelLabel(selection.model()),
                Optional.ofNullable(selection.effort()));
    }

    public Optional<WorkspaceAgentModel> workspaceOverride(String workspaceId)
    {
        return settings.get(WORKSPACE_AGENT_MODEL_PREFIX + requireNonNull(workspaceId, "workspaceId is null"))
                .flatMap(this::readOverride)
                .map(stored -> new WorkspaceAgentModel(
                        stored.configId(),
                        catalog(stored.configId()).map(Catalog::label).orElse(stored.configId()),
                        stored.model(),
                        catalog(stored.configId())
                                .map(catalog -> catalog.modelLabel(stored.model()))
                                .orElse(stored.model()),
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
        ModelOption option = catalog.model(requireNonNull(model, "model is null"))
                .orElseThrow(() -> new IllegalArgumentException("unknown model for " + configId + ": " + model));
        effort.ifPresent(value -> {
            if (!option.efforts().contains(value)) {
                throw new IllegalArgumentException("unknown effort for " + configId + ": " + value);
            }
        });
        writeJson(
                WORKSPACE_AGENT_MODEL_PREFIX + workspaceId,
                new StoredOverride(configId, model, effort.orElse(null)));
        return new WorkspaceAgentModel(configId, catalog.label(), model, option.name(), effort);
    }

    public void clearWorkspaceOverride(String workspaceId)
    {
        settings.delete(WORKSPACE_AGENT_MODEL_PREFIX + requireNonNull(workspaceId, "workspaceId is null"));
    }

    private Provider provider(AgentProviderStatus status, String mainId)
    {
        String id = status.laneId().value();
        Catalog catalog = cliCatalog(id)
                .orElseGet(() -> new Catalog(laneLabel(id), ImmutableList.of(), ""));
        Selection selection = selection(id, catalog);
        return new Provider(
                id,
                catalog.label(),
                status.health().name(),
                status.version(),
                status.diagnostic(),
                catalog.models(),
                selection.model(),
                selection.effort() == null ? "" : selection.effort(),
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
                .orElse(CLAUDE_LANE);
    }

    private Selection selection(String configId, Catalog catalog)
    {
        Optional<Selection> stored = settings.get(SELECTION_PREFIX + configId)
                .flatMap(this::readSelection)
                .filter(value -> catalog.model(value.model()).isPresent());
        if (stored.isEmpty()) {
            return new Selection(
                    catalog.defaultModelId(),
                    catalog.model(catalog.defaultModelId()).map(ModelOption::defaultEffort).orElse(null));
        }
        Selection selection = stored.orElseThrow();
        ModelOption model = catalog.model(selection.model()).orElseThrow();
        String effort = selection.effort() != null && model.efforts().contains(selection.effort())
                ? selection.effort()
                : model.defaultEffort();
        return new Selection(selection.model(), effort);
    }

    private Catalog requireCatalog(String configId)
    {
        return catalog(configId)
                .orElseThrow(() -> new IllegalArgumentException("unknown model configuration: " + configId));
    }

    private Optional<Catalog> catalog(String configId)
    {
        requireNonNull(configId, "configId is null");
        if (!configId.startsWith(API_KEY_ID_PREFIX)) {
            return cliCatalog(configId);
        }
        String alias = configId.substring(API_KEY_ID_PREFIX.length());
        return apiKeys.findAll().stream()
                .filter(key -> key.alias().equals(alias))
                .findFirst()
                .map(key -> {
                    List<ModelOption> models = KEY_PROVIDER_MODELS
                            .getOrDefault(key.provider(), ImmutableList.<String>of()).stream()
                            .map(name -> new ModelOption(name, name, "", ImmutableList.of(), null))
                            .collect(toImmutableList());
                    return new Catalog(
                            key.provider() + " · " + key.alias(),
                            models,
                            models.isEmpty() ? "" : models.getFirst().id());
                });
    }

    /** The stored catalog row for a CLI lane; absent until seeded or probed. */
    private Optional<Catalog> cliCatalog(String laneId)
    {
        return storedCatalog(laneId)
                .map(stored -> new Catalog(laneLabel(laneId), stored.models(), stored.defaultModelId()));
    }

    private String laneLabel(String laneId)
    {
        try {
            return drivers.require(new AgentLaneId(laneId)).label();
        }
        catch (RuntimeException unknown) {
            return laneId;
        }
    }

    private Optional<StoredCatalog> storedCatalog(String laneId)
    {
        return settings.get(CATALOG_PREFIX + laneId).flatMap(value -> {
            try {
                return Optional.of(mapper.readValue(value, StoredCatalog.class));
            }
            catch (JsonProcessingException corrupt) {
                return Optional.empty();
            }
        });
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

    public record ModelOption(
            String id,
            String name,
            String description,
            List<String> efforts,
            String defaultEffort) {}

    public record MainModel(
            String configId,
            String label,
            String model,
            String modelLabel,
            Optional<String> effort) {}

    public record WorkspaceAgentModel(
            String configId,
            String label,
            String model,
            String modelLabel,
            Optional<String> effort) {}

    private record StoredCatalog(String version, String defaultModelId, List<ModelOption> models) {}

    private record Catalog(String label, List<ModelOption> models, String defaultModelId)
    {
        Optional<ModelOption> model(String id)
        {
            return models.stream().filter(option -> option.id().equals(id)).findFirst();
        }

        String modelLabel(String id)
        {
            return model(id).map(ModelOption::name).orElse(id);
        }
    }

    private record Selection(String model, String effort) {}

    private record StoredOverride(String configId, String model, String effort) {}
}
