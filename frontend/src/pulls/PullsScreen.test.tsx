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
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { invalidate, setCached } from '../dataCache';
import type { DashboardPR } from '../types/dashboardPr';
import type { PullRow } from './model';
import PullsScreen from './PullsScreen';

vi.mock('./PullDetailPane', () => ({
  default: ({ row }: { row: PullRow }) => <div data-testid="pull-detail">{row.id}</div>,
}));

const pr: DashboardPR = {
  id: 'known-pr', repo: 'trinodb/trino', number: 4074, title: 'Fix snapshot expiry', author: 'octocat',
  htmlUrl: 'https://github.com/trinodb/trino/pull/4074', createdAt: null, updatedAt: null,
  origin: 'REVIEW_REQUESTED', labels: [], labelColors: null, draft: false, viewedAt: null,
  reviewedAt: null, handledAction: null, requestedReviewers: [], ciStatus: null, additions: 1,
  deletions: 1, commentCount: 0, attentionReason: null, state: 'open', closedAt: null,
  mergedAt: null, mergeable: null, mergeableState: null, headPushedAt: null,
  reviewerVerdicts: null, snoozedUntil: null, snoozeWakeReason: null,
};

afterEach(() => {
  cleanup();
  invalidate('prs:list');
  delete (globalThis as { bridge?: unknown }).bridge;
});

describe('PullsScreen', () => {
  it('opens a cached deep-linked PR without resolving it through GitHub again', async () => {
    setCached('prs:list', [pr]);
    const getPrForRepoPull = vi.fn();
    window.bridge = {
      listWorkspaces: vi.fn().mockResolvedValue([]),
      fetchDashboardPrs: vi.fn().mockResolvedValue([pr]),
      syncDashboardPrs: vi.fn().mockResolvedValue([pr]),
      getPrForRepoPull,
      recordSurfaceVisit: vi.fn().mockResolvedValue(undefined),
    } as unknown as typeof window.bridge;

    render(<PullsScreen initialPr={{ repo: pr.repo, number: pr.number }} />);

    expect((await screen.findByTestId('pull-detail')).textContent).toBe(pr.id);
    await waitFor(() => expect(window.bridge.fetchDashboardPrs).toHaveBeenCalled());
    expect(getPrForRepoPull).not.toHaveBeenCalled();
  });
});
