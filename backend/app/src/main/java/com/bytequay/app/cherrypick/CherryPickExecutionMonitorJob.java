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

import com.bytequay.app.agent.AgentServingGate;
import com.bytequay.app.scheduler.MonitorLoop;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Drives running executions forward, one bounded action at a time.
 *
 * <p>The job only chooses who to advance. Every decision about what happened
 * belongs to {@link CherryPickExecutionService}.
 */
@Component
final class CherryPickExecutionMonitorJob
{
    private final CherryPickExecutionStore store;
    private final CherryPickExecutionService service;
    private final AgentServingGate servingGate;
    private final int batchSize;
    private final MonitorLoop loop =
            new MonitorLoop(CherryPickExecutionMonitorJob.class, "cherry-pick execution");

    CherryPickExecutionMonitorJob(
            CherryPickExecutionStore store,
            CherryPickExecutionService service,
            AgentServingGate servingGate,
            @Value("${bytequay.cherry-pick.monitor-batch-size:20}") int batchSize)
    {
        this.store = requireNonNull(store, "store is null");
        this.service = requireNonNull(service, "service is null");
        this.servingGate = requireNonNull(servingGate, "servingGate is null");
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
        loop.pass(() -> loop.advanceEach(running(), service::advance));
    }

    private List<CherryPickExecutionId> running()
    {
        return store.listRunning(batchSize).stream()
                .map(CherryPickExecution::id)
                .collect(toImmutableList());
    }
}
