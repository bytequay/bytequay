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

import com.bytequay.agent.cli.process.GitCliEnvironment;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/**
 * Builds the App Server launch arguments proven by the compatibility gate.
 *
 * <p>Launching with no arguments would accept whatever the host's Codex installation defaults to,
 * which is not a profile anything has verified. Tasks explicitly enable native web reading; other
 * optional capabilities are disabled, the shell environment is reduced to a fixed allowlist, and
 * host skill discovery is skipped, so the running configuration matches the one the gate exercises.
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
            "guardian_ext",
            "guardianv2",
            "hooks",
            "image_generation",
            "in_app_browser",
            "memories",
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
    private static final List<String> GIT_SHELL_ENVIRONMENT_INCLUDE_ONLY = ImmutableList.<String>builder()
            .addAll(SHELL_ENVIRONMENT_INCLUDE_ONLY)
            .add("XDG_CONFIG_HOME")
            .addAll(GitCliEnvironment.variables())
            .build();

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
        return arguments(executable, childProfile, codeGraph, textOnly, ImmutableList.of(), false);
    }

    static List<String> arguments(
            String executable,
            ChildProfile childProfile,
            Optional<CodeGraphLaunch> codeGraph,
            boolean textOnly,
            List<String> ciWritableRoots,
            boolean allowGitHubCommands)
    {
        requireNonNull(codeGraph, "codeGraph is null");
        boolean ci = !ciWritableRoots.isEmpty();
        checkArgument(!allowGitHubCommands || (!textOnly && !ci), "Restricted Codex profiles cannot inherit Git authentication");
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
                "web_search=" + tomlString(textOnly || ci ? "disabled" : "live"),
                "-c",
                "shell_environment_policy.inherit=" + tomlString(allowGitHubCommands ? "all" : "core"),
                "-c",
                "shell_environment_policy.ignore_default_excludes=" + allowGitHubCommands,
                "-c",
                "shell_environment_policy.include_only=" + quotedList(expectedShellEnvironmentIncludeOnly(allowGitHubCommands)),
                "-c",
                "shell_environment_policy.experimental_use_profile=false");
        DISABLED_FEATURES.forEach(feature -> arguments.add("--disable", feature));
        arguments.add(textOnly || ci ? "--disable" : "--enable", "guardian_approval");
        if (!textOnly && !ci) {
            arguments.add("-c", "auto_review.policy=" + tomlString(CodexApprovalPolicy.policy()));
        }
        if (ci) {
            arguments.add(
                    "-c", "approval_policy=\"never\"",
                    "-c", "approvals_reviewer=\"user\"",
                    "-c", "sandbox_workspace_write.writable_roots=" + quotedList(ciWritableRoots),
                    "-c", "sandbox_workspace_write.network_access=false",
                    "-c", "sandbox_workspace_write.exclude_tmpdir_env_var=true",
                    "-c", "sandbox_workspace_write.exclude_slash_tmp=true");
        }
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
                // The broker owns authorization for this exact task inventory; other tools retain their native approval policy.
                "-c", "mcp_servers.bytequay.tools=" + launch.toolNames().stream()
                        .map(name -> tomlString(name) + "={approval_mode=\"approve\"}").collect(joining(",", "{", "}")),
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
        return threadConfig(childProfile, codeGraph, textOnly, ImmutableList.of(), false);
    }

    static Map<String, Object> threadConfig(ChildProfile childProfile, boolean codeGraph, boolean textOnly, List<String> ciWritableRoots, boolean allowGitHubCommands)
    {
        requireNonNull(childProfile, "childProfile is null");
        boolean ci = !ciWritableRoots.isEmpty();
        checkArgument(!allowGitHubCommands || (!textOnly && !ci), "Restricted Codex profiles cannot inherit Git authentication");
        ImmutableMap.Builder<String, Object> config = ImmutableMap.<String, Object>builder()
                .put("web_search", textOnly || ci ? "disabled" : "live")
                .put("features", expectedFeatures(childProfile, textOnly, ci))
                .put("shell_environment_policy", expectedShellEnvironmentPolicy(allowGitHubCommands));
        if (!textOnly && !ci) {
            config.put("auto_review", ImmutableMap.of("policy", CodexApprovalPolicy.policy()));
        }
        if (ci) {
            config.put("approval_policy", "never")
                    .put("approvals_reviewer", "user")
                    .put("sandbox_workspace_write", ciSandboxConfig(ciWritableRoots));
        }
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
        return expectedFeatures(childProfile, textOnly, false);
    }

    static Map<String, Boolean> expectedFeatures(ChildProfile childProfile, boolean textOnly, boolean ci)
    {
        requireNonNull(childProfile, "childProfile is null");
        ImmutableMap.Builder<String, Boolean> features = ImmutableMap.builder();
        features.put("skip_host_skill_discovery", true);
        features.put("guardian_approval", !textOnly && !ci);
        DISABLED_FEATURES.forEach(feature -> features.put(feature, false));
        if (textOnly) {
            TEXT_ONLY_DISABLED_FEATURES.forEach(feature -> features.put(feature, false));
        }
        features.put("multi_agent", childProfile == ChildProfile.V1);
        features.put("multi_agent_v2", childProfile == ChildProfile.V2);
        return features.buildOrThrow();
    }

    static Map<String, Object> ciSandboxConfig(List<String> writableRoots)
    {
        return ImmutableMap.of(
                "writable_roots", ImmutableList.copyOf(writableRoots),
                "network_access", false,
                "exclude_tmpdir_env_var", true,
                "exclude_slash_tmp", true);
    }

    /** A TOML basic string: quotes, backslashes, and line breaks escaped, everything else literal. */
    static String tomlString(String value)
    {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    /** What a launch needs to expose the CodeGraph tool: where the task MCP endpoint is and what to tell the model. */
    record CodeGraphLaunch(URI endpoint, String instruction, List<String> toolNames)
    {
        static final String TOKEN_VARIABLE = "BYTEQUAY_CODEGRAPH_TOKEN";

        CodeGraphLaunch
        {
            requireNonNull(endpoint, "endpoint is null");
            requireNonNull(instruction, "instruction is null");
            toolNames = ImmutableList.copyOf(toolNames);
        }
    }

    static Map<String, Object> expectedShellEnvironmentPolicy(boolean allowGitHubCommands)
    {
        return ImmutableMap.of(
                "inherit", allowGitHubCommands ? "all" : "core",
                "ignore_default_excludes", allowGitHubCommands,
                "include_only", expectedShellEnvironmentIncludeOnly(allowGitHubCommands),
                "experimental_use_profile", false);
    }

    static List<String> expectedShellEnvironmentIncludeOnly(boolean allowGitHubCommands)
    {
        return allowGitHubCommands ? GIT_SHELL_ENVIRONMENT_INCLUDE_ONLY : SHELL_ENVIRONMENT_INCLUDE_ONLY;
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
                .map(CodexLaunchProfile::tomlString)
                .collect(joining(",", "[", "]"));
    }
}
