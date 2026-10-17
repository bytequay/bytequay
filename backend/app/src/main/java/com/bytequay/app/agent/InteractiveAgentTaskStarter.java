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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.StoreAgentAttachmentCommand;
import com.bytequay.agent.core.StoredAgentAttachment;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.spi.AgentAttachment;
import com.bytequay.app.service.goal.WorkItemService;
import com.bytequay.app.service.recent.RecentTouchRecorder;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.JiraIssueSource;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Starts an interactive task: creates it from its source, then sends its first message as the first turn. */
@Component
public final class InteractiveAgentTaskStarter
{
    private final InteractiveAgentTaskCreator taskCreator;
    private final IssueDevelopmentService issueDevelopment;
    private final WorkItemService workItems;
    private final AgentTaskService taskService;
    private final AgentTurnService turnService;
    private final AgentResourceService resourceService;
    private final ModelSettingsService modelSettings;
    private final AgentTaskStartupProgress startupProgress;
    private final RecentTouchRecorder recent;

    public InteractiveAgentTaskStarter(
            InteractiveAgentTaskCreator taskCreator,
            IssueDevelopmentService issueDevelopment,
            WorkItemService workItems,
            AgentTaskService taskService,
            AgentTurnService turnService,
            AgentResourceService resourceService,
            ModelSettingsService modelSettings,
            AgentTaskStartupProgress startupProgress,
            RecentTouchRecorder recent)
    {
        this.taskCreator = requireNonNull(taskCreator, "taskCreator is null");
        this.issueDevelopment = requireNonNull(issueDevelopment, "issueDevelopment is null");
        this.workItems = requireNonNull(workItems, "workItems is null");
        this.taskService = requireNonNull(taskService, "taskService is null");
        this.turnService = requireNonNull(turnService, "turnService is null");
        this.resourceService = requireNonNull(resourceService, "resourceService is null");
        this.modelSettings = requireNonNull(modelSettings, "modelSettings is null");
        this.startupProgress = requireNonNull(startupProgress, "startupProgress is null");
        this.recent = requireNonNull(recent, "recent is null");
    }

    public AgentTask start(InteractiveTaskStart start)
    {
        requireNonNull(start, "start is null");
        issueDevelopment.requireAttachedSources(start.taskId(), start.issueSource(), start.jiraSource());
        start.workItemId().ifPresent(id -> workItems.requireAttemptable(start.workspaceId(), id));
        return start.issueSource()
                .map(source -> issueDevelopment.start(start.workspaceId(), start.taskId(), source, start.message(),
                        accepted -> create(start, Optional.of(accepted), Optional.empty())))
                .or(() -> start.jiraSource().map(source -> issueDevelopment.start(start.workspaceId(), start.taskId(), source, start.message(),
                        accepted -> create(start, Optional.empty(), Optional.of(accepted)))))
                .orElseGet(() -> create(start, Optional.empty(), Optional.empty()));
    }

    private AgentTask create(InteractiveTaskStart start, Optional<GitHubIssueSource> source, Optional<JiraIssueSource> jiraSource)
    {
        TaskId taskId = start.taskId();
        List<StoreAgentAttachmentCommand> uploads = start.uploads();
        try {
            boolean replay = initialTurnExists(taskId, start.idempotencyKey());
            if (!replay && !uploads.isEmpty()) {
                uploads.forEach(resourceService::validateAttachment);
                modelSettings.requireImageInput(start.laneId(), start.defaults().model(), uploads.stream().map(StoreAgentAttachmentCommand::mediaType).collect(toImmutableList()));
            }
            taskCreator.create(
                    taskId,
                    start.workspaceId(),
                    start.message(),
                    start.laneId(),
                    start.defaults(),
                    start.idempotencyKey(),
                    uploads.isEmpty() ? Optional.empty() : Optional.of(attachmentDigest(uploads)),
                    start.initialAutomation(),
                    source,
                    jiraSource,
                    source.<Consumer<AgentTask>>map(accepted -> prepared -> issueDevelopment.persistTask(prepared, accepted, start.message()))
                            .or(() -> jiraSource.map(accepted -> prepared -> issueDevelopment.persistTask(prepared, accepted, start.message())))
                            .or(() -> start.workItemId().map(id -> prepared -> workItems.persistAttempt(prepared, id))));
            if (!replay && !uploads.isEmpty()) {
                startupProgress.record(taskId, AgentTaskStartupProgress.Phase.ATTACHMENTS);
            }
            List<AgentAttachment> attachments = replay ? ImmutableList.of() : uploads.stream()
                    .map(resourceService::storeAttachment)
                    .map(StoredAgentAttachment::input)
                    .collect(toImmutableList());
            startupProgress.record(taskId, AgentTaskStartupProgress.Phase.SESSION);
            turnService.start(StartAgentTurnCommand.trustedInitialInteractiveTurn(
                    taskId,
                    start.message(),
                    attachments,
                    start.idempotencyKey()).withDevelopmentAutomation(start.initialAutomation()));
            AgentTask task = taskService.get(taskId);
            if (!replay) {
                // Recent swallows its own failures, so filing the prompt never decides the response.
                recent.taskPrompted(taskId.toString(), start.idempotencyKey(), start.message(), task.repository().toString(), task.laneId().value());
            }
            return task;
        }
        finally {
            // The screen polling this is about to get the answer or the error; a phase must not outlive either.
            startupProgress.clear(taskId);
        }
    }

    private boolean initialTurnExists(TaskId taskId, String idempotencyKey)
    {
        try {
            return turnService.findTurnByIdempotencyKey(taskId, idempotencyKey).isPresent();
        }
        catch (AgentTaskNotFoundException ignored) {
            return false;
        }
    }

    private static String attachmentDigest(List<StoreAgentAttachmentCommand> uploads)
    {
        Hasher digest = Hashing.sha256().newHasher();
        for (StoreAgentAttachmentCommand upload : uploads) {
            byte[] name = upload.name().getBytes(StandardCharsets.UTF_8);
            byte[] mediaType = upload.mediaType().getBytes(StandardCharsets.UTF_8);
            digest.putInt(name.length).putBytes(name);
            digest.putInt(mediaType.length).putBytes(mediaType);
            digest.putInt(upload.bytes().length).putBytes(upload.bytes());
        }
        return digest.hash().toString();
    }
}
