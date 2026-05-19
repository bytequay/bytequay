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
import com.bytequay.app.domain.TaskGroup;
import com.bytequay.app.domain.TaskGroupMembership;
import com.bytequay.app.domain.TaskKind;
import com.bytequay.app.domain.TaskMessage;
import com.bytequay.app.domain.TaskResourceLane;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.TaskTurn;
import com.bytequay.app.domain.TaskTurnEvent;
import com.bytequay.app.domain.TaskTurnEventType;
import com.bytequay.app.domain.TaskTurnStatus;
import com.bytequay.app.repository.TaskGroupStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.TaskTurnEventStore;
import com.bytequay.app.repository.TaskTurnStore;
import com.bytequay.app.service.local.GitRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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

import static org.assertj.core.api.Assertions.assertThat;

class TestTaskServiceScheduler
{
    @Test
    void createQueuesInitialPromptThroughScheduler()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        RecordingScheduler scheduler = new RecordingScheduler();
        ThrowingRegistry registry = new ThrowingRegistry();
        TaskService service = new TaskService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                registry,
                scheduler,
                new GitRunner());

        service.create(new TaskService.NewTaskRequest(
                TaskKind.CLI_AGENT,
                "claude-code",
                "claude-sonnet-4.6",
                "Fix tests",
                "/tmp/work",
                "main",
                "please fix",
                "{}",
                List.of(),
                "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null));

        assertThat(store.tasks).hasSize(1);
        assertThat(scheduler.requests).hasSize(1);
        assertThat(scheduler.requests.get(0).task()).isEqualTo(store.tasks.values().iterator().next());
        assertThat(scheduler.requests.get(0).input()).isEqualTo("please fix");
        assertThat(registry.used).isFalse();
    }

    @Test
    void createWithoutPromptDoesNotStartSession()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        RecordingScheduler scheduler = new RecordingScheduler();
        ThrowingRegistry registry = new ThrowingRegistry();
        TaskService service = new TaskService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                registry,
                scheduler,
                new GitRunner());

        service.create(new TaskService.NewTaskRequest(
                TaskKind.CLI_AGENT,
                "claude-code",
                "claude-sonnet-4.6",
                "Fix tests",
                "/tmp/work",
                "main",
                " ",
                "{}",
                List.of(),
                "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null));

        assertThat(scheduler.requests).isEmpty();
        assertThat(registry.used).isFalse();
    }

    @Test
    void followUpSendQueuesThroughScheduler()
    {
        Task task = task();
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveTask(task);
        RecordingScheduler scheduler = new RecordingScheduler();
        ThrowingRegistry registry = new ThrowingRegistry();
        TaskService service = new TaskService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                registry,
                scheduler,
                new GitRunner());

        String turnId = service.send(task.id(), "next");

        assertThat(turnId).isEqualTo("turn-1");
        assertThat(scheduler.requests).containsExactly(new QueuedRequest(task, "next"));
        assertThat(registry.used).isFalse();
    }

    @Test
    void turnsReturnDurableHistoryForTaskOnly()
    {
        Task task = task();
        Task otherTask = task("task-2");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveTask(task);
        store.saveTask(otherTask);
        InMemoryTaskTurnStore turns = new InMemoryTaskTurnStore();
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        turns.saveTurn(turn("turn-1", task.id(), now.minusSeconds(10)));
        turns.saveTurn(turn("turn-2", otherTask.id(), now));
        turns.saveTurn(turn("turn-3", task.id(), now.plusSeconds(10)));
        RecordingScheduler scheduler = new RecordingScheduler();
        ThrowingRegistry registry = new ThrowingRegistry();
        TaskService service = new TaskService(
                store,
                new EmptyTaskGroupStore(),
                turns,
                new InMemoryTaskTurnEventStore(),
                registry,
                scheduler,
                new GitRunner());

        assertThat(service.turns(task.id()))
                .extracting(TaskTurn::id)
                .containsExactly("turn-3", "turn-1");
        assertThat(registry.used).isFalse();
    }

    @Test
    void turnsUseStableTieBreakerForMatchingTimestamps()
    {
        Task task = task();
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveTask(task);
        InMemoryTaskTurnStore turns = new InMemoryTaskTurnStore();
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        turns.saveTurn(turn("turn-a", task.id(), now));
        turns.saveTurn(turn("turn-c", task.id(), now));
        turns.saveTurn(turn("turn-b", task.id(), now.minusSeconds(1)));
        RecordingScheduler scheduler = new RecordingScheduler();
        ThrowingRegistry registry = new ThrowingRegistry();
        TaskService service = new TaskService(
                store,
                new EmptyTaskGroupStore(),
                turns,
                new InMemoryTaskTurnEventStore(),
                registry,
                scheduler,
                new GitRunner());

        assertThat(service.turns(task.id()))
                .extracting(TaskTurn::id)
                .containsExactly("turn-c", "turn-a", "turn-b");
        assertThat(registry.used).isFalse();
    }

    @Test
    void turnEventsReturnDurableHistoryForTaskOnly()
    {
        Task task = task();
        Task otherTask = task("task-2");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveTask(task);
        store.saveTask(otherTask);
        InMemoryTaskTurnEventStore turnEvents = new InMemoryTaskTurnEventStore();
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        turnEvents.appendEvent(turnEvent("event-1", "turn-1", task.id(), now.minusSeconds(10)));
        turnEvents.appendEvent(turnEvent("event-2", "turn-2", otherTask.id(), now));
        turnEvents.appendEvent(turnEvent("event-3", "turn-3", task.id(), now.plusSeconds(10)));
        ThrowingRegistry registry = new ThrowingRegistry();
        TaskService service = new TaskService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                turnEvents,
                registry,
                new RecordingScheduler(),
                new GitRunner());

        assertThat(service.turnEvents(task.id()))
                .extracting(TaskTurnEvent::id)
                .containsExactly("event-3", "event-1");
        assertThat(registry.used).isFalse();
    }

    @Test
    void activeTurnsReturnQueuedAndRunningOnly()
    {
        InMemoryTaskTurnStore turns = new InMemoryTaskTurnStore();
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        turns.saveTurn(turn("queued", "task-1", TaskTurnStatus.QUEUED, now.minusSeconds(30)));
        turns.saveTurn(turn("completed", "task-2", TaskTurnStatus.COMPLETED, now.minusSeconds(20)));
        turns.saveTurn(turn("running", "task-3", TaskTurnStatus.RUNNING, now.minusSeconds(10)));
        TaskService service = new TaskService(
                new InMemoryTaskStore(),
                new EmptyTaskGroupStore(),
                turns,
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                new RecordingScheduler(),
                new GitRunner());

        assertThat(service.activeTurns(50))
                .extracting(TaskTurn::id)
                .containsExactly("queued", "running");
    }

    @Test
    void stopCancelsQueuedTurnsBeforeStoppingSession()
    {
        Task task = task();
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveTask(task);
        List<String> events = new ArrayList<>();
        RecordingScheduler scheduler = new RecordingScheduler(events);
        RecordingStopRegistry registry = new RecordingStopRegistry(events);
        TaskService service = new TaskService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                registry,
                scheduler,
                new GitRunner());

        service.stop(task.id());

        assertThat(events).containsExactly(
                "cancel:" + task.id(),
                "stop",
                "evict:" + task.id());
    }

    @Test
    void stopCancelsQueuedTurnsWithoutLiveSession()
    {
        Task task = task();
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveTask(task);
        RecordingScheduler scheduler = new RecordingScheduler();
        ThrowingRegistry registry = new ThrowingRegistry();
        TaskService service = new TaskService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                registry,
                scheduler,
                new GitRunner());

        service.stop(task.id());

        assertThat(scheduler.cancelledTaskIds).containsExactly(task.id());
        assertThat(registry.used).isFalse();
    }

    @Test
    void deleteCancelsQueuedTurnsBeforeDeletingTask()
    {
        Task task = task("task-1", TaskStatus.COMPLETED);
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveTask(task);
        RecordingScheduler scheduler = new RecordingScheduler();
        TaskService service = new TaskService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                scheduler,
                new GitRunner());

        service.delete(task.id());

        assertThat(scheduler.cancelledTaskIds).containsExactly(task.id());
        assertThat(store.findTaskById(task.id())).isEmpty();
    }

    private record QueuedRequest(Task task, String input) {}

    private static final class RecordingScheduler
            implements TaskTurnScheduler
    {
        private final List<QueuedRequest> requests = new ArrayList<>();
        private final List<String> cancelledTaskIds = new ArrayList<>();
        private final List<String> events;

        private RecordingScheduler()
        {
            this(new ArrayList<>());
        }

        private RecordingScheduler(List<String> events)
        {
            this.events = events;
        }

        @Override
        public String enqueueTurn(Task task, String input)
        {
            requests.add(new QueuedRequest(task, input));
            return "turn-" + requests.size();
        }

        @Override
        public int cancelQueuedTurns(String taskId)
        {
            cancelledTaskIds.add(taskId);
            events.add("cancel:" + taskId);
            return 0;
        }
    }

    private static final class RecordingStopRegistry
            extends TaskSessionRegistry
    {
        private final List<String> events;
        private final RecordingStopSession session;

        private RecordingStopRegistry(List<String> events)
        {
            super(
                    new InMemoryTaskStore(),
                    new StreamJsonParser(new ObjectMapper()),
                    new ObjectMapper(),
                    new McpPermissionGate(),
                    Executors.newSingleThreadExecutor(),
                    CheckpointTrigger.NOOP);
            this.events = events;
            this.session = new RecordingStopSession(events);
        }

        @Override
        public AgentSession getOrCreate(Task task)
        {
            return session;
        }

        @Override
        public Optional<AgentSession> find(String taskId)
        {
            return Optional.of(session);
        }

        @Override
        public void evict(String taskId)
        {
            events.add("evict:" + taskId);
        }
    }

    private static final class RecordingStopSession
            implements AgentSession
    {
        private final List<String> events;

        private RecordingStopSession(List<String> events)
        {
            this.events = events;
        }

        @Override
        public String id()
        {
            return "task-1";
        }

        @Override
        public TaskKind kind()
        {
            return TaskKind.CLI_AGENT;
        }

        @Override
        public String provider()
        {
            return "claude-code";
        }

        @Override
        public String model()
        {
            return "claude-sonnet-4.6";
        }

        @Override
        public String workingDir()
        {
            return "/tmp/work";
        }

        @Override
        public String branchName()
        {
            return "main";
        }

        @Override
        public TaskStatus status()
        {
            return TaskStatus.IDLE;
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
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void interrupt() {}

        @Override
        public void pause() {}

        @Override
        public void resume() {}

        @Override
        public void stop()
        {
            events.add("stop");
        }

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

    private static final class ThrowingRegistry
            extends TaskSessionRegistry
    {
        private boolean used;

        private ThrowingRegistry()
        {
            super(
                    new InMemoryTaskStore(),
                    new StreamJsonParser(new ObjectMapper()),
                    new ObjectMapper(),
                    new McpPermissionGate(),
                    Executors.newSingleThreadExecutor(),
                    CheckpointTrigger.NOOP);
        }

        @Override
        public AgentSession getOrCreate(Task task)
        {
            used = true;
            throw new AssertionError("TaskService should use the scheduler");
        }
    }

    private static final class EmptyTaskGroupStore
            implements TaskGroupStore
    {
        @Override
        public void saveGroup(TaskGroup group) {}

        @Override
        public Optional<TaskGroup> findGroupById(String id)
        {
            return Optional.empty();
        }

        @Override
        public List<TaskGroup> listGroups()
        {
            return List.of();
        }

        @Override
        public void deleteGroup(String id) {}

        @Override
        public void addMember(String taskId, String groupId) {}

        @Override
        public void removeMember(String taskId, String groupId) {}

        @Override
        public List<TaskGroupMembership> listMembers(String groupId)
        {
            return List.of();
        }

        @Override
        public List<TaskGroupMembership> listMemberships(String taskId)
        {
            return List.of();
        }

        @Override
        public List<TaskGroupMembership> listAllMemberships()
        {
            return List.of();
        }

        @Override
        public long countMembers(String groupId)
        {
            return 0;
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
                    .sorted(taskHistoryOrder())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<TaskTurn> listTurnsByTaskId(String taskId, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> turn.taskId().equals(taskId))
                    .sorted(taskHistoryOrder())
                    .limit(limit)
                    .toList();
        }

        private static Comparator<TaskTurn> turnOrder()
        {
            return Comparator.comparing(TaskTurn::createdAt)
                    .thenComparing(TaskTurn::id);
        }

        private static Comparator<TaskTurn> taskHistoryOrder()
        {
            return Comparator.comparing(TaskTurn::createdAt)
                    .thenComparing(TaskTurn::id)
                    .reversed();
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
        return turn(id, taskId, TaskTurnStatus.QUEUED, createdAt);
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

    private static TaskTurnEvent turnEvent(String id, String turnId, String taskId, Instant createdAt)
    {
        return new TaskTurnEvent(
                id,
                turnId,
                taskId,
                TaskTurnEventType.TURN_QUEUED,
                createdAt,
                /* message */ null);
    }

    private static Task task()
    {
        return task("task-1");
    }

    private static Task task(String id)
    {
        return task(id, TaskStatus.IDLE);
    }

    private static Task task(String id, TaskStatus status)
    {
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        Task task = new Task(
                id,
                TaskKind.CLI_AGENT,
                "claude-code",
                /* agentSessionId */ null,
                "Fix tests",
                status,
                "/tmp/work",
                "main",
                "claude-sonnet-4.6",
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
        return task;
    }
}
