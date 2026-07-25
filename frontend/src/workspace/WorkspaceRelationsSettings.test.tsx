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
import type { WorkspaceApiRequest } from '../types';
import WorkspaceRelationsSettings from './WorkspaceRelationsSettings';
import type { WorkspaceRelationDto } from './workspaceApi';

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, 'bridge');
  window.location.hash = '';
});

const linked: WorkspaceRelationDto = {
  workspaceId: 'fork',
  upstreamWorkspaceId: 'upstream',
  upstreamWorkspaceName: 'Trino',
  upstreamRepoFullName: 'trinodb/trino',
  commitsEnabled: true,
  tagsEnabled: true,
  branchesEnabled: false,
  issuesPullRequestsEnabled: false,
  lastFetchedAt: '2026-07-24T10:00:00Z',
  autoFetchIntervalMinutes: 30,
  indexedCommitCount: 3481,
};

function installBridge(initial: WorkspaceRelationDto | null) {
  let relation = initial;
  const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
    if (request.path === '/api/workspaces/fork/relation' && request.method === undefined) return relation;
    if (request.path === '/api/workspaces/fork/relation/candidates') return [{
      workspaceId: 'upstream', name: 'Trino', repoFullName: 'trinodb/trino', suggested: true,
    }];
    if (request.path === '/api/workspaces/fork/relation' && request.method === 'PUT') {
      const body = request.body as {
        upstreamWorkspaceId: string;
        commitsEnabled: boolean;
        tagsEnabled: boolean;
        autoFetchIntervalMinutes: number;
      };
      relation = { ...linked, ...body };
      return relation;
    }
    if (request.path === '/api/workspaces/fork/relation/fetch') return relation;
    if (request.path === '/api/workspaces/fork/relation' && request.method === 'DELETE') {
      relation = null;
      return null;
    }
    throw new Error(`Unexpected request: ${request.path}`);
  });
  (window as unknown as { bridge: unknown }).bridge = { workspaceApi };
  return workspaceApi;
}

describe('WorkspaceRelationsSettings', () => {
  it('links the fork-parent suggestion from the empty state', async () => {
    const api = installBridge(null);
    render(<WorkspaceRelationsSettings workspaceId="fork" repoName="acme/widget" />);

    expect(await screen.findByText('No upstream linked')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Link upstream workspace…' }));
    expect(await screen.findByText('trinodb/trino')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Link upstream' }));

    expect(await screen.findByText('Trino')).toBeTruthy();
    expect(api).toHaveBeenCalledWith({
      path: '/api/workspaces/fork/relation',
      method: 'PUT',
      body: {
        upstreamWorkspaceId: 'upstream', commitsEnabled: true, tagsEnabled: true,
        autoFetchIntervalMinutes: 30,
      },
    });
  });

  it('persists capability changes through the single relation PUT contract', async () => {
    const api = installBridge(linked);
    render(<WorkspaceRelationsSettings workspaceId="fork" repoName="acme/widget" />);

    const tags = await screen.findByRole('switch', { name: 'Tags' });
    fireEvent.click(tags);
    await waitFor(() => expect(api).toHaveBeenCalledWith({
      path: '/api/workspaces/fork/relation',
      method: 'PUT',
      body: {
        upstreamWorkspaceId: 'upstream', commitsEnabled: true, tagsEnabled: false,
        autoFetchIntervalMinutes: 30,
      },
    }));
    expect((screen.getByRole('switch', { name: 'Branches' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('switch', { name: 'Issues & pull requests' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('THIS WORKSPACE')).toBeTruthy();
    expect(screen.getByText('READS FROM')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Open workspace' }));
    expect(window.location.hash).toBe('#/workspace/upstream/today');
  });
});
