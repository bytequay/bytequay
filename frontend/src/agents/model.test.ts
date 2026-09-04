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
  isIdle,
  retryableMessage,
  runtimeStatus,
  unloadedTurnIds,
  type AgentTaskState,
} from './model';
import { projectTranscript, type ApprovalBlock } from './transcript';
import type { AgentQueuedInputDto, AgentTaskDto, AgentTurnDto, ModelOptionDto } from '../types';

const all = parseAgentEventSequence(fixture.events);

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
      costProvenance: 'UNAVAILABLE',
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
    settings: { model: 'm', effort: null, permissionPolicy: 'AUTO', sandboxPolicy: 'WORKSPACE_WRITE', maximumRuntimeMillis: 1000, attachments: [] },
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

  it('offers Retry only for a delivered, unfinished last message on an idle task', () => {
    const idleAfterFailure = { ...apply(base, stream(1, 2)), turn: turn('FAILED', { startedAt: '2026-08-20T10:00:01Z' }) };

    expect(retryableMessage(idleAfterFailure)).toBe('Review the authentication change');
    expect(retryableMessage({ ...idleAfterFailure, turn: turn('COMPLETED') })).toBeNull();
    // Started but never acknowledged: it may have arrived, so it is never resent by a shortcut.
    const uncertain = turn('INTERRUPTED', { startedAt: '2026-08-20T10:00:01Z', delivered: false });
    expect(deliveryUncertain(uncertain)).toBe(true);
    expect(retryableMessage({ ...idleAfterFailure, turn: uncertain })).toBeNull();
    // A failed turn without acknowledgement is durably known unsent, including a failure while
    // publishing the started lifecycle after its timestamp was stored.
    expect(deliveryUncertain(turn('FAILED', {
      startedAt: '2026-08-20T10:00:01Z',
      delivered: false,
      knownUnsent: true,
    }))).toBe(false);
    // Never started delivering is known unsent, which is safe to send.
    expect(deliveryUncertain(turn('INTERRUPTED', { delivered: false }))).toBe(false);
    expect(retryableMessage({ ...idleAfterFailure, turn: turn('RUNNING') })).toBeNull();
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
    expect(turns[0].user).not.toBeNull();
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
    expect(acceptsImages(models, 'vision')).toBe(true);
    expect(acceptsImages(models, 'text')).toBe(false);
    // A catalog entry that says nothing about modalities is not permission.
    expect(acceptsImages(models, 'old')).toBe(false);
    expect(acceptsImages(models, null)).toBe(false);
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
    steering: true,
  };

  it('offers Steer only for a running turn on a lane that steers, with something to say', () => {
    expect(canSteer(running)).toBe(true);
    expect(canSteer({ ...running, steering: false })).toBe(false);
    expect(canSteer({ ...running, composer: ' ' })).toBe(false);
    expect(canSteer({ ...running, sending: true })).toBe(false);
    expect(canSteer({ ...running, connection: 'failed' })).toBe(false);
    expect(canSteer({ ...running, turn: turn('COMPLETED') })).toBe(false);
    // An accepted turn has no provider run to steer yet.
    expect(canSteer({ ...running, turn: turn('ACCEPTED') })).toBe(false);
  });

  it('offers Run next while a turn runs, whatever the lane can steer', () => {
    expect(canQueue(running)).toBe(true);
    expect(canQueue({ ...running, steering: false })).toBe(true);
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

  it('keeps the queue and the lane capability the server reported', () => {
    let state = agentTaskReducer(initialAgentTaskState(), { type: 'steering/loaded', steering: true });
    state = agentTaskReducer(state, {
      type: 'queue/loaded',
      queue: [queued('q-2', 'QUEUED', 'newer')],
    });

    expect(state.steering).toBe(true);
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
