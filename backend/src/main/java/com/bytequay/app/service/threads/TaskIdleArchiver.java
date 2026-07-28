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

import com.bytequay.app.developmentflow.task.V2TaskControlService;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.bytequay.app.service.WorkspaceBehaviorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * The task-axis sibling of {@code IdleThreadArchiver}. Dormant Tasks drop to
 * ARCHIVED after the workspace's archive-idle cadence. Legacy Tasks retain the
 * sole {@link TaskPhaseMachine#archiveIdle} path; typed Tasks ask their Task
 * owner to record ARCHIVING only after an atomic durable-liveness recheck.
 *
 * <p>Each owner rechecks its own durable liveness under the Task command lock.
 */
@Service
public class TaskIdleArchiver
{
    private static final Logger log = LoggerFactory.getLogger(TaskIdleArchiver.class);

    private static final int PAGE = 200;

    /** Latest terminal turn activity marks the task's last work. */
    private static final Set<ThreadTurnStatus> TERMINAL_TURNS = EnumSet.of(
            ThreadTurnStatus.COMPLETED, ThreadTurnStatus.FAILED, ThreadTurnStatus.CANCELLED);

    private final TaskStore taskStore;
    private final ThreadTurnStore turnStore;
    private final ReviewRoundStore roundStore;
    private final ValidationPassStore validationStore;
    private final WorkspaceBehaviorService behavior;
    private final TaskPhaseMachine machine;
    private final ObjectProvider<V2TaskControlService> v2Controls;

    public TaskIdleArchiver(
            TaskStore taskStore,
            ThreadTurnStore turnStore,
            ReviewRoundStore roundStore,
            ValidationPassStore validationStore,
            WorkspaceBehaviorService behavior,
            TaskPhaseMachine machine,
            ObjectProvider<V2TaskControlService> v2Controls)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.roundStore = requireNonNull(roundStore, "roundStore is null");
        this.validationStore = requireNonNull(validationStore, "validationStore is null");
        this.behavior = requireNonNull(behavior, "behavior is null");
        this.machine = requireNonNull(machine, "machine is null");
        this.v2Controls = requireNonNull(v2Controls, "v2Controls is null");
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void sweep()
    {
        try {
            sweepOnce(Instant.now());
        }
        catch (RuntimeException e) {
            log.warn("Idle-task sweep failed; will retry next tick: {}", e.getMessage());
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
        V2TaskControlService controls = v2Controls.getIfAvailable();
        if (controls != null) {
            for (String taskId : controls.idleArchiveCandidates(cutoff, now, PAGE)) {
                controls.archiveIfIdle(taskId, cutoff, now);
            }
        }
        for (Task task : taskStore.listByStatuses(Set.of(TaskStatus.IDLE), PAGE)) {
            if (taskStore.isV2Task(task.id())) {
                continue;
            }
            if (!lastActivity(task).isBefore(cutoff)) {
                continue;
            }
            if (roundStore.findLiveByTask(task.id()).isPresent()
                    || !validationStore.findOpenByTask(task.id()).isEmpty()) {
                continue;
            }
            machine.archiveIdle(task.id());
        }
    }

    private Instant lastActivity(Task task)
    {
        Instant latest = task.createdAt();
        for (ThreadTurnStatus status : TERMINAL_TURNS) {
            List<ThreadTurn> turns = turnStore.listTurnsByExactTaskIdAndStatus(task.id(), status, 1);
            for (ThreadTurn turn : turns) {
                Instant at = turn.finishedAt() != null ? turn.finishedAt() : turn.createdAt();
                if (at != null && at.isAfter(latest)) {
                    latest = at;
                }
            }
        }
        return latest;
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
