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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.ImageAttachment;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Attachment;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Request;
import com.bytequay.app.domain.WorkModelKind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Applies the single Stage-owned policy for safe CLI session continuity. */
final class StageCliContinuity
{
    private StageCliContinuity() {}

    static void freezeImages(
            ObjectMapper json,
            ObjectNode launch,
            List<Attachment> attachments)
    {
        requireNonNull(json, "json is null");
        requireNonNull(launch, "launch is null");
        requireNonNull(attachments, "attachments is null");
        Set<ImageAttachment> images = new LinkedHashSet<>();
        attachments.forEach(attachment -> images.add(new ImageAttachment(
                attachment.contentRef(), attachment.mediaType(),
                attachment.contentDigest())));
        if (images.isEmpty()) {
            launch.remove("images");
            return;
        }
        launch.set("images", json.valueToTree(images));
    }

    static void apply(
            ObjectMapper json,
            ObjectNode launch,
            Request request,
            WorkModelKind modelKind,
            String currentPrompt,
            SqliteStageSteeringStore store,
            Fence fence)
    {
        requireNonNull(json, "json is null");
        requireNonNull(launch, "launch is null");
        requireNonNull(request, "request is null");
        requireNonNull(modelKind, "modelKind is null");
        requireNonNull(currentPrompt, "currentPrompt is null");
        requireNonNull(store, "store is null");
        requireNonNull(fence, "fence is null");
        if (request.predecessor() == null) {
            return;
        }
        boolean userWait = request.mode()
                == V2StageSteeringControl.Mode.CANCEL_AND_REPLACE
                && store.isUserWaitContinuation(request.id());
        if (!userWait && (request.mode()
                == V2StageSteeringControl.Mode.CANCEL_AND_REPLACE
                || modelKind != WorkModelKind.CLI)) {
            return;
        }
        SqliteStageSteeringStore.CliContinuation continuation = store
                .cliContinuation(
                        request, fence.stageId(), fence.stageGeneration(),
                        fence.codeFingerprint(), fence.headSha(), fence.baseSha(),
                        fence.provider(), fence.model(), fence.workingDirectory())
                .orElse(null);
        if (continuation == null) {
            return;
        }
        apply(json, launch, currentPrompt, store, continuation,
                modelKind == WorkModelKind.CLI);
    }

    static void applyExact(
            ObjectMapper json,
            ObjectNode launch,
            String predecessorTurnId,
            WorkModelKind modelKind,
            String currentPrompt,
            SqliteStageSteeringStore store,
            Fence fence)
    {
        requireNonNull(json, "json is null");
        requireNonNull(launch, "launch is null");
        requireNonNull(modelKind, "modelKind is null");
        requireNonNull(currentPrompt, "currentPrompt is null");
        requireNonNull(store, "store is null");
        requireNonNull(fence, "fence is null");
        if (predecessorTurnId == null
                || predecessorTurnId.isBlank()) {
            return;
        }
        SqliteStageSteeringStore.CliContinuation continuation = store
                .cliContinuation(
                        predecessorTurnId, fence.stageId(),
                        fence.stageGeneration(), fence.codeFingerprint(),
                        fence.headSha(), fence.baseSha(), fence.provider(),
                        fence.model(), fence.workingDirectory())
                .orElse(null);
        if (continuation == null) {
            return;
        }
        apply(json, launch, currentPrompt, store, continuation,
                modelKind == WorkModelKind.CLI);
    }

    private static void apply(
            ObjectMapper json,
            ObjectNode launch,
            String currentPrompt,
            SqliteStageSteeringStore store,
            SqliteStageSteeringStore.CliContinuation continuation,
            boolean allowSessionResume)
    {
        try {
            JsonNode previous = json.readTree(continuation.launchInput());
            mergeImages(json, previous, launch);
            String priorPrompt = previous.hasNonNull("fallbackPrompt")
                    ? required(previous.path("fallbackPrompt").asText(),
                            "fallbackPrompt")
                    : required(previous.path("prompt").asText(), "prompt");
            StringBuilder fallback = new StringBuilder(priorPrompt);
            List<String> trace = store.executionLog(continuation.executionId());
            if (!trace.isEmpty()) {
                fallback.append("\n\nDurable provider trace from the prior Turn:\n");
                trace.forEach(event -> fallback.append(event).append('\n'));
            }
            fallback.append("\n\nNext Stage instruction:\n")
                    .append(currentPrompt);
            boolean cumulativeReady = !"codex".equals(
                    launch.path("provider").asText())
                    || (continuation.cumulativeInputTokens() != null
                    && continuation.cumulativeOutputTokens() != null);
            if (allowSessionResume && continuation.sessionReusable()
                    && cumulativeReady) {
                launch.put("prompt", currentPrompt);
                launch.put("resumeSessionId", continuation.providerSessionId());
                launch.put("fallbackPrompt", fallback.toString());
                if (continuation.cumulativeInputTokens() != null) {
                    launch.put("priorCumulativeInputTokens",
                            continuation.cumulativeInputTokens());
                    launch.put("priorCumulativeOutputTokens",
                            continuation.cumulativeOutputTokens());
                }
            }
            else {
                launch.put("prompt", fallback.toString());
                launch.remove(List.of(
                        "resumeSessionId", "fallbackPrompt",
                        "priorCumulativeInputTokens",
                        "priorCumulativeOutputTokens"));
            }
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Stored StageTurn launch input is invalid", e);
        }
    }

    private static void mergeImages(
            ObjectMapper json,
            JsonNode previous,
            ObjectNode launch)
            throws JsonProcessingException
    {
        Set<ImageAttachment> images = new LinkedHashSet<>();
        readImages(json, previous.path("images"), images);
        readImages(json, launch.path("images"), images);
        if (images.isEmpty()) {
            launch.remove("images");
            return;
        }
        launch.set("images", json.valueToTree(images));
    }

    private static void readImages(
            ObjectMapper json,
            JsonNode frozen,
            Set<ImageAttachment> images)
            throws JsonProcessingException
    {
        if (frozen.isMissingNode() || frozen.isNull()) {
            return;
        }
        if (!frozen.isArray()) {
            throw new IllegalStateException(
                    "Stored StageTurn images are invalid");
        }
        for (JsonNode image : frozen) {
            images.add(json.treeToValue(image, ImageAttachment.class));
        }
    }

    private static String required(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("%s is missing".formatted(name));
        }
        return value;
    }

    record Fence(
            String stageId,
            long stageGeneration,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String provider,
            String model,
            String workingDirectory) {}
}
