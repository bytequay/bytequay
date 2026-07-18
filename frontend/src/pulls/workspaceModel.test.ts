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
import { bucketFor, filterCounts, pullRowFromDto, visibleRows } from './workspaceModel';

function pr(overrides: Partial<PullRequestDto>): PullRequestDto {
  return {
    id: 1,
    repo: 'trinodb/trino',
    number: 1,
    title: 'A change',
    author: '@octocat',
    htmlUrl: 'https://github.com/trinodb/trino/pull/1',
    createdAt: null,
    updatedAt: new Date().toISOString(),
    origin: 'AUTHORED',
    labels: [],
    labelColors: null,
    draft: false,
    viewedAt: null,
    reviewedAt: null,
    handledAction: null,
    requestedReviewers: [],
    ciStatus: null,
    additions: 0,
    deletions: 0,
    commentCount: 0,
    attentionReason: null,
    state: 'open',
    closedAt: null,
    mergedAt: null,
    mergeable: null,
    mergeableState: null,
    headPushedAt: null,
    reviewerVerdicts: null,
    snoozedUntil: null,
    snoozeWakeReason: null,
    ...overrides,
  };
}

const yesterday = new Date(Date.now() - 86400_000).toISOString();

describe('bucketFor', () => {
  it('clears PRs merged or reviewed today', () => {
    expect(bucketFor(pr({ state: 'merged', mergedAt: new Date().toISOString() }))).toBe('cleared');
    expect(bucketFor(pr({ reviewedAt: new Date().toISOString() }))).toBe('cleared');
    expect(bucketFor(pr({ state: 'merged', mergedAt: yesterday }))).not.toBe('cleared');
  });

  it('flags attention on attentionReason or an unhandled review request', () => {
    expect(bucketFor(pr({ attentionReason: 'CI_FAILING' }))).toBe('attention');
    expect(bucketFor(pr({ origin: 'REVIEW_REQUESTED' }))).toBe('attention');
    expect(bucketFor(pr({ origin: 'REVIEW_REQUESTED', handledAction: 'APPROVED' as PullRequestDto['handledAction'] }))).toBe('progress');
  });

  it('defaults to progress', () => {
    expect(bucketFor(pr({}))).toBe('progress');
  });
});

describe('filterCounts', () => {
  it('counts open PRs per origin', () => {
    const rows = [
      pr({ id: 1, origin: 'REVIEW_REQUESTED' }),
      pr({ id: 2, origin: 'REVIEW_REQUESTED', state: 'closed' }),
      pr({ id: 3, origin: 'AUTHORED' }),
      pr({ id: 4, origin: 'AUTHORED' }),
      pr({ id: 5, origin: 'AUTHORED', state: 'merged' }),
    ];
    expect(filterCounts(rows)).toEqual({ review: 1, mine: 2, open: 3 });
  });
});

describe('visibleRows', () => {
  const open = pr({ id: 1, number: 1 });
  const requested = pr({ id: 2, number: 2, origin: 'REVIEW_REQUESTED' });
  const mergedToday = pr({ id: 3, number: 3, state: 'merged', mergedAt: new Date().toISOString() });
  const mergedYesterday = pr({ id: 4, number: 4, state: 'merged', mergedAt: yesterday, updatedAt: yesterday });
  const all = [open, requested, mergedToday, mergedYesterday];

  it('shows open PRs plus PRs cleared today, never older closed ones', () => {
    expect(visibleRows(all, 'all').map(row => row.id).sort()).toEqual([1, 2, 3]);
  });

  it('applies the origin filters', () => {
    expect(visibleRows(all, 'review').map(row => row.id)).toEqual([2]);
    expect(visibleRows(all, 'mine').map(row => row.id).sort()).toEqual([1, 3]);
  });

  it('sorts newest activity first', () => {
    const stale = pr({ id: 5, number: 5, updatedAt: yesterday });
    const sorted = visibleRows([stale, open], 'all');
    expect(sorted.map(row => row.id)).toEqual([1, 5]);
  });
});

describe('pullRowFromDto', () => {
  it('stringifies the numeric id and strips the author @', () => {
    const row = pullRowFromDto(pr({ id: 42, author: '@octocat' }));
    expect(row.id).toBe('42');
    expect(row.dto.id).toBe('42');
    expect(row.author).toBe('octocat');
  });

  it('maps CI status and labels through the shared row model', () => {
    const row = pullRowFromDto(pr({ ciStatus: 'FAILING', labels: ['ui'], labelColors: { ui: '0969da' } }));
    expect(row.status).toBe('failed');
    expect(row.chips[0].bg).toBe('rgba(9,105,218,0.14)');
  });

  it('derives hasAgent from an attached review round', () => {
    expect(pullRowFromDto(pr({ reviewRound: 2 })).hasAgent).toBe(true);
    expect(pullRowFromDto(pr({ reviewRound: null })).hasAgent).toBe(false);
    expect(pullRowFromDto(pr({})).hasAgent).toBe(false);
  });
});
