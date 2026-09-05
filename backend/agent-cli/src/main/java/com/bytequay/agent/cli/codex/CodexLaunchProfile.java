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
import com.google.common.collect.ImmutableMap;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import static java.util.Objects.requireNonNull;

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
    /** Execution features a disposable text-only request must not expose to prompt data. */
    private static final List<String> TEXT_ONLY_DISABLED_FEATURES = ImmutableList.of(
            "code_mode",
            "js_repl",
            "shell_snapshot",
            "shell_tool",
            "unified_exec");

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
        return arguments(executable, childProfile, Optional.empty());
    }

    /**
     * Returns the full argument vector for one App Server launch, optionally pointing the process at
     * ByteQuay's task MCP endpoint for the CodeGraph tool. The bearer token is not on the command
     * line: it travels in {@link CodeGraphLaunch#TOKEN_VARIABLE} of the process environment, which
     * the shell policy above keeps away from the model-facing shell.
     */
    static List<String> arguments(String executable, ChildProfile childProfile, Optional<CodeGraphLaunch> codeGraph)
    {
        return arguments(executable, childProfile, codeGraph, false);
    }

    static List<String> arguments(
            String executable,
            ChildProfile childProfile,
            Optional<CodeGraphLaunch> codeGraph,
            boolean textOnly)
    {
        requireNonNull(codeGraph, "codeGraph is null");
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
        if (textOnly) {
            TEXT_ONLY_DISABLED_FEATURES.forEach(feature -> arguments.add("--disable", feature));
        }
        arguments.add(childProfile == ChildProfile.V1 ? "--enable" : "--disable", "multi_agent");
        arguments.add(childProfile == ChildProfile.V2 ? "--enable" : "--disable", "multi_agent_v2");
        if (childProfile == ChildProfile.V1) {
            arguments.add("-c", "agents.max_depth=" + MULTI_AGENT_MAX_DEPTH);
        }
        codeGraph.ifPresent(launch -> arguments.add(
                "-c", "mcp_servers.bytequay.url=" + tomlString(launch.endpoint().toString()),
                "-c", "mcp_servers.bytequay.bearer_token_env_var=" + tomlString(CodeGraphLaunch.TOKEN_VARIABLE),
                "-c", "developer_instructions=" + tomlString(launch.instruction())));
        return arguments.build();
    }

    /**
     * The restrictive per-thread override paired with the process launch flags.
     *
     * <p>The process flags remain the outer ceiling; repeating the profile on the thread prevents a
     * resumed provider thread from widening it. The MCP map is pinned empty for an ordinary task; a
     * CodeGraph launch already carries its one task-scoped endpoint as a process flag, so the thread
     * leaves that map alone rather than emptying it.
     */
    static Map<String, Object> threadConfig(ChildProfile childProfile)
    {
        return threadConfig(childProfile, false);
    }

    static Map<String, Object> threadConfig(ChildProfile childProfile, boolean codeGraph)
    {
        return threadConfig(childProfile, codeGraph, false);
    }

    static Map<String, Object> threadConfig(ChildProfile childProfile, boolean codeGraph, boolean textOnly)
    {
        requireNonNull(childProfile, "childProfile is null");
        ImmutableMap.Builder<String, Object> config = ImmutableMap.<String, Object>builder()
                .put("web_search", "disabled")
                .put("features", expectedFeatures(childProfile, textOnly))
                .put("shell_environment_policy", expectedShellEnvironmentPolicy());
        if (!codeGraph) {
            config.put("mcp_servers", ImmutableMap.of());
        }
        if (childProfile == ChildProfile.V1) {
            config.put("agents", ImmutableMap.of("max_depth", MULTI_AGENT_MAX_DEPTH));
        }
        return config.buildOrThrow();
    }

    static Map<String, Boolean> expectedFeatures(ChildProfile childProfile)
    {
        return expectedFeatures(childProfile, false);
    }

    static Map<String, Boolean> expectedFeatures(ChildProfile childProfile, boolean textOnly)
    {
        requireNonNull(childProfile, "childProfile is null");
        ImmutableMap.Builder<String, Boolean> features = ImmutableMap.builder();
        features.put("skip_host_skill_discovery", true);
        DISABLED_FEATURES.forEach(feature -> features.put(feature, false));
        if (textOnly) {
            TEXT_ONLY_DISABLED_FEATURES.forEach(feature -> features.put(feature, false));
        }
        features.put("multi_agent", childProfile == ChildProfile.V1);
        features.put("multi_agent_v2", childProfile == ChildProfile.V2);
        return features.buildOrThrow();
    }

    /** A TOML basic string: quotes, backslashes, and line breaks escaped, everything else literal. */
    static String tomlString(String value)
    {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    /** What a launch needs to expose the CodeGraph tool: where the task MCP endpoint is and what to tell the model. */
    record CodeGraphLaunch(URI endpoint, String instruction)
    {
        static final String TOKEN_VARIABLE = "BYTEQUAY_CODEGRAPH_TOKEN";

        CodeGraphLaunch
        {
            requireNonNull(endpoint, "endpoint is null");
            requireNonNull(instruction, "instruction is null");
        }
    }

    static Map<String, Object> expectedShellEnvironmentPolicy()
    {
        return ImmutableMap.of(
                "inherit", "core",
                "ignore_default_excludes", false,
                "include_only", SHELL_ENVIRONMENT_INCLUDE_ONLY,
                "experimental_use_profile", false);
    }

    static List<String> expectedShellEnvironmentIncludeOnly()
    {
        return SHELL_ENVIRONMENT_INCLUDE_ONLY;
    }

    static Optional<Integer> expectedAgentMaxDepth(ChildProfile childProfile)
    {
        requireNonNull(childProfile, "childProfile is null");
        return childProfile == ChildProfile.V1 ? Optional.of(MULTI_AGENT_MAX_DEPTH) : Optional.empty();
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
