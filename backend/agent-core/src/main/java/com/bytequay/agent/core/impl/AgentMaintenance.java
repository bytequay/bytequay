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
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentWorkspaceService;
import com.bytequay.agent.core.CleanupResult;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

public final class AgentMaintenance
{
    private static final String INTERRUPTION_REASON = "Backend restarted while the turn was active";

    private final AgentTaskStore taskStore;
    private final AgentTurnStore turnStore;
    private final AgentWorkspaceService workspaceService;
    private final AgentApprovalService approvalService;
    private final AgentMetricsService metricsService;
    private final AgentCompletionService completionService;
    private final AgentRuntimeRegistry runtimeRegistry;
    private final Clock clock;

    public AgentMaintenance(
            AgentTaskStore taskStore,
            AgentTurnStore turnStore,
            AgentWorkspaceService workspaceService,
            AgentApprovalService approvalService,
            AgentMetricsService metricsService,
            AgentCompletionService completionService,
            AgentRuntimeRegistry runtimeRegistry,
            Clock clock)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.workspaceService = requireNonNull(workspaceService, "workspaceService is null");
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
                    if (current.startedAt().isPresent()) {
                        return;
                    }
                    TurnMetrics metrics = metricsService.finishTurn(current.id(), TurnState.INTERRUPTED);
                    turnStore.update(turnStore.find(current.id()).orElseThrow().finish(
                            TurnState.INTERRUPTED,
                            metrics,
                            Optional.of(INTERRUPTION_REASON),
                            Instant.now(clock)));
                });
            }
        });

        taskStore.listLive().forEach(task -> {
            synchronized (runtimeRegistry.taskLock(task.id())) {
                AgentTask current = taskStore.find(task.id()).orElseThrow();
                if (current.state().terminal()) {
                    return;
                }
                try {
                    workspaceService.restore(current.id(), current.worktree(), current.repository());
                }
                catch (RuntimeException failure) {
                    if (turnStore.findActive(current.id()).isPresent()) {
                        return;
                    }
                    Instant failedAt = Instant.now(clock);
                    String message = failure.getMessage() == null || failure.getMessage().isBlank()
                            ? failure.getClass().getSimpleName()
                            : failure.getMessage();
                    taskStore.update(current.fail(
                            "Cannot restore worktree lease: " + message,
                            failedAt,
                            failedAt.plus(DefaultAgentCompletionService.RETENTION_PERIOD)));
                    workspaceService.release(current.id());
                }
            }
        });
    }

    public List<CleanupResult> cleanupDue(int limit)
    {
        checkArgument(limit > 0, "limit must be positive");
        return taskStore.listCleanupDue(Instant.now(clock), limit).stream()
                .map(AgentTask::id)
                .map(completionService::cleanup)
                .collect(toImmutableList());
    }
}
