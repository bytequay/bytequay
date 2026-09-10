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

import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.ProviderModel;
import com.bytequay.agent.spi.ProviderServiceTier;
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
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Model and credential configuration behind Settings: the model and effort
 * each agent type runs with, stored API keys, and per-workspace, per-type
 * overrides.
 *
 * <p>Resolution runs workspace override, then the global value for that agent
 * type, then — for Review only — the Development value, then the provider
 * catalog's own default. Every one of those fallbacks is applied here and
 * never written back, so "Review is the same as Development" stays an absent
 * row rather than a frozen copy.
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

    private static final String AGENT_MODEL_PREFIX = "model.agent.";
    /** Read once by {@link #migrateLegacyMainModel()} and then deleted; nothing else consults it. */
    private static final String LEGACY_MAIN_KEY = "model.main";
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
     * Probes only providers that are available and have no catalog yet.
     *
     * <p>A Codex catalog exists only after a successful probe, and the startup refresh skips a
     * provider that was not yet installed or signed in. Without this, fixing the provider leaves
     * the app with no models until it is restarted, and every surface that needs one stays dead
     * while reporting the provider as ready.
     */
    public void refreshMissingCatalogs(List<AgentProviderStatus> statuses)
    {
        // Takes statuses the caller already probed; re-probing to decide whether to probe is waste.
        refreshCatalogs(statuses, true);
    }

    /**
     * Re-probes every available provider's model catalog and rewrites its
     * stored row. A failed or empty probe keeps the stored catalog.
     */
    public void refreshCatalogs()
    {
        refreshCatalogs(drivers.statuses(), false);
    }

    private void refreshCatalogs(List<AgentProviderStatus> statuses, boolean onlyMissing)
    {
        for (AgentProviderStatus status : statuses) {
            if (!status.available()) {
                continue;
            }
            String id = status.laneId().value();
            if (onlyMissing && storedCatalog(id).isPresent()) {
                continue;
            }
            List<ProviderModel> models;
            try {
                models = drivers.require(status.laneId()).models();
            }
            catch (RuntimeException unavailable) {
                log.warn("Model catalog probe failed for {} {}: {}", id, status.version().orElse("unknown"), unavailable.getMessage());
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
                            model.defaultEffort().orElse(null),
                            model.inputModalities(),
                            model.serviceTiers()))
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
        return get(Optional.empty());
    }

    public ModelSettings get(Optional<String> workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        List<Provider> providers = drivers.statuses().stream()
                .map(this::provider)
                .collect(toImmutableList());
        List<ApiKey> keys = apiKeys.findAll().stream()
                .map(this::apiKey)
                .collect(toImmutableList());
        List<KeyProvider> keyProviders = KEY_PROVIDER_MODELS.entrySet().stream()
                .map(entry -> new KeyProvider(entry.getKey(), entry.getValue()))
                .collect(toImmutableList());
        List<AgentModelSetting> agents = Stream.of(AgentModelType.values())
                .map(type -> agentModel(type, workspaceId))
                .collect(toImmutableList());
        return new ModelSettings(agents, providers, keys, keyProviders);
    }

    public void select(String configId, String model, Optional<String> effort)
    {
        requireSelection(configId, model, effort);
        writeJson(SELECTION_PREFIX + configId, new Selection(model, effort.orElse(null)));
    }

    /** Validates a provider model selection without changing saved settings. */
    public void validateSelection(String configId, String model, Optional<String> effort, Optional<String> serviceTier)
    {
        ModelOption option = requireSelection(configId, model, effort);
        requireNonNull(serviceTier, "serviceTier is null").ifPresent(value -> {
            if (option.serviceTiers().stream().noneMatch(tier -> tier.id().equals(value))) {
                throw new IllegalArgumentException("unknown service tier for " + configId + ": " + value);
            }
        });
    }

    /**
     * The model one agent type runs with, with no workspace in play.
     *
     * <p>Falls back through the Development value and then the lane catalog's
     * own default, so a fresh install answers before anything has been chosen.
     */
    public AgentModelSetting agentModel(AgentModelType type)
    {
        requireNonNull(type, "type is null");
        Optional<StoredOverride> stored = readAgentModel(AGENT_MODEL_PREFIX + type.name());
        if (stored.isPresent()) {
            return resolve(type, true, stored.orElseThrow());
        }
        if (type == AgentModelType.REVIEW) {
            AgentModelSetting development = agentModel(AgentModelType.DEVELOPMENT);
            return new AgentModelSetting(
                    type,
                    false,
                    development.configId(),
                    development.label(),
                    development.model(),
                    development.modelLabel(),
                    development.effort());
        }
        return catalogDefault(type);
    }

    /** The model one agent type runs with in one workspace: its override, else the global value. */
    public AgentModelSetting agentModel(AgentModelType type, Optional<String> workspaceId)
    {
        requireNonNull(type, "type is null");
        return requireNonNull(workspaceId, "workspaceId is null")
                .flatMap(id -> readAgentModel(workspaceKey(id, type)))
                .map(stored -> resolve(type, true, stored))
                .orElseGet(() -> {
                    AgentModelSetting global = agentModel(type);
                    return new AgentModelSetting(
                            type, false, global.configId(), global.label(), global.model(), global.modelLabel(), global.effort());
                });
    }

    public AgentModelSetting setAgentModel(AgentModelType type, String configId, String model, Optional<String> effort)
    {
        requireNonNull(type, "type is null");
        requireAgentSelection(configId, model, effort);
        writeJson(AGENT_MODEL_PREFIX + type.name(), new StoredOverride(configId, model, effort.orElse(null)));
        return agentModel(type);
    }

    /** Clears the stored value so the type inherits again. */
    public AgentModelSetting clearAgentModel(AgentModelType type)
    {
        settings.delete(AGENT_MODEL_PREFIX + requireNonNull(type, "type is null").name());
        return agentModel(type);
    }

    /**
     * Moves a pre-agent-type {@code model.main} into the Development value and
     * removes it. Review is deliberately left absent so it starts out
     * inheriting. Runs once at startup; a second call is a no-op.
     */
    public void migrateLegacyMainModel()
    {
        Optional<String> legacy = settings.get(LEGACY_MAIN_KEY);
        if (legacy.isEmpty()) {
            return;
        }
        String configId = legacy.orElseThrow();
        if (settings.get(AGENT_MODEL_PREFIX + AgentModelType.DEVELOPMENT.name()).isEmpty()) {
            catalog(configId)
                    .map(catalog -> selection(configId, catalog))
                    .ifPresent(selection -> writeJson(
                            AGENT_MODEL_PREFIX + AgentModelType.DEVELOPMENT.name(),
                            new StoredOverride(configId, selection.model(), selection.effort())));
        }
        settings.delete(LEGACY_MAIN_KEY);
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
        apiKeys.save(new ModelApiKey(trimmedAlias, provider, SecretMasker.mask(secret), clock.instant()), secret);
        return get();
    }

    public ModelSettings removeApiKey(String alias)
    {
        String id = API_KEY_ID_PREFIX + requireNonNull(alias, "alias is null");
        apiKeys.delete(alias);
        settings.delete(SELECTION_PREFIX + id);
        return get();
    }

    public AgentModelSetting setWorkspaceOverride(
            String workspaceId,
            AgentModelType type,
            String configId,
            String model,
            Optional<String> effort)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(type, "type is null");
        requireAgentSelection(configId, model, effort);
        writeJson(workspaceKey(workspaceId, type), new StoredOverride(configId, model, effort.orElse(null)));
        return agentModel(type, Optional.of(workspaceId));
    }

    public AgentModelSetting clearWorkspaceOverride(String workspaceId, AgentModelType type)
    {
        settings.delete(workspaceKey(requireNonNull(workspaceId, "workspaceId is null"), requireNonNull(type, "type is null")));
        return agentModel(type, Optional.of(workspaceId));
    }

    /** Drops every per-type override for a workspace being deleted. */
    public void clearWorkspaceOverrides(String workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        for (AgentModelType type : AgentModelType.values()) {
            settings.delete(workspaceKey(workspaceId, type));
        }
    }

    private static String workspaceKey(String workspaceId, AgentModelType type)
    {
        return WORKSPACE_AGENT_MODEL_PREFIX + workspaceId + "." + type.name();
    }

    /**
     * The lane catalog's own default, used before anything has been chosen.
     * Tolerates a lane whose catalog row has not been seeded or probed yet, so
     * a fresh database answers instead of failing every surface that asks.
     */
    private AgentModelSetting catalogDefault(AgentModelType type)
    {
        Catalog catalog = cliCatalog(CLAUDE_LANE).orElseGet(() -> new Catalog(laneLabel(CLAUDE_LANE), ImmutableList.of(), ""));
        Selection selection = selection(CLAUDE_LANE, catalog);
        return new AgentModelSetting(
                type,
                false,
                CLAUDE_LANE,
                catalog.label(),
                selection.model(),
                catalog.modelLabel(selection.model()),
                Optional.ofNullable(selection.effort()));
    }

    private AgentModelSetting resolve(AgentModelType type, boolean explicit, StoredOverride stored)
    {
        Optional<Catalog> catalog = catalog(stored.configId());
        return new AgentModelSetting(
                type,
                explicit,
                stored.configId(),
                catalog.map(Catalog::label).orElse(stored.configId()),
                stored.model(),
                catalog.map(value -> value.modelLabel(stored.model())).orElse(stored.model()),
                Optional.ofNullable(stored.effort()));
    }

    private Optional<StoredOverride> readAgentModel(String key)
    {
        return settings.get(key).flatMap(this::readOverride);
    }

    /**
     * Validates a model an agent type may actually run with. An API key is not
     * a lane — {@link AgentLaneId} admits no {@code :} — so no agent driver can
     * execute a turn against one, and accepting it here is what used to make a
     * review start throw.
     */
    private void requireAgentSelection(String configId, String model, Optional<String> effort)
    {
        requireSelection(configId, model, effort);
        if (configId.startsWith(API_KEY_ID_PREFIX)) {
            throw new IllegalArgumentException(
                    "An API key cannot run agent tasks; choose a CLI provider's model.");
        }
    }

    private Provider provider(AgentProviderStatus status)
    {
        String id = status.laneId().value();
        Catalog catalog = cliCatalog(id)
                .orElseGet(() -> new Catalog(laneLabel(id), ImmutableList.of(), ""));
        Selection selection = selection(id, catalog);
        return new Provider(
                id,
                catalog.label(),
                status.readiness(),
                status.version(),
                status.diagnostic(),
                status.signInCommand(),
                status.capabilities(),
                status.capabilities().steering(),
                catalog.models(),
                selection.model(),
                selection.effort() == null ? "" : selection.effort());
    }

    private ApiKey apiKey(ModelApiKey key)
    {
        String id = API_KEY_ID_PREFIX + key.alias();
        List<String> models = KEY_PROVIDER_MODELS.getOrDefault(key.provider(), ImmutableList.of());
        Selection selection = selection(id, catalog(id).orElseThrow());
        return new ApiKey(
                id,
                key.alias(),
                key.provider(),
                key.maskedHint(),
                key.createdAt(),
                models,
                selection.model());
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

    private ModelOption requireSelection(String configId, String model, Optional<String> effort)
    {
        requireNonNull(effort, "effort is null");
        ModelOption option = requireCatalog(configId)
                .model(requireNonNull(model, "model is null"))
                .orElseThrow(() -> new IllegalArgumentException("unknown model for " + configId + ": " + model));
        effort.ifPresent(value -> {
            if (!option.efforts().contains(value)) {
                throw new IllegalArgumentException("unknown effort for " + configId + ": " + value);
            }
        });
        return option;
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

    /** A lane's display name, falling back to its id so an unregistered or unlabelled driver cannot blank a settings row. */
    private String laneLabel(String laneId)
    {
        try {
            return Optional.ofNullable(drivers.require(new AgentLaneId(laneId)).label()).orElse(laneId);
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
            List<AgentModelSetting> agents,
            List<Provider> providers,
            List<ApiKey> apiKeys,
            List<KeyProvider> keyProviders) {}

    public record Provider(
            String id,
            String label,
            AgentProviderStatus.Readiness readiness,
            Optional<String> version,
            String diagnostic,
            Optional<String> signInCommand,
            AgentCapabilities capabilities,
            /** Whether a running turn on this lane accepts more input. */
            boolean steering,
            List<ModelOption> models,
            String selectedModel,
            String selectedEffort) {}

    public record ApiKey(
            String id,
            String alias,
            String provider,
            String maskedKey,
            java.time.Instant createdAt,
            List<String> models,
            String selectedModel) {}

    public record KeyProvider(String name, List<String> models) {}

    public record ModelOption(
            String id,
            String name,
            String description,
            List<String> efforts,
            String defaultEffort,
            List<String> inputModalities,
            List<ProviderServiceTier> serviceTiers)
    {
        public ModelOption
        {
            serviceTiers = serviceTiers == null ? ImmutableList.of() : ImmutableList.copyOf(serviceTiers);
        }

        public ModelOption(String id, String name, String description, List<String> efforts, String defaultEffort, List<String> inputModalities)
        {
            this(id, name, description, efforts, defaultEffort, inputModalities, ImmutableList.of());
        }

        /** A text-only model. */
        public ModelOption(String id, String name, String description, List<String> efforts, String defaultEffort)
        {
            this(id, name, description, efforts, defaultEffort, ImmutableList.of("text"));
        }

        public boolean acceptsImages()
        {
            return inputModalities.contains("image");
        }
    }

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
