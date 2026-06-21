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
import com.bytequay.app.service.threads.PlanKickoffRequested;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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
    private final ObjectMapper mapper;

    public PlanStageService(StageStore stageStore, TaskPhaseMachine phaseMachine, ObjectMapper mapper)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /**
     * Seed a trunk-supplied plan onto the freshly-opened PlanStage. Runs on
     * the same {@link PlanKickoffRequested} that starts the brain's planning
     * turn; if the create request carried a {@code trunkPlan}, it lands as the
     * stage's first {@code PLAN_RECORDED} event with {@code source=trunk}, so
     * the brain's own plan(s) record as revisions on top of it. A no-op when
     * no trunk plan was supplied.
     */
    @EventListener
    @Transactional
    public void onPlanKickoff(PlanKickoffRequested event)
    {
        JsonNode trunkPlan = event.trunkPlan();
        if (trunkPlan == null || !trunkPlan.isObject()) {
            return;
        }
        stageStore.findActiveStage(event.taskId())
                .filter(stage -> stage.type() == StageType.PLAN_STAGE)
                .ifPresent(plan -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    trunkPlan.fields().forEachRemaining(field ->
                            payload.put(field.getKey(),
                                    mapper.convertValue(field.getValue(), Object.class)));
                    payload.put("id", UUID.randomUUID().toString());
                    payload.put("plannedAt", Instant.now().toString());
                    payload.put("source", "trunk");
                    stageStore.recordEvent(
                            plan.id(), event.taskId(), StageEventType.PLAN_RECORDED, payload);
                    log.debug("seeded trunk plan on PlanStage {} for task {}",
                            plan.id(), event.taskId());
                });
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
