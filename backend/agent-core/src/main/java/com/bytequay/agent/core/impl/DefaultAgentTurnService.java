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
package com.bytequay.agent.core.impl;

import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentAttachmentReference;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventOutboxEntry;
import com.bytequay.agent.core.AgentEventOutboxPayload;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentSandboxPolicy;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnNotFoundException;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.UnsequencedAgentEvent;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.RunningTurn;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static com.bytequay.agent.core.AgentEventSource.root;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

public final class DefaultAgentTurnService
        implements AgentTurnService
{
    private static final int OUTBOX_BATCH_SIZE = 100;

    private final AgentTaskStore taskStore;
    private final AgentTurnStore turnStore;
    private final AgentUnitOfWork unitOfWork;
    private final AgentEventOutboxStore outboxStore;
    private final TaskLogStore logStore;
    private final AgentResourceService resourceService;
    private final AgentDriverRegistry driverRegistry;
    private final AgentEventService eventService;
    private final AgentMetricsService metricsService;
    private final AgentApprovalService approvalService;
    private final AgentRuntimeRegistry runtimeRegistry;
    private final Clock clock;
    private final Duration maximumTurnRuntime;

    public DefaultAgentTurnService(
            AgentTaskStore taskStore,
            AgentTurnStore turnStore,
            AgentUnitOfWork unitOfWork,
            AgentEventOutboxStore outboxStore,
            TaskLogStore logStore,
            AgentResourceService resourceService,
            AgentDriverRegistry driverRegistry,
            AgentEventService eventService,
            AgentMetricsService metricsService,
            AgentApprovalService approvalService,
            AgentRuntimeRegistry runtimeRegistry,
            Clock clock,
            Duration maximumTurnRuntime)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.unitOfWork = requireNonNull(unitOfWork, "unitOfWork is null");
        this.outboxStore = requireNonNull(outboxStore, "outboxStore is null");
        this.logStore = requireNonNull(logStore, "logStore is null");
        this.resourceService = requireNonNull(resourceService, "resourceService is null");
        this.driverRegistry = requireNonNull(driverRegistry, "driverRegistry is null");
        this.eventService = requireNonNull(eventService, "eventService is null");
        this.metricsService = requireNonNull(metricsService, "metricsService is null");
        this.approvalService = requireNonNull(approvalService, "approvalService is null");
        this.runtimeRegistry = requireNonNull(runtimeRegistry, "runtimeRegistry is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.maximumTurnRuntime = requireNonNull(maximumTurnRuntime, "maximumTurnRuntime is null");
        checkArgument(!maximumTurnRuntime.isZero() && !maximumTurnRuntime.isNegative(), "maximumTurnRuntime must be positive");
    }

    @Override
    public AgentTurn start(StartAgentTurnCommand command)
    {
        requireNonNull(command, "command is null");
        StartedTurn started = null;
        RunningTurn failedRunningTurn = null;
        AgentTurn failedTurn = null;
        RuntimeException startFailure = null;
        synchronized (runtimeRegistry.taskLock(command.taskId())) {
            drainPendingEvents(command.taskId());
            Optional<AgentTurn> repeated = turnStore.findByIdempotencyKey(
                    command.taskId(),
                    command.idempotencyKey());
            if (repeated.isPresent()) {
                return repeated.orElseThrow();
            }

            AgentTask task = requireTask(command.taskId());
            if (task.state() != TaskState.OPEN) {
                throw new AgentConflictException("Task cannot start a turn in state " + task.state());
            }
            turnStore.findActive(task.id()).ifPresent(active -> {
                throw new AgentConflictException("Task already has an active turn: " + active.id());
            });
            AgentProviderStatus status = driverRegistry.require(task.laneId()).probe();
            if (!status.available()) {
                throw new AgentUnavailableException(status.diagnostic());
            }

            TurnId turnId = TurnId.random();
            Instant acceptedAt = Instant.now(clock);
            AgentTurnSettings settings = new AgentTurnSettings(
                    task.defaults().model(),
                    task.defaults().effort(),
                    task.permissionPolicy(),
                    AgentSandboxPolicy.WORKSPACE_WRITE,
                    maximumTurnRuntime,
                    command.attachments().stream()
                            .map(attachment -> new AgentAttachmentReference(
                                    UUID.randomUUID().toString(),
                                    attachment.name(),
                                    attachment.mediaType()))
                            .collect(toImmutableList()));
            AgentTurn accepted = AgentTurn.accepted(
                    turnId,
                    task.id(),
                    command.idempotencyKey(),
                    settings,
                    acceptedAt);
            BlobRef messageBlob = logStore.writeBlob(
                    task.id(),
                    ByteBuffer.wrap(command.message().getBytes(UTF_8)),
                    "text/plain; charset=utf-8");
            EventId messageEventId = EventId.random();
            unitOfWork.execute(() -> {
                turnStore.insert(accepted);
                outboxStore.insert(new AgentEventOutboxEntry(
                        messageEventId,
                        accepted.taskId(),
                        Optional.of(accepted.id()),
                        root(),
                        new AgentEventOutboxPayload.UserMessageBlob(
                                messageBlob,
                                settings.attachments(),
                                AgentEventPayload.DeliveryKind.TURN),
                        acceptedAt,
                        Optional.empty()));
                return null;
            });
            try {
                eventService.drain(messageEventId);
                AgentSession session = session(task);
                metricsService.beginTurn(task.id(), accepted.id());
                turnStore.update(requireTurn(accepted.id()).markDeliveryStarted(Instant.now(clock)));
                RunningTurn runningTurn = session.start(new AgentTurnCommand(
                        accepted.id().toString(),
                        command.message(),
                        command.attachments(),
                        settings.maximumRuntime()));
                failedRunningTurn = runningTurn;
                ProviderTurnRef providerTurnReference = runningTurn.reference();
                AgentTurn running = turnStore.update(requireTurn(accepted.id()).running(
                        providerTurnReference,
                        Optional.empty()));
                runtimeRegistry.registerRunning(running.id(), runningTurn);
                started = new StartedTurn(running, runningTurn);
            }
            catch (RuntimeException failure) {
                recordFailedStart(accepted, failure);
                failedTurn = accepted;
                startFailure = failure;
            }
        }
        if (startFailure != null) {
            if (failedRunningTurn != null) {
                observeCompletion(requireNonNull(failedTurn, "failed turn is null"), failedRunningTurn);
                cancelAndSuppressFailure(failedRunningTurn, startFailure);
            }
            denyPendingAndSuppressFailure(requireNonNull(failedTurn, "failed turn is null").id(), startFailure);
            throw startFailure;
        }

        StartedTurn completedStart = requireNonNull(started, "started turn is null");
        try {
            observeCompletion(completedStart.turn(), completedStart.runningTurn());
        }
        catch (RuntimeException failure) {
            synchronized (runtimeRegistry.taskLock(command.taskId())) {
                runtimeRegistry.removeRunning(completedStart.turn().id(), completedStart.runningTurn());
                recordFailedStart(completedStart.turn(), failure);
            }
            cancelAndSuppressFailure(completedStart.runningTurn(), failure);
            denyPendingAndSuppressFailure(completedStart.turn().id(), failure);
            throw failure;
        }
        return requireTurn(completedStart.turn().id());
    }

    private void drainPendingEvents(TaskId taskId)
    {
        while (true) {
            List<AgentEventOutboxEntry> pending = outboxStore.listPending(taskId, OUTBOX_BATCH_SIZE);
            if (pending.isEmpty()) {
                return;
            }
            pending.forEach(entry -> eventService.drain(entry.eventId()));
        }
    }

    @Override
    public AgentTurn cancel(TurnId turnId)
    {
        requireNonNull(turnId, "turnId is null");
        AgentTurn initial = requireTurn(turnId);
        RunningTurn running;
        synchronized (runtimeRegistry.taskLock(initial.taskId())) {
            AgentTurn turn = requireTurn(turnId);
            if (turn.state().terminal()) {
                return turn;
            }
            AgentTurn cancellation = turn.requestCancellation(Instant.now(clock));
            if (cancellation != turn) {
                turnStore.update(cancellation);
            }
            running = runtimeRegistry.running(turnId)
                    .orElseThrow(() -> new AgentConflictException("Turn has no live provider handle: " + turnId));
        }
        // Approval and provider cancellation may complete callbacks that
        // re-enter this service, so both run after releasing the task lock.
        try {
            approvalService.denyPending(turnId);
        }
        catch (RuntimeException failure) {
            cancelAndSuppressFailure(running, failure);
            throw failure;
        }
        running.cancel();
        return requireTurn(turnId);
    }

    @Override
    public AgentTurn get(TurnId turnId)
    {
        requireNonNull(turnId, "turnId is null");
        return requireTurn(turnId);
    }

    private AgentSession session(AgentTask task)
    {
        Optional<AgentSession> existing = runtimeRegistry.session(task.id());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }

        AgentDriver driver = driverRegistry.require(task.laneId());
        TaskResources resources = resourceService.require(task.id());
        AgentSession opened = requireNonNull(driver.open(
                new AgentSessionContext(
                        task.id().toString(),
                        task.worktree(),
                        resources.runtimeDirectory(),
                        resources.providerDirectory(),
                        task.permissionPolicy(),
                        task.sessionReference(),
                        (request, timeout) -> requestApproval(task.id(), request, timeout)),
                event -> routeEvent(task.id(), event)), "opened session is null");
        try {
            AgentSessionRef reference = requireNonNull(opened.reference(), "session reference is null");
            if (!reference.laneId().equals(task.laneId())) {
                throw new AgentUnavailableException("Provider returned a session reference for another lane");
            }
            AgentTask current = requireTask(task.id());
            if (current.sessionReference().isPresent()
                    && !current.sessionReference().orElseThrow().equals(reference)) {
                throw new AgentUnavailableException("Provider resumed a different session reference");
            }
            if (current.sessionReference().isEmpty()) {
                taskStore.update(current.withSessionReference(reference, Instant.now(clock)));
            }
            return runtimeRegistry.registerSession(task.id(), opened);
        }
        catch (RuntimeException failure) {
            closeAfterOpenFailure(opened, failure);
            throw failure;
        }
    }

    private void routeEvent(TaskId taskId, ProviderAgentEvent event)
    {
        requireNonNull(event, "event is null");
        AgentTurn turn = requireActiveTurn(taskId);
        eventService.append(new UnsequencedAgentEvent(taskId, Optional.of(turn.id()), event));
        if (event instanceof ProviderAgentEvent.Usage usage) {
            metricsService.record(taskId, turn.id(), usage.sample());
        }
    }

    private CompletionStage<ProviderApprovalDecision> requestApproval(
            TaskId taskId,
            ProviderApprovalRequest request,
            Duration timeout)
    {
        synchronized (runtimeRegistry.taskLock(taskId)) {
            AgentTurn turn = requireActiveTurn(taskId);
            if (turn.cancellationRequestedAt().isPresent()) {
                throw new AgentConflictException("Task turn is being cancelled: " + turn.id());
            }
            ApprovalRequest approval = new ApprovalRequest(
                    ApprovalId.random(),
                    taskId,
                    turn.id(),
                    request,
                    Instant.now(clock));
            return approvalService.request(approval, timeout)
                    .thenApply(DefaultAgentTurnService::toProviderDecision);
        }
    }

    private void observeCompletion(AgentTurn turn, RunningTurn runningTurn)
    {
        runningTurn.completion().whenComplete((result, failure) -> finishTurn(turn, runningTurn, result, failure));
    }

    private void finishTurn(
            AgentTurn observedTurn,
            RunningTurn runningTurn,
            AgentTurnResult result,
            Throwable failure)
    {
        synchronized (runtimeRegistry.taskLock(observedTurn.taskId())) {
            runtimeRegistry.removeRunning(observedTurn.id(), runningTurn);
            AgentTurn turn = requireTurn(observedTurn.id());
            if (turn.state().terminal()) {
                return;
            }
            if (failure != null) {
                return;
            }
            TurnState terminalState = terminalState(result.state());
            Optional<String> reason = terminalState == TurnState.COMPLETED
                    ? Optional.empty()
                    : Optional.of(result.terminalReason().orElseThrow());
            TurnMetrics finalMetrics = metricsService.finishTurn(turn.id(), terminalState);
            AgentTurn latest = requireTurn(turn.id());
            turnStore.update(latest.finish(terminalState, finalMetrics, reason, Instant.now(clock)));
        }
        // Completing an approval can run provider continuations. The terminal
        // turn is the barrier that rejects new requests after releasing the lock.
        approvalService.denyPending(observedTurn.id());
    }

    private void recordFailedStart(AgentTurn acceptedTurn, RuntimeException failure)
    {
        try {
            Instant now = Instant.now(clock);
            AgentTurn turn = requireTurn(acceptedTurn.id());
            if (turn.startedAt().isPresent()) {
                return;
            }
            TurnMetrics finalMetrics = metricsService.finishTurn(acceptedTurn.id(), TurnState.FAILED);
            turn = requireTurn(acceptedTurn.id());
            if (!turn.state().terminal()) {
                turnStore.update(turn.finish(
                        TurnState.FAILED,
                        finalMetrics,
                        Optional.of(failureMessage(failure)),
                        now));
            }
        }
        catch (RuntimeException cleanupFailure) {
            if (failure != cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private void denyPendingAndSuppressFailure(TurnId turnId, RuntimeException failure)
    {
        try {
            approvalService.denyPending(turnId);
        }
        catch (RuntimeException cleanupFailure) {
            if (failure != cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void cancelAndSuppressFailure(RunningTurn runningTurn, RuntimeException failure)
    {
        try {
            runningTurn.cancel();
        }
        catch (RuntimeException cancellationFailure) {
            if (failure != cancellationFailure) {
                failure.addSuppressed(cancellationFailure);
            }
        }
    }

    private static void closeAfterOpenFailure(AgentSession session, RuntimeException failure)
    {
        try {
            session.close();
        }
        catch (RuntimeException closeFailure) {
            if (failure != closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    private AgentTask requireTask(TaskId taskId)
    {
        return taskStore.find(taskId).orElseThrow(() -> new AgentTaskNotFoundException(taskId));
    }

    private AgentTurn requireTurn(TurnId turnId)
    {
        return turnStore.find(turnId).orElseThrow(() -> new AgentTurnNotFoundException(turnId));
    }

    private AgentTurn requireActiveTurn(TaskId taskId)
    {
        return turnStore.findActive(taskId)
                .orElseThrow(() -> new AgentConflictException("Task has no active turn: " + taskId));
    }

    private static ProviderApprovalDecision toProviderDecision(ApprovalDecision decision)
    {
        return switch (decision.decision()) {
            case ALLOW -> ProviderApprovalDecision.allow(decision.scopedPermission());
            case DENY -> ProviderApprovalDecision.deny();
        };
    }

    private static TurnState terminalState(AgentTurnResult.State state)
    {
        return switch (state) {
            case COMPLETED -> TurnState.COMPLETED;
            case FAILED -> TurnState.FAILED;
            case CANCELLED -> TurnState.CANCELLED;
            case INTERRUPTED -> TurnState.INTERRUPTED;
        };
    }

    private static String failureMessage(Throwable failure)
    {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private record StartedTurn(AgentTurn turn, RunningTurn runningTurn)
    {
        private StartedTurn
        {
            requireNonNull(turn, "turn is null");
            requireNonNull(runningTurn, "runningTurn is null");
        }
    }
}
