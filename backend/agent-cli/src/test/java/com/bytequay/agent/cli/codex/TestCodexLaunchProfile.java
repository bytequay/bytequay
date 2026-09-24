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
    void pinsDeepSeekProviderAndUserReviewedLocalTools()
    {
        Path catalog = temporary.resolve("home/deepseek-models.json");
        DeepSeekModelCatalog.prepare(catalog);
        DeepSeekModelCatalog.verify(catalog);
        URI proxy = URI.create("http://127.0.0.1:12345/fixture/");
        Path appData = temporary.resolve("Library/Application Support/ByteQuay");
        CodexLaunchProfile.DeepSeekProfile deepSeek = new CodexLaunchProfile.DeepSeekProfile(catalog, proxy, appData);
        List<String> arguments = CodexLaunchProfile.arguments(
                "codex", CodexLaunchProfile.ChildProfile.NONE, Optional.empty(), false, ImmutableList.of(), false, false, Optional.of(deepSeek));
        assertThat(arguments).containsSequence("-c", "model_provider=\"deepseek\"");
        assertThat(arguments).containsSequence("-c", "model_providers.deepseek.base_url=\"" + proxy + "\"");
        assertThat(arguments).containsSequence("-c", "approval_policy=\"on-request\"");
        assertThat(arguments).containsSequence("-c", "approvals_reviewer=\"user\"");
        assertThat(arguments).containsSequence("-c", "default_permissions=\"bytequay-deepseek\"");
        assertThat(arguments).containsSequence("-c", "permissions.bytequay-deepseek.extends=\":workspace\"");
        assertThat(arguments).containsSequence("-c", "permissions.bytequay-deepseek.network.enabled=false");
        assertThat(arguments).containsSequence("-c", "model_providers.deepseek.request_max_retries=0");
        assertThat(arguments).containsSequence("-c", "web_search=\"disabled\"");
        assertThat(arguments).containsSequence("--disable", "multi_agent");
        assertThat(arguments).containsSequence("--disable", "multi_agent_v2");
        assertThat(arguments).containsSequence("--disable", "guardian_approval");
        assertThat(arguments).noneMatch(value -> value.contains("DEEPSEEK_API_KEY") || value.contains("sk-secret")
                || value.contains("experimental_bearer_token") || value.contains("chatgpt")
                || value.contains("sandbox_workspace_write"));
        assertThat(CodexLaunchProfile.expectedShellEnvironmentIncludeOnly(false))
                .doesNotContain("DEEPSEEK_API_KEY", "OPENAI_API_KEY", "GH_TOKEN", "CODEX_HOME");
        assertThat(CodexLaunchProfile.threadConfig(
                CodexLaunchProfile.ChildProfile.NONE, false, false, ImmutableList.of(), false, false, Optional.of(deepSeek)))
                .containsEntry("model_provider", "deepseek")
                .containsEntry("model_catalog_json", catalog.toString())
                .containsEntry("approval_policy", "on-request")
                .containsEntry("approvals_reviewer", "user")
                .containsEntry("default_permissions", CodexLaunchProfile.DEEPSEEK_PERMISSIONS)
                .containsEntry("permissions", java.util.Map.of(CodexLaunchProfile.DEEPSEEK_PERMISSIONS,
                        CodexLaunchProfile.deepSeekPermissionsConfig(deepSeek)))
                .containsEntry("web_search", "disabled");
        assertThatThrownBy(() -> CodexLaunchProfile.arguments(
                "codex", CodexLaunchProfile.ChildProfile.V1, Optional.empty(), false, ImmutableList.of(), false, false, Optional.of(deepSeek)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> {
            Files.writeString(catalog, "{}");
            DeepSeekModelCatalog.verify(catalog);
        }).hasMessageContaining("does not match ByteQuay");
        assertThatThrownBy(() -> {
            Files.writeString(catalog.getParent().resolve("config.toml"), "web_search = \"live\"\n");
            DeepSeekModelCatalog.prepare(catalog);
        }).hasMessageContaining("unexpected user configuration");
        Path linkedHome = temporary.resolve("linked-home");
        assertThatThrownBy(() -> {
            Files.createSymbolicLink(linkedHome, catalog.getParent());
            DeepSeekModelCatalog.verifyHome(linkedHome);
        }).hasMessageContaining("cannot use a symbolic link");
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
        assertThat(arguments).containsSequence("--disable", "guardian_approval");
        assertThat(CodexLaunchProfile.threadConfig(CodexLaunchProfile.ChildProfile.NONE, false, true, ImmutableList.of(), false, false))
                .containsEntry("web_search", "disabled");
        for (String feature : List.of("code_mode", "js_repl", "shell_snapshot", "shell_tool", "unified_exec")) {
            assertThat(arguments).containsSequence("--disable", feature);
            assertThat(CodexLaunchProfile.expectedFeatures(CodexLaunchProfile.ChildProfile.NONE, true, false))
                    .containsEntry(feature, false);
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
    void pinsUnattendedCiAtProcessAndThreadScopeForEveryChildProfile()
    {
        List<String> roots = ImmutableList.of("/task", "/repo/.git/worktrees/task", "/repo/.git/refs/heads/task.lock");
        for (CodexLaunchProfile.ChildProfile childProfile : CodexLaunchProfile.ChildProfile.values()) {
            List<String> arguments = CodexLaunchProfile.arguments("/usr/local/bin/codex", childProfile, Optional.empty(), false, roots, false, false);
            assertThat(arguments).containsSequence("-c", "approval_policy=\"never\"");
            assertThat(arguments).containsSequence("-c", "approvals_reviewer=\"user\"");
            assertThat(arguments).containsSequence("-c", "web_search=\"disabled\"");
            assertThat(arguments).containsSequence("--disable", "guardian_approval");
            assertThat(arguments).containsSequence("-c", "sandbox_workspace_write.network_access=false");
            assertThat(arguments).containsSequence("-c", "sandbox_workspace_write.exclude_tmpdir_env_var=true");
            assertThat(arguments).containsSequence("-c", "sandbox_workspace_write.exclude_slash_tmp=true");
            assertThat(arguments).noneMatch(argument -> argument.startsWith("auto_review.policy="));
            assertThat(CodexLaunchProfile.threadConfig(childProfile, true, false, roots, false, false))
                    .containsEntry("approval_policy", "never")
                    .containsEntry("approvals_reviewer", "user")
                    .containsEntry("web_search", "disabled")
                    .containsEntry("sandbox_workspace_write", CodexLaunchProfile.ciSandboxConfig(roots))
                    .doesNotContainKey("auto_review");
            assertThat(CodexLaunchProfile.expectedFeatures(childProfile, false, true)).containsEntry("guardian_approval", false);
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
    void permitsOnlyNormalGitAuthenticationInTheInteractiveShell()
    {
        List<String> arguments = CodexLaunchProfile.arguments("codex", CodexLaunchProfile.ChildProfile.V1, Optional.empty(), false, ImmutableList.of(), true, false);
        assertThat(arguments).contains("shell_environment_policy.inherit=\"all\"", "shell_environment_policy.ignore_default_excludes=true");
        assertThat(CodexLaunchProfile.expectedShellEnvironmentIncludeOnly(true))
                .contains("GH_TOKEN", "GITHUB_TOKEN", "GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN", "GH_HOST", "GH_CONFIG_DIR", "XDG_CONFIG_HOME", "SSH_AUTH_SOCK")
                .doesNotContain("OPENAI_API_KEY", "CODEX_API_KEY", "ANTHROPIC_API_KEY", "CODEX_HOME", "BYTEQUAY_CODEGRAPH_TOKEN", "GH_REPO", "GIT_DIR");
        assertThat(CodexLaunchProfile.threadConfig(CodexLaunchProfile.ChildProfile.V1, false, false, ImmutableList.of(), true, false))
                .containsEntry("shell_environment_policy", CodexLaunchProfile.expectedShellEnvironmentPolicy(true));
        assertThat(CodexLaunchProfile.expectedShellEnvironmentIncludeOnly(false))
                .doesNotContain("GH_TOKEN", "GITHUB_TOKEN", "GH_CONFIG_DIR", "SSH_AUTH_SOCK", "XDG_CONFIG_HOME");
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
        assertThat(arguments).contains(
                "shell_environment_policy.include_only=[\"HOME\",\"LANG\",\"LC_ALL\",\"LOGNAME\","
                        + "\"PATH\",\"SHELL\",\"TERM\",\"TMPDIR\",\"USER\"]");
        for (String feature : List.of("apps", "hooks", "plugins", "computer_use", "browser_use", "skill_search")) {
            assertThat(arguments).containsSequence("--disable", feature);
        }
    }
}
