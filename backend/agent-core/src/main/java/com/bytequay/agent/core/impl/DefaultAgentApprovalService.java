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

import com.bytequay.agent.core.AgentApproval;
import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventOutboxEntry;
import com.bytequay.agent.core.AgentEventOutboxPayload;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ApprovalNotFoundException;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.ResolveApprovalCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderApprovalRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.ALLOW_ONCE;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.DENY;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

public final class DefaultAgentApprovalService
        implements AgentApprovalService
{
    private static final int OUTBOX_BATCH_SIZE = 100;

    private final AgentApprovalStore approvalStore;
    private final AgentTurnStore turnStore;
    private final AgentUnitOfWork unitOfWork;
    private final AgentEventOutboxStore outboxStore;
    private final AgentEventService eventService;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final AgentRuntimeRegistry runtimeRegistry;
    private final ConcurrentMap<ApprovalId, PendingApproval> pending = new ConcurrentHashMap<>();

    public DefaultAgentApprovalService(
            AgentApprovalStore approvalStore,
            AgentTurnStore turnStore,
            AgentUnitOfWork unitOfWork,
            AgentEventOutboxStore outboxStore,
            AgentEventService eventService,
            ScheduledExecutorService scheduler,
            Clock clock,
            AgentRuntimeRegistry runtimeRegistry)
    {
        this.approvalStore = requireNonNull(approvalStore, "approvalStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.unitOfWork = requireNonNull(unitOfWork, "unitOfWork is null");
        this.outboxStore = requireNonNull(outboxStore, "outboxStore is null");
        this.eventService = requireNonNull(eventService, "eventService is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.runtimeRegistry = requireNonNull(runtimeRegistry, "runtimeRegistry is null");
    }

    @Override
    public CompletionStage<ApprovalDecision> request(ApprovalRequest request, Duration timeout)
    {
        requireNonNull(request, "request is null");
        requireNonNull(timeout, "timeout is null");
        checkArgument(!timeout.isZero() && !timeout.isNegative(), "timeout must be positive");

        PendingApproval approval = new PendingApproval();
        if (pending.putIfAbsent(request.id(), approval) != null) {
            throw new AgentConflictException("Approval identifier is already pending: " + request.id());
        }
        AgentApproval stored = AgentApproval.pending(request);
        EventId requestEventId = EventId.random();
        boolean persisted = false;
        boolean requestEventDrained = false;
        try {
            unitOfWork.execute(() -> {
                approvalStore.insert(stored);
                outboxStore.insert(requestEvent(stored, requestEventId));
                return null;
            });
            persisted = true;
            drainPendingEvents(request.taskId());
            requestEventDrained = true;
            ScheduledFuture<?> timeoutFuture = scheduler.schedule(
                    () -> timeout(request.id(), approval),
                    Math.max(1, timeout.toMillis()),
                    TimeUnit.MILLISECONDS);
            approval.setTimeoutFuture(timeoutFuture);
            return approval.future();
        }
        catch (RuntimeException e) {
            boolean cancelled;
            synchronized (approval) {
                if (pending.get(request.id()) != approval) {
                    return approval.future();
                }
                if (persisted) {
                    try {
                        resolveDurably(
                                stored,
                                denial(request.id(), false),
                                AgentEventPayload.ApprovalOutcome.CANCELLED,
                                requestEventDrained);
                    }
                    catch (RuntimeException resolutionFailure) {
                        e.addSuppressed(resolutionFailure);
                    }
                }
                cancelled = pending.remove(request.id(), approval);
                approval.cancelTimeout();
            }
            if (cancelled) {
                approval.future().completeExceptionally(e);
            }
            throw e;
        }
    }

    @Override
    public ApprovalDecision resolve(ResolveApprovalCommand command)
    {
        requireNonNull(command, "command is null");
        if (command.scopedPermission().isPresent()) {
            throw new InvalidAgentRequestException("Scoped approval is not offered");
        }
        AgentApproval discovered = requireApproval(command.approvalId());
        PreparedResolution resolution;
        synchronized (runtimeRegistry.taskLock(discovered.request().taskId())) {
            AgentApproval approval = requireApproval(command.approvalId());
            checkOfferedDecision(approval, command);
            if (approval.pending()) {
                requireTurnAcceptsDecision(approval);
            }
            ApprovalDecision decision = new ApprovalDecision(
                    command.approvalId(),
                    command.decision(),
                    command.scopedPermission(),
                    Instant.now(clock),
                    false);
            resolution = prepareResolution(approval, decision, outcome(decision));
        }
        ApprovalDecision recorded = publishResolution(resolution);
        if (sameDecision(recorded, command)) {
            return recorded;
        }
        throw new AgentConflictException("Approval was already resolved differently: " + command.approvalId());
    }

    @Override
    public void denyPending(TurnId turnId)
    {
        requireNonNull(turnId, "turnId is null");
        deny(approvalStore.listPending(turnId));
    }

    @Override
    public void denyAllPending()
    {
        deny(approvalStore.listPending());
    }

    private void deny(List<AgentApproval> approvals)
    {
        RuntimeException firstFailure = null;
        for (AgentApproval approval : approvals) {
            try {
                completePending(
                        approval,
                        denial(approval.request().id(), false),
                        AgentEventPayload.ApprovalOutcome.CANCELLED);
            }
            catch (RuntimeException failure) {
                failLive(approval.request().id(), failure);
                if (firstFailure == null) {
                    firstFailure = failure;
                }
                else if (firstFailure != failure) {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private void timeout(ApprovalId approvalId, PendingApproval approval)
    {
        if (pending.get(approvalId) != approval) {
            return;
        }
        try {
            AgentApproval stored = requireApproval(approvalId);
            completePending(
                    stored,
                    denial(approvalId, true),
                    AgentEventPayload.ApprovalOutcome.EXPIRED);
        }
        catch (RuntimeException failure) {
            failLive(approvalId, failure);
        }
    }

    private ApprovalDecision denial(ApprovalId approvalId, boolean timedOut)
    {
        return new ApprovalDecision(
                approvalId,
                ApprovalDecision.Decision.DENY,
                Optional.empty(),
                Instant.now(clock),
                timedOut);
    }

    private ApprovalDecision completePending(
            AgentApproval approval,
            ApprovalDecision decision,
            AgentEventPayload.ApprovalOutcome outcome)
    {
        return publishResolution(prepareResolution(approval, decision, outcome));
    }

    private PreparedResolution prepareResolution(
            AgentApproval approval,
            ApprovalDecision decision,
            AgentEventPayload.ApprovalOutcome outcome)
    {
        PendingApproval live = pending.get(decision.approvalId());
        if (live == null) {
            return recordResolution(approval, decision, outcome, Optional.empty());
        }

        synchronized (live) {
            if (pending.get(decision.approvalId()) != live) {
                return recordResolution(approval, decision, outcome, Optional.empty());
            }
            PreparedResolution resolution = recordResolution(approval, decision, outcome, Optional.of(live));
            pending.remove(decision.approvalId(), live);
            live.cancelTimeout();
            return resolution;
        }
    }

    private PreparedResolution recordResolution(
            AgentApproval approval,
            ApprovalDecision decision,
            AgentEventPayload.ApprovalOutcome outcome,
            Optional<PendingApproval> live)
    {
        EventId eventId = EventId.random();
        boolean changed = unitOfWork.execute(() -> {
            boolean resolved = approvalStore.resolve(approval.request().id(), decision, outcome);
            if (resolved) {
                outboxStore.insert(resolutionEvent(approval.request(), decision, outcome, eventId));
            }
            return resolved;
        });
        return new PreparedResolution(
                approval.request().taskId(),
                changed ? decision : requireResolvedDecision(approval.request().id()),
                live);
    }

    private ApprovalDecision publishResolution(PreparedResolution resolution)
    {
        try {
            drainPendingEvents(resolution.taskId());
        }
        catch (RuntimeException failure) {
            resolution.live().ifPresent(live -> live.future().completeExceptionally(failure));
            throw failure;
        }

        // Provider continuations may take driver locks, so never run them while
        // holding either the task or approval monitor used by turn cleanup.
        resolution.live().ifPresent(live -> live.future().complete(resolution.decision()));
        return resolution.decision();
    }

    private ApprovalDecision resolveDurably(
            AgentApproval approval,
            ApprovalDecision decision,
            AgentEventPayload.ApprovalOutcome outcome,
            boolean drain)
    {
        PreparedResolution resolution = recordResolution(approval, decision, outcome, Optional.empty());
        if (!drain) {
            return resolution.decision();
        }
        return publishResolution(resolution);
    }

    private void drainPendingEvents(TaskId taskId)
    {
        while (true) {
            List<AgentEventOutboxEntry> events = outboxStore.listPending(taskId, OUTBOX_BATCH_SIZE);
            if (events.isEmpty()) {
                return;
            }
            events.forEach(event -> eventService.drain(event.eventId()));
        }
    }

    private void requireTurnAcceptsDecision(AgentApproval approval)
    {
        boolean acceptsDecision = turnStore.find(approval.request().turnId())
                .filter(turn -> turn.state().active())
                .filter(turn -> turn.cancellationRequestedAt().isEmpty())
                .isPresent();
        if (!acceptsDecision) {
            throw new AgentConflictException(
                    "Approval turn no longer accepts decisions: " + approval.request().turnId());
        }
    }

    private void failLive(ApprovalId approvalId, RuntimeException failure)
    {
        PendingApproval live = pending.get(approvalId);
        if (live == null) {
            return;
        }
        boolean removed;
        synchronized (live) {
            removed = pending.remove(approvalId, live);
            if (removed) {
                live.cancelTimeout();
            }
        }
        if (removed) {
            live.future().completeExceptionally(failure);
        }
    }

    private AgentApproval requireApproval(ApprovalId approvalId)
    {
        return approvalStore.find(approvalId).orElseThrow(() -> new ApprovalNotFoundException(approvalId));
    }

    private ApprovalDecision requireResolvedDecision(ApprovalId approvalId)
    {
        return requireApproval(approvalId).decision().orElseThrow(() -> new AgentConflictException("Approval is still pending: " + approvalId));
    }

    private static void checkOfferedDecision(AgentApproval approval, ResolveApprovalCommand command)
    {
        ProviderApprovalRequest.Decision requestedDecision = switch (command.decision()) {
            case ALLOW -> ALLOW_ONCE;
            case DENY -> DENY;
        };
        if (!approval.request().providerRequest().availableDecisions().contains(requestedDecision)) {
            throw new AgentConflictException("Approval decision was not offered: " + requestedDecision);
        }
    }

    private static AgentEventPayload.ApprovalOutcome outcome(ApprovalDecision decision)
    {
        return switch (decision.decision()) {
            case ALLOW -> AgentEventPayload.ApprovalOutcome.ALLOWED;
            case DENY -> AgentEventPayload.ApprovalOutcome.DENIED;
        };
    }

    private static AgentEventOutboxEntry requestEvent(AgentApproval approval, EventId eventId)
    {
        ApprovalRequest request = approval.request();
        ProviderApprovalRequest providerRequest = request.providerRequest();
        return new AgentEventOutboxEntry(
                eventId,
                request.taskId(),
                Optional.of(request.turnId()),
                source(providerRequest, Optional.of(providerRequest.requestedAt())),
                new AgentEventOutboxPayload.Inline(new AgentEventPayload.ApprovalRequested(
                        request.id().toString(),
                        approvalKind(providerRequest.kind()),
                        providerRequest.title(),
                        providerRequest.detail(),
                        true,
                        availableDecisions(providerRequest))),
                request.requestedAt(),
                Optional.empty());
    }

    private static AgentEventOutboxEntry resolutionEvent(
            ApprovalRequest request,
            ApprovalDecision decision,
            AgentEventPayload.ApprovalOutcome outcome,
            EventId eventId)
    {
        return new AgentEventOutboxEntry(
                eventId,
                request.taskId(),
                Optional.of(request.turnId()),
                source(request.providerRequest(), Optional.empty()),
                new AgentEventOutboxPayload.Inline(new AgentEventPayload.ApprovalResolved(
                        request.id().toString(),
                        outcome)),
                decision.resolvedAt(),
                Optional.empty());
    }

    private static List<AgentEventPayload.ApprovalOption> availableDecisions(ProviderApprovalRequest request)
    {
        return request.availableDecisions().stream()
                .map(decision -> switch (decision) {
                    case ALLOW_ONCE -> AgentEventPayload.ApprovalOption.ALLOW_ONCE;
                    case DENY -> AgentEventPayload.ApprovalOption.DENY;
                })
                .collect(toImmutableList());
    }

    private static AgentEventSource source(
            ProviderApprovalRequest request,
            Optional<Instant> providerOccurredAt)
    {
        ProviderAgentEventSource source = request.source();
        return new AgentEventSource(
                source.providerThreadId(),
                source.parentProviderThreadId(),
                source.logicalAgentPath(),
                source.providerTurnId(),
                source.providerItemId(),
                Optional.empty(),
                providerOccurredAt);
    }

    private static AgentEventPayload.ApprovalKind approvalKind(ProviderApprovalRequest.Kind kind)
    {
        return switch (kind) {
            case COMMAND -> AgentEventPayload.ApprovalKind.COMMAND;
            case FILE_CHANGE -> AgentEventPayload.ApprovalKind.FILE_CHANGE;
            case TOOL, OTHER -> AgentEventPayload.ApprovalKind.OTHER;
        };
    }

    private static boolean sameDecision(ApprovalDecision previous, ResolveApprovalCommand command)
    {
        return previous.decision() == command.decision()
                && previous.scopedPermission().equals(command.scopedPermission());
    }

    private record PreparedResolution(
            TaskId taskId,
            ApprovalDecision decision,
            Optional<PendingApproval> live)
    {
        private PreparedResolution
        {
            requireNonNull(taskId, "taskId is null");
            requireNonNull(decision, "decision is null");
            requireNonNull(live, "live is null");
        }
    }

    private static final class PendingApproval
    {
        private final CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();
        private volatile ScheduledFuture<?> timeoutFuture;

        private CompletableFuture<ApprovalDecision> future()
        {
            return future;
        }

        private void setTimeoutFuture(ScheduledFuture<?> timeoutFuture)
        {
            this.timeoutFuture = timeoutFuture;
            if (future.isDone()) {
                timeoutFuture.cancel(false);
            }
        }

        private void cancelTimeout()
        {
            ScheduledFuture<?> future = timeoutFuture;
            if (future != null) {
                future.cancel(false);
            }
        }
    }
}
