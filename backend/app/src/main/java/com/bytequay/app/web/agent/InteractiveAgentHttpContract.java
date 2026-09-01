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
package com.bytequay.app.web.agent;

import com.bytequay.agent.core.AgentAttachmentReference;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.TaskMetrics;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TestFact;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Workspace-safe HTTP shapes for the interactive task surface.
 *
 * <p>These records become the public route contract after the application can
 * resolve a workspace to a verified managed worktree. Keeping the replacement
 * shape separate until then avoids a half-migrated endpoint that still trusts
 * renderer paths.
 */
final class InteractiveAgentHttpContract
{
    private InteractiveAgentHttpContract() {}

    static TaskResponse task(AgentTask task)
    {
        requireNonNull(task, "task is null");
        checkArgument(task.interactive(), "task is not interactive");
        return new TaskResponse(
                task.id().toString(),
                task.workspaceId().orElseThrow(),
                task.title(),
                task.laneId().value(),
                defaults(task.defaults()),
                task.state().name(),
                task.version(),
                task.createdAt(),
                task.updatedAt(),
                task.terminalAt(),
                task.terminalReason(),
                task.metrics(),
                task.summary().map(InteractiveAgentHttpContract::summary),
                task.cleanupState().name(),
                task.deleteAfter(),
                task.cleanupFailure());
    }

    static TurnResponse turn(AgentTurn turn)
    {
        requireNonNull(turn, "turn is null");
        return new TurnResponse(
                turn.id().toString(),
                turn.taskId().toString(),
                turn.state().name(),
                turn.version(),
                turn.createdAt(),
                turn.startedAt(),
                turn.endedAt(),
                turn.cancellationRequestedAt(),
                turn.settings().map(InteractiveAgentHttpContract::settings),
                turn.terminalReason(),
                turn.metrics());
    }

    static TaskSummaryResponse summary(TaskSummary summary)
    {
        requireNonNull(summary, "summary is null");
        return new TaskSummaryResponse(
                summary.finalResponse(),
                summary.worktree().map(worktree -> worktree.worktree().generatedBranch()),
                summary.worktree().map(worktree -> worktree.head()),
                summary.tests(),
                summary.metrics(),
                summary.learnings(),
                summary.notes());
    }

    private static TaskDefaults defaults(AgentTaskDefaults defaults)
    {
        return new TaskDefaults(
                defaults.model(),
                defaults.effort(),
                defaults.permissionPolicy().name());
    }

    private static TurnSettings settings(AgentTurnSettings settings)
    {
        return new TurnSettings(
                settings.model(),
                settings.effort(),
                settings.permissionPolicy().name(),
                settings.sandboxPolicy().name(),
                settings.maximumRuntime().toMillis(),
                settings.attachments().stream()
                        .map(InteractiveAgentHttpContract::attachment)
                        .collect(toImmutableList()));
    }

    private static AttachmentReference attachment(AgentAttachmentReference attachment)
    {
        return new AttachmentReference(
                attachment.id(),
                attachment.name(),
                attachment.mediaType());
    }

    record CreateTaskRequest(
            String workspaceId,
            String title,
            String laneId,
            TaskDefaults defaults)
    {
        CreateTaskRequest
        {
            checkNotBlank(workspaceId, "workspaceId");
            checkNotBlank(title, "title");
            checkNotBlank(laneId, "laneId");
            requireNonNull(defaults, "defaults is null");
            if (!AgentLaneId.CODEX_CLI.value().equals(laneId)) {
                throw new IllegalArgumentException("Interactive tasks currently support only the Codex lane");
            }
            if (defaults.model().isEmpty()) {
                throw new IllegalArgumentException("model must not be blank");
            }
            if (!AgentPermissionPolicy.AUTO.name().equals(defaults.permissionPolicy())) {
                throw new IllegalArgumentException("Interactive tasks currently support only AUTO permission");
            }
        }
    }

    record TaskDefaults(
            Optional<String> model,
            Optional<String> effort,
            String permissionPolicy)
    {
        TaskDefaults
        {
            checkOptionalText(model, "model");
            checkOptionalText(effort, "effort");
            checkNotBlank(permissionPolicy, "permissionPolicy");
        }
    }

    record TaskResponse(
            String id,
            String workspaceId,
            String title,
            String laneId,
            TaskDefaults defaults,
            String state,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Optional<Instant> terminalAt,
            Optional<String> terminalReason,
            TaskMetrics metrics,
            Optional<TaskSummaryResponse> summary,
            String cleanupState,
            Optional<Instant> deleteAfter,
            Optional<String> cleanupFailure) {}

    record TaskSummaryResponse(
            String finalResponse,
            Optional<String> resultBranch,
            Optional<String> resultHead,
            List<TestFact> tests,
            TaskMetrics metrics,
            List<String> learnings,
            Optional<String> notes) {}

    record TurnResponse(
            String id,
            String taskId,
            String state,
            long version,
            Instant createdAt,
            Optional<Instant> startedAt,
            Optional<Instant> endedAt,
            Optional<Instant> cancellationRequestedAt,
            Optional<TurnSettings> settings,
            Optional<String> terminalReason,
            TurnMetrics metrics) {}

    record TurnSettings(
            Optional<String> model,
            Optional<String> effort,
            String permissionPolicy,
            String sandboxPolicy,
            long maximumRuntimeMillis,
            List<AttachmentReference> attachments) {}

    record AttachmentReference(
            String id,
            String name,
            String mediaType) {}
}
