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
package com.bytequay.app.repository.sqlite;

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
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Brain threads round-trip their {@code parentTaskId} and {@code BRAIN_AGENT}
 * kind, and the partial unique index enforces one brain thread per task.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestBrainThread
{
    @Autowired
    private ThreadStore threadStore;
    @Autowired
    private TaskStore taskStore;

    @Test
    void brainThreadRoundTripsKindAndParentTask()
    {
        String taskId = seedTask();
        Thread brain = brainThread(taskId);
        threadStore.saveThread(brain);

        Thread loaded = threadStore.findThreadById(brain.id()).orElseThrow();
        assertThat(loaded.kind()).isEqualTo(ThreadKind.BRAIN_AGENT);
        assertThat(loaded.parentTaskId()).isEqualTo(taskId);
    }

    @Test
    void secondBrainThreadForSameTaskViolatesUniqueIndex()
    {
        String taskId = seedTask();
        threadStore.saveThread(brainThread(taskId));

        assertThatThrownBy(() -> threadStore.saveThread(brainThread(taskId)))
                .isInstanceOf(DataAccessException.class);
    }

    private Thread brainThread(String taskId)
    {
        Instant now = Instant.parse("2026-06-20T10:00:00Z");
        return new Thread(
                UUID.randomUUID().toString(), ThreadKind.BRAIN_AGENT, "anthropic",
                null, "Brain", ThreadStatus.IDLE, "claude-haiku-4-5-20251001",
                0L, 0L, 0L, now, now, null, null, ThreadFlow.BUILD, "ws-default",
                null, /* parentReviewPassId */ null,
                1, taskId);
    }

    private String seedTask()
    {
        Instant now = Instant.parse("2026-06-20T09:00:00Z");
        Thread dev = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Dev", ThreadStatus.RUNNING, "claude-sonnet-4-6",
                0L, 0L, 0L, now, now, null, null, ThreadFlow.BUILD, "ws-default", null, null);
        threadStore.saveThread(dev);
        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, dev.id(), 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null));
        return taskId;
    }
}
