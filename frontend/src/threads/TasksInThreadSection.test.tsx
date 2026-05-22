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
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Bridge, WorkUnitTaskDto } from '../types';
import { TasksInThreadSection } from './TasksInThreadSection';

// React 19 enforces this flag before async act() works.
(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

afterEach(() => {
  cleanup();
  delete (window as unknown as { bridge?: unknown }).bridge;
});

describe('TasksInThreadSection', () => {
  it('renders the thread\'s tasks newest-first with a PR pill when one exists', async () => {
    installBridge(async () => [
      task({ seq: 1, status: 'COMPLETED', branchName: 'auto/init', prNumber: 100, prState: 'merged' }),
      task({ seq: 2, status: 'RUNNING', branchName: 'auto/next', prNumber: null, prState: null }),
    ]);

    render(<TasksInThreadSection threadId="thread-1" />);

    await waitFor(() => {
      expect(screen.getByText('Task 2')).toBeTruthy();
      expect(screen.getByText('Task 1')).toBeTruthy();
    });
    // Newest task (seq 2) is the first list item.
    const items = screen.getAllByRole('listitem');
    expect(items[0].textContent).toContain('Task 2');
    expect(items[1].textContent).toContain('Task 1');
    expect(items[0].textContent).toContain('auto/next');
    // PR-merged status overrides the raw TaskStatus pill so the user
    // sees the destination rather than the lifecycle state.
    expect(items[1].textContent?.toLowerCase()).toContain('merged');
  });

  it('shows a brainstorm hint when the thread has no tasks', async () => {
    installBridge(async () => []);

    render(<TasksInThreadSection threadId="thread-empty" />);

    await waitFor(() => {
      expect(screen.getByText(/brainstorm/i)).toBeTruthy();
    });
  });

  it('surfaces backend errors inline', async () => {
    installBridge(async () => { throw new Error('boom'); });

    render(<TasksInThreadSection threadId="thread-broken" />);

    await waitFor(() => {
      expect(screen.getByText(/Could not load tasks/)).toBeTruthy();
      expect(screen.getByText(/boom/)).toBeTruthy();
    });
  });
});

function installBridge(handler: Bridge['listTasksForThread']) {
  const listTasksForThread = vi.fn((id: string) => handler(id));
  (window as unknown as { bridge: Pick<Bridge, 'listTasksForThread'> }).bridge = {
    listTasksForThread: listTasksForThread as Bridge['listTasksForThread'],
  };
  return listTasksForThread;
}

function task(overrides: Partial<WorkUnitTaskDto>): WorkUnitTaskDto {
  return {
    id: 'task-' + (overrides.seq ?? 1),
    threadId: 'thread-1',
    seq: 1,
    status: 'IDLE',
    branchName: null,
    worktreePath: null,
    baseBranch: 'main',
    prNumber: null,
    prState: null,
    ciState: null,
    taskType: 'DEVELOP',
    linkedPrNumber: null,
    linkedIssueNumber: null,
    ...overrides,
  };
}
