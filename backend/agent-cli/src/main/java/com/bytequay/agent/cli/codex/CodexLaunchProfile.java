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
import java.nio.file.Path;
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
    /** Capabilities that must never be inherited by a task; the optional network proxy also blocks JVM builds on macOS. */
    private static final List<String> DISABLED_FEATURES = ImmutableList.of(
            "apps",
            "browser_use",
            "browser_use_external",
            "computer_use",
            "guardian_ext",
            "guardianv2",
            "image_generation",
            "in_app_browser",
            "memories",
            "network_proxy",
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
     * Returns the full argument vector for one App Server launch, optionally pointing the process at
     * ByteQuay's task MCP endpoint. A direct broker registration keeps its bearer in
     * {@link CodeGraphLaunch#TOKEN_VARIABLE}; a proxied registration keeps it outside Codex.
     */
    static List<String> arguments(
            String executable,
            ChildProfile childProfile,
            Optional<CodeGraphLaunch> codeGraph,
            boolean textOnly,
            List<String> ciWritableRoots,
            boolean allowGitHubCommands,
            boolean review)
    {
        return arguments(executable, childProfile, codeGraph, textOnly, ciWritableRoots, allowGitHubCommands, review, Optional.empty());
    }

    static List<String> arguments(
            String executable,
            ChildProfile childProfile,
            Optional<CodeGraphLaunch> codeGraph,
            boolean textOnly,
            List<String> ciWritableRoots,
            boolean allowGitHubCommands,
            boolean review,
            Optional<DeepSeekProfile> deepSeek)
    {
        requireNonNull(codeGraph, "codeGraph is null");
        requireNonNull(deepSeek, "deepSeek is null");
        boolean ci = !ciWritableRoots.isEmpty();
        boolean reviewChildren = review && childProfile != ChildProfile.NONE;
        checkArgument(!allowGitHubCommands || (!textOnly && !ci && !review), "Restricted Codex profiles cannot use attended network scope");
        checkArgument(!review || (!textOnly && !ci), "Restricted Codex profiles cannot run private review");
        checkArgument(!reviewChildren || codeGraph.flatMap(CodeGraphLaunch::reviewGate).isPresent(), "Review children require the ByteQuay hook gate");
        ImmutableList.Builder<String> arguments = ImmutableList.<String>builder().add(executable);
        arguments.add(
                "app-server",
                "--listen",
                "stdio://",
                "--strict-config",
                "--enable",
                "skip_host_skill_discovery");
        if (deepSeek.isPresent()) {
            arguments.add(
                    "-c", "cli_auth_credentials_store=\"ephemeral\"",
                    "-c", "model_provider=\"deepseek\"",
                    "-c", "model_catalog_json=" + tomlString(deepSeek.orElseThrow().catalog().toString()),
                    "-c", "model_providers.deepseek.name=\"DeepSeek\"",
                    "-c", "model_providers.deepseek.base_url=" + tomlString(deepSeek.orElseThrow().baseUrl().toString()),
                    "-c", "model_providers.deepseek.wire_api=\"responses\"",
                    "-c", "model_providers.deepseek.requires_openai_auth=false",
                    "-c", "model_providers.deepseek.supports_websockets=false",
                    "-c", "model_providers.deepseek.request_max_retries=0");
        }
        else {
            // forced_login_method synthesizes managed requirements; the isolation guard checks account/read instead.
            arguments.add("-c", "cli_auth_credentials_store=\"file\"");
        }
        arguments.add(
                "-c",
                "web_search=" + tomlString(textOnly || ci ? "disabled" : "live"),
                "-c",
                "shell_environment_policy.inherit=\"core\"",
                "-c",
                "shell_environment_policy.ignore_default_excludes=false",
                "-c",
                "shell_environment_policy.include_only=" + quotedList(expectedShellEnvironmentIncludeOnly()),
                "-c",
                "shell_environment_policy.experimental_use_profile=false",
                "-c",
                "sandbox_workspace_write.network_access=" + (allowGitHubCommands || review || ci),
                "-c",
                "approval_policy=" + tomlString(textOnly ? "never" : "on-request"),
                "-c",
                "approvals_reviewer=\"user\"");
        DISABLED_FEATURES.forEach(feature -> arguments.add("--disable", feature));
        arguments.add(reviewChildren ? "--enable" : "--disable", "hooks");
        arguments.add("--disable", "guardian_approval");
        if (ci) {
            arguments.add(
                    "-c", "sandbox_workspace_write.writable_roots=" + quotedList(ciWritableRoots),
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
        if (reviewChildren) {
            arguments.add("-c", "hooks.PreToolUse=[{matcher=\".*\",hooks=[{type=\"command\",command="
                    + tomlString(CodexReviewGate.command(codeGraph.orElseThrow().reviewGate().orElseThrow())) + ",timeout=5}]}]");
        }
        // The broker admits only this task's authorized tools; Codex must not ask a second approval question for them.
        codeGraph.ifPresent(launch -> {
            arguments.add("-c", "mcp_servers.bytequay.url=" + tomlString(launch.endpoint().toString()));
            if (launch.bearerTokenInEnvironment()) {
                arguments.add("-c", "mcp_servers.bytequay.bearer_token_env_var=" + tomlString(CodeGraphLaunch.TOKEN_VARIABLE));
            }
            arguments.add("-c", "mcp_servers.bytequay.default_tools_approval_mode=\"approve\"",
                    // The broker owns authorization for this exact task inventory; other tools retain their native approval policy.
                    "-c", "mcp_servers.bytequay.tools=" + launch.toolNames().stream()
                            .map(name -> tomlString(name) + "={approval_mode=\"approve\"}").collect(joining(",", "{", "}")),
                    "-c", "developer_instructions=" + tomlString(launch.instruction()));
        });
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
    static Map<String, Object> threadConfig(
            ChildProfile childProfile,
            boolean codeGraph,
            boolean textOnly,
            List<String> ciWritableRoots,
            boolean allowGitHubCommands,
            boolean review)
    {
        return threadConfig(childProfile, codeGraph, textOnly, ciWritableRoots, allowGitHubCommands, review, Optional.empty());
    }

    static Map<String, Object> threadConfig(
            ChildProfile childProfile,
            boolean codeGraph,
            boolean textOnly,
            List<String> ciWritableRoots,
            boolean allowGitHubCommands,
            boolean review,
            Optional<DeepSeekProfile> deepSeek)
    {
        requireNonNull(childProfile, "childProfile is null");
        requireNonNull(deepSeek, "deepSeek is null");
        boolean ci = !ciWritableRoots.isEmpty();
        checkArgument(!allowGitHubCommands || (!textOnly && !ci && !review), "Restricted Codex profiles cannot use attended network scope");
        checkArgument(!review || (!textOnly && !ci), "Restricted Codex profiles cannot run private review");
        ImmutableMap.Builder<String, Object> config = ImmutableMap.<String, Object>builder()
                .put("web_search", textOnly || ci ? "disabled" : "live")
                .put("features", expectedFeatures(childProfile, textOnly, review))
                .put("shell_environment_policy", expectedShellEnvironmentPolicy());
        if (review && childProfile != ChildProfile.NONE) {
            // App Server applies hook trust to each thread, not to its process flags.
            config.put("bypass_hook_trust", true);
        }
        if (deepSeek.isPresent()) {
            config.put("model_provider", "deepseek")
                    .put("model_catalog_json", deepSeek.orElseThrow().catalog().toString())
                    .put("model_providers", ImmutableMap.of("deepseek", deepSeekProviderConfig(deepSeek.orElseThrow().baseUrl())));
        }
        if (ci) {
            config.put("approval_policy", "on-request")
                    .put("approvals_reviewer", "user")
                    .put("sandbox_workspace_write", ciSandboxConfig(ciWritableRoots));
        }
        else if (allowGitHubCommands || review) {
            config.put("sandbox_workspace_write", ImmutableMap.of("network_access", true));
        }
        if (!codeGraph) {
            config.put("mcp_servers", ImmutableMap.of());
        }
        if (childProfile == ChildProfile.V1) {
            config.put("agents", ImmutableMap.of("max_depth", MULTI_AGENT_MAX_DEPTH));
        }
        return config.buildOrThrow();
    }

    static Map<String, Boolean> expectedFeatures(ChildProfile childProfile, boolean textOnly)
    {
        return expectedFeatures(childProfile, textOnly, false);
    }

    static Map<String, Boolean> expectedFeatures(ChildProfile childProfile, boolean textOnly, boolean review)
    {
        requireNonNull(childProfile, "childProfile is null");
        ImmutableMap.Builder<String, Boolean> features = ImmutableMap.builder();
        features.put("skip_host_skill_discovery", true);
        features.put("guardian_approval", false);
        DISABLED_FEATURES.forEach(feature -> features.put(feature, false));
        features.put("hooks", review && childProfile != ChildProfile.NONE);
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
                "network_access", true,
                "exclude_tmpdir_env_var", true,
                "exclude_slash_tmp", true);
    }

    static Map<String, Object> deepSeekProviderConfig(URI baseUrl)
    {
        return ImmutableMap.of(
                "name", "DeepSeek",
                "base_url", baseUrl.toString(),
                "wire_api", "responses",
                "requires_openai_auth", false,
                "supports_websockets", false,
                "request_max_retries", 0);
    }

    /** A TOML basic string: quotes, backslashes, and line breaks escaped, everything else literal. */
    static String tomlString(String value)
    {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    /** What a launch needs to expose the CodeGraph tool: where the task MCP endpoint is and what to tell the model. */
    record CodeGraphLaunch(URI endpoint, String instruction, List<String> toolNames, boolean bearerTokenInEnvironment, Optional<Path> reviewGate)
    {
        static final String TOKEN_VARIABLE = "BYTEQUAY_CODEGRAPH_TOKEN";

        CodeGraphLaunch(URI endpoint, String instruction, List<String> toolNames)
        {
            this(endpoint, instruction, toolNames, true, Optional.empty());
        }

        CodeGraphLaunch(URI endpoint, String instruction, List<String> toolNames, boolean bearerTokenInEnvironment)
        {
            this(endpoint, instruction, toolNames, bearerTokenInEnvironment, Optional.empty());
        }

        CodeGraphLaunch
        {
            requireNonNull(endpoint, "endpoint is null");
            requireNonNull(instruction, "instruction is null");
            toolNames = ImmutableList.copyOf(toolNames);
            requireNonNull(reviewGate, "reviewGate is null");
        }
    }

    record DeepSeekProfile(Path catalog, URI baseUrl)
    {
        DeepSeekProfile
        {
            requireNonNull(catalog, "catalog is null");
            requireNonNull(baseUrl, "baseUrl is null");
            checkArgument(baseUrl.getScheme().equals("http") && baseUrl.getHost().equals("127.0.0.1")
                    && baseUrl.getPort() > 0 && baseUrl.getRawQuery() == null, "DeepSeek proxy must use a loopback HTTP URL");
        }
    }

    static Map<String, Object> expectedShellEnvironmentPolicy()
    {
        ImmutableMap.Builder<String, Object> policy = ImmutableMap.<String, Object>builder()
                .put("inherit", "core")
                .put("ignore_default_excludes", false)
                .put("include_only", expectedShellEnvironmentIncludeOnly())
                .put("experimental_use_profile", false);
        return policy.buildOrThrow();
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
                .map(CodexLaunchProfile::tomlString)
                .collect(joining(",", "[", "]"));
    }
}
