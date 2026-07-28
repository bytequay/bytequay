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

import com.bytequay.app.developmentflow.task.V2RecoveryControlService;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.CiRecoveryCommand;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.CiRecoveryResult;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.CleanupRecoveryCommand;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.CleanupRecoveryResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

/** Explicit recovery choices for exact V2 CI episodes and Cleanup steps. */
@RestController
public class TaskRecoveryController
{
    private final V2RecoveryControlService recovery;

    public TaskRecoveryController(V2RecoveryControlService recovery)
    {
        this.recovery = requireNonNull(recovery, "recovery is null");
    }

    @PostMapping("/api/tasks/{taskId}/ci-repair/{episodeId}/recover")
    public CiRecoveryResult recoverCi(
            @PathVariable String taskId,
            @PathVariable String episodeId,
            @RequestBody CiRecoveryCommand command)
    {
        return recovery.recoverCi(taskId, episodeId, command);
    }

    @PostMapping("/api/tasks/{taskId}/cleanup/steps/{stepId}/recover")
    public CleanupRecoveryResult recoverCleanup(
            @PathVariable String taskId,
            @PathVariable String stepId,
            @RequestBody CleanupRecoveryCommand command)
    {
        return recovery.recoverCleanup(taskId, stepId, command);
    }
}
