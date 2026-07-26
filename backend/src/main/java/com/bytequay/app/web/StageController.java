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
package com.bytequay.app.web;

import com.bytequay.app.beans.stage.StageDetailData;
import com.bytequay.app.beans.stage.StageDetailDto;
import com.bytequay.app.beans.stage.StageDto;
import com.bytequay.app.beans.stage.TaskBrainViewData;
import com.bytequay.app.beans.workmodel.ResolvedWorkModelResponse;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.stage.PlanStageService;
import com.bytequay.app.service.stage.StageDetailService;
import com.bytequay.app.service.stage.StageService;
import com.bytequay.app.service.stage.StageSteeringService;
import com.bytequay.app.service.workmodel.ScopeWorkModel;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Endpoints for the Task stages surface and the brain view. Mostly read
 * delegators to {@link StageService}, plus the writes that steer a running
 * stage and set a stage's
 * work-model override. No auth beyond the existing internal-API sanity
 * checks (the backend is a localhost sidecar).
 */
@RestController
public class StageController
{
    private final StageService service;
    private final StageDetailService detailService;
    private final StageSteeringService steeringService;
    private final PlanStageService planStageService;
    private final StageStore stageStore;
    private final TaskStore taskStore;
    private final ThreadStore threadStore;
    private final WorkModelResolver workModelResolver;

    public StageController(
            StageService service,
            StageDetailService detailService,
            StageSteeringService steeringService,
            PlanStageService planStageService,
            StageStore stageStore,
            TaskStore taskStore,
            ThreadStore threadStore,
            WorkModelResolver workModelResolver)
    {
        this.service = requireNonNull(service, "service is null");
        this.detailService = requireNonNull(detailService, "detailService is null");
        this.steeringService = requireNonNull(steeringService, "steeringService is null");
        this.planStageService = requireNonNull(planStageService, "planStageService is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.workModelResolver = requireNonNull(workModelResolver, "workModelResolver is null");
    }

    @GetMapping("/api/tasks/{taskId}/brain")
    public TaskBrainViewData brain(@PathVariable String taskId)
    {
        return service.getBrain(taskId);
    }

    @GetMapping("/api/tasks/{taskId}/stages")
    public List<StageDto> stages(@PathVariable String taskId)
    {
        return service.getStages(taskId);
    }

    @GetMapping("/api/tasks/{taskId}/stages/active")
    public List<StageDto> activeStages(@PathVariable String taskId)
    {
        return service.getActiveStages(taskId);
    }

    @GetMapping("/api/stages/{stageId}")
    public StageDetailDto stage(@PathVariable String stageId)
    {
        return service.getStageDetail(parseStageId(stageId));
    }

    @GetMapping("/api/stages/{stageId}/detail")
    public StageDetailData stageDetail(@PathVariable String stageId)
    {
        return detailService.getDetail(parseStageId(stageId));
    }

    public record SteerRequest(String text, List<String> images) {}

    @PostMapping("/api/stages/{stageId}/steer")
    public StageSteeringService.SteerResult steer(
            @PathVariable String stageId, @RequestBody SteerRequest req)
    {
        return steeringService.steer(
                parseStageId(stageId), req == null ? null : req.text(), req == null ? null : req.images());
    }

    @PostMapping("/api/stages/{planStageId}/approve")
    public PlanStageService.ApproveResult approvePlan(@PathVariable String planStageId)
    {
        return planStageService.approveByStage(parseStageId(planStageId));
    }

    @PostMapping("/api/tasks/{taskId}/replan")
    public PlanStageService.ReplanResult replan(@PathVariable String taskId)
    {
        return planStageService.replan(taskId);
    }

    public record FollowupPatch(String status) {}

    @PatchMapping("/api/stages/{planStageId}/followups/{followupEventId}")
    public void resolveFollowup(
            @PathVariable String planStageId,
            @PathVariable String followupEventId,
            @RequestBody FollowupPatch patch)
    {
        planStageService.resolveFollowup(
                parseStageId(followupEventId), patch == null ? null : patch.status());
    }

    /** GET /api/stages/{stageId}/work-model — resolve the effective work
     *  model for this stage (stage → task → thread → workspace → global),
     *  the most-specific rung on the cascade. */
    @GetMapping("/api/stages/{stageId}/work-model")
    public ResolvedWorkModelResponse getWorkModel(@PathVariable String stageId)
    {
        UUID id = parseStageId(stageId);
        StageInstance stage = requireStage(id);
        Task task = requireTaskForStage(stage);
        WorkModelResolver.Resolved resolved =
                workModelResolver.resolveForStage(task.threadId(), task.id(), stageId);
        return new ResolvedWorkModelResponse(
                stage.workModel(), resolved.choice(), resolved.provenance(),
                !threadStore.listStageMessages(stageId).isEmpty());
    }

    public record WorkModelBody(WorkModel workModel) {}

    /** PUT /api/stages/{stageId}/work-model — set (or clear) the stage's
     *  reasoning-effort override. The engine is the workspace's call, so
     *  engine fields in the body are ignored. A stage's agent session is
     *  built once and reused across every iteration within it, so this is
     *  a stage-open-time choice: it has no effect on a session already
     *  running under this stage, only on the next one built for it. */
    @PutMapping("/api/stages/{stageId}/work-model")
    public ResolvedWorkModelResponse setWorkModel(
            @PathVariable String stageId,
            @RequestBody(required = false) WorkModelBody body)
    {
        UUID id = parseStageId(stageId);
        StageInstance stage = requireStage(id);
        Task task = requireTaskForStage(stage);
        WorkModel stored = ScopeWorkModel.effortOnly(
                workModelResolver.resolveForStage(task.threadId(), task.id(), stageId).choice(),
                body == null ? null : body.workModel());
        stageStore.updateWorkModel(id, stored);
        WorkModelResolver.Resolved resolved =
                workModelResolver.resolveForStage(task.threadId(), task.id(), stageId);
        return new ResolvedWorkModelResponse(
                stored, resolved.choice(), resolved.provenance(),
                !threadStore.listStageMessages(stageId).isEmpty());
    }

    private StageInstance requireStage(UUID stageId)
    {
        return stageStore.findStageById(stageId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no stage: " + stageId));
    }

    private Task requireTaskForStage(StageInstance stage)
    {
        return taskStore.findTaskById(stage.taskId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + stage.taskId()));
    }

    private static UUID parseStageId(String raw)
    {
        try {
            return UUID.fromString(raw);
        }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "bad stage id: " + raw);
        }
    }
}
