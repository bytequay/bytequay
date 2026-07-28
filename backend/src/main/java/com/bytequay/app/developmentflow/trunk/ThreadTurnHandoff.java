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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
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
                        request.systemPrompt(), request.compiledPrompt(), endpoint);
        String launchInput = write(input);
        return trunks.requestThreadTurn(new TrunkManager.ThreadTurnCommand(
                request.commandId(), request.actor(), request.trunkId(),
                request.workspaceId(), request.expectedTrunkVersion(),
                turnId, operationId, ticketId, messageId, request.purpose(),
                request.transport().name(),
                request.transport() == AgentTurnProviderSession.Transport.CLI ? 1 : 2,
                request.planningOperationId(), request.planningBaseSha(),
                launchInput, digest(launchInput), request.userMessage(),
                digest(request.userMessage()), clock.instant()));
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
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
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
            String planningOperationId,
            String planningBaseSha)
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
                    compiledPrompt, null, null);
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
