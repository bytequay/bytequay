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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnHooks;
import com.bytequay.app.service.agents.TurnResult;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.agents.TurnSpec;
import com.bytequay.app.service.review.CliReviewRunner;
import com.bytequay.app.service.review.ReviewProviderEndpoints;
import com.bytequay.app.service.settings.AiDefaultsService;
import com.bytequay.app.service.workmodel.SessionAudience;
import com.bytequay.app.service.workmodel.WorkspaceEngineSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.bytequay.app.repository.AppSettingsStore.Key.LLM_PROVIDER;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

/**
 * Repairs one conflicted cherry-pick. The agent reads the conflicted files and
 * the compile output and <em>proposes</em> find/replace edits; this class never
 * writes to the worktree — {@link UpstreamCherryPickService} applies what
 * survives validation and commits it as that pick's fixup.
 *
 * <p>The engine is the workspace's own pick for CI-fix work, falling back to the
 * account default (a CLI agent, so nothing bills an API key unless the user
 * asked for one). A CLI engine runs through {@link CliReviewRunner} and keeps
 * one session for the whole run, resumed by id, so a conflict late in a range
 * still knows what the fork decided about the ones before it.
 *
 * <p>Deliberately tool-free: the evidence a conflict needs — the conflicted
 * files as they stand and the compiler's complaint — is small enough to hand
 * over in the prompt, and a turn with no tools cannot wander. If a repair needs
 * to read a file nobody put in front of it, the attempt fails, the next attempt
 * gets the new compile output, and after the bound the run parks for a human.
 * (ponytail: add read-only tools here when a real conflict proves it needs them.)
 */
@Component
public class ConflictRepairAgent
        implements ConflictRepairAdvisor
{
    private static final Logger log = LoggerFactory.getLogger(ConflictRepairAgent.class);
    private static final int MAX_OUTPUT_TOKENS = 8_192;
    private static final int MAX_EDITS = 20;
    private static final int MAX_FILE_CHARS = 24_000;
    private static final int MAX_EVIDENCE_CHARS = 90_000;
    private static final int MAX_COMPILE_TAIL = 8_000;

    private final TurnRunner runner;
    private final ReviewProviderEndpoints endpoints;
    private final AppSettingsStore settings;
    private final ObjectMapper mapper;
    private final CliReviewRunner cli;
    private final WorkspaceEngineSettings engines;
    private final AiDefaultsService aiDefaults;

    public ConflictRepairAgent(
            TurnRunner runner,
            ReviewProviderEndpoints endpoints,
            AppSettingsStore settings,
            ObjectMapper mapper,
            CliReviewRunner cli,
            WorkspaceEngineSettings engines,
            AiDefaultsService aiDefaults)
    {
        this.runner = requireNonNull(runner, "runner is null");
        this.endpoints = requireNonNull(endpoints, "endpoints is null");
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.cli = requireNonNull(cli, "cli is null");
        this.engines = requireNonNull(engines, "engines is null");
        this.aiDefaults = requireNonNull(aiDefaults, "aiDefaults is null");
    }

    /**
     * The workspace's engine for CI-fix work, then the account default, then a
     * CLI agent. Never an API provider by accident: an unset preference used to
     * fall through to OpenAI and fail for want of a key nobody had asked for.
     */
    WorkModel engineFor(String workspaceId)
    {
        return engines.forAudience(workspaceId, SessionAudience.CI_FIX)
                .map(WorkspaceEngineSettings.Engine::model)
                .or(() -> WorkspaceEngineSettings.parseChoice(aiDefaults.get().ciFix()))
                .orElseGet(() -> new WorkModel(WorkModelKind.CLI, "codex", null, null));
    }

    static CliReviewRunner.Provider cliProvider(String agent)
    {
        return switch (agent) {
            case "claude-code", "claude-cli", "claude" -> CliReviewRunner.Provider.CLAUDE;
            case "codex", "codex-cli" -> CliReviewRunner.Provider.CODEX;
            default -> throw new IllegalStateException("unknown CLI agent: " + agent);
        };
    }

    @Override
    public Repair propose(
            Path worktree,
            String workspaceId,
            String targetSubject,
            List<String> conflictPaths,
            String compileOutput,
            long budgetMilliUsd,
            String resumeSessionId)
    {
        requireNonNull(worktree, "worktree is null");
        List<String> paths = conflictPaths == null ? List.of() : conflictPaths;
        WorkModel engine = engineFor(workspaceId);
        if (engine.kind() == WorkModelKind.CLI) {
            return proposeThroughCli(
                    engine, worktree, targetSubject, paths, compileOutput,
                    budgetMilliUsd, resumeSessionId);
        }
        return proposeThroughApi(
                engine, worktree, targetSubject, paths, compileOutput, budgetMilliUsd);
    }

    /**
     * The CLI lane: one subprocess per attempt, the run's session resumed by id
     * so the agent keeps everything it learned earlier in the range.
     */
    private Repair proposeThroughCli(
            WorkModel engine,
            Path worktree,
            String targetSubject,
            List<String> conflictPaths,
            String compileOutput,
            long budgetMilliUsd,
            String resumeSessionId)
    {
        CliReviewRunner.Provider provider = cliProvider(engine.agentOrProvider());
        // A resumed session already carries the rules; repeating them each turn
        // only crowds out the evidence.
        String prompt = (resumeSessionId == null ? systemPrompt() + "\n\n" : "")
                + userPrompt(worktree, targetSubject, conflictPaths, compileOutput);
        CliReviewRunner.Result result = cli.run(
                provider, prompt, resumeSessionId, worktree, null,
                toIntExact(Math.max(1, budgetMilliUsd / 10)));
        Repair parsed = parse(result.text(), result.costUsdMilli(), result.sessionId());
        return validated(parsed, worktree, conflictPaths);
    }

    private Repair proposeThroughApi(
            WorkModel engine,
            Path worktree,
            String targetSubject,
            List<String> conflictPaths,
            String compileOutput,
            long budgetMilliUsd)
    {
        List<String> paths = conflictPaths;
        String provider = engine.agentOrProvider() == null
                ? settings.get(LLM_PROVIDER).orElse("anthropic")
                : engine.agentOrProvider();
        ReviewProviderEndpoints.Endpoint endpoint = endpoints.resolve(provider);
        String system = systemPrompt();
        String prompt = userPrompt(worktree, targetSubject, paths, compileOutput);
        ArrayNode messages = mapper.createArrayNode();
        if (endpoint.transport() == TurnSpec.Transport.OPENAI_COMPAT) {
            messages.add(message("system", system));
        }
        messages.add(message("user", prompt));
        TurnHooks hooks = new TurnHooks()
        {
            @Override
            public boolean abortTurn(long costSoFarMilliUsd)
            {
                return costSoFarMilliUsd >= budgetMilliUsd;
            }
        };
        TurnResult result = runner.runTurn(
                new TurnSpec(
                        endpoint.transport(), endpoint.url(), endpoint.authToken(),
                        endpoint.modelId(),
                        endpoint.transport() == TurnSpec.Transport.ANTHROPIC ? system : null,
                        messages, mapper.createArrayNode(), MAX_OUTPUT_TOKENS, 1),
                refusingExecutor(),
                hooks);
        // The API lane is stateless: every turn re-sends the evidence, so there
        // is no session for the caller to resume.
        Repair repair = parse(result.finalText(), result.costMilliUsd(), null);
        return validated(repair, worktree, paths);
    }

    /** No tools are offered, so a tool call is a protocol error, not a request. */
    private static ToolExecutor refusingExecutor()
    {
        return (ToolCall call) -> ToolExecutor.ToolCallResult.error(
                "no tools are available; answer with the JSON object only");
    }

    Repair parse(String raw, long costMilliUsd, String sessionId)
    {
        JsonNode json;
        try {
            json = mapper.readTree(stripFence(raw));
        }
        catch (IOException e) {
            throw new IllegalArgumentException("conflict repair is not valid JSON", e);
        }
        if (json == null || !json.isObject()) {
            throw new IllegalArgumentException("conflict repair must be one JSON object");
        }
        String rationale = text(json, "rationale");
        if (json.path("needs_human").asBoolean(false)) {
            return new Repair(List.of(), rationale, costMilliUsd, sessionId);
        }
        JsonNode editNodes = json.path("edits");
        if (!editNodes.isArray() || editNodes.size() > MAX_EDITS) {
            throw new IllegalArgumentException(
                    "conflict repair edits must be an array of at most " + MAX_EDITS + " items");
        }
        List<Edit> edits = new ArrayList<>();
        for (JsonNode node : editNodes) {
            String path = text(node, "path");
            String find = text(node, "find");
            if (path.isBlank() || find.isBlank()) {
                throw new IllegalArgumentException("every edit needs a path and a find anchor");
            }
            edits.add(new Edit(path, find, node.path("replace").asText("")));
        }
        return new Repair(List.copyOf(edits), rationale, costMilliUsd, sessionId);
    }

    /**
     * The program's half of the contract. An edit is applied only if it names a
     * file this pick actually conflicted in or touched, its anchor appears
     * exactly once, and the replacement carries no conflict markers of its own.
     */
    Repair validated(Repair repair, Path worktree, List<String> conflictPaths)
    {
        if (repair.isEmpty()) {
            return repair;
        }
        Set<String> allowed = new LinkedHashSet<>(conflictPaths);
        Path root = worktree.toAbsolutePath().normalize();
        for (Edit edit : repair.edits()) {
            if (!allowed.contains(edit.path())) {
                throw new IllegalArgumentException(
                        "edit outside the conflicted files: " + edit.path());
            }
            Path file = root.resolve(edit.path()).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                throw new IllegalArgumentException("edit names a file that is not there: " + edit.path());
            }
            String content = read(file);
            int first = content.indexOf(edit.find());
            if (first < 0) {
                throw new IllegalArgumentException("edit anchor is not in " + edit.path());
            }
            if (content.indexOf(edit.find(), first + 1) >= 0) {
                throw new IllegalArgumentException("edit anchor is not unique in " + edit.path());
            }
            if (hasConflictMarkers(edit.replace())) {
                throw new IllegalArgumentException(
                        "replacement still carries conflict markers: " + edit.path());
            }
        }
        return repair;
    }

    static boolean hasConflictMarkers(String value)
    {
        return value != null
                && (value.contains("<<<<<<<") || value.contains(">>>>>>>") || value.contains("======="));
    }

    private String userPrompt(
            Path worktree, String targetSubject, List<String> conflictPaths, String compileOutput)
    {
        StringBuilder prompt = new StringBuilder(1_024);
        prompt.append("A cherry-pick from the upstream project conflicted on this fork.\n")
                .append("Git's own three-way resolution is already committed, so the files below")
                .append(" are exactly what is on disk right now — conflict markers and all.\n\n")
                .append("Cherry-picked commit: ").append(targetSubject).append('\n');
        if (compileOutput != null && !compileOutput.isBlank()) {
            prompt.append("\nThe module-scoped compile of that commit failed:\n<compile>\n")
                    .append(tail(compileOutput, MAX_COMPILE_TAIL))
                    .append("\n</compile>\n");
        }
        int budget = MAX_EVIDENCE_CHARS;
        for (String path : conflictPaths) {
            Path file = worktree.resolve(path);
            if (!Files.isRegularFile(file) || budget <= 0) {
                continue;
            }
            String content = read(file);
            String shown = content.length() <= Math.min(budget, MAX_FILE_CHARS)
                    ? content
                    : content.substring(0, Math.min(budget, MAX_FILE_CHARS)) + "\n… truncated\n";
            budget -= shown.length();
            prompt.append("\n<file path=\"").append(path).append("\">\n")
                    .append(shown).append("\n</file>\n");
        }
        prompt.append("\nPropose the edits that resolve this conflict the way this fork wants it.");
        return prompt.toString();
    }

    private static String systemPrompt()
    {
        return """
                You repair one conflicted cherry-pick in a fork that tracks an upstream project.

                You propose edits; a program applies, compiles and commits them. You never edit
                files yourself and you have no tools — answer from the evidence you were given.

                Rules:
                - Remove every conflict marker (<<<<<<<, =======, >>>>>>>) you touch.
                - Keep the fork's own behaviour where upstream did not intend to change it, and
                  keep upstream's change where it did. When those genuinely conflict, prefer the
                  fork's configuration names, bindings and defaults.
                - Do not reformat, do not fix unrelated code, do not delete tests.
                - Each edit's "find" must appear exactly once in that file. Include enough
                  surrounding context to make it unique.
                - If the evidence is not enough to be sure, say so instead of guessing.

                Answer with one JSON object and nothing else:
                {"edits":[{"path":"...","find":"...","replace":"..."}],
                 "rationale":"one or two sentences",
                 "needs_human":false}

                Set needs_human to true with an empty edits array when a human has to decide.
                """;
    }

    private String read(Path file)
    {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            log.warn("unable to read conflicted file {}: {}", file, e.getMessage());
            return "";
        }
    }

    private static String tail(String value, int max)
    {
        String stripped = value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(stripped.length() - max);
    }

    private ObjectNode message(String role, String content)
    {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    private static String text(JsonNode node, String field)
    {
        return node.path(field).asText("");
    }

    private static String stripFence(String raw)
    {
        String value = raw == null ? "" : raw.strip();
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            int closing = value.lastIndexOf("```");
            if (firstNewline >= 0 && closing > firstNewline) {
                return value.substring(firstNewline + 1, closing).strip();
            }
        }
        return value;
    }
}
