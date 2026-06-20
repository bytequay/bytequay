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
package com.bytequay.app.service.stage;

import com.bytequay.app.beans.stage.StageDetailDto;
import com.bytequay.app.beans.stage.StageDto;
import com.bytequay.app.beans.stage.TaskBrainViewData;

import java.util.List;
import java.util.UUID;

/**
 * Read API for Task stages and the brain view. The DTO shapes are locked
 * against the frontend mock. The content is sparse in this milestone —
 * many fields are placeholders until later milestones populate them — but
 * the shapes are final.
 */
public interface StageService
{
    /** The full brain-view payload for a Task. */
    TaskBrainViewData getBrain(String taskId);

    /** A Task's top-level stages ({@code callerStageId == null}),
     *  oldest-first. */
    List<StageDto> getStages(String taskId);

    /** A Task's stages currently in state {@code OPEN} or {@code ACTIVE}. */
    List<StageDto> getActiveStages(String taskId);

    /** One stage plus its most recent (≤50) lifecycle events. */
    StageDetailDto getStageDetail(UUID stageId);
}
