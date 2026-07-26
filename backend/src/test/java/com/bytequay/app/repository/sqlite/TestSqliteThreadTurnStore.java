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
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnEvent;
import com.bytequay.app.domain.ThreadTurnEventType;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnEventStore;
import com.bytequay.app.repository.ThreadTurnStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.bytequay.app.domain.ThreadTurnStatus.QUEUED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end exercise of scheduler turn persistence against the real
 * Flyway-migrated SQLite schema. Catches JPA method drift and index
 * ordering mistakes that the in-memory scheduler tests cannot see.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestSqliteThreadTurnStore
{
    @Autowired
    private ThreadStore threads;
    @Autowired
    private ThreadTurnStore turns;
    @Autowired
    private ThreadTurnEventStore events;
    @Autowired
    private TaskStore tasks;

    @Test
    void threadTurnHistoryUsesStableNewestFirstOrder()
    {
        String threadId = newTask();
        Instant now = Instant.parse("2026-05-19T12:00:00Z");
        turns.saveTurn(turn("turn-a", threadId, QUEUED, now));
        turns.saveTurn(turn("turn-c", threadId, QUEUED, now));
        turns.saveTurn(turn("turn-b", threadId, ThreadTurnStatus.RUNNING, now.minusSeconds(1)));

        assertThat(turns.listTurnsByTaskId(threadId, 10))
                .extracting(ThreadTurn::id)
                .containsExactly(id(threadId, "turn-c"), id(threadId, "turn-a"), id(threadId, "turn-b"));
        assertThat(turns.listTurnsByTaskIdAndStatus(threadId, QUEUED, 10))
                .extracting(ThreadTurn::id)
                .containsExactly(id(threadId, "turn-c"), id(threadId, "turn-a"));
    }

    @Test
    void turnInitiatorRoundTripsThroughTheStore()
    {
        String threadId = newTask();
        Instant now = Instant.parse("2026-05-19T12:00:00Z");
        // A user turn keeps the attended default; an automated turn
        // persists its unattended source so the gate can read it back.
        turns.saveTurn(turn("attended", threadId, QUEUED, now));
        ThreadTurn unattended = new ThreadTurn(
                id(threadId, "auto"), threadId, /* taskId */ null,
                ThreadResourceLane.CLI, QUEUED, "input", now, now,
                null, null, null,
                TurnInitiator.unattended("auto-fix-ci-fail"));
        turns.saveTurn(unattended);

        ThreadTurn reloadedAttended = turns.findTurnById(id(threadId, "attended")).orElseThrow();
        assertThat(reloadedAttended.initiator().attended()).isTrue();
        assertThat(reloadedAttended.initiator().source()).isEqualTo("user");

        ThreadTurn reloadedAuto = turns.findTurnById(id(threadId, "auto")).orElseThrow();
        assertThat(reloadedAuto.initiator().attended()).isFalse();
        assertThat(reloadedAuto.initiator().source()).isEqualTo("auto-fix-ci-fail");
    }

    @Test
    void agentRunIdRoundTripsThroughTheStore()
    {
        String threadId = newTask();
        Instant now = Instant.parse("2026-05-19T12:00:00Z");
        ThreadTurn turn = new ThreadTurn(
                id(threadId, "episode"), threadId, null,
                ThreadResourceLane.CLI, QUEUED, "input", now, now,
                null, null, null, TurnInitiator.unattended("review-round"),
                null, ThreadScope.TRUNK, "run-1");

        turns.saveTurn(turn);

        ThreadTurn reloaded = turns.findTurnById(id(threadId, "episode")).orElseThrow();
        assertThat(reloaded.agentRunId()).isEqualTo("run-1");
    }

    @Test
    void exactTaskStatusLookupDoesNotReturnSiblingTaskTurns()
    {
        String threadId = newTask();
        String firstTaskId = newWorkTask(threadId, 1L);
        String siblingTaskId = newWorkTask(threadId, 2L);
        Instant now = Instant.parse("2026-05-19T12:00:00Z");
        turns.saveTurn(taskTurn("first", threadId, firstTaskId, QUEUED, now));
        turns.saveTurn(taskTurn("sibling", threadId, siblingTaskId, QUEUED, now.plusSeconds(1)));

        assertThat(turns.listTurnsByExactTaskIdAndStatus(firstTaskId, QUEUED, 10))
                .extracting(ThreadTurn::id)
                .containsExactly(id(threadId, "first"));
    }

    @Test
    void threadTurnEventsUseStableNewestFirstOrder()
    {
        String threadId = newTask();
        Instant now = Instant.parse("2026-05-19T12:00:00Z");
        String turnId = id(threadId, "turn-a");
        turns.saveTurn(turn("turn-a", threadId, QUEUED, now));
        events.appendEvent(event("event-a", turnId, threadId, now));
        events.appendEvent(event("event-c", turnId, threadId, now));
        events.appendEvent(event("event-b", turnId, threadId, now.minusSeconds(1)));

        assertThat(events.listEventsByTaskId(threadId, 10))
                .extracting(ThreadTurnEvent::id)
                .containsExactly(id(threadId, "event-c"), id(threadId, "event-a"), id(threadId, "event-b"));
    }

    @Test
    void turnListMethodsRejectNonPositiveLimits()
    {
        String threadId = newTask();
        Instant now = Instant.parse("2026-05-19T12:00:00Z");

        assertThatThrownBy(() -> turns.listTurnsByStatus(QUEUED, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
        assertThatThrownBy(() -> turns.listTurnsByStatusAfter(QUEUED, now, id(threadId, "turn-a"), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
        assertThatThrownBy(() -> turns.listTurnsByStatuses(List.of(QUEUED), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
        assertThatThrownBy(() -> turns.listTurnsByTaskIdAndStatus(threadId, QUEUED, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
        assertThatThrownBy(() -> turns.listTurnsByExactTaskIdAndStatus("task-1", QUEUED, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
        assertThatThrownBy(() -> turns.listTurnsByTaskId(threadId, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
        assertThatThrownBy(() -> events.listEventsByTaskId(threadId, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
    }

    @Test
    void keyedInsertIsClaimOnceAndPreservesLivenessAcrossStatusSaves()
    {
        String threadId = newTask();
        Instant now = Instant.parse("2026-07-25T12:00:00Z");
        String kickKey = "test-kick:" + threadId;
        ThreadTurn turn = new ThreadTurn(
                id(threadId, "keyed"), threadId, /* taskId */ null,
                ThreadResourceLane.CLI, QUEUED, "input", now, now,
                null, null, null,
                TurnInitiator.unattended("brain-review-fix"));

        ThreadTurnStore.InsertResult inserted = turns.insertTurn(turn, true, kickKey);
        assertThat(inserted.turnId()).isEqualTo(turn.id());
        assertThat(inserted.inserted()).isTrue();
        assertThat(turns.findTurnIdByKickKey(kickKey)).contains(turn.id());
        assertThat(turns.turnAffectsTaskLiveness(turn.id())).isTrue();

        // A second keyed insert is a no-op — the first claim holds.
        ThreadTurn duplicate = new ThreadTurn(
                id(threadId, "keyed-dup"), threadId, null,
                ThreadResourceLane.CLI, QUEUED, "input", now, now,
                null, null, null,
                TurnInitiator.unattended("brain-review-fix"));
        ThreadTurnStore.InsertResult replay = turns.insertTurn(duplicate, false, kickKey);
        assertThat(replay.turnId()).isEqualTo(turn.id());
        assertThat(replay.inserted()).isFalse();
        assertThat(turns.findTurnIdByKickKey(kickKey)).contains(turn.id());
        assertThat(turns.findTurnById(duplicate.id())).isEmpty();

        // Status updates through the legacy full-row save keep the
        // entity-managed liveness flag and kick key intact.
        turns.saveTurn(new ThreadTurn(
                turn.id(), threadId, null,
                ThreadResourceLane.CLI, ThreadTurnStatus.COMPLETED, "input", now, now,
                now, now, null,
                TurnInitiator.unattended("brain-review-fix")));
        assertThat(turns.turnAffectsTaskLiveness(turn.id())).isTrue();
        assertThat(turns.findTurnIdByKickKey(kickKey)).contains(turn.id());
    }

    @Test
    void statusCompareAndSetCannotRewriteAChangedOrTerminalTurn()
    {
        String threadId = newTask();
        Instant now = Instant.parse("2026-07-25T12:00:00Z");
        ThreadTurn turn = turn("turn-cas", threadId, QUEUED, now);
        turns.saveTurn(turn);

        assertThat(turns.updateStatusIf(
                turn.id(), QUEUED, ThreadTurnStatus.RUNNING,
                now.plusSeconds(1), now.plusSeconds(1), null, null)).isTrue();
        assertThat(turns.updateStatusIf(
                turn.id(), QUEUED, ThreadTurnStatus.CANCELLED,
                now.plusSeconds(2), null, now.plusSeconds(2), "stale")).isFalse();
        assertThat(turns.updateStatusIf(
                turn.id(), ThreadTurnStatus.RUNNING, ThreadTurnStatus.COMPLETED,
                now.plusSeconds(3), now.plusSeconds(1), now.plusSeconds(3), null)).isTrue();
        assertThat(turns.updateStatusIf(
                turn.id(), ThreadTurnStatus.RUNNING, ThreadTurnStatus.FAILED,
                now.plusSeconds(4), now.plusSeconds(1), now.plusSeconds(4), "late")).isFalse();

        ThreadTurn completed = turns.findTurnById(turn.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(ThreadTurnStatus.COMPLETED);
        assertThat(completed.errorMessage()).isNull();
    }

    private String newTask()
    {
        String threadId = UUID.randomUUID().toString();
        Instant now = Instant.parse("2026-05-19T12:00:00Z");
        threads.saveThread(new Thread(
                threadId,
                ThreadKind.CLI_AGENT,
                "claude-code",
                /* agentSessionId */ null,
                "Turn store test thread",
                ThreadStatus.IDLE,
                "claude-sonnet-4.6",
                0L,
                0L,
                0L,
                now,
                now,
                /* endedAt */ null,
                /* errorMessage */ null,
                ThreadFlow.BUILD,
                "ws-default",
                /* workModel */ null,
                /* activeTask */ null));
        return threadId;
    }

    private String newWorkTask(String threadId, long seq)
    {
        String taskId = UUID.randomUUID().toString();
        Instant now = Instant.parse("2026-05-19T12:00:00Z");
        tasks.saveTask(new Task(
                taskId, threadId, seq, TaskStatus.RUNNING, "feature-" + seq,
                null, "main", "/tmp", null, null, null, null, null,
                "DEVELOP", null, null, 0L, 0L, 0L, null, now, null,
                null, null, null, null));
        return taskId;
    }

    private static ThreadTurn turn(String suffix, String threadId, ThreadTurnStatus status, Instant createdAt)
    {
        return new ThreadTurn(
                id(threadId, suffix),
                threadId,
                /* taskId */ null,
                ThreadResourceLane.CLI,
                status,
                "input",
                createdAt,
                createdAt,
                /* startedAt */ null,
                /* finishedAt */ null,
                /* errorMessage */ null,
                TurnInitiator.user());
    }

    private static ThreadTurn taskTurn(
            String suffix,
            String threadId,
            String taskId,
            ThreadTurnStatus status,
            Instant createdAt)
    {
        return new ThreadTurn(
                id(threadId, suffix),
                threadId,
                taskId,
                ThreadResourceLane.CLI,
                status,
                "input",
                createdAt,
                createdAt,
                /* startedAt */ null,
                /* finishedAt */ null,
                /* errorMessage */ null,
                TurnInitiator.user(),
                "stage-1",
                ThreadScope.STAGE,
                /* agentRunId */ null);
    }

    private static ThreadTurnEvent event(String suffix, String turnId, String threadId, Instant createdAt)
    {
        return new ThreadTurnEvent(
                id(threadId, suffix),
                turnId,
                threadId,
                /* taskId */ null,
                ThreadTurnEventType.TURN_QUEUED,
                createdAt,
                /* message */ null);
    }

    private static String id(String threadId, String suffix)
    {
        return threadId + "-" + suffix;
    }
}
