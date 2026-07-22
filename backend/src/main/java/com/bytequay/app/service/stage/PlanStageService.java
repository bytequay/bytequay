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
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.threads.PlanKickoffRequested;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
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
import java.util.Locale;
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
    private final ThreadTurnStore turnStore;
    private final ThreadTurnScheduler scheduler;
    private final PRService prService;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;

    public PlanStageService(
            StageStore stageStore,
            TaskPhaseMachine phaseMachine,
            TaskStore taskStore,
            ThreadStore threadStore,
            ThreadTurnStore turnStore,
            ThreadTurnScheduler scheduler,
            PRService prService,
            ApplicationEventPublisher events,
            ObjectMapper mapper)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.prService = requireNonNull(prService, "prService is null");
        this.events = requireNonNull(events, "events is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /**
     * Reacts to a planning turn finishing. Two behaviours, both keyed off the
     * turn's {@code plan-kickoff} / {@code plan-followup} initiator source:
     * <ul>
     *   <li><b>Failed</b> (the brain errored / produced nothing) — record a
     *       {@code PLAN_FAILED} event carrying the turn's error so the plan
     *       card and feed surface it instead of a silent empty draft.</li>
     *   <li><b>Succeeded but no plan recorded</b> — nudge once with a
     *       follow-up turn (only after the kickoff, so a second miss doesn't
     *       loop). The hard guarantee remains the approve endpoint's reject.</li>
     * </ul>
     */
    @EventListener
    @Transactional
    public void onTurnFinished(TaskTurnFinishedEvent event)
    {
        ThreadTurn turn = turnStore.findTurnById(event.turnId()).orElse(null);
        if (turn == null || turn.initiator() == null) {
            return;
        }
        String source = turn.initiator().source();
        if ("plan-kickoff".equals(source) || "plan-followup".equals(source)) {
            onPlanningTurnFinished(event, turn, source);
        }
        else if ("plan-approved".equals(source)
                || "automation-plan-approved".equals(source)) {
            // The development kickoff turn (enqueued by enqueueDevKickoff with
            // source "plan-approved"). If it ended still implementing without
            // proposing a push, nudge it once to ship.
            onDevTurnFinished(event);
        }
    }

    /** Plan-stage turn-end handling: surface a failure, else nudge once
     *  (after the kickoff only) to record a plan. */
    private void onPlanningTurnFinished(TaskTurnFinishedEvent event, ThreadTurn turn, String source)
    {
        StageInstance plan = stageStore.findActiveStage(event.taskId())
                .filter(s -> s.type() == StageType.PLAN_STAGE)
                .orElse(null);
        if (plan == null) {
            return;
        }
        boolean recorded = stageStore.findEventsByStage(plan.id()).stream()
                .anyMatch(e -> e.eventType() == StageEventType.PLAN_RECORDED);
        if (recorded) {
            return;
        }
        if (event.failed()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            String msg = turn.errorMessage();
            payload.put("error", msg == null || msg.isBlank()
                    ? "The planning turn failed before recording a plan." : msg);
            payload.put("failedAt", Instant.now().toString());
            stageStore.recordEvent(plan.id(), event.taskId(), StageEventType.PLAN_FAILED, payload);
            log.debug("surfaced planning failure on PlanStage {} for task {}: {}",
                    plan.id(), event.taskId(), msg);
            return;
        }
        // Succeeded without recording — nudge once (after the kickoff only).
        if ("plan-kickoff".equals(source)) {
            threadStore.findBrainThreadByTask(event.taskId()).ifPresent(brain -> {
                scheduler.enqueueTurn(brain,
                        "Your planning turn ended without recording a plan. Call "
                                + "record_plan(task_id='" + event.taskId() + "', plan={…}) now with a "
                                + "structured plan (status='finalized' when ready for review). Do not "
                                + "do any other work this turn.",
                        TurnInitiator.unattended("plan-followup"));
                log.debug("nudged brain {} to record a plan for task {}", brain.id(), event.taskId());
            });
        }
    }

    /**
     * Development kickoff turn ended. If the agent finished implementing but
     * never proposed a push (the task is still IMPLEMENTING — a ship/push
     * proposal would have fast-forwarded the phase to AWAITING_PUSH), nudge it
     * once to call {@code ship_task}, which parks a push + draft-PR proposal
     * for the user's approval. Without this the DevelopmentStage sits "running"
     * forever after producing a result. Mirrors the plan-stage record_plan
     * nudge: a one-shot follow-up turn under a distinct initiator source so it
     * can't loop. Nothing is pushed automatically — the proposal still gates on
     * the user's approval.
     */
    private void onDevTurnFinished(TaskTurnFinishedEvent event)
    {
        if (event.failed()) {
            return;
        }
        Task task = taskStore.findTaskById(event.taskId()).orElse(null);
        if (task == null || task.phase() != TaskPhase.IMPLEMENTING) {
            return;
        }
        boolean devOpen = stageStore.findActiveStage(event.taskId())
                .map(s -> s.type() == StageType.DEVELOPMENT_STAGE)
                .orElse(false);
        if (!devOpen) {
            return;
        }
        threadStore.findThreadById(task.threadId()).ifPresent(dev -> {
            String nudge = "Your implementation turn ended without proposing to publish. Start the "
                    + "PR workflow by calling record_pr_progress(phase=starting). Inspect git "
                    + "status, the complete base-to-head commit history, and the current change "
                    + "scope. First, if "
                    + "any work is uncommitted, stage and commit it now with a clear message per "
                    + "logical change — your commits become the PR's history verbatim, and "
                    + "ship_task bounces you if the worktree is dirty. Re-read the clean status "
                    + "and final committed base-to-head diff. Then, if the "
                    + "work is complete, call record_pr_progress(phase=creating-draft), record the "
                    + "finished title/body with record_pr_description, then call ship_task(...) "
                    + "with those exact values — do NOT call push by "
                    + "itself. When you call ship_task you MUST include a pr_title and a "
                    + "pr_body. ship_task parks ONE proposal that, on the "
                    + "user's approval, pushes the branch AND opens a draft PR in a single "
                    + "step, so the PR links and the stage advances together. It parks for "
                    + "approval and pushes nothing until the user approves. If the work "
                    + "isn't finished, keep going instead."
                    + PullRequestTemplate.find(task.agentCwd())
                            .map(tpl -> "\n\nThis repository provides a pull-request template. "
                                    + "Your pr_body MUST follow it EXACTLY: keep its headings, "
                                    + "checklists, and structure, fill in each section for this "
                                    + "change (delete only inapplicable optional sections), and "
                                    + "add no sections of your own. Template:\n\n" + tpl)
                            .orElse("\n\nThis repository has no pull-request template, so keep "
                                    + "the pr_body minimal and sized to the change: a small / nit "
                                    + "change gets ONE line saying what it does (e.g. \"Add a "
                                    + "requireNonNull check for currentPredicate in "
                                    + "DynamicFilterSnapshot\") — do NOT add Description / Changes "
                                    + "/ Validation headings, list every edit, or describe "
                                    + "testing. Only a substantial change warrants a short "
                                    + "summary paragraph.");
            scheduler.enqueueTaskTurn(dev, nudge, task.id(), TurnInitiator.unattended("ship-nudge"));
            log.debug("nudged dev thread {} to ship task {}", dev.id(), event.taskId());
        });
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
        return approve(taskId, approvedRevisionId, Actor.HUMAN, null);
    }

    private StageInstance approve(
            String taskId,
            String approvedRevisionId,
            Actor actor,
            String approvalSource)
    {
        StageInstance plan = stageStore.findActiveStage(taskId)
                .filter(stage -> stage.type() == StageType.PLAN_STAGE)
                .orElseThrow(() -> new IllegalStateException(
                        "no open PlanStage to approve for task " + taskId));

        Map<String, Object> payload = new LinkedHashMap<>();
        if (approvedRevisionId != null) {
            payload.put("approvedRevisionId", approvedRevisionId);
        }
        if (approvalSource != null) {
            payload.put("approvalSource", approvalSource);
        }
        payload.put("approvedAt", Instant.now().toString());
        stageStore.recordEvent(plan.id(), taskId, StageEventType.PLAN_APPROVED, payload);
        stageStore.closeStage(plan.id(), "plan_approved");
        // A no-op unless the local PR already exists (e.g. a replan after
        // dev started) — the usual first approval is backfilled onto the
        // timeline once PRServiceImpl.createForTask creates the row instead.
        prService.recordPlanApproved(taskId, plan.id().toString());

        // PLANNING ▶ IMPLEMENTING: StageLifecycle's reconcile opens the
        // DevelopmentStage off this transition; the PLAN_APPROVED event we
        // just wrote is what lets it past the plan guard.
        phaseMachine.transition(taskId, TaskPhase.IMPLEMENTING, "plan_approved", actor);

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
        return approveByStage(
                planStageId, Actor.HUMAN, null, "plan-approved", null, false);
    }

    /**
     * Applies the standing policy granted by enabling workspace issue intake.
     * The expected plan binds classification to the exact revision being
     * approved. This boundary independently verifies task provenance and the
     * high-confidence/low-risk/small policy; callers cannot opt around it.
     * The scheduler actor keeps the phase audit honest: this is local
     * automation, not a click masquerading as a human action.
     */
    @Transactional
    public ApproveResult approveByAutomation(UUID planStageId, JsonNode expectedPlan)
    {
        return approveByStage(
                planStageId,
                Actor.SCHEDULER,
                "workspace-issue-intake",
                "automation-plan-approved",
                requireNonNull(expectedPlan, "expectedPlan is null"),
                true);
    }

    private ApproveResult approveByStage(
            UUID planStageId,
            Actor actor,
            String approvalSource,
            String initiatorSource,
            JsonNode expectedPlan,
            boolean enforceIssueIntakePolicy)
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
        if (expectedPlan != null && !latest.equals(expectedPlan)) {
            throw status(409, "the plan changed after workspace issue intake classified it");
        }
        if (enforceIssueIntakePolicy) {
            assertIssueIntakePolicy(plan.taskId(), latest);
        }

        approve(plan.taskId(), latest.path("id").asText(null), actor, approvalSource);
        StageInstance dev = stageStore.findActiveStage(plan.taskId())
                .filter(stage -> stage.type() == StageType.DEVELOPMENT_STAGE)
                .orElseThrow(() -> status(500, "DevelopmentStage did not open on approval"));

        enqueueDevKickoff(plan.taskId(), latest, initiatorSource);
        return new ApproveResult(
                dev.id().toString(), "/tasks/" + plan.taskId() + "/stages/" + dev.id());
    }

    private void assertIssueIntakePolicy(String taskId, JsonNode plan)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> status(404, "no such task: " + taskId));
        if (!Task.ORIGIN_ISSUE_MONITOR.equals(task.origin())
                || !Task.TYPE_WORKSPACE_ISSUE_TRIAGE.equals(task.taskType())
                || task.linkedIssueNumber() == null) {
            throw status(400, "automated approval is restricted to issue-intake triage tasks");
        }
        JsonNode signals = plan.path("signals");
        if (!"high".equals(normalized(signals.path("confidence").asText()))
                || !"low".equals(normalized(signals.path("riskLevel").asText()))
                || !"small".equals(normalized(signals.path("estimatedComplexity").asText()))) {
            throw status(400, "issue-intake plan is not high-confidence, low-risk, and small");
        }
    }

    private static String normalized(String value)
    {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
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

    private void enqueueDevKickoff(String taskId, JsonNode plan, String initiatorSource)
    {
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        Thread dev = threadStore.findThreadById(task.threadId()).orElse(null);
        if (dev == null) {
            return;
        }
        scheduler.enqueueTaskTurn(
                dev, devKickoffPrompt(plan), task.id(), TurnInitiator.unattended(initiatorSource));
    }

    private static String devKickoffPrompt(JsonNode plan)
    {
        StringBuilder steps = new StringBuilder();
        // Mirror StageServiceImpl.buildPlanCard: steps arrive under intent.steps
        // or top-level steps, as {ordinal, action, …} objects OR plain strings,
        // with the text under any of a few keys. Read them the same robust way
        // so the kickoff never renders blank "0." lines for steps the plan card
        // showed fine.
        JsonNode stepNodes = plan.path("intent").path("steps");
        if (!stepNodes.isArray() || stepNodes.isEmpty()) {
            stepNodes = plan.path("steps");
        }
        if (stepNodes.isArray()) {
            int index = 0;
            for (JsonNode step : stepNodes) {
                index++;
                String action = step.isTextual() ? step.asText("")
                        : firstNonBlank(step.path("action").asText(""), step.path("step").asText(""),
                                step.path("description").asText(""), step.path("text").asText(""),
                                step.path("summary").asText(""));
                if (action.isBlank()) {
                    continue;
                }
                action = action.replaceFirst("^\\s*\\d+[.)]\\s+", "");
                steps.append("\n").append(step.path("ordinal").asInt(index)).append(". ").append(action);
            }
        }
        String intent = plan.path("intent").path("summary").asText("");
        String validation = plan.path("intent").path("validationStrategy").asText("");
        String push = plan.path("intent").path("pushStrategy").asText("await_approval");
        return """
                The plan for this task has been approved — implement it now.

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

    private static String firstNonBlank(String... candidates)
    {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
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
