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
package com.bytequay.agent.core;

import com.bytequay.agent.spi.DevelopmentLearningSummary;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderAgentEvent.ActivityState;
import com.bytequay.agent.spi.ProviderAgentEvent.ChangeKind;
import com.bytequay.agent.spi.ProviderAgentEvent.CommandAction;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.bytequay.agent.spi.ProviderAgentEvent.PlanItem;
import com.google.common.collect.ImmutableList;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Canonical provider-neutral payload persisted in task logs and exposed to clients. */
public sealed interface AgentEventPayload
        permits AgentEventPayload.UserMessage, AgentEventPayload.AssistantMessage,
        AgentEventPayload.PlanUpdated, AgentEventPayload.CommandActivity,
        AgentEventPayload.FileActivity, AgentEventPayload.ToolActivity,
        AgentEventPayload.ApprovalRequested, AgentEventPayload.ApprovalResolved,
        AgentEventPayload.UsageUpdated, AgentEventPayload.Lifecycle, AgentEventPayload.Error,
        AgentEventPayload.DevelopmentResult
{
    default Kind kind()
    {
        return switch (this) {
            case UserMessage ignored -> Kind.USER_MESSAGE;
            case AssistantMessage ignored -> Kind.ASSISTANT_MESSAGE;
            case DevelopmentResult ignored -> Kind.DEVELOPMENT_RESULT;
            case PlanUpdated ignored -> Kind.PLAN_UPDATED;
            case CommandActivity ignored -> Kind.COMMAND;
            case FileActivity ignored -> Kind.FILE_CHANGE;
            case ToolActivity ignored -> Kind.TOOL;
            case ApprovalRequested ignored -> Kind.APPROVAL_REQUESTED;
            case ApprovalResolved ignored -> Kind.APPROVAL_RESOLVED;
            case UsageUpdated ignored -> Kind.USAGE;
            case Lifecycle ignored -> Kind.LIFECYCLE;
            case Error ignored -> Kind.ERROR;
        };
    }

    record UserMessage(String text, List<AgentAttachmentReference> attachments, DeliveryKind deliveryKind)
            implements AgentEventPayload
    {
        public UserMessage
        {
            checkText(text, "text");
            attachments = ImmutableList.copyOf(attachments);
            requireNonNull(deliveryKind, "deliveryKind is null");
        }
    }

    record AssistantMessage(MessageKind messageKind, MessagePhase phase, String text)
            implements AgentEventPayload
    {
        public AssistantMessage
        {
            requireNonNull(messageKind, "messageKind is null");
            requireNonNull(phase, "phase is null");
            requireNonNull(text, "text is null");
        }
    }

    record PlanUpdated(List<PlanItem> items)
            implements AgentEventPayload
    {
        public PlanUpdated
        {
            items = ImmutableList.copyOf(items);
        }
    }

    /** The root agent's readiness report; execution completion and Git facts remain separate. */
    record DevelopmentResult(boolean readyForDelivery, Optional<String> message, Optional<DevelopmentLearningSummary> learningSummary)
            implements AgentEventPayload
    {
        public DevelopmentResult(DevelopmentResultReport report)
        {
            this(report.readyForDelivery(), report.message(), report.learningSummary());
        }

        public DevelopmentResult
        {
            new DevelopmentResultReport(readyForDelivery, message, learningSummary);
        }
    }

    record CommandActivity(
            ActivityState state,
            List<String> argv,
            Optional<String> output,
            Optional<Integer> exitCode,
            boolean truncated,
            Optional<String> cwd,
            Optional<Long> durationMs,
            List<CommandAction> actions,
            boolean background,
            Optional<String> description)
            implements AgentEventPayload
    {
        public CommandActivity
        {
            requireNonNull(state, "state is null");
            argv = ImmutableList.copyOf(argv);
            checkOptionalText(output, "output");
            requireNonNull(exitCode, "exitCode is null");
            checkArgument(argv.stream().noneMatch(String::isBlank), "argv contains a blank value");
            checkOptionalText(cwd, "cwd");
            requireNonNull(durationMs, "durationMs is null");
            durationMs.ifPresent(value -> checkArgument(value >= 0, "durationMs is negative"));
            // A row stored before actions were carried has no field for them, which reads as none.
            actions = actions == null ? ImmutableList.of() : ImmutableList.copyOf(actions);
            checkOptionalText(description, "description");
        }
    }

    record FileActivity(ActivityState state, String relativePath, ChangeKind change, Optional<String> diff)
            implements AgentEventPayload
    {
        public FileActivity
        {
            requireNonNull(state, "state is null");
            checkText(relativePath, "relativePath");
            requireNonNull(change, "change is null");
            requireNonNull(diff, "diff is null");
            Path path = Path.of(relativePath);
            checkArgument(!path.isAbsolute() && !path.normalize().startsWith(".."), "relativePath is unsafe");
        }
    }

    /** Summary is the tool's result or receipt; description, when present, is the line its row shows instead of the name. */
    record ToolActivity(String name, ActivityState state, String summary, Optional<Long> durationMs, Optional<String> description)
            implements AgentEventPayload
    {
        public ToolActivity
        {
            checkText(name, "name");
            requireNonNull(state, "state is null");
            requireNonNull(summary, "summary is null");
            requireNonNull(durationMs, "durationMs is null");
            durationMs.ifPresent(value -> checkArgument(value >= 0, "durationMs is negative"));
            requireNonNull(description, "description is null");
        }
    }

    record ApprovalRequested(
            String approvalId,
            ApprovalKind approvalKind,
            String title,
            String detail,
            /** What the provider asked to be allowed, so a decision is not made blind. */
            List<String> scope,
            /** Whether the action stays inside the provider's sandbox; false on events stored before it existed. */
            boolean sandboxed,
            boolean actionable,
            List<ApprovalOption> availableDecisions)
            implements AgentEventPayload
    {
        public ApprovalRequested
        {
            checkText(approvalId, "approvalId");
            requireNonNull(approvalKind, "approvalKind is null");
            checkText(title, "title");
            requireNonNull(detail, "detail is null");
            scope = ImmutableList.copyOf(scope);
            availableDecisions = ImmutableList.copyOf(availableDecisions);
            checkArgument(scope.stream().noneMatch(String::isBlank), "scope contains a blank value");
            checkArgument(actionable == !availableDecisions.isEmpty(), "availableDecisions must be non-empty exactly when actionable");
        }
    }

    record ApprovalResolved(String approvalId, ApprovalOutcome outcome, boolean forTask)
            implements AgentEventPayload
    {
        public ApprovalResolved
        {
            checkText(approvalId, "approvalId");
            requireNonNull(outcome, "outcome is null");
            checkArgument(!forTask || outcome == ApprovalOutcome.ALLOWED, "only an allowed approval can cover the task");
        }
    }

    record UsageUpdated(UsageSemantics semantics, long inputTokens, long cachedInputTokens, long outputTokens, Optional<BigDecimal> cost)
            implements AgentEventPayload
    {
        public UsageUpdated
        {
            requireNonNull(semantics, "semantics is null");
            requireNonNull(cost, "cost is null");
            checkArgument(inputTokens >= 0 && cachedInputTokens >= 0 && outputTokens >= 0, "token counts must not be negative");
            checkArgument(cost.stream().noneMatch(value -> value.signum() < 0), "cost must not be negative");
        }
    }

    record Lifecycle(LifecyclePhase phase, String message)
            implements AgentEventPayload
    {
        public Lifecycle
        {
            requireNonNull(phase, "phase is null");
            requireNonNull(message, "message is null");
        }
    }

    record Error(String code, String message, boolean retryable)
            implements AgentEventPayload
    {
        public Error
        {
            checkText(code, "code");
            checkText(message, "message");
        }
    }

    enum Kind
    {
        USER_MESSAGE(UserMessage.class),
        ASSISTANT_MESSAGE(AssistantMessage.class),
        DEVELOPMENT_RESULT(DevelopmentResult.class),
        PLAN_UPDATED(PlanUpdated.class),
        COMMAND(CommandActivity.class),
        FILE_CHANGE(FileActivity.class),
        TOOL(ToolActivity.class),
        APPROVAL_REQUESTED(ApprovalRequested.class),
        APPROVAL_RESOLVED(ApprovalResolved.class),
        USAGE(UsageUpdated.class),
        LIFECYCLE(Lifecycle.class),
        ERROR(Error.class);

        private final Class<? extends AgentEventPayload> type;

        Kind(Class<? extends AgentEventPayload> type)
        {
            this.type = type;
        }

        public Class<? extends AgentEventPayload> type()
        {
            return type;
        }
    }

    enum DeliveryKind
    {
        TURN,
        STEER,
        QUEUED
    }

    enum ApprovalKind
    {
        COMMAND,
        FILE_CHANGE,
        OTHER
    }

    enum ApprovalOption
    {
        ALLOW_ONCE,
        ALLOW_FOR_TASK,
        DENY
    }

    enum ApprovalOutcome
    {
        ALLOWED,
        DENIED,
        EXPIRED,
        CANCELLED
    }

    enum UsageSemantics
    {
        CUMULATIVE,
        CUMULATIVE_FOR_INVOCATION,
        DELTA,
        FINAL_FOR_INVOCATION,
        FINAL_WITH_SESSION_COST
    }

    enum LifecyclePhase
    {
        SESSION_OPENED,
        TURN_STARTED,
        TITLE_UPDATED,
        DEVELOPMENT_AUTOMATION_UPDATED,
        WORKTREE_UPDATED,
        QUEUE_CHANGED,
        QUEUED_INPUT_STARTED,
        QUEUED_INPUT_PAUSED,
        TURN_COMPLETED,
        TASK_COMPLETED,
        TASK_FAILED,
        RETRYING,
        RATE_LIMITED,
        INTERRUPTED,
        AGENT_STARTED,
        AGENT_COMPLETED,
        AGENT_FAILED,
        BACKFILL_STARTED,
        BACKFILL_COMPLETED
    }
}
