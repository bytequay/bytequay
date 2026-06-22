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
import type { ThreadMessageDto } from '../types';
import { deriveTrunkActivity } from './trunkActivity';

let seq = 0;
function msg(role: string, type: string, contentJson = '{}'): ThreadMessageDto {
  return {
    id: `m${seq}`, threadId: 't', taskId: null, seq: seq++,
    role, type, contentJson,
    durationMs: null, tokensIn: null, tokensOut: null, costUsdMilli: null,
    ts: '2026-06-22T10:00:00Z',
  };
}

const perm = { callId: 'c1', toolName: 'run_shell', summary: '' };

describe('deriveTrunkActivity', () => {
  it('prioritises a pending approval over model activity', () => {
    const messages = [msg('assistant', 'thinking')];
    expect(deriveTrunkActivity(messages, perm, false)).toEqual({
      meta: 'needs approval',
      text: 'Waiting for your approval to run run_shell…',
    });
  });

  it('prioritises a pending question over model activity', () => {
    expect(deriveTrunkActivity([msg('assistant', 'thinking')], null, true)).toEqual({
      meta: 'needs answer',
      text: 'Waiting for your answer…',
    });
  });

  it('reports the tool name while a tool call is the newest row', () => {
    const messages = [
      msg('user', 'text'),
      msg('assistant', 'thinking'),
      msg('assistant', 'tool_call', JSON.stringify({ toolName: 'read_file' })),
    ];
    expect(deriveTrunkActivity(messages, null, false)).toEqual({
      meta: 'running a tool',
      text: 'Calling read_file…',
    });
  });

  it('reports thinking when a thinking row is newest', () => {
    expect(deriveTrunkActivity([msg('assistant', 'thinking')], null, false))
      .toEqual({ meta: 'thinking', text: 'Thinking…' });
  });

  it('reports writing when an assistant text row is newest', () => {
    expect(deriveTrunkActivity([msg('assistant', 'text')], null, false))
      .toEqual({ meta: 'writing', text: 'Writing the reply…' });
  });

  it('falls back to Working between tools (tool_result newest)', () => {
    expect(deriveTrunkActivity([msg('tool', 'tool_result')], null, false))
      .toEqual({ meta: 'thinking', text: 'Working…' });
  });

  it('falls back to Working when only the user prompt exists', () => {
    expect(deriveTrunkActivity([msg('user', 'text')], null, false))
      .toEqual({ meta: 'working', text: 'Working…' });
  });
});
