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
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static java.util.Objects.requireNonNull;

/** Durable refresh-to-typed-ThreadTurn handoff for V2 Trunk conversation. */
public final class PlanningBaseTurnRuntime
        implements ExecutionPorts.ResultDeliveryPort
{
    private final TrunkManager trunks;
    private final TrunkManager.Store trunkStore;
    private final SqlitePlanningBaseTurnStore store;
    private final ThreadTurnHandoff turns;
    private final WorkspaceRepositoryResolver repositories;
    private final WatchedRepoStore watchedRepos;
    private final ObjectMapper json;
    private final ObjectReader snapshotReader;
    private final ObjectReader intentReader;
    private final Clock clock;

    public PlanningBaseTurnRuntime(
            TrunkManager trunks,
            TrunkManager.Store trunkStore,
            SqlitePlanningBaseTurnStore store,
            ThreadTurnHandoff turns,
            WorkspaceRepositoryResolver repositories,
            WatchedRepoStore watchedRepos,
            ObjectMapper json,
            Clock clock)
    {
        this.trunks = requireNonNull(trunks, "trunks is null");
        this.trunkStore = requireNonNull(trunkStore, "trunkStore is null");
        this.store = requireNonNull(store, "store is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.json = requireNonNull(json, "json is null");
        this.snapshotReader = json.readerFor(
                        PlanningBaseRefreshOperationHandler.Snapshot.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.intentReader = json.readerFor(LaunchIntent.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** Persists only the refresh request; the returned Turn id is launched later. */
    public Receipt request(Request request)
    {
        requireNonNull(request, "request is null");
        String planningId = id("planning", request.trunkId(), request.commandId());
        String operationId = id("operation", request.trunkId(), request.commandId());
        String ticketId = id("ticket", request.trunkId(), request.commandId());
        String launchCommandId = id(
                "launch", request.trunkId(), request.commandId());
        String turnId = ThreadTurnHandoff.turnIdFor(
                request.trunkId(), launchCommandId);
        LaunchIntent intent = new LaunchIntent(
                1, request.purpose(), request.transport(), request.provider(),
                request.credentialAccount(), request.model(),
                request.reasoningEffort(), request.systemPrompt(),
                request.userMessage(), request.prompt());
        String frozen = write(intent);
        Optional<TrunkManager.PlanningBaseRequestReceipt> existing =
                trunkStore.findPlanningBaseRequest(
                        request.trunkId(), request.commandId());
        String repositoryRoot;
        String previous;
        long expectedVersion;
        Instant requestedAt;
        if (existing.isPresent()) {
            // Idempotent replay is a database operation.  It must still work
            // after the watched clone has moved or temporarily disappeared.
            SqlitePlanningBaseTurnStore.RequestBase requested =
                    store.requestedBase(planningId);
            repositoryRoot = requested.repositoryRoot();
            previous = requested.previousBaseSha();
            expectedVersion = existing.orElseThrow().expectedVersion();
            requestedAt = existing.orElseThrow().recordedAt();
        }
        else {
            Path resolvedRoot = repositoryRoot(request.workspaceId());
            TrunkManager.State current = trunkStore.findById(request.trunkId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "no V2 Trunk: " + request.trunkId()));
            repositoryRoot = resolvedRoot.toString();
            previous = store.currentBaseSha(
                    request.trunkId(), repositoryRoot).orElse(null);
            expectedVersion = current.version();
            requestedAt = clock.instant();
        }
        CommandResult<TrunkManager.PlanningBaseRequestReceipt> accepted =
                trunks.requestPlanningBaseRefresh(new TrunkManager.PlanningBaseCommand(
                        request.commandId(), request.actor(), request.trunkId(),
                        request.workspaceId(), expectedVersion, planningId,
                        operationId, ticketId, launchCommandId,
                        turnId, repositoryRoot, previous, frozen,
                        ThreadTurnHandoff.digest(frozen), requestedAt));
        return new Receipt(
                accepted.state().reservedThreadTurnId(),
                accepted.state().planningOperationId(),
                accepted.state().operationId(), accepted.state().dispatchTicketId(),
                accepted.disposition());
    }

    @Override
    public DispatchTicket.DeliveryReceipt deliver(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(expectedFence, "expectedFence is null");
        requireNonNull(rawResult, "rawResult is null");
        if (owner.kind() != DispatchTicket.OwnerKind.TRUNK
                || !PlanningBaseRefreshOperationHandler.CALLBACK_ROUTE.equals(
                owner.callbackRoute())
                || expectedFence.taskEpoch() != null
                || expectedFence.stageId() != null
                || expectedFence.stageGeneration() != null
                || expectedFence.expectedCodeFingerprint() != null
                || expectedFence.expectedHeadSha() != null
                || !expectedFence.equals(rawResult.fence())) {
            return delivery(SUPERSEDED, "planning owner/fence is stale");
        }
        String rawDigest = ThreadTurnHandoff.digest(write(rawResult));
        PlanningBaseRefreshOperationHandler.Snapshot snapshot =
                rawResult.outcome() == DispatchTicket.Outcome.SUCCEEDED
                        ? readSnapshot(rawResult.payloadJson()) : null;
        if (snapshot != null
                && (!owner.id().equals(snapshot.trunkId())
                || !expectedFence.operationId().equals(snapshot.operationId()))) {
            throw new IllegalArgumentException(
                    "planning snapshot does not name its exact owner");
        }
        TrunkManager.PlanningBaseResultFact fact =
                new TrunkManager.PlanningBaseResultFact(
                        id("result", expectedFence.operationId(), rawDigest),
                        "v2-planning-base-delivery",
                        expectedFence.operationId(), expectedFence.attempt(),
                        expectedFence.expectedBaseSha(),
                        rawResult.outcome().name(), rawDigest,
                        snapshot == null ? null : snapshot.repositoryRoot(),
                        snapshot == null ? null : snapshot.worktreePath(),
                        snapshot == null ? null : snapshot.baseRef(),
                        snapshot == null ? null : snapshot.baseSha(),
                        rawResult.error(), clock.instant());
        CommandResult<TrunkManager.PlanningBaseResultReceipt> result =
                trunks.acceptPlanningBaseRefresh(fact);
        return delivery(
                "SUPERSEDED".equals(result.state().acceptance())
                        ? SUPERSEDED : ACCEPTED,
                result.state().operationStatus());
    }

    @Override
    public void afterDeliveryCommitted(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult,
            DispatchTicket.DeliveryReceipt receipt)
    {
        if (receipt.acceptance() == ACCEPTED
                && rawResult.outcome() == DispatchTicket.Outcome.SUCCEEDED) {
            launchReady(expectedFence.operationId());
        }
    }

    @Override
    public void recoverCommittedDeliveries(int limit)
    {
        for (String operationId : store.readyOperationIds(limit)) {
            launchReady(operationId);
        }
    }

    private void launchReady(String operationId)
    {
        SqlitePlanningBaseTurnStore.LaunchCandidate candidate =
                store.findLaunchCandidate(operationId).orElse(null);
        if (candidate == null) {
            return;
        }
        if (!ThreadTurnHandoff.digest(candidate.launchIntent())
                .equals(candidate.launchIntentDigest())) {
            throw new IllegalStateException("planning launch intent digest changed");
        }
        LaunchIntent intent = readIntent(candidate.launchIntent());
        TrunkManager.ThreadTurnRequestReceipt existing =
                trunkStore.findThreadTurnRequest(
                        candidate.trunkId(), candidate.launchCommandId())
                        .orElse(null);
        String turnId;
        if (existing != null) {
            turnId = existing.turnId();
        }
        else {
            TrunkManager.State current = trunkStore.findById(candidate.trunkId())
                    .orElseThrow(() -> new IllegalStateException(
                            "V2 Trunk disappeared before launch"));
            String prompt = movement(candidate.previousBaseSha(), candidate.baseSha())
                    + intent.prompt();
            turnId = turns.request(new ThreadTurnHandoff.Request(
                    candidate.launchCommandId(), candidate.actor(),
                    candidate.trunkId(), candidate.workspaceId(), current.version(),
                    intent.purpose(), intent.transport(), intent.provider(),
                    intent.credentialAccount(), intent.model(),
                    intent.reasoningEffort(), Path.of(candidate.worktreePath()),
                    intent.systemPrompt(), intent.userMessage(), prompt,
                    candidate.planningOperationId(), candidate.baseSha()))
                    .state().turnId();
        }
        if (!candidate.reservedThreadTurnId().equals(turnId)) {
            throw new IllegalStateException(
                    "planning launch did not use its reserved ThreadTurn id");
        }
        store.markLaunched(candidate, turnId, clock.instant());
    }

    private Path repositoryRoot(String workspaceId)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repository =
                repositories.resolve(workspaceId);
        WatchedRepo watched = watchedRepos.find(
                        repository.owner(), repository.repo())
                .orElseThrow(() -> new IllegalStateException(
                        "workspace repository has no watched clone: "
                                + repository.fullName()));
        if (watched.localClonePath() == null
                || watched.localClonePath().isBlank()) {
            throw new IllegalStateException(
                    "workspace repository has no verified local clone: "
                            + repository.fullName());
        }
        Path root = Path.of(watched.localClonePath())
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException(
                    "workspace repository clone is unavailable: " + root);
        }
        return root;
    }

    private PlanningBaseRefreshOperationHandler.Snapshot readSnapshot(String value)
    {
        try {
            return snapshotReader.readValue(value);
        }
        catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "planning snapshot payload is invalid", e);
        }
    }

    private LaunchIntent readIntent(String value)
    {
        try {
            return intentReader.readValue(value);
        }
        catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("planning launch intent is invalid", e);
        }
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("could not freeze planning data", e);
        }
    }

    private static String movement(String previous, String current)
    {
        if (previous == null || previous.equals(current)) {
            return "";
        }
        return "[Planning base updated " + shortSha(previous) + " -> "
                + shortSha(current)
                + ": re-verify earlier file and line assumptions.]\n\n";
    }

    private static String shortSha(String sha)
    {
        return sha.length() <= 12 ? sha : sha.substring(0, 12);
    }

    private static DispatchTicket.DeliveryReceipt delivery(
            DispatchTicket.Acceptance acceptance, String result)
    {
        return new DispatchTicket.DeliveryReceipt(
                acceptance,
                "{\"schema\":\"PLANNING_BASE_DELIVERY_V1\","
                        + "\"result\":\"" + escape(result) + "\"}");
    }

    private static String escape(String value)
    {
        return value == null ? "" : value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static String id(String kind, String left, String right)
    {
        return UUID.nameUUIDFromBytes(
                ("v2-planning-turn:" + kind + ":" + left + ":" + right)
                        .getBytes(StandardCharsets.UTF_8)).toString();
    }

    public record Request(
            String commandId,
            String actor,
            String trunkId,
            String workspaceId,
            String purpose,
            AgentTurnProviderSession.Transport transport,
            String provider,
            String credentialAccount,
            String model,
            String reasoningEffort,
            String systemPrompt,
            String userMessage,
            String prompt)
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
            requireText(userMessage, "userMessage");
            requireText(prompt, "prompt");
        }
    }

    public record Receipt(
            String turnId,
            String planningOperationId,
            String operationId,
            String dispatchTicketId,
            CommandResult.Disposition disposition)
    {}

    private record LaunchIntent(
            int schemaVersion,
            String purpose,
            AgentTurnProviderSession.Transport transport,
            String provider,
            String credentialAccount,
            String model,
            String reasoningEffort,
            String systemPrompt,
            String userMessage,
            String prompt)
    {
        private LaunchIntent
        {
            if (schemaVersion != 1) {
                throw new IllegalArgumentException(
                        "unsupported planning launch intent");
            }
            requireText(purpose, "purpose");
            requireNonNull(transport, "transport is null");
            requireText(provider, "provider");
            requireText(model, "model");
            requireText(userMessage, "userMessage");
            requireText(prompt, "prompt");
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
