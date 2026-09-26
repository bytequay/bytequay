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
import type { Bridge, DiffCommitDto, DiffFileDto, PullRequestDetailDto } from '../../types';
import PullsScreen from '../PullsScreen';
import { createPreviewBridge } from '../../agents/fixtures/previewBridge';
import { reviewFixture } from '../agent-review/fixtures/review';
import '../../css/base.css';
import { assert, settle } from '../../fixtures/harness';

const review = reviewFixture({ id: 'pending-review', state: 'CLOSED', closedAt: '2026-09-07T00:00:00Z' });
const stored: PullRequestDetailDto = {
  id: 9001, repo: review.repo, number: review.number, title: 'Sort by nested fields', htmlUrl: null, state: 'open', draft: false,
  mergeable: null, mergeableState: null, author: 'alice', authorAvatarUrl: null, createdAt: null, updatedAt: '2026-01-01T00:00:00Z',
  mergedAt: null, baseRef: 'master', headRef: 'feature', body: 'Implementation', labels: [], assignees: [], milestone: null,
  reviewers: [{ login: 'bob', avatarUrl: null, state: 'PENDING' }], additions: 1, deletions: 1, changedFiles: 1,
  approvalCount: 0, ciStatus: null, recentActivity: [], checkRuns: [], unavailableSections: [], viewerCanWrite: true,
};
const files: DiffFileDto[] = [{ filename: 'src/Example.java', status: 'modified', additions: 1, deletions: 1, patch: '@@ -1 +1 @@\n-old\n+new' }];
const commits: DiffCommitDto[] = [
  { sha: 'one', parentSha: 'base', message: 'one', author: null, authoredAt: null },
  { sha: 'two', parentSha: 'one', message: 'two', author: null, authoredAt: null },
];

/**
 * A staged review lasts as long as the pull request page.
 *
 * A background refresh used to drop it: the staged comments were keyed to the diff revision, and
 * GitHub restates a busy pull request's totals within seconds of opening it, so the comments were
 * gone by the time the reader came back from another tab.
 */
export async function run(): Promise<{ name: string; error?: string }[]> {
  const agent = createPreviewBridge(review.workspaceId, { replayThrough: Number.MAX_SAFE_INTEGER });
  let detail = stored;
  let reviewedReads = 0;
  const submissions: Parameters<Bridge['submitReview']>[] = [];
  let polls: (() => void)[] = [];
  const originalTimeout = window.setTimeout;
  window.setTimeout = ((handler: TimerHandler, delay?: number, ...args: unknown[]) => {
    if (delay === 5_000 && typeof handler === 'function') {
      polls.push(() => handler(...args));
      return 1_000_000;
    }
    return originalTimeout(handler, delay, ...args);
  }) as typeof window.setTimeout;
  const stubs = {
    getUserProfile: async () => ({ login: 'bob', name: null, avatarUrl: '', htmlUrl: '', publicRepos: 0, followers: 0,
      following: 0, bio: null, location: null, company: null, email: null }),
    readAgentReviewHistory: async (_id, taskId, before, after) => agent.readAgentHistory(taskId, before, after),
    fetchAttentionPrs: async () => [],
    fetchPullRequestDetailByRef: async () => detail,
    refreshPullRequestDetailByRef: async () => ({ full: true as const, detail, status: null }),
    refreshAllPullRequestDetailByRef: async () => detail,
    fetchPrDiffFiles: async () => files,
    fetchPrDiffCommits: async () => commits,
    refreshPrDiffFiles: async () => files,
    readWorktreeChanges: async (host, request) => {
      assert(host.kind === 'review' && host.reviewId === review.id, 'Reviewed changes must read the separate reviewer worktree');
      reviewedReads++;
      return { branch: 'reviewer', baseSha: 'base', headSha: 'local-only', baseLabel: 'master', scope: request.scope,
        selectedCommit: null, sourceFingerprint: 'local-only', truncated: false, staged: [], unstaged: ['local.txt'], untracked: [],
        files: [{ filename: 'local.txt', status: 'modified', additions: 1, deletions: 0, patch: '@@ -0,0 +1 @@\n+Unpublished reviewer edit' }], commits: [] };
    },
    getAgentReview: async () => review,
    findAgentReview: async () => review,
    findPullRequestTask: async () => null,
    listWorkspaces: async () => [],
    listLocalReviewComments: async () => [],
    listCodeFiles: async () => ({ source: 'WORKING_COPY', revision: 'one', truncated: false, codeGraph: 'OFF',
      sourceFingerprint: null, savedExperiment: false, files: [] }),
    listCodeRuns: async () => [],
    listAgentReviews: async () => [review],
    subscribeAgentReview: async () => () => {},
    fetchMergeMode: async () => ({ mergeQueue: false }),
    submitReview: async (...request) => { submissions.push(request); return detail; },
    recordRecentTouch: async () => {},
  } satisfies Pick<Bridge, 'getUserProfile' | 'readAgentReviewHistory' | 'fetchAttentionPrs' | 'fetchPullRequestDetailByRef'
    | 'refreshPullRequestDetailByRef' | 'refreshAllPullRequestDetailByRef' | 'fetchPrDiffFiles' | 'fetchPrDiffCommits'
    | 'refreshPrDiffFiles' | 'readWorktreeChanges' | 'getAgentReview' | 'findAgentReview' | 'findPullRequestTask' | 'listWorkspaces'
    | 'listLocalReviewComments' | 'listCodeFiles' | 'listCodeRuns' | 'listAgentReviews' | 'subscribeAgentReview'
    | 'fetchMergeMode' | 'submitReview' | 'recordRecentTouch'>;
  const previous = window.bridge;
  Object.assign(window, { bridge: { ...agent, ...stubs } });
  const host = document.createElement('main');
  host.style.cssText = 'height: 900px; width: 1600px';
  document.body.append(host);
  const root = createRoot(host);
  const button = (text: string) => {
    const found = [...host.querySelectorAll<HTMLButtonElement>('button')].find(entry => entry.textContent?.includes(text));
    assert(found !== undefined, `Missing ${text} button`);
    return found;
  };
  const named = (text: string) => [...host.querySelectorAll<HTMLButtonElement>('button')].some(entry => entry.textContent?.includes(text));
  /** The staged comment counted in the toolbar and drawn on the line it was written on. */
  const staged = async (): Promise<boolean> => {
    for (let attempt = 0; attempt < 30; attempt++) {
      if (host.querySelector('[aria-label^="Submit review, 1 pending"]') !== null
        && host.textContent?.includes('Keep this pending review') === true) return true;
      await settle();
    }
    return false;
  };
  // The detail poll, answered the way a busy pull request answers it.
  const refreshed = async (value: PullRequestDetailDto) => {
    detail = value;
    assert(polls.length > 0, 'Detail polling must schedule its next refresh');
    const due = polls;
    polls = [];
    for (const callback of due) callback();
    for (let attempt = 0; attempt < 10; attempt++) await settle();
  };
  try {
    flushSync(() => root.render(<PullsScreen initialPr={{ repo: review.repo, number: review.number }} initialReviewId={review.id}
      repositoryScope={{ workspaceId: review.workspaceId, repository: review.repo }} reviewWorkspace={{ onBack: () => {} }} />));
    for (let attempt = 0; attempt < 60 && !named('WorkBench'); attempt++) await settle();
    button('WorkBench').click(); await settle();
    button('Changes').click(); await settle();
    assert(host.querySelector('[aria-label="Changes source"]') === null,
      'A linked PR must expose a single Changes view without a source selector');
    for (let attempt = 0; attempt < 30 && host.querySelector('[aria-label="Add a comment on new line 1"]') === null; attempt++) await settle();
    const add = host.querySelector<HTMLButtonElement>('[aria-label="Add a comment on new line 1"]');
    assert(add !== null, 'The diff must offer the inline comment gutter');
    add.click();
    await settle();
    const input = host.querySelector<HTMLTextAreaElement>('.pull-diff-review textarea');
    assert(input !== null, 'Selecting a line must open a composer');
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(input, 'Keep this pending review');
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    button('Add to review').click();
    await settle();
    assert(await staged(), 'Adding an inline comment must stage it on the diff');

    assert(reviewedReads === 0 && !host.textContent?.includes('Unpublished reviewer edit'),
      'A different reviewer working copy must not replace the published diff');

    button('PR #').click();
    await settle();
    button('WorkBench').click(); await settle();
    button('Code & Tests').click();
    for (let attempt = 0; attempt < 30 && host.querySelector('.pull-detail__code') === null; attempt++) await settle();
    button('WorkBench').click(); await settle();
    button('Changes').click(); await settle();
    assert(await staged(), 'A trip through the other panel tabs must keep the staged review');

    // GitHub restates a busy pull request's totals on the next refresh, which used to reach the
    // staged review through the diff revision.
    await refreshed({ ...detail, additions: 2, updatedAt: '2026-01-02T00:00:00Z' });
    assert(await staged(), 'A background detail refresh must keep the staged review');

    const range = host.querySelector<HTMLInputElement>('[aria-label="Select two: two"]');
    assert(range !== null, 'The diff must offer its commit picker');
    range.click();
    for (let attempt = 0; attempt < 30 && host.querySelector('[aria-label="Deselect two: two"]') === null; attempt++) await settle();
    assert(await staged(), 'Narrowing the diff to selected commits must keep the staged review');

    button('Summary').click(); await settle();
    button('Submit review').click(); await settle();
    const submit = host.querySelector<HTMLButtonElement>('.pr-review-sheet__footer .is-primary');
    assert(submit !== null && !submit.disabled, 'WorkBench Summary must open the shared review dialog with the staged comment');
    assert(submissions.length === 0, 'Opening and staging review comments must not publish them');
    submit.click(); await settle();
    assert(Number(submissions.length) === 1, 'The explicit submit action must publish the review exactly once');
    const [, repo, number, event, , comments] = submissions[0];
    assert(repo === detail.repo && number === detail.number && event === 'APPROVE' && comments?.length === 1
      && comments[0].path === files[0].filename && comments[0].side === 'RIGHT' && comments[0].line === 1
      && comments[0].commitId === 'two' && comments[0].body === 'Keep this pending review',
    'Submitting from WorkBench must preserve the PR, revision, line, and staged comment');
    assert(host.querySelector('.pr-review-sheet') === null && host.querySelector('[aria-label^="Submit review, 1 pending"]') === null,
      'Successful submission must close the dialog and clear the staged comment');
    return [{ name: 'A staged review lasts as long as the pull request page' }];
  }
  catch (error) { return [{ name: 'A staged review lasts as long as the pull request page', error: error instanceof Error ? error.message : String(error) }]; }
  finally {
    window.setTimeout = originalTimeout;
    flushSync(() => root.unmount());
    host.remove();
    Object.assign(window, { bridge: previous });
  }
}
