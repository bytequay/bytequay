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
package com.bytequay.app.web.cherrypick;

import com.bytequay.app.agent.AgentProperties;
import com.bytequay.app.cherrypick.CherryPickPreviewService;
import com.bytequay.app.web.GlobalExceptionHandler;
import com.bytequay.app.web.agent.AgentController;
import com.bytequay.app.web.agent.AgentRequestFilter;
import com.bytequay.core.cherrypick.CherryPickConflictException;
import com.bytequay.core.cherrypick.CherryPickInvalidRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestCherryPickPreviewHttpApi
{
    private static final String SESSION = "desktop-session";
    private static final String BODY = """
            {"repository":"/tmp/clone","sourceRemote":"origin","sourceBaseRef":"477",
            "sourceTipRef":"478","targetRemote":"fork","targetBranch":"master"}
            """;

    private CherryPickPreviewService previewService;
    private MockMvc mvc;

    @BeforeEach
    void setUp()
    {
        previewService = mock(CherryPickPreviewService.class);
        AgentProperties properties = new AgentProperties(
                Path.of("/tmp/agent-runtime"),
                Duration.ofMinutes(30),
                SESSION,
                "codex",
                false,
                "claude",
                false);
        mvc = standaloneSetup(new CherryPickPreviewController(previewService))
                .addFilters(new AgentRequestFilter(properties, new ObjectMapper().findAndRegisterModules()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void protectsBothEndpointsWithTheDesktopSessionAndRejectsBrowserOrigins()
            throws Exception
    {
        mvc.perform(get("/api/cherry-pick-ranges/previews/any"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/cherry-pick-ranges/previews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/cherry-pick-ranges/previews/any")
                        .header(AgentController.SESSION_HEADER, SESSION)
                        .header("Origin", "https://example.com"))
                .andExpect(status().isForbidden());
        verify(previewService, never()).preview(any());
        verify(previewService, never()).find(any());
    }

    @Test
    void reportsAnExpiredPreviewAsNotFound()
            throws Exception
    {
        when(previewService.find("gone")).thenReturn(Optional.empty());

        mvc.perform(get("/api/cherry-pick-ranges/previews/gone")
                        .header(AgentController.SESSION_HEADER, SESSION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Preview is unknown or expired; preview the range again"));
    }

    @Test
    void mapsInvalidInputToBadRequestAndSyncFailureToConflict()
            throws Exception
    {
        doThrow(new CherryPickInvalidRequestException("Source range contains no commits"))
                .when(previewService).preview(any());
        mvc.perform(post("/api/cherry-pick-ranges/previews")
                        .header(AgentController.SESSION_HEADER, SESSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Source range contains no commits"));

        doThrow(new CherryPickConflictException("Local target branch fork-master has diverged"))
                .when(previewService).preview(any());
        mvc.perform(post("/api/cherry-pick-ranges/previews")
                        .header(AgentController.SESSION_HEADER, SESSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Local target branch fork-master has diverged"));
    }

    @Test
    void rejectsARelativeRepositoryPathBeforeTouchingGit()
            throws Exception
    {
        mvc.perform(post("/api/cherry-pick-ranges/previews")
                        .header(AgentController.SESSION_HEADER, SESSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repository":"relative/clone","sourceRemote":"origin","sourceBaseRef":"477",
                                "sourceTipRef":"478","targetRemote":"fork","targetBranch":"master"}
                                """))
                .andExpect(status().isBadRequest());
        verify(previewService, never()).preview(any());
    }
}
