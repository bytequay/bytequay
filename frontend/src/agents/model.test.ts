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

import { describe, expect, it } from 'vitest';
import fixture from './fixtures/agent-events-v2.json';
import { parseAgentEventSequence, type AgentEventEnvelope } from './events';
import {
  acceptsImages,
  agentTaskReducer,
  approvalDecisions,
  canSend,
  deliveryUncertain,
  initialAgentTaskState,
  isIdle,
  retryableMessage,
  runtimeStatus,
  unloadedTurnIds,
  type AgentTaskState,
} from './model';
import { projectTranscript, type ApprovalBlock } from './transcript';
import type { AgentTaskDto, AgentTurnDto, ModelOptionDto } from '../types';

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

describe('applying events', () => {
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

  it('derives running from the events until the durable turn has been read', () => {
    const mid = { ...apply(initialAgentTaskState(), stream(1, 2, 3)), task: openTask() };

    // A reload mid-turn has events but no turn response yet; the composer must not open up.
    expect(runtimeStatus(mid)).toBe('running');
    expect(isIdle(mid)).toBe(false);

    const done = { ...apply(mid, stream(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14)), task: openTask() };
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
  const base = { ...initialAgentTaskState(), task: openTask() };

  it('reads idle, running and terminal from the task and turn', () => {
    expect(runtimeStatus(initialAgentTaskState())).toBe('loading');
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
      at: '2026-08-20T10:00:07Z',
    };

    // Each offered decision keeps the answer the bridge actually accepts for it.
    expect(approvalDecisions(open)).toEqual([
      { offered: 'ALLOW_ONCE', decision: 'ALLOW', label: 'Allow once' },
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
    expect(isIdle({ ...cancelled, task: openTask() })).toBe(true);

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
    const base = { ...initialAgentTaskState(), task: openTask() };

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
