#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  existsSync,
  lstatSync,
  mkdtempSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  realpathSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { homedir, tmpdir } from "node:os";
import { dirname, isAbsolute, join, relative, sep } from "node:path";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";

const contract = {
  version: "0.151.0",
  schemaCommand:
    "codex app-server generate-json-schema --experimental --out <temporary-directory>",
  experimentalBundleSha256:
    "31ae67beb2c94cc9509f6a71968600062dc8c6d7fe45437ed3a9129838f4d2d9",
  experimentalV2BundleSha256:
    "a586cdc50f84c56c7654387e869b470a689796fa1c57678dcafb5921bb2d5255",
  stableBundleSha256:
    "424b204943b18e5ffa52667a2aa397c9950730ec1e49ad767e2a016743990541",
  stableV2BundleSha256:
    "2442b15801bc019ad55987ad03e0f0ae60c51417825b9b6d708db640e6c2651c",
};

const referenceBindingsPath = fileURLToPath(new URL(
  "../backend/agent-cli/src/test/resources/com/bytequay/agent/cli/codex/app-server-v0.151.0-bindings.json",
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
  ], [component("params"), component("emittedAtMs")]),
  recordBinding("TurnCompletedNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "turn/completed"),
  ], [component("params"), component("emittedAtMs")]),
  recordBinding("ItemStartedNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "item/started"),
  ], [component("params"), component("emittedAtMs")]),
  recordBinding("ItemCompletedNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "item/completed"),
  ], [component("params"), component("emittedAtMs")]),
  recordBinding("CommandOutputNotification", "decode", [
    schemaSource(
      "v2",
      "ServerNotification",
      "method",
      "item/commandExecution/outputDelta",
    ),
  ], [component("params"), component("emittedAtMs")]),
  recordBinding("AgentMessageDeltaNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "item/agentMessage/delta"),
  ], [component("params"), component("emittedAtMs")]),
  recordBinding("PlanDeltaNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "item/plan/delta"),
  ], [component("params"), component("emittedAtMs")]),
  recordBinding("ThreadStartedNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "thread/started"),
  ], [component("params"), component("emittedAtMs")]),
  recordBinding("ThreadStartedParams", "decode", [
    schemaSource("v2", "ThreadStartedNotification"),
  ], [component("thread")]),
  recordBinding("PlanUpdatedNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "turn/plan/updated"),
  ], [component("params"), component("emittedAtMs")]),
  recordBinding("TokenUsageUpdatedNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "thread/tokenUsage/updated"),
  ], [component("params"), component("emittedAtMs")]),
  recordBinding("ErrorNotification", "decode", [
    schemaSource("v2", "ServerNotification", "method", "error"),
  ], [component("params"), component("emittedAtMs")]),
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
    component("status"),
    component("durationMs"),
  ]),
  recordBinding("DynamicToolCallItem", "decode", [
    schemaSource("v2", "ThreadItem", "type", "dynamicToolCall"),
  ], [component("id"), component("tool"), component("status")]),
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
  ], [component("id"), component("query")]),
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
  ], [component("total")]),
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
  recordBinding("ThreadBackgroundTerminalsListParams", "encode", [
    schemaSource("v2", "ThreadBackgroundTerminalsListParams"),
  ], [component("threadId"), component("cursor")]),
  recordBinding("ThreadBackgroundTerminalsListResponse", "decode", [
    schemaSource("v2", "ThreadBackgroundTerminalsListResponse"),
  ], [component("data"), component("nextCursor")]),
  recordBinding("ThreadItemsListParams", "encode", [
    schemaSource("v2", "ThreadItemsListParams"),
  ], [component("threadId"), component("cursor")]),
  recordBinding("ThreadItemsListResponse", "decode", [
    schemaSource("v2", "ThreadItemsListResponse"),
  ], [component("data"), component("nextCursor")]),
  recordBinding("ThreadItemEntry", "decode", [
    schemaSource("v2", "ThreadItemEntry"),
  ], [component("item"), component("turnId")]),
  recordBinding("ThreadBackgroundTerminalsTerminateParams", "encode", [
    schemaSource("v2", "ThreadBackgroundTerminalsTerminateParams"),
  ], [component("threadId"), component("processId")]),
  recordBinding("ThreadBackgroundTerminal", "decode", [
    schemaSource("v2", "ThreadBackgroundTerminal"),
  ], [
    component("processId"),
    component("itemId"),
    component("command"),
    component("cwd"),
  ]),
  recordBinding("HistoryThread", "decode", [schemaSource("v2", "Thread")], [
    component("id"),
    component("sessionId"),
    component("parentThreadId"),
    component("turns"),
    component("agentNickname"),
    component("agentRole"),
  ]),
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
    component("approvalPolicy", "approvalPolicy", "on-request"),
    component("sandbox", "sandbox", "workspace-write"),
    component("serviceName"),
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
    component("cwd"),
    component("approvalPolicy", "approvalPolicy", "on-request"),
    component("sandbox", "sandbox", "workspace-write"),
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
  ]),
  recordBinding("ThreadParams", "encode", [
    schemaSource("v2", "ThreadUnsubscribeParams"),
  ], [component("threadId")]),
  recordBinding("TurnParams", "encode", [schemaSource("v2", "TurnInterruptParams")], [
    component("threadId"),
    component("turnId"),
  ]),
  recordBinding("CommandApprovalResponse", "encode", [
    schemaSource("all", "CommandExecutionRequestApprovalResponse"),
  ], [sampledComponent("decision")]),
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
  // One level, matching the shipped launch profile: a task's model may delegate, and what it
  // delegates to may not. A subagent still spawns at this depth.
  agentMaxDepth: 1,
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

const liveProfileRuntimeEntries = [
  "archived_sessions",
  "config.toml",
  "history.jsonl",
  "session_index.jsonl",
  "sessions",
  "shell_snapshots",
  "thread-writer-locks",
  "tmp",
];
const liveProfileLockName = ".bytequay-live-gate-lock";

const passiveTurnItemTypes = new Set([
  "agentMessage",
  "contextCompaction",
  "plan",
  "reasoning",
  "userMessage",
]);

const proofFileName = "bytequay-cp1-proof.txt";
const proofFileContents = "bytequay-cp1-proof\n";
const taskBProofFileName = "bytequay-cp1-task-b-proof.txt";
const taskBProofFileContents = "bytequay-cp1-task-b-proof\n";
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
const environmentEvidenceSuffix =
  'if [ -z "${CODEX_API_KEY+x}" ]; then key_state=absent; ' +
  "else key_state=present; fi; " +
  'if [ -z "${OPENAI_API_KEY+x}" ]; then openai_key_state=absent; ' +
  "else openai_key_state=present; fi; " +
  "printf 'CODEX_API_KEY_VISIBLE=%s\\nOPENAI_API_KEY_VISIBLE=%s\\n" +
  "APP_SERVER_SENTINEL=%s\\n" +
  "CODEX_HOME=%s\\nHOME=%s\\nPWD=%s\\n' \"$key_state\" " +
  '"$openai_key_state" ' +
  '"${BYTEQUAY_CP1_APP_SERVER_SENTINEL-<unset>}" ' +
  '"${CODEX_HOME-<unset>}" "$HOME" "$PWD"';
const environmentEvidenceCommand =
  `printf 'bytequay-cp1-proof\\n' > ${proofFileName}; ${environmentEvidenceSuffix}`;
const taskBEnvironmentEvidenceCommand =
  `printf 'bytequay-cp1-task-b-proof\\n' > ${taskBProofFileName}; ` +
  environmentEvidenceSuffix;

const environmentEvidencePrompt =
  "Use the shell exactly once to run the command in this code block verbatim. " +
  "Do not add punctuation to it:\n\n" +
  `\`\`\`sh\n${environmentEvidenceCommand}\n\`\`\`\n\n` +
  "Then reply with that exact output. Do not read files or use any other tool.";
const taskBEnvironmentEvidencePrompt =
  "Use the shell exactly once to run the command in this code block verbatim. " +
  "Do not add punctuation to it:\n\n" +
  `\`\`\`sh\n${taskBEnvironmentEvidenceCommand}\n\`\`\`\n\n` +
  "Then reply with that exact output. Do not read files or use any other tool.";
const backgroundEvidenceCommand =
  `${environmentEvidenceCommand}; ` +
  "printf 'BYTEQUAY_CP1_BACKGROUND_READY=1\\n'; exec /bin/sleep 300";
const backgroundEvidencePrompt =
  "Use the shell exactly once to run the command in this code block verbatim. " +
  "Do not add punctuation to it:\n\n" +
  `\`\`\`sh\n${backgroundEvidenceCommand}\n\`\`\`\n\n` +
  "Set yield_time_ms to 250. When it yields a running process, immediately reply exactly " +
  "bytequay-background-yielded. Do not call write_stdin or use another tool.";
const rootChildMarker = "BYTEQUAY_ROOT_OK";
const childMarker = "BYTEQUAY_CHILD_OK";
const grandchildMarker = "BYTEQUAY_GRANDCHILD_OK";
const childEvidencePrompt =
  "This is a compatibility probe. Use only spawn and wait subagent controls. " +
  "Spawn exactly one child and wait for it. Tell the child: 'Use only spawn and wait " +
  "subagent controls. Spawn exactly one grandchild and wait for it. Tell the grandchild " +
  `to use no tools, spawn no agent, and reply exactly ${grandchildMarker}. Then reply ` +
  `exactly ${childMarker}.' After the child finishes, reply exactly ${rootChildMarker}. ` +
  "Do not contact, interrupt, close, or resume any other agent.";

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

function startExecServer(cwd, environment, registerChild) {
  const child = spawn("codex", ["exec-server", "--listen", "ws://127.0.0.1:0"], {
    cwd,
    detached: true,
    env: environment,
    stdio: ["ignore", "pipe", "pipe"],
  });
  registerChild(child);
  const server = { child, failure: null, url: null };
  return new Promise((resolve, reject) => {
    let stderrBytes = 0;
    let settled = false;
    let timeout;
    const fail = async (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      try {
        await stopProcess(child);
        reject(error);
      } catch (cleanupError) {
        reject(new AggregateError([error, cleanupError], "exec-server startup and cleanup failed"));
      }
    };
    const reportFailure = (error) => {
      if (settled) {
        server.failure ??= error;
      } else {
        void fail(error);
      }
    };
    timeout = setTimeout(() => {
      void fail(new Error("exec-server startup timed out"));
    }, 15_000);
    createInterface({ input: child.stdout }).once("line", (line) => {
      if (settled) return;
      clearTimeout(timeout);
      if (!/^ws:\/\/127\.0\.0\.1:\d+$/.test(line)) {
        void fail(new Error(`unexpected exec-server endpoint: ${line}`));
        return;
      }
      settled = true;
      server.url = line;
      resolve(server);
    });
    child.stderr.on("data", (chunk) => {
      stderrBytes += chunk.length;
    });
    child.stdout.on("error", (error) => {
      reportFailure(new Error(`exec-server output failed: ${error.message}`));
    });
    child.stderr.on("error", (error) => {
      reportFailure(new Error(`exec-server diagnostics failed: ${error.message}`));
    });
    child.once("error", (error) => {
      reportFailure(new Error(`unable to start exec-server: ${error.message}`));
    });
    child.once("exit", (code, signal) => {
      reportFailure(new Error(
        `exec-server exited (${code ?? signal}); diagnostic bytes: ${stderrBytes}`,
      ));
    });
  });
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
  assert.equal(config.web_search, "disabled", "web search was not disabled");
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
  assert.equal(sessionFlags.web_search, "disabled", "session flags omitted web-search policy");
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

function assertRuntimeHealthy(rpc, execServers) {
  assert.ifError(rpc.failure);
  assert.ok(processGroupExists(rpc.child.pid), "App Server exited before PASS");
  for (const [name, server] of execServers) {
    assert.ifError(server.failure);
    assert.ok(
      processGroupExists(server.child.pid),
      `${name} exec-server exited before PASS`,
    );
  }
}

function canonicalPath(uri) {
  assert.equal(new URL(uri).protocol, "file:", `expected canonical file URI, got ${uri}`);
  return realpathSync(fileURLToPath(uri));
}

async function completeTurn(rpc, params, timeoutMs = 180_000) {
  const started = await rpc.request("turn/start", params);
  const completed = await rpc.waitFor(
    "turn/completed",
    (notification) => notification.threadId === params.threadId &&
      notification.turn.id === started.turn.id,
    timeoutMs,
  );
  assert.equal(
    completed.turn.status,
    "completed",
    `turn ${completed.turn.id} ended ${completed.turn.status}`,
  );
  return completed.turn;
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

async function waitForNoBackgroundTerminals(rpc, threadId, timeoutMs = 5_000) {
  const deadline = Date.now() + timeoutMs;
  while (true) {
    const response = await rpc.request("thread/backgroundTerminals/list", {
      threadId,
      limit: 100,
    });
    if (response.data.length === 0) return response;
    assert.ok(Date.now() < deadline, `background terminals remained after ${timeoutMs} ms`);
    await delay(50);
  }
}

async function waitForBackgroundTerminal(rpc, threadId, itemId, timeoutMs = 5_000) {
  const deadline = Date.now() + timeoutMs;
  while (true) {
    const response = await rpc.request("thread/backgroundTerminals/list", {
      threadId,
      limit: 100,
    });
    if (response.data.length === 1 && response.data[0].itemId === itemId) return response;
    assert.ok(Date.now() < deadline, `background terminal ${itemId} was not listed`);
    await delay(50);
  }
}

function pathAtOrWithin(root, candidate) {
  const candidateRelativeToRoot = relative(root, candidate);
  return candidateRelativeToRoot === "" ||
    (candidateRelativeToRoot !== ".." &&
      !candidateRelativeToRoot.startsWith(`..${sep}`) &&
      !isAbsolute(candidateRelativeToRoot));
}

function ambientCodexHomes() {
  return new Set([
    join(homedir(), ".codex"),
    process.env.CODEX_HOME?.trim(),
  ].filter(Boolean));
}

function requiredLiveCodexHome(root) {
  const configuredHome = process.env.BYTEQUAY_CODEX_COMPAT_HOME;
  assert.ok(
    configuredHome?.trim(),
    "live gate requires BYTEQUAY_CODEX_COMPAT_HOME pointing to a dedicated " +
      "ChatGPT-signed-in Codex profile; never copy auth.json",
  );
  assert.ok(
    isAbsolute(configuredHome.trim()),
    "BYTEQUAY_CODEX_COMPAT_HOME must be an absolute path",
  );
  assert.ok(
    existsSync(configuredHome.trim()) && statSync(configuredHome.trim()).isDirectory(),
    "BYTEQUAY_CODEX_COMPAT_HOME must name an existing directory",
  );
  const codexHome = realpathSync(configuredHome.trim());
  for (const ambientCodexHome of ambientCodexHomes()) {
    if (!existsSync(ambientCodexHome)) continue;
    assert.notEqual(
      codexHome,
      realpathSync(ambientCodexHome),
      "live gate refuses default and ambient Codex profiles; sign in to a dedicated profile",
    );
  }
  const fixtureRoot = realpathSync(root);
  assert.equal(
    pathAtOrWithin(fixtureRoot, codexHome),
    false,
    "BYTEQUAY_CODEX_COMPAT_HOME must remain outside the disposable fixture root",
  );
  const repositoryRoot = realpathSync(fileURLToPath(new URL("../", import.meta.url)));
  assert.equal(
    pathAtOrWithin(repositoryRoot, codexHome),
    false,
    "BYTEQUAY_CODEX_COMPAT_HOME must remain outside the ByteQuay repository",
  );
  assert.equal(
    statSync(codexHome).mode & 0o777,
    0o700,
    "BYTEQUAY_CODEX_COMPAT_HOME must have mode 0700",
  );
  return codexHome;
}

function assertLiveProfileReady(codexHome) {
  const authPath = join(codexHome, "auth.json");
  assert.ok(existsSync(authPath), "dedicated Codex profile has no auth.json; sign in directly");
  const authLinkStat = lstatSync(authPath);
  assert.ok(
    authLinkStat.isFile() && !authLinkStat.isSymbolicLink(),
    "dedicated Codex profile auth.json must be a regular file, not a link",
  );
  const authStat = statSync(authPath);
  assert.equal(authStat.nlink, 1, "dedicated Codex profile auth.json must not be hard-linked");
  const auth = readJson(authPath);
  assert.equal(
    auth.auth_mode,
    "chatgpt",
    "dedicated Codex profile must use ChatGPT auth before App Server starts",
  );
  const refreshToken = auth.tokens?.refresh_token;
  assert.ok(
    typeof refreshToken === "string" && refreshToken.trim(),
    "dedicated ChatGPT profile has no refresh credential; sign in directly",
  );
  const authHash = sha256(authPath);
  for (const ambientCodexHome of ambientCodexHomes()) {
    const ambientAuthPath = join(ambientCodexHome, "auth.json");
    if (!existsSync(ambientAuthPath)) continue;
    const ambientAuthStat = statSync(ambientAuthPath);
    assert.ok(
      authStat.dev !== ambientAuthStat.dev || authStat.ino !== ambientAuthStat.ino,
      "dedicated Codex profile auth.json must not share the ambient credential file",
    );
    assert.ok(
      authHash !== sha256(ambientAuthPath),
      "dedicated Codex profile auth.json must not copy the ambient credential file",
    );
    let ambientAuth;
    try {
      ambientAuth = readJson(ambientAuthPath);
    }
    catch {
      ambientAuth = null;
    }
    const ambientRefreshToken = ambientAuth?.tokens?.refresh_token;
    if (typeof ambientRefreshToken === "string" && ambientRefreshToken.trim()) {
      assert.ok(
        refreshToken !== ambientRefreshToken,
        "dedicated Codex profile must not reuse the ambient refresh credential",
      );
    }
  }
  const instructionEntries = ["AGENTS.override.md", "AGENTS.md"].filter(
    (entry) => existsSync(join(codexHome, entry)),
  );
  assert.deepEqual(
    instructionEntries,
    [],
    "auth-only Codex profile must not contain persistent instruction files",
  );
  const existingRuntimeEntries = liveProfileRuntimeEntries.filter(
    (entry) => existsSync(join(codexHome, entry)),
  );
  assert.deepEqual(
    existingRuntimeEntries,
    [],
    "dedicated Codex profile contains runtime/config state from another run; " +
      "remove only the listed fixture artifacts before retrying",
  );
}

function acquireLiveProfileLock(codexHome) {
  const lockPath = join(codexHome, liveProfileLockName);
  try {
    mkdirSync(lockPath, { mode: 0o700 });
    writeFileSync(join(lockPath, "owner"), `${process.pid}\n`, { mode: 0o600 });
  }
  catch (error) {
    if (error?.code === "EEXIST") {
      throw new Error(
        `another live compatibility gate owns ${lockPath}; ` +
          "do not share one ChatGPT profile concurrently",
      );
    }
    rmSync(lockPath, { recursive: true, force: true });
    throw error;
  }
  return lockPath;
}

function removeGeneratedLiveConfig(codexHome) {
  rmSync(join(codexHome, "config.toml"), { force: true });
}

function cleanLiveProfileRuntime(codexHome) {
  for (const entry of liveProfileRuntimeEntries) {
    rmSync(join(codexHome, entry), { recursive: true, force: true });
  }
}

function releaseLiveProfileLock(lockPath) {
  if (lockPath) rmSync(lockPath, { recursive: true, force: true });
}

function appServerProcessEnvironment(root, codexHome) {
  const temporaryDirectory = join(root, "app-server-tmp");
  const sqliteHome = join(root, "app-server-state");
  mkdirSync(temporaryDirectory, { mode: 0o700 });
  mkdirSync(sqliteHome, { mode: 0o700 });
  const environment = {
    ...minimalProcessEnvironment(root, temporaryDirectory),
    BYTEQUAY_CP1_APP_SERVER_SENTINEL: "must-not-reach-tools",
    CODEX_HOME: codexHome,
    CODEX_SQLITE_HOME: sqliteHome,
  };
  for (const name of [
    "ALL_PROXY",
    "HTTPS_PROXY",
    "HTTP_PROXY",
    "NO_PROXY",
    "all_proxy",
    "https_proxy",
    "http_proxy",
    "no_proxy",
  ]) {
    if (process.env[name]) environment[name] = process.env[name];
  }
  return environment;
}

function executorProcessEnvironment(root, name, cwd) {
  const codexHome = join(root, `${name}-codex-home`);
  const temporaryDirectory = join(root, `${name}-tmp`);
  mkdirSync(codexHome, { mode: 0o700 });
  mkdirSync(temporaryDirectory, { mode: 0o700 });
  return {
    environment: {
      ...minimalProcessEnvironment(cwd, temporaryDirectory),
      CODEX_HOME: codexHome,
      CODEX_SQLITE_HOME: codexHome,
    },
    codexHome,
  };
}

function initializeDisposableGitWorktree(cwd, environment) {
  run("git", ["init", "--quiet"], { cwd, env: environment });
  assert.equal(
    run("git", ["status", "--short", "--untracked-files=all"], {
      cwd,
      env: environment,
    }),
    "",
    `new disposable Git worktree is not clean: ${cwd}`,
  );
}

function assertProofFile(
  cwd,
  environment,
  fileName = proofFileName,
  contents = proofFileContents,
) {
  assert.equal(
    readFileSync(join(cwd, fileName), "utf8"),
    contents,
    `unexpected proof file content in ${cwd}`,
  );
  assert.equal(
    run("git", ["status", "--short", "--untracked-files=all"], {
      cwd,
      env: environment,
    }),
    `?? ${fileName}`,
    `bounded proof file was not the only Git worktree mutation in ${cwd}`,
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
    "-c",
    'cli_auth_credentials_store="file"',
    "-c",
    'forced_login_method="chatgpt"',
    "-c",
    'web_search="disabled"',
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

async function verifyPreThreadIsolation(rpc, codexHome, cwds, launchProfile) {
  const [hooks, skills, configs, requirements] = await Promise.all([
    rpc.request("hooks/list", { cwds }),
    rpc.request("skills/list", { cwds, forceReload: true }),
    Promise.all(cwds.map((cwd) =>
      rpc.request("config/read", { cwd, includeLayers: true }))),
    rpc.request("configRequirements/read", null),
  ]);
  assertNoAmbientHooks(hooks, cwds);
  assertOnlyBundledSystemSkills(skills, cwds, codexHome);
  for (const config of configs) {
    assertLaunchPolicy(config, launchProfile);
    assertNoAmbientExecPolicyRules(config, codexHome);
  }
  assertNoManagedRequirements(requirements);
}

function assertChatgptSubscriptionAccount(response) {
  assert.equal(
    response?.requiresOpenaiAuth,
    true,
    "account/read did not require OpenAI authentication",
  );
  assert.equal(
    response?.account?.type,
    "chatgpt",
    "live gate requires a ChatGPT-signed-in Codex profile; API-key auth is deferred",
  );
}

async function verifyChatgptSubscriptionAccount(rpc) {
  const response = await rpc.request("account/read", { refreshToken: false });
  assertChatgptSubscriptionAccount(response);
}

async function verifyCapabilityIsolation(
  rpc,
  threadIdOrIds,
  codexHome,
  cwds,
  launchProfile,
) {
  const threadIds = Array.isArray(threadIdOrIds) ? threadIdOrIds : [threadIdOrIds];
  const [mcpStatuses] = await Promise.all([
    Promise.all(threadIds.map((threadId) =>
      rpc.request("mcpServerStatus/list", {
        threadId,
        limit: 100,
        detail: "toolsAndAuthOnly",
      }))),
    verifyPreThreadIsolation(rpc, codexHome, cwds, launchProfile),
  ]);
  for (const mcpStatus of mcpStatuses) {
    assert.deepEqual(mcpStatus.data, [], "isolated thread inherited MCP servers");
  }
}

function recordsExactCommandAction(command, expectedCommand) {
  return command.commandActions?.length === 1 &&
    command.commandActions[0].command === expectedCommand;
}

function commandActionRanIn(command, cwd, expectedCommand) {
  return recordsExactCommandAction(command, expectedCommand) &&
    realpathSync(command.cwd) === realpathSync(cwd);
}

function commandRanIn(
  command,
  cwd,
  expectedCommand,
  observedOutput = command.aggregatedOutput,
) {
  const apiKeyVisibility = observedOutput
    ?.match(/^CODEX_API_KEY_VISIBLE=(.+)$/m)?.[1].trim();
  const openaiApiKeyVisibility = observedOutput
    ?.match(/^OPENAI_API_KEY_VISIBLE=(.+)$/m)?.[1].trim();
  const appServerSentinel = observedOutput
    ?.match(/^APP_SERVER_SENTINEL=(.+)$/m)?.[1].trim();
  const codexHome = observedOutput?.match(/^CODEX_HOME=(.+)$/m)?.[1].trim();
  const home = observedOutput?.match(/^HOME=(.+)$/m)?.[1].trim();
  const workingDirectory = observedOutput?.match(/^PWD=(.+)$/m)?.[1].trim();
  return commandActionRanIn(command, cwd, expectedCommand) &&
    apiKeyVisibility === "absent" &&
    openaiApiKeyVisibility === "absent" &&
    appServerSentinel === "<unset>" &&
    codexHome === "<unset>" &&
    home &&
    workingDirectory &&
    realpathSync(home) === realpathSync(cwd) &&
    realpathSync(workingDirectory) === realpathSync(cwd);
}

function commandItemDiagnostics(items) {
  return JSON.stringify(
    items.map((item) => ({
      actionCount: item.commandActions?.length ?? 0,
      actionTypes: item.commandActions?.map((action) => action.type) ?? [],
      cwd: item.cwd,
      exitCode: item.exitCode,
      id: item.id,
      outputBytes: typeof item.aggregatedOutput === "string"
        ? Buffer.byteLength(item.aggregatedOutput)
        : null,
      status: item.status,
      type: item.type,
    })),
  );
}

function assertSingleCommand(
  items,
  cwd,
  label,
  expectedCommand = environmentEvidenceCommand,
  expectedStatus = "completed",
  expectedExitCode = expectedStatus === "completed" ? 0 : undefined,
  observedOutput,
  allowMissingOutput = false,
) {
  const activeItems = items.filter((item) => !passiveTurnItemTypes.has(item.type));
  assert.equal(
    activeItems.length,
    1,
    `${label} executed unexpected active items: ${commandItemDiagnostics(activeItems)}`,
  );
  assert.equal(activeItems[0].type, "commandExecution", `${label} did not use the shell`);
  assert.ok(activeItems[0].id, `${label} command had no item id`);
  const output = observedOutput === undefined
    ? activeItems[0].aggregatedOutput
    : observedOutput;
  const commandMatches = allowMissingOutput && output == null
    ? commandActionRanIn(activeItems[0], cwd, expectedCommand)
    : commandRanIn(activeItems[0], cwd, expectedCommand, output);
  assert.ok(
    commandMatches,
    `${label} lacked environment evidence: ${commandItemDiagnostics(activeItems)}`,
  );
  assert.equal(activeItems[0].status, expectedStatus, `${label} had unexpected status`);
  if (expectedExitCode === null) {
    assert.ok(activeItems[0].exitCode == null, `${label} exited before it was terminated`);
  } else if (expectedExitCode !== undefined) {
    assert.equal(activeItems[0].exitCode, expectedExitCode, `${label} had unexpected exit code`);
  }
  return activeItems[0];
}

function streamedCommandOutput(notifications, threadId, turnId, itemId) {
  const output = notifications
    .filter(
      (notification) =>
        notification.method === "item/commandExecution/outputDelta" &&
        notification.params.threadId === threadId &&
        notification.params.turnId === turnId &&
        notification.params.itemId === itemId,
    )
    .map((notification) => notification.params.delta)
    .join("");
  return output || null;
}

function assertSingleCommandStart(items, label) {
  const activeItems = items.filter((item) => !passiveTurnItemTypes.has(item.type));
  assert.equal(
    activeItems.length,
    1,
    `${label} started unexpected active items: ${commandItemDiagnostics(activeItems)}`,
  );
  assert.equal(
    activeItems[0].type,
    "commandExecution",
    `${label} did not start with the shell`,
  );
  assert.ok(activeItems[0].id, `${label} command had no item id`);
  return activeItems[0];
}

function childLaunchProfileForVersion(version) {
  if (version === "v1") return v1ChildLaunchProfile;
  if (version === "v2") return v2ChildLaunchProfile;
  throw new Error(`model does not advertise a supported multi-agent version: ${version}`);
}

async function listAllModels(rpc) {
  const models = [];
  let cursor = null;
  let pages = 0;
  do {
    const response = await rpc.request("model/list", {
      cursor,
      includeHidden: false,
      limit: 100,
    });
    assert.ok(Array.isArray(response.data), "model/list omitted data");
    models.push(...response.data);
    cursor = response.nextCursor ?? null;
    pages++;
    assert.ok(pages <= 20, "model/list exceeded 20 pages");
  } while (cursor);
  return models;
}

function childThreadIds(thread, multiAgentVersion) {
  assert.ok(["v1", "v2"].includes(multiAgentVersion));
  const children = new Set();
  for (const turn of thread.turns ?? []) {
    for (const item of turn.items ?? []) {
      if (
        multiAgentVersion === "v1" &&
        item.type === "collabAgentToolCall" &&
        item.tool === "spawnAgent"
      ) {
        assert.equal(item.senderThreadId, thread.id, "V1 spawn sender did not own its history");
        for (const childId of item.receiverThreadIds) {
          assert.ok(childId && childId !== thread.id, "V1 spawn returned an invalid child ID");
          children.add(childId);
        }
      }
      if (
        multiAgentVersion === "v2" &&
        item.type === "subAgentActivity" &&
        item.kind === "started"
      ) {
        assert.ok(
          item.agentThreadId && item.agentThreadId !== thread.id,
          "V2 activity returned an invalid child ID",
        );
        children.add(item.agentThreadId);
      }
    }
  }
  return children;
}

function childHistorySignature(threads, multiAgentVersion) {
  return JSON.stringify(
    [...threads.values()]
      .sort((left, right) => left.id.localeCompare(right.id))
      .map((thread) => [
        thread.id,
        thread.parentThreadId ?? null,
        thread.sessionId,
        thread.turns,
        [...childThreadIds(thread, multiAgentVersion)].sort(),
      ]),
  );
}

function childHistoryDiagnostics(threads, multiAgentVersion) {
  return JSON.stringify([...threads.values()].map((thread) => ({
    children: [...childThreadIds(thread, multiAgentVersion)].sort(),
    id: thread.id,
    parentThreadId: thread.parentThreadId ?? null,
    turns: thread.turns.map((turn) => ({
      id: turn.id,
      itemTypes: turn.items.map((item) => item.type),
      status: turn.status,
    })),
  })));
}

async function readRecursiveChildHistory(rpc, rootThreadId, multiAgentVersion) {
  const threads = new Map();
  const pending = [rootThreadId];
  while (pending.length > 0) {
    const threadId = pending.shift();
    if (threads.has(threadId)) continue;
    const response = await rpc.request(
      "thread/read",
      { threadId, includeTurns: true },
      10_000,
    );
    assert.equal(response.thread.id, threadId, "thread/read returned another thread");
    threads.set(threadId, response.thread);
    assert.ok(threads.size <= 3, "the child probe created more than three threads");
    for (const childId of childThreadIds(response.thread, multiAgentVersion)) {
      if (!threads.has(childId) && !pending.includes(childId)) pending.push(childId);
    }
  }
  return threads;
}

async function waitForRecursiveChildHistory(
  rpc,
  rootThreadId,
  multiAgentVersion,
  timeoutMs = 60_000,
) {
  const deadline = Date.now() + timeoutMs;
  let previousSignature = null;
  let latest = new Map();
  let latestFailure;
  while (Date.now() < deadline) {
    try {
      latest = await readRecursiveChildHistory(rpc, rootThreadId, multiAgentVersion);
    } catch (error) {
      latestFailure = error;
      previousSignature = null;
      await delay(250);
      continue;
    }
    latestFailure = undefined;
    const signature = childHistorySignature(latest, multiAgentVersion);
    const complete = [...latest.values()].every(
      (thread) => thread.turns.length === 1 && thread.turns[0].status === "completed",
    );
    if (latest.size === 3 && complete) {
      try {
        assertChildTree(latest, rootThreadId, multiAgentVersion);
        if (signature === previousSignature) return latest;
      } catch (error) {
        latestFailure = error;
      }
    }
    previousSignature = signature;
    await delay(250);
  }
  throw new Error(
    `recursive ${multiAgentVersion} child history did not reach a stable completed tree: ` +
      `${latestFailure?.message ?? childHistoryDiagnostics(latest, multiAgentVersion)}`,
  );
}

function assertChildActivity(thread, childId, multiAgentVersion) {
  const items = thread.turns.flatMap((turn) => turn.items);
  if (multiAgentVersion === "v1") {
    const spawns = items.filter(
      (item) => item.type === "collabAgentToolCall" &&
        item.tool === "spawnAgent" &&
        item.receiverThreadIds.includes(childId),
    );
    assert.equal(spawns.length, 1, `${thread.id} did not record exactly one V1 spawn`);
    assert.equal(spawns[0].senderThreadId, thread.id);
    assert.deepEqual(spawns[0].receiverThreadIds, [childId]);
    assert.equal(spawns[0].status, "completed");
    assert.ok(spawns[0].agentsStates[childId], "V1 spawn omitted the child state");
    return null;
  }

  const activities = items.filter(
    (item) => item.type === "subAgentActivity" && item.agentThreadId === childId,
  );
  assert.ok(activities.length > 0, `${thread.id} did not record V2 child activity`);
  assert.equal(
    activities.filter((item) => item.kind === "started").length,
    1,
    `${thread.id} did not record exactly one V2 child start`,
  );
  const paths = new Set(activities.map((item) => item.agentPath));
  assert.equal(paths.size, 1, `${thread.id} changed the V2 child path`);
  const path = [...paths][0];
  assert.ok(path.startsWith("/"), `${thread.id} returned a non-absolute V2 child path`);
  return path;
}

function assertChildTree(threads, rootThreadId, multiAgentVersion) {
  assert.equal(threads.size, 3, "child history did not contain exactly three threads");
  const root = threads.get(rootThreadId);
  assert.ok(root, "child history omitted the root");
  assert.equal(root.parentThreadId ?? null, null, "root unexpectedly has a parent");
  const rootChildren = [...childThreadIds(root, multiAgentVersion)];
  assert.equal(rootChildren.length, 1, "root did not create exactly one child");
  const child = threads.get(rootChildren[0]);
  assert.ok(child, "child history was not loaded");
  assert.equal(child.parentThreadId, root.id, "child direct parent changed");
  const childChildren = [...childThreadIds(child, multiAgentVersion)];
  assert.equal(childChildren.length, 1, "child did not create exactly one grandchild");
  const grandchild = threads.get(childChildren[0]);
  assert.ok(grandchild, "grandchild history was not loaded");
  assert.equal(grandchild.parentThreadId, child.id, "grandchild direct parent changed");
  assert.deepEqual(
    [...childThreadIds(grandchild, multiAgentVersion)],
    [],
    "grandchild created another agent",
  );

  const markers = new Map([
    [root.id, rootChildMarker],
    [child.id, childMarker],
    [grandchild.id, grandchildMarker],
  ]);
  const allowedItemTypes = new Set([
    ...passiveTurnItemTypes,
    "collabAgentToolCall",
    "subAgentActivity",
  ]);
  const knownThreadIds = new Set(threads.keys());
  const expectedParents = new Map([
    [child.id, root.id],
    [grandchild.id, child.id],
  ]);
  const v2Paths = new Map();
  for (const thread of threads.values()) {
    assert.equal(thread.sessionId, root.sessionId, `${thread.id} left the root session tree`);
    assert.equal(thread.turns.length, 1, `${thread.id} did not have exactly one turn`);
    assert.equal(thread.turns[0].status, "completed", `${thread.id} turn did not complete`);
    const items = thread.turns.flatMap((turn) => turn.items);
    const messages = items.filter((item) => item.type === "agentMessage");
    assert.ok(messages.length > 0, `${thread.id} omitted its completion message`);
    assert.equal(
      messages.at(-1).text.trim(),
      markers.get(thread.id),
      `${thread.id} did not end with its exact completion marker`,
    );
    for (const item of items) {
      assert.ok(
        allowedItemTypes.has(item.type),
        `${thread.id} used disallowed or unknown item type ${item.type}`,
      );
      if (item.type === "collabAgentToolCall") {
        assert.ok(
          item.tool === "spawnAgent" || item.tool === "wait",
          `${thread.id} used disallowed V1 collaboration tool ${item.tool}`,
        );
        assert.equal(item.status, "completed", `${thread.id} has incomplete V1 activity`);
        assert.equal(item.senderThreadId, thread.id, `${thread.id} has foreign V1 activity`);
        for (const receiverThreadId of item.receiverThreadIds) {
          assert.ok(
            knownThreadIds.has(receiverThreadId),
            `${thread.id} contacted unknown V1 thread ${receiverThreadId}`,
          );
          if (item.tool === "spawnAgent") {
            assert.equal(
              expectedParents.get(receiverThreadId),
              thread.id,
              `${thread.id} recorded a V1 spawn that contradicts the parent graph`,
            );
          }
        }
        for (const agentThreadId of Object.keys(item.agentsStates)) {
          assert.ok(
            knownThreadIds.has(agentThreadId),
            `${thread.id} reported unknown V1 agent ${agentThreadId}`,
          );
        }
      }
      if (item.type === "subAgentActivity") {
        assert.notEqual(item.kind, "interrupted", `${thread.id} has interrupted V2 activity`);
        assert.ok(
          knownThreadIds.has(item.agentThreadId),
          `${thread.id} contacted unknown V2 thread ${item.agentThreadId}`,
        );
        assert.ok(item.agentPath.startsWith("/"), `${thread.id} has non-absolute V2 activity`);
        if (item.kind === "started") {
          assert.equal(
            expectedParents.get(item.agentThreadId),
            thread.id,
            `${thread.id} recorded a V2 start that contradicts the parent graph`,
          );
        }
        const previousPath = v2Paths.get(item.agentThreadId);
        assert.ok(
          previousPath === undefined || previousPath === item.agentPath,
          `${item.agentThreadId} changed V2 path`,
        );
        v2Paths.set(item.agentThreadId, item.agentPath);
      }
    }
  }

  assert.ok(
    grandchild.turns[0].items.every((item) => item.type !== "collabAgentToolCall"),
    "grandchild used a V1 collaboration tool",
  );

  const childPath = assertChildActivity(root, child.id, multiAgentVersion);
  const grandchildPath = assertChildActivity(child, grandchild.id, multiAgentVersion);
  if (multiAgentVersion === "v2") {
    assert.ok(
      grandchildPath.startsWith(`${childPath}/`),
      "V2 grandchild path is not nested beneath the child",
    );
  }
}

async function assertNoChildBackgroundTerminals(rpc, threadIds) {
  for (const threadId of threadIds) {
    let cursor = null;
    let pages = 0;
    do {
      const response = await rpc.request("thread/backgroundTerminals/list", {
        cursor,
        limit: 100,
        threadId,
      });
      assert.deepEqual(response.data, [], `${threadId} has a background terminal`);
      cursor = response.nextCursor ?? null;
      pages++;
      assert.ok(pages <= 20, `${threadId} background terminals exceeded 20 pages`);
    } while (cursor);
  }
}

function checkChildHistoryHelpers() {
  const thread = (id, parentThreadId, marker, items) => ({
    id,
    parentThreadId,
    sessionId: "synthetic-session",
    turns: [{
      id: `turn-${id}`,
      status: "completed",
      items: [...items, { type: "agentMessage", text: marker }],
    }],
  });
  const v1Spawn = (senderThreadId, receiverThreadId) => ({
    type: "collabAgentToolCall",
    tool: "spawnAgent",
    status: "completed",
    senderThreadId,
    receiverThreadIds: [receiverThreadId],
    agentsStates: { [receiverThreadId]: { status: "completed" } },
  });
  const v1Root = thread(
    "root-v1",
    null,
    rootChildMarker,
    [v1Spawn("root-v1", "child-v1")],
  );
  const v1Child = thread(
    "child-v1",
    "root-v1",
    childMarker,
    [v1Spawn("child-v1", "grandchild-v1")],
  );
  const v1Grandchild = thread(
    "grandchild-v1",
    "child-v1",
    grandchildMarker,
    [],
  );
  assertChildTree(new Map([
    [v1Root.id, v1Root],
    [v1Child.id, v1Child],
    [v1Grandchild.id, v1Grandchild],
  ]), v1Root.id, "v1");

  const v2Activity = (agentThreadId, agentPath, kind) => ({
    type: "subAgentActivity",
    agentThreadId,
    agentPath,
    kind,
  });
  const v2Root = thread(
    "root-v2",
    null,
    rootChildMarker,
    [
      v2Activity("child-v2", "/child-v2", "started"),
      v1Spawn("root-v2", "child-v2"),
    ],
  );
  const v2Child = thread(
    "child-v2",
    "root-v2",
    childMarker,
    [
      v2Activity("root-v2", "/", "interacted"),
      v2Activity("grandchild-v2", "/child-v2/grandchild-v2", "started"),
    ],
  );
  const v2Grandchild = thread(
    "grandchild-v2",
    "child-v2",
    grandchildMarker,
    [v2Activity("child-v2", "/child-v2", "interacted")],
  );
  assert.deepEqual([...childThreadIds(v2Child, "v2")], ["grandchild-v2"]);
  const v2Threads = new Map([
    [v2Root.id, v2Root],
    [v2Child.id, v2Child],
    [v2Grandchild.id, v2Grandchild],
  ]);
  assertChildTree(v2Threads, v2Root.id, "v2");
  const poisonedRoot = {
    ...v2Root,
    turns: [{
      ...v2Root.turns[0],
      items: [{ type: "commandExecution" }, ...v2Root.turns[0].items],
    }],
  };
  const poisonedThreads = new Map(v2Threads).set(poisonedRoot.id, poisonedRoot);
  assert.notEqual(
    childHistorySignature(v2Threads, "v2"),
    childHistorySignature(poisonedThreads, "v2"),
    "child history signature ignored an item change",
  );
  assert.throws(
    () => assertChildTree(poisonedThreads, poisonedRoot.id, "v2"),
    /disallowed or unknown item type commandExecution/,
  );
  const contradictoryV1Root = {
    ...v2Root,
    turns: [{
      ...v2Root.turns[0],
      items: [...v2Root.turns[0].items, v1Spawn("root-v2", "grandchild-v2")],
    }],
  };
  assert.throws(
    () => assertChildTree(
      new Map(v2Threads).set(contradictoryV1Root.id, contradictoryV1Root),
      contradictoryV1Root.id,
      "v2",
    ),
    /V1 spawn that contradicts the parent graph/,
  );
  const contradictoryV2Root = {
    ...v1Root,
    turns: [{
      ...v1Root.turns[0],
      items: [
        ...v1Root.turns[0].items,
        v2Activity("grandchild-v1", "/grandchild-v1", "started"),
      ],
    }],
  };
  assert.throws(
    () => assertChildTree(
      new Map([
        [contradictoryV2Root.id, contradictoryV2Root],
        [v1Child.id, v1Child],
        [v1Grandchild.id, v1Grandchild],
      ]),
      contradictoryV2Root.id,
      "v1",
    ),
    /V2 start that contradicts the parent graph/,
  );
  const v2GrandchildWithWait = {
    ...v2Grandchild,
    turns: [{
      ...v2Grandchild.turns[0],
      items: [
        {
          type: "collabAgentToolCall",
          tool: "wait",
          status: "completed",
          senderThreadId: "grandchild-v2",
          receiverThreadIds: ["child-v2"],
          agentsStates: { "child-v2": { status: "completed" } },
        },
        ...v2Grandchild.turns[0].items,
      ],
    }],
  };
  assert.throws(
    () => assertChildTree(
      new Map(v2Threads).set(v2GrandchildWithWait.id, v2GrandchildWithWait),
      v2Root.id,
      "v2",
    ),
    /grandchild used a V1 collaboration tool/,
  );

  assert.equal(childLaunchProfileForVersion("v1").multi_agent, true);
  assert.equal(childLaunchProfileForVersion("v1").agentMaxDepth, 1);
  assert.equal(childLaunchProfileForVersion("v2").multi_agent_v2, true);
}

function checkSubscriptionAccountHelper() {
  assertChatgptSubscriptionAccount({
    account: { type: "chatgpt" },
    requiresOpenaiAuth: true,
  });
  for (const account of [{ type: "apiKey" }, null]) {
    assert.throws(
      () => assertChatgptSubscriptionAccount({ account, requiresOpenaiAuth: true }),
      /ChatGPT-signed-in Codex profile/,
    );
  }
}

function checkLiveProfileLockHelper() {
  const codexHome = mkdtempSync(join(tmpdir(), "bytequay-codex-lock-"));
  let lockPath;
  try {
    lockPath = acquireLiveProfileLock(codexHome);
    assert.throws(
      () => acquireLiveProfileLock(codexHome),
      /another live compatibility gate owns/,
    );
    releaseLiveProfileLock(lockPath);
    lockPath = acquireLiveProfileLock(codexHome);
  }
  finally {
    releaseLiveProfileLock(lockPath);
    rmSync(codexHome, { recursive: true, force: true });
  }
}

function checkLiveProfileReadinessHelper() {
  const codexHome = mkdtempSync(join(tmpdir(), "bytequay-codex-profile-"));
  try {
    writeFileSync(join(codexHome, "auth.json"), JSON.stringify({
      auth_mode: "chatgpt",
      tokens: { refresh_token: "synthetic-bytequay-refresh-token" },
    }), { mode: 0o600 });
    writeFileSync(join(codexHome, "AGENTS.md"), "synthetic instructions\n");
    assert.throws(
      () => assertLiveProfileReady(codexHome),
      /must not contain persistent instruction files/,
    );
    rmSync(join(codexHome, "AGENTS.md"));
    writeFileSync(join(codexHome, "config.toml"), "");
    assert.throws(
      () => assertLiveProfileReady(codexHome),
      /contains runtime\/config state/,
    );
  }
  finally {
    rmSync(codexHome, { recursive: true, force: true });
  }
}

async function liveCompatibilityCheck() {
  const root = mkdtempSync(join(tmpdir(), "bytequay-codex-live-"));
  const cwdA = join(root, "environment-a");
  const cwdB = join(root, "environment-b");

  let appServer;
  let codexHome;
  let execA;
  let execB;
  let liveProfileLock;
  let liveProfileRuntimeOwned = false;
  let rpc;
  let failure;
  let interruptedSignal;
  let rejectInterruption;
  const signalErrors = [];
  const children = new Set();
  const interruption = new Promise((_, reject) => {
    rejectInterruption = reject;
  });
  void interruption.catch(() => {});
  const interruptible = (promise) => Promise.race([promise, interruption]);
  const registerChild = (child) => {
    children.add(child);
    if (interruptedSignal) {
      try {
        signalProcessGroup(child.pid, "SIGKILL");
      } catch (error) {
        signalErrors.push(error);
      }
    }
  };
  const stopRegisteredChild = async (child) => {
    await stopProcess(child);
    children.delete(child);
  };
  const handleSignal = (signal) => {
    const firstSignal = !interruptedSignal;
    interruptedSignal ??= signal;
    process.exitCode = signal === "SIGINT" ? 130 : 143;
    if (firstSignal) rejectInterruption(new Error(`received ${signal}`));
    for (const child of children) {
      try {
        signalProcessGroup(child.pid, firstSignal ? "SIGTERM" : "SIGKILL");
      } catch (error) {
        signalErrors.push(error);
      }
    }
  };
  const handleSigint = () => handleSignal("SIGINT");
  const handleSigterm = () => handleSignal("SIGTERM");
  process.on("SIGINT", handleSigint);
  process.on("SIGTERM", handleSigterm);
  try {
    codexHome = requiredLiveCodexHome(root);
    liveProfileLock = acquireLiveProfileLock(codexHome);
    assertLiveProfileReady(codexHome);
    liveProfileRuntimeOwned = true;
    mkdirSync(cwdA);
    mkdirSync(cwdB);
    const appServerEnvironment = appServerProcessEnvironment(root, codexHome);
    const executorA = executorProcessEnvironment(root, "exec-a", cwdA);
    const executorB = executorProcessEnvironment(root, "exec-b", cwdB);
    initializeDisposableGitWorktree(cwdA, executorA.environment);
    initializeDisposableGitWorktree(cwdB, executorB.environment);
    assert.equal(Object.hasOwn(appServerEnvironment, "CODEX_API_KEY"), false);
    assert.equal(Object.hasOwn(appServerEnvironment, "OPENAI_API_KEY"), false);
    assert.equal(existsSync(join(executorA.codexHome, "auth.json")), false);
    assert.equal(existsSync(join(executorB.codexHome, "auth.json")), false);
    ({ child: appServer, rpc } = await interruptible(
      startAppServer(
        root,
        appServerEnvironment,
        registerChild,
        noChildLaunchProfile,
      ),
    ));
    await interruptible(verifyChatgptSubscriptionAccount(rpc));
    await interruptible(
      verifyPreThreadIsolation(rpc, codexHome, [cwdA, cwdB], noChildLaunchProfile),
    );
    execA = await interruptible(
      startExecServer(cwdA, executorA.environment, registerChild),
    );
    execB = await interruptible(
      startExecServer(cwdB, executorB.environment, registerChild),
    );

    const models = await interruptible(rpc.request("model/list", {}));
    const model = models.data.find((candidate) => candidate.isDefault) ?? models.data[0];
    assert.ok(model, "model/list returned no model");
    assert.ok(model.supportedReasoningEfforts.length > 0);
    assert.ok(
      model.supportedReasoningEfforts.some(
        (option) => option.reasoningEffort === model.defaultReasoningEffort,
      ),
      "model default reasoning effort is not in its advertised choices",
    );
    assert.ok(model.inputModalities.includes("text"));
    assert.ok(
      model.multiAgentVersion === null ||
        ["disabled", "v1", "v2"].includes(model.multiAgentVersion),
    );

    await interruptible(rpc.request("environment/add", {
      environmentId: "bytequay-cp1-a",
      execServerUrl: execA.url,
      connectTimeoutMs: 10_000,
    }));
    await interruptible(rpc.request("environment/add", {
      environmentId: "bytequay-cp1-b",
      execServerUrl: execB.url,
      connectTimeoutMs: 10_000,
    }));
    const [infoA, infoB] = await interruptible(Promise.all([
      rpc.request("environment/info", { environmentId: "bytequay-cp1-a" }),
      rpc.request("environment/info", { environmentId: "bytequay-cp1-b" }),
    ]));
    assert.equal(canonicalPath(infoA.cwd), realpathSync(cwdA));
    assert.equal(canonicalPath(infoB.cwd), realpathSync(cwdB));
    assert.notEqual(infoA.cwd, infoB.cwd);
    assert.ok(infoA.shell.name && infoA.shell.path);
    assert.ok(infoB.shell.name && infoB.shell.path);

    const environmentA = {
      environmentId: "bytequay-cp1-a",
      cwd: cwdA,
      runtimeWorkspaceRoots: [cwdA],
    };
    const environmentB = {
      environmentId: "bytequay-cp1-b",
      cwd: cwdB,
      runtimeWorkspaceRoots: [cwdB],
    };
    const startedA = await interruptible(rpc.request("thread/start", {
      cwd: cwdA,
      model: model.model,
      approvalPolicy: "untrusted",
      approvalsReviewer: "user",
      sandbox: "workspace-write",
      ephemeral: false,
      serviceName: "ByteQuay CP1 compatibility fixture",
      dynamicTools: [],
      selectedCapabilityRoots: [],
      environments: [environmentA],
    }));
    const startedB = await interruptible(rpc.request("thread/start", {
      cwd: cwdB,
      model: model.model,
      approvalPolicy: "never",
      approvalsReviewer: "user",
      sandbox: "workspace-write",
      ephemeral: false,
      serviceName: "ByteQuay CP1 compatibility fixture task B",
      dynamicTools: [],
      selectedCapabilityRoots: [],
      environments: [environmentB],
    }));
    const threadAId = startedA.thread.id;
    const threadBId = startedB.thread.id;
    assert.notEqual(threadAId, threadBId, "two tasks shared a root thread");
    assert.equal(startedA.thread.parentThreadId ?? null, null);
    assert.equal(startedB.thread.parentThreadId ?? null, null);
    assert.equal(startedA.thread.sessionId, threadAId, "task A is not a session root");
    assert.equal(startedB.thread.sessionId, threadBId, "task B is not a session root");
    assert.notEqual(
      startedA.thread.sessionId,
      startedB.thread.sessionId,
      "two tasks shared a session tree",
    );

    await interruptible(
      verifyCapabilityIsolation(
        rpc,
        [threadAId, threadBId],
        codexHome,
        [cwdA, cwdB],
        noChildLaunchProfile,
      ),
    );

    let approvalItemId;
    let approvalTurnId;
    const taskACommandApprovals = [];
    rpc.handleServerRequests("item/commandExecution/requestApproval", (params) => {
      assert.equal(params.threadId, threadAId);
      assert.ok(params.turnId, "command approval omitted turnId");
      assert.ok(params.itemId, "command approval omitted itemId");
      assert.equal(realpathSync(params.cwd), realpathSync(cwdA));
      assert.equal(params.environmentId, environmentA.environmentId);
      assert.equal(params.kind ?? "command", "command");
      approvalItemId ??= params.itemId;
      approvalTurnId ??= params.turnId;
      assert.equal(params.itemId, approvalItemId, "approval crossed command items");
      assert.equal(params.turnId, approvalTurnId, "approval crossed turns");
      if (params.availableDecisions) {
        assert.ok(
          params.availableDecisions.includes("accept"),
          "command approval did not offer accept",
        );
      }
      assert.ok(
        recordsExactCommandAction(params, environmentEvidenceCommand),
        "parent approval did not contain the exact expected command action",
      );
      assert.equal(
        existsSync(join(cwdA, proofFileName)),
        false,
        "proof file existed before parent command approval",
      );
      taskACommandApprovals.push(params);
      return { decision: "accept" };
    });
    const firstTurnNotificationStart = rpc.notifications.length;
    const firstCompletedTurn = await interruptible(completeTurn(rpc, {
      threadId: threadAId,
      input: [
        {
          type: "text",
          text: environmentEvidencePrompt,
          text_elements: [],
        },
      ],
      model: model.model,
      effort: model.defaultReasoningEffort,
      approvalPolicy: "untrusted",
      approvalsReviewer: "user",
      cwd: cwdA,
      sandboxPolicy: {
        type: "workspaceWrite",
        writableRoots: [cwdA],
        networkAccess: false,
        excludeTmpdirEnvVar: true,
        excludeSlashTmp: true,
      },
      environments: [environmentA],
    }));
    rpc.stopHandlingServerRequests();
    const serverRequestCountAfterTaskA = rpc.serverRequests.length;

    const firstTurnNotifications = rpc.notifications.slice(firstTurnNotificationStart);
    const firstStartedItems = firstTurnNotifications
      .filter(
        (notification) =>
          notification.method === "item/started" &&
          notification.params.threadId === threadAId,
      )
      .map((notification) => notification.params.item);
    const firstStartedCommand = assertSingleCommandStart(firstStartedItems, "first turn");
    const firstCompletedItems = firstTurnNotifications
      .filter(
        (notification) =>
          notification.method === "item/completed" &&
          notification.params.threadId === threadAId,
      )
      .map((notification) => notification.params.item);
    const firstCompletedCommand = assertSingleCommand(
      firstCompletedItems,
      cwdA,
      "first turn completion",
    );
    assert.equal(firstCompletedCommand.id, firstStartedCommand.id);
    assert.ok(rpc.serverRequests.length >= 1, "first turn did not request approval");
    assert.equal(
      taskACommandApprovals.length,
      1,
      "task A did not request exactly one command approval",
    );
    assert.equal(taskACommandApprovals[0].itemId, firstStartedCommand.id);
    assert.equal(taskACommandApprovals[0].turnId, firstCompletedTurn.id);
    assertProofFile(cwdA, executorA.environment);
    assert.equal(
      existsSync(join(cwdB, proofFileName)),
      false,
      "environment A wrote its proof file into environment B",
    );
    assert.ok(
      firstTurnNotifications.some(
        (notification) =>
          notification.method === "item/completed" &&
          notification.params.threadId === threadAId &&
          notification.params.item?.type === "agentMessage",
      ),
      "final agent message was not streamed",
    );

    const firstRead = await interruptible(
      rpc.request("thread/read", { threadId: threadAId, includeTurns: true }),
    );
    assert.equal(firstRead.thread.turns.length, 1, "thread/read returned extra turns");
    assert.equal(firstRead.thread.turns[0].id, firstCompletedTurn.id);
    const firstHistoryCommand = assertSingleCommand(
      firstRead.thread.turns[0].items,
      cwdA,
      "first turn history",
    );
    assert.equal(firstHistoryCommand.id, firstCompletedCommand.id);

    const resumedA = await interruptible(rpc.request("thread/resume", {
      threadId: threadAId,
      cwd: cwdA,
      approvalPolicy: "never",
      approvalsReviewer: "user",
      sandbox: "workspace-write",
      excludeTurns: false,
    }));
    assert.equal(resumedA.thread.id, threadAId);
    await interruptible(
      verifyCapabilityIsolation(
        rpc,
        threadAId,
        codexHome,
        [cwdA, cwdB],
        noChildLaunchProfile,
      ),
    );

    const taskIsolationNotificationStart = rpc.notificationCount;
    const secondCompletedTurn = await interruptible(completeTurn(rpc, {
      threadId: threadAId,
      input: [
        {
          type: "text",
          text: backgroundEvidencePrompt,
          text_elements: [],
        },
      ],
      model: model.model,
      effort: model.defaultReasoningEffort,
      approvalPolicy: "never",
      approvalsReviewer: "user",
      cwd: cwdA,
      sandboxPolicy: {
        type: "workspaceWrite",
        writableRoots: [cwdA],
        networkAccess: false,
        excludeTmpdirEnvVar: true,
        excludeSlashTmp: true,
      },
      environments: [environmentA],
    }));

    const secondTurnNotificationCount = rpc.notificationCount - taskIsolationNotificationStart;
    assert.ok(secondTurnNotificationCount < 2_000, "second turn overflowed the event buffer");
    const secondTurnNotifications = rpc.notifications.slice(-secondTurnNotificationCount);
    const secondStartedItems = secondTurnNotifications
      .filter(
        (notification) =>
          notification.method === "item/started" &&
          notification.params.threadId === threadAId,
      )
      .map((notification) => notification.params.item);
    const secondStartedCommand = assertSingleCommandStart(
      secondStartedItems,
      "second turn",
    );
    const backgroundOutput = streamedCommandOutput(
      secondTurnNotifications,
      threadAId,
      secondCompletedTurn.id,
      secondStartedCommand.id,
    );
    assert.ok(
      backgroundOutput == null
        ? commandActionRanIn(secondStartedCommand, cwdA, backgroundEvidenceCommand)
        : commandRanIn(
          secondStartedCommand,
          cwdA,
          backgroundEvidenceCommand,
          backgroundOutput,
        ),
      "second turn lacked the exact background command action",
    );
    assert.equal(secondStartedCommand.status, "inProgress");
    const secondTurnCompletedItems = secondTurnNotifications
      .filter(
        (notification) =>
          notification.method === "item/completed" &&
          notification.params.threadId === threadAId,
      )
      .map((notification) => notification.params.item);
    assert.deepEqual(
      secondTurnCompletedItems.filter((item) => !passiveTurnItemTypes.has(item.type)),
      [],
      "yielded command completed before targeted termination",
    );
    assertProofFile(cwdA, executorA.environment);
    assert.ok(
      secondTurnNotifications.some(
        (notification) =>
          notification.method === "item/completed" &&
          notification.params.threadId === threadAId &&
          notification.params.item?.type === "agentMessage" &&
          notification.params.item.text.trim(),
      ),
      "task A final agent message was not streamed",
    );

    const secondRead = await interruptible(
      rpc.request("thread/read", { threadId: threadAId, includeTurns: true }),
    );
    assert.equal(secondRead.thread.turns.length, 2, "thread/read did not return two turns");
    assert.equal(secondRead.thread.id, threadAId);
    assert.equal(secondRead.thread.turns[0].id, firstCompletedTurn.id);
    assert.equal(secondRead.thread.turns[1].id, secondCompletedTurn.id);
    const firstReplayedCommand = assertSingleCommand(
      secondRead.thread.turns[0].items,
      cwdA,
      "first replayed turn",
    );
    assert.equal(firstReplayedCommand.id, firstCompletedCommand.id);
    const inProgressHistoryItems = secondRead.thread.turns[1].items.filter(
      (item) => !passiveTurnItemTypes.has(item.type),
    );
    if (inProgressHistoryItems.length > 0) {
      const secondHistoryCommand = assertSingleCommand(
        secondRead.thread.turns[1].items,
        cwdA,
        "second turn history",
        backgroundEvidenceCommand,
        "inProgress",
        null,
        backgroundOutput,
        true,
      );
      assert.equal(secondHistoryCommand.id, secondStartedCommand.id);
    }
    assert.ok(
      secondRead.thread.turns.at(-1).items.some(
        (item) => item.type === "agentMessage" && item.text.trim(),
      ),
      "resumed thread did not return a final response",
    );

    const beforeCleanup = await interruptible(
      waitForBackgroundTerminal(rpc, threadAId, secondStartedCommand.id),
    );
    assert.equal(beforeCleanup.nextCursor ?? null, null);
    const backgroundTerminal = beforeCleanup.data[0];
    assert.ok(
      typeof backgroundTerminal.processId === "string" && backgroundTerminal.processId.trim(),
      "background terminal omitted processId",
    );
    assert.equal(backgroundTerminal.itemId, secondStartedCommand.id);
    assert.equal(backgroundTerminal.command, backgroundEvidenceCommand);
    assert.equal(realpathSync(backgroundTerminal.cwd), realpathSync(cwdA));

    const taskBCompletedTurn = await interruptible(completeTurn(rpc, {
      threadId: threadBId,
      input: [
        {
          type: "text",
          text: taskBEnvironmentEvidencePrompt,
          text_elements: [],
        },
      ],
      model: model.model,
      effort: model.defaultReasoningEffort,
      approvalPolicy: "never",
      approvalsReviewer: "user",
      cwd: cwdB,
      sandboxPolicy: {
        type: "workspaceWrite",
        writableRoots: [cwdB],
        networkAccess: false,
        excludeTmpdirEnvVar: true,
        excludeSlashTmp: true,
      },
      environments: [environmentB],
    }));
    assert.equal(
      rpc.serverRequests.length,
      serverRequestCountAfterTaskA,
      "task B emitted an approval request under the never policy",
    );
    const taskBRead = await interruptible(
      rpc.request("thread/read", { threadId: threadBId, includeTurns: true }),
    );
    assert.equal(taskBRead.thread.id, threadBId);
    assert.equal(taskBRead.thread.turns.length, 1, "task B returned extra turns");
    assert.equal(taskBRead.thread.turns[0].id, taskBCompletedTurn.id);
    const taskBHistoryCommand = assertSingleCommand(
      taskBRead.thread.turns[0].items,
      cwdB,
      "task B history",
      taskBEnvironmentEvidenceCommand,
    );
    assertProofFile(
      cwdB,
      executorB.environment,
      taskBProofFileName,
      taskBProofFileContents,
    );
    assert.equal(
      existsSync(join(cwdA, taskBProofFileName)),
      false,
      "task B wrote its proof file into task A",
    );
    assert.equal(
      existsSync(join(cwdB, proofFileName)),
      false,
      "task A wrote its proof file into task B",
    );
    assert.equal(
      new Set([
        firstCompletedTurn.id,
        secondCompletedTurn.id,
        taskBCompletedTurn.id,
      ]).size,
      3,
      "task turns shared an id",
    );
    assert.equal(
      new Set([
        firstCompletedCommand.id,
        secondStartedCommand.id,
        taskBHistoryCommand.id,
      ]).size,
      3,
      "task commands shared an item id",
    );
    const taskBBackground = await interruptible(
      rpc.request("thread/backgroundTerminals/list", { threadId: threadBId, limit: 100 }),
    );
    assert.deepEqual(taskBBackground.data, [], "task B inherited task A's terminal");
    assert.equal(taskBBackground.nextCursor ?? null, null);
    const stillRunningInA = await interruptible(
      waitForBackgroundTerminal(rpc, threadAId, secondStartedCommand.id),
    );
    assert.equal(stillRunningInA.data[0].processId, backgroundTerminal.processId);

    const backgroundCompletion = rpc.waitFor(
      "item/completed",
      (notification) =>
        notification.threadId === threadAId &&
        notification.turnId === secondCompletedTurn.id &&
        notification.item?.id === secondStartedCommand.id,
    );
    const termination = await interruptible(rpc.request(
      "thread/backgroundTerminals/terminate",
      { threadId: threadAId, processId: backgroundTerminal.processId },
    ));
    assert.deepEqual(termination, { terminated: true });
    const terminatedItem = await interruptible(backgroundCompletion);
    const terminatedOutput = terminatedItem.item.aggregatedOutput ?? backgroundOutput;
    const terminatedSecondCommand = assertSingleCommand(
      [terminatedItem.item],
      cwdA,
      "targeted background termination",
      backgroundEvidenceCommand,
      "failed",
      undefined,
      terminatedOutput,
      true,
    );
    assert.notEqual(terminatedSecondCommand.exitCode, 0);
    if (terminatedOutput != null) {
      assert.ok(
        terminatedOutput.includes("BYTEQUAY_CP1_BACKGROUND_READY=1"),
        "terminated command omitted its pre-yield output",
      );
    }
    const afterTermination = await interruptible(
      waitForNoBackgroundTerminals(rpc, threadAId),
    );
    assert.equal(afterTermination.nextCursor ?? null, null);
    const taskBAfterTermination = await interruptible(
      waitForNoBackgroundTerminals(rpc, threadBId),
    );
    assert.equal(taskBAfterTermination.nextCursor ?? null, null);
    await interruptible(rpc.request(
      "thread/backgroundTerminals/clean",
      { threadId: threadAId },
    ));
    const afterCleanup = await interruptible(rpc.request("thread/backgroundTerminals/list", {
      threadId: threadAId,
      limit: 100,
    }));
    assert.deepEqual(afterCleanup.data, []);
    const terminatedRead = await interruptible(
      rpc.request("thread/read", { threadId: threadAId, includeTurns: true }),
    );
    assert.equal(terminatedRead.thread.id, threadAId);
    assert.deepEqual(
      terminatedRead.thread.turns.map((turn) => turn.id),
      [firstCompletedTurn.id, secondCompletedTurn.id],
      "task A history changed while task B ran",
    );
    const terminatedHistoryCommand = assertSingleCommand(
      terminatedRead.thread.turns[1].items,
      cwdA,
      "terminated second turn history",
      backgroundEvidenceCommand,
      "failed",
      undefined,
      terminatedOutput,
      true,
    );
    assert.equal(terminatedHistoryCommand.id, terminatedSecondCommand.id);
    const settledTaskBRead = await interruptible(
      rpc.request("thread/read", { threadId: threadBId, includeTurns: true }),
    );
    assert.equal(settledTaskBRead.thread.id, threadBId);
    assert.deepEqual(
      settledTaskBRead.thread.turns.map((turn) => turn.id),
      [taskBCompletedTurn.id],
      "terminating task A changed task B history",
    );
    const taskIsolationNotificationCount =
      rpc.notificationCount - taskIsolationNotificationStart;
    assert.ok(
      taskIsolationNotificationCount < 2_000,
      "task isolation window overflowed the notification buffer",
    );
    const taskIsolationNotifications = rpc.notifications.slice(-taskIsolationNotificationCount);
    assertTaskNotificationIsolation(
      taskIsolationNotifications,
      new Map([
        [threadAId, terminatedRead.thread],
        [threadBId, settledTaskBRead.thread],
      ]),
    );
    const taskBStartedCommand = assertSingleCommandStart(
      taskIsolationNotifications
        .filter(
          (notification) =>
            notification.method === "item/started" &&
            notification.params.threadId === threadBId,
        )
        .map((notification) => notification.params.item),
      "task B event stream",
    );
    const taskBCompletedCommand = assertSingleCommand(
      taskIsolationNotifications
        .filter(
          (notification) =>
            notification.method === "item/completed" &&
            notification.params.threadId === threadBId,
        )
        .map((notification) => notification.params.item),
      cwdB,
      "task B event stream",
      taskBEnvironmentEvidenceCommand,
    );
    assert.equal(taskBStartedCommand.id, taskBHistoryCommand.id);
    assert.equal(taskBCompletedCommand.id, taskBHistoryCommand.id);
    assert.ok(
      taskIsolationNotifications.some(
        (notification) =>
          notification.method === "item/completed" &&
          notification.params.threadId === threadBId &&
          notification.params.item?.type === "agentMessage" &&
          notification.params.item.text.trim(),
      ),
      "task B final agent message was not streamed",
    );
    const notificationIndex = (method, predicate) => taskIsolationNotifications.findIndex(
      (notification) => notification.method === method && predicate(notification.params),
    );
    const taskACommandStartedIndex = notificationIndex(
      "item/started",
      (params) => params.threadId === threadAId && params.item?.id === secondStartedCommand.id,
    );
    const taskBTurnStartedIndex = notificationIndex(
      "turn/started",
      (params) => params.threadId === threadBId && params.turn?.id === taskBCompletedTurn.id,
    );
    const taskBTurnCompletedIndex = notificationIndex(
      "turn/completed",
      (params) => params.threadId === threadBId && params.turn?.id === taskBCompletedTurn.id,
    );
    const taskACommandCompletedIndex = notificationIndex(
      "item/completed",
      (params) => params.threadId === threadAId && params.item?.id === secondStartedCommand.id,
    );
    assert.ok(
      taskACommandStartedIndex >= 0 &&
        taskACommandStartedIndex < taskBTurnStartedIndex &&
        taskBTurnStartedIndex < taskBTurnCompletedIndex &&
        taskBTurnCompletedIndex < taskACommandCompletedIndex,
      "task B did not complete while task A's background command remained active",
    );
    assert.ok(
      !rpc.notifications.some(
        (notification) =>
          notification.method === "mcpServer/startupStatus/updated" &&
          (notification.params.threadId === threadAId ||
            notification.params.threadId === threadBId),
      ),
      "an isolated task started an inherited MCP server",
    );

    assertRuntimeHealthy(rpc, [
      ["environment A", execA],
      ["environment B", execB],
    ]);
    const oldAppServerPid = appServer.pid;
    const oldExecAPid = execA.child.pid;
    const oldExecBPid = execB.child.pid;
    await interruptible(stopRegisteredChild(appServer));
    await interruptible(Promise.all([
      stopRegisteredChild(execA.child),
      stopRegisteredChild(execB.child),
    ]));
    assert.equal(processGroupExists(oldAppServerPid), false);
    assert.equal(processGroupExists(oldExecAPid), false);
    assert.equal(processGroupExists(oldExecBPid), false);
    removeGeneratedLiveConfig(codexHome);

    const replacementExecutorA = executorProcessEnvironment(root, "exec-a-restarted", cwdA);
    assert.equal(existsSync(join(replacementExecutorA.codexHome, "auth.json")), false);
    execA = await interruptible(
      startExecServer(cwdA, replacementExecutorA.environment, registerChild),
    );
    ({ child: appServer, rpc } = await interruptible(
      startAppServer(
        root,
        appServerEnvironment,
        registerChild,
        noChildLaunchProfile,
      ),
    ));
    await interruptible(verifyChatgptSubscriptionAccount(rpc));
    await interruptible(
      verifyPreThreadIsolation(rpc, codexHome, [cwdA, cwdB], noChildLaunchProfile),
    );
    await interruptible(rpc.request("environment/add", {
      environmentId: environmentA.environmentId,
      execServerUrl: execA.url,
      connectTimeoutMs: 10_000,
    }));
    const replacementInfoA = await interruptible(rpc.request("environment/info", {
      environmentId: environmentA.environmentId,
    }));
    assert.equal(canonicalPath(replacementInfoA.cwd), realpathSync(cwdA));
    assert.ok(replacementInfoA.shell.name && replacementInfoA.shell.path);

    const restartNotificationStart = rpc.notifications.length;
    const recoveredA = await interruptible(rpc.request("thread/resume", {
      threadId: threadAId,
      cwd: cwdA,
      approvalPolicy: "never",
      approvalsReviewer: "user",
      sandbox: "workspace-write",
      excludeTurns: false,
    }));
    assert.equal(recoveredA.thread.id, threadAId);
    await interruptible(
      verifyCapabilityIsolation(
        rpc,
        threadAId,
        codexHome,
        [cwdA, cwdB],
        noChildLaunchProfile,
      ),
    );
    const [recoveredRead, recoveredTaskBRead] = await interruptible(Promise.all([
      rpc.request("thread/read", { threadId: threadAId, includeTurns: true }),
      rpc.request("thread/read", { threadId: threadBId, includeTurns: true }),
    ]));
    assert.equal(recoveredRead.thread.id, threadAId);
    assert.deepEqual(
      recoveredRead.thread.turns.map((turn) => turn.id),
      [firstCompletedTurn.id, secondCompletedTurn.id],
    );
    assert.equal(recoveredTaskBRead.thread.id, threadBId);
    assert.deepEqual(
      recoveredTaskBRead.thread.turns.map((turn) => turn.id),
      [taskBCompletedTurn.id],
    );
    const recoveredFirstCommand = assertSingleCommand(
      recoveredRead.thread.turns[0].items,
      cwdA,
      "recovered first turn",
    );
    const recoveredSecondCommand = assertSingleCommand(
      recoveredRead.thread.turns[1].items,
      cwdA,
      "recovered second turn",
      backgroundEvidenceCommand,
      "failed",
      undefined,
      terminatedOutput,
      true,
    );
    const recoveredTaskBCommand = assertSingleCommand(
      recoveredTaskBRead.thread.turns[0].items,
      cwdB,
      "recovered task B turn",
      taskBEnvironmentEvidenceCommand,
    );
    assert.equal(recoveredFirstCommand.id, firstCompletedCommand.id);
    assert.equal(recoveredSecondCommand.id, terminatedSecondCommand.id);
    assert.equal(recoveredTaskBCommand.id, taskBHistoryCommand.id);
    const recoveredBackground = await interruptible(
      rpc.request("thread/backgroundTerminals/list", { threadId: threadAId, limit: 100 }),
    );
    assert.deepEqual(recoveredBackground.data, []);
    assertProofFile(cwdA, replacementExecutorA.environment);
    assertProofFile(
      cwdB,
      executorB.environment,
      taskBProofFileName,
      taskBProofFileContents,
    );

    await interruptible(delay(100));
    const settledRecoveredRead = await interruptible(
      rpc.request("thread/read", { threadId: threadAId, includeTurns: true }),
    );
    assert.deepEqual(
      settledRecoveredRead.thread.turns.map((turn) => turn.id),
      [firstCompletedTurn.id, secondCompletedTurn.id],
      "App Server restart changed durable history without a new turn",
    );
    assert.ok(
      !rpc.notifications.slice(restartNotificationStart).some(
        (notification) =>
          notification.method === "turn/started" ||
          notification.method === "item/started",
      ),
      "App Server restart started provider work without a new turn",
    );
    assertRuntimeHealthy(rpc, [
      ["replacement environment A", execA],
    ]);
    assert.equal(existsSync(join(executorA.codexHome, "auth.json")), false);
    assert.equal(existsSync(join(executorB.codexHome, "auth.json")), false);
    assert.equal(existsSync(join(replacementExecutorA.codexHome, "auth.json")), false);

    console.log(`PASS live initialize and model/list (${model.model})`);
    console.log("PASS ChatGPT subscription auth and credential-free executor environments");
    console.log("PASS empty MCP/hooks and disabled optional capabilities");
    console.log("PASS two root tasks route commands, events, and files without cross-talk");
    console.log("PASS command events, thread/read, resume, and task A's two turns");
    console.log("PASS yielded background-terminal list, targeted termination, and cleanup");
    console.log("PASS App Server restart and disk-backed thread recovery without resend");
  } catch (error) {
    failure = error;
  } finally {
    const cleanupErrors = [];
    try {
      if (appServer?.stdin.writable) appServer.stdin.end();
    } catch (error) {
      cleanupErrors.push(error);
    }
    const processResults = await Promise.allSettled(
      [...children].map((child) => stopProcess(child)),
    );
    let everyProcessGroupStopped = true;
    for (const result of processResults) {
      if (result.status === "rejected") {
        everyProcessGroupStopped = false;
        cleanupErrors.push(result.reason);
      }
    }
    if (everyProcessGroupStopped) {
      try {
        if (codexHome && liveProfileRuntimeOwned) cleanLiveProfileRuntime(codexHome);
      } catch (error) {
        cleanupErrors.push(error);
      }
      try {
        releaseLiveProfileLock(liveProfileLock);
      } catch (error) {
        cleanupErrors.push(error);
      }
      try {
        rmSync(root, { recursive: true, force: true });
      } catch (error) {
        cleanupErrors.push(error);
      }
    } else {
      cleanupErrors.push(
        new Error(`retained ${root} because a process group did not stop`),
      );
    }
    if (interruptedSignal) {
      cleanupErrors.push(new Error(`received ${interruptedSignal}`), ...signalErrors);
    }
    if (cleanupErrors.length > 0) {
      failure = new AggregateError(
        failure ? [failure, ...cleanupErrors] : cleanupErrors,
        interruptedSignal
          ? "live compatibility check interrupted"
          : "live compatibility check cleanup failed",
      );
    }
    process.off("SIGINT", handleSigint);
    process.off("SIGTERM", handleSigterm);
  }
  if (failure) throw failure;
}

async function liveChildrenCompatibilityCheck() {
  const root = mkdtempSync(join(tmpdir(), "bytequay-codex-live-children-"));
  const cwd = join(root, "environment");

  let appServer;
  let codexHome;
  let execServer;
  let liveProfileLock;
  let liveProfileRuntimeOwned = false;
  let rpc;
  let failure;
  let interruptedSignal;
  let rejectInterruption;
  const signalErrors = [];
  const children = new Set();
  const interruption = new Promise((_, reject) => {
    rejectInterruption = reject;
  });
  void interruption.catch(() => {});
  const interruptible = (promise) => Promise.race([promise, interruption]);
  const registerChild = (child) => {
    children.add(child);
    if (interruptedSignal) {
      try {
        signalProcessGroup(child.pid, "SIGKILL");
      } catch (error) {
        signalErrors.push(error);
      }
    }
  };
  const stopRegisteredChild = async (child) => {
    await stopProcess(child);
    children.delete(child);
  };
  const handleSignal = (signal) => {
    const firstSignal = !interruptedSignal;
    interruptedSignal ??= signal;
    process.exitCode = signal === "SIGINT" ? 130 : 143;
    if (firstSignal) rejectInterruption(new Error(`received ${signal}`));
    for (const child of children) {
      try {
        signalProcessGroup(child.pid, firstSignal ? "SIGTERM" : "SIGKILL");
      } catch (error) {
        signalErrors.push(error);
      }
    }
  };
  const handleSigint = () => handleSignal("SIGINT");
  const handleSigterm = () => handleSignal("SIGTERM");
  process.on("SIGINT", handleSigint);
  process.on("SIGTERM", handleSigterm);
  try {
    codexHome = requiredLiveCodexHome(root);
    liveProfileLock = acquireLiveProfileLock(codexHome);
    assertLiveProfileReady(codexHome);
    liveProfileRuntimeOwned = true;
    mkdirSync(cwd);
    const appServerEnvironment = appServerProcessEnvironment(root, codexHome);
    const executor = executorProcessEnvironment(root, "exec", cwd);
    initializeDisposableGitWorktree(cwd, executor.environment);
    assert.equal(Object.hasOwn(appServerEnvironment, "CODEX_API_KEY"), false);
    assert.equal(Object.hasOwn(appServerEnvironment, "OPENAI_API_KEY"), false);
    assert.equal(existsSync(join(executor.codexHome, "auth.json")), false);

    ({ child: appServer, rpc } = await interruptible(
      startAppServer(
        root,
        appServerEnvironment,
        registerChild,
        noChildLaunchProfile,
      ),
    ));
    await interruptible(verifyChatgptSubscriptionAccount(rpc));
    await interruptible(
      verifyPreThreadIsolation(rpc, codexHome, [cwd], noChildLaunchProfile),
    );
    const models = await interruptible(listAllModels(rpc));
    const childCapableModels = models.filter(
      (candidate) =>
        !candidate.hidden &&
        candidate.inputModalities?.includes("text") &&
        ["v1", "v2"].includes(candidate.multiAgentVersion),
    );
    const model = childCapableModels.find((candidate) => candidate.isDefault) ??
      childCapableModels[0];
    assert.ok(model, "model/list returned no visible text model with V1 or V2 multi-agent");
    assert.ok(model.model, "selected child-capable model omitted its provider model ID");
    assert.ok(model.supportedReasoningEfforts.length > 0);
    assert.ok(
      model.supportedReasoningEfforts.some(
        (option) => option.reasoningEffort === model.defaultReasoningEffort,
      ),
      "child-capable model default effort is not advertised",
    );
    const launchProfile = childLaunchProfileForVersion(model.multiAgentVersion);
    await interruptible(stopRegisteredChild(appServer));
    appServer = undefined;
    rpc = undefined;
    removeGeneratedLiveConfig(codexHome);

    execServer = await interruptible(
      startExecServer(cwd, executor.environment, registerChild),
    );
    ({ child: appServer, rpc } = await interruptible(
      startAppServer(root, appServerEnvironment, registerChild, launchProfile),
    ));
    await interruptible(verifyChatgptSubscriptionAccount(rpc));
    await interruptible(
      verifyPreThreadIsolation(rpc, codexHome, [cwd], launchProfile),
    );
    const selectedModels = await interruptible(listAllModels(rpc));
    const selectedModel = selectedModels.find(
      (candidate) => candidate.id === model.id && candidate.model === model.model,
    );
    assert.ok(selectedModel, "selected child-capable model disappeared after restart");
    assert.equal(selectedModel.hidden, false, "selected model became hidden after restart");
    assert.ok(
      selectedModel.inputModalities?.includes("text"),
      "selected model lost text input after restart",
    );
    assert.equal(
      selectedModel.multiAgentVersion,
      model.multiAgentVersion,
      "selected model changed multi-agent version after restart",
    );
    assert.ok(selectedModel.supportedReasoningEfforts.length > 0);
    assert.ok(
      selectedModel.supportedReasoningEfforts.some(
        (option) => option.reasoningEffort === selectedModel.defaultReasoningEffort,
      ),
      "selected model default effort changed to an unadvertised value after restart",
    );
    const environment = {
      environmentId: "bytequay-cp1-children",
      cwd,
      runtimeWorkspaceRoots: [cwd],
    };
    await interruptible(rpc.request("environment/add", {
      environmentId: environment.environmentId,
      execServerUrl: execServer.url,
      connectTimeoutMs: 10_000,
    }));
    const environmentInfo = await interruptible(rpc.request("environment/info", {
      environmentId: environment.environmentId,
    }));
    assert.equal(canonicalPath(environmentInfo.cwd), realpathSync(cwd));
    assert.ok(environmentInfo.shell.name && environmentInfo.shell.path);

    const started = await interruptible(rpc.request("thread/start", {
      cwd,
      model: selectedModel.model,
      approvalPolicy: "never",
      approvalsReviewer: "user",
      sandbox: "workspace-write",
      ephemeral: false,
      serviceName: "ByteQuay CP1 recursive-child fixture",
      dynamicTools: [],
      selectedCapabilityRoots: [],
      environments: [environment],
    }));
    const rootThreadId = started.thread.id;
    await interruptible(
      verifyCapabilityIsolation(rpc, rootThreadId, codexHome, [cwd], launchProfile),
    );

    const rootTurn = await interruptible(completeTurn(rpc, {
      threadId: rootThreadId,
      input: [{
        type: "text",
        text: childEvidencePrompt,
        text_elements: [],
      }],
      model: selectedModel.model,
      effort: selectedModel.defaultReasoningEffort,
      approvalPolicy: "never",
      approvalsReviewer: "user",
      cwd,
      sandboxPolicy: {
        type: "workspaceWrite",
        writableRoots: [cwd],
        networkAccess: false,
        excludeTmpdirEnvVar: true,
        excludeSlashTmp: true,
      },
      environments: [environment],
    }, 300_000));
    const histories = await interruptible(
      waitForRecursiveChildHistory(
        rpc,
        rootThreadId,
        selectedModel.multiAgentVersion,
      ),
    );
    assert.ok(
      histories.get(rootThreadId).turns.some((turn) => turn.id === rootTurn.id),
      "root history omitted the completed root turn",
    );
    assertChildTree(histories, rootThreadId, selectedModel.multiAgentVersion);
    await interruptible(assertNoChildBackgroundTerminals(rpc, [...histories.keys()]));
    assert.equal(rpc.serverRequests.length, 0, "child probe received an approval request");
    assert.equal(
      run("git", ["status", "--short", "--untracked-files=all"], {
        cwd,
        env: executor.environment,
      }),
      "",
      "child probe changed the disposable Git worktree",
    );
    assertRuntimeHealthy(rpc, [["child environment", execServer]]);
    assert.equal(existsSync(join(executor.codexHome, "auth.json")), false);

    console.log(
      `PASS live recursive child history ` +
        `(${selectedModel.model}, ${selectedModel.multiAgentVersion})`,
    );
    console.log("PASS exactly one root child and one grandchild with completed markers");
    console.log("PASS child profile isolation, clean worktree, and empty background terminals");
  } catch (error) {
    failure = error;
  } finally {
    const cleanupErrors = [];
    try {
      if (appServer?.stdin.writable) appServer.stdin.end();
    } catch (error) {
      cleanupErrors.push(error);
    }
    const processResults = await Promise.allSettled(
      [...children].map((child) => stopProcess(child)),
    );
    let everyProcessGroupStopped = true;
    for (const result of processResults) {
      if (result.status === "rejected") {
        everyProcessGroupStopped = false;
        cleanupErrors.push(result.reason);
      }
    }
    if (everyProcessGroupStopped) {
      try {
        if (codexHome && liveProfileRuntimeOwned) cleanLiveProfileRuntime(codexHome);
      } catch (error) {
        cleanupErrors.push(error);
      }
      try {
        releaseLiveProfileLock(liveProfileLock);
      } catch (error) {
        cleanupErrors.push(error);
      }
      try {
        rmSync(root, { recursive: true, force: true });
      } catch (error) {
        cleanupErrors.push(error);
      }
    } else {
      cleanupErrors.push(
        new Error(`retained ${root} because a process group did not stop`),
      );
    }
    if (interruptedSignal) {
      cleanupErrors.push(new Error(`received ${interruptedSignal}`), ...signalErrors);
    }
    if (cleanupErrors.length > 0) {
      failure = new AggregateError(
        failure ? [failure, ...cleanupErrors] : cleanupErrors,
        interruptedSignal
          ? "live child compatibility check interrupted"
          : "live child compatibility check cleanup failed",
      );
    }
    process.off("SIGINT", handleSigint);
    process.off("SIGTERM", handleSigterm);
  }
  if (failure) throw failure;
}

async function main() {
  const flags = new Set(process.argv.slice(2));
  for (const flag of flags) {
    assert.ok(
      flag === "--live" || flag === "--live-children" || flag === "--update-bindings",
      `unknown option ${flag}`,
    );
  }
  assert.ok(
    !(flags.has("--live") && flags.has("--live-children")),
    "--live and --live-children are separate provider-cost gates",
  );
  staticCompatibilityCheck(flags.has("--update-bindings"));
  checkSubscriptionAccountHelper();
  checkLiveProfileLockHelper();
  checkLiveProfileReadinessHelper();
  checkChildHistoryHelpers();
  checkTaskNotificationIsolation();
  for (const launchProfile of appServerLaunchProfiles) {
    await checkAppServerConfigSourceIsolation(launchProfile);
  }
  if (flags.has("--live")) await liveCompatibilityCheck();
  if (flags.has("--live-children")) await liveChildrenCompatibilityCheck();
}

main().catch((error) => {
  console.error(`FAIL ${error.stack ?? error}`);
  process.exitCode ||= 1;
});
