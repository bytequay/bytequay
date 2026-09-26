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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.Objects.requireNonNull;

/** Bounded requests with redirects disabled: credentials never follow a remote redirect. */
@Component
public class JiraHttpClient
{
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Duration timeout;

    @Autowired
    public JiraHttpClient(ObjectMapper mapper)
    {
        this(mapper, Duration.ofSeconds(30));
    }

    JiraHttpClient(ObjectMapper mapper, Duration timeout)
    {
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.timeout = requireNonNull(timeout, "timeout is null");
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public JsonNode request(URI uri, String method, Optional<String> token, Optional<Object> body)
    {
        return authorizedRequest(uri, method, token.map(value -> "Bearer " + credential(value)), body);
    }

    public JsonNode basicRequest(URI uri, String method, String email, String token, Optional<Object> body)
    {
        if (email == null || email.contains(":")) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Jira credential response was invalid.");
        }
        String encoded = Base64.getEncoder().encodeToString((credential(email) + ":" + credential(token)).getBytes(StandardCharsets.UTF_8));
        return authorizedRequest(uri, method, Optional.of("Basic " + encoded), body);
    }

    private static String credential(String value)
    {
        if (value == null || value.isEmpty() || value.length() > 16384 || !value.chars().allMatch(character -> character > 32 && character < 127)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Jira credential response was invalid.");
        }
        return value;
    }

    private JsonNode authorizedRequest(URI uri, String method, Optional<String> authorization, Optional<Object> body)
    {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json");
            authorization.ifPresent(value -> request.header("Authorization", value));
            if (body.isPresent()) {
                request.header("Content-Type", "application/json");
            }
            request.method(method, body.isPresent() ? HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body.orElseThrow())) : HttpRequest.BodyPublishers.noBody());
            var pending = client.sendAsync(request.build(), HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_BYTES));
            HttpResponse<byte[]> response;
            try {
                // Bound the complete body, including a peer that sends headers then stops sending bytes.
                response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
            finally {
                if (!pending.isDone()) {
                    pending.cancel(true);
                }
            }
            byte[] bytes = response.body();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                HttpStatus status = switch (response.statusCode()) {
                    case 401 -> HttpStatus.UNAUTHORIZED;
                    case 403 -> HttpStatus.FORBIDDEN;
                    case 404 -> HttpStatus.NOT_FOUND;
                    case 409 -> HttpStatus.CONFLICT;
                    case 429 -> HttpStatus.TOO_MANY_REQUESTS;
                    case 400, 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
                    default -> HttpStatus.BAD_GATEWAY;
                };
                // Do not forward remote bodies: authentication servers can include sensitive request data.
                throw new ResponseStatusException(status, "Jira request was rejected (HTTP " + response.statusCode() + "). Refresh or check your connection and permissions.");
            }
            return bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Jira request was interrupted.");
        }
        catch (IOException | ExecutionException | TimeoutException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Jira could not be reached. Check your connection and try again.");
        }
    }
}
