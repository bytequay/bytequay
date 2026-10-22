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
package com.bytequay.app.cherrypick;

import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskBranches;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitSequencerDriver;
import com.bytequay.app.git.SequenceState;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.WorkspacePaths;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.cherrypick.CherryPickConflictException;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecution.ActionJournal;
import com.bytequay.core.cherrypick.CherryPickExecution.ActionType;
import com.bytequay.core.cherrypick.CherryPickExecution.FinishReason;
import com.bytequay.core.cherrypick.CherryPickExecution.PauseReason;
import com.bytequay.core.cherrypick.CherryPickExecution.Range;
import com.bytequay.core.cherrypick.CherryPickExecution.Status;
import com.bytequay.core.cherrypick.CherryPickExecution.Step;
import com.bytequay.core.cherrypick.CherryPickExecution.Workspace;
import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import com.bytequay.core.cherrypick.CherryPickItem;
import com.bytequay.core.cherrypick.CherryPickItem.ConflictPath;
import com.bytequay.core.cherrypick.CherryPickItem.ConflictType;
import com.bytequay.core.cherrypick.CherryPickItem.Disposition;
import com.bytequay.core.cherrypick.CherryPickItem.IndexStage;
import com.bytequay.core.cherrypick.CherryPickLifecycle;
import com.bytequay.core.cherrypick.CherryPickLifecycle.BranchCommit.Origin;
import com.bytequay.core.cherrypick.CherryPickLifecycle.Hold;
import com.bytequay.core.cherrypick.CherryPickLifecycle.Hold.Reason;
import com.bytequay.core.cherrypick.CherryPickLifecycle.Note;
import com.bytequay.core.cherrypick.CherryPickLifecycle.Squash;
import com.bytequay.core.cherrypick.CherryPickLifecycle.Stage;
import com.bytequay.core.cherrypick.CherryPickNotFoundException;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.stream.Stream;

import static com.bytequay.app.agent.WorkflowAgentTasks.createOrGet;
import static com.bytequay.app.agent.WorkflowAgentTasks.repositoryIdentity;
import static com.bytequay.app.agent.WorkflowAgentTasks.requireReady;
import static com.bytequay.app.agent.WorkflowAgentTasks.requireSelectedRepository;
import static com.bytequay.app.utils.Locks.withLock;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Owns the progress and final results of confirmed cherry-pick executions.
 *
 * <p>The confirmed plan decides what runs next; Git reports what happened to
 * that one source commit. Nothing else may write status, step, item result, or pause
 * reason.
 */
@Service
public class CherryPickExecutionService
{
    private static final Logger log = LoggerFactory.getLogger(CherryPickExecutionService.class);
    private static final int MAX_MESSAGE_CHARACTERS = 16 * 1024;
    private static final int MAX_BUILD_TURNS = 2;
    private static final int MAX_LISTED_MODULES = 50;
    private static final String FIELD = "\u001f";
    private static final String RECORD = "\u001e";

    private final CherryPickPreviewService previews;
    private final CherryPickExecutionStore store;
    private final GitSequencerDriver sequencer;
    private final GitCommandRunner git;
    private final AgentTaskService tasks;
    private final AgentTurnService turns;
    private final AgentCompletionService completions;
    private final AgentDriverRegistry drivers;
    private final CredentialService credentials;
    private final WorkspaceRepositoryService workspaceRepositories;
    private final WorkspacePaths workspacePaths;
    private final CherryPickDelivery delivery;
    private final CherryPickBranch branch;
    private final Clock clock;
    private final Striped<Lock> advanceLocks = Striped.lock(64);

    public CherryPickExecutionService(
            CherryPickPreviewService previews,
            CherryPickExecutionStore store,
            GitSequencerDriver sequencer,
            GitCommandRunner git,
            AgentTaskService tasks,
            AgentTurnService turns,
            AgentCompletionService completions,
            AgentDriverRegistry drivers,
            CredentialService credentials,
            WorkspaceRepositoryService workspaceRepositories,
            WorkspacePaths workspacePaths,
            CherryPickDelivery delivery,
            Clock clock)
    {
        this.previews = requireNonNull(previews, "previews is null");
        this.store = requireNonNull(store, "store is null");
        this.sequencer = requireNonNull(sequencer, "sequencer is null");
        this.git = requireNonNull(git, "git is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.completions = requireNonNull(completions, "completions is null");
        this.drivers = requireNonNull(drivers, "drivers is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.workspaceRepositories = requireNonNull(workspaceRepositories, "workspaceRepositories is null");
        this.workspacePaths = requireNonNull(workspacePaths, "workspacePaths is null");
        this.delivery = requireNonNull(delivery, "delivery is null");
        this.branch = new CherryPickBranch(git);
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Freezes a confirmed plan and prepares the workspace it will run in.
     *
     * @param command confirmed skips and the object IDs they were chosen against
     * @param workspaceId durable workspace that owns the managed clone
     * @param repository clone that owns the objects
     * @param physicalRepository selected repository that physically owns the worktree
     * @return the durable execution
     */
    public CherryPickExecution start(StartCherryPickExecutionCommand command, String workspaceId, Path repository, RepositoryIdentity physicalRepository)
    {
        requireNonNull(command, "command is null");
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(repository, "repository is null");
        requireNonNull(physicalRepository, "physicalRepository is null");

        // Answer a repeated start before creating anything: the live-execution
        // check below would otherwise reject the caller's own execution.
        Optional<CherryPickExecution> existing = store.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            CherryPickExecution execution = existing.orElseThrow();
            requireWorkspace(execution.workspace().workspaceId(), workspaceId);
            previews.requireTransferAllowed(execution.workspace().sourceRepository(), execution.workspace().repository());
            if (execution.status() != Status.FINISHED) {
                ensureAgentTask(execution);
            }
            return execution;
        }

        CherryPickPreview preview = previews.find(command.previewId())
                .orElseThrow(() -> new IllegalArgumentException("Preview is unknown or expired; preview the range again"));
        requireWorkspace(preview.workspaceId(), workspaceId);
        previews.requireTransferAllowed(Optional.of(preview.sourceRepository()), preview.repository());
        requireUnmoved(preview, command);
        AgentLaneId laneId = new AgentLaneId(command.laneId());
        requireReady(drivers, laneId, AgentPermissionPolicy.CHERRY_PICK);
        previews.requireCurrentTarget(repository, preview);
        List<CherryPickItem> items = plan(preview, command.userSkippedSourceShas());
        requireNoLiveExecution(preview, workspaceId);
        WorkspaceRepositoryContext workspace = workspaceRepositories.requireReadyRepository(workspaceId);
        RepositoryIdentity selectedRepository = new RepositoryIdentity(workspace.selectedRepository().owner(), workspace.selectedRepository().repo());
        checkArgument(workspace.repository().equals(repository) && selectedRepository.equals(physicalRepository), "Workspace repository routing changed before start");

        TaskId taskId = TaskId.random();
        String branch = TaskBranches.generate(taskId, credentials.resolveGitHubCredential().profile().login(), "Cherry-pick " + preview.targetBranch());
        CherryPickExecution proposed = CherryPickExecution.create(
                CherryPickExecutionId.random(),
                command.idempotencyKey(),
                new Workspace(
                        workspaceId,
                        preview.repository(),
                        RepoRef.of(physicalRepository.owner(), physicalRepository.name()),
                        preview.sourceRemote(),
                        preview.targetRemote(),
                        preview.targetBranch(),
                        preview.localTargetBranch(),
                        branch,
                        plannedWorktree(workspaceId, taskId),
                        command.laneId(),
                        Optional.of(preview.sourceRepository()), command.model(), command.effort()),
                Optional.of(command.pullRequestDraft()),
                new Range(preview.sourceBaseSha(), preview.sourceTipSha(), preview.targetSha()),
                items,
                command.delivers(),
                clock.instant())
                .withAgentTask(taskId.toString(), clock.instant());

        CherryPickExecution execution = store.createOrGet(proposed);
        requireWorkspace(execution.workspace().workspaceId(), workspaceId);
        if (execution.status() != Status.FINISHED) {
            ensureAgentTask(execution);
        }
        return execution;
    }

    public CherryPickExecution get(CherryPickExecutionId executionId)
    {
        return store.find(executionId).orElseThrow(() -> new CherryPickNotFoundException(executionId));
    }

    public List<CherryPickExecution> list(int limit)
    {
        return store.listRecent(limit);
    }

    /**
     * Aborts the execution: stops its turn, CI and rebase, closes its pull request, deletes its
     * published branch, and requests safe task completion. The target branch is never touched.
     *
     * @param executionId execution identifier
     * @param workspaceId durable workspace that owns the execution
     * @return the finished execution
     */
    public CherryPickExecution cancel(CherryPickExecutionId executionId, String workspaceId)
    {
        Lock lock = advanceLocks.get(executionId.toString());
        return withLock(lock, () -> {
            CherryPickExecution execution = get(executionId);
            requireWorkspace(execution.workspace().workspaceId(), workspaceId);
            if (execution.status() == Status.FINISHED) {
                finishAgentTask(execution);
                return execution;
            }
            ensureAgentTask(execution);
            execution.currentTurnId().ifPresent(turnId -> {
                try {
                    turns.cancel(TurnId.valueOf(turnId));
                }
                catch (RuntimeException failure) {
                    log.warn("Cannot cancel turn {} for execution {}", turnId, execution.id(), failure);
                }
            });
            List<String> problems = execution.lifecycle().delivers() ? delivery.withdraw(execution) : ImmutableList.of();
            String aborted = problems.isEmpty() ? "Aborted" : "Aborted. " + String.join(". ", problems);
            CherryPickExecution finished = store.save(execution.finish(FinishReason.CANCELLED, aborted.substring(0, Math.min(aborted.length(), 8 * 1024)), clock.instant()));
            finishAgentTask(finished);
            return finished;
        });
    }

    /**
     * Inspects Git and performs at most one next action.
     *
     * @param executionId execution selected by the monitor
     * @return execution after one bounded action
     */
    public CherryPickExecution advance(CherryPickExecutionId executionId)
    {
        Lock lock = advanceLocks.get(executionId.toString());
        if (!lock.tryLock()) {
            return get(executionId);
        }
        try {
            CherryPickExecution execution = get(executionId);
            if (execution.status() != Status.RUNNING) {
                if (execution.status() == Status.FINISHED) {
                    finishAgentTask(execution);
                }
                return execution;
            }
            if (execution.lifecycle().hold().isPresent()) {
                return execution;
            }
            if (execution.agentTaskId().isEmpty()) {
                return pause(execution, PauseReason.RECONCILIATION_MISMATCH, "Execution has no agent task");
            }
            ensureAgentTask(execution);
            return switch (execution.lifecycle().stage()) {
                case APPLY -> execution.pendingItems().isEmpty() ? complete(execution) : act(execution);
                case BUILD -> build(execution);
                case PUBLISH, CI_FIX, SQUASH, MERGE -> {
                    CherryPickExecution delivered = delivery.advance(execution);
                    if (delivered.status() == Status.FINISHED) {
                        finishAgentTask(delivered);
                    }
                    yield delivered;
                }
            };
        }
        finally {
            lock.unlock();
        }
    }

    private CherryPickExecution act(CherryPickExecution execution)
    {
        if (execution.currentTurnId().isPresent()) {
            return afterTurn(execution);
        }
        if (execution.journal().map(ActionJournal::type).filter(ActionType.SKIP_EMPTY::equals).isPresent()) {
            return recoverEmptySkip(execution);
        }
        if (execution.journal().map(ActionJournal::type).filter(ActionType.COMMIT_CONFLICT_SNAPSHOT::equals)
                .isPresent()) {
            return recoverConflictSnapshot(execution);
        }
        if (execution.journal().map(ActionJournal::type).filter(type -> type == ActionType.AGENT_TURN || type == ActionType.AGENT_REPAIR).isPresent()) {
            return inspectFixup(execution, Optional.empty());
        }
        if (execution.journal().map(ActionJournal::type).filter(ActionType.FINALIZE_CONFLICT_PAIR::equals)
                .isPresent()) {
            return finalizeConflictPair(execution);
        }
        Path worktree = execution.workspace().worktree();
        String expectedHead = execution.currentTargetSha();
        SequenceState before = sequencer.observe(worktree);

        if (before.kind() == SequenceState.Kind.COMPLETED) {
            if (!before.head().equals(expectedHead)) {
                // Git ran but the result was never stored, which is what a crash
                // between the two looks like. Adopt the commits if they map to
                // the plan; reconciliation pauses if they do not.
                return reconcile(execution, expectedHead, before);
            }
            // One source commit per Git operation. Handing Git the whole plan
            // would let --skip and --continue run on past the commit in hand,
            // and then no single operation would own a single result.
            String next = execution.pendingItems().getFirst().sourceSha();
            CherryPickExecution started = store.save(execution
                    .withStep(Step.APPLYING, clock.instant())
                    .withJournal(journal(ActionType.BEGIN_SEQUENCE, Optional.of(next), expectedHead), clock.instant()));
            return reconcile(started, expectedHead, sequencer.beginCherryPick(worktree, expectedHead, next));
        }
        if (before.kind() == SequenceState.Kind.STOPPED_EMPTY) {
            String stopped = before.stoppedCommit().orElseThrow();
            CherryPickExecution journalled = store.save(execution
                    .withJournal(journal(ActionType.SKIP_EMPTY, Optional.of(stopped), before.head()), clock.instant()));
            return recoverEmptySkip(journalled);
        }
        if (before.kind() == SequenceState.Kind.STOPPED_CONFLICT) {
            return commitConflictSnapshot(execution, before);
        }
        if (before.kind() == SequenceState.Kind.STOPPED_RESOLVED) {
            return pause(execution, PauseReason.UNSAFE_GIT_STATE, "A conflict was staged outside ByteQuay before its index stages were recorded");
        }
        return pause(execution, PauseReason.UNSAFE_GIT_STATE, before.diagnostics().isBlank() ? "Unexpected Git state" : before.diagnostics());
    }

    private CherryPickExecution recoverEmptySkip(CherryPickExecution execution)
    {
        ActionJournal journal = execution.journal().orElseThrow();
        String sourceSha = journal.sourceSha().orElseThrow();
        String expectedHead = journal.expectedHead();
        CherryPickItem item = itemOf(execution, sourceSha);
        SequenceState state = sequencer.observe(execution.workspace().worktree());

        if (item.result() == CherryPickItem.Result.EMPTY_SKIPPED) {
            if (state.kind() != SequenceState.Kind.COMPLETED || !state.head().equals(expectedHead)) {
                return pause(execution, PauseReason.UNSAFE_GIT_STATE, "The worktree moved after the empty result was recorded");
            }
            return execution.pendingItems().isEmpty() ? complete(execution) : store.save(execution.withoutJournal(clock.instant()));
        }
        if (item.result() != CherryPickItem.Result.PENDING) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH, "Empty-skip journal points to a completed item");
        }

        SequenceState after;
        if (state.kind() == SequenceState.Kind.STOPPED_EMPTY
                && state.head().equals(expectedHead)
                && state.stoppedCommit().filter(sourceSha::equals).isPresent()) {
            after = sequencer.skipEmpty(execution.workspace().worktree(), expectedHead, sourceSha);
        }
        else if (state.kind() == SequenceState.Kind.COMPLETED && state.head().equals(expectedHead)) {
            // The guarded skip completed before its result was saved.
            after = state;
        }
        else {
            return pause(execution, PauseReason.UNSAFE_GIT_STATE, "Empty skip is not at its recorded before or after state");
        }
        if (after.kind() != SequenceState.Kind.COMPLETED || !after.head().equals(expectedHead)) {
            return pause(execution, PauseReason.UNSAFE_GIT_STATE, "Empty skip did not close cleanly at " + expectedHead);
        }
        CherryPickExecution updated = store.save(execution.recordEmptySkipped(sourceSha, clock.instant()));
        return reconcile(updated, expectedHead, after);
    }

    /**
     * Accepts at most one created commit, for the one commit that was in flight.
     *
     * <p>Because a single operation carries a single source commit, the check is
     * the direct one: HEAD is exactly one new child of the expected parent, and
     * that child records the source commit ByteQuay asked for. Anything else,
     * including a second commit, pauses.
     */
    private CherryPickExecution reconcile(CherryPickExecution execution, String expectedHead, SequenceState after)
    {
        Path worktree = execution.workspace().worktree();
        if (after.kind() == SequenceState.Kind.UNSAFE) {
            return pause(execution, PauseReason.UNSAFE_GIT_STATE, after.diagnostics());
        }
        List<CreatedCommit> created = createdCommits(worktree, expectedHead, after.head());
        if (created.size() > 1) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH, "One source commit produced " + created.size() + " commits");
        }
        Optional<CreatedCommit> applied = Optional.empty();
        if (!created.isEmpty()) {
            CreatedCommit commit = created.getFirst();
            if (commit.sourceSha().isEmpty()) {
                return pause(execution, PauseReason.RECONCILIATION_MISMATCH, "Commit " + commit.targetSha() + " carries no cherry-pick origin line");
            }
            String expectedSource = execution.pendingItems().getFirst().sourceSha();
            if (!commit.sourceSha().orElseThrow().equals(expectedSource)) {
                return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                        "Commit " + commit.targetSha() + " records " + commit.sourceSha().orElseThrow()
                                + " but the plan expects " + expectedSource);
            }
            if (!parentOf(worktree, commit.targetSha()).equals(expectedHead)) {
                return pause(execution, PauseReason.RECONCILIATION_MISMATCH, "Commit " + commit.targetSha() + " is not a child of " + expectedHead);
            }
            applied = Optional.of(commit);
        }
        if (applied.isPresent() && !git.withoutConflictMarkers(worktree, expectedHead, after.head())) {
            return pause(execution, PauseReason.UNSAFE_GIT_STATE, "A created commit still contains a conflict marker");
        }
        CherryPickExecution updated = applied
                .map(commit -> store.save(execution.recordApplied(commit.sourceSha().orElseThrow(), commit.targetSha(), clock.instant())))
                .orElse(execution);
        if (updated.pendingItems().isEmpty()) {
            return complete(updated);
        }
        return store.save(updated.withoutJournal(clock.instant()));
    }

    private List<CreatedCommit> createdCommits(Path worktree, String fromSha, String toSha)
    {
        if (fromSha.equals(toSha)) {
            return ImmutableList.of();
        }
        GitCommandRunner.Result read = git.run(worktree, "log", "--reverse", "--format=" + RECORD + "%H" + FIELD + "%B", fromSha + ".." + toSha);
        if (!read.successful()) {
            return ImmutableList.of();
        }
        ImmutableList.Builder<CreatedCommit> created = ImmutableList.builder();
        for (String record : read.output().split(RECORD)) {
            if (record.isBlank()) {
                continue;
            }
            String[] fields = record.split(FIELD, 2);
            if (fields.length < 2) {
                continue;
            }
            created.add(new CreatedCommit(fields[0].strip(), CherryPickBranch.originSha(fields[1])));
        }
        return created.build();
    }

    private String parentOf(Path worktree, String sha)
    {
        GitCommandRunner.Result parent = git.run(worktree, "rev-parse", sha + "^");
        return parent.successful() ? parent.output().strip() : "";
    }

    private String headOf(Path worktree)
    {
        GitCommandRunner.Result head = git.run(worktree, "rev-parse", "HEAD");
        return head.successful() ? head.output().strip() : "";
    }

    private String subjectOf(Path worktree, String sha)
    {
        GitCommandRunner.Result subject = git.run(worktree, "log", "-1", "--format=%s", sha);
        return subject.successful() ? subject.output().strip() : "";
    }

    private String messageOf(Path worktree, String sha)
    {
        GitCommandRunner.Result message = git.run(worktree, "log", "-1", "--format=%B", sha);
        return message.successful() ? message.output() : "";
    }

    private boolean operationOpenOrUnmerged(Path worktree)
    {
        if (!git.run(worktree, "ls-files", "-u").output().isBlank()) {
            return true;
        }
        return !git.openMarkers(worktree, GitCommandRunner.OPERATION_MARKERS).isEmpty();
    }

    private CherryPickExecution pause(CherryPickExecution execution, PauseReason reason, String diagnostics)
    {
        log.info("Cherry-pick execution {} paused: {}", execution.id(), diagnostics);
        return store.save(execution.pause(reason, diagnostics.isBlank() ? reason.name() : diagnostics, clock.instant()));
    }

    private ActionJournal journal(ActionType type, Optional<String> sourceSha, String expectedHead)
    {
        return new ActionJournal(type, sourceSha, expectedHead, 1, type.name(), clock.instant());
    }

    private static String summary(CherryPickExecution execution)
    {
        long applied = execution.items().stream()
                .filter(item -> item.result() == CherryPickItem.Result.APPLIED)
                .count();
        long empty = execution.items().stream()
                .filter(item -> item.result() == CherryPickItem.Result.EMPTY_SKIPPED)
                .count();
        return applied + " applied, " + empty + " empty, " + execution.items().size() + " planned";
    }

    private void requireUnmoved(CherryPickPreview preview, StartCherryPickExecutionCommand command)
    {
        checkArgument(
                preview.sourceBaseSha().equals(command.expectedSourceBaseSha())
                        && preview.sourceTipSha().equals(command.expectedSourceTipSha())
                        && preview.targetSha().equals(command.expectedTargetSha()),
                "Preview is stale; review a refreshed preview before confirming");
    }

    private void requireNoLiveExecution(CherryPickPreview preview, String workspaceId)
    {
        boolean live = store.listRunning(CherryPickExecutionStore.MAX_LIST_LIMIT).stream()
                .anyMatch(execution -> execution.workspace().workspaceId().equals(workspaceId)
                        && execution.workspace().localTargetBranch().equals(preview.localTargetBranch()));
        if (live) {
            throw new CherryPickConflictException("Another cherry-pick execution is already running for " + preview.localTargetBranch());
        }
    }

    private static void requireWorkspace(String actualWorkspaceId, String expectedWorkspaceId)
    {
        checkArgument(actualWorkspaceId.equals(expectedWorkspaceId), "Cherry-pick data does not belong to this workspace");
    }

    private static List<CherryPickItem> plan(CherryPickPreview preview, List<String> userSkipped)
    {
        Set<String> skipped = ImmutableSet.copyOf(userSkipped);
        for (String sha : skipped) {
            boolean eligible = preview.commits().stream()
                    .anyMatch(commit -> commit.sourceSha().equals(sha) && !commit.alreadyOnTarget());
            checkArgument(eligible, "Skipped commit is not an eligible commit in this preview: " + sha);
        }
        return preview.commits().stream()
                .map(commit -> CherryPickItem.planned(
                        commit.position(),
                        commit.sourceSha(),
                        commit.sourceParentSha(),
                        commit.title(),
                        commit.pullRequest().map(pullRequest -> new CherryPickItem.PullRequestRefView(pullRequest.number(), pullRequest.title(), pullRequest.url())),
                        disposition(commit, skipped)))
                .collect(toImmutableList());
    }

    private static Disposition disposition(CherryPickCommitPreview commit, Set<String> skipped)
    {
        if (commit.alreadyOnTarget()) {
            return Disposition.ALREADY_ON_TARGET;
        }
        return skipped.contains(commit.sourceSha()) ? Disposition.USER_SKIPPED : Disposition.TO_CHERRY_PICK;
    }

    /** Persists Git's conflict evidence, then closes the operation as-is. */
    private CherryPickExecution commitConflictSnapshot(CherryPickExecution execution, SequenceState state)
    {
        String stopped = state.stoppedCommit().orElseThrow();
        CherryPickItem item = execution.items().stream()
                .filter(value -> value.sourceSha().equals(stopped))
                .findFirst()
                .orElse(null);
        if (item == null) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH, "Sequencer stopped on " + stopped + ", which is not in the confirmed plan");
        }
        CherryPickExecution withConflict = store.save(execution
                .recordConflict(stopped, conflictPaths(execution, state), clock.instant())
                .withStep(Step.RESOLVING, clock.instant())
                .withJournal(journal(ActionType.COMMIT_CONFLICT_SNAPSHOT, Optional.of(stopped), state.head()), clock.instant()));
        SequenceState after = sequencer.commitConflictSnapshot(execution.workspace().worktree(), state.head(), stopped, state.conflicts());
        return acceptConflictSnapshot(withConflict, after);
    }

    /** Recovers either side of the snapshot commit's intent/result boundary. */
    private CherryPickExecution recoverConflictSnapshot(CherryPickExecution execution)
    {
        ActionJournal journal = execution.journal().orElseThrow();
        SequenceState state = sequencer.observe(execution.workspace().worktree());
        if (state.kind() == SequenceState.Kind.STOPPED_CONFLICT) {
            CherryPickItem item = itemOf(execution, journal.sourceSha().orElseThrow());
            if (!item.conflictPaths().equals(conflictPaths(execution, state))) {
                return pause(execution, PauseReason.RECONCILIATION_MISMATCH, "Conflict index stages changed after they were persisted");
            }
            SequenceState after = sequencer.commitConflictSnapshot(execution.workspace().worktree(), journal.expectedHead(), journal.sourceSha().orElseThrow(), state.conflicts());
            return acceptConflictSnapshot(execution, after);
        }
        return acceptConflictSnapshot(execution, state);
    }

    private CherryPickExecution acceptConflictSnapshot(CherryPickExecution execution, SequenceState state)
    {
        ActionJournal journal = execution.journal().orElseThrow();
        String sourceSha = journal.sourceSha().orElseThrow();
        if (state.kind() != SequenceState.Kind.COMPLETED) {
            return pause(execution, PauseReason.UNSAFE_GIT_STATE, state.diagnostics().isBlank() ? "Conflict snapshot did not close cleanly" : state.diagnostics());
        }
        List<CreatedCommit> created = createdCommits(execution.workspace().worktree(), journal.expectedHead(), state.head());
        if (created.size() != 1
                || !created.getFirst().sourceSha().filter(sourceSha::equals).isPresent()
                || !parentOf(execution.workspace().worktree(), state.head()).equals(journal.expectedHead())) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH, "Conflict snapshot is not one direct -x commit for " + sourceSha);
        }
        return store.save(execution
                .withStep(Step.RESOLVING, clock.instant())
                .withJournal(journal(ActionType.AGENT_TURN, Optional.of(sourceSha), state.head()), clock.instant()));
    }

    /** Starts one narrow agent turn after ByteQuay has committed snapshot C. */
    private CherryPickExecution startAgentTurn(CherryPickExecution execution, String conflictCommit, Optional<String> fixupCommit, Optional<String> userNote)
    {
        String sourceSha = execution.journal().orElseThrow().sourceSha().orElseThrow();
        CherryPickItem current = itemOf(execution, sourceSha);
        if (current.agentTurnCount() >= CherryPickItem.MAX_AGENT_TURNS) {
            return pause(execution, PauseReason.RETRY_LIMIT_REACHED, "Conflict on " + sourceSha + " remains after " + CherryPickItem.MAX_AGENT_TURNS + " attempts");
        }
        int attempt = current.agentTurnCount() + 1;
        CherryPickExecution counted = store.save(execution
                .recordAgentTurnStarted(sourceSha, clock.instant())
                .withStep(Step.RESOLVING, clock.instant()));

        String prompt = CherryPickPromptBuilder.resolveConflict(
                current,
                parentOf(execution.workspace().worktree(), conflictCommit),
                conflictCommit,
                fixupCommit,
                attempt,
                guidance(execution, userNote));
        try {
            AgentTurn turn = turns.start(new StartAgentTurnCommand(
                    TaskId.valueOf(counted.agentTaskId().orElseThrow()),
                    prompt,
                    ImmutableList.of(),
                    turnKey(counted, current.position(), attempt)));
            return store.save(counted.withTurn(turn.id().toString(), clock.instant()).withLifecycle(CherryPickLifecycle::withoutNotes, clock.instant()));
        }
        catch (RuntimeException failure) {
            return pause(counted, PauseReason.AGENT_TURN_FAILED, "Cannot start an agent turn: " + failure.getMessage());
        }
    }

    /** Runs the build-and-test turn once every commit applied, then checks what it left in Git. */
    private CherryPickExecution build(CherryPickExecution execution)
    {
        if (execution.currentTurnId().isPresent()) {
            AgentTurn turn;
            try {
                turn = turns.get(TurnId.valueOf(execution.currentTurnId().orElseThrow()));
            }
            catch (RuntimeException failure) {
                return pause(execution, PauseReason.AGENT_TURN_FAILED, "Cannot read the agent turn: " + failure.getMessage());
            }
            if (turn.state().active()) {
                return execution;
            }
            return afterBuildTurn(store.save(execution.withoutTurn(clock.instant())), turn);
        }
        CherryPickExecution journalled = execution.journal().filter(entry -> entry.type() == ActionType.BUILD_TURN).isPresent()
                ? execution
                : store.save(execution.withJournal(journal(ActionType.BUILD_TURN, Optional.empty(), execution.currentTargetSha()), clock.instant()));
        int attempt = journalled.journal().orElseThrow().attempt();
        Path worktree = journalled.workspace().worktree();
        String tip = headOf(worktree);
        String prompt = CherryPickPromptBuilder.buildAndTest(
                journalled.workspace().targetBranch(),
                journalled.lifecycle().baseSha(),
                tip,
                touchedModules(worktree, journalled.lifecycle().baseSha(), tip),
                attempt,
                MAX_BUILD_TURNS,
                attempt == 1 ? Optional.empty() : inspectBuild(journalled).problem(),
                guidance(journalled, Optional.empty()));
        AgentTurn turn;
        try {
            turn = turns.start(new StartAgentTurnCommand(
                    TaskId.valueOf(journalled.agentTaskId().orElseThrow()),
                    prompt,
                    ImmutableList.of(),
                    journalled.id() + ":build:" + attempt));
        }
        catch (RuntimeException failure) {
            return pause(journalled, PauseReason.AGENT_TURN_FAILED, "Cannot start an agent turn: " + failure.getMessage());
        }
        CherryPickExecution started = store.save(journalled.withLifecycle(CherryPickLifecycle::withoutNotes, clock.instant()));
        // A repeated start returns the turn the first one began, which may already have ended.
        return turn.state().active() ? store.save(started.withTurn(turn.id().toString(), clock.instant())) : afterBuildTurn(started, turn);
    }

    private CherryPickExecution afterBuildTurn(CherryPickExecution execution, AgentTurn turn)
    {
        if (turn.state() != TurnState.COMPLETED) {
            return pause(execution, PauseReason.AGENT_TURN_FAILED, "The build turn ended " + turn.state() + "; the worktree is left as it is");
        }
        CherryPickBranch.Inspection inspection = inspectBuild(execution);
        if (inspection.problem().isPresent()) {
            ActionJournal journal = execution.journal().orElseThrow();
            if (journal.attempt() >= MAX_BUILD_TURNS) {
                return pause(execution, PauseReason.RECONCILIATION_MISMATCH, inspection.problem().orElseThrow());
            }
            return store.save(execution.withJournal(nextAttempt(journal), clock.instant()));
        }
        String head = headOf(execution.workspace().worktree());
        return store.save(execution.withoutJournal(clock.instant())
                .withTip(head, clock.instant())
                .withLifecycle(lifecycle -> lifecycle.inStage(Stage.PUBLISH).withBranch(lifecycle.baseSha(), inspection.commits()), clock.instant()));
    }

    private CherryPickBranch.Inspection inspectBuild(CherryPickExecution execution)
    {
        Path worktree = execution.workspace().worktree();
        if (!branch.clean(worktree)) {
            return CherryPickBranch.Inspection.broken("The agent left uncommitted changes or an open Git operation");
        }
        return branch.inspect(worktree, execution.lifecycle().baseSha(), headOf(worktree), CherryPickBranch.appliedSources(execution),
                execution.lifecycle().commits(), Origin.BUILD);
    }

    private List<String> touchedModules(Path worktree, String baseSha, String tipSha)
    {
        GitCommandRunner.Result changed = git.run(worktree, "diff", "--name-only", baseSha, tipSha);
        if (!changed.successful()) {
            return ImmutableList.of();
        }
        return changed.output().lines()
                .filter(line -> !line.isBlank())
                .map(path -> findModule(worktree, path))
                .flatMap(Optional::stream)
                .distinct()
                .limit(MAX_LISTED_MODULES)
                .collect(toImmutableList());
    }

    private ActionJournal nextAttempt(ActionJournal journal)
    {
        return new ActionJournal(journal.type(), journal.sourceSha(), journal.expectedHead(), journal.attempt() + 1, journal.idempotencyKey(), clock.instant());
    }

    /** Messages the user left for the agent's next step, then the one sent with this resume. */
    private static Optional<String> guidance(CherryPickExecution execution, Optional<String> userNote)
    {
        return Stream.concat(execution.lifecycle().notes().stream().map(Note::text), userNote.stream())
                .reduce((left, right) -> left + "\n\n" + right);
    }

    /** Verifies the agent-created F from Git, never from the agent's reply. */
    private CherryPickExecution inspectFixup(CherryPickExecution execution, Optional<String> userNote)
    {
        ActionJournal journal = execution.journal().orElseThrow();
        Path worktree = execution.workspace().worktree();
        boolean repair = journal.type() == ActionType.AGENT_REPAIR;
        String expectedHead = journal.expectedHead();
        String conflictCommit = repair ? parentOf(worktree, expectedHead) : expectedHead;
        String actualHead = headOf(worktree);
        if (actualHead.equals(expectedHead)) {
            if (operationOpenOrUnmerged(worktree)) {
                return pause(execution, PauseReason.UNSAFE_GIT_STATE, repair
                        ? "An unexpected Git operation is open while repairing the fixup"
                        : "An unexpected Git operation is open while waiting for the fixup");
            }
            return startAgentTurn(execution, conflictCommit, repair ? Optional.of(expectedHead) : Optional.empty(), userNote);
        }
        if (!parentOf(worktree, actualHead).equals(conflictCommit)) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH, repair
                    ? "The repair added history instead of amending the existing fixup"
                    : "The agent did not create exactly one direct child of the conflict snapshot");
        }
        return inspectFixupCandidate(execution, conflictCommit, actualHead);
    }

    private CherryPickExecution inspectFixupCandidate(CherryPickExecution execution, String conflictCommit, String fixupCommit)
    {
        Path worktree = execution.workspace().worktree();
        ActionJournal journal = execution.journal().orElseThrow();
        String expectedSubject = "fixup! " + subjectOf(worktree, conflictCommit);
        if (!subjectOf(worktree, fixupCommit).equals(expectedSubject)) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH, "The agent commit is not the expected direct fixup");
        }
        if (CherryPickBranch.originSha(messageOf(worktree, fixupCommit)).isPresent()) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH, "The fixup copied a cherry-pick origin line");
        }
        String originalHead = parentOf(worktree, conflictCommit);
        if (sequencer.observe(worktree).kind() != SequenceState.Kind.COMPLETED
                || !git.withoutConflictMarkers(worktree, originalHead, fixupCommit)) {
            return store.save(execution.withJournal(journal(ActionType.AGENT_REPAIR, journal.sourceSha(), fixupCommit), clock.instant()));
        }
        return store.save(execution.withJournal(journal(ActionType.FINALIZE_CONFLICT_PAIR, journal.sourceSha(), fixupCommit), clock.instant()));
    }

    /** Records the verified C/F pair without rewriting either commit. */
    private CherryPickExecution finalizeConflictPair(CherryPickExecution execution)
    {
        ActionJournal journal = execution.journal().orElseThrow();
        Path worktree = execution.workspace().worktree();
        String fixupCommit = journal.expectedHead();
        String conflictCommit = parentOf(worktree, fixupCommit);
        String originalHead = parentOf(worktree, conflictCommit);
        if (!originalHead.equals(execution.currentTargetSha())) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH, "Conflict pair no longer follows the recorded target tip");
        }
        SequenceState state = sequencer.observe(worktree);
        if (state.kind() != SequenceState.Kind.COMPLETED || !state.head().equals(fixupCommit)) {
            return pause(execution, PauseReason.UNSAFE_GIT_STATE, "Conflict pair moved before it could be recorded");
        }
        CherryPickExecution updated = store.save(execution
                .recordAppliedWithFixup(journal.sourceSha().orElseThrow(), conflictCommit, fixupCommit, clock.instant())
                .withStep(Step.APPLYING, clock.instant())
                .withoutJournal(clock.instant()));
        return updated.pendingItems().isEmpty() ? complete(updated) : updated;
    }

    /**
     * Reads what the turn left behind, never what it said.
     */
    private CherryPickExecution afterTurn(CherryPickExecution execution)
    {
        AgentTurn turn;
        try {
            turn = turns.get(TurnId.valueOf(execution.currentTurnId().orElseThrow()));
        }
        catch (RuntimeException failure) {
            return pause(execution, PauseReason.AGENT_TURN_FAILED, "Cannot read the agent turn: " + failure.getMessage());
        }
        if (turn.state().active()) {
            return execution;
        }
        CherryPickExecution cleared = store.save(execution.withoutTurn(clock.instant()));
        if (turn.state() != TurnState.COMPLETED) {
            return pause(cleared, PauseReason.AGENT_TURN_FAILED, "Agent turn ended " + turn.state() + "; the worktree is left as it is");
        }
        // The turn finishing says nothing about the repository. Look.
        return act(cleared);
    }

    private static List<ConflictPath> conflictPaths(CherryPickExecution execution, SequenceState state)
    {
        Path worktree = execution.workspace().worktree();
        return state.conflicts().stream()
                .map(conflict -> new ConflictPath(
                        conflict.path(),
                        switch (conflict.type()) {
                            case CONTENT -> ConflictType.CONTENT;
                            case ADD_ADD -> ConflictType.ADD_ADD;
                            case MODIFY_DELETE -> ConflictType.MODIFY_DELETE;
                            case SUBMODULE -> ConflictType.SUBMODULE;
                        },
                        findModule(worktree, conflict.path()),
                        conflict.stages().stream()
                                .map(stage -> new IndexStage(stage.stage(), stage.mode(), stage.blobSha()))
                                .collect(toImmutableList())))
                .collect(toImmutableList());
    }

    private static CherryPickItem itemOf(CherryPickExecution execution, String sourceSha)
    {
        return execution.items().stream()
                .filter(item -> item.sourceSha().equals(sourceSha))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("commit is not in the plan: " + sourceSha));
    }

    /** Stable per attempt, so a retried advance cannot start a second turn. */
    private static String turnKey(CherryPickExecution execution, int position, int attempt)
    {
        return execution.id() + ":" + position + ":" + attempt;
    }

    private AgentTask agentTask(
            TaskId taskId,
            String workspaceId,
            AgentLaneId laneId,
            RepositoryIdentity physicalRepository,
            String startCommit,
            String generatedBranch,
            AgentTaskDefaults defaults)
    {
        return tasks.create(new CreateAgentTaskCommand(taskId, workspaceId, laneId, physicalRepository, startCommit, generatedBranch, "Cherry-pick task " + taskId, defaults));
    }

    private Path plannedWorktree(String workspaceId, TaskId taskId)
    {
        try {
            return workspacePaths.canonicalTaskWorktree(workspaceId, taskId.toString());
        }
        catch (IOException failure) {
            throw new IllegalArgumentException("Managed repository path cannot be resolved");
        }
    }

    private AgentTask ensureAgentTask(CherryPickExecution execution)
    {
        AgentTaskDefaults defaults = new AgentTaskDefaults(execution.workspace().model(), execution.workspace().effort(), AgentPermissionPolicy.CHERRY_PICK);
        TaskId taskId = TaskId.valueOf(execution.agentTaskId().orElseThrow());
        String workspaceId = execution.workspace().workspaceId();
        RepositoryIdentity selectedRepository = repositoryIdentity(workspaceRepositories.requireReadyRepository(workspaceId).selectedRepository());
        RepositoryIdentity repository = repositoryIdentity(execution.workspace().physicalRepository());
        requireSelectedRepository(selectedRepository, repository, CherryPickConflictException::new);
        AgentTask task;
        try {
            task = tasks.get(taskId);
        }
        catch (AgentTaskNotFoundException ignored) {
            task = createOrGet(tasks, taskId, () -> agentTask(
                    taskId,
                    workspaceId,
                    new AgentLaneId(execution.workspace().laneId()),
                    repository,
                    execution.range().initialTargetSha(),
                    execution.workspace().workBranch(),
                    defaults));
        }
        return requireAgentTask(execution, task, repository);
    }

    private static AgentTask requireAgentTask(CherryPickExecution execution, AgentTask task, RepositoryIdentity repository)
    {
        TaskId taskId = TaskId.valueOf(execution.agentTaskId().orElseThrow());
        String workspaceId = execution.workspace().workspaceId();
        TaskWorktree worktree = task.taskWorktree().orElseThrow(() -> new CherryPickConflictException("Cherry-pick agent task has no managed worktree"));
        if (!task.id().equals(taskId)
                || task.interactive()
                || task.workspaceId().stream().noneMatch(workspaceId::equals)
                || !worktree.workspaceId().equals(workspaceId)
                || !task.laneId().equals(new AgentLaneId(execution.workspace().laneId()))
                || task.permissionPolicy() != AgentPermissionPolicy.CHERRY_PICK
                || !task.repository().equals(repository)
                || !worktree.physicalRepository().equals(repository)
                || !worktree.canonicalWorktree().equals(execution.workspace().worktree())
                || !worktree.generatedBranch().equals(execution.workspace().workBranch())
                || !worktree.startCommit().equals(execution.range().initialTargetSha())) {
            throw new CherryPickConflictException("Cherry-pick agent task does not match the execution");
        }
        return task;
    }

    /**
     * Accepts a finished range only from the plan, the stored results, and Git.
     *
     * <p>The range review is produced for a person to read afterwards. It is
     * never consulted here: {@code range-diff} is heuristic, and letting a
     * heuristic decide completion would undo everything else.
     */
    private CherryPickExecution complete(CherryPickExecution execution)
    {
        Path worktree = execution.workspace().worktree();
        if (!execution.pendingItems().isEmpty()) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH, "Items remain unfinished");
        }
        SequenceState state = sequencer.observe(worktree);
        if (state.kind() != SequenceState.Kind.COMPLETED) {
            return pause(execution, PauseReason.UNSAFE_GIT_STATE, "Cannot finish while a Git operation is open or the worktree is dirty");
        }

        List<CherryPickItem> applied = execution.items().stream()
                .filter(item -> item.result() == CherryPickItem.Result.APPLIED)
                .collect(toImmutableList());
        List<CreatedCommit> created = createdCommits(worktree, execution.range().initialTargetSha(), state.head());
        if (!state.head().equals(execution.currentTargetSha())) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH, "The work branch no longer ends at the recorded target tip");
        }
        int createdIndex = 0;
        for (CherryPickItem item : applied) {
            if (createdIndex >= created.size()) {
                return pause(execution, PauseReason.RECONCILIATION_MISMATCH, "The work branch is missing the target for source " + item.sourceSha());
            }
            CreatedCommit commit = created.get(createdIndex++);
            if (!commit.sourceSha().filter(sha -> sha.equals(item.sourceSha())).isPresent()) {
                return pause(execution, PauseReason.RECONCILIATION_MISMATCH, "Commit " + commit.targetSha() + " does not record source " + item.sourceSha());
            }
            if (!commit.targetSha().equals(item.targetSha().orElse(null))) {
                return pause(execution, PauseReason.RECONCILIATION_MISMATCH, "Recorded target " + item.targetSha().orElse("none") + " is not the commit on the branch");
            }
            if (!item.conflictPaths().isEmpty() && createdIndex < created.size()) {
                CreatedCommit candidate = created.get(createdIndex);
                if (candidate.sourceSha().isEmpty()
                        && parentOf(worktree, candidate.targetSha()).equals(commit.targetSha())
                        && subjectOf(worktree, candidate.targetSha())
                                .equals("fixup! " + subjectOf(worktree, commit.targetSha()))) {
                    createdIndex++;
                }
            }
        }
        if (createdIndex != created.size()) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH, "The work branch holds " + (created.size() - createdIndex) + " unrecorded commit(s)");
        }
        if (!git.withoutConflictMarkers(worktree, execution.range().initialTargetSha(), state.head())) {
            return pause(execution, PauseReason.UNSAFE_GIT_STATE, "A created commit still contains a conflict marker");
        }

        CherryPickExecution settled = execution.journal().isPresent() ? store.save(execution.withoutJournal(clock.instant())) : execution;
        if (settled.lifecycle().delivers()) {
            CherryPickBranch.Inspection inspection = branch.inspect(worktree, execution.range().initialTargetSha(), state.head(),
                    CherryPickBranch.appliedSources(settled), ImmutableList.of(), Origin.CONFLICT);
            if (inspection.problem().isPresent()) {
                return pause(settled, PauseReason.RECONCILIATION_MISMATCH, inspection.problem().orElseThrow());
            }
            return store.save(settled
                    .withLifecycle(lifecycle -> lifecycle.inStage(Stage.BUILD).withBranch(lifecycle.baseSha(), inspection.commits()), clock.instant())
                    .withJournal(journal(ActionType.BUILD_TURN, Optional.empty(), state.head()), clock.instant()));
        }
        completeAgentTask(settled);
        CherryPickExecution finished = store.save(settled.finish(FinishReason.COMPLETED, summary(settled), clock.instant()));
        cleanupAgentTask(finished);
        return finished;
    }

    private void completeAgentTask(CherryPickExecution execution)
    {
        execution.agentTaskId().ifPresent(taskId -> completeAgentTask(TaskId.valueOf(taskId), summary(execution)));
    }

    private void cleanupAgentTask(CherryPickExecution execution)
    {
        execution.agentTaskId().ifPresent(taskId -> {
            try {
                completions.cleanupWorktree(TaskId.valueOf(taskId));
            }
            catch (RuntimeException failure) {
                log.warn("Cannot clean up agent task {} for execution {}", taskId, execution.id(), failure);
            }
        });
    }

    private void finishAgentTask(CherryPickExecution execution)
    {
        try {
            completeAgentTask(execution);
        }
        catch (RuntimeException failure) {
            log.warn("Cannot complete agent task for finished execution {}", execution.id(), failure);
            return;
        }
        cleanupAgentTask(execution);
    }

    private void completeAgentTask(TaskId taskId, String taskSummary)
    {
        completions.complete(new CompleteAgentTaskCommand(taskId, Optional.of(taskSummary)));
    }

    /**
     * Gives the agent one bounded message.
     *
     * <p>A paused execution resumes with it. A stopped CI round or rebase takes it and continues.
     * A running CI turn receives it now. Otherwise it waits for the agent's next step. The text is
     * quoted into a prompt as an untrusted note: it cannot change the plan, the target, the order,
     * or the skip list.
     *
     * @param executionId unfinished execution
     * @param message user text
     * @return the execution after the message was accepted
     */
    public CherryPickExecution sendMessage(CherryPickExecutionId executionId, String message)
    {
        requireNonNull(message, "message is null");
        checkArgument(!message.isBlank() && message.length() <= MAX_MESSAGE_CHARACTERS, "message must contain between 1 and " + MAX_MESSAGE_CHARACTERS + " characters");
        Lock lock = advanceLocks.get(executionId.toString());
        return withLock(lock, () -> {
            CherryPickExecution execution = get(executionId);
            if (execution.status() == Status.FINISHED) {
                throw new CherryPickConflictException("Execution is finished");
            }
            if (execution.status() == Status.PAUSED) {
                return resumePaused(execution, Optional.of(message));
            }
            Optional<Reason> held = execution.lifecycle().hold().map(Hold::reason);
            if (held.filter(reason -> reason == Reason.CI_FAILED || reason == Reason.REBASE_FAILED).isPresent()) {
                return delivery.resume(execution, Optional.of(message));
            }
            if (held.isEmpty() && delivery.steerCi(execution, message)) {
                return get(executionId);
            }
            return store.save(execution.withLifecycle(lifecycle -> lifecycle.withNote(new Note(message, clock.instant())), clock.instant()));
        });
    }

    /** Holds the execution after its current step; a running CI round stops so nothing more is pushed. */
    public CherryPickExecution pause(CherryPickExecutionId executionId)
    {
        Lock lock = advanceLocks.get(executionId.toString());
        return withLock(lock, () -> {
            CherryPickExecution execution = get(executionId);
            if (execution.status() == Status.FINISHED) {
                throw new CherryPickConflictException("Execution is finished");
            }
            if (execution.status() == Status.PAUSED || execution.lifecycle().hold().isPresent()) {
                return execution;
            }
            CherryPickExecution held = store.save(execution.withLifecycle(lifecycle -> lifecycle.held(Reason.USER, "Paused by you", clock.instant()), clock.instant()));
            if (held.lifecycle().delivers()) {
                delivery.pause(held);
            }
            return held;
        });
    }

    /** Continues a paused or held execution from where it stopped. */
    public CherryPickExecution resume(CherryPickExecutionId executionId)
    {
        Lock lock = advanceLocks.get(executionId.toString());
        return withLock(lock, () -> {
            CherryPickExecution execution = get(executionId);
            if (execution.status() == Status.FINISHED) {
                throw new CherryPickConflictException("Execution is finished");
            }
            if (execution.status() == Status.PAUSED) {
                return resumePaused(execution, Optional.empty());
            }
            return execution.lifecycle().hold().isPresent() ? delivery.resume(execution, Optional.empty()) : execution;
        });
    }

    /**
     * Records the user's approval to fold the fixups into their commits.
     *
     * @param executionId execution waiting in its squash stage
     * @param expectedHead the branch tip the user reviewed
     * @return the execution with the approval recorded; the monitor folds and pushes
     */
    public CherryPickExecution approveSquash(CherryPickExecutionId executionId, String expectedHead)
    {
        requireNonNull(expectedHead, "expectedHead is null");
        Lock lock = advanceLocks.get(executionId.toString());
        return withLock(lock, () -> {
            CherryPickExecution execution = get(executionId);
            CherryPickLifecycle lifecycle = execution.lifecycle();
            if (execution.status() != Status.RUNNING || lifecycle.stage() != Stage.SQUASH || lifecycle.hold().isPresent() || lifecycle.fixups().isEmpty()) {
                throw new CherryPickConflictException("Nothing is waiting for a squash approval");
            }
            if (!execution.currentTargetSha().equals(expectedHead)) {
                throw new CherryPickConflictException("The branch changed; review the fixups again");
            }
            if (lifecycle.squash().isPresent()) {
                return execution;
            }
            Squash approval = new Squash(expectedHead, clock.instant(), Optional.empty(), ImmutableList.of());
            return store.save(execution.withLifecycle(value -> value.withSquash(Optional.of(approval)), clock.instant()));
        });
    }

    private CherryPickExecution resumePaused(CherryPickExecution execution, Optional<String> message)
    {
        if (execution.lifecycle().stage() == Stage.BUILD) {
            CherryPickExecution resumed = store.save(execution.resume(clock.instant()));
            CherryPickExecution noted = message.isEmpty() ? resumed
                    : store.save(resumed.withLifecycle(lifecycle -> lifecycle.withNote(new Note(message.orElseThrow(), clock.instant())), clock.instant()));
            // A new attempt: the paused one's turn key would return the turn that already failed.
            return noted.journal().filter(entry -> entry.type() == ActionType.BUILD_TURN)
                    .map(entry -> store.save(noted.withJournal(nextAttempt(entry), clock.instant())))
                    .orElse(noted);
        }
        if (execution.cursor().isEmpty()) {
            // Paused while checking the finished range; the monitor checks it again.
            return store.save(execution.resume(clock.instant()));
        }
        // Look before resuming: the user may have fixed it by hand.
        SequenceState state = sequencer.observe(execution.workspace().worktree());
        if (state.kind() == SequenceState.Kind.UNSAFE) {
            throw new CherryPickConflictException("Git is in an unexpected state: " + state.diagnostics());
        }
        CherryPickExecution resumed = store.save(execution.resume(clock.instant()));
        if (resumed.journal().map(ActionJournal::type).filter(type -> type == ActionType.AGENT_TURN || type == ActionType.AGENT_REPAIR).isPresent()) {
            return inspectFixup(resumed, message);
        }
        if (state.kind() == SequenceState.Kind.STOPPED_CONFLICT) {
            CherryPickExecution snapshotted = commitConflictSnapshot(resumed, state);
            if (snapshotted.status() == Status.RUNNING
                    && snapshotted.journal().map(ActionJournal::type)
                            .filter(ActionType.AGENT_TURN::equals).isPresent()) {
                return inspectFixup(snapshotted, message);
            }
            return snapshotted;
        }
        // Nothing for an agent to resolve; let the monitor reconcile it.
        return resumed;
    }

    private record CreatedCommit(String targetSha, Optional<String> sourceSha)
    {
        private CreatedCommit
        {
            requireNonNull(targetSha, "targetSha is null");
            requireNonNull(sourceSha, "sourceSha is null");
        }
    }

    static Optional<String> findModule(Path worktree, String path)
    {
        Path directory = worktree.resolve(path).getParent();
        while (directory != null && directory.startsWith(worktree) && !directory.equals(worktree)) {
            if (Files.isRegularFile(directory.resolve("pom.xml"))) {
                return Optional.of(worktree.relativize(directory).toString());
            }
            directory = directory.getParent();
        }
        return Optional.empty();
    }
}
