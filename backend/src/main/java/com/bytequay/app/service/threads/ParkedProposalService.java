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

import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    public ParkedProposalService(
            TaskStore taskStore,
            NotificationService notifications,
            ObjectMapper mapper)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Transactional
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

        taskStore.saveTask(task.withStatus(TaskStatus.AWAITING_REVIEW));
        return notifications.notifyAwaitingReview(task.threadId(), task.id(), payloadJson);
    }

    /** Finalize a successful approved proposal after any remote side
     *  effect has returned. Advance actions already updated their task
     *  rows in {@link TaskService}; other actions close the parked task
     *  here in the same transaction as the notification resolution. */
    @Transactional
    public void finishApproved(Notification proposal, boolean taskAlreadyAdvanced)
    {
        requireNonNull(proposal, "proposal is null");
        if (!taskAlreadyAdvanced) {
            completeTaskIfStillParked(proposal.taskId());
        }
        finishClaim(proposal.id());
    }

    /** Resolve a user discard without any remote action. */
    @Transactional
    public void finishDiscarded(Notification proposal, boolean resumeTask)
    {
        requireNonNull(proposal, "proposal is null");
        if (resumeTask) {
            resumeTaskIfStillParked(proposal.taskId());
        }
        else {
            completeTaskIfStillParked(proposal.taskId());
        }
        finishClaim(proposal.id());
    }

    /**
     * Finish an interrupted approval without invoking its remote action
     * again. Confirmation of an ordinary proposal closes its parked
     * task. An interrupted {@code next_task} resumes local work unless
     * the approved advance already produced its successor; terminal
     * {@code ship_task} closes rather than reopening shipped work.
     *
     * <p>When an interrupted {@code next_task} has a live successor we
     * intentionally do not touch the prior task here — its
     * {@code AWAITING_REVIEW} state matches the happy-path approve
     * outcome (see {@link #finishApproved} with
     * {@code taskAlreadyAdvanced=true}) and is what
     * {@code isThreadParked} ignores in favour of the active sibling.
     * The prior task is audit-only at that point; the user resolves
     * the thread by acting on the successor.
     */
    @Transactional
    public void finishInterruptedApproval(Notification proposal, String action)
    {
        requireNonNull(proposal, "proposal is null");
        requireNonNull(action, "action is null");
        if ("next_task".equals(action)) {
            if (!successorExistsForParkedNext(proposal)) {
                resumeTaskIfStillParked(proposal.taskId());
            }
            // Successor exists → leave prior task parked as audit row,
            // mirroring the happy-path approve. No state change here.
        }
        else {
            completeTaskIfStillParked(proposal.taskId());
        }
        finishClaim(proposal.id());
    }

    private boolean successorExistsForParkedNext(Notification proposal)
    {
        if (proposal.threadId() == null) {
            return false;
        }
        return taskStore.findActiveTaskForThread(proposal.threadId())
                .filter(task -> !task.id().equals(proposal.taskId()))
                .isPresent();
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

    private void completeTaskIfStillParked(String taskId)
    {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        taskStore.findTaskById(taskId).ifPresent(task -> {
            if (task.status() == TaskStatus.AWAITING_REVIEW) {
                taskStore.saveTask(task.withStatus(TaskStatus.COMPLETED));
            }
        });
    }

    private void resumeTaskIfStillParked(String taskId)
    {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        taskStore.findTaskById(taskId).ifPresent(task -> {
            if (task.status() == TaskStatus.AWAITING_REVIEW) {
                taskStore.saveTask(asIdle(task));
            }
        });
    }

    private static Task asIdle(Task task)
    {
        // Clear the runtime handles on the prior parked execution.
        // processPid points at a process that's no longer ours, and
        // agentSessionId references a CLI session that already issued
        // its publish proposal — reusing either after the user chose
        // to keep editing would leave the next agent run thinking the
        // turn was already finalised. The Task row's remote linkage
        // (prNumber, prState, ciState, linkedPrNumber / linkedIssueNumber)
        // stays intact: a successful push during an interrupted advance
        // is real history, and the next sync pass refreshes the cached
        // PR / CI state from GitHub.
        return task
                .withStatus(TaskStatus.IDLE)
                .withProcessPid(null)
                .withAgentSessionId(null);
    }
}
