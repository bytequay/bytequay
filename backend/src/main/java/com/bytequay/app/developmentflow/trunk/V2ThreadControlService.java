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

import com.bytequay.app.developmentflow.execution.ExecutionDispatcher;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
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
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * V2-facing Trunk control adapter. It prepares immutable launch input, then
 * hands ownership to the typed Trunk domain and dispatcher. It never creates
 * a legacy session or scheduler turn.
 */
public final class V2ThreadControlService
{
    private final PlanningBaseTurnRuntime planning;
    private final ThreadTurnProjection projection;
    private final ExecutionDispatcher dispatcher;
    private final ThreadEngineOverrides engines;
    private final RoleRegistry roles;
    private final SessionKnowledgeProvider knowledge;

    public V2ThreadControlService(
            PlanningBaseTurnRuntime planning,
            ThreadTurnProjection projection,
            ExecutionDispatcher dispatcher,
            ThreadEngineOverrides engines,
            RoleRegistry roles,
            SessionKnowledgeProvider knowledge)
    {
        this.planning = requireNonNull(planning, "planning is null");
        this.projection = requireNonNull(projection, "projection is null");
        this.dispatcher = requireNonNull(dispatcher, "dispatcher is null");
        this.engines = requireNonNull(engines, "engines is null");
        this.roles = requireNonNull(roles, "roles is null");
        this.knowledge = requireNonNull(knowledge, "knowledge is null");
    }

    public String send(Thread thread, String input, TurnInitiator initiator)
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
        String commandId = UUID.randomUUID().toString();
        PlanningBaseTurnRuntime.Receipt result = planning.request(
                new PlanningBaseTurnRuntime.Request(
                        commandId,
                        initiator.attended() ? "user" : "system:" + source(initiator),
                        thread.id(), thread.workspaceId(),
                        "TRUNK_CONVERSATION", transport(engine),
                        engine.agentOrProvider(),
                        engine.kind() == WorkModelKind.API ? engine.account() : null,
                        engine.model(), engine.reasoningEffort(), systemPrompt,
                        input, input));
        return result.turnId();
    }

    public List<ThreadMessage> history(String trunkId)
    {
        return projection.history(trunkId);
    }

    public List<ThreadTurn> turns(String trunkId, int limit)
    {
        return projection.turns(trunkId, limit).stream()
                .map(turn -> projectedTurn(trunkId, turn))
                .toList();
    }

    /** V2 exposes typed Turn state directly; legacy scheduler events do not apply. */
    public List<ThreadTurnEvent> turnEvents(String trunkId)
    {
        requireText(trunkId, "trunkId");
        return List.of();
    }

    /** Persist cancellation first, then signal the exact active provider attempt. */
    public void interrupt(String trunkId)
    {
        projection.cancelableTicketIds(trunkId).forEach(dispatcher::requestCancel);
    }

    private static ThreadTurn projectedTurn(
            String trunkId, ThreadTurnProjection.TurnView view)
    {
        Instant updated = view.finishedAt() != null ? view.finishedAt()
                : view.startedAt() != null ? view.startedAt() : view.requestedAt();
        return new ThreadTurn(
                view.turnId(), trunkId, null,
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
}
