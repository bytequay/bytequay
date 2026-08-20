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

import com.bytequay.core.rebase.BranchRebase;
import com.bytequay.core.rebase.BranchRebaseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Selects running rebases and delegates one bounded transition to the service. */
@Component
final class BranchRebaseMonitorJob
{
    private static final Logger log = LoggerFactory.getLogger(BranchRebaseMonitorJob.class);

    private final BranchRebaseStore store;
    private final BranchRebaseService service;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean();

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
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            for (BranchRebase rebase : store.listRunning(batchSize)) {
                try {
                    service.advance(rebase.id());
                }
                catch (RuntimeException failure) {
                    log.warn("Cannot advance branch rebase {}", rebase.id(), failure);
                }
            }
        }
        finally {
            running.set(false);
        }
    }
}
