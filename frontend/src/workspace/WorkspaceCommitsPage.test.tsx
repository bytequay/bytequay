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
  lastFetchedAt: '2026-07-24T10:00:00Z', autoFetchIntervalMinutes: 30, indexedCommitCount: 3481,
};

const repo = {
  owner: 'chenjian2664', repo: 'widget', fullName: 'acme/widget', defaultBaseBranch: 'master',
  local: { currentBranch: 'master', defaultBranch: 'origin/master', localClonePath: '/repo/widget' },
} as unknown as WorkspaceRepositoryDto;

function installBridge(relation: WorkspaceRelationDto | null) {
  let current = relation;
  const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
    if (request.path === '/api/workspaces/fork/relation' && request.method === undefined) return current;
    if (request.path === '/api/workspaces/fork/relation' && request.method === 'PUT') {
      current = linked;
      return current;
    }
    if (request.path === '/api/workspaces/fork/relation/branches') {
      return ['master', 'release-475', 'release-476'];
    }
    if (request.path === '/api/workspaces/fork/relation/candidates') {
      return [{ workspaceId: 'upstream', name: 'Trino', repoFullName: 'trinodb/trino', suggested: true }];
    }
    if (request.path.startsWith('/api/workspaces/fork/branches')) {
      return [
        { name: 'master', remoteOnly: false },
        { name: 'dev/clamp-fix', remoteOnly: false },
        { name: 'pushed-from-laptop', remoteOnly: true },
      ];
    }
    if (request.path.startsWith('/api/workspaces/fork/working-tree/files')) {
      return [{ path: 'frontend/src/App.tsx', status: 'M', additions: 12, deletions: 3 }];
    }
    if (request.path.startsWith('/api/workspaces/fork/commits/rewritable')) {
      return { branch: 'master', trackingRef: 'origin/master', editable: true, commits: [] };
    }
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

    await waitFor(() => expect(screen.getByText(/3,481 not in widget/)).toBeTruthy());
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('offers every local branch in the picker, not just the current one', async () => {
    installBridge(null);
    render(<WorkspaceCommitsPage workspaceId="fork" repo={repo} />);

    fireEvent.click(await screen.findByRole('button', { name: /^Branch:/ }));
    const menu = await screen.findByRole('menu');
    expect(within(menu).getByText('dev/clamp-fix')).toBeTruthy();
    expect(within(menu).getByText('checked out')).toBeTruthy();
    // A PR head ref with no local checkout would show an empty history.
    expect(within(menu).queryByText('pushed-from-laptop')).toBeNull();

    fireEvent.click(within(menu).getByText('dev/clamp-fix'));
    expect(screen.getByRole('button', { name: 'Branch: dev/clamp-fix' })).toBeTruthy();
  });

  it('shows uncommitted work on its own tab', async () => {
    installBridge(null);
    render(<WorkspaceCommitsPage workspaceId="fork" repo={repo} />);

    fireEvent.click(await screen.findByRole('tab', { name: /Uncommitted changes/ }));

    expect(await screen.findByText('1 uncommitted file')).toBeTruthy();
    expect(screen.getByTitle('frontend/src/App.tsx')).toBeTruthy();
  });

  it('offers the upstream branches, and any ref typed in, as a cherry-pick source', async () => {
    const workspaceApi = installBridge(linked);
    render(<WorkspaceCommitsPage workspaceId="fork" repo={repo} />);

    fireEvent.click(await screen.findByRole('button', { name: /Trino/ }));
    fireEvent.click(await screen.findByRole('button', { name: /^Branch:/ }));
    const menu = await screen.findByRole('menu');
    expect(within(menu).getByText('release-475')).toBeTruthy();

    fireEvent.click(within(menu).getByText('release-476'));
    await waitFor(() => expect(workspaceApi.mock.calls.some(([request]) =>
      (request as WorkspaceApiRequest).path
        === '/api/workspaces/fork/upstream/commits?revision=release-476&limit=200&offset=0')).toBe(true));

    fireEvent.click(screen.getByRole('button', { name: /^Branch:/ }));
    const reopened = await screen.findByRole('menu');
    fireEvent.change(within(reopened).getByLabelText('Filter or type a branch'),
      { target: { value: 'never-fetched' } });
    fireEvent.click(within(reopened).getByText(/Use /));
    await waitFor(() => expect(workspaceApi.mock.calls.some(([request]) =>
      (request as WorkspaceApiRequest).path
        === '/api/workspaces/fork/upstream/commits?revision=never-fetched&limit=200&offset=0')).toBe(true));
  });

  it('sends relation management to workspace settings', async () => {
    installBridge(linked);
    render(<WorkspaceCommitsPage workspaceId="fork" repo={repo} />);

    fireEvent.click(await screen.findByRole('button', { name: /Trino/ }));
    await waitFor(() => expect(screen.getByText(/3,481 not in widget/)).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Manage relation' }));
    expect(window.location.hash).toBe('#/workspace/fork/settings/relations');
  });

  it('never appends the same upstream commit twice while paging', async () => {
    // The in-flight guard used to be state, so every closure created before the
    // re-render still saw "not paging" and fired again for the same offset.
    // A repeated sha collides on React's key and points the range selection at
    // two different rows.
    const rows = Array.from({ length: 3 }, (_, index) => ({
      sha: `${index}`.repeat(40), shortSha: `${index}`.repeat(7), subject: `Commit ${index}`,
      authorName: 'Dain', authorEmail: 'd@example.com',
      committedAt: '2026-08-01T00:00:00Z', tags: [] as string[], picked: false,
    }));
    const workspaceApi = installBridge(linked);
    workspaceApi.mockImplementation(async (request: WorkspaceApiRequest): Promise<unknown> => {
      if (request.path === '/api/workspaces/fork/relation') return linked;
      if (request.path === '/api/workspaces/fork/relation/branches') return ['master'];
      if (request.path.startsWith('/api/workspaces/fork/branches')) {
        return [{ name: 'master', remoteOnly: false }];
      }
      if (request.path.startsWith('/api/workspaces/fork/upstream/commits')) {
        // A backend that answers the same rows for a second page must not be
        // able to double the list.
        return {
          upstreamWorkspaceId: 'upstream', upstreamWorkspaceName: 'Trino',
          upstreamRepoFullName: 'trinodb/trino', revision: 'master', lastFetchedAt: null,
          indexedCommitCount: 3, notInForkCount: 3, commits: rows, offset: 0, hasMore: true,
        };
      }
      if (request.path.startsWith('/api/workspaces/fork/commits')) return [];
      return null;
    });

    render(<WorkspaceCommitsPage workspaceId="fork" repo={repo} />);
    await waitFor(() => expect(screen.getByRole('button', { name: /Trino/i })).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: /Trino/i }));
    await waitFor(() => expect(screen.getByText('Commit 1')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Load more commits' }));
    await waitFor(() => expect(workspaceApi.mock.calls.some(([request]) =>
      (request as WorkspaceApiRequest).path.includes('offset=3'))).toBe(true));

    expect(screen.getAllByText('Commit 1')).toHaveLength(1);
  });
});
