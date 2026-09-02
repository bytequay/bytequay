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
import type {
  AgentStreamMessage,
  AgentTaskDefaultsDto,
  AgentTaskDto,
  AgentTurnDto,
  Bridge,
  ModelSettingsDto,
} from '../types';

/** The slice of the bridge an agent screen uses, so a screen can be driven by a mock. */
export type AgentBridgePort = Pick<Bridge,
  | 'listAgentTasks'
  | 'createAgentTask'
  | 'getAgentTask'
  | 'readAgentEvents'
  | 'startAgentTurn'
  | 'getAgentTurn'
  | 'cancelAgentTurn'
  | 'resolveAgentApproval'
  | 'completeAgentTask'
  | 'subscribeAgentTask'
  | 'getModelSettings'
  | 'updateAgentTaskDefaults'
  | 'getEffectiveTurnSettings'>;

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

/** A catalog shaped like a probed Codex lane, so the preview exercises real selection code. */
const PREVIEW_CATALOG: ModelSettingsDto = {
  mainId: 'codex',
  providers: [{
    id: 'codex',
    label: 'Codex',
    readiness: 'READY',
    version: '0.151.0',
    diagnostic: '',
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
    title: eventFixture.createTaskRequest.title,
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

    listAgentTasks: async (id, limit) => {
      record('listAgentTasks', id, limit);
      return [previewTask(id, FIXTURE_EVENTS[0].taskId)];
    },
    createAgentTask: async (request) => {
      record('createAgentTask', request);
      return previewTask(request.workspaceId, FIXTURE_EVENTS[0].taskId);
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
    getEffectiveTurnSettings: async (taskId, id) => {
      record('getEffectiveTurnSettings', taskId, id);
      return {
        model: previewDefaults.model,
        effort: previewDefaults.effort,
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
        decision,
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
