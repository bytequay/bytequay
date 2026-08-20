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

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Durable facts for one program-owned rebase of a leased work branch. */
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
    private static final Pattern SHA = Pattern.compile("(?:[0-9a-f]{40}|[0-9a-f]{64})");

    public BranchRebase
    {
        requireNonNull(id, "id is null");
        text(startIdempotencyKey, "startIdempotencyKey", 200);
        text(agentTaskId, "agentTaskId", 128);
        requireNonNull(repository, "repository is null");
        requireNonNull(worktree, "worktree is null");
        checkArgument(worktree.isAbsolute(), "worktree is not absolute");
        worktree = worktree.normalize();
        text(workBranch, "workBranch", 255);
        text(baseBranch, "baseBranch", 255);
        sha(originalWorkTip, "originalWorkTip");
        sha(baseSha, "baseSha");
        requireNonNull(status, "status is null");
        requireNonNull(currentStep, "currentStep is null");
        requireNonNull(pauseReason, "pauseReason is null");
        requireNonNull(finishReason, "finishReason is null");
        optionalText(summary, "summary", MAX_SUMMARY_CHARACTERS);
        optionalSha(conflictCommitSha, "conflictCommitSha");
        checkArgument(attemptCount >= 0 && attemptCount <= MAX_AGENT_TURNS, "attemptCount is out of range");
        optionalText(currentTurnId, "currentTurnId", 128);
        requireNonNull(journal, "journal is null");
        optionalSha(resultWorkTip, "resultWorkTip");
        checkArgument(version >= 0, "version is negative");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
        requireNonNull(terminalAt, "terminalAt is null");
        checkArgument(!updatedAt.isBefore(createdAt), "updatedAt is before createdAt");
        validateStatus(status, currentStep, pauseReason, finishReason, summary,
                currentTurnId, journal, resultWorkTip, terminalAt);
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
        return copy(status, currentStep, pauseReason, finishReason, summary, conflictCommitSha,
                attemptCount, currentTurnId, Optional.of(entry), resultWorkTip, now, terminalAt);
    }

    public BranchRebase withoutJournal(Instant now)
    {
        requireMutable();
        return copy(status, currentStep, pauseReason, finishReason, summary, conflictCommitSha,
                attemptCount, currentTurnId, Optional.empty(), resultWorkTip, now, terminalAt);
    }

    public BranchRebase resolving(String stoppedCommitSha, Instant now)
    {
        requireRunning();
        sha(stoppedCommitSha, "stoppedCommitSha");
        int attempts = conflictCommitSha.filter(stoppedCommitSha::equals).isPresent() ? attemptCount : 0;
        return copy(status, Optional.of(Step.RESOLVING), pauseReason, finishReason, summary,
                Optional.of(stoppedCommitSha), attempts, currentTurnId, journal, resultWorkTip, now, terminalAt);
    }

    public BranchRebase replaying(Instant now)
    {
        requireRunning();
        return copy(status, Optional.of(Step.REPLAYING), pauseReason, finishReason, summary,
                Optional.empty(), 0, currentTurnId, journal, resultWorkTip, now, terminalAt);
    }

    public BranchRebase validating(Instant now)
    {
        requireRunning();
        return copy(status, Optional.of(Step.VALIDATING), pauseReason, finishReason, summary,
                Optional.empty(), 0, currentTurnId, journal, resultWorkTip, now, terminalAt);
    }

    public BranchRebase recordTurnStarted(Instant now)
    {
        requireRunning();
        checkState(currentStep.filter(Step.RESOLVING::equals).isPresent(), "rebase is not resolving");
        checkState(attemptCount < MAX_AGENT_TURNS, "agent turn limit reached");
        return copy(status, currentStep, pauseReason, finishReason, summary, conflictCommitSha,
                attemptCount + 1, currentTurnId, journal, resultWorkTip, now, terminalAt);
    }

    public BranchRebase withTurn(String turnId, Instant now)
    {
        requireRunning();
        text(turnId, "turnId", 128);
        checkState(currentTurnId.isEmpty(), "rebase already has an active turn");
        return copy(status, currentStep, pauseReason, finishReason, summary, conflictCommitSha,
                attemptCount, Optional.of(turnId), journal, resultWorkTip, now, terminalAt);
    }

    public BranchRebase withoutTurn(Instant now)
    {
        requireRunning();
        return copy(status, currentStep, pauseReason, finishReason, summary, conflictCommitSha,
                attemptCount, Optional.empty(), journal, resultWorkTip, now, terminalAt);
    }

    public BranchRebase pause(PauseReason reason, String diagnostic, Instant now)
    {
        requireRunning();
        requireNonNull(reason, "reason is null");
        text(diagnostic, "diagnostic", MAX_SUMMARY_CHARACTERS);
        checkState(currentTurnId.isEmpty(), "cannot pause with an active turn");
        return copy(Status.PAUSED, currentStep, Optional.of(reason), Optional.empty(),
                Optional.of(diagnostic), conflictCommitSha, attemptCount, Optional.empty(), journal,
                Optional.empty(), now, Optional.empty());
    }

    public BranchRebase resume(Instant now)
    {
        checkState(status == Status.PAUSED, "rebase is not paused");
        return copy(Status.RUNNING, currentStep, Optional.empty(), Optional.empty(), Optional.empty(),
                conflictCommitSha, attemptCount, Optional.empty(), journal, Optional.empty(), now, Optional.empty());
    }

    public BranchRebase requestAbort(ActionJournal entry, Instant now)
    {
        requireMutable();
        requireNonNull(entry, "entry is null");
        checkArgument(entry.type() == ActionType.ABORT, "abort journal has another action type");
        return copy(Status.RUNNING, currentStep.or(() -> Optional.of(Step.REPLAYING)),
                Optional.empty(), Optional.empty(), Optional.empty(), conflictCommitSha,
                attemptCount, currentTurnId, Optional.of(entry), Optional.empty(), now, Optional.empty());
    }

    public BranchRebase finish(FinishReason reason, String finalSummary, Optional<String> resultTip, Instant now)
    {
        requireMutable();
        requireNonNull(reason, "reason is null");
        text(finalSummary, "finalSummary", MAX_SUMMARY_CHARACTERS);
        optionalSha(resultTip, "resultTip");
        checkArgument((reason == FinishReason.COMPLETED) == resultTip.isPresent(),
                "only a completed rebase has a result tip");
        return copy(Status.FINISHED, Optional.empty(), Optional.empty(), Optional.of(reason),
                Optional.of(finalSummary), Optional.empty(), 0, Optional.empty(), Optional.empty(),
                resultTip, now, Optional.of(now));
    }

    public BranchRebase withVersion(long nextVersion)
    {
        return new BranchRebase(
                id, startIdempotencyKey, agentTaskId, repository, worktree, workBranch, baseBranch,
                originalWorkTip, baseSha, status, currentStep, pauseReason, finishReason, summary,
                conflictCommitSha, attemptCount, currentTurnId, journal, resultWorkTip, nextVersion,
                createdAt, updatedAt, terminalAt);
    }

    private BranchRebase copy(
            Status nextStatus,
            Optional<Step> nextStep,
            Optional<PauseReason> nextPauseReason,
            Optional<FinishReason> nextFinishReason,
            Optional<String> nextSummary,
            Optional<String> nextConflictCommit,
            int nextAttemptCount,
            Optional<String> nextTurn,
            Optional<ActionJournal> nextJournal,
            Optional<String> nextResultTip,
            Instant now,
            Optional<Instant> nextTerminalAt)
    {
        return new BranchRebase(
                id, startIdempotencyKey, agentTaskId, repository, worktree, workBranch, baseBranch,
                originalWorkTip, baseSha, nextStatus, nextStep, nextPauseReason, nextFinishReason,
                nextSummary, nextConflictCommit, nextAttemptCount, nextTurn, nextJournal, nextResultTip,
                version, createdAt, now, nextTerminalAt);
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
            checkArgument(step.isPresent() && pauseReason.isPresent() && summary.isPresent(),
                    "paused rebase is incomplete");
            checkArgument(finishReason.isEmpty() && resultTip.isEmpty() && terminalAt.isEmpty(),
                    "paused rebase has terminal state");
            return;
        }
        checkArgument(step.isEmpty() && pauseReason.isEmpty() && finishReason.isPresent()
                        && summary.isPresent() && journal.isEmpty() && terminalAt.isPresent(),
                "finished rebase is incomplete");
        checkArgument((finishReason.orElseThrow() == FinishReason.COMPLETED) == resultTip.isPresent(),
                "finished result tip disagrees with reason");
    }

    private static String text(String value, String name, int maximumLength)
    {
        requireNonNull(value, name + " is null");
        checkArgument(!value.isBlank(), name + " is blank");
        checkArgument(value.length() <= maximumLength, name + " is too long");
        return value;
    }

    private static void optionalText(Optional<String> value, String name, int maximumLength)
    {
        requireNonNull(value, name + " is null");
        value.ifPresent(text -> text(text, name, maximumLength));
    }

    private static String sha(String value, String name)
    {
        requireNonNull(value, name + " is null");
        checkArgument(SHA.matcher(value).matches(), name + " is not a full Git SHA");
        return value;
    }

    private static void optionalSha(Optional<String> value, String name)
    {
        requireNonNull(value, name + " is null");
        value.ifPresent(sha -> sha(sha, name));
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
        RESOLVING,
        VALIDATING
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
    public record ActionJournal(
            ActionType type,
            Optional<String> stoppedCommitSha,
            String expectedHead,
            int attempt,
            String idempotencyKey,
            Instant startedAt)
    {
        public ActionJournal
        {
            requireNonNull(type, "type is null");
            optionalSha(stoppedCommitSha, "stoppedCommitSha");
            sha(expectedHead, "expectedHead");
            checkArgument(attempt >= 0 && attempt <= MAX_AGENT_TURNS, "attempt is out of range");
            text(idempotencyKey, "idempotencyKey", 255);
            requireNonNull(startedAt, "startedAt is null");
        }
    }
}
