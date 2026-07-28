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

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;

import java.util.Optional;
import java.util.function.Supplier;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.COMMAND_ID_CONFLICT;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.INVALID_STATE;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.NOT_CURRENT_STAGE;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.NOT_FOUND;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_EPOCH;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_GENERATION;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_VERSION;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.WRONG_STAGE_KIND;
import static java.util.Objects.requireNonNull;

/** Common exact-owner and transition enforcement for the four V2 Stage writers. */
public abstract class StageManager
{
    private final TaskCommandExecutor commands;
    private final Store store;
    private final StageKind kind;

    protected StageManager(TaskCommandExecutor commands, Store store, StageKind kind)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.store = requireNonNull(store, "store is null");
        this.kind = requireNonNull(kind, "kind is null");
    }

    final AcceptedSeal sealForTaskCancellationInCommand(
            TaskManager.AcceptedCancellation accepted)
    {
        requireNonNull(accepted, "accepted is null");
        CommandResult<State> stage = seal(
                accepted.commandId(), accepted.actor(), accepted.taskId(),
                accepted.taskEpoch(), accepted.sourceStageId(),
                accepted.sourceStageGeneration(), accepted.sourceStageVersion(),
                accepted.barrierId(), "SEAL_FOR_TASK_CANCELLATION",
                StageEndReason.TASK_CANCELED, TaskLifecycle.CANCELING);
        return new AcceptedSeal(
                stage, accepted.taskId(), accepted.sourceStageId(),
                accepted.sourceStageGeneration(), StageEndReason.TASK_CANCELED,
                accepted.barrierId());
    }

    final AcceptedSeal sealForReplanInCommand(TaskManager.AcceptedReplan accepted)
    {
        requireNonNull(accepted, "accepted is null");
        if (kind == StageKind.CLEANUP) {
            throw rejected(INVALID_STATE, "Cleanup cannot be superseded by replan");
        }
        CommandResult<State> stage = seal(
                accepted.commandId(), accepted.actor(), accepted.taskId(),
                accepted.sourceTaskEpoch(), accepted.sourceStageId(),
                accepted.sourceStageGeneration(), accepted.sourceStageVersion(),
                accepted.replanRequestId(), "SEAL_FOR_REPLAN",
                StageEndReason.SUPERSEDED_BY_REPLAN, TaskLifecycle.ACTIVE);
        return new AcceptedSeal(
                stage, accepted.taskId(), accepted.sourceStageId(),
                accepted.sourceStageGeneration(), StageEndReason.SUPERSEDED_BY_REPLAN,
                accepted.replanRequestId());
    }

    protected abstract boolean accepts(TaskLifecycle lifecycle);

    final StageKind kind()
    {
        return kind;
    }

    final CommandResult<State> openInCommand(
            TaskManager.StageOpening opening,
            StageCheckpoint initialCheckpoint,
            String cause)
    {
        requireNonNull(opening, "opening is null");
        requireNonNull(initialCheckpoint, "initialCheckpoint is null");
        TaskCommandExecutor.requireCurrent(opening.taskId());
        if (opening.kind() != kind || !initialCheckpoint.belongsTo(kind)
                || !opening.stageId().equals(opening.taskState().currentStageId())
                || opening.taskEpoch() != opening.taskState().epoch()) {
            throw rejected(INVALID_STATE, "Task did not authorize this Stage opening");
        }

        Optional<CommandReceipt> duplicate = store.findCommandResult(
                opening.taskId(), opening.stageId(), opening.commandId());
        if (duplicate.isPresent()) {
            return CommandResult.duplicate(requireReceipt(
                    duplicate.orElseThrow(), opening.actor(), opening.taskId(), opening.stageId(),
                    opening.stageGeneration(), null, null, null, null,
                    cause, opening.subjectFence(),
                    opening.proofId()).state());
        }

        State state = new State(
                opening.stageId(),
                opening.taskId(),
                kind,
                opening.stageGeneration(),
                0,
                initialCheckpoint,
                null,
                null);
        return CommandResult.applied(store.create(
                opening.commandId(), cause, opening.actor(),
                null, null, null, null, opening.subjectFence(),
                opening.proofId(), state));
    }

    protected final CommandResult<State> execute(
            Command command, Supplier<CommandResult<State>> work)
    {
        requireNonNull(command, "command is null");
        requireNonNull(work, "work is null");
        return commands.execute(command.taskId(), work);
    }

    protected final CommandResult<State> execute(
            ResultCommand command, Supplier<CommandResult<State>> work)
    {
        requireNonNull(command, "command is null");
        requireNonNull(work, "work is null");
        return commands.execute(command.taskId(), work);
    }

    protected final <T> T executeOwnerCommand(String taskId, Supplier<T> work)
    {
        requireText(taskId, "taskId");
        requireNonNull(work, "work is null");
        return commands.execute(taskId, work);
    }

    protected final OwnerState requireOwnerInCommand(String taskId, String stageId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        return loadOwner(taskId, stageId);
    }

    protected final CommandResult<State> moveInCommand(
            Command command,
            String cause,
            StageCheckpoint source,
            StageCheckpoint target)
    {
        requireNonNull(command, "command is null");
        TaskCommandExecutor.requireCurrent(command.taskId());
        Optional<CommandReceipt> duplicate = store.findCommandResult(
                command.taskId(), command.stageId(), command.commandId());
        if (duplicate.isPresent()) {
            return CommandResult.duplicate(requireReceipt(
                    duplicate.orElseThrow(), command.actor(), command.taskId(), command.stageId(),
                    command.expectedStageGeneration(),
                    command.expectedTaskEpoch(), command.expectedStageGeneration(),
                    command.expectedStageVersion(), source, cause, null, null)
                    .state());
        }

        OwnerState owner = loadOwner(command.taskId(), command.stageId());
        validate(command, owner);
        if (!accepts(owner.taskLifecycle())) {
            throw rejected(INVALID_STATE,
                    kind + " Stage cannot run while Task is " + owner.taskLifecycle());
        }
        State current = owner.stage();
        if (current.checkpoint() != source || !source.allowsStructuralTransition(target)) {
            throw rejected(INVALID_STATE,
                    "Cannot move " + kind + " Stage from " + current.checkpoint()
                            + " to " + target);
        }
        return applied(
                command.commandId(), cause, command.actor(),
                command.expectedTaskEpoch(), command.expectedStageGeneration(),
                command.expectedStageVersion(), source,
                current, target, current.pendingResult(), null, null);
    }

    protected final CommandResult<State> acceptResultInCommand(
            ResultCommand command,
            String cause,
            StageCheckpoint source,
            StageCheckpoint target)
    {
        return resolveResultInCommand(command, cause, null, source, target).result();
    }

    /** Accepts a result only with one exact immutable owner proof. */
    protected final CommandResult<State> acceptResultWithProofInCommand(
            ResultCommand command,
            String proofId,
            String cause,
            StageCheckpoint source,
            StageCheckpoint target)
    {
        requireText(proofId, "proofId");
        return resolveResultInCommand(command, cause, proofId, source, target).result();
    }

    /**
     * Arms the first exact asynchronous result after a Stage has been opened.
     * Stage identity must exist before a typed Turn can reference it, so this
     * is a separate same-checkpoint owner command rather than part of create.
     */
    protected final CommandResult<State> requestInitialResultInCommand(
            Command command,
            InitialResultOwner resultOwner,
            ResultFence pendingResult,
            String cause,
            StageCheckpoint checkpoint)
    {
        requireNonNull(command, "command is null");
        requireNonNull(resultOwner, "resultOwner is null");
        requireNonNull(pendingResult, "pendingResult is null");
        requireText(cause, "cause");
        TaskCommandExecutor.requireCurrent(command.taskId());
        if (pendingResult.taskEpoch() != command.expectedTaskEpoch()
                || !command.stageId().equals(pendingResult.stageId())
                || pendingResult.stageGeneration()
                    != command.expectedStageGeneration()) {
            throw rejected(INVALID_STATE,
                    "Requested result targets another Stage owner");
        }
        Optional<CommandReceipt> duplicate = store.findCommandResult(
                command.taskId(), command.stageId(), command.commandId());
        if (duplicate.isPresent()) {
            State state = requireReceipt(
                    duplicate.orElseThrow(), command.actor(), command.taskId(),
                    command.stageId(), command.expectedStageGeneration(),
                    command.expectedTaskEpoch(), command.expectedStageGeneration(),
                    command.expectedStageVersion(), checkpoint, cause,
                    pendingResult, null).state();
            if (!pendingResult.equals(state.pendingResult())) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "Result request id was used for another operation");
            }
            return CommandResult.duplicate(state);
        }

        OwnerState owner = loadOwner(command.taskId(), command.stageId());
        validate(command, owner);
        if (!accepts(owner.taskLifecycle())) {
            throw rejected(INVALID_STATE,
                    kind + " Stage cannot run while Task is "
                            + owner.taskLifecycle());
        }
        State current = owner.stage();
        if (current.checkpoint() != checkpoint || current.pendingResult() != null) {
            throw rejected(INVALID_STATE,
                    "Cannot request result from " + current.checkpoint());
        }
        State requested = new State(
                current.id(), current.taskId(), current.kind(), current.generation(),
                current.version() + 1, current.checkpoint(), null, pendingResult);
        return CommandResult.applied(store.requestInitialResult(
                command.commandId(), cause, command.actor(),
                command.expectedTaskEpoch(), command.expectedStageGeneration(),
                command.expectedStageVersion(), checkpoint, resultOwner,
                pendingResult, current, requested));
    }

    /** Accepts one result and atomically arms the next owner operation. */
    protected final CommandResult<State> acceptResultAndRequestNextInCommand(
            ResultCommand acceptedCommand,
            ResultFence nextResult,
            String cause,
            StageCheckpoint source,
            StageCheckpoint target)
    {
        requireNonNull(acceptedCommand, "acceptedCommand is null");
        requireNonNull(nextResult, "nextResult is null");
        TaskCommandExecutor.requireCurrent(acceptedCommand.taskId());
        ResultFence accepted = acceptedCommand.resultFence();
        if (nextResult.taskEpoch() != accepted.taskEpoch()
                || !accepted.stageId().equals(nextResult.stageId())
                || nextResult.stageGeneration() != accepted.stageGeneration()) {
            throw rejected(INVALID_STATE,
                    "Next result targets another Stage owner");
        }
        Optional<CommandReceipt> duplicate = store.findCommandResult(
                acceptedCommand.taskId(), accepted.stageId(),
                acceptedCommand.commandId());
        if (duplicate.isPresent()) {
            State state = requireReceipt(
                    duplicate.orElseThrow(), acceptedCommand.actor(),
                    acceptedCommand.taskId(), accepted.stageId(),
                    accepted.stageGeneration(), null, null, null, null,
                    cause, accepted, null).state();
            if (!nextResult.equals(state.pendingResult())) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "Result command id was used for another successor");
            }
            return CommandResult.duplicate(state);
        }

        OwnerState owner = loadOwner(acceptedCommand.taskId(), accepted.stageId());
        State current = owner.stage();
        if (!matchesResult(owner, accepted)
                || current.checkpoint() != source
                || !source.allowsStructuralTransition(target)) {
            return CommandResult.superseded(store.recordSuperseded(
                    acceptedCommand.commandId(), cause, acceptedCommand.actor(),
                    null, null, null, null, accepted, null, current));
        }
        return applied(
                acceptedCommand.commandId(), cause, acceptedCommand.actor(),
                null, null, null, null,
                current, target, nextResult, accepted, null);
    }

    protected final ResultResolution acceptResultForHandoffInCommand(
            ResultCommand command,
            String cause,
            StageCheckpoint source,
            StageCheckpoint target)
    {
        return resolveResultInCommand(command, cause, null, source, target);
    }

    /** Consumes terminal child work while pause/archive is draining it. */
    protected final ResultResolution acceptDrainingResultForHandoffInCommand(
            ResultCommand command,
            String cause,
            StageCheckpoint source,
            StageCheckpoint target)
    {
        return resolveResultInCommand(
                command, cause, null, source, target,
                TaskLifecycle.PAUSING, TaskLifecycle.ARCHIVING);
    }

    /**
     * Accepts an exact externally persisted operation fact without requiring
     * the Stage to duplicate that operation as pending state. Cleanup uses
     * this after its ledger and dispatch result have both been persisted.
     */
    protected final ResultResolution acceptFactForHandoffInCommand(
            ResultCommand command,
            String cause,
            StageCheckpoint source,
            StageCheckpoint target)
    {
        requireNonNull(command, "command is null");
        TaskCommandExecutor.requireCurrent(command.taskId());
        ResultFence fact = command.resultFence();
        Optional<CommandReceipt> duplicate = store.findCommandResult(
                command.taskId(), fact.stageId(), command.commandId());
        if (duplicate.isPresent()) {
            CommandReceipt receipt = requireReceipt(
                    duplicate.orElseThrow(), command.actor(), command.taskId(), fact.stageId(),
                    fact.stageGeneration(), null, null, null, null, cause, fact, null);
            return new ResultResolution(
                    CommandResult.duplicate(receipt.state()), receipt.disposition());
        }

        OwnerState owner = loadOwner(command.taskId(), fact.stageId());
        State current = owner.stage();
        if (!matchesFact(owner, fact)
                || current.checkpoint() != source
                || !source.allowsStructuralTransition(target)) {
            return new ResultResolution(CommandResult.superseded(store.recordSuperseded(
                    command.commandId(), cause, command.actor(),
                    null, null, null, null, fact, null, current)),
                    CommandResult.Disposition.SUPERSEDED);
        }
        return new ResultResolution(applied(
                command.commandId(), cause, command.actor(),
                null, null, null, null,
                current, target, null, fact, null),
                CommandResult.Disposition.APPLIED);
    }

    private ResultResolution resolveResultInCommand(
            ResultCommand command,
            String cause,
            String proofId,
            StageCheckpoint source,
            StageCheckpoint target,
            TaskLifecycle... drainingLifecycles)
    {
        requireNonNull(command, "command is null");
        TaskCommandExecutor.requireCurrent(command.taskId());
        Optional<CommandReceipt> duplicate = store.findCommandResult(
                command.taskId(), command.resultFence().stageId(), command.commandId());
        if (duplicate.isPresent()) {
            CommandReceipt receipt = requireReceipt(
                    duplicate.orElseThrow(), command.actor(), command.taskId(),
                    command.resultFence().stageId(),
                    command.resultFence().stageGeneration(),
                    null, null, null, null, cause,
                    command.resultFence(), proofId);
            return new ResultResolution(
                    CommandResult.duplicate(receipt.state()), receipt.disposition());
        }

        ResultFence result = command.resultFence();
        OwnerState owner = loadOwner(command.taskId(), result.stageId());
        State current = owner.stage();
        if (!matchesResult(owner, result, drainingLifecycles)
                || current.checkpoint() != source
                || !source.allowsStructuralTransition(target)) {
            return new ResultResolution(CommandResult.superseded(store.recordSuperseded(
                    command.commandId(), cause, command.actor(),
                    null, null, null, null, result, proofId, current)),
                    CommandResult.Disposition.SUPERSEDED);
        }
        return new ResultResolution(applied(
                command.commandId(), cause, command.actor(),
                null, null, null, null,
                current, target, null, result, proofId),
                CommandResult.Disposition.APPLIED);
    }

    /** Accept an unforgeable Brain fact emitted by TaskManager in this transaction. */
    protected final CommandResult<State> acceptBrainVerdictInCommand(
            TaskManager.AcceptedBrainVerdict accepted,
            TaskManager.BrainVerdict expectedVerdict,
            String cause,
            StageCheckpoint source,
            StageCheckpoint target)
    {
        requireNonNull(accepted, "accepted is null");
        requireNonNull(expectedVerdict, "expectedVerdict is null");
        if (accepted.verdict() != expectedVerdict) {
            throw rejected(COMMAND_ID_CONFLICT,
                    "Accepted Brain verdict does not match the Stage command");
        }
        TaskCommandExecutor.requireCurrent(accepted.taskId());
        ResultFence fact = accepted.resultFence();
        Optional<CommandReceipt> duplicate = store.findCommandResult(
                accepted.taskId(), fact.stageId(), accepted.commandId());
        if (duplicate.isPresent()) {
            return CommandResult.duplicate(requireReceipt(
                    duplicate.orElseThrow(), accepted.actor(), accepted.taskId(), fact.stageId(),
                    fact.stageGeneration(),
                    null, null, null, null, cause, fact, null)
                    .state());
        }

        OwnerState owner = loadOwner(accepted.taskId(), fact.stageId());
        State current = owner.stage();
        if (!matchesFact(owner, fact)
                || current.checkpoint() != source
                || !source.allowsStructuralTransition(target)) {
            return CommandResult.superseded(store.recordSuperseded(
                    accepted.commandId(), cause, accepted.actor(),
                    null, null, null, null, fact, null, current));
        }
        return applied(
                accepted.commandId(), cause, accepted.actor(),
                null, null, null, null,
                current, target, current.pendingResult(), fact, null);
    }

    protected final CommandResult<State> moveWithProofInCommand(
            Command command,
            String proofId,
            String cause,
            StageCheckpoint source,
            StageCheckpoint target)
    {
        requireNonNull(command, "command is null");
        requireText(proofId, "proofId");
        TaskCommandExecutor.requireCurrent(command.taskId());
        Optional<CommandReceipt> duplicate = store.findCommandResult(
                command.taskId(), command.stageId(), command.commandId());
        if (duplicate.isPresent()) {
            return CommandResult.duplicate(requireReceipt(
                    duplicate.orElseThrow(), command.actor(), command.taskId(), command.stageId(),
                    command.expectedStageGeneration(),
                    command.expectedTaskEpoch(), command.expectedStageGeneration(),
                    command.expectedStageVersion(), source, cause, null, proofId).state());
        }

        OwnerState owner = loadOwner(command.taskId(), command.stageId());
        validate(command, owner);
        if (!accepts(owner.taskLifecycle())) {
            throw rejected(INVALID_STATE,
                    kind + " Stage cannot run while Task is " + owner.taskLifecycle());
        }
        State current = owner.stage();
        if (current.checkpoint() != source || !source.allowsStructuralTransition(target)) {
            throw rejected(INVALID_STATE,
                    "Cannot move " + kind + " Stage from " + current.checkpoint()
                            + " to " + target);
        }
        return applied(
                command.commandId(), cause, command.actor(),
                command.expectedTaskEpoch(), command.expectedStageGeneration(),
                command.expectedStageVersion(), source,
                current, target, current.pendingResult(), null, proofId);
    }

    /** Moves to a new exact subject and clears work armed for the superseded
     * subject. Only an owner manager can expose a concrete use case for it. */
    protected final CommandResult<State> moveWithProofClearingPendingInCommand(
            Command command,
            String proofId,
            String cause,
            StageCheckpoint source,
            StageCheckpoint target)
    {
        requireNonNull(command, "command is null");
        requireText(proofId, "proofId");
        TaskCommandExecutor.requireCurrent(command.taskId());
        Optional<CommandReceipt> duplicate = store.findCommandResult(
                command.taskId(), command.stageId(), command.commandId());
        if (duplicate.isPresent()) {
            return CommandResult.duplicate(requireReceipt(
                    duplicate.orElseThrow(), command.actor(), command.taskId(),
                    command.stageId(), command.expectedStageGeneration(),
                    command.expectedTaskEpoch(), command.expectedStageGeneration(),
                    command.expectedStageVersion(), source, cause, null, proofId)
                    .state());
        }

        OwnerState owner = loadOwner(command.taskId(), command.stageId());
        validate(command, owner);
        if (!accepts(owner.taskLifecycle())) {
            throw rejected(INVALID_STATE,
                    kind + " Stage cannot run while Task is "
                            + owner.taskLifecycle());
        }
        State current = owner.stage();
        if (current.checkpoint() != source
                || !source.allowsStructuralTransition(target)) {
            throw rejected(INVALID_STATE,
                    "Cannot move " + kind + " Stage from "
                            + current.checkpoint() + " to " + target);
        }
        return applied(
                command.commandId(), cause, command.actor(),
                command.expectedTaskEpoch(), command.expectedStageGeneration(),
                command.expectedStageVersion(), source,
                current, target, null, null, proofId);
    }

    /** Replays a structural command before an external proof store is consulted. */
    protected final Optional<CommandResult<State>> replayStructuralInCommand(
            Command command,
            String cause,
            ResultFence subjectFence,
            String proofId,
            StageCheckpoint sourceCheckpoint)
    {
        requireNonNull(command, "command is null");
        TaskCommandExecutor.requireCurrent(command.taskId());
        return store.findCommandResult(
                        command.taskId(), command.stageId(), command.commandId())
                .map(receipt -> CommandResult.duplicate(requireReceipt(
                        receipt, command.actor(), command.taskId(), command.stageId(),
                        command.expectedStageGeneration(), command.expectedTaskEpoch(),
                        command.expectedStageGeneration(), command.expectedStageVersion(),
                        sourceCheckpoint, cause, subjectFence, proofId).state()));
    }

    /**
     * Accept a persisted authorization and arm the exact asynchronous result
     * that is allowed to complete the new checkpoint.
     */
    protected final CommandResult<State> moveWithProofAndPendingResultInCommand(
            Command command,
            ResultFence pendingResult,
            String proofId,
            String cause,
            StageCheckpoint source,
            StageCheckpoint target)
    {
        requireNonNull(command, "command is null");
        requireNonNull(pendingResult, "pendingResult is null");
        requireText(proofId, "proofId");
        TaskCommandExecutor.requireCurrent(command.taskId());
        if (pendingResult.taskEpoch() != command.expectedTaskEpoch()
                || !command.stageId().equals(pendingResult.stageId())
                || pendingResult.stageGeneration() != command.expectedStageGeneration()) {
            throw rejected(INVALID_STATE, "Authorization result fence targets another owner");
        }
        Optional<CommandReceipt> duplicate = store.findCommandResult(
                command.taskId(), command.stageId(), command.commandId());
        if (duplicate.isPresent()) {
            return CommandResult.duplicate(requireReceipt(
                    duplicate.orElseThrow(), command.actor(), command.taskId(), command.stageId(),
                    command.expectedStageGeneration(),
                    command.expectedTaskEpoch(), command.expectedStageGeneration(),
                    command.expectedStageVersion(), source,
                    cause, pendingResult, proofId).state());
        }

        OwnerState owner = loadOwner(command.taskId(), command.stageId());
        validate(command, owner);
        if (!accepts(owner.taskLifecycle())) {
            throw rejected(INVALID_STATE,
                    kind + " Stage cannot run while Task is " + owner.taskLifecycle());
        }
        State current = owner.stage();
        if (current.checkpoint() != source
                || current.pendingResult() != null
                || !source.allowsStructuralTransition(target)) {
            throw rejected(INVALID_STATE,
                    "Cannot arm " + kind + " Stage from " + current.checkpoint());
        }
        return applied(
                command.commandId(), cause, command.actor(),
                command.expectedTaskEpoch(), command.expectedStageGeneration(),
                command.expectedStageVersion(), source,
                current, target, pendingResult, pendingResult, proofId);
    }

    /** Arms owner-specific work without inventing another Stage checkpoint. */
    protected final CommandResult<State> armPendingResultInCommand(
            Command command,
            ResultFence pendingResult,
            String proofId,
            String cause,
            StageCheckpoint checkpoint)
    {
        requireNonNull(command, "command is null");
        requireNonNull(pendingResult, "pendingResult is null");
        requireText(proofId, "proofId");
        TaskCommandExecutor.requireCurrent(command.taskId());
        if (pendingResult.taskEpoch() != command.expectedTaskEpoch()
                || !command.stageId().equals(pendingResult.stageId())
                || pendingResult.stageGeneration() != command.expectedStageGeneration()) {
            throw rejected(INVALID_STATE, "Pending result targets another Stage owner");
        }
        Optional<CommandReceipt> duplicate = store.findCommandResult(
                command.taskId(), command.stageId(), command.commandId());
        if (duplicate.isPresent()) {
            return CommandResult.duplicate(requireReceipt(
                    duplicate.orElseThrow(), command.actor(), command.taskId(),
                    command.stageId(), command.expectedStageGeneration(),
                    command.expectedTaskEpoch(), command.expectedStageGeneration(),
                    command.expectedStageVersion(), checkpoint, cause,
                    pendingResult, proofId).state());
        }

        OwnerState owner = loadOwner(command.taskId(), command.stageId());
        validate(command, owner);
        State current = owner.stage();
        if (!accepts(owner.taskLifecycle())
                || current.checkpoint() != checkpoint
                || current.pendingResult() != null) {
            throw rejected(INVALID_STATE,
                    "Cannot arm " + kind + " Stage at " + current.checkpoint());
        }
        State updated = new State(
                current.id(), current.taskId(), current.kind(), current.generation(),
                current.version() + 1, current.checkpoint(), null, pendingResult);
        return CommandResult.applied(store.commit(
                command.commandId(), cause, command.actor(),
                command.expectedTaskEpoch(), command.expectedStageGeneration(),
                command.expectedStageVersion(), checkpoint, pendingResult, proofId,
                current, updated));
    }

    /**
     * Arms the mandatory review of a material Plan edit. The persistence
     * boundary uses a dedicated receipt because the generic Stage receipt
     * cause set is intentionally closed.
     */
    protected final CommandResult<State> requestEditedPlanReviewInCommand(
            Command command, String revisionId, ResultFence pendingResult)
    {
        requireNonNull(command, "command is null");
        requireText(revisionId, "revisionId");
        requireNonNull(pendingResult, "pendingResult is null");
        TaskCommandExecutor.requireCurrent(command.taskId());
        if (kind != StageKind.PLAN
                || pendingResult.taskEpoch() != command.expectedTaskEpoch()
                || !command.stageId().equals(pendingResult.stageId())
                || pendingResult.stageGeneration()
                    != command.expectedStageGeneration()) {
            throw rejected(INVALID_STATE,
                    "Edited Plan review targets another Stage owner");
        }
        Optional<CommandReceipt> duplicate = store.findCommandResult(
                command.taskId(), command.stageId(), command.commandId());
        if (duplicate.isPresent()) {
            State state = requireReceipt(
                    duplicate.orElseThrow(), command.actor(), command.taskId(),
                    command.stageId(), command.expectedStageGeneration(),
                    command.expectedTaskEpoch(), command.expectedStageGeneration(),
                    command.expectedStageVersion(), StageCheckpoint.DRAFTING,
                    "REQUEST_EDITED_PLAN_SELF_REVIEW", pendingResult, revisionId)
                    .state();
            return CommandResult.duplicate(state);
        }

        OwnerState owner = loadOwner(command.taskId(), command.stageId());
        validate(command, owner);
        State current = owner.stage();
        if (!accepts(owner.taskLifecycle())
                || current.checkpoint() != StageCheckpoint.DRAFTING
                || current.pendingResult() != null) {
            throw rejected(INVALID_STATE,
                    "Cannot arm edited Plan review from " + current.checkpoint());
        }
        State requested = new State(
                current.id(), current.taskId(), current.kind(), current.generation(),
                current.version() + 1, StageCheckpoint.SELF_REVIEW, null,
                pendingResult);
        return CommandResult.applied(store.requestEditedPlanReview(
                command.commandId(), command.actor(), command.expectedTaskEpoch(),
                command.expectedStageGeneration(), command.expectedStageVersion(),
                revisionId, pendingResult, current, requested));
    }

    protected final CommandResult<State> retryPlanSelfReviewInCommand(
            ResultCommand command,
            String selfReviewId,
            String replacementTurnId,
            ResultFence replacement)
    {
        requireNonNull(command, "command is null");
        requireText(selfReviewId, "selfReviewId");
        requireText(replacementTurnId, "replacementTurnId");
        requireNonNull(replacement, "replacement is null");
        TaskCommandExecutor.requireCurrent(command.taskId());
        ResultFence failed = command.resultFence();
        if (kind != StageKind.PLAN
                || replacement.taskEpoch() != failed.taskEpoch()
                || !replacement.stageId().equals(failed.stageId())
                || replacement.stageGeneration() != failed.stageGeneration()) {
            throw rejected(INVALID_STATE,
                    "Plan self-review replacement targets another owner");
        }
        Optional<CommandReceipt> duplicate = store.findCommandResult(
                command.taskId(), failed.stageId(), command.commandId());
        if (duplicate.isPresent()) {
            State state = requireReceipt(
                    duplicate.orElseThrow(), command.actor(), command.taskId(),
                    failed.stageId(), failed.stageGeneration(), null, null, null,
                    null, "RETRY_PLAN_SELF_REVIEW", failed, selfReviewId).state();
            if (!replacement.equals(state.pendingResult())) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "Review retry command has another replacement");
            }
            return CommandResult.duplicate(state);
        }
        OwnerState owner = loadOwner(command.taskId(), failed.stageId());
        State current = owner.stage();
        if (!matchesResult(owner, failed)
                || current.checkpoint() != StageCheckpoint.SELF_REVIEW) {
            return CommandResult.superseded(current);
        }
        State requested = new State(
                current.id(), current.taskId(), current.kind(), current.generation(),
                current.version() + 1, current.checkpoint(), null, replacement);
        return CommandResult.applied(store.retryPlanSelfReview(
                command.commandId(), command.actor(), selfReviewId,
                replacementTurnId, failed, replacement, current, requested));
    }

    protected final CommandResult<State> acceptPlanTerminalResultInCommand(
            ResultCommand command,
            String cause,
            String proofId,
            StageCheckpoint checkpoint)
    {
        requireNonNull(command, "command is null");
        requireText(cause, "cause");
        requireText(proofId, "proofId");
        requireNonNull(checkpoint, "checkpoint is null");
        TaskCommandExecutor.requireCurrent(command.taskId());
        ResultFence result = command.resultFence();
        Optional<CommandReceipt> duplicate = store.findCommandResult(
                command.taskId(), result.stageId(), command.commandId());
        if (duplicate.isPresent()) {
            return CommandResult.duplicate(requireReceipt(
                    duplicate.orElseThrow(), command.actor(), command.taskId(),
                    result.stageId(), result.stageGeneration(), null, null, null,
                    null, cause, result, proofId).state());
        }
        OwnerState owner = loadOwner(command.taskId(), result.stageId());
        State current = owner.stage();
        if (kind != StageKind.PLAN || !matchesResult(owner, result)
                || current.checkpoint() != checkpoint) {
            return CommandResult.superseded(current);
        }
        State cleared = new State(
                current.id(), current.taskId(), current.kind(), current.generation(),
                current.version() + 1, current.checkpoint(), null, null);
        return CommandResult.applied(store.acceptPlanTerminalResult(
                command.commandId(), command.actor(), cause, proofId,
                result, current, cleared));
    }

    /** Replaces one exact pending result after its predecessor is superseded. */
    protected final CommandResult<State> replacePendingResultInCommand(
            ResultCommand command,
            ResultFence replacement,
            String proofId,
            String cause,
            StageCheckpoint checkpoint)
    {
        requireNonNull(replacement, "replacement is null");
        if (replacement.equals(command.resultFence())) {
            throw rejected(INVALID_STATE, "Replacement result must name new work");
        }
        if (replacement.taskEpoch() != command.resultFence().taskEpoch()
                || !replacement.stageId().equals(command.resultFence().stageId())
                || replacement.stageGeneration()
                    != command.resultFence().stageGeneration()) {
            throw rejected(INVALID_STATE, "Replacement result targets another owner");
        }
        return finishPendingResultInCommand(
                command, replacement, proofId, cause, checkpoint);
    }

    /** Clears one exact failed/canceled result while preserving its checkpoint. */
    protected final CommandResult<State> clearPendingResultInCommand(
            ResultCommand command,
            String proofId,
            String cause,
            StageCheckpoint checkpoint)
    {
        return finishPendingResultInCommand(command, null, proofId, cause, checkpoint);
    }

    private CommandResult<State> finishPendingResultInCommand(
            ResultCommand command,
            ResultFence replacement,
            String proofId,
            String cause,
            StageCheckpoint checkpoint)
    {
        requireNonNull(command, "command is null");
        requireText(proofId, "proofId");
        TaskCommandExecutor.requireCurrent(command.taskId());
        ResultFence completed = command.resultFence();
        Optional<CommandReceipt> duplicate = store.findCommandResult(
                command.taskId(), completed.stageId(), command.commandId());
        if (duplicate.isPresent()) {
            CommandReceipt receipt = requireReceipt(
                    duplicate.orElseThrow(), command.actor(), command.taskId(),
                    completed.stageId(), completed.stageGeneration(), null, null,
                    null, null, cause, completed, proofId);
            if (receipt.disposition() == CommandResult.Disposition.APPLIED
                    && !same(receipt.state().pendingResult(), replacement)) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "Command id was already used with another replacement result");
            }
            return CommandResult.duplicate(receipt.state());
        }

        OwnerState owner = loadOwner(command.taskId(), completed.stageId());
        State current = owner.stage();
        if (!matchesResult(owner, completed) || current.checkpoint() != checkpoint) {
            return CommandResult.superseded(store.recordSuperseded(
                    command.commandId(), cause, command.actor(), null, null, null,
                    null, completed, proofId, current));
        }
        State updated = new State(
                current.id(), current.taskId(), current.kind(), current.generation(),
                current.version() + 1, current.checkpoint(), null, replacement);
        return CommandResult.applied(store.commit(
                command.commandId(), cause, command.actor(), null, null, null,
                null, completed, proofId, current, updated));
    }

    protected final CommandResult<State> sealRemoteObservationInCommand(
            Command command, String proofId, StageEndReason reason)
    {
        requireNonNull(command, "command is null");
        requireText(proofId, "proofId");
        if (kind != StageKind.REMOTE_DEVELOPMENT
                || (reason != StageEndReason.REMOTE_MERGED
                && reason != StageEndReason.REMOTE_CLOSED)) {
            throw rejected(INVALID_STATE, "Remote observation can seal only Remote Development");
        }
        return seal(
                command.commandId(), command.actor(), command.taskId(),
                command.expectedTaskEpoch(), command.stageId(),
                command.expectedStageGeneration(), command.expectedStageVersion(),
                proofId, "ACCEPT_REMOTE_" + (reason == StageEndReason.REMOTE_MERGED
                        ? "MERGED" : "CLOSED"), reason,
                TaskLifecycle.ACTIVE, TaskLifecycle.PAUSED, TaskLifecycle.ARCHIVED);
    }

    private CommandResult<State> seal(
            String commandId,
            String actor,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            long stageVersion,
            String proofId,
            String cause,
            StageEndReason reason,
            TaskLifecycle... allowedTaskLifecycles)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Optional<CommandReceipt> duplicate = store.findCommandResult(
                taskId, stageId, commandId);
        if (duplicate.isPresent()) {
            return CommandResult.duplicate(requireReceipt(
                    duplicate.orElseThrow(), actor, taskId, stageId,
                    stageGeneration,
                    taskEpoch, stageGeneration, stageVersion, null,
                    cause, null, proofId)
                    .state());
        }

        OwnerState owner = loadOwner(taskId, stageId);
        validate(new Command(
                commandId, actor, taskId, taskEpoch, stageId,
                stageGeneration, stageVersion), owner);
        if (allowedTaskLifecycles.length > 0
                && !isOneOf(owner.taskLifecycle(), allowedTaskLifecycles)) {
            throw rejected(INVALID_STATE,
                    reason + " is not allowed while Task is " + owner.taskLifecycle());
        }

        State current = owner.stage();
        StageCheckpoint checkpoint = switch (reason) {
            case REMOTE_MERGED, REMOTE_CLOSED -> StageCheckpoint.COMPLETED;
            case SUPERSEDED_BY_REPLAN, TASK_CANCELED -> current.checkpoint();
            case NORMAL -> throw rejected(INVALID_STATE, "Use a structural completion command");
        };
        State updated = new State(
                current.id(),
                current.taskId(),
                current.kind(),
                current.generation(),
                current.version() + 1,
                checkpoint,
                reason,
                null);
        return CommandResult.applied(store.commit(
                commandId, cause, actor,
                taskEpoch, stageGeneration, stageVersion, null,
                null, proofId, current, updated));
    }

    private CommandResult<State> applied(
            String commandId,
            String cause,
            String actor,
            Long expectedTaskEpoch,
            Long expectedStageGeneration,
            Long expectedStageVersion,
            StageCheckpoint sourceCheckpoint,
            State current,
            StageCheckpoint target,
            ResultFence pendingResult,
            ResultFence subjectFence,
            String proofId)
    {
        StageEndReason endReason = target == StageCheckpoint.COMPLETED
                ? StageEndReason.NORMAL
                : null;
        State updated = new State(
                current.id(),
                current.taskId(),
                current.kind(),
                current.generation(),
                current.version() + 1,
                target,
                endReason,
                pendingResult);
        return CommandResult.applied(store.commit(
                commandId, cause, actor,
                expectedTaskEpoch, expectedStageGeneration, expectedStageVersion,
                sourceCheckpoint, subjectFence, proofId, current, updated));
    }

    private OwnerState loadOwner(String taskId, String stageId)
    {
        OwnerState owner = store.findOwner(taskId, stageId)
                .orElseThrow(() -> rejected(NOT_FOUND,
                        "Stage not found: " + stageId + " for Task " + taskId));
        if (!owner.taskId().equals(taskId) || !owner.stage().id().equals(stageId)) {
            throw rejected(NOT_FOUND,
                    "Stage not found: " + stageId + " for Task " + taskId);
        }
        return owner;
    }

    private void validate(Command command, OwnerState owner)
    {
        State current = owner.stage();
        validateKindAndOpen(current);
        if (owner.taskEpoch() != command.expectedTaskEpoch()) {
            throw rejected(STALE_EPOCH, "Stale Task epoch for " + command.taskId());
        }
        if (!command.stageId().equals(owner.currentStageId())) {
            throw rejected(NOT_CURRENT_STAGE, "Stage is no longer current: " + command.stageId());
        }
        if (current.generation() != command.expectedStageGeneration()) {
            throw rejected(STALE_GENERATION, "Stale Stage generation: " + command.stageId());
        }
        if (current.version() != command.expectedStageVersion()) {
            throw rejected(STALE_VERSION, "Stale Stage version: " + command.stageId());
        }
    }

    private boolean matchesResult(OwnerState owner, ResultFence result)
    {
        return matchesResult(owner, result, new TaskLifecycle[0]);
    }

    private boolean matchesResult(
            OwnerState owner,
            ResultFence result,
            TaskLifecycle... additionalLifecycles)
    {
        State current = owner.stage();
        return current.kind() == kind
                && current.endReason() == null
                && (accepts(owner.taskLifecycle())
                    || isOneOf(owner.taskLifecycle(), additionalLifecycles))
                && owner.taskEpoch() == result.taskEpoch()
                && result.stageId().equals(owner.currentStageId())
                && current.generation() == result.stageGeneration()
                && result.equals(current.pendingResult());
    }

    private boolean matchesFact(OwnerState owner, ResultFence fact)
    {
        State current = owner.stage();
        return current.kind() == kind
                && current.endReason() == null
                && accepts(owner.taskLifecycle())
                && owner.taskEpoch() == fact.taskEpoch()
                && fact.stageId().equals(owner.currentStageId())
                && current.generation() == fact.stageGeneration();
    }

    private void validateKindAndOpen(State current)
    {
        if (current.kind() != kind) {
            throw rejected(WRONG_STAGE_KIND,
                    "Expected " + kind + " Stage, got " + current.kind());
        }
        if (current.endReason() != null) {
            throw rejected(INVALID_STATE, "Stage is already sealed: " + current.id());
        }
    }

    private static CommandRejectedException rejected(
            CommandRejectedException.Reason reason, String message)
    {
        return new CommandRejectedException(reason, message);
    }

    private static CommandReceipt requireReceipt(
            CommandReceipt receipt,
            String actor,
            String taskId,
            String stageId,
            long stageGeneration,
            Long expectedTaskEpoch,
            Long expectedStageGeneration,
            Long expectedStageVersion,
            StageCheckpoint sourceCheckpoint,
            String cause,
            ResultFence subjectFence,
            String proofId)
    {
        if (!receipt.actor().equals(actor)
                || !receipt.taskId().equals(taskId)
                || !receipt.state().id().equals(stageId)
                || receipt.state().generation() != stageGeneration
                || !same(receipt.expectedTaskEpoch(), expectedTaskEpoch)
                || !same(receipt.expectedStageGeneration(), expectedStageGeneration)
                || !same(receipt.expectedStageVersion(), expectedStageVersion)
                || receipt.sourceCheckpoint() != sourceCheckpoint
                || !receipt.cause().equals(cause)
                || !same(receipt.subjectFence(), subjectFence)
                || !same(receipt.proofId(), proofId)) {
            throw rejected(COMMAND_ID_CONFLICT,
                    "Command id was already used for another Stage command");
        }
        return receipt;
    }

    private static boolean same(Object left, Object right)
    {
        return left == null ? right == null : left.equals(right);
    }

    private static boolean isOneOf(TaskLifecycle actual, TaskLifecycle... expected)
    {
        for (TaskLifecycle candidate : expected) {
            if (actual == candidate) {
                return true;
            }
        }
        return false;
    }

    public record Command(
            String commandId,
            String actor,
            String taskId,
            long expectedTaskEpoch,
            String stageId,
            long expectedStageGeneration,
            long expectedStageVersion)
    {
        public Command
        {
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(taskId, "taskId");
            requireText(stageId, "stageId");
            if (expectedTaskEpoch < 1) {
                throw new IllegalArgumentException("expectedTaskEpoch must be positive");
            }
            if (expectedStageGeneration < 1) {
                throw new IllegalArgumentException("expectedStageGeneration must be positive");
            }
            if (expectedStageVersion < 0) {
                throw new IllegalArgumentException("expectedStageVersion is negative");
            }
        }
    }

    /** Result commands intentionally have no aggregate-version field. */
    public record ResultCommand(
            String commandId, String actor, String taskId, ResultFence resultFence)
    {
        public ResultCommand
        {
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(taskId, "taskId");
            requireNonNull(resultFence, "resultFence is null");
            requireText(resultFence.stageId(), "resultFence.stageId");
        }
    }

    /** Exact typed Turn that owns a Stage's first asynchronous result. */
    public record InitialResultOwner(InitialResultOwnerKind kind, String id)
    {
        public InitialResultOwner
        {
            requireNonNull(kind, "kind is null");
            requireText(id, "id");
        }
    }

    public enum InitialResultOwnerKind
    {
        TASK_TURN,
        STAGE_TURN
    }

    public record OwnerState(
            String taskId,
            TaskLifecycle taskLifecycle,
            long taskEpoch,
            String currentStageId,
            State stage)
    {
        public OwnerState
        {
            requireText(taskId, "taskId");
            requireNonNull(taskLifecycle, "taskLifecycle is null");
            if (taskEpoch < 1) {
                throw new IllegalArgumentException("taskEpoch must be positive");
            }
            if (!taskId.equals(requireNonNull(stage, "stage is null").taskId())) {
                throw new IllegalArgumentException("Stage belongs to another Task");
            }
            if (currentStageId != null && currentStageId.isBlank()) {
                throw new IllegalArgumentException("currentStageId is blank");
            }
        }
    }

    public record State(
            String id,
            String taskId,
            StageKind kind,
            long generation,
            long version,
            StageCheckpoint checkpoint,
            StageEndReason endReason,
            ResultFence pendingResult)
    {
        public State
        {
            requireText(id, "id");
            requireText(taskId, "taskId");
            requireNonNull(kind, "kind is null");
            requireNonNull(checkpoint, "checkpoint is null");
            if (generation < 1) {
                throw new IllegalArgumentException("generation must be positive");
            }
            if (version < 0) {
                throw new IllegalArgumentException("version is negative");
            }
            if (!checkpoint.belongsTo(kind)) {
                throw new IllegalArgumentException("Checkpoint does not belong to " + kind);
            }
            if ((checkpoint == StageCheckpoint.COMPLETED) != (endReason != null)
                    && endReason != StageEndReason.SUPERSEDED_BY_REPLAN
                    && endReason != StageEndReason.TASK_CANCELED) {
                throw new IllegalArgumentException("Stage completion identity is inconsistent");
            }
            if (endReason == StageEndReason.NORMAL
                    && checkpoint != StageCheckpoint.COMPLETED) {
                throw new IllegalArgumentException("Normal completion requires COMPLETED");
            }
            if ((endReason == StageEndReason.SUPERSEDED_BY_REPLAN
                    || endReason == StageEndReason.TASK_CANCELED)
                    && checkpoint == StageCheckpoint.COMPLETED) {
                throw new IllegalArgumentException("Abnormal seal must preserve an open checkpoint");
            }
            if ((endReason == StageEndReason.REMOTE_MERGED
                    || endReason == StageEndReason.REMOTE_CLOSED)
                    && kind != StageKind.REMOTE_DEVELOPMENT) {
                throw new IllegalArgumentException("Remote seal requires Remote Development");
            }
            if (endReason != null && pendingResult != null) {
                throw new IllegalArgumentException("Sealed Stage cannot await a result");
            }
            if (pendingResult != null
                    && (!id.equals(pendingResult.stageId())
                    || generation != pendingResult.stageGeneration())) {
                throw new IllegalArgumentException("Pending result has a stale Stage identity");
            }
        }
    }

    public record CommandReceipt(
            String taskId,
            State state,
            String cause,
            String actor,
            Long expectedTaskEpoch,
            Long expectedStageGeneration,
            Long expectedStageVersion,
            StageCheckpoint sourceCheckpoint,
            ResultFence subjectFence,
            String proofId,
            CommandResult.Disposition disposition)
    {
        public CommandReceipt
        {
            requireText(taskId, "taskId");
            requireNonNull(state, "state is null");
            requireText(cause, "cause");
            requireText(actor, "actor");
            requireNonNull(disposition, "disposition is null");
            if (expectedTaskEpoch != null && expectedTaskEpoch < 1) {
                throw new IllegalArgumentException("expectedTaskEpoch must be positive");
            }
            if (expectedStageGeneration != null && expectedStageGeneration < 1) {
                throw new IllegalArgumentException(
                        "expectedStageGeneration must be positive");
            }
            if (expectedStageVersion != null && expectedStageVersion < 0) {
                throw new IllegalArgumentException("expectedStageVersion is negative");
            }
            if ((expectedTaskEpoch == null) != (expectedStageGeneration == null)
                    || (expectedTaskEpoch == null) != (expectedStageVersion == null)) {
                throw new IllegalArgumentException(
                        "Structural Stage receipt identity is incomplete");
            }
            if (disposition == CommandResult.Disposition.DUPLICATE) {
                throw new IllegalArgumentException(
                        "Durable Stage receipt cannot store a replay disposition");
            }
            if (!taskId.equals(state.taskId())) {
                throw new IllegalArgumentException("Stage receipt belongs to another Task");
            }
            if (proofId != null && proofId.isBlank()) {
                throw new IllegalArgumentException("proofId is blank");
            }
        }
    }

    /** Runtime replay disposition plus the immutable disposition stored by the first call. */
    protected record ResultResolution(
            CommandResult<State> result, CommandResult.Disposition originalDisposition)
    {
        public ResultResolution
        {
            requireNonNull(result, "result is null");
            requireNonNull(originalDisposition, "originalDisposition is null");
        }

        protected boolean wasAccepted()
        {
            return originalDisposition == CommandResult.Disposition.APPLIED;
        }
    }

    /** Opaque proof that the exact current Stage was durably sealed. */
    public static final class AcceptedSeal
    {
        private final CommandResult<State> stage;
        private final String taskId;
        private final String stageId;
        private final long stageGeneration;
        private final StageEndReason reason;
        private final String proofId;

        private AcceptedSeal(
                CommandResult<State> stage,
                String taskId,
                String stageId,
                long stageGeneration,
                StageEndReason reason,
                String proofId)
        {
            this.stage = requireNonNull(stage, "stage is null");
            this.taskId = requireNonNull(taskId, "taskId is null");
            this.stageId = requireNonNull(stageId, "stageId is null");
            this.stageGeneration = stageGeneration;
            this.reason = requireNonNull(reason, "reason is null");
            this.proofId = requireNonNull(proofId, "proofId is null");
        }

        public CommandResult<State> stage() { return stage; }

        public String taskId() { return taskId; }

        public String stageId() { return stageId; }

        public long stageGeneration() { return stageGeneration; }

        public StageEndReason reason() { return reason; }

        public String proofId() { return proofId; }
    }

    /**
     * Stage writer port. Owner lookup may join read-only Task facts; it never
     * exposes Task writes. Commit atomically compares Stage version and records
     * command id.
     */
    public interface Store
    {
        Optional<OwnerState> findOwner(String taskId, String stageId);

        Optional<CommandReceipt> findCommandResult(
                String taskId, String stageId, String commandId);

        State commit(
                String commandId,
                String cause,
                String actor,
                Long expectedTaskEpoch,
                Long expectedStageGeneration,
                Long expectedStageVersion,
                StageCheckpoint sourceCheckpoint,
                ResultFence subjectFence,
                String proofId,
                State expected,
                State updated);

        State create(
                String commandId,
                String cause,
                String actor,
                Long expectedTaskEpoch,
                Long expectedStageGeneration,
                Long expectedStageVersion,
                StageCheckpoint sourceCheckpoint,
                ResultFence subjectFence,
                String proofId,
                State state);

        State recordSuperseded(
                String commandId,
                String cause,
                String actor,
                Long expectedTaskEpoch,
                Long expectedStageGeneration,
                Long expectedStageVersion,
                StageCheckpoint sourceCheckpoint,
                ResultFence subjectFence,
                String proofId,
                State current);

        /**
         * Records the first pending result after Stage creation without a
         * fake same-checkpoint transition. The dedicated immutable receipt
         * breaks the Stage-to-typed-Turn foreign-key cycle at version zero.
         */
        default State requestInitialResult(
                String commandId,
                String cause,
                String actor,
                long expectedTaskEpoch,
                long expectedStageGeneration,
                long expectedStageVersion,
                StageCheckpoint checkpoint,
                InitialResultOwner resultOwner,
                ResultFence pendingResult,
                State expected,
                State requested)
        {
            throw new UnsupportedOperationException(
                    "Initial Stage result requests are not supported");
        }

        default State requestEditedPlanReview(
                String commandId,
                String actor,
                long expectedTaskEpoch,
                long expectedStageGeneration,
                long expectedStageVersion,
                String revisionId,
                ResultFence pendingResult,
                State expected,
                State requested)
        {
            throw new UnsupportedOperationException(
                    "Edited Plan review requests are not supported");
        }

        default State retryPlanSelfReview(
                String commandId,
                String actor,
                String selfReviewId,
                String replacementTurnId,
                ResultFence failed,
                ResultFence replacement,
                State expected,
                State requested)
        {
            throw new UnsupportedOperationException(
                    "Plan self-review retries are not supported");
        }

        default State acceptPlanTerminalResult(
                String commandId,
                String actor,
                String cause,
                String proofId,
                ResultFence result,
                State expected,
                State cleared)
        {
            throw new UnsupportedOperationException(
                    "Plan terminal results are not supported");
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
