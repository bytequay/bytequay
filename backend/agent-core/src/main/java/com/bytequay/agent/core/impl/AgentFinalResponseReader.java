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
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.TaskLogStore;

import java.util.List;

import static java.util.Objects.requireNonNull;

final class AgentFinalResponseReader
{
    private static final int EVENT_PAGE_SIZE = 1000;

    private final TaskLogStore logStore;

    AgentFinalResponseReader(TaskLogStore logStore)
    {
        this.logStore = requireNonNull(logStore, "logStore is null");
    }

    String read(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        String response = "";
        long afterSequence = 0;
        while (true) {
            List<AgentEventEnvelope> page = logStore.readAfter(taskId, afterSequence, EVENT_PAGE_SIZE);
            for (AgentEventEnvelope envelope : page) {
                if (envelope.payload() instanceof AgentEventPayload.AssistantMessage message
                        && message.messageKind() == AgentEventPayload.MessageKind.ANSWER
                        && message.phase() == AgentEventPayload.MessagePhase.FINAL) {
                    response = message.text();
                }
            }
            if (page.size() < EVENT_PAGE_SIZE) {
                return response;
            }
            afterSequence = page.getLast().sequence();
        }
    }
}
