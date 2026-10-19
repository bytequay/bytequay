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

import com.bytequay.app.artifact.ArtifactService;
import com.bytequay.core.domain.Artifact;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestArtifactController
{
    private static final Artifact ASKED = Artifact.asked("a1", "workspace", "Teach me delete files", Instant.parse("2026-10-19T10:00:00Z"));

    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final MockMvc mvc = standaloneSetup(new ArtifactController(artifacts)).build();

    @Test
    void listsRecordsWithoutPages()
            throws Exception
    {
        when(artifacts.list("workspace")).thenReturn(ImmutableList.of(ASKED));
        mvc.perform(get("/api/workspaces/workspace/artifacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("a1"))
                .andExpect(jsonPath("$[0].state").value("WRITING"))
                .andExpect(jsonPath("$[0].ask").value("Teach me delete files"))
                .andExpect(jsonPath("$[0].html").doesNotExist());
    }

    @Test
    void readsAPageOnlyOnceWritten()
            throws Exception
    {
        when(artifacts.page("workspace", "a1")).thenReturn(Optional.of("<p>Readers merge deletes.</p>"));
        when(artifacts.page("workspace", "a2")).thenReturn(Optional.empty());
        mvc.perform(get("/api/workspaces/workspace/artifacts/a1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.html").value("<p>Readers merge deletes.</p>"));
        mvc.perform(get("/api/workspaces/workspace/artifacts/a2"))
                .andExpect(status().isNotFound());
    }

    @Test
    void asksChangesStopsAndAnswersCards()
            throws Exception
    {
        when(artifacts.ask("workspace", "Teach me delete files")).thenReturn(ASKED);
        mvc.perform(post("/api/workspaces/workspace/artifacts").contentType(MediaType.APPLICATION_JSON).content("{\"ask\":\"Teach me delete files\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("a1"));
        mvc.perform(post("/api/workspaces/workspace/artifacts/a1/change").contentType(MediaType.APPLICATION_JSON).content("{\"ask\":\"Simpler diagram\"}"))
                .andExpect(status().isOk());
        verify(artifacts).change("workspace", "a1", "Simpler diagram");
        mvc.perform(post("/api/workspaces/workspace/artifacts/a1/stop")).andExpect(status().isOk());
        verify(artifacts).stop("workspace", "a1");
        mvc.perform(post("/api/workspaces/workspace/artifacts/a1/next/c1/write")).andExpect(status().isOk());
        verify(artifacts).writeNext("workspace", "a1", "c1");
        mvc.perform(post("/api/workspaces/workspace/artifacts/a1/next/c1/dismiss")).andExpect(status().isOk());
        verify(artifacts).dismissNext("workspace", "a1", "c1");
    }
}
