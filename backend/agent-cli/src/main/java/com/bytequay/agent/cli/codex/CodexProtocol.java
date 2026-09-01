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
            @JsonSubTypes.Type(
                    name = "item/commandExecution/outputDelta",
                    value = CommandOutputNotification.class),
            @JsonSubTypes.Type(
                    name = "item/agentMessage/delta",
                    value = AgentMessageDeltaNotification.class),
            @JsonSubTypes.Type(name = "turn/plan/updated", value = PlanUpdatedNotification.class),
            @JsonSubTypes.Type(
                    name = "thread/tokenUsage/updated",
                    value = TokenUsageUpdatedNotification.class),
            @JsonSubTypes.Type(name = "error", value = ErrorNotification.class),
    })
    sealed interface Notification
            permits TurnStartedNotification, TurnCompletedNotification,
            ItemStartedNotification, ItemCompletedNotification,
            CommandOutputNotification, AgentMessageDeltaNotification, PlanUpdatedNotification,
            TokenUsageUpdatedNotification, ErrorNotification, UnknownNotification
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
            permits TurnNotificationParams, ItemNotificationParams, ItemDeltaParams, PlanUpdatedParams,
            TokenUsageParams, ErrorParams, UnknownParams, CommandApprovalParams, FileChangeApprovalParams,
            PermissionsApprovalParams
    {
        String threadId();

        /** The owning turn, absent for thread-scoped payloads. */
        default Optional<String> owningTurnId()
        {
            return Optional.empty();
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

    record AgentMessageDeltaNotification(ItemDeltaParams params, Optional<Long> emittedAtMs)
            implements Notification
    {
        AgentMessageDeltaNotification
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
            implements Params
    {
        ItemNotificationParams
        {
            requireNonNull(item, "item is null");
            checkIdentifier(threadId, "threadId");
            checkIdentifier(turnId, "turnId");
        }

        @Override
        public Optional<String> owningTurnId()
        {
            return Optional.of(turnId);
        }
    }

    record ItemDeltaParams(String threadId, String turnId, String itemId, String delta)
            implements Params
    {
        ItemDeltaParams
        {
            checkIdentifier(threadId, "threadId");
            checkIdentifier(turnId, "turnId");
            checkIdentifier(itemId, "itemId");
            requireNonNull(delta, "delta is null");
        }

        @Override
        public Optional<String> owningTurnId()
        {
            return Optional.of(turnId);
        }
    }

    record PlanUpdatedParams(List<PlanStep> plan, String threadId, String turnId)
            implements Params
    {
        PlanUpdatedParams
        {
            plan = ImmutableList.copyOf(requireNonNull(plan, "plan is null"));
            checkIdentifier(threadId, "threadId");
            checkIdentifier(turnId, "turnId");
        }

        @Override
        public Optional<String> owningTurnId()
        {
            return Optional.of(turnId);
        }
    }

    record TokenUsageParams(String threadId, String turnId, ThreadTokenUsage tokenUsage)
            implements Params
    {
        TokenUsageParams
        {
            checkIdentifier(threadId, "threadId");
            checkIdentifier(turnId, "turnId");
            requireNonNull(tokenUsage, "tokenUsage is null");
        }

        @Override
        public Optional<String> owningTurnId()
        {
            return Optional.of(turnId);
        }
    }

    record ErrorParams(TurnError error, String threadId, String turnId, Boolean willRetry)
            implements Params
    {
        ErrorParams
        {
            requireNonNull(error, "error is null");
            checkIdentifier(threadId, "threadId");
            checkIdentifier(turnId, "turnId");
            requireNonNull(willRetry, "willRetry is null");
        }

        @Override
        public Optional<String> owningTurnId()
        {
            return Optional.of(turnId);
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
    })
    sealed interface ThreadItem
            permits AgentMessageItem, CommandExecutionItem, FileChangeItem, McpToolCallItem,
            DynamicToolCallItem, CollabAgentToolCallItem, SubAgentActivityItem, UnknownThreadItem
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
            Optional<Integer> exitCode)
            implements ThreadItem
    {
        CommandExecutionItem
        {
            checkIdentifier(id, "id");
            requireNonNull(command, "command is null");
            requireNonNull(status, "status is null");
            requireNonNull(aggregatedOutput, "aggregatedOutput is null");
            requireNonNull(exitCode, "exitCode is null");
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
            changes = ImmutableList.copyOf(requireNonNull(changes, "changes is null"));
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

    record McpToolCallItem(String id, String server, String tool, ActivityStatus status)
            implements ThreadItem
    {
        McpToolCallItem
        {
            checkIdentifier(id, "id");
            checkIdentifier(server, "server");
            checkIdentifier(tool, "tool");
            requireNonNull(status, "status is null");
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
            Map<String, CollabAgentState> agentsStates)
            implements ThreadItem
    {
        CollabAgentToolCallItem
        {
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
    })
    sealed interface ServerRequest
            permits CommandApprovalRequest, FileChangeApprovalRequest, PermissionsApprovalRequest,
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
            implements Params
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

        @Override
        public Optional<String> owningTurnId()
        {
            return Optional.of(turnId);
        }
    }

    record FileChangeApprovalParams(
            Optional<String> grantRoot,
            String itemId,
            Optional<String> reason,
            Long startedAtMs,
            String threadId,
            String turnId)
            implements Params
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

        @Override
        public Optional<String> owningTurnId()
        {
            return Optional.of(turnId);
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
            implements Params
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

        @Override
        public Optional<String> owningTurnId()
        {
            return Optional.of(turnId);
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
     * <p>App Server refuses every environment method with {@code -32600} unless
     * {@code experimentalApi} is declared here, so a task-owned execution environment is
     * unreachable without it.
     */
    record InitializeCapabilities(boolean experimentalApi)
    {
    }

    record EmptyParams() {}

    /** Selects the working directories a discovery request should report on. */
    record CwdsParams(List<String> cwds)
    {
        CwdsParams
        {
            cwds = ImmutableList.copyOf(cwds);
            checkArgument(!cwds.isEmpty(), "cwds is empty");
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
    record HooksListEntry(String cwd, List<Object> hooks, List<Object> errors, List<String> warnings)
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
     * Starts a thread, optionally pinning it to one task-owned execution environment.
     *
     * <p>{@code environments} is three-valued on the wire and the three values are not
     * interchangeable: omitted selects App Server's default environment, an empty list disables
     * environment access entirely, and a non-empty list selects its first entry. It is therefore
     * modelled as an absent-or-present list rather than a plain collection.
     */
    record ThreadStartParams(
            String cwd,
            String approvalPolicy,
            String sandbox,
            String serviceName,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            Optional<List<TurnEnvironmentParams>> environments)
    {
        ThreadStartParams
        {
            checkIdentifier(cwd, "cwd");
            checkIdentifier(approvalPolicy, "approvalPolicy");
            checkIdentifier(sandbox, "sandbox");
            checkIdentifier(serviceName, "serviceName");
            environments = requireNonNull(environments, "environments is null").map(ImmutableList::copyOf);
        }
    }

    /**
     * Selects exactly one task-owned execution environment.
     *
     * <p>A non-empty list selects its first entry, so ByteQuay always sends exactly one. Sending
     * none would silently fall back to App Server's default environment, which is never correct for
     * a task-bound thread.
     */
    record TurnEnvironmentParams(String environmentId, String cwd, List<String> runtimeWorkspaceRoots)
    {
        TurnEnvironmentParams
        {
            checkIdentifier(environmentId, "environmentId");
            checkIdentifier(cwd, "cwd");
            runtimeWorkspaceRoots = ImmutableList.copyOf(runtimeWorkspaceRoots);
        }
    }

    record EnvironmentAddParams(String environmentId, String execServerUrl, Long connectTimeoutMs)
    {
        EnvironmentAddParams
        {
            checkIdentifier(environmentId, "environmentId");
            checkIdentifier(execServerUrl, "execServerUrl");
        }
    }

    record EnvironmentInfoParams(String environmentId)
    {
        EnvironmentInfoParams
        {
            checkIdentifier(environmentId, "environmentId");
        }
    }

    record EnvironmentInfoResponse(EnvironmentShellInfo shell, Optional<String> cwd)
    {
        EnvironmentInfoResponse
        {
            requireNonNull(shell, "shell is null");
            requireNonNull(cwd, "cwd is null");
        }
    }

    record EnvironmentShellInfo(String name, String path)
    {
        EnvironmentShellInfo
        {
            checkIdentifier(name, "name");
            checkIdentifier(path, "path");
        }
    }

    record ThreadResumeParams(String threadId, String cwd, String approvalPolicy, String sandbox)
    {
        ThreadResumeParams
        {
            checkIdentifier(threadId, "threadId");
            checkIdentifier(cwd, "cwd");
            checkIdentifier(approvalPolicy, "approvalPolicy");
            checkIdentifier(sandbox, "sandbox");
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

    record ThreadReadResponse(HistoryThread thread)
    {
        ThreadReadResponse
        {
            requireNonNull(thread, "thread is null");
        }
    }

    record HistoryThread(
            String id,
            String sessionId,
            Optional<String> parentThreadId,
            List<Turn> turns)
    {
        HistoryThread
        {
            checkIdentifier(id, "id");
            checkIdentifier(sessionId, "sessionId");
            requireNonNull(parentThreadId, "parentThreadId is null");
            parentThreadId.ifPresent(parent -> checkIdentifier(parent, "parentThreadId"));
            turns = ImmutableList.copyOf(turns);
        }
    }

    record TextInput(String type, String text)
    {
        TextInput
        {
            checkIdentifier(type, "type");
            requireNonNull(text, "text is null");
        }
    }

    /**
     * Starts a turn, selecting the task environment for this turn and the ones after it.
     *
     * <p>{@code environments} carries the same three-valued rule as {@link ThreadStartParams}:
     * omitted keeps the thread's sticky selection, empty disables environment access for the turn,
     * and non-empty selects its first entry.
     */
    record TurnStartParams(
            String threadId,
            List<TextInput> input,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            Optional<List<TurnEnvironmentParams>> environments,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            Optional<String> model,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            Optional<String> effort)
    {
        TurnStartParams
        {
            checkIdentifier(threadId, "threadId");
            input = ImmutableList.copyOf(requireNonNull(input, "input is null"));
            checkArgument(!input.isEmpty(), "input is empty");
            environments = requireNonNull(environments, "environments is null").map(ImmutableList::copyOf);
            requireNonNull(model, "model is null");
            requireNonNull(effort, "effort is null");
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
            data = ImmutableList.copyOf(requireNonNull(data, "data is null"));
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
            Optional<MultiAgentVersion> multiAgentVersion)
    {
        ModelEntry
        {
            checkIdentifier(id, "id");
            checkIdentifier(displayName, "displayName");
            requireNonNull(description, "description is null");
            requireNonNull(hidden, "hidden is null");
            requireNonNull(isDefault, "isDefault is null");
            requireNonNull(defaultReasoningEffort, "defaultReasoningEffort is null");
            supportedReasoningEfforts = ImmutableList.copyOf(
                    requireNonNull(supportedReasoningEfforts, "supportedReasoningEfforts is null"));
            requireNonNull(multiAgentVersion, "multiAgentVersion is null");
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
