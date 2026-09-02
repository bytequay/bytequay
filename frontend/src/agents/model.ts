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
import type {
  AgentApprovalDecision,
  AgentStreamConnectionState,
  AgentTaskDto,
  AgentTurnDto,
  AgentTurnSettingsDto,
  Bridge,
} from '../types';

/** One entry in the transcript, derived from events and never from local guesses. */
export type AgentRow =
  | { kind: 'user'; id: string; text: string; delivery: string; attachments: string[] }
  | { kind: 'assistant'; id: string; text: string; final: boolean; messageKind: string }
  | { kind: 'plan'; id: string; items: Array<{ text: string; state: string }> }
  | { kind: 'command'; id: string; argv: string[]; state: string; output: string | null; exitCode: number | null; truncated: boolean }
  | { kind: 'file'; id: string; path: string; change: string; state: string; diff: string | null }
  | { kind: 'tool'; id: string; name: string; state: string; summary: string }
  | { kind: 'approval'; id: string; approvalId: string; title: string; detail: string; approvalKind: string; scope: string[]; agent: string; decisions: string[]; actionable: boolean; outcome: string | null }
  | { kind: 'usage'; id: string; inputTokens: number; cachedInputTokens: number; outputTokens: number; cost: number | null; semantics: string }
  | { kind: 'lifecycle'; id: string; phase: string; message: string }
  | { kind: 'error'; id: string; code: string; message: string; retryable: boolean };

export interface AgentTaskState {
  task: AgentTaskDto | null;
  turn: AgentTurnDto | null;
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
  /** True while a command is in flight, so the composer cannot send twice. */
  sending: boolean;
  /** What the next turn would run with, read from the server rather than assembled here. */
  effective: AgentTurnSettingsDto | null;
}

export type AgentTaskAction =
  | { type: 'task/loaded'; task: AgentTaskDto }
  | { type: 'turn/changed'; turn: AgentTurnDto }
  | { type: 'events/applied'; events: AgentEventEnvelope[] }
  | { type: 'connection/changed'; state: AgentStreamConnectionState; detail?: string }
  | { type: 'composer/changed'; text: string }
  | { type: 'composer/sending'; sending: boolean }
  | { type: 'settings/loaded'; settings: AgentTurnSettingsDto };

export function initialAgentTaskState(): AgentTaskState {
  return {
    task: null,
    turn: null,
    events: [],
    lastSequence: 0,
    pending: [],
    gap: false,
    connection: 'connecting',
    connectionDetail: null,
    composer: '',
    sending: false,
    effective: null,
  };
}

export function agentTaskReducer(state: AgentTaskState, action: AgentTaskAction): AgentTaskState {
  switch (action.type) {
    case 'task/loaded':
      // Every task response carries a fresh version; holding a stale one conflicts on the next write.
      return { ...state, task: action.task };
    case 'settings/loaded':
      return { ...state, effective: action.settings };
    case 'turn/changed':
      return { ...state, turn: action.turn };
    case 'connection/changed':
      return { ...state, connection: action.state, connectionDetail: action.detail ?? null };
    case 'composer/changed':
      return { ...state, composer: action.text };
    case 'composer/sending':
      return { ...state, sending: action.sending };
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
  const applied = [...state.events];
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
  }

  return {
    ...state,
    events: applied,
    lastSequence,
    pending,
    gap: pending.length > 0,
  };
}

/**
 * Which agent an event came from.
 *
 * A subagent's approval must not read as the root agent's, because the two ask for different work.
 * The logical path names the child when the provider supplies one; a thread with no parent is root.
 */
function agentName(source: AgentEventEnvelope['source']): string {
  if (source.logicalAgentPath !== null && source.logicalAgentPath !== '') {
    return source.logicalAgentPath;
  }
  return source.parentProviderThreadId === null ? 'root agent' : 'subagent';
}

/**
 * Projects applied events into transcript rows.
 *
 * A final assistant message replaces the partial one it completes rather than appending, so the
 * transcript shows the answer once. An approval's outcome is folded into the request it answers,
 * so a resolved approval reads as one settled row instead of two.
 */
export function projectAgentRows(events: AgentEventEnvelope[]): AgentRow[] {
  const rows: AgentRow[] = [];
  const assistantByTurn = new Map<string, number>();
  const approvalIndex = new Map<string, number>();

  for (const event of events) {
    const id = event.eventId;
    switch (event.type) {
      case 'user_message':
        rows.push({
          kind: 'user',
          id,
          text: event.payload.text,
          delivery: event.payload.deliveryKind,
          attachments: event.payload.attachments.map((attachment) => attachment.name),
        });
        break;
      case 'assistant_message': {
        const key = `${event.turnId ?? ''}:${event.payload.messageKind}`;
        const existing = assistantByTurn.get(key);
        const row: AgentRow = {
          kind: 'assistant',
          id,
          text: event.payload.text,
          final: event.payload.phase === 'FINAL',
          messageKind: event.payload.messageKind,
        };
        if (existing !== undefined && !(rows[existing] as { final: boolean }).final) {
          // The authoritative text replaces what was streamed toward it.
          rows[existing] = row;
        } else {
          assistantByTurn.set(key, rows.length);
          rows.push(row);
        }
        break;
      }
      case 'plan_updated':
        rows.push({ kind: 'plan', id, items: event.payload.items.map((item) => ({ ...item })) });
        break;
      case 'command':
        rows.push({
          kind: 'command',
          id,
          argv: event.payload.argv,
          state: event.payload.state,
          output: event.payload.output,
          exitCode: event.payload.exitCode,
          truncated: event.payload.truncated,
        });
        break;
      case 'file_change':
        rows.push({
          kind: 'file',
          id,
          path: event.payload.relativePath,
          change: event.payload.change,
          state: event.payload.state,
          diff: event.payload.diff,
        });
        break;
      case 'tool':
        rows.push({
          kind: 'tool',
          id,
          name: event.payload.name,
          state: event.payload.state,
          summary: event.payload.summary,
        });
        break;
      case 'approval_requested':
        approvalIndex.set(event.payload.approvalId, rows.length);
        rows.push({
          kind: 'approval',
          id,
          approvalId: event.payload.approvalId,
          title: event.payload.title,
          detail: event.payload.detail,
          approvalKind: event.payload.approvalKind,
          scope: [...event.payload.scope],
          agent: agentName(event.source),
          decisions: [...event.payload.availableDecisions],
          actionable: event.payload.actionable,
          outcome: null,
        });
        break;
      case 'approval_resolved': {
        const at = approvalIndex.get(event.payload.approvalId);
        if (at !== undefined) {
          const row = rows[at] as Extract<AgentRow, { kind: 'approval' }>;
          rows[at] = { ...row, outcome: event.payload.outcome, actionable: false };
        }
        break;
      }
      case 'usage':
        rows.push({
          kind: 'usage',
          id,
          inputTokens: event.payload.inputTokens,
          cachedInputTokens: event.payload.cachedInputTokens,
          outputTokens: event.payload.outputTokens,
          cost: event.payload.cost,
          semantics: event.payload.semantics,
        });
        break;
      case 'lifecycle':
        rows.push({ kind: 'lifecycle', id, phase: event.payload.phase, message: event.payload.message });
        break;
      case 'error':
        rows.push({
          kind: 'error',
          id,
          code: event.payload.code,
          message: event.payload.message,
          retryable: event.payload.retryable,
        });
        break;
      default:
        break;
    }
  }
  return rows;
}

/** A provider decision this product is willing to answer with, and what it resolves to. */
export interface AgentApprovalChoice {
  offered: string;
  decision: AgentApprovalDecision;
  label: string;
}

const PRODUCT_DECISIONS: Record<string, { decision: AgentApprovalDecision; label: string }> = {
  ALLOW_ONCE: { decision: 'ALLOW', label: 'Allow once' },
  DENY: { decision: 'DENY', label: 'Deny' },
};

/**
 * The decisions the product may offer for one approval.
 *
 * Fails closed twice over: a provider decision this product has no answer for is dropped rather
 * than sent as a guess, and an approval left with nothing offerable is shown but unanswerable
 * instead of being collapsed into an allow/deny the provider never offered.
 */
export function approvalDecisions(row: Extract<AgentRow, { kind: 'approval' }>): AgentApprovalChoice[] {
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
 * The key a retried send reuses so the backend returns the original turn.
 *
 * The applied sequence identifies the transcript position the send was composed against, which is
 * stable across a retry of the same send but differs once the transcript has moved on.
 */
export function agentIdempotencyKey(taskId: string, lastSequence: number): string {
  return `${taskId}:${lastSequence}`;
}

/** Whether the task is idle, which is the only time completing it is offered. */
export function isIdle(state: AgentTaskState): boolean {
  if (state.turn === null) {
    return state.task !== null;
  }
  return state.turn.state !== 'RUNNING' && state.turn.state !== 'ACCEPTED';
}

/** Whether Send may fire: idle, connected, not already sending, and with something to say. */
export function canSend(state: AgentTaskState): boolean {
  return isIdle(state)
    && !state.sending
    && state.composer.trim().length > 0
    && state.connection !== 'failed';
}
