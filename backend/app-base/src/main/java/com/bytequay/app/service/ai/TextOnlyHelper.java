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
package com.bytequay.app.service.ai;

import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.OneShotSession;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.RunningTurn;
import com.bytequay.agent.spi.SessionAccess;
import com.bytequay.app.service.settings.AgentModelSetting;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

/** Runs one tool-free model turn in disposable scratch, with no task row or Git worktree, and returns its final answer. */
@Component
public class TextOnlyHelper
{
    private static final Duration MAXIMUM_RUNTIME = Duration.ofSeconds(45);

    private final AgentDriverRegistry drivers;
    private final AgentResourceService resources;
    private final Map<String, Running> running = new ConcurrentHashMap<>();

    /** A turn answering under a key, which {@link #cancel} stops from another thread whether it started yet or not. */
    private static final class Running
    {
        private RunningTurn turn;
        private boolean cancelled;

        synchronized void started(RunningTurn started)
        {
            turn = started;
            if (cancelled) {
                started.cancel();
            }
        }

        synchronized void cancel()
        {
            cancelled = true;
            if (turn != null) {
                turn.cancel();
            }
        }
    }

    public TextOnlyHelper(AgentDriverRegistry drivers, AgentResourceService resources)
    {
        this.drivers = requireNonNull(drivers, "drivers is null");
        this.resources = requireNonNull(resources, "resources is null");
    }

    /**
     * Returns the model's final answer to {@code prompt}, within the short time a rewrite takes.
     *
     * @param failure what the user is told when the model cannot answer, such as "The selected model could not write the comment"
     */
    public String answer(AgentModelSetting model, String prompt, String managedInstructions, String failure)
    {
        return answer(new AgentLaneId(model.configId()), Optional.of(model.model()), model.effort(), prompt, managedInstructions, failure);
    }

    /** Runs a short text request using the task's selected provider, model and effort. */
    public String answer(AgentLaneId laneId, Optional<String> model, Optional<String> effort, String prompt, String managedInstructions, String failure)
    {
        return answer(laneId, model, effort, prompt, managedInstructions, failure, MAXIMUM_RUNTIME);
    }

    /** Runs a text request using the task's selected provider, model and effort, with a caller-specific runtime limit. */
    public String answer(AgentLaneId laneId, Optional<String> model, Optional<String> effort, String prompt, String managedInstructions, String failure, Duration maximumRuntime)
    {
        return answer(laneId, model, effort, prompt, managedInstructions, failure, maximumRuntime, UUID.randomUUID().toString());
    }

    /**
     * Returns the model's final answer to {@code prompt}, given as long as the answer needs.
     *
     * @param maximumRuntime when the turn stops unanswered
     * @param key what {@link #cancel} names to stop this answer
     */
    public String answer(AgentModelSetting model, String prompt, String managedInstructions, String failure, Duration maximumRuntime, String key)
    {
        return answer(new AgentLaneId(model.configId()), Optional.of(model.model()), model.effort(), prompt, managedInstructions, failure, maximumRuntime, key);
    }

    private String answer(AgentLaneId laneId, Optional<String> model, Optional<String> effort, String prompt, String managedInstructions,
            String failure, Duration maximumRuntime, String key)
    {
        Running current = new Running();
        if (running.putIfAbsent(key, current) != null) {
            throw new IllegalStateException("An answer is already running for " + key);
        }
        try {
            return answer(laneId, model, effort, prompt, managedInstructions, failure, maximumRuntime, current);
        }
        finally {
            running.remove(key, current);
        }
    }

    /** Stops the answer running under the key; it ends cancelled, and its caller is told the model could not answer. */
    public void cancel(String key)
    {
        Running current = running.get(key);
        if (current != null) {
            current.cancel();
        }
    }

    private String answer(AgentLaneId laneId, Optional<String> model, Optional<String> effort, String prompt, String managedInstructions,
            String failure, Duration maximumRuntime, Running current)
    {
        AgentTurnResult result;
        try {
            result = OneShotSession.run(resources, drivers.require(laneId), (taskId, scratch) -> new AgentSessionContext(
                            taskId.toString(), OneShotSession.scratchWorktree(scratch, "text-only-worktree"), scratch.runtimeDirectory(), scratch.providerDirectory(),
                            AgentPermissionPolicy.AUTO, Optional.empty(),
                            (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                            model, effort, SessionAccess.TEXT_ONLY, Optional.empty(), managedInstructions),
                    prompt, maximumRuntime, current::started).result();
        }
        catch (AgentUnavailableException unavailable) {
            throw new AgentUnavailableException(failure + ": " + unavailable.getMessage(), unavailable);
        }
        if (result.state() != AgentTurnResult.State.COMPLETED) {
            throw new AgentUnavailableException(failure + ": " + result.terminalReason().orElse(result.state().name().toLowerCase()));
        }
        return result.finalResponse().orElse("");
    }
}
