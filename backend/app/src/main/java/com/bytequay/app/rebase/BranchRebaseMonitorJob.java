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
package com.bytequay.app.rebase;

import com.bytequay.app.scheduler.MonitorLoop;
import com.bytequay.core.rebase.BranchRebase;
import com.bytequay.core.rebase.BranchRebaseId;
import com.bytequay.core.rebase.BranchRebaseStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Selects running rebases and delegates one bounded transition to the service. */
@Component
final class BranchRebaseMonitorJob
{
    private final BranchRebaseStore store;
    private final BranchRebaseService service;
    private final int batchSize;
    private final MonitorLoop loop = new MonitorLoop(BranchRebaseMonitorJob.class, "branch rebase");

    BranchRebaseMonitorJob(
            BranchRebaseStore store,
            BranchRebaseService service,
            @Value("${bytequay.rebase.monitor-batch-size:20}") int batchSize)
    {
        this.store = requireNonNull(store, "store is null");
        this.service = requireNonNull(service, "service is null");
        checkArgument(batchSize > 0, "batchSize must be positive");
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${bytequay.rebase.monitor-initial-delay:PT10S}",
            fixedDelayString = "${bytequay.rebase.monitor-interval:PT5S}")
    public void monitor()
    {
        loop.pass(() -> loop.advanceEach(running(), service::advance));
    }

    private List<BranchRebaseId> running()
    {
        return store.listRunning(batchSize).stream()
                .map(BranchRebase::id)
                .collect(toImmutableList());
    }
}
