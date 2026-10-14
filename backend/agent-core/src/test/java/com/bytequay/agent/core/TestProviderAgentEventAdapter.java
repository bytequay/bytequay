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

import com.bytequay.agent.spi.DevelopmentLearningSummary;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderUsageSample;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestProviderAgentEventAdapter
{
    @Test
    void preservesTheRootDevelopmentReportAndItsSource()
    {
        ProviderAgentEventSource source = new ProviderAgentEventSource(
                Optional.of("root-thread"), Optional.empty(), Optional.of("/"), Optional.of("turn-1"), Optional.of("report-1"));
        ProviderAgentEvent.DevelopmentResult result = new ProviderAgentEvent.DevelopmentResult(
                Optional.of("event-1"),
                new DevelopmentResultReport(false, Optional.of("Which approach?"),
                        Optional.of(new DevelopmentLearningSummary("Purpose", ImmutableList.of(), Optional.empty(), "Tests", ImmutableList.of()))),
                source);

        ProviderAgentEventAdapter.CanonicalEvent canonical = ProviderAgentEventAdapter.adapt(result);

        assertThat(canonical.payload()).isEqualTo(new AgentEventPayload.DevelopmentResult(result.report()));
        assertThat(((AgentEventPayload.DevelopmentResult) canonical.payload()).learningSummary()).isEqualTo(result.report().learningSummary());
        assertThat(canonical.source().providerTurnId()).contains("turn-1");
        assertThat(canonical.source().providerItemId()).contains("report-1");
        assertThat(canonical.source().logicalAgentPath()).contains("/");
    }

    @Test
    void keepsAUsageSampleAsReportedUnderEverySemantics()
    {
        for (ProviderUsageSample.Semantics semantics : ProviderUsageSample.Semantics.values()) {
            ProviderUsageSample sample = new ProviderUsageSample(Optional.empty(), semantics, 3, 2, 1, Optional.of(new BigDecimal("0.5")));
            assertThat(ProviderAgentEventAdapter.adapt(new ProviderAgentEvent.Usage(sample)).payload())
                    .isEqualTo(new AgentEventPayload.UsageUpdated(AgentEventPayload.UsageSemantics.valueOf(semantics.name()), 3, 2, 1, sample.cost()));
        }
    }

    @Test
    void preservesProviderRoutingAndReplacementIdentity()
    {
        ProviderAgentEventSource source = new ProviderAgentEventSource(
                Optional.of("thread-child"),
                Optional.of("thread-root"),
                Optional.of("/review/tests"),
                Optional.of("turn-1"),
                Optional.of("message-1"));
        ProviderAgentEvent.Message message = new ProviderAgentEvent.Message(Optional.of("event-1"), ProviderAgentEvent.MessagePhase.FINAL, "done", source);

        AgentEventSource adapted = ProviderAgentEventAdapter.adapt(message).source();

        assertThat(adapted.providerThreadId()).contains("thread-child");
        assertThat(adapted.parentProviderThreadId()).contains("thread-root");
        assertThat(adapted.logicalAgentPath()).contains("/review/tests");
        assertThat(adapted.providerTurnId()).contains("turn-1");
        assertThat(adapted.providerItemId()).contains("message-1");
        assertThat(adapted.providerEventId()).contains("event-1");
    }

    @Test
    void preservesExplicitProviderItemIds()
    {
        ProviderAgentEvent.Tool tool = new ProviderAgentEvent.Tool(
                Optional.empty(),
                "read",
                ProviderAgentEvent.ActivityState.STARTED,
                "reading",
                ProviderAgentEventSource.item("tool-call-1"));
        ProviderAgentEvent.Command command = new ProviderAgentEvent.Command(
                Optional.empty(),
                ProviderAgentEvent.ActivityState.COMPLETED,
                ImmutableList.of("git", "status"),
                Optional.empty(),
                Optional.of(0),
                false,
                ProviderAgentEventSource.item("command-1"));

        assertThat(ProviderAgentEventAdapter.adapt(tool).source().providerItemId()).contains("tool-call-1");
        assertThat(ProviderAgentEventAdapter.adapt(command).source().providerItemId()).contains("command-1");
    }

    @Test
    void carriesMessageKindCommandFactsAndToolDuration()
    {
        ProviderAgentEvent.Message summary = new ProviderAgentEvent.Message(
                Optional.of("event-1"),
                ProviderAgentEvent.MessageKind.REASONING_SUMMARY,
                ProviderAgentEvent.MessagePhase.FINAL,
                "why",
                ProviderAgentEventSource.root());
        assertThat(((AgentEventPayload.AssistantMessage) ProviderAgentEventAdapter.adapt(summary).payload()).messageKind())
                .isEqualTo(ProviderAgentEvent.MessageKind.REASONING_SUMMARY);

        ProviderAgentEvent.Command command = new ProviderAgentEvent.Command(
                Optional.of("event-2"),
                ProviderAgentEvent.ActivityState.COMPLETED,
                ImmutableList.of("rg TODO"),
                Optional.empty(),
                Optional.of(0),
                false,
                ProviderAgentEventSource.item("exec-1"),
                Optional.of("backend"),
                Optional.of(Duration.ofMillis(1450)),
                ImmutableList.of(new ProviderAgentEvent.CommandAction(
                        ProviderAgentEvent.CommandActionKind.SEARCH,
                        Optional.of("TODO references"),
                        Optional.empty(),
                        Optional.of("TODO"))));
        AgentEventPayload.CommandActivity activity = (AgentEventPayload.CommandActivity) ProviderAgentEventAdapter.adapt(command).payload();
        assertThat(activity.cwd()).contains("backend");
        assertThat(activity.durationMs()).contains(1450L);
        assertThat(activity.actions()).containsExactly(new ProviderAgentEvent.CommandAction(
                ProviderAgentEvent.CommandActionKind.SEARCH,
                Optional.of("TODO references"),
                Optional.empty(),
                Optional.of("TODO")));

        ProviderAgentEvent.Tool tool = new ProviderAgentEvent.Tool(
                Optional.empty(),
                "Web search",
                ProviderAgentEvent.ActivityState.COMPLETED,
                "trino",
                ProviderAgentEventSource.item("tool-1"),
                Optional.of(Duration.ofMillis(20)));
        assertThat(((AgentEventPayload.ToolActivity) ProviderAgentEventAdapter.adapt(tool).payload()).durationMs())
                .contains(20L);
    }
}
