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

import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.NewFlowAgentLaunches.Config;
import com.bytequay.app.flow.runtime.NewFlowAgentLaunches.LaunchUnavailableException;
import com.bytequay.app.repository.sqlite.WorkModelJson;
import com.bytequay.app.service.agents.TurnSpec.Transport;
import com.bytequay.app.service.workmodel.SessionAudience;
import com.bytequay.app.service.workmodel.WorkModelService;
import com.bytequay.app.service.workmodel.WorkspaceEngineSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Resolves the engine one new-flow agent run launches against. Nothing here
 * is a compiled-in default: the engine, model, reasoning effort, endpoint, and
 * credential all come from the user's workspace settings, or from what this
 * machine turns out to have installed. A machine with neither has no agent,
 * and says so rather than inventing a provider to bill.
 *
 * <p>Resolution runs once per run, immediately before {@link
 * NewFlowAgentLaunches#bind} freezes the answer into the launch binding, so a
 * settings change during an open repair cannot move a run already underway.
 *
 * <p>This is a configuration read against the primary application database.
 * It borrows no old-flow orchestration — the settings rows it reads are user
 * intent, which the greenfield flow has no separate copy of and should not
 * grow one.
 */
public class NewFlowEngineResolver
{
    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final String DEEPSEEK_URL = "https://api.deepseek.com/chat/completions";

    /** Program-owned bounds. These are limits on the runtime's own exposure,
     *  not part of the engine the user picks, so they stay constant across
     *  every resolved engine — but they still enter the binding digest. */
    private static final int MAX_OUTPUT_TOKENS = 8192;
    private static final int MAX_TOOL_ITERATIONS = 2;

    private final FlowRuntime runtime;
    private final JdbcTemplate jdbc;
    private final WorkspaceEngineSettings engineSettings;
    private final WorkModelService workModels;
    private final ObjectMapper mapper;

    public NewFlowEngineResolver(
            FlowRuntime runtime,
            JdbcTemplate jdbc,
            WorkspaceEngineSettings engineSettings,
            WorkModelService workModels,
            ObjectMapper mapper)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.engineSettings = requireNonNull(engineSettings, "engineSettings is null");
        this.workModels = requireNonNull(workModels, "workModels is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** The engine this run launches on, from its owning Task's workspace. */
    public Config resolve(AgentRun run)
    {
        requireNonNull(run, "run is null");
        return resolve(
                runtime.operation(run.operationId())
                        .flatMap(operation -> runtime.task(operation.taskId()))
                        .orElseThrow(() -> new IllegalStateException(
                                "AgentRun has no owning Task to resolve an engine for")),
                run.role());
    }

    /**
     * The engine this Task's {@code role} runs on. Throws {@link
     * LaunchUnavailableException} — a stable non-effect failure the owner
     * lanes already treat as retryable — when no engine can be resolved or
     * the resolved one needs a transport the runtime does not admit.
     */
    public Config resolve(Task task, AgentRole role)
    {
        requireNonNull(task, "task is null");
        requireNonNull(role, "role is null");
        Optional<String> workspaceId = workspaceFor(task.repositoryId());
        String audience = audienceFor(role);
        WorkModel picked = workspaceId
                .flatMap(id -> engineSettings.forAudience(id, audience)
                        .map(WorkspaceEngineSettings.Engine::model))
                .or(() -> workspaceId.flatMap(this::storedWorkspaceEngine))
                .orElseGet(this::discover);
        return config(freeze(picked));
    }

    static String audienceFor(AgentRole role)
    {
        return switch (role) {
            case TASK_AGENT -> SessionAudience.DEV;
            case CI_FIXER, CI_LEARNER -> SessionAudience.CI_FIX;
            case ADVERSARIAL_REVIEWER -> SessionAudience.REVIEW;
        };
    }

    /**
     * The workspace owning this Task's repository. A repository claimed by
     * more than one workspace resolves to the lowest non-scratch workspace ID:
     * arbitrary, but stable, so the same Task never resolves two engines.
     */
    private Optional<String> workspaceFor(String repositoryId)
    {
        List<String> ids = jdbc.queryForList(
                """
                SELECT repos.workspace_id
                FROM workspace_repos repos
                JOIN workspaces workspace ON workspace.id = repos.workspace_id
                WHERE repos.repo_full_name = ?
                ORDER BY workspace.is_scratch, repos.workspace_id
                """,
                String.class,
                repositoryId);
        return ids.stream().findFirst();
    }

    /** The workspace-scope engine column, which predates the settings page's
     *  per-audience rows and is still what the workspace REST endpoint writes. */
    private Optional<WorkModel> storedWorkspaceEngine(String workspaceId)
    {
        return jdbc.queryForList(
                        "SELECT work_model_json FROM workspaces WHERE id = ?",
                        String.class,
                        workspaceId)
                .stream()
                .findFirst()
                .map(json -> WorkModelJson.deserialise(mapper, json))
                .filter(model -> model != null
                        && model.agentOrProvider() != null
                        && !model.agentOrProvider().isBlank());
    }

    /**
     * What this machine can actually run when the workspace configured
     * nothing. Engines needing a transport the runtime does not admit are
     * skipped here rather than offered and then parked — discovery is the
     * program's own fallback, so picking something unrunnable would help
     * nobody. An explicit user pick is never filtered this way.
     */
    private WorkModel discover()
    {
        return workModels.discoverEngines().stream()
                .filter(model -> model.kind() == WorkModelKind.API)
                .findFirst()
                .orElseThrow(() -> new LaunchUnavailableException(
                        "no agent engine is configured for this workspace and none "
                                + "was discovered on this machine"));
    }

    private WorkModel freeze(WorkModel picked)
    {
        try {
            return workModels.freeze(picked);
        }
        catch (RuntimeException failure) {
            // Missing account, unknown engine, no catalog model: all mean the
            // configured engine cannot launch right now, none mean substitute
            // a different one.
            throw new LaunchUnavailableException(
                    "configured agent engine is unavailable: " + failure.getMessage());
        }
    }

    private Config config(WorkModel engine)
    {
        if (engine.kind() == WorkModelKind.CLI) {
            // ponytail: message, not a typed park reason. A distinct
            // NEEDS_ATTENTION code needs runtime plumbing and only earns its
            // keep once the out-of-process transport exists to clear it.
            throw new LaunchUnavailableException(
                    "ENGINE_TRANSPORT_UNSUPPORTED: the workflow runtime admits no "
                            + "CLI agent transport, and will not silently run "
                            + engine.agentOrProvider() + " on an API engine instead");
        }
        String provider = engine.agentOrProvider();
        Transport transport = switch (provider) {
            case "anthropic" -> Transport.ANTHROPIC;
            case "openai", "deepseek" -> Transport.OPENAI_COMPAT;
            default -> throw new LaunchUnavailableException(
                    "unsupported API provider for an agent run: " + provider);
        };
        String endpoint = switch (provider) {
            case "anthropic" -> ANTHROPIC_URL;
            case "openai" -> OPENAI_URL;
            default -> DEEPSEEK_URL;
        };
        if (WorkModelService.isLocalModel(provider, engine.model())) {
            // The locally-served variant is answered by the ds4 subprocess, not
            // this endpoint. Route it only once the runtime can address that
            // server; guessing a loopback port would fail mid-repair instead.
            throw new LaunchUnavailableException(
                    "ENGINE_TRANSPORT_UNSUPPORTED: the locally-served model "
                            + engine.model() + " has no agent-run transport");
        }
        return new Config(
                provider,
                transport,
                endpoint,
                engine.model(),
                workModels.resolveEffort(
                        engine.kind(), provider, engine.model(),
                        engine.reasoningEffort()),
                provider,
                engine.account(),
                MAX_OUTPUT_TOKENS,
                MAX_TOOL_ITERATIONS);
    }
}
