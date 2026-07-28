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
import WorkspaceCommitsPage from './WorkspaceCommitsPage';
import type { WorkspaceRelationDto, WorkspaceRepositoryDto } from './workspaceApi';

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, 'bridge');
  window.location.hash = '';
});

const linked: WorkspaceRelationDto = {
  workspaceId: 'fork', upstreamWorkspaceId: 'upstream', upstreamWorkspaceName: 'Trino',
  upstreamRepoFullName: 'trinodb/trino', commitsEnabled: true, tagsEnabled: true,
  branchesEnabled: false, issuesPullRequestsEnabled: false,
  lastFetchedAt: '2026-07-24T10:00:00Z', autoFetchIntervalMinutes: 30, indexedCommitCount: 3481,
};

const repo = {
  owner: 'chenjian2664', repo: 'cork', fullName: 'chenjian2664/cork', defaultBaseBranch: 'master',
  local: { currentBranch: 'master', defaultBranch: 'origin/master', localClonePath: '/repo/cork' },
} as unknown as WorkspaceRepositoryDto;

function installBridge(relation: WorkspaceRelationDto | null) {
  let current = relation;
  const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
    if (request.path === '/api/workspaces/fork/relation' && request.method === undefined) return current;
    if (request.path === '/api/workspaces/fork/relation' && request.method === 'PUT') {
      current = linked;
      return current;
    }
    if (request.path === '/api/workspaces/fork/relation/candidates') {
      return [{ workspaceId: 'upstream', name: 'Trino', repoFullName: 'trinodb/trino', suggested: true }];
    }
    if (request.path.startsWith('/api/workspaces/fork/branches')) return [];
    if (request.path.startsWith('/api/workspaces/fork/commits')) return [];
    if (request.path.startsWith('/api/workspaces/fork/upstream/commits')) {
      return {
        upstreamWorkspaceName: 'Trino', upstreamRepoFullName: 'trinodb/trino',
        revision: 'master', lastFetchedAt: null, notInForkCount: 3481, commits: [],
      };
    }
    return null;
  });
  (window as unknown as { bridge: unknown }).bridge = { workspaceApi };
  return workspaceApi;
}

describe('WorkspaceCommitsPage', () => {
  it('links the first upstream in place instead of offering a dead picker', async () => {
    installBridge(null);
    render(<WorkspaceCommitsPage workspaceId="fork" repo={repo} />);

    const upstream = await screen.findByRole('button', { name: /Link upstream/ });
    expect(upstream.hasAttribute('disabled')).toBe(false);
    fireEvent.click(upstream);

    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Link upstream' }));

    await waitFor(() => expect(screen.getByText(/3,481 not in cork/)).toBeTruthy());
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('sends relation management to workspace settings', async () => {
    installBridge(linked);
    render(<WorkspaceCommitsPage workspaceId="fork" repo={repo} />);

    fireEvent.click(await screen.findByRole('button', { name: /Trino/ }));
    await waitFor(() => expect(screen.getByText(/3,481 not in cork/)).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Manage relation' }));
    expect(window.location.hash).toBe('#/workspace/fork/settings/relations');
  });
});
