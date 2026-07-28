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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageResumeRearmStore;
import com.bytequay.app.developmentflow.task.TaskResumeOwner;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

@Component
final class LocalTaskResumeOwner
        implements TaskResumeOwner, ExecutionPorts.MaintenanceWork
{
    private static final int LIMIT = 32;

    private final SqliteStageResumeRearmStore store;
    private final TaskCommandExecutor commands;

    LocalTaskResumeOwner(
            SqliteStageResumeRearmStore store, TaskCommandExecutor commands)
    {
        this.store = requireNonNull(store, "store is null");
        this.commands = requireNonNull(commands, "commands is null");
    }

    @Override
    public StageKind kind() { return StageKind.LOCAL_DEVELOPMENT; }

    @Override
    public Acceptance accept(Request request) { return store.accept(request, kind()); }

    @Override
    public void maintain(Instant now)
    {
        for (var intent : store.pending(kind(), LIMIT)) {
            if (!intent.taskLifecycle().equals("ACTIVE")) {
                continue;
            }
            try {
                commands.executeVoid(intent.taskId(), () -> {
                    switch (intent.restoreCheckpoint()) {
                        case IMPLEMENTING, ADDRESSING_BRAIN_FINDINGS,
                                ADDRESSING_LOCAL_FEEDBACK ->
                                store.materializeLocalTurn(intent, now);
                        case LOCAL_REVIEW -> store.diagnose(
                                intent, "USER_WAIT_OWNER_NOT_FROZEN",
                                "Local Review wait has no exact typed question owner in V257",
                                now);
                        default -> store.diagnose(
                                intent, "LOCAL_RESUME_CURSOR_UNSUPPORTED",
                                "No exact Local continuation is frozen for "
                                        + intent.restoreCheckpoint(), now);
                    }
                });
            }
            catch (IllegalStateException ambiguous) {
                commands.executeVoid(intent.taskId(), () ->
                        store.diagnose(intent,
                                "LOCAL_RESUME_PREDECESSOR_AMBIGUOUS",
                                ambiguous.getMessage(), now));
            }
        }
    }
}
