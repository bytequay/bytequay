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
package com.bytequay.app.service.runs;

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.TurnLiveness;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.threads.ThreadAgent;
import com.bytequay.app.service.threads.ThreadRegistry;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Exact, run-scoped controls for the public Session API. */
@Service
public class SessionControlService
{
    private final AgentRunService runs;
    private final ThreadStore threads;
    private final ThreadTurnStore turns;
    private final ThreadTurnScheduler scheduler;
    private final ThreadRegistry registry;

    public SessionControlService(
            AgentRunService runs,
            ThreadStore threads,
            ThreadTurnStore turns,
            ThreadTurnScheduler scheduler,
            ThreadRegistry registry)
    {
        this.runs = requireNonNull(runs, "runs is null");
        this.threads = requireNonNull(threads, "threads is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.registry = requireNonNull(registry, "registry is null");
    }

    public AgentRun pause(String runId)
    {
        requireRun(runId);
        throw retired();
    }

    public AgentRun stop(String runId)
    {
        requireRun(runId);
        throw retired();
    }

    public AgentRun resume(String runId)
    {
        requireRun(runId);
        throw retired();
    }

    public AgentRun restart(String runId)
    {
        requireRun(runId);
        throw retired();
    }

    private static UnsupportedOperationException retired()
    {
        return new UnsupportedOperationException(
                "LEGACY AgentRun control is retired; use the typed V2 owner control");
    }

    private void enqueue(AgentRun run, ThreadScope scope)
    {
        Thread thread = validateReplay(run);
        switch (scope) {
            case TRUNK -> scheduler.enqueueTrunkTurn(thread, run.launchInput(), run.id());
            case TASK -> scheduler.enqueueTaskTurn(
                    thread, run.launchInput(), required(run.taskId(), "taskId"),
                    TurnInitiator.user(), run.id(), livenessFor(run));
            case STAGE -> scheduler.enqueueStageTurn(
                    thread, run.launchInput(), required(run.taskId(), "taskId"),
                    required(run.stageId(), "stageId"), TurnInitiator.user(), run.id(),
                    livenessFor(run));
        }
    }

    /** A replayed session's liveness classification comes from the run's
     *  persisted role, never the thread it happens to share. */
    private static TurnLiveness livenessFor(AgentRun run)
    {
        return switch (run.kind()) {
            case AgentRun.KIND_DEV, AgentRun.KIND_CI_FIX -> TurnLiveness.CODE;
            default -> TurnLiveness.NARRATION;
        };
    }

    private Thread validateReplay(AgentRun run)
    {
        if (run.launchInput() == null || run.launchInput().isBlank()) {
            throw new IllegalStateException(
                    "session has no stored launch request: " + run.id());
        }
        if (run.threadId() == null || run.threadId().isBlank()) {
            throw new IllegalStateException(
                    "session has no owning trunk: " + run.id());
        }
        return threads.findThreadById(run.threadId())
                .orElseThrow(() -> new NoSuchElementException(
                        "no trunk for session: " + run.id()));
    }

    private Optional<ThreadAgent> activeAgent(AgentRun run, ThreadScope scope)
    {
        return switch (scope) {
            case TRUNK -> registry.findTrunk(run.threadId());
            case TASK, STAGE -> threads.findThreadById(run.threadId())
                    .filter(thread -> thread.kind() == ThreadKind.BRAIN_AGENT)
                    .flatMap(thread -> registry.findTrunk(thread.id()))
                    .or(() -> registry.findTask(
                            run.threadId(), required(run.taskId(), "taskId")));
        };
    }

    private ThreadScope scopeOf(AgentRun run)
    {
        ThreadTurn turn = turns.listTurnsByAgentRunId(run.id(), 1).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "session has no authoritative turn: " + run.id()));
        return turn.scope();
    }

    private static String required(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("session has no " + name);
        }
        return value;
    }

    private AgentRun requireRun(String id)
    {
        return runs.findById(id)
                .orElseThrow(() -> new NoSuchElementException("no session: " + id));
    }
}
