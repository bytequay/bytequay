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
import com.bytequay.agent.core.AgentAttachmentReference;
import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentInputDelivery;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ArchiveAgentTaskCommand;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.QueueAgentInputCommand;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.RenameAgentTaskCommand;
import com.bytequay.agent.core.ResolveApprovalCommand;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.SteerAgentTurnCommand;
import com.bytequay.agent.core.StoreAgentAttachmentCommand;
import com.bytequay.agent.core.StoredAgentAttachment;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.UpdateAgentTaskDefaultsCommand;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.spi.AgentAttachment;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.app.agent.AgentStartupRecovery;
import com.bytequay.app.agent.AgentTaskStartupProgress;
import com.bytequay.app.agent.InteractiveAgentTaskCreator;
import com.bytequay.app.agent.IssueDevelopmentService;
import com.bytequay.app.agent.TaskPullRequestOwnership;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.context.ContextBuildService;
import com.bytequay.app.service.goal.WorkItemService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.recent.RecentTouchRecorder;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.web.DevelopmentAutomationDto;
import com.bytequay.app.web.ci.CiAutofixHttpModels;
import com.bytequay.app.workspace.ManagedWorktreeService;
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.app.workspace.WorktreeChanges;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.JiraIssueSource;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.store.PullRequestTaskStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/agent")
public class AgentController
{
    public static final String SESSION_HEADER = "X-ByteQuay-Session";

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentTaskService taskService;
    private final ContextBuildService contextBuilds;
    private final InteractiveAgentTaskCreator taskCreator;
    private final AgentStartupRecovery startupRecovery;
    private final AgentTurnService turnService;
    private final AgentEventService eventService;
    private final AgentApprovalService approvalService;
    private final AgentCompletionService completionService;
    private final AgentMetricsService metricsService;
    private final AgentDriverRegistry driverRegistry;
    private final ModelSettingsService modelSettings;
    private final AppSettingsService appSettings;
    private final AgentResourceService resourceService;
    private final RecentTouchRecorder recent;
    private final PullRequestTaskStore pullRequestTasks;
    private final ManagedWorktreeService worktrees;
    private final PullRequestService pullRequestCatalog;
    private final CiAutofixStore ciRuns;
    private final CiAutofixService ci;
    private final AgentTaskStartupProgress startupProgress;
    private final AgentStreamRegistry streams;
    private final IssueDevelopmentService issueDevelopment;
    private final TaskPullRequestOwnership ownership;
    private final WorkItemService workItems;

    public AgentController(
            AgentTaskService taskService,
            ContextBuildService contextBuilds,
            InteractiveAgentTaskCreator taskCreator,
            AgentStartupRecovery startupRecovery,
            AgentTurnService turnService,
            AgentEventService eventService,
            AgentApprovalService approvalService,
            AgentCompletionService completionService,
            AgentMetricsService metricsService,
            AgentDriverRegistry driverRegistry,
            ModelSettingsService modelSettings,
            AgentResourceService resourceService,
            RecentTouchRecorder recent,
            PullRequestTaskStore pullRequestTasks,
            ManagedWorktreeService worktrees,
            PullRequestService pullRequestCatalog,
            AppSettingsService appSettings,
            CiAutofixStore ciRuns,
            CiAutofixService ci,
            AgentTaskStartupProgress startupProgress,
            AgentStreamRegistry streams,
            IssueDevelopmentService issueDevelopment,
            TaskPullRequestOwnership ownership,
            WorkItemService workItems)
    {
        this.taskService = requireNonNull(taskService, "taskService is null");
        this.contextBuilds = requireNonNull(contextBuilds, "contextBuilds is null");
        this.taskCreator = requireNonNull(taskCreator, "taskCreator is null");
        this.startupRecovery = requireNonNull(startupRecovery, "startupRecovery is null");
        this.turnService = requireNonNull(turnService, "turnService is null");
        this.eventService = requireNonNull(eventService, "eventService is null");
        this.approvalService = requireNonNull(approvalService, "approvalService is null");
        this.completionService = requireNonNull(completionService, "completionService is null");
        this.metricsService = requireNonNull(metricsService, "metricsService is null");
        this.driverRegistry = requireNonNull(driverRegistry, "driverRegistry is null");
        this.modelSettings = requireNonNull(modelSettings, "modelSettings is null");
        this.resourceService = requireNonNull(resourceService, "resourceService is null");
        this.recent = requireNonNull(recent, "recent is null");
        this.pullRequestTasks = requireNonNull(pullRequestTasks, "pullRequestTasks is null");
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.pullRequestCatalog = requireNonNull(pullRequestCatalog, "pullRequestCatalog is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
        this.ciRuns = requireNonNull(ciRuns, "ciRuns is null");
        this.ci = requireNonNull(ci, "ci is null");
        this.startupProgress = requireNonNull(startupProgress, "startupProgress is null");
        this.streams = requireNonNull(streams, "streams is null");
        this.issueDevelopment = requireNonNull(issueDevelopment, "issueDevelopment is null");
        this.ownership = requireNonNull(ownership, "ownership is null");
        this.workItems = requireNonNull(workItems, "workItems is null");
    }

    /**
     * Task totals fold at turn end, so only a task whose worktree is claimed can have a turn
     * mid-flight and a row that lags it; the response sums the turns for that one.
     */
    private InteractiveAgentHttpContract.TaskResponse taskResponse(AgentTask task)
    {
        AgentMetrics metrics = task.worktreeMutationClaim().isPresent() ? metricsService.taskTotals(task.id()) : task.metrics();
        Optional<CiAutofixHttpModels.DevelopmentResponse> developmentCi = ciRuns.listByParentTaskId(task.id().toString()).stream()
                .max(comparing(CiAutofixRun::createdAt))
                .map(run -> CiAutofixHttpModels.development(run, ci.canMessage(run)));
        Optional<InteractiveAgentHttpContract.TaskPullRequestResponse> pullRequest = ownership.linkedPullRequest(task)
                .map(pr -> new InteractiveAgentHttpContract.TaskPullRequestResponse(pr.number(), pr.title(), pr.state(), pr.draft()));
        return InteractiveAgentHttpContract.task(task, metrics, developmentCi, pullRequest, issueDevelopment.source(task.id().toString()).map(source ->
                new InteractiveAgentHttpContract.IssueSourceResponse(source.issue(), source.initialTurnId(), source.instructions())),
                issueDevelopment.jiraSource(task.id().toString()).map(source ->
                        new InteractiveAgentHttpContract.JiraSourceResponse(source.issue(), source.initialTurnId(), source.instructions())));
    }

    @PostMapping(value = "/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InteractiveAgentHttpContract.TaskResponse> createTask(@RequestBody InteractiveAgentHttpContract.CreateTaskRequest request)
    {
        return createTask(request, ImmutableList.of());
    }

    @PostMapping(value = "/tasks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<InteractiveAgentHttpContract.TaskResponse> createTaskWithImages(
            @RequestPart("request") InteractiveAgentHttpContract.CreateTaskRequest request,
            @RequestPart("images") List<MultipartFile> images)
            throws IOException
    {
        checkArgument(!images.isEmpty() && images.size() <= 8, "Attach between 1 and 8 images");
        ImmutableList.Builder<StoreAgentAttachmentCommand> attachments = ImmutableList.builder();
        for (MultipartFile image : images) {
            checkArgument(image.getSize() <= 10L * 1024 * 1024, "Image must be under 10 MiB");
            attachments.add(new StoreAgentAttachmentCommand(
                    TaskId.valueOf(request.taskId()),
                    Optional.ofNullable(image.getOriginalFilename()).filter(name -> !name.isBlank()).orElse("image"),
                    Optional.ofNullable(image.getContentType()).orElse("application/octet-stream"),
                    image.getBytes()));
        }
        return createTask(request, attachments.build());
    }

    private ResponseEntity<InteractiveAgentHttpContract.TaskResponse> createTask(InteractiveAgentHttpContract.CreateTaskRequest request, List<StoreAgentAttachmentCommand> uploads)
    {
        issueDevelopment.requireAttachedSources(TaskId.valueOf(request.taskId()), request.issueSource(), request.jiraSource());
        request.workItemId().ifPresent(id -> workItems.requireAttemptable(request.workspaceId(), id));
        AgentTask task = request.issueSource()
                .map(source -> issueDevelopment.start(request.workspaceId(), TaskId.valueOf(request.taskId()), source, request.message(),
                        accepted -> createTask(request, uploads, Optional.of(accepted), Optional.empty())))
                .or(() -> request.jiraSource().map(source -> issueDevelopment.start(request.workspaceId(), TaskId.valueOf(request.taskId()), source, request.message(),
                        accepted -> createTask(request, uploads, Optional.empty(), Optional.of(accepted)))))
                .orElseGet(() -> createTask(request, uploads, Optional.empty(), Optional.empty()));
        return ResponseEntity.status(HttpStatus.CREATED).body(taskResponse(task));
    }

    private AgentTask createTask(
            InteractiveAgentHttpContract.CreateTaskRequest request,
            List<StoreAgentAttachmentCommand> uploads,
            Optional<GitHubIssueSource> source,
            Optional<JiraIssueSource> jiraSource)
    {
        String message = request.message();
        AgentLaneId laneId = new AgentLaneId(request.laneId());
        Optional<DevelopmentAutomation> requestedAutomation = request.developmentAutomation().map(DevelopmentAutomationDto::toDomain);
        AgentTaskDefaults defaults = new AgentTaskDefaults(
                request.defaults().model(),
                request.defaults().effort(),
                AgentPermissionPolicy.valueOf(request.defaults().permissionPolicy()),
                request.defaults().serviceTier(),
                request.defaults().developmentAutomation().map(DevelopmentAutomationDto::toDomain));
        TaskId taskId = TaskId.valueOf(request.taskId());
        try {
            boolean replay = initialTurnExists(taskId, request.idempotencyKey());
            if (!replay && !uploads.isEmpty()) {
                uploads.forEach(resourceService::validateAttachment);
                requireImageInput(laneId, defaults.model(), uploads.stream().map(StoreAgentAttachmentCommand::mediaType).collect(toImmutableList()));
            }
            taskCreator.create(
                    taskId,
                    request.workspaceId(),
                    message,
                    laneId,
                    defaults,
                    request.idempotencyKey(),
                    uploads.isEmpty() ? Optional.empty() : Optional.of(attachmentDigest(uploads)),
                    requestedAutomation,
                    source,
                    jiraSource,
                    source.<Consumer<AgentTask>>map(accepted -> prepared -> issueDevelopment.persistTask(prepared, accepted, request.message()))
                            .or(() -> jiraSource.map(accepted -> prepared -> issueDevelopment.persistTask(prepared, accepted, request.message())))
                            .or(() -> request.workItemId().map(id -> prepared -> workItems.persistAttempt(prepared, id))));
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
                    message,
                    attachments,
                    request.idempotencyKey()).withDevelopmentAutomation(requestedAutomation));
            AgentTask responseTask = taskService.get(taskId);
            if (!replay) {
                recordPrompt(taskId.toString(), request.idempotencyKey(), request.message());
            }
            return responseTask;
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

    /** Which creation step an in-flight create request is in; the task may not exist yet, so no workspace scope applies. */
    @GetMapping("/tasks/{taskId}/startup")
    public AgentHttpModels.StartupProgressResponse taskStartup(@PathVariable String taskId)
    {
        return new AgentHttpModels.StartupProgressResponse(startupProgress.current(TaskId.valueOf(taskId)).map(Enum::name));
    }

    @GetMapping("/tasks/{taskId}")
    public InteractiveAgentHttpContract.TaskResponse getTask(@PathVariable String taskId, @RequestParam String workspaceId)
    {
        return taskResponse(taskService.getInteractive(workspaceId, TaskId.valueOf(taskId)));
    }

    @PutMapping("/tasks/{taskId}/defaults")
    public InteractiveAgentHttpContract.TaskResponse updateTaskDefaults(
            @PathVariable String taskId,
            @RequestParam String workspaceId,
            @RequestBody InteractiveAgentHttpContract.UpdateDefaultsRequest request)
    {
        TaskId id = TaskId.valueOf(taskId);
        // Resolve through the workspace first so one workspace cannot retune another's task.
        AgentTask task = taskService.getInteractive(workspaceId, id);
        modelSettings.validateSelection(
                task.laneId().value(),
                request.defaults().model().orElseThrow(() -> new IllegalArgumentException("model must not be blank")),
                request.defaults().effort(),
                request.defaults().serviceTier());
        // A started task keeps the automation it was created with; this update only retunes its model.
        Optional<DevelopmentAutomation> automation = task.defaults().developmentAutomation();
        checkArgument(request.defaults().developmentAutomation().map(DevelopmentAutomationDto::toDomain).or(() -> automation).equals(automation),
                "Automation settings cannot change after a task starts");
        return taskResponse(taskService.updateDefaults(new UpdateAgentTaskDefaultsCommand(
                id,
                request.expectedVersion(),
                new AgentTaskDefaults(
                        request.defaults().model(),
                        request.defaults().effort(),
                        AgentPermissionPolicy.valueOf(request.defaults().permissionPolicy()),
                        request.defaults().serviceTier(),
                        automation))));
    }

    @PatchMapping("/tasks/{taskId}")
    public InteractiveAgentHttpContract.TaskResponse patchTask(
            @PathVariable String taskId,
            @RequestParam String workspaceId,
            @RequestBody InteractiveAgentHttpContract.PatchTaskRequest request)
    {
        TaskId id = TaskId.valueOf(taskId);
        taskService.getInteractive(workspaceId, id);
        AgentTask updated = request.title().isPresent()
                ? taskService.rename(new RenameAgentTaskCommand(id, request.expectedVersion(), request.title().orElseThrow()))
                : taskService.archive(new ArchiveAgentTaskCommand(id, request.expectedVersion(), request.archived().orElseThrow()));
        return taskResponse(updated);
    }

    /**
     * Removes a task and everything ByteQuay keeps for it, generated branch included: the one place
     * that branch may be destroyed. Delivery children stop first; repeating the request is a no-op.
     */
    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<Void> deleteTask(@PathVariable String taskId, @RequestParam String workspaceId)
    {
        TaskId id = TaskId.valueOf(taskId);
        AgentTask task;
        try {
            task = taskService.getInteractive(workspaceId, id);
        }
        catch (AgentTaskNotFoundException alreadyGone) {
            return ResponseEntity.noContent().build();
        }
        String correlationId = UUID.randomUUID().toString();
        log.info("Deleting task {} with branch {} (correlation {})", id, task.taskWorktree().map(TaskWorktree::generatedBranch).orElse("none"), correlationId);
        if (task.developmentContinuationUnfinished()) {
            stopTaskRuntime(task, turnService.stopImpact(id).generation());
        }
        ci.deleteDevelopment(id);
        completionService.delete(id);
        log.info("Deleted task {} (correlation {})", id, correlationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * The pull requests on the generated branch and the verified checkout's current branch,
     * which may have been renamed by the agent while opening its pull request.
     */
    @GetMapping("/tasks/{taskId}/pull-requests")
    public List<PullRequest> taskPullRequests(@PathVariable String taskId, @RequestParam String workspaceId)
    {
        AgentTask task = taskService.getInteractive(workspaceId, TaskId.valueOf(taskId));
        return ownership.pullRequests(task);
    }

    /** What each listed task's live checkout holds, by task id; a checkout that cannot be read is absent. */
    @GetMapping("/tasks/changes")
    public Map<String, WorktreeChanges> taskChanges(@RequestParam String workspaceId)
    {
        List<TaskWorktree> checkouts = taskService.list(workspaceId, 100).stream()
                .filter(task -> task.worktreeRemovedAt().isEmpty())
                .flatMap(task -> task.taskWorktree().stream())
                .collect(toImmutableList());
        return worktrees.changes(workspaceId, checkouts).entrySet().stream()
                .collect(toImmutableMap(entry -> entry.getKey().toString(), Map.Entry::getValue));
    }

    @PostMapping("/tasks/{taskId}/pull-requests/sync")
    public List<PullRequest> refreshTaskPullRequests(@PathVariable String taskId, @RequestParam String workspaceId)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            AgentTask task = taskService.getInteractive(workspaceId, TaskId.valueOf(taskId));
            var assignment = pullRequestTasks.findByTaskId(taskId);
            if (assignment.isPresent()) {
                var pr = assignment.orElseThrow();
                pullRequestCatalog.observeWorkspacePullRequest(ownership.repository(workspaceId), pr.number());
                return ownership.assignedPullRequests(task).orElseThrow();
            }
            return task.taskWorktree().map(worktree -> {
                List<String> branches = ownership.branches(worktree);
                RepoRef repository = ownership.repository(workspaceId);
                branches.forEach(branch -> pullRequestCatalog.syncRepositoryBranch(repository, worktree.physicalRepository().owner(), branch));
                return ownership.branchPullRequests(repository, worktree, branches);
            }).orElse(ImmutableList.of());
        });
    }

    /**
     * Refuses images for a model that does not advertise image input, so the catalog gates the
     * turn the same way it gates the composer's attach button.
     */
    private void requireImageInput(AgentTask task, Optional<String> selectedModel, List<AgentAttachment> attachments)
    {
        requireImageInput(task.laneId(), selectedModel, attachments.stream().map(AgentAttachment::mediaType).collect(toImmutableList()));
    }

    private void requireImageInput(AgentLaneId laneId, Optional<String> selectedModel, List<String> mediaTypes)
    {
        String model = selectedModel.orElse("");
        boolean modelAccepted = modelSettings.get().providers().stream()
                .filter(provider -> provider.id().equals(laneId.value()))
                .flatMap(provider -> provider.models().stream())
                .anyMatch(option -> option.id().equals(model) && option.acceptsImages());
        if (!modelAccepted) {
            throw new InvalidAgentRequestException("Model " + model + " does not accept images");
        }
        var supported = driverRegistry.require(laneId).capabilities().attachmentMediaTypes();
        mediaTypes.stream()
                .filter(mediaType -> !supported.contains(mediaType))
                .findFirst()
                .ifPresent(mediaType -> {
                    throw new InvalidAgentRequestException("Provider " + laneId + " does not accept " + mediaType + " attachments");
                });
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
        checkText(name, "X-Attachment-Name");
        StoredAgentAttachment stored = resourceService.storeAttachment(new StoreAgentAttachmentCommand(id, name, mediaType, bytes));
        return ResponseEntity.status(HttpStatus.CREATED).body(AgentHttpModels.attachment(stored));
    }

    @GetMapping("/tasks/{taskId}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> readAttachment(@PathVariable String taskId, @PathVariable String attachmentId, @RequestParam String workspaceId)
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
    public ResponseEntity<Void> deleteAttachment(@PathVariable String taskId, @PathVariable String attachmentId, @RequestParam String workspaceId)
    {
        TaskId id = TaskId.valueOf(taskId);
        taskService.getInteractive(workspaceId, id);
        resourceService.deleteAttachment(id, attachmentId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tasks/{taskId}/effective-settings")
    public InteractiveAgentHttpContract.TurnSettings effectiveSettings(@PathVariable String taskId, @RequestParam String workspaceId)
    {
        TaskId id = TaskId.valueOf(taskId);
        taskService.getInteractive(workspaceId, id);
        return InteractiveAgentHttpContract.settings(turnService.effectiveSettings(id));
    }

    @GetMapping("/tasks")
    public List<InteractiveAgentHttpContract.TaskResponse> listTasks(@RequestParam String workspaceId, @RequestParam(defaultValue = "100") int limit)
    {
        return taskService.list(workspaceId, limit).stream()
                .map(this::taskResponse)
                .collect(toImmutableList());
    }

    @PostMapping("/tasks/{taskId}/turns")
    public ResponseEntity<InteractiveAgentHttpContract.TurnResponse> startTurn(
            @PathVariable String taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InteractiveAgentHttpContract.InputRequest request)
    {
        checkText(idempotencyKey, "Idempotency-Key");
        checkArgument(request.expectedVersion() != null, "expectedVersion is null");
        checkArgument(request.expectedVersion() >= 0, "expectedVersion is negative");
        TaskId id = TaskId.valueOf(taskId);
        checkArgument(taskService.get(id).interactive(), "Workflow-owned tasks cannot accept interactive input");
        boolean replay = turnService.findTurnByIdempotencyKey(id, idempotencyKey).isPresent();
        // A replay owns its persisted attachments. Re-enter core so its completion/outbox recovery
        // still runs, while mutable files and defaults cannot block the accepted request.
        List<AgentAttachment> attachments = replay || request.attachments().isEmpty() ? ImmutableList.of() : attachments(taskService.get(id), request.attachments());
        AgentTurn turn = turnService.start(new StartAgentTurnCommand(
                id,
                request.expectedVersion(),
                request.message(),
                attachments,
                idempotencyKey).withDevelopmentAutomation(replay ? Optional.empty() : submissionAutomation(taskService.get(id), request.developmentAutomation())));
        if (!replay) {
            recordPrompt(taskId, idempotencyKey, request.message());
        }
        return ResponseEntity.accepted().body(InteractiveAgentHttpContract.turn(turn));
    }

    private Optional<DevelopmentAutomation> submissionAutomation(AgentTask task, Optional<DevelopmentAutomationDto> requested)
    {
        if (!task.interactive()) {
            checkArgument(requested.isEmpty(), "Development automation requires an interactive task");
            return Optional.empty();
        }
        DevelopmentAutomation automation = requested.map(DevelopmentAutomationDto::toDomain)
                .or(task.defaults()::developmentAutomation)
                .orElseGet(appSettings::developmentAutomation);
        appSettings.validateDevelopmentAutomation(automation);
        return Optional.of(automation);
    }

    /** The stored images a message names, resolved to the provider inputs only the backend may build. */
    private List<AgentAttachment> attachments(AgentTask task, List<String> attachmentIds)
    {
        List<AgentAttachment> attachments = resolveAttachments(task.id(), attachmentIds);
        if (!attachments.isEmpty()) {
            requireImageInput(task, task.defaults().model(), attachments);
        }
        return attachments;
    }

    /** Resolves steer images against the model snapshotted by that turn, not mutable task defaults. */
    private List<AgentAttachment> attachments(AgentTurn turn, List<String> attachmentIds)
    {
        List<AgentAttachment> attachments = resolveAttachments(turn.taskId(), attachmentIds);
        if (!attachments.isEmpty()) {
            requireImageInput(taskService.get(turn.taskId()), turn.settings().flatMap(settings -> settings.model()), attachments);
        }
        return attachments;
    }

    private List<AgentAttachment> resolveAttachments(TaskId taskId, List<String> attachmentIds)
    {
        return attachmentIds.stream()
                .map(attachmentId -> resourceService.readAttachment(taskId, attachmentId).input())
                .collect(toImmutableList());
    }

    /** Adds to the turn running now. Refused for any turn that is not the task's running one. */
    @PostMapping("/turns/{turnId}/steer")
    public ResponseEntity<InteractiveAgentHttpContract.InputDeliveryResponse> steerTurn(
            @PathVariable String turnId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InteractiveAgentHttpContract.InputRequest request)
    {
        checkText(idempotencyKey, "Idempotency-Key");
        checkArgument(request.developmentAutomation().isEmpty(), "Steering cannot change accepted automation settings");
        TurnId id = TurnId.valueOf(turnId);
        checkArgument(taskService.get(turnService.get(id).taskId()).interactive(), "Workflow-owned tasks cannot accept interactive input");
        var repeated = turnService.findSteerByIdempotencyKey(id, idempotencyKey);
        List<AgentAttachment> attachments = repeated.isPresent() ? ImmutableList.of() : attachments(turnService.get(id), request.attachments());
        AgentInputDelivery delivery = turnService.steer(new SteerAgentTurnCommand(id, request.message(), attachments, idempotencyKey));
        TaskId taskId = delivery.taskId();
        if (repeated.isEmpty()) {
            recordPrompt(taskId.toString(), idempotencyKey, request.message());
        }
        return ResponseEntity.accepted().body(InteractiveAgentHttpContract.delivery(delivery));
    }

    @PostMapping("/tasks/{taskId}/queue")
    public ResponseEntity<InteractiveAgentHttpContract.QueuedInputResponse> enqueue(
            @PathVariable String taskId,
            @RequestParam String workspaceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InteractiveAgentHttpContract.InputRequest request)
    {
        checkText(idempotencyKey, "Idempotency-Key");
        checkArgument(request.expectedVersion() != null, "expectedVersion is null");
        checkArgument(request.expectedVersion() >= 0, "expectedVersion is negative");
        TaskId id = TaskId.valueOf(taskId);
        AgentTask task = taskService.getInteractive(workspaceId, id);
        boolean replay = turnService.findQueuedByIdempotencyKey(id, idempotencyKey).isPresent();
        List<AgentAttachment> attachments = replay ? ImmutableList.of() : attachments(task, request.attachments());
        QueuedAgentInput queued = turnService.enqueue(new QueueAgentInputCommand(
                id,
                request.expectedVersion(),
                request.message(),
                attachments,
                idempotencyKey,
                replay ? Optional.empty() : submissionAutomation(task, request.developmentAutomation()),
                Optional.empty()));
        return ResponseEntity.status(HttpStatus.CREATED).body(InteractiveAgentHttpContract.queuedInput(queued));
    }

    @GetMapping("/tasks/{taskId}/queue")
    public List<InteractiveAgentHttpContract.QueuedInputResponse> queue(@PathVariable String taskId, @RequestParam String workspaceId)
    {
        TaskId id = TaskId.valueOf(taskId);
        taskService.getInteractive(workspaceId, id);
        return turnService.queued(id).stream()
                .map(InteractiveAgentHttpContract::queuedInput)
                .collect(toImmutableList());
    }

    /** Removes a waiting input, or records an uncertain one as abandoned; a consumed one cannot be removed. */
    @DeleteMapping("/tasks/{taskId}/queue/{queuedInputId}")
    public InteractiveAgentHttpContract.QueuedInputResponse removeQueued(@PathVariable String taskId, @PathVariable String queuedInputId, @RequestParam String workspaceId)
    {
        TaskId id = TaskId.valueOf(taskId);
        taskService.getInteractive(workspaceId, id);
        QueuedInputId inputId = QueuedInputId.valueOf(queuedInputId);
        var removed = turnService.removeQueued(id, inputId);
        return InteractiveAgentHttpContract.queuedInput(removed);
    }

    @PostMapping("/tasks/{taskId}/queue/{queuedInputId}/edit")
    public InteractiveAgentHttpContract.QueuedInputDraftResponse editQueued(@PathVariable String taskId, @PathVariable String queuedInputId, @RequestParam String workspaceId)
    {
        TaskId id = TaskId.valueOf(taskId);
        taskService.getInteractive(workspaceId, id);
        var draft = turnService.editQueued(id, QueuedInputId.valueOf(queuedInputId));
        return new InteractiveAgentHttpContract.QueuedInputDraftResponse(draft.message(), InteractiveAgentHttpContract.settings(draft.settings()),
                draft.attachments().stream().map(AgentHttpModels::attachment).collect(toImmutableList()));
    }

    @PostMapping("/tasks/{taskId}/queue/{queuedInputId}/steer")
    public ResponseEntity<InteractiveAgentHttpContract.InputDeliveryResponse> steerQueued(
            @PathVariable String taskId,
            @PathVariable String queuedInputId,
            @RequestParam String workspaceId,
            @RequestParam String turnId)
    {
        TaskId id = TaskId.valueOf(taskId);
        AgentTask task = taskService.getInteractive(workspaceId, id);
        AgentTurn turn = turnService.get(TurnId.valueOf(turnId));
        checkArgument(turn.taskId().equals(id), "Turn does not belong to task");
        QueuedInputId inputId = QueuedInputId.valueOf(queuedInputId);
        turnService.queued(id).stream().filter(input -> input.id().equals(inputId)).findFirst()
                .filter(input -> !input.settings().attachments().isEmpty())
                .ifPresent(input -> requireImageInput(task.laneId(), turn.settings().flatMap(settings -> settings.model()),
                        input.settings().attachments().stream().map(AgentAttachmentReference::mediaType).collect(toImmutableList())));
        return ResponseEntity.accepted().body(InteractiveAgentHttpContract.delivery(turnService.steerQueued(id, turn.id(), inputId)));
    }

    /** Starts the paused queue head on an idle task; a successful turn starts the next one by itself. */
    @PostMapping("/tasks/{taskId}/queue/resume")
    public ResponseEntity<InteractiveAgentHttpContract.TurnResponse> resumeQueue(@PathVariable String taskId, @RequestParam String workspaceId)
    {
        TaskId id = TaskId.valueOf(taskId);
        taskService.getInteractive(workspaceId, id);
        return ResponseEntity.accepted().body(InteractiveAgentHttpContract.turn(turnService.resumeQueued(id)));
    }

    /** Files the user's prompt in Recent. Never on the path that decides the response. */
    private void recordPrompt(String taskId, String requestId, String message)
    {
        try {
            AgentTask task = taskService.get(TaskId.valueOf(taskId));
            recent.taskPrompted(taskId, requestId, message, task.repository().toString(), task.laneId().value());
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
    public List<AgentHttpModels.EventResponse> events(@PathVariable String taskId, @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "100") int limit)
    {
        return eventService.readAfter(TaskId.valueOf(taskId), after, limit).stream()
                .map(AgentHttpModels::event)
                .collect(toImmutableList());
    }

    @GetMapping(path = "/tasks/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String taskId, @RequestParam(defaultValue = "0") long after)
            throws IOException
    {
        return streams.open(eventService.subscribe(TaskId.valueOf(taskId), after));
    }

    @GetMapping("/tasks/{taskId}/history")
    public AgentHttpModels.HistoryResponse history(@PathVariable String taskId, @RequestParam(required = false) Long before, @RequestParam(required = false) Long after)
    {
        return AgentHttpModels.history(eventService.readHistory(
                TaskId.valueOf(taskId),
                before == null ? OptionalLong.empty() : OptionalLong.of(before),
                after == null ? OptionalLong.empty() : OptionalLong.of(after)));
    }

    @GetMapping("/tasks/{taskId}/pending-approvals")
    public List<AgentHttpModels.PendingApprovalResponse> pendingApprovals(@PathVariable String taskId, @RequestParam String turnId)
    {
        AgentTurn turn = turnService.get(TurnId.valueOf(turnId));
        checkArgument(turn.taskId().equals(TaskId.valueOf(taskId)), "Turn does not belong to this task");
        return approvalService.listPending(turn.id()).stream()
                .map(AgentHttpModels::pendingApproval)
                .collect(toImmutableList());
    }

    @PostMapping("/approvals/{approvalId}")
    public AgentHttpModels.ApprovalResponse resolveApproval(@PathVariable String approvalId, @RequestBody ResolveApprovalRequest request)
    {
        checkArgument(request.decision() != null, "decision is null");
        return AgentHttpModels.approval(approvalService.resolve(new ResolveApprovalCommand(
                ApprovalId.valueOf(approvalId),
                request.decision(),
                Optional.ofNullable(request.scopedPermission()),
                Boolean.TRUE.equals(request.forTask()),
                request.answers() == null ? ImmutableMap.of() : ImmutableMap.copyOf(request.answers()))));
    }

    @PostMapping("/tasks/{taskId}/complete")
    public AgentHttpModels.CompletionResponse completeTask(
            @PathVariable String taskId,
            @RequestParam String workspaceId,
            @RequestBody(required = false) CompleteTaskRequest request)
    {
        TaskId id = TaskId.valueOf(taskId);
        taskService.getInteractive(workspaceId, id);
        var completion = completionService.complete(new CompleteAgentTaskCommand(id, request == null ? Optional.empty() : Optional.ofNullable(request.notes())));
        try {
            completionService.cleanupWorktree(id);
        }
        catch (RuntimeException failure) {
            // The task is complete either way; a worktree holding ignored build output is shown as retained.
            log.warn("Task {} completed but its worktree was retained: {}", taskId, failure.toString());
        }
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
    public InteractiveAgentHttpContract.TaskResponse markTaskFailed(@PathVariable String taskId, @RequestParam String workspaceId, @RequestBody MarkTaskFailedRequest request)
    {
        checkText(request.reason(), "reason");
        TaskId id = TaskId.valueOf(taskId);
        AgentTask task = lifecycleTask(workspaceId, id);
        if (task.developmentContinuationUnfinished()) {
            throw new AgentConflictException("Stop development automation before marking the task failed; its linked CI writers must be verified stopped");
        }
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
        return taskResponse(taskService.get(id));
    }

    /**
     * What stopping this task's provider runtime would interrupt, and the generation the stop must echo.
     */
    @GetMapping("/tasks/{taskId}/runtime/stop-impact")
    public AgentHttpModels.RuntimeStopImpactResponse runtimeStopImpact(@PathVariable String taskId, @RequestParam String workspaceId)
    {
        TaskId id = TaskId.valueOf(taskId);
        lifecycleTask(workspaceId, id);
        return AgentHttpModels.stopImpact(turnService.stopImpact(id));
    }

    /**
     * Stops this task's provider process, the last resort behind ordinary cancellation.
     *
     * <p>Development automation also stops its linked CI task. The generation is the one the
     * user confirmed, so a turn that started since cannot be stopped by a stale confirmation.
     */
    @PostMapping("/tasks/{taskId}/runtime/stop")
    public InteractiveAgentHttpContract.TaskResponse stopRuntime(@PathVariable String taskId, @RequestParam String workspaceId, @RequestBody StopRuntimeRequest request)
    {
        checkText(request.generation(), "generation");
        TaskId id = TaskId.valueOf(taskId);
        AgentTask task = lifecycleTask(workspaceId, id);
        return taskResponse(stopTaskRuntime(task, request.generation()));
    }

    private AgentTask lifecycleTask(String workspaceId, TaskId id)
    {
        return taskService.get(id).interactive() ? taskService.getInteractive(workspaceId, id) : contextBuilds.task(workspaceId, id.toString());
    }

    private AgentTask stopTaskRuntime(AgentTask task, String generation)
    {
        return task.worktreeMutationClaim()
                .filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.RECOVERY)
                .map(claim -> startupRecovery.stopRecoveredRuntime(task.id(), generation))
                .orElseGet(() -> turnService.stopRuntime(task.id(), generation));
    }

    @PostMapping("/tasks/{taskId}/cleanup")
    public InteractiveAgentHttpContract.TaskResponse cleanupTask(@PathVariable String taskId, @RequestParam String workspaceId)
    {
        TaskId id = TaskId.valueOf(taskId);
        lifecycleTask(workspaceId, id);
        completionService.cleanupWorktree(id);
        return taskResponse(taskService.get(id));
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

    public record CompleteTaskRequest(String notes) {}

    public record MarkTaskFailedRequest(String reason) {}

    public record StopRuntimeRequest(String generation) {}
}
