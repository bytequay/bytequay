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
