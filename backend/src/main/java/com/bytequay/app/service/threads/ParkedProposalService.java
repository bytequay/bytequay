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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static java.util.Objects.requireNonNull;

/**
 * Persists and locally resolves parked publish proposals as durable
 * transitions.
 *
 * <p>A task must not be placed at {@code AWAITING_REVIEW} unless its
 * actionable notification is written in the same transaction. Otherwise
 * an MCP failure can leave work parked with no approve/discard path.
 *
 * <p>Flow:
 * <pre>
 *  Agent turn        Backend                          User
 *  ─────────         ───────                          ────
 *   side-effect ──▶  {@link #park park(task, payload)}
 *   tool call        ┌─ single transaction ──┐
 *                    │ task   → AWAITING_REV │
 *                    │ notif  = AWAITING_REV │
 *                    │ payload = diff + body │
 *                    └───────────────────────┘
 *                            │
 *                            │ SSE push, badge tick
 *                            ▼
 *                                              PublishGatePane:
 *                                                diff + editable reply,
 *                                                [Approve] [Discard]
 *                                                    │
 *           ┌────────────────────────────────────────┤
 *           ▼                                        ▼
 *      POST /notifications                    POST /notifications
 *        /{id}/approve                          /{id}/discard
 *      PublishService.approve()               PublishService.discard()
 *      • run deferred side-effect             • skip side-effect
 *        (push / comment / merge)             • notif  → resolved
 *      • notif  → resolved                    • task   → closed
 *      • task   → next state
 * </pre>
 */
@Service
public class ParkedProposalService
{
    private final TaskStore taskStore;
    private final NotificationService notifications;
    private final ObjectMapper mapper;
    private final TaskCommandExecutor commands;
    private final TaskPhaseMachine taskPhaseMachine;

    public ParkedProposalService(
            TaskStore taskStore,
            NotificationService notifications,
            ObjectMapper mapper,
            TaskCommandExecutor commands,
            TaskPhaseMachine taskPhaseMachine)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.taskPhaseMachine = requireNonNull(taskPhaseMachine, "taskPhaseMachine is null");
    }

    public Notification park(Task task, Object payload)
    {
        requireNonNull(task, "task is null");
        requireNonNull(payload, "payload is null");

        String payloadJson;
        try {
            payloadJson = mapper.writeValueAsString(payload);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise parked proposal", e);
        }

        return commands.execute(task.id(), () -> {
            Task parked = taskPhaseMachine.parkForLocalReviewInCommand(
                    task.id(), Actor.AGENT, "publish_proposal_parked");
            return notifications.notifyAwaitingReview(
                    parked.threadId(), parked.id(), payloadJson);
        });
    }

    /** Parks a user-gated remote proposal after V2 cancellation was committed. */
    public Notification parkReadOnlyV2Proposal(
            String trunkId, String taskId, Object payload)
    {
        requireNonNull(trunkId, "trunkId is null");
        requireNonNull(taskId, "taskId is null");
        requireNonNull(payload, "payload is null");
        if (trunkId.isBlank() || taskId.isBlank()) {
            throw new IllegalArgumentException(
                    "Read-only V2 proposal owner is blank");
        }
        if (!taskStore.isV2Task(taskId)) {
            throw new IllegalArgumentException(
                    "Read-only V2 proposal requires a V2 Task");
        }
        String payloadJson;
        try {
            payloadJson = mapper.writeValueAsString(payload);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise parked proposal", e);
        }
        return commands.execute(taskId, () -> notifications.notifyAwaitingReview(
                trunkId, taskId, payloadJson));
    }

    /** Finalize a successful approved proposal after any remote side
     *  effect has returned. Advance actions already updated their task
     *  rows in {@link TaskService}; other actions close the parked task
     *  here in the same transaction as the notification resolution. */
    public void finishApproved(Notification proposal, boolean taskAlreadyAdvanced)
    {
        requireNonNull(proposal, "proposal is null");
        executeResolution(proposal, () -> {
            if (!taskAlreadyAdvanced) {
                completeTaskIfStillParked(
                        proposal.taskId(), "publish_proposal_approved");
            }
            finishClaim(proposal.id());
        });
    }

    /** Resolve a user discard without any remote action. */
    public void finishDiscarded(Notification proposal, boolean resumeTask)
    {
        requireNonNull(proposal, "proposal is null");
        executeResolution(proposal, () -> {
            if (resumeTask) {
                resumeTaskIfStillParked(
                        proposal.taskId(), "publish_proposal_discarded");
            }
            else {
                completeTaskIfStillParked(
                        proposal.taskId(), "publish_proposal_discarded");
            }
            finishClaim(proposal.id());
        });
    }

    /**
     * Finish an interrupted approval without invoking its remote action
     * again. Confirmation of an ordinary proposal closes its parked
     * task. An interrupted {@code next_task} resumes local work (no
     * successor is ever cut, so the prior task is always the one to
     * revive); terminal {@code ship_task} closes rather than reopening
     * shipped work.
     */
    public void finishInterruptedApproval(Notification proposal, String action)
    {
        requireNonNull(proposal, "proposal is null");
        requireNonNull(action, "action is null");
        executeResolution(proposal, () -> {
            if ("next_task".equals(action)) {
                resumeTaskIfStillParked(
                        proposal.taskId(), "interrupted_next_resumed");
            }
            else {
                completeTaskIfStillParked(
                        proposal.taskId(), "interrupted_publish_closed");
            }
            finishClaim(proposal.id());
        });
    }

    private void executeResolution(Notification proposal, Runnable resolution)
    {
        String taskId = proposal.taskId();
        if (taskId == null || taskId.isBlank()) {
            resolution.run();
            return;
        }
        commands.execute(taskId, () -> {
            resolution.run();
            return null;
        });
    }

    private void finishClaim(String notificationId)
    {
        if (!notifications.finishResolution(notificationId)) {
            // The atomic finishResolution write returned 0 rows, meaning
            // another concurrent approve/discard already finalized this
            // claim. Translate to a 409 the controllers can surface as a
            // clean "already resolved" — an IllegalStateException would
            // bubble as a 500 and obscure the race.
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "notification already resolved: " + notificationId);
        }
    }

    private void completeTaskIfStillParked(String taskId, String reason)
    {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        taskStore.findTaskById(taskId).ifPresent(task -> {
            if (task.status() != TaskStatus.AWAITING_REVIEW) {
                return;
            }
            if (task.linkedPrNumber() != null) {
                // A shipped task is still in its PR / CI-fix / review loop: a
                // resolved mid-loop proposal (push a fix, post a comment, …) is
                // not the end of the task. Keep it reviewable so its worktree
                // survives (the orphan sweep only reaps terminal tasks) and the
                // autonomous loop keeps driving it. The lifecycle driver
                // completes it for real once the PR actually merges or closes.
                taskPhaseMachine.markRemoteInReviewInCommand(
                        taskId, Actor.HUMAN, reason);
            }
            else {
                taskPhaseMachine.finishTerminalInCommand(
                        taskId, TaskStatus.COMPLETED, Actor.HUMAN, reason);
            }
        });
    }

    private void resumeTaskIfStillParked(String taskId, String reason)
    {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        taskStore.findTaskById(taskId).ifPresent(task -> {
            if (task.status() == TaskStatus.AWAITING_REVIEW) {
                taskPhaseMachine.resumeFromLocalReviewInCommand(
                        taskId, Actor.HUMAN, reason);
            }
        });
    }
}
