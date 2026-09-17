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

import { describe, expect, it, vi } from 'vitest';
import fixture from './fixtures/agent-events-v2.json';
import { parseAgentEventSequence, type AgentEventEnvelope } from './events';
import {
  acceptsImages,
  agentTaskReducer,
  approvalDecisions,
  canComplete,
  canQueue,
  canSend,
  canSteer,
  confirmCompletion,
  createDraftSubmissionTracker,
  createTaskCreationTracker,
  deliveryUncertain,
  initialAgentTaskState,
  historyBefore,
  historyAfter,
  isIdle,
  readRecentAgentHistory,
  retryableInput,
  runtimeStatus,
  unloadedTurnIds,
  type AgentTaskState,
} from './model';
import { projectTranscript, type ApprovalBlock } from './transcript';
import { NO_AGENT_CAPABILITIES } from './capabilities';
import type { AgentHistoryPageDto, AgentQueuedInputDto, AgentTaskDto, AgentTurnDto, ModelOptionDto } from '../types';

const all = parseAgentEventSequence(fixture.events);

it('retains the newest development progress across offscreen history, watermarks and replay', () => {
  const progress: AgentEventEnvelope = { ...all[0], type: 'lifecycle', turnId: null, sequence: 50,
    payload: { phase: 'DEVELOPMENT_AUTOMATION_UPDATED', message: 'Waiting for PR checks: required external build is pending' } };
  let state = agentTaskReducer({ ...initialAgentTaskState(), latestTurnId: 'source-turn' }, { type: 'live/changed', sequence: 100, turnId: 'source-turn' });
  state = agentTaskReducer(state, { type: 'history/loaded', direction: 'latest', page: {
    events: [progress], beforeCursor: '40', afterCursor: null, latestSequence: 100,
  } });
  expect(state.developmentProgress).toEqual({ sequence: 50, message: progress.payload.message });
  const newest = { ...progress, sequence: 101, payload: { ...progress.payload, message: 'Synchronizing source worktree' } };
  state = agentTaskReducer(state, { type: 'events/observed', events: [newest] });
  expect(state.events).not.toContainEqual(newest);
  expect(state.developmentProgress).toEqual({ sequence: 101, message: 'Synchronizing source worktree' });
  state = agentTaskReducer(state, { type: 'history/loaded', direction: 'older', page: {
    events: [{ ...progress, sequence: 10 }], beforeCursor: null, afterCursor: '40', latestSequence: 100,
  } });
  state = agentTaskReducer(state, { type: 'events/observed', events: [progress] });
  expect(state.developmentProgress?.message).toBe('Synchronizing source worktree');
  state = agentTaskReducer(state, { type: 'events/observed', events: [{ ...newest, sequence: 102, payload: { ...newest.payload, message: 'x'.repeat(1000) } }] });
  expect(state.developmentProgress?.message).toHaveLength(500);
});

it('new root turns and released markers prevent historical delivery progress from resurfacing', () => {
  const progress: AgentEventEnvelope = { ...all[0], type: 'lifecycle', turnId: null, sequence: 10,
    payload: { phase: 'DEVELOPMENT_AUTOMATION_UPDATED', message: 'Waiting for PR checks: old build' } };
  let state = agentTaskReducer(initialAgentTaskState(), { type: 'events/observed', events: [progress] });
  const started: AgentEventEnvelope = { ...progress, sequence: 11, turnId: 'next-turn', payload: { phase: 'TURN_STARTED', message: 'Started' } };
  state = agentTaskReducer(state, { type: 'events/observed', events: [started] });
  expect(state.developmentProgress).toEqual({ sequence: 11, message: '' });
  state = agentTaskReducer(state, { type: 'history/loaded', direction: 'older', page: {
    events: [progress], beforeCursor: null, afterCursor: '11', latestSequence: 11,
  } });
  expect(state.developmentProgress?.message).toBe('');
  state = agentTaskReducer(state, { type: 'events/observed', events: [{ ...progress, sequence: 12 }] });
  state = agentTaskReducer(state, { type: 'live/changed', sequence: 13, turnId: 'another-turn' });
  expect(state.developmentProgress).toEqual({ sequence: 13, message: '' });
  state = agentTaskReducer(state, { type: 'events/observed', events: [{ ...progress, sequence: 14,
    payload: { ...progress.payload, message: 'Development automation settled' } }] });
  expect(state.developmentProgress).toEqual({ sequence: 14, message: '' });
  state = agentTaskReducer(state, { type: 'events/observed', events: [{ ...progress, sequence: 15 }] });
  state = agentTaskReducer(state, { type: 'task/loaded', task: { ...openTask(), developmentContinuationUnfinished: false } });
  expect(state.developmentProgress).toEqual({ sequence: 15, message: '' });
});

it('retains the stopped automation reason after release and clears it for a new user turn', () => {
  const stopped: AgentEventEnvelope = { ...all[0], type: 'lifecycle', turnId: null, sequence: 20,
    payload: { phase: 'DEVELOPMENT_AUTOMATION_UPDATED', message: 'Automation stopped. Source synchronization failed; your files are retained.' } };
  let state = agentTaskReducer(initialAgentTaskState(), { type: 'history/loaded', direction: 'latest', page: {
    events: [stopped], beforeCursor: null, afterCursor: null, latestSequence: 20,
  } });
  state = agentTaskReducer(state, { type: 'task/loaded', task: { ...openTask(), developmentContinuationUnfinished: false } });
  expect(state.developmentProgress).toEqual({ sequence: 20, message: stopped.payload.message });
  state = agentTaskReducer(state, { type: 'events/observed', events: [{ ...stopped, sequence: 21, turnId: 'new-user-turn',
    payload: { phase: 'TURN_STARTED', message: 'Started' } }] });
  expect(state.developmentProgress).toEqual({ sequence: 21, message: '' });
  state = agentTaskReducer(state, { type: 'events/observed', events: [stopped] });
  expect(state.developmentProgress?.message).toBe('');
});

function apply(state: AgentTaskState, events: AgentEventEnvelope[]): AgentTaskState {
  return agentTaskReducer(state, { type: 'events/applied', events });
}

function stream(...sequences: number[]): AgentEventEnvelope[] {
  return sequences.map((sequence) => {
    const found = all.find((event) => event.sequence === sequence);
    if (!found) throw new Error(`fixture has no sequence ${sequence}`);
    return found;
  });
}

function turn(state: string, extra: Partial<AgentTurnDto> = {}): AgentTurnDto {
  return {
    id: 'turn-1',
    taskId: 'task-1',
    state,
    version: 1,
    createdAt: '2026-08-20T10:00:00Z',
    startedAt: null,
    endedAt: null,
    cancellationRequestedAt: null,
    delivered: true,
    knownUnsent: false,
    settings: null,
    terminalReason: null,
    ...extra,
    metrics: {
      inputTokens: 0,
      cachedInputTokens: 0,
      outputTokens: 0,
      cost: null,
      elapsed: 'PT0S',
    },
  };
}

/** An open task with only the facts the status derivation reads. */
function openTask(claim: AgentTaskDto['mutationClaim'] = null): AgentTaskDto {
  return { id: 'task-1', state: 'OPEN', mutationClaim: claim } as AgentTaskDto;
}

function queued(id: string, state = 'QUEUED', preview = 'later'): AgentQueuedInputDto {
  return {
    id,
    taskId: 'task-1',
    position: Number(id.replace(/\D/g, '')),
    state,
    preview,
    settings: { model: 'm', effort: null, permissionPolicy: 'AUTO', maximumRuntimeMillis: 1000, attachments: [] },
    turnId: null,
    pauseReason: null,
    createdAt: '2026-08-20T10:00:02Z',
    updatedAt: '2026-08-20T10:00:02Z',
  };
}

describe('applying events', () => {
  it('never lets an older task or turn response overwrite a newer mutation response', () => {
    const newerTask = { ...openTask(), version: 4, title: 'Renamed' };
    const olderTask = { ...newerTask, version: 3, title: 'Old title' };
    const newerTurn = turn('RUNNING', { version: 5 });
    const olderTurn = { ...newerTurn, version: 4, state: 'ACCEPTED' };

    let state = agentTaskReducer(initialAgentTaskState(), { type: 'task/loaded', task: newerTask });
    state = agentTaskReducer(state, { type: 'task/loaded', task: olderTask });
    state = agentTaskReducer(state, { type: 'turn/changed', turn: newerTurn });
    state = agentTaskReducer(state, { type: 'turn/changed', turn: olderTurn });

    expect(state.task).toEqual(newerTask);
    expect(state.turn).toEqual(newerTurn);
  });

  it('applies a replayed page then continues live from the highest applied sequence', () => {
    const replayed = apply(initialAgentTaskState(), stream(1, 2));
    const live = apply(replayed, stream(3));

    expect(live.lastSequence).toBe(3);
    expect(live.events.map((event) => event.sequence)).toEqual([1, 2, 3]);
    expect(live.gap).toBe(false);
  });

  it('drops an event the replay already delivered', () => {
    const replayed = apply(initialAgentTaskState(), stream(1, 2));

    // Replay and live delivery overlap; re-applying 2 must not duplicate it.
    const overlapped = apply(replayed, stream(2, 3));

    expect(overlapped.events.map((event) => event.sequence)).toEqual([1, 2, 3]);
    expect(overlapped.lastSequence).toBe(3);
  });

  it('holds events past a gap until the missing one arrives', () => {
    const started = apply(initialAgentTaskState(), stream(1));

    const gapped = apply(started, stream(3));

    // Showing later work before earlier work is worse than showing nothing yet.
    expect(gapped.gap).toBe(true);
    expect(gapped.lastSequence).toBe(1);
    expect(gapped.events.map((event) => event.sequence)).toEqual([1]);

    const filled = apply(gapped, stream(2));

    expect(filled.gap).toBe(false);
    expect(filled.events.map((event) => event.sequence)).toEqual([1, 2, 3]);
    expect(filled.lastSequence).toBe(3);
  });
});

describe('sending a message', () => {
  const page = (events: AgentEventEnvelope[]): AgentHistoryPageDto => ({ events, beforeCursor: null, afterCursor: null, latestSequence: events.at(-1)?.sequence ?? 0 });

  it('shows the sent message until a user message after its send point arrives', () => {
    let state = agentTaskReducer(apply(initialAgentTaskState(), stream(1, 2)), { type: 'message/sent', text: 'Next step' });
    expect(state.pendingMessage).toEqual({ text: 'Next step', attachments: [], after: 2 });

    // The earlier user message at sequence 1 is not the one just sent.
    state = agentTaskReducer(state, { type: 'history/loaded', direction: 'latest', page: page(stream(1, 2)) });
    expect(state.pendingMessage).not.toBeNull();

    const durable: AgentEventEnvelope = { ...all[0], eventId: 'durable-message', sequence: 3, turnId: 'next-turn' };
    state = agentTaskReducer(state, { type: 'history/loaded', direction: 'latest', page: page([...stream(1, 2), durable]) });
    expect(state.pendingMessage).toBeNull();
  });

  it('drops the sent message when the send fails', () => {
    const sent = agentTaskReducer(initialAgentTaskState(), { type: 'message/sent', text: 'Next step' });

    expect(agentTaskReducer(sent, { type: 'message/cleared' }).pendingMessage).toBeNull();
  });
});

describe('tracking the turn from events', () => {
  it('names the turn the events belong to and settles it on its terminal lifecycle', () => {
    const started = apply(initialAgentTaskState(), stream(1, 2, 3));

    // The user message names the turn; nothing has ended it yet.
    expect(started.latestTurnId).toBe(all[0].turnId);
    expect(started.latestTurnSettled).toBe(false);
    expect(started.lifecycleEpoch).toBe(1);

    const settled = apply(started, stream(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14));

    expect(settled.latestTurnSettled).toBe(true);
    // Two lifecycle events in the fixture, each a reason to re-read the durable facts.
    expect(settled.lifecycleEpoch).toBe(2);
  });

  it('does not let a late error from an older steer replace its successor turn', () => {
    const oldTurnId = 'turn-old';
    const successorTurnId = 'turn-successor';
    const oldMessage = { ...stream(1)[0], turnId: oldTurnId };
    const successorStarted = { ...stream(2)[0], turnId: successorTurnId };
    const error = all.find((event) => event.type === 'error');
    if (error === undefined) throw new Error('fixture has no error event');
    const lateSteerFailure = {
      ...error,
      sequence: 3,
      turnId: oldTurnId,
      payload: { code: 'STEER_FAILED', message: 'The old turn ended first', retryable: false },
    } as AgentEventEnvelope;

    let state = apply(initialAgentTaskState(), [oldMessage]);
    state = apply(state, [successorStarted]);
    expect(state.latestTurnId).toBe(successorTurnId);

    state = apply(state, [lateSteerFailure]);

    expect(state.latestTurnId).toBe(successorTurnId);
    expect(state.latestTurnSettled).toBe(false);
  });

  it('derives running from the events until the durable turn has been read', () => {
    // The history has been read, so the connection is open; status then follows the facts.
    const mid = { ...apply(initialAgentTaskState(), stream(1, 2, 3)), task: openTask(), connection: 'open' as const };

    // A reload mid-turn has events but no turn response yet; the composer must not open up.
    expect(runtimeStatus(mid)).toBe('running');
    expect(isIdle(mid)).toBe(false);

    const done = { ...apply(mid, stream(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14)), task: openTask(), connection: 'open' as const };
    expect(runtimeStatus(done)).toBe('idle');
  });
});

describe('reading the turns the transcript names', () => {
  it('asks for each turn a message belongs to exactly once, then stops', () => {
    const replayed = apply(initialAgentTaskState(), stream(1, 2, 3));
    const wanted = unloadedTurnIds(replayed);

    expect(wanted).toEqual([all[0].turnId]);
    // A user message belongs to its turn, so the transcript can show what that turn ran with.
    expect(projectTranscript(replayed.events)[0]?.turnId).toBe(all[0].turnId);

    const loaded = agentTaskReducer(replayed, { type: 'turn/loaded', turn: turn('COMPLETED', { id: wanted[0] }) });
    expect(unloadedTurnIds(loaded)).toEqual([]);
    // An older turn is only remembered; the latest turn stays whatever turn/changed set.
    expect(loaded.turn).toBeNull();
    expect(loaded.turns[wanted[0]]?.state).toBe('COMPLETED');
  });
});

describe('deriving runtime status', () => {
  const base = { ...initialAgentTaskState(), task: openTask(), connection: 'open' as const };

  it('reads idle, running and terminal from the task and turn', () => {
    expect(runtimeStatus(initialAgentTaskState())).toBe('loading');
    // A task read before its history says nothing yet, whatever claim it holds.
    expect(runtimeStatus({ ...base, connection: 'connecting' })).toBe('loading');
    expect(runtimeStatus(base)).toBe('idle');
    expect(runtimeStatus({ ...base, turn: turn('RUNNING') })).toBe('running');
    expect(runtimeStatus({ ...base, turn: turn('ACCEPTED') })).toBe('running');
    expect(runtimeStatus({ ...base, turn: turn('COMPLETED') })).toBe('idle');
    expect(runtimeStatus({ ...base, task: { ...openTask(), state: 'COMPLETED' } })).toBe('completed');
    expect(runtimeStatus({ ...base, task: { ...openTask(), state: 'FAILED' } })).toBe('failed');
  });

  it('reads recovery from a claim that outlived its turn, and closing from a completion claim', () => {
    const turnClaim = { kind: 'TURN', ownerId: 'turn-1', acquiredAt: '2026-08-20T10:00:00Z' };

    // While the claimed turn runs that is just running.
    expect(runtimeStatus({ ...base, task: openTask(turnClaim), turn: turn('RUNNING') })).toBe('running');
    // Once it ended without releasing the claim, the provider was not shown quiet.
    expect(runtimeStatus({ ...base, task: openTask(turnClaim), turn: turn('FAILED') })).toBe('recovery');
    expect(runtimeStatus({ ...base, task: openTask({ ...turnClaim, kind: 'RECOVERY' }) })).toBe('recovery');
    expect(runtimeStatus({ ...base, task: openTask({ ...turnClaim, kind: 'COMPLETION' }) })).toBe('closing');
    expect(isIdle({ ...base, task: openTask(turnClaim), turn: turn('FAILED') })).toBe(false);
  });

  it('offers Retry only for the typed authentication failure and its exact durable input', () => {
    const withMessage = apply(base, stream(1, 2));
    const message = withMessage.events.find((event) => event.type === 'user_message');
    expect(message?.turnId).not.toBeNull();
    const idleAfterFailure = {
      ...withMessage,
      turn: turn('FAILED', {
        id: message?.turnId ?? '',
        startedAt: '2026-08-20T10:00:01Z',
        failureKind: 'AUTHENTICATION_REQUIRED',
      }),
    };

    expect(retryableInput(idleAfterFailure)).toMatchObject({ text: 'Review the authentication change' });
    const steeredAfterStart = apply(withMessage, [{
      ...message,
      eventId: 'steer-after-start',
      sequence: 3,
      payload: { text: 'A later steer', attachments: [], deliveryKind: 'STEER' },
    } as AgentEventEnvelope]);
    expect(retryableInput({ ...idleAfterFailure, events: steeredAfterStart.events }))
      .toMatchObject({ text: 'Review the authentication change' });
    expect(retryableInput({ ...idleAfterFailure, turn: turn('COMPLETED') })).toBeNull();
    expect(retryableInput({ ...idleAfterFailure, turn: { ...idleAfterFailure.turn, failureKind: null } })).toBeNull();
    // Started but never acknowledged: it may have arrived, so it is never resent by a shortcut.
    const uncertain = turn('INTERRUPTED', { startedAt: '2026-08-20T10:00:01Z', delivered: false });
    expect(deliveryUncertain(uncertain)).toBe(true);
    expect(retryableInput({ ...idleAfterFailure, turn: { ...uncertain, id: idleAfterFailure.turn.id, failureKind: 'AUTHENTICATION_REQUIRED' } })).toBeNull();
    // A failed turn without acknowledgement is durably known unsent, including a failure while
    // publishing the started lifecycle after its timestamp was stored.
    expect(deliveryUncertain(turn('FAILED', {
      startedAt: '2026-08-20T10:00:01Z',
      delivered: false,
      knownUnsent: true,
    }))).toBe(false);
    // Never started delivering is known unsent, which is safe to send.
    expect(deliveryUncertain(turn('INTERRUPTED', { delivered: false }))).toBe(false);
    expect(retryableInput({ ...idleAfterFailure, turn: turn('RUNNING', { failureKind: 'AUTHENTICATION_REQUIRED' }) })).toBeNull();
  });
});

describe('projecting rows', () => {
  it('folds an approval outcome into the request it answers', () => {
    const approvals = projectTranscript(all)
      .flatMap((turn) => turn.blocks)
      .filter((block): block is ApprovalBlock => block.kind === 'approval');

    expect(approvals.length).toBeGreaterThan(0);
    const resolved = approvals.find((block) => block.outcome !== null);
    if (resolved) {
      // A resolved approval reads as one settled block, not a request plus a separate answer.
      expect(approvalDecisions(resolved)).toEqual([]);
    }
  });

  it('offers only the decisions the provider actually offered', () => {
    const open: ApprovalBlock = {
      kind: 'approval',
      id: 'a',
      approvalId: 'ap-1',
      title: 't',
      detail: 'd',
      approvalKind: 'COMMAND',
      scope: ['run: npm test'],
      agent: 'root agent',
      decisions: ['ALLOW_ONCE', 'DENY'],
      actionable: true,
      outcome: null,
      forTask: false,
      at: '2026-08-20T10:00:07Z',
    };

    // Each offered decision keeps the answer the bridge actually accepts for it.
    expect(approvalDecisions(open)).toEqual([
      { offered: 'ALLOW_ONCE', decision: 'ALLOW', label: 'Allow once' },
      { offered: 'DENY', decision: 'DENY', label: 'Deny' },
    ]);
    // The task-wide allow is a third answer, drawn between once and deny as the design does.
    expect(approvalDecisions({ ...open, decisions: ['ALLOW_ONCE', 'ALLOW_FOR_TASK', 'DENY'] })).toEqual([
      { offered: 'ALLOW_ONCE', decision: 'ALLOW', label: 'Allow once' },
      { offered: 'ALLOW_FOR_TASK', decision: 'ALLOW_FOR_TASK', label: 'Allow for this task' },
      { offered: 'DENY', decision: 'DENY', label: 'Deny' },
    ]);
    // Fails closed rather than collapsing to an allow/deny guess nobody offered.
    expect(approvalDecisions({ ...open, decisions: [] })).toEqual([]);
    expect(approvalDecisions({ ...open, actionable: false })).toEqual([]);
    // A decision the product has no answer for is dropped, never guessed at.
    expect(approvalDecisions({ ...open, decisions: ['ALLOW_ALWAYS'] })).toEqual([]);
  });

  it('keeps a cancelled turn and a terminal error visible', () => {
    const cancelled = agentTaskReducer(initialAgentTaskState(), {
      type: 'turn/changed',
      turn: turn('CANCELLED'),
    });

    // Cancellation ends the turn, so the task becomes completable again.
    expect(cancelled.turn?.state).toBe('CANCELLED');
    expect(isIdle({ ...cancelled, task: openTask(), connection: 'open' })).toBe(true);

    const errors = projectTranscript(all).flatMap((turn) => turn.blocks).filter((block) => block.kind === 'error');
    expect(errors.length).toBeGreaterThan(0);
    // A terminal error stays in the transcript rather than being swapped for a connection state.
    expect(errors.every((block) => block.kind === 'error' && block.message.length > 0)).toBe(true);
  });

  it('carries the requested scope and the asking agent onto the block', () => {
    const approvals = projectTranscript(all)
      .flatMap((turn) => turn.blocks)
      .filter((block): block is ApprovalBlock => block.kind === 'approval');

    // A decision made without seeing what was asked for is a guess.
    expect(approvals.every((block) => block.scope.length > 0)).toBe(true);
    expect(approvals.some((block) => block.scope.some((entry) => entry.startsWith('cwd:')))).toBe(true);
    // A subagent's approval must not read as the root agent's.
    expect(approvals.every((block) => block.agent === 'root agent')).toBe(true);
  });

  it('keeps every kind the shell prints', () => {
    const turns = projectTranscript(all);

    // The preview must not quietly lose a part of the transcript it is expected to show.
    expect(turns[0].users).not.toHaveLength(0);
    expect(turns[0].answer).not.toBeNull();
    // The streamed partial answer is gone: the final answer replaced it rather than sitting beside it.
    expect(new Set(turns[0].blocks.map((block) => block.kind))).toEqual(new Set(['plan', 'activity', 'approval', 'error']));
  });
});

describe('image attachments', () => {
  it('keeps uploaded drafts by reference until they are sent or removed', () => {
    const one = { id: 'att-1', name: 'a.png', mediaType: 'image/png', byteSize: 10, width: 1, height: 1 };
    const two = { ...one, id: 'att-2', name: 'b.png' };
    let state = agentTaskReducer(initialAgentTaskState(), { type: 'attachments/added', attachments: [one, two] });
    expect(state.attachments.map((attachment) => attachment.id)).toEqual(['att-1', 'att-2']);
    state = agentTaskReducer(state, { type: 'attachments/removed', attachmentId: 'att-1' });
    expect(state.attachments.map((attachment) => attachment.id)).toEqual(['att-2']);
    expect(agentTaskReducer(state, { type: 'attachments/cleared' }).attachments).toEqual([]);
  });

  it('lets only a model that advertises image input attach one', () => {
    const models: ModelOptionDto[] = [
      { id: 'vision', name: 'Vision', description: '', efforts: [], inputModalities: ['text', 'image'] },
      { id: 'text', name: 'Text', description: '', efforts: [], inputModalities: ['text'] },
      { id: 'old', name: 'Old', description: '', efforts: [] },
    ];
    const imageProvider = { ...NO_AGENT_CAPABILITIES, attachmentMediaTypes: ['image/png'] };
    expect(acceptsImages(models, 'vision', imageProvider)).toBe(true);
    expect(acceptsImages(models, 'text', imageProvider)).toBe(false);
    // A catalog entry that says nothing about modalities is not permission.
    expect(acceptsImages(models, 'old', imageProvider)).toBe(false);
    expect(acceptsImages(models, null, imageProvider)).toBe(false);
    expect(acceptsImages(models, 'vision', NO_AGENT_CAPABILITIES)).toBe(false);
  });
});

describe('composer and lifecycle', () => {
  it('offers Complete only when no turn is running', () => {
    const base = { ...initialAgentTaskState(), task: openTask(), connection: 'open' as const };

    expect(isIdle({ ...base, turn: turn('RUNNING') })).toBe(false);
    expect(isIdle({ ...base, turn: turn('ACCEPTED') })).toBe(false);
    expect(isIdle({ ...base, turn: turn('COMPLETED') })).toBe(true);
  });

  it('refuses Send while busy, empty, or disconnected', () => {
    const ready: AgentTaskState = {
      ...initialAgentTaskState(),
      task: openTask(),
      turn: turn('COMPLETED'),
      composer: 'do the thing',
      connection: 'open',
    };

    expect(canSend(ready)).toBe(true);
    expect(canSend({ ...ready, composer: '   ' })).toBe(false);
    expect(canSend({ ...ready, sending: true })).toBe(false);
    expect(canSend({ ...ready, connection: 'failed' })).toBe(false);
    expect(canSend({ ...ready, turn: turn('RUNNING') })).toBe(false);
  });

  it('records connection state and its reason', () => {
    const failed = agentTaskReducer(initialAgentTaskState(), {
      type: 'connection/changed',
      state: 'failed',
      detail: 'stream refused with status 404',
    });

    expect(failed.connection).toBe('failed');
    expect(failed.connectionDetail).toBe('stream refused with status 404');
  });
});

describe('steering and queueing', () => {
  const running: AgentTaskState = {
    ...initialAgentTaskState(),
    task: openTask(),
    turn: turn('RUNNING'),
    composer: 'also check the tests',
    connection: 'open',
    capabilities: { ...NO_AGENT_CAPABILITIES, steering: true },
  };

  it('offers Steer only for a running turn on a lane that steers, with something to say', () => {
    expect(canSteer(running)).toBe(true);
    expect(canSteer({ ...running, capabilities: { ...NO_AGENT_CAPABILITIES, steering: false } })).toBe(false);
    expect(canSteer({ ...running, composer: ' ' })).toBe(false);
    expect(canSteer({ ...running, sending: true })).toBe(false);
    expect(canSteer({ ...running, connection: 'failed' })).toBe(false);
    expect(canSteer({ ...running, turn: turn('COMPLETED') })).toBe(false);
    // An accepted turn has no provider run to steer yet.
    expect(canSteer({ ...running, turn: turn('ACCEPTED') })).toBe(false);
  });

  it('offers Run next while a turn runs, whatever the lane can steer', () => {
    expect(canQueue(running)).toBe(true);
    expect(canQueue({ ...running, capabilities: { ...NO_AGENT_CAPABILITIES, steering: false } })).toBe(true);
    expect(canQueue({ ...running, composer: '' })).toBe(false);
    expect(canQueue({ ...running, turn: turn('COMPLETED') })).toBe(false);
  });

  it('retains only an unchanged thrown request across the composer interaction lifecycle', () => {
    const createKey = vi.fn().mockReturnValueOnce('intent-1').mockReturnValueOnce('intent-2').mockReturnValueOnce('intent-3');
    const submissions = createDraftSubmissionTracker(createKey);
    const defaults = { model: 'm', effort: 'high', permissionPolicy: 'AUTO' };

    const first = submissions.keyFor('queue', 'task-1', 'task-1', 'do it', ['image-1'], defaults);
    // A thrown bridge request calls neither settled nor draftChanged, so the retry is identical.
    expect(submissions.keyFor('queue', 'task-1', 'task-1', 'do it', ['image-1'], defaults)).toBe(first);

    // The actual composer callback invalidates on the edit, even if the user later restores A.
    submissions.draftChanged('task-1', 'do something else', ['image-1'], defaults);
    submissions.draftChanged('task-1', 'do it', ['image-1'], defaults);
    expect(submissions.keyFor('queue', 'task-1', 'task-1', 'do it', ['image-1'], defaults)).toBe('intent-2');

    // A durable response settles the intent, so a deliberate identical submission is new.
    submissions.settled('intent-2');
    expect(submissions.keyFor('queue', 'task-1', 'task-1', 'do it', ['image-1'], defaults)).toBe('intent-3');
    expect(createKey).toHaveBeenCalledTimes(3);
  });

  it('gives an explicit authentication retry a fresh identity and retains it while ambiguous', () => {
    const createKey = vi.fn().mockReturnValueOnce('original').mockReturnValueOnce('retry');
    const submissions = createDraftSubmissionTracker(createKey);
    const defaults = { model: 'm', effort: 'high', permissionPolicy: 'AUTO' };

    expect(submissions.keyFor('send', 'task-1', 'task-1', 'do it', [], defaults)).toBe('original');
    expect(submissions.keyFor('retry', 'task-1', 'task-1', 'do it', [], defaults)).toBe('retry');
    expect(submissions.keyFor('retry', 'task-1', 'task-1', 'do it', [], defaults)).toBe('retry');
  });

  it('treats attachments, applicable settings, action, and the active steer turn as intent identity', () => {
    let next = 0;
    const submissions = createDraftSubmissionTracker(() => `intent-${++next}`);
    const defaults = { model: 'm', effort: 'high', permissionPolicy: 'AUTO' };

    expect(submissions.keyFor('queue', 'task-1', 'task-1', 'do it', [], defaults)).toBe('intent-1');
    submissions.draftChanged('task-1', 'do it', ['image-1'], defaults);
    expect(submissions.keyFor('queue', 'task-1', 'task-1', 'do it', ['image-1'], defaults)).toBe('intent-2');
    submissions.draftChanged('task-1', 'do it', ['image-1'], { ...defaults, model: 'm-2' });
    expect(submissions.keyFor('queue', 'task-1', 'task-1', 'do it', ['image-1'], { ...defaults, model: 'm-2' })).toBe('intent-3');
    expect(submissions.keyFor('steer', 'turn-1', 'task-1', 'do it', ['image-1'], { ...defaults, model: 'm-2' })).toBe('intent-4');
    // A steer targets the active turn's immutable settings; changing the next-turn defaults must
    // not rotate an ambiguous request's key and risk delivering the same steer twice.
    submissions.draftChanged('task-1', 'do it', ['image-1'], { ...defaults, model: 'm-3' });
    expect(submissions.keyFor('steer', 'turn-1', 'task-1', 'do it', ['image-1'], { ...defaults, model: 'm-3' })).toBe('intent-4');
    expect(submissions.keyFor('steer', 'turn-2', 'task-1', 'do it', ['image-1'], { ...defaults, model: 'm-2' })).toBe('intent-5');
  });

  it('gives ordinary sends the same safe ambiguous-retry lifecycle', () => {
    let next = 0;
    const submissions = createDraftSubmissionTracker(() => `intent-${++next}`);
    const defaults = { model: 'm', effort: 'high', permissionPolicy: 'AUTO' };

    const first = submissions.keyFor('send', 'task-1', 'task-1', 'do it', [], defaults);
    expect(submissions.keyFor('send', 'task-1', 'task-1', 'do it', [], defaults)).toBe(first);
    submissions.draftChanged('task-1', 'changed', [], defaults);
    expect(submissions.keyFor('send', 'task-1', 'task-1', 'changed', [], defaults)).toBe('intent-2');
    submissions.settled('intent-2');
    expect(submissions.keyFor('send', 'task-1', 'task-1', 'changed', [], defaults)).toBe('intent-3');
  });

  it('replays one composite task creation with stable task and first-turn identities', () => {
    let next = 0;
    const submissions = createTaskCreationTracker(() => `intent-${++next}`);
    const defaults = { model: 'm', effort: 'high', permissionPolicy: 'AUTO' };

    const first = submissions.requestFor('workspace-1', 'do it', 'codex', defaults);
    expect(submissions.requestFor('workspace-1', 'do it', 'codex', defaults)).toEqual(first);
    expect(first).toMatchObject({ taskId: 'intent-1', idempotencyKey: 'intent-2' });

    const changed = submissions.requestFor('workspace-1', 'do something else', 'codex', defaults);
    expect(changed).toMatchObject({ taskId: 'intent-3', idempotencyKey: 'intent-4' });
    submissions.settled(changed.taskId);
    expect(submissions.requestFor('workspace-1', 'do something else', 'codex', defaults))
      .toMatchObject({ taskId: 'intent-5', idempotencyKey: 'intent-6' });
  });

  it('keeps accepted issue provenance stable on replay and separates different source revisions', () => {
    let next = 0;
    const submissions = createTaskCreationTracker(() => `intent-${++next}`);
    const defaults = { model: 'm', effort: 'high', permissionPolicy: 'AUTO' };
    const source = { issueId: 123, number: 7, updatedAt: 'source-v1' };
    const first = submissions.requestFor('fork-workspace', 'Implement it', 'codex', defaults, [], undefined, source);
    expect(submissions.requestFor('fork-workspace', 'Implement it', 'codex', defaults, [], undefined, { ...source })).toBe(first);
    source.updatedAt = 'source-v2';
    expect(first.issueSource?.updatedAt).toBe('source-v1');
    const changed = submissions.requestFor('fork-workspace', 'Implement it', 'codex', defaults, [], undefined, source);
    expect(changed.taskId).not.toBe(first.taskId);
    expect(changed.idempotencyKey).not.toBe(first.idempotencyKey);
    expect(submissions.requestFor('fork-workspace', 'Implement it', 'codex', defaults).issueSource).toBeUndefined();
  });

  it('retains initial image drafts on replay and changes creation identity when images or Fast change', () => {
    let next = 0;
    const submissions = createTaskCreationTracker(() => `intent-${++next}`);
    const defaults = { model: 'm', effort: 'high', permissionPolicy: 'AUTO' };
    const first = submissions.requestFor('w', 'look', 'codex', defaults, ['image-1']);
    expect(first.draftAttachmentIds).toEqual(['image-1']);
    expect(submissions.requestFor('w', 'look', 'codex', defaults, ['image-1'])).toBe(first);
    const changed = submissions.requestFor('w', 'look', 'codex', defaults, ['image-2']);
    expect(changed.taskId).not.toBe(first.taskId);
    expect(submissions.requestFor('w', 'look', 'codex', { ...defaults, serviceTier: 'priority' }, ['image-2']).taskId)
      .not.toBe(changed.taskId);
  });

  it('changes send and queue identities for Fast while steering keeps the active turn settings', () => {
    const defaults = { model: 'm', effort: 'high', permissionPolicy: 'AUTO' };
    for (const kind of ['send', 'queue', 'steer'] as const) {
      let next = 0;
      const submissions = createDraftSubmissionTracker(() => `intent-${++next}`);
      const first = submissions.keyFor(kind, 'target', 'task', 'look', [], defaults);
      const changed = submissions.keyFor(kind, 'target', 'task', 'look', [], { ...defaults, serviceTier: 'priority' });
      expect(changed === first).toBe(kind === 'steer');
    }
  });

  it('keeps the queue and normalized lane capabilities the server reported', () => {
    const capabilities = { ...NO_AGENT_CAPABILITIES, steering: true, structuredPlans: true };
    let state = agentTaskReducer(initialAgentTaskState(), { type: 'capabilities/loaded', capabilities });
    state = agentTaskReducer(state, {
      type: 'queue/loaded',
      queue: [queued('q-2', 'QUEUED', 'newer')],
    });

    expect(state.capabilities).toEqual(capabilities);
    expect(state.queue.map((input) => input.preview)).toEqual(['newer']);
  });

  it('reconciles a starting row after the acknowledged turn lifecycle asks for a refresh', () => {
    let state = agentTaskReducer(initialAgentTaskState(), { type: 'queue/loaded', queue: [queued('q-1', 'STARTING')] });
    const acknowledged = {
      ...stream(2)[0],
      sequence: 1,
      payload: { phase: 'QUEUED_INPUT_STARTED', message: 'Queued input reached the provider' },
    } as AgentEventEnvelope;
    state = apply(state, [acknowledged]);
    expect(state.lifecycleEpoch).toBe(1);

    state = agentTaskReducer(state, { type: 'queue/loaded', queue: [] });
    expect(state.queue).toEqual([]);
  });

  it('treats a queue change as a projection refresh signal without inventing queue state', () => {
    let state = agentTaskReducer(initialAgentTaskState(), { type: 'queue/loaded', queue: [queued('q-1')] });
    const changed = {
      ...stream(2)[0],
      sequence: 1,
      turnId: null,
      payload: { phase: 'QUEUE_CHANGED', message: 'Queued input removed' },
    } as AgentEventEnvelope;

    state = apply(state, [changed]);

    expect(state.lifecycleEpoch).toBe(1);
    expect(state.latestTurnId).toBeNull();
    expect(state.queue).toHaveLength(1);
  });

  it('blocks Complete on waiting queue work and confirms uncertain abandonment', () => {
    const idle = { ...initialAgentTaskState(), task: openTask(), turn: turn('COMPLETED'), connection: 'open' as const };
    expect(canComplete(idle)).toBe(true);
    for (const state of ['QUEUED', 'PAUSED', 'STARTING']) {
      expect(canComplete({ ...idle, queue: [queued('q-1', state)] })).toBe(false);
    }

    const uncertain = [queued('q-1', 'UNCERTAIN')];
    expect(canComplete({ ...idle, queue: uncertain })).toBe(true);
    const reject = vi.fn(() => false);
    expect(confirmCompletion(uncertain, reject)).toBe(false);
    expect(reject).toHaveBeenCalledWith(expect.stringMatching(/abandoned.*never be retried/i));

    const unnecessary = vi.fn(() => false);
    expect(confirmCompletion([], unnecessary)).toBe(true);
    expect(unnecessary).not.toHaveBeenCalled();
  });
});


describe('bounded history pages', () => {
  function page(start: number, end: number, watermark = 1000): AgentHistoryPageDto {
    return {
      events: Array.from({ length: end - start + 1 }, (_, index) => ({ ...all[0], sequence: start + index, eventId: `event-${start + index}`, turnId: `turn-${start}` })),
      beforeCursor: start > 1 ? `byte-${start}` : null,
      afterCursor: end < watermark ? `byte-${end + 1}` : null,
      latestSequence: watermark,
    };
  }

  it('fills the recent window before display, with a fixed three-page ceiling', async () => {
    const readAgentHistory = vi.fn()
      .mockResolvedValueOnce(page(4001, 5000, 5000))
      .mockResolvedValueOnce(page(3001, 4000, 5000))
      .mockResolvedValueOnce(page(2001, 3000, 5000));
    const history = await readRecentAgentHistory({ readAgentHistory }, 'task-1');
    const state = agentTaskReducer(initialAgentTaskState(), { type: 'history/loaded', ...history, direction: 'latest' });
    expect(readAgentHistory.mock.calls).toEqual([['task-1'], ['task-1', 'byte-4001'], ['task-1', 'byte-3001']]);
    expect(state.events).toHaveLength(3000);
    expect(state.events[0].sequence).toBe(2001);
    expect(state.events.at(-1)?.sequence).toBe(5000);
    expect(state.lastSequence).toBe(5000);
    expect(state.historyPages).toHaveLength(3);
    expect(historyBefore(state)).toBe('byte-2001');
    expect(historyAfter(state)).toBeNull();
  });

  it('stops filling at the beginning and leaves cached pages visible until replacement arrives', async () => {
    const readAgentHistory = vi.fn().mockResolvedValue(page(1, 30, 30));
    const history = await readRecentAgentHistory({ readAgentHistory }, 'task-1');
    expect(readAgentHistory).toHaveBeenCalledTimes(1);
    expect(history.earlier).toEqual([]);
    const state = agentTaskReducer(initialAgentTaskState(), { type: 'history/loaded', ...history, direction: 'latest' });
    expect(agentTaskReducer(state, { type: 'live/changed', sequence: 31, turnId: 'turn-1' }).events).toBe(state.events);
  });

  it('opens a tail above sequence one and bounds uncompacted history while browsing', () => {
    let state = agentTaskReducer(initialAgentTaskState(), { type: 'history/loaded', page: page(4001, 5000, 5000), direction: 'latest' });
    expect(state.gap).toBe(false);
    expect(state.lastSequence).toBe(5000);
    expect(state.latestTurnId).toBe('turn-4001');
    for (const start of [3001, 2001, 1001, 1]) {
      state = agentTaskReducer(state, { type: 'history/loaded', page: page(start, start + 999, 5000), direction: 'older' });
      expect(state.historyPages.length).toBeLessThanOrEqual(3);
      expect(state.events.length).toBeLessThanOrEqual(3000);
      expect(state.lastSequence).toBe(5000);
      expect(state.latestTurnId).toBe('turn-4001');
    }
    expect(state.events[0].sequence).toBe(1);
    expect(state.events.at(-1)?.sequence).toBe(3000);
    expect(historyBefore(state)).toBeNull();
    expect(historyAfter(state)).toBe('byte-3001');
    state = agentTaskReducer(state, { type: 'history/loaded', page: page(3001, 4000, 5000), direction: 'newer' });
    expect(state.events[0].sequence).toBe(1001);
    expect(state.events.at(-1)?.sequence).toBe(4000);
    expect(state.events).toHaveLength(3000);
  });

  it('observes live changes while old pages remain unchanged and rejects stale latest responses', () => {
    let state = agentTaskReducer(initialAgentTaskState(), { type: 'history/loaded', page: page(801, 1000), direction: 'latest' });
    const displayed = state.events;
    state = { ...state, latestTurnSettled: true };
    state = agentTaskReducer(state, { type: 'live/changed', sequence: 1001, turnId: 'new-turn' });
    expect(state.events).toBe(displayed);
    expect(state.latestTurnId).toBe('new-turn');
    expect(state.latestTurnSettled).toBe(false);
    state = agentTaskReducer(state, { type: 'history/loaded', page: page(802, 1001, 1001), direction: 'latest' });
    const current = state;
    state = agentTaskReducer(state, { type: 'history/loaded', page: page(801, 1000), direction: 'latest' });
    expect(state).toBe(current);
  });

  it('does not accumulate live turn metadata while the user keeps an old window open', () => {
    let state = agentTaskReducer(initialAgentTaskState(), { type: 'history/loaded', page: page(1, 200), direction: 'latest' });
    state = agentTaskReducer(state, { type: 'turn/loaded', turn: turn('COMPLETED', { id: 'turn-1' }) });
    for (let index = 0; index < 1000; index++) state = agentTaskReducer(state, { type: 'turn/changed', turn: turn('RUNNING', { id: `live-${index}` }) });
    expect(Object.keys(state.turns)).toEqual(['turn-1', 'live-999']);
    expect(state.events).toHaveLength(200);
  });

  it('loads durable facts for activity-only turns and prunes facts when their pages are evicted', () => {
    const tail = page(801, 1000);
    const command = all.find((item) => item.type === 'command');
    if (command === undefined) throw new Error('missing command fixture');
    tail.events = tail.events.map((entry) => ({ ...command, sequence: entry.sequence, eventId: entry.eventId, turnId: 'turn-801' }));
    let state = agentTaskReducer(initialAgentTaskState(), { type: 'history/loaded', page: tail, direction: 'latest' });
    expect(unloadedTurnIds(state)).toEqual(['turn-801']);
    state = agentTaskReducer(state, { type: 'turn/loaded', turn: turn('COMPLETED', { id: 'turn-801' }) });
    state = agentTaskReducer(state, { type: 'history/loaded', page: page(1, 200), direction: 'latest' });
    expect(state.turns['turn-801']).toBeUndefined();
  });
});
