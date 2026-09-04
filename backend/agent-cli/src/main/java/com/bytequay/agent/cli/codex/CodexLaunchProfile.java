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

import java.util.List;

/**
 * Builds the App Server launch arguments proven by the compatibility gate.
 *
 * <p>Launching with no arguments would accept whatever the host's Codex installation defaults to,
 * which is not a profile anything has verified. Every optional capability is therefore disabled
 * explicitly, the shell environment is reduced to a fixed allowlist, and host skill discovery is
 * skipped, so the running configuration matches the one the gate exercises.
 *
 * <p>The model catalog runs without subagents. A task process enables exactly the V1 or V2 feature
 * advertised by its selected model; V1 is bounded to the proven root/child/grandchild depth.
 */
final class CodexLaunchProfile
{
    /** Capabilities that must never be inherited by a task; each is disabled explicitly. */
    private static final List<String> DISABLED_FEATURES = ImmutableList.of(
            "apps",
            "browser_use",
            "browser_use_external",
            "computer_use",
            "hooks",
            "image_generation",
            "in_app_browser",
            "plugins",
            "remote_plugin",
            "skill_mcp_dependency_install",
            "skill_search",
            "tool_call_mcp_elicitation",
            "tool_suggest",
            "workspace_dependencies");

    /** The only environment variables a model-requested command may inherit. */
    private static final List<String> SHELL_ENVIRONMENT_INCLUDE_ONLY = ImmutableList.of(
            "HOME",
            "LANG",
            "LC_ALL",
            "LOGNAME",
            "PATH",
            "SHELL",
            "TERM",
            "TMPDIR",
            "USER");

    /** Root, child, and grandchild: the bounded recursive tree proven by the compatibility gate. */
    private static final int MULTI_AGENT_MAX_DEPTH = 2;

    private CodexLaunchProfile() {}

    /**
     * Returns the full argument vector for one App Server launch.
     *
     * @param executable resolved Codex executable
     * @return argument vector including the executable itself
     */
    static List<String> arguments(String executable, ChildProfile childProfile)
    {
        ImmutableList.Builder<String> arguments = ImmutableList.<String>builder().add(
                executable,
                "app-server",
                "--listen",
                "stdio://",
                "--strict-config",
                "--enable",
                "skip_host_skill_discovery",
                "-c",
                "cli_auth_credentials_store=\"file\"",
                "-c",
                "forced_login_method=\"chatgpt\"",
                "-c",
                "web_search=\"disabled\"",
                "-c",
                "shell_environment_policy.inherit=\"core\"",
                "-c",
                "shell_environment_policy.ignore_default_excludes=false",
                "-c",
                "shell_environment_policy.include_only=" + quotedList(SHELL_ENVIRONMENT_INCLUDE_ONLY),
                "-c",
                "shell_environment_policy.experimental_use_profile=false");
        DISABLED_FEATURES.forEach(feature -> arguments.add("--disable", feature));
        arguments.add(childProfile == ChildProfile.V1 ? "--enable" : "--disable", "multi_agent");
        arguments.add(childProfile == ChildProfile.V2 ? "--enable" : "--disable", "multi_agent_v2");
        if (childProfile == ChildProfile.V1) {
            arguments.add("-c", "agents.max_depth=" + MULTI_AGENT_MAX_DEPTH);
        }
        return arguments.build();
    }

    enum ChildProfile
    {
        NONE,
        V1,
        V2,
    }

    private static String quotedList(List<String> values)
    {
        return values.stream()
                .map(value -> "\"" + value + "\"")
                .collect(ImmutableList.toImmutableList())
                .toString()
                .replace(", ", ",");
    }
}
