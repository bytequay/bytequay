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
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Durable facts for one bounded code-fix attempt. */
public record CiAutofixAttempt(
        CiAutofixAttemptId id,
        CiAutofixRunId runId,
        int number,
        String inputSha,
        List<FailureEvidence> failures,
        Optional<String> agentTurnId,
        Optional<String> untrustedAgentNote,
        Optional<GitResult> gitResult,
        Optional<String> expectedRemoteSha,
        Optional<String> pushedSha,
        Optional<Instant> pushedAt,
        CiResult observedCiResult,
        Optional<String> timelineEventKey,
        Instant createdAt,
        Instant updatedAt)
{
    private static final int MAX_FAILURES = 50;

    public CiAutofixAttempt
    {
        requireNonNull(id, "id is null");
        requireNonNull(runId, "runId is null");
        checkArgument(number > 0 && number <= CiAutofixRun.MAX_FIX_ATTEMPTS, "number must be between 1 and " + CiAutofixRun.MAX_FIX_ATTEMPTS);
        checkSha(inputSha, "inputSha");
        requireNonNull(failures, "failures is null");
        failures = ImmutableList.copyOf(failures);
        checkArgument(!failures.isEmpty() && failures.size() <= MAX_FAILURES, "failures must contain between 1 and " + MAX_FAILURES + " values");
        Set<String> signatures = new HashSet<>();
        checkArgument(failures.stream().allMatch(failure -> signatures.add(failure.signature())), "failures contains a duplicate signature");
        checkOptionalText(agentTurnId, "agentTurnId", 128);
        checkOptionalText(untrustedAgentNote, "untrustedAgentNote", 16 * 1024);
        requireNonNull(gitResult, "gitResult is null");
        requireNonNull(expectedRemoteSha, "expectedRemoteSha is null");
        expectedRemoteSha.ifPresent(sha -> checkSha(sha, "expectedRemoteSha"));
        requireNonNull(pushedSha, "pushedSha is null");
        pushedSha.ifPresent(sha -> checkSha(sha, "pushedSha"));
        requireNonNull(pushedAt, "pushedAt is null");
        checkArgument(pushedAt.isPresent() == pushedSha.isPresent(), "pushedAt and pushedSha must both be present or absent");
        checkArgument(pushedSha.isEmpty() || expectedRemoteSha.isPresent(), "a pushed SHA requires the expected remote SHA");
        requireNonNull(observedCiResult, "observedCiResult is null");
        checkOptionalText(timelineEventKey, "timelineEventKey", 200);
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
        checkArgument(!updatedAt.isBefore(createdAt), "updatedAt is before createdAt");
        pushedAt.ifPresent(value -> {
            checkArgument(!value.isBefore(createdAt), "pushedAt is before createdAt");
        });
    }

    public static CiAutofixAttempt create(
            CiAutofixAttemptId id,
            CiAutofixRunId runId,
            int number,
            String inputSha,
            List<FailureEvidence> failures,
            Instant now)
    {
        return new CiAutofixAttempt(
                id,
                runId,
                number,
                inputSha,
                failures,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                CiResult.UNKNOWN,
                Optional.empty(),
                now,
                now);
    }

    public CiAutofixAttempt withAgentTurn(String turnId, Instant now)
    {
        checkText(turnId, "turnId", 128);
        requireUpdateTime(now);
        checkState(agentTurnId.isEmpty() || agentTurnId.orElseThrow().equals(turnId), "attempt already owns another agent turn");
        return copy(
                Optional.of(turnId),
                untrustedAgentNote,
                gitResult,
                expectedRemoteSha,
                pushedSha,
                pushedAt,
                observedCiResult,
                timelineEventKey,
                now);
    }

    public CiAutofixAttempt withAgentNote(String note, Instant now)
    {
        checkText(note, "note", 16 * 1024);
        requireUpdateTime(now);
        return copy(
                agentTurnId,
                Optional.of(note),
                gitResult,
                expectedRemoteSha,
                pushedSha,
                pushedAt,
                observedCiResult,
                timelineEventKey,
                now);
    }

    public CiAutofixAttempt withGitResult(GitResult result, Instant now)
    {
        requireNonNull(result, "result is null");
        requireUpdateTime(now);
        return copy(
                agentTurnId,
                untrustedAgentNote,
                Optional.of(result),
                expectedRemoteSha,
                pushedSha,
                pushedAt,
                observedCiResult,
                timelineEventKey,
                now);
    }

    public CiAutofixAttempt withPushResult(String expectedSha, String actualSha, Instant pushedAt, Instant now)
    {
        checkSha(expectedSha, "expectedSha");
        checkSha(actualSha, "actualSha");
        requireNonNull(pushedAt, "pushedAt is null");
        requireUpdateTime(now);
        return copy(
                agentTurnId,
                untrustedAgentNote,
                gitResult,
                Optional.of(expectedSha),
                Optional.of(actualSha),
                Optional.of(pushedAt),
                observedCiResult,
                timelineEventKey,
                now);
    }

    public CiAutofixAttempt withCiResult(CiResult result, Instant now)
    {
        requireNonNull(result, "result is null");
        requireUpdateTime(now);
        return copy(
                agentTurnId,
                untrustedAgentNote,
                gitResult,
                expectedRemoteSha,
                pushedSha,
                pushedAt,
                result,
                timelineEventKey,
                now);
    }

    public CiAutofixAttempt withTimelineEvent(String eventKey, Instant now)
    {
        checkText(eventKey, "eventKey", 200);
        requireUpdateTime(now);
        return copy(
                agentTurnId,
                untrustedAgentNote,
                gitResult,
                expectedRemoteSha,
                pushedSha,
                pushedAt,
                observedCiResult,
                Optional.of(eventKey),
                now);
    }

    private CiAutofixAttempt copy(
            Optional<String> updatedAgentTurnId,
            Optional<String> updatedAgentNote,
            Optional<GitResult> updatedGitResult,
            Optional<String> updatedExpectedRemoteSha,
            Optional<String> updatedPushedSha,
            Optional<Instant> updatedPushedAt,
            CiResult updatedCiResult,
            Optional<String> updatedTimelineEventKey,
            Instant now)
    {
        return new CiAutofixAttempt(
                id,
                runId,
                number,
                inputSha,
                failures,
                updatedAgentTurnId,
                updatedAgentNote,
                updatedGitResult,
                updatedExpectedRemoteSha,
                updatedPushedSha,
                updatedPushedAt,
                updatedCiResult,
                updatedTimelineEventKey,
                createdAt,
                now);
    }

    private void requireUpdateTime(Instant now)
    {
        requireNonNull(now, "now is null");
        checkArgument(!now.isBefore(updatedAt), "update time is before the previous update");
    }

    public enum CiResult
    {
        UNKNOWN,
        PENDING,
        PASSED,
        FAILED
    }

    public record LogReference(String path, String mediaType, int size, boolean truncated)
    {
        public LogReference
        {
            checkText(path, "path", 500);
            Path relative = Path.of(path);
            checkArgument(!relative.isAbsolute() && !relative.normalize().startsWith(".."), "path is not task-relative");
            checkText(mediaType, "mediaType", 100);
            checkArgument(size >= 0 && size <= 10 * 1024 * 1024, "size is outside the task blob limit");
        }
    }

    public record FailureEvidence(
            String signature,
            String summary,
            long workflowRunId,
            int workflowAttempt,
            long jobId,
            int jobAttempt,
            Optional<LogReference> log)
    {
        public FailureEvidence
        {
            checkText(signature, "signature", 200);
            checkText(summary, "summary", 4 * 1024);
            checkArgument(workflowRunId > 0 && jobId > 0, "GitHub workflow and job IDs must be positive");
            checkArgument(workflowAttempt > 0 && jobAttempt > 0, "workflow and job attempts must be positive");
            requireNonNull(log, "log is null");
        }
    }

    public record GitResult(
            String localHead,
            String fingerprint,
            List<String> changedPaths,
            List<String> commitSubjects)
    {
        private static final int MAX_CHANGED_PATHS = 1000;

        public GitResult
        {
            checkSha(localHead, "localHead");
            checkText(fingerprint, "fingerprint", 200);
            requireNonNull(changedPaths, "changedPaths is null");
            changedPaths = ImmutableList.copyOf(changedPaths);
            checkArgument(changedPaths.size() <= MAX_CHANGED_PATHS, "changedPaths exceeds " + MAX_CHANGED_PATHS + " values");
            Set<String> paths = new HashSet<>();
            for (String path : changedPaths) {
                checkText(path, "changedPath", 500);
                Path relative = Path.of(path);
                checkArgument(!relative.isAbsolute() && !relative.normalize().startsWith("..") && paths.add(path), "changedPaths contains an unsafe or duplicate path");
            }
            requireNonNull(commitSubjects, "commitSubjects is null");
            commitSubjects = ImmutableList.copyOf(commitSubjects);
            checkArgument(commitSubjects.size() <= MAX_CHANGED_PATHS, "commitSubjects exceeds " + MAX_CHANGED_PATHS + " values");
            commitSubjects.forEach(subject -> checkText(subject, "commitSubject", 500));
        }
    }
}
