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

import com.bytequay.app.domain.StageEventType;
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
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the Task-level write mutex against the real schema: the
 * atomic compare-and-set lets only one stage win, records the audit
 * events, and the turn-finished safety release frees a held lock.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestTaskWriteMutex
{
    @Autowired
    private TaskWriteMutex mutex;
    @Autowired
    private StageStore stageStore;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;
    @Autowired
    private ApplicationEventPublisher events;

    @Test
    void atomicAcquireLetsOnlyOneStageWin()
    {
        String taskId = seedTask();
        UUID stageA = stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null).id();
        UUID stageB = stageStore.openStage(taskId, StageType.REVIEW_MONITOR_STAGE, null).id();

        assertThat(mutex.tryAcquire(taskId, stageA)).isTrue();
        assertThat(taskStore.writeMutexHolder(taskId)).hasValue(stageA.toString());
        assertThat(stageStore.findEventsByStage(stageA))
                .anyMatch(e -> e.eventType() == StageEventType.MUTEX_ACQUIRED);

        // The second stage can't acquire while the first holds it.
        assertThat(mutex.tryAcquire(taskId, stageB)).isFalse();
        assertThat(taskStore.writeMutexHolder(taskId)).hasValue(stageA.toString());
        assertThat(stageStore.findEventsByStage(stageB))
                .anySatisfy(e -> {
                    assertThat(e.eventType()).isEqualTo(StageEventType.MUTEX_SKIPPED);
                    assertThat(e.payloadJson()).contains(stageA.toString());
                });

        // Releasing frees the lock; the loser can now win.
        mutex.release(taskId, stageA);
        assertThat(taskStore.writeMutexHolder(taskId)).isEmpty();
        assertThat(mutex.tryAcquire(taskId, stageB)).isTrue();
        assertThat(taskStore.writeMutexHolder(taskId)).hasValue(stageB.toString());
    }

    @Test
    void turnFinishedEventReleasesTheLock()
    {
        String taskId = seedTask();
        UUID stageA = stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null).id();
        assertThat(mutex.tryAcquire(taskId, stageA)).isTrue();

        events.publishEvent(new TaskTurnFinishedEvent(taskId, "turn-1", false));

        assertThat(taskStore.writeMutexHolder(taskId)).isEmpty();
    }

    private String seedTask()
    {
        Instant now = Instant.parse("2026-06-20T09:00:00Z");
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Mutex test", ThreadStatus.RUNNING, "claude-sonnet-4.6",
                0L, 0L, 0L, now, now, null, null, ThreadFlow.BUILD, "ws-default", null, null);
        threadStore.saveThread(thread);

        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null));
        return taskId;
    }
}
