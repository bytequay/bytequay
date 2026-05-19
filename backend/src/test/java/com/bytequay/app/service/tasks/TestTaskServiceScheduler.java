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
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.TaskGroup;
import com.bytequay.app.domain.TaskGroupMembership;
import com.bytequay.app.domain.TaskKind;
import com.bytequay.app.domain.TaskMessage;
import com.bytequay.app.domain.TaskResourceLane;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.TaskTurn;
import com.bytequay.app.domain.TaskTurnStatus;
import com.bytequay.app.repository.TaskGroupStore;
import com.bytequay.app.repository.TaskStore;
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
import java.util.concurrent.Executors;

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
                registry,
                scheduler,
                new GitRunner());

        assertThat(service.turns(task.id()))
                .extracting(TaskTurn::id)
                .containsExactly("turn-3", "turn-1");
        assertThat(registry.used).isFalse();
    }

    private record QueuedRequest(Task task, String input) {}

    private static final class RecordingScheduler
            implements TaskTurnScheduler
    {
        private final List<QueuedRequest> requests = new ArrayList<>();

        @Override
        public String enqueueTurn(Task task, String input)
        {
            requests.add(new QueuedRequest(task, input));
            return "turn-" + requests.size();
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
                    Executors.newSingleThreadExecutor());
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
                    .sorted(Comparator.comparing(TaskTurn::createdAt))
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
    }

    private static TaskTurn turn(String id, String taskId, Instant createdAt)
    {
        return new TaskTurn(
                id,
                taskId,
                TaskResourceLane.CLI,
                TaskTurnStatus.QUEUED,
                "input",
                createdAt,
                createdAt,
                /* startedAt */ null,
                /* finishedAt */ null,
                /* errorMessage */ null);
    }

    private static Task task()
    {
        return task("task-1");
    }

    private static Task task(String id)
    {
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        Task task = new Task(
                id,
                TaskKind.CLI_AGENT,
                "claude-code",
                /* agentSessionId */ null,
                "Fix tests",
                TaskStatus.IDLE,
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
        assertThat(task.status()).isEqualTo(TaskStatus.IDLE);
        return task;
    }
}
