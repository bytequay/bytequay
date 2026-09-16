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
package com.bytequay.app.review;

import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.QueueAgentInputCommand;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskBranches;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderUnavailableException;
import com.bytequay.app.agent.WorkflowAgentTasks;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.ReviewExperiments;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewCandidateValidation;
import com.bytequay.core.review.ReviewCommit;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewDraftStore;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;
import com.bytequay.core.review.ReviewValidation;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

import static com.bytequay.agent.spi.AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE;
import static com.bytequay.app.review.DefaultAgentReviewService.CompletionAction.ACCEPT;
import static com.bytequay.app.review.DefaultAgentReviewService.CompletionAction.COLLECT_EVIDENCE;
import static com.bytequay.app.review.DefaultAgentReviewService.CompletionAction.INCOMPLETE_COVERAGE;
import static com.bytequay.app.review.DefaultAgentReviewService.CompletionAction.INCOMPLETE_REPORT;
import static com.bytequay.app.review.DefaultAgentReviewService.CompletionAction.REPAIR;
import static com.bytequay.app.review.DefaultAgentReviewService.CompletionAction.VALIDATE;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Service
public class DefaultAgentReviewService
        implements AgentReviewService
{
    private static final Logger log = LoggerFactory.getLogger(DefaultAgentReviewService.class);

    private final AgentReviewStore store;
    private final ReviewDraftStore drafts;
    private final AgentTaskService tasks;
    private final AgentTurnService turns;
    private final QueuedInputStore queueStore;
    private final AgentCompletionService completions;
    private final AgentApprovalService approvals;
    private final AgentDriverRegistry drivers;
    private final ModelSettingsService models;
    private final WorkspaceRepositoryService workspaces;
    private final WorktreeService worktrees;
    private final GitCommandRunner git;
    private final ReviewExperiments experiments;
    private final PullRequestService pullRequests;
    private final PullRequestRefreshService refresh;
    private final CredentialService credentials;
    private final ReviewTaskObserver observer;
    private final Clock clock;
    private final Striped<Lock> startLocks = Striped.lock(32);
    private final ReviewLocks locks;

    public DefaultAgentReviewService(
            AgentReviewStore store,
            ReviewDraftStore drafts,
            AgentTaskService tasks,
            AgentTurnService turns,
            QueuedInputStore queueStore,
            AgentCompletionService completions,
            AgentApprovalService approvals,
            AgentDriverRegistry drivers,
            ModelSettingsService models,
            WorkspaceRepositoryService workspaces,
            WorktreeService worktrees,
            GitCommandRunner git,
            ReviewExperiments experiments,
            PullRequestService pullRequests,
            PullRequestRefreshService refresh,
            CredentialService credentials,
            ReviewTaskObserver observer,
            ReviewLocks locks,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.drafts = requireNonNull(drafts, "drafts is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.queueStore = requireNonNull(queueStore, "queueStore is null");
        this.completions = requireNonNull(completions, "completions is null");
        this.approvals = requireNonNull(approvals, "approvals is null");
        this.drivers = requireNonNull(drivers, "drivers is null");
        this.models = requireNonNull(models, "models is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.git = requireNonNull(git, "git is null");
        this.experiments = requireNonNull(experiments, "experiments is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.refresh = requireNonNull(refresh, "refresh is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.observer = requireNonNull(observer, "observer is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.locks = requireNonNull(locks, "locks is null");
    }

    @Override
    public Optional<AgentReviewProjection> find(String workspaceId, String repo, int number)
    {
        return store.findOpenReview(workspaceId, normalize(repo), number).map(review -> projection(review.id()));
    }

    @Override
    public AgentReviewProjection get(String reviewId)
    {
        return projection(reviewId);
    }

    @Override
    public List<AgentReviewProjection> list(String workspaceId)
    {
        return store.listReviews(workspaceId).stream().map(review -> projection(review.id())).collect(toImmutableList());
    }

    @Override
    public AgentReviewProjection start(String workspaceId, String repo, int number)
    {
        String repository = normalize(repo);
        Lock lock = startLocks.get(workspaceId + "/" + repository + "#" + number);
        lock.lock();
        try {
            Optional<AgentReview> existing = store.findOpenReview(workspaceId, repository, number);
            if (existing.isPresent()) {
                return projection(existing.orElseThrow().id());
            }
            WorkspaceRepositoryContext workspace = workspaces.requireReadyRepository(workspaceId);
            String monitored = (workspace.monitoredRepository().owner() + "/" + workspace.monitoredRepository().repo()).toLowerCase(Locale.ROOT);
            if (!monitored.equals(repository)) {
                throw new ReviewInvalidRequestException("Pull request " + repository + "#" + number + " does not belong to this workspace");
            }
            ModelSelection selection = selectModel(workspaceId);
            WorkflowAgentTasks.requireReady(drivers, selection.laneId(), AgentPermissionPolicy.AUTO);
            Snapshot snapshot = snapshot(workspace, repository, number);
            TaskId taskId = TaskId.random();
            String title = "Review " + repository + "#" + number;
            String branch = TaskBranches.generate(taskId, credentials.resolveGitHubCredential().profile().login(), title);
            AgentTask task = WorkflowAgentTasks.createOrGet(tasks, taskId, () -> tasks.create(new CreateAgentTaskCommand(
                    taskId,
                    workspaceId,
                    selection.laneId(),
                    WorkflowAgentTasks.repositoryIdentity(workspace.selectedRepository()),
                    snapshot.headSha(),
                    branch,
                    title,
                    selection.defaults())));
            Instant now = Instant.now(clock);
            String reviewId = UUID.randomUUID().toString();
            return locked(reviewId, () -> {
                AgentReview review = store.insertReview(new AgentReview(
                        reviewId,
                        workspaceId,
                        repository,
                        number,
                        snapshot.pullRequestId(),
                        task.id().toString(),
                        AgentReview.State.OPEN,
                        false,
                        Optional.empty(),
                        Optional.of(snapshot.headSha()),
                        0,
                        now,
                        now,
                        Optional.empty()));
                observer.watch(review);
                startRun(review, snapshot, workspace, 1, Optional.empty(), ImmutableList.of());
                return projection(review.id());
            });
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public AgentReviewProjection stop(String reviewId)
    {
        return locked(reviewId, () -> {
            AgentReview review = requireOpen(reviewId);
            store.updateReview(review.withFollowUpPaused(true, Instant.now(clock)));
            activeTurn(tasks.get(TaskId.valueOf(review.taskId()))).ifPresent(turn -> turns.cancel(turn.id()));
            return projection(reviewId);
        });
    }

    @Override
    public AgentReviewProjection resume(String reviewId)
    {
        return locked(reviewId, () -> {
            AgentReview review = requireOpen(reviewId);
            Optional<ReviewRun> active = latestRun(reviewId).filter(ReviewRun::active);
            if (active.isPresent()) {
                review = settle(review, active.orElseThrow(), tasks.get(TaskId.valueOf(review.taskId())));
            }
            review = store.updateReview(review.withFollowUpPaused(false, Instant.now(clock)));
            Optional<ReviewRun> resumed = latestRun(reviewId).filter(ReviewRun::active);
            if (resumed.isPresent()) {
                review = settle(review, resumed.orElseThrow(), tasks.get(TaskId.valueOf(review.taskId())));
            }
            Optional<ReviewRun> latest = latestRun(reviewId);
            boolean retry = latest.map(run -> run.state() != ReviewRun.State.ACCEPTED && !run.active()).orElse(true);
            followHead(review, retry);
            return projection(reviewId);
        });
    }

    @Override
    public AgentReviewProjection update(String reviewId)
    {
        AgentReview review = requireOpen(reviewId);
        refresh.refreshAllDetail(review.repo(), review.number());
        String head = pullRequests.findHeadSha(review.repo(), review.number())
                .orElseThrow(() -> new ReviewConflictException("The pull request head is unknown after refresh"));
        return locked(reviewId, () -> {
            store.updateReview(requireOpen(reviewId).withObservedHead(head, Instant.now(clock)));
            return resume(reviewId);
        });
    }

    @Override
    public AgentReviewProjection end(String reviewId)
    {
        return locked(reviewId, () -> {
            AgentReview review = requireOpen(reviewId);
            TaskId taskId = TaskId.valueOf(review.taskId());
            AgentTask task = tasks.get(taskId);
            if (activeTurn(task).isPresent()) {
                throw new ReviewConflictException("Stop the running turn before ending the review");
            }
            observer.unwatch(reviewId);
            if (task.state() == TaskState.OPEN) {
                completions.complete(new CompleteAgentTaskCommand(taskId, Optional.of("Agent review ended by the user")));
            }
            latestRun(reviewId).filter(ReviewRun::active)
                    .ifPresent(run -> store.updateRun(run.ended(ReviewRun.State.STOPPED, Optional.of("Review ended"), Instant.now(clock))));
            store.updateReview(store.findReview(reviewId).orElseThrow().closed(Instant.now(clock)));
            try {
                completions.cleanupWorktree(taskId);
            }
            catch (RuntimeException failure) {
                // Dirty experiments are kept for inspection; the task's own cleanup retry owns the rest.
                log.warn("Review {} ended but its worktree was retained: {}", reviewId, failure.toString());
            }
            return projection(reviewId);
        });
    }

    @Override
    public ReviewMessage ask(String reviewId, Optional<String> findingId, Optional<String> cardId, int targetRevision, String message)
    {
        requireNonNull(findingId, "findingId is null");
        requireNonNull(cardId, "cardId is null");
        if (findingId.isPresent() && cardId.isPresent()) {
            throw new ReviewInvalidRequestException("A question must target either a finding or a summary card");
        }
        String text = requireNonNull(message, "message is null").strip();
        if (text.isEmpty()) {
            throw new ReviewInvalidRequestException("message must not be blank");
        }
        return locked(reviewId, () -> {
            AgentReview review = requireOpen(reviewId);
            ReviewRun run = cardId.map(id -> ReviewCards.requireRun(store, reviewId, id))
                    .orElseGet(() -> latestRun(reviewId).orElseThrow(() -> new ReviewConflictException("The review has no run yet")));
            String label;
            String runId = run.id();
            if (findingId.isPresent()) {
                ReviewFinding finding = requireFinding(review, findingId.orElseThrow());
                if (finding.state() == ReviewFinding.State.WITHDRAWN) {
                    throw new ReviewConflictException("The agent withdrew this finding");
                }
                if (finding.revision() != targetRevision) {
                    throw new ReviewConflictException("The finding was revised to revision " + finding.revision() + "; reload before asking");
                }
                label = finding.id();
                runId = finding.runId();
            }
            else {
                int revision = run.summary().map(summary -> summary.revision()).orElse(1);
                if (revision != targetRevision) {
                    throw new ReviewConflictException("The summary was revised to revision " + revision + "; reload before asking");
                }
                label = cardId.orElse("summary");
            }
            ReviewRun targetRun = store.findRun(runId).orElseThrow();
            String prompt = ReviewPrompts.question(label, targetRevision, targetRun.headSha(), text)
                    + cardId.map(id -> "\n\nSelected card:\n" + ReviewCards.content(targetRun, id)).orElse("");
            String messageId = UUID.randomUUID().toString();
            TaskId taskId = TaskId.valueOf(review.taskId());
            AgentTask task = tasks.get(taskId);
            Optional<String> turnId = Optional.empty();
            Optional<String> queuedId = Optional.empty();
            if (idle(task)) {
                turnId = Optional.of(turns.start(new StartAgentTurnCommand(taskId, prompt, ImmutableList.of(), "review-ask:" + messageId)).id().toString());
            }
            else {
                queuedId = Optional.of(turns.enqueue(new QueueAgentInputCommand(taskId, task.version(), prompt, ImmutableList.of(), "review-ask:" + messageId)).id().toString());
            }
            return store.insertMessage(new ReviewMessage(
                    messageId,
                    reviewId,
                    findingId,
                    cardId,
                    ReviewMessage.Author.USER,
                    text,
                    targetRevision,
                    runId,
                    turnId,
                    queuedId,
                    0,
                    Instant.now(clock)));
        });
    }

    @Override
    public void sendMessage(String reviewId, long expectedTaskVersion, String message, String idempotencyKey, boolean queued)
    {
        locked(reviewId, () -> {
            TaskId taskId = TaskId.valueOf(requireOpen(reviewId).taskId());
            if (queued) {
                turns.enqueue(new QueueAgentInputCommand(taskId, expectedTaskVersion, message, ImmutableList.of(), idempotencyKey));
            }
            else {
                turns.start(new StartAgentTurnCommand(taskId, expectedTaskVersion, message, ImmutableList.of(), idempotencyKey));
            }
            return null;
        });
    }

    @Override
    public ReviewFinding resolveFinding(String reviewId, String findingId, long expectedVersion, String reason)
    {
        return locked(reviewId, () -> {
            ReviewFinding finding = requireFinding(requireReview(reviewId), findingId);
            if (finding.version() != expectedVersion) {
                throw new ReviewConflictException("The finding changed; reload before resolving");
            }
            if (finding.state() == ReviewFinding.State.WITHDRAWN) {
                throw new ReviewConflictException("The agent withdrew this finding");
            }
            return store.updateFinding(finding.withState(ReviewFinding.State.RESOLVED, Optional.of(reason == null ? "" : reason.strip()), Instant.now(clock)));
        });
    }

    @Override
    public ReviewFinding reopenFinding(String reviewId, String findingId, long expectedVersion)
    {
        return locked(reviewId, () -> {
            ReviewFinding finding = requireFinding(requireReview(reviewId), findingId);
            if (finding.version() != expectedVersion) {
                throw new ReviewConflictException("The finding changed; reload before reopening");
            }
            if (finding.state() != ReviewFinding.State.RESOLVED) {
                throw new ReviewConflictException("Only a locally resolved finding can be reopened");
            }
            return store.updateFinding(finding.withState(ReviewFinding.State.OPEN, Optional.empty(), Instant.now(clock)));
        });
    }

    @Override
    public AgentTask task(String reviewId)
    {
        return tasks.get(TaskId.valueOf(requireReview(reviewId).taskId()));
    }

    @Override
    public void reconcile(String reviewId)
    {
        locked(reviewId, () -> {
            Optional<AgentReview> stored = store.findReview(reviewId);
            if (stored.isEmpty() || stored.orElseThrow().state() == AgentReview.State.CLOSED) {
                return null;
            }
            AgentReview review = stored.orElseThrow();
            AgentTask task = tasks.get(TaskId.valueOf(review.taskId()));
            Optional<ReviewRun> active = latestRun(reviewId).filter(ReviewRun::active);
            if (active.isPresent()) {
                review = settle(review, active.orElseThrow(), task);
            }
            if (task.state() == TaskState.FAILED) {
                latestRun(reviewId).filter(ReviewRun::active).ifPresent(run -> store.updateRun(run.ended(ReviewRun.State.FAILED, task.terminalReason(), Instant.now(clock))));
                if (!review.followUpPaused()) {
                    store.updateReview(review.withFollowUpPaused(true, Instant.now(clock)));
                }
                return null;
            }
            return null;
        });
    }

    @Override
    public void observeHead(String repo, int number, String headSha)
    {
        String repository = normalize(repo);
        store.listOpenReviews().stream()
                .filter(review -> review.repo().equals(repository) && review.number() == number)
                .forEach(review -> {
                    if (!review.observedHeadSha().equals(Optional.of(headSha))) {
                        locked(review.id(), () -> {
                            store.findReview(review.id()).filter(current -> current.state() == AgentReview.State.OPEN)
                                    .ifPresent(current -> store.updateReview(current.withObservedHead(headSha, Instant.now(clock))));
                            return null;
                        });
                    }
                });
    }

    /** Applies only the current assignment's accepted turn after its runtime has stopped. */
    private AgentReview settle(AgentReview review, ReviewRun run, AgentTask task)
    {
        if (run.currentStep().isEmpty()) {
            return incomplete(review, run, "The review has no current assignment; start a new review run");
        }
        ReviewStep step = run.currentStep().orElseThrow();
        Optional<AgentTurn> accepted = turns.findTurnByIdempotencyKey(task.id(), step.key(run.id()));
        if (accepted.isEmpty()) {
            if (idle(task) && !review.followUpPaused()) {
                startFollowup(run, task);
            }
            return review;
        }
        AgentTurn turn = turns.get(accepted.orElseThrow().id());
        // A provider can accept a turn before its association is saved. The assignment key remains authoritative.
        if (!run.turnIds().contains(turn.id().toString())) {
            run = store.updateRun(run.withTurn(turn.id().toString(), Instant.now(clock)));
        }
        if (!turn.state().terminal() || !idle(task)) {
            return review;
        }
        Instant now = Instant.now(clock);
        switch (turn.state()) {
            case COMPLETED -> {
                CompletionAction action = completionAction(run);
                // Pausing prevents continuation, but still records an already reported result.
                if (review.followUpPaused() && action != ACCEPT && action != INCOMPLETE_COVERAGE) {
                    return review;
                }
                return switch (action) {
                    case VALIDATE, COLLECT_EVIDENCE -> {
                        ReviewStep.Kind kind = action == VALIDATE ? ReviewStep.Kind.VALIDATE : ReviewStep.Kind.COLLECT_EVIDENCE;
                        ReviewRun next = store.updateRun(run.withStep(step.next(kind), now));
                        startFollowup(next, task);
                        yield review;
                    }
                    case REPAIR -> {
                        repairAssignment(run, task);
                        yield review;
                    }
                    case ACCEPT -> {
                        AgentReview advanced = review.withLatestCompletedRun(run.id(), now);
                        ReviewRun completed = run.withCoverage(step.validation().orElseThrow().coverage(), now);
                        store.acceptRun(advanced, completed.ended(ReviewRun.State.ACCEPTED, Optional.empty(), now));
                        yield store.findReview(review.id()).orElseThrow();
                    }
                    case INCOMPLETE_COVERAGE -> incomplete(review, run, step.validation().filter(ReviewValidation::needsEvidence).isPresent()
                            ? "The evidence follow-up limit was exhausted; candidates remain unresolved"
                            : "The review has unresolved candidates or incomplete coverage");
                    case INCOMPLETE_REPORT -> incomplete(review, run, "The agent stopped before saving a complete review report");
                };
            }
            case CANCELLED -> {
                store.updateRun(run.ended(ReviewRun.State.STOPPED, Optional.of("Stopped by the user"), now));
                return store.updateReview(review.withFollowUpPaused(true, now));
            }
            default -> {
                // A fresh private continuation may recover known delivery, never an uncertain send or a user's stop.
                if (!review.followUpPaused() && turn.cancellationRequestedAt().isEmpty() && turn.failureKind().isEmpty() && !step.repair()
                        && (turn.knownUnsent() || turn.startedAt().isEmpty() || turn.providerTurnReference().isPresent())) {
                    repairAssignment(run, task);
                    return review;
                }
                store.updateRun(
                        run.ended(ReviewRun.State.FAILED, turn.terminalReason().or(() -> Optional.of("The provider turn " + turn.state().name().toLowerCase(Locale.ROOT))), now));
                return store.updateReview(review.withFollowUpPaused(true, now));
            }
        }
    }

    private CompletionAction completionAction(ReviewRun run)
    {
        ReviewStep step = run.currentStep().orElseThrow();
        if (step.validation().isPresent()) {
            ReviewValidation validation = step.validation().orElseThrow();
            if (!currentValidation(run, validation)) {
                return step.repair() ? INCOMPLETE_REPORT : REPAIR;
            }
            if (validation.needsEvidence() && run.steps().stream().filter(value -> value.kind() == ReviewStep.Kind.COLLECT_EVIDENCE).count() < 2) {
                return COLLECT_EVIDENCE;
            }
            return validation.coverage().complete() && validation.coverage().omissions().isEmpty() ? ACCEPT : INCOMPLETE_COVERAGE;
        }
        if (run.coverage().filter(coverage -> !coverage.complete() || !coverage.omissions().isEmpty()).isPresent()) {
            return INCOMPLETE_COVERAGE;
        }
        if (step.investigation().isPresent() && run.hasCompleteSummary()) {
            return VALIDATE;
        }
        return step.repair() ? INCOMPLETE_REPORT : REPAIR;
    }

    private void repairAssignment(ReviewRun run, AgentTask task)
    {
        ReviewStep step = run.currentStep().orElseThrow();
        // A stale sealed report needs a new identity: replaying its old operation key would restore the stale result.
        boolean stale = step.validation().filter(report -> !currentValidation(run, report)).isPresent()
                || (step.investigation().isPresent() && !run.hasCompleteSummary());
        ReviewRun repairing = stale
                ? run.withStep(step.next(step.kind()).forRepair(), Instant.now(clock))
                : run.replaceCurrentStep(step.forRepair(), Instant.now(clock));
        startFollowup(store.updateRun(repairing.withCompletionRepairUsed(Instant.now(clock))), task);
    }

    /** Tool admission checks again at acceptance, since a retained finding can be edited between writes. */
    private boolean currentValidation(ReviewRun run, ReviewValidation validation)
    {
        Optional<ReviewStep> investigation = run.steps().stream().filter(step -> step.investigation().isPresent()).reduce((first, second) -> second);
        if (!run.hasCompleteSummary() || investigation.isEmpty() || investigation.orElseThrow().number() != validation.investigationStep()) {
            return false;
        }
        int existingCandidates = investigation.orElseThrow().investigation().orElseThrow().candidates().size();
        int candidates = existingCandidates + validation.newCandidates().size();
        if (validation.candidates().size() != candidates || validation.candidates().stream().anyMatch(candidate -> candidate.candidate() > candidates)) {
            return false;
        }
        for (ReviewCandidateValidation candidate : validation.candidates()) {
            if (candidate.candidate() > existingCandidates && candidate.outcome() != ReviewCandidateValidation.Outcome.NEEDS_EVIDENCE) {
                return false;
            }
            if (!run.evidence().keySet().containsAll(candidate.evidenceRefs())) {
                return false;
            }
            if (candidate.outcome() == ReviewCandidateValidation.Outcome.SUPPORTED) {
                Optional<ReviewFinding> finding = candidate.findingId().flatMap(store::findFinding);
                if (finding.isEmpty() || !finding.orElseThrow().reviewId().equals(run.reviewId()) || finding.orElseThrow().state() == ReviewFinding.State.WITHDRAWN
                        || finding.orElseThrow().outdated() || !finding.orElseThrow().validatedHeadSha().equals(run.headSha())
                        || !candidate.findingRevision().equals(Optional.of(finding.orElseThrow().revision()))
                        || !candidate.evidenceRefs().containsAll(finding.orElseThrow().evidence().stream().map(ReviewEvidence::ref).toList())) {
                    return false;
                }
            }
        }
        return true;
    }

    private AgentReview incomplete(AgentReview review, ReviewRun run, String reason)
    {
        Instant now = Instant.now(clock);
        ReviewRun recorded = run.currentStep().flatMap(ReviewStep::validation).map(report -> run.withCoverage(report.coverage(), now)).orElse(run);
        store.updateRun(recorded.ended(ReviewRun.State.INCOMPLETE, Optional.of(reason), now));
        return store.updateReview(review.withFollowUpPaused(true, now));
    }

    private void startFollowup(ReviewRun run, AgentTask task)
    {
        String key = run.currentStep().orElseThrow().key(run.id());
        AgentTurn accepted;
        try {
            accepted = startAssignment(run, new StartAgentTurnCommand(task.id(), ReviewPrompts.stepRequest(run), ImmutableList.of(), key));
        }
        catch (RuntimeException failure) {
            if (turns.findTurnByIdempotencyKey(task.id(), key).isEmpty()) {
                Instant now = Instant.now(clock);
                store.updateRun(store.findRun(run.id()).orElseThrow().ended(ReviewRun.State.FAILED, Optional.of("Cannot continue the review: " + failure.getMessage()), now));
                store.updateReview(requireReview(run.reviewId()).withFollowUpPaused(true, now));
            }
            throw failure;
        }
        ReviewRun current = store.findRun(run.id()).orElseThrow();
        if (!current.turnIds().contains(accepted.id().toString())) {
            store.updateRun(current.withTurn(accepted.id().toString(), Instant.now(clock)));
        }
    }

    private AgentTurn startAssignment(ReviewRun run, StartAgentTurnCommand command)
    {
        for (int attempt = 0; ; attempt++) {
            try {
                return turns.findTurnByIdempotencyKey(command.taskId(), command.idempotencyKey()).orElseGet(() -> turns.start(command));
            }
            catch (RuntimeException failure) {
                Optional<AgentTurn> accepted = turns.findTurnByIdempotencyKey(command.taskId(), command.idempotencyKey());
                if (accepted.isPresent()) {
                    return accepted.orElseThrow();
                }
                // No accepted turn means no later lifecycle event will drive recovery. Retry this unsent launch once here.
                if (attempt != 0 || requireReview(run.reviewId()).followUpPaused() || !idle(tasks.get(command.taskId()))
                        || failure instanceof AgentConflictException
                        || (failure instanceof AgentProviderUnavailableException unavailable && unavailable.status().readiness() != TEMPORARILY_UNAVAILABLE)) {
                    throw failure;
                }
                log.warn("Review {} could not launch its assignment; retrying the unsent request: {}", run.reviewId(), failure.toString());
            }
        }
    }

    /**
     * Starts a new-head review or retries a failed run only when the user chooses Update or Resume.
     * Synchronization and experiment cleanup hold the same mutation claim as ordinary task writers.
     */
    private void followHead(AgentReview review, boolean retry)
    {
        List<ReviewRun> runs = store.listRuns(review.id());
        Optional<ReviewRun> latest = runs.isEmpty() ? Optional.empty() : Optional.of(runs.getLast());
        if (latest.map(ReviewRun::active).orElse(false) || review.observedHeadSha().isEmpty()) {
            return;
        }
        String head = review.observedHeadSha().orElseThrow();
        boolean newHead = latest.map(run -> !run.headSha().equals(head)).orElse(true);
        if (!newHead && !retry) {
            return;
        }
        TaskId taskId = TaskId.valueOf(review.taskId());
        AgentTask task = tasks.get(taskId);
        if (!idle(task) || !turns.queued(taskId).isEmpty()) {
            return;
        }
        WorkspaceRepositoryContext workspace = workspaces.requireReadyRepository(review.workspaceId());
        Snapshot snapshot = snapshot(workspace, review.repo(), review.number());
        if (!snapshot.headSha().equals(head)) {
            store.updateReview(review.withObservedHead(snapshot.headSha(), Instant.now(clock)));
        }
        Optional<ReviewRun> previous = runs.stream()
                .filter(run -> run.state() == ReviewRun.State.ACCEPTED && run.hasCompleteSummary()
                        && run.coverage().filter(coverage -> coverage.complete() && coverage.omissions().isEmpty()).isPresent())
                .reduce((first, second) -> second);
        // Compared with the fetched head: a retry can find a newer head than the observed one, and every run must sit where the worktree does.
        try {
            turns.synchronizeWorktree(taskId, task.version(),
                    claimed -> moveWorktree(claimed, latest.map(ReviewRun::headSha), snapshot.headSha(), workspace));
        }
        catch (ReviewConflictException | AgentConflictException refusal) {
            store.updateReview(store.findReview(review.id()).orElseThrow().withFollowUpPaused(true, Instant.now(clock)));
            throw new ReviewConflictException(refusal.getMessage(), refusal);
        }
        List<ReviewFinding> findings = store.listFindings(review.id());
        startRun(store.findReview(review.id()).orElseThrow(), snapshot, workspace, runs.size() + 1, previous, findings);
    }

    /**
     * Fast-forwards the generated branch to a descendant head. When the pull request history was
     * rewritten, a user action realigns the worktree from the last reviewed head. Experiments are
     * temporarily stashed and restored at the new head; unrelated local commits are refused.
     * Ignored build output survives both moves, and a head that tracks one of its paths is refused.
     */
    private void moveWorktree(AgentTask task, Optional<String> reviewedHead, String headSha, WorkspaceRepositoryContext workspace)
    {
        TaskWorktree worktree = task.taskWorktree().orElseThrow(() -> new ReviewConflictException("The review task has no managed worktree"));
        WorktreeSnapshot snapshot = worktrees.inspect(worktree);
        if (snapshot.gitOperationInProgress()) {
            throw new ReviewConflictException("The review worktree has an open Git operation; finish it before updating the review");
        }
        String expectedHead = reviewedHead.orElse(worktree.startCommit());
        if (!snapshot.head().equals(expectedHead) && !snapshot.head().equals(headSha)) {
            throw new ReviewConflictException("The review worktree has local commits on top of the reviewed head; remove them before updating the review");
        }
        // Hooks stay off so a hooks path inside the tree cannot run the pull request's own scripts.
        List<String> move;
        if (snapshot.head().equals(headSha) || git.run(workspace.repository(), "merge-base", "--is-ancestor", snapshot.head(), headSha).successful()) {
            move = ImmutableList.of("-c", "core.hooksPath=/dev/null", "merge", "--ff-only", "--no-overwrite-ignore", headSha);
        }
        else {
            // Unlike reset --keep, checkout refuses to overwrite ignored files, not only files that became dirty or untracked after the inspection.
            move = ImmutableList.of("-c", "core.hooksPath=/dev/null", "checkout", "--no-overwrite-ignore", "--no-recurse-submodules", "-B", worktree.generatedBranch(), headSha);
        }
        try {
            experiments.synchronize(worktree, expectedHead, headSha, () -> {
                if (snapshot.head().equals(headSha)) {
                    return;
                }
                GitCommandRunner.Result moved = git.run(worktree.canonicalWorktree(), move);
                if (!moved.successful() || !worktrees.inspect(worktree).head().equals(headSha)) {
                    throw new ReviewConflictException("The review worktree could not be moved to " + ReviewPrompts.shortSha(headSha) + ": " + moved.output().strip());
                }
            }).ifPresent(message -> log.info("Review task {} synchronized: {}", task.id(), message));
        }
        catch (RuntimeException failure) {
            throw new ReviewConflictException(failure.getMessage(), failure);
        }
    }

    private void startRun(
            AgentReview review,
            Snapshot snapshot,
            WorkspaceRepositoryContext workspace,
            int ordinal,
            Optional<ReviewRun> previous,
            List<ReviewFinding> findings)
    {
        Instant now = Instant.now(clock);
        boolean incremental = previous.isPresent()
                && previous.orElseThrow().baseRef().equals(snapshot.baseRef())
                && previous.orElseThrow().baseSha().equals(snapshot.baseSha())
                && previous.orElseThrow().mergeBaseSha().equals(snapshot.mergeBaseSha())
                && git.run(workspace.repository(), "merge-base", "--is-ancestor", previous.orElseThrow().headSha(), snapshot.headSha()).successful();
        ReviewRun run = store.insertRun(new ReviewRun(
                UUID.randomUUID().toString(),
                review.id(),
                ordinal,
                incremental ? ReviewRun.Scope.INCREMENTAL : ReviewRun.Scope.FULL,
                snapshot.baseRef(),
                snapshot.baseSha(),
                snapshot.mergeBaseSha(),
                snapshot.headSha(),
                previous.map(ReviewRun::headSha),
                snapshot.commits(),
                snapshot.diff(),
                ImmutableList.of(),
                ReviewRun.State.RUNNING,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ImmutableList.of(ReviewStep.initial()),
                false,
                ImmutableMap.of(),
                ImmutableMap.of(),
                Optional.empty(),
                0,
                now,
                now,
                Optional.empty()));
        AgentTurn turn;
        try {
            ReviewDiffAnchors anchors = ReviewDiffAnchors.of(snapshot.diff());
            for (ReviewFinding finding : findings) {
                if (finding.state() == ReviewFinding.State.WITHDRAWN || finding.validatedHeadSha().equals(snapshot.headSha())) {
                    continue;
                }
                Optional<ReviewAnchor> mapped = anchors.remap(finding.anchor());
                store.updateFinding(mapped.map(anchor -> relocated(finding, anchor, snapshot.headSha(), now))
                        .orElseGet(() -> finding.withMapping(finding.validatedHeadSha(), true, now)));
            }
            String login = credentials.resolveGitHubCredential().profile().login();
            turn = startAssignment(run, new StartAgentTurnCommand(
                    TaskId.valueOf(review.taskId()),
                    ReviewPrompts.reviewRequest(review, run, previous, findings, login),
                    ImmutableList.of(),
                    run.currentStep().orElseThrow().key(run.id())));
        }
        catch (RuntimeException failure) {
            if (turns.findTurnByIdempotencyKey(TaskId.valueOf(review.taskId()), run.currentStep().orElseThrow().key(run.id())).isEmpty()) {
                store.updateRun(run.ended(ReviewRun.State.FAILED, Optional.of("Cannot start the review turn: " + failure.getMessage()), Instant.now(clock)));
                store.updateReview(store.findReview(review.id()).orElseThrow().withFollowUpPaused(true, Instant.now(clock)));
            }
            throw failure;
        }
        // If this write fails after turn acceptance, reconciliation reattaches that same request.
        store.updateRun(run.withTurn(turn.id().toString(), Instant.now(clock)));
    }

    private static ReviewFinding relocated(ReviewFinding finding, ReviewAnchor anchor, String headSha, Instant now)
    {
        return new ReviewFinding(
                finding.id(),
                finding.reviewId(),
                finding.runId(),
                finding.ordinal(),
                finding.title(),
                finding.body(),
                finding.severity(),
                anchor,
                finding.evidence(),
                finding.revision(),
                finding.state(),
                finding.stateReason(),
                headSha,
                false,
                finding.version(),
                finding.createdAt(),
                now);
    }

    /**
     * Freezes the pull request at its current remote head: the exact head and base are fetched
     * through the pull request's own refs, so a fork head needs no agent-supplied remote, and the
     * publication diff is the one GitHub reports for that head.
     */
    private Snapshot snapshot(WorkspaceRepositoryContext workspace, String repository, int number)
    {
        PullRequestDetail detail = pullRequests.getPullRequestDetail(repository, number);
        String headSha = pullRequests.findHeadSha(repository, number)
                .orElseThrow(() -> new ReviewConflictException("The pull request head is unknown; refresh the pull request"));
        String baseRef = Optional.ofNullable(detail.baseRef()).orElseThrow(() -> new ReviewConflictException("The pull request base branch is unknown"));
        String remote = workspace.selectedRepository().equals(workspace.monitoredRepository()) ? "origin" : workspace.sourceRemote();
        Path repositoryPath = workspace.repository();
        String headRef = "refs/bytequay/review/" + number;
        GitCommandRunner.Result fetched = git.run(repositoryPath, ImmutableList.of(
                "fetch",
                "--no-tags",
                "--no-recurse-submodules",
                "--no-write-fetch-head",
                remote,
                "+refs/pull/" + number + "/head:" + headRef,
                "+refs/heads/" + baseRef + ":refs/remotes/" + remote + "/" + baseRef));
        if (!fetched.successful()) {
            throw new ReviewConflictException("Cannot fetch pull request " + repository + "#" + number + ": " + fetched.output().strip());
        }
        String fetchedHead = revParse(repositoryPath, headRef);
        if (!fetchedHead.equals(headSha)) {
            // The head moved between GitHub's answer and the fetch; the fetched commit is the newer truth.
            refresh.refreshAllDetail(repository, number);
            String refreshed = pullRequests.findHeadSha(repository, number).orElse(fetchedHead);
            if (!refreshed.equals(fetchedHead)) {
                throw new ReviewConflictException("The pull request head moved while the review was starting; try again");
            }
            headSha = fetchedHead;
        }
        String baseSha = revParse(repositoryPath, "refs/remotes/" + remote + "/" + baseRef);
        String mergeBase = revParse(repositoryPath, headSha, baseSha);
        GitCommandRunner.Result log = git.run(repositoryPath, "log", "--reverse", "--format=%H%x1f%an%x1f%s", mergeBase + ".." + headSha);
        if (!log.successful()) {
            throw new ReviewConflictException("Cannot list the pull request commits: " + log.output().strip());
        }
        List<ReviewCommit> commits = log.output().lines()
                .filter(line -> !line.isBlank())
                .map(line -> line.split("\\u001f", 3))
                .map(parts -> new ReviewCommit(parts[0], parts.length > 2 ? parts[2] : "", parts.length > 1 ? parts[1] : ""))
                .collect(toImmutableList());
        List<DiffFile> diff = pullRequests.getPullRequestDiffFiles(repository, number, null, null);
        return new Snapshot(OptionalLong.of(detail.id()), headSha, baseRef, baseSha, mergeBase, commits, diff);
    }

    private String revParse(Path repository, String... arguments)
    {
        GitCommandRunner.Result result = arguments.length == 1
                ? git.run(repository, "rev-parse", "--verify", arguments[0] + "^{commit}")
                : git.run(repository, "merge-base", arguments[0], arguments[1]);
        if (!result.successful() || result.output().isBlank()) {
            throw new ReviewConflictException("Cannot resolve " + String.join(" ", arguments) + " in the workspace repository");
        }
        return result.output().strip();
    }

    /**
     * The model this workspace's reviews run with: its Review override, else
     * the global Review value, else Development. The setting is frozen into
     * the task at review creation, so a later change reaches new reviews only.
     */
    private ModelSelection selectModel(String workspaceId)
    {
        AgentModelSetting setting = models.agentModel(AgentModelType.REVIEW, Optional.ofNullable(workspaceId));
        return new ModelSelection(
                new AgentLaneId(setting.configId()),
                new AgentTaskDefaults(Optional.of(setting.model()), setting.effort(), AgentPermissionPolicy.AUTO, Optional.empty()));
    }

    private AgentReviewProjection projection(String reviewId)
    {
        AgentReview review = requireReview(reviewId);
        List<ReviewRun> runs = store.listRuns(reviewId);
        TaskId taskId = TaskId.valueOf(review.taskId());
        AgentTask task = tasks.get(taskId);
        Optional<AgentTurn> active = activeTurn(task);
        AgentCapabilities capabilities = drivers.require(task.laneId()).capabilities();
        int pendingApprovals = active.map(turn -> approvals.listPending(turn.id()).size()).orElse(0);
        List<String> queued = task.state() == TaskState.OPEN
                ? turns.queued(taskId).stream().map(QueuedAgentInput::id).map(Object::toString).collect(toImmutableList())
                : ImmutableList.of();
        return new AgentReviewProjection(
                review,
                runs.stream().map(AgentReviewProjection.RunView::of).collect(toImmutableList()),
                store.listFindings(reviewId),
                store.listMessages(reviewId).stream().map(this::messageView).collect(toImmutableList()),
                new AgentReviewProjection.TaskView(
                        task.id().toString(),
                        review.workspaceId(),
                        task.state().name(),
                        task.version(),
                        task.laneId().value(),
                        drivers.require(task.laneId()).label(),
                        task.defaults().model(),
                        task.worktreeMutationClaim().map(claim -> claim.kind().name()),
                        capabilities.steering(),
                        false),
                active.map(turn -> new AgentReviewProjection.TurnView(turn.id().toString(), turn.state().name(), turn.startedAt(), turn.cancellationRequestedAt())),
                queued,
                pendingApprovals,
                runs.stream().flatMap(run -> run.turnIds().stream()).collect(toImmutableList()),
                drafts.find(review.repo(), review.number()),
                pullRequests.findStored(review.repo(), review.number()));
    }

    private AgentReviewProjection.MessageView messageView(ReviewMessage message)
    {
        // Delivery identity must stay readable after the generated prompt blob expires.
        Optional<QueuedAgentInput> queued = message.queuedInputId().map(QueuedInputId::valueOf).flatMap(queueStore::find);
        return new AgentReviewProjection.MessageView(message,
                queued.flatMap(QueuedAgentInput::turnId).map(Object::toString).or(message::turnId),
                queued.flatMap(QueuedAgentInput::messageEventId).map(Object::toString));
    }

    /** The turn holding the task's worktree claim, if one is accepted or running. */
    private Optional<AgentTurn> activeTurn(AgentTask task)
    {
        return task.worktreeMutationClaim()
                .filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.TURN)
                .map(claim -> turns.get(TurnId.valueOf(claim.ownerId())))
                .filter(turn -> turn.state().active());
    }

    private boolean idle(AgentTask task)
    {
        return task.state() == TaskState.OPEN && task.worktreeMutationClaim().isEmpty();
    }

    private Optional<ReviewRun> latestRun(String reviewId)
    {
        List<ReviewRun> runs = store.listRuns(reviewId);
        return runs.isEmpty() ? Optional.empty() : Optional.of(runs.getLast());
    }

    private AgentReview requireReview(String reviewId)
    {
        return store.findReview(reviewId).orElseThrow(() -> new ReviewNotFoundException("Review not found: " + reviewId));
    }

    private AgentReview requireOpen(String reviewId)
    {
        AgentReview review = requireReview(reviewId);
        if (review.state() != AgentReview.State.OPEN) {
            throw new ReviewConflictException("The review has ended");
        }
        return review;
    }

    private ReviewFinding requireFinding(AgentReview review, String findingId)
    {
        return store.findFinding(findingId)
                .filter(finding -> finding.reviewId().equals(review.id()))
                .orElseThrow(() -> new ReviewNotFoundException("Finding not found: " + findingId));
    }

    private <T> T locked(String reviewId, Supplier<T> action)
    {
        Lock lock = locks.lock(reviewId);
        lock.lock();
        try {
            return action.get();
        }
        finally {
            lock.unlock();
        }
    }

    static String normalize(String repo)
    {
        return requireNonNull(repo, "repo is null").toLowerCase(Locale.ROOT);
    }

    enum CompletionAction
    {
        VALIDATE,
        COLLECT_EVIDENCE,
        REPAIR,
        ACCEPT,
        INCOMPLETE_COVERAGE,
        INCOMPLETE_REPORT
    }

    private record ModelSelection(AgentLaneId laneId, AgentTaskDefaults defaults) {}

    private record Snapshot(OptionalLong pullRequestId, String headSha, String baseRef, String baseSha, String mergeBaseSha, List<ReviewCommit> commits, List<DiffFile> diff) {}
}
