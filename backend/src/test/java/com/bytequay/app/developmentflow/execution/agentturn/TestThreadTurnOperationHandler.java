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
package com.bytequay.app.developmentflow.execution.agentturn;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.agents.ResolvedAgentContext;
import com.bytequay.app.service.agents.ToolExposurePolicy;
import com.bytequay.app.service.skills.ByteQuayRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.THREAD_TURN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestThreadTurnOperationHandler
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    private ActiveAgentContextRegistry contexts;
    private FakeStore store;
    private FakeProvider provider;
    private ThreadTurnOperationHandler handler;

    @BeforeEach
    void setUp()
            throws Exception
    {
        contexts = new ActiveAgentContextRegistry();
        store = new FakeStore(turn());
        provider = new FakeProvider(contexts);
        handler = new ThreadTurnOperationHandler(
                store, provider, contexts, new ToolExposurePolicy(), JSON,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void runsOnReservedControlCapacityAndScopesMcpToTheExactOperation()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(true);

        DispatchTicket.DispatchResult result = handler.execute(context(envelope, false));

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(store.starts).isOne();
        assertThat(provider.starts).isOne();
        assertThat(provider.contextWasActive).isTrue();
        assertThat(contexts.find("trunk-1", ThreadTurnOperationHandler.mcpAgentKey(
                "thread-turn-1", "operation-1"))).isEmpty();
        assertThat(provider.request.access())
                .isEqualTo(AgentTurnProviderSession.Access.READ_ONLY);
        assertThat(provider.request.images())
                .extracting(AgentTurnProviderSession.ImageAttachment::path)
                .containsExactly("/tmp/trunk-screenshot.png");
        AgentTurnOwnerResultCodec.OwnerResult decoded =
                new AgentTurnOwnerResultCodec(JSON).decode(
                        envelope.owner(), envelope.fence(), result);
        assertThat(decoded.payload().finalText()).isEqualTo("next task proposed");
    }

    @Test
    void wrongCapacityAndPreLaunchCancellationNeverStartTheProvider()
            throws Exception
    {
        DispatchTicket.DispatchResult wrong = handler.execute(
                context(envelope(false), false));
        assertThat(wrong.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(wrong.error()).contains("reserved Trunk control");
        assertThat(provider.opens).isZero();

        DispatchTicket.DispatchResult canceled = handler.execute(
                context(envelope(true), true));
        assertThat(canceled.outcome()).isEqualTo(DispatchTicket.Outcome.CANCELED);
        assertThat(provider.opens).isZero();
    }

    @Test
    void parallelTurnRetriesWithoutOpeningProviderAndReconciliationNeverReplays()
            throws Exception
    {
        store.start = ThreadTurnOperationHandler.StartDisposition.OTHER_TURN_RUNNING;
        assertThatThrownBy(() -> handler.execute(context(envelope(true), false)))
                .isInstanceOf(ExecutionPorts.RetryableExecutionException.class);
        assertThat(provider.opens).isZero();
        assertThat(provider.starts).isZero();

        DispatchTicket.DispatchResult reconciled = handler.reconcile(
                context(envelope(true), false));
        assertThat(reconciled.outcome())
                .isEqualTo(DispatchTicket.Outcome.INDETERMINATE);
        assertThat(provider.opens).isZero();
    }

    @Test
    void threadTurnPurposeCannotChangeItsTrunkRole()
            throws Exception
    {
        store = new FakeStore(turn("TASK_COMPLETION_SUMMARY"));
        provider = new FakeProvider(contexts);
        handler = new ThreadTurnOperationHandler(
                store, provider, contexts, new ToolExposurePolicy(), JSON,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(handler.execute(context(envelope(true), false)).outcome())
                .isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(provider.resolved.role()).isEqualTo(ByteQuayRole.TRUNK);
        assertThat(provider.resolved.toolNames())
                .isEqualTo(new ToolExposurePolicy().activeTools(
                        ByteQuayRole.TRUNK, null));
    }

    private static ExecutionContext context(
            DispatchTicket.DispatchEnvelope envelope, boolean canceled)
    {
        ExecutionContext context = mock(ExecutionContext.class);
        when(context.envelope()).thenReturn(envelope);
        when(context.isCancellationRequested()).thenReturn(canceled);
        return context;
    }

    private static DispatchTicket.DispatchEnvelope envelope(boolean reserved)
    {
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                null, null, null, "operation-1", 1,
                null, null, null);
        CapacityManager.CapacityRequest capacity =
                new CapacityManager.CapacityRequest(
                        "operation-1",
                        CapacityManager.WorkflowSource.V2,
                        Set.of(CapacityManager.CapacityLane.CLI),
                        new CapacityManager.CapacityScope(
                                "workspace-1", "trunk-1", null, null),
                        reserved, false, false);
        return new DispatchTicket.DispatchEnvelope(
                ThreadTurnOperationHandler.OPERATION_KIND,
                DispatchTicket.AsyncFamily.AGENT_TURN,
                new DispatchTicket.OwnerReference(
                        THREAD_TURN, "thread-turn-1",
                        ThreadTurnOperationHandler.CALLBACK_ROUTE),
                fence, capacity);
    }

    private static ThreadTurnOperationHandler.ExactTurn turn()
            throws Exception
    {
        return turn("PLANNING");
    }

    private static ThreadTurnOperationHandler.ExactTurn turn(String purpose)
            throws Exception
    {
        AgentTurnProviderSession.OwnerToolEndpoint endpoint =
                new AgentTurnProviderSession.OwnerToolEndpoint(
                        "bytequay",
                        "http://127.0.0.1:53123/api/v2/thread-turns/"
                                + "thread-turn-1/operations/operation-1/mcp",
                        THREAD_TURN, "thread-turn-1", "operation-1",
                        AgentTurnProviderSession.ToolProfile.TRUNK_CONTROL_READ_ONLY,
                        "mcp__bytequay__approval_prompt");
        String input = JSON.writeValueAsString(
                new AgentTurnOperationHandler.LaunchInput(
                        1, AgentTurnProviderSession.Transport.CLI, "codex", null,
                        "gpt-5.6", "high", "/tmp", "trunk role",
                        "propose the next task",
                        List.of(new AgentTurnProviderSession.ImageAttachment(
                                "/tmp/trunk-screenshot.png", "image/png",
                                "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81")),
                        endpoint));
        return new ThreadTurnOperationHandler.ExactTurn(
                "thread-turn-1", "trunk-1", "workspace-1", purpose,
                "REQUESTED", "operation-1", 1, input, "ACTIVE");
    }

    private static final class FakeStore
            implements ThreadTurnOperationHandler.Store
    {
        private final ThreadTurnOperationHandler.ExactTurn turn;
        private ThreadTurnOperationHandler.StartDisposition start =
                ThreadTurnOperationHandler.StartDisposition.STARTED;
        private int starts;

        private FakeStore(ThreadTurnOperationHandler.ExactTurn turn)
        {
            this.turn = turn;
        }

        @Override
        public Optional<ThreadTurnOperationHandler.ExactTurn> find(String turnId)
        {
            return turn.turnId().equals(turnId)
                    ? Optional.of(turn) : Optional.empty();
        }

        @Override
        public ThreadTurnOperationHandler.StartDisposition tryStart(
                String turnId, String operationId, Instant startedAt)
        {
            starts++;
            return start;
        }

        @Override
        public boolean resetAfterLaunchFailure(
                String turnId, String operationId, Instant resetAt)
        {
            return true;
        }

        @Override
        public Optional<String> findMcpTrunk(
                String turnId, String operationId, Instant now)
        {
            return Optional.empty();
        }
    }

    private static final class FakeProvider
            implements AgentTurnProviderSession
    {
        private final ActiveAgentContextRegistry contexts;
        private Request request;
        private int opens;
        private int starts;
        private boolean contextWasActive;
        private ResolvedAgentContext resolved;

        private FakeProvider(ActiveAgentContextRegistry contexts)
        {
            this.contexts = contexts;
        }

        @Override
        public Session open(Request request, Observer observer)
        {
            this.request = request;
            opens++;
            return new Session()
            {
                @Override
                public Result startAndAwait(WriterFence writerFence)
                {
                    starts++;
                    resolved = contexts.find(
                            "trunk-1", ThreadTurnOperationHandler.mcpAgentKey(
                                    "thread-turn-1", "operation-1"))
                            .orElse(null);
                    contextWasActive = resolved != null;
                    observer.providerSession("codex", "session-1");
                    return new Result(
                            Completion.SUCCEEDED, "session-1",
                            "next task proposed", 2, 3, 1, 123L, null);
                }

                @Override
                public void cancel() {}

                @Override
                public void close() {}
            };
        }
    }
}
