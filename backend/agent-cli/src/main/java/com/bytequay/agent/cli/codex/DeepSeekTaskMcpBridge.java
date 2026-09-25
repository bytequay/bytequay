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
package com.bytequay.agent.cli.codex;

import com.bytequay.agent.cli.mcp.McpHttpResponse;
import com.bytequay.agent.cli.mcp.McpRegistration;
import com.bytequay.agent.cli.mcp.PermissionBroker;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/** Serves one task's admitted MCP tools without passing its bearer token into Codex. */
final class DeepSeekTaskMcpBridge
        implements AutoCloseable
{
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;

    private final HttpServer server;
    private final ExecutorService executor;
    private final PermissionBroker permissions;
    private final ObjectMapper mapper;
    private final String token;
    private final URI endpoint;
    private final AtomicBoolean closed = new AtomicBoolean();

    static DeepSeekTaskMcpBridge start(PermissionBroker permissions, McpRegistration registration, ObjectMapper mapper)
    {
        try {
            return new DeepSeekTaskMcpBridge(permissions, registration, mapper);
        }
        catch (IOException failure) {
            throw new AgentUnavailableException("Unable to start the isolated DeepSeek task tool bridge", failure);
        }
    }

    private DeepSeekTaskMcpBridge(PermissionBroker permissions, McpRegistration registration, ObjectMapper mapper)
            throws IOException
    {
        this.permissions = requireNonNull(permissions, "permissions is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.token = requireNonNull(registration, "registration is null").token();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server.setExecutor(executor);
        this.endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/" + UUID.randomUUID() + "/mcp");
        this.server.createContext(endpoint.getPath(), this::handle);
        this.server.start();
    }

    URI endpoint()
    {
        return endpoint;
    }

    private void handle(HttpExchange exchange)
            throws IOException
    {
        try (exchange) {
            if (closed.get() || !exchange.getRequestMethod().equals("POST")
                    || !exchange.getRequestURI().getRawPath().equals(endpoint.getPath())
                    || exchange.getRequestURI().getRawQuery() != null
                    || exchange.getRequestHeaders().containsKey("Authorization")
                    || exchange.getRequestHeaders().containsKey("Origin")) {
                reply(exchange, 404, null);
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) {
                reply(exchange, 413, null);
                return;
            }
            try {
                JsonNode request = mapper.readTree(body);
                McpHttpResponse result = permissions.handle(token, request);
                reply(exchange, result.status(), result.body().isPresent() ? mapper.writeValueAsBytes(result.body().orElseThrow()) : null);
            }
            catch (JsonProcessingException failure) {
                reply(exchange, 400, null);
            }
            catch (RuntimeException failure) {
                reply(exchange, 500, null);
            }
        }
    }

    private static void reply(HttpExchange exchange, int status, byte[] body)
            throws IOException
    {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override
    public void close()
    {
        if (closed.compareAndSet(false, true)) {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
