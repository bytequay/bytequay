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
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.core.ci.CiAutofixChecks.checkOptionalText;
import static com.bytequay.core.ci.CiAutofixChecks.checkSha;
import static com.bytequay.core.ci.CiAutofixChecks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
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
        String expectedRemoteHead,
        String latestObservedBaseSha,
        Authorization authorization,
        int attemptsUsed,
        RerunCounter rerunCounter,
        Optional<FinalFixupCheck> finalFixupCheck,
        Optional<String> currentTurnId,
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
        checkSha(expectedRemoteHead, "expectedRemoteHead");
        checkSha(latestObservedBaseSha, "latestObservedBaseSha");
        requireNonNull(authorization, "authorization is null");
        checkArgument(attemptsUsed >= 0 && attemptsUsed <= authorization.maxFixAttempts(),
                "attemptsUsed exceeds authorization");
        requireNonNull(rerunCounter, "rerunCounter is null");
        requireNonNull(finalFixupCheck, "finalFixupCheck is null");
        checkOptionalText(currentTurnId, "currentTurnId", 128);
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
        validateStatus(status, pauseReason, finishReason, summary, currentTurnId, terminalAt);
    }

    public static CiAutofixRun create(
            CiAutofixRunId id,
            String startIdempotencyKey,
            Scope scope,
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
                expectedRemoteHead,
                latestObservedBaseSha,
                authorization,
                0,
                RerunCounter.empty(),
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
        if (agentTaskId.isPresent() && !agentTaskId.orElseThrow().equals(taskId)) {
            throw new IllegalStateException("run already owns another agent task");
        }
        return copy(
                Optional.of(taskId),
                status,
                pauseReason,
                finishReason,
                summary,
                attemptsUsed,
                rerunCounter,
                finalFixupCheck,
                currentTurnId,
                pushJournal,
                rerunJournal,
                warnings,
                attempts,
                now,
                terminalAt);
    }

    public CiAutofixRun addAttempt(CiAutofixAttempt attempt, Instant now)
    {
        requireActive(now);
        requireNonNull(attempt, "attempt is null");
        checkArgument(attempt.runId().equals(id), "attempt belongs to another run");
        if (attempts.size() >= authorization.maxFixAttempts()) {
            throw new IllegalStateException("fix attempt limit is exhausted");
        }
        int expectedNumber = attempts.size() + 1;
        checkArgument(attempt.number() == expectedNumber, "attempt number must be " + expectedNumber);
        return copy(
                agentTaskId,
                status,
                pauseReason,
                finishReason,
                summary,
                attemptsUsed,
                rerunCounter,
                finalFixupCheck,
                currentTurnId,
                pushJournal,
                rerunJournal,
                warnings,
                ImmutableList.<CiAutofixAttempt>builder().addAll(attempts).add(attempt).build(),
                now,
                terminalAt);
    }

    public CiAutofixRun linkAttemptTurn(CiAutofixAttemptId attemptId, String turnId, Instant now)
    {
        requireActive(now);
        checkText(turnId, "turnId", 128);
        if (currentTurnId.isPresent() && !currentTurnId.orElseThrow().equals(turnId)) {
            throw new IllegalStateException("run already owns another current turn");
        }
        CiAutofixAttempt attempt = requireAttempt(attempts, attemptId);
        boolean alreadyLinked = attempt.agentTurnId().isPresent();
        CiAutofixAttempt updatedAttempt = attempt.withAgentTurn(turnId, now);
        int updatedAttemptsUsed = alreadyLinked ? attemptsUsed : attemptsUsed + 1;
        if (updatedAttemptsUsed > authorization.maxFixAttempts()) {
            throw new IllegalStateException("fix attempt limit is exhausted");
        }
        return copy(
                agentTaskId,
                status,
                pauseReason,
                finishReason,
                summary,
                updatedAttemptsUsed,
                rerunCounter,
                finalFixupCheck,
                Optional.of(turnId),
                pushJournal,
                rerunJournal,
                warnings,
                replaceAttempt(attempts, updatedAttempt),
                now,
                terminalAt);
    }

    public CiAutofixRun linkUserTurn(String turnId, Instant now)
    {
        requireActive(now);
        checkText(turnId, "turnId", 128);
        if (currentTurnId.isPresent() && !currentTurnId.orElseThrow().equals(turnId)) {
            throw new IllegalStateException("run already owns another current turn");
        }
        return copy(
                agentTaskId,
                status,
                pauseReason,
                finishReason,
                summary,
                attemptsUsed,
                rerunCounter,
                finalFixupCheck,
                Optional.of(turnId),
                pushJournal,
                rerunJournal,
                warnings,
                attempts,
                now,
                terminalAt);
    }

    public CiAutofixRun updateAttempt(CiAutofixAttempt attempt, Instant now)
    {
        requireMutable(now);
        requireNonNull(attempt, "attempt is null");
        CiAutofixAttempt current = requireAttempt(attempts, attempt.id());
        checkArgument(attempt.runId().equals(id) && attempt.number() == current.number(),
                "attempt identity changed");
        return copy(
                agentTaskId,
                status,
                pauseReason,
                finishReason,
                summary,
                attemptsUsed,
                rerunCounter,
                finalFixupCheck,
                currentTurnId,
                pushJournal,
                rerunJournal,
                warnings,
                replaceAttempt(attempts, attempt),
                now,
                terminalAt);
    }

    public CiAutofixRun clearCurrentTurn(String turnId, Instant now)
    {
        requireMutable(now);
        checkText(turnId, "turnId", 128);
        checkArgument(currentTurnId.isEmpty() || currentTurnId.orElseThrow().equals(turnId),
                "turnId is not the current turn");
        return copy(
                agentTaskId,
                status,
                pauseReason,
                finishReason,
                summary,
                attemptsUsed,
                rerunCounter,
                finalFixupCheck,
                Optional.empty(),
                pushJournal,
                rerunJournal,
                warnings,
                attempts,
                now,
                terminalAt);
    }

    public CiAutofixRun recordPushJournal(PushJournal journal, Instant now)
    {
        requireActive(now);
        requireNonNull(journal, "journal is null");
        requireAttempt(attempts, journal.attemptId());
        checkArgument(journal.expectedRemoteSha().equals(expectedRemoteHead),
                "push journal expected SHA differs from expected remote head");
        return copy(
                agentTaskId,
                status,
                pauseReason,
                finishReason,
                summary,
                attemptsUsed,
                rerunCounter,
                finalFixupCheck,
                currentTurnId,
                Optional.of(journal),
                rerunJournal,
                warnings,
                attempts,
                now,
                terminalAt);
    }

    public CiAutofixRun recordRerunJournal(RerunJournal journal, Instant now)
    {
        requireActive(now);
        requireNonNull(journal, "journal is null");
        requireAttempt(attempts, journal.attemptId());
        checkArgument(journal.headSha().equals(expectedRemoteHead),
                "rerun journal head differs from expected remote head");
        RerunCounter updatedCounter = rerunCounter.record(journal.headSha());
        return copy(
                agentTaskId,
                status,
                pauseReason,
                finishReason,
                summary,
                attemptsUsed,
                updatedCounter,
                finalFixupCheck,
                currentTurnId,
                pushJournal,
                Optional.of(journal),
                warnings,
                attempts,
                now,
                terminalAt);
    }

    public CiAutofixRun clearPushJournal(Instant now)
    {
        requireActive(now);
        return copy(
                agentTaskId,
                status,
                pauseReason,
                finishReason,
                summary,
                attemptsUsed,
                rerunCounter,
                finalFixupCheck,
                currentTurnId,
                Optional.empty(),
                rerunJournal,
                warnings,
                attempts,
                now,
                terminalAt);
    }

    public CiAutofixRun clearRerunJournal(Instant now)
    {
        requireActive(now);
        return copy(
                agentTaskId,
                status,
                pauseReason,
                finishReason,
                summary,
                attemptsUsed,
                rerunCounter,
                finalFixupCheck,
                currentTurnId,
                pushJournal,
                Optional.empty(),
                warnings,
                attempts,
                now,
                terminalAt);
    }

    public CiAutofixRun observeBase(String headSha, String baseSha, Instant now)
    {
        requireActive(now);
        checkSha(headSha, "headSha");
        checkSha(baseSha, "baseSha");
        checkArgument(headSha.equals(expectedRemoteHead), "observed head differs from expected remote head");
        return copyWithRemote(
                expectedRemoteHead,
                baseSha,
                agentTaskId,
                status,
                pauseReason,
                finishReason,
                summary,
                attemptsUsed,
                rerunCounter,
                finalFixupCheck,
                currentTurnId,
                pushJournal,
                rerunJournal,
                warnings,
                attempts,
                now,
                terminalAt);
    }

    public CiAutofixRun recordPublishedAttempt(String pushedSha, Instant pushedAt, Instant now)
    {
        requireActive(now);
        checkSha(pushedSha, "pushedSha");
        requireNonNull(pushedAt, "pushedAt is null");
        PushJournal journal = pushJournal.orElseThrow(() -> new IllegalStateException("run has no push journal"));
        checkArgument(journal.expectedRemoteSha().equals(expectedRemoteHead),
                "push journal expected SHA differs from the run");
        checkArgument(journal.localHeadSha().equals(pushedSha), "pushed SHA differs from the push journal");
        CiAutofixAttempt attempt = requireAttempt(attempts, journal.attemptId());
        CiAutofixAttempt published = attempt
                .withPushResult(journal.expectedRemoteSha(), pushedSha, pushedAt, now)
                .withCiResult(CiAutofixAttempt.CiResult.PENDING, now);
        return copyWithRemote(
                pushedSha,
                latestObservedBaseSha,
                agentTaskId,
                status,
                pauseReason,
                finishReason,
                summary,
                attemptsUsed,
                RerunCounter.empty(),
                Optional.empty(),
                currentTurnId,
                Optional.empty(),
                rerunJournal,
                warnings,
                replaceAttempt(attempts, published),
                now,
                terminalAt);
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
        return copyWithRemote(
                expectedRemoteHead,
                baseSha,
                agentTaskId,
                status,
                pauseReason,
                finishReason,
                summary,
                attemptsUsed,
                rerunCounter,
                finalFixupCheck,
                currentTurnId,
                pushJournal,
                rerunJournal,
                warnings,
                replaceAttempt(attempts, attempt.withCiResult(result, now)),
                now,
                terminalAt);
    }

    public CiAutofixRun observeRerunAttempt(int observedAttempt, Instant now)
    {
        requireActive(now);
        RerunJournal journal = rerunJournal.orElseThrow(() -> new IllegalStateException("run has no rerun journal"));
        checkArgument(observedAttempt > journal.previousAttempt(),
                "observed rerun attempt must be later than the previous attempt");
        RerunJournal observed = new RerunJournal(
                journal.attemptId(),
                journal.workflowRunId(),
                journal.headSha(),
                journal.previousAttempt(),
                journal.startedAt(),
                Optional.of(observedAttempt));
        return copy(
                agentTaskId,
                status,
                pauseReason,
                finishReason,
                summary,
                attemptsUsed,
                rerunCounter,
                finalFixupCheck,
                currentTurnId,
                pushJournal,
                Optional.of(observed),
                warnings,
                attempts,
                now,
                terminalAt);
    }

    public CiAutofixRun resolveRerun(CiAutofixAttempt.CiResult result, String baseSha, Instant now)
    {
        requireActive(now);
        requireNonNull(result, "result is null");
        checkSha(baseSha, "baseSha");
        RerunJournal journal = rerunJournal.orElseThrow(() -> new IllegalStateException("run has no rerun journal"));
        checkArgument(journal.observedAttempt().isPresent(), "rerun has not been observed");
        CiAutofixAttempt attempt = requireAttempt(attempts, journal.attemptId());
        return copyWithRemote(
                expectedRemoteHead,
                baseSha,
                agentTaskId,
                status,
                pauseReason,
                finishReason,
                summary,
                attemptsUsed,
                rerunCounter,
                finalFixupCheck,
                currentTurnId,
                pushJournal,
                Optional.empty(),
                warnings,
                replaceAttempt(attempts, attempt.withCiResult(result, now)),
                now,
                terminalAt);
    }

    public CiAutofixRun withFinalFixupCheck(FinalFixupCheck check, Instant now)
    {
        requireActive(now);
        requireNonNull(check, "check is null");
        return copy(
                agentTaskId,
                status,
                pauseReason,
                finishReason,
                summary,
                attemptsUsed,
                rerunCounter,
                Optional.of(check),
                currentTurnId,
                pushJournal,
                rerunJournal,
                warnings,
                attempts,
                now,
                terminalAt);
    }

    public CiAutofixRun addWarning(Warning warning, Instant now)
    {
        requireMutable(now);
        requireNonNull(warning, "warning is null");
        if (warnings.size() >= MAX_WARNINGS) {
            throw new IllegalStateException("warning limit is exhausted");
        }
        return copy(
                agentTaskId,
                status,
                pauseReason,
                finishReason,
                summary,
                attemptsUsed,
                rerunCounter,
                finalFixupCheck,
                currentTurnId,
                pushJournal,
                rerunJournal,
                ImmutableList.<Warning>builder().addAll(warnings).add(warning).build(),
                attempts,
                now,
                terminalAt);
    }

    public CiAutofixRun pause(PauseReason reason, String pausedSummary, Instant now)
    {
        requireActive(now);
        requireNonNull(reason, "reason is null");
        checkText(pausedSummary, "pausedSummary", 8 * 1024);
        return copy(
                agentTaskId,
                Status.PAUSED,
                Optional.of(reason),
                Optional.empty(),
                Optional.of(pausedSummary),
                attemptsUsed,
                rerunCounter,
                finalFixupCheck,
                Optional.empty(),
                pushJournal,
                rerunJournal,
                warnings,
                attempts,
                now,
                Optional.empty());
    }

    public CiAutofixRun resume(Instant now)
    {
        requireMutable(now);
        if (status != Status.PAUSED) {
            throw new IllegalStateException("only a paused run can resume");
        }
        return copy(
                agentTaskId,
                Status.ACTIVE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                attemptsUsed,
                rerunCounter,
                finalFixupCheck,
                Optional.empty(),
                pushJournal,
                rerunJournal,
                warnings,
                attempts,
                now,
                Optional.empty());
    }

    public CiAutofixRun finish(FinishReason reason, String finalSummary, Instant now)
    {
        requireMutable(now);
        requireNonNull(reason, "reason is null");
        checkText(finalSummary, "finalSummary", 8 * 1024);
        return copy(
                agentTaskId,
                Status.FINISHED,
                Optional.empty(),
                Optional.of(reason),
                Optional.of(finalSummary),
                attemptsUsed,
                rerunCounter,
                finalFixupCheck,
                Optional.empty(),
                pushJournal,
                rerunJournal,
                warnings,
                attempts,
                now,
                Optional.of(now));
    }

    public CiAutofixRun withVersion(long updatedVersion)
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
                expectedRemoteHead,
                latestObservedBaseSha,
                authorization,
                attemptsUsed,
                rerunCounter,
                finalFixupCheck,
                currentTurnId,
                pushJournal,
                rerunJournal,
                warnings,
                attempts,
                updatedVersion,
                createdAt,
                updatedAt,
                terminalAt);
    }

    private CiAutofixRun copy(
            Optional<String> updatedAgentTaskId,
            Status updatedStatus,
            Optional<PauseReason> updatedPauseReason,
            Optional<FinishReason> updatedFinishReason,
            Optional<String> updatedSummary,
            int updatedAttemptsUsed,
            RerunCounter updatedRerunCounter,
            Optional<FinalFixupCheck> updatedFinalCheck,
            Optional<String> updatedCurrentTurnId,
            Optional<PushJournal> updatedPushJournal,
            Optional<RerunJournal> updatedRerunJournal,
            List<Warning> updatedWarnings,
            List<CiAutofixAttempt> updatedAttempts,
            Instant now,
            Optional<Instant> updatedTerminalAt)
    {
        return copyWithRemote(
                expectedRemoteHead,
                latestObservedBaseSha,
                updatedAgentTaskId,
                updatedStatus,
                updatedPauseReason,
                updatedFinishReason,
                updatedSummary,
                updatedAttemptsUsed,
                updatedRerunCounter,
                updatedFinalCheck,
                updatedCurrentTurnId,
                updatedPushJournal,
                updatedRerunJournal,
                updatedWarnings,
                updatedAttempts,
                now,
                updatedTerminalAt);
    }

    private CiAutofixRun copyWithRemote(
            String updatedExpectedRemoteHead,
            String updatedLatestObservedBaseSha,
            Optional<String> updatedAgentTaskId,
            Status updatedStatus,
            Optional<PauseReason> updatedPauseReason,
            Optional<FinishReason> updatedFinishReason,
            Optional<String> updatedSummary,
            int updatedAttemptsUsed,
            RerunCounter updatedRerunCounter,
            Optional<FinalFixupCheck> updatedFinalCheck,
            Optional<String> updatedCurrentTurnId,
            Optional<PushJournal> updatedPushJournal,
            Optional<RerunJournal> updatedRerunJournal,
            List<Warning> updatedWarnings,
            List<CiAutofixAttempt> updatedAttempts,
            Instant now,
            Optional<Instant> updatedTerminalAt)
    {
        return new CiAutofixRun(
                id,
                startIdempotencyKey,
                scope,
                updatedAgentTaskId,
                updatedStatus,
                updatedPauseReason,
                updatedFinishReason,
                updatedSummary,
                updatedExpectedRemoteHead,
                updatedLatestObservedBaseSha,
                authorization,
                updatedAttemptsUsed,
                updatedRerunCounter,
                updatedFinalCheck,
                updatedCurrentTurnId,
                updatedPushJournal,
                updatedRerunJournal,
                updatedWarnings,
                updatedAttempts,
                version,
                createdAt,
                now,
                updatedTerminalAt);
    }

    private void requireMutable(Instant now)
    {
        requireNonNull(now, "now is null");
        if (status == Status.FINISHED) {
            throw new IllegalStateException("run is finished");
        }
        checkArgument(!now.isBefore(updatedAt), "update time is before the previous update");
    }

    private void requireActive(Instant now)
    {
        requireMutable(now);
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("run is not active");
        }
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
            checkArgument(attempt.number() == index + 1 && ids.add(attempt.id()),
                    "attempts are not uniquely and consecutively ordered");
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
                                && currentTurnId.isEmpty() && terminalAt.isPresent(),
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
        TURN_FAILED,
        TURN_INTERRUPTED,
        TURN_CANCELLED,
        INVALID_GIT_RESULT,
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
            String author,
            String headRef,
            String baseRef,
            Path worktree,
            String laneId)
    {
        public Scope
        {
            requireNonNull(pullRequest, "pullRequest is null");
            checkText(pullRequest.owner(), "repository owner", 100);
            checkText(pullRequest.repo(), "repository name", 100);
            checkText(author, "author", 100);
            checkText(headRef, "headRef", 255);
            checkText(baseRef, "baseRef", 255);
            requireNonNull(worktree, "worktree is null");
            checkArgument(worktree.isAbsolute(), "worktree is not absolute");
            worktree = worktree.normalize();
            checkText(laneId, "laneId", 100);
        }

        public String activeBranchSlot()
        {
            return pullRequest.repoFullName().toLowerCase(java.util.Locale.ROOT) + ":" + headRef;
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
            checkArgument(maxFixAttempts > 0 && maxFixAttempts <= MAX_FIX_ATTEMPTS,
                    "maxFixAttempts must be between 1 and " + MAX_FIX_ATTEMPTS);
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
            if (updated > MAX_RERUNS_PER_HEAD) {
                throw new IllegalStateException("rerun limit is exhausted for head " + sha);
            }
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
            checkArgument(workflowRunId > 0 && previousAttempt > 0,
                    "workflow run and previous attempt must be positive");
            checkSha(headSha, "headSha");
            requireNonNull(startedAt, "startedAt is null");
            requireNonNull(observedAttempt, "observedAttempt is null");
            checkArgument(observedAttempt.isEmpty() || observedAttempt.orElseThrow() > previousAttempt,
                    "observedAttempt must be later than previousAttempt");
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
