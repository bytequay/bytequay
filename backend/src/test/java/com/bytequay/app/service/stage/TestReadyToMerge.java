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
package com.bytequay.app.service.stage;

import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestDetail.CiStatus;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.threads.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The ready-to-merge notification: fires once on the ready state via the
 * atomic sentinel CAS (so two monitor sweeps don't double-notify) and
 * auto-resets when a condition breaks.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestReadyToMerge
{
    @Autowired
    private ReadyToMergeService readyToMerge;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private StageStore stageStore;
    @Autowired
    private ThreadStore threadStore;
    @Autowired
    private NotificationService notifications;

    @Test
    void firesOnceOnReadyAndDedupsTheSecondSweep()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        StageInstance stage = stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null);
        Task task = taskStore.findTaskById(taskId).orElseThrow();

        readyToMerge.evaluate(task, readyDetail());
        assertThat(taskStore.mergeNotificationSentAt(taskId)).isPresent();
        assertThat(readyToMergeNotifications(threadId)).isEqualTo(1);
        assertThat(stageStore.findEventsByStage(stage.id()))
                .anyMatch(e -> e.eventType() == StageEventType.NOTIFY_FIRED);

        // A second sweep over the same ready state must not re-notify.
        readyToMerge.evaluate(task, readyDetail());
        assertThat(readyToMergeNotifications(threadId)).isEqualTo(1);
    }

    @Test
    void autoResetsWhenAConditionBreaks()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        Task task = taskStore.findTaskById(taskId).orElseThrow();

        readyToMerge.evaluate(task, readyDetail());
        assertThat(taskStore.mergeNotificationSentAt(taskId)).isPresent();

        // CI goes red — the armed sentinel clears.
        readyToMerge.evaluate(task, detail(CiStatus.FAILING, 1, 0, 0, false, "open"));
        assertThat(taskStore.mergeNotificationSentAt(taskId)).isEmpty();
    }

    /** The ready-to-merge gate is now a parked {@code merge_pr} proposal —
     *  an AWAITING_REVIEW notification the user one-click approves to merge —
     *  rather than the old informational READY_TO_MERGE notice. */
    private long readyToMergeNotifications(String threadId)
    {
        return notifications.listForThread(threadId).stream()
                .filter(n -> n.kind() == NotificationKind.AWAITING_REVIEW)
                .filter(n -> n.payloadJson().contains("\"action\":\"merge_pr\""))
                .count();
    }

    private static PullRequestDetail readyDetail()
    {
        return detail(CiStatus.PASSING, 1, 0, 0, false, "open");
    }

    private static PullRequestDetail detail(
            CiStatus ci, int approvals, int changesRequested, int pending, boolean merged, String state)
    {
        PullRequestDetail detail = mock(PullRequestDetail.class);
        when(detail.ciStatus()).thenReturn(ci);
        when(detail.approvalCount()).thenReturn(approvals);
        when(detail.changesRequestedCount()).thenReturn(changesRequested);
        when(detail.pendingReviewerCount()).thenReturn(pending);
        when(detail.merged()).thenReturn(merged);
        when(detail.state()).thenReturn(state);
        lenient().when(detail.repo()).thenReturn("octo/repo");
        lenient().when(detail.number()).thenReturn(7);
        return detail;
    }

    private String seedThread()
    {
        Instant now = Instant.parse("2026-06-20T09:00:00Z");
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Ready test", ThreadStatus.RUNNING, "claude-sonnet-4.6",
                0L, 0L, 0L, now, now, null, null, ThreadFlow.BUILD, "ws-default", null, null);
        threadStore.saveThread(thread);
        return thread.id();
    }

    private String seedTask(String threadId)
    {
        Instant now = Instant.parse("2026-06-20T09:00:00Z");
        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, threadId, 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null));
        return taskId;
    }
}
