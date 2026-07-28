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

import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnResult;
import com.bytequay.app.service.agents.TurnSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.STAGE_TURN;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.TASK_TURN;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Access.READ_ONLY;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Access.WORKTREE_WRITE;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Completion.CANCELED;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Completion.FAILED;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Completion.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.ToolProfile.STAGE_DEVELOPMENT;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.ToolProfile.TASK_BRAIN_READ_ONLY;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Transport.API;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Transport.CLI;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestApiAgentTurnProviderSession
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path WORKTREE = Path.of("/tmp/api-agent-turn-worktree");

    @Test
    void executesAnthropicTurnAgainstOnlyItsExactOwnerTools()
            throws Exception
    {
        RecordingTools tools = new RecordingTools();
        AtomicReference<TurnSpec> captured = new AtomicReference<>();
        RecordingObserver observer = new RecordingObserver();
        ApiAgentTurnProviderSession provider = new ApiAgentTurnProviderSession(
                ignored -> new ApiAgentTurnProviderSession.ResolvedProvider(
                        TurnSpec.Transport.ANTHROPIC,
                        "https://api.anthropic.com/v1/messages",
                        "secret"),
                tools,
                (spec, executor, hooks) -> {
                    captured.set(spec);
                    hooks.onTextDelta(0, "working");
                    hooks.onToolCallStarted("call-1", "record_plan", "{}");
                    ToolExecutor.ToolCallResult result = executor.execute(new ToolCall(
                            "call-1", "record_plan", "{}", JSON.createObjectNode()));
                    hooks.onToolCallDone("call-1", result.text(), result.isError());
                    return new TurnResult("done", 11, 7, 3, 2,
                            TurnResult.End.COMPLETED);
                },
                JSON);

        AgentTurnProviderSession.Result result;
        try (AgentTurnProviderSession.Session session = provider.open(
                request(READ_ONLY, TurnSpec.Transport.ANTHROPIC), observer)) {
            result = session.startAndAwait(null);
        }

        assertThat(result.completion()).isEqualTo(SUCCEEDED);
        assertThat(result.finalText()).isEqualTo("done");
        assertThat(result.inputTokens()).isEqualTo(11);
        assertThat(result.outputTokens()).isEqualTo(7);
        assertThat(result.costUsdMilli()).isEqualTo(3);
        assertThat(result.providerSessionId()).startsWith("api-");
        assertThat(observer.sessionId).isEqualTo(result.providerSessionId());
        assertThat(observer.logs).hasSize(3);

        TurnSpec spec = captured.get();
        assertThat(spec.transport()).isEqualTo(TurnSpec.Transport.ANTHROPIC);
        assertThat(spec.system()).isEqualTo("system");
        assertThat(spec.messages()).hasSize(1);
        assertThat(spec.messages().get(0).path("role").asText()).isEqualTo("user");
        assertThat(spec.tools()).hasSize(1);
        assertThat(spec.tools().get(0).path("name").asText()).isEqualTo("record_plan");
        assertThat(spec.tools().get(0).has("input_schema")).isTrue();
        assertThat(tools.listEndpoint).isEqualTo(endpoint(READ_ONLY));
        assertThat(tools.callEndpoint).isEqualTo(endpoint(READ_ONLY));
        assertThat(tools.listFence).isNull();
        assertThat(tools.callFence).isNull();
    }

    @Test
    void sendsTheExactWriterFenceAndRendersOpenAiTools()
            throws Exception
    {
        RecordingTools tools = new RecordingTools();
        AtomicReference<TurnSpec> captured = new AtomicReference<>();
        ApiAgentTurnProviderSession provider = new ApiAgentTurnProviderSession(
                ignored -> new ApiAgentTurnProviderSession.ResolvedProvider(
                        TurnSpec.Transport.OPENAI_COMPAT,
                        "https://api.openai.com/v1/chat/completions",
                        "secret"),
                tools,
                (spec, executor, hooks) -> {
                    captured.set(spec);
                    executor.execute(new ToolCall(
                            "call-1", "record_plan", "{}", JSON.createObjectNode()));
                    return new TurnResult("done", 1, 2, 0, 1,
                            TurnResult.End.MAX_STEPS);
                },
                JSON);
        AgentTurnProviderSession.WriterFence fence = fence();

        AgentTurnProviderSession.Result result;
        try (AgentTurnProviderSession.Session session = provider.open(
                request(WORKTREE_WRITE, TurnSpec.Transport.OPENAI_COMPAT),
                new RecordingObserver())) {
            result = session.startAndAwait(fence);
        }

        assertThat(result.completion()).isEqualTo(SUCCEEDED);
        assertThat(tools.listFence).isEqualTo(fence);
        assertThat(tools.callFence).isEqualTo(fence);
        TurnSpec spec = captured.get();
        assertThat(spec.system()).isNull();
        assertThat(spec.messages()).hasSize(2);
        assertThat(spec.messages().get(0).path("role").asText()).isEqualTo("system");
        assertThat(spec.messages().get(1).path("role").asText()).isEqualTo("user");
        assertThat(spec.tools().get(0).path("type").asText()).isEqualTo("function");
        assertThat(spec.tools().get(0).path("function").path("name").asText())
                .isEqualTo("record_plan");
    }

    @Test
    void rejectsMismatchedTransportAndWriterAuthority()
            throws Exception
    {
        ApiAgentTurnProviderSession provider = providerReturning(TurnResult.End.COMPLETED);
        AgentTurnProviderSession.Request apiRead =
                request(READ_ONLY, TurnSpec.Transport.ANTHROPIC);
        AgentTurnProviderSession.Request cli = new AgentTurnProviderSession.Request(
                CLI,
                "codex",
                null,
                "gpt-5.6",
                null,
                WORKTREE,
                "system",
                "prompt",
                endpoint(READ_ONLY),
                READ_ONLY);

        assertThatThrownBy(() -> provider.open(cli, new RecordingObserver()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CLI transport");
        try (AgentTurnProviderSession.Session session = provider.open(
                apiRead, new RecordingObserver())) {
            assertThatThrownBy(() -> session.startAndAwait(fence()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("read-only");
        }
        try (AgentTurnProviderSession.Session session = provider.open(
                request(WORKTREE_WRITE, TurnSpec.Transport.OPENAI_COMPAT),
                new RecordingObserver())) {
            AgentTurnProviderSession.WriterFence wrong =
                    new AgentTurnProviderSession.WriterFence(
                            WORKTREE.toString(), "task-1", "other-operation", 1, 1);
            assertThatThrownBy(() -> session.startAndAwait(wrong))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exact writer fence");
        }
    }

    @Test
    void cancellationIsInstalledBeforeProviderResolution()
            throws Exception
    {
        AtomicInteger resolutions = new AtomicInteger();
        ApiAgentTurnProviderSession provider = new ApiAgentTurnProviderSession(
                ignored -> {
                    resolutions.incrementAndGet();
                    throw new AssertionError("must not resolve");
                },
                new RecordingTools(),
                (spec, tools, hooks) -> {
                    throw new AssertionError("must not run");
                },
                JSON);

        AgentTurnProviderSession.Result result;
        try (AgentTurnProviderSession.Session session = provider.open(
                request(READ_ONLY, TurnSpec.Transport.ANTHROPIC),
                new RecordingObserver())) {
            session.cancel();
            result = session.startAndAwait(null);
        }

        assertThat(result.completion()).isEqualTo(CANCELED);
        assertThat(resolutions).hasValue(0);
    }

    @Test
    void cancellationInterruptsTheExactRunningTurn()
            throws Exception
    {
        CountDownLatch running = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        ApiAgentTurnProviderSession provider = new ApiAgentTurnProviderSession(
                ignored -> new ApiAgentTurnProviderSession.ResolvedProvider(
                        TurnSpec.Transport.ANTHROPIC,
                        "https://api.anthropic.com/v1/messages",
                        "secret"),
                new RecordingTools(),
                (spec, tools, hooks) -> {
                    running.countDown();
                    try {
                        new CountDownLatch(1).await();
                        throw new AssertionError("turn was not interrupted");
                    }
                    catch (InterruptedException expected) {
                        Thread.currentThread().interrupt();
                    }
                    interrupted.set(hooks.interrupted());
                    return new TurnResult("", 0, 0, 0, 0,
                            TurnResult.End.INTERRUPTED);
                },
                JSON);
        AgentTurnProviderSession.Session session = provider.open(
                request(READ_ONLY, TurnSpec.Transport.ANTHROPIC),
                new RecordingObserver());
        CompletableFuture<AgentTurnProviderSession.Result> future =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return session.startAndAwait(null);
                    }
                    catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                });

        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        session.cancel();
        AgentTurnProviderSession.Result result = future.get(5, TimeUnit.SECONDS);
        session.close();

        assertThat(result.completion()).isEqualTo(CANCELED);
        assertThat(interrupted).isTrue();
    }

    @Test
    void providerBudgetAbortIsAFailedOperation()
            throws Exception
    {
        ApiAgentTurnProviderSession provider = providerReturning(TurnResult.End.ABORTED);

        AgentTurnProviderSession.Result result;
        try (AgentTurnProviderSession.Session session = provider.open(
                request(READ_ONLY, TurnSpec.Transport.ANTHROPIC),
                new RecordingObserver())) {
            result = session.startAndAwait(null);
        }

        assertThat(result.completion()).isEqualTo(FAILED);
        assertThat(result.error()).contains("budget was exhausted");
    }

    private static ApiAgentTurnProviderSession providerReturning(TurnResult.End end)
    {
        return new ApiAgentTurnProviderSession(
                ignored -> new ApiAgentTurnProviderSession.ResolvedProvider(
                        TurnSpec.Transport.ANTHROPIC,
                        "https://api.anthropic.com/v1/messages",
                        "secret"),
                new RecordingTools(),
                (spec, tools, hooks) -> new TurnResult("result", 0, 0, 0, 1, end),
                JSON);
    }

    private static AgentTurnProviderSession.Request request(
            AgentTurnProviderSession.Access access,
            TurnSpec.Transport ignoredProviderTransport)
    {
        return new AgentTurnProviderSession.Request(
                API,
                ignoredProviderTransport == TurnSpec.Transport.ANTHROPIC
                        ? "anthropic" : "openai",
                "account-1",
                "model-1",
                "high",
                WORKTREE,
                "system",
                "prompt",
                endpoint(access),
                access);
    }

    private static AgentTurnProviderSession.OwnerToolEndpoint endpoint(
            AgentTurnProviderSession.Access access)
    {
        boolean write = access == WORKTREE_WRITE;
        return new AgentTurnProviderSession.OwnerToolEndpoint(
                "bytequay",
                write
                        ? "http://127.0.0.1:53123/api/v2/stage-turns/stage-turn-1/"
                                + "operations/operation-1/mcp"
                        : "http://127.0.0.1:53123/api/v2/task-turns/task-turn-1/"
                                + "operations/operation-1/mcp",
                write ? STAGE_TURN : TASK_TURN,
                write ? "stage-turn-1" : "task-turn-1",
                "operation-1",
                write ? STAGE_DEVELOPMENT : TASK_BRAIN_READ_ONLY,
                "mcp__bytequay__approval_prompt");
    }

    private static AgentTurnProviderSession.WriterFence fence()
    {
        return new AgentTurnProviderSession.WriterFence(
                WORKTREE.toString(), "task-1", "operation-1", 4, 9);
    }

    private static final class RecordingTools
            implements ApiAgentTurnProviderSession.OwnerMcpClient
    {
        private AgentTurnProviderSession.OwnerToolEndpoint listEndpoint;
        private AgentTurnProviderSession.OwnerToolEndpoint callEndpoint;
        private AgentTurnProviderSession.WriterFence listFence;
        private AgentTurnProviderSession.WriterFence callFence;

        @Override
        public List<ToolDefinition> list(
                AgentTurnProviderSession.OwnerToolEndpoint endpoint,
                AgentTurnProviderSession.WriterFence writerFence)
        {
            listEndpoint = endpoint;
            listFence = writerFence;
            return List.of(new ToolDefinition(
                    "record_plan",
                    "Record the plan",
                    JSON.createObjectNode().put("type", "object")));
        }

        @Override
        public ToolExecutor.ToolCallResult call(
                AgentTurnProviderSession.OwnerToolEndpoint endpoint,
                AgentTurnProviderSession.WriterFence writerFence,
                ToolCall call)
        {
            callEndpoint = endpoint;
            callFence = writerFence;
            return ToolExecutor.ToolCallResult.ok("recorded");
        }
    }

    private static final class RecordingObserver
            implements AgentTurnProviderSession.Observer
    {
        private String sessionId;
        private final List<String> logs = new ArrayList<>();

        @Override
        public void providerSession(String provider, String sessionId)
        {
            this.sessionId = sessionId;
        }

        @Override
        public void processStarted(long pid, String logReference)
        {
            throw new AssertionError("API turns do not start a child process");
        }

        @Override
        public void log(long sequence, String payloadJson)
        {
            assertThat(sequence).isEqualTo(logs.size());
            logs.add(payloadJson);
        }
    }
}
