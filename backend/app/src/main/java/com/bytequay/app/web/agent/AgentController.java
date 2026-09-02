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
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.ResolveApprovalCommand;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.UpdateAgentTaskDefaultsCommand;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agent.InteractiveAgentTaskCreator;
import com.bytequay.app.service.recent.RecentTouchRecorder;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final RecentTouchRecorder recent;

    public AgentController(
            AgentTaskService taskService,
            InteractiveAgentTaskCreator taskCreator,
            AgentTurnService turnService,
            AgentEventService eventService,
            AgentApprovalService approvalService,
            AgentCompletionService completionService,
            AgentDriverRegistry driverRegistry,
            RecentTouchRecorder recent)
    {
        this.taskService = requireNonNull(taskService, "taskService is null");
        this.taskCreator = requireNonNull(taskCreator, "taskCreator is null");
        this.turnService = requireNonNull(turnService, "turnService is null");
        this.eventService = requireNonNull(eventService, "eventService is null");
        this.approvalService = requireNonNull(approvalService, "approvalService is null");
        this.completionService = requireNonNull(completionService, "completionService is null");
        this.driverRegistry = requireNonNull(driverRegistry, "driverRegistry is null");
        this.recent = requireNonNull(recent, "recent is null");
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
                request.title(),
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
        if (request.attachments() != null && !request.attachments().isEmpty()) {
            throw new InvalidAgentRequestException("Interactive attachments are not available yet");
        }
        AgentTurn turn = turnService.start(new StartAgentTurnCommand(
                TaskId.valueOf(taskId),
                request.message(),
                ImmutableList.of(),
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
    {
        SseEmitter emitter = new SseEmitter(0L);
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
     * Fails a task whose turn could not be shown to have stopped touching its worktree.
     *
     * <p>Such a turn keeps its worktree claim, which deliberately blocks every later turn. This is
     * the way out, and it retains the worktree rather than cleaning it, so whatever the provider
     * left behind stays inspectable.
     */
    @PostMapping("/tasks/{taskId}/abandon")
    public InteractiveAgentHttpContract.TaskResponse abandonTask(
            @PathVariable String taskId,
            @RequestParam String workspaceId,
            @RequestBody AbandonTaskRequest request)
    {
        requireNotBlank(request.reason(), "reason must not be blank");
        TaskId id = TaskId.valueOf(taskId);
        taskService.getInteractive(workspaceId, id);
        return InteractiveAgentHttpContract.task(completionService.abandon(id, request.reason()));
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

    @GetMapping("/providers/status")
    public List<AgentHttpModels.ProviderStatusResponse> providerStatuses()
    {
        return driverRegistry.statuses().stream()
                .map(AgentHttpModels::providerStatus)
                .collect(toImmutableList());
    }

    public record StartTurnRequest(String message, List<Object> attachments) {}

    public record ResolveApprovalRequest(
            ApprovalDecision.Decision decision,
            String scopedPermission) {}

    public record CompleteTaskRequest(String notes) {}

    public record AbandonTaskRequest(String reason) {}
}
