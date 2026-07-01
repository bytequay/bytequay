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
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import static java.util.Objects.requireNonNull;

/**
 * Auto-approve mode for parked publish gates. When a gate is parked for a task
 * whose auto-approve toggle is on, this approves it on the user's behalf — the
 * one exception is the final PR merge ({@code merge_pr}), which always stays
 * manually gated.
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

    /** Upper bound on how many of a thread's notifications the enable-sweep
     *  scans — far more than a live task's handful of parked gates. */
    private static final int SWEEP_LIMIT = 200;

    private final TaskStore taskStore;
    private final PublishService publishService;
    private final NotificationStore notificationStore;
    private final ObjectMapper mapper;

    public AutoApproveGateListener(
            TaskStore taskStore, PublishService publishService,
            NotificationStore notificationStore, ObjectMapper mapper)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.publishService = requireNonNull(publishService, "publishService is null");
        this.notificationStore = requireNonNull(notificationStore, "notificationStore is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onGateParked(GateParkedEvent event)
    {
        if (event.taskId() == null || !taskStore.isAutoApprove(event.taskId())) {
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

    /** Approve one parked gate on the user's behalf — unless it's the final PR
     *  merge, the one gate auto-approve never resolves. */
    private void approveGate(String taskId, String notificationId, String payloadJson)
    {
        String action = parseAction(payloadJson);
        if (action == null || "merge_pr".equals(action)) {
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
