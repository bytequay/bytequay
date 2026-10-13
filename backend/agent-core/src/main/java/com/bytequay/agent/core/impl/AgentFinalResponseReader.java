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

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.ChildSummary;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

final class AgentFinalResponseReader
{
    private static final int EVENT_PAGE_SIZE = 1000;

    private final TaskLogStore logStore;

    AgentFinalResponseReader(TaskLogStore logStore)
    {
        this.logStore = requireNonNull(logStore, "logStore is null");
    }

    /**
     * What the task answered and what every subagent its events name did, from one pass over the
     * history: the root thread's last final answer, and the compact child index in order of
     * discovery. Both are kept with the task after its transcript is removed.
     */
    FinalResponse read(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        Map<String, ChildFacts> children = new LinkedHashMap<>();
        String response = "";
        long afterSequence = 0;
        while (true) {
            List<AgentEventEnvelope> page = logStore.readAfter(taskId, afterSequence, EVENT_PAGE_SIZE);
            for (AgentEventEnvelope envelope : page) {
                observeChild(children, envelope);
                response = envelope.rootAnswer().orElse(response);
            }
            if (page.size() < EVENT_PAGE_SIZE) {
                return new FinalResponse(response, children.values().stream().map(ChildFacts::summary).collect(toImmutableList()));
            }
            afterSequence = page.getLast().sequence();
        }
    }

    record FinalResponse(String response, List<ChildSummary> children)
    {
        FinalResponse
        {
            requireNonNull(response, "response is null");
            children = ImmutableList.copyOf(children);
        }
    }

    private static void observeChild(Map<String, ChildFacts> children, AgentEventEnvelope envelope)
    {
        AgentEventSource source = envelope.source();
        if (source.parentProviderThreadId().isEmpty() || source.providerThreadId().isEmpty()) {
            return;
        }
        ChildFacts facts = children.computeIfAbsent(source.providerThreadId().orElseThrow(), thread -> new ChildFacts(thread, source.parentProviderThreadId().orElseThrow()));
        source.logicalAgentPath().ifPresent(label -> facts.label = label);
        switch (envelope.payload()) {
            case AgentEventPayload.Lifecycle lifecycle -> {
                switch (lifecycle.phase()) {
                    case AGENT_STARTED -> facts.startedAt = Optional.of(envelope.occurredAt());
                    case AGENT_COMPLETED -> {
                        facts.outcome = ChildSummary.Outcome.COMPLETED;
                        facts.endedAt = Optional.of(envelope.occurredAt());
                    }
                    case AGENT_FAILED -> {
                        facts.outcome = ChildSummary.Outcome.FAILED;
                        facts.endedAt = Optional.of(envelope.occurredAt());
                    }
                    default -> {}
                }
            }
            case AgentEventPayload.AssistantMessage message -> {
                if (message.phase() != MessagePhase.FINAL) {
                    return;
                }
                if (message.messageKind() == MessageKind.PROMPT) {
                    facts.purpose = Optional.of(message.text());
                }
                else if (message.messageKind() == MessageKind.ANSWER) {
                    facts.result = Optional.of(message.text());
                }
            }
            default -> {}
        }
    }

    private static final class ChildFacts
    {
        private final String thread;
        private final String parent;
        private String label;
        private Optional<String> purpose = Optional.empty();
        private ChildSummary.Outcome outcome = ChildSummary.Outcome.UNKNOWN;
        private Optional<String> result = Optional.empty();
        private Optional<Instant> startedAt = Optional.empty();
        private Optional<Instant> endedAt = Optional.empty();

        private ChildFacts(String thread, String parent)
        {
            this.thread = thread;
            this.parent = parent;
            this.label = "Agent";
        }

        private ChildSummary summary()
        {
            return new ChildSummary(thread, Optional.of(parent), label, purpose, outcome, result, startedAt, endedAt);
        }
    }
}
