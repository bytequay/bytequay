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

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.domain.NotFoundException;
import com.bytequay.app.web.GlobalExceptionHandler.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wrong-verb path: a {@code GET} on a POST-only route — the probe a
 * Streamable-HTTP MCP client (Codex's rmcp client) makes for a server→client
 * SSE stream — must come back a clean {@code 405} with an {@code Allow} header,
 * not the {@code 500} the catch-all handler would otherwise emit. A 500 there
 * makes the client tear down the MCP session and drop the bytequay tools.
 *
 * <p>The 405 is body-less on purpose: the SSE probe's {@code Accept:
 * text/event-stream} can't be satisfied by a JSON body, and a body would make
 * the resolver raise "No acceptable representation" while writing the 405.
 */
class TestGlobalExceptionHandler
{
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void wrongVerbBecomesA405WithAllowHeaderNotA500()
    {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/threads/thread-1/mcp");
        HttpRequestMethodNotSupportedException exception =
                new HttpRequestMethodNotSupportedException("GET", List.of("POST"));

        ResponseEntity<Void> response = handler.handleMethodNotSupported(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        // Body-less: nothing to content-negotiate against the SSE probe's Accept.
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getFirst(HttpHeaders.ALLOW)).contains("POST");
    }

    @Test
    void notFoundBecomesA404CarryingTheMessage()
    {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/api/credentials/AI/openai/nope/default");
        NotFoundException exception = new NotFoundException("no credential for AI/openai/nope");

        ResponseEntity<ErrorResponse> response = handler.handleNotFoundException(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(response.getBody().message()).isEqualTo("no credential for AI/openai/nope");
        assertThat(response.getBody().path()).isEqualTo("/api/credentials/AI/openai/nope/default");
    }

    /**
     * Flipping a policy switch on a closed task used to answer 500 "Internal
     * server error", so the UI could only revert the switch without saying
     * why. The rejection is the caller's answer: 409 plus the reason text.
     */
    @Test
    void rejectedCommandBecomesA409CarryingTheReason()
    {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/api/threads/t-1/tasks/t-1.k1/auto-merge");
        CommandRejectedException exception = new CommandRejectedException(
                CommandRejectedException.Reason.INVALID_STATE,
                "Terminal Task policy cannot be revised");

        ResponseEntity<ErrorResponse> response = handler.handleCommandRejected(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Terminal Task policy cannot be revised");
    }

    @Test
    void rejectedCommandForAMissingSubjectBecomesA404()
    {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/api/threads/t-1/tasks/t-1.k9/auto-merge");
        CommandRejectedException exception = new CommandRejectedException(
                CommandRejectedException.Reason.NOT_FOUND, "No current Task policy: t-1.k9");

        ResponseEntity<ErrorResponse> response = handler.handleCommandRejected(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
