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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskRecoveryRequest;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.ValidationClaim;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.bytequay.app.service.checks.ValidationExecutorRegistry;
import com.bytequay.app.service.stage.PlanStageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * The one concrete owner of stopped-task runtime teardown. A task that
 * durably stopped (PAUSED, NEEDS_ATTENTION, ARCHIVED, or terminal) may
 * still have queued/running turns, a cached CLI agent, or a live
 * validation executor behind it; this reconciler cancels/evicts them
 * idempotently — from the stop command's after-commit callback, from a
 * periodic sweep, and from a startup pass that runs before queued-turn
 * recovery can redispatch a stopped task's rows.
 *
 * <p>It is also the resume barrier: a pending resume request leaves the
 * task PAUSED until this reconciler proves every pre-pause turn
 * terminal, no cached agent, and no live task-owned validation
 * lease/executor — only then does it invoke the completion command.
 */
@Component
public class TaskRuntimeStopReconciler
{
    private static final Logger log = LoggerFactory.getLogger(TaskRuntimeStopReconciler.class);

    private static final Set<TaskStatus> STOPPED = EnumSet.of(
            TaskStatus.PAUSED, TaskStatus.NEEDS_ATTENTION, TaskStatus.ARCHIVED,
            TaskStatus.COMPLETED, TaskStatus.REMOTE_CLOSED, TaskStatus.CANCELED);

    /** ERRORED keeps its noncurrent QUEUED followers frozen for retry, and
     *  terminal teardown already ran in the terminal command's wake — the
     *  sweep only revisits the stops that can hold a pending barrier. */
    private static final Set<TaskStatus> SWEEP_STATUSES = EnumSet.of(
            TaskStatus.PAUSED, TaskStatus.NEEDS_ATTENTION, TaskStatus.ARCHIVED);

    private static final Duration VALIDATION_CANCEL_DEADLINE = Duration.ofMinutes(2);
    private static final int SWEEP_PAGE = 200;

    private final TaskStore taskStore;
    private final ThreadTurnStore turnStore;
    private final ThreadRegistry registry;
    private final ThreadTurnScheduler scheduler;
    private final ValidationPassStore validationStore;
    private final ValidationExecutorRegistry executorRegistry;
    // Provider breaks the construction cycle: TaskService owns the resume
    // completion command's choreography and also calls back into this
    // reconciler from its pause teardown callback.
    private final ObjectProvider<TaskService> taskService;
    private final ObjectProvider<PlanStageService> planStages;

    public TaskRuntimeStopReconciler(
            TaskStore taskStore,
            ThreadTurnStore turnStore,
            ThreadRegistry registry,
            ThreadTurnScheduler scheduler,
            ValidationPassStore validationStore,
            ValidationExecutorRegistry executorRegistry,
            ObjectProvider<TaskService> taskService,
            ObjectProvider<PlanStageService> planStages)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.validationStore = requireNonNull(validationStore, "validationStore is null");
        this.executorRegistry = requireNonNull(executorRegistry, "executorRegistry is null");
        this.taskService = requireNonNull(taskService, "taskService is null");
        this.planStages = requireNonNull(planStages, "planStages is null");
    }

    /**
     * Idempotent teardown for one stopped task: cancel its persisted
     * QUEUED/RUNNING turns, interrupt + evict its cached Task agent,
     * and durably request cancellation of its live validation claims.
     * No-op when the task is not stopped (a late callback for a task
     * that already resumed must not kill the new runtime).
     */
    public void reconcileStoppedTask(String taskId)
    {
        if (taskStore.isV2Task(taskId)) {
            return;
        }
        TaskPhaseMachine.withTaskLock(taskId, () -> {
            Task task = taskStore.findTaskById(taskId).orElse(null);
            if (task == null || !STOPPED.contains(task.status())) {
                return null;
            }
            try {
                scheduler.cancelTaskTurns(taskId);
            }
            catch (RuntimeException e) {
                log.warn("cancelling durable turns of stopped task {} threw: {}",
                        taskId, e.getMessage());
            }
            try {
                registry.findTaskAgents(List.of(taskId)).forEach(ThreadAgent::interrupt);
            }
            finally {
                registry.evictTaskAgent(task.threadId(), taskId);
            }
            requestValidationCancellation(taskId);
            return null;
        });
        // Validators are cooperative: the reconciler never blocks on one
        // here — the barrier check below simply stays false until the
        // executor/lease is provably gone.
    }

    /**
     * The stop barrier: true only when no pre-stop QUEUED/RUNNING turn,
     * no cached Task agent, and no live task-owned validation
     * lease/executor remains. A cancellation-requested validator is
     * still live until this proof; lease expiry alone is insufficient
     * while its executor is in flight.
     */
    public boolean runtimeStopped(String taskId)
    {
        if (taskStore.isV2Task(taskId)) {
            return false;
        }
        if (!turnStore.listTurnsByExactTaskIdAndStatus(taskId, ThreadTurnStatus.QUEUED, 1).isEmpty()
                || !turnStore.listTurnsByExactTaskIdAndStatus(taskId, ThreadTurnStatus.RUNNING, 1).isEmpty()) {
            return false;
        }
        if (!registry.findTaskAgents(List.of(taskId)).isEmpty()) {
            return false;
        }
        Instant now = Instant.now();
        for (ValidationClaim claim : validationStore.findOpenByTask(taskId)) {
            if (executorRegistry.isInFlight(claim.claimKey())) {
                return false;
            }
            if (claim.leaseUntil() != null && claim.leaseUntil().isAfter(now)) {
                return false;
            }
        }
        return true;
    }

    /** Startup pass: tear down every stopped task's leftover runtime
     *  before queued-turn recovery can redispatch it. Recovery completion
     *  is a later ordered pass: it may enqueue fresh work, which must not
     *  be mistaken for an orphan by the scheduler's startup scan. */
    public void reconcileOnStartup()
    {
        forEachStoppedTask(task -> reconcileStoppedTask(task.id()));
    }

    /** Complete durable resume/recovery requests only after the scheduler
     *  has recovered pre-restart RUNNING/QUEUED turns at order 20. */
    public void completePendingRequestsOnStartup()
    {
        forEachStoppedTask(task -> {
            completePendingResume(task);
            completePendingRecovery(task);
        });
    }

    /** Park and terminal transitions publish teardown work: run it after
     *  the stop committed (immediately when there is no transaction), so
     *  a rolled-back park cannot kill a live runtime. */
    public void onPhaseTransitioned(TaskPhaseTransitionedEvent event)
    {
        if (taskStore.isV2Task(event.taskId())) {
            return;
        }
        if (event.to() != TaskPhase.NEEDS_ATTENTION && event.to() != TaskPhase.COMPLETED) {
            return;
        }
        try {
            reconcileStoppedTask(event.taskId());
        }
        catch (RuntimeException e) {
            log.warn("stop teardown for task {} failed: {}", event.taskId(), e.getMessage());
        }
    }

    public void sweep()
    {
        forEachStoppedTask(task -> {
            reconcileStoppedTask(task.id());
            completePendingResume(task);
            completePendingRecovery(task);
        });
    }

    private void forEachStoppedTask(Consumer<Task> action)
    {
        for (Task task : taskStore.listByStatuses(SWEEP_STATUSES, SWEEP_PAGE)) {
            if (taskStore.isV2Task(task.id())) {
                continue;
            }
            try {
                action.accept(task);
            }
            catch (RuntimeException e) {
                log.warn("stop reconcile for task {} failed: {}", task.id(), e.getMessage());
            }
        }
    }

    private void completePendingResume(Task task)
    {
        if (task.status() != TaskStatus.PAUSED
                || taskStore.resumeRequestedAt(task.id()).isEmpty()
                || !runtimeStopped(task.id())) {
            return;
        }
        taskService.getObject().completeRequestedResume(task.id());
    }

    private void completePendingRecovery(Task task)
    {
        if (task.status() != TaskStatus.NEEDS_ATTENTION || !runtimeStopped(task.id())) {
            return;
        }
        taskStore.recoveryRequest(task.id()).ifPresent(request -> {
            if (TaskRecoveryRequest.KIND_REPLAN.equals(request.kind())) {
                planStages.getObject().completeRequestedReplan(task.id());
            }
            else {
                taskService.getObject().completeRequestedRecovery(task.id());
            }
        });
    }

    private void requestValidationCancellation(String taskId)
    {
        Instant now = Instant.now();
        for (ValidationClaim claim : validationStore.findOpenByTask(taskId)) {
            if (claim.cancelRequestedAt() == null) {
                validationStore.requestCancel(
                        claim.claimKey(), now, now.plus(VALIDATION_CANCEL_DEADLINE));
            }
        }
    }
}
