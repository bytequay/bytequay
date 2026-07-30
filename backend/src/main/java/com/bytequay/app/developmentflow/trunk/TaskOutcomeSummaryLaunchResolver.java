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
package com.bytequay.app.developmentflow.trunk;

import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.skills.RoleRegistry;
import com.bytequay.app.service.workmodel.ReasoningEffortService;
import com.bytequay.app.service.workmodel.SessionAudience;
import com.bytequay.app.service.workmodel.ThreadEngineOverrides;
import com.bytequay.app.service.workspaces.SessionKnowledgeProvider;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

import static java.util.Objects.requireNonNull;

/** Freezes the Brain engine, role, evidence prompt, and stable repo root. */
@Component
public final class TaskOutcomeSummaryLaunchResolver
        implements TaskOutcomeSummaryRuntime.LaunchResolver
{
    private final ThreadEngineOverrides engines;
    private final WorkspaceRepositoryResolver repositories;
    private final WatchedRepoStore watchedRepos;
    private final RoleRegistry roles;
    private final SessionKnowledgeProvider knowledge;
    private final ReasoningEffortService reasoningEfforts;

    public TaskOutcomeSummaryLaunchResolver(
            ThreadEngineOverrides engines,
            WorkspaceRepositoryResolver repositories,
            WatchedRepoStore watchedRepos,
            RoleRegistry roles,
            SessionKnowledgeProvider knowledge,
            ReasoningEffortService reasoningEfforts)
    {
        this.engines = requireNonNull(engines, "engines is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.roles = requireNonNull(roles, "roles is null");
        this.knowledge = requireNonNull(knowledge, "knowledge is null");
        this.reasoningEfforts = requireNonNull(
                reasoningEfforts, "reasoningEfforts is null");
    }

    @Override
    public TaskOutcomeSummaryRuntime.LaunchSpec resolve(
            SqliteTaskOutcomeSummaryStore.Outcome outcome)
    {
        requireNonNull(outcome, "outcome is null");
        WorkModel model = engines.forAudience(
                        outcome.trunkId(), SessionAudience.PLAN)
                .orElseThrow(() -> new IllegalStateException(
                        "Trunk has no frozen Plan engine for outcome summary"));
        model = reasoningEfforts.forTask(
                outcome.trunkId(), outcome.taskId(), model);
        String provider = requireText(
                model.agentOrProvider(), "Plan engine provider");
        String modelName = requireText(model.model(), "Plan engine model");
        Path repositoryRoot = repositoryRoot(outcome.workspaceId());
        String context = knowledge.renderForThread(
                outcome.workspaceId(), outcome.trunkId(), SessionAudience.PLAN,
                outcome.fallbackSummaryText());
        String systemPrompt = roles.brainTemplate()
                + (context.isBlank() ? "" : "\n\n" + context);
        String userMessage = "Summarize completed Task " + outcome.taskSeq()
                + ": " + outcome.displayName();
        String prompt = """
                Write a concise 1-3 sentence completion summary for the Trunk.
                Use only the frozen outcome facts below and evidence available
                through read-only tools. Do not invent follow-up work. Return
                only the summary text.

                Task id: %s
                Task sequence: %s
                Task name: %s
                Terminal reason: %s
                Pull request: %s
                Observed head: %s
                Cleanup evidence digest: %s
                Deterministic fallback: %s
                """.formatted(
                outcome.taskId(), outcome.taskSeq(), outcome.displayName(),
                outcome.terminalReason(),
                outcome.remotePrNumber() == null
                        ? "none" : "#" + outcome.remotePrNumber(),
                value(outcome.observedHeadSha()),
                outcome.cleanupSummaryDigest(), outcome.fallbackSummaryText());
        return new TaskOutcomeSummaryRuntime.LaunchSpec(
                model.kind() == WorkModelKind.CLI
                        ? AgentTurnProviderSession.Transport.CLI
                        : AgentTurnProviderSession.Transport.API,
                provider, model.account(), modelName, model.reasoningEffort(),
                repositoryRoot, systemPrompt, userMessage, prompt);
    }

    private Path repositoryRoot(String workspaceId)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repository =
                repositories.resolve(workspaceId);
        WatchedRepo watched = watchedRepos.find(
                        repository.owner(), repository.repo())
                .orElseThrow(() -> new IllegalStateException(
                        "Workspace repository has no watched clone: "
                                + repository.fullName()));
        if (watched.localClonePath() == null
                || watched.localClonePath().isBlank()) {
            throw new IllegalStateException(
                    "Workspace repository has no verified local clone: "
                            + repository.fullName());
        }
        Path root = Path.of(watched.localClonePath())
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException(
                    "Workspace repository clone is unavailable: " + root);
        }
        return root;
    }

    private static String value(String value)
    {
        return value == null || value.isBlank() ? "none" : value;
    }

    private static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalStateException(name + " is blank");
        }
        return value;
    }
}
