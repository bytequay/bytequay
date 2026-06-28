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

/**
 * Fired right after a Task's active stage is closed on a phase transition.
 * Each per-stage CLI agent is keyed by its stage id, so the runtime listens
 * for this to evict the closed stage's agent — releasing its CLI subprocess
 * and worktree lease — without disturbing the thread's other concurrent
 * stages.
 *
 * @param taskId  the task the closed stage belonged to
 * @param stageId the id of the stage that just closed (stringified UUID)
 */
public record StageClosedEvent(String taskId, String stageId)
{
}
