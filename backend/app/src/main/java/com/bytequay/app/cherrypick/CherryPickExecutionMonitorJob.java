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
package com.bytequay.app.cherrypick;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agent.AgentServingGate;
import com.bytequay.app.scheduler.MonitorLoop;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Drives running executions and terminal cleanup retries, one bounded action at a time.
 *
 * <p>The job only chooses who to advance. Every decision about what happened
 * belongs to {@link CherryPickExecutionService}.
 */
@Component
final class CherryPickExecutionMonitorJob
{
    private static final Duration TERMINAL_RETRY_DELAY = Duration.ofMinutes(1);

    private final CherryPickExecutionStore store;
    private final AgentTaskStore taskStore;
    private final CherryPickExecutionService service;
    private final AgentServingGate servingGate;
    private final Clock clock;
    private final int batchSize;
    private final MonitorLoop loop =
            new MonitorLoop(CherryPickExecutionMonitorJob.class, "cherry-pick execution");

    CherryPickExecutionMonitorJob(
            CherryPickExecutionStore store,
            AgentTaskStore taskStore,
            CherryPickExecutionService service,
            AgentServingGate servingGate,
            Clock clock,
            @Value("${bytequay.cherry-pick.monitor-batch-size:20}") int batchSize)
    {
        this.store = requireNonNull(store, "store is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.service = requireNonNull(service, "service is null");
        this.servingGate = requireNonNull(servingGate, "servingGate is null");
        this.clock = requireNonNull(clock, "clock is null");
        checkArgument(batchSize > 0, "batchSize must be positive");
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${bytequay.cherry-pick.monitor-initial-delay:PT10S}",
            fixedDelayString = "${bytequay.cherry-pick.monitor-interval:PT5S}")
    public void monitor()
    {
        if (!servingGate.isReady()) {
            return;
        }
        loop.pass(() -> {
            loop.advanceEach(running(), service::advance);
            loop.advanceEach(finishedWithLiveTask(), service::advance);
            loop.advanceEach(finishedWithPendingCleanup(), service::advance);
        });
    }

    private List<CherryPickExecutionId> running()
    {
        return store.listRunning(batchSize).stream()
                .map(CherryPickExecution::id)
                .collect(toImmutableList());
    }

    private List<CherryPickExecutionId> finishedWithLiveTask()
    {
        return finishedExecutions(taskStore.listLive().stream()
                .filter(task -> task.permissionPolicy() == AgentPermissionPolicy.CHERRY_PICK)
                .filter(task -> !task.updatedAt().isAfter(retryBefore()))
                .collect(toImmutableList()));
    }

    private List<CherryPickExecutionId> finishedWithPendingCleanup()
    {
        return finishedExecutions(taskStore.listPendingWorktreeCleanup(
                AgentPermissionPolicy.CHERRY_PICK, retryBefore()));
    }

    private List<CherryPickExecutionId> finishedExecutions(List<AgentTask> tasks)
    {
        return tasks.stream()
                .map(AgentTask::id)
                .map(taskId -> store.findByAgentTaskId(taskId.toString()))
                .flatMap(Optional::stream)
                .filter(execution -> execution.status() == CherryPickExecution.Status.FINISHED)
                .map(CherryPickExecution::id)
                .limit(batchSize)
                .collect(toImmutableList());
    }

    private Instant retryBefore()
    {
        return Instant.now(clock).minus(TERMINAL_RETRY_DELAY);
    }
}
