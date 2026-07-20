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
import type { DashboardPR } from '../types/dashboardPr';
import type { LocalPR } from '../types/localPr';
import { pullRowFromLocal } from './localRow';
import { rowsForTab, toRow } from './model';

function pr(overrides: Partial<DashboardPR>): DashboardPR {
  return {
    id: overrides.id ?? 'trinodb/trino#1',
    repo: 'trinodb/trino',
    number: 1,
    title: 'A change',
    author: 'octocat',
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

describe('rowsForTab', () => {
  const open = pr({ id: 'a', updatedAt: new Date().toISOString() });
  const staleOpen = pr({ id: 'b', updatedAt: new Date(Date.now() - 30 * 86400_000).toISOString() });
  const requested = pr({ id: 'c', origin: 'REVIEW_REQUESTED' });
  const merged = pr({ id: 'd', state: 'merged', mergedAt: new Date().toISOString() });
  const handled = pr({ id: 'e', handledAction: 'APPROVED' as DashboardPR['handledAction'] });
  // A review-requested PR (someone else's) that merged — done, but NOT mine.
  const reqMerged = pr({ id: 'f', origin: 'REVIEW_REQUESTED', state: 'merged', mergedAt: new Date().toISOString() });
  const all = [open, staleOpen, requested, merged, handled, reqMerged];

  it('keeps done PRs out of every open tab; Done is only my own merged PRs', () => {
    expect(rowsForTab(all, 'all').map(r => r.id).sort()).toEqual(['a', 'b', 'c']);
    // 'e' (handled but still open) and 'f' (merged but not mine) are excluded.
    expect(rowsForTab(all, 'done').map(r => r.id).sort()).toEqual(['d']);
  });

  it('Active drops rows without recent activity', () => {
    expect(rowsForTab(all, 'active').map(r => r.id).sort()).toEqual(['a', 'c']);
  });

  it('Review requests filters on origin', () => {
    expect(rowsForTab(all, 'req').map(r => r.id)).toEqual(['c']);
  });

  it('sorts newest activity first', () => {
    expect(rowsForTab(all, 'all').map(r => r.id).indexOf('b')).toBe(2);
  });
});

describe('toRow', () => {
  it('maps CI status, merged kind, and agent flag', () => {
    const row = toRow(pr({ ciStatus: 'PENDING', state: 'merged', reviewState: 'running' }));
    expect(row.status).toBe('running');
    expect(row.kind).toBe('merged');
    expect(row.hasAgent).toBe(true);
  });

  it('prefers real GitHub label colors over the named fallback map', () => {
    const row = toRow(pr({ labels: ['ui'], labelColors: { ui: '0969da' } }));
    expect(row.chips[0].bg).toBe('rgba(9,105,218,0.14)');
    const fallback = toRow(pr({ labels: ['ui'], labelColors: null }));
    expect(fallback.chips[0].fg).toBe('#0f766e');
  });
});

describe('pullRowFromLocal', () => {
  it('keeps the standalone deep-link mapping available to embedded PR panes', () => {
    const localPr: LocalPR = {
      id: 'task-pr', taskId: 'task-1', branchName: 'codex/fix', baseBranch: 'master',
      title: 'Fix it', description: '', status: 'remote-drafted', createdAt: 1000,
      pushedAt: null, remotePrNumber: 42, remotePrUrl: 'https://github.com/trinodb/trino/pull/42',
      mergedAt: null, closedAt: null, origin: 'task', repo: 'trinodb/trino', author: '@octocat',
      syncedAt: 2000, syncedAdditions: 7, syncedDeletions: 3, syncedMergeable: true,
      syncedMergeableState: 'clean', syncedMergeQueueEnabled: false, syncedMergeQueueState: null,
      branchDeletedAt: null,
    };

    const row = pullRowFromLocal(localPr, 'trinodb/trino', 42);

    expect(row).toMatchObject({
      id: 'task-pr', repo: 'trinodb/trino', num: 42, title: 'Fix it', author: 'octocat',
      kind: 'pr', add: 7, del: 3,
    });
    expect(row.dto).toMatchObject({
      id: 'task-pr', number: 42, draft: true, state: 'open', mergeable: true,
    });
  });
});
