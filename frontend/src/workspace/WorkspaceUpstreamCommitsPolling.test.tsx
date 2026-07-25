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
  jobId: 'job-1', status: 'RUNNING', resultBranch: 'bump-upstream-9.9', requestedCount: 1,
  appliedCount: 0, skippedCount: 0, conflictPaths: [], worktreePath: '/tmp/job-1',
  prNumber: null, prUrl: null, harnessWatchId: null, errorMessage: null,
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
  authorName: 'A', authorEmail: 'a@example.com', authoredAt: null,
  tags: ['v482'], picked: false, upstreamPr: 'trinodb/trino#1',
};

const snapshot: UpstreamCommitsDto = {
  upstreamWorkspaceId: 'trino', upstreamWorkspaceName: 'Trino',
  upstreamRepoFullName: 'trinodb/trino', revision: 'master', lastFetchedAt: null,
  indexedCommitCount: 1, notInForkCount: 1, commits: [commit],
};

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
    expect(screen.getByDisplayValue('bump-upstream-9.9')).toBeTruthy();
  });
});

async function flush() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}
