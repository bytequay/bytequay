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
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { BacklogItemDto, StartDevelopmentResponse } from '../types';
import WorkspaceBacklogPage from './WorkspaceBacklogPage';

afterEach(() => {
  cleanup();
  delete (window as unknown as { bridge?: unknown }).bridge;
});

function item(over: Partial<BacklogItemDto>): BacklogItemDto {
  return {
    id: 'b1',
    threadId: 't1',
    workspaceId: 'w1',
    title: 'Reply templates',
    body: 'draft from a template',
    tags: ['enhancement'],
    priority: 'low',
    source: 'manual',
    status: 'created',
    createdBy: 'user',
    createdAt: 0,
    inProgressAt: null,
    startedAt: null,
    resolvedAt: null,
    rejectedAt: null,
    rejectionReason: null,
    linkedTaskId: null,
    relatedBacklogIds: [],
    ...over,
  };
}

describe('WorkspaceBacklogPage', () => {
  it('renders cards with the thread chip, source badge, and status', async () => {
    const listWorkspaceBacklog = vi.fn(async (): Promise<BacklogItemDto[]> => [
      item({ id: 'b1', status: 'created', source: 'manual' }),
      item({ id: 'b2', title: 'Cost rollup', status: 'in-progress', source: 'trunk-split', threadId: 't2' }),
    ]);
    window.bridge = { listWorkspaceBacklog } as unknown as typeof window.bridge;

    render(
      <WorkspaceBacklogPage
        workspaceId="w1"
        threadNames={new Map([['t1', 'Backend cleanup'], ['t2', 'Cost-meter telemetry']])}
      />,
    );

    await waitFor(() => expect(screen.getByText('Reply templates')).toBeTruthy());
    expect(screen.getByText('Backend cleanup')).toBeTruthy();
    expect(screen.getByText('Cost-meter telemetry')).toBeTruthy();
    expect(screen.getByText('manual')).toBeTruthy();
    expect(screen.getByText('trunk-split')).toBeTruthy();
    // The in-progress item shows the exploring chip, not a Start button.
    expect(screen.getByText('↗ Trunk exploring')).toBeTruthy();
    expect(listWorkspaceBacklog).toHaveBeenCalledWith('w1', { status: undefined, q: undefined });
  });

  it('re-queries with the status filter when a pill is clicked', async () => {
    const listWorkspaceBacklog = vi.fn(async (): Promise<BacklogItemDto[]> => [item({})]);
    window.bridge = { listWorkspaceBacklog } as unknown as typeof window.bridge;

    render(<WorkspaceBacklogPage workspaceId="w1" />);
    await waitFor(() => expect(screen.getByText('Reply templates')).toBeTruthy());

    fireEvent.click(screen.getByText('Resolved'));
    await waitFor(() =>
      expect(listWorkspaceBacklog).toHaveBeenCalledWith('w1', { status: 'resolved', q: undefined }));
  });

  it('starts development on a created item', async () => {
    const listWorkspaceBacklog = vi.fn(async (): Promise<BacklogItemDto[]> => [item({})]);
    const startBacklogDevelopment = vi.fn(
      async (): Promise<StartDevelopmentResponse> => ({ item: item({}), taskId: null }));
    window.bridge = { listWorkspaceBacklog, startBacklogDevelopment } as unknown as typeof window.bridge;

    render(<WorkspaceBacklogPage workspaceId="w1" />);
    await waitFor(() => expect(screen.getByText('Start →')).toBeTruthy());

    fireEvent.click(screen.getByText('Start →'));
    await waitFor(() => expect(startBacklogDevelopment).toHaveBeenCalledWith('b1'));
  });
});
