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
package com.bytequay.app.service.agents.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The vendor flag ordering is already pinned by the old flow's adapter tests,
 * which route through this class. What is only reachable from here is the tool
 * bridge each vendor needs, so that is what these cover.
 */
final class TestCliAgentArgv
{
    private static final Path WORKTREE = Path.of("/tmp/bq-worktree");

    @Test
    void aClaudeLaunchWithoutItsMcpConfigRefuses()
    {
        // Claude takes its server from a config file and Codex takes a URL. A
        // launch missing its own form would start an agent with no tool bridge
        // at all, which reads as a model that declined to use its tools.
        assertThatThrownBy(() -> launch(
                CliAgentArgv.Vendor.CLAUDE_CODE, null, "http://127.0.0.1:1/mcp"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Claude MCP config");
    }

    @Test
    void aCodexLaunchWithoutItsMcpUrlRefuses()
    {
        assertThatThrownBy(() -> launch(
                CliAgentArgv.Vendor.CODEX, Path.of("/tmp/mcp.json"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mcpUrl is blank");
    }

    @Test
    void aWritingTurnIsNotHandedTheReadOnlySandbox()
    {
        List<String> claude = CliAgentArgv.of(new CliAgentArgv.Launch(
                CliAgentArgv.Vendor.CLAUDE_CODE, "claude", "claude-opus-4-8",
                null, WORKTREE, null, false, Path.of("/tmp/mcp.json"), null,
                null, null, null, List.of(), List.of()));
        List<String> codex = CliAgentArgv.of(new CliAgentArgv.Launch(
                CliAgentArgv.Vendor.CODEX, "codex", "gpt-5", null, WORKTREE,
                null, false, null, "http://127.0.0.1:1/mcp", null, null, null,
                List.of(), List.of()));

        // The single flag that decides whether a repair can edit anything. Both
        // vendors express it differently, so neither default is safe to assume.
        assertThat(claude).doesNotContain("--tools");
        assertThat(codex).containsSequence("--sandbox", "workspace-write");
    }

    @Test
    void anMcpFirstClaudeWriterKeepsItsOwnToolsPreapproved()
    {
        // The owner's tools are recommended, not exclusive: the native
        // toolset stays available and pre-approved, so the agent never
        // stalls on a permission nobody is at a terminal to grant.
        List<String> argv = CliAgentArgv.of(new CliAgentArgv.Launch(
                CliAgentArgv.Vendor.CLAUDE_CODE, "claude", "sonnet", null,
                WORKTREE, null, false, Path.of("/tmp/mcp.json"), null,
                "mcp__bytequay__request_tool_permission", null, "session-1",
                List.of("mcp__bytequay__read_file",
                        "mcp__bytequay__write_file"), List.of(), true));

        assertThat(argv).containsSequence(
                "--permission-prompt-tool",
                "mcp__bytequay__request_tool_permission");
        assertThat(argv).doesNotContain("--permission-mode", "--tools");
        List<String> allowed = List.of(argv.get(
                argv.indexOf("--allowedTools") + 1).split(","));
        assertThat(allowed).contains(
                "mcp__bytequay__read_file", "mcp__bytequay__write_file",
                "Read", "Glob", "Grep", "Edit", "Write",
                "Bash(git diff:*)", "Bash(mvn:*)", "Bash(npx:*)");
        // Auto-permission: a blanket Bash rule would approve rm, curl, and
        // git push alike; only the expected read/build patterns pass without
        // a card. Deleting its own scratch is the one rm that does not ask.
        assertThat(allowed).doesNotContain("Bash");
        assertThat(allowed).contains(
                "Bash(rm /tmp/*)", "Bash(rm -rf /tmp/*)",
                "Bash(rm " + WORKTREE + "/*)",
                "Bash(rm -rf " + WORKTREE + "/*)");
        assertThat(allowed).noneMatch(rule -> rule.equals("Bash(rm:*)")
                || rule.startsWith("Bash(curl")
                || rule.startsWith("Bash(git push")
                || rule.startsWith("Bash(git commit"));
        assertThat(argv.stream().filter("--allowedTools"::equals)).hasSize(1);
        assertThat(argv).containsSequence("--resume", "session-1");
    }

    @Test
    void anMcpFirstReadOnlyClaudeTurnKeepsTheNativeReadTrio()
    {
        List<String> argv = CliAgentArgv.of(new CliAgentArgv.Launch(
                CliAgentArgv.Vendor.CLAUDE_CODE, "claude", "sonnet", null,
                WORKTREE, null, true, Path.of("/tmp/mcp.json"), null,
                null, null, null,
                List.of("mcp__bytequay__read_diff"), List.of(), true));

        assertThat(argv).containsSequence(
                "--tools", "Read,Glob,Grep,WebFetch,WebSearch");
        assertThat(argv).containsSequence(
                "--allowedTools",
                "mcp__bytequay__read_diff,Read,Glob,Grep,WebFetch,WebSearch");
    }

    @Test
    void anMcpFirstCodexWriterKeepsTheWritableSandbox()
    {
        List<String> argv = CliAgentArgv.of(new CliAgentArgv.Launch(
                CliAgentArgv.Vendor.CODEX, "codex", "gpt-5", null,
                WORKTREE, null, false, null,
                "http://127.0.0.1:1/mcp", null, null, null,
                List.of(), List.of(), true));

        assertThat(argv).containsSequence("--sandbox", "workspace-write");
    }

    private static void launch(
            CliAgentArgv.Vendor vendor, Path mcpConfig, String mcpUrl)
    {
        new CliAgentArgv.Launch(
                vendor, "binary", "model", null, WORKTREE, null, true,
                mcpConfig, mcpUrl, null, null, null, List.of(), List.of());
    }
}
