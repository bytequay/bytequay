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
import TaskChat from './TaskChat';
import type { ThreadMessageDto } from '../types';

afterEach(cleanup);

let seq = 0;
function msg(role: string, type: string, content: object): ThreadMessageDto {
  seq += 1;
  return {
    id: `m-${seq}`,
    threadId: 't1',
    taskId: 'task-1',
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

describe('TaskChat forked-from-thread hint', () => {
  it('says "waiting for your first message" when the task has no content', () => {
    // A task cut with a blank opening prompt lands empty — it was NOT actually
    // seeded with a plan, so don't claim it was.
    render(<TaskChat messages={[]} taskSeq={1} baseBranch="main" userInitials="JC" />);

    expect(screen.getByText(/waiting for your first message · off main/)).toBeTruthy();
    expect(screen.queryByText(/seeded with the plan/)).toBeNull();
  });

  it('says "seeded with the plan" once the task has a conversation', () => {
    render(
      <TaskChat
        messages={[msg('user', 'text', { text: 'do the thing' })]}
        taskSeq={1}
        baseBranch="main"
        userInitials="JC"
      />,
    );

    expect(screen.getByText(/seeded with the plan · off main/)).toBeTruthy();
    expect(screen.queryByText(/waiting for your first message/)).toBeNull();
  });

  it('treats an in-flight (working) task as seeded even before messages land', () => {
    render(
      <TaskChat messages={[]} taskSeq={1} baseBranch="main" userInitials="JC" isInFlight />,
    );

    expect(screen.getByText(/seeded with the plan · off main/)).toBeTruthy();
  });

  it('labels the assistant by the thread work model (Codex), not a hardcoded Claude', () => {
    render(
      <TaskChat
        messages={[msg('assistant', 'text', { text: 'on it' })]}
        taskSeq={1}
        baseBranch="main"
        userInitials="JC"
        thread={{
          workModel: { kind: 'CLI', agentOrProvider: 'codex', model: null, account: null },
          provider: 'codex',
          model: null,
        } as unknown as import('../types').ThreadDto}
      />,
    );

    expect(screen.getByText('Codex')).toBeTruthy();
    expect(screen.queryByText('Claude')).toBeNull();
  });

  it('renders messages queued mid-turn as pending bubbles, not lost', () => {
    render(
      <TaskChat
        messages={[msg('user', 'text', { text: 'first' })]}
        taskSeq={1}
        baseBranch="main"
        userInitials="JC"
        isInFlight
        queuedMessages={['fix the build', 'then update docs']}
      />,
    );

    expect(screen.getByText('fix the build')).toBeTruthy();
    expect(screen.getByText('then update docs')).toBeTruthy();
    // Both carry the "queued · sends next" pending tag.
    expect(screen.getAllByText(/queued · sends next/i)).toHaveLength(2);
  });
});
