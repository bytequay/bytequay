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

import com.bytequay.app.domain.Task;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared task-state guards for the ship / advance flows. Both
 * {@code TaskService.shipOrParkAndStartNext} and
 * {@code PublishService.preflightAdvance} need the same "is this task
 * still shippable" check before they push a branch and open a PR, so the
 * three-field guard lives here once instead of being copy-pasted.
 */
final class TaskPreconditions
{
    private TaskPreconditions() {}

    /**
     * Asserts the task still carries the local working dir, worktree, and
     * branch a push/ship needs. Throws {@code 409} naming the task and the
     * first missing piece — a task whose worktree was already reaped (or
     * that never had one) can't be shipped.
     */
    static void requireShippable(Task task)
    {
        if (task.workingDir() == null || task.workingDir().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + task.id() + " has no working dir; nothing to ship");
        }
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + task.id() + " has no worktree; nothing to ship");
        }
        if (task.branchName() == null || task.branchName().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + task.id() + " has no branch name; nothing to ship");
        }
    }
}
