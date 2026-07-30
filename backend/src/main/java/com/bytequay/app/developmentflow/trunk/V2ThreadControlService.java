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

import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnEvent;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.service.agents.AgentContextCompiler;
import com.bytequay.app.service.skills.RoleRegistry;
import com.bytequay.app.service.workmodel.SessionAudience;
import com.bytequay.app.service.workmodel.ThreadEngineOverrides;
import com.bytequay.app.service.workspaces.SessionKnowledgeProvider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * V2-facing Trunk control adapter. It prepares immutable launch input, then
 * hands ownership to the typed Trunk domain and dispatcher. It never creates
 * a legacy session or scheduler turn.
 */
public final class V2ThreadControlService
{
    private static final long STREAM_POLL_MS = 100;

    private final PlanningBaseTurnRuntime planning;
    private final ThreadTurnProjection projection;
    private final DispatchTicketControl tickets;
    private final TrunkManager trunks;
    private final V2TrunkPurge purge;
    private final ThreadEngineOverrides engines;
    private final RoleRegistry roles;
    private final SessionKnowledgeProvider knowledge;

    public V2ThreadControlService(
            PlanningBaseTurnRuntime planning,
            ThreadTurnProjection projection,
            DispatchTicketControl tickets,
            TrunkManager trunks,
            V2TrunkPurge purge,
            ThreadEngineOverrides engines,
            RoleRegistry roles,
            SessionKnowledgeProvider knowledge)
    {
        this.planning = requireNonNull(planning, "planning is null");
        this.projection = requireNonNull(projection, "projection is null");
        this.tickets = requireNonNull(tickets, "tickets is null");
        this.trunks = requireNonNull(trunks, "trunks is null");
        this.purge = requireNonNull(purge, "purge is null");
        this.engines = requireNonNull(engines, "engines is null");
        this.roles = requireNonNull(roles, "roles is null");
        this.knowledge = requireNonNull(knowledge, "knowledge is null");
    }

    public String send(Thread thread, String input, TurnInitiator initiator)
    {
        return send(
                thread, input, initiator, UUID.randomUUID().toString(),
                null, null);
    }

    /** Restart-safe attended continuation of one durable typed user wait. */
    public String continueUserWait(
            Thread thread,
            String input,
            String commandId,
            String sourceTurnId,
            String sourceOperationId)
    {
        requireText(commandId, "commandId");
        requireText(sourceTurnId, "sourceTurnId");
        requireText(sourceOperationId, "sourceOperationId");
        return send(
                thread, input, TurnInitiator.attended("typed-user-wait"),
                commandId, sourceTurnId, sourceOperationId);
    }

    private String send(
            Thread thread, String input, TurnInitiator initiator,
            String commandId, String continuationTurnId,
            String continuationOperationId)
    {
        requireNonNull(thread, "thread is null");
        requireText(input, "input");
        requireNonNull(initiator, "initiator is null");

        String audience = SessionAudience.forThread(thread);
        WorkModel engine = engines.forAudience(thread.id(), audience)
                .orElseThrow(() -> new IllegalStateException(
                        "V2 Trunk has no frozen " + audience + " engine: " + thread.id()));
        requireEngine(engine, thread.id());
        String memory = knowledge.renderForThread(
                thread.workspaceId(), thread.id(), audience, thread.title());
        String systemPrompt = AgentContextCompiler.compilePrompt(
                roles.trunkTemplate(), null, memory, List.of()).systemPrompt();
        PlanningBaseTurnRuntime.Receipt result = planning.request(
                new PlanningBaseTurnRuntime.Request(
                        commandId,
                        initiator.attended() ? "user" : "system:" + source(initiator),
                        thread.id(), thread.workspaceId(),
                        "TRUNK_CONVERSATION", transport(engine),
                        engine.agentOrProvider(),
                        engine.kind() == WorkModelKind.API ? engine.account() : null,
                        engine.model(), engine.reasoningEffort(), systemPrompt,
                        input, input, continuationTurnId,
                        continuationOperationId));
        return result.turnId();
    }

    public List<ThreadMessage> history(String trunkId)
    {
        return projection.history(trunkId);
    }

    public List<ThreadTurn> turns(String trunkId, int limit)
    {
        return projection.turns(trunkId, limit).stream()
                .map(V2ThreadControlService::projectedTurn)
                .toList();
    }

    public List<ThreadTurn> activeTurns(int limit)
    {
        return projection.activeTurns(limit).stream()
                .map(V2ThreadControlService::projectedTurn)
                .toList();
    }

    /** V2 exposes deterministic typed Turn/ticket facts, never scheduler events. */
    public List<ThreadTurnEvent> turnEvents(String trunkId)
    {
        return projection.turnEvents(trunkId);
    }

    /** Poll newly committed exact ThreadTurn execution logs for one Trunk. */
    public Runnable subscribe(
            String trunkId, Consumer<StreamEvent> listener)
    {
        requireText(trunkId, "trunkId");
        requireNonNull(listener, "listener is null");
        AtomicBoolean stopped = new AtomicBoolean();
        long cursor = projection.latestLogRow(trunkId);
        java.lang.Thread worker = java.lang.Thread.startVirtualThread(
                () -> stream(trunkId, cursor, listener, stopped));
        return () -> {
            if (stopped.compareAndSet(false, true)) {
                worker.interrupt();
            }
        };
    }

    public Optional<String> deletionBlocker(String trunkId)
    {
        return projection.deletionState(trunkId).blocker();
    }

    /** Archive through the sole Trunk owner before authorizing physical purge. */
    public DeletionPermit prepareDeletion(String trunkId)
    {
        ThreadTurnProjection.DeletionState state = projection.deletionState(trunkId);
        state.blocker().ifPresent(reason -> {
            throw new IllegalStateException(reason);
        });
        if (!"ARCHIVED".equals(state.lifecycle())) {
            trunks.archive(new TrunkManager.Command(
                    "physical-delete/" + trunkId + "/" + state.version(),
                    "user:delete", trunkId, state.version()));
        }
        ThreadTurnProjection.DeletionState archived =
                projection.deletionState(trunkId);
        archived.blocker().ifPresent(reason -> {
            throw new IllegalStateException(reason);
        });
        if (!"ARCHIVED".equals(archived.lifecycle())) {
            throw new IllegalStateException(
                    "V2 Trunk was not archived before deletion: " + trunkId);
        }
        return new DeletionPermit(trunkId, archived.version());
    }

    public void delete(DeletionPermit permit, Runnable deleteRows)
    {
        requireNonNull(permit, "permit is null");
        purge.delete(permit.trunkId(), permit.archivedVersion(), deleteRows);
    }

    /** Persist cancellation first, then signal the exact active provider attempt. */
    public void interrupt(String trunkId)
    {
        interrupt(trunkId, null);
    }

    /** A user-visible Stop always targets one exact Turn, never its siblings. */
    public void interrupt(String trunkId, String requestedTurnId)
    {
        requireText(trunkId, "trunkId");
        if (requestedTurnId != null && requestedTurnId.isBlank()) {
            throw new IllegalArgumentException("turnId is blank");
        }
        String turnId = requestedTurnId != null
                ? requestedTurnId
                : projection.latestCancelableTurnId(trunkId).orElse(null);
        if (turnId == null) {
            return;
        }
        planning.suppressPending(
                trunkId, turnId, "User canceled before provider launch");
        // Resolve after suppression: a concurrent planning launch either lost
        // the Trunk stripe or now maps this same reserved id to its Turn ticket.
        projection.cancelableTicketId(trunkId, turnId)
                .ifPresent(tickets::requestCancel);
    }

    private void stream(
            String trunkId,
            long initialCursor,
            Consumer<StreamEvent> listener,
            AtomicBoolean stopped)
    {
        long cursor = initialCursor;
        try {
            while (!stopped.get()) {
                for (ThreadTurnProjection.LogEvent row :
                        projection.logEventsAfter(trunkId, cursor)) {
                    cursor = row.cursor();
                    row.events().forEach(listener);
                }
                java.lang.Thread.sleep(STREAM_POLL_MS);
            }
        }
        catch (InterruptedException ignored) {
            java.lang.Thread.currentThread().interrupt();
        }
        catch (RuntimeException ignored) {
            // A disconnected subscriber cannot affect durable execution.
        }
    }

    private static ThreadTurn projectedTurn(ThreadTurnProjection.TurnView view)
    {
        Instant updated = view.finishedAt() != null ? view.finishedAt()
                : view.startedAt() != null ? view.startedAt() : view.requestedAt();
        return new ThreadTurn(
                view.turnId(), view.trunkId(), null,
                ThreadResourceLane.valueOf(view.deliveryLane()), status(view.status()),
                view.userMessage(), view.requestedAt(), updated,
                view.startedAt(), view.finishedAt(), view.error(),
                actor(view.actor()), null, ThreadScope.TRUNK, null);
    }

    private static ThreadTurnStatus status(String value)
    {
        return switch (value) {
            case "REQUESTED", "QUEUED" -> ThreadTurnStatus.QUEUED;
            case "CLAIMED", "RUNNING" -> ThreadTurnStatus.RUNNING;
            case "SUCCEEDED" -> ThreadTurnStatus.COMPLETED;
            case "CANCELED" -> ThreadTurnStatus.CANCELLED;
            case "FAILED", "SUPERSEDED" -> ThreadTurnStatus.FAILED;
            default -> throw new IllegalArgumentException("unknown V2 ThreadTurn status: " + value);
        };
    }

    private static TurnInitiator actor(String actor)
    {
        return actor != null && actor.startsWith("system:")
                ? TurnInitiator.unattended(actor.substring("system:".length()))
                : TurnInitiator.user();
    }

    private static AgentTurnProviderSession.Transport transport(WorkModel model)
    {
        return model.kind() == WorkModelKind.CLI
                ? AgentTurnProviderSession.Transport.CLI
                : AgentTurnProviderSession.Transport.API;
    }

    private static void requireEngine(WorkModel engine, String trunkId)
    {
        requireNonNull(engine, "engine is null");
        requireNonNull(engine.kind(), "engine kind is null");
        requireText(engine.agentOrProvider(), "engine provider");
        if (engine.model() == null || engine.model().isBlank()) {
            throw new IllegalStateException(
                    "V2 Trunk frozen engine has no model: " + trunkId);
        }
    }

    private static String source(TurnInitiator initiator)
    {
        return initiator.source() == null || initiator.source().isBlank()
                ? "unattended" : initiator.source();
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    public record DeletionPermit(String trunkId, long archivedVersion)
    {
        public DeletionPermit
        {
            requireText(trunkId, "trunkId");
            if (archivedVersion < 0) {
                throw new IllegalArgumentException("archivedVersion is negative");
            }
        }
    }
}
