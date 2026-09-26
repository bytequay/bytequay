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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/**
 * Serves one DeepSeek App Server session on loopback: {@code responses} forwards Responses streams with the saved key,
 * and {@code mcp}, when the task has tools, serves them with the task's bearer, so neither secret reaches Codex.
 */
final class DeepSeekLoopback
        implements AutoCloseable
{
    private static final URI UPSTREAM = URI.create("https://api.deepseek.com/responses");
    private static final int MAX_RESPONSES_BYTES = 16 * 1024 * 1024;
    private static final int MAX_MCP_BYTES = 1024 * 1024;

    private final HttpServer server;
    private final ExecutorService executor;
    private final HttpClient client;
    private final URI upstream;
    private final String key;
    private final PermissionBroker permissions;
    private final Optional<String> toolToken;
    private final ObjectMapper mapper;
    private final URI baseUrl;
    private final AtomicBoolean closed = new AtomicBoolean();

    static DeepSeekLoopback start(String key, PermissionBroker permissions, Optional<McpRegistration> tools, ObjectMapper mapper)
    {
        return start(key, UPSTREAM, permissions, tools, mapper);
    }

    /** The upstream override allows an offline loopback provider fixture. */
    static DeepSeekLoopback start(String key, URI upstream, PermissionBroker permissions, Optional<McpRegistration> tools, ObjectMapper mapper)
    {
        try {
            return new DeepSeekLoopback(key, upstream, permissions, tools, mapper);
        }
        catch (IOException e) {
            throw new AgentUnavailableException("Unable to start the isolated DeepSeek loopback server", e);
        }
    }

    private DeepSeekLoopback(String key, URI upstream, PermissionBroker permissions, Optional<McpRegistration> tools, ObjectMapper mapper)
            throws IOException
    {
        this.key = requireNonNull(key, "key is null");
        this.upstream = requireNonNull(upstream, "upstream is null");
        this.permissions = requireNonNull(permissions, "permissions is null");
        this.toolToken = tools.map(McpRegistration::token);
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.setExecutor(executor);
        this.baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/" + UUID.randomUUID() + "/");
        this.server.createContext(baseUrl.getPath(), this::handle);
        this.server.start();
    }

    URI baseUrl()
    {
        return baseUrl;
    }

    URI mcpEndpoint()
    {
        return baseUrl.resolve("mcp");
    }

    private void handle(HttpExchange exchange)
            throws IOException
    {
        try (exchange) {
            String path = exchange.getRequestURI().getRawPath();
            boolean responses = path.equals(baseUrl.getPath() + "responses");
            boolean mcp = toolToken.isPresent() && path.equals(mcpEndpoint().getPath());
            if (closed.get() || !exchange.getRequestMethod().equals("POST") || !(responses || mcp)
                    || exchange.getRequestURI().getRawQuery() != null
                    || exchange.getRequestHeaders().containsKey("Authorization")
                    || (mcp && exchange.getRequestHeaders().containsKey("Origin"))) {
                reply(exchange, 404, null);
                return;
            }
            int limit = responses ? MAX_RESPONSES_BYTES : MAX_MCP_BYTES;
            byte[] body = exchange.getRequestBody().readNBytes(limit + 1);
            if (body.length > limit) {
                reply(exchange, 413, null);
                return;
            }
            if (responses) {
                forward(exchange, body);
            }
            else {
                callTool(exchange, body);
            }
        }
    }

    private void forward(HttpExchange exchange, byte[] body)
            throws IOException
    {
        HttpRequest request = HttpRequest.newBuilder(upstream)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            Optional<String> type = response.headers().firstValue("Content-Type");
            exchange.getResponseHeaders().set("Content-Type", type.orElse("application/json"));
            response.headers().firstValue("Retry-After")
                    .ifPresent(value -> exchange.getResponseHeaders().set("Retry-After", value));
            exchange.sendResponseHeaders(response.statusCode(), 0);
            try (InputStream source = response.body()) {
                source.transferTo(exchange.getResponseBody());
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reply(exchange, 502, null);
        }
        catch (IOException e) {
            reply(exchange, 502, null);
        }
    }

    private void callTool(HttpExchange exchange, byte[] body)
            throws IOException
    {
        try {
            McpHttpResponse result = permissions.handle(toolToken.orElseThrow(), mapper.readTree(body));
            reply(exchange, result.status(), result.body().isPresent() ? mapper.writeValueAsBytes(result.body().orElseThrow()) : null);
        }
        catch (JsonProcessingException failure) {
            reply(exchange, 400, null);
        }
        catch (RuntimeException failure) {
            reply(exchange, 500, null);
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
            client.shutdownNow();
        }
    }
}
