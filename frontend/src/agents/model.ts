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

import type { AgentEventEnvelope } from './events';
import type { ApprovalBlock } from './transcript';
import type {
  AgentCapabilitiesDto,
  AgentAttachmentReferenceDto,
  AgentApprovalDecision,
  AgentAttachmentDto,
  AgentQueuedInputDto,
  AgentStreamConnectionState,
  AgentTaskDefaultsDto,
  AgentTaskDto,
  AgentTurnDto,
  AgentTurnSettingsDto,
  Bridge,
  CreateAgentTaskRequest,
  ModelOptionDto,
} from '../types';

/** The slice of the bridge an agent screen uses, so a screen can be driven by a test double. */
export type AgentBridgePort = Pick<Bridge,
  | 'listAgentTasks'
  | 'createAgentTask'
  | 'getAgentTask'
  | 'readAgentEvents'
  | 'startAgentTurn'
  | 'steerAgentTurn'
  | 'queueAgentInput'
  | 'listAgentQueue'
  | 'removeAgentQueuedInput'
  | 'resumeAgentQueue'
  | 'getAgentTurn'
  | 'cancelAgentTurn'
  | 'resolveAgentApproval'
  | 'completeAgentTask'
  | 'cleanupAgentTaskWorktree'
  | 'markAgentTaskFailed'
  | 'getAgentRuntimeStopImpact'
  | 'stopAgentRuntime'
  | 'pickAgentAttachments'
  | 'readAgentAttachment'
  | 'removeAgentAttachment'
  | 'subscribeAgentTask'
  | 'getModelSettings'
  | 'updateAgentTaskDefaults'
  | 'renameAgentTask'
  | 'listAgentTaskPullRequests'
  | 'getEffectiveTurnSettings'
  | 'refreshAgentProviderReadiness'>;

export interface AgentTaskState {
  task: AgentTaskDto | null;
  /** The latest turn, read from the server; the composer and status derive from it. */
  turn: AgentTurnDto | null;
  /** Every turn read so far, by id, so each message can show the settings it actually ran with. */
  turns: Record<string, AgentTurnDto>;
  /** Applied events in sequence order. */
  events: AgentEventEnvelope[];
  /** Highest sequence applied; what a subscription resumes from. */
  lastSequence: number;
  /** Events past a gap, held until the missing ones arrive. */
  pending: AgentEventEnvelope[];
  gap: boolean;
  connection: AgentStreamConnectionState;
  connectionDetail: string | null;
  composer: string;
  /** Images uploaded for the next message, by the references the backend issued. */
  attachments: AgentAttachmentDto[];
  /** True while a command is in flight, so the composer cannot send twice. */
  sending: boolean;
  /** What the next turn would run with, read from the server rather than assembled here. */
  effective: AgentTurnSettingsDto | null;
  /** The turn the newest applied events belong to, and whether its lifecycle has ended. */
  latestTurnId: string | null;
  latestTurnSettled: boolean;
  /** Bumped by every lifecycle event, so the screen re-reads the turn and task facts it changed. */
  lifecycleEpoch: number;
  /** The last failed command, shown until the next one; never a transcript row. */
  notice: string | null;
  /** What waits behind the current turn, read from the server whenever the task's lifecycle moves. */
  queue: AgentQueuedInputDto[];
  /** The immutable task lane's normalized feature shape. Unknown capabilities grant nothing. */
  capabilities: AgentCapabilitiesDto | null;
}

export type AgentTaskAction =
  | { type: 'task/loaded'; task: AgentTaskDto }
  | { type: 'turn/changed'; turn: AgentTurnDto }
  | { type: 'turn/loaded'; turn: AgentTurnDto }
  | { type: 'events/applied'; events: AgentEventEnvelope[] }
  | { type: 'connection/changed'; state: AgentStreamConnectionState; detail?: string }
  | { type: 'composer/changed'; text: string }
  | { type: 'attachments/added'; attachments: AgentAttachmentDto[] }
  | { type: 'attachments/removed'; attachmentId: string }
  | { type: 'attachments/cleared' }
  | { type: 'composer/sending'; sending: boolean }
  | { type: 'settings/loaded'; settings: AgentTurnSettingsDto }
  | { type: 'notice/changed'; notice: string | null }
  | { type: 'queue/loaded'; queue: AgentQueuedInputDto[] }
  | { type: 'queue/upserted'; input: AgentQueuedInputDto }
  | { type: 'queue/removed'; queuedInputId: string }
  | { type: 'capabilities/loaded'; capabilities: AgentCapabilitiesDto };

export function initialAgentTaskState(): AgentTaskState {
  return {
    task: null,
    turn: null,
    turns: {},
    events: [],
    lastSequence: 0,
    pending: [],
    gap: false,
    connection: 'connecting',
    connectionDetail: null,
    composer: '',
    attachments: [],
    sending: false,
    effective: null,
    latestTurnId: null,
    latestTurnSettled: true,
    lifecycleEpoch: 0,
    notice: null,
    queue: [],
    capabilities: null,
  };
}

export function agentTaskReducer(state: AgentTaskState, action: AgentTaskAction): AgentTaskState {
  switch (action.type) {
    case 'task/loaded':
      // Every task response carries a fresh version; holding a stale one conflicts on the next write.
      if (state.task?.id === action.task.id && action.task.version < state.task.version) return state;
      return { ...state, task: action.task };
    case 'settings/loaded':
      return { ...state, effective: action.settings };
    case 'turn/changed':
      if (state.turn?.id === action.turn.id && action.turn.version < state.turn.version) return state;
      return { ...state, turn: action.turn, turns: { ...state.turns, [action.turn.id]: action.turn } };
    case 'turn/loaded':
      // An older turn's durable facts; the latest turn is only ever set through turn/changed.
      if (state.turns[action.turn.id]?.version > action.turn.version) return state;
      return { ...state, turns: { ...state.turns, [action.turn.id]: action.turn } };
    case 'connection/changed':
      return { ...state, connection: action.state, connectionDetail: action.detail ?? null };
    case 'composer/changed':
      return { ...state, composer: action.text };
    case 'attachments/added':
      return { ...state, attachments: [...state.attachments, ...action.attachments] };
    case 'attachments/removed':
      return { ...state, attachments: state.attachments.filter((attachment) => attachment.id !== action.attachmentId) };
    case 'attachments/cleared':
      return { ...state, attachments: [] };
    case 'composer/sending':
      return { ...state, sending: action.sending };
    case 'notice/changed':
      return { ...state, notice: action.notice };
    case 'queue/loaded':
      return { ...state, queue: action.queue };
    case 'queue/upserted':
      return {
        ...state,
        queue: [...state.queue.filter((input) => input.id !== action.input.id), action.input]
          .sort((left, right) => left.position - right.position),
      };
    case 'queue/removed':
      return { ...state, queue: state.queue.filter((input) => input.id !== action.queuedInputId) };
    case 'capabilities/loaded':
      return { ...state, capabilities: action.capabilities };
    case 'events/applied':
      return applyEvents(state, action.events);
    default:
      return state;
  }
}

/**
 * Applies events in sequence order, tolerating the two things a stream really does.
 *
 * Replay and live delivery overlap, so an event at or below the cursor is a duplicate and is
 * dropped. An event beyond the next expected sequence means something is missing, so it and
 * everything after it wait rather than projecting a transcript with a hole in it: showing later
 * work before earlier work is worse than showing nothing yet.
 */
function applyEvents(state: AgentTaskState, incoming: AgentEventEnvelope[]): AgentTaskState {
  let lastSequence = state.lastSequence;
  let latestTurnId = state.latestTurnId;
  let latestTurnSettled = state.latestTurnSettled;
  let lifecycleEpoch = state.lifecycleEpoch;
  const applied = [...state.events];
  const seenTurnIds = new Set(applied.flatMap((event) => event.turnId === null ? [] : [event.turnId]));
  const queue = [...state.pending, ...incoming]
    .filter((event) => event.sequence > lastSequence)
    .sort((left, right) => left.sequence - right.sequence);

  const pending: AgentEventEnvelope[] = [];
  for (const event of queue) {
    if (event.sequence <= lastSequence) {
      // A duplicate that arrived inside this batch.
      continue;
    }
    if (pending.length > 0 || event.sequence !== lastSequence + 1) {
      pending.push(event);
      continue;
    }
    applied.push(event);
    lastSequence = event.sequence;
    const introducesTurn = event.turnId !== null && !seenTurnIds.has(event.turnId);
    if (event.turnId !== null) seenTurnIds.add(event.turnId);
    if (introducesTurn && event.turnId !== latestTurnId) {
      latestTurnId = event.turnId;
      latestTurnSettled = false;
    }
    if (event.type === 'lifecycle') {
      lifecycleEpoch++;
      const phase = event.payload.phase;
      if (event.turnId === latestTurnId && (phase === 'TURN_COMPLETED' || phase === 'INTERRUPTED')) {
        latestTurnSettled = true;
      }
    }
  }

  return {
    ...state,
    events: applied,
    lastSequence,
    pending,
    gap: pending.length > 0,
    latestTurnId,
    latestTurnSettled,
    lifecycleEpoch,
  };
}



/** A provider decision this product is willing to answer with, and what it resolves to. */
export interface AgentApprovalChoice {
  offered: string;
  decision: AgentApprovalDecision;
  label: string;
}

const PRODUCT_DECISIONS: Record<string, { decision: AgentApprovalDecision; label: string }> = {
  ALLOW_ONCE: { decision: 'ALLOW', label: 'Allow once' },
  ALLOW_FOR_TASK: { decision: 'ALLOW_FOR_TASK', label: 'Allow for this task' },
  DENY: { decision: 'DENY', label: 'Deny' },
};

/**
 * The decisions the product may offer for one approval.
 *
 * Fails closed twice over: a provider decision this product has no answer for is dropped rather
 * than sent as a guess, and an approval left with nothing offerable is shown but unanswerable
 * instead of being collapsed into an allow/deny the provider never offered.
 */
export function approvalDecisions(row: Pick<ApprovalBlock, 'actionable' | 'outcome' | 'decisions'>): AgentApprovalChoice[] {
  if (!row.actionable || row.outcome !== null) {
    return [];
  }
  return row.decisions.flatMap((offered) => {
    const permitted = PRODUCT_DECISIONS[offered];
    return permitted === undefined ? [] : [{ offered, ...permitted }];
  });
}

type AgentTaskSessionBridge = Pick<Bridge, 'getAgentTask' | 'readAgentEvents' | 'subscribeAgentTask'>;

/**
 * Loads a task, replays its history, then subscribes past the highest sequence replay delivered.
 *
 * Replaying before subscribing is the only ordering that cannot lose an event, because the
 * subscription resumes from what replay actually delivered rather than from a guess. `isActive`
 * exists because the screen can be closed while a request is still in flight, and a stream nobody
 * is reading has to be stopped rather than left running.
 */
export async function startAgentTaskSession(
  bridge: AgentTaskSessionBridge,
  taskId: string,
  workspaceId: string,
  dispatch: (action: AgentTaskAction) => void,
  isActive: () => boolean = () => true,
): Promise<() => void> {
  const noop = (): void => {};
  const task = await bridge.getAgentTask(taskId, workspaceId);
  if (!isActive()) return noop;
  dispatch({ type: 'task/loaded', task });

  const replayed = await bridge.readAgentEvents(taskId, 0);
  if (!isActive()) return noop;
  dispatch({ type: 'events/applied', events: replayed });
  if (task.state !== 'OPEN') {
    // The backend would only replay and close; the history just read is everything there is.
    dispatch({ type: 'connection/changed', state: 'open' });
    return noop;
  }

  const after = replayed.length === 0 ? 0 : replayed[replayed.length - 1].sequence;
  const stop = await bridge.subscribeAgentTask(taskId, after, (message) => {
    if (!isActive()) return;
    if (message.kind === 'events') {
      dispatch({ type: 'events/applied', events: message.events });
    }
    else {
      dispatch({ type: 'connection/changed', state: message.state, detail: message.detail });
    }
  });
  if (!isActive()) {
    stop();
    return noop;
  }
  return stop;
}

/**
 * What the task is doing, derived from durable facts rather than owned by the renderer.
 *
 * The backend keeps only OPEN, COMPLETED and FAILED. Running, idle, closing and recovery are read
 * from the turn and the task's mutation claim: a claim that outlives its turn means the provider
 * could not be shown to have stopped writing, and nothing may touch the checkout until it is.
 */
export type AgentRuntimeStatus = 'loading' | 'idle' | 'running' | 'closing' | 'recovery' | 'completed' | 'failed';

export function runtimeStatus(state: AgentTaskState): AgentRuntimeStatus {
  const task = state.task;
  // A held claim means recovery only once the history that would show a live turn has been read.
  if (task === null || state.connection === 'connecting') return 'loading';
  if (task.state === 'COMPLETED') return 'completed';
  if (task.state === 'FAILED') return 'failed';
  // Until the durable turn has been read, the events say whether one is still in flight.
  const turnActive = state.turn === null
    ? state.latestTurnId !== null && !state.latestTurnSettled
    : state.turn.state === 'RUNNING' || state.turn.state === 'ACCEPTED';
  const claim = task.mutationClaim ?? null;
  if (claim === null) return turnActive ? 'running' : 'idle';
  if (claim.kind === 'COMPLETION') return 'closing';
  if (claim.kind === 'RECOVERY') return 'recovery';
  return turnActive ? 'running' : 'recovery';
}

/** Whether the task is idle, which is the only time sending or completing is offered. */
export function isIdle(state: AgentTaskState): boolean {
  return runtimeStatus(state) === 'idle';
}

/**
 * Whether the selected model advertises image input. A catalog entry recorded before modalities
 * were carried says nothing, and nothing is not permission.
 */
export function acceptsImages(
  models: ModelOptionDto[],
  modelId: string | null | undefined,
  capabilities: AgentCapabilitiesDto | null,
): boolean {
  if (modelId === null || modelId === undefined) return false;
  const providerAcceptsImages = capabilities?.attachmentMediaTypes.some((mediaType) => mediaType.startsWith('image/')) ?? false;
  return providerAcceptsImages
    && (models.find((model) => model.id === modelId)?.inputModalities?.includes('image') ?? false);
}

/** Whether Send may fire: idle, connected, not already sending, and with something to say. */
export function canSend(state: AgentTaskState): boolean {
  return isIdle(state)
    && !state.sending
    && state.composer.trim().length > 0
    && state.connection !== 'failed';
}

/**
 * Whether Steer may fire: a turn is running on a lane that steers, the screen has its id, and
 * there is something to say. A queue that is starting its next turn is not one to steer.
 */
export function canSteer(state: AgentTaskState): boolean {
  return runtimeStatus(state) === 'running'
    && state.capabilities?.steering === true
    && state.turn !== null
    && state.turn.state === 'RUNNING'
    && !state.sending
    && state.composer.trim().length > 0
    && state.connection !== 'failed';
}

/** Whether Run next may fire: something to say while a turn is running. */
export function canQueue(state: AgentTaskState): boolean {
  return runtimeStatus(state) === 'running'
    && state.task !== null
    && !state.sending
    && state.composer.trim().length > 0
    && state.connection !== 'failed';
}

export type DraftSubmissionTracker = {
  keyFor: (kind: 'send' | 'retry' | 'steer' | 'queue', targetId: string, taskId: string, message: string, attachmentIds: string[], defaults: AgentTaskDefaultsDto | null) => string;
  draftChanged: (taskId: string, message: string, attachmentIds: string[], defaults: AgentTaskDefaultsDto | null) => void;
  settled: (idempotencyKey: string) => void;
};

export type TaskCreationTracker = {
  requestFor: (
    workspaceId: string,
    message: string,
    laneId: string,
    defaults: AgentTaskDefaultsDto,
  ) => CreateAgentTaskRequest;
  settled: (taskId: string) => void;
};

/** Keeps both halves of create-and-start stable until the composite request answers durably. */
export function createTaskCreationTracker(createKey: () => string = () => globalThis.crypto.randomUUID()): TaskCreationTracker {
  let retained: { signature: string; request: CreateAgentTaskRequest } | null = null;
  return {
    requestFor(workspaceId, message, laneId, defaults) {
      const signature = JSON.stringify([
        workspaceId,
        message.trim(),
        laneId,
        defaults.model,
        defaults.effort,
        defaults.permissionPolicy,
        defaults.serviceTier ?? null,
      ]);
      if (retained?.signature !== signature) {
        retained = {
          signature,
          request: {
            taskId: createKey(),
            workspaceId,
            message: message.trim(),
            laneId,
            defaults,
            idempotencyKey: createKey(),
          },
        };
      }
      return retained.request;
    },
    settled(taskId) {
      if (retained?.request.taskId === taskId) retained = null;
    },
  };
}

/** Retains one random key until its exact draft changes or the server gives a durable response. */
export function createDraftSubmissionTracker(createKey: () => string = () => globalThis.crypto.randomUUID()): DraftSubmissionTracker {
  let retained: { kind: 'send' | 'retry' | 'steer' | 'queue'; draft: string; request: string; key: string } | null = null;
  const signature = (
    kind: 'send' | 'retry' | 'steer' | 'queue',
    taskId: string,
    message: string,
    attachmentIds: string[],
    defaults: AgentTaskDefaultsDto | null,
  ): string => JSON.stringify([
    taskId,
    message.trim(),
    attachmentIds,
    ...(kind === 'steer' ? [] : [defaults?.model ?? null, defaults?.effort ?? null, defaults?.permissionPolicy ?? null, defaults?.serviceTier ?? null]),
  ]);
  return {
    keyFor(kind, targetId, taskId, message, attachmentIds, defaults) {
      const draft = signature(kind, taskId, message, attachmentIds, defaults);
      const request = JSON.stringify([kind, targetId, draft]);
      if (retained?.request !== request) retained = { kind, draft, request, key: createKey() };
      return retained.key;
    },
    draftChanged(taskId, message, attachmentIds, defaults) {
      if (retained !== null && retained.draft !== signature(retained.kind, taskId, message, attachmentIds, defaults)) retained = null;
    },
    settled(idempotencyKey) {
      if (retained?.key === idempotencyKey) retained = null;
    },
  };
}

/** Complete must serialize only once every safely resumable queue row is gone. */
export function canComplete(state: AgentTaskState): boolean {
  return isIdle(state) && !state.queue.some((input) => input.state === 'QUEUED' || input.state === 'PAUSED' || input.state === 'STARTING');
}

/** Confirms the one completion case that intentionally abandons a possibly delivered input. */
export function confirmCompletion(queue: AgentQueuedInputDto[], confirm: (message: string) => boolean): boolean {
  if (!queue.some((input) => input.state === 'UNCERTAIN')) return true;
  return confirm('Complete this task with an uncertain queued message? It will be marked abandoned and will never be retried.');
}

/**
 * Whether the provider may or may not have received a turn's input.
 *
 * Delivery started but was never acknowledged, so resending could run the message twice; such a
 * turn is never retried by a shortcut. The core explicitly persists the narrower case where it
 * proved the provider call never happened.
 */
export function deliveryUncertain(turn: AgentTurnDto): boolean {
  return turn.state !== 'COMPLETED'
    && turn.state !== 'RUNNING'
    && turn.state !== 'ACCEPTED'
    && turn.startedAt !== null
    && !turn.delivered
    && !turn.knownUnsent;
}

/**
 * Turns the transcript names but the screen has not read yet.
 *
 * A message shows the settings its turn actually ran with, which live on the durable turn rather
 * than in the event, so every turn a user message belongs to is read once.
 */
export function unloadedTurnIds(state: AgentTaskState): string[] {
  const ids: string[] = [];
  for (const event of state.events) {
    if (event.type === 'user_message' && event.turnId !== null && !(event.turnId in state.turns) && !ids.includes(event.turnId)) {
      ids.push(event.turnId);
    }
  }
  return ids;
}

export type RetryableInput = { text: string; attachments: AgentAttachmentReferenceDto[] };

/** The durable input a Retry would resend, only after its exact turn is quiescent and unambiguous. */
export function retryableInput(state: AgentTaskState): RetryableInput | null {
  if (!isIdle(state)
    || state.turn === null
    || state.turn.failureKind !== 'AUTHENTICATION_REQUIRED'
    || state.turn.state === 'COMPLETED'
    || deliveryUncertain(state.turn)) {
    return null;
  }
  for (let index = state.events.length - 1; index >= 0; index--) {
    const event = state.events[index];
    if (event.type === 'user_message'
      && event.turnId === state.turn.id
      && event.payload.deliveryKind !== 'STEER') {
      return { text: event.payload.text, attachments: event.payload.attachments };
    }
  }
  return null;
}
