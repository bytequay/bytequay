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
import com.bytequay.core.cherrypick.CherryPickItem.Disposition;
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
    private static final String ORIGIN_PREFIX = "(cherry picked from commit ";
    private static final String FIELD = "\u001f";
    private static final String RECORD = "\u001e";

    private final CherryPickPreviewService previews;
    private final CherryPickExecutionStore store;
    private final CherryPickWorkspace workspaces;
    private final GitSequencerDriver sequencer;
    private final GitCommandRunner git;
    private final Clock clock;
    private final Striped<Lock> advanceLocks = Striped.lock(64);

    public CherryPickExecutionService(
            CherryPickPreviewService previews,
            CherryPickExecutionStore store,
            CherryPickWorkspace workspaces,
            GitSequencerDriver sequencer,
            GitCommandRunner git,
            Clock clock)
    {
        this.previews = requireNonNull(previews, "previews is null");
        this.store = requireNonNull(store, "store is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.sequencer = requireNonNull(sequencer, "sequencer is null");
        this.git = requireNonNull(git, "git is null");
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
            CherryPickExecution execution = store.createOrGet(CherryPickExecution.create(
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
                return store.save(execution.finish(FinishReason.COMPLETED, summary(execution), clock.instant()));
            }
            return act(execution);
        }
        finally {
            lock.unlock();
        }
    }

    private CherryPickExecution act(CherryPickExecution execution)
    {
        Path worktree = execution.workspace().worktree();
        String expectedHead = execution.currentTargetSha();
        SequenceState before = sequencer.observe(worktree);

        if (before.kind() == SequenceState.Kind.COMPLETED) {
            if (!before.head().equals(expectedHead)) {
                return pause(execution, PauseReason.RECONCILIATION_MISMATCH,
                        "Work branch is at " + before.head() + " but the plan expects " + expectedHead);
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
            // Agent resolution arrives in the next checkpoint. Until then the
            // range pauses rather than pretending it can continue.
            return pause(execution, PauseReason.AGENT_TURN_FAILED,
                    "Conflict on " + before.stoppedCommit().orElse("an unknown commit")
                            + "; agent resolution is not enabled yet");
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
            return store.save(updated.finish(FinishReason.COMPLETED, summary(updated), clock.instant()));
        }
        if (after.kind() == SequenceState.Kind.STOPPED_CONFLICT) {
            // The conflict is already known, so pause now rather than leaving a
            // conflicted range marked RUNNING until the next monitor tick.
            return pause(updated, PauseReason.AGENT_TURN_FAILED,
                    "Conflict on " + after.stoppedCommit().orElse("an unknown commit")
                            + "; agent resolution is not enabled yet");
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

    private record CreatedCommit(String targetSha, Optional<String> sourceSha) {}
}
