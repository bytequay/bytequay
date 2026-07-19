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
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PullRequestDto } from '../types';
import type { PullRow } from './model';
import WorkspacePullsScreen from './WorkspacePullsScreen';

vi.mock('./PullDetailPane', () => ({
  default: ({ row }: { row: PullRow }) => <div data-testid="pull-detail">{row.num}</div>,
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

    expect((await screen.findByTestId('pull-detail')).textContent).toBe('29');
    expect(workspaceRequest).toHaveBeenCalledWith({
      path: '/api/workspaces/ws-1/pull-requests/29',
    });
  });
});
