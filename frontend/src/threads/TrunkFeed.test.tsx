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
import type { ThreadMessageDto, WorkUnitTaskDto } from '../types';

afterEach(cleanup);

function msg(id: string, role: string, type: string, body: unknown, ts: string): ThreadMessageDto {
  return {
    id, threadId: 't', taskId: null, seq: 0, role, type,
    contentJson: JSON.stringify(body), durationMs: null, tokensIn: null, tokensOut: null, costUsdMilli: null, ts,
  };
}

function task(
  id: string, seq: number, name: string, status: string, createdAt: string,
): WorkUnitTaskDto {
  return { id, seq, name, status, createdAt } as unknown as WorkUnitTaskDto;
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

  it('folds a task by its own identity, not by whichever summary lands next — concurrent tasks stay visible', () => {
    // Regression: task2 and task3 both start (and task3's own cut lands)
    // BEFORE task2's own completion summary is finally recorded — tasks run
    // concurrently, so a later task routinely starts before an earlier one
    // finishes. The old grouping ("everything before the next summary
    // encountered belongs to it") swallowed task3's cut into task2's fold,
    // making it disappear entirely.
    const messages: ThreadMessageDto[] = [
      msg('sum1', 'assistant', 'task_summary', { text: 'Shipped task 1.', taskId: 't1', taskSeq: 1 },
        '2026-01-01T01:00:00Z'),
      // task3 (created below) starts before task2's summary lands:
      msg('sum2', 'assistant', 'task_summary', { text: 'Shipped task 2.', taskId: 't2', taskSeq: 2 },
        '2026-01-03T00:00:00Z'),
    ];
    const tasks = [
      task('t1', 1, 'Task one', 'COMPLETED', '2026-01-01T00:00:00Z'),
      task('t2', 2, 'Task two', 'COMPLETED', '2026-01-01T00:30:00Z'),
      task('t3', 3, 'Task three', 'IN_REVIEW', '2026-01-02T00:00:00Z'),
      task('t4', 4, 'Task four', 'IDLE', '2026-01-02T01:00:00Z'),
    ];
    const { container } = render(
      <TrunkFeed messages={messages} tasks={tasks} density="focused" onOpenTask={() => {}} />,
    );

    // task1 and task2 are done — blue folds.
    expect(container.querySelectorAll('.sp-taskfold--done').length).toBe(2);
    // task3 and task4 both have no summary yet — each still gets its own
    // green "running" fold instead of vanishing into task2's, or staying
    // unfolded as "the current task". Every cut folds immediately; there's
    // no such exception.
    const runningFolds = container.querySelectorAll('.sp-taskfold--running');
    expect(runningFolds.length).toBe(2);
    expect(runningFolds[0].textContent).toContain('Task three');
    expect(runningFolds[1].textContent).toContain('Task four');
  });
});
