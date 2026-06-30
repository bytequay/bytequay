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
import type { ThreadMessageDto, WorkUnitTaskDto } from '../types';
import { buildTrunkTimeline, extractText, parseToolCall, trunkHeadline, trunkWork } from './trunkTimeline';

function msg(id: string, role: string, type: string, body: unknown, ts: string): ThreadMessageDto {
  return {
    id, threadId: 't', taskId: null, seq: 0, role, type,
    contentJson: JSON.stringify(body), durationMs: null, tokensIn: null, tokensOut: null, costUsdMilli: null, ts,
  };
}

function task(id: string, createdAt: string): WorkUnitTaskDto {
  return { id, createdAt } as unknown as WorkUnitTaskDto;
}

describe('extractText / parseToolCall', () => {
  it('pulls text / summary out of envelopes', () => {
    expect(extractText(JSON.stringify({ text: 'hi' }))).toBe('hi');
    expect(extractText(JSON.stringify({ summary: 'thought' }))).toBe('thought');
    expect(extractText('not json')).toBe('');
  });

  it('reads a tool name + summary', () => {
    const { name, summary } = parseToolCall(JSON.stringify({ toolName: 'Grep', input: { pattern: 'foo' } }));
    expect(name).toBe('Grep');
    expect(summary).toBe('foo');
  });
});

describe('buildTrunkTimeline', () => {
  it('groups rounds by user message and merges task cuts by time', () => {
    const items = buildTrunkTimeline([
      msg('u1', 'user', 'text', { text: 'clean this up' }, '2026-01-01T00:00:00Z'),
      msg('t1', 'assistant', 'thinking', { summary: 'scouting' }, '2026-01-01T00:00:10Z'),
      msg('a1', 'assistant', 'text', { text: 'found 3 sites' }, '2026-01-01T00:00:20Z'),
      msg('u2', 'user', 'text', { text: 'cut a task' }, '2026-01-01T00:02:00Z'),
    ], [task('task-9', '2026-01-01T00:01:00Z')]);

    // round1, cut (00:01 falls between the two rounds), round2.
    expect(items.map(i => i.kind)).toEqual(['round', 'cut', 'round']);
    const round1 = items[0].kind === 'round' ? items[0].round : null;
    expect(round1?.userTurn?.id).toBe('u1');
    expect(round1?.rows.map(r => r.id)).toEqual(['t1', 'a1']);
  });

  it('derives headline (last text) and folds the rest as work', () => {
    const items = buildTrunkTimeline([
      msg('u', 'user', 'text', { text: 'go' }, '2026-01-01T00:00:00Z'),
      msg('th', 'assistant', 'thinking', { summary: 'hmm' }, '2026-01-01T00:00:05Z'),
      msg('tc', 'assistant', 'tool_call', { toolName: 'Read', input: { path: 'x' } }, '2026-01-01T00:00:06Z'),
      msg('tx', 'assistant', 'text', { text: 'conclusion' }, '2026-01-01T00:00:09Z'),
    ], []);
    const round = items[0].kind === 'round' ? items[0].round : null;
    expect(trunkHeadline(round!)?.id).toBe('tx');
    expect(trunkWork(round!).map(r => r.id)).toEqual(['th', 'tc']);
  });
});
