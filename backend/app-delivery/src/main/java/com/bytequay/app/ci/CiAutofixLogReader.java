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
package com.bytequay.app.ci;

import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.app.service.ci.BoundedCiLog;
import com.bytequay.app.service.ci.CiLogReader;
import com.bytequay.app.service.ci.ReadCiLogQuery;
import com.bytequay.core.ci.CiAutofixAttempt.FailureEvidence;
import com.bytequay.core.ci.CiAutofixAttempt.LogReference;
import com.bytequay.core.ci.CiHeadSnapshot;
import com.bytequay.core.ci.CiJob;
import com.bytequay.core.ci.CiRun;
import com.bytequay.core.ci.CiStep;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.PullRequestCi;
import com.google.common.hash.Hashing;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static com.google.common.base.Ascii.truncate;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.util.concurrent.Futures.getUnchecked;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

@Component
final class CiAutofixLogReader
{
    private static final int MAXIMUM_FAILURES = 50;
    private static final int LOG_DOWNLOAD_CONCURRENCY = 4;
    private static final int MAXIMUM_LOG_BYTES = 1024 * 1024;
    private static final int MAXIMUM_EVENT_PAGES = 10;
    private static final int MAXIMUM_NOTE_CHARACTERS = 16 * 1024;

    private final CiLogReader logs;
    private final AgentEventService eventService;

    CiAutofixLogReader(CiLogReader logs, AgentEventService eventService)
    {
        this.logs = requireNonNull(logs, "logs is null");
        this.eventService = requireNonNull(eventService, "eventService is null");
    }

    public Optional<String> readFinalMessage(TaskId taskId, TurnId turnId)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(turnId, "turnId is null");
        String result = null;
        // Newest first: the turn just ended, so its answer sits at the end of a log that can be far longer than the bound.
        OptionalLong before = OptionalLong.empty();
        try {
            for (int pageNumber = 0; pageNumber < MAXIMUM_EVENT_PAGES && result == null; pageNumber++) {
                AgentHistoryPage page = eventService.readHistory(taskId, before, OptionalLong.empty());
                result = page.events().reversed().stream()
                        .filter(envelope -> envelope.turnId().filter(turnId::equals).isPresent())
                        .flatMap(envelope -> envelope.rootAnswer().stream())
                        .findFirst()
                        .orElse(null);
                if (page.beforeCursor().isEmpty()) {
                    break;
                }
                before = page.beforeCursor();
            }
        }
        catch (RuntimeException ignored) {
            return Optional.empty();
        }
        if (result == null || result.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(truncate(result.strip(), MAXIMUM_NOTE_CHARACTERS, "…"));
    }

    List<FailureEvidence> readFailures(TaskId taskId, HostingTarget target, PullRequestCi ci, CiHeadSnapshot snapshot)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(target, "target is null");
        requireNonNull(ci, "ci is null");
        requireNonNull(snapshot, "snapshot is null");
        List<Callable<FailureEvidence>> downloads = snapshot.runs().stream()
                .flatMap(run -> run.jobs().stream()
                        .filter(CiJob::actionableFailure)
                        .map(job -> (Callable<FailureEvidence>) () -> readFailure(taskId, target, ci, snapshot, run, job)))
                .limit(MAXIMUM_FAILURES)
                .collect(toImmutableList());
        try (var executor = Executors.newFixedThreadPool(LOG_DOWNLOAD_CONCURRENCY, Thread.ofVirtual().factory())) {
            return executor.invokeAll(downloads).stream()
                    .map(future -> getUnchecked(future))
                    .collect(toImmutableList());
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CI log collection was interrupted", failure);
        }
    }

    private FailureEvidence readFailure(TaskId taskId, HostingTarget target, PullRequestCi ci, CiHeadSnapshot snapshot, CiRun run, CiJob job)
    {
        BoundedCiLog log = logs.read(target, ci, new ReadCiLogQuery(taskId, snapshot.pullRequest(), run, job, MAXIMUM_LOG_BYTES));
        Optional<LogReference> reference = log.blob().map(blob -> new LogReference(
                blob.relativePath(),
                blob.mediaType(),
                toIntExact(blob.size()),
                log.compacted()));
        return new FailureEvidence(
                failureSignature(run, job, log),
                run.name() + " / " + job.failureSummary() + log.unavailableReason().map(reason -> "; log unavailable: " + reason).orElse(""),
                run.id(),
                run.attempt(),
                job.id(),
                job.attempt(),
                reference);
    }

    static String failureSignature(CiRun run, CiJob job, BoundedCiLog log)
    {
        checkArgument(job.runId() == run.id(), "job does not belong to run");
        StringBuilder canonical = new StringBuilder()
                .append(run.definitionId()).append('\n')
                .append(run.name().toLowerCase(Locale.ROOT)).append('\n')
                .append(run.attempt()).append('\n')
                .append(run.conclusion().orElse("")).append('\n')
                .append(job.name().toLowerCase(Locale.ROOT)).append('\n')
                .append(job.attempt()).append('\n')
                .append(job.conclusion().orElse("")).append('\n');
        for (CiStep step : job.failedSteps()) {
            canonical.append(step.number()).append(':')
                    .append(step.name().toLowerCase(Locale.ROOT)).append(':')
                    .append(step.conclusion().orElse("")).append('\n');
        }
        log.terminalErrorLines().forEach(line -> canonical.append(line.toLowerCase(Locale.ROOT)).append('\n'));
        return "ci-" + Hashing.sha256().hashString(canonical.toString(), StandardCharsets.UTF_8);
    }
}
