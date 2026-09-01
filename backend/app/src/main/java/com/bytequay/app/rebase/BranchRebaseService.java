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
package com.bytequay.app.rebase;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.RebaseSequencerDriver;
import com.bytequay.app.git.SequenceState;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.rebase.BranchRebase;
import com.bytequay.core.rebase.BranchRebase.ActionJournal;
import com.bytequay.core.rebase.BranchRebase.ActionType;
import com.bytequay.core.rebase.BranchRebase.FinishReason;
import com.bytequay.core.rebase.BranchRebase.PauseReason;
import com.bytequay.core.rebase.BranchRebase.Status;
import com.bytequay.core.rebase.BranchRebase.Step;
import com.bytequay.core.rebase.BranchRebaseException;
import com.bytequay.core.rebase.BranchRebaseId;
import com.bytequay.core.rebase.BranchRebaseNotFoundException;
import com.bytequay.core.rebase.BranchRebaseStore;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.locks.Lock;

import static java.util.Objects.requireNonNull;

/** Owns every durable transition of one local branch rebase. */
@Service
public class BranchRebaseService
{
    private static final Logger log = LoggerFactory.getLogger(BranchRebaseService.class);
    private static final int MAX_MESSAGE_CHARACTERS = 16 * 1024;

    private final BranchRebaseStore store;
    private final AgentTaskService tasks;
    private final WorktreeService worktrees;
    private final AgentTurnService turns;
    private final RebaseSequencerDriver sequencer;
    private final GitCommandRunner git;
    private final Clock clock;
    private final Striped<Lock> advanceLocks = Striped.lock(64);

    public BranchRebaseService(
            BranchRebaseStore store,
            AgentTaskService tasks,
            WorktreeService worktrees,
            AgentTurnService turns,
            RebaseSequencerDriver sequencer,
            GitCommandRunner git,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.sequencer = requireNonNull(sequencer, "sequencer is null");
        this.git = requireNonNull(git, "git is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Freezes local branch tips and creates or returns one idempotent rebase.
     *
     * @param command caller-owned task, lease, branches, and idempotency key
     * @return durable rebase before any replay command runs
     */
    public synchronized BranchRebase start(StartBranchRebaseCommand command)
    {
        requireNonNull(command, "command is null");
        Optional<BranchRebase> existing = store.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            BranchRebase found = existing.orElseThrow();
            if (!sameRequest(found, command)) {
                throw new BranchRebaseException("Idempotency key belongs to another branch rebase");
            }
            return found;
        }
        WorktreeSnapshot snapshot = requireCallerScope(command);
        TaskWorktree taskWorktree = snapshot.worktree();
        Path worktree = taskWorktree.canonicalWorktree();
        String workBranch = taskWorktree.generatedBranch();
        requireLocalBranch(worktree, workBranch);
        requireLocalBranch(worktree, command.baseBranch());
        if (workBranch.equals(command.baseBranch())) {
            throw new BranchRebaseException("Work branch and base branch are the same");
        }
        String originalTip = snapshot.head();
        String baseSha = required(worktree,
                "rev-parse", "--verify", "refs/heads/" + command.baseBranch() + "^{commit}");
        String workRef = required(worktree,
                "rev-parse", "--verify", "refs/heads/" + workBranch + "^{commit}");
        if (!workRef.equals(originalTip)) {
            throw new BranchRebaseException("Checked-out work branch does not point at HEAD");
        }
        String fork = required(worktree, "merge-base", originalTip, baseSha);
        if (!required(worktree, "rev-list", "--min-parents=2", fork + ".." + originalTip)
                .isBlank()) {
            throw new BranchRebaseException("Rebase range contains merge commits");
        }
        requireNoLiveRebase(worktree);

        BranchRebase proposed = BranchRebase.create(
                BranchRebaseId.random(),
                command.idempotencyKey(),
                taskWorktree.taskId().toString(),
                RepoRef.of(
                        taskWorktree.physicalRepository().owner(),
                        taskWorktree.physicalRepository().name()),
                worktree,
                workBranch,
                command.baseBranch(),
                originalTip,
                baseSha,
                clock.instant());
        if (isAncestor(worktree, baseSha, originalTip)) {
            return store.createOrGet(proposed.finish(
                    FinishReason.COMPLETED,
                    "Work branch already contains the base",
                    Optional.of(originalTip),
                    clock.instant()));
        }
        ActionJournal start = journal(proposed, ActionType.START, Optional.empty(), originalTip, 0);
        return store.createOrGet(proposed.withJournal(start, clock.instant()));
    }

    /** Returns the current durable rebase. */
    public BranchRebase get(BranchRebaseId rebaseId)
    {
        return store.find(rebaseId).orElseThrow(() -> new BranchRebaseNotFoundException(rebaseId));
    }

    /** Inspects Git and performs at most one bounded next action. */
    public BranchRebase advance(BranchRebaseId rebaseId)
    {
        Lock lock = advanceLocks.get(rebaseId.toString());
        if (!lock.tryLock()) {
            return get(rebaseId);
        }
        try {
            BranchRebase rebase = get(rebaseId);
            if (rebase.status() != Status.RUNNING) {
                return rebase;
            }
            if (rebase.currentTurnId().isPresent()) {
                return afterTurn(rebase);
            }
            return act(rebase, Optional.empty());
        }
        finally {
            lock.unlock();
        }
    }

    /** Resumes one paused rebase with a bounded, untrusted user note. */
    public BranchRebase sendMessage(BranchRebaseId rebaseId, String message)
    {
        requireNonNull(message, "message is null");
        if (message.isBlank() || message.length() > MAX_MESSAGE_CHARACTERS) {
            throw new BranchRebaseException(
                    "message must contain between 1 and " + MAX_MESSAGE_CHARACTERS + " characters");
        }
        Lock lock = advanceLocks.get(rebaseId.toString());
        lock.lock();
        try {
            BranchRebase rebase = get(rebaseId);
            if (rebase.status() != Status.PAUSED) {
                throw new BranchRebaseException("Branch rebase is not paused");
            }
            BranchRebase resumed = store.save(rebase.resume(clock.instant()));
            return act(resumed, Optional.of(message));
        }
        finally {
            lock.unlock();
        }
    }

    /** Requests turn cancellation, aborts the owned rebase, and verifies restoration. */
    public BranchRebase cancel(BranchRebaseId rebaseId)
    {
        Lock lock = advanceLocks.get(rebaseId.toString());
        lock.lock();
        try {
            BranchRebase rebase = get(rebaseId);
            if (rebase.status() == Status.FINISHED) {
                return rebase;
            }
            ActionJournal abort = journal(
                    rebase,
                    ActionType.ABORT,
                    rebase.conflictCommitSha(),
                    head(rebase.worktree()),
                    rebase.attemptCount());
            BranchRebase requested = store.save(rebase.requestAbort(abort, clock.instant()));
            requested.currentTurnId().ifPresent(turnId -> turns.cancel(TurnId.valueOf(turnId)));
            return requested.currentTurnId().isPresent() ? requested : recoverAbort(requested);
        }
        finally {
            lock.unlock();
        }
    }

    private BranchRebase act(BranchRebase rebase, Optional<String> userNote)
    {
        if (!ownsBranch(rebase)) {
            return pause(rebase, PauseReason.UNSAFE_GIT_STATE, "Worktree is no longer on the recorded work branch");
        }
        Optional<ActionType> action = rebase.journal().map(ActionJournal::type);
        if (action.filter(ActionType.START::equals).isPresent()) {
            return recoverStart(rebase);
        }
        if (action.filter(ActionType.AGENT_TURN::equals).isPresent()) {
            return startAgentTurn(rebase, userNote);
        }
        if (action.filter(ActionType.CONTINUE::equals).isPresent()) {
            return recoverContinue(rebase);
        }
        if (action.filter(ActionType.SKIP::equals).isPresent()) {
            return recoverSkip(rebase);
        }
        if (action.filter(ActionType.ABORT::equals).isPresent()) {
            return recoverAbort(rebase);
        }
        return recordState(rebase, sequencer.observe(rebase.worktree()), userNote);
    }

    private BranchRebase recoverStart(BranchRebase rebase)
    {
        ActionJournal action = rebase.journal().orElseThrow();
        SequenceState state = sequencer.observe(rebase.worktree());
        if (state.kind() == SequenceState.Kind.COMPLETED
                && state.head().equals(action.expectedHead())) {
            state = sequencer.begin(rebase.worktree(), action.expectedHead(), rebase.baseSha());
        }
        return recordState(rebase, state, Optional.empty());
    }

    private BranchRebase recoverContinue(BranchRebase rebase)
    {
        ActionJournal action = rebase.journal().orElseThrow();
        SequenceState state = sequencer.observe(rebase.worktree());
        if (sameStop(state, action, SequenceState.Kind.STOPPED_RESOLVED)) {
            state = sequencer.continueRebase(
                    rebase.worktree(), action.expectedHead(), action.stoppedCommitSha().orElseThrow());
        }
        return recordState(rebase, state, Optional.empty());
    }

    private BranchRebase recoverSkip(BranchRebase rebase)
    {
        ActionJournal action = rebase.journal().orElseThrow();
        SequenceState state = sequencer.observe(rebase.worktree());
        if (sameStop(state, action, SequenceState.Kind.STOPPED_EMPTY)) {
            state = sequencer.skipEmpty(
                    rebase.worktree(), action.expectedHead(), action.stoppedCommitSha().orElseThrow());
        }
        return recordState(rebase, state, Optional.empty());
    }

    private BranchRebase recoverAbort(BranchRebase rebase)
    {
        SequenceState state = sequencer.abort(rebase.worktree(), rebase.originalWorkTip());
        if (state.kind() != SequenceState.Kind.COMPLETED
                || !state.head().equals(rebase.originalWorkTip())) {
            return pause(rebase, PauseReason.UNSAFE_GIT_STATE,
                    state.diagnostics().isBlank() ? "Rebase could not be aborted safely" : state.diagnostics());
        }
        return store.save(rebase.finish(
                FinishReason.CANCELLED, "Rebase cancelled", Optional.empty(), clock.instant()));
    }

    private BranchRebase recordState(
            BranchRebase rebase,
            SequenceState state,
            Optional<String> userNote)
    {
        return switch (state.kind()) {
            case COMPLETED -> complete(rebase, state);
            case STOPPED_CONFLICT -> prepareAgentTurn(rebase, state, userNote);
            case STOPPED_EMPTY -> store.save(rebase
                    .replaying(clock.instant())
                    .withJournal(journal(
                            rebase,
                            ActionType.SKIP,
                            state.stoppedCommit(),
                            state.head(),
                            rebase.attemptCount()), clock.instant()));
            case STOPPED_RESOLVED -> pause(rebase, PauseReason.RECONCILIATION_MISMATCH,
                    "A rebase resolution was staged outside the expected agent turn");
            case UNSAFE -> pause(rebase, PauseReason.UNSAFE_GIT_STATE,
                    state.diagnostics().isBlank() ? "Unexpected Git state" : state.diagnostics());
        };
    }

    private BranchRebase prepareAgentTurn(
            BranchRebase rebase,
            SequenceState state,
            Optional<String> userNote)
    {
        String stopped = state.stoppedCommit().orElseThrow();
        BranchRebase resolving = rebase.resolving(stopped, clock.instant());
        if (resolving.attemptCount() >= BranchRebase.MAX_AGENT_TURNS) {
            return pause(resolving, PauseReason.RETRY_LIMIT_REACHED,
                    "Conflict on " + stopped + " remains after "
                            + BranchRebase.MAX_AGENT_TURNS + " attempts");
        }
        BranchRebase counted = resolving.recordTurnStarted(clock.instant());
        ActionJournal action = journal(
                counted,
                ActionType.AGENT_TURN,
                Optional.of(stopped),
                state.head(),
                counted.attemptCount());
        BranchRebase prepared = store.save(counted.withJournal(action, clock.instant()));
        return userNote.isPresent() ? startAgentTurn(prepared, userNote) : prepared;
    }

    private BranchRebase startAgentTurn(BranchRebase rebase, Optional<String> userNote)
    {
        ActionJournal action = rebase.journal().orElseThrow();
        SequenceState state = sequencer.observe(rebase.worktree());
        if (!sameStop(state, action, SequenceState.Kind.STOPPED_CONFLICT)) {
            return pause(rebase, PauseReason.RECONCILIATION_MISMATCH,
                    "Rebase moved before the conflict-resolution turn started");
        }
        try {
            AgentTurn turn = turns.start(new StartAgentTurnCommand(
                    TaskId.valueOf(rebase.agentTaskId()),
                    BranchRebasePromptBuilder.resolve(rebase, state, action.attempt(), userNote),
                    ImmutableList.of(),
                    action.idempotencyKey()));
            return store.save(rebase.withTurn(turn.id().toString(), clock.instant()));
        }
        catch (RuntimeException failure) {
            return pause(rebase, PauseReason.AGENT_TURN_FAILED,
                    "Cannot start an agent turn: " + failure.getMessage());
        }
    }

    private BranchRebase afterTurn(BranchRebase rebase)
    {
        AgentTurn turn;
        try {
            turn = turns.get(TurnId.valueOf(rebase.currentTurnId().orElseThrow()));
        }
        catch (RuntimeException failure) {
            BranchRebase cleared = rebase.withoutTurn(clock.instant()).withoutJournal(clock.instant());
            return pause(cleared, PauseReason.AGENT_TURN_FAILED,
                    "Cannot read the agent turn: " + failure.getMessage());
        }
        if (turn.state().active()) {
            return rebase;
        }
        if (rebase.journal().map(ActionJournal::type).filter(ActionType.ABORT::equals).isPresent()) {
            return recoverAbort(store.save(rebase.withoutTurn(clock.instant())));
        }
        if (turn.state() != TurnState.COMPLETED) {
            BranchRebase cleared = rebase.withoutTurn(clock.instant()).withoutJournal(clock.instant());
            return pause(cleared, PauseReason.AGENT_TURN_FAILED,
                    "Agent turn ended " + turn.state() + "; the rebase remains open");
        }
        BranchRebase cleared = rebase.withoutTurn(clock.instant());
        return inspectAgentResult(cleared);
    }

    private BranchRebase inspectAgentResult(BranchRebase rebase)
    {
        ActionJournal action = rebase.journal().orElseThrow();
        SequenceState state = sequencer.observe(rebase.worktree());
        if (!state.head().equals(action.expectedHead())
                || !state.stoppedCommit().equals(action.stoppedCommitSha())) {
            return pause(rebase, PauseReason.RECONCILIATION_MISMATCH,
                    "Agent advanced or changed the rebase sequence");
        }
        return switch (state.kind()) {
            case STOPPED_CONFLICT -> prepareAgentTurn(rebase, state, Optional.empty());
            case STOPPED_RESOLVED -> store.save(rebase
                    .replaying(clock.instant())
                    .withJournal(journal(
                            rebase,
                            ActionType.CONTINUE,
                            state.stoppedCommit(),
                            state.head(),
                            action.attempt()), clock.instant()));
            case STOPPED_EMPTY -> store.save(rebase
                    .replaying(clock.instant())
                    .withJournal(journal(
                            rebase,
                            ActionType.SKIP,
                            state.stoppedCommit(),
                            state.head(),
                            action.attempt()), clock.instant()));
            case COMPLETED -> pause(rebase, PauseReason.RECONCILIATION_MISMATCH,
                    "Agent closed the rebase operation");
            case UNSAFE -> pause(rebase, PauseReason.UNSAFE_GIT_STATE,
                    state.diagnostics().isBlank() ? "Agent left an unsafe Git state" : state.diagnostics());
        };
    }

    private BranchRebase complete(BranchRebase rebase, SequenceState state)
    {
        if (!ownsBranch(rebase)
                || state.kind() != SequenceState.Kind.COMPLETED
                || !originalHeadMatches(rebase)
                || !isAncestor(rebase.worktree(), rebase.baseSha(), state.head())
                || !sequencer.withoutConflictMarkers(rebase.worktree(), rebase.baseSha(), state.head())) {
            return pause(rebase, PauseReason.RECONCILIATION_MISMATCH,
                    "Final work branch is not a clean descendant of the frozen base");
        }
        BranchRebase validating = rebase.currentStep().filter(Step.VALIDATING::equals).isPresent()
                ? rebase
                : store.save(rebase.validating(clock.instant()));
        validate(validating.worktree(), state.head());
        return store.save(validating.finish(
                FinishReason.COMPLETED,
                "Rebased " + validating.workBranch() + " onto " + validating.baseBranch(),
                Optional.of(state.head()),
                clock.instant()));
    }

    private WorktreeSnapshot requireCallerScope(StartBranchRebaseCommand command)
    {
        TaskWorktree taskWorktree = command.taskWorktree();
        AgentTask task = tasks.get(taskWorktree.taskId());
        if (task.state() != TaskState.OPEN) {
            throw new BranchRebaseException("Agent task is not live");
        }
        if (task.taskWorktree().filter(taskWorktree::equals).isEmpty()) {
            throw new BranchRebaseException("Agent task does not own this repository worktree");
        }
        WorktreeSnapshot snapshot = worktrees.inspect(taskWorktree);
        if (snapshot.dirty() || snapshot.gitOperationInProgress()) {
            throw new BranchRebaseException("Worktree is dirty or another Git operation is open");
        }
        return snapshot;
    }

    private void requireNoLiveRebase(Path worktree)
    {
        boolean live = store.listRunning(BranchRebaseStore.MAX_LIST_LIMIT).stream()
                .anyMatch(rebase -> rebase.worktree().equals(worktree));
        if (live) {
            throw new BranchRebaseException("Another branch rebase is already running in this worktree");
        }
    }

    private void requireLocalBranch(Path worktree, String branch)
    {
        if (branch.startsWith("refs/")
                || !git.run(worktree, "check-ref-format", "refs/heads/" + branch).successful()) {
            throw new BranchRebaseException("Branch is not a valid local branch: " + branch);
        }
    }

    private boolean ownsBranch(BranchRebase rebase)
    {
        GitCommandRunner.Result branch = git.run(
                rebase.worktree(), "symbolic-ref", "--quiet", "--short", "HEAD");
        if (branch.successful() && branch.output().strip().equals(rebase.workBranch())) {
            return true;
        }
        for (String state : ImmutableList.of("rebase-merge/head-name", "rebase-apply/head-name")) {
            GitCommandRunner.Result path = git.run(rebase.worktree(), "rev-parse", "--git-path", state);
            if (!path.successful()) {
                continue;
            }
            Path headName = rebase.worktree().resolve(path.output().strip()).normalize();
            try {
                if (java.nio.file.Files.isRegularFile(headName)
                        && java.nio.file.Files.readString(headName).strip()
                                .equals("refs/heads/" + rebase.workBranch())) {
                    return true;
                }
            }
            catch (java.io.IOException ignored) {
                return false;
            }
        }
        return false;
    }

    private boolean isAncestor(Path worktree, String ancestor, String descendant)
    {
        return git.run(worktree, "merge-base", "--is-ancestor", ancestor, descendant).successful();
    }

    private static boolean sameRequest(BranchRebase rebase, StartBranchRebaseCommand command)
    {
        return rebase.agentTaskId().equals(command.taskWorktree().taskId().toString())
                && rebase.repository().equals(RepoRef.of(
                        command.taskWorktree().physicalRepository().owner(),
                        command.taskWorktree().physicalRepository().name()))
                && rebase.worktree().equals(command.taskWorktree().canonicalWorktree())
                && rebase.workBranch().equals(command.taskWorktree().generatedBranch())
                && rebase.baseBranch().equals(command.baseBranch());
    }

    private boolean originalHeadMatches(BranchRebase rebase)
    {
        GitCommandRunner.Result original = git.run(
                rebase.worktree(), "rev-parse", "--verify", "ORIG_HEAD");
        return original.successful() && original.output().strip().equals(rebase.originalWorkTip());
    }

    private String required(Path worktree, String... arguments)
    {
        GitCommandRunner.Result result = git.run(worktree, arguments);
        if (!result.successful()) {
            throw new BranchRebaseException(
                    "Git " + arguments[0] + " failed: " + result.output().strip());
        }
        return result.output().strip();
    }

    private String head(Path worktree)
    {
        GitCommandRunner.Result result = git.run(worktree, "rev-parse", "HEAD");
        return result.successful() ? result.output().strip() : "0".repeat(40);
    }

    private ActionJournal journal(
            BranchRebase rebase,
            ActionType type,
            Optional<String> stoppedCommit,
            String expectedHead,
            int attempt)
    {
        return new ActionJournal(
                type,
                stoppedCommit,
                expectedHead,
                attempt,
                rebase.id() + ":" + type.name().toLowerCase(java.util.Locale.ROOT)
                        + ":" + expectedHead + ":" + attempt,
                clock.instant());
    }

    private static boolean sameStop(
            SequenceState state,
            ActionJournal action,
            SequenceState.Kind kind)
    {
        return state.kind() == kind
                && state.head().equals(action.expectedHead())
                && state.stoppedCommit().equals(action.stoppedCommitSha());
    }

    private BranchRebase pause(BranchRebase rebase, PauseReason reason, String diagnostic)
    {
        log.info("Branch rebase {} paused: {}", rebase.id(), diagnostic);
        return store.save(rebase.pause(reason, diagnostic, clock.instant()));
    }

    private static void validate(Path worktree, String resultTip)
    {
        // ponytail: successful no-op until a user-approved project validation command exists.
    }
}
