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

import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.repository.NotificationStore;
import com.bytequay.app.repository.TaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAutoApproveGateListener
{
    private static final String THREAD = "ws.t1";
    private static final String TASK = "ws.t1.k1";

    private final TaskStore taskStore = mock(TaskStore.class);
    private final PublishService publishService = mock(PublishService.class);
    private final NotificationStore store = mock(NotificationStore.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final AutoApproveGateListener listener =
            new AutoApproveGateListener(taskStore, publishService, store, notifications, new ObjectMapper());

    private Notification gate(String id, String taskId, NotificationStatus status, String action, Instant createdAt)
    {
        return new Notification(id, NotificationKind.AWAITING_REVIEW, THREAD, taskId, status,
                "{\"action\":\"" + action + "\"}", createdAt, null);
    }

    @Test
    void enablingSweepsParkedNonMergeGatesButLeavesTheMerge()
    {
        when(taskStore.isAutoApprove(TASK)).thenReturn(true);
        when(store.listForThread(eq(THREAD), anyInt())).thenReturn(List.of(
                gate("n1", TASK, NotificationStatus.UNREAD, "ship_task", Instant.EPOCH),
                gate("n2", TASK, NotificationStatus.UNREAD, "merge_pr", Instant.EPOCH),
                gate("n3", TASK, NotificationStatus.RESOLVED, "mark_ready", Instant.EPOCH),
                gate("n4", "ws.t1.k2", NotificationStatus.UNREAD, "ship_task", Instant.EPOCH)));

        listener.onAutoApproveEnabled(new AutoApproveEnabledEvent(THREAD, TASK));

        // Only the task's own UNREAD non-merge gate is approved.
        verify(publishService).approve(eq("n1"), isNull(), eq("ship_task"));
        verify(publishService, never()).approve(eq("n2"), isNull(), eq("merge_pr"));
        verify(publishService, never()).approve(eq("n3"), isNull(), eq("mark_ready"));
        verify(publishService, never()).approve(eq("n4"), isNull(), eq("ship_task"));
    }

    @Test
    void aQuickToggleOffBeforeTheSweepIsANoOp()
    {
        when(taskStore.isAutoApprove(TASK)).thenReturn(false);

        listener.onAutoApproveEnabled(new AutoApproveEnabledEvent(THREAD, TASK));

        verify(publishService, never()).approve(anyString(), any(), anyString());
    }

    @Test
    void backstopReapprovesStrandedGatesButSkipsMergeAndFreshOnes()
    {
        when(taskStore.isAutoApprove(TASK)).thenReturn(true);
        when(taskStore.isAutoApprove("ws.t1.k2")).thenReturn(false);
        when(store.listByStatus(eq(NotificationStatus.UNREAD), anyInt())).thenReturn(List.of(
                gate("n1", TASK, NotificationStatus.UNREAD, "ship_task", Instant.EPOCH),        // stranded → approve
                gate("n2", TASK, NotificationStatus.UNREAD, "merge_pr", Instant.EPOCH),         // merge → skip
                gate("nf", TASK, NotificationStatus.UNREAD, "ship_task", Instant.now()),        // too fresh → skip
                gate("n5", "ws.t1.k2", NotificationStatus.UNREAD, "ship_task", Instant.EPOCH))); // not auto-approve → skip

        listener.reconcileStrandedGates();

        verify(publishService).approve(eq("n1"), isNull(), eq("ship_task"));
        verify(publishService, never()).approve(eq("n2"), any(), any());
        verify(publishService, never()).approve(eq("nf"), any(), any());
        verify(publishService, never()).approve(eq("n5"), any(), any());
    }

    @Test
    void backstopEscalatesOnceAfterTheAttemptCapThenStops()
    {
        when(taskStore.isAutoApprove(TASK)).thenReturn(true);
        when(store.listByStatus(eq(NotificationStatus.UNREAD), anyInt())).thenReturn(List.of(
                gate("n1", TASK, NotificationStatus.UNREAD, "ship_task", Instant.EPOCH)));
        when(publishService.approve(eq("n1"), isNull(), eq("ship_task")))
                .thenThrow(new RuntimeException("push failed"));

        listener.reconcileStrandedGates();   // attempt 1
        listener.reconcileStrandedGates();   // attempt 2
        listener.reconcileStrandedGates();   // attempt 3 → escalate

        verify(publishService, times(3)).approve(eq("n1"), isNull(), eq("ship_task"));
        verify(notifications, times(1)).notifyNeedsAttention(eq(THREAD), eq(TASK), anyString());

        // Capped: no further approve attempts or escalations.
        listener.reconcileStrandedGates();
        verify(publishService, times(3)).approve(eq("n1"), isNull(), eq("ship_task"));
        verify(notifications, times(1)).notifyNeedsAttention(eq(THREAD), eq(TASK), anyString());
    }
}
