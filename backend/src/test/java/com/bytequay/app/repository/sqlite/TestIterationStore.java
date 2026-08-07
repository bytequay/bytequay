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

import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStageIteration;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestIterationStore
{
    @Autowired
    private IterationStore iterationStore;
    @Autowired
    private StageStore stageStore;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;

    @Test
    void roundTripsAndQueriesByTurnAndSummary()
    {
        String taskId = seedTask();
        StageInstance stage = stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null);
        Instant now = Instant.parse("2026-06-20T10:00:00Z");

        assertThat(iterationStore.nextIterationNumber(stage.id())).isEqualTo(1);

        // Unique turn id: the SQLite DB is shared across @SpringBootTest classes and a
        // literal like "turn-1" collides with rows other suites persist, so findByTurnId
        // could match a foreign row. Turn ids are globally unique in production.
        String turnId = "turn-" + UUID.randomUUID();
        UUID iterId = UUID.randomUUID();
        iterationStore.save(TaskStageIteration.opened(
                iterId, stage.id(), taskId, turnId, 1, "red_ci", now));

        assertThat(iterationStore.findById(iterId)).isPresent();
        assertThat(iterationStore.findByTurnId(turnId).map(TaskStageIteration::id)).hasValue(iterId);
        assertThat(iterationStore.nextIterationNumber(stage.id())).isEqualTo(2);
        assertThat(iterationStore.findRecentSummaries(taskId, 5)).isEmpty();

        // Close it and record a summary.
        TaskStageIteration ended = iterationStore.findById(iterId).orElseThrow()
                .withEnded(now.plusSeconds(60), "push_completed")
                .withSummary("bumped retry default 3->5", now.plusSeconds(70));
        iterationStore.save(ended);

        TaskStageIteration reloaded = iterationStore.findById(iterId).orElseThrow();
        assertThat(reloaded.endedReason()).isEqualTo("push_completed");
        assertThat(reloaded.summaryText()).isEqualTo("bumped retry default 3->5");
        assertThat(iterationStore.findRecentSummaries(taskId, 5))
                .extracting(TaskStageIteration::id)
                .containsExactly(iterId);
    }

    private String seedTask()
    {
        Instant now = Instant.parse("2026-06-20T09:00:00Z");
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Iter store test", ThreadStatus.RUNNING, "claude-sonnet-4.6",
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
