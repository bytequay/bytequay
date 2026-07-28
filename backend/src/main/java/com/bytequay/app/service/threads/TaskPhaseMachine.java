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
import com.bytequay.app.domain.ValidationClaim;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.checks.ValidationExecutorRegistry;
import com.bytequay.app.service.checks.ValidationPassFinishedEvent;
import com.bytequay.app.service.checks.ValidationRecheckRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
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
    public static final int DEFAULT_AUTO_PUSH_CAP = 5;

    private static final String AUTO_PUSH_CAP_NOTICE =
            "{\"reason\":\"this has been retrying for a while\",\"cause\":\"auto_push_cap\"}";

    private final TaskStore taskStore;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;
    private final LocalCiFixExecutor localCiFix;
    private final ThreadTurnStore turnStore;
    private final ValidationPassStore validationStore;
    private final ValidationExecutorRegistry validationExecutors;
    private final CodeFingerprints fingerprints;
    private final TaskCommandExecutor commands;

    public TaskPhaseMachine(
            TaskStore taskStore,
            NotificationService notifications,
            ApplicationEventPublisher events,
            LocalCiFixExecutor localCiFix,
            ThreadTurnStore turnStore,
            ValidationPassStore validationStore,
            ValidationExecutorRegistry validationExecutors,
            CodeFingerprints fingerprints,
            TaskCommandExecutor commands)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.events = requireNonNull(events, "events is null");
        this.localCiFix = requireNonNull(localCiFix, "localCiFix is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.validationStore = requireNonNull(validationStore, "validationStore is null");
        this.validationExecutors = requireNonNull(validationExecutors, "validationExecutors is null");
        this.fingerprints = requireNonNull(fingerprints, "fingerprints is null");
        this.commands = requireNonNull(commands, "commands is null");
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
    public void transition(String taskId, TaskPhase to, String reason, Actor actor)
    {
        requireNonNull(to, "to is null");
        requireNonNull(actor, "actor is null");
        commands.executeVoid(taskId, () -> transitionInCommand(taskId, to, reason, actor));
    }

    /** Same-transaction projection for another intent already running in
     *  this task's command. */
    public void transitionInCommand(String taskId, TaskPhase to, String reason, Actor actor)
    {
        requireNonNull(to, "to is null");
        requireNonNull(actor, "actor is null");
        TaskCommandExecutor.requireCurrent(taskId);
        transitionLocked(taskId, to, reason, actor);
    }

    /** Atomically spend an automatic local-ship authorization before its
     * first external effect. Retrying the retained token never calls this
     * again, so both autonomy counters are charged exactly once. */
    public boolean spendLocalShipAuthorizationInCommand(String taskId, Actor actor)
    {
        requireNonNull(actor, "actor is null");
        TaskCommandExecutor.requireCurrent(taskId);
        legacyTask(taskId);
        if (!actor.isAuto()) {
            return true;
        }
        if (taskStore.consecutiveAutoPushes(taskId) >= DEFAULT_AUTO_PUSH_CAP) {
            parkOperationalInCommand(taskId, actor, "auto_push_cap_hit");
            return false;
        }
        taskStore.setConsecutiveAutoPushes(
                taskId, taskStore.consecutiveAutoPushes(taskId) + 1);
        events.publishEvent(new TaskAutoPushEvent(taskId));
        return true;
    }

    /**
     * Consume an already-authorized first-push result. Authorization has
     * already spent the automatic-push budget, so this command only moves
     * both task axes and their audit rows. A successful explicit human push
     * resets the consecutive automatic-push streak.
     */
    public void finalizeLocalShipInCommand(String taskId, Actor actor, String reason)
    {
        requireNonNull(actor, "actor is null");
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = legacyTask(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        if (task.phase() == TaskPhase.PUSHED_AWAITING_CI
                && task.status() == TaskStatus.IN_REVIEW) {
            return;
        }
        if (task.phase() != TaskPhase.AWAITING_PUSH
                || task.status() != TaskStatus.IDLE
                        && task.status() != TaskStatus.AWAITING_REVIEW) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " is not at the Local Review push gate");
        }
        Instant now = Instant.now();
        if (!taskStore.updateStatusIf(taskId, task.status(), TaskStatus.IN_REVIEW)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " changed while finalizing Push");
        }
        taskStore.appendStatusEvent(
                taskId, task.status(), TaskStatus.IN_REVIEW, actor, reason, now);
        applyTransition(task, task.phase(), TaskPhase.PUSHED_AWAITING_CI, reason, actor);
        if (actor == Actor.HUMAN) {
            taskStore.setConsecutiveAutoPushes(taskId, 0);
        }
    }

    /** The worktree changed after Brain approved the local PR. Return the
     * task to fingerprinted validation; its normal green path opens a fresh
     * Brain round before Push can be authorized again. */
    public void invalidateLocalShipInCommand(String taskId, Actor actor, String reason)
    {
        requireNonNull(actor, "actor is null");
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = legacyTask(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        if (task.phase() == TaskPhase.VALIDATING) {
            return;
        }
        if (task.phase() != TaskPhase.AWAITING_PUSH
                || task.status() != TaskStatus.IDLE
                        && task.status() != TaskStatus.AWAITING_REVIEW) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " is not at an invalidatable local Push gate");
        }
        if (task.status() == TaskStatus.AWAITING_REVIEW) {
            if (!taskStore.updateStatusIf(
                    taskId, TaskStatus.AWAITING_REVIEW, TaskStatus.IDLE)) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "task " + taskId + " changed while invalidating Push");
            }
            taskStore.appendStatusEvent(
                    taskId, TaskStatus.AWAITING_REVIEW, TaskStatus.IDLE,
                    actor, reason, Instant.now());
        }
        applyTransition(task, TaskPhase.AWAITING_PUSH, TaskPhase.VALIDATING, reason, actor);
    }

    /** Park one runnable task at the local publish/review gate. The
     * notification row is written by the caller in the same task command. */
    public Task parkForLocalReviewInCommand(String taskId, Actor actor, String reason)
    {
        requireNonNull(actor, "actor is null");
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = legacyTask(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        if (task.status() == TaskStatus.AWAITING_REVIEW) {
            return task;
        }
        if (!acceptsForwardResult(task.status())) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " cannot enter Local Review from " + task.status());
        }
        if (!taskStore.updateStatusIf(taskId, task.status(), TaskStatus.AWAITING_REVIEW)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " changed while entering Local Review");
        }
        taskStore.appendStatusEvent(
                taskId, task.status(), TaskStatus.AWAITING_REVIEW,
                actor, reason, Instant.now());
        return task.withStatus(TaskStatus.AWAITING_REVIEW);
    }

    /** A declined local proposal returns to editable local work. */
    public Task resumeFromLocalReviewInCommand(String taskId, Actor actor, String reason)
    {
        requireNonNull(actor, "actor is null");
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = legacyTask(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        if (task.status() != TaskStatus.AWAITING_REVIEW) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " is not at Local Review");
        }
        if (!taskStore.updateStatusIf(taskId, TaskStatus.AWAITING_REVIEW, TaskStatus.IDLE)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " changed while leaving Local Review");
        }
        taskStore.clearProcessPid(taskId);
        taskStore.updateRuntimeFailure(taskId, null, null);
        taskStore.appendStatusEvent(
                taskId, TaskStatus.AWAITING_REVIEW, TaskStatus.IDLE,
                actor, reason, Instant.now());
        return task.withStatus(TaskStatus.IDLE)
                .withProcessPid(null)
                .withEndedAt(null)
                .withErrorMessage(null);
    }

    /** Resolving a local gate on an already-linked task restores the
     * remote-review liveness state; the remote lifecycle phase holds. */
    public Task markRemoteInReviewInCommand(String taskId, Actor actor, String reason)
    {
        requireNonNull(actor, "actor is null");
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = legacyTask(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        if (task.status() == TaskStatus.IN_REVIEW) {
            return task;
        }
        if (task.status() != TaskStatus.AWAITING_REVIEW
                || task.linkedPrNumber() == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " has no linked Local Review gate");
        }
        if (!taskStore.updateStatusIf(taskId, TaskStatus.AWAITING_REVIEW, TaskStatus.IN_REVIEW)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " changed while restoring remote review");
        }
        taskStore.appendStatusEvent(
                taskId, TaskStatus.AWAITING_REVIEW, TaskStatus.IN_REVIEW,
                actor, reason, Instant.now());
        return task.withStatus(TaskStatus.IN_REVIEW);
    }

    /**
     * The one operational-park intent: checkpoint the current phase in
     * {@code recovery_phase} (with its typed context), park both axes at
     * NEEDS_ATTENTION together, and audit the status move. Idempotent —
     * a repeated park repairs a half-parked row without duplicating the
     * checkpoint or events. Runtime teardown follows after commit via
     * the stop reconciler's transition listener.
     */
    public void parkOperational(String taskId, Actor actor, String reason)
    {
        requireNonNull(actor, "actor is null");
        commands.executeVoid(taskId, () -> parkOperationalInCommand(taskId, actor, reason));
    }

    public void parkOperationalInCommand(String taskId, Actor actor, String reason)
    {
        requireNonNull(actor, "actor is null");
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = legacyTask(taskId).orElse(null);
        if (task == null || task.status().isDone() || task.phase() == TaskPhase.COMPLETED) {
            return;
        }
        if (task.phase() != TaskPhase.NEEDS_ATTENTION) {
            applyTransition(task, task.phase(), TaskPhase.NEEDS_ATTENTION, reason, actor);
        }
        else {
            parkStatus(task, actor, reason);
        }
    }

    /**
     * The only public exit request from NEEDS_ATTENTION: durably record
     * a validated recovery request while both axes stay parked. The stop
     * reconciler's barrier command consumes it once every pre-park turn,
     * agent, and validation executor is proven gone. Repeating the same
     * kind is idempotent; a different live request is rejected.
     */
    public void requestRecovery(String taskId, String kind)
    {
        if (!supportedRecoveryKind(kind)) {
            throw new IllegalArgumentException("unsupported recovery kind: " + kind);
        }
        commands.executeVoid(taskId, () -> requestRecoveryInCommand(taskId, kind));
    }

    public void requestRecoveryInCommand(String taskId, String kind)
    {
        requestRecoveryInCommand(taskId, kind, null);
    }

    /** Payload-bearing sibling for recovery kinds whose exact durable cursor
     * is part of the human intent. */
    public void requestRecoveryInCommand(String taskId, String kind, String payloadJson)
    {
        if (!supportedRecoveryKind(kind)) {
            throw new IllegalArgumentException("unsupported recovery kind: " + kind);
        }
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = legacyTask(taskId)
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
            if (existing.get().kind().equals(kind)
                    && Objects.equals(existing.get().payloadJson(), payloadJson)) {
                return;
            }
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " already has a live "
                            + existing.get().kind() + " recovery request");
        }
        taskStore.recordRecoveryRequest(
                taskId, UUID.randomUUID().toString(), kind, payloadJson, Instant.now());
    }

    /** Reject a request whose immutable recovery evidence no longer
     * validates. This deliberately keeps both task axes parked and retains
     * the pre-park phase, while clearing only the exact stale request so the
     * user can submit a newly valid one. */
    public void rejectRecoveryRequest(
            String taskId, String requestId, String reason)
    {
        commands.executeVoid(taskId,
                () -> rejectRecoveryRequestInCommand(taskId, requestId, reason));
    }

    public void rejectRecoveryRequestInCommand(
            String taskId, String requestId, String reason)
    {
        requireNonNull(requestId, "requestId is null");
        requireNonNull(reason, "reason is null");
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = legacyTask(taskId).orElse(null);
        if (task == null
                || task.phase() != TaskPhase.NEEDS_ATTENTION
                || task.status() != TaskStatus.NEEDS_ATTENTION) {
            return;
        }
        TaskRecoveryRequest request = taskStore.recoveryRequest(taskId)
                .filter(candidate -> requestId.equals(candidate.id()))
                .orElse(null);
        if (request == null) {
            return;
        }
        String context = "{\"reason\":\"" + reason
                + "\",\"rejectedRequestId\":\"" + request.id()
                + "\",\"rejectedRequestKind\":\"" + request.kind() + "\"}";
        if (!taskStore.clearRecoveryRequest(taskId, requestId, context)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " recovery request changed while rejecting it");
        }
    }

    /**
     * The recovery barrier's completion command — the only exit from
     * NEEDS_ATTENTION. Restores the checkpointed {@code recovery_phase}
     * (falling back to the caller's server-derived phase for legacy rows
     * with no usable checkpoint), derives the safe status from that
     * phase, and clears the checkpoint + request. The durable turn half
     * of the no-old-runtime proof is re-checked here.
     */
    public Task completeRecovery(String taskId, Actor actor, String reason, TaskPhase fallbackPhase)
    {
        requireNonNull(actor, "actor is null");
        requireNonNull(fallbackPhase, "fallbackPhase is null");
        if (fallbackPhase == TaskPhase.NEEDS_ATTENTION || fallbackPhase == TaskPhase.COMPLETED) {
            throw new IllegalArgumentException("invalid recovery phase: " + fallbackPhase);
        }
        return commands.execute(taskId,
                () -> completeRecoveryInCommand(taskId, actor, reason, fallbackPhase));
    }

    public Task completeRecoveryInCommand(
            String taskId, Actor actor, String reason, TaskPhase fallbackPhase)
    {
        return completeRecoveryInCommand(
                taskId, actor, reason, fallbackPhase, TaskRecoveryRequest.KIND_NORMAL);
    }

    /** Intent-specific completion after TaskPushSaga has validated and
     * re-armed the exact cursor in this same task command. */
    public Task completeExternalSagaRecoveryInCommand(
            String taskId, Actor actor, String reason, TaskPhase fallbackPhase)
    {
        return completeRecoveryInCommand(
                taskId, actor, reason, fallbackPhase,
                TaskRecoveryRequest.KIND_EXTERNAL_SAGA);
    }

    private Task completeRecoveryInCommand(
            String taskId,
            Actor actor,
            String reason,
            TaskPhase fallbackPhase,
            String expectedRecoveryKind)
    {
        requireNonNull(actor, "actor is null");
        requireNonNull(fallbackPhase, "fallbackPhase is null");
        TaskCommandExecutor.requireCurrent(taskId);
        if (fallbackPhase == TaskPhase.NEEDS_ATTENTION || fallbackPhase == TaskPhase.COMPLETED) {
            throw new IllegalArgumentException("invalid recovery phase: " + fallbackPhase);
        }
        Task task = legacyTask(taskId)
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
        assertValidationStopped(taskId);
        Optional<TaskRecoveryRequest> request = taskStore.recoveryRequest(taskId);
        if (request.filter(value -> TaskRecoveryRequest.KIND_REPLAN.equals(value.kind()))
                .isPresent()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " requires replan recovery completion");
        }
        if (request.filter(value -> TaskRecoveryRequest.KIND_EXTERNAL_SAGA.equals(value.kind()))
                .isPresent()
                && !TaskRecoveryRequest.KIND_EXTERNAL_SAGA.equals(expectedRecoveryKind)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " requires external-saga recovery completion");
        }
        if (TaskRecoveryRequest.KIND_EXTERNAL_SAGA.equals(expectedRecoveryKind)
                && request.filter(value -> expectedRecoveryKind.equals(value.kind())).isEmpty()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " has no external-saga recovery request");
        }
        TaskPhase restored = task.phase() != TaskPhase.NEEDS_ATTENTION
                ? task.phase()
                : taskStore.recoveryPhase(taskId)
                        .filter(phase -> phase != TaskPhase.NEEDS_ATTENTION
                                && phase != TaskPhase.COMPLETED)
                        .orElse(fallbackPhase);
        TaskStatus to = resumedStatus(restored);
        Instant now = Instant.now();
        clearStoppedLivenessPointer(taskId);
        taskStore.clearRecoveryState(taskId);
        taskStore.updateRuntimeFailure(taskId, null, null);
        // Restore the status before publishing the phase projection.
        // Stage reopen guards reload this row synchronously and must see
        // the recovery intent's safe runnable status, not the old park.
        if (task.status() != to) {
            taskStore.updateStatusIf(taskId, task.status(), to);
            taskStore.appendStatusEvent(taskId, task.status(), to, actor, reason, now);
        }
        if (task.phase() != restored) {
            // Deliberate legality escape, like the pre-machine recover():
            // the graph has no generic NEEDS_ATTENTION exit; the barrier
            // command is the audited way back to the checkpointed phase.
            taskStore.updatePhase(taskId, restored);
            taskStore.appendPhaseEvent(taskId, task.phase(), restored, now, reason, actor);
            events.publishEvent(new TaskPhaseTransitionedEvent(
                    taskId, task.phase(), restored, reason));
        }
        if (restored == TaskPhase.VALIDATING) {
            events.publishEvent(new ValidationRecheckRequestedEvent(taskId));
        }
        return task.withPhase(restored).withStatus(to).withEndedAt(null).withErrorMessage(null);
    }

    /** Complete a guided replan after the stop barrier has retired every
     * old turn and validation owner. Unlike normal recovery, this intent
     * deliberately opens a fresh Planning epoch instead of restoring the
     * checkpointed phase. */
    public Task completeReplanInCommand(String taskId, Actor actor, String reason)
    {
        requireNonNull(actor, "actor is null");
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = legacyTask(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        TaskRecoveryRequest request = taskStore.recoveryRequest(taskId)
                .filter(value -> TaskRecoveryRequest.KIND_REPLAN.equals(value.kind()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(409),
                        "task " + taskId + " has no pending replan"));
        if (task.status().isDone()
                || (task.phase() != TaskPhase.NEEDS_ATTENTION
                        && task.status() != TaskStatus.NEEDS_ATTENTION)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " is not parked for replan " + request.id());
        }
        assertNoLiveTurns(taskId, "pre-replan");
        assertValidationStopped(taskId);

        Instant now = Instant.now();
        clearStoppedLivenessPointer(taskId);
        taskStore.clearRecoveryState(taskId);
        taskStore.updateRuntimeFailure(taskId, null, null);
        if (task.status() != TaskStatus.IDLE) {
            taskStore.updateStatusIf(taskId, task.status(), TaskStatus.IDLE);
            taskStore.appendStatusEvent(
                    taskId, task.status(), TaskStatus.IDLE, actor, reason, now);
        }
        if (task.phase() != TaskPhase.PLANNING) {
            taskStore.updatePhase(taskId, TaskPhase.PLANNING);
            taskStore.appendPhaseEvent(
                    taskId, task.phase(), TaskPhase.PLANNING, now, reason, actor);
            events.publishEvent(new TaskPhaseTransitionedEvent(
                    taskId, task.phase(), TaskPhase.PLANNING, reason));
        }
        return task.withPhase(TaskPhase.PLANNING).withStatus(TaskStatus.IDLE)
                .withEndedAt(null).withErrorMessage(null);
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
    public ThreadTurn retryErrored(String taskId, String failedTurnId)
    {
        requireNonNull(failedTurnId, "failedTurnId is null");
        return commands.execute(taskId, () -> retryErroredInCommand(taskId, failedTurnId));
    }

    public ThreadTurn retryErroredInCommand(String taskId, String failedTurnId)
    {
        requireNonNull(failedTurnId, "failedTurnId is null");
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = legacyTask(taskId)
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
    }

    /**
     * IDLE → ARCHIVED, callable only from the idle archiver's guarded
     * scan. Re-verifies under the lock that the task is still IDLE with
     * no queued/running liveness work and no pending resume/recovery
     * request; the archiver owns the idle-threshold and
     * coordinator/validation guards.
     */
    public void archiveIdle(String taskId)
    {
        commands.executeVoid(taskId, () -> archiveIdleInCommand(taskId));
    }

    public void archiveIdleInCommand(String taskId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = legacyTask(taskId).orElse(null);
        if (task == null || task.status() != TaskStatus.IDLE) {
            return;
        }
        if (!turnStore.listTurnsByExactTaskIdAndStatus(taskId, ThreadTurnStatus.QUEUED, 1).isEmpty()
                || !turnStore.listTurnsByExactTaskIdAndStatus(taskId, ThreadTurnStatus.RUNNING, 1).isEmpty()
                || taskStore.resumeRequestedAt(taskId).isPresent()
                || taskStore.recoveryRequest(taskId).isPresent()) {
            return;
        }
        taskStore.updateStatusIf(taskId, TaskStatus.IDLE, TaskStatus.ARCHIVED);
        taskStore.appendStatusEvent(
                taskId, TaskStatus.IDLE, TaskStatus.ARCHIVED,
                Actor.SCHEDULER, "idle_archived", Instant.now());
    }

    /** The separate ARCHIVED → IDLE intent. It cannot touch PAUSED, and
     *  an anomalous live turn must finish first. */
    public Task reviveArchived(String taskId)
    {
        return commands.execute(taskId, () -> reviveArchivedInCommand(taskId));
    }

    public Task reviveArchivedInCommand(String taskId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = legacyTask(taskId)
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
    }

    /** Re-enter an already-IDLE task's runtime without manufacturing a
     * status edge. ERRORED work must use {@link #retryErrored}; stopped and
     * terminal work has its own named intent. */
    public Task resumeIdleRuntimeInCommand(String taskId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = legacyTask(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        if (task.status() != TaskStatus.IDLE
                || task.phase() == TaskPhase.NEEDS_ATTENTION
                || task.phase() == TaskPhase.COMPLETED) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " has no idle runtime to resume");
        }
        taskStore.updateRuntimeFailure(taskId, null, null);
        return task.withEndedAt(null).withErrorMessage(null);
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
        Task task = legacyTask(taskId)
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
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onValidationFinished(ValidationPassFinishedEvent event)
    {
        TaskCommandExecutor.dispatchAfterCommit(() ->
                commands.executeVoid(event.taskId(), () -> acceptValidationInCommand(event)));
    }

    private void acceptValidationInCommand(ValidationPassFinishedEvent event)
    {
        Task task = taskStore.findTaskById(event.taskId()).orElse(null);
        if (task == null || task.phase() != TaskPhase.VALIDATING
                || !acceptsForwardResult(task.status())
                || !matchesCurrentClaim(task, event)) {
            return;
        }
        if (event.passed()) {
            localCiFix.closeIfGreenInCommand(event.taskId());
            transitionInCommand(
                    event.taskId(), TaskPhase.INTERNAL_REVIEW,
                    "validation_passed", Actor.AGENT);
        }
        else if (!localCiFix.tryFixInCommand(task, event.failures())) {
            // No fix turn was queued (budget spent or nothing to run on) —
            // hand the failing checks back to the human.
            transitionInCommand(
                    event.taskId(), TaskPhase.NEEDS_ATTENTION,
                    "validation_failed", Actor.AGENT);
        }
    }

    private boolean matchesCurrentClaim(Task task, ValidationPassFinishedEvent event)
    {
        if (event.claimKey() == null) {
            return true; // legacy validation, removed when all callers claim
        }
        Optional<ValidationClaim> stored = validationStore.findByClaimKey(event.claimKey());
        if (stored.isEmpty()
                || stored.get().endedAt() == null
                || stored.get().supersededAt() != null
                || Boolean.TRUE.equals(stored.get().passed()) != event.passed()
                || !event.codeFingerprint().equals(stored.get().codeFingerprint())
                || event.validationEpoch() == null
                || event.validationEpoch() != currentValidationEpoch(task.id())) {
            return false;
        }
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            return false;
        }
        return event.codeFingerprint().equals(
                fingerprints.fingerprint(Path.of(task.worktreePath())));
    }

    private long currentValidationEpoch(String taskId)
    {
        return taskStore.listPhaseEvents(taskId).stream()
                .filter(event -> event.toPhase() == TaskPhase.VALIDATING)
                .mapToLong(event -> event.id())
                .max()
                .orElse(0L);
    }

    private static boolean acceptsForwardResult(TaskStatus status)
    {
        return switch (status) {
            case PENDING, RUNNING, IDLE, AWAITING_REVIEW, IN_REVIEW -> true;
            case PAUSED, NEEDS_ATTENTION, COMPLETED, REMOTE_CLOSED,
                    ERRORED, CANCELED, ARCHIVED -> false;
        };
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
    public void observe(String taskId, TaskPhase to, String reason)
    {
        requireNonNull(to, "to is null");
        commands.executeVoid(taskId, () -> observeInCommand(taskId, to, reason));
    }

    /** Record an authoritative exact-repo/head/base remote PR observation.
     * Local work may move onto the remote spine, but stopped tasks only retain
     * the fact/checkpoint for their teardown-barrier completion. */
    public void observeRemoteOpened(String taskId, String reason)
    {
        commands.executeVoid(taskId, () -> observeRemoteOpenedInCommand(taskId, reason));
    }

    public void observeRemoteOpenedInCommand(String taskId, String reason)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = legacyTask(taskId).orElse(null);
        if (task == null || task.status().isDone() || task.phase() == TaskPhase.COMPLETED) {
            return;
        }
        if (task.status() == TaskStatus.PAUSED || task.status() == TaskStatus.ARCHIVED) {
            return;
        }
        if (task.status() == TaskStatus.NEEDS_ATTENTION
                || task.phase() == TaskPhase.NEEDS_ATTENTION) {
            taskStore.checkpointRecovery(
                    taskId, TaskPhase.PUSHED_AWAITING_CI, recoveryContext(reason));
            return;
        }
        if (task.phase() == TaskPhase.PUSHED_AWAITING_CI
                || task.phase() == TaskPhase.AWAITING_READY
                || task.phase() == TaskPhase.AWAITING_REMOTE_REVIEW) {
            return;
        }
        if (!isRemoteOpenSource(task.phase())) {
            return;
        }
        moveStatusToRemoteReview(task, reason);
        applyObservedPhase(task, TaskPhase.PUSHED_AWAITING_CI, reason);
    }

    /** Consume current green-CI evidence once. Red/pending observations hold
     * the existing phase; a later stale poll can therefore never rewind the
     * remote spine. */
    public void observeRemoteCiGreen(String taskId, boolean draft, String reason)
    {
        commands.executeVoid(taskId,
                () -> observeRemoteCiGreenInCommand(taskId, draft, reason));
    }

    public void observeRemoteCiGreenInCommand(String taskId, boolean draft, String reason)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = legacyTask(taskId).orElse(null);
        if (task == null || !acceptsForwardResult(task.status())
                || task.phase() != TaskPhase.PUSHED_AWAITING_CI) {
            return;
        }
        applyTransition(task, task.phase(),
                draft ? TaskPhase.AWAITING_READY : TaskPhase.AWAITING_REMOTE_REVIEW,
                reason, Actor.WEBHOOK);
    }

    /** Consume confirmation that GitHub accepted the draft-to-ready effect. */
    public void observeReady(String taskId, String reason)
    {
        commands.executeVoid(taskId, () -> observeReadyInCommand(taskId, reason));
    }

    public void observeReadyInCommand(String taskId, String reason)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = legacyTask(taskId).orElse(null);
        if (task == null || !acceptsForwardResult(task.status())
                || task.phase() != TaskPhase.AWAITING_READY) {
            return;
        }
        applyTransition(task, task.phase(), TaskPhase.AWAITING_REMOTE_REVIEW,
                reason, Actor.WEBHOOK);
    }

    public void observeInCommand(String taskId, TaskPhase to, String reason)
    {
        requireNonNull(to, "to is null");
        TaskCommandExecutor.requireCurrent(taskId);
        observeLocked(taskId, to, reason);
    }

    private void observeLocked(String taskId, TaskPhase to, String reason)
    {
        Task task = legacyTask(taskId).orElse(null);
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

    private void moveStatusToRemoteReview(Task task, String reason)
    {
        if (task.status() == TaskStatus.IN_REVIEW) {
            return;
        }
        if (task.status() != TaskStatus.PENDING
                && task.status() != TaskStatus.RUNNING
                && task.status() != TaskStatus.IDLE
                && task.status() != TaskStatus.AWAITING_REVIEW
                && task.status() != TaskStatus.ERRORED) {
            return;
        }
        if (!taskStore.updateStatusIf(task.id(), task.status(), TaskStatus.IN_REVIEW)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + task.id() + " changed while recording its remote PR");
        }
        taskStore.appendStatusEvent(
                task.id(), task.status(), TaskStatus.IN_REVIEW,
                Actor.WEBHOOK, reason, Instant.now());
        if (task.status() == TaskStatus.ERRORED) {
            clearStoppedLivenessPointer(task.id());
            taskStore.updateRuntimeFailure(task.id(), null, null);
        }
    }

    private void applyObservedPhase(Task task, TaskPhase to, String reason)
    {
        TaskPhase from = task.phase();
        taskStore.updatePhase(task.id(), to);
        taskStore.appendPhaseEvent(task.id(), from, to, Instant.now(), reason, Actor.WEBHOOK);
        events.publishEvent(new TaskPhaseTransitionedEvent(task.id(), from, to, reason));
    }

    private static boolean isRemoteOpenSource(TaskPhase phase)
    {
        return switch (phase) {
            case IMPLEMENTING, VALIDATING, INTERNAL_REVIEW,
                    AWAITING_PUSH, ADDRESSING_LOCAL_COMMENTS -> true;
            default -> false;
        };
    }

    /**
     * The one durable terminal command: write the terminal status (with
     * its status-audit row) and drive the phase to COMPLETED in a single
     * locked step, including every durable child sealer. Runtime teardown —
     * interrupts, agent eviction, worktree reaping — belongs to the caller
     * and runs only after this durable intent, each step idempotent and
     * reconciled.
     */
    public void finishTerminal(String taskId, TaskStatus terminalStatus, Actor actor, String reason)
    {
        if (terminalStatus != TaskStatus.COMPLETED
                && terminalStatus != TaskStatus.REMOTE_CLOSED
                && terminalStatus != TaskStatus.CANCELED) {
            throw new IllegalArgumentException("not a terminal status: " + terminalStatus);
        }
        requireNonNull(actor, "actor is null");
        commands.executeVoid(taskId,
                () -> finishTerminalInCommand(taskId, terminalStatus, actor, reason));
    }

    public void finishTerminalInCommand(
            String taskId, TaskStatus terminalStatus, Actor actor, String reason)
    {
        if (terminalStatus != TaskStatus.COMPLETED
                && terminalStatus != TaskStatus.REMOTE_CLOSED
                && terminalStatus != TaskStatus.CANCELED) {
            throw new IllegalArgumentException("not a terminal status: " + terminalStatus);
        }
        requireNonNull(actor, "actor is null");
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = legacyTask(taskId).orElse(null);
        if (task == null) {
            return;
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
        // Synchronous listener: every durable child is sealed before this
        // command can commit. Publish on repeats too, so a retry reconciles
        // a terminal row written by an older/partially-deployed producer.
        events.publishEvent(new TaskTerminalSealingEvent(taskId, reason));
    }

    /**
     * The one durable pause command: checkpoint the pre-pause status in
     * {@code paused_status}, clear the stale subprocess pid, and move to
     * PAUSED with its status-audit row — phase holds. Idempotent when
     * already PAUSED. Runtime teardown belongs to the caller, after
     * commit, behind its identity token.
     */
    public Task pause(String taskId, Actor actor, String reason)
    {
        requireNonNull(actor, "actor is null");
        return commands.execute(taskId, () -> pauseInCommand(taskId, actor, reason));
    }

    public Task pauseInCommand(String taskId, Actor actor, String reason)
    {
        requireNonNull(actor, "actor is null");
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = legacyTask(taskId)
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
    public void requestResume(String taskId)
    {
        commands.executeVoid(taskId, () -> requestResumeInCommand(taskId));
    }

    public void requestResumeInCommand(String taskId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = legacyTask(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        if (task.status() != TaskStatus.PAUSED) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " is not paused");
        }
        taskStore.requestResume(taskId, Instant.now());
    }

    /**
     * The only normal edge out of PAUSED. The caller must first prove the
     * pre-pause runtime is gone (no cached agent, no live validation
     * executor); the durable turn half of that proof is re-checked here.
     * The safe post-resume status derives from the phase — never RUNNING,
     * and never a blind restore of the checkpoint.
     */
    public Task completeResume(String taskId, Actor actor, String reason)
    {
        requireNonNull(actor, "actor is null");
        return commands.execute(taskId, () -> completeResumeInCommand(taskId, actor, reason));
    }

    public Task completeResumeInCommand(String taskId, Actor actor, String reason)
    {
        requireNonNull(actor, "actor is null");
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = legacyTask(taskId)
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
        assertValidationStopped(taskId);
        TaskStatus to = resumedStatus(task.phase());
        clearStoppedLivenessPointer(taskId);
        taskStore.clearPauseCheckpoint(taskId);
        taskStore.updateRuntimeFailure(taskId, null, null);
        taskStore.updateStatusIf(taskId, TaskStatus.PAUSED, to);
        taskStore.appendStatusEvent(taskId, TaskStatus.PAUSED, to, actor, reason, Instant.now());
        if (task.phase() == TaskPhase.VALIDATING) {
            events.publishEvent(new ValidationRecheckRequestedEvent(taskId));
        }
        return task.withStatus(to).withEndedAt(null).withErrorMessage(null);
    }

    /** A stopped task's pre-stop turn may remain as the durable authority
     *  after scheduler cancellation. Recovery must retire that terminal
     *  pointer before a replacement can claim liveness. */
    private void clearStoppedLivenessPointer(String taskId)
    {
        Optional<String> currentId = taskStore.currentLivenessTurnId(taskId);
        if (currentId.isEmpty()) {
            return;
        }
        Optional<ThreadTurn> current = turnStore.findTurnById(currentId.orElseThrow());
        if (current.isPresent()
                && (current.get().status() == ThreadTurnStatus.QUEUED
                        || current.get().status() == ThreadTurnStatus.RUNNING)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " still owns live turn " + currentId.orElseThrow());
        }
        if (!taskStore.setCurrentLivenessTurnIdIf(taskId, currentId.orElseThrow(), null)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " liveness authority changed during recovery");
        }
    }

    private void assertNoLiveTurns(String taskId, String stopKind)
    {
        if (!turnStore.listTurnsByExactTaskIdAndStatus(taskId, ThreadTurnStatus.QUEUED, 1).isEmpty()
                || !turnStore.listTurnsByExactTaskIdAndStatus(
                        taskId, ThreadTurnStatus.RUNNING, 1).isEmpty()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " still has live " + stopKind + " turns");
        }
    }

    /** A cancellation request is not an absence proof. The cancellation
     * reconciler must first finish the worker or mark the claim
     * superseded; only then does it leave this open set. */
    private void assertValidationStopped(String taskId)
    {
        for (ValidationClaim claim : validationStore.findOpenByTask(taskId)) {
            if (validationExecutors.isInFlight(claim.claimKey())
                    || claim.leaseUntil() != null && claim.leaseUntil().isAfter(Instant.now())) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "task " + taskId + " still has live validation " + claim.claimKey());
            }
        }
    }

    /** Fail closed at the legacy writer boundary. Routing mistakes must not
     *  let this state machine compete with the V2 TaskManager. */
    private Optional<Task> legacyTask(String taskId)
    {
        if (taskStore.isV2Task(taskId)) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "V2 task lifecycle is owned by TaskManager: " + taskId);
        }
        return taskStore.findTaskById(taskId);
    }

    private static boolean supportedRecoveryKind(String kind)
    {
        return TaskRecoveryRequest.KIND_NORMAL.equals(kind)
                || TaskRecoveryRequest.KIND_CI_RETRY.equals(kind)
                || TaskRecoveryRequest.KIND_REPLAN.equals(kind)
                || TaskRecoveryRequest.KIND_EXTERNAL_SAGA.equals(kind);
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
