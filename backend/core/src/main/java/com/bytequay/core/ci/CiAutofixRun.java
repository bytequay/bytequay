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
package com.bytequay.core.ci;

import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkSha;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Complete durable projection of one consented CI Autofix run. */
public record CiAutofixRun(
        CiAutofixRunId id,
        String startIdempotencyKey,
        Scope scope,
        Optional<String> agentTaskId,
        Status status,
        Optional<PauseReason> pauseReason,
        Optional<FinishReason> finishReason,
        Optional<String> summary,
        Optional<String> taskStartCommit,
        String expectedRemoteHead,
        String latestObservedBaseSha,
        Authorization authorization,
        int attemptsUsed,
        RerunCounter rerunCounter,
        Optional<FinalFixupCheck> finalFixupCheck,
        Optional<String> currentTurnId,
        Optional<String> currentRebaseId,
        Optional<PushJournal> pushJournal,
        Optional<RerunJournal> rerunJournal,
        List<Warning> warnings,
        List<CiAutofixAttempt> attempts,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Optional<Instant> terminalAt)
{
    public static final int MAX_FIX_ATTEMPTS = 3;
    public static final int MAX_RERUNS_PER_HEAD = 3;
    private static final int MAX_WARNINGS = 50;

    public CiAutofixRun
    {
        requireNonNull(id, "id is null");
        checkText(startIdempotencyKey, "startIdempotencyKey", 200);
        requireNonNull(scope, "scope is null");
        checkOptionalText(agentTaskId, "agentTaskId", 128);
        requireNonNull(status, "status is null");
        requireNonNull(pauseReason, "pauseReason is null");
        requireNonNull(finishReason, "finishReason is null");
        checkOptionalText(summary, "summary", 8 * 1024);
        requireNonNull(taskStartCommit, "taskStartCommit is null");
        taskStartCommit.ifPresent(value -> checkSha(value, "taskStartCommit"));
        checkSha(expectedRemoteHead, "expectedRemoteHead");
        checkSha(latestObservedBaseSha, "latestObservedBaseSha");
        requireNonNull(authorization, "authorization is null");
        checkArgument(attemptsUsed >= 0 && attemptsUsed <= authorization.maxFixAttempts(), "attemptsUsed exceeds authorization");
        requireNonNull(rerunCounter, "rerunCounter is null");
        requireNonNull(finalFixupCheck, "finalFixupCheck is null");
        checkOptionalText(currentTurnId, "currentTurnId", 128);
        checkOptionalText(currentRebaseId, "currentRebaseId", 128);
        checkArgument(currentTurnId.isEmpty() || currentRebaseId.isEmpty(),
                "run cannot own a fix turn and a branch rebase together");
        requireNonNull(pushJournal, "pushJournal is null");
        requireNonNull(rerunJournal, "rerunJournal is null");
        requireNonNull(warnings, "warnings is null");
        warnings = ImmutableList.copyOf(warnings);
        checkArgument(warnings.size() <= MAX_WARNINGS, "warnings exceeds " + MAX_WARNINGS + " values");
        requireNonNull(attempts, "attempts is null");
        attempts = ImmutableList.copyOf(attempts);
        validateAttempts(id, authorization, attemptsUsed, attempts);
        if (pushJournal.isPresent()) {
            requireAttempt(attempts, pushJournal.orElseThrow().attemptId());
        }
        if (rerunJournal.isPresent()) {
            requireAttempt(attempts, rerunJournal.orElseThrow().attemptId());
        }
        checkArgument(version >= 0, "version is negative");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
        requireNonNull(terminalAt, "terminalAt is null");
        checkArgument(!updatedAt.isBefore(createdAt), "updatedAt is before createdAt");
        terminalAt.ifPresent(value -> {
            checkArgument(!value.isBefore(createdAt), "terminalAt is before createdAt");
        });
        validateStatus(status, pauseReason, finishReason, summary, currentTurnId, currentRebaseId, terminalAt);
    }

    public static CiAutofixRun create(
            CiAutofixRunId id,
            String startIdempotencyKey,
            Scope scope,
            String taskStartCommit,
            String expectedRemoteHead,
            String latestObservedBaseSha,
            Authorization authorization,
            Instant now)
    {
        return new CiAutofixRun(
                id,
                startIdempotencyKey,
                scope,
                Optional.empty(),
                Status.ACTIVE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(taskStartCommit),
                expectedRemoteHead,
                latestObservedBaseSha,
                authorization,
                0,
                RerunCounter.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ImmutableList.of(),
                ImmutableList.of(),
                0,
                now,
                now,
                Optional.empty());
    }

    public CiAutofixRun assignAgentTask(String taskId, Instant now)
    {
        requireMutable(now);
        checkText(taskId, "taskId", 128);
        checkState(agentTaskId.isEmpty() || agentTaskId.orElseThrow().equals(taskId), "run already owns another agent task");
        return edit(now, run -> run.agentTaskId = Optional.of(taskId));
    }

    public CiAutofixRun addAttempt(CiAutofixAttempt attempt, Instant now)
    {
        requireActive(now);
        requireNonNull(attempt, "attempt is null");
        checkArgument(attempt.runId().equals(id), "attempt belongs to another run");
        checkState(attempts.size() < authorization.maxFixAttempts(), "fix attempt limit is exhausted");
        int expectedNumber = attempts.size() + 1;
        checkArgument(attempt.number() == expectedNumber, "attempt number must be " + expectedNumber);
        return edit(now, run -> run.attempts = ImmutableList.<CiAutofixAttempt>builder().addAll(attempts).add(attempt).build());
    }

    public CiAutofixRun linkAttemptTurn(CiAutofixAttemptId attemptId, String turnId, Instant now)
    {
        requireActive(now);
        checkText(turnId, "turnId", 128);
        checkState(currentRebaseId.isEmpty(), "run already owns a branch rebase");
        checkState(currentTurnId.isEmpty() || currentTurnId.orElseThrow().equals(turnId), "run already owns another current turn");
        CiAutofixAttempt attempt = requireAttempt(attempts, attemptId);
        boolean alreadyLinked = attempt.agentTurnId().isPresent();
        CiAutofixAttempt updatedAttempt = attempt.withAgentTurn(turnId, now);
        int updatedAttemptsUsed = alreadyLinked ? attemptsUsed : attemptsUsed + 1;
        checkState(updatedAttemptsUsed <= authorization.maxFixAttempts(), "fix attempt limit is exhausted");
        return edit(now, run -> {
            run.attemptsUsed = updatedAttemptsUsed;
            run.currentTurnId = Optional.of(turnId);
            run.attempts = replaceAttempt(attempts, updatedAttempt);
        });
    }

    public CiAutofixRun linkUserTurn(String turnId, Instant now)
    {
        requireActive(now);
        checkText(turnId, "turnId", 128);
        checkState(currentRebaseId.isEmpty(), "run already owns a branch rebase");
        checkState(currentTurnId.isEmpty() || currentTurnId.orElseThrow().equals(turnId), "run already owns another current turn");
        return edit(now, run -> run.currentTurnId = Optional.of(turnId));
    }

    public CiAutofixRun updateAttempt(CiAutofixAttempt attempt, Instant now)
    {
        requireMutable(now);
        requireNonNull(attempt, "attempt is null");
        CiAutofixAttempt current = requireAttempt(attempts, attempt.id());
        checkArgument(attempt.runId().equals(id) && attempt.number() == current.number(), "attempt identity changed");
        return edit(now, run -> run.attempts = replaceAttempt(attempts, attempt));
    }

    public CiAutofixRun clearCurrentTurn(String turnId, Instant now)
    {
        requireMutable(now);
        checkText(turnId, "turnId", 128);
        checkArgument(currentTurnId.isEmpty() || currentTurnId.orElseThrow().equals(turnId), "turnId is not the current turn");
        return edit(now, run -> run.currentTurnId = Optional.empty());
    }

    public CiAutofixRun linkBranchRebase(String rebaseId, Instant now)
    {
        requireActive(now);
        checkText(rebaseId, "rebaseId", 128);
        checkState(currentTurnId.isEmpty(), "run already owns a fix turn");
        checkState(currentRebaseId.isEmpty() || currentRebaseId.orElseThrow().equals(rebaseId),
                "run already owns another branch rebase");
        return edit(now, run -> run.currentRebaseId = Optional.of(rebaseId));
    }

    public CiAutofixRun completeBranchRebase(String rebaseId, String baseSha, Instant now)
    {
        requireActive(now);
        checkText(rebaseId, "rebaseId", 128);
        checkSha(baseSha, "baseSha");
        checkArgument(currentRebaseId.filter(rebaseId::equals).isPresent(),
                "rebaseId is not the current branch rebase");
        return edit(now, run -> {
            run.currentRebaseId = Optional.empty();
            run.latestObservedBaseSha = baseSha;
        });
    }

    public CiAutofixRun clearBranchRebase(String rebaseId, Instant now)
    {
        requireActive(now);
        checkText(rebaseId, "rebaseId", 128);
        checkArgument(currentRebaseId.filter(rebaseId::equals).isPresent(),
                "rebaseId is not the current branch rebase");
        return edit(now, run -> run.currentRebaseId = Optional.empty());
    }

    public CiAutofixRun recordPushJournal(PushJournal journal, Instant now)
    {
        requireActive(now);
        requireNonNull(journal, "journal is null");
        requireAttempt(attempts, journal.attemptId());
        checkArgument(journal.expectedRemoteSha().equals(expectedRemoteHead), "push journal expected SHA differs from expected remote head");
        return edit(now, run -> run.pushJournal = Optional.of(journal));
    }

    public CiAutofixRun recordRerunJournal(RerunJournal journal, Instant now)
    {
        requireActive(now);
        requireNonNull(journal, "journal is null");
        requireAttempt(attempts, journal.attemptId());
        checkArgument(journal.headSha().equals(expectedRemoteHead), "rerun journal head differs from expected remote head");
        return edit(now, run -> {
            run.rerunCounter = rerunCounter.record(journal.headSha());
            run.rerunJournal = Optional.of(journal);
        });
    }

    public CiAutofixRun clearPushJournal(Instant now)
    {
        requireActive(now);
        return edit(now, run -> run.pushJournal = Optional.empty());
    }

    public CiAutofixRun clearRerunJournal(Instant now)
    {
        requireActive(now);
        return edit(now, run -> run.rerunJournal = Optional.empty());
    }

    public CiAutofixRun observeBase(String headSha, String baseSha, Instant now)
    {
        requireActive(now);
        checkSha(headSha, "headSha");
        checkSha(baseSha, "baseSha");
        checkArgument(headSha.equals(expectedRemoteHead), "observed head differs from expected remote head");
        return edit(now, run -> run.latestObservedBaseSha = baseSha);
    }

    public CiAutofixRun recordPublishedAttempt(String pushedSha, Instant pushedAt, Instant now)
    {
        requireActive(now);
        checkSha(pushedSha, "pushedSha");
        requireNonNull(pushedAt, "pushedAt is null");
        PushJournal journal = pushJournal.orElseThrow(() -> new IllegalStateException("run has no push journal"));
        checkArgument(journal.expectedRemoteSha().equals(expectedRemoteHead), "push journal expected SHA differs from the run");
        checkArgument(journal.localHeadSha().equals(pushedSha), "pushed SHA differs from the push journal");
        CiAutofixAttempt attempt = requireAttempt(attempts, journal.attemptId());
        CiAutofixAttempt published = attempt
                .withPushResult(journal.expectedRemoteSha(), pushedSha, pushedAt, now)
                .withCiResult(CiAutofixAttempt.CiResult.PENDING, now);
        return edit(now, run -> {
            run.expectedRemoteHead = pushedSha;
            run.rerunCounter = RerunCounter.empty();
            run.finalFixupCheck = Optional.empty();
            run.pushJournal = Optional.empty();
            run.attempts = replaceAttempt(attempts, published);
        });
    }

    public CiAutofixRun observeCiResult(
            CiAutofixAttemptId attemptId,
            CiAutofixAttempt.CiResult result,
            String baseSha,
            Instant now)
    {
        requireActive(now);
        requireNonNull(result, "result is null");
        checkSha(baseSha, "baseSha");
        CiAutofixAttempt attempt = requireAttempt(attempts, attemptId);
        return edit(now, run -> {
            run.latestObservedBaseSha = baseSha;
            run.attempts = replaceAttempt(attempts, attempt.withCiResult(result, now));
        });
    }

    public CiAutofixRun observeRerunAttempt(int observedAttempt, Instant now)
    {
        requireActive(now);
        RerunJournal journal = rerunJournal.orElseThrow(() -> new IllegalStateException("run has no rerun journal"));
        checkArgument(observedAttempt > journal.previousAttempt(), "observed rerun attempt must be later than the previous attempt");
        RerunJournal observed = new RerunJournal(
                journal.attemptId(),
                journal.workflowRunId(),
                journal.headSha(),
                journal.previousAttempt(),
                journal.startedAt(),
                Optional.of(observedAttempt));
        return edit(now, run -> run.rerunJournal = Optional.of(observed));
    }

    public CiAutofixRun resolveRerun(CiAutofixAttempt.CiResult result, String baseSha, Instant now)
    {
        requireActive(now);
        requireNonNull(result, "result is null");
        checkSha(baseSha, "baseSha");
        RerunJournal journal = rerunJournal.orElseThrow(() -> new IllegalStateException("run has no rerun journal"));
        checkArgument(journal.observedAttempt().isPresent(), "rerun has not been observed");
        CiAutofixAttempt attempt = requireAttempt(attempts, journal.attemptId());
        return edit(now, run -> {
            run.latestObservedBaseSha = baseSha;
            run.rerunJournal = Optional.empty();
            run.attempts = replaceAttempt(attempts, attempt.withCiResult(result, now));
        });
    }

    public CiAutofixRun withFinalFixupCheck(FinalFixupCheck check, Instant now)
    {
        requireActive(now);
        requireNonNull(check, "check is null");
        return edit(now, run -> run.finalFixupCheck = Optional.of(check));
    }

    public CiAutofixRun addWarning(Warning warning, Instant now)
    {
        requireMutable(now);
        requireNonNull(warning, "warning is null");
        checkState(warnings.size() < MAX_WARNINGS, "warning limit is exhausted");
        return edit(now, run -> run.warnings = ImmutableList.<Warning>builder().addAll(warnings).add(warning).build());
    }

    public CiAutofixRun pause(PauseReason reason, String pausedSummary, Instant now)
    {
        requireActive(now);
        requireNonNull(reason, "reason is null");
        checkText(pausedSummary, "pausedSummary", 8 * 1024);
        return edit(now, run -> {
            run.status = Status.PAUSED;
            run.pauseReason = Optional.of(reason);
            run.finishReason = Optional.empty();
            run.summary = Optional.of(pausedSummary);
            run.currentTurnId = Optional.empty();
            run.terminalAt = Optional.empty();
        });
    }

    public CiAutofixRun resume(Instant now)
    {
        requireMutable(now);
        checkState(status == Status.PAUSED, "only a paused run can resume");
        return edit(now, run -> {
            run.status = Status.ACTIVE;
            run.pauseReason = Optional.empty();
            run.finishReason = Optional.empty();
            run.summary = Optional.empty();
            run.currentTurnId = Optional.empty();
            run.terminalAt = Optional.empty();
        });
    }

    public CiAutofixRun finish(FinishReason reason, String finalSummary, Instant now)
    {
        requireMutable(now);
        requireNonNull(reason, "reason is null");
        checkText(finalSummary, "finalSummary", 8 * 1024);
        return edit(now, run -> {
            run.status = Status.FINISHED;
            run.pauseReason = Optional.empty();
            run.finishReason = Optional.of(reason);
            run.summary = Optional.of(finalSummary);
            run.currentTurnId = Optional.empty();
            run.currentRebaseId = Optional.empty();
            run.terminalAt = Optional.of(now);
        });
    }

    public CiAutofixRun withVersion(long updatedVersion)
    {
        Draft draft = new Draft(this);
        draft.version = updatedVersion;
        return draft.build();
    }

    /**
     * Applies {@code edits} to a mutable copy of this run and stamps it with
     * {@code now}. Every transition above is "this run, with these few fields
     * different", so only the fields that actually change are named.
     */
    private CiAutofixRun edit(Instant now, Consumer<Draft> edits)
    {
        Draft draft = new Draft(this);
        draft.updatedAt = now;
        edits.accept(draft);
        return draft.build();
    }

    /** Mutable scratch copy of every component, used only by {@link #edit}. */
    private static final class Draft
    {
        private final CiAutofixRunId id;
        private final String startIdempotencyKey;
        private final Scope scope;
        private final Authorization authorization;
        private final Instant createdAt;

        private Optional<String> agentTaskId;
        private Status status;
        private Optional<PauseReason> pauseReason;
        private Optional<FinishReason> finishReason;
        private Optional<String> summary;
        private final Optional<String> taskStartCommit;
        private String expectedRemoteHead;
        private String latestObservedBaseSha;
        private int attemptsUsed;
        private RerunCounter rerunCounter;
        private Optional<FinalFixupCheck> finalFixupCheck;
        private Optional<String> currentTurnId;
        private Optional<String> currentRebaseId;
        private Optional<PushJournal> pushJournal;
        private Optional<RerunJournal> rerunJournal;
        private List<Warning> warnings;
        private List<CiAutofixAttempt> attempts;
        private long version;
        private Instant updatedAt;
        private Optional<Instant> terminalAt;

        private Draft(CiAutofixRun run)
        {
            this.id = run.id;
            this.startIdempotencyKey = run.startIdempotencyKey;
            this.scope = run.scope;
            this.authorization = run.authorization;
            this.createdAt = run.createdAt;
            this.agentTaskId = run.agentTaskId;
            this.status = run.status;
            this.pauseReason = run.pauseReason;
            this.finishReason = run.finishReason;
            this.summary = run.summary;
            this.taskStartCommit = run.taskStartCommit;
            this.expectedRemoteHead = run.expectedRemoteHead;
            this.latestObservedBaseSha = run.latestObservedBaseSha;
            this.attemptsUsed = run.attemptsUsed;
            this.rerunCounter = run.rerunCounter;
            this.finalFixupCheck = run.finalFixupCheck;
            this.currentTurnId = run.currentTurnId;
            this.currentRebaseId = run.currentRebaseId;
            this.pushJournal = run.pushJournal;
            this.rerunJournal = run.rerunJournal;
            this.warnings = run.warnings;
            this.attempts = run.attempts;
            this.version = run.version;
            this.updatedAt = run.updatedAt;
            this.terminalAt = run.terminalAt;
        }

        private CiAutofixRun build()
        {
            return new CiAutofixRun(
                    id,
                    startIdempotencyKey,
                    scope,
                    agentTaskId,
                    status,
                    pauseReason,
                    finishReason,
                    summary,
                    taskStartCommit,
                    expectedRemoteHead,
                    latestObservedBaseSha,
                    authorization,
                    attemptsUsed,
                    rerunCounter,
                    finalFixupCheck,
                    currentTurnId,
                    currentRebaseId,
                    pushJournal,
                    rerunJournal,
                    warnings,
                    attempts,
                    version,
                    createdAt,
                    updatedAt,
                    terminalAt);
        }
    }
    private void requireMutable(Instant now)
    {
        requireNonNull(now, "now is null");
        checkState(status != Status.FINISHED, "run is finished");
        checkArgument(!now.isBefore(updatedAt), "update time is before the previous update");
    }

    private void requireActive(Instant now)
    {
        requireMutable(now);
        checkState(status == Status.ACTIVE, "run is not active");
    }

    private static void validateAttempts(
            CiAutofixRunId runId,
            Authorization authorization,
            int attemptsUsed,
            List<CiAutofixAttempt> attempts)
    {
        checkArgument(attempts.size() <= authorization.maxFixAttempts(), "attempts exceeds authorization");
        Set<CiAutofixAttemptId> ids = new HashSet<>();
        int linkedTurns = 0;
        for (int index = 0; index < attempts.size(); index++) {
            CiAutofixAttempt attempt = requireNonNull(attempts.get(index), "attempt is null");
            checkArgument(attempt.runId().equals(runId), "attempt belongs to another run");
            checkArgument(attempt.number() == index + 1 && ids.add(attempt.id()), "attempts are not uniquely and consecutively ordered");
            if (attempt.agentTurnId().isPresent()) {
                linkedTurns++;
            }
        }
        checkArgument(attemptsUsed == linkedTurns, "attemptsUsed disagrees with linked agent turns");
    }

    private static void validateStatus(
            Status status,
            Optional<PauseReason> pauseReason,
            Optional<FinishReason> finishReason,
            Optional<String> summary,
            Optional<String> currentTurnId,
            Optional<String> currentRebaseId,
            Optional<Instant> terminalAt)
    {
        switch (status) {
            case ACTIVE -> {
                checkArgument(pauseReason.isEmpty() && finishReason.isEmpty()
                                && summary.isEmpty() && terminalAt.isEmpty(),
                        "active run carries terminal or pause facts");
            }
            case PAUSED -> {
                checkArgument(pauseReason.isPresent() && finishReason.isEmpty() && summary.isPresent()
                                && currentTurnId.isEmpty() && terminalAt.isEmpty(),
                        "paused run facts are inconsistent");
            }
            case FINISHED -> {
                checkArgument(pauseReason.isEmpty() && finishReason.isPresent() && summary.isPresent()
                                && currentTurnId.isEmpty() && currentRebaseId.isEmpty() && terminalAt.isPresent(),
                        "finished run facts are inconsistent");
            }
        }
    }

    private static CiAutofixAttempt requireAttempt(
            List<CiAutofixAttempt> attempts,
            CiAutofixAttemptId attemptId)
    {
        requireNonNull(attemptId, "attemptId is null");
        return attempts.stream()
                .filter(attempt -> attempt.id().equals(attemptId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("attempt is not part of the run: " + attemptId));
    }

    private static List<CiAutofixAttempt> replaceAttempt(
            List<CiAutofixAttempt> attempts,
            CiAutofixAttempt replacement)
    {
        ImmutableList.Builder<CiAutofixAttempt> builder = ImmutableList.builderWithExpectedSize(attempts.size());
        for (CiAutofixAttempt attempt : attempts) {
            builder.add(attempt.id().equals(replacement.id()) ? replacement : attempt);
        }
        return builder.build();
    }

    public enum Status
    {
        ACTIVE,
        PAUSED,
        FINISHED
    }

    public enum PauseReason
    {
        AUTHENTICATION_REQUIRED,
        TURN_FAILED,
        TURN_INTERRUPTED,
        TURN_CANCELLED,
        INVALID_GIT_RESULT,
        REBASE_PAUSED,
        INVALID_FIXUP_POSITION,
        RERUN_UNAVAILABLE,
        RERUN_EXHAUSTED,
        ERROR
    }

    public enum FinishReason
    {
        GREEN,
        CANCELLED,
        STALE_HEAD,
        MAX_ATTEMPTS,
        AUTHORIZATION_EXPIRED,
        ERROR
    }

    public record Scope(
            PullRequestRef pullRequest,
            String workspaceId,
            Optional<RepoRef> physicalRepository,
            String author,
            String headRef,
            String baseRef,
            String laneId)
    {
        public Scope
        {
            requireNonNull(pullRequest, "pullRequest is null");
            checkText(pullRequest.owner(), "repository owner", 100);
            checkText(pullRequest.repo(), "repository name", 100);
            checkText(workspaceId, "workspaceId", 128);
            requireNonNull(physicalRepository, "physicalRepository is null");
            physicalRepository.ifPresent(repository -> {
                checkText(repository.owner(), "physical repository owner", 100);
                checkText(repository.repo(), "physical repository name", 100);
            });
            checkText(author, "author", 100);
            checkText(headRef, "headRef", 255);
            checkText(baseRef, "baseRef", 255);
            checkText(laneId, "laneId", 100);
        }

        public String activeBranchSlot()
        {
            return pullRequest.repoFullName().toLowerCase(Locale.ROOT) + ":" + headRef;
        }
    }

    public record Authorization(
            boolean allowFailedJobReruns,
            boolean allowHistoryRewrites,
            boolean allowExactLeasePush,
            int maxFixAttempts,
            Instant authorizedAt,
            Instant expiresAt)
    {
        public Authorization
        {
            checkArgument(maxFixAttempts > 0 && maxFixAttempts <= MAX_FIX_ATTEMPTS, "maxFixAttempts must be between 1 and " + MAX_FIX_ATTEMPTS);
            requireNonNull(authorizedAt, "authorizedAt is null");
            requireNonNull(expiresAt, "expiresAt is null");
            checkArgument(expiresAt.isAfter(authorizedAt), "expiresAt must be after authorizedAt");
        }
    }

    public record RerunCounter(Optional<String> headSha, int used)
    {
        public RerunCounter
        {
            requireNonNull(headSha, "headSha is null");
            headSha.ifPresent(sha -> checkSha(sha, "headSha"));
            checkArgument(used >= 0 && used <= MAX_RERUNS_PER_HEAD, "rerun count is outside the limit");
            checkArgument(headSha.isPresent() == (used > 0), "rerun head and count disagree");
        }

        public static RerunCounter empty()
        {
            return new RerunCounter(Optional.empty(), 0);
        }

        public RerunCounter record(String sha)
        {
            checkSha(sha, "sha");
            int updated = headSha.filter(sha::equals).isPresent() ? used + 1 : 1;
            checkState(updated <= MAX_RERUNS_PER_HEAD, "rerun limit is exhausted for head " + sha);
            return new RerunCounter(Optional.of(sha), updated);
        }
    }

    public record PushJournal(
            CiAutofixAttemptId attemptId,
            String expectedRemoteSha,
            String localHeadSha,
            String localFingerprint,
            Instant startedAt,
            int retries)
    {
        public PushJournal
        {
            requireNonNull(attemptId, "attemptId is null");
            checkSha(expectedRemoteSha, "expectedRemoteSha");
            checkSha(localHeadSha, "localHeadSha");
            checkText(localFingerprint, "localFingerprint", 200);
            requireNonNull(startedAt, "startedAt is null");
            checkArgument(retries >= 0 && retries <= 1, "push retries must be 0 or 1");
        }
    }

    public record RerunJournal(
            CiAutofixAttemptId attemptId,
            long workflowRunId,
            String headSha,
            int previousAttempt,
            Instant startedAt,
            Optional<Integer> observedAttempt)
    {
        public RerunJournal
        {
            requireNonNull(attemptId, "attemptId is null");
            checkArgument(workflowRunId > 0 && previousAttempt > 0, "workflow run and previous attempt must be positive");
            checkSha(headSha, "headSha");
            requireNonNull(startedAt, "startedAt is null");
            requireNonNull(observedAttempt, "observedAttempt is null");
            checkArgument(observedAttempt.isEmpty() || observedAttempt.orElseThrow() > previousAttempt, "observedAttempt must be later than previousAttempt");
        }
    }

    public record FinalFixupCheck(String headSha, CheckResult result, String diagnostics, Instant checkedAt)
    {
        public FinalFixupCheck
        {
            checkSha(headSha, "headSha");
            requireNonNull(result, "result is null");
            requireNonNull(diagnostics, "diagnostics is null");
            checkArgument(diagnostics.length() <= 8 * 1024, "diagnostics exceeds 8192 characters");
            requireNonNull(checkedAt, "checkedAt is null");
        }
    }

    public enum CheckResult
    {
        VALID,
        INVALID
    }

    public record Warning(String code, String message, Instant occurredAt)
    {
        public Warning
        {
            checkText(code, "code", 100);
            checkText(message, "message", 2 * 1024);
            requireNonNull(occurredAt, "occurredAt is null");
        }
    }
}
