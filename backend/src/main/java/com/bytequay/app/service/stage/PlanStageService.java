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

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * The sanctioned transitions on a Task's PlanStage. The REST endpoint and
 * the brain/dev tooling layered on top (later milestone slices) all funnel
 * through here so the plan-approval invariant has exactly one
 * implementation: record {@code PLAN_APPROVED} on the open PlanStage, close
 * it, and move the phase to {@code IMPLEMENTING} — which opens the
 * DevelopmentStage via the normal {@link StageLifecycle} reconcile (the
 * just-written approval event satisfies its guard).
 */
@Service
public class PlanStageService
{
    private static final Logger log = LoggerFactory.getLogger(PlanStageService.class);

    private final StageStore stageStore;
    private final TaskPhaseMachine phaseMachine;

    public PlanStageService(StageStore stageStore, TaskPhaseMachine phaseMachine)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
    }

    /**
     * Approve the open PlanStage of {@code taskId} and open the
     * DevelopmentStage. Writes a {@code PLAN_APPROVED} event (carrying the
     * approved revision id, when known), closes the PlanStage, and
     * transitions the phase {@code PLANNING ▶ IMPLEMENTING}. Returns the
     * freshly-opened DevelopmentStage.
     *
     * @throws IllegalStateException if the Task has no open PlanStage
     */
    @Transactional
    public StageInstance approve(String taskId, String approvedRevisionId)
    {
        StageInstance plan = stageStore.findActiveStage(taskId)
                .filter(stage -> stage.type() == StageType.PLAN_STAGE)
                .orElseThrow(() -> new IllegalStateException(
                        "no open PlanStage to approve for task " + taskId));

        Map<String, Object> payload = new LinkedHashMap<>();
        if (approvedRevisionId != null) {
            payload.put("approvedRevisionId", approvedRevisionId);
        }
        payload.put("approvedAt", Instant.now().toString());
        stageStore.recordEvent(plan.id(), taskId, StageEventType.PLAN_APPROVED, payload);
        stageStore.closeStage(plan.id(), "plan_approved");

        // PLANNING ▶ IMPLEMENTING: StageLifecycle's reconcile opens the
        // DevelopmentStage off this transition; the PLAN_APPROVED event we
        // just wrote is what lets it past the plan guard.
        phaseMachine.transition(taskId, TaskPhase.IMPLEMENTING, "plan_approved", Actor.HUMAN);

        StageInstance dev = stageStore.findActiveStage(taskId)
                .orElseThrow(() -> new IllegalStateException(
                        "DevelopmentStage did not open after approving the plan for task " + taskId));
        log.debug("approved plan for task {}; opened {} stage {}", taskId, dev.type(), dev.id());
        return dev;
    }
}
