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
import com.bytequay.agent.core.AgentInputDelivery;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.TaskMetrics;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TestFact;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentTurnFailureKind;
import com.google.common.collect.ImmutableList;

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
                task.titleSource().name(),
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
                turn.knownUnsent(),
                turn.settings().map(InteractiveAgentHttpContract::settings),
                turn.terminalReason(),
                turn.failureKind(),
                turn.metrics());
    }

    static QueuedInputResponse queuedInput(QueuedAgentInput input)
    {
        requireNonNull(input, "input is null");
        return new QueuedInputResponse(
                input.id().toString(),
                input.taskId().toString(),
                input.position(),
                input.state().name(),
                input.preview(),
                settings(input.settings()),
                input.turnId().map(Object::toString),
                input.pauseReason(),
                input.createdAt(),
                input.updatedAt());
    }

    static InputDeliveryResponse delivery(AgentInputDelivery delivery)
    {
        requireNonNull(delivery, "delivery is null");
        return new InputDeliveryResponse(
                delivery.id().toString(),
                delivery.taskId().toString(),
                delivery.turnId().toString(),
                delivery.state().name(),
                delivery.failure(),
                delivery.createdAt(),
                delivery.updatedAt());
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
                defaults.permissionPolicy().name(),
                defaults.serviceTier());
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
                        .collect(toImmutableList()),
                settings.serviceTier());
    }

    private static AttachmentReference attachment(AgentAttachmentReference attachment)
    {
        return new AttachmentReference(
                attachment.id(),
                attachment.name(),
                attachment.mediaType());
    }

    /** The first message creates the task and supplies its provisional title; there is no title field. */
    record CreateTaskRequest(
            String taskId,
            String workspaceId,
            String message,
            String laneId,
            TaskDefaults defaults,
            String idempotencyKey)
    {
        CreateTaskRequest
        {
            checkNotBlank(taskId, "taskId");
            checkNotBlank(workspaceId, "workspaceId");
            checkNotBlank(message, "message");
            checkNotBlank(laneId, "laneId");
            requireNonNull(defaults, "defaults is null");
            checkNotBlank(idempotencyKey, "idempotencyKey");
            new AgentLaneId(laneId);
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

    record RenameTaskRequest(
            long expectedVersion,
            String title)
    {
        RenameTaskRequest
        {
            checkArgument(expectedVersion >= 0, "expectedVersion is negative");
            checkNotBlank(title, "title");
        }
    }

    record TaskDefaults(
            Optional<String> model,
            Optional<String> effort,
            String permissionPolicy,
            Optional<String> serviceTier)
    {
        TaskDefaults(Optional<String> model, Optional<String> effort, String permissionPolicy)
        {
            this(model, effort, permissionPolicy, Optional.empty());
        }

        TaskDefaults
        {
            checkOptionalText(model, "model");
            checkOptionalText(effort, "effort");
            checkOptionalText(serviceTier, "serviceTier");
            checkNotBlank(permissionPolicy, "permissionPolicy");
        }
    }

    record TaskResponse(
            String id,
            String workspaceId,
            String title,
            String titleSource,
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
            boolean knownUnsent,
            Optional<TurnSettings> settings,
            Optional<String> terminalReason,
            Optional<AgentTurnFailureKind> failureKind,
            TurnMetrics metrics) {}

    /** Input for the running turn, or for the queue behind it; the version is the one the caller saw. */
    record InputRequest(String message, List<String> attachments, Long expectedVersion)
    {
        InputRequest
        {
            checkNotBlank(message, "message");
            attachments = attachments == null ? ImmutableList.of() : ImmutableList.copyOf(attachments);
        }
    }

    /** A request waiting in the queue. Its text is a bounded preview; the whole message reaches the transcript when it runs. */
    record QueuedInputResponse(
            String id,
            String taskId,
            long position,
            String state,
            String preview,
            TurnSettings settings,
            Optional<String> turnId,
            Optional<String> pauseReason,
            Instant createdAt,
            Instant updatedAt) {}

    /** What became of one steer. UNKNOWN is final: the input was sent and nothing says whether it arrived. */
    record InputDeliveryResponse(
            String id,
            String taskId,
            String turnId,
            String state,
            Optional<String> failure,
            Instant createdAt,
            Instant updatedAt) {}

    record TurnSettings(
            Optional<String> model,
            Optional<String> effort,
            String permissionPolicy,
            String sandboxPolicy,
            long maximumRuntimeMillis,
            List<AttachmentReference> attachments,
            Optional<String> serviceTier) {}

    record AttachmentReference(
            String id,
            String name,
            String mediaType) {}
}
