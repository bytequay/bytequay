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
            // A goal makes App Server start further turns itself, outside the one turn each process serves.
            "goals",
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
    private static final List<String> TEXT_ONLY_DISABLED_FEATURES = ImmutableList.of("code_mode", "js_repl", "shell_snapshot", "shell_tool", "unified_exec");

    /** The only environment variables a model-requested command may inherit. */
    private static final List<String> SHELL_ENVIRONMENT_INCLUDE_ONLY = ImmutableList.of("HOME", "LANG", "LC_ALL", "LOGNAME", "PATH", "SHELL", "TERM", "TMPDIR", "USER");
    /** Root, child, and grandchild: the bounded recursive tree proven by the compatibility gate. */
    private static final int MULTI_AGENT_MAX_DEPTH = 2;
    /** Codex's experimental switch for its question tool outside plan mode. */
    static final String ASK_USER_FEATURE = "default_mode_request_user_input";

    private CodexLaunchProfile() {}

    /**
     * Returns the full argument vector for one App Server launch, optionally pointing the process at
     * ByteQuay's task MCP endpoint. A direct broker registration keeps its bearer in
     * {@link ManagedToolsLaunch#TOKEN_VARIABLE}; a proxied registration keeps it outside Codex.
     */
    static List<String> arguments(String executable, Options options, Optional<ManagedToolsLaunch> codeQueries)
    {
        requireNonNull(options, "options is null");
        requireNonNull(codeQueries, "codeQueries is null");
        ChildProfile childProfile = options.childProfile();
        boolean textOnly = options.textOnly();
        boolean ci = options.ci();
        boolean reviewChildren = options.review() && childProfile != ChildProfile.NONE;
        checkArgument(!reviewChildren || codeQueries.flatMap(ManagedToolsLaunch::reviewGate).isPresent(), "Review children require the ByteQuay hook gate");
        ImmutableList.Builder<String> arguments = ImmutableList.<String>builder().add(executable);
        arguments.add("app-server", "--listen", "stdio://", "--strict-config", "--enable", "skip_host_skill_discovery");
        if (options.deepSeek().isPresent()) {
            DeepSeekProfile deepSeek = options.deepSeek().orElseThrow();
            arguments.add(
                    "-c", "cli_auth_credentials_store=\"ephemeral\"",
                    "-c", "model_provider=\"deepseek\"",
                    "-c", "model_catalog_json=" + tomlString(deepSeek.catalog().toString()),
                    "-c", "model_providers.deepseek.name=\"DeepSeek\"",
                    "-c", "model_providers.deepseek.base_url=" + tomlString(deepSeek.baseUrl().toString()),
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
                "sandbox_workspace_write.network_access=" + (options.githubCommands() || options.review() || ci),
                "-c",
                "approval_policy=" + tomlString(textOnly ? "never" : "on-request"),
                "-c",
                "approvals_reviewer=\"auto_review\"",
                // ByteQuay hands both lanes the same guideline files, read at a commit the task cannot change.
                "-c",
                "project_doc_max_bytes=0");
        DISABLED_FEATURES.forEach(feature -> arguments.add("--disable", feature));
        arguments.add(reviewChildren ? "--enable" : "--disable", "hooks");
        arguments.add(textOnly ? "--disable" : "--enable", "guardian_approval");
        if (options.asksUser()) {
            // Outside plan mode, request_user_input is off unless this feature is on.
            arguments.add("--enable", ASK_USER_FEATURE);
        }
        if (ci) {
            arguments.add(
                    "-c", "sandbox_workspace_write.writable_roots=" + quotedList(options.ciWritableRoots()),
                    "-c", "sandbox_workspace_write.exclude_tmpdir_env_var=true",
                    "-c", "sandbox_workspace_write.exclude_slash_tmp=true");
        }
        if (textOnly) {
            TEXT_ONLY_DISABLED_FEATURES.forEach(feature -> arguments.add("--disable", feature));
        }
        arguments.add(childProfile == ChildProfile.V1 ? "--enable" : "--disable", "multi_agent");
        arguments.add(childProfile == ChildProfile.V2 ? "--enable" : "--disable", "multi_agent_v2");
        // Model metadata can enable subagents despite the legacy feature flags in newer Codex releases.
        arguments.add("-c", "agents.enabled=" + (childProfile != ChildProfile.NONE));
        if (childProfile == ChildProfile.V1) {
            arguments.add("-c", "agents.max_depth=" + MULTI_AGENT_MAX_DEPTH);
        }
        if (reviewChildren) {
            arguments.add("-c", "hooks.PreToolUse=[{matcher=\".*\",hooks=[{type=\"command\",command="
                    + tomlString(CodexReviewGate.command(codeQueries.orElseThrow().reviewGate().orElseThrow())) + ",timeout=5}]}]");
        }
        // The broker admits only this task's authorized tools; Codex must not ask a second approval question for them.
        codeQueries.ifPresent(launch -> {
            arguments.add("-c", "mcp_servers.bytequay.url=" + tomlString(launch.endpoint().toString()));
            if (launch.bearerTokenInEnvironment()) {
                arguments.add("-c", "mcp_servers.bytequay.bearer_token_env_var=" + tomlString(ManagedToolsLaunch.TOKEN_VARIABLE));
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
     * managed-tools launch already carries its one task-scoped endpoint as a process flag, so the thread
     * leaves that map alone rather than emptying it.
     */
    static Map<String, Object> threadConfig(Options options, boolean codeQueries)
    {
        requireNonNull(options, "options is null");
        ChildProfile childProfile = options.childProfile();
        boolean ci = options.ci();
        ImmutableMap.Builder<String, Object> config = ImmutableMap.<String, Object>builder()
                .put("web_search", options.textOnly() || ci ? "disabled" : "live")
                .put("features", expectedFeatures(childProfile, options.textOnly(), options.review(), options.asksUser()))
                .put("shell_environment_policy", expectedShellEnvironmentPolicy())
                .put("project_doc_max_bytes", 0);
        if (options.review() && childProfile != ChildProfile.NONE) {
            // App Server applies hook trust to each thread, not to its process flags.
            config.put("bypass_hook_trust", true);
        }
        options.deepSeek().ifPresent(deepSeek -> config.put("model_provider", "deepseek")
                .put("model_catalog_json", deepSeek.catalog().toString())
                .put("model_providers", ImmutableMap.of("deepseek", deepSeekProviderConfig(deepSeek.baseUrl()))));
        if (ci) {
            config.put("approval_policy", "on-request")
                    .put("approvals_reviewer", "auto_review")
                    .put("sandbox_workspace_write", ciSandboxConfig(options.ciWritableRoots()));
        }
        else if (options.githubCommands() || options.review()) {
            config.put("sandbox_workspace_write", ImmutableMap.of("network_access", true));
        }
        if (!codeQueries) {
            config.put("mcp_servers", ImmutableMap.of());
        }
        config.put("agents", childProfile == ChildProfile.V1
                ? ImmutableMap.of("enabled", true, "max_depth", MULTI_AGENT_MAX_DEPTH)
                : ImmutableMap.of("enabled", childProfile != ChildProfile.NONE));
        return config.buildOrThrow();
    }

    /** The features a launch sets, which config/read must report back; the question feature is named only when it is on. */
    static Map<String, Boolean> expectedFeatures(ChildProfile childProfile, boolean textOnly, boolean review, boolean asksUser)
    {
        requireNonNull(childProfile, "childProfile is null");
        ImmutableMap.Builder<String, Boolean> features = ImmutableMap.builder();
        features.put("skip_host_skill_discovery", true);
        features.put("guardian_approval", !textOnly);
        if (asksUser) {
            features.put(ASK_USER_FEATURE, true);
        }
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
        return ImmutableMap.of("writable_roots", ImmutableList.copyOf(writableRoots), "network_access", true, "exclude_tmpdir_env_var", true, "exclude_slash_tmp", true);
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

    /** What a launch needs to expose the managed tools: where the task MCP endpoint is and what to tell the model. */
    record ManagedToolsLaunch(URI endpoint, String instruction, List<String> toolNames, boolean bearerTokenInEnvironment, Optional<Path> reviewGate)
    {
        static final String TOKEN_VARIABLE = "BYTEQUAY_TOOL_TOKEN";

        ManagedToolsLaunch
        {
            requireNonNull(endpoint, "endpoint is null");
            requireNonNull(instruction, "instruction is null");
            toolNames = ImmutableList.copyOf(toolNames);
            requireNonNull(reviewGate, "reviewGate is null");
        }
    }

    /**
     * What one Codex launch may do. The process flags, the per-thread config and the isolation guard all read this one
     * value, so the guard checks exactly the profile that was launched.
     *
     * @param asksUser whether the agent may ask the user a question with choices, which turns on Codex's {@code request_user_input}
     */
    record Options(
            ChildProfile childProfile,
            boolean textOnly,
            List<String> ciWritableRoots,
            boolean githubCommands,
            boolean review,
            boolean asksUser,
            Optional<DeepSeekProfile> deepSeek)
    {
        Options
        {
            requireNonNull(childProfile, "childProfile is null");
            ciWritableRoots = ImmutableList.copyOf(ciWritableRoots);
            requireNonNull(deepSeek, "deepSeek is null");
            boolean ci = !ciWritableRoots.isEmpty();
            checkArgument(!githubCommands || (!textOnly && !ci && !review), "Restricted Codex profiles cannot use attended network scope");
            checkArgument(!review || (!textOnly && !ci), "Restricted Codex profiles cannot run private review");
        }

        boolean ci()
        {
            return !ciWritableRoots.isEmpty();
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
