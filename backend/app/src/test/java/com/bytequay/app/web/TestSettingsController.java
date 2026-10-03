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

import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.app.agent.codegraph.CodeGraphBaseline;
import com.bytequay.app.service.settings.AppPreferences;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.AppTheme;
import com.bytequay.app.service.settings.CodeGraphMode;
import com.bytequay.app.service.settings.CodeGraphSetting;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.service.settings.QuietHours;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestSettingsController
{
    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final AppSettingsService settings = mock(AppSettingsService.class);
    private final AtomicReference<CodeGraphBaseline> baseline = new AtomicReference<>(CodeGraphBaseline.NONE);
    private final SettingsController controller = new SettingsController(models, settings, baseline::get);

    /** Boot's own serialization settings, so the test asserts the wire format the renderer parses. */
    private static ObjectMapper appMapper()
    {
        return Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    private MockMvc mvc()
    {
        return standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(appMapper()))
                .build();
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
    void automationUsesExplicitBooleanSettingsAndStringLaneIds()
            throws Exception
    {
        when(settings.developmentAutomation()).thenReturn(DevelopmentAutomation.DEFAULT);
        DevelopmentAutomation selected = new DevelopmentAutomation(true, true, true, Optional.of(AgentLaneId.CLAUDE_CLI), true);
        when(settings.saveDevelopmentAutomation(selected)).thenReturn(selected);
        mvc().perform(get("/api/settings/development-automation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoCommit").doesNotExist())
                .andExpect(jsonPath("$.autoDraftPr").value(true))
                .andExpect(jsonPath("$.autoCiFix").value(true))
                .andExpect(jsonPath("$.ciLaneId").isEmpty())
                .andExpect(jsonPath("$.reviewSummary").value(false));
        mvc().perform(put("/api/settings/development-automation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"autoDraftPr\":true,\"autoCiFix\":true,\"ciLaneId\":\"claude\",\"reviewSummary\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoCommit").doesNotExist())
                .andExpect(jsonPath("$.ciLaneId").value("claude"))
                .andExpect(jsonPath("$.reviewSummary").value(true));
        mvc().perform(put("/api/settings/development-automation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"autoDraftPr\":true}"))
                .andExpect(status().isBadRequest());
        mvc().perform(put("/api/settings/development-automation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"autoDraftPr\":true,\"autoCiFix\":true,\"ciLaneId\":\"claude\"}"))
                .andExpect(status().isBadRequest());
        verify(settings).saveDevelopmentAutomation(selected);
    }

    @Test
    void preferencesRoundTripThroughTheSettingsService()
            throws Exception
    {
        when(settings.preferences()).thenReturn(AppPreferences.DEFAULT);
        AppPreferences saved = new AppPreferences(30, 21, AppTheme.LIGHT, 30);
        when(settings.savePreferences(saved)).thenReturn(saved);
        MockMvc mvc = mvc();

        mvc.perform(get("/api/settings/app"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskRetentionDays").value(7))
                .andExpect(jsonPath("$.recentRetentionDays").value(14))
                .andExpect(jsonPath("$.refreshIntervalMinutes").doesNotExist())
                .andExpect(jsonPath("$.theme").value("SYSTEM"))
                .andExpect(jsonPath("$.prWarmCount").value(30));
        mvc.perform(put("/api/settings/app").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskRetentionDays\":30,\"recentRetentionDays\":21,\"theme\":\"LIGHT\",\"prWarmCount\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskRetentionDays").value(30))
                .andExpect(jsonPath("$.theme").value("LIGHT"));

        verify(settings).savePreferences(saved);
    }

    @Test
    void anOutOfRangePreferenceIsRejectedBeforeItReachesTheStore()
            throws Exception
    {
        MockMvc mvc = standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(appMapper()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(put("/api/settings/app").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskRetentionDays\":0,\"recentRetentionDays\":14,\"theme\":\"SYSTEM\",\"prWarmCount\":30}"))
                .andExpect(status().isBadRequest());

        verify(settings, org.mockito.Mockito.never()).savePreferences(any());
    }

    @Test
    void quietHoursAndCodeGraphModeAreServedAndSaved()
            throws Exception
    {
        when(settings.quietHours()).thenReturn(QuietHours.DEFAULT);
        QuietHours evening = new QuietHours(true, 1320, 420);
        when(settings.saveQuietHours(evening)).thenReturn(evening);
        when(settings.codeGraphMode()).thenReturn(CodeGraphMode.ON);
        CodeGraphSetting off = new CodeGraphSetting(CodeGraphMode.OFF);
        when(settings.saveCodeGraph(off)).thenReturn(off);
        MockMvc mvc = mvc();

        mvc.perform(get("/api/settings/quiet-hours"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.startMinute").value(60));
        mvc.perform(put("/api/settings/quiet-hours").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"startMinute\":1320,\"endMinute\":420}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(get("/api/settings/codegraph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("ON"));
        mvc.perform(put("/api/settings/codegraph").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"OFF\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("OFF"));
    }

    @Test
    void codeGraphBaselineIsAReadOnlyReadout()
            throws Exception
    {
        baseline.set(new CodeGraphBaseline(3, Optional.of(Instant.parse("2026-09-10T04:00:00Z"))));

        mvc().perform(get("/api/settings/codegraph/baseline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories").value(3))
                .andExpect(jsonPath("$.updatedAt").value("2026-09-10T04:00:00Z"));
    }
}
