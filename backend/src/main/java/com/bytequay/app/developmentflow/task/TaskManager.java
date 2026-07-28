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

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.stage.CleanupStageManager;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.PlanStageManager;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.stage.StageEndReason;
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.task.creation.ProvisionTarget;
import com.bytequay.app.developmentflow.task.creation.TaskAssignment;
import com.bytequay.app.developmentflow.task.creation.TaskCreationInput;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import com.bytequay.app.service.ids.IdGenerator;
import com.bytequay.app.service.threads.TaskCommandExecutor;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.COMMAND_ID_CONFLICT;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.INVALID_STATE;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.NOT_FOUND;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_EPOCH;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_VERSION;
import static java.util.Objects.requireNonNull;

/**
 * Sole synchronous writer for V2 Task lifecycle, creation, and Task Brain facts.
 */
public final class TaskManager
{
    private final TaskCommandExecutor commands;
    private final Store store;

    public TaskManager(TaskCommandExecutor commands, Store store)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.store = requireNonNull(store, "store is null");
    }

    /** Creates one epoch-one PROVISIONING Task from exact Trunk authority. */
    public TaskCreationResult createTaskInCommand(
            TrunkManager.AuthorizedTaskCreation authorization,
            Path repositoryRoot,
            IdGenerator ids)
    {
        requireNonNull(authorization, "authorization is null");
        requireNonNull(repositoryRoot, "repositoryRoot is null");
        requireNonNull(ids, "ids is null");
        TrunkManager.TaskCreationCommand command = authorization.command();
        TaskCreationInput input = command.input();
        String trunkId = input.assignment().identity().trunkId();
        TaskCommandExecutor.requireCurrent("v2-trunk/" + trunkId);

        Optional<TaskCreationReceipt> persisted = store.findTaskCreation(
                trunkId, command.commandId());
        if (persisted.isPresent()) {
            TaskCreationReceipt receipt = persisted.orElseThrow();
            if (store.findCommandResult(
                    receipt.state().id(), command.commandId()).isPresent()) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "Command id is also used by an ordinary Task command");
            }
            if (authorization.disposition() != CommandResult.Disposition.DUPLICATE) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "Task receipt exists without replayed Trunk authorization");
            }
            ProvisionTarget target = replayTarget(
                    receipt, input.base().repositories());
            if (!store.matchesTaskCreation(
                    command, authorization, receipt, target)) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "Command id was already used for another Task creation");
            }
            return new TaskCreationResult(
                    receipt, target, CommandResult.Disposition.DUPLICATE);
        }

        Path normalizedRoot = repositoryRoot.normalize();
        if (!repositoryRoot.isAbsolute()
                || !store.matchesRepositoryRoot(input, normalizedRoot)) {
            throw rejected(INVALID_STATE,
                    "Repository root does not match the exact Workspace repository");
        }

        long taskSequence = store.nextTaskSequence(trunkId);
        String taskId = ids.newTaskId(trunkId, taskSequence);
        ProvisionTarget target = ProvisionTarget.derive(
                taskId, repositoryRoot, input.base().repositories());
        State state = new State(
                taskId, trunkId, TaskLifecycle.PROVISIONING, 1, 0,
                null, null, null, null, null);
        TaskCreationReceipt receipt = store.createTask(
                command, authorization, state, taskSequence, target);
        return new TaskCreationResult(
                receipt, target, CommandResult.Disposition.APPLIED);
    }

    private static ProvisionTarget replayTarget(
            TaskCreationReceipt receipt,
            TaskAssignment.RepositoryRouting repositories)
    {
        try {
            Path worktree = Path.of(receipt.worktreePath()).normalize();
            Path worktrees = worktree.getParent();
            Path repositoryRoot = worktrees == null ? null : worktrees.getParent();
            if (!worktree.isAbsolute()
                    || worktrees == null
                    || repositoryRoot == null
                    || !receipt.state().id().equals(worktree.getFileName().toString())
                    || worktrees.getFileName() == null
                    || !".worktrees".equals(worktrees.getFileName().toString())) {
                throw new IllegalArgumentException("invalid persisted worktree path");
            }
            ProvisionTarget target = ProvisionTarget.derive(
                    receipt.state().id(), repositoryRoot, repositories);
            if (!receipt.branchName().equals(target.branchName())
                    || !worktree.equals(target.worktreePath())) {
                throw new IllegalArgumentException("persisted target is inconsistent");
            }
            return target;
        }
        catch (RuntimeException failure) {
            throw rejected(COMMAND_ID_CONFLICT,
                    "Persisted Task creation target is invalid");
        }
    }

    public CommandResult<State> requestPause(Command command)
    {
        return execute(
                command, "REQUEST_PAUSE", TaskLifecycle.PAUSING, false, null,
                TaskLifecycle.ACTIVE);
    }

    public CommandResult<State> requestResume(Command command)
    {
        return execute(
                command,
                "REQUEST_RESUME",
                TaskLifecycle.RESUMING,
                false,
                null,
                TaskLifecycle.PAUSED,
                TaskLifecycle.ARCHIVED);
    }

    public CommandResult<State> requestArchive(Command command)
    {
        return execute(
                command, "REQUEST_ARCHIVE", TaskLifecycle.ARCHIVING, false, null,
                TaskLifecycle.ACTIVE);
    }

    public CommandResult<State> requestCancel(Command command)
    {
        return execute(
                command,
                "REQUEST_CANCEL",
                TaskLifecycle.CANCELING,
                true,
                TerminalOutcome.CANCELED,
                TaskLifecycle.ACTIVE,
                TaskLifecycle.PAUSED,
                TaskLifecycle.ARCHIVED);
    }

    AcceptedPause requirePauseCompletionInCommand(PauseCompletionCommand command)
    {
        requireNonNull(command, "command is null");
        TaskCommandExecutor.requireCurrent(command.task().taskId());
        Optional<CommandReceipt> duplicate = findCommandResult(
                command.task().taskId(), command.task().commandId());
        if (duplicate.isPresent()) {
            requireReceipt(
                    duplicate.orElseThrow(), "COMPLETE_PAUSE", command.task().actor(),
                    command.task().expectedEpoch(), command.task().expectedVersion(),
                    null, null, command.barrierId(), null, null, null);
            return new AcceptedPause(command);
        }
        PauseEvidence evidence = store.findPauseEvidence(
                        command.task().taskId(), command.barrierId())
                .orElseThrow(() -> rejected(
                        INVALID_STATE, "Exact satisfied Pause barrier is missing"));
        if (!evidence.matches(command)) {
            throw rejected(INVALID_STATE, "Pause evidence does not match its restore checkpoint");
        }
        State current = load(command.task().taskId());
        validateExpected(command.task(), current);
        if (current.lifecycle() != TaskLifecycle.PAUSING
                || !same(current.currentStageId(), command.stageId())) {
            throw rejected(INVALID_STATE, "Pause completion no longer owns this Task");
        }
        return new AcceptedPause(command);
    }

    CommandResult<State> acceptPauseCompletionInCommand(AcceptedPause accepted)
    {
        requireNonNull(accepted, "accepted is null");
        PauseCompletionCommand command = accepted.command;
        return completeControlInCommand(
                command.task(), "COMPLETE_PAUSE", command.barrierId(),
                TaskLifecycle.PAUSING, TaskLifecycle.PAUSED);
    }

    AcceptedResume requireResumeCompletionInCommand(ResumeCompletionCommand command)
    {
        requireNonNull(command, "command is null");
        TaskCommandExecutor.requireCurrent(command.task().taskId());
        Optional<CommandReceipt> duplicate = findCommandResult(
                command.task().taskId(), command.task().commandId());
        if (duplicate.isPresent()) {
            requireReceipt(
                    duplicate.orElseThrow(), "COMPLETE_RESUME", command.task().actor(),
                    command.task().expectedEpoch(), command.task().expectedVersion(),
                    null, null, command.reconciliationId(), null, null, null);
            return new AcceptedResume(command);
        }
        ResumeEvidence evidence = store.findResumeEvidence(
                        command.task().taskId(), command.reconciliationId())
                .orElseThrow(() -> rejected(
                        INVALID_STATE, "Exact Resume reconciliation is missing"));
        if (!evidence.matches(command)) {
            throw rejected(INVALID_STATE, "Resume evidence does not match its restore checkpoint");
        }
        State current = load(command.task().taskId());
        validateExpected(command.task(), current);
        if (current.lifecycle() != TaskLifecycle.RESUMING
                || !same(current.currentStageId(), command.stageId())) {
            throw rejected(INVALID_STATE, "Resume completion no longer owns this Task");
        }
        return new AcceptedResume(command);
    }

    CommandResult<State> acceptResumeCompletionInCommand(AcceptedResume accepted)
    {
        requireNonNull(accepted, "accepted is null");
        ResumeCompletionCommand command = accepted.command;
        return completeControlInCommand(
                command.task(), "COMPLETE_RESUME", command.reconciliationId(),
                TaskLifecycle.RESUMING, TaskLifecycle.ACTIVE);
    }

    AcceptedArchive requireArchiveCompletionInCommand(ArchiveCompletionCommand command)
    {
        requireNonNull(command, "command is null");
        TaskCommandExecutor.requireCurrent(command.task().taskId());
        Optional<CommandReceipt> duplicate = findCommandResult(
                command.task().taskId(), command.task().commandId());
        if (duplicate.isPresent()) {
            requireReceipt(
                    duplicate.orElseThrow(), "COMPLETE_ARCHIVE", command.task().actor(),
                    command.task().expectedEpoch(), command.task().expectedVersion(),
                    null, null, command.archiveEvidenceId(), null, null, null);
            return new AcceptedArchive(command);
        }
        ArchiveEvidence evidence = store.findArchiveEvidence(
                        command.task().taskId(), command.archiveEvidenceId())
                .orElseThrow(() -> rejected(
                        INVALID_STATE, "Exact Archive liveness evidence is missing"));
        if (!evidence.matches(command)) {
            throw rejected(INVALID_STATE, "Archive evidence does not match the current owner");
        }
        State current = load(command.task().taskId());
        validateExpected(command.task(), current);
        if (current.lifecycle() != TaskLifecycle.ARCHIVING
                || !same(current.currentStageId(), command.stageId())) {
            throw rejected(INVALID_STATE, "Archive completion no longer owns this Task");
        }
        return new AcceptedArchive(command);
    }

    CommandResult<State> acceptArchiveCompletionInCommand(AcceptedArchive accepted)
    {
        requireNonNull(accepted, "accepted is null");
        ArchiveCompletionCommand command = accepted.command;
        return completeControlInCommand(
                command.task(), "COMPLETE_ARCHIVE", command.archiveEvidenceId(),
                TaskLifecycle.ARCHIVING, TaskLifecycle.ARCHIVED);
    }

    private CommandResult<State> completeControlInCommand(
            Command command,
            String cause,
            String proofId,
            TaskLifecycle source,
            TaskLifecycle target)
    {
        TaskCommandExecutor.requireCurrent(command.taskId());
        Optional<CommandReceipt> duplicate = findCommandResult(
                command.taskId(), command.commandId());
        if (duplicate.isPresent()) {
            return CommandResult.duplicate(requireReceipt(
                    duplicate.orElseThrow(), cause, command.actor(),
                    command.expectedEpoch(), command.expectedVersion(), null, null,
                    proofId, null, null, null).state());
        }
        State current = load(command.taskId());
        validateExpected(command, current);
        if (current.lifecycle() != source || !source.allows(target)) {
            throw rejected(INVALID_STATE,
                    "Cannot complete Task control from " + current.lifecycle());
        }
        State updated = new State(
                current.id(), current.trunkId(), target, current.epoch(),
                current.version() + 1, current.currentStageId(),
                current.pendingBrainResult(), current.lastBrainVerdict(),
                current.lastBrainResult(), current.terminalIntent());
        return CommandResult.applied(store.commit(
                command.commandId(), cause, command.actor(),
                command.expectedEpoch(), command.expectedVersion(), null, null, proofId,
                null, null, null, current, updated));
    }

    /**
     * Accepts only an exact, persisted ProvisionTaskOperation result and
     * atomically authorizes the initial Plan Stage in the caller transaction.
     */
    public StageOpening acceptProvisioningInCommand(ProvisioningCommand command)
    {
        requireNonNull(command, "command is null");
        TaskCommandExecutor.requireCurrent(command.taskId());

        Optional<CommandReceipt> duplicate = findCommandResult(
                command.taskId(), command.commandId());
        if (duplicate.isPresent()) {
            State state = requireReceipt(
                    duplicate.orElseThrow(), "ACCEPT_PROVISIONING",
                    command.actor(), command.resultFence().taskEpoch(),
                    command.expectedTaskVersion(),
                    command.resultFence(), null, command.operationId(),
                    command.planStageId(), StageKind.PLAN,
                    command.planStageGeneration()).state();
            if (!command.planStageId().equals(state.currentStageId())
                    || state.epoch() != command.resultFence().taskEpoch()) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "Provisioning command id targets another initial Plan");
            }
            return new StageOpening(
                    command.commandId(), command.actor(), command.taskId(), state.epoch(),
                    command.planStageId(), command.planStageGeneration(), StageKind.PLAN,
                    command.resultFence(), command.operationId(),
                    CommandResult.Disposition.DUPLICATE, state);
        }

        State current = load(command.taskId());
        if (current.version() != command.expectedTaskVersion()) {
            throw rejected(STALE_VERSION, "Stale Task version for " + command.taskId());
        }
        ResultFence result = command.resultFence();
        if (current.lifecycle() != TaskLifecycle.PROVISIONING
                || current.epoch() != result.taskEpoch()
                || current.currentStageId() != null) {
            throw rejected(INVALID_STATE,
                    "Provisioning can activate only its exact unstarted Task");
        }
        ProvisioningResult evidence = store.findAcceptedProvisioningResult(
                        command.taskId(), command.operationId())
                .orElseThrow(() -> rejected(
                        INVALID_STATE, "Accepted provisioning evidence is missing"));
        if (!evidence.matches(command)) {
            throw rejected(INVALID_STATE,
                    "Provisioning result does not match the persisted operation fence");
        }

        State updated = new State(
                current.id(), current.trunkId(), TaskLifecycle.ACTIVE,
                current.epoch(), current.version() + 1, command.planStageId(),
                null, current.lastBrainVerdict(), current.lastBrainResult(),
                current.terminalIntent());
        State accepted = store.commit(
                command.commandId(), "ACCEPT_PROVISIONING", command.actor(),
                command.resultFence().taskEpoch(), command.expectedTaskVersion(),
                command.resultFence(), null, command.operationId(),
                command.planStageId(), StageKind.PLAN,
                command.planStageGeneration(), current, updated);
        return new StageOpening(
                command.commandId(), command.actor(), command.taskId(), accepted.epoch(),
                command.planStageId(), command.planStageGeneration(), StageKind.PLAN,
                command.resultFence(), command.operationId(),
                CommandResult.Disposition.APPLIED, accepted);
    }

    /**
     * Accepts the raw, strictly decoded provisioning result in the Task-owned
     * store, freezes the Task code identity, and activates the initial Plan in
     * the same command transaction. The caller may then ask PlanStageManager
     * to create the Stage before the transaction commits.
     */
    public StageOpening acceptProvisionedCodeInCommand(
            ProvisionedCode code,
            String commandId,
            String actor,
            String planStageId,
            long planStageGeneration)
    {
        requireNonNull(code, "code is null");
        requireText(commandId, "commandId");
        requireText(actor, "actor");
        requireText(planStageId, "planStageId");
        TaskCommandExecutor.requireCurrent(code.taskId());

        State current = load(code.taskId());
        if (current.lifecycle() != TaskLifecycle.PROVISIONING
                || current.epoch() != code.taskEpoch()
                || current.version() != 0
                || current.currentStageId() != null) {
            throw rejected(INVALID_STATE,
                    "Provisioning result no longer owns an unstarted Task");
        }
        ProvisioningResult accepted = store.acceptProvisioningResult(code);
        if (!accepted.matches(code)) {
            throw rejected(INVALID_STATE,
                    "Persisted provisioning result differs from decoded evidence");
        }
        return acceptProvisioningInCommand(new ProvisioningCommand(
                commandId,
                actor,
                code.taskId(),
                current.version(),
                code.resultFence(),
                planStageId,
                planStageGeneration));
    }

    /** Accepts one terminal provisioning failure and opens a Task blocker. */
    public ProvisioningFailureResult acceptProvisioningFailureInCommand(
            ProvisioningFailure failure)
    {
        requireNonNull(failure, "failure is null");
        TaskCommandExecutor.requireCurrent(failure.taskId());
        Optional<ProvisioningFailureResult> duplicate =
                store.findProvisioningFailure(
                        failure.taskId(), failure.operationId());
        if (duplicate.isPresent()) {
            ProvisioningFailureResult result = duplicate.orElseThrow();
            if (!result.matches(failure)) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "Provisioning failure was accepted with other evidence");
            }
            return result;
        }
        State current = load(failure.taskId());
        if (current.lifecycle() != TaskLifecycle.PROVISIONING
                || current.epoch() != failure.taskEpoch()
                || current.version() != 0
                || current.currentStageId() != null) {
            throw rejected(INVALID_STATE,
                    "Provisioning failure no longer owns an unstarted Task");
        }
        return store.acceptProvisioningFailure(failure);
    }

    /** Reads and freezes the exact satisfied replan request for one handoff. */
    public AcceptedReplan requireSatisfiedReplanInCommand(ReplanCommand command)
    {
        requireNonNull(command, "command is null");
        TaskCommandExecutor.requireCurrent(command.taskId());
        Optional<CommandReceipt> duplicate = findCommandResult(
                command.taskId(), command.commandId());
        if (duplicate.isPresent()) {
            State state = requireReceipt(
                    duplicate.orElseThrow(), "OPEN_REPLAN_PLAN", command.actor(),
                    command.sourceTaskEpoch(), command.expectedTaskVersion(), null, null,
                    command.replanRequestId(), command.nextPlanStageId(), StageKind.PLAN,
                    command.nextPlanStageGeneration()).state();
            if (state.epoch() != command.targetTaskEpoch()
                    || !command.nextPlanStageId().equals(state.currentStageId())) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "Replan command id targets another epoch or Plan Stage");
            }
            return new AcceptedReplan(command, null);
        }
        ReplanEvidence evidence = store.findReplanEvidence(
                        command.taskId(), command.replanRequestId())
                .orElseThrow(() -> rejected(
                        INVALID_STATE, "Satisfied replan evidence is missing"));
        if (!evidence.matches(command)) {
            throw rejected(INVALID_STATE,
                    "Replan request does not match its exact barrier and source Stage");
        }
        State current = load(command.taskId());
        if (current.lifecycle() != TaskLifecycle.ACTIVE
                || current.epoch() != command.sourceTaskEpoch()
                || current.version() != command.expectedTaskVersion()
                || !same(current.currentStageId(), command.sourceStageId())) {
            throw rejected(INVALID_STATE, "Replan source is no longer current");
        }
        return new AcceptedReplan(command, evidence);
    }

    /** Repoints Task after the exact source Stage was sealed by ReplanHandoff. */
    public StageOpening acceptReplanStageInCommand(
            AcceptedReplan accepted, StageManager.AcceptedSeal sealed)
    {
        requireNonNull(accepted, "accepted is null");
        requireNonNull(sealed, "sealed is null");
        ReplanCommand command = accepted.command;
        TaskCommandExecutor.requireCurrent(command.taskId());
        StageManager.State source = sealed.stage().state();
        if (!sealed.taskId().equals(command.taskId())
                || !sealed.stageId().equals(command.sourceStageId())
                || sealed.stageGeneration() != command.sourceStageGeneration()
                || sealed.reason() != StageEndReason.SUPERSEDED_BY_REPLAN
                || !sealed.proofId().equals(command.replanRequestId())
                || source.endReason() != StageEndReason.SUPERSEDED_BY_REPLAN) {
            throw rejected(INVALID_STATE, "Replan did not seal its exact source Stage");
        }

        Optional<CommandReceipt> duplicate = findCommandResult(
                command.taskId(), command.commandId());
        if (duplicate.isPresent()) {
            State state = requireReceipt(
                    duplicate.orElseThrow(), "OPEN_REPLAN_PLAN", command.actor(),
                    command.sourceTaskEpoch(), command.expectedTaskVersion(), null, null,
                    command.replanRequestId(), command.nextPlanStageId(), StageKind.PLAN,
                    command.nextPlanStageGeneration()).state();
            if (state.epoch() != command.targetTaskEpoch()
                    || !command.nextPlanStageId().equals(state.currentStageId())) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "Replan command id targets another epoch or Plan Stage");
            }
            return openingForReplan(command, CommandResult.Disposition.DUPLICATE, state);
        }

        State current = load(command.taskId());
        if (current.lifecycle() != TaskLifecycle.ACTIVE
                || current.epoch() != command.sourceTaskEpoch()
                || current.version() != command.expectedTaskVersion()
                || !same(current.currentStageId(), command.sourceStageId())) {
            throw rejected(INVALID_STATE, "Replan source is no longer current");
        }
        State updated = new State(
                current.id(), current.trunkId(), TaskLifecycle.ACTIVE,
                command.targetTaskEpoch(), current.version() + 1,
                command.nextPlanStageId(), null,
                current.lastBrainVerdict(), current.lastBrainResult(), null);
        State state = store.commit(
                command.commandId(), "OPEN_REPLAN_PLAN", command.actor(),
                command.sourceTaskEpoch(), command.expectedTaskVersion(),
                null, null, command.replanRequestId(),
                command.nextPlanStageId(), StageKind.PLAN,
                command.nextPlanStageGeneration(), current, updated);
        return openingForReplan(command, CommandResult.Disposition.APPLIED, state);
    }

    /** Marks the durable request applied only after its exact new Plan exists. */
    public void finishReplanInCommand(
            AcceptedReplan accepted, PlanStageManager.AcceptedOpening openedPlan)
    {
        requireNonNull(accepted, "accepted is null");
        requireNonNull(openedPlan, "openedPlan is null");
        ReplanCommand command = accepted.command;
        TaskCommandExecutor.requireCurrent(command.taskId());
        StageManager.State newPlan = openedPlan.stage().state();
        if (!openedPlan.taskId().equals(command.taskId())
                || !openedPlan.stageId().equals(command.nextPlanStageId())
                || openedPlan.stageGeneration() != command.nextPlanStageGeneration()
                || !openedPlan.proofId().equals(command.replanRequestId())
                || !newPlan.taskId().equals(command.taskId())
                || !newPlan.id().equals(command.nextPlanStageId())
                || newPlan.kind() != StageKind.PLAN
                || newPlan.generation() != command.nextPlanStageGeneration()
                || newPlan.checkpoint() != StageCheckpoint.DRAFTING
                || newPlan.endReason() != null) {
            throw rejected(INVALID_STATE, "Replan did not open its exact new Plan Stage");
        }
        if (accepted.evidence != null) {
            store.markReplanApplied(accepted.evidence, newPlan.id(), newPlan.generation());
        }
    }

    /** Reads and freezes the exact satisfied cancellation barrier. */
    public AcceptedCancellation requireSatisfiedCancellationInCommand(
            CancellationCommand command)
    {
        requireNonNull(command, "command is null");
        TaskCommandExecutor.requireCurrent(command.taskId());
        Optional<CommandReceipt> duplicate = findCommandResult(
                command.taskId(), command.commandId());
        if (duplicate.isPresent()) {
            State state = requireReceipt(
                    duplicate.orElseThrow(), "OPEN_CANCELED_CLEANUP", command.actor(),
                    command.taskEpoch(), command.expectedTaskVersion(), null, null,
                    command.barrierId(), command.cleanupStageId(), StageKind.CLEANUP,
                    command.cleanupStageGeneration()).state();
            if (state.lifecycle() != TaskLifecycle.CLEANING
                    || !command.cleanupStageId().equals(state.currentStageId())) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "Cancellation command id targets another Cleanup Stage");
            }
            return new AcceptedCancellation(command);
        }
        QuiescenceEvidence evidence = store.findSatisfiedQuiescence(
                        command.taskId(), command.barrierId())
                .orElseThrow(() -> rejected(
                        INVALID_STATE, "Satisfied cancellation barrier is missing"));
        if (!evidence.matches(command)) {
            throw rejected(INVALID_STATE,
                    "Cancellation barrier does not match the exact Task epoch");
        }
        State current = load(command.taskId());
        if (current.lifecycle() != TaskLifecycle.CANCELING
                || current.terminalIntent() != TerminalOutcome.CANCELED
                || current.epoch() != command.taskEpoch()
                || current.version() != command.expectedTaskVersion()
                || !same(current.currentStageId(), command.sourceStageId())) {
            throw rejected(INVALID_STATE, "Cancellation source is no longer current");
        }
        return new AcceptedCancellation(command);
    }

    /** Repoints a canceling Task only after its exact current Stage is sealed. */
    public StageOpening acceptCancellationStageInCommand(
            AcceptedCancellation accepted, StageManager.AcceptedSeal sealed)
    {
        requireNonNull(accepted, "accepted is null");
        requireNonNull(sealed, "sealed is null");
        CancellationCommand command = accepted.command;
        TaskCommandExecutor.requireCurrent(command.taskId());
        StageManager.State source = sealed.stage().state();
        if (!sealed.taskId().equals(command.taskId())
                || !sealed.stageId().equals(command.sourceStageId())
                || sealed.stageGeneration() != command.sourceStageGeneration()
                || sealed.reason() != StageEndReason.TASK_CANCELED
                || !sealed.proofId().equals(command.barrierId())
                || source.endReason() != StageEndReason.TASK_CANCELED) {
            throw rejected(INVALID_STATE,
                    "Cancellation did not seal its exact current Stage");
        }

        Optional<CommandReceipt> duplicate = findCommandResult(
                command.taskId(), command.commandId());
        if (duplicate.isPresent()) {
            State state = requireReceipt(
                    duplicate.orElseThrow(), "OPEN_CANCELED_CLEANUP", command.actor(),
                    command.taskEpoch(), command.expectedTaskVersion(), null, null,
                    command.barrierId(), command.cleanupStageId(), StageKind.CLEANUP,
                    command.cleanupStageGeneration()).state();
            if (state.lifecycle() != TaskLifecycle.CLEANING
                    || !command.cleanupStageId().equals(state.currentStageId())) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "Cancellation command id targets another Cleanup Stage");
            }
            return openingForCancellation(
                    command, CommandResult.Disposition.DUPLICATE, state);
        }

        State current = load(command.taskId());
        if (current.lifecycle() != TaskLifecycle.CANCELING
                || current.terminalIntent() != TerminalOutcome.CANCELED
                || current.epoch() != command.taskEpoch()
                || current.version() != command.expectedTaskVersion()
                || !same(current.currentStageId(), command.sourceStageId())) {
            throw rejected(INVALID_STATE, "Cancellation source is no longer current");
        }
        State updated = new State(
                current.id(), current.trunkId(), TaskLifecycle.CLEANING,
                current.epoch(), current.version() + 1,
                command.cleanupStageId(), null,
                current.lastBrainVerdict(), current.lastBrainResult(),
                TerminalOutcome.CANCELED);
        State state = store.commit(
                command.commandId(), "OPEN_CANCELED_CLEANUP", command.actor(),
                command.taskEpoch(), command.expectedTaskVersion(),
                null, null, command.barrierId(),
                command.cleanupStageId(), StageKind.CLEANUP, command.cleanupStageGeneration(),
                current, updated);
        return openingForCancellation(
                command, CommandResult.Disposition.APPLIED, state);
    }

    /** Starts normal/remote-close Cleanup only from an exact persisted observation. */
    public StageOpening acceptRemoteTerminalInCommand(
            TerminalCleanupCommand command,
            RemoteDevelopmentStageManager.AcceptedTerminal terminal)
    {
        requireNonNull(command, "command is null");
        requireNonNull(terminal, "terminal is null");
        TaskCommandExecutor.requireCurrent(command.taskId());
        StageManager.State remote = terminal.stage().state();
        if (!terminal.taskId().equals(command.taskId())
                || terminal.taskEpoch() != command.expectedTaskEpoch()
                || !terminal.stageId().equals(remote.id())
                || terminal.stageGeneration() != remote.generation()
                || remote.kind() != StageKind.REMOTE_DEVELOPMENT
                || remote.checkpoint() != StageCheckpoint.COMPLETED
                || remote.endReason() != terminal.reason()
                || (terminal.reason() != StageEndReason.REMOTE_MERGED
                && terminal.reason() != StageEndReason.REMOTE_CLOSED)) {
            throw rejected(INVALID_STATE, "Remote terminal proof is not exact");
        }
        TerminalOutcome intent = terminal.reason() == StageEndReason.REMOTE_MERGED
                ? TerminalOutcome.COMPLETED
                : TerminalOutcome.REMOTE_CLOSED;
        String cause = intent == TerminalOutcome.COMPLETED
                ? "OPEN_MERGED_CLEANUP"
                : "OPEN_REMOTE_CLOSED_CLEANUP";

        Optional<CommandReceipt> duplicate = findCommandResult(
                command.taskId(), command.commandId());
        if (duplicate.isPresent()) {
            State state = requireReceipt(
                    duplicate.orElseThrow(), cause, command.actor(),
                    command.expectedTaskEpoch(), command.expectedTaskVersion(),
                    terminal.resultFence(), null,
                    terminal.observationId(), command.cleanupStageId(), StageKind.CLEANUP,
                    command.cleanupStageGeneration()).state();
            if (state.lifecycle() != TaskLifecycle.CLEANING
                    || state.terminalIntent() != intent
                    || !command.cleanupStageId().equals(state.currentStageId())) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "Remote terminal command targets another Cleanup Stage");
            }
            return openingForTerminal(command, terminal, CommandResult.Disposition.DUPLICATE, state);
        }

        State current = load(command.taskId());
        validateExpected(
                new Command(command.commandId(), command.actor(), command.taskId(),
                        command.expectedTaskEpoch(), command.expectedTaskVersion()),
                current);
        if (!same(current.currentStageId(), terminal.stageId())
                || !current.lifecycle().allows(TaskLifecycle.CLEANING)
                || (current.lifecycle() != TaskLifecycle.ACTIVE
                && current.lifecycle() != TaskLifecycle.PAUSED
                && current.lifecycle() != TaskLifecycle.ARCHIVED)) {
            throw rejected(INVALID_STATE, "Remote terminal Stage is no longer current");
        }
        State updated = new State(
                current.id(), current.trunkId(), TaskLifecycle.CLEANING,
                current.epoch(), current.version() + 1, command.cleanupStageId(),
                null, current.lastBrainVerdict(), current.lastBrainResult(), intent);
        State state = store.commit(
                command.commandId(), cause, command.actor(),
                command.expectedTaskEpoch(), command.expectedTaskVersion(),
                terminal.resultFence(), null,
                terminal.observationId(), command.cleanupStageId(), StageKind.CLEANUP,
                command.cleanupStageGeneration(), current, updated);
        return openingForTerminal(command, terminal, CommandResult.Disposition.APPLIED, state);
    }

    public AcceptedCleanupQuiescence requireSatisfiedCleanupQuiescenceInCommand(
            CleanupQuiescenceCommand command)
    {
        requireNonNull(command, "command is null");
        TaskCommandExecutor.requireCurrent(command.taskId());
        QuiescenceEvidence evidence = store.findSatisfiedQuiescence(
                        command.taskId(), command.barrierId())
                .orElseThrow(() -> rejected(
                        INVALID_STATE, "Exact satisfied Cleanup barrier is missing"));
        if (!evidence.matchesCleanup(command)) {
            throw rejected(INVALID_STATE, "Cleanup barrier targets another Task epoch");
        }
        State current = load(command.taskId());
        if (current.lifecycle() != TaskLifecycle.CLEANING
                || current.terminalIntent() == null
                || current.epoch() != command.taskEpoch()
                || current.version() != command.expectedTaskVersion()
                || !same(current.currentStageId(), command.cleanupStageId())) {
            throw rejected(INVALID_STATE, "Cleanup quiescence owner is no longer current");
        }
        return new AcceptedCleanupQuiescence(command, evidence);
    }

    /** Reuses the already-satisfied CANCEL barrier for the new Cleanup owner. */
    public AcceptedCleanupQuiescence acceptCanceledCleanupQuiescenceInCommand(
            AcceptedCancellation cancellation, StageOpening opening)
    {
        requireNonNull(cancellation, "cancellation is null");
        requireNonNull(opening, "opening is null");
        CancellationCommand source = cancellation.command;
        State task = opening.taskState();
        TaskCommandExecutor.requireCurrent(source.taskId());
        if (!opening.taskId().equals(source.taskId())
                || opening.taskEpoch() != source.taskEpoch()
                || opening.kind() != StageKind.CLEANUP
                || !opening.stageId().equals(source.cleanupStageId())
                || opening.stageGeneration() != source.cleanupStageGeneration()
                || !same(opening.proofId(), source.barrierId())
                || task.lifecycle() != TaskLifecycle.CLEANING
                || task.terminalIntent() != TerminalOutcome.CANCELED) {
            throw rejected(INVALID_STATE,
                    "Canceled Cleanup does not own its satisfied barrier");
        }
        CleanupQuiescenceCommand command = new CleanupQuiescenceCommand(
                "cancel-cleanup-quiescence/" + source.commandId(),
                source.actor(), source.taskId(), source.taskEpoch(), task.version(),
                source.cleanupStageId(), source.cleanupStageGeneration(), 0,
                source.barrierId());
        return new AcceptedCleanupQuiescence(command, new QuiescenceEvidence(
                source.taskId(), source.taskEpoch(), source.barrierId(),
                QuiescenceReason.CANCEL));
    }

    /** Semantic command step for a cross-domain use case already holding the Task stripe. */
    public CommandResult<State> requestBrainReview(BrainReviewRequestCommand command)
    {
        requireNonNull(command, "command is null");
        return commands.execute(
                command.taskId(), () -> requestBrainReviewInCommand(command));
    }

    /**
     * Arms one exact Task-owned Brain result. The caller must have already
     * persisted the matching BrainReviewEpisode, TaskTurn, and DispatchTicket
     * in this command transaction.
     */
    public CommandResult<State> requestBrainReviewInCommand(
            BrainReviewRequestCommand command)
    {
        requireNonNull(command, "command is null");
        TaskCommandExecutor.requireCurrent(command.taskId());

        Optional<CommandReceipt> duplicate = findCommandResult(
                command.taskId(), command.commandId());
        if (duplicate.isPresent()) {
            return CommandResult.duplicate(requireReceipt(
                    duplicate.orElseThrow(), "REQUEST_BRAIN_REVIEW", command.actor(),
                    command.expectedEpoch(), command.expectedVersion(), command.resultFence(),
                    null, command.episodeId(), null, null, null).state());
        }

        State current = load(command.taskId());
        validateExpected(command.asTaskCommand(), current);
        ResultFence result = command.resultFence();
        if (current.lifecycle() != TaskLifecycle.ACTIVE
                || current.pendingBrainResult() != null
                || result.taskEpoch() != current.epoch()
                || !same(current.currentStageId(), result.stageId())) {
            throw rejected(INVALID_STATE,
                    "Task cannot arm this exact Brain review result");
        }

        State updated = new State(
                current.id(), current.trunkId(), current.lifecycle(), current.epoch(),
                current.version() + 1, current.currentStageId(), result,
                current.lastBrainVerdict(), current.lastBrainResult(),
                current.terminalIntent());
        return CommandResult.applied(store.commit(
                command.commandId(), "REQUEST_BRAIN_REVIEW", command.actor(),
                command.expectedEpoch(), command.expectedVersion(), result, null,
                command.episodeId(), null, null, null, current, updated));
    }

    /** Semantic command step for a cross-domain use case already holding the Task stripe. */
    public BrainVerdictResult acceptBrainVerdictInCommand(
            ResultCommand command, BrainVerdict verdict)
    {
        requireNonNull(command, "command is null");
        requireNonNull(verdict, "verdict is null");
        TaskCommandExecutor.requireCurrent(command.taskId());

        Optional<CommandReceipt> duplicate = findCommandResult(
                command.taskId(), command.commandId());
        if (duplicate.isPresent()) {
            CommandReceipt receipt = requireReceipt(
                    duplicate.orElseThrow(),
                    "ACCEPT_BRAIN_VERDICT",
                    command.actor(), null, null, command.resultFence(),
                    verdict,
                    null,
                    null,
                    null,
                    null);
            if (receipt.disposition() == CommandResult.Disposition.SUPERSEDED) {
                return BrainVerdictResult.replayedWithoutProof(receipt.state());
            }
            CommandResult<State> result = CommandResult.duplicate(receipt.state());
            return BrainVerdictResult.accepted(result, new AcceptedBrainVerdict(
                    command.commandId(), command.actor(), command.taskId(),
                    receipt.resultFence(), receipt.brainVerdict(), result.disposition(),
                    receipt.state()));
        }

        State current = load(command.taskId());
        ResultFence result = command.resultFence();
        if (!ownsBrainResult(current, result)) {
            return BrainVerdictResult.superseded(store.recordSuperseded(
                    command.commandId(), "ACCEPT_BRAIN_VERDICT", command.actor(),
                    null, null,
                    command.resultFence(), verdict, null, null, null, null, current));
        }

        State updated = new State(
                current.id(),
                current.trunkId(),
                current.lifecycle(),
                current.epoch(),
                current.version() + 1,
                current.currentStageId(),
                null,
                verdict,
                result,
                current.terminalIntent());
        CommandResult<State> accepted = CommandResult.applied(store.commit(
                command.commandId(), "ACCEPT_BRAIN_VERDICT", command.actor(),
                null, null,
                result, verdict, null, null, null, null, current, updated));
        return BrainVerdictResult.accepted(accepted, new AcceptedBrainVerdict(
                command.commandId(), command.actor(), command.taskId(),
                result, verdict, accepted.disposition(), accepted.state()));
    }

    /**
     * Terminalizes one exhausted Brain episode in Task state. The caller must
     * first persist the exact BUDGET_EXHAUSTED episode, failed TaskTurn, and
     * open blocker named by {@code blockerId} in this command transaction.
     */
    public CommandResult<State> acceptBrainBudgetExhaustionInCommand(
            ResultCommand command, String blockerId)
    {
        requireNonNull(command, "command is null");
        requireText(blockerId, "blockerId");
        TaskCommandExecutor.requireCurrent(command.taskId());

        Optional<CommandReceipt> duplicate = findCommandResult(
                command.taskId(), command.commandId());
        if (duplicate.isPresent()) {
            return CommandResult.duplicate(requireReceipt(
                    duplicate.orElseThrow(),
                    "ACCEPT_BRAIN_BUDGET_EXHAUSTION", command.actor(),
                    null, null, command.resultFence(), null, blockerId,
                    null, null, null).state());
        }

        State current = load(command.taskId());
        ResultFence result = command.resultFence();
        if (!ownsBrainResult(current, result)) {
            return CommandResult.superseded(store.recordSuperseded(
                    command.commandId(), "ACCEPT_BRAIN_BUDGET_EXHAUSTION",
                    command.actor(), null, null, result, null, blockerId,
                    null, null, null, current));
        }

        State updated = new State(
                current.id(), current.trunkId(), current.lifecycle(), current.epoch(),
                current.version() + 1, current.currentStageId(), null,
                current.lastBrainVerdict(), current.lastBrainResult(),
                current.terminalIntent());
        return CommandResult.applied(store.commit(
                command.commandId(), "ACCEPT_BRAIN_BUDGET_EXHAUSTION",
                command.actor(), null, null, result, null, blockerId,
                null, null, null, current, updated));
    }

    /** Read-only preflight for an owner delivery already holding the Task stripe. */
    public boolean isCurrentBrainResultInCommand(ResultCommand command)
    {
        requireNonNull(command, "command is null");
        TaskCommandExecutor.requireCurrent(command.taskId());
        return ownsBrainResult(load(command.taskId()), command.resultFence());
    }

    private static boolean ownsBrainResult(State current, ResultFence result)
    {
        return current.lifecycle() == TaskLifecycle.ACTIVE
                && current.epoch() == result.taskEpoch()
                && same(current.currentStageId(), result.stageId())
                && same(current.pendingBrainResult(), result);
    }

    /** Only CleanupCompletionHandoff can obtain the proof required by this command. */
    public CommandResult<State> acceptCleanupCompletionInCommand(
            Command command, CleanupStageManager.AcceptedCompletion completion)
    {
        requireNonNull(command, "command is null");
        requireNonNull(completion, "completion is null");
        TaskCommandExecutor.requireCurrent(command.taskId());

        Optional<CommandReceipt> duplicate = findCommandResult(
                command.taskId(), command.commandId());
        if (duplicate.isPresent()) {
            return CommandResult.duplicate(requireReceipt(
                    duplicate.orElseThrow(),
                    "ACCEPT_CLEANUP_COMPLETION", command.actor(),
                    command.expectedEpoch(), command.expectedVersion(),
                    completion.resultFence(),
                    null,
                    null,
                    null,
                    null,
                    null).state());
        }

        State current = load(command.taskId());
        validateExpected(command, current);
        if (current.lifecycle() != TaskLifecycle.CLEANING
                || current.terminalIntent() == null
                || !command.taskId().equals(completion.taskId())
                || current.epoch() != completion.taskEpoch()
                || !same(current.currentStageId(), completion.stageId())
                || !completion.taskId().equals(completion.stageState().taskId())
                || !completion.stageId().equals(completion.stageState().id())
                || completion.stageGeneration() != completion.stageState().generation()
                || completion.stageState().kind() != StageKind.CLEANUP
                || completion.stageState().checkpoint() != StageCheckpoint.COMPLETED
                || completion.stageState().endReason() != StageEndReason.NORMAL) {
            throw rejected(INVALID_STATE,
                    "Task terminalization requires its exact completed Cleanup Stage");
        }

        TaskLifecycle target = switch (current.terminalIntent()) {
            case CANCELED -> TaskLifecycle.CANCELED;
            case COMPLETED -> TaskLifecycle.COMPLETED;
            case REMOTE_CLOSED -> TaskLifecycle.REMOTE_CLOSED;
        };
        State updated = new State(
                current.id(),
                current.trunkId(),
                target,
                current.epoch(),
                current.version() + 1,
                null,
                null,
                current.lastBrainVerdict(),
                current.lastBrainResult(),
                current.terminalIntent());
        return CommandResult.applied(store.commit(
                command.commandId(), "ACCEPT_CLEANUP_COMPLETION", command.actor(),
                command.expectedEpoch(), command.expectedVersion(),
                completion.resultFence(), null, null, null, null, null, current, updated));
    }

    public StageOpening acceptPlanCompletionInCommand(
            StageAdvanceCommand command, PlanStageManager.AcceptedCompletion completion)
    {
        requireNonNull(completion, "completion is null");
        return advanceStageInCommand(
                command,
                "OPEN_LOCAL_DEVELOPMENT",
                completion.taskId(),
                completion.taskEpoch(),
                completion.stageId(),
                completion.stageGeneration(),
                completion.stage().state(),
                null,
                completion.approvalId(),
                StageKind.PLAN,
                StageKind.LOCAL_DEVELOPMENT);
    }

    public StageOpening acceptLocalCompletionInCommand(
            StageAdvanceCommand command,
            LocalDevelopmentStageManager.AcceptedCompletion completion)
    {
        requireNonNull(completion, "completion is null");
        return advanceStageInCommand(
                command,
                "OPEN_REMOTE_DEVELOPMENT",
                completion.taskId(),
                completion.taskEpoch(),
                completion.stageId(),
                completion.stageGeneration(),
                completion.stage().state(),
                completion.resultFence(),
                null,
                StageKind.LOCAL_DEVELOPMENT,
                StageKind.REMOTE_DEVELOPMENT);
    }

    private StageOpening advanceStageInCommand(
            StageAdvanceCommand command,
            String cause,
            String proofTaskId,
            long proofTaskEpoch,
            String completedStageId,
            long completedStageGeneration,
            StageManager.State completedStage,
            ResultFence subjectFence,
            String proofId,
            StageKind previousKind,
            StageKind nextKind)
    {
        requireNonNull(command, "command is null");
        TaskCommandExecutor.requireCurrent(command.taskId());
        if (!command.taskId().equals(proofTaskId)
                || command.expectedEpoch() != proofTaskEpoch
                || !completedStage.id().equals(completedStageId)
                || completedStage.generation() != completedStageGeneration
                || completedStage.checkpoint() != StageCheckpoint.COMPLETED
                || completedStage.endReason() != StageEndReason.NORMAL
                || completedStage.kind() != previousKind) {
            throw rejected(INVALID_STATE, "Stage advance proof is not an exact completion");
        }

        Optional<CommandReceipt> duplicate = findCommandResult(
                command.taskId(), command.commandId());
        if (duplicate.isPresent()) {
            State state = requireReceipt(
                    duplicate.orElseThrow(), cause, command.actor(),
                    command.expectedEpoch(), command.expectedVersion(), subjectFence, null,
                    proofId, command.nextStageId(), nextKind,
                    command.nextStageGeneration()).state();
            if (!command.nextStageId().equals(state.currentStageId())) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "Stage-advance command id targets another Stage");
            }
            return new StageOpening(
                    command.commandId(), command.actor(), command.taskId(),
                    command.expectedEpoch(), command.nextStageId(),
                    command.nextStageGeneration(), nextKind, subjectFence, proofId,
                    CommandResult.Disposition.DUPLICATE, state);
        }

        State current = load(command.taskId());
        validateExpected(command.asTaskCommand(), current);
        if (current.lifecycle() != TaskLifecycle.ACTIVE
                || !same(current.currentStageId(), completedStageId)
                || current.epoch() != proofTaskEpoch) {
            throw rejected(INVALID_STATE, "Completed Stage is no longer current");
        }
        State updated = new State(
                current.id(),
                current.trunkId(),
                current.lifecycle(),
                current.epoch(),
                current.version() + 1,
                command.nextStageId(),
                null,
                current.lastBrainVerdict(),
                current.lastBrainResult(),
                current.terminalIntent());
        State accepted = store.commit(
                command.commandId(), cause, command.actor(),
                command.expectedEpoch(), command.expectedVersion(),
                subjectFence, null, proofId,
                command.nextStageId(), nextKind, command.nextStageGeneration(), current, updated);
        return new StageOpening(
                command.commandId(), command.actor(), command.taskId(),
                command.expectedEpoch(), command.nextStageId(),
                command.nextStageGeneration(), nextKind, subjectFence, proofId,
                CommandResult.Disposition.APPLIED, accepted);
    }

    private static StageOpening openingForReplan(
            ReplanCommand command,
            CommandResult.Disposition disposition,
            State state)
    {
        return new StageOpening(
                command.commandId(), command.actor(), command.taskId(),
                command.targetTaskEpoch(), command.nextPlanStageId(),
                command.nextPlanStageGeneration(), StageKind.PLAN, null,
                command.replanRequestId(), disposition, state);
    }

    private static StageOpening openingForCancellation(
            CancellationCommand command,
            CommandResult.Disposition disposition,
            State state)
    {
        return new StageOpening(
                command.commandId(), command.actor(), command.taskId(),
                command.taskEpoch(), command.cleanupStageId(),
                command.cleanupStageGeneration(), StageKind.CLEANUP, null,
                command.barrierId(), disposition, state);
    }

    private static StageOpening openingForTerminal(
            TerminalCleanupCommand command,
            RemoteDevelopmentStageManager.AcceptedTerminal terminal,
            CommandResult.Disposition disposition,
            State state)
    {
        return new StageOpening(
                command.commandId(), command.actor(), command.taskId(),
                command.expectedTaskEpoch(), command.cleanupStageId(),
                command.cleanupStageGeneration(), StageKind.CLEANUP, null,
                terminal.observationId(), disposition, state);
    }

    private Optional<CommandReceipt> findCommandResult(
            String taskId, String commandId)
    {
        if (store.hasTaskCreationReceipt(taskId, commandId)) {
            throw rejected(COMMAND_ID_CONFLICT,
                    "Command id was already used to create this Task");
        }
        return store.findCommandResult(taskId, commandId);
    }

    private CommandResult<State> execute(
            Command command,
            String cause,
            TaskLifecycle target,
            boolean incrementEpoch,
            TerminalOutcome terminalIntent,
            TaskLifecycle... sources)
    {
        requireNonNull(command, "command is null");
        return commands.execute(command.taskId(), () -> applyInCommand(
                command, cause, target, incrementEpoch, terminalIntent, sources));
    }

    private CommandResult<State> applyInCommand(
            Command command,
            String cause,
            TaskLifecycle target,
            boolean incrementEpoch,
            TerminalOutcome terminalIntent,
            TaskLifecycle... sources)
    {
        TaskCommandExecutor.requireCurrent(command.taskId());
        Optional<CommandReceipt> duplicate = findCommandResult(
                command.taskId(), command.commandId());
        if (duplicate.isPresent()) {
            return CommandResult.duplicate(requireReceipt(
                    duplicate.orElseThrow(), cause, command.actor(),
                    command.expectedEpoch(), command.expectedVersion(), null, null,
                    null, null, null, null).state());
        }

        State current = load(command.taskId());
        validateExpected(command, current);
        if (!isOneOf(current.lifecycle(), sources) || !current.lifecycle().allows(target)) {
            throw rejected(INVALID_STATE,
                    "Cannot move Task from " + current.lifecycle() + " to " + target);
        }

        State updated = new State(
                current.id(),
                current.trunkId(),
                target,
                current.epoch() + (incrementEpoch ? 1 : 0),
                current.version() + 1,
                current.currentStageId(),
                incrementEpoch ? null : current.pendingBrainResult(),
                current.lastBrainVerdict(),
                current.lastBrainResult(),
                terminalIntent == null ? current.terminalIntent() : terminalIntent);
        return CommandResult.applied(store.commit(
                command.commandId(), cause, command.actor(),
                command.expectedEpoch(), command.expectedVersion(),
                null, null, null, null, null, null, current, updated));
    }

    private static CommandReceipt requireReceipt(
            CommandReceipt receipt,
            String cause,
            String actor,
            Long expectedEpoch,
            Long expectedVersion,
            ResultFence resultFence,
            BrainVerdict brainVerdict,
            String proofId,
            String nextStageId,
            StageKind nextStageKind,
            Long nextStageGeneration)
    {
        if (!receipt.cause().equals(cause)
                || !receipt.actor().equals(actor)
                || !same(receipt.expectedEpoch(), expectedEpoch)
                || !same(receipt.expectedVersion(), expectedVersion)
                || !same(receipt.resultFence(), resultFence)
                || receipt.brainVerdict() != brainVerdict
                || !same(receipt.proofId(), proofId)
                || !same(receipt.nextStageId(), nextStageId)
                || receipt.nextStageKind() != nextStageKind
                || !same(receipt.nextStageGeneration(), nextStageGeneration)) {
            throw rejected(COMMAND_ID_CONFLICT,
                    "Command id was already used for another Task command");
        }
        return receipt;
    }

    private State load(String taskId)
    {
        return store.findById(taskId)
                .orElseThrow(() -> rejected(NOT_FOUND, "Task not found: " + taskId));
    }

    private static void validateExpected(Command command, State current)
    {
        if (current.epoch() != command.expectedEpoch()) {
            throw rejected(STALE_EPOCH, "Stale Task epoch for " + command.taskId());
        }
        if (current.version() != command.expectedVersion()) {
            throw rejected(STALE_VERSION, "Stale Task version for " + command.taskId());
        }
    }

    private static boolean same(Object left, Object right)
    {
        return left == null ? right == null : left.equals(right);
    }

    private static boolean isOneOf(TaskLifecycle actual, TaskLifecycle... expected)
    {
        for (TaskLifecycle lifecycle : expected) {
            if (actual == lifecycle) {
                return true;
            }
        }
        return false;
    }

    private static CommandRejectedException rejected(
            CommandRejectedException.Reason reason, String message)
    {
        return new CommandRejectedException(reason, message);
    }

    public record Command(
            String commandId,
            String actor,
            String taskId,
            long expectedEpoch,
            long expectedVersion)
    {
        public Command
        {
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(taskId, "taskId");
            if (expectedEpoch < 1) {
                throw new IllegalArgumentException("expectedEpoch must be positive");
            }
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion is negative");
            }
        }
    }

    public record PauseCompletionCommand(
            Command task,
            String barrierId,
            String stageId,
            long stageGeneration,
            StageCheckpoint restoreCheckpoint,
            String stopEvidenceDigest)
    {
        public PauseCompletionCommand
        {
            requireNonNull(task, "task is null");
            requireText(barrierId, "barrierId");
            requireText(stageId, "stageId");
            requireNonNull(restoreCheckpoint, "restoreCheckpoint is null");
            requireText(stopEvidenceDigest, "stopEvidenceDigest");
            if (stageGeneration < 1) {
                throw new IllegalArgumentException("stageGeneration must be positive");
            }
        }
    }

    public record PauseEvidence(
            String taskId,
            long taskEpoch,
            String barrierId,
            String stageId,
            long stageGeneration,
            StageCheckpoint restoreCheckpoint,
            String stopEvidenceDigest)
    {
        public PauseEvidence
        {
            requireText(taskId, "taskId");
            requireText(barrierId, "barrierId");
            requireText(stageId, "stageId");
            requireNonNull(restoreCheckpoint, "restoreCheckpoint is null");
            requireText(stopEvidenceDigest, "stopEvidenceDigest");
            if (taskEpoch < 1 || stageGeneration < 1) {
                throw new IllegalArgumentException("Pause evidence identity is invalid");
            }
        }

        private boolean matches(PauseCompletionCommand command)
        {
            return taskId.equals(command.task().taskId())
                    && taskEpoch == command.task().expectedEpoch()
                    && barrierId.equals(command.barrierId())
                    && stageId.equals(command.stageId())
                    && stageGeneration == command.stageGeneration()
                    && restoreCheckpoint == command.restoreCheckpoint()
                    && stopEvidenceDigest.equals(command.stopEvidenceDigest());
        }
    }

    public record ResumeCompletionCommand(
            Command task,
            String reconciliationId,
            String stageId,
            long stageGeneration,
            StageCheckpoint restoreCheckpoint,
            String reconciliationDigest)
    {
        public ResumeCompletionCommand
        {
            requireNonNull(task, "task is null");
            requireText(reconciliationId, "reconciliationId");
            requireText(stageId, "stageId");
            requireNonNull(restoreCheckpoint, "restoreCheckpoint is null");
            requireText(reconciliationDigest, "reconciliationDigest");
            if (stageGeneration < 1) {
                throw new IllegalArgumentException("stageGeneration must be positive");
            }
        }
    }

    public record ResumeEvidence(
            String taskId,
            long taskEpoch,
            String reconciliationId,
            String stageId,
            long stageGeneration,
            StageCheckpoint restoreCheckpoint,
            String reconciliationDigest)
    {
        public ResumeEvidence
        {
            requireText(taskId, "taskId");
            requireText(reconciliationId, "reconciliationId");
            requireText(stageId, "stageId");
            requireNonNull(restoreCheckpoint, "restoreCheckpoint is null");
            requireText(reconciliationDigest, "reconciliationDigest");
            if (taskEpoch < 1 || stageGeneration < 1) {
                throw new IllegalArgumentException("Resume evidence identity is invalid");
            }
        }

        private boolean matches(ResumeCompletionCommand command)
        {
            return taskId.equals(command.task().taskId())
                    && taskEpoch == command.task().expectedEpoch()
                    && reconciliationId.equals(command.reconciliationId())
                    && stageId.equals(command.stageId())
                    && stageGeneration == command.stageGeneration()
                    && restoreCheckpoint == command.restoreCheckpoint()
                    && reconciliationDigest.equals(command.reconciliationDigest());
        }
    }

    public record ArchiveCompletionCommand(
            Command task,
            String archiveEvidenceId,
            String stageId,
            long stageGeneration,
            String livenessDigest)
    {
        public ArchiveCompletionCommand
        {
            requireNonNull(task, "task is null");
            requireText(archiveEvidenceId, "archiveEvidenceId");
            requireText(stageId, "stageId");
            requireText(livenessDigest, "livenessDigest");
            if (stageGeneration < 1) {
                throw new IllegalArgumentException("stageGeneration must be positive");
            }
        }
    }

    public record ArchiveEvidence(
            String taskId,
            long taskEpoch,
            String archiveEvidenceId,
            String stageId,
            long stageGeneration,
            String livenessDigest)
    {
        public ArchiveEvidence
        {
            requireText(taskId, "taskId");
            requireText(archiveEvidenceId, "archiveEvidenceId");
            requireText(stageId, "stageId");
            requireText(livenessDigest, "livenessDigest");
            if (taskEpoch < 1 || stageGeneration < 1) {
                throw new IllegalArgumentException("Archive evidence identity is invalid");
            }
        }

        private boolean matches(ArchiveCompletionCommand command)
        {
            return taskId.equals(command.task().taskId())
                    && taskEpoch == command.task().expectedEpoch()
                    && archiveEvidenceId.equals(command.archiveEvidenceId())
                    && stageId.equals(command.stageId())
                    && stageGeneration == command.stageGeneration()
                    && livenessDigest.equals(command.livenessDigest());
        }
    }

    public record ProvisioningCommand(
            String commandId,
            String actor,
            String taskId,
            long expectedTaskVersion,
            ResultFence resultFence,
            String planStageId,
            long planStageGeneration)
    {
        public ProvisioningCommand
        {
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(taskId, "taskId");
            requireNonNull(resultFence, "resultFence is null");
            requireText(planStageId, "planStageId");
            if (expectedTaskVersion < 0) {
                throw new IllegalArgumentException("expectedTaskVersion is negative");
            }
            if (resultFence.stageId() != null || resultFence.stageGeneration() != 0) {
                throw new IllegalArgumentException(
                        "Provisioning result must be fenced to Task, not Stage");
            }
            requireText(resultFence.expectedBaseSha(), "resultFence.expectedBaseSha");
            requireText(resultFence.expectedHeadSha(), "resultFence.expectedHeadSha");
            requireText(
                    resultFence.expectedCodeFingerprint(),
                    "resultFence.expectedCodeFingerprint");
            if (planStageGeneration < 1) {
                throw new IllegalArgumentException("planStageGeneration must be positive");
            }
        }

        public String operationId()
        {
            return resultFence.operationId();
        }
    }

    /** Exact accepted ProvisionTaskOperation row returned only by Task Store. */
    public record ProvisioningResult(
            String taskId,
            long taskEpoch,
            String operationId,
            int attempt,
            String resultBaseSha,
            String resultHeadSha,
            String resultCodeFingerprint)
    {
        public ProvisioningResult
        {
            requireText(taskId, "taskId");
            requireText(operationId, "operationId");
            requireText(resultBaseSha, "resultBaseSha");
            requireText(resultHeadSha, "resultHeadSha");
            requireText(resultCodeFingerprint, "resultCodeFingerprint");
            if (taskEpoch < 1 || attempt < 1) {
                throw new IllegalArgumentException("Provisioning fence is invalid");
            }
        }

        private boolean matches(ProvisioningCommand command)
        {
            ResultFence fence = command.resultFence();
            return taskId.equals(command.taskId())
                    && taskEpoch == fence.taskEpoch()
                    && operationId.equals(fence.operationId())
                    && attempt == fence.attempt()
                    && resultBaseSha.equals(fence.expectedBaseSha())
                    && resultHeadSha.equals(fence.expectedHeadSha())
                    && resultCodeFingerprint.equals(fence.expectedCodeFingerprint());
        }

        private boolean matches(ProvisionedCode code)
        {
            return taskId.equals(code.taskId())
                    && taskEpoch == code.taskEpoch()
                    && operationId.equals(code.operationId())
                    && attempt == code.attempt()
                    && resultBaseSha.equals(code.baseSha())
                    && resultHeadSha.equals(code.headSha())
                    && resultCodeFingerprint.equals(code.codeFingerprint());
        }
    }

    /** Strictly decoded discovered identity returned by ProvisionTaskOperation. */
    public record ProvisionedCode(
            String taskId,
            long taskEpoch,
            String operationId,
            int attempt,
            String repositoryId,
            String branchName,
            String worktreePath,
            String baseSha,
            String headSha,
            String codeFingerprint,
            String evidenceJson)
    {
        public ProvisionedCode
        {
            requireText(taskId, "taskId");
            requireText(operationId, "operationId");
            requireText(repositoryId, "repositoryId");
            requireText(branchName, "branchName");
            requireText(worktreePath, "worktreePath");
            requireText(baseSha, "baseSha");
            requireText(headSha, "headSha");
            requireText(codeFingerprint, "codeFingerprint");
            requireText(evidenceJson, "evidenceJson");
            if (taskEpoch < 1 || attempt < 1) {
                throw new IllegalArgumentException(
                        "Provisioned code fence must be positive");
            }
            Path path = Path.of(worktreePath);
            if (!path.isAbsolute() || !path.normalize().toString().equals(worktreePath)) {
                throw new IllegalArgumentException(
                        "worktreePath must be absolute and normalized");
            }
        }

        public ResultFence resultFence()
        {
            return new ResultFence(
                    taskEpoch,
                    null,
                    0,
                    operationId,
                    attempt,
                    codeFingerprint,
                    headSha,
                    baseSha);
        }
    }

    public record ProvisioningFailure(
            String taskId,
            long taskEpoch,
            String operationId,
            int attempt,
            String rawOutcome,
            String rawDigest,
            String errorMessage,
            long recordedAtMillis)
    {
        public ProvisioningFailure
        {
            requireText(taskId, "taskId");
            requireText(operationId, "operationId");
            requireText(rawOutcome, "rawOutcome");
            requireText(rawDigest, "rawDigest");
            requireText(errorMessage, "errorMessage");
            if (!Set.of("FAILED", "CANCELED", "INDETERMINATE")
                    .contains(rawOutcome)
                    || taskEpoch < 1 || attempt < 1 || recordedAtMillis < 0) {
                throw new IllegalArgumentException(
                        "Provisioning failure fence is invalid");
            }
        }
    }

    public record ProvisioningFailureResult(
            String taskId,
            long taskEpoch,
            String operationId,
            int attempt,
            String rawOutcome,
            String rawDigest,
            String errorMessage,
            String blockerId,
            long recordedAtMillis)
    {
        private boolean matches(ProvisioningFailure failure)
        {
            return taskId.equals(failure.taskId())
                    && taskEpoch == failure.taskEpoch()
                    && operationId.equals(failure.operationId())
                    && attempt == failure.attempt()
                    && rawOutcome.equals(failure.rawOutcome())
                    && rawDigest.equals(failure.rawDigest())
                    && errorMessage.equals(failure.errorMessage());
        }
    }

    public record ReplanCommand(
            String commandId,
            String actor,
            String taskId,
            long expectedTaskVersion,
            long sourceTaskEpoch,
            String sourceStageId,
            long sourceStageGeneration,
            long sourceStageVersion,
            String replanRequestId,
            String quiescenceBarrierId,
            String nextPlanStageId,
            long nextPlanStageGeneration)
    {
        public ReplanCommand
        {
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(taskId, "taskId");
            requireText(sourceStageId, "sourceStageId");
            requireText(replanRequestId, "replanRequestId");
            requireText(quiescenceBarrierId, "quiescenceBarrierId");
            requireText(nextPlanStageId, "nextPlanStageId");
            if (expectedTaskVersion < 0 || sourceStageVersion < 0) {
                throw new IllegalArgumentException("Aggregate version is negative");
            }
            if (sourceTaskEpoch < 1
                    || sourceStageGeneration < 1
                    || nextPlanStageGeneration < 1) {
                throw new IllegalArgumentException("Replan identity is invalid");
            }
            if (sourceStageId.equals(nextPlanStageId)) {
                throw new IllegalArgumentException("Replan must open a new Stage id");
            }
        }

        public long targetTaskEpoch()
        {
            return sourceTaskEpoch + 1;
        }
    }

    /** Exact SATISFIED task_replan_request/barrier join returned by Task Store. */
    public record ReplanEvidence(
            String taskId,
            String replanRequestId,
            String quiescenceBarrierId,
            String sourceStageId,
            long sourceStageGeneration,
            long sourceTaskEpoch,
            long targetTaskEpoch,
            String commandId,
            String requestedBy)
    {
        public ReplanEvidence
        {
            requireText(taskId, "taskId");
            requireText(replanRequestId, "replanRequestId");
            requireText(quiescenceBarrierId, "quiescenceBarrierId");
            requireText(sourceStageId, "sourceStageId");
            requireText(commandId, "commandId");
            requireText(requestedBy, "requestedBy");
            if (sourceStageGeneration < 1
                    || sourceTaskEpoch < 1
                    || targetTaskEpoch != sourceTaskEpoch + 1) {
                throw new IllegalArgumentException("Replan evidence fence is invalid");
            }
        }

        private boolean matches(ReplanCommand command)
        {
            return taskId.equals(command.taskId())
                    && replanRequestId.equals(command.replanRequestId())
                    && quiescenceBarrierId.equals(command.quiescenceBarrierId())
                    && sourceStageId.equals(command.sourceStageId())
                    && sourceStageGeneration == command.sourceStageGeneration()
                    && sourceTaskEpoch == command.sourceTaskEpoch()
                    && targetTaskEpoch == command.targetTaskEpoch()
                    && commandId.equals(command.commandId())
                    && requestedBy.equals(command.actor());
        }
    }

    public record CancellationCommand(
            String commandId,
            String actor,
            String taskId,
            long taskEpoch,
            long expectedTaskVersion,
            String sourceStageId,
            long sourceStageGeneration,
            long sourceStageVersion,
            String barrierId,
            String cleanupStageId,
            long cleanupStageGeneration)
    {
        public CancellationCommand
        {
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(taskId, "taskId");
            requireText(sourceStageId, "sourceStageId");
            requireText(barrierId, "barrierId");
            requireText(cleanupStageId, "cleanupStageId");
            if (taskEpoch < 1
                    || sourceStageGeneration < 1
                    || cleanupStageGeneration < 1) {
                throw new IllegalArgumentException("Cancellation identity is invalid");
            }
            if (expectedTaskVersion < 0 || sourceStageVersion < 0) {
                throw new IllegalArgumentException("Aggregate version is negative");
            }
            if (sourceStageId.equals(cleanupStageId)) {
                throw new IllegalArgumentException("Cleanup must use a new Stage id");
            }
        }
    }

    public record TerminalCleanupCommand(
            String commandId,
            String actor,
            String taskId,
            long expectedTaskEpoch,
            long expectedTaskVersion,
            String cleanupStageId,
            long cleanupStageGeneration)
    {
        public TerminalCleanupCommand
        {
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(taskId, "taskId");
            requireText(cleanupStageId, "cleanupStageId");
            if (expectedTaskEpoch < 1 || cleanupStageGeneration < 1) {
                throw new IllegalArgumentException("Terminal Cleanup identity is invalid");
            }
            if (expectedTaskVersion < 0) {
                throw new IllegalArgumentException("expectedTaskVersion is negative");
            }
        }
    }

    public record CleanupQuiescenceCommand(
            String commandId,
            String actor,
            String taskId,
            long taskEpoch,
            long expectedTaskVersion,
            String cleanupStageId,
            long cleanupStageGeneration,
            long expectedCleanupStageVersion,
            String barrierId)
    {
        public CleanupQuiescenceCommand
        {
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(taskId, "taskId");
            requireText(cleanupStageId, "cleanupStageId");
            requireText(barrierId, "barrierId");
            if (taskEpoch < 1 || cleanupStageGeneration < 1) {
                throw new IllegalArgumentException("Cleanup quiescence identity is invalid");
            }
            if (expectedTaskVersion < 0 || expectedCleanupStageVersion < 0) {
                throw new IllegalArgumentException("Aggregate version is negative");
            }
        }
    }

    /** Exact SATISFIED task_quiescence_barrier returned by Task Store. */
    public record QuiescenceEvidence(
            String taskId,
            long taskEpoch,
            String barrierId,
            QuiescenceReason reason)
    {
        public QuiescenceEvidence
        {
            requireText(taskId, "taskId");
            requireText(barrierId, "barrierId");
            requireNonNull(reason, "reason is null");
            if (taskEpoch < 1) {
                throw new IllegalArgumentException("taskEpoch must be positive");
            }
        }

        private boolean matches(CancellationCommand command)
        {
            return taskId.equals(command.taskId())
                    && taskEpoch == command.taskEpoch()
                    && barrierId.equals(command.barrierId())
                    && reason == QuiescenceReason.CANCEL;
        }

        private boolean matchesCleanup(CleanupQuiescenceCommand command)
        {
            return taskId.equals(command.taskId())
                    && taskEpoch == command.taskEpoch()
                    && barrierId.equals(command.barrierId())
                    && (reason == QuiescenceReason.CLEANUP
                    || reason == QuiescenceReason.CANCEL);
        }
    }

    public enum QuiescenceReason
    {
        REPLAN,
        PAUSE,
        CANCEL,
        CLEANUP
    }

    public record StageAdvanceCommand(
            String commandId,
            String actor,
            String taskId,
            long expectedEpoch,
            long expectedVersion,
            String nextStageId,
            long nextStageGeneration)
    {
        public StageAdvanceCommand
        {
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(taskId, "taskId");
            requireText(nextStageId, "nextStageId");
            if (expectedEpoch < 1) {
                throw new IllegalArgumentException("expectedEpoch must be positive");
            }
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion is negative");
            }
            if (nextStageGeneration < 1) {
                throw new IllegalArgumentException("nextStageGeneration must be positive");
            }
        }

        private Command asTaskCommand()
        {
            return new Command(commandId, actor, taskId, expectedEpoch, expectedVersion);
        }
    }

    /** Result commands use the operation fence, not an aggregate version. */
    public record ResultCommand(
            String commandId, String actor, String taskId, ResultFence resultFence)
    {
        public ResultCommand
        {
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(taskId, "taskId");
            requireNonNull(resultFence, "resultFence is null");
        }
    }

    public record BrainReviewRequestCommand(
            String commandId,
            String actor,
            String taskId,
            long expectedEpoch,
            long expectedVersion,
            String episodeId,
            ResultFence resultFence)
    {
        public BrainReviewRequestCommand
        {
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(taskId, "taskId");
            requireText(episodeId, "episodeId");
            requireNonNull(resultFence, "resultFence is null");
            if (expectedEpoch < 1 || expectedVersion < 0) {
                throw new IllegalArgumentException("Task Brain request fence is invalid");
            }
            if (resultFence.taskEpoch() != expectedEpoch
                    || resultFence.stageId() == null
                    || resultFence.expectedCodeFingerprint() == null
                    || resultFence.expectedCodeFingerprint().isBlank()
                    || resultFence.expectedHeadSha() == null
                    || resultFence.expectedHeadSha().isBlank()
                    || resultFence.expectedBaseSha() == null
                    || resultFence.expectedBaseSha().isBlank()) {
                throw new IllegalArgumentException(
                        "Task Brain request requires its complete Stage subject");
            }
        }

        private Command asTaskCommand()
        {
            return new Command(commandId, actor, taskId, expectedEpoch, expectedVersion);
        }
    }

    public record State(
            String id,
            String trunkId,
            TaskLifecycle lifecycle,
            long epoch,
            long version,
            String currentStageId,
            ResultFence pendingBrainResult,
            BrainVerdict lastBrainVerdict,
            ResultFence lastBrainResult,
            TerminalOutcome terminalIntent)
    {
        public State
        {
            requireText(id, "id");
            requireText(trunkId, "trunkId");
            requireNonNull(lifecycle, "lifecycle is null");
            if (epoch < 1) {
                throw new IllegalArgumentException("epoch must be positive");
            }
            if (version < 0) {
                throw new IllegalArgumentException("version is negative");
            }
            if (currentStageId != null && currentStageId.isBlank()) {
                throw new IllegalArgumentException("currentStageId is blank");
            }
            if (lifecycle == TaskLifecycle.PROVISIONING && currentStageId != null) {
                throw new IllegalArgumentException(
                        "PROVISIONING Task cannot preseed a current Stage");
            }
            if (!lifecycle.isTerminal()
                    && lifecycle != TaskLifecycle.PROVISIONING
                    && currentStageId == null) {
                throw new IllegalArgumentException(
                        "Started Task requires an exact current Stage");
            }
            if (!lifecycle.isTerminal()
                    && (lifecycle == TaskLifecycle.CANCELING
                    || lifecycle == TaskLifecycle.CLEANING)
                    != (terminalIntent != null)) {
                throw new IllegalArgumentException(
                        "Terminal workflow intent does not match Task lifecycle");
            }
            if ((lastBrainVerdict == null) != (lastBrainResult == null)) {
                throw new IllegalArgumentException("Brain verdict identity is incomplete");
            }
            if (lifecycle.isTerminal()
                    && (terminalIntent == null
                    || !lifecycle.name().equals(terminalIntent.name()))) {
                throw new IllegalArgumentException("terminal Task does not match its intent");
            }
            if (lifecycle.isTerminal()
                    && (currentStageId != null || pendingBrainResult != null)) {
                throw new IllegalArgumentException("terminal Task retains live workflow state");
            }
        }
    }

    public record TaskCreationReceipt(
            State state,
            long taskSequence,
            String authorizationId,
            String assignmentId,
            String policyRevisionId,
            String taskBrainId,
            String provisionOperationId,
            String dispatchTicketId,
            String operationId,
            String branchName,
            String worktreePath)
    {
        public TaskCreationReceipt
        {
            requireNonNull(state, "state is null");
            if (taskSequence < 1) {
                throw new IllegalArgumentException("taskSequence must be positive");
            }
            requireText(authorizationId, "authorizationId");
            requireText(assignmentId, "assignmentId");
            requireText(policyRevisionId, "policyRevisionId");
            requireText(taskBrainId, "taskBrainId");
            requireText(provisionOperationId, "provisionOperationId");
            requireText(dispatchTicketId, "dispatchTicketId");
            requireText(operationId, "operationId");
            requireText(branchName, "branchName");
            requireText(worktreePath, "worktreePath");
        }
    }

    public record TaskCreationResult(
            TaskCreationReceipt receipt,
            ProvisionTarget target,
            CommandResult.Disposition disposition)
    {
        public TaskCreationResult
        {
            requireNonNull(receipt, "receipt is null");
            requireNonNull(target, "target is null");
            requireNonNull(disposition, "disposition is null");
        }

        public State task()
        {
            return receipt.state();
        }
    }

    public enum BrainVerdict
    {
        APPROVED,
        CHANGES_REQUESTED
    }

    public enum TerminalOutcome
    {
        CANCELED,
        COMPLETED,
        REMOTE_CLOSED
    }

    public static final class AcceptedPause
    {
        private final PauseCompletionCommand command;

        private AcceptedPause(PauseCompletionCommand command)
        {
            this.command = requireNonNull(command, "command is null");
        }
    }

    public static final class AcceptedResume
    {
        private final ResumeCompletionCommand command;

        private AcceptedResume(ResumeCompletionCommand command)
        {
            this.command = requireNonNull(command, "command is null");
        }
    }

    public static final class AcceptedArchive
    {
        private final ArchiveCompletionCommand command;

        private AcceptedArchive(ArchiveCompletionCommand command)
        {
            this.command = requireNonNull(command, "command is null");
        }
    }

    public static final class AcceptedCleanupQuiescence
    {
        private final CleanupQuiescenceCommand command;
        private final QuiescenceEvidence evidence;

        private AcceptedCleanupQuiescence(
                CleanupQuiescenceCommand command, QuiescenceEvidence evidence)
        {
            this.command = requireNonNull(command, "command is null");
            this.evidence = requireNonNull(evidence, "evidence is null");
        }

        public String commandId() { return command.commandId(); }
        public String actor() { return command.actor(); }
        public String taskId() { return command.taskId(); }
        public long taskEpoch() { return command.taskEpoch(); }
        public String stageId() { return command.cleanupStageId(); }
        public long stageGeneration() { return command.cleanupStageGeneration(); }
        public long stageVersion() { return command.expectedCleanupStageVersion(); }
        public String barrierId() { return evidence.barrierId(); }
    }

    /** Opaque proof that TaskManager accepted this exact Brain result. */
    public static final class AcceptedBrainVerdict
    {
        private final String commandId;
        private final String actor;
        private final String taskId;
        private final ResultFence resultFence;
        private final BrainVerdict verdict;
        private final CommandResult.Disposition disposition;
        private final State taskState;

        private AcceptedBrainVerdict(
                String commandId,
                String actor,
                String taskId,
                ResultFence resultFence,
                BrainVerdict verdict,
                CommandResult.Disposition disposition,
                State taskState)
        {
            this.commandId = requireNonNull(commandId, "commandId is null");
            this.actor = requireNonNull(actor, "actor is null");
            this.taskId = requireNonNull(taskId, "taskId is null");
            this.resultFence = requireNonNull(resultFence, "resultFence is null");
            this.verdict = requireNonNull(verdict, "verdict is null");
            this.disposition = requireNonNull(disposition, "disposition is null");
            this.taskState = requireNonNull(taskState, "taskState is null");
        }

        public String commandId() { return commandId; }

        public String actor() { return actor; }

        public String taskId() { return taskId; }

        public ResultFence resultFence() { return resultFence; }

        public BrainVerdict verdict() { return verdict; }

        public CommandResult.Disposition disposition() { return disposition; }

        public State taskState() { return taskState; }
    }

    /** Unforgeable capability for one exact satisfied replan request. */
    public static final class AcceptedReplan
    {
        private final ReplanCommand command;
        private final ReplanEvidence evidence;

        private AcceptedReplan(ReplanCommand command, ReplanEvidence evidence)
        {
            this.command = requireNonNull(command, "command is null");
            this.evidence = evidence;
        }

        public String commandId() { return command.commandId(); }

        public String actor() { return command.actor(); }

        public String taskId() { return command.taskId(); }

        public long sourceTaskEpoch() { return command.sourceTaskEpoch(); }

        public String sourceStageId() { return command.sourceStageId(); }

        public long sourceStageGeneration() { return command.sourceStageGeneration(); }

        public long sourceStageVersion() { return command.sourceStageVersion(); }

        public String replanRequestId() { return command.replanRequestId(); }

        public String quiescenceBarrierId() { return command.quiescenceBarrierId(); }
    }

    /** Unforgeable capability for one exact satisfied cancellation barrier. */
    public static final class AcceptedCancellation
    {
        private final CancellationCommand command;

        private AcceptedCancellation(CancellationCommand command)
        {
            this.command = requireNonNull(command, "command is null");
        }

        public String commandId() { return command.commandId(); }

        public String actor() { return command.actor(); }

        public String taskId() { return command.taskId(); }

        public long taskEpoch() { return command.taskEpoch(); }

        public String sourceStageId() { return command.sourceStageId(); }

        public long sourceStageGeneration() { return command.sourceStageGeneration(); }

        public long sourceStageVersion() { return command.sourceStageVersion(); }

        public String barrierId() { return command.barrierId(); }
    }

    /** Opaque authorization for exactly one next Stage creation. */
    public static final class StageOpening
    {
        private final String commandId;
        private final String actor;
        private final String taskId;
        private final long taskEpoch;
        private final String stageId;
        private final long stageGeneration;
        private final StageKind kind;
        private final ResultFence subjectFence;
        private final String proofId;
        private final CommandResult.Disposition disposition;
        private final State taskState;

        private StageOpening(
                String commandId,
                String actor,
                String taskId,
                long taskEpoch,
                String stageId,
                long stageGeneration,
                StageKind kind,
                ResultFence subjectFence,
                String proofId,
                CommandResult.Disposition disposition,
                State taskState)
        {
            this.commandId = requireNonNull(commandId, "commandId is null");
            this.actor = requireNonNull(actor, "actor is null");
            this.taskId = requireNonNull(taskId, "taskId is null");
            this.taskEpoch = taskEpoch;
            this.stageId = requireNonNull(stageId, "stageId is null");
            this.stageGeneration = stageGeneration;
            this.kind = requireNonNull(kind, "kind is null");
            this.subjectFence = subjectFence;
            this.proofId = proofId;
            this.disposition = requireNonNull(disposition, "disposition is null");
            this.taskState = requireNonNull(taskState, "taskState is null");
        }

        public String commandId() { return commandId; }

        public String actor() { return actor; }

        public String taskId() { return taskId; }

        public long taskEpoch() { return taskEpoch; }

        public String stageId() { return stageId; }

        public long stageGeneration() { return stageGeneration; }

        public StageKind kind() { return kind; }

        public ResultFence subjectFence() { return subjectFence; }

        public String proofId() { return proofId; }

        public CommandResult.Disposition disposition() { return disposition; }

        public State taskState() { return taskState; }
    }

    public record BrainVerdictResult(
            CommandResult<State> task,
            Optional<AcceptedBrainVerdict> accepted)
    {
        public BrainVerdictResult
        {
            requireNonNull(task, "task is null");
            requireNonNull(accepted, "accepted is null");
            if ((task.disposition() == CommandResult.Disposition.APPLIED
                    && accepted.isEmpty())
                    || (task.disposition() == CommandResult.Disposition.SUPERSEDED
                    && accepted.isPresent())) {
                throw new IllegalArgumentException("Brain acceptance proof is inconsistent");
            }
        }

        private static BrainVerdictResult accepted(
                CommandResult<State> task, AcceptedBrainVerdict accepted)
        {
            return new BrainVerdictResult(task, Optional.of(accepted));
        }

        private static BrainVerdictResult superseded(State state)
        {
            return new BrainVerdictResult(CommandResult.superseded(state), Optional.empty());
        }

        private static BrainVerdictResult replayedWithoutProof(State state)
        {
            return new BrainVerdictResult(CommandResult.duplicate(state), Optional.empty());
        }
    }

    public record CommandReceipt(
            State state,
            String cause,
            String actor,
            Long expectedEpoch,
            Long expectedVersion,
            ResultFence resultFence,
            BrainVerdict brainVerdict,
            String proofId,
            String nextStageId,
            StageKind nextStageKind,
            Long nextStageGeneration,
            CommandResult.Disposition disposition)
    {
        public CommandReceipt
        {
            requireNonNull(state, "state is null");
            requireText(cause, "cause");
            requireText(actor, "actor");
            requireNonNull(disposition, "disposition is null");
            if (expectedEpoch != null && expectedEpoch < 1) {
                throw new IllegalArgumentException("expectedEpoch must be positive");
            }
            if (expectedVersion != null && expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion is negative");
            }
            if ((expectedEpoch == null) != (expectedVersion == null)) {
                throw new IllegalArgumentException(
                        "Structural Task receipt identity is incomplete");
            }
            if (disposition == CommandResult.Disposition.DUPLICATE) {
                throw new IllegalArgumentException(
                        "Durable Task receipt cannot store a replay disposition");
            }
            if (cause.equals("ACCEPT_BRAIN_VERDICT")
                    && (resultFence == null || brainVerdict == null)) {
                throw new IllegalArgumentException("Task result receipt is incomplete");
            }
            if (!cause.equals("ACCEPT_BRAIN_VERDICT") && brainVerdict != null) {
                throw new IllegalArgumentException("Non-Brain receipt has a Brain verdict");
            }
            if (proofId != null && proofId.isBlank()) {
                throw new IllegalArgumentException("proofId is blank");
            }
            if (nextStageGeneration != null && nextStageGeneration < 1) {
                throw new IllegalArgumentException(
                        "nextStageGeneration must be positive");
            }
            if ((nextStageId == null) != (nextStageKind == null)
                    || (nextStageId == null) != (nextStageGeneration == null)) {
                throw new IllegalArgumentException("Next Stage receipt identity is incomplete");
            }
            if (nextStageId != null && nextStageId.isBlank()) {
                throw new IllegalArgumentException("nextStageId is blank");
            }
        }
    }

    /** Persistence port. Commit must atomically compare version and record command id. */
    public interface Store
    {
        Optional<State> findById(String taskId);

        Optional<CommandReceipt> findCommandResult(String taskId, String commandId);

        boolean matchesRepositoryRoot(TaskCreationInput input, Path repositoryRoot);

        long nextTaskSequence(String trunkId);

        Optional<TaskCreationReceipt> findTaskCreation(
                String trunkId, String commandId);

        boolean hasTaskCreationReceipt(String taskId, String commandId);

        boolean matchesTaskCreation(
                TrunkManager.TaskCreationCommand command,
                TrunkManager.AuthorizedTaskCreation authorization,
                TaskCreationReceipt receipt,
                ProvisionTarget target);

        TaskCreationReceipt createTask(
                TrunkManager.TaskCreationCommand command,
                TrunkManager.AuthorizedTaskCreation authorization,
                State state,
                long taskSequence,
                ProvisionTarget target);

        Optional<ProvisioningResult> findAcceptedProvisioningResult(
                String taskId, String operationId);

        /** Accepts the operation and inserts immutable TaskCodeIdentity. */
        default ProvisioningResult acceptProvisioningResult(ProvisionedCode code)
        {
            throw new UnsupportedOperationException(
                    "Provisioning result acceptance is not supported");
        }

        default Optional<ProvisioningFailureResult> findProvisioningFailure(
                String taskId, String operationId)
        {
            return Optional.empty();
        }

        default ProvisioningFailureResult acceptProvisioningFailure(
                ProvisioningFailure failure)
        {
            throw new UnsupportedOperationException(
                    "Provisioning failure acceptance is not supported");
        }

        /**
         * Returns an exact request whose barrier is SATISFIED, whether the
         * request is still QUIESCING or was already APPLIED by this handoff.
         */
        Optional<ReplanEvidence> findReplanEvidence(
                String taskId, String replanRequestId);

        Optional<QuiescenceEvidence> findSatisfiedQuiescence(
                String taskId, String barrierId);

        Optional<PauseEvidence> findPauseEvidence(String taskId, String barrierId);

        Optional<ResumeEvidence> findResumeEvidence(String taskId, String reconciliationId);

        Optional<ArchiveEvidence> findArchiveEvidence(String taskId, String archiveEvidenceId);

        State commit(
                String commandId,
                String cause,
                String actor,
                Long expectedEpoch,
                Long expectedVersion,
                ResultFence resultFence,
                BrainVerdict brainVerdict,
                String proofId,
                String nextStageId,
                StageKind nextStageKind,
                Long nextStageGeneration,
                State expected,
                State updated);

        State recordSuperseded(
                String commandId,
                String cause,
                String actor,
                Long expectedEpoch,
                Long expectedVersion,
                ResultFence resultFence,
                BrainVerdict brainVerdict,
                String proofId,
                String nextStageId,
                StageKind nextStageKind,
                Long nextStageGeneration,
                State current);

        void markReplanApplied(
                ReplanEvidence evidence, String newPlanStageId, long newPlanGeneration);
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
