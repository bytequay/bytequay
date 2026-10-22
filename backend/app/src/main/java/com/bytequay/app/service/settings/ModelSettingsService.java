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

import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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
 * <p>CLI catalogs live in the settings store. Startup and background settings
 * refreshes probe each available driver ({@code model/list} for Codex) and rewrite its catalog row;
 * Claude Code cannot enumerate models, so its row is seeded with the CLI
 * aliases ({@code opus}, {@code fable}, ...). The isolated DeepSeek key lane
 * uses ByteQuay's fixed two-model catalog. Model ids are the values providers
 * accept on session start; display names resolve through the catalog.
 */
@Service
public class ModelSettingsService
{
    public static final String WORKSPACE_AGENT_MODEL_PREFIX = "workspace.agentModel.";

    private static final Logger log = LoggerFactory.getLogger(ModelSettingsService.class);

    private static final String AGENT_MODEL_PREFIX = "model.agent.";
    private static final String CATALOG_PREFIX = "model.catalog.";
    private static final String API_KEY_ID_PREFIX = "key:";
    private static final String DEEPSEEK_LANE_PREFIX = "deepseek-key-";
    private static final Duration REFRESH_INTERVAL = Duration.ofMinutes(5);
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
    private static final List<ModelOption> DEEPSEEK_MODELS = ImmutableList.of(
            new ModelOption("deepseek-flash", "DeepSeek Flash", "Fast coding model", ImmutableList.of("low", "high"), "high"),
            new ModelOption("deepseek-v4-pro", "DeepSeek V4 Pro", "Complex coding model", ImmutableList.of("low", "high"), "high"));

    private static final Map<String, List<String>> KEY_PROVIDER_MODELS = ImmutableMap.of(
            "DeepSeek", ImmutableList.of("deepseek-flash", "deepseek-v4-pro"),
            "OpenAI", ImmutableList.of("gpt-5.2", "gpt-5.2-mini", "gpt-5.1"));

    private final AgentDriverRegistry drivers;
    private final ModelApiKeyStore apiKeys;
    private final ClaudeAccounts claudeAccounts;
    private final AppSettingsStore settings;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AtomicReference<List<AgentProviderStatus>> providerStatuses = new AtomicReference<>(ImmutableList.of());
    private volatile Optional<Instant> checkedAt = Optional.empty();

    public ModelSettingsService(AgentDriverRegistry drivers, ModelApiKeyStore apiKeys, ClaudeAccounts claudeAccounts, AppSettingsStore settings, ObjectMapper mapper, Clock clock)
    {
        this.drivers = requireNonNull(drivers, "drivers is null");
        this.apiKeys = requireNonNull(apiKeys, "apiKeys is null");
        this.claudeAccounts = requireNonNull(claudeAccounts, "claudeAccounts is null");
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
        providerStatuses.set(ImmutableList.copyOf(statuses));
        // Takes statuses the caller already probed; re-probing to decide whether to probe is waste.
        refreshCatalogs(statuses, true);
    }

    /**
     * Re-probes every available provider's model catalog and rewrites its
     * stored row. A failed or empty probe keeps the stored catalog.
     */
    public void refreshCatalogs()
    {
        refreshCatalogs(true);
    }

    /** Settings reads never wait for CLI or network probes; only refresh requests enter this lock. */
    public synchronized void refreshCatalogs(boolean force)
    {
        if (!force && checkedAt.filter(at -> clock.instant().isBefore(at.plus(REFRESH_INTERVAL))).isPresent()) {
            return;
        }
        List<AgentProviderStatus> statuses = drivers.statuses();
        providerStatuses.set(ImmutableList.copyOf(statuses));
        refreshCatalogs(statuses, false);
        checkedAt = Optional.of(clock.instant());
    }

    /**
     * Checks a lane just added by an account or key and keeps every other provider's last result, so adding one waits on its
     * own check instead of every provider's CLI. Account and key lanes take their models from stored data, not this check.
     */
    public void checkNewLane(AgentLaneId laneId)
    {
        AgentProviderStatus status;
        try {
            status = drivers.require(laneId).probe();
        }
        catch (RuntimeException failure) {
            // Provider errors can include HTTP response bodies; never echo them into settings.
            status = new AgentProviderStatus(laneId, AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE, Optional.empty(), "Could not be checked. Try again.",
                    AgentCapabilities.none());
        }
        AgentProviderStatus checked = status;
        providerStatuses.updateAndGet(statuses -> Stream.concat(statuses.stream().filter(value -> !value.laneId().equals(laneId)), Stream.of(checked))
                .collect(toImmutableList()));
    }

    private void refreshCatalogs(List<AgentProviderStatus> statuses, boolean onlyMissing)
    {
        for (AgentProviderStatus status : statuses) {
            if (!status.available()) {
                continue;
            }
            String id = status.laneId().value();
            if (id.startsWith(DEEPSEEK_LANE_PREFIX)) {
                // Its two-model catalog is ByteQuay-owned and needs no App Server probe per saved key.
                continue;
            }
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
            writeJson(CATALOG_PREFIX + id, new StoredCatalog(status.version().orElse(""), defaultModelId, options));
        }
    }

    public ModelSettings get()
    {
        return get(Optional.empty());
    }

    public ModelSettings get(Optional<String> workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        List<Provider> providers = providerStatuses.get().stream()
                .filter(status -> !status.laneId().value().startsWith(AgentLaneId.CLAUDE_ACCOUNT_PREFIX)
                        || claudeAccounts.find(status.laneId().value()).filter(account -> account.availableIn(workspaceId)).isPresent())
                .filter(status -> (!status.laneId().value().startsWith(DEEPSEEK_LANE_PREFIX)
                        && !status.laneId().value().startsWith(AgentLaneId.CLAUDE_ACCOUNT_PREFIX))
                        || cliCatalog(status.laneId().value()).isPresent())
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
        return new ModelSettings(agents, providers, keys, keyProviders, checkedAt);
    }

    /** Whether the last provider check, the one Settings shows, found this lane usable under the policy. */
    public boolean lastCheckedReady(AgentLaneId laneId, AgentPermissionPolicy policy)
    {
        return providerStatuses.get().stream()
                .anyMatch(status -> status.laneId().equals(laneId) && status.available() && status.capabilities().permissionPolicies().contains(policy));
    }

    public void validateWorkspaceAccess(String configId, Optional<String> workspaceId)
    {
        claudeAccounts.checkWorkspaceAccess(configId, workspaceId);
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
     * Refuses images for a model that does not advertise image input, so the catalog gates the
     * turn the same way it gates the composer's attach button.
     */
    public void requireImageInput(AgentLaneId laneId, Optional<String> selectedModel, List<String> mediaTypes)
    {
        String model = selectedModel.orElse("");
        boolean modelAccepted = get().providers().stream()
                .filter(provider -> provider.id().equals(laneId.value()))
                .flatMap(provider -> provider.models().stream())
                .anyMatch(option -> option.id().equals(model) && option.acceptsImages());
        if (!modelAccepted) {
            throw new InvalidAgentRequestException("Model " + model + " does not accept images");
        }
        var supported = drivers.require(laneId).capabilities().attachmentMediaTypes();
        mediaTypes.stream()
                .filter(mediaType -> !supported.contains(mediaType))
                .findFirst()
                .ifPresent(mediaType -> {
                    throw new InvalidAgentRequestException("Provider " + laneId + " does not accept " + mediaType + " attachments");
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
            return new AgentModelSetting(type, false, development.configId(), development.label(), development.model(), development.modelLabel(), development.effort());
        }
        return catalogDefault(type);
    }

    /** The model one agent type runs with in one workspace: its override, else the global value. */
    public AgentModelSetting agentModel(AgentModelType type, Optional<String> workspaceId)
    {
        requireNonNull(type, "type is null");
        AgentModelSetting selected = requireNonNull(workspaceId, "workspaceId is null")
                .flatMap(id -> readAgentModel(workspaceKey(id, type)))
                .map(stored -> resolve(type, true, stored))
                .orElseGet(() -> {
                    AgentModelSetting global = agentModel(type);
                    return new AgentModelSetting(
                            type, workspaceId.isEmpty() && global.explicit(), global.configId(), global.label(), global.model(), global.modelLabel(), global.effort());
                });
        if (!claudeAccounts.availableIn(selected.configId(), workspaceId)) {
            return new AgentModelSetting(type, selected.explicit(), selected.configId(), "Claude account unavailable", selected.model(), selected.modelLabel(), selected.effort());
        }
        return selected;
    }

    public AgentModelSetting setAgentModel(AgentModelType type, String configId, String model, Optional<String> effort)
    {
        requireNonNull(type, "type is null");
        validateWorkspaceAccess(configId, Optional.empty());
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

    public ModelSettings addApiKey(String provider, String alias, String secret)
    {
        if (!KEY_PROVIDER_MODELS.containsKey(requireNonNull(provider, "provider is null"))) {
            throw new IllegalArgumentException("unknown API key provider: " + provider);
        }
        String trimmedAlias = requireNonNull(alias, "alias is null").trim();
        if (!ALIAS.matcher(trimmedAlias).matches()) {
            throw new IllegalArgumentException("Aliases use letters, digits, dots, dashes, or underscores.");
        }
        if (apiKeys.findAll().stream().anyMatch(key -> key.alias().equals(trimmedAlias))) {
            throw new IllegalArgumentException("An API key named " + trimmedAlias + " already exists.");
        }
        ModelApiKey key = new ModelApiKey(trimmedAlias, provider, SecretMasker.mask(secret), clock.instant());
        apiKeys.save(key, secret);
        // Only DeepSeek keys get an agent lane; the other providers have nothing to check.
        if (provider.equals("DeepSeek")) {
            checkNewLane(new AgentLaneId(DEEPSEEK_LANE_PREFIX + key.id()));
        }
        return get();
    }

    public ModelSettings removeApiKey(String alias)
    {
        apiKeys.delete(requireNonNull(alias, "alias is null"));
        return get();
    }

    public AgentModelSetting setWorkspaceOverride(String workspaceId, AgentModelType type, String configId, String model, Optional<String> effort)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(type, "type is null");
        validateWorkspaceAccess(configId, Optional.of(workspaceId));
        requireAgentSelection(configId, model, effort);
        writeJson(workspaceKey(workspaceId, type), new StoredOverride(configId, model, effort.orElse(null)));
        return agentModel(type, Optional.of(workspaceId));
    }

    @Transactional
    public ClaudeAccount addClaudeAccount(String label, String email, Optional<String> workspaceId)
    {
        ClaudeAccount account = claudeAccounts.add(label, email, workspaceId);
        workspaceId.ifPresent(id -> useClaudeAccount(id, account.laneId().value()));
        return account;
    }

    @Transactional
    public void restrictClaudeAccount(String laneId, String workspaceId)
    {
        claudeAccounts.restrictToWorkspace(laneId, workspaceId);
        useClaudeAccount(workspaceId, laneId);
    }

    private void useClaudeAccount(String workspaceId, String laneId)
    {
        Catalog catalog = requireCatalog(laneId);
        for (AgentModelType type : AgentModelType.values()) {
            AgentModelSetting current = agentModel(type, Optional.of(workspaceId));
            ModelOption model = catalog.model(current.model()).orElseGet(() -> catalog.model(catalog.defaultModelId()).orElseThrow());
            Optional<String> effort = current.effort().filter(model.efforts()::contains).or(() -> Optional.ofNullable(model.defaultEffort()));
            setWorkspaceOverride(workspaceId, type, laneId, model.id(), effort);
        }
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
        String laneId = defaultLaneId();
        Catalog catalog = cliCatalog(laneId).orElseGet(() -> new Catalog(laneLabel(laneId), ImmutableList.of(), ""));
        Selection selection = defaultSelection(catalog);
        return new AgentModelSetting(type, false, laneId, catalog.label(), selection.model(), catalog.modelLabel(selection.model()), Optional.ofNullable(selection.effort()));
    }

    /**
     * The lane a fresh install runs with: the first provider the registry
     * reports as available and able to name a model, in the registry's own
     * display order. Naming one lane here is what made a machine with only
     * Codex signed in default to a Claude lane it could not start.
     *
     * <p>Falls back to the seeded Claude catalog when nothing is available, so
     * an install with no signed-in provider still answers with a model rather
     * than a blank row. Saved-key lanes and named Claude accounts need
     * an explicit choice: signing one in must not redirect unrelated repositories.
     */
    private String defaultLaneId()
    {
        return providerStatuses.get().stream()
                .filter(AgentProviderStatus::available)
                .map(status -> status.laneId().value())
                .filter(laneId -> !laneId.startsWith(DEEPSEEK_LANE_PREFIX))
                .filter(laneId -> !laneId.startsWith(AgentLaneId.CLAUDE_ACCOUNT_PREFIX))
                .filter(laneId -> cliCatalog(laneId).filter(catalog -> !catalog.models().isEmpty()).isPresent())
                .findFirst()
                .orElse(CLAUDE_LANE);
    }

    private AgentModelSetting resolve(AgentModelType type, boolean explicit, StoredOverride stored)
    {
        Optional<Catalog> catalog = catalog(stored.configId());
        return new AgentModelSetting(
                type,
                explicit,
                stored.configId(),
                catalog.map(Catalog::label).orElseGet(() -> stored.configId().startsWith(AgentLaneId.CLAUDE_ACCOUNT_PREFIX)
                        ? "Claude account unavailable" : stored.configId()),
                stored.model(),
                catalog.map(value -> value.modelLabel(stored.model())).orElse(stored.model()),
                Optional.ofNullable(stored.effort()));
    }

    private Optional<StoredOverride> readAgentModel(String key)
    {
        return settings.get(key).flatMap(value -> readJson(value, StoredOverride.class));
    }

    /**
     * Saved keys without an execution driver cannot run agent work.
     */
    private void requireAgentSelection(String configId, String model, Optional<String> effort)
    {
        requireSelection(configId, model, effort);
        if (configId.startsWith(API_KEY_ID_PREFIX)) {
            throw new IllegalArgumentException("Choose a provider with an agent execution driver.");
        }
    }

    private Provider provider(AgentProviderStatus status)
    {
        String id = status.laneId().value();
        Catalog catalog = cliCatalog(id)
                .orElseGet(() -> new Catalog(laneLabel(id), ImmutableList.of(), ""));
        Selection selection = defaultSelection(catalog);
        return new Provider(
                id,
                catalog.label(),
                claudeAccounts.find(id).flatMap(ClaudeAccount::workspaceId),
                status.readiness(),
                status.version(),
                status.diagnostic(),
                status.signInCommand(),
                status.capabilities(),
                catalog.models(),
                selection.model(),
                selection.effort() == null ? "" : selection.effort());
    }

    private ApiKey apiKey(ModelApiKey key)
    {
        String id = API_KEY_ID_PREFIX + key.alias();
        List<String> models = KEY_PROVIDER_MODELS.getOrDefault(key.provider(), ImmutableList.of());
        Selection selection = defaultSelection(catalog(id).orElseThrow());
        return new ApiKey(id, key.alias(), key.provider(), key.maskedHint(), key.createdAt(), models, selection.model());
    }

    private static Selection defaultSelection(Catalog catalog)
    {
        return new Selection(catalog.defaultModelId(), catalog.model(catalog.defaultModelId()).map(ModelOption::defaultEffort).orElse(null));
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
                    return new Catalog(key.provider() + " · " + key.alias(), models, models.isEmpty() ? "" : models.getFirst().id());
                });
    }

    /** The stored catalog row for a CLI lane; absent until seeded or probed. */
    private Optional<Catalog> cliCatalog(String laneId)
    {
        if (laneId.startsWith(AgentLaneId.CLAUDE_ACCOUNT_PREFIX)) {
            try {
                String label = drivers.require(new AgentLaneId(laneId)).label();
                return storedCatalog(CLAUDE_LANE).map(stored -> new Catalog(label, stored.models(), stored.defaultModelId()));
            }
            catch (RuntimeException unavailable) {
                return Optional.empty();
            }
        }
        if (laneId.startsWith(DEEPSEEK_LANE_PREFIX)) {
            String keyId = laneId.substring(DEEPSEEK_LANE_PREFIX.length());
            return apiKeys.findById(keyId)
                    .filter(key -> key.provider().equals("DeepSeek"))
                    .map(key -> new Catalog("DeepSeek · " + key.alias(), DEEPSEEK_MODELS, DEEPSEEK_MODELS.getFirst().id()));
        }
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
        return settings.get(CATALOG_PREFIX + laneId).flatMap(value -> readJson(value, StoredCatalog.class));
    }

    /** A corrupt row reads as absent so one bad setting cannot fail every surface that asks. */
    private <T> Optional<T> readJson(String value, Class<T> type)
    {
        try {
            return Optional.of(mapper.readValue(value, type));
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
            List<KeyProvider> keyProviders,
            Optional<Instant> checkedAt) {}

    public record Provider(
            String id,
            String label,
            Optional<String> workspaceId,
            AgentProviderStatus.Readiness readiness,
            Optional<String> version,
            String diagnostic,
            Optional<String> signInCommand,
            AgentCapabilities capabilities,
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
