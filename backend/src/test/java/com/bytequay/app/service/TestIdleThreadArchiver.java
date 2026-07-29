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
package com.bytequay.app.service;

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.WorkspaceBehaviorService.Settings;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestIdleThreadArchiver
{
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final WorkspaceBehaviorService behavior = mock(WorkspaceBehaviorService.class);
    private final IdleThreadArchiver archiver = new IdleThreadArchiver(threadStore, behavior);

    @Test
    void neverCadenceSkipsTheSweepEntirely()
    {
        when(behavior.get()).thenReturn(settings("never"));
        archiver.sweepOnce(Instant.now());
        verify(threadStore, never()).listTasksByStatus(any(), anyInt());
    }

    @Test
    void archivesIdleThreadsOlderThanTheCadence()
    {
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        // 1d cadence — anything older than now - 1d gets archived.
        when(behavior.get()).thenReturn(settings("1d"));

        Thread fresh = thread("fresh", ThreadStatus.IDLE, now.minus(Duration.ofHours(6)));
        Thread stale = thread("stale", ThreadStatus.IDLE, now.minus(Duration.ofDays(3)));
        when(threadStore.listTasksByStatus(eq(ThreadStatus.IDLE), anyInt()))
                .thenReturn(List.of(fresh, stale));

        archiver.sweepOnce(now);

        ArgumentCaptor<Thread> saved = ArgumentCaptor.forClass(Thread.class);
        verify(threadStore).saveThread(saved.capture());
        Thread archivedSave = saved.getValue();
        assertThat(archivedSave.id()).isEqualTo("stale");
        assertThat(archivedSave.status()).isEqualTo(ThreadStatus.ARCHIVED);
        assertThat(archivedSave.endedAt()).isEqualTo(now);
    }

    @Test
    void doesNotTouchRunningOrAwaitingReviewThreadsEvenWhenStale()
    {
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        when(behavior.get()).thenReturn(settings("1h"));
        when(threadStore.listTasksByStatus(eq(ThreadStatus.IDLE), anyInt())).thenReturn(List.of());

        archiver.sweepOnce(now);

        // RUNNING / AWAITING_REVIEW are never queried; they're not in the
        // eligible set. saveThread should not fire.
        verify(threadStore, never()).saveThread(any());
        verify(threadStore, never()).listTasksByStatus(eq(ThreadStatus.RUNNING), anyInt());
        verify(threadStore, never()).listTasksByStatus(eq(ThreadStatus.AWAITING_REVIEW), anyInt());
        verify(threadStore, never()).listTasksByStatus(eq(ThreadStatus.NEEDS_ATTENTION), anyInt());
    }

    @Test
    void leavesV2LifecycleToTheTrunkManager()
    {
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        Thread v2 = thread("v2", ThreadStatus.IDLE, now.minus(Duration.ofDays(3)));
        Thread legacy = thread(
                "legacy", ThreadStatus.IDLE, now.minus(Duration.ofDays(3)));
        when(behavior.get()).thenReturn(settings("1d"));
        when(threadStore.listTasksByStatus(eq(ThreadStatus.IDLE), anyInt()))
                .thenReturn(List.of(v2, legacy));
        when(threadStore.findTurnVersion("v2"))
                .thenReturn(Optional.of("V2"));
        when(threadStore.findTurnVersion("legacy"))
                .thenReturn(Optional.of("LEGACY"));

        archiver.sweepOnce(now);

        ArgumentCaptor<Thread> saved = ArgumentCaptor.forClass(Thread.class);
        verify(threadStore).saveThread(saved.capture());
        assertThat(saved.getValue().id()).isEqualTo("legacy");
    }

    private static Settings settings(String archiveAfter)
    {
        return new Settings(archiveAfter, /* propose */ true,
                /* promote */ false, /* nudge */ true);
    }

    private static Thread thread(String id, ThreadStatus status, Instant updatedAt)
    {
        return new Thread(
                id,
                ThreadKind.LOGIC_LOOP,
                "claude",
                /* agentSessionId */ null,
                "Title",
                status,
                "claude-sonnet-4-6",
                0L, 0L, 0L,
                /* createdAt */ updatedAt,
                updatedAt,
                /* endedAt */ null,
                /* errorMessage */ null,
                ThreadFlow.BUILD,
                "ws-default",
                /* workModel */ null,
                /* activeTask */ null);
    }
}
