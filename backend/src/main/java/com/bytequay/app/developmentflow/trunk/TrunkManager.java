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
package com.bytequay.app.developmentflow.trunk;

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.task.creation.TaskCreationInput;
import com.bytequay.app.service.threads.TaskCommandExecutor;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.COMMAND_ID_CONFLICT;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.INVALID_STATE;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.NOT_FOUND;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_VERSION;
import static java.util.Objects.requireNonNull;

/** Sole synchronous writer for V2 Trunk lifecycle. */
public final class TrunkManager
{
    private final TaskCommandExecutor commands;
    private final Store store;

    public TrunkManager(TaskCommandExecutor commands, Store store)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.store = requireNonNull(store, "store is null");
    }

    public CommandResult<State> markIdle(Command command)
    {
        return apply(command, TrunkLifecycle.IDLE, "MARK_IDLE");
    }

    public CommandResult<State> activate(Command command)
    {
        return apply(command, TrunkLifecycle.ACTIVE, "ACTIVATE");
    }

    public CommandResult<State> archive(Command command)
    {
        return apply(command, TrunkLifecycle.ARCHIVED, "ARCHIVE");
    }

    /** Authorizes one exact, capacity-bounded planning-base refresh. */
    public CommandResult<PlanningBaseRequestReceipt> requestPlanningBaseRefresh(
            PlanningBaseCommand command)
    {
        requireNonNull(command, "command is null");
        String scope = scope(command.trunkId());
        return commands.execute(scope, () -> {
            TaskCommandExecutor.requireCurrent(scope);
            Optional<PlanningBaseRequestReceipt> duplicate =
                    store.findPlanningBaseRequest(
                            command.trunkId(), command.commandId());
            if (duplicate.isPresent()) {
                if (!store.matchesPlanningBaseRequest(command)) {
                    throw rejected(COMMAND_ID_CONFLICT,
                            "Command id was already used for another planning refresh");
                }
                return CommandResult.duplicate(duplicate.orElseThrow());
            }
            if (store.findCommandResult(command.trunkId(), command.commandId()).isPresent()
                    || store.findTaskCreationAuthorization(
                    command.trunkId(), command.commandId()).isPresent()
                    || store.isThreadTurnCommandUsed(
                    command.trunkId(), command.commandId())
                    || store.isPlanningBaseCommandUsed(
                    command.trunkId(), command.commandId())
                    || store.isTaskOutcomeCommandUsed(
                    command.trunkId(), command.commandId())) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "Command id was already used for another Trunk command");
            }
            State current = store.findById(command.trunkId())
                    .orElseThrow(() -> rejected(
                            NOT_FOUND, "Trunk not found: " + command.trunkId()));
            if (current.version() != command.expectedTrunkVersion()) {
                throw rejected(STALE_VERSION,
                        "Stale Trunk version for " + command.trunkId());
            }
            if (current.lifecycle() == TrunkLifecycle.ARCHIVED) {
                throw rejected(INVALID_STATE,
                        "Archived Trunk cannot refresh its planning base");
            }
            State updated = new State(
                    current.id(), TrunkLifecycle.ACTIVE, current.version() + 1);
            return CommandResult.applied(
                    store.requestPlanningBaseRefresh(command, current, updated));
        });
    }

    /** Accepts one exact refresh result; launching the Turn is a later command. */
    public CommandResult<PlanningBaseResultReceipt> acceptPlanningBaseRefresh(
            PlanningBaseResultFact fact)
    {
        requireNonNull(fact, "fact is null");
        String trunkId = store.findPlanningBaseTrunk(fact.operationId())
                .orElseThrow(() -> rejected(
                        NOT_FOUND,
                        "Planning-base Operation not found: " + fact.operationId()));
        String scope = scope(trunkId);
        return commands.execute(scope, () -> {
            TaskCommandExecutor.requireCurrent(scope);
            Optional<PlanningBaseResultReceipt> duplicate =
                    store.findPlanningBaseResult(fact.operationId());
            if (duplicate.isPresent()) {
                PlanningBaseResultReceipt receipt = duplicate.orElseThrow();
                if (!receipt.commandId().equals(fact.commandId())
                        || !receipt.rawOutcome().equals(fact.rawOutcome())
                        || !receipt.rawResultDigest().equals(
                        fact.rawResultDigest())) {
                    throw rejected(COMMAND_ID_CONFLICT,
                            "Planning result was redelivered with different evidence");
                }
                return CommandResult.duplicate(receipt);
            }
            PlanningBaseResultContext context =
                    store.requirePlanningBaseResult(fact.operationId());
            if (!context.trunkState().id().equals(trunkId)
                    || !context.matches(fact)) {
                throw rejected(STALE_VERSION,
                        "Planning result differs from its exact Operation fence");
            }
            boolean superseded = context.trunkState().lifecycle()
                    == TrunkLifecycle.ARCHIVED;
            String acceptance = superseded ? "SUPERSEDED" : "ACCEPTED";
            String status = superseded ? "SUPERSEDED" : switch (fact.rawOutcome()) {
                case "SUCCEEDED" -> "SUCCEEDED";
                case "CANCELED" -> "CANCELED";
                case "FAILED", "INDETERMINATE" -> "FAILED";
                default -> throw new IllegalArgumentException(
                        "Unknown planning result outcome: " + fact.rawOutcome());
            };
            TrunkLifecycle lifecycle = context.trunkState().lifecycle();
            if (lifecycle != TrunkLifecycle.ARCHIVED
                    && !"SUCCEEDED".equals(status)) {
                lifecycle = context.otherLiveWork()
                        ? TrunkLifecycle.ACTIVE : TrunkLifecycle.IDLE;
            }
            State updated = new State(
                    context.trunkState().id(), lifecycle,
                    context.trunkState().version() + 1);
            PlanningBaseResultReceipt receipt = store.acceptPlanningBaseResult(
                    fact, context, updated, acceptance, status);
            return superseded
                    ? CommandResult.superseded(receipt)
                    : CommandResult.applied(receipt);
        });
    }

    /** Consumes one durable TaskOutcome inbox item into the Trunk timeline. */
    public CommandResult<TaskOutcomeReceipt> acceptTaskOutcome(
            TaskOutcomeFact fact)
    {
        requireNonNull(fact, "fact is null");
        String trunkId = store.findTaskOutcomeTrunk(
                        fact.taskOutcomeId(), fact.deliveryKey())
                .orElseThrow(() -> rejected(
                        NOT_FOUND,
                        "TaskOutcome inbox item not found: "
                                + fact.taskOutcomeId()));
        String scope = scope(trunkId);
        return commands.execute(scope, () -> {
            TaskCommandExecutor.requireCurrent(scope);
            Optional<TaskOutcomeReceipt> duplicate =
                    store.findTaskOutcomeReceipt(
                            fact.taskOutcomeId(), fact.deliveryKey());
            if (duplicate.isPresent()) {
                TaskOutcomeReceipt receipt = duplicate.orElseThrow();
                if (!receipt.actor().equals(fact.actor())) {
                    throw rejected(COMMAND_ID_CONFLICT,
                            "TaskOutcome was delivered by another command fact");
                }
                return CommandResult.duplicate(receipt);
            }
            if (store.findCommandResult(trunkId, fact.deliveryKey()).isPresent()
                    || store.findTaskCreationAuthorization(
                    trunkId, fact.deliveryKey()).isPresent()
                    || store.isThreadTurnCommandUsed(
                    trunkId, fact.deliveryKey())
                    || store.isPlanningBaseCommandUsed(
                    trunkId, fact.deliveryKey())) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "TaskOutcome delivery key was used by another Trunk command");
            }
            TaskOutcomeContext context = store.requireTaskOutcome(
                    fact.taskOutcomeId(), fact.deliveryKey());
            if (!context.trunkState().id().equals(trunkId)) {
                throw rejected(STALE_VERSION,
                        "TaskOutcome inbox changed before delivery");
            }
            State current = context.trunkState();
            State updated = new State(
                    current.id(), current.lifecycle(), current.version() + 1);
            return CommandResult.applied(
                    store.acceptTaskOutcome(fact, context, updated));
        });
    }

    /** Accepts exact successful Brain evidence into the completion marker. */
    public CommandResult<TaskOutcomeSummaryState> enrichTaskOutcomeSummary(
            TaskOutcomeSummaryFact fact)
    {
        requireNonNull(fact, "fact is null");
        String trunkId = store.findTaskOutcomeTrunk(fact.taskOutcomeId())
                .orElseThrow(() -> rejected(
                        NOT_FOUND, "TaskOutcome not found: "
                                + fact.taskOutcomeId()));
        String scope = scope(trunkId);
        return commands.execute(scope, () -> {
            TaskCommandExecutor.requireCurrent(scope);
            TaskOutcomeSummaryState current = store.requireTaskOutcomeSummary(
                    fact.taskOutcomeId());
            if ("BRAIN_GENERATED".equals(current.summaryState())) {
                if (!current.matches(fact)) {
                    throw rejected(COMMAND_ID_CONFLICT,
                            "TaskOutcome summary was generated from different evidence");
                }
                return CommandResult.duplicate(current);
            }
            if (!Objects.equals(current.summaryTaskTurnId(), fact.turnId())) {
                throw rejected(STALE_VERSION,
                        "TaskOutcome summary TaskTurn fence is stale");
            }
            return CommandResult.applied(
                    store.enrichTaskOutcomeSummary(fact, current));
        });
    }

    /** Creates one typed Trunk conversation Turn and its reserved-lane ticket. */
    public CommandResult<ThreadTurnRequestReceipt> requestThreadTurn(
            ThreadTurnCommand command)
    {
        requireNonNull(command, "command is null");
        String scope = scope(command.trunkId());
        return commands.execute(scope, () -> requestThreadTurnInCommand(command, scope));
    }

    /** Atomically consumes one successful planning launch disposition. */
    public Optional<CommandResult<ThreadTurnRequestReceipt>> launchPlanningTurn(
            String planningOperationId, ThreadTurnCommand command)
    {
        requireText(planningOperationId, "planningOperationId");
        requireNonNull(command, "command is null");
        String scope = scope(command.trunkId());
        return commands.execute(scope, () -> {
            TaskCommandExecutor.requireCurrent(scope);
            if (!store.isPlanningLaunchPending(
                    planningOperationId, command.trunkId(), command.turnId())) {
                return Optional.empty();
            }
            CommandResult<ThreadTurnRequestReceipt> result =
                    requestThreadTurnInCommand(command, scope);
            store.markPlanningLaunch(
                    planningOperationId, command.turnId(), command.requestedAt());
            return Optional.of(result);
        });
    }

    /** Durably prevents every not-yet-launched planning result for this Trunk. */
    public int suppressPlanningLaunches(String trunkId, String reason, Instant at)
    {
        requireText(trunkId, "trunkId");
        requireText(reason, "reason");
        requireNonNull(at, "at is null");
        String scope = scope(trunkId);
        return commands.execute(scope, () -> {
            TaskCommandExecutor.requireCurrent(scope);
            return store.suppressPlanningLaunches(trunkId, reason, at);
        });
    }

    private CommandResult<ThreadTurnRequestReceipt> requestThreadTurnInCommand(
            ThreadTurnCommand command, String scope)
    {
        TaskCommandExecutor.requireCurrent(scope);
        Optional<ThreadTurnRequestReceipt> duplicate =
                store.findThreadTurnRequest(command.trunkId(), command.commandId());
        if (duplicate.isPresent()) {
            if (!store.matchesThreadTurnRequest(command)) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "Command id was already used for another ThreadTurn");
            }
            return CommandResult.duplicate(duplicate.orElseThrow());
        }
        if (store.findCommandResult(command.trunkId(), command.commandId()).isPresent()
                || store.findTaskCreationAuthorization(
                command.trunkId(), command.commandId()).isPresent()
                || store.isThreadTurnCommandUsed(
                command.trunkId(), command.commandId())
                    || store.isPlanningBaseCommandUsed(
                    command.trunkId(), command.commandId())
                    || store.isTaskOutcomeCommandUsed(
                    command.trunkId(), command.commandId())) {
            throw rejected(COMMAND_ID_CONFLICT,
                    "Command id was already used for another Trunk command");
        }

        State current = store.findById(command.trunkId())
                .orElseThrow(() -> rejected(
                        NOT_FOUND, "Trunk not found: " + command.trunkId()));
        if (current.version() != command.expectedTrunkVersion()) {
            throw rejected(STALE_VERSION,
                    "Stale Trunk version for " + command.trunkId());
        }
        if (current.lifecycle() == TrunkLifecycle.ARCHIVED) {
            throw rejected(INVALID_STATE,
                    "Archived Trunk cannot accept a ThreadTurn");
        }
        State updated = new State(
                current.id(), TrunkLifecycle.ACTIVE, current.version() + 1);
        return CommandResult.applied(
                store.requestThreadTurn(command, current, updated));
    }

    /** Accepts one exact asynchronous ThreadTurn fact into Trunk conversation. */
    public CommandResult<ThreadTurnResultReceipt> acceptThreadTurnResult(
            ThreadTurnResultFact fact)
    {
        requireNonNull(fact, "fact is null");
        String trunkId = store.findThreadTurnTrunk(
                        fact.turnId(), fact.operationId())
                .orElseThrow(() -> rejected(
                        NOT_FOUND, "ThreadTurn not found: " + fact.turnId()));
        String scope = scope(trunkId);
        return commands.execute(scope, () -> acceptThreadTurnResultInCommand(
                trunkId, fact, scope));
    }

    private CommandResult<ThreadTurnResultReceipt> acceptThreadTurnResultInCommand(
            String trunkId, ThreadTurnResultFact fact, String scope)
    {
        TaskCommandExecutor.requireCurrent(scope);
        Optional<ThreadTurnResultReceipt> duplicate = store.findThreadTurnResult(
                fact.turnId(), fact.operationId());
        if (duplicate.isPresent()) {
            ThreadTurnResultReceipt receipt = duplicate.orElseThrow();
            if (!receipt.commandId().equals(fact.commandId())
                    || !receipt.actor().equals(fact.actor())
                    || receipt.attempt() != fact.attempt()
                    || !receipt.rawOutcome().equals(fact.rawOutcome())
                    || !receipt.rawResultDigest().equals(fact.rawResultDigest())) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "ThreadTurn result was redelivered with different evidence");
            }
            return CommandResult.duplicate(receipt);
        }
        if (store.findCommandResult(trunkId, fact.commandId()).isPresent()
                || store.findTaskCreationAuthorization(
                trunkId, fact.commandId()).isPresent()
                || store.isThreadTurnCommandUsed(trunkId, fact.commandId())
                || store.isPlanningBaseCommandUsed(trunkId, fact.commandId())
                || store.isTaskOutcomeCommandUsed(trunkId, fact.commandId())) {
            throw rejected(COMMAND_ID_CONFLICT,
                    "Command id was already used for another Trunk command");
        }

        ThreadTurnResultContext context = store.requireThreadTurnResult(
                fact.turnId(), fact.operationId());
        if (!trunkId.equals(context.trunkState().id())
                || context.attempt() != fact.attempt()) {
            throw rejected(STALE_VERSION,
                    "ThreadTurn result fence is stale");
        }

        boolean live = isLiveTurn(context.turnStatus());
        String acceptance = live
                && context.trunkState().lifecycle() != TrunkLifecycle.ARCHIVED
                ? "ACCEPTED" : "SUPERSEDED";
        String terminalStatus = "ACCEPTED".equals(acceptance)
                ? terminalStatus(fact.rawOutcome())
                : live ? "SUPERSEDED" : context.turnStatus();
        TrunkLifecycle lifecycle = context.trunkState().lifecycle();
        if (lifecycle != TrunkLifecycle.ARCHIVED) {
            lifecycle = context.otherLiveWork()
                    ? TrunkLifecycle.ACTIVE : TrunkLifecycle.IDLE;
        }
        State updated = new State(
                context.trunkState().id(), lifecycle,
                context.trunkState().version() + 1);
        ThreadTurnResultReceipt receipt = store.acceptThreadTurnResult(
                fact, context, updated, acceptance, terminalStatus);
        return "ACCEPTED".equals(acceptance)
                ? CommandResult.applied(receipt)
                : CommandResult.superseded(receipt);
    }

    private static boolean isLiveTurn(String status)
    {
        return switch (status) {
            case "REQUESTED", "QUEUED", "CLAIMED", "RUNNING" -> true;
            default -> false;
        };
    }

    private static String terminalStatus(String outcome)
    {
        return switch (outcome) {
            case "SUCCEEDED" -> "SUCCEEDED";
            case "CANCELED" -> "CANCELED";
            case "FAILED", "INDETERMINATE" -> "FAILED";
            default -> throw new IllegalArgumentException(
                    "unknown ThreadTurn raw outcome: " + outcome);
        };
    }

    /** Authorizes one exact assignment without making Trunk conversation busy. */
    public AuthorizedTaskCreation authorizeTaskCreationInCommand(
            TaskCreationCommand command)
    {
        requireNonNull(command, "command is null");
        String scope = scope(command.input().assignment().identity().trunkId());
        TaskCommandExecutor.requireCurrent(scope);

        if (store.findCommandResult(
                command.input().assignment().identity().trunkId(),
                command.commandId()).isPresent()) {
            throw rejected(COMMAND_ID_CONFLICT,
                    "Command id was already used for another Trunk command");
        }

        if (store.isThreadTurnCommandUsed(
                command.input().assignment().identity().trunkId(),
                command.commandId())
                || store.isPlanningBaseCommandUsed(
                command.input().assignment().identity().trunkId(),
                command.commandId())
                || store.isTaskOutcomeCommandUsed(
                command.input().assignment().identity().trunkId(),
                command.commandId())) {
            throw rejected(COMMAND_ID_CONFLICT,
                    "Command id was already used for a ThreadTurn command");
        }

        Optional<TaskCreationAuthorizationReceipt> duplicate =
                store.findTaskCreationAuthorization(
                        command.input().assignment().identity().trunkId(),
                        command.commandId());
        if (duplicate.isPresent()) {
            TaskCreationAuthorizationReceipt receipt = duplicate.orElseThrow();
            if (!receipt.actor().equals(command.actor())
                    || receipt.expectedVersion() != command.expectedTrunkVersion()
                    || !receipt.assignmentId().equals(
                            command.input().assignment().identity().id())
                    || !receipt.authorizationId().equals(
                            command.input().assignment().identity()
                                    .creationAuthorizationId())
                    || !receipt.policyRevisionId().equals(command.input().policy().id())
                    || !store.matchesTaskCreationAuthorization(command)) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "Command id was already used for another Task creation");
            }
            return new AuthorizedTaskCreation(
                    command, CommandResult.Disposition.DUPLICATE, receipt.state());
        }

        String trunkId = command.input().assignment().identity().trunkId();
        State current = store.findById(trunkId)
                .orElseThrow(() -> rejected(NOT_FOUND, "Trunk not found: " + trunkId));
        if (current.version() != command.expectedTrunkVersion()) {
            throw rejected(STALE_VERSION, "Stale Trunk version for " + trunkId);
        }
        if (current.lifecycle() == TrunkLifecycle.ARCHIVED) {
            throw rejected(INVALID_STATE,
                    "Archived Trunk cannot authorize Task creation");
        }

        State updated = new State(
                current.id(), current.lifecycle(), current.version() + 1);
        State authorized = store.authorizeTaskCreation(command, current, updated);
        return new AuthorizedTaskCreation(
                command, CommandResult.Disposition.APPLIED, authorized);
    }

    private CommandResult<State> apply(
            Command command, TrunkLifecycle target, String cause)
    {
        requireNonNull(command, "command is null");
        String scope = scope(command.trunkId());
        return commands.execute(scope, () -> applyInCommand(command, target, cause, scope));
    }

    private CommandResult<State> applyInCommand(
            Command command, TrunkLifecycle target, String cause, String scope)
    {
        TaskCommandExecutor.requireCurrent(scope);
        if (store.findTaskCreationAuthorization(
                command.trunkId(), command.commandId()).isPresent()) {
            throw rejected(COMMAND_ID_CONFLICT,
                    "Command id was already used for Task creation");
        }
        if (store.isThreadTurnCommandUsed(
                command.trunkId(), command.commandId())
                || store.isPlanningBaseCommandUsed(
                command.trunkId(), command.commandId())
                || store.isTaskOutcomeCommandUsed(
                command.trunkId(), command.commandId())) {
            throw rejected(COMMAND_ID_CONFLICT,
                    "Command id was already used for a ThreadTurn command");
        }
        Optional<CommandReceipt> duplicate = store.findCommandResult(
                command.trunkId(), command.commandId());
        if (duplicate.isPresent()) {
            CommandReceipt receipt = duplicate.orElseThrow();
            if (!receipt.cause().equals(cause)
                    || !receipt.actor().equals(command.actor())
                    || receipt.expectedVersion() != command.expectedVersion()
                    || receipt.disposition() != CommandResult.Disposition.APPLIED) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "Command id was already used for another Trunk command");
            }
            return CommandResult.duplicate(receipt.state());
        }

        State current = store.findById(command.trunkId())
                .orElseThrow(() -> rejected(NOT_FOUND, "Trunk not found: " + command.trunkId()));
        if (current.version() != command.expectedVersion()) {
            throw rejected(STALE_VERSION, "Stale Trunk version for " + command.trunkId());
        }
        if (!current.lifecycle().allows(target)) {
            throw rejected(INVALID_STATE,
                    "Cannot " + target + " Trunk from " + current.lifecycle());
        }

        State updated = new State(current.id(), target, current.version() + 1);
        State committed = store.commit(
                command.commandId(), cause, command.actor(), command.expectedVersion(),
                current, updated);
        if (target == TrunkLifecycle.ARCHIVED) {
            store.suppressPlanningLaunches(
                    command.trunkId(), "Trunk archived before turn launch",
                    Instant.now());
        }
        return CommandResult.applied(committed);
    }

    private static String scope(String trunkId)
    {
        return "v2-trunk/" + trunkId;
    }

    private static CommandRejectedException rejected(
            CommandRejectedException.Reason reason, String message)
    {
        return new CommandRejectedException(reason, message);
    }

    public record Command(
            String commandId, String actor, String trunkId, long expectedVersion)
    {
        public Command
        {
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(trunkId, "trunkId");
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion is negative");
            }
        }
    }

    public record State(String id, TrunkLifecycle lifecycle, long version)
    {
        public State
        {
            requireText(id, "id");
            requireNonNull(lifecycle, "lifecycle is null");
            if (version < 0) {
                throw new IllegalArgumentException("version is negative");
            }
        }
    }

    public record TaskCreationCommand(
            String commandId,
            String actor,
            long expectedTrunkVersion,
            TaskCreationInput input)
    {
        public TaskCreationCommand
        {
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            if (expectedTrunkVersion < 0) {
                throw new IllegalArgumentException("expectedTrunkVersion is negative");
            }
            requireNonNull(input, "input is null");
        }
    }

    public record PlanningBaseCommand(
            String commandId,
            String actor,
            String trunkId,
            String workspaceId,
            long expectedTrunkVersion,
            String planningOperationId,
            String operationId,
            String dispatchTicketId,
            String launchCommandId,
            String reservedThreadTurnId,
            String repositoryRoot,
            String previousBaseSha,
            String launchIntent,
            String launchIntentDigest,
            Instant requestedAt)
    {
        public PlanningBaseCommand
        {
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(trunkId, "trunkId");
            requireText(workspaceId, "workspaceId");
            requireText(planningOperationId, "planningOperationId");
            requireText(operationId, "operationId");
            requireText(dispatchTicketId, "dispatchTicketId");
            requireText(launchCommandId, "launchCommandId");
            requireText(reservedThreadTurnId, "reservedThreadTurnId");
            requireText(repositoryRoot, "repositoryRoot");
            requireText(launchIntent, "launchIntent");
            requireDigest(launchIntentDigest, "launchIntentDigest");
            requireNonNull(requestedAt, "requestedAt is null");
            if (expectedTrunkVersion < 0) {
                throw new IllegalArgumentException(
                        "expectedTrunkVersion is negative");
            }
            if (previousBaseSha != null && previousBaseSha.isBlank()) {
                throw new IllegalArgumentException(
                        "previousBaseSha must not be blank");
            }
        }
    }

    public record PlanningBaseRequestReceipt(
            State state,
            String commandId,
            String actor,
            long expectedVersion,
            String planningOperationId,
            String operationId,
            String dispatchTicketId,
            String launchCommandId,
            String reservedThreadTurnId,
            Instant recordedAt)
    {
        public PlanningBaseRequestReceipt
        {
            requireNonNull(state, "state is null");
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(planningOperationId, "planningOperationId");
            requireText(operationId, "operationId");
            requireText(dispatchTicketId, "dispatchTicketId");
            requireText(launchCommandId, "launchCommandId");
            requireText(reservedThreadTurnId, "reservedThreadTurnId");
            requireNonNull(recordedAt, "recordedAt is null");
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion is negative");
            }
        }
    }

    public record PlanningBaseResultFact(
            String commandId,
            String actor,
            String operationId,
            int attempt,
            String expectedPreviousBaseSha,
            String rawOutcome,
            String rawResultDigest,
            String repositoryRoot,
            String worktreePath,
            String baseRef,
            String baseSha,
            String error,
            Instant finishedAt)
    {
        public PlanningBaseResultFact
        {
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(operationId, "operationId");
            requireText(rawOutcome, "rawOutcome");
            requireDigest(rawResultDigest, "rawResultDigest");
            requireNonNull(finishedAt, "finishedAt is null");
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be positive");
            }
            boolean succeeded = "SUCCEEDED".equals(rawOutcome);
            if (succeeded != (repositoryRoot != null
                    && worktreePath != null && baseRef != null && baseSha != null)) {
                throw new IllegalArgumentException(
                        "successful planning result requires one complete snapshot");
            }
            if (expectedPreviousBaseSha != null
                    && expectedPreviousBaseSha.isBlank()) {
                throw new IllegalArgumentException(
                        "expectedPreviousBaseSha must not be blank");
            }
        }
    }

    public record PlanningBaseResultContext(
            State trunkState,
            String planningOperationId,
            String operationId,
            int attempt,
            String repositoryRoot,
            String previousBaseSha,
            boolean otherLiveWork)
    {
        public PlanningBaseResultContext
        {
            requireNonNull(trunkState, "trunkState is null");
            requireText(planningOperationId, "planningOperationId");
            requireText(operationId, "operationId");
            requireText(repositoryRoot, "repositoryRoot");
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be positive");
            }
        }

        private boolean matches(PlanningBaseResultFact fact)
        {
            return operationId.equals(fact.operationId())
                    && attempt == fact.attempt()
                    && Objects.equals(
                    previousBaseSha, fact.expectedPreviousBaseSha())
                    && (fact.repositoryRoot() == null
                    || repositoryRoot.equals(fact.repositoryRoot()));
        }
    }

    public record PlanningBaseResultReceipt(
            State state,
            String commandId,
            String planningOperationId,
            String operationId,
            String rawOutcome,
            String rawResultDigest,
            String acceptance,
            String operationStatus,
            String baseSha,
            Instant recordedAt)
    {
        public PlanningBaseResultReceipt
        {
            requireNonNull(state, "state is null");
            requireText(commandId, "commandId");
            requireText(planningOperationId, "planningOperationId");
            requireText(operationId, "operationId");
            requireText(rawOutcome, "rawOutcome");
            requireDigest(rawResultDigest, "rawResultDigest");
            requireText(acceptance, "acceptance");
            requireText(operationStatus, "operationStatus");
            requireNonNull(recordedAt, "recordedAt is null");
        }
    }

    public record TaskOutcomeFact(
            String taskOutcomeId,
            String deliveryKey,
            String actor,
            Instant receivedAt)
    {
        public TaskOutcomeFact
        {
            requireText(taskOutcomeId, "taskOutcomeId");
            requireText(deliveryKey, "deliveryKey");
            requireText(actor, "actor");
            requireNonNull(receivedAt, "receivedAt is null");
        }
    }

    public record TaskOutcomeContext(
            State trunkState,
            String taskOutcomeId,
            String taskId,
            long taskEpoch,
            String deliveryKey,
            String fallbackSummaryText,
            Instant createdAt)
    {
        public TaskOutcomeContext
        {
            requireNonNull(trunkState, "trunkState is null");
            requireText(taskOutcomeId, "taskOutcomeId");
            requireText(taskId, "taskId");
            requireText(deliveryKey, "deliveryKey");
            requireText(fallbackSummaryText, "fallbackSummaryText");
            requireNonNull(createdAt, "createdAt is null");
            if (taskEpoch < 1) {
                throw new IllegalArgumentException("taskEpoch must be positive");
            }
        }
    }

    public record TaskOutcomeReceipt(
            State state,
            String taskOutcomeId,
            String taskId,
            String deliveryKey,
            String actor,
            String fallbackSummaryText,
            Instant recordedAt)
    {
        public TaskOutcomeReceipt
        {
            requireNonNull(state, "state is null");
            requireText(taskOutcomeId, "taskOutcomeId");
            requireText(taskId, "taskId");
            requireText(deliveryKey, "deliveryKey");
            requireText(actor, "actor");
            requireText(fallbackSummaryText, "fallbackSummaryText");
            requireNonNull(recordedAt, "recordedAt is null");
        }
    }

    public record TaskOutcomeSummaryFact(
            String taskOutcomeId,
            String turnId,
            String operationId,
            String summaryText,
            String summaryDigest,
            Instant finishedAt)
    {
        public TaskOutcomeSummaryFact
        {
            requireText(taskOutcomeId, "taskOutcomeId");
            requireText(turnId, "turnId");
            requireText(operationId, "operationId");
            requireText(summaryText, "summaryText");
            requireDigest(summaryDigest, "summaryDigest");
            requireNonNull(finishedAt, "finishedAt is null");
        }
    }

    public record TaskOutcomeSummaryState(
            String taskOutcomeId,
            String trunkId,
            String summaryState,
            String summaryTaskTurnId,
            String summaryOperationId,
            String summaryText,
            String summaryDigest,
            Instant summaryUpdatedAt)
    {
        public TaskOutcomeSummaryState
        {
            requireText(taskOutcomeId, "taskOutcomeId");
            requireText(trunkId, "trunkId");
            requireText(summaryState, "summaryState");
            requireText(summaryText, "summaryText");
            requireText(summaryDigest, "summaryDigest");
            if (summaryTaskTurnId != null) {
                requireText(summaryTaskTurnId, "summaryTaskTurnId");
            }
            if (summaryOperationId != null) {
                requireText(summaryOperationId, "summaryOperationId");
            }
            if ("FALLBACK".equals(summaryState)) {
                if (summaryOperationId != null || summaryUpdatedAt != null) {
                    throw new IllegalArgumentException(
                            "fallback TaskOutcome has generated summary evidence");
                }
            }
            else if (!"BRAIN_GENERATED".equals(summaryState)
                    || summaryTaskTurnId == null
                    || summaryOperationId == null
                    || summaryUpdatedAt == null) {
                throw new IllegalArgumentException(
                        "generated TaskOutcome summary is incomplete");
            }
            else {
                requireDigest(summaryDigest, "summaryDigest");
            }
        }

        private boolean matches(TaskOutcomeSummaryFact fact)
        {
            return taskOutcomeId.equals(fact.taskOutcomeId())
                    && summaryTaskTurnId.equals(fact.turnId())
                    && summaryOperationId.equals(fact.operationId())
                    && summaryText.equals(fact.summaryText())
                    && summaryDigest.equals(fact.summaryDigest())
                    && summaryUpdatedAt.equals(fact.finishedAt());
        }
    }

    public record ThreadTurnCommand(
            String commandId,
            String actor,
            String trunkId,
            String workspaceId,
            long expectedTrunkVersion,
            String turnId,
            String operationId,
            String ticketId,
            String userMessageId,
            String purpose,
            String deliveryLane,
            int laneMask,
            String planningOperationId,
            String expectedBaseSha,
            String launchInput,
            String launchInputDigest,
            String userMessage,
            String userMessageDigest,
            Instant requestedAt)
    {
        public ThreadTurnCommand
        {
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(trunkId, "trunkId");
            requireText(workspaceId, "workspaceId");
            requireText(turnId, "turnId");
            requireText(operationId, "operationId");
            requireText(ticketId, "ticketId");
            requireText(userMessageId, "userMessageId");
            requireText(purpose, "purpose");
            requireText(deliveryLane, "deliveryLane");
            requireText(launchInput, "launchInput");
            requireDigest(launchInputDigest, "launchInputDigest");
            requireText(userMessage, "userMessage");
            requireDigest(userMessageDigest, "userMessageDigest");
            requireNonNull(requestedAt, "requestedAt is null");
            if (expectedTrunkVersion < 0) {
                throw new IllegalArgumentException(
                        "expectedTrunkVersion is negative");
            }
            if (!("CLI".equals(deliveryLane) && laneMask == 1)
                    && !("API".equals(deliveryLane) && laneMask == 2)) {
                throw new IllegalArgumentException(
                        "ThreadTurn lane and mask are inconsistent");
            }
            if ((planningOperationId == null) != (expectedBaseSha == null)) {
                throw new IllegalArgumentException(
                        "ThreadTurn planning fence is incomplete");
            }
            if (planningOperationId != null
                    && (planningOperationId.isBlank() || expectedBaseSha.isBlank())) {
                throw new IllegalArgumentException(
                        "ThreadTurn planning fence must not be blank");
            }
        }
    }

    public record ThreadTurnRequestReceipt(
            State state,
            String commandId,
            String actor,
            long expectedVersion,
            String turnId,
            String operationId,
            String ticketId,
            String purpose,
            String deliveryLane,
            String launchInputDigest,
            String userMessageDigest,
            Instant recordedAt)
    {
        public ThreadTurnRequestReceipt
        {
            requireNonNull(state, "state is null");
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(turnId, "turnId");
            requireText(operationId, "operationId");
            requireText(ticketId, "ticketId");
            requireText(purpose, "purpose");
            requireText(deliveryLane, "deliveryLane");
            requireDigest(launchInputDigest, "launchInputDigest");
            requireDigest(userMessageDigest, "userMessageDigest");
            requireNonNull(recordedAt, "recordedAt is null");
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion is negative");
            }
        }
    }

    public record ThreadTurnResultFact(
            String commandId,
            String actor,
            String turnId,
            String operationId,
            int attempt,
            String rawOutcome,
            String rawResultDigest,
            String finalText,
            String error,
            Instant finishedAt)
    {
        public ThreadTurnResultFact
        {
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(turnId, "turnId");
            requireText(operationId, "operationId");
            requireText(rawOutcome, "rawOutcome");
            requireDigest(rawResultDigest, "rawResultDigest");
            requireNonNull(finishedAt, "finishedAt is null");
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be positive");
            }
            terminalStatus(rawOutcome);
            if (finalText != null && finalText.isBlank()) {
                finalText = null;
            }
            if (error != null && error.isBlank()) {
                error = null;
            }
        }
    }

    public record ThreadTurnResultContext(
            State trunkState,
            String turnStatus,
            String operationId,
            int attempt,
            boolean otherLiveWork)
    {
        public ThreadTurnResultContext
        {
            requireNonNull(trunkState, "trunkState is null");
            requireText(turnStatus, "turnStatus");
            requireText(operationId, "operationId");
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be positive");
            }
        }
    }

    public record ThreadTurnResultReceipt(
            State state,
            String commandId,
            String actor,
            String turnId,
            String operationId,
            int attempt,
            String rawResultDigest,
            String rawOutcome,
            String acceptance,
            String terminalStatus,
            String assistantMessageId,
            Instant recordedAt)
    {
        public ThreadTurnResultReceipt
        {
            requireNonNull(state, "state is null");
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(turnId, "turnId");
            requireText(operationId, "operationId");
            requireDigest(rawResultDigest, "rawResultDigest");
            requireText(rawOutcome, "rawOutcome");
            requireText(acceptance, "acceptance");
            requireText(terminalStatus, "terminalStatus");
            requireNonNull(recordedAt, "recordedAt is null");
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be positive");
            }
        }
    }

    public record TaskCreationAuthorizationReceipt(
            State state,
            String actor,
            long expectedVersion,
            String assignmentId,
            String authorizationId,
            String policyRevisionId)
    {
        public TaskCreationAuthorizationReceipt
        {
            requireNonNull(state, "state is null");
            requireText(actor, "actor");
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion is negative");
            }
            requireText(assignmentId, "assignmentId");
            requireText(authorizationId, "authorizationId");
            requireText(policyRevisionId, "policyRevisionId");
        }
    }

    /** Opaque proof that Trunk authorized this exact creation command. */
    public static final class AuthorizedTaskCreation
    {
        private final TaskCreationCommand command;
        private final CommandResult.Disposition disposition;
        private final State trunkState;

        private AuthorizedTaskCreation(
                TaskCreationCommand command,
                CommandResult.Disposition disposition,
                State trunkState)
        {
            this.command = requireNonNull(command, "command is null");
            this.disposition = requireNonNull(disposition, "disposition is null");
            this.trunkState = requireNonNull(trunkState, "trunkState is null");
        }

        public TaskCreationCommand command() { return command; }

        public CommandResult.Disposition disposition() { return disposition; }

        public State trunkState() { return trunkState; }
    }

    /** Immutable replay result, stored separately from versioned transition audit. */
    public record CommandReceipt(
            State state,
            String cause,
            String actor,
            long expectedVersion,
            CommandResult.Disposition disposition)
    {
        public CommandReceipt
        {
            requireNonNull(state, "state is null");
            requireText(cause, "cause");
            requireText(actor, "actor");
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion is negative");
            }
            requireNonNull(disposition, "disposition is null");
            if (disposition != CommandResult.Disposition.APPLIED) {
                throw new IllegalArgumentException("Trunk receipt must describe an applied command");
            }
        }
    }

    /** Persistence port. Commit must atomically compare version and record command id. */
    public interface Store
    {
        Optional<State> findById(String trunkId);

        Optional<CommandReceipt> findCommandResult(String trunkId, String commandId);

        Optional<TaskCreationAuthorizationReceipt> findTaskCreationAuthorization(
                String trunkId, String commandId);

        default Optional<PlanningBaseRequestReceipt> findPlanningBaseRequest(
                String trunkId, String commandId)
        {
            return Optional.empty();
        }

        default boolean matchesPlanningBaseRequest(PlanningBaseCommand command)
        {
            return false;
        }

        default PlanningBaseRequestReceipt requestPlanningBaseRefresh(
                PlanningBaseCommand command, State expected, State updated)
        {
            throw new UnsupportedOperationException(
                    "Planning-base persistence is unavailable");
        }

        default Optional<String> findPlanningBaseTrunk(String operationId)
        {
            return Optional.empty();
        }

        default Optional<PlanningBaseResultReceipt> findPlanningBaseResult(
                String operationId)
        {
            return Optional.empty();
        }

        default PlanningBaseResultContext requirePlanningBaseResult(
                String operationId)
        {
            throw new UnsupportedOperationException(
                    "Planning-base persistence is unavailable");
        }

        default PlanningBaseResultReceipt acceptPlanningBaseResult(
                PlanningBaseResultFact fact,
                PlanningBaseResultContext context,
                State updated,
                String acceptance,
                String operationStatus)
        {
            throw new UnsupportedOperationException(
                    "Planning-base persistence is unavailable");
        }

        default boolean isPlanningBaseCommandUsed(
                String trunkId, String commandId)
        {
            return false;
        }

        default boolean isPlanningLaunchPending(
                String planningOperationId, String trunkId, String turnId)
        {
            return false;
        }

        default void markPlanningLaunch(
                String planningOperationId, String turnId, Instant launchedAt)
        {
            throw new UnsupportedOperationException(
                    "Planning launch persistence is unavailable");
        }

        default int suppressPlanningLaunches(
                String trunkId, String reason, Instant suppressedAt)
        {
            return 0;
        }

        default Optional<String> findTaskOutcomeTrunk(
                String taskOutcomeId, String deliveryKey)
        {
            return Optional.empty();
        }

        default Optional<String> findTaskOutcomeTrunk(String taskOutcomeId)
        {
            return Optional.empty();
        }

        default Optional<TaskOutcomeReceipt> findTaskOutcomeReceipt(
                String taskOutcomeId, String deliveryKey)
        {
            return Optional.empty();
        }

        default TaskOutcomeContext requireTaskOutcome(
                String taskOutcomeId, String deliveryKey)
        {
            throw new UnsupportedOperationException(
                    "TaskOutcome inbox persistence is unavailable");
        }

        default TaskOutcomeReceipt acceptTaskOutcome(
                TaskOutcomeFact fact,
                TaskOutcomeContext context,
                State updated)
        {
            throw new UnsupportedOperationException(
                    "TaskOutcome inbox persistence is unavailable");
        }

        default boolean isTaskOutcomeCommandUsed(
                String trunkId, String commandId)
        {
            return false;
        }

        default TaskOutcomeSummaryState requireTaskOutcomeSummary(
                String taskOutcomeId)
        {
            throw new UnsupportedOperationException(
                    "TaskOutcome summary persistence is unavailable");
        }

        default TaskOutcomeSummaryState enrichTaskOutcomeSummary(
                TaskOutcomeSummaryFact fact,
                TaskOutcomeSummaryState current)
        {
            throw new UnsupportedOperationException(
                    "TaskOutcome summary persistence is unavailable");
        }

        boolean matchesTaskCreationAuthorization(TaskCreationCommand command);

        Optional<ThreadTurnRequestReceipt> findThreadTurnRequest(
                String trunkId, String commandId);

        boolean matchesThreadTurnRequest(ThreadTurnCommand command);

        ThreadTurnRequestReceipt requestThreadTurn(
                ThreadTurnCommand command, State expected, State updated);

        Optional<String> findThreadTurnTrunk(String turnId, String operationId);

        Optional<ThreadTurnResultReceipt> findThreadTurnResult(
                String turnId, String operationId);

        /** True for any request or result command receipt on this Trunk. */
        default boolean isThreadTurnCommandUsed(
                String trunkId, String commandId)
        {
            return false;
        }

        ThreadTurnResultContext requireThreadTurnResult(
                String turnId, String operationId);

        ThreadTurnResultReceipt acceptThreadTurnResult(
                ThreadTurnResultFact fact,
                ThreadTurnResultContext context,
                State updated,
                String acceptance,
                String terminalStatus);

        State authorizeTaskCreation(
                TaskCreationCommand command, State expected, State updated);

        State commit(
                String commandId,
                String cause,
                String actor,
                long expectedVersion,
                State expected,
                State updated);
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    private static void requireDigest(String value, String name)
    {
        requireText(value, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
    }
}
