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
import type { AgentStreamMessage, AgentTaskDto, Bridge, DiffCommitDto, DiffFileDto, InlineReviewCommentDto, PullRequestDetailDto, PullRequestDto, PullTimelinePayloadDto, WorkspaceDto } from '../../types';
import { SUMMARY_REQUEST, type TaskDiscussionDto } from '../../agents/discussions/contracts';
import PullOverview from '../PullOverview';
import PullRequestTaskActions from '../PullRequestTaskActions';
import PullsScreen, { MergeDialog, remotePullRequest, ReviewDialog } from '../PullsScreen';
import ReviewTaskScreen from '../../agents/ReviewTaskScreen';
import type { AgentEventEnvelope } from '../../agents/events';
import { createPreviewBridge, previewTask } from '../../agents/fixtures/previewBridge';
import type { CodeHostRef } from '../code-tests/contracts';
import type { AgentReviewDto, ReviewDraftDto } from '../agent-review/contracts';
import { reviewFixture } from '../agent-review/fixtures/review';
import '../../css/base.css';
import { assert, changeInput, settle } from '../../fixtures/harness';
import { loadHandled } from '../../home/attention';
import { setCached } from '../../dataCache';

const payload: PullTimelinePayloadDto = {
  actorAvatarUrl: null, actorBot: false, state: null, htmlUrl: null, label: null, labelColor: null,
  renameFrom: null, renameTo: null, commitSha: null, commitMessage: null, sourceTitle: null, sourceUrl: null,
  sourceNumber: null, milestoneTitle: null, requestedReviewer: null, assignee: null, path: null, diffHunk: null, line: null,
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

export async function runMainScreen(keepMounted = false, explore = false): Promise<string> {
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
    if (delay === 60_000 && typeof handler === 'function') {
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
    findAgentReview: async () => null,
    findPullRequestTask: async () => null,
    listAgentReviews: async () => [],
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
    | 'refreshPrDiffFiles' | 'recordRecentTouch' | 'findAgentReview' | 'findPullRequestTask' | 'listAgentReviews'>;
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
    flushSync(() => root.render(<PullsScreen repositoryScope={explore ? { workspaceId: 'workspace-1', repository: 'owner/repo' } : undefined} />));
    for (let attempts = 0; attempts < 45 && !host.textContent?.includes('Original description'); attempts++) await settle();
    assert(host.textContent?.includes('Original description') === true && host.querySelector('.attention-visit-status') === null
      && loadHandled()['owner/repo#7'] === undefined,
    'A loaded PR must omit the visit label while its attention notification remains visible');
    button('WorkBench').click(); await settle();
    if (explore) {
      assert(!button('Code & Tests').disabled, 'Watched repositories must offer exploration before any task or review exists');
      button('Code & Tests').click(); await settle();
      assert(button('Create worktree to explore') !== undefined && host.textContent?.includes('additional memory'),
        'The Code & Tests tab must offer explicit worktree creation and disclose memory use');
      return 'Watched PRs offer exploration before a review exists';
    }
    assert(button('Code & Tests').disabled, 'Unwatched repositories must not offer local code exploration');
    const firstView = host.querySelector('[aria-label="WorkBench views"] button');
    assert(firstView?.textContent?.trim() === 'Changes' && firstView.getAttribute('aria-pressed') === 'true',
      'Opening WorkBench must select its leftmost Changes tab');
    const choice = host.querySelector<HTMLInputElement>('[aria-label="Select one: one"]');
    assert(choice !== null, 'Main PR page must load its commit picker');
    assert(host.querySelector('[aria-label="Changes source"]') === null,
      'Published Changes must not offer a working-copy source selector');
    const tabs = host.querySelector('[aria-label="WorkBench views"]')?.getBoundingClientRect();
    const filesToggle = host.querySelector('.pull-changes__files-toggle')?.getBoundingClientRect();
    const collapse = host.querySelector('.pull-changes__bulk-toggle')?.getBoundingClientRect();
    assert(tabs !== undefined && filesToggle !== undefined && collapse !== undefined
      && filesToggle.top >= tabs.bottom && collapse.top >= tabs.bottom && filesToggle.left < collapse.left,
      'Changed files and Collapse all must start the separate row beneath WorkBench tabs');
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
    assert(host.querySelector('.pull-detail.zoom-card .attention-visit-status') === null, 'The zoom header must omit the visit label');
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
    assert(host.querySelector('.pull-detail') !== null && loadHandled()['owner/repo#7']?.label === 'Viewed',
      'Leaving zoom acknowledges the visit even when the PR remains in the regular detail pane');
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
      return { source: 'WORKING_COPY' as const, revision: review.runs[0].headSha,
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
    fetchPrDiffFiles: async () => [file('reviewed.txt')],
    fetchPrDiffCommits: async () => commits,
    readWorktreeChanges: async (_host, request) => ({ scope: request.scope, selectedCommit: null, sourceFingerprint: 'review',
      baseSha: 'base', headSha: 'head', baseLabel: 'main', branch: 'feature', truncated: false,
      commits: [], staged: [], unstaged: [], untracked: [], files: [file('reviewed.txt')] }),
    listLocalReviewComments: async () => [],
    recordRecentTouch: async () => {},
  } satisfies Pick<Bridge, 'getUserProfile' | 'getModelSettings' | 'getAgentTurn' | 'getAgentReview' | 'findAgentReview' | 'findPullRequestTask' | 'getAgentTask' | 'listCodeFiles' | 'listCodeRuns'
    | 'readAgentReviewHistory' | 'fetchAttentionPrs' | 'fetchPrs' | 'fetchPullRequestDetailByRef'
    | 'refreshPullRequestDetailByRef' | 'fetchPrDiffFiles' | 'fetchPrDiffCommits' | 'readWorktreeChanges' | 'listLocalReviewComments' | 'recordRecentTouch'>;
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
    const firstView = pane?.querySelector('[aria-label="WorkBench views"] button');
    assert(firstView?.textContent?.trim() === 'Changes' && firstView.getAttribute('aria-pressed') === 'true'
      && pane?.querySelector('.pull-diff-file') !== null && pane?.querySelector('.pull-file-tree') === null,
    'Review WorkBench must start on leftmost Changes with its file tree closed');
    [...pane.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.trim() === 'Summary')?.click(); await settle();
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
    const files = pane.querySelector<HTMLElement>('[aria-label="Files"]');
    const fileList = files?.querySelector<HTMLElement>('.ct-files__list');
    assert(files !== null && fileList !== undefined && fileList !== null
      && fileList.getBoundingClientRect().top - files.getBoundingClientRect().top < 50,
    'Ended reviews must keep file filters compact and start the file list at the top of Code & Tests');
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
    fetchPrDiffFiles: async () => [file('reviewed.txt')],
    fetchPrDiffCommits: async () => commits,
    listLocalReviewComments: async () => [],
    recordRecentTouch: async () => {},
  } satisfies Pick<Bridge, 'getUserProfile' | 'getModelSettings' | 'getAgentTurn' | 'getAgentReview' | 'readAgentReviewHistory'
    | 'subscribeAgentReview' | 'fetchAttentionPrs' | 'fetchPullRequestDetailByRef' | 'refreshPullRequestDetailByRef'
    | 'fetchPrDiffFiles' | 'fetchPrDiffCommits' | 'listLocalReviewComments' | 'recordRecentTouch'>;
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
    [...pane?.querySelectorAll<HTMLButtonElement>('button') ?? []].find(button => button.textContent?.trim() === 'Summary')?.click(); await settle();
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

export async function runActionSheetDismissal(): Promise<string> {
  const detail = { ...reviewDetail(reviewFixture()), draft: false, viewerCanWrite: true };
  const pr = remotePullRequest(detail, 'bob');
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const originalBridge = window.bridge;
  let closes = 0;
  let finishMerge = () => {};
  const merging = new Promise<void>(resolve => { finishMerge = resolve; });
  Object.assign(window, { bridge: { ...originalBridge, mergePr: () => merging } });
  const close = () => { closes++; };
  const escape = (handled = false) => {
    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    if (handled) event.preventDefault();
    document.dispatchEvent(event);
    assert(event.defaultPrevented, 'A sheet must consume Escape before the underlying screen sees it');
  };
  try {
    for (const kind of ['review', 'merge'] as const) {
      closes = 0;
      flushSync(() => root.render(kind === 'review'
        ? <ReviewDialog pr={pr} detail={detail} review={null} onClose={close} onReviewSubmitted={() => {}} />
        : <MergeDialog pr={pr} detail={detail} mergeQueue={false} onClose={close} onRefresh={async () => {}} />));
      await settle();
      assert(document.activeElement === host.querySelector(kind === 'review' ? 'textarea' : '[aria-label="Close merge dialog"]'),
        'Each sheet must retain its initial focus target');
      escape(true);
      host.querySelector('[role="dialog"]')?.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
      assert(closes === 0, 'Handled Escape and presses inside the sheet must not dismiss it');
      escape();
      assert(Number(closes) === 1, 'Unhandled Escape must dismiss the sheet once');
      host.querySelector('.pr-sheet-overlay')?.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
      assert(Number(closes) === 2, 'A backdrop press must dismiss the sheet');
    }
    host.querySelector<HTMLButtonElement>('.pr-merge-sheet__footer .is-primary:not(:disabled)')?.click();
    await settle();
    const before = closes;
    escape();
    host.querySelector('.pr-sheet-overlay')?.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    assert(closes === before && host.querySelector<HTMLButtonElement>('[aria-label="Close merge dialog"]')?.disabled,
      'A pending merge must block keyboard, backdrop, and close-button dismissal');
    finishMerge();
    await settle();
    assert(closes === before + 1, 'A completed merge must close the sheet');
    return 'Shared review and merge sheets preserve dismissal and busy behavior';
  }
  finally {
    finishMerge();
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
    for (let attempt = 0; attempt < 10 && !closed && !host.querySelector('[role="alert"]'); attempt++) await settle();
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
 * Ordinary searches narrow loaded rows. Exact #numbers cross tabs and look up missing rows
 * without reloading the list or letting a late response change a newer search.
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
  const lookups: { repo: string; number: number; result: ReturnType<typeof Promise.withResolvers<PullRequestDto>> }[] = [];
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
    lookupPullRequest: (repo, number) => {
      const result = Promise.withResolvers<PullRequestDto>();
      lookups.push({ repo, number, result });
      return result.promise;
    },
    listWorkspaces: async () => [],
    fetchAttentionPrs: async () => [],
    fetchPullRequestDetailByRef: async (_repo, number) => page.find(detail => detail.number === number) ?? page[0],
    refreshPullRequestDetailByRef: async (_repo, number) =>
      ({ full: true as const, detail: page.find(detail => detail.number === number) ?? page[0], status: null }),
    refreshAllPullRequestDetailByRef: async (_repo, number) => page.find(detail => detail.number === number) ?? page[0],
    recordRecentTouch: async () => {},
  } satisfies Pick<Bridge, 'getUserProfile' | 'fetchPrs' | 'onPullRequestsSynced' | 'listWorkspaces' | 'fetchAttentionPrs'
    | 'lookupPullRequest' | 'fetchPullRequestDetailByRef' | 'refreshPullRequestDetailByRef' | 'refreshAllPullRequestDetailByRef' | 'recordRecentTouch'>;
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
    const render = () => flushSync(() => root.render(<PullsScreen
      repositoryScope={{ workspaceId: 'workspace', repository: 'owner/repo' }} />));
    render();
    await until(() => titles().length > 0);
    assert(titles().join('|') === 'Requested of bob', `Review must open on the requested pull request, not ${titles().join('|')}`);
    const reads = listReads;

    await type('30');
    assert(titles().join('|') === 'Requested of bob', `A plain number prefix must match 30682, not ${titles().join('|')}`);
    await type('@voon');
    assert(titles().join('|') === 'Requested of bob', `An @author prefix must match voonhous, not ${titles().join('|')}`);
    await type('requested');
    assert(titles().join('|') === 'Requested of bob', `A title term must still match, and found ${titles().join('|')}`);
    // The search narrows the open filter; bob's own #31206 is My work, not Review.
    await type('312');
    assert(titles().length === 0, `A search must stay inside the open filter, but found ${titles().join('|')}`);

    await pickTab('My work');
    await type('312');
    assert(titles().join('|') === 'Authored by bob', `My work must search its own rows, and found ${titles().join('|')}`);
    await type('#30682');
    assert(titles().join('|') === 'Requested of bob', 'An exact number must cross the selected tab');
    await type('#31215');
    assert(titles().join('|') === 'Closed work', 'An exact number must include closed PRs');
    await type('@bob #28755');
    assert(lookups.length === 0, 'Loaded numbers and ordinary searches must not request a lookup');

    await type('#28755');
    assert(Number(lookups.length) === 1 && lookups[0].repo === 'owner/repo' && lookups[0].number === 28755,
      'A missing exact number must look up only the scoped repository');
    assert(host.querySelector('[role="status"]')?.textContent === 'Looking up #28755…', 'Lookup progress must replace the empty state');
    lookups[0].result.resolve(remotePullRequest(detailFor(28755, 'Older merged work', 'alice', 'merged'), 'bob'));
    await until(() => titles().includes('Older merged work'));
    assert(titles().join('|') === 'Older merged work', 'The fetched result must appear even when merged');
    await type('#2875');
    assert(Number(lookups.length) === 2 && titles().length === 0, 'A longer loaded number must not suppress an exact lookup');
    lookups[1].result.reject(new Error('Pull request not found'));
    await until(() => host.querySelector('.pulls-sync-error') !== null);
    assert(host.querySelector('.pulls-sync-error')?.textContent?.includes('Pull request not found') === true, 'Lookup failures must be visible');

    await type('#28756');
    assert(Number(lookups.length) === 3, 'A new exact number must start a new lookup');
    await type('');
    lookups[2].result.resolve(remotePullRequest(detailFor(28756, 'Abandoned search', 'bob', 'open'), 'bob'));
    await settle();
    assert(!titles().includes('Abandoned search'), 'A late lookup must not populate the list after clearing the search');
    assert(listReads === reads, `Searching must not reload the list, but it read ${listReads - reads} more pages`);

    await type('');
    await until(() => titles().length === 1);
    assert(titles().join('|') === 'Authored by bob', 'Clearing the search must restore the selected filter');
    const broadcast = () => window.dispatchEvent(new CustomEvent('bytequay:pr-attention-updated', {
      detail: page.map(detail => remotePullRequest(detail, 'bob')),
    }));
    broadcast(); await settle();
    page.push(detailFor(32000, 'Newly discovered', 'bob', 'open'));
    broadcast(); await until(() => titles().includes('Newly discovered'));
    assert(titles().includes('Newly discovered'), 'A newly discovered PR must enter the current list without reopening it');
    const afterDiscovery = listReads;
    for (let tick = 0; tick < 3; tick++) {
      render();
      broadcast(); await settle();
      assert(listReads === afterDiscovery, 'Unchanged attention snapshots must not reload after a parent renders the same workspace');
    }
    return 'Search resolves exact PR numbers, filters local text, and shows newly discovered PRs';
  }
  finally {
    flushSync(() => root.unmount());
    host.remove();
    Object.assign(window, { bridge: originalBridge });
  }
}

/** Refreshes must retain loaded rows as well as a selected pull request opened from a page or link. */
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
  const sibling = detailFor(4, 'Unselected from page one', '2026-09-09T00:00:00Z');
  const details = [newest, first, older, sibling];
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
        pullRequests: (page === 0 ? pageZero : [older, sibling]).map(detail => remotePullRequest(detail, 'bob')),
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
    const siblingRow = [...host.querySelectorAll('.pull-card')].find(element => element.textContent?.includes(sibling.title));
    const removed: Node[] = [];
    const observer = new MutationObserver(records => records.forEach(record => removed.push(...record.removedNodes)));
    const list = host.querySelector('.pulls-list__rows');
    assert(list !== null, 'The list must remain mounted beside the detail pane');
    observer.observe(list, { childList: true });
    navigations.length = 0;
    host.querySelector<HTMLButtonElement>('[aria-label="Refresh pull request"]')?.click();
    pageZero = [newest, first];
    synced.forEach(listener => listener());
    await until(() => host.querySelector('.pulls-list')?.textContent?.includes(newest.title) === true);
    await settle();
    observer.disconnect();
    if (!link) assert(siblingRow !== undefined && siblingRow.isConnected && !removed.includes(siblingRow),
      'Sync must keep unselected rows from later pages mounted throughout the refresh');
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
  let linkedReviewReads = 0;
  let route = '';
  let resolveAssignment: ((value: { workspaceId: string; taskId: string }) => void) | undefined;
  const review = reviewFixture();
  const taskReview = reviewFixture({ id: 'development-review', sourceTaskId: 'development-task', number: null });
  const original = window.bridge;
  window.bridge = { ...original,
    findPullRequestTask: async () => null,
    findTaskAgentReview: async (workspaceId, taskId) => {
      assert(workspaceId === 'workspace' && taskId === 'development-task', 'Find the reviewer belonging to this development task');
      linkedReviewReads++;
      return taskReview;
    },
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
    assert(host.querySelectorAll('button').length === 2, 'Own PRs offer distinct development and review tasks');
    assert(button('Development task').textContent === 'Development task' && button('Review task').textContent === 'Review task', 'Both task buttons have visible labels');
    assert(button('Development task').disabled && button('Review task').disabled, 'Opening either task requires a workspace');
    render(true, 'workspace');
    await settle();
    button('Development task').click();
    button('Review task').click();
    button('Development task').click();
    await settle();
    assert(assigned === 1 && reviewed === 0, 'Own PR assignment must use one development task request, never a review');
    assert(button('Development task').disabled && button('Review task').disabled, 'Do not start duplicate tasks while assigning');
    resolveAssignment?.({ workspaceId: 'workspace', taskId: 'assigned-task' });
    await settle();
    assert(route === '#/workspaces/workspace/tasks/agent/assigned-task', `Open the assigned conversation with its PR: ${route}`);
    window.bridge.findPullRequestTask = async () => ({ workspaceId: 'workspace', taskId: 'completed-task' });
    render(true, 'workspace', null, 'completed');
    await settle();
    button('Development task').click();
    await settle();
    assert(String(route) === '#/workspaces/workspace/tasks/agent/completed-task' && assigned === 1, 'Open an existing completed development conversation without creating another');
    window.bridge.findPullRequestTask = async () => ({ workspaceId: 'workspace', taskId: 'development-task' });
    render(true, 'workspace', review, 'both');
    button('Review task').click();
    await settle();
    assert(route.endsWith(`/tasks/review/${taskReview.id}`) && linkedReviewReads === 1 && reviewed === 0,
      'Open the development task reviewer even when it predates publication and another PR review exists');
    button('Development task').click();
    await settle();
    assert(String(route) === '#/workspaces/workspace/tasks/agent/development-task', 'The two buttons open different agents for the same PR');
    window.bridge.findTaskAgentReview = async () => null;
    button('Review task').click();
    await settle();
    assert(route.endsWith(`/tasks/review/${review.id}`) && reviewed === 0, 'Fall back to the existing PR review when the developer has no reviewer');
    window.bridge.findPullRequestTask = async () => null;
    render(true, 'workspace', null, 'own-new-review');
    button('Review task').click();
    await settle();
    assert(Number(reviewed) === 1 && assigned === 1, 'Starting a review on an own PR must not assign a developer');
    render(false, 'workspace', review, 'other');
    await settle();
    assert(host.querySelector('[aria-label="Development task"]') === null, 'Other authors do not offer development assignment');
    button('Review task').click();
    await settle();
    assert(route.endsWith(`/tasks/review/${review.id}`) && Number(reviewed) === 1, 'Open an existing review without restarting it');
    render(false, 'workspace', null, 'new-review');
    button('Review task').click();
    await settle();
    assert(Number(reviewed) === 2, 'An unassigned review opens via the existing review service');
    render(true, 'workspace', null, 'stale');
    button('Development task').click();
    await settle();
    route = '';
    render(true, 'workspace', null, 'replacement');
    resolveAssignment?.({ workspaceId: 'workspace', taskId: 'old-selection' });
    await settle();
    assert(route === '', 'A late assignment cannot navigate away from a different PR');
    window.bridge.findPullRequestTask = async () => { throw new Error('Task lookup failed'); };
    button('Review task').click();
    await settle();
    assert(host.querySelector('[role="alert"]')?.textContent === 'Task lookup failed' && !button('Review task').disabled,
      'A failed lookup reports the error and allows retry without starting a duplicate');
    assert(Number(reviewed) === 2, 'Lookup errors must not create reviews');
    return 'Open distinct development and review task conversations';
  }
  finally {
    flushSync(() => root.unmount());
    host.remove();
    window.bridge = original;
  }
}

export async function runTitleEdit(): Promise<string> {
  const original = window.bridge;
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  let detail = { ...reviewDetail(reviewFixture()), viewerCanWrite: true,
    title: 'Fix leaked temp tables on JDBC rollback (ENG-8689: TestPostgreSqlRollbacks.testRollbackMerge)' };
  const writes: { repo: string; number: number; title: string; answer: ReturnType<typeof Promise.withResolvers<void>> }[] = [];
  window.bridge = { ...original,
    getUserProfile: async () => { throw new Error('No viewer in this fixture'); },
    updatePrTitle: (repo, number, title) => {
      const answer = Promise.withResolvers<void>();
      writes.push({ repo, number, title, answer });
      return answer.promise;
    },
  };
  const render = () => flushSync(() => root.render(<PullOverview pr={remotePullRequest(detail, 'viewer')}
    detail={detail} onRefresh={async () => {}} onDetailUpdate={(repo, number, update) => {
      if (repo === detail.repo && number === detail.number) { detail = update(detail); render(); }
    }} />));
  const button = (label: string) => {
    const found = [...host.querySelectorAll<HTMLButtonElement>('button')]
      .find(value => value.getAttribute('aria-label') === label || value.textContent === label);
    assert(found !== undefined, `Missing ${label}`);
    return found;
  };
  const input = () => {
    const found = host.querySelector<HTMLTextAreaElement>('[aria-label="Pull request title"]');
    assert(found !== null, 'Title editor must be open');
    return found;
  };
  const type = async (value: string) => {
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(input(), value);
    input().dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
  };
  try {
    render(); await settle();
    const titleLines = host.querySelector('.pr-title__text')?.getClientRects();
    const lastLine = titleLines?.[titleLines.length - 1];
    const editBounds = button('Edit pull request title').getBoundingClientRect();
    assert(titleLines !== undefined && titleLines.length > 1 && lastLine !== undefined
      && editBounds.left >= lastLine.right && editBounds.left - lastLine.right < 12
      && Math.abs(editBounds.top - lastLine.top) < 8, 'The pencil must follow the last line of a wrapped title');
    button('Edit pull request title').click(); await settle();
    assert(input().value === detail.title && document.activeElement === input(), 'Editing must focus and prefill the current title');
    const editorBounds = input().getBoundingClientRect();
    const numberBounds = host.querySelector('.pr-overview__number')?.getBoundingClientRect();
    assert(editorBounds.width > 200 && numberBounds !== undefined && editorBounds.right <= numberBounds.left,
      'The inline editor must fit beside the PR number in a narrow pane');
    assert(editorBounds.height >= 84 && getComputedStyle(input()).fontSize === '16px'
      && input().scrollHeight <= input().clientHeight, 'The taller editor must show the whole title in a smaller font');
    assert(button('Save').disabled, 'Unchanged titles must not write');
    await type('A longer title '.repeat(20));
    assert(input().getBoundingClientRect().height > editorBounds.height && input().scrollHeight <= input().clientHeight,
      'The title editor must grow to show longer text');
    await type(detail.title);
    await type('   ');
    assert(button('Save').disabled, 'Blank titles must not write');
    await type('Cancel this edit');
    input().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await settle();
    assert(host.querySelector('textarea[aria-label="Pull request title"]') === null && writes.length === 0, 'Escape must cancel without writing');
    button('Edit pull request title').click(); await settle();
    await type('  Updated\ntitle  ');
    input().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', isComposing: true, bubbles: true }));
    await settle();
    assert(writes.length === 0, 'IME composition must not save the title');
    input().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    input().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await settle();
    assert(Number(writes.length) === 1 && writes[0].title === 'Updated title' && writes[0].repo === detail.repo
      && writes[0].number === detail.number && input().disabled, 'Save must trim and send one write for this PR');
    writes[0].answer.reject(new Error('Permission denied'));
    await settle();
    assert(input().value === '  Updated title  ' && host.querySelector('[role="alert"]')?.textContent?.includes('Permission denied') === true,
      'Rejected edits must retain the draft and show the failure');
    button('Save').click(); await settle();
    writes[1].answer.resolve(); await settle();
    assert(detail.title === 'Updated title' && host.querySelector('h1')?.textContent === 'Updated title'
      && host.querySelector('.pr-title-editor') === null, 'Successful saves must update the shared detail and close editing');
    button('Edit pull request title').click(); await settle();
    await type('Stale draft');
    detail = { ...detail, title: 'Changed elsewhere' }; render();
    button('Save').click(); await settle();
    assert(Number(writes.length) === 2 && host.querySelector('[role="alert"]')?.textContent?.includes('changed while you were editing') === true,
      'A refreshed title must not be overwritten by a stale edit');
    button('Cancel').click(); await settle();
    button('Edit pull request title').click(); await settle();
    await type('Late save'); button('Save').click(); await settle();
    detail = { ...detail, number: detail.number + 1, title: 'Another PR' }; render();
    writes[2].answer.resolve(); await settle();
    assert(host.querySelector('h1')?.textContent === 'Another PR' && host.querySelector('.pr-title-editor') === null,
      'A late save must not affect another PR');
    detail = { ...detail, viewerCanWrite: false }; render();
    assert(host.querySelector('[aria-label="Edit pull request title"]') === null, 'Another author requires write permission');
    return 'Inline title edits preserve drafts and scope writes to the selected PR';
  }
  finally {
    flushSync(() => root.unmount());
    host.remove();
    window.bridge = original;
  }
}

export async function runPrivateThreads(answeredBy: 'task' | 'review' = 'task'): Promise<string> {
  setCached('private-thread-drafts:owner/repo#8', undefined);
  const at = '2026-10-01T00:00:00Z';
  const detail: PullRequestDetailDto = {
    id: 8, repo: 'owner/repo', number: 8, title: 'Resolve owners', htmlUrl: null, state: 'open', draft: false,
    mergeable: null, mergeableState: null, author: 'alice', authorAvatarUrl: null, createdAt: at, updatedAt: at,
    mergedAt: null, baseRef: 'main', headRef: 'feature', body: 'Resolves owners.', labels: [], assignees: [], milestone: null,
    reviewers: [], additions: 1, deletions: 1, changedFiles: 1, approvalCount: 0, ciStatus: null, checkRuns: [], unavailableSections: [],
    viewerCanWrite: true,
    recentActivity: [
      { actor: 'carol', eventType: 'commented', timestamp: at, body: 'Should this cache be keyed by id?', githubId: 81, payload },
      { actor: 'alice', eventType: 'commented', timestamp: at, body: 'Good point, checking.', githubId: 82, payload },
    ],
  };
  const task: AgentTaskDto = { ...previewTask('workspace-1', 'development-task'), checkoutRemovedAt: null };
  const review = reviewFixture({ repo: 'owner/repo', number: 8, workspaceId: 'workspace-1' });
  const asked: { taskId: string; reviewId: string | null; key: string; question: string }[] = [];
  const pending = (taskId: string, key: string): TaskDiscussionDto => ({ id: 'thread-1', taskId, turnId: 'review-comment', quote: key, occurrence: 1,
    resolved: false, pending: true, providerLane: 'codex', version: 1, createdAt: at, updatedAt: at, comment: { repo: 'owner/repo', number: 8, key },
    messages: [{ id: 'hidden', author: 'USER', body: SUMMARY_REQUEST, createdAt: at, error: null }] });
  const original = window.bridge;
  const bridge = {
    getUserProfile: async () => ({ login: 'alice', name: null, avatarUrl: '', htmlUrl: '', publicRepos: 0, followers: 0,
      following: 0, bio: null, location: null, company: null, email: null }),
    fetchPullRequestDetailByRef: async () => detail,
    refreshPullRequestDetailByRef: async () => ({ full: true as const, detail, status: null }),
    fetchPrDiffFiles: async () => [file('owner.txt')],
    fetchPrDiffCommits: async () => commits,
    // A pull request with both hosts would show the task's threads, so each run offers only the one it tests.
    findAgentReview: async () => answeredBy === 'review' ? review : null,
    findPullRequestTask: async () => answeredBy === 'task'
      ? { workspaceId: task.workspaceId, taskId: task.id, taskState: 'OPEN', version: 1, linked: true } : null,
    getAgentTask: async () => task,
    listTaskDiscussions: async () => [],
    askTaskDiscussionComment: async request => {
      asked.push({ taskId: request.taskId, reviewId: null, key: request.key, question: request.question });
      return pending(request.taskId, request.key);
    },
    listReviewDiscussions: async () => [],
    askReviewCommentThread: async (reviewId, key, question) => {
      asked.push({ taskId: review.task.id, reviewId, key, question });
      return pending(review.task.id, key);
    },
    listLocalReviewComments: async () => [],
    listWorkspaces: async () => [],
    fetchAttentionPrs: async () => [],
    setPrAttentionHandled: async () => {},
    fetchMergeMode: async () => ({ mergeQueue: false }),
    recordRecentTouch: async () => {},
  } satisfies Pick<Bridge, 'getUserProfile' | 'fetchPullRequestDetailByRef' | 'refreshPullRequestDetailByRef' | 'fetchPrDiffFiles'
    | 'fetchPrDiffCommits' | 'findAgentReview' | 'findPullRequestTask' | 'getAgentTask' | 'listTaskDiscussions' | 'askTaskDiscussionComment'
    | 'listReviewDiscussions' | 'askReviewCommentThread' | 'listLocalReviewComments' | 'listWorkspaces' | 'fetchAttentionPrs'
    | 'setPrAttentionHandled' | 'fetchMergeMode' | 'recordRecentTouch'>;
  Object.assign(window, { bridge });
  const host = document.createElement('div');
  host.style.width = '1200px';
  host.style.height = '800px';
  document.body.append(host);
  const root = createRoot(host);
  try {
    flushSync(() => root.render(<PullsScreen initialPr={{ repo: 'owner/repo', number: 8 }}
      repositoryScope={{ workspaceId: 'workspace-1', repository: 'owner/repo' }} onClose={() => {}} />));
    for (let attempt = 0; attempt < 30 && !(host.querySelector('.pr-meta > .pr-private-toggle')
      && host.textContent?.includes('Should this cache be keyed by id?') && host.querySelectorAll('.pr-ask-agent').length === 1); attempt++) await settle();
    assert(host.querySelector('.pr-meta > .pr-private-toggle') !== null && host.textContent?.includes('Should this cache be keyed by id?') === true,
      `A pull request with a ${answeredBy} to answer must show the private threads toggle beside its labels`);
    const asks = [...host.querySelectorAll<HTMLButtonElement>('.pr-ask-agent')];
    assert(asks.length === 1, `Only carol's comment may offer Ask agent, not the viewer's own; found ${asks.length}`);
    asks[0].click();
    await settle();
    const field = host.querySelector<HTMLTextAreaElement>('[aria-label="Private question to the agent"]');
    assert(field !== null && host.textContent?.includes('on carol’s comment · only the agent sees this') === true,
      'Ask agent must bring carol’s private thread forward');
    field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', metaKey: true, bubbles: true }));
    await settle();
    const expected = answeredBy === 'task'
      ? { taskId: task.id, reviewId: null, key: 'issue_comment:81', question: '' }
      : { taskId: review.task.id, reviewId: review.id, key: 'issue_comment:81', question: '' };
    assert(JSON.stringify(asked) === JSON.stringify([expected]), `The ${answeredBy} must answer the page's question: ${JSON.stringify(asked)}`);
    return answeredBy === 'task'
      ? 'The pull-request page hosts its development task’s private threads on other people’s comments'
      : 'The pull-request page hosts its review’s private threads on other people’s comments';
  }
  finally {
    flushSync(() => root.unmount());
    host.remove();
    window.bridge = original;
  }
}


export async function runPreparePrivateThreads(mode: 'ready' | 'failure' | 'model' | 'workspace' | 'switch' = 'ready'): Promise<string> {
  const repo = `comment-tests/${mode}`;
  const number = 810;
  setCached(`private-thread-drafts:${repo}#${number}`, undefined);
  const review = reviewFixture({ repo, number, workspaceId: 'workspace-1', runs: [], findings: [] });
  const detail: PullRequestDetailDto = { ...reviewDetail(review), id: number, recentActivity: [
    { actor: 'carol', eventType: 'commented', timestamp: null, body: 'The caller already handles this exception.', githubId: 8101, payload },
  ] };
  const nextDetail: PullRequestDetailDto = { ...detail, id: number + 1, number: number + 1, title: 'Another pull request', recentActivity: [
    { actor: 'dave', eventType: 'commented', timestamp: null, body: 'Please check the next PR.', githubId: 8102, payload },
  ] };
  const repository = { owner: 'comment-tests', repo: mode };
  const workspace: WorkspaceDto = {
    id: 'workspace-1', name: 'repo', selectedRepositoryId: 1, selectedOwner: repository.owner, selectedRepo: repository.repo,
    upstreamRepositoryId: null, upstreamOwner: null, upstreamRepo: null, monitoredRepositoryId: 1,
    monitoredOwner: repository.owner, monitoredRepo: repository.repo, topology: 'DIRECT', defaultBranch: 'main',
    upstreamDefaultBranch: null, localRepositoryPath: '/managed/repo', lastCodeSyncAt: null,
    lastMetadataSyncAt: null, displayOrder: 0,
  };
  let watched = false;
  const cacheKey = `private-thread-drafts:${repo}#${number}`;
  const original = window.bridge;
  let prepared: AgentReviewDto | null = null;
  let prepareCount = 0;
  let reviewCount = 0;
  let readyModel = mode !== 'model';
  let release: (() => void) | null = null;
  const asked: { reviewId: string; key: string; question: string }[] = [];
  const saved: TaskDiscussionDto[] = [];
  const catalog = await createPreviewBridge('workspace-1').getModelSettings();
  const bridge = {
    getUserProfile: async () => ({ login: 'alice', name: null, avatarUrl: '', htmlUrl: '', publicRepos: 0, followers: 0,
      following: 0, bio: null, location: null, company: null, email: null }),
    fetchPullRequestDetailByRef: async (_repo: string, requested: number) => requested === number ? detail : nextDetail,
    fetchAttentionPrs: async () => [],
    listWorkspaces: async () => watched ? [workspace] : [],
    searchRepositories: async query => {
      assert(query === repo, 'Repository setup must look up the comment’s own repository');
      return [{ id: 1, repository, fullName: repo, description: null, visibility: 'public', fork: false,
        defaultBranch: 'main', upstream: null, existingWorkspaceId: null }];
    },
    previewWatch: async () => ({ selectedRepositoryId: 1, selectedRepository: repository, visibility: 'public', topology: 'DIRECT' as const,
      upstreamRepositoryId: null, upstreamRepository: null, monitoredRepositoryId: 1, monitoredRepository: repository,
      defaultBranch: 'main', developmentBase: 'origin/main', pushRemote: 'origin', pushRepository: repository,
      pushWritable: true, forkRequired: false, pullRequestBase: repository, fingerprint: 'routing', existingWorkspaceId: null }),
    watchRepository: async command => {
      assert(command.selectedRepositoryId === 1 && command.previewFingerprint === 'routing', 'Watch must use the confirmed repository');
      watched = true;
      return workspace;
    },
    findAgentReview: async (_workspace: string, _repo: string, requested: number) => requested === number ? prepared : null,
    findPullRequestTask: async () => null,
    listLocalReviewComments: async () => [],
    listReviewDiscussions: async () => [...saved],
    fetchMergeMode: async () => ({ mergeQueue: false }),
    setPrAttentionHandled: async () => {},
    recordRecentTouch: async () => {},
    getModelSettings: async () => readyModel ? catalog : { ...catalog, providers: [] },
    prepareReviewWorktree: async (workspaceId: string, requestedRepo: string, requestedNumber: number) => {
      prepareCount++;
      assert(workspaceId === 'workspace-1' && requestedRepo === repo && requestedNumber === number, 'Prepare must target only the selected PR');
      if (mode === 'failure' && prepareCount === 1) throw new Error('Could not fetch the PR branch');
      await new Promise<void>(resolve => { release = resolve; });
      prepared = review;
      return review;
    },
    startAgentReview: async () => { reviewCount++; throw new Error('Asking a comment must never start a full review'); },
    askReviewCommentThread: async (reviewId: string, key: string, question: string) => {
      asked.push({ reviewId, key, question });
      const at = '2026-10-01T00:00:00Z';
      const thread: TaskDiscussionDto = { id: 'prepared-thread', taskId: review.task.id, turnId: 'review-comment', quote: key,
        occurrence: 1, resolved: false, pending: false, providerLane: 'codex', version: asked.length, createdAt: at, updatedAt: at,
        comment: { repo, number, key }, messages: [...(saved[0]?.messages ?? []),
          { id: `q${asked.length}`, author: 'USER', body: question, createdAt: at, error: null },
          { id: `a${asked.length}`, author: 'AGENT', body: 'The exception is handled by the caller.', createdAt: at, error: null }] };
      saved.splice(0, saved.length, thread);
      return thread;
    },
  } satisfies Pick<Bridge, 'getUserProfile' | 'fetchPullRequestDetailByRef' | 'fetchAttentionPrs' | 'listWorkspaces' | 'findAgentReview'
    | 'findPullRequestTask' | 'listLocalReviewComments' | 'listReviewDiscussions' | 'fetchMergeMode' | 'setPrAttentionHandled'
    | 'recordRecentTouch' | 'getModelSettings' | 'prepareReviewWorktree' | 'startAgentReview' | 'askReviewCommentThread'
    | 'searchRepositories' | 'previewWatch' | 'watchRepository'>;
  Object.assign(window, { bridge });
  const host = document.createElement('div');
  host.style.cssText = 'width:1250px;height:850px';
  document.body.append(host);
  const root = createRoot(host);
  const mount = (selected = number) => flushSync(() => root.render(<PullsScreen initialPr={{ repo, number: selected }}
    repositoryScope={mode === 'workspace' ? undefined : { workspaceId: 'workspace-1', repository: repo }} onClose={() => {}} />));
  const waitFor = async (condition: () => boolean, message: string) => {
    for (let attempt = 0; attempt < 60 && !condition(); attempt++) await settle();
    assert(condition(), message);
  };
  const field = () => {
    const input = host.querySelector<HTMLTextAreaElement>('[aria-label="Private question to the agent"]');
    assert(input !== null, 'The private question must remain editable');
    return input;
  };
  const type = (value: string) => {
    const input = field();
    changeInput(input, value);
  };
  const send = () => field().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', metaKey: true, bubbles: true }));
  try {
    mount();
    await waitFor(() => host.querySelector('.pr-ask-agent') !== null, 'Ask agent must be visible without a task or review');
    host.querySelector<HTMLButtonElement>('.pr-ask-agent')?.click();
    await waitFor(() => host.querySelector('textarea') !== null && !host.textContent?.includes('Loading PR context…'), 'The inline composer must open');
    type('Does their explanation address my concern?');
    await settle();
    assert(prepareCount === 0 && asked.length === 0 && reviewCount === 0, 'Opening and typing must not prepare or invoke an agent');
    assert(host.querySelector('.pr-private-fold__body')?.textContent?.includes('The caller already handles this exception.'), 'The source comment must remain visible');
    if (mode === 'workspace') {
      assert(host.textContent?.includes(`Watch ${repo} to ask the agent about its code.`), 'Missing workspace must be explained');
      assert([...host.querySelectorAll('button')].some(button => button.textContent === 'Watch repository'), 'Repository setup must be actionable');
      send(); await settle();
      assert(prepareCount === 0 && field().value.includes('my concern'), 'Setup must block sending without losing the question');
      const watch = [...host.querySelectorAll('button')].find(button => button.textContent === 'Watch repository');
      watch?.click();
      await waitFor(() => document.querySelector('.modal-preview__confirm') !== null, 'Setup must open the existing watch confirmation');
      assert(!watched && prepareCount === 0, 'Opening setup must not watch the repository or create agent context');
      document.querySelector<HTMLButtonElement>('.modal-preview__confirm')?.click();
      await waitFor(() => document.querySelector('.modal') === null && !host.textContent?.includes(`Watch ${repo}`)
        && !host.textContent?.includes('Loading PR context…'), 'Confirmed setup must refresh the PR without navigating away');
      assert(watched && field().value === 'Does their explanation address my concern?', 'Successful setup must preserve the question');
    }
    send();
    if (mode === 'failure' || mode === 'model') {
      await waitFor(() => host.textContent?.includes(mode === 'failure' ? 'Could not fetch the PR branch' : 'Choose a Review model') === true,
        'Failed setup must appear beside the question');
      assert(field().value === 'Does their explanation address my concern?', 'Failure must keep the exact question');
      if (mode === 'model') {
        assert(prepareCount === 0 && host.querySelector('a[href="#/settings/agents/models"]') !== null, 'Model setup must precede preparation and link to settings');
        readyModel = true;
        flushSync(() => root.render(null));
        mount();
        await waitFor(() => host.querySelector('[aria-label="Private question to the agent"]') !== null
          && !host.textContent?.includes('Loading PR context…'), 'Returning from settings must reopen the draft');
        assert(field().value === 'Does their explanation address my concern?', 'Navigation to model setup must preserve the draft');
      }
      send();
    }
    await waitFor(() => release !== null, 'The first question must prepare a working copy');
    assert(host.textContent?.includes('Preparing PR context…'), 'Preparation must show progress in the private composer');
    assert(asked.length === 0 && reviewCount === 0, 'Preparation must not start a review or send before context is ready');
    if (mode === 'switch') {
      mount(number + 1);
      await waitFor(() => host.textContent?.includes('Please check the next PR.') === true, 'The second PR must open while the first prepares');
    }
    const finish = release as (() => void) | null;
    assert(finish !== null, 'Preparation resolver must exist');
    finish();
    await waitFor(() => asked.length === 1, 'Prepared context must receive the first question exactly once');
    assert(asked[0].key === 'issue_comment:8101' && asked[0].question === 'Does their explanation address my concern?', 'The original comment must receive the question');
    if (mode === 'switch') {
      assert(!host.textContent?.includes('The exception is handled by the caller.'), 'A late answer must not enter the next PR');
      assert(host.textContent?.includes('Please check the next PR.'), 'Late preparation must not change the selected PR');
      return 'Late preparation remains bound to its original PR';
    }
    await waitFor(() => host.textContent?.includes('The exception is handled by the caller.') === true, 'The first answer must appear inline');
    flushSync(() => root.render(null));
    mount();
    await waitFor(() => host.textContent?.includes('The exception is handled by the caller.') === true, 'Saved discussion must reopen after navigation');
    type('Which caller?'); await settle(); send();
    await waitFor(() => asked.length === 2, 'Follow-up must reuse the prepared discussion');
    assert(Number(prepareCount) === (mode === 'failure' ? 2 : 1) && reviewCount === 0, 'Follow-ups must reuse context without starting a review');
    assert(saved[0].messages.length === 4, 'The discussion must retain both exchanges');
    return `Private comment ${mode} flow prepares once, keeps drafts and reuses its discussion`;
  }
  finally {
    flushSync(() => root.unmount()); host.remove(); window.bridge = original;
    setCached(cacheKey, undefined);
  }
}
