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

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.checks.ValidationPassFinishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

/**
 * The deterministic spine for dev-task phase transitions — pattern is
 * the same shape as the review pass's phase handling: it owns the
 * transition guard, cap enforcement, persistence, audit, and event
 * publishing. The agent's turn-by-turn work runs <em>inside</em> a phase
 * (driven by the existing thread agents); this class only moves a task
 * between phases.
 *
 * <p>External events drive transitions through dedicated listeners that
 * land alongside their event sources (validation / review / CI / PR
 * sync) in later commits; each is a one-line call into
 * {@link #transition}.
 */
@Component
public class TaskPhaseMachine
{
    private static final Logger log = LoggerFactory.getLogger(TaskPhaseMachine.class);

    /** Consecutive auto-pushes per task before the lifecycle parks at
     *  NEEDS_ATTENTION. Guards against runaway autonomy. */
    static final int DEFAULT_AUTO_PUSH_CAP = 5;

    private static final String AUTO_PUSH_CAP_NOTICE =
            "{\"reason\":\"this has been retrying for a while\",\"cause\":\"auto_push_cap\"}";

    private final TaskStore taskStore;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;

    public TaskPhaseMachine(
            TaskStore taskStore,
            NotificationService notifications,
            ApplicationEventPublisher events)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.events = requireNonNull(events, "events is null");
    }

    /**
     * Move {@code taskId} to phase {@code to}. Throws on an illegal edge.
     * Enforces the consecutive-auto-push cap: an auto actor pushing past
     * the cap parks at NEEDS_ATTENTION instead of pushing again.
     */
    @Transactional
    public void transition(String taskId, TaskPhase to, String reason, Actor actor)
    {
        requireNonNull(to, "to is null");
        requireNonNull(actor, "actor is null");
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        TaskPhase from = task.phase();

        // Auto-push cap: a non-human push that would be the (cap+1)th in a
        // row parks the task for the human instead of pushing again.
        if (to == TaskPhase.PUSHED_AWAITING_CI
                && actor.isAuto()
                && taskStore.consecutiveAutoPushes(taskId) >= DEFAULT_AUTO_PUSH_CAP) {
            applyTransition(task, from, TaskPhase.NEEDS_ATTENTION, "auto_push_cap_hit", actor);
            notifyParked(task);
            return;
        }

        applyTransition(task, from, to, reason, actor);

        // Push bookkeeping: an auto push bumps the streak; a human push
        // resets it (the human vouched for this one).
        if (to == TaskPhase.PUSHED_AWAITING_CI) {
            taskStore.setConsecutiveAutoPushes(taskId,
                    actor.isAuto() ? taskStore.consecutiveAutoPushes(taskId) + 1 : 0);
            // An autonomous push also spends the active ci-fixing stage's
            // per-instance budget (separate from the task-level cap above).
            if (actor.isAuto()) {
                events.publishEvent(new TaskAutoPushEvent(taskId));
            }
        }
    }

    /**
     * Validation finishing drives VALIDATING ▶ INTERNAL_REVIEW (clean)
     * or VALIDATING ▶ NEEDS_ATTENTION (cap-hit failure). Guarded so a
     * stray event for a task that has since moved on is ignored rather
     * than throwing an illegal-transition error.
     */
    @EventListener
    public void onValidationFinished(ValidationPassFinishedEvent event)
    {
        Task task = taskStore.findTaskById(event.taskId()).orElse(null);
        if (task == null || task.phase() != TaskPhase.VALIDATING) {
            return;
        }
        if (event.passed()) {
            transition(event.taskId(), TaskPhase.INTERNAL_REVIEW, "validation_passed", Actor.AGENT);
        }
        else {
            transition(event.taskId(), TaskPhase.NEEDS_ATTENTION, "validation_failed", Actor.AGENT);
        }
    }

    /**
     * Fast-forward a task's phase to match authoritative <em>observed</em>
     * external reality (PR / CI / review state), bypassing the strict
     * forward graph that governs agent-driven steps. The graph assumes
     * the agent walks every phase via our tools; when it instead pushes /
     * opens a PR directly (raw git / the GitHub API), the observed PR
     * state is ground truth and the phase jumps straight to it. No-op if
     * already there or already terminal. Records {@link Actor#WEBHOOK}
     * and fires the same transition event as a normal step (so e.g. a
     * jump to COMPLETED still advances the queue).
     */
    @Transactional
    public void observe(String taskId, TaskPhase to, String reason)
    {
        requireNonNull(to, "to is null");
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        TaskPhase from = task.phase();
        if (from == to || from == TaskPhase.COMPLETED) {
            return;
        }
        taskStore.updatePhase(taskId, to);
        taskStore.appendPhaseEvent(taskId, from, to, Instant.now(), reason, Actor.WEBHOOK);
        events.publishEvent(new TaskPhaseTransitionedEvent(taskId, from, to, reason));
    }

    private void applyTransition(Task task, TaskPhase from, TaskPhase to, String reason, Actor actor)
    {
        if (!TaskPhaseTransitions.isLegal(from, to)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "illegal task phase transition " + from + " -> " + to + " (task " + task.id() + ")");
        }
        taskStore.updatePhase(task.id(), to);
        taskStore.appendPhaseEvent(task.id(), from, to, Instant.now(), reason, actor);
        events.publishEvent(new TaskPhaseTransitionedEvent(task.id(), from, to, reason));
    }

    private void notifyParked(Task task)
    {
        try {
            notifications.notifyNeedsAttention(task.threadId(), task.id(), AUTO_PUSH_CAP_NOTICE);
        }
        catch (RuntimeException e) {
            log.warn("needs-attention notify for task {} failed: {}", task.id(), e.getMessage());
        }
    }
}
