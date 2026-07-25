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
import com.bytequay.app.domain.TaskRecoveryRequest;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.checks.ValidationPassFinishedEvent;
import com.bytequay.app.service.localpr.LocalReviewClearedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

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

    /** Bounded in-process locks for check-then-act lifecycle boundaries. The
     *  backend is a single local sidecar, so a striped JVM lock is sufficient
     *  to serialize Local Review promotion against local-comment intake
     *  without holding one global lock across GitHub network calls. */
    private static final Object[] TASK_LOCKS = createTaskLocks(64);

    /** Consecutive auto-pushes per task before the lifecycle parks at
     *  NEEDS_ATTENTION. Guards against runaway autonomy. */
    static final int DEFAULT_AUTO_PUSH_CAP = 5;

    private static final String AUTO_PUSH_CAP_NOTICE =
            "{\"reason\":\"this has been retrying for a while\",\"cause\":\"auto_push_cap\"}";

    private final TaskStore taskStore;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;
    private final LocalCiFixExecutor localCiFix;
    private final ThreadTurnStore turnStore;

    public TaskPhaseMachine(
            TaskStore taskStore,
            NotificationService notifications,
            ApplicationEventPublisher events,
            LocalCiFixExecutor localCiFix,
            ThreadTurnStore turnStore)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.events = requireNonNull(events, "events is null");
        this.localCiFix = requireNonNull(localCiFix, "localCiFix is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
    }

    /** Run {@code action} exclusively against one task's lifecycle. Hash
     *  collisions only serialize unrelated tasks; they never weaken safety. */
    public static <T> T withTaskLock(String taskId, Supplier<T> action)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(action, "action is null");
        Object lock = TASK_LOCKS[Math.floorMod(taskId.hashCode(), TASK_LOCKS.length)];
        synchronized (lock) {
            return action.get();
        }
    }

    private static Object[] createTaskLocks(int count)
    {
        Object[] locks = new Object[count];
        Arrays.setAll(locks, ignored -> new Object());
        return locks;
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
        withTaskLock(taskId, () -> {
            transitionLocked(taskId, to, reason, actor);
            return null;
        });
    }

    /**
     * The one operational-park intent: checkpoint the current phase in
     * {@code recovery_phase} (with its typed context), park both axes at
     * NEEDS_ATTENTION together, and audit the status move. Idempotent —
     * a repeated park repairs a half-parked row without duplicating the
     * checkpoint or events. Runtime teardown follows after commit via
     * the stop reconciler's transition listener.
     */
    @Transactional
    public void parkOperational(String taskId, Actor actor, String reason)
    {
        requireNonNull(actor, "actor is null");
        withTaskLock(taskId, () -> {
            Task task = taskStore.findTaskById(taskId).orElse(null);
            if (task == null || task.status().isDone() || task.phase() == TaskPhase.COMPLETED) {
                return null;
            }
            if (task.phase() != TaskPhase.NEEDS_ATTENTION) {
                applyTransition(task, task.phase(), TaskPhase.NEEDS_ATTENTION, reason, actor);
            }
            else {
                parkStatus(task, actor, reason);
            }
            return null;
        });
    }

    /**
     * The only public exit request from NEEDS_ATTENTION: durably record
     * a validated recovery request while both axes stay parked. The stop
     * reconciler's barrier command consumes it once every pre-park turn,
     * agent, and validation executor is proven gone. Repeating the same
     * kind is idempotent; a different live request is rejected.
     */
    @Transactional
    public void requestRecovery(String taskId, String kind)
    {
        // Replan / external-saga / legacy kinds arrive with their owners;
        // until then only the plain checkpoint restore is a valid request.
        if (!TaskRecoveryRequest.KIND_NORMAL.equals(kind)
                && !TaskRecoveryRequest.KIND_CI_RETRY.equals(kind)) {
            throw new IllegalArgumentException("unsupported recovery kind: " + kind);
        }
        withTaskLock(taskId, () -> {
            Task task = taskStore.findTaskById(taskId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatusCode.valueOf(404), "no task: " + taskId));
            if (task.status().isDone()
                    || (task.phase() != TaskPhase.NEEDS_ATTENTION
                            && task.status() != TaskStatus.NEEDS_ATTENTION)) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "task " + taskId + " is not parked at NEEDS_ATTENTION");
            }
            Optional<TaskRecoveryRequest> existing = taskStore.recoveryRequest(taskId);
            if (existing.isPresent()) {
                if (existing.get().kind().equals(kind)) {
                    return null;
                }
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "task " + taskId + " already has a live "
                                + existing.get().kind() + " recovery request");
            }
            taskStore.recordRecoveryRequest(
                    taskId, UUID.randomUUID().toString(), kind, null, Instant.now());
            return null;
        });
    }

    /**
     * The recovery barrier's completion command — the only exit from
     * NEEDS_ATTENTION. Restores the checkpointed {@code recovery_phase}
     * (falling back to the caller's server-derived phase for legacy rows
     * with no usable checkpoint), derives the safe status from that
     * phase, and clears the checkpoint + request. The durable turn half
     * of the no-old-runtime proof is re-checked here.
     */
    @Transactional
    public Task completeRecovery(String taskId, Actor actor, String reason, TaskPhase fallbackPhase)
    {
        requireNonNull(actor, "actor is null");
        requireNonNull(fallbackPhase, "fallbackPhase is null");
        if (fallbackPhase == TaskPhase.NEEDS_ATTENTION || fallbackPhase == TaskPhase.COMPLETED) {
            throw new IllegalArgumentException("invalid recovery phase: " + fallbackPhase);
        }
        return withTaskLock(taskId, () -> {
            Task task = taskStore.findTaskById(taskId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatusCode.valueOf(404), "no task: " + taskId));
            if (task.status().isDone()
                    || (task.phase() != TaskPhase.NEEDS_ATTENTION
                            && task.status() != TaskStatus.NEEDS_ATTENTION)) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "task " + taskId + " is not parked at NEEDS_ATTENTION");
            }
            if (!turnStore.listTurnsByExactTaskIdAndStatus(taskId, ThreadTurnStatus.QUEUED, 1).isEmpty()
                    || !turnStore.listTurnsByExactTaskIdAndStatus(taskId, ThreadTurnStatus.RUNNING, 1).isEmpty()) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "task " + taskId + " still has live pre-park turns");
            }
            TaskPhase restored = task.phase() != TaskPhase.NEEDS_ATTENTION
                    ? task.phase()
                    : taskStore.recoveryPhase(taskId)
                            .filter(phase -> phase != TaskPhase.NEEDS_ATTENTION
                                    && phase != TaskPhase.COMPLETED)
                            .orElse(fallbackPhase);
            TaskStatus to = resumedStatus(restored);
            Instant now = Instant.now();
            taskStore.clearRecoveryState(taskId);
            taskStore.updateRuntimeFailure(taskId, null, null);
            if (task.phase() != restored) {
                // Deliberate legality escape, like the pre-machine recover():
                // the graph has no generic NEEDS_ATTENTION exit; the barrier
                // command is the audited way back to the checkpointed phase.
                taskStore.updatePhase(taskId, restored);
                taskStore.appendPhaseEvent(taskId, task.phase(), restored, now, reason, actor);
                events.publishEvent(new TaskPhaseTransitionedEvent(
                        taskId, task.phase(), restored, reason));
            }
            if (task.status() != to) {
                taskStore.updateStatusIf(taskId, task.status(), to);
                taskStore.appendStatusEvent(taskId, task.status(), to, actor, reason, now);
            }
            return task.withPhase(restored).withStatus(to).withEndedAt(null).withErrorMessage(null);
        });
    }

    /**
     * The only generic exit from ERRORED. Requires the exact failed
     * (or cancelled) turn to still be the task's current liveness
     * authority and an executable phase, then atomically moves
     * ERRORED → IDLE with its audit and clears the copied failure
     * fields. Returns the failed turn so the caller can insert its
     * keyed replacement + move the pointer in the same command —
     * an insert failure rolls all of it back, leaving ERRORED.
     */
    @Transactional
    public ThreadTurn retryErrored(String taskId, String failedTurnId)
    {
        requireNonNull(failedTurnId, "failedTurnId is null");
        return withTaskLock(taskId, () -> {
            Task task = taskStore.findTaskById(taskId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatusCode.valueOf(404), "no task: " + taskId));
            if (task.status() != TaskStatus.ERRORED) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "task " + taskId + " is not errored");
            }
            if (task.phase() == TaskPhase.COMPLETED || task.phase() == TaskPhase.NEEDS_ATTENTION) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "task " + taskId + " cannot retry from phase " + task.phase());
            }
            if (!failedTurnId.equals(taskStore.currentLivenessTurnId(taskId).orElse(null))) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "turn " + failedTurnId + " is no longer task " + taskId
                                + "'s current failure");
            }
            ThreadTurn failed = turnStore.findTurnById(failedTurnId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatusCode.valueOf(409), "no turn: " + failedTurnId));
            if (failed.status() != ThreadTurnStatus.FAILED
                    && failed.status() != ThreadTurnStatus.CANCELLED) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "turn " + failedTurnId + " is not a terminal failure");
            }
            taskStore.updateStatusIf(taskId, TaskStatus.ERRORED, TaskStatus.IDLE);
            taskStore.appendStatusEvent(
                    taskId, TaskStatus.ERRORED, TaskStatus.IDLE,
                    Actor.HUMAN, "task_retry", Instant.now());
            taskStore.updateRuntimeFailure(taskId, null, null);
            return failed;
        });
    }

    /**
     * IDLE → ARCHIVED, callable only from the idle archiver's guarded
     * scan. Re-verifies under the lock that the task is still IDLE with
     * no queued/running liveness work and no pending resume/recovery
     * request; the archiver owns the idle-threshold and
     * coordinator/validation guards.
     */
    @Transactional
    public void archiveIdle(String taskId)
    {
        withTaskLock(taskId, () -> {
            Task task = taskStore.findTaskById(taskId).orElse(null);
            if (task == null || task.status() != TaskStatus.IDLE) {
                return null;
            }
            if (!turnStore.listTurnsByExactTaskIdAndStatus(taskId, ThreadTurnStatus.QUEUED, 1).isEmpty()
                    || !turnStore.listTurnsByExactTaskIdAndStatus(taskId, ThreadTurnStatus.RUNNING, 1).isEmpty()
                    || taskStore.resumeRequestedAt(taskId).isPresent()
                    || taskStore.recoveryRequest(taskId).isPresent()) {
                return null;
            }
            taskStore.updateStatusIf(taskId, TaskStatus.IDLE, TaskStatus.ARCHIVED);
            taskStore.appendStatusEvent(
                    taskId, TaskStatus.IDLE, TaskStatus.ARCHIVED,
                    Actor.SCHEDULER, "idle_archived", Instant.now());
            return null;
        });
    }

    /** The separate ARCHIVED → IDLE intent. It cannot touch PAUSED, and
     *  an anomalous live turn must finish first. */
    @Transactional
    public Task reviveArchived(String taskId)
    {
        return withTaskLock(taskId, () -> {
            Task task = taskStore.findTaskById(taskId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatusCode.valueOf(404), "no task: " + taskId));
            if (task.status() != TaskStatus.ARCHIVED) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "task " + taskId + " is not archived");
            }
            if (!turnStore.listTurnsByExactTaskIdAndStatus(taskId, ThreadTurnStatus.QUEUED, 1).isEmpty()
                    || !turnStore.listTurnsByExactTaskIdAndStatus(taskId, ThreadTurnStatus.RUNNING, 1).isEmpty()) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "task " + taskId + " still has live turns");
            }
            taskStore.updateStatusIf(taskId, TaskStatus.ARCHIVED, TaskStatus.IDLE);
            taskStore.appendStatusEvent(
                    taskId, TaskStatus.ARCHIVED, TaskStatus.IDLE,
                    Actor.HUMAN, "user_resumed_task", Instant.now());
            taskStore.updateRuntimeFailure(taskId, null, null);
            return task.withStatus(TaskStatus.IDLE).withEndedAt(null).withErrorMessage(null);
        });
    }

    /** Park the status axis with its audit row; no-op when already
     *  parked. Never touches the phase. */
    private void parkStatus(Task task, Actor actor, String reason)
    {
        if (task.status() == TaskStatus.NEEDS_ATTENTION) {
            return;
        }
        taskStore.updateStatusIf(task.id(), task.status(), TaskStatus.NEEDS_ATTENTION);
        taskStore.appendStatusEvent(
                task.id(), task.status(), TaskStatus.NEEDS_ATTENTION, actor, reason, Instant.now());
    }

    /** Reasons are snake_case identifiers, so plain interpolation stays
     *  valid JSON. */
    private static String recoveryContext(String reason)
    {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private void transitionLocked(String taskId, TaskPhase to, String reason, Actor actor)
    {
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
     * Validation finishing drives VALIDATING ▶ INTERNAL_REVIEW (clean). A
     * failed check first tries the bounded local-CI fix loop; only when no
     * fix turn can be queued (budget spent or nothing to run on) does it park
     * at NEEDS_ATTENTION. Guarded so a stray event for a task that has since
     * moved on is ignored rather than throwing an illegal-transition error.
     */
    @EventListener
    public void onValidationFinished(ValidationPassFinishedEvent event)
    {
        Task task = taskStore.findTaskById(event.taskId()).orElse(null);
        if (task == null || task.phase() != TaskPhase.VALIDATING) {
            return;
        }
        if (event.passed()) {
            localCiFix.closeIfGreen(event.taskId());
            transition(event.taskId(), TaskPhase.INTERNAL_REVIEW, "validation_passed", Actor.AGENT);
        }
        else if (!localCiFix.tryFix(task, event.failures())) {
            // No fix turn was queued (budget spent or nothing to run on) —
            // hand the failing checks back to the human.
            transition(event.taskId(), TaskPhase.NEEDS_ATTENTION, "validation_failed", Actor.AGENT);
        }
    }

    /** Brain review finished (approved or escalated after its bounded
     *  budget), so the private PR is now local-open for the human. This is
     *  the only normal INTERNAL_REVIEW -> AWAITING_PUSH transition. */
    @EventListener
    public void onLocalReviewCleared(LocalReviewClearedEvent event)
    {
        Task task = taskStore.findTaskById(event.taskId()).orElse(null);
        if (task == null || task.phase() != TaskPhase.INTERNAL_REVIEW) {
            return;
        }
        transition(event.taskId(), TaskPhase.AWAITING_PUSH, "local_review_opened", Actor.AGENT);
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
        withTaskLock(taskId, () -> {
            observeLocked(taskId, to, reason);
            return null;
        });
    }

    private void observeLocked(String taskId, TaskPhase to, String reason)
    {
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        TaskPhase from = task.phase();
        if (from == TaskPhase.COMPLETED) {
            return;
        }

        // NEEDS_ATTENTION is one durable stop across both task axes. An
        // observed park must stamp the runtime status too, and a repeated
        // observation repairs legacy/partially-written rows whose phase was
        // already parked without appending a duplicate phase event.
        if (to == TaskPhase.NEEDS_ATTENTION) {
            if (from != TaskPhase.NEEDS_ATTENTION && taskStore.recoveryPhase(taskId).isEmpty()) {
                taskStore.checkpointRecovery(taskId, from, recoveryContext(reason));
            }
            parkStatus(task, Actor.WEBHOOK, reason);
            if (from == TaskPhase.NEEDS_ATTENTION) {
                return;
            }
        }
        else if (from == to || (to != TaskPhase.COMPLETED
                && (from == TaskPhase.NEEDS_ATTENTION
                        || task.status() == TaskStatus.NEEDS_ATTENTION))) {
            return;
        }
        taskStore.updatePhase(taskId, to);
        taskStore.appendPhaseEvent(taskId, from, to, Instant.now(), reason, Actor.WEBHOOK);
        events.publishEvent(new TaskPhaseTransitionedEvent(taskId, from, to, reason));
    }

    /**
     * The one durable terminal command: write the terminal status (with
     * its status-audit row) and drive the phase to COMPLETED in a single
     * locked step. Runtime teardown — interrupts, agent eviction,
     * worktree reaping — belongs to the caller and runs only after this
     * durable intent, each step idempotent and reconciled.
     */
    @Transactional
    public void finishTerminal(String taskId, TaskStatus terminalStatus, Actor actor, String reason)
    {
        if (terminalStatus != TaskStatus.COMPLETED
                && terminalStatus != TaskStatus.REMOTE_CLOSED
                && terminalStatus != TaskStatus.CANCELED) {
            throw new IllegalArgumentException("not a terminal status: " + terminalStatus);
        }
        requireNonNull(actor, "actor is null");
        withTaskLock(taskId, () -> {
            Task task = taskStore.findTaskById(taskId).orElse(null);
            if (task == null) {
                return null;
            }
            if (task.status() != terminalStatus) {
                Instant now = Instant.now();
                switch (terminalStatus) {
                    case COMPLETED -> taskStore.completeTask(taskId, now);
                    case REMOTE_CLOSED -> taskStore.remoteCloseTask(taskId, now);
                    case CANCELED -> taskStore.cancelTask(taskId, now);
                    default -> throw new IllegalStateException("unreachable");
                }
                taskStore.appendStatusEvent(taskId, task.status(), terminalStatus, actor, reason, now);
            }
            if (task.phase() != TaskPhase.COMPLETED) {
                applyTransition(task, task.phase(), TaskPhase.COMPLETED, reason, actor);
            }
            return null;
        });
    }

    /**
     * The one durable pause command: checkpoint the pre-pause status in
     * {@code paused_status}, clear the stale subprocess pid, and move to
     * PAUSED with its status-audit row — phase holds. Idempotent when
     * already PAUSED. Runtime teardown belongs to the caller, after
     * commit, behind its identity token.
     */
    @Transactional
    public Task pause(String taskId, Actor actor, String reason)
    {
        requireNonNull(actor, "actor is null");
        return withTaskLock(taskId, () -> {
            Task task = taskStore.findTaskById(taskId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatusCode.valueOf(404), "no task: " + taskId));
            if (task.status() == TaskStatus.PAUSED) {
                return task;
            }
            if (!pausable(task.status())) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "task " + taskId + " cannot be paused from status " + task.status());
            }
            taskStore.checkpointPause(taskId, task.status());
            taskStore.clearProcessPid(taskId);
            taskStore.updateStatusIf(taskId, task.status(), TaskStatus.PAUSED);
            taskStore.appendStatusEvent(
                    taskId, task.status(), TaskStatus.PAUSED, actor, reason, Instant.now());
            return task.withStatus(TaskStatus.PAUSED).withProcessPid(null);
        });
    }

    /** A task can be paused only while it's live, non-terminal work — not
     *  once parked (NEEDS_ATTENTION), failed (ERRORED), dormant
     *  (ARCHIVED), or done. */
    private static boolean pausable(TaskStatus status)
    {
        return switch (status) {
            case PENDING, RUNNING, IDLE, AWAITING_REVIEW, IN_REVIEW -> true;
            default -> false;
        };
    }

    /**
     * Durably record that a human asked to resume while the task stays
     * PAUSED. The pre-pause runtime keeps being torn down; only
     * {@link #completeResume} — invoked once that teardown is proven —
     * leaves PAUSED. Idempotent.
     */
    @Transactional
    public void requestResume(String taskId)
    {
        withTaskLock(taskId, () -> {
            Task task = taskStore.findTaskById(taskId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatusCode.valueOf(404), "no task: " + taskId));
            if (task.status() != TaskStatus.PAUSED) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "task " + taskId + " is not paused");
            }
            taskStore.requestResume(taskId, Instant.now());
            return null;
        });
    }

    /**
     * The only normal edge out of PAUSED. The caller must first prove the
     * pre-pause runtime is gone (no cached agent, no live validation
     * executor); the durable turn half of that proof is re-checked here.
     * The safe post-resume status derives from the phase — never RUNNING,
     * and never a blind restore of the checkpoint.
     */
    @Transactional
    public Task completeResume(String taskId, Actor actor, String reason)
    {
        requireNonNull(actor, "actor is null");
        return withTaskLock(taskId, () -> {
            Task task = taskStore.findTaskById(taskId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatusCode.valueOf(404), "no task: " + taskId));
            if (task.status() != TaskStatus.PAUSED) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "task " + taskId + " is not paused");
            }
            if (!turnStore.listTurnsByExactTaskIdAndStatus(taskId, ThreadTurnStatus.QUEUED, 1).isEmpty()
                    || !turnStore.listTurnsByExactTaskIdAndStatus(taskId, ThreadTurnStatus.RUNNING, 1).isEmpty()) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "task " + taskId + " still has live pre-pause turns");
            }
            TaskStatus to = resumedStatus(task.phase());
            taskStore.clearPauseCheckpoint(taskId);
            taskStore.updateRuntimeFailure(taskId, null, null);
            taskStore.updateStatusIf(taskId, TaskStatus.PAUSED, to);
            taskStore.appendStatusEvent(taskId, TaskStatus.PAUSED, to, actor, reason, Instant.now());
            return task.withStatus(to).withEndedAt(null).withErrorMessage(null);
        });
    }

    /** The safe status a resumed task re-enters, derived from its held
     *  phase: local gate → AWAITING_REVIEW, remote spine → IN_REVIEW,
     *  executable local work → IDLE (the projection promotes RUNNING only
     *  when a replacement turn actually starts). */
    static TaskStatus resumedStatus(TaskPhase phase)
    {
        return switch (phase) {
            case AWAITING_PUSH -> TaskStatus.AWAITING_REVIEW;
            case PUSHED_AWAITING_CI, AWAITING_READY, AWAITING_REMOTE_REVIEW -> TaskStatus.IN_REVIEW;
            default -> TaskStatus.IDLE;
        };
    }

    private void applyTransition(Task task, TaskPhase from, TaskPhase to, String reason, Actor actor)
    {
        if (!TaskPhaseTransitions.isLegal(from, to)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "illegal task phase transition " + from + " -> " + to + " (task " + task.id() + ")");
        }
        if (to == TaskPhase.NEEDS_ATTENTION) {
            // First park wins the checkpoint; a re-park while parked must
            // not overwrite the phase recovery will restore.
            if (from != TaskPhase.NEEDS_ATTENTION && taskStore.recoveryPhase(task.id()).isEmpty()) {
                taskStore.checkpointRecovery(task.id(), from, recoveryContext(reason));
            }
            parkStatus(task, actor, reason);
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
