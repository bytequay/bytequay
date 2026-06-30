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

import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Published when the brain records a {@code finalized} plan on an open
 * PlanStage — i.e. a plan ready for the user to approve. {@link
 * AutoApprovePlanListener} approves it on the user's behalf when the task's
 * auto-approve toggle is on; otherwise it's inert and the plan waits for the
 * manual "Approve plan" click.
 */
public record PlanFinalizedEvent(String taskId, UUID planStageId)
{
    public PlanFinalizedEvent
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(planStageId, "planStageId is null");
    }
}
