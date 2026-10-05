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
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.bytequay.app.context.ContextText;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemEvent;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

/**
 * Records how a turn of a task working on a Goal's work item ended: a receipt in the item's history, once per turn, with what a finished turn
 * said or why a failed or interrupted one stopped, the pull request the task published, and a word to the goal's agent, which may recommend
 * what comes next or how to recover. The item's state stays the user's decision.
 */
@Component
public class GoalReceipts
{
    private static final Logger log = LoggerFactory.getLogger(GoalReceipts.class);
    private static final int MAX_RECORDS = 10_000;
    private static final int MAX_ANSWER = 1_500;
    private static final Map<TurnState, String> ENDINGS = ImmutableMap.of(TurnState.COMPLETED, "finished", TurnState.FAILED, "failed",
            TurnState.INTERRUPTED, "was interrupted");

    private final WorkItemStore items;
    private final WorkspaceStore workspaces;
    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final TaskLogStore logs;
    private final ObjectProvider<WorkItemService> work;
    private final ObjectProvider<GoalConversationService> conversations;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("goal-receipts-", 0).factory());

    /** A receipt saved for a turn: the work it belongs to, how the turn ended and what it said or why it stopped. */
    private record Saved(WorkItem item, TurnId turn, TurnState state, String answer)
    {
        String ended()
        {
            return ENDINGS.get(state);
        }
    }

    public GoalReceipts(WorkItemStore items, WorkspaceStore workspaces, AgentTaskStore tasks, AgentTurnStore turns, TaskLogStore logs, ObjectProvider<WorkItemService> work,
            ObjectProvider<GoalConversationService> conversations)
    {
        this.items = requireNonNull(items, "items is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
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
        receipt(task, turn).ifPresent(saved -> tell(saved.item(), "turn:" + turn.id(), turn.state() == TurnState.COMPLETED,
                "A turn of the task for \"" + saved.item().title() + "\" " + saved.ended() + ": " + saved.answer()));
    }

    /** A turn that finished normally is progress the agent hears with its next news; a failed or interrupted one is news itself. */
    private void tell(WorkItem item, String operationId, boolean finished, String event)
    {
        GoalConversationService conversation = conversations.getObject();
        if (finished) {
            conversation.report(item.workspaceId(), item.goalId().orElseThrow(), operationId, item.id(), event);
        }
        else {
            conversation.tell(item.workspaceId(), item.goalId().orElseThrow(), operationId, event);
        }
    }

    /**
     * Records the turns that finished while ByteQuay was stopped, or whose receipt was lost, before the startup cleanup can remove their
     * answers; each Goal's agent hears once per work item what it missed.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void recover()
    {
        // Requests the agent was answering when ByteQuay stopped are settled first; while one looks pending, the agent cannot be told anything.
        conversations.getObject().settleInterrupted();
        for (Workspace workspace : workspaces.findAll()) {
            for (WorkItem item : items.list(workspace.id())) {
                try {
                    recover(item);
                }
                catch (RuntimeException failure) {
                    log.warn("Missed turns of Goal work {} were not recorded", item.id(), failure);
                }
            }
        }
    }

    private void recover(WorkItem item)
    {
        if (item.goalId().isEmpty()) {
            return;
        }
        Set<String> recorded = item.history().stream().map(WorkItemEvent::operationId).collect(toImmutableSet());
        List<Saved> missed = new ArrayList<>();
        for (WorkItemLink link : item.links()) {
            if (link.reference().kind() != GoalItemKind.TASK) {
                continue;
            }
            Optional<AgentTask> task = tasks.find(TaskId.valueOf(link.reference().key()));
            if (task.isEmpty()) {
                continue;
            }
            for (AgentTurn turn : turns.list(task.get().id())) {
                if (!recorded.contains("turn:" + turn.id())) {
                    receipt(task.get(), turn).ifPresent(missed::add);
                }
            }
        }
        if (!missed.isEmpty()) {
            tell(item, "recovered:" + missed.getLast().turn(), missed.stream().allMatch(saved -> saved.state() == TurnState.COMPLETED),
                    "While ByteQuay was stopped, " + (missed.size() == 1 ? "a turn" : missed.size() + " turns") + " of the task for \"" + item.title()
                            + "\" ended. The last " + missed.getLast().ended() + ": " + missed.getLast().answer());
        }
    }

    private Optional<Saved> receipt(AgentTask task, AgentTurn turn)
    {
        // Development's own automatic turns and noninteractive runs are not results the user worked toward, and a turn the user stopped needs no word.
        if (!task.interactive() || turn.developmentOrigin().isPresent() || !ENDINGS.containsKey(turn.state()) || task.workspaceId().isEmpty()) {
            return Optional.empty();
        }
        Optional<WorkItem> item = items.findByIdentity(WorkItemLink.taskIdentity(task.id().toString())).filter(found -> found.goalId().isPresent());
        if (item.isEmpty()) {
            return Optional.empty();
        }
        String answer = ContextText.excerpt(turn.state() == TurnState.COMPLETED ? finalAnswer(task, turn).orElse("The turn ended without a final answer.")
                : turn.terminalReason().orElse("No reason was recorded."), MAX_ANSWER);
        String ended = ENDINGS.get(turn.state());
        String workspaceId = task.workspaceId().orElseThrow();
        work.getObject().receipt(workspaceId, item.get().id(), "turn:" + turn.id(), "Task turn " + ended + ": " + answer);
        try {
            // A pull request the turn published joins the work it delivers, so its merge can be recorded.
            work.getObject().linkRelated(workspaceId, item.get().id());
        }
        catch (RuntimeException failure) {
            log.debug("Goal work {} did not pick up its task's pull request: {}", item.get().id(), failure.toString());
        }
        return Optional.of(new Saved(item.get(), turn.id(), turn.state(), answer));
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
