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
package com.bytequay.app.service.stage;

import com.bytequay.app.repository.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import static java.util.Objects.requireNonNull;

/**
 * Auto-approve mode for the plan gate. When the brain finalizes a plan on a
 * task whose auto-approve toggle is on, this closes the PlanStage and opens
 * Development on the user's behalf — the same effect as clicking "Approve plan"
 * — so an auto-approve task runs hands-off from create through ready-to-merge
 * (the final PR merge is the one gate that always stays manual; see {@link
 * com.bytequay.app.service.threads.AutoApproveGateListener}).
 *
 * <p>Runs {@code AFTER_COMMIT} so it acts on a persisted plan;
 * {@code fallbackExecution} keeps it working when the recording call isn't
 * itself transactional.
 */
@Component
public class AutoApprovePlanListener
{
    private static final Logger log = LoggerFactory.getLogger(AutoApprovePlanListener.class);

    private final TaskStore taskStore;
    private final PlanStageService planStageService;

    public AutoApprovePlanListener(TaskStore taskStore, PlanStageService planStageService)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.planStageService = requireNonNull(planStageService, "planStageService is null");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPlanFinalized(PlanFinalizedEvent event)
    {
        if (!taskStore.isAutoApprove(event.taskId())) {
            return;
        }
        try {
            planStageService.approveByStage(event.planStageId());
            log.info("Auto-approved plan for task {} (PlanStage {})", event.taskId(), event.planStageId());
        }
        catch (RuntimeException e) {
            log.warn("Auto-approve of plan for task {} (PlanStage {}) failed: {}",
                    event.taskId(), event.planStageId(), e.getMessage());
        }
    }
}
