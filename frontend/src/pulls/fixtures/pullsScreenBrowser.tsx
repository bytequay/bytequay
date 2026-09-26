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
import PullOverview from '../PullOverview';
import PullRequestTaskActions from '../PullRequestTaskActions';
import PullsScreen, { remotePullRequest, ReviewDialog } from '../PullsScreen';
import ReviewTaskScreen from '../../agents/ReviewTaskScreen';
import type { AgentEventEnvelope } from '../../agents/events';
import { createPreviewBridge, previewTask } from '../../agents/fixtures/previewBridge';
import type { CodeHostRef } from '../code-tests/contracts';
import type { AgentReviewDto, ReviewDraftDto } from '../agent-review/contracts';
import { reviewFixture } from '../agent-review/fixtures/review';
import '../../css/base.css';
import { assert, settle } from '../../fixtures/harness';

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

export async function runMainScreen(keepMounted = false): Promise<string> {
  let detail: PullRequestDetailDto = {
    id: 7, repo: 'owner/repo', number: 7, title: 'Implementation', htmlUrl: null, state: 'open', draft: true,
    mergeable: null, mergeableState: null, author: 'alice', authorAvatarUrl: null, createdAt: null, updatedAt: '2026-01-01T00:00:00Z',
    mergedAt: null, baseRef: 'main', headRef: 'feature', body: 'Original description', labels: [], assignees: [], milestone: null,
    reviewers: [{ login: 'bob', avatarUrl: null, state: 'PENDING' }], additions: 1, deletions: 1, changedFiles: 1,
    approvalCount: 0, ciStatus: null, recentActivity: [], checkRuns: [], unavailableSections: [], viewerCanWrite: false,
  };
  // The list opens on Review, so the row has to be one bob is asked to review.
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
    listLocalReviewComments: async () => [],
    fetchPullRequestDetailByRef: async () => detail,
    refreshPullRequestDetailByRef: async () => ({ full: true as const, detail, status: null }),
    refreshAllPullRequestDetailByRef: async () => detail,
    fetchPrDiffFiles: async () => { defaultReads++; return [file('all.txt')]; },
    fetchPrDiffCommits: async () => commits,
    refreshPrDiffFiles: async (_repo, _number, base, head) => { comparisons.push([base, head]); return [file('selected.txt')]; },
    recordRecentTouch: async () => {},
  } satisfies Pick<Bridge, 'getUserProfile' | 'fetchPrs' | 'onPullRequestsSynced' | 'listWorkspaces' | 'listLocalReviewComments' | 'fetchPullRequestDetailByRef'
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
    button('WorkBench').click(); await settle();
    button('Changes').click();
    await settle();
    const choice = host.querySelector<HTMLInputElement>('[aria-label="Select one: one"]');
    assert(choice !== null, 'Main PR page must load its commit picker');
    assert(host.querySelector<HTMLSelectElement>('[aria-label="Changes source"]')?.value === 'published',
      'PR-only browsing must default to Published PR in the unified Changes destination');
    assert(host.querySelectorAll('.pull-changes__toolbar').length === 1
      && host.querySelector('.pull-changes__toolbar')?.closest('.pull-detail__header') !== null,
      'The published diff must use one shared header toolbar');
    assert(host.querySelector('.pull-file-tree') === null, 'The side panel must start with its changed-file sidebar closed');
    const picker = host.querySelector<HTMLDetailsElement>('.pull-commit-picker');
    assert(picker !== null, 'Published Changes must retain the commit picker');
    picker.open = true; await settle();
    const menu = picker.querySelector('.pull-commit-picker__menu')?.getBoundingClientRect();
    const pane = host.querySelector('.pull-detail')?.getBoundingClientRect();
    assert(menu !== undefined && pane !== undefined && menu.width > 100 && menu.left >= pane.left && menu.right <= pane.right,
      'The portaled commit picker must stay within the detail pane');
    choice.click();
    picker.open = false;
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
    button('PR #').click();
    await settle();
    assert(host.textContent?.includes('New timeline discussion') === true, 'The overview must render the shared refreshed timeline');
    button('WorkBench').click(); await settle();
    button('Changes').click();
    await settle();
    assert(host.querySelector<HTMLInputElement>('[aria-label="Deselect one: one"]')?.checked === true,
      'Returning to Changes must preserve the selected commit');
    detail = { ...detail, additions: 2 };
    await tick();
    assert(host.querySelector('[aria-label="Select one: one"]') !== null
      && host.querySelector('[aria-label="Submit review, 1 pending review comment"]') !== null,
    'Changed code must reset its obsolete range while keeping the staged review');

    const zoom = host.querySelector<HTMLButtonElement>('[aria-label="Zoom pull request details"]');
    assert(zoom !== null, 'The list pane must offer the zoom action');
    zoom.click();
    await settle();
    assert(host.querySelector('.pull-detail.zoom-card') !== null && host.querySelector('.pull-detail.is-full') === null
      && host.querySelector('.zoom-scrim') !== null, 'Zoom must open a card over the list, not cover the window');
    assert(host.querySelector('.pull-file-tree') !== null, 'Zoom must default the changed-file sidebar open');
    // The browser rail is fixed chrome painted above every layer, so a layer that reached under
    // it would be covered by the web page list instead of the page it was opened from.
    document.body.style.setProperty('--browser-rail-width', '220px');
    const railed = (selector: string) => host.querySelector(selector)?.getBoundingClientRect().left ?? -1;
    assert(railed('.pull-detail.zoom-card') >= 220 && railed('.zoom-scrim') >= 220,
      'A zoom layer must start where the browser rail ends');
    document.body.style.removeProperty('--browser-rail-width');
    const full = host.querySelector<HTMLButtonElement>('[aria-label="Enter full screen"]');
    assert(full !== null, 'The zoom layer must offer full screen');
    full.click();
    await settle();
    assert(host.querySelector('.pull-detail.zoom-card.is-full') !== null && host.querySelector('.zoom-scrim') === null,
      'Full screen must drop the card and its backdrop');
    assert(host.querySelector('[aria-label="Exit full screen"]') !== null
      && host.querySelector('[aria-label="Close pull request details"]') !== null,
    'Full screen must keep both the way back to the card and the way out');
    if (keepMounted) {
      host.querySelector<HTMLInputElement>('[aria-label="Select one: one"]')?.click();
      await settle();
      return 'Compact Changes preview';
    }
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await settle();
    assert(host.querySelector('.pull-detail.zoom-card') === null && host.querySelector('.pulls-list') !== null,
      'Escape must leave the zoom layer for the list, even from full screen');
    return 'Main PR page shares commit selection, timeline polling, row reconciliation, range invalidation, and the zoom layer';
  }
  finally {
    if (!keepMounted) { flushSync(() => root.unmount()); host.remove(); }
    window.setTimeout = originalTimeout;
  }
}

export async function runReviewWorkspace(local = false): Promise<string> {
  const agent = createPreviewBridge('workspace-1', { replayThrough: Number.MAX_SAFE_INTEGER });
  const review = reviewFixture({ id: 'retained-review', state: 'CLOSED', closedAt: '2026-09-07T00:00:00Z', sourceTaskId: 'development-task',
    ...(local ? { number: null, sourceTaskId: 'development-task' } : {}) });
  review.task = { ...review.task, id: agent.events[0].taskId };
  const detail = reviewDetail(review);
  const reviewReads: string[] = [];
  const codeSources: CodeHostRef[] = [];
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
    findPullRequestTask: async () => ({ workspaceId: review.workspaceId, taskId: 'development-task', taskState: 'OPEN', version: 1, linked: true }),
    getAgentTask: async () => previewTask(review.workspaceId, 'development-task'),
    listCodeFiles: async host => {
      codeSources.push(host);
      return { source: 'WORKING_COPY' as const, revision: review.runs[0].headSha, codeGraph: 'OFF' as const,
        truncated: false, sourceFingerprint: null, savedExperiment: false, files: [] };
    },
    listCodeRuns: async () => [],
    readAgentReviewHistory: async (id, taskId, before, after) => {
      assert(id === review.id && taskId === review.task.id, 'Conversation history must stay scoped to the retained review and task');
      historyReads++;
      return agent.readAgentHistory(taskId, before, after);
    },
    fetchAttentionPrs: async () => [],
    fetchPrs: async () => { listReads++; throw new Error('A review workspace must not load the PR list'); },
    fetchPullRequestDetailByRef: async (repo, number) => {
      assert(!local && repo === review.repo && number === review.number, 'A local review must never fetch a fake pull request');
      return detail;
    },
    refreshPullRequestDetailByRef: async () => ({ full: true as const, detail, status: null }),
    listLocalReviewComments: async () => [],
    recordRecentTouch: async () => {},
  } satisfies Pick<Bridge, 'getUserProfile' | 'getModelSettings' | 'getAgentTurn' | 'getAgentReview' | 'findAgentReview' | 'findPullRequestTask' | 'getAgentTask' | 'listCodeFiles' | 'listCodeRuns'
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
    assert(pane?.textContent?.includes('Change intention') === true && pane.querySelector('[data-finding-id="finding-1"]') !== null,
      'The right PR pane must show independent learning and finding cards');
    assert(conversation.parentElement === pane.parentElement
      && Boolean(conversation.compareDocumentPosition(pane) & Node.DOCUMENT_POSITION_FOLLOWING),
    'Conversation and PR cards must be sibling panes in left-to-right order');
    assert(host.querySelector('.pulls-list') === null && listReads === 0 && host.querySelector('[aria-modal="true"]') === null,
      'The workspace layout must show neither a PR list nor a modal');
    assert(reviewReads.length >= (local ? 1 : 2) && reviewReads.every(id => id === review.id) && historyReads > 0,
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
    const learningTab = [...pane.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.includes('WorkBench'));
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
    const codeTab = [...pane.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.includes('Code & Tests'));
    assert(codeTab !== undefined, 'A retained reviewer must offer Code & Tests');
    codeTab.click(); await settle();
    assert(codeSources.length > 0 && codeSources.every(source => source.kind === 'review' && source.reviewId === review.id),
      'A reviewer workspace must keep its exact review worktree even when a matching development task exists');
    assert(pane.querySelector('[aria-label="Review of development changes"]') === null,
      'A reviewer workspace must not mount the development Summary or replace its conversation stream');
    conversation.querySelector<HTMLButtonElement>('[aria-label="Back to tasks"]')?.click();
    assert(back === 1, 'The review workspace back action must return to its task list');
    return local ? 'Local review workspace keeps its reviewer conversation without fetching PR details'
      : 'Retained review workspace replays its conversation beside learning cards and returns to tasks';
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
    assert(pane?.textContent?.includes('Change intention') === true, 'The open review must show its learning cards beside the conversation');
    // Leaving and returning to Summary remounts its cards while the conversation stays mounted.
    pane.querySelector<HTMLButtonElement>('.pull-detail__pr-tab')?.click();
    await settle();
    [...pane.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.includes('WorkBench'))?.click();
    await settle();
    assert(pane.textContent?.includes('Change intention') === true, 'Returning to Summary must show the cards again');
    delivered = true;
    for (const stream of streams.filter(item => item.live)) {
      stream.listener({ subscriptionId: 'review-stream', taskId: review.task.id, kind: 'changed', sequence: sent.sequence, turnId: sent.turnId });
    }
    for (let attempt = 0; attempt < 40 && !conversation().includes('Check the new head again'); attempt++) await new Promise(resolve => setTimeout(resolve, 25));
    assert(conversation().includes('Check the new head again'), 'A message delivered after the cards remount must reach the conversation');
    assert(streams.length === 1, `The review workspace must open one review stream for both panes, opened ${streams.length}`);
    return 'Open review workspace keeps its conversation live after Summary remounts';
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
      commitId: reviewFixture().findings[0].validatedHeadSha,
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
  let publicationProblem: string | null = null;
  let diffHead = review.observedHeadSha;
  const addedCommits: Array<string | null> = [];
  const bridge = {
    saveReviewDraft: async (_repo, _number, version, body, event) => {
      calls.push(`save v${version} ${event} "${body}"`);
      return { ...draft, body, event, version: version + 1 };
    },
    addReviewDraftComment: async (_repo, _number, version, comment) => {
      calls.push(`add v${version} ${comment.path}:${comment.line} ${comment.body}`);
      addedCommits.push(comment.commitId);
      return { ...draft, version: version + 1 };
    },
    submitReviewDraft: async (_repo, _number, version, body, event) => {
      calls.push(`draft v${version} ${event} "${body}"`);
      if (publicationProblem !== null) throw new Error(`Error invoking remote method 'review:draft:submit': Error: ${publicationProblem}`);
    },
    submitReview: async (_prId, _repo, _number, event, body, comments) => {
      calls.push(`plain ${event} "${body}" ${(comments ?? []).map(comment => comment.path).join(',')}`);
      return detail;
    },
    getAgentReview: async () => { calls.push('reload'); return review; },
    fetchPullRequestDetailByRef: async () => { calls.push('detail'); return detail; },
    fetchPrDiffFiles: async () => [
      { filename: 'src/Finding.java', status: 'modified', additions: 1, deletions: 0, patch: '@@ -1,2 +10,3 @@\n a\n b\n+int findingLine = 12;' },
      { filename: 'src/Written.java', status: 'modified', additions: 1, deletions: 0, patch: '@@ -1,2 +1,3 @@\n a\n b\n+int writtenLine = 3;' },
    ],
    fetchPrDiffCommits: async () => [{ sha: diffHead ?? 'head', parentSha: null, message: null, author: null, authoredAt: null }],
    getUserProfile: async () => ({ login: 'bob', name: null, avatarUrl: '', htmlUrl: '', publicRepos: 0, followers: 0, following: 0,
      bio: null, location: null, company: null, email: null }),
  } satisfies Pick<Bridge, 'saveReviewDraft' | 'addReviewDraftComment' | 'submitReviewDraft' | 'submitReview' | 'getAgentReview' | 'fetchPullRequestDetailByRef'
    | 'fetchPrDiffFiles' | 'fetchPrDiffCommits' | 'getUserProfile'>;
  const originalBridge = window.bridge;
  Object.assign(window, { bridge });
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  // A submitted sheet stays mounted with its submit in flight, so each scenario needs a fresh one.
  let mounted = 0;
  const mount = (comments: InlineReviewCommentDto[], carries: AgentReviewDto | null = review) => flushSync(() => root.render(
    <ReviewDialog key={++mounted} pr={remotePullRequest(detail, 'bob')}
    detail={detail} review={carries} onReviewChange={() => events.push('review')} pendingComments={comments}
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
    assert(closed && addedCommits.join(',') === 'older-commit' && !calls.some(call => call.startsWith('plain ')),
      `Selected-commit comments join the reserved draft with their original commit beside agent findings: ${calls.join(' | ')}`);

    calls.length = 0;
    addedCommits.length = 0;
    closed = false;
    mount([written]);
    host.querySelector<HTMLInputElement>('input[type="radio"]')?.click();
    await settle();
    await submit();
    assert(closed && calls.join(' | ') === 'save v4 COMMENT "" | add v5 src/Written.java:3 Written comment text | draft v6 COMMENT "" | reload | detail',
      `Added findings must go out through the draft with the written comments, not the plain review: ${calls.join(' | ')}`);
    assert(events.includes('pending 0') && events.includes('submitted COMMENT') && events.includes('review'),
      `Submitting must clear written comments and refresh the review and pull request: ${events.join(' | ')}`);
    assert(addedCommits[0] === written.commitId, 'Written comments keep their displayed commit when joining the durable draft');

    calls.length = 0;
    closed = false;
    diffHead = 'newer-commit';
    mount([]);
    await settle();
    const olderFinding = host.querySelector('.pr-review-sheet__pending > .pr-inline-thread');
    assert(olderFinding?.querySelector('.pr-commented-code-line') === null
      && olderFinding?.textContent?.includes(`commit ${draft.items[0].commitId?.slice(0, 7)}`) === true,
    'An older finding shows its saved commit and never previews current code at the old line number');
    await submit();
    assert(closed && calls.some(call => call.startsWith('draft ')), 'An advanced head does not block the pinned finding draft');

    calls.length = 0;
    events.length = 0;
    closed = false;
    publicationProblem = 'Comment publication is uncertain; check GitHub before retrying';
    mount([], { ...review, draft: { ...draft, body: 'Keep my review summary.' } });
    await submit();
    assert(!closed && host.querySelector('[role="alert"]')?.textContent === publicationProblem
      && host.querySelector<HTMLTextAreaElement>('textarea')?.value === 'Keep my review summary.'
      && !host.querySelector<HTMLButtonElement>('.pr-review-sheet__footer .is-primary')?.disabled
      && host.querySelector('.pr-review-sheet__pending')?.textContent?.includes('Promoted finding text') === true,
    'A publication failure keeps the summary and pending comments editable and shows the useful error without the Electron wrapper');
    assert(!events.some(event => event.startsWith('submitted ')), 'A refused review must not be reported as submitted');
    publicationProblem = null;
    diffHead = review.observedHeadSha;

    // No added findings, so the comments go out as plain reviews — one per commit they were
    // written on, because GitHub anchors a whole review at a single commit.
    calls.length = 0;
    events.length = 0;
    mount([written, { ...written, path: 'src/Older.java', commitId: 'older-commit' }], null);
    await settle();
    await settle();
    await submit();
    assert(calls.join(' | ') === 'plain COMMENT "" src/Older.java | plain APPROVE "" src/Written.java',
      `Comments written on different commits must go out as one review each, the verdict last: ${calls.join(' | ')}`);
    assert(events.join(' | ').startsWith('pending 1 | pending 0'),
      `Each group must leave the staged list as it lands, so a retry cannot send it twice: ${events.join(' | ')}`);
    return 'Submit review sends added findings through the draft and splits written comments by commit';
  }
  finally {
    flushSync(() => root.unmount());
    host.remove();
    Object.assign(window, { bridge: originalBridge });
  }
}

/**
 * Searching narrows the open filter's already-loaded rows. It used to be sent to the backend as
 * `?search=`, which emptied the list on every keystroke and left `#number` and `@author` terms
 * matching nothing.
 */
export async function runListSearch(): Promise<string> {
  const detailFor = (number: number, title: string, author: string, state: string): PullRequestDetailDto => ({
    id: number, repo: 'owner/repo', number, title, htmlUrl: null, state, draft: false,
    mergeable: null, mergeableState: null, author, authorAvatarUrl: null, createdAt: null,
    updatedAt: `2026-09-1${number % 9}T00:00:00Z`,
    mergedAt: null, baseRef: 'main', headRef: 'feature', body: '', labels: [], assignees: [], milestone: null,
    reviewers: [{ login: 'bob', avatarUrl: null, state: 'PENDING' }], additions: 1, deletions: 1, changedFiles: 1,
    approvalCount: 0, ciStatus: null, recentActivity: [], checkRuns: [], unavailableSections: [], viewerCanWrite: false,
  });
  // 30682 is bob's to review, 31206 is his own, and 31215 is closed: three tabs, one loaded page.
  const page = [
    detailFor(30682, 'Requested of bob', 'voonhous', 'open'),
    detailFor(31206, 'Authored by bob', 'bob', 'open'),
    detailFor(31215, 'Closed work', 'lukasz', 'closed'),
  ];
  let listReads = 0;
  const originalBridge = window.bridge;
  const bridge = {
    getUserProfile: async () => ({ login: 'bob', name: null, avatarUrl: '', htmlUrl: '', publicRepos: 0, followers: 0,
      following: 0, bio: null, location: null, company: null, email: null }),
    fetchPrs: async () => {
      listReads++;
      return { pullRequests: page.map(detail => remotePullRequest(detail, 'bob')),
        page: 0, pageSize: 50, totalPullRequests: page.length, openPullRequests: 2, hasMore: false };
    },
    onPullRequestsSynced: () => () => {},
    listWorkspaces: async () => [],
    fetchAttentionPrs: async () => [],
    fetchPullRequestDetailByRef: async (_repo, number) => page.find(detail => detail.number === number) ?? page[0],
    refreshPullRequestDetailByRef: async (_repo, number) =>
      ({ full: true as const, detail: page.find(detail => detail.number === number) ?? page[0], status: null }),
    refreshAllPullRequestDetailByRef: async (_repo, number) => page.find(detail => detail.number === number) ?? page[0],
    recordRecentTouch: async () => {},
  } satisfies Pick<Bridge, 'getUserProfile' | 'fetchPrs' | 'onPullRequestsSynced' | 'listWorkspaces' | 'fetchAttentionPrs'
    | 'fetchPullRequestDetailByRef' | 'refreshPullRequestDetailByRef' | 'refreshAllPullRequestDetailByRef' | 'recordRecentTouch'>;
  Object.assign(window, { bridge });
  const host = document.createElement('div');
  host.style.cssText = 'width: 1400px; height: 900px; display: flex';
  document.body.append(host);
  const root = createRoot(host);
  // The title span carries the number too; the assertions read the title alone.
  const titles = () => [...host.querySelectorAll('.pulls-list .pull-row__title')]
    .map(row => (row.textContent ?? '').replace(/\s*#\d+$/, '').trim());
  const until = async (condition: () => boolean) => {
    for (let attempt = 0; attempt < 120 && !condition(); attempt++) await settle();
  };
  /** The strip shows as many filters as the measured pane fits; the rest live in the ⋯ menu. */
  const pickTab = async (label: string) => {
    const tab = [...host.querySelectorAll<HTMLButtonElement>('.pulls-tab')].find(entry => entry.textContent === label);
    if (tab === undefined) {
      host.querySelector<HTMLButtonElement>('.pulls-tab-more')?.click();
      await settle();
    }
    const target = tab ?? [...host.querySelectorAll<HTMLButtonElement>('.pulls-filter-menu__item')]
      .find(entry => entry.textContent?.includes(label));
    assert(target !== undefined, `The filters must offer ${label}`);
    target.click();
    await settle();
  };
  const type = async (value: string) => {
    const input = host.querySelector<HTMLInputElement>('.pulls-search input');
    assert(input !== null, 'The list must offer a search box');
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
    // The box debounces for 250ms before the filter runs.
    await new Promise(resolve => window.setTimeout(resolve, 320));
    await settle();
  };
  try {
    flushSync(() => root.render(<PullsScreen />));
    await until(() => titles().length > 0);
    assert(titles().join('|') === 'Requested of bob', `Review must open on the requested pull request, not ${titles().join('|')}`);
    const reads = listReads;

    await type('#30');
    assert(titles().join('|') === 'Requested of bob', `A #number prefix must match 30682, not ${titles().join('|')}`);
    await type('@voon');
    assert(titles().join('|') === 'Requested of bob', `An @author prefix must match voonhous, not ${titles().join('|')}`);
    await type('requested');
    assert(titles().join('|') === 'Requested of bob', `A title term must still match, and found ${titles().join('|')}`);
    // The search narrows the open filter; bob's own #31206 is My work, not Review.
    await type('#312');
    assert(titles().length === 0, `A search must stay inside the open filter, but found ${titles().join('|')}`);

    await pickTab('My work');
    await type('#312');
    assert(titles().join('|') === 'Authored by bob', `My work must search its own rows, and found ${titles().join('|')}`);
    assert(listReads === reads, `Searching must not reload the list, but it read ${listReads - reads} more pages`);

    await type('');
    await until(() => titles().length === 1);
    assert(titles().join('|') === 'Authored by bob', 'Clearing the search must restore the selected filter');
    return 'Search filters the open filter\'s loaded rows by number, author, and title without reloading the list';
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
    // The list opens on Review, so every row has to be one bob is asked to review.
    reviewers: [{ login: 'bob', avatarUrl: null, state: 'PENDING' }], additions: 1, deletions: 1, changedFiles: 1,
    approvalCount: 0, ciStatus: null, recentActivity: [], checkRuns: [], unavailableSections: [], viewerCanWrite: false,
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
      // The link resolves after page zero renders; its wait must never paint another pull request.
      let intruder: string | undefined;
      await until(() => {
        const shown = open();
        if (shown !== undefined && shown !== older.title) intruder = shown;
        return shown === older.title;
      });
      assert(intruder === undefined, `A linked pull request must open alone, but ${intruder} showed first`);
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

export async function runCheckLogs(): Promise<string> {
  const detail = reviewDetail(reviewFixture());
  detail.ciStatus = 'FAILING';
  detail.checkRuns = [{ githubId: 999, name: 'Unit tests', status: 'completed', conclusion: 'failure',
    detailsUrl: 'https://github.com/owner/repo/actions/runs/101/job/301', source: 'check_run',
    startedAt: null, completedAt: null, category: 'failing' }];
  const requests: ReturnType<typeof Promise.withResolvers<{ text: string }>>[] = [];
  const opened: string[] = [];
  Object.assign(window, { bridge: {
    getUserProfile: async () => ({ login: 'bob' }),
    openExternal: async (url: string) => { opened.push(url); },
    getPrCheckLog: (repo: string, number: number, jobId: number) => {
      assert(repo === detail.repo && number === detail.number && jobId === 301, 'Log requests must use the PR and Actions job ID, not the check-run ID');
      const request = Promise.withResolvers<{ text: string }>();
      requests.push(request);
      return request.promise;
    },
  } });
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const render = () => flushSync(() => root.render(<PullOverview pr={remotePullRequest(detail, 'bob')} detail={{ ...detail }} onRefresh={async () => {}} />));
  const button = (text: string) => {
    const found = [...host.querySelectorAll<HTMLButtonElement>('.pr-check button')].find(entry => entry.getAttribute('aria-label') === text);
    assert(found !== undefined, `Missing ${text} button`);
    return found;
  };
  const click = (text: string) => flushSync(() => button(text).click());
  try {
    render();
    await settle();
    assert(requests.length === 0, 'Displaying failed checks must not download logs');
    click('Open in GitHub');
    assert(opened[0] === detail.checkRuns[0].detailsUrl, 'The browser action must open the selected check URL');
    click('Show log');
    assert(button('Loading…').disabled && host.querySelector('[role="status"]')?.textContent === 'Loading log…', 'Loading must be visible and prevent duplicate requests');
    const log = '<script>untrusted log</script>\r\n[ERROR] Assertion failed\r\n'
      + 'Caused by: java.io.IOException: connection closed\r\n    at Worker.call(Worker.java:42)\r\n'
      + '    Suppressed: TimeoutException: timed out\r\n[error] cannot find symbol\r\n'
      + 'FATAL FAILURE panic traceback mismatch segmentation fault\r\n[INFO] errorCount=0 failedChecks=0\r\n';
    requests[0].resolve({ text: log });
    await settle();
    assert(host.querySelector('.pr-check__log pre')?.textContent === log
      && host.querySelector('.pr-check__log script') === null, 'Highlighting must preserve every log character and keep HTML-looking content as text');
    const highlighted = [...host.querySelectorAll('.pr-check__log mark')].map(mark => mark.textContent);
    assert(highlighted.join('|') === 'ERROR|Assertion|failed|Caused by:|IOException|Suppressed:|TimeoutException|timed out|error|cannot find symbol|FATAL|FAILURE|panic|traceback|mismatch|segmentation fault',
      'Diagnostic keywords and exception names must be highlighted case-insensitively without highlighting unrelated words or stack frames');
    click('Hide log');
    assert(host.querySelector('.pr-check__log') === null, 'Hide must collapse the excerpt');
    click('Show log');
    assert(Number(requests.length) === 1 && host.querySelector('.pr-check__log pre') !== null, 'Reopening must reuse the loaded log');
    detail.number++;
    render();
    assert(host.querySelector('.pr-check__log') === null, 'Switching PR must clear the previous excerpt');
    click('Show log');
    requests[1].reject(new Error('Log expired'));
    await settle();
    assert(host.querySelector('.pr-check__log [role="alert"]')?.textContent === 'Log expired', 'Failed retrieval must show its reason');
    click('Retry log');
    requests[2].resolve({ text: '' });
    await settle();
    assert(host.querySelector('.pr-check__log pre')?.textContent === 'This job returned an empty log.', 'Retry must handle an empty log');
    detail.checkRuns = [{ ...detail.checkRuns[0], completedAt: '2026-01-01T00:00:00Z' }];
    render();
    click('Show log');
    detail.checkRuns = [{ ...detail.checkRuns[0], githubId: 1000, detailsUrl: 'https://github.com/owner/repo/actions/runs/101/job/302' }];
    render();
    requests[3].resolve({ text: 'Late response for the previous attempt' });
    await settle();
    assert(host.querySelector('.pr-check__log') === null, 'A previous attempt must not populate the replacement row');
    return 'Open failed CI in the browser, load and retry inline logs, and discard stale attempts';
  }
  finally {
    flushSync(() => root.unmount());
    host.remove();
  }
}

export async function runAssignedPrActions(): Promise<string> {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  let assigned = 0;
  let reviewed = 0;
  let route = '';
  let resolveAssignment: ((value: { workspaceId: string; taskId: string }) => void) | undefined;
  const review = reviewFixture();
  const original = window.bridge;
  window.bridge = { ...original,
    findPullRequestTask: async () => null,
    assignPullRequestAgent: async () => { assigned++; return new Promise(resolve => { resolveAssignment = resolve; }); },
    startAgentReview: async () => { reviewed++; return review; },
  };
  const render = (authored: boolean, workspaceId?: string, existing: AgentReviewDto | null = null, key = 'pr') => {
    flushSync(() => root.render(<PullRequestTaskActions key={key} repo="owner/repo" number={7} authored={authored}
      workspaceId={workspaceId} loading={false} contextError={null} review={existing} onRetryContext={() => {}}
      onNavigate={value => { route = value; }} />));
  };
  const button = (name: string): HTMLButtonElement => {
    const element = host.querySelector<HTMLButtonElement>(`button[aria-label="${name}"]`);
    assert(element !== null, `Missing ${name}`);
    return element;
  };
  try {
    render(true);
    assert(button('Assign agent').disabled && button('View in task').disabled, 'Both actions require a workspace');
    render(true, 'workspace');
    await settle();
    button('Assign agent').click();
    button('Assign agent').click();
    await settle();
    assert(assigned === 1 && reviewed === 0, 'Own PR assignment must use one development task request, never a review');
    assert(button('View in task').disabled, 'Do not start duplicate tasks while assigning');
    resolveAssignment?.({ workspaceId: 'workspace', taskId: 'assigned-task' });
    await settle();
    assert(route === '#/workspaces/workspace/tasks/agent/assigned-task', `Open the assigned conversation with its PR: ${route}`);
    render(false, 'workspace', review, 'other');
    await settle();
    assert(host.querySelector('[aria-label="Assign agent"]') === null, 'Other authors retain review ownership');
    button('View in task').click();
    await settle();
    assert(route.endsWith(`/tasks/review/${review.id}`) && reviewed === 0, 'Open an existing review without restarting it');
    render(false, 'workspace', null, 'new-review');
    button('View in task').click();
    await settle();
    assert(Number(reviewed) === 1, 'An unassigned review opens via the existing review service');
    render(true, 'workspace', null, 'stale');
    button('Assign agent').click();
    await settle();
    route = '';
    render(true, 'workspace', null, 'replacement');
    resolveAssignment?.({ workspaceId: 'workspace', taskId: 'old-selection' });
    await settle();
    assert(route === '', 'A late assignment cannot navigate away from a different PR');
    return 'Assign authored PRs and open existing task conversations';
  }
  finally {
    flushSync(() => root.unmount());
    host.remove();
    window.bridge = original;
  }
}
