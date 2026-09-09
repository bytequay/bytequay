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

import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.service.settings.AgentSkill;
import com.bytequay.app.service.settings.AgentSkillService;
import com.bytequay.core.review.AgentReviewStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import static com.bytequay.app.service.settings.AgentSkillScope.GLOBAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestAgentSkillsController
{
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AgentSkillService service = new AgentSkillService(new InMemoryAppSettingsStore(), mapper, mock(AgentReviewStore.class));
    private final MockMvc mvc = standaloneSetup(new AgentSkillsController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void managementReturnsOnlyUserSkillsAndCannotChangeTheSystemGuidance()
            throws Exception
    {
        String system = service.instructions();
        mvc.perform(get("/api/settings/agent-skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/settings/agent-skills").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Team style","instructions":"Explain behavior first.","scope":"GLOBAL","enabled":true}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Team style"))
                .andExpect(jsonPath("$[0].scope").value("GLOBAL"))
                .andExpect(jsonPath("$[0].instructions").value("Explain behavior first."));
        String userSkillId = service.list().getFirst().id();
        mvc.perform(put("/api/settings/agent-skills/" + userSkillId).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Team style","instructions":"Use short paragraphs.","scope":"REVIEW","enabled":false}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].scope").value("REVIEW"))
                .andExpect(jsonPath("$[0].enabled").value(false));
        assertThat(service.instructions()).isEqualTo(system);
        mvc.perform(delete("/api/settings/agent-skills/" + userSkillId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(delete("/api/settings/agent-skills/bytequay-system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(put("/api/settings/agent-skills/bytequay-system").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"System override","instructions":"Disabled","scope":"GLOBAL","enabled":false}
                        """))
                .andExpect(status().isBadRequest());
        assertThat(service.instructions()).isEqualTo(system);
    }

    @Test
    void invalidAndMissingScopesAreRejectedForAddAndUpdate()
            throws Exception
    {
        AgentSkill saved = service.add("Existing", "Keep this guidance.", GLOBAL, true).getFirst();
        for (String scopeField : ImmutableList.of("", ",\"scope\":null", ",\"scope\":\"INVALID\"", ",\"scope\":\"global\"", ",\"scope\":1")) {
            String request = "{\"name\":\"Changed\",\"instructions\":\"New guidance\",\"enabled\":true" + scopeField + "}";
            mvc.perform(post("/api/settings/agent-skills").contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isBadRequest());
            mvc.perform(put("/api/settings/agent-skills/" + saved.id()).contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isBadRequest());
            assertThat(service.list()).containsExactly(saved);
        }
    }

    @Test
    void invalidAndMissingTextIsRejectedWithoutChangingTheCollection()
            throws Exception
    {
        mvc.perform(post("/api/settings/agent-skills").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Empty","instructions":" ","scope":"GLOBAL","enabled":true}
                        """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/settings/agent-skills").contentType(MediaType.APPLICATION_JSON).content("""
                        {"scope":"GLOBAL","enabled":true}
                        """))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings/agent-skills/missing").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Missing","instructions":"Some instructions","scope":"GLOBAL","enabled":true}
                        """))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/settings/agent-skills"))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
