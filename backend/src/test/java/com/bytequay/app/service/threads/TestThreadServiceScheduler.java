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

import com.bytequay.app.domain.AgentMetrics;
import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFile;
import com.bytequay.app.domain.ThreadGroup;
import com.bytequay.app.domain.ThreadGroupMembership;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnEvent;
import com.bytequay.app.domain.ThreadTurnEventType;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.repository.ThreadGroupStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnEventStore;
import com.bytequay.app.repository.ThreadTurnStore;
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

class TestThreadServiceScheduler
{
    @Test
    void createQueuesInitialPromptThroughScheduler()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        RecordingScheduler scheduler = new RecordingScheduler();
        ThrowingRegistry registry = new ThrowingRegistry();
        ThreadService service = new ThreadService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                registry,
                scheduler,
                new GitRunner(),
                noopWorktreeService());

        service.create(new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT,
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

        assertThat(store.threads).hasSize(1);
        assertThat(scheduler.requests).hasSize(1);
        assertThat(scheduler.requests.get(0).thread()).isEqualTo(store.threads.values().iterator().next());
        assertThat(scheduler.requests.get(0).input()).isEqualTo("please fix");
        assertThat(registry.used).isFalse();
    }

    @Test
    void createWithoutPromptDoesNotStartSession()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        RecordingScheduler scheduler = new RecordingScheduler();
        ThrowingRegistry registry = new ThrowingRegistry();
        ThreadService service = new ThreadService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                registry,
                scheduler,
                new GitRunner(),
                noopWorktreeService());

        service.create(new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT,
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
        Thread thread = thread();
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        RecordingScheduler scheduler = new RecordingScheduler();
        ThrowingRegistry registry = new ThrowingRegistry();
        ThreadService service = new ThreadService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                registry,
                scheduler,
                new GitRunner(),
                noopWorktreeService());

        String turnId = service.send(thread.id(), "next");

        assertThat(turnId).isEqualTo("turn-1");
        assertThat(scheduler.requests).containsExactly(new QueuedRequest(thread, "next"));
        assertThat(registry.used).isFalse();
    }

    @Test
    void turnsReturnDurableHistoryForTaskOnly()
    {
        Thread thread = thread();
        Thread otherTask = thread("thread-2");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        store.saveThread(otherTask);
        InMemoryTaskTurnStore turns = new InMemoryTaskTurnStore();
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        turns.saveTurn(turn("turn-1", thread.id(), now.minusSeconds(10)));
        turns.saveTurn(turn("turn-2", otherTask.id(), now));
        turns.saveTurn(turn("turn-3", thread.id(), now.plusSeconds(10)));
        RecordingScheduler scheduler = new RecordingScheduler();
        ThrowingRegistry registry = new ThrowingRegistry();
        ThreadService service = new ThreadService(
                store,
                new EmptyTaskGroupStore(),
                turns,
                new InMemoryTaskTurnEventStore(),
                registry,
                scheduler,
                new GitRunner(),
                noopWorktreeService());

        assertThat(service.turns(thread.id()))
                .extracting(ThreadTurn::id)
                .containsExactly("turn-3", "turn-1");
        assertThat(registry.used).isFalse();
    }

    @Test
    void turnsUseStableTieBreakerForMatchingTimestamps()
    {
        Thread thread = thread();
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        InMemoryTaskTurnStore turns = new InMemoryTaskTurnStore();
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        turns.saveTurn(turn("turn-a", thread.id(), now));
        turns.saveTurn(turn("turn-c", thread.id(), now));
        turns.saveTurn(turn("turn-b", thread.id(), now.minusSeconds(1)));
        RecordingScheduler scheduler = new RecordingScheduler();
        ThrowingRegistry registry = new ThrowingRegistry();
        ThreadService service = new ThreadService(
                store,
                new EmptyTaskGroupStore(),
                turns,
                new InMemoryTaskTurnEventStore(),
                registry,
                scheduler,
                new GitRunner(),
                noopWorktreeService());

        assertThat(service.turns(thread.id()))
                .extracting(ThreadTurn::id)
                .containsExactly("turn-c", "turn-a", "turn-b");
        assertThat(registry.used).isFalse();
    }

    @Test
    void turnEventsReturnDurableHistoryForTaskOnly()
    {
        Thread thread = thread();
        Thread otherTask = thread("thread-2");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        store.saveThread(otherTask);
        InMemoryTaskTurnEventStore turnEvents = new InMemoryTaskTurnEventStore();
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        turnEvents.appendEvent(turnEvent("event-1", "turn-1", thread.id(), now.minusSeconds(10)));
        turnEvents.appendEvent(turnEvent("event-2", "turn-2", otherTask.id(), now));
        turnEvents.appendEvent(turnEvent("event-3", "turn-3", thread.id(), now.plusSeconds(10)));
        ThrowingRegistry registry = new ThrowingRegistry();
        ThreadService service = new ThreadService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                turnEvents,
                registry,
                new RecordingScheduler(),
                new GitRunner(),
                noopWorktreeService());

        assertThat(service.turnEvents(thread.id()))
                .extracting(ThreadTurnEvent::id)
                .containsExactly("event-3", "event-1");
        assertThat(registry.used).isFalse();
    }

    @Test
    void turnEventsUseStableTieBreakerForMatchingTimestamps()
    {
        Thread thread = thread();
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        InMemoryTaskTurnEventStore turnEvents = new InMemoryTaskTurnEventStore();
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        turnEvents.appendEvent(turnEvent("event-a", "turn-1", thread.id(), now));
        turnEvents.appendEvent(turnEvent("event-c", "turn-1", thread.id(), now));
        turnEvents.appendEvent(turnEvent("event-b", "turn-1", thread.id(), now.minusSeconds(1)));
        ThrowingRegistry registry = new ThrowingRegistry();
        ThreadService service = new ThreadService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                turnEvents,
                registry,
                new RecordingScheduler(),
                new GitRunner(),
                noopWorktreeService());

        assertThat(service.turnEvents(thread.id()))
                .extracting(ThreadTurnEvent::id)
                .containsExactly("event-c", "event-a", "event-b");
        assertThat(registry.used).isFalse();
    }

    @Test
    void activeTurnsReturnQueuedAndRunningOnly()
    {
        InMemoryTaskTurnStore turns = new InMemoryTaskTurnStore();
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        turns.saveTurn(turn("queued", "thread-1", ThreadTurnStatus.QUEUED, now.minusSeconds(30)));
        turns.saveTurn(turn("completed", "thread-2", ThreadTurnStatus.COMPLETED, now.minusSeconds(20)));
        turns.saveTurn(turn("running", "thread-3", ThreadTurnStatus.RUNNING, now.minusSeconds(10)));
        ThreadService service = new ThreadService(
                new InMemoryTaskStore(),
                new EmptyTaskGroupStore(),
                turns,
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                new RecordingScheduler(),
                new GitRunner(),
                noopWorktreeService());

        assertThat(service.activeTurns(50))
                .extracting(ThreadTurn::id)
                .containsExactly("queued", "running");
    }

    @Test
    void listByStatusReturnsEmptyForNonPositiveLimit()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread("thread-1"));
        ThreadService service = new ThreadService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                new RecordingScheduler(),
                new GitRunner(),
                noopWorktreeService());

        assertThat(service.listByStatus(ThreadStatus.IDLE, 0)).isEmpty();
        assertThat(service.listByStatus(ThreadStatus.IDLE, -1)).isEmpty();
    }

    @Test
    void listByGroupReturnsEmptyForNonPositiveLimit()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        Thread thread = thread("thread-1");
        store.saveThread(thread);
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        ThreadService service = new ThreadService(
                store,
                new EmptyTaskGroupStore(List.of(new ThreadGroupMembership(thread.id(), "group-1", now))),
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
        store.saveThread(thread("thread-1"));
        store.saveThread(thread("thread-2"));
        EmptyTaskGroupStore groups = new EmptyTaskGroupStore();
        ThreadService service = new ThreadService(
                store,
                groups,
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                new RecordingScheduler(),
                new GitRunner(),
                noopWorktreeService());

        ThreadGroup group = service.createGroup(new ThreadService.NewGroupRequest(
                "Backend",
                "B",
                "blue",
                1,
                List.of("thread-1", "thread-1", "thread-2", "thread-2", "thread-2")));

        assertThat(groups.listMembers(group.id()))
                .extracting(ThreadGroupMembership::threadId)
                .containsExactly("thread-1", "thread-2");
    }

    @Test
    void createDeduplicatesInitialGroupIds()
    {
        EmptyTaskGroupStore groups = new EmptyTaskGroupStore();
        groups.saveGroup(group("group-1"));
        ThreadService service = new ThreadService(
                new InMemoryTaskStore(),
                groups,
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                new RecordingScheduler(),
                new GitRunner(),
                noopWorktreeService());

        Thread thread = service.create(new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT,
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
                .extracting(ThreadGroupMembership::threadId)
                .containsExactly(thread.id());
    }

    @Test
    void stopCancelsQueuedTurnsBeforeStoppingSession()
    {
        Thread thread = thread();
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        List<String> events = new ArrayList<>();
        RecordingScheduler scheduler = new RecordingScheduler(events);
        RecordingStopRegistry registry = new RecordingStopRegistry(events);
        ThreadService service = new ThreadService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                registry,
                scheduler,
                new GitRunner(),
                noopWorktreeService());

        service.stop(thread.id());

        assertThat(events).containsExactly(
                "cancel:" + thread.id(),
                "stop",
                "evict:" + thread.id());
    }

    @Test
    void stopCancelsQueuedTurnsWithoutLiveSession()
    {
        Thread thread = thread();
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        RecordingScheduler scheduler = new RecordingScheduler();
        ThrowingRegistry registry = new ThrowingRegistry();
        ThreadService service = new ThreadService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                registry,
                scheduler,
                new GitRunner(),
                noopWorktreeService());

        service.stop(thread.id());

        assertThat(scheduler.cancelledTaskIds).containsExactly(thread.id());
        assertThat(registry.used).isFalse();
    }

    @Test
    void deleteCancelsQueuedTurnsBeforeDeletingTask()
    {
        Thread thread = thread("thread-1", ThreadStatus.COMPLETED);
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        RecordingScheduler scheduler = new RecordingScheduler();
        ThreadService service = new ThreadService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                scheduler,
                new GitRunner(),
                noopWorktreeService());

        service.delete(thread.id());

        assertThat(scheduler.cancelledTaskIds).containsExactly(thread.id());
        assertThat(store.findThreadById(thread.id())).isEmpty();
    }

    @Test
    void createStoresWorktreeHandleAndQueuesAgentAgainstIt()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        RecordingScheduler scheduler = new RecordingScheduler();
        RecordingWorktreeService worktrees = new RecordingWorktreeService(Optional.of(
                new WorktreeService.WorktreeHandle(
                        Path.of("/tmp/repo/.bytequay/worktrees/dev/thread-1"),
                        "dev/thread-1")));
        ThreadService service = new ThreadService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                scheduler,
                new GitRunner(),
                worktrees);

        Thread thread = service.create(new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT,
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

        assertThat(thread.worktreePath()).isEqualTo("/tmp/repo/.bytequay/worktrees/dev/thread-1");
        assertThat(thread.localBranch()).isEqualTo("dev/thread-1");
        assertThat(thread.agentCwd()).isEqualTo(thread.worktreePath());
        assertThat(scheduler.requests)
                .extracting(request -> request.thread().agentCwd())
                .containsExactly(thread.worktreePath());
        assertThat(worktrees.createRequests).containsExactly(new WorktreeCreateRequest(
                Path.of("/tmp/repo"),
                thread.id(),
                "Fix tests"));
    }

    @Test
    void deleteRemovesTaskWorktreeBeforeDeletingRow()
    {
        Thread thread = threadWithWorktree("thread-1");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        RecordingWorktreeService worktrees = new RecordingWorktreeService(Optional.empty());
        ThreadService service = new ThreadService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                new RecordingScheduler(),
                new GitRunner(),
                worktrees);

        service.delete(thread.id());

        assertThat(worktrees.removeRequests).containsExactly(new WorktreeRemoveRequest(
                Path.of(thread.workingDir()),
                thread.worktreePath(),
                thread.localBranch()));
        assertThat(store.findThreadById(thread.id())).isEmpty();
    }

    @Test
    void threadDiffAndCommitViewsUseAgentCwd()
    {
        Thread thread = threadWithWorktree("thread-1");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        RecordingGitRunner git = new RecordingGitRunner();
        ThreadService service = new ThreadService(
                store,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                new RecordingScheduler(),
                git,
                noopWorktreeService());

        service.listWorkingChanges(thread.id());
        service.getWorkingDiff(thread.id(), "src/App.java");
        service.listTaskCommits(thread.id());
        service.listCommitFiles(thread.id(), "abc123");
        service.getCommitDiff(thread.id(), "abc123", "src/App.java");

        Path expected = Path.of(thread.agentCwd());
        assertThat(git.workingTreeFilesPaths).containsExactly(expected);
        assertThat(git.workingTreeDiffPaths).containsExactly(expected);
        assertThat(git.listCommitsSincePaths).containsExactly(expected);
        assertThat(git.commitFilesPaths).containsExactly(expected);
        assertThat(git.commitDiffPaths).containsExactly(expected);
    }

    private record WorktreeCreateRequest(Path repoRoot, String sessionId, String title) {}

    private record WorktreeRemoveRequest(Path repoRoot, String worktreePath, String localBranch) {}

    private record QueuedRequest(Thread thread, String input) {}

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
            implements ThreadTurnScheduler
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
        public String enqueueTurn(Thread thread, String input)
        {
            requests.add(new QueuedRequest(thread, input));
            return "turn-" + requests.size();
        }

        @Override
        public int cancelQueuedTurns(String threadId)
        {
            cancelledTaskIds.add(threadId);
            events.add("cancel:" + threadId);
            return 0;
        }
    }

    private static final class RecordingStopRegistry
            extends ThreadRegistry
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
        public ThreadAgent getOrCreate(Thread thread)
        {
            return session;
        }

        @Override
        public Optional<ThreadAgent> find(String threadId)
        {
            return Optional.of(session);
        }

        @Override
        public void evict(String threadId)
        {
            events.add("evict:" + threadId);
        }
    }

    private static final class RecordingStopSession
            implements ThreadAgent
    {
        private final List<String> events;

        private RecordingStopSession(List<String> events)
        {
            this.events = events;
        }

        @Override
        public String id()
        {
            return "thread-1";
        }

        @Override
        public ThreadKind kind()
        {
            return ThreadKind.CLI_AGENT;
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
        public ThreadStatus status()
        {
            return ThreadStatus.IDLE;
        }

        @Override
        public AgentMetrics metrics()
        {
            return new AgentMetrics(0, 0, 0, 0, 0, 0);
        }

        @Override
        public List<ThreadMessage> history()
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
            extends ThreadRegistry
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
        public ThreadAgent getOrCreate(Thread thread)
        {
            used = true;
            throw new AssertionError("ThreadService should use the scheduler");
        }
    }

    private static final class EmptyTaskGroupStore
            implements ThreadGroupStore
    {
        private final Map<String, ThreadGroup> groups = new LinkedHashMap<>();
        private final List<ThreadGroupMembership> memberships = new ArrayList<>();

        private EmptyTaskGroupStore()
        {
            this(List.of());
        }

        private EmptyTaskGroupStore(List<ThreadGroupMembership> memberships)
        {
            this.memberships.addAll(memberships);
        }

        @Override
        public void saveGroup(ThreadGroup group)
        {
            groups.put(group.id(), group);
        }

        @Override
        public Optional<ThreadGroup> findGroupById(String id)
        {
            return Optional.ofNullable(groups.get(id));
        }

        @Override
        public List<ThreadGroup> listGroups()
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
        public void addMember(String threadId, String groupId)
        {
            boolean exists = memberships.stream()
                    .anyMatch(membership -> membership.threadId().equals(threadId)
                            && membership.groupId().equals(groupId));
            if (!exists) {
                memberships.add(new ThreadGroupMembership(threadId, groupId, Instant.EPOCH));
            }
        }

        @Override
        public void removeMember(String threadId, String groupId)
        {
            memberships.removeIf(membership -> membership.threadId().equals(threadId)
                    && membership.groupId().equals(groupId));
        }

        @Override
        public List<ThreadGroupMembership> listMembers(String groupId)
        {
            return memberships.stream()
                    .filter(membership -> membership.groupId().equals(groupId))
                    .toList();
        }

        @Override
        public List<ThreadGroupMembership> listMemberships(String threadId)
        {
            return memberships.stream()
                    .filter(membership -> membership.threadId().equals(threadId))
                    .toList();
        }

        @Override
        public List<ThreadGroupMembership> listAllMemberships()
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
            implements ThreadStore
    {
        private final Map<String, Thread> threads = new LinkedHashMap<>();

        @Override
        public void saveThread(Thread thread)
        {
            threads.put(thread.id(), thread);
        }

        @Override
        public Optional<Thread> findThreadById(String id)
        {
            return Optional.ofNullable(threads.get(id));
        }

        @Override
        public void deleteThread(String id)
        {
            threads.remove(id);
        }

        @Override
        public List<Thread> listTasksByStatus(ThreadStatus status, int limit)
        {
            return threads.values().stream()
                    .filter(thread -> thread.status() == status)
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<Thread> listTasksByIds(Collection<String> ids)
        {
            return threads.values().stream()
                    .filter(thread -> ids.contains(thread.id()))
                    .toList();
        }

        @Override
        public void appendMessage(ThreadMessage message) {}

        @Override
        public List<ThreadMessage> listMessages(String threadId)
        {
            return List.of();
        }

        @Override
        public void recordFile(ThreadFile file) {}

        @Override
        public List<ThreadFile> listFiles(String threadId)
        {
            return List.of();
        }
    }

    private static final class InMemoryTaskTurnStore
            implements ThreadTurnStore
    {
        private final Map<String, ThreadTurn> turns = new LinkedHashMap<>();

        @Override
        public void saveTurn(ThreadTurn turn)
        {
            turns.put(turn.id(), turn);
        }

        @Override
        public Optional<ThreadTurn> findTurnById(String id)
        {
            return Optional.ofNullable(turns.get(id));
        }

        @Override
        public List<ThreadTurn> listTurnsByStatus(ThreadTurnStatus status, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> turn.status() == status)
                    .sorted(turnOrder())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ThreadTurn> listTurnsByStatusAfter(ThreadTurnStatus status, Instant createdAfter, String idAfter, int limit)
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
        public List<ThreadTurn> listTurnsByStatuses(Collection<ThreadTurnStatus> statuses, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> statuses.contains(turn.status()))
                    .sorted(turnOrder())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ThreadTurn> listTurnsByTaskIdAndStatus(String threadId, ThreadTurnStatus status, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> turn.threadId().equals(threadId))
                    .filter(turn -> turn.status() == status)
                    .sorted(threadHistoryOrder())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ThreadTurn> listTurnsByTaskId(String threadId, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> turn.threadId().equals(threadId))
                    .sorted(threadHistoryOrder())
                    .limit(limit)
                    .toList();
        }

        private static Comparator<ThreadTurn> turnOrder()
        {
            return Comparator.comparing(ThreadTurn::createdAt)
                    .thenComparing(ThreadTurn::id);
        }

        private static Comparator<ThreadTurn> threadHistoryOrder()
        {
            return Comparator.comparing(ThreadTurn::createdAt)
                    .thenComparing(ThreadTurn::id)
                    .reversed();
        }
    }

    private static final class InMemoryTaskTurnEventStore
            implements ThreadTurnEventStore
    {
        private final Map<String, ThreadTurnEvent> events = new LinkedHashMap<>();

        @Override
        public void appendEvent(ThreadTurnEvent event)
        {
            events.put(event.id(), event);
        }

        @Override
        public List<ThreadTurnEvent> listEventsByTaskId(String threadId, int limit)
        {
            return events.values().stream()
                    .filter(event -> event.threadId().equals(threadId))
                    .sorted(eventHistoryOrder())
                    .limit(limit)
                    .toList();
        }

        private static Comparator<ThreadTurnEvent> eventHistoryOrder()
        {
            return Comparator.comparing(ThreadTurnEvent::createdAt)
                    .thenComparing(ThreadTurnEvent::id)
                    .reversed();
        }
    }

    private static ThreadTurn turn(String id, String threadId, Instant createdAt)
    {
        return turn(id, threadId, ThreadTurnStatus.QUEUED, createdAt);
    }

    private static ThreadTurn turn(String id, String threadId, ThreadTurnStatus status, Instant createdAt)
    {
        return new ThreadTurn(
                id,
                threadId,
                ThreadResourceLane.CLI,
                status,
                "input",
                createdAt,
                createdAt,
                /* startedAt */ null,
                /* finishedAt */ null,
                /* errorMessage */ null);
    }

    private static ThreadTurnEvent turnEvent(String id, String turnId, String threadId, Instant createdAt)
    {
        return new ThreadTurnEvent(
                id,
                turnId,
                threadId,
                ThreadTurnEventType.TURN_QUEUED,
                createdAt,
                /* message */ null);
    }

    private static ThreadGroup group(String id)
    {
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        return new ThreadGroup(id, "Group " + id, "G", "blue", 1, now, now);
    }

    private static Thread thread()
    {
        return thread("thread-1");
    }

    private static Thread thread(String id)
    {
        return thread(id, ThreadStatus.IDLE);
    }

    private static Thread thread(String id, ThreadStatus status)
    {
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        return new Thread(
                id,
                ThreadKind.CLI_AGENT,
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

    private static Thread threadWithWorktree(String id)
    {
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        return new Thread(
                id,
                ThreadKind.CLI_AGENT,
                "claude-code",
                /* agentSessionId */ null,
                "Fix tests",
                ThreadStatus.COMPLETED,
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
                "/tmp/work/.bytequay/worktrees/dev/thread-1",
                "dev/thread-1");
    }
}
