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
import type { AgentStreamMessage, Bridge, DiffCommitDto, DiffFileDto, InlineReviewCommentDto, PullRequestDetailDto, PullTimelinePayloadDto } from '../../types';
import PullsScreen, { remotePullRequest, ReviewDialog } from '../PullsScreen';
import ReviewTaskScreen from '../../agents/ReviewTaskScreen';
import type { AgentEventEnvelope } from '../../agents/events';
import { createPreviewBridge } from '../../agents/fixtures/previewBridge';
import type { AgentReviewDto, ReviewDraftDto } from '../agent-review/contracts';
import { reviewFixture } from '../agent-review/fixtures/review';
import '../../css/base.css';

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
const reviewDetail = (review: AgentReviewDto): PullRequestDetailDto => ({
  id: 42, repo: review.repo, number: review.number, title: 'Review this retained pull request', htmlUrl: null, state: 'open', draft: true,
  mergeable: null, mergeableState: null, author: 'alice', authorAvatarUrl: null, createdAt: null, updatedAt: null,
  mergedAt: null, baseRef: 'main', headRef: 'feature', body: 'Pull request implementation', labels: [], assignees: [], milestone: null,
  reviewers: [], additions: 1, deletions: 1, changedFiles: 1, approvalCount: 0, ciStatus: null, recentActivity: [],
  checkRuns: [], unavailableSections: [], viewerCanWrite: false,
});

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

export async function runReviewWorkspace(): Promise<string> {
  const agent = createPreviewBridge('workspace-1', { replayThrough: Number.MAX_SAFE_INTEGER });
  const review = reviewFixture({ id: 'retained-review', state: 'CLOSED', closedAt: '2026-09-07T00:00:00Z' });
  review.task = { ...review.task, id: agent.events[0].taskId };
  const detail = reviewDetail(review);
  const reviewReads: string[] = [];
  let historyReads = 0;
  let listReads = 0;
  let back = 0;
  const bridge = {
    getUserProfile: async () => ({ login: 'bob', name: null, avatarUrl: '', htmlUrl: '', publicRepos: 0, followers: 0,
      following: 0, bio: null, location: null, company: null, email: null }),
    getModelSettings: agent.getModelSettings,
    getAgentTurn: agent.getAgentTurn,
    getAgentReview: async id => {
      reviewReads.push(id);
      assert(id === review.id, 'Workspace reads must keep the exact retained review ID');
      return review;
    },
    findAgentReview: async () => { throw new Error('An exact retained review must not be replaced by the newest review'); },
    readAgentReviewHistory: async (id, taskId, before, after) => {
      assert(id === review.id && taskId === review.task.id, 'Conversation history must stay scoped to the retained review and task');
      historyReads++;
      return agent.readAgentHistory(taskId, before, after);
    },
    fetchAttentionPrs: async () => [],
    fetchPrs: async () => { listReads++; throw new Error('A review workspace must not load the PR list'); },
    fetchPullRequestDetailByRef: async (repo, number) => {
      assert(repo === review.repo && number === review.number, 'Review workspace must open the owning pull request');
      return detail;
    },
    refreshPullRequestDetailByRef: async () => ({ full: true as const, detail, status: null }),
    listLocalReviewComments: async () => [],
    recordRecentTouch: async () => {},
  } satisfies Pick<Bridge, 'getUserProfile' | 'getModelSettings' | 'getAgentTurn' | 'getAgentReview' | 'findAgentReview'
    | 'readAgentReviewHistory' | 'fetchAttentionPrs' | 'fetchPrs' | 'fetchPullRequestDetailByRef'
    | 'refreshPullRequestDetailByRef' | 'listLocalReviewComments' | 'recordRecentTouch'>;
  const originalBridge = window.bridge;
  Object.assign(window, { bridge });
  const host = document.createElement('div');
  host.style.width = '1200px';
  host.style.height = '800px';
  document.body.append(host);
  const root = createRoot(host);
  try {
    flushSync(() => root.render(<ReviewTaskScreen workspaceId={review.workspaceId} reviewId={review.id} browserOpen={false}
      onBack={() => { back++; }} />));
    for (let attempt = 0; attempt < 20 && !host.textContent?.includes('I found one authentication regression.'); attempt++) await settle();
    const conversation = host.querySelector<HTMLElement>('[aria-label="Review agent conversation"]');
    const pane = host.querySelector<HTMLElement>('.pull-detail');
    assert(conversation?.textContent?.includes('I found one authentication regression.') === true,
      'The left workspace conversation must replay the review agent response');
    assert(pane?.textContent?.includes('PR intention') === true && pane.querySelector('[data-finding-id="finding-1"]') !== null,
      'The right PR pane must show independent learning and finding cards');
    assert(conversation.parentElement === pane.parentElement
      && Boolean(conversation.compareDocumentPosition(pane) & Node.DOCUMENT_POSITION_FOLLOWING),
    'Conversation and PR cards must be sibling panes in left-to-right order');
    assert(host.querySelector('.pulls-list') === null && listReads === 0 && host.querySelector('[aria-modal="true"]') === null,
      'The workspace layout must show neither a PR list nor a modal');
    assert(reviewReads.length >= 2 && reviewReads.every(id => id === review.id) && historyReads > 0,
      'Opening and loading the workspace must retain its exact review identity');
    assert(!host.textContent?.includes('Summary & comments') && host.querySelector('.agr-strip__tabs') === null,
      'Review cards must not restore Summary or Activity subtabs');
    const divider = host.querySelector<HTMLElement>('[aria-label="Resize side panel"]');
    assert(divider !== null && divider.nextElementSibling === pane && getComputedStyle(divider).cursor === 'col-resize',
      'The review workspace must use the same visible draggable separator as development tasks');
    const initialWidth = pane.getBoundingClientRect().width;
    const initialConversationWidth = conversation.getBoundingClientRect().width;
    const drag = async (distance: number) => {
      const startX = divider.getBoundingClientRect().left;
      divider.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, button: 0, clientX: startX }));
      await settle();
      assert(divider.classList.contains('is-resizing'), 'Dragging must show the shared active divider state');
      window.dispatchEvent(new MouseEvent('mousemove', { clientX: startX + distance }));
      await settle();
      window.dispatchEvent(new MouseEvent('mouseup'));
      await settle();
      assert(!divider.classList.contains('is-resizing') && document.body.style.cursor === '' && document.body.style.userSelect === '',
        'Releasing the divider must restore normal pointer and text selection');
    };
    await drag(80);
    const resizedWidth = pane.getBoundingClientRect().width;
    assert(Math.abs(initialWidth - resizedWidth - 80) < 1
      && Math.abs(conversation.getBoundingClientRect().width - initialConversationWidth - 80) < 1,
    `Dragging must resize the visible panes immediately, including when CSS constrained the starting width: PR ${initialWidth} -> ${resizedWidth}, conversation ${initialConversationWidth} -> ${conversation.getBoundingClientRect().width}`);
    pane.querySelector<HTMLButtonElement>('.pull-detail__pr-tab')?.click(); await settle();
    const learningTab = [...pane.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.includes('Review & Learning'));
    assert(learningTab !== undefined, 'The resized PR must retain its learning tab');
    learningTab.click(); await settle();
    assert(Math.abs(pane.getBoundingClientRect().width - resizedWidth) < 1,
      'Changing PR tabs must retain the user-selected pane width');
    await drag(5000);
    assert(pane.style.getPropertyValue('width') === '360px', 'The shared pane minimum must keep the PR readable');
    await drag(-5000);
    assert(pane.style.getPropertyValue('width') === '1600px' && conversation.getBoundingClientRect().width > 0
      && pane.getBoundingClientRect().right <= host.getBoundingClientRect().right + 1,
    'The shared maximum and CSS bounds must retain the conversation without overflowing the workspace');
    conversation.querySelector<HTMLButtonElement>('[aria-label="Back to tasks"]')?.click();
    assert(back === 1, 'The review workspace back action must return to its task list');
    return 'Retained review workspace replays its conversation beside learning cards and returns to tasks';
  }
  finally {
    flushSync(() => root.unmount());
    host.remove();
    Object.assign(window, { bridge: originalBridge });
  }
}

export async function runOpenReviewWorkspaceStream(): Promise<string> {
  const agent = createPreviewBridge('workspace-1', { replayThrough: Number.MAX_SAFE_INTEGER });
  const review = reviewFixture({ id: 'open-review' });
  review.task = { ...review.task, id: agent.events[0].taskId };
  const detail = reviewDetail(review);
  const sent = { ...agent.events[0], eventId: 'sent-after-remount', sequence: agent.events.length + 1, turnId: 'turn-after-remount',
    payload: { text: 'Check the new head again', attachments: [], deliveryKind: 'TURN' } } as AgentEventEnvelope;
  let delivered = false;
  // Main keeps one reader per renderer and review: a later subscription silently replaces the earlier one.
  const streams: { listener: (message: AgentStreamMessage) => void; live: boolean }[] = [];
  const bridge = {
    getUserProfile: async () => ({ login: 'bob', name: null, avatarUrl: '', htmlUrl: '', publicRepos: 0, followers: 0,
      following: 0, bio: null, location: null, company: null, email: null }),
    getModelSettings: agent.getModelSettings,
    getAgentTurn: agent.getAgentTurn,
    getAgentReview: async () => review,
    readAgentReviewHistory: async (_id, taskId, before, after) => {
      const page = await agent.readAgentHistory(taskId, before, after);
      return delivered && before === undefined && after === undefined
        ? { ...page, events: [...page.events, sent], latestSequence: sent.sequence } : page;
    },
    subscribeAgentReview: async (_id, _after, listener) => {
      for (const stream of streams) stream.live = false;
      const stream = { listener, live: true };
      streams.push(stream);
      return () => { stream.live = false; };
    },
    fetchAttentionPrs: async () => [],
    fetchPullRequestDetailByRef: async () => detail,
    refreshPullRequestDetailByRef: async () => ({ full: true as const, detail, status: null }),
    listLocalReviewComments: async () => [],
    recordRecentTouch: async () => {},
  } satisfies Pick<Bridge, 'getUserProfile' | 'getModelSettings' | 'getAgentTurn' | 'getAgentReview' | 'readAgentReviewHistory'
    | 'subscribeAgentReview' | 'fetchAttentionPrs' | 'fetchPullRequestDetailByRef' | 'refreshPullRequestDetailByRef' | 'listLocalReviewComments' | 'recordRecentTouch'>;
  const originalBridge = window.bridge;
  Object.assign(window, { bridge });
  const host = document.createElement('div');
  host.style.width = '1200px';
  host.style.height = '800px';
  document.body.append(host);
  const root = createRoot(host);
  const conversation = () => host.querySelector<HTMLElement>('[aria-label="Review agent conversation"]')?.textContent ?? '';
  try {
    flushSync(() => root.render(<ReviewTaskScreen workspaceId={review.workspaceId} reviewId={review.id} browserOpen={false} onBack={() => {}} />));
    for (let attempt = 0; attempt < 20 && !(conversation().includes('I found one authentication regression.') && streams.length > 0); attempt++) await settle();
    await settle();
    const pane = host.querySelector<HTMLElement>('.pull-detail');
    assert(pane?.textContent?.includes('PR intention') === true, 'The open review must show its learning cards beside the conversation');
    // Leaving and returning to Review & Learning remounts its cards while the conversation stays mounted.
    pane.querySelector<HTMLButtonElement>('.pull-detail__pr-tab')?.click();
    await settle();
    [...pane.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.includes('Review & Learning'))?.click();
    await settle();
    assert(pane.textContent?.includes('PR intention') === true, 'Returning to Review & Learning must show the cards again');
    delivered = true;
    for (const stream of streams.filter(item => item.live)) {
      stream.listener({ subscriptionId: 'review-stream', taskId: review.task.id, kind: 'changed', sequence: sent.sequence, turnId: sent.turnId });
    }
    for (let attempt = 0; attempt < 40 && !conversation().includes('Check the new head again'); attempt++) await new Promise(resolve => setTimeout(resolve, 25));
    assert(conversation().includes('Check the new head again'), 'A message delivered after the cards remount must reach the conversation');
    assert(streams.length === 1, `The review workspace must open one review stream for both panes, opened ${streams.length}`);
    return 'Open review workspace keeps its conversation live after Review & Learning remounts';
  }
  finally {
    flushSync(() => root.unmount());
    host.remove();
    Object.assign(window, { bridge: originalBridge });
  }
}

export async function runReviewSheet(): Promise<string> {
  const draft: ReviewDraftDto = { repo: 'trinodb/trino', number: 30304, body: '', event: 'COMMENT', version: 4, updatedAt: '2026-09-06T16:20:00Z',
    items: [{ id: 'item-1', source: 'AGENT_FINDING', findingId: 'finding-1', findingRevision: 1, path: 'src/Finding.java', side: 'RIGHT',
      line: 12, startLine: null, body: 'Promoted finding text', included: true, published: false, publication: null }] };
  const review = reviewFixture({ draft });
  const detail: PullRequestDetailDto = {
    id: 9, repo: review.repo, number: review.number, title: 'Sheet', htmlUrl: null, state: 'open', draft: false,
    mergeable: null, mergeableState: null, author: 'alice', authorAvatarUrl: null, createdAt: null, updatedAt: null,
    mergedAt: null, baseRef: 'main', headRef: 'feature', body: null, labels: [], assignees: [], milestone: null,
    reviewers: [], additions: 1, deletions: 1, changedFiles: 1, approvalCount: 0, ciStatus: null, recentActivity: [],
    checkRuns: [], unavailableSections: [], viewerCanWrite: true,
  };
  const written: InlineReviewCommentDto = { path: 'src/Written.java', body: 'Written comment text', line: 3, side: 'RIGHT',
    startLine: null, startSide: null, commitId: review.observedHeadSha };
  const calls: string[] = [];
  const events: string[] = [];
  let closed = false;
  const bridge = {
    addReviewDraftComment: async (_repo, _number, version, comment) => {
      calls.push(`add v${version} ${comment.path}:${comment.line} ${comment.body}`);
      return { ...draft, version: version + 1 };
    },
    submitReviewDraft: async (_repo, _number, version, body, event) => {
      calls.push(`draft v${version} ${event} "${body}"`);
      return { draft, publishedItemIds: ['item-1'], uncertainReason: null };
    },
    submitReview: async () => { calls.push('plain'); return detail; },
    getAgentReview: async () => { calls.push('reload'); return review; },
    fetchPullRequestDetailByRef: async () => { calls.push('detail'); return detail; },
    fetchPrDiffFiles: async () => [
      { filename: 'src/Finding.java', status: 'modified', additions: 1, deletions: 0, patch: '@@ -1,2 +10,3 @@\n a\n b\n+int findingLine = 12;' },
      { filename: 'src/Written.java', status: 'modified', additions: 1, deletions: 0, patch: '@@ -1,2 +1,3 @@\n a\n b\n+int writtenLine = 3;' },
    ],
    fetchPrDiffCommits: async () => [{ sha: review.observedHeadSha ?? 'head', parentSha: null, message: null, author: null, authoredAt: null }],
    getUserProfile: async () => ({ login: 'bob', name: null, avatarUrl: '', htmlUrl: '', publicRepos: 0, followers: 0, following: 0,
      bio: null, location: null, company: null, email: null }),
  } satisfies Pick<Bridge, 'addReviewDraftComment' | 'submitReviewDraft' | 'submitReview' | 'getAgentReview' | 'fetchPullRequestDetailByRef'
    | 'fetchPrDiffFiles' | 'fetchPrDiffCommits' | 'getUserProfile'>;
  const originalBridge = window.bridge;
  Object.assign(window, { bridge });
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const mount = (comments: InlineReviewCommentDto[]) => flushSync(() => root.render(<ReviewDialog pr={remotePullRequest(detail, 'bob')}
    detail={detail} review={review} onReviewChange={() => events.push('review')} pendingComments={comments}
    onPendingCommentsChange={next => events.push(`pending ${next.length}`)}
    onReviewSubmitted={(_value, event) => events.push(`submitted ${event}`)} onClose={() => { closed = true; }} />));
  const submit = async () => {
    host.querySelector<HTMLButtonElement>('.pr-review-sheet__footer .is-primary')?.click();
    for (let attempt = 0; attempt < 10; attempt++) await settle();
  };
  try {
    mount([{ ...written, commitId: 'older-commit' }]);
    await settle();
    const cards = [...host.querySelectorAll('.pr-review-sheet__pending > .pr-inline-thread')];
    assert(cards.length === 2 && cards[0].textContent?.includes('Written comment text') === true
      && cards[1].textContent?.includes('src/Finding.java') === true && cards[1].textContent.includes('Promoted finding text'),
    'The sheet must list added findings beside written comments as review cards');
    assert(cards[1].querySelector('.pr-commented-code-line')?.textContent === '12+int findingLine = 12;'
      && cards[0].querySelector('.pr-commented-code-line') === null,
    `A card shows its commented line only when the stored diff has the comment's revision: ${cards.map(card => card.textContent).join(' | ')}`);
    host.querySelector<HTMLInputElement>('input[type="radio"]')?.click();
    await settle();
    await submit();
    assert(calls.every(call => call === 'reload') && host.querySelector('[role="alert"]')?.textContent?.includes('selected commits') === true,
      `A comment written on selected commits must not be moved onto the current head: ${calls.join(' | ')}`);

    calls.length = 0;
    mount([written]);
    await submit();
    assert(closed && calls.join(' | ') === 'add v4 src/Written.java:3 Written comment text | draft v5 COMMENT "" | reload | detail',
      `Added findings must go out through the draft with the written comments, not the plain review: ${calls.join(' | ')}`);
    assert(events.includes('pending 0') && events.includes('submitted COMMENT') && events.includes('review'),
      `Submitting must clear written comments and refresh the review and pull request: ${events.join(' | ')}`);
    return 'Submit review lists added findings and sends them with written comments through the draft';
  }
  finally {
    flushSync(() => root.unmount());
    host.remove();
    Object.assign(window, { bridge: originalBridge });
  }
}

/** Refreshes reload only the first list page; the open pull request may come from a later page or a link. */
export async function runSelectionStability(): Promise<string> {
  const detailFor = (number: number, title: string, updatedAt: string): PullRequestDetailDto => ({
    id: number, repo: 'owner/repo', number, title, htmlUrl: null, state: 'open', draft: false,
    mergeable: null, mergeableState: null, author: 'alice', authorAvatarUrl: null, createdAt: null, updatedAt,
    mergedAt: null, baseRef: 'main', headRef: 'feature', body: '', labels: [], assignees: [], milestone: null,
    reviewers: [], additions: 1, deletions: 1, changedFiles: 1, approvalCount: 0, ciStatus: null, recentActivity: [],
    checkRuns: [], unavailableSections: [], viewerCanWrite: false,
  });
  const newest = detailFor(3, 'Newest after sync', '2026-09-14T06:00:00Z');
  const first = detailFor(1, 'First on page zero', '2026-09-14T01:00:00Z');
  const older = detailFor(2, 'Older from page one', '2026-09-10T00:00:00Z');
  const details = [newest, first, older];
  const originalBridge = window.bridge;
  const host = document.createElement('div');
  host.style.cssText = 'width: 1400px; height: 900px; display: flex';
  document.body.append(host);
  const root = createRoot(host);
  const open = () => details.find(detail => host.querySelector('.pull-detail')?.textContent?.includes(detail.title))?.title;
  const until = async (condition: () => boolean) => {
    for (let attempt = 0; attempt < 60 && !condition(); attempt++) await settle();
  };

  const scenario = async (link: boolean) => {
    let pageZero = [first];
    const synced: (() => void)[] = [];
    const navigations: number[] = [];
    const bridge = {
      getUserProfile: async () => ({ login: 'bob', name: null, avatarUrl: '', htmlUrl: '', publicRepos: 0, followers: 0,
        following: 0, bio: null, location: null, company: null, email: null }),
      fetchPrs: async (_workspaceId, page) => ({
        pullRequests: (page === 0 ? pageZero : [older]).map(detail => remotePullRequest(detail, 'bob')),
        page, pageSize: 50, totalPullRequests: 3, openPullRequests: 3, hasMore: page === 0 && !link,
      }),
      onPullRequestsSynced: listener => { synced.push(listener); return () => {}; },
      listWorkspaces: async () => [],
      fetchAttentionPrs: async () => [],
      // The linked pull request resolves after the first row's detail, whose row update used to abandon it.
      fetchPullRequestDetailByRef: async (_repo, number) => {
        if (number === older.number) await new Promise(resolve => window.setTimeout(resolve, 100));
        return details.find(detail => detail.number === number) ?? first;
      },
      refreshPullRequestDetailByRef: async (_repo, number) =>
        ({ full: true as const, detail: details.find(detail => detail.number === number) ?? first, status: null }),
      refreshAllPullRequestDetailByRef: async (_repo, number) => details.find(detail => detail.number === number) ?? first,
      recordRecentTouch: async () => {},
    } satisfies Pick<Bridge, 'getUserProfile' | 'fetchPrs' | 'onPullRequestsSynced' | 'listWorkspaces' | 'fetchAttentionPrs'
      | 'fetchPullRequestDetailByRef' | 'refreshPullRequestDetailByRef' | 'refreshAllPullRequestDetailByRef' | 'recordRecentTouch'>;
    Object.assign(window, { bridge });
    flushSync(() => root.render(<PullsScreen key={String(link)} initialPr={link ? { repo: older.repo, number: older.number } : undefined}
      onSelectPullRequest={reference => navigations.push(reference.number)} />));
    if (link) {
      await until(() => open() === older.title);
    } else {
      await until(() => host.querySelector('.pulls-list')?.textContent?.includes(older.title) === true);
      const row = [...host.querySelectorAll<HTMLElement>('.pulls-list button, .pulls-list [role="option"]')]
        .find(element => element.textContent?.includes(older.title));
      assert(row !== undefined, 'The next list page must load the older pull request');
      row.click();
      await until(() => open() === older.title);
    }
    assert(open() === older.title, `${link ? 'A linked' : 'A scrolled-to'} pull request must open, not ${open()}`);
    navigations.length = 0;
    host.querySelector<HTMLButtonElement>('[aria-label="Refresh pull request"]')?.click();
    pageZero = [newest, first];
    synced.forEach(listener => listener());
    await until(() => host.querySelector('.pulls-list')?.textContent?.includes(newest.title) === true);
    await settle();
    assert(open() === older.title && navigations.length === 0,
      `Refresh and sync must keep ${older.title} open, not ${open()} (navigated to ${navigations.join(', ')})`);
  };

  try {
    await scenario(false);
    await scenario(true);
    return 'Refresh and sync keep a pull request opened from a later page or a link';
  }
  finally {
    flushSync(() => root.unmount());
    host.remove();
    Object.assign(window, { bridge: originalBridge });
  }
}
