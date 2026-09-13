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

import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import type { Bridge, DiffCommitDto, DiffFileDto, PullRequestDetailDto, PullTimelinePayloadDto } from '../../types';
import PullsScreen, { remotePullRequest } from '../PullsScreen';

const settle = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
function assert(condition: boolean, message: string): asserts condition { if (!condition) throw new Error(message); }
const payload: PullTimelinePayloadDto = {
  actorAvatarUrl: null, actorBot: false, state: null, htmlUrl: null, label: null, labelColor: null,
  renameFrom: null, renameTo: null, commitSha: null, commitMessage: null, sourceTitle: null, sourceUrl: null,
  sourceNumber: null, milestoneTitle: null, requestedReviewer: null, path: null, diffHunk: null, line: null,
  originalLine: null, startLine: null, originalStartLine: null, side: null, resolved: false, outdated: false, reactions: [], comments: [],
};
const file = (filename: string): DiffFileDto => ({ filename, status: 'modified', additions: 1, deletions: 1, patch: '@@ -1 +1 @@\n-old\n+new' });
const commits: DiffCommitDto[] = ['one', 'two'].map((sha, index): DiffCommitDto => ({ sha, parentSha: index === 0 ? 'base' : 'one', message: sha, author: null, authoredAt: null }));

export async function runMainScreen(): Promise<string> {
  let detail: PullRequestDetailDto = {
    id: 7, repo: 'owner/repo', number: 7, title: 'Implementation', htmlUrl: null, state: 'open', draft: true,
    mergeable: null, mergeableState: null, author: 'alice', authorAvatarUrl: null, createdAt: null, updatedAt: '2026-01-01T00:00:00Z',
    mergedAt: null, baseRef: 'main', headRef: 'feature', body: 'Original description', labels: [], assignees: [], milestone: null,
    reviewers: [], additions: 1, deletions: 1, changedFiles: 1, approvalCount: 0, ciStatus: null, recentActivity: [],
    checkRuns: [], unavailableSections: [], viewerCanWrite: false,
  };
  const row = remotePullRequest(detail, 'bob');
  const comparisons: (string | undefined)[][] = [];
  let defaultReads = 0;
  let poll: (() => void) | null = null;
  const originalTimeout = window.setTimeout;
  window.setTimeout = ((handler: TimerHandler, delay?: number, ...args: unknown[]) => {
    if (delay === 5_000 && typeof handler === 'function') {
      poll = () => handler(...args);
      return 1_000_000;
    }
    return originalTimeout(handler, delay, ...args);
  }) as typeof window.setTimeout;
  const bridge = {
    getUserProfile: async () => ({ login: 'bob', name: null, avatarUrl: '', htmlUrl: '', publicRepos: 0, followers: 0,
      following: 0, bio: null, location: null, company: null, email: null }),
    fetchPrs: async () => ({ pullRequests: [row], page: 0, pageSize: 50, totalPullRequests: 1, openPullRequests: 1, hasMore: false }),
    onPullRequestsSynced: () => () => {},
    listWorkspaces: async () => [],
    fetchPullRequestDetailByRef: async () => detail,
    refreshPullRequestDetailByRef: async () => ({ full: true as const, detail, status: null }),
    refreshAllPullRequestDetailByRef: async () => detail,
    fetchPrDiffFiles: async () => { defaultReads++; return [file('all.txt')]; },
    fetchPrDiffCommits: async () => commits,
    refreshPrDiffFiles: async (_repo, _number, base, head) => { comparisons.push([base, head]); return [file('selected.txt')]; },
    recordRecentTouch: async () => {},
  } satisfies Pick<Bridge, 'getUserProfile' | 'fetchPrs' | 'onPullRequestsSynced' | 'listWorkspaces' | 'fetchPullRequestDetailByRef'
    | 'refreshPullRequestDetailByRef' | 'refreshAllPullRequestDetailByRef' | 'fetchPrDiffFiles' | 'fetchPrDiffCommits'
    | 'refreshPrDiffFiles' | 'recordRecentTouch'>;
  Object.assign(window, { bridge });
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const button = (text: string) => {
    const found = [...host.querySelectorAll<HTMLButtonElement>('button')].find(entry => entry.textContent?.includes(text));
    assert(found !== undefined, `Missing ${text} button`);
    return found;
  };
  const tick = async () => {
    const callback = poll;
    assert(callback !== null, 'Detail polling must schedule its next refresh');
    poll = null;
    callback();
    await settle();
  };
  try {
    flushSync(() => root.render(<PullsScreen />));
    await settle();
    button('Changes').click();
    await settle();
    const choice = host.querySelector<HTMLInputElement>('[aria-label="Select one: one"]');
    assert(choice !== null, 'Main PR page must load its commit picker');
    choice.click();
    await settle();
    assert(comparisons.length === 1 && comparisons[0][0] === 'base' && comparisons[0][1] === 'one'
      && host.textContent?.includes('selected.txt') === true, 'Main PR commit selection must show the requested comparison');
    const add = host.querySelector<HTMLButtonElement>('[aria-label="Add a comment on new line 1"]');
    assert(add !== null, 'Reviewable PR must offer the shared inline composer');
    add.click();
    await settle();
    const input = host.querySelector<HTMLTextAreaElement>('.pull-diff-review textarea');
    assert(input !== null, 'Comment selection must open a composer');
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(input, 'Keep this pending review');
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    button('Add to review').click();
    await settle();
    assert(host.querySelector('[aria-label="Submit review, 1 pending review comment"]') !== null, 'Inline review must stage one pending comment');

    detail = { ...detail, updatedAt: '2026-01-02T00:00:00Z', labels: [{ name: 'reviewed-label', color: 'aabbcc' }], recentActivity: [{
      actor: 'alice', eventType: 'commented', timestamp: '2026-01-02T00:00:00Z', body: 'New timeline discussion', githubId: 99,
      payload: { ...payload, reactions: [{ content: '+1', count: 1, viewerHasReacted: false }] },
    }] };
    await tick();
    assert(host.querySelector<HTMLInputElement>('[aria-label="Deselect one: one"]')?.checked === true && defaultReads === 1,
      'Discussion and reactions must not reset the selected commit or reload all changes');
    assert(host.querySelector('[aria-label="Submit review, 1 pending review comment"]') !== null,
      'Unrelated timeline updates must retain the pending review');
    assert(host.querySelector('.pulls-list')?.textContent?.includes('reviewed-label') === true,
      'The shared detail update must reconcile the visible list row');
    button('PR #7').click();
    await settle();
    assert(host.textContent?.includes('New timeline discussion') === true, 'The overview must render the shared refreshed timeline');
    button('Changes').click();
    await settle();
    assert(host.querySelector<HTMLInputElement>('[aria-label="Deselect one: one"]')?.checked === true,
      'Returning to Changes must preserve the selected commit');
    detail = { ...detail, additions: 2 };
    await tick();
    assert(host.querySelector('[aria-label="Select one: one"]') !== null
      && host.querySelector('[aria-label="Submit review, 1 pending review comment"]') === null,
    'Changed code must reset its obsolete range and staged line comments');
    return 'Main PR page shares commit selection, timeline polling, row reconciliation, and draft invalidation';
  }
  finally {
    flushSync(() => root.unmount());
    host.remove();
    window.setTimeout = originalTimeout;
  }
}
