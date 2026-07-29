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
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Bridge, WorkUnitTaskDto } from '../types';
import type { LocalPR } from '../types/localPr';
import { TasksInThreadSection } from './TasksInThreadSection';

// React 19 enforces this flag before async act() works.
(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

afterEach(() => {
  cleanup();
  delete (window as unknown as { bridge?: unknown }).bridge;
});

beforeEach(() => {
  // window.confirm defaults to a no-op in jsdom; stub it true so the
  // ship-and-continue test can drive past the guard.
  window.confirm = vi.fn(() => true) as unknown as typeof window.confirm;
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

  it('routes Ship & continue against the newest non-terminal task', async () => {
    const promotion = installBridge(
      async () => [
        task({ id: 'task-1', seq: 1, status: 'COMPLETED', branchName: 'auto/first' }),
        task({ id: 'task-2', seq: 2, status: 'IDLE', branchName: 'auto/second' }),
      ],
    );

    render(<TasksInThreadSection threadId="thread-1" />);
    await waitFor(() => expect(screen.getByText('Task 2')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: /Ship & continue/i }));

    await waitFor(() => {
      // Active task is the newest non-terminal one (task-2, not the
      // already-completed task-1).
      expect(promotion.getPrForTask).toHaveBeenCalledWith('task-2');
      expect(promotion.pushLocalPr).toHaveBeenCalledWith('pr-task-2');
    });
  });

  it('surfaces a missing local PR instead of falling back to legacy ship', async () => {
    installBridge(
      async () => [task({ id: 'task-1', seq: 1, status: 'IDLE' })],
      { getPrForTask: async () => null },
    );

    render(<TasksInThreadSection threadId="thread-1" />);
    await waitFor(() => expect(screen.getByText('idle')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: /Ship & continue/i }));

    await waitFor(() => {
      expect(screen.getByText(/has no local PR to ship/)).toBeTruthy();
    });
  });

  it('disables Ship & continue when no active task remains', async () => {
    installBridge(async () => [
      task({ id: 'task-1', seq: 1, status: 'COMPLETED', branchName: 'auto/first' }),
    ]);

    render(<TasksInThreadSection threadId="thread-1" />);
    // Single-task threads drop the "Task N" badge — fall back to the
    // humanised branch label the component renders ("First").
    await waitFor(() => expect(screen.getByText('First')).toBeTruthy());

    const button = screen.getByRole('button', { name: /Ship & continue/i });
    expect(button.hasAttribute('disabled')).toBe(true);
  });
});

function installBridge(
  handler: Bridge['listTasksForThread'],
  overrides: Partial<Pick<Bridge, 'getPrForTask' | 'pushLocalPr'>> = {},
) {
  const listTasksForThread = vi.fn((id: string) => handler(id));
  const getPrForTask = vi.fn(overrides.getPrForTask
    ?? (async (taskId: string) => localPr(taskId)));
  const pushLocalPr = vi.fn(overrides.pushLocalPr
    ?? (async (prId: string) => localPr(prId.replace(/^pr-/, ''))));
  (window as unknown as {
    bridge: Pick<Bridge, 'listTasksForThread' | 'getPrForTask' | 'pushLocalPr'>;
  }).bridge = {
    listTasksForThread: listTasksForThread as Bridge['listTasksForThread'],
    getPrForTask: getPrForTask as Bridge['getPrForTask'],
    pushLocalPr: pushLocalPr as Bridge['pushLocalPr'],
  };
  return { getPrForTask, pushLocalPr };
}

function localPr(taskId: string): LocalPR {
  return {
    id: `pr-${taskId}`,
    taskId,
    branchName: 'auto/task',
    baseBranch: 'main',
    title: 'Task PR',
    description: '',
    status: 'local-open',
    createdAt: 0,
    pushedAt: null,
    remotePrNumber: null,
    remotePrUrl: null,
    mergedAt: null,
    closedAt: null,
    origin: 'task',
    repo: null,
    author: null,
    syncedAt: null,
    syncedAdditions: null,
    syncedDeletions: null,
    syncedMergeable: null,
    syncedMergeableState: null,
    syncedMergeQueueEnabled: false,
    syncedMergeQueueState: null,
    branchDeletedAt: null,
  };
}

function task(overrides: Partial<WorkUnitTaskDto>): WorkUnitTaskDto {
  return {
    workModel: null,
    id: 'task-' + (overrides.seq ?? 1),
    threadId: 'thread-1',
    seq: 1,
    status: 'IDLE',
    branchName: null,
    worktreePath: null,
    baseBranch: 'main',
    workingDir: null,
    prNumber: null,
    prState: null,
    ciState: null,
    taskType: 'DEVELOP',
    linkedPrNumber: null,
    linkedIssueNumber: null,
    costUsdMilli: 0,
    tokensIn: 0,
    tokensOut: 0,
    createdAt: '2026-05-15T12:00:00Z',
    name: null,
    ...overrides,
  } as WorkUnitTaskDto;
}
