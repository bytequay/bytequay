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

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.ReviewRoundState;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.ValidationClaim;
import com.bytequay.app.repository.LocalReviewBrainHandoffStore;
import com.bytequay.app.repository.LocalReviewSubmissionStore;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.localpr.LocalReviewClearedEvent;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.runs.AgentRunServiceImpl;
import com.bytequay.app.service.stage.RemoteDevelopmentStageService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.bytequay.app.statemachine.StateMachine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.bytequay.app.domain.ReviewRoundState.ADDRESSING;
import static com.bytequay.app.domain.ReviewRoundState.AWAITING_GATE;
import static com.bytequay.app.domain.ReviewRoundState.CLOSED;
import static com.bytequay.app.domain.ReviewRoundState.PAUSED;
import static com.bytequay.app.domain.ReviewRoundState.POSTED;
import static com.bytequay.app.domain.ReviewRoundState.TRIAGING;
import static java.util.Objects.requireNonNull;

/**
 * Sole writer of response-round lifecycle state. Every public intent enters
 * the task command boundary; callers already inside the same command use the
 * matching {@code ...InCommand} form.
 */
@Component
public class ReviewRoundStateMachine
{
    private static final String CONTEXT_GATE_REVALIDATION = "review-gate-revalidation";
    private static final String DEV_VALIDATION_CONTEXT = "dev-round";
    private static final String LOCAL_REVIEW_VALIDATION_CONTEXT = "local-review";
    private static final String ROUND_VALIDATION_CONTEXT = "review-round";
    private static final int MAX_DELIVERY_FAILURES = 2;

    private static final StateMachine<ReviewRoundState> GRAPH =
            StateMachine.<ReviewRoundState>builder("review round")
                    .edge(TRIAGING, ADDRESSING, AWAITING_GATE, PAUSED)
                    .edge(ADDRESSING, TRIAGING, PAUSED)
                    .edge(AWAITING_GATE, TRIAGING, ADDRESSING, POSTED, PAUSED)
                    .edge(POSTED)
                    .edge(PAUSED, TRIAGING, ADDRESSING, AWAITING_GATE)
                    .terminal(CLOSED)
                    .universal(CLOSED)
                    .build();

    public enum OwnedTurnAction
    {
        CONCLUDE,
        VALIDATE,
        RETRY,
        PAUSED,
        NONE
    }

    public record OwnedTurnEnded(
            ReviewRound round, ThreadTurn turn, OwnedTurnAction action)
    {
    }

    private final ReviewRoundStore rounds;
    private final AgentRunServiceImpl runs;
    private final TaskStore tasks;
    private final StageStore stages;
    private final PRService prs;
    private final ValidationPassStore validations;
    private final LocalReviewBrainHandoffStore handoffs;
    private final LocalReviewSubmissionStore submissions;
    private final ThreadTurnStore turns;
    private final RemoteDevelopmentStageService remoteStages;
    private final CodeFingerprints fingerprints;
    private final TaskPhaseMachine taskPhases;
    private final TaskCommandExecutor commands;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public ReviewRoundStateMachine(
            ReviewRoundStore rounds,
            AgentRunServiceImpl runs,
            TaskStore tasks,
            StageStore stages,
            PRService prs,
            ValidationPassStore validations,
            LocalReviewBrainHandoffStore handoffs,
            LocalReviewSubmissionStore submissions,
            ThreadTurnStore turns,
            RemoteDevelopmentStageService remoteStages,
            CodeFingerprints fingerprints,
            TaskPhaseMachine taskPhases,
            TaskCommandExecutor commands,
            ApplicationEventPublisher events)
    {
        this(rounds, runs, tasks, stages, prs, validations, handoffs,
                submissions, turns, remoteStages, fingerprints, taskPhases,
                commands, events, Clock.systemUTC());
    }

    ReviewRoundStateMachine(
            ReviewRoundStore rounds,
            AgentRunServiceImpl runs,
            TaskStore tasks,
            StageStore stages,
            PRService prs,
            ValidationPassStore validations,
            LocalReviewBrainHandoffStore handoffs,
            LocalReviewSubmissionStore submissions,
            ThreadTurnStore turns,
            RemoteDevelopmentStageService remoteStages,
            CodeFingerprints fingerprints,
            TaskPhaseMachine taskPhases,
            TaskCommandExecutor commands,
            ApplicationEventPublisher events,
            Clock clock)
    {
        this.rounds = requireNonNull(rounds, "rounds is null");
        this.runs = requireNonNull(runs, "runs is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.stages = requireNonNull(stages, "stages is null");
        this.prs = requireNonNull(prs, "prs is null");
        this.validations = requireNonNull(validations, "validations is null");
        this.handoffs = requireNonNull(handoffs, "handoffs is null");
        this.submissions = requireNonNull(submissions, "submissions is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.remoteStages = requireNonNull(remoteStages, "remoteStages is null");
        this.fingerprints = requireNonNull(fingerprints, "fingerprints is null");
        this.taskPhases = requireNonNull(taskPhases, "taskPhases is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.events = requireNonNull(events, "events is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public ReviewRound openBrain(
            String taskId,
            String prId,
            String validationClaimKey,
            boolean adoptOpenFindings)
    {
        return commands.execute(taskId, () -> openBrainInCommand(
                taskId, prId, validationClaimKey, adoptOpenFindings));
    }

    public ReviewRound openBrainInCommand(
            String taskId,
            String prId,
            String validationClaimKey,
            boolean adoptOpenFindings)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = requireRunnableTask(taskId, TaskPhase.INTERNAL_REVIEW);
        PR pr = prs.findById(prId)
                .filter(candidate -> taskId.equals(candidate.taskId()))
                .filter(candidate -> PR.STATUS_LOCAL_DRAFTED.equals(candidate.status())
                        || PR.STATUS_LOCAL_OPEN.equals(candidate.status()))
                .orElseThrow(() -> conflict("local PR does not belong to task " + taskId));
        LocalReviewBrainHandoffStore.Handoff handoff = validationClaimKey == null
                ? handoffs.listUnconsumedByTask(taskId).stream().findFirst().orElse(null)
                : handoffs.listUnconsumedByTask(taskId).stream()
                        .filter(candidate -> validationClaimKey.equals(candidate.validationClaimKey()))
                        .findFirst()
                        .orElse(null);
        String effectiveClaimKey = validationClaimKey != null
                ? validationClaimKey
                : handoff == null ? null : handoff.validationClaimKey();
        ValidationClaim claim = effectiveClaimKey == null
                ? validations.findLatestGreenByTaskAndContext(taskId, DEV_VALIDATION_CONTEXT)
                        .filter(this::isBrainEntryClaim)
                        .orElseThrow(() -> conflict(
                                "task " + taskId + " has no current green validation claim"))
                : requireGreenClaim(taskId, effectiveClaimKey);
        if (!isBrainEntryClaim(claim)) {
            throw conflict("validation claim cannot open a Brain round: " + claim.claimKey());
        }
        String fingerprint = claim.codeFingerprint();
        requireCurrentFingerprint(task, fingerprint);

        if (handoff != null && !Objects.equals(handoff.codeFingerprint(), fingerprint)) {
            throw conflict("local-review handoff fingerprint no longer matches its validation");
        }

        ReviewRound existing = findCoordinatorRound(taskId).orElse(null);
        if (existing != null) {
            if (ReviewRound.ORIGIN_BRAIN.equals(existing.origin())
                    && existing.id().equals(claim.roundId())) {
                consumeHandoff(handoff, existing.runId());
                return existing;
            }
            throw conflict("task " + taskId + " already owns live round " + existing.id());
        }

        String roundId = UUID.randomUUID().toString();
        String parentStageId = stages.findStageByType(taskId, StageType.DEVELOPMENT_STAGE)
                .map(StageInstance::id)
                .map(UUID::toString)
                .orElse(null);
        AgentRun run = runs.openInCommand(
                taskId, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL,
                parentStageId, StageType.REVIEW_ROUND_STAGE, null);
        run = queueNewRun(taskId, run);
        ReviewRoundState state = adoptOpenFindings ? ADDRESSING : TRIAGING;
        ReviewRound opened = new ReviewRound(
                roundId, taskId, rounds.nextIndex(taskId), List.of(), state,
                ReviewRound.ReviewRoundStats.empty(), run.id(), now(), null, null,
                ReviewRound.ORIGIN_BRAIN, null, adoptOpenFindings ? 0 : 1,
                ReviewRound.DEFAULT_BRAIN_BUDGET, null, fingerprint,
                0, 0, 0, null, null);
        rounds.insert(opened);
        if (!validations.bindRoundIfUnbound(claim.claimKey(), roundId)) {
            throw conflict("validation claim is already bound to another Brain round: "
                    + claim.claimKey());
        }
        consumeHandoff(handoff, run.id());
        events.publishEvent(new ReviewRoundOpenedEvent(
                taskId, roundId, pr.id(), ReviewRound.ORIGIN_BRAIN,
                effectiveClaimKey, state));
        return opened;
    }

    public ReviewRound openExternal(
            String taskId, String prId, List<UUID> commentIds)
    {
        return commands.execute(taskId,
                () -> openExternalInCommand(taskId, prId, commentIds));
    }

    public ReviewRound openExternalInCommand(
            String taskId, String prId, List<UUID> commentIds)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = requireRunnableTask(taskId, TaskPhase.AWAITING_REMOTE_REVIEW);
        if (task.status() != TaskStatus.IN_REVIEW) {
            throw conflict("task " + taskId + " is not in remote review");
        }
        PR pr = prs.findById(prId)
                .filter(candidate -> taskId.equals(candidate.taskId()))
                .filter(candidate -> PR.STATUS_REMOTE_DRAFTED.equals(candidate.status())
                        || PR.STATUS_REMOTE_OPEN.equals(candidate.status()))
                .orElseThrow(() -> conflict("remote PR does not belong to task " + taskId));
        List<UUID> frozenIds = List.copyOf(requireNonNull(commentIds, "commentIds is null"));
        if (frozenIds.isEmpty() || new HashSet<>(frozenIds).size() != frozenIds.size()) {
            throw conflict("external round requires a nonempty unique comment batch");
        }
        List<ReviewComment> comments = frozenIds.stream()
                .map(id -> stages.findReviewCommentById(id)
                        .orElseThrow(() -> conflict("review comment not found: " + id)))
                .toList();
        if (comments.stream().anyMatch(comment -> !taskId.equals(comment.taskId())
                || comment.source() != ReviewCommentSource.REMOTE_REVIEWER
                || comment.roundId() != null)) {
            throw conflict("external round batch contains ineligible comments");
        }

        String fingerprint = currentFingerprint(task);
        ReviewRound existing = findCoordinatorRound(taskId).orElse(null);
        if (existing != null) {
            Set<UUID> existingIds = new HashSet<>(stages.findCommentsByRound(
                    UUID.fromString(existing.id())).stream().map(ReviewComment::id).toList());
            if (ReviewRound.ORIGIN_EXTERNAL.equals(existing.origin())
                    && existingIds.equals(new HashSet<>(frozenIds))) {
                return existing;
            }
            throw conflict("task " + taskId + " already owns live round " + existing.id());
        }

        String roundId = UUID.randomUUID().toString();
        StageInstance remote = remoteStages.ensureOpenInCommand(taskId);
        AgentRun run = runs.openInStageInCommand(
                taskId, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_REMOTE,
                remote.id().toString(), null);
        run = queueNewRun(taskId, run);
        ReviewRound opened = new ReviewRound(
                roundId, taskId, rounds.nextIndex(taskId), List.of(), ADDRESSING,
                new ReviewRound.ReviewRoundStats(0, 0, 0, frozenIds.size()),
                run.id(), now(), null, null, ReviewRound.ORIGIN_EXTERNAL,
                null, 0, ReviewRound.DEFAULT_BRAIN_BUDGET, null, fingerprint,
                0, 0, 0, null, null);
        rounds.insert(opened);
        stages.assignCommentsToRound(frozenIds, UUID.fromString(roundId));
        events.publishEvent(new ReviewRoundOpenedEvent(
                taskId, roundId, pr.id(), ReviewRound.ORIGIN_EXTERNAL, null, ADDRESSING));
        return opened;
    }

    public ReviewRound concludeBrain(String roundId, String attemptId)
    {
        ReviewRound round = requireRound(roundId);
        return commands.execute(round.taskId(),
                () -> concludeBrainInCommand(round.taskId(), roundId, attemptId));
    }

    public ReviewRound concludeBrainInCommand(
            String taskId, String roundId, String attemptId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        ReviewRound round = requireOwnedRound(taskId, roundId);
        if (round.status() != TRIAGING) {
            throw conflict("round " + roundId + " is not waiting for a Brain conclusion");
        }
        ThreadTurn attempt = requireCompletedAttempt(round, attemptId, "brain-review");
        AgentRun run = requireQueuedRun(round);
        Task task = requireRunnableTask(taskId, ReviewRound.ORIGIN_BRAIN.equals(round.origin())
                ? TaskPhase.INTERNAL_REVIEW : TaskPhase.AWAITING_REMOTE_REVIEW);
        requireCurrentFingerprint(task, round.codeFingerprint());

        ReviewRound.ReviewRoundStats previous = round.stats() == null
                ? ReviewRound.ReviewRoundStats.empty() : round.stats();
        ReviewRound.ReviewRoundStats stats;
        if (ReviewRound.ORIGIN_BRAIN.equals(round.origin())) {
            int open = openBrainFindings(taskId);
            stats = new ReviewRound.ReviewRoundStats(
                    previous.fixed(), previous.replied(), previous.pushedBack(), open);
        }
        else {
            stats = externalStats(round);
        }
        int open = stats.open();
        String verdict = round.brainVerdict();
        if (ReviewRound.VERDICT_APPROVED.equals(verdict) && open > 0) {
            verdict = ReviewRound.VERDICT_CHANGES_REQUESTED;
        }
        if (!ReviewRound.VERDICT_APPROVED.equals(verdict)
                && !ReviewRound.VERDICT_CHANGES_REQUESTED.equals(verdict)) {
            throw conflict("round " + roundId + " has no terminal Brain verdict");
        }
        boolean approved = ReviewRound.VERDICT_APPROVED.equals(verdict) && open == 0;
        boolean exhausted = round.brainBudgetExhausted();
        boolean brainOrigin = ReviewRound.ORIGIN_BRAIN.equals(round.origin());
        ReviewRoundState target = brainOrigin
                ? approved || exhausted ? CLOSED : ADDRESSING
                : approved || exhausted ? AWAITING_GATE : ADDRESSING;
        GRAPH.checkTransition(roundId, TRIAGING, target);
        Instant at = now();
        if (!rounds.concludeIf(
                roundId, TRIAGING, target,
                attemptFence(round, attempt, "brain-review"), stats, verdict,
                target == AWAITING_GATE ? at : null,
                target == CLOSED ? at : null)) {
            throw changed(roundId, "concluding");
        }
        String runTarget = switch (target) {
            case CLOSED -> AgentRun.STATUS_SUCCEEDED;
            case AWAITING_GATE -> AgentRun.STATUS_AWAITING_GATE;
            case ADDRESSING -> AgentRun.STATUS_QUEUED;
            default -> throw new IllegalStateException("unexpected conclusion target " + target);
        };
        if (!AgentRun.STATUS_QUEUED.equals(runTarget)) {
            runs.transitionInCommand(taskId, run.id(), runTarget,
                    target == CLOSED ? "brain_review_concluded" : "drafts_ready");
        }
        publish(round, target, "brain_review_concluded");

        if (brainOrigin && target == CLOSED) {
            PR pr = prs.findByTask(taskId)
                    .orElseThrow(() -> conflict("task " + taskId + " has no local PR"));
            if (PR.STATUS_LOCAL_DRAFTED.equals(pr.status())) {
                pr = prs.requestUserReview(pr.id(), "brain");
            }
            if (task.phase() == TaskPhase.INTERNAL_REVIEW) {
                taskPhases.transitionInCommand(
                        taskId, TaskPhase.AWAITING_PUSH,
                        PR.STATUS_LOCAL_OPEN.equals(pr.status())
                                ? "local_review_reverified" : "local_review_opened",
                        Actor.AGENT);
            }
            events.publishEvent(new BrainRoundConcludedEvent(
                    taskId, roundId, pr.id(), approved, verdict, open, attemptId));
            events.publishEvent(new LocalReviewClearedEvent(taskId, pr.id(), approved));
        }
        return requireRound(roundId);
    }

    public ReviewRound finishAddressing(
            String roundId, String attemptId, String validationClaimKey)
    {
        ReviewRound round = requireRound(roundId);
        return commands.execute(round.taskId(), () -> finishAddressingInCommand(
                round.taskId(), roundId, attemptId, validationClaimKey));
    }

    public ReviewRound finishAddressingInCommand(
            String taskId,
            String roundId,
            String attemptId,
            String validationClaimKey)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        ReviewRound round = requireOwnedRound(taskId, roundId);
        if (round.status() != ADDRESSING) {
            throw conflict("round " + roundId + " is not addressing findings");
        }
        ThreadTurn attempt = requireCompletedAddressingAttempt(round, attemptId);
        requireQueuedRun(round);
        ValidationClaim claim = requireGreenClaim(taskId, validationClaimKey);
        requireExactRoundValidationClaim(round, attemptId, claim);
        Task task = requireRunnableTask(taskId, ReviewRound.ORIGIN_BRAIN.equals(round.origin())
                ? TaskPhase.INTERNAL_REVIEW : TaskPhase.AWAITING_REMOTE_REVIEW);
        requireCurrentFingerprint(task, claim.codeFingerprint());
        if (ReviewRound.ORIGIN_BRAIN.equals(round.origin())) {
            if (openBrainFindings(taskId) != 0) {
                throw conflict("Brain findings remain open for round " + roundId);
            }
        }
        else if (openExternalFindings(round) != 0) {
            throw conflict("reviewer findings remain open for round " + roundId);
        }
        if (!rounds.finishAddressingIf(
                roundId, attemptFence(round, attempt, addressingSource(round)),
                claim.claimKey(), claim.codeFingerprint())) {
            throw changed(roundId, "finishing addressing");
        }
        publish(round, TRIAGING, "addressing_validated");
        return requireRound(roundId);
    }

    public ReviewRound recordVerdict(
            String roundId, String attemptId, String verdict)
    {
        ReviewRound round = requireRound(roundId);
        return commands.execute(round.taskId(), () -> recordVerdictInCommand(
                round.taskId(), roundId, attemptId, verdict));
    }

    public ReviewRound recordVerdictInCommand(
            String taskId, String roundId, String attemptId, String verdict)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        ReviewRound round = requireOwnedRound(taskId, roundId);
        if (round.status() != TRIAGING) {
            throw conflict("round " + roundId + " is not being reviewed");
        }
        requireCurrentKick(round, attemptId, "brain-review");
        ThreadTurn attempt = turns.findTurnById(attemptId)
                .orElseThrow(() -> conflict("review attempt not found: " + attemptId));
        if (!Objects.equals(round.runId(), attempt.agentRunId())
                || attempt.initiator() == null
                || !"brain-review".equals(attempt.initiator().source())
                || (attempt.status() != ThreadTurnStatus.RUNNING
                        && attempt.status() != ThreadTurnStatus.COMPLETED)) {
            throw conflict("review attempt does not belong to the active round kick " + roundId);
        }
        String effective = ReviewRound.VERDICT_APPROVED.equals(verdict)
                && openBrainFindings(taskId) > 0
                ? ReviewRound.VERDICT_CHANGES_REQUESTED
                : verdict;
        if (!ReviewRound.VERDICT_APPROVED.equals(effective)
                && !ReviewRound.VERDICT_CHANGES_REQUESTED.equals(effective)) {
            throw conflict("unsupported Brain verdict: " + verdict);
        }
        if (!rounds.updateBrainVerdictIf(roundId, TRIAGING, effective)) {
            throw changed(roundId, "recording its verdict");
        }
        return requireRound(roundId);
    }

    public OwnedTurnEnded recordOwnedTurnEnded(String roundId, String turnId)
    {
        ReviewRound round = requireRound(roundId);
        return commands.execute(round.taskId(), () -> recordOwnedTurnEndedInCommand(
                round.taskId(), roundId, turnId));
    }

    public OwnedTurnEnded recordOwnedTurnEndedInCommand(
            String taskId, String roundId, String turnId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        ReviewRound round = requireOwnedRound(taskId, roundId);
        ThreadTurn turn = turns.findTurnById(turnId)
                .orElseThrow(() -> conflict("owned turn not found: " + turnId));
        if (!taskId.equals(turn.taskId()) || !Objects.equals(round.runId(), turn.agentRunId())) {
            throw conflict("turn " + turnId + " is not owned by round " + roundId);
        }
        if (turn.status() != ThreadTurnStatus.COMPLETED
                && turn.status() != ThreadTurnStatus.FAILED
                && turn.status() != ThreadTurnStatus.CANCELLED) {
            throw conflict("turn " + turnId + " is not terminal");
        }
        String source = turn.initiator() == null ? "" : turn.initiator().source();
        String expectedSource = round.status() == TRIAGING
                ? "brain-review" : addressingSource(round);
        String expectedKickKey = kickKey(round, expectedSource);
        if (!source.equals(expectedSource)
                || turns.findTurnIdByKickKey(expectedKickKey)
                        .filter(turnId::equals)
                        .isEmpty()) {
            return new OwnedTurnEnded(round, turn, OwnedTurnAction.NONE);
        }
        if (!round.status().isLive() || round.status() == AWAITING_GATE) {
            return new OwnedTurnEnded(round, turn, OwnedTurnAction.NONE);
        }
        AgentRun run = requireOwningRun(round);
        if (AgentRun.STATUS_PAUSED.equals(run.status())) {
            if (round.status() != PAUSED) {
                if (!rounds.parkIf(roundId, round.status())) {
                    throw changed(roundId, "preserving its budget pause");
                }
                publish(round, PAUSED, "review_budget_paused");
            }
            Task task = tasks.findTaskById(taskId).orElse(null);
            if (task != null && !stopped(task)) {
                taskPhases.pauseInCommand(taskId, Actor.AGENT, "review_budget_paused");
            }
            return new OwnedTurnEnded(requireRound(roundId), turn, OwnedTurnAction.PAUSED);
        }
        if (AgentRun.STATUS_RUNNING.equals(run.status())) {
            run = runs.transitionInCommand(
                    taskId, run.id(), AgentRun.STATUS_QUEUED, "review_attempt_finished");
        }
        if (!AgentRun.STATUS_QUEUED.equals(run.status())) {
            return new OwnedTurnEnded(round, turn, OwnedTurnAction.NONE);
        }
        if (turn.status() == ThreadTurnStatus.FAILED
                || turn.status() == ThreadTurnStatus.CANCELLED) {
            if (!rounds.recordDeliveryFailureIf(
                    roundId, round.status(), round.kickAttempt())) {
                throw changed(roundId, "recording attempt failure");
            }
            ReviewRound failed = requireRound(roundId);
            if (failed.enqueueFailures() >= MAX_DELIVERY_FAILURES) {
                parkInCommand(taskId, roundId, "review_turn_failed");
                Task task = tasks.findTaskById(taskId).orElse(null);
                if (task != null && !stopped(task)) {
                    taskPhases.parkOperationalInCommand(
                            taskId, Actor.AGENT, "review_turn_failed");
                }
                return new OwnedTurnEnded(requireRound(roundId), turn, OwnedTurnAction.PAUSED);
            }
            return new OwnedTurnEnded(failed, turn, OwnedTurnAction.RETRY);
        }
        OwnedTurnAction action = round.status() == TRIAGING && "brain-review".equals(source)
                ? OwnedTurnAction.CONCLUDE
                : round.status() == ADDRESSING
                        && ("brain-review-fix".equals(source) || "review-round".equals(source))
                                ? OwnedTurnAction.VALIDATE
                                : OwnedTurnAction.NONE;
        return new OwnedTurnEnded(round, turn, action);
    }

    public ReviewRound recordDeliveryFailure(
            String roundId, int expectedKickAttempt, String reason)
    {
        ReviewRound round = requireRound(roundId);
        return commands.execute(round.taskId(), () -> recordDeliveryFailureInCommand(
                round.taskId(), roundId, expectedKickAttempt, reason));
    }

    public ReviewRound recordDeliveryFailureInCommand(
            String taskId, String roundId, int expectedKickAttempt, String reason)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        ReviewRound round = requireOwnedRound(taskId, roundId);
        if (round.status() != TRIAGING && round.status() != ADDRESSING) {
            return round;
        }
        if (!rounds.recordDeliveryFailureIf(
                roundId, round.status(), expectedKickAttempt)) {
            return requireRound(roundId);
        }
        ReviewRound failed = requireRound(roundId);
        if (failed.enqueueFailures() < MAX_DELIVERY_FAILURES) {
            return failed;
        }
        ReviewRound parked = parkInCommand(taskId, roundId, reason);
        Task task = tasks.findTaskById(taskId).orElse(null);
        if (task != null && !stopped(task)) {
            taskPhases.parkOperationalInCommand(taskId, Actor.AGENT, reason);
        }
        return parked;
    }

    public ReviewRound recordKickAdmittedInCommand(
            String taskId,
            String roundId,
            ReviewRoundState expectedState,
            int expectedKickAttempt)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        ReviewRound round = requireOwnedRound(taskId, roundId);
        if (round.status() != expectedState || round.kickAttempt() != expectedKickAttempt) {
            throw changed(roundId, "recording its admitted kick");
        }
        if (round.enqueueFailures() == 0) {
            return round;
        }
        if (!rounds.clearEnqueueFailuresIf(roundId, expectedState, expectedKickAttempt)) {
            throw changed(roundId, "recording its admitted kick");
        }
        return requireRound(roundId);
    }

    public ReviewRound park(String roundId, String reason)
    {
        ReviewRound round = requireRound(roundId);
        return commands.execute(round.taskId(),
                () -> parkInCommand(round.taskId(), roundId, reason));
    }

    public ReviewRound parkInCommand(String taskId, String roundId, String reason)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        ReviewRound round = requireOwnedRound(taskId, roundId);
        if (round.status() == PAUSED) {
            return round;
        }
        GRAPH.checkTransition(roundId, round.status(), PAUSED);
        AgentRun run = requireOwningRun(round);
        if (!parkableRun(run.status())) {
            throw conflict("round " + roundId + " run is not parkable from " + run.status());
        }
        if (!rounds.parkIf(roundId, round.status())) {
            throw changed(roundId, "parking");
        }
        runs.pauseInCommand(taskId, run.id(), reason);
        publish(round, PAUSED, reason);
        return requireRound(roundId);
    }

    public ReviewRound resume(String roundId, String reason)
    {
        ReviewRound round = requireRound(roundId);
        return commands.execute(round.taskId(),
                () -> resumeInCommand(round.taskId(), roundId, reason));
    }

    public ReviewRound resumeInCommand(String taskId, String roundId, String reason)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        ReviewRound round = requireOwnedRound(taskId, roundId);
        if (round.status() != PAUSED || round.pausedFrom() == null) {
            throw conflict("round " + roundId + " has no unambiguous pause checkpoint");
        }
        ReviewRoundState target = round.pausedFrom();
        GRAPH.checkTransition(roundId, PAUSED, target);
        AgentRun run = requireOwningRun(round);
        if (!AgentRun.STATUS_PAUSED.equals(run.status())) {
            throw conflict("round " + roundId + " run is not paused");
        }
        if (!rounds.resumeIf(roundId, target)) {
            throw changed(roundId, "resuming");
        }
        AgentRun replacement = runs.restartInCommand(taskId, run.id());
        rounds.updateRunId(roundId, replacement.id());
        if (target == AWAITING_GATE) {
            runs.transitionInCommand(
                    taskId, replacement.id(), AgentRun.STATUS_AWAITING_GATE, "review_resumed");
        }
        publish(round, target, reason);
        return requireRound(roundId);
    }

    public ReviewRound seal(String roundId, String reason)
    {
        ReviewRound round = requireRound(roundId);
        return commands.execute(round.taskId(),
                () -> sealInCommand(round.taskId(), roundId, reason));
    }

    public ReviewRound sealInCommand(String taskId, String roundId, String reason)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        ReviewRound round = requireOwnedRound(taskId, roundId);
        if (round.status() == CLOSED) {
            return round;
        }
        GRAPH.checkTransition(roundId, round.status(), CLOSED);
        if (!rounds.sealIf(roundId, round.status(), now())) {
            ReviewRound current = requireRound(roundId);
            if (current.status() == CLOSED) {
                return current;
            }
            throw changed(roundId, "sealing");
        }
        publish(round, CLOSED, reason);
        return requireRound(roundId);
    }

    public ReviewRound post(String roundId)
    {
        ReviewRound round = requireRound(roundId);
        if (round.activeGateToken() == null) {
            throw conflict("round " + roundId + " has no active gate authorization");
        }
        return post(roundId, round.activeGateToken(), "round_posted");
    }

    public ReviewRound post(String roundId, String token, String reason)
    {
        ReviewRound round = requireRound(roundId);
        return commands.execute(round.taskId(),
                () -> postInCommand(round.taskId(), roundId, token, reason));
    }

    public ReviewRound postInCommand(String taskId, String roundId)
    {
        ReviewRound round = requireOwnedRound(taskId, roundId);
        if (round.activeGateToken() == null) {
            throw conflict("round " + roundId + " has no active gate authorization");
        }
        return postInCommand(taskId, roundId, round.activeGateToken(), "round_posted");
    }

    public ReviewRound postInCommand(
            String taskId, String roundId, String token, String reason)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        requireNonNull(reason, "reason is null");
        ReviewRound round = requireOwnedRound(taskId, roundId);
        Task task = requireRunnableTask(taskId, TaskPhase.AWAITING_REMOTE_REVIEW);
        AgentRun run = requireOwningRun(round);
        if (task.status() != TaskStatus.IN_REVIEW
                || round.status() != AWAITING_GATE
                || !AgentRun.STATUS_AWAITING_GATE.equals(run.status())
                || token == null || !token.equals(round.activeGateToken())) {
            throw conflict("round " + roundId + " is not at its posting gate");
        }
        if (!rounds.postIf(roundId, token, now())) {
            throw changed(roundId, "posting");
        }
        runs.transitionInCommand(taskId, run.id(), AgentRun.STATUS_SUCCEEDED, "round_posted");
        taskPhases.transitionInCommand(
                task.id(), TaskPhase.PUSHED_AWAITING_CI, reason, Actor.HUMAN);
        publish(round, POSTED, reason);
        return requireRound(roundId);
    }

    public ReviewRound authorizeGate(
            String roundId,
            String token,
            int expectedGateRevision,
            String codeFingerprint,
            String reason)
    {
        ReviewRound round = requireRound(roundId);
        return commands.execute(round.taskId(), () -> authorizeGateInCommand(
                round.taskId(), roundId, token, expectedGateRevision,
                codeFingerprint, reason));
    }

    public ReviewRound authorizeGateInCommand(
            String taskId,
            String roundId,
            String token,
            int expectedGateRevision,
            String codeFingerprint,
            String reason)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        if (token == null || token.isBlank()
                || codeFingerprint == null || codeFingerprint.isBlank()) {
            throw new IllegalArgumentException("gate token and code fingerprint are required");
        }
        requireNonNull(reason, "reason is null");
        Task task = requireRunnableTask(taskId, TaskPhase.AWAITING_REMOTE_REVIEW);
        ReviewRound round = requireOwnedRound(taskId, roundId);
        AgentRun run = requireOwningRun(round);
        if (task.status() != TaskStatus.IN_REVIEW
                || round.status() != AWAITING_GATE
                || !AgentRun.STATUS_AWAITING_GATE.equals(run.status())
                || round.gateRevision() != expectedGateRevision
                || !codeFingerprint.equals(round.codeFingerprint())) {
            throw conflict("round " + roundId + " is not authorizable at this gate revision");
        }
        if (token.equals(round.activeGateToken())) {
            events.publishEvent(new RoundGateAuthorizedEvent(taskId, roundId, token));
            return round;
        }
        if (round.activeGateToken() != null
                || !rounds.authorizeGateIf(
                        roundId, expectedGateRevision, codeFingerprint, token)) {
            throw changed(roundId, "authorizing its gate");
        }
        events.publishEvent(new RoundGateAuthorizedEvent(taskId, roundId, token));
        return requireRound(roundId);
    }

    public ReviewRound requestGateChanges(
            String roundId, String instruction, int additionalBudget)
    {
        ReviewRound round = requireRound(roundId);
        return commands.execute(round.taskId(), () -> requestGateChangesInCommand(
                round.taskId(), roundId, instruction, additionalBudget));
    }

    public ReviewRound requestGateChangesInCommand(
            String taskId, String roundId, String instruction, int additionalBudget)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        if (instruction == null || instruction.isBlank() || additionalBudget < 0) {
            throw new IllegalArgumentException("gate-change instruction and nonnegative budget are required");
        }
        requireRunnableTask(taskId, TaskPhase.AWAITING_REMOTE_REVIEW);
        ReviewRound round = requireOwnedRound(taskId, roundId);
        AgentRun run = requireOwningRun(round);
        if (round.status() != AWAITING_GATE
                || !AgentRun.STATUS_AWAITING_GATE.equals(run.status())) {
            throw conflict("round " + roundId + " is not revisable");
        }
        if (!rounds.requestGateChangesIf(roundId, additionalBudget)) {
            throw changed(roundId, "revising its gate");
        }
        runs.transitionInCommand(
                taskId, run.id(), AgentRun.STATUS_QUEUED, "round_gate_changes_requested");
        publish(round, ADDRESSING, "round_gate_changes_requested");
        return requireRound(roundId);
    }

    public ReviewRound invalidateGateFingerprint(
            String roundId, String token, String observedFingerprint)
    {
        ReviewRound round = requireRound(roundId);
        return commands.execute(round.taskId(), () -> invalidateGateFingerprintInCommand(
                round.taskId(), roundId, token, observedFingerprint));
    }

    public ReviewRound invalidateGateFingerprintInCommand(
            String taskId, String roundId, String token, String observedFingerprint)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = requireRunnableTask(taskId, TaskPhase.AWAITING_REMOTE_REVIEW);
        ReviewRound round = requireOwnedRound(taskId, roundId);
        AgentRun run = requireOwningRun(round);
        String currentFingerprint = currentFingerprint(task);
        ValidationClaim priorClaim = validations.findLatestByRoundAndContext(
                roundId, CONTEXT_GATE_REVALIDATION).orElse(null);
        if (round.status() == TRIAGING
                && AgentRun.STATUS_QUEUED.equals(run.status())
                && priorClaim != null
                && observedFingerprint != null
                && observedFingerprint.equals(currentFingerprint)
                && observedFingerprint.equals(priorClaim.codeFingerprint())
                && gateRevalidationClaimKey(
                        taskId, roundId, round.gateRevision(),
                        round.kickAttempt(), observedFingerprint)
                        .equals(priorClaim.claimKey())) {
            requireGateValidationClaimIdentity(
                    taskId, roundId, observedFingerprint, priorClaim);
            return round;
        }
        if (round.status() != AWAITING_GATE
                || !AgentRun.STATUS_AWAITING_GATE.equals(run.status())
                || token == null || !token.equals(round.activeGateToken())
                || observedFingerprint == null
                || !observedFingerprint.equals(currentFingerprint)
                || observedFingerprint.equals(round.codeFingerprint())) {
            throw conflict("round " + roundId + " has no invalidatable gate mismatch");
        }
        GRAPH.checkTransition(roundId, AWAITING_GATE, TRIAGING);
        int nextKickAttempt = round.kickAttempt() + 1;
        String claimKey = gateRevalidationClaimKey(
                taskId, roundId, round.gateRevision(), nextKickAttempt, observedFingerprint);
        ValidationClaim existingClaim = validations.findByClaimKey(claimKey).orElse(null);
        if (existingClaim != null) {
            requireGateValidationClaimIdentity(
                    taskId, roundId, observedFingerprint, existingClaim);
        }
        if (!rounds.invalidateGateFingerprintIf(roundId, token)) {
            throw changed(roundId, "invalidating its gate");
        }
        runs.transitionInCommand(
                taskId, run.id(), AgentRun.STATUS_QUEUED, "round_gate_fingerprint_changed");
        if (existingClaim == null) {
            Optional<Long> inserted = validations.insertClaim(
                    claimKey, taskId, CONTEXT_GATE_REVALIDATION,
                    roundId, observedFingerprint, null, null, now());
            if (inserted.isEmpty()) {
                requireGateValidationClaimIdentity(
                        taskId, roundId, observedFingerprint,
                        validations.findByClaimKey(claimKey)
                                .orElseThrow(() -> changed(
                                        roundId, "creating gate revalidation")));
            }
        }
        publish(round, TRIAGING, "round_gate_fingerprint_changed");
        return requireRound(roundId);
    }

    public ReviewRound acceptGateValidation(String roundId, String claimKey)
    {
        ReviewRound round = requireRound(roundId);
        return commands.execute(round.taskId(), () -> acceptGateValidationInCommand(
                round.taskId(), roundId, claimKey));
    }

    public ReviewRound acceptGateValidationInCommand(
            String taskId, String roundId, String claimKey)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = requireRunnableTask(taskId, TaskPhase.AWAITING_REMOTE_REVIEW);
        ReviewRound round = requireOwnedRound(taskId, roundId);
        AgentRun run = requireOwningRun(round);
        ValidationClaim claim = requireGreenClaim(taskId, claimKey);
        if (round.status() != TRIAGING
                || !ReviewRound.ORIGIN_EXTERNAL.equals(round.origin())
                || !AgentRun.STATUS_QUEUED.equals(run.status())
                || !roundId.equals(claim.roundId())
                || !CONTEXT_GATE_REVALIDATION.equals(claim.context())
                || !gateRevalidationClaimKey(
                        taskId, roundId, round.gateRevision(),
                        round.kickAttempt(), claim.codeFingerprint())
                        .equals(claim.claimKey())) {
            throw conflict("gate validation is not owned by the current round checkpoint");
        }
        requireCurrentFingerprint(task, claim.codeFingerprint());
        if (claim.codeFingerprint().equals(round.codeFingerprint())) {
            return round;
        }
        if (!rounds.acceptGateValidationIf(
                roundId, round.kickAttempt(), claim.codeFingerprint())) {
            throw changed(roundId, "accepting gate validation");
        }
        return requireRound(roundId);
    }

    static boolean isLegalTransition(ReviewRoundState from, ReviewRoundState to)
    {
        return GRAPH.isLegal(from, to);
    }

    static String kickKey(ReviewRound round, String owedAction)
    {
        return kickKey(round, owedAction, round.kickAttempt());
    }

    static String kickKey(ReviewRound round, String owedAction, int kickAttempt)
    {
        return round.id() + ':' + round.status().dbValue() + ':' + owedAction + ':'
                + round.iteration() + ':' + round.gateRevision() + ':' + kickAttempt;
    }

    private AgentRun queueNewRun(String taskId, AgentRun run)
    {
        if (AgentRun.STATUS_RUNNING.equals(run.status())) {
            return runs.transitionInCommand(
                    taskId, run.id(), AgentRun.STATUS_QUEUED, "review_round_opened");
        }
        if (!AgentRun.STATUS_QUEUED.equals(run.status())) {
            throw conflict("review round run is not queueable from " + run.status());
        }
        return run;
    }

    private void consumeHandoff(
            LocalReviewBrainHandoffStore.Handoff handoff, String runId)
    {
        if (handoff == null) {
            return;
        }
        submissions.bindRunThrough(
                handoff.taskId(), handoff.throughSequence(), runId, now());
        handoffs.markConsumed(handoff.validationClaimKey(), now());
    }

    private ValidationClaim requireGreenClaim(String taskId, String claimKey)
    {
        if (claimKey == null || claimKey.isBlank()) {
            throw conflict("a green validation claim is required");
        }
        return validations.findByClaimKey(claimKey)
                .filter(claim -> taskId.equals(claim.taskId()))
                .filter(ValidationClaim::isTerminalGreen)
                .filter(claim -> claim.cancelRequestedAt() == null)
                .filter(claim -> claim.supersededAt() == null)
                .filter(claim -> claim.codeFingerprint() != null
                        && !claim.codeFingerprint().isBlank())
                .orElseThrow(() -> conflict("validation claim is not current and green: " + claimKey));
    }

    private void requireExactRoundValidationClaim(
            ReviewRound round, String attemptId, ValidationClaim claim)
    {
        String baseKey = ROUND_VALIDATION_CONTEXT + ':' + round.taskId() + ':'
                + round.id() + ':' + attemptId + ':' + claim.codeFingerprint();
        if (!ROUND_VALIDATION_CONTEXT.equals(claim.context())
                || !round.id().equals(claim.roundId())
                || (!baseKey.equals(claim.claimKey())
                        && !claim.claimKey().startsWith(baseKey + ":after:"))) {
            throw conflict("validation claim is not bound to the completed round attempt");
        }
    }

    private static void requireGateValidationClaimIdentity(
            String taskId,
            String roundId,
            String fingerprint,
            ValidationClaim claim)
    {
        if (!taskId.equals(claim.taskId())
                || !roundId.equals(claim.roundId())
                || !fingerprint.equals(claim.codeFingerprint())
                || !CONTEXT_GATE_REVALIDATION.equals(claim.context())
                || claim.cancelRequestedAt() != null
                || claim.supersededAt() != null) {
            throw conflict("gate revalidation claim identity is already occupied");
        }
    }

    private boolean isBrainEntryClaim(ValidationClaim claim)
    {
        return DEV_VALIDATION_CONTEXT.equals(claim.context())
                || LOCAL_REVIEW_VALIDATION_CONTEXT.equals(claim.context());
    }

    private void requireCurrentFingerprint(Task task, String expected)
    {
        if (expected != null && !expected.equals(currentFingerprint(task))) {
            throw conflict("task code changed after validation");
        }
    }

    private String currentFingerprint(Task task)
    {
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            throw conflict("task " + task.id() + " has no worktree fingerprint source");
        }
        return fingerprints.fingerprint(Path.of(task.worktreePath()));
    }

    private Task requireRunnableTask(String taskId, TaskPhase phase)
    {
        Task task = tasks.findTaskById(taskId)
                .orElseThrow(() -> conflict("task not found: " + taskId));
        if (task.phase() != phase || stopped(task)) {
            throw conflict("task " + taskId + " does not own " + phase + " review work");
        }
        return task;
    }

    private static boolean stopped(Task task)
    {
        if (task.phase() == TaskPhase.NEEDS_ATTENTION
                || task.phase() == TaskPhase.COMPLETED) {
            return true;
        }
        return switch (task.status()) {
            case PAUSED, NEEDS_ATTENTION, COMPLETED, REMOTE_CLOSED,
                    ERRORED, CANCELED, ARCHIVED -> true;
            case PENDING, RUNNING, IDLE, AWAITING_REVIEW, IN_REVIEW -> false;
        };
    }

    private int openBrainFindings(String taskId)
    {
        return prs.findByTask(taskId)
                .map(pr -> (int) prs.comments(pr.id()).stream()
                        .filter(comment -> PRTimelineEntry.ACTOR_BRAIN.equals(comment.author()))
                        .filter(comment -> comment.parentCommentId() == null)
                        .filter(comment -> comment.resolvedAt() == null
                                && comment.dismissedAt() == null)
                        .count())
                .orElse(0);
    }

    private int openExternalFindings(ReviewRound round)
    {
        return externalStats(round).open();
    }

    private ReviewRound.ReviewRoundStats externalStats(ReviewRound round)
    {
        int fixed = 0;
        int replied = 0;
        int open = 0;
        for (ReviewComment comment : stages.findCommentsByRound(UUID.fromString(round.id()))) {
            if (!comment.resolved()) {
                open++;
            }
            else if (comment.draftReplyBody() != null) {
                replied++;
            }
            else {
                fixed++;
            }
        }
        return new ReviewRound.ReviewRoundStats(fixed, replied, 0, open);
    }

    private ThreadTurn requireCompletedAttempt(
            ReviewRound round, String attemptId, String source)
    {
        requireCurrentKick(round, attemptId, source);
        ThreadTurn turn = turns.findTurnById(attemptId)
                .orElseThrow(() -> conflict("review attempt not found: " + attemptId));
        if (turn.status() != ThreadTurnStatus.COMPLETED
                || !Objects.equals(round.runId(), turn.agentRunId())
                || turn.initiator() == null
                || !source.equals(turn.initiator().source())) {
            throw conflict("attempt " + attemptId + " is not the completed round review");
        }
        return turn;
    }

    private ThreadTurn requireCompletedAddressingAttempt(
            ReviewRound round, String attemptId)
    {
        String expectedSource = addressingSource(round);
        requireCurrentKick(round, attemptId, expectedSource);
        ThreadTurn turn = turns.findTurnById(attemptId)
                .orElseThrow(() -> conflict("addressing attempt not found: " + attemptId));
        String source = turn.initiator() == null ? "" : turn.initiator().source();
        if (turn.status() != ThreadTurnStatus.COMPLETED
                || !Objects.equals(round.runId(), turn.agentRunId())
                || !expectedSource.equals(source)) {
            throw conflict("attempt " + attemptId + " is not the completed addressing turn");
        }
        return turn;
    }

    private static ReviewRoundStore.AttemptFence attemptFence(
            ReviewRound round, ThreadTurn turn, String source)
    {
        return new ReviewRoundStore.AttemptFence(
                round.iteration(), round.gateRevision(), round.kickAttempt(),
                turn.id(), kickKey(round, source));
    }

    private void requireCurrentKick(
            ReviewRound round, String attemptId, String source)
    {
        if (attemptId == null || attemptId.isBlank()) {
            throw conflict("round " + round.id() + " requires an exact attempt id");
        }
        if (turns.findTurnIdByKickKey(kickKey(round, source))
                .filter(attemptId::equals)
                .isEmpty()) {
            throw conflict("attempt " + attemptId + " is not the current round kick");
        }
    }

    private static String addressingSource(ReviewRound round)
    {
        return ReviewRound.ORIGIN_EXTERNAL.equals(round.origin()) && round.iteration() == 0
                ? "review-round" : "brain-review-fix";
    }

    private static String gateRevalidationClaimKey(
            String taskId,
            String roundId,
            int gateRevision,
            int kickAttempt,
            String fingerprint)
    {
        return CONTEXT_GATE_REVALIDATION + ":" + taskId + ":" + roundId + ":"
                + gateRevision + ":" + kickAttempt + ":" + fingerprint;
    }

    private ReviewRound requireOwnedRound(String taskId, String roundId)
    {
        ReviewRound round = requireRound(roundId);
        if (!taskId.equals(round.taskId())) {
            throw conflict("round " + roundId + " does not belong to task " + taskId);
        }
        return round;
    }

    private Optional<ReviewRound> findCoordinatorRound(String taskId)
    {
        return rounds.findByTask(taskId).stream()
                .filter(round -> round.isLive() || round.status() == PAUSED)
                .findFirst();
    }

    private ReviewRound requireRound(String roundId)
    {
        requireNonNull(roundId, "roundId is null");
        return rounds.findById(roundId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "review round not found: " + roundId));
    }

    private AgentRun requireQueuedRun(ReviewRound round)
    {
        AgentRun run = requireOwningRun(round);
        if (!AgentRun.STATUS_QUEUED.equals(run.status())) {
            throw conflict("round " + round.id() + " run is not waiting after its attempt");
        }
        return run;
    }

    private AgentRun requireOwningRun(ReviewRound round)
    {
        if (round.runId() == null) {
            throw conflict("round " + round.id() + " has no owning run");
        }
        AgentRun run = runs.findById(round.runId())
                .orElseThrow(() -> conflict("round " + round.id() + " owning run is missing"));
        if (!round.taskId().equals(run.taskId())) {
            throw conflict("round " + round.id() + " does not own run " + run.id());
        }
        return run;
    }

    private void publish(ReviewRound round, ReviewRoundState to, String reason)
    {
        events.publishEvent(new ReviewRoundTransitionedEvent(
                round.taskId(), round.id(), round.status(), to,
                reason == null ? "" : reason));
    }

    private static boolean parkableRun(String status)
    {
        return AgentRun.STATUS_QUEUED.equals(status)
                || AgentRun.STATUS_RUNNING.equals(status)
                || AgentRun.STATUS_AWAITING_GATE.equals(status);
    }

    private Instant now()
    {
        return clock.instant();
    }

    private static ResponseStatusException changed(String roundId, String action)
    {
        return conflict("round " + roundId + " changed while it was " + action);
    }

    private static ResponseStatusException conflict(String message)
    {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
