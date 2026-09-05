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
package com.bytequay.agent.core.impl;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventOutboxEntry;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.RunningTurn;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestDefaultAgentTaskTitleService
{
    private static final AgentLaneId LANE_ID = new AgentLaneId("codex");

    @TempDir
    private Path temporaryDirectory;

    private AgentTaskStore tasks;
    private AgentResourceService resources;
    private AgentDriver driver;
    private AgentEventOutboxStore outbox;
    private AgentEventService events;
    private AgentRuntimeRegistry runtimeRegistry;
    private AgentTask task;
    private TaskId taskId;
    private TurnId ownerTurnId;
    private Path runtimeDirectory;
    private DefaultAgentTaskTitleService service;

    @BeforeEach
    void setUp()
            throws IOException
    {
        tasks = mock(AgentTaskStore.class);
        resources = mock(AgentResourceService.class);
        AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
        driver = mock(AgentDriver.class);
        outbox = mock(AgentEventOutboxStore.class);
        events = mock(AgentEventService.class);
        runtimeRegistry = new AgentRuntimeRegistry();
        task = mock(AgentTask.class);
        taskId = TaskId.random();
        ownerTurnId = TurnId.random();
        when(task.id()).thenReturn(taskId);
        when(task.state()).thenReturn(TaskState.OPEN);
        when(task.titleSource()).thenReturn(TitleSource.PROVISIONAL);
        when(task.worktreeMutationClaim()).thenReturn(Optional.empty());
        when(task.laneId()).thenReturn(LANE_ID);
        when(task.worktree()).thenReturn(temporaryDirectory.resolve("task-worktree").toAbsolutePath());
        when(task.defaults()).thenReturn(new AgentTaskDefaults(
                Optional.of("model-a"),
                Optional.of("high"),
                AgentPermissionPolicy.AUTO));
        when(tasks.find(taskId)).thenReturn(Optional.of(task));
        when(drivers.require(LANE_ID)).thenReturn(driver);
        runtimeDirectory = Files.createDirectory(temporaryDirectory.resolve("runtime")).toAbsolutePath();
        when(resources.require(taskId)).thenReturn(new TaskResources(
                runtimeDirectory,
                runtimeDirectory.resolve("events.jsonl"),
                runtimeDirectory.resolve("blobs"),
                runtimeDirectory.resolve("provider")));
        AgentUnitOfWork unitOfWork = new AgentUnitOfWork()
        {
            @Override
            public <T> T execute(Work<T> work)
            {
                return work.run();
            }
        };
        service = new DefaultAgentTaskTitleService(
                tasks,
                resources,
                drivers,
                unitOfWork,
                outbox,
                events,
                runtimeRegistry,
                Runnable::run,
                Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofMillis(100));
    }

    @Test
    void generatesACompactTitleInAnIsolatedTextOnlySession()
    {
        AtomicReference<AgentEventOutboxEntry> pending = new AtomicReference<>();
        doAnswer(invocation -> {
            pending.set(invocation.getArgument(0));
            return null;
        }).when(outbox).insert(any());
        when(outbox.listPending(eq(taskId), anyInt())).thenAnswer(invocation ->
                Optional.ofNullable(pending.getAndSet(null)).map(ImmutableList::of).orElseGet(ImmutableList::of));
        AgentSession session = completedSession("Title: **Improve agent task naming.**");
        when(driver.open(any(), any())).thenReturn(session);
        AgentTask named = mock(AgentTask.class);
        when(task.withTitle(eq("Improve agent task naming"), eq(TitleSource.AGENT), any())).thenReturn(named);
        when(tasks.update(named)).thenReturn(named);

        service.generate(task, ownerTurnId, "Please make ByteQuay task names short and useful");

        ArgumentCaptor<AgentSessionContext> context = ArgumentCaptor.forClass(AgentSessionContext.class);
        verify(driver).open(context.capture(), any());
        assertThat(context.getValue().taskId()).isEqualTo(taskId.toString());
        assertThat(context.getValue().textOnly()).isTrue();
        assertThat(context.getValue().worktree()).isNotEqualTo(task.worktree());
        assertThat(context.getValue().runtimeDirectory().getParent()).isEqualTo(runtimeDirectory);
        assertThat(context.getValue().model()).contains("model-a");
        assertThat(context.getValue().effort()).contains("high");
        ArgumentCaptor<AgentTurnCommand> command = ArgumentCaptor.forClass(AgentTurnCommand.class);
        verify(session).start(command.capture());
        assertThat(command.getValue().turnId()).isEqualTo(ownerTurnId.toString());
        assertThat(command.getValue().message()).contains("User prompt as a JSON string:");
        verify(tasks).update(named);
        ArgumentCaptor<AgentEventOutboxEntry> event = ArgumentCaptor.forClass(AgentEventOutboxEntry.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().turnId()).isEmpty();
        assertThat(event.getValue().payload()).isEqualTo(new com.bytequay.agent.core.AgentEventOutboxPayload.Inline(
                new AgentEventPayload.Lifecycle(
                        AgentEventPayload.LifecyclePhase.TITLE_UPDATED,
                        "Task title generated")));
        verify(events).drain(event.getValue().eventId());
        verify(session).close();
        assertThat(titleScratch()).isEmpty();
    }

    @Test
    void keepsTheFallbackWhenTheNamingTurnFails()
    {
        AgentSession failed = completedSession(new AgentTurnResult(
                AgentTurnResult.State.FAILED,
                Optional.empty(),
                Optional.of("unavailable"),
                Duration.ZERO));
        when(driver.open(any(), any())).thenReturn(failed);

        service.generate(task, ownerTurnId, "Fix task naming");

        verify(tasks, never()).update(any());
        verify(outbox, never()).insert(any());
        assertThat(titleScratch()).isEmpty();
    }

    @Test
    void timesOutAndCancelsAStuckNamingTurn()
    {
        AgentSession session = mock(AgentSession.class);
        RunningTurn turn = mock(RunningTurn.class);
        when(session.start(any())).thenReturn(turn);
        when(turn.completion()).thenReturn(new CompletableFuture<>());
        when(driver.open(any(), any())).thenReturn(session);
        service = titleService(Duration.ofMillis(1));

        service.generate(task, ownerTurnId, "Fix task naming");

        verify(turn).cancel();
        verify(session).close();
        verify(tasks, never()).update(any());
        assertThat(titleScratch()).isEmpty();
    }

    @Test
    void retainsScratchWithoutApplyingTitleWhenProviderShutdownCannotBeVerified()
    {
        AgentSession session = completedSession("Improve agent task naming");
        doThrow(new RuntimeException("still running")).when(session).close();
        when(driver.open(any(), any())).thenReturn(session);

        service.generate(task, ownerTurnId, "Fix task naming");

        verify(tasks, never()).update(any());
        verify(outbox, never()).insert(any());
        assertThat(titleScratch()).hasSize(1);
    }

    @Test
    void keepsAManualRenameThatWinsWhileTheTitleIsGenerated()
    {
        AgentTask manuallyNamed = mock(AgentTask.class);
        when(manuallyNamed.state()).thenReturn(TaskState.OPEN);
        when(manuallyNamed.titleSource()).thenReturn(TitleSource.USER);
        when(tasks.find(taskId)).thenReturn(
                Optional.of(task),
                Optional.of(task),
                Optional.of(manuallyNamed));
        when(tasks.update(any())).thenThrow(new AgentConflictException("manual rename won"));
        AgentSession session = completedSession("Replace manual title");
        when(driver.open(any(), any())).thenReturn(session);

        service.generate(task, ownerTurnId, "Fix task naming");

        verify(tasks).update(any());
        verify(outbox, never()).insert(any());
        assertThat(titleScratch()).isEmpty();
    }

    @Test
    void schedulesOnlyOneNamingRequestWhileAnotherIsInFlight()
    {
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        Executor queued = command -> assertThat(scheduled.compareAndSet(null, command)).isTrue();
        service = titleService(Duration.ofMillis(100), queued);
        AgentSession session = completedSession("Name first task");
        when(driver.open(any(), any())).thenReturn(session);

        service.generate(task, ownerTurnId, "Fix task naming");
        service.generate(task, ownerTurnId, "Fix task naming");

        assertThat(scheduled.get()).isNotNull();
        verify(driver, never()).open(any(), any());
        scheduled.get().run();
        verify(driver).open(any(), any());
        assertThat(titleScratch()).isEmpty();
    }

    private DefaultAgentTaskTitleService titleService(Duration maximumRuntime)
    {
        return titleService(maximumRuntime, Runnable::run);
    }

    private DefaultAgentTaskTitleService titleService(Duration maximumRuntime, Executor executor)
    {
        AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
        when(drivers.require(LANE_ID)).thenReturn(driver);
        AgentUnitOfWork unitOfWork = new AgentUnitOfWork()
        {
            @Override
            public <T> T execute(Work<T> work)
            {
                return work.run();
            }
        };
        return new DefaultAgentTaskTitleService(
                tasks,
                resources,
                drivers,
                unitOfWork,
                outbox,
                events,
                runtimeRegistry,
                executor,
                Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC),
                maximumRuntime);
    }

    private List<Path> titleScratch()
    {
        try (Stream<Path> children = Files.list(runtimeDirectory)) {
            return children
                    .filter(path -> path.getFileName().toString().startsWith("title-"))
                    .toList();
        }
        catch (IOException e) {
            throw new AssertionError("cannot list title scratch", e);
        }
    }

    private AgentSession completedSession(String response)
    {
        return completedSession(new AgentTurnResult(
                AgentTurnResult.State.COMPLETED,
                Optional.of(response),
                Optional.empty(),
                Duration.ofMillis(10)));
    }

    private AgentSession completedSession(AgentTurnResult result)
    {
        AgentSession session = mock(AgentSession.class);
        RunningTurn turn = mock(RunningTurn.class);
        when(session.start(any())).thenReturn(turn);
        when(turn.completion()).thenReturn(CompletableFuture.completedFuture(result));
        return session;
    }
}
