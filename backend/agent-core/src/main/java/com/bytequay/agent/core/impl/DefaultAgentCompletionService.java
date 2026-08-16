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
package com.bytequay.agent.core.impl;

import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentWorkspaceService;
import com.bytequay.agent.core.CleanupResult;
import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.TaskCompletion;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskMetrics;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.WorkspaceSnapshot;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.google.common.collect.ImmutableList;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public final class DefaultAgentCompletionService
        implements AgentCompletionService
{
    static final Duration RETENTION_PERIOD = Duration.ofDays(7);

    private static final int EVENT_PAGE_SIZE = 1000;

    private final AgentTaskStore taskStore;
    private final AgentTurnStore turnStore;
    private final TaskLogStore logStore;
    private final AgentWorkspaceService workspaceService;
    private final AgentResourceService resourceService;
    private final AgentRuntimeRegistry runtimeRegistry;
    private final AgentMetricsService metricsService;
    private final Clock clock;

    public DefaultAgentCompletionService(
            AgentTaskStore taskStore,
            AgentTurnStore turnStore,
            TaskLogStore logStore,
            AgentWorkspaceService workspaceService,
            AgentResourceService resourceService,
            AgentRuntimeRegistry runtimeRegistry,
            AgentMetricsService metricsService,
            Clock clock)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.logStore = requireNonNull(logStore, "logStore is null");
        this.workspaceService = requireNonNull(workspaceService, "workspaceService is null");
        this.resourceService = requireNonNull(resourceService, "resourceService is null");
        this.runtimeRegistry = requireNonNull(runtimeRegistry, "runtimeRegistry is null");
        this.metricsService = requireNonNull(metricsService, "metricsService is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public TaskCompletion complete(CompleteAgentTaskCommand command)
    {
        requireNonNull(command, "command is null");
        synchronized (runtimeRegistry.taskLock(command.taskId())) {
            AgentTask task = requireTask(command.taskId());
            if (task.state() == TaskState.COMPLETED) {
                workspaceService.release(task.id());
                return completion(task);
            }
            if (task.state() != TaskState.ACTIVE) {
                throw new AgentConflictException("Task cannot complete in state " + task.state());
            }
            turnStore.findActive(task.id()).ifPresent(turn -> {
                throw new AgentConflictException("Task has an active turn: " + turn.id());
            });

            runtimeRegistry.closeSession(task.id());
            TaskMetrics metrics = metricsService.taskTotals(task.id());
            String finalResponse = finalResponse(task.id());
            WorkspaceSnapshot workspace = workspaceService.inspect(task.worktree());
            Instant completedAt = Instant.now(clock);
            TaskSummary summary = new TaskSummary(
                    finalResponse,
                    workspace,
                    ImmutableList.of(),
                    metrics,
                    ImmutableList.of(),
                    command.notes());
            Instant deleteAfter = completedAt.plus(RETENTION_PERIOD);
            AgentTask completed = taskStore.update(task.beginCompletion(completedAt)
                    .complete(summary, completedAt, deleteAfter));
            workspaceService.release(task.id());
            return completion(completed);
        }
    }

    @Override
    public CleanupResult cleanup(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        synchronized (runtimeRegistry.taskLock(taskId)) {
            AgentTask task = requireTask(taskId);
            if (!task.state().terminal()) {
                throw new AgentConflictException("Cannot clean up a live task: " + taskId);
            }
            if (task.cleanupState() == CleanupState.DELETED) {
                return new CleanupResult(taskId, CleanupState.DELETED, Optional.of(task.updatedAt()), Optional.empty());
            }

            Instant now = Instant.now(clock);
            if (task.deleteAfter().orElseThrow().isAfter(now)) {
                throw new AgentConflictException("Task retention deadline has not passed: " + taskId);
            }
            if (task.cleanupState() == CleanupState.RETAINED) {
                task = taskStore.update(task.withCleanupState(CleanupState.DELETE_DUE, Optional.empty(), now));
            }
            try {
                resourceService.delete(taskId);
            }
            catch (RuntimeException failure) {
                String message = failure.getMessage() == null || failure.getMessage().isBlank()
                        ? failure.getClass().getSimpleName()
                        : failure.getMessage();
                AgentTask retry = taskStore.update(task.withCleanupState(
                        CleanupState.RETRY_PENDING,
                        Optional.of(message),
                        Instant.now(clock)));
                return new CleanupResult(taskId, retry.cleanupState(), Optional.empty(), retry.cleanupFailure());
            }
            AgentTask deleted = taskStore.update(
                    task.withCleanupState(CleanupState.DELETED, Optional.empty(), Instant.now(clock)));
            return new CleanupResult(
                    taskId,
                    CleanupState.DELETED,
                    Optional.of(deleted.updatedAt()),
                    Optional.empty());
        }
    }

    private String finalResponse(TaskId taskId)
    {
        String response = "";
        long afterSequence = 0;
        while (true) {
            List<AgentEventEnvelope> page = logStore.readAfter(taskId, afterSequence, EVENT_PAGE_SIZE);
            for (AgentEventEnvelope envelope : page) {
                if (envelope.event() instanceof ProviderAgentEvent.Message message
                        && message.phase() == ProviderAgentEvent.MessagePhase.FINAL) {
                    response = message.text();
                }
            }
            if (page.size() < EVENT_PAGE_SIZE) {
                return response;
            }
            afterSequence = page.getLast().sequence();
        }
    }

    private AgentTask requireTask(TaskId taskId)
    {
        return taskStore.find(taskId).orElseThrow(() -> new AgentTaskNotFoundException(taskId));
    }

    private static TaskCompletion completion(AgentTask task)
    {
        return new TaskCompletion(
                task.id(),
                task.summary().orElseThrow(),
                task.terminalAt().orElseThrow(),
                task.deleteAfter().orElseThrow());
    }
}
