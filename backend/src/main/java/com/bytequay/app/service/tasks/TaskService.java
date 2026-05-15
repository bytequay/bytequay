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
package com.bytequay.app.service.tasks;

import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskKind;
import com.bytequay.app.domain.TaskMessage;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * Top-level facade controllers call into. Hides the registry / store
 * split: every endpoint either persists a row, mutates a session, or
 * subscribes to events — TaskService offers exactly those verbs.
 */
@Service
public class TaskService
{
    private final TaskStore store;
    private final TaskSessionRegistry registry;

    public TaskService(TaskStore store, TaskSessionRegistry registry)
    {
        this.store = requireNonNull(store, "store is null");
        this.registry = requireNonNull(registry, "registry is null");
    }

    public List<Task> listByStatus(TaskStatus status, int limit)
    {
        return store.listTasksByStatus(status, limit);
    }

    public Task create(NewTaskRequest request)
    {
        requireNonNull(request, "request is null");
        Instant now = Instant.now();
        Task task = new Task(
                UUID.randomUUID().toString(),
                request.kind(),
                request.provider(),
                /* agentSessionId */ null,
                request.title(),
                TaskStatus.PENDING,
                request.workingDir(),
                request.branchName(),
                request.model(),
                /* costUsdMilli */ 0L,
                /* tokensIn */ 0L,
                /* tokensOut */ 0L,
                /* processPid */ null,
                /* logPath */ null,
                now,
                now,
                /* endedAt */ null,
                /* errorMessage */ null,
                request.metadataJson() == null ? "{}" : request.metadataJson());
        store.saveTask(task);
        // Spin up the session synchronously so the first send() call
        // inside this request can dispatch on it.
        AgentSession session = registry.getOrCreate(task);
        if (request.initialPrompt() != null && !request.initialPrompt().isBlank()) {
            session.send(request.initialPrompt());
        }
        return store.findTaskById(task.id()).orElse(task);
    }

    public Optional<Task> find(String taskId)
    {
        return store.findTaskById(taskId);
    }

    public List<TaskMessage> history(String taskId)
    {
        return store.listMessages(taskId);
    }

    /** Send a follow-up turn to an existing task. Re-creates the
     *  in-memory session if it was evicted (e.g. after restart). */
    public void send(String taskId, String input)
    {
        sessionOrThrow(taskId).send(input);
    }

    public void interrupt(String taskId)
    {
        sessionOrThrow(taskId).interrupt();
    }

    public void pause(String taskId)
    {
        sessionOrThrow(taskId).pause();
    }

    public void resume(String taskId)
    {
        sessionOrThrow(taskId).resume();
    }

    public void stop(String taskId)
    {
        sessionOrThrow(taskId).stop();
        registry.evict(taskId);
    }

    public void decide(String taskId, String callId, PermissionDecision decision)
    {
        sessionOrThrow(taskId).decide(callId, decision);
    }

    /** Subscribe to live events. The returned {@link Runnable}
     *  unsubscribes — controllers wire it to the SSE
     *  {@code onCompletion}/{@code onTimeout} callbacks. */
    public Runnable subscribe(String taskId, Consumer<StreamEvent> listener)
    {
        return sessionOrThrow(taskId).subscribeToEvents(listener);
    }

    private AgentSession sessionOrThrow(String taskId)
    {
        Task task = store.findTaskById(taskId)
                .orElseThrow(() -> new NoSuchElementException("no task: " + taskId));
        return registry.getOrCreate(task);
    }

    /** Inputs from the create-task dialog. Kept as a record next to
     *  the service so controllers don't have to define a near-identical
     *  request DTO. */
    public record NewTaskRequest(
            TaskKind kind,
            String provider,
            String model,
            String title,
            String workingDir,
            String branchName,
            String initialPrompt,
            String metadataJson) {}
}
