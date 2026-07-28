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

import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.bytequay.app.service.WorkspaceBehaviorService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTaskIdleArchiver
{
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    private final TaskStore taskStore = mock(TaskStore.class);
    private final ThreadTurnStore turnStore = mock(ThreadTurnStore.class);
    private final ReviewRoundStore roundStore = mock(ReviewRoundStore.class);
    private final ValidationPassStore validationStore = mock(ValidationPassStore.class);
    private final WorkspaceBehaviorService behavior = mock(
            WorkspaceBehaviorService.class, Mockito.RETURNS_DEEP_STUBS);
    private final TaskPhaseMachine machine = mock(TaskPhaseMachine.class);
    private final TaskIdleArchiver archiver = new TaskIdleArchiver(
            taskStore, turnStore, roundStore, validationStore, behavior, machine);

    @Test
    void archivesOnlyDormantIdleTasksPastTheCadence()
    {
        when(behavior.get().archiveIdleAfter()).thenReturn("1d");
        Task stale = task("stale", NOW.minusSeconds(200_000));
        Task fresh = task("fresh", NOW.minusSeconds(60));
        when(taskStore.listByStatuses(eq(Set.of(TaskStatus.IDLE)), eq(200)))
                .thenReturn(List.of(stale, fresh));
        when(roundStore.findLiveByTask(anyString())).thenReturn(Optional.empty());

        archiver.sweepOnce(NOW);

        verify(machine).archiveIdle("stale");
        verify(machine, never()).archiveIdle("fresh");
    }

    @Test
    void liveRoundOrOpenClaimBlocksArchiving()
    {
        when(behavior.get().archiveIdleAfter()).thenReturn("1h");
        Task stale = task("stale", NOW.minusSeconds(200_000));
        when(taskStore.listByStatuses(eq(Set.of(TaskStatus.IDLE)), eq(200)))
                .thenReturn(List.of(stale));
        when(roundStore.findLiveByTask("stale")).thenReturn(Optional.of(mock(ReviewRound.class)));

        archiver.sweepOnce(NOW);

        verify(machine, never()).archiveIdle(any());
    }

    @Test
    void v2TaskIsOwnedByTheV2MaintenancePath()
    {
        when(behavior.get().archiveIdleAfter()).thenReturn("1h");
        Task task = task("v2", NOW.minusSeconds(200_000));
        when(taskStore.listByStatuses(eq(Set.of(TaskStatus.IDLE)), eq(200)))
                .thenReturn(List.of(task));
        when(taskStore.isV2Task("v2")).thenReturn(true);

        archiver.sweepOnce(NOW);

        verify(machine, never()).archiveIdle(any());
        verify(roundStore, never()).findLiveByTask(any());
    }

    @Test
    void neverCadenceSkipsTheSweep()
    {
        when(behavior.get().archiveIdleAfter()).thenReturn("never");

        archiver.sweepOnce(NOW);

        verify(taskStore, never()).listByStatuses(any(), eq(200));
    }

    private static Task task(String id, Instant createdAt)
    {
        return new Task(
                id, "thread-1", 1L, TaskStatus.IDLE,
                "dev/x", "/tmp/wt", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP",
                null, null, 0L, 0L, 0L, null,
                createdAt, null, null, null, null, null);
    }
}
