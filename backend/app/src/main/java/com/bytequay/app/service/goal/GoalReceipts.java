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
package com.bytequay.app.service.goal;

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.bytequay.app.context.ContextText;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.store.WorkItemStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.Objects.requireNonNull;

/**
 * Records what a finished turn of a task working on a Goal's work item said: a receipt in the item's history, once per turn, and a word to
 * the goal's agent, which may recommend what comes next. The item's state stays the user's decision.
 */
@Component
public class GoalReceipts
{
    private static final Logger log = LoggerFactory.getLogger(GoalReceipts.class);
    private static final int MAX_RECORDS = 10_000;
    private static final int MAX_ANSWER = 1_500;

    private final WorkItemStore items;
    private final TaskLogStore logs;
    private final ObjectProvider<WorkItemService> work;
    private final ObjectProvider<GoalConversationService> conversations;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("goal-receipts-", 0).factory());

    public GoalReceipts(WorkItemStore items, TaskLogStore logs, ObjectProvider<WorkItemService> work, ObjectProvider<GoalConversationService> conversations)
    {
        this.items = requireNonNull(items, "items is null");
        this.logs = requireNonNull(logs, "logs is null");
        this.work = requireNonNull(work, "work is null");
        this.conversations = requireNonNull(conversations, "conversations is null");
    }

    /** Records off the provider's thread, so a turn settles without waiting for it. */
    public void finished(AgentTask task, AgentTurn turn)
    {
        executor.execute(() -> {
            try {
                record(task, turn);
            }
            catch (RuntimeException failure) {
                log.warn("Turn {} was not recorded for its Goal", turn.id(), failure);
            }
        });
    }

    void record(AgentTask task, AgentTurn turn)
    {
        // Development's own automatic turns and noninteractive runs are not results the user worked toward.
        if (!task.interactive() || turn.developmentOrigin().isPresent() || turn.state() != TurnState.COMPLETED || task.workspaceId().isEmpty()) {
            return;
        }
        Optional<WorkItem> item = items.findByIdentity(WorkItemLink.taskIdentity(task.id().toString())).filter(found -> found.goalId().isPresent());
        if (item.isEmpty()) {
            return;
        }
        String answer = ContextText.excerpt(finalAnswer(task, turn).orElse("The turn ended without a final answer."), MAX_ANSWER);
        String workspaceId = task.workspaceId().orElseThrow();
        work.getObject().receipt(workspaceId, item.get().id(), "turn:" + turn.id(), "Task turn finished: " + answer);
        conversations.getObject().tell(workspaceId, item.get().goalId().orElseThrow(), "turn:" + turn.id(),
                "A task for \"" + item.get().title() + "\" finished a turn: " + answer);
    }

    private Optional<String> finalAnswer(AgentTask task, AgentTurn turn)
    {
        try {
            return logs.findLatest(task.id(), event -> answers(event, turn), MAX_RECORDS)
                    .map(event -> ((AgentEventPayload.AssistantMessage) event.payload()).text());
        }
        catch (AgentStorageException unreadable) {
            return Optional.empty();
        }
    }

    private static boolean answers(AgentEventEnvelope event, AgentTurn turn)
    {
        return event.turnId().equals(Optional.of(turn.id())) && event.source().parentProviderThreadId().isEmpty()
                && event.payload() instanceof AgentEventPayload.AssistantMessage message && message.messageKind() == MessageKind.ANSWER
                && message.phase() == MessagePhase.FINAL;
    }
}
