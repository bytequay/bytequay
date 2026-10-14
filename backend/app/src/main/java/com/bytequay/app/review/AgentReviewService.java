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
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskBranches;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.UpdateAgentTaskDefaultsCommand;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderUnavailableException;
import com.bytequay.app.agent.TaskInput;
import com.bytequay.app.agent.WorkflowAgentTasks;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.ReviewExperiments;
import com.bytequay.app.review.AgentReviewProjection.WorktreeSyncView;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestTask;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewCandidate;
import com.bytequay.core.review.ReviewCandidateValidation;
import com.bytequay.core.review.ReviewCommit;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewDraftStore;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.bytequay.core.review.ReviewInvestigation;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;
import com.bytequay.core.review.ReviewValidation;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.bytequay.agent.spi.AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE;
import static com.bytequay.app.review.AgentReviewService.CompletionAction.ACCEPT;
import static com.bytequay.app.review.AgentReviewService.CompletionAction.COLLECT_EVIDENCE;
import static com.bytequay.app.review.AgentReviewService.CompletionAction.INCOMPLETE_COVERAGE;
import static com.bytequay.app.review.AgentReviewService.CompletionAction.INCOMPLETE_REPORT;
import static com.bytequay.app.review.AgentReviewService.CompletionAction.REPAIR;
import static com.bytequay.app.review.AgentReviewService.CompletionAction.VALIDATE;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * The one application service that owns private pull-request reviews: their agent task, runs,
 * summaries, findings and discussions. Publication is the user's and lives in
 * {@link ReviewPublicationService}; task execution stays in the agent service.
 */
@Service
public class AgentReviewService
{
    private static final Logger log = LoggerFactory.getLogger(AgentReviewService.class);
    private static final List<String> GIT_OPERATION_PATHS = ImmutableList.of(
            "MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "REBASE_HEAD", "BISECT_LOG", "BISECT_START", "rebase-merge", "rebase-apply");

    private final Map<String, SyncFailure> syncFailures = new ConcurrentHashMap<>();

    private final AgentReviewStore store;
    private final ReviewDraftStore drafts;
    private final ReviewPublicationService publications;
    private final AgentTaskService tasks;
    private final AgentEventService events;
    private final AgentTurnService turns;
    private final AgentTurnStore turnStore;
    private final QueuedInputStore queueStore;
    private final AgentCompletionService completions;
    private final AgentApprovalService approvals;
    private final AgentDriverRegistry drivers;
    private final ModelSettingsService models;
    private final WorkspaceRepositoryService workspaces;
    private final WorktreeService worktrees;
    private final GitCommandRunner git;
    private final WorktreeDiffReader browse;
    private final PullRequestTaskStore assignments;
    private final PullRequestStore pullRequestStore;
    private final ReviewExperiments experiments;
    private final PullRequestService pullRequests;
    private final PullRequestRefreshService refresh;
    private final CredentialService credentials;
    private final ReviewTaskObserver observer;
    private final ReviewSweep sweeps;
    private final Clock clock;
    private final Striped<Lock> startLocks = Striped.lock(32);
    private final ReviewLocks locks;

    public AgentReviewService(
            AgentReviewStore store,
            ReviewDraftStore drafts,
            ReviewPublicationService publications,
            AgentTaskService tasks,
            AgentEventService events,
            AgentTurnService turns,
            AgentTurnStore turnStore,
            QueuedInputStore queueStore,
            AgentCompletionService completions,
            AgentApprovalService approvals,
            AgentDriverRegistry drivers,
            ModelSettingsService models,
            WorkspaceRepositoryService workspaces,
            WorktreeService worktrees,
            GitCommandRunner git,
            WorktreeDiffReader browse,
            PullRequestTaskStore assignments,
            PullRequestStore pullRequestStore,
            ReviewExperiments experiments,
            PullRequestService pullRequests,
            PullRequestRefreshService refresh,
            CredentialService credentials,
            ReviewTaskObserver observer,
            ReviewSweep sweeps,
            ReviewLocks locks,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.drafts = requireNonNull(drafts, "drafts is null");
        this.publications = requireNonNull(publications, "publications is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.events = requireNonNull(events, "events is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.queueStore = requireNonNull(queueStore, "queueStore is null");
        this.completions = requireNonNull(completions, "completions is null");
        this.approvals = requireNonNull(approvals, "approvals is null");
        this.drivers = requireNonNull(drivers, "drivers is null");
        this.models = requireNonNull(models, "models is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.git = requireNonNull(git, "git is null");
        this.browse = requireNonNull(browse, "browse is null");
        this.assignments = requireNonNull(assignments, "assignments is null");
        this.pullRequestStore = requireNonNull(pullRequestStore, "pullRequestStore is null");
        this.experiments = requireNonNull(experiments, "experiments is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.refresh = requireNonNull(refresh, "refresh is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.observer = requireNonNull(observer, "observer is null");
        this.sweeps = requireNonNull(sweeps, "sweeps is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.locks = requireNonNull(locks, "locks is null");
    }

    /** The open review of a pull request, else its most recent ended one, else empty. */
    public Optional<AgentReviewProjection> find(String workspaceId, String repo, int number)
    {
        return store.findOpenReview(workspaceId, normalize(repo), number).map(review -> projection(review.id()));
    }

    /** The newest review linked to this workspace's development task, including retained ended reviews. */
    public Optional<AgentReviewProjection> findForTask(String workspaceId, String sourceTaskId)
    {
        tasks.getInteractive(workspaceId, TaskId.valueOf(sourceTaskId));
        return store.listReviews(workspaceId).stream().filter(review -> review.sourceTaskId().equals(Optional.of(sourceTaskId)))
                .findFirst().map(review -> projection(review.id()));
    }

    /** Starts a separate reviewer for the development task's committed working-copy revision. */
    public AgentReviewProjection startForTask(String workspaceId, String sourceTaskId)
    {
        AgentTask source = tasks.getInteractive(workspaceId, TaskId.valueOf(sourceTaskId));
        Lock lock = startLocks.get("task:" + sourceTaskId);
        lock.lock();
        try {
            Optional<AgentReview> existing = store.listReviews(workspaceId).stream()
                    .filter(review -> review.sourceTaskId().equals(Optional.of(sourceTaskId)) && review.state() == AgentReview.State.OPEN).findFirst();
            if (existing.isPresent()) {
                return projection(existing.orElseThrow().id());
            }
            WorkspaceRepositoryContext workspace = workspaces.requireReadyRepository(workspaceId);
            WorkflowAgentTasks.requireSelectedRepository(WorkflowAgentTasks.repositoryIdentity(workspace.selectedRepository()), source.repository(), ReviewConflictException::new);
            String repository = normalize(workspace.monitoredRepository().fullName());
            OptionalInt number = sourcePullRequest(source, repository);
            ModelSelection selection = selectModel(workspaceId);
            WorkflowAgentTasks.requireReady(drivers, selection.laneId(), AgentPermissionPolicy.AUTO);
            Snapshot snapshot = localSnapshot(source, workspace, number);
            return createReview(workspace, selection, snapshot, "Review: " + source.title(), number, Optional.of(sourceTaskId), Optional.empty(), true);
        }
        finally {
            lock.unlock();
        }
    }

    public AgentReviewProjection get(String reviewId)
    {
        return projection(reviewId);
    }

    /** Lists open and closed workspace reviews without inspecting working copies or refreshing GitHub. Replays are left out; newest updates come first. */
    public List<AgentReviewProjection> list(String workspaceId)
    {
        return store.listReviews(workspaceId).stream().filter(review -> review.replay().isEmpty()).map(review -> projection(review, false)).collect(toImmutableList());
    }

    /** Starts a review, or returns the open one: one review per workspace and pull request. */
    public AgentReviewProjection start(String workspaceId, String repo, int number)
    {
        return prepare(workspaceId, repo, number, true);
    }

    /** Prepares the PR working copy for exploration without starting an agent review. */
    public AgentReviewProjection prepare(String workspaceId, String repo, int number)
    {
        return prepare(workspaceId, repo, number, false);
    }

    private AgentReviewProjection prepare(String workspaceId, String repo, int number, boolean startReview)
    {
        String repository = normalize(repo);
        Lock lock = startLocks.get(workspaceId + "/" + repository + "#" + number);
        lock.lock();
        try {
            WorkspaceRepositoryContext workspace = workspaces.requireReadyRepository(workspaceId);
            String monitored = (workspace.monitoredRepository().owner() + "/" + workspace.monitoredRepository().repo()).toLowerCase(Locale.ROOT);
            if (!monitored.equals(repository)) {
                throw new ReviewInvalidRequestException("Pull request " + repository + "#" + number + " does not belong to this workspace");
            }
            Optional<AgentReview> existing = store.findOpenReview(workspaceId, repository, number);
            if (existing.isPresent()) {
                String reviewId = existing.orElseThrow().id();
                return locked(reviewId, () -> startReview && store.listRuns(reviewId).isEmpty() ? resume(reviewId) : projection(reviewId));
            }
            ModelSelection selection = selectModel(workspaceId);
            if (startReview) {
                WorkflowAgentTasks.requireReady(drivers, selection.laneId(), AgentPermissionPolicy.AUTO);
            }
            Snapshot snapshot = snapshot(workspace, repository, number);
            return createReview(workspace, selection, snapshot, "Review " + repository + "#" + number, OptionalInt.of(number),
                    Optional.empty(), Optional.empty(), startReview);
        }
        finally {
            lock.unlock();
        }
    }

    private AgentReview insertReview(AgentReview review)
    {
        try {
            return store.insertReview(review);
        }
        catch (RuntimeException failure) {
            try {
                // Only an unowned startup can be discarded: a committed review must retain its task for retry.
                if (store.findReview(review.id()).isEmpty()) {
                    completions.delete(TaskId.valueOf(review.taskId()));
                }
            }
            catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
                log.warn("Review {} could not discard its unstarted task {} after startup failed", review.id(), review.taskId(), cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * Starts one side of a replay: a review of a merged pull request's change at a past head, with or without repository memory.
     * It is never the pull request's review, so nothing it finds can be published, and it stays at that head.
     */
    public AgentReviewProjection startReplay(String workspaceId, ReplayStart start)
    {
        AgentReview.Replay replay = start.replay();
        WorkspaceRepositoryContext workspace = workspaces.requireReadyRepository(workspaceId);
        ModelSelection selection = selectModel(workspaceId);
        WorkflowAgentTasks.requireReady(drivers, selection.laneId(), AgentPermissionPolicy.AUTO);
        Snapshot snapshot = replaySnapshot(workspace.repository(), start.headSha(), start.baseRef(), start.mergeBaseSha());
        String title = "Replay review #" + replay.number() + (replay.memory() ? " · with memory" : " · without memory");
        return createReview(workspace, selection, snapshot, title, OptionalInt.empty(), Optional.empty(), Optional.of(replay), true);
    }

    private AgentReviewProjection createReview(WorkspaceRepositoryContext workspace, ModelSelection selection, Snapshot snapshot, String title,
            OptionalInt number, Optional<String> sourceTaskId, Optional<AgentReview.Replay> replay, boolean startReview)
    {
        TaskId taskId = TaskId.random();
        String branch = TaskBranches.generate(taskId, credentials.resolveGitHubCredential().profile().login(), title);
        CreateAgentTaskCommand command = new CreateAgentTaskCommand(taskId, workspace.workspaceId(), selection.laneId(),
                WorkflowAgentTasks.repositoryIdentity(workspace.selectedRepository()), snapshot.headSha(), branch, title, selection.defaults());
        AgentTask task = WorkflowAgentTasks.createOrGet(tasks, taskId, () -> startReview ? tasks.create(command) : tasks.prepare(command));
        Instant now = Instant.now(clock);
        String reviewId = UUID.randomUUID().toString();
        return locked(reviewId, () -> {
            AgentReview review = insertReview(new AgentReview(reviewId, workspace.workspaceId(), normalize(workspace.monitoredRepository().fullName()), number,
                    snapshot.pullRequestId(), task.id().toString(), sourceTaskId, AgentReview.State.OPEN, !startReview, Optional.empty(), Optional.of(snapshot.headSha()),
                    Optional.of(snapshot.headSha()), 0, now, now, Optional.empty(), replay));
            observer.watch(review);
            if (startReview) {
                startRun(review, snapshot, workspace, 1, Optional.empty(), ImmutableList.of());
            }
            return projection(review.id());
        });
    }

    /** Saves the composer choice without altering an already accepted turn. */
    public AgentReviewProjection configure(String reviewId, long expectedVersion, AgentLaneId lane, String model, Optional<String> effort, Optional<String> serviceTier)
    {
        return locked(reviewId, () -> {
            AgentReview review = requireOpen(reviewId);
            AgentTask task = tasks.get(TaskId.valueOf(review.taskId()));
            models.validateWorkspaceAccess(lane.value(), Optional.of(review.workspaceId()));
            models.validateSelection(lane.value(), model, effort, serviceTier);
            drivers.checkWorkspaceAccess(lane, task.workspaceId());
            drivers.require(lane);
            tasks.updateDefaults(new UpdateAgentTaskDefaultsCommand(task.id(), expectedVersion,
                    new AgentTaskDefaults(Optional.of(model), effort, AgentPermissionPolicy.AUTO, serviceTier).withLaneId(lane)));
            return projection(reviewId);
        });
    }

    /** Cancels the active run and pauses follow-up; the review and its discussion stay open. */
    public AgentReviewProjection stop(String reviewId)
    {
        return locked(reviewId, () -> {
            AgentReview review = requireOpen(reviewId);
            store.updateReview(review.withFollowUpPaused(true, Instant.now(clock)));
            activeTurn(tasks.get(TaskId.valueOf(review.taskId()))).ifPresent(turn -> turns.cancel(turn.id()));
            sweeps.cancel(reviewId);
            return projection(reviewId);
        });
    }

    /** Lifts the pause and starts a run for the newest observed head when nothing is running. */
    public AgentReviewProjection resume(String reviewId)
    {
        return locked(reviewId, () -> {
            AgentReview review = requireOpen(reviewId);
            requireNoLocalRun(tasks.get(TaskId.valueOf(review.taskId())));
            Optional<ReviewRun> active = latestRun(reviewId).filter(ReviewRun::active);
            boolean explaining = active.filter(ReviewRun::explanationOnly).isPresent();
            if (active.isPresent()) {
                review = settle(review, active.orElseThrow(), tasks.get(TaskId.valueOf(review.taskId())));
            }
            review = store.updateReview(review.withFollowUpPaused(false, Instant.now(clock)));
            Optional<ReviewRun> resumed = latestRun(reviewId).filter(ReviewRun::active);
            if (resumed.isPresent()) {
                review = settle(review, resumed.orElseThrow(), tasks.get(TaskId.valueOf(review.taskId())));
            }
            if (explaining) {
                return projection(reviewId);
            }
            Optional<ReviewRun> latest = latestFindingsRun(store.listRuns(reviewId));
            boolean retry = latest.map(run -> run.state() != ReviewRun.State.ACCEPTED && !run.active()).orElse(true);
            followHead(review, retry);
            return projection(reviewId);
        });
    }

    /** Refreshes the pull request now and follows its head; a replay keeps its head and only resumes. */
    public AgentReviewProjection update(String reviewId)
    {
        AgentReview review = requireOpen(reviewId);
        if (review.replay().isPresent()) {
            return resume(reviewId);
        }
        requireNoLocalRun(tasks.get(TaskId.valueOf(review.taskId())));
        String head;
        OptionalInt number;
        OptionalLong pullRequestId;
        if (review.sourceTaskId().isPresent()) {
            AgentTask source = sourceTask(review);
            number = review.number().isPresent() ? review.number() : sourcePullRequest(source, review.repo());
            Snapshot snapshot = localSnapshot(source, workspaces.requireReadyRepository(review.workspaceId()), number);
            head = snapshot.headSha();
            pullRequestId = snapshot.pullRequestId();
        }
        else {
            number = review.number();
            pullRequestId = review.pullRequestId();
            refresh.refreshAllDetail(review.repo(), number.orElseThrow());
            head = pullRequests.findHeadSha(review.repo(), number.orElseThrow())
                    .orElseThrow(() -> new ReviewConflictException("The pull request head is unknown after refresh"));
        }
        return locked(reviewId, () -> {
            AgentReview current = requireOpen(reviewId);
            if (current.number().isEmpty() && number.isPresent()) {
                current = current.withPullRequest(number.getAsInt(), pullRequestId, Instant.now(clock));
            }
            store.updateReview(current.withObservedHead(head, Instant.now(clock)));
            return resume(reviewId);
        });
    }

    /** Regenerates only the displayed change explanation at the latest committed head. */
    public AgentReviewProjection refreshExplanation(String reviewId, String runId, int expectedRevision)
    {
        checkArgument(runId != null && !runId.isBlank(), "runId must not be blank");
        checkArgument(expectedRevision > 0, "expectedRevision must be positive");
        AgentReview review = locked(reviewId, () -> requireExplanationRefresh(reviewId, runId, expectedRevision));
        WorkspaceRepositoryContext workspace = workspaces.requireReadyRepository(review.workspaceId());
        OptionalInt number = review.number();
        Snapshot snapshot;
        if (review.replay().isPresent()) {
            snapshot = frozen(store.listRuns(reviewId).getFirst());
        }
        else if (review.sourceTaskId().isPresent()) {
            AgentTask source = sourceTask(review);
            number = number.isPresent() ? number : sourcePullRequest(source, review.repo());
            snapshot = localSnapshot(source, workspace, number);
        }
        else {
            refresh.refreshAllDetail(review.repo(), number.orElseThrow());
            snapshot = snapshot(workspace, review.repo(), number.orElseThrow());
        }
        OptionalInt refreshedNumber = number;
        return locked(reviewId, () -> {
            AgentReview current = requireExplanationRefresh(reviewId, runId, expectedRevision);
            if (!current.observedHeadSha().equals(review.observedHeadSha()) && !current.observedHeadSha().equals(Optional.of(snapshot.headSha()))) {
                throw new ReviewConflictException("The change moved while refreshing its explanation; reload and try again");
            }
            AgentTask task = tasks.get(TaskId.valueOf(current.taskId()));
            turns.synchronizeWorktree(task.id(), task.version(),
                    claimed -> moveWorktree(claimed, current.worktreeHeadSha().or(() -> latestRun(reviewId).map(ReviewRun::headSha)), snapshot.headSha(), workspace));
            Instant now = Instant.now(clock);
            AgentReview synchronizedReview = requireOpen(reviewId).withObservedHead(snapshot.headSha(), now).withWorktreeHead(snapshot.headSha(), now)
                    .withFollowUpPaused(false, now);
            if (synchronizedReview.number().isEmpty() && refreshedNumber.isPresent()) {
                synchronizedReview = synchronizedReview.withPullRequest(refreshedNumber.getAsInt(), snapshot.pullRequestId(), now);
            }
            store.updateReview(synchronizedReview);
            syncFailures.remove(reviewId);
            ReviewRun run = insertRun(current, snapshot, store.listRuns(reviewId).size() + 1, ReviewRun.Scope.FULL, Optional.empty(), ReviewStep.explanation());
            startFollowup(run, tasks.get(task.id()));
            return projection(reviewId);
        });
    }

    private AgentReview requireExplanationRefresh(String reviewId, String runId, int expectedRevision)
    {
        AgentReview review = requireOpen(reviewId);
        List<ReviewRun> runs = store.listRuns(reviewId);
        ReviewRun explanation = ReviewCards.explanationRun(runs).orElseThrow(() -> new ReviewConflictException("The initial change explanation is not ready"));
        if (!explanation.id().equals(runId) || explanation.summary().orElseThrow().revision() != expectedRevision) {
            throw new ReviewConflictException("The change explanation was revised; reload before refreshing it");
        }
        LocalRunService.requireIdle(turns, runs, review, tasks.get(TaskId.valueOf(review.taskId())));
        if (runs.stream().anyMatch(ReviewRun::active)) {
            throw new ReviewConflictException("Finish or stop the current review before refreshing its explanation");
        }
        return review;
    }

    /** Completes the task, closes the review durably, then requests clean worktree cleanup. */
    public AgentReviewProjection end(String reviewId)
    {
        return locked(reviewId, () -> {
            AgentReview review = requireOpen(reviewId);
            TaskId taskId = TaskId.valueOf(review.taskId());
            AgentTask task = tasks.get(taskId);
            requireNoLocalRun(task);
            if (activeTurn(task).isPresent()) {
                throw new ReviewConflictException("Stop the running turn before ending the review");
            }
            // Completion refuses uncommitted work, and refusing only after unwatching would leave an open review nobody observes.
            Optional<TaskWorktree> worktree = task.taskWorktree().filter(ignored -> task.state() == TaskState.OPEN && task.worktreeRemovedAt().isEmpty());
            if (worktree.isPresent()) {
                WorktreeSnapshot snapshot = worktrees.inspect(worktree.orElseThrow());
                if (snapshot.gitOperationInProgress()) {
                    throw new ReviewConflictException("Finish the open Git operation in the working copy before ending the review");
                }
                if (snapshot.uncommitted()) {
                    throw new ReviewConflictException("Discard saved edits before ending the review");
                }
            }
            observer.unwatch(reviewId);
            sweeps.cancel(reviewId);
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
            try {
                ReviewMavenRepository.delete(reviewId);
            }
            catch (IOException | RuntimeException failure) {
                log.warn("Review {} ended but its private Maven repository was kept: {}", reviewId, failure.toString());
            }
            return projection(reviewId);
        });
    }

    /** A private question on the summary or a finding: a turn when idle, a visible queue entry when busy. */
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
                    .orElseGet(() -> latestFindingsRun(store.listRuns(reviewId)).orElseThrow(() -> new ReviewConflictException("The review has no findings run yet")));
            String label;
            String reassessmentNote = "";
            if (findingId.isPresent()) {
                ReviewFinding finding = requireFinding(review, findingId.orElseThrow());
                if (finding.revision() != targetRevision) {
                    throw new ReviewConflictException("The finding was revised to revision " + finding.revision() + "; reload before asking");
                }
                label = finding.id();
                if (finding.outdated()) {
                    reassessmentNote = "\n\nThis finding is pending reassessment in the current review scope. "
                            + "Check its claim against the current base and head before standing behind it.";
                }
            }
            else {
                int revision = run.summary().map(summary -> summary.revision()).orElse(1);
                if (revision != targetRevision) {
                    throw new ReviewConflictException("The summary was revised to revision " + revision + "; reload before asking");
                }
                label = cardId.orElse("summary");
            }
            String prompt = ReviewPrompts.question(label, targetRevision, run, text)
                    + reassessmentNote
                    + cardId.map(id -> "\n\nSelected card:\n" + ReviewCards.content(run, id)).orElse("");
            String messageId = UUID.randomUUID().toString();
            TaskId taskId = TaskId.valueOf(review.taskId());
            AgentTask task = tasks.get(taskId);
            // A synchronous launch can resolve this discussion before start returns its turn ID.
            ReviewMessage question = store.insertMessage(new ReviewMessage(
                    messageId,
                    reviewId,
                    findingId,
                    cardId,
                    ReviewMessage.Author.USER,
                    text,
                    targetRevision,
                    run.id(),
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    Instant.now(clock)));
            String requestKey = "review-ask:" + messageId;
            try {
                return attachQuestionDelivery(question, TaskInput.send(turns, taskId, task.version(), prompt, requestKey, !idle(task), Optional.empty()));
            }
            catch (RuntimeException failure) {
                try {
                    Optional<ReviewMessage> attached = TaskInput.accepted(turns, taskId, requestKey).map(accepted -> attachQuestionDelivery(question, accepted));
                    if (attached.isEmpty()) {
                        // Nothing accepted it, so no answer can come; kept, it would look sent forever. The caller sees why and can ask again.
                        store.deleteMessage(question.id());
                    }
                }
                catch (RuntimeException recoveryFailure) {
                    if (failure != recoveryFailure) {
                        failure.addSuppressed(recoveryFailure);
                    }
                }
                throw failure;
            }
        });
    }

    private ReviewMessage attachQuestionDelivery(ReviewMessage question, TaskInput.Accepted accepted)
    {
        Optional<String> turnId = accepted.turnId().map(Object::toString);
        Optional<String> queuedId = accepted.queuedInputId().map(Object::toString);
        return store.updateMessage(new ReviewMessage(
                question.id(),
                question.reviewId(),
                question.findingId(),
                question.cardId(),
                question.author(),
                question.body(),
                question.targetRevision(),
                question.runId(),
                turnId,
                queuedId,
                question.version(),
                question.createdAt(),
                question.developerTaskId()));
    }

    /** Admits a discussion input under the same lock as review head updates and closure. */
    public void sendMessage(String reviewId, long expectedTaskVersion, String message, String idempotencyKey, boolean queued)
    {
        locked(reviewId, () -> {
            TaskInput.send(turns, TaskId.valueOf(requireOpen(reviewId).taskId()), expectedTaskVersion, message, idempotencyKey, queued, Optional.empty());
            return null;
        });
    }

    /**
     * Records a development agent's private reply on a finding. It starts no reviewer turn and leaves the finding's state alone.
     *
     * @param developerTaskId the development task whose agent replies
     * @param messageId the caller's identity for this reply; repeating it with the same reply returns the saved one
     * @throws com.bytequay.core.review.ReviewConflictException when the identity already holds a different message
     */
    public ReviewMessage replyFromDeveloper(String reviewId, String findingId, String developerTaskId, String messageId, String body)
    {
        requireNonNull(developerTaskId, "developerTaskId is null");
        requireNonNull(messageId, "messageId is null");
        String text = requireNonNull(body, "body is null").strip();
        if (text.isEmpty()) {
            throw new ReviewInvalidRequestException("body must not be blank");
        }
        return locked(reviewId, () -> {
            ReviewFinding finding = requireFinding(requireReview(reviewId), findingId);
            Optional<ReviewMessage> saved = store.findMessage(messageId);
            if (saved.isPresent()) {
                ReviewMessage reply = saved.orElseThrow();
                if (!reply.developerTaskId().equals(Optional.of(developerTaskId)) || !reply.findingId().equals(Optional.of(finding.id())) || !reply.body().equals(text)) {
                    throw new ReviewConflictException("The reply identity was reused for a different message");
                }
                return reply;
            }
            return store.insertMessage(new ReviewMessage(messageId, reviewId, Optional.of(finding.id()), Optional.empty(), ReviewMessage.Author.AGENT, text,
                    finding.revision(), finding.runId(), Optional.empty(), Optional.empty(), 0, Instant.now(clock), Optional.of(developerTaskId)));
        });
    }

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

    /**
     * Resolves a finding for the development agent that fixed it, or reopens one a development agent resolved. A finding
     * that is already resolved keeps whoever resolved it, and one the user resolved is the user's to reopen.
     *
     * @param developerTaskId the development task whose agent decides
     * @throws com.bytequay.core.review.ReviewConflictException when the reviewer withdrew the finding, or the agent would reopen the user's resolution
     */
    public ReviewFinding resolveFromDeveloper(String reviewId, String findingId, String developerTaskId, boolean resolved)
    {
        requireNonNull(developerTaskId, "developerTaskId is null");
        return locked(reviewId, () -> {
            ReviewFinding finding = requireFinding(requireReview(reviewId), findingId);
            if (finding.state() == ReviewFinding.State.WITHDRAWN) {
                throw new ReviewConflictException("The reviewer withdrew this finding");
            }
            if (resolved) {
                return finding.state() == ReviewFinding.State.RESOLVED ? finding : store.updateFinding(finding.resolvedByDeveloper(developerTaskId, Instant.now(clock)));
            }
            if (finding.state() == ReviewFinding.State.OPEN) {
                return finding;
            }
            if (finding.resolvedByTaskId().isEmpty()) {
                throw new ReviewConflictException("The user resolved this finding, and only the user reopens it");
            }
            return store.updateFinding(finding.withState(ReviewFinding.State.OPEN, Optional.empty(), Instant.now(clock)));
        });
    }

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

    /** The task a review owns, for the review-scoped activity routes. */
    public AgentTask task(String reviewId)
    {
        return tasks.get(TaskId.valueOf(requireReview(reviewId).taskId()));
    }

    /**
     * Applies a user's edit to the review's working copy under the review lock and the task's worktree claim, so it
     * cannot land between review steps, or after Stop before the cancellation settles.
     *
     * @param mutation receives the claimed task, whose worktree is present
     * @return the mutation's result
     * @throws com.bytequay.core.review.ReviewConflictException when the review has ended, its working copy is gone, or the agent, a run, an update or queued input holds it
     */
    public <T> T mutateWorkingCopy(String reviewId, Function<AgentTask, T> mutation)
    {
        requireNonNull(mutation, "mutation is null");
        return locked(reviewId, () -> {
            AgentReview review = requireOpen(reviewId);
            AgentTask task = tasks.get(TaskId.valueOf(review.taskId()));
            if (task.taskWorktree().isEmpty() || task.worktreeRemovedAt().isPresent()) {
                throw new ReviewConflictException("The working copy is gone");
            }
            // An unpaused running review owns the working copy even between its steps, when the task holds no claim.
            LocalRunService.requireIdle(turns, store.listRuns(reviewId), review, task);
            AtomicReference<T> result = new AtomicReference<>();
            turns.synchronizeWorktree(task.id(), task.version(), claimed -> result.set(mutation.apply(claimed)));
            observer.reconcileLater(reviewId);
            return result.get();
        });
    }

    /** Idempotent progression of the current run from durable task facts: acceptance, completion repair, and pause. */
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
        catchUpWorkingCopy(reviewId);
        endFinishedReplay(reviewId);
    }

    /** A replay ends once its run does, which removes its worktree; one with leftover edits stays open for the user to look at. */
    private void endFinishedReplay(String reviewId)
    {
        boolean replay = store.findReview(reviewId).filter(review -> review.state() == AgentReview.State.OPEN && review.replay().isPresent()).isPresent();
        if (!replay || latestRun(reviewId).map(ReviewRun::active).orElse(true)) {
            return;
        }
        try {
            end(reviewId);
        }
        catch (ReviewConflictException refusal) {
            log.info("Replay review {} finished but stays open: {}", reviewId, refusal.getMessage());
        }
    }

    /** Records a newer remote head for every open review without moving its checkout or starting a run. */
    public void observeHead(String repo, int number, String headSha)
    {
        String repository = normalize(repo);
        store.listOpenReviews().stream()
                .filter(review -> review.sourceTaskId().isEmpty())
                .filter(review -> review.repo().equals(repository) && review.number().stream().anyMatch(value -> value == number))
                .forEach(review -> {
                    if (!review.observedHeadSha().equals(Optional.of(headSha))) {
                        locked(review.id(), () -> {
                            store.findReview(review.id()).filter(current -> current.state() == AgentReview.State.OPEN)
                                    .ifPresent(current -> store.updateReview(current.withObservedHead(headSha, Instant.now(clock))));
                            return null;
                        });
                    }
                    catchUpWorkingCopy(review.id());
                });
    }

    /** Network work stays outside the review/task locks; only a still-current target may move the checkout. */
    private void catchUpWorkingCopy(String reviewId)
    {
        Optional<AgentReview> stored = store.findReview(reviewId);
        if (stored.isEmpty() || !remoteReview(stored.orElseThrow())) {
            syncFailures.remove(reviewId);
            return;
        }
        AgentReview review = stored.orElseThrow();
        String target = review.observedHeadSha().orElseThrow();
        try {
            AgentTask task = tasks.get(TaskId.valueOf(review.taskId()));
            List<ReviewRun> runs = store.listRuns(reviewId);
            if (syncBusy(task, runs)) {
                return;
            }
            CheckoutState checkout = checkoutState(task);
            if (checkout.head().equals(target) && review.worktreeHeadSha().equals(Optional.of(target))) {
                return;
            }
            if (syncBlocker(review, task, runs, checkout).isPresent()) {
                return;
            }
            WorkspaceRepositoryContext workspace = workspaces.requireReadyRepository(review.workspaceId());
            if (!fetchReviewHead(review, workspace).equals(target)) {
                refresh.refreshAllDetail(review.repo(), review.number().orElseThrow());
                return;
            }
            locked(reviewId, () -> {
                AgentReview current = requireReview(reviewId);
                AgentTask currentTask = tasks.get(TaskId.valueOf(current.taskId()));
                if (!remoteReview(current) || !current.observedHeadSha().equals(Optional.of(target)) || syncBusy(currentTask, store.listRuns(reviewId))) {
                    return null;
                }
                turns.synchronizeWorktree(currentTask.id(), currentTask.version(), claimed -> {
                    AgentReview latest = requireReview(reviewId);
                    checkState(remoteReview(latest) && latest.observedHeadSha().equals(Optional.of(target)), "The observed PR head changed during catch-up");
                    CheckoutState fresh = checkoutState(claimed);
                    Optional<String> blocker = syncBlocker(latest, claimed, store.listRuns(reviewId), fresh);
                    checkState(blocker.isEmpty(), "%s", blocker.orElse(""));
                    if (!fresh.head().equals(target)) {
                        TaskWorktree worktree = claimed.taskWorktree().orElseThrow();
                        experiments.synchronizeClean(worktree, fresh.head(), target, () -> {
                            checkState(requireReview(reviewId).observedHeadSha().equals(Optional.of(target)), "The observed PR head changed during catch-up");
                            moveCleanHead(worktree, fresh.head(), target, workspace);
                        });
                    }
                    store.updateReview(requireReview(reviewId).withWorktreeHead(target, Instant.now(clock)));
                });
                syncFailures.remove(reviewId);
                return null;
            });
        }
        catch (RuntimeException failure) {
            syncFailures.put(reviewId, new SyncFailure(target, failure.getMessage() == null ? "Automatic catch-up could not finish" : failure.getMessage()));
            log.debug("Review {} working copy could not catch up: {}", reviewId, failure.toString());
        }
    }

    private String fetchReviewHead(AgentReview review, WorkspaceRepositoryContext workspace)
    {
        String remote = workspace.selectedRepository().equals(workspace.monitoredRepository()) ? "origin" : workspace.sourceRemote();
        String headRef = "refs/bytequay/review/" + review.number().orElseThrow();
        ImmutableList.Builder<String> fetch = ImmutableList.<String>builder()
                .add("fetch", "--no-tags", "--no-recurse-submodules", "--no-write-fetch-head", remote, "+refs/pull/" + review.number().orElseThrow() + "/head:" + headRef);
        pullRequests.findPullRequestDetail(review.repo(), review.number().orElseThrow()).map(PullRequestDetail::baseRef)
                .ifPresent(base -> fetch.add("+refs/heads/" + base + ":refs/remotes/" + remote + "/" + base));
        GitCommandRunner.Result fetched = git.run(workspace.repository(), fetch.build());
        checkState(fetched.successful() && !fetched.truncated(), "Cannot fetch the latest PR commit: %s", fetched.output().strip());
        String head = revParse(workspace.repository(), headRef);
        checkState(head.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Cannot identify the latest PR commit");
        return head;
    }

    private boolean syncBusy(AgentTask task, List<ReviewRun> runs)
    {
        return !idle(task) || runs.stream().anyMatch(ReviewRun::active) || turnStore.findActive(task.id()).isPresent() || !turns.queued(task.id()).isEmpty();
    }

    private static boolean remoteReview(AgentReview review)
    {
        return review.state() == AgentReview.State.OPEN && review.sourceTaskId().isEmpty() && review.number().isPresent() && review.observedHeadSha().isPresent();
    }

    private Optional<WorktreeSyncView> worktreeSync(AgentReview review, AgentTask task, List<ReviewRun> runs)
    {
        if (!remoteReview(review)) {
            return Optional.empty();
        }
        Optional<String> head = Optional.empty();
        boolean dirty = false;
        try {
            CheckoutState checkout = checkoutState(task);
            head = Optional.of(checkout.head());
            dirty = checkout.dirty();
            if (head.equals(review.observedHeadSha())) {
                return Optional.of(new WorktreeSyncView(WorktreeSyncView.State.CURRENT, head, review.observedHeadSha(), dirty, Optional.empty()));
            }
            if (syncBusy(task, runs)) {
                return Optional.of(new WorktreeSyncView(WorktreeSyncView.State.PENDING, head, review.observedHeadSha(), dirty,
                        Optional.of("Waiting for the review and its working copy to become idle")));
            }
            Optional<String> blocker = syncBlocker(review, task, runs, checkout);
            if (blocker.isPresent()) {
                return Optional.of(new WorktreeSyncView(WorktreeSyncView.State.BLOCKED, head, review.observedHeadSha(), dirty, blocker));
            }
            SyncFailure failure = syncFailures.get(review.id());
            if (failure != null && review.observedHeadSha().equals(Optional.of(failure.head()))) {
                return Optional.of(new WorktreeSyncView(WorktreeSyncView.State.UNAVAILABLE, head, review.observedHeadSha(), dirty, Optional.of(failure.reason())));
            }
            return Optional.of(new WorktreeSyncView(WorktreeSyncView.State.PENDING, head, review.observedHeadSha(), dirty,
                    Optional.of("Waiting to catch up with the published PR")));
        }
        catch (RuntimeException failure) {
            return Optional.of(new WorktreeSyncView(WorktreeSyncView.State.UNAVAILABLE, head, review.observedHeadSha(), dirty,
                    Optional.ofNullable(failure.getMessage()).or(() -> Optional.of("The working copy could not be inspected"))));
        }
    }

    private Optional<String> syncBlocker(AgentReview review, AgentTask task, List<ReviewRun> runs, CheckoutState checkout)
    {
        if (checkout.operation()) {
            return Optional.of("Finish the working copy's open Git operation before catching up");
        }
        if (checkout.dirty()) {
            return Optional.of("Saved local changes are preserved");
        }
        String expected = review.worktreeHeadSha().or(() -> runs.isEmpty() ? Optional.empty() : Optional.of(runs.getLast().headSha()))
                .orElseGet(() -> task.taskWorktree().orElseThrow().startCommit());
        if (!checkout.head().equals(expected) && !review.observedHeadSha().equals(Optional.of(checkout.head()))) {
            return Optional.of("Local commits are preserved");
        }
        if (experiments.hasSavedExperiment(task.taskWorktree().orElseThrow())) {
            return Optional.of("Saved private experiments are preserved");
        }
        return Optional.empty();
    }

    /** One bounded status read provides actual HEAD and dirtiness without refreshing Git's index. */
    private CheckoutState checkoutState(AgentTask task)
    {
        checkState(task.worktreeRemovedAt().isEmpty(), "The review working copy is no longer available");
        TaskWorktree worktree = task.taskWorktree().orElseThrow(() -> new ReviewConflictException("The review has no managed working copy"));
        Path checkout = worktrees.ownedCheckout(worktree);
        GitCommandRunner.Result status = git.runLocalBoundedStdout(checkout, Duration.ofSeconds(2), 128 * 1024, ImmutableList.of(
                "--no-optional-locks", "-c", "core.fsmonitor=false", "-c", "core.untrackedCache=false", "status", "--porcelain=v2", "--branch", "-z",
                "--untracked-files=all", "--ignore-submodules=none"));
        checkState(status.successful() && !status.truncated(), "Cannot inspect the working copy: %s", status.output().strip());
        String head = "";
        String branch = "";
        boolean dirty = false;
        for (String entry : status.output().split("\\x00")) {
            if (entry.startsWith("# branch.oid ")) {
                head = entry.substring("# branch.oid ".length());
            }
            else if (entry.startsWith("# branch.head ")) {
                branch = entry.substring("# branch.head ".length());
            }
            else if (!entry.isEmpty() && !entry.startsWith("# ")) {
                dirty = true;
            }
        }
        checkState(head.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Cannot identify the working copy HEAD");
        checkState(branch.equals(worktree.generatedBranch()), "The working copy is on another branch");
        List<Path> operations = git.gitPaths(checkout, GIT_OPERATION_PATHS);
        checkState(operations.size() == GIT_OPERATION_PATHS.size(), "Cannot inspect the working copy's Git operations");
        return new CheckoutState(head, dirty, operations.stream().anyMatch(path -> !Files.notExists(path, LinkOption.NOFOLLOW_LINKS)));
    }

    private record CheckoutState(String head, boolean dirty, boolean operation) {}

    private record SyncFailure(String head, String reason) {}

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
        run = attachTurn(run, turn);
        if (!turn.state().terminal() || !idle(task)) {
            return review;
        }
        Instant now = Instant.now(clock);
        switch (turn.state()) {
            case COMPLETED -> {
                return settleCompleted(review, run, task, now);
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

    private AgentReview settleCompleted(AgentReview review, ReviewRun run, AgentTask task, Instant now)
    {
        CompletionAction action = completionAction(run);
        // Pausing prevents continuation, but still records an already reported result.
        if (review.followUpPaused() && action != ACCEPT && action != INCOMPLETE_COVERAGE) {
            return review;
        }
        ReviewStep step = run.currentStep().orElseThrow();
        return switch (action) {
            case VALIDATE, COLLECT_EVIDENCE -> {
                if (action == VALIDATE && sweeps.pending(review.id(), run.id())) {
                    // Validation must see the second reader's leads. Its end reconciles this review again.
                    yield review;
                }
                ReviewStep.Kind kind = action == VALIDATE ? ReviewStep.Kind.VALIDATE : ReviewStep.Kind.COLLECT_EVIDENCE;
                ReviewRun settled = action == VALIDATE ? withLeads(run, step, sweeps.take(review.id(), run.id()), now) : run;
                ReviewRun next = store.updateRun(settled.withStep(step.next(kind), now));
                startFollowup(next, task);
                yield review;
            }
            case REPAIR -> {
                repairAssignment(run, task);
                yield review;
            }
            case ACCEPT -> {
                if (run.explanationOnly()) {
                    store.updateRun(run.ended(ReviewRun.State.ACCEPTED, Optional.empty(), now));
                    yield review;
                }
                AgentReview advanced = review.withLatestCompletedRun(run.id(), now);
                ReviewRun completed = run.withCoverage(step.validation().orElseThrow().coverage(), now);
                store.acceptRun(advanced, completed.ended(ReviewRun.State.ACCEPTED, Optional.empty(), now));
                yield store.findReview(review.id()).orElseThrow();
            }
            case INCOMPLETE_COVERAGE -> incomplete(review, run, step.validation().filter(ReviewValidation::needsEvidence).isPresent()
                    ? "The evidence follow-up limit was exhausted; candidates remain unresolved"
                    : "The review has unresolved candidates or incomplete coverage");
            case INCOMPLETE_REPORT -> incomplete(review, run, run.explanationOnly()
                    ? "The agent stopped before saving a complete change explanation"
                    : "The agent stopped before saving a complete review report");
        };
    }

    /**
     * Appends the second reader's leads to the sealed report as unverified candidates, numbered after the investigation's
     * own, so the validation that starts next must settle each one. Leads beyond the run's candidate capacity are left out.
     */
    private ReviewRun withLeads(ReviewRun run, ReviewStep step, List<ReviewLead> leads, Instant now)
    {
        ReviewInvestigation report = step.investigation().orElseThrow();
        int room = Math.min(leads.size(), ReviewTools.candidateLimit(store, run) - report.candidates().size());
        if (room <= 0) {
            return run;
        }
        ImmutableList.Builder<ReviewCandidate> candidates = ImmutableList.<ReviewCandidate>builder().addAll(report.candidates());
        for (int index = 0; index < room; index++) {
            candidates.add(ReviewSweep.candidate(leads.get(index), report.candidates().size() + index + 1, step.number()));
        }
        ReviewInvestigation merged = new ReviewInvestigation(report.changeMap(), report.reviewedFiles(), report.contextEvidenceRefs(), candidates.build(),
                report.validations(), report.answers(), report.verification(), report.perspectives(), report.testCheck());
        return run.withInvestigation(merged, now).replaceCurrentStep(new ReviewStep(step.number(), step.kind(), step.repair(), Optional.of(merged), Optional.empty()), now);
    }

    private CompletionAction completionAction(ReviewRun run)
    {
        ReviewStep step = run.currentStep().orElseThrow();
        if (run.explanationOnly()) {
            return run.hasCompleteSummary() ? ACCEPT : step.repair() ? INCOMPLETE_REPORT : REPAIR;
        }
        if (step.validation().isPresent()) {
            ReviewValidation validation = step.validation().orElseThrow();
            if (!currentValidation(run, validation)) {
                return step.repair() ? INCOMPLETE_REPORT : REPAIR;
            }
            if (validation.needsEvidence() && run.steps().stream().filter(value -> value.kind() == ReviewStep.Kind.COLLECT_EVIDENCE).count() < 2) {
                return COLLECT_EVIDENCE;
            }
            if (!validation.coverage().complete() || !validation.coverage().omissions().isEmpty()
                    || !run.latestSealedInvestigation().orElseThrow().investigation().orElseThrow().completePerspectives()) {
                return INCOMPLETE_COVERAGE;
            }
            return ACCEPT;
        }
        if (run.coverage().filter(coverage -> !coverage.complete() || !coverage.omissions().isEmpty()).isPresent()) {
            return INCOMPLETE_COVERAGE;
        }
        if (step.investigation().isPresent() && ReviewCards.hasExplanation(store, run)) {
            return VALIDATE;
        }
        return step.repair() ? INCOMPLETE_REPORT : REPAIR;
    }

    private void repairAssignment(ReviewRun run, AgentTask task)
    {
        ReviewStep step = run.currentStep().orElseThrow();
        // A stale sealed report needs a new identity: replaying its old operation key would restore the stale result.
        boolean stale = step.validation().filter(report -> !currentValidation(run, report)).isPresent()
                || (step.investigation().isPresent() && !ReviewCards.hasExplanation(store, run));
        ReviewRun repairing = stale ? run.withStep(step.next(step.kind()).forRepair(), Instant.now(clock)) : run.replaceCurrentStep(step.forRepair(), Instant.now(clock));
        startFollowup(store.updateRun(repairing.withCompletionRepairUsed(Instant.now(clock))), task);
    }

    /** Tool admission checks again at acceptance, since a retained finding can be edited between writes. */
    private boolean currentValidation(ReviewRun run, ReviewValidation validation)
    {
        Optional<ReviewStep> investigation = run.latestSealedInvestigation();
        if (!ReviewCards.hasExplanation(store, run) || investigation.isEmpty() || investigation.orElseThrow().number() != validation.investigationStep()) {
            return false;
        }
        ReviewInvestigation report = investigation.orElseThrow().investigation().orElseThrow();
        int existingCandidates = report.candidates().size();
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
                Optional<String> carriedFinding = report.candidates().get(candidate.candidate() - 1).carriedFindingId();
                if (carriedFinding.isPresent() && !carriedFinding.equals(candidate.findingId())) {
                    return false;
                }
                Optional<ReviewFinding> finding = candidate.findingId().flatMap(store::findFinding);
                if (finding.isEmpty() || !finding.orElseThrow().reviewId().equals(run.reviewId()) || finding.orElseThrow().state() == ReviewFinding.State.WITHDRAWN
                        || finding.orElseThrow().outdated() || !finding.orElseThrow().validatedHeadSha().equals(run.headSha())
                        || !candidate.findingRevision().equals(Optional.of(finding.orElseThrow().revision()))
                        || !candidate.evidenceRefs().containsAll(finding.orElseThrow().evidence().stream().map(ReviewEvidence::ref).toList())) {
                    return false;
                }
            }
        }
        if (validation.selectedFindingIds().stream().anyMatch(id -> store.findFinding(id)
                .filter(finding -> finding.reviewId().equals(run.reviewId()) && finding.runId().equals(run.id())
                        && finding.origin() == ReviewFinding.Origin.AUTOMATIC)
                .filter(finding -> validation.candidates().stream().anyMatch(candidate -> candidate.outcome() == ReviewCandidateValidation.Outcome.SUPPORTED
                        && candidate.findingId().equals(Optional.of(id)) && candidate.findingRevision().equals(Optional.of(finding.revision()))))
                .isEmpty())) {
            return false;
        }
        return !validation.coverage().complete() || store.listFindings(run.reviewId()).stream()
                .filter(finding -> finding.state() == ReviewFinding.State.OPEN
                        || (finding.runId().equals(run.id()) && finding.origin() == ReviewFinding.Origin.AUTOMATIC && finding.state() != ReviewFinding.State.WITHDRAWN))
                .allMatch(finding -> (finding.runId().equals(run.id()) || report.candidates().stream()
                        .anyMatch(candidate -> candidate.carriedFindingId().equals(Optional.of(finding.id())))) && validation.candidates().stream()
                        .anyMatch(candidate -> candidate.outcome() == ReviewCandidateValidation.Outcome.SUPPORTED
                                && candidate.findingId().equals(Optional.of(finding.id()))));
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
            Optional<AgentTurn> existing = turns.findTurnByIdempotencyKey(task.id(), key);
            if (existing.isPresent()) {
                accepted = existing.orElseThrow();
            }
            else {
                accepted = startAssignment(run, new StartAgentTurnCommand(task.id(), ReviewPrompts.stepRequest(run), ImmutableList.of(), key));
            }
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
        attachTurn(current, accepted);
    }

    private ReviewRun attachTurn(ReviewRun run, AgentTurn turn)
    {
        if (run.turnIds().contains(turn.id().toString())) {
            return run;
        }
        return store.updateRun(run.withTurn(turn.id().toString(), Instant.now(clock)));
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
     * A head that carries the change the last accepted review read gets that review instead of another pass.
     * Synchronization and experiment cleanup hold the same mutation claim as ordinary task writers.
     */
    private void followHead(AgentReview review, boolean retry)
    {
        List<ReviewRun> runs = store.listRuns(review.id());
        Optional<ReviewRun> latest = latestFindingsRun(runs);
        if (runs.stream().anyMatch(ReviewRun::active) || review.observedHeadSha().isEmpty()) {
            return;
        }
        String head = review.observedHeadSha().orElseThrow();
        TaskId taskId = TaskId.valueOf(review.taskId());
        AgentTask task = tasks.get(taskId);
        if (!idle(task) || !turns.queued(taskId).isEmpty()) {
            return;
        }
        WorkspaceRepositoryContext workspace = workspaces.requireReadyRepository(review.workspaceId());
        Snapshot snapshot = review.replay().isPresent() ? frozen(runs.getFirst()) : review.sourceTaskId()
                .map(ignored -> localSnapshot(sourceTask(review), workspace, review.number()))
                .orElseGet(() -> snapshot(workspace, review.repo(), review.number().orElseThrow()));
        boolean changed = latest.map(run -> !run.headSha().equals(snapshot.headSha())
                || !run.baseRef().equals(snapshot.baseRef())
                || !run.baseSha().equals(snapshot.baseSha())
                || !run.mergeBaseSha().equals(snapshot.mergeBaseSha())).orElse(true);
        List<ReviewFinding> findings = store.listFindings(review.id());
        boolean needsReassessment = findings.stream().anyMatch(finding -> finding.state() == ReviewFinding.State.OPEN
                && (finding.outdated() || !finding.validatedHeadSha().equals(snapshot.headSha())));
        if (!changed && !retry && !needsReassessment) {
            return;
        }
        if (!snapshot.headSha().equals(head)) {
            store.updateReview(review.withObservedHead(snapshot.headSha(), Instant.now(clock)));
        }
        Optional<ReviewRun> previous = runs.stream()
                .filter(run -> !run.explanationOnly() && run.state() == ReviewRun.State.ACCEPTED
                        && run.coverage().filter(coverage -> coverage.complete() && coverage.omissions().isEmpty()).isPresent())
                .reduce((first, second) -> second);
        // Decided before the worktree moves, while the reviewed head is still the one checked out.
        Optional<List<ReviewFinding>> carried = changed ? carriedFindings(review, workspace, snapshot, previous, findings) : Optional.empty();
        // Compared with the fetched head: a retry can find a newer head than the observed one, and every run must sit where the worktree does.
        try {
            turns.synchronizeWorktree(taskId, task.version(),
                    claimed -> moveWorktree(claimed, review.worktreeHeadSha().or(() -> latest.map(ReviewRun::headSha)), snapshot.headSha(), workspace));
            store.updateReview(requireReview(review.id()).withWorktreeHead(snapshot.headSha(), Instant.now(clock)));
            syncFailures.remove(review.id());
        }
        catch (ReviewConflictException | AgentConflictException refusal) {
            store.updateReview(store.findReview(review.id()).orElseThrow().withFollowUpPaused(true, Instant.now(clock)));
            throw new ReviewConflictException(refusal.getMessage(), refusal);
        }
        if (carried.isPresent()) {
            carry(store.findReview(review.id()).orElseThrow(), snapshot, runs.size() + 1, previous.orElseThrow(), carried.orElseThrow());
            return;
        }
        startRun(store.findReview(review.id()).orElseThrow(), snapshot, workspace, runs.size() + 1, previous, findings);
    }

    /**
     * The open findings as they stand at a new head that carries the change the last accepted review read. Empty when that
     * review cannot simply be carried: the change differs, or an open finding is not exactly as that review left it,
     * which a discussion revision, a pending reassessment or lines that no longer map all mean.
     */
    private Optional<List<ReviewFinding>> carriedFindings(AgentReview review, WorkspaceRepositoryContext workspace, Snapshot snapshot, Optional<ReviewRun> accepted,
            List<ReviewFinding> findings)
    {
        if (accepted.isEmpty() || review.replay().isPresent()) {
            return Optional.empty();
        }
        ReviewRun reviewed = accepted.orElseThrow();
        List<ReviewCandidateValidation> conclusions = reviewed.currentStep().flatMap(ReviewStep::validation).map(ReviewValidation::candidates).orElse(ImmutableList.of());
        ReviewDiffAnchors anchors = ReviewDiffAnchors.of(snapshot.diff());
        Instant now = Instant.now(clock);
        ImmutableList.Builder<ReviewFinding> carried = ImmutableList.builder();
        for (ReviewFinding finding : findings) {
            if (finding.state() != ReviewFinding.State.OPEN) {
                continue;
            }
            boolean settled = !finding.outdated() && finding.validatedHeadSha().equals(reviewed.headSha())
                    && conclusions.stream().anyMatch(conclusion -> conclusion.outcome() == ReviewCandidateValidation.Outcome.SUPPORTED
                            && conclusion.findingId().equals(Optional.of(finding.id())) && conclusion.findingRevision().equals(Optional.of(finding.revision())));
            Optional<ReviewAnchor> anchor = finding.anchor().flatMap(anchors::remap);
            if (!settled || anchor.isPresent() != finding.anchor().isPresent()) {
                return Optional.empty();
            }
            carried.add(finding.carried(anchor, snapshot.headSha(), now));
        }
        // Asked last: the findings are already in memory, and the change needs Git.
        return ReviewHeadCheck.sameChange(git, workspace.repository(), reviewed, snapshot.baseRef(), snapshot.mergeBaseSha(), snapshot.headSha())
                ? Optional.of(carried.build()) : Optional.empty();
    }

    /**
     * Records the accepted review at a head with the same change, without a turn: a run that is already accepted and holds
     * the reviewed run's report, coverage and evidence, and the open findings where they now sit.
     */
    private void carry(AgentReview review, Snapshot snapshot, int ordinal, ReviewRun reviewed, List<ReviewFinding> findings)
    {
        Instant now = Instant.now(clock);
        ReviewRun run = store.insertRun(new ReviewRun(
                UUID.randomUUID().toString(), review.id(), ordinal, ReviewRun.Scope.FULL, snapshot.baseRef(), snapshot.baseSha(), snapshot.mergeBaseSha(), snapshot.headSha(),
                Optional.of(reviewed.headSha()), snapshot.commits(), snapshot.diff(), ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), reviewed.coverage(),
                reviewed.investigation(), reviewed.steps(), false, ImmutableMap.of(), reviewed.evidence(), Optional.empty(), 0, now, now, Optional.empty()));
        for (ReviewFinding finding : findings) {
            store.updateFinding(finding);
        }
        store.acceptRun(review.withLatestCompletedRun(run.id(), now), run.ended(ReviewRun.State.ACCEPTED, Optional.of("The change and its commit messages are identical to "
                + "the reviewed head `" + ReviewPrompts.shortSha(reviewed.headSha()) + "`, so that review was carried to this head without another pass."), now));
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
        try {
            experiments.synchronize(worktree, expectedHead, headSha, () -> {
                if (snapshot.head().equals(headSha)) {
                    return;
                }
                moveCleanHead(worktree, snapshot.head(), headSha, workspace);
            }).ifPresent(message -> log.info("Review task {} synchronized: {}", task.id(), message));
        }
        catch (RuntimeException failure) {
            throw new ReviewConflictException(failure.getMessage(), failure);
        }
    }

    private void moveCleanHead(TaskWorktree worktree, String previousHead, String headSha, WorkspaceRepositoryContext workspace)
    {
        GitCommandRunner.Result ancestry = git.run(workspace.repository(), "merge-base", "--is-ancestor", previousHead, headSha);
        checkState(!ancestry.truncated() && (ancestry.successful() || ancestry.exitCode() == 1), "Cannot verify the new PR commit ancestry");
        // No hooks, ignored-file overwrite, submodule recursion, or reset of private work.
        List<String> move = ancestry.successful()
                ? ImmutableList.of("-c", "core.hooksPath=/dev/null", "merge", "--ff-only", "--no-overwrite-ignore", headSha)
                : ImmutableList.of("-c", "core.hooksPath=/dev/null", "checkout", "--no-overwrite-ignore", "--no-recurse-submodules", "-B", worktree.generatedBranch(), headSha);
        GitCommandRunner.Result moved = git.run(worktree.canonicalWorktree(), move);
        checkState(moved.successful() && !moved.truncated() && worktrees.inspect(worktree).head().equals(headSha),
                "The review worktree could not be moved to %s: %s", ReviewPrompts.shortSha(headSha), moved.output().strip());
    }

    private void startRun(AgentReview review, Snapshot snapshot, WorkspaceRepositoryContext workspace, int ordinal, Optional<ReviewRun> previous, List<ReviewFinding> findings)
    {
        Instant now = Instant.now(clock);
        boolean incremental = previous.isPresent()
                && !previous.orElseThrow().headSha().equals(snapshot.headSha())
                && previous.orElseThrow().baseRef().equals(snapshot.baseRef())
                && previous.orElseThrow().baseSha().equals(snapshot.baseSha())
                && previous.orElseThrow().mergeBaseSha().equals(snapshot.mergeBaseSha())
                && git.run(workspace.repository(), "merge-base", "--is-ancestor", previous.orElseThrow().headSha(), snapshot.headSha()).successful();
        ReviewRun run = insertRun(review, snapshot, ordinal, incremental ? ReviewRun.Scope.INCREMENTAL : ReviewRun.Scope.FULL,
                previous.map(ReviewRun::headSha), ReviewStep.initial());
        AgentTurn turn;
        try {
            for (ReviewFinding finding : findings) {
                if (finding.state() == ReviewFinding.State.WITHDRAWN) {
                    continue;
                }
                store.updateFinding(finding.pendingReassessment(now));
            }
            turn = startAssignment(run, new StartAgentTurnCommand(
                    TaskId.valueOf(review.taskId()),
                    ReviewPrompts.reviewRequest(review, run),
                    ImmutableList.of(),
                    run.currentStep().orElseThrow().key(run.id())));
        }
        catch (RuntimeException failure) {
            ReviewRun current = store.findRun(run.id()).orElse(run);
            if (turns.findTurnByIdempotencyKey(TaskId.valueOf(review.taskId()), current.currentStep().orElseThrow().key(current.id())).isEmpty()) {
                store.updateRun(current.ended(ReviewRun.State.FAILED, Optional.of("Cannot start the review turn: " + failure.getMessage()), Instant.now(clock)));
                store.updateReview(store.findReview(review.id()).orElseThrow().withFollowUpPaused(true, Instant.now(clock)));
            }
            throw failure;
        }
        // If this write fails after turn acceptance, reconciliation reattaches that same request.
        ReviewRun current = store.findRun(run.id()).orElseThrow();
        if (!current.turnIds().contains(turn.id().toString())) {
            store.updateRun(current.withTurn(turn.id().toString(), Instant.now(clock)));
        }
        // The second reader reads beside the investigation. Validation waits for it, and its end reconciles this review.
        sweeps.start(review, run, tasks.get(TaskId.valueOf(review.taskId())), () -> reconcile(review.id()));
    }

    private ReviewRun insertRun(AgentReview review, Snapshot snapshot, int ordinal, ReviewRun.Scope scope, Optional<String> priorHead, ReviewStep step)
    {
        Instant now = Instant.now(clock);
        return store.insertRun(new ReviewRun(
                UUID.randomUUID().toString(), review.id(), ordinal, scope, snapshot.baseRef(), snapshot.baseSha(), snapshot.mergeBaseSha(), snapshot.headSha(), priorHead,
                snapshot.commits(), snapshot.diff(), ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(),
                ImmutableList.of(step), false, ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), 0, now, now, Optional.empty()));
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

    private AgentTask sourceTask(AgentReview review)
    {
        try {
            return tasks.getInteractive(review.workspaceId(), TaskId.valueOf(review.sourceTaskId().orElseThrow()));
        }
        catch (AgentTaskNotFoundException failure) {
            throw new ReviewConflictException("The development task was deleted; retained review results remain available", failure);
        }
    }

    private OptionalInt sourcePullRequest(AgentTask source, String repository)
    {
        Path checkout = sourceCheckout(source);
        Optional<Integer> assigned = assignments.findByTaskId(source.id().toString())
                .filter(assignment -> assignment.repo().equalsIgnoreCase(repository)).map(PullRequestTask::number);
        if (assigned.isPresent()) {
            return OptionalInt.of(assigned.orElseThrow());
        }
        TaskWorktree worktree = source.taskWorktree().orElseThrow(() -> new ReviewConflictException("The development task has no worktree"));
        GitCommandRunner.Result currentBranch = browse.read(checkout, "symbolic-ref", "--quiet", "--short", "HEAD");
        List<String> branches = currentBranch.successful() && !currentBranch.truncated()
                ? ImmutableList.of(worktree.generatedBranch(), currentBranch.output().strip()) : ImmutableList.of(worktree.generatedBranch());
        return branches.stream().distinct()
                .flatMap(branch -> pullRequestStore.findByHeadRef(repository, branch).stream())
                .filter(pr -> pr.origin() == PullRequest.Origin.AUTHORED).mapToInt(PullRequest::number).max();
    }

    private Path sourceCheckout(AgentTask source)
    {
        if (source.worktreeRemovedAt().isPresent()) {
            throw new ReviewConflictException("The development worktree is no longer available");
        }
        TaskWorktree worktree = source.taskWorktree().orElseThrow(() -> new ReviewConflictException("The development task has no worktree"));
        return worktrees.ownedCheckout(worktree);
    }

    private Snapshot localSnapshot(AgentTask source, WorkspaceRepositoryContext workspace, OptionalInt number)
    {
        WorkflowAgentTasks.requireSelectedRepository(WorkflowAgentTasks.repositoryIdentity(workspace.selectedRepository()), source.repository(), ReviewConflictException::new);
        Path checkout = sourceCheckout(source);
        TaskWorktree worktree = source.taskWorktree().orElseThrow();
        List<Path> operationPaths = git.gitPaths(checkout, GIT_OPERATION_PATHS);
        if (operationPaths.size() != GIT_OPERATION_PATHS.size() || operationPaths.stream().anyMatch(path -> !Files.notExists(path, LinkOption.NOFOLLOW_LINKS))) {
            throw new ReviewConflictException("Finish the development worktree's open Git operation before requesting review");
        }
        String head = browse.require(checkout, "rev-parse", "--verify", "HEAD^{commit}").strip();
        String baseRef = worktree.startCommit();
        String base = worktree.startCommit();
        OptionalLong pullRequestId = OptionalLong.empty();
        if (number.isPresent()) {
            PullRequestDetail detail = pullRequests.findPullRequestDetail(workspace.monitoredRepository().fullName(), number.getAsInt())
                    .orElseThrow(() -> new ReviewConflictException("Refresh the associated pull request before reviewing its local branch"));
            baseRef = Optional.ofNullable(detail.baseRef()).orElseThrow(() -> new ReviewConflictException("The pull request base branch is unknown"));
            String remote = workspace.selectedRepository().equals(workspace.monitoredRepository()) ? "origin" : workspace.sourceRemote();
            base = browse.require(checkout, "rev-parse", "--verify", "refs/remotes/" + remote + "/" + baseRef + "^{commit}").strip();
            pullRequestId = OptionalLong.of(detail.id());
        }
        String mergeBase = browse.require(checkout, "merge-base", head, base).strip();
        WorktreeDiffReader.History history = browse.history(checkout, mergeBase, head);
        WorktreeDiffReader.Diff diff = browse.diff(checkout, mergeBase, Optional.of(head));
        if (history.truncated() || diff.truncated()) {
            throw new ReviewConflictException("The local change set exceeds the review snapshot limit; narrow the committed changes before reviewing");
        }
        List<ReviewCommit> commits = history.commits().reversed().stream()
                .map(commit -> new ReviewCommit(commit.sha(), commit.message(), commit.author())).collect(toImmutableList());
        return new Snapshot(pullRequestId, head, baseRef, base, mergeBase, commits, diff.files());
    }

    /** A replay's scope, read from the workspace repository: the commits and diff from where its pull request branched to the past head. */
    private Snapshot replaySnapshot(Path repository, String headSha, String baseRef, String mergeBaseSha)
    {
        WorktreeDiffReader.History history = browse.history(repository, mergeBaseSha, headSha);
        WorktreeDiffReader.Diff diff = browse.diff(repository, mergeBaseSha, Optional.of(headSha));
        if (history.truncated() || diff.truncated()) {
            throw new ReviewConflictException("The replayed change exceeds the review snapshot limit");
        }
        List<ReviewCommit> commits = history.commits().reversed().stream()
                .map(commit -> new ReviewCommit(commit.sha(), commit.message(), commit.author())).collect(toImmutableList());
        return new Snapshot(OptionalLong.empty(), headSha, baseRef, mergeBaseSha, mergeBaseSha, commits, diff.files());
    }

    /** A replay never moves: a retry reviews the scope its first run froze. */
    private static Snapshot frozen(ReviewRun run)
    {
        return new Snapshot(OptionalLong.empty(), run.headSha(), run.baseRef(), run.baseSha(), run.mergeBaseSha(), run.commits(), run.diff());
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
     * the task at review creation; later global changes reach new reviews only.
     * An explicit composer choice updates the existing task defaults.
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
        return projection(requireReview(reviewId), true);
    }

    private AgentReviewProjection projection(AgentReview review, boolean inspectWorktree)
    {
        String reviewId = review.id();
        List<ReviewRun> runs = store.listRuns(reviewId);
        TaskId taskId = TaskId.valueOf(review.taskId());
        AgentTask task = tasks.get(taskId);
        Optional<AgentTurn> active = activeTurn(task);
        AgentCapabilities capabilities = drivers.require(task.laneId()).capabilities();
        int pendingApprovals = active.map(turn -> approvals.listPending(turn.id()).size()).orElse(0);
        List<QueuedAgentInput> queuedInputs = task.state() == TaskState.OPEN ? turns.queued(taskId) : ImmutableList.of();
        List<String> queued = queuedInputs.stream().map(QueuedAgentInput::id).map(Object::toString).collect(toImmutableList());
        return new AgentReviewProjection(
                review,
                runs,
                store.listFindings(reviewId),
                store.listMessages(reviewId).stream().map(message -> messageView(taskId, message)).collect(toImmutableList()),
                new AgentReviewProjection.TaskView(
                        task.id().toString(),
                        review.workspaceId(),
                        task.state().name(),
                        task.version(),
                        task.laneId().value(),
                        drivers.require(task.laneId()).label(),
                        task.defaults().model(),
                        task.defaults().effort(),
                        task.defaults().serviceTier(),
                        task.defaults().laneId().orElse(task.laneId()).value(),
                        task.worktreeMutationClaim().map(claim -> claim.kind().name()),
                        capabilities.steering(),
                        capabilities.nestedAgentActivity() && drivers.require(task.laneId())
                                .reviewChildAgents(task.sessionReference(), active.flatMap(AgentTurn::settings).flatMap(AgentTurnSettings::model).or(task.defaults()::model))),
                active.map(turn -> new AgentReviewProjection.TurnView(turn.id().toString(), turn.state().name(), turn.startedAt(), turn.cancellationRequestedAt())),
                queued,
                queuedInputs.stream().findFirst().filter(input -> input.state() == QueuedAgentInput.State.PAUSED).flatMap(QueuedAgentInput::pauseReason),
                pendingApprovals,
                runs.stream().flatMap(run -> run.turnIds().stream()).collect(toImmutableList()),
                review.number().stream().mapToObj(number -> drafts.find(review.repo(), number).map(ignored -> publications.draft(review.repo(), number)))
                        .flatMap(Optional::stream).findFirst(),
                review.number().stream().mapToObj(number -> pullRequests.findStored(review.repo(), number)).flatMap(Optional::stream).findFirst(),
                inspectWorktree ? worktreeSync(review, task, runs) : Optional.empty());
    }

    private AgentReviewProjection.MessageView messageView(TaskId taskId, ReviewMessage message)
    {
        // Delivery identity must stay readable after the generated prompt blob expires.
        // Acceptance precedes provider startup; do not wait for start/enqueue to return before showing the question card.
        boolean dispatching = message.author() == ReviewMessage.Author.USER && message.turnId().isEmpty() && message.queuedInputId().isEmpty();
        String key = "review-ask:" + message.id();
        Optional<QueuedAgentInput> queued = message.queuedInputId().map(QueuedInputId::valueOf).flatMap(queueStore::find)
                .or(() -> dispatching ? queueStore.findQueuedByIdempotencyKey(taskId, key) : Optional.empty());
        return new AgentReviewProjection.MessageView(message,
                queued.flatMap(QueuedAgentInput::turnId).map(Object::toString).or(message::turnId)
                        .or(() -> dispatching ? turnStore.findByIdempotencyKey(taskId, key).map(turn -> turn.id().toString()) : Optional.empty()),
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

    private static void requireNoLocalRun(AgentTask task)
    {
        if (task.worktreeMutationClaim().filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.LOCAL_RUN).isPresent()) {
            throw new ReviewConflictException("A run is using this working copy");
        }
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

    private static Optional<ReviewRun> latestFindingsRun(List<ReviewRun> runs)
    {
        return runs.stream().filter(run -> !run.explanationOnly()).reduce((first, second) -> second);
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

    /**
     * Where a replay reviews: the past head, and the commit its pull request branched from.
     *
     * @param mergeBaseSha where the pull request branched from its base branch, which is the replay's base
     */
    public record ReplayStart(AgentReview.Replay replay, String headSha, String baseRef, String mergeBaseSha) {}
}
