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
import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.threads.PlanKickoffRequested;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** Approve / replan API result shapes. */
    public record ApproveResult(String devStageId, String redirectUrl) {}

    public record ReplanResult(String planStageId) {}

    private final StageStore stageStore;
    private final TaskPhaseMachine phaseMachine;
    private final TaskStore taskStore;
    private final ThreadStore threadStore;
    private final ThreadTurnScheduler scheduler;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;

    public PlanStageService(
            StageStore stageStore,
            TaskPhaseMachine phaseMachine,
            TaskStore taskStore,
            ThreadStore threadStore,
            ThreadTurnScheduler scheduler,
            ApplicationEventPublisher events,
            ObjectMapper mapper)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.events = requireNonNull(events, "events is null");
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

    /**
     * REST entry point for {@code POST /api/stages/{planStageId}/approve}.
     * Validates the stage and its latest plan, then approves it (closing the
     * PlanStage, opening the DevelopmentStage) and kicks off the dev agent's
     * first turn with the approved plan injected. Returns the new
     * DevelopmentStage id and the surface to navigate to.
     *
     * <ul>
     *   <li>404 — no such stage</li>
     *   <li>400 — not a PlanStage / no PLAN_RECORDED / latest plan not finalized</li>
     *   <li>409 — the PlanStage is already closed</li>
     * </ul>
     */
    @Transactional
    public ApproveResult approveByStage(UUID planStageId)
    {
        StageInstance plan = stageStore.findStageById(planStageId)
                .orElseThrow(() -> status(404, "no such stage: " + planStageId));
        if (plan.type() != StageType.PLAN_STAGE) {
            throw status(400, "stage " + planStageId + " is not a PlanStage");
        }
        if (plan.state() == StageState.CLOSED) {
            throw status(409, "PlanStage " + planStageId + " is already closed");
        }
        JsonNode latest = latestPlan(plan.id())
                .orElseThrow(() -> status(400, "no plan has been recorded on this PlanStage yet"));
        if (!"finalized".equals(latest.path("status").asText(null))) {
            throw status(400, "the latest plan is not finalized — the brain must finalize it first");
        }

        approve(plan.taskId(), latest.path("id").asText(null));
        StageInstance dev = stageStore.findActiveStage(plan.taskId())
                .filter(stage -> stage.type() == StageType.DEVELOPMENT_STAGE)
                .orElseThrow(() -> status(500, "DevelopmentStage did not open on approval"));

        enqueueDevKickoff(plan.taskId(), latest);
        return new ApproveResult(
                dev.id().toString(), "/tasks/" + plan.taskId() + "/stages/" + dev.id());
    }

    /**
     * REST entry point for {@code POST /api/tasks/{taskId}/replan}. Opens a
     * fresh PlanStage after a prior one was approved; the original plan and
     * its conversation stay locked and readable.
     *
     * <ul>
     *   <li>400 — no closed PlanStage yet (use the existing open one)</li>
     *   <li>409 — a PlanStage is already open</li>
     * </ul>
     */
    @Transactional
    public ReplanResult replan(String taskId)
    {
        List<StageInstance> stages = stageStore.findStagesByTask(taskId);
        boolean hasClosedPlan = stages.stream()
                .anyMatch(s -> s.type() == StageType.PLAN_STAGE && s.state() == StageState.CLOSED);
        boolean hasOpenPlan = stages.stream()
                .anyMatch(s -> s.type() == StageType.PLAN_STAGE && s.state() != StageState.CLOSED);
        if (hasOpenPlan) {
            throw status(409, "a PlanStage is already open for task " + taskId);
        }
        if (!hasClosedPlan) {
            throw status(400, "task " + taskId + " has no approved plan to revise yet");
        }
        // PLANNING is a universal escape: this closes the active dev (or later)
        // stage and reopens a PlanStage via StageLifecycle.reconcile.
        phaseMachine.transition(taskId, TaskPhase.PLANNING, "replan", Actor.HUMAN);
        StageInstance plan = stageStore.findActiveStage(taskId)
                .filter(s -> s.type() == StageType.PLAN_STAGE)
                .orElseThrow(() -> status(500, "PlanStage did not reopen on replan"));
        // Re-run the brain's planning turn; it reads the prior plan + follow-ups
        // through its own tools, so no seed prompt is needed here.
        events.publishEvent(new PlanKickoffRequested(taskId, null, null));
        log.debug("opened replan PlanStage {} for task {}", plan.id(), taskId);
        return new ReplanResult(plan.id().toString());
    }

    /**
     * REST entry point for
     * {@code PATCH /api/stages/{planStageId}/followups/{followupEventId}}.
     * Flips a follow-up note's status to {@code addressed} or {@code dismissed}.
     *
     * <ul>
     *   <li>400 — status is not addressed/dismissed</li>
     *   <li>404 — event not found or not a follow-up note</li>
     * </ul>
     */
    @Transactional
    public void resolveFollowup(UUID followupEventId, String statusValue)
    {
        if (!"addressed".equals(statusValue) && !"dismissed".equals(statusValue)) {
            throw status(400, "status must be 'addressed' or 'dismissed'");
        }
        StageEvent event = stageStore.findEventById(followupEventId)
                .filter(e -> e.eventType() == StageEventType.PLAN_FOLLOWUP_NOTED)
                .orElseThrow(() -> status(404, "no follow-up note: " + followupEventId));
        Map<String, Object> payload = toMutableMap(event.payloadJson());
        payload.put("status", statusValue);
        stageStore.updateEventPayload(followupEventId, payload);
    }

    /** The latest recorded plan on a stage (newest {@code PLAN_RECORDED}), or
     *  empty when none has been recorded. */
    private Optional<JsonNode> latestPlan(UUID stageId)
    {
        return stageStore.findEventsByStage(stageId).stream()
                .filter(e -> e.eventType() == StageEventType.PLAN_RECORDED)
                .max(Comparator.comparing(StageEvent::eventAt))
                .map(e -> parse(e.payloadJson()));
    }

    private void enqueueDevKickoff(String taskId, JsonNode plan)
    {
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        Thread dev = threadStore.findThreadById(task.threadId()).orElse(null);
        if (dev == null) {
            return;
        }
        scheduler.enqueueTurn(dev, devKickoffPrompt(plan), TurnInitiator.unattended("plan-approved"));
    }

    private static String devKickoffPrompt(JsonNode plan)
    {
        StringBuilder steps = new StringBuilder();
        JsonNode stepNodes = plan.path("intent").path("steps");
        if (stepNodes.isArray()) {
            for (JsonNode step : stepNodes) {
                steps.append("\n").append(step.path("ordinal").asInt())
                        .append(". ").append(step.path("action").asText(""));
            }
        }
        String intent = plan.path("intent").path("summary").asText("");
        String validation = plan.path("intent").path("validationStrategy").asText("");
        String push = plan.path("intent").path("pushStrategy").asText("await_approval");
        return """
                Your plan for this task has been approved — implement it now.

                Intent: %s
                Steps:%s
                Validation: %s
                Push strategy: %s

                Follow the plan's steps. If you hit something the plan didn't \
                anticipate, you can note a concern with note_plan_concern, but keep \
                going unless you genuinely cannot proceed."""
                .formatted(intent, steps.toString(), validation, push);
    }

    private JsonNode parse(String json)
    {
        try {
            return mapper.readTree(json == null ? "{}" : json);
        }
        catch (JsonProcessingException e) {
            return mapper.createObjectNode();
        }
    }

    private Map<String, Object> toMutableMap(String json)
    {
        Map<String, Object> map = new LinkedHashMap<>();
        parse(json).fields().forEachRemaining(field ->
                map.put(field.getKey(), mapper.convertValue(field.getValue(), Object.class)));
        return map;
    }

    private static ResponseStatusException status(int code, String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(code), message);
    }
}
