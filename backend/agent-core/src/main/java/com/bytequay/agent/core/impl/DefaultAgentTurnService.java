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
import com.bytequay.agent.core.AgentInputDelivery;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentRuntimeStopImpact;
import com.bytequay.agent.core.AgentSandboxPolicy;
import com.bytequay.agent.core.AgentStorageException;
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
import com.bytequay.agent.core.CodeGraphException;
import com.bytequay.agent.core.CodeGraphInstructions;
import com.bytequay.agent.core.CodeGraphService;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.InputDeliveryId;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.ProviderAgentEventAdapter;
import com.bytequay.agent.core.QueueAgentInputCommand;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.QueuedInputNotFoundException;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.SteerAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.UnsequencedAgentEvent;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.core.store.SteerDeliveryStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentAttachment;
import com.bytequay.agent.spi.AgentDeliveryUncertainException;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentProviderUnavailableException;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnFailureKind;
import com.bytequay.agent.spi.AgentTurnInput;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.CodeGraphBridge;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.RunningTurn;
import com.google.common.collect.ImmutableList;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.bytequay.agent.core.AgentEventOutboxEntry.pendingInline;
import static com.bytequay.agent.core.AgentEventOutboxEntry.pendingLifecycle;
import static com.bytequay.agent.core.AgentEventSource.root;
import static com.bytequay.agent.core.impl.AgentEventOutboxDrainer.drainPending;
import static com.bytequay.agent.core.impl.AgentFailures.failureMessage;
import static com.bytequay.agent.core.impl.AgentFailures.message;
import static com.bytequay.agent.core.impl.AgentFailures.suppressFailure;
import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

public final class DefaultAgentTurnService
        implements AgentTurnService
{
    private final AgentTaskStore taskStore;
    private final AgentTurnStore turnStore;
    private final QueuedInputStore queueStore;
    private final SteerDeliveryStore steerStore;
    private final AgentUnitOfWork unitOfWork;
    private final AgentEventOutboxStore outboxStore;
    private final TaskLogStore logStore;
    private final AgentResourceService resourceService;
    private final AgentDriverRegistry driverRegistry;
    private final AgentEventService eventService;
    private final AgentMetricsService metricsService;
    private final AgentApprovalService approvalService;
    private final AgentRuntimeRegistry runtimeRegistry;
    private final CodeGraphService codeGraph;
    private final Clock clock;
    private final Duration maximumTurnRuntime;
    /** Runs the queue's next turn off the thread that finished the last one, which may be the provider's own. */
    private final Executor queueStarter;

    /** How long a closed process may take to report its turn interrupted before the stop is a failure. */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

    public DefaultAgentTurnService(
            AgentTaskStore taskStore,
            AgentTurnStore turnStore,
            QueuedInputStore queueStore,
            SteerDeliveryStore steerStore,
            AgentUnitOfWork unitOfWork,
            AgentEventOutboxStore outboxStore,
            TaskLogStore logStore,
            AgentResourceService resourceService,
            AgentDriverRegistry driverRegistry,
            AgentEventService eventService,
            AgentMetricsService metricsService,
            AgentApprovalService approvalService,
            AgentRuntimeRegistry runtimeRegistry,
            CodeGraphService codeGraph,
            Clock clock,
            Duration maximumTurnRuntime,
            Executor queueStarter)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.queueStore = requireNonNull(queueStore, "queueStore is null");
        this.steerStore = requireNonNull(steerStore, "steerStore is null");
        this.unitOfWork = requireNonNull(unitOfWork, "unitOfWork is null");
        this.outboxStore = requireNonNull(outboxStore, "outboxStore is null");
        this.logStore = requireNonNull(logStore, "logStore is null");
        this.resourceService = requireNonNull(resourceService, "resourceService is null");
        this.driverRegistry = requireNonNull(driverRegistry, "driverRegistry is null");
        this.eventService = requireNonNull(eventService, "eventService is null");
        this.metricsService = requireNonNull(metricsService, "metricsService is null");
        this.approvalService = requireNonNull(approvalService, "approvalService is null");
        this.runtimeRegistry = requireNonNull(runtimeRegistry, "runtimeRegistry is null");
        this.codeGraph = requireNonNull(codeGraph, "codeGraph is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.maximumTurnRuntime = requireNonNull(maximumTurnRuntime, "maximumTurnRuntime is null");
        checkArgument(!maximumTurnRuntime.isZero() && !maximumTurnRuntime.isNegative(), "maximumTurnRuntime must be positive");
        this.queueStarter = requireNonNull(queueStarter, "queueStarter is null");
    }

    @Override
    public AgentTurn start(StartAgentTurnCommand command)
    {
        requireNonNull(command, "command is null");
        retryCompletedTurn(command.taskId());
        Launch launch;
        synchronized (runtimeRegistry.taskLock(command.taskId())) {
            drainPending(outboxStore, eventService, command.taskId());
            Optional<AgentTurn> repeated = turnStore.findByIdempotencyKey(
                    command.taskId(),
                    command.idempotencyKey());
            if (repeated.isPresent()) {
                return repeated.orElseThrow();
            }

            AgentTask task = requireStartableTask(command);
            command.expectedTaskVersion().ifPresent(expectedVersion -> {
                if (task.version() != expectedVersion) {
                    throw new AgentConflictException("Task version changed: " + task.id());
                }
            });
            launch = launchLocked(task, acceptTurnLocked(task, command), command.message(), command.attachments());
        }
        return settleLaunch(command.taskId(), launch);
    }

    @Override
    public Optional<AgentTurn> findTurnByIdempotencyKey(TaskId taskId, String idempotencyKey)
    {
        requireNonNull(taskId, "taskId is null");
        checkNotBlank(idempotencyKey, "idempotencyKey");
        synchronized (runtimeRegistry.taskLock(taskId)) {
            requireTask(taskId);
            return turnStore.findByIdempotencyKey(taskId, idempotencyKey);
        }
    }

    /**
     * Sends an accepted turn to the provider.
     *
     * <p>A failure before the send is recorded as a known-unsent failed turn here; a failure after
     * it leaves the turn as it is, because the provider may be running it.
     */
    private Launch launchLocked(AgentTask task, TurnAcceptance acceptance, String message, List<AgentAttachment> attachments)
    {
        AgentTurn accepted = acceptance.turn();
        boolean deliveryAttempted = false;
        AgentSession session = null;
        RunningTurn failedRunningTurn = null;
        try {
            eventService.drain(acceptance.deliveryEventId());
            session = session(task, accepted.id(), acceptance.settings());
            metricsService.beginTurn(task.id(), accepted.id());
            markDeliveryStarted(accepted.id());
            deliveryAttempted = true;
            RunningTurn runningTurn = session.start(new AgentTurnCommand(
                    accepted.id().toString(),
                    message,
                    attachments,
                    acceptance.settings().maximumRuntime(),
                    acceptance.settings().model(),
                    acceptance.settings().effort()));
            failedRunningTurn = runningTurn;
            return new Launch(accepted, registerRunningTurn(accepted, runningTurn, session.reference()), null, null);
        }
        catch (RuntimeException failure) {
            if (!deliveryAttempted) {
                boolean providerQuiescent = runtimeRegistry.unverifiedClose(task.id()).isEmpty();
                if (session != null) {
                    try {
                        runtimeRegistry.closeSession(task.id());
                    }
                    catch (RuntimeException closeFailure) {
                        providerQuiescent = false;
                        if (failure != closeFailure) {
                            failure.addSuppressed(closeFailure);
                        }
                    }
                }
                recordKnownUnsentFailure(accepted, failure, providerQuiescent);
            }
            return new Launch(accepted, null, failedRunningTurn, failure);
        }
    }

    /** Finishes a launch outside the task lock: watches the run, or cleans up and rethrows. */
    private AgentTurn settleLaunch(TaskId taskId, Launch launch)
    {
        if (launch.failure() != null) {
            cleanupFailedStart(launch.turn(), launch.failedRunningTurn(), launch.failure());
            throw launch.failure();
        }
        return observeStartedTurn(taskId, launch.started());
    }

    @Override
    public AgentInputDelivery steer(SteerAgentTurnCommand command)
    {
        requireNonNull(command, "command is null");
        TaskId taskId = requireTurn(command.turnId()).taskId();
        retryCompletedTurn(taskId);
        // The provider has no steer idempotency key. Keep acceptance and the corresponding send in
        // one task-local order, while leaving the task state lock free during provider I/O.
        synchronized (runtimeRegistry.steerLock(taskId)) {
            SteerAcceptance acceptance;
            RunningTurn running;
            synchronized (runtimeRegistry.taskLock(taskId)) {
                drainPending(outboxStore, eventService, taskId);
                Optional<AgentInputDelivery> repeated = steerStore.findSteerByIdempotencyKey(taskId, command.idempotencyKey());
                if (repeated.isPresent()) {
                    AgentInputDelivery existing = repeated.orElseThrow();
                    if (!existing.turnId().equals(command.turnId())) {
                        throw new AgentConflictException("Steer idempotency key belongs to another turn: " + existing.turnId());
                    }
                    if (existing.state() == AgentInputDelivery.State.SENDING) {
                        return settleSteer(
                                existing,
                                AgentInputDelivery.State.UNKNOWN,
                                Optional.of("Previous steer attempt ended without a recorded provider response"));
                    }
                    if (existing.state() != AgentInputDelivery.State.ACCEPTED) {
                        return existing;
                    }
                }
                AgentTurn turn = requireTurn(command.turnId());
                // The turn the user was looking at, and only that one: a turn that has since ended
                // is stale, and its successor must not receive input meant for it.
                if (turn.state() != TurnState.RUNNING) {
                    throw new AgentConflictException("Turn is not running: " + turn.id());
                }
                AgentTask task = requireTask(taskId);
                if (!driverRegistry.require(task.laneId()).capabilities().steering()) {
                    throw new AgentUnavailableException("Provider " + task.laneId() + " cannot add input to a running turn");
                }
                running = runtimeRegistry.running(turn.id())
                        .orElseThrow(() -> new AgentConflictException("Turn has no live provider handle: " + turn.id()));
                if (repeated.isPresent()) {
                    AgentInputDelivery accepted = repeated.orElseThrow();
                    acceptance = new SteerAcceptance(accepted, EventId.steerMessage(accepted.id()), persistedSteerInput(accepted));
                }
                else {
                    acceptance = acceptSteerLocked(task, turn, command);
                }
                eventService.drain(acceptance.messageEventId());
                steerStore.update(acceptance.delivery().sending(Instant.now(clock)));
            }

            try {
                running.steer(acceptance.input());
            }
            catch (AgentDeliveryUncertainException uncertain) {
                return settleSteer(acceptance.delivery(), AgentInputDelivery.State.UNKNOWN, Optional.of(message(uncertain)));
            }
            catch (RuntimeException refused) {
                return settleSteer(acceptance.delivery(), AgentInputDelivery.State.FAILED, Optional.of(message(refused)));
            }
            return settleSteer(acceptance.delivery(), AgentInputDelivery.State.DELIVERED, Optional.empty());
        }
    }

    @Override
    public Optional<AgentInputDelivery> findSteerByIdempotencyKey(TurnId turnId, String idempotencyKey)
    {
        requireNonNull(turnId, "turnId is null");
        checkNotBlank(idempotencyKey, "idempotencyKey");
        AgentTurn requestedTurn = requireTurn(turnId);
        TaskId taskId = requestedTurn.taskId();
        synchronized (runtimeRegistry.taskLock(taskId)) {
            Optional<AgentInputDelivery> found = steerStore.findSteerByIdempotencyKey(taskId, idempotencyKey);
            found.filter(delivery -> !delivery.turnId().equals(turnId)).ifPresent(delivery -> {
                throw new AgentConflictException("Steer idempotency key belongs to another turn: " + delivery.turnId());
            });
            return found;
        }
    }

    private SteerAcceptance acceptSteerLocked(AgentTask task, AgentTurn turn, SteerAgentTurnCommand command)
    {
        Instant acceptedAt = Instant.now(clock);
        BlobRef messageBlob = logStore.writeBlob(
                task.id(),
                ByteBuffer.wrap(command.message().getBytes(UTF_8)),
                "text/plain; charset=utf-8");
        InputDeliveryId deliveryId = InputDeliveryId.random();
        EventId messageEventId = EventId.steerMessage(deliveryId);
        AgentInputDelivery delivery = AgentInputDelivery.accepted(
                deliveryId, task.id(), turn.id(), command.idempotencyKey(), acceptedAt);
        unitOfWork.execute(() -> {
            steerStore.insert(delivery);
            outboxStore.insert(new AgentEventOutboxEntry(
                    messageEventId,
                    task.id(),
                    Optional.of(turn.id()),
                    root(),
                    new AgentEventOutboxPayload.UserMessageBlob(
                            messageBlob,
                            references(command.attachments()),
                            AgentEventPayload.DeliveryKind.STEER),
                    acceptedAt,
                    Optional.empty()));
            return null;
        });
        return new SteerAcceptance(delivery, messageEventId, new AgentTurnInput(command.message(), command.attachments()));
    }

    private AgentTurnInput persistedSteerInput(AgentInputDelivery delivery)
    {
        AgentEventOutboxEntry entry = outboxStore.find(EventId.steerMessage(delivery.id()))
                .orElseThrow(() -> new AgentStorageException("Steer message event is absent: " + delivery.id()));
        if (!(entry.payload() instanceof AgentEventOutboxPayload.UserMessageBlob message)
                || message.deliveryKind() != AgentEventPayload.DeliveryKind.STEER) {
            throw new AgentStorageException("Steer message event has an invalid payload: " + delivery.id());
        }
        List<AgentAttachment> attachments = message.attachments().stream()
                .map(reference -> resourceService.readAttachment(delivery.taskId(), reference.id()).input())
                .collect(toImmutableList());
        return new AgentTurnInput(readUtf8(delivery.taskId(), message.textBlob()), attachments);
    }

    /**
     * Records how a steer ended. A failure is also an error event on the turn, so the message the
     * transcript already shows is visibly not one the agent received, or not surely.
     */
    private AgentInputDelivery settleSteer(
            AgentInputDelivery accepted,
            AgentInputDelivery.State outcome,
            Optional<String> failure)
    {
        checkArgument(outcome == AgentInputDelivery.State.DELIVERED
                || outcome == AgentInputDelivery.State.FAILED
                || outcome == AgentInputDelivery.State.UNKNOWN, "outcome is not terminal");
        synchronized (runtimeRegistry.taskLock(accepted.taskId())) {
            AgentInputDelivery current = steerStore.findSteerByIdempotencyKey(accepted.taskId(), accepted.idempotencyKey()).orElseThrow();
            Instant now = Instant.now(clock);
            AgentInputDelivery settled = switch (outcome) {
                case DELIVERED -> current.delivered(now);
                case FAILED -> current.failed(failure.orElseThrow(), now);
                case UNKNOWN -> current.unknown(failure.orElseThrow(), now);
                case ACCEPTED, SENDING -> throw new IllegalArgumentException("outcome is not terminal: " + outcome);
            };
            Optional<String> errorCode = switch (outcome) {
                case FAILED -> Optional.of("STEER_FAILED");
                case UNKNOWN -> Optional.of("STEER_UNKNOWN");
                case DELIVERED -> Optional.empty();
                case ACCEPTED, SENDING -> throw new IllegalArgumentException("outcome is not terminal: " + outcome);
            };
            EventId eventId = EventId.random();
            AgentInputDelivery stored = unitOfWork.execute(() -> {
                AgentInputDelivery updated = steerStore.update(settled);
                if (errorCode.isPresent()) {
                    outboxStore.insert(pendingInline(
                            eventId,
                            updated.taskId(),
                            Optional.of(updated.turnId()),
                            root(),
                            new AgentEventPayload.Error(errorCode.orElseThrow(), failure.orElseThrow(), false),
                            now));
                }
                return updated;
            });
            if (errorCode.isPresent()) {
                eventService.drain(eventId);
            }
            return stored;
        }
    }

    @Override
    public QueuedAgentInput enqueue(QueueAgentInputCommand command)
    {
        requireNonNull(command, "command is null");
        retryCompletedTurn(command.taskId());
        synchronized (runtimeRegistry.taskLock(command.taskId())) {
            drainPending(outboxStore, eventService, command.taskId());
            Optional<QueuedAgentInput> repeated = queueStore.findQueuedByIdempotencyKey(command.taskId(), command.idempotencyKey());
            if (repeated.isPresent()) {
                return displayInput(repeated.orElseThrow());
            }
            AgentTask task = requireTask(command.taskId());
            if (task.state() != TaskState.OPEN) {
                throw new AgentConflictException("Task cannot queue input in state " + task.state());
            }
            if (task.version() != command.expectedVersion()) {
                throw new AgentConflictException("Task version changed: " + task.id());
            }
            // A queue is later work behind the task's own active turn. Accepting one after that
            // turn released its claim would leave an idle QUEUED row with nothing to start it.
            Optional<WorktreeMutationClaim> claim = task.worktreeMutationClaim();
            Optional<AgentTurn> activeTurn = turnStore.findActive(task.id());
            boolean ownTurnRunning = claim
                    .filter(held -> held.kind() == WorktreeMutationClaim.Kind.TURN)
                    .filter(held -> activeTurn.stream().anyMatch(turn -> held.ownerId().equals(turn.id().toString())))
                    .isPresent();
            if (!ownTurnRunning) {
                throw new AgentConflictException(claim.isPresent()
                        ? "Task worktree is claimed by " + claim.orElseThrow().kind()
                        : "Task has no active turn to queue behind: " + task.id());
            }
            Instant queuedAt = Instant.now(clock);
            BlobRef textBlob = logStore.writeBlob(
                    task.id(),
                    ByteBuffer.wrap(command.message().getBytes(UTF_8)),
                    "text/plain; charset=utf-8");
            long position = queueStore.listQueued(task.id()).stream().mapToLong(QueuedAgentInput::position).max().orElse(0) + 1;
            QueuedAgentInput input = QueuedAgentInput.queued(
                    QueuedInputId.random(),
                    task.id(),
                    position,
                    command.idempotencyKey(),
                    textBlob,
                    command.message(),
                    turnSettings(task, references(command.attachments())),
                    queuedAt);
            EventId eventId = EventId.random();
            QueuedAgentInput queued = unitOfWork.execute(() -> {
                // The version write is what serializes this against Complete.
                taskStore.update(task.withUpdatedAt(queuedAt));
                queueStore.insert(input);
                outboxStore.insert(pendingLifecycle(
                        eventId,
                        task.id(),
                        Optional.empty(),
                        AgentEventPayload.LifecyclePhase.QUEUE_CHANGED,
                        "Queued input added",
                        queuedAt));
                return input;
            });
            eventService.drain(eventId);
            return queued;
        }
    }

    @Override
    public Optional<QueuedAgentInput> findQueuedByIdempotencyKey(TaskId taskId, String idempotencyKey)
    {
        requireNonNull(taskId, "taskId is null");
        checkNotBlank(idempotencyKey, "idempotencyKey");
        synchronized (runtimeRegistry.taskLock(taskId)) {
            requireTask(taskId);
            return queueStore.findQueuedByIdempotencyKey(taskId, idempotencyKey).map(this::displayInput);
        }
    }

    @Override
    public List<QueuedAgentInput> queued(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        requireTask(taskId);
        return queueStore.listQueued(taskId).stream()
                .filter(input -> !input.state().historical())
                .map(this::displayInput)
                .collect(toImmutableList());
    }

    @Override
    public QueuedAgentInput removeQueued(TaskId taskId, QueuedInputId queuedInputId)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(queuedInputId, "queuedInputId is null");
        synchronized (runtimeRegistry.taskLock(taskId)) {
            drainPending(outboxStore, eventService, taskId);
            requireTask(taskId);
            QueuedAgentInput input = requireQueued(queuedInputId);
            if (!input.taskId().equals(taskId)) {
                throw new AgentConflictException("Queued input does not belong to task " + taskId + ": " + queuedInputId);
            }
            Instant now = Instant.now(clock);
            QueuedAgentInput removed = switch (input.state()) {
                case QUEUED, PAUSED -> input.removed(now);
                // The user is declining to resend it, which says nothing about whether it arrived.
                case UNCERTAIN -> input.abandoned("Discarded without resending it", now);
                // A lost HTTP response must not turn the same removal into a conflict on retry.
                case REMOVED, ABANDONED -> input;
                case STARTING, STARTED -> throw new AgentConflictException("Queued input can no longer be removed: " + input.state());
            };
            if (removed == input) {
                return displayInput(input);
            }
            EventId eventId = EventId.random();
            QueuedAgentInput stored = unitOfWork.execute(() -> {
                QueuedAgentInput updated = queueStore.update(removed);
                outboxStore.insert(pendingLifecycle(
                        eventId,
                        taskId,
                        Optional.empty(),
                        AgentEventPayload.LifecyclePhase.QUEUE_CHANGED,
                        removed.state() == QueuedAgentInput.State.ABANDONED
                                ? "Queued input abandoned"
                                : "Queued input removed",
                        now));
                return updated;
            });
            eventService.drain(eventId);
            return displayInput(stored);
        }
    }

    @Override
    public AgentTurn resumeQueued(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        retryCompletedTurn(taskId);
        Launch launch;
        synchronized (runtimeRegistry.taskLock(taskId)) {
            drainPending(outboxStore, eventService, taskId);
            AgentTask task = requireStartableTask(taskId);
            QueuedAgentInput head = queueHead(taskId)
                    .orElseThrow(() -> new AgentConflictException("Task has no queued input: " + taskId));
            if (head.state() == QueuedAgentInput.State.UNCERTAIN) {
                throw new AgentConflictException("The next queued input may already have been delivered; discard it first: " + head.id());
            }
            launch = launchQueuedLocked(task, head);
        }
        return settleLaunch(taskId, launch);
    }

    /** The next input that is not history: waiting, paused, or uncertain. */
    private Optional<QueuedAgentInput> queueHead(TaskId taskId)
    {
        return queueStore.listQueued(taskId).stream()
                .filter(input -> !input.state().historical())
                .findFirst();
    }

    /**
     * Starts the queue head after a turn ended well.
     *
     * <p>Only a waiting input starts by itself; a paused one waits for the user, and an uncertain
     * one for a decision. Whatever stops the start is written on the input as its pause reason,
     * because this runs on no request that could carry the failure back to anyone.
     */
    private void startQueuedHead(TaskId taskId)
    {
        Launch launch;
        synchronized (runtimeRegistry.taskLock(taskId)) {
            Optional<QueuedAgentInput> head = queueHead(taskId).filter(input -> input.state() == QueuedAgentInput.State.QUEUED);
            if (head.isEmpty()) {
                return;
            }
            try {
                drainPending(outboxStore, eventService, taskId);
            }
            catch (RuntimeException unavailable) {
                pauseQueuedHead(head.orElseThrow(), "Not started: " + message(unavailable));
                return;
            }
            AgentTask task;
            try {
                task = requireStartableTask(taskId);
            }
            catch (AgentConflictException busy) {
                // A turn the user sent got there first; the queue waits for it like any other.
                return;
            }
            catch (RuntimeException unavailable) {
                pauseQueuedHead(head.orElseThrow(), "Not started: " + message(unavailable));
                return;
            }
            try {
                launch = launchQueuedLocked(task, head.orElseThrow());
            }
            catch (RuntimeException failure) {
                pauseQueuedHead(requireQueued(head.orElseThrow().id()), "Not started: " + message(failure));
                return;
            }
        }
        try {
            settleLaunch(taskId, launch);
        }
        catch (RuntimeException failure) {
            // Already on the turn and its input; there is no caller to tell.
        }
    }

    /** Persists a background queue mutation and only then publishes the refresh signal for it. */
    private void pauseQueuedHead(QueuedAgentInput input, String reason)
    {
        Instant pausedAt = Instant.now(clock);
        EventId eventId = EventId.random();
        unitOfWork.execute(() -> {
            queueStore.update(input.paused(reason, pausedAt));
            outboxStore.insert(pendingLifecycle(
                    eventId,
                    input.taskId(),
                    Optional.empty(),
                    AgentEventPayload.LifecyclePhase.QUEUED_INPUT_PAUSED,
                    reason,
                    pausedAt));
            return null;
        });
        eventService.drain(eventId);
    }

    private Launch launchQueuedLocked(AgentTask task, QueuedAgentInput head)
    {
        List<AgentAttachment> attachments = head.settings().attachments().stream()
                .map(reference -> resourceService.readAttachment(task.id(), reference.id()).input())
                .collect(toImmutableList());
        String text = readUtf8(task.id(), head.textBlob());
        return launchLocked(task, acceptQueuedLocked(task, head), text, attachments);
    }

    /**
     * Claims the queue head for a new turn: the input, the task's worktree, and the turn move
     * together. The one user-message event is emitted on the first claim; a later attempt after a
     * known pre-send failure reuses it, so the transcript shows one request, not two.
     */
    private TurnAcceptance acceptQueuedLocked(AgentTask task, QueuedAgentInput head)
    {
        TurnId turnId = TurnId.random();
        Instant acceptedAt = Instant.now(clock);
        AgentTurnSettings settings = head.settings();
        AgentTurn turn = AgentTurn.accepted(turnId, task.id(), "queue:" + head.id() + ":" + turnId, settings, acceptedAt);
        EventId messageEventId = head.messageEventId().orElseGet(EventId::random);
        EventId deliveryEventId = head.messageEventId().isPresent() ? EventId.random() : messageEventId;
        unitOfWork.execute(() -> {
            taskStore.update(task.claimWorktreeMutation(WorktreeMutationClaim.turn(turnId, acceptedAt), acceptedAt));
            turnStore.insert(turn);
            queueStore.update(head.starting(turnId, messageEventId, acceptedAt));
            if (head.messageEventId().isEmpty()) {
                outboxStore.insert(new AgentEventOutboxEntry(
                        messageEventId,
                        task.id(),
                        Optional.of(turnId),
                        root(),
                        new AgentEventOutboxPayload.UserMessageBlob(
                                head.textBlob(),
                                settings.attachments(),
                                AgentEventPayload.DeliveryKind.QUEUED),
                        acceptedAt,
                        Optional.empty()));
            }
            else {
                outboxStore.insert(pendingLifecycle(
                        deliveryEventId,
                        task.id(),
                        Optional.of(turnId),
                        AgentEventPayload.LifecyclePhase.RETRYING,
                        "Retrying queued input",
                        acceptedAt));
            }
            return null;
        });
        return new TurnAcceptance(turn, settings, deliveryEventId);
    }

    private String readUtf8(TaskId taskId, BlobRef blobRef)
    {
        try {
            return UTF_8.newDecoder().decode(logStore.readBlob(taskId, blobRef)).toString();
        }
        catch (CharacterCodingException e) {
            throw new InvalidAgentRequestException("Queued text is not valid UTF-8");
        }
    }

    private QueuedAgentInput displayInput(QueuedAgentInput input)
    {
        return input.withPreview(readUtf8(input.taskId(), input.textBlob()));
    }

    private static List<AgentAttachmentReference> references(List<AgentAttachment> attachments)
    {
        return attachments.stream()
                .map(attachment -> new AgentAttachmentReference(attachment.id(), attachment.name(), attachment.mediaType()))
                .collect(toImmutableList());
    }

    private QueuedAgentInput requireQueued(QueuedInputId queuedInputId)
    {
        return queueStore.find(queuedInputId).orElseThrow(() -> new QueuedInputNotFoundException(queuedInputId));
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

    private AgentTask requireStartableTask(StartAgentTurnCommand command)
    {
        AgentTask task = requireStartableTaskWithoutReadiness(command.taskId());
        if (command.useTrustedTaskCreationReadiness()) {
            boolean trustedInitialTurn = task.interactive()
                    && task.version() == 0
                    && command.expectedTaskVersion().isPresent()
                    && command.expectedTaskVersion().getAsLong() == 0
                    && turnStore.list(task.id()).isEmpty();
            if (!trustedInitialTurn) {
                throw new InvalidAgentRequestException(
                        "Task-creation readiness applies only to a fresh interactive task");
            }
            return task;
        }
        return requireReady(task);
    }

    private AgentTask requireStartableTask(TaskId taskId)
    {
        return requireReady(requireStartableTaskWithoutReadiness(taskId));
    }

    private AgentTask requireStartableTaskWithoutReadiness(TaskId taskId)
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
        runtimeRegistry.unverifiedClose(task.id()).ifPresent(failure -> {
            throw new AgentConflictException("Task provider process is not verified stopped: " + failureMessage(failure));
        });
        return task;
    }

    private AgentTask requireReady(AgentTask task)
    {
        AgentProviderStatus status = driverRegistry.require(task.laneId()).probe();
        if (!status.available()) {
            throw new AgentProviderUnavailableException(status);
        }
        return task;
    }

    @Override
    public AgentTurnSettings effectiveSettings(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        // Attachments belong to a message, not to a task, so a described turn carries none.
        return turnSettings(requireTask(taskId), ImmutableList.of());
    }

    private TurnAcceptance acceptTurnLocked(AgentTask task, StartAgentTurnCommand command)
    {
        TurnId turnId = TurnId.random();
        Instant acceptedAt = Instant.now(clock);
        AgentTurnSettings settings = turnSettings(task, references(command.attachments()));
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

    private StartedTurn registerRunningTurn(AgentTurn accepted, RunningTurn runningTurn, AgentSessionRef reference)
    {
        ProviderTurnRef providerTurnReference = runningTurn.reference();
        Instant acknowledgedAt = Instant.now(clock);
        Optional<QueuedAgentInput> queuedInput = queueStore.listQueued(accepted.taskId()).stream()
                .filter(input -> input.state() == QueuedAgentInput.State.STARTING && input.turnId().equals(Optional.of(accepted.id())))
                .findFirst();
        Optional<EventId> queueEventId = queuedInput.map(input -> EventId.random());
        AgentTurn running = unitOfWork.execute(() -> {
            AgentTurn updated = turnStore.update(requireTurn(accepted.id()).running(
                    providerTurnReference,
                    Optional.empty()));
            // The thread reference is persisted only now that a turn has run on it. A thread that
            // was opened but never turned has no rollout, so resuming it after a restart fails
            // forever; forgetting it means the next turn starts a fresh thread instead.
            AgentTask task = requireTask(updated.taskId());
            if (task.sessionReference().isEmpty()) {
                taskStore.update(task.withSessionReference(reference, acknowledgedAt));
            }
            // The provider acknowledged the turn, so a queued input that became it is consumed for
            // good, whatever that turn does next.
            queuedInput.ifPresent(input -> queueStore.update(input.started(acknowledgedAt)));
            queueEventId.ifPresent(eventId -> outboxStore.insert(pendingLifecycle(
                    eventId,
                    updated.taskId(),
                    Optional.of(updated.id()),
                    AgentEventPayload.LifecyclePhase.QUEUED_INPUT_STARTED,
                    "Queued input started",
                    acknowledgedAt)));
            return updated;
        });
        runtimeRegistry.registerRunning(running.id(), runningTurn);
        queueEventId.ifPresent(eventId -> {
            try {
                eventService.drain(eventId);
            }
            catch (RuntimeException ignored) {
                // The provider already acknowledged the turn. The outbox row is durable and a
                // later request/recovery drain can publish it; delivery failure must not cancel a
                // provider execution that is now writing to the task worktree.
            }
        });
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

    @Override
    public AgentRuntimeStopImpact stopImpact(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        retryCompletedTurn(taskId);
        synchronized (runtimeRegistry.taskLock(taskId)) {
            AgentTask task = requireTask(taskId);
            Optional<AgentTurn> active = turnStore.findActive(taskId);
            return new AgentRuntimeStopImpact(
                    taskId,
                    generation(task, active),
                    active.map(AgentTurn::id),
                    runtimeRegistry.session(taskId).isPresent());
        }
    }

    private static String generation(AgentTask task, Optional<AgentTurn> active)
    {
        // A running turn is the thing being stopped, so it names the generation; otherwise the
        // task version does, and starting a turn bumps it.
        return active.map(turn -> "turn:" + turn.id()).orElseGet(() -> "task:" + task.version());
    }

    @Override
    public AgentTask stopRuntime(TaskId taskId, String generation)
    {
        requireNonNull(taskId, "taskId is null");
        if (generation == null || generation.isBlank()) {
            throw new InvalidAgentRequestException("generation must not be blank");
        }
        retryCompletedTurn(taskId);
        Optional<AgentTurn> active;
        synchronized (runtimeRegistry.taskLock(taskId)) {
            AgentTask task = requireTask(taskId);
            if (task.state().terminal()) {
                throw new AgentConflictException("Task is already terminal: " + taskId);
            }
            active = turnStore.findActive(taskId);
            if (!generation.equals(generation(task, active))) {
                throw new AgentConflictException("Task runtime changed since the stop was confirmed: " + taskId);
            }
            active.ifPresent(turn -> {
                AgentTurn cancellation = turn.requestCancellation(Instant.now(clock));
                if (cancellation != turn) {
                    turnStore.update(cancellation);
                }
            });
        }
        // Denying approvals and closing the session both complete provider callbacks that take the
        // task lock, so neither may run under it.
        active.ifPresent(turn -> approvalService.denyPending(turn.id()));
        runtimeRegistry.closeSession(taskId);
        active.ifPresent(this::awaitInterruption);
        synchronized (runtimeRegistry.taskLock(taskId)) {
            releaseStaleTurnClaim(requireTask(taskId));
            return requireTask(taskId);
        }
    }

    /**
     * Waits for a closed process to report its turn interrupted, then records that.
     *
     * <p>The close returns once the process tree is verified stopped, but the turn learns of it
     * through the provider's own callbacks, so the stop has to wait for them before the task can
     * be read as settled.
     */
    private void awaitInterruption(AgentTurn turn)
    {
        Optional<RunningTurn> running = runtimeRegistry.running(turn.id());
        if (running.isEmpty()) {
            // Either the close already ran the turn's completion, which drops the handle, or the
            // turn failed before a provider run existed. In the second case the closed process
            // was all there was, so the verified stop is what settles it.
            synchronized (runtimeRegistry.taskLock(turn.taskId())) {
                AgentTurn current = requireTurn(turn.id());
                if (current.state().active()) {
                    persistTerminalTurn(
                            current,
                            TurnState.INTERRUPTED,
                            Optional.of("Provider process stopped before the turn was acknowledged"),
                            true);
                }
            }
            drainPending(outboxStore, eventService, turn.taskId());
            approvalService.denyPending(turn.id());
            return;
        }
        try {
            running.orElseThrow().completion().toCompletableFuture().get(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentUnavailableException("Interrupted while stopping turn " + turn.id(), e);
        }
        catch (ExecutionException | CancellationException ignored) {
            // An exceptional completion is still a completion; the terminal transaction records it.
        }
        catch (TimeoutException e) {
            throw new AgentUnavailableException("Turn " + turn.id() + " did not report itself stopped after its process was closed", e);
        }
        get(turn.id());
    }

    /**
     * Releases a claim left by a finished turn now that the task's process tree is verified gone.
     *
     * <p>A turn keeps its claim when its provider could not be shown quiet; the only thing that
     * could still have been writing was that process, so a verified stop is what makes the
     * worktree safe again.
     */
    private void releaseStaleTurnClaim(AgentTask task)
    {
        Optional<WorktreeMutationClaim> claim = task.worktreeMutationClaim()
                .filter(held -> held.kind() == WorktreeMutationClaim.Kind.TURN);
        if (claim.isEmpty() || turnStore.findActive(task.id()).isPresent()) {
            return;
        }
        Instant releasedAt = Instant.now(clock);
        unitOfWork.execute(() -> taskStore.update(requireTask(task.id()).releaseWorktreeMutation(claim.orElseThrow(), releasedAt)));
    }

    /**
     * The task-bound graph tool for this session, absent without a managed worktree or when the
     * helper is off. The callback turns every expected graph failure into tool text so the provider
     * adapter never sees it.
     */
    private Optional<CodeGraphBridge> codeGraphBridge(AgentTask task, TaskResources resources)
    {
        return task.taskWorktree()
                .filter(worktree -> codeGraph.available())
                .map(worktree -> new CodeGraphBridge(query -> exploreForAgent(worktree, resources, query), CodeGraphInstructions.MANAGED));
    }

    private String exploreForAgent(TaskWorktree worktree, TaskResources resources, String query)
    {
        try {
            return codeGraph.explore(worktree, resources, query);
        }
        catch (CodeGraphException e) {
            return "CodeGraph is unavailable: " + e.getMessage() + ". Continue with normal source and search tools.";
        }
        catch (IllegalArgumentException e) {
            return "CodeGraph rejected the query: " + e.getMessage();
        }
    }

    private AgentSession session(AgentTask task, TurnId turnId, AgentTurnSettings settings)
    {
        Optional<AgentSession> existing = runtimeRegistry.session(task.id());
        if (existing.isPresent()) {
            // A provider launch profile may depend on the accepted model. Closing before delivery
            // lets the stored provider thread resume under the new turn's immutable snapshot.
            runtimeRegistry.closeSession(task.id());
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
                        (request, timeout) -> requestApproval(task.id(), request, timeout),
                        settings.model(),
                        settings.effort(),
                        false,
                        codeGraphBridge(task, resources)),
                event -> routeEvent(task.id(), event)), "opened session is null");
        AgentSession registered = runtimeRegistry.registerSession(task.id(), opened);
        try {
            AgentSessionRef reference = requireNonNull(registered.reference(), "session reference is null");
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
            return registered;
        }
        catch (RuntimeException failure) {
            // Keep a close failure in the registry. It is the only durable-in-process proof that
            // the provider may still be writing, and lets an explicit stop retry the same session.
            suppressFailure(failure, () -> runtimeRegistry.closeSession(task.id()));
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
            // Subagent and backfill phases are display-only; they change no task or turn state.
            case RETRYING, RATE_LIMITED, AGENT_STARTED, AGENT_COMPLETED, AGENT_FAILED,
                    BACKFILL_STARTED, BACKFILL_COMPLETED -> false;
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
        // Completion may arrive on the provider's response reader. It must not wait for the steer
        // lock: the request holding that lock can itself be waiting for this callback to return.
        synchronized (runtimeRegistry.taskLock(observedTurn.taskId())) {
            AgentTurn turn = requireTurn(observedTurn.id());
            if (!turn.state().terminal()) {
                if (completionFailure == null) {
                    AgentTurnResult result = requireNonNull(providerResult, "providerResult is null");
                    TurnState terminalState = terminalState(result.state());
                    persistTerminalTurn(
                            turn,
                            terminalState,
                            terminalReason(result, terminalState),
                            result.providerQuiescent(),
                            result.failureKind());
                }
                else {
                    persistTerminalTurn(
                            turn,
                            TurnState.INTERRUPTED,
                            Optional.of(failureMessage(completionFailure)),
                            false);
                }
            }
        }
        finishTerminalCallbacks(observedTurn, runningTurn);
        if (queueMayStart(observedTurn)) {
            queueStarter.execute(() -> startQueuedHead(observedTurn.taskId()));
        }
    }

    /** Only a turn that ended well and gave the worktree back may start what waits behind it. */
    private boolean queueMayStart(AgentTurn observedTurn)
    {
        synchronized (runtimeRegistry.taskLock(observedTurn.taskId())) {
            return requireTurn(observedTurn.id()).state() == TurnState.COMPLETED
                    && requireTask(observedTurn.taskId()).worktreeMutationClaim().isEmpty();
        }
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

    private void recordKnownUnsentFailure(
            AgentTurn acceptedTurn,
            RuntimeException failure,
            boolean providerQuiescent)
    {
        suppressFailure(failure, () -> {
            AgentTurn turn = requireTurn(acceptedTurn.id());
            if (!turn.state().terminal()) {
                Optional<String> reason = Optional.of(failureMessage(failure));
                persistTerminalTurn(turn, TurnState.FAILED, reason, providerQuiescent, true);
                drainPending(outboxStore, eventService, turn.taskId());
            }
        });
    }

    /**
     * Ends a turn, releasing its worktree claim only when the provider was shown quiet.
     *
     * @param providerQuiescent whether the provider proved nothing it started is still running. A
     *     turn that cannot show this keeps the claim, so no later turn edits a worktree something
     *     may still be writing to. A later confirmed Stop may retry the retained process proof and
     *     release that claim only after the provider is verified stopped.
     */
    private void persistTerminalTurn(
            AgentTurn turn,
            TurnState terminalState,
            Optional<String> reason,
            boolean providerQuiescent)
    {
        persistTerminalTurn(turn, terminalState, reason, providerQuiescent, Optional.empty(), false);
    }

    private void persistTerminalTurn(
            AgentTurn turn,
            TurnState terminalState,
            Optional<String> reason,
            boolean providerQuiescent,
            Optional<AgentTurnFailureKind> failureKind)
    {
        persistTerminalTurn(turn, terminalState, reason, providerQuiescent, failureKind, false);
    }

    private void persistTerminalTurn(
            AgentTurn turn,
            TurnState terminalState,
            Optional<String> reason,
            boolean providerQuiescent,
            boolean knownUnsent)
    {
        persistTerminalTurn(turn, terminalState, reason, providerQuiescent, Optional.empty(), knownUnsent);
    }

    private void persistTerminalTurn(
            AgentTurn turn,
            TurnState terminalState,
            Optional<String> reason,
            boolean providerQuiescent,
            Optional<AgentTurnFailureKind> failureKind,
            boolean knownUnsent)
    {
        EventId eventId = EventId.random();
        unitOfWork.execute(() -> {
            TurnMetrics finalMetrics = metricsService.finishTurn(turn.id(), terminalState);
            Instant endedAt = Instant.now(clock);
            AgentTurn latest = requireTurn(turn.id());
            AgentTurn finished = knownUnsent
                    ? latest.finishKnownUnsent(finalMetrics, reason.orElseThrow(), endedAt)
                    : latest.finish(terminalState, finalMetrics, reason, failureKind, endedAt);
            AgentTurn terminal = turnStore.update(finished);
            WorktreeMutationClaim claim = WorktreeMutationClaim.turn(latest.id(), latest.createdAt());
            AgentTask task = requireTask(terminal.taskId());
            AgentTask updated = providerQuiescent ? task.releaseWorktreeMutation(claim, endedAt) : task;
            if (updated != task) {
                taskStore.update(updated);
            }
            settleQueue(
                    terminal,
                    terminalState,
                    providerQuiescent,
                    lifecycleMessage(terminalState, reason),
                    endedAt);
            settleSteersForTerminalTurn(terminal.id(), endedAt);
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

    /** A turn cannot finish with steer input that is known not to have reached the provider. */
    private void settleSteersForTerminalTurn(TurnId turnId, Instant endedAt)
    {
        for (AgentInputDelivery delivery : steerStore.listUnsettled()) {
            if (!delivery.turnId().equals(turnId) || delivery.state() != AgentInputDelivery.State.ACCEPTED) {
                continue;
            }
            AgentInputDelivery settled = delivery.failed("Turn ended before the input was sent", endedAt);
            steerStore.update(settled);
            outboxStore.insert(pendingInline(
                    EventId.random(),
                    settled.taskId(),
                    Optional.of(settled.turnId()),
                    root(),
                    new AgentEventPayload.Error(
                            "STEER_FAILED",
                            settled.failure().orElseThrow(),
                            false),
                    endedAt));
        }
    }

    /**
     * What a turn's end means for the queue behind it.
     *
     * <p>The input this turn was claimed from is settled by what the turn proves: never sent, it
     * waits again as paused; sent but never acknowledged, nobody knows, so it is uncertain and
     * only the user may discard it. Anything less than a clean success pauses the rest.
     */
    private void settleQueue(
            AgentTurn terminal,
            TurnState terminalState,
            boolean providerQuiescent,
            String outcome,
            Instant endedAt)
    {
        boolean success = terminalState == TurnState.COMPLETED && providerQuiescent;
        for (QueuedAgentInput input : queueStore.listQueued(terminal.taskId())) {
            if (input.state() == QueuedAgentInput.State.STARTING && input.turnId().equals(Optional.of(terminal.id()))) {
                if (terminal.knownUnsent() || terminal.startedAt().isEmpty()) {
                    queueStore.update(input.paused(outcome, endedAt));
                }
                else if (terminal.providerTurnReference().isEmpty()) {
                    queueStore.update(input.uncertain(outcome, endedAt));
                }
                else {
                    queueStore.update(input.started(endedAt));
                }
            }
            else if (input.state() == QueuedAgentInput.State.QUEUED && !success) {
                queueStore.update(input.paused(
                        terminalState == TurnState.COMPLETED ? "Turn completed but its work could not be shown stopped" : outcome,
                        endedAt));
            }
        }
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

    /**
     * The settings a turn on this task records.
     *
     * <p>One derivation, shared by accepting a turn and by describing the next one, so what the
     * user is shown before sending cannot disagree with what is snapshotted when they do.
     */
    private AgentTurnSettings turnSettings(AgentTask task, List<AgentAttachmentReference> attachments)
    {
        return new AgentTurnSettings(
                task.defaults().model(),
                task.defaults().effort(),
                task.permissionPolicy(),
                AgentSandboxPolicy.WORKSPACE_WRITE,
                maximumTurnRuntime,
                attachments);
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
            EventId deliveryEventId)
    {
        private TurnAcceptance
        {
            requireNonNull(turn, "turn is null");
            requireNonNull(settings, "settings is null");
            requireNonNull(deliveryEventId, "deliveryEventId is null");
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

    /** What launching an accepted turn produced: a watched run, or the failure and what it left behind. */
    private record Launch(AgentTurn turn, StartedTurn started, RunningTurn failedRunningTurn, RuntimeException failure)
    {
        private Launch
        {
            requireNonNull(turn, "turn is null");
            checkArgument((started == null) != (failure == null), "a launch either started or failed");
        }
    }

    private record SteerAcceptance(AgentInputDelivery delivery, EventId messageEventId, AgentTurnInput input)
    {
        private SteerAcceptance
        {
            requireNonNull(delivery, "delivery is null");
            requireNonNull(messageEventId, "messageEventId is null");
            requireNonNull(input, "input is null");
        }
    }
}
