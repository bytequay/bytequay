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

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDeepSeekResponsesProxy
{
    @Test
    void closesTheTaskScopedListener()
    {
        DeepSeekResponsesProxy proxy = DeepSeekResponsesProxy.start(
                "fake-secret-for-test", URI.create("http://127.0.0.1:1/responses"));
        URI responses = proxy.baseUrl().resolve("responses");

        proxy.close();

        assertThatThrownBy(() -> HttpClient.newHttpClient().send(HttpRequest.newBuilder(responses)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void forwardsOnlyResponsesWithTheSavedKeyAndStreamsTheProviderReply()
            throws Exception
    {
        AtomicInteger providerRequests = new AtomicInteger();
        AtomicReference<String> providerAuthorization = new AtomicReference<>();
        AtomicReference<String> providerBody = new AtomicReference<>();
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/responses", exchange -> {
            providerRequests.incrementAndGet();
            providerAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            providerBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] stream = "data: {\"type\":\"response.completed\"}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (exchange) {
                exchange.getResponseBody().write(stream);
            }
        });
        provider.start();
        try (DeepSeekResponsesProxy proxy = DeepSeekResponsesProxy.start(
                "fake-secret-for-test", URI.create("http://127.0.0.1:" + provider.getAddress().getPort() + "/responses"))) {
            HttpClient client = HttpClient.newHttpClient();
            URI responses = proxy.baseUrl().resolve("responses");
            HttpResponse<String> result = client.send(HttpRequest.newBuilder(responses)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"deepseek-flash\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.headers().firstValue("Content-Type")).contains("text/event-stream");
            assertThat(result.body()).isEqualTo("data: {\"type\":\"response.completed\"}\n\n");
            assertThat(providerAuthorization).hasValue("Bearer fake-secret-for-test");
            assertThat(providerBody).hasValue("{\"model\":\"deepseek-flash\"}");
            assertThat(proxy.baseUrl().toString()).doesNotContain("fake-secret-for-test");

            assertThat(client.send(HttpRequest.newBuilder(responses).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode())
                    .isEqualTo(404);
            assertThat(client.send(HttpRequest.newBuilder(responses).header("Authorization", "Bearer not-trusted")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode())
                    .isEqualTo(404);
            assertThat(client.send(HttpRequest.newBuilder(proxy.baseUrl().resolve("other"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode())
                    .isEqualTo(404);
            assertThat(providerRequests).hasValue(1);
        }
        finally {
            provider.stop(0);
        }
    }
}
