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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * Proves that no inherited Codex capability applies to a task before its first turn.
 *
 * <p>One App Server process serves every task, so process-wide configuration is shared rather than
 * partitioned. Safety therefore comes from proving these capabilities are absent, not from scoping
 * them per task. Every check fails closed: an unexpected shape, a request failure, or anything that
 * cannot be inspected makes the lane unavailable rather than assumed safe.
 *
 * <p>This covers managed requirements, lifecycle hooks, ambient exec-policy rule sources, and skill
 * origin. The isolated subprocess environment and the child-capable launch profile are separate
 * launch concerns and are not adopted here.
 */
final class CodexIsolationGuard
{
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final String REQUIREMENTS_FIELD = "requirements";

    private final CodexAppServerClient client;

    CodexIsolationGuard(CodexAppServerClient client)
    {
        this.client = requireNonNull(client, "client is null");
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
        verifyNoManagedRequirements();
        verifyNoLifecycleHooks(cwd);
        verifyNoAmbientExecPolicyRules(cwd);
        verifySkillOrigin(cwd);
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
            response = client.request(
                    "config/read",
                    new CodexProtocol.ConfigReadParams(cwd.toString(), true),
                    JsonNode.class,
                    REQUEST_TIMEOUT);
        }
        catch (RuntimeException failure) {
            throw unavailable("config/read could not be verified: " + failure.getMessage());
        }
        JsonNode layers = response.path("layers");
        if (!layers.isArray()) {
            throw unavailable("config/read omitted the requested layer details");
        }
        for (JsonNode layer : layers) {
            rulesFolder(layer.path("name")).ifPresent(CodexIsolationGuard::rejectRuleFiles);
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
            case "packagedDefaults", "legacyManagedConfigTomlFromFile", "mdm",
                    "enterpriseManaged", "sessionFlags", "legacyManagedConfigTomlFromMdm" -> Optional.empty();
            default -> throw unavailable("Codex reported an unsupported config layer source: " + type);
        };
    }

    private static void rejectRuleFiles(Path folder)
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
        JsonNode response = request("configRequirements/read");
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

    private void verifyNoLifecycleHooks(Path cwd)
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
        if (!entry.hooks().isEmpty()) {
            throw unavailable("Codex lifecycle hooks apply to the task worktree");
        }
    }

    private JsonNode request(String method)
    {
        try {
            return client.request(method, new CodexProtocol.EmptyParams(), JsonNode.class, REQUEST_TIMEOUT);
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
