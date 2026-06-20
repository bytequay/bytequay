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

import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.service.threads.TaskCreatedEvent;
import com.bytequay.app.service.threads.TaskPhaseTransitionedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>A Task opens its {@code DevelopmentStage} the moment it is created
 * (via {@link TaskCreatedEvent}); thereafter each transition closes the
 * active stage and opens the next whenever the phase crosses a stage
 * boundary. The creation hook is idempotent, and so is the
 * transition hook — should a task ever transition before its creation
 * event is seen, the first transition opens the stage just the same.
 * Because
 * the listener runs inside the transition's transaction, it must never
 * throw on an ordinary phase — an unmapped or cross-cutting phase
 * ({@code QUEUED}, {@code NEEDS_ATTENTION}, the post-push idle waits) is a
 * deliberate no-op that leaves the current stage in place.
 */
@Component
public class StageLifecycle
{
    private static final Logger log = LoggerFactory.getLogger(StageLifecycle.class);

    private final StageStore stageStore;
    private final StageBudgetService budgetService;

    public StageLifecycle(StageStore stageStore, StageBudgetService budgetService)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.budgetService = requireNonNull(budgetService, "budgetService is null");
    }

    @EventListener
    @Transactional
    public void onTaskCreated(TaskCreatedEvent event)
    {
        ensureDevelopmentStageOpen(event.taskId());
    }

    @EventListener
    @Transactional
    public void onPhaseTransition(TaskPhaseTransitionedEvent event)
    {
        reconcile(event.taskId(), event.to());
    }

    /**
     * Open a {@code DevelopmentStage} for a freshly-created Task. Idempotent:
     * a no-op once the Task has any stage at all, so a re-fired creation
     * event or a creation event arriving after the first phase transition
     * never produces a duplicate.
     */
    public void ensureDevelopmentStageOpen(String taskId)
    {
        if (!stageStore.findStagesByTask(taskId).isEmpty()) {
            return;
        }
        StageInstance opened = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        log.debug("opened {} stage {} at creation of task {}",
                StageType.DEVELOPMENT_STAGE, opened.id(), taskId);
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
            // when it legally belongs to the active stage. The latter keeps a
            // monitor stage stable across a phase that overlaps two stages —
            // e.g. AWAITING_UPDATE_PUSH belongs to both CI-fixing and
            // review-monitor; the active stage wins over forPhase's
            // declaration-order precedence so a review-comment push doesn't
            // flip the active stage to CI-fixing mid-loop.
            if (activeType == target.get() || activeType.allowedPhases().contains(toPhase)) {
                return;
            }
            stageStore.closeStage(active.get().id(), "phase_transition_to_" + toPhase.name());
        }
        // Phase-driven stages are top-level — only a callable review panel
        // carries a caller pointer, so callerStageId stays null here.
        StageInstance opened = stageStore.openStage(taskId, target.get(), null);
        // Seed a monitor stage's budget / review config at open time.
        budgetService.onStageOpened(opened);
        log.debug("opened {} stage {} for task {} on phase {}",
                target.get(), opened.id(), taskId, toPhase);
    }
}
