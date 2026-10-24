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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentEventOutboxEntry;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskTitleService;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskNames;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.agent.core.AgentTaskFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestInteractiveTaskNaming
{
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final AgentTaskDefaults DEFAULTS = AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO);
    private static final TaskNames GENERATED = new TaskNames("Fix the message display", "fix-message-display");
    private final AgentTaskTitleService names = mock(AgentTaskTitleService.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final DevelopmentBranchService branches = mock(DevelopmentBranchService.class);
    private final AgentUnitOfWork unitOfWork = mock(AgentUnitOfWork.class);
    private final AgentEventOutboxStore outbox = mock(AgentEventOutboxStore.class);
    private final AgentEventService events = mock(AgentEventService.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final AgentRuntimeRegistry runtime = new AgentRuntimeRegistry(tasks, ignored -> {}, ignored -> false, true);
    private final Queue<Runnable> queued = new ArrayDeque<>();
    private final TaskId id = TaskId.random();
    private final TaskWorktree worktree = new TaskWorktree(id, "workspace", new RepositoryIdentity("owner", "repo"),
            Path.of("/tmp", id.toString()), "user/bytequay/provisional-12345678", "a".repeat(40));
    private final AtomicReference<AgentTask> current = new AtomicReference<>(created(id, true, "Fix messages", TitleSource.PROVISIONAL,
            AgentLaneId.CODEX_CLI, worktree, DEFAULTS, NOW));

    @BeforeEach
    void setUp()
    {
        beans.registerSingleton("branches", branches);
        when(tasks.find(id)).thenAnswer(ignored -> Optional.ofNullable(current.get()));
        when(tasks.update(any())).thenAnswer(invocation -> {
            AgentTask updated = invocation.getArgument(0);
            current.set(updated);
            return updated;
        });
        when(unitOfWork.execute(any())).thenAnswer(invocation -> invocation.<AgentUnitOfWork.Work<?>>getArgument(0).run());
        when(names.generate(AgentLaneId.CODEX_CLI, DEFAULTS, "Full request and issue context")).thenReturn(Optional.of(GENERATED));
    }

    @Test
    void schedulingReturnsWithoutCallingTheProviderAndDoesNotQueueDuplicates()
    {
        InteractiveTaskNaming naming = service(queued::add);
        schedule(naming);
        schedule(naming);
        assertThat(queued).hasSize(1);
        verifyNoInteractions(names, branches, outbox, events);
        assertThat(current.get().titleSource()).isEqualTo(TitleSource.PROVISIONAL);
        assertThat(current.get().version()).isZero();

        queued.remove().run();

        assertThat(current.get().title()).isEqualTo(GENERATED.title());
        assertThat(current.get().titleSource()).isEqualTo(TitleSource.AGENT);
        assertThat(current.get().taskWorktree()).contains(worktree);
        verify(branches).propose(id, GENERATED.branch());
        ArgumentCaptor<AgentEventOutboxEntry> event = ArgumentCaptor.forClass(AgentEventOutboxEntry.class);
        verify(outbox).insert(event.capture());
        verify(events).drain(event.getValue().eventId());
    }

    @Test
    void aUserTitleSetWhileTheHelperRunsWins()
    {
        when(names.generate(AgentLaneId.CODEX_CLI, DEFAULTS, "Full request and issue context")).thenAnswer(ignored -> {
            current.set(current.get().withTitle("My task title", TitleSource.USER, NOW));
            return Optional.of(GENERATED);
        });
        schedule(service(Runnable::run));
        assertThat(current.get().title()).isEqualTo("My task title");
        verifyNoInteractions(branches, outbox, events);
        verify(tasks, never()).update(any());
    }

    @Test
    void deletedTasksCannotBeResurrectedByLateNaming()
    {
        when(names.generate(AgentLaneId.CODEX_CLI, DEFAULTS, "Full request and issue context")).thenAnswer(ignored -> {
            current.set(null);
            return Optional.of(GENERATED);
        });
        schedule(service(Runnable::run));
        verifyNoInteractions(branches, outbox, events);
        verify(tasks, never()).update(any());
    }

    @Test
    void providerFailureKeepsTheWorkingProvisionalNames()
    {
        AgentTask original = current.get();
        when(names.generate(AgentLaneId.CODEX_CLI, DEFAULTS, "Full request and issue context")).thenThrow(new IllegalStateException("provider unavailable"));
        schedule(service(Runnable::run));
        assertThat(current.get()).isSameAs(original);
        verifyNoInteractions(branches, outbox, events);
    }

    @Test
    void helperIsSkippedIfTheTitleWasAlreadyChosen()
    {
        current.set(current.get().withTitle("Keep this", TitleSource.USER, NOW));
        schedule(service(Runnable::run));
        verifyNoInteractions(names, branches, outbox, events);
    }

    @Test
    void executorShutdownCannotFailAnAcceptedSend()
    {
        schedule(service(command -> { throw new IllegalStateException("executor stopped"); }));
        verifyNoInteractions(names, branches, outbox, events);
        assertThat(current.get().titleSource()).isEqualTo(TitleSource.PROVISIONAL);
    }

    @Test
    void theWorkingAgentRenamesAGeneratedTitleAndProposesItsBranch()
    {
        InteractiveTaskNaming naming = service(Runnable::run);
        schedule(naming);
        TurnId turnId = TurnId.random();
        when(branches.rename(id, turnId, "convert-parquet-time-millis")).thenReturn("Recorded proposed branch.");
        assertThat(naming.rename(id, turnId, "Convert Parquet time millis", Optional.of("convert-parquet-time-millis")))
                .isEqualTo("Renamed the task: Convert Parquet time millis. Recorded proposed branch.");
        assertThat(current.get().title()).isEqualTo("Convert Parquet time millis");
        assertThat(current.get().titleSource()).isEqualTo(TitleSource.AGENT);
        verify(outbox, times(2)).insert(any());
        verify(events, times(2)).drain(any());
    }

    @Test
    void lateBackgroundNamingKeepsTheAgentTitle()
    {
        InteractiveTaskNaming naming = service(queued::add);
        schedule(naming);
        assertThat(naming.rename(id, TurnId.random(), "Convert Parquet time millis", Optional.empty()))
                .isEqualTo("Renamed the task: Convert Parquet time millis. Kept the current branch.");
        queued.remove().run();
        assertThat(current.get().title()).isEqualTo("Convert Parquet time millis");
        verifyNoInteractions(names, branches);
    }

    @Test
    void aPersonsTitleOutranksTheAgentAndUnnormalizedTitlesAreRefused()
    {
        current.set(current.get().withTitle("Keep this", TitleSource.USER, NOW));
        InteractiveTaskNaming naming = service(Runnable::run);
        assertThat(naming.rename(id, TurnId.random(), "Convert Parquet time millis", Optional.empty()))
                .isEqualTo("Kept the user's task title: Keep this. Kept the current branch.");
        assertThat(current.get().title()).isEqualTo("Keep this");
        assertThatThrownBy(() -> naming.rename(id, TurnId.random(), "Fix the bug.", Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(outbox, events);
    }

    private InteractiveTaskNaming service(Executor executor)
    {
        return new InteractiveTaskNaming(names, tasks, runtime, beans.getBeanProvider(DevelopmentBranchService.class), executor,
                Clock.fixed(NOW, ZoneOffset.UTC), unitOfWork, outbox, events);
    }

    private void schedule(InteractiveTaskNaming naming)
    {
        naming.schedule(id, AgentLaneId.CODEX_CLI, DEFAULTS, "Full request and issue context");
    }
}
