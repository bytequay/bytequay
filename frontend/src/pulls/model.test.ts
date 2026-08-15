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
import { rowsForTab } from './model';

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
    closedAt: null,
    mergedAt: null,
    mergeable: true,
    mergeableState: 'clean',
    headPushedAt: null,
    reviewerVerdicts: null,
    snoozedUntil: null,
    snoozeWakeReason: null,
    headRef: 'feature',
    ...overrides,
  };
}

describe('rowsForTab', () => {
  it('keeps authored and review-requested pull requests in their direct views', () => {
    const authored = pr({ id: 1, origin: 'AUTHORED' });
    const requested = pr({ id: 2, origin: 'REVIEW_REQUESTED' });

    expect(rowsForTab([authored, requested], 'active').map(row => row.id)).toEqual([1]);
    expect(rowsForTab([authored, requested], 'req').map(row => row.id)).toEqual([2]);
  });

  it('puts merged and closed pull requests in Done', () => {
    const open = pr({ id: 1, state: 'open' });
    const merged = pr({ id: 2, state: 'merged' });
    const closed = pr({ id: 3, state: 'closed' });

    expect(rowsForTab([open, merged, closed], 'done').map(row => row.id)).toEqual([2, 3]);
  });
});
