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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.STAGE_TURN;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.TASK_TURN;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Access.READ_ONLY;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Access.WORKTREE_WRITE;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Completion.CANCELED;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Completion.FAILED;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Completion.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.ToolProfile.REVIEW_ASSIGNMENT_READ_ONLY;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.ToolProfile.STAGE_DEVELOPMENT;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.ToolProfile.TASK_BRAIN_READ_ONLY;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Transport.CLI;
import static com.bytequay.app.developmentflow.execution.agentturn.CliAgentTurnProviderSession.CliProvider.CLAUDE_CODE;
import static com.bytequay.app.developmentflow.execution.agentturn.CliAgentTurnProviderSession.CliProvider.CODEX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestCliAgentTurnProviderSession
{
    private static final Path WORKTREE = Path.of("/tmp/agent-turn-worktree");

    @Test
    void openIsInertAndCancellationBeforeStartNeverSpawns()
            throws Exception
    {
        AgentTurnProviderSession.Observer observer =
                mock(AgentTurnProviderSession.Observer.class);
        CliAgentTurnProviderSession provider = new CliAgentTurnProviderSession(
                new ObjectMapper(), ignored -> "/definitely/not/an/executable");

        try (AgentTurnProviderSession.Session session = provider.open(
                request(READ_ONLY), observer)) {
            verifyNoInteractions(observer);

            session.cancel();
            AgentTurnProviderSession.Result result = session.startAndAwait(null);

            assertThat(result.completion()).isEqualTo(CANCELED);
            assertThat(result.processPid()).isNull();
            verifyNoInteractions(observer);
        }
    }

    @Test
    void worktreeWriteCannotStartWithoutAuthorizedFence()
            throws Exception
    {
        CliAgentTurnProviderSession provider = new CliAgentTurnProviderSession(
                new ObjectMapper(), ignored -> "/definitely/not/an/executable");
        try (AgentTurnProviderSession.Session session = provider.open(
                request(WORKTREE_WRITE), mock(AgentTurnProviderSession.Observer.class))) {
            assertThatThrownBy(() -> session.startAndAwait(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("writer fence");
        }
    }

    @Test
    void cancellationTearsDownDescendantsAndParent()
    {
        Process process = mock(Process.class);
        ProcessHandle parent = mock(ProcessHandle.class);
        ProcessHandle child = mock(ProcessHandle.class);
        ProcessHandle grandchild = mock(ProcessHandle.class);
        when(process.descendants()).thenReturn(Stream.of(child, grandchild));
        when(process.toHandle()).thenReturn(parent);

        CliAgentTurnProviderSession.stopProcessTree(process);

        InOrder teardown = inOrder(grandchild, child, parent);
        teardown.verify(grandchild).destroyForcibly();
        teardown.verify(child).destroyForcibly();
        teardown.verify(parent).destroyForcibly();
    }

    @Test
    void commandsExposeOnlyTheFrozenBytequayMcpEndpoint()
            throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        AgentTurnProviderSession.Request request = request(READ_ONLY);
        List<String> codex = CliAgentTurnProviderSession.buildArgv(
                request, CODEX, "codex", null);

        assertThat(codex).contains(
                "--ignore-user-config",
                "mcp_servers={bytequay={url=\"" + endpoint().url()
                        + "\",default_tools_approval_mode=\"approve\"}}");
        assertThat(codex).noneMatch(value ->
                value.startsWith("mcp_servers.bytequay."));
        assertThat(codex).doesNotContain("--dangerously-bypass-approvals-and-sandbox");

        Path config = CliAgentTurnProviderSession.createMcpConfig(endpoint(), mapper);
        try {
            List<String> claude = CliAgentTurnProviderSession.buildArgv(
                    request, CLAUDE_CODE, "claude", config);
            assertThat(claude).contains(
                    "--setting-sources", "",
                    "--mcp-config", config.toString(),
                    "--strict-mcp-config",
                    "--permission-prompt-tool", "mcp__bytequay__approval_prompt");
            assertThat(mapper.readTree(Files.readString(config)))
                    .isEqualTo(mapper.readTree("""
                            {"mcpServers":{"bytequay":{"type":"http","url":"%s"}}}
                            """.formatted(endpoint().url())));
        }
        finally {
            CliAgentTurnProviderSession.deleteMcpConfig(config);
        }
        assertThat(config).doesNotExist();
    }

    @Test
    void resumeCommandsRebindTheCurrentMcpEndpointWithoutFreshCodexFlags()
            throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        AgentTurnProviderSession.Request request = resumedRequest(
                WORKTREE, "stale-session", "complete durable history");

        List<String> codex = CliAgentTurnProviderSession.buildArgv(
                request, CODEX, "codex", null);
        assertThat(codex).containsSubsequence(
                "exec", "--ignore-user-config", "resume", "--json",
                "--skip-git-repo-check", "-m", "gpt-5.6", "stale-session");
        assertThat(codex).contains(
                "mcp_servers={bytequay={url=\"" + endpoint().url()
                        + "\",default_tools_approval_mode=\"approve\"}}");
        assertThat(codex).doesNotContain("--sandbox", "-C");
        assertThat(codex.getLast()).isEqualTo("-");
        assertThat(codex).doesNotContain("incremental prompt");

        Path config = CliAgentTurnProviderSession.createMcpConfig(endpoint(), mapper);
        try {
            assertThat(CliAgentTurnProviderSession.buildArgv(
                    request, CLAUDE_CODE, "claude", config))
                    .containsSubsequence("--mcp-config", config.toString())
                    .containsSubsequence("--resume", "stale-session");
        }
        finally {
            CliAgentTurnProviderSession.deleteMcpConfig(config);
        }
    }

    @Test
    void unavailableResumeDurablyAnnouncesBothProcessesBeforeFallback(
            @TempDir Path tempDir)
            throws Exception
    {
        Path invocations = tempDir.resolve("invocations.txt");
        Path executable = executable(tempDir, """
                #!/bin/sh
                prompt=$(cat)
                printf 'args=%%s\\nprompt=%%s\\n' "$*" "$prompt" >> '%s'
                case " $* " in
                  *" resume "*)
                    printf '%%s\\n' '{"type":"turn.failed","error":{"message":"no rollout found for thread id stale-session"}}'
                    exit 1
                    ;;
                esac
                printf '%%s\\n' '{"type":"thread.started","thread_id":"new-session"}'
                printf '%%s\\n' '{"type":"item.completed","item":{"type":"agent_message","id":"m1","text":"done"}}'
                printf '%%s\\n' '{"type":"turn.completed","usage":{"input_tokens":2,"output_tokens":1}}'
                """.formatted(invocations));
        AgentTurnProviderSession.Observer observer =
                mock(AgentTurnProviderSession.Observer.class);
        CliAgentTurnProviderSession provider = new CliAgentTurnProviderSession(
                new ObjectMapper(), ignored -> executable.toString());

        AgentTurnProviderSession.Result result;
        try (AgentTurnProviderSession.Session session = provider.open(
                resumedRequest(tempDir, "stale-session",
                        "complete durable history", 100, 40),
                observer)) {
            result = session.startAndAwait(null);
        }

        assertThat(result.completion()).isEqualTo(SUCCEEDED);
        assertThat(result.providerSessionId()).isEqualTo("new-session");
        assertThat(result.finalText()).isEqualTo("done");
        assertThat(result.inputTokens()).isEqualTo(2);
        assertThat(result.outputTokens()).isEqualTo(1);
        assertThat(result.cumulativeInputTokens()).isEqualTo(2);
        assertThat(result.cumulativeOutputTokens()).isEqualTo(1);
        assertThat(Files.readString(invocations))
                .contains("resume", "stale-session", "complete durable history");
        ArgumentCaptor<Long> processPids = ArgumentCaptor.forClass(Long.class);
        verify(observer, times(2)).processStarted(
                processPids.capture(), anyString());
        assertThat(processPids.getAllValues())
                .hasSize(2)
                .endsWith(result.processPid());
        verify(observer).providerSession("codex", "new-session");
        ArgumentCaptor<Long> sequences = ArgumentCaptor.forClass(Long.class);
        verify(observer, times(4)).log(sequences.capture(), anyString());
        assertThat(sequences.getAllValues()).containsExactly(0L, 1L, 2L, 3L);
    }

    @Test
    void resumedCodexUsageIsTheDeltaFromTheFrozenCumulativeBaseline(
            @TempDir Path tempDir)
            throws Exception
    {
        Path executable = executable(tempDir, """
                #!/bin/sh
                cat >/dev/null
                printf '%s\\n' '{"type":"thread.started","thread_id":"session-1"}'
                printf '%s\\n' '{"type":"turn.completed","usage":{"input_tokens":125,"output_tokens":47}}'
                """);
        CliAgentTurnProviderSession provider = new CliAgentTurnProviderSession(
                new ObjectMapper(), ignored -> executable.toString());

        AgentTurnProviderSession.Result result;
        try (AgentTurnProviderSession.Session session = provider.open(
                resumedRequest(tempDir, "session-1", "history", 100, 40),
                mock(AgentTurnProviderSession.Observer.class))) {
            result = session.startAndAwait(null);
        }

        assertThat(result.completion()).isEqualTo(SUCCEEDED);
        assertThat(result.inputTokens()).isEqualTo(25);
        assertThat(result.outputTokens()).isEqualTo(7);
        assertThat(result.cumulativeInputTokens()).isEqualTo(125);
        assertThat(result.cumulativeOutputTokens()).isEqualTo(47);
    }

    @Test
    void resumedCodexRejectsUsageBelowItsFrozenBaseline(
            @TempDir Path tempDir)
            throws Exception
    {
        Path executable = executable(tempDir, """
                #!/bin/sh
                cat >/dev/null
                printf '%s\\n' '{"type":"thread.started","thread_id":"session-1"}'
                printf '%s\\n' '{"type":"turn.completed","usage":{"input_tokens":99,"output_tokens":39}}'
                """);
        CliAgentTurnProviderSession provider = new CliAgentTurnProviderSession(
                new ObjectMapper(), ignored -> executable.toString());

        AgentTurnProviderSession.Result result;
        try (AgentTurnProviderSession.Session session = provider.open(
                resumedRequest(tempDir, "session-1", "history", 100, 40),
                mock(AgentTurnProviderSession.Observer.class))) {
            result = session.startAndAwait(null);
        }

        assertThat(result.completion()).isEqualTo(FAILED);
        assertThat(result.error()).contains("below the frozen session baseline");
        assertThat(result.inputTokens()).isZero();
        assertThat(result.outputTokens()).isZero();
    }

    @Test
    void acceptedResumeFailureIsNeverReplayed(@TempDir Path tempDir)
            throws Exception
    {
        Path invocations = tempDir.resolve("invocations.txt");
        Path executable = executable(tempDir, """
                #!/bin/sh
                printf 'invoked\\n' >> '%s'
                printf '%%s\\n' '{"type":"thread.started","thread_id":"accepted-session"}'
                printf '%%s\\n' '{"type":"turn.failed","error":{"message":"no rollout found for thread id stale-session"}}'
                exit 1
                """.formatted(invocations));
        AgentTurnProviderSession.Observer observer =
                mock(AgentTurnProviderSession.Observer.class);
        CliAgentTurnProviderSession provider = new CliAgentTurnProviderSession(
                new ObjectMapper(), ignored -> executable.toString());

        AgentTurnProviderSession.Result result;
        try (AgentTurnProviderSession.Session session = provider.open(
                resumedRequest(tempDir, "stale-session", "complete durable history"),
                observer)) {
            result = session.startAndAwait(null);
        }

        assertThat(result.completion()).isEqualTo(FAILED);
        assertThat(result.error()).contains("no rollout found");
        assertThat(result.providerSessionId()).isEqualTo("accepted-session");
        assertThat(Files.readAllLines(invocations)).hasSize(1);
        verify(observer, times(1)).processStarted(
                result.processPid(), "agent-turn/codex");
    }

    @Test
    void partialProviderWorkBeforeAResumeErrorIsNeverReplayed(
            @TempDir Path tempDir)
            throws Exception
    {
        Path invocations = tempDir.resolve("invocations.txt");
        Path executable = executable(tempDir, """
                #!/bin/sh
                cat >/dev/null
                printf 'invoked\\n' >> '%s'
                printf '%%s\\n' '{"type":"item.completed","item":{"type":"agent_message","id":"m1","text":"partial work"}}'
                printf '%%s\\n' '{"type":"turn.failed","error":{"message":"no rollout found for thread id stale-session"}}'
                exit 1
                """.formatted(invocations));
        AgentTurnProviderSession.Observer observer =
                mock(AgentTurnProviderSession.Observer.class);
        CliAgentTurnProviderSession provider = new CliAgentTurnProviderSession(
                new ObjectMapper(), ignored -> executable.toString());

        AgentTurnProviderSession.Result result;
        try (AgentTurnProviderSession.Session session = provider.open(
                resumedRequest(tempDir, "stale-session", "complete durable history"),
                observer)) {
            result = session.startAndAwait(null);
        }

        assertThat(result.completion()).isEqualTo(FAILED);
        assertThat(result.finalText()).isEqualTo("partial work");
        assertThat(result.error()).contains("no rollout found");
        assertThat(Files.readAllLines(invocations)).hasSize(1);
        verify(observer, times(1)).processStarted(
                result.processPid(), "agent-turn/codex");
    }

    @Test
    void unknownJsonBeforeAResumeErrorIsNeverReplayed(
            @TempDir Path tempDir)
            throws Exception
    {
        Path invocations = tempDir.resolve("invocations.txt");
        Path executable = executable(tempDir, """
                #!/bin/sh
                cat >/dev/null
                printf 'invoked\\n' >> '%s'
                printf '%%s\\n' '{"type":"future.tool.completed","effect":"work happened"}'
                printf '%%s\\n' '{"type":"turn.failed","error":{"message":"no rollout found for thread id stale-session"}}'
                exit 1
                """.formatted(invocations));
        AgentTurnProviderSession.Observer observer =
                mock(AgentTurnProviderSession.Observer.class);
        CliAgentTurnProviderSession provider = new CliAgentTurnProviderSession(
                new ObjectMapper(), ignored -> executable.toString());

        AgentTurnProviderSession.Result result;
        try (AgentTurnProviderSession.Session session = provider.open(
                resumedRequest(tempDir, "stale-session", "complete durable history"),
                observer)) {
            result = session.startAndAwait(null);
        }

        assertThat(result.completion()).isEqualTo(FAILED);
        assertThat(result.error()).contains("no rollout found");
        assertThat(Files.readAllLines(invocations)).hasSize(1);
        verify(observer, times(1)).processStarted(
                result.processPid(), "agent-turn/codex");
    }

    @Test
    void claudeUnavailableResumeRetriesFreshWithDurableFallback(
            @TempDir Path tempDir)
            throws Exception
    {
        Path invocations = tempDir.resolve("invocations.txt");
        Path executable = executable(tempDir, """
                #!/bin/sh
                prompt=$(cat)
                printf 'args=%%s\\nprompt=%%s\\n' "$*" "$prompt" >> '%s'
                case " $* " in
                  *" --resume stale-session "*)
                    printf '%%s\\n' '{"type":"result","subtype":"error","duration_ms":1,"is_error":true,"error":"No conversation found with session ID: stale-session","total_cost_usd":0,"usage":{"input_tokens":0,"output_tokens":0}}'
                    exit 1
                    ;;
                esac
                printf '%%s\\n' '{"type":"system","subtype":"init","session_id":"new-claude-session","cwd":"/tmp","model":"claude-opus-4-8"}'
                printf '%%s\\n' '{"type":"assistant","message":{"content":[{"type":"text","text":"done"}]}}'
                printf '%%s\\n' '{"type":"result","subtype":"success","duration_ms":2,"is_error":false,"total_cost_usd":0.001,"usage":{"input_tokens":2,"output_tokens":1}}'
                """.formatted(invocations));
        AgentTurnProviderSession.Observer observer =
                mock(AgentTurnProviderSession.Observer.class);
        CliAgentTurnProviderSession provider = new CliAgentTurnProviderSession(
                new ObjectMapper(), ignored -> executable.toString());
        AgentTurnProviderSession.Request request = resumedRequest(
                tempDir, "claude-code", "claude-opus-4-8", "stale-session",
                "complete durable history");

        AgentTurnProviderSession.Result result;
        try (AgentTurnProviderSession.Session session = provider.open(
                request, observer)) {
            result = session.startAndAwait(null);
        }

        assertThat(result.completion()).isEqualTo(SUCCEEDED);
        assertThat(result.providerSessionId()).isEqualTo("new-claude-session");
        assertThat(result.finalText()).isEqualTo("done");
        assertThat(Files.readString(invocations))
                .contains("--resume stale-session", "complete durable history");
        ArgumentCaptor<Long> processPids = ArgumentCaptor.forClass(Long.class);
        verify(observer, times(2)).processStarted(
                processPids.capture(), anyString());
        assertThat(processPids.getAllValues())
                .hasSize(2)
                .endsWith(result.processPid());
    }

    @Test
    void failedDurableProcessRegistrationStopsTheProcessBeforePromptDelivery(
            @TempDir Path tempDir)
            throws Exception
    {
        Path executable = executable(tempDir, """
                #!/bin/sh
                cat >/dev/null
                """);
        AtomicLong processPid = new AtomicLong();
        AgentTurnProviderSession.Observer observer =
                new AgentTurnProviderSession.Observer()
                {
                    @Override
                    public void providerSession(String provider, String sessionId) {}

                    @Override
                    public void processStarted(long pid, String logReference)
                    {
                        processPid.set(pid);
                        throw new IllegalStateException("durable registration failed");
                    }

                    @Override
                    public void log(long sequence, String payloadJson) {}
                };
        CliAgentTurnProviderSession provider = new CliAgentTurnProviderSession(
                new ObjectMapper(), ignored -> executable.toString());
        AgentTurnProviderSession.Request launch =
                new AgentTurnProviderSession.Request(
                        CLI, "codex", null, "gpt-5.6", "high", tempDir,
                        "system", "prompt", List.of(), endpoint(), READ_ONLY);

        try (AgentTurnProviderSession.Session session = provider.open(
                launch, observer)) {
            assertThatThrownBy(() -> session.startAndAwait(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("durable registration failed");
        }

        assertThat(processPid.get()).isPositive();
        ProcessHandle handle = ProcessHandle.of(processPid.get()).orElse(null);
        if (handle != null) {
            handle.onExit().get(5, TimeUnit.SECONDS);
        }
        assertThat(ProcessHandle.of(processPid.get()))
                .isEmpty();
    }

    @Test
    void onlyExplicitUnavailableSessionFailuresAreRestartable()
    {
        assertThat(CliAgentTurnProviderSession.isSessionUnavailable(
                CODEX, "no rollout found for thread id abc")).isTrue();
        assertThat(CliAgentTurnProviderSession.isSessionUnavailable(
                CLAUDE_CODE, "No conversation found with session ID abc")).isTrue();
        assertThat(CliAgentTurnProviderSession.isSessionUnavailable(
                CLAUDE_CODE, "Session has expired")).isTrue();
        assertThat(CliAgentTurnProviderSession.isSessionUnavailable(
                CODEX, "provider request timed out")).isFalse();
        assertThat(CliAgentTurnProviderSession.isSessionUnavailable(
                CLAUDE_CODE, "failed to resume session")).isFalse();
        assertThat(CliAgentTurnProviderSession.isSessionUnavailable(
                CODEX, "No conversation found with session ID abc")).isFalse();
        assertThat(CliAgentTurnProviderSession.isSessionUnavailable(
                CLAUDE_CODE, "no rollout found for thread id abc")).isFalse();
        assertThat(CliAgentTurnProviderSession.isSessionUnavailable(
                CODEX, "session not found")).isFalse();
    }

    @Test
    void deliversImagesThroughEachCliNativeReadPath()
    {
        AgentTurnProviderSession.Request request = request(
                READ_ONLY, List.of("/tmp/first.png", "/tmp/second.jpg"));

        assertThat(CliAgentTurnProviderSession.buildArgv(
                request, CODEX, "codex", null))
                .containsSubsequence("-i", "/tmp/first.png")
                .containsSubsequence("-i", "/tmp/second.jpg");

        List<String> claude = CliAgentTurnProviderSession.buildArgv(
                request, CLAUDE_CODE, "claude", Path.of("/tmp/mcp.json"));
        assertThat(claude)
                .containsSubsequence("--add-dir", "/tmp")
                .containsSubsequence(
                        "--allowedTools",
                        "Read(//tmp/first.png),Read(//tmp/second.jpg)");
        assertThat(CliAgentTurnProviderSession.providerPrompt(
                request, CLAUDE_CODE))
                .isEqualTo("""
                        prompt

                        Attached images (read these managed files):
                        - /tmp/first.png
                        - /tmp/second.jpg""");
        assertThat(CliAgentTurnProviderSession.providerPrompt(request, CODEX))
                .isEqualTo("prompt");
    }

    @Test
    void endpointUrlMustNameTheSameTypedTurn()
    {
        assertThatThrownBy(() -> new AgentTurnProviderSession.OwnerToolEndpoint(
                "bytequay",
                "http://127.0.0.1:53123/api/v2/task-turns/other-turn/"
                        + "operations/operation-1/mcp",
                TASK_TURN,
                "task-turn-1",
                "operation-1",
                TASK_BRAIN_READ_ONLY,
                "mcp__bytequay__approval_prompt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact typed Turn");
    }

    @Test
    void reviewProfileKeepsBuiltinsReadOnlyAndPreapprovesOnlyReviewMcpTools()
    {
        AgentTurnProviderSession.OwnerToolEndpoint endpoint =
                new AgentTurnProviderSession.OwnerToolEndpoint(
                        "bytequay",
                        "http://127.0.0.1:53123/api/v2/review-assignment-turns/"
                                + "review-turn-1/operations/operation-1/mcp",
                        REVIEW_ASSIGNMENT_TURN,
                        "review-turn-1",
                        "operation-1",
                        REVIEW_ASSIGNMENT_READ_ONLY,
                        "mcp__bytequay__approval_prompt");
        AgentTurnProviderSession.Request request = new AgentTurnProviderSession.Request(
                CLI, "claude-code", null, "claude-opus-4-8", null,
                WORKTREE, "system", "prompt", List.of(), endpoint, READ_ONLY, 250L);

        List<String> argv = CliAgentTurnProviderSession.buildArgv(
                request, CLAUDE_CODE, "claude", Path.of("/tmp/review-mcp.json"));

        assertThat(argv).containsSubsequence(
                "--tools", "Read,Glob,Grep,WebFetch,WebSearch");
        assertThat(argv).containsSubsequence("--max-budget-usd", "0.25");
        assertThat(argv).containsSubsequence(
                "--allowedTools",
                "mcp__bytequay__record_assignment,mcp__bytequay__record_hypothesis,"
                        + "mcp__bytequay__record_step,mcp__bytequay__read_diff,"
                        + "mcp__bytequay__read_file,mcp__bytequay__search_diff,"
                        + "mcp__bytequay__record_evidence,mcp__bytequay__record_finding,"
                        + "mcp__bytequay__record_verification");
        assertThat(argv).doesNotContain("Bash", "Edit", "Write", "Task");
    }

    private static AgentTurnProviderSession.Request request(
            AgentTurnProviderSession.Access access)
    {
        return request(access, List.of());
    }

    private static AgentTurnProviderSession.Request request(
            AgentTurnProviderSession.Access access, List<String> images)
    {
        List<AgentTurnProviderSession.ImageAttachment> frozen = images.stream()
                .map(image -> new AgentTurnProviderSession.ImageAttachment(
                        image, image.endsWith(".png")
                                ? "image/png" : "image/jpeg",
                        "0".repeat(64)))
                .toList();
        return new AgentTurnProviderSession.Request(
                CLI,
                "codex",
                null,
                "gpt-5.6",
                "high",
                WORKTREE,
                "system",
                "prompt",
                frozen,
                endpoint(access),
                access);
    }

    private static AgentTurnProviderSession.Request resumedRequest(
            Path workingDirectory, String sessionId, String fallbackPrompt)
    {
        return resumedRequest(
                workingDirectory, sessionId, fallbackPrompt, 0, 0);
    }

    private static AgentTurnProviderSession.Request resumedRequest(
            Path workingDirectory,
            String sessionId,
            String fallbackPrompt,
            long priorCumulativeInputTokens,
            long priorCumulativeOutputTokens)
    {
        return resumedRequest(
                workingDirectory, "codex", "gpt-5.6", sessionId,
                fallbackPrompt, priorCumulativeInputTokens,
                priorCumulativeOutputTokens);
    }

    private static AgentTurnProviderSession.Request resumedRequest(
            Path workingDirectory,
            String provider,
            String model,
            String sessionId,
            String fallbackPrompt)
    {
        return resumedRequest(
                workingDirectory, provider, model, sessionId, fallbackPrompt,
                0, 0);
    }

    private static AgentTurnProviderSession.Request resumedRequest(
            Path workingDirectory,
            String provider,
            String model,
            String sessionId,
            String fallbackPrompt,
            long priorCumulativeInputTokens,
            long priorCumulativeOutputTokens)
    {
        return new AgentTurnProviderSession.Request(
                CLI,
                provider,
                null,
                model,
                "high",
                workingDirectory,
                "system",
                "incremental prompt",
                List.of(),
                endpoint(),
                READ_ONLY,
                null,
                sessionId,
                fallbackPrompt,
                priorCumulativeInputTokens,
                priorCumulativeOutputTokens);
    }

    private static Path executable(Path tempDir, String source)
            throws Exception
    {
        Path executable = tempDir.resolve("fake-agent.sh");
        Files.writeString(executable, source);
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        return executable;
    }

    private static AgentTurnProviderSession.OwnerToolEndpoint endpoint()
    {
        return endpoint(READ_ONLY);
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
}
