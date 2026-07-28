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
package com.bytequay.app.developmentflow.task;

import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.developmentflow.execution.cleanup.SqliteCleanupOperationStore;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteObservationRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CiEpisode;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationRequest;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.bytequay.app.developmentflow.task.V2RecoveryControlService.CiRecoveryAction.CONTINUE_WITH_PER_PUSH_APPROVAL;
import static com.bytequay.app.developmentflow.task.V2RecoveryControlService.CiRecoveryAction.EXTEND_BUDGET;
import static com.bytequay.app.developmentflow.task.V2RecoveryControlService.CiRecoveryAction.MANUAL_TAKEOVER;
import static com.bytequay.app.developmentflow.task.V2RecoveryControlService.CiRecoveryAction.STOP_AUTOMATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestV2RecoveryControlService
{
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");

    private final TaskCommandExecutor commands = mock(TaskCommandExecutor.class);
    private final RemoteCiRepairRuntimeCoordinator ciRepair =
            mock(RemoteCiRepairRuntimeCoordinator.class);
    private final RemoteObservationRuntimeCoordinator observations =
            mock(RemoteObservationRuntimeCoordinator.class);
    private final SqliteCleanupOperationStore cleanup =
            mock(SqliteCleanupOperationStore.class);
    private final DispatchTicketControl tickets = mock(DispatchTicketControl.class);
    private final V2RecoveryControlService controls = new V2RecoveryControlService(
            commands, ciRepair, observations, cleanup, tickets,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void exposesAllFourCiExhaustionChoices()
    {
        CiEpisode open = episode("OPEN");
        CiEpisode stopped = episode("STOPPED");
        when(ciRepair.extendBudget(
                "task-1", "episode-1", "extend", 1, 2, 3,
                "user", "extend budgets"))
                .thenReturn(open);
        when(ciRepair.continueWithPerPushApproval(
                "task-1", "episode-1", "per-push", "user",
                "approve one push"))
                .thenReturn(open);
        when(ciRepair.manualTakeover(
                "task-1", "episode-1", "manual", "user", "take over"))
                .thenReturn(stopped);
        when(ciRepair.stopAutomation(
                "task-1", "episode-1", "stop", "user", "stop"))
                .thenReturn(stopped);
        when(observations.requestObservation("task-1", "stage-1"))
                .thenReturn(new ObservationRequest(
                        "observation-row", "observation-operation",
                        "task-1", "stage-1", 2, NOW));

        assertThat(controls.recoverCi(
                "task-1", "episode-1",
                new V2RecoveryControlService.CiRecoveryCommand(
                        "extend", EXTEND_BUDGET, 1, 2, 3,
                        "extend budgets")).status())
                .isEqualTo("OPEN");
        assertThat(controls.recoverCi(
                "task-1", "episode-1",
                new V2RecoveryControlService.CiRecoveryCommand(
                        "per-push", CONTINUE_WITH_PER_PUSH_APPROVAL,
                        0, 0, 0, "approve one push"))
                .observationOperationId()).isEqualTo("observation-operation");
        assertThat(controls.recoverCi(
                "task-1", "episode-1",
                new V2RecoveryControlService.CiRecoveryCommand(
                        "manual", MANUAL_TAKEOVER, 0, 0, 0,
                        "take over")).status())
                .isEqualTo("STOPPED");
        assertThat(controls.recoverCi(
                "task-1", "episode-1",
                new V2RecoveryControlService.CiRecoveryCommand(
                        "stop", STOP_AUTOMATION, 0, 0, 0, "stop")).status())
                .isEqualTo("STOPPED");

        verify(ciRepair).extendBudget(
                "task-1", "episode-1", "extend", 1, 2, 3,
                "user", "extend budgets");
        verify(ciRepair).continueWithPerPushApproval(
                "task-1", "episode-1", "per-push", "user",
                "approve one push");
        verify(ciRepair).manualTakeover(
                "task-1", "episode-1", "manual", "user", "take over");
        verify(ciRepair).stopAutomation(
                "task-1", "episode-1", "stop", "user", "stop");
    }

    @Test
    void rejectsBudgetDeltasForNonExtensionChoices()
    {
        assertThatThrownBy(() ->
                controls.recoverCi(
                        "task-1", "episode-1",
                        new V2RecoveryControlService.CiRecoveryCommand(
                                "stop", STOP_AUTOMATION, 0, 1, 0, "stop")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only EXTEND_BUDGET accepts positive CI budget deltas");
        verifyNoInteractions(ciRepair, observations);
    }

    private static CiEpisode episode(String status)
    {
        return new CiEpisode(
                "episode-1", "stage-1", "task-1", 1, 1,
                "binding-1", "evaluation-1", "head-1", "base-1",
                "FLAKY", status, 1, 2, 1, 3, 0, 3,
                1, 4, null, null, null, NOW,
                "STOPPED".equals(status) ? NOW : null, null);
    }
}
