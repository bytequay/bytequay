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
    private final JiraActivityLog activity = mock(JiraActivityLog.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp()
    {
        mvc = standaloneSetup(new JiraController(connections, jira, activity)).setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    @Test
    void attentionNamesTheLastChangeSeenOnEachTicket()
            throws Exception
    {
        JiraIssue issue = detail().issue();
        when(jira.attention()).thenReturn(ImmutableList.of(issue));
        when(activity.latest(issue)).thenReturn(Optional.of(new JiraActivity(JiraActivity.Kind.STATUS_CHANGED, UPDATED, Optional.empty(),
                Optional.of(new JiraDtos.Status("3", "In Progress", "indeterminate")), Optional.of(new JiraDtos.Status("6", "Blocked", "indeterminate")),
                Optional.empty(), ImmutableList.of())));
        JsonNode tickets = mapper.readTree(mvc.perform(get("/api/jira/attention")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        // The ticket stays flat, as every other Jira read sends it, with its last change beside it.
        assertThat(tickets.at("/0/key").asText()).isEqualTo(issue.key());
        assertThat(tickets.at("/0/issue").isMissingNode()).isTrue();
        assertThat(tickets.at("/0/latestActivity/kind").asText()).isEqualTo("STATUS_CHANGED");
        assertThat(tickets.at("/0/latestActivity/to/name").asText()).isEqualTo("Blocked");
        assertThat(tickets.at("/0/latestActivity/actor").isNull()).isTrue();
    }

    @Test
    void usesApiPrefixAndSeparatesCachedReadsFromExplicitRefresh()
            throws Exception
    {
        JiraDtos.Connection connection = new JiraDtos.Connection("connection", "connected",
                ImmutableList.of(new JiraDtos.Site("cloud", "Example", "https://example.atlassian.net")), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(new JiraDtos.Account("account", "Alice", Optional.empty())), Optional.empty(), "oauth", Optional.empty());
        when(connections.list()).thenReturn(ImmutableList.of(connection));
        JsonNode list = mapper.readTree(mvc.perform(get("/api/jira/connections")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.at("/0/sites/0/id").asText()).isEqualTo("cloud");
        assertThat(list.at("/0/authorizationUrl").isNull()).isTrue();
        assertThat(list.at("/0/account/accountId").asText()).isEqualTo("account");
        assertThat(list.at("/0/account/email").isNull()).isTrue();
        assertThat(list.at("/0/duplicateOf").isNull()).isTrue();
        assertThat(list.get(0).has("brokerUrl")).isFalse();
        when(jira.boards("connection", "cloud", false)).thenReturn(ImmutableList.of(new JiraDtos.Board(7, "Product", "scrum")));
        when(jira.boards("connection", "cloud", true)).thenReturn(ImmutableList.of());
        mvc.perform(get(SITE + "/boards")).andExpect(status().isOk());
        mvc.perform(post(SITE + "/boards/refresh")).andExpect(status().isOk());
        verify(jira).boards("connection", "cloud", false);
        verify(jira).boards("connection", "cloud", true);
        when(jira.issues("connection", "cloud", 7, Optional.of(8L), true, false, false, Optional.empty()))
                .thenReturn(new JiraDtos.IssuePage(ImmutableList.of(detail().issue()), Optional.of(UPDATED), Optional.empty(), Optional.empty()));
        mvc.perform(get(SITE + "/issues").param("boardId", "7").param("sprintId", "8")).andExpect(status().isOk());
        verify(jira).issues("connection", "cloud", 7, Optional.of(8L), true, false, false, Optional.empty());
        when(jira.detail("connection", "cloud", "10001", false)).thenReturn(detail());
        JsonNode item = mapper.readTree(mvc.perform(get(ISSUE)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(item.at("/issue/updatedAt").asText()).isEqualTo(UPDATED.toString());
        assertThat(item.at("/issue/assignee").isNull()).isTrue();
        assertThat(item.at("/issue/completedAt").isNull()).isTrue();
        assertThat(item.path("fetchedAt").asText()).isEqualTo(UPDATED.toString());
        assertThat(item.path("error").isNull()).isTrue();
        when(jira.refreshDetail("connection", "cloud", "10001", false)).thenReturn(detail());
        mvc.perform(post(ISSUE + "/refresh")).andExpect(status().isOk());
        verify(jira).refreshDetail("connection", "cloud", "10001", false);
        when(jira.refreshDetail("connection", "cloud", "10001", true)).thenReturn(detail());
        mvc.perform(post(ISSUE + "/refresh").param("automatic", "true")).andExpect(status().isOk());
        verify(jira).refreshDetail("connection", "cloud", "10001", true);
        mvc.perform(get("/jira/connections")).andExpect(status().isNotFound());
    }

    @Test
    void configAndSignInRoutesTakeNoRendererOrigin()
            throws Exception
    {
        when(connections.configured()).thenReturn(true);
        JsonNode config = mapper.readTree(mvc.perform(get("/api/jira/config")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(config.path("configured").asBoolean()).isTrue();
        JiraDtos.Connection pending = new JiraDtos.Connection("connection", "pending", ImmutableList.of(), Optional.of("https://broker.example/oauth/start"),
                Optional.of("ABCD1234"), Optional.empty(), Optional.empty(), Optional.empty(), "oauth", Optional.empty());
        when(connections.connect()).thenReturn(pending);
        when(connections.reconnect("connection")).thenReturn(pending);
        mvc.perform(post("/api/jira/connections")).andExpect(status().isOk());
        verify(connections).connect();
        mvc.perform(post("/api/jira/connections/connection/reconnect")).andExpect(status().isOk());
        verify(connections).reconnect("connection");
    }

    @Test
    void completedQueryReachesBothReadAndRefreshRoutes()
            throws Exception
    {
        JiraDtos.IssuePage page = new JiraDtos.IssuePage(ImmutableList.of(detail().issue()), Optional.of(UPDATED), Optional.empty(), Optional.empty());
        when(jira.issues("connection", "cloud", 7, Optional.of(8L), true, true, false, Optional.empty())).thenReturn(page);
        when(jira.issues("connection", "cloud", 7, Optional.empty(), false, true, true, Optional.empty())).thenReturn(page);
        mvc.perform(get(SITE + "/issues").param("boardId", "7").param("sprintId", "8").param("completed", "true")).andExpect(status().isOk());
        mvc.perform(post(SITE + "/issues/refresh").param("boardId", "7").param("mine", "false").param("completed", "true")).andExpect(status().isOk());
        verify(jira).issues("connection", "cloud", 7, Optional.of(8L), true, true, false, Optional.empty());
        verify(jira).issues("connection", "cloud", 7, Optional.empty(), false, true, true, Optional.empty());
        String cursor = "next/token + value";
        when(jira.issues("connection", "cloud", 7, Optional.empty(), false, true, true, Optional.of(cursor)))
                .thenReturn(new JiraDtos.IssuePage(page.issues(), page.fetchedAt(), Optional.empty(), Optional.of("last")));
        JsonNode next = mapper.readTree(mvc.perform(post(SITE + "/issues/refresh").param("boardId", "7").param("mine", "false")
                .param("completed", "true").param("nextPageToken", cursor)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(next.path("nextPageToken").asText()).isEqualTo("last");
        verify(jira).issues("connection", "cloud", 7, Optional.empty(), false, true, true, Optional.of(cursor));
    }

    @Test
    void bindsTokenSetupAndReplacementWithoutExposingTheSecret()
            throws Exception
    {
        JiraDtos.Connection connected = new JiraDtos.Connection("connection", "connected",
                ImmutableList.of(new JiraDtos.Site("cloud", "Example", "https://example.atlassian.net")), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(new JiraDtos.Account("account", "Alice", Optional.empty())), Optional.empty(), "api_token", Optional.of("alice@example.com"));
        JiraController.Token request = new JiraController.Token("https://example.atlassian.net", "alice@example.com", "private-token-secret");
        when(connections.connectToken(request.siteUrl(), request.email(), request.token())).thenReturn(connected);
        when(connections.replaceToken("connection", request.siteUrl(), request.email(), request.token())).thenReturn(connected);
        String created = mvc.perform(post("/api/jira/connections/token").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(created).contains("api_token", "alice@example.com").doesNotContain(request.token());
        String replaced = mvc.perform(put("/api/jira/connections/connection/token").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(replaced).doesNotContain(request.token());
        assertThat(request.toString()).doesNotContain(request.token());
        verify(connections).replaceToken("connection", request.siteUrl(), request.email(), request.token());
        String malformed = mvc.perform(post("/api/jira/connections/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":{\"private-token-secret\":true}}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(malformed).contains("Invalid Jira request body").doesNotContain("private-token-secret");
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
    void bindsSprintCommentAndEpicCommandsWithoutLosingNullBacklog()
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
        when(jira.comment("connection", "cloud", "10001", "Ready for review", "request-1234567890", Optional.empty())).thenReturn(detail());
        mvc.perform(post(ISSUE + "/comments").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(ImmutableMap.of("body", "Ready for review", "operationId", "request-1234567890"))))
                .andExpect(status().isOk());
        verify(jira).comment("connection", "cloud", "10001", "Ready for review", "request-1234567890", Optional.empty());
        when(jira.comment("connection", "cloud", "10001", "Agreed", "request-1234567891", Optional.of("5"))).thenReturn(detail());
        mvc.perform(post(ISSUE + "/comments").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(ImmutableMap.of("body", "Agreed", "operationId", "request-1234567891", "parentId", "5"))))
                .andExpect(status().isOk());
        verify(jira).comment("connection", "cloud", "10001", "Agreed", "request-1234567891", Optional.of("5"));
        when(jira.editComment("connection", "cloud", "10001", "5", "Updated", "2026-09-26T01:00:00.000+0000")).thenReturn(detail());
        mvc.perform(put(ISSUE + "/comments/5").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(ImmutableMap.of("body", "Updated", "expectedUpdated", "2026-09-26T01:00:00.000+0000"))))
                .andExpect(status().isOk());
        verify(jira).editComment("connection", "cloud", "10001", "5", "Updated", "2026-09-26T01:00:00.000+0000");
        when(jira.epic("connection", "cloud", "10001", true)).thenReturn(detail());
        mvc.perform(put(ISSUE + "/epic").contentType(MediaType.APPLICATION_JSON).content("{\"isEpic\":true}"))
                .andExpect(status().isOk());
        verify(jira).epic("connection", "cloud", "10001", true);
        when(jira.assignWorkspace("connection", "cloud", "10001", "workspace")).thenReturn(detail());
        mvc.perform(put(ISSUE + "/workspace").contentType(MediaType.APPLICATION_JSON).content("{\"workspaceId\":\"workspace\"}"))
                .andExpect(status().isOk());
        verify(jira).assignWorkspace("connection", "cloud", "10001", "workspace");
    }

    @Test
    void resolvesATicketLinkKeyOnlyThroughExplicitRefresh()
            throws Exception
    {
        when(jira.refreshIssueByKey("connection", "cloud", "APP-7")).thenReturn(detail().issue());
        JsonNode issue = mapper.readTree(mvc.perform(post(SITE + "/keys/APP-7/refresh")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(issue.path("id").asText()).isEqualTo("10001");
        verify(jira).refreshIssueByKey("connection", "cloud", "APP-7");
    }

    @Test
    void childAndLinkRoutesReachJiraOnlyThroughPosts()
            throws Exception
    {
        when(jira.childTypes("connection", "cloud", "10001")).thenReturn(ImmutableList.of(new JiraDtos.Choice("3", "Task")));
        JsonNode types = mapper.readTree(mvc.perform(post(ISSUE + "/child-types")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(types.at("/0/name").asText()).isEqualTo("Task");
        mvc.perform(get(ISSUE + "/child-types")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post(SITE + "/link-types")).andExpect(status().isOk());
        verify(jira).linkTypes("connection", "cloud");
        mvc.perform(post(ISSUE + "/children").contentType(MediaType.APPLICATION_JSON).content("{\"issueTypeId\":\"3\",\"summary\":\"Add the flag\"}"))
                .andExpect(status().isOk());
        verify(jira).createChild("connection", "cloud", "10001", "3", "Add the flag");
        mvc.perform(post(ISSUE + "/links").contentType(MediaType.APPLICATION_JSON).content("{\"typeId\":\"10000\",\"outward\":false,\"key\":\"APP-8\"}"))
                .andExpect(status().isOk());
        verify(jira).link("connection", "cloud", "10001", "10000", false, "APP-8");
    }

    private static JiraDtos.Detail detail()
    {
        JiraIssue issue = new JiraIssue("connection", "cloud", "10001", "APP-7", "https://example.atlassian.net/browse/APP-7", "Selection", "Body",
                "Story", new JiraDtos.Status("3", "In progress", "indeterminate"), Optional.empty(), ImmutableList.of(), Optional.empty(), Optional.empty(), false, false,
                UPDATED, Optional.empty(), Optional.empty(), ImmutableList.of(), false, Optional.empty(), Optional.empty());
        return new JiraDtos.Detail(issue, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), Optional.of(UPDATED), Optional.empty(), false,
                0, ImmutableList.of(), false, ImmutableList.of(), ImmutableList.of(), Optional.empty(), Optional.empty(), ImmutableList.of(), ImmutableList.of());
    }
}
