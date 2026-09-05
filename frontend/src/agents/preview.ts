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

import contracts from './fixtures/agent-contracts-v1.json';
import eventFixture from './fixtures/agent-events-v2.json';
import { parseAgentEventSequence, type AgentEventEnvelope } from './events';
import type { AgentBridgePort } from './model';
import { NO_AGENT_CAPABILITIES } from './capabilities';
import type {
  AgentQueuedInputDto,
  AgentStreamMessage,
  AgentTaskDefaultsDto,
  AgentTaskDto,
  AgentTurnDto,
  ModelSettingsDto,
} from '../types';

export type { AgentBridgePort } from './model';

/** Every command the preview sent, so a test can assert the exact bridge calls. */
export interface PreviewCommand {
  name: string;
  args: unknown[];
}

export interface PreviewOptions {
  /** How the simulated stream misbehaves, so each case is exercised on purpose. */
  delivery?: 'ordered' | 'duplicate' | 'gap' | 'reconnect';
  /** Events replayed before the subscription starts; the rest arrive live. */
  replayThrough?: number;
}

const FIXTURE_EVENTS: AgentEventEnvelope[] = parseAgentEventSequence(eventFixture.events);

const PREVIEW_CAPABILITIES = {
  ...NO_AGENT_CAPABILITIES,
  sessionResume: true,
  structuredPlans: true,
  toolLifecycle: true,
  incrementalCommandOutput: true,
  interactiveApprovals: true,
  tokenUsage: true,
  steering: true,
  attachmentMediaTypes: ['image/png', 'image/jpeg', 'image/gif'],
};

/** A catalog shaped like a probed Codex lane, so the preview exercises real selection code. */
const PREVIEW_CATALOG: ModelSettingsDto = {
  mainId: 'codex',
  providers: [{
    id: 'codex',
    label: 'Codex',
    readiness: 'READY',
    version: '0.151.0',
    diagnostic: '',
    capabilities: PREVIEW_CAPABILITIES,
    steering: true,
    models: [
      { id: 'gpt-5.6-codex', name: '5.6 Terra', description: 'Balanced', efforts: ['medium', 'high'], defaultEffort: 'high' },
      { id: 'gpt-5.6-codex-mini', name: '5.6 Sol', description: 'Faster', efforts: ['medium'], defaultEffort: 'medium' },
    ],
    selectedModel: 'gpt-5.6-codex',
    selectedEffort: 'high',
    main: true,
  }],
  apiKeys: [],
  keyProviders: [],
};

export function previewTask(workspaceId: string, taskId: string): AgentTaskDto {
  return {
    ...(contracts.task as AgentTaskDto),
    id: taskId,
    workspaceId,
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
 * records what it was asked to do. Delivery can be made to misbehave on purpose, because ordered
 * delivery is the case least likely to be wrong.
 */
export function createPreviewBridge(
  workspaceId: string,
  options: PreviewOptions = {},
): AgentBridgePort & { commands: PreviewCommand[]; events: AgentEventEnvelope[] } {
  const commands: PreviewCommand[] = [];
  const record = (name: string, ...args: unknown[]): void => {
    commands.push({ name, args });
  };
  const replayThrough = options.replayThrough ?? 2;
  const delivery = options.delivery ?? 'ordered';
  let turnState = 'COMPLETED';
  let previewVersion = 0;
  const previewQueue: AgentQueuedInputDto[] = [];
  let previewDefaults: AgentTaskDefaultsDto = {
    model: 'gpt-5.6-codex',
    effort: 'high',
    permissionPolicy: 'AUTO',
  };

  const liveEvents = (): AgentEventEnvelope[] => {
    const live = FIXTURE_EVENTS.filter((event) => event.sequence > replayThrough);
    switch (delivery) {
      case 'duplicate':
        // Replay and live delivery overlap at the boundary.
        return [FIXTURE_EVENTS[replayThrough - 1], ...live];
      case 'gap':
        // The first live event is lost, so everything after it must wait.
        return live.slice(1).concat(live.slice(0, 1));
      default:
        return live;
    }
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

    listAgentTasks: async (id, limit) => {
      record('listAgentTasks', id, limit);
      return [previewTask(id, FIXTURE_EVENTS[0].taskId)];
    },
    createAgentTask: async (request) => {
      record('createAgentTask', request);
      return previewTask(request.workspaceId, request.taskId);
    },
    getAgentTask: async (taskId, id) => {
      record('getAgentTask', taskId, id);
      return { ...previewTask(id, taskId), defaults: previewDefaults, version: previewVersion };
    },
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
    listAgentTaskPullRequests: async (taskId, id) => {
      record('listAgentTaskPullRequests', taskId, id);
      return [];
    },
    renameAgentTask: async (taskId, id, expectedVersion, title) => {
      record('renameAgentTask', taskId, id, expectedVersion, title);
      if (expectedVersion !== previewVersion) {
        throw new Error(`Task version changed: ${taskId}`);
      }
      previewVersion += 1;
      return { ...previewTask(id, taskId), title, titleSource: 'USER', defaults: previewDefaults, version: previewVersion };
    },
    getEffectiveTurnSettings: async (taskId, id) => {
      record('getEffectiveTurnSettings', taskId, id);
      return {
        model: previewDefaults.model,
        effort: previewDefaults.effort,
        serviceTier: previewDefaults.serviceTier ?? null,
        permissionPolicy: previewDefaults.permissionPolicy,
        sandboxPolicy: 'WORKSPACE_WRITE',
        maximumRuntimeMillis: 3600000,
        attachments: [],
      };
    },
    readAgentEvents: async (taskId, after = 0, limit) => {
      record('readAgentEvents', taskId, after, limit);
      return FIXTURE_EVENTS.filter(
        (event) => event.sequence > after && event.sequence <= replayThrough,
      );
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
          sandboxPolicy: 'WORKSPACE_WRITE',
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
    removeAgentQueuedInput: async (taskId, id, queuedInputId) => {
      record('removeAgentQueuedInput', taskId, id, queuedInputId);
      const index = previewQueue.findIndex((queued) => queued.id === queuedInputId);
      if (index < 0) throw new Error(`queued input not found: ${queuedInputId}`);
      const [removed] = previewQueue.splice(index, 1);
      return { ...removed, state: removed.state === 'UNCERTAIN' ? 'ABANDONED' : 'REMOVED' };
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
    readAgentAttachment: async (taskId, id, attachmentId) => {
      record('readAgentAttachment', taskId, id, attachmentId);
      return { mediaType: 'image/png', bytes: new Uint8Array([137, 80, 78, 71]) };
    },
    removeAgentAttachment: async (taskId, id, attachmentId) => {
      record('removeAgentAttachment', taskId, id, attachmentId);
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
    subscribeAgentTask: async (taskId, after, listener) => {
      record('subscribeAgentTask', taskId, after);
      let stopped = false;
      const push = (message: AgentStreamMessage): void => {
        if (!stopped) listener(message);
      };
      push({ subscriptionId: 'preview', taskId, kind: 'state', state: 'connecting' });
      if (delivery === 'reconnect') {
        push({ subscriptionId: 'preview', taskId, kind: 'state', state: 'reconnecting', detail: 'simulated drop' });
      }
      push({ subscriptionId: 'preview', taskId, kind: 'state', state: 'open' });
      for (const event of liveEvents()) {
        push({ subscriptionId: 'preview', taskId, kind: 'events', events: [event] });
      }
      return () => {
        stopped = true;
        record('unsubscribeAgentTask', taskId);
      };
    },
  };
}
