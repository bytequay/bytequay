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
import {
  buildTrunkTimeline, extractImages, extractText, parsePermissionRequest, parseToolCall, trunkHeadline, trunkWork,
} from './trunkTimeline';

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
    expect(extractText(JSON.stringify({ message: 'agent failed' }))).toBe('agent failed');
    expect(extractText('not json')).toBe('');
  });

  it('reads a tool name + summary', () => {
    const { name, summary } = parseToolCall(JSON.stringify({ toolName: 'Grep', input: { pattern: 'foo' } }));
    expect(name).toBe('Grep');
    expect(summary).toBe('foo');
  });
});

describe('extractImages', () => {
  it('pulls the images array out of a message envelope', () => {
    expect(extractImages(JSON.stringify({ text: 'hi', images: ['/tmp/a.png', '/tmp/b.png'] })))
      .toEqual(['/tmp/a.png', '/tmp/b.png']);
  });

  it('is empty for a plain-text message, malformed JSON, or a non-array images field', () => {
    expect(extractImages(JSON.stringify({ text: 'hi' }))).toEqual([]);
    expect(extractImages('not json')).toEqual([]);
    expect(extractImages(JSON.stringify({ text: 'hi', images: 'not-an-array' }))).toEqual([]);
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

  it('emits a completion marker as a summary item (not a round row)', () => {
    const items = buildTrunkTimeline([
      msg('u1', 'user', 'text', { text: 'cut a task' }, '2026-01-01T00:00:00Z'),
      msg('sum', 'assistant', 'task_summary', { text: 'Hoisted message() calls', taskId: 'task-9', taskSeq: 3 }, '2026-01-01T00:05:00Z'),
    ], [task('task-9', '2026-01-01T00:01:00Z')]);

    // round, cut, then the completion summary closes the block.
    expect(items.map(i => i.kind)).toEqual(['round', 'cut', 'summary']);
    const summary = items[2].kind === 'summary' ? items[2].summary : null;
    expect(summary?.taskId).toBe('task-9');
    expect(summary?.taskSeq).toBe(3);
    expect(summary?.text).toBe('Hoisted message() calls');
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

  it('keeps a pending permission_request in the round instead of dropping it', () => {
    const items = buildTrunkTimeline([
      msg('u', 'user', 'text', { text: 'check the branch' }, '2026-01-01T00:00:00Z'),
      msg('tx', 'assistant', 'text', { text: 'let me check' }, '2026-01-01T00:00:05Z'),
      msg('pr', 'system', 'permission_request',
        { callId: 'c1', toolName: 'Bash', summary: '{"command":"git fetch origin main"}' },
        '2026-01-01T00:00:06Z'),
    ], []);
    const round = items[0].kind === 'round' ? items[0].round : null;
    // The trailing permission_request means there's no settled headline yet —
    // both rows fold into work rather than the text row swallowing the
    // permission_request that comes after it.
    expect(trunkHeadline(round!)).toBeNull();
    expect(trunkWork(round!).map(r => r.id)).toEqual(['tx', 'pr']);
  });

  it('keeps a durable process error in the failed round', () => {
    const items = buildTrunkTimeline([
      msg('u', 'user', 'text', { text: 'inspect this' }, '2026-01-01T00:00:00Z'),
      msg('err', 'system', 'error', { message: 'permission MCP unavailable' }, '2026-01-01T00:00:05Z'),
    ], []);
    const round = items[0].kind === 'round' ? items[0].round : null;
    if (round === null) throw new Error('expected a conversation round');
    expect(trunkWork(round).map(r => r.id)).toEqual(['err']);
  });

  it('drops an already-decided permission_request from the timeline', () => {
    const items = buildTrunkTimeline([
      msg('u', 'user', 'text', { text: 'check the branch' }, '2026-01-01T00:00:00Z'),
      msg('pr', 'system', 'permission_request', { callId: 'c1', toolName: 'Bash', summary: 'git fetch' },
        '2026-01-01T00:00:05Z'),
      msg('dec', 'system', 'permission_decision', { callId: 'c1', decision: 'ALLOW' },
        '2026-01-01T00:00:10Z'),
    ], []);
    const round = items[0].kind === 'round' ? items[0].round : null;
    expect(round!.rows.some(r => r.type === 'permission_request')).toBe(false);
  });
});

describe('parsePermissionRequest', () => {
  it('reads callId / toolName / summary out of the envelope', () => {
    const parsed = parsePermissionRequest(
      JSON.stringify({ callId: 'c1', toolName: 'Bash', summary: '{"command":"git fetch origin main"}' }));
    expect(parsed).toEqual({ callId: 'c1', toolName: 'Bash', summary: '{"command":"git fetch origin main"}' });
  });

  it('falls back to blanks on unparseable input', () => {
    expect(parsePermissionRequest('not json')).toEqual({ callId: '', toolName: 'tool', summary: '' });
  });
});
