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
import com.bytequay.app.artifact.ArtifactView;
import com.bytequay.core.domain.Artifact;
import com.bytequay.core.domain.ArtifactItem;
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
    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final Artifact ARTIFACT = Artifact.started("a1", "workspace", "Teach me position deletes", NOW).working(true, NOW);
    private static final ArtifactView VIEW = new ArtifactView(ARTIFACT,
            ImmutableList.of(ArtifactItem.of("a1", ArtifactItem.Kind.USER, "Teach me position deletes", NOW)), ImmutableList.of());

    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final MockMvc mvc = standaloneSetup(new ArtifactController(artifacts)).build();

    @Test
    void listsConversationsAndShowsOneWithItsItems()
            throws Exception
    {
        when(artifacts.list("workspace")).thenReturn(ImmutableList.of(ARTIFACT));
        when(artifacts.view("workspace", "a1")).thenReturn(VIEW);
        mvc.perform(get("/api/workspaces/workspace/artifacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("a1"))
                .andExpect(jsonPath("$[0].working").value(true));
        mvc.perform(get("/api/workspaces/workspace/artifacts/a1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifact.title").value("Teach me position deletes"))
                .andExpect(jsonPath("$.items[0].kind").value("USER"))
                .andExpect(jsonPath("$.pages").isEmpty());
    }

    @Test
    void readsAPageVersionOnlyWhileItExists()
            throws Exception
    {
        when(artifacts.pageHtml("workspace", "a1", "v1")).thenReturn(Optional.of("<p>Readers merge deletes.</p>"));
        when(artifacts.pageHtml("workspace", "a1", "gone")).thenReturn(Optional.empty());
        mvc.perform(get("/api/workspaces/workspace/artifacts/a1/pages/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.html").value("<p>Readers merge deletes.</p>"));
        mvc.perform(get("/api/workspaces/workspace/artifacts/a1/pages/gone"))
                .andExpect(status().isNotFound());
    }

    @Test
    void startsSendsAnswersOffersAndStops()
            throws Exception
    {
        when(artifacts.start("workspace", "Teach me position deletes")).thenReturn(VIEW);
        mvc.perform(post("/api/workspaces/workspace/artifacts").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Teach me position deletes\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifact.id").value("a1"));
        mvc.perform(post("/api/workspaces/workspace/artifacts/a1/messages").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"The read path\"}"))
                .andExpect(status().isOk());
        verify(artifacts).send("workspace", "a1", "The read path");
        mvc.perform(post("/api/workspaces/workspace/artifacts/a1/offers/i3").contentType(MediaType.APPLICATION_JSON).content("{\"accept\":true}"))
                .andExpect(status().isOk());
        verify(artifacts).answerOffer("workspace", "a1", "i3", true);
        mvc.perform(post("/api/workspaces/workspace/artifacts/a1/stop")).andExpect(status().isOk());
        verify(artifacts).stop("workspace", "a1");
    }
}
