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

import type { JiraTaskSource } from '../jira/contracts';
import type {
  AgentIssueSourceDto,
  DevelopmentAutomationDto,
  DevelopmentResultDto,
  AgentModelSettingDto,
  AgentApprovalDto,
  AgentAttachmentDto,
  AgentCapabilitiesDto,
  AgentCompletionDto,
  AgentDevelopmentCiDto,
  CiAutofixRunDto,
  AgentInputDeliveryDto,
  AgentMetricsDto,
  AgentMutationClaimDto,
  AgentProviderReadiness,
  AgentProviderStatus,
  AgentProviderUnavailableDto,
  AgentQueuedInputDto,
  AgentRuntimeStopImpactDto,
  AgentSkillDto,
  AgentSkillInput,
  AgentTaskDefaultsDto,
  AgentTaskChangesDto,
  AgentTaskDto,
  AgentTaskPullRequestDto,
  AgentTaskStartupDto,
  AgentTaskStartupPhase,
  AgentTaskSummaryDto,
  AgentTurnDto,
  AgentTurnSettingsDto,
  ModelApiKeyDto,
  ModelOptionDto,
  ModelProviderDto,
  ModelSettingsDto,
  PullRequestTaskLink,
} from '../types';

const AGENT_PROVIDER_UNAVAILABLE_MARKER = 'AGENT_PROVIDER_UNAVAILABLE:';

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

function flag(value: unknown, path: string): boolean {
  if (typeof value !== 'boolean') {
    throw new Error(`${path} is not a boolean`);
  }
  return value;
}

function enumText<const Values extends readonly string[]>(
  value: unknown,
  values: Values,
  path: string,
): Values[number] {
  const found = text(value, path);
  if (!(values as readonly string[]).includes(found)) {
    throw new Error(`${path} is not supported`);
  }
  return found as Values[number];
}

export function parseAgentProviderUnavailable(
  value: unknown,
  path = 'providerUnavailable',
): AgentProviderUnavailableDto {
  const source = object(value, path);
  const status = count(source.status, `${path}.status`);
  if (status !== 503) throw new Error(`${path}.status is not 503`);
  const code = text(source.code, `${path}.code`);
  if (code !== 'AGENT_PROVIDER_UNAVAILABLE') {
    throw new Error(`${path}.code is not AGENT_PROVIDER_UNAVAILABLE`);
  }
  return {
    timestamp: text(source.timestamp, `${path}.timestamp`),
    status,
    code,
    laneId: text(source.laneId, `${path}.laneId`),
    readiness: enumText(
      source.readiness,
      ['READY', 'AUTHENTICATION_REQUIRED', 'CLI_MISSING', 'UNSUPPORTED', 'DEGRADED', 'TEMPORARILY_UNAVAILABLE'] as const,
      `${path}.readiness`,
    ),
    diagnostic: text(source.diagnostic, `${path}.diagnostic`),
    signInCommand: optionalText(source.signInCommand, `${path}.signInCommand`),
    path: text(source.path, `${path}.path`),
  };
}

/** Reads either a direct bridge rejection or Electron's serialized Error message. */
export function agentProviderUnavailableFrom(failure: unknown): AgentProviderUnavailableDto | null {
  if (typeof failure === 'object' && failure !== null && 'code' in failure) {
    try {
      return parseAgentProviderUnavailable(failure);
    }
    catch {
      return null;
    }
  }
  const message = failure instanceof Error ? failure.message : typeof failure === 'string' ? failure : '';
  const marker = message.indexOf(AGENT_PROVIDER_UNAVAILABLE_MARKER);
  if (marker < 0) return null;
  try {
    return parseAgentProviderUnavailable(JSON.parse(message.slice(marker + AGENT_PROVIDER_UNAVAILABLE_MARKER.length)));
  }
  catch {
    return null;
  }
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

function list(value: unknown, path: string): unknown[] {
  if (!Array.isArray(value)) {
    throw new Error(`${path} is not an array`);
  }
  return value;
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
    elapsed: text(source.elapsed, `${path}.elapsed`),
  };
}

export function parseDevelopmentAutomation(value: unknown, path = 'developmentAutomation'): DevelopmentAutomationDto {
  const source = object(value, path);
  return {
    autoDraftPr: flag(source.autoDraftPr, `${path}.autoDraftPr`),
    autoCiFix: flag(source.autoCiFix, `${path}.autoCiFix`),
    ciLaneId: optionalText(source.ciLaneId, `${path}.ciLaneId`),
    reviewSummary: flag(source.reviewSummary, `${path}.reviewSummary`),
  };
}

function automationField(source: Record<string, unknown>, path: string): { developmentAutomation?: DevelopmentAutomationDto | null } {
  if (source.developmentAutomation === undefined) return {};
  return { developmentAutomation: source.developmentAutomation === null ? null : parseDevelopmentAutomation(source.developmentAutomation, `${path}.developmentAutomation`) };
}

function defaults(value: unknown, path: string): AgentTaskDefaultsDto {
  const source = object(value, path);
  return {
    model: optionalText(source.model, `${path}.model`),
    effort: optionalText(source.effort, `${path}.effort`),
    serviceTier: optionalText(source.serviceTier, `${path}.serviceTier`),
    permissionPolicy: text(source.permissionPolicy, `${path}.permissionPolicy`),
    ...automationField(source, path),
  };
}

function claim(value: unknown, path: string): AgentMutationClaimDto {
  const source = object(value, path);
  return {
    kind: text(source.kind, `${path}.kind`),
    ownerId: text(source.ownerId, `${path}.ownerId`),
    acquiredAt: text(source.acquiredAt, `${path}.acquiredAt`),
  };
}

function summary(value: unknown, path: string): AgentTaskSummaryDto {
  const source = object(value, path);
  return {
    finalResponse: optionalText(source.finalResponse, `${path}.finalResponse`),
    resultBranch: optionalText(source.resultBranch, `${path}.resultBranch`),
    resultHead: optionalText(source.resultHead, `${path}.resultHead`),
    metrics: metrics(source.metrics, `${path}.metrics`),
    notes: optionalText(source.notes, `${path}.notes`),
    // Absent on summaries stored before subagents were indexed, which read as none.
    children: list(source.children ?? [], `${path}.children`).map((child, index) => {
      const item = object(child, `${path}.children[${index}]`);
      return {
        threadId: text(item.threadId, `${path}.children[${index}].threadId`),
        parentThreadId: optionalText(item.parentThreadId, `${path}.children[${index}].parentThreadId`),
        label: text(item.label, `${path}.children[${index}].label`),
        purpose: optionalText(item.purpose, `${path}.children[${index}].purpose`),
        outcome: text(item.outcome, `${path}.children[${index}].outcome`),
        result: optionalText(item.result, `${path}.children[${index}].result`),
        startedAt: optionalText(item.startedAt, `${path}.children[${index}].startedAt`),
        endedAt: optionalText(item.endedAt, `${path}.children[${index}].endedAt`),
      };
    }),
  };
}

export function parsePullRequestTaskLink(value: unknown): PullRequestTaskLink {
  const source = object(value, 'pullRequestTask');
  return { workspaceId: text(source.workspaceId, 'pullRequestTask.workspaceId'), taskId: text(source.taskId, 'pullRequestTask.taskId') };
}

function issueSource(value: unknown, path: string): AgentIssueSourceDto {
  const source = object(value, path);
  return {
    initialTurnId: optionalText(source.initialTurnId, `${path}.initialTurnId`),
    instructions: source.instructions === undefined ? '' : text(source.instructions, `${path}.instructions`),
    host: text(source.host, `${path}.host`),
    issueId: count(source.issueId, `${path}.issueId`),
    repo: text(source.repo, `${path}.repo`),
    number: count(source.number, `${path}.number`),
    url: text(source.url, `${path}.url`),
    title: text(source.title, `${path}.title`),
    body: text(source.body, `${path}.body`),
    updatedAt: text(source.updatedAt, `${path}.updatedAt`),
  };
}

function jiraSource(value: unknown, path: string): JiraTaskSource {
  const source = object(value, path);
  return {
    connectionId: text(source.connectionId, `${path}.connectionId`), cloudId: text(source.cloudId, `${path}.cloudId`),
    issueId: text(source.issueId, `${path}.issueId`), key: text(source.key, `${path}.key`),
    url: text(source.url, `${path}.url`), title: text(source.title, `${path}.title`), body: text(source.body, `${path}.body`),
    updatedAt: text(source.updatedAt, `${path}.updatedAt`), initialTurnId: optionalText(source.initialTurnId, `${path}.initialTurnId`),
    instructions: text(source.instructions, `${path}.instructions`),
  };
}

export function parseAgentTask(value: unknown, path = 'task'): AgentTaskDto {
  const source = object(value, path);
  return {
    id: text(source.id, `${path}.id`),
    workspaceId: text(source.workspaceId, `${path}.workspaceId`),
    jiraSource: source.jiraSource == null ? null : jiraSource(source.jiraSource, `${path}.jiraSource`),
    issueSource: source.issueSource == null ? null : issueSource(source.issueSource, `${path}.issueSource`),
    generatedBranch: optionalText(source.generatedBranch, `${path}.generatedBranch`),
    ...(source.interactive === undefined ? {} : { interactive: flag(source.interactive, `${path}.interactive`) }),
    localReviewHead: optionalText(source.localReviewHead, `${path}.localReviewHead`),
    developmentContinuationUnfinished: flag(source.developmentContinuationUnfinished, `${path}.developmentContinuationUnfinished`),
    developmentCi: source.developmentCi === null || source.developmentCi === undefined ? null : parseAgentDevelopmentCi(source.developmentCi, `${path}.developmentCi`),
    pullRequest: source.pullRequest === null || source.pullRequest === undefined ? null : taskPullRequest(source.pullRequest, `${path}.pullRequest`),
    title: text(source.title, `${path}.title`),
    titleSource: text(source.titleSource, `${path}.titleSource`),
    laneId: text(source.laneId, `${path}.laneId`),
    defaults: defaults(source.defaults, `${path}.defaults`),
    state: text(source.state, `${path}.state`),
    mutationClaim: source.mutationClaim === null || source.mutationClaim === undefined
      ? null
      : claim(source.mutationClaim, `${path}.mutationClaim`),
    version: count(source.version, `${path}.version`),
    createdAt: text(source.createdAt, `${path}.createdAt`),
    updatedAt: text(source.updatedAt, `${path}.updatedAt`),
    terminalAt: optionalText(source.terminalAt, `${path}.terminalAt`),
    terminalReason: optionalText(source.terminalReason, `${path}.terminalReason`),
    metrics: metrics(source.metrics, `${path}.metrics`),
    summary: source.summary === null || source.summary === undefined
      ? null
      : summary(source.summary, `${path}.summary`),
    deleteAfter: optionalText(source.deleteAfter, `${path}.deleteAfter`),
    checkoutRemovedAt: optionalText(source.checkoutRemovedAt, `${path}.checkoutRemovedAt`),
    codegraph: flag(source.codegraph, `${path}.codegraph`),
    archivedAt: optionalText(source.archivedAt, `${path}.archivedAt`),
  };
}

function taskPullRequest(value: unknown, path: string): AgentTaskPullRequestDto {
  const source = object(value, path);
  return {
    number: count(source.number, `${path}.number`),
    title: text(source.title, `${path}.title`),
    state: text(source.state, `${path}.state`),
    draft: flag(source.draft, `${path}.draft`),
  };
}

export function parseAgentTaskChanges(value: unknown): Record<string, AgentTaskChangesDto> {
  const source = object(value, 'taskChanges');
  return Object.fromEntries(Object.entries(source).map(([taskId, entry]) => {
    const changes = object(entry, `taskChanges.${taskId}`);
    return [taskId, {
      uncommitted: flag(changes.uncommitted, `taskChanges.${taskId}.uncommitted`),
      commits: count(changes.commits, `taskChanges.${taskId}.commits`),
    }];
  }));
}

export function parseAgentTasks(value: unknown): AgentTaskDto[] {
  if (!Array.isArray(value)) {
    throw new Error('tasks is not an array');
  }
  return value.map((entry, index) => parseAgentTask(entry, `tasks[${index}]`));
}

export function parseAgentDevelopmentCi(value: unknown, path = 'developmentCi'): AgentDevelopmentCiDto {
  const source = object(value, path);
  return {
    runId: text(source.runId, `${path}.runId`),
    agentTaskId: optionalText(source.agentTaskId, `${path}.agentTaskId`),
    status: enumText(source.status, ['ACTIVE', 'PAUSED', 'FINISHED'] as const, `${path}.status`),
    activity: text(source.activity, `${path}.activity`),
    attemptsUsed: count(source.attemptsUsed, `${path}.attemptsUsed`),
    maxAttempts: count(source.maxAttempts, `${path}.maxAttempts`),
    rerunsUsed: count(source.rerunsUsed, `${path}.rerunsUsed`),
    summary: optionalText(source.summary, `${path}.summary`),
    finishReason: optionalText(source.finishReason, `${path}.finishReason`),
    terminalAt: optionalText(source.terminalAt, `${path}.terminalAt`),
    headRef: text(source.headRef, `${path}.headRef`),
    expectedRemoteHead: text(source.expectedRemoteHead, `${path}.expectedRemoteHead`),
    pullRequestNumber: count(source.pullRequestNumber, `${path}.pullRequestNumber`),
    pullRequestUrl: text(source.pullRequestUrl, `${path}.pullRequestUrl`),
    canMessage: flag(source.canMessage, `${path}.canMessage`),
  };
}

export function parseCiAutofixRun(value: unknown): CiAutofixRunDto {
  const source = object(value, 'ciRun');
  const authorization = object(source.authorization, 'ciRun.authorization');
  return {
    id: text(source.id, 'ciRun.id'), workspaceId: text(source.workspaceId, 'ciRun.workspaceId'),
    repository: text(source.repository, 'ciRun.repository'), pullRequestNumber: count(source.pullRequestNumber, 'ciRun.pullRequestNumber'),
    headRef: text(source.headRef, 'ciRun.headRef'),
    status: enumText(source.status, ['ACTIVE', 'PAUSED', 'FINISHED'] as const, 'ciRun.status'),
    activity: text(source.activity, 'ciRun.activity'), version: count(source.version, 'ciRun.version'),
    authorization: { maxAttempts: count(authorization.maxAttempts, 'ciRun.authorization.maxAttempts'), expiresAt: text(authorization.expiresAt, 'ciRun.authorization.expiresAt') },
    attemptsUsed: count(source.attemptsUsed, 'ciRun.attemptsUsed'), rerunsUsed: count(source.rerunsUsed, 'ciRun.rerunsUsed'),
    agentTaskId: optionalText(source.agentTaskId, 'ciRun.agentTaskId'), currentTurnId: optionalText(source.currentTurnId, 'ciRun.currentTurnId'),
    parentTaskId: optionalText(source.parentTaskId, 'ciRun.parentTaskId'), canMessage: flag(source.canMessage, 'ciRun.canMessage'),
    summary: optionalText(source.summary, 'ciRun.summary'), pauseReason: optionalText(source.pauseReason, 'ciRun.pauseReason'), finishReason: optionalText(source.finishReason, 'ciRun.finishReason'),
  };
}

/** Narrows turn settings, whether they came from a turn or from the effective-settings read. */
export function parseAgentTurnSettings(value: unknown, path = 'effectiveSettings'): AgentTurnSettingsDto {
  const source = object(value, path);
  return {
    ...defaults(value, path),
    maximumRuntimeMillis: count(source.maximumRuntimeMillis, `${path}.maximumRuntimeMillis`),
    // Attachment contents and their paths stay in the backend; only their ids may cross.
    attachments: list(source.attachments ?? [], `${path}.attachments`).map((entry, index) => {
      const item = object(entry, `${path}.attachments[${index}]`);
      return {
        id: text(item.id, `${path}.attachments[${index}].id`),
        name: text(item.name, `${path}.attachments[${index}].name`),
        mediaType: text(item.mediaType, `${path}.attachments[${index}].mediaType`),
      };
    }),
  };
}

export function parseDevelopmentResult(value: unknown, path = 'developmentResult'): DevelopmentResultDto {
  const source = object(value, path);
  const summary = source.learningSummary == null ? null : object(source.learningSummary, `${path}.learningSummary`);
  return {
    readyForDelivery: flag(source.readyForDelivery, `${path}.readyForDelivery`),
    message: optionalText(source.message, `${path}.message`),
    ...(source.learningSummary === undefined ? {} : { learningSummary: summary === null ? null : {
      purpose: text(summary.purpose, `${path}.learningSummary.purpose`),
      commits: list(summary.commits, `${path}.learningSummary.commits`).map((value, index) => {
        const commit = object(value, `${path}.learningSummary.commits[${index}]`);
        return { sha: text(commit.sha, 'commit.sha'), title: text(commit.title, 'commit.title'), summary: text(commit.summary, 'commit.summary') };
      }),
      combinedResult: optionalText(summary.combinedResult, `${path}.learningSummary.combinedResult`),
      evidence: text(summary.evidence, `${path}.learningSummary.evidence`),
      limitations: textList(summary.limitations, `${path}.learningSummary.limitations`),
    } }),
  };
}

export function parseAgentTurn(value: unknown, path = 'turn'): AgentTurnDto {
  const source = object(value, path);
  const origin = source.developmentOrigin === null || source.developmentOrigin === undefined ? null : object(source.developmentOrigin, `${path}.developmentOrigin`);
  return {
    plan: source.plan === null || source.plan === undefined ? null : list(source.plan, `${path}.plan`).map((value, index) => {
      const itemPath = `${path}.plan[${index}]`;
      const item = object(value, itemPath);
      return { text: text(item.text, `${itemPath}.text`), state: enumText(item.state, ['PENDING', 'IN_PROGRESS', 'COMPLETED'] as const, `${itemPath}.state`) };
    }),
    id: text(source.id, `${path}.id`),
    taskId: text(source.taskId, `${path}.taskId`),
    developmentOrigin: origin === null ? null : {
      kind: enumText(origin.kind, ['AUTO_COMMIT', 'CREATE_DRAFT_PR', 'REFINE_SUMMARY'] as const, `${path}.developmentOrigin.kind`),
      parentUserTurnId: text(origin.parentUserTurnId, `${path}.developmentOrigin.parentUserTurnId`),
    },
    ...(source.developmentResult === undefined ? {} : { developmentResult: source.developmentResult === null ? null : parseDevelopmentResult(source.developmentResult, `${path}.developmentResult`) }),
    state: text(source.state, `${path}.state`),
    version: count(source.version, `${path}.version`),
    createdAt: text(source.createdAt, `${path}.createdAt`),
    startedAt: optionalText(source.startedAt, `${path}.startedAt`),
    endedAt: optionalText(source.endedAt, `${path}.endedAt`),
    cancellationRequestedAt: optionalText(source.cancellationRequestedAt, `${path}.cancellationRequestedAt`),
    delivered: source.delivered === true,
    knownUnsent: source.knownUnsent === true,
    settings: source.settings === null || source.settings === undefined
      ? null
      : parseAgentTurnSettings(source.settings, `${path}.settings`),
    failureKind: source.failureKind === null || source.failureKind === undefined
      ? null
      : enumText(source.failureKind, ['AUTHENTICATION_REQUIRED'] as const, `${path}.failureKind`),
    terminalReason: optionalText(source.terminalReason, `${path}.terminalReason`),
    metrics: metrics(source.metrics, `${path}.metrics`),
  };
}

/** Narrows a provider capability shape before it can decide which renderer controls exist. */
function parseAgentCapabilities(value: unknown, path = 'capabilities'): AgentCapabilitiesDto {
  const source = object(value, path);
  return {
    structuredPlans: flag(source.structuredPlans, `${path}.structuredPlans`),
    nestedAgentActivity: flag(source.nestedAgentActivity, `${path}.nestedAgentActivity`),
    steering: flag(source.steering, `${path}.steering`),
    permissionPolicies: textList(source.permissionPolicies, `${path}.permissionPolicies`),
    attachmentMediaTypes: textList(source.attachmentMediaTypes, `${path}.attachmentMediaTypes`),
  };
}

const PROVIDER_READINESS = [
  'READY',
  'AUTHENTICATION_REQUIRED',
  'CLI_MISSING',
  'UNSUPPORTED',
  'DEGRADED',
  'TEMPORARILY_UNAVAILABLE',
] as const satisfies readonly AgentProviderReadiness[];

export function parseAgentProviderStatus(value: unknown, path = 'provider'): AgentProviderStatus {
  const source = object(value, path);
  return {
    laneId: text(source.laneId, `${path}.laneId`),
    readiness: enumText(source.readiness, PROVIDER_READINESS, `${path}.readiness`),
    version: optionalText(source.version, `${path}.version`),
    diagnostic: text(source.diagnostic, `${path}.diagnostic`),
    capabilities: parseAgentCapabilities(source.capabilities, `${path}.capabilities`),
    signInCommand: optionalText(source.signInCommand, `${path}.signInCommand`),
  };
}

export function parseAgentProviderStatuses(value: unknown): AgentProviderStatus[] {
  return list(value, 'providers').map((provider, index) => parseAgentProviderStatus(provider, `providers[${index}]`));
}

function modelOption(value: unknown, path: string): ModelOptionDto {
  const source = object(value, path);
  const inputModalities = source.inputModalities === undefined || source.inputModalities === null
    ? undefined
    : textList(source.inputModalities, `${path}.inputModalities`);
  return {
    id: text(source.id, `${path}.id`),
    name: text(source.name, `${path}.name`),
    description: text(source.description, `${path}.description`),
    efforts: textList(source.efforts, `${path}.efforts`),
    defaultEffort: optionalText(source.defaultEffort, `${path}.defaultEffort`),
    ...(inputModalities === undefined ? {} : { inputModalities }),
    serviceTiers: list(source.serviceTiers ?? [], `${path}.serviceTiers`).map((entry, index) => {
      const tierPath = `${path}.serviceTiers[${index}]`;
      const tier = object(entry, tierPath);
      return {
        id: text(tier.id, `${tierPath}.id`),
        name: text(tier.name, `${tierPath}.name`),
        description: text(tier.description, `${tierPath}.description`),
      };
    }),
  };
}

function modelProvider(value: unknown, path: string): ModelProviderDto {
  const source = object(value, path);
  return {
    id: text(source.id, `${path}.id`),
    label: text(source.label, `${path}.label`),
    readiness: enumText(source.readiness, PROVIDER_READINESS, `${path}.readiness`),
    version: optionalText(source.version, `${path}.version`),
    diagnostic: text(source.diagnostic, `${path}.diagnostic`),
    signInCommand: optionalText(source.signInCommand, `${path}.signInCommand`),
    capabilities: parseAgentCapabilities(source.capabilities, `${path}.capabilities`),
    models: list(source.models, `${path}.models`).map((model, index) => modelOption(model, `${path}.models[${index}]`)),
    selectedModel: text(source.selectedModel, `${path}.selectedModel`),
    selectedEffort: text(source.selectedEffort, `${path}.selectedEffort`),
  };
}

function modelApiKey(value: unknown, path: string): ModelApiKeyDto {
  const source = object(value, path);
  return {
    id: text(source.id, `${path}.id`),
    alias: text(source.alias, `${path}.alias`),
    provider: text(source.provider, `${path}.provider`),
    maskedKey: text(source.maskedKey, `${path}.maskedKey`),
    createdAt: text(source.createdAt, `${path}.createdAt`),
    models: textList(source.models, `${path}.models`),
    selectedModel: text(source.selectedModel, `${path}.selectedModel`),
  };
}

export function parseAgentModelSetting(value: unknown, path: string): AgentModelSettingDto {
  const source = object(value, path);
  const type = source.type;
  if (type !== 'DEVELOPMENT' && type !== 'REVIEW') {
    throw new Error(`${path}.type is not an agent model type`);
  }
  return {
    type,
    explicit: flag(source.explicit, `${path}.explicit`),
    configId: text(source.configId, `${path}.configId`),
    label: text(source.label, `${path}.label`),
    model: text(source.model, `${path}.model`),
    modelLabel: text(source.modelLabel, `${path}.modelLabel`),
    effort: optionalText(source.effort, `${path}.effort`),
  };
}

export function parseModelSettings(value: unknown): ModelSettingsDto {
  const source = object(value, 'modelSettings');
  return {
    agents: list(source.agents, 'modelSettings.agents')
      .map((agent, index) => parseAgentModelSetting(agent, `modelSettings.agents[${index}]`)),
    providers: list(source.providers, 'modelSettings.providers')
      .map((provider, index) => modelProvider(provider, `modelSettings.providers[${index}]`)),
    apiKeys: list(source.apiKeys, 'modelSettings.apiKeys')
      .map((key, index) => modelApiKey(key, `modelSettings.apiKeys[${index}]`)),
    keyProviders: list(source.keyProviders, 'modelSettings.keyProviders').map((entry, index) => {
      const item = object(entry, `modelSettings.keyProviders[${index}]`);
      return {
        name: text(item.name, `modelSettings.keyProviders[${index}].name`),
        models: textList(item.models, `modelSettings.keyProviders[${index}].models`),
      };
    }),
  };
}

export function parseAgentSkillInput(value: unknown, path = 'agentSkill'): AgentSkillInput {
  const source = object(value, path);
  const scope = source.scope;
  if (scope !== 'GLOBAL' && scope !== 'DEVELOPMENT' && scope !== 'REVIEW') {
    throw new Error(`${path}.scope is not an agent skill scope`);
  }
  return {
    name: text(source.name, `${path}.name`),
    instructions: text(source.instructions, `${path}.instructions`),
    enabled: flag(source.enabled, `${path}.enabled`),
    scope,
  };
}

export function parseAgentSkills(value: unknown): AgentSkillDto[] {
  return list(value, 'agentSkills').map((entry, index) => {
    const path = `agentSkills[${index}]`;
    const source = object(entry, path);
    return {
      ...parseAgentSkillInput(source, path),
      id: text(source.id, `${path}.id`),
    };
  });
}

export function parseAgentQueuedInput(value: unknown, path = 'queuedInput'): AgentQueuedInputDto {
  const source = object(value, path);
  return {
    id: text(source.id, `${path}.id`),
    taskId: text(source.taskId, `${path}.taskId`),
    position: count(source.position, `${path}.position`),
    state: text(source.state, `${path}.state`),
    preview: text(source.preview, `${path}.preview`),
    settings: parseAgentTurnSettings(source.settings, `${path}.settings`),
    turnId: optionalText(source.turnId, `${path}.turnId`),
    pauseReason: optionalText(source.pauseReason, `${path}.pauseReason`),
    createdAt: text(source.createdAt, `${path}.createdAt`),
    updatedAt: text(source.updatedAt, `${path}.updatedAt`),
  };
}

export function parseAgentQueue(value: unknown): AgentQueuedInputDto[] {
  if (!Array.isArray(value)) {
    throw new Error('queue is not an array');
  }
  return value.map((entry, index) => parseAgentQueuedInput(entry, `queue[${index}]`));
}

export function parseAgentInputDelivery(value: unknown, path = 'delivery'): AgentInputDeliveryDto {
  const source = object(value, path);
  return {
    id: text(source.id, `${path}.id`),
    taskId: text(source.taskId, `${path}.taskId`),
    turnId: text(source.turnId, `${path}.turnId`),
    state: text(source.state, `${path}.state`),
    failure: optionalText(source.failure, `${path}.failure`),
    createdAt: text(source.createdAt, `${path}.createdAt`),
    updatedAt: text(source.updatedAt, `${path}.updatedAt`),
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
    forTask: source.forTask === true,
    resolvedAt: text(source.resolvedAt, `${path}.resolvedAt`),
    timedOut: source.timedOut === true,
  };
}

export function parseAgentRuntimeStopImpact(value: unknown, path = 'stopImpact'): AgentRuntimeStopImpactDto {
  const source = object(value, path);
  return {
    taskId: text(source.taskId, `${path}.taskId`),
    generation: text(source.generation, `${path}.generation`),
    activeTurnId: optionalText(source.activeTurnId, `${path}.activeTurnId`),
    liveProcess: source.liveProcess === true,
  };
}

const STARTUP_PHASES: readonly AgentTaskStartupPhase[] = ['PROVIDER', 'REPOSITORY', 'NAMING', 'WORKTREE', 'ATTACHMENTS', 'SESSION'];

export function parseAgentTaskStartup(value: unknown, path = 'startup'): AgentTaskStartupDto {
  const phase = optionalText(object(value, path).phase, `${path}.phase`);
  // A phase this build does not know reads as "not started yet" rather than failing the poll.
  return { phase: STARTUP_PHASES.find((known) => known === phase) ?? null };
}

export function parseAgentAttachment(value: unknown, path = 'attachment'): AgentAttachmentDto {
  const source = object(value, path);
  const size = (field: string): number => {
    const number = source[field];
    if (typeof number !== 'number' || !Number.isSafeInteger(number) || number <= 0) {
      throw new Error(`${path}.${field} must be a positive integer`);
    }
    return number;
  };
  return {
    id: text(source.id, `${path}.id`),
    name: text(source.name, `${path}.name`),
    mediaType: text(source.mediaType, `${path}.mediaType`),
    byteSize: size('byteSize'),
    width: size('width'),
    height: size('height'),
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
