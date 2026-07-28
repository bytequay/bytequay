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
package com.bytequay.app.developmentflow;

import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.task.TaskControlHandoff;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.developmentflow.task.TaskManager;
import org.junit.jupiter.api.Test;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.INVALID_STATE;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_EPOCH;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestTaskManager
{
    @Test
    void requestsLifecycleBarriersWithoutProofFreeCompletion()
    {
        CommandTestSupport.Tasks store = new CommandTestSupport.Tasks();
        TaskManager manager = new TaskManager(CommandTestSupport.executor(), store);

        store.put(state(TaskLifecycle.ACTIVE, 1, 0, null));
        assertThat(manager.requestPause(command("pause", 1, 0)).state().lifecycle())
                .isEqualTo(TaskLifecycle.PAUSING);
        assertThatThrownBy(() -> manager.requestPause(command("pause", 1, 99)))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                CommandRejectedException.Reason.COMMAND_ID_CONFLICT));

        store.put(state(TaskLifecycle.PAUSED, 1, 4, null));
        assertThat(manager.requestResume(command("resume", 1, 4)).state().lifecycle())
                .isEqualTo(TaskLifecycle.RESUMING);

        store.put(state(TaskLifecycle.ACTIVE, 1, 8, null));
        assertThat(manager.requestArchive(command("archive", 1, 8)).state().lifecycle())
                .isEqualTo(TaskLifecycle.ARCHIVING);

        store.put(state(TaskLifecycle.ACTIVE, 1, 12, null));
        TaskManager.Command cancel = command("cancel", 1, 12);
        TaskManager.State current = manager.requestCancel(cancel).state();
        assertThat(current.epoch()).isEqualTo(2);
        assertThat(manager.requestCancel(cancel).disposition())
                .isEqualTo(CommandResult.Disposition.DUPLICATE);
        assertThat(current.lifecycle()).isEqualTo(TaskLifecycle.CANCELING);
        assertThat(current.terminalIntent()).isEqualTo(TaskManager.TerminalOutcome.CANCELED);
    }

    @Test
    void rejectsIllegalStaleEpochAndStaleVersionCommands()
    {
        CommandTestSupport.Tasks store = new CommandTestSupport.Tasks();
        TaskManager manager = new TaskManager(CommandTestSupport.executor(), store);
        store.put(state(TaskLifecycle.ACTIVE, 4, 7, null));

        assertThatThrownBy(() -> manager.requestResume(command("illegal", 4, 7)))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(INVALID_STATE));
        store.put(state(TaskLifecycle.PROVISIONING, 4, 7, null));
        assertThatThrownBy(() -> manager.requestPause(command("wrong-active-edge", 4, 7)))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(INVALID_STATE));
        store.put(state(TaskLifecycle.ACTIVE, 4, 7, null));
        assertThatThrownBy(() -> manager.requestPause(command("epoch", 3, 7)))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(STALE_EPOCH));
        assertThatThrownBy(() -> manager.requestPause(command("version", 4, 6)))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(STALE_VERSION));
    }

    @Test
    void completesPauseResumeAndArchiveOnlyFromExactPersistedEvidence()
    {
        CommandTestSupport.Tasks store = new CommandTestSupport.Tasks();
        TaskManager manager = new TaskManager(CommandTestSupport.executor(), store);
        TaskControlHandoff controls = new TaskControlHandoff(
                CommandTestSupport.executor(), manager);

        store.put(state(TaskLifecycle.PAUSING, 4, 10, null));
        store.put(new TaskManager.PauseEvidence(
                "task", 4, "pause-barrier", "stage", 2,
                StageCheckpoint.BRAIN_REVIEW, "stopped-digest"));
        assertThatThrownBy(() -> controls.completePause(
                new TaskManager.PauseCompletionCommand(
                        command("wrong-pause-proof", 4, 10), "pause-barrier", "stage", 2,
                        StageCheckpoint.BRAIN_REVIEW, "tampered-digest")))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(INVALID_STATE));
        TaskManager.PauseCompletionCommand pause = new TaskManager.PauseCompletionCommand(
                command("pause-complete", 4, 10), "pause-barrier", "stage", 2,
                StageCheckpoint.BRAIN_REVIEW, "stopped-digest");
        assertThat(controls.completePause(pause).state().lifecycle())
                .isEqualTo(TaskLifecycle.PAUSED);
        assertThat(controls.completePause(pause).disposition())
                .isEqualTo(CommandResult.Disposition.DUPLICATE);

        store.put(state(TaskLifecycle.RESUMING, 4, 20, null));
        store.put(new TaskManager.ResumeEvidence(
                "task", 4, "resume-reconcile", "stage", 2,
                StageCheckpoint.BRAIN_REVIEW, "reconciled-digest"));
        TaskManager.ResumeCompletionCommand resume = new TaskManager.ResumeCompletionCommand(
                command("resume-complete", 4, 20), "resume-reconcile", "stage", 2,
                StageCheckpoint.BRAIN_REVIEW, "reconciled-digest");
        assertThat(controls.completeResume(resume).state().lifecycle())
                .isEqualTo(TaskLifecycle.ACTIVE);

        store.put(state(TaskLifecycle.ARCHIVING, 4, 30, null));
        store.put(new TaskManager.ArchiveEvidence(
                "task", 4, "archive-evidence", "stage", 2, "no-live-work"));
        TaskManager.ArchiveCompletionCommand archive = new TaskManager.ArchiveCompletionCommand(
                command("archive-complete", 4, 30), "archive-evidence",
                "stage", 2, "no-live-work");
        assertThat(controls.completeArchive(archive).state().lifecycle())
                .isEqualTo(TaskLifecycle.ARCHIVED);

        store.put(state(TaskLifecycle.PAUSING, 5, 40, null));
        assertThatThrownBy(() -> controls.completePause(
                new TaskManager.PauseCompletionCommand(
                        command("wrong-pause", 5, 40), "missing", "stage", 2,
                        StageCheckpoint.BRAIN_REVIEW, "stopped-digest")))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(INVALID_STATE));
    }

    private static TaskManager.Command command(String id, TaskManager.State state)
    {
        return command(id, state.epoch(), state.version());
    }

    private static TaskManager.Command command(String id, long epoch, long version)
    {
        return new TaskManager.Command(id, "user", "task", epoch, version);
    }

    private static TaskManager.State state(
            TaskLifecycle lifecycle,
            long epoch,
            long version,
            TaskManager.TerminalOutcome intent)
    {
        return new TaskManager.State(
                "task", "trunk", lifecycle, epoch, version,
                lifecycle == TaskLifecycle.PROVISIONING ? null : "stage",
                null, null, null, intent);
    }
}
