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
package com.bytequay.app.service.runs;

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.threads.ThreadAgent;
import com.bytequay.app.service.threads.ThreadRegistry;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSessionControlService
{
    private final AgentRunService runs = mock(AgentRunService.class);
    private final ThreadStore threads = mock(ThreadStore.class);
    private final ThreadTurnStore turns = mock(ThreadTurnStore.class);
    private final ThreadTurnScheduler scheduler =
            mock(ThreadTurnScheduler.class);
    private final ThreadRegistry registry = mock(ThreadRegistry.class);
    private final SessionControlService service = new SessionControlService(
            runs, threads, turns, scheduler, registry);

    @Test
    void resumeValidatesReplayBeforeChangingSessionState()
    {
        AgentRun broken = run(
                "run-1", AgentRun.STATUS_PAUSED, null, null);
        when(runs.findById("run-1")).thenReturn(Optional.of(broken));

        assertThatThrownBy(() -> service.resume("run-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stored launch request");

        verify(runs, never()).resume("run-1");
    }

    @Test
    void restartCreatesANewSessionAndReplaysTheStoredTrunkTurn()
    {
        Thread trunk = trunk();
        AgentRun prior = run(
                "run-old", AgentRun.STATUS_SUCCEEDED,
                "Re-check the implementation", trunk.id());
        AgentRun restarted = run(
                "run-new", AgentRun.STATUS_QUEUED,
                prior.launchInput(), trunk.id());
        when(runs.findById(prior.id())).thenReturn(Optional.of(prior));
        stubScope(prior.id(), ThreadScope.TRUNK);
        when(threads.findThreadById(trunk.id())).thenReturn(Optional.of(trunk));
        when(runs.restart(prior.id())).thenReturn(restarted);

        AgentRun result = service.restart(prior.id());

        assertThat(result.id()).isEqualTo("run-new");
        verify(scheduler).enqueueTrunkTurn(
                trunk, "Re-check the implementation", "run-new");
    }

    @Test
    void stopCancelsQueuedTurnsStopsTheAgentAndRecordsCancelledOutcome()
    {
        Thread trunk = trunk();
        AgentRun running = run(
                "run-1", AgentRun.STATUS_RUNNING, "Implement it", trunk.id());
        AgentRun stopped = running
                .withStatus(AgentRun.STATUS_CANCELLED, Instant.now());
        ThreadAgent agent = mock(ThreadAgent.class);
        when(runs.findById(running.id())).thenReturn(Optional.of(running));
        stubScope(running.id(), ThreadScope.TRUNK);
        when(registry.findTrunk(trunk.id())).thenReturn(Optional.of(agent));
        when(runs.transition(
                running.id(), AgentRun.STATUS_CANCELLED, "stopped by user"))
                .thenReturn(stopped);

        AgentRun result = service.stop(running.id());

        assertThat(result.outcome()).isEqualTo("cancelled");
        verify(scheduler).cancelSessionTurns(running.id());
        verify(agent).stop();
    }

    private void stubScope(String runId, ThreadScope scope)
    {
        ThreadTurn turn = mock(ThreadTurn.class);
        when(turn.scope()).thenReturn(scope);
        when(turns.listTurnsByAgentRunId(runId, 1)).thenReturn(List.of(turn));
    }

    private static AgentRun run(
            String id, String status, String launchInput, String threadId)
    {
        return new AgentRun(
                id,
                null,
                AgentRun.KIND_PLAN,
                AgentRun.SOURCE_SCHEDULED,
                null,
                null,
                null,
                status,
                0,
                null,
                null,
                null,
                Instant.parse("2026-07-17T00:00:00Z"),
                null,
                "ws-1",
                threadId,
                "claude-code",
                "claude-opus-4-8",
                0,
                0,
                0,
                0,
                launchInput,
                null,
                null);
    }

    private static Thread trunk()
    {
        Instant now = Instant.parse("2026-07-17T00:00:00Z");
        return new Thread(
                "trunk-1",
                ThreadKind.CLI_AGENT,
                "claude-code",
                null,
                "Workspace trunk",
                ThreadStatus.IDLE,
                "claude-opus-4-8",
                0,
                0,
                0,
                now,
                now,
                null,
                null,
                ThreadFlow.BUILD,
                "ws-1",
                null);
    }
}
