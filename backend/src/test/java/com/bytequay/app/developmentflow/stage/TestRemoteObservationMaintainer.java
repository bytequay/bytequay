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

import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationTarget;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ParkedObservation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestRemoteObservationMaintainer
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @Test
    void requestsEveryDueOwnerWithoutDoingRemoteIoItself()
    {
        SqliteRemoteRuntimeStore store = mock(SqliteRemoteRuntimeStore.class);
        RemoteObservationRuntimeCoordinator observations = mock(
                RemoteObservationRuntimeCoordinator.class);
        DispatchTicketControl tickets = mock(DispatchTicketControl.class);
        when(store.findDueObservations(NOW.minusSeconds(30), 10)).thenReturn(List.of(
                new ObservationTarget("task-1", "stage-1", NOW.minusSeconds(60)),
                new ObservationTarget("task-2", "stage-2", NOW.minusSeconds(45))));

        new RemoteObservationMaintainer(
                store, observations, tickets, Duration.ofSeconds(30), 10)
                .maintain(NOW);

        verify(observations).requestObservation("task-1", "stage-1");
        verify(observations).requestObservation("task-2", "stage-2");
    }

    @Test
    void rearmsEveryParkedReadOnlyObservationBeforeRequestingNewOnes()
    {
        SqliteRemoteRuntimeStore store = mock(SqliteRemoteRuntimeStore.class);
        RemoteObservationRuntimeCoordinator observations = mock(
                RemoteObservationRuntimeCoordinator.class);
        DispatchTicketControl tickets = mock(DispatchTicketControl.class);
        when(store.findParkedObservations(NOW.minusSeconds(30), 10)).thenReturn(
                List.of(
                        new ParkedObservation(
                                "ticket-1", "task-1", "stage-1",
                                NOW.minusSeconds(60)),
                        new ParkedObservation(
                                "ticket-2", "task-2", "stage-2",
                                NOW.minusSeconds(45))));

        new RemoteObservationMaintainer(
                store, observations, tickets, Duration.ofSeconds(30), 10)
                .maintain(NOW);

        verify(tickets).resumeDeferred("ticket-1");
        verify(tickets).resumeDeferred("ticket-2");
    }

    @Test
    void oneBadOwnerDoesNotStarveTheRestOfTheBatch()
    {
        SqliteRemoteRuntimeStore store = mock(SqliteRemoteRuntimeStore.class);
        RemoteObservationRuntimeCoordinator observations = mock(
                RemoteObservationRuntimeCoordinator.class);
        DispatchTicketControl tickets = mock(DispatchTicketControl.class);
        when(store.findDueObservations(NOW.minusSeconds(30), 10)).thenReturn(List.of(
                new ObservationTarget("task-1", "stage-1", NOW.minusSeconds(60)),
                new ObservationTarget("task-2", "stage-2", NOW.minusSeconds(45))));
        doThrow(new IllegalStateException("owner gone"))
                .when(observations).requestObservation("task-1", "stage-1");

        assertThatThrownBy(() -> new RemoteObservationMaintainer(
                store, observations, tickets, Duration.ofSeconds(30), 10)
                .maintain(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("owner gone");
        verify(observations).requestObservation("task-2", "stage-2");
    }
}
