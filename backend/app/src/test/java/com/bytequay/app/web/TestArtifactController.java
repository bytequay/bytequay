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

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.app.artifact.ArtifactRow;
import com.bytequay.app.artifact.ArtifactService;
import com.bytequay.app.artifact.ArtifactView;
import com.bytequay.core.domain.Artifact;
import com.bytequay.core.domain.ArtifactOffer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
    private static final String ID = "8bafef5c-0000-4000-8000-000000000001";
    private static final ArtifactView VIEW = new ArtifactView(ARTIFACT,
            ImmutableList.of(ArtifactOffer.offered("a1", "t1", "Deletes on read", "How a scan skips deleted rows.", NOW)), ImmutableList.of(), ImmutableList.of("q1"));

    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final MockMvc mvc = standaloneSetup(new ArtifactController(artifacts)).build();

    @Test
    void listsConversationsAndShowsOneWithItsItems()
            throws Exception
    {
        when(artifacts.list("workspace")).thenReturn(ImmutableList.of(new ArtifactRow(ARTIFACT, 2)));
        when(artifacts.view("workspace", "a1")).thenReturn(VIEW);
        mvc.perform(get("/api/workspaces/workspace/artifacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].artifact.id").value("a1"))
                .andExpect(jsonPath("$[0].artifact.working").value(true))
                .andExpect(jsonPath("$[0].pages").value(2));
        mvc.perform(get("/api/workspaces/workspace/artifacts/a1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifact.title").value("Teach me position deletes"))
                .andExpect(jsonPath("$.offers[0].title").value("Deletes on read"))
                .andExpect(jsonPath("$.offers[0].state").value("SHOWN"))
                .andExpect(jsonPath("$.questions[0]").value("q1"))
                .andExpect(jsonPath("$.pages").isEmpty());
    }

    @Test
    void readsEventsInTheFormOfATasksHistory()
            throws Exception
    {
        AgentEventEnvelope event = new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), 3, TaskId.valueOf(ID), Optional.of(TurnId.random()),
                AgentEventSource.root(), NOW, new AgentEventPayload.UserMessage("The read path", ImmutableList.of(), AgentEventPayload.DeliveryKind.TURN));
        when(artifacts.events("workspace", ID, 2)).thenReturn(ImmutableList.of(event));
        mvc.perform(get("/api/workspaces/workspace/artifacts/" + ID + "/events").param("after", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sequence").value(3))
                .andExpect(jsonPath("$[0].taskId").value(ID))
                .andExpect(jsonPath("$[0].type").value("user_message"))
                .andExpect(jsonPath("$[0].payload.text").value("The read path"));
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
        when(artifacts.view("workspace", "a1")).thenReturn(VIEW);
        mvc.perform(post("/api/workspaces/workspace/artifacts/a1/questions/q1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allow\":true,\"answers\":{\"Which path?\":\"Read path\"}}"))
                .andExpect(status().isOk());
        verify(artifacts).answerQuestion("workspace", "a1", "q1", true, ImmutableMap.of("Which path?", "Read path"));
        mvc.perform(post("/api/workspaces/workspace/artifacts/a1/questions/q1").contentType(MediaType.APPLICATION_JSON).content("{\"allow\":false}"))
                .andExpect(status().isOk());
        verify(artifacts).answerQuestion("workspace", "a1", "q1", false, ImmutableMap.of());
        mvc.perform(post("/api/workspaces/workspace/artifacts/a1/stop")).andExpect(status().isOk());
        verify(artifacts).stop("workspace", "a1");
    }
}
