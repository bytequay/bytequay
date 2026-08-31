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

import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAgentTurnLifecycle
{
    private static final Instant ACCEPTED_AT = Instant.parse("2026-08-16T00:00:00Z");
    private static final AgentTurnSettings SETTINGS = new AgentTurnSettings(
            Optional.of("model-1"),
            Optional.empty(),
            AgentPermissionPolicy.AUTO,
            AgentSandboxPolicy.WORKSPACE_WRITE,
            Duration.ofMinutes(5),
            ImmutableList.of());

    @Test
    void turnRunsAndCompletesOnce()
    {
        AgentTurn accepted = newTurn();
        AgentTurn running = accepted.running(
                new ProviderTurnRef("provider-turn-1"),
                Optional.of("model-1"),
                ACCEPTED_AT.plusSeconds(1));
        AgentTurn completed = running.finish(
                TurnState.COMPLETED,
                TurnMetrics.empty(),
                Optional.empty(),
                ACCEPTED_AT.plusSeconds(2));

        assertThat(completed.state()).isEqualTo(TurnState.COMPLETED);
        assertThat(completed.startedAt()).contains(ACCEPTED_AT.plusSeconds(1));
        assertThat(completed.endedAt()).contains(ACCEPTED_AT.plusSeconds(2));
        assertThat(completed.model()).contains("model-1");
        assertThat(completed.settings()).contains(SETTINGS);
        assertThat(completed.terminalReason()).isEmpty();
        assertThat(completed.finish(
                TurnState.COMPLETED,
                TurnMetrics.empty(),
                Optional.empty(),
                ACCEPTED_AT.plusSeconds(3)))
                .isSameAs(completed);
    }

    @Test
    void rejectsAProviderModelThatDiffersFromTheAcceptedSnapshot()
    {
        assertThatThrownBy(() -> newTurn().running(
                new ProviderTurnRef("provider-turn-1"),
                Optional.of("another-model"),
                ACCEPTED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void effortRequiresAnExplicitModel()
    {
        assertThatThrownBy(() -> new AgentTaskDefaults(
                Optional.empty(),
                Optional.of("high"),
                AgentPermissionPolicy.AUTO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effort requires a model");
    }

    @Test
    void acceptedTurnCannotReportCompletionBeforeItRuns()
    {
        assertThatThrownBy(() -> newTurn().finish(
                TurnState.COMPLETED,
                TurnMetrics.empty(),
                Optional.empty(),
                ACCEPTED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before running");
    }

    @Test
    void cancellationRequestIsIdempotentUntilTheProviderExits()
    {
        AgentTurn running = newTurn().running(
                new ProviderTurnRef("provider-turn-1"),
                Optional.empty(),
                ACCEPTED_AT.plusSeconds(1));
        AgentTurn requested = running.requestCancellation(ACCEPTED_AT.plusSeconds(2));

        assertThat(requested.state()).isEqualTo(TurnState.RUNNING);
        assertThat(requested.requestCancellation(ACCEPTED_AT.plusSeconds(3))).isSameAs(requested);

        AgentTurn cancelled = requested.finish(
                TurnState.CANCELLED,
                TurnMetrics.empty(),
                Optional.of("cancelled by user"),
                ACCEPTED_AT.plusSeconds(4));
        assertThat(cancelled.state()).isEqualTo(TurnState.CANCELLED);
    }

    @Test
    void nonCompletedTerminalTurnRequiresAReason()
    {
        AgentTurn running = newTurn().running(
                new ProviderTurnRef("provider-turn-1"),
                Optional.empty(),
                ACCEPTED_AT.plusSeconds(1));

        assertThatThrownBy(() -> running.finish(
                TurnState.FAILED,
                TurnMetrics.empty(),
                Optional.empty(),
                ACCEPTED_AT.plusSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disagree");
    }

    private static AgentTurn newTurn()
    {
        return AgentTurn.accepted(
                TurnId.valueOf("123e4567-e89b-12d3-a456-426614174001"),
                TaskId.valueOf("123e4567-e89b-12d3-a456-426614174000"),
                "request-1",
                SETTINGS,
                ACCEPTED_AT);
    }
}
