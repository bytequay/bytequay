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
package com.bytequay.app.service;

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.ThreadStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Sweeps IDLE / AWAITING threads whose {@code updatedAt} is older
 * than the configured behavior.archive_idle_after cadence and marks
 * them COMPLETED so they drop from the default list view. Conservative
 * by design — RUNNING / PENDING / AWAITING_REVIEW / NEEDS_ATTENTION
 * are never touched even if old.
 *
 * <p>Cadence values: {@code 1h}, {@code 1d}, {@code 1w}, {@code never}.
 * The {@code never} sentinel (or an unknown value) skips the sweep
 * entirely so users who turn the feature off on the workspace
 * Settings page actually get the off behavior. The job runs once an
 * hour after a 5-minute warmup; that's the same cadence the
 * scheduled-review sweeper uses, and it's coarse enough to avoid
 * thrashing.
 */
@Service
public class IdleThreadArchiver
{
    private static final Logger log = LoggerFactory.getLogger(IdleThreadArchiver.class);

    /** Only these are eligible for archive-by-idle. RUNNING /
     *  PENDING / AWAITING_REVIEW / NEEDS_ATTENTION represent active
     *  work and stay in the list. */
    private static final Set<ThreadStatus> ELIGIBLE =
            Set.of(ThreadStatus.IDLE, ThreadStatus.AWAITING);

    private static final int PER_STATUS_PAGE = 200;

    private final ThreadStore threadStore;
    private final WorkspaceBehaviorService behavior;

    public IdleThreadArchiver(ThreadStore threadStore, WorkspaceBehaviorService behavior)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.behavior = requireNonNull(behavior, "behavior is null");
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void sweep()
    {
        try {
            sweepOnce(Instant.now());
        }
        catch (RuntimeException e) {
            // Logged + swallowed; the scheduler keeps ticking. The
            // sweeper is best-effort housekeeping — one bad iteration
            // shouldn't take the scheduler down.
            log.warn("Idle-thread sweep failed; will retry next tick: {}", e.getMessage());
        }
    }

    /** Visible for tests. */
    void sweepOnce(Instant now)
    {
        Duration cadence = cadence();
        if (cadence == null) {
            return;
        }
        Instant cutoff = now.minus(cadence);
        int archived = 0;
        for (ThreadStatus status : ELIGIBLE) {
            List<Thread> candidates = threadStore.listTasksByStatus(status, PER_STATUS_PAGE);
            for (Thread t : candidates) {
                if (t.updatedAt().isBefore(cutoff)) {
                    threadStore.saveThread(archive(t, now));
                    archived++;
                }
            }
        }
        if (archived > 0) {
            log.info("Auto-archived {} idle thread(s) older than {} (cadence = {})",
                    archived, cutoff, cadence);
        }
    }

    private Duration cadence()
    {
        return switch (behavior.get().archiveIdleAfter()) {
            case "1h" -> Duration.ofHours(1);
            case "1d" -> Duration.ofDays(1);
            case "1w" -> Duration.ofDays(7);
            // "never" and anything unknown: skip the sweep entirely.
            default -> null;
        };
    }

    private static Thread archive(Thread t, Instant now)
    {
        return new Thread(
                t.id(),
                t.kind(),
                t.provider(),
                t.agentSessionId(),
                t.title(),
                ThreadStatus.COMPLETED,
                t.model(),
                t.costUsdMilli(),
                t.tokensIn(),
                t.tokensOut(),
                t.createdAt(),
                now,
                /* endedAt */ now,
                t.errorMessage(),
                t.flow(),
                t.activeTask());
    }
}
