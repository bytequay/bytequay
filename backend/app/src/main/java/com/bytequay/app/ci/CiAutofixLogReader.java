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
import com.bytequay.core.ci.CiAutofixAttempt.FailureEvidence;
import com.bytequay.core.ci.CiAutofixAttempt.LogReference;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.utils.FileChecks.requireRealDirectory;
import static com.bytequay.app.utils.FileChecks.requireRealFile;
import static com.google.common.base.Ascii.truncate;
import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

@Component
final class CiAutofixLogReader
{
    private static final int MAXIMUM_FAILURES = 50;
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
                            && message.messageKind() == AgentEventPayload.MessageKind.ANSWER
                            && message.phase() == AgentEventPayload.MessagePhase.FINAL) {
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
        ImmutableList.Builder<FailureEvidence> failures = ImmutableList.builder();
        int count = 0;
        for (CiWorkflowRun workflow : snapshot.workflowRuns()) {
            for (CiJob job : workflow.jobs()) {
                if (!job.actionableFailure()) {
                    continue;
                }
                BoundedCiLog log = gitHub.readLog(new ReadCiLogQuery(
                        taskId,
                        snapshot.pullRequest(),
                        workflow,
                        job,
                        MAXIMUM_LOG_BYTES));
                Optional<LogReference> reference = log.blob().map(blob -> new LogReference(
                        blob.relativePath(),
                        blob.mediaType(),
                        toIntExact(blob.size()),
                        log.truncated()));
                failures.add(new FailureEvidence(
                        CiFailureSignature.compute(workflow, job, log),
                        workflow.name() + " / " + job.failureSummary(),
                        workflow.id(),
                        workflow.attempt(),
                        job.id(),
                        job.attempt(),
                        reference));
                count++;
                if (count == MAXIMUM_FAILURES) {
                    return failures.build();
                }
            }
        }
        return failures.build();
    }

    List<AgentAttachment> attachments(TaskId taskId, List<FailureEvidence> failures)
    {
        TaskResources resources = resourceService.require(taskId);
        Path root = requireRealDirectory(resources.runtimeDirectory(), "task runtime directory");
        ImmutableList.Builder<AgentAttachment> attachments = ImmutableList.builder();
        for (FailureEvidence failure : failures) {
            if (failure.log().isEmpty()) {
                continue;
            }
            LogReference log = failure.log().orElseThrow();
            Path path = requireRealFile(root.resolve(log.path()).normalize(), "CI log attachment");
            checkArgument(path.startsWith(root), "CI log attachment escapes task runtime directory");
            attachments.add(new AgentAttachment(
                    "github-actions-job-" + failure.jobId() + "-attempt-" + failure.jobAttempt() + ".log",
                    log.mediaType(),
                    path));
        }
        return attachments.build();
    }
}
