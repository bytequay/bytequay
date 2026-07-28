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

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Synchronous writer for one Cleanup Stage generation. */
public final class CleanupStageManager
        extends StageManager
{
    public CleanupStageManager(TaskCommandExecutor commands, Store store)
    {
        super(commands, store, StageKind.CLEANUP);
    }

    CommandResult<State> openFromCancellationInCommand(TaskManager.StageOpening opening)
    {
        return openInCommand(
                opening, StageCheckpoint.WAITING_QUIESCENCE, "OPEN_CANCELED_CLEANUP");
    }

    CommandResult<State> openFromTerminalInCommand(TaskManager.StageOpening opening)
    {
        return openInCommand(
                opening, StageCheckpoint.WAITING_QUIESCENCE, "OPEN_TERMINAL_CLEANUP");
    }

    CommandResult<State> acceptQuiescenceInCommand(
            TaskManager.AcceptedCleanupQuiescence accepted)
    {
        requireNonNull(accepted, "accepted is null");
        return moveWithProofInCommand(
                new Command(
                        accepted.commandId(), accepted.actor(), accepted.taskId(),
                        accepted.taskEpoch(), accepted.stageId(),
                        accepted.stageGeneration(), accepted.stageVersion()),
                accepted.barrierId(), "ACCEPT_CLEANUP_QUIESCENCE",
                StageCheckpoint.WAITING_QUIESCENCE, StageCheckpoint.CLEANING);
    }

    Optional<CommandResult<State>> replayQuiescenceInCommand(
            TaskManager.CleanupQuiescenceCommand command)
    {
        requireNonNull(command, "command is null");
        return replayStructuralInCommand(
                new Command(
                        command.commandId(), command.actor(), command.taskId(),
                        command.taskEpoch(), command.cleanupStageId(),
                        command.cleanupStageGeneration(),
                        command.expectedCleanupStageVersion()),
                "ACCEPT_CLEANUP_QUIESCENCE", null, command.barrierId(),
                StageCheckpoint.WAITING_QUIESCENCE);
    }

    CompletionResult acceptCleanupCompleteInCommand(ResultCommand command)
    {
        ResultResolution resolution = acceptResultForHandoffInCommand(
                command, "ACCEPT_CLEANUP_COMPLETE",
                StageCheckpoint.CLEANING, StageCheckpoint.COMPLETED);
        CommandResult<State> result = resolution.result();
        if (!resolution.wasAccepted()) {
            return new CompletionResult(result, Optional.empty());
        }
        State state = result.state();
        if (state.kind() != StageKind.CLEANUP
                || state.checkpoint() != StageCheckpoint.COMPLETED
                || state.endReason() != StageEndReason.NORMAL) {
            throw new IllegalStateException("Cleanup completion did not seal Cleanup");
        }
        return new CompletionResult(result, Optional.of(new AcceptedCompletion(
                command.taskId(), command.resultFence(), result.disposition(), state)));
    }

    @Override
    protected boolean accepts(TaskLifecycle lifecycle)
    {
        return lifecycle == TaskLifecycle.CLEANING;
    }

    /** Opaque proof emitted only after accepting the exact Cleanup result. */
    public static final class AcceptedCompletion
    {
        private final String taskId;
        private final ResultFence resultFence;
        private final CommandResult.Disposition disposition;
        private final State stageState;

        private AcceptedCompletion(
                String taskId,
                ResultFence resultFence,
                CommandResult.Disposition disposition,
                State stageState)
        {
            this.taskId = requireNonNull(taskId, "taskId is null");
            this.resultFence = requireNonNull(resultFence, "resultFence is null");
            this.disposition = requireNonNull(disposition, "disposition is null");
            this.stageState = requireNonNull(stageState, "stageState is null");
        }

        public String taskId() { return taskId; }

        public long taskEpoch() { return resultFence.taskEpoch(); }

        public String stageId() { return resultFence.stageId(); }

        public long stageGeneration() { return resultFence.stageGeneration(); }

        public ResultFence resultFence() { return resultFence; }

        public CommandResult.Disposition disposition() { return disposition; }

        public State stageState() { return stageState; }
    }

    public record CompletionResult(
            CommandResult<State> stage,
            Optional<AcceptedCompletion> accepted)
    {
        public CompletionResult
        {
            requireNonNull(stage, "stage is null");
            requireNonNull(accepted, "accepted is null");
            if ((stage.disposition() == CommandResult.Disposition.APPLIED
                    && accepted.isEmpty())
                    || (stage.disposition() == CommandResult.Disposition.SUPERSEDED
                    && accepted.isPresent())) {
                throw new IllegalArgumentException("Cleanup completion proof is inconsistent");
            }
        }
    }
}
