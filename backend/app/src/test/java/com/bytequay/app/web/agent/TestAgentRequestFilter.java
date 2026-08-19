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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TestAgentRequestFilter
{
    private static final String SESSION = "desktop-session";

    @ParameterizedTest
    @ValueSource(strings = {"/agent/tasks", "/api/ci-autofix/runs", "/api/cherry-pick-ranges/previews"})
    void protectsEveryLocalAgentSurface(String path)
            throws Exception
    {
        assertThat(filter(SESSION, request(path, null, null)).getStatus()).isEqualTo(401);
        assertThat(filter(SESSION, request(path, "wrong", null)).getStatus()).isEqualTo(401);
        assertThat(filter(SESSION, request(path, SESSION, "https://example.com")).getStatus()).isEqualTo(403);
        assertThat(filter("", request(path, SESSION, null)).getStatus()).isEqualTo(503);

        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest allowed = request(path, SESSION, null);
        new AgentRequestFilter(properties(SESSION), new ObjectMapper().findAndRegisterModules()).doFilter(allowed, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(allowed);
    }

    @Test
    void leavesOrdinaryPullRequestEndpointsUnprotected()
            throws Exception
    {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = request("/api/pulls", null, "https://example.com");
        new AgentRequestFilter(properties(SESSION), new ObjectMapper().findAndRegisterModules()).doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    private static MockHttpServletResponse filter(String configuredToken, MockHttpServletRequest request)
            throws Exception
    {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new AgentRequestFilter(properties(configuredToken), new ObjectMapper().findAndRegisterModules()).doFilter(request, response, chain);
        assertThat(chain.getRequest()).isNull();
        return response;
    }

    private static MockHttpServletRequest request(String path, String session, String origin)
    {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
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
        return new AgentProperties(Path.of("/tmp/agent-runtime"), Duration.ofMinutes(30), desktopSessionToken, "codex", false, "claude", false);
    }
}
