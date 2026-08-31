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
import com.bytequay.agent.core.AgentConflictException;
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
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.UnsequencedAgentEvent;
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
    private final AgentEventService eventService;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final ConcurrentMap<ApprovalId, PendingApproval> pending = new ConcurrentHashMap<>();
    private final ConcurrentMap<ApprovalId, ApprovalDecision> resolved = new ConcurrentHashMap<>();

    public DefaultAgentApprovalService(
            AgentEventService eventService,
            ScheduledExecutorService scheduler,
            Clock clock)
    {
        this.eventService = requireNonNull(eventService, "eventService is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public CompletionStage<ApprovalDecision> request(ApprovalRequest request, Duration timeout)
    {
        requireNonNull(request, "request is null");
        requireNonNull(timeout, "timeout is null");
        checkArgument(!timeout.isZero() && !timeout.isNegative(), "timeout must be positive");
        if (resolved.containsKey(request.id())) {
            throw new AgentConflictException("Approval identifier was already resolved: " + request.id());
        }

        PendingApproval approval = new PendingApproval(request);
        if (pending.putIfAbsent(request.id(), approval) != null) {
            throw new AgentConflictException("Approval identifier is already pending: " + request.id());
        }
        boolean requestEventAppended = false;
        try {
            ProviderApprovalRequest providerRequest = request.providerRequest();
            eventService.append(new UnsequencedAgentEvent(
                    EventId.random(),
                    request.taskId(),
                    Optional.of(request.turnId()),
                    source(providerRequest, Optional.of(providerRequest.requestedAt())),
                    new AgentEventPayload.ApprovalRequested(
                            request.id().toString(),
                            approvalKind(providerRequest.kind()),
                            providerRequest.title(),
                            providerRequest.detail(),
                            true,
                            availableDecisions(providerRequest))));
            requestEventAppended = true;
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
                cancelled = pending.remove(request.id(), approval);
                approval.cancelTimeout();
            }
            if (cancelled && requestEventAppended) {
                try {
                    appendResolution(request, AgentEventPayload.ApprovalOutcome.CANCELLED);
                }
                catch (RuntimeException resolutionFailure) {
                    e.addSuppressed(resolutionFailure);
                }
            }
            approval.future().completeExceptionally(e);
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
        PendingApproval approval = pending.get(command.approvalId());
        if (approval == null) {
            return requireSameResolvedDecision(command);
        }
        ProviderApprovalRequest.Decision requestedDecision = switch (command.decision()) {
            case ALLOW -> ALLOW_ONCE;
            case DENY -> DENY;
        };
        if (!approval.request().providerRequest().availableDecisions().contains(requestedDecision)) {
            throw new AgentConflictException("Approval decision was not offered: " + requestedDecision);
        }

        ApprovalDecision decision = new ApprovalDecision(
                command.approvalId(),
                command.decision(),
                command.scopedPermission(),
                Instant.now(clock),
                false);
        if (completePending(approval, decision, outcome(decision))) {
            return decision;
        }
        return requireSameResolvedDecision(command);
    }

    @Override
    public void denyPending(TurnId turnId)
    {
        requireNonNull(turnId, "turnId is null");
        pending.forEach((approvalId, approval) -> {
            if (approval.request().turnId().equals(turnId)) {
                completePending(
                        approval,
                        denial(approvalId, false),
                        AgentEventPayload.ApprovalOutcome.CANCELLED);
            }
        });
    }

    private void timeout(ApprovalId approvalId, PendingApproval approval)
    {
        completePending(
                approval,
                denial(approvalId, true),
                AgentEventPayload.ApprovalOutcome.EXPIRED);
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

    private boolean completePending(
            PendingApproval approval,
            ApprovalDecision decision,
            AgentEventPayload.ApprovalOutcome outcome)
    {
        RuntimeException appendFailure = null;
        synchronized (approval) {
            if (pending.get(decision.approvalId()) != approval) {
                return false;
            }
            try {
                ApprovalRequest request = approval.request();
                appendResolution(request, outcome);
            }
            catch (RuntimeException failure) {
                pending.remove(decision.approvalId(), approval);
                approval.cancelTimeout();
                appendFailure = failure;
            }
            if (appendFailure == null) {
                resolved.put(decision.approvalId(), decision);
                pending.remove(decision.approvalId(), approval);
                approval.cancelTimeout();
            }
        }
        // Provider continuations may take driver locks, so never run them while
        // holding the approval monitor used by turn cleanup.
        if (appendFailure != null) {
            approval.future().completeExceptionally(appendFailure);
            throw appendFailure;
        }
        approval.future().complete(decision);
        return true;
    }

    private ApprovalDecision requireSameResolvedDecision(ResolveApprovalCommand command)
    {
        ApprovalDecision previous = resolved.get(command.approvalId());
        if (previous == null) {
            throw new ApprovalNotFoundException(command.approvalId());
        }
        if (sameDecision(previous, command)) {
            return previous;
        }
        throw new AgentConflictException("Approval was already resolved differently: " + command.approvalId());
    }

    private static AgentEventPayload.ApprovalOutcome outcome(ApprovalDecision decision)
    {
        return switch (decision.decision()) {
            case ALLOW -> AgentEventPayload.ApprovalOutcome.ALLOWED;
            case DENY -> AgentEventPayload.ApprovalOutcome.DENIED;
        };
    }

    private void appendResolution(ApprovalRequest request, AgentEventPayload.ApprovalOutcome outcome)
    {
        eventService.append(new UnsequencedAgentEvent(
                EventId.random(),
                request.taskId(),
                Optional.of(request.turnId()),
                source(request.providerRequest(), Optional.empty()),
                new AgentEventPayload.ApprovalResolved(
                        request.id().toString(),
                        outcome)));
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

    private static final class PendingApproval
    {
        private final ApprovalRequest request;
        private final CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();
        private volatile ScheduledFuture<?> timeoutFuture;

        private PendingApproval(ApprovalRequest request)
        {
            this.request = request;
        }

        private ApprovalRequest request()
        {
            return request;
        }

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
