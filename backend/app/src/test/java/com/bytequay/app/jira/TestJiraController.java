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
package com.bytequay.app.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestJiraController
{
    private static final String SITE = "/api/jira/connections/connection/sites/cloud";
    private static final String ISSUE = SITE + "/issues/10001";
    private static final Instant UPDATED = Instant.parse("2026-09-16T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JiraService jira = mock(JiraService.class);
    private final JiraConnections connections = mock(JiraConnections.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp()
    {
        mvc = standaloneSetup(new JiraController(connections, jira)).setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    @Test
    void usesApiPrefixAndSeparatesCachedReadsFromExplicitRefresh()
            throws Exception
    {
        JiraDtos.Connection connection = new JiraDtos.Connection("connection", "https://broker.example", "connected",
                ImmutableList.of(new JiraDtos.Site("cloud", "Example", "https://example.atlassian.net")), Optional.empty(), Optional.empty(), Optional.empty());
        when(connections.list()).thenReturn(ImmutableList.of(connection));
        JsonNode list = mapper.readTree(mvc.perform(get("/api/jira/connections")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.at("/0/sites/0/id").asText()).isEqualTo("cloud");
        assertThat(list.at("/0/authorizationUrl").isNull()).isTrue();
        when(jira.boards("connection", "cloud", false)).thenReturn(ImmutableList.of(new JiraDtos.Board(7, "Product", "scrum")));
        when(jira.boards("connection", "cloud", true)).thenReturn(ImmutableList.of());
        mvc.perform(get(SITE + "/boards")).andExpect(status().isOk());
        mvc.perform(post(SITE + "/boards/refresh")).andExpect(status().isOk());
        verify(jira).boards("connection", "cloud", false);
        verify(jira).boards("connection", "cloud", true);
        when(jira.issues("connection", "cloud", 7, Optional.of(8L), true, false))
                .thenReturn(new JiraDtos.IssuePage(ImmutableList.of(detail().issue()), Optional.of(UPDATED), Optional.empty(), false));
        mvc.perform(get(SITE + "/issues").param("boardId", "7").param("sprintId", "8")).andExpect(status().isOk());
        verify(jira).issues("connection", "cloud", 7, Optional.of(8L), true, false);
        when(jira.detail("connection", "cloud", "10001", false)).thenReturn(detail());
        JsonNode item = mapper.readTree(mvc.perform(get(ISSUE)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(item.at("/issue/updatedAt").asText()).isEqualTo(UPDATED.toString());
        assertThat(item.at("/issue/assignee").isNull()).isTrue();
        assertThat(item.path("fetchedAt").asText()).isEqualTo(UPDATED.toString());
        assertThat(item.path("error").isNull()).isTrue();
        when(jira.detail("connection", "cloud", "10001", true)).thenReturn(detail());
        mvc.perform(post(ISSUE + "/refresh")).andExpect(status().isOk());
        verify(jira).detail("connection", "cloud", "10001", true);
        mvc.perform(get("/jira/connections")).andExpect(status().isNotFound());
    }

    @Test
    void preservesNullAndPrimitiveEditValuesAndVersion()
            throws Exception
    {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("assignee", null);
        fields.put("labels", ImmutableList.of("bug", "sprint"));
        fields.put("priority", "2");
        fields.put("duedate", "2026-09-30");
        when(jira.update("connection", "cloud", "10001", UPDATED, fields)).thenReturn(detail());
        mvc.perform(patch(ISSUE).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(ImmutableMap.of("fields", fields, "expectedUpdatedAt", UPDATED))))
                .andExpect(status().isOk());
        verify(jira).update("connection", "cloud", "10001", UPDATED, fields);
        Map<String, Object> transitionFields = ImmutableMap.of("resolution", "1", "summary", "Deliver selection");
        when(jira.transition("connection", "cloud", "10001", UPDATED, "31", transitionFields)).thenReturn(detail());
        mvc.perform(post(ISSUE + "/transitions").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(ImmutableMap.of("transitionId", "31", "fields", transitionFields, "expectedUpdatedAt", UPDATED))))
                .andExpect(status().isOk());
        verify(jira).transition("connection", "cloud", "10001", UPDATED, "31", transitionFields);
    }

    @Test
    void bindsSprintCommentAndGoalCommandsWithoutLosingNullBacklog()
            throws Exception
    {
        when(jira.move("connection", "cloud", "10001", UPDATED, Optional.empty())).thenReturn(detail());
        mvc.perform(post(ISSUE + "/sprint").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sprintId\":null,\"expectedUpdatedAt\":\"" + UPDATED + "\"}"))
                .andExpect(status().isOk());
        verify(jira).move("connection", "cloud", "10001", UPDATED, Optional.empty());
        when(jira.move("connection", "cloud", "10001", UPDATED, Optional.of(8L))).thenReturn(detail());
        mvc.perform(post(ISSUE + "/sprint").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(ImmutableMap.of("sprintId", 8, "expectedUpdatedAt", UPDATED))))
                .andExpect(status().isOk());
        verify(jira).move("connection", "cloud", "10001", UPDATED, Optional.of(8L));
        when(jira.comment("connection", "cloud", "10001", "Ready for review", "request-1234567890")).thenReturn(detail());
        mvc.perform(post(ISSUE + "/comments").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(ImmutableMap.of("body", "Ready for review", "operationId", "request-1234567890"))))
                .andExpect(status().isOk());
        verify(jira).comment("connection", "cloud", "10001", "Ready for review", "request-1234567890");
        when(jira.goal("connection", "cloud", "10001", true)).thenReturn(detail());
        mvc.perform(put(ISSUE + "/goal").contentType(MediaType.APPLICATION_JSON).content("{\"isGoal\":true}"))
                .andExpect(status().isOk());
        verify(jira).goal("connection", "cloud", "10001", true);
    }

    private static JiraDtos.Detail detail()
    {
        JiraIssue issue = new JiraIssue("connection", "cloud", "10001", "APP-7", "https://example.atlassian.net/browse/APP-7", "Selection", "Body",
                "Story", new JiraDtos.Status("3", "In progress", "indeterminate"), Optional.empty(), ImmutableList.of(), Optional.empty(), Optional.empty(), false, false,
                UPDATED, Optional.empty(), Optional.empty(), ImmutableList.of(), false);
        return new JiraDtos.Detail(issue, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), Optional.of(UPDATED), Optional.empty(), false);
    }
}
