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
import com.bytequay.app.domain.ThreadCheckpoint;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadCheckpointStore;
import com.bytequay.app.repository.ThreadStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end exercise of {@link SqliteThreadCheckpointStore} against
 * the Flyway-migrated schema. Catches schema/entity drift, the
 * bullet-list JSON serde, and — most importantly — the active-Overall
 * swap, since that's where the "at most one active Overall per thread"
 * invariant is enforced.
 */
@SpringBootTest
class TestSqliteThreadCheckpointStore
{
    @Autowired
    private ThreadStore threads;
    @Autowired
    private TaskStore tasks;
    @Autowired
    private ThreadCheckpointStore checkpoints;

    @Test
    void roundtripsAPerSegmentCheckpoint()
    {
        String threadId = newTask();
        ThreadCheckpoint cp = segment(threadId, /* seq */ 1, /* range */ 1, 6, /* tokens */ 28_100,
                List.of("Pipeline mapped"));
        checkpoints.saveSegment(cp);

        Optional<ThreadCheckpoint> got = checkpoints.findById(cp.id());
        assertThat(got).isPresent();
        ThreadCheckpoint loaded = got.get();
        assertThat(loaded.threadId()).isEqualTo(threadId);
        assertThat(loaded.seq()).isEqualTo(1L);
        assertThat(loaded.isOverall()).isFalse();
        assertThat(loaded.firstMsgSeq()).isEqualTo(1L);
        assertThat(loaded.lastMsgSeq()).isEqualTo(6L);
        assertThat(loaded.tokensCovered()).isEqualTo(28_100L);
        assertThat(loaded.bulletTitles()).containsExactly("Pipeline mapped");
        assertThat(loaded.supersededAt()).isNull();
    }

    @Test
    void nextSegmentSeqStartsAtOneAndIncrementsPerInsert()
    {
        String threadId = newTask();
        assertThat(checkpoints.nextSegmentSeq(threadId)).isEqualTo(1L);

        checkpoints.saveSegment(segment(threadId, 1, 1, 6, 28_100, List.of()));
        assertThat(checkpoints.nextSegmentSeq(threadId)).isEqualTo(2L);

        checkpoints.saveSegment(segment(threadId, 2, 7, 13, 31_400, List.of()));
        assertThat(checkpoints.nextSegmentSeq(threadId)).isEqualTo(3L);
    }

    @Test
    void replaceOverallStampsPriorAsSupersededAndKeepsExactlyOneActive()
    {
        String threadId = newTask();
        ThreadCheckpoint first = overall(threadId, 1, 6, 28_100, "first rollup",
                List.of("first"));
        checkpoints.replaceOverall(threadId, first);

        // After the first call there should be exactly one active
        // Overall — the one we just inserted.
        Optional<ThreadCheckpoint> active1 = checkpoints.findActiveOverall(threadId);
        assertThat(active1).isPresent();
        assertThat(active1.get().summaryMd()).isEqualTo("first rollup");
        assertThat(active1.get().supersededAt()).isNull();

        // Generate a second Overall that should replace the first.
        // generatedAt must move forward so we can deterministically
        // assert ordering downstream.
        ThreadCheckpoint second = new ThreadCheckpoint(
                UUID.randomUUID().toString(), threadId, 0L, true,
                1L, 13L, 59_500L,
                "second rollup", List.of("second"),
                "claude-haiku-4-5", 1_500L, 350L, 1L,
                Instant.parse("2026-05-15T12:00:01Z"), null,
                /* taskId */ null);
        checkpoints.replaceOverall(threadId, second);

        Optional<ThreadCheckpoint> active2 = checkpoints.findActiveOverall(threadId);
        assertThat(active2).isPresent();
        assertThat(active2.get().summaryMd()).isEqualTo("second rollup");
        assertThat(active2.get().supersededAt()).isNull();

        // The previous Overall row stays in the table for history but
        // is marked superseded — listActive should not return it.
        List<ThreadCheckpoint> activeRows = checkpoints.listActive(threadId);
        assertThat(activeRows).hasSize(1);
        assertThat(activeRows.get(0).summaryMd()).isEqualTo("second rollup");
    }

    @Test
    void listActiveReturnsOverallFirstThenSegmentsByDescendingSeq()
    {
        String threadId = newTask();
        checkpoints.saveSegment(segment(threadId, 1, 1, 6, 28_100, List.of()));
        checkpoints.saveSegment(segment(threadId, 2, 7, 13, 31_400, List.of()));
        checkpoints.saveSegment(segment(threadId, 3, 14, 18, 22_800, List.of()));
        checkpoints.replaceOverall(threadId, overall(threadId, 1, 18, 82_300,
                "overall rollup", List.of()));

        List<ThreadCheckpoint> rows = checkpoints.listActive(threadId);
        assertThat(rows).hasSize(4);
        // Overall comes first.
        assertThat(rows.get(0).isOverall()).isTrue();
        assertThat(rows.get(0).seq()).isZero();
        // Then segments newest-first.
        assertThat(rows.get(1).seq()).isEqualTo(3L);
        assertThat(rows.get(2).seq()).isEqualTo(2L);
        assertThat(rows.get(3).seq()).isEqualTo(1L);
    }

    @Test
    void findLastSegmentSkipsOverallAndReturnsHighestSeq()
    {
        String threadId = newTask();
        checkpoints.saveSegment(segment(threadId, 1, 1, 6, 28_100, List.of()));
        checkpoints.saveSegment(segment(threadId, 2, 7, 13, 31_400, List.of()));
        checkpoints.replaceOverall(threadId, overall(threadId, 1, 13, 59_500, "rollup", List.of()));

        Optional<ThreadCheckpoint> last = checkpoints.findLastSegment(threadId);
        assertThat(last).isPresent();
        assertThat(last.get().isOverall()).isFalse();
        assertThat(last.get().seq()).isEqualTo(2L);
    }

    @Test
    void deleteSegmentDropsTheRowAndRefusesOverall()
    {
        String threadId = newTask();
        ThreadCheckpoint seg = segment(threadId, 1, 1, 6, 28_100, List.of());
        checkpoints.saveSegment(seg);
        checkpoints.replaceOverall(threadId, overall(threadId, 1, 6, 28_100, "rollup", List.of()));

        checkpoints.deleteSegment(seg.id());
        assertThat(checkpoints.findById(seg.id())).isEmpty();

        // Overall row is scheduler-owned; the store refuses direct
        // deletion to keep callers from getting around the
        // replaceOverall invariant by accident.
        String overallId = checkpoints.findActiveOverall(threadId).orElseThrow().id();
        assertThatThrownBy(() -> checkpoints.deleteSegment(overallId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listActiveForTaskReturnsOnlyTheNamedTaskSegmentsNewestFirst()
    {
        String threadId = newTask();
        // SqliteThreadStore.saveThread auto-materialises a seq=1 task
        // when the thread carries execution state — start our explicit
        // task seqs at 2 to avoid colliding with that row.
        String taskA = persistTask(threadId, 2);
        String taskB = persistTask(threadId, 3);

        // Two segments for taskA, one for taskB, and one thread-scoped
        // segment from the 0-Task brainstorm prefix. Only taskA's two
        // rows should come back, newest seq first.
        checkpoints.saveSegment(segmentForTask(threadId, 1, 1, 6, 28_100, List.of("brainstorm"), null));
        checkpoints.saveSegment(segmentForTask(threadId, 2, 7, 13, 31_400, List.of("a-first"), taskA));
        checkpoints.saveSegment(segmentForTask(threadId, 3, 14, 18, 22_800, List.of("b-only"), taskB));
        checkpoints.saveSegment(segmentForTask(threadId, 4, 19, 24, 26_200, List.of("a-second"), taskA));

        List<ThreadCheckpoint> rows = checkpoints.listActiveForTask(taskA);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).seq()).isEqualTo(4L);
        assertThat(rows.get(0).taskId()).isEqualTo(taskA);
        assertThat(rows.get(1).seq()).isEqualTo(2L);
        assertThat(rows.get(1).taskId()).isEqualTo(taskA);
    }

    @Test
    void saveSegmentRejectsOverallShapedRows()
    {
        String threadId = newTask();
        ThreadCheckpoint bad = new ThreadCheckpoint(
                UUID.randomUUID().toString(), threadId, 1L, /* isOverall */ true,
                1L, 6L, 28_100L, "wrong", List.of(),
                "haiku", 100L, 50L, 1L,
                Instant.parse("2026-05-15T12:00:00Z"), null,
                /* taskId */ null);
        assertThatThrownBy(() -> checkpoints.saveSegment(bad))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String newTask()
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        Thread t = new Thread(
                UUID.randomUUID().toString(),
                ThreadKind.CLI_AGENT,
                "claude-code",
                /* agentSessionId */ null,
                "Checkpoints test thread",
                ThreadStatus.RUNNING,
                "/tmp",
                /* branchName */ "main",
                "claude-sonnet-4.6",
                0L, 0L, 0L,
                /* processPid */ null,
                /* logPath */ null,
                now, now, null, null,
                /* metadataJson */ "{}",
                "DEVELOP", null, null, null, null);
        threads.saveThread(t);
        return t.id();
    }

    private String persistTask(String threadId, long seq)
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        Task task = new Task(
                UUID.randomUUID().toString(),
                threadId,
                seq,
                TaskStatus.RUNNING,
                /* branchName */ "bytequay/task-" + seq,
                /* worktreePath */ null,
                /* baseBranch */ "main",
                /* workingDir */ "/tmp",
                /* processPid */ null,
                /* logPath */ null,
                /* prNumber */ null,
                /* prState */ null,
                /* ciState */ null,
                /* taskType */ "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null,
                /* costUsdMilli */ 0L,
                /* tokensIn */ 0L,
                /* tokensOut */ 0L,
                /* firstMsgSeq */ null,
                /* lastMsgSeq */ null,
                /* createdAt */ now,
                /* endedAt */ null,
                /* errorMessage */ null);
        tasks.saveTask(task);
        return task.id();
    }

    private static ThreadCheckpoint segment(
            String threadId, long seq, long firstMsgSeq, long lastMsgSeq,
            long tokensCovered, List<String> bullets)
    {
        return segmentForTask(threadId, seq, firstMsgSeq, lastMsgSeq, tokensCovered, bullets, null);
    }

    private static ThreadCheckpoint segmentForTask(
            String threadId, long seq, long firstMsgSeq, long lastMsgSeq,
            long tokensCovered, List<String> bullets, String taskId)
    {
        return new ThreadCheckpoint(
                UUID.randomUUID().toString(), threadId, seq, /* isOverall */ false,
                firstMsgSeq, lastMsgSeq, tokensCovered,
                "segment summary", bullets,
                "claude-haiku-4-5", 800L, 150L, 1L,
                Instant.parse("2026-05-15T12:00:00Z"), null,
                taskId);
    }

    private static ThreadCheckpoint overall(
            String threadId, long firstMsgSeq, long lastMsgSeq,
            long tokensCovered, String summary, List<String> bullets)
    {
        return new ThreadCheckpoint(
                UUID.randomUUID().toString(), threadId, /* seq */ 0L, /* isOverall */ true,
                firstMsgSeq, lastMsgSeq, tokensCovered,
                summary, bullets,
                "claude-haiku-4-5", 1_500L, 350L, 1L,
                Instant.parse("2026-05-15T12:00:00Z"), null,
                /* taskId — Overall always thread-scoped */ null);
    }
}
