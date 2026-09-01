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
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.CleanupResult;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.agent.core.AgentEventOutboxEntry.pendingLifecycle;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

public final class AgentMaintenance
{
    private static final String INTERRUPTION_REASON = "Backend restarted while the turn was active";
    private static final Duration CLEANUP_RETRY_DELAY = Duration.ofMinutes(1);

    private final AgentTaskStore taskStore;
    private final AgentTurnStore turnStore;
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

        taskStore.listLive().forEach(task -> {
            synchronized (runtimeRegistry.taskLock(task.id())) {
                AgentTask current = taskStore.find(task.id()).orElseThrow();
                if (current.state().terminal()) {
                    return;
                }
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
