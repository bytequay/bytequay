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

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageResumeRearmStore;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.task.TaskResumeOwner;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final TaskManager tasks;
    private final LocalPublishBaseSyncRuntimeCoordinator baseSync;

    @Autowired
    LocalTaskResumeOwner(
            SqliteStageResumeRearmStore store,
            TaskCommandExecutor commands,
            TaskManager tasks,
            LocalPublishBaseSyncRuntimeCoordinator baseSync)
    {
        this.store = requireNonNull(store, "store is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.baseSync = requireNonNull(baseSync, "baseSync is null");
    }

    LocalTaskResumeOwner(
            SqliteStageResumeRearmStore store,
            TaskCommandExecutor commands,
            TaskManager tasks)
    {
        this.store = requireNonNull(store, "store is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.baseSync = null;
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
                        case VALIDATING -> store.materializeValidation(intent, now);
                        case BRAIN_REVIEW -> materializeBrainReview(intent, now);
                        case LOCAL_REVIEW -> materializeLocalReview(intent, now);
                        case PUBLISHING -> store.recoverPublish(intent, now);
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

    private void materializeLocalReview(
            SqliteStageResumeRearmStore.Intent intent, Instant now)
    {
        store.materializePassiveWait(
                intent, kind(), StageCheckpoint.LOCAL_REVIEW, now);
        if (baseSync != null) {
            baseSync.resumePausedInCommand(
                    intent.handoffId(), intent.taskId(), intent.stageId(),
                    intent.taskEpoch(), intent.stageGeneration(), now);
        }
    }

    private void materializeBrainReview(
            SqliteStageResumeRearmStore.Intent intent, Instant now)
    {
        SqliteStageResumeRearmStore.BrainResume resume =
                store.prepareBrainReview(intent, now);
        var requested = tasks.requestBrainReviewInCommand(
                new TaskManager.BrainReviewRequestCommand(
                        resume.commandId(), "stage-resume-owner",
                        intent.taskId(), intent.taskEpoch(),
                        intent.currentTaskVersion(), resume.episodeId(),
                        resume.fence()));
        if (requested.disposition() == CommandResult.Disposition.SUPERSEDED) {
            throw new IllegalStateException("Brain resume request was superseded");
        }
        store.completeBrainReview(intent, resume, now);
    }
}
