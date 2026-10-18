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

import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.service.settings.SetupSettingsService;
import com.bytequay.core.store.CredentialStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestSetupController
{
    @Test
    void setupResumesItsSavedStepAndRejectsInvalidPages()
            throws Exception
    {
        SetupSettingsService settings = new SetupSettingsService(new InMemoryAppSettingsStore(), mock(CredentialStore.class), mock(AppSettingsService.class),
                mock(ModelSettingsService.class), mock(AgentDriverRegistry.class), new ObjectMapper());
        MockMvc http = standaloneSetup(new SetupController(settings)).setControllerAdvice(new GlobalExceptionHandler()).build();

        http.perform(get("/api/settings/setup"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.step").value("APPEARANCE")).andExpect(jsonPath("$.assistantEnabled").value(false));
        http.perform(put("/api/settings/setup").contentType(MediaType.APPLICATION_JSON).content("{\"step\":\"AGENT\",\"assistantEnabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.step").value("AGENT"));
        http.perform(put("/api/settings/setup").contentType(MediaType.APPLICATION_JSON).content("{\"step\":\"UNKNOWN\",\"assistantEnabled\":false}"))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/settings/setup")).andExpect(status().isOk()).andExpect(jsonPath("$.step").value("AGENT"));
    }
}
