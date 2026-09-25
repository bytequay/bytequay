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
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.CodeGraphService;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.TaskCompletion;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.core.store.TaskLogStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static com.bytequay.agent.core.AgentEventOutboxEntry.pendingLifecycle;
import static com.bytequay.agent.core.impl.AgentEventOutboxDrainer.drainPending;
import static com.bytequay.agent.core.impl.AgentFailures.message;
import static com.bytequay.agent.core.impl.AgentFailures.suppressFailure;
import static java.util.Objects.requireNonNull;

public final class DefaultAgentCompletionService
        implements AgentCompletionService
{
    private static final System.Logger log = System.getLogger(DefaultAgentCompletionService.class.getName());

    private final AgentTaskStore taskStore;
    private final AgentTurnStore turnStore;
    private final QueuedInputStore queueStore;
    private final AgentUnitOfWork unitOfWork;
    private final AgentEventOutboxStore outboxStore;
    private final AgentEventService eventService;
    private final AgentEventCommitter eventCommitter;
    private final AgentFinalResponseReader finalResponseReader;
    private final WorktreeService worktreeService;
    private final AgentResourceService resourceService;
    private final AgentRuntimeRegistry runtimeRegistry;
    private final AgentMetricsService metricsService;
    private final CodeGraphService codeGraph;
    private final Supplier<Duration> retentionPeriod;
    private final Clock clock;

    public DefaultAgentCompletionService(
            AgentTaskStore taskStore,
            AgentTurnStore turnStore,
            QueuedInputStore queueStore,
            AgentUnitOfWork unitOfWork,
            AgentEventOutboxStore outboxStore,
            AgentEventService eventService,
            TaskLogStore logStore,
            WorktreeService worktreeService,
            AgentResourceService resourceService,
            AgentRuntimeRegistry runtimeRegistry,
            AgentMetricsService metricsService,
            CodeGraphService codeGraph,
            Supplier<Duration> retentionPeriod,
            Clock clock)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.queueStore = requireNonNull(queueStore, "queueStore is null");
        this.unitOfWork = requireNonNull(unitOfWork, "unitOfWork is null");
        this.outboxStore = requireNonNull(outboxStore, "outboxStore is null");
        this.eventService = requireNonNull(eventService, "eventService is null");
        this.eventCommitter = new AgentEventCommitter(unitOfWork, outboxStore, eventService);
        this.finalResponseReader = new AgentFinalResponseReader(logStore);
        this.worktreeService = requireNonNull(worktreeService, "worktreeService is null");
        this.resourceService = requireNonNull(resourceService, "resourceService is null");
        this.runtimeRegistry = requireNonNull(runtimeRegistry, "runtimeRegistry is null");
        this.metricsService = requireNonNull(metricsService, "metricsService is null");
        this.codeGraph = requireNonNull(codeGraph, "codeGraph is null");
        this.retentionPeriod = requireNonNull(retentionPeriod, "retentionPeriod is null");
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
            requireNoDevelopmentContinuation(task);
            runtimeRegistry.checkBranchAdmission(task);
            turnStore.findActive(task.id()).ifPresent(turn -> {
                throw new AgentConflictException("Task has an active turn: " + turn.id());
            });
            task.worktreeMutationClaim().ifPresent(owner -> {
                throw new AgentConflictException(owner.conflictMessage());
            });
            List<QueuedAgentInput> queue = queueStore.listQueued(task.id());
            queue.stream().filter(input -> input.state().live()).findFirst().ifPresent(input -> {
                throw new AgentConflictException("Task still has queued input: " + input.id());
            });
            Instant claimedAt = Instant.now(clock);
            WorktreeMutationClaim claim = new WorktreeMutationClaim(WorktreeMutationClaim.Kind.COMPLETION, UUID.randomUUID().toString(), claimedAt);
            return unitOfWork.execute(() -> {
                // Completing is the user declining to resend what may never have arrived. Recorded
                // as abandoned, not removed: nothing here knows the provider did not run it.
                queue.stream()
                        .filter(input -> input.state() == QueuedAgentInput.State.UNCERTAIN)
                        .forEach(input -> queueStore.update(input.abandoned("Task completed without resending it", claimedAt)));
                return taskStore.update(task.claimWorktreeMutation(claim, claimedAt));
            });
        }
    }

    private TaskSummary summarize(AgentTask task, Optional<String> notes)
    {
        AgentMetrics metrics = metricsService.taskTotals(task.id());
        AgentFinalResponseReader.FinalResponse finalResponse = finalResponseReader.read(task.id());
        WorktreeSnapshot worktree = worktreeService.inspect(task.taskWorktree()
                .orElseThrow(() -> new AgentConflictException("Task has no managed worktree: " + task.id())));
        // Ignored build output is no unfinished work; cleanup, which would delete it, still refuses it.
        if (worktree.uncommitted() || worktree.gitOperationInProgress()) {
            throw new AgentConflictException("Task worktree is not clean: " + task.id());
        }
        return new TaskSummary(
                finalResponse.response(),
                Optional.of(worktree),
                metrics,
                notes,
                finalResponse.children());
    }

    private AgentTask persistCompletion(TaskId taskId, WorktreeMutationClaim claim, TaskSummary summary)
    {
        Instant completedAt = Instant.now(clock);
        Instant deleteAfter = completedAt.plus(retentionPeriod.get());
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
            requireAbandonable(taskId);
        }
        // Closing the session stops the process this backend runs for the task and returns only
        // once that process tree is verified stopped. It runs outside the task lock because the
        // provider's own callbacks take that lock to record what the close interrupted.
        runtimeRegistry.closeSession(taskId);
        synchronized (runtimeRegistry.taskLock(taskId)) {
            requireAbandonable(taskId);
            Instant failedAt = Instant.now(clock);
            Duration retention = retentionPeriod.get();
            return eventCommitter.commit(
                    pendingLifecycle(EventId.random(), taskId, Optional.empty(), AgentEventPayload.LifecyclePhase.TASK_FAILED, reason, failedAt),
                    () -> {
                        AgentTask current = requireTask(taskId);
                        settleQueueForFailedTask(taskId, failedAt);
                        // The worktree is kept, not cleaned: the reason this task is stuck is that
                        // something may still have been writing to it, and that evidence is the point.
                        return taskStore.update(current.worktreeMutationClaim()
                                .map(claim -> current.fail(reason, failedAt, failedAt.plus(retention), claim))
                                .orElseGet(() -> current.fail(reason, failedAt, failedAt.plus(retention))));
                    });
        }
    }

    /**
     * A terminal task cannot retain work that still looks resumable. An input mid-claim is first
     * settled by what its turn proves, and then discarded the way its settled state is discarded.
     */
    private void settleQueueForFailedTask(TaskId taskId, Instant failedAt)
    {
        for (QueuedAgentInput input : queueStore.listQueued(taskId)) {
            QueuedAgentInput settled = input.state() == QueuedAgentInput.State.STARTING
                    ? queueStore.update(input.settledBy(
                            turnStore.find(input.turnId().orElseThrow()).orElseThrow(),
                            "Task was marked failed before the input was sent",
                            "Task was marked failed while input delivery was unresolved",
                            failedAt))
                    : input;
            switch (settled.state()) {
                case QUEUED, PAUSED -> queueStore.update(settled.removed(failedAt));
                case UNCERTAIN -> queueStore.update(settled.abandoned("Task was marked failed without resending it", failedAt));
                case STARTING, STARTED, REMOVED, ABANDONED -> {}
            }
        }
    }

    /**
     * A task can be given up on only once nothing of its own is still running.
     *
     * <p>A live turn is refused rather than interrupted here, because stopping it is a separate
     * user action with its own confirmation; failing the task underneath a turn would also leave
     * that turn's completion unable to record itself.
     */
    private void requireAbandonable(TaskId taskId)
    {
        AgentTask task = requireTask(taskId);
        if (task.state().terminal()) {
            throw new AgentConflictException("Task is already terminal: " + taskId);
        }
        if (runtimeRegistry.hasDevelopmentOwner(taskId)
                || task.developmentContinuationUnfinished() && task.worktreeMutationClaim().isPresent()) {
            throw new AgentConflictException("Development automation must stop its writers before marking the task failed: " + taskId);
        }
        turnStore.findActive(taskId).ifPresent(turn -> {
            throw new AgentConflictException("Task has an active turn; stop it before marking the task failed: " + turn.id());
        });
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
    public void cleanup(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        synchronized (runtimeRegistry.taskLock(taskId)) {
            AgentTask task = requireTask(taskId);
            if (!task.state().terminal()) {
                throw new AgentConflictException("Cannot clean up a live task: " + taskId);
            }
            if (task.cleanupState() == CleanupState.DELETED) {
                return;
            }
            if (task.deleteAfter().orElseThrow().isAfter(Instant.now(clock))) {
                throw new AgentConflictException("Task retention deadline has not passed: " + taskId);
            }
            try {
                drainPending(outboxStore, eventService, taskId);
                task = removeWorktree(task);
                resourceService.delete(taskId);
            }
            catch (RuntimeException failure) {
                taskStore.update(task.withCleanupState(CleanupState.RETRY_PENDING, Optional.of(message(failure)), Instant.now(clock)));
                log.log(System.Logger.Level.WARNING, "Agent runtime cleanup remains pending for task " + taskId + ": " + message(failure));
                return;
            }
            taskStore.update(task.withCleanupState(CleanupState.DELETED, Optional.empty(), Instant.now(clock)));
        }
    }

    @Override
    public void delete(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        synchronized (runtimeRegistry.taskLock(taskId)) {
            if (!deletable(taskId)) {
                return;
            }
        }
        // As for abandon: the provider's own callbacks take the task lock while the close runs.
        runtimeRegistry.closeSession(taskId);
        synchronized (runtimeRegistry.taskLock(taskId)) {
            if (!deletable(taskId)) {
                return;
            }
            AgentTask task = requireTask(taskId);
            TaskResources resources = resourceService.pathsForDeletion(taskId);
            task.taskWorktree().ifPresent(worktreeService::delete);
            // Wait for an in-flight graph preparation before removing its runtime files.
            codeGraph.deleteIndex(resources);
            resourceService.delete(taskId);
            unitOfWork.execute(() -> {
                taskStore.delete(taskId);
                return null;
            });
        }
    }

    /** False once there is nothing left to delete; refuses a task whose work may still be moving. */
    private boolean deletable(TaskId taskId)
    {
        Optional<AgentTask> found = taskStore.find(taskId);
        if (found.isEmpty()) {
            return false;
        }
        requireNoDevelopmentContinuation(found.orElseThrow());
        runtimeRegistry.checkBranchAdmission(found.orElseThrow());
        turnStore.findActive(taskId).ifPresent(turn -> {
            throw new AgentConflictException("Task has an active turn; stop it before deleting: " + turn.id());
        });
        // A recovery claim is not a writer: it holds a worktree whose launch could not be shown
        // stopped, and a task wearing one is otherwise stuck for good. Deleting closes the session
        // and forces the checkout out, which is what the claim is waiting for.
        found.orElseThrow().worktreeMutationClaim()
                .filter(claim -> claim.kind() != WorktreeMutationClaim.Kind.RECOVERY)
                .ifPresent(claim -> {
                    throw new AgentConflictException("Task worktree is claimed by " + claim.kind() + "; it cannot be deleted yet");
                });
        return true;
    }

    private void requireNoDevelopmentContinuation(AgentTask task)
    {
        if (task.developmentContinuationUnfinished() || runtimeRegistry.hasDevelopmentOwner(task.id())) {
            throw new AgentConflictException("Development automation is running; stop it before completing or deleting the task: " + task.id());
        }
    }

    private AgentTask removeWorktree(AgentTask task)
    {
        if (task.worktreeRemovedAt().isPresent()) {
            return task;
        }
        task.taskWorktree().ifPresent(worktreeService::cleanup);
        resourceService.deleteWorktreeCache(task.id());
        // Only after the worktree is gone: a refused cleanup keeps the index for the retry, and retention covers the rest.
        task.taskWorktree().ifPresent(ignored -> codeGraph.deleteIndex(resourceService.require(task.id())));
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
