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

    private final TaskStore taskStore;
    private final PublishService publishService;
    private final ObjectMapper mapper;

    public AutoApproveGateListener(
            TaskStore taskStore, PublishService publishService, ObjectMapper mapper)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.publishService = requireNonNull(publishService, "publishService is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onGateParked(GateParkedEvent event)
    {
        if (event.taskId() == null || !taskStore.isAutoApprove(event.taskId())) {
            return;
        }
        String action = parseAction(event.payloadJson());
        // The final PR merge is the one gate auto-approve never resolves.
        if (action == null || "merge_pr".equals(action)) {
            return;
        }
        try {
            publishService.approve(event.notificationId(), null, action);
            log.info("Auto-approved {} gate for task {} (notification {})",
                    action, event.taskId(), event.notificationId());
        }
        catch (RuntimeException e) {
            log.warn("Auto-approve of {} gate (notification {}) failed: {}",
                    action, event.notificationId(), e.getMessage());
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
