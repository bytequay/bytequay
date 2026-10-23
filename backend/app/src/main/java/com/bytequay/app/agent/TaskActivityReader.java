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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentApproval;
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.bytequay.app.utils.ContextText;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimaps;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

/**
 * Summarizes tasks for lists and Needs attention from local stores only: the newest root message
 * in each event log, the task's pending approval, and its newest turn. Nothing here reaches a
 * provider, and a task's runtime files are never created by reading them.
 */
@Component
public class TaskActivityReader
{
    /** Enough records to walk back over a turn's tool activity to the message before it. */
    private static final int MAX_RECORDS = 400;
    private static final int MAX_TEXT_LENGTH = 400;

    private final TaskLogStore logs;
    private final AgentApprovalStore approvals;
    private final AgentTurnStore turns;

    public TaskActivityReader(TaskLogStore logs, AgentApprovalStore approvals, AgentTurnStore turns)
    {
        this.logs = requireNonNull(logs, "logs is null");
        this.approvals = requireNonNull(approvals, "approvals is null");
        this.turns = requireNonNull(turns, "turns is null");
    }

    public Map<TaskId, TaskActivity> read(List<AgentTask> tasks)
    {
        ImmutableListMultimap<TaskId, ApprovalRequest> pending = Multimaps.index(approvals.listPending().stream().map(AgentApproval::request).iterator(), ApprovalRequest::taskId);
        ImmutableMap.Builder<TaskId, TaskActivity> activity = ImmutableMap.builder();
        for (AgentTask task : tasks) {
            activity.put(task.id(), new TaskActivity(
                    lastMessage(task),
                    pending.get(task.id()).stream().max(comparing(ApprovalRequest::requestedAt)),
                    turns.list(task.id()).stream().max(comparing(AgentTurn::createdAt))));
        }
        return activity.buildOrThrow();
    }

    private Optional<TaskMessage> lastMessage(AgentTask task)
    {
        if (task.cleanupState() == CleanupState.DELETED) {
            // Older cleanups removed the transcript, so a cleaned task quotes its kept final answer, the last thing the agent said.
            return task.summary()
                    .filter(summary -> !summary.finalResponse().isBlank())
                    .map(summary -> new TaskMessage(TaskMessage.Author.AGENT, clip(summary.finalResponse()), task.terminalAt().orElse(task.updatedAt()), Optional.empty()));
        }
        try {
            return logs.findLatest(task.id(), TaskActivityReader::rootMessage, MAX_RECORDS).map(TaskActivityReader::message);
        }
        catch (AgentStorageException e) {
            // A missing or unreadable log leaves the list without a quote rather than failing it.
            return Optional.empty();
        }
    }

    /** A user's message, or a finished answer or commentary of the task's own agent, never a subagent's. */
    static boolean rootMessage(AgentEventEnvelope event)
    {
        if (event.source().parentProviderThreadId().isPresent()) {
            return false;
        }
        return switch (event.payload()) {
            case AgentEventPayload.UserMessage ignored -> true;
            case AgentEventPayload.AssistantMessage message -> message.phase() == MessagePhase.FINAL
                    && (message.messageKind() == MessageKind.ANSWER || message.messageKind() == MessageKind.COMMENTARY)
                    && !message.text().isBlank();
            default -> false;
        };
    }

    private static TaskMessage message(AgentEventEnvelope event)
    {
        return switch (event.payload()) {
            case AgentEventPayload.UserMessage message -> new TaskMessage(TaskMessage.Author.USER, clip(message.text()), event.occurredAt(), event.turnId());
            case AgentEventPayload.AssistantMessage message -> new TaskMessage(TaskMessage.Author.AGENT, clip(message.text()), event.occurredAt(), event.turnId());
            default -> throw new IllegalArgumentException("Not a message: " + event.payload().kind());
        };
    }

    private static String clip(String text)
    {
        return ContextText.excerpt(text.strip(), MAX_TEXT_LENGTH);
    }
}
