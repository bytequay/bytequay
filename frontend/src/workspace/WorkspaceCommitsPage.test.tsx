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
import WorkspaceCommitsPage from './WorkspaceCommitsPage';
import type { WorkspaceRelationDto, WorkspaceRepositoryDto } from './workspaceApi';

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, 'bridge');
});

const linked: WorkspaceRelationDto = {
  workspaceId: 'fork', upstreamWorkspaceId: 'upstream', upstreamWorkspaceName: 'Trino',
  upstreamRepoFullName: 'trinodb/trino', commitsEnabled: true, tagsEnabled: true,
  branchesEnabled: false, issuesPullRequestsEnabled: false,
  lastFetchedAt: '2026-07-24T10:00:00Z', autoFetchIntervalMinutes: 30, indexedCommitCount: 3481,
};

const repo = {
  owner: 'chenjian2664', repo: 'widget', fullName: 'chenjian2664/widget', defaultBaseBranch: 'master',
  local: { currentBranch: 'master', defaultBranch: 'origin/master', localClonePath: '/repo/widget' },
} as unknown as WorkspaceRepositoryDto;

function installBridge(relation: WorkspaceRelationDto | null) {
  const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
    if (request.path === '/api/workspaces/fork/relation') return relation;
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
  it('sends an unlinked workspace to the Relations tab instead of a dead upstream picker', async () => {
    installBridge(null);
    render(<WorkspaceCommitsPage workspaceId="fork" repo={repo} />);

    const upstream = await screen.findByRole('button', { name: /Link upstream/ });
    expect(upstream.hasAttribute('disabled')).toBe(false);

    fireEvent.click(upstream);

    await waitFor(() => expect(screen.getByRole('heading', { name: 'No upstream linked' })).toBeTruthy());
    expect(screen.getByRole('tab', { name: 'Relations' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.queryByLabelText('Commit source')).toBeNull();
  });

  it('keeps the upstream source reachable once a relation exists', async () => {
    installBridge(linked);
    render(<WorkspaceCommitsPage workspaceId="fork" repo={repo} />);

    fireEvent.click(await screen.findByRole('button', { name: /Trino/ }));

    await waitFor(() => expect(screen.getByText(/3,481 not in widget/)).toBeTruthy());
    expect(screen.getByRole('tab', { name: 'Commits' }).getAttribute('aria-selected')).toBe('true');

    fireEvent.click(screen.getByRole('button', { name: 'Manage relation' }));
    await waitFor(() => expect(screen.getByText('READS FROM')).toBeTruthy());
  });
});
