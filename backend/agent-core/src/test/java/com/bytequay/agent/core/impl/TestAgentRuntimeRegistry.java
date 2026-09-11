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
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.RunningTurn;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAgentRuntimeRegistry
{
    private static final TaskId FIRST = TaskId.random();
    private static final TaskId SECOND = TaskId.random();

    @Test
    void closesEverySessionItStillHolds()
    {
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry();
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
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry();
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
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry();
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
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry();
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
