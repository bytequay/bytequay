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

import com.bytequay.app.domain.AgentMetrics;
import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.TaskKind;
import com.bytequay.app.domain.TaskMessage;
import com.bytequay.app.domain.TaskResourceLane;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.TaskTurn;
import com.bytequay.app.domain.TaskTurnEvent;
import com.bytequay.app.domain.TaskTurnStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.TaskTurnEventStore;
import com.bytequay.app.repository.TaskTurnStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static com.bytequay.app.domain.TaskKind.CLI_AGENT;
import static com.bytequay.app.domain.TaskKind.LOGIC_LOOP;
import static com.bytequay.app.domain.TaskTurnEventType.TURN_CANCELLED;
import static com.bytequay.app.domain.TaskTurnEventType.TURN_FAILED;
import static com.bytequay.app.domain.TaskTurnEventType.TURN_FINISHED;
import static com.bytequay.app.domain.TaskTurnEventType.TURN_QUEUED;
import static com.bytequay.app.domain.TaskTurnEventType.TURN_STARTED;
import static com.bytequay.app.domain.TaskTurnEventType.WAITING_FOR_CAPACITY;
import static com.bytequay.app.domain.TaskTurnStatus.CANCELLED;
import static com.bytequay.app.domain.TaskTurnStatus.COMPLETED;
import static com.bytequay.app.domain.TaskTurnStatus.FAILED;
import static com.bytequay.app.domain.TaskTurnStatus.QUEUED;
import static com.bytequay.app.domain.TaskTurnStatus.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;

class TestAgentScheduler
{
    @Test
    void capsCliTurnsAndQueuesOverflow()
    {
        TestHarness harness = new TestHarness(1, 4);
        Task first = task("task-1", CLI_AGENT);
        Task second = task("task-2", CLI_AGENT);
        RecordingSession firstSession = harness.register(first);
        RecordingSession secondSession = harness.register(second);

        String firstTurn = harness.scheduler.enqueueTurn(first, "first");
        String secondTurn = harness.scheduler.enqueueTurn(second, "second");

        assertThat(firstSession.inputs).containsExactly("first");
        assertThat(secondSession.inputs).isEmpty();
        assertThat(harness.turns.findTurnById(firstTurn).orElseThrow().status())
                .isEqualTo(RUNNING);
        assertThat(harness.turns.findTurnById(secondTurn).orElseThrow().status())
                .isEqualTo(QUEUED);

        firstSession.completeNext();

        assertThat(harness.turns.findTurnById(firstTurn).orElseThrow().status())
                .isEqualTo(COMPLETED);
        assertThat(secondSession.inputs).containsExactly("second");
        assertThat(harness.turns.findTurnById(secondTurn).orElseThrow().status())
                .isEqualTo(RUNNING);
    }

    @Test
    void apiLaneRunsWhileCliLaneIsFull()
    {
        TestHarness harness = new TestHarness(1, 1);
        Task cliFirst = task("cli-1", CLI_AGENT);
        Task cliSecond = task("cli-2", CLI_AGENT);
        Task apiTask = task("api-1", LOGIC_LOOP);
        RecordingSession cliFirstSession = harness.register(cliFirst);
        RecordingSession cliSecondSession = harness.register(cliSecond);
        RecordingSession apiSession = harness.register(apiTask);

        harness.scheduler.enqueueTurn(cliFirst, "cli first");
        String cliSecondTurn = harness.scheduler.enqueueTurn(cliSecond, "cli second");
        String apiTurn = harness.scheduler.enqueueTurn(apiTask, "api");

        assertThat(cliFirstSession.inputs).containsExactly("cli first");
        assertThat(cliSecondSession.inputs).isEmpty();
        assertThat(apiSession.inputs).containsExactly("api");
        assertThat(harness.turns.findTurnById(cliSecondTurn).orElseThrow().status())
                .isEqualTo(QUEUED);
        assertThat(harness.turns.findTurnById(apiTurn).orElseThrow().lane())
                .isEqualTo(TaskResourceLane.API);
    }

    @Test
    void sameTaskTurnsDoNotRunConcurrently()
    {
        TestHarness harness = new TestHarness(2, 4);
        Task task = task("task-1", CLI_AGENT);
        RecordingSession session = harness.register(task);

        String firstTurn = harness.scheduler.enqueueTurn(task, "first");
        String secondTurn = harness.scheduler.enqueueTurn(task, "second");

        assertThat(session.inputs).containsExactly("first");
        assertThat(harness.turns.findTurnById(firstTurn).orElseThrow().status())
                .isEqualTo(RUNNING);
        assertThat(harness.turns.findTurnById(secondTurn).orElseThrow().status())
                .isEqualTo(QUEUED);

        session.completeNext();

        assertThat(session.inputs).containsExactly("first", "second");
        assertThat(harness.turns.findTurnById(firstTurn).orElseThrow().status())
                .isEqualTo(COMPLETED);
        assertThat(harness.turns.findTurnById(secondTurn).orElseThrow().status())
                .isEqualTo(RUNNING);
    }

    @Test
    void cancelQueuedTurnsRemovesOnlyQueuedTurnsForTask()
    {
        TestHarness harness = new TestHarness(1, 4);
        Task first = task("task-1", CLI_AGENT);
        Task second = task("task-2", CLI_AGENT);
        RecordingSession firstSession = harness.register(first);
        RecordingSession secondSession = harness.register(second);

        String runningTurn = harness.scheduler.enqueueTurn(first, "first");
        String cancelledTurn = harness.scheduler.enqueueTurn(first, "second");
        String otherTaskTurn = harness.scheduler.enqueueTurn(second, "other");

        assertThat(harness.scheduler.cancelQueuedTurns(first.id())).isEqualTo(1);

        assertThat(harness.turns.findTurnById(runningTurn).orElseThrow().status())
                .isEqualTo(RUNNING);
        assertThat(harness.turns.findTurnById(cancelledTurn).orElseThrow().status())
                .isEqualTo(CANCELLED);
        assertThat(harness.turns.findTurnById(otherTaskTurn).orElseThrow().status())
                .isEqualTo(QUEUED);

        firstSession.completeNext();

        assertThat(firstSession.inputs).containsExactly("first");
        assertThat(secondSession.inputs).containsExactly("other");
        assertThat(harness.turns.findTurnById(runningTurn).orElseThrow().status())
                .isEqualTo(COMPLETED);
        assertThat(harness.turns.findTurnById(otherTaskTurn).orElseThrow().status())
                .isEqualTo(RUNNING);
    }

    @Test
    void appendsSchedulerEventsForTurnLifecycle()
    {
        TestHarness harness = new TestHarness(1, 4);
        Task task = task("task-1", CLI_AGENT);
        RecordingSession session = harness.register(task);

        String turnId = harness.scheduler.enqueueTurn(task, "first");
        session.completeNext();

        assertThat(harness.events.listEventsByTaskId(task.id(), 10))
                .extracting(TaskTurnEvent::event)
                .containsExactlyInAnyOrder(TURN_FINISHED, TURN_STARTED, TURN_QUEUED);
        assertThat(harness.events.listEventsByTaskId(task.id(), 10))
                .extracting(TaskTurnEvent::turnId)
                .containsOnly(turnId);
    }

    @Test
    void appendsSchedulerEventWhenQueuedTurnIsCancelled()
    {
        TestHarness harness = new TestHarness(1, 4);
        Task first = task("task-1", CLI_AGENT);
        Task second = task("task-2", CLI_AGENT);
        harness.register(first);
        harness.register(second);

        harness.scheduler.enqueueTurn(first, "first");
        String cancelledTurn = harness.scheduler.enqueueTurn(second, "second");

        assertThat(harness.scheduler.cancelQueuedTurns(second.id())).isEqualTo(1);
        assertThat(harness.events.listEventsByTaskId(second.id(), 10))
                .extracting(TaskTurnEvent::event)
                .containsExactlyInAnyOrder(TURN_CANCELLED, WAITING_FOR_CAPACITY, TURN_QUEUED);
        assertThat(harness.events.listEventsByTaskId(second.id(), 10))
                .extracting(TaskTurnEvent::turnId)
                .containsOnly(cancelledTurn);
    }

    @Test
    void appendsWaitingEventWhenLaneIsFull()
    {
        TestHarness harness = new TestHarness(1, 4);
        Task first = task("task-1", CLI_AGENT);
        Task second = task("task-2", CLI_AGENT);
        harness.register(first);
        harness.register(second);

        harness.scheduler.enqueueTurn(first, "first");
        String waitingTurn = harness.scheduler.enqueueTurn(second, "second");

        assertThat(harness.events.listEventsByTaskId(second.id(), 10))
                .anySatisfy(event -> {
                    assertThat(event.turnId()).isEqualTo(waitingTurn);
                    assertThat(event.event()).isEqualTo(WAITING_FOR_CAPACITY);
                    assertThat(event.message()).isEqualTo("waiting for cli lane capacity");
                });
    }

    @Test
    void appendsWaitingEventWhenSameTaskAlreadyHasRunningTurn()
    {
        TestHarness harness = new TestHarness(2, 4);
        Task task = task("task-1", CLI_AGENT);
        harness.register(task);

        harness.scheduler.enqueueTurn(task, "first");
        String waitingTurn = harness.scheduler.enqueueTurn(task, "second");

        assertThat(harness.events.listEventsByTaskId(task.id(), 10))
                .anySatisfy(event -> {
                    assertThat(event.turnId()).isEqualTo(waitingTurn);
                    assertThat(event.event()).isEqualTo(WAITING_FOR_CAPACITY);
                    assertThat(event.message()).isEqualTo("waiting for previous turn for this task");
                });
    }

    @Test
    void cancelQueuedTurnsPagesThroughAllDurableQueuedTurns()
    {
        TestHarness harness = new TestHarness(1, 4);
        String taskId = "task-1";
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        for (int i = 0; i < 1_001; i++) {
            harness.turns.saveTurn(turn("turn-" + i, taskId, now.plusMillis(i)));
        }

        assertThat(harness.scheduler.cancelQueuedTurns(taskId)).isEqualTo(1_001);
        assertThat(harness.turns.turns.values())
                .extracting(TaskTurn::status)
                .containsOnly(CANCELLED);
    }

    @Test
    void recoveryPagesThroughAllOrphanedRunningTurns()
    {
        TestHarness harness = new TestHarness(1, 4);
        Task task = task("task-1", CLI_AGENT);
        RecordingSession session = harness.register(task);
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        for (int i = 0; i < 1_001; i++) {
            harness.turns.saveTurn(turn("turn-" + i, task.id(), RUNNING, now.plusMillis(i)));
        }

        harness.scheduler.recoverQueuedTurns();

        assertThat(session.inputs).containsExactly("input");
        assertThat(harness.turns.turns.values())
                .filteredOn(turn -> turn.status() == RUNNING)
                .hasSize(1);
        assertThat(harness.turns.turns.values())
                .filteredOn(turn -> turn.status() == QUEUED)
                .hasSize(1_000);
        assertThat(harness.events.listEventsByTaskId(task.id(), 2_100))
                .filteredOn(event -> event.event() == TURN_QUEUED)
                .hasSize(1_001);
    }

    @Test
    void recoveryPagesThroughAllDurableQueuedTurns()
    {
        TestHarness harness = new TestHarness(1, 4);
        Task task = task("task-1", CLI_AGENT);
        RecordingSession session = harness.register(task);
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        for (int i = 0; i < 1_001; i++) {
            harness.turns.saveTurn(turn("turn-" + i, task.id(), QUEUED, now.plusMillis(i)));
        }

        harness.scheduler.recoverQueuedTurns();

        for (int i = 0; i < 1_001; i++) {
            assertThat(session.inputs).hasSize(i + 1);
            session.completeNext();
        }
        assertThat(harness.turns.turns.values())
                .extracting(TaskTurn::status)
                .containsOnly(COMPLETED);
    }

    @Test
    void recoveryDoesNotDuplicateWaitingEventForAlreadyKnownQueuedTurn()
    {
        TestHarness harness = new TestHarness(1, 4);
        Task first = task("task-1", CLI_AGENT);
        Task second = task("task-2", CLI_AGENT);
        RecordingSession firstSession = harness.register(first);
        RecordingSession secondSession = harness.register(second);
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        harness.turns.saveTurn(turn("turn-1", first.id(), RUNNING, now));
        harness.turns.saveTurn(turn("turn-2", second.id(), RUNNING, now.plusMillis(1)));

        harness.scheduler.recoverQueuedTurns();

        assertThat(firstSession.inputs).containsExactly("input");
        assertThat(secondSession.inputs).isEmpty();
        assertThat(harness.events.listEventsByTaskId(second.id(), 10))
                .filteredOn(event -> event.event() == WAITING_FOR_CAPACITY)
                .hasSize(1)
                .allSatisfy(event -> assertThat(event.turnId()).isEqualTo("turn-2"));
    }

    @Test
    void failedTurnReleasesLane()
    {
        TestHarness harness = new TestHarness(1, 4);
        Task first = task("task-1", CLI_AGENT);
        Task second = task("task-2", CLI_AGENT);
        RecordingSession firstSession = harness.register(first);
        RecordingSession secondSession = harness.register(second);

        String firstTurn = harness.scheduler.enqueueTurn(first, "first");
        String secondTurn = harness.scheduler.enqueueTurn(second, "second");

        firstSession.failNext(new IllegalStateException("boom"));

        TaskTurn failed = harness.turns.findTurnById(firstTurn).orElseThrow();
        assertThat(failed.status()).isEqualTo(FAILED);
        assertThat(failed.errorMessage()).isEqualTo("boom");
        assertThat(harness.events.listEventsByTaskId(first.id(), 10))
                .anySatisfy(event -> {
                    assertThat(event.turnId()).isEqualTo(firstTurn);
                    assertThat(event.event()).isEqualTo(TURN_FAILED);
                    assertThat(event.message()).isEqualTo("boom");
                });
        assertThat(secondSession.inputs).containsExactly("second");
        assertThat(harness.turns.findTurnById(secondTurn).orElseThrow().status())
                .isEqualTo(RUNNING);
    }

    private static Task task(String id, TaskKind kind)
    {
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        return new Task(
                id,
                kind,
                kind == CLI_AGENT ? "claude-code" : "openai",
                /* agentSessionId */ null,
                "Task " + id,
                TaskStatus.IDLE,
                "/tmp/work",
                "main",
                "model",
                /* costUsdMilli */ 0L,
                /* tokensIn */ 0L,
                /* tokensOut */ 0L,
                /* processPid */ null,
                /* logPath */ null,
                now,
                now,
                /* endedAt */ null,
                /* errorMessage */ null,
                "{}",
                "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null);
    }

    private static final class TestHarness
    {
        private final InMemoryTaskStore tasks = new InMemoryTaskStore();
        private final InMemoryTaskTurnStore turns = new InMemoryTaskTurnStore();
        private final InMemoryTaskTurnEventStore events = new InMemoryTaskTurnEventStore();
        private final RecordingRegistry registry = new RecordingRegistry();
        private final AgentScheduler scheduler;

        private TestHarness(int maxCliRunning, int maxApiRunning)
        {
            scheduler = new AgentScheduler(tasks, turns, events, registry, maxCliRunning, maxApiRunning);
        }

        private RecordingSession register(Task task)
        {
            RecordingSession session = new RecordingSession(task);
            tasks.saveTask(task);
            registry.sessions.put(task.id(), session);
            return session;
        }
    }

    private static final class RecordingRegistry
            extends TaskSessionRegistry
    {
        private final Map<String, AgentSession> sessions = new LinkedHashMap<>();

        private RecordingRegistry()
        {
            super(
                    new InMemoryTaskStore(),
                    new StreamJsonParser(new ObjectMapper()),
                    new ObjectMapper(),
                    new McpPermissionGate(),
                    Executors.newSingleThreadExecutor());
        }

        @Override
        public AgentSession getOrCreate(Task task)
        {
            AgentSession session = sessions.get(task.id());
            if (session == null) {
                throw new IllegalStateException("no session for " + task.id());
            }
            return session;
        }
    }

    private static final class InMemoryTaskStore
            implements TaskStore
    {
        private final Map<String, Task> tasks = new LinkedHashMap<>();

        @Override
        public void saveTask(Task task)
        {
            tasks.put(task.id(), task);
        }

        @Override
        public Optional<Task> findTaskById(String id)
        {
            return Optional.ofNullable(tasks.get(id));
        }

        @Override
        public void deleteTask(String id)
        {
            tasks.remove(id);
        }

        @Override
        public List<Task> listTasksByStatus(TaskStatus status, int limit)
        {
            return tasks.values().stream()
                    .filter(task -> task.status() == status)
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<Task> listTasksByIds(Collection<String> ids)
        {
            return tasks.values().stream()
                    .filter(task -> ids.contains(task.id()))
                    .toList();
        }

        @Override
        public void appendMessage(TaskMessage message) {}

        @Override
        public List<TaskMessage> listMessages(String taskId)
        {
            return List.of();
        }

        @Override
        public void recordFile(TaskFile file) {}

        @Override
        public List<TaskFile> listFiles(String taskId)
        {
            return List.of();
        }
    }

    private static final class InMemoryTaskTurnStore
            implements TaskTurnStore
    {
        private final Map<String, TaskTurn> turns = new LinkedHashMap<>();

        @Override
        public void saveTurn(TaskTurn turn)
        {
            turns.put(turn.id(), turn);
        }

        @Override
        public Optional<TaskTurn> findTurnById(String id)
        {
            return Optional.ofNullable(turns.get(id));
        }

        @Override
        public List<TaskTurn> listTurnsByStatus(TaskTurnStatus status, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> turn.status() == status)
                    .sorted(turnOrder())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<TaskTurn> listTurnsByStatusAfter(TaskTurnStatus status, Instant createdAfter, String idAfter, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> turn.status() == status)
                    .filter(turn -> turn.createdAt().compareTo(createdAfter) > 0
                            || (turn.createdAt().equals(createdAfter) && turn.id().compareTo(idAfter) > 0))
                    .sorted(turnOrder())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<TaskTurn> listTurnsByStatuses(Collection<TaskTurnStatus> statuses, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> statuses.contains(turn.status()))
                    .sorted(turnOrder())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<TaskTurn> listTurnsByTaskIdAndStatus(String taskId, TaskTurnStatus status, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> turn.taskId().equals(taskId))
                    .filter(turn -> turn.status() == status)
                    .sorted(Comparator.comparing(TaskTurn::createdAt).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<TaskTurn> listTurnsByTaskId(String taskId, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> turn.taskId().equals(taskId))
                    .sorted(Comparator.comparing(TaskTurn::createdAt).reversed())
                    .limit(limit)
                    .toList();
        }

        private static Comparator<TaskTurn> turnOrder()
        {
            return Comparator.comparing(TaskTurn::createdAt)
                    .thenComparing(TaskTurn::id);
        }
    }

    private static final class InMemoryTaskTurnEventStore
            implements TaskTurnEventStore
    {
        private final Map<String, TaskTurnEvent> events = new LinkedHashMap<>();

        @Override
        public void appendEvent(TaskTurnEvent event)
        {
            events.put(event.id(), event);
        }

        @Override
        public List<TaskTurnEvent> listEventsByTaskId(String taskId, int limit)
        {
            return events.values().stream()
                    .filter(event -> event.taskId().equals(taskId))
                    .sorted(Comparator.comparing(TaskTurnEvent::createdAt).reversed())
                    .limit(limit)
                    .toList();
        }
    }

    private static TaskTurn turn(String id, String taskId, Instant createdAt)
    {
        return turn(id, taskId, QUEUED, createdAt);
    }

    private static TaskTurn turn(String id, String taskId, TaskTurnStatus status, Instant createdAt)
    {
        return new TaskTurn(
                id,
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

    private static final class RecordingSession
            implements AgentSession
    {
        private final Task task;
        private final List<String> inputs = new ArrayList<>();
        private final ArrayDeque<CompletableFuture<Void>> completions = new ArrayDeque<>();
        private TaskStatus status = TaskStatus.IDLE;

        private RecordingSession(Task task)
        {
            this.task = task;
        }

        @Override
        public String id()
        {
            return task.id();
        }

        @Override
        public TaskKind kind()
        {
            return task.kind();
        }

        @Override
        public String provider()
        {
            return task.provider();
        }

        @Override
        public String model()
        {
            return task.model();
        }

        @Override
        public String workingDir()
        {
            return task.workingDir();
        }

        @Override
        public String branchName()
        {
            return task.branchName();
        }

        @Override
        public TaskStatus status()
        {
            return status;
        }

        @Override
        public AgentMetrics metrics()
        {
            return new AgentMetrics(0, 0, 0, 0, 0, 0);
        }

        @Override
        public List<TaskMessage> history()
        {
            return List.of();
        }

        @Override
        public CompletionStage<Void> send(String userInput)
        {
            inputs.add(userInput);
            status = TaskStatus.RUNNING;
            CompletableFuture<Void> completion = new CompletableFuture<>();
            completions.add(completion);
            return completion;
        }

        private void completeNext()
        {
            status = TaskStatus.IDLE;
            completions.removeFirst().complete(null);
        }

        private void failNext(RuntimeException failure)
        {
            status = TaskStatus.ERRORED;
            completions.removeFirst().completeExceptionally(failure);
        }

        @Override
        public void interrupt() {}

        @Override
        public void pause() {}

        @Override
        public void resume() {}

        @Override
        public void stop() {}

        @Override
        public void notifyPermissionRequested(String callId, String toolName, String summary) {}

        @Override
        public void decide(String callId, PermissionDecision decision) {}

        @Override
        public void grantToolBudget(String toolName, int count) {}

        @Override
        public OptionalInt tryConsumeToolBudget(String toolName)
        {
            return OptionalInt.empty();
        }

        @Override
        public void notifyPermissionAutoAllowed(String callId, String toolName, int remaining) {}

        @Override
        public Runnable subscribeToEvents(Consumer<StreamEvent> listener)
        {
            return () -> {};
        }
    }
}
