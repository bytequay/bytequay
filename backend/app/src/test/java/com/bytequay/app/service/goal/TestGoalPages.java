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
package com.bytequay.app.service.goal;

import com.google.common.collect.ImmutableList;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class TestGoalPages
{
    @Test
    void onlyAPageThatDoesNotExistIsMissing()
            throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = switch (path) {
                case "/spec" -> 200;
                case "/moved" -> 301;
                case "/gone" -> 410;
                // A server that refuses HEAD may still serve the page.
                case "/no-head" -> exchange.getRequestMethod().equals("HEAD") ? 405 : 200;
                default -> 404;
            };
            if (status == 301) {
                exchange.getResponseHeaders().add("Location", "/spec");
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            assertThat(new GoalPages().missing(ImmutableList.of(base + "/spec", base + "/moved", base + "/no-head", base + "/invented", base + "/gone", "https://bad page")))
                    .containsExactly(base + "/invented", base + "/gone", "https://bad page");
        }
        finally {
            server.stop(0);
        }
    }
}
