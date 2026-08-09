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

import com.bytequay.app.domain.BacklogItem;
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
import org.springframework.jdbc.UncategorizedSQLException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bytequay-test-backlog-cas-${random.uuid}.db?journal_mode=WAL&foreign_keys=ON&busy_timeout=30000",
        "spring.datasource.hikari.maximum-pool-size=2"
})
class TestSqliteBacklogStoreConcurrency
{
    @Autowired
    private SqliteBacklogStore backlogStore;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;

    @Test
    void onlyOneConcurrentResolutionCanClaimABacklogItem()
            throws Exception
    {
        Instant startedAt = Instant.parse("2026-07-24T09:00:00Z");
        Thread thread = thread(startedAt);
        threadStore.saveThread(thread);
        String firstTaskId = saveTask(thread.id(), 1, startedAt);
        String secondTaskId = saveTask(thread.id(), 2, startedAt);
        String backlogId = UUID.randomUUID().toString();
        BacklogItem inProgress = BacklogItem.create(
                backlogId,
                thread.id(),
                "ws-default",
                "Resolve atomically",
                "",
                List.of(),
                BacklogItem.PRIORITY_MEDIUM,
                BacklogItem.SOURCE_MANUAL,
                BacklogItem.CREATED_BY_USER,
                startedAt.minusSeconds(60),
                List.of()).markInProgress(startedAt);
        backlogStore.save(inProgress);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Instant firstResolvedAt = startedAt.plusSeconds(1);
        Instant secondResolvedAt = startedAt.plusSeconds(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = pool.submit(() -> {
                ready.countDown();
                go.await();
                return backlogStore.resolveIfInProgressAndUnlinked(
                        backlogId, firstTaskId, firstResolvedAt);
            });
            Future<Boolean> second = pool.submit(() -> {
                ready.countDown();
                go.await();
                return backlogStore.resolveIfInProgressAndUnlinked(
                        backlogId, secondTaskId, secondResolvedAt);
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            boolean firstWon = first.get(10, TimeUnit.SECONDS);
            boolean secondWon = second.get(10, TimeUnit.SECONDS);
            assertThat(List.of(firstWon, secondWon))
                    .containsExactlyInAnyOrder(true, false);

            String winningTaskId = firstWon ? firstTaskId : secondTaskId;
            String losingTaskId = firstWon ? secondTaskId : firstTaskId;
            Instant winningResolvedAt = firstWon ? firstResolvedAt : secondResolvedAt;
            BacklogItem resolved = backlogStore.findById(backlogId).orElseThrow();
            assertThat(resolved.status()).isEqualTo(BacklogItem.STATUS_RESOLVED);
            assertThat(resolved.linkedTaskId()).isEqualTo(winningTaskId);
            assertThat(resolved.resolvedAt()).isEqualTo(winningResolvedAt);

            assertThat(backlogStore.resolveIfInProgressAndUnlinked(
                    backlogId, losingTaskId, startedAt.plusSeconds(3))).isFalse();
            BacklogItem unchanged = backlogStore.findById(backlogId).orElseThrow();
            assertThat(unchanged.linkedTaskId()).isEqualTo(winningTaskId);
            assertThat(unchanged.resolvedAt()).isEqualTo(winningResolvedAt);

            String otherBacklogId = UUID.randomUUID().toString();
            backlogStore.save(BacklogItem.create(
                            otherBacklogId,
                            thread.id(),
                            "ws-default",
                            "Do not duplicate a task link",
                            "",
                            List.of(),
                            BacklogItem.PRIORITY_MEDIUM,
                            BacklogItem.SOURCE_MANUAL,
                            BacklogItem.CREATED_BY_USER,
                            startedAt,
                            List.of())
                    .markInProgress(startedAt));
            assertThatThrownBy(() -> backlogStore.resolveIfInProgressAndUnlinked(
                    otherBacklogId, winningTaskId, startedAt.plusSeconds(4)))
                    .isInstanceOf(UncategorizedSQLException.class)
                    .hasMessageContaining(
                            "UNIQUE constraint failed: backlog_item.linked_task_id");
            BacklogItem unclaimed = backlogStore.findById(otherBacklogId).orElseThrow();
            assertThat(unclaimed.status()).isEqualTo(BacklogItem.STATUS_IN_PROGRESS);
            assertThat(unclaimed.linkedTaskId()).isNull();

            assertThatThrownBy(() -> backlogStore.save(inProgress.markCreated()))
                    .hasStackTraceContaining("resolved_backlog_link_is_immutable");
            BacklogItem afterStaleSave = backlogStore.findById(backlogId).orElseThrow();
            assertThat(afterStaleSave.status()).isEqualTo(BacklogItem.STATUS_RESOLVED);
            assertThat(afterStaleSave.linkedTaskId()).isEqualTo(winningTaskId);
            assertThat(afterStaleSave.resolvedAt()).isEqualTo(winningResolvedAt);
        }
    }

    private String saveTask(String threadId, long seq, Instant createdAt)
    {
        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId,
                threadId,
                seq,
                TaskStatus.RUNNING,
                "codex/backlog-race-" + seq,
                null,
                "main",
                "/tmp",
                null,
                null,
                null,
                null,
                null,
                "DEVELOP",
                null,
                null,
                0L,
                0L,
                0L,
                null,
                createdAt,
                null,
                null,
                null,
                null,
                null));
        return taskId;
    }

    private static Thread thread(Instant createdAt)
    {
        return new Thread(
                UUID.randomUUID().toString(),
                ThreadKind.CLI_AGENT,
                "codex",
                null,
                "Backlog resolution race",
                ThreadStatus.RUNNING,
                "gpt-5",
                0L,
                0L,
                0L,
                createdAt,
                createdAt,
                null,
                null,
                ThreadFlow.BUILD,
                "ws-default",
                null,
                null);
    }
}
