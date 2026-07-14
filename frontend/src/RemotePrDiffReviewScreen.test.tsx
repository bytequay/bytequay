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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import RemotePrDiffReviewScreen from './RemotePrDiffReviewScreen';
import type { DiffFileDto, PullRequestCommitDto, PullRequestDetailDto, PullRequestDto, ReviewFindingDto, ReviewThreadDto } from './types';

// React 19 enforces this flag before async act() works.
(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

function makePr(overrides: Partial<PullRequestDto> = {}): PullRequestDto {
  return {
    id: 1,
    repo: 'trinodb/trino',
    number: 42,
    title: 'Test PR',
    author: 'octocat',
    htmlUrl: 'https://github.com/trinodb/trino/pull/42',
    createdAt: '2026-04-29T10:00:00Z',
    updatedAt: '2026-04-29T11:00:00Z',
    origin: 'REVIEW_REQUESTED',
    labels: [],
    labelColors: null,
    draft: false,
    viewedAt: null,
    reviewedAt: null,
    handledAction: null,
    requestedReviewers: [],
    ciStatus: 'PASSING',
    additions: 10,
    deletions: 5,
    commentCount: 0,
    attentionReason: null,
    state: 'open',
    closedAt: null,
    mergedAt: null,
    mergeable: true,
    mergeableState: 'clean',
    headPushedAt: '2026-04-29T11:00:00Z',
    reviewerVerdicts: {},
    snoozedUntil: null,
    snoozeWakeReason: null,
    ...overrides,
  };
}

function makeDetail(overrides: Partial<PullRequestDetailDto> = {}): PullRequestDetailDto {
  return {
    repo: 'trinodb/trino',
    number: 42,
    body: 'PR body',
    labels: [],
    draft: false,
    mergeable: true,
    mergeableState: 'clean',
    additions: 10,
    deletions: 5,
    changedFiles: 1,
    approvalCount: 0,
    changesRequestedCount: 0,
    pendingReviewerCount: 0,
    requestedReviewers: [],
    ciStatus: 'PASSING',
    files: [{ filename: 'src/foo.ts', additions: 10, deletions: 5, status: 'modified' }],
    recentActivity: [],
    checkRuns: [],
    reviewThreads: [],
    linkedIssues: [],
    viewerCanWrite: true,
    headRef: null,
    headRepo: null,
    baseRef: null,
    baseRepo: null,
    mergeQueueState: null,
    mergeQueueEnabled: false,
    ...overrides,
  };
}

function makeDiffFile(overrides: Partial<DiffFileDto> = {}): DiffFileDto {
  return {
    filename: 'src/foo.ts',
    status: 'modified',
    additions: 1,
    deletions: 0,
    patch: '@@ -1,1 +1,1 @@\n+new line',
    ...overrides,
  };
}

function makeCommit(overrides: Partial<PullRequestCommitDto> = {}): PullRequestCommitDto {
  return {
    sha: 'abc123',
    authorLogin: 'octocat',
    authorName: 'Octocat',
    authoredAt: '2026-04-29T11:00:00Z',
    message: 'Update foo',
    ...overrides,
  };
}

function makeThread(overrides: Partial<ReviewThreadDto> = {}): ReviewThreadDto {
  return {
    rootGithubId: 5001,
    filePath: 'src/foo.ts',
    line: 1,
    side: 'RIGHT',
    diffHunk: '@@ -1,1 +1,1 @@\n+new line',
    messages: [{
      githubId: 1001,
      author: 'reviewer',
      body: 'existing thread comment',
      createdAt: '2026-04-29T11:00:00Z',
      reactions: null,
      reviewId: null,
      authorAssociation: 'MEMBER',
    }],
    resolved: false,
    outdated: false,
    startLine: null,
    startSide: null,
    originalLine: null,
    originalStartLine: null,
    ...overrides,
  };
}

function bridgeStub(detail: PullRequestDetailDto, options: {
  files?: DiffFileDto[];
  commits?: PullRequestCommitDto[];
  reviewDetail?: unknown;
} = {}) {
  return {
    markPrViewed: vi.fn().mockResolvedValue(undefined),
    fetchPullRequestDetail: vi.fn().mockResolvedValue(detail),
    refreshPullRequestDetail: vi.fn().mockResolvedValue(detail),
    fetchPrDiffFiles: vi.fn().mockResolvedValue(options.files ?? []),
    fetchPrCommits: vi.fn().mockResolvedValue(options.commits ?? []),
    fetchPrCommitDiff: vi.fn().mockResolvedValue([]),
    fetchFileBlob: vi.fn().mockResolvedValue({ lines: [] }),
    createInlineReviewComment: vi.fn().mockResolvedValue(undefined),
    replyToReviewThread: vi.fn().mockResolvedValue(undefined),
    setReviewThreadResolved: vi.fn().mockResolvedValue(undefined),
    stageReviewComment: vi.fn().mockResolvedValue({
      id: 1,
      prId: 1,
      summary: null,
      providerId: 'anthropic',
      model: 'claude',
      headSha: 'abc123',
      status: 'DRAFT',
      createdAt: '2026-07-14T00:00:00Z',
      updatedAt: '2026-07-14T00:00:00Z',
      comments: [],
    }),
    polishCommentText: vi.fn(),
    listAiProviders: vi.fn().mockResolvedValue([]),
    getLatestAiReview: vi.fn().mockResolvedValue(null),
    getAiReviewStatus: vi.fn().mockResolvedValue({ state: 'IDLE', error: null }),
    getReviewPassForPr: vi.fn().mockResolvedValue(options.reviewDetail ?? null),
    editReviewFinding: vi.fn().mockResolvedValue(options.reviewDetail ?? { pass: { id: 'pass-1' }, findings: [] }),
    dropReviewFinding: vi.fn().mockResolvedValue({ pass: { id: 'pass-1' }, findings: [] }),
    addReviewFinding: vi.fn().mockResolvedValue(options.reviewDetail ?? { pass: { id: 'pass-1' }, findings: [] }),
    // Used by the durable AgentReview launcher on the diff page.
    getPrForRepoPull: vi.fn().mockResolvedValue({ id: 'local-pr-42' }),
    startAgentReview: vi.fn().mockResolvedValue({
      review: { owner_thread_id: 'review-owner-thread' },
    }),
  };
}

/** Minimal panel finding for the review-overlay lookup — only the fields
 *  RemotePrDiffReviewScreen reads (status / path / line / severity / body). */
function panelFinding(overrides: Partial<ReviewFindingDto> = {}): ReviewFindingDto {
  return {
    id: 'f',
    reviewPassId: 'pass-1',
    path: null,
    line: null,
    severity: 'MAJOR',
    status: 'AGREED',
    body: '',
    resolution: null,
    postedCommentId: null,
    createdAt: '2026-05-22T12:00:00Z',
    ...overrides,
  };
}

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn();
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => {
    root.unmount();
  });
  container.remove();
});

async function render(detail = makeDetail(), props: {
  onApprove?: (prId: number, repo: string, number: number) => Promise<void>;
  onBack?: () => void;
  files?: DiffFileDto[];
  commits?: PullRequestCommitDto[];
  reviewDetail?: unknown;
  onStartReview?: (threadId: string) => void;
} = {}) {
  const bridge = bridgeStub(detail, {
    files: props.files, commits: props.commits, reviewDetail: props.reviewDetail,
  });
  (window as unknown as { bridge: ReturnType<typeof bridgeStub> }).bridge = bridge;
  await act(async () => {
    root.render(
      <RemotePrDiffReviewScreen
        pr={makePr()}
        onBack={props.onBack ?? vi.fn()}
        onApprove={props.onApprove}
        workspaceId="ws-1"
        onStartReview={props.onStartReview}
      />,
    );
  });
  await act(async () => { await Promise.resolve(); });
  await act(async () => { await Promise.resolve(); });
  await act(async () => { await Promise.resolve(); });
  return bridge;
}

function updateTextarea(textarea: HTMLTextAreaElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
  setter?.call(textarea, value);
  textarea.dispatchEvent(new Event('input', { bubbles: true }));
}

describe('RemotePrDiffReviewScreen freshness', () => {
  it('uses force-refresh reconciliation after approval', async () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    const onBack = vi.fn();
    const bridge = await render(makeDetail(), { onApprove, onBack });

    const approve = Array.from(container.querySelectorAll('button'))
      .find(el => el.textContent === 'Approve');
    expect(approve).toBeTruthy();

    await act(async () => {
      approve!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    expect(onApprove).toHaveBeenCalledWith(1, 'trinodb/trino', 42);
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42);
    expect(bridge.fetchPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42);
    expect(onBack).toHaveBeenCalled();
  });

  it('stages an inline comment into the local draft without posting to GitHub', async () => {
    const bridge = await render(makeDetail(), {
      files: [makeDiffFile()],
      commits: [makeCommit()],
    });

    const row = container.querySelector<HTMLElement>('[data-anchor="src/foo.ts:RIGHT:1"]');
    expect(row).toBeTruthy();

    await act(async () => {
      row!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    const textarea = container.querySelector<HTMLTextAreaElement>('.diff-inline-composer__input');
    expect(textarea).toBeTruthy();
    await act(async () => {
      updateTextarea(textarea!, 'please adjust this line');
    });

    const stage = Array.from(container.querySelectorAll('button'))
      .find(el => el.textContent === 'Add review comment');
    expect(stage).toBeTruthy();

    await act(async () => {
      stage!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    // Inline comments stage into the local review draft — they must not be
    // posted to GitHub until the user submits the batched review. An
    // immediate post would leave a lingering pending-review comment that
    // duplicates when the batched review is submitted.
    expect(bridge.stageReviewComment).toHaveBeenCalledWith(
      expect.objectContaining({
        repo: 'trinodb/trino',
        number: 42,
        filePath: 'src/foo.ts',
        line: 1,
        side: 'RIGHT',
        body: 'please adjust this line',
      }),
    );
    expect(bridge.createInlineReviewComment).not.toHaveBeenCalled();
  });

  it('keeps review-thread replies optimistic without fetching stale detail', async () => {
    const bridge = await render(makeDetail({ reviewThreads: [makeThread()] }), {
      files: [makeDiffFile()],
      commits: [makeCommit()],
    });

    const openReply = Array.from(container.querySelectorAll('button'))
      .find(el => el.textContent === 'Write a reply…');
    expect(openReply).toBeTruthy();

    await act(async () => {
      openReply!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    const textarea = container.querySelector<HTMLTextAreaElement>('.diff-thread__reply-input');
    expect(textarea).toBeTruthy();
    await act(async () => {
      updateTextarea(textarea!, 'thanks for the context');
    });

    const reply = Array.from(container.querySelectorAll('button'))
      .find(el => el.textContent === 'Reply');
    expect(reply).toBeTruthy();

    await act(async () => {
      reply!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    expect(bridge.replyToReviewThread).toHaveBeenCalledWith('trinodb/trino', 42, 5001, 'thanks for the context');
    expect(bridge.refreshPullRequestDetail).not.toHaveBeenCalled();
    expect(bridge.fetchPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42);
    expect(container.innerHTML).toContain('thanks for the context');
  });

  it('keeps review-thread resolution optimistic and refreshes resolver state', async () => {
    const bridge = await render(makeDetail({ reviewThreads: [makeThread()] }), {
      files: [makeDiffFile()],
      commits: [makeCommit()],
    });

    const resolve = Array.from(container.querySelectorAll('button'))
      .find(el => el.textContent === 'Resolve');
    expect(resolve).toBeTruthy();

    await act(async () => {
      resolve!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    expect(bridge.setReviewThreadResolved).toHaveBeenCalledWith('trinodb/trino', 1, 5001, true);
    expect(bridge.refreshPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42);
    expect(bridge.fetchPullRequestDetail).toHaveBeenCalledWith('trinodb/trino', 42);
    expect(container.innerHTML).toContain('Resolved');
    expect(container.querySelector('.diff-thread--resolved')).toBeTruthy();
  });
});

describe('RemotePrDiffReviewScreen panel-findings overlay', () => {
  it('lists whole-PR (path-less) agreed findings in the sidebar, not just line-anchored ones', async () => {
    // Regression: "View findings on the diff" showed nothing when the
    // panel's agreed findings had no file path. Whole-PR findings can't
    // anchor to a diff line but must still appear in the sidebar list.
    await render(makeDetail(), {
      files: [makeDiffFile()],
      commits: [makeCommit()],
      reviewDetail: {
        pass: { id: 'pass-1' },
        findings: [
          panelFinding({ id: 'whole', status: 'AGREED', path: null, line: null,
            body: 'Overall the error handling is inconsistent.' }),
          panelFinding({ id: 'anchored', status: 'ARBITRATED', path: 'src/foo.ts', line: 1,
            body: 'Null deref here.' }),
        ],
      },
    });

    // The sidebar "Panel findings" list shows both — the whole-PR one
    // labelled "whole PR", the anchored one by its file.
    expect(container.textContent).toContain('Panel findings');
    expect(container.textContent).toContain('Overall the error handling is inconsistent.');
    expect(container.textContent).toContain('Null deref here.');
    expect(container.textContent).toContain('whole PR');
  });

  it('drops findings that are neither AGREED nor ARBITRATED from the overlay', async () => {
    await render(makeDetail(), {
      files: [makeDiffFile()],
      commits: [makeCommit()],
      reviewDetail: {
        pass: { id: 'pass-1' },
        findings: [
          panelFinding({ id: 'disputed', status: 'DISPUTED', path: null, line: null,
            body: 'Still contested.' }),
          panelFinding({ id: 'dropped', status: 'DROPPED', path: 'src/foo.ts', line: 1,
            body: 'Discarded.' }),
        ],
      },
    });

    expect(container.textContent).not.toContain('Still contested.');
    expect(container.textContent).not.toContain('Discarded.');
  });

  it('removes a panel finding from the sidebar via the card ✕ button', async () => {
    const bridge = await render(makeDetail(), {
      files: [makeDiffFile()],
      commits: [makeCommit()],
      reviewDetail: {
        pass: { id: 'pass-1' },
        findings: [
          panelFinding({ id: 'f-keep', status: 'AGREED', path: 'src/foo.ts', line: 1,
            body: 'Drop me from the sidebar.' }),
        ],
      },
    });

    // Target the sidebar card's remove button specifically (the inline
    // diff-row overlay also renders a remove button for the same finding,
    // but that one only hides itself without updating the shared list).
    const sidebar = container.querySelector('.ai-sidebar');
    expect(sidebar).toBeTruthy();
    const removeBtn = Array.from(sidebar!.querySelectorAll('button'))
      .find(b => b.getAttribute('title')?.startsWith('Remove this finding'));
    expect(removeBtn).toBeTruthy();

    await act(async () => {
      removeBtn!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });
    await act(async () => { await Promise.resolve(); });
    await act(async () => { await Promise.resolve(); });

    expect(bridge.dropReviewFinding).toHaveBeenCalledWith('pass-1', 'f-keep');
    // The drop returns an empty finding set, so the card is gone.
    expect(container.textContent).not.toContain('Drop me from the sidebar.');
  });

  it('starts the durable agent review from a PR-scoped dialog', async () => {
    const onStartReview = vi.fn();
    await render(makeDetail(), {
      files: [makeDiffFile()],
      commits: [makeCommit()],
      onStartReview,
    });

    const runBtn = Array.from(container.querySelectorAll('button'))
      .find(b => b.textContent?.includes('Review with agent'));
    expect(runBtn).toBeTruthy();

    await act(async () => {
      runBtn!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    const dialog = container.querySelector('[aria-label="Start agent review"]');
    expect(dialog).toBeTruthy();
    expect(dialog!.textContent).toContain('#42');

    const start = Array.from(dialog!.querySelectorAll('button'))
      .find(button => button.textContent === 'Start review');
    await act(async () => {
      start!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });
    await act(async () => { await Promise.resolve(); });

    expect(window.bridge.getPrForRepoPull).toHaveBeenCalledWith('trinodb', 'trino', 42);
    expect(window.bridge.startAgentReview).toHaveBeenCalledWith('local-pr-42', {
      runner: undefined,
      workspaceId: 'ws-1',
    });
    expect(onStartReview).toHaveBeenCalledWith('review-owner-thread');
  });

  it('exposes an Add-finding affordance even when there are no findings yet', async () => {
    await render(makeDetail(), {
      files: [makeDiffFile()],
      commits: [makeCommit()],
      reviewDetail: { pass: { id: 'pass-1' }, findings: [] },
    });

    // Section renders (passId known) with the add button despite 0 findings.
    expect(container.textContent).toContain('Panel findings');
    const addBtn = Array.from(container.querySelectorAll('button'))
      .find(b => b.textContent?.includes('+ Add'));
    expect(addBtn).toBeTruthy();
  });
});

describe('RemotePrDiffReviewScreen commits column', () => {
  const commits = [
    makeCommit({ sha: 'c0', message: 'First commit' }),
    makeCommit({ sha: 'c1', message: 'Second commit' }),
    makeCommit({ sha: 'c2', message: 'Third commit' }),
  ];

  function commitRows() {
    return Array.from(container.querySelectorAll<HTMLElement>(
      '.diff-viewer__commit-row:not(.diff-viewer__commit-all)'));
  }

  it('renders a row per commit plus the All-commits affordance', async () => {
    await render(makeDetail(), { files: [makeDiffFile()], commits });
    expect(commitRows()).toHaveLength(3);
    expect(container.textContent).toContain('First commit');
    expect(container.textContent).toContain('Third commit');
    const all = container.querySelector('.diff-viewer__commit-all');
    expect(all?.textContent).toContain('All 3 commits');
  });

  it('selects a single commit on a plain click', async () => {
    const bridge = await render(makeDetail(), { files: [makeDiffFile()], commits });
    await act(async () => {
      commitRows()[1].dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });
    expect(bridge.fetchPrCommitDiff).toHaveBeenCalledWith('trinodb/trino', 42, 'c1');
  });

  it('selects a contiguous range on shift-click and shows the footer summary', async () => {
    const bridge = await render(makeDetail(), { files: [makeDiffFile()], commits });
    await act(async () => {
      commitRows()[0].dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });
    await act(async () => {
      commitRows()[2].dispatchEvent(new MouseEvent('click', { bubbles: true, shiftKey: true }));
    });
    await act(async () => { await Promise.resolve(); });

    // The whole c0..c2 run is selected → the union fetches each commit's diff.
    expect(bridge.fetchPrCommitDiff).toHaveBeenCalledWith('trinodb/trino', 42, 'c0');
    expect(bridge.fetchPrCommitDiff).toHaveBeenCalledWith('trinodb/trino', 42, 'c1');
    expect(bridge.fetchPrCommitDiff).toHaveBeenCalledWith('trinodb/trino', 42, 'c2');
    expect(container.textContent).toContain('Reviewing 3 commits');
  });

  it('returns to the cumulative diff via the All-commits affordance', async () => {
    const bridge = await render(makeDetail(), { files: [makeDiffFile()], commits });
    await act(async () => {
      commitRows()[1].dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });

    bridge.fetchPrDiffFiles.mockClear();
    const all = container.querySelector<HTMLElement>('.diff-viewer__commit-all');
    await act(async () => {
      all!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await act(async () => { await Promise.resolve(); });
    expect(bridge.fetchPrDiffFiles).toHaveBeenCalledWith('trinodb/trino', 42);
  });
});
