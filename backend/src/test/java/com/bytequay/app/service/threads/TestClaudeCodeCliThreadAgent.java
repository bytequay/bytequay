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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestClaudeCodeCliThreadAgent
{
    private static final String CWD = "/tmp/wt-claude";
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");

    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void reasoningEffortOverrideIsPassedToClaude()
    {
        when(threadStore.listMessages(anyString())).thenReturn(List.of());
        Thread thread = new Thread(
                "thread-1", ThreadKind.CLI_AGENT, "claude-code", /* agentSessionId */ null,
                "Claude trunk test", ThreadStatus.IDLE, "claude-sonnet-4-6",
                0L, 0L, 0L,
                NOW, NOW, null, null,
                ThreadFlow.BUILD, "ws-default", null, null);
        ClaudeCodeCliThreadAgent agent = new ClaudeCodeCliThreadAgent(
                thread, threadStore, taskStore, new StreamJsonParser(mapper), mapper,
                mock(McpPermissionGate.class), mock(ExecutorService.class),
                mock(CheckpointTrigger.class), () -> "", null, null, CWD,
                ClaudeCodeCliThreadAgent.TrunkMode.ENABLED, "high");

        assertThat(agent.buildCommand("plan").command())
                .containsSubsequence("--effort", "high");
    }

    @Test
    void trunkOnlyReceivesReadOnlyBuiltInTools()
    {
        when(threadStore.listMessages(anyString())).thenReturn(List.of());
        Thread thread = new Thread(
                "thread-1", ThreadKind.CLI_AGENT, "claude-code", null,
                "Claude trunk test", ThreadStatus.IDLE, "claude-sonnet-4-6",
                0L, 0L, 0L, NOW, NOW, null, null,
                ThreadFlow.BUILD, "ws-default", null, null);
        ClaudeCodeCliThreadAgent agent = new ClaudeCodeCliThreadAgent(
                thread, threadStore, taskStore, new StreamJsonParser(mapper), mapper,
                mock(McpPermissionGate.class), mock(ExecutorService.class),
                mock(CheckpointTrigger.class), () -> "", null, null, CWD,
                ClaudeCodeCliThreadAgent.TrunkMode.ENABLED);

        assertThat(agent.buildCommand("cut a task").command())
                .containsSubsequence("--tools", "Read,Glob,Grep,WebFetch,WebSearch")
                .containsSubsequence("--allowed-tools",
                        "Read(/" + ChatAttachmentStore.attachmentsRoot().toAbsolutePath().normalize() + "/**)")
                .containsSubsequence("--setting-sources", "")
                .containsSubsequence("--settings",
                        "{\"autoMemoryEnabled\":false,\"attribution\":{\"commit\":\"\"}}")
                .contains("--disable-slash-commands", "--no-chrome")
                .contains("--strict-mcp-config", "--permission-prompt-tool")
                .doesNotContain("--safe-mode")
                .doesNotContain("Bash", "Edit", "Write", "Task");
    }

    @Test
    void onlyMissingByteQuayPermissionToolIsAutomaticallyRecoverable()
    {
        when(threadStore.listMessages(anyString())).thenReturn(List.of());
        Thread thread = new Thread(
                "thread-1", ThreadKind.CLI_AGENT, "claude-code", null,
                "Claude trunk test", ThreadStatus.IDLE, "claude-sonnet-4-6",
                0L, 0L, 0L, NOW, NOW, null, null,
                ThreadFlow.BUILD, "ws-default", null, null);
        ClaudeCodeCliThreadAgent agent = new ClaudeCodeCliThreadAgent(
                thread, threadStore, taskStore, new StreamJsonParser(mapper), mapper,
                mock(McpPermissionGate.class), mock(ExecutorService.class),
                mock(CheckpointTrigger.class), () -> "", null, null, CWD,
                ClaudeCodeCliThreadAgent.TrunkMode.ENABLED);

        assertThat(agent.shouldAutomaticallyRecover("claude exited with code 1:\n"
                + "MCP tool mcp__bytequay__approval_prompt (passed via --permission-prompt-tool) "
                + "not found. Available MCP tools: none")).isTrue();
        assertThat(agent.shouldAutomaticallyRecover("claude exited with code 1: authentication failed"))
                .isFalse();
    }

    @Test
    void preTurnHookNotePrefixesTheModelInputOnly()
    {
        when(threadStore.listMessages(anyString())).thenReturn(List.of());
        Thread thread = new Thread(
                "thread-1", ThreadKind.CLI_AGENT, "claude-code", null,
                "Claude trunk test", ThreadStatus.IDLE, "claude-sonnet-4-6",
                0L, 0L, 0L, NOW, NOW, null, null,
                ThreadFlow.BUILD, "ws-default", null, null);
        ClaudeCodeCliThreadAgent agent = new ClaudeCodeCliThreadAgent(
                thread, threadStore, taskStore, new StreamJsonParser(mapper), mapper,
                mock(McpPermissionGate.class), mock(ExecutorService.class),
                mock(CheckpointTrigger.class), () -> "", null, null, CWD,
                ClaudeCodeCliThreadAgent.TrunkMode.ENABLED);

        // No hook: the prompt passes through untouched.
        assertThat(agent.composeTurnInput("cut phase 3")).isEqualTo("cut phase 3");

        // A hook note is prepended to the turn input …
        agent.setPreTurnHook(() -> "[Planning base updated abc -> def]");
        assertThat(agent.composeTurnInput("cut phase 3"))
                .isEqualTo("[Planning base updated abc -> def]\n\ncut phase 3");

        // … a blank note is ignored, and a throwing hook never blocks the turn.
        agent.setPreTurnHook(() -> " ");
        assertThat(agent.composeTurnInput("cut phase 3")).isEqualTo("cut phase 3");
        agent.setPreTurnHook(() -> {
            throw new IllegalStateException("sync failed");
        });
        assertThat(agent.composeTurnInput("cut phase 3")).isEqualTo("cut phase 3");
    }
}
