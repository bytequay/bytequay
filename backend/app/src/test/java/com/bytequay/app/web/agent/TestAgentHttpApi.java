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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.shutdown=immediate",
                "bytequay.agent.desktop-session-token=test-session",
                "bytequay.agent.deterministic-enabled=true",
                "bytequay.agent.runtime-root=${java.io.tmpdir}/bytequay-agent-http-${random.uuid}"
        })
class TestAgentHttpApi
{
    private static final String SESSION_TOKEN = "test-session";
    private static final String REPOSITORY = "bytequay/agent-e2e";

    @TempDir
    private Path temporaryDirectory;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    private Path worktree;

    @BeforeEach
    void setUp()
            throws IOException, InterruptedException
    {
        worktree = temporaryDirectory.resolve("worktree");
        run("git", "init", "-b", "main", worktree.toString());
        run("git", "-C", worktree.toString(), "config", "user.name", "ByteQuay Test");
        run("git", "-C", worktree.toString(), "config", "user.email", "test@bytequay.local");
        Files.writeString(worktree.resolve("README.md"), "agent e2e\n");
        run("git", "-C", worktree.toString(), "add", "README.md");
        run("git", "-C", worktree.toString(), "commit", "-m", "Initial commit");
        run("git", "-C", worktree.toString(), "remote", "add", "origin",
                "https://github.com/" + REPOSITORY + ".git");
    }

    @Test
    void protectsAgentEndpointsAndReportsProviderStatus()
            throws Exception
    {
        assertThat(rest.getForEntity(url("/agent/providers/status"), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders originHeaders = headers();
        originHeaders.setOrigin("https://example.com");
        ResponseEntity<JsonNode> originResponse = rest.exchange(
                url("/agent/providers/status"),
                HttpMethod.GET,
                new HttpEntity<>(originHeaders),
                JsonNode.class);
        assertThat(originResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> status = get("/agent/providers/status");
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody()).isNotNull();
        assertThat(status.getBody().get(0).get("laneId").asText()).isEqualTo("deterministic");
    }

    @Test
    void validatesRequestsAndMapsUnavailableLanes()
            throws Exception
    {
        ResponseEntity<JsonNode> invalid = post(
                "/agent/tasks",
                ImmutableMap.of(
                        "laneId", "",
                        "worktree", worktree.toString(),
                        "repository", REPOSITORY,
                        "title", "Invalid"),
                null);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<JsonNode> unavailable = post(
                "/agent/tasks",
                createTaskBody("missing-lane"),
                null);
        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void createsTaskRunsIdempotentTurnsAndReplaysSse()
            throws Exception
    {
        String taskId = createTask();
        JsonNode first = startTurn(taskId, "first", "request-1");
        JsonNode repeated = startTurn(taskId, "ignored", "request-1");
        assertThat(repeated.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(get("/agent/turns/" + first.get("id").asText()).getBody().get("state").asText())
                .isEqualTo("COMPLETED");

        JsonNode second = startTurn(taskId, "second", "request-2");
        assertThat(get("/agent/turns/" + second.get("id").asText()).getBody().get("state").asText())
                .isEqualTo("COMPLETED");

        JsonNode events = get("/agent/tasks/" + taskId + "/events?after=0&limit=10").getBody();
        assertThat(events).isNotNull();
        assertThat(events.size()).isEqualTo(2);
        long firstSequence = events.get(0).get("sequence").asLong();
        assertThat(events.get(0).get("payload").get("text").asText()).isEqualTo("deterministic: first");

        JsonNode afterFirst = get("/agent/tasks/" + taskId + "/events?after=" + firstSequence + "&limit=10")
                .getBody();
        assertThat(afterFirst).isNotNull();
        assertThat(afterFirst).hasSize(1);
        assertThat(afterFirst.get(0).get("payload").get("text").asText()).isEqualTo("deterministic: second");
        assertThat(firstSseData(taskId, firstSequence)).contains("deterministic: second");

        ResponseEntity<JsonNode> completion = post(
                "/agent/tasks/" + taskId + "/complete",
                ImmutableMap.of("notes", "ready for review"),
                null);
        assertThat(completion.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completion.getBody().get("summary").get("finalResponse").asText())
                .isEqualTo("deterministic: second");
        assertThat(completion.getBody().get("summary").get("notes").asText())
                .isEqualTo("ready for review");
        assertThat(get("/agent/tasks/" + taskId).getBody().get("state").asText())
                .isEqualTo("COMPLETED");

        ResponseEntity<JsonNode> earlyCleanup = post(
                "/agent/tasks/" + taskId + "/cleanup",
                ImmutableMap.of(),
                null);
        assertThat(earlyCleanup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private String createTask()
            throws Exception
    {
        ResponseEntity<JsonNode> response = post("/agent/tasks", createTaskBody("deterministic"), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").asText();
    }

    private Map<String, String> createTaskBody(String laneId)
    {
        return ImmutableMap.of(
                "laneId", laneId,
                "worktree", worktree.toString(),
                "repository", REPOSITORY,
                "title", "Agent HTTP test");
    }

    private JsonNode startTurn(String taskId, String message, String idempotencyKey)
            throws Exception
    {
        ResponseEntity<JsonNode> response = post(
                "/agent/tasks/" + taskId + "/turns",
                ImmutableMap.of("message", message),
                idempotencyKey);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> get(String path)
    {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers()), JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(String path, Object body, String idempotencyKey)
            throws Exception
    {
        HttpHeaders requestHeaders = headers();
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            requestHeaders.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange(
                url(path),
                HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), requestHeaders),
                JsonNode.class);
    }

    private String firstSseData(String taskId, long after)
            throws IOException, InterruptedException
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url(
                        "/agent/tasks/" + taskId + "/stream?after=" + after)))
                .header(AgentController.SESSION_HEADER, SESSION_TOKEN)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<Stream<String>> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        try (Stream<String> lines = response.body()) {
            return lines.filter(line -> line.startsWith("data:"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private HttpHeaders headers()
    {
        HttpHeaders headers = new HttpHeaders();
        headers.set(AgentController.SESSION_HEADER, SESSION_TOKEN);
        return headers;
    }

    private String url(String path)
    {
        return "http://127.0.0.1:" + port + path;
    }

    private static void run(String... command)
            throws IOException, InterruptedException
    {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Command failed: " + output);
        }
    }
}
