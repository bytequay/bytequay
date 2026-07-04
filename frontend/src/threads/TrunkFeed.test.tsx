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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TrunkFeed } from './TrunkFeed';
import type { ThreadMessageDto } from '../types';

afterEach(cleanup);

function msg(id: string, role: string, type: string, body: unknown, ts: string): ThreadMessageDto {
  return {
    id, threadId: 't', taskId: null, seq: 0, role, type,
    contentJson: JSON.stringify(body), durationMs: null, tokensIn: null, tokensOut: null, costUsdMilli: null, ts,
  };
}

describe('TrunkFeed', () => {
  it('renders a pending permission_request as a clickable card and answers it', () => {
    const onDecidePermission = vi.fn();
    const messages = [
      msg('u', 'user', 'text', { text: 'check the branch' }, '2026-01-01T00:00:00Z'),
      msg('pr', 'system', 'permission_request',
        { callId: 'c1', toolName: 'Bash', summary: '{"command":"git fetch origin main"}' },
        '2026-01-01T00:00:05Z'),
    ];
    render(
      <TrunkFeed
        messages={messages}
        tasks={[]}
        density="focused"
        onOpenTask={() => {}}
        onDecidePermission={onDecidePermission}
      />,
    );

    expect(screen.getByText(/Approval needed/)).toBeTruthy();
    fireEvent.click(screen.getByText('Approve once'));
    expect(onDecidePermission).toHaveBeenCalledWith('c1', 'ALLOW');
  });

  it('does not render a card for an already-decided permission_request', () => {
    const messages = [
      msg('u', 'user', 'text', { text: 'check the branch' }, '2026-01-01T00:00:00Z'),
      msg('pr', 'system', 'permission_request', { callId: 'c1', toolName: 'Bash', summary: 'git fetch' },
        '2026-01-01T00:00:05Z'),
      msg('dec', 'system', 'permission_decision', { callId: 'c1', decision: 'ALLOW' },
        '2026-01-01T00:00:10Z'),
    ];
    render(<TrunkFeed messages={messages} tasks={[]} density="focused" onOpenTask={() => {}} />);
    expect(screen.queryByText(/Approval needed/)).toBeNull();
  });
});
