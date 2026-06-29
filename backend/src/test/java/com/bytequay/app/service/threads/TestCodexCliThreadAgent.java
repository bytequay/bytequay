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

import com.bytequay.app.domain.AgentMetrics;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link CodexCliThreadAgent#buildCommand} — the
 * Codex-specific argv assembly (sandbox, working dir, model, resume, and
 * the prompt-as-trailing-arg with its first-turn role/memory preamble).
 * The provider-agnostic lifecycle is covered by the integration suite;
 * this pins the wire-level command without spawning a real {@code codex}.
 */
class TestCodexCliThreadAgent
{
    private static final String CWD = "/tmp/wt-codex";
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");

    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void freshTurnBuildsCodexExecWithSandboxModelAndPromptAsTrailingArg()
    {
        CodexCliThreadAgent agent = agent("gpt-5", /* sessionId */ null, /* role */ null);

        List<String> cmd = agent.buildCommand("do the thing").command();

        assertThat(cmd).containsSubsequence(
                "codex", "exec",
                "--json", "--skip-git-repo-check",
                "--sandbox", "workspace-write",
                "-C", CWD,
                "-m", "gpt-5");
        // No resume subcommand on the first turn.
        assertThat(cmd).doesNotContain("resume");
        // The prompt is the trailing positional arg, verbatim (no role /
        // memory preamble was supplied).
        assertThat(cmd.get(cmd.size() - 1)).isEqualTo("do the thing");
    }

    @Test
    void resumeTurnInsertsResumeSubcommandAndSkipsTheRolePreamble()
    {
        CodexCliThreadAgent agent = agent("gpt-5", "sess-abc", "ROLE BRIEF");

        List<String> cmd = agent.buildCommand("next step").command();

        // `codex [-c …] exec resume --json --skip-git-repo-check <id> <prompt>`
        // continues the recorded session — the resume args appear in order.
        assertThat(cmd).containsSubsequence(
                "exec", "resume", "--json", "--skip-git-repo-check", "sess-abc", "next step");
        // `exec resume` rejects --sandbox / -C / -m (they were recorded on the
        // session) — passing them made every resume exit 2. Guard against it.
        assertThat(cmd).doesNotContain("--sandbox", "-C", "-m");
    }

    @Test
    void wiresTheThreadMcpServerOnEveryTurn()
    {
        // Both a fresh and a resumed turn must carry the -c overrides that
        // point Codex at our per-agent MCP server, auto-approve its tool
        // calls (non-interactive exec can't answer the approval prompt), AND
        // enable the rmcp client (which actually enumerates the server's
        // tools), so a Codex trunk gets create_task / read_task / … the same
        // as the Claude agent. An agent built directly (not via the registry)
        // defaults to the reserved "trunk" agent key in its URL.
        String url = "mcp_servers.bytequay.url="
                + "\"http://127.0.0.1:53123/api/threads/thread-1/agents/trunk/mcp\"";
        String approval = "mcp_servers.bytequay.default_tools_approval_mode=\"approve\"";
        String rmcp = "experimental_use_rmcp_client=true";

        List<String> fresh = agent("gpt-5", null, "").buildCommand("go").command();
        assertThat(fresh).containsSubsequence("codex", "-c", url, "-c", approval, "-c", rmcp, "exec");

        List<String> resumed = agent("gpt-5", "sess-abc", "").buildCommand("go").command();
        assertThat(resumed).containsSubsequence(
                "codex", "-c", url, "-c", approval, "-c", rmcp, "exec", "resume");
    }

    @Test
    void autoApprovesOurMcpToolsWithApproveNotAuto()
    {
        // The per-server approval override MUST be "approve" (unconditional
        // skip). "auto" only skips the prompt when Codex has full-disk-write;
        // our --sandbox workspace-write turn lacks it, so "auto" falls back to
        // prompting, hits EOF on the closed stdin of non-interactive `exec`,
        // and Codex cancels the call ("cancelled by the user") — which
        // silently killed every create_task. Guard the exact value so a
        // regression to "auto" can't quietly strand the Codex trunk again.
        List<String> cmd = agent("gpt-5", /* sessionId */ null, /* role */ null)
                .buildCommand("go").command();

        assertThat(cmd).containsSubsequence(
                "-c", "mcp_servers.bytequay.default_tools_approval_mode=\"approve\"");
        assertThat(cmd).doesNotContain(
                "mcp_servers.bytequay.default_tools_approval_mode=\"auto\"");
    }

    @Test
    void metricsReportTheTasksOwnUsageNotTheThreadLifetime()
    {
        // A focused task agent must report the TASK's own spend, not the
        // thread's lifetime-cumulative total — otherwise a freshly-cut task
        // shows the whole chain's 26M tokens (the context-window bug).
        when(threadStore.listMessages(anyString())).thenReturn(List.of());
        when(threadStore.listFiles(anyString())).thenReturn(List.of());
        // Task's own usage is small; the thread's lifetime is huge.
        Task active = new Task(
                "task-1", "thread-1", 1L, TaskStatus.RUNNING,
                "auto/task-1", CWD, "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                /* cost */ 2L, /* tokensIn */ 5L, /* tokensOut */ 3L,
                /* agentSessionId */ null, NOW, null, null, null, null, null);
        when(taskStore.findActiveTaskForThread("thread-1")).thenReturn(Optional.of(active));
        Thread thread = new Thread(
                "thread-1", ThreadKind.CLI_AGENT, "codex", null, "Codex test", ThreadStatus.IDLE,
                "gpt-5",
                /* cost */ 9_000L, /* tokensIn */ 26_000_000L, /* tokensOut */ 200_000L,
                NOW, NOW, null, null,
                ThreadFlow.BUILD, "ws-default", null, null);
        CodexCliThreadAgent agent = new CodexCliThreadAgent(
                thread, threadStore, taskStore, new CodexJsonParser(mapper), mapper,
                mock(McpPermissionGate.class), mock(ExecutorService.class),
                mock(CheckpointTrigger.class), () -> "", null, "codex", active);

        AgentMetrics metrics = agent.metrics();

        assertThat(metrics.tokensIn()).isEqualTo(5L);
        assertThat(metrics.tokensOut()).isEqualTo(3L);
        assertThat(metrics.costUsdMilli()).isEqualTo(2L);
    }

    @Test
    void firstTurnFoldsRoleAndWorkspaceMemoryIntoThePrompt()
    {
        CodexCliThreadAgent agent = agent("gpt-5", /* sessionId */ null, "ROLE BRIEF",
                /* memory */ "PROJECT FACTS");

        String prompt = lastArg(agent.buildCommand("implement X"));

        assertThat(prompt).contains("ROLE BRIEF");
        assertThat(prompt).contains("# Workspace memory");
        assertThat(prompt).contains("PROJECT FACTS");
        // The user's prompt lands after the preamble separator.
        assertThat(prompt).contains("---");
        assertThat(prompt).endsWith("implement X");
    }

    @Test
    void omitsModelFlagWhenNoModelIsConfigured()
    {
        CodexCliThreadAgent agent = agent(/* model */ "", /* sessionId */ null, /* role */ null);

        assertThat(agent.buildCommand("go").command()).doesNotContain("-m");
    }

    @Test
    void runsTheSubprocessInTheTaskWorkingDirectory()
    {
        CodexCliThreadAgent agent = agent("gpt-5", null, null);

        assertThat(agent.buildCommand("go").directory()).isEqualTo(Path.of(CWD).toFile());
    }

    private static String lastArg(ProcessBuilder pb)
    {
        List<String> cmd = pb.command();
        return cmd.get(cmd.size() - 1);
    }

    private CodexCliThreadAgent agent(String model, String sessionId, String roleSkillText)
    {
        return agent(model, sessionId, roleSkillText, "");
    }

    private CodexCliThreadAgent agent(String model, String sessionId, String roleSkillText, String memory)
    {
        when(threadStore.listMessages(anyString())).thenReturn(List.of());
        Task active = new Task(
                "task-1", "thread-1", /* seq */ 1L, TaskStatus.RUNNING,
                /* branchName */ "auto/task-1",
                /* worktreePath */ CWD,
                /* baseBranch */ "main",
                /* workingDir */ "/tmp/repo",
                /* processPid */ null, /* logPath */ null,
                /* prNumber */ null, /* prState */ null, /* ciState */ null,
                /* taskType */ "DEVELOP",
                /* linkedPrNumber */ null, /* linkedIssueNumber */ null,
                0L, 0L, 0L,
                /* agentSessionId */ sessionId,
                NOW, null, null, null, null, null);
        when(taskStore.findActiveTaskForThread("thread-1")).thenReturn(Optional.of(active));
        Thread thread = new Thread(
                "thread-1", ThreadKind.CLI_AGENT, "codex", /* agentSessionId */ null,
                "Codex test", ThreadStatus.IDLE,
                model,
                0L, 0L, 0L,
                NOW, NOW, null, null,
                ThreadFlow.BUILD, "ws-default", null, null);
        return new CodexCliThreadAgent(
                thread, threadStore, taskStore, new CodexJsonParser(mapper), mapper,
                mock(McpPermissionGate.class), mock(ExecutorService.class),
                mock(CheckpointTrigger.class), () -> memory, roleSkillText, "codex", active);
    }
}
