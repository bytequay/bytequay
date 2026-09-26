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

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.spi.AgentAttachment;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.bytequay.core.ci.CiAutofixAttempt.FailureEvidence;
import com.bytequay.core.ci.CiAutofixAttempt.LogReference;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
    private static final int EVENT_PAGE_SIZE = 1000;
    private static final int MAXIMUM_EVENT_PAGES = 10;
    private static final int MAXIMUM_NOTE_CHARACTERS = 16 * 1024;

    private final GitHubActionsClient gitHub;
    private final AgentResourceService resourceService;
    private final AgentEventService eventService;

    CiAutofixLogReader(
            GitHubActionsClient gitHub,
            AgentResourceService resourceService,
            AgentEventService eventService)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.resourceService = requireNonNull(resourceService, "resourceService is null");
        this.eventService = requireNonNull(eventService, "eventService is null");
    }

    public Optional<String> readFinalMessage(TaskId taskId, TurnId turnId)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(turnId, "turnId is null");
        String result = null;
        long after = 0;
        try {
            for (int pageNumber = 0; pageNumber < MAXIMUM_EVENT_PAGES; pageNumber++) {
                List<AgentEventEnvelope> page = eventService.readAfter(taskId, after, EVENT_PAGE_SIZE);
                for (AgentEventEnvelope envelope : page) {
                    if (envelope.turnId().filter(turnId::equals).isPresent()
                            && envelope.payload() instanceof AgentEventPayload.AssistantMessage message
                            && message.messageKind() == MessageKind.ANSWER
                            && message.phase() == MessagePhase.FINAL) {
                        result = message.text();
                    }
                }
                if (page.size() < EVENT_PAGE_SIZE) {
                    break;
                }
                after = page.getLast().sequence();
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

    List<FailureEvidence> readFailures(TaskId taskId, CiHeadSnapshot snapshot)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(snapshot, "snapshot is null");
        List<Callable<FailureEvidence>> downloads = snapshot.workflowRuns().stream()
                .flatMap(workflow -> workflow.jobs().stream()
                        .filter(CiJob::actionableFailure)
                        .map(job -> (Callable<FailureEvidence>) () -> readFailure(taskId, snapshot, workflow, job)))
                .limit(MAXIMUM_FAILURES)
                .collect(toImmutableList());
        try (var executor = Executors.newFixedThreadPool(LOG_DOWNLOAD_CONCURRENCY, Thread.ofVirtual().factory())) {
            return executor.invokeAll(downloads).stream()
                    .map(future -> getUnchecked(future))
                    .collect(toImmutableList());
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub Actions log collection was interrupted", failure);
        }
    }

    private FailureEvidence readFailure(TaskId taskId, CiHeadSnapshot snapshot, CiWorkflowRun workflow, CiJob job)
    {
        BoundedCiLog log = gitHub.readLog(new ReadCiLogQuery(taskId, snapshot.pullRequest(), workflow, job, MAXIMUM_LOG_BYTES));
        Optional<LogReference> reference = log.blob().map(blob -> new LogReference(
                blob.relativePath(),
                blob.mediaType(),
                toIntExact(blob.size()),
                log.truncated() || log.omittedBeginning() || log.compacted()));
        return new FailureEvidence(
                failureSignature(workflow, job, log),
                workflow.name() + " / " + job.failureSummary() + log.unavailableReason().map(reason -> "; log unavailable: " + reason).orElse(""),
                workflow.id(),
                workflow.attempt(),
                job.id(),
                job.attempt(),
                reference);
    }

    List<AgentAttachment> attachments(TaskId taskId, List<FailureEvidence> failures)
    {
        TaskResources resources = resourceService.require(taskId);
        Path root = realPath(resources.runtimeDirectory(), "task runtime directory", true);
        ImmutableList.Builder<AgentAttachment> attachments = ImmutableList.builder();
        for (FailureEvidence failure : failures) {
            if (failure.log().isEmpty()) {
                continue;
            }
            LogReference log = failure.log().orElseThrow();
            Path path = realPath(root.resolve(log.path()).normalize(), "CI log attachment", false);
            checkArgument(path.startsWith(root), "CI log attachment escapes task runtime directory");
            attachments.add(new AgentAttachment(
                    "github-actions-job-" + failure.jobId() + "-attempt-" + failure.jobAttempt() + ".log",
                    log.mediaType(),
                    path));
        }
        return attachments.build();
    }

    static String failureSignature(CiWorkflowRun workflowRun, CiJob job, BoundedCiLog log)
    {
        checkArgument(job.workflowRunId() == workflowRun.id(), "job does not belong to workflowRun");
        StringBuilder canonical = new StringBuilder()
                .append(workflowRun.workflowId()).append('\n')
                .append(workflowRun.name().toLowerCase(Locale.ROOT)).append('\n')
                .append(workflowRun.attempt()).append('\n')
                .append(workflowRun.conclusion().orElse("")).append('\n')
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

    /** Resolves links so the containment check compares real paths. */
    private static Path realPath(Path path, String name, boolean directory)
    {
        try {
            Path real = path.toRealPath();
            checkArgument(directory ? Files.isDirectory(real) : Files.isRegularFile(real), "%s is not a %s", name, directory ? "directory" : "regular file");
            return real;
        }
        catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve " + name, e);
        }
    }
}
