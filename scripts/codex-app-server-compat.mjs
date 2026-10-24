#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  existsSync,
  mkdtempSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  realpathSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, isAbsolute, join, relative, sep } from "node:path";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";

const contract = {
  version: "0.159.3",
  schemaCommand:
    "codex app-server generate-json-schema --experimental --out <temporary-directory>",
  experimentalBundleSha256:
    "7243ba241962af92ca60581f1a81808ebda4212a800f8b205f54703bcfd508c5",
  experimentalV2BundleSha256:
    "e77b7d1436a78f431a74b2cb263a862e92ae40d70411bc63835b47ab2168827c",
  stableBundleSha256:
    "8683f6fd9fbb994fa2bfb704ab5b02c9890238fa507a4c0ff33ff45884cd0a2e",
  stableV2BundleSha256:
    "81a88c04ae4984b16d73080f4109d0477682bc76c8adbe483e371175ce54c054",
};

const referenceBindingsPath = fileURLToPath(new URL(
  "../backend/agent-cli/src/test/resources/com/bytequay/agent/cli/codex/app-server-v0.159.3-bindings.json",
  import.meta.url,
));

function schemaSource(bundle, definition, discriminatorField, discriminatorValue) {
  return {
    bundle,
    definition,
    ...(discriminatorField ? { discriminatorField, discriminatorValue } : {}),
  };
}

function mergedSchemaSource(bundle, definition, variantField) {
  return { bundle, definition, mergeVariants: true, variantField };
}

function component(javaName, wireName = javaName, sample) {
  return { javaName, wireName, sample };
}

function sampledComponent(javaName, wireName = javaName) {
  return { javaName, wireName, deriveSamples: true };
}

function sampledArrayComponent(javaName, wireName = javaName) {
  return { javaName, wireName, deriveItemSamples: true };
}

function recordBinding(javaType, mode, sources, components) {
  return { javaType, mode, sources, components };
}

const referenceRecordBindings = [
  recordBinding("TurnStartedNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "turn/started"),
  ], [component("params")]),
  recordBinding("TurnCompletedNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "turn/completed"),
  ], [component("params")]),
  recordBinding("ItemStartedNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "item/started"),
  ], [component("params")]),
  recordBinding("ItemCompletedNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "item/completed"),
  ], [component("params")]),
  recordBinding("CommandOutputNotification", "decode", [
    schemaSource(
      "v2",
      "ServerNotification",
      "method",
      "item/commandExecution/outputDelta",
    ),
  ], [component("params")]),
  recordBinding("AgentMessageDeltaNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "item/agentMessage/delta"),
  ], [component("params")]),
  recordBinding("PlanDeltaNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "item/plan/delta"),
  ], [component("params")]),
  recordBinding("ThreadStartedNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "thread/started"),
  ], [component("params")]),
  recordBinding("ThreadStartedParams", "decode", [
    schemaSource("v2", "ThreadStartedNotification"),
  ], [component("thread")]),
  recordBinding("ServerRequestResolvedNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "serverRequest/resolved"),
  ], [component("params")]),
  recordBinding("ServerRequestResolvedParams", "decode", [
    schemaSource("v2", "ServerRequestResolvedNotification"),
  ], [component("threadId"), component("requestId", "requestId", 1)]),
  recordBinding("PlanUpdatedNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "turn/plan/updated"),
  ], [component("params")]),
  recordBinding("TokenUsageUpdatedNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "thread/tokenUsage/updated"),
  ], [component("params")]),
  recordBinding("ErrorNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "error"),
  ], [component("params")]),
  recordBinding("AutoApprovalReviewStartedNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "item/autoApprovalReview/started"),
  ], [component("params")]),
  recordBinding("AutoApprovalReviewCompletedNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "item/autoApprovalReview/completed"),
  ], [component("params")]),
  recordBinding("AutoApprovalReviewParams", "decode", [
    schemaSource("v2", "ItemGuardianApprovalReviewStartedNotification"),
    schemaSource("v2", "ItemGuardianApprovalReviewCompletedNotification"),
  ], [component("threadId"), component("turnId"), component("reviewId"), component("review")]),
  recordBinding("AutoApprovalReview", "decode", [
    schemaSource("v2", "GuardianApprovalReview"),
  ], [component("status"), component("riskLevel"), component("rationale")]),
  recordBinding("TurnNotificationParams", "decode", [
    schemaSource("v2", "TurnStartedNotification"),
    schemaSource("v2", "TurnCompletedNotification"),
  ], [component("threadId"), component("turn")]),
  recordBinding("ItemNotificationParams", "decode", [
    schemaSource("v2", "ItemStartedNotification"),
    schemaSource("v2", "ItemCompletedNotification"),
  ], [component("item"), component("threadId"), component("turnId")]),
  recordBinding("ItemDeltaParams", "decode", [
    schemaSource("v2", "CommandExecutionOutputDeltaNotification"),
    schemaSource("v2", "AgentMessageDeltaNotification"),
    schemaSource("v2", "PlanDeltaNotification"),
  ], [
    component("threadId"),
    component("turnId"),
    component("itemId"),
    component("delta"),
  ]),
  recordBinding("PlanUpdatedParams", "decode", [
    schemaSource("v2", "TurnPlanUpdatedNotification"),
  ], [component("plan"), component("threadId"), component("turnId")]),
  recordBinding("TokenUsageParams", "decode", [
    schemaSource("v2", "ThreadTokenUsageUpdatedNotification"),
  ], [component("threadId"), component("turnId"), component("tokenUsage")]),
  recordBinding("ErrorParams", "decode", [schemaSource("v2", "ErrorNotification")], [
    component("error"),
    component("threadId"),
    component("turnId"),
    component("willRetry"),
  ]),
  recordBinding("AgentMessageItem", "decode", [
    schemaSource("v2", "ThreadItem", "type", "agentMessage"),
  ], [component("id"), component("text"), component("phase")]),
  recordBinding("CommandExecutionItem", "decode", [
    schemaSource("v2", "ThreadItem", "type", "commandExecution"),
  ], [
    component("id"),
    component("command"),
    component("status"),
    component("aggregatedOutput"),
    component("exitCode"),
    component("cwd"),
    component("durationMs"),
    component("commandActions"),
  ]),
  recordBinding("CommandAction", "decode", [
    mergedSchemaSource("v2", "CommandAction", "type"),
  ], [
    component("type"),
    component("command"),
    component("name"),
    component("path"),
    component("query"),
  ]),
  recordBinding("FileChangeItem", "decode", [
    schemaSource("v2", "ThreadItem", "type", "fileChange"),
  ], [component("id"), component("changes"), component("status")]),
  recordBinding("McpToolCallItem", "decode", [
    schemaSource("v2", "ThreadItem", "type", "mcpToolCall"),
  ], [
    component("id"),
    component("server"),
    component("tool"),
    component("arguments"),
    component("status"),
    component("durationMs"),
    component("result"),
    component("error"),
  ]),
  recordBinding("DynamicToolCallItem", "decode", [
    schemaSource("v2", "ThreadItem", "type", "dynamicToolCall"),
  ], [component("id"), component("tool"), component("arguments"), component("status"),
    component("success"), component("contentItems")]),
  recordBinding("CollabAgentToolCallItem", "decode", [
    schemaSource("v2", "ThreadItem", "type", "collabAgentToolCall"),
  ], [
    component("id"),
    component("tool"),
    component("status"),
    component("senderThreadId"),
    component("receiverThreadIds", "receiverThreadIds", ["child-thread"]),
    component("agentsStates", "agentsStates", {
      "child-thread": { status: "running" },
    }),
    component("prompt"),
  ]),
  recordBinding("CollabAgentState", "decode", [
    schemaSource("v2", "CollabAgentState"),
  ], [component("status"), component("message")]),
  recordBinding("SubAgentActivityItem", "decode", [
    schemaSource("v2", "ThreadItem", "type", "subAgentActivity"),
  ], [
    component("id"),
    component("agentThreadId"),
    component("agentPath"),
    component("kind"),
  ]),
  recordBinding("UserMessageItem", "decode", [
    schemaSource("v2", "ThreadItem", "type", "userMessage"),
  ], [component("id"), component("content")]),
  recordBinding("ReasoningItem", "decode", [
    schemaSource("v2", "ThreadItem", "type", "reasoning"),
  ], [component("id"), component("summary")]),
  recordBinding("PlanTextItem", "decode", [
    schemaSource("v2", "ThreadItem", "type", "plan"),
  ], [component("id"), component("text")]),
  recordBinding("WebSearchItem", "decode", [
    schemaSource("v2", "ThreadItem", "type", "webSearch"),
  ], [component("id"), component("query"), component("action")]),
  recordBinding("WebSearchAction", "decode", [
    mergedSchemaSource("v2", "WebSearchAction", "type"),
  ], [component("type"), component("url"), component("pattern")]),
  recordBinding("ImageViewItem", "decode", [
    schemaSource("v2", "ThreadItem", "type", "imageView"),
  ], [component("id"), component("path")]),
  recordBinding("ContextCompactionItem", "decode", [
    schemaSource("v2", "ThreadItem", "type", "contextCompaction"),
  ], [component("id")]),
  recordBinding("FileUpdateChange", "decode", [schemaSource("v2", "FileUpdateChange")], [
    component("path"),
    component("kind"),
    component("diff"),
  ]),
  recordBinding("FileChangeKind", "decode", [
    mergedSchemaSource("v2", "PatchChangeKind", "type"),
  ], [component("type"), component("movePath", "move_path")]),
  recordBinding("Turn", "decode", [schemaSource("v2", "Turn")], [
    component("id"),
    component("status"),
    component("error"),
    component("durationMs"),
    component("items"),
  ]),
  recordBinding("TurnError", "decode", [schemaSource("v2", "TurnError")], [
    component("message"),
  ]),
  recordBinding("PlanStep", "decode", [schemaSource("v2", "TurnPlanStep")], [
    component("step"),
    component("status"),
  ]),
  recordBinding("ThreadTokenUsage", "decode", [
    schemaSource("v2", "ThreadTokenUsage"),
  ], [component("total"), component("last")]),
  recordBinding("TokenUsageBreakdown", "decode", [
    schemaSource("v2", "TokenUsageBreakdown"),
  ], [
    component("totalTokens"),
    component("inputTokens"),
    component("cachedInputTokens"),
    component("outputTokens"),
  ]),
  recordBinding("CommandApprovalRequest", "decode", [
    schemaSource("all", "ServerRequest", "method", "item/commandExecution/requestApproval"),
  ], [component("id"), component("params")]),
  recordBinding("FileChangeApprovalRequest", "decode", [
    schemaSource("all", "ServerRequest", "method", "item/fileChange/requestApproval"),
  ], [component("id"), component("params")]),
  recordBinding("PermissionsApprovalRequest", "decode", [
    schemaSource("all", "ServerRequest", "method", "item/permissions/requestApproval"),
  ], [component("id"), component("params")]),
  recordBinding("DynamicToolCallRequest", "decode", [
    schemaSource("all", "ServerRequest", "method", "item/tool/call"),
  ], [component("id"), component("params", "params", {
    threadId: "root-thread", turnId: "root-turn", callId: "tool-call", tool: "review_finish", arguments: {},
  })]),
  recordBinding("DynamicToolCallParams", "decode", [
    schemaSource("all", "DynamicToolCallParams"),
  ], [
    component("threadId"),
    component("turnId"),
    component("callId"),
    component("tool"),
    component("namespace"),
    component("arguments", "arguments", {}),
  ]),
  recordBinding("CommandApprovalParams", "decode", [
    schemaSource("all", "CommandExecutionRequestApprovalParams"),
  ], [
    component("additionalPermissions"),
    component("approvalId"),
    sampledArrayComponent("availableDecisions"),
    component("command"),
    component("commandActions"),
    component("cwd"),
    component("environmentId"),
    component("itemId"),
    component("kind"),
    component("networkApprovalContext"),
    component("proposedExecpolicyAmendment"),
    component("proposedNetworkPolicyAmendments"),
    component("reason"),
    component("startedAtMs"),
    component("threadId"),
    component("turnId"),
  ]),
  recordBinding("FileChangeApprovalParams", "decode", [
    schemaSource("all", "FileChangeRequestApprovalParams"),
  ], [
    component("grantRoot"),
    component("itemId"),
    component("reason"),
    component("startedAtMs"),
    component("threadId"),
    component("turnId"),
  ]),
  recordBinding("PermissionsApprovalParams", "decode", [
    schemaSource("all", "PermissionsRequestApprovalParams"),
  ], [
    component("cwd"),
    component("environmentId"),
    component("itemId"),
    component("permissions"),
    component("reason"),
    component("startedAtMs"),
    component("threadId"),
    component("turnId"),
  ]),
  recordBinding("JsonRpcEnvelope", "decode", [
    mergedSchemaSource("all", "JSONRPCMessage"),
  ], [
    component("id", "id", 1),
    component("method"),
    component("result", "result", {}),
    component("error", "error", { code: 1, message: "value" }),
  ]),
  recordBinding("JsonRpcError", "decode", [schemaSource("all", "JSONRPCErrorError")], [
    component("code"),
    component("message"),
  ]),
  recordBinding("JsonRpcRequest", "encode", [schemaSource("all", "JSONRPCRequest")], [
    component("id", "id", 1),
    component("method"),
    component("params", "params", {}),
  ]),
  recordBinding("JsonRpcNotification", "encode", [
    schemaSource("all", "JSONRPCNotification"),
  ], [component("method"), component("params", "params", {})]),
  recordBinding("JsonRpcResponse", "encode", [schemaSource("all", "JSONRPCResponse")], [
    component("id", "id", 1),
    component("result", "result", {}),
  ]),
  recordBinding("JsonRpcFailure", "encode", [schemaSource("all", "JSONRPCError")], [
    component("id", "id", 1),
    component("error", "error", { code: 1, message: "value" }),
  ]),
  recordBinding("ThreadResponse", "decode", [
    schemaSource("v2", "ThreadStartResponse"),
    schemaSource("v2", "ThreadResumeResponse"),
  ], [component("thread")]),
  recordBinding("TurnThread", "decode", [schemaSource("v2", "Thread")], [
    component("id"),
  ]),
  recordBinding("ThreadReadParams", "encode", [
    schemaSource("v2", "ThreadReadParams"),
  ], [component("threadId"), component("includeTurns")]),
  recordBinding("ThreadReadResponse", "decode", [
    schemaSource("v2", "ThreadReadResponse"),
  ], [component("thread")]),
  recordBinding("ThreadTurnsListParams", "encode", [
    schemaSource("v2", "ThreadTurnsListParams"),
  ], [
    component("threadId"),
    component("limit", "limit", 1),
    component("sortDirection"),
    component("itemsView"),
  ]),
  recordBinding("ThreadTurnsListResponse", "decode", [
    schemaSource("v2", "ThreadTurnsListResponse"),
  ], [component("data")]),
  recordBinding("ThreadBackgroundTerminalsListParams", "encode", [
    schemaSource("v2", "ThreadBackgroundTerminalsListParams"),
  ], [component("threadId"), component("cursor")]),
  recordBinding("ThreadBackgroundTerminalsListResponse", "decode", [
    schemaSource("v2", "ThreadBackgroundTerminalsListResponse"),
  ], [component("data"), component("nextCursor")]),
  recordBinding("ThreadListParams", "encode", [
    schemaSource("v2", "ThreadListParams"),
  ], [
    component("parentThreadId"),
    component("sourceKinds"),
    component("modelProviders"),
    component("cursor"),
  ]),
  recordBinding("ThreadListResponse", "decode", [
    schemaSource("v2", "ThreadListResponse"),
  ], [component("data"), component("nextCursor")]),
  recordBinding("ThreadBackgroundTerminalsTerminateParams", "encode", [
    schemaSource("v2", "ThreadBackgroundTerminalsTerminateParams"),
  ], [component("threadId"), component("processId")]),
  recordBinding("ThreadBackgroundTerminal", "decode", [
    schemaSource("v2", "ThreadBackgroundTerminal"),
  ], [component("processId")]),
  recordBinding("HistoryThread", "decode", [schemaSource("v2", "Thread")], [
    component("id"),
    component("parentThreadId"),
    component("status"),
    component("agentNickname"),
    component("agentRole"),
    component("source", "source", {
      subAgent: {
        thread_spawn: { parent_thread_id: "value", depth: 1, agent_path: "/root/value", agent_nickname: null, agent_role: null },
      },
    }),
  ]),
  recordBinding("ThreadStatus", "decode", [
    mergedSchemaSource("v2", "ThreadStatus", "type"),
  ], [component("type")]),
  recordBinding("TurnStartResponse", "decode", [
    schemaSource("v2", "TurnStartResponse"),
  ], [component("turn")]),
  recordBinding("ModelListResponse", "decode", [
    schemaSource("v2", "ModelListResponse"),
  ], [component("data")]),
  recordBinding("ModelEntry", "decode", [schemaSource("v2", "Model")], [
    component("id"),
    component("displayName"),
    component("description"),
    component("hidden"),
    component("isDefault"),
    component("defaultReasoningEffort"),
    component("supportedReasoningEfforts"),
    component("multiAgentVersion"),
    component("inputModalities"),
    component("serviceTiers"),
  ]),
  recordBinding("ModelServiceTier", "decode", [schemaSource("v2", "ModelServiceTier")], [
    component("id"), component("name"), component("description"),
  ]),
  recordBinding("ReasoningEffortOption", "decode", [
    schemaSource("v2", "ReasoningEffortOption"),
  ], [component("reasoningEffort")]),
  recordBinding("ClientInfo", "encode", [schemaSource("v2", "ClientInfo")], [
    component("name"),
    component("title"),
    component("version"),
  ]),
  recordBinding("InitializeParams", "encode", [schemaSource("v2", "InitializeParams")], [
    component("clientInfo", "clientInfo", {
      name: "value",
      title: "value",
      version: "value",
    }),
    component("capabilities", "capabilities", { experimentalApi: false }),
  ]),
  recordBinding("InitializeCapabilities", "encode", [
    schemaSource("v2", "InitializeCapabilities"),
  ], [
    component("experimentalApi"),
  ]),
  recordBinding("ThreadStartParams", "encode", [schemaSource("v2", "ThreadStartParams")], [
    component("cwd"),
    component("model"),
    component("approvalPolicy", "approvalPolicy", "on-request"),
    component("approvalsReviewer", "approvalsReviewer", "auto_review"),
    component("config", "config", {}),
    component("sandbox", "sandbox", "workspace-write"),
    component("developerInstructions", "developerInstructions", ""),
    component("ephemeral", "ephemeral", false),
    component("serviceName"),
    component("dynamicTools", "dynamicTools", []),
    component("selectedCapabilityRoots", "selectedCapabilityRoots", []),
  ]),
  recordBinding("CwdsParams", "encode", [
    schemaSource("v2", "HooksListParams"),
  ], [
    component("cwds", "cwds", ["value"]),
  ]),
  recordBinding("InitializeResponse", "decode", [
    schemaSource("all", "InitializeResponse"),
  ], [
    component("codexHome"),
  ]),
  recordBinding("SkillsListParams", "encode", [
    schemaSource("v2", "SkillsListParams"),
  ], [
    component("cwds", "cwds", ["value"]),
    component("forceReload"),
  ]),
  recordBinding("SkillsListResponse", "decode", [
    schemaSource("v2", "SkillsListResponse"),
  ], [
    component("data"),
  ]),
  recordBinding("SkillsListEntry", "decode", [
    schemaSource("v2", "SkillsListEntry"),
  ], [
    component("cwd"),
    component("skills"),
    component("errors"),
  ]),
  recordBinding("SkillMetadata", "decode", [
    schemaSource("v2", "SkillMetadata"),
  ], [
    component("name"),
    component("path"),
    component("scope", "scope", "system"),
    component("pluginId"),
  ]),
  recordBinding("ConfigReadParams", "encode", [
    schemaSource("v2", "ConfigReadParams"),
  ], [
    component("cwd"),
    component("includeLayers"),
  ]),
  recordBinding("HooksListResponse", "decode", [
    schemaSource("v2", "HooksListResponse"),
  ], [
    component("data"),
  ]),
  recordBinding("HooksListEntry", "decode", [
    schemaSource("v2", "HooksListEntry"),
  ], [
    component("cwd"),
    component("hooks"),
    component("errors"),
    component("warnings"),
  ]),
  recordBinding("ThreadResumeParams", "encode", [
    schemaSource("v2", "ThreadResumeParams"),
  ], [
    component("threadId"),
    component("excludeTurns"),
    component("cwd"),
    component("model"),
    component("approvalPolicy", "approvalPolicy", "on-request"),
    component("approvalsReviewer", "approvalsReviewer", "auto_review"),
    component("config", "config", {}),
    component("sandbox", "sandbox", "workspace-write"),
    component("developerInstructions", "developerInstructions", ""),
  ]),
  recordBinding("TextInput", "encode", [
    schemaSource("v2", "UserInput", "type", "text"),
  ], [component("type"), component("text")]),
  recordBinding("LocalImageInput", "encode", [
    schemaSource("v2", "UserInput", "type", "localImage"),
  ], [component("type"), component("path")]),
  recordBinding("TurnStartParams", "encode", [schemaSource("v2", "TurnStartParams")], [
    component("threadId"),
    component("input", "input", [{ type: "text", text: "value" }]),
    component("model"),
    component("effort", "effort", "minimal"),
    component("serviceTierForTurn", "serviceTierForTurn", "default"),
    component("approvalPolicy", "approvalPolicy", "on-request"),
    component("approvalsReviewer", "approvalsReviewer", "auto_review"),
    component("cwd"),
    component("sandboxPolicy", "sandboxPolicy", {
      type: "workspaceWrite",
      writableRoots: ["/value"],
      networkAccess: false,
      excludeTmpdirEnvVar: true,
      excludeSlashTmp: true,
    }),
    component("additionalContext", "additionalContext", {
      bytequay_instructions: { value: "value", kind: "application" },
    }),
  ]),
  recordBinding("AdditionalContextEntry", "encode", [schemaSource("v2", "AdditionalContextEntry")], [
    component("value"),
    component("kind", "kind", "application"),
  ]),
  recordBinding("ThreadParams", "encode", [
    schemaSource("v2", "ThreadUnsubscribeParams"),
  ], [component("threadId")]),
  recordBinding("TurnParams", "encode", [schemaSource("v2", "TurnInterruptParams")], [
    component("threadId"),
    component("turnId"),
  ]),
  recordBinding("TurnSteerParams", "encode", [schemaSource("v2", "TurnSteerParams")], [
    component("threadId"),
    component("expectedTurnId"),
    component("input", "input", [{ type: "text", text: "value" }]),
  ]),
  recordBinding("TurnSteerResponse", "decode", [
    schemaSource("v2", "TurnSteerResponse"),
  ], [component("turnId")]),
  recordBinding("CommandApprovalResponse", "encode", [
    schemaSource("all", "CommandExecutionRequestApprovalResponse"),
  ], [sampledComponent("decision")]),
  recordBinding("DynamicToolCallResponse", "encode", [
    schemaSource("all", "DynamicToolCallResponse"),
  ], [component("contentItems", "contentItems", [{ type: "inputText", text: "value" }]), component("success")]),
  recordBinding("DynamicToolTextContent", "encode", [
    schemaSource("v2", "DynamicToolCallOutputContentItem", "type", "inputText"),
  ], [component("type", "type", "inputText"), component("text")]),
  recordBinding("FileChangeApprovalResponse", "encode", [
    schemaSource("all", "FileChangeRequestApprovalResponse"),
  ], [sampledComponent("decision")]),
  recordBinding("PermissionsApprovalResponse", "encode", [
    schemaSource("all", "PermissionsRequestApprovalResponse"),
  ], [component("permissions"), sampledComponent("scope"), component("strictAutoReview")]),
  recordBinding("ModelListParams", "encode", [schemaSource("v2", "ModelListParams")], []),
];

const referenceEnumBindings = [
  { javaType: "TurnStatus", sources: [schemaSource("v2", "TurnStatus")] },
  {
    javaType: "ActivityStatus",
    sources: [
      schemaSource("v2", "CommandExecutionStatus"),
      schemaSource("v2", "PatchApplyStatus"),
      schemaSource("v2", "McpToolCallStatus"),
      schemaSource("v2", "DynamicToolCallStatus"),
      schemaSource("v2", "CollabAgentToolCallStatus"),
    ],
  },
  { javaType: "PlanStepStatus", sources: [schemaSource("v2", "TurnPlanStepStatus")] },
  { javaType: "MessagePhase", sources: [schemaSource("v2", "MessagePhase")] },
  {
    javaType: "CollabAgentStatus",
    sources: [schemaSource("v2", "CollabAgentStatus")],
  },
  {
    javaType: "SubAgentActivityKind",
    sources: [schemaSource("v2", "SubAgentActivityKind")],
  },
  {
    javaType: "MultiAgentVersion",
    sources: [schemaSource("v2", "MultiAgentVersion")],
  },
];

const experimentalMethods = [
  "environment/add",
  "environment/info",
  "thread/backgroundTerminals/list",
  "thread/backgroundTerminals/clean",
  "thread/backgroundTerminals/terminate",
];

const stableMethods = [
  "account/read",
  "model/list",
  "config/read",
  "configRequirements/read",
  "hooks/list",
  "skills/list",
  "thread/list",
  "thread/start",
  "turn/start",
  "thread/read",
  "thread/resume",
];

const modernApprovalMethods = [
  "item/commandExecution/requestApproval",
  "item/fileChange/requestApproval",
  "item/permissions/requestApproval",
];

const legacyApprovalMethods = [
  "applyPatchApproval",
  "execCommandApproval",
];

const alwaysDisabledFeatures = [
  "apps",
  "browser_use",
  "browser_use_external",
  "computer_use",
  "guardianv2",
  "guardian_ext",
  "hooks",
  "image_generation",
  "in_app_browser",
  "plugins",
  "remote_plugin",
  "skill_mcp_dependency_install",
  "skill_search",
  "tool_call_mcp_elicitation",
  "tool_suggest",
  "workspace_dependencies",
];

const noChildLaunchProfile = {
  name: "no-child",
  multi_agent: false,
  multi_agent_v2: false,
};
const v1ChildLaunchProfile = {
  name: "multi-agent-v1-enabled",
  multi_agent: true,
  multi_agent_v2: false,
  // Two levels: the task may delegate to a child, and that child may delegate once more to a
  // grandchild.
  agentMaxDepth: 2,
};
const v2ChildLaunchProfile = {
  name: "multi-agent-v2-enabled",
  multi_agent: false,
  multi_agent_v2: true,
};
const appServerLaunchProfiles = [
  noChildLaunchProfile,
  v1ChildLaunchProfile,
  v2ChildLaunchProfile,
];

const shellEnvironmentIncludeOnly = [
  "HOME",
  "LANG",
  "LOGNAME",
  "PATH",
  "SHELL",
  "TMPDIR",
  "USER",
];

const execPolicyAllowedCommand = ["/usr/bin/git", "status", "--short"];
const execPolicyRule = `prefix_rule(
    pattern = ["/usr/bin/git", "status", "--short"],
    decision = "allow",
    justification = "Read-only ByteQuay compatibility probe",
    match = [
        "/usr/bin/git status --short",
    ],
)
`;
function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    encoding: "utf8",
    killSignal: "SIGKILL",
    maxBuffer: 16 * 1024 * 1024,
    timeout: 30_000,
    ...options,
  });
  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    throw new Error(
      `${command} ${args.join(" ")} failed (${result.status}):\n${result.stderr || result.stdout}`,
    );
  }
  return result.stdout.trim();
}

function minimalProcessEnvironment(home, temporaryDirectory) {
  return {
    HOME: home,
    LANG: process.env.LANG ?? "en_US.UTF-8",
    LOGNAME: process.env.LOGNAME ?? process.env.USER ?? "bytequay",
    PATH: process.env.PATH ?? "/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin",
    SHELL: "/bin/zsh",
    TMPDIR: temporaryDirectory,
    USER: process.env.USER ?? "bytequay",
  };
}

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function sha256(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function requestMethods(schema) {
  return new Set(
    schema.oneOf.flatMap((request) => request.properties?.method?.enum ?? []),
  );
}

function decisionVariants(schema, definition) {
  return schema.definitions[definition].oneOf.flatMap(
    (variant) => variant.enum ?? Object.keys(variant.properties ?? {}),
  );
}

function requireProperties(schema, names) {
  for (const name of names) {
    assert.ok(schema.properties?.[name], `${schema.title} lacks property ${name}`);
  }
}

function requireFields(schema, names) {
  for (const name of names) {
    assert.ok(schema.required?.includes(name), `${schema.title} does not require ${name}`);
  }
}

function requireExactObjectShape(schema, properties, required) {
  assert.deepEqual(
    Object.keys(schema.properties ?? {}),
    properties,
    `${schema.title ?? "object"} properties changed`,
  );
  assert.deepEqual(
    schema.required ?? [],
    required,
    `${schema.title ?? "object"} required fields changed`,
  );
}

function requireDecisionPayload(schema, definition, decision, fields) {
  const variant = schema.definitions[definition].oneOf.find(
    (candidate) => candidate.properties?.[decision],
  );
  assert.ok(variant, `${definition} lacks ${decision}`);
  requireExactObjectShape(variant, [decision], [decision]);
  const payload = variant.properties[decision];
  requireExactObjectShape(payload, fields, fields);
  return payload;
}

function requireEnumMembers(schema, values) {
  const members = new Set(schema.enum ?? []);
  for (const value of values) {
    assert.ok(members.has(value), `${schema.title ?? "enum"} lacks ${value}`);
  }
}

function checkChildThreadItems(schema) {
  const threadItems = schema.definitions.ThreadItem.oneOf;
  const collabItem = threadItems.find(
    (item) => item.properties?.type?.enum?.includes("collabAgentToolCall"),
  );
  assert.ok(collabItem, `${schema.title} lacks collabAgentToolCall`);
  requireProperties(collabItem, [
    "agentsStates",
    "id",
    "receiverThreadIds",
    "senderThreadId",
    "status",
    "tool",
    "type",
  ]);
  requireFields(collabItem, [
    "agentsStates",
    "id",
    "receiverThreadIds",
    "senderThreadId",
    "status",
    "tool",
    "type",
  ]);
  requireProperties(schema.definitions.CollabAgentState, ["message", "status"]);
  requireFields(schema.definitions.CollabAgentState, ["status"]);
  requireEnumMembers(schema.definitions.CollabAgentTool, ["spawnAgent"]);
  requireEnumMembers(schema.definitions.CollabAgentToolCallStatus, [
    "inProgress",
    "completed",
    "failed",
    "interrupted",
  ]);
  requireEnumMembers(schema.definitions.CollabAgentStatus, [
    "pendingInit",
    "running",
    "interrupted",
    "completed",
    "errored",
    "shutdown",
    "notFound",
  ]);

  const subAgentItem = threadItems.find(
    (item) => item.properties?.type?.enum?.includes("subAgentActivity"),
  );
  assert.ok(subAgentItem, `${schema.title} lacks subAgentActivity`);
  requireProperties(subAgentItem, ["agentPath", "agentThreadId", "id", "kind", "type"]);
  requireFields(subAgentItem, ["agentPath", "agentThreadId", "id", "kind", "type"]);
  requireEnumMembers(schema.definitions.SubAgentActivityKind, [
    "started",
    "interacted",
    "interrupted",
    "completed",
  ]);
}

function checkChildThreadContract(directory, experimental) {
  const notificationMethods = requestMethods(
    readJson(join(directory, "ServerNotification.json")),
  );
  for (const method of ["thread/started", "thread/status/changed"]) {
    assert.ok(notificationMethods.has(method), `generated schema lacks ${method}`);
  }

  const threadListParams = readJson(join(directory, "v2", "ThreadListParams.json"));
  requireProperties(threadListParams, ["cursor", "limit", "sourceKinds"]);
  assert.equal(
    Boolean(threadListParams.properties.parentThreadId),
    experimental,
    "thread/list parentThreadId experimental gating changed",
  );
  assert.equal(
    Boolean(threadListParams.properties.ancestorThreadId),
    experimental,
    "thread/list ancestorThreadId experimental gating changed",
  );
  if (experimental) {
    for (const name of ["parentThreadId", "ancestorThreadId"]) {
      const types = new Set([threadListParams.properties[name].type].flat());
      assert.ok(types.has("string"), `thread/list ${name} is not a string`);
      assert.ok(types.has("null"), `thread/list ${name} is not nullable`);
    }
  }
  requireEnumMembers(threadListParams.definitions.ThreadSourceKind, [
    "subAgent",
    "subAgentReview",
    "subAgentCompact",
    "subAgentThreadSpawn",
    "subAgentOther",
  ]);

  const threadListResponse = readJson(
    join(directory, "v2", "ThreadListResponse.json"),
  );
  requireProperties(threadListResponse, ["data", "nextCursor"]);
  requireFields(threadListResponse, ["data"]);
  const thread = threadListResponse.definitions.Thread;
  requireProperties(thread, ["id", "parentThreadId", "sessionId", "source", "status", "turns"]);
  requireFields(thread, ["id", "sessionId", "source", "status", "turns"]);
  const statusTypes = new Set(
    threadListResponse.definitions.ThreadStatus.oneOf.flatMap(
      (variant) => variant.properties.type.enum,
    ),
  );
  for (const status of ["notLoaded", "idle", "systemError", "active"]) {
    assert.ok(statusTypes.has(status), `ThreadStatus lacks ${status}`);
  }
  const activeStatus = threadListResponse.definitions.ThreadStatus.oneOf.find(
    (variant) => variant.properties.type.enum.includes("active"),
  );
  assert.ok(activeStatus, "ThreadStatus lacks active");
  requireFields(activeStatus, ["activeFlags", "type"]);
  requireEnumMembers(threadListResponse.definitions.ThreadActiveFlag, [
    "waitingOnApproval",
    "waitingOnUserInput",
  ]);

  const statusChanged = readJson(
    join(directory, "v2", "ThreadStatusChangedNotification.json"),
  );
  requireProperties(statusChanged, ["status", "threadId"]);
  requireFields(statusChanged, ["status", "threadId"]);

  const threadStarted = readJson(
    join(directory, "v2", "ThreadStartedNotification.json"),
  );
  requireProperties(threadStarted, ["thread"]);
  requireFields(threadStarted, ["thread"]);
  requireProperties(threadStarted.definitions.Thread, [
    "id",
    "parentThreadId",
    "sessionId",
    "status",
  ]);
  requireFields(threadStarted.definitions.Thread, ["id", "sessionId", "status"]);

  const itemStarted = readJson(
    join(directory, "v2", "ItemStartedNotification.json"),
  );
  requireProperties(itemStarted, ["item", "startedAtMs", "threadId", "turnId"]);
  requireFields(itemStarted, ["item", "startedAtMs", "threadId", "turnId"]);
  const itemCompleted = readJson(
    join(directory, "v2", "ItemCompletedNotification.json"),
  );
  requireProperties(itemCompleted, ["completedAtMs", "item", "threadId", "turnId"]);
  requireFields(itemCompleted, ["completedAtMs", "item", "threadId", "turnId"]);
  checkChildThreadItems(itemStarted);
  checkChildThreadItems(itemCompleted);

  const threadReadResponse = readJson(
    join(directory, "v2", "ThreadReadResponse.json"),
  );
  requireProperties(threadReadResponse.definitions.Thread, [
    "id",
    "parentThreadId",
    "sessionId",
    "status",
    "turns",
  ]);
  requireFields(threadReadResponse.definitions.Thread, ["id", "sessionId", "status", "turns"]);
  requireProperties(threadReadResponse.definitions.Turn, ["id", "items", "status"]);
  requireFields(threadReadResponse.definitions.Turn, ["id", "items", "status"]);
  requireEnumMembers(threadReadResponse.definitions.TurnStatus, [
    "completed",
    "interrupted",
    "failed",
    "inProgress",
  ]);
  checkChildThreadItems(threadReadResponse);
}

function checkSchema(directory, experimental) {
  const requests = requestMethods(readJson(join(directory, "ClientRequest.json")));
  const serverRequests = requestMethods(readJson(join(directory, "ServerRequest.json")));
  for (const method of stableMethods) {
    assert.ok(requests.has(method), `generated schema lacks ${method}`);
  }
  assert.deepEqual(
    [...serverRequests]
      .filter((method) => method.toLowerCase().includes("approval"))
      .sort(),
    [...modernApprovalMethods, ...legacyApprovalMethods].sort(),
    "approval request methods changed",
  );
  for (const method of experimentalMethods) {
    assert.equal(
      requests.has(method),
      experimental,
      `${method} experimental gating changed`,
    );
  }

  const getAccountResponse = readJson(join(directory, "v2", "GetAccountResponse.json"));
  const getAccountParams = readJson(join(directory, "v2", "GetAccountParams.json"));
  requireProperties(getAccountParams, ["refreshToken"]);
  requireProperties(getAccountResponse, ["account", "requiresOpenaiAuth"]);
  requireFields(getAccountResponse, ["requiresOpenaiAuth"]);
  const accountTypes = new Set(
    getAccountResponse.definitions.Account.oneOf.flatMap(
      (account) => account.properties?.type?.enum ?? [],
    ),
  );
  for (const accountType of ["apiKey", "chatgpt"]) {
    assert.ok(accountTypes.has(accountType), `account/read lacks ${accountType} identity`);
  }

  const initialize = readJson(join(directory, "v1", "InitializeParams.json"));
  requireProperties(initialize.definitions.InitializeCapabilities, [
    "experimentalApi",
    "requestAttestation",
  ]);

  const modelList = readJson(join(directory, "v2", "ModelListResponse.json"));
  requireProperties(modelList.definitions.Model, [
    "defaultReasoningEffort",
    "id",
    "isDefault",
    "inputModalities",
    "supportedReasoningEfforts",
    "multiAgentVersion",
  ]);
  requireFields(modelList.definitions.Model, ["defaultReasoningEffort"]);
  requireProperties(modelList.definitions.ReasoningEffortOption, [
    "description",
    "reasoningEffort",
  ]);
  assert.deepEqual(modelList.definitions.MultiAgentVersion.enum, ["disabled", "v1", "v2"]);

  const threadRead = readJson(join(directory, "v2", "ThreadReadParams.json"));
  requireProperties(threadRead, ["threadId", "includeTurns"]);
  requireFields(threadRead, ["threadId"]);

  const threadResume = readJson(join(directory, "v2", "ThreadResumeParams.json"));
  requireProperties(threadResume, ["threadId", "cwd", "approvalPolicy", "sandbox"]);
  requireFields(threadResume, ["threadId"]);

  const configReadParams = readJson(join(directory, "v2", "ConfigReadParams.json"));
  requireExactObjectShape(configReadParams, ["cwd", "includeLayers"], []);
  const configReadResponse = readJson(join(directory, "v2", "ConfigReadResponse.json"));
  requireExactObjectShape(configReadResponse, ["config", "layers", "origins"], [
    "config",
    "origins",
  ]);
  requireExactObjectShape(
    configReadResponse.definitions.ConfigLayer,
    ["config", "disabledReason", "name", "version"],
    ["config", "name", "version"],
  );
  const configLayerShapes = new Map(
    configReadResponse.definitions.ConfigLayerSource.oneOf.map((shape) => [
      shape.properties.type.enum[0],
      shape,
    ]),
  );
  assert.deepEqual([...configLayerShapes.keys()].sort(), [
    "enterpriseManaged",
    "legacyManagedConfigTomlFromFile",
    "legacyManagedConfigTomlFromMdm",
    "mdm",
    "packagedDefaults",
    "project",
    "sessionFlags",
    "system",
    "user",
  ]);
  for (const [type, fields] of [
    ["packagedDefaults", ["file", "type"]],
    ["system", ["file", "type"]],
    ["user", ["file", "profile", "type"]],
    ["project", ["dotCodexFolder", "type"]],
    ["legacyManagedConfigTomlFromFile", ["file", "type"]],
    ["mdm", ["domain", "key", "type"]],
    ["enterpriseManaged", ["id", "name", "type"]],
    ["sessionFlags", ["type"]],
    ["legacyManagedConfigTomlFromMdm", ["type"]],
  ]) {
    const shape = configLayerShapes.get(type);
    assert.ok(shape, `ConfigLayerSource lacks ${type}`);
    const required = fields.filter((field) => field !== "profile");
    requireExactObjectShape(shape, fields, required);
  }
  requireExactObjectShape(
    readJson(join(directory, "v2", "ConfigRequirementsReadResponse.json")),
    ["requirements"],
    [],
  );

  const skillsListParams = readJson(join(directory, "v2", "SkillsListParams.json"));
  requireProperties(skillsListParams, ["cwds", "forceReload"]);
  const skillsListResponse = readJson(join(directory, "v2", "SkillsListResponse.json"));
  requireProperties(skillsListResponse, ["data"]);
  requireFields(skillsListResponse, ["data"]);
  requireProperties(skillsListResponse.definitions.SkillsListEntry, [
    "cwd",
    "errors",
    "skills",
  ]);
  requireFields(skillsListResponse.definitions.SkillsListEntry, [
    "cwd",
    "errors",
    "skills",
  ]);
  requireProperties(skillsListResponse.definitions.SkillMetadata, [
    "name",
    "path",
    "pluginId",
    "scope",
  ]);
  requireFields(skillsListResponse.definitions.SkillMetadata, ["name", "path", "scope"]);
  assert.ok(
    skillsListResponse.definitions.SkillScope.enum.includes("system"),
    "skill scopes lack system",
  );

  const hooksListParams = readJson(join(directory, "v2", "HooksListParams.json"));
  requireProperties(hooksListParams, ["cwds"]);
  const hooksListResponse = readJson(join(directory, "v2", "HooksListResponse.json"));
  requireProperties(hooksListResponse, ["data"]);
  requireFields(hooksListResponse, ["data"]);
  requireProperties(hooksListResponse.definitions.HooksListEntry, [
    "cwd",
    "errors",
    "hooks",
    "warnings",
  ]);
  requireFields(hooksListResponse.definitions.HooksListEntry, [
    "cwd",
    "errors",
    "hooks",
    "warnings",
  ]);
  if (!experimental) return;

  const commandApprovalParams = readJson(
    join(directory, "CommandExecutionRequestApprovalParams.json"),
  );
  requireExactObjectShape(commandApprovalParams, [
    "additionalPermissions",
    "approvalId",
    "availableDecisions",
    "command",
    "commandActions",
    "cwd",
    "environmentId",
    "itemId",
    "kind",
    "networkApprovalContext",
    "proposedExecpolicyAmendment",
    "proposedNetworkPolicyAmendments",
    "reason",
    "startedAtMs",
    "threadId",
    "turnId",
  ], ["itemId", "startedAtMs", "threadId", "turnId"]);
  assert.deepEqual(
    commandApprovalParams.definitions.CommandExecutionApprovalKind.enum,
    ["command", "writeStdin"],
  );

  const fileApprovalParams = readJson(
    join(directory, "FileChangeRequestApprovalParams.json"),
  );
  requireExactObjectShape(fileApprovalParams, [
    "grantRoot",
    "itemId",
    "reason",
    "startedAtMs",
    "threadId",
    "turnId",
  ], ["itemId", "startedAtMs", "threadId", "turnId"]);

  const permissionsApprovalParams = readJson(
    join(directory, "PermissionsRequestApprovalParams.json"),
  );
  requireExactObjectShape(permissionsApprovalParams, [
    "cwd",
    "environmentId",
    "itemId",
    "permissions",
    "reason",
    "startedAtMs",
    "threadId",
    "turnId",
  ], ["cwd", "itemId", "permissions", "startedAtMs", "threadId", "turnId"]);

  const commandApprovalResponse = readJson(
    join(directory, "CommandExecutionRequestApprovalResponse.json"),
  );
  requireExactObjectShape(commandApprovalResponse, ["decision"], ["decision"]);
  assert.deepEqual(
    decisionVariants(commandApprovalResponse, "CommandExecutionApprovalDecision"),
    [
      "accept",
      "acceptForSession",
      "acceptWithExecpolicyAmendment",
      "applyNetworkPolicyAmendment",
      "decline",
      "cancel",
    ],
    "command approval decisions changed",
  );
  const execpolicyDecision = requireDecisionPayload(
    commandApprovalResponse,
    "CommandExecutionApprovalDecision",
    "acceptWithExecpolicyAmendment",
    ["execpolicy_amendment"],
  );
  assert.deepEqual(
    execpolicyDecision.properties.execpolicy_amendment.type,
    "array",
    "execpolicy amendment shape changed",
  );
  assert.equal(
    execpolicyDecision.properties.execpolicy_amendment.items?.type,
    "string",
    "execpolicy amendment entry shape changed",
  );
  const networkDecision = requireDecisionPayload(
    commandApprovalResponse,
    "CommandExecutionApprovalDecision",
    "applyNetworkPolicyAmendment",
    ["network_policy_amendment"],
  );
  assert.equal(
    networkDecision.properties.network_policy_amendment.$ref,
    "#/definitions/NetworkPolicyAmendment",
    "network policy amendment shape changed",
  );
  requireExactObjectShape(
    commandApprovalResponse.definitions.NetworkPolicyAmendment,
    ["action", "host"],
    ["action", "host"],
  );
  assert.equal(
    commandApprovalResponse.definitions.NetworkPolicyAmendment.properties.action.$ref,
    "#/definitions/NetworkPolicyRuleAction",
    "network policy action shape changed",
  );
  assert.deepEqual(
    commandApprovalResponse.definitions.NetworkPolicyRuleAction.enum,
    ["allow", "deny"],
  );

  const fileApprovalResponse = readJson(
    join(directory, "FileChangeRequestApprovalResponse.json"),
  );
  requireExactObjectShape(fileApprovalResponse, ["decision"], ["decision"]);
  assert.deepEqual(
    decisionVariants(fileApprovalResponse, "FileChangeApprovalDecision"),
    ["accept", "acceptForSession", "decline", "cancel"],
    "file approval decisions changed",
  );

  const permissionsApprovalResponse = readJson(
    join(directory, "PermissionsRequestApprovalResponse.json"),
  );
  requireExactObjectShape(
    permissionsApprovalResponse,
    ["permissions", "scope", "strictAutoReview"],
    ["permissions"],
  );
  assert.deepEqual(
    permissionsApprovalResponse.definitions.PermissionGrantScope.enum,
    ["turn", "session"],
  );

  requireExactObjectShape(
    readJson(join(directory, "ApplyPatchApprovalParams.json")),
    ["callId", "conversationId", "fileChanges", "grantRoot", "reason"],
    ["callId", "conversationId", "fileChanges"],
  );
  requireExactObjectShape(
    readJson(join(directory, "ApplyPatchApprovalResponse.json")),
    ["decision"],
    ["decision"],
  );
  requireExactObjectShape(
    readJson(join(directory, "ExecCommandApprovalParams.json")),
    ["approvalId", "callId", "command", "conversationId", "cwd", "parsedCmd", "reason"],
    ["callId", "command", "conversationId", "cwd", "parsedCmd"],
  );
  requireExactObjectShape(
    readJson(join(directory, "ExecCommandApprovalResponse.json")),
    ["decision"],
    ["decision"],
  );

  const threadStart = readJson(join(directory, "v2", "ThreadStartParams.json"));
  const turnStart = readJson(join(directory, "v2", "TurnStartParams.json"));
  requireProperties(threadStart, ["cwd", "approvalPolicy", "sandbox"]);
  requireProperties(turnStart, ["threadId", "input"]);

  const backgroundList = readJson(
    join(directory, "v2", "ThreadBackgroundTerminalsListResponse.json"),
  );
  requireProperties(backgroundList, ["data", "nextCursor"]);
  requireFields(backgroundList, ["data"]);
  requireProperties(backgroundList.definitions.ThreadBackgroundTerminal, [
    "processId",
    "itemId",
    "command",
    "cwd",
  ]);
  requireFields(backgroundList.definitions.ThreadBackgroundTerminal, [
    "processId",
    "itemId",
    "command",
    "cwd",
  ]);

  const backgroundListParams = readJson(
    join(directory, "v2", "ThreadBackgroundTerminalsListParams.json"),
  );
  requireProperties(backgroundListParams, ["threadId", "cursor", "limit"]);
  requireFields(backgroundListParams, ["threadId"]);

  const backgroundTerminateParams = readJson(
    join(directory, "v2", "ThreadBackgroundTerminalsTerminateParams.json"),
  );
  requireProperties(backgroundTerminateParams, ["threadId", "processId"]);
  requireFields(backgroundTerminateParams, ["threadId", "processId"]);
  const backgroundTerminateResponse = readJson(
    join(directory, "v2", "ThreadBackgroundTerminalsTerminateResponse.json"),
  );
  requireProperties(backgroundTerminateResponse, ["terminated"]);
  requireFields(backgroundTerminateResponse, ["terminated"]);

  const threadResponse = readJson(join(directory, "v2", "ThreadReadResponse.json"));
  requireProperties(threadResponse.definitions.Thread, ["parentThreadId", "turns"]);
  const commandItem = threadResponse.definitions.ThreadItem.oneOf.find(
    (item) => item.properties?.type?.enum?.includes("commandExecution"),
  );
  assert.ok(commandItem, "ThreadItem lacks commandExecution");
  requireProperties(commandItem, [
    "aggregatedOutput",
    "command",
    "commandActions",
    "cwd",
    "exitCode",
    "id",
    "processId",
    "status",
    "type",
  ]);
  requireFields(commandItem, ["command", "commandActions", "cwd", "id", "status", "type"]);
  assert.deepEqual(
    threadResponse.definitions.CommandExecutionStatus.enum,
    ["inProgress", "completed", "failed", "declined"],
  );
  checkChildThreadContract(directory, experimental);
}

function bundleDefinition(bundle, definition) {
  const namespaced = definition.match(/^(v[12])\/(.+)$/);
  const schema = bundle.definitions[definition] ??
    (namespaced ? bundle.definitions[namespaced[1]]?.[namespaced[2]] : undefined) ??
    bundle.definitions[definition.replace(/^v[12]\//, "")];
  assert.ok(schema, `generated schema lacks definition ${definition}`);
  return schema;
}

function referencedDefinition(schema, bundle) {
  if (!schema?.$ref) return schema;
  const prefix = "#/definitions/";
  assert.ok(schema.$ref.startsWith(prefix), `unsupported schema reference ${schema.$ref}`);
  const definition = schema.$ref.slice(prefix.length).replaceAll("~1", "/").replaceAll("~0", "~");
  return bundleDefinition(bundle, definition);
}

function mergeSchemas(base, variant) {
  return {
    ...base,
    ...variant,
    properties: {
      ...(base.properties ?? {}),
      ...(variant.properties ?? {}),
    },
    required: [...new Set([...(base.required ?? []), ...(variant.required ?? [])])],
  };
}

function schemaVariants(schema, bundle) {
  const resolved = referencedDefinition(schema, bundle);
  return (resolved.oneOf ?? resolved.anyOf ?? []).map((variant) =>
    referencedDefinition(variant, bundle));
}

function discriminatorValues(schema, bundle, field) {
  const resolved = referencedDefinition(schema, bundle);
  const property = resolved.properties?.[field];
  if (!property) return [];
  return enumValues(property, bundle);
}

function resolveBindingSource(source, bundles) {
  const bundle = bundles[source.bundle];
  assert.ok(bundle, `unknown schema bundle ${source.bundle}`);
  const schema = bundleDefinition(bundle, source.definition);
  const variants = schemaVariants(schema, bundle);
  if (source.discriminatorField) {
    const variant = variants.find((candidate) =>
      discriminatorValues(candidate, bundle, source.discriminatorField)
        .includes(source.discriminatorValue));
    assert.ok(
      variant,
      `${source.definition} lacks ${source.discriminatorField}=${source.discriminatorValue}`,
    );
    return {
      bundle,
      schema: mergeSchemas(
        { ...schema, oneOf: undefined, anyOf: undefined },
        variant,
      ),
    };
  }
  if (source.mergeVariants) {
    assert.ok(variants.length > 0, `${source.definition} has no variants to merge`);
    const required = variants
      .map((variant) => new Set(variant.required ?? []))
      .reduce((intersection, names) =>
        new Set([...intersection].filter((name) => names.has(name))));
    return {
      bundle,
      variantValues: source.variantField
        ? [...new Set(variants.flatMap((variant) =>
          discriminatorValues(variant, bundle, source.variantField)))].sort()
        : [],
      schema: {
        properties: Object.assign({}, ...variants.map((variant) => variant.properties ?? {})),
        required: [...required],
        type: "object",
      },
    };
  }
  return { bundle, schema };
}

function allowsNull(schema, bundle, visited = new Set()) {
  if (schema === true) return true;
  if (!schema) return false;
  if (schema.$ref) {
    if (visited.has(schema.$ref)) return false;
    const nextVisited = new Set(visited).add(schema.$ref);
    return allowsNull(referencedDefinition(schema, bundle), bundle, nextVisited);
  }
  if (schema.const === null || schema.enum?.includes(null)) return true;
  if (schema.type === "null" || Array.isArray(schema.type) && schema.type.includes("null")) {
    return true;
  }
  if (schema.allOf?.length) {
    return schema.allOf.every((variant) => allowsNull(variant, bundle, visited));
  }
  return [...(schema.oneOf ?? []), ...(schema.anyOf ?? [])]
    .some((variant) => allowsNull(variant, bundle, visited));
}

function schemaRichness(schema, bundle) {
  const resolved = referencedDefinition(schema, bundle);
  if (allowsNull(resolved, bundle) &&
      (resolved.type === "null" || resolved.const === null)) {
    return -1;
  }
  return Object.keys(resolved.properties ?? {}).length * 10 +
    (resolved.required ?? []).length;
}

function nonNullVariant(schema, bundle) {
  return schemaVariants(schema, bundle)
    .filter((variant) => !(variant.type === "null" || variant.const === null))
    .sort((left, right) => schemaRichness(right, bundle) - schemaRichness(left, bundle))[0];
}

function sampleValue(schema, bundle, visited = new Set()) {
  if (schema === true) return {};
  if (schema.default !== undefined && schema.default !== null) return schema.default;
  if (schema.$ref) {
    if (visited.has(schema.$ref)) return {};
    return sampleValue(
      referencedDefinition(schema, bundle),
      bundle,
      new Set(visited).add(schema.$ref),
    );
  }
  if (schema.allOf?.length) {
    const combined = schema.allOf
      .map((variant) => referencedDefinition(variant, bundle))
      .reduce(
        (merged, variant) => mergeSchemas(merged, variant),
        { ...schema, allOf: undefined },
      );
    return sampleValue(combined, bundle, visited);
  }
  const variant = nonNullVariant(schema, bundle);
  if (variant) {
    return sampleValue(
      mergeSchemas({ ...schema, oneOf: undefined, anyOf: undefined }, variant),
      bundle,
      visited,
    );
  }
  if (schema.const !== undefined && schema.const !== null) return schema.const;
  const enumValue = schema.enum?.find((value) => value !== null);
  if (enumValue !== undefined) return enumValue;
  const type = Array.isArray(schema.type)
    ? schema.type.find((candidate) => candidate !== "null")
    : schema.type;
  if (type === "array") return [];
  if (type === "boolean") return false;
  if (type === "integer" || type === "number") return Math.max(schema.minimum ?? 1, 0);
  if (type === "string") return "value";
  if (type === "object" || schema.properties) {
    return Object.fromEntries(
      (schema.required ?? []).map((name) => {
        const property = schema.properties?.[name];
        assert.ok(property, `${schema.title ?? "object"} lacks required property ${name}`);
        return [name, sampleValue(property, bundle, visited)];
      }),
    );
  }
  assert.fail(`cannot generate sample for ${JSON.stringify(schema)}`);
}

function enumValues(schema, bundle, visited = new Set()) {
  if (!schema) return [];
  if (schema.$ref) {
    if (visited.has(schema.$ref)) return [];
    return enumValues(
      referencedDefinition(schema, bundle),
      bundle,
      new Set(visited).add(schema.$ref),
    );
  }
  const values = [
    ...(schema.enum ?? []),
    ...(schema.const === undefined ? [] : [schema.const]),
  ].filter((value) => value !== null);
  for (const variant of [...(schema.oneOf ?? []), ...(schema.anyOf ?? [])]) {
    values.push(...enumValues(variant, bundle, visited));
  }
  for (const variant of schema.allOf ?? []) {
    values.push(...enumValues(variant, bundle, visited));
  }
  return [...new Set(values)].sort();
}

function topLevelSamples(schema, bundle) {
  const resolved = referencedDefinition(schema, bundle);
  const variants = schemaVariants(resolved, bundle);
  const alternatives = variants.length > 0
    ? variants
      .filter((variant) => !(variant.type === "null" || variant.const === null))
      .map((variant) => sampleValue(variant, bundle))
    : enumValues(resolved, bundle);
  const normal = schema.default ?? resolved.default ?? alternatives[0] ??
    sampleValue(schema, bundle);
  const samples = [];
  const seen = new Set();
  for (const value of [normal, ...alternatives]) {
    const key = JSON.stringify(value);
    if (!seen.has(key)) {
      seen.add(key);
      samples.push(value);
    }
  }
  return samples;
}

function arrayItemSamples(schema, bundle) {
  const resolved = referencedDefinition(schema, bundle);
  assert.ok(resolved.items, "array schema lacks items");
  return topLevelSamples(resolved.items, bundle).map((value) => [value]);
}

function bindingSourceName(source) {
  const discriminator = source.discriminatorField
    ? `[${source.discriminatorField}=${source.discriminatorValue}]`
    : source.mergeVariants ? "[all variants]" : "";
  return `${source.bundle}#${source.definition}${discriminator}`;
}

function buildRecordBinding(binding, bundles) {
  const resolvedSources = binding.sources.map((source) => ({
    source,
    ...resolveBindingSource(source, bundles),
  }));
  const wireNames = new Set(binding.components.map(({ wireName }) => wireName));
  const contexts = binding.sources
    .filter(({ discriminatorField }) => discriminatorField && !wireNames.has(discriminatorField))
    .map(({ discriminatorField, discriminatorValue }) => ({
      [discriminatorField]: discriminatorValue,
    }));
  const context = contexts[0];
  for (const candidate of contexts.slice(1)) {
    assert.deepEqual(candidate, context, `${binding.javaType} has conflicting contexts`);
  }
  const decodeTargets = [...new Set(binding.sources.flatMap(({ definition }) => {
    const decodeAs = {
      ServerNotification: "Notification",
      ServerRequest: "ServerRequest",
      ThreadItem: "ThreadItem",
    }[definition];
    return decodeAs ? [decodeAs] : [];
  }))];
  assert.ok(decodeTargets.length <= 1, `${binding.javaType} has conflicting decode targets`);

  const components = binding.components.map(({ javaName, wireName, sample, deriveSamples, deriveItemSamples }) => {
    const metadata = resolvedSources.map(({ bundle, schema, source, variantValues = [] }) => {
      const property = schema.properties?.[wireName];
      assert.ok(property, `${bindingSourceName(source)} lacks property ${wireName}`);
      const samples = deriveSamples
        ? topLevelSamples(property, bundle)
        : deriveItemSamples
          ? arrayItemSamples(property, bundle)
          : source.variantField === wireName ? variantValues : undefined;
      return {
        required: (schema.required ?? []).includes(wireName),
        nullable: allowsNull(property, bundle),
        sample: sample ?? (deriveSamples || deriveItemSamples ? samples[0] : sampleValue(property, bundle)),
        ...(samples ? { samples } : {}),
      };
    });
    for (const candidate of metadata.slice(1)) {
      assert.equal(
        candidate.required,
        metadata[0].required,
        `${binding.javaType}.${javaName} has inconsistent requiredness`,
      );
      assert.equal(
        candidate.nullable,
        metadata[0].nullable,
        `${binding.javaType}.${javaName} has inconsistent nullability`,
      );
      assert.deepEqual(
        candidate.samples,
        metadata[0].samples,
        `${binding.javaType}.${javaName} has inconsistent samples`,
      );
    }
    return { javaName, wireName, ...metadata[0] };
  });

  if (binding.mode === "encode") {
    for (const { schema, source } of resolvedSources) {
      const represented = new Set([
        ...wireNames,
        ...(source.discriminatorField ? [source.discriminatorField] : []),
      ]);
      for (const required of schema.required ?? []) {
        assert.ok(
          represented.has(required),
          `${binding.javaType} cannot encode required ${bindingSourceName(source)}.${required}`,
        );
      }
    }
  }

  return {
    javaType: binding.javaType,
    mode: binding.mode,
    sources: binding.sources.map(bindingSourceName),
    ...(context ? { context } : {}),
    ...(binding.mode === "decode" && decodeTargets[0]
      ? { decodeAs: decodeTargets[0] }
      : {}),
    components,
  };
}

function buildReferenceBindings(experimental, stable) {
  const bundles = {
    all: readJson(join(experimental, "codex_app_server_protocol.schemas.json")),
    v2: readJson(join(experimental, "codex_app_server_protocol.v2.schemas.json")),
  };
  return {
    formatVersion: 1,
    codexVersion: contract.version,
    bundleSha256: {
      stable: sha256(join(stable, "codex_app_server_protocol.schemas.json")),
      stableV2: sha256(join(stable, "codex_app_server_protocol.v2.schemas.json")),
      experimental: sha256(join(experimental, "codex_app_server_protocol.schemas.json")),
      experimentalV2: sha256(join(experimental, "codex_app_server_protocol.v2.schemas.json")),
    },
    records: referenceRecordBindings.map((binding) => buildRecordBinding(binding, bundles)),
    enums: referenceEnumBindings.map((binding) => ({
      javaType: binding.javaType,
      sources: binding.sources.map(bindingSourceName),
      wireValues: [...new Set(binding.sources.flatMap((source) => {
        const { bundle, schema } = resolveBindingSource(source, bundles);
        return enumValues(schema, bundle);
      }))].sort(),
    })),
  };
}

function checkReferenceBindings(experimental, stable, updateBindings) {
  const generated = buildReferenceBindings(experimental, stable);
  if (updateBindings) {
    mkdirSync(dirname(referenceBindingsPath), { recursive: true });
    writeFileSync(referenceBindingsPath, `${JSON.stringify(generated, null, 2)}\n`);
    console.log(`PASS wrote compact reference bindings to ${referenceBindingsPath}`);
    return;
  }
  assert.ok(
    existsSync(referenceBindingsPath),
    "reference bindings are missing; run " +
      "node scripts/codex-app-server-compat.mjs --update-bindings",
  );
  assert.deepEqual(
    readJson(referenceBindingsPath),
    generated,
    "committed App Server reference bindings differ from the exact generated schema",
  );
  console.log("PASS compact Java projection matches the exact generated schema");
}

function staticCompatibilityCheck(updateBindings) {
  const root = mkdtempSync(join(tmpdir(), "bytequay-codex-schema-"));
  const codexHome = join(root, "codex-home");
  const temporaryDirectory = join(root, "tmp");
  const experimental = join(root, "experimental");
  const stable = join(root, "stable");
  mkdirSync(codexHome, { mode: 0o700 });
  mkdirSync(temporaryDirectory, { mode: 0o700 });
  const environment = {
    ...minimalProcessEnvironment(root, temporaryDirectory),
    CODEX_HOME: codexHome,
    CODEX_SQLITE_HOME: codexHome,
  };
  try {
    const versionOutput = run("codex", ["--version"], { env: environment });
    assert.equal(
      versionOutput,
      `codex-cli ${contract.version}`,
      `expected codex-cli ${contract.version}, got ${versionOutput}`,
    );
    run("codex", [
      "app-server",
      "generate-json-schema",
      "--experimental",
      "--out",
      experimental,
    ], { env: environment });
    run("codex", ["app-server", "generate-json-schema", "--out", stable], {
      env: environment,
    });

    assert.equal(
      sha256(join(experimental, "codex_app_server_protocol.schemas.json")),
      contract.experimentalBundleSha256,
      "experimental protocol bundle changed",
    );
    assert.equal(
      sha256(join(experimental, "codex_app_server_protocol.v2.schemas.json")),
      contract.experimentalV2BundleSha256,
      "experimental v2 protocol bundle changed",
    );
    assert.equal(
      sha256(join(stable, "codex_app_server_protocol.schemas.json")),
      contract.stableBundleSha256,
      "stable protocol bundle changed",
    );
    assert.equal(
      sha256(join(stable, "codex_app_server_protocol.v2.schemas.json")),
      contract.stableV2BundleSha256,
      "stable v2 protocol bundle changed",
    );

    checkSchema(experimental, true);
    checkSchema(stable, false);
    checkReferenceBindings(experimental, stable, updateBindings);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }

  console.log(`PASS exact Codex version ${contract.version}`);
  console.log(
    `PASS generated stable and experimental schema, including child-thread contract (${contract.schemaCommand})`,
  );
}

class JsonRpcProcess {
  constructor(child) {
    this.child = child;
    this.nextId = 1;
    this.pending = new Map();
    this.notifications = [];
    this.notificationCount = 0;
    this.serverRequestHandler = null;
    this.serverRequests = [];
    this.waiters = [];
    this.stderrBytes = 0;
    this.failure = null;

    createInterface({ input: child.stdout }).on("line", (line) => {
      try {
        this.#receive(line);
      } catch (error) {
        this.#disconnect(error);
      }
    });
    child.stdin.on("error", (error) => {
      this.#disconnect(new Error(`app-server input failed: ${error.message}`));
    });
    child.stdout.on("error", (error) => {
      this.#disconnect(new Error(`app-server output failed: ${error.message}`));
    });
    child.stderr.on("data", (chunk) => {
      this.stderrBytes += chunk.length;
    });
    child.stderr.on("error", (error) => {
      this.#disconnect(new Error(`app-server diagnostics failed: ${error.message}`));
    });
    child.on("error", (error) => {
      this.#disconnect(new Error(`unable to start app-server: ${error.message}`));
    });
    child.on("exit", (code, signal) => {
      this.#disconnect(new Error(
        `app-server exited (${code ?? signal}); diagnostic bytes: ${this.stderrBytes}`,
      ));
    });
  }

  request(method, params = {}, timeoutMs = 30_000) {
    if (this.failure) return Promise.reject(this.failure);
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${method} timed out after ${timeoutMs} ms`));
      }, timeoutMs);
      this.pending.set(id, { method, resolve, reject, timeout });
      try {
        this.#send({ id, method, params });
      } catch (error) {
        clearTimeout(timeout);
        this.pending.delete(id);
        reject(error);
      }
    });
  }

  notify(method, params = {}) {
    this.#send({ method, params });
  }

  handleServerRequests(method, respond) {
    assert.equal(this.serverRequestHandler, null, "server request handler already installed");
    this.serverRequestHandler = { method, respond };
  }

  stopHandlingServerRequests() {
    this.serverRequestHandler = null;
  }

  waitFor(method, predicate = () => true, timeoutMs = 180_000) {
    if (this.failure) return Promise.reject(this.failure);
    const buffered = this.notifications.find(
      (notification) => notification.method === method && predicate(notification.params),
    );
    if (buffered) return Promise.resolve(buffered.params);

    return new Promise((resolve, reject) => {
      const waiter = { method, predicate, resolve, reject };
      waiter.timeout = setTimeout(() => {
        this.waiters = this.waiters.filter((candidate) => candidate !== waiter);
        reject(new Error(`${method} notification timed out after ${timeoutMs} ms`));
      }, timeoutMs);
      this.waiters.push(waiter);
    });
  }

  #send(message) {
    if (this.failure) throw this.failure;
    if (!this.child.stdin.writable) throw new Error("app-server input is closed");
    this.child.stdin.write(`${JSON.stringify(message)}\n`);
  }

  #receive(line) {
    let message;
    try {
      message = JSON.parse(line);
    } catch {
      throw new Error(
        `app-server emitted ${Buffer.byteLength(line)} bytes of non-JSON stdout`,
      );
    }

    if (message.id !== undefined && message.method) {
      this.#handleServerRequest(message);
      return;
    }
    if (message.id !== undefined) {
      const pending = this.pending.get(message.id);
      if (!pending) return;
      clearTimeout(pending.timeout);
      this.pending.delete(message.id);
      if (message.error) {
        pending.reject(
          new Error(`${pending.method} failed with JSON-RPC code ${message.error.code ?? "unknown"}`),
        );
      } else {
        pending.resolve(message.result);
      }
      return;
    }
    if (!message.method) return;

    this.notificationCount++;
    this.notifications.push(message);
    if (this.notifications.length > 2_000) this.notifications.shift();
    for (const waiter of [...this.waiters]) {
      if (waiter.method === message.method && waiter.predicate(message.params)) {
        clearTimeout(waiter.timeout);
        this.waiters = this.waiters.filter((candidate) => candidate !== waiter);
        waiter.resolve(message.params);
      }
    }
  }

  #handleServerRequest(message) {
    const handler = this.serverRequestHandler;
    if (!handler || handler.method !== message.method) {
      this.#send({
        id: message.id,
        error: { code: -32601, message: `unexpected CP1 request: ${message.method}` },
      });
      throw new Error(`unexpected App Server request: ${message.method}`);
    }
    const result = handler.respond(message.params);
    this.serverRequests.push(message);
    this.#send({ id: message.id, result });
  }

  #disconnect(error) {
    if (this.failure) return;
    this.failure = error;
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timeout);
      pending.reject(error);
    }
    this.pending.clear();
    for (const waiter of this.waiters) {
      clearTimeout(waiter.timeout);
      waiter.reject(error);
    }
    this.waiters = [];
  }
}

function processGroupExists(pid) {
  if (!pid) return false;
  try {
    process.kill(-pid, 0);
    return true;
  } catch (error) {
    if (error.code === "ESRCH") return false;
    throw error;
  }
}

function signalProcessGroup(pid, signal) {
  if (!pid) return;
  try {
    process.kill(-pid, signal);
  } catch (error) {
    if (error.code !== "ESRCH") throw error;
  }
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function waitForProcessGroupExit(pid, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (processGroupExists(pid) && Date.now() < deadline) {
    await delay(50);
  }
  return !processGroupExists(pid);
}

async function stopProcess(child) {
  const pid = child?.pid;
  if (!pid || !processGroupExists(pid)) return;
  signalProcessGroup(pid, "SIGTERM");
  if (await waitForProcessGroupExit(pid, 5_000)) return;
  signalProcessGroup(pid, "SIGKILL");
  assert.ok(
    await waitForProcessGroupExit(pid, 2_000),
    `process group ${pid} remained after SIGKILL`,
  );
}

async function startAppServer(cwd, environment, registerChild, launchProfile) {
  const child = spawn("codex", appServerArguments(launchProfile), {
    cwd,
    detached: true,
    env: environment,
    stdio: ["pipe", "pipe", "pipe"],
  });
  registerChild(child);
  const rpc = new JsonRpcProcess(child);
  await rpc.request("initialize", {
    clientInfo: { name: "bytequay-cp1-compat", version: "1" },
    capabilities: { experimentalApi: true, requestAttestation: false },
  });
  rpc.notify("initialized");
  return { child, rpc };
}

function configLayerFolder(name) {
  assert.ok(name && typeof name === "object", "config layer lacks a source");
  switch (name.type) {
    case "system":
    case "user":
      assert.equal(typeof name.file, "string", `${name.type} config layer lacks its file`);
      return dirname(name.file);
    case "project":
      assert.equal(
        typeof name.dotCodexFolder,
        "string",
        "project config layer lacks its .codex folder",
      );
      return name.dotCodexFolder;
    case "packagedDefaults":
    case "legacyManagedConfigTomlFromFile":
    case "mdm":
    case "enterpriseManaged":
    case "sessionFlags":
    case "legacyManagedConfigTomlFromMdm":
      return null;
    default:
      assert.fail(`unsupported config layer source ${name.type}`);
  }
}

function assertNoAmbientExecPolicyRules(config, codexHome) {
  assert.ok(Array.isArray(config.layers), "config/read omitted requested layer details");
  let userLayers = 0;
  for (const layer of config.layers) {
    const folder = configLayerFolder(layer.name);
    if (layer.name.type === "user") {
      userLayers++;
      assert.equal(
        realpathSync(folder),
        realpathSync(codexHome),
        `App Server loaded user config ${layer.name.file}`,
      );
    }
    if (folder) {
      const rulesDirectory = join(folder, "rules");
      let entries;
      try {
        entries = readdirSync(rulesDirectory, { withFileTypes: true });
      }
      catch (error) {
        if (error?.code !== "ENOENT") {
          throw error;
        }
        entries = [];
      }
      const rules = entries
        .filter((entry) => entry.name.endsWith(".rules"))
        .map((entry) => join(rulesDirectory, entry.name));
      assert.deepEqual(
        rules,
        [],
        `App Server config source contains exec-policy rules: ${rules.join(", ")}`,
      );
    }
    if (layer.name.type === "project") {
      assert.fail(`App Server loaded project config ${layer.name.dotCodexFolder}`);
    }
  }
  assert.equal(userLayers, 1, "config/read did not report exactly one isolated user layer");
}

function assertNoManagedRequirements(response) {
  assert.ok(
    response && Object.hasOwn(response, "requirements"),
    "configRequirements/read omitted requirements",
  );
  assert.deepEqual(
    Object.keys(response),
    ["requirements"],
    "configRequirements/read returned an unknown managed-policy field",
  );
  assert.equal(
    response.requirements,
    null,
    "App Server loaded opaque managed requirements",
  );
}

function entriesByCwd(response, cwds, method) {
  assert.ok(Array.isArray(response?.data), `${method} omitted its data array`);
  assert.equal(
    response.data.length,
    cwds.length,
    `${method} returned an unexpected number of cwd entries`,
  );
  const canonicalCwds = cwds.map((cwd) => realpathSync(cwd));
  const requested = new Set(canonicalCwds);
  assert.equal(requested.size, cwds.length, `${method} received duplicate cwd values`);
  const entries = new Map();
  for (const entry of response.data) {
    assert.equal(typeof entry.cwd, "string", `${method} returned an entry without cwd`);
    const cwd = realpathSync(entry.cwd);
    assert.ok(requested.has(cwd), `${method} returned unrequested cwd ${entry.cwd}`);
    assert.ok(!entries.has(cwd), `${method} returned duplicate cwd ${entry.cwd}`);
    entries.set(cwd, entry);
  }
  return canonicalCwds.map((cwd) => entries.get(cwd));
}

function assertNoAmbientHooks(response, cwds) {
  for (const entry of entriesByCwd(response, cwds, "hooks/list")) {
    assert.deepEqual(entry.hooks, [], `lifecycle hooks discovered for ${entry.cwd}`);
    assert.deepEqual(entry.errors, [], `hook discovery failed for ${entry.cwd}`);
    assert.deepEqual(entry.warnings, [], `hook discovery warned for ${entry.cwd}`);
  }
}

function assertCapabilityGuardsRejectPoison(cwd, codexHome) {
  const inRootDirectory = join(
    codexHome,
    "skills",
    ".system",
    "bytequay-poison-control",
  );
  const inRootPath = join(inRootDirectory, "SKILL.md");
  const outOfRootPath = join(cwd, "bytequay-poison-skill.md");
  mkdirSync(inRootDirectory, { recursive: true, mode: 0o700 });
  writeFileSync(inRootPath, "poison control\n");
  writeFileSync(outOfRootPath, "poison control\n");
  const skillsResponse = (skill, errors = []) => ({
    data: [{ cwd, errors, skills: skill ? [skill] : [] }],
  });
  const systemSkill = {
    name: "bytequay-poison-control",
    path: inRootPath,
    pluginId: null,
    scope: "system",
  };
  assert.throws(
    () => assertOnlyBundledSystemSkills(
      skillsResponse({ ...systemSkill, scope: "repo" }),
      [cwd],
      codexHome,
    ),
    /non-system skill/,
    "repository skill passed the skill-origin guard",
  );
  assert.throws(
    () => assertOnlyBundledSystemSkills(
      skillsResponse({ ...systemSkill, pluginId: "poison-plugin" }),
      [cwd],
      codexHome,
    ),
    /plugin skill/,
    "plugin skill passed the skill-origin guard",
  );
  assert.throws(
    () => assertOnlyBundledSystemSkills(
      skillsResponse({ ...systemSkill, path: outOfRootPath }),
      [cwd],
      codexHome,
    ),
    /escaped the isolated system-skill root/,
    "out-of-root skill passed the skill-origin guard",
  );
  assert.throws(
    () => assertOnlyBundledSystemSkills(
      skillsResponse(null, [{ message: "poison", path: outOfRootPath }]),
      [cwd],
      codexHome,
    ),
    /skill discovery failed/,
    "skill discovery error passed the skill-origin guard",
  );
  assert.throws(
    () => assertNoAmbientHooks({
      data: [{ cwd, errors: [], hooks: [{}], warnings: [] }],
    }, [cwd]),
    /lifecycle hooks discovered/,
    "discovered hook passed the hook guard",
  );
}

function assertOnlyBundledSystemSkills(response, cwds, codexHome) {
  const isolatedHome = realpathSync(codexHome);
  let systemSkillsRoot;
  for (const entry of entriesByCwd(response, cwds, "skills/list")) {
    assert.deepEqual(entry.errors, [], `skill discovery failed for ${entry.cwd}`);
    assert.ok(Array.isArray(entry.skills), `skills/list omitted skills for ${entry.cwd}`);
    for (const skill of entry.skills) {
      assert.equal(skill.scope, "system", `non-system skill ${skill.name} was discovered`);
      assert.equal(skill.pluginId ?? null, null, `plugin skill ${skill.name} was discovered`);
      assert.equal(typeof skill.path, "string", `skill ${skill.name} omitted its path`);
      if (!systemSkillsRoot) {
        systemSkillsRoot = realpathSync(join(codexHome, "skills", ".system"));
        assertPathWithin(
          isolatedHome,
          systemSkillsRoot,
          "system-skill root escaped the isolated Codex home",
        );
      }
      const skillPath = realpathSync(skill.path);
      assertPathWithin(
        systemSkillsRoot,
        skillPath,
        `skill ${skill.name} escaped the isolated system-skill root`,
      );
    }
  }
}

function assertPathWithin(root, candidate, message) {
  const pathWithinRoot = relative(root, candidate);
  assert.ok(
    pathWithinRoot !== "" &&
      pathWithinRoot !== ".." &&
      !pathWithinRoot.startsWith(`..${sep}`) &&
      !isAbsolute(pathWithinRoot),
    message,
  );
}

function assertLaunchPolicy(response, launchProfile) {
  const config = response?.config;
  assert.ok(config && typeof config === "object", "config/read omitted effective config");
  assert.equal(config.web_search, "live", "native web search was not enabled");
  assert.equal(config.approval_policy, "on-request", "native approval policy changed");
  assert.equal(config.approvals_reviewer, "auto_review", "native automatic review was not selected");
  assert.equal(config.project_doc_max_bytes, 0, "Codex still loads AGENTS.md itself");
  assert.equal(config.features?.guardian_approval, true, "native automatic review was disabled");
  assert.deepEqual(config.mcp_servers, {}, "isolated launch inherited MCP servers");
  assert.equal(
    config.features?.skip_host_skill_discovery,
    true,
    "host skill discovery was not disabled",
  );
  for (const feature of alwaysDisabledFeatures) {
    assert.equal(config.features?.[feature], false, `feature ${feature} was not disabled`);
  }
  for (const feature of ["multi_agent", "multi_agent_v2"]) {
    assert.equal(
      config.features?.[feature],
      launchProfile[feature],
      `feature ${feature} did not match the ${launchProfile.name} launch profile`,
    );
  }
  if (launchProfile.agentMaxDepth !== undefined) {
    assert.equal(
      config.agents?.max_depth,
      launchProfile.agentMaxDepth,
      `effective agent depth did not match ${launchProfile.name}`,
    );
  }
  assert.deepEqual(
    config.shell_environment_policy,
    {
      inherit: "core",
      ignore_default_excludes: false,
      exclude: null,
      set: null,
      include_only: shellEnvironmentIncludeOnly,
      filters: null,
      experimental_use_profile: false,
    },
    "effective shell environment policy changed",
  );
  const sessionFlagLayers = response.layers?.filter(
    (layer) => layer.name.type === "sessionFlags",
  );
  assert.equal(
    sessionFlagLayers?.length,
    1,
    "config/read did not report exactly one session-flags layer",
  );
  const sessionFlagLayer = sessionFlagLayers[0];
  assert.equal(
    sessionFlagLayer.disabledReason ?? null,
    null,
    "the session-flags layer is disabled",
  );
  const sessionFlags = sessionFlagLayer.config;
  assert.equal(sessionFlags.web_search, "live", "session flags omitted web-search policy");
  assert.equal(sessionFlags.approval_policy, "on-request", "session flags omitted approval policy");
  assert.equal(sessionFlags.approvals_reviewer, "auto_review", "session flags omitted native automatic review");
  assert.equal(sessionFlags.project_doc_max_bytes, 0, "session flags omitted the project-doc override");
  assert.equal(sessionFlags.features?.guardian_approval, true, "session flags disabled native automatic review");
  assert.equal(
    sessionFlags.features?.skip_host_skill_discovery,
    true,
    "session flags omitted host-skill isolation",
  );
  for (const feature of alwaysDisabledFeatures) {
    assert.equal(
      sessionFlags.features?.[feature],
      false,
      `session flags omitted disabled feature ${feature}`,
    );
  }
  for (const feature of ["multi_agent", "multi_agent_v2"]) {
    assert.equal(
      sessionFlags.features?.[feature],
      launchProfile[feature],
      `session flags did not preserve ${feature} for ${launchProfile.name}`,
    );
  }
  if (launchProfile.agentMaxDepth !== undefined) {
    assert.equal(
      sessionFlags.agents?.max_depth,
      launchProfile.agentMaxDepth,
      `session flags omitted agent depth for ${launchProfile.name}`,
    );
  }
  assert.deepEqual(
    sessionFlags.shell_environment_policy,
    {
      inherit: "core",
      ignore_default_excludes: false,
      include_only: shellEnvironmentIncludeOnly,
      experimental_use_profile: false,
    },
    "session flags omitted the shell environment policy",
  );
}

async function checkAppServerConfigSourceIsolation(launchProfile) {
  const root = mkdtempSync(join(tmpdir(), `bytequay-codex-${launchProfile.name}-`));
  let child;
  let failure;
  let interruptedSignal;
  const signalErrors = [];
  const handleSignal = (signal) => {
    const firstSignal = !interruptedSignal;
    interruptedSignal ??= signal;
    process.exitCode = signal === "SIGINT" ? 130 : 143;
    try {
      signalProcessGroup(child?.pid, firstSignal ? "SIGTERM" : "SIGKILL");
    } catch (error) {
      signalErrors.push(error);
    }
  };
  const handleSigint = () => handleSignal("SIGINT");
  const handleSigterm = () => handleSignal("SIGTERM");
  process.on("SIGINT", handleSigint);
  process.on("SIGTERM", handleSigterm);
  try {
    const codexHome = join(root, "codex-home");
    const temporaryDirectory = join(root, "tmp");
    const cleanWorkspace = join(root, "clean-workspace");
    const poisonedRulesDirectory = join(codexHome, "rules");
    const poisonedRule = join(poisonedRulesDirectory, "poison.rules");
    mkdirSync(codexHome, { mode: 0o700 });
    mkdirSync(temporaryDirectory, { mode: 0o700 });
    mkdirSync(cleanWorkspace, { mode: 0o700 });
    mkdirSync(poisonedRulesDirectory, { mode: 0o700 });
    writeFileSync(poisonedRule, execPolicyRule);
    const environment = {
      ...minimalProcessEnvironment(root, temporaryDirectory),
      CODEX_HOME: codexHome,
      CODEX_SQLITE_HOME: codexHome,
    };
    const poisonDecision = JSON.parse(run("codex", [
      "execpolicy",
      "check",
      "--rules",
      poisonedRule,
      "--",
      ...execPolicyAllowedCommand,
    ], { cwd: cleanWorkspace, env: environment }));
    assert.equal(poisonDecision.decision, "allow", "poison rule is not an allow rule");
    assert.equal(poisonDecision.matchedRules?.length, 1, "poison rule did not match once");
    assert.doesNotMatch(
      run("codex", ["app-server", "--help"], { cwd: cleanWorkspace, env: environment }),
      /--ignore-rules\b/,
      "the reference App Server gained an exec-policy isolation option; re-review the gate",
    );
    const started = await startAppServer(
      cleanWorkspace,
      environment,
      (startedChild) => {
        child = startedChild;
        if (interruptedSignal) {
          try {
            signalProcessGroup(child.pid, "SIGKILL");
          } catch (error) {
            signalErrors.push(error);
          }
        }
      },
      launchProfile,
    );
    ({ child } = started);
    const [poisonedConfig, requirements] = await Promise.all([
      started.rpc.request("config/read", { cwd: cleanWorkspace, includeLayers: true }),
      started.rpc.request("configRequirements/read", null),
    ]);
    assertNoManagedRequirements(requirements);
    const userLayer = poisonedConfig.layers?.find((layer) => layer.name.type === "user");
    assert.ok(userLayer, "config/read omitted the rule-bearing user layer");
    assert.equal(userLayer.disabledReason ?? null, null, "the rule-bearing user layer is disabled");
    assert.throws(
      () => assertNoAmbientExecPolicyRules(poisonedConfig, codexHome),
      /config source contains exec-policy rules/,
      "rule-bearing user config passed the App Server source gate",
    );
    rmSync(poisonedRule);
    const [cleanConfig, skills, hooks] = await Promise.all([
      started.rpc.request("config/read", {
        cwd: cleanWorkspace,
        includeLayers: true,
      }),
      started.rpc.request("skills/list", {
        cwds: [cleanWorkspace],
        forceReload: true,
      }),
      started.rpc.request("hooks/list", { cwds: [cleanWorkspace] }),
    ]);
    assertLaunchPolicy(cleanConfig, launchProfile);
    assertOnlyBundledSystemSkills(skills, [cleanWorkspace], codexHome);
    assertNoAmbientHooks(hooks, [cleanWorkspace]);
    assertCapabilityGuardsRejectPoison(cleanWorkspace, codexHome);
    assertNoAmbientExecPolicyRules(cleanConfig, codexHome);
  } catch (error) {
    failure = error;
  } finally {
    const cleanupErrors = [];
    try {
      if (child?.stdin.writable) child.stdin.end();
    } catch (error) {
      cleanupErrors.push(error);
    }
    let childStopped = true;
    try {
      await stopProcess(child);
    } catch (error) {
      childStopped = false;
      cleanupErrors.push(error);
    }
    if (childStopped) {
      try {
        rmSync(root, { recursive: true, force: true });
      } catch (error) {
        cleanupErrors.push(error);
      }
    } else {
      cleanupErrors.push(new Error(`retained ${root} because the process group did not stop`));
    }
    if (interruptedSignal) {
      cleanupErrors.push(new Error(`received ${interruptedSignal}`), ...signalErrors);
    }
    process.off("SIGINT", handleSigint);
    process.off("SIGTERM", handleSigterm);
    if (cleanupErrors.length > 0) {
      failure = new AggregateError(
        failure ? [failure, ...cleanupErrors] : cleanupErrors,
        interruptedSignal
          ? "App Server source-isolation check interrupted"
          : "App Server source-isolation check cleanup failed",
      );
    }
  }
  if (failure) throw failure;
  console.log(
    `PASS App Server ${launchProfile.name} launch profile and config-source isolation`,
  );
}

function assertTaskNotificationIsolation(notifications, historiesByThread) {
  const turnOwners = new Map();
  const itemOwners = new Map();
  const itemTurns = new Map();
  for (const [threadId, thread] of historiesByThread) {
    assert.equal(thread.id, threadId, `history key did not match thread ${threadId}`);
    for (const turn of thread.turns) {
      assert.ok(!turnOwners.has(turn.id), `turn ${turn.id} belongs to two task histories`);
      turnOwners.set(turn.id, threadId);
      for (const item of turn.items) {
        assert.ok(item.id, `turn ${turn.id} contained an item without an id`);
        assert.ok(!itemOwners.has(item.id), `item ${item.id} belongs to two task histories`);
        itemOwners.set(item.id, threadId);
        itemTurns.set(item.id, turn.id);
      }
    }
  }

  let threadScopedCount = 0;
  for (const notification of notifications) {
    const threadId = notification.params?.threadId;
    if (threadId === undefined) continue;
    threadScopedCount++;
    assert.ok(
      historiesByThread.has(threadId),
      `notification ${notification.method} crossed into thread ${threadId}`,
    );
    const eventTurnIds = new Set(
      [notification.params.turnId, notification.params.turn?.id]
        .filter((turnId) => turnId !== undefined),
    );
    for (const turnId of eventTurnIds) {
      assert.equal(
        turnOwners.get(turnId),
        threadId,
        `notification ${notification.method} crossed turns in thread ${threadId}`,
      );
    }
    const itemIds = [
      notification.params.item?.id,
      notification.params.itemId,
      ...(notification.params.turn?.items ?? []).map((item) => item.id),
    ];
    for (const itemId of itemIds) {
      if (itemId === undefined) continue;
      assert.equal(
        itemOwners.get(itemId),
        threadId,
        `notification ${notification.method} crossed items into thread ${threadId}`,
      );
      for (const turnId of eventTurnIds) {
        assert.equal(
          itemTurns.get(itemId),
          turnId,
          `notification ${notification.method} crossed items into turn ${turnId}`,
        );
      }
    }
  }
  assert.ok(threadScopedCount > 0, "task isolation window contained no thread events");
}

function checkTaskNotificationIsolation() {
  const histories = new Map([
    ["thread-a", { id: "thread-a", turns: [{ id: "turn-a", items: [{ id: "item-a" }] }] }],
    ["thread-b", { id: "thread-b", turns: [{ id: "turn-b", items: [{ id: "item-b" }] }] }],
  ]);
  assertTaskNotificationIsolation([
    { method: "turn/started", params: { threadId: "thread-a", turn: { id: "turn-a" } } },
    {
      method: "item/started",
      params: { threadId: "thread-b", turnId: "turn-b", item: { id: "item-b" } },
    },
  ], histories);
  assert.throws(
    () => assertTaskNotificationIsolation([
      { method: "item/started", params: { threadId: "thread-c", turnId: "turn-c" } },
    ], histories),
    /crossed into thread thread-c/,
  );
  assert.throws(
    () => assertTaskNotificationIsolation([
      { method: "item/started", params: { threadId: "thread-a", turnId: "turn-b" } },
    ], histories),
    /crossed turns in thread thread-a/,
  );
  assert.throws(
    () => assertTaskNotificationIsolation([
      {
        method: "item/started",
        params: { threadId: "thread-a", turnId: "turn-a", item: { id: "item-b" } },
      },
    ], histories),
    /crossed items into thread thread-a/,
  );
  assert.throws(
    () => assertTaskNotificationIsolation([], new Map([
      ["thread-a", histories.get("thread-a")],
      ["thread-b", {
        id: "thread-b",
        turns: [{ id: "turn-b", items: [{ id: "item-a" }] }],
      }],
    ])),
    /item item-a belongs to two task histories/,
  );
  assert.throws(
    () => assertTaskNotificationIsolation([
      {
        method: "turn/completed",
        params: {
          threadId: "thread-a",
          turn: { id: "turn-a", items: [{ id: "item-b" }] },
        },
      },
    ], histories),
    /crossed items into thread thread-a/,
  );
}

function appServerArguments(launchProfile) {
  const args = [
    "app-server",
    "--listen",
    "stdio://",
    "--strict-config",
    "--enable",
    "skip_host_skill_discovery",
    "--enable",
    "guardian_approval",
    "-c",
    'cli_auth_credentials_store="file"',
    "-c",
    'web_search="live"',
    "-c",
    'approval_policy="on-request"',
    "-c",
    'approvals_reviewer="auto_review"',
    "-c",
    "project_doc_max_bytes=0",
    "-c",
    'shell_environment_policy.inherit="core"',
    "-c",
    "shell_environment_policy.ignore_default_excludes=false",
    "-c",
    `shell_environment_policy.include_only=${JSON.stringify(shellEnvironmentIncludeOnly)}`,
    "-c",
    "shell_environment_policy.experimental_use_profile=false",
  ];
  for (const feature of alwaysDisabledFeatures) args.push("--disable", feature);
  for (const feature of ["multi_agent", "multi_agent_v2"]) {
    args.push(launchProfile[feature] ? "--enable" : "--disable", feature);
  }
  if (launchProfile.agentMaxDepth !== undefined) {
    args.push("-c", `agents.max_depth=${launchProfile.agentMaxDepth}`);
  }
  return args;
}

async function main() {
  const flags = new Set(process.argv.slice(2));
  for (const flag of flags) {
    assert.ok(flag === "--update-bindings", `unknown option ${flag}`);
  }
  staticCompatibilityCheck(flags.has("--update-bindings"));
  checkTaskNotificationIsolation();
  for (const launchProfile of appServerLaunchProfiles) {
    await checkAppServerConfigSourceIsolation(launchProfile);
  }
}

main().catch((error) => {
  console.error(`FAIL ${error.stack ?? error}`);
  process.exitCode ||= 1;
});
