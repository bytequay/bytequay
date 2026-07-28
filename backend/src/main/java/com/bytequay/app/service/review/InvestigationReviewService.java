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

import com.bytequay.app.beans.localpr.PRCommentDto;
import com.bytequay.app.beans.localpr.PRTimelineEntryDto;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.LaunchInput;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Transport;
import com.bytequay.app.developmentflow.stage.V2LocalReviewControl;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.InvestigationReviewData;
import com.bytequay.app.domain.InvestigationReviewData.ActivityFactRow;
import com.bytequay.app.domain.InvestigationReviewData.AgentReviewRow;
import com.bytequay.app.domain.InvestigationReviewData.AssignmentBudget;
import com.bytequay.app.domain.InvestigationReviewData.CriterionRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingEvidenceRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingRelationRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingVerificationRow;
import com.bytequay.app.domain.InvestigationReviewData.HypothesisRow;
import com.bytequay.app.domain.InvestigationReviewData.InvestigationStepRow;
import com.bytequay.app.domain.InvestigationReviewData.ObservationRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewAssignmentRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewObjectiveRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewOutcomeRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewRoundMessageRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewRoundRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewedCommitRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewerDefRow;
import com.bytequay.app.domain.InvestigationReviewData.RoundBudget;
import com.bytequay.app.domain.KnowledgeItem.Applicability;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.review.DeterministicReviewCoverage.CoverageReport;
import com.bytequay.app.service.review.DeterministicReviewCoverage.FailureClassResult;
import com.bytequay.app.service.review.DeterministicReviewCoverage.SweepResult;
import com.bytequay.app.service.review.InvestigationReviewModel.ReviewKnowledge;
import com.bytequay.app.service.review.InvestigationReviewModel.ReviewTurnPrompt;
import com.bytequay.app.service.review.InvestigationReviewRunner.ProviderChoice;
import com.bytequay.app.service.review.InvestigationReviewRunner.RunOutcome;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.FlowPhase;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.FollowUpSeat;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.RoundFlow;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.Seat;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.TurnState;
import com.bytequay.app.service.review.ReviewProviderEndpoints.AgentLaunch;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.BLIND_RECONSTRUCTION;
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.INDEPENDENT_VERIFICATION;
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.INVESTIGATE;
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.ROUND_GUIDANCE;
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.SELF_REFUTATION;
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.guidanceSubject;
import static java.util.Objects.requireNonNull;

/** AgentReview lifecycle and deterministic orchestration. */
@Service
public class InvestigationReviewService
        implements ReviewAssignmentTurnContinuation
{
    private static final Logger log = LoggerFactory.getLogger(InvestigationReviewService.class);
    private static final Duration PREFLIGHT_TTL = Duration.ofHours(24);
    private static final int MAX_LEARNED_OBJECTIVES = 3;
    private static final int MIN_PUBLISHABLE_SEVERITY = 4;
    private static final Set<String> CRITERION_KINDS = Set.of(
            "hard-invariant", "engineering-principle", "repo-convention");
    private static final Set<String> HARD_LEARNED_KINDS = Set.of(
            "domain-invariant", "invariant", "compatibility-contract", "build-test-rule");
    private static final Set<String> PRINCIPLE_LEARNED_KINDS = Set.of(
            "architecture-principle", "principle", "recurring-concern", "concern",
            "investigation-recipe", "recipe", "performance-assumption");
    private static final Set<String> CONVENTION_LEARNED_KINDS = Set.of(
            "doc-note", "convention");
    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");

    private final InvestigationReviewStore store;
    private final InvestigationReviewContext contexts;
    private final InvestigationReviewModel runner;
    private final AgentRunService runs;
    private final PRService prs;
    private final TaskStore tasks;
    private final ThreadStore threads;
    private final WorkspaceService workspaces;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, CachedPlan> preflightPlans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Thread> activeRounds = new ConcurrentHashMap<>();
    private final Object budgetGuard = new Object();
    private final ConcurrentHashMap<String, Integer> inFlightBudgetFloors =
            new ConcurrentHashMap<>();
    private final Set<String> cancellationSideEffectsDone = ConcurrentHashMap.newKeySet();
    private final Object[] typedRoundGuards = Stream.generate(Object::new)
            .limit(64).toArray(Object[]::new);
    private ReviewAssignmentTurnRuntime typedReviewTurns;
    private V2LocalReviewControl v2LocalReview;

    @Autowired
    public InvestigationReviewService(
            InvestigationReviewStore store, InvestigationReviewContext contexts,
            InvestigationReviewRunner runner, AgentRunService runs,
            PRService prs, TaskStore tasks, ThreadStore threads, ObjectMapper mapper,
            WorkspaceService workspaces)
    {
        this(store, contexts, (InvestigationReviewModel) runner, runs,
                prs, tasks, threads, mapper, workspaces);
    }

    InvestigationReviewService(
            InvestigationReviewStore store, InvestigationReviewContext contexts,
            InvestigationReviewModel runner, AgentRunService runs,
            PRService prs, TaskStore tasks, ThreadStore threads, ObjectMapper mapper)
    {
        this(store, contexts, runner, runs, prs, tasks, threads, mapper, null);
    }

    private InvestigationReviewService(
            InvestigationReviewStore store, InvestigationReviewContext contexts,
            InvestigationReviewModel runner, AgentRunService runs,
            PRService prs, TaskStore tasks, ThreadStore threads, ObjectMapper mapper,
            WorkspaceService workspaces)
    {
        this.store = requireNonNull(store, "store is null");
        this.contexts = requireNonNull(contexts, "contexts is null");
        this.runner = requireNonNull(runner, "runner is null");
        this.runs = requireNonNull(runs, "runs is null");
        this.prs = requireNonNull(prs, "prs is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.threads = requireNonNull(threads, "threads is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.workspaces = workspaces;
    }

    @Autowired(required = false)
    public void setReviewAssignmentTurnRuntime(ReviewAssignmentTurnRuntime typedReviewTurns)
    {
        this.typedReviewTurns = requireNonNull(typedReviewTurns, "typedReviewTurns is null");
    }

    @Autowired(required = false)
    public void setV2LocalReview(V2LocalReviewControl v2LocalReview)
    {
        this.v2LocalReview = requireNonNull(v2LocalReview, "v2LocalReview is null");
    }

    /** Exact, idempotent handoff after primary ReviewAssignmentTurns exist. */
    public void registerTypedRound(
            String taskId, String reviewId, String reviewRoundId)
    {
        String exactTaskId = requiredText(taskId, "taskId");
        String exactReviewId = requiredText(reviewId, "reviewId");
        String exactRoundId = requiredText(reviewRoundId, "reviewRoundId");
        AgentReviewRow review = requireReview(exactReviewId);
        ReviewRoundRow round = store.findRound(exactRoundId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown review round " + exactRoundId));
        if (!exactTaskId.equals(review.ownerTaskId())
                || !exactReviewId.equals(round.reviewId())
                || !tasks.isV2Task(exactTaskId)) {
            throw new IllegalArgumentException(
                    "typed review registration does not match its exact Task/session/round");
        }
        ReviewAssignmentTurnRuntime runtime = requireNonNull(
                typedReviewTurns, "typed review runtime is unavailable");
        RoundFlow flow = runtime.flow(exactRoundId)
                .orElseThrow(() -> new IllegalStateException(
                        "typed review registration requires durable primary Turns"));
        boolean primary = runtime.turns(exactRoundId).stream()
                .anyMatch(turn -> INVESTIGATE.equals(turn.purpose()));
        if (!primary || !round.startCommit().equals(flow.startCommit())) {
            throw new IllegalStateException(
                    "typed review registration has no exact primary assignment");
        }
        resumeTypedRound(exactRoundId);
    }

    @Override
    public void resumeAfter(String turnId)
    {
        ReviewAssignmentTurnRuntime runtime = typedReviewTurns;
        if (runtime == null) {
            return;
        }
        runtime.roundId(turnId).ifPresent(this::resumeTypedRound);
    }

    private void resumeTypedRound(String roundId)
    {
        Object guard = typedRoundGuards[Math.floorMod(
                roundId.hashCode(), typedRoundGuards.length)];
        synchronized (guard) {
            continueTypedRound(roundId);
        }
    }

    private void continueTypedRound(String roundId)
    {
        ReviewAssignmentTurnRuntime runtime = requireNonNull(
                typedReviewTurns, "typed review runtime is unavailable");
        for (int transitions = 0; transitions < 32; transitions++) {
            RoundFlow flow = runtime.flow(roundId).orElse(null);
            ReviewRoundRow round = store.findRound(roundId).orElse(null);
            if (flow == null || round == null
                    || flow.phase() == FlowPhase.COMPLETED
                    || flow.phase() == FlowPhase.BLOCKED
                    || flow.phase() == FlowPhase.CANCELED) {
                return;
            }
            if (!"RUNNING".equals(round.status())) {
                if (flow.phase() == FlowPhase.FINALIZING
                        && round.status().startsWith("COMPLETED")) {
                    if (!store.isRoundFinalized(roundId)) {
                        reconcileInterruptedRound(round);
                    }
                    runtime.movePhase(roundId, FlowPhase.FINALIZING, FlowPhase.COMPLETED);
                }
                else if ("CANCELLED".equals(round.status())) {
                    runtime.movePhase(roundId, flow.phase(), FlowPhase.CANCELED);
                }
                else if ("ERRORED".equals(round.status())) {
                    runtime.movePhase(roundId, flow.phase(), FlowPhase.BLOCKED);
                }
                return;
            }
            RoundWork work = typedRoundWork(round);
            if (work == null) {
                return;
            }
            List<TurnState> turns = runtime.turns(roundId);
            if (flow.phase() == FlowPhase.PRIMARY) {
                GuidanceProgress guidance = advanceTypedGuidance(work, turns);
                if (guidance == GuidanceProgress.WAITING) {
                    return;
                }
                if (guidance == GuidanceProgress.PROGRESSED) {
                    continue;
                }
            }
            switch (flow.phase()) {
                case PRIMARY -> {
                    List<TurnState> primary = turns.stream()
                            .filter(turn -> INVESTIGATE.equals(turn.purpose()))
                            .toList();
                    if (primary.isEmpty() || primary.stream().anyMatch(turn -> !turn.terminal())) {
                        return;
                    }
                    if (primary.stream().anyMatch(turn -> !"SUCCEEDED".equals(turn.status()))) {
                        return;
                    }
                    primary.forEach(turn -> store.skipRunningSteps(turn.assignmentId()));
                    ReviewRoundRow current = store.findRound(roundId).orElseThrow();
                    if (current.messageGateOpen()
                            && !store.closeMessageGateIfDrained(roundId)) {
                        continue;
                    }
                    List<FindingRow> missing = findingsMissingRefutation(work);
                    if (missing.isEmpty() || remainingTypedBudget(work) == 0) {
                        if (!runtime.movePhase(
                                roundId, FlowPhase.PRIMARY, FlowPhase.VERIFYING)) {
                            continue;
                        }
                        continue;
                    }
                    if (!runtime.movePhase(
                            roundId, FlowPhase.PRIMARY, FlowPhase.SELF_REFUTATION)) {
                        continue;
                    }
                    admitSelfRefutation(work, primary.get(0), missing);
                    return;
                }
                case SELF_REFUTATION -> {
                    TurnState turn = turns.stream()
                            .filter(candidate -> SELF_REFUTATION.equals(candidate.purpose()))
                            .findFirst().orElse(null);
                    if (turn == null) {
                        List<FindingRow> missing = findingsMissingRefutation(work);
                        if (missing.isEmpty() || remainingTypedBudget(work) == 0) {
                            runtime.movePhase(roundId, FlowPhase.SELF_REFUTATION,
                                    FlowPhase.VERIFYING);
                            continue;
                        }
                        TurnState primary = turns.stream()
                                .filter(candidate -> INVESTIGATE.equals(candidate.purpose()))
                                .findFirst().orElseThrow();
                        admitSelfRefutation(work, primary, missing);
                        return;
                    }
                    if (!turn.terminal()) {
                        return;
                    }
                    if (!"SUCCEEDED".equals(turn.status())) {
                        return;
                    }
                    if (store.steps(work.review().id()).stream().noneMatch(step ->
                            turn.assignmentId().equals(step.assignmentId())
                                    && SELF_REFUTATION.equals(step.actionType()))) {
                        recordSelfRefutationPass(
                                turn.assignmentId(),
                                turn.subjectKey().split("\\|").length,
                                outcome(turn));
                    }
                    runtime.movePhase(roundId, FlowPhase.SELF_REFUTATION,
                            FlowPhase.VERIFYING);
                    continue;
                }
                case VERIFYING -> {
                    if (!advanceTypedVerification(work, flow, turns)) {
                        return;
                    }
                    continue;
                }
                case FINALIZING -> {
                    finalizeTypedRound(work, turns);
                    ReviewRoundRow completed = store.findRound(roundId).orElseThrow();
                    if (!completed.status().startsWith("COMPLETED")) {
                        throw new IllegalStateException(
                                "typed review finalization did not commit");
                    }
                    if (!store.isRoundFinalized(roundId)) {
                        reconcileInterruptedRound(completed);
                    }
                    runtime.movePhase(roundId, FlowPhase.FINALIZING, FlowPhase.COMPLETED);
                    return;
                }
                default -> {
                    return;
                }
            }
        }
        throw new IllegalStateException("review follow-up transition loop did not quiesce");
    }

    private RoundWork typedRoundWork(ReviewRoundRow round)
    {
        AgentReviewRow review = requireReview(round.reviewId());
        PR pr = requirePr(review.prId());
        InvestigationReviewContext.Snapshot snapshot = fullReviewSnapshot(
                pr, review.workspaceId() != null || review.ownerTaskId() != null);
        AgentRun run = runs.findById(round.agentRunId()).orElseThrow(() ->
                new IllegalStateException("review round has no primary run"));
        if (!round.startCommit().equals(snapshot.headCommit())) {
            blockTypedRound(round, run, "reviewed head moved before follow-up verification");
            return null;
        }
        List<TurnState> primaryTurns = typedReviewTurns.turns(round.id()).stream()
                .filter(turn -> INVESTIGATE.equals(turn.purpose()))
                .toList();
        Map<String, TurnState> turnsByAssignment = primaryTurns.stream()
                .collect(Collectors.toMap(
                        TurnState::assignmentId, turn -> turn, (left, right) -> left));
        List<AssignmentWork> assignments = store.assignments(review.id()).stream()
                .filter(row -> round.id().equals(row.roundId()))
                .filter(row -> turnsByAssignment.containsKey(row.id()))
                .map(row -> new AssignmentWork(
                        row.id(), providerChoice(turnsByAssignment.get(row.id())),
                        store.findReviewerDef(row.reviewerDefId()).orElseThrow()))
                .toList();
        List<ReviewObjectiveRow> objectives = store.objectives(review.id()).stream()
                .filter(objective -> round.id().equals(objective.roundId()))
                .toList();
        PlanDraft plan = new PlanDraft(
                reviewClass(round.budgetJson()), round.budgetJson(), List.of(), null);
        return new RoundWork(
                review, pr, snapshot, plan, round, run, objectives,
                List.copyOf(assignments), null);
    }

    private void blockTypedRound(ReviewRoundRow round, AgentRun run, String reason)
    {
        store.findings(round.reviewId()).stream()
                .filter(finding -> round.id().equals(finding.roundId()))
                .filter(finding -> !"dropped".equals(finding.lifecycleStatus()))
                .forEach(finding -> insertUnknownVerificationIfMissing(
                        run.id(), finding, reason));
        store.finishRunningRound(round.id(), "ERRORED", null, round.costCents());
        runs.findById(run.id()).filter(AgentRun::isLive).ifPresent(current ->
                runs.transition(current.id(), AgentRun.STATUS_FAILED, reason));
        typedReviewTurns.flow(round.id()).ifPresent(flow -> {
            if (flow.phase() != FlowPhase.BLOCKED
                    && flow.phase() != FlowPhase.CANCELED
                    && flow.phase() != FlowPhase.COMPLETED) {
                typedReviewTurns.movePhase(
                        round.id(), flow.phase(), FlowPhase.BLOCKED);
            }
        });
    }

    private GuidanceProgress advanceTypedGuidance(
            RoundWork work, List<TurnState> turns)
    {
        ReviewRoundMessageRow message = store.roundMessages(work.review().id()).stream()
                .filter(row -> work.round().id().equals(row.roundId()))
                .filter(row -> Set.of("pending", "processing").contains(row.status()))
                .sorted(Comparator.comparingLong(ReviewRoundMessageRow::createdAt)
                        .thenComparing(ReviewRoundMessageRow::id))
                .findFirst().orElse(null);
        if (message == null) {
            return GuidanceProgress.IDLE;
        }
        if ("pending".equals(message.status())) {
            if (!store.claimRoundMessage(message.id())) {
                return GuidanceProgress.PROGRESSED;
            }
            String claimedMessageId = message.id();
            message = store.roundMessages(work.review().id()).stream()
                    .filter(row -> claimedMessageId.equals(row.id()))
                    .findFirst().orElseThrow();
        }
        if (remainingTypedBudget(work) == 0 && message.assignmentId() == null) {
            store.completeRoundMessage(
                    message.id(), "failed",
                    "No round budget remains for this guidance. Increase the cap and send it again.",
                    Instant.now());
            return GuidanceProgress.PROGRESSED;
        }

        String subject = guidanceSubject(message.id(), message.target());
        String linkedAssignmentId = message.assignmentId();
        ReviewAssignmentRow assignment = linkedAssignmentId == null
                ? null : store.assignments(work.review().id()).stream()
                        .filter(row -> linkedAssignmentId.equals(row.id()))
                        .findFirst().orElseThrow();
        TurnState turn = assignment == null ? null : logicalTurn(
                turns, assignment.id(), ROUND_GUIDANCE, subject);
        PanelSeat seat = null;
        try {
            if (assignment == null) {
                seat = messageReviewer(work, message.target());
                String assignmentId = "v2-guidance-assignment:" + message.id();
                assignment = new ReviewAssignmentRow(
                        assignmentId, work.round().id(), seat.reviewerDef().id(),
                        seat.provider().runner(), "queued", "", List.of(), List.of(),
                        new AssignmentBudget(6, 3, 12, 5));
                store.insertGuidanceAssignment(message.id(), assignment);
            }
            if (turn == null) {
                if (seat == null) {
                    seat = linkedGuidanceReviewer(work, message, assignment);
                }
                ReviewTurnPrompt prompt = typedGuidancePrompt(work, message, seat);
                typedReviewTurns.admitFollowUp(
                        work.round().id(), work.round().startCommit(),
                        new FollowUpSeat(
                                assignment.id(), ROUND_GUIDANCE, subject, null,
                                typedReviewTurns.freezeProvider(seat.provider()),
                                typedWorkingDirectory(work), prompt));
                return GuidanceProgress.WAITING;
            }
        }
        catch (RuntimeException e) {
            if (assignment != null) {
                store.skipRunningSteps(assignment.id());
                store.updateAssignment(
                        assignment.id(), "errored", "Guidance could not be processed.",
                        List.of(), List.of());
            }
            store.completeRoundMessage(
                    message.id(), "failed",
                    concise("Guidance could not be processed: "
                            + (e.getMessage() == null ? "reviewer failed" : e.getMessage()),
                            600), Instant.now());
            log.warn("Typed review round {} could not admit guidance {}: {}",
                    work.round().id(), message.id(), e.getMessage());
            return GuidanceProgress.PROGRESSED;
        }

        if (!turn.terminal()) {
            return GuidanceProgress.WAITING;
        }
        if (!"SUCCEEDED".equals(turn.status())) {
            store.completeRoundMessage(
                    message.id(), "failed", "Guidance provider did not complete.",
                    Instant.now());
            return GuidanceProgress.PROGRESSED;
        }
        store.skipRunningSteps(assignment.id());
        String completedAssignmentId = assignment.id();
        ReviewAssignmentRow recorded = store.assignments(work.review().id()).stream()
                .filter(row -> completedAssignmentId.equals(row.id()))
                .findFirst().orElseThrow();
        store.updateAssignmentWhileRoundRunning(
                assignment.id(), "completed",
                recorded.understandingSummary().isBlank()
                        ? "Guidance processed." : recorded.understandingSummary(),
                recorded.assumptionsJson(), recorded.unknownsJson());
        ReviewerDefRow reviewer = store.findReviewerDef(assignment.reviewerDefId())
                .orElseThrow();
        store.completeRoundMessage(
                message.id(), "completed",
                messageResponse(reviewer, outcome(turn), recorded), Instant.now());
        return GuidanceProgress.PROGRESSED;
    }

    private PanelSeat linkedGuidanceReviewer(
            RoundWork work,
            ReviewRoundMessageRow message,
            ReviewAssignmentRow assignment)
    {
        ReviewerDefRow definition = store.findReviewerDef(assignment.reviewerDefId())
                .orElseThrow(() -> new IllegalStateException(
                        "guidance reviewer definition disappeared"));
        AssignmentWork primary = work.assignments().get(0);
        if (Set.of("panel", "planner").contains(message.target())) {
            return new PanelSeat(primary.provider(), definition);
        }
        Optional<AssignmentWork> original = work.assignments().stream()
                .filter(row -> row.reviewerDef().id().equals(definition.id()))
                .findFirst();
        if (original.isPresent()) {
            return new PanelSeat(original.orElseThrow().provider(), definition);
        }
        ProviderChoice choice = "independent-verifier".equals(definition.id())
                ? verifierProvider(definition, primary.provider())
                : provider(definition);
        return new PanelSeat(choice, definition);
    }

    private ReviewTurnPrompt typedGuidancePrompt(
            RoundWork work,
            ReviewRoundMessageRow message,
            PanelSeat seat)
    {
        List<ReviewObjectiveRow> objectives = work.objectives().stream()
                .filter(objective -> "applicable".equals(objective.applicabilityStatus()))
                .toList();
        return switch (message.target()) {
            case "planner" -> runner.planGuidancePrompt(
                    work.snapshot(), objectives, message.body());
            case "independent-verifier" -> runner.verifyGuidancePrompt(
                    work.snapshot(), objectives, message.body());
            default -> runner.investigationPrompt(
                    work.review().id(), work.snapshot(), objectives,
                    "User guidance checkpoint\nTarget: " + message.target()
                            + "\nGuidance: " + message.body()
                            + "\nAddress this guidance within the frozen round scope. "
                            + "Use read-only evidence tools and record any resulting artifacts "
                            + "before responding.",
                    seat.reviewerDef().persona());
        };
    }

    private List<FindingRow> findingsMissingRefutation(RoundWork work)
    {
        Set<String> refuted = store.evidence(work.review().id()).stream()
                .filter(edge -> "REFUTES".equals(edge.relation()))
                .map(FindingEvidenceRow::findingId)
                .collect(Collectors.toSet());
        return store.findings(work.review().id()).stream()
                .filter(finding -> work.round().id().equals(finding.roundId()))
                .filter(finding -> !refuted.contains(finding.id()))
                .toList();
    }

    private void admitSelfRefutation(
            RoundWork work, TurnState primary, List<FindingRow> findings)
    {
        Map<String, List<FindingEvidenceRow>> evidence = evidenceByFinding(work.review().id());
        Map<String, ObservationRow> observations = observationsById(work.review().id());
        String bundles = findings.stream()
                .map(finding -> findingBundle(
                        finding, evidence.getOrDefault(finding.id(), List.of()), observations))
                .collect(Collectors.joining("\n---\n"));
        ReviewTurnPrompt prompt = runner.selfRefutationPrompt(work.snapshot(), bundles);
        typedReviewTurns.admitFollowUp(
                work.round().id(), work.round().startCommit(),
                new FollowUpSeat(
                        primary.assignmentId(), SELF_REFUTATION,
                        findings.stream().map(FindingRow::id).sorted()
                                .collect(Collectors.joining("|")),
                        null, frozenProvider(primary), typedWorkingDirectory(work), prompt));
    }

    /** Returns true only after the phase moved and the caller may continue. */
    private boolean advanceTypedVerification(
            RoundWork work, RoundFlow flow, List<TurnState> turns)
    {
        List<FindingRow> candidates = consolidate(store.findings(work.review().id()).stream()
                .filter(finding -> work.round().id().equals(finding.roundId()))
                .toList());
        if (candidates.isEmpty()) {
            typedReviewTurns.movePhase(
                    work.round().id(), FlowPhase.VERIFYING, FlowPhase.FINALIZING);
            return true;
        }
        if ("trivial".equals(work.plan().reviewClass())) {
            verifyTrivial(work, candidates);
            typedReviewTurns.movePhase(
                    work.round().id(), FlowPhase.VERIFYING, FlowPhase.FINALIZING);
            return true;
        }
        if (remainingTypedBudget(work) == 0) {
            candidates.forEach(finding -> insertUnknownVerificationIfMissing(
                    work.run().id(), finding,
                    "Round cost cap was reached before independent verification."));
            typedReviewTurns.movePhase(
                    work.round().id(), FlowPhase.VERIFYING, FlowPhase.FINALIZING);
            return true;
        }

        VerifierOwner verifier = verifierOwner(work, flow);
        if (verifier == null) {
            candidates.forEach(finding -> insertUnknownVerificationIfMissing(
                    work.run().id(), finding,
                    "No independent verifier is available for this review class."));
            typedReviewTurns.movePhase(
                    work.round().id(), FlowPhase.VERIFYING, FlowPhase.FINALIZING);
            return true;
        }

        Map<String, List<FindingEvidenceRow>> evidence = evidenceByFinding(work.review().id());
        Map<String, ObservationRow> observations = observationsById(work.review().id());
        Set<String> verified = store.verifications(work.review().id()).stream()
                .map(FindingVerificationRow::findingId)
                .collect(Collectors.toSet());
        for (FindingRow finding : candidates.stream()
                .sorted(Comparator.comparing(FindingRow::id)).toList()) {
            if (verified.contains(finding.id())) {
                continue;
            }
            List<FindingEvidenceRow> findingEvidence =
                    evidence.getOrDefault(finding.id(), List.of());
            if (!deterministicallyValid(work, finding, findingEvidence, observations)) {
                insertRejectedVerificationIfMissing(
                        verifier.run().id(), finding,
                        "Deterministic validation failed: missing/current evidence or action.");
                continue;
            }
            if (remainingTypedBudget(work) == 0) {
                insertUnknownVerificationIfMissing(
                        verifier.run().id(), finding,
                        "Round cost cap was reached before independent verification.");
                continue;
            }
            String blind = null;
            if (finding.severity() >= MIN_PUBLISHABLE_SEVERITY) {
                TurnState reconstruction = logicalTurn(
                        turns, verifier.assignment().id(),
                        BLIND_RECONSTRUCTION, finding.id());
                if (reconstruction == null) {
                    ReviewTurnPrompt prompt = runner.reconstructionPrompt(
                            work.snapshot(), evidenceLocations(findingEvidence, observations),
                            verifier.definition().persona());
                    typedReviewTurns.admitFollowUp(
                            work.round().id(), work.round().startCommit(),
                            new FollowUpSeat(
                                    verifier.assignment().id(), BLIND_RECONSTRUCTION,
                                    finding.id(), null, verifier.provider(),
                                    typedWorkingDirectory(work), prompt));
                    return false;
                }
                if (!reconstruction.terminal()
                        || !"SUCCEEDED".equals(reconstruction.status())) {
                    return false;
                }
                blind = reconstruction.finalText();
            }
            TurnState verification = logicalTurn(
                    turns, verifier.assignment().id(),
                    INDEPENDENT_VERIFICATION, finding.id());
            if (verification == null) {
                ReviewTurnPrompt prompt = runner.verificationPrompt(
                        work.snapshot(), verifier.run().id(),
                        findingBundle(finding, findingEvidence, observations), blind,
                        verifier.definition().persona());
                typedReviewTurns.admitFollowUp(
                        work.round().id(), work.round().startCommit(),
                        new FollowUpSeat(
                                verifier.assignment().id(), INDEPENDENT_VERIFICATION,
                                finding.id(), verifier.run().id(), verifier.provider(),
                                typedWorkingDirectory(work), prompt));
                return false;
            }
            if (!verification.terminal()
                    || !"SUCCEEDED".equals(verification.status())) {
                return false;
            }
            boolean structured = store.verifications(work.review().id()).stream()
                    .anyMatch(row -> finding.id().equals(row.findingId()));
            if (!structured) {
                insertUnknownVerificationIfMissing(
                        verifier.run().id(), finding,
                        "Verifier returned without a structured result.");
            }
        }
        finishTypedVerifier(work, verifier, typedReviewTurns.turns(work.round().id()));
        typedReviewTurns.movePhase(
                work.round().id(), FlowPhase.VERIFYING, FlowPhase.FINALIZING);
        return true;
    }

    private VerifierOwner verifierOwner(RoundWork work, RoundFlow flow)
    {
        if (work.assignments().isEmpty()) {
            return null;
        }
        String assignmentId = flow.verifierAssignmentId() == null
                ? "v2-verifier-assignment:" + work.round().id()
                : flow.verifierAssignmentId();
        ReviewAssignmentRow assignment = store.assignments(work.review().id()).stream()
                .filter(row -> assignmentId.equals(row.id()))
                .findFirst().orElse(null);
        ReviewerDefRow definition = assignment == null
                ? store.findReviewerDef("independent-verifier")
                        .filter(ReviewerDefRow::enabled)
                        .filter(row -> row.eligibleKinds().contains(work.plan().reviewClass()))
                        .orElse(null)
                : store.findReviewerDef(assignment.reviewerDefId()).orElse(null);
        if (definition == null) {
            return null;
        }
        List<TurnState> existingVerifierTurns = typedReviewTurns.turns(work.round().id())
                .stream()
                .filter(turn -> assignmentId.equals(turn.assignmentId()))
                .toList();
        ProviderChoice choice;
        AgentLaunch provider;
        if (existingVerifierTurns.isEmpty()) {
            try {
                choice = verifierProvider(definition, work.assignments().get(0).provider());
                provider = typedReviewTurns.freezeProvider(choice);
            }
            catch (IllegalStateException unavailable) {
                return null;
            }
        }
        else {
            TurnState frozen = existingVerifierTurns.get(0);
            choice = providerChoice(frozen);
            provider = frozenProvider(frozen);
        }
        if (assignment == null) {
            assignment = new ReviewAssignmentRow(
                    assignmentId, work.round().id(), definition.id(), choice.runner(),
                    "verifying", "Independent evidence audit", List.of(), List.of(),
                    new AssignmentBudget(0, 0, 6, 5));
            store.insertAssignment(assignment);
        }
        AgentRun verifierRun = flow.verifierRunId() == null
                ? runs.findByReviewRound(work.round().id()).stream()
                        .filter(run -> !work.run().id().equals(run.id()))
                        .findFirst()
                        .orElseGet(() -> openReviewRun(
                                work.review(), work.pr(), work.round().id(),
                                Math.max(1, remainingTypedBudget(work))))
                : runs.findById(flow.verifierRunId()).orElseThrow();
        if (flow.verifierRunId() == null) {
            typedReviewTurns.bindVerifier(
                    work.round().id(), assignment.id(), verifierRun.id());
        }
        return new VerifierOwner(
                assignment, verifierRun, definition, provider);
    }

    private void finishTypedVerifier(
            RoundWork work, VerifierOwner verifier, List<TurnState> turns)
    {
        List<TurnState> verifierTurns = turns.stream()
                .filter(turn -> verifier.assignment().id().equals(turn.assignmentId()))
                .toList();
        long tokensIn = verifierTurns.stream().mapToLong(TurnState::inputTokens).sum();
        long tokensOut = verifierTurns.stream().mapToLong(TurnState::outputTokens).sum();
        long cost = verifierTurns.stream().mapToLong(TurnState::costUsdMilli).sum();
        store.updateAssignmentWhileRoundRunning(
                verifier.assignment().id(), "completed", "Verification complete.",
                List.of(), List.of());
        runs.updateHeadline(verifier.run().id(),
                store.findings(work.review().id()).stream()
                        .filter(finding -> work.round().id().equals(finding.roundId()))
                        .count() + " findings verified");
        ObjectNode metrics = mapper.createObjectNode();
        metrics.put("provider", verifier.provider().provider());
        metrics.put("runner", verifier.provider().transport().name().toLowerCase(Locale.ROOT));
        metrics.put("tokensIn", tokensIn);
        metrics.put("tokensOut", tokensOut);
        metrics.put("providerRounds", verifierTurns.size());
        metrics.put("costCents", (cost + 9) / 10);
        runs.updateMetrics(verifier.run().id(), metrics.toString());
        runs.findById(verifier.run().id()).filter(AgentRun::isLive).ifPresent(run ->
                runs.transition(run.id(), AgentRun.STATUS_SUCCEEDED, "verification complete"));
    }

    private void finalizeTypedRound(RoundWork work, List<TurnState> turns)
    {
        ReviewRoundRow current = store.findRound(work.round().id()).orElseThrow();
        if (!"RUNNING".equals(current.status())) {
            return;
        }
        List<FindingRow> finished = store.findings(work.review().id()).stream()
                .filter(finding -> work.round().id().equals(finding.roundId()))
                .toList();
        finished.stream()
                .filter(finding -> !"dropped".equals(finding.lifecycleStatus()))
                .forEach(finding -> insertUnknownVerificationIfMissing(
                        work.run().id(), finding,
                        "Review follow-up finished without conclusive verification."));
        List<FindingRow> finalFindings = store.findings(work.review().id()).stream()
                .filter(finding -> work.round().id().equals(finding.roundId()))
                .toList();
        materialiseComments(work, finalFindings);
        turns.stream().filter(turn -> INVESTIGATE.equals(turn.purpose()))
                .findFirst().map(TurnState::finalText)
                .ifPresent(response -> appendAnswerReply(work, response));
        boolean coverageGaps = resolveObjectives(work, finalFindings);
        boolean questions = coverageGaps || finalFindings.stream().anyMatch(finding ->
                !"dropped".equals(finding.lifecycleStatus())
                        && "unknown".equals(finding.verificationStatus()));
        String status = questions ? "COMPLETED_WITH_QUESTIONS" : "COMPLETED";
        if (!store.finishRunningRoundAndAdvanceReview(
                work.round().id(), status, work.round().startCommit(), current.costCents())) {
            return;
        }
        runs.updateHeadline(work.run().id(), finalFindings.size() + " findings");
        runs.updateMetrics(work.run().id(), typedMetrics(work, turns, finalFindings).toString());
        runs.findById(work.run().id()).filter(AgentRun::isLive).ifPresent(run ->
                runs.transition(run.id(), AgentRun.STATUS_SUCCEEDED,
                        "investigation review complete"));
        reviewEvent(work.pr().id(), "round-complete", node -> {
            node.put("roundId", work.round().id());
            node.put("findingCount", finalFindings.size());
        });
        syncStandaloneOwnerAfterRound(work.review(), questions
                ? ThreadStatus.NEEDS_ATTENTION : ThreadStatus.AWAITING_REVIEW, null);
        store.markRoundFinalized(work.round().id());
    }

    private ObjectNode typedMetrics(
            RoundWork work, List<TurnState> turns, List<FindingRow> findings)
    {
        ObjectNode metrics = mapper.createObjectNode();
        TurnState primary = turns.stream()
                .filter(turn -> INVESTIGATE.equals(turn.purpose()))
                .findFirst().orElseThrow();
        AgentLaunch launch = frozenProvider(primary);
        metrics.put("reviewClass", work.plan().reviewClass());
        metrics.put("provider", launch.provider());
        metrics.put("runner", launch.transport().name().toLowerCase(Locale.ROOT));
        metrics.put("assignments", work.assignments().size());
        metrics.put("wallClockMs", Duration.between(
                work.run().startedAt(), Instant.now()).toMillis());
        metrics.put("tokensIn", turns.stream().mapToLong(TurnState::inputTokens).sum());
        metrics.put("tokensOut", turns.stream().mapToLong(TurnState::outputTokens).sum());
        metrics.put("providerRounds", turns.size());
        metrics.put("costCents", store.findRound(work.round().id())
                .map(ReviewRoundRow::costCents).orElse(work.round().costCents()));
        metrics.put("findings", findings.size());
        metrics.put("verified", findings.stream()
                .filter(row -> "verified".equals(row.verificationStatus())).count());
        metrics.put("partially", findings.stream()
                .filter(row -> "partially".equals(row.verificationStatus())).count());
        metrics.put("unknown", findings.stream()
                .filter(row -> "unknown".equals(row.verificationStatus())).count());
        metrics.put("rejected", findings.stream()
                .filter(row -> "rejected".equals(row.verificationStatus())).count());
        return metrics;
    }

    private int remainingTypedBudget(RoundWork work)
    {
        ReviewRoundRow current = store.findRound(work.round().id()).orElseThrow();
        return Math.max(0, current.budgetJson().costCapCents() - current.costCents());
    }

    private AgentLaunch frozenProvider(TurnState turn)
    {
        try {
            LaunchInput input = mapper.readValue(turn.launchInput(), LaunchInput.class);
            return new AgentLaunch(
                    input.transport(), input.provider(),
                    input.credentialAccount(), input.model());
        }
        catch (Exception e) {
            throw new IllegalStateException("stored review provider is invalid", e);
        }
    }

    private ProviderChoice providerChoice(TurnState turn)
    {
        AgentLaunch launch = frozenProvider(turn);
        String providerId = switch (launch.provider()) {
            case "claude-code" -> "claude-cli";
            case "codex" -> "codex-cli";
            case "anthropic" -> "claude";
            default -> launch.provider();
        };
        String runnerKind = launch.transport()
                == Transport.CLI
                ? "cli" : "api";
        String family = switch (launch.provider()) {
            case "claude-code", "anthropic" -> "anthropic";
            case "deepseek" -> "deepseek";
            default -> "openai";
        };
        return new ProviderChoice(providerId, runnerKind, family);
    }

    private static TurnState logicalTurn(
            List<TurnState> turns, String assignmentId, String purpose, String subjectKey)
    {
        return turns.stream()
                .filter(turn -> assignmentId.equals(turn.assignmentId()))
                .filter(turn -> purpose.equals(turn.purpose()))
                .filter(turn -> subjectKey.equals(turn.subjectKey()))
                .findFirst().orElse(null);
    }

    private static Path typedWorkingDirectory(RoundWork work)
    {
        return work.snapshot().localRoot() == null
                ? Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
                : work.snapshot().localRoot().toAbsolutePath().normalize();
    }

    private RunOutcome outcome(TurnState turn)
    {
        return new RunOutcome(
                providerChoice(turn), (int) ((turn.costUsdMilli() + 9) / 10),
                turn.finalText(), turn.inputTokens(), turn.outputTokens(), 1,
                "SUCCEEDED".equals(turn.status()) ? "COMPLETED" : "ABORTED");
    }

    private void insertUnknownVerificationIfMissing(
            String verifierRunId, FindingRow finding, String explanation)
    {
        if (store.verifications(finding.reviewId()).stream()
                .noneMatch(row -> finding.id().equals(row.findingId()))) {
            insertUnknownVerification(verifierRunId, finding, explanation);
        }
    }

    private void insertRejectedVerificationIfMissing(
            String verifierRunId, FindingRow finding, String explanation)
    {
        if (store.verifications(finding.reviewId()).stream()
                .noneMatch(row -> finding.id().equals(row.findingId()))) {
            insertRejectedVerification(verifierRunId, finding, explanation);
        }
    }

    private record VerifierOwner(
            ReviewAssignmentRow assignment,
            AgentRun run,
            ReviewerDefRow definition,
            AgentLaunch provider) {}

    private enum GuidanceProgress
    {
        IDLE,
        PROGRESSED,
        WAITING
    }

    /** A local provider process cannot survive a sidecar restart. Reconcile
     * persisted live rows once the app is ready so they never remain queued
     * forever or block a later round behind work that no longer exists. */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileInterruptedRounds()
    {
        if (typedReviewTurns != null) {
            for (String roundId : typedReviewTurns.incompleteRoundIds()) {
                try {
                    resumeTypedRound(roundId);
                }
                catch (RuntimeException e) {
                    log.error("Could not resume typed review round {} after startup",
                            roundId, e);
                }
            }
        }
        for (ReviewRoundRow round : store.liveRounds()) {
            if (typedReviewTurns != null && typedReviewTurns.ownsRound(round.id())) {
                continue;
            }
            try {
                reconcileInterruptedRound(round);
            }
            catch (RuntimeException e) {
                // Leave lifecycle_finalized=0 so a later restart retries the
                // remaining idempotent side effects instead of releasing a
                // queued successor behind an incomplete predecessor.
                log.error("Could not reconcile review round {} after startup",
                        round.id(), e);
            }
        }
    }

    private void reconcileInterruptedRound(ReviewRoundRow round)
    {
        AgentReviewRow review = store.findReview(round.reviewId()).orElse(null);
        boolean wasLive = Set.of("QUEUED", "RUNNING").contains(round.status());
        boolean cancelled = wasLive || "CANCELLED".equals(round.status());
        if (wasLive && !store.cancelLiveRound(round.id(), round.costCents())) {
            return;
        }
        if (cancelled) {
            store.terminalizeCancelledRoundWork(round.id());
        }
        boolean completed = round.status().startsWith("COMPLETED");
        AgentRun primary = runs.findById(round.agentRunId()).orElse(null);
        int findingCount = review == null ? 0 : (int) store.findings(review.id()).stream()
                .filter(finding -> round.id().equals(finding.roundId())).count();
        if (primary != null) {
            if (primary.headline() == null || primary.headline().isBlank()) {
                runs.updateHeadline(primary.id(), completed
                        ? findingCount + " findings" : wasLive
                                ? "Interrupted by local backend restart" : cancelled
                                        ? "Review round cancelled" : "Review round failed");
            }
            if (primary.metricsJson() == null || primary.metricsJson().isBlank()) {
                ObjectNode recoveredMetrics = mapper.createObjectNode();
                recoveredMetrics.put("recovered", true);
                recoveredMetrics.put("costCents", round.costCents());
                recoveredMetrics.put("findings", findingCount);
                runs.updateMetrics(primary.id(), recoveredMetrics.toString());
            }
        }
        Set<String> runIds = runs.findByReviewRound(round.id()).stream()
                .map(AgentRun::id).collect(Collectors.toCollection(LinkedHashSet::new));
        runIds.add(round.agentRunId());
        runIds.stream().map(runs::findById).flatMap(Optional::stream)
                .filter(AgentRun::isLive)
                .forEach(run -> runs.transition(
                        run.id(), completed ? AgentRun.STATUS_SUCCEEDED
                                : "ERRORED".equals(round.status())
                                        ? AgentRun.STATUS_FAILED : AgentRun.STATUS_CANCELLED,
                        completed ? "review round finalized during startup recovery"
                                : "local backend restarted before the review round finished"));

        if (review != null && !hasTerminalReviewEvent(review.prId(), round.id())) {
            boolean budgetHalt = "ERRORED".equals(round.status())
                    && ((primary != null && "Budget cap hit".equals(primary.headline()))
                    || round.costCents() >= round.budgetJson().costCapCents());
            String event = completed ? "round-complete" : budgetHalt
                    ? "round-budget-halted" : cancelled ? "round-cancelled" : "round-error";
            reviewEvent(review.prId(), event, node -> {
                node.put("roundId", round.id());
                node.put("recovered", true);
                if (completed) {
                    node.put("findingCount", findingCount);
                }
                else if (!budgetHalt) {
                    node.put("message", "Local backend restarted before the review round finished.");
                }
            });
        }
        if (review != null) {
            if (completed && round.endCommit() != null) {
                store.updateReviewHead(review.id(), round.endCommit(), "ACTIVE");
            }
            syncStandaloneOwnerAfterRound(
                    review, completed
                            ? "COMPLETED_WITH_QUESTIONS".equals(round.status())
                                    ? ThreadStatus.NEEDS_ATTENTION : ThreadStatus.AWAITING_REVIEW
                            : cancelled && !wasLive ? ThreadStatus.COMPLETED
                                    : ThreadStatus.NEEDS_ATTENTION,
                    completed || cancelled && !wasLive ? null
                            : "local backend restarted before the review round finished");
        }
        store.markRoundFinalized(round.id());
        log.info("reconciled interrupted review round {} after startup", round.id());
    }

    private boolean hasTerminalReviewEvent(String prId, String roundId)
    {
        Set<String> terminal = Set.of(
                "round-complete", "round-error", "round-budget-halted", "round-cancelled");
        return prs.timeline(prId).stream()
                .filter(event -> PRTimelineEntry.TYPE_REVIEW.equals(event.eventType()))
                .map(PRTimelineEntry::payloadJson)
                .filter(Objects::nonNull)
                .map(payload -> {
                    try {
                        return mapper.readTree(payload);
                    }
                    catch (Exception ignored) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .anyMatch(payload -> roundId.equals(payload.path("roundId").asText())
                        && terminal.contains(payload.path("reviewEvent").asText()));
    }

    public PlanDraft preflight(String prId)
    {
        PR pr = requirePr(prId);
        InvestigationReviewContext.Snapshot snapshot = contexts.load(pr, false);
        return cachedPlan(snapshot);
    }

    public InvestigationReviewData start(String prId, StartOptions options)
    {
        PR pr = requirePr(prId);
        boolean typed = pr.taskId() != null && tasks.isV2Task(pr.taskId());
        boolean typedLocal = typed
                && PR.ORIGIN_TASK.equals(pr.origin())
                && PR.STATUS_LOCAL_OPEN.equals(pr.status());
        if (typed && typedReviewTurns == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "V2 Task review requires its typed Review assignment handoff");
        }
        if (typedLocal && v2LocalReview == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "V2 Task review requires its Local Review owner");
        }
        Optional<AgentReviewRow> existing = store.findActiveReviewByPr(prId);
        if (existing.isPresent()) {
            AgentReviewRow owned = ensureOwnership(existing.get(), pr, options);
            if (typedLocal && "ACTIVE".equals(owned.status())) {
                store.rounds(owned.id()).stream()
                        .filter(round -> Set.of("QUEUED", "RUNNING")
                                .contains(round.status()))
                        .reduce((left, right) -> right)
                        .ifPresent(round -> {
                            if (options != null && options.blocking() != null) {
                                v2LocalReview.requestAgentReview(
                                        pr.taskId(), owned.id(), round.id(),
                                        options.blocking());
                            }
                            else {
                                v2LocalReview.continueAgentReview(
                                        pr.taskId(), owned.id(), round.id());
                            }
                        });
            }
            return detail(owned);
        }
        ReviewOwnership ownership = ownershipFor(pr, options);
        InvestigationReviewContext.Snapshot snapshot = fullReviewSnapshot(
                pr, ownership.workspaceId() != null || ownership.taskId() != null);
        PlanDraft plan = cachedPlan(snapshot);
        List<PanelSeat> panel = panel(plan.reviewClass(), options);
        Instant now = Instant.now();
        AgentReviewRow review = new AgentReviewRow(
                UUID.randomUUID().toString(), pr.repo() == null ? "local" : pr.repo(), pr.id(),
                snapshot.baseCommit(), snapshot.headCommit(), "ACTIVE", ownership.workspaceId(),
                ownership.threadId(), ownership.taskId());
        store.insertReview(review, now);
        RoundWork work;
        try {
            work = createRound(review, pr, snapshot, plan, panel,
                    "initial", "full", List.of(), null);
        }
        catch (RuntimeException e) {
            store.deleteReview(review.id());
            syncStandaloneOwner(review, ThreadStatus.ERRORED,
                    e.getMessage() == null ? "review round setup failed" : e.getMessage());
            throw e;
        }
        try {
            if (typedLocal) {
                v2LocalReview.requestAgentReview(
                        pr.taskId(), review.id(), work.round().id(), options != null
                                && Boolean.TRUE.equals(options.blocking()));
            }
            reviewEvent(pr.id(), "started", "you", node -> {
                node.put("reviewId", review.id());
                node.put("roundId", work.round().id());
                node.put("reviewClass", plan.reviewClass());
            });
            launchRound(work);
        }
        catch (RuntimeException e) {
            if (typedLocal && v2LocalReview != null) {
                v2LocalReview.cancelAgentReviewRound(
                        review.id(), work.round().id(),
                        "initial AgentReview launch failed");
            }
            abandonPreparedRound(work, "initial review setup failed");
            store.deleteReview(review.id());
            syncStandaloneOwner(review, ThreadStatus.ERRORED,
                    e.getMessage() == null ? "initial review setup failed" : e.getMessage());
            throw e;
        }
        return detail(review);
    }

    public Optional<InvestigationReviewData> findByPr(String prId)
    {
        return store.findActiveReviewByPr(prId).map(found -> {
            PR pr = requirePr(prId);
            AgentReviewRow review = ensureOwnership(found, pr, null);
            String head = contexts.headCommit(pr);
            List<ReviewRoundRow> liveRounds = store.rounds(review.id()).stream()
                    .filter(round -> Set.of("QUEUED", "RUNNING").contains(round.status()))
                    .toList();
            List<ReviewRoundRow> stopped = liveRounds.stream()
                    .filter(round -> !head.equals(round.startCommit()))
                    .filter(round -> stopRound(round, "review evidence became stale"))
                    .toList();
            boolean liveRoundFrozenAtHead = liveRounds.stream()
                    .anyMatch(round -> head.equals(round.startCommit()));
            if (!review.reviewedHeadCommit().equals(head)
                    && !"STALE".equals(review.status())
                    && !liveRoundFrozenAtHead) {
                if (store.markReviewStaleIfHeadDiffers(review.id(), head)) {
                    review = new AgentReviewRow(review.id(), review.repoId(), review.prId(),
                            review.baseCommit(), review.reviewedHeadCommit(), "STALE",
                            review.workspaceId(), review.ownerThreadId(), review.ownerTaskId());
                    syncStandaloneOwner(review, ThreadStatus.NEEDS_ATTENTION, null);
                }
                else {
                    review = store.findReview(review.id()).orElseThrow();
                }
            }
            stopped.forEach(round -> finalizeCancelledAfterSideEffects(round.id()));
            return detail(review);
        });
    }

    public Optional<InvestigationReviewData> findByOwnerThread(String threadId)
    {
        return store.findActiveReviewByOwnerThread(threadId).map(this::detail);
    }

    public Optional<InvestigationReviewData> findById(String reviewId)
    {
        return store.findReview(reviewId).map(this::detail);
    }

    /** Stop and remove every AgentReview owned by a thread before that thread
     * (or its workspace) is permanently deleted. */
    public void purgeByOwnerThread(String threadId)
    {
        for (AgentReviewRow review : store.reviewsByOwnerThread(threadId)) {
            store.rounds(review.id()).stream()
                    .filter(round -> Set.of("QUEUED", "RUNNING").contains(round.status()))
                    .forEach(this::interruptRoundForPurge);
            store.deleteReview(review.id());
            log.info("deleted agent review {} with owner thread {}", review.id(), threadId);
        }
    }

    /** Stop and remove every remaining AgentReview owned directly by a
     * workspace. Thread-owned reviews are normally removed with their trunk;
     * this sweep covers PR-owned reviews that deliberately have no trunk. */
    public void purgeByWorkspace(String workspaceId)
    {
        for (AgentReviewRow review : store.reviewsByWorkspace(workspaceId)) {
            store.rounds(review.id()).stream()
                    .filter(round -> Set.of("QUEUED", "RUNNING").contains(round.status()))
                    .forEach(this::interruptRoundForPurge);
            store.deleteReview(review.id());
            log.info("deleted agent review {} with owner workspace {}", review.id(), workspaceId);
        }
    }

    /** A purge deletes the run rows in the same transaction, so persisting a
     * cancelled JPA entity first would leave Hibernate trying to flush an
     * update after the JDBC aggregate delete. Interrupt the worker only; the
     * aggregate delete is the authoritative terminal action. */
    private void interruptRoundForPurge(ReviewRoundRow round)
    {
        Thread worker = activeRounds.get(round.id());
        if (worker != null) {
            worker.interrupt();
        }
    }

    private AgentReviewRow ensureOwnership(
            AgentReviewRow review, PR pr, StartOptions options)
    {
        if (review.ownerThreadId() != null && !review.ownerThreadId().isBlank()
                && review.workspaceId() != null && !review.workspaceId().isBlank()) {
            if (options != null
                    && review.workspaceId().equals(blankToNull(options.workspaceId()))) {
                reactivateReviewTrunk(review.ownerThreadId());
            }
            return review;
        }
        // Legacy remote reviews may have no thread or workspace. Do not
        // silently promote one just because the user opened its PR again.
        if (options == null || blankToNull(options.workspaceId()) == null) {
            return review;
        }
        ReviewOwnership owner = ownershipFor(pr, options);
        store.updateReviewOwner(review.id(), owner.workspaceId(), owner.threadId(), owner.taskId());
        return new AgentReviewRow(
                review.id(), review.repoId(), review.prId(), review.baseCommit(),
                review.reviewedHeadCommit(), review.status(), owner.workspaceId(),
                owner.threadId(), owner.taskId());
    }

    private ReviewOwnership ownershipFor(PR pr, StartOptions options)
    {
        if (pr.taskId() != null && !pr.taskId().isBlank()) {
            Task task = tasks.findTaskById(pr.taskId())
                    .orElseThrow(() -> new IllegalStateException("review PR has no task " + pr.taskId()));
            com.bytequay.app.domain.Thread thread = threads.findThreadById(task.threadId())
                    .orElseThrow(() -> new IllegalStateException("review task has no thread " + task.threadId()));
            return new ReviewOwnership(thread.workspaceId(), thread.id(), task.id());
        }

        String workspaceId = options == null ? null : blankToNull(options.workspaceId());
        if (workspaceId == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "full agent review requires a watched repository workspace");
        }
        if (workspaces == null || pr.repo() == null
                || !workspaces.ownsVerifiedLocalRepo(workspaceId, pr.repo())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "workspace must be the verified local clone for this PR repository");
        }
        return new ReviewOwnership(workspaceId, null, null);
    }

    private void reactivateReviewTrunk(String threadId)
    {
        threads.findThreadById(threadId).ifPresent(thread -> {
            if (thread.status() == ThreadStatus.RUNNING
                    && thread.endedAt() == null
                    && thread.errorMessage() == null) {
                return;
            }
            Instant now = Instant.now();
            threads.saveThread(new com.bytequay.app.domain.Thread(
                    thread.id(),
                    thread.kind(),
                    thread.provider(),
                    thread.agentSessionId(),
                    thread.title(),
                    ThreadStatus.RUNNING,
                    thread.model(),
                    thread.costUsdMilli(),
                    thread.tokensIn(),
                    thread.tokensOut(),
                    thread.createdAt(),
                    now,
                    null,
                    null,
                    thread.flow(),
                    thread.workspaceId(),
                    thread.workModel(),
                    thread.parentReviewPassId(),
                    thread.parallelSlots(),
                    thread.parentTaskId(),
                    thread.prRef()));
        });
    }

    /** Attach all remote sessions for this workspace's sole repository. The
     * existing rounds remain remote-only; a later continuation starts with
     * local workspace context. */
    public int adoptRemoteReviews(String workspaceId)
    {
        String repo = workspaces.listRepos(workspaceId).stream().findFirst()
                .map(r -> r.repoFullName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "workspace has no repository binding"));
        if (!workspaces.ownsVerifiedLocalRepo(workspaceId, repo)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "workspace repository no longer has a verified local clone");
        }
        int adopted = 0;
        for (AgentReviewRow review : store.remoteReviewsForRepo(repo)) {
            PR pr = requirePr(review.prId());
            ReviewOwnership owner = ownershipFor(pr,
                    new StartOptions(null, null, workspaceId));
            store.updateReviewOwner(review.id(), owner.workspaceId(), owner.threadId(), null);
            adopted++;
        }
        return adopted;
    }

    public List<QueueItem> queue(String scope)
    {
        String selected = scope == null ? "all" : scope.toLowerCase(Locale.ROOT);
        if (!Set.of("all", "remote", "local").contains(selected)) {
            throw new IllegalArgumentException("scope must be all, remote, or local");
        }
        return store.standaloneReviews().stream()
                .filter(review -> "all".equals(selected)
                        || ("remote".equals(selected) == (review.workspaceId() == null)))
                .map(review -> {
                    PR pr = requirePr(review.prId());
                    int findings = store.findings(review.id()).size();
                    return new QueueItem(review.id(), review.prId(), review.repoId(),
                            pr.remotePrNumber(), pr.title(), review.status(), review.workspaceId(),
                            review.ownerThreadId(), review.workspaceId() == null,
                            store.rounds(review.id()).size(), findings);
                }).toList();
    }

    /** Small persisted projection used by kanban cards; one query for the
     * whole dashboard rather than review + rounds queries per PR. */
    public Map<String, String> dashboardStates()
    {
        return store.dashboardStates();
    }

    public InvestigationReviewData createRound(
            String reviewId, String kind, List<String> findingIds, StartOptions options)
    {
        return createRound(reviewId, kind, findingIds, options, null, null);
    }

    public InvestigationReviewData createRound(
            String reviewId, String kind, List<String> findingIds, StartOptions options,
            String seed, Integer costCapCents)
    {
        AgentReviewRow review = requireReview(reviewId);
        RoundWork work = prepareRound(review, kind, findingIds, options, seed, costCapCents);
        try {
            launchRound(work);
        }
        catch (RuntimeException e) {
            abandonPreparedRound(work, "review launch failed");
            syncStandaloneOwnerAfterRound(review, ThreadStatus.ERRORED,
                    e.getMessage() == null ? "review launch failed" : e.getMessage());
            throw e;
        }
        return detail(review);
    }

    public InvestigationReviewData postRoundMessage(
            String roundId, String target, String text)
    {
        ReviewRoundRow round = store.findRound(roundId)
                .orElseThrow(() -> new IllegalArgumentException("unknown round " + roundId));
        AgentReviewRow review = requireReview(round.reviewId());
        if (!"RUNNING".equals(round.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "review round is no longer running");
        }
        String cleanTarget = requiredText(target, "target");
        String cleanText = requiredText(text, "text");
        Set<String> allowedTargets = allowedMessageTargets(review.id(), round);
        if (!allowedTargets.contains(cleanTarget)) {
            throw new IllegalArgumentException(
                    "target must be panel, planner, or an assigned reviewer");
        }
        ReviewRoundMessageRow message = new ReviewRoundMessageRow(
                UUID.randomUUID().toString(), roundId, cleanTarget, "you", cleanText,
                "pending", null, Instant.now().toEpochMilli(), null);
        if (!store.insertPendingRoundMessage(message)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "review round is finalizing or no longer running");
        }
        if (typedReviewTurns != null && typedReviewTurns.ownsRound(round.id())) {
            resumeTypedRound(round.id());
        }
        return detail(review);
    }

    private Set<String> allowedMessageTargets(String reviewId, ReviewRoundRow round)
    {
        if (!"RUNNING".equals(round.status()) || !round.messageGateOpen()) {
            return Set.of();
        }
        Set<String> guidanceAssignments = store.roundMessages(reviewId).stream()
                .map(ReviewRoundMessageRow::assignmentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<ReviewAssignmentRow> originalAssignments = store.assignments(reviewId).stream()
                .filter(assignment -> round.id().equals(assignment.roundId()))
                .filter(assignment -> !guidanceAssignments.contains(assignment.id()))
                .toList();
        Set<String> targets = new LinkedHashSet<>();
        targets.add("panel");
        store.findReviewerDef("review-planner")
                .filter(ReviewerDefRow::enabled)
                .filter(definition -> definition.eligibleKinds().contains(
                        reviewClass(round.budgetJson())))
                .ifPresent(definition -> targets.add("planner"));
        originalAssignments.stream().map(ReviewAssignmentRow::reviewerDefId)
                .filter(reviewerId -> !Set.of(
                        "review-planner", "independent-verifier").contains(reviewerId))
                .forEach(targets::add);
        Optional<ReviewerDefRow> verifierDefinition = store.findReviewerDef("independent-verifier")
                .filter(ReviewerDefRow::enabled)
                .filter(definition -> definition.eligibleKinds().contains(
                        reviewClass(round.budgetJson())));
        if (verifierDefinition.isPresent() && !originalAssignments.isEmpty()) {
            try {
                ReviewerDefRow investigatorDefinition = store.findReviewerDef(
                        originalAssignments.get(0).reviewerDefId()).orElseThrow();
                verifierProvider(
                        verifierDefinition.orElseThrow(), provider(investigatorDefinition));
                targets.add("independent-verifier");
            }
            catch (RuntimeException unavailable) {
                log.debug("Independent verifier is unavailable for round {}: {}",
                        round.id(), unavailable.getMessage());
            }
        }
        return targets;
    }

    public InvestigationReviewData updateRoundBudget(String roundId, Integer costCapCents)
    {
        int cap = validateCostCap(costCapCents);
        AgentReviewRow review;
        synchronized (budgetGuard) {
            ReviewRoundRow round = store.findRound(roundId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown round " + roundId));
            review = requireReview(round.reviewId());
            if (!"RUNNING".equals(round.status())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "review round is no longer running");
            }
            int protectedSpend = Math.max(
                    round.costCents(), inFlightBudgetFloors.getOrDefault(roundId, 0));
            if (cap < protectedSpend) {
                throw new IllegalArgumentException(
                        "costCapCents cannot be lower than current spend or an in-flight reservation");
            }
            RoundBudget budget = new RoundBudget(cap, round.budgetJson().wallClockMinutes());
            if (!store.updateRunningRoundBudget(roundId, budget)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "review round is no longer running");
            }
        }
        return detail(review);
    }

    public InvestigationReviewData answer(String findingId, String text)
    {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("answer text is required");
        }
        FindingRow finding = store.findFinding(findingId)
                .orElseThrow(() -> new IllegalArgumentException("unknown finding " + findingId));
        AgentReviewRow review = requireReview(finding.reviewId());
        RoundWork work = prepareRound(
                review, "continuation", List.of(findingId), null, null, null);
        try {
            ReviewRoundRow round = work.round();
            ReviewAssignmentRow assignment = store.assignments(review.id()).stream()
                    .filter(row -> row.roundId().equals(round.id())).findFirst().orElseThrow();
            String stepId = UUID.randomUUID().toString();
            ObjectNode arguments = mapper.createObjectNode();
            arguments.put("findingId", findingId);
            arguments.put("originalFinding", renderComment(finding));
            arguments.put("userReply", text.strip());
            store.insertStep(new InvestigationStepRow(
                    stepId, assignment.id(), null, "user-answer", arguments,
                    "User supplied explicit judgement.", false, 0, "completed"));
            String observationId = UUID.randomUUID().toString();
            store.insertObservation(new ObservationRow(
                    observationId, stepId, "user-answer", round.startCommit(), null, null, null,
                    null, null, null, null, digest(text), text.strip()));
            store.insertEvidence(new FindingEvidenceRow(
                    findingId, observationId, "SUPPORTS", text.strip(), "E3",
                    "Explicit user judgement.", "DIRECT_ONLY", mapper.createObjectNode()));
            store.updateFinding(findingId, "answered", finding.verificationStatus(),
                    "SUPPORTED", finding.claim(), finding.severity());
            reviewEvent(review.prId(), "answered", "you",
                    node -> node.put("findingId", findingId));
            launchRound(work);
        }
        catch (RuntimeException e) {
            abandonPreparedRound(work, "answer continuation setup failed");
            syncStandaloneOwnerAfterRound(review, ThreadStatus.ERRORED,
                    e.getMessage() == null ? "answer continuation setup failed" : e.getMessage());
            throw e;
        }
        return detail(review);
    }

    public InvestigationReviewData mutateFinding(String findingId, FindingMutation mutation)
    {
        FindingRow finding = store.findFinding(findingId)
                .orElseThrow(() -> new IllegalArgumentException("unknown finding " + findingId));
        AgentReviewRow review = requireReview(finding.reviewId());
        PRComment comment = prs.comments(review.prId()).stream()
                .filter(row -> findingId.equals(row.findingId()))
                .findFirst().orElse(null);
        String action = mutation == null ? "" : mutation.action();
        switch (action == null ? "" : action) {
            case "dismiss" -> {
                store.updateFinding(findingId, "dismissed", finding.verificationStatus(),
                        finding.confidenceClass(), finding.claim(), finding.severity());
                if (comment != null && comment.dismissedAt() == null) {
                    prs.dismissComment(comment.id());
                }
                store.insertOutcome(new ReviewOutcomeRow(
                        findingId, "dismissed", "ignored", "unresolved", "not useful", 0));
            }
            case "include" -> store.updateFinding(findingId, "included", finding.verificationStatus(),
                    finding.confidenceClass(), finding.claim(), finding.severity());
            case "exclude" -> store.updateFinding(findingId, "excluded", finding.verificationStatus(),
                    finding.confidenceClass(), finding.claim(), finding.severity());
            case "resolve" -> {
                store.updateFinding(findingId, "resolved", finding.verificationStatus(),
                        finding.confidenceClass(), finding.claim(), finding.severity());
                if (comment != null && comment.resolvedAt() == null) {
                    if (comment.dismissedAt() != null) {
                        prs.reopenComment(comment.id());
                    }
                    prs.resolveComment(comment.id());
                }
                store.insertOutcome(new ReviewOutcomeRow(
                        findingId, "resolved", "ignored", "unresolved",
                        "resolved locally by reviewer", 0));
            }
            case "reopen" -> {
                store.updateFinding(findingId, "open", finding.verificationStatus(),
                        finding.confidenceClass(), finding.claim(), finding.severity());
                if (comment != null
                        && (comment.resolvedAt() != null || comment.dismissedAt() != null)) {
                    prs.reopenComment(comment.id());
                }
                store.insertOutcome(new ReviewOutcomeRow(
                        findingId, "deferred", "ignored", "unresolved",
                        "reopened by reviewer", 0));
            }
            case "editDraft" -> {
                if (comment == null) {
                    throw new IllegalStateException("finding has no pending comment");
                }
                prs.editCommentBody(comment.id(), mutation.text());
                store.insertOutcome(new ReviewOutcomeRow(
                        findingId, "edited", "ignored", "unresolved", "pending author response",
                        editMagnitude(comment.body(), mutation.text())));
            }
            default -> throw new IllegalArgumentException("unknown finding action: " + action);
        }
        reviewEvent(review.prId(), "dismiss".equals(action) ? "dismissed" : "finding-updated", "you",
                node -> node.put("findingId", findingId));
        return detail(review);
    }

    /** Persist an author disposition observed by PR synchronization. */
    public InvestigationReviewData recordFindingOutcome(
            String findingId, FindingOutcomeInput input)
    {
        FindingRow finding = store.findFinding(findingId)
                .orElseThrow(() -> new IllegalArgumentException("unknown finding " + findingId));
        if (input == null) {
            throw new IllegalArgumentException("finding outcome is required");
        }
        String disposition = requiredText(input.userDisposition(), "user_disposition");
        String authorResponse = requiredText(input.authorResponse(), "author_response");
        String resolution = requiredText(input.epistemicResolution(), "epistemic_resolution");
        if (!Set.of("fixed", "acknowledged", "disagreed", "ignored").contains(authorResponse)) {
            throw new IllegalArgumentException(
                    "author_response must be fixed, acknowledged, disagreed, or ignored");
        }
        if (!Set.of("confirmed", "refuted", "unresolved").contains(resolution)) {
            throw new IllegalArgumentException(
                    "epistemic_resolution must be confirmed, refuted, or unresolved");
        }
        int magnitude = input.styleEditMagnitude() == null ? 0
                : Math.max(0, Math.min(100, input.styleEditMagnitude()));
        store.insertOutcome(new ReviewOutcomeRow(
                findingId, disposition, authorResponse, resolution,
                input.utilityAssessment() == null ? "not assessed"
                        : input.utilityAssessment().strip(),
                magnitude));
        if ("fixed".equals(authorResponse)) {
            store.updateFinding(findingId, "fixed", finding.verificationStatus(),
                    finding.confidenceClass(), finding.claim(), finding.severity());
        }
        AgentReviewRow review = requireReview(finding.reviewId());
        reviewEvent(review.prId(), "author-outcome", node -> {
            node.put("findingId", findingId);
            node.put("authorResponse", authorResponse);
            node.put("epistemicResolution", resolution);
        });
        return detail(review);
    }

    public void recordPublished(
            String prId, String verdict, List<String> findingIds, List<String> commentIds)
    {
        Optional<AgentReviewRow> found = store.findActiveReviewByPr(prId);
        if (found.isEmpty() || (findingIds == null && commentIds == null)) {
            return;
        }
        AgentReviewRow review = ensureOwnership(found.orElseThrow(), requirePr(prId), null);
        Set<String> publishedFindings = findingIds == null
                ? Set.of() : new LinkedHashSet<>(findingIds);
        for (String findingId : publishedFindings) {
            store.findFinding(findingId).ifPresent(finding -> {
                store.updateFinding(finding.id(), "published", finding.verificationStatus(),
                        finding.confidenceClass(), finding.claim(), finding.severity());
                store.insertOutcome(new ReviewOutcomeRow(
                        finding.id(), "published", "ignored", "unresolved",
                        "pending author response", 0));
            });
        }
        int count = commentIds == null
                ? publishedFindings.size() : new LinkedHashSet<>(commentIds).size();
        reviewEvent(prId, "submitted", "you", node -> {
            node.put("count", count);
            node.put("verdict", verdict == null ? "COMMENT" : verdict);
        });
        syncStandaloneOwner(review, ThreadStatus.COMPLETED, null);
    }

    public InvestigationReviewData roundLog(String roundId)
    {
        ReviewRoundRow round = store.findRound(roundId)
                .orElseThrow(() -> new IllegalArgumentException("unknown round " + roundId));
        return detail(requireReview(round.reviewId()));
    }

    public InvestigationReviewData cancelRound(String roundId)
    {
        ReviewRoundRow round = store.findRound(roundId)
                .orElseThrow(() -> new IllegalArgumentException("unknown round " + roundId));
        AgentReviewRow review = requireReview(round.reviewId());
        if (!stopRound(round, "review round stopped by user")) {
            if (v2LocalReview != null && isTypedReview(review)) {
                v2LocalReview.cancelAgentReviewRound(
                        review.id(), round.id(),
                        "AgentReview attachment closed by user");
            }
            return detail(review);
        }
        reviewEvent(review.prId(), "round-cancelled", "you",
                node -> node.put("roundId", round.id()));
        if (v2LocalReview != null && isTypedReview(review)) {
            v2LocalReview.cancelAgentReviewRound(
                    review.id(), round.id(), "AgentReview canceled by user");
        }
        syncStandaloneOwnerAfterRound(review, ThreadStatus.COMPLETED, null);
        finalizeCancelledAfterSideEffects(round.id());
        return detail(review);
    }

    public V2LocalReviewControl.Submission importLocalFindings(
            String reviewId, String reviewRoundId, List<String> findingIds)
    {
        AgentReviewRow review = requireReview(reviewId);
        if (!isTypedReview(review) || v2LocalReview == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "selected finding import requires a V2 Task Local Review");
        }
        return v2LocalReview.importSelectedFindings(
                reviewId, reviewRoundId, findingIds);
    }

    private boolean stopRound(ReviewRoundRow round, String reason)
    {
        boolean finished = store.cancelLiveRound(round.id(), round.costCents());
        if (!finished) {
            return false;
        }
        if (typedReviewTurns != null && typedReviewTurns.ownsRound(round.id())) {
            typedReviewTurns.cancelRound(round.id());
        }
        try {
            Set<String> runIds = runs.findByReviewRound(round.id()).stream()
                    .map(AgentRun::id).collect(Collectors.toCollection(LinkedHashSet::new));
            runIds.add(round.agentRunId()); // Covers rows created before round ownership was persisted.
            runIds.stream().map(runs::findById).flatMap(Optional::stream)
                    .filter(AgentRun::isLive)
                    .forEach(run -> runs.transition(run.id(), AgentRun.STATUS_CANCELLED, reason));
        }
        finally {
            Thread worker = activeRounds.get(round.id());
            if (worker != null) {
                worker.interrupt();
            }
        }
        return finished;
    }

    public InvestigationReviewData retryAssignment(
            String reviewId, String assignmentId)
    {
        AgentReviewRow review = requireReview(reviewId);
        if (typedReviewTurns == null || !isTypedReview(review)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "only a typed V2 review assignment can be retried here");
        }
        boolean exact = store.assignments(review.id()).stream()
                .anyMatch(assignment -> assignment.id().equals(assignmentId));
        if (!exact) {
            throw new IllegalArgumentException(
                    "assignment does not belong to review " + reviewId);
        }
        typedReviewTurns.retryAssignment(assignmentId);
        return detail(review);
    }

    private void finalizeCancelledAfterSideEffects(String roundId)
    {
        cancellationSideEffectsDone.add(roundId);
        if (!activeRounds.containsKey(roundId)) {
            store.markRoundFinalized(roundId);
            cancellationSideEffectsDone.remove(roundId);
        }
    }

    public List<ReviewerDefRow> reviewerDefs()
    {
        return store.reviewerDefs();
    }

    public ReviewerDefRow saveReviewerDef(String pathId, ReviewerDefInput input)
    {
        if (input == null) {
            throw new IllegalArgumentException("reviewer definition is required");
        }
        String id = pathId == null || pathId.isBlank()
                ? input.id() == null || input.id().isBlank() ? UUID.randomUUID().toString() : input.id().strip()
                : pathId.strip();
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) {
            throw new IllegalArgumentException("reviewer id must use letters, numbers, '.', '_' or '-'");
        }
        String runnerKind = requiredText(input.runner(), "runner").toLowerCase(Locale.ROOT);
        if (!Set.of("api", "cli").contains(runnerKind)) {
            throw new IllegalArgumentException("runner must be api or cli");
        }
        List<String> eligible = input.eligibleKinds() == null ? List.of() : input.eligibleKinds().stream()
                .map(value -> value == null ? "" : value.strip().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank()).distinct().toList();
        if (eligible.isEmpty() || !Set.of("trivial", "standard", "high-risk").containsAll(eligible)) {
            throw new IllegalArgumentException("eligible_kinds must contain review classes");
        }
        ReviewerDefRow row = new ReviewerDefRow(
                id, requiredText(input.name(), "name"),
                requiredText(input.description(), "description"), runnerKind,
                input.runnerJson() == null ? mapper.createObjectNode() : input.runnerJson(),
                blankToNull(input.persona()), eligible, input.enabled() == null || input.enabled());
        store.upsertReviewerDef(row);
        return row;
    }

    public boolean disableReviewerDef(String id)
    {
        return store.disableReviewerDef(id);
    }

    private RoundWork createRound(
            AgentReviewRow review, PR pr, InvestigationReviewContext.Snapshot snapshot,
            PlanDraft plan, List<PanelSeat> panel, String trigger, String scope,
            List<String> findingIds, String seed)
    {
        String roundId = UUID.randomUUID().toString();
        AgentRun run = openReviewRun(review, pr, roundId, plan.budget().costCapCents());
        ReviewRoundRow proposed = new ReviewRoundRow(
                roundId, review.id(), run.id(), trigger, scope, snapshot.headCommit(), null,
                "RUNNING", plan.budget(), 0,
                snapshot.capabilities(), null, true);
        ReviewRoundRow round = store.insertLiveRound(proposed, Instant.now());
        try {
            freezeReviewedCommits(review, pr, round, snapshot);
            if (seed != null) {
                long now = Instant.now().toEpochMilli();
                store.insertRoundMessage(new ReviewRoundMessageRow(
                        UUID.randomUUID().toString(), roundId, "panel", "you", seed,
                        "completed", null, now, now));
            }
            List<ReviewObjectiveRow> objectives = persistPlan(review, round, plan, findingIds);
            List<AssignmentWork> assignments = new ArrayList<>();
            for (PanelSeat seat : panel) {
                ProviderChoice investigator = seat.provider();
                ReviewerDefRow reviewerDef = seat.reviewerDef();
                String assignmentId = UUID.randomUUID().toString();
                store.insertAssignment(new ReviewAssignmentRow(
                        assignmentId, roundId, reviewerDef.id(),
                        investigator.runner(), "queued", "", List.of(), List.of(),
                        new AssignmentBudget(6, 3, 12, 5)));
                assignments.add(new AssignmentWork(assignmentId, investigator, reviewerDef));
            }
            return new RoundWork(review, pr, snapshot, plan, round, run, objectives,
                    List.copyOf(assignments), seed);
        }
        catch (RuntimeException e) {
            store.cancelLiveRound(round.id(), 0);
            store.terminalizeCancelledRoundWork(round.id());
            try {
                runs.findById(run.id()).filter(AgentRun::isLive).ifPresent(row ->
                        runs.transition(row.id(), AgentRun.STATUS_FAILED,
                                "review round setup failed"));
            }
            catch (RuntimeException transitionFailure) {
                log.warn("Could not transition failed setup run {}: {}",
                        run.id(), transitionFailure.getMessage());
            }
            store.markRoundFinalized(round.id());
            throw e;
        }
    }

    /** AgentReview is an artifact track, not a development stage. */
    private AgentRun openReviewRun(
            AgentReviewRow review, PR pr, String roundId, int budget)
    {
        AgentRun run;
        if (review.ownerTaskId() != null && !isTypedReview(review)) {
            run = runs.openTaskArtifact(review.ownerTaskId(), AgentRun.KIND_PANEL_REVIEW,
                    null, roundId, budget);
        }
        else {
            run = runs.openDetached(
                    AgentRun.KIND_PANEL_REVIEW, null, roundId, budget);
        }
        if (review.workspaceId() == null) {
            return run;
        }
        if (review.ownerThreadId() == null) {
            String ref = pr.repo() == null || pr.remotePrNumber() == null
                    ? pr.title() : pr.repo() + "#" + pr.remotePrNumber();
            return runs.attachOwnership(
                    run.id(), review.workspaceId(), null,
                    "agent-review", "agent-review", "Review " + ref);
        }
        com.bytequay.app.domain.Thread owner = threads
                .findThreadById(review.ownerThreadId())
                .orElseThrow(() -> new IllegalStateException(
                        "review has no owning trunk " + review.ownerThreadId()));
        String launchInput = owner.prRef() == null || owner.prRef().isBlank()
                ? "Review pull request"
                : "Review " + owner.prRef();
        return runs.attachOwnership(
                run.id(), review.workspaceId(), owner.id(),
                owner.provider(), owner.model(), launchInput);
    }

    private RoundWork prepareRound(
            AgentReviewRow review, String kind, List<String> findingIds, StartOptions options,
            String seed, Integer costCapCents)
    {
        PR pr = requirePr(review.prId());
        InvestigationReviewContext.Snapshot snapshot = fullReviewSnapshot(
                pr, review.workspaceId() != null || review.ownerTaskId() != null);
        PlanDraft plan = cachedPlan(snapshot);
        String cleanSeed = seed == null ? null : requiredText(seed, "seed");
        if (cleanSeed != null) {
            plan = new PlanDraft(
                    plan.reviewClass(), plan.budget(),
                    List.of(new PlanObjective(
                            "engineering-principle", cleanSeed,
                            "user-guidance", "round-seed", true)),
                    plan.plannerSuggestion());
        }
        if (costCapCents != null) {
            plan = new PlanDraft(
                    plan.reviewClass(),
                    new RoundBudget(
                            validateCostCap(costCapCents), plan.budget().wallClockMinutes()),
                    plan.objectives(), plan.plannerSuggestion());
        }
        List<PanelSeat> panel = panel(plan.reviewClass(), options);
        List<String> selected = findingIds == null ? List.of() : findingIds;
        RoundWork work = createRound(review, pr, snapshot, plan, panel,
                normaliseRoundKind(kind), scopeFor(selected, cleanSeed), selected, cleanSeed);
        try {
            if (isTypedReview(review)
                    && PR.ORIGIN_TASK.equals(pr.origin())
                    && PR.STATUS_LOCAL_OPEN.equals(pr.status())) {
                requireNonNull(v2LocalReview, "V2 Local Review owner is unavailable")
                        .continueAgentReview(
                                review.ownerTaskId(), review.id(), work.round().id());
            }
            store.updateReviewStatus(review.id(), "ACTIVE");
            syncStandaloneOwner(review, ThreadStatus.RUNNING, null);
            reviewEvent(pr.id(), "round-started", "you",
                    node -> node.put("roundId", work.round().id()));
            return work;
        }
        catch (RuntimeException e) {
            abandonPreparedRound(work, "review round setup failed");
            syncStandaloneOwnerAfterRound(review, ThreadStatus.ERRORED,
                    e.getMessage() == null ? "review round setup failed" : e.getMessage());
            throw e;
        }
    }

    private void abandonPreparedRound(RoundWork work, String reason)
    {
        if (stopRound(work.round(), reason)) {
            finalizeCancelledAfterSideEffects(work.round().id());
        }
    }

    private void freezeReviewedCommits(
            AgentReviewRow review, PR pr, ReviewRoundRow round,
            InvestigationReviewContext.Snapshot snapshot)
    {
        List<PRCommit> commits = prs.commits(pr.id());
        int head = -1;
        for (int i = 0; i < commits.size(); i++) {
            if (snapshot.headCommit().equals(commits.get(i).sha())) {
                head = i;
            }
        }
        if (head < 0) {
            return;
        }
        int first = 0;
        if (!"initial".equals(round.trigger())) {
            int prior = -1;
            for (int i = 0; i <= head; i++) {
                if (review.reviewedHeadCommit().equals(commits.get(i).sha())) {
                    prior = i;
                }
            }
            first = prior < 0 ? 0 : prior + 1;
            if (first > head) {
                first = head;
            }
        }
        int position = 0;
        for (int i = first; i <= head; i++) {
            PRCommit commit = commits.get(i);
            store.insertReviewedCommit(new ReviewedCommitRow(
                    round.id(), commit.sha(), commit.message(), position++));
        }
    }

    private List<ReviewObjectiveRow> persistPlan(
            AgentReviewRow review, ReviewRoundRow round, PlanDraft plan,
            List<String> findingIds)
    {
        List<ReviewObjectiveRow> rows = new ArrayList<>();
        Map<String, ReviewObjectiveRow> priorObjectives = store.objectives(review.id()).stream()
                .collect(Collectors.toMap(
                        ReviewObjectiveRow::id, row -> row, (left, right) -> left));
        Set<String> selectedCriterionIds = store.findings(review.id()).stream()
                .filter(finding -> findingIds.contains(finding.id()))
                .map(FindingRow::objectiveId)
                .map(priorObjectives::get)
                .filter(Objects::nonNull)
                .map(ReviewObjectiveRow::criterionId)
                .collect(Collectors.toSet());
        for (PlanObjective objective : plan.objectives()) {
            String criterionId = stableCriterionId(review.repoId(), objective);
            if (!findingIds.isEmpty()) {
                if (!selectedCriterionIds.contains(criterionId)) {
                    continue;
                }
            }
            store.insertCriterion(new CriterionRow(
                    criterionId, review.repoId(), objective.kind(), objective.statement(),
                    objective.sourceType(), objective.sourceRef()));
            String applicability = objective.applicable() ? "applicable" : "not-applicable";
            ReviewObjectiveRow row = new ReviewObjectiveRow(
                    UUID.randomUUID().toString(), round.id(), criterionId,
                    objective.statement(), objective.sourceType(), applicability,
                    objective.applicable() ? "pending" : "not-applicable");
            store.insertObjective(row);
            rows.add(row);
        }
        if (rows.isEmpty()) {
            PlanObjective fallback = plan.objectives().stream().filter(PlanObjective::applicable)
                    .findFirst().orElse(plan.objectives().get(0));
            String criterionId = stableCriterionId(review.repoId(), fallback);
            store.insertCriterion(new CriterionRow(
                    criterionId, review.repoId(), fallback.kind(), fallback.statement(),
                    fallback.sourceType(), fallback.sourceRef()));
            ReviewObjectiveRow row = new ReviewObjectiveRow(
                    UUID.randomUUID().toString(), round.id(), criterionId,
                    fallback.statement(), fallback.sourceType(), "applicable", "pending");
            store.insertObjective(row);
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private void execute(RoundWork work)
    {
        int cost = 0;
        try {
            ensureRunning(work);
            List<RunOutcome> investigations = new ArrayList<>();
            CoverageReport coverage = persistMandatorySweeps(
                    work, work.assignments().get(0).id());
            List<ReviewObjectiveRow> applicableObjectives = work.objectives().stream()
                    .filter(objective -> "applicable".equals(objective.applicabilityStatus()))
                    .toList();
            String investigationContext = investigationContext(work, coverage.promptContext());
            boolean initialInvestigationAborted = false;
            assignmentLoop:
            for (int index = 0; index < work.assignments().size(); index++) {
                AssignmentWork assignment = work.assignments().get(index);
                int remainingAssignments = work.assignments().size() - index;
                while (true) {
                    int remaining = remainingBudget(work, cost);
                    if (remaining == 0) {
                        initialInvestigationAborted = true;
                        break assignmentLoop;
                    }
                    BudgetReservation reservation = reserveProviderTurn(
                            work, cost, Math.max(1, remaining / remainingAssignments));
                    int capAtLaunch = reservation.roundCap();
                    int assignmentCap = reservation.turnCap();
                    if (assignmentCap == 0) {
                        initialInvestigationAborted = true;
                        break assignmentLoop;
                    }
                    RunOutcome investigation;
                    try {
                        investigation = runner.investigate(
                                assignment.provider(), work.review().id(), assignment.id(),
                                work.snapshot(), applicableObjectives,
                                investigationContext, assignment.reviewerDef().persona(), assignmentCap);
                    }
                    catch (RuntimeException e) {
                        releaseProviderTurn(work);
                        throw e;
                    }
                    cost += investigation.costCents();
                    completeProviderTurn(work, cost);
                    ensureRunning(work);
                    investigations.add(investigation);
                    failIfProviderErrored(investigation, "investigation");
                    boolean aborted = "ABORTED".equals(investigation.end());
                    store.skipRunningSteps(assignment.id());
                    cost = drainRoundMessages(
                            work, investigations, applicableObjectives,
                            coverage.promptContext(), cost);
                    boolean retryWithRaisedBudget = aborted
                            && investigation.costCents() > 0
                            && currentCostCap(work) > capAtLaunch
                            && remainingBudget(work, cost) > 0;
                    if (retryWithRaisedBudget) {
                        continue;
                    }
                    ReviewAssignmentRow recorded = store.assignments(work.review().id()).stream()
                            .filter(row -> row.id().equals(assignment.id()))
                            .findFirst()
                            .orElseThrow();
                    store.updateAssignmentWhileRoundRunning(
                            assignment.id(), aborted ? "aborted" : "completed",
                            recorded.understandingSummary().isBlank()
                                    ? "Investigation complete." : recorded.understandingSummary(),
                            recorded.assumptionsJson(), recorded.unknownsJson());
                    if (aborted && remainingBudget(work, cost) == 0) {
                        initialInvestigationAborted = true;
                        break assignmentLoop;
                    }
                    // A panel seat owns only a slice of the round cap. Hitting
                    // that slice must not discard the still-reserved budget
                    // and skip the remaining independent seats.
                    break;
                }
            }
            if (initialInvestigationAborted) {
                if (!store.finishRunningRound(work.round().id(), "ERRORED", null, cost)) {
                    return;
                }
                runs.updateHeadline(work.run().id(), "Budget cap hit");
                runs.updateMetrics(work.run().id(), metrics(
                        work, investigations, VerificationOutcome.EMPTY, List.of(), cost).toString());
                runs.transition(work.run().id(), AgentRun.STATUS_FAILED, "review budget cap hit");
                reviewEvent(work.pr().id(), "round-budget-halted",
                        node -> node.put("roundId", work.round().id()));
                syncStandaloneOwnerAfterRound(
                        work.review(), ThreadStatus.ERRORED, "review budget cap hit");
                store.markRoundFinalized(work.round().id());
                return;
            }
            cost = drainAndCloseMessageGate(
                    work, investigations, applicableObjectives,
                    coverage.promptContext(), cost);
            List<FindingEvidenceRow> roundEvidence = store.evidence(work.review().id());
            Map<String, List<FindingEvidenceRow>> roundEvidenceByFinding = roundEvidence.stream()
                    .collect(Collectors.groupingBy(FindingEvidenceRow::findingId));
            Map<String, ObservationRow> roundObservationsById = observationsById(work.review().id());
            Set<String> findingsWithRefutes = roundEvidence.stream()
                    .filter(edge -> "REFUTES".equals(edge.relation()))
                    .map(FindingEvidenceRow::findingId)
                    .collect(Collectors.toSet());
            List<FindingRow> missingRefutationPass = store.findings(work.review().id()).stream()
                    .filter(finding -> finding.roundId().equals(work.round().id()))
                    .filter(finding -> !findingsWithRefutes.contains(finding.id()))
                    .toList();
            if (!missingRefutationPass.isEmpty()) {
                int remaining = remainingBudget(work, cost);
                if (remaining == 0) {
                    for (FindingRow finding : missingRefutationPass) {
                        insertUnknownVerification(work.run().id(), finding,
                                "Round cost cap was reached before the mandatory counter-evidence pass.");
                    }
                }
                else {
                    AssignmentWork primary = work.assignments().get(0);
                    int refutationCap = reserveProviderTurn(
                            work, cost, Math.max(1, Math.min(10, remaining / 4))).turnCap();
                    if (refutationCap == 0) {
                        for (FindingRow finding : missingRefutationPass) {
                            insertUnknownVerification(work.run().id(), finding,
                                    "Round cost cap was lowered before the mandatory counter-evidence pass.");
                        }
                    }
                    else {
                        RunOutcome refutation;
                        try {
                            refutation = runner.selfRefute(
                                    primary.provider(), work.review().id(), primary.id(), work.snapshot(),
                                    missingRefutationPass.stream()
                                            .map(finding -> findingBundle(
                                                    finding,
                                                    roundEvidenceByFinding.getOrDefault(
                                                            finding.id(), List.of()),
                                                    roundObservationsById))
                                            .reduce("", (left, right) -> left + "\n---\n" + right),
                                    refutationCap);
                        }
                        catch (RuntimeException e) {
                            releaseProviderTurn(work);
                            throw e;
                        }
                        cost += refutation.costCents();
                        completeProviderTurn(work, cost);
                        ensureRunning(work);
                        investigations.add(refutation);
                        failIfProviderErrored(refutation, "self-refutation");
                        recordSelfRefutationPass(
                                primary.id(), missingRefutationPass.size(), refutation);
                        if ("ABORTED".equals(refutation.end())) {
                            throw new IllegalStateException(
                                    "mandatory self-refutation pass exceeded its budget");
                        }
                    }
                }
            }
            List<FindingRow> candidates = consolidate(store.findings(work.review().id()).stream()
                    .filter(finding -> finding.roundId().equals(work.round().id()))
                    .toList());
            VerificationOutcome verification;
            if (candidates.isEmpty()) {
                verification = VerificationOutcome.EMPTY;
            }
            else if (remainingBudget(work, cost) == 0) {
                Set<String> alreadyVerified = store.verifications(work.review().id()).stream()
                        .map(FindingVerificationRow::findingId).collect(Collectors.toSet());
                candidates.stream().filter(finding -> !alreadyVerified.contains(finding.id()))
                        .forEach(finding -> insertUnknownVerification(
                                work.run().id(), finding,
                                "Round cost cap was reached before verification."));
                verification = VerificationOutcome.EMPTY;
            }
            else {
                verification = "trivial".equals(work.plan().reviewClass())
                        ? verifyTrivial(work, candidates) : verify(work, candidates, cost);
            }
            ensureRunning(work);
            cost += verification.costCents();
            List<FindingRow> finished = store.findings(work.review().id()).stream()
                    .filter(finding -> finding.roundId().equals(work.round().id()))
                    .toList();
            materialiseComments(work, finished);
            appendAnswerReply(work, investigations);
            boolean coverageGaps = resolveObjectives(work, finished);
            boolean questions = coverageGaps || finished.stream()
                    .anyMatch(f -> !"dropped".equals(f.lifecycleStatus())
                            && "unknown".equals(f.verificationStatus()));
            String status = questions ? "COMPLETED_WITH_QUESTIONS" : "COMPLETED";
            if (!store.finishRunningRoundAndAdvanceReview(
                    work.round().id(), status, work.snapshot().headCommit(), cost)) {
                return;
            }
            runs.updateHeadline(work.run().id(), finished.size() + " findings");
            runs.updateMetrics(work.run().id(), metrics(
                    work, investigations, verification, finished, cost).toString());
            runs.transition(work.run().id(), AgentRun.STATUS_SUCCEEDED, "investigation review complete");
            reviewEvent(work.pr().id(), "round-complete", node -> {
                node.put("roundId", work.round().id());
                node.put("findingCount", finished.size());
            });
            syncStandaloneOwnerAfterRound(work.review(), questions
                    ? ThreadStatus.NEEDS_ATTENTION : ThreadStatus.AWAITING_REVIEW, null);
            store.markRoundFinalized(work.round().id());
        }
        catch (RuntimeException e) {
            // Providers preserve cancellation interrupts. Consume the flag so
            // terminal database work can acquire the single SQLite connection.
            Thread.interrupted();
            if (store.findRound(work.round().id())
                    .map(round -> "CANCELLED".equals(round.status())).orElse(false)) {
                return;
            }
            log.error("Investigation round {} failed", work.round().id(), e);
            if (!store.finishRunningRound(work.round().id(), "ERRORED", null, cost)) {
                return;
            }
            runs.transition(work.run().id(), AgentRun.STATUS_FAILED, e.getMessage());
            reviewEvent(work.pr().id(), "round-error", node -> {
                node.put("roundId", work.round().id());
                node.put("message", e.getMessage() == null ? "review failed" : e.getMessage());
            });
            syncStandaloneOwnerAfterRound(work.review(), ThreadStatus.ERRORED,
                    e.getMessage() == null ? "review failed" : e.getMessage());
            store.markRoundFinalized(work.round().id());
        }
    }

    /** Mirror the standalone review lifecycle into its lightweight owner
     * thread so workspace navigation, status filters, and spend metrics see
     * the review. Task-owned reviews deliberately leave the development
     * thread alone; their cost/status is presented by the task aggregate. */
    private void syncStandaloneOwner(
            AgentReviewRow review, ThreadStatus status, String errorMessage)
    {
        if (review.ownerTaskId() != null || review.ownerThreadId() == null) {
            return;
        }
        threads.findThreadById(review.ownerThreadId()).ifPresent(current -> {
            Instant now = Instant.now();
            long costUsdMilli = store.rounds(review.id()).stream()
                    .mapToLong(ReviewRoundRow::costCents)
                    .sum() * 10L;
            boolean terminal = status == ThreadStatus.COMPLETED || status == ThreadStatus.ERRORED;
            threads.saveThread(new com.bytequay.app.domain.Thread(
                    current.id(), current.kind(), current.provider(), current.agentSessionId(),
                    current.title(), status, current.model(), costUsdMilli,
                    current.tokensIn(), current.tokensOut(), current.createdAt(), now,
                    terminal ? now : null, status == ThreadStatus.ERRORED ? errorMessage : null,
                    current.flow(), current.workspaceId(), current.workModel(),
                    current.parentReviewPassId(), current.parallelSlots(), current.parentTaskId()));
        });
    }

    private void syncStandaloneOwnerAfterRound(
            AgentReviewRow review, ThreadStatus terminalStatus, String errorMessage)
    {
        AgentReviewRow current = store.findReview(review.id()).orElse(review);
        if ("STALE".equals(current.status())) {
            syncStandaloneOwner(current, ThreadStatus.NEEDS_ATTENTION, null);
            return;
        }
        boolean anotherRoundIsLive = store.rounds(review.id()).stream()
                .anyMatch(round -> Set.of("QUEUED", "RUNNING").contains(round.status()));
        syncStandaloneOwner(
                review, anotherRoundIsLive ? ThreadStatus.RUNNING : terminalStatus,
                anotherRoundIsLive ? null : errorMessage);
        store.findReview(review.id()).filter(row -> "STALE".equals(row.status()))
                .ifPresent(row -> syncStandaloneOwner(
                        row, ThreadStatus.NEEDS_ATTENTION, null));
    }

    private void resyncStandaloneOwnerCost(AgentReviewRow review)
    {
        if (review.ownerTaskId() != null || review.ownerThreadId() == null) {
            return;
        }
        threads.findThreadById(review.ownerThreadId()).ifPresent(current ->
                syncStandaloneOwner(review, current.status(), current.errorMessage()));
    }

    private void launchRound(RoundWork work)
    {
        if (!isTypedReview(work.review())) {
            launchLegacy(work);
            return;
        }
        if (typedReviewTurns == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "V2 Task review requires its typed Review assignment handoff");
        }
        CoverageReport coverage = persistMandatorySweeps(
                work, work.assignments().get(0).id());
        List<ReviewObjectiveRow> applicableObjectives = work.objectives().stream()
                .filter(objective -> "applicable".equals(objective.applicabilityStatus()))
                .toList();
        String context = investigationContext(work, coverage.promptContext());
        Path workingDirectory = work.snapshot().localRoot() == null
                ? Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
                : work.snapshot().localRoot().toAbsolutePath().normalize();
        List<Seat> seats = work.assignments().stream()
                .map(assignment -> {
                    ReviewTurnPrompt prompt = runner.investigationPrompt(
                            work.review().id(), work.snapshot(), applicableObjectives,
                            context, assignment.reviewerDef().persona());
                    return new Seat(
                            assignment.id(), assignment.provider(), workingDirectory, prompt);
                })
                .toList();
        typedReviewTurns.admit(
                work.round().id(), work.round().startCommit(), seats);
        registerTypedRound(
                work.review().ownerTaskId(), work.review().id(), work.round().id());
    }

    private boolean isTypedReview(AgentReviewRow review)
    {
        return review.ownerTaskId() != null && tasks.isV2Task(review.ownerTaskId());
    }

    private void launchLegacy(RoundWork work)
    {
        Thread worker = Thread.ofVirtual().unstarted(() -> {
            try {
                while (true) {
                    ReviewRoundRow current = store.findRound(work.round().id()).orElse(null);
                    if (current == null || !Set.of("QUEUED", "RUNNING").contains(current.status())) {
                        return;
                    }
                    if ("RUNNING".equals(current.status())) {
                        execute(work);
                        return;
                    }
                    if (store.queuedRoundCanStart(work.round().id())
                            && store.startQueuedRound(work.round().id())) {
                        syncStandaloneOwner(work.review(), ThreadStatus.RUNNING, null);
                        execute(work);
                        return;
                    }
                    try {
                        Thread.sleep(25);
                    }
                    catch (InterruptedException e) {
                        // Cancellation cleanup below still needs a database connection.
                        return;
                    }
                }
            }
            finally {
                // The worker is terminating; cleanup must not inherit an
                // interrupt that makes Hikari reject connection acquisition.
                Thread.interrupted();
                inFlightBudgetFloors.remove(work.round().id());
                String status = store.findRound(work.round().id())
                        .map(ReviewRoundRow::status).orElse(null);
                if ("CANCELLED".equals(status)) {
                    // Keep the worker registered until the final write fence
                    // is complete so the request-side finalizer cannot
                    // release a queued successor into late callbacks.
                    store.terminalizeCancelledRoundWork(work.round().id());
                }
                activeRounds.remove(work.round().id(), Thread.currentThread());
                if ("CANCELLED".equals(status)) {
                    resyncStandaloneOwnerCost(work.review());
                    if (cancellationSideEffectsDone.remove(work.round().id())) {
                        store.markRoundFinalized(work.round().id());
                    }
                }
                else if (status != null && !"RUNNING".equals(status)
                        && !"QUEUED".equals(status)
                        && !store.isRoundFinalized(work.round().id())) {
                    recoverTerminalLifecycleAfterWorker(work.round().id());
                }
            }
        });
        activeRounds.put(work.round().id(), worker);
        worker.start();
    }

    void recoverTerminalLifecycleAfterWorker(String roundId)
    {
        RuntimeException failure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                ReviewRoundRow round = store.findRound(roundId).orElseThrow();
                reconcileInterruptedRound(round);
                return;
            }
            catch (RuntimeException e) {
                failure = e;
            }
        }
        log.error("Review round {} terminal side effects failed after retries; "
                + "leaving it fenced for startup recovery", roundId, failure);
    }

    private void ensureRunning(RoundWork work)
    {
        boolean running = store.findRound(work.round().id())
                .map(round -> "RUNNING".equals(round.status())).orElse(false);
        if (!running || Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("review round stopped");
        }
    }

    private int drainAndCloseMessageGate(
            RoundWork work, List<RunOutcome> investigations,
            List<ReviewObjectiveRow> objectives, String coverageContext, int cost)
    {
        int total = cost;
        while (true) {
            ensureRunning(work);
            total = drainRoundMessages(
                    work, investigations, objectives, coverageContext, total);
            if (store.closeMessageGateIfDrained(work.round().id())) {
                return total;
            }
        }
    }

    private int drainRoundMessages(
            RoundWork work, List<RunOutcome> investigations,
            List<ReviewObjectiveRow> objectives, String coverageContext, int cost)
    {
        int total = cost;
        while (true) {
            List<ReviewRoundMessageRow> pending =
                    store.pendingRoundMessages(work.round().id());
            if (pending.isEmpty()) {
                return total;
            }
            for (ReviewRoundMessageRow message : pending) {
                ensureRunning(work);
                if (!store.claimRoundMessage(message.id())) {
                    continue;
                }
                total = processRoundMessage(
                        work, message, investigations, objectives, coverageContext, total);
            }
        }
    }

    private int processRoundMessage(
            RoundWork work, ReviewRoundMessageRow message,
            List<RunOutcome> investigations, List<ReviewObjectiveRow> objectives,
            String coverageContext, int cost)
    {
        String assignmentId = null;
        int chargedCost = cost;
        try {
            int remaining = remainingBudget(work, cost);
            if (remaining == 0) {
                store.completeRoundMessage(
                        message.id(), "failed",
                        "No round budget remains for this guidance. Increase the cap and send it again.",
                        Instant.now());
                return cost;
            }
            PanelSeat seat = messageReviewer(work, message.target());
            assignmentId = UUID.randomUUID().toString();
            store.insertGuidanceAssignment(message.id(), new ReviewAssignmentRow(
                    assignmentId, work.round().id(), seat.reviewerDef().id(),
                    seat.provider().runner(), "queued", "", List.of(), List.of(),
                    new AssignmentBudget(6, 3, 12, 5)));
            int messageCap = reserveProviderTurn(
                    work, cost, Math.max(1, Math.min(25, remaining))).turnCap();
            if (messageCap == 0) {
                store.updateAssignment(
                        assignmentId, "aborted", "Guidance did not start because the budget was lowered.",
                        List.of(), List.of());
                store.completeRoundMessage(
                        message.id(), "failed",
                        "The round budget was lowered before this guidance could start.",
                        Instant.now());
                return cost;
            }
            String promptContext = investigationContext(work, coverageContext)
                    + "\n\nUser guidance checkpoint\n"
                    + "Target: " + message.target() + "\n"
                    + "Guidance: " + message.body() + "\n"
                    + "Address this guidance within the frozen round scope. Use read-only evidence tools "
                    + "and record any resulting artifacts before responding.";
            RunOutcome outcome;
            try {
                outcome = switch (message.target()) {
                    case "planner" -> runner.planGuidance(
                            seat.provider(), work.snapshot(), objectives,
                            message.body(), messageCap);
                    case "independent-verifier" -> runner.verifyGuidance(
                            seat.provider(), work.snapshot(), objectives,
                            message.body(), messageCap);
                    default -> runner.investigate(
                            seat.provider(), work.review().id(), assignmentId, work.snapshot(),
                            objectives, promptContext, seat.reviewerDef().persona(), messageCap);
                };
            }
            catch (RuntimeException e) {
                releaseProviderTurn(work);
                throw e;
            }
            int updatedCost = cost + outcome.costCents();
            chargedCost = updatedCost;
            completeProviderTurn(work, updatedCost);
            ensureRunning(work);
            investigations.add(outcome);
            failIfProviderErrored(outcome, "guidance");
            String completedAssignmentId = assignmentId;
            ReviewAssignmentRow recorded = store.assignments(work.review().id()).stream()
                    .filter(row -> row.id().equals(completedAssignmentId))
                    .findFirst().orElseThrow();
            boolean aborted = "ABORTED".equals(outcome.end());
            store.skipRunningSteps(assignmentId);
            store.updateAssignmentWhileRoundRunning(
                    assignmentId, aborted ? "aborted" : "completed",
                    recorded.understandingSummary().isBlank()
                            ? "Guidance processed." : recorded.understandingSummary(),
                    recorded.assumptionsJson(), recorded.unknownsJson());
            store.completeRoundMessage(
                    message.id(), aborted ? "failed" : "completed",
                    aborted
                            ? "The assigned reviewer reached the current budget boundary before completing this guidance."
                            : messageResponse(seat.reviewerDef(), outcome, recorded),
                    Instant.now());
            return updatedCost;
        }
        catch (RuntimeException e) {
            boolean stopped = Thread.interrupted()
                    || store.findRound(work.round().id())
                            .map(round -> !"RUNNING".equals(round.status())).orElse(true);
            if (assignmentId != null) {
                store.skipRunningSteps(assignmentId);
                store.updateAssignment(
                        assignmentId, stopped ? "cancelled" : "errored",
                        stopped ? "Guidance stopped with the round." : "Guidance could not be processed.",
                        List.of(), List.of());
            }
            if (stopped) {
                throw e;
            }
            store.completeRoundMessage(
                    message.id(), "failed", concise(
                            "Guidance could not be processed: "
                                    + (e.getMessage() == null ? "reviewer failed" : e.getMessage()),
                            600), Instant.now());
            log.warn("Review round {} could not process message {}: {}",
                    work.round().id(), message.id(), e.getMessage());
            return chargedCost;
        }
    }

    private PanelSeat messageReviewer(RoundWork work, String target)
    {
        AssignmentWork primary = work.assignments().get(0);
        if ("panel".equals(target)) {
            return new PanelSeat(primary.provider(), primary.reviewerDef());
        }
        if ("planner".equals(target)) {
            ReviewerDefRow planner = store.findReviewerDef("review-planner")
                    .filter(ReviewerDefRow::enabled)
                    .filter(row -> row.eligibleKinds().contains(work.plan().reviewClass()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "review planner is no longer available for this round"));
            return new PanelSeat(primary.provider(), planner);
        }
        Optional<AssignmentWork> assigned = work.assignments().stream()
                .filter(row -> row.reviewerDef().id().equals(target))
                .findFirst();
        if (assigned.isPresent()) {
            AssignmentWork reviewer = assigned.orElseThrow();
            return new PanelSeat(reviewer.provider(), reviewer.reviewerDef());
        }
        ReviewerDefRow definition = store.findReviewerDef(target)
                .filter(ReviewerDefRow::enabled)
                .filter(row -> row.eligibleKinds().contains(work.plan().reviewClass()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "target reviewer is no longer available for this round"));
        ProviderChoice choice = "independent-verifier".equals(definition.id())
                ? verifierProvider(definition, primary.provider())
                : provider(definition);
        return new PanelSeat(choice, definition);
    }

    private int currentCostCap(RoundWork work)
    {
        return store.findRound(work.round().id())
                .orElseThrow(() -> new IllegalStateException("review round disappeared"))
                .budgetJson().costCapCents();
    }

    /** Reserve the maximum billable amount of one provider turn before it is
     * launched. Budget edits share the same guard and therefore cannot lower
     * the cap underneath an in-flight CLI/API call. */
    private BudgetReservation reserveProviderTurn(
            RoundWork work, int spent, int requestedCap)
    {
        synchronized (budgetGuard) {
            int roundCap = currentCostCap(work);
            int reserved = Math.min(
                    requestedCap, Math.max(0, roundCap - spent));
            if (reserved > 0) {
                inFlightBudgetFloors.put(work.round().id(), spent + reserved);
            }
            return new BudgetReservation(roundCap, reserved);
        }
    }

    private void completeProviderTurn(RoundWork work, int spent)
    {
        synchronized (budgetGuard) {
            try {
                store.settleRoundCost(work.round().id(), spent);
            }
            finally {
                inFlightBudgetFloors.remove(work.round().id());
            }
        }
    }

    private void releaseProviderTurn(RoundWork work)
    {
        synchronized (budgetGuard) {
            inFlightBudgetFloors.remove(work.round().id());
        }
    }

    private int remainingBudget(RoundWork work, int spent)
    {
        return Math.max(0, currentCostCap(work) - spent);
    }

    static String guidanceContext(String coverageContext, String seed)
    {
        if (seed == null) {
            return coverageContext;
        }
        return coverageContext + "\n\nUser seed for this round\n"
                + seed + "\n"
                + "Use this seed to prioritize the investigation while preserving every evidence rule.";
    }

    private String investigationContext(RoundWork work, String coverageContext)
    {
        String context = guidanceContext(coverageContext, work.seed());
        return answerStep(work).map(step -> context + "\n\nLocal review thread continuation\n"
                + "Original finding:\n" + step.argumentsJson().path("originalFinding").asText()
                + "\n\nUser reply:\n" + step.argumentsJson().path("userReply").asText()
                + "\n\nRespond directly to the user after re-checking the evidence. "
                + "Your final response will be appended to this local review thread.")
                .orElse(context);
    }

    private static String messageResponse(
            ReviewerDefRow reviewer, RunOutcome outcome, ReviewAssignmentRow assignment)
    {
        String detail = blankToNull(outcome.finalText());
        if (detail == null) {
            detail = blankToNull(assignment.understandingSummary());
        }
        String prefix = reviewer.name() + " processed the guidance.";
        return detail == null ? prefix : concise(prefix + " " + detail, 600);
    }

    private static void failIfProviderErrored(RunOutcome outcome, String phase)
    {
        if ("ERRORED".equals(outcome.end())) {
            throw new IllegalStateException("CLI provider failed during " + phase);
        }
    }

    private static String concise(String value, int limit)
    {
        String stripped = value.strip().replaceAll("\\s+", " ");
        return stripped.substring(0, Math.min(limit, stripped.length()));
    }

    private CoverageReport persistMandatorySweeps(RoundWork work, String assignmentId)
    {
        CoverageReport coverage = DeterministicReviewCoverage.analyze(
                work.snapshot().diff(), contexts, work.snapshot());
        for (SweepResult sweep : coverage.sweeps()) {
            String stepId = UUID.randomUUID().toString();
            ObjectNode arguments = mapper.createObjectNode();
            arguments.put("applicable", sweep.applicable());
            arguments.put("covered", sweep.covered());
            arguments.put("inspected_units", sweep.inspectedUnits());
            arguments.put("candidate_count", sweep.candidates().size());
            store.insertStep(new InvestigationStepRow(
                    stepId, assignmentId, null, "sweep:" + sweep.name(), arguments,
                    sweep.note(), false, 0, !sweep.applicable() ? "not-applicable"
                            : sweep.covered() ? "completed" : "not-covered"));
            String preview = sweep.preview();
            store.insertObservation(new ObservationRow(
                    UUID.randomUUID().toString(), stepId, "mandatory-sweep",
                    work.snapshot().headCommit(), null, null, null, null,
                    null, null, null, digest(preview), preview));
        }
        return coverage;
    }

    private void recordSelfRefutationPass(
            String assignmentId, int findingCount, RunOutcome outcome)
    {
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("finding_count", findingCount);
        arguments.put("provider_rounds", outcome.providerRounds());
        store.insertStep(new InvestigationStepRow(
                UUID.randomUUID().toString(), assignmentId, null, "self-refutation",
                arguments, "Mandatory bounded counter-evidence pass over surviving findings.",
                false, outcome.costCents(), "ABORTED".equals(outcome.end()) ? "skipped" : "completed"));
    }

    /** Returns true when at least one applicable objective could not be covered. */
    private boolean resolveObjectives(RoundWork work, List<FindingRow> findings)
    {
        List<HypothesisRow> hypotheses = store.hypotheses(work.review().id());
        List<InvestigationStepRow> steps = store.steps(work.review().id());
        boolean gaps = false;
        for (ReviewObjectiveRow objective : work.objectives()) {
            if (!"applicable".equals(objective.applicabilityStatus())) {
                continue;
            }
            String resolution = objectiveResolution(objective, findings, hypotheses, steps);
            gaps |= "not-covered-budget".equals(resolution);
            store.updateObjectiveResolution(objective.id(), resolution);
        }
        return gaps;
    }

    static String objectiveResolution(
            ReviewObjectiveRow objective, List<FindingRow> findings,
            List<HypothesisRow> hypotheses, List<InvestigationStepRow> steps)
    {
        List<FindingRow> objectiveFindings = findings.stream()
                .filter(finding -> finding.objectiveId().equals(objective.id()))
                .filter(finding -> !"dropped".equals(finding.lifecycleStatus()))
                .toList();
        if (objectiveFindings.stream().anyMatch(
                finding -> "unknown".equals(finding.verificationStatus()))) {
            return "unknown";
        }
        if (!objectiveFindings.isEmpty()) {
            return "finding";
        }
        Set<String> hypothesisIds = hypotheses.stream()
                .filter(hypothesis -> objective.id().equals(hypothesis.objectiveId()))
                .map(HypothesisRow::id)
                .collect(Collectors.toSet());
        boolean inspected = hypotheses.stream()
                .filter(hypothesis -> objective.id().equals(hypothesis.objectiveId()))
                .anyMatch(hypothesis -> "refuted".equals(hypothesis.status()))
                || steps.stream()
                .filter(step -> step.hypothesisId() != null)
                .anyMatch(step -> hypothesisIds.contains(step.hypothesisId())
                        && "completed".equals(step.status()));
        return inspected ? "investigated-clean" : "not-covered-budget";
    }

    private VerificationOutcome verify(
            RoundWork work, List<FindingRow> candidates, int priorCost)
    {
        Map<String, List<FindingEvidenceRow>> evidenceByFinding = evidenceByFinding(work.review().id());
        Map<String, ObservationRow> observationsById = observationsById(work.review().id());
        ReviewerDefRow verifierDefinition = store.findReviewerDef("independent-verifier")
                .filter(ReviewerDefRow::enabled)
                .filter(row -> row.eligibleKinds().contains(work.plan().reviewClass()))
                .orElse(null);
        if (verifierDefinition == null) {
            for (FindingRow finding : candidates) {
                insertUnknownVerification(work.run().id(), finding,
                        "No enabled independent verifier definition is eligible for this review class.");
            }
            return VerificationOutcome.EMPTY;
        }
        ProviderChoice verifier;
        try {
            verifier = verifierProvider(
                    verifierDefinition, work.assignments().get(0).provider());
        }
        catch (IllegalStateException noVerifier) {
            for (FindingRow finding : candidates) {
                insertUnknownVerification(work.run().id(), finding, noVerifier.getMessage());
            }
            return VerificationOutcome.EMPTY;
        }
        AgentRun verifierRun = openReviewRun(work.review(), work.pr(), work.round().id(),
                Math.max(1, remainingBudget(work, priorCost) / 3));
        String verifierAssignment = UUID.randomUUID().toString();
        boolean assignmentInserted = false;
        boolean completed = false;
        try {
            ensureRunning(work);
            store.insertAssignment(new ReviewAssignmentRow(
                    verifierAssignment, work.round().id(), verifierDefinition.id(), verifier.runner(),
                    "verifying", "Independent evidence audit", List.of(), List.of(),
                    new AssignmentBudget(0, 0, 6, 5)));
            assignmentInserted = true;
            int cost = 0;
            long tokensIn = 0;
            long tokensOut = 0;
            int providerRounds = 0;
            List<FindingRow> awaitingStructuredVerification = new ArrayList<>();
            for (FindingRow finding : candidates) {
                ensureRunning(work);
                int remaining = remainingBudget(work, priorCost + cost);
                if (remaining == 0) {
                    insertUnknownVerification(verifierRun.id(), finding,
                            "Round cost cap was reached before independent verification.");
                    continue;
                }
                List<FindingEvidenceRow> findingEvidence =
                        evidenceByFinding.getOrDefault(finding.id(), List.of());
                String bundle = findingBundle(finding, findingEvidence, observationsById);
                if (!deterministicallyValid(work, finding, findingEvidence, observationsById)) {
                    insertRejectedVerification(verifierRun.id(), finding,
                            "Deterministic validation failed: missing/current evidence or action.");
                    continue;
                }
                String blind = null;
                if (finding.severity() >= MIN_PUBLISHABLE_SEVERITY) {
                    int reconstructionCap = reserveProviderTurn(
                            work, priorCost + cost,
                            Math.max(1, Math.min(
                                    10, remainingBudget(work, priorCost + cost)))).turnCap();
                    if (reconstructionCap == 0) {
                        insertUnknownVerification(verifierRun.id(), finding,
                                "Round cost cap was lowered before blind reconstruction.");
                        continue;
                    }
                    RunOutcome reconstruction;
                    try {
                        reconstruction = runner.reconstruct(
                                verifier, work.review().id(), verifierAssignment, work.snapshot(),
                                evidenceLocations(findingEvidence, observationsById),
                                verifierDefinition.persona(), reconstructionCap);
                    }
                    catch (RuntimeException e) {
                        releaseProviderTurn(work);
                        throw e;
                    }
                    cost += reconstruction.costCents();
                    completeProviderTurn(work, priorCost + cost);
                    ensureRunning(work);
                    failIfProviderErrored(reconstruction, "blind reconstruction");
                    blind = reconstruction.finalText();
                    tokensIn += reconstruction.tokensIn();
                    tokensOut += reconstruction.tokensOut();
                    providerRounds += reconstruction.providerRounds();
                    if ("ABORTED".equals(reconstruction.end())) {
                        insertUnknownVerification(verifierRun.id(), finding,
                                "Blind reconstruction reached the current round budget boundary.");
                        continue;
                    }
                }
                int verifyRemaining = remainingBudget(work, priorCost + cost);
                if (verifyRemaining == 0) {
                    insertUnknownVerification(verifierRun.id(), finding,
                            "Round cost cap was reached before the evidence verdict.");
                    continue;
                }
                int verificationCap = reserveProviderTurn(
                        work, priorCost + cost,
                        Math.max(1, Math.min(
                                Math.max(1, currentCostCap(work) / 3),
                                verifyRemaining))).turnCap();
                if (verificationCap == 0) {
                    insertUnknownVerification(verifierRun.id(), finding,
                            "Round cost cap was lowered before the evidence verdict.");
                    continue;
                }
                RunOutcome result;
                try {
                    result = runner.verify(
                            verifier, work.review().id(), verifierAssignment, work.snapshot(),
                            verifierRun.id(), bundle, blind,
                            verifierDefinition.persona(), verificationCap);
                }
                catch (RuntimeException e) {
                    releaseProviderTurn(work);
                    throw e;
                }
                cost += result.costCents();
                completeProviderTurn(work, priorCost + cost);
                ensureRunning(work);
                failIfProviderErrored(result, "verification");
                tokensIn += result.tokensIn();
                tokensOut += result.tokensOut();
                providerRounds += result.providerRounds();
                awaitingStructuredVerification.add(finding);
            }
            Set<String> verifiedFindingIds = store.verifications(work.review().id()).stream()
                    .map(FindingVerificationRow::findingId).collect(Collectors.toSet());
            for (FindingRow finding : awaitingStructuredVerification) {
                if (!verifiedFindingIds.contains(finding.id())) {
                    insertUnknownVerification(verifierRun.id(), finding,
                            "Verifier returned without a structured result.");
                }
            }
            store.updateAssignmentWhileRoundRunning(
                    verifierAssignment, "completed", "Verification complete.",
                    List.of(), List.of());
            runs.updateHeadline(verifierRun.id(), candidates.size() + " findings verified");
            ObjectNode verifierMetrics = mapper.createObjectNode();
            verifierMetrics.put("provider", verifier.providerId());
            verifierMetrics.put("runner", verifier.runner());
            verifierMetrics.put("tokensIn", tokensIn);
            verifierMetrics.put("tokensOut", tokensOut);
            verifierMetrics.put("providerRounds", providerRounds);
            verifierMetrics.put("costCents", cost);
            verifierMetrics.put("findings", candidates.size());
            runs.updateMetrics(verifierRun.id(), verifierMetrics.toString());
            ensureRunning(work);
            runs.transition(verifierRun.id(), AgentRun.STATUS_SUCCEEDED, "verification complete");
            completed = true;
            return new VerificationOutcome(cost, tokensIn, tokensOut, providerRounds);
        }
        finally {
            if (!completed) {
                boolean cancelled = Thread.interrupted()
                        || store.findRound(work.round().id())
                                .map(round -> "CANCELLED".equals(round.status())).orElse(false);
                if (assignmentInserted) {
                    store.updateAssignment(verifierAssignment,
                            cancelled ? "cancelled" : "errored",
                            cancelled ? "Verification stopped with the round." : "Verification failed.",
                            List.of(), List.of());
                }
                runs.findById(verifierRun.id()).filter(AgentRun::isLive)
                        .ifPresent(run -> runs.transition(run.id(),
                                cancelled ? AgentRun.STATUS_CANCELLED : AgentRun.STATUS_FAILED,
                                cancelled ? "review round stopped" : "verification failed"));
            }
        }
    }

    private List<FindingRow> consolidate(List<FindingRow> candidates)
    {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<FindingEvidenceRow> evidence = store.evidence(candidates.get(0).reviewId());
        Set<String> existingRelations = store.relations(candidates.get(0).reviewId()).stream()
                .map(relation -> relation.sourceFindingId() + "\u0000"
                        + relation.targetFindingId() + "\u0000" + relation.relation())
                .collect(Collectors.toSet());
        Map<String, ObservationRow> observationsById = observationsById(candidates.get(0).reviewId());
        Map<String, List<ObservationRow>> observationsByFinding = candidates.stream()
                .collect(Collectors.toMap(
                        FindingRow::id,
                        finding -> evidence.stream()
                                .filter(edge -> edge.findingId().equals(finding.id()))
                                .map(FindingEvidenceRow::observationId)
                                .map(observationsById::get)
                                .filter(Objects::nonNull)
                                .toList()));
        List<FindingRow> kept = new ArrayList<>();
        for (FindingRow candidate : candidates) {
            FindingRow duplicate = kept.stream()
                    .filter(existing -> sameRootFinding(
                            existing, candidate, observationsByFinding))
                    .findFirst().orElse(null);
            if (duplicate == null) {
                kept.stream()
                        .filter(existing -> relatedFinding(
                                existing, candidate, observationsByFinding))
                        .findFirst()
                        .ifPresent(existing -> insertRelationIfAbsent(
                                existingRelations, new FindingRelationRow(
                                        candidate.id(), existing.id(), "RELATED_ROOT_CAUSE")));
                kept.add(candidate);
                continue;
            }
            insertRelationIfAbsent(existingRelations, new FindingRelationRow(
                    candidate.id(), duplicate.id(), "DUPLICATES"));
            store.updateFinding(candidate.id(), "dropped", candidate.verificationStatus(),
                    candidate.confidenceClass(), candidate.claim(), candidate.severity());
        }
        return List.copyOf(kept);
    }

    private void insertRelationIfAbsent(
            Set<String> existing, FindingRelationRow relation)
    {
        String key = relation.sourceFindingId() + "\u0000"
                + relation.targetFindingId() + "\u0000" + relation.relation();
        if (existing.add(key)) {
            store.insertRelation(relation);
        }
    }

    private static boolean sameRootFinding(
            FindingRow left, FindingRow right,
            Map<String, List<ObservationRow>> observationsByFinding)
    {
        if (!normalised(left.requestedAction()).equals(normalised(right.requestedAction()))) {
            return false;
        }
        if (normalised(left.claim()).equals(normalised(right.claim()))) {
            return true;
        }
        if (shareAnchor(observationsByFinding.get(left.id()), observationsByFinding.get(right.id()))) {
            return true;
        }
        return left.objectiveId().equals(right.objectiveId())
                && tokenOverlap(left.claim(), right.claim()) >= 0.6;
    }

    private static boolean relatedFinding(
            FindingRow left, FindingRow right,
            Map<String, List<ObservationRow>> observationsByFinding)
    {
        return left.objectiveId().equals(right.objectiveId())
                || shareAnchor(observationsByFinding.get(left.id()), observationsByFinding.get(right.id()));
    }

    private static boolean shareAnchor(List<ObservationRow> left, List<ObservationRow> right)
    {
        if (left == null || right == null) {
            return false;
        }
        for (ObservationRow a : left) {
            for (ObservationRow b : right) {
                if (a.symbol() != null && !a.symbol().isBlank() && a.symbol().equals(b.symbol())) {
                    return true;
                }
                if (a.path() == null || !a.path().equals(b.path())
                        || a.startLine() == null || b.startLine() == null) {
                    continue;
                }
                int aEnd = a.endLine() == null ? a.startLine() : a.endLine();
                int bEnd = b.endLine() == null ? b.startLine() : b.endLine();
                if (a.startLine() <= bEnd + 5 && b.startLine() <= aEnd + 5) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double tokenOverlap(String left, String right)
    {
        Set<String> a = Stream.of(normalised(left).split("[^a-z0-9_]+"))
                .filter(token -> token.length() > 2).collect(Collectors.toSet());
        Set<String> b = Stream.of(normalised(right).split("[^a-z0-9_]+"))
                .filter(token -> token.length() > 2).collect(Collectors.toSet());
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        long common = a.stream().filter(b::contains).count();
        return (double) common / Math.min(a.size(), b.size());
    }

    private VerificationOutcome verifyTrivial(RoundWork work, List<FindingRow> candidates)
    {
        Map<String, List<FindingEvidenceRow>> evidenceByFinding = evidenceByFinding(work.review().id());
        Map<String, ObservationRow> observationsById = observationsById(work.review().id());
        Set<String> verified = store.verifications(work.review().id()).stream()
                .map(FindingVerificationRow::findingId)
                .collect(Collectors.toSet());
        for (FindingRow finding : candidates) {
            if (verified.contains(finding.id())) {
                continue;
            }
            if (!deterministicallyValid(
                    work, finding, evidenceByFinding.getOrDefault(finding.id(), List.of()), observationsById)) {
                insertRejectedVerification(work.run().id(), finding,
                        "Deterministic validation failed: missing/current evidence or action.");
                continue;
            }
            store.insertVerification(new FindingVerificationRow(
                    UUID.randomUUID().toString(), finding.id(), work.run().id(),
                    true, true, true, List.of(), "partially", "SUPPORTED",
                    "Trivial review class: deterministic evidence checks passed; independent verifier omitted by policy."));
            store.updateFinding(finding.id(), "ready", "partially", "SUPPORTED",
                    finding.claim(), finding.severity());
        }
        return VerificationOutcome.EMPTY;
    }

    private ObjectNode metrics(
            RoundWork work, List<RunOutcome> investigations, VerificationOutcome verification,
            List<FindingRow> findings, int totalCost)
    {
        RunOutcome primary = investigations.get(0);
        ObjectNode metrics = mapper.createObjectNode();
        metrics.put("reviewClass", work.plan().reviewClass());
        metrics.put("provider", primary.provider().providerId());
        metrics.put("runner", primary.provider().runner());
        metrics.put("assignments", work.assignments().size());
        metrics.put("wallClockMs", Duration.between(work.run().startedAt(), Instant.now()).toMillis());
        metrics.put("tokensIn", investigations.stream().mapToLong(RunOutcome::tokensIn).sum()
                + verification.tokensIn());
        metrics.put("tokensOut", investigations.stream().mapToLong(RunOutcome::tokensOut).sum()
                + verification.tokensOut());
        metrics.put("providerRounds", investigations.stream().mapToInt(RunOutcome::providerRounds).sum()
                + verification.providerRounds());
        Set<String> roundAssignmentIds = new HashSet<>();
        store.assignments(work.review().id()).stream()
                .filter(assignment -> assignment.roundId().equals(work.round().id()))
                .map(ReviewAssignmentRow::id)
                .forEach(roundAssignmentIds::add);
        metrics.put("toolCalls", store.steps(work.review().id()).stream()
                .filter(step -> roundAssignmentIds.contains(step.assignmentId()))
                .count());
        metrics.put("costCents", totalCost);
        metrics.put("findings", findings.size());
        metrics.put("verified", findings.stream().filter(row -> "verified".equals(row.verificationStatus())).count());
        metrics.put("partially", findings.stream().filter(row -> "partially".equals(row.verificationStatus())).count());
        metrics.put("unknown", findings.stream().filter(row -> "unknown".equals(row.verificationStatus())).count());
        metrics.put("rejected", findings.stream().filter(row -> "rejected".equals(row.verificationStatus())).count());
        return metrics;
    }

    private boolean deterministicallyValid(
            RoundWork work, FindingRow finding, List<FindingEvidenceRow> evidence,
            Map<String, ObservationRow> observationsById)
    {
        if (finding.requestedAction() == null || finding.requestedAction().isBlank()
                || finding.severity() < 1 || finding.severity() > 5
                || !CRITERION_KINDS.contains(finding.criterionKind())
                || !work.snapshot().headCommit().equals(finding.lastCheckedCommit())
                || !rightSideRange(
                        work.snapshot(), finding.path(), finding.startLine(), finding.endLine())) {
            return false;
        }
        if (!hasRequiredEvidence(evidence)
                || !hasAnchoredSupportingEvidence(finding, evidence, observationsById)) {
            return false;
        }
        List<ObservationRow> observations = evidence.stream()
                .map(FindingEvidenceRow::observationId)
                .map(observationsById::get)
                .filter(Objects::nonNull)
                .toList();
        return observations.size() == evidence.size()
                && observations.stream().allMatch(observation -> observationIsCurrent(work.snapshot(), observation));
    }

    static boolean hasRequiredEvidence(List<FindingEvidenceRow> evidence)
    {
        return evidence != null && evidence.stream()
                .anyMatch(row -> "SUPPORTS".equals(row.relation()));
    }

    static boolean hasAnchoredSupportingEvidence(
            FindingRow finding, List<FindingEvidenceRow> evidence,
            Map<String, ObservationRow> observationsById)
    {
        if (finding.path() == null || finding.startLine() == null || finding.endLine() == null
                || evidence == null || observationsById == null) {
            return false;
        }
        return evidence.stream()
                .filter(row -> "SUPPORTS".equals(row.relation()))
                .map(FindingEvidenceRow::observationId)
                .map(observationsById::get)
                .filter(Objects::nonNull)
                .anyMatch(observation -> finding.path().equals(observation.path())
                        && observation.startLine() != null && observation.endLine() != null
                        && observation.startLine() <= finding.startLine()
                        && observation.endLine() >= finding.endLine());
    }

    private void materialiseComments(RoundWork work, List<FindingRow> findings)
    {
        Set<String> materialisedFindingIds = new HashSet<>();
        prs.comments(work.pr().id()).stream()
                .map(PRComment::findingId)
                .filter(id -> id != null)
                .forEach(materialisedFindingIds::add);
        for (FindingRow finding : findings) {
            if ("dropped".equals(finding.lifecycleStatus())) {
                if (!"rejected".equals(finding.verificationStatus())) {
                    continue;
                }
                reviewEvent(work.pr().id(), "rejected-dropped",
                        node -> node.put("findingId", finding.id()));
                continue;
            }
            if (!isPublishableComment(finding)) {
                continue;
            }
            if (!materialisedFindingIds.add(finding.id())) {
                continue;
            }
            if (!rightSideRange(
                    work.snapshot(), finding.path(), finding.startLine(), finding.endLine())) {
                continue;
            }
            String body = renderComment(finding);
            boolean range = !finding.startLine().equals(finding.endLine());
            PRComment comment = prs.addComment(
                    work.pr().id(), PRComment.ORIGIN_LOCAL,
                    PRComment.SCOPE_FILE_LINE, finding.path(), finding.endLine(),
                    "RIGHT", range ? finding.startLine() : null,
                    range ? "RIGHT" : null, "agent", body, null);
            prs.attachFinding(comment.id(), finding.id());
        }
    }

    static boolean isPublishableComment(FindingRow finding)
    {
        return finding.severity() >= MIN_PUBLISHABLE_SEVERITY
                && "verified".equals(finding.verificationStatus())
                && !"TENTATIVE".equals(finding.confidenceClass());
    }

    private Optional<InvestigationStepRow> answerStep(RoundWork work)
    {
        Set<String> assignmentIds = work.assignments().stream()
                .map(AssignmentWork::id).collect(Collectors.toSet());
        return store.steps(work.review().id()).stream()
                .filter(step -> assignmentIds.contains(step.assignmentId()))
                .filter(step -> "user-answer".equals(step.actionType()))
                .findFirst();
    }

    private void appendAnswerReply(RoundWork work, List<RunOutcome> investigations)
    {
        String response = investigations.isEmpty()
                ? null : blankToNull(investigations.get(0).finalText());
        appendAnswerReply(work, response);
    }

    private void appendAnswerReply(RoundWork work, String response)
    {
        InvestigationStepRow answer = answerStep(work).orElse(null);
        response = blankToNull(response);
        if (answer == null || response == null) {
            return;
        }
        String findingId = answer.argumentsJson().path("findingId").asText();
        PRComment root = prs.comments(work.pr().id()).stream()
                .filter(comment -> findingId.equals(comment.findingId()))
                .findFirst().orElse(null);
        if (root == null) {
            return;
        }
        prs.addComment(
                work.pr().id(), PRComment.ORIGIN_LOCAL, root.scope(), root.filePath(),
                root.lineNumber(), root.side(), root.startLine(), root.startSide(),
                "agent", response, root.id());
    }

    private InvestigationReviewData detail(AgentReviewRow review)
    {
        List<ReviewRoundRow> roundRows = store.rounds(review.id());
        Set<String> runIds = new LinkedHashSet<>();
        roundRows.forEach(round -> {
            runs.findByReviewRound(round.id()).forEach(run -> runIds.add(run.id()));
            runIds.add(round.agentRunId());
        });
        store.verifications(review.id()).forEach(row -> runIds.add(row.verifierRunId()));
        List<AgentRun> runRows = runIds.stream().map(runs::findById).flatMap(Optional::stream).toList();
        List<PRComment> prComments = prs.comments(review.prId());
        Set<String> findingCommentIds = prComments.stream()
                .filter(comment -> comment.findingId() != null)
                .map(PRComment::id).collect(Collectors.toSet());
        List<PRCommentDto> comments = prComments.stream()
                .filter(comment -> comment.findingId() != null
                        || findingCommentIds.contains(comment.parentCommentId()))
                .map(PRCommentDto::from).toList();
        List<PRTimelineEntryDto> timeline = prs.timeline(review.prId()).stream()
                .map(event -> PRTimelineEntryDto.from(event, mapper))
                .filter(event -> event.payload().hasNonNull("reviewEvent"))
                .toList();
        Map<String, List<String>> messageTargets = roundRows.stream()
                .collect(Collectors.toMap(
                        ReviewRoundRow::id,
                        round -> List.copyOf(allowedMessageTargets(review.id(), round))));
        return new InvestigationReviewData(
                review, roundRows, store.roundMessages(review.id()),
                store.reviewedCommits(review.id()), messageTargets, runRows,
                store.criteria(review.id()), store.objectives(review.id()),
                store.assignments(review.id()), store.hypotheses(review.id()), store.steps(review.id()),
                store.observations(review.id()), store.findings(review.id()), store.evidence(review.id()),
                store.verifications(review.id()), store.relations(review.id()), store.outcomes(review.id()),
                store.knowledge(review.repoId()), store.knowledgeProvenance(review.repoId()),
                activityFacts(review.id()),
                comments, timeline);
    }

    private List<ActivityFactRow> activityFacts(String reviewId)
    {
        List<InvestigationStepRow> steps = store.steps(reviewId);
        List<ReviewObjectiveRow> objectives = store.objectives(reviewId);
        List<ObservationRow> observations = store.observations(reviewId);
        Map<String, CriterionRow> criteria = store.criteria(reviewId).stream()
                .collect(Collectors.toMap(CriterionRow::id, row -> row));
        long hunks = sweepInspectedUnits(steps, "line-scan");
        long publicTraces = sweepInspectedUnits(steps, "cross-file-trace");
        long deletions = sweepInspectedUnits(steps, "removed-behavior");
        long resolved = objectives.stream()
                .filter(objective -> !"pending".equals(objective.resolutionStatus()))
                .count();
        long applicableClasses = objectives.stream()
                .filter(objective -> "applicable".equals(objective.applicabilityStatus()))
                .filter(objective -> {
                    CriterionRow criterion = criteria.get(objective.criterionId());
                    return criterion != null && "failure-class".equals(criterion.sourceType());
                })
                .filter(objective -> !Set.of("pending", "not-covered-budget")
                        .contains(objective.resolutionStatus()))
                .count();
        long tests = observations.stream()
                .filter(observation -> observation.path() != null)
                .filter(observation -> observation.path().toLowerCase(Locale.ROOT).contains("test"))
                .count();
        long budgetGaps = objectives.stream()
                .filter(objective -> "not-covered-budget".equals(objective.resolutionStatus()))
                .count();
        return List.of(
                new ActivityFactRow("hunks-inspected", hunks, "Hunks covered by the deterministic line scan"),
                new ActivityFactRow("public-symbols-traced", publicTraces, "Modified symbols queued by the bounded trace sweep"),
                new ActivityFactRow("deletions-evaluated", deletions, "Deleted lines covered by the removed-behavior sweep"),
                new ActivityFactRow("applicable-classes-resolved", applicableClasses,
                        "Applicable failure classes with finding, investigated-clean, or unknown disposition"),
                new ActivityFactRow("objectives-resolved", resolved, resolved + " of " + objectives.size()),
                new ActivityFactRow("tests-inspected", tests, "Test-source observations"),
                new ActivityFactRow("budget-gaps", budgetGaps, "Applicable objectives explicitly not covered within budget"));
    }

    private static long sweepInspectedUnits(List<InvestigationStepRow> steps, String sweep)
    {
        return steps.stream()
                .filter(step -> ("sweep:" + sweep).equals(step.actionType()))
                .filter(step -> "completed".equals(step.status()))
                .mapToLong(step -> step.argumentsJson().path("inspected_units").asLong(0))
                .sum();
    }

    private PlanDraft plan(
            InvestigationReviewContext.Snapshot snapshot,
            List<ReviewKnowledge> learnedKnowledge)
    {
        String diff = snapshot.diff();
        CoverageReport coverage = DeterministicReviewCoverage.analyze(diff, contexts, snapshot);
        long changedLines = diff.lines().filter(line -> line.startsWith("+") || line.startsWith("-")).count();
        long files = diff.lines().filter(line -> line.startsWith("diff --git ")).count();
        String lower = diff.toLowerCase(Locale.ROOT);
        boolean publicSurface = Stream.of("public ", "interface ", "schema", "config", "protocol", "spi")
                .anyMatch(lower::contains);
        boolean highRisk = Stream.of("synchronized", "concurrent", "security", "credential", "transaction")
                .anyMatch(lower::contains);
        String reviewClass = changedLines <= 30 && files <= 1 && !publicSurface
                ? "trivial" : changedLines > 500 || highRisk ? "high-risk" : "standard";
        RoundBudget budget = switch (reviewClass) {
            case "trivial" -> new RoundBudget(50, 5);
            case "high-risk" -> new RoundBudget(150, 20);
            default -> new RoundBudget(50, 10);
        };
        List<PlanObjective> objectives = new ArrayList<>();
        objectives.add(new PlanObjective(
                "hard-invariant", "Preserve existing behavior on changed return and error paths.",
                "shipped-rule", "correctness", true));
        if (publicSurface) {
            objectives.add(new PlanObjective(
                    "hard-invariant", "Confirm public API/SPI and serialized compatibility is intentional.",
                    "shipped-rule", "compatibility", true));
        }
        else {
            objectives.add(new PlanObjective(
                    "engineering-principle", "Confirm tests specify the changed behavior and failure cases.",
                    "shipped-rule", "tests", true));
        }
        if (Stream.of("close(", "stream", "transaction", "resource").anyMatch(lower::contains)) {
            objectives.add(new PlanObjective(
                    "hard-invariant", "Preserve resource and transaction lifecycle on every exit path.",
                    "shipped-rule", "lifecycle", true));
        }
        if (Stream.of("synchronized", "concurrent", "atomic", "executor", "queue")
                .anyMatch(lower::contains)) {
            objectives.add(new PlanObjective(
                    "hard-invariant", "Trace changed shared state and ordering through direct concurrency boundaries.",
                    "shipped-rule", "concurrency", true));
        }
        if (diff.lines().anyMatch(line -> line.startsWith("-") && !line.startsWith("---"))) {
            objectives.add(new PlanObjective(
                    "engineering-principle", "Confirm deleted behavior has no required callers, tests, or compatibility role.",
                    "shipped-rule", "deletion", true));
        }
        Set<String> existingStatements = objectives.stream()
                .map(PlanObjective::statement)
                .map(InvestigationReviewService::normalised)
                .collect(Collectors.toSet());
        objectives.addAll(learnedObjectives(snapshot, learnedKnowledge, existingStatements));
        for (FailureClassResult failureClass : coverage.failureClasses()) {
            objectives.add(new PlanObjective(
                    "hard-invariant", failureClassStatement(failureClass.id()),
                    "failure-class", failureClass.id(), failureClass.applicable()));
        }
        return new PlanDraft(reviewClass, budget, List.copyOf(objectives), null);
    }

    private static List<PlanObjective> learnedObjectives(
            InvestigationReviewContext.Snapshot snapshot,
            List<ReviewKnowledge> learnedKnowledge,
            Set<String> existingStatements)
    {
        List<PlanObjective> objectives = new ArrayList<>();
        for (ReviewKnowledge knowledge : learnedKnowledge) {
            if (objectives.size() >= MAX_LEARNED_OBJECTIVES) {
                break;
            }
            String kind = learnedCriterionKind(knowledge.kind());
            if (kind == null || knowledge.statement() == null || knowledge.statement().isBlank()) {
                continue;
            }
            if (!existingStatements.add(normalised(knowledge.statement()))) {
                continue;
            }
            objectives.add(new PlanObjective(
                    kind,
                    scopedLearnedStatement(snapshot, knowledge),
                    "project-intelligence",
                    knowledge.id(),
                    true));
        }
        return List.copyOf(objectives);
    }

    private static String learnedCriterionKind(String kind)
    {
        if (HARD_LEARNED_KINDS.contains(kind)) {
            return "hard-invariant";
        }
        if (PRINCIPLE_LEARNED_KINDS.contains(kind)) {
            return "engineering-principle";
        }
        if (CONVENTION_LEARNED_KINDS.contains(kind)) {
            return "repo-convention";
        }
        return null;
    }

    private static String scopedLearnedStatement(
            InvestigationReviewContext.Snapshot snapshot,
            ReviewKnowledge knowledge)
    {
        List<String> changedPaths = InvestigationReviewRunner.changedFilenames(snapshot);
        List<String> areas = knowledge.applicability().stream()
                .filter(tag -> Set.of("module", "path").contains(tag.kind()))
                .map(Applicability::value)
                .filter(area -> changedPaths.stream().anyMatch(path -> pathsIntersect(area, path)))
                .distinct()
                .limit(2)
                .toList();
        String statement = knowledge.statement().strip();
        return areas.isEmpty() ? statement : "[" + String.join(", ", areas) + "] " + statement;
    }

    private static boolean pathsIntersect(String left, String right)
    {
        String first = normalisedPath(left);
        String second = normalisedPath(right);
        return !first.isBlank() && !second.isBlank()
                && (first.equals(second)
                || first.startsWith(second + "/")
                || second.startsWith(first + "/"));
    }

    private static String normalisedPath(String value)
    {
        if (value == null) {
            return "";
        }
        String path = value.strip().replace('\\', '/').toLowerCase(Locale.ROOT);
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        while (path.endsWith("/") && !path.isEmpty()) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private PlanDraft cachedPlan(InvestigationReviewContext.Snapshot snapshot)
    {
        List<ReviewKnowledge> learnedKnowledge = learnedReviewKnowledge(snapshot);
        String key = preflightKey(snapshot, learnedKnowledge);
        Instant now = Instant.now();
        Set<String> expired = preflightPlans.entrySet().stream()
                .filter(entry -> !entry.getValue().expiresAt().isAfter(now))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        expired.forEach(expiredKey -> {
            preflightPlans.remove(expiredKey);
        });
        String prPrefix = snapshot.pr().id() + "@";
        preflightPlans.keySet().removeIf(existing -> existing.startsWith(prPrefix) && !existing.equals(key));
        CachedPlan cached = preflightPlans.get(key);
        if (cached != null) {
            return cached.plan();
        }
        PlanDraft draft = plan(snapshot, learnedKnowledge);
        preflightPlans.put(key, new CachedPlan(draft, now.plus(PREFLIGHT_TTL)));
        return draft;
    }

    private List<ReviewKnowledge> learnedReviewKnowledge(
            InvestigationReviewContext.Snapshot snapshot)
    {
        try {
            List<ReviewKnowledge> learned = runner.reviewKnowledge(snapshot);
            return learned == null ? List.of() : List.copyOf(learned);
        }
        catch (RuntimeException e) {
            // Project Intelligence is an advisory head start; a retrieval
            // problem must never prevent the evidence-first review from running.
            log.warn("Could not load Project Intelligence for review plan {}: {}",
                    snapshot.pr().id(), e.getMessage());
            return List.of();
        }
    }

    private static String preflightKey(
            InvestigationReviewContext.Snapshot snapshot,
            List<ReviewKnowledge> learnedKnowledge)
    {
        String knowledgeVersion = learnedKnowledge.stream()
                .map(item -> item.id() + ":" + item.updatedAtMs() + ":" + item.applicability())
                .collect(Collectors.joining("\n"));
        String inputs = Objects.toString(snapshot.pr().title(), "") + "\n"
                + Objects.toString(snapshot.pr().description(), "") + "\n"
                + knowledgeVersion;
        return snapshot.pr().id() + "@" + snapshot.headCommit() + "@" + digest(inputs);
    }

    private static String failureClassStatement(String failureClass)
    {
        return switch (failureClass) {
            case "logic-boundary" -> "Check changed logic, boundaries, nullability, and return behavior.";
            case "removed-behavior" -> "Trace every removed behavior to its replacement or required invariant.";
            case "interface-contract" -> "Preserve intentional interface and externally visible contracts.";
            case "state-lifecycle" -> "Preserve valid state transitions and lifecycle ordering.";
            case "concurrency" -> "Preserve synchronization, ordering, and shared-state safety.";
            case "resource-handling" -> "Preserve resource acquisition, cleanup, and transaction boundaries.";
            case "error-handling" -> "Preserve error propagation, recovery, and failure semantics.";
            case "security" -> "Preserve authentication, authorization, secret, and input-safety boundaries.";
            case "compatibility" -> "Preserve compatible API, schema, config, serialization, and protocol behavior.";
            case "data-integrity" -> "Preserve persistence, mutation, and serialized-data integrity.";
            default -> throw new IllegalArgumentException("unknown failure class " + failureClass);
        };
    }

    private String findingBundle(
            FindingRow finding, List<FindingEvidenceRow> evidence,
            Map<String, ObservationRow> observationsById)
    {
        StringBuilder out = new StringBuilder()
                .append("finding_id: ").append(finding.id()).append('\n')
                .append("location: ").append(finding.path()).append(':')
                .append(finding.startLine()).append('-').append(finding.endLine()).append('\n')
                .append("claim: ").append(finding.claim()).append('\n')
                .append("severity: ").append(finding.severity()).append('\n')
                .append("requested_action: ").append(finding.requestedAction()).append('\n')
                .append("evidence:\n");
        for (FindingEvidenceRow edge : evidence) {
            out.append("- ").append(edge.relation()).append(' ').append(edge.strengthClass())
                    .append(": ").append(edge.proposition()).append('\n');
            ObservationRow observation = observationsById.get(edge.observationId());
            if (observation != null) {
                out.append("  ").append(observation.path()).append(':')
                        .append(observation.startLine()).append('@')
                        .append(observation.commitSha()).append(" — ")
                        .append(observation.preview(), 0, Math.min(2_000, observation.preview().length()))
                        .append('\n');
            }
        }
        return out.toString();
    }

    private String evidenceLocations(
            List<FindingEvidenceRow> evidence, Map<String, ObservationRow> observationsById)
    {
        StringBuilder out = new StringBuilder();
        evidence.stream().map(FindingEvidenceRow::observationId).map(observationsById::get)
                .filter(Objects::nonNull).forEach(observation -> out
                        .append(observation.path()).append(':').append(observation.startLine())
                        .append('@').append(observation.commitSha()).append('\n')
                        .append(observation.preview(), 0, Math.min(2_000, observation.preview().length()))
                        .append('\n'));
        return out.toString();
    }

    private Map<String, List<FindingEvidenceRow>> evidenceByFinding(String reviewId)
    {
        return store.evidence(reviewId).stream()
                .collect(Collectors.groupingBy(FindingEvidenceRow::findingId));
    }

    private Map<String, ObservationRow> observationsById(String reviewId)
    {
        return store.observations(reviewId).stream()
                .collect(Collectors.toMap(ObservationRow::id, observation -> observation));
    }

    private void insertUnknownVerification(String verifierRunId, FindingRow finding, String explanation)
    {
        store.insertVerification(new FindingVerificationRow(
                UUID.randomUUID().toString(), finding.id(), verifierRunId,
                false, false, false, List.of(), "unknown", "UNKNOWN", explanation));
        store.updateFinding(finding.id(), "NEEDS_AUTHOR_INPUT", "unknown", "UNKNOWN",
                finding.claim(), finding.severity());
    }

    private void insertRejectedVerification(String verifierRunId, FindingRow finding, String explanation)
    {
        store.insertVerification(new FindingVerificationRow(
                UUID.randomUUID().toString(), finding.id(), verifierRunId,
                false, false, false, List.of(), "rejected", "REJECTED", explanation));
        store.updateFinding(finding.id(), "dropped", "rejected", "REJECTED",
                finding.claim(), finding.severity());
    }

    private void reviewEvent(String prId, String event, Consumer<ObjectNode> details)
    {
        reviewEvent(prId, event, "agent", details);
    }

    private void reviewEvent(
            String prId, String event, String actor, Consumer<ObjectNode> details)
    {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("reviewEvent", event);
        store.findActiveReviewByPr(prId)
                .ifPresent(review -> payload.put("reviewId", review.id()));
        details.accept(payload);
        prs.recordReviewEvent(prId, actor, payload.toString());
    }

    private PR requirePr(String prId)
    {
        return prs.findById(prId)
                .orElseThrow(() -> new IllegalArgumentException("unknown PR " + prId));
    }

    private InvestigationReviewContext.Snapshot fullReviewSnapshot(
            PR pr, boolean allowWorkspaceSource)
    {
        if (allowWorkspaceSource && PR.ORIGIN_EXTERNAL.equals(pr.origin())) {
            contexts.prepareWatchedPr(pr);
        }
        InvestigationReviewContext.Snapshot snapshot = contexts.load(pr, allowWorkspaceSource);
        if (PR.ORIGIN_EXTERNAL.equals(pr.origin()) && snapshot.repositoryRoot() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "watched repository must contain the reviewed commit for full agent review");
        }
        return snapshot;
    }

    private AgentReviewRow requireReview(String reviewId)
    {
        return store.findReview(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("unknown agent review " + reviewId));
    }

    private List<PanelSeat> panel(String reviewClass, StartOptions options)
    {
        int target = switch (reviewClass) {
            case "high-risk" -> 3;
            case "standard" -> 2;
            default -> 1;
        };
        List<ReviewerDefRow> eligible = store.reviewerDefs().stream()
                .filter(ReviewerDefRow::enabled)
                .filter(row -> !Set.of("independent-verifier", "review-planner").contains(row.id()))
                .filter(row -> row.eligibleKinds().contains(reviewClass))
                .toList();
        if (eligible.isEmpty()) {
            throw new IllegalStateException(
                    "no enabled reviewer definition is eligible for " + reviewClass);
        }

        List<PanelSeat> seats = new ArrayList<>();
        if (options != null && ((options.runner() != null && !options.runner().isBlank())
                || (options.providerId() != null && !options.providerId().isBlank()))) {
            ProviderChoice requested = runner.choose(options.runner(), options.providerId());
            ReviewerDefRow definition = eligible.stream()
                    .filter(row -> requested.runner().equals(row.runner()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no enabled reviewer definition accepts the requested "
                                    + requested.runner() + " runner"));
            seats.add(new PanelSeat(requested, definition));
        }

        for (ReviewerDefRow definition : eligible) {
            if (seats.size() >= target
                    || seats.stream().anyMatch(seat -> seat.reviewerDef().id().equals(definition.id()))) {
                continue;
            }
            try {
                seats.add(new PanelSeat(provider(definition), definition));
            }
            catch (IllegalStateException unavailable) {
                log.debug("Reviewer definition {} is unavailable: {}", definition.id(), unavailable.getMessage());
            }
        }
        if (seats.isEmpty()) {
            throw new IllegalStateException(
                    "no configured provider can run the enabled reviewer definitions for " + reviewClass);
        }
        int reusable = seats.size();
        for (int i = 0; seats.size() < target; i++) {
            seats.add(seats.get(i % reusable));
        }
        return List.copyOf(seats);
    }

    private ProviderChoice provider(ReviewerDefRow definition)
    {
        String configured = definition.runnerJson().path("provider").asText("auto");
        String providerId = configured.isBlank() || "auto".equalsIgnoreCase(configured)
                ? null : configured;
        ProviderChoice choice = runner.choose(definition.runner(), providerId);
        if (!definition.runner().equals(choice.runner())) {
            throw new IllegalStateException(
                    "reviewer " + definition.id() + " requires " + definition.runner()
                            + " but provider " + choice.providerId() + " uses " + choice.runner());
        }
        return choice;
    }

    private ProviderChoice verifierProvider(
            ReviewerDefRow definition, ProviderChoice investigator)
    {
        String configured = definition.runnerJson().path("provider").asText("auto-cross-family");
        ProviderChoice choice = Set.of("", "auto", "auto-cross-family").contains(
                configured.toLowerCase(Locale.ROOT))
                ? runner.chooseVerifier(investigator, definition.runner())
                : runner.choose(definition.runner(), configured);
        if (!definition.runner().equals(choice.runner())
                || investigator.providerId().equalsIgnoreCase(choice.providerId())
                || investigator.family().equals(choice.family())) {
            throw new IllegalStateException(
                    "independent verifier must use the configured runner and a cross-family provider");
        }
        return choice;
    }

    private static String requiredText(String value, String field)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    private static String blankToNull(String value)
    {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static int validateCostCap(Integer costCapCents)
    {
        if (costCapCents == null) {
            throw new IllegalArgumentException("costCapCents is required");
        }
        if (costCapCents < 50 || costCapCents > 500 || costCapCents % 25 != 0) {
            throw new IllegalArgumentException(
                    "costCapCents must be between 50 and 500 in 25-cent increments");
        }
        return costCapCents;
    }

    private static String reviewClass(RoundBudget budget)
    {
        return switch (budget.wallClockMinutes()) {
            case 5 -> "trivial";
            case 20 -> "high-risk";
            default -> "standard";
        };
    }

    private static String normaliseRoundKind(String kind)
    {
        return switch (kind == null ? "continue" : kind) {
            case "continue", "re-review", "continuation" -> kind == null ? "continue" : kind;
            default -> throw new IllegalArgumentException("unknown round kind: " + kind);
        };
    }

    private static String scopeFor(List<String> findingIds, String seed)
    {
        if (findingIds != null && !findingIds.isEmpty()) {
            return "affected findings";
        }
        return seed == null ? "full" : "delta";
    }

    private static String stableCriterionId(String repoId, PlanObjective objective)
    {
        return UUID.nameUUIDFromBytes((repoId + "\n" + objective.kind() + "\n" + objective.statement())
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String renderComment(FindingRow finding)
    {
        if ("unknown".equals(finding.verificationStatus())
                || "SUPPORTED".equals(finding.confidenceClass())) {
            return finding.claim() + "\n\n**Question:** "
                    + finding.requestedAction();
        }
        return finding.claim() + "\n\n**Requested action:** " + finding.requestedAction();
    }

    private static int editMagnitude(String before, String after)
    {
        if (after == null) {
            return before.length();
        }
        return Math.abs(before.length() - after.length());
    }

    private static String normalised(String value)
    {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String digest(String value)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private boolean observationIsCurrent(
            InvestigationReviewContext.Snapshot snapshot, ObservationRow observation)
    {
        if (!snapshot.headCommit().equals(observation.commitSha())
                || observation.contentDigest() == null
                || !digest(observation.preview()).equalsIgnoreCase(observation.contentDigest())) {
            return false;
        }
        if (observation.path() == null || observation.startLine() == null) {
            return true;
        }
        try {
            int lines = contexts.fileLineCount(snapshot, observation.path());
            int end = observation.endLine() == null ? observation.startLine() : observation.endLine();
            return observation.startLine() >= 1
                    && end >= observation.startLine()
                    && end <= lines;
        }
        catch (RuntimeException invalidPath) {
            return false;
        }
    }

    static boolean rightSideRange(
            InvestigationReviewContext.Snapshot snapshot, String path,
            Integer startLine, Integer endLine)
    {
        if (path == null || path.isBlank() || startLine == null || endLine == null
                || startLine < 1 || endLine < startLine) {
            return false;
        }
        Set<Integer> lines = rightSideLines(snapshot.diff(), path);
        long covered = lines.stream()
                .filter(line -> line >= startLine && line <= endLine)
                .count();
        return covered == (long) endLine - startLine + 1;
    }

    private static Set<Integer> rightSideLines(String diff, String path)
    {
        String marker = "diff --git a/" + path + " b/" + path;
        int start = diff.indexOf(marker);
        if (start < 0) {
            return Set.of();
        }
        int end = diff.indexOf("\ndiff --git ", start + marker.length());
        String section = diff.substring(start, end < 0 ? diff.length() : end);
        Set<Integer> lines = new HashSet<>();
        int newLine = -1;
        for (String line : section.lines().toList()) {
            Matcher header = HUNK_HEADER.matcher(line);
            if (header.matches()) {
                newLine = Integer.parseInt(header.group(1));
                continue;
            }
            if (newLine < 0 || line.startsWith("\\ No newline")) {
                continue;
            }
            if (line.startsWith("-")) {
                continue;
            }
            if (line.startsWith("+") || line.startsWith(" ")) {
                lines.add(newLine++);
            }
        }
        return Set.copyOf(lines);
    }

    public record StartOptions(
            String runner, String providerId, String workspaceId, Boolean blocking)
    {
        public StartOptions(String runner, String providerId)
        {
            this(runner, providerId, null, null);
        }

        public StartOptions(String runner, String providerId, String workspaceId)
        {
            this(runner, providerId, workspaceId, null);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record QueueItem(
            String reviewId, String prId, String repo, Integer prNumber, String title,
            String status, String workspaceId, String ownerThreadId, boolean remoteOnly,
            int roundCount, int findingCount) {}

    public record FindingMutation(String action, String text) {}

    public record FindingOutcomeInput(
            String userDisposition, String authorResponse,
            String epistemicResolution, String utilityAssessment,
            Integer styleEditMagnitude) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReviewerDefInput(
            String id, String name, String description, String runner,
            JsonNode runnerJson, String persona, List<String> eligibleKinds,
            Boolean enabled) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PlanObjective(
            String kind, String statement, String sourceType, String sourceRef, boolean applicable) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PlanDraft(
            String reviewClass, RoundBudget budget, List<PlanObjective> objectives,
            String plannerSuggestion) {}

    private record RoundWork(
            AgentReviewRow review, PR pr, InvestigationReviewContext.Snapshot snapshot,
            PlanDraft plan, ReviewRoundRow round, AgentRun run,
            List<ReviewObjectiveRow> objectives, List<AssignmentWork> assignments,
            String seed) {}

    private record AssignmentWork(
            String id, ProviderChoice provider, ReviewerDefRow reviewerDef) {}

    private record PanelSeat(ProviderChoice provider, ReviewerDefRow reviewerDef) {}

    private record BudgetReservation(int roundCap, int turnCap) {}

    private record VerificationOutcome(int costCents, long tokensIn, long tokensOut, int providerRounds)
    {
        private static final VerificationOutcome EMPTY = new VerificationOutcome(0, 0, 0, 0);
    }

    private record CachedPlan(PlanDraft plan, Instant expiresAt) {}

    private record ReviewOwnership(String workspaceId, String threadId, String taskId) {}
}
