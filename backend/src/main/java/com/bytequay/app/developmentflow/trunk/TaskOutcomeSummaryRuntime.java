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
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final TrunkManager.Store trunkStore;
    private final SqliteTaskOutcomeSummaryStore store;
    private final ThreadTurnHandoff turns;
    private final LaunchResolver launches;

    public TaskOutcomeSummaryRuntime(
            TrunkManager trunks,
            TrunkManager.Store trunkStore,
            SqliteTaskOutcomeSummaryStore store,
            ThreadTurnHandoff turns,
            LaunchResolver launches)
    {
        this.trunks = requireNonNull(trunks, "trunks is null");
        this.trunkStore = requireNonNull(trunkStore, "trunkStore is null");
        this.store = requireNonNull(store, "store is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.launches = requireNonNull(launches, "launches is null");
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
            launchSummary(outcome);
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

    private void launchSummary(SqliteTaskOutcomeSummaryStore.Outcome outcome)
    {
        String commandId = "TASK_OUTCOME_SUMMARY:" + outcome.taskOutcomeId();
        try {
            TrunkManager.ThreadTurnRequestReceipt receipt =
                    trunkStore.findThreadTurnRequest(
                                    outcome.trunkId(), commandId)
                            .orElseGet(() -> requestTurn(
                                    outcome, commandId));
            trunks.bindTaskOutcomeSummary(
                    new TrunkManager.TaskOutcomeSummaryBinding(
                            outcome.taskOutcomeId(), receipt.turnId()));
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

    private TrunkManager.ThreadTurnRequestReceipt requestTurn(
            SqliteTaskOutcomeSummaryStore.Outcome outcome,
            String commandId)
    {
        LaunchSpec launch = launches.resolve(outcome);
        TrunkManager.State current = trunkStore.findById(outcome.trunkId())
                .orElseThrow(() -> new IllegalStateException(
                        "V2 Trunk disappeared before outcome summary"));
        return turns.request(new ThreadTurnHandoff.Request(
                commandId, "v2-task-outcome", outcome.trunkId(),
                outcome.workspaceId(), current.version(),
                "TASK_COMPLETION_SUMMARY", launch.transport(),
                launch.provider(), launch.credentialAccount(), launch.model(),
                launch.reasoningEffort(), launch.workingDirectory(),
                launch.systemPrompt(), launch.userMessage(), launch.prompt(),
                null, null)).state();
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
