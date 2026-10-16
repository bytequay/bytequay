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

import com.bytequay.agent.spi.AgentUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * Proves that no inherited Codex capability applies to a task before its first turn.
 *
 * <p>A task's own App Server process partitions what that task can reach, but these capabilities
 * come from the host's Codex installation rather than from the process, so a per-task process does
 * not scope them. Safety still comes from proving they are absent. Every check fails closed: an
 * unexpected shape, a request failure, or anything that cannot be inspected makes the lane
 * unavailable rather than assumed safe.
 *
 * <p>This covers managed requirements, lifecycle hooks, ambient exec-policy rule sources, and skill
 * origin. The isolated subprocess environment and the child-capable launch profile are separate
 * launch concerns and are not adopted here.
 */
final class CodexIsolationGuard
{
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    /** A fresh App Server answers its first account/read only after its own ChatGPT requests return. */
    private static final Duration ACCOUNT_TIMEOUT = Duration.ofSeconds(60);
    private static final String REQUIREMENTS_FIELD = "requirements";

    private final CodexAppServerClient client;
    private final Path codexHome;
    private final CodexLaunchProfile.ChildProfile childProfile;
    private final Optional<URI> codeQueriesEndpoint;
    private final boolean textOnly;
    private final List<String> ciWritableRoots;
    private final boolean allowGitHubCommands;
    private final boolean review;
    private final Optional<CodexLaunchProfile.DeepSeekProfile> deepSeek;
    private boolean accountVerified;

    CodexIsolationGuard(
            CodexAppServerClient client,
            Path codexHome,
            CodexLaunchProfile.ChildProfile childProfile,
            Optional<URI> codeQueriesEndpoint,
            boolean textOnly,
            List<String> ciWritableRoots,
            boolean allowGitHubCommands,
            boolean review,
            Optional<CodexLaunchProfile.DeepSeekProfile> deepSeek)
    {
        this.client = requireNonNull(client, "client is null");
        this.codexHome = requireNonNull(codexHome, "codexHome is null").toAbsolutePath().normalize();
        this.childProfile = requireNonNull(childProfile, "childProfile is null");
        this.codeQueriesEndpoint = requireNonNull(codeQueriesEndpoint, "codeQueriesEndpoint is null");
        this.textOnly = textOnly;
        this.ciWritableRoots = ImmutableList.copyOf(ciWritableRoots);
        this.allowGitHubCommands = allowGitHubCommands;
        this.review = review;
        this.deepSeek = requireNonNull(deepSeek, "deepSeek is null");
    }

    /**
     * Verifies the guards that apply to one task working directory.
     *
     * @param cwd task worktree the turn will run against
     * @throws AgentUnavailableException when any capability cannot be proven absent
     */
    void verify(Path cwd)
    {
        requireNonNull(cwd, "cwd is null");
        verifyReportedCodexHome();
        verifyNoManagedRequirements();
        if (!accountVerified) {
            verifyChatGptAccount();
            // The login lives in the isolated Codex home, which no turn's sandbox can write, so it holds for the process.
            accountVerified = true;
        }
        verifyLifecycleHooks(cwd);
        verifyNoAmbientExecPolicyRules(cwd);
        verifySkillOrigin(cwd);
    }

    /**
     * Verifies the thread-scoped inventory after start or resume, before the session is usable. The checks of {@link #verify}
     * do not depend on the thread: the session runs them before opening it and again before each turn.
     */
    void verifyThread(String threadId)
    {
        requireNonNull(threadId, "threadId is null");
        verifyMcpServerStatus(threadId);
    }

    /**
     * Rejects any config layer that could carry an inherited exec-policy allow rule.
     *
     * <p>v0.151 exposes no option to disable {@code .rules} and no effective-rule inventory, so the
     * check is source-based: a project layer is rejected outright, and every policy-bearing system
     * or user source has its {@code rules} folder scanned. Packaged defaults, MDM, enterprise and
     * session-flag layers carry no folder to scan. An unrecognised layer type fails closed rather
     * than being assumed harmless.
     *
     * <p>The response is read raw because the layer source is a wire union whose unknown variants
     * must fail rather than decode to a default.
     */
    private void verifyNoAmbientExecPolicyRules(Path cwd)
    {
        JsonNode response;
        try {
            response = client.request("config/read", new CodexProtocol.ConfigReadParams(cwd.toString(), true), JsonNode.class, REQUEST_TIMEOUT);
        }
        catch (RuntimeException failure) {
            throw unavailable("config/read could not be verified: " + failure.getMessage());
        }
        JsonNode config = response.path("config");
        verifyLaunchPolicy(config, true);
        JsonNode layers = response.path("layers");
        if (!layers.isArray()) {
            throw unavailable("config/read omitted the requested layer details");
        }
        int userLayers = 0;
        int sessionFlagLayers = 0;
        for (JsonNode layer : layers) {
            JsonNode source = layer.path("name");
            if (source.path("type").asText("").equals("user")) {
                userLayers++;
                Path configFile = Path.of(requireText(source, "file", "user"));
                Path folder = Optional.ofNullable(configFile.getParent())
                        .orElseThrow(() -> unavailable("Codex user config layer has no parent folder"));
                if (!canonical(folder, "Codex user config folder").equals(canonical(codexHome, "isolated Codex home"))) {
                    throw unavailable("Codex loaded a user config outside the isolated home");
                }
            }
            if (source.path("type").asText("").equals("sessionFlags")) {
                sessionFlagLayers++;
                JsonNode disabledReason = layer.path("disabledReason");
                if (!disabledReason.isMissingNode() && !disabledReason.isNull()) {
                    throw unavailable("Codex disabled the session-flags layer");
                }
                verifyLaunchPolicy(layer.path("config"), false);
            }
            rulesFolder(source).ifPresent(CodexIsolationGuard::rejectRuleFiles);
        }
        if (userLayers != 1) {
            throw unavailable("config/read did not report exactly one isolated user layer");
        }
        if (sessionFlagLayers != 1) {
            throw unavailable("config/read did not report exactly one session-flags layer");
        }
    }

    /**
     * Returns the folder whose {@code rules} directory decides this layer, if it has one.
     *
     * @param source config layer source
     * @return folder to scan, or empty for a layer that cannot carry a rules folder
     */
    private static Optional<Path> rulesFolder(JsonNode source)
    {
        String type = source.path("type").asText("");
        return switch (type) {
            case "system", "user" -> Optional.of(Path.of(requireText(source, "file", type)).getParent());
            case "project" -> throw unavailable("Codex loaded a project config layer for the task worktree");
            case "packagedDefaults", "legacyManagedConfigTomlFromFile", "mdm", "enterpriseManaged", "sessionFlags", "legacyManagedConfigTomlFromMdm" -> Optional.empty();
            default -> throw unavailable("Codex reported an unsupported config layer source: " + type);
        };
    }

    /**
     * Refuses a config source that carries exec-policy rules.
     *
     * <p>Also run by the readiness probe against the user's Codex home, so a machine whose rules
     * would make every task refuse to start is reported before a task is created.
     */
    static void rejectRuleFiles(Path folder)
    {
        Path rules = folder.resolve("rules");
        try (Stream<Path> entries = Files.list(rules)) {
            if (entries.anyMatch(entry -> entry.getFileName().toString().endsWith(".rules"))) {
                throw unavailable("Codex exec-policy rules are present beside " + folder);
            }
        }
        catch (NoSuchFileException e) {
            // No rules folder beside this config source is the expected, clean case.
        }
        catch (IOException e) {
            throw unavailable("Unable to scan Codex exec-policy rules beside " + folder);
        }
    }

    /**
     * Requires every discovered skill to be a bundled system skill of the reported Codex home.
     *
     * <p>Discovery is forced rather than cached, because an earlier workspace-write turn could have
     * added a skill that a cached listing would not show.
     */
    private void verifyReportedCodexHome()
    {
        Path reported = client.codexHome()
                .map(Path::of)
                .orElseThrow(() -> unavailable("App Server reported no Codex home"));
        if (!canonical(reported, "reported Codex home").equals(canonical(codexHome, "isolated Codex home"))) {
            throw unavailable("App Server did not use the isolated Codex home");
        }
    }

    /** Verifies the effective config, or the launch layer that must produce that config. */
    private void verifyLaunchPolicy(JsonNode config, boolean effective)
    {
        if (!config.isObject()) {
            throw unavailable("config/read omitted the launch policy");
        }
        boolean ci = !ciWritableRoots.isEmpty();
        requireTextValue(config, "web_search", textOnly || ci ? "disabled" : "live");
        requireTextValue(config, "approval_policy", textOnly ? "never" : "on-request");
        requireTextValue(config, "approvals_reviewer", "auto_review");
        if (deepSeek.isPresent()) {
            requireTextValue(config, "cli_auth_credentials_store", "ephemeral");
            JsonNode forcedLogin = config.path("forced_login_method");
            if (!forcedLogin.isMissingNode() && !forcedLogin.isNull()) {
                throw unavailable("DeepSeek launch inherited an OpenAI login method");
            }
            requireTextValue(config, "model_provider", "deepseek");
            requireTextValue(config, "model_catalog_json", deepSeek.orElseThrow().catalog().toString());
            JsonNode provider = config.path("model_providers").path("deepseek");
            requireTextValue(provider, "name", "DeepSeek");
            requireTextValue(provider, "base_url", deepSeek.orElseThrow().baseUrl().toString());
            requireTextValue(provider, "wire_api", "responses");
            if (provider.hasNonNull("env_key") || provider.hasNonNull("experimental_bearer_token")) {
                throw unavailable("DeepSeek launch exposed provider credentials to Codex");
            }
            requireBooleanValue(provider, "requires_openai_auth", false);
            requireBooleanValue(provider, "supports_websockets", false);
            JsonNode retries = provider.path("request_max_retries");
            if (!retries.isIntegralNumber() || retries.asInt() != 0) {
                throw unavailable("DeepSeek provider could retry a failed request");
            }
            DeepSeekModelCatalog.verify(deepSeek.orElseThrow().catalog());
        }
        if (ci) {
            JsonNode sandbox = config.path("sandbox_workspace_write");
            JsonNode roots = sandbox.path("writable_roots");
            if (!roots.isArray() || roots.size() != ciWritableRoots.size()) {
                throw unavailable("Codex CI writable roots do not match the task's Git scope");
            }
            for (int index = 0; index < ciWritableRoots.size(); index++) {
                if (!roots.get(index).isTextual() || !roots.get(index).asText().equals(ciWritableRoots.get(index))) {
                    throw unavailable("Codex CI writable roots do not match the task's Git scope");
                }
            }
            for (String field : ImmutableList.of("network_access", "exclude_tmpdir_env_var", "exclude_slash_tmp")) {
                JsonNode value = sandbox.path(field);
                if (!value.isBoolean() || !value.asBoolean()) {
                    throw unavailable("Codex CI sandbox " + field + " does not match the launch profile");
                }
            }
        }
        else if (allowGitHubCommands || review) {
            requireBooleanValue(config.path("sandbox_workspace_write"), "network_access", true);
        }

        JsonNode features = config.path("features");
        if (!features.isObject()) {
            throw unavailable("config/read omitted the feature policy");
        }
        for (Map.Entry<String, Boolean> feature : CodexLaunchProfile.expectedFeatures(childProfile, textOnly, review).entrySet()) {
            JsonNode value = features.path(feature.getKey());
            if (feature.getKey().equals("network_proxy") && value.isObject()) {
                value = value.path("enabled");
            }
            if (!value.isBoolean() || value.asBoolean() != feature.getValue()) {
                throw unavailable("Codex feature " + feature.getKey() + " does not match the launch profile");
            }
        }
        requireBooleanValue(config.path("agents"), "enabled", childProfile != CodexLaunchProfile.ChildProfile.NONE);
        CodexLaunchProfile.expectedAgentMaxDepth(childProfile).ifPresent(expected -> {
            JsonNode depth = config.path("agents").path("max_depth");
            if (!depth.isIntegralNumber() || depth.asInt() != expected) {
                throw unavailable("Codex agent depth does not match the launch profile");
            }
        });

        JsonNode shell = config.path("shell_environment_policy");
        if (!shell.isObject()) {
            throw unavailable("config/read omitted the shell environment policy");
        }
        requireTextValue(shell, "inherit", "core");
        requireBooleanValue(shell, "ignore_default_excludes", false);
        requireBooleanValue(shell, "experimental_use_profile", false);
        JsonNode includeOnly = shell.path("include_only");
        if (!includeOnly.isArray()
                || includeOnly.size() != CodexLaunchProfile.expectedShellEnvironmentIncludeOnly().size()) {
            throw unavailable("Codex shell environment allowlist does not match the launch profile");
        }
        int index = 0;
        for (String expected : CodexLaunchProfile.expectedShellEnvironmentIncludeOnly()) {
            if (!includeOnly.get(index++).isTextual()
                    || !includeOnly.get(index - 1).asText().equals(expected)) {
                throw unavailable("Codex shell environment allowlist does not match the launch profile");
            }
        }
        if (effective) {
            requireNullValue(shell, "exclude");
            requireNullValue(shell, "set");
            requireNullValue(shell, "filters");
            verifyOnlyTheManagedToolsServer(config.path("mcp_servers"));
        }
    }

    private void verifyMcpServerStatus(String threadId)
    {
        int servers = 0;
        Optional<String> cursor = Optional.empty();
        Set<String> seenCursors = new HashSet<>();
        do {
            ImmutableMap.Builder<String, Object> params = ImmutableMap.<String, Object>builder()
                    .put("threadId", threadId)
                    .put("limit", 100)
                    .put("detail", "toolsAndAuthOnly");
            cursor.ifPresent(value -> params.put("cursor", value));
            JsonNode response;
            try {
                response = client.request("mcpServerStatus/list", params.buildOrThrow(), JsonNode.class, REQUEST_TIMEOUT);
            }
            catch (RuntimeException failure) {
                throw unavailable("mcpServerStatus/list could not be verified: " + failure.getMessage());
            }
            JsonNode data = response.path("data");
            if (!data.isArray()) {
                throw unavailable("mcpServerStatus/list omitted its data array");
            }
            for (JsonNode status : data) {
                if (codeQueriesEndpoint.isEmpty()) {
                    throw unavailable("thread inherited an MCP server");
                }
                if (++servers > 1 || !status.path("name").asText("").equals("bytequay")) {
                    throw unavailable("thread exposes an MCP server other than the ByteQuay task endpoint");
                }
            }
            JsonNode nextCursor = response.path("nextCursor");
            if (nextCursor.isMissingNode() || nextCursor.isNull()) {
                cursor = Optional.empty();
            }
            else if (nextCursor.isTextual() && !nextCursor.asText().isBlank()) {
                cursor = Optional.of(nextCursor.asText());
                if (!seenCursors.add(cursor.orElseThrow())) {
                    throw unavailable("mcpServerStatus/list repeated a page cursor");
                }
            }
            else {
                throw unavailable("mcpServerStatus/list returned an invalid page cursor");
            }
        }
        while (cursor.isPresent());
        if (codeQueriesEndpoint.isPresent() && servers != 1) {
            throw unavailable("thread does not expose exactly the ByteQuay task MCP endpoint");
        }
    }

    /** The effective MCP map holds nothing, or exactly ByteQuay's own task endpoint when this launch exposes the managed tools. */
    private void verifyOnlyTheManagedToolsServer(JsonNode servers)
    {
        if (codeQueriesEndpoint.isEmpty()) {
            if (!servers.isObject() || !servers.isEmpty()) {
                throw unavailable("config/read exposes MCP servers on an isolated launch");
            }
            return;
        }
        String expected = codeQueriesEndpoint.orElseThrow().toString();
        if (!servers.isObject() || servers.size() != 1 || !servers.path("bytequay").path("url").asText("").equals(expected)) {
            throw unavailable("config/read does not expose exactly the ByteQuay task MCP endpoint");
        }
        requireTextValue(servers.path("bytequay"), "default_tools_approval_mode", "approve");
        JsonNode bearer = servers.path("bytequay").path("bearer_token_env_var");
        if (deepSeek.isPresent() && !bearer.isMissingNode() && !bearer.isNull()) {
            throw unavailable("DeepSeek task tools exposed a bearer token to Codex");
        }
    }

    private static void requireTextValue(JsonNode object, String field, String expected)
    {
        JsonNode value = object.path(field);
        if (!value.isTextual() || !value.asText().equals(expected)) {
            throw unavailable("Codex " + field + " does not match the launch profile");
        }
    }

    private static void requireBooleanValue(JsonNode object, String field, boolean expected)
    {
        JsonNode value = object.path(field);
        if (!value.isBoolean() || value.asBoolean() != expected) {
            throw unavailable("Codex " + field + " does not match the launch profile");
        }
    }

    private static void requireNullValue(JsonNode object, String field)
    {
        if (!object.has(field) || !object.get(field).isNull()) {
            throw unavailable("Codex shell environment policy carries " + field);
        }
    }

    private static Path canonical(Path path, String description)
    {
        try {
            return path.toRealPath();
        }
        catch (IOException e) {
            throw unavailable("Unable to resolve " + description);
        }
    }

    private void verifySkillOrigin(Path cwd)
    {
        CodexProtocol.SkillsListResponse response = client.request(
                "skills/list",
                new CodexProtocol.SkillsListParams(ImmutableList.of(cwd.toString()), true),
                CodexProtocol.SkillsListResponse.class,
                REQUEST_TIMEOUT);
        CodexProtocol.SkillsListEntry entry = response.data().stream()
                .filter(candidate -> candidate.cwd().equals(cwd.toString()))
                .findFirst()
                .orElseThrow(() -> unavailable("skills/list reported nothing for the task worktree"));
        if (!entry.errors().isEmpty()) {
            throw unavailable("Codex skill discovery failed for the task worktree");
        }
        Path systemRoot = client.codexHome()
                .map(home -> Path.of(home).resolve("skills").resolve(".system"))
                .orElseThrow(() -> unavailable("App Server reported no Codex home to anchor skill origin"));
        for (CodexProtocol.SkillMetadata skill : entry.skills()) {
            if (!skill.scope().equals("system") || skill.pluginId().isPresent()) {
                throw unavailable("Codex skill " + skill.name() + " is not a bundled system skill");
            }
            if (!Path.of(skill.path()).normalize().startsWith(systemRoot.normalize())) {
                throw unavailable("Codex skill " + skill.name() + " is outside the bundled system skill root");
            }
        }
    }

    private static String requireText(JsonNode source, String field, String type)
    {
        JsonNode value = source.path(field);
        if (!value.isTextual()) {
            throw unavailable("Codex " + type + " config layer lacks its " + field);
        }
        return value.asText();
    }

    /**
     * Requires managed policy to report exactly one explicitly null requirements field.
     *
     * <p>This reads the raw response on purpose. The rule is about unknown siblings, and decoding
     * into a record would silently ignore exactly the field that should fail the check, because the
     * codec tolerates additive properties everywhere else.
     */
    private void verifyNoManagedRequirements()
    {
        JsonNode response = request("configRequirements/read", REQUEST_TIMEOUT);
        if (!response.isObject()) {
            throw unavailable("configRequirements/read returned no policy object");
        }
        Iterator<String> fields = response.fieldNames();
        if (!fields.hasNext() || !fields.next().equals(REQUIREMENTS_FIELD) || fields.hasNext()) {
            throw unavailable("configRequirements/read returned an unknown managed-policy field");
        }
        if (!response.get(REQUIREMENTS_FIELD).isNull()) {
            throw unavailable("Codex managed requirements apply to this host");
        }
    }

    private void verifyChatGptAccount()
    {
        JsonNode response = request("account/read", ACCOUNT_TIMEOUT);
        if (!response.path("requiresOpenaiAuth").asBoolean(false)
                || !response.path("account").path("type").asText().equals("chatgpt")) {
            throw unavailable("Codex must use the isolated ChatGPT account");
        }
    }

    private void verifyLifecycleHooks(Path cwd)
    {
        CodexProtocol.HooksListResponse response = client.request(
                "hooks/list",
                new CodexProtocol.CwdsParams(ImmutableList.of(cwd.toString())),
                CodexProtocol.HooksListResponse.class,
                REQUEST_TIMEOUT);
        CodexProtocol.HooksListEntry entry = response.data().stream()
                .filter(candidate -> candidate.cwd().equals(cwd.toString()))
                .findFirst()
                .orElseThrow(() -> unavailable("hooks/list reported nothing for the task worktree"));
        if (!entry.errors().isEmpty()) {
            throw unavailable("Codex hook discovery failed for the task worktree");
        }
        if (review && childProfile != CodexLaunchProfile.ChildProfile.NONE) {
            CodexReviewGate.verify(codexHome);
            if (!entry.warnings().isEmpty() || entry.hooks().size() != 1) {
                throw unavailable("Codex review child gate is not the only lifecycle hook");
            }
            JsonNode hook = entry.hooks().getFirst();
            if (!hook.path("eventName").asText("").equals("preToolUse")
                    || !hook.path("handlerType").asText("").equals("command")
                    || !hook.path("command").asText("").equals(CodexReviewGate.command(CodexReviewGate.path(codexHome)))
                    || !hook.path("matcher").asText("").equals(".*")
                    || !hook.path("source").asText("").equals("sessionFlags")
                    || !hook.path("sourcePath").asText("").equals("/<session-flags>/config.toml")
                    || !hook.path("enabled").asBoolean(false)
                    || hook.path("async").asBoolean(true)
                    || hook.path("timeoutSec").asInt() != 5) {
                throw unavailable("Codex review child gate does not match the launch profile");
            }
            return;
        }
        if (!entry.hooks().isEmpty()) {
            throw unavailable("Codex lifecycle hooks apply to the task worktree");
        }
    }

    private JsonNode request(String method, Duration timeout)
    {
        try {
            return client.request(method, new CodexProtocol.EmptyParams(), JsonNode.class, timeout);
        }
        catch (RuntimeException failure) {
            throw unavailable(method + " could not be verified: " + failure.getMessage());
        }
    }

    private static AgentUnavailableException unavailable(String message)
    {
        return new AgentUnavailableException("Codex isolation check failed: " + message);
    }
}
