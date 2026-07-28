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
import com.bytequay.app.developmentflow.compatibility.V2ControlRouteStore;
import com.bytequay.app.developmentflow.compatibility.V2StageApiService;
import com.bytequay.app.developmentflow.stage.V2PlanControlService;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.stage.PlanStageService;
import com.bytequay.app.service.stage.StageDetailService;
import com.bytequay.app.service.stage.StageRuntimeService;
import com.bytequay.app.service.stage.StageService;
import com.bytequay.app.service.stage.StageSteeringService;
import com.bytequay.app.service.workmodel.ScopeWorkModel;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

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
    private final StageRuntimeService runtimeService;
    private final PlanStageService planStageService;
    private final StageStore stageStore;
    private final TaskStore taskStore;
    private final ThreadStore threadStore;
    private final WorkModelResolver workModelResolver;
    private V2ControlRouteStore v2Routes;
    private V2PlanControlService v2PlanControls;
    private V2StageApiService v2Stages;

    public StageController(
            StageService service,
            StageDetailService detailService,
            StageSteeringService steeringService,
            StageRuntimeService runtimeService,
            PlanStageService planStageService,
            StageStore stageStore,
            TaskStore taskStore,
            ThreadStore threadStore,
            WorkModelResolver workModelResolver)
    {
        this.service = requireNonNull(service, "service is null");
        this.detailService = requireNonNull(detailService, "detailService is null");
        this.steeringService = requireNonNull(steeringService, "steeringService is null");
        this.runtimeService = requireNonNull(runtimeService, "runtimeService is null");
        this.planStageService = requireNonNull(planStageService, "planStageService is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.workModelResolver = requireNonNull(workModelResolver, "workModelResolver is null");
    }

    @Autowired
    void setV2PlanControls(
            V2ControlRouteStore v2Routes,
            V2PlanControlService v2PlanControls)
    {
        this.v2Routes = requireNonNull(v2Routes, "v2Routes is null");
        this.v2PlanControls = requireNonNull(
                v2PlanControls, "v2PlanControls is null");
    }

    @Autowired
    void setV2Stages(
            V2ControlRouteStore v2Routes,
            V2StageApiService v2Stages)
    {
        this.v2Routes = requireNonNull(v2Routes, "v2Routes is null");
        this.v2Stages = requireNonNull(v2Stages, "v2Stages is null");
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
        UUID id = parseStageId(stageId);
        String v2TaskId = v2TaskForStage(id);
        if (v2TaskId != null) {
            return requireV2Stages().detail(v2TaskId, id.toString());
        }
        return detailService.getDetail(id);
    }

    @GetMapping(value = "/api/stages/{stageId}/stream", produces = "text/event-stream")
    public SseEmitter stream(@PathVariable String stageId)
    {
        UUID id = parseStageId(stageId);
        SseEmitter emitter = new SseEmitter(30L * 60L * 1000L);
        String v2TaskId = v2TaskForStage(id);
        Consumer<StreamEvent> listener = event -> {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.getClass().getSimpleName())
                        .data(event));
            }
            catch (IOException e) {
                throw new IllegalStateException("SSE channel closed", e);
            }
        };
        Runnable unsubscribe = v2TaskId == null
                ? runtimeService.subscribe(id, listener)
                : requireV2Stages().subscribe(v2TaskId, id.toString(), listener);
        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(() -> {
            unsubscribe.run();
            emitter.complete();
        });
        emitter.onError(ignored -> unsubscribe.run());
        return emitter;
    }

    @PostMapping("/api/stages/{stageId}/interrupt")
    public void interrupt(@PathVariable String stageId)
    {
        UUID id = parseStageId(stageId);
        String v2TaskId = v2TaskForStage(id);
        if (v2TaskId != null) {
            requireV2Stages().interrupt(v2TaskId, id.toString());
            return;
        }
        runtimeService.interrupt(id);
    }

    public record SteerRequest(
            String text, List<String> images, StageSteeringService.Mode mode) {}

    @PostMapping("/api/stages/{stageId}/steer")
    public StageSteeringService.SteerResult steer(
            @PathVariable String stageId, @RequestBody SteerRequest req)
    {
        return steeringService.steer(
                parseStageId(stageId), req == null ? null : req.text(),
                req == null ? null : req.images(),
                req == null || req.mode() == null
                        ? StageSteeringService.Mode.APPEND : req.mode());
    }

    @PostMapping("/api/stages/{planStageId}/approve")
    public PlanStageService.ApproveResult approvePlan(@PathVariable String planStageId)
    {
        UUID stageId = parseStageId(planStageId);
        if (isV2Stage(stageId)) {
            V2PlanControlService.Approval approval = requireV2PlanControls()
                    .approve(stageId.toString());
            return new PlanStageService.ApproveResult(
                    approval.localStageId(),
                    "/tasks/" + approval.taskId() + "/stages/"
                            + approval.localStageId());
        }
        return planStageService.approveByStage(stageId);
    }

    @PostMapping("/api/tasks/{taskId}/replan")
    public PlanStageService.ReplanResult replan(@PathVariable String taskId)
    {
        if (taskStore.isV2Task(taskId)) {
            V2PlanControlService.Replan result = requireV2PlanControls()
                    .replan(taskId);
            return new PlanStageService.ReplanResult(
                    result.planStageId(), result.preparing());
        }
        return planStageService.replan(taskId);
    }

    public record FollowupPatch(String status) {}

    @PatchMapping("/api/stages/{planStageId}/followups/{followupEventId}")
    public void resolveFollowup(
            @PathVariable String planStageId,
            @PathVariable String followupEventId,
            @RequestBody FollowupPatch patch)
    {
        UUID stageId = parseStageId(planStageId);
        String v2TaskId = v2TaskForStage(stageId);
        if (v2TaskId != null) {
            requireV2PlanControls().resolveFollowup(
                    v2TaskId, stageId.toString(), followupEventId,
                    patch == null ? null : patch.status());
            return;
        }
        planStageService.resolveFollowup(
                parseStageId(followupEventId), patch == null ? null : patch.status());
    }

    private boolean isV2Stage(UUID stageId)
    {
        return v2TaskForStage(stageId) != null;
    }

    private String v2TaskForStage(UUID stageId)
    {
        return v2Routes == null ? null
                : v2Routes.taskForStage(stageId.toString()).orElse(null);
    }

    private V2PlanControlService requireV2PlanControls()
    {
        if (v2PlanControls == null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(503),
                    "V2 Plan controls are not configured");
        }
        return v2PlanControls;
    }

    private V2StageApiService requireV2Stages()
    {
        if (v2Stages == null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(503),
                    "V2 Stage APIs are not configured");
        }
        return v2Stages;
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
