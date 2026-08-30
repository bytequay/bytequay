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
package com.bytequay.agent.cli.codex;

import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessOutputSink;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.RunningTurn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCodexCliDriver
{
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    private Path worktree;

    @Test
    void probesResumesAndMapsAnAuthoritativeRedactedResult()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        AtomicReference<ProviderApprovalRequest> approval = new AtomicReference<>();

        assertThat(driver.probe().health()).isEqualTo(AgentProviderStatus.Health.AVAILABLE);
        AgentSession initial = driver.open(context(Optional.empty(), approval), events::add);
        AgentSessionRef reference = initial.reference();
        initial.close();
        AgentSession resumed = driver.open(context(Optional.of(reference), approval), events::add);
        assertThat(supervisor.server().resumeCount()).isOne();

        RunningTurn turn = resumed.start(command("complete"));
        supervisor.server().completeTurn();
        AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(result.finalResponse()).contains("authoritative [REDACTED]");
        assertThat(result.toString()).doesNotContain("known-secret");
        assertThat(approval.get().detail()).isEqualTo("echo [REDACTED]");
        assertThat(supervisor.server().approvalDecision()).isEqualTo("accept");
        assertThat(events).anySatisfy(event -> {
            assertThat(event).isInstanceOf(ProviderAgentEvent.Message.class);
            ProviderAgentEvent.Message message = (ProviderAgentEvent.Message) event;
            assertThat(message.phase()).isEqualTo(ProviderAgentEvent.MessagePhase.PARTIAL);
            assertThat(message.text()).isEqualTo("draft [REDACTED]");
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event).isInstanceOf(ProviderAgentEvent.Usage.class);
            ProviderAgentEvent.Usage usage = (ProviderAgentEvent.Usage) event;
            assertThat(usage.sample().semantics()).isEqualTo(
                    com.bytequay.agent.spi.ProviderUsageSample.Semantics.CUMULATIVE);
            assertThat(usage.sample().inputTokens()).isEqualTo(80);
            assertThat(usage.sample().outputTokens()).isEqualTo(20);
        });
        assertThat(events.toString()).doesNotContain("known-secret");

        resumed.close();

        assertThat(driver.capabilities().permissionPolicies())
                .containsExactly(AgentPermissionPolicy.AUTO);
        assertThat(supervisor.server().approvalPolicies()).containsExactly("on-request", "on-request");

        driver.close();
    }

    @Test
    void reportsCancellationFailureAndAppServerDisconnect()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        assertThat(driver.probe().health()).isEqualTo(AgentProviderStatus.Health.AVAILABLE);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});

        RunningTurn cancelled = session.start(command("cancel"));
        cancelled.cancel();
        AgentTurnResult cancelledResult = cancelled.completion()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertThat(cancelledResult.state()).isEqualTo(AgentTurnResult.State.CANCELLED);

        RunningTurn failed = session.start(command("fail"));
        supervisor.server().failTurn();
        AgentTurnResult failedResult = failed.completion()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertThat(failedResult.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(failedResult.terminalReason()).hasValueSatisfying(reason ->
                assertThat(reason).contains("Codex CLI 0.151.0").contains("provider failed"));

        supervisor.versionOutput("future-build");
        assertThat(driver.probe().version()).contains("future-build");

        RunningTurn disconnected = session.start(command("disconnect"));
        supervisor.server().disconnect();
        AgentTurnResult disconnectedResult = disconnected.completion()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertThat(disconnectedResult.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
        assertThat(disconnectedResult.terminalReason()).hasValueSatisfying(reason ->
                assertThat(reason).contains("Codex CLI 0.151.0").contains("exited with code 1"));

        driver.close();
    }

    @Test
    void acceptsAndReportsTheInstalledCodexVersion()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper, "future-build");
        CodexCliDriver driver = driver(supervisor);

        AgentProviderStatus status = driver.probe();

        assertThat(status.health()).isEqualTo(AgentProviderStatus.Health.AVAILABLE);
        assertThat(status.version()).contains("future-build");
        driver.close();
    }

    @Test
    void reportsTheInstalledVersionWhenAppServerIsIncompatible()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper, "future-build", true);
        CodexCliDriver driver = driver(supervisor);

        assertThatThrownBy(() -> driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("Codex CLI future-build")
                .hasMessageContaining("thread/start")
                .hasMessageContaining("unsupported protocol");

        driver.close();
    }

    private CodexCliDriver driver(ProcessSupervisor supervisor)
    {
        return new CodexCliDriver(
                "/fake/codex",
                objectMapper,
                supervisor,
                ImmutableMap.of(
                        "HOME", worktree.toString(),
                        "OPENAI_API_KEY", "known-secret"));
    }

    private AgentSessionContext context(
            Optional<AgentSessionRef> reference,
            AtomicReference<ProviderApprovalRequest> approval)
    {
        return new AgentSessionContext(
                "task-1",
                worktree,
                worktree,
                worktree.resolve("provider"),
                AgentPermissionPolicy.AUTO,
                reference,
                (request, timeout) -> {
                    approval.set(request);
                    return CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty()));
                });
    }

    private static AgentTurnCommand command(String message)
    {
        return new AgentTurnCommand("turn-" + message, message, ImmutableList.of(), Duration.ofMinutes(1));
    }

    private static final class ScriptedProcessSupervisor
            implements ProcessSupervisor
    {
        private final ObjectMapper objectMapper;
        private final boolean failThreadStart;
        private volatile String versionOutput;
        private AppServerProcess server;

        private ScriptedProcessSupervisor(ObjectMapper objectMapper)
        {
            this(objectMapper, "codex-cli 0.151.0", false);
        }

        private ScriptedProcessSupervisor(ObjectMapper objectMapper, String versionOutput)
        {
            this(objectMapper, versionOutput, false);
        }

        private ScriptedProcessSupervisor(ObjectMapper objectMapper, String versionOutput, boolean failThreadStart)
        {
            this.objectMapper = objectMapper;
            this.versionOutput = versionOutput;
            this.failThreadStart = failThreadStart;
        }

        @Override
        public ManagedProcess start(ProcessSpec spec, ProcessOutputSink output)
        {
            if (spec.argv().contains("--version")) {
                output.onOutput(
                        ProcessStream.STDOUT,
                        StandardCharsets.UTF_8.encode(versionOutput + "\n"));
                return ImmediateProcess.success();
            }
            if (spec.argv().contains("login")) {
                output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode("Logged in using ChatGPT\n"));
                return ImmediateProcess.success();
            }
            server = new AppServerProcess(objectMapper, output, failThreadStart);
            return server;
        }

        private AppServerProcess server()
        {
            return server;
        }

        private void versionOutput(String versionOutput)
        {
            this.versionOutput = versionOutput;
        }
    }

    private static final class AppServerProcess
            implements ManagedProcess
    {
        private final ObjectMapper objectMapper;
        private final ProcessOutputSink output;
        private final boolean failThreadStart;
        private final CompletableFuture<ProcessExit> exit = new CompletableFuture<>();
        private final AtomicInteger turns = new AtomicInteger();
        private final AtomicInteger resumes = new AtomicInteger();
        private final List<String> approvalPolicies = new CopyOnWriteArrayList<>();
        private volatile String activeTurn;
        private volatile String approvalDecision = "";

        private AppServerProcess(ObjectMapper objectMapper, ProcessOutputSink output, boolean failThreadStart)
        {
            this.objectMapper = objectMapper;
            this.output = output;
            this.failThreadStart = failThreadStart;
        }

        @Override
        public long pid()
        {
            return 4242;
        }

        @Override
        public CompletionStage<Void> write(ByteBuffer bytes)
        {
            try {
                JsonNode message = objectMapper.readTree(StandardCharsets.UTF_8.decode(bytes).toString());
                receive(message);
                return CompletableFuture.completedFuture(null);
            }
            catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        private void receive(JsonNode message)
        {
            String method = message.path("method").asText();
            long id = message.path("id").asLong();
            switch (method) {
                case "initialize" -> respond(id, "{}");
                case "initialized" -> {}
                case "thread/start" -> {
                    approvalPolicies.add(message.path("params").path("approvalPolicy").asText());
                    if (failThreadStart) {
                        emit("{\"id\":" + id + ",\"error\":{\"code\":-32602,\"message\":\"unsupported protocol\"}}");
                    }
                    else {
                        respond(id, "{\"thread\":{\"id\":\"thread-1\"}}");
                    }
                }
                case "thread/resume" -> {
                    approvalPolicies.add(message.path("params").path("approvalPolicy").asText());
                    resumes.incrementAndGet();
                    respond(id, "{\"thread\":{\"id\":\"thread-1\"}}");
                }
                case "thread/unsubscribe" -> respond(id, "{\"status\":\"unsubscribed\"}");
                case "turn/start" -> {
                    activeTurn = "turn-" + turns.incrementAndGet();
                    respond(id, "{\"turn\":{\"id\":\"" + activeTurn
                            + "\",\"status\":\"inProgress\",\"error\":null,\"durationMs\":null}}");
                }
                case "turn/interrupt" -> {
                    respond(id, "{}");
                    emit("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\","
                            + "\"turn\":{\"id\":\"" + activeTurn
                            + "\",\"status\":\"interrupted\",\"error\":null,\"durationMs\":1}}}");
                }
                default -> {
                    if (id == 91) {
                        approvalDecision = message.path("result").path("decision").asText();
                    }
                }
            }
        }

        private void completeTurn()
        {
            emit("{\"id\":91,\"method\":\"item/commandExecution/requestApproval\",\"params\":{"
                    + "\"threadId\":\"thread-1\",\"turnId\":\"" + activeTurn + "\","
                    + "\"itemId\":\"exec-1\",\"command\":\"echo known-secret\","
                    + "\"reason\":null,\"startedAtMs\":1786884871461}}");
            emit("{\"method\":\"item/agentMessage/delta\",\"params\":{"
                    + "\"threadId\":\"thread-1\",\"turnId\":\"" + activeTurn + "\","
                    + "\"itemId\":\"message-1\",\"delta\":\"draft known-secret\"}}");
            emit("{\"method\":\"item/completed\",\"params\":{\"item\":{"
                    + "\"type\":\"agentMessage\",\"id\":\"message-1\","
                    + "\"text\":\"authoritative known-secret\",\"phase\":\"final_answer\"},"
                    + "\"threadId\":\"thread-1\",\"turnId\":\"" + activeTurn + "\"}}");
            emit("{\"method\":\"thread/tokenUsage/updated\",\"params\":{"
                    + "\"threadId\":\"thread-1\",\"turnId\":\"" + activeTurn + "\","
                    + "\"tokenUsage\":{\"total\":{\"totalTokens\":100,\"inputTokens\":80,"
                    + "\"cachedInputTokens\":30,\"outputTokens\":20}}}}");
            emit("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\","
                    + "\"turn\":{\"id\":\"" + activeTurn
                    + "\",\"status\":\"completed\",\"error\":null,\"durationMs\":2}}}");
        }

        private void failTurn()
        {
            emit("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\","
                    + "\"turn\":{\"id\":\"" + activeTurn
                    + "\",\"status\":\"failed\",\"error\":{\"message\":\"provider failed\"},\"durationMs\":2}}}");
        }

        private void disconnect()
        {
            exit.complete(new ProcessExit(1, ProcessExit.Reason.EXITED, Duration.ZERO, false, false));
        }

        private int resumeCount()
        {
            return resumes.get();
        }

        private List<String> approvalPolicies()
        {
            return approvalPolicies;
        }

        private String approvalDecision()
        {
            return approvalDecision;
        }

        private void respond(long id, String result)
        {
            emit("{\"id\":" + id + ",\"result\":" + result + "}");
        }

        private void emit(String message)
        {
            output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode(message + "\n"));
        }

        @Override
        public void closeInput() {}

        @Override
        public CompletionStage<ProcessExit> exit()
        {
            return exit;
        }

        @Override
        public void cancel()
        {
            exit.complete(new ProcessExit(143, ProcessExit.Reason.CANCELLED, Duration.ZERO, false, false));
        }

        @Override
        public void close()
        {
            cancel();
        }
    }

    private record ImmediateProcess(CompletableFuture<ProcessExit> exit)
            implements ManagedProcess
    {
        private static ImmediateProcess success()
        {
            return new ImmediateProcess(CompletableFuture.completedFuture(
                    new ProcessExit(0, ProcessExit.Reason.EXITED, Duration.ZERO, false, false)));
        }

        @Override
        public long pid()
        {
            return 1;
        }

        @Override
        public CompletionStage<Void> write(ByteBuffer bytes)
        {
            return CompletableFuture.failedFuture(new IllegalStateException("input is closed"));
        }

        @Override
        public void closeInput() {}

        @Override
        public void cancel() {}

        @Override
        public void close() {}
    }
}
