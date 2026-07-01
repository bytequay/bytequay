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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAutoApproveGateListener
{
    private static final String THREAD = "ws.t1";
    private static final String TASK = "ws.t1.k1";

    private Notification gate(String id, String taskId, NotificationStatus status, String action)
    {
        return new Notification(id, NotificationKind.AWAITING_REVIEW, THREAD, taskId, status,
                "{\"action\":\"" + action + "\"}", Instant.EPOCH, null);
    }

    @Test
    void enablingSweepsParkedNonMergeGatesButLeavesTheMerge()
    {
        TaskStore taskStore = mock(TaskStore.class);
        PublishService publishService = mock(PublishService.class);
        NotificationStore store = mock(NotificationStore.class);
        when(taskStore.isAutoApprove(TASK)).thenReturn(true);
        when(store.listForThread(eq(THREAD), anyInt())).thenReturn(List.of(
                gate("n1", TASK, NotificationStatus.UNREAD, "ship_task"),
                gate("n2", TASK, NotificationStatus.UNREAD, "merge_pr"),
                gate("n3", TASK, NotificationStatus.RESOLVED, "mark_ready"),
                gate("n4", "ws.t1.k2", NotificationStatus.UNREAD, "ship_task")));

        new AutoApproveGateListener(taskStore, publishService, store, new ObjectMapper())
                .onAutoApproveEnabled(new AutoApproveEnabledEvent(THREAD, TASK));

        // Only the task's own UNREAD non-merge gate is approved.
        verify(publishService).approve(eq("n1"), isNull(), eq("ship_task"));
        verify(publishService, never()).approve(eq("n2"), isNull(), eq("merge_pr"));
        verify(publishService, never()).approve(eq("n3"), isNull(), eq("mark_ready"));
        verify(publishService, never()).approve(eq("n4"), isNull(), eq("ship_task"));
    }

    @Test
    void aQuickToggleOffBeforeTheSweepIsANoOp()
    {
        TaskStore taskStore = mock(TaskStore.class);
        PublishService publishService = mock(PublishService.class);
        NotificationStore store = mock(NotificationStore.class);
        when(taskStore.isAutoApprove(TASK)).thenReturn(false);

        new AutoApproveGateListener(taskStore, publishService, store, new ObjectMapper())
                .onAutoApproveEnabled(new AutoApproveEnabledEvent(THREAD, TASK));

        verify(publishService, never()).approve(anyString(),
                any(), anyString());
    }
}
