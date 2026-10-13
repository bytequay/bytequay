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
package com.bytequay.app.agent.impl;

import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentEventSink;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnInput;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderModel;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.RunningTurn;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkState;

public final class DeterministicAgentDriver
        implements AgentDriver
{
    private final AgentLaneId laneId;
    private final AtomicBoolean holdNextTurn = new AtomicBoolean();
    private final AtomicBoolean imageAttachments = new AtomicBoolean(true);
    private final AtomicLong openCount = new AtomicLong();
    private final AtomicLong probeCount = new AtomicLong();
    private final ArrayDeque<AgentProviderStatus.Readiness> probeReadiness = new ArrayDeque<>();
    private final AtomicReference<HeldTurn> heldTurn = new AtomicReference<>();
    private final AtomicReference<DevelopmentResultReport> nextDevelopmentResult = new AtomicReference<>();

    public DeterministicAgentDriver()
    {
        this(new AgentLaneId("deterministic"));
    }

    public DeterministicAgentDriver(AgentLaneId laneId)
    {
        this.laneId = laneId;
    }

    @Override
    public AgentLaneId id()
    {
        return laneId;
    }

    @Override
    public AgentCapabilities capabilities()
    {
        return new AgentCapabilities(false, false, true, ImmutableSet.of(AgentPermissionPolicy.AUTO), imageAttachments.get() ? ImmutableSet.of("image/png") : ImmutableSet.of());
    }

    @Override
    public synchronized AgentProviderStatus probe()
    {
        probeCount.incrementAndGet();
        AgentProviderStatus.Readiness readiness = probeReadiness.pollFirst();
        if (readiness == null) {
            readiness = AgentProviderStatus.Readiness.READY;
        }
        return new AgentProviderStatus(
                laneId,
                readiness,
                Optional.of("development"),
                readiness == AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED ? "sign in required" : "ready",
                capabilities());
    }

    @Override
    public List<ProviderModel> models()
    {
        return ImmutableList.of(
                new ProviderModel("test-model", "Test model", "", ImmutableList.of("high"), Optional.of("high"), true, ImmutableList.of("text", "image"), ImmutableList.of()),
                new ProviderModel("text-only-model", "Text only", "", ImmutableList.of("high"), Optional.of("high"), false, ImmutableList.of("text"), ImmutableList.of()));
    }

    /** Keeps the next turn running until {@link #completeHeldTurn()} is called. */
    public void holdNextTurn()
    {
        checkState(holdNextTurn.compareAndSet(false, true), "a turn is already waiting to be held");
    }

    public void reportNextTurn(DevelopmentResultReport report)
    {
        checkState(nextDevelopmentResult.compareAndSet(null, report), "a development report is already pending");
    }

    public void completeHeldTurn()
    {
        HeldTurn held = heldTurn.getAndSet(null);
        checkState(held != null, "no turn is held");
        held.completion().complete(result(held.response()));
    }

    public synchronized void reset()
    {
        holdNextTurn.set(false);
        nextDevelopmentResult.set(null);
        imageAttachments.set(true);
        openCount.set(0);
        probeCount.set(0);
        probeReadiness.clear();
        HeldTurn held = heldTurn.getAndSet(null);
        if (held != null) {
            held.completion().complete(new AgentTurnResult(AgentTurnResult.State.INTERRUPTED, Optional.empty(), Optional.of("test reset"), Duration.ZERO, true));
        }
    }

    public void disableImageAttachments()
    {
        imageAttachments.set(false);
    }

    public long openCount()
    {
        return openCount.get();
    }

    public long probeCount()
    {
        return probeCount.get();
    }

    public synchronized void probeReadiness(AgentProviderStatus.Readiness... readiness)
    {
        for (AgentProviderStatus.Readiness value : readiness) {
            probeReadiness.addLast(value);
        }
    }

    @Override
    public AgentSession open(AgentSessionContext context, AgentEventSink events)
    {
        openCount.incrementAndGet();
        AgentSessionRef reference = context.sessionReference().orElseGet(() -> new AgentSessionRef(laneId, 1, "deterministic-" + UUID.randomUUID()));
        return new DeterministicSession(this, reference, events, context.textOnly());
    }

    private RunningTurn start(AgentTurnCommand command, AgentEventSink events, boolean textOnly)
    {
        String response = textOnly ? "{\"title\":\"Name first task\",\"branch\":\"name-first-task\"}" : "deterministic: " + command.message();
        events.emit(new ProviderAgentEvent.Message(Optional.of("message-" + command.turnId()), ProviderAgentEvent.MessagePhase.FINAL, response, ProviderAgentEventSource.root()));
        if (!textOnly) {
            Optional.ofNullable(nextDevelopmentResult.getAndSet(null)).ifPresent(report -> events.emit(new ProviderAgentEvent.DevelopmentResult(
                    Optional.of("report-" + command.turnId()),
                    report,
                    new ProviderAgentEventSource(Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.of("deterministic-" + command.turnId()), Optional.of("report-" + command.turnId())))));
        }
        CompletableFuture<AgentTurnResult> completion = new CompletableFuture<>();
        if (!textOnly && holdNextTurn.compareAndSet(true, false)) {
            checkState(heldTurn.compareAndSet(null, new HeldTurn(response, completion)), "a turn is already held");
        }
        else {
            completion.complete(result(response));
        }
        return new DeterministicTurn(command.turnId(), completion);
    }

    private static AgentTurnResult result(String response)
    {
        return new AgentTurnResult(AgentTurnResult.State.COMPLETED, Optional.of(response), Optional.empty(), Duration.ZERO, true);
    }

    private record DeterministicSession(
            DeterministicAgentDriver driver,
            AgentSessionRef reference,
            AgentEventSink events,
            boolean textOnly)
            implements AgentSession
    {
        @Override
        public RunningTurn start(AgentTurnCommand command)
        {
            return driver.start(command, events, textOnly);
        }

        @Override
        public void close() {}
    }

    private record DeterministicTurn(String turnId, CompletableFuture<AgentTurnResult> completion)
            implements RunningTurn
    {
        @Override
        public ProviderTurnRef reference()
        {
            return new ProviderTurnRef("deterministic-" + turnId);
        }

        @Override
        public void steer(AgentTurnInput input) {}

        @Override
        public void cancel() {}
    }

    private record HeldTurn(String response, CompletableFuture<AgentTurnResult> completion) {}
}
