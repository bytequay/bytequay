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
import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFile;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnEvent;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorktreeLease;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnEventStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.WorktreeLeaseStore;
import com.bytequay.app.service.skills.CavemanPrompt;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static com.bytequay.app.domain.ThreadKind.CLI_AGENT;
import static com.bytequay.app.domain.ThreadKind.LOGIC_LOOP;
import static com.bytequay.app.domain.ThreadTurnEventType.TURN_CANCELLED;
import static com.bytequay.app.domain.ThreadTurnEventType.TURN_FAILED;
import static com.bytequay.app.domain.ThreadTurnEventType.TURN_FINISHED;
import static com.bytequay.app.domain.ThreadTurnEventType.TURN_QUEUED;
import static com.bytequay.app.domain.ThreadTurnEventType.TURN_STARTED;
import static com.bytequay.app.domain.ThreadTurnEventType.WAITING_FOR_CAPACITY;
import static com.bytequay.app.domain.ThreadTurnStatus.CANCELLED;
import static com.bytequay.app.domain.ThreadTurnStatus.COMPLETED;
import static com.bytequay.app.domain.ThreadTurnStatus.FAILED;
import static com.bytequay.app.domain.ThreadTurnStatus.QUEUED;
import static com.bytequay.app.domain.ThreadTurnStatus.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;

class TestAgentScheduler
{
    @Test
    void capsCliTurnsAndQueuesOverflow()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread first = thread("thread-1", CLI_AGENT);
        Thread second = thread("thread-2", CLI_AGENT);
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
    void enqueueTaskTurnStampsTheExplicitTaskIdEvenWhenTheActiveProjectionIsNull()
    {
        // thread(...) builds a thread whose activeTask projection is null —
        // the state a task in AWAITING_REVIEW / NEEDS_ATTENTION / phase-
        // COMPLETED presents. The task composer binds the turn to its task
        // by explicit id so the row is NOT recorded as a trunk (task_id =
        // null) turn that would leak into the trunk conversation slice.
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("thread-1", CLI_AGENT);
        harness.register(thread);

        String turnId = harness.scheduler.enqueueTaskTurn(thread, "steer", "task-42");

        assertThat(harness.turns.findTurnById(turnId).orElseThrow().taskId())
                .isEqualTo("task-42");
    }

    @Test
    void enqueueTaskTurnWithNullTaskIdFallsBackToATrunkTurn()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("thread-1", CLI_AGENT);
        harness.register(thread);

        String turnId = harness.scheduler.enqueueTaskTurn(thread, "plan", null);

        assertThat(harness.turns.findTurnById(turnId).orElseThrow().taskId()).isNull();
    }

    @Test
    void codingStageActivatesPonytailAndCavemanWithoutChangingUserInput()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("thread-1", CLI_AGENT);
        RecordingSession session = harness.register(thread);
        String stageId = "11111111-1111-1111-1111-111111111111";
        Instant now = Instant.parse("2026-07-10T00:00:00Z");
        harness.stageStore.stages.put(UUID.fromString(stageId), new StageInstance(
                UUID.fromString(stageId), "task-1", StageType.DEVELOPMENT_STAGE,
                StageState.OPEN, now, null, null));

        harness.scheduler.enqueueTaskTurn(
                thread, "implement", "task-1", stageId, TurnInitiator.user());

        assertThat(session.inputs).containsExactly("implement");
        assertThat(session.skillNames).containsExactly(List.of(
                "task-execution", "codegraph-first", "ponytail", CavemanPrompt.NAME));
        assertThat(session.toolNames.getFirst())
                .contains("codegraph_explore", "run_checks", "push")
                .doesNotContain("list_skills", "list_tools", "load_skill");
    }

    @Test
    void apiCodingStageActivatesPonytailAndCavemanWithoutChangingUserInput()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("thread-1", LOGIC_LOOP);
        RecordingSession session = harness.register(thread);
        String stageId = "11111111-1111-1111-1111-111111111111";
        Instant now = Instant.parse("2026-07-10T00:00:00Z");
        harness.stageStore.stages.put(UUID.fromString(stageId), new StageInstance(
                UUID.fromString(stageId), "task-1", StageType.DEVELOPMENT_STAGE,
                StageState.OPEN, now, null, null));

        harness.scheduler.enqueueTaskTurn(
                thread, "implement", "task-1", stageId, TurnInitiator.user());

        assertThat(session.inputs).containsExactly("implement");
        assertThat(session.skillNames).containsExactly(List.of(
                "task-execution", "codegraph-first", "ponytail", CavemanPrompt.NAME));
        assertThat(session.toolNames.getFirst())
                .contains("codegraph_explore", "run_checks", "push")
                .doesNotContain("list_skills", "list_tools", "load_skill");
    }

    @Test
    void trunkPlanningTurnActivatesTrunkPlannerAndCavemanWithoutChangingUserInput()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("thread-1", CLI_AGENT);
        RecordingSession session = harness.register(thread);

        harness.scheduler.enqueueTrunkTurn(thread, "go ahead and implement this");

        assertThat(session.inputs).containsExactly("go ahead and implement this");
        assertThat(session.skillNames).containsExactly(List.of(
                "trunk-planner", "codegraph-first", CavemanPrompt.NAME));
        assertThat(session.toolNames.getFirst())
                .contains("codegraph_explore", "create_task")
                .doesNotContain("run_checks", "push", "list_skills", "list_tools", "load_skill");
    }

    @Test
    void apiLaneRunsWhileCliLaneIsFull()
    {
        TestHarness harness = new TestHarness(1, 1);
        Thread cliFirst = thread("cli-1", CLI_AGENT);
        Thread cliSecond = thread("cli-2", CLI_AGENT);
        Thread apiTask = thread("api-1", LOGIC_LOOP);
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
                .isEqualTo(ThreadResourceLane.API);
    }

    @Test
    void sameTaskTurnsDoNotRunConcurrently()
    {
        TestHarness harness = new TestHarness(2, 4);
        Thread thread = thread("thread-1", CLI_AGENT);
        RecordingSession session = harness.register(thread);

        String firstTurn = harness.scheduler.enqueueTurn(thread, "first");
        String secondTurn = harness.scheduler.enqueueTurn(thread, "second");

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
    void differentTasksOnOneThreadRunConcurrently()
    {
        // Two task turns on one thread now key the run gate by task (the
        // registry stage key), not by thread, so both dispatch at once when
        // the CLI lane has room. This is the intra-thread parallelism the
        // per-stage agent runtime enables.
        TestHarness harness = new TestHarness(2, 4);
        Thread thread = thread("thread-1", CLI_AGENT);
        RecordingSession session = harness.register(thread);

        String firstTurn = harness.scheduler.enqueueTaskTurn(thread, "first", "task-a");
        String secondTurn = harness.scheduler.enqueueTaskTurn(thread, "second", "task-b");

        assertThat(harness.turns.findTurnById(firstTurn).orElseThrow().status())
                .isEqualTo(RUNNING);
        assertThat(harness.turns.findTurnById(secondTurn).orElseThrow().status())
                .isEqualTo(RUNNING);
        assertThat(session.inputs).containsExactly("first", "second");
    }

    @Test
    void cancelQueuedTurnsRemovesOnlyQueuedTurnsForTask()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread first = thread("thread-1", CLI_AGENT);
        Thread second = thread("thread-2", CLI_AGENT);
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
        Thread thread = thread("thread-1", CLI_AGENT);
        RecordingSession session = harness.register(thread);

        String turnId = harness.scheduler.enqueueTurn(thread, "first");
        session.completeNext();

        assertThat(harness.events.listEventsByTaskId(thread.id(), 10))
                .extracting(ThreadTurnEvent::event)
                .containsExactlyInAnyOrder(TURN_FINISHED, TURN_STARTED, TURN_QUEUED);
        assertThat(harness.events.listEventsByTaskId(thread.id(), 10))
                .extracting(ThreadTurnEvent::turnId)
                .containsOnly(turnId);
    }

    @Test
    void appendsSchedulerEventWhenQueuedTurnIsCancelled()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread first = thread("thread-1", CLI_AGENT);
        Thread second = thread("thread-2", CLI_AGENT);
        harness.register(first);
        harness.register(second);

        harness.scheduler.enqueueTurn(first, "first");
        String cancelledTurn = harness.scheduler.enqueueTurn(second, "second");

        assertThat(harness.scheduler.cancelQueuedTurns(second.id())).isEqualTo(1);
        assertThat(harness.events.listEventsByTaskId(second.id(), 10))
                .extracting(ThreadTurnEvent::event)
                .containsExactlyInAnyOrder(TURN_CANCELLED, WAITING_FOR_CAPACITY, TURN_QUEUED);
        assertThat(harness.events.listEventsByTaskId(second.id(), 10))
                .extracting(ThreadTurnEvent::turnId)
                .containsOnly(cancelledTurn);
    }

    @Test
    void appendsWaitingEventWhenLaneIsFull()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread first = thread("thread-1", CLI_AGENT);
        Thread second = thread("thread-2", CLI_AGENT);
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
        Thread thread = thread("thread-1", CLI_AGENT);
        harness.register(thread);

        harness.scheduler.enqueueTurn(thread, "first");
        String waitingTurn = harness.scheduler.enqueueTurn(thread, "second");

        assertThat(harness.events.listEventsByTaskId(thread.id(), 10))
                .anySatisfy(event -> {
                    assertThat(event.turnId()).isEqualTo(waitingTurn);
                    assertThat(event.event()).isEqualTo(WAITING_FOR_CAPACITY);
                    assertThat(event.message()).isEqualTo("waiting for previous turn for this agent");
                });
    }

    @Test
    void cancelQueuedTurnsPagesThroughAllDurableQueuedTurns()
    {
        TestHarness harness = new TestHarness(1, 4);
        String threadId = "thread-1";
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        for (int i = 0; i < 1_001; i++) {
            harness.turns.saveTurn(turn("turn-" + i, threadId, now.plusMillis(i)));
        }

        assertThat(harness.scheduler.cancelQueuedTurns(threadId)).isEqualTo(1_001);
        assertThat(harness.turns.turns.values())
                .extracting(ThreadTurn::status)
                .containsOnly(CANCELLED);
    }

    @Test
    void recoveryPagesThroughAllOrphanedRunningTurns()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("thread-1", CLI_AGENT);
        RecordingSession session = harness.register(thread);
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        for (int i = 0; i < 1_001; i++) {
            harness.turns.saveTurn(turn("turn-" + i, thread.id(), RUNNING, now.plusMillis(i)));
        }

        harness.scheduler.recoverQueuedTurns();

        assertThat(session.inputs).containsExactly("input");
        assertThat(harness.turns.turns.values())
                .filteredOn(turn -> turn.status() == RUNNING)
                .hasSize(1);
        assertThat(harness.turns.turns.values())
                .filteredOn(turn -> turn.status() == QUEUED)
                .hasSize(1_000);
        assertThat(harness.events.listEventsByTaskId(thread.id(), 2_100))
                .filteredOn(event -> event.event() == TURN_QUEUED)
                .hasSize(1_001);
    }

    @Test
    void recoveryPagesThroughAllDurableQueuedTurns()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("thread-1", CLI_AGENT);
        RecordingSession session = harness.register(thread);
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        for (int i = 0; i < 1_001; i++) {
            harness.turns.saveTurn(turn("turn-" + i, thread.id(), QUEUED, now.plusMillis(i)));
        }

        harness.scheduler.recoverQueuedTurns();

        for (int i = 0; i < 1_001; i++) {
            assertThat(session.inputs).hasSize(i + 1);
            session.completeNext();
        }
        assertThat(harness.turns.turns.values())
                .extracting(ThreadTurn::status)
                .containsOnly(COMPLETED);
    }

    @Test
    void recoveryDoesNotDuplicateWaitingEventForAlreadyKnownQueuedTurn()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread first = thread("thread-1", CLI_AGENT);
        Thread second = thread("thread-2", CLI_AGENT);
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
        Thread first = thread("thread-1", CLI_AGENT);
        Thread second = thread("thread-2", CLI_AGENT);
        RecordingSession firstSession = harness.register(first);
        RecordingSession secondSession = harness.register(second);

        String firstTurn = harness.scheduler.enqueueTurn(first, "first");
        String secondTurn = harness.scheduler.enqueueTurn(second, "second");

        firstSession.failNext(new IllegalStateException("boom"));

        ThreadTurn failed = harness.turns.findTurnById(firstTurn).orElseThrow();
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

    private static Thread thread(String id, ThreadKind kind)
    {
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        return new Thread(
                id,
                kind,
                kind == CLI_AGENT ? "claude-code" : "openai",
                /* agentSessionId */ null,
                "Thread " + id,
                ThreadStatus.IDLE,
                "model",
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

    private static final class TestHarness
    {
        private final InMemoryTaskStore threads = new InMemoryTaskStore();
        private final InMemoryTaskTurnStore turns = new InMemoryTaskTurnStore();
        private final InMemoryTaskTurnEventStore events = new InMemoryTaskTurnEventStore();
        private final RecordingRegistry registry = new RecordingRegistry();
        private final StubStageStore stageStore = new StubStageStore();
        private final AgentScheduler scheduler;

        private TestHarness(int maxCliRunning, int maxApiRunning)
        {
            scheduler = new AgentScheduler(
                    threads, turns, events, registry, stageStore,
                    new StubTaskStore(), maxCliRunning, maxApiRunning);
        }

        private RecordingSession register(Thread thread)
        {
            RecordingSession session = new RecordingSession(thread);
            threads.saveThread(thread);
            registry.sessions.put(thread.id(), session);
            return session;
        }
    }

    private static final class RecordingRegistry
            extends ThreadRegistry
    {
        private final Map<String, ThreadAgent> sessions = new LinkedHashMap<>();

        private RecordingRegistry()
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
            ThreadAgent session = sessions.get(thread.id());
            if (session == null) {
                throw new IllegalStateException("no session for " + thread.id());
            }
            return session;
        }

        @Override
        public ThreadAgent getOrCreate(Thread thread, Task task, String stageId)
        {
            // The scheduler tests record one session per thread and don't
            // distinguish per-stage agents — route the per-stage call back
            // to the recorded session.
            return getOrCreate(thread);
        }

        @Override
        public ThreadAgent getOrCreateTrunk(Thread thread)
        {
            // The scheduler tests don't distinguish trunk vs task agents.
            // Both return the recorded session for the thread.
            return getOrCreate(thread);
        }
    }

    private static final class StubStageStore
            implements StageStore
    {
        private final Map<UUID, StageInstance> stages = new LinkedHashMap<>();

        @Override public StageInstance openStage(String taskId, StageType type, UUID callerStageId) { throw new UnsupportedOperationException(); }
        @Override public void closeStage(UUID stageId, String reason) {}
        @Override public void closeStage(UUID stageId, String reason, Map<String, Object> extraPayload) {}
        @Override public StageInstance reopenStage(UUID stageId) { throw new UnsupportedOperationException(); }
        @Override public Optional<StageInstance> findStageById(UUID stageId) { return Optional.ofNullable(stages.get(stageId)); }
        @Override public Optional<String> findMetricsJson(UUID stageId) { return Optional.empty(); }
        @Override public void updateMetricsJson(UUID stageId, String metricsJson) {}
        @Override public void updateWorkModel(UUID stageId, WorkModel workModel) {}
        @Override public List<StageInstance> findStagesByTask(String taskId) { return List.of(); }
        @Override public Optional<StageInstance> findActiveStage(String taskId) { return Optional.empty(); }
        @Override public StageEvent recordEvent(UUID stageId, String taskId, StageEventType type, Map<String, Object> payload) { throw new UnsupportedOperationException(); }
        @Override public Optional<StageEvent> findEventById(UUID eventId) { return Optional.empty(); }
        @Override public void updateEventPayload(UUID eventId, Map<String, Object> payload) {}
        @Override public List<StageEvent> findEventsByStage(UUID stageId) { return List.of(); }
        @Override public List<StageEvent> findRecentEventsByStage(UUID stageId, int limit) { return List.of(); }
        @Override public List<StageEvent> findEventsByTask(String taskId) { return List.of(); }
        @Override public ReviewComment saveReviewComment(ReviewComment comment) { throw new UnsupportedOperationException(); }
        @Override public Optional<ReviewComment> findReviewCommentById(UUID id) { return Optional.empty(); }
        @Override public boolean reviewCommentExistsByRemoteLink(String remoteLink) { return false; }
        @Override public List<ReviewComment> findUnresolvedComments(String taskId) { return List.of(); }
        @Override public List<ReviewComment> findCommentsBySource(String taskId, ReviewCommentSource source) { return List.of(); }
        @Override public List<ReviewComment> findUnroundedRemoteComments(String taskId) { return List.of(); }
        @Override public List<ReviewComment> findCommentsByRound(UUID roundId) { return List.of(); }
        @Override public void assignCommentsToRound(List<UUID> commentIds, UUID roundId) {}
    }

    /** Empty TaskStore — the scheduler tests don't exercise the
     *  per-work-unit storage. Same shape as the one in
     *  TestThreadServiceScheduler. */
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

    /** Tiny in-memory WorktreeLeaseStore so the registry constructor's
     *  WorktreeLeaseService dep is satisfied without dragging Spring in. */
    private static final class StubLeaseStore
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
        public List<Thread> listThreadsUpdatedSince(Instant since)
        {
            return threads.values().stream()
                    .filter(thread -> !thread.updatedAt().isBefore(since))
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
        return turn(id, threadId, QUEUED, createdAt);
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

    private static final class RecordingSession
            implements ThreadAgent
    {
        private final Thread thread;
        private final List<String> inputs = new ArrayList<>();
        private final List<List<String>> skillNames = new ArrayList<>();
        private final List<Set<String>> toolNames = new ArrayList<>();
        private final ArrayDeque<CompletableFuture<Void>> completions = new ArrayDeque<>();
        private ThreadStatus status = ThreadStatus.IDLE;

        private RecordingSession(Thread thread)
        {
            this.thread = thread;
        }

        @Override
        public String id()
        {
            return thread.id();
        }

        @Override
        public ThreadKind kind()
        {
            return thread.kind();
        }

        @Override
        public String provider()
        {
            return thread.provider();
        }

        @Override
        public String model()
        {
            return thread.model();
        }

        @Override
        public String workingDir()
        {
            return null;
        }

        @Override
        public String branchName()
        {
            return null;
        }

        @Override
        public ThreadStatus status()
        {
            return status;
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
            inputs.add(userInput);
            status = ThreadStatus.RUNNING;
            CompletableFuture<Void> completion = new CompletableFuture<>();
            completions.add(completion);
            return completion;
        }

        @Override
        public void setActiveManagedSkillNames(List<String> names)
        {
            skillNames.add(names == null ? List.of() : List.copyOf(names));
        }

        @Override
        public void setActiveToolNames(Set<String> names)
        {
            toolNames.add(names == null ? Set.of() : Set.copyOf(names));
        }

        private void completeNext()
        {
            status = ThreadStatus.IDLE;
            completions.removeFirst().complete(null);
        }

        private void failNext(RuntimeException failure)
        {
            status = ThreadStatus.ERRORED;
            completions.removeFirst().completeExceptionally(failure);
        }

        @Override
        public void interrupt() {}

        @Override
        public void resume() {}

        @Override
        public void stop() {}

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
}
