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
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitSequencerDriver;
import com.bytequay.app.git.GitSequencerDriver.NormalizationResult;
import com.bytequay.app.git.SequenceState;
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
import com.bytequay.core.cherrypick.CherryPickInvalidRequestException;
import com.bytequay.core.cherrypick.CherryPickItem;
import com.bytequay.core.cherrypick.CherryPickItem.ConflictPath;
import com.bytequay.core.cherrypick.CherryPickItem.ConflictType;
import com.bytequay.core.cherrypick.CherryPickItem.Disposition;
import com.bytequay.core.cherrypick.CherryPickItem.IndexStage;
import com.bytequay.core.cherrypick.CherryPickNotFoundException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;

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
    private static final String ORIGIN_PREFIX = "(cherry picked from commit ";
    private static final String FIELD = "\u001f";
    private static final String RECORD = "\u001e";

    private final CherryPickPreviewService previews;
    private final CherryPickExecutionStore store;
    private final CherryPickWorkspace workspaces;
    private final GitSequencerDriver sequencer;
    private final GitCommandRunner git;
    private final AgentTaskService tasks;
    private final AgentTurnService turns;
    private final AgentCompletionService completions;
    private final Clock clock;
    private final Striped<Lock> advanceLocks = Striped.lock(64);

    public CherryPickExecutionService(
            CherryPickPreviewService previews,
            CherryPickExecutionStore store,
            CherryPickWorkspace workspaces,
            GitSequencerDriver sequencer,
            GitCommandRunner git,
            AgentTaskService tasks,
            AgentTurnService turns,
            AgentCompletionService completions,
            Clock clock)
    {
        this.previews = requireNonNull(previews, "previews is null");
        this.store = requireNonNull(store, "store is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.sequencer = requireNonNull(sequencer, "sequencer is null");
        this.git = requireNonNull(git, "git is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.completions = requireNonNull(completions, "completions is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Freezes a confirmed plan and prepares the workspace it will run in.
     *
     * @param command confirmed skips and the object IDs they were chosen against
     * @param workspaceId durable workspace that owns the managed clone
     * @param repository clone that owns the objects
     * @param worktreeParent directory that will hold the generated worktree
     * @return the durable execution
     */
    public CherryPickExecution start(
            StartCherryPickExecutionCommand command,
            String workspaceId,
            Path repository,
            Path worktreeParent)
    {
        requireNonNull(command, "command is null");
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(repository, "repository is null");
        requireNonNull(worktreeParent, "worktreeParent is null");

        // Answer a repeated start before creating anything: the live-execution
        // check below would otherwise reject the caller's own execution.
        Optional<CherryPickExecution> existing = store.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            CherryPickExecution execution = existing.orElseThrow();
            requireWorkspace(execution.workspace().workspaceId(), workspaceId);
            return execution;
        }

        CherryPickPreview preview = previews.find(command.previewId())
                .orElseThrow(() -> new CherryPickInvalidRequestException(
                        "Preview is unknown or expired; preview the range again"));
        requireWorkspace(preview.workspaceId(), workspaceId);
        requireUnmoved(preview, command);
        previews.requireCurrentTarget(repository, preview);
        List<CherryPickItem> items = plan(preview, command.userSkippedSourceShas());
        requireNoLiveExecution(preview, workspaceId);

        CherryPickWorkspace.Created created = workspaces.create(
                repository,
                worktreeParent,
                preview.sourceBaseSha(),
                preview.sourceTipSha(),
                preview.targetSha());
        try {
            requireOnItsBranch(created);
            CherryPickExecution created0 = store.createOrGet(CherryPickExecution.create(
                    CherryPickExecutionId.random(),
                    command.idempotencyKey(),
                    new Workspace(
                            Optional.of(workspaceId),
                            preview.repository(),
                            preview.sourceRemote(),
                            preview.targetRemote(),
                            preview.targetBranch(),
                            preview.localTargetBranch(),
                            created.branch(),
                            created.worktree(),
                            command.laneId()),
                    Optional.of(command.pullRequestDraft()),
                    new Range(preview.sourceBaseSha(), preview.sourceTipSha(), preview.targetSha()),
                    items,
                    clock.instant()));
            requireWorkspace(created0.workspace().workspaceId(), workspaceId);
            // Created while the worktree is still clean, even though a clean
            // range never uses a turn.
            CherryPickExecution execution = created0.agentTaskId().isPresent()
                    ? created0
                    : store.save(created0.withAgentTask(agentTask(created0).toString(), clock.instant()));
            if (!execution.workspace().workBranch().equals(created.branch())) {
                // A repeated idempotency key returns the original execution, so
                // the workspace this call created is surplus.
                workspaces.remove(repository, created);
            }
            return execution;
        }
        catch (RuntimeException failure) {
            workspaces.remove(repository, created);
            throw failure;
        }
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
     * Stops future work and removes the branch and worktree ByteQuay created.
     *
     * <p>They are ByteQuay's own, not user work, and the local target branch
     * never received a cherry-pick, so nothing outside them needs repair.
     *
     * @param executionId execution identifier
     * @param workspaceId durable workspace that owns the execution
     * @param repository clone that owns the worktree
     * @return the finished execution
     */
    public CherryPickExecution cancel(CherryPickExecutionId executionId, String workspaceId, Path repository)
    {
        Lock lock = advanceLocks.get(executionId.toString());
        lock.lock();
        try {
            CherryPickExecution execution = get(executionId);
            requireWorkspace(execution.workspace().workspaceId(), workspaceId);
            if (execution.status() == Status.FINISHED) {
                return execution;
            }
            execution.currentTurnId().ifPresent(turnId -> {
                try {
                    turns.cancel(TurnId.valueOf(turnId));
                }
                catch (RuntimeException failure) {
                    log.warn("Cannot cancel turn {} for execution {}", turnId, execution.id(), failure);
                }
            });
            if (!ownsWorktree(execution)) {
                // Refuse to delete something that is no longer recognisably ours.
                return pause(execution, PauseReason.UNSAFE_GIT_STATE,
                        "Worktree is not on " + execution.workspace().workBranch()
                                + "; cancel left it untouched for you to inspect");
            }
            completeAgentTask(execution);
            workspaces.remove(repository, new CherryPickWorkspace.Created(
                    execution.workspace().workBranch(), execution.workspace().worktree()));
            return store.save(execution.finish(FinishReason.CANCELLED, "Cancelled", clock.instant()));
        }
        finally {
            lock.unlock();
        }
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
                return execution;
            }
            if (execution.pendingItems().isEmpty()) {
                return complete(execution);
            }
            return act(execution);
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
        if (execution.journal().map(ActionJournal::type).filter(ActionType.AGENT_TURN::equals).isPresent()) {
            return inspectFixup(execution, Optional.empty());
        }
        if (execution.journal().map(ActionJournal::type).filter(ActionType.AGENT_REPAIR::equals).isPresent()) {
            return inspectRepair(execution, Optional.empty());
        }
        if (execution.journal().map(ActionJournal::type).filter(ActionType.FINALIZE_CONFLICT_PAIR::equals)
                .isPresent()) {
            return finalizeConflictPair(execution);
        }
        if (execution.journal().map(ActionJournal::type).filter(ActionType.NORMALIZE_CONFLICT_PAIR::equals)
                .isPresent()) {
            return normalizeConflictPair(execution);
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
            return reconcile(
                    started,
                    expectedHead,
                    sequencer.beginCherryPick(worktree, expectedHead, next));
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
            return pause(execution, PauseReason.UNSAFE_GIT_STATE,
                    "A conflict was staged outside ByteQuay before its index stages were recorded");
        }
        return pause(execution, PauseReason.UNSAFE_GIT_STATE,
                before.diagnostics().isBlank() ? "Unexpected Git state" : before.diagnostics());
    }

    private CherryPickExecution recoverEmptySkip(CherryPickExecution execution)
    {
        ActionJournal journal = execution.journal().orElseThrow();
        String sourceSha = journal.sourceSha().orElseThrow();
        String expectedHead = journal.expectedHead();
        CherryPickItem item = execution.items().stream()
                .filter(candidate -> candidate.sourceSha().equals(sourceSha))
                .findFirst()
                .orElseThrow();
        SequenceState state = sequencer.observe(execution.workspace().worktree());

        if (item.result() == CherryPickItem.Result.EMPTY_SKIPPED) {
            if (state.kind() != SequenceState.Kind.COMPLETED || !state.head().equals(expectedHead)) {
                return pause(execution, PauseReason.UNSAFE_GIT_STATE,
                        "The worktree moved after the empty result was recorded");
            }
            return execution.pendingItems().isEmpty()
                    ? complete(execution)
                    : store.save(execution.withoutJournal(clock.instant()));
        }
        if (item.result() != CherryPickItem.Result.PENDING) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                    "Empty-skip journal points to a completed item");
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
            return pause(execution, PauseReason.UNSAFE_GIT_STATE,
                    "Empty skip is not at its recorded before or after state");
        }
        if (after.kind() != SequenceState.Kind.COMPLETED || !after.head().equals(expectedHead)) {
            return pause(execution, PauseReason.UNSAFE_GIT_STATE,
                    "Empty skip did not close cleanly at " + expectedHead);
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
    private CherryPickExecution reconcile(
            CherryPickExecution execution,
            String expectedHead,
            SequenceState after)
    {
        Path worktree = execution.workspace().worktree();
        if (after.kind() == SequenceState.Kind.UNSAFE) {
            return pause(execution, PauseReason.UNSAFE_GIT_STATE, after.diagnostics());
        }
        List<CreatedCommit> created = createdCommits(worktree, expectedHead, after.head());
        if (created.size() > 1) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                    "One source commit produced " + created.size() + " commits");
        }
        Optional<CreatedCommit> applied = Optional.empty();
        if (!created.isEmpty()) {
            CreatedCommit commit = created.getFirst();
            if (commit.sourceSha().isEmpty()) {
                return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                        "Commit " + commit.targetSha() + " carries no cherry-pick origin line");
            }
            String expectedSource = execution.pendingItems().getFirst().sourceSha();
            if (!commit.sourceSha().orElseThrow().equals(expectedSource)) {
                return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                        "Commit " + commit.targetSha() + " records " + commit.sourceSha().orElseThrow()
                                + " but the plan expects " + expectedSource);
            }
            if (!parentOf(worktree, commit.targetSha()).equals(expectedHead)) {
                return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                        "Commit " + commit.targetSha() + " is not a child of " + expectedHead);
            }
            applied = Optional.of(commit);
        }
        if (applied.isPresent() && !sequencer.withoutConflictMarkers(worktree, expectedHead, after.head())) {
            return pause(execution, PauseReason.UNSAFE_GIT_STATE,
                    "A created commit still contains a conflict marker");
        }
        if (applied.isPresent() && execution.pendingItems().getFirst().agentTurnCount() > 0) {
            validate(worktree, after.head());
        }

        CherryPickExecution updated = applied
                .map(commit -> store.save(execution.recordApplied(
                        commit.sourceSha().orElseThrow(), commit.targetSha(), clock.instant())))
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
        GitCommandRunner.Result read = git.run(
                worktree, "log", "--reverse", "--format=" + RECORD + "%H" + FIELD + "%B", fromSha + ".." + toSha);
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
            created.add(new CreatedCommit(fields[0].strip(), originSha(fields[1])));
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
        for (String marker : ImmutableList.of(
                "CHERRY_PICK_HEAD", "MERGE_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply")) {
            GitCommandRunner.Result path = git.run(worktree, "rev-parse", "--git-path", marker);
            if (path.successful()
                    && java.nio.file.Files.exists(worktree.resolve(path.output().strip()).normalize())) {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> originSha(String message)
    {
        for (String line : message.split("\n")) {
            String value = line.strip();
            if (value.startsWith(ORIGIN_PREFIX) && value.endsWith(")")) {
                String sha = value.substring(ORIGIN_PREFIX.length(), value.length() - 1).strip();
                if (sha.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
                    return Optional.of(sha.toLowerCase(Locale.ROOT));
                }
            }
        }
        return Optional.empty();
    }

    private CherryPickExecution pause(CherryPickExecution execution, PauseReason reason, String diagnostics)
    {
        log.info("Cherry-pick execution {} paused: {}", execution.id(), diagnostics);
        return store.save(execution.pause(
                reason, diagnostics.isBlank() ? reason.name() : diagnostics, clock.instant()));
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
        if (!preview.sourceBaseSha().equals(command.expectedSourceBaseSha())
                || !preview.sourceTipSha().equals(command.expectedSourceTipSha())
                || !preview.targetSha().equals(command.expectedTargetSha())) {
            throw new CherryPickInvalidRequestException(
                    "Preview is stale; review a refreshed preview before confirming");
        }
    }

    private void requireNoLiveExecution(CherryPickPreview preview, String workspaceId)
    {
        boolean live = store.listRunning(CherryPickExecutionStore.MAX_LIST_LIMIT).stream()
                .anyMatch(execution -> execution.workspace().workspaceId()
                        .map(workspaceId::equals)
                        .orElseGet(() -> execution.workspace().repository().equals(preview.repository()))
                        && execution.workspace().localTargetBranch().equals(preview.localTargetBranch()));
        if (live) {
            throw new CherryPickConflictException(
                    "Another cherry-pick execution is already running for " + preview.localTargetBranch());
        }
    }

    private static void requireWorkspace(Optional<String> actualWorkspaceId, String expectedWorkspaceId)
    {
        if (actualWorkspaceId.filter(expectedWorkspaceId::equals).isEmpty()) {
            throw new CherryPickInvalidRequestException("Cherry-pick data does not belong to this workspace");
        }
    }

    private void requireOnItsBranch(CherryPickWorkspace.Created created)
    {
        GitCommandRunner.Result branch = git.run(
                created.worktree(), "symbolic-ref", "--quiet", "--short", "HEAD");
        if (!branch.successful() || !branch.output().strip().equals(created.branch())) {
            throw new CherryPickConflictException(
                    "Worktree is not on " + created.branch() + "; commits would reach no branch");
        }
    }

    private static List<CherryPickItem> plan(CherryPickPreview preview, List<String> userSkipped)
    {
        Set<String> skipped = ImmutableSet.copyOf(userSkipped);
        for (String sha : skipped) {
            boolean eligible = preview.commits().stream()
                    .anyMatch(commit -> commit.sourceSha().equals(sha) && !commit.alreadyOnTarget());
            if (!eligible) {
                throw new CherryPickInvalidRequestException(
                        "Skipped commit is not an eligible commit in this preview: " + sha);
            }
        }
        return preview.commits().stream()
                .map(commit -> CherryPickItem.planned(
                        commit.position(),
                        commit.sourceSha(),
                        commit.sourceParentSha(),
                        commit.title(),
                        commit.pullRequest().map(pullRequest -> new CherryPickItem.PullRequestRefView(
                                pullRequest.number(), pullRequest.title(), pullRequest.url())),
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
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                    "Sequencer stopped on " + stopped + ", which is not in the confirmed plan");
        }
        CherryPickExecution withConflict = store.save(execution
                .recordConflict(stopped, conflictPaths(execution, state), clock.instant())
                .withStep(Step.RESOLVING, clock.instant())
                .withJournal(journal(
                        ActionType.COMMIT_CONFLICT_SNAPSHOT, Optional.of(stopped), state.head()), clock.instant()));
        SequenceState after = sequencer.commitConflictSnapshot(
                execution.workspace().worktree(), state.head(), stopped, state.conflicts());
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
                return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                        "Conflict index stages changed after they were persisted");
            }
            SequenceState after = sequencer.commitConflictSnapshot(
                    execution.workspace().worktree(),
                    journal.expectedHead(),
                    journal.sourceSha().orElseThrow(),
                    state.conflicts());
            return acceptConflictSnapshot(execution, after);
        }
        return acceptConflictSnapshot(execution, state);
    }

    private CherryPickExecution acceptConflictSnapshot(CherryPickExecution execution, SequenceState state)
    {
        ActionJournal journal = execution.journal().orElseThrow();
        String sourceSha = journal.sourceSha().orElseThrow();
        if (state.kind() != SequenceState.Kind.COMPLETED) {
            return pause(execution, PauseReason.UNSAFE_GIT_STATE,
                    state.diagnostics().isBlank() ? "Conflict snapshot did not close cleanly" : state.diagnostics());
        }
        List<CreatedCommit> created = createdCommits(
                execution.workspace().worktree(), journal.expectedHead(), state.head());
        if (created.size() != 1
                || !created.getFirst().sourceSha().filter(sourceSha::equals).isPresent()
                || !parentOf(execution.workspace().worktree(), state.head()).equals(journal.expectedHead())) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                    "Conflict snapshot is not one direct -x commit for " + sourceSha);
        }
        return store.save(execution
                .withStep(Step.RESOLVING, clock.instant())
                .withJournal(journal(ActionType.AGENT_TURN, Optional.of(sourceSha), state.head()), clock.instant()));
    }

    /** Starts one narrow agent turn after ByteQuay has committed snapshot C. */
    private CherryPickExecution startAgentTurn(
            CherryPickExecution execution,
            String conflictCommit,
            Optional<String> fixupCommit,
            Optional<String> userNote)
    {
        String sourceSha = execution.journal().orElseThrow().sourceSha().orElseThrow();
        CherryPickItem current = itemOf(execution, sourceSha);
        if (current.agentTurnCount() >= CherryPickItem.MAX_AGENT_TURNS) {
            return pause(execution, PauseReason.RETRY_LIMIT_REACHED,
                    "Conflict on " + sourceSha + " remains after "
                            + CherryPickItem.MAX_AGENT_TURNS + " attempts");
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
                userNote);
        try {
            AgentTurn turn = turns.start(new StartAgentTurnCommand(
                    TaskId.valueOf(counted.agentTaskId().orElseThrow()),
                    prompt,
                    ImmutableList.of(),
                    turnKey(counted, current.position(), attempt)));
            return store.save(counted.withTurn(turn.id().toString(), clock.instant()));
        }
        catch (RuntimeException failure) {
            return pause(counted, PauseReason.AGENT_TURN_FAILED,
                    "Cannot start an agent turn: " + failure.getMessage());
        }
    }

    /** Verifies the agent-created F from Git, never from the agent's reply. */
    private CherryPickExecution inspectFixup(CherryPickExecution execution, Optional<String> userNote)
    {
        ActionJournal journal = execution.journal().orElseThrow();
        Path worktree = execution.workspace().worktree();
        String conflictCommit = journal.expectedHead();
        String actualHead = headOf(worktree);
        if (actualHead.equals(conflictCommit)) {
            if (operationOpenOrUnmerged(worktree)) {
                return pause(execution, PauseReason.UNSAFE_GIT_STATE,
                        "An unexpected Git operation is open while waiting for the fixup");
            }
            return startAgentTurn(execution, conflictCommit, Optional.empty(), userNote);
        }
        if (!parentOf(worktree, actualHead).equals(conflictCommit)) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                    "The agent did not create exactly one direct child of the conflict snapshot");
        }
        return inspectFixupCandidate(execution, conflictCommit, actualHead);
    }

    /** Retries a structurally valid but dirty or marker-bearing fixup by amendment. */
    private CherryPickExecution inspectRepair(CherryPickExecution execution, Optional<String> userNote)
    {
        ActionJournal journal = execution.journal().orElseThrow();
        Path worktree = execution.workspace().worktree();
        String previousFixup = journal.expectedHead();
        String conflictCommit = parentOf(worktree, previousFixup);
        String actualHead = headOf(worktree);
        if (actualHead.equals(previousFixup)) {
            if (operationOpenOrUnmerged(worktree)) {
                return pause(execution, PauseReason.UNSAFE_GIT_STATE,
                        "An unexpected Git operation is open while repairing the fixup");
            }
            return startAgentTurn(execution, conflictCommit, Optional.of(previousFixup), userNote);
        }
        if (!parentOf(worktree, actualHead).equals(conflictCommit)) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                    "The repair added history instead of amending the existing fixup");
        }
        return inspectFixupCandidate(execution, conflictCommit, actualHead);
    }

    private CherryPickExecution inspectFixupCandidate(
            CherryPickExecution execution,
            String conflictCommit,
            String fixupCommit)
    {
        Path worktree = execution.workspace().worktree();
        ActionJournal journal = execution.journal().orElseThrow();
        String expectedSubject = "fixup! " + subjectOf(worktree, conflictCommit);
        if (!subjectOf(worktree, fixupCommit).equals(expectedSubject)) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                    "The agent commit is not the expected direct fixup");
        }
        if (originSha(messageOf(worktree, fixupCommit)).isPresent()) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                    "The fixup copied a cherry-pick origin line");
        }
        String originalHead = parentOf(worktree, conflictCommit);
        if (!sequencer.cleanAndResolved(worktree)
                || !sequencer.withoutConflictMarkers(worktree, originalHead, fixupCommit)) {
            return store.save(execution.withJournal(journal(
                    ActionType.AGENT_REPAIR,
                    journal.sourceSha(),
                    fixupCommit), clock.instant()));
        }
        validate(worktree, fixupCommit);
        return store.save(execution.withJournal(journal(
                ActionType.FINALIZE_CONFLICT_PAIR,
                journal.sourceSha(),
                fixupCommit), clock.instant()));
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
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                    "Conflict pair no longer follows the recorded target tip");
        }
        SequenceState state = sequencer.observe(worktree);
        if (state.kind() != SequenceState.Kind.COMPLETED || !state.head().equals(fixupCommit)) {
            return pause(execution, PauseReason.UNSAFE_GIT_STATE,
                    "Conflict pair moved before it could be recorded");
        }
        CherryPickExecution updated = store.save(execution
                .recordAppliedWithFixup(
                        journal.sourceSha().orElseThrow(), conflictCommit, fixupCommit, clock.instant())
                .withStep(Step.APPLYING, clock.instant())
                .withoutJournal(clock.instant()));
        return updated.pendingItems().isEmpty() ? complete(updated) : updated;
    }

    /** Finishes executions journaled by the former squashing implementation. */
    private CherryPickExecution normalizeConflictPair(CherryPickExecution execution)
    {
        ActionJournal journal = execution.journal().orElseThrow();
        Path worktree = execution.workspace().worktree();
        String fixupCommit = journal.expectedHead();
        String conflictCommit = parentOf(worktree, fixupCommit);
        String originalHead = parentOf(worktree, conflictCommit);
        if (!originalHead.equals(execution.currentTargetSha())) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                    "Conflict pair no longer follows the recorded target tip");
        }
        NormalizationResult result = sequencer.normalizeConflictPair(
                worktree, originalHead, conflictCommit, fixupCommit);
        return switch (result.kind()) {
            case UNSAFE -> pause(execution, PauseReason.UNSAFE_GIT_STATE, result.diagnostics());
            case APPLIED -> reconcile(
                    execution.withStep(Step.APPLYING, clock.instant()),
                    originalHead,
                    sequencer.observe(worktree));
            case EMPTY -> {
                CherryPickExecution updated = store.save(execution
                        .recordEmptySkipped(journal.sourceSha().orElseThrow(), clock.instant())
                        .withStep(Step.APPLYING, clock.instant())
                        .withoutJournal(clock.instant()));
                yield updated.pendingItems().isEmpty() ? complete(updated) : updated;
            }
        };
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
            return pause(execution, PauseReason.AGENT_TURN_FAILED,
                    "Cannot read the agent turn: " + failure.getMessage());
        }
        if (turn.state().active()) {
            return execution;
        }
        CherryPickExecution cleared = store.save(execution.withoutTurn(clock.instant()));
        if (turn.state() != TurnState.COMPLETED) {
            return pause(cleared, PauseReason.AGENT_TURN_FAILED,
                    "Agent turn ended " + turn.state() + "; the worktree is left as it is");
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
                        module(worktree, conflict.path()),
                        conflict.stages().stream()
                                .map(stage -> new IndexStage(stage.stage(), stage.mode(), stage.blobSha()))
                                .collect(toImmutableList())))
                .collect(toImmutableList());
    }

    private static Optional<String> module(Path worktree, String path)
    {
        Path directory = worktree.resolve(path).getParent();
        while (directory != null && directory.startsWith(worktree) && !directory.equals(worktree)) {
            if (java.nio.file.Files.isRegularFile(directory.resolve("pom.xml"))) {
                return Optional.of(worktree.relativize(directory).toString());
            }
            directory = directory.getParent();
        }
        return Optional.empty();
    }

    private static void validate(Path worktree, String targetSha)
    {
        // ponytail: successful no-op until a user-approved project validation command exists.
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

    private TaskId agentTask(CherryPickExecution execution)
    {
        return tasks.create(new CreateAgentTaskCommand(
                new AgentLaneId(execution.workspace().laneId()),
                execution.workspace().worktree(),
                RepositoryIdentity.valueOf(execution.workspace().repository().fullName()),
                "Cherry-pick " + execution.workspace().workBranch(),
                AgentPermissionPolicy.CHERRY_PICK)).id();
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
            return pause(execution, PauseReason.UNSAFE_GIT_STATE,
                    "Cannot finish while a Git operation is open or the worktree is dirty");
        }

        List<CherryPickItem> applied = execution.items().stream()
                .filter(item -> item.result() == CherryPickItem.Result.APPLIED)
                .collect(toImmutableList());
        List<CreatedCommit> created = createdCommits(
                worktree, execution.range().initialTargetSha(), state.head());
        if (!state.head().equals(execution.currentTargetSha())) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                    "The work branch no longer ends at the recorded target tip");
        }
        int createdIndex = 0;
        for (CherryPickItem item : applied) {
            if (createdIndex >= created.size()) {
                return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                        "The work branch is missing the target for source " + item.sourceSha());
            }
            CreatedCommit commit = created.get(createdIndex++);
            if (!commit.sourceSha().filter(sha -> sha.equals(item.sourceSha())).isPresent()) {
                return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                        "Commit " + commit.targetSha() + " does not record source " + item.sourceSha());
            }
            if (!commit.targetSha().equals(item.targetSha().orElse(null))) {
                return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                        "Recorded target " + item.targetSha().orElse("none")
                                + " is not the commit on the branch");
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
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                    "The work branch holds " + (created.size() - createdIndex)
                            + " unrecorded commit(s)");
        }
        if (!sequencer.withoutConflictMarkers(worktree, execution.range().initialTargetSha(), state.head())) {
            return pause(execution, PauseReason.UNSAFE_GIT_STATE,
                    "A created commit still contains a conflict marker");
        }

        CherryPickExecution settled = execution.journal().isPresent()
                ? store.save(execution.withoutJournal(clock.instant()))
                : execution;
        CherryPickExecution reviewed = store.save(settled.withRangeReview(rangeReview(settled, state.head()),
                clock.instant()));
        completeAgentTask(reviewed);
        return store.save(reviewed.finish(FinishReason.COMPLETED, summary(reviewed), clock.instant()));
    }

    /** True only when the worktree still holds the branch ByteQuay created. */
    private boolean ownsWorktree(CherryPickExecution execution)
    {
        GitCommandRunner.Result branch = git.run(
                execution.workspace().worktree(), "symbolic-ref", "--quiet", "--short", "HEAD");
        return branch.successful() && branch.output().strip().equals(execution.workspace().workBranch());
    }

    private CherryPickExecution.RangeReview rangeReview(CherryPickExecution execution, String currentTargetSha)
    {
        GitCommandRunner.Result result = git.run(
                execution.workspace().worktree(),
                "range-diff",
                execution.range().sourceBaseSha() + ".." + execution.range().sourceTipSha(),
                execution.range().initialTargetSha() + ".." + currentTargetSha);
        String output = result.successful() ? result.output() : "Range review unavailable: " + result.output();
        boolean truncated = result.truncated()
                || output.length() > CherryPickExecution.RangeReview.MAX_OUTPUT_CHARACTERS;
        return new CherryPickExecution.RangeReview(
                truncated
                        ? output.substring(0, Math.min(
                                output.length(), CherryPickExecution.RangeReview.MAX_OUTPUT_CHARACTERS))
                        : output,
                truncated);
    }

    private void completeAgentTask(CherryPickExecution execution)
    {
        execution.agentTaskId().ifPresent(taskId -> {
            try {
                completions.complete(new CompleteAgentTaskCommand(
                        TaskId.valueOf(taskId), Optional.of(summary(execution))));
            }
            catch (RuntimeException failure) {
                // The range is finished either way; a task that cannot be closed
                // is a cleanup problem, not a result.
                log.warn("Cannot complete agent task {} for execution {}", taskId, execution.id(), failure);
            }
        });
    }

    /**
     * Adds one bounded user instruction to a paused range.
     *
     * <p>The text is quoted into the fixed prompt as an untrusted note. It
     * cannot change the plan, the target, the order, or the skip list.
     *
     * @param executionId paused execution
     * @param message user text
     * @return the resumed or still-paused execution
     */
    public CherryPickExecution sendMessage(CherryPickExecutionId executionId, String message)
    {
        requireNonNull(message, "message is null");
        if (message.isBlank() || message.length() > MAX_MESSAGE_CHARACTERS) {
            throw new CherryPickInvalidRequestException(
                    "message must contain between 1 and " + MAX_MESSAGE_CHARACTERS + " characters");
        }
        Lock lock = advanceLocks.get(executionId.toString());
        lock.lock();
        try {
            CherryPickExecution execution = get(executionId);
            if (execution.status() != Status.PAUSED) {
                throw new CherryPickConflictException("Execution is not paused");
            }
            execution.cursor().orElseThrow(() -> new CherryPickConflictException(
                    "Execution has no unfinished commit to work on"));
            // Look before resuming: the user may have fixed it by hand.
            SequenceState state = sequencer.observe(execution.workspace().worktree());
            if (state.kind() == SequenceState.Kind.UNSAFE) {
                throw new CherryPickConflictException(
                        "Git is in an unexpected state: " + state.diagnostics());
            }
            CherryPickExecution resumed = store.save(execution.resume(clock.instant()));
            if (resumed.journal().map(ActionJournal::type).filter(ActionType.AGENT_TURN::equals).isPresent()) {
                return inspectFixup(resumed, Optional.of(message));
            }
            if (resumed.journal().map(ActionJournal::type).filter(ActionType.AGENT_REPAIR::equals).isPresent()) {
                return inspectRepair(resumed, Optional.of(message));
            }
            if (state.kind() == SequenceState.Kind.STOPPED_CONFLICT) {
                CherryPickExecution snapshotted = commitConflictSnapshot(resumed, state);
                if (snapshotted.status() == Status.RUNNING
                        && snapshotted.journal().map(ActionJournal::type)
                                .filter(ActionType.AGENT_TURN::equals).isPresent()) {
                    return inspectFixup(snapshotted, Optional.of(message));
                }
                return snapshotted;
            }
            // Nothing for an agent to resolve; let the monitor reconcile it.
            return resumed;
        }
        finally {
            lock.unlock();
        }
    }

    private record CreatedCommit(String targetSha, Optional<String> sourceSha)
    {
        private CreatedCommit
        {
            requireNonNull(targetSha, "targetSha is null");
            requireNonNull(sourceSha, "sourceSha is null");
        }
    }
}
