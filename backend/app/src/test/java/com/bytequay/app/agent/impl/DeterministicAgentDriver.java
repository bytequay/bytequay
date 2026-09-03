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
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderModel;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.RunningTurn;
import com.google.common.collect.ImmutableList;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class DeterministicAgentDriver
        implements AgentDriver
{
    private final AgentLaneId laneId;

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
        return AgentCapabilities.none();
    }

    @Override
    public AgentProviderStatus probe()
    {
        return new AgentProviderStatus(
                laneId,
                AgentProviderStatus.Readiness.READY,
                Optional.of("development"),
                "ready",
                capabilities());
    }

    @Override
    public List<ProviderModel> models()
    {
        return ImmutableList.of(
                new ProviderModel(
                        "test-model",
                        "Test model",
                        "",
                        ImmutableList.of("high"),
                        Optional.of("high"),
                        true,
                        ImmutableList.of("text", "image")),
                new ProviderModel(
                        "text-only-model",
                        "Text only",
                        "",
                        ImmutableList.of("high"),
                        Optional.of("high"),
                        false));
    }

    @Override
    public AgentSession open(AgentSessionContext context, AgentEventSink events)
    {
        AgentSessionRef reference = context.sessionReference().orElseGet(() -> new AgentSessionRef(
                laneId,
                1,
                "deterministic-" + UUID.randomUUID()));
        return new DeterministicSession(reference, events);
    }

    private record DeterministicSession(AgentSessionRef reference, AgentEventSink events)
            implements AgentSession
    {
        @Override
        public RunningTurn start(AgentTurnCommand command)
        {
            String response = "deterministic: " + command.message();
            events.emit(new ProviderAgentEvent.Message(
                    Optional.of("message-" + command.turnId()),
                    Optional.empty(),
                    ProviderAgentEvent.MessagePhase.FINAL,
                    response,
                    Optional.empty()));
            return new CompletedTurn(command.turnId(), response);
        }

        @Override
        public void close() {}
    }

    private record CompletedTurn(String turnId, String response)
            implements RunningTurn
    {
        @Override
        public ProviderTurnRef reference()
        {
            return new ProviderTurnRef("deterministic-" + turnId);
        }

        @Override
        public CompletionStage<AgentTurnResult> completion()
        {
            return CompletableFuture.completedFuture(new AgentTurnResult(
                    AgentTurnResult.State.COMPLETED,
                    Optional.of(response),
                    Optional.empty(),
                    Duration.ZERO));
        }

        @Override
        public void cancel() {}
    }
}
