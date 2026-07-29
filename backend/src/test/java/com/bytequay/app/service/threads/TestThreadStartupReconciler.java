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

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.ThreadStore;
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
class TestThreadStartupReconciler
{
    @Autowired
    private ThreadStore store;

    @Test
    void flipsOrphanedRunningTasksToIdle()
    {
        Thread running = newTask(ThreadStatus.RUNNING);
        Thread pending = newTask(ThreadStatus.PENDING);
        Thread completed = newTask(ThreadStatus.COMPLETED);
        store.saveThread(running);
        store.saveThread(pending);
        store.saveThread(completed);

        new ThreadStartupReconciler(store).reconcileOnStartup();

        // RUNNING row reconciled.
        Thread afterRunning = store.findThreadById(running.id()).orElseThrow();
        assertThat(afterRunning.status()).isEqualTo(ThreadStatus.IDLE);
        // Untouched buckets remain themselves.
        assertThat(store.findThreadById(pending.id()).orElseThrow().status())
                .isEqualTo(ThreadStatus.PENDING);
        assertThat(store.findThreadById(completed.id()).orElseThrow().status())
                .isEqualTo(ThreadStatus.COMPLETED);
    }

    @Test
    void pagesThroughAllOrphanedRunningTasks()
    {
        for (int i = 0; i < 1_001; i++) {
            store.saveThread(newTask(ThreadStatus.RUNNING));
        }

        new ThreadStartupReconciler(store).reconcileOnStartup();

        assertThat(store.listTasksByStatus(ThreadStatus.RUNNING, 1)).isEmpty();
    }

    private static Thread newTask(ThreadStatus status)
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        return new Thread(
                UUID.randomUUID().toString(),
                ThreadKind.CLI_AGENT,
                "claude-code",
                /* agentSessionId */ "sess-" + UUID.randomUUID(),
                "Reconciler test thread",
                status,
                "claude-sonnet-4.6",
                /* costUsdMilli */ 0L,
                /* tokensIn */ 0L,
                /* tokensOut */ 0L,
                now,
                now,
                /* endedAt */ status == ThreadStatus.COMPLETED ? now : null,
                /* errorMessage */ null,
                ThreadFlow.BUILD,
                "ws-default",
                /* workModel */ null,
                /* activeTask */ null);
    }
}
