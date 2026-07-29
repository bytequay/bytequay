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

function installBridge(
  initial: WorkspaceRelationDto | null,
  candidates: unknown[] = [{
    workspaceId: 'upstream', name: 'Trino', repoFullName: 'trinodb/trino', suggested: true,
  }],
) {
  let relation = initial;
  const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
    if (request.path === '/api/workspaces/fork/relation' && request.method === undefined) return relation;
    if (request.path === '/api/workspaces/fork/relation/candidates') return candidates;
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
    render(<WorkspaceRelationsSettings workspaceId="fork" repoName="acme/cork" />);

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

  it('centres the picker and explains a dead confirm when nothing can be linked', async () => {
    installBridge(null, []);
    render(<WorkspaceRelationsSettings workspaceId="fork" repoName="acme/cork" />);

    expect(await screen.findByText('No upstream linked')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Link upstream workspace…' }));

    // The picker sits in the middle of the window, not the top-left corner.
    const dialog = await screen.findByRole('dialog');
    expect(dialog.parentElement?.className).toContain('wu-modal-backdrop--centered');

    // With nothing to select the confirm is disabled — the empty state has
    // to say why, or the button just looks broken.
    const confirm = screen.getByRole('button', { name: 'Link upstream' }) as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);
    expect(screen.getByRole('status').textContent).toMatch(/another workspace in this app/);
  });

  it('blocks a workspace that already reads from this one, and says why', async () => {
    installBridge(null, [
      {
        workspaceId: 'gateway', name: 'trino-gateway', repoFullName: 'trinodb/trino-gateway',
        suggested: true, ineligibleReason: 'already reads from this workspace, so linking back would make a cycle',
      },
    ]);
    render(<WorkspaceRelationsSettings workspaceId="fork" repoName="acme/cork" />);

    expect(await screen.findByText('No upstream linked')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Link upstream workspace…' }));

    // Offered but disabled with the reason — not silently missing, and
    // not selectable into a PUT the server would refuse.
    expect(await screen.findByText(/already reads from this workspace/)).toBeTruthy();
    expect((screen.getByRole('radio') as HTMLInputElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'Link upstream' }) as HTMLButtonElement)
      .disabled).toBe(true);
  });

  it('surfaces the server sentence from a wrapped bridge rejection', async () => {
    const api = installBridge(null);
    api.mockImplementation(async (request: WorkspaceApiRequest) => {
      if (request.path === '/api/workspaces/fork/relation' && request.method === undefined) return null;
      if (request.path === '/api/workspaces/fork/relation/candidates') {
        return [{
          workspaceId: 'upstream', name: 'Trino', repoFullName: 'trinodb/trino', suggested: true,
        }];
      }
      throw new Error("Error invoking remote method 'workspace:api': Error: workspace request "
        + 'PUT /api/workspaces/ws/relation returned 422: {"timestamp":"2026-07-29T12:34:16Z",'
        + '"status":422,"message":"workspace upstream relations cannot form a cycle",'
        + '"path":"/api/workspaces/ws/relation"}');
    });
    render(<WorkspaceRelationsSettings workspaceId="fork" repoName="acme/cork" />);

    fireEvent.click(await screen.findByRole('button', { name: 'Link upstream workspace…' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Link upstream' }));

    expect(await screen.findByText('workspace upstream relations cannot form a cycle'))
      .toBeTruthy();
    expect(screen.queryByText(/invoking remote method/)).toBeNull();
  });

  it('persists capability changes through the single relation PUT contract', async () => {
    const api = installBridge(linked);
    render(<WorkspaceRelationsSettings workspaceId="fork" repoName="acme/cork" />);

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
