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

import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.service.threads.TaskCreatedEvent;
import com.bytequay.app.service.threads.TaskPhaseTransitionedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Keeps each Task's stage timeline in step with its phase machine. It
 * rides the existing {@link TaskPhaseTransitionedEvent} that
 * {@code TaskPhaseMachine} fires after every transition (and after the
 * audit row is written), so the stage hooks integrate into the phase
 * scheduler without any new code path bypassing it.
 *
 * <p>A Task opens its {@code PlanStage} the moment it is created (via
 * {@link TaskCreatedEvent}); the {@code DevelopmentStage} only opens once
 * the user approves the plan (a {@code PLAN_APPROVED} event on the
 * PlanStage). Thereafter each transition closes the active stage and opens
 * the next whenever the phase crosses a stage boundary. The creation hook
 * is idempotent, and so is the transition hook — should a task ever
 * transition before its creation event is seen, the first transition opens
 * the stage just the same. Because the listener runs inside the
 * transition's transaction, it must never throw on an ordinary phase — an
 * unmapped or cross-cutting phase ({@code QUEUED}, {@code NEEDS_ATTENTION},
 * the post-push idle waits) is a deliberate no-op that leaves the current
 * stage in place. The one intentional throw is the plan guard: opening the
 * DevelopmentStage without an approved plan is illegal, not a no-op.
 */
@Component
public class StageLifecycle
{
    private static final Logger log = LoggerFactory.getLogger(StageLifecycle.class);

    private final StageStore stageStore;
    private final StageBudgetService budgetService;
    private final ApplicationEventPublisher events;

    public StageLifecycle(
            StageStore stageStore,
            StageBudgetService budgetService,
            ApplicationEventPublisher events)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.budgetService = requireNonNull(budgetService, "budgetService is null");
        this.events = requireNonNull(events, "events is null");
    }

    @EventListener
    @Transactional
    public void onTaskCreated(TaskCreatedEvent event)
    {
        ensurePlanStageOpen(event.taskId());
    }

    @EventListener
    @Transactional
    public void onPhaseTransition(TaskPhaseTransitionedEvent event)
    {
        reconcile(event.taskId(), event.to());
    }

    /**
     * Open a {@code PlanStage} for a freshly-created Task. Idempotent: a
     * no-op once the Task has any stage at all, so a re-fired creation event
     * or a creation event arriving after the first phase transition never
     * produces a duplicate.
     */
    public void ensurePlanStageOpen(String taskId)
    {
        if (!stageStore.findStagesByTask(taskId).isEmpty()) {
            return;
        }
        StageInstance opened = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        log.debug("opened {} stage {} at creation of task {}",
                StageType.PLAN_STAGE, opened.id(), taskId);
    }

    /**
     * Ensure the active stage matches the stage {@code toPhase} belongs to,
     * closing the old one and opening the new one when they differ. A
     * cross-cutting / unmapped phase keeps whatever stage is active.
     */
    void reconcile(String taskId, TaskPhase toPhase)
    {
        Optional<StageType> target = StageType.forPhase(toPhase);
        if (target.isEmpty()) {
            // Cross-cutting (QUEUED / NEEDS_ATTENTION) or an unmapped idle
            // wait — attach to the current stage, don't churn the timeline.
            return;
        }

        Optional<StageInstance> active = stageStore.findActiveStage(taskId);
        if (active.isPresent()) {
            StageType activeType = active.get().type();
            // Stay put when the new phase is already the active stage's, or
            // when it legally belongs to the active stage.
            if (activeType == target.get() || activeType.allowedPhases().contains(toPhase)) {
                return;
            }
            stageStore.closeStage(active.get().id(), "phase_transition_to_" + toPhase.name());
            // Let the runtime reap the closed stage's per-stage CLI agent
            // (its subprocess + worktree lease) — the next stage spawns a
            // fresh one. Fires only for a real stage transition; the
            // synthetic CleanupStage below never had an agent.
            events.publishEvent(new StageClosedEvent(taskId, active.get().id().toString()));
        }
        // The DevelopmentStage is gated on an approved plan: it may only open
        // once the user has approved the PlanStage (a PLAN_APPROVED event).
        // This throw is intentional — entering IMPLEMENTING without an
        // approved plan is illegal, not a no-op (the approval flow records
        // PLAN_APPROVED before it transitions the phase, so the sanctioned
        // path passes the guard).
        if (target.get() == StageType.DEVELOPMENT_STAGE && !planApproved(taskId)) {
            throw new IllegalStateException(
                    "DevelopmentStage cannot open without an approved PlanStage on task " + taskId);
        }
        // Phase-driven stages are top-level — only a callable review panel
        // carries a caller pointer, so callerStageId stays null here.
        StageInstance opened = stageStore.openStage(taskId, target.get(), null);
        // The CleanupStage is a terminal marker, not live work — the worktree
        // reap happens in the completion path, not in an agent turn. Open and
        // immediately close it so a finished task shows "done", never a stage
        // stuck "running".
        if (target.get() == StageType.CLEANUP_STAGE) {
            stageStore.closeStage(opened.id(), "task_completed");
            log.debug("opened + closed CleanupStage {} for completed task {}", opened.id(), taskId);
            return;
        }
        // Seed a monitor stage's budget / review config at open time.
        budgetService.onStageOpened(opened);
        log.debug("opened {} stage {} for task {} on phase {}",
                target.get(), opened.id(), taskId, toPhase);
    }

    /**
     * True once the Task has an approved plan — i.e. some PlanStage recorded
     * a {@link StageEventType#PLAN_APPROVED} event. The approval is modelled
     * as an event (not a stage state) because {@code StageState} has no
     * APPROVED value; an approved PlanStage is a {@code CLOSED} one carrying
     * this event.
     */
    private boolean planApproved(String taskId)
    {
        return stageStore.findEventsByTask(taskId).stream()
                .anyMatch(e -> e.eventType() == StageEventType.PLAN_APPROVED);
    }
}
