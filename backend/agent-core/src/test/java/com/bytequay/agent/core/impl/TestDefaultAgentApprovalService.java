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

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.ResolveApprovalCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.UnsequencedAgentEvent;
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.bytequay.agent.spi.ProviderAgentEventSource.root;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.ALLOW_ONCE;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.DENY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDefaultAgentApprovalService
{
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    private ScheduledExecutorService scheduler;
    private RecordingEventService eventService;
    private DefaultAgentApprovalService service;

    @Test
    void providerApprovalMustOfferDeny()
    {
        assertThatThrownBy(() -> new ProviderApprovalRequest(
                "provider-request",
                ProviderApprovalRequest.Kind.COMMAND,
                "Run command",
                "git status",
                ImmutableList.of("command:git status"),
                ImmutableList.of(ALLOW_ONCE),
                root(),
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DENY");
    }

    @BeforeEach
    void setUp()
    {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        eventService = new RecordingEventService();
        service = new DefaultAgentApprovalService(
                eventService,
                scheduler,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown()
    {
        scheduler.shutdownNow();
    }

    @Test
    void emitsAndIdempotentlyResolvesExplicitDecision()
    {
        ApprovalRequest request = request(TurnId.random());
        CompletableFuture<ApprovalDecision> future = service.request(request, Duration.ofMinutes(1))
                .toCompletableFuture();
        ResolveApprovalCommand command = new ResolveApprovalCommand(
                request.id(),
                ApprovalDecision.Decision.ALLOW,
                Optional.empty());

        ApprovalDecision decision = service.resolve(command);

        assertThat(future).isCompletedWithValue(decision);
        assertThat(service.resolve(command)).isEqualTo(decision);
        assertThat(eventService.events).hasSize(2);
        assertThat(eventService.events.getFirst()).satisfies(event -> {
            assertThat(event.taskId()).isEqualTo(request.taskId());
            assertThat(event.source().providerItemId())
                    .isEqualTo(request.providerRequest().source().providerItemId());
            assertThat(event.payload()).isInstanceOfSatisfying(
                    AgentEventPayload.ApprovalRequested.class,
                    approval -> {
                        assertThat(approval.approvalId()).isEqualTo(request.id().toString());
                        assertThat(approval.approvalKind()).isEqualTo(AgentEventPayload.ApprovalKind.COMMAND);
                        assertThat(approval.actionable()).isTrue();
                        assertThat(approval.availableDecisions()).isNotEmpty();
                    });
        });
        assertThat(eventService.events.getLast().payload()).isInstanceOfSatisfying(
                AgentEventPayload.ApprovalResolved.class,
                resolution -> {
                    assertThat(resolution.approvalId()).isEqualTo(request.id().toString());
                    assertThat(resolution.outcome()).isEqualTo(AgentEventPayload.ApprovalOutcome.ALLOWED);
                });
        assertThatThrownBy(() -> service.resolve(new ResolveApprovalCommand(
                request.id(),
                ApprovalDecision.Decision.DENY,
                Optional.empty())))
                .isInstanceOf(AgentConflictException.class);
    }

    @Test
    void timesOutToDenial()
            throws Exception
    {
        ApprovalRequest request = request(TurnId.random());

        ApprovalDecision decision = service.request(request, Duration.ofMillis(10))
                .toCompletableFuture()
                .get(1, TimeUnit.SECONDS);

        assertThat(decision.decision()).isEqualTo(ApprovalDecision.Decision.DENY);
        assertThat(decision.timedOut()).isTrue();
        assertThat(eventService.events.getLast().payload()).isInstanceOfSatisfying(
                AgentEventPayload.ApprovalResolved.class,
                resolution -> assertThat(resolution.outcome())
                        .isEqualTo(AgentEventPayload.ApprovalOutcome.EXPIRED));
        assertThatThrownBy(() -> service.resolve(new ResolveApprovalCommand(
                request.id(),
                ApprovalDecision.Decision.ALLOW,
                Optional.empty())))
                .isInstanceOf(AgentConflictException.class);
    }

    @Test
    void exposesAndAcceptsOnlyProviderOfferedProductDecisions()
    {
        ApprovalRequest request = request(
                TurnId.random(),
                ImmutableList.of(DENY));
        service.request(request, Duration.ofMinutes(1));

        assertThat(eventService.events.getFirst().payload()).isInstanceOfSatisfying(
                AgentEventPayload.ApprovalRequested.class,
                approval -> assertThat(approval.availableDecisions())
                        .containsExactly(AgentEventPayload.ApprovalOption.DENY));
        assertThatThrownBy(() -> service.resolve(new ResolveApprovalCommand(
                request.id(),
                ApprovalDecision.Decision.ALLOW,
                Optional.empty())))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("not offered");

        assertThat(service.resolve(new ResolveApprovalCommand(
                request.id(),
                ApprovalDecision.Decision.DENY,
                Optional.empty())).decision())
                .isEqualTo(ApprovalDecision.Decision.DENY);
    }

    @Test
    void rejectsScopedPermissionThatWasNotOffered()
    {
        ApprovalRequest request = request(TurnId.random());
        service.request(request, Duration.ofMinutes(1));

        assertThatThrownBy(() -> service.resolve(new ResolveApprovalCommand(
                request.id(),
                ApprovalDecision.Decision.ALLOW,
                Optional.of("session"))))
                .isInstanceOf(InvalidAgentRequestException.class)
                .hasMessageContaining("Scoped approval");
        service.denyPending(request.turnId());
    }

    @Test
    void rejectsApprovalWithNoProductDecision()
    {
        assertThatThrownBy(() -> request(TurnId.random(), ImmutableList.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("availableDecisions is empty");
    }

    @Test
    void deniesOnlyPendingApprovalsForTerminalTurn()
    {
        TurnId terminalTurn = TurnId.random();
        ApprovalRequest terminalRequest = request(terminalTurn);
        ApprovalRequest liveRequest = request(TurnId.random());
        CompletableFuture<ApprovalDecision> terminal = service.request(terminalRequest, Duration.ofMinutes(1))
                .toCompletableFuture();
        CompletableFuture<ApprovalDecision> live = service.request(liveRequest, Duration.ofMinutes(1))
                .toCompletableFuture();

        service.denyPending(terminalTurn);

        assertThat(terminal.join().decision()).isEqualTo(ApprovalDecision.Decision.DENY);
        assertThat(live).isNotDone();
        assertThat(eventService.events.getLast().payload()).isInstanceOfSatisfying(
                AgentEventPayload.ApprovalResolved.class,
                resolution -> {
                    assertThat(resolution.approvalId()).isEqualTo(terminalRequest.id().toString());
                    assertThat(resolution.outcome()).isEqualTo(AgentEventPayload.ApprovalOutcome.CANCELLED);
                });
        service.denyPending(liveRequest.turnId());
    }

    @Test
    void persistsResolutionBeforeContinuingProvider()
            throws Exception
    {
        ApprovalRequest request = request(TurnId.random());
        CompletableFuture<ApprovalDecision> provider = service.request(request, Duration.ofMinutes(1))
                .toCompletableFuture();
        eventService.blockResolutionAppend();

        CompletableFuture<ApprovalDecision> resolving = CompletableFuture.supplyAsync(() -> service.resolve(
                new ResolveApprovalCommand(
                        request.id(),
                        ApprovalDecision.Decision.ALLOW,
                        Optional.empty())));

        eventService.awaitResolutionAppend();
        assertThat(provider).isNotDone();
        eventService.releaseResolutionAppend();
        assertThat(resolving.get(1, TimeUnit.SECONDS)).isEqualTo(provider.get(1, TimeUnit.SECONDS));
    }

    @Test
    void recordsCancellationWhenTimeoutSchedulingFails()
    {
        ApprovalRequest request = request(TurnId.random());
        scheduler.shutdownNow();

        assertThatThrownBy(() -> service.request(request, Duration.ofMinutes(1)))
                .isInstanceOf(RejectedExecutionException.class);

        assertThat(eventService.events)
                .extracting(event -> event.payload().kind())
                .containsExactly(
                        AgentEventPayload.Kind.APPROVAL_REQUESTED,
                        AgentEventPayload.Kind.APPROVAL_RESOLVED);
        assertThat(eventService.events.getLast().payload()).isInstanceOfSatisfying(
                AgentEventPayload.ApprovalResolved.class,
                resolution -> assertThat(resolution.outcome())
                        .isEqualTo(AgentEventPayload.ApprovalOutcome.CANCELLED));
    }

    @Test
    void doesNotCancelApprovalResolvedWhileTimeoutSchedulingFails()
    {
        ApprovalRequest request = request(TurnId.random());
        scheduler.shutdownNow();
        eventService.onApprovalRequest(() -> service.resolve(new ResolveApprovalCommand(
                request.id(),
                ApprovalDecision.Decision.ALLOW,
                Optional.empty())));

        ApprovalDecision decision = service.request(request, Duration.ofMinutes(1))
                .toCompletableFuture()
                .join();

        assertThat(decision.decision()).isEqualTo(ApprovalDecision.Decision.ALLOW);
        assertThat(eventService.events).hasSize(2);
        assertThat(eventService.events.getLast().payload()).isInstanceOfSatisfying(
                AgentEventPayload.ApprovalResolved.class,
                resolution -> assertThat(resolution.outcome())
                        .isEqualTo(AgentEventPayload.ApprovalOutcome.ALLOWED));
    }

    private static ApprovalRequest request(TurnId turnId)
    {
        return request(
                turnId,
                ImmutableList.of(
                        ALLOW_ONCE,
                        DENY));
    }

    private static ApprovalRequest request(
            TurnId turnId,
            List<ProviderApprovalRequest.Decision> availableDecisions)
    {
        return new ApprovalRequest(
                ApprovalId.random(),
                TaskId.random(),
                turnId,
                new ProviderApprovalRequest(
                        "provider-request-" + turnId,
                        ProviderApprovalRequest.Kind.COMMAND,
                        "Run command",
                        "git status",
                        ImmutableList.of("command:git status"),
                        availableDecisions,
                        new ProviderAgentEventSource(
                                Optional.of("provider-thread"),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of("provider-turn"),
                                Optional.of("provider-item")),
                        NOW),
                NOW);
    }

    private static final class RecordingEventService
            implements AgentEventService
    {
        private final List<UnsequencedAgentEvent> events = new ArrayList<>();
        private final CountDownLatch resolutionAppendEntered = new CountDownLatch(1);
        private final CountDownLatch releaseResolutionAppend = new CountDownLatch(1);
        private boolean blockResolutionAppend;
        private Runnable approvalRequestCallback = () -> {};

        @Override
        public AgentEventEnvelope append(UnsequencedAgentEvent event)
        {
            events.add(event);
            if (event.payload() instanceof AgentEventPayload.ApprovalRequested) {
                approvalRequestCallback.run();
            }
            if (blockResolutionAppend && event.payload() instanceof AgentEventPayload.ApprovalResolved) {
                resolutionAppendEntered.countDown();
                try {
                    if (!releaseResolutionAppend.await(1, TimeUnit.SECONDS)) {
                        throw new AssertionError("resolution append was not released");
                    }
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return new AgentEventEnvelope(
                    AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                    event.eventId(),
                    events.size(),
                    event.taskId(),
                    event.turnId(),
                    event.source(),
                    NOW,
                    event.payload());
        }

        @Override
        public AgentEventEnvelope drain(EventId eventId)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentEventEnvelope> readAfter(TaskId taskId, long afterSequence, int limit)
        {
            return ImmutableList.of();
        }

        @Override
        public Flow.Publisher<AgentEventEnvelope> subscribe(TaskId taskId, long afterSequence)
        {
            throw new UnsupportedOperationException();
        }

        private void blockResolutionAppend()
        {
            blockResolutionAppend = true;
        }

        private void awaitResolutionAppend()
                throws InterruptedException
        {
            assertThat(resolutionAppendEntered.await(1, TimeUnit.SECONDS)).isTrue();
        }

        private void releaseResolutionAppend()
        {
            releaseResolutionAppend.countDown();
        }

        private void onApprovalRequest(Runnable callback)
        {
            approvalRequestCallback = callback;
        }
    }
}
