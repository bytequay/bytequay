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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import javax.sql.DataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestTaskEpochReadPort
{
    @Autowired
    private TaskStore tasks;
    @Autowired
    private ThreadStore threads;
    @Autowired
    private DataSource dataSource;

    @Test
    void readsTheDatabaseOwnedEpochWithoutAFullRowSaveClobberingIt()
    {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        String trunkId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        threads.saveThread(new Thread(
                trunkId, ThreadKind.LOGIC_LOOP, "openai", null, "Trunk",
                ThreadStatus.PENDING, "test-model", 0L, 0L, 0L, now, now,
                null, null, ThreadFlow.BUILD, "ws-default", null));
        Task task = new Task(
                taskId, trunkId, 1L, TaskStatus.IDLE, "feature/epoch", null,
                "main", "/tmp", null, null, null, null, null, "DEVELOP",
                null, null, 0L, 0L, 0L, null, now, null, null, null, null, null);
        tasks.saveTask(task);
        new JdbcTemplate(dataSource).update(
                "UPDATE tasks SET epoch = 7 WHERE id = ?", taskId);

        tasks.saveTask(task.withStatus(TaskStatus.RUNNING));

        assertThat(tasks.findTaskEpoch(taskId)).hasValue(7L);
    }
}
