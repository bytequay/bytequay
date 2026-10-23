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

import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TestAgentInputHttpApi
        extends AbstractTestAgentHttpApi
{
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

    private void deleteQueued(String taskId, String queuedInputId)
    {
        ResponseEntity<JsonNode> deleted = rest.exchange(
                url("/agent/tasks/" + taskId + "/queue/" + queuedInputId + "?workspaceId=" + workspaceId),
                HttpMethod.DELETE,
                new HttpEntity<>(headers()),
                JsonNode.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void queuesInputBehindARunningTurnAndStartsItAfterSuccess()
            throws Exception
    {
        driver.reportNextTurn(new DevelopmentResultReport(true, Optional.empty()));
        JsonNode created = post("/agent/tasks", createTaskBody("codex"), null).getBody();
        String taskId = created.required("id").asText();
        String queuePath = "/agent/tasks/" + taskId + "/queue?workspaceId=" + workspaceId;
        assertThat(post(queuePath, ImmutableMap.of("message", "too early", "expectedVersion", created.required("version").asLong()), "queue-idle").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        driver.reportNextTurn(new DevelopmentResultReport(true, Optional.empty()));
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
        driver.reportNextTurn(new DevelopmentResultReport(true, Optional.empty()));
        driver.completeHeldTurn();
        await().pollInSameThread()
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(50))
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(get(queuePath).getBody()).isEmpty());
        awaitContinuationSettled(taskId);
        JsonNode events = get("/agent/tasks/" + taskId + "/events?after=0&limit=20").getBody();
        assertThat(events).anySatisfy(event -> {
            assertThat(event.required("type").asText()).isEqualTo("user_message");
            assertThat(event.required("payload").required("text").asText()).isEqualTo("later");
            assertThat(event.required("payload").required("deliveryKind").asText()).isEqualTo("QUEUED");
        });
        assertThat(post("/agent/tasks/" + taskId + "/queue/resume?workspaceId=" + workspaceId, ImmutableMap.of(), null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        driver.reportNextTurn(new DevelopmentResultReport(true, Optional.empty()));
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
        awaitContinuationSettled(taskId);
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
        assertThat(get(ownerQueuePath).getBody()).singleElement().satisfies(input -> assertThat(input.required("id").asText()).isEqualTo(queuedId));
        rest.exchange(url("/agent/tasks/" + ownerTaskId + "/queue/" + queuedId + "?workspaceId=" + workspaceId), HttpMethod.DELETE, new HttpEntity<>(headers()), JsonNode.class);
        driver.completeHeldTurn();
    }

    @Test
    void movesAQueuedMessageIntoAnEditableDraft()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        String attachmentId = uploadImage(taskId, "queued.png");
        driver.holdNextTurn();
        startTurn(taskId, "keep running", "queue-edit-turn");
        String queuePath = "/agent/tasks/" + taskId + "/queue?workspaceId=" + workspaceId;
        String message = "Preserve every line of the queued message\n".repeat(20);
        String queuedId = post(queuePath, ImmutableMap.of("message", message, "attachments", ImmutableList.of(attachmentId), "expectedVersion", taskVersion(taskId)), "queue-edit")
                .getBody().required("id").asText();
        String editPath = "/agent/tasks/" + taskId + "/queue/" + queuedId + "/edit?workspaceId=" + workspaceId;
        assertThat(post("/agent/tasks/" + taskId + "/queue/" + queuedId + "/edit?workspaceId=another-workspace", ImmutableMap.of(), null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(queuePath).getBody()).hasSize(1);

        ResponseEntity<JsonNode> edited = post(editPath, ImmutableMap.of(), null);

        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getBody().required("message").asText()).isEqualTo(message);
        assertThat(edited.getBody().required("settings").required("model").asText()).isEqualTo("test-model");
        assertThat(edited.getBody().required("attachments")).singleElement().satisfies(image -> {
            assertThat(image.required("id").asText()).isEqualTo(attachmentId);
            assertThat(image.required("width").asInt()).isPositive();
            assertThat(image.has("path")).isFalse();
        });
        assertThat(get(queuePath).getBody()).isEmpty();
        assertThat(post(editPath, ImmutableMap.of(), null).getBody()).isEqualTo(edited.getBody());
        driver.completeHeldTurn();
        awaitContinuationSettled(taskId);
    }

    @Test
    void steersAQueuedMessageOnceUsingItsFullSavedInput()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        String attachmentId = uploadImage(taskId, "queued.png");
        driver.holdNextTurn();
        String turnId = startTurn(taskId, "keep running", "queued-steer-turn").required("id").asText();
        String queuePath = "/agent/tasks/" + taskId + "/queue?workspaceId=" + workspaceId;
        String message = "Preserve every line of the queued message\n".repeat(20);
        String queuedId = post(queuePath,
                ImmutableMap.of("message", message, "attachments", ImmutableList.of(attachmentId), "expectedVersion", taskVersion(taskId)), "queued-steer")
                .getBody().required("id").asText();
        String steerPath = "/agent/tasks/" + taskId + "/queue/" + queuedId + "/steer?workspaceId=" + workspaceId + "&turnId=" + turnId;

        ResponseEntity<JsonNode> steered = post(steerPath, ImmutableMap.of(), null);

        assertThat(steered.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(steered.getBody().required("state").asText()).isEqualTo("DELIVERED");
        assertThat(get(queuePath).getBody()).isEmpty();
        deleteAttachment(taskId, attachmentId);
        assertThat(post(steerPath, ImmutableMap.of(), null).getBody().required("id").asText()).isEqualTo(steered.getBody().required("id").asText());
        assertThat(get("/agent/tasks/" + taskId + "/events?after=0&limit=100").getBody())
                .filteredOn(event -> event.required("payload").path("deliveryKind").asText().equals("STEER"))
                .singleElement().satisfies(event -> assertThat(event.required("payload").required("text").asText()).isEqualTo(message));
        driver.completeHeldTurn();
        awaitContinuationSettled(taskId);
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
        String queuePath = "/agent/tasks/" + taskId + "/queue?workspaceId=" + workspaceId;
        String queuedId = post(queuePath,
                ImmutableMap.of("message", "look later", "attachments", ImmutableList.of(attachmentId), "expectedVersion", taskVersion(taskId)), "queued-image")
                .getBody().required("id").asText();
        String steerPath = "/agent/tasks/" + taskId + "/queue/" + queuedId + "/steer?workspaceId=" + workspaceId + "&turnId=" + running.required("id").asText();
        ResponseEntity<JsonNode> refusedQueued = post(steerPath, ImmutableMap.of(), null);
        assertThat(refusedQueued.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(queuePath).getBody()).hasSize(1);
        rest.exchange(url("/agent/tasks/" + taskId + "/queue/" + queuedId + "?workspaceId=" + workspaceId), HttpMethod.DELETE, new HttpEntity<>(headers()), JsonNode.class);
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
                ImmutableMap.of("message", "look", "attachments", ImmutableList.of(attachmentId), "expectedVersion", taskVersion(taskId)),
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
        Map<String, Object> body = ImmutableMap.of("message", "look", "attachments", ImmutableList.of(attachmentId), "expectedVersion", taskVersion(taskId));

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
        Map<String, Object> steerBody = ImmutableMap.of("message", "steer image", "attachments", ImmutableList.of(attachmentId));
        Map<String, Object> queueBody = ImmutableMap.of("message", "queue image", "attachments", ImmutableList.of(attachmentId), "expectedVersion", taskVersion(taskId));

        ResponseEntity<JsonNode> firstSteer = post("/agent/turns/" + running.required("id").asText() + "/steer", steerBody, "steer-image-replay");
        ResponseEntity<JsonNode> firstQueue = post("/agent/tasks/" + taskId + "/queue?workspaceId=" + workspaceId, queueBody, "queue-image-replay");
        assertThat(firstSteer.getBody().required("state").asText()).isEqualTo("DELIVERED");
        assertThat(firstQueue.getBody().required("state").asText()).isEqualTo("QUEUED");
        deleteAttachment(taskId, attachmentId);
        updateDefaults(taskId, "text-only-model");

        ResponseEntity<JsonNode> replaySteer = post("/agent/turns/" + running.required("id").asText() + "/steer", steerBody, "steer-image-replay");
        ResponseEntity<JsonNode> replayQueue = post("/agent/tasks/" + taskId + "/queue?workspaceId=" + workspaceId, queueBody, "queue-image-replay");
        assertThat(replaySteer.getBody().required("id").asText())
                .isEqualTo(firstSteer.getBody().required("id").asText());
        assertThat(replayQueue.getBody().required("id").asText())
                .isEqualTo(firstQueue.getBody().required("id").asText());

        deleteQueued(taskId, replayQueue.getBody().required("id").asText());
        driver.completeHeldTurn();
    }

    @Test
    void createsATaskWithItsFirstImageAndReplaysWithoutUploadingAgain()
            throws Exception
    {
        Map<String, Object> request = createTaskBody("codex");
        byte[] image = png(9, 4);
        ResponseEntity<JsonNode> created = createTaskWithImage(request, "failure.png", MediaType.IMAGE_PNG, image);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String taskId = created.getBody().required("id").asText();
        JsonNode events = get("/agent/tasks/" + taskId + "/events?after=0&limit=20").getBody();
        JsonNode message = StreamSupport.stream(events.spliterator(), false)
                .filter(event -> event.path("type").asText().equals("user_message"))
                .findFirst().orElseThrow();
        String attachmentId = message.required("payload").required("attachments").get(0).required("id").asText();
        assertThat(message.required("payload").required("attachments").get(0).required("name").asText()).isEqualTo("failure.png");
        assertThat(Files.readAllBytes(resources.readAttachment(TaskId.valueOf(taskId), attachmentId).path())).isEqualTo(image);
        assertThat(turns.findTurnByIdempotencyKey(TaskId.valueOf(taskId), request.get("idempotencyKey").toString()).orElseThrow()
                .settings().orElseThrow().attachments()).singleElement().satisfies(attachment -> assertThat(attachment.id()).isEqualTo(attachmentId));
        deleteAttachment(taskId, attachmentId);
        assertThat(createTaskWithImage(request, "failure.png", MediaType.IMAGE_PNG, image).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createTaskWithImage(request, "failure.png", MediaType.IMAGE_PNG, png(10, 4)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(get("/agent/tasks/" + taskId + "/events?after=0&limit=20").getBody())
                .filteredOn(event -> event.path("type").asText().equals("user_message"))
                .hasSize(1);
        assertThat(get("/agent/tasks?workspaceId=" + workspaceId).getBody()).hasSize(1);
    }

    @Test
    void rejectsInvalidInitialImagesBeforeAllocatingTasksOrWorktrees()
            throws Exception
    {
        assertThat(createTaskWithImage(createTaskBody("codex"), "bad.png", MediaType.IMAGE_PNG, new byte[] {1, 2, 3}).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(createTaskWithImage(createTaskBody("codex", "text-only-model", "Look"), "image.png", MediaType.IMAGE_PNG, png(3, 3)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(createTaskWithImage(createTaskBody("codex"), "wrong.jpg", MediaType.IMAGE_JPEG, png(3, 3)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/agent/tasks?workspaceId=" + workspaceId).getBody()).isEmpty();
        assertThat(workspacePaths.worktrees(workspaceId)).doesNotExist();
    }

    private ResponseEntity<JsonNode> createTaskWithImage(Map<String, Object> request, String name, MediaType mediaType, byte[] bytes)
    {
        HttpHeaders metadataHeaders = new HttpHeaders();
        metadataHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpHeaders imageHeaders = new HttpHeaders();
        imageHeaders.setContentType(mediaType);
        imageHeaders.setContentDispositionFormData("images", name);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("request", new HttpEntity<>(request, metadataHeaders));
        parts.add("images", new HttpEntity<>(bytes, imageHeaders));
        HttpHeaders requestHeaders = headers();
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange(url("/agent/tasks"), HttpMethod.POST, new HttpEntity<>(parts, requestHeaders), JsonNode.class);
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
                ImmutableMap.of("message", "What is in this screenshot?", "attachments", ImmutableList.of(attachmentId), "expectedVersion", taskVersion(taskId)),
                "attach-1").getBody();
        assertThat(turn.required("settings").required("attachments").get(0).required("id").asText()).isEqualTo(attachmentId);
        assertThat(turn.required("settings").required("attachments").get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("id", "name", "mediaType");
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
                ImmutableMap.of("message", "look", "attachments", ImmutableList.of(attachmentId), "expectedVersion", taskVersion(taskId)),
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
}
