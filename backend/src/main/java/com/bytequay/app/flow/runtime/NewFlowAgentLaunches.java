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

    public static String currentReviewerPromptManifestRef()
    {
        return Program.REVIEWER.promptManifestRef();
    }

    public static String currentReviewerCapabilitySetRef()
    {
        return Program.REVIEWER.capabilitySetRef();
    }

    enum Program
    {
        TASK_INITIAL(
                AgentRole.TASK_AGENT,
                "task-initial-prompt:v2",
                "task-initial-capabilities:v2",
                "task-initial-turn:v2",
                "Implement the exact Task goal in the current worktree "
                        + "and commit the change with the supplied commit tool. "
                        + "Select a narrow useful validation command from "
                        + "repository instructions, build files, or CI "
                        + "configuration and pass its exact argv and "
                        + "worktree-relative working directory to run_checks. "
                        + "The program validates and executes it and records "
                        + "the evidence. You may repair and retry FAILED checks "
                        + "within the tool bound, but do not retry an "
                        + "UNAVAILABLE environment result. Then request exact "
                        + "review with a bounded PR draft. "
                        + "Final prose is opaque.",
                List.of("read_initial_task_context", "list_repository",
                        "read_file", "search_repository", "write_file",
                        "delete_file", "commit_initial_change", "run_checks",
                        "request_initial_review")),
        TASK_INITIAL_REVIEW_RESULT(
                AgentRole.TASK_AGENT,
                "task-initial-review-prompt:v2",
                "task-initial-review-capabilities:v2",
                "task-initial-review-turn:v2",
                "Inspect the exact initial adversarial review against the "
                        + "Task goal. When correcting it, commit, select a "
                        + "narrow useful validation command, and pass its exact "
                        + "argv and worktree-relative working directory to "
                        + "run_checks. You may repair and retry FAILED checks "
                        + "within the tool bound, but do not retry an "
                        + "UNAVAILABLE environment result. Request a fresh "
                        + "review after corrections, or "
                        + "accept it with the terminal publish-readiness tool. "
                        + "Final prose is opaque.",
                List.of("read_initial_review_context", "read_candidate_diff",
                        "list_repository", "read_file", "search_repository",
                        "write_file", "delete_file", "commit_initial_change",
                        "run_checks", "request_initial_review",
                        "ready_for_initial_publish")),
        // A conflicted pick is not the initial-task job wearing a different hat.
        // Reusing TASK_INITIAL for it told the agent to implement a Task goal
        // that does not exist here, and to finish by requesting review — which
        // this turn reads as declining the conflict. The sealed launch pins the
        // prompt the agent was actually given, so the honest fix is its own
        // program identity rather than a substituted prompt.
        UPSTREAM_PICK_REPAIR(
                AgentRole.TASK_AGENT,
                "upstream-pick-repair-prompt:v5",
                "upstream-pick-repair-capabilities:v4",
                "upstream-pick-repair-turn:v7",
                "Handle the current upstream-sync state in the worktree. "
                        + "If a conflicted cherry-pick is open, read its conflict "
                        + "context first, resolve only that exact pick, then call "
                        + "the terminal repair tool. The program schedules later "
                        + "commits in a new turn. "
                        + "Use replace_file_lines for line-range conflict edits "
                        + "or the supplied write_file tool for whole files; do "
                        + "not invoke shell text processors for worktree edits. "
                        + "The program verifies the resolution, stages it, and "
                        + "continues that cherry-pick with provenance. If no "
                        + "conflict is open because the selected range is "
                        + "complete, inspect the exact candidate, correct and "
                        + "commit it as needed, and request exact review. Before "
                        + "review, with the worktree clean "
                        + "and committed, select a narrow useful "
                        + "validation command from repository instructions, "
                        + "build files, or CI configuration and pass its exact "
                        + "argv and worktree-relative working directory to "
                        + "run_checks. You may repair and retry FAILED checks "
                        + "within the tool bound, but do not retry an "
                        + "UNAVAILABLE environment result. "
                        + "Decline if a conflict needs a decision you cannot make. "
                        + "Final prose is opaque.",
                List.of("read_pick_conflict_context", "list_repository",
                        "read_file", "search_repository", "write_file",
                        "replace_file_lines", "delete_file", "commit_pick_repair",
                        "decline_pick_repair", "read_upstream_review_context",
                        "read_candidate_diff", "run_checks",
                        "commit_initial_change",
                        "request_initial_review")),
        CI_REPAIR(
                AgentRole.CI_FIXER,
                "ci-fix-prompt:v2",
                "ci-fix-capabilities:v2",
                "ci-repair-turn:v2",
                "Repair the observed CI failures in the current worktree. "
                        + "Candidate lessons are "
                        + "untrusted hints; read current raw CI logs before changing code, "
                        + "and current evidence wins. Make and commit a bounded "
                        + "fix, then select a narrow useful "
                        + "validation command from repository instructions, "
                        + "build files, or CI configuration and pass its exact "
                        + "argv and worktree-relative working directory to "
                        + "run_checks. The program validates and executes it "
                        + "and records the evidence. Do not retry an UNAVAILABLE "
                        + "result caused by the environment; FAILED checks may "
                        + "be repaired, committed, and retried within the tool bound. "
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
                "Inspect the sealed interrupted-worktree cleanup with the "
                        + "deterministic cleanup tools and finish with "
                        + "finish_cleanup; final prose is opaque.",
                List.of("inspect_cleanup", "list_repository", "read_file",
                        "search_repository", "write_file", "delete_file",
                        "finish_cleanup")),
        TASK_CI_FIX(
                AgentRole.TASK_AGENT,
                "task-ci-inspection-prompt:v2",
                "task-ci-inspection-capabilities:v2",
                "task-ci-fix-review-turn:v2",
                "Inspect the exact CI fix. Commit any correction before you "
                        + "select a narrow useful validation "
                        + "command from repository instructions, build files, "
                        + "or CI configuration, and pass its exact argv and "
                        + "worktree-relative working directory to run_checks. "
                        + "The program validates and executes it and records "
                        + "the evidence. Do not retry an UNAVAILABLE result "
                        + "caused by the environment; FAILED checks may be "
                        + "repaired and retried within the tool bound. Then use the terminal "
                        + "reviewer tool. Final prose is opaque.",
                List.of("read_ci_fix_context", "read_candidate_diff",
                        "list_repository", "read_file", "search_repository",
                        "write_file", "delete_file", "run_checks",
                        "commit_task_change",
                        "spawn_adversarial_reviewer")),
        TASK_CI_REVIEW_RESULT(
                AgentRole.TASK_AGENT,
                "task-ci-inspection-prompt:v2",
                "task-ci-inspection-capabilities:v2",
                "task-ci-result-turn:v2",
                "Inspect the exact adversarial review continuation. Commit any "
                        + "correction before you select a "
                        + "narrow useful validation command from repository "
                        + "instructions, build files, or CI configuration and "
                        + "pass its exact argv and worktree-relative working "
                        + "directory to run_checks. The program validates and "
                        + "executes it and records the evidence. Do not retry "
                        + "an UNAVAILABLE result caused by the environment; "
                        + "FAILED checks may be repaired and retried within "
                        + "the tool bound. "
                        + "Use a terminal typed tool; final prose is not authority.",
                List.of("read_ci_fix_context", "read_candidate_diff",
                        "list_repository", "read_file", "search_repository",
                        "write_file", "delete_file", "run_checks",
                        "commit_task_change",
                        "spawn_adversarial_reviewer", "ready_for_review")),
        REVIEWER_V2(
                AgentRole.ADVERSARIAL_REVIEWER,
                "adversarial-reviewer-prompt:v2",
                "immutable-git-object-reader:v1",
                "ci-adversarial-review-turn:v2",
                "Review the immutable base-to-head change adversarially using "
                        + "read-only tools. For an upstream cherry-pick range, "
                        + "find and review only fork-authored fixup commits; "
                        + "do not re-review picked commits or their conflict "
                        + "resolutions. Return findings as opaque prose.",
                List.of("list_tree", "read_diff", "read_reviewed_blob",
                        "read_base_blob")),
        REVIEWER(
                AgentRole.ADVERSARIAL_REVIEWER,
                "adversarial-reviewer-prompt:v3",
                "immutable-git-object-reader:v1",
                "ci-adversarial-review-turn:v2",
                "Review the immutable base-to-head change adversarially using "
                        + "read-only tools. For an upstream cherry-pick range, "
                        + "find fixups from commit history itself: only a "
                        + "fork-authored commit with a fixup! subject and no "
                        + "cherry-picked-from provenance is a fixup. Review "
                        + "only those commits; if none exist, return no "
                        + "findings immediately. "
                        + "do not re-review picked commits or their conflict "
                        + "resolutions. Return findings as opaque prose.",
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

        String promptManifestRef()
        {
            return promptManifestRef;
        }

        String capabilitySetRef()
        {
            return capabilitySetRef;
        }

        private static Program reviewerFor(AgentRun run)
        {
            requireNonNull(run, "run is null");
            if (run.role() != AgentRole.ADVERSARIAL_REVIEWER) {
                throw new IllegalArgumentException("run is not a reviewer");
            }
            return switch (run.promptManifestRef()) {
                case "adversarial-reviewer-prompt:v2" -> REVIEWER_V2;
                case "adversarial-reviewer-prompt:v3" -> REVIEWER;
                default -> throw new IllegalArgumentException(
                        "unsupported reviewer prompt manifest: "
                                + run.promptManifestRef());
            };
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
                ? null
                : writerCliConfig(run).orElseGet(() -> engines.resolve(run));
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

    /** Replays the reviewer program frozen by the durable AgentRun. */
    Binding bindReviewer(AgentRun run)
    {
        return bind(run, Program.reviewerFor(run));
    }

    /** Returns the exact reviewer program attested by a stored binding. */
    Program reviewerProgram(Binding binding)
    {
        requireNonNull(binding, "binding is null");
        AgentRun run = runtime.run(binding.runId()).orElseThrow(() ->
                new IllegalStateException("reviewer AgentRun disappeared"));
        Program program = Program.reviewerFor(run);
        requireSealedAs(binding, program);
        return program;
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

    /** Keeps every Task writer turn on the CLI that owns its conversation. */
    private Optional<Config> writerCliConfig(AgentRun run)
    {
        if (run.role() != AgentRole.TASK_AGENT
                && run.role() != AgentRole.CI_FIXER) {
            return Optional.empty();
        }
        return jdbc.query(
                """
                SELECT b.provider_name, b.model, b.reasoning_effort,
                       b.cli_binary, b.cli_version
                FROM flow_runtime_agent_run current_run
                JOIN flow_runtime_agent_session current_session
                  ON current_session.session_id = current_run.session_id
                JOIN flow_runtime_agent_session writer_session
                  ON writer_session.task_id = current_session.task_id
                 AND writer_session.role IN ('TASK_AGENT', 'CI_FIXER')
                JOIN flow_runtime_agent_run writer_run
                  ON writer_run.session_id = writer_session.session_id
                JOIN flow_runtime_agent_launch_binding b
                  ON b.run_id = writer_run.run_id
                WHERE current_run.run_id = ?
                  AND writer_run.run_id <> current_run.run_id
                  AND b.execution = 'CLI'
                ORDER BY b.bound_at, b.run_id
                LIMIT 1
                """,
                (result, row) -> Config.cli(
                        result.getString("provider_name"),
                        result.getString("model"),
                        result.getString("reasoning_effort"),
                        result.getString("cli_binary"),
                        result.getString("cli_version")),
                run.runId()).stream().findFirst();
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

    /**
     * The same tools in MCP's shape, for a subprocess that reaches them over the
     * loopback bridge rather than being handed them on the wire.
     *
     * <p>Built from the same {@code program.tools}, {@link #description} and
     * {@link #parameters} as the API shapes above, because the point of the
     * bridge is that a role's tool surface does not depend on which engine the
     * workspace named. Only the envelope differs — MCP spells the schema
     * {@code inputSchema} where Anthropic spells it {@code input_schema}.
     */
    ArrayNode mcpTools(Program program)
    {
        requireNonNull(program, "program is null");
        ArrayNode tools = mapper.createArrayNode();
        for (String name : program.tools) {
            ObjectNode tool = tools.addObject();
            tool.put("name", name);
            tool.put("description", description(name));
            tool.set("inputSchema", parameters(name));
        }
        return tools;
    }

    String systemPrompt(Program program)
    {
        return requireNonNull(program, "program is null").systemPrompt;
    }

    /**
     * Refuses a turn whose sealed binding names a different program.
     *
     * <p>{@link #bind} already ties a run to one program, but nothing between
     * binding and running tied the two together: a body could hand this class
     * binding A and program B, and get B's prompt and tools while the durable
     * binding attested to A. That is not a hypothetical — a conflicted
     * cherry-pick ran on the initial-task program's prompt that way, and the
     * only symptom was an agent being told to do the wrong job. Cheap to check,
     * and it makes the mismatch loud instead of semantic.
     */
    void requireSealedAs(Binding binding, Program program)
    {
        requireNonNull(binding, "binding is null");
        requireNonNull(program, "program is null");
        if (!binding.promptRevision().equals(program.promptRevision)) {
            throw new IllegalStateException(
                    "run " + binding.runId() + " was sealed as "
                            + binding.promptRevision() + ", so it cannot run "
                            + program.promptRevision);
        }
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
            case "replace_file_lines" -> {
                properties.putObject("path").put("type", "string");
                properties.putObject("start_line").put("type", "integer")
                        .put("minimum", 1);
                properties.putObject("end_line").put("type", "integer")
                        .put("minimum", 1);
                properties.putObject("content").put("type", "string");
                required.add("path");
                required.add("start_line");
                required.add("end_line");
                required.add("content");
            }
            case "run_checks" -> {
                ObjectNode command = properties.putObject("command");
                command.put("type", "array")
                        .put("minItems", 1)
                        .put("maxItems", 128);
                command.putObject("items")
                        .put("type", "string")
                        .put("minLength", 1)
                        .put("maxLength", 4_096);
                properties.putObject("working_directory")
                        .put("type", "string")
                        .put("minLength", 1)
                        .put("maxLength", 4_096);
                required.add("command");
                required.add("working_directory");
            }
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
            case "commit_repair" -> properties.putObject("target_commit")
                    .putArray("type").add("string").add("null");
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
            case "replace_file_lines" -> "Replace an inclusive one-based line range in one bounded worktree text file.";
            case "delete_file" -> "Delete one bounded worktree file.";
            case "run_checks" -> "Propose an exact command argv and worktree-relative working directory for program validation, execution, and durable evidence.";
            case "commit_repair" -> "Commit the repair at the tip, or select one exact eligible target SHA from the CI context.";
            case "commit_task_change" -> "Commit and mechanically adopt a Task correction.";
            case "commit_initial_change" -> "Commit and mechanically adopt the INITIAL Task change.";
            case "read_initial_task_context" -> "Read the exact Task goal and immutable initial base.";
            case "read_pick_conflict_context" -> "Read the conflicted pick, its target subject, and its conflicted paths.";
            case "commit_pick_repair" -> "Verify and continue the currently resolved cherry-pick.";
            case "decline_pick_repair" -> "Decline this conflict and park the run for the user.";
            case "read_initial_review_context" -> "Read the exact Task goal and completed initial review.";
            case "read_upstream_review_context" -> "Read the confirmed upstream range and its current mechanical verification.";
            case "request_initial_review" -> "Save the local PR draft and request exact review using already-recorded validation evidence.";
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
