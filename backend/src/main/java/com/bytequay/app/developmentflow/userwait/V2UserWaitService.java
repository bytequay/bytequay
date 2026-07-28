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
package com.bytequay.app.developmentflow.userwait;

import com.bytequay.app.developmentflow.persistence.V2UserWaitStore;
import com.bytequay.app.domain.AgentQuestion;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/** Product adapter for durable typed questions; owner commands resume them. */
@Component
public final class V2UserWaitService
{
    private static final TypeReference<List<AgentQuestion.Option>> OPTIONS =
            new TypeReference<>() {};

    private final V2UserWaitStore waits;
    private final ActiveAgentContextRegistry activeContexts;
    private final ObjectMapper json;
    private final Clock clock;
    private TrunkUserWaitContinuation trunkContinuations;
    private TypedUserWaitContinuation typedContinuations;

    @Autowired
    public V2UserWaitService(
            V2UserWaitStore waits,
            ActiveAgentContextRegistry activeContexts,
            ObjectMapper json)
    {
        this(waits, activeContexts, json, Clock.systemUTC());
    }

    public V2UserWaitService(
            V2UserWaitStore waits,
            ActiveAgentContextRegistry activeContexts,
            ObjectMapper json,
            Clock clock)
    {
        this.waits = requireNonNull(waits, "waits is null");
        this.activeContexts = requireNonNull(activeContexts, "activeContexts is null");
        this.json = requireNonNull(json, "json is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public Optional<String> askQuestion(
            String trunkId,
            String agentKey,
            String callId,
            String prompt,
            String context,
            List<AgentQuestion.Option> options,
            boolean allowFreeForm)
    {
        Optional<ActiveAgentContextRegistry.TypedOwner> owner =
                activeContexts.findTypedOwner(trunkId, agentKey);
        if (owner.isEmpty()) {
            failClosedV2Owner(agentKey);
            return Optional.empty();
        }
        requireSupportedContinuation(owner.orElseThrow());
        requireText(callId, "callId");
        String id = stableId("question", owner.orElseThrow(), callId);
        try {
            waits.insertQuestion(
                    owner.orElseThrow(), id, callId, prompt, context,
                    json.writeValueAsString(options == null ? List.of() : options),
                    allowFreeForm, clock.instant());
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("question options are not serializable", e);
        }
        return Optional.of(id);
    }

    /** Compatibility shape for non-transport callers. */
    public Optional<String> askQuestion(
            String trunkId,
            String agentKey,
            String prompt,
            String context,
            List<AgentQuestion.Option> options,
            boolean allowFreeForm)
    {
        return askQuestion(
                trunkId, agentKey, UUID.randomUUID().toString(), prompt,
                context, options, allowFreeForm);
    }

    public PermissionPrompt requestPermission(
            String trunkId,
            String agentKey,
            String callId,
            String capability,
            String toolName,
            JsonNode parameters,
            String policySnapshot)
    {
        ActiveAgentContextRegistry.TypedOwner owner = activeContexts
                .findTypedOwner(trunkId, agentKey)
                .orElseGet(() -> {
                    failClosedV2Owner(agentKey);
                    return null;
                });
        if (owner == null) {
            return PermissionPrompt.legacy();
        }
        requireSupportedContinuation(owner);
        requireText(callId, "callId");
        requireText(capability, "capability");
        requireText(toolName, "toolName");
        requireText(policySnapshot, "policySnapshot");
        try {
            String parametersJson = json.writeValueAsString(
                    parameters == null ? json.createObjectNode() : parameters);
            String digest = V2UserWaitDigests.sha256(parametersJson);
            String id = stableId("permission", owner, callId);
            Optional<V2UserWaitStore.PermissionRequest> existing =
                    waits.findPermission(id);
            if (existing.isPresent()) {
                V2UserWaitStore.PermissionRequest request = existing.orElseThrow();
                if (!request.owner().equals(owner)) {
                    throw new IllegalStateException(
                            "stable permission id names another typed Turn");
                }
                if (request.state().equals("DENIED")
                        || request.state().equals("CANCELED")
                        || request.state().equals("EXPIRED")) {
                    return PermissionPrompt.denied(request.answer());
                }
                // A duplicate call from the suspended owner must not consume
                // the grant intended for its successor Turn.
                return PermissionPrompt.waiting(request.id());
            }
            OptionalInt remaining = waits.consumeGrant(
                    owner, callId, toolName, digest, clock.instant());
            if (remaining.isPresent()) {
                return PermissionPrompt.allowed(remaining.orElseThrow());
            }
            // permission_request.call_id is globally unique in the typed
            // schema, while provider tool-use ids are only stable within a
            // Turn. Persist the owner-derived id in both identity columns.
            V2UserWaitStore.PermissionRequest request = waits.insertPermission(
                    owner, id, id, capability, toolName, parametersJson,
                    digest, policySnapshot, clock.instant());
            if (request.state().equals("OPEN")) {
                return PermissionPrompt.waiting(request.id());
            }
            if (request.state().equals("DENIED")
                    || request.state().equals("CANCELED")
                    || request.state().equals("EXPIRED")) {
                return PermissionPrompt.denied(request.answer());
            }
            return PermissionPrompt.denied(
                    "permission grant is no longer consumable");
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "permission parameters are not serializable", e);
        }
    }

    public V2UserWaitStore.QuestionResolution answerQuestion(
            String questionId,
            int expectedRevision,
            String answerOptionId,
            String answerFreeForm,
            String actor)
    {
        V2UserWaitStore.QuestionResolution result = waits.answerQuestion(
                questionId, expectedRevision, answerOptionId, answerFreeForm,
                actor, clock.instant());
        if (result.accepted()) {
            if (typedContinuations != null) {
                typedContinuations.resumeQuestion(result.question().id());
            }
            else if (trunkContinuations != null) {
                trunkContinuations.resumeQuestion(result.question().id());
            }
        }
        return result;
    }

    public V2UserWaitStore.PermissionResolution answerPermission(
            String trunkId,
            String callId,
            int expectedRevision,
            V2UserWaitStore.PermissionChoice choice,
            String actor)
    {
        if (waits.findPermissionForTrunk(trunkId, callId).isEmpty()) {
            throw new IllegalArgumentException(
                    "permission request does not belong to Trunk");
        }
        V2UserWaitStore.PermissionResolution result = waits.resolvePermission(
                callId, expectedRevision, choice, actor, clock.instant());
        if (result.accepted()) {
            if (typedContinuations != null) {
                typedContinuations.resumePermission(result.request().id());
            }
            else if (trunkContinuations != null) {
                trunkContinuations.resumePermission(result.request().id());
            }
        }
        return result;
    }

    @Autowired
    void setTrunkContinuations(TrunkUserWaitContinuation trunkContinuations)
    {
        this.trunkContinuations = requireNonNull(
                trunkContinuations, "trunkContinuations is null");
    }

    @Autowired
    void setTypedContinuations(TypedUserWaitContinuation typedContinuations)
    {
        this.typedContinuations = requireNonNull(
                typedContinuations, "typedContinuations is null");
    }

    public Optional<V2UserWaitStore.Question> findQuestion(String id)
    {
        return waits.findQuestion(id);
    }

    public Optional<V2UserWaitStore.PermissionRequest> findPermissionForTrunk(
            String trunkId, String callId)
    {
        return waits.findPermissionForTrunk(trunkId, callId);
    }

    public Optional<V2UserWaitStore.PermissionRequest> findPermission(
            String callId)
    {
        return waits.findPermission(callId);
    }

    public List<AgentQuestion> listOpen(String trunkId)
    {
        return waits.listOpenQuestions(trunkId).stream()
                .map(this::toQuestion)
                .toList();
    }

    public List<V2UserWaitStore.PermissionRequest> listOpenPermissions(
            String trunkId)
    {
        return waits.listOpenPermissions(trunkId);
    }

    public AgentQuestion toQuestion(V2UserWaitStore.Question question)
    {
        try {
            return new AgentQuestion(
                    question.id(), question.trunkId(), question.taskId(),
                    question.callId(), question.prompt(), question.context(),
                    json.readValue(question.optionsJson(), OPTIONS),
                    question.allowFreeForm(),
                    question.state().equals("OPEN")
                            ? AgentQuestion.STATUS_OPEN
                            : AgentQuestion.STATUS_ANSWERED,
                    question.answerOptionId(), question.answerFreeForm(),
                    question.createdAt(), question.answeredAt(),
                    question.answerRevision(), question.answerActor());
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "persisted typed question options are invalid", e);
        }
    }

    private static String stableId(
            String kind,
            ActiveAgentContextRegistry.TypedOwner owner,
            String callId)
    {
        String source = kind + ":" + owner.kind() + ":" + owner.turnId()
                + ":" + owner.operationId() + ":" + callId;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private static void failClosedV2Owner(String agentKey)
    {
        if (agentKey != null && agentKey.startsWith("v2-")) {
            throw new IllegalStateException(
                    "V2 MCP call has no exact typed Turn owner");
        }
    }

    private static void requireSupportedContinuation(
            ActiveAgentContextRegistry.TypedOwner owner)
    {
        switch (owner.kind()) {
            case THREAD_TURN, TASK_TURN, STAGE_TURN,
                    REVIEW_ASSIGNMENT_TURN -> {
                return;
            }
            default -> throw new IllegalStateException(
                    "typed user waits require an owner-specific continuation");
        }
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    public record PermissionPrompt(
            PermissionPromptState state,
            String waitId,
            String detail,
            Integer remaining)
    {
        public static PermissionPrompt legacy()
        {
            return new PermissionPrompt(
                    PermissionPromptState.LEGACY, null, null, null);
        }

        public static PermissionPrompt allowed(int remaining)
        {
            return new PermissionPrompt(
                    PermissionPromptState.ALLOWED, null, null, remaining);
        }

        public static PermissionPrompt waiting(String waitId)
        {
            return new PermissionPrompt(
                    PermissionPromptState.WAITING, waitId, null, null);
        }

        public static PermissionPrompt denied(String detail)
        {
            return new PermissionPrompt(
                    PermissionPromptState.DENIED, null, detail, null);
        }
    }

    public enum PermissionPromptState
    {
        LEGACY,
        ALLOWED,
        WAITING,
        DENIED
    }
}
