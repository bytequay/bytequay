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
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadCheckpointStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.workspaces.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SpringBootTest coverage for {@link ThreadService#jumpIn}. The path
 * threads through several services (registry, lease service,
 * notification service) so a real-wired test catches the integration
 * gotchas. We seed the data plane directly through the stores
 * (no live agent session is required — jumpIn must tolerate that case).
 */
@SpringBootTest
class TestThreadServiceJumpIn
{
    @Autowired
    private ThreadService threads;
    @Autowired
    private ThreadStore threadStore;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private NotificationService notifications;
    @Autowired
    private WorktreeLeaseService leases;
    @Autowired
    @SuppressWarnings("unused")
    private ThreadCheckpointStore checkpoints;
    @Autowired
    @SuppressWarnings("unused")
    private WorkspaceService workspaces;

    @Test
    void jumpInReleasesWorktreeLeaseAndMarksParkedNotificationsRead()
    {
        String threadId = newThread(ThreadStatus.IDLE);
        Task task = newTask(threadId);
        taskStore.saveTask(task);
        // Pretend a headless agent claimed the lease earlier this tick.
        leases.tryAcquire(task.worktreePath(), task.id(), ThreadKind.CLI_AGENT, /* pid */ null);
        Notification parked = notifications.notifyNeedsAttention(threadId, task.id(),
                "{\"reason\":\"CI failing\"}");
        Notification other = notifications.notifyAwaitingReview(threadId, task.id(),
                "{\"summary\":\"ready for review\"}");

        Thread updated = threads.jumpIn(threadId);

        assertThat(updated.id()).isEqualTo(threadId);
        assertThat(leases.isHeld(task.worktreePath()))
                .as("worktree lease must be released so the user's next turn can claim it")
                .isFalse();
        assertThat(reload(parked).status()).isEqualTo(NotificationStatus.READ);
        assertThat(reload(other).status()).isEqualTo(NotificationStatus.READ);
    }

    @Test
    void jumpInLeavesUnrelatedKindsAlone()
    {
        String threadId = newThread(ThreadStatus.IDLE);
        // Notifications with kinds other than the two parked kinds
        // must stay UNREAD — jumping in to one thread shouldn't quiet
        // an AUTO_FIX_DONE row that's pointing at a different concern.
        Notification autoFixDone = notifications.notifyAutoFixDone(threadId, /* taskId */ null,
                "{\"summary\":\"branch updated\"}");

        threads.jumpIn(threadId);

        assertThat(reload(autoFixDone).status()).isEqualTo(NotificationStatus.UNREAD);
    }

    @Test
    void jumpInIsIdempotentWhenNoLeaseOrNotificationsExist()
    {
        String threadId = newThread(ThreadStatus.IDLE);
        // No active task, no lease, no notifications — jumpIn should
        // still return the thread cleanly. (The user navigated to a
        // thread that already cleaned itself up.)

        Thread result = threads.jumpIn(threadId);

        assertThat(result.id()).isEqualTo(threadId);
    }

    private Notification reload(Notification n)
    {
        return notifications.listForThread(n.threadId()).stream()
                .filter(row -> row.id().equals(n.id()))
                .findFirst()
                .orElseThrow();
    }

    private String newThread(ThreadStatus status)
    {
        Instant now = Instant.now();
        Thread t = new Thread(
                UUID.randomUUID().toString(),
                ThreadKind.CLI_AGENT,
                "claude-code",
                /* agentSessionId */ null,
                "JumpIn test thread",
                status,
                "claude-sonnet-4.6",
                0L, 0L, 0L,
                now, now, null, null,
                ThreadFlow.BUILD, null);
        threadStore.saveThread(t);
        return t.id();
    }

    private Task newTask(String threadId)
    {
        // Bridge teardown dropped saveThread's auto-create branch;
        // the explicit task is the only one for this thread, so seq=1.
        return new Task(
                UUID.randomUUID().toString(),
                threadId,
                /* seq */ 1L,
                TaskStatus.IDLE,
                /* branchName */ "auto-fix/jump-in",
                /* worktreePath */ "/tmp/jump-in-repo/.worktrees/jump-in-task",
                /* baseBranch */ "main",
                /* workingDir */ "/tmp/jump-in-repo",
                /* processPid */ null, /* logPath */ null,
                /* prNumber */ null, /* prState */ null, /* ciState */ null,
                /* taskType */ "DEVELOP",
                /* linkedPrNumber */ null, /* linkedIssueNumber */ null,
                /* costUsdMilli */ 0L, /* tokensIn */ 0L, /* tokensOut */ 0L,
                /* firstMsgSeq */ null, /* lastMsgSeq */ null,
                Instant.now(),
                /* endedAt */ null, /* errorMessage */ null);
    }

    @SuppressWarnings("unused")
    private static List<String> tag()
    {
        return List.of("jump-in");
    }
}
