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

import type {
  AgentApprovalDto,
  AgentCompletionDto,
  AgentMetricsDto,
  AgentTaskDefaultsDto,
  AgentTaskDto,
  AgentTaskSummaryDto,
  AgentTurnDto,
  AgentTurnSettingsDto,
} from '../types';

/**
 * Narrows the backend's task, turn, approval and completion responses.
 *
 * Events already cross the bridge field by field; these did not, so a worktree path or a provider
 * detail added to one of them later would reach the renderer on its own. Reading only the fields
 * the contract names means a new one has to be added here on purpose before anything can see it.
 */

function object(value: unknown, path: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(`${path} is not an object`);
  }
  return value as Record<string, unknown>;
}

function text(value: unknown, path: string): string {
  if (typeof value !== 'string') {
    throw new Error(`${path} is not a string`);
  }
  return value;
}

function optionalText(value: unknown, path: string): string | null {
  return value === null || value === undefined ? null : text(value, path);
}

function count(value: unknown, path: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error(`${path} is not a number`);
  }
  return value;
}

function optionalCount(value: unknown, path: string): number | null {
  return value === null || value === undefined ? null : count(value, path);
}

function textList(value: unknown, path: string): string[] {
  if (!Array.isArray(value)) {
    throw new Error(`${path} is not an array`);
  }
  return value.map((entry, index) => text(entry, `${path}[${index}]`));
}

function metrics(value: unknown, path: string): AgentMetricsDto {
  const source = object(value, path);
  return {
    inputTokens: count(source.inputTokens, `${path}.inputTokens`),
    cachedInputTokens: count(source.cachedInputTokens, `${path}.cachedInputTokens`),
    outputTokens: count(source.outputTokens, `${path}.outputTokens`),
    cost: optionalCount(source.cost, `${path}.cost`),
    costProvenance: text(source.costProvenance, `${path}.costProvenance`),
    elapsed: text(source.elapsed, `${path}.elapsed`),
  };
}

function defaults(value: unknown, path: string): AgentTaskDefaultsDto {
  const source = object(value, path);
  return {
    model: optionalText(source.model, `${path}.model`),
    effort: optionalText(source.effort, `${path}.effort`),
    permissionPolicy: text(source.permissionPolicy, `${path}.permissionPolicy`),
  };
}

function summary(value: unknown, path: string): AgentTaskSummaryDto {
  const source = object(value, path);
  return {
    finalResponse: optionalText(source.finalResponse, `${path}.finalResponse`),
    resultBranch: optionalText(source.resultBranch, `${path}.resultBranch`),
    resultHead: optionalText(source.resultHead, `${path}.resultHead`),
    tests: textList(source.tests ?? [], `${path}.tests`),
    metrics: metrics(source.metrics, `${path}.metrics`),
    learnings: textList(source.learnings ?? [], `${path}.learnings`),
    notes: optionalText(source.notes, `${path}.notes`),
  };
}

export function parseAgentTask(value: unknown, path = 'task'): AgentTaskDto {
  const source = object(value, path);
  return {
    id: text(source.id, `${path}.id`),
    workspaceId: text(source.workspaceId, `${path}.workspaceId`),
    title: text(source.title, `${path}.title`),
    laneId: text(source.laneId, `${path}.laneId`),
    defaults: defaults(source.defaults, `${path}.defaults`),
    state: text(source.state, `${path}.state`),
    version: count(source.version, `${path}.version`),
    createdAt: text(source.createdAt, `${path}.createdAt`),
    updatedAt: text(source.updatedAt, `${path}.updatedAt`),
    terminalAt: optionalText(source.terminalAt, `${path}.terminalAt`),
    terminalReason: optionalText(source.terminalReason, `${path}.terminalReason`),
    metrics: metrics(source.metrics, `${path}.metrics`),
    summary: source.summary === null || source.summary === undefined
      ? null
      : summary(source.summary, `${path}.summary`),
    cleanupState: text(source.cleanupState, `${path}.cleanupState`),
    deleteAfter: optionalText(source.deleteAfter, `${path}.deleteAfter`),
    cleanupFailure: optionalText(source.cleanupFailure, `${path}.cleanupFailure`),
  };
}

export function parseAgentTasks(value: unknown): AgentTaskDto[] {
  if (!Array.isArray(value)) {
    throw new Error('tasks is not an array');
  }
  return value.map((entry, index) => parseAgentTask(entry, `tasks[${index}]`));
}

/** Narrows turn settings, whether they came from a turn or from the effective-settings read. */
export function parseAgentTurnSettings(value: unknown, path = 'effectiveSettings'): AgentTurnSettingsDto {
  const source = object(value, path);
  return {
    model: optionalText(source.model, `${path}.model`),
    effort: optionalText(source.effort, `${path}.effort`),
    permissionPolicy: text(source.permissionPolicy, `${path}.permissionPolicy`),
    sandboxPolicy: text(source.sandboxPolicy, `${path}.sandboxPolicy`),
    maximumRuntimeMillis: count(source.maximumRuntimeMillis, `${path}.maximumRuntimeMillis`),
    // Attachment contents and their paths stay in the backend; only their ids may cross.
    attachments: textList(source.attachments ?? [], `${path}.attachments`),
  };
}

export function parseAgentTurn(value: unknown, path = 'turn'): AgentTurnDto {
  const source = object(value, path);
  return {
    id: text(source.id, `${path}.id`),
    taskId: text(source.taskId, `${path}.taskId`),
    state: text(source.state, `${path}.state`),
    version: count(source.version, `${path}.version`),
    createdAt: text(source.createdAt, `${path}.createdAt`),
    startedAt: optionalText(source.startedAt, `${path}.startedAt`),
    endedAt: optionalText(source.endedAt, `${path}.endedAt`),
    cancellationRequestedAt: optionalText(source.cancellationRequestedAt, `${path}.cancellationRequestedAt`),
    settings: source.settings === null || source.settings === undefined
      ? null
      : parseAgentTurnSettings(source.settings, `${path}.settings`),
    terminalReason: optionalText(source.terminalReason, `${path}.terminalReason`),
    metrics: metrics(source.metrics, `${path}.metrics`),
  };
}

export function parseAgentApproval(value: unknown, path = 'approval'): AgentApprovalDto {
  const source = object(value, path);
  const decision = text(source.decision, `${path}.decision`);
  if (decision !== 'ALLOW' && decision !== 'DENY') {
    throw new Error(`${path}.decision is not a decision the renderer can represent`);
  }
  return {
    approvalId: text(source.approvalId, `${path}.approvalId`),
    decision,
    scopedPermission: optionalText(source.scopedPermission, `${path}.scopedPermission`),
    resolvedAt: text(source.resolvedAt, `${path}.resolvedAt`),
    timedOut: source.timedOut === true,
  };
}

export function parseAgentCompletion(value: unknown, path = 'completion'): AgentCompletionDto {
  const source = object(value, path);
  return {
    taskId: text(source.taskId, `${path}.taskId`),
    summary: summary(source.summary, `${path}.summary`),
    completedAt: text(source.completedAt, `${path}.completedAt`),
    deleteAfter: text(source.deleteAfter, `${path}.deleteAfter`),
  };
}
