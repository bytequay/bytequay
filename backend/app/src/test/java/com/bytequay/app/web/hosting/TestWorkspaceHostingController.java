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
package com.bytequay.app.web.hosting;

import com.bytequay.app.agent.runtime.AgentProperties;
import com.bytequay.app.agent.runtime.AgentServingGate;
import com.bytequay.app.web.GlobalExceptionHandler;
import com.bytequay.app.web.agent.AgentRequestFilter;
import com.bytequay.app.workspace.WorkspaceException;
import com.bytequay.app.workspace.WorkspaceHostingService;
import com.bytequay.app.workspace.WorkspacePullRequestPage;
import com.bytequay.app.workspace.WorkspacePullRequestRow;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.hosting.HostingPullRequest;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.hosting.WorkspaceHostingBinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.app.web.agent.AgentController.SESSION_HEADER;
import static com.bytequay.core.domain.PullRequest.Origin.OTHER;
import static com.bytequay.core.hosting.HostingPullRequestSnapshot.CONNECTED_ACCOUNT;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestWorkspaceHostingController
{
    private static final String SESSION = "desktop-session";
    private static final String BASE = "/api/workspaces/workspace/hosting";
    private static final WorkspaceHostingBinding BINDING = new WorkspaceHostingBinding("workspace", 20, 30, Optional.of(31L));
    private final WorkspaceHostingService hosting = mock(WorkspaceHostingService.class);
    private final WorkspaceRepositoryService repositories = mock(WorkspaceRepositoryService.class);

    @ParameterizedTest
    @CsvSource({"GET,/api/workspaces/workspace/hosting", "POST,/api/workspaces/workspace/hosting", "GET,/api/workspaces/workspace/hosting/requests/7",
            "GET,/api/workspaces/workspace/hosting/requests", "POST,/api/workspaces/workspace/hosting/requests/7/refresh"})
    void requiresLocalDesktopSession(String method, String path)
            throws Exception
    {
        mvc().perform(request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":20}"))
                .andExpect(status().isUnauthorized());
        mvc().perform(request(HttpMethod.valueOf(method), path).header(SESSION_HEADER, SESSION).header("Origin", "https://example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":20}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(hosting);
    }

    @Test
    void returnsTheSharedCachedPageWithoutRefreshing()
            throws Exception
    {
        FetchedPullRequest summary = snapshot().pullRequest();
        when(hosting.cachedPullRequests("workspace", 2, 25))
                .thenReturn(new WorkspacePullRequestPage(ImmutableList.of(new WorkspacePullRequestRow(summary, Optional.empty())), true));

        mvc().perform(get(BASE + "/requests").header(SESSION_HEADER, SESSION).param("page", "2").param("pageSize", "25"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].summary.title").value("Change"))
                .andExpect(jsonPath("$.items[0].github").isEmpty()).andExpect(jsonPath("$.hasNext").value(true));
        verifyNoInteractions(repositories);
    }

    @Test
    void readsCachedDetailsWithoutRefreshing()
            throws Exception
    {
        when(hosting.cachedPullRequest("workspace", 7)).thenReturn(Optional.of(snapshot()));

        mvc().perform(get(BASE + "/requests/7").header(SESSION_HEADER, SESSION))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(20))
                .andExpect(jsonPath("$.detail.body").value("Description")).andExpect(jsonPath("$.detail.headRef").value("topic"));
        verify(hosting).cachedPullRequest("workspace", 7);
        verifyNoMoreInteractions(hosting, repositories);
    }

    @Test
    void returnsNullWhenDetailHasNotBeenCached()
            throws Exception
    {
        mvc().perform(get(BASE + "/requests/7").header(SESSION_HEADER, SESSION)).andExpect(status().isOk()).andExpect(content().string("null"));
        verify(hosting).cachedPullRequest("workspace", 7);
        verifyNoMoreInteractions(hosting, repositories);
    }

    @Test
    void refreshesOnlyTheWorkspaceRequestNamedByThePost()
            throws Exception
    {
        when(hosting.readPullRequest("workspace", 7)).thenReturn(snapshot());

        mvc().perform(post(BASE + "/requests/7/refresh").header(SESSION_HEADER, SESSION))
                .andExpect(status().isOk()).andExpect(jsonPath("$.request.number").value(7))
                .andExpect(jsonPath("$.detail.body").value("Description"));
        verify(hosting).readPullRequest("workspace", 7);
        verifyNoMoreInteractions(hosting, repositories);
    }

    @Test
    void bindsOnlyTheExplicitAccountAndReturnsRetainedIdentities()
            throws Exception
    {
        when(hosting.bind("workspace", 20)).thenReturn(BINDING);
        mvc().perform(post(BASE).header(SESSION_HEADER, SESSION).contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value("workspace"))
                .andExpect(jsonPath("$.accountId").value(20))
                .andExpect(jsonPath("$.selectedRepositoryId").value(30))
                .andExpect(jsonPath("$.upstreamRepositoryId").value(31));
        verify(hosting).bind("workspace", 20);
        verify(repositories).requestSyncAfterConfigurationChange("workspace");
    }

    @Test
    void readsRetainedBinding()
            throws Exception
    {
        when(hosting.findBinding("workspace")).thenReturn(Optional.of(BINDING));
        mvc().perform(get(BASE).header(SESSION_HEADER, SESSION)).andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(20));
    }

    @Test
    void mapsInvalidAccountSelectionToBadRequest()
            throws Exception
    {
        when(hosting.bind("workspace", 0)).thenThrow(new IllegalArgumentException("accountId must be positive"));
        mvc().perform(post(BASE).header(SESSION_HEADER, SESSION).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retainsExistingWorkspaceNotFoundResponse()
            throws Exception
    {
        when(hosting.findBinding("workspace")).thenThrow(WorkspaceException.notFound("workspace"));
        mvc().perform(get(BASE).header(SESSION_HEADER, SESSION)).andExpect(status().isNotFound());
    }

    private static HostingPullRequestSnapshot snapshot()
    {
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        String url = "https://gitlab.com/group/project/-/merge_requests/7";
        FetchedPullRequest summary = new FetchedPullRequest(99L, "group/project", 7, "Change", "alice", url,
                now, now, OTHER, ImmutableList.of(), ImmutableMap.of(), false, "open", null, null, ImmutableList.of(), null, null, "topic");
        PrRawDetail detail = new PrRawDetail("Change", url, "open", "alice", null, now, now, null, "Description", ImmutableList.of(), ImmutableMap.of(),
                ImmutableList.of(), null, false, null, null, -1, -1, -1, 0, ImmutableList.of(), "a".repeat(40), "topic", "main");
        return new HostingPullRequestSnapshot(new HostingPullRequest(50, 30, 7, "99"), 20, CONNECTED_ACCOUNT, summary, detail, Optional.empty(), now);
    }

    private MockMvc mvc()
    {
        AgentProperties properties = new AgentProperties(Path.of("/tmp/agent-runtime"), Duration.ofMinutes(30), SESSION, "codex", "0.153.4",
                Path.of("/tmp/agent-codex-home"), "claude", Path.of("/tmp/agent-claude-home"));
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().modules(new Jdk8Module(), new JavaTimeModule()).build();
        return standaloneSetup(new WorkspaceHostingController(hosting, repositories))
                .setControllerAdvice(new GlobalExceptionHandler(), new HostingExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new AgentRequestFilter(properties, mapper, new AgentServingGate()))
                .build();
    }
}
