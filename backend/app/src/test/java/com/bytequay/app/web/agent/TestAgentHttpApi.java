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

import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.app.agent.InteractiveAgentTaskCreator;
import com.bytequay.app.agent.impl.DeterministicAgentDriver;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspacePaths;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.PullRequestStore;
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
import org.springframework.beans.factory.annotation.Qualifier;
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

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
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
        DeterministicAgentDriver deterministicAgentDriver()
        {
            return new DeterministicAgentDriver(AgentLaneId.CODEX_CLI);
        }

        @Bean
        DeterministicAgentDriver deterministicClaudeAgentDriver()
        {
            return new DeterministicAgentDriver(AgentLaneId.CLAUDE_CLI);
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
            "deleteAfter",
            "acquiredAt",
            "checkoutRemovedAt");

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

    @Autowired
    private AgentTaskService tasks;

    @Autowired
    private AgentTurnService turns;

    @Autowired
    private AgentResourceService resources;

    @Autowired
    private AgentCompletionService completions;

    @Autowired
    private InteractiveAgentTaskCreator taskCreator;

    @Autowired
    @Qualifier("deterministicAgentDriver")
    private DeterministicAgentDriver driver;

    @Autowired
    @Qualifier("deterministicClaudeAgentDriver")
    private DeterministicAgentDriver claudeDriver;

    @Autowired
    private PullRequestStore pullRequests;

    private String workspaceId;
    private Path repository;

    @BeforeEach
    void setUp()
            throws IOException, InterruptedException
    {
        driver.reset();
        claudeDriver.reset();
        models.refreshCatalogs();
        workspaceId = "agent-http-" + UUID.randomUUID();
        long repositoryId = NEXT_REPOSITORY_ID.incrementAndGet();
        repository = workspacePaths.repository(workspaceId);
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
    void listsThePullRequestsOnTheTasksBranch()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        AgentTask task = tasks.get(TaskId.valueOf(taskId));
        String branch = task.taskWorktree().orElseThrow().generatedBranch();
        String path = "/agent/tasks/" + taskId + "/pull-requests?workspaceId=";
        assertThat(get(path + workspaceId).getBody()).isEmpty();

        pullRequests.saveRepositorySyncPage(
                REPOSITORY,
                ImmutableList.of(fetchedPullRequest(482, branch), fetchedPullRequest(483, "feature")),
                "seed");

        JsonNode listed = get(path + workspaceId).getBody();
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("number").asInt()).isEqualTo(482);
        assertThat(listed.get(0).get("headRef").asText()).isEqualTo(branch);
        // Another workspace cannot see this task, let alone its pull requests.
        assertThat(get(path + "another-workspace").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static FetchedPullRequest fetchedPullRequest(int number, String headRef)
    {
        Instant at = Instant.parse("2026-09-01T00:00:00Z");
        return new FetchedPullRequest(
                (long) number,
                REPOSITORY,
                number,
                "PR " + number,
                "alice",
                "https://github.com/" + REPOSITORY + "/pull/" + number,
                at,
                at,
                PullRequest.Origin.AUTHORED,
                ImmutableList.of(),
                ImmutableMap.of(),
                false,
                "open",
                null,
                null,
                ImmutableList.of(),
                null,
                null,
                headRef);
    }

    @Test
    void rejectsInvalidAndUnavailableTaskRequests()
            throws Exception
    {
        ResponseEntity<JsonNode> invalid = post(
                "/agent/tasks",
                ImmutableMap.of(
                        "workspaceId", "",
                        "laneId", "codex",
                        "message", "Invalid"),
                null);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<JsonNode> unsupported = post(
                "/agent/tasks",
                createTaskBody("missing-lane"),
                null);
        assertThat(unsupported.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        ResponseEntity<JsonNode> unknownModel = post(
                "/agent/tasks",
                createTaskBody("codex", "unknown-model", "Unknown model"),
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
                        "expectedVersion", taskVersion(taskId),
                        "attachments", ImmutableList.of(ImmutableMap.of(
                                "name", "screen.png",
                                "mediaType", "image/png",
                                "path", "/tmp/screen.png"))),
                "attachment-request");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsAStartWhenTaskDefaultsChangedAfterTheRendererObservedThem()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        long observedVersion = taskVersion(taskId);
        updateDefaults(taskId, "text-only-model");

        ResponseEntity<JsonNode> stale = post(
                "/agent/tasks/" + taskId + "/turns",
                ImmutableMap.of("message", "stale", "expectedVersion", observedVersion),
                "stale-defaults-start");

        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(get("/agent/tasks/" + taskId + "/events?after=0&limit=20").getBody())
                .filteredOn(event -> event.required("type").asText().equals("user_message"))
                .hasSize(1);
    }

    @Test
    void completesOnlyInteractiveTasksFromTheirWorkspace()
            throws Exception
    {
        JsonNode interactive = post("/agent/tasks", createTaskBody("codex"), null).getBody();
        String interactiveTaskId = interactive.required("id").asText();

        ResponseEntity<JsonNode> wrongWorkspace = post(
                "/agent/tasks/" + interactiveTaskId + "/complete?workspaceId=another-workspace",
                ImmutableMap.of(),
                null);
        assertThat(wrongWorkspace.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(
                "/agent/tasks/" + interactiveTaskId + "/cleanup?workspaceId=another-workspace",
                ImmutableMap.of(),
                null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/agent/tasks/" + interactiveTaskId + "?workspaceId=" + workspaceId)
                .getBody().required("state").asText()).isEqualTo("OPEN");

        TaskId workflowTaskId = TaskId.random();
        var workflowTask = tasks.create(new CreateAgentTaskCommand(
                workflowTaskId,
                workspaceId,
                AgentLaneId.CODEX_CLI,
                RepositoryIdentity.valueOf(REPOSITORY),
                run("git", "-C", repository.toString(), "rev-parse", "--verify", "HEAD^{commit}"),
                "Workflow-owned task",
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO)));
        ResponseEntity<JsonNode> nonInteractive = post(
                "/agent/tasks/" + workflowTaskId + "/complete?workspaceId=" + workspaceId,
                ImmutableMap.of(),
                null);
        assertThat(nonInteractive.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(
                "/agent/tasks/" + workflowTaskId + "/cleanup?workspaceId=" + workspaceId,
                ImmutableMap.of(),
                null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(tasks.get(workflowTaskId).state().name()).isEqualTo("OPEN");
        assertThat(workflowTask.taskWorktree().orElseThrow().canonicalWorktree()).exists();
    }

    @Test
    void marksAnIdleTaskFailedAndRemovesOnlyACleanWorktree()
            throws Exception
    {
        JsonNode clean = post("/agent/tasks", createTaskBody("codex"), null).getBody();
        String cleanTaskId = clean.required("id").asText();
        Path cleanWorktree = tasks.get(TaskId.valueOf(cleanTaskId)).taskWorktree().orElseThrow().canonicalWorktree();
        assertThat(post(
                "/agent/tasks/" + cleanTaskId + "/mark-failed?workspaceId=another-workspace",
                ImmutableMap.of("reason", "wrong workspace"),
                null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<JsonNode> failed = post(
                "/agent/tasks/" + cleanTaskId + "/mark-failed?workspaceId=" + workspaceId,
                ImmutableMap.of("reason", "giving up"),
                null);

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(failed.getBody().get("state").asText()).isEqualTo("FAILED");
        assertThat(failed.getBody().get("terminalReason").asText()).isEqualTo("giving up");
        assertThat(failed.getBody().get("mutationClaim").isNull()).isTrue();
        // Nothing was written, so the checkout goes while its branch stays behind as the result.
        assertThat(failed.getBody().get("checkoutRemovedAt").isTextual()).isTrue();
        assertThat(cleanWorktree).doesNotExist();
        assertThat(run("git", "-C", repository.toString(), "branch", "--list", "bytequay/task/" + cleanTaskId))
                .contains(cleanTaskId);

        JsonNode dirty = post("/agent/tasks", createTaskBody("codex"), null).getBody();
        String dirtyTaskId = dirty.required("id").asText();
        Path dirtyWorktree = tasks.get(TaskId.valueOf(dirtyTaskId)).taskWorktree().orElseThrow().canonicalWorktree();
        Files.writeString(dirtyWorktree.resolve("notes.txt"), "half-finished\n");

        JsonNode retained = post(
                "/agent/tasks/" + dirtyTaskId + "/mark-failed?workspaceId=" + workspaceId,
                ImmutableMap.of("reason", "giving up"),
                null).getBody();

        // Dirty work is evidence, not garbage: the task fails but its checkout stays for inspection.
        assertThat(retained.get("state").asText()).isEqualTo("FAILED");
        assertThat(retained.get("checkoutRemovedAt").isNull()).isTrue();
        assertThat(dirtyWorktree.resolve("notes.txt")).exists();
    }

    @Test
    void stopsATasksRuntimeOnlyWithTheConfirmedGeneration()
            throws Exception
    {
        JsonNode created = post("/agent/tasks", createTaskBody("codex"), null).getBody();
        String taskId = created.required("id").asText();
        String impactPath = "/agent/tasks/" + taskId + "/runtime/stop-impact?workspaceId=" + workspaceId;
        String stopPath = "/agent/tasks/" + taskId + "/runtime/stop?workspaceId=" + workspaceId;

        JsonNode idle = get(impactPath).getBody();
        assertThat(idle.get("activeTurnId").isNull()).isTrue();
        assertThat(idle.get("liveProcess").asBoolean()).isTrue();
        assertThat(post(stopPath, ImmutableMap.of("generation", "task:999"), null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        startTurn(taskId, "first", "request-1");
        JsonNode afterTurn = get(impactPath).getBody();
        // The deterministic turn completes at once, but its session, and so its process, stays open.
        assertThat(afterTurn.get("activeTurnId").isNull()).isTrue();
        assertThat(afterTurn.get("liveProcess").asBoolean()).isTrue();
        // A confirmation from before the turn is stale: the turn moved the task on.
        assertThat(post(stopPath, ImmutableMap.of("generation", idle.get("generation").asText()), null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<JsonNode> stopped = post(
                stopPath,
                ImmutableMap.of("generation", afterTurn.get("generation").asText()),
                null);

        assertThat(stopped.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stopped.getBody().get("state").asText()).isEqualTo("OPEN");
        assertThat(stopped.getBody().get("mutationClaim").isNull()).isTrue();
        assertThat(get(impactPath).getBody().get("liveProcess").asBoolean()).isFalse();
    }

    @Test
    void createsTaskRunsIdempotentTurnsAndReplaysSse()
            throws Exception
    {
        Map<String, Object> createRequest = new HashMap<>(createTaskBody("codex"));
        createRequest.put("message", "first");
        createRequest.put("idempotencyKey", "request-1");
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

        String firstTurnId = get("/agent/tasks/" + taskId + "/events?after=0&limit=10")
                .getBody().get(0).required("turnId").asText();
        JsonNode first = get("/agent/turns/" + firstTurnId).getBody();
        JsonNode repeated = startTurn(taskId, "ignored", "request-1");
        assertThat(repeated.get("id").asText()).isEqualTo(first.get("id").asText());
        JsonNode firstTerminal = get("/agent/turns/" + first.get("id").asText()).getBody();
        assertThat(firstTerminal.get("state").asText()).isEqualTo("COMPLETED");

        JsonNode second = startTurn(taskId, "second", "request-2");
        JsonNode secondTerminal = get("/agent/turns/" + second.get("id").asText()).getBody();
        assertThat(secondTerminal.get("state").asText()).isEqualTo("COMPLETED");

        JsonNode events = get("/agent/tasks/" + taskId + "/events?after=0&limit=10").getBody();
        assertThat(events).isNotNull();
        assertThat(events).hasSize(9);
        assertThat(events)
                .extracting(event -> event.get("type").asText())
                .containsExactly(
                        "user_message",
                        "lifecycle",
                        "lifecycle",
                        "assistant_message",
                        "lifecycle",
                        "user_message",
                        "lifecycle",
                        "assistant_message",
                        "lifecycle");
        assertThat(events.get(0).get("payload").get("text").asText()).isEqualTo("first");
        assertThat(events.get(1).get("payload").get("phase").asText()).isEqualTo("SESSION_OPENED");
        assertThat(events.get(2).get("payload").get("phase").asText()).isEqualTo("TURN_STARTED");
        assertThat(events.get(3).get("payload").get("text").asText()).isEqualTo("deterministic: first");
        assertThat(events.get(4).get("payload").get("phase").asText()).isEqualTo("TURN_COMPLETED");
        assertThat(events.get(5).get("payload").get("text").asText()).isEqualTo("second");
        assertThat(events.get(6).get("payload").get("phase").asText()).isEqualTo("TURN_STARTED");
        assertThat(events.get(7).get("payload").get("text").asText()).isEqualTo("deterministic: second");
        assertThat(events.get(8).get("payload").get("phase").asText()).isEqualTo("TURN_COMPLETED");
        long firstSequence = events.get(4).get("sequence").asLong();
        assertThat(events.get(0).get("schemaVersion").asInt()).isEqualTo(2);
        assertThat(events.get(0).get("eventId").asText()).isNotBlank();
        assertThat(events.get(0).get("source").isObject()).isTrue();
        assertThat(events.get(0).get("source").get("logicalAgentPath").isNull()).isTrue();

        JsonNode afterFirst = get("/agent/tasks/" + taskId + "/events?after=" + firstSequence + "&limit=10")
                .getBody();
        assertThat(afterFirst).isNotNull();
        assertThat(afterFirst).hasSize(4);
        assertThat(afterFirst.get(0).get("type").asText()).isEqualTo("user_message");
        assertThat(afterFirst.get(0).get("payload").get("text").asText()).isEqualTo("second");
        assertThat(afterFirst.get(1).get("type").asText()).isEqualTo("lifecycle");
        assertThat(afterFirst.get(1).get("payload").get("phase").asText()).isEqualTo("TURN_STARTED");
        assertThat(afterFirst.get(2).get("type").asText()).isEqualTo("assistant_message");
        assertThat(afterFirst.get(2).get("payload").get("text").asText()).isEqualTo("deterministic: second");
        assertThat(afterFirst.get(3).get("type").asText()).isEqualTo("lifecycle");
        assertThat(afterFirst.get(3).get("payload").get("phase").asText()).isEqualTo("TURN_COMPLETED");
        JsonNode sseEvent = objectMapper.readTree(firstSseData(taskId, firstSequence));
        assertThat(sseEvent).isEqualTo(afterFirst.get(0));

        Map<String, String> completionRequest = ImmutableMap.of("notes", "ready for review");
        ResponseEntity<JsonNode> completion = post(
                "/agent/tasks/" + taskId + "/complete?workspaceId=" + workspaceId,
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

        ResponseEntity<JsonNode> repeatedCleanup = post(
                "/agent/tasks/" + taskId + "/cleanup?workspaceId=" + workspaceId,
                ImmutableMap.of(),
                null);
        assertThat(repeatedCleanup.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeatedCleanup.getBody().required("state").asText()).isEqualTo("COMPLETED");
        assertThat(repeatedCleanup.getBody().required("checkoutRemovedAt").isTextual()).isTrue();
    }

    @Test
    void retriesImmediateWorktreeCleanupWithoutWaitingForRuntimeRetention()
            throws Exception
    {
        JsonNode created = post("/agent/tasks", createTaskBody("codex"), null).getBody();
        TaskId taskId = TaskId.valueOf(created.required("id").asText());
        Path worktree = tasks.get(taskId).taskWorktree().orElseThrow().canonicalWorktree();
        completions.complete(new CompleteAgentTaskCommand(taskId, Optional.empty()));
        Path retained = worktree.resolve("inspect-me.txt");
        Files.writeString(retained, "retained\n");

        ResponseEntity<JsonNode> failed = post(
                "/agent/tasks/" + taskId + "/cleanup?workspaceId=" + workspaceId,
                ImmutableMap.of(),
                null);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tasks.get(taskId).state().name()).isEqualTo("COMPLETED");
        assertThat(tasks.get(taskId).worktreeRemovedAt()).isEmpty();

        Files.delete(retained);
        ResponseEntity<JsonNode> retried = post(
                "/agent/tasks/" + taskId + "/cleanup?workspaceId=" + workspaceId,
                ImmutableMap.of(),
                null);

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody().required("checkoutRemovedAt").isTextual()).isTrue();
        assertThat(worktree).doesNotExist();
    }

    @Test
    void createsRunsAndReplaysAClaudeTaskWithoutCrossLaneEvents()
            throws Exception
    {
        ResponseEntity<JsonNode> created = post(
                "/agent/tasks",
                createTaskBody("claude", "test-model", "Claude first"),
                null);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String claudeTaskId = created.getBody().required("id").asText();
        assertThat(created.getBody().required("laneId").asText()).isEqualTo("claude");
        assertThat(claudeDriver.openCount()).isEqualTo(1);
        assertThat(driver.openCount()).isZero();

        JsonNode claudeEvents = get("/agent/tasks/" + claudeTaskId + "/events?after=0&limit=20").getBody();
        assertThat(claudeEvents)
                .extracting(event -> event.required("type").asText())
                .containsExactly("user_message", "lifecycle", "lifecycle", "assistant_message", "lifecycle");
        assertThat(claudeEvents.get(0).required("payload").required("text").asText()).isEqualTo("Claude first");
        assertThat(claudeEvents.get(3).required("payload").required("text").asText()).isEqualTo("deterministic: Claude first");
        String turnId = claudeEvents.get(0).required("turnId").asText();
        assertThat(get("/agent/turns/" + turnId).getBody().required("state").asText()).isEqualTo("COMPLETED");

        long userSequence = claudeEvents.get(0).required("sequence").asLong();
        JsonNode replay = get("/agent/tasks/" + claudeTaskId + "/events?after=" + userSequence + "&limit=20").getBody();
        assertThat(replay).hasSize(4);
        assertThat(objectMapper.readTree(firstSseData(claudeTaskId, userSequence))).isEqualTo(replay.get(0));

        ResponseEntity<JsonNode> codexCreated = post(
                "/agent/tasks",
                createTaskBody("codex", "test-model", "Codex first"),
                null);
        assertThat(codexCreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(driver.openCount()).isEqualTo(1);
        assertThat(claudeDriver.openCount()).isEqualTo(1);
        assertThat(get("/agent/tasks/" + claudeTaskId + "/events?after=0&limit=20").getBody())
                .extracting(event -> event.required("payload").path("text").asText())
                .doesNotContain("Codex first", "deterministic: Codex first");
    }

    @Test
    void replaysCompositeTaskCreationAndRejectsAChangedIntent()
            throws Exception
    {
        Map<String, Object> request = createTaskBody("codex");

        ResponseEntity<JsonNode> first = post("/agent/tasks", request, null);
        ResponseEntity<JsonNode> replay = post("/agent/tasks", request, null);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().required("id").asText()).isEqualTo(request.get("taskId"));
        assertThat(replay.getBody().required("id").asText()).isEqualTo(first.getBody().required("id").asText());
        assertThat(get("/agent/tasks?workspaceId=" + workspaceId).getBody()).hasSize(1);
        assertThat(get("/agent/tasks/" + request.get("taskId") + "/events?after=0&limit=20").getBody())
                .filteredOn(event -> event.required("type").asText().equals("user_message"))
                .hasSize(1);

        Map<String, Object> changed = new HashMap<>(request);
        changed.put("message", "a different creation request");
        assertThat(post("/agent/tasks", changed, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        changed = new HashMap<>(request);
        changed.put("idempotencyKey", "a-different-first-turn-key");
        assertThat(post("/agent/tasks", changed, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void compositeCreationTrustsItsCreationProbeButLaterTurnsStillProbe()
            throws Exception
    {
        Map<String, Object> request = createTaskBody("codex");
        long probesBeforeCreation = driver.probeCount();
        driver.probeReadiness(
                AgentProviderStatus.Readiness.READY,
                AgentProviderStatus.Readiness.READY,
                AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);

        ResponseEntity<JsonNode> created = post("/agent/tasks", request, null);
        ResponseEntity<JsonNode> replay = post("/agent/tasks", request, null);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(driver.probeCount()).isEqualTo(probesBeforeCreation + 2);
        String taskId = created.getBody().required("id").asText();
        assertThat(get("/agent/tasks/" + taskId + "/events?after=0&limit=20").getBody())
                .filteredOn(event -> event.required("type").asText().equals("user_message"))
                .hasSize(1);

        ResponseEntity<JsonNode> laterTurn = post(
                "/agent/tasks/" + taskId + "/turns",
                ImmutableMap.of("message", "Later request", "expectedVersion", taskVersion(taskId)),
                "later-turn");

        assertThat(laterTurn.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(laterTurn.getBody().required("readiness").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(driver.probeCount()).isEqualTo(probesBeforeCreation + 3);
    }

    @Test
    void orphanCompositeReplayReprobesBeforeAddingATurnOrResources()
            throws Exception
    {
        Map<String, Object> request = createTaskBody("codex");
        long probesBeforeCreation = driver.probeCount();
        TaskId taskId = TaskId.valueOf((String) request.get("taskId"));
        String idempotencyKey = (String) request.get("idempotencyKey");
        AgentTask orphan = taskCreator.create(
                taskId,
                workspaceId,
                "Agent HTTP test",
                AgentLaneId.CODEX_CLI,
                new AgentTaskDefaults(Optional.of("test-model"), Optional.of("high"), AgentPermissionPolicy.AUTO),
                idempotencyKey);
        TaskResources existingResources = resources.require(taskId);
        long runtimeEntries = entryCount(existingResources.runtimeDirectory());
        long worktrees = entryCount(workspacePaths.worktrees(workspaceId));
        assertThat(driver.probeCount()).isEqualTo(probesBeforeCreation + 2);
        assertThat(turns.findTurnByIdempotencyKey(taskId, idempotencyKey)).isEmpty();
        driver.probeReadiness(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);

        ResponseEntity<JsonNode> failedReplay = post("/agent/tasks", request, null);

        assertThat(failedReplay.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(failedReplay.getBody().required("readiness").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(driver.probeCount()).isEqualTo(probesBeforeCreation + 3);
        assertThat(turns.findTurnByIdempotencyKey(taskId, idempotencyKey)).isEmpty();
        assertThat(resources.require(taskId)).isEqualTo(existingResources);
        assertThat(entryCount(existingResources.runtimeDirectory())).isEqualTo(runtimeEntries);
        assertThat(entryCount(workspacePaths.worktrees(workspaceId))).isEqualTo(worktrees);
        assertThat(tasks.get(taskId)).isEqualTo(orphan);
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
        return createTaskBody(laneId, "test-model", "Agent HTTP test");
    }

    private Map<String, Object> createTaskBody(String laneId, String model, String message)
    {
        return ImmutableMap.of(
                "taskId", UUID.randomUUID().toString(),
                "workspaceId", workspaceId,
                "laneId", laneId,
                "message", message,
                "idempotencyKey", "create-" + UUID.randomUUID(),
                "defaults", ImmutableMap.of(
                        "model", model,
                        "effort", "high",
                        "permissionPolicy", "AUTO"));
    }

    private JsonNode startTurn(String taskId, String message, String idempotencyKey)
            throws Exception
    {
        ResponseEntity<JsonNode> response = post(
                "/agent/tasks/" + taskId + "/turns",
                ImmutableMap.of("message", message, "expectedVersion", taskVersion(taskId)),
                idempotencyKey);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody();
    }

    private long taskVersion(String taskId)
    {
        return get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId)
                .getBody()
                .required("version")
                .asLong();
    }

    private String uploadImage(String taskId, String name)
            throws Exception
    {
        HttpHeaders upload = headers();
        upload.setContentType(MediaType.IMAGE_PNG);
        upload.set("X-Attachment-Name", name);
        return rest.exchange(
                url("/agent/tasks/" + taskId + "/attachments?workspaceId=" + workspaceId),
                HttpMethod.POST,
                new HttpEntity<>(png(3, 3), upload),
                JsonNode.class).getBody().required("id").asText();
    }

    private void deleteAttachment(String taskId, String attachmentId)
    {
        ResponseEntity<Void> deleted = rest.exchange(
                url("/agent/tasks/" + taskId + "/attachments/" + attachmentId + "?workspaceId=" + workspaceId),
                HttpMethod.DELETE,
                new HttpEntity<>(headers()),
                Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private void updateDefaults(String taskId, String model)
            throws Exception
    {
        HttpHeaders updateHeaders = headers();
        updateHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> updated = rest.exchange(
                url("/agent/tasks/" + taskId + "/defaults?workspaceId=" + workspaceId),
                HttpMethod.PUT,
                new HttpEntity<>(objectMapper.writeValueAsString(ImmutableMap.of(
                        "expectedVersion", taskVersion(taskId),
                        "defaults", ImmutableMap.of(
                                "model", model,
                                "effort", "high",
                                "permissionPolicy", "AUTO"))), updateHeaders),
                JsonNode.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void deleteQueued(String taskId, String queuedInputId)
    {
        ResponseEntity<JsonNode> deleted = rest.exchange(
                url("/agent/tasks/" + taskId + "/queue/" + queuedInputId + "?workspaceId=" + workspaceId),
                HttpMethod.DELETE,
                new HttpEntity<>(headers()),
                JsonNode.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
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

    @Test
    void queuesInputBehindARunningTurnAndStartsItAfterSuccess()
            throws Exception
    {
        JsonNode created = post("/agent/tasks", createTaskBody("codex"), null).getBody();
        String taskId = created.required("id").asText();
        String queuePath = "/agent/tasks/" + taskId + "/queue?workspaceId=" + workspaceId;
        assertThat(post(queuePath, ImmutableMap.of("message", "too early", "expectedVersion", created.required("version").asLong()), "queue-idle").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        driver.holdNextTurn();
        JsonNode running = startTurn(taskId, "first", "request-1");
        assertThat(running.required("state").asText()).isEqualTo("RUNNING");
        long runningVersion = get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId).getBody().required("version").asLong();

        ResponseEntity<JsonNode> queued = post(queuePath, ImmutableMap.of("message", "later", "expectedVersion", runningVersion), "queue-1");
        assertThat(queued.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(queued.getBody().required("state").asText()).isEqualTo("QUEUED");
        assertThat(queued.getBody().required("preview").asText()).isEqualTo("later");
        assertThat(queued.getBody().required("settings").required("model").asText()).isEqualTo("test-model");
        // Idempotent by key, and refused with the version this screen no longer holds.
        ResponseEntity<JsonNode> repeated = post(queuePath, ImmutableMap.of("message", "ignored", "expectedVersion", runningVersion), "queue-1");
        assertThat(repeated.getBody().required("id").asText()).isEqualTo(queued.getBody().required("id").asText());
        assertThat(post(queuePath, ImmutableMap.of("message", "stale", "expectedVersion", runningVersion), "queue-2").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(get(queuePath).getBody()).hasSize(1);
        assertThat(get("/agent/tasks/" + taskId + "/queue?workspaceId=another-workspace").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Completing must wait for the queue; the successful running turn starts its head once.
        assertThat(post("/agent/tasks/" + taskId + "/complete?workspaceId=" + workspaceId, ImmutableMap.of(), null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        driver.completeHeldTurn();
        awaitCondition(() -> get(queuePath).getBody().isEmpty());
        awaitCondition(() -> get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId).getBody().path("mutationClaim").isNull());
        JsonNode events = get("/agent/tasks/" + taskId + "/events?after=0&limit=20").getBody();
        assertThat(events).anySatisfy(event -> {
            assertThat(event.required("type").asText()).isEqualTo("user_message");
            assertThat(event.required("payload").required("text").asText()).isEqualTo("later");
            assertThat(event.required("payload").required("deliveryKind").asText()).isEqualTo("QUEUED");
        });
        assertThat(post("/agent/tasks/" + taskId + "/queue/resume?workspaceId=" + workspaceId, ImmutableMap.of(), null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        driver.holdNextTurn();
        startTurn(taskId, "hold removal", "request-2");
        long version = get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId).getBody().required("version").asLong();
        String removable = post(queuePath, ImmutableMap.of("message", "never mind", "expectedVersion", version), "queue-3").getBody().required("id").asText();
        ResponseEntity<JsonNode> removed = rest.exchange(
                url("/agent/tasks/" + taskId + "/queue/" + removable + "?workspaceId=" + workspaceId),
                HttpMethod.DELETE,
                new HttpEntity<>(headers()),
                JsonNode.class);
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(removed.getBody().required("state").asText()).isEqualTo("REMOVED");
        assertThat(get(queuePath).getBody()).isEmpty();
        driver.completeHeldTurn();
    }

    @Test
    void cannotRemoveAnotherTasksQueuedInput()
            throws Exception
    {
        String routeTaskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        String ownerTaskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        String ownerQueuePath = "/agent/tasks/" + ownerTaskId + "/queue?workspaceId=" + workspaceId;
        driver.holdNextTurn();
        startTurn(ownerTaskId, "hold", "owner-turn");
        long version = get("/agent/tasks/" + ownerTaskId + "?workspaceId=" + workspaceId).getBody().required("version").asLong();
        String queuedId = post(ownerQueuePath, ImmutableMap.of("message", "owned", "expectedVersion", version), "owner-queue")
                .getBody().required("id").asText();

        ResponseEntity<JsonNode> foreignDelete = rest.exchange(
                url("/agent/tasks/" + routeTaskId + "/queue/" + queuedId + "?workspaceId=" + workspaceId),
                HttpMethod.DELETE,
                new HttpEntity<>(headers()),
                JsonNode.class);

        assertThat(foreignDelete.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(get(ownerQueuePath).getBody()).singleElement().satisfies(input ->
                assertThat(input.required("id").asText()).isEqualTo(queuedId));
        rest.exchange(
                url("/agent/tasks/" + ownerTaskId + "/queue/" + queuedId + "?workspaceId=" + workspaceId),
                HttpMethod.DELETE,
                new HttpEntity<>(headers()),
                JsonNode.class);
        driver.completeHeldTurn();
    }

    @Test
    void refusesToSteerATurnThatIsNotRunning()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        JsonNode finished = startTurn(taskId, "first", "request-1");

        ResponseEntity<JsonNode> steer = post("/agent/turns/" + finished.required("id").asText() + "/steer", ImmutableMap.of("message", "late"), "steer-1");

        assertThat(steer.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(get("/agent/tasks/" + taskId + "/events?after=0&limit=20").getBody())
                .noneSatisfy(event -> assertThat(event.required("payload").path("deliveryKind").asText()).isEqualTo("STEER"));
    }

    @Test
    void validatesSteerImagesAgainstTheRunningTurnsModelSnapshot()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex", "text-only-model", "Text only"), null)
                .getBody().required("id").asText();
        HttpHeaders upload = headers();
        upload.setContentType(MediaType.IMAGE_PNG);
        upload.set("X-Attachment-Name", "shot.png");
        String attachmentId = rest.exchange(
                url("/agent/tasks/" + taskId + "/attachments?workspaceId=" + workspaceId),
                HttpMethod.POST,
                new HttpEntity<>(png(3, 3), upload),
                JsonNode.class).getBody().required("id").asText();
        driver.holdNextTurn();
        JsonNode running = startTurn(taskId, "keep running", "running-text-turn");
        long version = get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId).getBody().required("version").asLong();
        HttpHeaders updateHeaders = headers();
        updateHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> updated = rest.exchange(
                url("/agent/tasks/" + taskId + "/defaults?workspaceId=" + workspaceId),
                HttpMethod.PUT,
                new HttpEntity<>(objectMapper.writeValueAsString(ImmutableMap.of(
                        "expectedVersion", version,
                        "defaults", ImmutableMap.of("model", "test-model", "effort", "high", "permissionPolicy", "AUTO"))), updateHeaders),
                JsonNode.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> refused = post(
                "/agent/turns/" + running.required("id").asText() + "/steer",
                ImmutableMap.of("message", "look", "attachments", ImmutableList.of(attachmentId)),
                "steer-image");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().path("message").asText()).contains("text-only-model").contains("does not accept images");
        driver.completeHeldTurn();
    }

    @Test
    void refusesImagesWhenTheLaneDoesNotAdvertiseTheirMediaType()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        String attachmentId = uploadImage(taskId, "unsupported.png");
        driver.disableImageAttachments();

        ResponseEntity<JsonNode> refused = post(
                "/agent/tasks/" + taskId + "/turns",
                ImmutableMap.of(
                        "message", "look",
                        "attachments", ImmutableList.of(attachmentId),
                        "expectedVersion", taskVersion(taskId)),
                "unsupported-provider-image");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().path("message").asText())
                .contains("Provider codex").contains("image/png");
    }

    @Test
    void replaysAnImageTurnBeforeReadingChangedDefaultsOrADeletedAttachment()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        String attachmentId = uploadImage(taskId, "start.png");
        Map<String, Object> body = ImmutableMap.of(
                "message", "look",
                "attachments", ImmutableList.of(attachmentId),
                "expectedVersion", taskVersion(taskId));

        ResponseEntity<JsonNode> first = post("/agent/tasks/" + taskId + "/turns", body, "image-turn-replay");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        deleteAttachment(taskId, attachmentId);
        updateDefaults(taskId, "text-only-model");

        ResponseEntity<JsonNode> replay = post("/agent/tasks/" + taskId + "/turns", body, "image-turn-replay");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replay.getBody().required("id").asText()).isEqualTo(first.getBody().required("id").asText());
    }

    @Test
    void replaysSteerAndQueueBeforeReadingChangedDefaultsOrADeletedAttachment()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        String attachmentId = uploadImage(taskId, "running.png");
        driver.holdNextTurn();
        JsonNode running = startTurn(taskId, "hold", "held-for-replay");
        Map<String, Object> steerBody = ImmutableMap.of(
                "message", "steer image",
                "attachments", ImmutableList.of(attachmentId));
        Map<String, Object> queueBody = ImmutableMap.of(
                "message", "queue image",
                "attachments", ImmutableList.of(attachmentId),
                "expectedVersion", taskVersion(taskId));

        ResponseEntity<JsonNode> firstSteer = post(
                "/agent/turns/" + running.required("id").asText() + "/steer",
                steerBody,
                "steer-image-replay");
        ResponseEntity<JsonNode> firstQueue = post(
                "/agent/tasks/" + taskId + "/queue?workspaceId=" + workspaceId,
                queueBody,
                "queue-image-replay");
        assertThat(firstSteer.getBody().required("state").asText()).isEqualTo("DELIVERED");
        assertThat(firstQueue.getBody().required("state").asText()).isEqualTo("QUEUED");
        deleteAttachment(taskId, attachmentId);
        updateDefaults(taskId, "text-only-model");

        ResponseEntity<JsonNode> replaySteer = post(
                "/agent/turns/" + running.required("id").asText() + "/steer",
                steerBody,
                "steer-image-replay");
        ResponseEntity<JsonNode> replayQueue = post(
                "/agent/tasks/" + taskId + "/queue?workspaceId=" + workspaceId,
                queueBody,
                "queue-image-replay");
        assertThat(replaySteer.getBody().required("id").asText())
                .isEqualTo(firstSteer.getBody().required("id").asText());
        assertThat(replayQueue.getBody().required("id").asText())
                .isEqualTo(firstQueue.getBody().required("id").asText());

        deleteQueued(taskId, replayQueue.getBody().required("id").asText());
        driver.completeHeldTurn();
    }

    @Test
    void storesATaskOwnedImageAndSendsItWithATurn()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();

        HttpHeaders upload = headers();
        upload.setContentType(MediaType.IMAGE_PNG);
        upload.set("X-Attachment-Name", "failing-test.png");
        ResponseEntity<JsonNode> stored = rest.exchange(
                url("/agent/tasks/" + taskId + "/attachments?workspaceId=" + workspaceId),
                HttpMethod.POST,
                new HttpEntity<>(png(9, 4), upload),
                JsonNode.class);
        assertThat(stored.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String attachmentId = stored.getBody().required("id").asText();
        assertThat(stored.getBody().required("name").asText()).isEqualTo("failing-test.png");
        assertThat(stored.getBody().required("width").asInt()).isEqualTo(9);
        assertThat(stored.getBody().required("height").asInt()).isEqualTo(4);

        ResponseEntity<byte[]> bytes = rest.exchange(
                url("/agent/tasks/" + taskId + "/attachments/" + attachmentId + "?workspaceId=" + workspaceId),
                HttpMethod.GET,
                new HttpEntity<>(headers()),
                byte[].class);
        assertThat(bytes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bytes.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(bytes.getBody()).isEqualTo(png(9, 4));

        // The turn's durable user message names the attachment by the reference the upload returned.
        JsonNode turn = post(
                "/agent/tasks/" + taskId + "/turns",
                ImmutableMap.of(
                        "message", "What is in this screenshot?",
                        "attachments", ImmutableList.of(attachmentId),
                        "expectedVersion", taskVersion(taskId)),
                "attach-1").getBody();
        assertThat(turn.required("settings").required("attachments").get(0).required("id").asText()).isEqualTo(attachmentId);
        JsonNode events = get("/agent/tasks/" + taskId + "/events?after=0&limit=10").getBody();
        assertThat(events)
                .filteredOn(event -> event.path("payload").path("attachments").size() == 1)
                .singleElement()
                .satisfies(userMessage -> {
                    assertThat(userMessage.required("type").asText()).isEqualTo("user_message");
                    assertThat(userMessage.required("payload").required("attachments").get(0).required("id").asText())
                            .isEqualTo(attachmentId);
                });

        // A reference nobody issued, and a task that does not own the image, get nothing.
        assertThat(rest.exchange(
                url("/agent/tasks/" + taskId + "/attachments/not-an-attachment?workspaceId=" + workspaceId),
                HttpMethod.GET,
                new HttpEntity<>(headers()),
                byte[].class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String otherTask = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        assertThat(rest.exchange(
                url("/agent/tasks/" + otherTask + "/attachments/" + attachmentId + "?workspaceId=" + workspaceId),
                HttpMethod.GET,
                new HttpEntity<>(headers()),
                byte[].class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void refusesAnImageForAModelThatDoesNotAcceptOne()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex", "text-only-model", "Text only"), null)
                .getBody().required("id").asText();
        HttpHeaders upload = headers();
        upload.setContentType(MediaType.IMAGE_PNG);
        upload.set("X-Attachment-Name", "shot.png");
        String attachmentId = rest.exchange(
                url("/agent/tasks/" + taskId + "/attachments?workspaceId=" + workspaceId),
                HttpMethod.POST,
                new HttpEntity<>(png(3, 3), upload),
                JsonNode.class).getBody().required("id").asText();

        ResponseEntity<JsonNode> refused = post(
                "/agent/tasks/" + taskId + "/turns",
                ImmutableMap.of(
                        "message", "look",
                        "attachments", ImmutableList.of(attachmentId),
                        "expectedVersion", taskVersion(taskId)),
                "attach-2");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().path("message").asText()).contains("does not accept images");

        // Bytes that are not the claimed image never become an attachment.
        upload.setContentType(MediaType.IMAGE_JPEG);
        assertThat(rest.exchange(
                url("/agent/tasks/" + taskId + "/attachments?workspaceId=" + workspaceId),
                HttpMethod.POST,
                new HttpEntity<>(png(3, 3), upload),
                JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static byte[] png(int width, int height)
            throws Exception
    {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    @Test
    void commitsTheStreamBeforeTheTaskHasAnyEvent()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url("/agent/tasks/" + taskId + "/stream?after=0")))
                .header(AgentController.SESSION_HEADER, SESSION_TOKEN)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<Stream<String>> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofLines());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        try (Stream<String> lines = response.body()) {
            assertThat(lines.findFirst()).contains(":connected");
        }
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

    private static void awaitCondition(BooleanSupplier condition)
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            checkState(System.nanoTime() < deadline, "condition was not met before the deadline");
            try {
                Thread.sleep(5);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting", e);
            }
        }
    }

    private static long entryCount(Path directory)
            throws IOException
    {
        try (Stream<Path> entries = Files.walk(directory)) {
            return entries.count();
        }
    }

    private String url(String path)
    {
        return "http://127.0.0.1:" + port + path;
    }

    private static String run(String... command)
            throws IOException, InterruptedException
    {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        checkState(exitCode == 0, "Command failed: " + output);
        return output.strip();
    }
}
