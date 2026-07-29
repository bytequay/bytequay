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
 * Verifies that legacy live-session routes are retained only as fail-closed
 * compatibility ports after the V2 hard cutover.
 */
@SpringBootTest
class TestThreadServiceTrunkSession
{
    @Autowired
    private ThreadService threads;
    @Autowired
    private ThreadStore threadStore;
    @Autowired
    private TaskStore taskStore;

    @Test
    void subscribeRejectsALegacyTrunk()
    {
        String threadId = newTrunkThread();

        assertThatThrownBy(() -> threads.subscribeTrunk(threadId, event -> {}))
                .hasMessageContaining("Historical LEGACY Trunk")
                .hasMessageContaining("read-only");
    }

    @Test
    void resumeRejectsALegacyTrunkWithoutChangingItsTask()
    {
        String threadId = UUID.randomUUID().toString();
        String taskId = threadId + ".task";
        Instant now = Instant.now();
        threadStore.saveThread(new Thread(
                threadId,
                ThreadKind.CLI_AGENT,
                /* provider */ "claude-code",
                /* agentSessionId */ "trunk-session",
                "Errored task resume fixture",
                ThreadStatus.ERRORED,
                /* model */ "test",
                0L, 0L, 0L,
                now, now, now, "limit hit",
                ThreadFlow.BUILD,
                /* workspaceId */ "ws-default",
                /* workModel */ null,
                /* activeTask */ null));
        taskStore.saveTask(new Task(
                taskId, threadId, 1L, TaskStatus.ERRORED,
                "dev/resume-latest-task", /* worktreePath */ null,
                /* baseBranch */ "main", System.getProperty("java.io.tmpdir"),
                /* processPid */ null, /* logPath */ null,
                /* prNumber */ null, /* prState */ null, /* ciState */ null,
                "DEVELOP", /* linkedPrNumber */ null, /* linkedIssueNumber */ null,
                0L, 0L, 0L, "task-session",
                now, now, "limit hit",
                /* name */ null, /* roleSkill */ null, /* workModel */ null));

        assertThatThrownBy(() -> threads.resume(threadId))
                .hasMessageContaining("Historical LEGACY Trunk")
                .hasMessageContaining("read-only");

        Thread retainedThread = threadStore.findThreadById(threadId).orElseThrow();
        Task untouchedTask = taskStore.findTaskById(taskId).orElseThrow();
        assertThat(retainedThread.status()).isEqualTo(ThreadStatus.ERRORED);
        assertThat(retainedThread.endedAt()).isNotNull();
        assertThat(retainedThread.errorMessage()).isEqualTo("limit hit");
        assertThat(untouchedTask.status()).isEqualTo(TaskStatus.ERRORED);
        assertThat(untouchedTask.endedAt()).isNotNull();
        assertThat(untouchedTask.errorMessage()).isEqualTo("limit hit");
    }

    private String newTrunkThread()
    {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Thread thread = new Thread(
                id,
                ThreadKind.CLI_AGENT,
                /* provider */ "claude-code",
                /* agentSessionId */ null,
                "Trunk session fixture",
                ThreadStatus.IDLE,
                /* model */ "test",
                0L, 0L, 0L,
                now, now, null, null,
                ThreadFlow.BUILD,
                /* workspaceId */ "ws-default",
                /* workModel */ null,
                /* activeTask */ null);
        threadStore.saveThread(thread);
        return id;
    }
}
