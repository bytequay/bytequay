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
package com.bytequay.app.service.review;

import com.bytequay.app.config.AsyncConfig;
import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.LegacySagaCapacity;
import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.ReviewRoundState;
import com.bytequay.app.domain.RoundGateAuthorization;
import com.bytequay.app.domain.RoundGateEffect;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskRecoveryRequest;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.RoundGateStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.threads.TaskExternalEffectGate;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Durable, fingerprint-fenced posting saga for one external review round. */
@Service
public class RoundGateSaga
{
    static final String EFFECT_PUSH_BRANCH = "push_branch";
    private static final String EFFECT_REPLY_PREFIX = "reply:";
    private static final String EFFECT_RESOLVE_PREFIX = "resolve:";
    private static final int ATTEMPT_LIMIT = 3;
    public static final int DEFAULT_RECOVERY_ALLOWANCE = 1;
    private static final int MAX_RECOVERY_ALLOWANCE = 3;
    private static final String RECOVERY_EFFECT_FAILED = "EFFECT_FAILED";
    private static final String RECOVERY_FINGERPRINT_MISMATCH = "FINGERPRINT_MISMATCH";
    private static final int RECOVERY_BATCH = 50;
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(10);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(15);
    private static final Logger log = LoggerFactory.getLogger(RoundGateSaga.class);

    private final ReviewRoundStore rounds;
    private final RoundGateStore gates;
    private final TaskStore tasks;
    private final StageStore stages;
    private final ReviewRoundStateMachine roundMachine;
    private final TaskPhaseMachine taskMachine;
    private final TaskCommandExecutor commands;
    private final PRService prs;
    private final PullRequestService pullRequests;
    private final GitRunner git;
    private final CodeFingerprints fingerprints;
    private final LegacySagaCapacity capacity;
    private final ObjectMapper mapper;
    private final Executor executor;

    public RoundGateSaga(
            ReviewRoundStore rounds,
            RoundGateStore gates,
            TaskStore tasks,
            StageStore stages,
            ReviewRoundStateMachine roundMachine,
            TaskPhaseMachine taskMachine,
            TaskCommandExecutor commands,
            PRService prs,
            PullRequestService pullRequests,
            GitRunner git,
            CodeFingerprints fingerprints,
            LegacySagaCapacity capacity,
            ObjectMapper mapper,
            @Qualifier(AsyncConfig.APPLICATION_EXECUTOR) Executor executor)
    {
        this.rounds = requireNonNull(rounds, "rounds is null");
        this.gates = requireNonNull(gates, "gates is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.stages = requireNonNull(stages, "stages is null");
        this.roundMachine = requireNonNull(roundMachine, "roundMachine is null");
        this.taskMachine = requireNonNull(taskMachine, "taskMachine is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.prs = requireNonNull(prs, "prs is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.git = requireNonNull(git, "git is null");
        this.fingerprints = requireNonNull(fingerprints, "fingerprints is null");
        this.capacity = requireNonNull(capacity, "capacity is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    public ReviewRound approve(String roundId)
    {
        ReviewRound round = requireRound(roundId);
        return TaskExternalEffectGate.withEffectGate(round.taskId(), () -> {
            authorize(roundId);
            return requireRound(roundId);
        });
    }

    public void drive(String token)
    {
        RoundGateAuthorization authorization = gates.findAuthorization(token).orElse(null);
        if (authorization == null) {
            return;
        }
        TaskExternalEffectGate.withEffectGate(authorization.taskId(), () -> {
            driveLocked(token);
            return null;
        });
    }

    public Optional<String> activeToken(String taskId)
    {
        return gates.findActiveByTask(taskId).map(RoundGateAuthorization::token);
    }

    /** Persist a gate-payload mutation and invalidate its human authorization
     * in one task command. No payload may change after an external attempt. */
    public <T> T editPayload(
            String taskId, String roundId, Supplier<T> mutation)
    {
        requireNonNull(mutation, "mutation is null");
        return TaskExternalEffectGate.withEffectGate(taskId, () ->
                commands.execute(taskId, () -> editPayloadInCommand(
                        taskId, roundId, mutation)));
    }

    public void editPayload(String taskId, String roundId, Runnable mutation)
    {
        requireNonNull(mutation, "mutation is null");
        editPayload(taskId, roundId, () -> {
            mutation.run();
            return null;
        });
    }

    /** Build a recovery request for this saga only. A task-push request uses
     * the same request kind but has no round id, so it is left to TaskPushSaga. */
    public Optional<RecoveryPlan> prepareRecovery(String taskId, int addedAllowance)
    {
        Task task = requireTask(taskId);
        if (task.phase() != TaskPhase.NEEDS_ATTENTION
                || task.status() != TaskStatus.NEEDS_ATTENTION) {
            return Optional.empty();
        }
        Optional<RecoveryPlan> existing = existingRecoveryPlan(taskId);
        if (existing.isPresent()) {
            return Optional.of(verifyRecoveryPlan(taskId, existing.orElseThrow()));
        }
        RoundGateAuthorization authorization = gates.findActiveByTask(taskId).orElse(null);
        if (authorization == null) {
            return Optional.empty();
        }
        ReviewRound round = requireRound(authorization.roundId());
        if (round.status() != ReviewRoundState.PAUSED
                || round.pausedFrom() != ReviewRoundState.AWAITING_GATE
                || !authorization.token().equals(round.activeGateToken())) {
            throw conflict("round gate is not parked at its posting checkpoint");
        }
        RoundGateEffect cursor = currentCursor(authorization.token()).orElse(null);
        if (cursor != null && cursor.status() == RoundGateEffect.Status.IN_FLIGHT) {
            throw conflict("round gate effect " + cursor.effectKey() + " is still being reconciled");
        }
        boolean failed = failedCursor(cursor);
        int allowance = failed ? addedAllowance : 0;
        if (failed && (allowance < 1 || allowance > MAX_RECOVERY_ALLOWANCE)) {
            throw conflict("round-gate recovery allowance must be between 1 and "
                    + MAX_RECOVERY_ALLOWANCE);
        }
        GatePayload payload = payload(authorization);
        ObservedCode observed = observeCode(payload);
        requireRestoredCode(authorization, payload, observed);
        return Optional.of(new RecoveryPlan(
                taskId, round.id(), round.runId(), authorization.token(),
                cursor == null ? null : cursor.effectKey(),
                failed ? RECOVERY_EFFECT_FAILED : RECOVERY_FINGERPRINT_MISMATCH,
                allowance, observed.headSha(), observed.fingerprint()));
    }

    public Optional<RecoveryPlan> verifyRecoveryRequest(String taskId)
    {
        return existingRecoveryPlan(taskId)
                .map(plan -> verifyRecoveryPlan(taskId, plan));
    }

    public String recoveryPayload(RecoveryPlan plan)
    {
        return json(plan);
    }

    /** Re-arm and restore the exact paused round cursor in the same command
     * that will restore the task's checkpointed phase. */
    public void resumeExternalSagaInCommand(RecoveryPlan plan)
    {
        TaskCommandExecutor.requireCurrent(plan.taskId());
        RecoveryPlan durable = existingRecoveryPlan(plan.taskId())
                .orElseThrow(() -> conflict("task has no round-gate recovery request"));
        if (!durable.equals(plan)) {
            throw conflict("round-gate recovery request changed");
        }
        verifyRecoveryRows(plan.taskId(), plan);
        if (RECOVERY_EFFECT_FAILED.equals(plan.reason())
                && !gates.rearmEffect(
                        plan.token(), plan.effectKey(), plan.addedAllowance(), Instant.now())) {
            throw conflict("failed round-gate cursor could not be re-armed");
        }
        roundMachine.resumeInCommand(
                plan.taskId(), plan.roundId(), "round_gate_recovered");
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAuthorized(RoundGateAuthorizedEvent event)
    {
        try {
            executor.execute(() -> {
                try {
                    drive(event.token());
                }
                catch (RuntimeException e) {
                    log.warn("driving authorized round gate {} failed: {}",
                            event.token(), e.getMessage());
                }
            });
        }
        catch (RuntimeException e) {
            // The durable authorization sweep is the delivery guarantee.
            log.warn("submitting authorized round gate {} failed: {}",
                    event.token(), e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void reconcileActive()
    {
        for (RoundGateAuthorization authorization : gates.findRecoverable(
                Instant.now(), RECOVERY_BATCH)) {
            try {
                drive(authorization.token());
            }
            catch (RuntimeException e) {
                log.warn("recovering round gate {} for task {} failed: {}",
                        authorization.token(), authorization.taskId(), e.getMessage());
            }
        }
    }

    private RoundGateAuthorization authorize(String roundId)
    {
        ReviewRound round = requireRound(roundId);
        RoundGateAuthorization active = gates.findActiveByRound(roundId).orElse(null);
        if (active != null) {
            if (!active.token().equals(round.activeGateToken())) {
                throw conflict("round gate authorization does not match the active round token");
            }
            return active;
        }
        if (round.status() != ReviewRoundState.AWAITING_GATE
                || !ReviewRound.ORIGIN_EXTERNAL.equals(round.origin())
                || round.codeFingerprint() == null
                || round.codeFingerprint().isBlank()) {
            throw conflict("round " + roundId + " is not ready for posting approval");
        }
        Task task = requireTask(round.taskId());
        PullRequestRef ref = PullRequestRef.parse(task.linkedPrRef())
                .orElseThrow(() -> conflict("task has no valid linked pull request"));
        Path worktree = worktree(task);
        List<ReviewComment> comments = stages.findCommentsByRound(UUID.fromString(roundId));
        GatePayload payload = freezePayload(
                round, ref, worktree, task.branchName(), headSha(worktree), comments);
        String payloadJson = json(payload);
        List<String> effectKeys = effectKeys(payload);
        RoundGateAuthorization candidate = new RoundGateAuthorization(
                UUID.randomUUID().toString(), task.id(), round.id(), round.gateRevision(),
                round.kickAttempt(), Actor.HUMAN, round.codeFingerprint(), payloadJson,
                sha256(payloadJson), json(effectKeys), Instant.now(), null, null, null);
        return commands.execute(task.id(), () -> authorizeInCommand(candidate, effectKeys));
    }

    private RoundGateAuthorization authorizeInCommand(
            RoundGateAuthorization candidate, List<String> effectKeys)
    {
        TaskCommandExecutor.requireCurrent(candidate.taskId());
        ReviewRound round = requireRound(candidate.roundId());
        RoundGateAuthorization active = gates.findActiveByRound(round.id()).orElse(null);
        if (active != null) {
            if (active.token().equals(round.activeGateToken())) {
                return active;
            }
            throw conflict("round already has a different posting authorization");
        }
        if (round.status() != ReviewRoundState.AWAITING_GATE
                || round.gateRevision() != candidate.gateRevision()
                || !candidate.codeFingerprint().equals(round.codeFingerprint())) {
            throw conflict("round changed while posting approval was being recorded");
        }
        gates.insert(candidate, effectKeys, ATTEMPT_LIMIT);
        roundMachine.authorizeGateInCommand(
                candidate.taskId(), candidate.roundId(), candidate.token(),
                candidate.gateRevision(), candidate.codeFingerprint(), "round_gate_approved");
        prs.findByTask(candidate.taskId()).ifPresent(pr -> prs.recordGateApproval(
                pr.id(), "external-review",
                candidate.roundId() + ":revision:" + candidate.gateRevision()));
        return candidate;
    }

    private void driveLocked(String token)
    {
        RoundGateAuthorization authorization = gates.findAuthorization(token).orElse(null);
        if (authorization == null || !authorization.active()) {
            return;
        }
        GatePayload payload = payload(authorization);
        if (!runnableAtGate(authorization)) {
            return;
        }
        for (String effectKey : authorizedEffectKeys(authorization)) {
            RoundGateEffect effect = gates.findEffect(token, effectKey).orElseThrow();
            if (effect.completed()) {
                continue;
            }
            if (effect.status() == RoundGateEffect.Status.PERMANENT_FAILED) {
                return;
            }
            if (effect.status() == RoundGateEffect.Status.RETRYABLE_FAILED
                    && !retryDue(effect)) {
                return;
            }
            if (effect.status() == RoundGateEffect.Status.IN_FLIGHT
                    && !leaseExpired(effect)) {
                return;
            }

            // The application executor only delivers the durable wake. Shared
            // admission starts here, before any effect claim or adapter I/O;
            // denial leaves this row for reconcileActive().
            Optional<LegacySagaCapacity.Attempt> admitted = capacity.tryAcquire(
                    authorization.taskId(), capacityOperationId(effect),
                    Set.of(CapacityManager.CapacityLane.GITHUB));
            if (admitted.isEmpty()) {
                return;
            }
            try (LegacySagaCapacity.Attempt attempt = admitted.orElseThrow()) {
                if ((effect.status() == RoundGateEffect.Status.IN_FLIGHT
                        && leaseExpired(effect))
                        || effect.status() == RoundGateEffect.Status.RETRYABLE_FAILED) {
                    ProbeResult probe;
                    try {
                        attempt.requireLive();
                        probe = probeApplied(authorization, payload, effectKey);
                        attempt.requireLive();
                    }
                    catch (RuntimeException e) {
                        if (attempt.leaseLost()) {
                            return;
                        }
                        if (effect.status() == RoundGateEffect.Status.IN_FLIGHT) {
                            failEffect(authorization, effectKey, effect.claimOwner(), e);
                        }
                        else {
                            failRetryableProbe(authorization, effectKey, e);
                        }
                        return;
                    }
                    if (probe == ProbeResult.APPLIED
                            && recoverCompletedEffect(authorization, payload, effect)) {
                        continue;
                    }
                }
                if (effect.exhausted()) {
                    parkExhausted(authorization, effect);
                    return;
                }

                String owner = UUID.randomUUID().toString();
                boolean claimed = false;
                try {
                    attempt.requireLive();
                    ObservedCode observed = observeCode(payload);
                    if (!authorizedCodeMatches(authorization, payload, observed)) {
                        invalidateOrParkMismatch(authorization, observed.fingerprint());
                        return;
                    }
                    attempt.requireLive();
                    claimed = commands.execute(authorization.taskId(), () ->
                            claimInCommand(authorization, effectKey, owner, observed));
                    if (!claimed) {
                        return;
                    }
                    attempt.requireLive();
                    String evidence = performEffect(authorization, payload, effectKey);
                    attempt.requireLive();
                    commands.executeVoid(authorization.taskId(), () ->
                            completeEffectInCommand(
                                    authorization, payload, effectKey, owner, evidence));
                }
                catch (RuntimeException e) {
                    if (attempt.leaseLost()) {
                        // Preserve an ambiguous owned claim for the existing
                        // probe-before-retry recovery path.
                        return;
                    }
                    if (claimed) {
                        failEffect(authorization, effectKey, owner, e);
                    }
                    throw e;
                }
            }
        }
        finalizeUnderCapacity(authorization, payload);
    }

    private void finalizeUnderCapacity(
            RoundGateAuthorization authorization, GatePayload payload)
    {
        Optional<LegacySagaCapacity.Attempt> admitted = capacity.tryAcquire(
                authorization.taskId(),
                authorizationCapacityOperationId(authorization.token(), "finalize"),
                Set.of(CapacityManager.CapacityLane.GITHUB));
        if (admitted.isEmpty()) {
            return;
        }
        ObservedCode finalCode;
        try (LegacySagaCapacity.Attempt attempt = admitted.orElseThrow()) {
            try {
                attempt.requireLive();
                finalCode = observeCode(payload);
                if (!authorizedCodeMatches(authorization, payload, finalCode)) {
                    invalidateOrParkMismatch(authorization, finalCode.fingerprint());
                    return;
                }
                attempt.requireLive();
                commands.executeVoid(authorization.taskId(), () ->
                        finalizeInCommand(authorization, finalCode));
            }
            catch (RuntimeException e) {
                if (!attempt.leaseLost()) {
                    throw e;
                }
                return;
            }
        }
    }

    static String capacityOperationId(RoundGateEffect effect)
    {
        return "legacy-round-gate-effect:" + effect.id();
    }

    private static String authorizationCapacityOperationId(String token, String action)
    {
        return "legacy-round-gate-authorization:" + token + ":" + action;
    }

    private boolean claimInCommand(
            RoundGateAuthorization authorization,
            String effectKey,
            String owner,
            ObservedCode observed)
    {
        TaskCommandExecutor.requireCurrent(authorization.taskId());
        RoundGateAuthorization current = gates.findAuthorization(authorization.token()).orElse(null);
        if (current == null || !current.active()
                || !runnableAtGate(current)
                || !current.codeFingerprint().equals(observed.fingerprint())) {
            return false;
        }
        Instant now = Instant.now();
        return gates.claimEffect(
                current.token(), effectKey, owner, now, now.plus(CLAIM_LEASE));
    }

    private void completeEffectInCommand(
            RoundGateAuthorization authorization,
            GatePayload payload,
            String effectKey,
            String owner,
            String evidence)
    {
        TaskCommandExecutor.requireCurrent(authorization.taskId());
        if (!gates.completeEffect(
                authorization.token(), effectKey, owner, evidence, Instant.now())) {
            throw conflict("round gate effect " + effectKey + " lost its claim");
        }
        stampLocalCheckpoint(payload, effectKey);
    }

    private boolean recoverCompletedEffect(
            RoundGateAuthorization authorization,
            GatePayload payload,
            RoundGateEffect effect)
    {
        return commands.execute(authorization.taskId(), () -> {
            TaskCommandExecutor.requireCurrent(authorization.taskId());
            boolean completed = gates.completeProbedEffect(
                    authorization.token(), effect.effectKey(),
                    json(Map.of("recoveredByProbe", true)), Instant.now());
            if (completed) {
                stampLocalCheckpoint(payload, effect.effectKey());
            }
            return completed;
        });
    }

    private void failEffect(
            RoundGateAuthorization authorization,
            String effectKey,
            String owner,
            RuntimeException failure)
    {
        commands.executeVoid(authorization.taskId(), () -> {
            TaskCommandExecutor.requireCurrent(authorization.taskId());
            failClaimedEffectInCommand(authorization, effectKey, owner, failure);
        });
    }

    /** A retryable row is ambiguous until its remote probe succeeds. A failed
     * probe therefore spends a bounded attempt and persists backoff; otherwise
     * a due row would be hot-probed forever without ever reaching its limit. */
    private void failRetryableProbe(
            RoundGateAuthorization authorization,
            String effectKey,
            RuntimeException failure)
    {
        commands.executeVoid(authorization.taskId(), () -> {
            TaskCommandExecutor.requireCurrent(authorization.taskId());
            RoundGateEffect current = gates.findEffect(
                    authorization.token(), effectKey).orElseThrow();
            if (current.status() != RoundGateEffect.Status.RETRYABLE_FAILED
                    || !retryDue(current)) {
                return;
            }
            if (current.exhausted()) {
                gates.markExhausted(
                        authorization.token(), effectKey,
                        failure.getClass().getName(), safeMessage(failure));
                parkInCommand(authorization, "round_gate_effect_exhausted");
                return;
            }
            String owner = UUID.randomUUID().toString();
            Instant now = Instant.now();
            if (!gates.claimEffect(
                    authorization.token(), effectKey, owner, now, now.plus(CLAIM_LEASE))) {
                return;
            }
            failClaimedEffectInCommand(authorization, effectKey, owner, failure);
        });
    }

    private void failClaimedEffectInCommand(
            RoundGateAuthorization authorization,
            String effectKey,
            String owner,
            RuntimeException failure)
    {
        RoundGateEffect claimed = gates.findEffect(
                authorization.token(), effectKey).orElseThrow();
        boolean terminal = permanent(failure) || claimed.exhausted();
        RoundGateEffect.Status status = terminal
                ? RoundGateEffect.Status.PERMANENT_FAILED
                : RoundGateEffect.Status.RETRYABLE_FAILED;
        Instant retryAt = terminal ? null : Instant.now().plus(retryDelay(claimed.attempts()));
        if (!gates.failEffect(
                authorization.token(), effectKey, owner, status,
                failure.getClass().getName(), safeMessage(failure), retryAt)) {
            return;
        }
        if (terminal) {
            parkInCommand(authorization, "round_gate_effect_failed");
        }
    }

    private <T> T editPayloadInCommand(
            String taskId, String roundId, Supplier<T> mutation)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = requireTask(taskId);
        ReviewRound round = requireRound(roundId);
        if (!taskId.equals(round.taskId())
                || !ReviewRound.ORIGIN_EXTERNAL.equals(round.origin())
                || task.phase() != TaskPhase.AWAITING_REMOTE_REVIEW
                || task.status() != TaskStatus.IN_REVIEW
                || (round.status() != ReviewRoundState.AWAITING_GATE
                        && round.status() != ReviewRoundState.ADDRESSING)) {
            throw conflict("round " + roundId + " has no editable gate payload");
        }
        RoundGateAuthorization active = gates.findActiveByRound(roundId).orElse(null);
        if (active == null && round.activeGateToken() != null) {
            throw conflict("round gate authorization is inconsistent");
        }
        if (active != null) {
            if (!active.token().equals(round.activeGateToken())
                    || gates.findEffects(active.token()).stream().anyMatch(effect ->
                            effect.status() != RoundGateEffect.Status.PENDING
                                    || effect.attempts() != 0)) {
                throw conflict("round gate payload cannot change after posting has started");
            }
        }
        T result = mutation.get();
        // Drafts created by the admitted ADDRESSING turn are not a new human-reviewed
        // payload yet. Advancing gateRevision here would change that live turn's kick
        // key and allow the recovery sweep to enqueue a duplicate. Once the round is
        // awaiting the human gate, edits do create a new payload revision and revoke
        // any still-unclaimed authorization atomically with the mutation.
        if (round.status() == ReviewRoundState.AWAITING_GATE) {
            if (active != null && !gates.revokeIfUnclaimed(
                    active.token(), "payload_edited", Instant.now())) {
                throw conflict("round gate posting started while its payload was being edited");
            }
            if (!gates.bumpGateRevision(
                    taskId, roundId, round.gateRevision(),
                    active == null ? null : active.token())) {
                throw conflict("round changed while its gate payload was being edited");
            }
        }
        return result;
    }

    private void parkExhausted(
            RoundGateAuthorization authorization, RoundGateEffect effect)
    {
        commands.executeVoid(authorization.taskId(), () -> {
            TaskCommandExecutor.requireCurrent(authorization.taskId());
            gates.markExhausted(
                    authorization.token(), effect.effectKey(),
                    IllegalStateException.class.getName(), "round gate retry limit reached");
            parkInCommand(authorization, "round_gate_effect_exhausted");
        });
    }

    private void invalidateOrParkMismatch(
            RoundGateAuthorization authorization, String observedFingerprint)
    {
        commands.executeVoid(authorization.taskId(), () -> {
            TaskCommandExecutor.requireCurrent(authorization.taskId());
            boolean claimed = gates.findEffects(authorization.token()).stream()
                    .anyMatch(RoundGateEffect::claimed);
            if (!claimed && gates.revokeIfUnclaimed(
                    authorization.token(), "fingerprint_changed", Instant.now())) {
                roundMachine.invalidateGateFingerprintInCommand(
                        authorization.taskId(), authorization.roundId(),
                        authorization.token(), observedFingerprint);
                return;
            }
            parkInCommand(authorization, "round_gate_fingerprint_changed");
        });
    }

    private void parkInCommand(RoundGateAuthorization authorization, String reason)
    {
        ReviewRound round = rounds.findById(authorization.roundId()).orElse(null);
        if (round != null && round.status() != ReviewRoundState.PAUSED
                && round.status().isLive()) {
            roundMachine.parkInCommand(
                    authorization.taskId(), authorization.roundId(), reason);
        }
        Task task = tasks.findTaskById(authorization.taskId()).orElse(null);
        if (task != null && !task.status().isDone()
                && task.status() != TaskStatus.NEEDS_ATTENTION) {
            taskMachine.parkOperationalInCommand(
                    authorization.taskId(), Actor.AGENT, reason);
        }
    }

    private void finalizeInCommand(
            RoundGateAuthorization authorization, ObservedCode observed)
    {
        TaskCommandExecutor.requireCurrent(authorization.taskId());
        RoundGateAuthorization current = gates.findAuthorization(authorization.token()).orElse(null);
        if (current == null || !current.active()
                || !current.codeFingerprint().equals(observed.fingerprint())
                || gates.findEffects(current.token()).stream().anyMatch(effect -> !effect.completed())
                || !runnableAtGate(current)) {
            return;
        }
        if (!gates.consumeIfComplete(
                current.token(), RoundGateAuthorization.OUTCOME_POSTED, Instant.now())) {
            throw conflict("round gate authorization changed before finalization");
        }
        roundMachine.postInCommand(
                current.taskId(), current.roundId(), current.token(), "round_gate_posted");
    }

    private String performEffect(
            RoundGateAuthorization authorization, GatePayload payload, String effectKey)
    {
        if (EFFECT_PUSH_BRANCH.equals(effectKey)) {
            push(Path.of(payload.worktreePath()));
            return json(Map.of("headSha", payload.headSha()));
        }
        ReplyPayload reply = payload.replies().stream()
                .filter(candidate -> candidate.effectKey().equals(effectKey))
                .findFirst()
                .orElse(null);
        if (reply != null) {
            String body = reply.body() + "\n\n" + marker(authorization.token(), effectKey);
            if (reply.general()) {
                pullRequests.commentOnPullRequest(
                        payload.repo(), payload.number(), 0L, body, false);
            }
            else {
                pullRequests.replyToReviewThread(
                        payload.repo(), payload.number(), reply.remoteCommentId(), body);
            }
            return json(Map.of("commentId", reply.commentId()));
        }
        ResolvePayload resolution = payload.resolutions().stream()
                .filter(candidate -> candidate.effectKey().equals(effectKey))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown round gate effect: " + effectKey));
        pullRequests.setReviewThreadResolved(
                payload.repo(), payload.number(), 0L, resolution.remoteCommentId(), true);
        return json(Map.of("remoteCommentId", resolution.remoteCommentId()));
    }

    private ProbeResult probeApplied(
            RoundGateAuthorization authorization,
            GatePayload payload,
            String effectKey)
    {
        if (EFFECT_PUSH_BRANCH.equals(effectKey)) {
            try {
                return git.remoteHeadSha(
                                Path.of(payload.worktreePath()), "origin", payload.branchName())
                        .filter(payload.headSha()::equals)
                        .map(ignored -> ProbeResult.APPLIED)
                        .orElse(ProbeResult.NOT_APPLIED);
            }
            catch (IOException e) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "probing stale round gate push failed: " + e.getMessage());
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "probing stale round gate push was interrupted");
            }
        }
        PullRequestDetail detail = pullRequests.fetchFreshPullRequestDetail(
                payload.repo(), payload.number());
        ReplyPayload reply = payload.replies().stream()
                .filter(candidate -> candidate.effectKey().equals(effectKey))
                .findFirst()
                .orElse(null);
        if (reply != null) {
            String marker = marker(authorization.token(), effectKey);
            boolean inTimeline = detail.recentActivity().stream()
                    .map(PullRequestDetail.ActivityItem::body)
                    .filter(body -> body != null)
                    .anyMatch(body -> body.contains(marker));
            boolean inThreads = detail.reviewThreads().stream()
                    .flatMap(thread -> thread.messages().stream())
                    .map(PullRequestDetail.ReviewMessage::body)
                    .filter(body -> body != null)
                    .anyMatch(body -> body.contains(marker));
            return inTimeline || inThreads ? ProbeResult.APPLIED : ProbeResult.NOT_APPLIED;
        }
        ResolvePayload resolution = payload.resolutions().stream()
                .filter(candidate -> candidate.effectKey().equals(effectKey))
                .findFirst()
                .orElse(null);
        if (resolution == null) {
            return ProbeResult.NOT_APPLIED;
        }
        return detail.reviewThreads().stream()
                .filter(thread -> thread.rootGithubId() == resolution.remoteCommentId())
                .filter(thread -> Boolean.TRUE.equals(thread.resolved()))
                .findFirst()
                .map(ignored -> ProbeResult.APPLIED)
                .orElse(ProbeResult.NOT_APPLIED);
    }

    private void stampLocalCheckpoint(GatePayload payload, String effectKey)
    {
        payload.replies().stream()
                .filter(reply -> reply.effectKey().equals(effectKey))
                .findFirst()
                .flatMap(reply -> stages.findReviewCommentById(UUID.fromString(reply.commentId())))
                .ifPresent(comment -> stages.saveReviewComment(
                        comment.withDraftReplyPostedAt(Instant.now())));
        payload.resolutions().stream()
                .filter(resolution -> resolution.effectKey().equals(effectKey))
                .findFirst()
                .ifPresent(resolution -> resolution.commentIds().forEach(id ->
                        stages.markRemoteThreadResolutionPosted(UUID.fromString(id), Instant.now())));
    }

    private GatePayload freezePayload(
            ReviewRound round,
            PullRequestRef ref,
            Path worktree,
            String branchName,
            String headSha,
            List<ReviewComment> comments)
    {
        List<ReplyPayload> replies = new ArrayList<>();
        Map<Long, List<String>> resolutions = new LinkedHashMap<>();
        for (ReviewComment comment : comments) {
            if (!comment.resolved()) {
                throw conflict("round still has unresolved comment " + comment.id());
            }
            boolean general = comment.file() == null || comment.file().isBlank();
            if (!general && (comment.remoteCommentId() == null || comment.remoteCommentId() <= 0)) {
                throw conflict("inline comment has no remote thread root: " + comment.id());
            }
            if (comment.draftReplyBody() != null) {
                if (comment.draftReplyBody().isBlank()) {
                    throw conflict("draft reply is blank: " + comment.id());
                }
                if (!general && (comment.remoteCommentId() == null
                        || comment.remoteCommentId() <= 0)) {
                    throw conflict("draft reply has no remote thread root: " + comment.id());
                }
                replies.add(new ReplyPayload(
                        EFFECT_REPLY_PREFIX + comment.id(), comment.id().toString(),
                        comment.remoteCommentId(), general, comment.draftReplyBody()));
            }
            if (!general) {
                resolutions.computeIfAbsent(comment.remoteCommentId(), ignored -> new ArrayList<>())
                        .add(comment.id().toString());
            }
        }
        List<ResolvePayload> resolutionPayloads = resolutions.entrySet().stream()
                .map(entry -> new ResolvePayload(
                        EFFECT_RESOLVE_PREFIX + entry.getKey(), entry.getKey(), entry.getValue()))
                .toList();
        return new GatePayload(
                round.taskId(), round.id(), worktree.toString(), ref.repoRef().fullName(),
                ref.number(), branchName, headSha, round.codeFingerprint(), replies,
                resolutionPayloads);
    }

    private List<String> effectKeys(GatePayload payload)
    {
        List<String> keys = new ArrayList<>();
        keys.add(EFFECT_PUSH_BRANCH);
        payload.replies().forEach(reply -> keys.add(reply.effectKey()));
        payload.resolutions().forEach(resolution -> keys.add(resolution.effectKey()));
        return List.copyOf(keys);
    }

    private List<String> authorizedEffectKeys(RoundGateAuthorization authorization)
    {
        try {
            List<?> values = mapper.readValue(authorization.effectKeysJson(), List.class);
            List<String> keys = values.stream().map(String::valueOf).toList();
            if (!authorization.effectKeysJson().equals(json(keys))) {
                throw new IllegalStateException(
                        "round gate effect-key payload is not canonical: " + authorization.token());
            }
            return keys;
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "invalid effect keys for round gate " + authorization.token(), e);
        }
    }

    private Optional<RecoveryPlan> existingRecoveryPlan(String taskId)
    {
        TaskRecoveryRequest request = tasks.recoveryRequest(taskId)
                .filter(candidate -> TaskRecoveryRequest.KIND_EXTERNAL_SAGA.equals(candidate.kind()))
                .orElse(null);
        if (request == null || request.payloadJson() == null
                || request.payloadJson().isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode payload = mapper.readTree(request.payloadJson());
            if (!payload.hasNonNull("roundId")) {
                return Optional.empty();
            }
            return Optional.of(mapper.treeToValue(payload, RecoveryPlan.class));
        }
        catch (JsonProcessingException e) {
            throw conflict("round-gate recovery request has an invalid payload");
        }
    }

    private RecoveryPlan verifyRecoveryPlan(String expectedTaskId, RecoveryPlan plan)
    {
        RecoveryRows rows = verifyRecoveryRows(expectedTaskId, plan);
        RoundGateAuthorization authorization = rows.authorization();
        GatePayload payload = rows.payload();
        ObservedCode observed = observeCode(payload);
        requireRestoredCode(authorization, payload, observed);
        if (!plan.headSha().equals(observed.headSha())
                || !plan.codeFingerprint().equals(observed.fingerprint())) {
            throw conflict("round-gate recovery request no longer matches the worktree");
        }
        return plan;
    }

    /** Re-prove only durable rows inside the recovery command. The worktree
     * was checked immediately before entering the command while the caller
     * held the external-effect gate; git I/O must not run in the transaction. */
    private RecoveryRows verifyRecoveryRows(String expectedTaskId, RecoveryPlan plan)
    {
        if (!expectedTaskId.equals(plan.taskId())) {
            throw conflict("round-gate recovery authorization belongs to another task");
        }
        RoundGateAuthorization authorization = gates.findAuthorization(plan.token())
                .filter(RoundGateAuthorization::active)
                .filter(candidate -> expectedTaskId.equals(candidate.taskId()))
                .filter(candidate -> plan.roundId().equals(candidate.roundId()))
                .orElseThrow(() -> conflict(
                        "round-gate recovery authorization is no longer active"));
        Task task = requireTask(expectedTaskId);
        ReviewRound round = requireRound(plan.roundId());
        GatePayload payload = payload(authorization);
        if (task.phase() != TaskPhase.NEEDS_ATTENTION
                || task.status() != TaskStatus.NEEDS_ATTENTION
                || round.status() != ReviewRoundState.PAUSED
                || round.pausedFrom() != ReviewRoundState.AWAITING_GATE
                || !Objects.equals(plan.runId(), round.runId())
                || !plan.token().equals(round.activeGateToken())
                || authorization.gateRevision() != round.gateRevision()
                || authorization.attempt() != round.kickAttempt()
                || !authorization.codeFingerprint().equals(round.codeFingerprint())
                || !plan.headSha().equals(payload.headSha())
                || !plan.codeFingerprint().equals(payload.codeFingerprint())
                || !plan.codeFingerprint().equals(authorization.codeFingerprint())) {
            throw conflict("task and round are not parked for this round-gate recovery");
        }
        RoundGateEffect cursor = currentCursor(plan.token()).orElse(null);
        if (!Objects.equals(plan.effectKey(), cursor == null ? null : cursor.effectKey())) {
            throw conflict("round-gate recovery cursor changed");
        }
        if (RECOVERY_EFFECT_FAILED.equals(plan.reason())) {
            if (!failedCursor(cursor)
                    || plan.addedAllowance() < 1
                    || plan.addedAllowance() > MAX_RECOVERY_ALLOWANCE) {
                throw conflict("round-gate failure recovery no longer matches its cursor");
            }
        }
        else if (!RECOVERY_FINGERPRINT_MISMATCH.equals(plan.reason())
                || plan.addedAllowance() != 0
                || failedCursor(cursor)) {
            throw conflict("round-gate fingerprint recovery no longer matches its cursor");
        }
        return new RecoveryRows(authorization, payload);
    }

    private Optional<RoundGateEffect> currentCursor(String token)
    {
        return gates.findEffects(token).stream()
                .filter(effect -> !effect.completed())
                .findFirst();
    }

    private static boolean failedCursor(RoundGateEffect cursor)
    {
        return cursor != null
                && (cursor.status() == RoundGateEffect.Status.PERMANENT_FAILED
                        || cursor.exhausted());
    }

    private static void requireRestoredCode(
            RoundGateAuthorization authorization,
            GatePayload payload,
            ObservedCode observed)
    {
        if (!payload.headSha().equals(observed.headSha())
                || !authorization.codeFingerprint().equals(observed.fingerprint())) {
            throw conflict("restore the round gate's reviewed worktree before recovery");
        }
    }

    private GatePayload payload(RoundGateAuthorization authorization)
    {
        if (!authorization.payloadDigest().equals(sha256(authorization.payloadJson()))) {
            throw new IllegalStateException(
                    "round gate payload digest mismatch for " + authorization.token());
        }
        try {
            return mapper.readValue(authorization.payloadJson(), GatePayload.class);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "invalid payload for round gate " + authorization.token(), e);
        }
    }

    private boolean runnableAtGate(RoundGateAuthorization authorization)
    {
        Task task = tasks.findTaskById(authorization.taskId()).orElse(null);
        ReviewRound round = rounds.findById(authorization.roundId()).orElse(null);
        return task != null
                && task.phase() == TaskPhase.AWAITING_REMOTE_REVIEW
                && task.status() == TaskStatus.IN_REVIEW
                && round != null
                && round.status() == ReviewRoundState.AWAITING_GATE
                && authorization.token().equals(round.activeGateToken())
                && authorization.gateRevision() == round.gateRevision()
                && authorization.codeFingerprint().equals(round.codeFingerprint());
    }

    private ObservedCode observeCode(GatePayload payload)
    {
        Path worktree = Path.of(payload.worktreePath());
        return new ObservedCode(headSha(worktree), fingerprints.fingerprint(worktree));
    }

    private static boolean authorizedCodeMatches(
            RoundGateAuthorization authorization,
            GatePayload payload,
            ObservedCode observed)
    {
        return payload.headSha().equals(observed.headSha())
                && authorization.codeFingerprint().equals(observed.fingerprint());
    }

    private String headSha(Path worktree)
    {
        try {
            return git.headSha(worktree);
        }
        catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "reading task HEAD failed: " + e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "reading task HEAD interrupted");
        }
    }

    private void push(Path worktree)
    {
        try {
            git.push(worktree);
        }
        catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "git push failed: " + e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "git push interrupted");
        }
    }

    private ReviewRound requireRound(String roundId)
    {
        return rounds.findById(roundId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no round: " + roundId));
    }

    private Task requireTask(String taskId)
    {
        return tasks.findTaskById(taskId)
                .orElseThrow(() -> conflict("no task: " + taskId));
    }

    private static Path worktree(Task task)
    {
        String path = task.worktreePath() == null || task.worktreePath().isBlank()
                ? task.workingDir() : task.worktreePath();
        if (path == null || path.isBlank()) {
            throw conflict("task has no worktree path");
        }
        return Path.of(path);
    }

    private String json(Object value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("serializing round gate payload failed", e);
        }
    }

    private static String marker(String token, String effectKey)
    {
        return "<!-- bytequay-round-gate:" + token + ':' + effectKey + " -->";
    }

    private static boolean leaseExpired(RoundGateEffect effect)
    {
        return effect.leaseUntil() != null && !effect.leaseUntil().isAfter(Instant.now());
    }

    private static boolean retryDue(RoundGateEffect effect)
    {
        return effect.nextAttemptAt() == null || !effect.nextAttemptAt().isAfter(Instant.now());
    }

    private static Duration retryDelay(int attempts)
    {
        long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 5);
        Duration delay = RETRY_DELAY.multipliedBy(multiplier);
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }

    private static boolean permanent(RuntimeException failure)
    {
        if (!(failure instanceof ResponseStatusException response)) {
            return false;
        }
        int status = response.getStatusCode().value();
        return status >= 400 && status < 500 && status != 408 && status != 409 && status != 429;
    }

    private static String safeMessage(Throwable failure)
    {
        String message = Optional.ofNullable(failure.getMessage())
                .orElse(failure.getClass().getSimpleName());
        return message.length() <= 2_000 ? message : message.substring(0, 2_000);
    }

    private static String sha256(String value)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static ResponseStatusException conflict(String message)
    {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private enum ProbeResult
    {
        APPLIED,
        NOT_APPLIED
    }

    private record GatePayload(
            String taskId,
            String roundId,
            String worktreePath,
            String repo,
            int number,
            String branchName,
            String headSha,
            String codeFingerprint,
            List<ReplyPayload> replies,
            List<ResolvePayload> resolutions) {}

    private record ReplyPayload(
            String effectKey,
            String commentId,
            Long remoteCommentId,
            boolean general,
            String body) {}

    private record ResolvePayload(
            String effectKey,
            long remoteCommentId,
            List<String> commentIds) {}

    private record ObservedCode(String headSha, String fingerprint) {}

    private record RecoveryRows(
            RoundGateAuthorization authorization,
            GatePayload payload) {}

    public record RecoveryPlan(
            String taskId,
            String roundId,
            String runId,
            String token,
            String effectKey,
            String reason,
            int addedAllowance,
            String headSha,
            String codeFingerprint) {}
}
