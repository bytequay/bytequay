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
package com.bytequay.app.developmentflow.task;

import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.stage.ReplanHandoff;
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.task.persistence.SqliteTaskControlRuntimeStore;
import com.bytequay.app.developmentflow.task.persistence.SqliteTaskControlRuntimeStore.ReplanContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Stops old-epoch delivery, records quiescence, then invokes the exact Stage handoff. */
@Component
public final class TaskReplanMaintainer
        implements ExecutionPorts.MaintenanceWork
{
    private final SqliteTaskControlRuntimeStore store;
    private final Map<StageKind, ReplanHandoff> handoffs;
    private final CancellationPort cancellations;

    @Autowired
    public TaskReplanMaintainer(
            SqliteTaskControlRuntimeStore store,
            List<ReplanHandoff> handoffs,
            DispatchTicketControl tickets)
    {
        this(store, handoffs, tickets::requestCancel);
    }

    public TaskReplanMaintainer(
            SqliteTaskControlRuntimeStore store,
            List<ReplanHandoff> handoffs,
            CancellationPort cancellations)
    {
        this.store = requireNonNull(store, "store is null");
        this.cancellations = requireNonNull(cancellations, "cancellations is null");
        EnumMap<StageKind, ReplanHandoff> byKind = new EnumMap<>(StageKind.class);
        for (ReplanHandoff handoff : List.copyOf(
                requireNonNull(handoffs, "handoffs is null"))) {
            if (handoff.sourceKind() == StageKind.CLEANUP
                    || byKind.put(handoff.sourceKind(), handoff) != null) {
                throw new IllegalArgumentException(
                        "invalid duplicate replan owner: " + handoff.sourceKind());
            }
        }
        this.handoffs = Map.copyOf(byKind);
    }

    @Override
    public void maintain(Instant now)
    {
        maintain((String) null, now);
    }

    public void maintain(String requestId, Instant now)
    {
        requireNonNull(now, "now is null");
        RuntimeException first = null;
        for (ReplanContext context : store.pendingReplans()) {
            if (requestId != null && !requestId.equals(context.requestId())) {
                continue;
            }
            try {
                maintainContext(context, now);
            }
            catch (RuntimeException failure) {
                if (first == null) {
                    first = failure;
                }
                else {
                    first.addSuppressed(failure);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private void maintainContext(ReplanContext context, Instant now)
    {
        for (String ticketId : store.liveReplanTicketIds(
                context.taskId(), context.taskEpoch())) {
            if (!cancellations.request(ticketId)) {
                return;
            }
        }
        if (!store.liveReplanTicketIds(
                context.taskId(), context.taskEpoch()).isEmpty()) {
            return;
        }
        if (!store.satisfyBarrier(context.taskId(), context.barrierId(), now)) {
            return;
        }
        ReplanHandoff handoff = handoffs.get(context.sourceStageKind());
        if (handoff == null) {
            throw new IllegalStateException(
                    "No replan handoff for " + context.sourceStageKind());
        }
        handoff.accept(new TaskManager.ReplanCommand(
                context.commandId(), context.requestedBy(), context.taskId(),
                context.taskVersion(), context.taskEpoch(),
                context.sourceStageId(), context.sourceStageGeneration(),
                context.sourceStageVersion(), context.requestId(),
                context.barrierId(),
                id("replan-plan-stage", context.requestId()),
                context.nextPlanGeneration()));
    }

    @FunctionalInterface
    public interface CancellationPort
    {
        boolean request(String ticketId);
    }
}
