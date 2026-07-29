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

import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.task.V2TaskControlService;
import com.bytequay.app.service.WorkspaceBehaviorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

import static java.util.Objects.requireNonNull;

/**
 * Archives dormant V2 Tasks through their aggregate owner after an atomic
 * durable-liveness recheck.
 */
@Service
public class TaskIdleArchiver
        implements ExecutionPorts.MaintenanceWork
{
    private static final int PAGE = 200;
    private static final Duration SWEEP_INTERVAL = Duration.ofHours(1);

    private final WorkspaceBehaviorService behavior;
    private final ObjectProvider<V2TaskControlService> v2Controls;
    private Instant nextSweepAt = Instant.MIN;

    public TaskIdleArchiver(
            WorkspaceBehaviorService behavior,
            ObjectProvider<V2TaskControlService> v2Controls)
    {
        this.behavior = requireNonNull(behavior, "behavior is null");
        this.v2Controls = requireNonNull(v2Controls, "v2Controls is null");
    }

    @Override
    public synchronized void maintain(Instant now)
    {
        requireNonNull(now, "now is null");
        if (now.isBefore(nextSweepAt)) {
            return;
        }
        nextSweepAt = now.plus(SWEEP_INTERVAL);
        sweepOnce(now);
    }

    /** Visible for tests. */
    void sweepOnce(Instant now)
    {
        Duration cadence = cadence();
        if (cadence == null) {
            return;
        }
        Instant cutoff = now.minus(cadence);
        V2TaskControlService controls = v2Controls.getIfAvailable();
        if (controls != null) {
            for (String taskId : controls.idleArchiveCandidates(cutoff, now, PAGE)) {
                controls.archiveIfIdle(taskId, cutoff, now);
            }
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
}
