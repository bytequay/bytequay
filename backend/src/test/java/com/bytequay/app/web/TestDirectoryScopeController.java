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

import com.bytequay.app.service.learning.DirectoryScopeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Wire contract for approval-first directory scopes. */
@WebMvcTest(DirectoryScopeController.class)
class TestDirectoryScopeController
{
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DirectoryScopeService scopes;

    @Test
    void suggestionsIncludeDecisionAndCurrentAssignment()
            throws Exception
    {
        when(scopes.suggestions("ws-1")).thenReturn(new DirectoryScopeService.Overview(
                25, 25, 25, true,
                List.of(new DirectoryScopeService.Suggestion(
                        "core", List.of("modules/core"), 7, 0.28,
                        "7 distinct analyzed PRs", "approved")),
                List.of(new DirectoryScopeService.Assignment(
                        "thread-1", "core", List.of("modules/core"),
                        "approved", 100L))));

        mvc.perform(get("/api/workspaces/ws-1/directory-scopes/suggestions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historyReady").value(true))
                .andExpect(jsonPath("$.suggestions[0].paths[0]").value("modules/core"))
                .andExpect(jsonPath("$.suggestions[0].decisionState").value("approved"))
                .andExpect(jsonPath("$.assignments[0].threadId").value("thread-1"));
    }

    @Test
    void decisionsAndThreadAssignmentsDelegateWithinWorkspace()
            throws Exception
    {
        when(scopes.decide("ws-1", "modules/core", "approved"))
                .thenReturn(new DirectoryScopeService.Decision(
                        List.of("modules/core"), "approved", 100L));
        when(scopes.assign("ws-1", "thread-1", "modules/core"))
                .thenReturn(new DirectoryScopeService.Assignment(
                        "thread-1", "core", List.of("modules/core"),
                        "approved", 100L));

        mvc.perform(post("/api/workspaces/ws-1/directory-scopes/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"modules/core\",\"decision\":\"approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionState").value("approved"));
        mvc.perform(put("/api/workspaces/ws-1/directory-scopes/threads/thread-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"modules/core\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threadId").value("thread-1"));
        mvc.perform(delete("/api/workspaces/ws-1/directory-scopes/threads/thread-1"))
                .andExpect(status().isOk());

        verify(scopes).decide("ws-1", "modules/core", "approved");
        verify(scopes).assign("ws-1", "thread-1", "modules/core");
        verify(scopes).clearAssignment("ws-1", "thread-1");
    }
}
