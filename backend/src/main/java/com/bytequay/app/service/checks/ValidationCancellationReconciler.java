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
package com.bytequay.app.service.checks;

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.bytequay.app.repository.ValidationPassStore.PendingValidationCancel;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;

import static java.util.Objects.requireNonNull;

/**
 * Drives cancellation-requested validation claims to a proven stop. A
 * cancel request alone proves nothing: the executor is confirmed absent
 * only when it is not in flight in this JVM and either it ran here (our
 * process identity owns the claim) or its lease has expired. Until that
 * proof, this reconciler keeps interrupting the worker — the shell
 * runner kills the owned child process on interrupt — from startup and
 * a periodic sweep, recording each durable attempt.
 *
 * <p>Past the persisted deadline with absence still unprovable, the
 * task is parked visibly rather than ever admitting new work beside a
 * possibly live validation; stopped tasks keep their axes and only
 * record the failure.
 */
public class ValidationCancellationReconciler
{
    private static final Logger log = LoggerFactory.getLogger(ValidationCancellationReconciler.class);

    private final ValidationPassStore store;
    private final ValidationExecutorRegistry registry;
    private final TaskStore taskStore;
    private final TaskPhaseMachine machine;
    private final String executorIdentity;

    public ValidationCancellationReconciler(
            ValidationPassStore store,
            ValidationExecutorRegistry registry,
            TaskStore taskStore,
            TaskPhaseMachine machine)
    {
        this.store = requireNonNull(store, "store is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.machine = requireNonNull(machine, "machine is null");
        this.executorIdentity = ProcessHandle.current().pid() + "@" + hostName();
    }

    public void reconcileOnStartup()
    {
        sweep();
    }

    public void sweep()
    {
        for (PendingValidationCancel pending : store.findCancelPending()) {
            try {
                reconcile(pending, Instant.now());
            }
            catch (RuntimeException e) {
                log.warn("validation cancellation reconcile for {} failed: {}",
                        pending.claimKey(), e.getMessage());
            }
        }
    }

    private void reconcile(PendingValidationCancel pending, Instant now)
    {
        if (pending.taskId() != null && taskStore.isV2Task(pending.taskId())) {
            return;
        }
        if (executorAbsent(pending, now)) {
            store.markSuperseded(pending.claimKey(), now);
            return;
        }
        if (registry.requestStop(pending.claimKey())) {
            store.incrementCancelAttempts(pending.claimKey());
        }
        if (pending.deadline() != null && now.isAfter(pending.deadline())) {
            recordStopFailure(pending);
        }
    }

    /** Absence proof: not in flight here, and either this process owned
     *  the claim (so gone is gone) or the durable lease has expired. */
    private boolean executorAbsent(PendingValidationCancel pending, Instant now)
    {
        if (registry.isInFlight(pending.claimKey())) {
            return false;
        }
        if (executorIdentity.equals(pending.executorIdentity())) {
            return true;
        }
        return pending.leaseUntil() == null || !pending.leaseUntil().isAfter(now);
    }

    private void recordStopFailure(PendingValidationCancel pending)
    {
        Task task = pending.taskId() == null
                ? null
                : taskStore.findTaskById(pending.taskId()).orElse(null);
        if (task == null) {
            log.warn("validation claim {} cannot be proven stopped and has no task",
                    pending.claimKey());
            return;
        }
        // Runnable work parks visibly; PAUSED/ARCHIVED/terminal keep both
        // axes — the open cancel-requested claim row is the durable
        // stop-failure record either way.
        boolean runnable = switch (task.status()) {
            case PENDING, RUNNING, IDLE, AWAITING_REVIEW, IN_REVIEW,
                    NEEDS_ATTENTION, ERRORED -> true;
            case PAUSED, ARCHIVED, COMPLETED, REMOTE_CLOSED, CANCELED -> false;
        };
        if (runnable && task.status() != TaskStatus.ERRORED) {
            machine.parkOperational(task.id(), Actor.AGENT, "validation_stop_failed");
        }
        else {
            log.warn("validation claim {} for stopped task {} cannot be proven stopped",
                    pending.claimKey(), task.id());
        }
    }

    private static String hostName()
    {
        try {
            return InetAddress.getLocalHost().getHostName();
        }
        catch (UnknownHostException e) {
            return "localhost";
        }
    }
}
