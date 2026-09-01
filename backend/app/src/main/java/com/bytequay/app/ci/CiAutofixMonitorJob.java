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
package com.bytequay.app.ci;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agent.AgentServingGate;
import com.bytequay.app.scheduler.MonitorLoop;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStore;
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

@Component
final class CiAutofixMonitorJob
{
    private static final Duration TERMINAL_RETRY_DELAY = Duration.ofMinutes(1);

    private final CiAutofixStore store;
    private final AgentTaskStore taskStore;
    private final CiAutofixService service;
    private final AgentServingGate servingGate;
    private final Clock clock;
    private final int batchSize;
    private final MonitorLoop loop = new MonitorLoop(CiAutofixMonitorJob.class, "CI Autofix run");

    CiAutofixMonitorJob(
            CiAutofixStore store,
            AgentTaskStore taskStore,
            CiAutofixService service,
            AgentServingGate servingGate,
            Clock clock,
            @Value("${bytequay.ci-autofix.monitor-batch-size:20}") int batchSize)
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
            initialDelayString = "${bytequay.ci-autofix.monitor-initial-delay:PT30S}",
            fixedDelayString = "${bytequay.ci-autofix.monitor-interval:PT30S}")
    public void monitor()
    {
        if (!servingGate.isReady()) {
            return;
        }
        loop.pass(() -> {
            loop.advanceEach(nonFinished(), service::advance);
            loop.advanceEach(finishedWithLiveTask(), service::advance);
            loop.advanceEach(finishedWithPendingCleanup(), service::advance);
        });
    }

    private List<CiAutofixRunId> nonFinished()
    {
        return store.listNonFinished(batchSize).stream()
                .map(CiAutofixRun::id)
                .collect(toImmutableList());
    }

    /**
     * Runs whose work is done but whose agent task is still live. Advancing them
     * releases the task, which the finished run no longer polls for.
     */
    private List<CiAutofixRunId> finishedWithLiveTask()
    {
        return finishedRuns(taskStore.listLive().stream()
                .filter(task -> task.permissionPolicy() == AgentPermissionPolicy.CI_AUTOFIX)
                .filter(task -> !task.updatedAt().isAfter(retryBefore()))
                .collect(toImmutableList()));
    }

    private List<CiAutofixRunId> finishedWithPendingCleanup()
    {
        return finishedRuns(taskStore.listPendingWorktreeCleanup(
                AgentPermissionPolicy.CI_AUTOFIX, retryBefore()));
    }

    private List<CiAutofixRunId> finishedRuns(List<AgentTask> tasks)
    {
        return tasks.stream()
                .map(AgentTask::id)
                .map(taskId -> store.findByAgentTaskId(taskId.toString()))
                .flatMap(Optional::stream)
                .filter(run -> run.status() == CiAutofixRun.Status.FINISHED)
                .map(CiAutofixRun::id)
                .limit(batchSize)
                .collect(toImmutableList());
    }

    private Instant retryBefore()
    {
        return Instant.now(clock).minus(TERMINAL_RETRY_DELAY);
    }
}
