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
package com.bytequay.app.notices;

import com.bytequay.agent.core.AgentApproval;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimaps;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

/** Reads local facts without opening transcripts, contacting providers, or changing execution. */
@Service
public class AppNoticeService
{
    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final AgentApprovalStore approvals;
    private final QueuedInputStore queues;
    private final ObjectMapper mapper;

    public AppNoticeService(AgentTaskStore tasks, AgentTurnStore turns, AgentApprovalStore approvals, QueuedInputStore queues, ObjectMapper mapper)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.approvals = requireNonNull(approvals, "approvals is null");
        this.queues = requireNonNull(queues, "queues is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    public List<AppNotice> current()
    {
        Map<TaskId, AgentTask> candidates = new LinkedHashMap<>();
        // Unresolved requests must survive even when newer completed tasks fill the recent page.
        tasks.listRecent(200).forEach(task -> candidates.put(task.id(), task));
        tasks.listLive().forEach(task -> candidates.put(task.id(), task));
        var pending = Multimaps.index(approvals.listPending().stream().map(AgentApproval::request).iterator(), ApprovalRequest::taskId);
        ImmutableList.Builder<AppNotice> notices = ImmutableList.builder();
        for (AgentTask task : candidates.values()) {
            if (!task.interactive() || task.archivedAt().isPresent()) {
                continue;
            }
            List<AgentTurn> history = turns.list(task.id());
            Optional<AgentTurn> latest = history.stream().max(comparing(AgentTurn::createdAt));
            Optional<ApprovalRequest> approval = pending.get(task.id()).stream()
                    .filter(request -> latest.filter(turn -> turn.state().active() && turn.id().equals(request.turnId())).isPresent())
                    .min(comparing(ApprovalRequest::requestedAt));
            select(task, history, latest, approval).ifPresent(notices::add);
        }
        return notices.build();
    }

    private Optional<AppNotice> select(AgentTask task, List<AgentTurn> history, Optional<AgentTurn> latest, Optional<ApprovalRequest> approval)
    {
        if (task.state() == TaskState.FAILED) {
            return Optional.of(notice(task, latest, "failed:" + task.id(), AppNotice.Kind.FAILURE, task.terminalReason().orElse("Task failed"), task.terminalAt().orElseThrow()));
        }
        if (task.state() == TaskState.OPEN && approval.isPresent()) {
            ApprovalRequest request = approval.orElseThrow();
            boolean question = request.providerRequest().requestedPermissions().contains("AskUserQuestion");
            return Optional.of(notice(task, latest, "approval:" + request.id(), question ? AppNotice.Kind.QUESTION : AppNotice.Kind.APPROVAL,
                    question ? question(request.providerRequest().detail()) : request.providerRequest().title(), request.requestedAt()));
        }
        Optional<WorktreeMutationClaim> recovery = task.worktreeMutationClaim().filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.RECOVERY
                || claim.kind() == WorktreeMutationClaim.Kind.TURN && latest.filter(turn -> turn.state().active()).isEmpty());
        if (recovery.isPresent()) {
            WorktreeMutationClaim claim = recovery.orElseThrow();
            return Optional.of(notice(task, latest, "recovery:" + task.id() + ":" + claim.ownerId(), AppNotice.Kind.RECOVERY,
                    "ByteQuay could not verify that the agent stopped. New work is blocked; your files are kept.",
                    latest.flatMap(AgentTurn::endedAt).orElse(claim.acquiredAt())));
        }
        if (task.worktreeMutationClaim().isPresent() || task.developmentContinuationUnfinished() || latest.filter(turn -> turn.state().active()).isPresent()) {
            return Optional.empty();
        }
        Optional<AgentTurn> report = history.stream()
                .filter(turn -> turn.developmentOrigin().filter(origin -> origin.kind() == DevelopmentTurnOrigin.Kind.REFINE_SUMMARY).isEmpty())
                .filter(turn -> turn.developmentResult().isPresent())
                .max(comparing(AgentTurn::createdAt));
        if (task.state() == TaskState.OPEN && latest.filter(turn -> turn.state() == TurnState.COMPLETED).isPresent()
                && report.filter(turn -> turn.state() == TurnState.COMPLETED).flatMap(AgentTurn::developmentResult).filter(result -> !result.readyForDelivery()).isPresent()) {
            AgentTurn asked = report.orElseThrow();
            return Optional.of(notice(task, Optional.of(asked), "question:" + asked.id(), AppNotice.Kind.QUESTION,
                    asked.developmentResult().orElseThrow().message().orElse("The agent is waiting for your answer."), asked.endedAt().orElse(asked.createdAt())));
        }
        List<QueuedAgentInput> queue = task.state() == TaskState.OPEN ? queues.listQueued(task.id()).stream()
                .filter(input -> !input.state().historical()).collect(ImmutableList.toImmutableList()) : ImmutableList.of();
        Optional<QueuedAgentInput> paused = queue.stream().findFirst()
                .filter(input -> input.state() == QueuedAgentInput.State.PAUSED || input.state() == QueuedAgentInput.State.UNCERTAIN);
        if (latest.filter(turn -> turn.state() == TurnState.FAILED || turn.state() == TurnState.INTERRUPTED).isPresent()) {
            AgentTurn failed = latest.orElseThrow();
            String message = failed.terminalReason().orElse("The turn stopped before it finished.");
            if (paused.isPresent()) {
                message += " Queued messages are paused.";
            }
            return Optional.of(notice(task, latest, "failed:" + failed.id(), AppNotice.Kind.FAILURE, message, failed.endedAt().orElseThrow()));
        }
        if (paused.isPresent()) {
            QueuedAgentInput input = paused.orElseThrow();
            String message = input.state() == QueuedAgentInput.State.UNCERTAIN ? "Delivery is uncertain. Review the queue before sending anything again."
                    : input.pauseReason().orElse("Queued messages are waiting for you to resume them.");
            return Optional.of(notice(task, latest, "queue:" + input.id() + ":" + input.updatedAt(), AppNotice.Kind.QUEUE_PAUSED, message, input.updatedAt()));
        }
        // A successful turn that is handing off to the next queued message does not need a completion popup.
        if (!queue.isEmpty()) {
            return Optional.empty();
        }
        return latest.filter(turn -> turn.state() == TurnState.COMPLETED)
                .map(turn -> notice(task, latest, "finished:" + turn.id(), AppNotice.Kind.TURN_FINISHED, "The latest response is ready to read.", turn.endedAt().orElseThrow()));
    }

    private String question(String detail)
    {
        try {
            String text = Optional.ofNullable(mapper.readTree(detail)).map(node -> node.path("questions").path(0).path("question").asText()).orElse("");
            if (!text.isBlank()) {
                return text;
            }
        }
        catch (JsonProcessingException ignored) {
            // Provider input can be incomplete; answering still takes place in the full task view.
        }
        return "The agent has a question for you.";
    }

    private static AppNotice notice(AgentTask task, Optional<AgentTurn> turn, String id, AppNotice.Kind kind, String message, Instant occurredAt)
    {
        return new AppNotice(id, kind, task.id().toString(), task.workspaceId().orElseThrow(), turn.map(value -> value.id().toString()),
                clip(task.title(), 160), clip(message, 280), task.repository().toString(), task.laneId().value(), occurredAt);
    }

    private static String clip(String text, int limit)
    {
        String flat = text.strip().replaceAll("\\s+", " ");
        if (flat.length() <= limit) {
            return flat;
        }
        int end = Character.isHighSurrogate(flat.charAt(limit - 1)) ? limit - 1 : limit;
        return flat.substring(0, end) + "…";
    }
}
