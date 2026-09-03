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

import com.bytequay.agent.spi.CostProvenance;
import com.google.common.collect.ImmutableList;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Canonical provider-neutral payload persisted in task logs and exposed to clients. */
public sealed interface AgentEventPayload
        permits AgentEventPayload.UserMessage, AgentEventPayload.AssistantMessage,
        AgentEventPayload.PlanUpdated, AgentEventPayload.CommandActivity,
        AgentEventPayload.FileActivity, AgentEventPayload.ToolActivity,
        AgentEventPayload.ApprovalRequested, AgentEventPayload.ApprovalResolved,
        AgentEventPayload.UsageUpdated, AgentEventPayload.Lifecycle, AgentEventPayload.Error
{
    Kind kind();

    record UserMessage(String text, List<AgentAttachmentReference> attachments, DeliveryKind deliveryKind)
            implements AgentEventPayload
    {
        public UserMessage
        {
            checkNotBlank(text, "text");
            attachments = ImmutableList.copyOf(attachments);
            requireNonNull(deliveryKind, "deliveryKind is null");
        }

        @Override
        public Kind kind()
        {
            return Kind.USER_MESSAGE;
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

        @Override
        public Kind kind()
        {
            return Kind.ASSISTANT_MESSAGE;
        }
    }

    record PlanUpdated(List<PlanItem> items)
            implements AgentEventPayload
    {
        public PlanUpdated
        {
            items = ImmutableList.copyOf(items);
        }

        @Override
        public Kind kind()
        {
            return Kind.PLAN_UPDATED;
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
            List<CommandAction> actions)
            implements AgentEventPayload
    {
        /** The shape stored before the working directory, duration, and parsed actions were carried. */
        public CommandActivity(
                ActivityState state,
                List<String> argv,
                Optional<String> output,
                Optional<Integer> exitCode,
                boolean truncated)
        {
            this(state, argv, output, exitCode, truncated, Optional.empty(), Optional.empty(), ImmutableList.of());
        }

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
        }

        @Override
        public Kind kind()
        {
            return Kind.COMMAND;
        }
    }

    record FileActivity(
            ActivityState state,
            String relativePath,
            ChangeKind change,
            Optional<String> diff)
            implements AgentEventPayload
    {
        public FileActivity
        {
            requireNonNull(state, "state is null");
            checkNotBlank(relativePath, "relativePath");
            requireNonNull(change, "change is null");
            requireNonNull(diff, "diff is null");
            Path path = Path.of(relativePath);
            checkArgument(!path.isAbsolute() && !path.normalize().startsWith(".."), "relativePath is unsafe");
        }

        @Override
        public Kind kind()
        {
            return Kind.FILE_CHANGE;
        }
    }

    record ToolActivity(String name, ActivityState state, String summary, Optional<Long> durationMs)
            implements AgentEventPayload
    {
        /** The shape stored before a tool's duration was carried. */
        public ToolActivity(String name, ActivityState state, String summary)
        {
            this(name, state, summary, Optional.empty());
        }

        public ToolActivity
        {
            checkNotBlank(name, "name");
            requireNonNull(state, "state is null");
            requireNonNull(summary, "summary is null");
            requireNonNull(durationMs, "durationMs is null");
            durationMs.ifPresent(value -> checkArgument(value >= 0, "durationMs is negative"));
        }

        @Override
        public Kind kind()
        {
            return Kind.TOOL;
        }
    }

    record ApprovalRequested(
            String approvalId,
            ApprovalKind approvalKind,
            String title,
            String detail,
            /** What the provider asked to be allowed, so a decision is not made blind. */
            List<String> scope,
            boolean actionable,
            List<ApprovalOption> availableDecisions)
            implements AgentEventPayload
    {
        public ApprovalRequested
        {
            checkNotBlank(approvalId, "approvalId");
            requireNonNull(approvalKind, "approvalKind is null");
            checkNotBlank(title, "title");
            requireNonNull(detail, "detail is null");
            scope = ImmutableList.copyOf(scope);
            availableDecisions = ImmutableList.copyOf(availableDecisions);
            checkArgument(scope.stream().noneMatch(String::isBlank), "scope contains a blank value");
            checkArgument(actionable == !availableDecisions.isEmpty(),
                    "availableDecisions must be non-empty exactly when actionable");
        }

        @Override
        public Kind kind()
        {
            return Kind.APPROVAL_REQUESTED;
        }
    }

    record ApprovalResolved(String approvalId, ApprovalOutcome outcome)
            implements AgentEventPayload
    {
        public ApprovalResolved
        {
            checkNotBlank(approvalId, "approvalId");
            requireNonNull(outcome, "outcome is null");
        }

        @Override
        public Kind kind()
        {
            return Kind.APPROVAL_RESOLVED;
        }
    }

    record UsageUpdated(
            UsageSemantics semantics,
            long inputTokens,
            long cachedInputTokens,
            long outputTokens,
            Optional<BigDecimal> cost,
            CostProvenance costProvenance)
            implements AgentEventPayload
    {
        public UsageUpdated
        {
            requireNonNull(semantics, "semantics is null");
            requireNonNull(cost, "cost is null");
            requireNonNull(costProvenance, "costProvenance is null");
            checkArgument(inputTokens >= 0 && cachedInputTokens >= 0 && outputTokens >= 0, "token counts must not be negative");
            checkArgument(cost.stream().noneMatch(value -> value.signum() < 0), "cost must not be negative");
            checkArgument(cost.isEmpty() == (costProvenance == CostProvenance.UNAVAILABLE), "cost and costProvenance disagree");
        }

        @Override
        public Kind kind()
        {
            return Kind.USAGE;
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

        @Override
        public Kind kind()
        {
            return Kind.LIFECYCLE;
        }
    }

    record Error(String code, String message, boolean retryable)
            implements AgentEventPayload
    {
        public Error
        {
            checkNotBlank(code, "code");
            checkNotBlank(message, "message");
        }

        @Override
        public Kind kind()
        {
            return Kind.ERROR;
        }
    }

    /** What a shell command did to one worktree-relative path, as the provider parsed it. */
    record CommandAction(CommandActionKind kind, Optional<String> path, Optional<String> query)
    {
        public CommandAction
        {
            requireNonNull(kind, "kind is null");
            checkOptionalText(path, "path");
            checkOptionalText(query, "query");
        }
    }

    enum CommandActionKind
    {
        READ,
        LIST_FILES,
        SEARCH,
        OTHER
    }

    record PlanItem(String text, PlanItemState state)
    {
        public PlanItem
        {
            checkNotBlank(text, "text");
            requireNonNull(state, "state is null");
        }
    }

    enum Kind
    {
        USER_MESSAGE,
        ASSISTANT_MESSAGE,
        PLAN_UPDATED,
        COMMAND,
        FILE_CHANGE,
        TOOL,
        APPROVAL_REQUESTED,
        APPROVAL_RESOLVED,
        USAGE,
        LIFECYCLE,
        ERROR
    }

    enum DeliveryKind
    {
        TURN,
        STEER,
        QUEUED
    }

    enum MessageKind
    {
        ANSWER,
        REASONING_SUMMARY,
        PLAN_TEXT,
        PROMPT
    }

    enum MessagePhase
    {
        PARTIAL,
        FINAL
    }

    enum ActivityState
    {
        STARTED,
        PROGRESS,
        COMPLETED,
        FAILED
    }

    enum ChangeKind
    {
        ADDED,
        MODIFIED,
        DELETED,
        RENAMED
    }

    enum PlanItemState
    {
        PENDING,
        IN_PROGRESS,
        COMPLETED
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
        DELTA,
        FINAL_FOR_INVOCATION
    }

    enum LifecyclePhase
    {
        SESSION_OPENED,
        TURN_STARTED,
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
