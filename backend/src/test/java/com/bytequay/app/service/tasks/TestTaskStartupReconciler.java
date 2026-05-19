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
package com.bytequay.app.service.tasks;

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskKind;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the reconciler against the real Flyway-migrated SQLite
 * store so it covers the same wiring the production startup path uses.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestTaskStartupReconciler
{
    @Autowired
    private TaskStore store;

    @Autowired
    private TaskStartupReconciler reconciler;

    @Test
    void flipsOrphanedRunningTasksToIdle()
    {
        Task running = newTask(TaskStatus.RUNNING);
        Task pending = newTask(TaskStatus.PENDING);
        Task completed = newTask(TaskStatus.COMPLETED);
        store.saveTask(running);
        store.saveTask(pending);
        store.saveTask(completed);

        reconciler.reconcileOnStartup();

        // RUNNING row reconciled.
        Task afterRunning = store.findTaskById(running.id()).orElseThrow();
        assertThat(afterRunning.status()).isEqualTo(TaskStatus.IDLE);
        assertThat(afterRunning.processPid()).isNull();
        // Untouched buckets remain themselves.
        assertThat(store.findTaskById(pending.id()).orElseThrow().status())
                .isEqualTo(TaskStatus.PENDING);
        assertThat(store.findTaskById(completed.id()).orElseThrow().status())
                .isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void pagesThroughAllOrphanedRunningTasks()
    {
        for (int i = 0; i < 1_001; i++) {
            store.saveTask(newTask(TaskStatus.RUNNING));
        }

        reconciler.reconcileOnStartup();

        assertThat(store.listTasksByStatus(TaskStatus.RUNNING, 1)).isEmpty();
    }

    private static Task newTask(TaskStatus status)
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        return new Task(
                UUID.randomUUID().toString(),
                TaskKind.CLI_AGENT,
                "claude-code",
                /* agentSessionId */ "sess-" + UUID.randomUUID(),
                "Reconciler test task",
                status,
                "/tmp/work",
                /* branchName */ null,
                "claude-sonnet-4.6",
                /* costUsdMilli */ 0L,
                /* tokensIn */ 0L,
                /* tokensOut */ 0L,
                /* processPid */ status == TaskStatus.RUNNING ? 12345 : null,
                /* logPath */ null,
                now,
                now,
                /* endedAt */ status == TaskStatus.COMPLETED ? now : null,
                /* errorMessage */ null,
                "{}",
                /* taskType */ "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null);
    }
}
