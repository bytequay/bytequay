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
import com.bytequay.agent.cli.process.OwnedProcessRecovery;
import com.bytequay.agent.cli.process.PreparedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessOutputSink;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.spi.AgentAttachment;
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
import com.bytequay.agent.spi.RunningTurn;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        ClaudeCliDriver driver = driver(supervisor);
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();

        assertThat(driver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.READY);
        assertThat(driver.capabilities().permissionPolicies())
                .containsExactlyInAnyOrder(AgentPermissionPolicy.values());
        assertThat(driver.capabilities().attachmentMediaTypes())
                .contains("text/plain; charset=utf-8");
        AgentSession session = driver.open(context(Optional.empty()), events::add);
        AgentSessionRef reference = session.reference();

        AgentTurnResult first = await(session.start(command("first")));
        assertThat(first.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(first.finalResponse()).contains("authoritative [REDACTED]");
        ProcessSpec firstSpec = supervisor.turns().getFirst().spec();
        assertThat(firstSpec.argv()).containsSequence("--session-id", reference.providerValue());
        assertThat(firstSpec.argv()).contains("--permission-prompt-tool", "mcp__bytequay__permission_prompt");
        assertThat(firstSpec.argv()).containsSequence("--permission-mode", "auto");
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

        Path configFile = runtimeDirectory().resolve("provider/scratch/bytequay-mcp.json");
        assertThat(configFile).exists();
        session.close();
        assertThat(configFile).doesNotExist();

        AgentSession ciSession = driver.open(context(Optional.empty(), AgentPermissionPolicy.CI_AUTOFIX), events::add);
        Path log = runtimeDirectory().resolve("blobs/ci.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "failure");
        Path realLog = log.toRealPath();
        AgentAttachment attachment = new AgentAttachment(
                "github-actions.log",
                "text/plain; charset=utf-8",
                log);
        assertThat(await(ciSession.start(command("ci-fix", ImmutableList.of(attachment)))).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);
        ProcessSpec ciSpec = supervisor.turns().getLast().spec();
        assertThat(ciSpec.argv()).containsSequence("--permission-mode", "auto");
        assertThat(ciSpec.argv()).contains("--strict-mcp-config");
        assertThat(ciSpec.argv()).contains("--setting-sources=");
        assertThat(ciSpec.argv()).containsSequence("--add-dir", realLog.getParent().toString());
        int allowedTools = ciSpec.argv().indexOf("--allowedTools");
        assertThat(allowedTools).isGreaterThanOrEqualTo(0);
        assertThat(ciSpec.argv().get(allowedTools + 1))
                .contains("mcp__bytequay__read_ci_memory", "Read(/" + realLog + ")");
        assertThat(supervisor.turns().getLast().prompt()).contains(realLog.toString());
        assertThat(ciSpec.environment()).doesNotContainKeys("GH_TOKEN", "GITHUB_TOKEN");
        Path settingsFile = runtimeDirectory().resolve("provider/scratch/sandbox-settings.json");
        assertThat(settingsFile).exists();
        ciSession.close();
        assertThat(settingsFile).doesNotExist();

        AgentSession cherryPickSession = driver.open(context(Optional.empty(), AgentPermissionPolicy.CHERRY_PICK), events::add);
        assertThat(await(cherryPickSession.start(command("resolve"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        ProcessSpec cherryPickSpec = supervisor.turns().getLast().spec();
        assertThat(cherryPickSpec.argv()).contains("--strict-mcp-config", "--setting-sources=", "--settings");
        assertThat(cherryPickSpec.argv()).doesNotContain("--allowedTools");
        assertThat(String.join(" ", cherryPickSpec.argv())).doesNotContain("read_ci_memory");
        assertThat(settingsFile).exists();
        cherryPickSession.close();
        assertThat(settingsFile).doesNotExist();

        AgentSession autoSession = driver.open(context(Optional.empty(), AgentPermissionPolicy.AUTO), events::add);
        assertThat(await(autoSession.start(command("auto"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        ProcessSpec autoSpec = supervisor.turns().getLast().spec();
        assertThat(autoSpec.argv()).containsSequence("--permission-mode", "auto");
        assertThat(autoSpec.argv()).contains("--strict-mcp-config", "--setting-sources=", "--settings");
        assertThat(autoSpec.argv()).contains("--permission-prompt-tool", "mcp__bytequay__permission_prompt");
        assertThat(settingsFile).exists();
        autoSession.close();
        assertThat(settingsFile).doesNotExist();

        AgentSession textSession = driver.open(context(
                Optional.empty(), AgentPermissionPolicy.AUTO, Optional.of("sonnet"), Optional.of("Extra"), true),
                events::add);
        assertThat(await(textSession.start(command("rewrite"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        ProcessSpec textSpec = supervisor.turns().getLast().spec();
        assertThat(textSpec.argv()).contains("--tools=");
        assertThat(textSpec.argv()).contains("--disable-slash-commands", "--no-session-persistence");
        assertThat(textSpec.argv()).containsSequence("--model", "sonnet");
        assertThat(textSpec.argv()).containsSequence("--effort", "xhigh");
        assertThat(settingsFile).exists();
        assertThat(objectMapper.readTree(Files.readString(settingsFile))
                .at("/sandbox/allowUnsandboxedCommands").asBoolean()).isFalse();
        assertThat(objectMapper.readTree(Files.readString(settingsFile))
                .at("/sandbox/network/allowedDomains")).isEmpty();
        textSession.close();
        assertThat(settingsFile).doesNotExist();
        driver.close();
    }

    @Test
    void distinguishesUnsupportedFromUnparseableVersions()
    {
        ScriptedProcessSupervisor unsupportedSupervisor = new ScriptedProcessSupervisor();
        unsupportedSupervisor.versionOutput("1.0.0");
        ClaudeCliDriver unsupportedDriver = driver(unsupportedSupervisor);
        assertThat(unsupportedDriver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.UNSUPPORTED);
        unsupportedDriver.close();

        ScriptedProcessSupervisor unparseableSupervisor = new ScriptedProcessSupervisor();
        unparseableSupervisor.versionOutput("future-build");
        ClaudeCliDriver unparseableDriver = driver(unparseableSupervisor);
        assertThat(unparseableDriver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        unparseableDriver.close();
    }

    @Test
    void rejectsTurnWhenPromptWriteFails()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        supervisor.failNextPromptWrite();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), ignored -> {});

        assertThatThrownBy(() -> session.start(command("write-failure")))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("Unable to write Claude prompt")
                .hasRootCauseMessage("simulated prompt write failure");

        assertThat(supervisor.turns()).singleElement().satisfies(turn -> {
            assertThat(turn.cancelled()).isTrue();
            assertThat(turn.inputClosed()).isFalse();
        });
        session.close();
        driver.close();
    }

    @Test
    void rejectsTurnWhenPromptInputCannotClose()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        supervisor.failNextPromptClose();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), ignored -> {});

        assertThatThrownBy(() -> session.start(command("close-failure")))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("Unable to close Claude prompt input")
                .hasRootCauseMessage("simulated prompt close failure");

        assertThat(supervisor.turns()).singleElement().satisfies(turn -> {
            assertThat(turn.cancelled()).isTrue();
            assertThat(turn.inputClosed()).isTrue();
        });
        session.close();
        driver.close();
    }

    @Test
    void interruptsTurnWhenFinalUsageCannotPersist()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        AtomicBoolean failUsage = new AtomicBoolean(true);
        AgentSession session = driver.open(context(Optional.empty()), event -> {
            if (event instanceof ProviderAgentEvent.Usage && failUsage.compareAndSet(true, false)) {
                throw new IllegalStateException("simulated usage persistence failure");
            }
            events.add(event);
        });

        AgentTurnResult result = await(session.start(command("usage-failure")));

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
        assertThat(result.terminalReason())
                .contains("Unable to persist Claude terminal events: simulated usage persistence failure");
        assertThat(events).noneMatch(ProviderAgentEvent.Usage.class::isInstance);
        assertThat(events).anyMatch(ProviderAgentEvent.Error.class::isInstance);
        session.close();
        driver.close();
    }

    @Test
    void refusesToCompleteATurnWhoseProcessTreeIsUnverifiable()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), event -> {});

        AgentTurnResult result = await(session.start(command("unverifiable")));

        // A clean provider result is not quiescence: the group may still be writing to the worktree.
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
        assertThat(result.terminalReason()).contains("Claude process tree could not be verified stopped");
        assertThat(result.finalResponse()).isEmpty();
    }

    private ClaudeCliDriver driver(ProcessSupervisor supervisor)
    {
        ClaudePermissionBroker permissions = new ClaudePermissionBroker(
                () -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"),
                objectMapper,
                (taskId, query) -> "memory");
        return new ClaudeCliDriver(
                "/fake/claude",
                objectMapper,
                permissions,
                supervisor,
                ImmutableMap.of(
                        "HOME", worktree.toString(),
                        "ANTHROPIC_API_KEY", "known-secret"));
    }

    private AgentSessionContext context(Optional<AgentSessionRef> reference)
    {
        return context(reference, AgentPermissionPolicy.AUTO);
    }

    private AgentSessionContext context(
            Optional<AgentSessionRef> reference,
            AgentPermissionPolicy permissionPolicy)
    {
        return context(reference, permissionPolicy, false);
    }

    private AgentSessionContext context(
            Optional<AgentSessionRef> reference,
            AgentPermissionPolicy permissionPolicy,
            boolean textOnly)
    {
        return context(reference, permissionPolicy, Optional.empty(), Optional.empty(), textOnly);
    }

    private AgentSessionContext context(
            Optional<AgentSessionRef> reference,
            AgentPermissionPolicy permissionPolicy,
            Optional<String> model,
            Optional<String> effort,
            boolean textOnly)
    {
        return new AgentSessionContext(
                "task-1",
                worktree,
                runtimeDirectory(),
                runtimeDirectory().resolve("provider/scratch"),
                permissionPolicy,
                reference,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                model,
                effort,
                textOnly);
    }

    private Path runtimeDirectory()
    {
        return worktree.resolve("task-runtime");
    }

    private static AgentTurnCommand command(String message)
    {
        return command(message, ImmutableList.of());
    }

    private static AgentTurnCommand command(String message, List<AgentAttachment> attachments)
    {
        return new AgentTurnCommand("turn-" + message, message, attachments, Duration.ofMinutes(1));
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
        private String versionOutput = "2.1.233 (Claude Code)";
        private boolean failNextPromptWrite;
        private boolean failNextPromptClose;

        @Override
        public PreparedProcess prepare(ProcessSpec spec)
        {
            return new ScriptedLaunch(spec);
        }

        @Override
        public List<OwnedProcessRecovery> recoverOwnedProcesses()
        {
            return ImmutableList.of();
        }

        @Override
        public void acknowledge(String launchId) {}

        private final class ScriptedLaunch
                implements PreparedProcess
        {
            private final ProcessSpec spec;

            private ScriptedLaunch(ProcessSpec spec)
            {
                this.spec = spec;
            }

            @Override
            public String launchId()
            {
                return "scripted";
            }

            @Override
            public void abandon() {}

            @Override
            public ManagedProcess start(ProcessOutputSink output)
            {
                return ScriptedProcessSupervisor.this.start(spec, output);
            }
        }

        private ManagedProcess start(ProcessSpec spec, ProcessOutputSink output)
        {
            if (spec.argv().contains("--version")) {
                output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode(versionOutput + "\n"));
                return ImmediateProcess.success();
            }
            if (spec.argv().contains("--help")) {
                output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode("""
                        --output-format --include-partial-messages --include-hook-events
                        --forward-subagent-text --permission-mode --mcp-config
                        --settings --setting-sources --strict-mcp-config --add-dir
                        --tools --no-session-persistence --disable-slash-commands --model --effort
                        """));
                return ImmediateProcess.success();
            }
            ScriptedTurn turn = new ScriptedTurn(spec, output, failNextPromptWrite, failNextPromptClose);
            failNextPromptWrite = false;
            failNextPromptClose = false;
            turns.add(turn);
            return turn;
        }

        private List<ScriptedTurn> turns()
        {
            return turns;
        }

        private void versionOutput(String output)
        {
            versionOutput = output;
        }

        private void failNextPromptWrite()
        {
            failNextPromptWrite = true;
        }

        private void failNextPromptClose()
        {
            failNextPromptClose = true;
        }
    }

    private static final class ScriptedTurn
            implements ManagedProcess
    {
        private final ProcessSpec spec;
        private final ProcessOutputSink output;
        private final CompletableFuture<ProcessExit> exit = new CompletableFuture<>();
        private final boolean failPromptWrite;
        private final boolean failPromptClose;
        private String prompt = "";
        private boolean cancelled;
        private boolean inputClosed;

        private ScriptedTurn(
                ProcessSpec spec,
                ProcessOutputSink output,
                boolean failPromptWrite,
                boolean failPromptClose)
        {
            this.spec = spec;
            this.output = output;
            this.failPromptWrite = failPromptWrite;
            this.failPromptClose = failPromptClose;
        }

        private ProcessSpec spec()
        {
            return spec;
        }

        private String prompt()
        {
            return prompt;
        }

        private boolean cancelled()
        {
            return cancelled;
        }

        private boolean inputClosed()
        {
            return inputClosed;
        }

        @Override
        public String launchId()
        {
            return "scripted";
        }

        @Override
        public long pid()
        {
            return 4242;
        }

        @Override
        public CompletionStage<Void> write(ByteBuffer bytes)
        {
            if (failPromptWrite) {
                return CompletableFuture.failedFuture(new IllegalStateException("simulated prompt write failure"));
            }
            prompt += StandardCharsets.UTF_8.decode(bytes).toString();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void closeInput()
        {
            inputClosed = true;
            if (failPromptClose) {
                throw new IllegalStateException("simulated prompt close failure");
            }
            if (prompt.equals("cancel\n")) {
                return;
            }
            if (prompt.equals("fail\n")) {
                emit(ProcessStream.STDERR, "known-secret diagnostic\n");
                exit.complete(new ProcessExit(1, ProcessExit.Reason.EXITED, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ofMillis(5), false, false));
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
            exit.complete(new ProcessExit(
                    0,
                    ProcessExit.Reason.EXITED,
                    prompt.equals("unverifiable\n")
                            ? ProcessExit.TreeTermination.UNVERIFIABLE
                            : ProcessExit.TreeTermination.VERIFIED_STOPPED,
                    Duration.ofMillis(10),
                    false,
                    false));
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
            cancelled = true;
            exit.complete(new ProcessExit(143, ProcessExit.Reason.CANCELLED, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ofMillis(5), false, false));
        }

        @Override
        public void close() {}
    }

    private static final class ImmediateProcess
            implements ManagedProcess
    {
        private final CompletableFuture<ProcessExit> exit = CompletableFuture.completedFuture(
                new ProcessExit(0, ProcessExit.Reason.EXITED, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ZERO, false, false));

        private static ImmediateProcess success()
        {
            return new ImmediateProcess();
        }

        @Override
        public String launchId()
        {
            return "scripted";
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
