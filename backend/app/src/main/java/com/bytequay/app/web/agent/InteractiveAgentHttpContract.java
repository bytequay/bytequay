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
import com.bytequay.agent.core.WorktreeMutationClaim;
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
                task.worktreeMutationClaim().map(InteractiveAgentHttpContract::mutationClaim),
                task.version(),
                task.createdAt(),
                task.updatedAt(),
                task.terminalAt(),
                task.terminalReason(),
                task.metrics(),
                task.summary().map(InteractiveAgentHttpContract::summary),
                task.cleanupState().name(),
                task.deleteAfter(),
                task.cleanupFailure(),
                task.worktreeRemovedAt());
    }

    /**
     * The one operation currently allowed to touch the task's checkout.
     *
     * <p>Exposed because the renderer derives running, closing, and recovery-required from it: a
     * task whose claim outlives its turns is one whose provider could not be shown to have stopped
     * writing, and every mutation control has to stay disabled until that is resolved. The owner id
     * is a turn or launch identifier, never a path or a provider handle.
     */
    private static MutationClaim mutationClaim(WorktreeMutationClaim claim)
    {
        return new MutationClaim(claim.kind().name(), claim.ownerId(), claim.acquiredAt());
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
                // Whether the provider acknowledged the input. A turn that was started but never
                // acknowledged may or may not have been received, so a retry must not be offered
                // as if the message were known unsent.
                turn.providerTurnReference().isPresent(),
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
                summary.notes(),
                summary.children().stream()
                        .map(child -> new ChildSummaryResponse(
                                child.threadId(),
                                child.parentThreadId(),
                                child.label(),
                                child.purpose(),
                                child.outcome().name(),
                                child.result(),
                                child.startedAt(),
                                child.endedAt()))
                        .collect(toImmutableList()));
    }

    private static TaskDefaults defaults(AgentTaskDefaults defaults)
    {
        return new TaskDefaults(
                defaults.model(),
                defaults.effort(),
                defaults.permissionPolicy().name());
    }

    static TurnSettings settings(AgentTurnSettings settings)
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

    record UpdateDefaultsRequest(
            long expectedVersion,
            TaskDefaults defaults)
    {
        UpdateDefaultsRequest
        {
            checkArgument(expectedVersion >= 0, "expectedVersion is negative");
            requireNonNull(defaults, "defaults is null");
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
            Optional<MutationClaim> mutationClaim,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Optional<Instant> terminalAt,
            Optional<String> terminalReason,
            TaskMetrics metrics,
            Optional<TaskSummaryResponse> summary,
            String cleanupState,
            Optional<Instant> deleteAfter,
            Optional<String> cleanupFailure,
            Optional<Instant> checkoutRemovedAt) {}

    record MutationClaim(
            String kind,
            String ownerId,
            Instant acquiredAt) {}

    record TaskSummaryResponse(
            String finalResponse,
            Optional<String> resultBranch,
            Optional<String> resultHead,
            List<TestFact> tests,
            TaskMetrics metrics,
            List<String> learnings,
            Optional<String> notes,
            List<ChildSummaryResponse> children) {}

    /** What is kept about one subagent once its transcript is gone. */
    record ChildSummaryResponse(
            String threadId,
            Optional<String> parentThreadId,
            String label,
            Optional<String> purpose,
            String outcome,
            Optional<String> result,
            Optional<Instant> startedAt,
            Optional<Instant> endedAt) {}

    record TurnResponse(
            String id,
            String taskId,
            String state,
            long version,
            Instant createdAt,
            Optional<Instant> startedAt,
            Optional<Instant> endedAt,
            Optional<Instant> cancellationRequestedAt,
            boolean delivered,
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
