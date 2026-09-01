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
import com.bytequay.app.web.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

import static java.util.Objects.requireNonNull;

@Component
public final class AgentRequestFilter
        extends OncePerRequestFilter
{
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
    protected boolean shouldNotFilter(HttpServletRequest request)
    {
        String path = request.getRequestURI();
        return !path.startsWith("/agent")
                && !path.startsWith("/api/ci-autofix")
                && !path.startsWith("/api/cherry-pick-ranges")
                && !(path.startsWith("/api/workspaces/")
                        && (path.contains("/git/") || path.contains("/cherry-pick/")));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException
    {
        if (request.getHeader("Origin") != null) {
            reject(request, response, HttpStatus.FORBIDDEN, "Browser-originated agent requests are forbidden");
            return;
        }
        String expected = properties.desktopSessionToken();
        if (expected.isBlank()) {
            reject(request, response, HttpStatus.SERVICE_UNAVAILABLE, "Agent desktop session is not configured");
            return;
        }
        String actual = request.getHeader(AgentController.SESSION_HEADER);
        if (actual == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
            reject(request, response, HttpStatus.UNAUTHORIZED, "Invalid agent desktop session");
            return;
        }
        if (!servingGate.isReady() && !isProviderStatusRequest(request)) {
            reject(request, response, HttpStatus.SERVICE_UNAVAILABLE, "Agent startup recovery is not complete");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isProviderStatusRequest(HttpServletRequest request)
    {
        return request.getMethod().equals("GET") && request.getRequestURI().equals("/agent/providers/status");
    }

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String message)
            throws IOException
    {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), new GlobalExceptionHandler.ErrorResponse(
                Instant.now(),
                status.value(),
                message,
                request.getRequestURI()));
    }
}
