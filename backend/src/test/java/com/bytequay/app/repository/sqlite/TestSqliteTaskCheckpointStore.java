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
import com.bytequay.app.domain.TaskCheckpoint;
import com.bytequay.app.domain.TaskKind;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskCheckpointStore;
import com.bytequay.app.repository.TaskStore;
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
 * End-to-end exercise of {@link SqliteTaskCheckpointStore} against
 * the Flyway-migrated schema. Catches schema/entity drift, the
 * bullet-list JSON serde, and — most importantly — the active-Overall
 * swap, since that's where the "at most one active Overall per task"
 * invariant is enforced.
 */
@SpringBootTest
class TestSqliteTaskCheckpointStore
{
    @Autowired
    private TaskStore tasks;
    @Autowired
    private TaskCheckpointStore checkpoints;

    @Test
    void roundtripsAPerSegmentCheckpoint()
    {
        String taskId = newTask();
        TaskCheckpoint cp = segment(taskId, /* seq */ 1, /* range */ 1, 6, /* tokens */ 28_100,
                List.of("Pipeline mapped"));
        checkpoints.saveSegment(cp);

        Optional<TaskCheckpoint> got = checkpoints.findById(cp.id());
        assertThat(got).isPresent();
        TaskCheckpoint loaded = got.get();
        assertThat(loaded.taskId()).isEqualTo(taskId);
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
        String taskId = newTask();
        assertThat(checkpoints.nextSegmentSeq(taskId)).isEqualTo(1L);

        checkpoints.saveSegment(segment(taskId, 1, 1, 6, 28_100, List.of()));
        assertThat(checkpoints.nextSegmentSeq(taskId)).isEqualTo(2L);

        checkpoints.saveSegment(segment(taskId, 2, 7, 13, 31_400, List.of()));
        assertThat(checkpoints.nextSegmentSeq(taskId)).isEqualTo(3L);
    }

    @Test
    void replaceOverallStampsPriorAsSupersededAndKeepsExactlyOneActive()
    {
        String taskId = newTask();
        TaskCheckpoint first = overall(taskId, 1, 6, 28_100, "first rollup",
                List.of("first"));
        checkpoints.replaceOverall(taskId, first);

        // After the first call there should be exactly one active
        // Overall — the one we just inserted.
        Optional<TaskCheckpoint> active1 = checkpoints.findActiveOverall(taskId);
        assertThat(active1).isPresent();
        assertThat(active1.get().summaryMd()).isEqualTo("first rollup");
        assertThat(active1.get().supersededAt()).isNull();

        // Generate a second Overall that should replace the first.
        // generatedAt must move forward so we can deterministically
        // assert ordering downstream.
        TaskCheckpoint second = new TaskCheckpoint(
                UUID.randomUUID().toString(), taskId, 0L, true,
                1L, 13L, 59_500L,
                "second rollup", List.of("second"),
                "claude-haiku-4-5", 1_500L, 350L, 1L,
                Instant.parse("2026-05-15T12:00:01Z"), null);
        checkpoints.replaceOverall(taskId, second);

        Optional<TaskCheckpoint> active2 = checkpoints.findActiveOverall(taskId);
        assertThat(active2).isPresent();
        assertThat(active2.get().summaryMd()).isEqualTo("second rollup");
        assertThat(active2.get().supersededAt()).isNull();

        // The previous Overall row stays in the table for history but
        // is marked superseded — listActive should not return it.
        List<TaskCheckpoint> activeRows = checkpoints.listActive(taskId);
        assertThat(activeRows).hasSize(1);
        assertThat(activeRows.get(0).summaryMd()).isEqualTo("second rollup");
    }

    @Test
    void listActiveReturnsOverallFirstThenSegmentsByDescendingSeq()
    {
        String taskId = newTask();
        checkpoints.saveSegment(segment(taskId, 1, 1, 6, 28_100, List.of()));
        checkpoints.saveSegment(segment(taskId, 2, 7, 13, 31_400, List.of()));
        checkpoints.saveSegment(segment(taskId, 3, 14, 18, 22_800, List.of()));
        checkpoints.replaceOverall(taskId, overall(taskId, 1, 18, 82_300,
                "overall rollup", List.of()));

        List<TaskCheckpoint> rows = checkpoints.listActive(taskId);
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
        String taskId = newTask();
        checkpoints.saveSegment(segment(taskId, 1, 1, 6, 28_100, List.of()));
        checkpoints.saveSegment(segment(taskId, 2, 7, 13, 31_400, List.of()));
        checkpoints.replaceOverall(taskId, overall(taskId, 1, 13, 59_500, "rollup", List.of()));

        Optional<TaskCheckpoint> last = checkpoints.findLastSegment(taskId);
        assertThat(last).isPresent();
        assertThat(last.get().isOverall()).isFalse();
        assertThat(last.get().seq()).isEqualTo(2L);
    }

    @Test
    void deleteSegmentDropsTheRowAndRefusesOverall()
    {
        String taskId = newTask();
        TaskCheckpoint seg = segment(taskId, 1, 1, 6, 28_100, List.of());
        checkpoints.saveSegment(seg);
        checkpoints.replaceOverall(taskId, overall(taskId, 1, 6, 28_100, "rollup", List.of()));

        checkpoints.deleteSegment(seg.id());
        assertThat(checkpoints.findById(seg.id())).isEmpty();

        // Overall row is scheduler-owned; the store refuses direct
        // deletion to keep callers from getting around the
        // replaceOverall invariant by accident.
        String overallId = checkpoints.findActiveOverall(taskId).orElseThrow().id();
        assertThatThrownBy(() -> checkpoints.deleteSegment(overallId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saveSegmentRejectsOverallShapedRows()
    {
        String taskId = newTask();
        TaskCheckpoint bad = new TaskCheckpoint(
                UUID.randomUUID().toString(), taskId, 1L, /* isOverall */ true,
                1L, 6L, 28_100L, "wrong", List.of(),
                "haiku", 100L, 50L, 1L,
                Instant.parse("2026-05-15T12:00:00Z"), null);
        assertThatThrownBy(() -> checkpoints.saveSegment(bad))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String newTask()
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        Task t = new Task(
                UUID.randomUUID().toString(),
                TaskKind.CLI_AGENT,
                "claude-code",
                /* agentSessionId */ null,
                "Checkpoints test task",
                TaskStatus.RUNNING,
                "/tmp",
                /* branchName */ "main",
                "claude-sonnet-4.6",
                0L, 0L, 0L,
                /* processPid */ null,
                /* logPath */ null,
                now, now, null, null,
                /* metadataJson */ "{}",
                "DEVELOP", null, null);
        tasks.saveTask(t);
        return t.id();
    }

    private static TaskCheckpoint segment(
            String taskId, long seq, long firstMsgSeq, long lastMsgSeq,
            long tokensCovered, List<String> bullets)
    {
        return new TaskCheckpoint(
                UUID.randomUUID().toString(), taskId, seq, /* isOverall */ false,
                firstMsgSeq, lastMsgSeq, tokensCovered,
                "segment summary", bullets,
                "claude-haiku-4-5", 800L, 150L, 1L,
                Instant.parse("2026-05-15T12:00:00Z"), null);
    }

    private static TaskCheckpoint overall(
            String taskId, long firstMsgSeq, long lastMsgSeq,
            long tokensCovered, String summary, List<String> bullets)
    {
        return new TaskCheckpoint(
                UUID.randomUUID().toString(), taskId, /* seq */ 0L, /* isOverall */ true,
                firstMsgSeq, lastMsgSeq, tokensCovered,
                summary, bullets,
                "claude-haiku-4-5", 1_500L, 350L, 1L,
                Instant.parse("2026-05-15T12:00:00Z"), null);
    }
}
