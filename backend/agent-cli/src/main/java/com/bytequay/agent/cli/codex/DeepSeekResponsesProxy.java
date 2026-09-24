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

import com.bytequay.agent.spi.AgentUnavailableException;
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

/** Keeps the saved key out of Codex and its model-facing shell while forwarding Responses streams. */
final class DeepSeekResponsesProxy
        implements AutoCloseable
{
    private static final URI UPSTREAM = URI.create("https://api.deepseek.com/responses");
    private static final int MAX_REQUEST_BYTES = 16 * 1024 * 1024;

    private final HttpServer server;
    private final ExecutorService executor;
    private final HttpClient client;
    private final URI upstream;
    private final URI baseUrl;
    private final String key;
    private final AtomicBoolean closed = new AtomicBoolean();

    static DeepSeekResponsesProxy start(String key)
    {
        return start(key, UPSTREAM);
    }

    /** The upstream override allows an offline loopback provider fixture. */
    static DeepSeekResponsesProxy start(String key, URI upstream)
    {
        requireNonNull(key, "key is null");
        requireNonNull(upstream, "upstream is null");
        try {
            return new DeepSeekResponsesProxy(key, upstream);
        }
        catch (IOException e) {
            throw new AgentUnavailableException("Unable to start the isolated DeepSeek Responses proxy", e);
        }
    }

    private DeepSeekResponsesProxy(String key, URI upstream)
            throws IOException
    {
        this.key = key;
        this.upstream = upstream;
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

    private void handle(HttpExchange exchange)
            throws IOException
    {
        try (exchange) {
            if (closed.get() || !exchange.getRequestMethod().equals("POST")
                    || !exchange.getRequestURI().getRawPath().equals(baseUrl.getPath() + "responses")
                    || exchange.getRequestURI().getRawQuery() != null
                    || exchange.getRequestHeaders().containsKey("Authorization")) {
                reply(exchange, 404);
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) {
                reply(exchange, 413);
                return;
            }
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
                reply(exchange, 502);
            }
            catch (IOException e) {
                reply(exchange, 502);
            }
        }
    }

    private static void reply(HttpExchange exchange, int status)
            throws IOException
    {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, -1);
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
