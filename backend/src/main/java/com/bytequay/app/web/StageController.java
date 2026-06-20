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

import com.bytequay.app.beans.stage.SpawnReviewResult;
import com.bytequay.app.beans.stage.StageDetailData;
import com.bytequay.app.beans.stage.StageDetailDto;
import com.bytequay.app.beans.stage.StageDto;
import com.bytequay.app.beans.stage.TaskBrainViewData;
import com.bytequay.app.service.stage.ReviewStageService;
import com.bytequay.app.service.stage.StageDetailService;
import com.bytequay.app.service.stage.StageService;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Endpoints for the Task stages surface and the brain view. Mostly read
 * delegators to {@link StageService}, plus the one write that spawns a
 * callable review sub-stage. No auth beyond the existing internal-API
 * sanity checks (the backend is a localhost sidecar).
 */
@RestController
public class StageController
{
    private final StageService service;
    private final StageDetailService detailService;
    private final ReviewStageService reviewStageService;

    public StageController(
            StageService service,
            StageDetailService detailService,
            ReviewStageService reviewStageService)
    {
        this.service = requireNonNull(service, "service is null");
        this.detailService = requireNonNull(detailService, "detailService is null");
        this.reviewStageService = requireNonNull(reviewStageService, "reviewStageService is null");
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

    @PostMapping("/api/stages/{parentStageId}/spawn-review")
    public SpawnReviewResult spawnReview(@PathVariable String parentStageId)
    {
        return reviewStageService.spawnReview(parseStageId(parentStageId));
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
