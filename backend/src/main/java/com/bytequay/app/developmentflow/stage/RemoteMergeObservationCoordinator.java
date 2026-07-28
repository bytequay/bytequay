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

import com.bytequay.app.developmentflow.execution.merge.SqliteMergeOperationStore;
import com.bytequay.app.developmentflow.stage.RemoteObservationConsumer.Candidate;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteMergeRuntimeStore;
import com.bytequay.app.service.threads.TaskCommandExecutor;

import java.time.Clock;

import static java.util.Objects.requireNonNull;

/** Reconciles every live merge against one accepted snapshot before terminal handoff. */
public final class RemoteMergeObservationCoordinator
{
    private final SqliteRemoteMergeRuntimeStore runtime;
    private final SqliteMergeOperationStore operations;
    private final RemoteTerminalObservationCoordinator terminal;
    private final Clock clock;

    public RemoteMergeObservationCoordinator(
            SqliteRemoteMergeRuntimeStore runtime,
            SqliteMergeOperationStore operations,
            RemoteTerminalObservationCoordinator terminal,
            Clock clock)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.operations = requireNonNull(operations, "operations is null");
        this.terminal = requireNonNull(terminal, "terminal is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public void acceptInCommand(Candidate candidate)
    {
        requireNonNull(candidate, "candidate is null");
        TaskCommandExecutor.requireCurrent(candidate.context().taskId());
        for (String operationId : runtime.findLiveMergeOperationIds(
                candidate.context().stageId())) {
            operations.reconcileAcceptedObservation(operationId, clock.instant());
        }
        if (candidate.observation().prState()
                == RemoteObservationOperationHandler.PrState.MERGED
                || candidate.observation().prState()
                == RemoteObservationOperationHandler.PrState.CLOSED) {
            terminal.acceptInCommand(candidate.evidence().snapshotId());
        }
    }
}
