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
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFile;
import com.bytequay.app.domain.ThreadFlow;
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
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.WorktreeLease;
import com.bytequay.app.repository.IdSequenceStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadGroupStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnEventStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.WorktreeLeaseStore;
import com.bytequay.app.service.concepts.ConceptRegistry;
import com.bytequay.app.service.ids.IdGenerator;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.skills.RoleSkillService;
import com.bytequay.app.service.workspaces.WorkspaceDataPurger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestThreadServiceScheduler
{
    @Test
    void permissionBudgetStaysOnTheStageAgentThatRaisedThePrompt()
    {
        McpPermissionGate gate = new McpPermissionGate();
        gate.register("call-1", "Bash", "stage-1");
        ThreadRegistry registry = Mockito.mock(ThreadRegistry.class);
        ThreadAgent stageAgent = Mockito.mock(ThreadAgent.class);
        Mockito.when(registry.findByAgentKey("brain-1", "stage-1"))
                .thenReturn(Optional.of(stageAgent));
        Mockito.when(stageAgent.decide("call-1", PermissionDecision.ALLOW))
                .thenAnswer(invocation -> gate.decide("call-1", PermissionDecision.ALLOW));
        Mockito.when(stageAgent.tryConsumeToolBudget("Bash"))
                .thenReturn(OptionalInt.of(4));
        ThreadService service = new ThreadService(
                Mockito.mock(ThreadStore.class),
                Mockito.mock(TaskStore.class),
                Mockito.mock(ThreadGroupStore.class),
                Mockito.mock(ThreadTurnStore.class),
                Mockito.mock(ThreadTurnEventStore.class),
                registry,
                gate,
                new RecordingScheduler(),
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class),
                Mockito.mock(CheckpointSummariser.class));

        assertThat(service.decide(
                "brain-1", "call-1", PermissionDecision.ALLOW, "Bash", 5)).isTrue();
        assertThat(gate.agentKeyFor("call-1")).isNull();
        Mockito.verify(stageAgent).decide("call-1", PermissionDecision.ALLOW);
        Mockito.verify(stageAgent).grantToolBudget("Bash", 5);

        assertThat(service.tryConsumeToolBudget("brain-1", "stage-1", "Bash"))
                .hasValue(4);
        Mockito.verify(registry, Mockito.times(2))
                .findByAgentKey("brain-1", "stage-1");
    }

    @Test
    void createDoesNotEnqueueTrunkTurnFromInitialPrompt()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        RecordingScheduler scheduler = new RecordingScheduler();
        ThrowingRegistry registry = new ThrowingRegistry();
        ThreadService service = new ThreadService(
                store,
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                registry,
                Mockito.mock(McpPermissionGate.class),
                scheduler,
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

        // initialPrompt feeds title derivation but is treated as
        // context the create dialog will stage in the trunk composer,
        // not as a turn to fire at the agent.
        Thread created = service.create(new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT,
                "claude-code",
                "claude-sonnet-4.6",
                /* title */ null,
                "/tmp/work",
                "main",
                "please fix the broken tests",
                List.of(),
                "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null,
                /* flow */ null, "ws-default", /* workModel */ null)
                .withDescription("Focused remark for this trunk"));

        assertThat(store.threads).hasSize(1);
        assertThat(scheduler.requests).isEmpty();
        assertThat(registry.used).isFalse();
        assertThat(created.title()).isEqualTo("Please fix the broken tests");
        assertThat(created.description()).isEqualTo("Focused remark for this trunk");
        // A NewTaskRequest with no flow defaults to BUILD per the
        // V74 column default and the design's "BUILD threads are the
        // overwhelming majority" guidance.
        assertThat(store.threads.values().iterator().next().flow())
                .isEqualTo(ThreadFlow.BUILD);
    }

    @Test
    void createDisambiguatesTitleCollisionsWithinAWorkspaceOnly()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        ThreadService service = new ThreadService(
                store,
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                Mockito.mock(McpPermissionGate.class),
                new RecordingScheduler(),
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

        NewThreadRequestBuilder request = title -> new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT, "claude-code", "claude-sonnet-4.6", title,
                "/tmp/work", "main", /* initialPrompt */ null, List.of(), "DEVELOP",
                null, null, null, "ws-default", null);

        Thread first = service.create(request.of("Fix the broken tests"));
        Thread second = service.create(request.of("Fix the broken tests"));
        Thread third = service.create(request.of("Fix the broken tests"));
        assertThat(first.title()).isEqualTo("Fix the broken tests");
        assertThat(second.title()).isEqualTo("Fix the broken tests (2)");
        assertThat(third.title()).isEqualTo("Fix the broken tests (3)");

        // Same title, different workspace — no collision, no suffix.
        Thread otherWorkspace = service.create(new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT, "claude-code", "claude-sonnet-4.6", "Fix the broken tests",
                "/tmp/work", "main", null, List.of(), "DEVELOP",
                null, null, null, "ws-other", null));
        assertThat(otherWorkspace.title()).isEqualTo("Fix the broken tests");
    }

    @FunctionalInterface
    private interface NewThreadRequestBuilder
    {
        ThreadService.NewTaskRequest of(String title);
    }

    @Test
    void createHonoursReviewFlowOnTheRequest()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        ThreadService service = new ThreadService(
                store,
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                Mockito.mock(McpPermissionGate.class),
                new RecordingScheduler(),
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

        service.create(new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT,
                "claude-code",
                "claude-sonnet-4.6",
                "Review PR #42",
                "/tmp/work",
                "main",
                /* initialPrompt */ null,
                List.of(),
                "DEVELOP",
                /* linkedPrNumber */ 42,
                /* linkedIssueNumber */ null,
                ThreadFlow.REVIEW, "ws-default", /* workModel */ null));

        assertThat(store.threads).hasSize(1);
        assertThat(store.threads.values().iterator().next().flow())
                .isEqualTo(ThreadFlow.REVIEW);
    }

    @Test
    void createWithoutPromptDoesNotStartSession()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        RecordingScheduler scheduler = new RecordingScheduler();
        ThrowingRegistry registry = new ThrowingRegistry();
        ThreadService service = new ThreadService(
                store,
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                registry,
                Mockito.mock(McpPermissionGate.class),
                scheduler,
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

        service.create(new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT,
                "claude-code",
                "claude-sonnet-4.6",
                "Fix tests",
                "/tmp/work",
                "main",
                " ",
                List.of(),
                "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null,
                /* flow */ null, "ws-default", /* workModel */ null));

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
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                registry,
                Mockito.mock(McpPermissionGate.class),
                scheduler,
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

        String turnId = service.send(thread.id(), null, "next");

        assertThat(turnId).isEqualTo("turn-1");
        assertThat(scheduler.requests).containsExactly(new QueuedRequest(thread, "next"));
        assertThat(registry.used).isFalse();
    }

    @Test
    void followUpSendBindsTheTurnToTheLatestTaskWhenNoTaskIsActive()
    {
        // The task-window composer sends through /messages with no explicit
        // taskId while the task is parked at AWAITING_REVIEW. send() must fall
        // back to the thread's latest task so the row lands in the task's
        // slice — not as a task_id = null trunk row that leaks into the trunk
        // conversation.
        Thread thread = thread();
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        Task parked = new Task(
                "task-9", thread.id(), 1L, TaskStatus.AWAITING_REVIEW,
                "dev/thread-1", "/tmp/work/.wt/task-9", "main", "/tmp/work",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, /* agentSessionId */ null,
                Instant.parse("2026-05-18T12:00:00Z"), null, null, null, null, null);
        RecordingScheduler scheduler = new RecordingScheduler();
        ThreadService service = new ThreadService(
                store,
                new LatestOnlyTaskStore(parked),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                Mockito.mock(McpPermissionGate.class),
                scheduler,
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

        service.send(thread.id(), null, "keep going");

        assertThat(scheduler.taskTurnTaskIds).containsExactly("task-9");
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
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                turns,
                new InMemoryTaskTurnEventStore(),
                registry,
                Mockito.mock(McpPermissionGate.class),
                scheduler,
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

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
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                turns,
                new InMemoryTaskTurnEventStore(),
                registry,
                Mockito.mock(McpPermissionGate.class),
                scheduler,
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

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
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                turnEvents,
                registry,
                Mockito.mock(McpPermissionGate.class),
                new RecordingScheduler(),
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

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
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                turnEvents,
                registry,
                Mockito.mock(McpPermissionGate.class),
                new RecordingScheduler(),
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

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
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                turns,
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                Mockito.mock(McpPermissionGate.class),
                new RecordingScheduler(),
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

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
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                Mockito.mock(McpPermissionGate.class),
                new RecordingScheduler(),
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

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
                new StubTaskStore(),
                new EmptyTaskGroupStore(List.of(new ThreadGroupMembership(thread.id(), "group-1", now))),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                Mockito.mock(McpPermissionGate.class),
                new RecordingScheduler(),
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

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
                new StubTaskStore(),
                groups,
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                Mockito.mock(McpPermissionGate.class),
                new RecordingScheduler(),
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

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
                new StubTaskStore(),
                groups,
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                Mockito.mock(McpPermissionGate.class),
                new RecordingScheduler(),
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

        Thread thread = service.create(new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT,
                "claude-code",
                "claude-sonnet-4.6",
                "Fix tests",
                "/tmp/work",
                "main",
                /* initialPrompt */ null,
                List.of("group-1", "group-1"),
                "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null,
                /* flow */ null, "ws-default", /* workModel */ null));

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
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                registry,
                Mockito.mock(McpPermissionGate.class),
                scheduler,
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

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
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                registry,
                Mockito.mock(McpPermissionGate.class),
                scheduler,
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

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
                new StubTaskStore(),
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                Mockito.mock(McpPermissionGate.class),
                scheduler,
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

        service.delete(thread.id());

        assertThat(scheduler.cancelledTaskIds).containsExactly(thread.id());
        assertThat(store.findThreadById(thread.id())).isEmpty();
    }

    @Test
    void materialiseTaskCutsAWorktreeAndDefersDevWorkToPlanApproval()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        RecordingScheduler scheduler = new RecordingScheduler();
        RecordingWorktreeService worktrees = new RecordingWorktreeService(Optional.of(
                new WorktreeService.WorktreeHandle(
                        Path.of("/tmp/repo/.worktrees/task-1"),
                        "dev/task-1", null)));
        // Use the recording task store + a store wrapper so the
        // active-task projection actually populates on read-back.
        InMemoryRecordingTaskStore tasks = new InMemoryRecordingTaskStore();
        ProjectingThreadStore projecting = new ProjectingThreadStore(store);
        ThreadService service = new ThreadService(
                projecting,
                tasks,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                Mockito.mock(McpPermissionGate.class),
                scheduler,
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                worktrees,
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

        // Step 1 — create is a 0-Task path: no worktree, no Task.
        Thread thread = service.create(new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT,
                "claude-code",
                "claude-sonnet-4.6",
                "Fix tests",
                /* workingDir */ null,
                /* branchName */ null,
                /* initialPrompt */ null,
                List.of(),
                "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null,
                /* flow */ null, "ws-default", /* workModel */ null));
        assertThat(worktrees.createRequests).isEmpty();
        assertThat(tasks.byId).isEmpty();

        // Step 2 — materialiseTask is the branch-worthy step.
        projecting.setPlanningSnapshot(thread.id(), new ThreadStore.PlanningSnapshot(
                Path.of("/tmp/repo").toAbsolutePath().normalize().toString(), "planned-sha"));
        service.materialiseTask(thread.id(), new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT,
                "claude-code",
                "claude-sonnet-4.6",
                "Fix tests",
                "/tmp/repo",
                "main",
                "please fix",
                List.of(),
                "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null,
                /* flow */ null, "ws-default", /* workModel */ null)
                .withOrigin(Task.ORIGIN_AGENT));

        Task active = tasks.activeTasksForThread(thread.id()).stream().findFirst().orElseThrow();
        assertThat(active.worktreePath()).isEqualTo("/tmp/repo/.worktrees/task-1");
        assertThat(active.branchName()).isEqualTo("dev/task-1");
        assertThat(active.agentCwd()).isEqualTo(active.worktreePath());
        // materialiseTask cuts the worktree but no longer enqueues a dev turn:
        // development is gated on plan approval, and planning is kicked off via
        // a PlanKickoffRequested event handled by the brain layer (covered by an
        // integration test), so this unit-level scheduler sees no turn.
        assertThat(scheduler.requests).isEmpty();
        assertThat(worktrees.createRequests)
                .singleElement()
                .extracting(WorktreeCreateRequest::repoRoot,
                        WorktreeCreateRequest::title,
                        WorktreeCreateRequest::baseSha)
                .containsExactly(
                        Path.of("/tmp/repo").toAbsolutePath().normalize(),
                        "Fix tests", "planned-sha");
        // The cut no longer consumes the snapshot — it keeps reflecting the
        // planning worktree until the turn-start sync moves it.
        assertThat(projecting.findPlanningSnapshot(thread.id())).isPresent();
    }

    @Test
    void userOriginTaskCutIgnoresThePlanningSnapshot()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        RecordingWorktreeService worktrees = new RecordingWorktreeService(Optional.of(
                new WorktreeService.WorktreeHandle(
                        Path.of("/tmp/repo/.worktrees/task-1"),
                        "dev/task-1", null)));
        InMemoryRecordingTaskStore tasks = new InMemoryRecordingTaskStore();
        ProjectingThreadStore projecting = new ProjectingThreadStore(store);
        ThreadService service = new ThreadService(
                projecting, tasks, new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(), new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(), Mockito.mock(McpPermissionGate.class),
                new RecordingScheduler(), Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(), worktrees,
                new RoleSkillService(new ConceptRegistry()), stubIdGenerator(),
                Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class),
                Mockito.mock(CheckpointSummariser.class));
        Thread thread = service.create(new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT, "claude-code", "claude-sonnet-4.6", "Fix tests",
                null, null, null, List.of(), "DEVELOP", null, null,
                null, "ws-default", null));
        projecting.setPlanningSnapshot(thread.id(), new ThreadStore.PlanningSnapshot(
                Path.of("/tmp/repo").toAbsolutePath().normalize().toString(), "planned-sha"));

        // Default origin is "user": the trunk's planning view is unrelated,
        // so the cut branches from the freshly-fetched remote base instead.
        service.materialiseTask(thread.id(), new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT, "claude-code", "claude-sonnet-4.6", "Fix tests",
                "/tmp/repo", "main", "please fix", List.of(), "DEVELOP", null, null,
                null, "ws-default", null));

        assertThat(worktrees.createRequests)
                .singleElement()
                .extracting(WorktreeCreateRequest::baseSha)
                .isNull();
        assertThat(projecting.findPlanningSnapshot(thread.id())).isPresent();
    }

    @Test
    void materialiseTaskUsesAiTitleWhenSourceTitleNeedsShortening()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        RecordingWorktreeService worktrees = new RecordingWorktreeService(Optional.of(
                new WorktreeService.WorktreeHandle(
                        Path.of("/tmp/repo/.worktrees/task-1"),
                        "dev/task-1", null)));
        InMemoryRecordingTaskStore tasks = new InMemoryRecordingTaskStore();
        ProjectingThreadStore projecting = new ProjectingThreadStore(store);
        String longTitle = "Remove the 3 confirmed-dead skill routes (GET /skills/by-role, "
                + "GET /skills/{id}, POST /skills/{id}/set-default) and their tests";
        CheckpointSummariser summariser = Mockito.mock(CheckpointSummariser.class);
        Mockito.when(summariser.summariseTaskTitle(longTitle)).thenReturn("Remove dead skill routes");
        ThreadService service = new ThreadService(
                projecting,
                tasks,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                Mockito.mock(McpPermissionGate.class),
                new RecordingScheduler(),
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                worktrees,
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), summariser);

        Thread thread = service.create(new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT, "claude-code", "claude-sonnet-4.6", "Fix tests",
                /* workingDir */ null, /* branchName */ null, /* initialPrompt */ null,
                List.of(), "DEVELOP", /* linkedPrNumber */ null, /* linkedIssueNumber */ null,
                /* flow */ null, "ws-default", /* workModel */ null));
        service.materialiseTask(thread.id(), new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT, "claude-code", "claude-sonnet-4.6", longTitle,
                "/tmp/repo", "main", "please fix", List.of(), "DEVELOP",
                /* linkedPrNumber */ null, /* linkedIssueNumber */ null,
                /* flow */ null, "ws-default", /* workModel */ null));

        Task active = tasks.activeTasksForThread(thread.id()).stream().findFirst().orElseThrow();
        assertThat(active.name()).isEqualTo("Remove dead skill routes");
    }

    @Test
    void failedTaskCutKeepsThePlanningSnapshotForRetry()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        ProjectingThreadStore projecting = new ProjectingThreadStore(store);
        InMemoryRecordingTaskStore tasks = new InMemoryRecordingTaskStore();
        ThreadService service = new ThreadService(
                projecting, tasks, new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(), new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(), Mockito.mock(McpPermissionGate.class),
                new RecordingScheduler(), Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(), new RecordingWorktreeService(Optional.empty()),
                new RoleSkillService(new ConceptRegistry()), stubIdGenerator(),
                Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class),
                Mockito.mock(CheckpointSummariser.class));
        Thread thread = service.create(new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT, "claude-code", "claude-sonnet-4.6", "Fix NPE",
                null, null, null, List.of(), "DEVELOP", null, null,
                null, "ws-default", null));
        String repoRoot = Path.of("/tmp/repo").toAbsolutePath().normalize().toString();
        projecting.setPlanningSnapshot(thread.id(),
                new ThreadStore.PlanningSnapshot(repoRoot, "planned-sha"));

        assertThatThrownBy(() -> service.materialiseTask(thread.id(),
                new ThreadService.NewTaskRequest(
                        ThreadKind.CLI_AGENT, "claude-code", "claude-sonnet-4.6", "Fix NPE",
                        repoRoot, null, "fix it", List.of(), "DEVELOP", null, null,
                        null, "ws-default", null)
                        .withOrigin(Task.ORIGIN_AGENT)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("planned-sha");

        assertThat(projecting.findPlanningSnapshot(thread.id()))
                .contains(new ThreadStore.PlanningSnapshot(repoRoot, "planned-sha"));
        assertThat(tasks.byId).isEmpty();
    }

    @Test
    void materialiseTaskFallsBackToWordBoundaryTruncationWhenAiSummarisationFails()
    {
        InMemoryTaskStore store = new InMemoryTaskStore();
        RecordingWorktreeService worktrees = new RecordingWorktreeService(Optional.of(
                new WorktreeService.WorktreeHandle(
                        Path.of("/tmp/repo/.worktrees/task-1"),
                        "dev/task-1", null)));
        InMemoryRecordingTaskStore tasks = new InMemoryRecordingTaskStore();
        ProjectingThreadStore projecting = new ProjectingThreadStore(store);
        String longTitle = "Remove the 3 confirmed-dead skill routes (GET /skills/by-role, "
                + "GET /skills/{id}, POST /skills/{id}/set-default) and their tests";
        // Mockito.mock() returns null from an unstubbed method — exactly the
        // contract summariseTaskTitle uses to signal "AI unavailable", so
        // this stands in for a missing credential or a network failure.
        CheckpointSummariser summariser = Mockito.mock(CheckpointSummariser.class);
        ThreadService service = new ThreadService(
                projecting,
                tasks,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                Mockito.mock(McpPermissionGate.class),
                new RecordingScheduler(),
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                worktrees,
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), summariser);

        Thread thread = service.create(new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT, "claude-code", "claude-sonnet-4.6", "Fix tests",
                /* workingDir */ null, /* branchName */ null, /* initialPrompt */ null,
                List.of(), "DEVELOP", /* linkedPrNumber */ null, /* linkedIssueNumber */ null,
                /* flow */ null, "ws-default", /* workModel */ null));
        service.materialiseTask(thread.id(), new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT, "claude-code", "claude-sonnet-4.6", longTitle,
                "/tmp/repo", "main", "please fix", List.of(), "DEVELOP",
                /* linkedPrNumber */ null, /* linkedIssueNumber */ null,
                /* flow */ null, "ws-default", /* workModel */ null));

        Task active = tasks.activeTasksForThread(thread.id()).stream().findFirst().orElseThrow();
        // Word-boundary truncation at TASK_NAME_MAX (60 chars) — never
        // cuts mid-word.
        assertThat(active.name()).isEqualTo("Remove the 3 confirmed-dead skill routes (GET");
        assertThat(active.name().length()).isLessThanOrEqualTo(60);
    }

    @Test
    void deleteRemovesTaskWorktreeBeforeDeletingRow()
    {
        Thread thread = threadWithWorktree("thread-1");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        // Seed a completed task with the worktree path the test
        // expects to be pruned. {@link ThreadService#delete} now refuses
        // unless every task has reached COMPLETED; an idle task here
        // would correctly trigger the new pre-flight check instead of
        // exercising the worktree-reaper path this test cares about.
        SingleTaskStore tasks = new SingleTaskStore(new Task(
                "task-1", thread.id(), 1L, TaskStatus.COMPLETED,
                "dev/thread-1",
                "/tmp/work/.bytequay/worktrees/dev/thread-1",
                "main", "/tmp/work",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, /* agentSessionId */ null,
                Instant.parse("2026-05-18T12:00:00Z"), null, null, null, null, null));
        RecordingWorktreeService worktrees = new RecordingWorktreeService(Optional.empty());
        ThreadService service = new ThreadService(
                store,
                tasks,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                Mockito.mock(McpPermissionGate.class),
                new RecordingScheduler(),
                Mockito.mock(WorktreeLeaseService.class),
                new GitRunner(),
                worktrees,
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

        service.delete(thread.id());

        assertThat(worktrees.removeRequests).containsExactly(new WorktreeRemoveRequest(
                Path.of("/tmp/work"),
                "/tmp/work/.bytequay/worktrees/dev/thread-1",
                "dev/thread-1"));
        assertThat(store.findThreadById(thread.id())).isEmpty();
    }

    @Test
    void threadDiffAndCommitViewsUseAgentCwd(@TempDir Path tmp)
            throws IOException
    {
        Thread thread = threadWithWorktree("thread-1");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        // The commit/diff surfaces skip git when the worktree dir is gone
        // (a merged task's worktree gets reaped), so the seeded agentCwd
        // must be a real, present directory for this "worktree alive" case.
        Path worktree = tmp.resolve(".bytequay/worktrees/dev/thread-1");
        Files.createDirectories(worktree);
        // Per-task fields live on the active task projection; seed
        // one so service.X(threadId) can resolve a real agentCwd.
        Task active = new Task(
                "task-1", thread.id(), 1L, TaskStatus.IDLE,
                "dev/thread-1",
                worktree.toString(),
                "main", tmp.toString(),
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, /* agentSessionId */ null,
                Instant.parse("2026-05-18T12:00:00Z"), null, null, null, null, null);
        SingleTaskStore tasks = new SingleTaskStore(active);
        RecordingGitRunner git = new RecordingGitRunner();
        ThreadService service = new ThreadService(
                store,
                tasks,
                new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(),
                new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(),
                Mockito.mock(McpPermissionGate.class),
                new RecordingScheduler(),
                Mockito.mock(WorktreeLeaseService.class),
                git,
                noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()),
                stubIdGenerator(), Mockito.mock(PullRequestService.class), Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

        service.listWorkingChanges(thread.id(), null);
        service.getWorkingDiff(thread.id(), null, "src/App.java");
        service.listTaskCommits(thread.id(), null);
        service.listCommitFiles(thread.id(), null, "abc123");
        service.getCommitDiff(thread.id(), null, "abc123", "src/App.java");

        Path expected = Path.of(active.agentCwd());
        assertThat(git.workingTreeFilesPaths).containsExactly(expected);
        assertThat(git.workingTreeDiffPaths).containsExactly(expected);
        assertThat(git.listCommitsAheadPaths).containsExactly(expected);
        // Commits are scoped to the task's base branch (base..HEAD), not a
        // time window — so the panel shows only the task's own commits.
        assertThat(git.listCommitsAheadBases).containsExactly("main");
        assertThat(git.commitFilesPaths).containsExactly(expected);
        assertThat(git.commitDiffPaths).containsExactly(expected);
    }

    @Test
    void commitAndDiffViewsReturnEmptyWhenTheWorktreeWasReaped(@TempDir Path tmp)
    {
        Thread thread = threadWithWorktree("thread-1");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        // A merged task's worktree is deleted by the lifecycle driver, so
        // its agentCwd no longer exists on disk — the read surfaces must
        // return nothing rather than 500 on a missing directory.
        Path reaped = tmp.resolve("reaped/dev/thread-1");
        Task active = new Task(
                "task-1", thread.id(), 1L, TaskStatus.COMPLETED,
                "dev/thread-1",
                reaped.toString(),
                "main", tmp.toString(),
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, /* agentSessionId */ null,
                Instant.parse("2026-05-18T12:00:00Z"), null, null, null, null, null);
        SingleTaskStore tasks = new SingleTaskStore(active);
        RecordingGitRunner git = new RecordingGitRunner();
        ThreadService service = new ThreadService(
                store, tasks, new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(), new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(), Mockito.mock(McpPermissionGate.class), new RecordingScheduler(),
                Mockito.mock(WorktreeLeaseService.class), git, noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()), stubIdGenerator(), Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

        assertThat(service.listTaskCommits(thread.id(), null)).isEmpty();
        assertThat(service.getWorkingDiff(thread.id(), null, "src/App.java")).isEmpty();
        // git was never invoked against the missing directory.
        assertThat(git.listCommitsAheadPaths).isEmpty();
        assertThat(git.workingTreeDiffPaths).isEmpty();
    }

    @Test
    void cumulativeDiffMapsStatusWordsAndCountsLinesFromPatch(@TempDir Path tmp)
            throws IOException
    {
        Thread thread = threadWithWorktree("thread-1");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        Path worktree = tmp.resolve(".bytequay/worktrees/dev/thread-1");
        Files.createDirectories(worktree);
        Task active = new Task(
                "task-1", thread.id(), 1L, TaskStatus.IDLE,
                "dev/thread-1",
                worktree.toString(),
                "main", tmp.toString(),
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, /* agentSessionId */ null,
                Instant.parse("2026-05-18T12:00:00Z"), null, null, null, null, null);
        SingleTaskStore tasks = new SingleTaskStore(active);
        RecordingGitRunner git = new RecordingGitRunner();
        // A range listing reports A/M letters and 0 counts; the per-file
        // patch carries the real lines the service must count.
        git.cannedRangeFiles = List.of(
                new GitRunner.CommitFileChange("src/New.java", "A", 0, 0),
                new GitRunner.CommitFileChange("src/Old.java", "M", 0, 0));
        String newPatch = "@@\n+x\n+x\n-y\n";
        // +++/--- headers must NOT count as add/del lines.
        String oldPatch = "--- a/src/Old.java\n+++ b/src/Old.java\n@@\n+keep\n-drop\n";
        git.cannedRangePatches = Map.of("src/New.java", newPatch, "src/Old.java", oldPatch);
        ThreadService service = new ThreadService(
                store, tasks, new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(), new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(), Mockito.mock(McpPermissionGate.class), new RecordingScheduler(),
                Mockito.mock(WorktreeLeaseService.class), git, noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()), stubIdGenerator(), Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

        List<ThreadService.TaskDiffFile> diff = service.taskCumulativeDiff(thread.id(), null);

        assertThat(diff).hasSize(2);
        assertThat(diff.get(0))
                .extracting(
                        ThreadService.TaskDiffFile::filename,
                        ThreadService.TaskDiffFile::status,
                        ThreadService.TaskDiffFile::additions,
                        ThreadService.TaskDiffFile::deletions,
                        ThreadService.TaskDiffFile::patch)
                .containsExactly("src/New.java", "added", 2, 1, newPatch);
        assertThat(diff.get(1))
                .extracting(
                        ThreadService.TaskDiffFile::filename,
                        ThreadService.TaskDiffFile::status,
                        ThreadService.TaskDiffFile::additions,
                        ThreadService.TaskDiffFile::deletions)
                .containsExactly("src/Old.java", "modified", 1, 1);
    }

    @Test
    void cumulativeDiffReturnsEmptyWhenTheWorktreeWasReaped(@TempDir Path tmp)
    {
        Thread thread = threadWithWorktree("thread-1");
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveThread(thread);
        Path reaped = tmp.resolve("reaped/dev/thread-1");
        Task active = new Task(
                "task-1", thread.id(), 1L, TaskStatus.COMPLETED,
                "dev/thread-1",
                reaped.toString(),
                "main", tmp.toString(),
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, /* agentSessionId */ null,
                Instant.parse("2026-05-18T12:00:00Z"), null, null, null, null, null);
        SingleTaskStore tasks = new SingleTaskStore(active);
        RecordingGitRunner git = new RecordingGitRunner();
        git.cannedRangeFiles = List.of(
                new GitRunner.CommitFileChange("src/New.java", "A", 0, 0));
        ThreadService service = new ThreadService(
                store, tasks, new EmptyTaskGroupStore(),
                new InMemoryTaskTurnStore(), new InMemoryTaskTurnEventStore(),
                new ThrowingRegistry(), Mockito.mock(McpPermissionGate.class), new RecordingScheduler(),
                Mockito.mock(WorktreeLeaseService.class), git, noopWorktreeService(),
                new RoleSkillService(new ConceptRegistry()), stubIdGenerator(), Mockito.mock(PullRequestService.class),
                Mockito.mock(WorkspaceDataPurger.class), Mockito.mock(CheckpointSummariser.class));

        assertThat(service.taskCumulativeDiff(thread.id(), null)).isEmpty();
    }

    private record WorktreeCreateRequest(
            Path repoRoot, String sessionId, String title, String baseSha) {}

    private record WorktreeRemoveRequest(Path repoRoot, String worktreePath, String localBranch) {}

    private record QueuedRequest(Thread thread, String input) {}

    private static WorktreeService noopWorktreeService()
    {
        return new RecordingWorktreeService(Optional.empty());
    }

    /** IdGenerator backed by a tiny in-memory sequence store. Hand-rolled
     *  rather than mocked because we just need monotonic counters per
     *  ymd — the format itself is covered by TestIdGenerator. */
    private static IdGenerator stubIdGenerator()
    {
        IdSequenceStore store = new IdSequenceStore() {
            private final Map<String, Integer> next = new LinkedHashMap<>();

            @Override
            public int nextThreadSeq(String ymd)
            {
                int v = next.getOrDefault(ymd, 1);
                next.put(ymd, v + 1);
                return v;
            }
        };
        return new IdGenerator(store);
    }

    private static final class RecordingWorktreeService
            extends WorktreeService
    {
        private final Optional<WorktreeHandle> createResult;
        private final List<WorktreeCreateRequest> createRequests = new ArrayList<>();
        private final List<WorktreeRemoveRequest> removeRequests = new ArrayList<>();

        private RecordingWorktreeService(Optional<WorktreeHandle> createResult)
        {
            super(new GitRunner(), Mockito.mock(WatchedRepoStore.class));
            this.createResult = createResult;
        }

        @Override
        public Optional<WorktreeHandle> create(
                Path repoRoot, String sessionId, String title, String baseSha)
        {
            createRequests.add(new WorktreeCreateRequest(repoRoot, sessionId, title, baseSha));
            return createResult;
        }

        @Override
        public void remove(Path repoRoot, String worktreePath, String localBranch)
        {
            removeRequests.add(new WorktreeRemoveRequest(repoRoot, worktreePath, localBranch));
        }

        @Override
        public void removePlanningWorktree(Path repoRoot, String threadId)
        {
        }
    }

    private static final class RecordingGitRunner
            extends GitRunner
    {
        private final List<Path> workingTreeFilesPaths = new ArrayList<>();
        private final List<Path> workingTreeDiffPaths = new ArrayList<>();
        private final List<Path> listCommitsAheadPaths = new ArrayList<>();
        private final List<String> listCommitsAheadBases = new ArrayList<>();
        private final List<Path> commitFilesPaths = new ArrayList<>();
        private final List<Path> commitDiffPaths = new ArrayList<>();
        // Canned returns for the cumulative-diff path; empty by default so
        // the existing commit-view tests are unaffected.
        private List<GitRunner.CommitFileChange> cannedRangeFiles = List.of();
        private Map<String, String> cannedRangePatches = Map.of();
        private List<GitRunner.CommitFileChange> cannedCommitFiles = List.of();
        private Map<String, String> cannedCommitPatches = Map.of();

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
        public boolean refExists(Path workingDir, String ref)
        {
            // The commit surface resolves its base ref before diffing; the
            // configured "main" is treated as present so listCommitsAhead is
            // exercised with it.
            return "main".equals(ref);
        }

        @Override
        public Optional<String> mergeBase(Path workingDir, String branch, String base)
        {
            // No history in this stub, so the resolver falls back to the
            // base ref name itself.
            return Optional.empty();
        }

        @Override
        public List<GitRunner.CommitEntry> listCommitsAhead(Path workingDir, String base, int limit)
        {
            listCommitsAheadPaths.add(workingDir);
            listCommitsAheadBases.add(base);
            return List.of();
        }

        @Override
        public List<GitRunner.CommitFileChange> commitFiles(Path workingDir, String sha)
        {
            commitFilesPaths.add(workingDir);
            return cannedCommitFiles;
        }

        @Override
        public String commitFileDiff(Path workingDir, String sha, String path, int maxBytes)
        {
            commitDiffPaths.add(workingDir);
            return cannedCommitPatches.getOrDefault(path, "");
        }

        @Override
        public List<GitRunner.CommitFileChange> rangeFiles(Path workingDir, String base, String head)
        {
            return cannedRangeFiles;
        }

        @Override
        public String rangeFileDiff(Path workingDir, String base, String head, String path, int maxBytes)
        {
            return cannedRangePatches.getOrDefault(path, "");
        }

        @Override
        public List<GitRunner.CommitFileChange> effectiveFiles(Path workingDir, String base)
        {
            return cannedRangeFiles;
        }

        @Override
        public String effectiveFileDiff(Path workingDir, String base, String path, int maxBytes)
        {
            return cannedRangePatches.getOrDefault(path, "");
        }
    }

    private static final class RecordingScheduler
            implements ThreadTurnScheduler
    {
        private final List<QueuedRequest> requests = new ArrayList<>();
        private final List<String> cancelledTaskIds = new ArrayList<>();
        private final List<String> taskTurnTaskIds = new ArrayList<>();
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
        public String enqueueTurn(Thread thread, String input, TurnInitiator initiator)
        {
            return enqueueTurn(thread, input);
        }

        @Override
        public String enqueueTrunkTurn(Thread thread, String input)
        {
            // The recording surface doesn't distinguish trunk vs task —
            // turn ids are still issued in arrival order.
            requests.add(new QueuedRequest(thread, input));
            return "trunk-turn-" + requests.size();
        }

        @Override
        public String enqueueTaskTurn(Thread thread, String input, String taskId)
        {
            // Capture the task id the service resolved (active-or-latest) so
            // a test can assert a task-window send binds to its task even
            // when no task is "active". Still records the QueuedRequest so
            // the existing arrival-order assertions hold.
            requests.add(new QueuedRequest(thread, input));
            taskTurnTaskIds.add(taskId);
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
                    new StubTaskStore(),
                    new StreamJsonParser(new ObjectMapper()),
                    new ObjectMapper(),
                    new McpPermissionGate(),
                    Executors.newSingleThreadExecutor(),
                    CheckpointTrigger.NOOP,
                    () -> "",
                    new WorktreeLeaseService(new StubLeaseStore()));
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
        public List<ThreadAgent> findAll(String threadId)
        {
            return List.of(session);
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
        public void resume() {}

        @Override
        public void stop()
        {
            events.add("stop");
        }

        @Override
        public void notifyPermissionRequested(String callId, String toolName, String summary) {}

        @Override
        public boolean decide(String callId, PermissionDecision decision)
        {
            return false;
        }

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
                    new StubTaskStore(),
                    new StreamJsonParser(new ObjectMapper()),
                    new ObjectMapper(),
                    new McpPermissionGate(),
                    Executors.newSingleThreadExecutor(),
                    CheckpointTrigger.NOOP,
                    () -> "",
                    new WorktreeLeaseService(new StubLeaseStore()));
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

    /** Empty WorktreeLeaseStore — these scheduler tests don't exercise
     *  the lease layer that landed alongside the Phase-7 data plane.
     *  Backed by a tiny in-memory map so save / find / release behave. */
    static final class StubLeaseStore
            implements WorktreeLeaseStore
    {
        private final Map<String, WorktreeLease> leases = new LinkedHashMap<>();
        @Override public void save(WorktreeLease lease) { leases.put(lease.worktreePath(), lease); }
        @Override public Optional<WorktreeLease> findByWorktreePath(String worktreePath) {
            return Optional.ofNullable(leases.get(worktreePath));
        }
        @Override public List<WorktreeLease> listForTask(String taskId) {
            return leases.values().stream().filter(l -> l.taskId().equals(taskId)).toList();
        }
        @Override public List<WorktreeLease> listAll() { return List.copyOf(leases.values()); }
        @Override public void releaseByWorktreePath(String worktreePath) { leases.remove(worktreePath); }
    }

    /** Empty TaskStore — these scheduler tests don't exercise the
     *  per-work-unit storage that landed alongside Thread/Task split.
     *  A returning-empty stub keeps the constructor happy. */
    private static final class StubTaskStore
            implements TaskStore
    {
        @Override public void saveTask(Task task) {}
        @Override public Optional<Task> findTaskById(String id) { return Optional.empty(); }
        @Override public void deleteTask(String id) {}
        @Override public List<Task> listTasksByThread(String threadId) { return List.of(); }
        @Override public boolean hasActiveTask(String threadId) { return !activeTasksForThread(threadId).isEmpty(); }
        @Override public List<Task> activeTasksForThread(String threadId) { return List.of(); }
        @Override public Optional<Task> findLatestTaskForThread(String threadId) { return Optional.empty(); }
        @Override public Optional<Long> maxSeqForThread(String threadId) { return Optional.empty(); }
        @Override public List<Task> listByStatus(TaskStatus status, int limit) { return List.of(); }
        @Override public List<Task> listWithLinkedPr(int limit) { return List.of(); }
        @Override public List<Task> listByPhases(Collection<TaskPhase> phases, int limit) { return List.of(); }
        @Override public void recordFile(TaskFile file) {}
        @Override public List<TaskFile> listFiles(String taskId) { return List.of(); }
    }

    /** TaskStore that holds one task which is NOT "active" — {@link
     *  #findActiveTaskForThread} returns empty (mirroring a task parked at
     *  AWAITING_REVIEW / phase-complete) while {@link
     *  #findLatestTaskForThread} still surfaces it. Lets a test assert that
     *  {@code send} binds a task-window turn to the latest task rather than
     *  falling back to a trunk turn. */
    private static final class LatestOnlyTaskStore
            implements TaskStore
    {
        private final Task task;

        LatestOnlyTaskStore(Task task) { this.task = task; }

        @Override public void saveTask(Task t) {}
        @Override public Optional<Task> findTaskById(String id) {
            return task.id().equals(id) ? Optional.of(task) : Optional.empty();
        }
        @Override public void deleteTask(String id) {}
        @Override public List<Task> listTasksByThread(String threadId) {
            return task.threadId().equals(threadId) ? List.of(task) : List.of();
        }
        @Override public boolean hasActiveTask(String threadId) { return !activeTasksForThread(threadId).isEmpty(); }
        @Override public List<Task> activeTasksForThread(String threadId) {
            return List.of();
        }
        @Override public Optional<Task> findLatestTaskForThread(String threadId) {
            return task.threadId().equals(threadId) ? Optional.of(task) : Optional.empty();
        }
        @Override public Optional<Long> maxSeqForThread(String threadId) {
            return Optional.of(task.seq());
        }
        @Override public List<Task> listByStatus(TaskStatus status, int limit) { return List.of(); }
        @Override public List<Task> listWithLinkedPr(int limit) { return List.of(); }
        @Override public List<Task> listByPhases(Collection<TaskPhase> phases, int limit) { return List.of(); }
        @Override public void recordFile(TaskFile file) {}
        @Override public List<TaskFile> listFiles(String taskId) { return List.of(); }
    }

    /** TaskStore that records saveTask calls and surfaces them back
     *  through the standard query API. Tests that exercise the create
     *  flow need this so the active-task projection lands on the
     *  Thread record read back from the store. */
    private static final class InMemoryRecordingTaskStore
            implements TaskStore
    {
        final Map<String, Task> byId = new LinkedHashMap<>();

        @Override public void saveTask(Task task) { byId.put(task.id(), task); }
        @Override public Optional<Task> findTaskById(String id) {
            return Optional.ofNullable(byId.get(id));
        }
        @Override public void deleteTask(String id) { byId.remove(id); }
        @Override public List<Task> listTasksByThread(String threadId) {
            return byId.values().stream().filter(t -> t.threadId().equals(threadId)).toList();
        }
        @Override public boolean hasActiveTask(String threadId) { return !activeTasksForThread(threadId).isEmpty(); }
        @Override public List<Task> activeTasksForThread(String threadId) {
            return byId.values().stream()
                    .filter(t -> t.threadId().equals(threadId))
                    .sorted(Comparator.comparingLong(Task::seq).reversed())
                    .toList();
        }
        @Override public Optional<Task> findLatestTaskForThread(String threadId) {
            return byId.values().stream()
                    .filter(t -> t.threadId().equals(threadId))
                    .max(Comparator.comparingLong(Task::seq));
        }
        @Override public Optional<Long> maxSeqForThread(String threadId) {
            return findLatestTaskForThread(threadId).map(Task::seq);
        }
        @Override public List<Task> listByStatus(TaskStatus status, int limit) { return List.of(); }
        @Override public List<Task> listWithLinkedPr(int limit) { return List.of(); }
        @Override public List<Task> listByPhases(Collection<TaskPhase> phases, int limit) { return List.of(); }
        @Override public void recordFile(TaskFile file) {}
        @Override public List<TaskFile> listFiles(String taskId) { return List.of(); }
    }

    /** Plain ThreadStore delegate. Per-task fields no longer live on
     *  Thread, so the create-flow tests look the active task up via the
     *  TaskStore directly. */
    private static final class ProjectingThreadStore
            implements ThreadStore
    {
        private final ThreadStore inner;

        ProjectingThreadStore(ThreadStore inner)
        {
            this.inner = inner;
        }

        @Override public void saveThread(Thread thread) { inner.saveThread(thread); }
        @Override public Optional<Thread> findThreadById(String id) {
            return inner.findThreadById(id);
        }
        @Override public Optional<PlanningSnapshot> findPlanningSnapshot(String threadId) {
            return inner.findPlanningSnapshot(threadId);
        }
        @Override public void setPlanningSnapshot(String threadId, PlanningSnapshot snapshot) {
            inner.setPlanningSnapshot(threadId, snapshot);
        }
        @Override public List<Thread> listTasksByStatus(ThreadStatus status, int limit) {
            return inner.listTasksByStatus(status, limit);
        }
        @Override public List<Thread> listTasksByIds(Collection<String> ids) {
            return inner.listTasksByIds(ids);
        }
        @Override public List<Thread> listThreadsUpdatedSince(Instant since) {
            return inner.listThreadsUpdatedSince(since);
        }
        @Override public void deleteThread(String threadId) { inner.deleteThread(threadId); }
        @Override public void appendMessage(ThreadMessage message) { inner.appendMessage(message); }
        @Override public List<ThreadMessage> listMessages(String threadId) {
            return inner.listMessages(threadId);
        }
        @Override public void recordFile(ThreadFile file) { inner.recordFile(file); }
        @Override public List<ThreadFile> listFiles(String threadId) { return inner.listFiles(threadId); }
    }

    /** TaskStore that holds exactly one seeded task. The bridge
     *  teardown moved per-task fields off Thread, so tests that need
     *  thread.activeTask() to be non-null seed via this helper. */
    private static final class SingleTaskStore
            implements TaskStore
    {
        private final Task task;

        SingleTaskStore(Task task) { this.task = task; }

        @Override public void saveTask(Task t) {}
        @Override public Optional<Task> findTaskById(String id) {
            return task.id().equals(id) ? Optional.of(task) : Optional.empty();
        }
        @Override public void deleteTask(String id) {}
        @Override public List<Task> listTasksByThread(String threadId) {
            return task.threadId().equals(threadId) ? List.of(task) : List.of();
        }
        @Override public boolean hasActiveTask(String threadId) { return !activeTasksForThread(threadId).isEmpty(); }
        @Override public List<Task> activeTasksForThread(String threadId) {
            return task.threadId().equals(threadId) ? List.of(task) : List.of();
        }
        @Override public Optional<Task> findLatestTaskForThread(String threadId) {
            return task.threadId().equals(threadId) ? Optional.of(task) : Optional.empty();
        }
        @Override public Optional<Long> maxSeqForThread(String threadId) {
            return task.threadId().equals(threadId) ? Optional.of(task.seq()) : Optional.empty();
        }
        @Override public List<Task> listByStatus(TaskStatus status, int limit) { return List.of(); }
        @Override public List<Task> listWithLinkedPr(int limit) { return List.of(); }
        @Override public List<Task> listByPhases(Collection<TaskPhase> phases, int limit) { return List.of(); }
        @Override public void recordFile(TaskFile file) {}
        @Override public List<TaskFile> listFiles(String taskId) { return List.of(); }
    }

    private static final class InMemoryTaskStore
            implements ThreadStore
    {
        private final Map<String, Thread> threads = new LinkedHashMap<>();
        private final Map<String, PlanningSnapshot> planningSnapshots = new LinkedHashMap<>();

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
        public Optional<PlanningSnapshot> findPlanningSnapshot(String threadId)
        {
            return Optional.ofNullable(planningSnapshots.get(threadId));
        }

        @Override
        public void setPlanningSnapshot(String threadId, PlanningSnapshot snapshot)
        {
            planningSnapshots.put(threadId, snapshot);
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
        public List<Thread> listThreadsUpdatedSince(Instant since)
        {
            return threads.values().stream()
                    .filter(thread -> !thread.updatedAt().isBefore(since))
                    .toList();
        }

        @Override
        public List<Thread> listThreadsByWorkspace(String workspaceId)
        {
            return threads.values().stream()
                    .filter(thread -> thread.workspaceId().equals(workspaceId))
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

    private static ThreadTurnEvent turnEvent(String id, String turnId, String threadId, Instant createdAt)
    {
        return new ThreadTurnEvent(
                id,
                turnId,
                threadId,
                /* taskId */ null,
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
                "claude-sonnet-4.6",
                /* costUsdMilli */ 0L,
                /* tokensIn */ 0L,
                /* tokensOut */ 0L,
                now,
                now,
                /* endedAt */ null,
                /* errorMessage */ null,
                ThreadFlow.BUILD,
                "ws-default",
                /* workModel */ null,
                /* activeTask */ null);
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
                "claude-sonnet-4.6",
                /* costUsdMilli */ 0L,
                /* tokensIn */ 0L,
                /* tokensOut */ 0L,
                now,
                now,
                /* endedAt */ now,
                /* errorMessage */ null,
                ThreadFlow.BUILD,
                "ws-default",
                /* workModel */ null,
                /* activeTask */ null);
    }
}
