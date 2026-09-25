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

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The shared task MCP token needs a child-aware hook before review sessions may spawn agents. */
class TestCodexReviewProfile
{
    @TempDir
    Path codexHome;

    @Test
    void reviewSessionsKeepTheirModelChildProfileBehindTheGate()
    {
        for (CodexLaunchProfile.ChildProfile configured : CodexLaunchProfile.ChildProfile.values()) {
            assertThat(CodexCliDriver.childProfile(configured, false, true, "0.156.1"))
                    .as("a review configured for %s", configured)
                    .isEqualTo(configured);
            assertThat(CodexCliDriver.childProfile(configured, false, true, "0.155.9")).isEqualTo(CodexLaunchProfile.ChildProfile.NONE);
        }
        Path gate = CodexReviewGate.install(codexHome);
        CodexLaunchProfile.CodeGraphLaunch tools = new CodexLaunchProfile.CodeGraphLaunch(
                URI.create("http://127.0.0.1:9000/mcp"), "Review", ImmutableList.of("review_get_context", "review_save_finding"),
                true, Optional.of(gate));
        for (CodexLaunchProfile.ChildProfile profile : ImmutableList.of(CodexLaunchProfile.ChildProfile.V1, CodexLaunchProfile.ChildProfile.V2)) {
            assertThat(CodexLaunchProfile.arguments("codex", profile, Optional.of(tools), false, ImmutableList.of(), false, true))
                    .contains("--enable", "hooks")
                    .anyMatch(argument -> argument.startsWith("hooks.PreToolUse=") && argument.contains(gate.toString()));
            assertThat(CodexLaunchProfile.expectedFeatures(profile, false, true)).containsEntry("hooks", true);
            assertThat(CodexLaunchProfile.threadConfig(profile, true, false, ImmutableList.of(), false, true))
                    .containsEntry("bypass_hook_trust", true);
        }
        assertThat(CodexLaunchProfile.arguments("codex", CodexLaunchProfile.ChildProfile.NONE, Optional.of(tools), false, ImmutableList.of(), false, true))
                .containsSequence("--disable", "multi_agent")
                .containsSequence("--disable", "multi_agent_v2")
                .doesNotContainSequence("--enable", "hooks");
        assertThat(CodexLaunchProfile.threadConfig(CodexLaunchProfile.ChildProfile.NONE, true, false, ImmutableList.of(), false, true))
                .doesNotContainKey("bypass_hook_trust");
    }

    @Test
    void anOrdinaryTaskKeepsTheProfileItsModelAsksFor()
    {
        for (CodexLaunchProfile.ChildProfile configured : CodexLaunchProfile.ChildProfile.values()) {
            assertThat(CodexCliDriver.childProfile(configured, false, false, "0.156.1")).isEqualTo(configured);
            // A text-only task has no way to show child activity, so it keeps the existing opt-out.
            assertThat(CodexCliDriver.childProfile(configured, true, false, "0.156.1"))
                    .isEqualTo(CodexLaunchProfile.ChildProfile.NONE);
        }
    }

    @Test
    void childCanOnlyUseTheTwoReviewReads()
            throws Exception
    {
        Path gate = CodexReviewGate.install(codexHome);
        assertThat(runGate(gate, "{\"tool_name\":\"mcp__bytequay__review_save_finding\"}")).isZero();
        assertThat(runGate(gate, "{\"agent_id\":\"child\",\"tool_name\":\"mcp__bytequay__review_get_context\"}")).isZero();
        assertThat(runGate(gate, "{\"agent_id\":\"child\",\"tool_name\":\"mcp__bytequay__codegraph_explore\"}")).isZero();
        for (String tool : ImmutableList.of("mcp__bytequay__review_save_finding", "Bash", "apply_patch", "Agent")) {
            assertThat(runGate(gate, "{\"agent_id\":\"child\",\"tool_name\":\"" + tool + "\"}"))
                    .as("child tool %s", tool)
                    .isEqualTo(2);
        }
        assertThat(runGate(gate, "{\"agent_id\":\"child\"}")).isEqualTo(2);
    }

    @Test
    void gateCommandRunsThroughShellWhenProfilePathContainsSpacesAndApostrophe()
            throws Exception
    {
        Path home = Files.createDirectory(codexHome.resolve("profile with Jack's name"));
        Path gate = CodexReviewGate.install(home);
        Process process = new ProcessBuilder("/bin/sh", "-c", CodexReviewGate.command(gate)).start();
        process.getOutputStream().write("{\"tool_name\":\"mcp__bytequay__review_save_finding\"}".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        assertThat(process.waitFor()).isZero();
    }

    private static int runGate(Path gate, String payload)
            throws Exception
    {
        Process process = new ProcessBuilder(gate.toString()).start();
        process.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        return process.waitFor();
    }
}
