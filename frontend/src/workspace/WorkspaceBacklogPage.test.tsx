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
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { WorkspaceApiRequest } from '../types';
import WorkspaceBacklogPage from './WorkspaceBacklogPage';
import type {
  WorkspaceBacklogItemDto,
  WorkspaceTrunkDto,
} from './workspaceApi';

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, 'bridge');
});

function item(overrides: Partial<WorkspaceBacklogItemDto> = {}): WorkspaceBacklogItemDto {
  return {
    id: 'b1',
    key: 'BQ-1',
    threadId: 't1',
    workspaceId: 'w1',
    title: 'Reply templates',
    summary: 'Draft from a reusable template',
    detail: 'Preserve the original long-form proposal.',
    impactRisk: null,
    body: 'draft from a template',
    tags: ['enhancement'],
    priority: 'low',
    source: 'manual',
    status: 'open',
    createdBy: 'user',
    origin: 'user',
    createdAt: 0,
    inProgressAt: null,
    startedAt: null,
    resolvedAt: null,
    rejectedAt: null,
    rejectionReason: null,
    linkedTaskId: null,
    relatedBacklogIds: [],
    links: [{ type: 'trunk', id: 't1' }],
    ...overrides,
  };
}

const TRUNKS: WorkspaceTrunkDto[] = [{
  id: 't1',
  workspaceId: 'w1',
  title: 'Backend cleanup',
  kind: 'dev',
  status: 'ACTIVE',
  provider: null,
  model: null,
  prRef: null,
  costUsdMilli: 0,
  tokensIn: 0,
  tokensOut: 0,
  createdAt: 1,
  updatedAt: 1,
  endedAt: null,
}];

function mockBridge(
  rows: WorkspaceBacklogItemDto[],
  linkRows: WorkspaceBacklogItemDto[] = rows,
  workspaceTrunks: WorkspaceTrunkDto[] = TRUNKS,
) {
  let backlogReads = 0;
  const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
    if (request.path === '/api/workspaces/w1/backlog') return backlogReads++ === 0 ? rows : linkRows;
    if (request.path === '/api/workspaces/w1/trunks') return workspaceTrunks;
    if (request.path.endsWith('/start')) return { item: rows[0], taskId: 'task-1' };
    if (request.path === '/api/workspaces/w1/backlog/BQ-1' && request.method === 'PATCH') return rows[0];
    throw new Error(`Unexpected workspace request: ${request.path}`);
  });
  (window as unknown as { bridge: unknown }).bridge = { workspaceApi };
  return workspaceApi;
}

describe('WorkspaceBacklogPage', () => {
  it('renders structured items with workspace keys, source, and lifecycle state', async () => {
    const workspaceApi = mockBridge([
      item(),
      item({
        id: 'b2',
        key: 'BQ-2',
        title: 'Cost rollup',
        summary: 'Aggregate cost by provider',
        status: 'in-progress',
        source: 'agent',
        createdBy: 'trunk-agent',
        origin: 'agent',
        threadId: 't2',
        links: [{ type: 'trunk', id: 't2' }],
      }),
    ]);

    render(
      <WorkspaceBacklogPage
        workspaceId="w1"
        threadNames={new Map([['t1', 'Backend cleanup'], ['t2', 'Cost-meter telemetry']])}
      />,
    );

    expect(await screen.findByText('Reply templates')).toBeTruthy();
    expect(screen.getByText(/BQ-1/)).toBeTruthy();
    expect(screen.getByText('User')).toBeTruthy();
    expect(screen.getByText('Agent')).toBeTruthy();
    expect(screen.getByText('from Cost-meter telemetry')).toBeTruthy();
    expect(screen.getByText('trunk exploring')).toBeTruthy();
    expect(workspaceApi).toHaveBeenCalledWith({ path: '/api/workspaces/w1/backlog' });
  });

  it('renders the persisted origin instead of inferring it from editable tags', async () => {
    mockBridge([item({
      tags: ['quality-scan'],
      source: 'agent',
      createdBy: 'trunk-agent',
      origin: 'issue-monitor',
    })]);

    render(<WorkspaceBacklogPage workspaceId="w1" />);

    expect(await screen.findByText('Issue monitor')).toBeTruthy();
    expect(screen.queryByText('Quality scan')).toBeNull();
  });

  it('filters the loaded workspace backlog without another request', async () => {
    const workspaceApi = mockBridge([
      item(),
      item({
        id: 'b2',
        key: 'BQ-2',
        title: 'Shipped telemetry',
        summary: 'Done',
        status: 'resolved',
      }),
    ]);
    render(<WorkspaceBacklogPage workspaceId="w1" />);
    await screen.findByText('Reply templates');

    fireEvent.click(screen.getByRole('tab', { name: 'Resolved' }));
    expect(screen.queryByText('Reply templates')).toBeNull();
    expect(screen.getByText('Shipped telemetry')).toBeTruthy();
    expect(workspaceApi.mock.calls.filter(([request]) =>
      (request as WorkspaceApiRequest).path === '/api/workspaces/w1/backlog')).toHaveLength(1);
  });

  it('labels linked backlog items with their loaded titles instead of ids', async () => {
    mockBridge([
      item({ links: [
        { type: 'trunk', id: 't1' },
        { type: 'trunk', id: 't2' },
        { type: 'backlog', id: 'b2' },
      ] }),
      item({
        id: 'b2',
        key: 'BQ-2',
        title: 'Preserve cache evidence',
        summary: 'Keep evidence after refresh',
        status: 'discarded',
      }),
    ]);
    render(<WorkspaceBacklogPage workspaceId="w1" />);

    fireEvent.click(await screen.findByText('Reply templates'));

    expect(screen.getByText('Preserve cache evidence')).toBeTruthy();
    expect(screen.getByText('linked thread')).toBeTruthy();
    expect(screen.queryByText(/Backlog b2/)).toBeNull();
  });

  it('hides a duplicated summary and exposes separate link actions', async () => {
    const current = item({ summary: 'Reply templates' });
    const other = item({
      id: 'b2',
      key: 'BQ-2',
      title: 'Preserve cache evidence',
      summary: 'Keep evidence after refresh',
    });
    const otherTrunk: WorkspaceTrunkDto = {
      ...TRUNKS[0],
      id: 't2',
      title: 'Frontend follow-up',
    };
    const workspaceApi = mockBridge([current], [current, other], [...TRUNKS, otherTrunk]);
    window.bridge.listTasksForThread = vi.fn().mockResolvedValue([{
      id: 'task-9',
      seq: 2,
      name: 'Rank evidence bundles',
    }]);
    render(<WorkspaceBacklogPage workspaceId="w1" />);

    fireEvent.click(await screen.findByText('Reply templates'));
    const dialog = screen.getByRole('dialog', { name: 'Backlog item BQ-1' });
    expect(screen.queryByRole('textbox', { name: 'Summary' })).toBeNull();
    expect(screen.getByRole('button', { name: /Link thread/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /Link task/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /Link other item/ })).toBeTruthy();

    const trunkReads = workspaceApi.mock.calls.filter(([request]) =>
      (request as WorkspaceApiRequest).path === '/api/workspaces/w1/trunks').length;
    fireEvent.click(screen.getByRole('button', { name: /Link thread/ }));
    const threadPicker = await screen.findByRole('combobox', { name: 'Link thread' });
    expect(within(threadPicker).getByRole('option', { name: 'Frontend follow-up' })).toBeTruthy();
    expect(workspaceApi.mock.calls.filter(([request]) =>
      (request as WorkspaceApiRequest).path === '/api/workspaces/w1/trunks')).toHaveLength(trunkReads + 1);
    fireEvent.change(threadPicker, { target: { value: 't2' } });
    expect(within(dialog).getByText(/Frontend follow-up/)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /Link other item/ }));
    const itemPicker = await screen.findByRole('combobox', { name: 'Link other item' });
    fireEvent.change(itemPicker, { target: { value: 'b2' } });
    expect(within(dialog).getByText('Preserve cache evidence')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /Link task/ }));
    const taskPicker = await screen.findByRole('combobox', { name: 'Link task' });
    expect(within(taskPicker).getByRole('option', { name: 'Rank evidence bundles' })).toBeTruthy();
    fireEvent.change(taskPicker, { target: { value: 'task-9' } });
    expect(within(dialog).getByText(/Task #9/)).toBeTruthy();

    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(workspaceApi).toHaveBeenCalledWith(expect.objectContaining({
      path: '/api/workspaces/w1/backlog/BQ-1',
      method: 'PATCH',
      body: expect.objectContaining({
        links: expect.arrayContaining([
          { type: 'backlog', id: 'b2' },
          { type: 'task', id: 'task-9' },
        ]),
      }),
    })));
  });

  it('starts work through the shared trunk picker', async () => {
    const workspaceApi = mockBridge([item()]);
    const onOpenThread = vi.fn();
    render(<WorkspaceBacklogPage workspaceId="w1" onOpenThread={onOpenThread} />);

    fireEvent.click(await screen.findByRole('button', { name: 'Start work under a thread' }));
    fireEvent.click(screen.getByRole('button', { name: /Backend cleanup/ }));

    await waitFor(() =>
      expect(workspaceApi).toHaveBeenCalledWith({
        path: '/api/workspaces/w1/backlog/BQ-1/start',
        method: 'POST',
        body: { trunkId: 't1' },
      }));
    expect(onOpenThread).toHaveBeenCalledWith('t1');
  });
});
