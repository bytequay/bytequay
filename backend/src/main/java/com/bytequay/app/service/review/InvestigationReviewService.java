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
import com.bytequay.app.domain.InvestigationReviewData.ReviewRoundRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewerDefRow;
import com.bytequay.app.domain.InvestigationReviewData.RoundBudget;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.review.DeterministicReviewCoverage.CoverageReport;
import com.bytequay.app.service.review.DeterministicReviewCoverage.FailureClassResult;
import com.bytequay.app.service.review.DeterministicReviewCoverage.SweepResult;
import com.bytequay.app.service.review.InvestigationReviewRunner.ProviderChoice;
import com.bytequay.app.service.review.InvestigationReviewRunner.RunOutcome;
import com.bytequay.app.service.runs.AgentRunService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/** AgentReview lifecycle and deterministic orchestration. */
@Service
public class InvestigationReviewService
{
    private static final Logger log = LoggerFactory.getLogger(InvestigationReviewService.class);
    private static final Duration PREFLIGHT_TTL = Duration.ofHours(24);
    private static final Set<String> CRITERION_KINDS = Set.of(
            "hard-invariant", "engineering-principle", "repo-convention");
    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");

    private final InvestigationReviewStore store;
    private final InvestigationReviewContext contexts;
    private final InvestigationReviewRunner runner;
    private final AgentRunService runs;
    private final PRService prs;
    private final TaskStore tasks;
    private final ThreadStore threads;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, CachedPlan> preflightPlans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<String>> preflightAugments =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Thread> activeRounds = new ConcurrentHashMap<>();

    public InvestigationReviewService(
            InvestigationReviewStore store, InvestigationReviewContext contexts,
            InvestigationReviewRunner runner, AgentRunService runs,
            PRService prs, TaskStore tasks, ThreadStore threads, ObjectMapper mapper)
    {
        this.store = requireNonNull(store, "store is null");
        this.contexts = requireNonNull(contexts, "contexts is null");
        this.runner = requireNonNull(runner, "runner is null");
        this.runs = requireNonNull(runs, "runs is null");
        this.prs = requireNonNull(prs, "prs is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.threads = requireNonNull(threads, "threads is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    public PlanDraft preflight(String prId)
    {
        PR pr = requirePr(prId);
        InvestigationReviewContext.Snapshot snapshot = contexts.load(pr);
        PlanDraft draft = cachedPlan(snapshot);
        CompletableFuture<String> augment = preflightAugment(snapshot, draft);
        return new PlanDraft(
                draft.reviewClass(), draft.budget(), draft.objectives(), augment.getNow(null));
    }

    public InvestigationReviewData start(String prId, StartOptions options)
    {
        Optional<AgentReviewRow> existing = store.findActiveReviewByPr(prId);
        if (existing.isPresent()) {
            PR pr = requirePr(prId);
            return detail(ensureOwnership(existing.get(), pr, options));
        }
        PR pr = requirePr(prId);
        ReviewOwnership ownership = ownershipFor(pr, options);
        InvestigationReviewContext.Snapshot snapshot = contexts.load(pr);
        PlanDraft plan = cachedPlan(snapshot);
        List<PanelSeat> panel = panel(plan.reviewClass(), options);
        Instant now = Instant.now();
        AgentReviewRow review = new AgentReviewRow(
                UUID.randomUUID().toString(), pr.repo() == null ? "local" : pr.repo(), pr.id(),
                snapshot.baseCommit(), snapshot.headCommit(), "ACTIVE", ownership.workspaceId(),
                ownership.threadId(), ownership.taskId());
        store.insertReview(review, now);
        RoundWork work = createRound(review, pr, snapshot, plan, panel,
                "initial", "full", List.of());
        reviewEvent(pr.id(), "started", "you", node -> {
            node.put("reviewId", review.id());
            node.put("roundId", work.round().id());
            node.put("reviewClass", plan.reviewClass());
        });
        launch(work);
        preflightAugment(snapshot, plan).thenAccept(suggestion -> {
            if (suggestion != null) {
                reviewEvent(work.pr().id(), "plan-amendment-suggested", node -> {
                    node.put("roundId", work.round().id());
                    node.put("suggestion", suggestion);
                });
            }
        });
        return detail(review);
    }

    public Optional<InvestigationReviewData> findByPr(String prId)
    {
        return store.findActiveReviewByPr(prId).map(found -> {
            PR pr = requirePr(prId);
            AgentReviewRow review = ensureOwnership(found, pr, null);
            String head = contexts.headCommit(pr);
            if (!review.reviewedHeadCommit().equals(head)
                    && !"STALE".equals(review.status())) {
                store.rounds(review.id()).stream().reduce((first, second) -> second)
                        .ifPresent(round -> stopRound(round, "review evidence became stale"));
                store.updateReviewStatus(review.id(), "STALE");
                review = new AgentReviewRow(review.id(), review.repoId(), review.prId(),
                        review.baseCommit(), review.reviewedHeadCommit(), "STALE",
                        review.workspaceId(), review.ownerThreadId(), review.ownerTaskId());
                syncStandaloneOwner(review, ThreadStatus.NEEDS_ATTENTION, null);
            }
            return detail(review);
        });
    }

    public Optional<InvestigationReviewData> findByOwnerThread(String threadId)
    {
        return store.findActiveReviewByOwnerThread(threadId).map(this::detail);
    }

    /** Stop and remove every AgentReview owned by a thread before that thread
     * (or its workspace) is permanently deleted. */
    public void purgeByOwnerThread(String threadId)
    {
        for (AgentReviewRow review : store.reviewsByOwnerThread(threadId)) {
            store.rounds(review.id()).stream()
                    .filter(round -> "RUNNING".equals(round.status()))
                    .forEach(round -> stopRound(round, "owning thread was deleted"));
            store.deleteReview(review.id());
            log.info("deleted agent review {} with owner thread {}", review.id(), threadId);
        }
    }

    private AgentReviewRow ensureOwnership(
            AgentReviewRow review, PR pr, StartOptions options)
    {
        if (review.ownerThreadId() != null && !review.ownerThreadId().isBlank()
                && review.workspaceId() != null && !review.workspaceId().isBlank()) {
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "workspaceId is required to start a standalone PR review");
        }
        String provider = options == null ? null : blankToNull(options.providerId());
        if (provider == null) {
            provider = "agent-review";
        }
        Instant now = Instant.now();
        String ref = pr.repo() + "#" + pr.remotePrNumber();
        String title = pr.title() == null || pr.title().isBlank()
                ? "Review " + ref
                : "Review " + ref + " — " + pr.title();
        com.bytequay.app.domain.Thread thread = new com.bytequay.app.domain.Thread(
                UUID.randomUUID().toString(), ThreadKind.LOGIC_LOOP, provider,
                null, title,
                ThreadStatus.RUNNING, provider,
                0L, 0L, 0L, now, now, null, null,
                ThreadFlow.REVIEW, workspaceId, null);
        threads.saveThread(thread);
        return new ReviewOwnership(workspaceId, thread.id(), null);
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
        AgentReviewRow review = requireReview(reviewId);
        RoundWork work = prepareRound(review, kind, findingIds, options);
        launch(work);
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
        RoundWork work = prepareRound(review, "continuation", List.of(findingId), null);
        ReviewRoundRow round = work.round();
        ReviewAssignmentRow assignment = store.assignments(review.id()).stream()
                .filter(row -> row.roundId().equals(round.id())).findFirst().orElseThrow();
        String stepId = UUID.randomUUID().toString();
        store.insertStep(new InvestigationStepRow(
                stepId, assignment.id(), null, "user-answer", mapper.createObjectNode(),
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
        launch(work);
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
            case "reopen" -> {
                store.updateFinding(findingId, "included", finding.verificationStatus(),
                        finding.confidenceClass(), finding.claim(), finding.severity());
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
            return detail(review);
        }
        reviewEvent(review.prId(), "round-cancelled", "you",
                node -> node.put("roundId", round.id()));
        syncStandaloneOwner(review, ThreadStatus.COMPLETED, null);
        return detail(review);
    }

    private boolean stopRound(ReviewRoundRow round, String reason)
    {
        boolean finished = store.finishRunningRound(
                round.id(), "CANCELLED", null, round.costCents());
        Set<String> runIds = runs.findByReviewRound(round.id()).stream()
                .map(AgentRun::id).collect(Collectors.toCollection(LinkedHashSet::new));
        runIds.add(round.agentRunId()); // Covers rows created before round ownership was persisted.
        runIds.stream().map(runs::findById).flatMap(Optional::stream)
                .filter(AgentRun::isLive)
                .forEach(run -> runs.transition(run.id(), AgentRun.STATUS_CANCELLED, reason));
        Thread worker = activeRounds.get(round.id());
        if (worker != null) {
            worker.interrupt();
        }
        return finished;
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
            List<String> findingIds)
    {
        String roundId = UUID.randomUUID().toString();
        AgentRun run = openReviewRun(review, roundId, plan.budget().costCapCents());
        ReviewRoundRow round = new ReviewRoundRow(
                roundId, review.id(), run.id(), trigger, scope, snapshot.headCommit(), null,
                "RUNNING", plan.budget(), 0, snapshot.capabilities(), null);
        store.insertRound(round, Instant.now());
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
                List.copyOf(assignments));
    }

    /** AgentReview is a task-owned artifact track, not a development stage. */
    private AgentRun openReviewRun(AgentReviewRow review, String roundId, int budget)
    {
        if (review.ownerTaskId() != null) {
            return runs.openTaskArtifact(review.ownerTaskId(), AgentRun.KIND_PANEL_REVIEW,
                    null, roundId, budget);
        }
        return runs.openDetached(AgentRun.KIND_PANEL_REVIEW, null, roundId, budget);
    }

    private RoundWork prepareRound(
            AgentReviewRow review, String kind, List<String> findingIds, StartOptions options)
    {
        PR pr = requirePr(review.prId());
        InvestigationReviewContext.Snapshot snapshot = contexts.load(pr);
        PlanDraft plan = cachedPlan(snapshot);
        List<PanelSeat> panel = panel(plan.reviewClass(), options);
        List<String> selected = findingIds == null ? List.of() : findingIds;
        RoundWork work = createRound(review, pr, snapshot, plan, panel,
                normaliseRoundKind(kind), scopeFor(selected), selected);
        store.updateReviewStatus(review.id(), "ACTIVE");
        syncStandaloneOwner(review, ThreadStatus.RUNNING, null);
        reviewEvent(pr.id(), "round-started", "you", node -> node.put("roundId", work.round().id()));
        return work;
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
            int assignmentCap = Math.max(5,
                    work.plan().budget().costCapCents() / work.assignments().size());
            for (AssignmentWork assignment : work.assignments()) {
                RunOutcome investigation = runner.investigate(
                        assignment.provider(), work.review().id(), assignment.id(),
                        work.snapshot(), applicableObjectives,
                        coverage.promptContext(), assignment.reviewerDef().persona(), assignmentCap);
                ensureRunning(work);
                investigations.add(investigation);
                cost += investigation.costCents();
                ReviewAssignmentRow recorded = store.assignments(work.review().id()).stream()
                        .filter(row -> row.id().equals(assignment.id()))
                        .findFirst()
                        .orElseThrow();
                boolean aborted = "ABORTED".equals(investigation.end());
                store.skipRunningSteps(assignment.id());
                store.updateAssignment(assignment.id(), aborted ? "aborted" : "completed",
                        recorded.understandingSummary().isBlank()
                                ? "Investigation complete." : recorded.understandingSummary(),
                        recorded.assumptionsJson(), recorded.unknownsJson());
                store.updateRunningRoundCost(work.round().id(), cost);
                if (aborted) {
                    break;
                }
            }
            if (investigations.stream().anyMatch(outcome -> "ABORTED".equals(outcome.end()))) {
                cost = Math.max(cost, work.plan().budget().costCapCents());
                if (!store.finishRunningRound(work.round().id(), "ERRORED", null, cost)) {
                    return;
                }
                runs.updateHeadline(work.run().id(), "Budget cap hit");
                runs.updateMetrics(work.run().id(), metrics(
                        work, investigations, VerificationOutcome.EMPTY, List.of(), cost).toString());
                runs.transition(work.run().id(), AgentRun.STATUS_FAILED, "review budget cap hit");
                reviewEvent(work.pr().id(), "round-budget-halted",
                        node -> node.put("roundId", work.round().id()));
                syncStandaloneOwner(work.review(), ThreadStatus.ERRORED, "review budget cap hit");
                return;
            }
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
                AssignmentWork primary = work.assignments().get(0);
                RunOutcome refutation = runner.selfRefute(
                        primary.provider(), work.review().id(), primary.id(), work.snapshot(),
                        missingRefutationPass.stream()
                                .map(finding -> findingBundle(
                                        finding,
                                        roundEvidenceByFinding.getOrDefault(finding.id(), List.of()),
                                        roundObservationsById))
                                .reduce("", (left, right) -> left + "\n---\n" + right),
                        Math.max(3, Math.min(10, assignmentCap / 4)));
                ensureRunning(work);
                investigations.add(refutation);
                cost += refutation.costCents();
                recordSelfRefutationPass(primary.id(), missingRefutationPass.size(), refutation);
                if ("ABORTED".equals(refutation.end())) {
                    throw new IllegalStateException("mandatory self-refutation pass exceeded its budget");
                }
            }
            List<FindingRow> candidates = consolidate(store.findings(work.review().id()).stream()
                    .filter(finding -> finding.roundId().equals(work.round().id()))
                    .toList());
            VerificationOutcome verification = candidates.isEmpty()
                    ? VerificationOutcome.EMPTY
                    : "trivial".equals(work.plan().reviewClass())
                            ? verifyTrivial(work, candidates) : verify(work, candidates);
            ensureRunning(work);
            cost += verification.costCents();
            List<FindingRow> finished = store.findings(work.review().id()).stream()
                    .filter(finding -> finding.roundId().equals(work.round().id()))
                    .toList();
            materialiseComments(work, finished);
            boolean coverageGaps = resolveObjectives(work, finished);
            boolean questions = coverageGaps || finished.stream()
                    .anyMatch(f -> "unknown".equals(f.verificationStatus()));
            String status = questions ? "COMPLETED_WITH_QUESTIONS" : "COMPLETED";
            if (!store.finishRunningRound(
                    work.round().id(), status, work.snapshot().headCommit(), cost)) {
                return;
            }
            store.updateReviewHead(work.review().id(), work.snapshot().headCommit(), "ACTIVE");
            runs.updateHeadline(work.run().id(), finished.size() + " findings");
            runs.updateMetrics(work.run().id(), metrics(
                    work, investigations, verification, finished, cost).toString());
            runs.transition(work.run().id(), AgentRun.STATUS_SUCCEEDED, "investigation review complete");
            reviewEvent(work.pr().id(), "round-complete", node -> {
                node.put("roundId", work.round().id());
                node.put("findingCount", finished.size());
            });
            syncStandaloneOwner(work.review(), questions
                    ? ThreadStatus.NEEDS_ATTENTION : ThreadStatus.AWAITING_REVIEW, null);
        }
        catch (RuntimeException e) {
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
            syncStandaloneOwner(work.review(), ThreadStatus.ERRORED,
                    e.getMessage() == null ? "review failed" : e.getMessage());
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

    private void launch(RoundWork work)
    {
        Thread worker = Thread.ofVirtual().unstarted(() -> {
            try {
                execute(work);
            }
            finally {
                activeRounds.remove(work.round().id(), Thread.currentThread());
            }
        });
        activeRounds.put(work.round().id(), worker);
        worker.start();
    }

    private void ensureRunning(RoundWork work)
    {
        boolean running = store.findRound(work.round().id())
                .map(round -> "RUNNING".equals(round.status())).orElse(false);
        if (!running || Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("review round stopped");
        }
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

    private VerificationOutcome verify(RoundWork work, List<FindingRow> candidates)
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
        AgentRun verifierRun = openReviewRun(work.review(), work.round().id(),
                Math.max(10, work.plan().budget().costCapCents() / 3));
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
                List<FindingEvidenceRow> findingEvidence =
                        evidenceByFinding.getOrDefault(finding.id(), List.of());
                String bundle = findingBundle(finding, findingEvidence, observationsById);
                if (!deterministicallyValid(work, finding, findingEvidence, observationsById)) {
                    insertRejectedVerification(verifierRun.id(), finding,
                            "Deterministic validation failed: missing/current evidence or action.");
                    continue;
                }
                String blind = null;
                if (finding.severity() >= 4) {
                    RunOutcome reconstruction = runner.reconstruct(
                            verifier, work.review().id(), verifierAssignment, work.snapshot(),
                            evidenceLocations(findingEvidence, observationsById),
                            verifierDefinition.persona(), 10);
                    ensureRunning(work);
                    blind = reconstruction.finalText();
                    cost += reconstruction.costCents();
                    tokensIn += reconstruction.tokensIn();
                    tokensOut += reconstruction.tokensOut();
                    providerRounds += reconstruction.providerRounds();
                }
                RunOutcome result = runner.verify(
                        verifier, work.review().id(), verifierAssignment, work.snapshot(),
                        verifierRun.id(), bundle, blind,
                        verifierDefinition.persona(),
                        Math.max(10, work.plan().budget().costCapCents() / 3));
                ensureRunning(work);
                cost += result.costCents();
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
            store.updateAssignment(verifierAssignment, "completed", "Verification complete.",
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
                boolean cancelled = Thread.currentThread().isInterrupted()
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
                        .ifPresent(existing -> store.insertRelation(new FindingRelationRow(
                                candidate.id(), existing.id(), "RELATED_ROOT_CAUSE")));
                kept.add(candidate);
                continue;
            }
            store.insertRelation(new FindingRelationRow(
                    candidate.id(), duplicate.id(), "DUPLICATES"));
            store.updateFinding(candidate.id(), "dropped", candidate.verificationStatus(),
                    candidate.confidenceClass(), candidate.claim(), candidate.severity());
        }
        return List.copyOf(kept);
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
        for (FindingRow finding : candidates) {
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
                || !work.snapshot().headCommit().equals(finding.lastCheckedCommit())) {
            return false;
        }
        if (!hasRequiredEvidence(evidence)) {
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

    private void materialiseComments(RoundWork work, List<FindingRow> findings)
    {
        Map<String, List<FindingEvidenceRow>> evidenceByFinding = evidenceByFinding(work.review().id());
        Map<String, ObservationRow> observationsById = observationsById(work.review().id());
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
            if ("TENTATIVE".equals(finding.confidenceClass())) {
                continue;
            }
            if (!materialisedFindingIds.add(finding.id())) {
                continue;
            }
            ObservationRow anchor = evidenceByFinding.getOrDefault(finding.id(), List.of()).stream()
                    .filter(row -> "SUPPORTS".equals(row.relation()))
                    .map(FindingEvidenceRow::observationId)
                    .map(observationsById::get).filter(Objects::nonNull)
                    .filter(observation -> rightSideAnchor(work.snapshot(), observation))
                    .findFirst().orElse(null);
            String body = renderComment(finding);
            PRComment comment = prs.addComment(
                    work.pr().id(), PRComment.ORIGIN_LOCAL,
                    anchor == null ? PRComment.SCOPE_PR : PRComment.SCOPE_FILE_LINE,
                    anchor == null ? null : anchor.path(),
                    anchor == null ? null : anchor.startLine() == null ? 1 : anchor.startLine(),
                    "RIGHT", null, null, "agent", body, null);
            prs.attachFinding(comment.id(), finding.id());
        }
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
        List<PRCommentDto> comments = prs.comments(review.prId()).stream()
                .filter(comment -> comment.findingId() != null)
                .map(PRCommentDto::from).toList();
        List<PRTimelineEntryDto> timeline = prs.timeline(review.prId()).stream()
                .map(event -> PRTimelineEntryDto.from(event, mapper))
                .filter(event -> event.payload().hasNonNull("reviewEvent"))
                .toList();
        return new InvestigationReviewData(
                review, roundRows, runRows, store.criteria(review.id()), store.objectives(review.id()),
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

    private PlanDraft plan(InvestigationReviewContext.Snapshot snapshot)
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
            case "trivial" -> new RoundBudget(10, 5);
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
        for (FailureClassResult failureClass : coverage.failureClasses()) {
            objectives.add(new PlanObjective(
                    "hard-invariant", failureClassStatement(failureClass.id()),
                    "failure-class", failureClass.id(), failureClass.applicable()));
        }
        return new PlanDraft(reviewClass, budget, List.copyOf(objectives), null);
    }

    private PlanDraft cachedPlan(InvestigationReviewContext.Snapshot snapshot)
    {
        String key = preflightKey(snapshot);
        Instant now = Instant.now();
        Set<String> expired = preflightPlans.entrySet().stream()
                .filter(entry -> !entry.getValue().expiresAt().isAfter(now))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        expired.forEach(expiredKey -> {
            preflightPlans.remove(expiredKey);
            preflightAugments.remove(expiredKey);
        });
        String prPrefix = snapshot.pr().id() + "@";
        preflightPlans.keySet().removeIf(existing -> existing.startsWith(prPrefix) && !existing.equals(key));
        preflightAugments.keySet().removeIf(existing -> existing.startsWith(prPrefix) && !existing.equals(key));
        CachedPlan cached = preflightPlans.get(key);
        if (cached != null) {
            return cached.plan();
        }
        PlanDraft draft = plan(snapshot);
        preflightPlans.put(key, new CachedPlan(draft, now.plus(PREFLIGHT_TTL)));
        preflightAugments.remove(key);
        return draft;
    }

    private CompletableFuture<String> preflightAugment(
            InvestigationReviewContext.Snapshot snapshot, PlanDraft plan)
    {
        String key = preflightKey(snapshot);
        CompletableFuture<String> existing = preflightAugments.get(key);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<String> created = new CompletableFuture<>();
        existing = preflightAugments.putIfAbsent(key, created);
        if (existing != null) {
            return existing;
        }
        Thread.startVirtualThread(() -> {
            try {
                String repoId = snapshot.pr().repo() == null ? "local" : snapshot.pr().repo();
                List<ReviewObjectiveRow> objectives = plan.objectives().stream()
                        .filter(PlanObjective::applicable)
                        .map(objective -> new ReviewObjectiveRow(
                                stableCriterionId(repoId, objective), "preflight",
                                stableCriterionId(repoId, objective), objective.statement(),
                                objective.sourceType(), "applicable", "pending"))
                        .toList();
                created.complete(runner.suggestPlanAmendment(
                        runner.choose(null, null), snapshot, objectives));
            }
            catch (RuntimeException e) {
                log.debug("Optional preflight augmentation failed for {}: {}",
                        snapshot.pr().id(), e.getMessage());
                created.complete(null);
            }
        });
        return created;
    }

    private static String preflightKey(InvestigationReviewContext.Snapshot snapshot)
    {
        return snapshot.pr().id() + "@" + snapshot.headCommit();
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
                .filter(row -> !"independent-verifier".equals(row.id()))
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

    private static String normaliseRoundKind(String kind)
    {
        return switch (kind == null ? "continue" : kind) {
            case "continue", "re-review", "continuation" -> kind == null ? "continue" : kind;
            default -> throw new IllegalArgumentException("unknown round kind: " + kind);
        };
    }

    private static String scopeFor(List<String> findingIds)
    {
        return findingIds == null || findingIds.isEmpty() ? "full" : "affected findings";
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

    private static boolean rightSideAnchor(
            InvestigationReviewContext.Snapshot snapshot, ObservationRow observation)
    {
        if (observation.path() == null || observation.startLine() == null) {
            return false;
        }
        return rightSideLines(snapshot.diff(), observation.path()).contains(observation.startLine());
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

    public record StartOptions(String runner, String providerId, String workspaceId)
    {
        public StartOptions(String runner, String providerId)
        {
            this(runner, providerId, null);
        }
    }

    public record FindingMutation(String action, String text) {}

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
            List<ReviewObjectiveRow> objectives, List<AssignmentWork> assignments) {}

    private record AssignmentWork(
            String id, ProviderChoice provider, ReviewerDefRow reviewerDef) {}

    private record PanelSeat(ProviderChoice provider, ReviewerDefRow reviewerDef) {}

    private record VerificationOutcome(int costCents, long tokensIn, long tokensOut, int providerRounds)
    {
        private static final VerificationOutcome EMPTY = new VerificationOutcome(0, 0, 0, 0);
    }

    private record CachedPlan(PlanDraft plan, Instant expiresAt) {}

    private record ReviewOwnership(String workspaceId, String threadId, String taskId) {}
}
