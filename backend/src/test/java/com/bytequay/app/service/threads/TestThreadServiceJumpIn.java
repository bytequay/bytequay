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
import com.bytequay.app.repository.ThreadStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that the retired legacy jump-in route fails before changing any
 * historical state.
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
    @Test
    void jumpInRejectsALegacyTrunkWithoutSideEffects()
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

        assertThatThrownBy(() -> threads.jumpIn(threadId))
                .hasMessageContaining("Historical LEGACY Trunk")
                .hasMessageContaining("read-only");

        assertThat(leases.isHeld(task.worktreePath()))
                .as("fail-closed compatibility must not release the historical lease")
                .isTrue();
        assertThat(reload(parked).status()).isEqualTo(NotificationStatus.UNREAD);
        assertThat(reload(other).status()).isEqualTo(NotificationStatus.UNREAD);
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
                ThreadFlow.BUILD, "ws-default", null, null);
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
                /* agentSessionId */ null,
                Instant.now(),
                /* endedAt */ null, /* errorMessage */ null,
                /* name */ null, /* roleSkill */ null, /* workModel */ null);
    }
}
