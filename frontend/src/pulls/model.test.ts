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
import { describe, expect, it } from 'vitest';
import type { PullRequestDto } from '../types';
import { reconcilePullRequestDetail, rowsForTab, tabForInitialPullRequest, visibleSelectedId } from './model';

function pr(overrides: Partial<PullRequestDto>): PullRequestDto {
  return {
    id: 1,
    repo: 'bytequay/app',
    number: 42,
    title: 'Keep PR details',
    author: 'jack',
    htmlUrl: 'https://github.com/bytequay/app/pull/42',
    createdAt: null,
    updatedAt: '2026-08-15T00:00:00Z',
    origin: 'AUTHORED',
    labels: [],
    labelColors: null,
    draft: false,
    viewedAt: null,
    reviewedAt: null,
    handledAction: null,
    requestedReviewers: [],
    ciStatus: 'PASSING',
    additions: 1,
    deletions: 0,
    commentCount: 0,
    attentionReason: null,
    state: 'open',
    mergeable: true,
    mergeableState: 'clean',
    headPushedAt: null,
    headRef: 'feature',
    ...overrides,
  };
}

describe('rowsForTab', () => {
  it('keeps only actual review work in Needs review', () => {
    const authored = pr({ id: 1, origin: 'AUTHORED' });
    const requested = pr({ id: 2, origin: 'REVIEW_REQUESTED' });
    const unrelated = pr({ id: 3, origin: 'OTHER' });
    const followUp = pr({ id: 4, origin: 'OTHER', reviewedAt: '2026-08-21T09:00:00Z' });

    expect(rowsForTab([authored, requested], 'active').map(row => row.id)).toEqual([1]);
    expect(rowsForTab([authored, requested, unrelated, followUp], 'req').map(row => row.id)).toEqual([2, 4]);
  });

  it('moves handled reviews from Needs review to Reviewed newest review first', () => {
    const waiting = pr({
      id: 2,
      origin: 'REVIEW_REQUESTED',
      reviewedAt: '2026-08-21T09:00:00Z',
      handledAction: 'COMMENTED',
    });
    const latest = pr({
      id: 3,
      origin: 'OTHER',
      reviewedAt: '2026-08-22T09:00:00Z',
      handledAction: 'APPROVED',
    });

    expect(rowsForTab([waiting, latest], 'req')).toEqual([]);
    expect(rowsForTab([waiting, latest], 'reviewed').map(row => row.id)).toEqual([3, 2]);
  });

  it('puts merged and closed pull requests in Done', () => {
    const open = pr({ id: 1, state: 'open' });
    const merged = pr({ id: 2, state: 'merged' });
    const closed = pr({ id: 3, state: 'closed' });

    expect(rowsForTab([open, merged, closed], 'done').map(row => row.id)).toEqual([2, 3]);
    expect(tabForInitialPullRequest(closed, 'all')).toBe('done');
  });

  it('keeps a commented pull request open after it leaves Needs review', () => {
    const current = pr({ id: 1, origin: 'REVIEW_REQUESTED' });
    const waiting = pr({ id: 2, origin: 'REVIEW_REQUESTED' });
    const selected = visibleSelectedId(rowsForTab([current, waiting], 'req'), null);
    const commented = pr({
      id: 1,
      origin: 'REVIEW_REQUESTED',
      handledAction: 'COMMENTED',
      reviewedAt: '2026-08-25T00:00:00Z',
    });
    const needsReview = rowsForTab([commented, waiting], 'req');

    expect(selected).toBe(current.id);
    expect(needsReview.map(row => row.id)).toEqual([waiting.id]);
    expect(visibleSelectedId(needsReview, selected)).toBe(commented.id);
    expect(visibleSelectedId(needsReview, null)).toBe(waiting.id);
    expect(rowsForTab([commented, waiting], 'reviewed').map(row => row.id)).toEqual([commented.id]);
  });

  it('moves a stale open row to Done when detail reports it merged', () => {
    const updated = reconcilePullRequestDetail(pr({ id: 1, state: 'open' }), {
      state: 'merged',
      updatedAt: '2026-08-21T01:00:00Z',
    });

    expect(rowsForTab([updated], 'all')).toEqual([]);
    expect(rowsForTab([updated], 'active')).toEqual([]);
    expect(rowsForTab([updated], 'req')).toEqual([]);
    expect(rowsForTab([updated], 'reviewed')).toEqual([]);
    expect(rowsForTab([updated], 'done')[0]?.kind).toBe('merged');
  });
});
