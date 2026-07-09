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
import { TrunkPage } from './TrunkPage';
import type { BacklogItemDto, ThreadSignalDto } from '../types';

function backlog(overrides: Partial<BacklogItemDto> = {}): BacklogItemDto {
  return {
    id: 'b1', threadId: 't1', workspaceId: 'ws-1', title: 'Parked idea', body: 'do the thing',
    tags: ['ui'], priority: 'medium', source: 'manual', status: 'created', createdBy: 'user',
    createdAt: 1, inProgressAt: null, startedAt: null, resolvedAt: null, rejectedAt: null,
    rejectionReason: null, linkedTaskId: null, relatedBacklogIds: [],
    ...overrides,
  };
}

const BACKLOG: BacklogItemDto[] = [backlog()];
const SIGNALS: ThreadSignalDto[] = [
  { id: 's1', threadId: 't1', taskId: null, sourceKind: 'system', iconKind: 'success', title: 'Pushed branch', body: null, sourceUrl: null, createdAt: 2, readAt: null },
];

function mockBridge(overrides: Record<string, unknown> = {}) {
  const bridge = {
    listBacklog: vi.fn().mockResolvedValue(BACKLOG),
    listThreadSignals: vi.fn().mockResolvedValue(SIGNALS),
    createBacklogItem: vi.fn().mockResolvedValue(BACKLOG[0]),
    updateBacklogItem: vi.fn().mockResolvedValue(BACKLOG[0]),
    deleteBacklogItem: vi.fn().mockResolvedValue(undefined),
    startBacklogDevelopment: vi.fn().mockResolvedValue({ item: { ...BACKLOG[0], startedAt: 5 }, taskId: 'task-9' }),
    markSignalRead: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  };
  (window as unknown as { bridge: unknown }).bridge = bridge;
  return bridge;
}

function renderTrunk() {
  return render(
    <TrunkPage
      threadId="t1"
      thread={{ title: 'Backend cleanup', createdLabel: '3d ago' }}
      sidebar={<aside data-testid="sidebar" />}
      conversation={<div data-testid="conv">conversation</div>}
      composer={{ value: '', onChange: () => {}, onSubmit: () => {} }}
      tasks={{
        active: [{ id: 'ta', title: 'Active task', status: 'foreground' }],
        closed: [{ id: 'tc', title: 'Closed task', status: 'closed' }],
      }}
    />,
  );
}

afterEach(() => { cleanup(); vi.restoreAllMocks(); Reflect.deleteProperty(window, 'bridge'); });
beforeEach(() => { mockBridge(); });

describe('TrunkPage', () => {
  it('renders the shell, THREAD pill, title, and the Tasks tab by default', async () => {
    renderTrunk();
    expect(screen.getByTestId('sidebar')).toBeTruthy();
    expect(screen.getByTestId('conv')).toBeTruthy();
    expect(screen.getByText('THREAD')).toBeTruthy();
    expect(screen.getByText('Backend cleanup')).toBeTruthy();
    // Tasks tab active by default: Active sub-tab only shows unfinished work.
    expect(await screen.findByText('Active task')).toBeTruthy();
    expect(screen.queryByText('Closed task')).toBeNull();
    expect(screen.getByRole('button', { name: /Active/ })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /Closed/ }));
    expect(await screen.findByText('Closed task')).toBeTruthy();
  });

  it('loads backlog + signals from the bridge and shows them in their tabs', async () => {
    const bridge = mockBridge();
    renderTrunk();
    await waitFor(() => expect(bridge.listBacklog).toHaveBeenCalledWith('t1'));
    // "Backlog"/"Notifications" name both the pinned composer chip and the
    // in-pane tab; either switches the tab, so click the first match.
    fireEvent.click(screen.getAllByRole('button', { name: /Backlog/ })[0]);
    expect(await screen.findByText('Parked idea')).toBeTruthy();
    fireEvent.click(screen.getAllByRole('button', { name: /Notifications/ })[0]);
    expect(await screen.findByText('Pushed branch')).toBeTruthy();
  });

  it('Start development on a backlog item calls the bridge', async () => {
    const bridge = mockBridge();
    renderTrunk();
    fireEvent.click(screen.getAllByRole('button', { name: /Backlog/ })[0]);
    fireEvent.click(await screen.findByRole('button', { name: /Start development/ }));
    // Start development now opens a confirmation dialog; confirm to dispatch.
    fireEvent.click(await screen.findByRole('button', { name: /Send to trunk/ }));
    await waitFor(() => expect(bridge.startBacklogDevelopment).toHaveBeenCalledWith('b1'));
  });

  it('shows only ready backlog by default and keeps progressed items behind lifecycle subtabs', async () => {
    mockBridge({
      listBacklog: vi.fn().mockResolvedValue([
        backlog({ id: 'ready', title: 'Ready idea', status: 'created' }),
        backlog({
          id: 'running', title: 'Trunk is exploring', status: 'in-progress',
          inProgressAt: 5, startedAt: 5,
        }),
        backlog({
          id: 'resolved', title: 'Task was cut', status: 'resolved',
          inProgressAt: 4, startedAt: 4, resolvedAt: 6, linkedTaskId: 'task-1',
        }),
        backlog({
          id: 'stopped', title: 'Rejected idea', status: 'not-to-proceed',
          rejectedAt: 7, rejectionReason: 'not now',
        }),
      ]),
    });
    renderTrunk();

    fireEvent.click(screen.getAllByRole('button', { name: /Backlog/ })[0]);
    expect(await screen.findByText('Ready idea')).toBeTruthy();
    expect(screen.queryByText('Trunk is exploring')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /In progress/ }));
    expect(await screen.findByText('Trunk is exploring')).toBeTruthy();
    expect(screen.queryByText('Dropped')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /Done \/ stopped/ }));
    expect(await screen.findByText('Task was cut')).toBeTruthy();
    expect(screen.getByText('Rejected idea')).toBeTruthy();
    expect(screen.getByText('Dropped')).toBeTruthy();
  });

  it('opening a notification marks it read', async () => {
    const bridge = mockBridge();
    renderTrunk();
    fireEvent.click(screen.getAllByRole('button', { name: /Notifications/ })[0]);
    fireEvent.click(await screen.findByText('Pushed branch'));
    await waitFor(() => expect(bridge.markSignalRead).toHaveBeenCalledWith('s1'));
  });

  it('collapsing the right pane surfaces inline chips that reopen a tab', async () => {
    renderTrunk();
    await screen.findByText('Active task');
    // Toggle the pane closed.
    fireEvent.click(screen.getByRole('button', { name: 'Toggle right pane' }));
    // Inline chips appear; the right-pane tab strip is gone.
    const backlogChip = await screen.findByRole('button', { name: /Backlog/ });
    fireEvent.click(backlogChip);
    // Pane reopens on the Backlog tab.
    expect(await screen.findByText('Parked idea')).toBeTruthy();
  });
});
