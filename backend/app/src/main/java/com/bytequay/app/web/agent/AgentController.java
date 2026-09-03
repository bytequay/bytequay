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
package com.bytequay.app.web.agent;

import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.RenameAgentTaskCommand;
import com.bytequay.agent.core.ResolveApprovalCommand;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.StoreAgentAttachmentCommand;
import com.bytequay.agent.core.StoredAgentAttachment;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.UpdateAgentTaskDefaultsCommand;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.spi.AgentAttachment;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.app.agent.InteractiveAgentTaskCreator;
import com.bytequay.app.service.recent.RecentTouchRecorder;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.store.PullRequestStore;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/agent")
public class AgentController
{
    public static final String SESSION_HEADER = "X-ByteQuay-Session";

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentTaskService taskService;
    private final InteractiveAgentTaskCreator taskCreator;
    private final AgentTurnService turnService;
    private final AgentEventService eventService;
    private final AgentApprovalService approvalService;
    private final AgentCompletionService completionService;
    private final AgentDriverRegistry driverRegistry;
    private final ModelSettingsService modelSettings;
    private final AgentResourceService resourceService;
    private final RecentTouchRecorder recent;
    private final PullRequestStore pullRequests;

    public AgentController(
            AgentTaskService taskService,
            InteractiveAgentTaskCreator taskCreator,
            AgentTurnService turnService,
            AgentEventService eventService,
            AgentApprovalService approvalService,
            AgentCompletionService completionService,
            AgentDriverRegistry driverRegistry,
            ModelSettingsService modelSettings,
            AgentResourceService resourceService,
            RecentTouchRecorder recent,
            PullRequestStore pullRequests)
    {
        this.taskService = requireNonNull(taskService, "taskService is null");
        this.taskCreator = requireNonNull(taskCreator, "taskCreator is null");
        this.turnService = requireNonNull(turnService, "turnService is null");
        this.eventService = requireNonNull(eventService, "eventService is null");
        this.approvalService = requireNonNull(approvalService, "approvalService is null");
        this.completionService = requireNonNull(completionService, "completionService is null");
        this.driverRegistry = requireNonNull(driverRegistry, "driverRegistry is null");
        this.modelSettings = requireNonNull(modelSettings, "modelSettings is null");
        this.resourceService = requireNonNull(resourceService, "resourceService is null");
        this.recent = requireNonNull(recent, "recent is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
    }

    @PostMapping("/tasks")
    public ResponseEntity<InteractiveAgentHttpContract.TaskResponse> createTask(
            @RequestBody InteractiveAgentHttpContract.CreateTaskRequest request)
    {
        AgentTaskDefaults defaults = new AgentTaskDefaults(
                request.defaults().model(),
                request.defaults().effort(),
                AgentPermissionPolicy.valueOf(request.defaults().permissionPolicy()));
        AgentTask task = taskCreator.create(
                request.workspaceId(),
                request.message(),
                new AgentLaneId(request.laneId()),
                defaults);
        return ResponseEntity.status(HttpStatus.CREATED).body(InteractiveAgentHttpContract.task(task));
    }

    @GetMapping("/tasks/{taskId}")
    public InteractiveAgentHttpContract.TaskResponse getTask(
            @PathVariable String taskId,
            @RequestParam String workspaceId)
    {
        return InteractiveAgentHttpContract.task(
                taskService.getInteractive(workspaceId, TaskId.valueOf(taskId)));
    }

    @PutMapping("/tasks/{taskId}/defaults")
    public InteractiveAgentHttpContract.TaskResponse updateTaskDefaults(
            @PathVariable String taskId,
            @RequestParam String workspaceId,
            @RequestBody InteractiveAgentHttpContract.UpdateDefaultsRequest request)
    {
        TaskId id = TaskId.valueOf(taskId);
        // Resolve through the workspace first so one workspace cannot retune another's task.
        taskService.getInteractive(workspaceId, id);
        return InteractiveAgentHttpContract.task(taskService.updateDefaults(new UpdateAgentTaskDefaultsCommand(
                id,
                request.expectedVersion(),
                new AgentTaskDefaults(
                        request.defaults().model(),
                        request.defaults().effort(),
                        AgentPermissionPolicy.valueOf(request.defaults().permissionPolicy())))));
    }

    @PatchMapping("/tasks/{taskId}")
    public InteractiveAgentHttpContract.TaskResponse renameTask(
            @PathVariable String taskId,
            @RequestParam String workspaceId,
            @RequestBody InteractiveAgentHttpContract.RenameTaskRequest request)
    {
        TaskId id = TaskId.valueOf(taskId);
        taskService.getInteractive(workspaceId, id);
        return InteractiveAgentHttpContract.task(taskService.rename(new RenameAgentTaskCommand(
                id,
                request.expectedVersion(),
                request.title())));
    }

    /**
     * The pull requests on this task's branch. The branch is derived from the task, so nothing
     * records what a task opened; whatever the user pushed and opened on it appears here.
     */
    @GetMapping("/tasks/{taskId}/pull-requests")
    public List<PullRequest> taskPullRequests(
            @PathVariable String taskId,
            @RequestParam String workspaceId)
    {
        AgentTask task = taskService.getInteractive(workspaceId, TaskId.valueOf(taskId));
        return task.taskWorktree()
                .map(worktree -> pullRequests.findByHeadRef(
                        task.repository().owner() + "/" + task.repository().name(),
                        worktree.generatedBranch()))
                .orElse(ImmutableList.of());
    }

    /**
     * Refuses images for a model that does not advertise image input, so the catalog gates the
     * turn the same way it gates the composer's attach button.
     */
    private void requireImageInput(TaskId taskId)
    {
        AgentTask task = taskService.get(taskId);
        String model = turnService.effectiveSettings(taskId).model().orElse("");
        boolean accepted = modelSettings.get().providers().stream()
                .filter(provider -> provider.id().equals(task.laneId().value()))
                .flatMap(provider -> provider.models().stream())
                .anyMatch(option -> option.id().equals(model) && option.acceptsImages());
        if (!accepted) {
            throw new InvalidAgentRequestException("Model " + model + " does not accept images");
        }
    }

    @PostMapping("/tasks/{taskId}/attachments")
    public ResponseEntity<AgentHttpModels.AttachmentResponse> storeAttachment(
            @PathVariable String taskId,
            @RequestParam String workspaceId,
            @RequestHeader("X-Attachment-Name") String name,
            @RequestHeader("Content-Type") String mediaType,
            @RequestBody byte[] bytes)
    {
        TaskId id = TaskId.valueOf(taskId);
        taskService.getInteractive(workspaceId, id);
        requireNotBlank(name, "X-Attachment-Name must not be blank");
        StoredAgentAttachment stored = resourceService.storeAttachment(
                new StoreAgentAttachmentCommand(id, name, mediaType, bytes));
        return ResponseEntity.status(HttpStatus.CREATED).body(AgentHttpModels.attachment(stored));
    }

    @GetMapping("/tasks/{taskId}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> readAttachment(
            @PathVariable String taskId,
            @PathVariable String attachmentId,
            @RequestParam String workspaceId)
            throws IOException
    {
        TaskId id = TaskId.valueOf(taskId);
        taskService.getInteractive(workspaceId, id);
        StoredAgentAttachment stored = resourceService.readAttachment(id, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stored.mediaType()))
                .header("X-Attachment-Name", stored.name())
                .body(Files.readAllBytes(stored.path()));
    }

    @DeleteMapping("/tasks/{taskId}/attachments/{attachmentId}")
    public ResponseEntity<Void> deleteAttachment(
            @PathVariable String taskId,
            @PathVariable String attachmentId,
            @RequestParam String workspaceId)
    {
        TaskId id = TaskId.valueOf(taskId);
        taskService.getInteractive(workspaceId, id);
        resourceService.deleteAttachment(id, attachmentId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tasks/{taskId}/effective-settings")
    public InteractiveAgentHttpContract.TurnSettings effectiveSettings(
            @PathVariable String taskId,
            @RequestParam String workspaceId)
    {
        TaskId id = TaskId.valueOf(taskId);
        taskService.getInteractive(workspaceId, id);
        return InteractiveAgentHttpContract.settings(turnService.effectiveSettings(id));
    }

    @GetMapping("/tasks")
    public List<InteractiveAgentHttpContract.TaskResponse> listTasks(
            @RequestParam String workspaceId,
            @RequestParam(defaultValue = "100") int limit)
    {
        return taskService.list(workspaceId, limit).stream()
                .map(InteractiveAgentHttpContract::task)
                .collect(toImmutableList());
    }

    @PostMapping("/tasks/{taskId}/turns")
    public ResponseEntity<InteractiveAgentHttpContract.TurnResponse> startTurn(
            @PathVariable String taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody StartTurnRequest request)
    {
        requireNotBlank(request.message(), "message must not be blank");
        requireNotBlank(idempotencyKey, "Idempotency-Key must not be blank");
        TaskId id = TaskId.valueOf(taskId);
        List<String> attachmentIds = request.attachments() == null ? ImmutableList.of() : request.attachments();
        List<AgentAttachment> attachments = attachmentIds.stream()
                .map(attachmentId -> resourceService.readAttachment(id, attachmentId).input())
                .collect(toImmutableList());
        if (!attachments.isEmpty()) {
            requireImageInput(id);
        }
        AgentTurn turn = turnService.start(new StartAgentTurnCommand(
                id,
                request.message(),
                attachments,
                idempotencyKey));
        recordPrompt(taskId, request.message());
        return ResponseEntity.accepted().body(InteractiveAgentHttpContract.turn(turn));
    }

    /** Files the user's prompt in Recent. Never on the path that decides the response. */
    private void recordPrompt(String taskId, String message)
    {
        try {
            AgentTask task = taskService.get(TaskId.valueOf(taskId));
            recent.taskPrompted(taskId, message, task.repository().toString(), task.laneId().value());
        }
        catch (RuntimeException failure) {
            log.debug("Prompt not recorded for task {}: {}", taskId, failure.toString());
        }
    }

    @GetMapping("/turns/{turnId}")
    public InteractiveAgentHttpContract.TurnResponse getTurn(@PathVariable String turnId)
    {
        return InteractiveAgentHttpContract.turn(turnService.get(TurnId.valueOf(turnId)));
    }

    @PostMapping("/turns/{turnId}/cancel")
    public InteractiveAgentHttpContract.TurnResponse cancelTurn(@PathVariable String turnId)
    {
        return InteractiveAgentHttpContract.turn(turnService.cancel(TurnId.valueOf(turnId)));
    }

    @GetMapping("/tasks/{taskId}/events")
    public List<AgentHttpModels.EventResponse> events(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "100") int limit)
    {
        return eventService.readAfter(TaskId.valueOf(taskId), after, limit).stream()
                .map(AgentHttpModels::event)
                .collect(toImmutableList());
    }

    @GetMapping(path = "/tasks/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "0") long after)
            throws IOException
    {
        SseEmitter emitter = new SseEmitter(0L);
        // An idle task may have no event to send for minutes, and until something is written the
        // response is never committed, so a client cannot tell an open stream from a hanging request.
        emitter.send(SseEmitter.event().comment("connected"));
        eventService.subscribe(TaskId.valueOf(taskId), after)
                .subscribe(new AgentSseSubscriber(emitter));
        return emitter;
    }

    @PostMapping("/approvals/{approvalId}")
    public AgentHttpModels.ApprovalResponse resolveApproval(
            @PathVariable String approvalId,
            @RequestBody ResolveApprovalRequest request)
    {
        checkArgument(request.decision() != null, "decision is null");
        return AgentHttpModels.approval(approvalService.resolve(new ResolveApprovalCommand(
                ApprovalId.valueOf(approvalId),
                request.decision(),
                Optional.ofNullable(request.scopedPermission()))));
    }

    @PostMapping("/tasks/{taskId}/complete")
    public AgentHttpModels.CompletionResponse completeTask(
            @PathVariable String taskId,
            @RequestParam String workspaceId,
            @RequestBody(required = false) CompleteTaskRequest request)
    {
        TaskId id = TaskId.valueOf(taskId);
        taskService.getInteractive(workspaceId, id);
        var completion = completionService.complete(new CompleteAgentTaskCommand(
                id,
                request == null ? Optional.empty() : Optional.ofNullable(request.notes())));
        completionService.cleanupWorktree(id);
        return AgentHttpModels.completion(completion);
    }

    /**
     * Fails a task whose provider could not be shown to have stopped touching its worktree.
     *
     * <p>The retained claim deliberately blocks every later turn, and this is the way out. The
     * task's own provider process is stopped and verified first, so a live turn has to be stopped
     * before this is accepted. A clean worktree is then removed and its branch kept; dirty work
     * stays in place for inspection, and a worktree whose old process could never be verified
     * stopped is not touched at all.
     */
    @PostMapping("/tasks/{taskId}/mark-failed")
    public InteractiveAgentHttpContract.TaskResponse markTaskFailed(
            @PathVariable String taskId,
            @RequestParam String workspaceId,
            @RequestBody MarkTaskFailedRequest request)
    {
        requireNotBlank(request.reason(), "reason must not be blank");
        TaskId id = TaskId.valueOf(taskId);
        AgentTask task = taskService.getInteractive(workspaceId, id);
        boolean processUnverified = task.worktreeMutationClaim()
                .map(claim -> claim.kind() == WorktreeMutationClaim.Kind.RECOVERY)
                .orElse(false);
        completionService.abandon(id, request.reason());
        if (!processUnverified) {
            try {
                completionService.cleanupWorktree(id);
            }
            catch (RuntimeException failure) {
                // The response shows the worktree as retained; nothing here discards work.
                log.warn("Task {} was marked failed but its worktree was retained: {}", taskId, failure.toString());
            }
        }
        return InteractiveAgentHttpContract.task(taskService.get(id));
    }

    /**
     * What stopping this task's Codex server would interrupt, and the generation the stop must echo.
     */
    @GetMapping("/tasks/{taskId}/runtime/stop-impact")
    public AgentHttpModels.RuntimeStopImpactResponse runtimeStopImpact(
            @PathVariable String taskId,
            @RequestParam String workspaceId)
    {
        TaskId id = TaskId.valueOf(taskId);
        taskService.getInteractive(workspaceId, id);
        return AgentHttpModels.stopImpact(turnService.stopImpact(id));
    }

    /**
     * Stops this task's Codex server, the last resort behind ordinary cancellation.
     *
     * <p>Reaches no other task. The generation is the one the user confirmed, so a turn that
     * started since cannot be stopped by a stale confirmation.
     */
    @PostMapping("/tasks/{taskId}/runtime/stop")
    public InteractiveAgentHttpContract.TaskResponse stopRuntime(
            @PathVariable String taskId,
            @RequestParam String workspaceId,
            @RequestBody StopRuntimeRequest request)
    {
        requireNotBlank(request.generation(), "generation must not be blank");
        TaskId id = TaskId.valueOf(taskId);
        taskService.getInteractive(workspaceId, id);
        return InteractiveAgentHttpContract.task(turnService.stopRuntime(id, request.generation()));
    }

    @PostMapping("/tasks/{taskId}/cleanup")
    public AgentHttpModels.CleanupResponse cleanupTask(
            @PathVariable String taskId,
            @RequestParam String workspaceId)
    {
        TaskId id = TaskId.valueOf(taskId);
        taskService.getInteractive(workspaceId, id);
        return AgentHttpModels.cleanup(completionService.cleanup(id));
    }

    /**
     * Probes every provider and reports its readiness.
     *
     * <p>A provider that has just become usable has no model catalog, because the catalog is only
     * probed at startup. Filling it here is what makes fixing a provider take effect without a
     * restart; a provider that already has one costs nothing.
     */
    @GetMapping("/providers/status")
    public List<AgentHttpModels.ProviderStatusResponse> providerStatuses()
    {
        List<AgentProviderStatus> statuses = driverRegistry.statuses();
        modelSettings.refreshMissingCatalogs(statuses);
        return statuses.stream()
                .map(AgentHttpModels::providerStatus)
                .collect(toImmutableList());
    }

    public record StartTurnRequest(String message, List<String> attachments) {}

    public record ResolveApprovalRequest(
            ApprovalDecision.Decision decision,
            String scopedPermission) {}

    public record CompleteTaskRequest(String notes) {}

    public record MarkTaskFailedRequest(String reason) {}

    public record StopRuntimeRequest(String generation) {}
}
