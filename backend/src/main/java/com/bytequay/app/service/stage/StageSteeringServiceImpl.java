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

import com.bytequay.app.developmentflow.compatibility.V2ControlRouteStore;
import com.bytequay.app.developmentflow.stage.V2StageSteeringControl;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.sqlite.SqliteStageStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Enqueues a steering turn on the Task's dev thread. The agent's own
 * user-message echo is the conversation row; the steered message is
 * attributed to the stage by time window at read time (see the stage
 * detail metrics + brain feed), so nothing is written here beyond the turn
 * and, for monitor stages, its iteration.
 */
@Service
public class StageSteeringServiceImpl
{
    public enum Mode
    {
        APPEND,
        CANCEL_AND_REPLACE
    }

    public record SteerResult(String turnId) {}

    private final SqliteStageStore stageStore;
    private final TaskStore taskStore;
    private V2ControlRouteStore v2Routes;
    private V2StageSteeringControl v2Steering;

    public StageSteeringServiceImpl(
            SqliteStageStore stageStore,
            TaskStore taskStore)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
    }

    @Autowired
    void setV2Routes(V2ControlRouteStore v2Routes)
    {
        this.v2Routes = requireNonNull(v2Routes, "v2Routes is null");
    }

    @Autowired(required = false)
    void setV2Steering(V2StageSteeringControl v2Steering)
    {
        this.v2Steering = requireNonNull(v2Steering, "v2Steering is null");
    }

    public SteerResult steer(UUID stageId, String text, List<String> images)
    {
        return steer(stageId, text, images, Mode.APPEND, null);
    }

    public SteerResult steer(
            UUID stageId, String text, List<String> images, Mode mode)
    {
        return steer(stageId, text, images, mode, null);
    }
    public SteerResult steer(
            UUID stageId, String text, List<String> images, Mode mode,
            String expectedPredecessorStageTurnId)
    {
        requireNonNull(mode, "mode is null");
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty() && (images == null || images.isEmpty())) {
            throw status(400, "steering message is empty");
        }
        String v2TaskId = v2Routes == null
                ? null : v2Routes.taskForStage(stageId.toString()).orElse(null);
        if (v2TaskId != null) {
            if (v2Steering == null) {
                throw status(503, "V2 Stage steering is not configured");
            }
            String turnId = v2Steering.steer(
                    v2TaskId, stageId.toString(), trimmed, images,
                    V2StageSteeringControl.Mode.valueOf(mode.name()),
                    expectedPredecessorStageTurnId);
            return new SteerResult(turnId);
        }
        if (mode != Mode.APPEND) {
            throw status(422,
                    "CANCEL_AND_REPLACE is available only for V2 stages");
        }
        String taskId = stageStore.findStageById(stageId)
                .map(StageInstance::taskId)
                .orElseThrow(() -> status(404, "no stage: " + stageId));
        if (taskStore.isV2Task(taskId)) {
            throw status(503, "V2 Stage steering route is unavailable");
        }
        throw status(409,
                "Historical LEGACY Stage " + stageId
                        + " is read-only; use a typed V2 Stage");
    }

    private static ResponseStatusException status(int code, String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(code), message);
    }
}
