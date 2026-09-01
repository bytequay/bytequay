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
import com.bytequay.agent.core.ProviderAgentEventAdapter;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.UnsequencedAgentEvent;
import com.bytequay.agent.core.WorktreeMutationClaim;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static com.bytequay.agent.core.AgentEventOutboxEntry.pendingInline;
import static com.bytequay.agent.core.AgentEventOutboxEntry.pendingLifecycle;
import static com.bytequay.agent.core.AgentEventSource.root;
import static com.bytequay.agent.core.impl.AgentEventOutboxDrainer.drainPending;
import static com.bytequay.agent.core.impl.AgentFailures.failureMessage;
import static com.bytequay.agent.core.impl.AgentFailures.suppressFailure;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

public final class DefaultAgentTurnService
        implements AgentTurnService
{
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
        retryCompletedTurn(command.taskId());
        StartedTurn started = null;
        RunningTurn failedRunningTurn = null;
        AgentTurn failedTurn = null;
        RuntimeException startFailure = null;
        synchronized (runtimeRegistry.taskLock(command.taskId())) {
            drainPending(outboxStore, eventService, command.taskId());
            Optional<AgentTurn> repeated = turnStore.findByIdempotencyKey(
                    command.taskId(),
                    command.idempotencyKey());
            if (repeated.isPresent()) {
                return repeated.orElseThrow();
            }

            AgentTask task = requireStartableTask(command.taskId());
            TurnAcceptance acceptance = acceptTurnLocked(task, command);
            AgentTurn accepted = acceptance.turn();
            boolean deliveryAttempted = false;
            try {
                eventService.drain(acceptance.messageEventId());
                AgentSession session = session(task, accepted.id());
                metricsService.beginTurn(task.id(), accepted.id());
                markDeliveryStarted(accepted.id());
                deliveryAttempted = true;
                RunningTurn runningTurn = session.start(new AgentTurnCommand(
                        accepted.id().toString(),
                        command.message(),
                        command.attachments(),
                        acceptance.settings().maximumRuntime(),
                        acceptance.settings().model(),
                        acceptance.settings().effort()));
                failedRunningTurn = runningTurn;
                started = registerRunningTurn(accepted, runningTurn);
            }
            catch (RuntimeException failure) {
                if (!deliveryAttempted) {
                    recordKnownUnsentFailure(accepted, failure);
                }
                failedTurn = accepted;
                startFailure = failure;
            }
        }
        if (startFailure != null) {
            cleanupFailedStart(
                    requireNonNull(failedTurn, "failed turn is null"),
                    failedRunningTurn,
                    startFailure);
            throw startFailure;
        }
        return observeStartedTurn(command.taskId(), requireNonNull(started, "started turn is null"));
    }

    private void cleanupFailedStart(AgentTurn turn, RunningTurn runningTurn, RuntimeException failure)
    {
        if (runningTurn != null) {
            observeCompletion(turn, runningTurn);
            suppressFailure(failure, runningTurn::cancel);
        }
        suppressFailure(failure, () -> approvalService.denyPending(turn.id()));
    }

    private AgentTurn observeStartedTurn(TaskId taskId, StartedTurn started)
    {
        try {
            observeCompletion(started.turn(), started.runningTurn());
        }
        catch (RuntimeException failure) {
            synchronized (runtimeRegistry.taskLock(taskId)) {
                runtimeRegistry.removeRunning(started.turn().id(), started.runningTurn());
            }
            suppressFailure(failure, started.runningTurn()::cancel);
            suppressFailure(failure, () -> approvalService.denyPending(started.turn().id()));
            throw failure;
        }
        return requireTurn(started.turn().id());
    }

    private AgentTask requireStartableTask(TaskId taskId)
    {
        AgentTask task = requireTask(taskId);
        if (task.state() != TaskState.OPEN) {
            throw new AgentConflictException("Task cannot start a turn in state " + task.state());
        }
        turnStore.findActive(task.id()).ifPresent(active -> {
            throw new AgentConflictException("Task already has an active turn: " + active.id());
        });
        task.worktreeMutationClaim().ifPresent(claim -> {
            throw new AgentConflictException("Task worktree is claimed by " + claim.kind());
        });
        AgentProviderStatus status = driverRegistry.require(task.laneId()).probe();
        if (!status.available()) {
            throw new AgentUnavailableException(status.diagnostic());
        }
        return task;
    }

    private TurnAcceptance acceptTurnLocked(AgentTask task, StartAgentTurnCommand command)
    {
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
        AgentTurn turn = AgentTurn.accepted(
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
            taskStore.update(task.claimWorktreeMutation(WorktreeMutationClaim.turn(turnId, acceptedAt), acceptedAt));
            turnStore.insert(turn);
            outboxStore.insert(new AgentEventOutboxEntry(
                    messageEventId,
                    turn.taskId(),
                    Optional.of(turn.id()),
                    root(),
                    new AgentEventOutboxPayload.UserMessageBlob(
                            messageBlob,
                            settings.attachments(),
                            AgentEventPayload.DeliveryKind.TURN),
                    acceptedAt,
                    Optional.empty()));
            return null;
        });
        return new TurnAcceptance(turn, settings, messageEventId);
    }

    private StartedTurn registerRunningTurn(AgentTurn accepted, RunningTurn runningTurn)
    {
        ProviderTurnRef providerTurnReference = runningTurn.reference();
        AgentTurn running = turnStore.update(requireTurn(accepted.id()).running(
                providerTurnReference,
                Optional.empty()));
        runtimeRegistry.registerRunning(running.id(), runningTurn);
        return new StartedTurn(running, runningTurn);
    }

    private void markDeliveryStarted(TurnId turnId)
    {
        Instant startedAt = Instant.now(clock);
        EventId eventId = EventId.random();
        unitOfWork.execute(() -> {
            AgentTurn stored = turnStore.update(requireTurn(turnId).markDeliveryStarted(startedAt));
            outboxStore.insert(pendingLifecycle(
                    eventId,
                    stored.taskId(),
                    Optional.of(stored.id()),
                    AgentEventPayload.LifecyclePhase.TURN_STARTED,
                    "Turn started",
                    startedAt));
            return null;
        });
        eventService.drain(eventId);
    }

    @Override
    public AgentTurn cancel(TurnId turnId)
    {
        requireNonNull(turnId, "turnId is null");
        AgentTurn initial = requireTurn(turnId);
        retryCompletedTurn(initial);
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
            suppressFailure(failure, running::cancel);
            throw failure;
        }
        running.cancel();
        return requireTurn(turnId);
    }

    @Override
    public AgentTurn get(TurnId turnId)
    {
        requireNonNull(turnId, "turnId is null");
        AgentTurn turn = requireTurn(turnId);
        retryCompletedTurn(turn);
        return requireTurn(turnId);
    }

    private AgentSession session(AgentTask task, TurnId turnId)
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
                Instant openedAt = Instant.now(clock);
                EventId eventId = EventId.random();
                unitOfWork.execute(() -> {
                    taskStore.update(current.withSessionReference(reference, openedAt));
                    outboxStore.insert(pendingLifecycle(
                            eventId,
                            current.id(),
                            Optional.of(turnId),
                            AgentEventPayload.LifecyclePhase.SESSION_OPENED,
                            "Provider session opened",
                            openedAt));
                    return null;
                });
                eventService.drain(eventId);
            }
            return runtimeRegistry.registerSession(task.id(), opened);
        }
        catch (RuntimeException failure) {
            suppressFailure(failure, opened::close);
            throw failure;
        }
    }

    private void routeEvent(TaskId taskId, ProviderAgentEvent event)
    {
        requireNonNull(event, "event is null");
        if (event instanceof ProviderAgentEvent.Lifecycle lifecycle && stateCoupled(lifecycle.phase())) {
            return;
        }
        if (event instanceof ProviderAgentEvent.Usage usage) {
            synchronized (runtimeRegistry.taskLock(taskId)) {
                recordUsage(taskId, requireActiveTurn(taskId), usage);
            }
            return;
        }
        AgentTurn turn = requireActiveTurn(taskId);
        eventService.append(new UnsequencedAgentEvent(taskId, Optional.of(turn.id()), event));
    }

    private void recordUsage(TaskId taskId, AgentTurn turn, ProviderAgentEvent.Usage usage)
    {
        EventId eventId = usage.providerEventId()
                .map(providerEventId -> EventId.providerUsage(taskId, turn.id(), providerEventId))
                // Without provider identity, distinct callbacks cannot be durably deduplicated.
                .orElseGet(EventId::random);
        ProviderAgentEventAdapter.CanonicalEvent event = ProviderAgentEventAdapter.adapt(usage);
        Instant occurredAt = Instant.now(clock);
        unitOfWork.execute(() -> {
            if (outboxStore.find(eventId).isPresent()) {
                return null;
            }
            metricsService.record(taskId, turn.id(), usage.sample());
            outboxStore.insert(pendingInline(
                    eventId,
                    taskId,
                    Optional.of(turn.id()),
                    event.source(),
                    event.payload(),
                    occurredAt));
            return null;
        });
        eventService.drain(eventId);
    }

    private static boolean stateCoupled(ProviderAgentEvent.LifecyclePhase phase)
    {
        return switch (phase) {
            case SESSION_OPENED, TURN_STARTED, TURN_COMPLETED, INTERRUPTED -> true;
            case RETRYING, RATE_LIMITED -> false;
        };
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
            AgentTurnResult providerResult,
            Throwable completionFailure)
    {
        synchronized (runtimeRegistry.taskLock(observedTurn.taskId())) {
            AgentTurn turn = requireTurn(observedTurn.id());
            if (!turn.state().terminal()) {
                if (completionFailure == null) {
                    AgentTurnResult result = requireNonNull(providerResult, "providerResult is null");
                    TurnState terminalState = terminalState(result.state());
                    persistTerminalTurn(turn, terminalState, terminalReason(result, terminalState));
                }
                else {
                    persistTerminalTurn(
                            turn,
                            TurnState.INTERRUPTED,
                            Optional.of(failureMessage(completionFailure)));
                }
            }
        }
        finishTerminalCallbacks(observedTurn, runningTurn);
    }

    private void finishTerminalCallbacks(AgentTurn turn, RunningTurn runningTurn)
    {
        RuntimeException drainFailure = null;
        try {
            drainPending(outboxStore, eventService, turn.taskId());
        }
        catch (RuntimeException drainError) {
            drainFailure = drainError;
        }
        // Completing an approval can run provider continuations. The terminal
        // turn is the barrier that rejects new requests after releasing the lock.
        try {
            approvalService.denyPending(turn.id());
        }
        catch (RuntimeException approvalFailure) {
            if (drainFailure == null) {
                throw approvalFailure;
            }
            if (drainFailure != approvalFailure) {
                drainFailure.addSuppressed(approvalFailure);
            }
        }
        if (drainFailure != null) {
            throw drainFailure;
        }
        runtimeRegistry.removeRunning(turn.id(), runningTurn);
    }

    private void recordKnownUnsentFailure(AgentTurn acceptedTurn, RuntimeException failure)
    {
        suppressFailure(failure, () -> {
            AgentTurn turn = requireTurn(acceptedTurn.id());
            if (!turn.state().terminal()) {
                Optional<String> reason = Optional.of(failureMessage(failure));
                persistTerminalTurn(turn, TurnState.FAILED, reason);
                drainPending(outboxStore, eventService, turn.taskId());
            }
        });
    }

    private void persistTerminalTurn(
            AgentTurn turn,
            TurnState terminalState,
            Optional<String> reason)
    {
        EventId eventId = EventId.random();
        unitOfWork.execute(() -> {
            TurnMetrics finalMetrics = metricsService.finishTurn(turn.id(), terminalState);
            Instant endedAt = Instant.now(clock);
            AgentTurn latest = requireTurn(turn.id());
            AgentTurn terminal = turnStore.update(latest.finish(terminalState, finalMetrics, reason, endedAt));
            WorktreeMutationClaim claim = WorktreeMutationClaim.turn(latest.id(), latest.createdAt());
            AgentTask task = requireTask(terminal.taskId());
            taskStore.update(task.releaseWorktreeMutation(claim, endedAt));
            outboxStore.insert(pendingLifecycle(
                    eventId,
                    terminal.taskId(),
                    Optional.of(terminal.id()),
                    lifecyclePhase(terminalState),
                    lifecycleMessage(terminalState, reason),
                    endedAt));
            return null;
        });
    }

    private void retryCompletedTurn(TaskId taskId)
    {
        turnStore.list(taskId).forEach(this::retryCompletedTurn);
    }

    private void retryCompletedTurn(AgentTurn turn)
    {
        runtimeRegistry.running(turn.id()).ifPresent(runningTurn -> {
            CompletableFuture<AgentTurnResult> completion = runningTurn.completion().toCompletableFuture();
            if (!completion.isDone()) {
                return;
            }
            if (completion.isCancelled()) {
                finishTurn(turn, runningTurn, null, new IllegalStateException("Provider completion stage was cancelled"));
            }
            else if (completion.isCompletedExceptionally()) {
                finishTurn(turn, runningTurn, null, completion.exceptionNow());
            }
            else {
                finishTurn(turn, runningTurn, completion.resultNow(), null);
            }
        });
    }

    private static Optional<String> terminalReason(AgentTurnResult result, TurnState terminalState)
    {
        return terminalState == TurnState.COMPLETED
                ? Optional.empty()
                : Optional.of(result.terminalReason().orElseThrow());
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

    private static AgentEventPayload.LifecyclePhase lifecyclePhase(TurnState state)
    {
        return state == TurnState.INTERRUPTED
                ? AgentEventPayload.LifecyclePhase.INTERRUPTED
                : AgentEventPayload.LifecyclePhase.TURN_COMPLETED;
    }

    private static String lifecycleMessage(TurnState state, Optional<String> reason)
    {
        return switch (state) {
            case COMPLETED -> "Turn completed";
            case FAILED -> "Turn failed: " + reason.orElseThrow();
            case CANCELLED -> "Turn cancelled: " + reason.orElseThrow();
            case INTERRUPTED -> "Turn interrupted: " + reason.orElseThrow();
            case ACCEPTED, RUNNING -> throw new IllegalArgumentException("Turn is not terminal: " + state);
        };
    }

    private record TurnAcceptance(
            AgentTurn turn,
            AgentTurnSettings settings,
            EventId messageEventId)
    {
        private TurnAcceptance
        {
            requireNonNull(turn, "turn is null");
            requireNonNull(settings, "settings is null");
            requireNonNull(messageEventId, "messageEventId is null");
        }
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
