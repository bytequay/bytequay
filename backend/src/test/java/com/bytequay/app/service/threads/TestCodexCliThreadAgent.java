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
import com.bytequay.app.service.skills.ManagedSkill;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
    void resumeTurnKeepsTheByteQuayContextAndIgnoresProviderConfig()
    {
        CodexCliThreadAgent agent = agent("gpt-5", "sess-abc", "ROLE BRIEF");

        List<String> cmd = agent.buildCommand("next step").command();

        // `codex [-c …] exec resume --json --skip-git-repo-check <id> <prompt>`
        // continues the recorded session — the resume args appear in order.
        assertThat(cmd).containsSubsequence(
                "exec", "--ignore-user-config", "resume", "--json",
                "--skip-git-repo-check", "-m", "gpt-5", "sess-abc");
        assertThat(cmd.get(cmd.size() - 1)).contains("ROLE BRIEF").endsWith("next step");
        // Resume accepts a model override, but still rejects the fresh-session
        // sandbox and working-directory flags.
        assertThat(cmd).doesNotContain("--sandbox", "-C");
    }

    @Test
    void missingRolloutRetriesAsAFreshCodexSession()
    {
        CodexCliThreadAgent agent = agent("gpt-5", "claude-session-id", "ROLE BRIEF");

        assertThat(agent.shouldAutomaticallyRecover(
                "codex exited with code 1: no rollout found for thread id claude-session-id"))
                .isTrue();

        List<String> retry = agent.buildCommand("next step").command();
        assertThat(retry).doesNotContain("resume", "claude-session-id");
        assertThat(retry).containsSubsequence("exec", "--json", "--skip-git-repo-check");
    }

    @Test
    void aStageTurnResumesTheTaskSession()
    {
        CodexCliThreadAgent agent = agent("gpt-5", "sess-abc", "");
        agent.setActiveStage("stage-1");

        List<String> cmd = agent.buildCommand("go").command();

        assertThat(cmd).containsSubsequence("resume", "sess-abc");
        assertThat(cmd.get(cmd.size() - 1)).isEqualTo("go");
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
    void disablesProviderInstructionFilesForFreshAndResumedTurns()
    {
        List<String> fresh = agent("gpt-5", null, "").buildCommand("go").command();
        List<String> resumed = agent("gpt-5", "sess-abc", "").buildCommand("go").command();

        assertThat(fresh).containsSubsequence("-c", "project_doc_max_bytes=0", "exec",
                "--ignore-user-config");
        assertThat(resumed).containsSubsequence("-c", "project_doc_max_bytes=0", "exec",
                "--ignore-user-config", "resume");
    }

    @Test
    void aBoundStageKeyReplacesTheTrunkKeyInTheMcpUrl()
    {
        // The registry binds each stage agent its own key; that key must flow
        // into the agent's own MCP URL so concurrent stage agents on one thread
        // resolve role/turn separately instead of colliding on /agents/trunk.
        CodexCliThreadAgent agent = agent("gpt-5", /* sessionId */ null, "");
        agent.setMcpAgentKey("stage-abc");

        List<String> cmd = agent.buildCommand("go").command();

        assertThat(cmd).anyMatch(arg -> arg.contains(
                "http://127.0.0.1:53123/api/threads/thread-1/agents/stage-abc/mcp"));
        assertThat(cmd).noneMatch(arg -> arg.contains("/agents/trunk/mcp"));
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
    void reasoningEffortOverrideIsPassedToFreshAndResumedTrunkTurns()
    {
        List<String> fresh = trunkAgent(/* sessionId */ null, "high")
                .buildCommand("plan").command();
        List<String> resumed = trunkAgent("sess-abc", "high")
                .buildCommand("continue").command();

        assertThat(fresh).containsSubsequence("-c", "model_reasoning_effort=\"high\"", "exec");
        assertThat(resumed).containsSubsequence("-c", "model_reasoning_effort=\"high\"",
                "exec", "resume");
    }

    @Test
    void freshTrunkTurnUsesAReadOnlySandbox()
    {
        List<String> cmd = trunkAgent(null, null).buildCommand("cut a task").command();

        assertThat(cmd).containsSubsequence("-c", "sandbox_mode=\"read-only\"");
        assertThat(cmd).containsSubsequence("--sandbox", "read-only");
        assertThat(cmd).doesNotContain("workspace-write");
    }

    @Test
    void resumedTrunkTurnOverridesAnOlderSessionsSandbox()
    {
        CodexCliThreadAgent agent = trunkAgent("sess-abc", null, "TRUNK ROLE");
        List<String> cmd = agent.buildCommand("continue").command();

        assertThat(cmd).containsSubsequence("-c", "sandbox_mode=\"read-only\"");
        assertThat(cmd).doesNotContain("--sandbox", "workspace-write");
        assertThat(cmd.get(cmd.size() - 1)).contains("TRUNK ROLE").endsWith("continue");
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
        when(taskStore.activeTasksForThread("thread-1")).thenReturn(List.of(active));
        Thread thread = new Thread(
                "thread-1", ThreadKind.CLI_AGENT, "codex", null, "Codex test", ThreadStatus.IDLE,
                "gpt-5",
                /* cost */ 9_000L, /* tokensIn */ 26_000_000L, /* tokensOut */ 200_000L,
                NOW, NOW, null, null,
                ThreadFlow.BUILD, "ws-default", null, null);
        CodexCliThreadAgent agent = new CodexCliThreadAgent(
                thread, threadStore, taskStore, new CodexJsonParser(mapper), mapper,
                mock(McpPermissionGate.class), mock(ExecutorService.class),
                mock(CheckpointTrigger.class), () -> "", null, "codex", null,
                active, null, null);

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
    void resolvedAuthoredSkillBodyIsInjectedOnFreshAndResumedTurns()
    {
        CodexCliThreadAgent fresh = agent("gpt-5", null, "TASK ROLE");
        fresh.setActiveManagedSkills(List.of(new ManagedSkill("authored", "AUTHORED BODY")));
        CodexCliThreadAgent resumed = agent("gpt-5", "sess-abc", "TASK ROLE");
        resumed.setActiveManagedSkills(List.of(new ManagedSkill("authored", "AUTHORED BODY")));

        assertThat(lastArg(fresh.buildCommand("implement")))
                .contains("## authored", "AUTHORED BODY");
        assertThat(lastArg(resumed.buildCommand("continue")))
                .contains("## authored", "AUTHORED BODY");
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
        when(taskStore.activeTasksForThread("thread-1")).thenReturn(List.of(active));
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
                mock(CheckpointTrigger.class), () -> memory, roleSkillText, "codex",
                null, active, null, null);
    }

    private CodexCliThreadAgent trunkAgent(String sessionId, String reasoningEffort)
    {
        return trunkAgent(sessionId, reasoningEffort, null);
    }

    private CodexCliThreadAgent trunkAgent(
            String sessionId, String reasoningEffort, String roleSkillText)
    {
        when(threadStore.listMessages(anyString())).thenReturn(List.of());
        Thread thread = new Thread(
                "thread-1", ThreadKind.CLI_AGENT, "codex", sessionId,
                "Codex trunk test", ThreadStatus.IDLE, "gpt-5",
                0L, 0L, 0L,
                NOW, NOW, null, null,
                ThreadFlow.BUILD, "ws-default", null, null);
        return new CodexCliThreadAgent(
                thread, threadStore, taskStore, new CodexJsonParser(mapper), mapper,
                mock(McpPermissionGate.class), mock(ExecutorService.class),
                mock(CheckpointTrigger.class), () -> "", roleSkillText,
                CodexCliThreadAgent.DEFAULT_BINARY, CWD, null, null, reasoningEffort);
    }
}
