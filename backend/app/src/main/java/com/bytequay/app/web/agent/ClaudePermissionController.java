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

import com.bytequay.agent.cli.claude.ClaudePermissionBroker;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

@RestController
@ConditionalOnProperty(name = "bytequay.agent.claude-enabled", havingValue = "true")
public final class ClaudePermissionController
{
    private static final String BEARER_PREFIX = "Bearer ";

    private final ClaudePermissionBroker permissions;

    public ClaudePermissionController(ClaudePermissionBroker permissions)
    {
        this.permissions = requireNonNull(permissions, "permissions is null");
    }

    @PostMapping(
            path = "/internal/agent/claude/mcp",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> handle(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin,
            @RequestBody JsonNode body)
    {
        if (origin != null) {
            return ResponseEntity.status(403).build();
        }
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return ResponseEntity.status(401).build();
        }
        ClaudePermissionBroker.McpHttpResponse response = permissions.handle(
                authorization.substring(BEARER_PREFIX.length()), body);
        return response.body()
                .map(value -> ResponseEntity.status(response.status()).body(value))
                .orElseGet(() -> ResponseEntity.status(response.status()).build());
    }
}
