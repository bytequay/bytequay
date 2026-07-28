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
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteMergeRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteMergeRuntimeStore.TerminalContext;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;

import static java.util.Objects.requireNonNull;

/** Routes only accepted merged/closed snapshots through the Task-owned handoff. */
@Component
public final class RemoteTerminalObservationCoordinator
{
    private static final String ACTOR = "remote-observer";

    private final SqliteRemoteMergeRuntimeStore store;
    private final SqliteMergeOperationStore merges;
    private final RemoteTerminalToCleanupHandoff handoff;
    private final Clock clock;

    @Autowired
    public RemoteTerminalObservationCoordinator(
            SqliteRemoteMergeRuntimeStore store,
            SqliteMergeOperationStore merges,
            RemoteTerminalToCleanupHandoff handoff)
    {
        this(store, merges, handoff, Clock.systemUTC());
    }

    RemoteTerminalObservationCoordinator(
            SqliteRemoteMergeRuntimeStore store,
            SqliteMergeOperationStore merges,
            RemoteTerminalToCleanupHandoff handoff,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.merges = requireNonNull(merges, "merges is null");
        this.handoff = requireNonNull(handoff, "handoff is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public Result accept(String snapshotId)
    {
        return accept(snapshotId, false);
    }

    /** Reconciles terminal truth without nesting the observation Task command. */
    public Result acceptInCommand(String snapshotId)
    {
        return accept(snapshotId, true);
    }

    private Result accept(String snapshotId, boolean inCommand)
    {
        TerminalContext context = store.findTerminalContext(snapshotId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "snapshot is not accepted merged/closed remote truth"));
        if (inCommand) {
            TaskCommandExecutor.requireCurrent(context.taskId());
        }
        if (context.accepted()) {
            reconcileMerge(context);
            return new Result(true, context.terminalObservationId(), null);
        }
        String commandId = SqliteMergeOperationStore.id(
                "remote-terminal-command", context.snapshotId());
        String cleanupStageId = SqliteMergeOperationStore.id(
                "remote-terminal-cleanup", context.taskId(), context.snapshotId());
        RemoteTerminalToCleanupHandoff.Command command =
                new RemoteTerminalToCleanupHandoff.Command(
                        new RemoteDevelopmentStageManager.TerminalObservationCommand(
                                new StageManager.Command(
                                        commandId, ACTOR, context.taskId(),
                                        context.taskEpoch(), context.stageId(),
                                        context.stageGeneration(), context.stageVersion()),
                                context.snapshotId(), context.observationRevision(),
                                context.headSha(), context.baseSha(), context.outcome()),
                        new TaskManager.TerminalCleanupCommand(
                                commandId, ACTOR, context.taskId(),
                                context.taskEpoch(), context.taskVersion(),
                                cleanupStageId, context.cleanupGeneration()));
        RemoteTerminalToCleanupHandoff.Result accepted = inCommand
                ? handoff.acceptInCommand(command)
                : handoff.accept(command);
        TerminalContext persisted = store.findTerminalContext(snapshotId)
                .filter(TerminalContext::accepted)
                .orElseThrow(() -> new IllegalStateException(
                        "terminal handoff committed without immutable remote fact"));
        reconcileMerge(persisted);
        return new Result(false, persisted.terminalObservationId(), accepted);
    }

    private void reconcileMerge(TerminalContext context)
    {
        store.findLiveMergeOperationId(
                        context.stageId(), context.headSha(), context.baseSha())
                .ifPresent(operationId ->
                        merges.reconcileAcceptedObservation(
                                operationId, clock.instant()));
    }

    public record Result(
            boolean duplicate,
            String terminalObservationId,
            RemoteTerminalToCleanupHandoff.Result handoff)
    {
        public Result
        {
            requireNonNull(terminalObservationId, "terminalObservationId is null");
            if (duplicate != (handoff == null)) {
                throw new IllegalArgumentException(
                        "only a duplicate terminal observation omits handoff output");
            }
        }
    }
}
