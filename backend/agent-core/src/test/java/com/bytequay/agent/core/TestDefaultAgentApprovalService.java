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
package com.bytequay.agent.core;

import com.bytequay.agent.spi.ProviderAgentEvent;
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
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDefaultAgentApprovalService
{
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    private ScheduledExecutorService scheduler;
    private RecordingEventService eventService;
    private DefaultAgentApprovalService service;

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
                Optional.of("command:git status"));

        ApprovalDecision decision = service.resolve(command);

        assertThat(future).isCompletedWithValue(decision);
        assertThat(service.resolve(command)).isEqualTo(decision);
        assertThat(eventService.events).singleElement().satisfies(event -> {
            assertThat(event.taskId()).isEqualTo(request.taskId());
            assertThat(event.event()).isInstanceOf(ProviderAgentEvent.Approval.class);
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
        assertThatThrownBy(() -> service.resolve(new ResolveApprovalCommand(
                request.id(),
                ApprovalDecision.Decision.ALLOW,
                Optional.empty())))
                .isInstanceOf(AgentConflictException.class);
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
        service.denyPending(liveRequest.turnId());
    }

    private static ApprovalRequest request(TurnId turnId)
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
                        NOW),
                NOW);
    }

    private static final class RecordingEventService
            implements AgentEventService
    {
        private final List<UnsequencedAgentEvent> events = new ArrayList<>();

        @Override
        public AgentEventEnvelope append(UnsequencedAgentEvent event)
        {
            events.add(event);
            return new AgentEventEnvelope(
                    AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                    events.size(),
                    event.taskId(),
                    event.turnId(),
                    NOW,
                    event.event());
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
    }
}
