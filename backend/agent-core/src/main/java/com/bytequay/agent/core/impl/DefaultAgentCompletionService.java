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
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.CleanupResult;
import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.TaskCompletion;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskMetrics;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.core.store.TaskLogStore;
import com.google.common.collect.ImmutableList;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.agent.core.AgentEventOutboxEntry.pendingLifecycle;
import static com.bytequay.agent.core.impl.AgentEventOutboxDrainer.drainPending;
import static com.bytequay.agent.core.impl.AgentFailures.message;
import static com.bytequay.agent.core.impl.AgentFailures.suppressFailure;
import static java.util.Objects.requireNonNull;

public final class DefaultAgentCompletionService
        implements AgentCompletionService
{
    static final Duration RETENTION_PERIOD = Duration.ofDays(7);

    private final AgentTaskStore taskStore;
    private final AgentTurnStore turnStore;
    private final AgentUnitOfWork unitOfWork;
    private final AgentEventOutboxStore outboxStore;
    private final AgentEventService eventService;
    private final AgentFinalResponseReader finalResponseReader;
    private final WorktreeService worktreeService;
    private final AgentResourceService resourceService;
    private final AgentRuntimeRegistry runtimeRegistry;
    private final AgentMetricsService metricsService;
    private final Clock clock;

    public DefaultAgentCompletionService(
            AgentTaskStore taskStore,
            AgentTurnStore turnStore,
            AgentUnitOfWork unitOfWork,
            AgentEventOutboxStore outboxStore,
            AgentEventService eventService,
            TaskLogStore logStore,
            WorktreeService worktreeService,
            AgentResourceService resourceService,
            AgentRuntimeRegistry runtimeRegistry,
            AgentMetricsService metricsService,
            Clock clock)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.unitOfWork = requireNonNull(unitOfWork, "unitOfWork is null");
        this.outboxStore = requireNonNull(outboxStore, "outboxStore is null");
        this.eventService = requireNonNull(eventService, "eventService is null");
        this.finalResponseReader = new AgentFinalResponseReader(logStore);
        this.worktreeService = requireNonNull(worktreeService, "worktreeService is null");
        this.resourceService = requireNonNull(resourceService, "resourceService is null");
        this.runtimeRegistry = requireNonNull(runtimeRegistry, "runtimeRegistry is null");
        this.metricsService = requireNonNull(metricsService, "metricsService is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public TaskCompletion complete(CompleteAgentTaskCommand command)
    {
        requireNonNull(command, "command is null");
        drainPending(outboxStore, eventService, command.taskId());
        AgentTask task = claimCompletion(command.taskId());
        if (task.state() == TaskState.COMPLETED) {
            return completion(task);
        }
        WorktreeMutationClaim claim = task.worktreeMutationClaim().orElseThrow();

        try {
            runtimeRegistry.closeSession(task.id());
            TaskSummary summary = summarize(task, command.notes());
            AgentTask completed = persistCompletion(task.id(), claim, summary);
            drainPending(outboxStore, eventService, task.id());
            return completion(completed);
        }
        catch (RuntimeException failure) {
            releaseCompletionClaim(command.taskId(), claim, failure);
            throw failure;
        }
    }

    private AgentTask claimCompletion(TaskId taskId)
    {
        synchronized (runtimeRegistry.taskLock(taskId)) {
            AgentTask task = requireTask(taskId);
            if (task.state() == TaskState.COMPLETED) {
                return task;
            }
            if (task.state() != TaskState.OPEN) {
                throw new AgentConflictException("Task cannot complete in state " + task.state());
            }
            turnStore.findActive(task.id()).ifPresent(turn -> {
                throw new AgentConflictException("Task has an active turn: " + turn.id());
            });
            task.worktreeMutationClaim().ifPresent(owner -> {
                throw new AgentConflictException("Task worktree is claimed by " + owner.kind());
            });
            Instant claimedAt = Instant.now(clock);
            WorktreeMutationClaim claim = new WorktreeMutationClaim(WorktreeMutationClaim.Kind.COMPLETION, UUID.randomUUID().toString(), claimedAt);
            return unitOfWork.execute(() -> taskStore.update(task.claimWorktreeMutation(claim, claimedAt)));
        }
    }

    private TaskSummary summarize(AgentTask task, Optional<String> notes)
    {
        TaskMetrics metrics = metricsService.taskTotals(task.id());
        String finalResponse = finalResponseReader.read(task.id());
        WorktreeSnapshot worktree = worktreeService.inspect(task.taskWorktree()
                .orElseThrow(() -> new AgentConflictException("Task has no managed worktree: " + task.id())));
        if (worktree.dirty() || worktree.gitOperationInProgress()) {
            throw new AgentConflictException("Task worktree is not clean: " + task.id());
        }
        return new TaskSummary(
                finalResponse,
                Optional.of(worktree),
                ImmutableList.of(),
                metrics,
                ImmutableList.of(),
                notes);
    }

    private AgentTask persistCompletion(TaskId taskId, WorktreeMutationClaim claim, TaskSummary summary)
    {
        Instant completedAt = Instant.now(clock);
        Instant deleteAfter = completedAt.plus(RETENTION_PERIOD);
        EventId eventId = EventId.random();
        return unitOfWork.execute(() -> {
            AgentTask current = requireTask(taskId);
            AgentTask terminal = taskStore.update(current.complete(summary, completedAt, deleteAfter, claim));
            outboxStore.insert(pendingLifecycle(
                    eventId,
                    terminal.id(),
                    Optional.empty(),
                    AgentEventPayload.LifecyclePhase.TASK_COMPLETED,
                    "Task completed",
                    completedAt));
            return terminal;
        });
    }

    @Override
    public AgentTask abandon(TaskId taskId, String reason)
    {
        requireNonNull(taskId, "taskId is null");
        if (reason == null || reason.isBlank()) {
            throw new InvalidAgentRequestException("reason must not be blank");
        }
        synchronized (runtimeRegistry.taskLock(taskId)) {
            AgentTask task = requireTask(taskId);
            if (task.state().terminal()) {
                throw new AgentConflictException("Task is already terminal: " + taskId);
            }
            runtimeRegistry.closeSession(taskId);
            Instant failedAt = Instant.now(clock);
            EventId eventId = EventId.random();
            AgentTask failed = unitOfWork.execute(() -> {
                AgentTask current = requireTask(taskId);
                // The worktree is kept, not cleaned: the reason this task is stuck is that
                // something may still have been writing to it, and that evidence is the point.
                AgentTask terminal = taskStore.update(current.worktreeMutationClaim()
                        .map(claim -> current.fail(
                                reason,
                                failedAt,
                                failedAt.plus(RETENTION_PERIOD),
                                claim))
                        .orElseGet(() -> current.fail(reason, failedAt, failedAt.plus(RETENTION_PERIOD))));
                outboxStore.insert(pendingLifecycle(
                        eventId,
                        taskId,
                        Optional.empty(),
                        AgentEventPayload.LifecyclePhase.TASK_FAILED,
                        reason,
                        failedAt));
                return terminal;
            });
            eventService.drain(eventId);
            return failed;
        }
    }

    private void releaseCompletionClaim(TaskId taskId, WorktreeMutationClaim claim, RuntimeException failure)
    {
        synchronized (runtimeRegistry.taskLock(taskId)) {
            suppressFailure(failure, () -> unitOfWork.execute(() -> {
                AgentTask task = requireTask(taskId);
                if (!task.state().terminal() && task.worktreeMutationClaim().equals(Optional.of(claim))) {
                    taskStore.update(task.releaseWorktreeMutation(claim, Instant.now(clock)));
                }
                return null;
            }));
        }
    }

    @Override
    public void cleanupWorktree(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        synchronized (runtimeRegistry.taskLock(taskId)) {
            AgentTask task = requireTask(taskId);
            if (!task.state().terminal()) {
                throw new AgentConflictException("Cannot clean up a live task worktree: " + taskId);
            }
            try {
                drainPending(outboxStore, eventService, taskId);
                removeWorktree(task);
            }
            catch (RuntimeException failure) {
                recordFailedWorktreeCleanup(taskId, failure);
                throw failure;
            }
        }
    }

    private void recordFailedWorktreeCleanup(TaskId taskId, RuntimeException failure)
    {
        suppressFailure(failure, () -> {
            AgentTask current = requireTask(taskId);
            if (current.worktreeRemovedAt().isEmpty()) {
                taskStore.update(current.withWorktreeCleanupAttemptAt(Instant.now(clock)));
            }
        });
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
                drainPending(outboxStore, eventService, taskId);
                task = removeWorktree(task);
                resourceService.delete(taskId);
            }
            catch (RuntimeException failure) {
                AgentTask retry = taskStore.update(task.withCleanupState(
                        CleanupState.RETRY_PENDING,
                        Optional.of(message(failure)),
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

    private AgentTask removeWorktree(AgentTask task)
    {
        if (task.worktreeRemovedAt().isPresent()) {
            return task;
        }
        task.taskWorktree().ifPresent(worktreeService::cleanup);
        return taskStore.update(task.withWorktreeRemovedAt(Instant.now(clock)));
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
