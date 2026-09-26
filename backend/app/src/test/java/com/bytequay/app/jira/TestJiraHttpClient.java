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
package com.bytequay.app.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestJiraHttpClient
{
    @Test
    void rejectsUnsafeCredentialCharactersWithoutEchoingTheSecret()
    {
        JiraHttpClient client = new JiraHttpClient(new ObjectMapper());
        assertThatThrownBy(() -> client.request(URI.create("https://example.invalid"), "GET", Optional.of("private-bearer\r\nsecret"), Optional.empty()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("credential response was invalid").hasMessageNotContaining("private-bearer");
        assertThatThrownBy(() -> client.request(URI.create("https://example.invalid"), "GET", Optional.of("private bearer"), Optional.empty()))
                .isInstanceOf(ResponseStatusException.class).hasMessageNotContaining("private bearer");
    }

    @Test
    void neverFollowsRedirectsOrForwardsRemoteErrorBodies()
            throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger redirected = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/secret");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/secret", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/error", exchange -> {
            byte[] body = "sensitive-token-from-remote-error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            JiraHttpClient client = new JiraHttpClient(new ObjectMapper(), Duration.ofSeconds(3));
            assertThatThrownBy(() -> client.request(uri(server, "/redirect"), "GET", Optional.of("secret-token"), Optional.empty()))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("HTTP 302");
            assertThat(redirected).hasValue(0);
            assertThatThrownBy(() -> client.request(uri(server, "/error"), "GET", Optional.empty(), Optional.empty()))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("HTTP 401").hasMessageNotContaining("sensitive-token");
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void boundsOversizedAndStalledResponseBodies()
            throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/large", exchange -> {
            byte[] body = new byte[4 * 1024 * 1024 + 1];
            Arrays.fill(body, (byte) ' ');
            body[0] = '[';
            body[body.length - 1] = ']';
            exchange.sendResponseHeaders(200, body.length);
            try {
                exchange.getResponseBody().write(body);
            }
            finally {
                exchange.close();
            }
        });
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 10);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try {
                release.await(5, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finally {
                exchange.close();
            }
        });
        server.start();
        try {
            JiraHttpClient client = new JiraHttpClient(new ObjectMapper(), Duration.ofSeconds(3));
            assertThatThrownBy(() -> client.request(uri(server, "/large"), "GET", Optional.empty(), Optional.empty()))
                    .isInstanceOf(ResponseStatusException.class);
            JiraHttpClient shortDeadline = new JiraHttpClient(new ObjectMapper(), Duration.ofMillis(300));
            long start = System.nanoTime();
            assertThatThrownBy(() -> shortDeadline.request(uri(server, "/slow"), "GET", Optional.empty(), Optional.empty()))
                    .isInstanceOf(ResponseStatusException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        }
        finally {
            release.countDown();
            server.stop(0);
        }
    }

    private static URI uri(HttpServer server, String path)
    {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }
}
