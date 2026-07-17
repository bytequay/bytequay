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
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.ThreadStore;
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
    private final ThreadTurnScheduler scheduler;
    private final ThreadRegistry registry;

    public SessionControlService(
            AgentRunService runs,
            ThreadStore threads,
            ThreadTurnScheduler scheduler,
            ThreadRegistry registry)
    {
        this.runs = requireNonNull(runs, "runs is null");
        this.threads = requireNonNull(threads, "threads is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.registry = requireNonNull(registry, "registry is null");
    }

    public AgentRun pause(String runId)
    {
        AgentRun run = requireRun(runId);
        scheduler.cancelSessionTurns(run.id());
        activeAgent(run).ifPresent(ThreadAgent::interrupt);
        return runs.pause(run.id(), "paused by user");
    }

    public AgentRun stop(String runId)
    {
        AgentRun run = requireRun(runId);
        scheduler.cancelSessionTurns(run.id());
        activeAgent(run).ifPresent(ThreadAgent::stop);
        return runs.transition(run.id(), AgentRun.STATUS_CANCELLED, "stopped by user");
    }

    public AgentRun resume(String runId)
    {
        validateReplay(requireRun(runId));
        AgentRun run = runs.resume(runId);
        enqueue(run);
        return run;
    }

    public AgentRun restart(String runId)
    {
        validateReplay(requireRun(runId));
        AgentRun restarted = runs.restart(runId);
        enqueue(restarted);
        return restarted;
    }

    private void enqueue(AgentRun run)
    {
        Thread thread = validateReplay(run);
        if (run.taskId() == null) {
            scheduler.enqueueTrunkTurn(thread, run.launchInput(), run.id());
            return;
        }
        scheduler.enqueueTaskTurn(
                thread,
                run.launchInput(),
                run.taskId(),
                run.stageId(),
                TurnInitiator.user(),
                run.id());
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

    private Optional<ThreadAgent> activeAgent(AgentRun run)
    {
        if (run.stageId() != null && !run.stageId().isBlank()) {
            return registry.findStage(run.stageId());
        }
        if (run.taskId() != null && !run.taskId().isBlank()) {
            return registry.find(run.threadId());
        }
        return registry.findTrunk(run.threadId());
    }

    private AgentRun requireRun(String id)
    {
        return runs.findById(id)
                .orElseThrow(() -> new NoSuchElementException("no session: " + id));
    }
}
