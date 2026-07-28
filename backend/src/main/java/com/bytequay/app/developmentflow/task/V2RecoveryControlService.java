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
import com.bytequay.app.developmentflow.execution.cleanup.SqliteCleanupOperationStore;
import com.bytequay.app.developmentflow.execution.cleanup.SqliteCleanupOperationStore.RecoveryAuthorization;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteObservationRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CiEpisode;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationRequest;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;

import static java.util.Objects.requireNonNull;

/** Exact owner commands for V2 CI exhaustion and Cleanup recovery. */
@Component
public final class V2RecoveryControlService
{
    private static final String ACTOR = "user";

    private final TaskCommandExecutor commands;
    private final RemoteCiRepairRuntimeCoordinator ciRepair;
    private final RemoteObservationRuntimeCoordinator observations;
    private final SqliteCleanupOperationStore cleanup;
    private final DispatchTicketControl tickets;
    private final Clock clock;

    @Autowired
    public V2RecoveryControlService(
            TaskCommandExecutor commands,
            RemoteCiRepairRuntimeCoordinator ciRepair,
            RemoteObservationRuntimeCoordinator observations,
            SqliteCleanupOperationStore cleanup,
            DispatchTicketControl tickets)
    {
        this(commands, ciRepair, observations, cleanup, tickets, Clock.systemUTC());
    }

    V2RecoveryControlService(
            TaskCommandExecutor commands,
            RemoteCiRepairRuntimeCoordinator ciRepair,
            RemoteObservationRuntimeCoordinator observations,
            SqliteCleanupOperationStore cleanup,
            DispatchTicketControl tickets,
            Clock clock)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.ciRepair = requireNonNull(ciRepair, "ciRepair is null");
        this.observations = requireNonNull(observations, "observations is null");
        this.cleanup = requireNonNull(cleanup, "cleanup is null");
        this.tickets = requireNonNull(tickets, "tickets is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public CiRecoveryResult recoverCi(
            String taskId, String episodeId, CiRecoveryCommand command)
    {
        requireText(taskId, "taskId");
        requireText(episodeId, "episodeId");
        requireNonNull(command, "command is null");
        requireText(command.commandId(), "commandId");
        requireText(command.reason(), "reason");
        requireNonNull(command.action(), "action is null");
        validateDeltas(command);
        try {
            CiEpisode episode = switch (command.action()) {
                case EXTEND_BUDGET -> ciRepair.extendBudget(
                        taskId, episodeId, command.commandId(),
                        command.rerunDelta(), command.fixDelta(),
                        command.pushDelta(), ACTOR, command.reason());
                case CONTINUE_WITH_PER_PUSH_APPROVAL ->
                        ciRepair.continueWithPerPushApproval(
                                taskId, episodeId, command.commandId(),
                                ACTOR, command.reason());
                case MANUAL_TAKEOVER -> ciRepair.manualTakeover(
                        taskId, episodeId, command.commandId(),
                        ACTOR, command.reason());
                case STOP_AUTOMATION -> ciRepair.stopAutomation(
                        taskId, episodeId, command.commandId(),
                        ACTOR, command.reason());
            };
            String observationOperationId = null;
            if (command.action() == CiRecoveryAction.EXTEND_BUDGET
                    || command.action()
                    == CiRecoveryAction.CONTINUE_WITH_PER_PUSH_APPROVAL) {
                ObservationRequest observation = observations.requestObservation(
                        taskId, episode.stageId());
                observationOperationId = observation.operationId();
            }
            return new CiRecoveryResult(
                    taskId, episodeId, command.commandId(), command.action(),
                    episode.status(), episode.rerunLimit(),
                    episode.fixAttemptLimit(), episode.pushLimit(),
                    observationOperationId);
        }
        catch (DataAccessException | IllegalStateException failure) {
            throw conflict("CI recovery does not own the exact exhausted episode",
                    failure);
        }
        catch (IllegalArgumentException failure) {
            throw conflict(failure.getMessage(), failure);
        }
    }

    public CleanupRecoveryResult recoverCleanup(
            String taskId, String stepId, CleanupRecoveryCommand command)
    {
        requireText(taskId, "taskId");
        requireText(stepId, "stepId");
        requireNonNull(command, "command is null");
        requireText(command.commandId(), "commandId");
        requireText(command.reason(), "reason");
        requireNonNull(command.action(), "action is null");
        try {
            RecoveryAuthorization authorization = commands.execute(
                    taskId, () -> switch (command.action()) {
                        case RETRY -> cleanup.authorizeRetry(
                                taskId, stepId, command.commandId(), ACTOR,
                                command.reason(), clock.instant());
                        case WAIVE_OPTIONAL -> cleanup.authorizeOptionalWaiver(
                                taskId, stepId, command.commandId(), ACTOR,
                                command.reason(), clock.instant());
                    });
            boolean rearmed = tickets.resumeDeferred(
                    authorization.dispatchTicketId());
            return new CleanupRecoveryResult(
                    taskId, stepId, command.commandId(), command.action(),
                    authorization.cleanupOperationId(),
                    authorization.dispatchTicketId(), authorization.created(),
                    rearmed);
        }
        catch (DataAccessException | IllegalStateException failure) {
            throw conflict("Cleanup recovery does not own the exact failed step",
                    failure);
        }
        catch (IllegalArgumentException failure) {
            throw conflict(failure.getMessage(), failure);
        }
    }

    private static void validateDeltas(CiRecoveryCommand command)
    {
        boolean valid = command.rerunDelta() >= 0
                && command.fixDelta() >= 0
                && command.pushDelta() >= 0;
        if (command.action() == CiRecoveryAction.EXTEND_BUDGET) {
            valid &= command.rerunDelta() + command.fixDelta()
                    + command.pushDelta() > 0;
        }
        else {
            valid &= command.rerunDelta() == 0
                    && command.fixDelta() == 0
                    && command.pushDelta() == 0;
        }
        if (!valid) {
            throw new IllegalArgumentException(
                    "Only EXTEND_BUDGET accepts positive CI budget deltas");
        }
    }

    private static ResponseStatusException conflict(
            String message, RuntimeException cause)
    {
        return new ResponseStatusException(HttpStatus.CONFLICT, message, cause);
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public enum CiRecoveryAction
    {
        EXTEND_BUDGET,
        CONTINUE_WITH_PER_PUSH_APPROVAL,
        MANUAL_TAKEOVER,
        STOP_AUTOMATION
    }

    public record CiRecoveryCommand(
            String commandId,
            CiRecoveryAction action,
            int rerunDelta,
            int fixDelta,
            int pushDelta,
            String reason) {}

    public record CiRecoveryResult(
            String taskId,
            String episodeId,
            String commandId,
            CiRecoveryAction action,
            String status,
            int rerunLimit,
            int fixAttemptLimit,
            int pushLimit,
            String observationOperationId) {}

    public enum CleanupRecoveryAction
    {
        RETRY,
        WAIVE_OPTIONAL
    }

    public record CleanupRecoveryCommand(
            String commandId,
            CleanupRecoveryAction action,
            String reason) {}

    public record CleanupRecoveryResult(
            String taskId,
            String stepId,
            String commandId,
            CleanupRecoveryAction action,
            String cleanupOperationId,
            String dispatchTicketId,
            boolean created,
            boolean rearmed) {}
}
