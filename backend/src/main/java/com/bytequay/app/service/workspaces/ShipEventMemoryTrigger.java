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
package com.bytequay.app.service.workspaces;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

import static com.bytequay.app.config.AsyncConfig.APPLICATION_EXECUTOR;
import static java.util.Objects.requireNonNull;

/**
 * Event-driven workspace-memory distill trigger. The Tasks surface
 * calls {@link #onShip} after a successful ship-and-continue or
 * park-and-start-next; this component runs the distiller off the
 * caller's thread (so the ship endpoint stays snappy) and dedups
 * back-to-back fires per workspace so a burst of ships in one
 * session produces one distill, not a stampede.
 *
 * <p>The background daily {@code @Scheduled} pass on
 * {@link WorkspaceMemoryDistiller} remains the safety net for
 * workspaces that never ship; this trigger is the "as the work
 * lands, memory catches up" hook.
 */
@Component
public class ShipEventMemoryTrigger
{
    private static final Logger log = LoggerFactory.getLogger(ShipEventMemoryTrigger.class);

    /** Back-to-back ship events within this window collapse to one
     *  distill. Five minutes is the spec's target — short enough
     *  that a single user session's memory stays fresh, long enough
     *  that a burst of ships doesn't pay for distillation N times. */
    static final Duration DEDUP_WINDOW = Duration.ofMinutes(5);

    private final WorkspaceMemoryDistiller distiller;
    private final Executor executor;
    /** workspaceId → wall-clock of the last distill we ran (or
     *  enqueued). Used purely for dedup; eventual consistency is
     *  fine because the daily scheduled pass backstops anything we
     *  miss. */
    private final ConcurrentMap<String, Instant> lastFireAt = new ConcurrentHashMap<>();

    public ShipEventMemoryTrigger(
            WorkspaceMemoryDistiller distiller,
            @Qualifier(APPLICATION_EXECUTOR) Executor executor)
    {
        this.distiller = requireNonNull(distiller, "distiller is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    @EventListener
    public void onShipEvent(WorkspaceShipEvent event)
    {
        onShip(event.workspaceId());
    }

    /** Called by the ship / next code paths. Returns immediately;
     *  the distill runs on the application executor and is dedupped
     *  against {@link #DEDUP_WINDOW}. */
    public void onShip(String workspaceId)
    {
        if (workspaceId == null || workspaceId.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        Instant prior = lastFireAt.get(workspaceId);
        if (prior != null && Duration.between(prior, now).compareTo(DEDUP_WINDOW) < 0) {
            log.debug("Skipping ship-event distill for {}: within {}min window of prior fire",
                    workspaceId, DEDUP_WINDOW.toMinutes());
            return;
        }
        // Mark BEFORE submitting so a second event landing while
        // the executor is still queuing this one is also dedupped.
        lastFireAt.put(workspaceId, now);
        executor.execute(() -> runDistill(workspaceId));
    }

    private void runDistill(String workspaceId)
    {
        try {
            distiller.distill(workspaceId);
        }
        catch (RuntimeException e) {
            // The scheduled pass catches anything we miss; log and
            // move on rather than failing the ship that triggered us.
            log.warn("Ship-event distill for {} failed: {}", workspaceId, e.getMessage());
        }
    }
}
