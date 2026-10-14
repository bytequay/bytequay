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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCodexLaunchProfile
{
    @TempDir
    private Path temporary;

    @Test
    void sharesInteractiveCodexPermissionsWithDeepSeek()
            throws Exception
    {
        Path catalog = temporary.resolve("home/deepseek-models.json");
        DeepSeekModelCatalog.prepare(catalog);
        DeepSeekModelCatalog.verify(catalog);
        JsonNode models = new ObjectMapper().readTree(Files.readString(catalog)).path("models");
        assertThat(models.size()).isEqualTo(2);
        for (JsonNode model : models) {
            assertThat(model.path("slug").asText()).isNotBlank();
            assertThat(model.path("auto_review_model_override").asText()).isEqualTo(model.path("slug").asText());
        }
        URI proxy = URI.create("http://127.0.0.1:12345/fixture/");
        CodexLaunchProfile.DeepSeekProfile deepSeek = new CodexLaunchProfile.DeepSeekProfile(catalog, proxy);
        List<String> arguments = CodexLaunchProfile.arguments(
                "codex", CodexLaunchProfile.ChildProfile.V1, Optional.empty(), false, ImmutableList.of(), true, false, Optional.of(deepSeek));
        List<String> codexArguments = CodexLaunchProfile.arguments("codex", CodexLaunchProfile.ChildProfile.V1, Optional.empty(), false, ImmutableList.of(), true, false);
        assertThat(arguments).containsSequence("-c", "model_provider=\"deepseek\"");
        assertThat(arguments).containsSequence("-c", "model_providers.deepseek.base_url=\"" + proxy + "\"");
        assertThat(arguments).containsSequence("-c", "model_providers.deepseek.request_max_retries=0");
        assertThat(arguments.subList(arguments.indexOf("web_search=\"live\"") - 1, arguments.size()))
                .containsExactlyElementsOf(codexArguments.subList(codexArguments.indexOf("web_search=\"live\"") - 1, codexArguments.size()));
        assertThat(arguments).noneMatch(value -> value.contains("DEEPSEEK_API_KEY") || value.contains("sk-secret")
                || value.contains("experimental_bearer_token") || value.contains("chatgpt") || value.contains("default_permissions"));
        assertThat(CodexLaunchProfile.expectedShellEnvironmentIncludeOnly())
                .doesNotContain("DEEPSEEK_API_KEY", "OPENAI_API_KEY", "CODEX_HOME");
        var threadConfig = CodexLaunchProfile.threadConfig(CodexLaunchProfile.ChildProfile.V1, false, false, ImmutableList.of(), true, false, Optional.of(deepSeek));
        var codexThreadConfig = CodexLaunchProfile.threadConfig(CodexLaunchProfile.ChildProfile.V1, false, false, ImmutableList.of(), true, false);
        assertThat(threadConfig)
                .containsEntry("model_provider", "deepseek")
                .containsEntry("model_catalog_json", catalog.toString())
                .containsAllEntriesOf(codexThreadConfig)
                .hasSize(codexThreadConfig.size() + 3)
                .doesNotContainKeys("default_permissions", "permissions");
        assertThatThrownBy(() -> {
            Files.writeString(catalog, "{}");
            DeepSeekModelCatalog.verify(catalog);
        }).hasMessageContaining("does not match ByteQuay");
        Files.writeString(catalog.getParent().resolve("config.toml"), "web_search = \"live\"\n");
        DeepSeekModelCatalog.prepare(catalog);
        DeepSeekModelCatalog.verify(catalog);
        Path linkedHome = temporary.resolve("linked-home");
        assertThatThrownBy(() -> {
            Files.createSymbolicLink(linkedHome, catalog.getParent());
            DeepSeekModelCatalog.verifyHome(linkedHome);
        }).hasMessageContaining("cannot use a symbolic link");
    }

    @Test
    void acceptsCodexProjectTrustAndOtherUserConfiguration()
            throws Exception
    {
        Path catalog = temporary.resolve("home/deepseek-models.json");
        DeepSeekModelCatalog.prepare(catalog);
        Path config = catalog.getParent().resolve("config.toml");
        String trustedProjects = "[projects.\"" + temporary.resolve("repository") + "\"]\n"
                + "trust_level = \"trusted\"\n\n"
                + "[projects.\"" + temporary.resolve("other-repository") + "\"]\n"
                + "trust_level = \"trusted\"\n";
        Files.writeString(config, trustedProjects);

        DeepSeekModelCatalog.verifyHome(catalog.getParent());
        DeepSeekModelCatalog.prepare(catalog);
        DeepSeekModelCatalog.verify(catalog);

        Files.writeString(config, trustedProjects + "web_search = \"live\"\n");
        DeepSeekModelCatalog.verifyHome(catalog.getParent());
        DeepSeekModelCatalog.prepare(catalog);
        DeepSeekModelCatalog.verify(catalog);
    }

    @Test
    void givesActionableDeepSeekTurnErrorsWithoutEchoingProviderDetails()
    {
        assertThat(CodexCliDriver.deepSeekFailure("HTTP 401: invalid api key sk-secret"))
                .contains("rejected this saved API key")
                .doesNotContain("sk-secret");
        assertThat(CodexCliDriver.deepSeekFailure("HTTP 402: insufficient_balance"))
                .contains("insufficient balance")
                .contains("explicitly retry");
        assertThat(CodexCliDriver.deepSeekFailure("model_not_found: deepseek-old"))
                .contains("selected model")
                .doesNotContain("deepseek-old");
    }

    @Test
    void keepsTaskMcpBearerOutOfCodexArguments()
    {
        CodexLaunchProfile.DeepSeekProfile profile = new CodexLaunchProfile.DeepSeekProfile(
                temporary.resolve("deepseek-models.json"), URI.create("http://127.0.0.1:12345/responses/"));
        CodexLaunchProfile.ManagedToolsLaunch tools = new CodexLaunchProfile.ManagedToolsLaunch(
                URI.create("http://127.0.0.1:12346/task/mcp"), "Task tools", ImmutableList.of("development_result_report"), false);
        List<String> arguments = CodexLaunchProfile.arguments("codex", CodexLaunchProfile.ChildProfile.NONE,
                Optional.of(tools), false, ImmutableList.of(), false, false, Optional.of(profile));
        assertThat(arguments).containsSequence("-c", "mcp_servers.bytequay.url=\"" + tools.endpoint() + "\"");
        assertThat(arguments).contains("mcp_servers.bytequay.tools={\"development_result_report\"={approval_mode=\"approve\"}}");
        assertThat(arguments).noneMatch(argument -> argument.contains("bearer_token_env_var") || argument.contains("BYTEQUAY_TOOL_TOKEN"));
    }

    @Test
    void sharesCiCodexPermissionsWithDeepSeek()
    {
        List<String> roots = ImmutableList.of("/task", "/repo/.git/worktrees/task", "/repo/.git/refs/heads/task.lock");
        CodexLaunchProfile.DeepSeekProfile profile = new CodexLaunchProfile.DeepSeekProfile(
                temporary.resolve("deepseek-models.json"), URI.create("http://127.0.0.1:12345/responses/"));
        List<String> arguments = CodexLaunchProfile.arguments("codex", CodexLaunchProfile.ChildProfile.NONE, Optional.empty(), false, roots, false, false, Optional.of(profile));
        List<String> codexArguments = CodexLaunchProfile.arguments("codex", CodexLaunchProfile.ChildProfile.NONE, Optional.empty(), false, roots, false, false);
        assertThat(arguments).containsSequence("-c", "approval_policy=\"on-request\"");
        assertThat(arguments.subList(arguments.indexOf("web_search=\"disabled\"") - 1, arguments.size()))
                .containsExactlyElementsOf(codexArguments.subList(codexArguments.indexOf("web_search=\"disabled\"") - 1, codexArguments.size()));
        var codexThreadConfig = CodexLaunchProfile.threadConfig(CodexLaunchProfile.ChildProfile.NONE, false, false, roots, false, false);
        assertThat(CodexLaunchProfile.threadConfig(CodexLaunchProfile.ChildProfile.NONE, false, false, roots, false, false, Optional.of(profile)))
                .containsEntry("approval_policy", "on-request")
                .containsAllEntriesOf(codexThreadConfig)
                .hasSize(codexThreadConfig.size() + 3)
                .doesNotContainKeys("default_permissions", "permissions");
    }

    @Test
    void isolatesCatalogCapabilitiesWithoutSubagents()
    {
        List<String> arguments = CodexLaunchProfile.arguments(
                "/usr/local/bin/codex", CodexLaunchProfile.ChildProfile.NONE, Optional.empty(), false, ImmutableList.of(), false, false);

        assertCommonIsolation(arguments);
        assertThat(arguments).containsSequence("--disable", "multi_agent");
        assertThat(arguments).containsSequence("--disable", "multi_agent_v2");
        assertThat(arguments).doesNotContain("agents.max_depth=2");
    }

    @Test
    void enablesOnlyV1AtRecursiveDepthTwo()
    {
        List<String> arguments = CodexLaunchProfile.arguments(
                "/usr/local/bin/codex", CodexLaunchProfile.ChildProfile.V1, Optional.empty(), false, ImmutableList.of(), false, false);

        assertCommonIsolation(arguments);
        assertThat(arguments).containsSequence("--enable", "multi_agent");
        assertThat(arguments).containsSequence("--disable", "multi_agent_v2");
        assertThat(arguments).containsSequence("-c", "agents.max_depth=2");
    }

    @Test
    void enablesOnlyV2WithoutAV1DepthSetting()
    {
        List<String> arguments = CodexLaunchProfile.arguments(
                "/usr/local/bin/codex", CodexLaunchProfile.ChildProfile.V2, Optional.empty(), false, ImmutableList.of(), false, false);

        assertCommonIsolation(arguments);
        assertThat(arguments).containsSequence("--disable", "multi_agent");
        assertThat(arguments).containsSequence("--enable", "multi_agent_v2");
        assertThat(arguments).doesNotContain("agents.max_depth=2");
    }

    @Test
    void disablesAndReportsEveryExecutionFeatureForTextOnlyRequests()
    {
        List<String> arguments = CodexLaunchProfile.arguments(
                "/usr/local/bin/codex", CodexLaunchProfile.ChildProfile.NONE, Optional.empty(), true, ImmutableList.of(), false, false);

        assertThat(arguments).containsSequence("-c", "web_search=\"disabled\"");
        assertThat(arguments).containsSequence("-c", "approvals_reviewer=\"auto_review\"");
        assertThat(arguments).containsSequence("--disable", "guardian_approval");
        assertThat(CodexLaunchProfile.expectedFeatures(CodexLaunchProfile.ChildProfile.NONE, true))
                .containsEntry("guardian_approval", false);
        assertThat(CodexLaunchProfile.threadConfig(CodexLaunchProfile.ChildProfile.NONE, false, true, ImmutableList.of(), false, false))
                .containsEntry("web_search", "disabled");
        for (String feature : List.of("code_mode", "js_repl", "shell_snapshot", "shell_tool", "unified_exec")) {
            assertThat(arguments).containsSequence("--disable", feature);
            assertThat(CodexLaunchProfile.expectedFeatures(CodexLaunchProfile.ChildProfile.NONE, true))
                    .containsEntry(feature, false);
        }
    }

    @Test
    void pinsAgentEnablementAtProcessAndResumedThreadScope()
    {
        for (CodexLaunchProfile.ChildProfile childProfile : CodexLaunchProfile.ChildProfile.values()) {
            boolean enabled = childProfile != CodexLaunchProfile.ChildProfile.NONE;
            assertThat(CodexLaunchProfile.arguments("codex", childProfile, Optional.empty(), false, ImmutableList.of(), false, false))
                    .containsSequence("-c", "agents.enabled=" + enabled);
            assertThat(CodexLaunchProfile.threadConfig(childProfile, false, false, ImmutableList.of(), false, false))
                    .containsEntry("agents", childProfile == CodexLaunchProfile.ChildProfile.V1
                            ? ImmutableMap.of("enabled", true, "max_depth", 2)
                            : ImmutableMap.of("enabled", enabled));
        }
    }

    @Test
    void enablesLiveWebForEveryTaskThreadProfile()
    {
        for (CodexLaunchProfile.ChildProfile childProfile : CodexLaunchProfile.ChildProfile.values()) {
            assertThat(CodexLaunchProfile.threadConfig(childProfile, false, false, ImmutableList.of(), false, false))
                    .containsEntry("web_search", "live");
            assertThat(CodexLaunchProfile.threadConfig(childProfile, true, false, ImmutableList.of(), false, false))
                    .containsEntry("web_search", "live");
        }
    }

    @Test
    void disablesNativeMemoriesAtProcessAndResumedThreadScope()
    {
        CodexLaunchProfile.ManagedToolsLaunch launch = new CodexLaunchProfile.ManagedToolsLaunch(
                URI.create("http://127.0.0.1:9000/mcp"), "Learn", ImmutableList.of("repository_context_read"));
        for (CodexLaunchProfile.ChildProfile childProfile : CodexLaunchProfile.ChildProfile.values()) {
            assertThat(CodexLaunchProfile.arguments("codex", childProfile, Optional.of(launch), false, ImmutableList.of(), false, false))
                    .containsSequence("--disable", "memories");
            assertThat(CodexLaunchProfile.expectedFeatures(childProfile, false)).containsEntry("memories", false).containsEntry("goals", false);
            assertThat(CodexLaunchProfile.threadConfig(childProfile, false, false, ImmutableList.of(), false, false))
                    .containsEntry("features", CodexLaunchProfile.expectedFeatures(childProfile, false));
            assertThat(CodexLaunchProfile.threadConfig(childProfile, true, false, ImmutableList.of(), false, false))
                    .containsEntry("features", CodexLaunchProfile.expectedFeatures(childProfile, false));
        }
    }

    @Test
    void approvesOnlyTheRegisteredTaskToolInventory()
    {
        List<String> tools = ImmutableList.of("repository_context_read", "repository_context_prepare");
        CodexLaunchProfile.ManagedToolsLaunch launch = new CodexLaunchProfile.ManagedToolsLaunch(URI.create("http://127.0.0.1:9000/mcp"), "Learn", tools);
        List<String> arguments = CodexLaunchProfile.arguments("codex", CodexLaunchProfile.ChildProfile.NONE, Optional.of(launch), false, ImmutableList.of(), false, false);
        assertThat(arguments).containsSequence("-c", "mcp_servers.bytequay.tools={"
                + "\"repository_context_read\"={approval_mode=\"approve\"},\"repository_context_prepare\"={approval_mode=\"approve\"}}");
        assertThat(arguments).contains("mcp_servers.bytequay.default_tools_approval_mode=\"approve\"");
        assertThat(arguments).contains("approval_policy=\"on-request\"");
        assertThat(CodexLaunchProfile.arguments("codex", CodexLaunchProfile.ChildProfile.NONE, Optional.empty(), false, ImmutableList.of(), false, false))
                .noneMatch(argument -> argument.startsWith("mcp_servers."));
    }

    @Test
    void toolNamesCannotEscapeTheTaskApprovalTable()
    {
        CodexLaunchProfile.ManagedToolsLaunch launch = new CodexLaunchProfile.ManagedToolsLaunch(
                URI.create("http://127.0.0.1:9000/mcp"), "Learn", ImmutableList.of("review_\".other\\tool"));
        assertThat(CodexLaunchProfile.arguments("codex", CodexLaunchProfile.ChildProfile.NONE, Optional.of(launch), false, ImmutableList.of(), false, false))
                .contains("mcp_servers.bytequay.tools={\"review_\\\".other\\\\tool\"={approval_mode=\"approve\"}}");
    }

    @Test
    void pinsUnattendedCiAtProcessAndThreadScopeForEveryChildProfile()
    {
        List<String> roots = ImmutableList.of("/task", "/repo/.git/worktrees/task", "/repo/.git/refs/heads/task.lock");
        for (CodexLaunchProfile.ChildProfile childProfile : CodexLaunchProfile.ChildProfile.values()) {
            List<String> arguments = CodexLaunchProfile.arguments("/usr/local/bin/codex", childProfile, Optional.empty(), false, roots, false, false);
            assertThat(arguments).containsSequence("-c", "approval_policy=\"on-request\"");
            assertThat(arguments).containsSequence("-c", "approvals_reviewer=\"auto_review\"");
            assertThat(arguments).containsSequence("-c", "web_search=\"disabled\"");
            assertThat(arguments).containsSequence("--enable", "guardian_approval");
            assertThat(arguments).containsSequence("-c", "sandbox_workspace_write.network_access=true");
            assertThat(arguments).containsSequence("-c", "sandbox_workspace_write.exclude_tmpdir_env_var=true");
            assertThat(arguments).containsSequence("-c", "sandbox_workspace_write.exclude_slash_tmp=true");
            assertThat(arguments).noneMatch(argument -> argument.startsWith("auto_review.policy="));
            assertThat(CodexLaunchProfile.threadConfig(childProfile, true, false, roots, false, false))
                    .containsEntry("approval_policy", "on-request")
                    .containsEntry("approvals_reviewer", "auto_review")
                    .containsEntry("web_search", "disabled")
                    .containsEntry("sandbox_workspace_write", CodexLaunchProfile.ciSandboxConfig(roots))
                    .containsEntry("project_doc_max_bytes", 0)
                    .doesNotContainKey("auto_review");
            assertThat(CodexLaunchProfile.expectedFeatures(childProfile, false)).containsEntry("guardian_approval", true);
        }
    }

    @Test
    void preservesPunctuationInsideWritablePaths()
    {
        List<String> roots = ImmutableList.of("/Work, Projects/task", "/repo/quote\"and\\slash");
        assertThat(CodexLaunchProfile.arguments("codex", CodexLaunchProfile.ChildProfile.NONE, Optional.empty(), false, roots, false, false))
                .contains("sandbox_workspace_write.writable_roots=[\"/Work, Projects/task\",\"/repo/quote\\\"and\\\\slash\"]");
    }

    @Test
    void keepsCredentialsOutOfTheInteractiveShell()
    {
        List<String> arguments = CodexLaunchProfile.arguments("codex", CodexLaunchProfile.ChildProfile.V1, Optional.empty(), false, ImmutableList.of(), true, false);
        assertThat(arguments).contains("shell_environment_policy.inherit=\"core\"", "shell_environment_policy.ignore_default_excludes=false");
        assertThat(arguments).containsSequence("-c", "sandbox_workspace_write.network_access=true");
        assertThat(CodexLaunchProfile.expectedShellEnvironmentIncludeOnly())
                .doesNotContain("GH_TOKEN", "GITHUB_TOKEN", "GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN", "GH_HOST", "GH_CONFIG_DIR", "XDG_CONFIG_HOME", "SSH_AUTH_SOCK",
                        "OPENAI_API_KEY", "CODEX_API_KEY", "ANTHROPIC_API_KEY", "CODEX_HOME", "BYTEQUAY_TOOL_TOKEN", "GH_REPO", "GIT_DIR");
        assertThat(CodexLaunchProfile.threadConfig(CodexLaunchProfile.ChildProfile.V1, false, false, ImmutableList.of(), true, false))
                .containsEntry("shell_environment_policy", CodexLaunchProfile.expectedShellEnvironmentPolicy());
        assertThat(CodexLaunchProfile.expectedShellEnvironmentIncludeOnly())
                .doesNotContain("GH_TOKEN", "GITHUB_TOKEN", "GH_CONFIG_DIR", "SSH_AUTH_SOCK", "XDG_CONFIG_HOME");
    }

    @Test
    void privateReviewKeepsCredentialsOutOfTheShell()
    {
        List<String> arguments = CodexLaunchProfile.arguments("codex", CodexLaunchProfile.ChildProfile.NONE, Optional.empty(), false, ImmutableList.of(), false, true);
        assertThat(arguments).containsSequence("-c", "approvals_reviewer=\"auto_review\"");
        assertThat(arguments).containsSequence("--enable", "guardian_approval");
        assertThat(arguments).contains("shell_environment_policy.inherit=\"core\"", "shell_environment_policy.ignore_default_excludes=false");
        assertThat(CodexLaunchProfile.expectedShellEnvironmentIncludeOnly())
                .doesNotContain("GH_HOST", "GH_CONFIG_DIR", "SSH_AUTH_SOCK", "XDG_CONFIG_HOME",
                        "GH_TOKEN", "GITHUB_TOKEN", "GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN",
                        "OPENAI_API_KEY", "CODEX_HOME", "BYTEQUAY_TOOL_TOKEN");
        assertThat(CodexLaunchProfile.threadConfig(CodexLaunchProfile.ChildProfile.NONE, false, false, ImmutableList.of(), false, true))
                .containsEntry("shell_environment_policy", CodexLaunchProfile.expectedShellEnvironmentPolicy())
                .containsEntry("features", CodexLaunchProfile.expectedFeatures(CodexLaunchProfile.ChildProfile.NONE, false, true));
        assertThat(CodexLaunchProfile.expectedFeatures(CodexLaunchProfile.ChildProfile.NONE, false, true))
                .containsEntry("guardian_approval", true);
    }

    private static void assertCommonIsolation(List<String> arguments)
    {
        assertThat(arguments).startsWith("/usr/local/bin/codex", "app-server", "--listen", "stdio://");
        // Launching without these would accept whatever the host installation defaults to.
        assertThat(arguments).contains("--strict-config");
        assertThat(arguments).containsSequence("--enable", "skip_host_skill_discovery");
        assertThat(arguments).containsSequence("--enable", "guardian_approval");
        assertThat(arguments).containsSequence("--disable", "guardianv2");
        assertThat(arguments).containsSequence("--disable", "guardian_ext");
        assertThat(arguments).containsSequence("-c", "web_search=\"live\"");
        // ByteQuay supplies the repository's guideline files itself, so Codex must not load AGENTS.md from the worktree.
        assertThat(arguments).containsSequence("-c", "project_doc_max_bytes=0");
        assertThat(arguments).contains("shell_environment_policy.include_only=[\"HOME\",\"LANG\",\"LC_ALL\",\"LOGNAME\"," + "\"PATH\",\"SHELL\",\"TERM\",\"TMPDIR\",\"USER\"]");
        for (String feature : ImmutableList.of("apps", "goals", "hooks", "memories", "plugins", "computer_use", "browser_use", "network_proxy", "skill_search")) {
            assertThat(arguments).containsSequence("--disable", feature);
        }
    }
}
