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

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleMethodNotSupported(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(405);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ALLOW)).contains("POST");
    }
}
