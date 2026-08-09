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

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;

import static java.util.Objects.requireNonNull;

/** Delivers TaskOutcome markers and launches optional typed Brain enrichment. */
@Component
public final class TaskOutcomeSummaryRuntime
        implements ExecutionPorts.MaintenanceWork
{
    private static final int BATCH_SIZE = 50;
    private static final Logger log =
            LoggerFactory.getLogger(TaskOutcomeSummaryRuntime.class);

    private final TrunkManager trunks;
    private final SqliteTaskOutcomeSummaryStore store;
    private final LaunchResolver launches;
    private final ObjectMapper json;
    private final int serverPort;

    @Autowired
    public TaskOutcomeSummaryRuntime(
            TrunkManager trunks,
            SqliteTaskOutcomeSummaryStore store,
            LaunchResolver launches,
            ObjectMapper json,
            @Value("${server.port:53123}") int serverPort)
    {
        this.trunks = requireNonNull(trunks, "trunks is null");
        this.store = requireNonNull(store, "store is null");
        this.launches = requireNonNull(launches, "launches is null");
        this.json = requireNonNull(json, "json is null");
        if (serverPort < 1 || serverPort > 65535) {
            throw new IllegalArgumentException("serverPort is invalid");
        }
        this.serverPort = serverPort;
    }

    @Override
    public void maintain(Instant now)
    {
        requireNonNull(now, "now is null");
        for (SqliteTaskOutcomeSummaryStore.Outcome outcome
                : store.pendingOutcomes(BATCH_SIZE)) {
            try {
                trunks.acceptTaskOutcome(new TrunkManager.TaskOutcomeFact(
                        outcome.taskOutcomeId(), outcome.deliveryKey(),
                        "v2-task-outcome", now));
            }
            catch (RuntimeException failure) {
                log.warn("TaskOutcome marker delivery deferred for {}",
                        outcome.taskOutcomeId(), failure);
            }
        }

        for (SqliteTaskOutcomeSummaryStore.Outcome outcome
                : store.summaryCandidates(BATCH_SIZE)) {
            launchSummary(outcome, now);
        }

        for (SqliteTaskOutcomeSummaryStore.Enrichment enrichment
                : store.successfulEnrichments(BATCH_SIZE)) {
            try {
                trunks.enrichTaskOutcomeSummary(
                        new TrunkManager.TaskOutcomeSummaryFact(
                                enrichment.taskOutcomeId(), enrichment.turnId(),
                                enrichment.operationId(),
                                enrichment.summaryText(),
                                enrichment.summaryDigest(),
                                enrichment.finishedAt()));
            }
            catch (RuntimeException failure) {
                log.warn("TaskOutcome summary enrichment deferred for {}",
                        enrichment.taskOutcomeId(), failure);
            }
        }
    }

    private void launchSummary(
            SqliteTaskOutcomeSummaryStore.Outcome outcome, Instant requestedAt)
    {
        try {
            LaunchSpec launch = launches.resolve(outcome);
            store.requestSummary(
                    outcome,
                    (turnId, operationId) -> launchInput(
                            launch, turnId, operationId),
                    launch.transport().name(),
                    launch.transport() == AgentTurnProviderSession.Transport.CLI
                            ? 9 : 10,
                    requestedAt);
        }
        catch (CommandRejectedException failure) {
            if (failure.reason()
                    != CommandRejectedException.Reason.STALE_VERSION
                    && failure.reason()
                    != CommandRejectedException.Reason.CONCURRENT_UPDATE) {
                log.warn("TaskOutcome summary launch rejected for {}",
                        outcome.taskOutcomeId(), failure);
            }
        }
        catch (RuntimeException failure) {
            // The completion marker is already durable and remains visible.
            log.warn("TaskOutcome summary launch deferred for {}",
                    outcome.taskOutcomeId(), failure);
        }
    }

    private String launchInput(
            LaunchSpec launch, String turnId, String operationId)
    {
        AgentTurnProviderSession.OwnerToolEndpoint endpoint =
                new AgentTurnProviderSession.OwnerToolEndpoint(
                        "bytequay",
                        "http://127.0.0.1:" + serverPort
                                + "/api/v2/task-turns/" + turnId
                                + "/operations/" + operationId + "/mcp",
                        DispatchTicket.OwnerKind.TASK_TURN,
                        turnId, operationId,
                        AgentTurnProviderSession.ToolProfile.TASK_BRAIN_READ_ONLY,
                        "mcp__bytequay__approval_prompt");
        AgentTurnOperationHandler.LaunchInput input =
                new AgentTurnOperationHandler.LaunchInput(
                        1, launch.transport(),
                        launch.provider(), launch.credentialAccount(), launch.model(),
                        launch.reasoningEffort(), launch.workingDirectory().toString(),
                        launch.systemPrompt(), launch.prompt(), endpoint);
        try {
            return json.writeValueAsString(input);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "could not freeze TaskOutcome summary launch input", e);
        }
    }

    @FunctionalInterface
    public interface LaunchResolver
    {
        LaunchSpec resolve(SqliteTaskOutcomeSummaryStore.Outcome outcome);
    }

    public record LaunchSpec(
            AgentTurnProviderSession.Transport transport,
            String provider,
            String credentialAccount,
            String model,
            String reasoningEffort,
            Path workingDirectory,
            String systemPrompt,
            String userMessage,
            String prompt)
    {
        public LaunchSpec
        {
            requireNonNull(transport, "transport is null");
            requireText(provider, "provider");
            requireText(model, "model");
            requireNonNull(workingDirectory, "workingDirectory is null");
            requireText(userMessage, "userMessage");
            requireText(prompt, "prompt");
            if (!workingDirectory.isAbsolute()
                    || !workingDirectory.normalize().equals(workingDirectory)) {
                throw new IllegalArgumentException(
                        "workingDirectory must be absolute and normalized");
            }
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
