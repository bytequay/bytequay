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
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentProviderStatus.Readiness;
import com.bytequay.agent.spi.ProviderModel;
import com.bytequay.agent.spi.ProviderServiceTier;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.service.settings.ModelSettingsService.ModelOption;
import com.bytequay.app.service.settings.ModelSettingsService.ModelSettings;
import com.bytequay.core.domain.ModelApiKey;
import com.bytequay.core.store.ModelApiKeyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestModelSettingsService
{
    private static final AgentLaneId CODEX = new AgentLaneId("codex");

    private AgentDriverRegistry drivers;
    private AgentDriver codexDriver;
    private InMemoryAppSettingsStore settingsStore;
    private InMemoryModelApiKeyStore apiKeys;
    private ModelSettingsService service;
    private Clock clock;

    @BeforeEach
    void setUp()
    {
        drivers = mock(AgentDriverRegistry.class);
        when(drivers.statuses()).thenReturn(ImmutableList.of(
                new AgentProviderStatus(
                        new AgentLaneId("claude"), Readiness.READY,
                        Optional.of("2.1.4"), "ready", AgentCapabilities.none()),
                new AgentProviderStatus(
                        CODEX, Readiness.READY,
                        Optional.of("0.145.0"), "ready", AgentCapabilities.none())));
        // The claude driver cannot enumerate models; the mock's empty default
        // list stands in for that. Codex reports a live catalog.
        when(drivers.require(new AgentLaneId("claude"))).thenReturn(mock(AgentDriver.class));
        codexDriver = mock(AgentDriver.class);
        when(codexDriver.models()).thenReturn(ImmutableList.of(
                new ProviderModel(
                        "gpt-5.6-sol", "GPT-5.6-Sol", "Latest frontier agentic coding model.",
                        ImmutableList.of("low", "medium", "high", "xhigh", "max", "ultra"),
                        Optional.of("low"), true, ImmutableList.of("text", "image"),
                        ImmutableList.of(new ProviderServiceTier("priority", "Fast", "Faster processing"))),
                new ProviderModel(
                        "gpt-5.6-luna", "GPT-5.6-Luna", "Fast and affordable agentic coding model.",
                        ImmutableList.of("low", "medium", "high", "xhigh", "max"),
                        Optional.of("medium"), false, ImmutableList.of("text"), ImmutableList.of())));
        when(drivers.require(CODEX)).thenReturn(codexDriver);
        settingsStore = new InMemoryAppSettingsStore();
        apiKeys = new InMemoryModelApiKeyStore();
        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.parse("2026-08-23T10:00:00Z"));
        service = new ModelSettingsService(
                drivers,
                apiKeys,
                settingsStore,
                new ObjectMapper(),
                clock);
        service.seedCatalogs();
        service.refreshCatalogs();
    }

    @Test
    void bothAgentTypesDefaultToTheClaudeCatalogWithSeededAndProbedCatalogs()
    {
        ModelSettings settings = service.get();
        assertThat(settings.agents()).extracting(AgentModelSetting::type)
                .containsExactly(AgentModelType.DEVELOPMENT, AgentModelType.REVIEW);
        assertThat(settings.agents()).allSatisfy(agent -> {
            assertThat(agent.explicit()).isFalse();
            assertThat(agent.configId()).isEqualTo("claude");
            assertThat(agent.model()).isEqualTo("opus");
        });
        var claude = settings.providers().getFirst();
        assertThat(claude.readiness()).isEqualTo(Readiness.READY);
        assertThat(claude.selectedModel()).isEqualTo("opus");
        assertThat(claude.selectedEffort()).isEqualTo("High");
        var codex = settings.providers().get(1);
        assertThat(codex.readiness()).isEqualTo(Readiness.READY);
        assertThat(codex.models()).extracting(ModelOption::id)
                .containsExactly("gpt-5.6-sol", "gpt-5.6-luna");
        assertThat(codex.selectedModel()).isEqualTo("gpt-5.6-sol");
        assertThat(codex.selectedEffort()).isEqualTo("low");
    }

    @Test
    void validatesOnlyTheServiceTiersAdvertisedForTheSelectedModel()
    {
        assertThat(service.get().providers().get(1).models().getFirst().serviceTiers())
                .containsExactly(new ProviderServiceTier("priority", "Fast", "Faster processing"));
        service.validateSelection("codex", "gpt-5.6-sol", Optional.of("high"), Optional.of("priority"));
        service.validateSelection("codex", "gpt-5.6-sol", Optional.of("low"), Optional.empty());
        assertThatThrownBy(() -> service.validateSelection("codex", "gpt-5.6-luna", Optional.of("high"), Optional.of("priority")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown service tier");
        assertThatThrownBy(() -> service.validateSelection("codex", "gpt-5.6-sol", Optional.of("invalid"), Optional.of("priority")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown effort");
    }

    @Test
    void projectsNormalizedLaneCapabilitiesWithoutProviderBranches()
    {
        AgentCapabilities capabilities = new AgentCapabilities(
                true,
                true,
                true,
                ImmutableSet.of(AgentPermissionPolicy.AUTO, AgentPermissionPolicy.CI_AUTOFIX),
                ImmutableSet.of("image/png", "image/jpeg"));
        when(drivers.statuses()).thenReturn(ImmutableList.of(new AgentProviderStatus(
                new AgentLaneId("claude"),
                Readiness.READY,
                Optional.of("2.1.259"),
                "ready",
                capabilities)));

        service.refreshMissingCatalogs(drivers.statuses());
        var provider = service.get().providers().getFirst();

        assertThat(provider.capabilities()).isEqualTo(capabilities);
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(provider);
        assertThat(json.at("/capabilities/structuredPlans").asBoolean()).isTrue();
        assertThat(json.at("/capabilities/nestedAgentActivity").asBoolean()).isTrue();
        assertThat(json.at("/capabilities/permissionPolicies")).hasSize(2);
        assertThat(json.at("/capabilities/attachmentMediaTypes")).hasSize(2);
    }

    @Test
    void anAgentModelIsValidatedAgainstTheCatalog()
    {
        service.setAgentModel(AgentModelType.DEVELOPMENT, "claude", "sonnet", Optional.of("Max"));
        AgentModelSetting development = service.agentModel(AgentModelType.DEVELOPMENT);
        assertThat(development.model()).isEqualTo("sonnet");
        assertThat(development.effort()).contains("Max");

        assertThatThrownBy(() -> service.setAgentModel(AgentModelType.DEVELOPMENT, "claude", "gpt-5.2", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setAgentModel(AgentModelType.DEVELOPMENT, "claude", "opus", Optional.of("Turbo")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setAgentModel(AgentModelType.REVIEW, "codex", "gpt-5.6-luna", Optional.of("ultra")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDeepSeekKeyHasACuratedTaskCatalogButCannotBeAnAgentDefault()
    {
        service.addApiKey("DeepSeek", "personal-deepseek", "sk-4fabcdefghijklmnope2a1");
        ModelSettings settings = service.get();
        var key = settings.apiKeys().getFirst();
        assertThat(key.maskedKey()).doesNotContain("abcdefgh").contains("••••");
        assertThat(key.selectedModel()).isEqualTo("deepseek-flash");
        AgentLaneId lane = new AgentLaneId("deepseek-key-" + apiKeys.findAll().getFirst().id());
        when(drivers.statuses()).thenReturn(ImmutableList.of(
                new AgentProviderStatus(lane, Readiness.READY, Optional.of("0.156.1"), "ready", AgentCapabilities.none()),
                new AgentProviderStatus(new AgentLaneId("claude"), Readiness.READY, Optional.of("2.1.4"), "ready", AgentCapabilities.none())));
        AgentDriver deepSeek = mock(AgentDriver.class);
        when(deepSeek.label()).thenReturn("DeepSeek · personal-deepseek");
        when(drivers.require(lane)).thenReturn(deepSeek);

        service.refreshMissingCatalogs(drivers.statuses());
        var provider = service.get().providers().getFirst();
        assertThat(provider.models()).extracting(ModelOption::id)
                .containsExactly("deepseek-flash", "deepseek-v4-pro");
        assertThat(provider.selectedModel()).isEqualTo("deepseek-flash");
        service.validateSelection(lane.value(), "deepseek-v4-pro", Optional.of("high"), Optional.empty());
        assertThat(service.agentModel(AgentModelType.DEVELOPMENT).configId()).isEqualTo("claude");

        assertThatThrownBy(() -> service.setAgentModel(
                AgentModelType.DEVELOPMENT, lane.value(), "deepseek-flash", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CLI provider");
        assertThatThrownBy(() -> service.setWorkspaceOverride(
                "ws-1", AgentModelType.REVIEW, "key:personal-deepseek", "deepseek-flash", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reviewInheritsDevelopmentUntilItIsChosenAndClearingRestoresInheritance()
    {
        service.setAgentModel(AgentModelType.DEVELOPMENT, "codex", "gpt-5.6-sol", Optional.of("high"));

        AgentModelSetting review = service.agentModel(AgentModelType.REVIEW);
        assertThat(review.explicit()).isFalse();
        assertThat(review.configId()).isEqualTo("codex");
        assertThat(review.model()).isEqualTo("gpt-5.6-sol");
        assertThat(review.effort()).contains("high");

        service.setAgentModel(AgentModelType.REVIEW, "claude", "sonnet", Optional.of("Max"));
        AgentModelSetting chosen = service.agentModel(AgentModelType.REVIEW);
        assertThat(chosen.explicit()).isTrue();
        assertThat(chosen.model()).isEqualTo("sonnet");
        assertThat(chosen.modelLabel()).isEqualTo("Sonnet");
        assertThat(service.agentModel(AgentModelType.DEVELOPMENT).model()).isEqualTo("gpt-5.6-sol");

        assertThat(service.clearAgentModel(AgentModelType.REVIEW).model()).isEqualTo("gpt-5.6-sol");
    }

    @Test
    void unknownProvidersAndDuplicateAliasesAreRejected()
    {
        service.addApiKey("OpenAI", "team", "sk-secret");
        assertThatThrownBy(() -> service.addApiKey("OpenAI", "team", "sk-other"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addApiKey("Acme", "acme", "sk-secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setAgentModel(AgentModelType.DEVELOPMENT, "missing", "opus", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aWorkspaceOverrideAppliesToOneTypeInOneWorkspace()
    {
        assertThat(service.agentModel(AgentModelType.DEVELOPMENT, Optional.of("ws-1")).explicit()).isFalse();

        service.setWorkspaceOverride("ws-1", AgentModelType.DEVELOPMENT, "codex", "gpt-5.6-sol", Optional.of("xhigh"));

        AgentModelSetting override = service.agentModel(AgentModelType.DEVELOPMENT, Optional.of("ws-1"));
        assertThat(override.explicit()).isTrue();
        assertThat(override.model()).isEqualTo("gpt-5.6-sol");
        assertThat(override.modelLabel()).isEqualTo("GPT-5.6-Sol");
        assertThat(override.effort()).contains("xhigh");
        // The other type in the same workspace, and the same type elsewhere, are untouched.
        assertThat(service.agentModel(AgentModelType.REVIEW, Optional.of("ws-1")).explicit()).isFalse();
        assertThat(service.agentModel(AgentModelType.DEVELOPMENT, Optional.of("ws-2")).explicit()).isFalse();
        assertThat(service.agentModel(AgentModelType.DEVELOPMENT).explicit()).isFalse();

        assertThatThrownBy(() -> service.setWorkspaceOverride("ws-1", AgentModelType.DEVELOPMENT, "codex", "opus", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(service.clearWorkspaceOverride("ws-1", AgentModelType.DEVELOPMENT).explicit()).isFalse();
    }

    @Test
    void aGoalUsesItsOwnModelElseItsWorkspacesDevelopmentModel()
    {
        service.setWorkspaceOverride("ws-1", AgentModelType.DEVELOPMENT, "codex", "gpt-5.6-sol", Optional.of("xhigh"));
        assertThat(service.goalModel("goal-1", "ws-1")).extracting(AgentModelSetting::model, AgentModelSetting::explicit).containsExactly("gpt-5.6-sol", false);
        assertThat(service.goalModel("goal-1", "ws-1").effort()).contains("xhigh");

        AgentModelSetting chosen = service.setGoalModel("goal-1", "ws-1", "codex", "gpt-5.6-sol", Optional.of("low"));
        assertThat(chosen.explicit()).isTrue();
        assertThat(chosen.effort()).as("a Goal can chat at a lower effort than its workspace develops at").contains("low");
        assertThat(service.goalModel("goal-2", "ws-1").effort()).contains("xhigh");
        assertThatThrownBy(() -> service.setGoalModel("goal-1", "ws-1", "codex", "opus", Optional.empty())).isInstanceOf(IllegalArgumentException.class);

        assertThat(service.clearGoalModel("goal-1", "ws-1")).extracting(setting -> setting.effort().orElseThrow(), AgentModelSetting::explicit)
                .containsExactly("xhigh", false);
    }

    @Test
    void workspaceDevelopmentProviderOverridesGlobalAndClearingRestoresIt()
    {
        service.setAgentModel(AgentModelType.DEVELOPMENT, "claude", "sonnet", Optional.of("High"));
        service.setWorkspaceOverride("ws-1", AgentModelType.DEVELOPMENT, "codex", "gpt-5.6-sol", Optional.of("xhigh"));

        assertThat(service.agentModel(AgentModelType.DEVELOPMENT, Optional.of("ws-1")))
                .extracting(AgentModelSetting::configId, AgentModelSetting::model).containsExactly("codex", "gpt-5.6-sol");
        assertThat(service.agentModel(AgentModelType.DEVELOPMENT, Optional.of("ws-2")))
                .extracting(AgentModelSetting::configId, AgentModelSetting::model).containsExactly("claude", "sonnet");
        assertThat(service.agentModel(AgentModelType.DEVELOPMENT))
                .extracting(AgentModelSetting::configId, AgentModelSetting::model).containsExactly("claude", "sonnet");

        assertThat(service.clearWorkspaceOverride("ws-1", AgentModelType.DEVELOPMENT))
                .extracting(AgentModelSetting::configId, AgentModelSetting::model).containsExactly("claude", "sonnet");
    }

    @Test
    void aMissingCatalogStillAnswersInsteadOfFailingEverySurface()
    {
        ModelSettingsService fresh = new ModelSettingsService(
                drivers,
                new InMemoryModelApiKeyStore(),
                new InMemoryAppSettingsStore(),
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-23T10:00:00Z"), ZoneOffset.UTC));

        AgentModelSetting development = fresh.agentModel(AgentModelType.DEVELOPMENT);

        assertThat(development.configId()).isEqualTo("claude");
        assertThat(development.explicit()).isFalse();
    }

    @Test
    void theDefaultSkipsAProviderThatIsNotSignedIn()
    {
        when(drivers.statuses()).thenReturn(ImmutableList.of(
                new AgentProviderStatus(
                        new AgentLaneId("claude"), Readiness.AUTHENTICATION_REQUIRED,
                        Optional.of("2.1.4"), "sign in required", AgentCapabilities.none()),
                new AgentProviderStatus(
                        CODEX, Readiness.READY,
                        Optional.of("0.145.0"), "ready", AgentCapabilities.none())));

        service.refreshMissingCatalogs(drivers.statuses());
        assertThat(service.get().agents()).allSatisfy(agent -> {
            assertThat(agent.explicit()).isFalse();
            assertThat(agent.configId()).isEqualTo("codex");
            assertThat(agent.model()).isEqualTo("gpt-5.6-sol");
        });
    }

    @Test
    void theDefaultFallsBackToTheSeededLaneWhenNothingIsSignedIn()
    {
        when(drivers.statuses()).thenReturn(ImmutableList.of(
                new AgentProviderStatus(
                        new AgentLaneId("claude"), Readiness.AUTHENTICATION_REQUIRED,
                        Optional.of("2.1.4"), "sign in required", AgentCapabilities.none()),
                new AgentProviderStatus(
                        CODEX, Readiness.CLI_MISSING,
                        Optional.empty(), "not installed", AgentCapabilities.none())));

        service.refreshMissingCatalogs(drivers.statuses());
        assertThat(service.agentModel(AgentModelType.DEVELOPMENT).configId()).isEqualTo("claude");
    }

    @Test
    void readingAndSavingSettingsNeverLaunchesProviderChecks()
    {
        clearInvocations(drivers, codexDriver);
        service.get();
        service.get(Optional.of("ws-1"));
        service.agentModel(AgentModelType.DEVELOPMENT);
        service.setAgentModel(AgentModelType.DEVELOPMENT, "codex", "gpt-5.6-sol", Optional.of("high"));
        service.get();

        verify(drivers, never()).statuses();
        verify(codexDriver, never()).models();
    }

    @Test
    void automaticRefreshReusesRecentChecksThenDiscoversNewModels()
    {
        clearInvocations(drivers, codexDriver);
        service.refreshCatalogs(false);
        verify(drivers, never()).statuses();
        verify(codexDriver, never()).models();

        when(clock.instant()).thenReturn(Instant.parse("2026-08-23T10:06:00Z"));
        when(codexDriver.models()).thenReturn(ImmutableList.of(
                new ProviderModel("gpt-6.1-sol", "GPT-6.1 Sol", "", ImmutableList.of("high"), Optional.of("high"), true,
                        ImmutableList.of("text", "image"), ImmutableList.of())));
        service.refreshCatalogs(false);

        verify(drivers).statuses();
        verify(codexDriver).models();
        assertThat(service.get().providers().get(1).models()).extracting(ModelOption::id).containsExactly("gpt-6.1-sol");
        assertThat(service.get().checkedAt()).contains(Instant.parse("2026-08-23T10:06:00Z"));
    }

    @Test
    void refreshRewritesTheStoredCatalogOnEveryRun()
    {
        when(codexDriver.models()).thenReturn(ImmutableList.of(
                new ProviderModel(
                        "gpt-6-sol", "GPT-6-Sol", "Next frontier model.",
                        ImmutableList.of("low", "high"), Optional.of("low"), true, ImmutableList.of("text"), ImmutableList.of())));
        service.refreshCatalogs();

        var codex = service.get().providers().get(1);
        assertThat(codex.models()).extracting(ModelOption::id).containsExactly("gpt-6-sol");
        assertThat(codex.selectedModel()).isEqualTo("gpt-6-sol");
        assertThat(codex.selectedEffort()).isEqualTo("low");
    }

    @Test
    void fillsOnlyAMissingCatalogSoAFixedProviderNeedsNoRestart()
    {
        ModelSettingsService fresh = new ModelSettingsService(
                drivers,
                new InMemoryModelApiKeyStore(),
                new InMemoryAppSettingsStore(),
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-23T10:00:00Z"), ZoneOffset.UTC));
        when(codexDriver.models()).thenReturn(ImmutableList.of(
                new ProviderModel(
                        "gpt-6-sol", "GPT-6-Sol", "Next frontier model.",
                        ImmutableList.of("low", "high"), Optional.of("low"), true, ImmutableList.of("text"), ImmutableList.of())));

        // A provider that became usable after startup has no catalog; this is what fills it.
        fresh.refreshMissingCatalogs(drivers.statuses());
        assertThat(fresh.get().providers().get(1).models()).extracting(ModelOption::id)
                .containsExactly("gpt-6-sol");

        // A provider that already has one is not probed again, so a status read stays cheap.
        reset(codexDriver);
        fresh.refreshMissingCatalogs(drivers.statuses());
        verify(codexDriver, never()).models();
    }

    @Test
    void probeFailureKeepsTheStoredCatalog()
    {
        when(drivers.require(CODEX)).thenThrow(new IllegalStateException("codex is gone"));
        service.refreshCatalogs();

        var codex = service.get().providers().get(1);
        assertThat(codex.selectedModel()).isEqualTo("gpt-5.6-sol");
    }

    @Test
    void newerSeedVersionReplacesTheStoredClaudeCatalog()
    {
        InMemoryAppSettingsStore store = new InMemoryAppSettingsStore();
        store.put(
                "model.catalog.claude",
                "{\"version\":\"seed-1\",\"defaultModelId\":\"opus\",\"models\":"
                        + "[{\"id\":\"opus\",\"name\":\"Opus 5\",\"description\":\"\","
                        + "\"efforts\":[\"High\"],\"defaultEffort\":\"High\"}]}");
        ModelSettingsService upgraded = new ModelSettingsService(
                drivers,
                new InMemoryModelApiKeyStore(),
                store,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-23T10:00:00Z"), ZoneOffset.UTC));
        upgraded.seedCatalogs();
        upgraded.refreshCatalogs();

        var claude = upgraded.get().providers().getFirst();
        assertThat(claude.models()).extracting(ModelOption::name)
                .containsExactly("Fable", "Opus", "Sonnet", "Haiku");
    }

    @Test
    void catalogsComeOnlyFromTheStore()
    {
        ModelSettingsService unseeded = new ModelSettingsService(
                drivers,
                new InMemoryModelApiKeyStore(),
                new InMemoryAppSettingsStore(),
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-23T10:00:00Z"), ZoneOffset.UTC));
        unseeded.refreshCatalogs();
        var claude = unseeded.get().providers().getFirst();
        assertThat(claude.models()).isEmpty();
        assertThat(claude.selectedModel()).isEmpty();
    }

    private static final class InMemoryModelApiKeyStore
            implements ModelApiKeyStore
    {
        private final List<ModelApiKey> keys = new ArrayList<>();
        private final List<String> secrets = new ArrayList<>();

        @Override
        public List<ModelApiKey> findAll()
        {
            return ImmutableList.copyOf(keys);
        }

        @Override
        public Optional<ModelApiKey> findById(String id)
        {
            return keys.stream().filter(key -> key.id().equals(id)).findFirst();
        }

        @Override
        public Optional<String> findSecretById(String id)
        {
            for (int i = 0; i < keys.size(); i++) {
                if (keys.get(i).id().equals(id)) {
                    return Optional.of(secrets.get(i));
                }
            }
            return Optional.empty();
        }

        @Override
        public void save(ModelApiKey key, String secret)
        {
            keys.add(key);
            secrets.add(secret);
        }

        @Override
        public void delete(String alias)
        {
            for (int i = 0; i < keys.size(); i++) {
                if (keys.get(i).alias().equals(alias)) {
                    keys.remove(i);
                    secrets.remove(i);
                    return;
                }
            }
        }
    }
}
