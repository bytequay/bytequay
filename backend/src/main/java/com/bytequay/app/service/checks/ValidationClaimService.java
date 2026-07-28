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
package com.bytequay.app.service.checks;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.ValidationClaim;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.bytequay.app.service.review.ReviewRoundGateValidationFinishedEvent;
import com.bytequay.app.service.review.ReviewRoundValidationFinishedEvent;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static java.util.Objects.requireNonNull;

/**
 * Claimed validation: one durable, fingerprinted claim row owns one
 * validation of one exact code state, and checks run with no database
 * transaction active. The flow is the design's three steps — insert the
 * STARTED claim in a short task command and commit; admit at most one
 * executor (in-JVM single flight + cross-restart owner/lease CAS); and
 * finish through a terminal CAS that only the owner of the exact
 * fingerprint can win. Completion publishes the same
 * {@link ValidationPassFinishedEvent} the legacy path publishes, so the
 * phase machine's existing listener keeps working unchanged.
 *
 * <p>Legacy {@link ValidationPassService#run} remains for callers inside
 * ambient transactions (the brain-review paths) until the round machine
 * rewires them.
 */
@Service
public class ValidationClaimService
{
    private static final Logger log = LoggerFactory.getLogger(ValidationClaimService.class);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final long RENEW_PERIOD_MS = 30_000;
    private static final int RECOVERY_SCAN_LIMIT = 1_000;

    static final String CONTEXT_DEV_ROUND = "dev-round";
    static final String CONTEXT_LOCAL_REVIEW = "local-review";
    static final String CONTEXT_REVIEW_ROUND = "review-round";
    public static final String CONTEXT_GATE_REVALIDATION = "review-gate-revalidation";

    private final ValidationPassStore store;
    private final TaskStore taskStore;
    private final ThreadStore threadStore;
    private final ReviewRoundStore roundStore;
    private final ValidationPassService validation;
    private final CodeFingerprints fingerprints;
    private final ValidationExecutorRegistry registry;
    private final TaskCommandExecutor commands;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String executorIdentity;

    public ValidationClaimService(
            ValidationPassStore store,
            TaskStore taskStore,
            ThreadStore threadStore,
            ReviewRoundStore roundStore,
            ValidationPassService validation,
            CodeFingerprints fingerprints,
            ValidationExecutorRegistry registry,
            TaskCommandExecutor commands,
            ApplicationEventPublisher events,
            ObjectMapper mapper)
    {
        this.store = requireNonNull(store, "store is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.roundStore = requireNonNull(roundStore, "roundStore is null");
        this.validation = requireNonNull(validation, "validation is null");
        this.fingerprints = requireNonNull(fingerprints, "fingerprints is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.events = requireNonNull(events, "events is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = Clock.systemUTC();
        this.executorIdentity = ProcessHandle.current().pid() + "@" + hostName();
    }

    /**
     * Claim and run the normal dev-round validation for the task's
     * current code fingerprint. Idempotent per fingerprint: an existing
     * terminal claim replays its finished event; a live leased claim is
     * left to its owner.
     */
    public void claimAndRunDevRound(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (!canAdmit(task, TaskPhase.VALIDATING)
                || task.worktreePath() == null || task.worktreePath().isBlank()) {
            return;
        }
        String fingerprint = fingerprints.fingerprint(Path.of(task.worktreePath()));

        Admission admission = commands.execute(taskId, () -> {
            Task current = taskStore.findTaskById(taskId).orElse(null);
            if (!canAdmit(current, TaskPhase.VALIDATING)
                    || !task.worktreePath().equals(current.worktreePath())) {
                return null;
            }
            long epoch = validationEpoch(taskId);
            String baseKey = CONTEXT_DEV_ROUND + ":" + taskId + ":" + epoch + ":" + fingerprint;
            return new Admission(claim(
                    baseKey, taskId, CONTEXT_DEV_ROUND, fingerprint,
                    null, null, null), epoch);
        });
        if (admission == null) {
            return;
        }
        ValidationClaim claim = admission.claim();

        if (claim.endedAt() != null) {
            // Terminal already — replay the finished event so a dropped
            // consumer advances; the phase-machine listener is idempotent.
            publishFinished(taskId, claim, admission.epoch());
            return;
        }
        if (!claim.isLive()) {
            return; // cancelled or superseded — nothing to run
        }
        submit(taskId, claim.claimKey(),
                () -> runOwned(taskId, claim.claimKey(), fingerprint, admission.epoch()));
    }

    /** Resume/recovery holds green evidence while stopped, then replays
     * the same claim (or safely reacquires an unfinished one) after the
     * barrier commits. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onValidationRecheckRequested(ValidationRecheckRequestedEvent event)
    {
        TaskCommandExecutor.dispatchAfterCommit(() -> claimAndRunDevRound(event.taskId()));
    }

    /**
     * Claim and run the roots-closed local-review validation. The claim
     * identity additionally binds the task-wide submission watermark and
     * root-set digest, so a newer submission supersedes it rather than
     * consuming stale evidence. Publishes the dedicated local-review
     * event with the full identity for the acceptance command to
     * re-verify.
     */
    public void claimAndRunLocalReview(String taskId, long throughSequence, String rootSetDigest)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(rootSetDigest, "rootSetDigest is null");
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (!canAdmit(task, TaskPhase.ADDRESSING_LOCAL_COMMENTS)
                || task.worktreePath() == null || task.worktreePath().isBlank()) {
            return;
        }
        String fingerprint = fingerprints.fingerprint(Path.of(task.worktreePath()));
        String baseKey = CONTEXT_LOCAL_REVIEW + ":" + taskId + ":" + throughSequence
                + ":" + rootSetDigest + ":" + fingerprint;

        ValidationClaim claim = commands.execute(taskId, () -> {
            Task current = taskStore.findTaskById(taskId).orElse(null);
            if (!canAdmit(current, TaskPhase.ADDRESSING_LOCAL_COMMENTS)
                    || !task.worktreePath().equals(current.worktreePath())) {
                return null;
            }
            return claim(
                    baseKey, taskId, CONTEXT_LOCAL_REVIEW, fingerprint,
                    null, throughSequence, rootSetDigest);
        });
        if (claim == null) {
            return;
        }

        if (claim.endedAt() != null) {
            events.publishEvent(new LocalReviewValidationFinishedEvent(
                    taskId, claim.claimKey(), throughSequence, rootSetDigest, fingerprint,
                    Boolean.TRUE.equals(claim.passed())));
            return;
        }
        if (!claim.isLive()) {
            return;
        }
        submit(taskId, claim.claimKey(),
                () -> runOwnedLocalReview(
                        taskId, claim.claimKey(), throughSequence, rootSetDigest, fingerprint));
    }

    /** Claim post-addressing validation for one exact coordinator turn. */
    public void claimAndRunReviewRound(String taskId, String roundId, String attemptId)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(roundId, "roundId is null");
        requireNonNull(attemptId, "attemptId is null");
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (!canAdmitReviewRound(task)
                || task.worktreePath() == null || task.worktreePath().isBlank()) {
            return;
        }
        String fingerprint = fingerprints.fingerprint(Path.of(task.worktreePath()));
        String baseKey = CONTEXT_REVIEW_ROUND + ":" + taskId + ":" + roundId
                + ":" + attemptId + ":" + fingerprint;

        ValidationClaim claim = commands.execute(taskId, () -> {
            Task current = taskStore.findTaskById(taskId).orElse(null);
            if (!canAdmitReviewRound(current)
                    || !task.worktreePath().equals(current.worktreePath())) {
                return null;
            }
            return claim(
                    baseKey, taskId, CONTEXT_REVIEW_ROUND, fingerprint,
                    roundId, null, null);
        });
        if (claim == null) {
            return;
        }
        if (claim.endedAt() != null) {
            events.publishEvent(new ReviewRoundValidationFinishedEvent(
                    taskId, roundId, attemptId, claim.claimKey(), fingerprint,
                    Boolean.TRUE.equals(claim.passed())));
            return;
        }
        if (!claim.isLive()) {
            return;
        }
        submit(taskId, claim.claimKey(), () -> runOwnedReviewRound(
                taskId, roundId, attemptId, claim.claimKey(), fingerprint));
    }

    /** Drive the validation checkpoint created by a posting-gate fingerprint
     * invalidation. Returns true while validation, rather than a Brain turn,
     * owns TRIAGING. */
    public boolean claimAndRunGateRevalidation(String roundId)
    {
        requireNonNull(roundId, "roundId is null");
        ReviewRound snapshot = roundStore.findById(roundId).orElse(null);
        if (snapshot == null) {
            return false;
        }
        GateRevalidationAdmission admission = commands.execute(snapshot.taskId(), () -> {
            ReviewRound round = roundStore.findById(roundId).orElse(null);
            Task task = taskStore.findTaskById(snapshot.taskId()).orElse(null);
            ValidationClaim current = store.findLatestByRoundAndContext(
                    roundId, CONTEXT_GATE_REVALIDATION).orElse(null);
            if (!gateRevalidationOwed(round, current)) {
                return new GateRevalidationAdmission(false, null);
            }
            if (!canAdmit(task, TaskPhase.AWAITING_REMOTE_REVIEW)
                    || task.worktreePath() == null || task.worktreePath().isBlank()) {
                return new GateRevalidationAdmission(true, null);
            }
            String observed = fingerprints.fingerprint(Path.of(task.worktreePath()));
            if (observed.equals(current.codeFingerprint())) {
                return new GateRevalidationAdmission(true, current);
            }
            store.markSuperseded(current.claimKey(), clock.instant());
            String replacementKey = gateRevalidationClaimKey(
                    task.id(), round.id(), round.gateRevision(),
                    round.kickAttempt(), observed);
            return new GateRevalidationAdmission(true, claim(
                    replacementKey, task.id(), CONTEXT_GATE_REVALIDATION,
                    observed, round.id(), null, null));
        });
        if (!admission.owed()) {
            return false;
        }
        ValidationClaim claim = admission.claim();
        if (claim == null) {
            return true; // Owed, but frozen while the task is stopped.
        }
        if (claim.endedAt() != null) {
            events.publishEvent(new ReviewRoundGateValidationFinishedEvent(
                    claim.taskId(), roundId, claim.claimKey(), claim.codeFingerprint(),
                    Boolean.TRUE.equals(claim.passed())));
            return true;
        }
        if (!claim.isLive()) {
            return true;
        }
        submit(claim.taskId(), claim.claimKey(), () -> runOwnedGateRevalidation(
                claim.taskId(), roundId, claim.claimKey(), claim.codeFingerprint()));
        return true;
    }

    public static String gateRevalidationClaimKey(
            String taskId,
            String roundId,
            int gateRevision,
            int kickAttempt,
            String fingerprint)
    {
        return CONTEXT_GATE_REVALIDATION + ":" + taskId + ":" + roundId + ":"
                + gateRevision + ":" + kickAttempt + ":" + fingerprint;
    }

    /**
     * Recover the two crash windows around validation execution: a STARTED
     * claim whose owner disappeared, and a terminal dev claim whose finished
     * event was lost before its consumer advanced the phase. Local-review
     * terminal replay remains owned by TaskLifecycleDriver because it alone
     * has the current frozen root-set watermark.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void reconcileClaims()
    {
        for (Task task : taskStore.listByPhases(
                List.of(TaskPhase.VALIDATING), RECOVERY_SCAN_LIMIT)) {
            claimAndRunDevRound(task.id());
        }
        for (ValidationClaim claim : store.findResumableStarted(clock.instant())) {
            if (CONTEXT_LOCAL_REVIEW.equals(claim.context())
                    && claim.throughSequence() != null
                    && claim.rootSetDigest() != null) {
                resumeLocalReviewClaim(claim);
            }
            else if (CONTEXT_REVIEW_ROUND.equals(claim.context())
                    && claim.roundId() != null) {
                resumeReviewRoundClaim(claim);
            }
            else if (CONTEXT_GATE_REVALIDATION.equals(claim.context())
                    && claim.roundId() != null) {
                claimAndRunGateRevalidation(claim.roundId());
            }
        }
    }

    private void resumeLocalReviewClaim(ValidationClaim claim)
    {
        Task task = taskStore.findTaskById(claim.taskId()).orElse(null);
        if (!canAdmit(task, TaskPhase.ADDRESSING_LOCAL_COMMENTS)
                || task.worktreePath() == null || task.worktreePath().isBlank()) {
            return;
        }
        String currentFingerprint = fingerprints.fingerprint(Path.of(task.worktreePath()));
        if (!claim.codeFingerprint().equals(currentFingerprint)) {
            commands.executeVoid(task.id(), () ->
                    store.markSuperseded(claim.claimKey(), clock.instant()));
            claimAndRunLocalReview(
                    task.id(), claim.throughSequence(), claim.rootSetDigest());
            return;
        }
        submit(task.id(), claim.claimKey(), () -> runOwnedLocalReview(
                task.id(), claim.claimKey(), claim.throughSequence(),
                claim.rootSetDigest(), claim.codeFingerprint()));
    }

    private void resumeReviewRoundClaim(ValidationClaim claim)
    {
        Task task = taskStore.findTaskById(claim.taskId()).orElse(null);
        String attemptId = reviewAttemptId(claim);
        if (!canAdmitReviewRound(task) || attemptId == null
                || task.worktreePath() == null || task.worktreePath().isBlank()) {
            return;
        }
        String currentFingerprint = fingerprints.fingerprint(Path.of(task.worktreePath()));
        if (!claim.codeFingerprint().equals(currentFingerprint)) {
            commands.executeVoid(task.id(), () ->
                    store.markSuperseded(claim.claimKey(), clock.instant()));
            claimAndRunReviewRound(task.id(), claim.roundId(), attemptId);
            return;
        }
        submit(task.id(), claim.claimKey(), () -> runOwnedReviewRound(
                task.id(), claim.roundId(), attemptId,
                claim.claimKey(), claim.codeFingerprint()));
    }

    /**
     * Shared admission happens after the short claim-creation command has
     * committed and before the validator submits a worker or claims durable
     * ownership. A denial therefore leaves only the STARTED claim for the
     * existing recovery sweep.
     */
    private boolean submit(String taskId, String claimKey, Runnable work)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new IllegalStateException(
                        "validation task disappeared before admission: " + taskId));
        com.bytequay.app.domain.Thread trunk = threadStore.findThreadById(task.threadId())
                .orElseThrow(() -> new IllegalStateException(
                        "validation Trunk disappeared before admission: " + task.threadId()));
        String operationId = ValidationExecutorRegistry.operationId(claimKey);
        return registry.submitIfAbsent(
                claimKey,
                new CapacityManager.CapacityRequest(
                        operationId,
                        CapacityManager.WorkflowSource.LEGACY,
                        Set.of(CapacityManager.CapacityLane.VALIDATION),
                        new CapacityManager.CapacityScope(
                                trunk.workspaceId(), trunk.id(), task.id(), 1L),
                        false,
                        true,
                        false),
                work);
    }

    private static String reviewAttemptId(ValidationClaim claim)
    {
        String prefix = CONTEXT_REVIEW_ROUND + ":" + claim.taskId() + ":"
                + claim.roundId() + ":";
        if (!claim.claimKey().startsWith(prefix)) {
            return null;
        }
        String suffix = claim.claimKey().substring(prefix.length());
        int separator = suffix.indexOf(':');
        return separator <= 0 ? null : suffix.substring(0, separator);
    }

    private void runOwnedLocalReview(
            String taskId, String claimKey, long throughSequence, String rootSetDigest, String fingerprint)
    {
        String ownerId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        if (!store.acquireOwner(claimKey, ownerId, executorIdentity, now.plus(LEASE), now)) {
            return;
        }
        ScheduledFuture<?> renewal = registry.scheduleLeaseRenewal(
                () -> store.renewLease(
                        claimKey, ownerId, clock.instant().plus(LEASE), clock.instant()),
                RENEW_PERIOD_MS);
        boolean passed;
        List<ValidationFailure> failures;
        try {
            failures = validation.runChecks(taskId);
            passed = failures.isEmpty();
        }
        catch (RuntimeException e) {
            log.warn("claimed local-review validation {} failed to execute: {}",
                    claimKey, e.getMessage());
            failures = List.of(new ValidationFailure("validation", String.valueOf(e.getMessage())));
            passed = false;
        }
        finally {
            renewal.cancel(false);
        }
        boolean accepted = completeLocalReview(
                taskId, claimKey, ownerId, throughSequence, rootSetDigest,
                fingerprint, passed, failures);
        if (!accepted) {
            log.warn("claimed local-review validation {} lost ownership or became stale; result discarded",
                    claimKey);
        }
    }

    private void runOwnedReviewRound(
            String taskId,
            String roundId,
            String attemptId,
            String claimKey,
            String fingerprint)
    {
        String ownerId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        if (!store.acquireOwner(claimKey, ownerId, executorIdentity, now.plus(LEASE), now)) {
            return;
        }
        ScheduledFuture<?> renewal = registry.scheduleLeaseRenewal(
                () -> store.renewLease(
                        claimKey, ownerId, clock.instant().plus(LEASE), clock.instant()),
                RENEW_PERIOD_MS);
        boolean passed;
        List<ValidationFailure> failures;
        try {
            failures = validation.runChecks(taskId);
            passed = failures.isEmpty();
        }
        catch (RuntimeException e) {
            log.warn("claimed review-round validation {} failed to execute: {}",
                    claimKey, e.getMessage());
            failures = List.of(new ValidationFailure("validation", String.valueOf(e.getMessage())));
            passed = false;
        }
        finally {
            renewal.cancel(false);
        }
        boolean accepted = completeReviewRound(
                taskId, roundId, attemptId, claimKey, ownerId,
                fingerprint, passed, failures);
        if (!accepted) {
            log.warn("claimed review-round validation {} lost ownership or became stale; result discarded",
                    claimKey);
        }
    }

    private void runOwnedGateRevalidation(
            String taskId, String roundId, String claimKey, String fingerprint)
    {
        String ownerId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        if (!store.acquireOwner(claimKey, ownerId, executorIdentity, now.plus(LEASE), now)) {
            return;
        }
        ScheduledFuture<?> renewal = registry.scheduleLeaseRenewal(
                () -> store.renewLease(
                        claimKey, ownerId, clock.instant().plus(LEASE), clock.instant()),
                RENEW_PERIOD_MS);
        boolean passed;
        List<ValidationFailure> failures;
        try {
            failures = validation.runChecks(taskId);
            passed = failures.isEmpty();
        }
        catch (RuntimeException e) {
            log.warn("claimed gate revalidation {} failed to execute: {}",
                    claimKey, e.getMessage());
            failures = List.of(new ValidationFailure("validation", String.valueOf(e.getMessage())));
            passed = false;
        }
        finally {
            renewal.cancel(false);
        }
        if (!store.completeOwned(
                claimKey, ownerId, fingerprint, clock.instant(), passed, toJson(failures))) {
            log.warn("claimed gate revalidation {} lost ownership; result discarded", claimKey);
            return;
        }
        publishGateRevalidationIfCurrent(
                taskId, roundId, claimKey, fingerprint, passed);
    }

    private void publishGateRevalidationIfCurrent(
            String taskId,
            String roundId,
            String claimKey,
            String fingerprint,
            boolean passed)
    {
        commands.executeVoid(taskId, () -> {
            Task task = taskStore.findTaskById(taskId).orElse(null);
            ReviewRound round = roundStore.findById(roundId).orElse(null);
            ValidationClaim current = store.findLatestByRoundAndContext(
                    roundId, CONTEXT_GATE_REVALIDATION).orElse(null);
            if (!canAdmit(task, TaskPhase.AWAITING_REMOTE_REVIEW)
                    || !gateRevalidationOwed(round, current)
                    || !claimKey.equals(current.claimKey())
                    || task.worktreePath() == null || task.worktreePath().isBlank()
                    || !fingerprint.equals(fingerprints.fingerprint(Path.of(task.worktreePath())))) {
                return;
            }
            events.publishEvent(new ReviewRoundGateValidationFinishedEvent(
                    taskId, roundId, claimKey, fingerprint, passed));
        });
    }

    private static boolean gateRevalidationOwed(
            ReviewRound round, ValidationClaim claim)
    {
        return round != null
                && round.status() == ReviewRound.STATUS_TRIAGING
                && ReviewRound.ORIGIN_EXTERNAL.equals(round.origin())
                && claim != null
                && claim.roundId() != null
                && claim.roundId().equals(round.id())
                && !claim.codeFingerprint().equals(round.codeFingerprint())
                && gateRevalidationClaimKey(
                        round.taskId(), round.id(), round.gateRevision(),
                        round.kickAttempt(), claim.codeFingerprint())
                        .equals(claim.claimKey());
    }

    private void runOwned(
            String taskId, String claimKey, String fingerprint, long validationEpoch)
    {
        String ownerId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        if (!store.acquireOwner(claimKey, ownerId, executorIdentity, now.plus(LEASE), now)) {
            return; // someone else owns a live lease
        }
        ScheduledFuture<?> renewal = registry.scheduleLeaseRenewal(
                () -> store.renewLease(
                        claimKey, ownerId, clock.instant().plus(LEASE), clock.instant()),
                RENEW_PERIOD_MS);
        boolean passed;
        List<ValidationFailure> failures;
        try {
            failures = validation.runChecks(taskId);
            passed = failures.isEmpty();
        }
        catch (RuntimeException e) {
            log.warn("claimed validation {} failed to execute: {}", claimKey, e.getMessage());
            failures = List.of(new ValidationFailure("validation", String.valueOf(e.getMessage())));
            passed = false;
        }
        finally {
            renewal.cancel(false);
        }
        boolean accepted = completeDevRound(
                taskId, claimKey, ownerId, fingerprint, validationEpoch, passed, failures);
        if (!accepted) {
            log.warn("claimed validation {} lost ownership or became stale; result discarded", claimKey);
        }
    }

    private boolean completeDevRound(
            String taskId,
            String claimKey,
            String ownerId,
            String fingerprint,
            long validationEpoch,
            boolean passed,
            List<ValidationFailure> failures)
    {
        return commands.execute(taskId, () -> {
            if (!store.completeOwned(
                    claimKey, ownerId, fingerprint, clock.instant(), passed, toJson(failures))) {
                return false;
            }
            Task task = taskStore.findTaskById(taskId).orElse(null);
            if (!canAdmit(task, TaskPhase.VALIDATING)
                    || task.worktreePath() == null || task.worktreePath().isBlank()) {
                return true;
            }
            String observed = fingerprints.fingerprint(Path.of(task.worktreePath()));
            if (!fingerprint.equals(observed) || validationEpoch(taskId) != validationEpoch) {
                events.publishEvent(new ValidationRecheckRequestedEvent(taskId));
                return true;
            }
            events.publishEvent(new ValidationPassFinishedEvent(
                    taskId, passed, failures, claimKey, fingerprint, validationEpoch));
            return true;
        });
    }

    private boolean completeLocalReview(
            String taskId,
            String claimKey,
            String ownerId,
            long throughSequence,
            String rootSetDigest,
            String fingerprint,
            boolean passed,
            List<ValidationFailure> failures)
    {
        return commands.execute(taskId, () -> {
            if (!store.completeOwned(
                    claimKey, ownerId, fingerprint, clock.instant(), passed, toJson(failures))) {
                return false;
            }
            Task task = taskStore.findTaskById(taskId).orElse(null);
            if (!canAdmit(task, TaskPhase.ADDRESSING_LOCAL_COMMENTS)
                    || task.worktreePath() == null || task.worktreePath().isBlank()) {
                return true;
            }
            String observed = fingerprints.fingerprint(Path.of(task.worktreePath()));
            if (!fingerprint.equals(observed)) {
                return true;
            }
            events.publishEvent(new LocalReviewValidationFinishedEvent(
                    taskId, claimKey, throughSequence, rootSetDigest, fingerprint, passed));
            return true;
        });
    }

    private boolean completeReviewRound(
            String taskId,
            String roundId,
            String attemptId,
            String claimKey,
            String ownerId,
            String fingerprint,
            boolean passed,
            List<ValidationFailure> failures)
    {
        return commands.execute(taskId, () -> {
            if (!store.completeOwned(
                    claimKey, ownerId, fingerprint, clock.instant(), passed, toJson(failures))) {
                return false;
            }
            Task task = taskStore.findTaskById(taskId).orElse(null);
            if (!canAdmitReviewRound(task)
                    || task.worktreePath() == null || task.worktreePath().isBlank()) {
                return true;
            }
            String observed = fingerprints.fingerprint(Path.of(task.worktreePath()));
            if (!fingerprint.equals(observed)) {
                return true;
            }
            events.publishEvent(new ReviewRoundValidationFinishedEvent(
                    taskId, roundId, attemptId, claimKey, fingerprint, passed));
            return true;
        });
    }

    private void publishFinished(String taskId, ValidationClaim claim, long validationEpoch)
    {
        boolean passed = Boolean.TRUE.equals(claim.passed());
        events.publishEvent(new ValidationPassFinishedEvent(
                taskId, passed, passed ? List.of() : fromJson(claim.failuresJson()),
                claim.claimKey(), claim.codeFingerprint(), validationEpoch));
    }

    private long validationEpoch(String taskId)
    {
        return taskStore.listPhaseEvents(taskId).stream()
                .filter(event -> event.toPhase() == TaskPhase.VALIDATING)
                .mapToLong(event -> event.id())
                .max()
                .orElse(0L);
    }

    /** A stopped attempt stays immutable history. Its id deterministically
     * names the next attempt, so duplicate resume/recovery kicks converge
     * on one claim instead of reviving or duplicating the cancelled one. */
    private ValidationClaim claim(
            String baseKey,
            String taskId,
            String context,
            String fingerprint,
            String roundId,
            Long throughSequence,
            String rootSetDigest)
    {
        String claimKey = baseKey;
        while (true) {
            Optional<ValidationClaim> existing = store.findByClaimKey(claimKey);
            if (existing.isEmpty()) {
                store.insertClaim(
                        claimKey, taskId, context, roundId,
                        fingerprint, throughSequence, rootSetDigest, clock.instant());
                return store.findByClaimKey(claimKey).orElseThrow();
            }
            ValidationClaim claim = existing.orElseThrow();
            if (claim.endedAt() != null || claim.isLive()) {
                return claim;
            }
            claimKey = baseKey + ":after:" + claim.id();
        }
    }

    private static boolean canAdmit(Task task, TaskPhase phase)
    {
        if (task == null || task.phase() != phase) {
            return false;
        }
        return switch (task.status()) {
            case PAUSED, NEEDS_ATTENTION, COMPLETED, REMOTE_CLOSED,
                    ERRORED, CANCELED, ARCHIVED -> false;
            case PENDING, RUNNING, IDLE, AWAITING_REVIEW, IN_REVIEW -> true;
        };
    }

    private static boolean canAdmitReviewRound(Task task)
    {
        return canAdmit(task, TaskPhase.INTERNAL_REVIEW)
                || canAdmit(task, TaskPhase.AWAITING_REMOTE_REVIEW);
    }

    private record Admission(ValidationClaim claim, long epoch) {}

    private record GateRevalidationAdmission(boolean owed, ValidationClaim claim) {}

    private String toJson(List<ValidationFailure> failures)
    {
        try {
            return mapper.writeValueAsString(failures);
        }
        catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private List<ValidationFailure> fromJson(String json)
    {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(mapper.readValue(json, ValidationFailure[].class));
        }
        catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static String hostName()
    {
        String name = System.getenv("HOSTNAME");
        return name == null || name.isBlank() ? "local" : name;
    }
}
