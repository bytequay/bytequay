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
import com.bytequay.core.cherrypick.CherryPickItem.Validation;
import com.bytequay.core.cherrypick.CherryPickNotFoundException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Owns the progress and final results of confirmed cherry-pick executions.
 *
 * <p>Git's sequencer decides what runs next; this service decides what the
 * result was. Nothing else may write status, step, item result, or pause
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
    private final CherryPickValidation validation;
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
            CherryPickValidation validation,
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
        this.validation = requireNonNull(validation, "validation is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Freezes a confirmed plan and prepares the workspace it will run in.
     *
     * @param command confirmed skips and the object IDs they were chosen against
     * @param repository clone that owns the objects
     * @param worktreeParent directory that will hold the generated worktree
     * @return the durable execution
     */
    public CherryPickExecution start(
            StartCherryPickExecutionCommand command,
            Path repository,
            Path worktreeParent)
    {
        requireNonNull(command, "command is null");
        requireNonNull(repository, "repository is null");
        requireNonNull(worktreeParent, "worktreeParent is null");

        // Answer a repeated start before creating anything: the live-execution
        // check below would otherwise reject the caller's own execution.
        Optional<CherryPickExecution> existing = store.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }

        CherryPickPreview preview = previews.find(command.previewId())
                .orElseThrow(() -> new CherryPickInvalidRequestException(
                        "Preview is unknown or expired; preview the range again"));
        requireUnmoved(preview, command);
        List<CherryPickItem> items = plan(preview, command.userSkippedSourceShas());
        requireNoLiveExecution(preview);

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
                            preview.repository(),
                            preview.sourceRemote(),
                            preview.targetRemote(),
                            preview.targetBranch(),
                            preview.localTargetBranch(),
                            created.branch(),
                            created.worktree(),
                            command.laneId()),
                    new Range(preview.sourceBaseSha(), preview.sourceTipSha(), preview.targetSha()),
                    items,
                    clock.instant()));
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
     * @param repository clone that owns the worktree
     * @return the finished execution
     */
    public CherryPickExecution cancel(CherryPickExecutionId executionId, Path repository)
    {
        Lock lock = advanceLocks.get(executionId.toString());
        lock.lock();
        try {
            CherryPickExecution execution = get(executionId);
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
        if (execution.currentStep().filter(step -> step == Step.VALIDATING).isPresent()) {
            return validate(execution);
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
            List<String> pending = execution.pendingItems().stream()
                    .map(CherryPickItem::sourceSha)
                    .collect(toImmutableList());
            CherryPickExecution started = store.save(execution
                    .withStep(Step.APPLYING, clock.instant())
                    .withJournal(journal(ActionType.BEGIN_SEQUENCE, Optional.empty(), expectedHead), clock.instant()));
            return reconcile(started, expectedHead, sequencer.beginCherryPick(worktree, expectedHead, pending));
        }
        if (before.kind() == SequenceState.Kind.STOPPED_EMPTY) {
            String stopped = before.stoppedCommit().orElseThrow();
            CherryPickExecution journalled = store.save(execution
                    .withJournal(journal(ActionType.SKIP_EMPTY, Optional.of(stopped), before.head()), clock.instant()));
            SequenceState after = sequencer.skipEmpty(worktree, before.head(), stopped);
            if (after.kind() == SequenceState.Kind.UNSAFE) {
                return pause(journalled, PauseReason.UNSAFE_GIT_STATE,
                        "Empty skip was rejected: " + after.diagnostics());
            }
            return reconcile(
                    store.save(journalled.recordEmptySkipped(stopped, clock.instant())),
                    before.head(),
                    after);
        }
        if (before.kind() == SequenceState.Kind.STOPPED_CONFLICT) {
            return startResolution(execution, before);
        }
        if (before.kind() == SequenceState.Kind.STOPPED_RESOLVED) {
            String stopped = before.stoppedCommit().orElseThrow();
            SequenceState after = sequencer.continueSequence(worktree, stopped);
            if (after.kind() == SequenceState.Kind.UNSAFE) {
                return pause(execution, PauseReason.UNSAFE_GIT_STATE, after.diagnostics());
            }
            // An agent touched this commit, so its result waits on validation.
            return store.save(execution.withStep(Step.VALIDATING, clock.instant()));
        }
        return pause(execution, PauseReason.UNSAFE_GIT_STATE,
                before.diagnostics().isBlank() ? "Unexpected Git state" : before.diagnostics());
    }

    /**
     * Maps every commit created since the stored HEAD back to a plan item.
     *
     * <p>The {@code -x} line on each created commit is what makes a batch safe:
     * it names the source commit, so a run of commits maps to plan items exactly
     * rather than by counting them.
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
        Map<String, String> applied = new LinkedHashMap<>();
        for (CreatedCommit created : createdCommits(worktree, expectedHead, after.head())) {
            if (created.sourceSha().isEmpty()) {
                return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                        "Commit " + created.targetSha() + " carries no cherry-pick origin line");
            }
            applied.put(created.sourceSha().orElseThrow(), created.targetSha());
        }
        List<String> expectedOrder = execution.pendingItems().stream()
                .map(CherryPickItem::sourceSha)
                .limit(applied.size())
                .collect(toImmutableList());
        if (!ImmutableList.copyOf(applied.keySet()).equals(expectedOrder)) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                    "Created commits do not match the confirmed plan order");
        }
        if (!applied.isEmpty() && !sequencer.withoutConflictMarkers(worktree, expectedHead, after.head())) {
            return pause(execution, PauseReason.UNSAFE_GIT_STATE,
                    "A created commit still contains a conflict marker");
        }

        CherryPickExecution updated = applied.isEmpty()
                ? execution
                : store.save(execution.recordApplied(ImmutableMap.copyOf(applied), after.head(), clock.instant()));
        if (updated.pendingItems().isEmpty()) {
            return complete(updated);
        }
        if (after.kind() == SequenceState.Kind.STOPPED_CONFLICT) {
            // The conflict is already known, so hand it over now rather than
            // leaving a conflicted range idle until the next monitor tick.
            return startResolution(updated, after);
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

    private void requireNoLiveExecution(CherryPickPreview preview)
    {
        boolean live = store.listRunning(CherryPickExecutionStore.MAX_LIST_LIMIT).stream()
                .anyMatch(execution -> execution.workspace().repository().equals(preview.repository())
                        && execution.workspace().localTargetBranch().equals(preview.localTargetBranch()));
        if (live) {
            throw new CherryPickConflictException(
                    "Another cherry-pick execution is already running for " + preview.localTargetBranch());
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

    /**
     * Hands one conflict to the agent, or stops when it has had its attempts.
     *
     * <p>The agent stages files. It never moves the sequence, so a turn cannot
     * make progress the program has not verified.
     */
    private CherryPickExecution startResolution(CherryPickExecution execution, SequenceState state)
    {
        return startResolution(execution, state, Optional.empty());
    }

    private CherryPickExecution startResolution(
            CherryPickExecution execution,
            SequenceState state,
            Optional<String> userNote)
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
        if (item.agentTurnCount() >= CherryPickItem.MAX_AGENT_TURNS) {
            return pause(execution, PauseReason.RETRY_LIMIT_REACHED,
                    "Conflict on " + stopped + " remains after " + CherryPickItem.MAX_AGENT_TURNS + " attempts");
        }

        CherryPickExecution withConflict = store.save(execution
                .recordConflict(stopped, conflictPaths(execution, state), clock.instant())
                .withStep(Step.RESOLVING, clock.instant()));
        CherryPickItem current = itemOf(withConflict, stopped);
        int attempt = current.agentTurnCount() + 1;
        CherryPickExecution counted = store.save(withConflict
                .recordAgentTurnStarted(stopped, clock.instant())
                .withJournal(journal(ActionType.AGENT_TURN, Optional.of(stopped), state.head()), clock.instant()));

        String prompt = CherryPickPromptBuilder.resolveConflict(
                current,
                state.head(),
                validationPlan(counted, stopped).map(CherryPickValidation.Plan::describe),
                current.validation().map(Validation::diagnostics),
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

    /**
     * Runs the stored build and only then accepts the commit.
     */
    private CherryPickExecution validate(CherryPickExecution execution)
    {
        Path worktree = execution.workspace().worktree();
        String expectedHead = execution.currentTargetSha();
        SequenceState state = sequencer.observe(worktree);
        if (state.kind() != SequenceState.Kind.COMPLETED) {
            return pause(execution, PauseReason.UNSAFE_GIT_STATE,
                    "Cannot validate while a Git operation is open");
        }
        List<CreatedCommit> created = createdCommits(worktree, expectedHead, state.head());
        if (created.isEmpty()) {
            // The resolution turned out to contain nothing; the guarded skip
            // handles it on the next pass, with no extra agent turn.
            return store.save(execution.withoutJournal(clock.instant()));
        }
        Optional<CherryPickValidation.Plan> plan = validationPlan(execution, created.getLast().targetSha());
        if (plan.isEmpty()) {
            return pause(execution, PauseReason.VALIDATION_UNAVAILABLE,
                    "No checked-in Maven wrapper, so ByteQuay cannot verify this resolution itself");
        }
        CherryPickValidation.Outcome outcome = validation.run(plan.orElseThrow());
        String targetSha = created.getLast().targetSha();
        String sourceSha = created.getLast().sourceSha().orElse(null);
        if (sourceSha == null) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                    "Commit " + targetSha + " carries no cherry-pick origin line");
        }
        CherryPickExecution recorded = store.save(execution.recordValidation(
                sourceSha, new Validation(targetSha, outcome.passed(), outcome.bounded()), clock.instant()));
        if (!outcome.passed()) {
            return startRepair(recorded, sourceSha, targetSha, plan.orElseThrow(), outcome);
        }
        return reconcile(recorded, expectedHead, state);
    }

    private CherryPickExecution startRepair(
            CherryPickExecution execution,
            String sourceSha,
            String targetSha,
            CherryPickValidation.Plan plan,
            CherryPickValidation.Outcome outcome)
    {
        CherryPickItem item = itemOf(execution, sourceSha);
        if (item.agentTurnCount() >= CherryPickItem.MAX_AGENT_TURNS) {
            return pause(execution, PauseReason.VALIDATION_FAILED,
                    "Validation still fails for " + targetSha + " after "
                            + CherryPickItem.MAX_AGENT_TURNS + " attempts");
        }
        int attempt = item.agentTurnCount() + 1;
        CherryPickExecution counted = store.save(execution.recordAgentTurnStarted(sourceSha, clock.instant()));
        try {
            AgentTurn turn = turns.start(new StartAgentTurnCommand(
                    TaskId.valueOf(counted.agentTaskId().orElseThrow()),
                    CherryPickPromptBuilder.repairValidation(
                            item, targetSha, plan.describe(), outcome.bounded(), attempt),
                    ImmutableList.of(),
                    turnKey(counted, item.position(), attempt)));
            return store.save(counted.withTurn(turn.id().toString(), clock.instant()));
        }
        catch (RuntimeException failure) {
            return pause(counted, PauseReason.AGENT_TURN_FAILED,
                    "Cannot start a repair turn: " + failure.getMessage());
        }
    }

    private Optional<CherryPickValidation.Plan> validationPlan(CherryPickExecution execution, String reference)
    {
        Path worktree = execution.workspace().worktree();
        GitCommandRunner.Result changed = git.run(
                worktree, "show", "--name-only", "--no-renames", "--format=", reference);
        ImmutableList.Builder<String> paths = ImmutableList.builder();
        if (changed.successful()) {
            changed.output().lines().map(String::strip).filter(line -> !line.isEmpty()).forEach(paths::add);
        }
        itemsOf(execution, reference).forEach(path -> paths.add(path));
        return validation.plan(worktree, paths.build());
    }

    private static List<String> itemsOf(CherryPickExecution execution, String sourceSha)
    {
        return execution.items().stream()
                .filter(item -> item.sourceSha().equals(sourceSha))
                .flatMap(item -> item.conflictPaths().stream().map(ConflictPath::path))
                .collect(toImmutableList());
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
                        module(worktree, conflict.path())))
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
        if (created.size() != applied.size()) {
            return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                    "The work branch holds " + created.size() + " commits but " + applied.size()
                            + " are recorded as applied");
        }
        for (int index = 0; index < created.size(); index++) {
            CreatedCommit commit = created.get(index);
            CherryPickItem item = applied.get(index);
            if (!commit.sourceSha().filter(sha -> sha.equals(item.sourceSha())).isPresent()) {
                return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                        "Commit " + commit.targetSha() + " does not record source " + item.sourceSha());
            }
            if (!commit.targetSha().equals(item.targetSha().orElse(null))) {
                return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                        "Recorded target " + item.targetSha().orElse("none")
                                + " is not the commit on the branch");
            }
        }
        if (!sequencer.withoutConflictMarkers(worktree, execution.range().initialTargetSha(), state.head())) {
            return pause(execution, PauseReason.UNSAFE_GIT_STATE,
                    "A created commit still contains a conflict marker");
        }

        CherryPickExecution reviewed = store.save(execution.withRangeReview(rangeReview(execution, state.head()),
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
            if (state.kind() != SequenceState.Kind.STOPPED_CONFLICT) {
                // Nothing for an agent to resolve; let the monitor carry on.
                return resumed;
            }
            return startResolution(resumed, state, Optional.of(message));
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
