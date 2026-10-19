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
package com.bytequay.app.web.agent;

import com.bytequay.app.agent.AgentProperties;
import com.bytequay.app.agent.AgentServingGate;
import com.bytequay.app.jira.JiraActivityLog;
import com.bytequay.app.jira.JiraCache;
import com.bytequay.app.jira.JiraConnections;
import com.bytequay.app.jira.JiraController;
import com.bytequay.app.jira.JiraHttpClient;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.repository.github.GitHubRateLimitInterceptor;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.web.CredentialController;
import com.bytequay.app.web.PullRequestController;
import com.bytequay.core.store.JiraConnectionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import static com.bytequay.app.agent.AgentServingGates.readyAgentServingGate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestAgentRequestFilter
{
    private static final String SESSION = "desktop-session";

    @ParameterizedTest
    @ValueSource(strings = {
            "/agent/tasks",
            "/agent/usage",
            "/agent/usage/codex",
            "/api/jira/config",
            "/api/jira/connections",
            "/api/google",
            "/api/google/mail",
            "/api/google/install",
            "/api/teams",
            "/api/teams/circle/members/123",
            "/api/agenda/today",
            "/api/agenda/today/run",
            "/api/settings/agenda",
            "/api/assistant",
            "/api/assistant/today",
            "/api/assistant/messages",
            "/api/assistant/stop",
            "/api/assistant/retry",
            "/api/settings/assistant",
            "/api/jira/connections/connection/sites/cloud/issues/1001/comments",
            "/api/ci-autofix/runs",
            "/api/cherry-pick-ranges/previews",
            "/api/workspaces/workspace-a/cherry-pick/preview",
            "/api/workspaces/workspace-a/git/status"})
    void protectsEveryLocalAgentSurface(String path)
            throws Exception
    {
        assertThat(filter(SESSION, request(path, null, null)).getStatus()).isEqualTo(401);
        assertThat(filter(SESSION, request(path, "wrong", null)).getStatus()).isEqualTo(401);
        assertThat(filter(SESSION, request(path, SESSION, "https://example.com")).getStatus()).isEqualTo(403);
        assertThat(filter("", request(path, SESSION, null)).getStatus()).isEqualTo(503);

        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest allowed = request(path, SESSION, null);
        new AgentRequestFilter(properties(SESSION), new ObjectMapper().findAndRegisterModules(), readyAgentServingGate())
                .doFilter(allowed, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(allowed);
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /agent/tasks",
            "GET, /agent/tasks/task-id/stream",
            "GET, /agent/approvals/approval-id",
            "GET, /api/assistant/messages",
            "GET, /api/ci-autofix/runs",
            "GET, /api/cherry-pick-ranges/previews",
            "POST, /api/jira/connections",
            "POST, /api/jira/connections/token",
            "POST, /api/jira/connections/connection/refresh",
            "DELETE, /api/jira/connections/connection",
            "GET, /api/jira/connections/connection/sites/cloud/issues/1001",
            "GET, /api/jira/attention"})
    void rejectsAgentOperationsUntilStartupRecoveryCompletes(String method, String path)
            throws Exception
    {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new AgentRequestFilter(properties(SESSION), new ObjectMapper().findAndRegisterModules(), new AgentServingGate())
                .doFilter(request(method, path, SESSION, null), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/agent/providers/status", "/api/jira/config", "/api/jira/connections"})
    void keepsAuthenticatedStatusReadsAvailableDuringRecovery(String path)
            throws Exception
    {
        assertThat(filter(SESSION, request(path, null, null)).getStatus()).isEqualTo(401);
        assertThat(filter(SESSION, request(path, "wrong", null)).getStatus()).isEqualTo(401);
        assertThat(filter(SESSION, request(path, SESSION, "https://example.com")).getStatus()).isEqualTo(403);

        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = request(path, SESSION, null);
        new AgentRequestFilter(properties(SESSION), new ObjectMapper().findAndRegisterModules(), new AgentServingGate())
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void unconnectedJiraSettingsRemainAvailableDuringRecovery()
            throws Exception
    {
        JiraConnectionStore store = mock(JiraConnectionStore.class);
        when(store.ids()).thenReturn(ImmutableList.of());
        JiraHttpClient http = mock(JiraHttpClient.class);
        JiraConnections connections = new JiraConnections(store, mock(JiraCache.class), http, "");
        MockMvc mvc = standaloneSetup(new JiraController(connections, mock(JiraService.class), mock(JiraActivityLog.class)))
                .addFilters(new AgentRequestFilter(properties(SESSION), new ObjectMapper().findAndRegisterModules(), new AgentServingGate()))
                .build();

        mvc.perform(get("/api/jira/config").header(AgentController.SESSION_HEADER, SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
        mvc.perform(get("/api/jira/connections").header(AgentController.SESSION_HEADER, SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        verifyNoInteractions(http);
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /api/auth/github/configured",
            "GET, /api/auth/github/account",
            "GET, /api/auth/github/sync-status",
            "GET, /api/auth/github/token/value",
            "HEAD, /api/auth/github/token/value",
            "POST, /api/auth/github/token",
            "DELETE, /api/auth/github/token",
            "GET, /api/auth/github/cli",
            "POST, /api/auth/github/cli/import",
            "GET, /api/workspaces/workspace-a/github-token/value",
            "PUT, /api/workspaces/workspace-a/github-token",
            "DELETE, /api/workspaces/workspace-a/github-token",
            "PATCH, /prs/123/monitor",
            "POST, /prs/detail/refresh",
            "POST, /prs/detail/refresh-all",
            "POST, /prs/diffFiles/refresh",
            "POST, /prs/diffContent/refresh",
            "PATCH, /prs/title",
            "PATCH, /prs/description",
            "PATCH, /prs/metadata",
            "PATCH, /prs/reviewers",
            "POST, /prs/comment",
            "PATCH, /prs/comments",
            "POST, /prs/reactions",
            "POST, /prs/close",
            "POST, /prs/ready-for-review",
            "POST, /prs/checks/rerun-failed",
            "POST, /prs/review-comments/123/replies",
            "POST, /prs/review-comments/123/resolution",
            "POST, /prs/review",
            "POST, /prs/review-comment",
            "DELETE, /prs/merge-queue",
            "POST, /prs/merge",
            "GET, /%61pi/auth/github/token/value",
            "GET, /api/auth;parameter=value/github/token/value",
            "GET, /api/workspaces/workspace-a/github%2Dtoken/value",
            "GET, /other/../api/auth/github/token/value",
            "POST, /%70rs/merge",
            "POST, /other/../prs/merge",
            "POST, /prs;parameter=value/close"})
    void protectsCredentialsAndPullRequestWritesWithoutWaitingForAgentRecovery(String method, String path)
            throws Exception
    {
        assertThat(filter(SESSION, request(method, path, null, null)).getStatus()).isEqualTo(401);
        assertThat(filter(SESSION, request(method, path, "wrong", null)).getStatus()).isEqualTo(401);
        assertThat(filter(SESSION, request(method, path, SESSION, "https://example.com")).getStatus()).isEqualTo(403);
        assertThat(filter("", request(method, path, SESSION, null)).getStatus()).isEqualTo(503);

        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest allowed = request(method, path, SESSION, null);
        new AgentRequestFilter(properties(SESSION), new ObjectMapper().findAndRegisterModules(), new AgentServingGate())
                .doFilter(allowed, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(allowed);
    }

    @Test
    void onlyAuthenticatedRequestsCanReadTokensOrPerformPullRequestWrites()
            throws Exception
    {
        CredentialService credentials = mock(CredentialService.class);
        PullRequestService pullRequests = mock(PullRequestService.class);
        MockMvc mvc = standaloneSetup(
                new CredentialController(credentials, new GitHubRateLimitInterceptor(Clock.systemUTC())),
                new PullRequestController(pullRequests, mock(PullRequestRefreshService.class)))
                .addFilters(new AgentRequestFilter(properties(SESSION), new ObjectMapper().findAndRegisterModules(), new AgentServingGate()))
                .build();

        mvc.perform(get("/api/auth/github/token/value")).andExpect(status().isUnauthorized());
        mvc.perform(post("/prs/close").param("repo", "owner/repo").param("number", "123")).andExpect(status().isUnauthorized());
        verifyNoInteractions(credentials, pullRequests);

        when(credentials.gitHubTokenValue()).thenReturn(Optional.of("test-github-token"));
        mvc.perform(get("/api/auth/github/token/value").header(AgentController.SESSION_HEADER, SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("test-github-token"));
        mvc.perform(post("/prs/close").param("repo", "owner/repo").param("number", "123").header(AgentController.SESSION_HEADER, SESSION))
                .andExpect(status().isNoContent());
        verify(credentials).gitHubTokenValue();
        verify(pullRequests).closePullRequest("owner/repo", 123);
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /prs",
            "GET, /prs/detail",
            "GET, /recent",
            "POST, /api/settings/agent-skills",
            "PUT, /api/settings/development-automation",
            "POST, /api/settings/api-keys",
            "POST, /api/settings/sync/trigger",
            "POST, /api/workspaces/workspace-a/issues/7/comments",
            "POST, /api/workspaces/workspace-a/issues/7/close",
            "POST, /api/workspaces/workspace-a/work-items/item/publish",
            "POST, /api/workspaces/workspace-a/remotes",
            "GET, /api/repos/owner/repo/code-snippet",
            "POST, /api/avatars/../settings/agent-skills",
            "GET, /hello/../api/settings/app"})
    void protectsEveryOtherRouteWithoutWaitingForAgentRecovery(String method, String path)
            throws Exception
    {
        assertThat(filter(SESSION, request(method, path, null, null)).getStatus()).isEqualTo(401);
        assertThat(filter(SESSION, request(method, path, SESSION, "https://example.com")).getStatus()).isEqualTo(403);

        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest allowed = request(method, path, SESSION, null);
        new AgentRequestFilter(properties(SESSION), new ObjectMapper().findAndRegisterModules(), new AgentServingGate())
                .doFilter(allowed, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(allowed);
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /hello",
            "HEAD, /hello",
            "GET, /api/avatars/repositories/owner",
            "POST, /internal/agent/claude/mcp"})
    void leavesRequestsThatCannotCarryTheSessionOpen(String method, String path)
            throws Exception
    {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = request(method, path, null, null);
        new AgentRequestFilter(properties(""), new ObjectMapper().findAndRegisterModules(), new AgentServingGate())
                .doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/hello", "/api/avatars/repositories/owner", "/prs", "/agent/tasks"})
    void rejectsRequestsAddressedToAnotherHost(String path)
            throws Exception
    {
        MockHttpServletRequest request = request(path, SESSION, null);
        request.setServerName("rebound.example.com");
        assertThat(filter(SESSION, request).getStatus()).isEqualTo(403);

        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest loopback = request(path, SESSION, null);
        loopback.setServerName("127.0.0.1");
        new AgentRequestFilter(properties(SESSION), new ObjectMapper().findAndRegisterModules(), readyAgentServingGate())
                .doFilter(loopback, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(loopback);
    }

    private static MockHttpServletResponse filter(String configuredToken, MockHttpServletRequest request)
            throws Exception
    {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new AgentRequestFilter(properties(configuredToken), new ObjectMapper().findAndRegisterModules(), readyAgentServingGate())
                .doFilter(request, response, chain);
        assertThat(chain.getRequest()).isNull();
        return response;
    }

    private static MockHttpServletRequest request(String path, String session, String origin)
    {
        return request("GET", path, session, origin);
    }

    private static MockHttpServletRequest request(String method, String path, String session, String origin)
    {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (session != null) {
            request.addHeader(AgentController.SESSION_HEADER, session);
        }
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }

    private static AgentProperties properties(String desktopSessionToken)
    {
        return new AgentProperties(
                Path.of("/tmp/agent-runtime"), Duration.ofMinutes(30), desktopSessionToken, "codex", "0.153.4", Path.of("/tmp/agent-codex-home"), "claude",
                Path.of("/tmp/agent-claude-home"));
    }
}
