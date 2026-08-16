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
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.ResolveApprovalCommand;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.spi.AgentAttachment;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.google.common.collect.ImmutableList;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/agent")
public class AgentController
{
    public static final String SESSION_HEADER = "X-ByteQuay-Session";

    private final AgentTaskService taskService;
    private final AgentTurnService turnService;
    private final AgentEventService eventService;
    private final AgentApprovalService approvalService;
    private final AgentCompletionService completionService;
    private final AgentDriverRegistry driverRegistry;

    public AgentController(
            AgentTaskService taskService,
            AgentTurnService turnService,
            AgentEventService eventService,
            AgentApprovalService approvalService,
            AgentCompletionService completionService,
            AgentDriverRegistry driverRegistry)
    {
        this.taskService = requireNonNull(taskService, "taskService is null");
        this.turnService = requireNonNull(turnService, "turnService is null");
        this.eventService = requireNonNull(eventService, "eventService is null");
        this.approvalService = requireNonNull(approvalService, "approvalService is null");
        this.completionService = requireNonNull(completionService, "completionService is null");
        this.driverRegistry = requireNonNull(driverRegistry, "driverRegistry is null");
    }

    @PostMapping("/tasks")
    public ResponseEntity<AgentHttpModels.TaskResponse> createTask(@RequestBody CreateTaskRequest request)
    {
        requireNotBlank(request.laneId(), "laneId must not be blank");
        requireNotBlank(request.worktree(), "worktree must not be blank");
        requireNotBlank(request.repository(), "repository must not be blank");
        requireNotBlank(request.title(), "title must not be blank");
        return ResponseEntity.status(HttpStatus.CREATED).body(AgentHttpModels.task(taskService.create(
                new CreateAgentTaskCommand(
                        new AgentLaneId(request.laneId()),
                        Path.of(request.worktree()),
                        RepositoryIdentity.valueOf(request.repository()),
                        request.title(),
                        AgentPermissionPolicy.MANUAL))));
    }

    @GetMapping("/tasks/{taskId}")
    public AgentHttpModels.TaskResponse getTask(@PathVariable String taskId)
    {
        return AgentHttpModels.task(taskService.get(TaskId.valueOf(taskId)));
    }

    @GetMapping("/tasks")
    public List<AgentHttpModels.TaskResponse> listTasks(
            @RequestParam(defaultValue = "100") int limit)
    {
        return taskService.list(limit).stream()
                .map(AgentHttpModels::task)
                .collect(toImmutableList());
    }

    @PostMapping("/tasks/{taskId}/turns")
    public ResponseEntity<AgentHttpModels.TurnResponse> startTurn(
            @PathVariable String taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody StartTurnRequest request)
    {
        requireNotBlank(request.message(), "message must not be blank");
        requireNotBlank(idempotencyKey, "Idempotency-Key must not be blank");
        List<AgentAttachment> attachments = request.attachments() == null
                ? ImmutableList.of()
                : request.attachments().stream()
                        .map(AttachmentRequest::toAttachment)
                        .collect(toImmutableList());
        return ResponseEntity.accepted().body(AgentHttpModels.turn(turnService.start(new StartAgentTurnCommand(
                TaskId.valueOf(taskId),
                request.message(),
                attachments,
                idempotencyKey))));
    }

    @GetMapping("/turns/{turnId}")
    public AgentHttpModels.TurnResponse getTurn(@PathVariable String turnId)
    {
        return AgentHttpModels.turn(turnService.get(TurnId.valueOf(turnId)));
    }

    @PostMapping("/turns/{turnId}/cancel")
    public AgentHttpModels.TurnResponse cancelTurn(@PathVariable String turnId)
    {
        return AgentHttpModels.turn(turnService.cancel(TurnId.valueOf(turnId)));
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
        if (request.decision() == null) {
            throw new IllegalArgumentException("decision is null");
        }
        return AgentHttpModels.approval(approvalService.resolve(new ResolveApprovalCommand(
                ApprovalId.valueOf(approvalId),
                request.decision(),
                Optional.ofNullable(request.scopedPermission()))));
    }

    @PostMapping("/tasks/{taskId}/complete")
    public AgentHttpModels.CompletionResponse completeTask(
            @PathVariable String taskId,
            @RequestBody(required = false) CompleteTaskRequest request)
    {
        return AgentHttpModels.completion(completionService.complete(new CompleteAgentTaskCommand(
                TaskId.valueOf(taskId),
                request == null ? Optional.empty() : Optional.ofNullable(request.notes()))));
    }

    @PostMapping("/tasks/{taskId}/cleanup")
    public AgentHttpModels.CleanupResponse cleanupTask(@PathVariable String taskId)
    {
        return AgentHttpModels.cleanup(completionService.cleanup(TaskId.valueOf(taskId)));
    }

    @GetMapping("/providers/status")
    public List<AgentHttpModels.ProviderStatusResponse> providerStatuses()
    {
        return driverRegistry.statuses().stream()
                .map(AgentHttpModels::providerStatus)
                .collect(toImmutableList());
    }

    public record CreateTaskRequest(String laneId, String worktree, String repository, String title) {}

    public record StartTurnRequest(String message, List<AttachmentRequest> attachments) {}

    public record AttachmentRequest(String name, String mediaType, String path)
    {
        private AgentAttachment toAttachment()
        {
            requireNotBlank(path, "attachment path must not be blank");
            return new AgentAttachment(name, mediaType, Path.of(path));
        }
    }

    public record ResolveApprovalRequest(
            ApprovalDecision.Decision decision,
            String scopedPermission) {}

    public record CompleteTaskRequest(String notes) {}
}
