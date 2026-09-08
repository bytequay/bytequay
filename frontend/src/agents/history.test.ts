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
import type { AgentHistoryPageDto } from '../types';
import type { AgentEventEnvelope } from './events';
import { compactHistoryPages } from './history';
import { projectTranscript } from './transcript';

type Command = Extract<AgentEventEnvelope, { type: 'command' }>;

function command(sequence: number, changes: Partial<Command['payload']> = {}): Command {
  return {
    schemaVersion: 2, eventId: `event-${sequence}`, sequence, taskId: 'task', turnId: 'turn', occurredAt: `2026-09-08T00:00:${String(sequence % 60).padStart(2, '0')}Z`,
    source: { providerThreadId: 'thread', parentProviderThreadId: null, providerTurnId: 'provider-turn', providerItemId: 'command', providerEventId: null, logicalAgentPath: '/', providerOccurredAt: null },
    type: 'command',
    payload: { state: 'PROGRESS', argv: [], output: `Line ${sequence}\n`, exitCode: null, truncated: false, cwd: null, durationMs: null, actions: [], ...changes },
  };
}

function message(sequence: number, text: string, complete = false): AgentEventEnvelope {
  const base = command(sequence);
  return {
    ...base, source: { ...base.source, providerItemId: 'message', providerEventId: complete ? 'completed' : null }, type: 'assistant_message',
    payload: { text, messageKind: 'COMMENTARY', phase: complete ? 'FINAL' : 'PARTIAL' },
  };
}

function pages(events: AgentEventEnvelope[], size = 1000): AgentHistoryPageDto[] {
  return Array.from({ length: Math.ceil(events.length / size) }, (_, index) => ({
    events: events.slice(index * size, (index + 1) * size),
    beforeCursor: index === 0 ? null : String(index * size),
    afterCursor: (index + 1) * size >= events.length ? null : String((index + 1) * size),
    latestSequence: events.at(-1)?.sequence ?? 0,
  }));
}

const flattened = (value: AgentHistoryPageDto[]): AgentEventEnvelope[] => value.flatMap(page => page.events);

describe('compact conversation history', () => {
  it('keeps 7,000 command updates and their surrounding messages in one bounded logical page', () => {
    const before = message(1, 'Running the build.', true);
    const after = message(7002, 'The build finished.', true);
    const events = [{ ...before, source: { ...before.source, providerItemId: 'before' } }, command(2, { state: 'STARTED', argv: ['mvn', 'verify'], output: null }),
      ...Array.from({ length: 6999 }, (_, index) => command(index + 3)), { ...after, source: { ...after.source, providerItemId: 'after' } }];
    const transport = pages(events);
    let retained: AgentHistoryPageDto[] = [];
    for (const page of transport.slice().reverse()) retained = compactHistoryPages([page, ...retained]).slice(0, 3);
    expect(retained).toHaveLength(1);
    expect(retained[0].events.map(event => event.sequence)).toEqual([1, 2, 7001, 7002]);
    expect(retained[0]).toMatchObject({ beforeCursor: null, afterCursor: null, latestSequence: 7002 });
    expect(projectTranscript(flattened(retained))).toEqual(projectTranscript(events));
  });

  it.each([null, 'Authoritative build result'])('preserves terminal output %s, metadata, and interleaved facts', output => {
    const events: AgentEventEnvelope[] = [
      command(1, { state: 'STARTED', argv: ['mvn', 'verify'], output: null }),
      command(2, { output: 'first\n', cwd: '.', actions: [{ kind: 'READ', path: 'pom.xml', query: null }] }),
      message(3, 'Still checking.', true),
      command(4, { output: 'second\n', truncated: true, durationMs: 12 }),
      { ...command(5), type: 'lifecycle', payload: { phase: 'RATE_LIMITED', message: 'Retrying later' } },
      command(6, { output: 'third\n' }),
      command(7, { state: 'COMPLETED', output, exitCode: 0, durationMs: 20 }),
      { ...command(8), type: 'approval_resolved', payload: { approvalId: 'approval', outcome: 'DENIED', forTask: false } },
    ];
    const compact = flattened(compactHistoryPages(pages(events, 2)));
    expect(projectTranscript(compact)).toEqual(projectTranscript(events));
    expect(compact.filter(event => event.type === 'lifecycle' || event.type === 'approval_resolved')).toEqual([events[4], events[7]]);
    expect(compact[0]).toBe(events[0]);
    expect(compact.at(-1)).toBe(events.at(-1));
    expect(compact.length).toBeLessThan(events.length);
  });

  it('preserves partial text and lets authoritative text replace all its deltas', () => {
    const partial = Array.from({ length: 2000 }, (_, index) => message(index + 1, `${index} `));
    const compact = flattened(compactHistoryPages(pages(partial)));
    expect(compact).toHaveLength(2);
    expect(projectTranscript(compact)).toEqual(projectTranscript(partial));
    const events = [...partial, message(2001, 'The authoritative response.', true)];
    const completed = flattened(compactHistoryPages(pages(events)));
    expect(completed).toHaveLength(2);
    expect(completed.map(event => event.sequence)).toEqual([1, 2001]);
    expect(projectTranscript(completed)).toEqual(projectTranscript(events));
  });

  it('does not combine separate threads, missing item identities, or nonadjacent cursors', () => {
    const events = [command(1), command(2), command(3), { ...command(4), source: { ...command(4).source, providerThreadId: 'child', parentProviderThreadId: 'thread' } },
      { ...command(5), source: { ...command(5).source, providerItemId: null } }, command(6)];
    const transport = pages(events, 3);
    const broken = [{ ...transport[0], afterCursor: 'different-boundary' }, transport[1]];
    expect(compactHistoryPages(broken)).toHaveLength(2);
    const compact = flattened(compactHistoryPages(transport));
    expect(compact.some(event => event.sequence === 4)).toBe(true);
    expect(compact.some(event => event.sequence === 5)).toBe(true);
    expect(projectTranscript(compact)).toEqual(projectTranscript(events));
    expect(projectTranscript(compact, 'child')).toEqual(projectTranscript(events, 'child'));
  });

  it('keeps the count and normalized byte caps without truncating large progress output', () => {
    const independent = Array.from({ length: 2500 }, (_, index) => ({ ...command(index + 1), source: { ...command(index + 1).source, providerItemId: `item-${index}` } }));
    expect(compactHistoryPages(pages(independent)).map(page => page.events.length)).toEqual([1000, 1000, 500]);
    const text = '大\n'.repeat(1800000);
    const events = [command(1, { state: 'STARTED', output: null }), command(2, { output: text }), command(3, { output: text })];
    const compact = compactHistoryPages(pages(events, 1));
    expect(compact).toHaveLength(2);
    for (const page of compact) expect(new TextEncoder().encode(JSON.stringify(page.events)).byteLength).toBeLessThanOrEqual(12 * 1024 * 1024);
    expect(projectTranscript(flattened(compact))).toEqual(projectTranscript(events));
  });
});
