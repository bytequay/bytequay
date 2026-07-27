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
import type { ThreadDto } from '../types';
import { WorkspaceThreadsSurface } from './WorkspaceThreadsSurface';

afterEach(() => { cleanup(); Reflect.deleteProperty(window, 'bridge'); });

function thread(over: Partial<ThreadDto> = {}): ThreadDto {
  return {
    id: 't1', kind: 'TASK', provider: 'claude', agentSessionId: null,
    title: 'Backend cleanup review', status: 'RUNNING', flow: 'build',
    model: 'opus', costUsdMilli: 0, tokensIn: 0, tokensOut: 0,
    createdAt: '2026-06-24T00:00:00Z', updatedAt: '2026-06-24T00:00:00Z',
    endedAt: null, errorMessage: null, workspaceId: 'bq',
    workModel: null, parallelSlots: 1,
    ...over,
  } as ThreadDto;
}

describe('WorkspaceThreadsSurface', () => {
  it('renders a thread card with trunk tile, title, meta chips and active count', () => {
    const { container } = render(
      <WorkspaceThreadsSurface
        threads={[thread({ description: 'Owns backend cleanup work.' })]}
        loading={false}
      />,
    );
    expect(screen.getByRole('heading', { name: 'Trunks' })).toBeTruthy();
    expect(screen.getByText('1 open · 1 active')).toBeTruthy();
    expect(screen.getByText('Backend cleanup review').getAttribute('title'))
      .toBe('Owns backend cleanup work.');
    expect(screen.getByText('agent running')).toBeTruthy();
    expect(screen.getByText('Agent is working in this trunk')).toBeTruthy();
    expect(container.querySelector('.thread-card .tile svg')).toBeTruthy();
  });

  it('hides terminal threads and shows the empty state when none are open', () => {
    render(
      <WorkspaceThreadsSurface
        threads={[thread({ id: 't9', status: 'COMPLETED' })]}
        loading={false}
      />,
    );
    expect(screen.queryByText('Backend cleanup review')).toBeNull();
    expect(screen.getByText(/No trunks match this view/)).toBeTruthy();
  });

  it('hides legacy review-flow threads from trunks', () => {
    render(
      <WorkspaceThreadsSurface
        threads={[
          thread({ id: 'dev', title: 'Real development trunk' }),
          thread({ id: 'review', title: 'Review acme/widget#42', flow: 'review' }),
        ]}
        loading={false}
      />,
    );
    expect(screen.getByText('Real development trunk')).toBeTruthy();
    expect(screen.queryByText('Review acme/widget#42')).toBeNull();
    expect(screen.getByText('1 open · 1 active')).toBeTruthy();
  });

  it('shows the loading hint before data arrives', () => {
    render(<WorkspaceThreadsSurface threads={[]} loading />);
    expect(screen.getByText('Loading trunks…')).toBeTruthy();
  });

  it('routes a card click to onOpenThread', () => {
    const onOpenThread = vi.fn();
    render(<WorkspaceThreadsSurface threads={[thread()]} loading={false} onOpenThread={onOpenThread} />);
    fireEvent.click(screen.getByText('Backend cleanup review'));
    expect(onOpenThread).toHaveBeenCalledWith('t1');
  });

  it('renders a zero-task trunk without a task-count chip', async () => {
    (window as unknown as { bridge: unknown }).bridge = {
      listTasksForThread: vi.fn().mockResolvedValue([]),
    };
    render(
      <WorkspaceThreadsSurface
        threads={[thread({ id: 't2', title: 'View prs' })]}
        loading={false}
      />,
    );
    expect(await screen.findByText('Agent is working in this trunk')).toBeTruthy();
    expect(document.querySelector('.wu-trunk-count')).toBeNull();
  });

  it('shows a task-count pill with the headline status on task-bearing threads', async () => {
    const listTasksForThread = vi.fn().mockResolvedValue([
      { id: 'k1', threadId: 't1', status: 'COMPLETED' },
      { id: 'k2', threadId: 't1', status: 'RUNNING' },
    ]);
    (window as unknown as { bridge: unknown }).bridge = { listTasksForThread };
    const { container } = render(
      <WorkspaceThreadsSurface threads={[thread()]} loading={false} />,
    );
    expect(await screen.findByText('2 tasks')).toBeTruthy();
    expect(container.querySelector('.wu-trunk-count')).toBeTruthy();
    expect(screen.getByText('agent running')).toBeTruthy();
    expect(listTasksForThread).toHaveBeenCalledWith('t1');
  });

  it('filters automated tasks without attributing their parent thread', async () => {
    const listTasksForThread = vi.fn(async (threadId: string) => threadId === 'auto'
      ? [{ id: 'k1', threadId, status: 'RUNNING', origin: 'issue-monitor' }]
      : [{ id: 'k2', threadId, status: 'IDLE', origin: 'user' }]);
    (window as unknown as { bridge: unknown }).bridge = { listTasksForThread };
    render(
      <WorkspaceThreadsSurface
        threads={[
          thread({ id: 'auto', title: 'Issue triage' }),
          thread({ id: 'human', title: 'Manual cleanup', status: 'IDLE' }),
        ]}
        loading={false}
      />,
    );

    expect(await screen.findAllByText('1 task')).toHaveLength(2);
    expect(screen.queryByText('Issue monitor')).toBeNull();
    fireEvent.click(screen.getByText('Automated'));
    expect(screen.getByText('Issue triage')).toBeTruthy();
    expect(screen.queryByText('Manual cleanup')).toBeNull();
  });
});
