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

import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentEventSink;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.CostProvenance;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.ProviderUsageSample;
import com.bytequay.agent.spi.RunningTurn;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static com.google.common.base.Preconditions.checkState;

final class DeterministicAgentDriver
        implements AgentDriver
{
    static final AgentLaneId LANE_ID = new AgentLaneId("deterministic");

    private static final AgentCapabilities CAPABILITIES = new AgentCapabilities(
            true,
            false,
            false,
            true,
            false,
            false,
            false,
            true,
            true,
            CostProvenance.UNAVAILABLE,
            ImmutableSet.of(
                    com.bytequay.agent.spi.AgentPermissionPolicy.AUTO,
                    com.bytequay.agent.spi.AgentPermissionPolicy.AUTO),
            ImmutableSet.of());

    private final List<AgentSessionRef> openedReferences = new ArrayList<>();
    private final List<AgentTurnCommand> commands = new ArrayList<>();
    private AgentProviderStatus.Readiness readiness = AgentProviderStatus.Readiness.READY;
    private RuntimeException probeFailure;
    private RuntimeException nextSessionReferenceAndCloseFailure;
    private RuntimeException nextTurnReferenceAndCancelFailure;
    private DeterministicSession currentSession;
    private boolean failNextStart;
    private boolean completeNextStart;
    private ProviderUsageSample usageOnOpen;
    private Runnable beforeStart = () -> {};
    private Runnable beforeCancel = () -> {};

    @Override
    public AgentLaneId id()
    {
        return LANE_ID;
    }

    @Override
    public AgentCapabilities capabilities()
    {
        return CAPABILITIES;
    }

    @Override
    public AgentProviderStatus probe()
    {
        if (probeFailure != null) {
            throw probeFailure;
        }
        return new AgentProviderStatus(
                LANE_ID,
                readiness,
                Optional.of("test"),
                readiness == AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE ? "deterministic lane unavailable" : "ready",
                CAPABILITIES);
    }

    @Override
    public synchronized AgentSession open(AgentSessionContext context, AgentEventSink events)
    {
        AgentSessionRef reference = context.sessionReference().orElseGet(() -> new AgentSessionRef(
                LANE_ID,
                1,
                "session-" + UUID.randomUUID()));
        openedReferences.add(reference);
        RuntimeException sessionFailure = nextSessionReferenceAndCloseFailure;
        nextSessionReferenceAndCloseFailure = null;
        currentSession = new DeterministicSession(context, events, reference, sessionFailure);
        if (usageOnOpen != null) {
            events.emit(new ProviderAgentEvent.Usage(usageOnOpen));
            usageOnOpen = null;
        }
        return currentSession;
    }

    synchronized void unavailable()
    {
        readiness = AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE;
    }

    synchronized void readiness(AgentProviderStatus.Readiness value)
    {
        readiness = value;
    }

    synchronized void failProbe(RuntimeException failure)
    {
        probeFailure = failure;
    }

    synchronized void failNextStart()
    {
        failNextStart = true;
    }

    synchronized void completeNextStart()
    {
        completeNextStart = true;
    }

    synchronized void failNextSessionReferenceAndClose(RuntimeException failure)
    {
        nextSessionReferenceAndCloseFailure = failure;
    }

    synchronized void failNextTurnReferenceAndCancel(RuntimeException failure)
    {
        nextTurnReferenceAndCancelFailure = failure;
    }

    synchronized void emitUsageOnOpen(ProviderUsageSample sample)
    {
        usageOnOpen = sample;
    }

    synchronized void beforeStart(Runnable action)
    {
        beforeStart = action;
    }

    synchronized void beforeCancel(Runnable action)
    {
        beforeCancel = action;
    }

    synchronized void emit(ProviderAgentEvent event)
    {
        requireCurrentTurn().emit(event);
    }

    synchronized void emitUsage(ProviderUsageSample sample)
    {
        emit(new ProviderAgentEvent.Usage(sample));
    }

    synchronized CompletionStage<ProviderApprovalDecision> requestApproval(
            ProviderApprovalRequest request,
            Duration timeout)
    {
        return requireCurrentTurn().requestApproval(request, timeout);
    }

    synchronized void complete(String response)
    {
        requireCurrentTurn().complete(response);
    }

    synchronized List<AgentSessionRef> openedReferences()
    {
        return ImmutableList.copyOf(openedReferences);
    }

    synchronized List<AgentTurnCommand> commands()
    {
        return ImmutableList.copyOf(commands);
    }

    private DeterministicRunningTurn requireCurrentTurn()
    {
        checkState(currentSession != null && currentSession.running != null, "no deterministic turn is running");
        return currentSession.running;
    }

    private final class DeterministicSession
            implements AgentSession
    {
        private final AgentSessionContext context;
        private final AgentEventSink events;
        private final AgentSessionRef reference;
        private final RuntimeException referenceAndCloseFailure;
        private DeterministicRunningTurn running;
        private boolean closed;

        private DeterministicSession(
                AgentSessionContext context,
                AgentEventSink events,
                AgentSessionRef reference,
                RuntimeException referenceAndCloseFailure)
        {
            this.context = context;
            this.events = events;
            this.reference = reference;
            this.referenceAndCloseFailure = referenceAndCloseFailure;
        }

        @Override
        public AgentSessionRef reference()
        {
            if (referenceAndCloseFailure != null) {
                throw referenceAndCloseFailure;
            }
            return reference;
        }

        @Override
        public RunningTurn start(AgentTurnCommand command)
        {
            synchronized (DeterministicAgentDriver.this) {
                checkState(!closed, "deterministic session is closed");
                if (failNextStart) {
                    failNextStart = false;
                    throw new IllegalStateException("deterministic start failed");
                }
                beforeStart.run();
                checkState(running == null || running.completion.isDone(), "deterministic turn is already running");
                commands.add(command);
                RuntimeException turnFailure = nextTurnReferenceAndCancelFailure;
                nextTurnReferenceAndCancelFailure = null;
                running = new DeterministicRunningTurn(context, events, command, beforeCancel, turnFailure);
                running.emit(new ProviderAgentEvent.Lifecycle(
                        Optional.empty(),
                        Optional.empty(),
                        ProviderAgentEvent.LifecyclePhase.TURN_STARTED,
                        "turn started"));
                if (completeNextStart) {
                    completeNextStart = false;
                    running.complete("immediate response");
                }
                return running;
            }
        }

        @Override
        public void close()
        {
            synchronized (DeterministicAgentDriver.this) {
                closed = true;
                if (referenceAndCloseFailure != null) {
                    throw referenceAndCloseFailure;
                }
            }
        }
    }

    private static final class DeterministicRunningTurn
            implements RunningTurn
    {
        private final AgentSessionContext context;
        private final AgentEventSink events;
        private final AgentTurnCommand command;
        private final Runnable beforeCancel;
        private final RuntimeException referenceAndCancelFailure;
        private final ProviderTurnRef reference;
        private final CompletableFuture<AgentTurnResult> completion = new CompletableFuture<>();

        private DeterministicRunningTurn(
                AgentSessionContext context,
                AgentEventSink events,
                AgentTurnCommand command,
                Runnable beforeCancel,
                RuntimeException referenceAndCancelFailure)
        {
            this.context = context;
            this.events = events;
            this.command = command;
            this.beforeCancel = beforeCancel;
            this.referenceAndCancelFailure = referenceAndCancelFailure;
            this.reference = new ProviderTurnRef("turn-" + command.turnId());
        }

        @Override
        public ProviderTurnRef reference()
        {
            if (referenceAndCancelFailure != null) {
                throw referenceAndCancelFailure;
            }
            return reference;
        }

        @Override
        public CompletionStage<AgentTurnResult> completion()
        {
            return completion;
        }

        @Override
        public void cancel()
        {
            if (referenceAndCancelFailure != null) {
                throw referenceAndCancelFailure;
            }
            if (completion.isDone()) {
                return;
            }
            beforeCancel.run();
            emit(new ProviderAgentEvent.Lifecycle(
                    Optional.empty(),
                    Optional.empty(),
                    ProviderAgentEvent.LifecyclePhase.INTERRUPTED,
                    "turn cancelled"));
            completion.complete(new AgentTurnResult(
                    AgentTurnResult.State.CANCELLED,
                    Optional.empty(),
                    Optional.of("cancelled"),
                    Duration.ZERO));
        }

        private void emit(ProviderAgentEvent event)
        {
            events.emit(event);
        }

        private CompletionStage<ProviderApprovalDecision> requestApproval(
                ProviderApprovalRequest request,
                Duration timeout)
        {
            return context.approvals().request(request, timeout);
        }

        private void complete(String response)
        {
            checkState(!completion.isDone(), "deterministic turn is already complete");
            emit(new ProviderAgentEvent.Message(
                    Optional.of("message-" + command.turnId()),
                    Optional.empty(),
                    ProviderAgentEvent.MessagePhase.FINAL,
                    response,
                    Optional.empty()));
            emit(new ProviderAgentEvent.Lifecycle(
                    Optional.empty(),
                    Optional.empty(),
                    ProviderAgentEvent.LifecyclePhase.TURN_COMPLETED,
                    "turn completed"));
            completion.complete(new AgentTurnResult(
                    AgentTurnResult.State.COMPLETED,
                    Optional.of(response),
                    Optional.empty(),
                    Duration.ZERO));
        }
    }
}
