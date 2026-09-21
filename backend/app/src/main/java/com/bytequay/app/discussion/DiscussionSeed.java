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
package com.bytequay.app.discussion;

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.bytequay.core.discussion.TaskDiscussion;

import java.util.List;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/**
 * Renders the task's conversation through one completed turn, as the starting context a discussion
 * inherits.
 *
 * <p>Only the reader's messages and the root thread's final answers are kept. Streaming deltas,
 * tool calls, tool output, plans and activity are excluded: they are the overwhelming majority of
 * the volume and the discussion can read the repository instead. Bodies are carried verbatim as the
 * markdown they already are.
 *
 * <p>The result is snapshotted when a discussion is created and never recomputed, because the task
 * runtime log is deleted after its retention period while the thread outlives it.
 */
final class DiscussionSeed
{
    private static final int EVENT_PAGE_SIZE = 1000;

    private final TaskLogStore logStore;

    DiscussionSeed(TaskLogStore logStore)
    {
        this.logStore = requireNonNull(logStore, "logStore is null");
    }

    /**
     * @param upTo the completed turn whose answer is being discussed; its own final answer is the
     *         last thing in the seed
     * @throws AgentUnavailableException when that turn has no recorded final answer, which is the
     *         only honest response to an anchor this task's history cannot support
     */
    String build(TaskId taskId, TurnId upTo)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(upTo, "upTo is null");
        StringBuilder seed = new StringBuilder("--- conversation so far ---\n");
        long afterSequence = 0;
        while (true) {
            List<AgentEventEnvelope> page = logStore.readAfter(taskId, afterSequence, EVENT_PAGE_SIZE);
            for (AgentEventEnvelope envelope : page) {
                boolean anchored = envelope.turnId().filter(upTo::equals).isPresent();
                if (envelope.payload() instanceof AgentEventPayload.UserMessage message) {
                    append(seed, "[user]", message.text());
                }
                else if (isRootAnswer(envelope)) {
                    append(seed, "[agent]", ((AgentEventPayload.AssistantMessage) envelope.payload()).text());
                    if (anchored) {
                        checkState(seed.length() <= TaskDiscussion.MAX_SEED_CHARACTERS,
                                "This task's history is too long to discuss; start from an earlier turn");
                        return seed.toString().stripTrailing();
                    }
                }
            }
            if (page.size() < EVENT_PAGE_SIZE) {
                throw new AgentUnavailableException("That turn has no recorded answer to discuss");
            }
            afterSequence = page.getLast().sequence();
        }
    }

    /** A subagent's answer is its own; only the root thread answers for the task. */
    private static boolean isRootAnswer(AgentEventEnvelope envelope)
    {
        return envelope.source().parentProviderThreadId().isEmpty()
                && envelope.payload() instanceof AgentEventPayload.AssistantMessage message
                && message.messageKind() == MessageKind.ANSWER
                && message.phase() == MessagePhase.FINAL;
    }

    private static void append(StringBuilder seed, String label, String body)
    {
        if (!body.isBlank()) {
            seed.append('\n').append(label).append('\n').append(body.strip()).append('\n');
        }
    }
}
