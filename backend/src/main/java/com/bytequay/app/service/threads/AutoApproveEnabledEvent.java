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
package com.bytequay.app.service.threads;

import static java.util.Objects.requireNonNull;

/**
 * Published when a task's auto-approve toggle is switched on. {@link
 * AutoApproveGateListener} sweeps the task's already-parked publish gates and
 * approves the non-merge ones — so enabling auto-approve clears a gate that is
 * already sitting there, not just gates that park afterwards.
 */
public record AutoApproveEnabledEvent(String threadId, String taskId)
{
    public AutoApproveEnabledEvent
    {
        requireNonNull(threadId, "threadId is null");
        requireNonNull(taskId, "taskId is null");
    }
}
