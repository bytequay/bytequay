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
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.repository.NotificationStore;
import com.bytequay.app.repository.TaskStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

/**
 * Auto-approve mode for parked publish gates. When a gate is parked for a task
 * whose auto-approve toggle is on, this approves it on the user's behalf.
 * Two fixed exceptions carve out of the per-task toggle in either direction:
 * {@link #NEVER_AUTO_APPROVE} always stays manually gated no matter the
 * toggle — the final PR merge, and every action that publishes something
 * externally visible to another person (a comment, a review, a reviewer
 * request) — while {@link #ALWAYS_AUTO_APPROVE} always resolves regardless of
 * the toggle — marking a PR ready for review is a visibility change, not a
 * publish action, so it never needs to wait on the user.
 *
 * <p>Runs {@code AFTER_COMMIT} so the approve acts on a persisted gate and the
 * GitHub side effect doesn't nest inside the parking transaction;
 * {@code fallbackExecution} keeps it working for the (non-transactional)
 * callers that park a gate outside a transaction.
 */
@Component
public class AutoApproveGateListener
{
    private static final Logger log = LoggerFactory.getLogger(AutoApproveGateListener.class);

    /** Actions that never auto-approve, regardless of the task's toggle — the
     *  final merge, and anything that publishes content another person will
     *  see (a comment, a review, a reviewer request). */
    private static final Set<String> NEVER_AUTO_APPROVE = Set.of(
            "merge_pr", "post_comment", "publish_review", "request_reviewer", "reply_review_thread");

    /** Actions that always auto-approve, regardless of the task's toggle —
     *  visibility changes with no externally-visible publish side effect. */
    private static final Set<String> ALWAYS_AUTO_APPROVE = Set.of("mark_ready");

    /** Upper bound on how many of a thread's notifications the enable-sweep
     *  scans — far more than a live task's handful of parked gates. */
    private static final int SWEEP_LIMIT = 200;

    /** Backstop attempts per stranded gate before it's escalated to the user
     *  instead of retried. */
    private static final int MAX_BACKSTOP_ATTEMPTS = 3;

    /** Give the in-process park/enable listeners this long to resolve a fresh
     *  gate before the backstop sweep considers it stranded — avoids racing
     *  the primary path and wasting an attempt on a gate about to resolve. */
    private static final Duration STRANDED_AFTER = Duration.ofSeconds(90);

    private final TaskStore taskStore;
    private final PublishService publishService;
    private final NotificationStore notificationStore;
    private final NotificationService notifications;
    private final ObjectMapper mapper;

    /** Per-gate backstop attempt counter. In-memory only: bounded by the live
     *  gate set, cleared when a gate resolves; a restart resets counts, which
     *  just grants a stranded gate a fresh few attempts (harmless).
     *  ponytail: in-memory cap — persist it only if restart-resets bite. */
    private final Map<String, Integer> backstopAttempts = new ConcurrentHashMap<>();

    public AutoApproveGateListener(
            TaskStore taskStore, PublishService publishService,
            NotificationStore notificationStore, NotificationService notifications,
            ObjectMapper mapper)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.publishService = requireNonNull(publishService, "publishService is null");
        this.notificationStore = requireNonNull(notificationStore, "notificationStore is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /**
     * Backstop for the two in-process listeners above. They fire only at park
     * time ({@link #onGateParked}) and toggle-on time ({@link
     * #onAutoApproveEnabled}), so a gate parked across a restart, lost to a
     * race, or released back to UNREAD by a transient push failure would never
     * retry — stranding the task despite auto-approve being on. This sweep
     * re-approves such gates, capping attempts so a genuinely broken action
     * escalates to the user rather than looping forever.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void reconcileStrandedGates()
    {
        Instant cutoff = Instant.now().minus(STRANDED_AFTER);
        for (Notification n : notificationStore.listByStatus(NotificationStatus.UNREAD, SWEEP_LIMIT)) {
            if (n.kind() != NotificationKind.AWAITING_REVIEW
                    || n.taskId() == null
                    || n.createdAt().isAfter(cutoff)) {
                continue;
            }
            String action = parseAction(n.payloadJson());
            if (action == null || NEVER_AUTO_APPROVE.contains(action)) {
                continue;
            }
            if (!ALWAYS_AUTO_APPROVE.contains(action) && !taskStore.isAutoApprove(n.taskId())) {
                continue;
            }
            int priorAttempts = backstopAttempts.getOrDefault(n.id(), 0);
            if (priorAttempts >= MAX_BACKSTOP_ATTEMPTS) {
                continue;   // already escalated — leave the gate for the user
            }
            int attempt = priorAttempts + 1;
            backstopAttempts.put(n.id(), attempt);
            try {
                publishService.approve(n.id(), null, action);
                backstopAttempts.remove(n.id());
                log.info("Backstop auto-approved {} gate for task {} (notification {})",
                        action, n.taskId(), n.id());
            }
            catch (RuntimeException e) {
                log.warn("Backstop auto-approve of {} gate (notification {}) failed (attempt {}/{}): {}",
                        action, n.id(), attempt, MAX_BACKSTOP_ATTEMPTS, e.getMessage());
                if (attempt >= MAX_BACKSTOP_ATTEMPTS) {
                    escalateStranded(n, action, e.getMessage());
                }
            }
        }
    }

    /** After the attempt cap, alert the user once so a broken push doesn't
     *  loop silently. The gate itself stays UNREAD for a manual approve. */
    private void escalateStranded(Notification n, String action, String reason)
    {
        try {
            notifications.notifyNeedsAttention(n.threadId(), n.taskId(), mapper.writeValueAsString(Map.of(
                    "reason", "auto_approve_failed",
                    "action", action,
                    "originalNotificationId", n.id(),
                    "message", reason == null ? "" : reason)));
        }
        catch (JsonProcessingException | RuntimeException e) {
            log.warn("escalation notice for stranded gate {} failed: {}", n.id(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onGateParked(GateParkedEvent event)
    {
        if (event.taskId() == null) {
            return;
        }
        approveGate(event.taskId(), event.notificationId(), event.payloadJson());
    }

    /**
     * The toggle was switched on: sweep the task's already-parked gates and
     * approve the non-merge ones. {@link #onGateParked} only fires at park
     * time, so without this a gate parked while auto-approve was off would sit
     * unresolved forever after the user turns it on.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAutoApproveEnabled(AutoApproveEnabledEvent event)
    {
        if (!taskStore.isAutoApprove(event.taskId())) {
            return;
        }
        for (Notification n : notificationStore.listForThread(event.threadId(), SWEEP_LIMIT)) {
            if (n.kind() == NotificationKind.AWAITING_REVIEW
                    && event.taskId().equals(n.taskId())
                    && n.status() == NotificationStatus.UNREAD) {
                approveGate(n.taskId(), n.id(), n.payloadJson());
            }
        }
    }

    /** Approve one parked gate on the user's behalf. {@link #NEVER_AUTO_APPROVE}
     *  actions never resolve here; {@link #ALWAYS_AUTO_APPROVE} actions always
     *  do, independent of the task's auto-approve toggle; every other action
     *  still requires it. */
    private void approveGate(String taskId, String notificationId, String payloadJson)
    {
        String action = parseAction(payloadJson);
        if (action == null || NEVER_AUTO_APPROVE.contains(action)) {
            return;
        }
        if (!ALWAYS_AUTO_APPROVE.contains(action) && !taskStore.isAutoApprove(taskId)) {
            return;
        }
        try {
            publishService.approve(notificationId, null, action);
            log.info("Auto-approved {} gate for task {} (notification {})",
                    action, taskId, notificationId);
        }
        catch (RuntimeException e) {
            log.warn("Auto-approve of {} gate (notification {}) failed: {}",
                    action, notificationId, e.getMessage());
        }
    }

    private String parseAction(String payloadJson)
    {
        if (payloadJson == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(payloadJson).get("action");
            return node != null && node.isTextual() ? node.asText() : null;
        }
        catch (JsonProcessingException e) {
            return null;
        }
    }
}
