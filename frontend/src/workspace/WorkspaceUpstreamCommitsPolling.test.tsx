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
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { WorkspaceApiRequest } from '../types';
import { UpstreamCherryPicker } from './WorkspaceUpstreamCommits';
import type {
  UpstreamCherryPickJobDto,
  UpstreamCommitDto,
  UpstreamCommitsDto,
  WorkspaceRepositoryDto,
} from './workspaceApi';

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  Reflect.deleteProperty(window, 'bridge');
});

const running: UpstreamCherryPickJobDto = {
  jobId: 'job-1', status: 'RUNNING', sourceBranch: 'master',
  resultBranch: 'upstream-9-9', baseRef: 'b'.repeat(40), requestedCount: 1,
  appliedCount: 0, skippedCount: 0, conflictedCount: 0, pauseRequested: false,
  budgetMilliUsd: 5_000, spentMilliUsd: 0, localGateUnavailable: false, conflictPaths: [], worktreePath: '/tmp/job-1',
  prNumber: null, prUrl: null, harnessWatchId: null, errorMessage: null,
  closedAt: null,
  createdAt: '2026-08-05T09:00:00Z', updatedAt: '2026-08-05T09:10:00Z',
};

const failed: UpstreamCherryPickJobDto = {
  ...running,
  status: 'FAILED',
  appliedCount: 1,
  errorMessage: 'temporary git failure',
};

const completed: UpstreamCherryPickJobDto = {
  ...running,
  jobId: 'job-2',
  status: 'COMPLETED',
  appliedCount: 1,
};

const repository: WorkspaceRepositoryDto = {
  owner: 'acme', repo: 'widget', fullName: 'acme/widget', defaultBaseBranch: 'main',
  local: {
    owner: 'acme', repo: 'widget', localClonePath: '/tmp/widget', state: 'CLEAN',
    currentBranch: 'main', dirtyFileCount: 0, errorMessage: null,
    upstreamRemoteName: null, defaultBranch: 'main', viewFocus: 'fork',
  },
};

const commit: UpstreamCommitDto = {
  sha: 'a'.repeat(40), shortSha: 'aaaaaaa', subject: 'Update dependency',
  authorName: 'A', authorEmail: 'a@example.com', committedAt: null,
  tags: ['v9.9'], picked: false,
};

const snapshot: UpstreamCommitsDto = {
  upstreamWorkspaceId: 'upstream', upstreamWorkspaceName: 'Upstream',
  upstreamRepoFullName: 'upstream/widget', revision: 'master', lastFetchedAt: null,
  indexedCommitCount: 1, notInForkCount: 1, commits: [commit], offset: 0, hasMore: false,
};

describe('UpstreamCherryPicker dry run', () => {
  it('splits the plan into a picked list and a skipped list that carries reasons', async () => {
    const request = vi.fn(async (input: WorkspaceApiRequest): Promise<unknown> => {
      if (input.path === '/api/workspaces/fork/upstream/cherry-picks') return [];
      if (input.path === '/api/workspaces/fork/upstream/cherry-picks/preview') {
        return {
          pickCount: 2,
          skipCount: 2,
          commits: [
            { sha: 'a1', shortSha: 'a1a1a1a', subject: 'Add null checks', authorName: 'A', pick: true, skipReason: null },
            { sha: 'b2', shortSha: 'b2b2b2b', subject: 'Bump guava', authorName: 'B', pick: false, skipReason: 'subject starts with "bump"' },
            { sha: 'c3', shortSha: 'c3c3c3c', subject: 'Update docs', authorName: 'C', pick: true, skipReason: null },
            { sha: 'd4', shortSha: 'd4d4d4d', subject: 'Old change', authorName: 'D', pick: false, skipReason: 'already in the fork' },
          ],
        };
      }
      throw new Error(`Unexpected request: ${input.path}`);
    });
    (window as unknown as { bridge: unknown }).bridge = { workspaceApi: request };

    render(<UpstreamCherryPicker workspaceId="fork" repo={repository} snapshot={snapshot}
      commits={[commit]} onClose={() => {}} />);
    await flush();
    fireEvent.click(screen.getByRole('button', { name: 'Dry run' }));
    await flush();

    // Two separate lists, each counted in its own summary.
    const picks = document.querySelector('.wu-plan-list.is-pick');
    const skips = document.querySelector('.wu-plan-list.is-skip');
    expect(picks?.querySelector('summary')?.textContent).toBe('2 will be cherry-picked');
    expect(skips?.querySelector('summary')?.textContent).toBe('2 skipped');
    expect(picks?.querySelectorAll('li')).toHaveLength(2);
    expect(skips?.querySelectorAll('li')).toHaveLength(2);

    // The skip reason travels with the commit rather than being summarised away.
    expect(screen.getByText('subject starts with "bump"')).toBeTruthy();
    expect(screen.getByText('already in the fork')).toBeTruthy();

    // A picked commit must not carry a reason.
    const picked = screen.getByText('Add null checks').closest('li');
    expect(picked?.querySelector('em')).toBeNull();
    expect(picked?.querySelector('code')?.textContent).toBe('a1a1a1a');
  });

  it('drops a stale plan when a filter changes so it cannot be read as current', async () => {
    const request = vi.fn(async (input: WorkspaceApiRequest): Promise<unknown> => {
      if (input.path === '/api/workspaces/fork/upstream/cherry-picks') return [];
      if (input.path === '/api/workspaces/fork/upstream/cherry-picks/preview') {
        return { pickCount: 1, skipCount: 0, commits: [
          { sha: 'a1', shortSha: 'a1a1a1a', subject: 'Add null checks', authorName: 'A', pick: true, skipReason: null },
        ] };
      }
      throw new Error(`Unexpected request: ${input.path}`);
    });
    (window as unknown as { bridge: unknown }).bridge = { workspaceApi: request };

    render(<UpstreamCherryPicker workspaceId="fork" repo={repository} snapshot={snapshot}
      commits={[commit]} onClose={() => {}} />);
    await flush();
    fireEvent.click(screen.getByRole('button', { name: 'Dry run' }));
    await flush();
    expect(screen.getByText('Add null checks')).toBeTruthy();

    fireEvent.change(
      screen.getByLabelText('Skip commits whose subject contains'),
      { target: { value: 'docs' } });
    await flush();

    expect(screen.queryByText('Add null checks')).toBeNull();
  });
});

describe('UpstreamCherryPicker durable polling', () => {
  it('rediscovers a running job and retries after a transient read failure', async () => {
    vi.useFakeTimers();
    let reads = 0;
    const request = vi.fn(async (input: WorkspaceApiRequest): Promise<unknown> => {
      if (input.path === '/api/workspaces/fork/upstream/cherry-picks') return [running];
      if (input.path === '/api/workspaces/fork/upstream/cherry-picks/job-1') {
        reads += 1;
        if (reads === 1) throw new Error('temporary read failure');
        return { ...running, status: 'COMPLETED', appliedCount: 1 };
      }
      throw new Error(`Unexpected request: ${input.path}`);
    });
    (window as unknown as { bridge: unknown }).bridge = { workspaceApi: request };

    render(<UpstreamCherryPicker workspaceId="fork" repo={repository} snapshot={snapshot}
      commits={[commit]} onClose={() => {}} />);
    await flush();
    expect(screen.getByText('Cherry-pick in progress')).toBeTruthy();

    await act(async () => { vi.advanceTimersByTime(900); });
    await flush();
    expect(screen.getByText(/temporary read failure/)).toBeTruthy();

    await act(async () => { vi.advanceTimersByTime(900); });
    await flush();
    expect(screen.getByText('Cherry-pick complete')).toBeTruthy();
    expect(reads).toBe(2);
  });

  it('rediscovers the latest failed job and explicitly retries it', async () => {
    const request = vi.fn(async (input: WorkspaceApiRequest): Promise<unknown> => {
      if (input.path === '/api/workspaces/fork/upstream/cherry-picks') {
        return [completed, failed];
      }
      if (input.path === '/api/workspaces/fork/upstream/cherry-picks/job-1/retry'
        && input.method === 'POST') {
        return { ...failed, status: 'QUEUED', errorMessage: null };
      }
      throw new Error(`Unexpected request: ${input.path}`);
    });
    (window as unknown as { bridge: unknown }).bridge = { workspaceApi: request };

    render(<UpstreamCherryPicker workspaceId="fork" repo={repository} snapshot={snapshot}
      commits={[commit]} onClose={() => {}} />);
    await flush();
    expect(screen.getByText('Cherry-pick failed')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Retry cherry-pick' }));
    await flush();

    expect(request).toHaveBeenCalledWith({
      path: '/api/workspaces/fork/upstream/cherry-picks/job-1/retry',
      method: 'POST',
    });
    expect(screen.getByText('Cherry-pick in progress')).toBeTruthy();
  });

  it('allows a new range after rediscovering a historical failed job', async () => {
    const request = vi.fn(async (input: WorkspaceApiRequest): Promise<unknown> => {
      if (input.path === '/api/workspaces/fork/upstream/cherry-picks') return [failed];
      throw new Error(`Unexpected request: ${input.path}`);
    });
    (window as unknown as { bridge: unknown }).bridge = { workspaceApi: request };

    render(<UpstreamCherryPicker workspaceId="fork" repo={repository} snapshot={snapshot}
      commits={[commit]} onClose={() => {}} />);
    await flush();
    expect(screen.getByText('Cherry-pick failed')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Start another' }));

    expect(screen.getByRole('button', { name: 'Start cherry-pick' })).toBeTruthy();
    // Named after the range it carries, so a second run cannot collide
    // with the branch the first one took.
    expect(screen.getByDisplayValue('bump-widget-aaaaaaa')).toBeTruthy();
  });

  it('a parked run reports itself instead of holding the picker shut', async () => {
    // Closing a run only stamped closedAt and left the status at
    // PAUSED_CONFLICT, so a finished run looked parked forever — and a parked
    // run replaced the picker with a view that has no way back to it.
    const parked = { ...running, status: 'PAUSED_CONFLICT' as const, resultBranch: 'bump-a-to-b' };
    const request = vi.fn(async (input: WorkspaceApiRequest): Promise<unknown> => {
      if (input.path === '/api/workspaces/fork/upstream/cherry-picks') return [parked];
      throw new Error(`Unexpected request: ${input.path}`);
    });
    (window as unknown as { bridge: unknown }).bridge = { workspaceApi: request };

    render(<UpstreamCherryPicker workspaceId="fork" repo={repository} snapshot={snapshot}
      commits={[commit]} onClose={() => {}} />);
    await flush();

    // The picker is available for this range, with the other run noted.
    expect(screen.getByRole('button', { name: 'Start cherry-pick' })).toBeTruthy();
    expect(screen.getByText(/parked on/)).toBeTruthy();
    expect(screen.getByText('bump-a-to-b')).toBeTruthy();
  });

  it('a closed run is over whatever status it carries', async () => {
    const closed = {
      ...running, status: 'PAUSED_CONFLICT' as const, closedAt: '2026-08-06T10:19:00Z',
    };
    const request = vi.fn(async (input: WorkspaceApiRequest): Promise<unknown> => {
      if (input.path === '/api/workspaces/fork/upstream/cherry-picks') return [closed];
      throw new Error(`Unexpected request: ${input.path}`);
    });
    (window as unknown as { bridge: unknown }).bridge = { workspaceApi: request };

    render(<UpstreamCherryPicker workspaceId="fork" repo={repository} snapshot={snapshot}
      commits={[commit]} onClose={() => {}} />);
    await flush();

    expect(screen.getByRole('button', { name: 'Start cherry-pick' })).toBeTruthy();
    expect(screen.queryByText(/parked on/)).toBeNull();
  });
});


async function flush() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}
