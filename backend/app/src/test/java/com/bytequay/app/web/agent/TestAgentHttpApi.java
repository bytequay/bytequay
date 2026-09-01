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

import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.app.agent.impl.DeterministicAgentDriver;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspacePaths;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkState;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.shutdown=immediate",
                "bytequay.agent.desktop-session-token=test-session",
                "bytequay.agent.runtime-root=${java.io.tmpdir}/bytequay-agent-http-${random.uuid}",
                "bytequay.workspace.root=${java.io.tmpdir}/bytequay-agent-workspace-${random.uuid}"
        })
class TestAgentHttpApi
{
    @TestConfiguration
    static class DeterministicLane
    {
        @Bean
        AgentDriver deterministicAgentDriver()
        {
            return new DeterministicAgentDriver(AgentLaneId.CODEX_CLI);
        }
    }

    private static final String SESSION_TOKEN = "test-session";
    private static final String REPOSITORY = "bytequay/agent-e2e";
    private static final String ORIGIN = "https://github.com/" + REPOSITORY + ".git";
    private static final AtomicLong NEXT_REPOSITORY_ID = new AtomicLong(10_000);
    private static final String BASELINE_FIXTURE = "/com/bytequay/app/web/agent/agent-http-baseline.json";
    private static final Set<String> TIMESTAMP_FIELDS = ImmutableSet.of(
            "createdAt",
            "updatedAt",
            "startedAt",
            "endedAt",
            "cancellationRequestedAt",
            "occurredAt",
            "terminalAt",
            "completedAt",
            "deleteAfter");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkspacePaths workspacePaths;

    @Autowired
    private WorkspaceStore workspaces;

    @Autowired
    private ModelSettingsService models;

    private String workspaceId;

    @BeforeEach
    void setUp()
            throws IOException, InterruptedException
    {
        models.refreshCatalogs();
        workspaceId = "agent-http-" + UUID.randomUUID();
        long repositoryId = NEXT_REPOSITORY_ID.incrementAndGet();
        Path repository = workspacePaths.repository(workspaceId);
        Files.createDirectories(repository);
        Files.writeString(
                workspacePaths.workspace(workspaceId).resolve(".bytequay-workspace"),
                workspaceId + "\n" + repositoryId + "\n");
        run("git", "init", "-b", "main", repository.toString());
        run("git", "-C", repository.toString(), "config", "user.name", "ByteQuay Test");
        run("git", "-C", repository.toString(), "config", "user.email", "test@bytequay.local");
        Files.writeString(repository.resolve("README.md"), "agent e2e\n");
        run("git", "-C", repository.toString(), "add", "README.md");
        run("git", "-C", repository.toString(), "commit", "-m", "Initial commit");
        run("git", "-C", repository.toString(), "remote", "add", "origin", ORIGIN);
        run("git", "-C", repository.toString(), "update-ref", "refs/remotes/origin/main", "HEAD");

        Instant now = Instant.now();
        RepoRef selected = RepoRef.of("bytequay", "agent-e2e");
        workspaces.createOrGet(new Workspace(
                workspaceId,
                "Agent HTTP test",
                repositoryId,
                selected,
                Optional.empty(),
                Optional.empty(),
                repositoryId,
                selected,
                Workspace.Topology.DIRECT,
                "main",
                ORIGIN,
                Optional.empty(),
                Optional.empty(),
                repository.toString(),
                Optional.of(now),
                Optional.of(now),
                0,
                now,
                now));
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
        assertThat(status.getBody().get(0).get("laneId").asText()).isEqualTo("codex");
        assertThat(status.getBody().get(0).get("readiness").asText()).isEqualTo("READY");
        assertThat(status.getBody().get(0).has("health")).isFalse();
    }

    @Test
    void rejectsInvalidAndNonCodexTaskRequests()
            throws Exception
    {
        ResponseEntity<JsonNode> invalid = post(
                "/agent/tasks",
                ImmutableMap.of(
                        "workspaceId", "",
                        "laneId", "codex",
                        "title", "Invalid"),
                null);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<JsonNode> unsupported = post(
                "/agent/tasks",
                createTaskBody("missing-lane"),
                null);
        assertThat(unsupported.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<JsonNode> unknownModel = post(
                "/agent/tasks",
                ImmutableMap.of(
                        "workspaceId", workspaceId,
                        "laneId", "codex",
                        "title", "Unknown model",
                        "defaults", ImmutableMap.of(
                                "model", "unknown-model",
                                "effort", "high",
                                "permissionPolicy", "AUTO")),
                null);
        assertThat(unknownModel.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/agent/tasks?workspaceId=" + workspaceId).getBody()).isEmpty();
        assertThat(workspacePaths.worktrees(workspaceId)).doesNotExist();
    }

    @Test
    void rejectsRendererSuppliedAttachmentPaths()
            throws Exception
    {
        ResponseEntity<JsonNode> createResponse = post("/agent/tasks", createTaskBody("codex"), null);
        String taskId = createResponse.getBody().required("id").asText();

        ResponseEntity<JsonNode> response = post(
                "/agent/tasks/" + taskId + "/turns",
                ImmutableMap.of(
                        "message", "inspect this image",
                        "attachments", ImmutableList.of(ImmutableMap.of(
                                "name", "screen.png",
                                "mediaType", "image/png",
                                "path", "/tmp/screen.png"))),
                "attachment-request");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createsTaskRunsIdempotentTurnsAndReplaysSse()
            throws Exception
    {
        Map<String, Object> createRequest = createTaskBody("codex");
        ResponseEntity<JsonNode> createResponse = post("/agent/tasks", createRequest, null);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode createdTask = createResponse.getBody();
        assertThat(createdTask).isNotNull();
        String taskId = createdTask.get("id").asText();
        assertThat(createdTask.get("workspaceId").asText()).isEqualTo(workspaceId);
        assertThat(createdTask.has("worktree")).isFalse();
        assertThat(createdTask.has("repository")).isFalse();
        assertThat(createdTask.has("sessionReference")).isFalse();

        JsonNode listed = get("/agent/tasks?workspaceId=" + workspaceId + "&limit=10").getBody();
        assertThat(listed).hasSize(1);
        assertThat(get("/agent/tasks/" + taskId + "?workspaceId=another-workspace").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode first = startTurn(taskId, "first", "request-1");
        JsonNode repeated = startTurn(taskId, "ignored", "request-1");
        assertThat(repeated.get("id").asText()).isEqualTo(first.get("id").asText());
        JsonNode firstTerminal = get("/agent/turns/" + first.get("id").asText()).getBody();
        assertThat(firstTerminal.get("state").asText()).isEqualTo("COMPLETED");

        JsonNode second = startTurn(taskId, "second", "request-2");
        JsonNode secondTerminal = get("/agent/turns/" + second.get("id").asText()).getBody();
        assertThat(secondTerminal.get("state").asText()).isEqualTo("COMPLETED");

        JsonNode events = get("/agent/tasks/" + taskId + "/events?after=0&limit=10").getBody();
        assertThat(events).isNotNull();
        assertThat(events).hasSize(4);
        assertThat(events)
                .extracting(event -> event.get("type").asText())
                .containsExactly("user_message", "assistant_message", "user_message", "assistant_message");
        assertThat(events.get(0).get("payload").get("text").asText()).isEqualTo("first");
        assertThat(events.get(1).get("payload").get("text").asText()).isEqualTo("deterministic: first");
        assertThat(events.get(2).get("payload").get("text").asText()).isEqualTo("second");
        assertThat(events.get(3).get("payload").get("text").asText()).isEqualTo("deterministic: second");
        long firstSequence = events.get(1).get("sequence").asLong();
        assertThat(events.get(0).get("schemaVersion").asInt()).isEqualTo(2);
        assertThat(events.get(0).get("eventId").asText()).isNotBlank();
        assertThat(events.get(0).get("source").isObject()).isTrue();
        assertThat(events.get(0).get("source").get("logicalAgentPath").isNull()).isTrue();

        JsonNode afterFirst = get("/agent/tasks/" + taskId + "/events?after=" + firstSequence + "&limit=10")
                .getBody();
        assertThat(afterFirst).isNotNull();
        assertThat(afterFirst).hasSize(2);
        assertThat(afterFirst.get(0).get("type").asText()).isEqualTo("user_message");
        assertThat(afterFirst.get(0).get("payload").get("text").asText()).isEqualTo("second");
        assertThat(afterFirst.get(1).get("type").asText()).isEqualTo("assistant_message");
        assertThat(afterFirst.get(1).get("payload").get("text").asText()).isEqualTo("deterministic: second");
        JsonNode sseEvent = objectMapper.readTree(firstSseData(taskId, firstSequence));
        assertThat(sseEvent).isEqualTo(afterFirst.get(0));

        Map<String, String> completionRequest = ImmutableMap.of("notes", "ready for review");
        ResponseEntity<JsonNode> completion = post(
                "/agent/tasks/" + taskId + "/complete",
                completionRequest,
                null);
        assertThat(completion.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completion.getBody().get("summary").get("finalResponse").asText())
                .isEqualTo("deterministic: second");
        assertThat(completion.getBody().get("summary").get("notes").asText())
                .isEqualTo("ready for review");
        JsonNode finalTask = get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId).getBody();
        assertThat(finalTask.get("state").asText()).isEqualTo("COMPLETED");

        ObjectNode transcript = objectMapper.createObjectNode();
        transcript.set("createRequest", objectMapper.valueToTree(createRequest));
        transcript.set("createResponse", createdTask);
        ArrayNode turns = transcript.putArray("turns");
        addTurnTranscript(turns, "request-1", "first", first, firstTerminal);
        addTurnTranscript(turns, "request-1", "ignored", repeated, firstTerminal);
        addTurnTranscript(turns, "request-2", "second", second, secondTerminal);
        transcript.set("replay", events);
        transcript.set("replayAfterFirst", afterFirst);
        transcript.set("sse", sseEvent);
        transcript.set("completionRequest", objectMapper.valueToTree(completionRequest));
        transcript.set("completionResponse", completion.getBody());
        transcript.set("finalTask", finalTask);

        JsonNode sanitizedTranscript = sanitizeTranscript(
                transcript,
                taskId,
                first.get("id").asText(),
                second.get("id").asText());
        try (InputStream fixture = getClass().getResourceAsStream(BASELINE_FIXTURE)) {
            assertThat(fixture).as("baseline fixture %s", BASELINE_FIXTURE).isNotNull();
            assertThat(sanitizedTranscript).isEqualTo(objectMapper.readTree(fixture));
        }

        ResponseEntity<JsonNode> earlyCleanup = post(
                "/agent/tasks/" + taskId + "/cleanup",
                ImmutableMap.of(),
                null);
        assertThat(earlyCleanup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private void addTurnTranscript(
            ArrayNode turns,
            String idempotencyKey,
            String message,
            JsonNode acceptedResponse,
            JsonNode terminalResponse)
    {
        ObjectNode turn = turns.addObject();
        turn.put("idempotencyKey", idempotencyKey);
        turn.set("request", objectMapper.valueToTree(ImmutableMap.of("message", message)));
        turn.set("acceptedResponse", acceptedResponse);
        turn.set("terminalResponse", terminalResponse);
    }

    private JsonNode sanitizeTranscript(
            JsonNode transcript,
            String taskId,
            String firstTurnId,
            String secondTurnId)
    {
        Map<String, String> values = ImmutableMap.<String, String>builder()
                .put(workspaceId, "<workspace-id>")
                .put(taskId, "<task-id>")
                .put(firstTurnId, "<turn-1>")
                .put(secondTurnId, "<turn-2>")
                .buildOrThrow();
        JsonNode sanitized = transcript.deepCopy();
        sanitizeNode(sanitized, values);
        return sanitized;
    }

    private static void sanitizeNode(JsonNode node, Map<String, String> replacements)
    {
        if (node.isArray()) {
            node.forEach(child -> sanitizeNode(child, replacements));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        ObjectNode object = (ObjectNode) node;
        List<String> fields = new ArrayList<>();
        object.fieldNames().forEachRemaining(fields::add);
        for (String field : fields) {
            JsonNode value = object.get(field);
            if ((field.equals("head") || field.equals("resultHead")) && value.isTextual()) {
                object.put(field, "<head>");
                continue;
            }
            if (field.equals("elapsed") && value.isTextual()) {
                object.put(field, "<duration>");
                continue;
            }
            if (field.equals("eventId") && value.isTextual()) {
                object.put(field, "<event-id>");
                continue;
            }
            if (TIMESTAMP_FIELDS.contains(field) && value.isTextual()) {
                object.put(field, "<timestamp>");
                continue;
            }
            if (value.isTextual()) {
                String text = value.asText();
                for (Map.Entry<String, String> replacement : replacements.entrySet()) {
                    text = text.replace(replacement.getKey(), replacement.getValue());
                }
                object.put(field, text);
                continue;
            }
            sanitizeNode(value, replacements);
        }
    }

    private Map<String, Object> createTaskBody(String laneId)
    {
        return ImmutableMap.of(
                "workspaceId", workspaceId,
                "laneId", laneId,
                "title", "Agent HTTP test",
                "defaults", ImmutableMap.of(
                        "model", "test-model",
                        "effort", "high",
                        "permissionPolicy", "AUTO"));
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
                    .map(line -> line.substring("data:".length()).stripLeading())
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
        checkState(exitCode == 0, "Command failed: " + output);
    }
}
