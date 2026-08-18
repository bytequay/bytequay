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
package com.bytequay.agent.cli.claude;

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
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.RunningTurn;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
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
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class TestClaudeCliDriver
{
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    private Path worktree;

    @Test
    void probesStreamsResumesHandlesMalformedAndTrailingEventsAndCancels()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudePermissionBroker permissions = new ClaudePermissionBroker(
                () -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"),
                objectMapper,
                (taskId, query) -> "memory");
        ClaudeCliDriver driver = new ClaudeCliDriver(
                "/fake/claude",
                objectMapper,
                permissions,
                supervisor,
                ImmutableMap.of(
                        "HOME", worktree.toString(),
                        "ANTHROPIC_API_KEY", "known-secret"));
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();

        assertThat(driver.probe().health()).isEqualTo(AgentProviderStatus.Health.AVAILABLE);
        assertThat(driver.capabilities().permissionPolicies())
                .containsExactlyInAnyOrder(AgentPermissionPolicy.MANUAL, AgentPermissionPolicy.CI_AUTOFIX);
        AgentSession session = driver.open(context(Optional.empty()), events::add);
        AgentSessionRef reference = session.reference();

        AgentTurnResult first = await(session.start(command("first")));
        assertThat(first.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(first.finalResponse()).contains("authoritative [REDACTED]");
        ProcessSpec firstSpec = supervisor.turns().getFirst().spec();
        assertThat(firstSpec.argv()).containsSequence("--session-id", reference.providerValue());
        assertThat(firstSpec.argv()).contains("--permission-prompt-tool", "mcp__bytequay__permission_prompt");
        assertThat(firstSpec.argv()).doesNotContain("first");
        assertThat(supervisor.turns().getFirst().prompt()).isEqualTo("first\n");
        assertThat(events).anyMatch(ProviderAgentEvent.Error.class::isInstance);
        assertThat(events).filteredOn(ProviderAgentEvent.Tool.class::isInstance)
                .anySatisfy(event -> assertThat(((ProviderAgentEvent.Tool) event).name())
                        .isEqualTo("hook/Stop"));
        int trailingHook = indexOf(events, event -> event instanceof ProviderAgentEvent.Tool tool
                && tool.name().equals("hook/Stop"));
        int completion = indexOf(events, event -> event instanceof ProviderAgentEvent.Lifecycle lifecycle
                && lifecycle.phase() == ProviderAgentEvent.LifecyclePhase.TURN_COMPLETED);
        assertThat(trailingHook).isLessThan(completion);
        assertThat(events.toString()).doesNotContain("known-secret");

        AgentTurnResult second = await(session.start(command("second")));
        assertThat(second.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(supervisor.turns().get(1).spec().argv())
                .containsSequence("--resume", reference.providerValue());

        AgentTurnResult failed = await(session.start(command("fail")));
        assertThat(failed.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(failed.terminalReason()).contains("[REDACTED] diagnostic");

        RunningTurn cancelled = session.start(command("cancel"));
        cancelled.cancel();
        assertThat(await(cancelled).state()).isEqualTo(AgentTurnResult.State.CANCELLED);

        Path configFile = worktree.resolve("provider/bytequay-mcp.json");
        assertThat(configFile).exists();
        session.close();
        assertThat(configFile).doesNotExist();

        AgentSession ciSession = driver.open(context(Optional.empty(), AgentPermissionPolicy.CI_AUTOFIX), events::add);
        assertThat(await(ciSession.start(command("ci-fix"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        ProcessSpec ciSpec = supervisor.turns().getLast().spec();
        assertThat(ciSpec.argv()).contains("--strict-mcp-config");
        assertThat(ciSpec.argv()).contains("--setting-sources=");
        assertThat(ciSpec.argv()).containsSequence("--allowedTools", "mcp__bytequay__read_ci_memory");
        assertThat(ciSpec.environment()).doesNotContainKeys("GH_TOKEN", "GITHUB_TOKEN");
        Path settingsFile = worktree.resolve("provider/ci-autofix-settings.json");
        assertThat(settingsFile).exists();
        ciSession.close();
        assertThat(settingsFile).doesNotExist();
        driver.close();
    }

    private AgentSessionContext context(Optional<AgentSessionRef> reference)
    {
        return context(reference, AgentPermissionPolicy.MANUAL);
    }

    private AgentSessionContext context(
            Optional<AgentSessionRef> reference,
            AgentPermissionPolicy permissionPolicy)
    {
        return new AgentSessionContext(
                "task-1",
                worktree,
                worktree.resolve("provider"),
                permissionPolicy,
                reference,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()));
    }

    private static AgentTurnCommand command(String message)
    {
        return new AgentTurnCommand("turn-" + message, message, ImmutableList.of(), Duration.ofMinutes(1));
    }

    private static AgentTurnResult await(RunningTurn turn)
            throws Exception
    {
        return turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static int indexOf(
            List<ProviderAgentEvent> events,
            Predicate<ProviderAgentEvent> predicate)
    {
        for (int index = 0; index < events.size(); index++) {
            if (predicate.test(events.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private static final class ScriptedProcessSupervisor
            implements ProcessSupervisor
    {
        private final List<ScriptedTurn> turns = new CopyOnWriteArrayList<>();

        @Override
        public ManagedProcess start(ProcessSpec spec, ProcessOutputSink output)
        {
            if (spec.argv().contains("--version")) {
                output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode("2.1.233 (Claude Code)\n"));
                return ImmediateProcess.success();
            }
            if (spec.argv().contains("--help")) {
                output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode("""
                        --output-format --include-partial-messages --include-hook-events
                        --forward-subagent-text --permission-mode --mcp-config
                        --settings --setting-sources --strict-mcp-config
                        """));
                return ImmediateProcess.success();
            }
            ScriptedTurn turn = new ScriptedTurn(spec, output);
            turns.add(turn);
            return turn;
        }

        private List<ScriptedTurn> turns()
        {
            return turns;
        }
    }

    private static final class ScriptedTurn
            implements ManagedProcess
    {
        private final ProcessSpec spec;
        private final ProcessOutputSink output;
        private final CompletableFuture<ProcessExit> exit = new CompletableFuture<>();
        private String prompt = "";

        private ScriptedTurn(ProcessSpec spec, ProcessOutputSink output)
        {
            this.spec = spec;
            this.output = output;
        }

        private ProcessSpec spec()
        {
            return spec;
        }

        private String prompt()
        {
            return prompt;
        }

        @Override
        public long pid()
        {
            return 4242;
        }

        @Override
        public CompletionStage<Void> write(ByteBuffer bytes)
        {
            prompt += StandardCharsets.UTF_8.decode(bytes).toString();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void closeInput()
        {
            if (prompt.equals("cancel\n")) {
                return;
            }
            if (prompt.equals("fail\n")) {
                emit(ProcessStream.STDERR, "known-secret diagnostic\n");
                exit.complete(new ProcessExit(1, ProcessExit.Reason.EXITED, Duration.ofMillis(5), false, false));
                return;
            }
            String sessionId = sessionId();
            emit(ProcessStream.STDOUT, "not-json\n");
            emit(ProcessStream.STDOUT,
                    "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"%s\","
                            .formatted(sessionId)
                            + "\"uuid\":\"init-1\"}\n");
            emit(ProcessStream.STDOUT,
                    "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                            + "\"index\":0,\"delta\":{\"type\":\"text_delta\","
                            + "\"text\":\"draft known-secret\"}},\"session_id\":\"%s\","
                                    .formatted(sessionId)
                            + "\"parent_tool_use_id\":null,\"uuid\":\"delta-1\"}\n");
            emit(ProcessStream.STDOUT,
                    "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":["
                            + "{\"type\":\"text\",\"text\":\"authoritative known-secret\"}]},"
                            + "\"parent_tool_use_id\":null,\"session_id\":\"%s\","
                                    .formatted(sessionId)
                            + "\"uuid\":\"answer-1\"}\n");
            emit(ProcessStream.STDOUT,
                    "{\"type\":\"result\",\"is_error\":false,\"duration_ms\":10,"
                            + "\"duration_api_ms\":8,\"num_turns\":1,\"session_id\":\"%s\","
                                    .formatted(sessionId)
                            + "\"total_cost_usd\":0.01,\"usage\":{\"input_tokens\":2,"
                            + "\"cache_creation_input_tokens\":3,\"cache_read_input_tokens\":4,"
                            + "\"output_tokens\":5},\"subtype\":\"success\","
                            + "\"result\":\"authoritative known-secret\","
                            + "\"terminal_reason\":\"completed\",\"uuid\":\"result-1\"}\n");
            emit(ProcessStream.STDOUT,
                    "{\"type\":\"system\",\"subtype\":\"hook_response\","
                            + "\"hook_id\":\"hook-1\",\"hook_name\":\"Stop\","
                            + "\"hook_event\":\"Stop\",\"output\":\"trailing event\","
                            + "\"exit_code\":0,\"outcome\":\"success\",\"session_id\":\"%s\","
                                    .formatted(sessionId)
                            + "\"uuid\":\"trailing-1\"}\n");
            emit(ProcessStream.STDERR, "known-secret warning\n");
            exit.complete(new ProcessExit(0, ProcessExit.Reason.EXITED, Duration.ofMillis(10), false, false));
        }

        private String sessionId()
        {
            int option = spec.argv().indexOf("--session-id");
            if (option < 0) {
                option = spec.argv().indexOf("--resume");
            }
            return spec.argv().get(option + 1);
        }

        private void emit(ProcessStream stream, String text)
        {
            output.onOutput(stream, StandardCharsets.UTF_8.encode(text));
        }

        @Override
        public CompletionStage<ProcessExit> exit()
        {
            return exit;
        }

        @Override
        public void cancel()
        {
            exit.complete(new ProcessExit(143, ProcessExit.Reason.CANCELLED, Duration.ofMillis(5), false, false));
        }

        @Override
        public void close() {}
    }

    private static final class ImmediateProcess
            implements ManagedProcess
    {
        private final CompletableFuture<ProcessExit> exit = CompletableFuture.completedFuture(
                new ProcessExit(0, ProcessExit.Reason.EXITED, Duration.ZERO, false, false));

        private static ImmediateProcess success()
        {
            return new ImmediateProcess();
        }

        @Override
        public long pid()
        {
            return 1;
        }

        @Override
        public CompletionStage<Void> write(ByteBuffer bytes)
        {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void closeInput() {}

        @Override
        public CompletionStage<ProcessExit> exit()
        {
            return exit;
        }

        @Override
        public void cancel() {}

        @Override
        public void close() {}
    }
}
