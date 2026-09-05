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

type JsonObject = Record<string, unknown>;

const EVENT_TYPES = [
  'user_message',
  'assistant_message',
  'plan_updated',
  'command',
  'file_change',
  'tool',
  'approval_requested',
  'approval_resolved',
  'usage',
  'lifecycle',
  'error',
] as const;
const DELIVERY_KINDS = ['TURN', 'STEER', 'QUEUED'] as const;
const MESSAGE_KINDS = ['ANSWER', 'COMMENTARY', 'REASONING_SUMMARY', 'PLAN_TEXT', 'PROMPT'] as const;
const MESSAGE_PHASES = ['PARTIAL', 'FINAL'] as const;
const ACTIVITY_STATES = ['STARTED', 'PROGRESS', 'COMPLETED', 'FAILED'] as const;
const CHANGE_KINDS = ['ADDED', 'MODIFIED', 'DELETED', 'RENAMED'] as const;
const PLAN_ITEM_STATES = ['PENDING', 'IN_PROGRESS', 'COMPLETED'] as const;
const APPROVAL_KINDS = ['COMMAND', 'FILE_CHANGE', 'OTHER'] as const;
const APPROVAL_OPTIONS = ['ALLOW_ONCE', 'ALLOW_FOR_TASK', 'DENY'] as const;
const APPROVAL_OUTCOMES = ['ALLOWED', 'DENIED', 'EXPIRED', 'CANCELLED'] as const;
const USAGE_SEMANTICS = ['CUMULATIVE', 'DELTA', 'FINAL_FOR_INVOCATION'] as const;
const COST_PROVENANCE = ['PROVIDER_REPORTED', 'CLI_ESTIMATE', 'UNAVAILABLE'] as const;
const COMMAND_ACTION_KINDS = ['READ', 'LIST_FILES', 'SEARCH', 'OTHER'] as const;
const LIFECYCLE_PHASES = [
  'SESSION_OPENED',
  'TURN_STARTED',
  'TITLE_UPDATED',
  'QUEUE_CHANGED',
  'QUEUED_INPUT_STARTED',
  'QUEUED_INPUT_PAUSED',
  'TURN_COMPLETED',
  'TASK_COMPLETED',
  'TASK_FAILED',
  'RETRYING',
  'RATE_LIMITED',
  'INTERRUPTED',
  'AGENT_STARTED',
  'AGENT_COMPLETED',
  'AGENT_FAILED',
  'BACKFILL_STARTED',
  'BACKFILL_COMPLETED',
] as const;

type AgentEventSource = {
  providerThreadId: string | null;
  parentProviderThreadId: string | null;
  logicalAgentPath: string | null;
  providerTurnId: string | null;
  providerItemId: string | null;
  providerEventId: string | null;
  providerOccurredAt: string | null;
};

type AgentEvent<Tag extends string, Payload> = {
  schemaVersion: 2;
  eventId: string;
  sequence: number;
  taskId: string;
  turnId: string | null;
  source: AgentEventSource;
  occurredAt: string;
  type: Tag;
  payload: Payload;
};

export type AgentEventEnvelope =
  | AgentEvent<'user_message', {
    text: string;
    attachments: Array<{ id: string; name: string; mediaType: string }>;
    deliveryKind: typeof DELIVERY_KINDS[number];
  }>
  | AgentEvent<'assistant_message', {
    messageKind: typeof MESSAGE_KINDS[number];
    phase: typeof MESSAGE_PHASES[number];
    text: string;
  }>
  | AgentEvent<'plan_updated', {
    items: Array<{ text: string; state: typeof PLAN_ITEM_STATES[number] }>;
  }>
  | AgentEvent<'command', {
    state: typeof ACTIVITY_STATES[number];
    argv: string[];
    output: string | null;
    exitCode: number | null;
    truncated: boolean;
    /** Worktree-relative working directory; null when unknown or outside the worktree. */
    cwd: string | null;
    durationMs: number | null;
    /** What the provider parsed the command as doing; empty on events stored before this existed. */
    actions: Array<{ kind: typeof COMMAND_ACTION_KINDS[number]; path: string | null; query: string | null }>;
  }>
  | AgentEvent<'file_change', {
    state: typeof ACTIVITY_STATES[number];
    relativePath: string;
    change: typeof CHANGE_KINDS[number];
    diff: string | null;
  }>
  | AgentEvent<'tool', {
    name: string;
    state: typeof ACTIVITY_STATES[number];
    summary: string;
    durationMs: number | null;
  }>
  | AgentEvent<'approval_requested', {
    approvalId: string;
    approvalKind: typeof APPROVAL_KINDS[number];
    title: string;
    detail: string;
    /** What the provider asked to be allowed. Empty on events stored before this field existed. */
    scope: string[];
    actionable: boolean;
    availableDecisions: Array<typeof APPROVAL_OPTIONS[number]>;
  }>
  | AgentEvent<'approval_resolved', {
    approvalId: string;
    outcome: typeof APPROVAL_OUTCOMES[number];
    /** Whether the allow covers the task's later commands too. False on events stored before it existed. */
    forTask: boolean;
  }>
  | AgentEvent<'usage', {
    semantics: typeof USAGE_SEMANTICS[number];
    inputTokens: number;
    cachedInputTokens: number;
    outputTokens: number;
    cost: number | null;
    costProvenance: typeof COST_PROVENANCE[number];
  }>
  | AgentEvent<'lifecycle', {
    phase: typeof LIFECYCLE_PHASES[number];
    message: string;
  }>
  | AgentEvent<'error', {
    code: string;
    message: string;
    retryable: boolean;
  }>;

/** Parses one ordered event page. Provider-native payloads never cross this boundary. */
export function parseAgentEventSequence(value: unknown, afterSequence = 0): AgentEventEnvelope[] {
  if (!Number.isSafeInteger(afterSequence) || afterSequence < 0) {
    throw new Error('afterSequence must be a non-negative integer');
  }
  const events = arrayValue(value, 'events');
  const eventIds = new Set<string>();
  let taskId: string | undefined;
  let expectedSequence = afterSequence + 1;
  return events.map((candidate, index) => {
    const event = objectValue(candidate, `event[${index}]`);
    literalValue(event.schemaVersion, 2, `event[${index}].schemaVersion`);
    const eventId = stringValue(event.eventId, `event[${index}].eventId`);
    if (eventIds.has(eventId)) {
      throw new Error(`Duplicate eventId ${eventId}`);
    }
    eventIds.add(eventId);
    const sequence = integerValue(event.sequence, `event[${index}].sequence`);
    if (sequence < expectedSequence) {
      throw new Error(`Duplicate or out-of-order event sequence ${sequence}`);
    }
    if (sequence > expectedSequence) {
      throw new Error(`Event sequence gap: expected ${expectedSequence}, received ${sequence}`);
    }
    const currentTaskId = stringValue(event.taskId, `event[${index}].taskId`);
    if (taskId !== undefined && currentTaskId !== taskId) {
      throw new Error(`Mixed taskId: expected ${taskId}, received ${currentTaskId}`);
    }
    taskId = currentTaskId;
    nullableString(event.turnId, `event[${index}].turnId`);
    validateSource(event.source, index);
    stringValue(event.occurredAt, `event[${index}].occurredAt`);
    const type = enumValue(event.type, EVENT_TYPES, `event[${index}].type`);
    validatePayload(type, event.payload, index);
    expectedSequence++;
    return candidate as AgentEventEnvelope;
  });
}

function validateSource(value: unknown, index: number): void {
  const source = objectValue(value, `event[${index}].source`);
  const prefix = `event[${index}].source`;
  nullableString(source.providerThreadId, `${prefix}.providerThreadId`);
  nullableString(source.parentProviderThreadId, `${prefix}.parentProviderThreadId`);
  nullableString(source.logicalAgentPath, `${prefix}.logicalAgentPath`);
  nullableString(source.providerTurnId, `${prefix}.providerTurnId`);
  nullableString(source.providerItemId, `${prefix}.providerItemId`);
  nullableString(source.providerEventId, `${prefix}.providerEventId`);
  nullableString(source.providerOccurredAt, `${prefix}.providerOccurredAt`);
}

function validatePayload(type: typeof EVENT_TYPES[number], value: unknown, index: number): void {
  const payload = objectValue(value, `event[${index}].payload`);
  switch (type) {
    case 'user_message':
      stringValue(payload.text, 'userMessage.text');
      arrayValue(payload.attachments, 'userMessage.attachments').forEach((attachment, attachmentIndex) => {
        const item = objectValue(attachment, `userMessage.attachments[${attachmentIndex}]`);
        stringValue(item.id, 'attachment.id');
        stringValue(item.name, 'attachment.name');
        stringValue(item.mediaType, 'attachment.mediaType');
      });
      enumValue(payload.deliveryKind, DELIVERY_KINDS, 'userMessage.deliveryKind');
      break;
    case 'assistant_message':
      enumValue(payload.messageKind, MESSAGE_KINDS, 'assistantMessage.messageKind');
      enumValue(payload.phase, MESSAGE_PHASES, 'assistantMessage.phase');
      stringValue(payload.text, 'assistantMessage.text');
      break;
    case 'plan_updated':
      arrayValue(payload.items, 'planUpdated.items').forEach((item, itemIndex) => {
        const planItem = objectValue(item, `planUpdated.items[${itemIndex}]`);
        stringValue(planItem.text, 'planItem.text');
        enumValue(planItem.state, PLAN_ITEM_STATES, 'planItem.state');
      });
      break;
    case 'command':
      enumValue(payload.state, ACTIVITY_STATES, 'command.state');
      arrayValue(payload.argv, 'command.argv').forEach((argument, argumentIndex) =>
        stringValue(argument, `command.argv[${argumentIndex}]`));
      nullableString(payload.output, 'command.output');
      nullableInteger(payload.exitCode, 'command.exitCode');
      booleanValue(payload.truncated, 'command.truncated');
      // Absent on events stored before these facts were carried; absent reads as unknown or none.
      if (payload.cwd === undefined) payload.cwd = null;
      if (payload.durationMs === undefined) payload.durationMs = null;
      if (payload.actions === undefined) payload.actions = [];
      nullableString(payload.cwd, 'command.cwd');
      nullableInteger(payload.durationMs, 'command.durationMs');
      arrayValue(payload.actions, 'command.actions').forEach((action, actionIndex) => {
        const item = objectValue(action, `command.actions[${actionIndex}]`);
        enumValue(item.kind, COMMAND_ACTION_KINDS, 'commandAction.kind');
        nullableString(item.path, 'commandAction.path');
        nullableString(item.query, 'commandAction.query');
      });
      break;
    case 'file_change':
      enumValue(payload.state, ACTIVITY_STATES, 'fileChange.state');
      stringValue(payload.relativePath, 'fileChange.relativePath');
      enumValue(payload.change, CHANGE_KINDS, 'fileChange.change');
      nullableString(payload.diff, 'fileChange.diff');
      break;
    case 'tool':
      stringValue(payload.name, 'tool.name');
      enumValue(payload.state, ACTIVITY_STATES, 'tool.state');
      stringValue(payload.summary, 'tool.summary');
      if (payload.durationMs === undefined) payload.durationMs = null;
      nullableInteger(payload.durationMs, 'tool.durationMs');
      break;
    case 'approval_requested': {
      stringValue(payload.approvalId, 'approvalRequested.approvalId');
      enumValue(payload.approvalKind, APPROVAL_KINDS, 'approvalRequested.approvalKind');
      stringValue(payload.title, 'approvalRequested.title');
      stringValue(payload.detail, 'approvalRequested.detail');
      // Absent on events stored before scope was carried; an empty scope reads the same.
      if (payload.scope === undefined) payload.scope = [];
      arrayValue(payload.scope, 'approvalRequested.scope')
        .forEach((entry, scopeIndex) => stringValue(entry, `approvalRequested.scope[${scopeIndex}]`));
      const actionable = booleanValue(payload.actionable, 'approvalRequested.actionable');
      const decisions = arrayValue(payload.availableDecisions, 'approvalRequested.availableDecisions');
      decisions.forEach((decision, decisionIndex) =>
        enumValue(decision, APPROVAL_OPTIONS, `availableDecisions[${decisionIndex}]`));
      if (actionable !== (decisions.length > 0)) {
        throw new Error('approvalRequested actionable state and availableDecisions disagree');
      }
      break;
    }
    case 'approval_resolved':
      stringValue(payload.approvalId, 'approvalResolved.approvalId');
      enumValue(payload.outcome, APPROVAL_OUTCOMES, 'approvalResolved.outcome');
      if (payload.forTask === undefined) payload.forTask = false;
      booleanValue(payload.forTask, 'approvalResolved.forTask');
      break;
    case 'usage':
      enumValue(payload.semantics, USAGE_SEMANTICS, 'usage.semantics');
      integerValue(payload.inputTokens, 'usage.inputTokens');
      integerValue(payload.cachedInputTokens, 'usage.cachedInputTokens');
      integerValue(payload.outputTokens, 'usage.outputTokens');
      nullableNumber(payload.cost, 'usage.cost');
      enumValue(payload.costProvenance, COST_PROVENANCE, 'usage.costProvenance');
      break;
    case 'lifecycle':
      enumValue(payload.phase, LIFECYCLE_PHASES, 'lifecycle.phase');
      stringValue(payload.message, 'lifecycle.message');
      break;
    case 'error':
      stringValue(payload.code, 'error.code');
      stringValue(payload.message, 'error.message');
      booleanValue(payload.retryable, 'error.retryable');
      break;
  }
}

function objectValue(value: unknown, field: string): JsonObject {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(`${field} must be an object`);
  }
  return value as JsonObject;
}

function arrayValue(value: unknown, field: string): unknown[] {
  if (!Array.isArray(value)) throw new Error(`${field} must be an array`);
  return value;
}

function stringValue(value: unknown, field: string): string {
  if (typeof value !== 'string') throw new Error(`${field} must be a string`);
  return value;
}

function nullableString(value: unknown, field: string): string | null {
  return value === null ? null : stringValue(value, field);
}

function booleanValue(value: unknown, field: string): boolean {
  if (typeof value !== 'boolean') throw new Error(`${field} must be a boolean`);
  return value;
}

function integerValue(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) throw new Error(`${field} must be an integer`);
  return value;
}

function nullableInteger(value: unknown, field: string): number | null {
  return value === null ? null : integerValue(value, field);
}

function nullableNumber(value: unknown, field: string): number | null {
  if (value === null) return null;
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error(`${field} must be a number or null`);
  }
  return value;
}

function literalValue<T extends string | number>(value: unknown, expected: T, field: string): T {
  if (value !== expected) throw new Error(`${field} must be ${expected}`);
  return expected;
}

function enumValue<const T extends readonly string[]>(value: unknown, values: T, field: string): T[number] {
  const text = stringValue(value, field);
  if (!(values as readonly string[]).includes(text)) throw new Error(`${field} has unsupported value: ${text}`);
  return text as T[number];
}
