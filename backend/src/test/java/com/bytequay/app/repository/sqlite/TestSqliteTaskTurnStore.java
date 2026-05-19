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
import com.bytequay.app.domain.TaskKind;
import com.bytequay.app.domain.TaskResourceLane;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.TaskTurn;
import com.bytequay.app.domain.TaskTurnEvent;
import com.bytequay.app.domain.TaskTurnEventType;
import com.bytequay.app.domain.TaskTurnStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.TaskTurnEventStore;
import com.bytequay.app.repository.TaskTurnStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.UUID;

import static com.bytequay.app.domain.TaskTurnStatus.QUEUED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end exercise of scheduler turn persistence against the real
 * Flyway-migrated SQLite schema. Catches JPA method drift and index
 * ordering mistakes that the in-memory scheduler tests cannot see.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestSqliteTaskTurnStore
{
    @Autowired
    private TaskStore tasks;
    @Autowired
    private TaskTurnStore turns;
    @Autowired
    private TaskTurnEventStore events;

    @Test
    void taskTurnHistoryUsesStableNewestFirstOrder()
    {
        String taskId = newTask();
        Instant now = Instant.parse("2026-05-19T12:00:00Z");
        turns.saveTurn(turn("turn-a", taskId, QUEUED, now));
        turns.saveTurn(turn("turn-c", taskId, QUEUED, now));
        turns.saveTurn(turn("turn-b", taskId, TaskTurnStatus.RUNNING, now.minusSeconds(1)));

        assertThat(turns.listTurnsByTaskId(taskId, 10))
                .extracting(TaskTurn::id)
                .containsExactly(id(taskId, "turn-c"), id(taskId, "turn-a"), id(taskId, "turn-b"));
        assertThat(turns.listTurnsByTaskIdAndStatus(taskId, QUEUED, 10))
                .extracting(TaskTurn::id)
                .containsExactly(id(taskId, "turn-c"), id(taskId, "turn-a"));
    }

    @Test
    void taskTurnEventsUseStableNewestFirstOrder()
    {
        String taskId = newTask();
        Instant now = Instant.parse("2026-05-19T12:00:00Z");
        String turnId = id(taskId, "turn-a");
        turns.saveTurn(turn("turn-a", taskId, QUEUED, now));
        events.appendEvent(event("event-a", turnId, taskId, now));
        events.appendEvent(event("event-c", turnId, taskId, now));
        events.appendEvent(event("event-b", turnId, taskId, now.minusSeconds(1)));

        assertThat(events.listEventsByTaskId(taskId, 10))
                .extracting(TaskTurnEvent::id)
                .containsExactly(id(taskId, "event-c"), id(taskId, "event-a"), id(taskId, "event-b"));
    }

    private String newTask()
    {
        String taskId = UUID.randomUUID().toString();
        Instant now = Instant.parse("2026-05-19T12:00:00Z");
        tasks.saveTask(new Task(
                taskId,
                TaskKind.CLI_AGENT,
                "claude-code",
                /* agentSessionId */ null,
                "Turn store test task",
                TaskStatus.IDLE,
                "/tmp",
                "main",
                "claude-sonnet-4.6",
                0L,
                0L,
                0L,
                /* processPid */ null,
                /* logPath */ null,
                now,
                now,
                /* endedAt */ null,
                /* errorMessage */ null,
                "{}",
                "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null));
        return taskId;
    }

    private static TaskTurn turn(String suffix, String taskId, TaskTurnStatus status, Instant createdAt)
    {
        return new TaskTurn(
                id(taskId, suffix),
                taskId,
                TaskResourceLane.CLI,
                status,
                "input",
                createdAt,
                createdAt,
                /* startedAt */ null,
                /* finishedAt */ null,
                /* errorMessage */ null);
    }

    private static TaskTurnEvent event(String suffix, String turnId, String taskId, Instant createdAt)
    {
        return new TaskTurnEvent(
                id(taskId, suffix),
                turnId,
                taskId,
                TaskTurnEventType.TURN_QUEUED,
                createdAt,
                /* message */ null);
    }

    private static String id(String taskId, String suffix)
    {
        return taskId + "-" + suffix;
    }
}
