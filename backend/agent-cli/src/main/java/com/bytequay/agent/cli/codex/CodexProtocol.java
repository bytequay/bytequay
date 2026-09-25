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
package com.bytequay.agent.cli.codex;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

final class CodexProtocol
{
    private CodexProtocol() {}

    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            property = "method",
            defaultImpl = UnknownNotification.class)
    @JsonSubTypes({
            @JsonSubTypes.Type(name = "turn/started", value = TurnStartedNotification.class),
            @JsonSubTypes.Type(name = "turn/completed", value = TurnCompletedNotification.class),
            @JsonSubTypes.Type(name = "item/started", value = ItemStartedNotification.class),
            @JsonSubTypes.Type(name = "item/completed", value = ItemCompletedNotification.class),
            @JsonSubTypes.Type(name = "item/autoApprovalReview/started", value = AutoApprovalReviewStartedNotification.class),
            @JsonSubTypes.Type(name = "item/autoApprovalReview/completed", value = AutoApprovalReviewCompletedNotification.class),
            @JsonSubTypes.Type(
                    name = "item/commandExecution/outputDelta",
                    value = CommandOutputNotification.class),
            @JsonSubTypes.Type(
                    name = "item/agentMessage/delta",
                    value = AgentMessageDeltaNotification.class),
            @JsonSubTypes.Type(name = "item/plan/delta", value = PlanDeltaNotification.class),
            @JsonSubTypes.Type(name = "thread/started", value = ThreadStartedNotification.class),
            @JsonSubTypes.Type(name = "turn/plan/updated", value = PlanUpdatedNotification.class),
            @JsonSubTypes.Type(
                    name = "thread/tokenUsage/updated",
                    value = TokenUsageUpdatedNotification.class),
            @JsonSubTypes.Type(name = "error", value = ErrorNotification.class),
    })
    sealed interface Notification
            permits TurnStartedNotification, TurnCompletedNotification,
            ItemStartedNotification, ItemCompletedNotification,
            AutoApprovalReviewStartedNotification, AutoApprovalReviewCompletedNotification,
            CommandOutputNotification, AgentMessageDeltaNotification, PlanDeltaNotification,
            PlanUpdatedNotification, TokenUsageUpdatedNotification, ErrorNotification,
            ThreadStartedNotification, UnknownNotification
    {
        Params params();

        Optional<Long> emittedAtMs();

        default String threadId()
        {
            return params().threadId();
        }

        default Optional<String> turnId()
        {
            return params().owningTurnId();
        }
    }

    /**
     * Payload of a notification or server request. Both wrappers route on the
     * thread and filter on the turn, so every payload answers for itself
     * instead of each wrapper reaching into its own params type.
     */
    sealed interface Params
            permits TurnScopedParams, TurnNotificationParams, ThreadStartedParams, UnknownParams
    {
        String threadId();

        /** The owning turn, absent for thread-scoped payloads. */
        default Optional<String> owningTurnId()
        {
            return Optional.empty();
        }
    }

    /** A payload the App Server scopes to one turn, which names that turn directly. */
    sealed interface TurnScopedParams
            extends Params
            permits ItemNotificationParams, ItemDeltaParams, PlanUpdatedParams, TokenUsageParams,
            ErrorParams, AutoApprovalReviewParams, CommandApprovalParams, FileChangeApprovalParams,
            PermissionsApprovalParams, DynamicToolCallParams
    {
        String turnId();

        @Override
        default Optional<String> owningTurnId()
        {
            return Optional.of(turnId());
        }
    }

    record TurnStartedNotification(TurnNotificationParams params, Optional<Long> emittedAtMs)
            implements Notification
    {
        TurnStartedNotification
        {
            requireNonNull(params, "params is null");
            requireNonNull(emittedAtMs, "emittedAtMs is null");
        }
    }

    record TurnCompletedNotification(TurnNotificationParams params, Optional<Long> emittedAtMs)
            implements Notification
    {
        TurnCompletedNotification
        {
            requireNonNull(params, "params is null");
            requireNonNull(emittedAtMs, "emittedAtMs is null");
        }
    }

    record ItemStartedNotification(ItemNotificationParams params, Optional<Long> emittedAtMs)
            implements Notification
    {
        ItemStartedNotification
        {
            requireNonNull(params, "params is null");
            requireNonNull(emittedAtMs, "emittedAtMs is null");
        }
    }

    record ItemCompletedNotification(ItemNotificationParams params, Optional<Long> emittedAtMs)
            implements Notification
    {
        ItemCompletedNotification
        {
            requireNonNull(params, "params is null");
            requireNonNull(emittedAtMs, "emittedAtMs is null");
        }
    }

    record CommandOutputNotification(ItemDeltaParams params, Optional<Long> emittedAtMs)
            implements Notification
    {
        CommandOutputNotification
        {
            requireNonNull(params, "params is null");
            requireNonNull(emittedAtMs, "emittedAtMs is null");
        }
    }

    record AutoApprovalReviewStartedNotification(AutoApprovalReviewParams params, Optional<Long> emittedAtMs)
            implements Notification
    {
        AutoApprovalReviewStartedNotification
        {
            requireNonNull(params, "params is null");
            requireNonNull(emittedAtMs, "emittedAtMs is null");
        }
    }

    record AutoApprovalReviewCompletedNotification(AutoApprovalReviewParams params, Optional<Long> emittedAtMs)
            implements Notification
    {
        AutoApprovalReviewCompletedNotification
        {
            requireNonNull(params, "params is null");
            requireNonNull(emittedAtMs, "emittedAtMs is null");
        }
    }

    record AutoApprovalReviewParams(String threadId, String turnId, String reviewId, AutoApprovalReview review)
            implements TurnScopedParams
    {
        AutoApprovalReviewParams
        {
            checkIdentifier(threadId, "threadId");
            checkIdentifier(turnId, "turnId");
            checkIdentifier(reviewId, "reviewId");
            requireNonNull(review, "review is null");
        }
    }

    record AutoApprovalReview(String status, Optional<String> riskLevel, Optional<String> rationale)
    {
        AutoApprovalReview
        {
            checkIdentifier(status, "status");
            requireNonNull(riskLevel, "riskLevel is null");
            requireNonNull(rationale, "rationale is null");
        }
    }

    record AgentMessageDeltaNotification(ItemDeltaParams params, Optional<Long> emittedAtMs)
            implements Notification
    {
        AgentMessageDeltaNotification
        {
            requireNonNull(params, "params is null");
            requireNonNull(emittedAtMs, "emittedAtMs is null");
        }
    }

    /** Free-form Plan-mode text streamed a piece at a time; the structured steps arrive as turn/plan/updated. */
    record PlanDeltaNotification(ItemDeltaParams params, Optional<Long> emittedAtMs)
            implements Notification
    {
        PlanDeltaNotification
        {
            requireNonNull(params, "params is null");
            requireNonNull(emittedAtMs, "emittedAtMs is null");
        }
    }

    /** A thread came into being in this process; for a subagent it names its parent and nickname. */
    record ThreadStartedNotification(ThreadStartedParams params, Optional<Long> emittedAtMs)
            implements Notification
    {
        ThreadStartedNotification
        {
            requireNonNull(params, "params is null");
            requireNonNull(emittedAtMs, "emittedAtMs is null");
        }
    }

    record PlanUpdatedNotification(PlanUpdatedParams params, Optional<Long> emittedAtMs)
            implements Notification
    {
        PlanUpdatedNotification
        {
            requireNonNull(params, "params is null");
            requireNonNull(emittedAtMs, "emittedAtMs is null");
        }
    }

    record TokenUsageUpdatedNotification(TokenUsageParams params, Optional<Long> emittedAtMs)
            implements Notification
    {
        TokenUsageUpdatedNotification
        {
            requireNonNull(params, "params is null");
            requireNonNull(emittedAtMs, "emittedAtMs is null");
        }
    }

    record ErrorNotification(ErrorParams params, Optional<Long> emittedAtMs)
            implements Notification
    {
        ErrorNotification
        {
            requireNonNull(params, "params is null");
            requireNonNull(emittedAtMs, "emittedAtMs is null");
        }
    }

    record UnknownNotification(UnknownParams params, Optional<Long> emittedAtMs)
            implements Notification
    {
        UnknownNotification
        {
            requireNonNull(params, "params is null");
            requireNonNull(emittedAtMs, "emittedAtMs is null");
        }
    }

    record TurnNotificationParams(String threadId, Turn turn)
            implements Params
    {
        TurnNotificationParams
        {
            checkIdentifier(threadId, "threadId");
            requireNonNull(turn, "turn is null");
        }

        @Override
        public Optional<String> owningTurnId()
        {
            return Optional.of(turn.id());
        }
    }

    record ItemNotificationParams(ThreadItem item, String threadId, String turnId)
            implements TurnScopedParams
    {
        ItemNotificationParams
        {
            requireNonNull(item, "item is null");
            checkIdentifier(threadId, "threadId");
            checkIdentifier(turnId, "turnId");
        }
    }

    record ThreadStartedParams(HistoryThread thread)
            implements Params
    {
        ThreadStartedParams
        {
            requireNonNull(thread, "thread is null");
        }

        @Override
        public String threadId()
        {
            return thread.id();
        }
    }

    record ItemDeltaParams(String threadId, String turnId, String itemId, String delta)
            implements TurnScopedParams
    {
        ItemDeltaParams
        {
            checkIdentifier(threadId, "threadId");
            checkIdentifier(turnId, "turnId");
            checkIdentifier(itemId, "itemId");
            requireNonNull(delta, "delta is null");
        }
    }

    record PlanUpdatedParams(List<PlanStep> plan, String threadId, String turnId)
            implements TurnScopedParams
    {
        PlanUpdatedParams
        {
            plan = ImmutableList.copyOf(plan);
            checkIdentifier(threadId, "threadId");
            checkIdentifier(turnId, "turnId");
        }
    }

    record TokenUsageParams(String threadId, String turnId, ThreadTokenUsage tokenUsage)
            implements TurnScopedParams
    {
        TokenUsageParams
        {
            checkIdentifier(threadId, "threadId");
            checkIdentifier(turnId, "turnId");
            requireNonNull(tokenUsage, "tokenUsage is null");
        }
    }

    record ErrorParams(TurnError error, String threadId, String turnId, Boolean willRetry)
            implements TurnScopedParams
    {
        ErrorParams
        {
            requireNonNull(error, "error is null");
            checkIdentifier(threadId, "threadId");
            checkIdentifier(turnId, "turnId");
            requireNonNull(willRetry, "willRetry is null");
        }
    }

    record UnknownParams(String threadId)
            implements Params
    {
        UnknownParams
        {
            // An unrecognized payload may omit the thread entirely.
            threadId = threadId == null ? "" : threadId;
        }
    }

    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            property = "type",
            defaultImpl = UnknownThreadItem.class)
    @JsonSubTypes({
            @JsonSubTypes.Type(name = "agentMessage", value = AgentMessageItem.class),
            @JsonSubTypes.Type(name = "commandExecution", value = CommandExecutionItem.class),
            @JsonSubTypes.Type(name = "fileChange", value = FileChangeItem.class),
            @JsonSubTypes.Type(name = "mcpToolCall", value = McpToolCallItem.class),
            @JsonSubTypes.Type(name = "dynamicToolCall", value = DynamicToolCallItem.class),
            @JsonSubTypes.Type(name = "collabAgentToolCall", value = CollabAgentToolCallItem.class),
            @JsonSubTypes.Type(name = "subAgentActivity", value = SubAgentActivityItem.class),
            @JsonSubTypes.Type(name = "reasoning", value = ReasoningItem.class),
            @JsonSubTypes.Type(name = "plan", value = PlanTextItem.class),
            @JsonSubTypes.Type(name = "webSearch", value = WebSearchItem.class),
            @JsonSubTypes.Type(name = "imageView", value = ImageViewItem.class),
            @JsonSubTypes.Type(name = "contextCompaction", value = ContextCompactionItem.class),
            @JsonSubTypes.Type(name = "userMessage", value = UserMessageItem.class),
    })
    sealed interface ThreadItem
            permits AgentMessageItem, CommandExecutionItem, FileChangeItem, McpToolCallItem,
            DynamicToolCallItem, CollabAgentToolCallItem, SubAgentActivityItem, ReasoningItem,
            PlanTextItem, WebSearchItem, ImageViewItem, ContextCompactionItem, UserMessageItem,
            UnknownThreadItem
    {
        String id();
    }

    record AgentMessageItem(
            String id,
            String text,
            Optional<MessagePhase> phase)
            implements ThreadItem
    {
        AgentMessageItem
        {
            checkIdentifier(id, "id");
            requireNonNull(text, "text is null");
            requireNonNull(phase, "phase is null");
        }
    }

    record CommandExecutionItem(
            String id,
            String command,
            ActivityStatus status,
            Optional<String> aggregatedOutput,
            Optional<Integer> exitCode,
            String cwd,
            Optional<Long> durationMs,
            List<CommandAction> commandActions)
            implements ThreadItem
    {
        CommandExecutionItem
        {
            checkIdentifier(id, "id");
            requireNonNull(command, "command is null");
            requireNonNull(status, "status is null");
            requireNonNull(aggregatedOutput, "aggregatedOutput is null");
            requireNonNull(exitCode, "exitCode is null");
            checkIdentifier(cwd, "cwd");
            requireNonNull(durationMs, "durationMs is null");
            durationMs.ifPresent(value -> checkArgument(value >= 0, "durationMs is negative"));
            commandActions = ImmutableList.copyOf(commandActions);
        }
    }

    record FileChangeItem(
            String id,
            List<FileUpdateChange> changes,
            ActivityStatus status)
            implements ThreadItem
    {
        FileChangeItem
        {
            checkIdentifier(id, "id");
            changes = ImmutableList.copyOf(changes);
            requireNonNull(status, "status is null");
        }
    }

    record FileUpdateChange(String path, FileChangeKind kind, String diff)
    {
        FileUpdateChange
        {
            checkIdentifier(path, "path");
            requireNonNull(kind, "kind is null");
            requireNonNull(diff, "diff is null");
        }
    }

    record FileChangeKind(String type, @JsonProperty("move_path") Optional<String> movePath)
    {
        FileChangeKind
        {
            checkIdentifier(type, "type");
            requireNonNull(movePath, "movePath is null");
        }
    }

    record McpToolCallItem(
            String id,
            String server,
            String tool,
            ActivityStatus status,
            Optional<Long> durationMs,
            Optional<JsonNode> result,
            Optional<JsonNode> error)
            implements ThreadItem
    {
        McpToolCallItem
        {
            checkIdentifier(id, "id");
            checkIdentifier(server, "server");
            checkIdentifier(tool, "tool");
            result = requireNonNull(result, "result is null").filter(value -> !value.isNull());
            error = requireNonNull(error, "error is null").filter(value -> !value.isNull());
            requireNonNull(status, "status is null");
            requireNonNull(durationMs, "durationMs is null");
            durationMs.ifPresent(value -> checkArgument(value >= 0, "durationMs is negative"));
        }
    }

    record DynamicToolCallItem(String id, String tool, ActivityStatus status)
            implements ThreadItem
    {
        DynamicToolCallItem
        {
            checkIdentifier(id, "id");
            checkIdentifier(tool, "tool");
            requireNonNull(status, "status is null");
        }
    }

    record CollabAgentToolCallItem(
            String id,
            String tool,
            ActivityStatus status,
            String senderThreadId,
            List<String> receiverThreadIds,
            Map<String, CollabAgentState> agentsStates,
            Optional<String> prompt)
            implements ThreadItem
    {
        CollabAgentToolCallItem
        {
            requireNonNull(prompt, "prompt is null");
            checkIdentifier(id, "id");
            checkIdentifier(tool, "tool");
            requireNonNull(status, "status is null");
            checkIdentifier(senderThreadId, "senderThreadId");
            receiverThreadIds = ImmutableList.copyOf(receiverThreadIds);
            receiverThreadIds.forEach(threadId -> checkIdentifier(threadId, "receiverThreadId"));
            agentsStates = ImmutableMap.copyOf(agentsStates);
            agentsStates.keySet().forEach(threadId -> checkIdentifier(threadId, "agentThreadId"));
        }
    }

    record CollabAgentState(CollabAgentStatus status, Optional<String> message)
    {
        CollabAgentState
        {
            requireNonNull(status, "status is null");
            requireNonNull(message, "message is null");
        }
    }

    record SubAgentActivityItem(
            String id,
            String agentThreadId,
            String agentPath,
            SubAgentActivityKind kind)
            implements ThreadItem
    {
        SubAgentActivityItem
        {
            checkIdentifier(id, "id");
            checkIdentifier(agentThreadId, "agentThreadId");
            checkIdentifier(agentPath, "agentPath");
            requireNonNull(kind, "kind is null");
        }
    }

    /**
     * App Server's best-effort reading of what a shell command does. One command can carry
     * several actions; the variants share {@code command} and differ only in which other fields
     * they fill, so one record with optional fields decodes every variant, known or not.
     */
    record CommandAction(
            String type,
            String command,
            Optional<String> name,
            Optional<String> path,
            Optional<String> query)
    {
        CommandAction
        {
            checkIdentifier(type, "type");
            requireNonNull(command, "command is null");
            requireNonNull(name, "name is null");
            requireNonNull(path, "path is null");
            requireNonNull(query, "query is null");
        }
    }

    /**
     * Readable reasoning summaries only. The wire item also carries hidden reasoning text under
     * {@code content}; it is never decoded, so it cannot reach a log or a screen.
     */
    record ReasoningItem(String id, Optional<List<String>> summary)
            implements ThreadItem
    {
        ReasoningItem
        {
            checkIdentifier(id, "id");
            summary = requireNonNull(summary, "summary is null").map(ImmutableList::copyOf);
        }
    }

    /** Free-form Plan-mode text; the structured steps arrive separately as turn/plan/updated. */
    record PlanTextItem(String id, String text)
            implements ThreadItem
    {
        PlanTextItem
        {
            checkIdentifier(id, "id");
            requireNonNull(text, "text is null");
        }
    }

    record WebSearchItem(String id, String query)
            implements ThreadItem
    {
        WebSearchItem
        {
            checkIdentifier(id, "id");
            requireNonNull(query, "query is null");
        }
    }

    record ImageViewItem(String id, String path)
            implements ThreadItem
    {
        ImageViewItem
        {
            checkIdentifier(id, "id");
            requireNonNull(path, "path is null");
        }
    }

    record ContextCompactionItem(String id)
            implements ThreadItem
    {
        ContextCompactionItem
        {
            checkIdentifier(id, "id");
        }
    }

    record UnknownThreadItem(String id)
            implements ThreadItem
    {
        UnknownThreadItem
        {
            checkIdentifier(id, "id");
        }
    }

    record Turn(
            String id,
            TurnStatus status,
            Optional<TurnError> error,
            Optional<Long> durationMs,
            List<ThreadItem> items)
    {
        Turn
        {
            checkIdentifier(id, "id");
            requireNonNull(status, "status is null");
            requireNonNull(error, "error is null");
            requireNonNull(durationMs, "durationMs is null");
            durationMs.ifPresent(value -> checkArgument(value >= 0, "durationMs is negative"));
            items = ImmutableList.copyOf(items);
        }

        boolean terminal()
        {
            return status == TurnStatus.COMPLETED || status == TurnStatus.FAILED || status == TurnStatus.INTERRUPTED;
        }
    }

    record TurnError(String message)
    {
        TurnError
        {
            requireNonNull(message, "message is null");
        }
    }

    record PlanStep(String step, PlanStepStatus status)
    {
        PlanStep
        {
            requireNonNull(step, "step is null");
            requireNonNull(status, "status is null");
        }
    }

    record ThreadTokenUsage(TokenUsageBreakdown total)
    {
        ThreadTokenUsage
        {
            requireNonNull(total, "total is null");
        }
    }

    record TokenUsageBreakdown(
            Long totalTokens,
            Long inputTokens,
            Long cachedInputTokens,
            Long outputTokens)
    {
        TokenUsageBreakdown
        {
            requireNonNull(totalTokens, "totalTokens is null");
            requireNonNull(inputTokens, "inputTokens is null");
            requireNonNull(cachedInputTokens, "cachedInputTokens is null");
            requireNonNull(outputTokens, "outputTokens is null");
            checkArgument(totalTokens >= 0, "totalTokens is negative");
            checkArgument(inputTokens >= 0, "inputTokens is negative");
            checkArgument(cachedInputTokens >= 0, "cachedInputTokens is negative");
            checkArgument(outputTokens >= 0, "outputTokens is negative");
        }
    }

    enum TurnStatus
    {
        @JsonProperty("completed")
        COMPLETED,
        @JsonProperty("interrupted")
        INTERRUPTED,
        @JsonProperty("failed")
        FAILED,
        @JsonProperty("inProgress")
        IN_PROGRESS,
        @JsonEnumDefaultValue
        UNKNOWN,
    }

    enum ActivityStatus
    {
        @JsonProperty("inProgress")
        IN_PROGRESS,
        @JsonProperty("completed")
        COMPLETED,
        @JsonProperty("failed")
        FAILED,
        @JsonProperty("declined")
        DECLINED,
        @JsonProperty("interrupted")
        INTERRUPTED,
        @JsonEnumDefaultValue
        UNKNOWN,
    }

    enum CollabAgentStatus
    {
        @JsonProperty("pendingInit")
        PENDING_INIT,
        @JsonProperty("running")
        RUNNING,
        @JsonProperty("interrupted")
        INTERRUPTED,
        @JsonProperty("completed")
        COMPLETED,
        @JsonProperty("errored")
        ERRORED,
        @JsonProperty("shutdown")
        SHUTDOWN,
        @JsonProperty("notFound")
        NOT_FOUND,
        @JsonEnumDefaultValue
        UNKNOWN,
    }

    enum SubAgentActivityKind
    {
        @JsonProperty("started")
        STARTED,
        @JsonProperty("interacted")
        INTERACTED,
        @JsonProperty("interrupted")
        INTERRUPTED,
        @JsonProperty("completed")
        COMPLETED,
        @JsonEnumDefaultValue
        UNKNOWN,
    }

    enum PlanStepStatus
    {
        @JsonProperty("pending")
        PENDING,
        @JsonProperty("inProgress")
        IN_PROGRESS,
        @JsonProperty("completed")
        COMPLETED,
        @JsonEnumDefaultValue
        UNKNOWN,
    }

    enum MessagePhase
    {
        @JsonProperty("commentary")
        COMMENTARY,
        @JsonProperty("final_answer")
        FINAL_ANSWER,
        @JsonEnumDefaultValue
        UNKNOWN,
    }

    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            property = "method",
            defaultImpl = UnknownServerRequest.class)
    @JsonSubTypes({
            @JsonSubTypes.Type(
                    name = "item/commandExecution/requestApproval",
                    value = CommandApprovalRequest.class),
            @JsonSubTypes.Type(
                    name = "item/fileChange/requestApproval",
                    value = FileChangeApprovalRequest.class),
            @JsonSubTypes.Type(
                    name = "item/permissions/requestApproval",
                    value = PermissionsApprovalRequest.class),
            @JsonSubTypes.Type(
                    name = "item/tool/call",
                    value = DynamicToolCallRequest.class),
    })
    sealed interface ServerRequest
            permits CommandApprovalRequest, FileChangeApprovalRequest, PermissionsApprovalRequest, DynamicToolCallRequest,
            UnknownServerRequest
    {
        Object id();

        Params params();

        default String threadId()
        {
            return params().threadId();
        }

        default Optional<String> turnId()
        {
            return params().owningTurnId();
        }
    }

    record CommandApprovalRequest(Object id, CommandApprovalParams params)
            implements ServerRequest
    {
        CommandApprovalRequest
        {
            requireNonNull(id, "id is null");
            requireNonNull(params, "params is null");
        }
    }

    record FileChangeApprovalRequest(Object id, FileChangeApprovalParams params)
            implements ServerRequest
    {
        FileChangeApprovalRequest
        {
            requireNonNull(id, "id is null");
            requireNonNull(params, "params is null");
        }
    }

    record PermissionsApprovalRequest(Object id, PermissionsApprovalParams params)
            implements ServerRequest
    {
        PermissionsApprovalRequest
        {
            requireNonNull(id, "id is null");
            requireNonNull(params, "params is null");
        }
    }

    record DynamicToolCallRequest(Object id, DynamicToolCallParams params)
            implements ServerRequest
    {
        DynamicToolCallRequest
        {
            requireNonNull(id, "id is null");
            requireNonNull(params, "params is null");
        }
    }

    record DynamicToolCallParams(
            String threadId, String turnId, String callId, String tool, Optional<String> namespace, JsonNode arguments)
            implements TurnScopedParams
    {
        DynamicToolCallParams
        {
            checkIdentifier(threadId, "threadId");
            checkIdentifier(turnId, "turnId");
            checkIdentifier(callId, "callId");
            checkIdentifier(tool, "tool");
            requireNonNull(namespace, "namespace is null");
            requireNonNull(arguments, "arguments is null");
            checkArgument(arguments.isObject(), "arguments must be an object");
        }
    }

    record DynamicToolCallResponse(List<DynamicToolTextContent> contentItems, boolean success)
    {
        DynamicToolCallResponse
        {
            contentItems = ImmutableList.copyOf(contentItems);
        }
    }

    record DynamicToolTextContent(String type, String text)
    {
        DynamicToolTextContent
        {
            checkIdentifier(type, "type");
            requireNonNull(text, "text is null");
        }
    }

    record UnknownServerRequest(Object id, UnknownParams params)
            implements ServerRequest
    {
        UnknownServerRequest
        {
            requireNonNull(id, "id is null");
            requireNonNull(params, "params is null");
        }
    }

    record CommandApprovalParams(
            Optional<JsonNode> additionalPermissions,
            Optional<String> approvalId,
            Optional<List<JsonNode>> availableDecisions,
            Optional<String> command,
            Optional<List<JsonNode>> commandActions,
            Optional<String> cwd,
            Optional<String> environmentId,
            String itemId,
            Optional<String> kind,
            Optional<JsonNode> networkApprovalContext,
            Optional<List<String>> proposedExecpolicyAmendment,
            Optional<List<JsonNode>> proposedNetworkPolicyAmendments,
            Optional<String> reason,
            Long startedAtMs,
            String threadId,
            String turnId)
            implements TurnScopedParams
    {
        CommandApprovalParams
        {
            additionalPermissions = requireNonNull(additionalPermissions, "additionalPermissions is null")
                    .filter(value -> !value.isNull() && !value.isMissingNode());
            requireNonNull(approvalId, "approvalId is null");
            availableDecisions = requireNonNull(availableDecisions, "availableDecisions is null").map(ImmutableList::copyOf);
            requireNonNull(command, "command is null");
            commandActions = requireNonNull(commandActions, "commandActions is null").map(ImmutableList::copyOf);
            requireNonNull(cwd, "cwd is null");
            requireNonNull(environmentId, "environmentId is null");
            checkIdentifier(itemId, "itemId");
            requireNonNull(kind, "kind is null");
            networkApprovalContext = requireNonNull(networkApprovalContext, "networkApprovalContext is null")
                    .filter(value -> !value.isNull() && !value.isMissingNode());
            proposedExecpolicyAmendment = requireNonNull(proposedExecpolicyAmendment, "proposedExecpolicyAmendment is null").map(ImmutableList::copyOf);
            proposedNetworkPolicyAmendments = requireNonNull(proposedNetworkPolicyAmendments, "proposedNetworkPolicyAmendments is null").map(ImmutableList::copyOf);
            requireNonNull(reason, "reason is null");
            requireNonNull(startedAtMs, "startedAtMs is null");
            checkIdentifier(threadId, "threadId");
            checkIdentifier(turnId, "turnId");
        }
    }

    record FileChangeApprovalParams(
            Optional<String> grantRoot,
            String itemId,
            Optional<String> reason,
            Long startedAtMs,
            String threadId,
            String turnId)
            implements TurnScopedParams
    {
        FileChangeApprovalParams
        {
            requireNonNull(grantRoot, "grantRoot is null");
            checkIdentifier(itemId, "itemId");
            requireNonNull(reason, "reason is null");
            requireNonNull(startedAtMs, "startedAtMs is null");
            checkIdentifier(threadId, "threadId");
            checkIdentifier(turnId, "turnId");
        }
    }

    record PermissionsApprovalParams(
            String cwd,
            Optional<String> environmentId,
            String itemId,
            JsonNode permissions,
            Optional<String> reason,
            Long startedAtMs,
            String threadId,
            String turnId)
            implements TurnScopedParams
    {
        PermissionsApprovalParams
        {
            checkIdentifier(cwd, "cwd");
            requireNonNull(environmentId, "environmentId is null");
            checkIdentifier(itemId, "itemId");
            requireNonNull(permissions, "permissions is null");
            checkArgument(!permissions.isNull(), "permissions is null");
            requireNonNull(reason, "reason is null");
            requireNonNull(startedAtMs, "startedAtMs is null");
            checkIdentifier(threadId, "threadId");
            checkIdentifier(turnId, "turnId");
        }
    }

    record JsonRpcEnvelope(
            Optional<Object> id,
            Optional<String> method,
            Optional<JsonNode> result,
            Optional<JsonRpcError> error)
    {
        JsonRpcEnvelope
        {
            requireNonNull(id, "id is null");
            requireNonNull(method, "method is null");
            requireNonNull(result, "result is null");
            requireNonNull(error, "error is null");
        }
    }

    record JsonRpcError(Integer code, String message)
    {
        JsonRpcError
        {
            requireNonNull(code, "code is null");
            requireNonNull(message, "message is null");
        }
    }

    record JsonRpcRequest(long id, String method, Object params)
    {
        JsonRpcRequest
        {
            checkArgument(id > 0, "id is not positive");
            checkIdentifier(method, "method");
            requireNonNull(params, "params is null");
        }
    }

    record JsonRpcNotification(String method, Object params)
    {
        JsonRpcNotification
        {
            checkIdentifier(method, "method");
            requireNonNull(params, "params is null");
        }
    }

    record JsonRpcResponse(Object id, Object result)
    {
        JsonRpcResponse
        {
            requireNonNull(id, "id is null");
            requireNonNull(result, "result is null");
        }
    }

    record JsonRpcFailure(Object id, JsonRpcError error)
    {
        JsonRpcFailure
        {
            requireNonNull(id, "id is null");
            requireNonNull(error, "error is null");
        }
    }

    record ClientInfo(String name, String title, String version)
    {
        ClientInfo
        {
            checkIdentifier(name, "name");
            checkIdentifier(title, "title");
            checkIdentifier(version, "version");
        }
    }

    record InitializeParams(ClientInfo clientInfo, InitializeCapabilities capabilities)
    {
        InitializeParams
        {
            requireNonNull(clientInfo, "clientInfo is null");
            requireNonNull(capabilities, "capabilities is null");
        }
    }

    /**
     * Client capabilities declared at initialize.
     *
     * <p>Declared true for exactly one reason: {@code thread/backgroundTerminals/list} is
     * experimental, and it is the only inventory that can prove a thread owns no live process.
     * Measured against codex-cli 0.151.0, the experimental surface is that method, its
     * {@code clean} and {@code terminate} siblings, and the environment family this client does
     * not use; every other method ByteQuay calls answers either way.
     */
    record InitializeCapabilities(boolean experimentalApi)
    {
    }

    record EmptyParams() {}

    /** Initialize result; only the isolated home the skill-origin guard needs is modelled. */
    record InitializeResponse(String codexHome)
    {
        InitializeResponse
        {
            checkIdentifier(codexHome, "codexHome");
        }
    }

    /** Selects the working directories a discovery request should report on. */
    record CwdsParams(List<String> cwds)
    {
        CwdsParams
        {
            cwds = ImmutableList.copyOf(cwds);
            checkArgument(!cwds.isEmpty(), "cwds is empty");
        }
    }

    /** Forces a fresh scan so a skill added by an earlier turn cannot hide behind the cache. */
    record SkillsListParams(List<String> cwds, boolean forceReload)
    {
        SkillsListParams
        {
            cwds = ImmutableList.copyOf(cwds);
            checkArgument(!cwds.isEmpty(), "cwds is empty");
        }
    }

    record SkillsListResponse(List<SkillsListEntry> data)
    {
        SkillsListResponse
        {
            data = ImmutableList.copyOf(data);
        }
    }

    record SkillsListEntry(String cwd, List<SkillMetadata> skills, List<Object> errors)
    {
        SkillsListEntry
        {
            checkIdentifier(cwd, "cwd");
            skills = ImmutableList.copyOf(skills);
            errors = ImmutableList.copyOf(errors);
        }
    }

    /**
     * Origin of one discovered skill.
     *
     * <p>Only the fields that decide whether a skill is an inherited capability are modelled: its
     * scope, its owning plugin, and where it was loaded from.
     */
    record SkillMetadata(String name, String path, String scope, Optional<String> pluginId)
    {
        SkillMetadata
        {
            checkIdentifier(name, "name");
            checkIdentifier(path, "path");
            checkIdentifier(scope, "scope");
            requireNonNull(pluginId, "pluginId is null");
        }
    }

    record ConfigReadParams(String cwd, boolean includeLayers)
    {
        ConfigReadParams
        {
            checkIdentifier(cwd, "cwd");
        }
    }

    record HooksListResponse(List<HooksListEntry> data)
    {
        HooksListResponse
        {
            data = ImmutableList.copyOf(data);
        }
    }

    /**
     * Hook discovery for one working directory.
     *
     * <p>Discovered hooks and discovery errors both fail the isolation check: a hook that applies is
     * unsafe, and a directory that could not be scanned is unproven.
     */
    record HooksListEntry(String cwd, List<JsonNode> hooks, List<Object> errors, List<String> warnings)
    {
        HooksListEntry
        {
            checkIdentifier(cwd, "cwd");
            hooks = ImmutableList.copyOf(hooks);
            errors = ImmutableList.copyOf(errors);
            warnings = ImmutableList.copyOf(warnings);
        }
    }

    record EmptyResponse() {}

    /**
     * Starts a thread in the App Server process that serves one task.
     *
     * <p>No execution environment is selected, so the thread uses the process default, which is
     * rooted at the process working directory. That directory is the task worktree, which is what
     * confines the turn.
     */
    record ThreadStartParams(
            String cwd,
            String model,
            String approvalPolicy,
            String approvalsReviewer,
            Map<String, Object> config,
            String sandbox,
            String developerInstructions,
            boolean ephemeral,
            String serviceName,
            List<Object> dynamicTools,
            List<Object> selectedCapabilityRoots)
    {
        ThreadStartParams
        {
            checkIdentifier(cwd, "cwd");
            checkIdentifier(model, "model");
            checkIdentifier(approvalPolicy, "approvalPolicy");
            checkIdentifier(approvalsReviewer, "approvalsReviewer");
            config = ImmutableMap.copyOf(config);
            checkIdentifier(sandbox, "sandbox");
            requireNonNull(developerInstructions, "developerInstructions is null");
            checkIdentifier(serviceName, "serviceName");
            dynamicTools = ImmutableList.copyOf(dynamicTools);
            selectedCapabilityRoots = ImmutableList.copyOf(selectedCapabilityRoots);
        }
    }

    record ThreadResumeParams(
            String threadId,
            String cwd,
            String model,
            String approvalPolicy,
            String approvalsReviewer,
            Map<String, Object> config,
            String sandbox,
            String developerInstructions)
    {
        ThreadResumeParams
        {
            checkIdentifier(threadId, "threadId");
            checkIdentifier(cwd, "cwd");
            checkIdentifier(model, "model");
            checkIdentifier(approvalPolicy, "approvalPolicy");
            checkIdentifier(approvalsReviewer, "approvalsReviewer");
            config = ImmutableMap.copyOf(config);
            checkIdentifier(sandbox, "sandbox");
            requireNonNull(developerInstructions, "developerInstructions is null");
        }
    }

    record ThreadResponse(TurnThread thread)
    {
        ThreadResponse
        {
            requireNonNull(thread, "thread is null");
        }
    }

    record TurnThread(String id)
    {
        TurnThread
        {
            checkIdentifier(id, "id");
        }
    }

    record ThreadReadParams(
            String threadId,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            Optional<Boolean> includeTurns)
    {
        ThreadReadParams
        {
            checkIdentifier(threadId, "threadId");
            requireNonNull(includeTurns, "includeTurns is null");
        }
    }

    record ThreadTurnsListParams(String threadId, int limit, String sortDirection, String itemsView)
    {
        ThreadTurnsListParams
        {
            checkIdentifier(threadId, "threadId");
            checkArgument(limit > 0, "limit must be positive");
            checkIdentifier(sortDirection, "sortDirection");
            checkIdentifier(itemsView, "itemsView");
        }
    }

    record ThreadTurnsListResponse(List<Turn> data)
    {
        ThreadTurnsListResponse
        {
            data = ImmutableList.copyOf(data);
        }
    }

    /**
     * Lists one thread's background terminals, a page at a time.
     *
     * <p>A yielded command keeps running after the item that started it reports terminal, so this
     * is the only inventory that can say whether a thread still owns a live process.
     */
    record ThreadBackgroundTerminalsListParams(
            String threadId,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            Optional<String> cursor)
    {
        ThreadBackgroundTerminalsListParams
        {
            checkIdentifier(threadId, "threadId");
            requireNonNull(cursor, "cursor is null");
        }
    }

    record ThreadBackgroundTerminalsListResponse(
            List<ThreadBackgroundTerminal> data,
            Optional<String> nextCursor)
    {
        ThreadBackgroundTerminalsListResponse
        {
            data = ImmutableList.copyOf(data);
            requireNonNull(nextCursor, "nextCursor is null");
        }
    }

    record ThreadListParams(
            String parentThreadId,
            List<String> sourceKinds,
            List<String> modelProviders,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            Optional<String> cursor)
    {
        ThreadListParams
        {
            checkIdentifier(parentThreadId, "parentThreadId");
            sourceKinds = ImmutableList.copyOf(sourceKinds);
            modelProviders = ImmutableList.copyOf(modelProviders);
            requireNonNull(cursor, "cursor is null");
        }
    }

    record ThreadListResponse(List<HistoryThread> data, Optional<String> nextCursor)
    {
        ThreadListResponse
        {
            data = ImmutableList.copyOf(data);
            requireNonNull(nextCursor, "nextCursor is null");
        }
    }

    /** Stops one background terminal, so a cancelled turn does not wait on work nobody wants. */
    record ThreadBackgroundTerminalsTerminateParams(String threadId, String processId)
    {
        ThreadBackgroundTerminalsTerminateParams
        {
            checkIdentifier(threadId, "threadId");
            checkIdentifier(processId, "processId");
        }
    }

    record ThreadBackgroundTerminal(String processId)
    {
        ThreadBackgroundTerminal
        {
            checkIdentifier(processId, "processId");
        }
    }

    record ThreadReadResponse(HistoryThread thread)
    {
        ThreadReadResponse
        {
            requireNonNull(thread, "thread is null");
        }
    }

    record HistoryThread(
            String id,
            Optional<String> parentThreadId,
            ThreadStatus status,
            Optional<String> agentNickname,
            Optional<String> agentRole)
    {
        HistoryThread
        {
            checkIdentifier(id, "id");
            requireNonNull(parentThreadId, "parentThreadId is null");
            parentThreadId.ifPresent(parent -> checkIdentifier(parent, "parentThreadId"));
            requireNonNull(status, "status is null");
            requireNonNull(agentNickname, "agentNickname is null");
            requireNonNull(agentRole, "agentRole is null");
        }
    }

    record ThreadStatus(String type)
    {
        ThreadStatus
        {
            checkIdentifier(type, "type");
        }
    }

    record TextInput(String type, String text)
            implements UserInput
    {
        TextInput
        {
            checkIdentifier(type, "type");
            requireNonNull(text, "text is null");
        }
    }

    /**
     * Starts a turn, carrying the model settings snapshotted when it was accepted.
     */
    /**
     * One item of a turn's input: the prompt text, or a validated task-owned image by path.
     * Decoding also meets the inputs a subagent's own prompt was given, which may be of kinds
     * this product never sends; those decode to their type alone.
     */
    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "type",
            visible = true,
            defaultImpl = OtherUserInput.class)
    @JsonSubTypes({
            @JsonSubTypes.Type(name = "text", value = TextInput.class),
            @JsonSubTypes.Type(name = "localImage", value = LocalImageInput.class),
    })
    sealed interface UserInput
            permits TextInput, LocalImageInput, OtherUserInput
    {
    }

    record OtherUserInput(String type)
            implements UserInput
    {
        OtherUserInput
        {
            checkIdentifier(type, "type");
        }
    }

    /** The message a thread was given; on a subagent it is the prompt its parent spawned it with. */
    record UserMessageItem(String id, List<UserInput> content)
            implements ThreadItem
    {
        UserMessageItem
        {
            checkIdentifier(id, "id");
            content = ImmutableList.copyOf(content);
        }

        /** The prompt's text parts, in order; images and other inputs are not text. */
        public String text()
        {
            return content.stream()
                    .filter(TextInput.class::isInstance)
                    .map(input -> ((TextInput) input).text())
                    .collect(joining("\n"));
        }
    }

    record LocalImageInput(String type, String path)
            implements UserInput
    {
        LocalImageInput
        {
            checkIdentifier(type, "type");
            checkIdentifier(path, "path");
        }
    }

    /**
     * Adds input to the turn that is running now. The turn id is a precondition: App Server
     * refuses the request when that turn is no longer the active one, so input meant for one turn
     * can never land in the next.
     */
    record TurnSteerParams(String threadId, String expectedTurnId, List<UserInput> input)
    {
        TurnSteerParams
        {
            checkIdentifier(threadId, "threadId");
            checkIdentifier(expectedTurnId, "expectedTurnId");
            input = ImmutableList.copyOf(input);
            checkArgument(!input.isEmpty(), "input is empty");
        }
    }

    record TurnSteerResponse(String turnId)
    {
        TurnSteerResponse
        {
            checkIdentifier(turnId, "turnId");
        }
    }

    record TurnStartParams(
            String threadId,
            List<UserInput> input,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            Optional<String> model,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            Optional<String> effort,
            String serviceTierForTurn,
            String approvalPolicy,
            String approvalsReviewer,
            String cwd,
            Map<String, Object> sandboxPolicy)
    {
        TurnStartParams
        {
            checkIdentifier(threadId, "threadId");
            input = ImmutableList.copyOf(input);
            checkArgument(!input.isEmpty(), "input is empty");
            requireNonNull(model, "model is null");
            requireNonNull(effort, "effort is null");
            checkIdentifier(serviceTierForTurn, "serviceTierForTurn");
            checkIdentifier(approvalPolicy, "approvalPolicy");
            checkIdentifier(approvalsReviewer, "approvalsReviewer");
            checkIdentifier(cwd, "cwd");
            sandboxPolicy = ImmutableMap.copyOf(sandboxPolicy);
        }
    }

    record TurnStartResponse(Turn turn)
    {
        TurnStartResponse
        {
            requireNonNull(turn, "turn is null");
        }
    }

    record ThreadParams(String threadId)
    {
        ThreadParams
        {
            checkIdentifier(threadId, "threadId");
        }
    }

    record TurnParams(String threadId, String turnId)
    {
        TurnParams
        {
            checkIdentifier(threadId, "threadId");
            checkIdentifier(turnId, "turnId");
        }
    }

    record CommandApprovalResponse(JsonNode decision)
    {
        CommandApprovalResponse
        {
            requireNonNull(decision, "decision is null");
            checkArgument(!decision.isNull(), "decision is null");
        }
    }

    record FileChangeApprovalResponse(JsonNode decision)
    {
        FileChangeApprovalResponse
        {
            requireNonNull(decision, "decision is null");
            checkArgument(!decision.isNull(), "decision is null");
        }
    }

    record PermissionsApprovalResponse(
            JsonNode permissions,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            Optional<String> scope,
            Optional<Boolean> strictAutoReview)
    {
        PermissionsApprovalResponse
        {
            requireNonNull(permissions, "permissions is null");
            checkArgument(!permissions.isNull(), "permissions is null");
            requireNonNull(scope, "scope is null");
            requireNonNull(strictAutoReview, "strictAutoReview is null");
        }
    }

    record ModelListParams() {}

    record ModelListResponse(List<ModelEntry> data)
    {
        ModelListResponse
        {
            data = ImmutableList.copyOf(data);
        }
    }

    record ModelEntry(
            String id,
            String displayName,
            String description,
            Boolean hidden,
            Boolean isDefault,
            String defaultReasoningEffort,
            List<ReasoningEffortOption> supportedReasoningEfforts,
            Optional<MultiAgentVersion> multiAgentVersion,
            Optional<List<String>> inputModalities,
            Optional<List<ModelServiceTier>> serviceTiers)
    {
        ModelEntry
        {
            inputModalities = requireNonNull(inputModalities, "inputModalities is null").map(ImmutableList::copyOf);
            serviceTiers = requireNonNull(serviceTiers, "serviceTiers is null").map(ImmutableList::copyOf);
            checkIdentifier(id, "id");
            checkIdentifier(displayName, "displayName");
            requireNonNull(description, "description is null");
            requireNonNull(hidden, "hidden is null");
            requireNonNull(isDefault, "isDefault is null");
            requireNonNull(defaultReasoningEffort, "defaultReasoningEffort is null");
            supportedReasoningEfforts = ImmutableList.copyOf(supportedReasoningEfforts);
            requireNonNull(multiAgentVersion, "multiAgentVersion is null");
        }
    }

    record ModelServiceTier(String id, String name, String description)
    {
        ModelServiceTier
        {
            checkIdentifier(id, "id");
            checkIdentifier(name, "name");
            requireNonNull(description, "description is null");
        }
    }

    enum MultiAgentVersion
    {
        @JsonProperty("disabled")
        DISABLED,
        @JsonProperty("v1")
        V1,
        @JsonProperty("v2")
        V2,
        @JsonEnumDefaultValue
        UNKNOWN,
    }

    record ReasoningEffortOption(String reasoningEffort)
    {
        ReasoningEffortOption
        {
            checkIdentifier(reasoningEffort, "reasoningEffort");
        }
    }

    private static void checkIdentifier(String value, String name)
    {
        requireNonNull(value, name + " is null");
        checkArgument(!value.isBlank(), name + " is blank");
    }
}
