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
package com.bytequay.app.web.ci;

import com.bytequay.agent.core.TaskMetrics;
import com.bytequay.core.ci.CiAutofixAttempt;
import com.bytequay.core.ci.CiAutofixRun;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

final class CiAutofixHttpModels
{
    private static final int MAXIMUM_GIT_FACTS = 200;

    private CiAutofixHttpModels() {}

    static RunResponse run(CiAutofixRun run, Optional<TaskMetrics> metrics)
    {
        return new RunResponse(
                run.id().toString(),
                run.scope().pullRequest().repoFullName(),
                run.scope().pullRequest().number(),
                run.scope().author(),
                run.scope().headRef(),
                run.scope().baseRef(),
                run.status().name(),
                activity(run),
                run.version(),
                run.createdAt(),
                run.updatedAt(),
                run.terminalAt(),
                new AuthorizationResponse(
                        run.authorization().allowFailedJobReruns(),
                        run.authorization().allowHistoryRewrites(),
                        run.authorization().allowExactLeasePush(),
                        run.authorization().maxFixAttempts(),
                        run.authorization().authorizedAt(),
                        run.authorization().expiresAt()),
                run.expectedRemoteHead(),
                run.latestObservedBaseSha(),
                run.attemptsUsed(),
                run.rerunCounter().used(),
                run.agentTaskId(),
                run.currentTurnId(),
                run.currentRebaseId(),
                run.pauseReason().map(Enum::name),
                run.finishReason().map(Enum::name),
                run.summary(),
                metrics,
                run.warnings().stream().map(warning -> new WarningResponse(
                        warning.code(), warning.message(), warning.occurredAt())).collect(toImmutableList()),
                run.pushJournal().map(journal -> new PushJournalResponse(
                        journal.attemptId().toString(),
                        journal.expectedRemoteSha(),
                        journal.localHeadSha(),
                        journal.startedAt(),
                        journal.retries())),
                run.rerunJournal().map(journal -> new RerunJournalResponse(
                        journal.attemptId().toString(),
                        journal.workflowRunId(),
                        journal.headSha(),
                        journal.previousAttempt(),
                        journal.observedAttempt(),
                        journal.startedAt())),
                run.finalFixupCheck().map(check -> new FinalCheckResponse(
                        check.headSha(), check.result().name(), check.diagnostics(), check.checkedAt())),
                run.attempts().stream().map(CiAutofixHttpModels::attempt).collect(toImmutableList()));
    }

    private static AttemptResponse attempt(CiAutofixAttempt attempt)
    {
        return new AttemptResponse(
                attempt.id().toString(),
                attempt.number(),
                attempt.inputSha(),
                attempt.failures().stream().map(failure -> new FailureResponse(
                        failure.signature(),
                        failure.summary(),
                        failure.workflowRunId(),
                        failure.workflowAttempt(),
                        failure.jobId(),
                        failure.jobAttempt())).collect(toImmutableList()),
                attempt.agentTurnId(),
                attempt.untrustedAgentNote(),
                attempt.gitResult().map(git -> new GitResponse(
                        git.localHead(),
                        git.changedPaths().stream().limit(MAXIMUM_GIT_FACTS).collect(toImmutableList()),
                        git.changedPaths().size() > MAXIMUM_GIT_FACTS,
                        git.commitSubjects().stream().limit(MAXIMUM_GIT_FACTS).collect(toImmutableList()),
                        git.commitSubjects().size() > MAXIMUM_GIT_FACTS)),
                attempt.expectedRemoteSha(),
                attempt.pushedSha(),
                attempt.pushedAt(),
                attempt.observedCiResult().name(),
                attempt.timelineEventKey(),
                attempt.createdAt(),
                attempt.updatedAt());
    }

    private static String activity(CiAutofixRun run)
    {
        if (run.status() == CiAutofixRun.Status.FINISHED) {
            return "FINISHED";
        }
        if (run.status() == CiAutofixRun.Status.PAUSED) {
            return "PAUSED";
        }
        if (run.currentTurnId().isPresent()) {
            return "FIXING";
        }
        if (run.currentRebaseId().isPresent()) {
            return "REBASING";
        }
        if (run.pushJournal().isPresent()) {
            return "PUSHING";
        }
        if (run.rerunJournal().isPresent()) {
            return "RERUNNING_CI";
        }
        if (run.attempts().isEmpty() || run.attempts().getLast().agentTurnId().isEmpty()) {
            return "PREPARING_AGENT";
        }
        CiAutofixAttempt attempt = run.attempts().getLast();
        if (attempt.gitResult().isEmpty()) {
            return "INSPECTING_GIT";
        }
        if (attempt.pushedSha().isEmpty()) {
            return "PREPARING_PUSH";
        }
        if (attempt.timelineEventKey().isEmpty()) {
            return "RECORDING_TIMELINE";
        }
        return "WATCHING_CI";
    }

    record RunResponse(
            String id,
            String repository,
            int pullRequestNumber,
            String author,
            String headRef,
            String baseRef,
            String status,
            String activity,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Optional<Instant> terminalAt,
            AuthorizationResponse authorization,
            String expectedRemoteHead,
            String latestObservedBaseSha,
            int attemptsUsed,
            int rerunsUsed,
            Optional<String> agentTaskId,
            Optional<String> currentTurnId,
            Optional<String> currentRebaseId,
            Optional<String> pauseReason,
            Optional<String> finishReason,
            Optional<String> summary,
            Optional<TaskMetrics> metrics,
            List<WarningResponse> warnings,
            Optional<PushJournalResponse> pushJournal,
            Optional<RerunJournalResponse> rerunJournal,
            Optional<FinalCheckResponse> finalCheck,
            List<AttemptResponse> attempts) {}

    record AuthorizationResponse(
            boolean allowFailedJobReruns,
            boolean allowHistoryRewrites,
            boolean allowExactLeasePush,
            int maxAttempts,
            Instant authorizedAt,
            Instant expiresAt) {}

    record WarningResponse(String code, String message, Instant occurredAt) {}

    record PushJournalResponse(
            String attemptId,
            String expectedRemoteSha,
            String localHeadSha,
            Instant startedAt,
            int retries) {}

    record RerunJournalResponse(
            String attemptId,
            long workflowRunId,
            String headSha,
            int previousAttempt,
            Optional<Integer> observedAttempt,
            Instant startedAt) {}

    record FinalCheckResponse(String headSha, String result, String diagnostics, Instant checkedAt) {}

    record AttemptResponse(
            String id,
            int number,
            String inputSha,
            List<FailureResponse> failures,
            Optional<String> agentTurnId,
            Optional<String> displayOnlyAgentNote,
            Optional<GitResponse> git,
            Optional<String> expectedRemoteSha,
            Optional<String> pushedSha,
            Optional<Instant> pushedAt,
            String observedCiResult,
            Optional<String> timelineEventKey,
            Instant createdAt,
            Instant updatedAt) {}

    record FailureResponse(
            String signature,
            String summary,
            long workflowRunId,
            int workflowAttempt,
            long jobId,
            int jobAttempt) {}

    record GitResponse(
            String localHead,
            List<String> changedPaths,
            boolean changedPathsTruncated,
            List<String> commitSubjects,
            boolean commitSubjectsTruncated) {}
}
