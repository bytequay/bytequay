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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationTarget;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ParkedObservation;

import java.time.Duration;
import java.time.Instant;

import static java.util.Objects.requireNonNull;

/** Requests durable observations; GitHub I/O still runs only as a ticket. */
public final class RemoteObservationMaintainer
        implements ExecutionPorts.MaintenanceWork
{
    private final SqliteRemoteRuntimeStore store;
    private final RemoteObservationRuntimeCoordinator observations;
    private final DispatchTicketControl tickets;
    private final Duration interval;
    private final int batchSize;

    public RemoteObservationMaintainer(
            SqliteRemoteRuntimeStore store,
            RemoteObservationRuntimeCoordinator observations,
            DispatchTicketControl tickets,
            Duration interval,
            int batchSize)
    {
        this.store = requireNonNull(store, "store is null");
        this.observations = requireNonNull(observations, "observations is null");
        this.tickets = requireNonNull(tickets, "tickets is null");
        this.interval = requireNonNull(interval, "interval is null");
        if (interval.isNegative() || interval.isZero() || batchSize < 1) {
            throw new IllegalArgumentException(
                    "observation interval and batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    @Override
    public void maintain(Instant now)
    {
        requireNonNull(now, "now is null");
        RuntimeException firstFailure = null;
        for (ParkedObservation parked : store.findParkedObservations(
                now.minus(interval), batchSize)) {
            try {
                tickets.resumeDeferred(parked.ticketId());
            }
            catch (RuntimeException failure) {
                firstFailure = accumulate(firstFailure, failure);
            }
        }
        for (ObservationTarget target : store.findDueObservations(
                now.minus(interval), batchSize)) {
            try {
                observations.requestObservation(target.taskId(), target.stageId());
            }
            catch (RuntimeException failure) {
                firstFailure = accumulate(firstFailure, failure);
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private static RuntimeException accumulate(
            RuntimeException first, RuntimeException next)
    {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }
}
