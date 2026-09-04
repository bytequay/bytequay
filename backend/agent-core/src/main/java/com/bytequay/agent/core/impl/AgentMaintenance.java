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

import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentInputDelivery;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.CleanupResult;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.core.store.SteerDeliveryStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.agent.core.AgentEventOutboxEntry.pendingInline;
import static com.bytequay.agent.core.AgentEventOutboxEntry.pendingLifecycle;
import static com.bytequay.agent.core.AgentEventSource.root;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

public final class AgentMaintenance
{
    private static final String INTERRUPTION_REASON = "Backend restarted while the turn was active";
    private static final String VERIFIED_STOP_REASON =
            INTERRUPTION_REASON + "; its provider process is verified stopped";
    private static final String UNVERIFIED_STOP_REASON =
            INTERRUPTION_REASON + "; a process it started could not be verified stopped, so the worktree stays reserved";
    private static final Duration CLEANUP_RETRY_DELAY = Duration.ofMinutes(1);

    private final AgentTaskStore taskStore;
    private final AgentTurnStore turnStore;
    private final QueuedInputStore queueStore;
    private final SteerDeliveryStore steerStore;
    private final AgentUnitOfWork unitOfWork;
    private final AgentEventOutboxStore outboxStore;
    private final AgentApprovalService approvalService;
    private final AgentMetricsService metricsService;
    private final AgentCompletionService completionService;
    private final AgentRuntimeRegistry runtimeRegistry;
    private final Clock clock;

    public AgentMaintenance(
            AgentTaskStore taskStore,
            AgentTurnStore turnStore,
            QueuedInputStore queueStore,
            SteerDeliveryStore steerStore,
            AgentUnitOfWork unitOfWork,
            AgentEventOutboxStore outboxStore,
            AgentApprovalService approvalService,
            AgentMetricsService metricsService,
            AgentCompletionService completionService,
            AgentRuntimeRegistry runtimeRegistry,
            Clock clock)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.queueStore = requireNonNull(queueStore, "queueStore is null");
        this.steerStore = requireNonNull(steerStore, "steerStore is null");
        this.unitOfWork = requireNonNull(unitOfWork, "unitOfWork is null");
        this.outboxStore = requireNonNull(outboxStore, "outboxStore is null");
        this.approvalService = requireNonNull(approvalService, "approvalService is null");
        this.metricsService = requireNonNull(metricsService, "metricsService is null");
        this.completionService = requireNonNull(completionService, "completionService is null");
        this.runtimeRegistry = requireNonNull(runtimeRegistry, "runtimeRegistry is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public void recover()
    {
        // This also closes approvals whose turn became terminal before a
        // previous denial could be persisted.
        approvalService.denyAllPending();
        turnStore.listRecoverable().forEach(turn -> {
            synchronized (runtimeRegistry.taskLock(turn.taskId())) {
                turnStore.find(turn.id()).filter(current -> current.state().active()).ifPresent(current -> {
                    WorktreeMutationClaim claim = WorktreeMutationClaim.turn(current.id(), current.createdAt());
                    AgentTask task = taskStore.find(current.taskId()).orElseThrow();
                    if (task.worktreeMutationClaim().isEmpty()) {
                        taskStore.update(task.claimWorktreeMutation(claim, Instant.now(clock)));
                    }
                    else if (!task.worktreeMutationClaim().equals(Optional.of(claim))) {
                        throw new IllegalStateException("Active turn does not own the task worktree claim: " + current.id());
                    }
                    if (current.startedAt().isPresent()) {
                        return;
                    }
                    EventId eventId = EventId.random();
                    unitOfWork.execute(() -> {
                        TurnMetrics metrics = metricsService.finishTurn(current.id(), TurnState.INTERRUPTED);
                        Instant interruptedAt = Instant.now(clock);
                        AgentTask claimedTask = taskStore.find(current.taskId()).orElseThrow();
                        turnStore.update(turnStore.find(current.id()).orElseThrow().finish(
                                TurnState.INTERRUPTED,
                                metrics,
                                Optional.of(INTERRUPTION_REASON),
                                interruptedAt));
                        taskStore.update(claimedTask.releaseWorktreeMutation(claim, interruptedAt));
                        outboxStore.insert(pendingLifecycle(
                                eventId,
                                current.taskId(),
                                Optional.of(current.id()),
                                AgentEventPayload.LifecyclePhase.INTERRUPTED,
                                INTERRUPTION_REASON,
                                interruptedAt));
                        return null;
                    });
                });
            }
        });

        settleSteers();
        taskStore.listLive().forEach(task -> {
            synchronized (runtimeRegistry.taskLock(task.id())) {
                AgentTask current = taskStore.find(task.id()).orElseThrow();
                if (current.state().terminal()) {
                    return;
                }
                pauseQueue(current.id());
                if (turnStore.findActive(current.id()).isPresent()) {
                    return;
                }
                if (current.worktreeMutationClaim().isPresent()) {
                    WorktreeMutationClaim claim = current.worktreeMutationClaim().orElseThrow();
                    if (claim.kind() == WorktreeMutationClaim.Kind.COMPLETION) {
                        taskStore.update(current.releaseWorktreeMutation(claim, Instant.now(clock)));
                    }
                    return;
                }
                if (current.taskWorktree().isEmpty()) {
                    failLegacyTask(current);
                }
            }
        });
    }

    /**
     * A restart pauses the queue: nothing starts by itself until the user has seen what happened.
     *
     * <p>An input that was being claimed is settled by what its turn durably shows. Never sent
     * means it waits again as paused; sent but never acknowledged means nobody knows, so it is
     * uncertain and only the user may discard it; acknowledged means it was consumed.
     */
    private void pauseQueue(TaskId taskId)
    {
        Instant now = Instant.now(clock);
        for (QueuedAgentInput input : queueStore.listQueued(taskId)) {
            switch (input.state()) {
                case QUEUED -> queueStore.update(input.paused("Backend restarted", now));
                case STARTING -> {
                    AgentTurn turn = turnStore.find(input.turnId().orElseThrow()).orElseThrow();
                    if (turn.knownUnsent() || turn.startedAt().isEmpty()) {
                        queueStore.update(input.paused("Backend restarted before the input was sent", now));
                    }
                    else if (turn.providerTurnReference().isEmpty()) {
                        queueStore.update(input.uncertain("Backend restarted while the input was being sent", now));
                    }
                    else {
                        queueStore.update(input.started(now));
                    }
                }
                default -> {}
            }
        }
    }

    /** A steer caught by the restart: not sent is a plain failure; sent without an answer stays unknown for good. */
    private void settleSteers()
    {
        for (AgentInputDelivery delivery : steerStore.listUnsettled()) {
            synchronized (runtimeRegistry.taskLock(delivery.taskId())) {
                Instant now = Instant.now(clock);
                AgentInputDelivery settled = delivery.state() == AgentInputDelivery.State.SENDING
                        ? delivery.unknown("Backend restarted while the input was being sent", now)
                        : delivery.failed("Backend restarted before the input was sent", now);
                EventId eventId = EventId.random();
                unitOfWork.execute(() -> {
                    steerStore.update(settled);
                    outboxStore.insert(pendingInline(
                            eventId,
                            settled.taskId(),
                            Optional.of(settled.turnId()),
                            root(),
                            new AgentEventPayload.Error(
                                    settled.state() == AgentInputDelivery.State.UNKNOWN ? "STEER_UNKNOWN" : "STEER_FAILED",
                                    settled.failure().orElseThrow(),
                                    false),
                            now));
                    return null;
                });
            }
        }
    }

    /**
     * Settles provider ownership once the previous backend's processes have been classified.
     *
     * <p>A turn that was mid-flight at the crash has no transport any more, so it is interrupted
     * either way; what the process verdict decides is the worktree. A task whose launch could not
     * be verified stopped swaps its claim for a {@code RECOVERY} claim keyed by that launch, so
     * nothing may touch the worktree until a later startup verifies the launch, and that later
     * verification is what releases the claim here. Every other task's turn is released, because
     * its own process was verified stopped or was never launched.
     *
     * @param unverifiableLaunches the launch that could not be verified stopped, by the task it names
     * @param verifiedLaunches launches now verified stopped or known never launched
     * @param everyProcessAttributed false when some unverifiable process names no task, in which
     * case no turn outside {@code unverifiableLaunches} may be treated as stopped, because the
     * stray process could be its own
     */
    public void settleAfterProcessRecovery(
            Map<TaskId, String> unverifiableLaunches,
            Set<String> verifiedLaunches,
            boolean everyProcessAttributed)
    {
        requireNonNull(unverifiableLaunches, "unverifiableLaunches is null");
        requireNonNull(verifiedLaunches, "verifiedLaunches is null");
        unverifiableLaunches.forEach((taskId, launchId) -> settleTask(taskId, Optional.of(launchId), verifiedLaunches));
        if (!everyProcessAttributed) {
            return;
        }
        taskStore.listLive().stream()
                .map(AgentTask::id)
                .filter(taskId -> !unverifiableLaunches.containsKey(taskId))
                .forEach(taskId -> settleTask(taskId, Optional.empty(), verifiedLaunches));
    }

    /**
     * Releases or retargets one startup recovery claim after an explicit process check.
     *
     * <p>The caller checks every durable provider launch for the task. If one still cannot be
     * verified stopped, the same claim is retained or moved to that launch; otherwise it is
     * released. No runtime registry entry or recovery state machine is created.
     *
     * @param taskId task whose worktree is reserved
     * @param expectedVersion task generation the user confirmed
     * @param expectedClaimLaunch launch named by the confirmed recovery claim
     * @param remainingUnverifiableLaunch another launch that still blocks the task, if any
     * @return the current or updated task
     */
    public AgentTask settleRecoveryClaim(
            TaskId taskId,
            long expectedVersion,
            String expectedClaimLaunch,
            Optional<String> remainingUnverifiableLaunch)
    {
        requireNonNull(taskId, "taskId is null");
        checkArgument(expectedVersion >= 0, "expectedVersion is negative");
        requireNonNull(expectedClaimLaunch, "expectedClaimLaunch is null");
        checkArgument(!expectedClaimLaunch.isBlank(), "expectedClaimLaunch is blank");
        requireNonNull(remainingUnverifiableLaunch, "remainingUnverifiableLaunch is null");
        remainingUnverifiableLaunch.ifPresent(launchId -> checkArgument(!launchId.isBlank(), "remainingUnverifiableLaunch is blank"));
        synchronized (runtimeRegistry.taskLock(taskId)) {
            AgentTask task = taskStore.find(taskId).orElseThrow(() -> new AgentTaskNotFoundException(taskId));
            if (task.state().terminal()) {
                throw new AgentConflictException("Task is already terminal: " + taskId);
            }
            if (task.version() != expectedVersion) {
                throw new AgentConflictException("Task runtime changed since the stop was confirmed: " + taskId);
            }
            if (turnStore.findActive(taskId).isPresent()) {
                throw new AgentConflictException("Task has an active turn: " + taskId);
            }
            WorktreeMutationClaim claim = task.worktreeMutationClaim()
                    .filter(held -> held.kind() == WorktreeMutationClaim.Kind.RECOVERY)
                    .filter(held -> held.ownerId().equals(expectedClaimLaunch))
                    .orElseThrow(() -> new AgentConflictException("Task recovery runtime changed since the stop was confirmed: " + taskId));
            if (remainingUnverifiableLaunch.equals(Optional.of(expectedClaimLaunch))) {
                return task;
            }
            Instant now = Instant.now(clock);
            AgentTask released = task.releaseWorktreeMutation(claim, now);
            AgentTask settled = remainingUnverifiableLaunch
                    .map(launchId -> released.claimWorktreeMutation(WorktreeMutationClaim.recovery(launchId, now), now))
                    .orElse(released);
            return taskStore.update(settled);
        }
    }

    private void settleTask(TaskId taskId, Optional<String> unverifiableLaunch, Set<String> verifiedLaunches)
    {
        synchronized (runtimeRegistry.taskLock(taskId)) {
            Optional<AgentTask> found = taskStore.find(taskId).filter(task -> !task.state().terminal());
            if (found.isEmpty()) {
                return;
            }
            AgentTask task = found.orElseThrow();
            Optional<AgentTurn> active = turnStore.findActive(taskId);
            if (active.isPresent()) {
                interruptRestartedTurn(task, active.orElseThrow(), unverifiableLaunch);
                return;
            }
            Instant now = Instant.now(clock);
            Optional<WorktreeMutationClaim> claim = task.worktreeMutationClaim();
            if (unverifiableLaunch.isPresent()) {
                WorktreeMutationClaim recovery = WorktreeMutationClaim.recovery(unverifiableLaunch.orElseThrow(), now);
                if (claim.isEmpty()) {
                    taskStore.update(task.claimWorktreeMutation(recovery, now));
                }
                else if (claim.orElseThrow().kind() == WorktreeMutationClaim.Kind.TURN) {
                    // The turn ended without proving its process quiet, and now that process is
                    // gone unverified: the launch, not the finished turn, is what still blocks.
                    taskStore.update(task.releaseWorktreeMutation(claim.orElseThrow(), now).claimWorktreeMutation(recovery, now));
                }
                else if (claim.orElseThrow().kind() == WorktreeMutationClaim.Kind.RECOVERY
                        && !claim.orElseThrow().ownerId().equals(recovery.ownerId())) {
                    // One task can leave more than one launch behind. If the claim's launch was
                    // resolved while another remains unverifiable, keep the task blocked by the
                    // remaining launch instead of stranding a claim that can never be observed.
                    taskStore.update(task.releaseWorktreeMutation(claim.orElseThrow(), now).claimWorktreeMutation(recovery, now));
                }
                return;
            }
            if (claim.isPresent()
                    && claim.orElseThrow().kind() == WorktreeMutationClaim.Kind.RECOVERY
                    && verifiedLaunches.contains(claim.orElseThrow().ownerId())) {
                taskStore.update(task.releaseWorktreeMutation(claim.orElseThrow(), now));
            }
        }
    }

    private void interruptRestartedTurn(AgentTask task, AgentTurn turn, Optional<String> unverifiableLaunch)
    {
        WorktreeMutationClaim claim = WorktreeMutationClaim.turn(turn.id(), turn.createdAt());
        String reason = unverifiableLaunch.isPresent() ? UNVERIFIED_STOP_REASON : VERIFIED_STOP_REASON;
        EventId eventId = EventId.random();
        unitOfWork.execute(() -> {
            TurnMetrics metrics = metricsService.finishTurn(turn.id(), TurnState.INTERRUPTED);
            Instant interruptedAt = Instant.now(clock);
            AgentTask current = taskStore.find(task.id()).orElseThrow();
            turnStore.update(turnStore.find(turn.id()).orElseThrow().finish(
                    TurnState.INTERRUPTED,
                    metrics,
                    Optional.of(reason),
                    interruptedAt));
            AgentTask released = current.worktreeMutationClaim().equals(Optional.of(claim))
                    ? current.releaseWorktreeMutation(claim, interruptedAt)
                    : current;
            taskStore.update(unverifiableLaunch
                    .map(launchId -> released.claimWorktreeMutation(
                            WorktreeMutationClaim.recovery(launchId, interruptedAt),
                            interruptedAt))
                    .orElse(released));
            outboxStore.insert(pendingLifecycle(
                    eventId,
                    task.id(),
                    Optional.of(turn.id()),
                    AgentEventPayload.LifecyclePhase.INTERRUPTED,
                    reason,
                    interruptedAt));
            return null;
        });
    }

    private void failLegacyTask(AgentTask task)
    {
        String reason = "Task has no managed worktree ownership facts";
        Instant failedAt = Instant.now(clock);
        EventId eventId = EventId.random();
        unitOfWork.execute(() -> {
            AgentTask failed = taskStore.update(task.fail(
                    reason,
                    failedAt,
                    failedAt.plus(DefaultAgentCompletionService.RETENTION_PERIOD)));
            outboxStore.insert(pendingLifecycle(
                    eventId,
                    failed.id(),
                    Optional.empty(),
                    AgentEventPayload.LifecyclePhase.TASK_FAILED,
                    reason,
                    failedAt));
            return null;
        });
    }

    public List<CleanupResult> cleanupDue(int limit)
    {
        checkArgument(limit > 0, "limit must be positive");
        Instant now = Instant.now(clock);
        return taskStore.listCleanupDue(now, now.minus(CLEANUP_RETRY_DELAY), limit).stream()
                .map(AgentTask::id)
                .map(completionService::cleanup)
                .collect(toImmutableList());
    }
}
