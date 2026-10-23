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

import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.UnsequencedAgentEvent;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.app.workspace.WorkspaceCodeSyncResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAgentTurnHttpApi
        extends AbstractTestAgentHttpApi
{
    @Test
    void mapsBoundedHistoryPagesAndOpaqueCursorsInBothDirections()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        awaitCondition(() -> get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId)
                .getBody().required("titleSource").asText().equals("AGENT"));
        long latestSequence = 0;
        for (int index = 0; index < 2001; index++) {
            latestSequence = events.append(new UnsequencedAgentEvent(
                    EventId.random(), TaskId.valueOf(taskId), Optional.empty(), AgentEventSource.root(),
                    new AgentEventPayload.AssistantMessage(MessageKind.ANSWER, MessagePhase.FINAL, "history " + index))).sequence();
        }
        String historyPath = "/agent/tasks/" + taskId + "/history";

        ResponseEntity<JsonNode> response = get(historyPath + "?limit=1000000");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode latest = response.getBody();
        assertThat(latest.required("events")).hasSize(1000);
        assertThat(latest.required("latestSequence").asLong()).isEqualTo(latestSequence);
        assertThat(latest.required("beforeCursor").isTextual()).isTrue();
        assertThat(latest.required("afterCursor").isNull()).isTrue();
        assertThat(latest.required("events").get(0).required("sequence").asLong()).isEqualTo(latestSequence - 999);
        assertThat(latest.required("events").get(999).required("sequence").asLong()).isEqualTo(latestSequence);
        assertThat(latest.required("events").get(999).required("type").asText()).isEqualTo("assistant_message");
        assertThat(latest.required("events").get(999).required("payload").required("text").asText()).isEqualTo("history 2000");

        JsonNode older = get(historyPath + "?before=" + latest.required("beforeCursor").asText()).getBody();
        assertThat(older.required("events")).hasSize(1000);
        assertThat(older.required("events").get(999).required("sequence").asLong()).isEqualTo(latestSequence - 1000);
        assertThat(older.required("latestSequence").asLong()).isEqualTo(latestSequence);
        assertThat(older.required("afterCursor").isTextual()).isTrue();
        JsonNode forward = get(historyPath + "?after=" + older.required("afterCursor").asText()).getBody();
        assertThat(forward).isEqualTo(latest);

        JsonNode oldest = get(historyPath + "?before=" + older.required("beforeCursor").asText()).getBody();
        assertThat(oldest.required("beforeCursor").isNull()).isTrue();
        assertThat(oldest.required("events").get(0).required("sequence").asLong()).isEqualTo(1);
        assertThat(get("/agent/tasks/" + taskId + "/events?after=0&limit=2").getBody()).hasSize(2);
        assertThat(get("/agent/tasks/" + taskId + "/events?after=0&limit=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsInvalidHistoryCursorsAtTheHttpBoundary()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        String historyPath = "/agent/tasks/" + taskId + "/history";

        for (String query : ImmutableList.of("before=-1", "after=-1", "before=0&after=0", "before=not-a-cursor", "after=1", "after=" + Long.MAX_VALUE)) {
            assertThat(get(historyPath + "?" + query).getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(get("/agent/tasks/" + TaskId.random() + "/history").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void exposesDurablePendingApprovalsOnlyUnderTheirOwningTaskAndRemovesResolvedRequests()
            throws Exception
    {
        String ownerTaskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        String otherTaskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        driver.holdNextTurn();
        String turnId = startTurn(ownerTaskId, "wait for approval", "approval-turn").required("id").asText();
        Instant requestedAt = Instant.now();
        ApprovalRequest request = new ApprovalRequest(
                ApprovalId.random(), TaskId.valueOf(ownerTaskId), TurnId.valueOf(turnId),
                new ProviderApprovalRequest(
                        ProviderApprovalRequest.Kind.COMMAND,
                        "Run command",
                        "git status",
                        ImmutableList.of("command:git status"),
                        ImmutableList.of(ProviderApprovalRequest.Decision.DENY),
                        true,
                        new ProviderAgentEventSource(Optional.of("child-thread"), Optional.of("root-thread"), Optional.of("root/child"),
                                Optional.of("provider-turn"), Optional.of("provider-item")),
                        requestedAt),
                requestedAt);
        try {
            approvals.request(request, Duration.ofMinutes(1));
            String path = "/agent/tasks/" + ownerTaskId + "/pending-approvals?turnId=" + turnId;

            JsonNode pending = get(path).getBody();

            assertThat(pending).hasSize(1);
            JsonNode approval = pending.get(0);
            assertThat(approval.required("approvalId").asText()).isEqualTo(request.id().toString());
            assertThat(approval.required("taskId").asText()).isEqualTo(ownerTaskId);
            assertThat(approval.required("turnId").asText()).isEqualTo(turnId);
            assertThat(approval.required("source").required("providerThreadId").asText()).isEqualTo("child-thread");
            assertThat(approval.required("title").asText()).isEqualTo("Run command");
            assertThat(approval.required("approvalKind").asText()).isEqualTo("COMMAND");
            assertThat(approval.required("sandboxed").asBoolean()).isTrue();
            assertThat(approval.required("availableDecisions").get(0).asText()).isEqualTo("DENY");
            assertThat(approval.required("actionable").asBoolean()).isTrue();
            assertThat(get("/agent/tasks/" + otherTaskId + "/pending-approvals?turnId=" + turnId).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            // Task lists and Needs attention read the same approval, the running turn and the newest message in one call.
            JsonNode activity = get("/agent/tasks/activity?workspaceId=" + workspaceId).getBody();
            JsonNode owner = activity.required(ownerTaskId);
            assertThat(owner.required("pendingApproval").required("title").asText()).isEqualTo("Run command");
            assertThat(owner.required("pendingApproval").required("approvalKind").asText()).isEqualTo("COMMAND");
            assertThat(owner.required("latestTurn").required("id").asText()).isEqualTo(turnId);
            assertThat(owner.required("latestTurn").required("state").asText()).isEqualTo("RUNNING");
            // The deterministic driver answers before it holds the turn, so the newest root message is that answer.
            assertThat(owner.required("lastMessage").required("author").asText()).isEqualTo("AGENT");
            assertThat(owner.required("lastMessage").required("text").asText()).startsWith("deterministic: ");
            assertThat(owner.required("lastMessage").required("turnId").asText()).isEqualTo(turnId);
            assertThat(activity.required(otherTaskId).required("pendingApproval").isNull()).isTrue();

            assertThat(get("/agent/tasks/running").getBody()).anySatisfy(running -> {
                assertThat(running.required("id").asText()).isEqualTo(ownerTaskId);
                assertThat(running.required("workspaceId").asText()).isEqualTo(workspaceId);
                assertThat(running.required("kind").asText()).isEqualTo("TASK");
                assertThat(running.required("status").asText()).isEqualTo("WAITING");
                assertThat(running.required("expiresAt").isNull()).isTrue();
            });

            assertThat(post("/agent/approvals/" + request.id(), ImmutableMap.of("decision", "DENY"), null).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get(path).getBody()).isEmpty();
            assertThat(get("/agent/tasks/activity?workspaceId=" + workspaceId).getBody().required(ownerTaskId).required("pendingApproval").isNull()).isTrue();
        }
        finally {
            approvals.denyPending(TurnId.valueOf(turnId));
            driver.completeHeldTurn();
        }
    }

    @Test
    void anUnreportedTurnThatChangedNothingSettlesWithoutStoppingAutomation()
            throws Exception
    {
        driver.holdNextTurn();
        Map<String, Object> request = new HashMap<>(createTaskBody("codex"));
        request.put("developmentAutomation", ImmutableMap.of("level", "CI_FIX"));
        JsonNode created = post("/agent/tasks", request, null).getBody();
        String taskId = created.required("id").asText();
        String taskPath = "/agent/tasks/" + taskId + "?workspaceId=" + workspaceId;
        String eventsPath = "/agent/tasks/" + taskId + "/events?after=0&limit=100";
        TaskWorktree worktree = tasks.get(TaskId.valueOf(taskId)).taskWorktree().orElseThrow();
        String unchangedHead = run("git", "-C", worktree.canonicalWorktree().toString(), "rev-parse", "HEAD");
        driver.completeHeldTurn();

        // The turn answered without reporting and left the checkout alone, so automation holds nothing
        // back and has nothing to announce; only an unreported turn that changed code is an error.
        awaitContinuationSettled(taskId);
        JsonNode settled = get(taskPath).getBody();
        assertThat(settled.required("state").asText()).isEqualTo("OPEN");
        assertThat(settled.required("developmentContinuationUnfinished").asBoolean()).isFalse();
        assertThat(settled.path("mutationClaim").isNull()).isTrue();
        assertThat(settled.path("terminalReason").isNull()).isTrue();
        assertThat(get(eventsPath).getBody().findValuesAsText("message"))
                .noneMatch(message -> message.startsWith("Automation stopped."));
        assertThat(tasks.get(TaskId.valueOf(taskId)).taskWorktree()).contains(worktree);
        assertThat(worktree.canonicalWorktree()).isDirectory();

        driver.reportNextTurn(new DevelopmentResultReport(true, Optional.empty()));
        ResponseEntity<JsonNode> followup = post("/agent/tasks/" + taskId + "/turns", ImmutableMap.of(
                "message", "Continue this task",
                "expectedVersion", taskVersion(taskId),
                "developmentAutomation", request.get("developmentAutomation")), "after-missing-readiness");
        assertThat(followup.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        awaitCondition(() -> {
            JsonNode current = get(taskPath).getBody();
            return current.path("mutationClaim").isNull() && !current.required("developmentContinuationUnfinished").asBoolean();
        });
        assertThat(get(taskPath).getBody().required("state").asText()).isEqualTo("OPEN");
        assertThat(tasks.get(TaskId.valueOf(taskId)).taskWorktree()).contains(worktree);
        assertThat(run("git", "-C", worktree.canonicalWorktree().toString(), "rev-parse", "HEAD")).isEqualTo(unchangedHead);
    }

    @Test
    void enabledQuestionKeepsTaskOpenAndPausesPreviouslyQueuedWork()
            throws Exception
    {
        driver.reportNextTurn(new DevelopmentResultReport(false, Optional.of("Which implementation?")));
        driver.holdNextTurn();
        Map<String, Object> request = new HashMap<>(createTaskBody("codex"));
        request.put("defaults", ImmutableMap.of("model", "test-model", "effort", "high", "permissionPolicy", "AUTO",
                "developmentAutomation", ImmutableMap.of("level", "CI_FIX")));
        JsonNode created = post("/agent/tasks", request, null).getBody();
        String taskId = created.required("id").asText();
        String taskPath = "/agent/tasks/" + taskId + "?workspaceId=" + workspaceId;
        String queuePath = "/agent/tasks/" + taskId + "/queue?workspaceId=" + workspaceId;
        assertThat(post(queuePath, ImmutableMap.of("message", "Previously queued work", "expectedVersion", taskVersion(taskId)), "queued-before-question")
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        driver.completeHeldTurn();
        awaitCondition(() -> get(queuePath).getBody().get(0).path("state").asText().equals("PAUSED"));
        awaitContinuationSettled(taskId);
        JsonNode waiting = get(taskPath).getBody();
        assertThat(waiting.required("state").asText()).isEqualTo("OPEN");
        assertThat(waiting.required("developmentContinuationUnfinished").asBoolean()).isFalse();
        assertThat(waiting.path("mutationClaim").isNull()).isTrue();
        assertThat(post("/agent/tasks/" + taskId + "/queue/resume?workspaceId=" + workspaceId, ImmutableMap.of(), null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        JsonNode history = get("/agent/tasks/" + taskId + "/events?after=0&limit=20").getBody();
        assertThat(history).anySatisfy(event -> {
            assertThat(event.path("type").asText()).isEqualTo("development_result");
            assertThat(event.path("payload").path("message").asText()).isEqualTo("Which implementation?");
        });

        Path worktree = tasks.get(TaskId.valueOf(taskId)).worktree();
        String unchangedHead = run("git", "-C", worktree.toString(), "rev-parse", "HEAD");
        driver.reportNextTurn(new DevelopmentResultReport(true, Optional.empty()));
        startTurn(taskId, "Use the first implementation", "answer-question");
        awaitCondition(() -> {
            JsonNode current = get(taskPath).getBody();
            return current.path("mutationClaim").isNull() && !current.required("developmentContinuationUnfinished").asBoolean();
        });
        assertThat(run("git", "-C", worktree.toString(), "rev-parse", "HEAD")).isEqualTo(unchangedHead);
        assertThat(get(taskPath).getBody().required("state").asText()).isEqualTo("OPEN");
        assertThat(get(queuePath).getBody()).singleElement().satisfies(input -> assertThat(input.path("state").asText()).isEqualTo("PAUSED"));

        driver.reportNextTurn(new DevelopmentResultReport(true, Optional.empty()));
        assertThat(post("/agent/tasks/" + taskId + "/queue/resume?workspaceId=" + workspaceId, ImmutableMap.of(), null).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        awaitCondition(() -> {
            JsonNode current = get(taskPath).getBody();
            return get(queuePath).getBody().isEmpty() && current.path("mutationClaim").isNull()
                    && !current.required("developmentContinuationUnfinished").asBoolean();
        });
        assertThat(post("/agent/tasks/" + taskId + "/queue/resume?workspaceId=" + workspaceId, ImmutableMap.of(), null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(get("/agent/tasks/" + taskId + "/events?after=0&limit=100").getBody())
                .filteredOn(event -> event.path("type").asText().equals("user_message")
                        && event.path("payload").path("text").asText().equals("Previously queued work"))
                .hasSize(1);
    }

    @Test
    void createsTaskRunsIdempotentTurnsAndReplaysSse()
            throws Exception
    {
        Map<String, Object> createRequest = new HashMap<>(createTaskBody("codex"));
        createRequest.put("message", "first");
        createRequest.put("idempotencyKey", "request-1");
        driver.reportNextTurn(new DevelopmentResultReport(true, Optional.empty()));
        ResponseEntity<JsonNode> createResponse = post("/agent/tasks", createRequest, null);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode createdTask = createResponse.getBody();
        assertThat(createdTask).isNotNull();
        String taskId = createdTask.get("id").asText();
        assertThat(createdTask.get("workspaceId").asText()).isEqualTo(workspaceId);
        assertThat(createdTask.required("laneId").asText()).isEqualTo("codex");
        assertThat(createdTask.required("defaults").required("model").asText()).isEqualTo("test-model");
        assertThat(createdTask.required("generatedBranch").asText())
                .isEqualTo(tasks.get(TaskId.valueOf(taskId)).taskWorktree().orElseThrow().generatedBranch());
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
        assertThat(firstTerminal.required("settings").required("model").asText()).isEqualTo("test-model");
        awaitCondition(() -> get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId)
                .getBody().required("titleSource").asText().equals("AGENT"));

        driver.reportNextTurn(new DevelopmentResultReport(true, Optional.empty()));
        JsonNode second = startTurn(taskId, "second", "request-2");
        JsonNode secondTerminal = get("/agent/turns/" + second.get("id").asText()).getBody();
        assertThat(secondTerminal.get("state").asText()).isEqualTo("COMPLETED");

        JsonNode recordedEvents = recordedEvents(taskId, 14);
        assertThat(recordedEvents).hasSize(14);
        assertThat(recordedEvents).filteredOn(event -> event.at("/payload/phase").asText().equals("TITLE_UPDATED")).hasSize(1);
        List<JsonNode> events = StreamSupport.stream(recordedEvents.spliterator(), false)
                .filter(event -> !event.at("/payload/phase").asText().equals("TITLE_UPDATED")).toList();
        assertThat(events).hasSize(13);
        assertThat(events)
                .extracting(event -> event.get("type").asText())
                .containsExactly(
                        "user_message",
                        "lifecycle",
                        "lifecycle",
                        "assistant_message",
                        "development_result",
                        "lifecycle",
                        "lifecycle",
                        "user_message",
                        "lifecycle",
                        "assistant_message",
                        "development_result",
                        "lifecycle",
                        "lifecycle");
        assertThat(events.get(0).get("payload").get("text").asText()).isEqualTo("first");
        assertThat(events.get(1).get("payload").get("phase").asText()).isEqualTo("SESSION_OPENED");
        assertThat(events.get(2).get("payload").get("phase").asText()).isEqualTo("TURN_STARTED");
        assertThat(events.get(3).get("payload").get("text").asText()).isEqualTo("deterministic: first");
        assertThat(events.get(4).get("payload").get("readyForDelivery").asBoolean()).isTrue();
        assertThat(events.get(5).get("payload").get("phase").asText()).isEqualTo("TURN_COMPLETED");
        assertThat(events.get(6).get("payload").get("phase").asText()).isEqualTo("DEVELOPMENT_AUTOMATION_UPDATED");
        assertThat(events.get(7).get("payload").get("text").asText()).isEqualTo("second");
        assertThat(events.get(8).get("payload").get("phase").asText()).isEqualTo("TURN_STARTED");
        assertThat(events.get(9).get("payload").get("text").asText()).isEqualTo("deterministic: second");
        assertThat(events.get(10).get("payload").get("readyForDelivery").asBoolean()).isTrue();
        assertThat(events.get(11).get("payload").get("phase").asText()).isEqualTo("TURN_COMPLETED");
        assertThat(events.get(12).get("payload").get("phase").asText()).isEqualTo("DEVELOPMENT_AUTOMATION_UPDATED");
        long firstSequence = events.get(7).get("sequence").asLong() - 1;
        assertThat(events.get(0).get("schemaVersion").asInt()).isEqualTo(2);
        assertThat(events.get(0).get("eventId").asText()).isNotBlank();
        assertThat(events.get(0).get("source").isObject()).isTrue();
        assertThat(events.get(0).get("source").get("logicalAgentPath").isNull()).isTrue();

        JsonNode afterFirst = get("/agent/tasks/" + taskId + "/events?after=" + firstSequence + "&limit=10")
                .getBody();
        assertThat(afterFirst).isNotNull();
        assertThat(afterFirst).hasSize(6);
        assertThat(afterFirst.get(0).get("type").asText()).isEqualTo("user_message");
        assertThat(afterFirst.get(0).get("payload").get("text").asText()).isEqualTo("second");
        assertThat(afterFirst.get(1).get("type").asText()).isEqualTo("lifecycle");
        assertThat(afterFirst.get(1).get("payload").get("phase").asText()).isEqualTo("TURN_STARTED");
        assertThat(afterFirst.get(2).get("type").asText()).isEqualTo("assistant_message");
        assertThat(afterFirst.get(2).get("payload").get("text").asText()).isEqualTo("deterministic: second");
        assertThat(afterFirst.get(3).get("type").asText()).isEqualTo("development_result");
        assertThat(afterFirst.get(3).get("payload").get("readyForDelivery").asBoolean()).isTrue();
        assertThat(afterFirst.get(4).get("type").asText()).isEqualTo("lifecycle");
        assertThat(afterFirst.get(4).get("payload").get("phase").asText()).isEqualTo("TURN_COMPLETED");
        assertThat(afterFirst.get(5).get("payload").get("phase").asText()).isEqualTo("DEVELOPMENT_AUTOMATION_UPDATED");
        JsonNode sseEvent = objectMapper.readTree(firstSseData(taskId, firstSequence));
        assertThat(sseEvent).isEqualTo(afterFirst.get(0));

        Map<String, String> completionRequest = ImmutableMap.of("notes", "ready for review");
        ResponseEntity<JsonNode> completion = post("/agent/tasks/" + taskId + "/complete?workspaceId=" + workspaceId, completionRequest, null);
        assertThat(completion.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completion.getBody().get("summary").get("finalResponse").asText())
                .isEqualTo("deterministic: second");
        assertThat(completion.getBody().get("summary").get("notes").asText())
                .isEqualTo("ready for review");
        assertThat(completion.getBody().required("summary").required("resultBranch")).isEqualTo(createdTask.required("generatedBranch"));
        assertThat(completion.getBody().required("summary").required("resultHead").asText()).isEqualTo(run("git", "-C", repository.toString(), "rev-parse", "HEAD"));
        JsonNode finalTask = get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId).getBody();
        assertThat(finalTask.get("state").asText()).isEqualTo("COMPLETED");
        assertThat(finalTask.required("summary")).isEqualTo(completion.getBody().required("summary"));

        ResponseEntity<JsonNode> repeatedCleanup = post("/agent/tasks/" + taskId + "/cleanup?workspaceId=" + workspaceId, ImmutableMap.of(), null);
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

        ResponseEntity<JsonNode> failed = post("/agent/tasks/" + taskId + "/cleanup?workspaceId=" + workspaceId, ImmutableMap.of(), null);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tasks.get(taskId).state().name()).isEqualTo("COMPLETED");
        assertThat(tasks.get(taskId).worktreeRemovedAt()).isEmpty();

        Files.delete(retained);
        ResponseEntity<JsonNode> retried = post("/agent/tasks/" + taskId + "/cleanup?workspaceId=" + workspaceId, ImmutableMap.of(), null);

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody().required("checkoutRemovedAt").isTextual()).isTrue();
        assertThat(worktree).doesNotExist();
    }

    @Test
    void createsRunsAndReplaysAClaudeTaskWithoutCrossLaneEvents()
            throws Exception
    {
        ResponseEntity<JsonNode> created = post("/agent/tasks", createTaskBody("claude", "test-model", "Claude first"), null);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String claudeTaskId = created.getBody().required("id").asText();
        assertThat(created.getBody().required("laneId").asText()).isEqualTo("claude");
        awaitCondition(() -> get("/agent/tasks/" + claudeTaskId + "?workspaceId=" + workspaceId)
                .getBody().required("titleSource").asText().equals("AGENT"));
        assertThat(claudeDriver.openCount()).isEqualTo(2);
        assertThat(driver.openCount()).isZero();

        JsonNode recordedClaudeEvents = recordedEvents(claudeTaskId, 7);
        assertThat(recordedClaudeEvents).filteredOn(event -> event.at("/payload/phase").asText().equals("TITLE_UPDATED")).hasSize(1);
        List<JsonNode> claudeEvents = StreamSupport.stream(recordedClaudeEvents.spliterator(), false)
                .filter(event -> !event.at("/payload/phase").asText().equals("TITLE_UPDATED")).toList();
        assertThat(claudeEvents)
                .extracting(event -> event.required("type").asText())
                .containsExactly("user_message", "lifecycle", "lifecycle", "assistant_message", "lifecycle", "lifecycle");
        assertThat(claudeEvents.get(0).required("payload").required("text").asText()).isEqualTo("Claude first");
        assertThat(claudeEvents.get(3).required("payload").required("text").asText()).isEqualTo("deterministic: Claude first");
        String turnId = claudeEvents.get(0).required("turnId").asText();
        assertThat(get("/agent/turns/" + turnId).getBody().required("state").asText()).isEqualTo("COMPLETED");

        long userSequence = claudeEvents.get(0).required("sequence").asLong();
        JsonNode replay = get("/agent/tasks/" + claudeTaskId + "/events?after=" + userSequence + "&limit=20").getBody();
        assertThat(replay).hasSize(6);
        assertThat(objectMapper.readTree(firstSseData(claudeTaskId, userSequence))).isEqualTo(replay.get(0));

        ResponseEntity<JsonNode> codexCreated = post("/agent/tasks", createTaskBody("codex", "test-model", "Codex first"), null);
        assertThat(codexCreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String codexTaskId = codexCreated.getBody().required("id").asText();
        awaitCondition(() -> get("/agent/tasks/" + codexTaskId + "?workspaceId=" + workspaceId)
                .getBody().required("titleSource").asText().equals("AGENT"));
        assertThat(driver.openCount()).isEqualTo(2);
        assertThat(claudeDriver.openCount()).isEqualTo(2);
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
        verify(provisioning).synchronizeCode(workspaceId);
        when(provisioning.synchronizeCode(workspaceId)).thenReturn(new WorkspaceCodeSyncResult(workspaceId, false, Optional.empty(), ImmutableSet.of()));
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
        verify(provisioning).synchronizeCode(workspaceId);
    }

    @Test
    void compositeCreationTrustsItsCreationProbeButLaterTurnsStillProbe()
            throws Exception
    {
        Map<String, Object> request = createTaskBody("codex");
        long probesBeforeCreation = driver.probeCount();
        driver.probeReadiness(AgentProviderStatus.Readiness.READY, AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);

        ResponseEntity<JsonNode> created = post("/agent/tasks", request, null);
        ResponseEntity<JsonNode> replay = post("/agent/tasks", request, null);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(driver.probeCount()).isEqualTo(probesBeforeCreation + 1);
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
        assertThat(driver.probeCount()).isEqualTo(probesBeforeCreation + 2);
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
                new AgentTaskDefaults(Optional.of("test-model"), Optional.of("high"), AgentPermissionPolicy.AUTO)
                        .withDevelopmentAutomation(DevelopmentAutomation.LOCAL_ONLY.forDevelopment()),
                idempotencyKey,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        TaskResources existingResources = resources.require(taskId);
        long runtimeEntries = entryCount(existingResources.runtimeDirectory());
        long worktrees = entryCount(workspacePaths.worktrees(workspaceId));
        assertThat(driver.probeCount()).isEqualTo(probesBeforeCreation + 1);
        assertThat(turns.findTurnByIdempotencyKey(taskId, idempotencyKey)).isEmpty();
        driver.probeReadiness(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);

        ResponseEntity<JsonNode> failedReplay = post("/agent/tasks", request, null);

        assertThat(failedReplay.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(failedReplay.getBody().required("readiness").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(driver.probeCount()).isEqualTo(probesBeforeCreation + 2);
        assertThat(turns.findTurnByIdempotencyKey(taskId, idempotencyKey)).isEmpty();
        assertThat(resources.require(taskId)).isEqualTo(existingResources);
        assertThat(entryCount(existingResources.runtimeDirectory())).isEqualTo(runtimeEntries);
        assertThat(entryCount(workspacePaths.worktrees(workspaceId))).isEqualTo(worktrees);
        assertThat(tasks.get(taskId)).isEqualTo(orphan);
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(url("/agent/tasks/" + taskId + "/stream?after=" + after)))
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

    /** A task's first events once there are at least {@code count}: a turn's automation update is recorded just after the turn completes. */
    private JsonNode recordedEvents(String taskId, int count)
    {
        String path = "/agent/tasks/" + taskId + "/events?after=0&limit=20";
        await().atMost(Duration.ofSeconds(30)).until(() -> get(path).getBody().size() >= count);
        return get(path).getBody();
    }

    private static long entryCount(Path directory)
            throws IOException
    {
        try (Stream<Path> entries = Files.walk(directory)) {
            return entries.count();
        }
    }
}
