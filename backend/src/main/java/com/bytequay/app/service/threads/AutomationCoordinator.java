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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.WorktreeLease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * The home for the headless automation surface — CI-fail subscribing,
 * jump-in lease transfer, parked-state writers (see
 * {@code docs/mockups/workspace-thread-task-design.md} "Automation
 * and system-initiated tasks").
 *
 * <p>v1 is just the stale-lease reaper: a periodic sweep that
 * releases lease rows whose holder pid no longer corresponds to a
 * live OS process. Without it a crashed subprocess (JVM kill, OOM)
 * would leave the lease row in place and the next agent on that
 * worktree would log "already held" forever. The reaper closes the
 * loop. CI-fail subscriber and friends land next.
 */
@Component
public class AutomationCoordinator
{
    private static final Logger log = LoggerFactory.getLogger(AutomationCoordinator.class);

    /** Lease rows whose lifetime exceeds this clock-wall window are
     *  considered candidates for the reaper even when the holder pid
     *  still maps to a live process — the surrounding agent should
     *  have released by now. Six hours covers a long debugging
     *  session with plenty of slack. */
    private static final long MAX_LEASE_AGE_MS = 6L * 60 * 60 * 1000;

    private final WorktreeLeaseService leaseService;

    public AutomationCoordinator(WorktreeLeaseService leaseService)
    {
        this.leaseService = requireNonNull(leaseService, "leaseService is null");
    }

    /**
     * Periodic sweep. Releases any lease row whose holder is gone or
     * whose age exceeds the soft cap. Runs every minute under the
     * same {@code bytequay.scheduling.enabled} gate as the other
     * scheduled jobs so tests don't get surprise reapings.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void reapStaleLeases()
    {
        Instant now = Instant.now();
        int reaped = 0;
        List<WorktreeLease> all = leaseService.listAll();
        for (WorktreeLease lease : all) {
            if (shouldReap(lease, now)) {
                log.info("Reaping stale lease on {} (task {}, pid {}, acquired {})",
                        lease.worktreePath(), lease.taskId(),
                        lease.holderPid(), lease.acquiredAt());
                leaseService.release(lease.worktreePath());
                reaped++;
            }
        }
        if (reaped > 0) {
            log.info("Released {} stale worktree lease(s)", reaped);
        }
    }

    /**
     * Decides whether a lease row is stale. A lease counts as stale
     * when ANY of the following holds:
     *   * its soft expiry has passed,
     *   * its holder pid is non-null and no live OS process matches,
     *   * its acquired_at is older than {@link #MAX_LEASE_AGE_MS}.
     *
     * The third rule catches LOGIC_LOOP holders that have no pid to
     * check against — for those, "too old" is the only signal.
     */
    static boolean shouldReap(WorktreeLease lease, Instant now)
    {
        if (lease.expiresAt() != null && now.isAfter(lease.expiresAt())) {
            return true;
        }
        if (lease.holderPid() != null) {
            Optional<ProcessHandle> handle = ProcessHandle.of(lease.holderPid());
            if (handle.isEmpty() || !handle.get().isAlive()) {
                return true;
            }
        }
        long ageMs = now.toEpochMilli() - lease.acquiredAt().toEpochMilli();
        return ageMs > MAX_LEASE_AGE_MS;
    }
}
