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
import { parseAgentEventSequence, parseAgentHistoryPage, parseAgentPendingApprovals } from './events';

describe('agent event contract', () => {
  it('freezes the workspace-safe task-create request', () => {
    expect(fixture.createTaskRequest).toEqual({
      workspaceId: 'workspace-1',
      message: 'Review the authentication change',
      laneId: 'codex',
      defaults: {
        model: 'gpt-5.6-sol',
        effort: 'high',
        permissionPolicy: 'AUTO',
      },
    });
  });

  it('parses the provider-neutral v2 fixture', () => {
    const events = parseAgentEventSequence(fixture.events);

    expect(events.map(event => event.type)).toEqual([
      'user_message',
      'lifecycle',
      'assistant_message',
      'plan_updated',
      'command',
      'file_change',
      'tool',
      'approval_requested',
      'approval_resolved',
      'approval_requested',
      'error',
      'usage',
      'assistant_message',
      'lifecycle',
    ]);
    const messages = events.filter(event => event.type === 'assistant_message');
    expect(messages.map(event => event.payload.phase)).toEqual(['PARTIAL', 'FINAL']);
    expect(messages.map(event => event.source.providerItemId)).toEqual(['message-1', 'message-1']);
    const approval = events.find(event => event.type === 'approval_requested');
    expect(approval?.payload.actionable).toBe(true);
    expect(approval?.payload.availableDecisions).toEqual(['ALLOW_ONCE', 'DENY']);
  });

  it('reads the line a tool gave its call, and none from events stored before tools described calls', () => {
    const source = fixture.events.find(event => event.type === 'tool');
    if (source === undefined) throw new Error('Fixture requires a tool event');
    const tool = { ...source, sequence: 1 };
    const [older] = parseAgentEventSequence([tool]);
    expect(older.type === 'tool' ? older.payload.description : undefined).toBeNull();
    const [described] = parseAgentEventSequence([{ ...tool, payload: { ...tool.payload, description: 'Read note · Snapshot loading' } }]);
    expect(described.type === 'tool' ? described.payload.description : undefined).toBe('Read note · Snapshot loading');
    expect(() => parseAgentEventSequence([{ ...tool, payload: { ...tool.payload, description: 3 } }])).toThrow('tool.description');
  });

  it('reads whether an approval stays in the sandbox, and outside for events stored before it said', () => {
    const source = fixture.events.find(event => event.type === 'approval_requested');
    if (source === undefined) throw new Error('Fixture requires an approval request');
    const request = { ...source, sequence: 1 };
    const [older] = parseAgentEventSequence([request]);
    expect(older.type === 'approval_requested' ? older.payload.sandboxed : undefined).toBe(false);
    const [inside] = parseAgentEventSequence([{ ...request, payload: { ...request.payload, sandboxed: true } }]);
    expect(inside.type === 'approval_requested' ? inside.payload.sandboxed : undefined).toBe(true);
    expect(() => parseAgentEventSequence([{ ...request, payload: { ...request.payload, sandboxed: 'yes' } }])).toThrow('approvalRequested.sandboxed');
  });

  it('accepts invocation-cumulative usage and preserves its counter scope', () => {
    const usage = { ...fixture.events[11], sequence: 1,
      payload: { ...fixture.events[11].payload, semantics: 'CUMULATIVE_FOR_INVOCATION' } };
    const [parsed] = parseAgentEventSequence([usage]);
    expect(parsed.type).toBe('usage');
    expect(parsed.payload).toEqual(usage.payload);
    const sessionCost = { ...usage.payload, semantics: 'FINAL_WITH_SESSION_COST' };
    expect(parseAgentEventSequence([{ ...usage, payload: sessionCost }])[0].payload).toEqual(sessionCost);
    expect(() => parseAgentEventSequence([{ ...usage, payload: { ...usage.payload, semantics: 'UNKNOWN_SCOPE' } }]))
      .toThrow('usage.semantics');
  });

  it('accepts completed commentary as distinct from the terminal answer', () => {
    const commentary = structuredClone(fixture.events[2]);
    commentary.sequence = 1;
    commentary.payload.messageKind = 'COMMENTARY';

    const [parsed] = parseAgentEventSequence([commentary]);
    expect(parsed.type === 'assistant_message' && parsed.payload.messageKind).toBe('COMMENTARY');
  });

  it.each(['QUEUE_CHANGED', 'QUEUED_INPUT_STARTED', 'QUEUED_INPUT_PAUSED', 'DEVELOPMENT_AUTOMATION_UPDATED', 'WORKTREE_UPDATED'])('accepts the task-update lifecycle signal %s', (phase) => {
    const acknowledged = structuredClone(fixture.events[1]);
    acknowledged.sequence = 1;
    acknowledged.payload.phase = phase;

    const [event] = parseAgentEventSequence([acknowledged]);
    expect(event.type === 'lifecycle' && event.payload.phase).toBe(phase);
  });

  it('reads a task-wide allow, and defaults it off on resolutions stored before it existed', () => {
    const stored = parseAgentEventSequence(fixture.events).find(event => event.type === 'approval_resolved');
    expect(stored?.payload.forTask).toBe(false);
    const offered = structuredClone(fixture.events).map(event => {
      if (event.type === 'approval_requested') {
        return { ...event, payload: { ...event.payload, availableDecisions: ['ALLOW_ONCE', 'ALLOW_FOR_TASK', 'DENY'] } };
      }
      return event.type === 'approval_resolved' ? { ...event, payload: { ...event.payload, forTask: true } } : event;
    });
    const parsed = parseAgentEventSequence(offered);
    expect(parsed.find(event => event.type === 'approval_resolved')?.payload.forTask).toBe(true);
    expect(parsed.find(event => event.type === 'approval_requested')?.payload.availableDecisions)
      .toEqual(['ALLOW_ONCE', 'ALLOW_FOR_TASK', 'DENY']);
  });

  it('keeps shared primitive validation and requires explicit nulls in event fields', () => {
    const event = fixture.events[0];
    for (const invalid of [
      { ...event, eventId: 7 },
      { ...event, type: 'unknown' },
      { ...event, turnId: undefined },
      { ...event, source: [] as unknown[] },
      { ...event, payload: { ...event.payload, attachments: {} } },
    ]) expect(() => parseAgentEventSequence([invalid])).toThrow();
    const usage = { ...fixture.events[11], sequence: 1 };
    for (const cost of [undefined, Infinity, NaN, '1']) {
      expect(() => parseAgentEventSequence([{ ...usage, payload: { ...usage.payload, cost } }])).toThrow('usage.cost');
    }
  });

  it('rejects a duplicate sequence', () => {
    const duplicate = structuredClone(fixture.events);
    duplicate[5].sequence = duplicate[4].sequence;

    expect(() => parseAgentEventSequence(duplicate)).toThrow('Duplicate or out-of-order event sequence 5');
  });

  it('keeps read names, accepts old actions without names, and rejects invalid names', () => {
    const command = {
      ...structuredClone(fixture.events[4]),
      sequence: 1,
      payload: {
        ...fixture.events[4].payload,
        actions: [
          { kind: 'READ', name: 'OpenAI Docs skill', path: null as string | null, query: null as string | null },
          { kind: 'READ', path: 'README.md', query: null as string | null },
        ],
      },
    };
    const [parsed] = parseAgentEventSequence([command]);
    expect(parsed.type === 'command' && parsed.payload.actions).toEqual([
      { kind: 'READ', name: 'OpenAI Docs skill', path: null, query: null },
      { kind: 'READ', name: null, path: 'README.md', query: null },
    ]);
    expect(() => parseAgentEventSequence([{
      ...command,
      payload: { ...command.payload, actions: [{ kind: 'READ', name: 42, path: null, query: null }] },
    }])).toThrow('commandAction.name');
  });

  it('rejects a sequence gap', () => {
    const gapped = structuredClone(fixture.events);
    gapped[5].sequence++;

    expect(() => parseAgentEventSequence(gapped)).toThrow('Event sequence gap: expected 6, received 7');
  });

  it('rejects a duplicate event id', () => {
    const duplicate = structuredClone(fixture.events);
    duplicate[5].eventId = duplicate[4].eventId;

    expect(() => parseAgentEventSequence(duplicate)).toThrow(`Duplicate eventId ${duplicate[4].eventId}`);
  });

  it('rejects events from another task', () => {
    const mixed = structuredClone(fixture.events);
    mixed[5].taskId = '33333333-3333-4333-8333-333333333333';

    expect(() => parseAgentEventSequence(mixed)).toThrow(
      'Mixed taskId: expected 11111111-1111-4111-8111-111111111111, received 33333333-3333-4333-8333-333333333333');
  });
});

describe('bounded history contract', () => {
  const taskId = fixture.events[0].taskId;
  const page = () => ({
    events: structuredClone(fixture.events.slice(8)),
    beforeCursor: '4294967296',
    afterCursor: null as string | null,
    latestSequence: 14,
  });

  it('accepts a partial history window and preserves a large byte cursor exactly', () => {
    const parsed = parseAgentHistoryPage(page(), taskId);
    expect(parsed.events[0].sequence).toBe(9);
    expect(parsed.beforeCursor).toBe('4294967296');
    expect(parsed.latestSequence).toBe(14);
    expect(parseAgentHistoryPage({ ...page(), events: [], beforeCursor: null }, taskId).events).toEqual([]);
  });

  it('rejects gaps, foreign history, and a watermark behind the page', () => {
    const gap = page();
    gap.events.splice(2, 1);
    expect(() => parseAgentHistoryPage(gap, taskId)).toThrow('Event sequence gap');
    expect(() => parseAgentHistoryPage(page(), 'another-task')).toThrow('another task');
    expect(() => parseAgentHistoryPage({ ...page(), latestSequence: 13 }, taskId)).toThrow('watermark');
  });

  it.each(['-1', '1.5', '01', '9223372036854775808'])('rejects invalid byte cursor %s', (beforeCursor) => {
    expect(() => parseAgentHistoryPage({ ...page(), beforeCursor }, taskId)).toThrow('cursor');
  });

  it('enforces both the event count and serialized byte budget', () => {
    const events = Array.from({ length: 1000 }, (_, index) => ({
      ...fixture.events[0], sequence: index + 1, eventId: `event-${index + 1}`,
    }));
    expect(parseAgentHistoryPage({ ...page(), events, latestSequence: 1000 }, taskId).events).toHaveLength(1000);
    expect(() => parseAgentHistoryPage({ ...page(), events: [...events, events[0]], latestSequence: 1001 }, taskId))
      .toThrow('count or byte limit');
    const oversized = structuredClone(fixture.events[0]);
    oversized.payload.text = '界'.repeat(4 * 1024 * 1024);
    expect(() => parseAgentHistoryPage({ ...page(), events: [oversized] }, taskId))
      .toThrow('count or byte limit');
  });

  it.each([
    { count: 50_000, storedAction: { kind: 'READ', path: null, query: null }, kind: 'READ' },
    { count: 190_000, storedAction: { kind: 1 }, kind: 'LIST_FILES' },
  ])('accepts bounded legacy normalization for $count actions', ({ count, storedAction, kind }) => {
    const stored = {
      ...fixture.events[4],
      payload: { ...fixture.events[4].payload, actions: Array(count).fill(storedAction) },
    };
    const normalized = {
      ...stored,
      payload: { ...stored.payload, actions: Array(count).fill({ kind, name: null, path: null, query: null }) },
    };
    const bytes = (value: unknown): number => new TextEncoder().encode(JSON.stringify(value)).byteLength;
    expect(bytes(stored)).toBeLessThanOrEqual(2 * 1024 * 1024);
    expect(bytes(normalized)).toBeGreaterThan(2 * 1024 * 1024);
    expect(bytes(normalized)).toBeLessThan(12 * 1024 * 1024);
    const parsed = parseAgentHistoryPage({ ...page(), events: [normalized] }, taskId).events[0];
    expect(parsed.type === 'command' && parsed.payload.actions.length).toBe(count);
  });

  it('reads pending approvals separately and rejects approvals belonging to another turn', () => {
    const event = fixture.events[7];
    if (event.turnId === null) throw new Error('The approval fixture must belong to a turn');
    const approval = { ...event.payload, taskId, turnId: event.turnId, source: event.source, occurredAt: event.occurredAt };
    expect(parseAgentPendingApprovals([approval], taskId, event.turnId)[0].approvalId).toBe(event.payload.approvalId);
    expect(() => parseAgentPendingApprovals([approval], taskId, 'another-turn')).toThrow('another task or turn');
    expect(() => parseAgentPendingApprovals([{ ...approval, actionable: 'yes' }], taskId, event.turnId))
      .toThrow('actionable');
  });
});
