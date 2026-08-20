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

import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkArgument;
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
    private static final Logger log = LoggerFactory.getLogger(CherryPickExecutionMonitorJob.class);

    private final CherryPickExecutionStore store;
    private final CherryPickExecutionService service;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean();

    CherryPickExecutionMonitorJob(
            CherryPickExecutionStore store,
            CherryPickExecutionService service,
            @Value("${bytequay.cherry-pick.monitor-batch-size:20}") int batchSize)
    {
        this.store = requireNonNull(store, "store is null");
        this.service = requireNonNull(service, "service is null");
        checkArgument(batchSize > 0, "batchSize must be positive");
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${bytequay.cherry-pick.monitor-initial-delay:PT10S}",
            fixedDelayString = "${bytequay.cherry-pick.monitor-interval:PT5S}")
    public void monitor()
    {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            for (CherryPickExecution execution : store.listRunning(batchSize)) {
                try {
                    service.advance(execution.id());
                }
                catch (RuntimeException failure) {
                    // One unhealthy execution must not stop the others; the
                    // service has already recorded whatever it could observe.
                    log.warn("Cannot advance cherry-pick execution {}", execution.id(), failure);
                }
            }
        }
        finally {
            running.set(false);
        }
    }
}
