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

import contracts from './agent-contracts-v1.json';
import eventFixture from './agent-events-v2.json';
import { parseAgentEventSequence, type AgentEventEnvelope } from '../events';
import type { AgentBridgePort } from '../model';
import { NO_AGENT_CAPABILITIES } from '../capabilities';
import type {
  AgentQueuedInputDto,
  AgentStreamMessage,
  AgentTaskDefaultsDto,
  AgentTaskDto,
  AgentTurnDto,
  ModelSettingsDto,
} from '../../types';

/** Every command the preview sent, so a test can assert the exact bridge calls. */
export interface PreviewCommand {
  name: string;
  args: unknown[];
}

interface PreviewOptions {
  /** Events replayed before the subscription starts; the rest arrive live. */
  replayThrough?: number;
}

const FIXTURE_EVENTS: AgentEventEnvelope[] = parseAgentEventSequence(eventFixture.events);

const PREVIEW_CAPABILITIES = {
  ...NO_AGENT_CAPABILITIES,
  permissionPolicies: ['AUTO', 'CI_AUTOFIX'],
  structuredPlans: true,
  steering: true,
  attachmentMediaTypes: ['image/png', 'image/jpeg', 'image/gif'],
};

/** A catalog shaped like a probed Codex lane, so the preview exercises real selection code. */
const PREVIEW_CATALOG: ModelSettingsDto = {
  agents: [
    { type: 'DEVELOPMENT', explicit: true, configId: 'codex', label: 'Codex', model: 'gpt-5.6-codex', modelLabel: '5.6 Terra', effort: 'high' },
    { type: 'REVIEW', explicit: false, configId: 'codex', label: 'Codex', model: 'gpt-5.6-codex', modelLabel: '5.6 Terra', effort: 'high' },
  ],
  providers: [{
    id: 'codex',
    label: 'Codex',
    readiness: 'READY',
    version: '0.151.0',
    diagnostic: '',
    capabilities: PREVIEW_CAPABILITIES,
    models: [
      { id: 'gpt-5.6-codex', name: '5.6 Terra', description: 'Balanced', efforts: ['medium', 'high'], defaultEffort: 'high' },
      { id: 'gpt-5.6-codex-mini', name: '5.6 Sol', description: 'Faster', efforts: ['medium'], defaultEffort: 'medium' },
    ],
    selectedModel: 'gpt-5.6-codex',
    selectedEffort: 'high',
  }],
  apiKeys: [],
  keyProviders: [],
};

/** The AgentTaskBody callbacks a render-only fixture never drives. Spread first, then override the one under test. */
export const TASK_BODY_NOOPS = {
  onSelectModel: () => {},
  onBack: () => {},
  onSend: () => {},
  onCancel: () => {},
  onComplete: () => {},
  onDecide: () => {},
  onCompose: () => {},
  onStopServer: () => {},
  onMarkFailed: () => {},
};

/** A turn at rest. Each suite overrides the few fields its own fixtures need. */
export function agentTurn(state: string, extra: Partial<AgentTurnDto> = {}): AgentTurnDto {
  return {
    id: 'turn-1',
    taskId: 't-1',
    state,
    version: 1,
    createdAt: '2026-08-20T10:00:00Z',
    startedAt: '2026-08-20T10:00:01Z',
    endedAt: null,
    cancellationRequestedAt: null,
    delivered: true,
    knownUnsent: false,
    settings: null,
    terminalReason: null,
    metrics: { inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, cost: null, elapsed: 'PT0S' },
    ...extra,
  };
}

export function previewTask(workspaceId: string, taskId: string): AgentTaskDto {
  return {
    ...(contracts.task as AgentTaskDto),
    id: taskId,
    workspaceId,
    generatedBranch: `contributor/bytequay/review-the-authentication-change-${taskId.slice(0, 8)}`,
    title: eventFixture.createTaskRequest.message,
    state: 'OPEN',
  };
}

function previewTurn(taskId: string, state: string): AgentTurnDto {
  return { ...(contracts.turn as AgentTurnDto), taskId, state };
}

/**
 * A bridge backed by the frozen fixture instead of a provider.
 *
 * The preview must never reach a real agent, so every method here answers from checked-in data and
 * records what it was asked to do.
 */
export function createPreviewBridge(
  _workspaceId: string,
  options: PreviewOptions = {},
): AgentBridgePort & { commands: PreviewCommand[]; events: AgentEventEnvelope[] } {
  const commands: PreviewCommand[] = [];
  const record = (name: string, ...args: unknown[]): void => {
    commands.push({ name, args });
  };
  let replayThrough = options.replayThrough ?? 2;
  let turnState = 'COMPLETED';
  let previewVersion = 0;
  const previewQueue: AgentQueuedInputDto[] = [];
  let previewDefaults: AgentTaskDefaultsDto = {
    model: 'gpt-5.6-codex',
    effort: 'high',
    permissionPolicy: 'AUTO',
  };

  return {
    commands,
    events: FIXTURE_EVENTS,

    getModelSettings: async () => {
      record('getModelSettings');
      return PREVIEW_CATALOG;
    },

    refreshAgentProviderReadiness: async () => {
      record('refreshAgentProviderReadiness');
      return PREVIEW_CATALOG.providers.map((provider) => ({
        laneId: provider.id,
        readiness: provider.readiness,
        version: provider.version,
        diagnostic: provider.diagnostic,
        capabilities: provider.capabilities,
        signInCommand: provider.signInCommand,
      }));
    },

    // A preview has no pty; the sign-in terminal is exercised in the app, not here.
    openProviderSignIn: async (laneId) => {
      record('openProviderSignIn', laneId);
      throw new Error('Sign-in is unavailable in this preview');
    },
    writeReviewTerminal: () => {},
    resizeReviewTerminal: () => {},
    onReviewTerminalEvent: () => () => {},

    fetchWorkspaceIssue: async () => { throw new Error('No issue selected in this preview'); },
    listAgentTasks: async (id, limit) => {
      record('listAgentTasks', id, limit);
      return [previewTask(id, FIXTURE_EVENTS[0].taskId)];
    },
    pickAgentDraftAttachments: async () => [],
    pasteAgentDraftAttachments: async () => {
      record('pasteAgentDraftAttachments');
      return [];
    },
    discardAgentDraftAttachments: async () => {},
    getDevelopmentAutomation: async () => ({ level: 'CI_FIX', ciLaneId: null }),
    createAgentTask: async (request) => {
      record('createAgentTask', request);
      return previewTask(request.workspaceId, request.taskId);
    },
    getAgentTask: async (taskId, id) => {
      record('getAgentTask', taskId, id);
      return { ...previewTask(id, taskId), defaults: previewDefaults, version: previewVersion };
    },
    // Not recorded: the creation screen polls this on a timer, which would make command logs timing-dependent.
    getAgentTaskStartup: async () => ({ phase: null }),
    updateAgentTaskDefaults: async (taskId, id, expectedVersion, defaults) => {
      record('updateAgentTaskDefaults', taskId, id, expectedVersion, defaults);
      if (expectedVersion !== previewVersion) {
        throw new Error(`Task version changed: ${taskId}`);
      }
      // The real service bumps the version on write, so a stale screen must fail the same way here.
      previewVersion += 1;
      previewDefaults = defaults;
      return { ...previewTask(id, taskId), defaults, version: previewVersion };
    },
    listAgentTaskPullRequests: async (taskId, id, refresh) => {
      record('listAgentTaskPullRequests', taskId, id, refresh);
      return [];
    },
    listAgentTaskChanges: async (id) => {
      record('listAgentTaskChanges', id);
      return {};
    },
    listAgentTaskActivity: async (id) => {
      record('listAgentTaskActivity', id);
      return {};
    },
    listTaskDiscussions: async query => {
      record('listTaskDiscussions', query);
      return [];
    },
    openTaskDiscussion: async request => {
      record('openTaskDiscussion', request);
      throw new Error('This preview has no discussions');
    },
    askTaskDiscussion: async request => {
      record('askTaskDiscussion', request);
      throw new Error('This preview has no discussions');
    },
    resolveTaskDiscussion: async request => {
      record('resolveTaskDiscussion', request);
      throw new Error('This preview has no discussions');
    },
    retryTaskDiscussion: async request => {
      record('retryTaskDiscussion', request);
      throw new Error('This preview has no discussions');
    },
    askTaskDiscussionComment: async request => {
      record('askTaskDiscussionComment', request);
      throw new Error('This preview has no discussions');
    },
    draftTaskDiscussionReply: async request => {
      record('draftTaskDiscussionReply', request);
      throw new Error('This preview has no discussions');
    },
    listLocalReviewComments: async query => {
      record('listLocalReviewComments', query);
      return [];
    },
    createLocalReviewComment: async request => {
      record('createLocalReviewComment', request);
      throw new Error('This preview has no local review discussion');
    },
    replyLocalReviewComment: async request => {
      record('replyLocalReviewComment', request);
      throw new Error('This preview has no local review discussion');
    },
    resolveLocalReviewComment: async request => {
      record('resolveLocalReviewComment', request);
      throw new Error('This preview has no local review discussion');
    },
    retryLocalReviewComment: async request => {
      record('retryLocalReviewComment', request);
      throw new Error('This preview has no local review discussion');
    },
    getCiAutofixRun: async () => { throw new Error('This preview has no CI run'); },
    cancelCiAutofixRun: async () => { throw new Error('This preview has no CI run'); },
    sendCiAutofixMessage: async () => { throw new Error('This preview has no CI run'); },
    syncPrs: async (id) => {
      record('syncPrs', id);
    },
    renameAgentTask: async (taskId, id, expectedVersion, title) => {
      record('renameAgentTask', taskId, id, expectedVersion, title);
      if (expectedVersion !== previewVersion) {
        throw new Error(`Task version changed: ${taskId}`);
      }
      previewVersion += 1;
      return { ...previewTask(id, taskId), title, titleSource: 'USER', defaults: previewDefaults, version: previewVersion };
    },
    archiveAgentTask: async (taskId, id, expectedVersion, archived) => {
      record('archiveAgentTask', taskId, id, expectedVersion, archived);
      if (expectedVersion !== previewVersion) {
        throw new Error(`Task version changed: ${taskId}`);
      }
      previewVersion += 1;
      return { ...previewTask(id, taskId), defaults: previewDefaults, version: previewVersion, archivedAt: archived ? '2026-08-20T10:12:00Z' : null };
    },
    deleteAgentTask: async (taskId, id) => {
      record('deleteAgentTask', taskId, id);
    },
    getEffectiveTurnSettings: async (taskId, id) => {
      record('getEffectiveTurnSettings', taskId, id);
      return {
        model: previewDefaults.model,
        effort: previewDefaults.effort,
        serviceTier: previewDefaults.serviceTier ?? null,
        permissionPolicy: previewDefaults.permissionPolicy,
        maximumRuntimeMillis: 3600000,
        attachments: [],
      };
    },
    readAgentHistory: async (taskId, before, after) => {
      record('readAgentHistory', taskId, before, after);
      const all = FIXTURE_EVENTS.filter((event) => event.sequence <= replayThrough);
      const start = after === undefined ? Math.max(0, (before === undefined ? all.length : Number(before)) - 1000) : Number(after);
      const end = before === undefined ? Math.min(all.length, start + 1000) : Number(before);
      return { events: all.slice(start, end), beforeCursor: start > 0 ? String(start) : null, afterCursor: end < all.length ? String(end) : null, latestSequence: all.at(-1)?.sequence ?? 0 };
    },
    readAgentPendingApprovals: async (taskId, turnId) => {
      record('readAgentPendingApprovals', taskId, turnId);
      const resolved = new Set(FIXTURE_EVENTS.filter((event) => event.type === 'approval_resolved').map((event) => event.payload.approvalId));
      return FIXTURE_EVENTS.flatMap((event) => event.type !== 'approval_requested' || event.turnId !== turnId || resolved.has(event.payload.approvalId) ? [] : [{
        ...event.payload, taskId, turnId, source: event.source, occurredAt: event.occurredAt,
      }]);
    },
    startAgentTurn: async (request) => {
      record('startAgentTurn', request);
      turnState = 'RUNNING';
      return previewTurn(request.taskId, turnState);
    },
    steerAgentTurn: async (request) => {
      record('steerAgentTurn', request);
      return {
        id: 'delivery-preview',
        taskId: FIXTURE_EVENTS[0].taskId,
        turnId: request.turnId,
        state: 'DELIVERED',
        failure: null,
        createdAt: '2026-08-20T10:00:03Z',
        updatedAt: '2026-08-20T10:00:03Z',
      };
    },
    queueAgentInput: async (request) => {
      record('queueAgentInput', request);
      if (request.expectedVersion !== previewVersion) {
        throw new Error(`Task version changed: ${request.taskId}`);
      }
      previewVersion += 1;
      const queued: AgentQueuedInputDto = {
        id: `queued-${previewQueue.length + 1}`,
        taskId: request.taskId,
        position: previewQueue.length + 1,
        state: 'QUEUED',
        preview: request.message,
        settings: {
          model: previewDefaults.model,
          effort: previewDefaults.effort,
          serviceTier: previewDefaults.serviceTier ?? null,
          permissionPolicy: previewDefaults.permissionPolicy,
          maximumRuntimeMillis: 3600000,
          attachments: [],
        },
        turnId: null,
        pauseReason: null,
        createdAt: '2026-08-20T10:00:04Z',
        updatedAt: '2026-08-20T10:00:04Z',
      };
      previewQueue.push(queued);
      return queued;
    },
    listAgentQueue: async (taskId, id) => {
      record('listAgentQueue', taskId, id);
      return [...previewQueue];
    },
    editAgentQueuedInput: async (taskId, id, queuedInputId) => {
      record('editAgentQueuedInput', taskId, id, queuedInputId);
      const index = previewQueue.findIndex(input => input.id === queuedInputId);
      if (index < 0) throw new Error('Queued input not found');
      const [input] = previewQueue.splice(index, 1);
      return { message: input.preview, settings: input.settings, attachments: [] };
    },
    removeAgentQueuedInput: async (taskId, id, queuedInputId) => {
      record('removeAgentQueuedInput', taskId, id, queuedInputId);
      const index = previewQueue.findIndex((queued) => queued.id === queuedInputId);
      if (index < 0) throw new Error(`queued input not found: ${queuedInputId}`);
      const [removed] = previewQueue.splice(index, 1);
      return { ...removed, state: removed.state === 'UNCERTAIN' ? 'ABANDONED' : 'REMOVED' };
    },
    steerAgentQueuedInput: async (taskId, id, queuedInputId, turnId) => {
      record('steerAgentQueuedInput', taskId, id, queuedInputId, turnId);
      const index = previewQueue.findIndex(input => input.id === queuedInputId);
      if (index < 0) throw new Error('Queued input not found');
      previewQueue.splice(index, 1);
      return { id: `steer-${queuedInputId}`, taskId, turnId, state: 'DELIVERED', failure: null,
        createdAt: '2026-08-20T10:00:03Z', updatedAt: '2026-08-20T10:00:03Z' };
    },
    resumeAgentQueue: async (taskId, id) => {
      record('resumeAgentQueue', taskId, id);
      previewQueue.shift();
      turnState = 'RUNNING';
      return previewTurn(taskId, turnState);
    },
    getAgentTurn: async (turnId) => {
      record('getAgentTurn', turnId);
      return previewTurn(FIXTURE_EVENTS[0].taskId, turnState);
    },
    cancelAgentTurn: async (turnId) => {
      record('cancelAgentTurn', turnId);
      turnState = 'CANCELLED';
      return previewTurn(FIXTURE_EVENTS[0].taskId, turnState);
    },
    resolveAgentApproval: async (approvalId, decision, scopedPermission) => {
      record('resolveAgentApproval', approvalId, decision, scopedPermission);
      return {
        approvalId,
        decision: decision === 'ALLOW_FOR_TASK' ? 'ALLOW' : decision,
        forTask: decision === 'ALLOW_FOR_TASK',
        scopedPermission: scopedPermission ?? null,
        resolvedAt: '2026-08-20T10:00:05Z',
        timedOut: false,
      };
    },
    completeAgentTask: async (taskId, id, notes) => {
      record('completeAgentTask', taskId, id, notes);
      return {
        taskId,
        summary: contracts.completion.summary as never,
        completedAt: '2026-08-20T10:05:00Z',
        deleteAfter: '2026-08-27T10:05:00Z',
      };
    },
    cleanupAgentTaskWorktree: async (taskId, id) => {
      record('cleanupAgentTaskWorktree', taskId, id);
      return {
        ...previewTask(id, taskId),
        state: 'COMPLETED',
        checkoutRemovedAt: '2026-08-20T10:05:01Z',
      };
    },
    pickAgentAttachments: async (taskId, id) => {
      record('pickAgentAttachments', taskId, id);
      return [{ id: 'attachment-preview', name: 'preview.png', mediaType: 'image/png', byteSize: 1024, width: 16, height: 16 }];
    },
    pasteAgentAttachments: async (taskId, id) => {
      record('pasteAgentAttachments', taskId, id);
      return [{ id: 'attachment-pasted-preview', name: 'pasted-image.png', mediaType: 'image/png', byteSize: 1024, width: 16, height: 16 }];
    },
    readAgentAttachment: async (taskId, id, attachmentId) => {
      record('readAgentAttachment', taskId, id, attachmentId);
      return { mediaType: 'image/png', bytes: new Uint8Array([137, 80, 78, 71]) };
    },
    removeAgentAttachment: async (taskId, id, attachmentId) => {
      record('removeAgentAttachment', taskId, id, attachmentId);
    },
    cancelAgentAutoMerge: async (taskId, id) => {
      record('cancelAgentAutoMerge', taskId, id);
      return { ...previewTask(id, taskId), autoMerge: { state: 'CANCELLED', mergeAt: null, message: 'Auto-merge was stopped' } };
    },
    markAgentTaskFailed: async (taskId, id, reason) => {
      record('markAgentTaskFailed', taskId, id, reason);
      return {
        ...previewTask(id, taskId),
        state: 'FAILED',
        terminalReason: reason,
        terminalAt: '2026-08-20T10:06:00Z',
        deleteAfter: '2026-08-27T10:06:00Z',
      };
    },
    getAgentRuntimeStopImpact: async (taskId, id) => {
      record('getAgentRuntimeStopImpact', taskId, id);
      return {
        taskId,
        generation: turnState === 'RUNNING' ? 'turn:preview' : `task:${previewVersion}`,
        activeTurnId: turnState === 'RUNNING' ? contracts.turn.id : null,
        liveProcess: true,
      };
    },
    stopAgentRuntime: async (taskId, id, generation) => {
      record('stopAgentRuntime', taskId, id, generation);
      if (turnState === 'RUNNING') turnState = 'INTERRUPTED';
      return { ...previewTask(id, taskId), defaults: previewDefaults, version: previewVersion };
    },
    subscribeAgentTask: async (taskId, after, listener, statusOnly = false) => {
      record('subscribeAgentTask', taskId, after);
      let stopped = false;
      const push = (message: AgentStreamMessage): void => {
        if (!stopped) listener(message);
      };
      push({ subscriptionId: 'preview', taskId, kind: 'state', state: 'connecting' });
      push({ subscriptionId: 'preview', taskId, kind: 'state', state: 'open' });
      for (const event of FIXTURE_EVENTS.filter(event => event.sequence > replayThrough)) {
        replayThrough = Math.max(replayThrough, event.sequence);
        push(statusOnly
          ? { subscriptionId: 'preview', taskId, kind: 'changed', sequence: event.sequence, turnId: event.turnId }
          : { subscriptionId: 'preview', taskId, kind: 'events', events: [event] });
      }
      return () => {
        stopped = true;
        record('unsubscribeAgentTask', taskId);
      };
    },
  };
}
