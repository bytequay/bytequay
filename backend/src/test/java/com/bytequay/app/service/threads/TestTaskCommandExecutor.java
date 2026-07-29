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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The command boundary's guarantees: no ambient transaction, no nested
 * command, {@code requireCurrent} only inside a command, and — the point
 * of the class — the transaction commits while the task stripe is still
 * held, so the next command on the same task observes committed state.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestTaskCommandExecutor
{
    @Autowired
    private TaskCommandExecutor executor;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void rejectsAmbientTransaction()
    {
        String taskId = seedTask();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertThatThrownBy(() -> executor.execute(taskId, () -> null))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("ambient transaction"));
    }

    @Test
    void rejectsNestedCommand()
    {
        String taskId = seedTask();
        executor.executeVoid(taskId, () ->
                assertThatThrownBy(() -> executor.execute(taskId, () -> null))
                        .isInstanceOf(IllegalStateException.class));
    }

    @Test
    void requireCurrentOnlyInsideTheMatchingCommand()
    {
        String taskId = seedTask();
        assertThatThrownBy(() -> TaskCommandExecutor.requireCurrent(taskId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active task command");

        executor.executeVoid(taskId, () -> {
            TaskCommandExecutor.requireCurrent(taskId);
            assertThatThrownBy(() -> TaskCommandExecutor.requireCurrent("other-task"))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void afterCommitDispatchFailsClosedInsteadOfStartingAnUnownedWorker()
    {
        AtomicReference<RuntimeException> failure = new AtomicReference<>();

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization()
                        {
                            @Override
                            public void afterCompletion(int completionStatus)
                            {
                                try {
                                    TaskCommandExecutor.dispatchAfterCommit(() -> {});
                                }
                                catch (RuntimeException e) {
                                    failure.set(e);
                                }
                            }
                        }));

        assertThat(failure.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DispatchTicket");
    }

    @Test
    void commitHappensInsideTheStripeSoTheNextCommandReadsCommittedState()
            throws Exception
    {
        String taskId = seedTask();
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch aEntered = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> executor.executeVoid(taskId, () -> {
                events.add("a-work-start");
                assertThat(taskStore.updateStatusIf(taskId, TaskStatus.RUNNING, TaskStatus.PAUSED))
                        .isTrue();
                aEntered.countDown();
                try {
                    java.lang.Thread.sleep(200);
                }
                catch (InterruptedException e) {
                    java.lang.Thread.currentThread().interrupt();
                }
                events.add("a-work-end");
            }));
            Future<TaskStatus> b = pool.submit(() -> {
                aEntered.await();
                events.add("b-requested");
                return executor.execute(taskId, () -> {
                    events.add("b-work-start");
                    return taskStore.findTaskById(taskId).orElseThrow().status();
                });
            });
            a.get(10, TimeUnit.SECONDS);
            // B blocked on the stripe until A's command (including its
            // commit) finished, and then read the committed status.
            assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo(TaskStatus.PAUSED);
            assertThat(events.indexOf("b-work-start")).isGreaterThan(events.indexOf("a-work-end"));
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test
    void siblingTasksDoNotShareACommandStripe()
            throws Exception
    {
        TaskCommandExecutor isolatedExecutor =
                new TaskCommandExecutor(new TestTransactionManager());
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch bEntered = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> isolatedExecutor.executeVoid("task-a", () -> {
                aEntered.countDown();
                try {
                    assertThat(releaseA.await(10, TimeUnit.SECONDS)).isTrue();
                }
                catch (InterruptedException e) {
                    java.lang.Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }));
            Future<?> b = pool.submit(() -> {
                assertThat(aEntered.await(10, TimeUnit.SECONDS)).isTrue();
                isolatedExecutor.executeVoid("task-b", bEntered::countDown);
                return null;
            });

            assertThat(bEntered.await(10, TimeUnit.SECONDS)).isTrue();
            releaseA.countDown();
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        }
        finally {
            releaseA.countDown();
            pool.shutdownNow();
        }
    }

    private String seedTask()
    {
        Instant now = Instant.parse("2026-07-25T09:00:00Z");
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Command executor test", ThreadStatus.RUNNING, "claude-sonnet-4.6",
                0L, 0L, 0L, now, now, null, null, ThreadFlow.BUILD, "ws-default", null, null);
        threadStore.saveThread(thread);

        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null));
        return taskId;
    }

    private static final class TestTransactionManager
            extends AbstractPlatformTransactionManager
    {
        @Override
        protected Object doGetTransaction()
        {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
