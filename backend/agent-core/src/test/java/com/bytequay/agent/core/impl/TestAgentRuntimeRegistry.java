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
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.RunningTurn;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestAgentRuntimeRegistry
{
    private static final TaskId FIRST = TaskId.random();
    private static final TaskId SECOND = TaskId.random();

    @Test
    void boundsTheNumberOfIdleProcessesWithoutClosingActiveOnes()
            throws Exception
    {
        AgentTaskStore tasks = mock(AgentTaskStore.class);
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(tasks, ignored -> {});
        List<String> closed = new CopyOnWriteArrayList<>();
        try {
            for (int index = 0; index < 5; index++) {
                TaskId id = TaskId.random();
                AgentTask task = mock(AgentTask.class);
                when(task.id()).thenReturn(id);
                when(tasks.find(id)).thenReturn(Optional.of(task));
                registry.registerSession(id, new StubSession("idle-" + index, closed)
                {
                    @Override
                    public boolean retained()
                    {
                        return true;
                    }
                });
                registry.retainFinishedSession(id);
            }
            registry.registerSession(FIRST, session("active", closed));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (closed.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertThat(closed).containsExactly("idle-0");
            assertThat(registry.session(FIRST)).isPresent();
        }
        finally {
            registry.close();
        }
    }

    @Test
    void retiringAnIdleProcessExcludesWritersWithoutHoldingTheirLock()
    {
        AgentTaskStore tasks = mock(AgentTaskStore.class);
        AgentTask task = mock(AgentTask.class);
        when(task.id()).thenReturn(FIRST);
        when(tasks.find(FIRST)).thenReturn(Optional.of(task));
        when(tasks.require(FIRST)).thenReturn(task);
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(tasks, ignored -> {});
        List<String> closed = new CopyOnWriteArrayList<>();
        AgentSession warm = new StubSession("warm", closed)
        {
            @Override
            public boolean retained()
            {
                return true;
            }

            @Override
            public void close()
            {
                assertThat(Thread.holdsLock(registry.taskLock(FIRST))).isFalse();
                CompletableFuture.runAsync(() -> {
                    synchronized (registry.taskLock(FIRST)) {
                        assertThatThrownBy(() -> registry.checkBranchAdmission(task)).hasMessageContaining("stopping");
                        assertThatThrownBy(() -> registry.requireStoppedSession(FIRST)).hasMessageContaining("stopping");
                    }
                }).orTimeout(5, TimeUnit.SECONDS).join();
                super.close();
            }
        };
        registry.registerSession(FIRST, warm);
        registry.retireIdleSessions(FIRST, true);
        assertThat(closed).containsExactly("warm");
        AgentSession replacement = session("replacement", closed);
        registry.registerSession(FIRST, replacement);
        registry.closeSession(FIRST, warm);
        assertThat(registry.session(FIRST)).contains(replacement);
        assertThat(closed).containsExactly("warm");
        registry.close();
    }

    @Test
    void closesEverySessionItStillHolds()
    {
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(mock(AgentTaskStore.class), ignored -> {});
        List<String> closed = new CopyOnWriteArrayList<>();
        registry.registerSession(FIRST, session("task-1", closed));
        registry.registerSession(SECOND, session("task-2", closed));

        registry.close();

        // A session owns the provider process serving its task, and only completing a task closes
        // one. Shutting down with tasks still open would otherwise strand those processes.
        assertThat(closed).containsExactlyInAnyOrder("task-1", "task-2");
        assertThat(registry.session(FIRST)).isEmpty();
    }

    @Test
    void closesTheRemainingSessionsWhenOneRefuses()
    {
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(mock(AgentTaskStore.class), ignored -> {});
        List<String> closed = new CopyOnWriteArrayList<>();
        registry.registerSession(FIRST, new StubSession("task-1", closed)
        {
            @Override
            public void close()
            {
                throw new IllegalStateException("provider is wedged");
            }
        });
        registry.registerSession(SECOND, session("task-2", closed));

        // One process that will not die must not strand every process behind it, but the failure
        // still surfaces rather than being swallowed at shutdown.
        assertThatThrownBy(registry::close).hasMessage("provider is wedged");

        assertThat(closed).containsExactly("task-2");
    }

    @Test
    void closesSessionsRegisteredAfterShutdownAndKeepsFailedClosesTracked()
    {
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(mock(AgentTaskStore.class), ignored -> {});
        List<String> closed = new CopyOnWriteArrayList<>();
        registry.close();

        assertThatThrownBy(() -> registry.registerSession(FIRST, session("late", closed)))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("shutting down");
        assertThat(closed).containsExactly("late");
        assertThat(registry.session(FIRST)).isEmpty();

        AgentSession wedged = new StubSession("wedged", closed)
        {
            @Override
            public void close()
            {
                throw new IllegalStateException("provider is wedged");
            }
        };
        assertThatThrownBy(() -> registry.registerSession(SECOND, wedged)).hasMessage("provider is wedged");
        assertThat(registry.session(SECOND)).contains(wedged);
        assertThat(registry.unverifiedClose(SECOND)).isPresent();
    }

    @Test
    void rejectsDevelopmentReservationsAndTurnsAfterShutdown()
    {
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(mock(AgentTaskStore.class), ignored -> {});
        TurnId source = TurnId.random();
        DevelopmentTurnOrigin origin = new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, source);
        registry.reserveDevelopmentOwner(FIRST, source);
        var pending = registry.registerDevelopmentTurn(FIRST, origin);

        registry.close();

        assertThat(pending).isCompletedExceptionally();
        assertThat(registry.hasDevelopmentOwner(FIRST)).isFalse();
        assertThatThrownBy(() -> registry.reserveDevelopmentOwner(FIRST, source)).isInstanceOf(AgentConflictException.class);
        assertThatThrownBy(() -> registry.registerDevelopmentTurn(FIRST, origin)).isInstanceOf(AgentConflictException.class);
        assertThat(registry.hasDevelopmentOwner(FIRST)).isFalse();
    }

    @Test
    void stoppedDevelopmentKeepsExclusionButCannotAuthorizeAnotherTurn()
    {
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(mock(AgentTaskStore.class), ignored -> {});
        TurnId source = TurnId.random();
        DevelopmentTurnOrigin origin = new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, source);
        registry.reserveDevelopmentOwner(FIRST, source);
        var pending = registry.registerDevelopmentTurn(FIRST, origin);

        assertThat(registry.stopDevelopment(FIRST)).containsExactly(pending);

        assertThat(registry.ownsDevelopmentContinuation(FIRST, source)).isFalse();
        assertThat(registry.hasDevelopmentOwner(FIRST)).isTrue();
        assertThatThrownBy(() -> registry.registerDevelopmentTurn(FIRST, origin)).isInstanceOf(AgentConflictException.class);
        registry.releaseDevelopmentOwner(FIRST, source);
        assertThat(registry.hasDevelopmentOwner(FIRST)).isFalse();
        assertThatThrownBy(() -> registry.reserveDevelopmentOwner(FIRST, source)).isInstanceOf(AgentConflictException.class);
    }

    @Test
    void stopRevokesBeforeDrainingAdmittedDevelopmentAction()
            throws Exception
    {
        assertDrainsDevelopmentAction(false);
    }

    @Test
    void shutdownRevokesBeforeDrainingAdmittedDevelopmentAction()
            throws Exception
    {
        assertDrainsDevelopmentAction(true);
    }

    private static void assertDrainsDevelopmentAction(boolean shutdown)
            throws Exception
    {
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(mock(AgentTaskStore.class), ignored -> {});
        TurnId source = TurnId.random();
        List<String> closed = new CopyOnWriteArrayList<>();
        registry.reserveDevelopmentOwner(FIRST, source);
        registry.registerSession(FIRST, session("source", closed));
        CompletableFuture<Void> entered = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<Void> action = CompletableFuture.runAsync(() -> registry.executeDevelopmentAction(FIRST, source, () -> {
            entered.complete(null);
            release.orTimeout(5, TimeUnit.SECONDS).join();
            synchronized (registry.taskLock(FIRST)) {
                // Stop must not retain this lock while draining synchronization or promotion.
                assertThat(registry.ownsDevelopmentContinuation(FIRST, source)).isFalse();
                assertThat(registry.hasDevelopmentOwner(FIRST)).isTrue();
            }
        }));
        CompletableFuture<Void> stopping = null;
        try {
            entered.get(5, TimeUnit.SECONDS);
            stopping = CompletableFuture.runAsync(() -> {
                if (shutdown) {
                    registry.close();
                    return;
                }
                synchronized (registry.taskLock(FIRST)) {
                    registry.stopDevelopment(FIRST);
                }
                registry.drainDevelopmentActions(FIRST);
                registry.closeSession(FIRST);
                synchronized (registry.taskLock(FIRST)) {
                    registry.releaseDevelopmentOwner(FIRST, source);
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (registry.ownsDevelopmentContinuation(FIRST, source) && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertThat(registry.ownsDevelopmentContinuation(FIRST, source)).isFalse();
            assertThat(registry.hasDevelopmentOwner(FIRST)).isTrue();
            CompletableFuture<Void> stopped = stopping;
            assertThatThrownBy(() -> stopped.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(closed).isEmpty();

            release.complete(null);
            action.get(5, TimeUnit.SECONDS);
            stopping.get(5, TimeUnit.SECONDS);
            assertThat(registry.hasDevelopmentOwner(FIRST)).isFalse();
            assertThat(closed).containsExactly("source");
            assertThatThrownBy(() -> registry.executeDevelopmentAction(FIRST, source, () -> {
                throw new AssertionError("A revoked continuation must not admit another action");
            })).isInstanceOf(AgentConflictException.class);
        }
        finally {
            release.complete(null);
            action.get(5, TimeUnit.SECONDS);
            if (stopping != null) {
                stopping.get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static AgentSession session(String taskId, List<String> closed)
    {
        return new StubSession(taskId, closed);
    }

    private static class StubSession
            implements AgentSession
    {
        private final String taskId;
        private final List<String> closed;

        StubSession(String taskId, List<String> closed)
        {
            this.taskId = taskId;
            this.closed = closed;
        }

        @Override
        public AgentSessionRef reference()
        {
            return new AgentSessionRef(new AgentLaneId("codex"), 1, taskId);
        }

        @Override
        public RunningTurn start(AgentTurnCommand command)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close()
        {
            closed.add(taskId);
        }
    }
}
