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
import {
  filterPullRequests,
  parsePullSearch,
  PULL_TABS,
  reconcilePostedComment,
  reconcilePullRequestDetail,
  rowsForTab,
  tabForInitialPullRequest,
  visibleSelectedId,
} from './model';
import { pr } from './fixtures/pullRequest';


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

  it('leaves merged and closed work out of My work', () => {
    const open = pr({ id: 1, origin: 'AUTHORED', state: 'open' });
    const merged = pr({ id: 2, origin: 'AUTHORED', state: 'merged' });
    const closed = pr({ id: 3, origin: 'AUTHORED', state: 'closed' });
    const someoneElse = pr({ id: 4, author: 'someone-else', origin: 'OTHER', state: 'open' });

    expect(rowsForTab([open, merged, closed, someoneElse], 'active', 'jack').map(row => row.id)).toEqual([1]);
    expect(rowsForTab([merged, closed], 'done', 'jack').map(row => row.id)).toEqual([2, 3]);
    expect(tabForInitialPullRequest(merged, 'active', 'jack')).toBe('done');
  });

  it('uses the viewer login instead of a stale authored origin', () => {
    const mine = pr({ id: 1, author: 'Jack', origin: 'OTHER' });
    const stale = pr({ id: 2, author: 'someone-else', origin: 'AUTHORED' });
    const reviewed = pr({
      id: 3,
      author: 'review-author',
      origin: 'AUTHORED',
      handledAction: 'APPROVED',
      reviewedAt: '2026-08-22T09:00:00Z',
    });

    expect(rowsForTab([mine, stale, reviewed], 'active', 'jack').map(row => row.id)).toEqual([1]);
    expect(rowsForTab([reviewed], 'reviewed', 'jack').map(row => row.id)).toEqual([3]);
    expect(tabForInitialPullRequest(stale, 'active', 'jack')).toBe('all');
    expect(rowsForTab([stale], 'active', null).map(row => row.id)).toEqual([2]);
  });

  it('orders the filters the way the shortcuts and the tab slots read them', () => {
    expect(PULL_TABS.map(tab => tab.key)).toEqual(['req', 'all', 'active', 'monitored', 'reviewed', 'done']);
    expect(PULL_TABS.map(tab => tab.label)).toEqual(['Review', 'Open', 'My work', 'Monitored', 'Reviewed', 'Closed']);
  });

  it('preserves monitoring state in pull request cards', () => {
    const monitored = pr({ id: 1, monitoredAt: '2026-09-06T00:00:00Z' });
    const unmonitored = pr({ id: 2, monitoredAt: null });

    expect(rowsForTab([monitored, unmonitored], 'all').map(row => row.monitored)).toEqual([true, false]);
  });

  it('lists the pinned set under Monitored, whoever authored it and whether or not it closed', () => {
    const mine = pr({ id: 1, monitoredAt: '2026-09-06T00:00:00Z', origin: 'AUTHORED' });
    const theirs = pr({ id: 2, monitoredAt: '2026-09-05T00:00:00Z', origin: 'OTHER' });
    const merged = pr({ id: 3, monitoredAt: '2026-09-04T00:00:00Z', state: 'merged' });
    const unpinned = pr({ id: 4, monitoredAt: null });

    expect(rowsForTab([mine, theirs, merged, unpinned], 'monitored').map(row => row.id).sort())
      .toEqual([1, 2, 3]);
    expect(rowsForTab([unpinned], 'monitored')).toEqual([]);
  });

  it('keeps a commented pull request open after it leaves Needs review', () => {
    const current = pr({ id: 1, origin: 'REVIEW_REQUESTED' });
    const waiting = pr({ id: 2, origin: 'REVIEW_REQUESTED' });
    const selected = visibleSelectedId(rowsForTab([current, waiting], 'req'), null);
    const commented = reconcilePostedComment(current, current, 'review', '2026-08-25T00:00:00Z');
    const needsReview = rowsForTab([commented, waiting], 'req');

    expect(selected).toBe(current.id);
    expect(needsReview.map(row => row.id)).toEqual([waiting.id]);
    expect(visibleSelectedId(needsReview, selected)).toBe(commented.id);
    expect(visibleSelectedId(needsReview, null)).toBe(waiting.id);
    expect(rowsForTab([commented, waiting], 'reviewed').map(row => row.id)).toEqual([commented.id]);
  });

  it('increments the row comment count after a successful discussion post', () => {
    const commented = reconcilePostedComment(
      pr({ commentCount: 4 }),
      { state: 'open', updatedAt: '2026-08-25T01:00:00Z' },
      'discussion',
      '2026-08-25T01:00:00Z',
    );

    expect(commented.commentCount).toBe(5);
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

  it('projects optimistic labels and reviewers into the list row', () => {
    const current = pr({ labels: ['old'], origin: 'OTHER', requestedReviewers: [] });
    const updated = reconcilePullRequestDetail(current, {
      state: 'open',
      updatedAt: '2026-08-21T01:00:00Z',
      author: 'alice',
      labels: [{ name: 'docs', color: 'aabbcc' }],
      reviewers: [{ login: 'bob', avatarUrl: null, state: 'PENDING' }],
    }, 'bob');
    const removed = reconcilePullRequestDetail(updated, {
      state: 'open',
      updatedAt: '2026-08-21T01:00:00Z',
      author: 'alice',
      reviewers: [],
    }, 'bob');

    expect(updated.labels).toEqual(['docs']);
    expect(updated.labelColors).toEqual({ docs: 'aabbcc' });
    expect(updated.requestedReviewers).toEqual(['bob']);
    expect(updated.origin).toBe('REVIEW_REQUESTED');
    expect(removed.origin).toBe('OTHER');
  });

  it('preserves a newer review request when cached detail has older reviewers', () => {
    const current = pr({ origin: 'REVIEW_REQUESTED', requestedReviewers: ['bob'], labels: ['new'], updatedAt: '2026-09-28T01:00:00Z' });
    const updated = reconcilePullRequestDetail(current, {
      state: 'open', updatedAt: '2026-09-28T00:00:00Z', author: 'alice', labels: [], reviewers: [],
    }, 'bob');

    expect(updated).toBe(current);
    expect(rowsForTab([updated], 'req').map(row => row.id)).toEqual([current.id]);
  });
});

describe('pull search', () => {
  it('reads an at-marked term as an author and keeps the rest as text', () => {
    expect(parsePullSearch('')).toBeNull();
    expect(parsePullSearch('   ')).toBeNull();
    expect(parsePullSearch('  @Alice  ')).toEqual({ author: 'Alice', text: '' });
    expect(parsePullSearch('@alice flaky test')).toEqual({ author: 'alice', text: 'flaky test' });
    expect(parsePullSearch('flaky test @alice')).toEqual({ author: 'alice', text: 'flaky test' });
    expect(parsePullSearch('flaky')).toEqual({ author: null, text: 'flaky' });
    expect(parsePullSearch('#42')).toEqual({ author: null, text: '#42' });
  });

  it('matches an author by prefix, case-insensitively', () => {
    const alice = pr({ id: 1, author: 'alice', title: 'Fix flaky test' });
    const albert = pr({ id: 2, author: 'albert', title: 'Fix flaky test' });
    const bob = pr({ id: 3, author: 'bob', title: 'Fix flaky test' });
    const noAuthor = pr({ id: 4, author: null, title: 'Fix flaky test' });

    expect(filterPullRequests([alice, albert, bob, noAuthor], parsePullSearch('@AL')).map(row => row.id)).toEqual([1, 2]);
    expect(filterPullRequests([alice, albert, bob], parsePullSearch('@ALICE')).map(row => row.id)).toEqual([1]);
    expect(filterPullRequests([alice, albert, bob], parsePullSearch('@lice'))).toEqual([]);
    expect(filterPullRequests([alice, albert], parsePullSearch('@carol'))).toEqual([]);
    expect(filterPullRequests([alice, albert, bob], parsePullSearch('@al flaky')).map(row => row.id)).toEqual([1, 2]);
    expect(filterPullRequests([alice, albert, bob], parsePullSearch('@al absent')).map(row => row.id)).toEqual([]);
    expect(filterPullRequests([alice, bob], null).map(row => row.id)).toEqual([1, 3]);
  });

  it('matches a number term against the pull request number, not the title', () => {
    const numbered = pr({ id: 1, number: 413, title: 'Fix flaky test' });
    const titled = pr({ id: 2, number: 7, title: 'Backport 413' });

    expect(filterPullRequests([numbered, titled], parsePullSearch('#41')).map(row => row.id)).toEqual([1]);
    expect(filterPullRequests([numbered, titled], parsePullSearch('41')).map(row => row.id)).toEqual([1]);
    expect(filterPullRequests([numbered, titled], parsePullSearch('#7')).map(row => row.id)).toEqual([2]);
    expect(filterPullRequests([numbered, titled], parsePullSearch('#4137'))).toEqual([]);
    expect(filterPullRequests([numbered, titled], parsePullSearch('flaky')).map(row => row.id)).toEqual([1]);
  });

  it('keeps an author search inside the selected filter', () => {
    const mine = pr({ id: 1, author: 'alice', origin: 'AUTHORED' });
    const theirs = pr({ id: 2, author: 'alice', origin: 'REVIEW_REQUESTED' });

    const rows = rowsForTab(filterPullRequests([mine, theirs], parsePullSearch('@alice')), 'active');

    expect(rows.map(row => row.id)).toEqual([1]);
  });
});
