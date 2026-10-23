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
package com.bytequay.core.rebase;

import com.bytequay.core.domain.RepoRef;
import lombok.AccessLevel;
import lombok.Builder;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

import static com.bytequay.core.domain.Checks.checkOptionalSha;
import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkSha;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Durable facts for one program-owned rebase of a leased work branch. */
@Builder(builderClassName = "Draft", builderMethodName = "", toBuilder = true, access = AccessLevel.PRIVATE)
public record BranchRebase(
        BranchRebaseId id,
        String startIdempotencyKey,
        String agentTaskId,
        RepoRef repository,
        Path worktree,
        String workBranch,
        String baseBranch,
        String originalWorkTip,
        String baseSha,
        Status status,
        Optional<Step> currentStep,
        Optional<PauseReason> pauseReason,
        Optional<FinishReason> finishReason,
        Optional<String> summary,
        Optional<String> conflictCommitSha,
        int attemptCount,
        Optional<String> currentTurnId,
        Optional<ActionJournal> journal,
        Optional<String> resultWorkTip,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Optional<Instant> terminalAt)
{
    public static final int MAX_AGENT_TURNS = 3;
    private static final int MAX_SUMMARY_CHARACTERS = 8 * 1024;

    public BranchRebase
    {
        requireNonNull(id, "id is null");
        checkText(startIdempotencyKey, "startIdempotencyKey", 200);
        checkText(agentTaskId, "agentTaskId", 128);
        requireNonNull(repository, "repository is null");
        requireNonNull(worktree, "worktree is null");
        checkArgument(worktree.isAbsolute(), "worktree is not absolute");
        worktree = worktree.normalize();
        checkText(workBranch, "workBranch", 255);
        checkText(baseBranch, "baseBranch", 255);
        checkSha(originalWorkTip, "originalWorkTip");
        checkSha(baseSha, "baseSha");
        requireNonNull(status, "status is null");
        requireNonNull(currentStep, "currentStep is null");
        requireNonNull(pauseReason, "pauseReason is null");
        requireNonNull(finishReason, "finishReason is null");
        checkOptionalText(summary, "summary", MAX_SUMMARY_CHARACTERS);
        checkOptionalSha(conflictCommitSha, "conflictCommitSha");
        checkArgument(attemptCount >= 0 && attemptCount <= MAX_AGENT_TURNS, "attemptCount is out of range");
        checkOptionalText(currentTurnId, "currentTurnId", 128);
        requireNonNull(journal, "journal is null");
        checkOptionalSha(resultWorkTip, "resultWorkTip");
        checkArgument(version >= 0, "version is negative");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
        requireNonNull(terminalAt, "terminalAt is null");
        checkArgument(!updatedAt.isBefore(createdAt), "updatedAt is before createdAt");
        validateStatus(status, currentStep, pauseReason, finishReason, summary, currentTurnId, journal, resultWorkTip, terminalAt);
    }

    public static BranchRebase create(
            BranchRebaseId id,
            String idempotencyKey,
            String agentTaskId,
            RepoRef repository,
            Path worktree,
            String workBranch,
            String baseBranch,
            String originalWorkTip,
            String baseSha,
            Instant now)
    {
        return new BranchRebase(
                id, idempotencyKey, agentTaskId, repository, worktree, workBranch, baseBranch,
                originalWorkTip, baseSha, Status.RUNNING, Optional.of(Step.REPLAYING),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0,
                Optional.empty(), Optional.empty(), Optional.empty(), 0, now, now, Optional.empty());
    }

    public BranchRebase withJournal(ActionJournal entry, Instant now)
    {
        requireRunning();
        return edit(now, draft -> draft.journal = Optional.of(entry));
    }

    public BranchRebase withoutJournal(Instant now)
    {
        requireMutable();
        return edit(now, draft -> draft.journal = Optional.empty());
    }

    public BranchRebase resolving(String stoppedCommitSha, Instant now)
    {
        requireRunning();
        checkSha(stoppedCommitSha, "stoppedCommitSha");
        int attempts = conflictCommitSha.filter(stoppedCommitSha::equals).isPresent() ? attemptCount : 0;
        return edit(now, draft -> {
            draft.currentStep = Optional.of(Step.RESOLVING);
            draft.conflictCommitSha = Optional.of(stoppedCommitSha);
            draft.attemptCount = attempts;
        });
    }

    public BranchRebase replaying(Instant now)
    {
        requireRunning();
        return edit(now, draft -> {
            draft.currentStep = Optional.of(Step.REPLAYING);
            draft.conflictCommitSha = Optional.empty();
            draft.attemptCount = 0;
        });
    }

    public BranchRebase recordTurnStarted(Instant now)
    {
        requireRunning();
        checkState(currentStep.filter(Step.RESOLVING::equals).isPresent(), "rebase is not resolving");
        checkState(attemptCount < MAX_AGENT_TURNS, "agent turn limit reached");
        return edit(now, draft -> draft.attemptCount = attemptCount + 1);
    }

    public BranchRebase withTurn(String turnId, Instant now)
    {
        requireRunning();
        checkText(turnId, "turnId", 128);
        checkState(currentTurnId.isEmpty(), "rebase already has an active turn");
        return edit(now, draft -> draft.currentTurnId = Optional.of(turnId));
    }

    public BranchRebase withoutTurn(Instant now)
    {
        requireRunning();
        return edit(now, draft -> draft.currentTurnId = Optional.empty());
    }

    public BranchRebase pause(PauseReason reason, String diagnostic, Instant now)
    {
        requireRunning();
        requireNonNull(reason, "reason is null");
        checkText(diagnostic, "diagnostic", MAX_SUMMARY_CHARACTERS);
        checkState(currentTurnId.isEmpty(), "cannot pause with an active turn");
        return edit(now, draft -> {
            draft.status = Status.PAUSED;
            draft.pauseReason = Optional.of(reason);
            draft.summary = Optional.of(diagnostic);
            draft.resultWorkTip = Optional.empty();
        });
    }

    public BranchRebase resume(Instant now)
    {
        checkState(status == Status.PAUSED, "rebase is not paused");
        return edit(now, draft -> {
            draft.status = Status.RUNNING;
            draft.pauseReason = Optional.empty();
            draft.summary = Optional.empty();
        });
    }

    public BranchRebase requestAbort(ActionJournal entry, Instant now)
    {
        requireMutable();
        requireNonNull(entry, "entry is null");
        checkArgument(entry.type() == ActionType.ABORT, "abort journal has another action type");
        return edit(now, draft -> {
            draft.status = Status.RUNNING;
            draft.currentStep = currentStep.or(() -> Optional.of(Step.REPLAYING));
            draft.pauseReason = Optional.empty();
            draft.summary = Optional.empty();
            draft.journal = Optional.of(entry);
            draft.resultWorkTip = Optional.empty();
        });
    }

    public BranchRebase finish(FinishReason reason, String finalSummary, Optional<String> resultTip, Instant now)
    {
        requireMutable();
        requireNonNull(reason, "reason is null");
        checkText(finalSummary, "finalSummary", MAX_SUMMARY_CHARACTERS);
        checkOptionalSha(resultTip, "resultTip");
        checkArgument((reason == FinishReason.COMPLETED) == resultTip.isPresent(), "only a completed rebase has a result tip");
        return edit(now, draft -> {
            draft.status = Status.FINISHED;
            draft.currentStep = Optional.empty();
            draft.pauseReason = Optional.empty();
            draft.finishReason = Optional.of(reason);
            draft.summary = Optional.of(finalSummary);
            draft.conflictCommitSha = Optional.empty();
            draft.attemptCount = 0;
            draft.currentTurnId = Optional.empty();
            draft.journal = Optional.empty();
            draft.resultWorkTip = resultTip;
            draft.terminalAt = Optional.of(now);
        });
    }

    public BranchRebase withVersion(long nextVersion)
    {
        Draft draft = toBuilder();
        draft.version = nextVersion;
        return draft.build();
    }

    /**
     * Applies {@code edits} to a mutable copy of this rebase and stamps it with {@code now}. A
     * running or paused rebase cannot hold finish facts or, except while running, a turn, so each
     * transition names only the fields it actually changes.
     */
    private BranchRebase edit(Instant now, Consumer<Draft> edits)
    {
        Draft draft = toBuilder();
        draft.updatedAt = now;
        edits.accept(draft);
        return draft.build();
    }

    private void requireRunning()
    {
        checkState(status == Status.RUNNING, "rebase is not running");
    }

    private void requireMutable()
    {
        checkState(status != Status.FINISHED, "rebase is finished");
    }

    private static void validateStatus(
            Status status,
            Optional<Step> step,
            Optional<PauseReason> pauseReason,
            Optional<FinishReason> finishReason,
            Optional<String> summary,
            Optional<String> currentTurnId,
            Optional<ActionJournal> journal,
            Optional<String> resultTip,
            Optional<Instant> terminalAt)
    {
        if (status == Status.RUNNING) {
            checkArgument(step.isPresent(), "running rebase has no step");
            checkArgument(pauseReason.isEmpty() && finishReason.isEmpty() && summary.isEmpty()
                    && terminalAt.isEmpty(), "running rebase has terminal state");
            return;
        }
        checkArgument(currentTurnId.isEmpty(), "non-running rebase has an active turn");
        if (status == Status.PAUSED) {
            checkArgument(step.isPresent() && pauseReason.isPresent() && summary.isPresent(), "paused rebase is incomplete");
            checkArgument(finishReason.isEmpty() && resultTip.isEmpty() && terminalAt.isEmpty(), "paused rebase has terminal state");
            return;
        }
        checkArgument(step.isEmpty() && pauseReason.isEmpty() && finishReason.isPresent()
                        && summary.isPresent() && journal.isEmpty() && terminalAt.isPresent(),
                "finished rebase is incomplete");
        checkArgument((finishReason.orElseThrow() == FinishReason.COMPLETED) == resultTip.isPresent(), "finished result tip disagrees with reason");
    }

    public enum Status
    {
        RUNNING,
        PAUSED,
        FINISHED
    }

    public enum Step
    {
        REPLAYING,
        RESOLVING
    }

    public enum PauseReason
    {
        UNSAFE_GIT_STATE,
        RECONCILIATION_MISMATCH,
        AGENT_TURN_FAILED,
        RETRY_LIMIT_REACHED
    }

    public enum FinishReason
    {
        COMPLETED,
        CANCELLED
    }

    public enum ActionType
    {
        START,
        AGENT_TURN,
        CONTINUE,
        SKIP,
        ABORT
    }

    /** Intent saved immediately before one external action. */
    public record ActionJournal(ActionType type, Optional<String> stoppedCommitSha, String expectedHead, int attempt, String idempotencyKey, Instant startedAt)
    {
        public ActionJournal
        {
            requireNonNull(type, "type is null");
            checkOptionalSha(stoppedCommitSha, "stoppedCommitSha");
            checkSha(expectedHead, "expectedHead");
            checkArgument(attempt >= 0 && attempt <= MAX_AGENT_TURNS, "attempt is out of range");
            checkText(idempotencyKey, "idempotencyKey", 255);
            requireNonNull(startedAt, "startedAt is null");
        }
    }
}
