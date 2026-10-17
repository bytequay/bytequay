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
import com.bytequay.app.web.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Set;

import static java.util.Objects.requireNonNull;

@Component
public final class AgentRequestFilter
        extends OncePerRequestFilter
{
    private static final Set<String> LOOPBACK_HOSTS = ImmutableSet.of("127.0.0.1", "localhost", "[::1]", "::1");

    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final AgentServingGate servingGate;

    public AgentRequestFilter(AgentProperties properties, ObjectMapper objectMapper, AgentServingGate servingGate)
    {
        this.properties = requireNonNull(properties, "properties is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
        this.servingGate = requireNonNull(servingGate, "servingGate is null");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException
    {
        // A DNS-rebinding page reaches 127.0.0.1 under its own host name; the loopback names are the only ones this server answers to.
        if (!LOOPBACK_HOSTS.contains(request.getServerName())) {
            reject(request, response, HttpStatus.FORBIDDEN, "Requests must address this Mac's loopback interface");
            return;
        }
        if (isOpenRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (request.getHeader("Origin") != null) {
            reject(request, response, HttpStatus.FORBIDDEN, "Browser-originated desktop requests are forbidden");
            return;
        }
        String expected = properties.desktopSessionToken();
        if (expected.isBlank()) {
            reject(request, response, HttpStatus.SERVICE_UNAVAILABLE, "Desktop session is not configured");
            return;
        }
        String actual = request.getHeader(AgentController.SESSION_HEADER);
        if (actual == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
            reject(request, response, HttpStatus.UNAUTHORIZED, "Invalid desktop session");
            return;
        }
        if (!servingGate.isReady() && waitsForAgentRecovery(requestPath(request)) && !isProviderStatusRequest(request)) {
            reject(request, response, HttpStatus.SERVICE_UNAVAILABLE, "Agent startup recovery is not complete");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Requests that cannot carry the session header. The renderer loads avatars as plain images, and Claude's permission MCP
     * server authenticates each task with its own bearer token.
     */
    private static boolean isOpenRequest(HttpServletRequest request)
    {
        String path = requestPath(request);
        boolean read = request.getMethod().equals("GET") || request.getMethod().equals("HEAD");
        return (read && (path.equals("/hello") || path.startsWith("/api/avatars/")))
                || (request.getMethod().equals("POST") && path.equals("/internal/agent/claude/mcp"));
    }

    private static boolean waitsForAgentRecovery(String path)
    {
        return path.startsWith("/agent")
                || path.startsWith("/api/agent-review")
                || path.equals("/api/google") || path.startsWith("/api/google/")
                || path.equals("/api/jira") || path.startsWith("/api/jira/")
                || path.equals("/api/teams") || path.startsWith("/api/teams/")
                || path.equals("/api/agenda") || path.startsWith("/api/agenda/") || path.equals("/api/settings/agenda")
                || path.equals("/api/assistant") || path.startsWith("/api/assistant/") || path.equals("/api/settings/assistant")
                || path.startsWith("/api/ci-autofix")
                || path.startsWith("/api/cherry-pick-ranges")
                || (path.startsWith("/api/workspaces/") && (path.contains("/git/") || path.contains("/cherry-pick/")));
    }

    private static boolean isProviderStatusRequest(HttpServletRequest request)
    {
        return request.getMethod().equals("GET") && requestPath(request).equals("/agent/providers/status");
    }

    private static String requestPath(HttpServletRequest request)
    {
        return StringUtils.cleanPath(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String message)
            throws IOException
    {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), new ErrorResponse(Instant.now(), status.value(), message, request.getRequestURI()));
    }
}
