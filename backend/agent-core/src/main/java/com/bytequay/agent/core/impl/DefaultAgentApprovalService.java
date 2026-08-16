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
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ApprovalNotFoundException;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.ResolveApprovalCommand;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.UnsequencedAgentEvent;
import com.bytequay.agent.spi.ProviderAgentEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
        requireNonNull(eventService, "eventService is null");
        requireNonNull(scheduler, "scheduler is null");
        requireNonNull(clock, "clock is null");
        this.eventService = eventService;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @Override
    public CompletionStage<ApprovalDecision> request(ApprovalRequest request, Duration timeout)
    {
        requireNonNull(request, "request is null");
        requireNonNull(timeout, "timeout is null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (resolved.containsKey(request.id())) {
            throw new AgentConflictException("Approval identifier was already resolved: " + request.id());
        }

        PendingApproval approval = new PendingApproval(request);
        if (pending.putIfAbsent(request.id(), approval) != null) {
            throw new AgentConflictException("Approval identifier is already pending: " + request.id());
        }
        try {
            eventService.append(new UnsequencedAgentEvent(
                    request.taskId(),
                    Optional.of(request.turnId()),
                    new ProviderAgentEvent.Approval(
                            Optional.of(request.providerRequest().providerRequestId()),
                            Optional.of(request.providerRequest().requestedAt()),
                            request.providerRequest().providerRequestId(),
                            request.providerRequest().title(),
                            request.providerRequest().detail())));
            ScheduledFuture<?> timeoutFuture = scheduler.schedule(
                    () -> timeout(request.id(), approval),
                    Math.max(1, timeout.toMillis()),
                    TimeUnit.MILLISECONDS);
            approval.setTimeoutFuture(timeoutFuture);
            return approval.future();
        }
        catch (RuntimeException e) {
            pending.remove(request.id(), approval);
            approval.future().completeExceptionally(e);
            throw e;
        }
    }

    @Override
    public ApprovalDecision resolve(ResolveApprovalCommand command)
    {
        requireNonNull(command, "command is null");
        PendingApproval approval = pending.get(command.approvalId());
        if (approval == null || !pending.remove(command.approvalId(), approval)) {
            ApprovalDecision previous = resolved.get(command.approvalId());
            if (previous == null) {
                throw new ApprovalNotFoundException(command.approvalId());
            }
            if (sameDecision(previous, command)) {
                return previous;
            }
            throw new AgentConflictException("Approval was already resolved differently: " + command.approvalId());
        }

        ApprovalDecision decision = new ApprovalDecision(
                command.approvalId(),
                command.decision(),
                command.scopedPermission(),
                Instant.now(clock),
                false);
        complete(approval, decision);
        return decision;
    }

    @Override
    public void denyPending(TurnId turnId)
    {
        requireNonNull(turnId, "turnId is null");
        pending.forEach((approvalId, approval) -> {
            if (approval.request().turnId().equals(turnId) && pending.remove(approvalId, approval)) {
                complete(approval, denial(approvalId, false));
            }
        });
    }

    private void timeout(ApprovalId approvalId, PendingApproval approval)
    {
        if (pending.remove(approvalId, approval)) {
            complete(approval, denial(approvalId, true));
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

    private void complete(PendingApproval approval, ApprovalDecision decision)
    {
        resolved.put(decision.approvalId(), decision);
        approval.cancelTimeout();
        approval.future().complete(decision);
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
