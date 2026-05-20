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

import java.nio.file.Path;
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
                new GitRunner(),
                noopWorktreeService());

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
                new GitRunner(),
                noopWorktreeService());

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
                new GitRunner(),
                noopWorktreeService());

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
                new GitRunner(),
                noopWorktreeService());

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
                new GitRunner(),
                noopWorktreeService());

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
                new GitRunner(),
                noopWorktreeService());

        assertThat(service.turnEvents(task.id()))
                .extracting(TaskTurnEvent::id)
                .containsExactly("event-3", "event-1");
        assertThat(registry.used).isFalse();
    }

    @Test
    void turnEventsUseStableTieBreakerForMatchingTimestamps()
    {
        Task task = task();
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveTask(task);
        InMemoryTaskTurnEventStore turnEvents = new InMemoryTaskTurnEventStore();
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        turnEvents.appendEvent(turnEvent("event-a", "turn-1", task.id(), now));
        turnEvents.appendEvent(turnEvent("event-c", "turn-1", task.id(), now));
        turnEvents.appendEvent(turnEvent("event-b", "turn-1", task.id(), now.minusSeconds(1)));
        ThrowingRegistry registry = new ThrowingRegistry();
        TaskService service = new TaskService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                turnEvents,
                registry,
                new RecordingScheduler(),
                new GitRunner(),
                noopWorktreeService());

        assertThat(service.turnEvents(task.id()))
                .extracting(TaskTurnEvent::id)
                .containsExactly("event-c", "event-a", "event-b");
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
                new GitRunner(),
                noopWorktreeService());

        assertThat(service.activeTurns(50))
                .extracting(TaskTurn::id)
                .containsExactly("queued", "running");
    }

    @Test
    void listByStatusReturnsEmptyForNonPositiveLimit()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveTask(task("task-1"));
        TaskService service = new TaskService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                new RecordingScheduler(),
                new GitRunner(),
                noopWorktreeService());

        assertThat(service.listByStatus(TaskStatus.IDLE, 0)).isEmpty();
        assertThat(service.listByStatus(TaskStatus.IDLE, -1)).isEmpty();
    }

    @Test
    void listByGroupReturnsEmptyForNonPositiveLimit()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        Task task = task("task-1");
        store.saveTask(task);
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        TaskService service = new TaskService(
                store,
                new EmptyTaskGroupStore(List.of(new TaskGroupMembership(task.id(), "group-1", now))),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                new RecordingScheduler(),
                new GitRunner(),
                noopWorktreeService());

        assertThat(service.listByGroup("group-1", 0)).isEmpty();
        assertThat(service.listByGroup("group-1", -1)).isEmpty();
    }

    @Test
    void createGroupDeduplicatesInitialTaskIdsBeforeCapCheck()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveTask(task("task-1"));
        store.saveTask(task("task-2"));
        EmptyTaskGroupStore groups = new EmptyTaskGroupStore();
        TaskService service = new TaskService(
                store,
                groups,
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                new RecordingScheduler(),
                new GitRunner(),
                noopWorktreeService());

        TaskGroup group = service.createGroup(new TaskService.NewGroupRequest(
                "Backend",
                "B",
                "blue",
                1,
                List.of("task-1", "task-1", "task-2", "task-2", "task-2")));

        assertThat(groups.listMembers(group.id()))
                .extracting(TaskGroupMembership::taskId)
                .containsExactly("task-1", "task-2");
    }

    @Test
    void createDeduplicatesInitialGroupIds()
    {
        EmptyTaskGroupStore groups = new EmptyTaskGroupStore();
        groups.saveGroup(group("group-1"));
        TaskService service = new TaskService(
                new InMemoryTaskStore(),
                groups,
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                new RecordingScheduler(),
                new GitRunner(),
                noopWorktreeService());

        Task task = service.create(new TaskService.NewTaskRequest(
                TaskKind.CLI_AGENT,
                "claude-code",
                "claude-sonnet-4.6",
                "Fix tests",
                "/tmp/work",
                "main",
                /* initialPrompt */ null,
                "{}",
                List.of("group-1", "group-1"),
                "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null));

        assertThat(groups.listMembers("group-1"))
                .extracting(TaskGroupMembership::taskId)
                .containsExactly(task.id());
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
                new GitRunner(),
                noopWorktreeService());

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
                new GitRunner(),
                noopWorktreeService());

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
                new GitRunner(),
                noopWorktreeService());

        service.delete(task.id());

        assertThat(scheduler.cancelledTaskIds).containsExactly(task.id());
        assertThat(store.findTaskById(task.id())).isEmpty();
    }

    @Test
    void createStoresWorktreeHandleAndQueuesAgentAgainstIt()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        RecordingScheduler scheduler = new RecordingScheduler();
        RecordingWorktreeService worktrees = new RecordingWorktreeService(Optional.of(
                new WorktreeService.WorktreeHandle(
                        Path.of("/tmp/repo/.bytequay/worktrees/dev/task-1"),
                        "dev/task-1")));
        TaskService service = new TaskService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                scheduler,
                new GitRunner(),
                worktrees);

        Task task = service.create(new TaskService.NewTaskRequest(
                TaskKind.CLI_AGENT,
                "claude-code",
                "claude-sonnet-4.6",
                "Fix tests",
                "/tmp/repo",
                "main",
                "please fix",
                "{}",
                List.of(),
                "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null));

        assertThat(task.worktreePath()).isEqualTo("/tmp/repo/.bytequay/worktrees/dev/task-1");
        assertThat(task.localBranch()).isEqualTo("dev/task-1");
        assertThat(task.agentCwd()).isEqualTo(task.worktreePath());
        assertThat(scheduler.requests)
                .extracting(request -> request.task().agentCwd())
                .containsExactly(task.worktreePath());
        assertThat(worktrees.createRequests).containsExactly(new WorktreeCreateRequest(
                Path.of("/tmp/repo"),
                task.id(),
                "Fix tests"));
    }

    @Test
    void deleteRemovesTaskWorktreeBeforeDeletingRow()
    {
        Task task = taskWithWorktree("task-1");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveTask(task);
        RecordingWorktreeService worktrees = new RecordingWorktreeService(Optional.empty());
        TaskService service = new TaskService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                new RecordingScheduler(),
                new GitRunner(),
                worktrees);

        service.delete(task.id());

        assertThat(worktrees.removeRequests).containsExactly(new WorktreeRemoveRequest(
                Path.of(task.workingDir()),
                task.worktreePath(),
                task.localBranch()));
        assertThat(store.findTaskById(task.id())).isEmpty();
    }

    @Test
    void taskDiffAndCommitViewsUseAgentCwd()
    {
        Task task = taskWithWorktree("task-1");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveTask(task);
        RecordingGitRunner git = new RecordingGitRunner();
        TaskService service = new TaskService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                new RecordingScheduler(),
                git,
                noopWorktreeService());

        service.listWorkingChanges(task.id());
        service.getWorkingDiff(task.id(), "src/App.java");
        service.listTaskCommits(task.id());
        service.listCommitFiles(task.id(), "abc123");
        service.getCommitDiff(task.id(), "abc123", "src/App.java");

        Path expected = Path.of(task.agentCwd());
        assertThat(git.workingTreeFilesPaths).containsExactly(expected);
        assertThat(git.workingTreeDiffPaths).containsExactly(expected);
        assertThat(git.listCommitsSincePaths).containsExactly(expected);
        assertThat(git.commitFilesPaths).containsExactly(expected);
        assertThat(git.commitDiffPaths).containsExactly(expected);
    }

    private record WorktreeCreateRequest(Path repoRoot, String sessionId, String title) {}

    private record WorktreeRemoveRequest(Path repoRoot, String worktreePath, String localBranch) {}

    private record QueuedRequest(Task task, String input) {}

    private static WorktreeService noopWorktreeService()
    {
        return new RecordingWorktreeService(Optional.empty());
    }

    private static final class RecordingWorktreeService
            extends WorktreeService
    {
        private final Optional<WorktreeHandle> createResult;
        private final List<WorktreeCreateRequest> createRequests = new ArrayList<>();
        private final List<WorktreeRemoveRequest> removeRequests = new ArrayList<>();

        private RecordingWorktreeService(Optional<WorktreeHandle> createResult)
        {
            super(new GitRunner());
            this.createResult = createResult;
        }

        @Override
        public Optional<WorktreeHandle> create(Path repoRoot, String sessionId, String title)
        {
            createRequests.add(new WorktreeCreateRequest(repoRoot, sessionId, title));
            return createResult;
        }

        @Override
        public void remove(Path repoRoot, String worktreePath, String localBranch)
        {
            removeRequests.add(new WorktreeRemoveRequest(repoRoot, worktreePath, localBranch));
        }
    }

    private static final class RecordingGitRunner
            extends GitRunner
    {
        private final List<Path> workingTreeFilesPaths = new ArrayList<>();
        private final List<Path> workingTreeDiffPaths = new ArrayList<>();
        private final List<Path> listCommitsSincePaths = new ArrayList<>();
        private final List<Path> commitFilesPaths = new ArrayList<>();
        private final List<Path> commitDiffPaths = new ArrayList<>();

        @Override
        public List<GitRunner.WorkingTreeFile> workingTreeFiles(Path workingDir)
        {
            workingTreeFilesPaths.add(workingDir);
            return List.of();
        }

        @Override
        public String workingTreeFileDiff(Path workingDir, String path, int maxBytes)
        {
            workingTreeDiffPaths.add(workingDir);
            return "";
        }

        @Override
        public List<GitRunner.CommitEntry> listCommitsSince(Path workingDir, Instant since, int limit)
        {
            listCommitsSincePaths.add(workingDir);
            return List.of();
        }

        @Override
        public List<GitRunner.CommitFileChange> commitFiles(Path workingDir, String sha)
        {
            commitFilesPaths.add(workingDir);
            return List.of();
        }

        @Override
        public String commitFileDiff(Path workingDir, String sha, String path, int maxBytes)
        {
            commitDiffPaths.add(workingDir);
            return "";
        }
    }

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
        private final Map<String, TaskGroup> groups = new LinkedHashMap<>();
        private final List<TaskGroupMembership> memberships = new ArrayList<>();

        private EmptyTaskGroupStore()
        {
            this(List.of());
        }

        private EmptyTaskGroupStore(List<TaskGroupMembership> memberships)
        {
            this.memberships.addAll(memberships);
        }

        @Override
        public void saveGroup(TaskGroup group)
        {
            groups.put(group.id(), group);
        }

        @Override
        public Optional<TaskGroup> findGroupById(String id)
        {
            return Optional.ofNullable(groups.get(id));
        }

        @Override
        public List<TaskGroup> listGroups()
        {
            return List.copyOf(groups.values());
        }

        @Override
        public void deleteGroup(String id)
        {
            groups.remove(id);
            memberships.removeIf(membership -> membership.groupId().equals(id));
        }

        @Override
        public void addMember(String taskId, String groupId)
        {
            boolean exists = memberships.stream()
                    .anyMatch(membership -> membership.taskId().equals(taskId)
                            && membership.groupId().equals(groupId));
            if (!exists) {
                memberships.add(new TaskGroupMembership(taskId, groupId, Instant.EPOCH));
            }
        }

        @Override
        public void removeMember(String taskId, String groupId)
        {
            memberships.removeIf(membership -> membership.taskId().equals(taskId)
                    && membership.groupId().equals(groupId));
        }

        @Override
        public List<TaskGroupMembership> listMembers(String groupId)
        {
            return memberships.stream()
                    .filter(membership -> membership.groupId().equals(groupId))
                    .toList();
        }

        @Override
        public List<TaskGroupMembership> listMemberships(String taskId)
        {
            return memberships.stream()
                    .filter(membership -> membership.taskId().equals(taskId))
                    .toList();
        }

        @Override
        public List<TaskGroupMembership> listAllMemberships()
        {
            return memberships;
        }

        @Override
        public long countMembers(String groupId)
        {
            return listMembers(groupId).size();
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
                    .sorted(eventHistoryOrder())
                    .limit(limit)
                    .toList();
        }

        private static Comparator<TaskTurnEvent> eventHistoryOrder()
        {
            return Comparator.comparing(TaskTurnEvent::createdAt)
                    .thenComparing(TaskTurnEvent::id)
                    .reversed();
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

    private static TaskGroup group(String id)
    {
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        return new TaskGroup(id, "Group " + id, "G", "blue", 1, now, now);
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
        return new Task(
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
                /* linkedIssueNumber */ null,
                /* worktreePath */ null,
                /* localBranch */ null);
    }

    private static Task taskWithWorktree(String id)
    {
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        return new Task(
                id,
                TaskKind.CLI_AGENT,
                "claude-code",
                /* agentSessionId */ null,
                "Fix tests",
                TaskStatus.COMPLETED,
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
                /* endedAt */ now,
                /* errorMessage */ null,
                "{}",
                "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null,
                "/tmp/work/.bytequay/worktrees/dev/task-1",
                "dev/task-1");
    }
}
