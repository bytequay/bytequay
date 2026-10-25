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
package com.bytequay.app.web;

import com.bytequay.agent.core.AutomationLevel;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.app.service.settings.AppPreferences;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.AppTheme;
import com.bytequay.app.service.settings.ClaudeAccount;
import com.bytequay.app.service.settings.ClaudeAccounts;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.service.settings.QuietHours;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestSettingsController
{
    private static final ClaudeAccount WORK = new ClaudeAccount("3fc79e41-f2c8-4d04-b4fc-bc6e658f4e56", "Work", Optional.of("work@example.com"), Optional.empty());

    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final AppSettingsService settings = mock(AppSettingsService.class);
    private final ClaudeAccounts claudeAccounts = mock(ClaudeAccounts.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final SettingsController controller = new SettingsController(models, settings, claudeAccounts, workspaces);

    /** Boot's own serialization settings, so the test asserts the wire format the renderer parses. */
    private static ObjectMapper appMapper()
    {
        return Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    private MockMvc mvc()
    {
        return standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(appMapper()))
                .build();
    }

    @Test
    void removingAClaudeAccountReturnsItsChoicesWithoutCheckingProviders()
            throws Exception
    {
        mvc().perform(delete("/api/settings/claude-accounts/" + WORK.id())).andExpect(status().isOk());
        verify(claudeAccounts).remove(WORK.id());
        verify(models, never()).refreshCatalogs();
        verify(models).get();
    }

    @Test
    void addingAClaudeAccountChecksOnlyItsOwnLane()
            throws Exception
    {
        when(models.addClaudeAccount("Work", "work@example.com", Optional.empty())).thenReturn(WORK);
        mvc().perform(post("/api/settings/claude-accounts").contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Work\",\"email\":\"work@example.com\"}"))
                .andExpect(status().isOk());
        verify(models).checkNewLane(WORK.laneId());
        verify(models, never()).refreshCatalogs();
        verify(models).get(Optional.empty());
    }

    @Test
    void accountCreationAndRestrictionBindToTheRequestedWorkspace()
            throws Exception
    {
        when(models.addClaudeAccount(any(), any(), any())).thenReturn(WORK);
        when(workspaces.findById("downstream")).thenReturn(Optional.of(mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE))));
        mvc().perform(post("/api/settings/claude-accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Work\",\"email\":\"work@example.com\",\"workspaceId\":\"downstream\"}"))
                .andExpect(status().isOk());
        verify(models).addClaudeAccount("Work", "work@example.com", Optional.of("downstream"));
        mvc().perform(put("/api/settings/claude-accounts/account-id/workspace").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspaceId\":\"downstream\"}"))
                .andExpect(status().isOk());
        verify(models).restrictClaudeAccount("claude-account-account-id", "downstream");
    }

    @Test
    void addingAClaudeAccountRequiresAnEmail()
            throws Exception
    {
        mvc().perform(post("/api/settings/claude-accounts").contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Work\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(claudeAccounts, models);
    }

    @Test
    void modelRefreshDistinguishesAutomaticAndForcedChecks()
            throws Exception
    {
        mvc().perform(post("/api/settings/models/refresh")).andExpect(status().isOk());
        verify(models).refreshCatalogs(false);
        mvc().perform(post("/api/settings/models/refresh").param("force", "true")).andExpect(status().isOk());
        verify(models).refreshCatalogs(true);
    }

    @Test
    void automationUsesOneLevelAndStringLaneIds()
            throws Exception
    {
        when(settings.developmentAutomation()).thenReturn(DevelopmentAutomation.DEFAULT);
        DevelopmentAutomation selected = new DevelopmentAutomation(true, AutomationLevel.CI_FIX, Optional.of(AgentLaneId.CLAUDE_CLI));
        when(settings.saveDevelopmentAutomation(selected)).thenReturn(selected);
        mvc().perform(get("/api/settings/development-automation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoCommit").doesNotExist())
                .andExpect(jsonPath("$.level").value("COMMIT"))
                .andExpect(jsonPath("$.ciLaneId").isEmpty());
        mvc().perform(put("/api/settings/development-automation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"CI_FIX\",\"ciLaneId\":\"claude\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoCommit").doesNotExist())
                .andExpect(jsonPath("$.level").value("CI_FIX"))
                .andExpect(jsonPath("$.ciLaneId").value("claude"));
        mvc().perform(put("/api/settings/development-automation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ciLaneId\":\"claude\"}"))
                .andExpect(status().isBadRequest());
        mvc().perform(put("/api/settings/development-automation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"EVERYTHING\",\"ciLaneId\":null}"))
                .andExpect(status().isBadRequest());
        verify(settings).saveDevelopmentAutomation(selected);
    }

    @Test
    void preferencesRoundTripThroughTheSettingsService()
            throws Exception
    {
        when(settings.preferences()).thenReturn(AppPreferences.DEFAULT);
        AppPreferences saved = new AppPreferences(30, 21, AppTheme.LIGHT, 30, 10);
        when(settings.savePreferences(saved)).thenReturn(saved);
        MockMvc mvc = mvc();

        mvc.perform(get("/api/settings/app"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskRetentionDays").value(7))
                .andExpect(jsonPath("$.recentRetentionDays").value(14))
                .andExpect(jsonPath("$.refreshIntervalMinutes").doesNotExist())
                .andExpect(jsonPath("$.theme").value("SYSTEM"))
                .andExpect(jsonPath("$.prWarmCount").value(30))
                .andExpect(jsonPath("$.autoMergeDelayMinutes").value(60));
        mvc.perform(put("/api/settings/app").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskRetentionDays\":30,\"recentRetentionDays\":21,\"theme\":\"LIGHT\",\"prWarmCount\":30,\"autoMergeDelayMinutes\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskRetentionDays").value(30))
                .andExpect(jsonPath("$.theme").value("LIGHT"));

        verify(settings).savePreferences(saved);
    }

    @Test
    void anOutOfRangePreferenceIsRejectedBeforeItReachesTheStore()
            throws Exception
    {
        MockMvc mvc = mvc();

        mvc.perform(put("/api/settings/app").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskRetentionDays\":0,\"recentRetentionDays\":14,\"theme\":\"SYSTEM\",\"prWarmCount\":30}"))
                .andExpect(status().isBadRequest());

        verify(settings, never()).savePreferences(any());
    }

    @Test
    void quietHoursAreServedAndSaved()
            throws Exception
    {
        when(settings.quietHours()).thenReturn(QuietHours.DEFAULT);
        QuietHours evening = new QuietHours(true, 1320, 420);
        when(settings.saveQuietHours(evening)).thenReturn(evening);
        MockMvc mvc = mvc();

        mvc.perform(get("/api/settings/quiet-hours"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.startMinute").value(60));
        mvc.perform(put("/api/settings/quiet-hours").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"startMinute\":1320,\"endMinute\":420}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }
}
