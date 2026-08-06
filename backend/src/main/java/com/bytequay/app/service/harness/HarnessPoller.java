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
package com.bytequay.app.service.harness;

import com.bytequay.app.service.harness.HarnessModels.Watch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

/** Starts idempotent cycles for quiet active watches; work runs asynchronously. */
@Component
public class HarnessPoller
{
    private static final Logger log = LoggerFactory.getLogger(HarnessPoller.class);
    /** A CI board rarely settles inside a minute, and every cycle costs a
     *  GitHub round trip per watch. Five minutes is the cadence. */
    private static final long POLL_INTERVAL_MS = 300_000;

    private final HarnessStore store;
    private final HarnessOrchestrator orchestrator;

    public HarnessPoller(HarnessStore store, HarnessOrchestrator orchestrator)
    {
        this.store = requireNonNull(store, "store is null");
        this.orchestrator = requireNonNull(orchestrator, "orchestrator is null");
    }

    @Scheduled(fixedDelay = POLL_INTERVAL_MS, initialDelay = POLL_INTERVAL_MS)
    public void poll()
    {
        long before = Instant.now().toEpochMilli() - POLL_INTERVAL_MS;
        for (Watch watch : store.pollableWatches(before, 50)) {
            try {
                orchestrator.requestRun(watch.id(), "poll");
            }
            catch (RuntimeException e) {
                log.warn("Unable to schedule CI harness watch {}: {}", watch.id(), e.getMessage());
            }
        }
    }
}
