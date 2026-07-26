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

import com.bytequay.app.domain.Actor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import static java.util.Objects.requireNonNull;

/** Converts a durable task-owned scheduler conflict into the normal recovery gate. */
@Component
public class TaskSchedulerConflictBridge
{
    private static final String RECOVERY_REASON = "scheduler_turn_conflict";

    private final TaskPhaseMachine tasks;

    public TaskSchedulerConflictBridge(TaskPhaseMachine tasks)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onConflict(TaskSchedulerConflictEvent event)
    {
        TaskCommandExecutor.dispatchAfterCommit(() -> tasks.parkOperational(
                event.taskId(), Actor.SCHEDULER, RECOVERY_REASON));
    }
}
