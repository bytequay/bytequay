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
package com.bytequay.app.developmentflow.trunk;

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.service.threads.MessageAttachments;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/** Freezes and admits one Trunk conversation request as a typed ThreadTurn. */
public final class ThreadTurnHandoff
{
    private final TrunkManager trunks;
    private final ObjectMapper json;
    private final Clock clock;
    private final int serverPort;

    public ThreadTurnHandoff(
            TrunkManager trunks,
            ObjectMapper json,
            Clock clock,
            int serverPort)
    {
        this.trunks = requireNonNull(trunks, "trunks is null");
        this.json = requireNonNull(json, "json is null");
        this.clock = requireNonNull(clock, "clock is null");
        if (serverPort < 1 || serverPort > 65535) {
            throw new IllegalArgumentException("serverPort is invalid");
        }
        this.serverPort = serverPort;
    }

    public CommandResult<TrunkManager.ThreadTurnRequestReceipt> request(
            Request request)
    {
        return trunks.requestThreadTurn(prepare(request));
    }

    /** Freezes a command without committing it, for an enclosing Trunk command. */
    public TrunkManager.ThreadTurnCommand prepare(Request request)
    {
        requireNonNull(request, "request is null");
        String turnId = id("turn", request.trunkId(), request.commandId());
        String operationId = id(
                "operation", request.trunkId(), request.commandId());
        String ticketId = id("ticket", request.trunkId(), request.commandId());
        String messageId = id("user-message", request.trunkId(), request.commandId());
        AgentTurnProviderSession.OwnerToolEndpoint endpoint =
                new AgentTurnProviderSession.OwnerToolEndpoint(
                        "bytequay",
                        "http://127.0.0.1:" + serverPort
                                + "/api/v2/thread-turns/" + turnId
                                + "/operations/" + operationId + "/mcp",
                        DispatchTicket.OwnerKind.THREAD_TURN,
                        turnId,
                        operationId,
                        AgentTurnProviderSession.ToolProfile.TRUNK_CONTROL_READ_ONLY,
                        "mcp__bytequay__approval_prompt");
        AgentTurnOperationHandler.LaunchInput input =
                new AgentTurnOperationHandler.LaunchInput(
                        1, request.transport(), request.provider(),
                        request.credentialAccount(), request.model(),
                        request.reasoningEffort(),
                        request.workingDirectory().toString(),
                        request.systemPrompt(), request.compiledPrompt(),
                        request.images(), endpoint,
                        request.resumeSessionId(), request.fallbackPrompt(),
                        request.priorCumulativeInputTokens(),
                        request.priorCumulativeOutputTokens());
        String launchInput = write(input);
        verifyImages(request.images());
        List<TrunkManager.ThreadTurnAttachment> attachments = attachments(
                turnId, request.messageImages());
        return new TrunkManager.ThreadTurnCommand(
                request.commandId(), request.actor(), request.trunkId(),
                request.workspaceId(), request.expectedTrunkVersion(),
                turnId, operationId, ticketId, messageId, request.purpose(),
                request.transport().name(),
                request.transport() == AgentTurnProviderSession.Transport.CLI ? 1 : 2,
                request.planningOperationId(), request.planningBaseSha(),
                launchInput, digest(launchInput), request.userMessage(),
                digest(request.userMessage()), attachments, clock.instant());
    }

    private static List<TrunkManager.ThreadTurnAttachment> attachments(
            String turnId,
            List<AgentTurnProviderSession.ImageAttachment> images)
    {
        List<TrunkManager.ThreadTurnAttachment> attachments =
                new ArrayList<>(images.size());
        for (int index = 0; index < images.size(); index++) {
            AgentTurnProviderSession.ImageAttachment image = images.get(index);
            attachments.add(new TrunkManager.ThreadTurnAttachment(
                    "%s:attachment:%08d".formatted(turnId, index + 1),
                    "image", image.path(), image.mediaType(), image.digest()));
        }
        return List.copyOf(attachments);
    }

    private static void verifyImages(
            List<AgentTurnProviderSession.ImageAttachment> images)
    {
        for (AgentTurnProviderSession.ImageAttachment image : images) {
            try {
                image.readVerified();
            }
            catch (IllegalArgumentException | IllegalStateException e) {
                throw new InvalidFrozenAttachmentException(e.getMessage(), e);
            }
        }
    }

    public static List<AgentTurnProviderSession.ImageAttachment> freezeImages(
            List<String> images)
    {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        List<AgentTurnProviderSession.ImageAttachment> frozen =
                new ArrayList<>(images.size());
        for (String image : images) {
            byte[] content;
            try {
                content = Files.readAllBytes(Path.of(image));
            }
            catch (IOException e) {
                throw new IllegalStateException(
                        "could not freeze ThreadTurn attachment " + image, e);
            }
            frozen.add(new AgentTurnProviderSession.ImageAttachment(
                    image, MessageAttachments.mimeTypeFor(image), digest(content)));
        }
        return List.copyOf(frozen);
    }

    static final class InvalidFrozenAttachmentException
            extends IllegalStateException
    {
        private InvalidFrozenAttachmentException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("could not freeze ThreadTurn input", e);
        }
    }

    private static String id(String kind, String trunkId, String commandId)
    {
        return UUID.nameUUIDFromBytes(
                ("v2-thread-turn:" + kind + ":" + trunkId + ":" + commandId)
                        .getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Stable id returned before an asynchronous planning refresh completes. */
    public static String turnIdFor(String trunkId, String commandId)
    {
        requireText(trunkId, "trunkId");
        requireText(commandId, "commandId");
        return id("turn", trunkId, commandId);
    }

    static String digest(String value)
    {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] value)
    {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record Request(
            String commandId,
            String actor,
            String trunkId,
            String workspaceId,
            long expectedTrunkVersion,
            String purpose,
            AgentTurnProviderSession.Transport transport,
            String provider,
            String credentialAccount,
            String model,
            String reasoningEffort,
            Path workingDirectory,
            String systemPrompt,
            String userMessage,
            String compiledPrompt,
            List<AgentTurnProviderSession.ImageAttachment> images,
            List<AgentTurnProviderSession.ImageAttachment> messageImages,
            String planningOperationId,
            String planningBaseSha,
            String resumeSessionId,
            String fallbackPrompt,
            long priorCumulativeInputTokens,
            long priorCumulativeOutputTokens)
    {
        public Request
        {
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(trunkId, "trunkId");
            requireText(workspaceId, "workspaceId");
            requireText(purpose, "purpose");
            requireNonNull(transport, "transport is null");
            requireText(provider, "provider");
            requireText(model, "model");
            requireNonNull(workingDirectory, "workingDirectory is null");
            requireText(userMessage, "userMessage");
            requireText(compiledPrompt, "compiledPrompt");
            images = images == null ? List.of() : List.copyOf(images);
            messageImages = messageImages == null
                    ? List.of() : List.copyOf(messageImages);
            if (!images.containsAll(messageImages)) {
                throw new IllegalArgumentException(
                        "message images must be included in provider images");
            }
            if (expectedTrunkVersion < 0) {
                throw new IllegalArgumentException(
                        "expectedTrunkVersion is negative");
            }
            if (!workingDirectory.isAbsolute()
                    || !workingDirectory.normalize().equals(workingDirectory)) {
                throw new IllegalArgumentException(
                        "workingDirectory must be an absolute normalized path");
            }
            if (credentialAccount != null && credentialAccount.isBlank()) {
                throw new IllegalArgumentException(
                        "credentialAccount must not be blank");
            }
            if (transport == AgentTurnProviderSession.Transport.CLI
                    && credentialAccount != null) {
                throw new IllegalArgumentException(
                        "CLI provider credentials are managed outside ByteQuay");
            }
            if (reasoningEffort != null && reasoningEffort.isBlank()) {
                throw new IllegalArgumentException(
                        "reasoningEffort must not be blank");
            }
            if (systemPrompt != null && systemPrompt.isBlank()) {
                throw new IllegalArgumentException("systemPrompt must not be blank");
            }
            if ((planningOperationId == null) != (planningBaseSha == null)) {
                throw new IllegalArgumentException(
                        "planning Operation and base SHA must be supplied together");
            }
            if (planningOperationId != null
                    && (planningOperationId.isBlank() || planningBaseSha.isBlank())) {
                throw new IllegalArgumentException(
                        "planning Operation and base SHA must not be blank");
            }
            if ((resumeSessionId == null) != (fallbackPrompt == null)) {
                throw new IllegalArgumentException(
                        "resume session and fallback prompt must be supplied together");
            }
            if (resumeSessionId != null
                    && (transport != AgentTurnProviderSession.Transport.CLI
                    || resumeSessionId.isBlank() || fallbackPrompt.isBlank())) {
                throw new IllegalArgumentException(
                        "CLI resume input is invalid");
            }
            if (priorCumulativeInputTokens < 0
                    || priorCumulativeOutputTokens < 0
                    || (resumeSessionId == null
                    && (priorCumulativeInputTokens != 0
                    || priorCumulativeOutputTokens != 0))) {
                throw new IllegalArgumentException(
                        "CLI cumulative resume baseline is invalid");
            }
        }

        public Request(
                String commandId,
                String actor,
                String trunkId,
                String workspaceId,
                long expectedTrunkVersion,
                String purpose,
                AgentTurnProviderSession.Transport transport,
                String provider,
                String credentialAccount,
                String model,
                String reasoningEffort,
                Path workingDirectory,
                String systemPrompt,
                String userMessage,
                String compiledPrompt,
                List<AgentTurnProviderSession.ImageAttachment> images,
                String planningOperationId,
                String planningBaseSha,
                String resumeSessionId,
                String fallbackPrompt)
        {
            this(commandId, actor, trunkId, workspaceId, expectedTrunkVersion,
                    purpose, transport, provider, credentialAccount, model,
                    reasoningEffort, workingDirectory, systemPrompt, userMessage,
                    compiledPrompt, images, images, planningOperationId,
                    planningBaseSha, resumeSessionId, fallbackPrompt, 0, 0);
        }

        public Request(
                String commandId,
                String actor,
                String trunkId,
                String workspaceId,
                long expectedTrunkVersion,
                String purpose,
                AgentTurnProviderSession.Transport transport,
                String provider,
                String credentialAccount,
                String model,
                String reasoningEffort,
                Path workingDirectory,
                String systemPrompt,
                String userMessage,
                String compiledPrompt,
                List<AgentTurnProviderSession.ImageAttachment> images,
                String planningOperationId,
                String planningBaseSha,
                String resumeSessionId,
                String fallbackPrompt,
                long priorCumulativeInputTokens,
                long priorCumulativeOutputTokens)
        {
            this(commandId, actor, trunkId, workspaceId, expectedTrunkVersion,
                    purpose, transport, provider, credentialAccount, model,
                    reasoningEffort, workingDirectory, systemPrompt, userMessage,
                    compiledPrompt, images, images, planningOperationId,
                    planningBaseSha, resumeSessionId, fallbackPrompt,
                    priorCumulativeInputTokens,
                    priorCumulativeOutputTokens);
        }

        public Request(
                String commandId,
                String actor,
                String trunkId,
                String workspaceId,
                long expectedTrunkVersion,
                String purpose,
                AgentTurnProviderSession.Transport transport,
                String provider,
                String credentialAccount,
                String model,
                String reasoningEffort,
                Path workingDirectory,
                String systemPrompt,
                String userMessage,
                String compiledPrompt,
                List<AgentTurnProviderSession.ImageAttachment> images,
                String planningOperationId,
                String planningBaseSha)
        {
            this(commandId, actor, trunkId, workspaceId, expectedTrunkVersion,
                    purpose, transport, provider, credentialAccount, model,
                    reasoningEffort, workingDirectory, systemPrompt, userMessage,
                    compiledPrompt, images, images, planningOperationId,
                    planningBaseSha, null, null, 0, 0);
        }

        /** Compatibility constructor for non-planning and pre-V260 tests. */
        public Request(
                String commandId,
                String actor,
                String trunkId,
                String workspaceId,
                long expectedTrunkVersion,
                String purpose,
                AgentTurnProviderSession.Transport transport,
                String provider,
                String credentialAccount,
                String model,
                String reasoningEffort,
                Path workingDirectory,
                String systemPrompt,
                String userMessage,
                String compiledPrompt)
        {
            this(commandId, actor, trunkId, workspaceId, expectedTrunkVersion,
                    purpose, transport, provider, credentialAccount, model,
                    reasoningEffort, workingDirectory, systemPrompt, userMessage,
                    compiledPrompt, List.of(), List.of(), null, null, null, null,
                    0, 0);
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
