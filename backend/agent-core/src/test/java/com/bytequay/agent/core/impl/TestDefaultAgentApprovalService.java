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
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventOutboxEntry;
import com.bytequay.agent.core.AgentEventOutboxPayload;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentSandboxPolicy;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.ResolveApprovalCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.UnsequencedAgentEvent;
import com.bytequay.agent.spi.AgentPermissionPolicy;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.bytequay.agent.spi.ProviderAgentEventSource.root;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.ALLOW_FOR_TASK;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.ALLOW_ONCE;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.DENY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDefaultAgentApprovalService
{
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    private ScheduledExecutorService scheduler;
    private InMemoryAgentStores stores;
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
        stores = new InMemoryAgentStores();
        eventService = new RecordingEventService(stores);
        service = new DefaultAgentApprovalService(
                stores,
                stores,
                stores,
                stores,
                eventService,
                scheduler,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new AgentRuntimeRegistry());
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
        assertThat(stores.find(request.id())).contains(AgentApproval.pending(request));
        ResolveApprovalCommand command = new ResolveApprovalCommand(
                request.id(),
                ApprovalDecision.Decision.ALLOW,
                Optional.empty(),
                false);

        ApprovalDecision decision = service.resolve(command);

        assertThat(future).isCompletedWithValue(decision);
        assertThat(stores.find(request.id()).orElseThrow().decision()).contains(decision);
        assertThat(stores.outboxEntries()).allSatisfy(entry -> assertThat(entry.sequence()).isPresent());
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
                Optional.empty(),
                false)))
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
                Optional.empty(),
                false)))
                .isInstanceOf(AgentConflictException.class);
    }

    @Test
    void failsProviderCallbackWhenTimeoutCannotReadDurableApproval()
    {
        ApprovalRequest request = request(TurnId.random());
        CompletableFuture<ApprovalDecision> provider = service.request(request, Duration.ofMillis(100))
                .toCompletableFuture();
        stores.failApprovalFind(new AgentStorageException("blocked approval lookup"));

        assertThatThrownBy(() -> provider.get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(AgentStorageException.class)
                .hasRootCauseMessage("blocked approval lookup");
        assertThat(stores.find(request.id()).orElseThrow().pending()).isTrue();
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
                Optional.empty(),
                false)))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("not offered");

        assertThat(service.resolve(new ResolveApprovalCommand(
                request.id(),
                ApprovalDecision.Decision.DENY,
                Optional.empty(),
                false)).decision())
                .isEqualTo(ApprovalDecision.Decision.DENY);
    }

    @Test
    void allowForTaskAnswersTheTasksLaterRequestsWithTheSameScope()
    {
        List<ProviderApprovalRequest.Decision> decisions = ImmutableList.of(ALLOW_ONCE, ALLOW_FOR_TASK, DENY);
        ApprovalRequest first = request(TurnId.random(), decisions, Optional.of("gh issue list"));
        service.request(first, Duration.ofMinutes(1));
        assertThat(eventService.events.getFirst().payload()).isInstanceOfSatisfying(
                AgentEventPayload.ApprovalRequested.class,
                approval -> assertThat(approval.availableDecisions()).containsExactly(
                        AgentEventPayload.ApprovalOption.ALLOW_ONCE,
                        AgentEventPayload.ApprovalOption.ALLOW_FOR_TASK,
                        AgentEventPayload.ApprovalOption.DENY));

        ApprovalDecision decision = service.resolve(new ResolveApprovalCommand(
                first.id(),
                ApprovalDecision.Decision.ALLOW,
                Optional.empty(),
                true));
        assertThat(decision.forTask()).isTrue();
        assertThat(eventService.events.getLast().payload()).isEqualTo(new AgentEventPayload.ApprovalResolved(
                first.id().toString(),
                AgentEventPayload.ApprovalOutcome.ALLOWED,
                true));

        // The same scope in the same task needs no card decision, and is recorded as allowed for the task.
        ApprovalRequest second = request(first.turnId(), decisions, Optional.of("gh issue list"));
        assertThat(service.request(second, Duration.ofMinutes(1)).toCompletableFuture())
                .isCompletedWithValueMatching(ApprovalDecision::forTask);
        assertThat(eventService.events.getLast().payload()).isEqualTo(new AgentEventPayload.ApprovalResolved(
                second.id().toString(),
                AgentEventPayload.ApprovalOutcome.ALLOWED,
                true));

        // Another scope, or another task, still waits on the user.
        ApprovalRequest other = request(first.turnId(), decisions, Optional.of("gh pr create"));
        assertThat(service.request(other, Duration.ofMinutes(1)).toCompletableFuture()).isNotDone();
        ApprovalRequest elsewhere = request(TurnId.random(), decisions, Optional.of("gh issue list"));
        assertThat(service.request(elsewhere, Duration.ofMinutes(1)).toCompletableFuture()).isNotDone();
        service.denyPending(first.turnId());
        service.denyPending(elsewhere.turnId());
    }

    @Test
    void rejectsAllowForTaskThatWasNotOffered()
    {
        ApprovalRequest request = request(TurnId.random());
        service.request(request, Duration.ofMinutes(1));

        assertThatThrownBy(() -> service.resolve(new ResolveApprovalCommand(
                request.id(),
                ApprovalDecision.Decision.ALLOW,
                Optional.empty(),
                true)))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("not offered");
        service.denyPending(request.turnId());
    }

    @Test
    void rejectsScopedPermissionThatWasNotOffered()
    {
        ApprovalRequest request = request(TurnId.random());
        service.request(request, Duration.ofMinutes(1));

        assertThatThrownBy(() -> service.resolve(new ResolveApprovalCommand(
                request.id(),
                ApprovalDecision.Decision.ALLOW,
                Optional.of("session"),
                false)))
                .isInstanceOf(InvalidAgentRequestException.class)
                .hasMessageContaining("Scoped approval");
        service.denyPending(request.turnId());
    }

    @Test
    void rejectsUserDecisionAfterTurnCancellation()
    {
        ApprovalRequest request = request(TurnId.random());
        CompletableFuture<ApprovalDecision> provider = service.request(request, Duration.ofMinutes(1))
                .toCompletableFuture();
        AgentTurn turn = stores.find(request.turnId()).orElseThrow();
        stores.update(turn.requestCancellation(NOW));

        assertThatThrownBy(() -> service.resolve(new ResolveApprovalCommand(
                request.id(),
                ApprovalDecision.Decision.ALLOW,
                Optional.empty(),
                false)))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("no longer accepts decisions");

        service.denyPending(request.turnId());
        assertThat(provider.join().decision()).isEqualTo(ApprovalDecision.Decision.DENY);
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
    void attemptsEveryPendingDenialWhenOneEventDrainFails()
    {
        TurnId turnId = TurnId.random();
        ApprovalRequest firstRequest = request(turnId);
        ApprovalRequest secondRequest = request(turnId);
        CompletableFuture<ApprovalDecision> first = service.request(firstRequest, Duration.ofMinutes(1))
                .toCompletableFuture();
        CompletableFuture<ApprovalDecision> second = service.request(secondRequest, Duration.ofMinutes(1))
                .toCompletableFuture();
        eventService.failNextResolutionDrain();

        assertThatThrownBy(() -> service.denyPending(turnId))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("blocked resolution event");

        assertThat(first.isCompletedExceptionally() ^ second.isCompletedExceptionally()).isTrue();
        assertThat(first).isDone();
        assertThat(second).isDone();
        CompletableFuture<ApprovalDecision> completed = first.isCompletedExceptionally() ? second : first;
        assertThat(completed.join().decision()).isEqualTo(ApprovalDecision.Decision.DENY);
        assertThat(stores.find(firstRequest.id()).orElseThrow().pending()).isFalse();
        assertThat(stores.find(secondRequest.id()).orElseThrow().pending()).isFalse();
        assertThat(stores.outboxEntries()).allSatisfy(entry -> assertThat(entry.sequence()).isPresent());
    }

    @Test
    void settlesEveryLiveCallbackWhenTheTaskChannelKeepsFailing()
    {
        TurnId turnId = TurnId.random();
        ApprovalRequest firstRequest = request(turnId);
        ApprovalRequest secondRequest = request(turnId);
        CompletableFuture<ApprovalDecision> first = service.request(firstRequest, Duration.ofMinutes(1))
                .toCompletableFuture();
        CompletableFuture<ApprovalDecision> second = service.request(secondRequest, Duration.ofMinutes(1))
                .toCompletableFuture();
        AgentStorageException channelFailure = new AgentStorageException("blocked task channel");
        eventService.failResolutionDrains(channelFailure);

        assertThatThrownBy(() -> service.denyPending(turnId)).isSameAs(channelFailure);

        assertThat(first).isCompletedExceptionally();
        assertThat(second).isCompletedExceptionally();
        assertThat(stores.find(firstRequest.id()).orElseThrow().pending()).isFalse();
        assertThat(stores.find(secondRequest.id()).orElseThrow().pending()).isFalse();
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
                        Optional.empty(),
                        false)));

        eventService.awaitResolutionAppend();
        assertThat(stores.find(request.id()).orElseThrow().decision())
                .get()
                .extracting(ApprovalDecision::decision)
                .isEqualTo(ApprovalDecision.Decision.ALLOW);
        assertThat(stores.outboxEntries().getLast().sequence()).isPresent();
        assertThat(provider).isNotDone();
        eventService.releaseResolutionAppend();
        assertThat(resolving.get(1, TimeUnit.SECONDS)).isEqualTo(provider.get(1, TimeUnit.SECONDS));
    }

    @Test
    void reloadDeniesDurablePendingApprovalWithoutAProviderCallback()
    {
        ApprovalRequest request = request(TurnId.random());
        CompletableFuture<ApprovalDecision> abandonedProvider = service.request(request, Duration.ofMinutes(1))
                .toCompletableFuture();
        RecordingEventService restartedEvents = new RecordingEventService(stores);
        DefaultAgentApprovalService restarted = new DefaultAgentApprovalService(
                stores,
                stores,
                stores,
                stores,
                restartedEvents,
                scheduler,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new AgentRuntimeRegistry());

        restarted.denyAllPending();

        AgentApproval resolved = stores.find(request.id()).orElseThrow();
        assertThat(resolved.pending()).isFalse();
        assertThat(resolved.decision().orElseThrow().decision()).isEqualTo(ApprovalDecision.Decision.DENY);
        assertThat(resolved.outcome()).contains(AgentEventPayload.ApprovalOutcome.CANCELLED);
        assertThat(restartedEvents.events).singleElement().satisfies(event ->
                assertThat(event.payload()).isInstanceOf(AgentEventPayload.ApprovalResolved.class));
        assertThat(abandonedProvider).isNotDone();
    }

    @Test
    void retriesResolutionEventAfterItsStateWasCommitted()
    {
        ApprovalRequest request = request(TurnId.random());
        CompletableFuture<ApprovalDecision> provider = service.request(request, Duration.ofMinutes(1))
                .toCompletableFuture();
        ResolveApprovalCommand command = new ResolveApprovalCommand(
                request.id(),
                ApprovalDecision.Decision.ALLOW,
                Optional.empty(),
                false);
        eventService.failNextResolutionDrain();

        assertThatThrownBy(() -> service.resolve(command))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("blocked resolution event");

        assertThat(provider).isCompletedExceptionally();
        assertThat(stores.find(request.id()).orElseThrow().decision())
                .get()
                .extracting(ApprovalDecision::decision)
                .isEqualTo(ApprovalDecision.Decision.ALLOW);
        assertThat(stores.outboxEntries().getLast().sequence()).isEmpty();

        RecordingEventService restartedEvents = new RecordingEventService(stores);
        DefaultAgentApprovalService restarted = new DefaultAgentApprovalService(
                stores,
                stores,
                stores,
                stores,
                restartedEvents,
                scheduler,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new AgentRuntimeRegistry());
        assertThat(restarted.resolve(command).decision()).isEqualTo(ApprovalDecision.Decision.ALLOW);
        assertThat(stores.outboxEntries().getLast().sequence()).isPresent();
        assertThat(restartedEvents.events).singleElement().satisfies(event ->
                assertThat(event.payload()).isInstanceOf(AgentEventPayload.ApprovalResolved.class));
    }

    @Test
    void resumesProviderWithoutReadingBackACommittedResolution()
    {
        ApprovalRequest request = request(TurnId.random());
        CompletableFuture<ApprovalDecision> provider = service.request(request, Duration.ofMinutes(1))
                .toCompletableFuture();
        stores.failApprovalFindAfterResolve(new AgentStorageException("blocked approval readback"));

        ApprovalDecision decision = service.resolve(new ResolveApprovalCommand(
                request.id(),
                ApprovalDecision.Decision.ALLOW,
                Optional.empty(),
                false));

        assertThat(provider).isCompletedWithValue(decision);
        stores.clearApprovalFindFailure();
        assertThat(stores.find(request.id()).orElseThrow().decision()).contains(decision);
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
        eventService.onApprovalRequest(() -> {
            assertThat(stores.find(request.id())).contains(AgentApproval.pending(request));
            service.resolve(new ResolveApprovalCommand(
                    request.id(),
                    ApprovalDecision.Decision.ALLOW,
                    Optional.empty(),
                    false));
        });

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

    private ApprovalRequest request(TurnId turnId)
    {
        return request(
                turnId,
                ImmutableList.of(
                        ALLOW_ONCE,
                        DENY));
    }

    private ApprovalRequest request(
            TurnId turnId,
            List<ProviderApprovalRequest.Decision> availableDecisions)
    {
        return request(turnId, availableDecisions, Optional.empty());
    }

    private ApprovalRequest request(
            TurnId turnId,
            List<ProviderApprovalRequest.Decision> availableDecisions,
            Optional<String> taskScope)
    {
        Optional<AgentTurn> existingTurn = stores.find(turnId);
        TaskId taskId = existingTurn.map(AgentTurn::taskId).orElseGet(TaskId::random);
        ApprovalRequest request = new ApprovalRequest(
                ApprovalId.random(),
                taskId,
                turnId,
                new ProviderApprovalRequest(
                        "provider-request-" + turnId,
                        ProviderApprovalRequest.Kind.COMMAND,
                        "Run command",
                        "git status",
                        ImmutableList.of("command:git status"),
                        availableDecisions,
                        taskScope,
                        new ProviderAgentEventSource(
                                Optional.of("provider-thread"),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of("provider-turn"),
                                Optional.of("provider-item")),
                        NOW),
                NOW);
        if (existingTurn.isEmpty()) {
            stores.insert(AgentTurn.accepted(
                    turnId,
                    taskId,
                    "approval-" + request.id(),
                    new AgentTurnSettings(
                            Optional.empty(),
                            Optional.empty(),
                            AgentPermissionPolicy.AUTO,
                            AgentSandboxPolicy.WORKSPACE_WRITE,
                            Duration.ofMinutes(1),
                            ImmutableList.of()),
                    NOW));
        }
        return request;
    }

    private static final class RecordingEventService
            implements AgentEventService
    {
        private final InMemoryAgentStores stores;
        private final List<UnsequencedAgentEvent> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch resolutionAppendEntered = new CountDownLatch(1);
        private final CountDownLatch releaseResolutionAppend = new CountDownLatch(1);
        private volatile boolean blockResolutionAppend;
        private volatile boolean failNextResolutionDrain;
        private volatile RuntimeException resolutionDrainFailure;
        private volatile Runnable approvalRequestCallback = () -> {};

        private RecordingEventService(InMemoryAgentStores stores)
        {
            this.stores = stores;
        }

        @Override
        public AgentEventEnvelope append(UnsequencedAgentEvent event)
        {
            return record(event, events.size() + 1, NOW);
        }

        @Override
        public AgentEventEnvelope drain(EventId eventId)
        {
            AgentEventOutboxEntry entry = stores.find(eventId).orElseThrow();
            AgentEventPayload payload = switch (entry.payload()) {
                case AgentEventOutboxPayload.Inline inline -> inline.payload();
                case AgentEventOutboxPayload.UserMessageBlob ignored -> throw new AssertionError("unexpected blob event");
            };
            if (failNextResolutionDrain && payload instanceof AgentEventPayload.ApprovalResolved) {
                failNextResolutionDrain = false;
                throw new AgentStorageException("blocked resolution event");
            }
            if (resolutionDrainFailure != null && payload instanceof AgentEventPayload.ApprovalResolved) {
                throw resolutionDrainFailure;
            }
            long sequence = entry.sequence().orElseGet(() -> stores.outboxEntries().stream()
                    .map(AgentEventOutboxEntry::sequence)
                    .flatMap(Optional::stream)
                    .mapToLong(Long::longValue)
                    .max()
                    .orElse(0) + 1);
            if (entry.sequence().isEmpty()) {
                stores.markAppended(eventId, sequence);
            }
            UnsequencedAgentEvent event = new UnsequencedAgentEvent(
                    entry.eventId(),
                    entry.taskId(),
                    entry.turnId(),
                    entry.source(),
                    payload);
            if (events.stream().noneMatch(existing -> existing.eventId().equals(eventId))) {
                return record(event, sequence, entry.occurredAt());
            }
            return new AgentEventEnvelope(
                    AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                    event.eventId(),
                    sequence,
                    event.taskId(),
                    event.turnId(),
                    event.source(),
                    entry.occurredAt(),
                    event.payload());
        }

        private AgentEventEnvelope record(UnsequencedAgentEvent event, long sequence, Instant occurredAt)
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
                    sequence,
                    event.taskId(),
                    event.turnId(),
                    event.source(),
                    occurredAt,
                    event.payload());
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

        private void failNextResolutionDrain()
        {
            failNextResolutionDrain = true;
        }

        private void failResolutionDrains(RuntimeException failure)
        {
            resolutionDrainFailure = failure;
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
