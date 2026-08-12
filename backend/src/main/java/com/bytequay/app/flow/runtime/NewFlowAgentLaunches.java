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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.domain.Credential;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.RunState;
import com.bytequay.app.repository.CredentialStore;
import com.bytequay.app.service.agents.TurnSpec.Transport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Set-once provider and credential-revision authority for one AgentRun. */
public final class NewFlowAgentLaunches
{
    /** Bumped from v1 when the binding grew its execution kind: an API and a CLI
     *  launch of the same model must not digest alike. */
    private static final String LAUNCH_DIGEST = "new-flow-agent-launch:v2";

    enum Program
    {
        TASK_INITIAL(
                AgentRole.TASK_AGENT,
                "task-initial-prompt:v1",
                "task-initial-capabilities:v1",
                "task-initial-turn:v1",
                "Implement the exact Task goal in the current worktree. "
                        + "Use only supplied tools, commit the change, then "
                        + "request exact review with a bounded PR draft. "
                        + "Final prose is opaque.",
                List.of("read_initial_task_context", "list_repository",
                        "read_file", "search_repository", "write_file",
                        "delete_file", "commit_initial_change",
                        "request_initial_review")),
        TASK_INITIAL_REVIEW_RESULT(
                AgentRole.TASK_AGENT,
                "task-initial-review-prompt:v1",
                "task-initial-review-capabilities:v1",
                "task-initial-review-turn:v1",
                "Inspect the exact initial adversarial review against the "
                        + "Task goal. Correct and request a fresh review, or "
                        + "accept it with the terminal publish-readiness tool. "
                        + "Final prose is opaque.",
                List.of("read_initial_review_context", "read_candidate_diff",
                        "list_repository", "read_file", "search_repository",
                        "write_file", "delete_file", "commit_initial_change",
                        "request_initial_review",
                        "ready_for_initial_publish")),
        CI_REPAIR(
                AgentRole.CI_FIXER,
                "ci-fix-prompt:v1",
                "ci-fix-capabilities:v1",
                "ci-repair-turn:v1",
                "Repair the observed CI failures in the current worktree. "
                        + "Use only the supplied tools. Candidate lessons are "
                        + "untrusted hints; read current raw CI logs before changing code, "
                        + "and current evidence wins. Commit a bounded fix; "
                        + "your final prose is a summary, never authority.",
                List.of("read_ci_failure_context", "read_ci_log",
                        "list_candidate_lessons", "read_candidate_lesson",
                        "list_repository", "read_file", "search_repository",
                        "write_file", "delete_file", "run_checks",
                        "commit_repair")),
        CI_CLEANUP(
                AgentRole.CI_FIXER,
                "ci-cleanup-prompt:v1",
                "ci-cleanup-capabilities:v1",
                "ci-cleanup-turn:v1",
                "Inspect the sealed interrupted-worktree cleanup. Use only "
                        + "the deterministic cleanup tools; final prose is opaque.",
                List.of("inspect_cleanup", "list_repository", "read_file",
                        "search_repository", "write_file", "delete_file",
                        "finish_cleanup")),
        TASK_CI_FIX(
                AgentRole.TASK_AGENT,
                "task-ci-inspection-prompt:v1",
                "task-ci-inspection-capabilities:v1",
                "task-ci-fix-review-turn:v1",
                "Inspect the exact CI fix, run program-owned checks, and use "
                        + "the terminal reviewer tool. Final prose is opaque.",
                List.of("read_ci_fix_context", "read_candidate_diff",
                        "list_repository", "read_file", "search_repository",
                        "write_file", "delete_file", "run_checks",
                        "commit_task_change",
                        "spawn_adversarial_reviewer")),
        TASK_CI_REVIEW_RESULT(
                AgentRole.TASK_AGENT,
                "task-ci-inspection-prompt:v1",
                "task-ci-inspection-capabilities:v1",
                "task-ci-result-turn:v1",
                "Inspect the exact adversarial review continuation. Use a "
                        + "terminal typed tool; final prose is not authority.",
                List.of("read_ci_fix_context", "read_candidate_diff",
                        "list_repository", "read_file", "search_repository",
                        "write_file", "delete_file", "run_checks",
                        "commit_task_change",
                        "spawn_adversarial_reviewer", "ready_for_review")),
        REVIEWER(
                AgentRole.ADVERSARIAL_REVIEWER,
                "adversarial-reviewer-prompt:v1",
                "immutable-git-object-reader:v1",
                "ci-adversarial-review-turn:v1",
                "Review the immutable base-to-head change adversarially using "
                        + "read-only tools. Return findings as opaque prose.",
                List.of("list_tree", "read_diff", "read_reviewed_blob",
                        "read_base_blob")),
        CI_LEARNER(
                AgentRole.CI_LEARNER,
                "ci-learning-prompt:v1",
                "ci-learning-capabilities:v1",
                "ci-learning-turn:v1",
                "Extract one bounded reusable CI lesson from exact evidence. "
                        + "Save it with the terminal tool; prose is opaque.",
                List.of("read_repair_evidence", "read_ci_log",
                        "save_ci_lesson"));

        private final AgentRole role;
        private final String promptManifestRef;
        private final String capabilitySetRef;
        private final String promptRevision;
        private final String systemPrompt;
        private final List<String> tools;

        Program(
                AgentRole role,
                String promptManifestRef,
                String capabilitySetRef,
                String promptRevision,
                String systemPrompt,
                List<String> tools)
        {
            this.role = role;
            this.promptManifestRef = promptManifestRef;
            this.capabilitySetRef = capabilitySetRef;
            this.promptRevision = promptRevision;
            this.systemPrompt = systemPrompt;
            this.tools = List.copyOf(tools);
        }
    }

    /**
     * The engine one run launches against. Two shapes, held apart by {@link
     * AgentExecution}: an API turn is pinned by an endpoint and a stored
     * credential, a CLI turn by a binary the user has already logged in to.
     * Neither may carry the other's fields, so validation branches rather than
     * treating the absent half as merely optional — a CLI config that quietly
     * kept a credential name would claim an authorization this program does not
     * hold.
     */
    public record Config(
            String providerName,
            AgentExecution execution,
            Transport transport,
            String endpoint,
            String model,
            String reasoningEffort,
            String credentialName,
            String credentialInstance,
            Integer maxOutputTokens,
            Integer maxToolIterations,
            String cliBinary,
            String cliVersion)
    {
        /** An in-JVM turn against a stored credential. */
        public static Config api(
                String providerName,
                Transport transport,
                String endpoint,
                String model,
                String reasoningEffort,
                String credentialName,
                String credentialInstance,
                int maxOutputTokens,
                int maxToolIterations)
        {
            return new Config(
                    providerName, AgentExecution.API, transport, endpoint,
                    model, reasoningEffort, credentialName, credentialInstance,
                    maxOutputTokens, maxToolIterations, null, null);
        }

        /**
         * A subprocess turn. There is deliberately no credential parameter: the
         * CLI is authorized by the user's own login, which this program never
         * sees, so no signature of this method can ever name it.
         */
        public static Config cli(
                String providerName,
                String model,
                String reasoningEffort,
                String cliBinary,
                String cliVersion)
        {
            return new Config(
                    providerName, AgentExecution.CLI, null, null, model,
                    reasoningEffort, null, null, null, null, cliBinary,
                    cliVersion);
        }

        public Config
        {
            requireText(providerName, "providerName");
            requireNonNull(execution, "execution is null");
            requireText(model, "model");
            switch (execution) {
                case API -> {
                    requireNonNull(transport, "transport is null");
                    requireText(endpoint, "endpoint");
                    requireText(credentialName, "credentialName");
                    requireText(credentialInstance, "credentialInstance");
                    requireEndpoint(endpoint);
                    if (maxOutputTokens == null || maxToolIterations == null
                            || maxOutputTokens < 1 || maxOutputTokens > 32_768
                            || maxToolIterations < 1 || maxToolIterations > 2) {
                        throw new IllegalArgumentException(
                                "agent output/tool limits are invalid");
                    }
                    requireAbsent(cliBinary, "cliBinary");
                    requireAbsent(cliVersion, "cliVersion");
                }
                case CLI -> {
                    requireText(cliBinary, "cliBinary");
                    requireAbsent(transport, "transport");
                    requireAbsent(endpoint, "endpoint");
                    requireAbsent(credentialName, "credentialName");
                    requireAbsent(credentialInstance, "credentialInstance");
                    requireAbsent(maxOutputTokens, "maxOutputTokens");
                    requireAbsent(maxToolIterations, "maxToolIterations");
                }
            }
        }
    }

    /** The frozen answer, in the same two shapes {@link Config} carries. */
    public record Binding(
            String runId,
            String providerName,
            AgentExecution execution,
            Transport transport,
            String endpoint,
            String model,
            String reasoningEffort,
            Long credentialId,
            String credentialName,
            String credentialInstance,
            Instant credentialUpdatedAt,
            String promptRevision,
            String promptDigest,
            String toolManifestDigest,
            Integer maxOutputTokens,
            Integer maxToolIterations,
            String cliBinary,
            String cliVersion,
            String bindingDigest,
            Instant boundAt)
    {
        public Binding
        {
            requireText(runId, "runId");
            requireText(providerName, "providerName");
            requireNonNull(execution, "execution is null");
            requireText(model, "model");
            requireText(promptRevision, "promptRevision");
            requireText(promptDigest, "promptDigest");
            requireText(toolManifestDigest, "toolManifestDigest");
            requireText(bindingDigest, "bindingDigest");
            requireNonNull(boundAt, "boundAt is null");
            switch (execution) {
                case API -> {
                    requireNonNull(transport, "transport is null");
                    requireText(endpoint, "endpoint");
                    requireNonNull(credentialId, "credentialId is null");
                    requireText(credentialName, "credentialName");
                    requireText(credentialInstance, "credentialInstance");
                    requireNonNull(
                            credentialUpdatedAt, "credentialUpdatedAt is null");
                    if (maxOutputTokens == null || maxToolIterations == null
                            || maxOutputTokens < 1 || maxToolIterations < 1) {
                        throw new IllegalArgumentException(
                                "bound agent limits are invalid");
                    }
                    requireAbsent(cliBinary, "cliBinary");
                    requireAbsent(cliVersion, "cliVersion");
                }
                case CLI -> {
                    requireText(cliBinary, "cliBinary");
                    requireAbsent(transport, "transport");
                    requireAbsent(endpoint, "endpoint");
                    requireAbsent(credentialId, "credentialId");
                    requireAbsent(credentialName, "credentialName");
                    requireAbsent(credentialInstance, "credentialInstance");
                    requireAbsent(credentialUpdatedAt, "credentialUpdatedAt");
                    requireAbsent(maxOutputTokens, "maxOutputTokens");
                    requireAbsent(maxToolIterations, "maxToolIterations");
                }
            }
        }

        /** Whether this run's turns go over HTTP from inside this JVM. */
        public boolean isApi()
        {
            return execution == AgentExecution.API;
        }
    }

    /** Stable non-effect failure: retry requires the exact credential row. */
    public static final class LaunchUnavailableException
            extends IllegalStateException
    {
        public LaunchUnavailableException(String message)
        {
            super(message);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final CredentialStore credentials;
    private final NewFlowEngineResolver engines;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final FlowRuntime runtime;

    public NewFlowAgentLaunches(
            DataSource dataSource,
            FlowRuntime runtime,
            CredentialStore credentials,
            NewFlowEngineResolver engines,
            Clock clock,
            ObjectMapper mapper)
    {
        this.jdbc = new JdbcTemplate(requireNonNull(
                dataSource, "dataSource is null"));
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.engines = requireNonNull(engines, "engines is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /**
     * Freezes the exact program-selected launch before a process attempt can
     * activate. A redelivery must name the same prompt and actual manifest.
     */
    Binding bind(
            AgentRun run, Program program)
    {
        requireNonNull(run, "run is null");
        requireNonNull(program, "program is null");
        if (run.role() != program.role
                || !run.promptManifestRef().equals(program.promptManifestRef)
                || !run.capabilitySetRef().equals(program.capabilitySetRef)
                || (run.state() != RunState.QUEUED
                    && run.state() != RunState.RUNNING)) {
            throw new IllegalArgumentException(
                    "AgentRun does not own this launch manifest");
        }
        String promptDigest = digest(List.of(
                "new-flow-agent-prompt:v1", program.promptRevision,
                program.systemPrompt));
        // Resolve outside the transaction: discovery may probe an installed
        // CLI, and no database write should wait on a process fork. A run that
        // is already bound is never re-resolved — its frozen engine is the
        // answer, even if the workspace has since been repointed.
        Config resolved = binding(run.runId()).isPresent()
                ? null : engines.resolve(run);
        return requireNonNull(transactions.execute(ignored -> {
            AgentRun current = runtime.run(run.runId()).orElseThrow(() ->
                    new IllegalStateException("AgentRun disappeared before binding"));
            if (!current.equals(run)
                    || (current.state() != RunState.QUEUED
                        && current.state() != RunState.RUNNING)) {
                throw new IllegalStateException(
                        "AgentRun changed before launch binding");
            }
            Optional<Binding> existing = binding(run.runId());
            if (existing.isPresent()) {
                Binding stored = existing.orElseThrow();
                String storedManifestDigest = digestJson(manifest(
                        program, stored.execution(), stored.transport()));
                assertProgramIdentity(
                        stored, run, program, promptDigest,
                        storedManifestDigest);
                return stored;
            }
            Config config = requireNonNull(resolved,
                    "launch binding disappeared between resolution and insert");
            String manifestDigest = digestJson(manifest(
                    program, config.execution(), config.transport()));
            // A CLI turn is authorized by the user's own CLI login. There is no
            // stored row to pin, so there is nothing to look up — and asking for
            // one would either fail or bind a credential the subprocess never
            // uses.
            Credential credential = config.execution() == AgentExecution.API
                    ? credentials.find(
                            CredentialType.AI,
                            config.credentialName(),
                            config.credentialInstance()).orElseThrow(() ->
                                    new LaunchUnavailableException(
                                            "configured AI credential is unavailable"))
                    : null;
            Instant now = clock.instant();
            String bindingDigest = digest(List.of(
                    LAUNCH_DIGEST,
                    run.runId(),
                    run.role().name(),
                    config.providerName(),
                    config.execution().name(),
                    nullable(config.transport()),
                    nullable(config.endpoint()),
                    config.model(),
                    nullable(config.reasoningEffort()),
                    nullable(credential == null ? null : credential.id()),
                    nullable(config.credentialName()),
                    nullable(config.credentialInstance()),
                    nullable(credential == null
                            ? null : credential.updatedAt()),
                    program.promptRevision,
                    promptDigest,
                    manifestDigest,
                    nullable(config.maxOutputTokens()),
                    nullable(config.maxToolIterations()),
                    nullable(config.cliBinary()),
                    nullable(config.cliVersion())));
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_agent_launch_binding (
                        run_id, provider_name, execution, transport, endpoint,
                        model, reasoning_effort, credential_id,
                        credential_name, credential_instance,
                        credential_updated_at, prompt_revision,
                        prompt_digest, tool_manifest_digest, max_output_tokens,
                        max_tool_iterations, cli_binary, cli_version,
                        binding_digest, bound_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    run.runId(),
                    config.providerName(),
                    config.execution().name(),
                    config.transport() == null
                            ? null : config.transport().name(),
                    config.endpoint(),
                    config.model(),
                    config.reasoningEffort(),
                    credential == null ? null : credential.id(),
                    config.credentialName(),
                    config.credentialInstance(),
                    credential == null
                            ? null : credential.updatedAt().toString(),
                    program.promptRevision,
                    promptDigest,
                    manifestDigest,
                    config.maxOutputTokens(),
                    config.maxToolIterations(),
                    config.cliBinary(),
                    config.cliVersion(),
                    bindingDigest,
                    now.toEpochMilli());
            Binding stored = binding(run.runId()).orElseThrow();
            assertProgramIdentity(
                    stored, run, program, promptDigest, manifestDigest);
            return stored;
        }), "launch binding transaction returned null");
    }

    /**
     * Loads the secret ephemerally immediately before the first request. Both
     * display-safe reads pin the exact row around decryption; no default or
     * alternate credential is ever consulted.
     */
    String resolveSecret(Binding binding)
    {
        requireNonNull(binding, "binding is null");
        if (!binding.isApi()) {
            // The single choke point every in-JVM turn passes through, which is
            // why the guard lives here and not in each body: a CLI-bound run has
            // no secret to load, and reaching this far means it was routed to the
            // wrong transport.
            throw new LaunchUnavailableException(
                    "a " + binding.execution() + " run holds no stored credential"
                            + " and must not take the in-JVM turn path");
        }
        Credential before = exactCredential(binding);
        String secret = credentials.getSecret(
                CredentialType.AI,
                binding.credentialName(),
                binding.credentialInstance()).orElseThrow(() ->
                        new LaunchUnavailableException(
                                "bound AI credential secret is unavailable"));
        if (secret.isBlank()) {
            throw new LaunchUnavailableException(
                    "bound AI credential secret is blank");
        }
        Credential after = exactCredential(binding);
        if (before.id() != after.id()
                || !before.updatedAt().equals(after.updatedAt())) {
            throw new LaunchUnavailableException(
                    "bound AI credential rotated during launch");
        }
        return secret;
    }

    public Optional<Binding> binding(String runId)
    {
        requireText(runId, "runId");
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_agent_launch_binding
                WHERE run_id = ?
                """,
                (result, row) -> new Binding(
                        result.getString("run_id"),
                        result.getString("provider_name"),
                        AgentExecution.valueOf(result.getString("execution")),
                        transport(result.getString("transport")),
                        result.getString("endpoint"),
                        result.getString("model"),
                        result.getString("reasoning_effort"),
                        nullableLong(result, "credential_id"),
                        result.getString("credential_name"),
                        result.getString("credential_instance"),
                        instant(result.getString("credential_updated_at")),
                        result.getString("prompt_revision"),
                        result.getString("prompt_digest"),
                        result.getString("tool_manifest_digest"),
                        nullableInt(result, "max_output_tokens"),
                        nullableInt(result, "max_tool_iterations"),
                        result.getString("cli_binary"),
                        result.getString("cli_version"),
                        result.getString("binding_digest"),
                        Instant.ofEpochMilli(result.getLong("bound_at"))),
                runId).stream().findFirst();
    }

    private static Transport transport(String value)
    {
        return value == null ? null : Transport.valueOf(value);
    }

    private static Instant instant(String value)
    {
        return value == null ? null : Instant.parse(value);
    }

    private static Long nullableLong(ResultSet result, String column)
            throws SQLException
    {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet result, String column)
            throws SQLException
    {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private Credential exactCredential(Binding binding)
    {
        Credential current = credentials.find(
                CredentialType.AI,
                binding.credentialName(),
                binding.credentialInstance()).orElseThrow(() ->
                        new LaunchUnavailableException(
                                "bound AI credential was deleted"));
        if (current.id() != binding.credentialId()
                || !current.updatedAt().equals(
                        binding.credentialUpdatedAt())) {
            throw new LaunchUnavailableException(
                    "bound AI credential revision changed");
        }
        return current;
    }

    private void assertProgramIdentity(
            Binding stored,
            AgentRun run,
            Program program,
            String promptDigest,
            String manifestDigest)
    {
        String expected = digest(List.of(
                LAUNCH_DIGEST,
                run.runId(),
                run.role().name(),
                stored.providerName(),
                stored.execution().name(),
                nullable(stored.transport()),
                nullable(stored.endpoint()),
                stored.model(),
                nullable(stored.reasoningEffort()),
                nullable(stored.credentialId()),
                nullable(stored.credentialName()),
                nullable(stored.credentialInstance()),
                nullable(stored.credentialUpdatedAt()),
                program.promptRevision,
                promptDigest,
                manifestDigest,
                nullable(stored.maxOutputTokens()),
                nullable(stored.maxToolIterations()),
                nullable(stored.cliBinary()),
                nullable(stored.cliVersion())));
        if (!stored.runId().equals(run.runId())
                || !stored.promptRevision().equals(program.promptRevision)
                || !stored.promptDigest().equals(promptDigest)
                || !stored.toolManifestDigest().equals(manifestDigest)
                || !stored.bindingDigest().equals(expected)) {
            throw new IllegalStateException(
                    "AgentRun launch binding changed after reservation");
        }
    }

    static String digestJson(JsonNode value)
    {
        try {
            return digest(List.of(
                    "new-flow-tool-manifest:v1",
                    value.toString()));
        }
        catch (RuntimeException failure) {
            throw failure;
        }
    }

    /**
     * What the binding digest pins as the agent's tool surface. An API turn's
     * manifest is the wire-shaped tool list it actually sends. A CLI turn sends
     * no wire, so digesting one would pin a shape it never uses; its manifest is
     * the tool names, which is the part that bounds what the agent may do.
     */
    private ArrayNode manifest(
            Program program, AgentExecution execution, Transport transport)
    {
        if (execution == AgentExecution.CLI) {
            ArrayNode names = mapper.createArrayNode();
            program.tools.forEach(names::add);
            return names;
        }
        return tools(program, transport);
    }

    ArrayNode tools(Program program, Transport transport)
    {
        requireNonNull(program, "program is null");
        requireNonNull(transport, "transport is null");
        ArrayNode tools = mapper.createArrayNode();
        for (String name : program.tools) {
            ObjectNode parameters = parameters(name);
            if (transport == Transport.ANTHROPIC) {
                ObjectNode tool = tools.addObject();
                tool.put("name", name);
                tool.put("description", description(name));
                tool.set("input_schema", parameters);
            }
            else {
                ObjectNode function = tools.addObject()
                        .put("type", "function")
                        .putObject("function");
                function.put("name", name);
                function.put("description", description(name));
                function.set("parameters", parameters);
            }
        }
        return tools;
    }

    String systemPrompt(Program program)
    {
        return requireNonNull(program, "program is null").systemPrompt;
    }

    private ObjectNode parameters(String name)
    {
        ObjectNode schema = mapper.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        switch (name) {
            case "read_file", "read_reviewed_blob", "read_base_blob",
                    "delete_file" -> {
                properties.putObject("path").put("type", "string");
                required.add("path");
            }
            case "search_repository" -> {
                properties.putObject("query").put("type", "string");
                required.add("query");
            }
            case "write_file" -> {
                properties.putObject("path").put("type", "string");
                properties.putObject("content").put("type", "string");
                required.add("path");
                required.add("content");
            }
            case "run_checks" -> properties.putObject("profile")
                    .putArray("type").add("string").add("null");
            case "read_ci_log" -> {
                properties.putObject("index").put("type", "integer")
                        .put("minimum", 0);
                properties.putObject("offset").put("type", "integer")
                        .put("minimum", 0);
                required.add("index");
                required.add("offset");
            }
            case "read_candidate_lesson" -> {
                properties.putObject("index").put("type", "integer")
                        .put("minimum", 0);
                required.add("index");
            }
            case "save_ci_lesson" -> {
                properties.putObject("title").put("type", "string");
                properties.putObject("markdown").put("type", "string");
                required.add("title");
                required.add("markdown");
            }
            case "request_initial_review" -> {
                properties.putObject("title").put("type", "string");
                properties.putObject("body").put("type", "string");
                required.add("title");
                required.add("body");
            }
            default -> {
                // Zero-argument program-owned subject.
            }
        }
        return schema;
    }

    private static String description(String name)
    {
        return switch (name) {
            case "list_repository" -> "List bounded files in this worktree.";
            case "read_file" -> "Read one bounded worktree file.";
            case "search_repository" -> "Search bounded text in this worktree.";
            case "write_file" -> "Replace one bounded worktree text file.";
            case "delete_file" -> "Delete one bounded worktree file.";
            case "run_checks" -> "Run the program-owned local check policy.";
            case "commit_repair" -> "Commit the current CI repair with a fixed message.";
            case "commit_task_change" -> "Commit and mechanically adopt a Task correction.";
            case "commit_initial_change" -> "Commit and mechanically adopt the INITIAL Task change.";
            case "read_initial_task_context" -> "Read the exact Task goal and immutable initial base.";
            case "read_initial_review_context" -> "Read the exact Task goal and completed initial review.";
            case "request_initial_review" -> "Save the local PR draft, run checks, and request exact review.";
            case "ready_for_initial_publish" -> "Accept the exact initial review for manual publication.";
            case "inspect_cleanup" -> "Read the sealed cleanup state.";
            case "finish_cleanup" -> "Finish deterministic cleanup inspection.";
            case "read_ci_failure_context" -> "Read exact failing-check summaries.";
            case "read_ci_fix_context" -> "Read the exact fixer or reviewer result.";
            case "read_candidate_diff" -> "Read the immutable candidate diff.";
            case "spawn_adversarial_reviewer" -> "Seal checks and request exact adversarial review.";
            case "ready_for_review" -> "Accept the exact completed review result.";
            case "list_tree" -> "List the immutable reviewed tree.";
            case "read_diff" -> "Read the immutable base-to-reviewed diff.";
            case "read_reviewed_blob" -> "Read a blob from the immutable reviewed head.";
            case "read_base_blob" -> "Read a blob from the immutable base.";
            case "read_repair_evidence" -> "Read the exact CI repair evidence.";
            case "read_ci_log" -> "Read a bounded log by program-projected index.";
            case "list_candidate_lessons" -> "List bounded untrusted prior CI hints.";
            case "read_candidate_lesson" -> "Read one prior hint by program index.";
            case "save_ci_lesson" -> "Save the sole bounded lesson proposal.";
            default -> throw new IllegalArgumentException("unknown tool " + name);
        };
    }

    private static String digest(List<String> values)
    {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES)
                        .putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String nullable(Object value)
    {
        return value == null ? "<null>" : value.toString();
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    /** Rejects a field belonging to the other execution kind. */
    private static void requireAbsent(Object value, String name)
    {
        if (value != null) {
            throw new IllegalArgumentException(
                    name + " does not belong to this execution kind");
        }
    }

    private static void requireEndpoint(String endpoint)
    {
        URI uri = URI.create(endpoint);
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopback = "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(uri.getHost())
                    || "127.0.0.1".equals(uri.getHost())
                    || "::1".equals(uri.getHost()));
        if ((!secure && !loopback) || uri.isOpaque()
                || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "agent endpoint must be HTTPS or loopback HTTP");
        }
    }
}
