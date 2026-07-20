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
import type { PullRequestDto } from '../types';
import type { PullRow } from './model';
import WorkspacePullsScreen from './WorkspacePullsScreen';

vi.mock('./PullDetailPane', () => ({
  default: ({ row, onAssignAgent, onWorkWithAgent }: {
    row: PullRow;
    onAssignAgent?: () => void;
    onWorkWithAgent?: () => void;
  }) => (
    <div data-testid="pull-detail">
      {row.num}
      {row.dto.reviewState === 'running'
        ? <button onClick={onWorkWithAgent} disabled={onWorkWithAgent === undefined}>Full review • running</button>
        : row.hasAgent
          ? <button onClick={onWorkWithAgent} disabled={onWorkWithAgent === undefined}>Full review • completed</button>
          : <button onClick={onAssignAgent}>Full review</button>}
    </div>
  ),
}));

vi.mock('./AgentColumn', () => ({
  default: ({ prId }: { prId: string }) => <div data-testid="agent-column">{prId}</div>,
}));

const pr = (number: number): PullRequestDto => ({
  id: number,
  repo: 'trinodb/trino',
  number,
  title: `PR ${number}`,
  author: 'octocat',
  htmlUrl: `https://github.com/trinodb/trino/pull/${number}`,
  createdAt: '2026-07-19T00:00:00Z',
  updatedAt: '2026-07-19T00:00:00Z',
  origin: 'REVIEW_REQUESTED',
  labels: [],
  labelColors: null,
  draft: false,
  viewedAt: null,
  reviewedAt: null,
  handledAction: null,
  requestedReviewers: [],
  ciStatus: null,
  additions: 1,
  deletions: 1,
  commentCount: 0,
  attentionReason: null,
  state: 'open',
  closedAt: null,
  mergedAt: null,
  mergeable: true,
  mergeableState: 'clean',
  headPushedAt: null,
  reviewerVerdicts: null,
  snoozedUntil: null,
  snoozeWakeReason: null,
  reviewRound: null,
});

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, 'bridge');
});

describe('WorkspacePullsScreen', () => {
  it('opens a deep-linked PR that is outside the workspace list page', async () => {
    const requested = pr(30627);
    const deepLinked = pr(29);
    const workspaceRequest = vi.fn().mockImplementation(({ path }: { path: string }) => {
      if (path.endsWith('/repository')) {
        return Promise.resolve({
          fullName: 'trinodb/trino', owner: 'trinodb', repo: 'trino',
          defaultBaseBranch: 'master', local: null,
        });
      }
      if (path.endsWith('/pull-requests/29')) return Promise.resolve(deepLinked);
      if (path.endsWith('/pull-requests')) return Promise.resolve([requested]);
      return Promise.reject(new Error(`Unexpected request: ${path}`));
    });
    window.bridge = {
      workspaceApi: workspaceRequest,
      getPrForRepoPull: vi.fn().mockResolvedValue({ id: 'pr-29' }),
      getAgentReview: vi.fn().mockResolvedValue(null),
      recordSurfaceVisit: vi.fn().mockResolvedValue(undefined),
    } as unknown as typeof window.bridge;

    render(
      <WorkspacePullsScreen
        workspaceId="ws-1"
        initialPrNumber={29}
        onOpenPr={() => {}}
        onBackToList={() => {}}
      />,
    );

    expect((await screen.findByTestId('pull-detail')).textContent).toContain('29');
    expect(workspaceRequest).toHaveBeenCalledWith({
      path: '/api/workspaces/ws-1/pull-requests/29',
    });
  });

  it('changes a started full review to running and replaces column two with AgentColumn', async () => {
    const selected = pr(30627);
    const workspaceRequest = vi.fn().mockImplementation(({ path }: { path: string }) => {
      if (path.endsWith('/repository')) {
        return Promise.resolve({
          fullName: 'trinodb/trino', owner: 'trinodb', repo: 'trino',
          defaultBaseBranch: 'master', local: null,
        });
      }
      if (path.endsWith('/pull-requests')) return Promise.resolve([selected]);
      return Promise.reject(new Error(`Unexpected request: ${path}`));
    });
    const startAgentReview = vi.fn().mockResolvedValue({});
    window.bridge = {
      workspaceApi: workspaceRequest,
      getPrForRepoPull: vi.fn().mockResolvedValue({ id: 'pr-30627' }),
      getAgentReview: vi.fn().mockResolvedValue(null),
      recordSurfaceVisit: vi.fn().mockResolvedValue(undefined),
      startAgentReview,
    } as unknown as typeof window.bridge;

    render(
      <WorkspacePullsScreen
        workspaceId="ws-1"
        initialPrNumber={30627}
        onOpenPr={() => {}}
        onBackToList={() => {}}
      />,
    );

    fireEvent.click(await screen.findByRole('button', { name: 'Full review' }));
    expect(startAgentReview).toHaveBeenCalledWith('pr-30627', { workspaceId: 'ws-1' });

    const running = await screen.findByRole('button', { name: 'Full review • running' });
    await waitFor(() => expect((running as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(running);
    await waitFor(() => expect(screen.getByTestId('agent-column').textContent).toBe('pr-30627'));
    expect(screen.queryByText('To review · 1')).toBeNull();
  });

  it('does not open AgentColumn when an optimistic full-review start fails', async () => {
    const selected = pr(30627);
    const workspaceRequest = vi.fn().mockImplementation(({ path }: { path: string }) => {
      if (path.endsWith('/repository')) {
        return Promise.resolve({
          fullName: 'trinodb/trino', owner: 'trinodb', repo: 'trino',
          defaultBaseBranch: 'master', local: null,
        });
      }
      if (path.endsWith('/pull-requests')) return Promise.resolve([selected]);
      return Promise.reject(new Error(`Unexpected request: ${path}`));
    });
    let failStart!: (reason: Error) => void;
    const startAgentReview = vi.fn().mockReturnValue(new Promise((_resolve, reject) => {
      failStart = reject;
    }));
    window.bridge = {
      workspaceApi: workspaceRequest,
      getPrForRepoPull: vi.fn().mockResolvedValue({ id: 'pr-30627' }),
      getAgentReview: vi.fn().mockResolvedValue(null),
      recordSurfaceVisit: vi.fn().mockResolvedValue(undefined),
      startAgentReview,
    } as unknown as typeof window.bridge;

    render(
      <WorkspacePullsScreen
        workspaceId="ws-1"
        initialPrNumber={30627}
        onOpenPr={() => {}}
        onBackToList={() => {}}
      />,
    );

    fireEvent.click(await screen.findByRole('button', { name: 'Full review' }));
    const running = await screen.findByRole('button', { name: 'Full review • running' });
    expect((running as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(running);
    expect(screen.queryByTestId('agent-column')).toBeNull();

    failStart(new Error('review could not start'));
    expect(await screen.findByRole('button', { name: 'Full review' })).toBeTruthy();
    expect(screen.queryByTestId('agent-column')).toBeNull();
  });

  it('restores a running full-review state when a watched PR is reopened', async () => {
    const selected = pr(30627);
    const workspaceRequest = vi.fn().mockImplementation(({ path }: { path: string }) => {
      if (path.endsWith('/repository')) {
        return Promise.resolve({
          fullName: 'trinodb/trino', owner: 'trinodb', repo: 'trino',
          defaultBaseBranch: 'master', local: null,
        });
      }
      if (path.endsWith('/pull-requests')) return Promise.resolve([selected]);
      return Promise.reject(new Error(`Unexpected request: ${path}`));
    });
    window.bridge = {
      workspaceApi: workspaceRequest,
      getPrForRepoPull: vi.fn().mockResolvedValue({ id: 'pr-30627' }),
      getAgentReview: vi.fn().mockResolvedValue({
        review: { status: 'ACTIVE' },
        rounds: [{ status: 'RUNNING' }],
      }),
      recordSurfaceVisit: vi.fn().mockResolvedValue(undefined),
    } as unknown as typeof window.bridge;

    render(
      <WorkspacePullsScreen
        workspaceId="ws-1"
        initialPrNumber={30627}
        onOpenPr={() => {}}
        onBackToList={() => {}}
      />,
    );

    fireEvent.click(await screen.findByRole('button', { name: 'Full review • running' }));
    await waitFor(() => expect(screen.getByTestId('agent-column').textContent).toBe('pr-30627'));
  });

  it('opens a local PR AgentColumn even when its review-state lookup fails', async () => {
    const workspaceRequest = vi.fn().mockImplementation(({ path }: { path: string }) => {
      if (path.endsWith('/repository')) {
        return Promise.resolve({
          fullName: 'trinodb/trino', owner: 'trinodb', repo: 'trino',
          defaultBaseBranch: 'master', local: null,
        });
      }
      if (path.endsWith('/pull-requests')) return Promise.resolve([]);
      return Promise.reject(new Error(`Unexpected request: ${path}`));
    });
    const getPrForRepoPull = vi.fn();
    window.bridge = {
      workspaceApi: workspaceRequest,
      getPrForRepoPull,
      getLocalPrBundle: vi.fn().mockResolvedValue({
        pr: {
          id: 'local-pr', taskId: 'task-1', branchName: 'feature/local', baseBranch: 'master',
          title: 'Local review before push', description: '', status: 'local-open', createdAt: 1,
          pushedAt: null, remotePrNumber: null, remotePrUrl: null, mergedAt: null, closedAt: null,
          origin: 'task', repo: null, author: 'you', syncedAt: null, syncedAdditions: null,
          syncedDeletions: null, syncedMergeable: null, syncedMergeableState: null,
          syncedMergeQueueEnabled: false, syncedMergeQueueState: null, branchDeletedAt: null,
        },
        commits: [], timeline: [], checks: [], comments: [],
      }),
      getAgentReview: vi.fn().mockRejectedValue(new Error('temporary review read failure')),
      recordSurfaceVisit: vi.fn().mockResolvedValue(undefined),
    } as unknown as typeof window.bridge;

    const { rerender } = render(
      <WorkspacePullsScreen
        workspaceId="ws-1"
        initialPrId="local-pr"
        initialAgentView
        onOpenPr={() => {}}
        onBackToList={() => {}}
      />,
    );

    await waitFor(() => expect(screen.getByTestId('agent-column').textContent).toBe('local-pr'));
    expect(screen.getByTestId('pull-detail').textContent).toContain('0');
    expect(getPrForRepoPull).not.toHaveBeenCalled();

    rerender(
      <WorkspacePullsScreen
        workspaceId="ws-1"
        onOpenPr={() => {}}
        onBackToList={() => {}}
      />,
    );
    await waitFor(() => expect(screen.queryByTestId('agent-column')).toBeNull());
    expect(screen.queryByTestId('pull-detail')).toBeNull();
  });
});
