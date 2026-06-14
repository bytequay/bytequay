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
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import TrunkChat from './TrunkChat';
import type { ThreadMessageDto } from '../types';

let seq = 0;
function msg(role: string, type: string, content: object): ThreadMessageDto {
  seq += 1;
  return {
    id: `m-${seq}`,
    threadId: 't1',
    taskId: null,
    seq,
    role,
    type,
    contentJson: JSON.stringify(content),
    durationMs: null,
    tokensIn: null,
    tokensOut: null,
    costUsdMilli: null,
    ts: new Date(Date.UTC(2026, 5, 14, 12, 0, seq)).toISOString(),
  };
}

function renderTrunk(messages: ThreadMessageDto[]) {
  return render(
    <TrunkChat
      messages={messages}
      tasks={[]}
      foregroundTaskId={null}
      userInitials="CJ"
      onOpenTask={() => {}}
    />,
  );
}

describe('TrunkChat tool-activity badge', () => {
  afterEach(cleanup);

  it('rolls the turn’s hidden tool calls onto its final answer', () => {
    seq = 0;
    renderTrunk([
      msg('user', 'text', { text: 'find my PRs' }),
      msg('assistant', 'text', { text: 'Let me check.' }),
      msg('tool', 'tool_call', { toolName: 'list_prs', input: {} }),
      msg('tool', 'tool_result', { output: '...' }),
      msg('tool', 'tool_call', { toolName: 'read_pr', input: {} }),
      msg('assistant', 'text', { text: 'Here is the breakdown.' }),
      msg('system', 'turn_done', {}),
    ]);

    // Two tool calls in the turn, no files → badge on the final answer only.
    expect(screen.getByText('· 2 tool calls')).toBeTruthy();
    // The intermediate "Let me check." answer carries no badge.
    expect(screen.queryByText('· 1 tool call')).toBeNull();
  });

  it('counts distinct files touched and singularises the labels', () => {
    seq = 0;
    renderTrunk([
      msg('user', 'text', { text: 'tweak a file' }),
      msg('tool', 'tool_call', { toolName: 'Read', input: { file_path: '/repo/A.java' } }),
      msg('tool', 'tool_call', { toolName: 'Edit', input: { file_path: '/repo/A.java' } }),
      msg('assistant', 'text', { text: 'Done.' }),
      msg('system', 'turn_done', {}),
    ]);

    // Same path twice → 1 distinct file; "1 file touched" stays singular.
    expect(screen.getByText('· 2 tool calls · 1 file touched')).toBeTruthy();
  });

  it('shows no badge for a pure-conversation turn', () => {
    seq = 0;
    renderTrunk([
      msg('user', 'text', { text: 'hi' }),
      msg('assistant', 'text', { text: 'hello' }),
      msg('system', 'turn_done', {}),
    ]);

    expect(screen.queryByText(/tool call/)).toBeNull();
  });
});
