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
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnMemory;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentTurnFailureKind;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderAgentEvent.PlanItem;
import com.bytequay.app.agent.IssueSourceInput;
import com.bytequay.app.agent.JiraSourceInput;
import com.bytequay.app.web.DevelopmentAutomationDto;
import com.bytequay.app.web.ci.CiAutofixHttpModels;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.JiraIssueSource;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
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

    /** {@code metrics} are the totals to show: a live task's row lags its turns, so the caller folds them. */
    static TaskResponse task(
            AgentTask task,
            AgentMetrics metrics,
            Optional<CiAutofixHttpModels.DevelopmentResponse> developmentCi,
            Optional<TaskPullRequestResponse> pullRequest,
            Optional<IssueSourceResponse> issueSource)
    {
        return task(task, metrics, developmentCi, pullRequest, issueSource, Optional.empty());
    }

    static TaskResponse task(
            AgentTask task,
            AgentMetrics metrics,
            Optional<CiAutofixHttpModels.DevelopmentResponse> developmentCi,
            Optional<TaskPullRequestResponse> pullRequest,
            Optional<IssueSourceResponse> issueSource,
            Optional<JiraSourceResponse> jiraSource)
    {
        requireNonNull(task, "task is null");
        requireNonNull(metrics, "metrics is null");
        checkArgument(task.interactive(), "task is not interactive");
        return new TaskResponse(
                task.id().toString(),
                task.workspaceId().orElseThrow(),
                task.taskWorktree().map(TaskWorktree::generatedBranch),
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
                metrics,
                task.summary().map(InteractiveAgentHttpContract::summary),
                task.cleanupState().name(),
                task.deleteAfter(),
                task.cleanupFailure(),
                task.worktreeRemovedAt(),
                task.archivedAt(),
                task.interactive(),
                task.developmentContinuationUnfinished(),
                developmentCi,
                pullRequest,
                issueSource,
                jiraSource);
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
                turn.metrics(),
                turn.developmentResult(),
                turn.developmentOrigin().map(origin -> new DevelopmentOrigin(origin.kind().name(), origin.parentUserTurnId().toString())),
                workflowRequest(turn),
                turn.plan(),
                turn.guidelines(),
                turn.memories());
    }

    /** Display hints from saved workflow keys and policies; never authorization for an operation. */
    private static Optional<String> workflowRequest(AgentTurn turn)
    {
        String key = turn.idempotencyKey();
        String contextPrefix = "context-build:" + turn.taskId() + ":";
        if (key.startsWith(contextPrefix) && key.substring(contextPrefix.length()).matches("[1-9][0-9]*")) {
            return Optional.of("CONTEXT_BUILD");
        }
        if (turn.settings().filter(settings -> settings.permissionPolicy() == AgentPermissionPolicy.CHERRY_PICK).isPresent()) {
            return Optional.of("CHERRY_PICK");
        }
        if (turn.settings().filter(settings -> settings.permissionPolicy() == AgentPermissionPolicy.CI_AUTOFIX).isPresent()
                && key.matches("[0-9a-f-]{36}:agent_turn:(?:[0-9a-f]{40}|[0-9a-f]{64}):[1-9][0-9]*")) {
            return Optional.of("REBASE");
        }
        return Optional.empty();
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
                summary.metrics(),
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
                defaults.serviceTier(),
                defaults.developmentAutomation().map(DevelopmentAutomationDto::from));
    }

    static TurnSettings settings(AgentTurnSettings settings)
    {
        return new TurnSettings(
                settings.model(),
                settings.effort(),
                settings.permissionPolicy().name(),
                settings.maximumRuntime().toMillis(),
                settings.attachments().stream()
                        .map(InteractiveAgentHttpContract::attachment)
                        .collect(toImmutableList()),
                settings.serviceTier(),
                settings.developmentAutomation().map(DevelopmentAutomationDto::from));
    }

    private static AttachmentReference attachment(AgentAttachmentReference attachment)
    {
        return new AttachmentReference(attachment.id(), attachment.name(), attachment.mediaType());
    }

    /** The first message creates the task and supplies its provisional title; there is no title field. */
    record CreateTaskRequest(
            String taskId,
            String workspaceId,
            String message,
            String laneId,
            TaskDefaults defaults,
            String idempotencyKey,
            Optional<DevelopmentAutomationDto> developmentAutomation,
            Optional<IssueSourceInput> issueSource,
            Optional<JiraSourceInput> jiraSource,
            Optional<String> workItemId)
    {
        CreateTaskRequest(String taskId, String workspaceId, String message, String laneId, TaskDefaults defaults, String idempotencyKey)
        {
            this(taskId, workspaceId, message, laneId, defaults, idempotencyKey, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        CreateTaskRequest(
                String taskId, String workspaceId, String message, String laneId, TaskDefaults defaults, String idempotencyKey,
                Optional<DevelopmentAutomationDto> developmentAutomation, Optional<IssueSourceInput> issueSource)
        {
            this(taskId, workspaceId, message, laneId, defaults, idempotencyKey, developmentAutomation, issueSource, Optional.empty(), Optional.empty());
        }

        CreateTaskRequest(
                String taskId, String workspaceId, String message, String laneId, TaskDefaults defaults, String idempotencyKey,
                Optional<DevelopmentAutomationDto> developmentAutomation, Optional<IssueSourceInput> issueSource, Optional<JiraSourceInput> jiraSource)
        {
            this(taskId, workspaceId, message, laneId, defaults, idempotencyKey, developmentAutomation, issueSource, jiraSource, Optional.empty());
        }

        CreateTaskRequest
        {
            requireNonNull(issueSource, "issueSource is null");
            requireNonNull(jiraSource, "jiraSource is null");
            checkArgument(issueSource.isEmpty() || jiraSource.isEmpty(), "Choose only one issue source");
            requireNonNull(workItemId, "workItemId is null");
            // Work with an issue or ticket becomes that source's attempt through its own admission.
            checkArgument(workItemId.isEmpty() || issueSource.isEmpty() && jiraSource.isEmpty(), "Start work with an issue or ticket from its source");
            requireNonNull(developmentAutomation, "developmentAutomation is null");
            checkNotBlank(taskId, "taskId");
            checkNotBlank(workspaceId, "workspaceId");
            checkNotBlank(message, "message");
            checkNotBlank(laneId, "laneId");
            requireNonNull(defaults, "defaults is null");
            checkNotBlank(idempotencyKey, "idempotencyKey");
            new AgentLaneId(laneId);
            checkArgument(defaults.model().isPresent(), "model must not be blank");
            checkArgument(AgentPermissionPolicy.AUTO.name().equals(defaults.permissionPolicy()), "Interactive tasks currently support only AUTO permission");
        }
    }

    record UpdateDefaultsRequest(long expectedVersion, TaskDefaults defaults)
    {
        UpdateDefaultsRequest
        {
            checkArgument(expectedVersion >= 0, "expectedVersion is negative");
            requireNonNull(defaults, "defaults is null");
            checkArgument(AgentPermissionPolicy.AUTO.name().equals(defaults.permissionPolicy()), "Interactive tasks currently support only AUTO permission");
        }
    }

    /** One change to a task: a person's title, or whether it sits on the archive shelf. Exactly one is given. */
    record PatchTaskRequest(long expectedVersion, Optional<String> title, Optional<Boolean> archived)
    {
        PatchTaskRequest
        {
            checkArgument(expectedVersion >= 0, "expectedVersion is negative");
            checkOptionalText(title, "title");
            requireNonNull(archived, "archived is null");
            checkArgument(title.isPresent() != archived.isPresent(), "give either a title or archived");
        }
    }

    record TaskDefaults(
            Optional<String> model,
            Optional<String> effort,
            String permissionPolicy,
            Optional<String> serviceTier,
            Optional<DevelopmentAutomationDto> developmentAutomation)
    {
        TaskDefaults(Optional<String> model, Optional<String> effort, String permissionPolicy)
        {
            this(model, effort, permissionPolicy, Optional.empty(), Optional.empty());
        }

        TaskDefaults
        {
            requireNonNull(developmentAutomation, "developmentAutomation is null");
            checkOptionalText(model, "model");
            checkOptionalText(effort, "effort");
            checkOptionalText(serviceTier, "serviceTier");
            checkNotBlank(permissionPolicy, "permissionPolicy");
        }
    }

    record IssueSourceResponse(@JsonUnwrapped GitHubIssueSource source, Optional<String> initialTurnId, String instructions) {}

    record JiraSourceResponse(@JsonUnwrapped JiraIssueSource source, Optional<String> initialTurnId, String instructions) {}

    record TaskResponse(
            String id,
            String workspaceId,
            Optional<String> generatedBranch,
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
            AgentMetrics metrics,
            Optional<TaskSummaryResponse> summary,
            String cleanupState,
            Optional<Instant> deleteAfter,
            Optional<String> cleanupFailure,
            Optional<Instant> checkoutRemovedAt,
            Optional<Instant> archivedAt,
            boolean interactive,
            boolean developmentContinuationUnfinished,
            Optional<CiAutofixHttpModels.DevelopmentResponse> developmentCi,
            Optional<TaskPullRequestResponse> pullRequest,
            Optional<IssueSourceResponse> issueSource,
            Optional<JiraSourceResponse> jiraSource) {}

    record TaskPullRequestResponse(
            int number,
            String title,
            String state,
            boolean draft) {}

    record MutationClaim(
            String kind,
            String ownerId,
            Instant acquiredAt) {}

    record TaskSummaryResponse(
            String finalResponse,
            Optional<String> resultBranch,
            Optional<String> resultHead,
            AgentMetrics metrics,
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
            AgentMetrics metrics,
            Optional<DevelopmentResultReport> developmentResult,
            Optional<DevelopmentOrigin> developmentOrigin,
            Optional<String> workflowRequest,
            Optional<List<PlanItem>> plan,
            List<String> guidelines,
            List<TurnMemory> memories) {}

    record DevelopmentOrigin(String kind, String parentUserTurnId) {}

    /** Input for the running turn, or for the queue behind it; the version is the one the caller saw. */
    record InputRequest(String message, List<String> attachments, Long expectedVersion, Optional<DevelopmentAutomationDto> developmentAutomation)
    {
        InputRequest
        {
            requireNonNull(developmentAutomation, "developmentAutomation is null");
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

    record QueuedInputDraftResponse(String message, TurnSettings settings, List<AgentHttpModels.AttachmentResponse> attachments) {}

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
            long maximumRuntimeMillis,
            List<AttachmentReference> attachments,
            Optional<String> serviceTier,
            Optional<DevelopmentAutomationDto> developmentAutomation) {}

    record AttachmentReference(
            String id,
            String name,
            String mediaType) {}
}
