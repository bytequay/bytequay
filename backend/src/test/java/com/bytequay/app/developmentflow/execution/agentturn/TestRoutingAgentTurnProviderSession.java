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

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Access.READ_ONLY;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.ToolProfile.TASK_BRAIN_READ_ONLY;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Transport.API;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Transport.CLI;
import static org.assertj.core.api.Assertions.assertThat;

class TestRoutingAgentTurnProviderSession
{
    @Test
    void routesOnlyToTheFrozenTransport()
            throws Exception
    {
        AtomicInteger cli = new AtomicInteger();
        AtomicInteger api = new AtomicInteger();
        RoutingAgentTurnProviderSession router = new RoutingAgentTurnProviderSession(
                counting(cli), counting(api));

        router.open(request(CLI), observer()).close();
        router.open(request(API), observer()).close();
        router.open(request(API), observer()).close();

        assertThat(cli).hasValue(1);
        assertThat(api).hasValue(2);
    }

    private static AgentTurnProviderSession counting(AtomicInteger opens)
    {
        return (request, observer) -> {
            opens.incrementAndGet();
            return new AgentTurnProviderSession.Session()
            {
                @Override
                public AgentTurnProviderSession.Result startAndAwait(
                        AgentTurnProviderSession.WriterFence writerFence)
                {
                    throw new UnsupportedOperationException();
                }

                @Override public void cancel() {}

                @Override public void close() {}
            };
        };
    }

    private static AgentTurnProviderSession.Request request(
            AgentTurnProviderSession.Transport transport)
    {
        return new AgentTurnProviderSession.Request(
                transport,
                transport == CLI ? "codex" : "openai",
                transport == CLI ? null : "default",
                "model",
                null,
                Path.of("/tmp/worktree"),
                null,
                "prompt",
                new AgentTurnProviderSession.OwnerToolEndpoint(
                        "bytequay",
                        "http://127.0.0.1:8080/api/v2/task-turns/turn/operations/op/mcp",
                        DispatchTicket.OwnerKind.TASK_TURN,
                        "turn",
                        "op",
                        TASK_BRAIN_READ_ONLY,
                        "mcp__bytequay__approval_prompt"),
                READ_ONLY);
    }

    private static AgentTurnProviderSession.Observer observer()
    {
        return new AgentTurnProviderSession.Observer()
        {
            @Override public void providerSession(String provider, String sessionId) {}

            @Override public void processStarted(long pid, String logReference) {}

            @Override public void log(long sequence, String payloadJson) {}
        };
    }
}
