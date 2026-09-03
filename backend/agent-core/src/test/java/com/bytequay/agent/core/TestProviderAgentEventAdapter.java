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
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestProviderAgentEventAdapter
{
    @Test
    void preservesProviderRoutingAndReplacementIdentity()
    {
        ProviderAgentEventSource source = new ProviderAgentEventSource(
                Optional.of("thread-child"),
                Optional.of("thread-root"),
                Optional.of("/review/tests"),
                Optional.of("turn-1"),
                Optional.of("message-1"));
        ProviderAgentEvent.Message message = new ProviderAgentEvent.Message(
                Optional.of("event-1"),
                Optional.empty(),
                ProviderAgentEvent.MessagePhase.FINAL,
                "done",
                Optional.empty(),
                source);

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
                Optional.empty(),
                "tool-call-1",
                "read",
                ProviderAgentEvent.ActivityState.STARTED,
                "reading",
                Optional.empty());
        ProviderAgentEvent.Command command = new ProviderAgentEvent.Command(
                Optional.empty(),
                Optional.empty(),
                "command-1",
                ProviderAgentEvent.ActivityState.COMPLETED,
                ImmutableList.of("git", "status"),
                Optional.empty(),
                Optional.of(0),
                false);

        assertThat(ProviderAgentEventAdapter.adapt(tool).source().providerItemId()).contains("tool-call-1");
        assertThat(ProviderAgentEventAdapter.adapt(command).source().providerItemId()).contains("command-1");
    }

    @Test
    void doesNotInventRoutingIdentityForLegacyEvents()
    {
        ProviderAgentEvent.Tool tool = new ProviderAgentEvent.Tool(
                Optional.of("event-1"),
                Optional.empty(),
                "tool-call-1",
                "read",
                ProviderAgentEvent.ActivityState.COMPLETED,
                "read",
                Optional.empty());

        AgentEventSource source = ProviderAgentEventAdapter.adaptLegacy(tool).source();

        assertThat(source.providerThreadId()).isEmpty();
        assertThat(source.providerTurnId()).isEmpty();
        assertThat(source.providerItemId()).isEmpty();
        assertThat(source.providerEventId()).contains("event-1");
    }

    @Test
    void carriesMessageKindCommandFactsAndToolDuration()
    {
        ProviderAgentEvent.Message summary = new ProviderAgentEvent.Message(
                Optional.of("event-1"),
                Optional.empty(),
                ProviderAgentEvent.MessageKind.REASONING_SUMMARY,
                ProviderAgentEvent.MessagePhase.FINAL,
                "why",
                Optional.empty(),
                ProviderAgentEventSource.root());
        assertThat(((AgentEventPayload.AssistantMessage) ProviderAgentEventAdapter.adapt(summary).payload()).messageKind())
                .isEqualTo(AgentEventPayload.MessageKind.REASONING_SUMMARY);

        ProviderAgentEvent.Command command = new ProviderAgentEvent.Command(
                Optional.of("event-2"),
                Optional.empty(),
                "exec-1",
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
                        Optional.empty(),
                        Optional.of("TODO"))));
        AgentEventPayload.CommandActivity activity =
                (AgentEventPayload.CommandActivity) ProviderAgentEventAdapter.adapt(command).payload();
        assertThat(activity.cwd()).contains("backend");
        assertThat(activity.durationMs()).contains(1450L);
        assertThat(activity.actions()).containsExactly(new AgentEventPayload.CommandAction(
                AgentEventPayload.CommandActionKind.SEARCH,
                Optional.empty(),
                Optional.of("TODO")));

        ProviderAgentEvent.Tool tool = new ProviderAgentEvent.Tool(
                Optional.empty(),
                Optional.empty(),
                "tool-1",
                "Web search",
                ProviderAgentEvent.ActivityState.COMPLETED,
                "trino",
                Optional.empty(),
                ProviderAgentEventSource.item("tool-1"),
                Optional.of(Duration.ofMillis(20)));
        assertThat(((AgentEventPayload.ToolActivity) ProviderAgentEventAdapter.adapt(tool).payload()).durationMs())
                .contains(20L);
    }

    @Test
    void treatsDisplayOnlyProviderApprovalsAsNonActionable()
    {
        ProviderAgentEvent.Approval approval = new ProviderAgentEvent.Approval(
                Optional.of("event-1"),
                Optional.empty(),
                "approval-1",
                "Run command",
                "git status");

        assertThat(ProviderAgentEventAdapter.adapt(approval).payload())
                .isInstanceOfSatisfying(AgentEventPayload.ApprovalRequested.class, event -> {
                    assertThat(event.actionable()).isFalse();
                    assertThat(event.availableDecisions()).isEmpty();
                });
    }
}
