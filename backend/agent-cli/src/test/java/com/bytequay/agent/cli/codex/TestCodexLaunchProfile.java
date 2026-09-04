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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestCodexLaunchProfile
{
    @Test
    void disablesEveryInheritableCapabilityWithoutSubagentsForCatalogs()
    {
        List<String> arguments = CodexLaunchProfile.arguments(
                "/usr/local/bin/codex",
                CodexLaunchProfile.ChildProfile.NONE);

        assertCommonIsolation(arguments);
        assertThat(arguments).containsSequence("--disable", "multi_agent");
        assertThat(arguments).containsSequence("--disable", "multi_agent_v2");
        assertThat(arguments).doesNotContain("agents.max_depth=2");
    }

    @Test
    void enablesOnlyV1AtRecursiveDepthTwo()
    {
        List<String> arguments = CodexLaunchProfile.arguments(
                "/usr/local/bin/codex",
                CodexLaunchProfile.ChildProfile.V1);

        assertCommonIsolation(arguments);
        assertThat(arguments).containsSequence("--enable", "multi_agent");
        assertThat(arguments).containsSequence("--disable", "multi_agent_v2");
        assertThat(arguments).containsSequence("-c", "agents.max_depth=2");
    }

    @Test
    void enablesOnlyV2WithoutAV1DepthSetting()
    {
        List<String> arguments = CodexLaunchProfile.arguments(
                "/usr/local/bin/codex",
                CodexLaunchProfile.ChildProfile.V2);

        assertCommonIsolation(arguments);
        assertThat(arguments).containsSequence("--disable", "multi_agent");
        assertThat(arguments).containsSequence("--enable", "multi_agent_v2");
        assertThat(arguments).doesNotContain("agents.max_depth=2");
    }

    private static void assertCommonIsolation(List<String> arguments)
    {
        assertThat(arguments).startsWith("/usr/local/bin/codex", "app-server", "--listen", "stdio://");
        // Launching without these would accept whatever the host installation defaults to.
        assertThat(arguments).contains("--strict-config");
        assertThat(arguments).containsSequence("--enable", "skip_host_skill_discovery");
        assertThat(arguments).contains("web_search=\"disabled\"");
        assertThat(arguments).contains(
                "shell_environment_policy.include_only=[\"HOME\",\"LANG\",\"LC_ALL\",\"LOGNAME\","
                        + "\"PATH\",\"SHELL\",\"TERM\",\"TMPDIR\",\"USER\"]");
        for (String feature : List.of("apps", "hooks", "plugins", "computer_use", "browser_use", "skill_search")) {
            assertThat(arguments).containsSequence("--disable", feature);
        }
    }
}
