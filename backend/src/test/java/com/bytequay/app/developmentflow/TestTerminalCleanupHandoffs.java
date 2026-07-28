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

import com.bytequay.app.developmentflow.stage.CleanupQuiescenceHandoff;
import com.bytequay.app.developmentflow.stage.CleanupStageManager;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.RemoteTerminalToCleanupHandoff;
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.stage.StageEndReason;
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestTerminalCleanupHandoffs
{
    @Test
    void observedRemoteTruthStartsCleanupFromEveryDeclaredTaskSource()
    {
        List<TaskLifecycle> sources = List.of(
                TaskLifecycle.ACTIVE, TaskLifecycle.PAUSED, TaskLifecycle.ARCHIVED);
        for (TaskLifecycle source : sources) {
            for (RemoteDevelopmentStageManager.TerminalOutcome outcome
                    : RemoteDevelopmentStageManager.TerminalOutcome.values()) {
                assertTerminalCleanup(source, outcome);
            }
        }
    }

    private static void assertTerminalCleanup(
            TaskLifecycle source, RemoteDevelopmentStageManager.TerminalOutcome outcome)
    {
        TaskCommandExecutor executor = CommandTestSupport.executor();
        CommandTestSupport.Tasks tasks = new CommandTestSupport.Tasks();
        tasks.put(new TaskManager.State(
                "task", "trunk", source, 3, 20, "remote",
                null, null, null, null));
        CommandTestSupport.Stages stages = new CommandTestSupport.Stages(
                taskId -> tasks.findById(taskId).orElse(null));
        stages.put(new StageManager.State(
                "remote", "task", StageKind.REMOTE_DEVELOPMENT, 2, 8,
                StageCheckpoint.MERGING, null, null));
        RemoteDevelopmentStageManager.TerminalObservationEvidence observation =
                new RemoteDevelopmentStageManager.TerminalObservationEvidence(
                        "task", 3, "remote", 2, "observation", 7,
                        "remote-head", "base", outcome);
        RemoteDevelopmentStageManager remote = new RemoteDevelopmentStageManager(
                executor, stages, terminalEvidence(observation));
        TaskManager taskManager = new TaskManager(executor, tasks);
        CleanupStageManager cleanup = new CleanupStageManager(executor, stages);
        RemoteTerminalToCleanupHandoff handoff = new RemoteTerminalToCleanupHandoff(
                executor, remote, taskManager, cleanup);
        StageManager.Command stageCommand = new StageManager.Command(
                "terminal", "observer", "task", 3, "remote", 2, 8);
        RemoteTerminalToCleanupHandoff.Command command =
                new RemoteTerminalToCleanupHandoff.Command(
                        new RemoteDevelopmentStageManager.TerminalObservationCommand(
                                stageCommand, "observation", 7,
                                "remote-head", "base", outcome),
                        new TaskManager.TerminalCleanupCommand(
                                "terminal", "observer", "task", 3, 20,
                                "cleanup", 1));

        for (RemoteDevelopmentStageManager.RemoteSubjectEvidence staleSubject : List.of(
                new RemoteDevelopmentStageManager.RemoteSubjectEvidence(
                        "task", 3, "remote", 2, 7, "new-head", "base"),
                new RemoteDevelopmentStageManager.RemoteSubjectEvidence(
                        "task", 3, "remote", 2, 8, "remote-head", "base"))) {
            RemoteDevelopmentStageManager staleRemote = new RemoteDevelopmentStageManager(
                    executor, stages, terminalEvidence(observation, staleSubject));
            RemoteTerminalToCleanupHandoff staleHandoff = new RemoteTerminalToCleanupHandoff(
                    executor, staleRemote, taskManager, cleanup);
            assertThatThrownBy(() -> staleHandoff.accept(command))
                    .isInstanceOfSatisfying(CommandRejectedException.class,
                            failure -> assertThat(failure.reason())
                                    .isEqualTo(CommandRejectedException.Reason.INVALID_STATE));
        }

        RemoteTerminalToCleanupHandoff.Result result = executor.execute(
                "task", () -> handoff.acceptInCommand(command));
        assertThat(result.remote().state().endReason()).isEqualTo(
                outcome == RemoteDevelopmentStageManager.TerminalOutcome.MERGED
                        ? StageEndReason.REMOTE_MERGED
                        : StageEndReason.REMOTE_CLOSED);
        assertThat(result.task())
                .extracting(
                        TaskManager.State::lifecycle,
                        TaskManager.State::terminalIntent,
                        TaskManager.State::currentStageId)
                .containsExactly(
                        TaskLifecycle.CLEANING,
                        outcome == RemoteDevelopmentStageManager.TerminalOutcome.MERGED
                                ? TaskManager.TerminalOutcome.COMPLETED
                                : TaskManager.TerminalOutcome.REMOTE_CLOSED,
                        "cleanup");
        assertThat(result.cleanup().state().checkpoint())
                .isEqualTo(StageCheckpoint.WAITING_QUIESCENCE);

        tasks.put(new TaskManager.QuiescenceEvidence(
                "task", 3, "cleanup-barrier", TaskManager.QuiescenceReason.CLEANUP));
        CleanupQuiescenceHandoff quiescence = new CleanupQuiescenceHandoff(
                executor, taskManager, cleanup);
        assertThat(quiescence.accept(new TaskManager.CleanupQuiescenceCommand(
                        "cleanup-quiesced", "system", "task", 3, 21,
                        "cleanup", 1, 0, "cleanup-barrier")).state().checkpoint())
                .isEqualTo(StageCheckpoint.CLEANING);

        RemoteTerminalToCleanupHandoff.Result replay = handoff.accept(command);
        assertThat(replay.remote().disposition()).isEqualTo(CommandResult.Disposition.DUPLICATE);
        assertThat(replay.cleanup().disposition()).isEqualTo(CommandResult.Disposition.DUPLICATE);
        assertThat(replay.cleanup().state().checkpoint())
                .as("duplicate replays the immutable original receipt")
                .isEqualTo(StageCheckpoint.WAITING_QUIESCENCE);

        RemoteTerminalToCleanupHandoff.Command alteredSubject =
                new RemoteTerminalToCleanupHandoff.Command(
                        new RemoteDevelopmentStageManager.TerminalObservationCommand(
                                stageCommand, "observation", 8,
                                "new-head", "base", outcome),
                        command.task());
        assertThatThrownBy(() -> handoff.accept(alteredSubject))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(CommandRejectedException.Reason.COMMAND_ID_CONFLICT));
    }

    private static RemoteDevelopmentStageManager.EvidenceStore terminalEvidence(
            RemoteDevelopmentStageManager.TerminalObservationEvidence observation)
    {
        return terminalEvidence(
                observation,
                new RemoteDevelopmentStageManager.RemoteSubjectEvidence(
                        observation.taskId(), observation.taskEpoch(), observation.stageId(),
                        observation.stageGeneration(), observation.observationRevision(),
                        observation.remoteHeadSha(), observation.baseSha()));
    }

    private static RemoteDevelopmentStageManager.EvidenceStore terminalEvidence(
            RemoteDevelopmentStageManager.TerminalObservationEvidence observation,
            RemoteDevelopmentStageManager.RemoteSubjectEvidence subject)
    {
        return new RemoteDevelopmentStageManager.EvidenceStore()
        {
            @Override
            public Optional<RemoteDevelopmentStageManager.FeedbackEvidence>
                    findRemoteFeedback(
                            String taskId, String stageId, long generation, String batchId)
            {
                return Optional.empty();
            }

            @Override
            public Optional<RemoteDevelopmentStageManager.MergeAuthorizationEvidence>
                    findMergeAuthorization(
                            String taskId,
                            String stageId,
                            long generation,
                            String authorizationId)
            {
                return Optional.empty();
            }

            @Override
            public Optional<RemoteDevelopmentStageManager.TerminalObservationEvidence>
                    findTerminalObservation(
                            String taskId,
                            String stageId,
                            long generation,
                            String observationId)
            {
                return Optional.of(observation);
            }

            @Override
            public Optional<RemoteDevelopmentStageManager.RemoteSubjectEvidence>
                    findCurrentRemoteSubject(
                            String taskId, String stageId, long generation)
            {
                return Optional.of(subject);
            }
        };
    }
}
