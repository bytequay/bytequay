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
  const all = [open, staleOpen, requested, merged, handled];

  it('keeps done PRs out of every open tab and in Done', () => {
    expect(rowsForTab(all, 'all').map(r => r.id).sort()).toEqual(['a', 'b', 'c']);
    expect(rowsForTab(all, 'done').map(r => r.id).sort()).toEqual(['d', 'e']);
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
